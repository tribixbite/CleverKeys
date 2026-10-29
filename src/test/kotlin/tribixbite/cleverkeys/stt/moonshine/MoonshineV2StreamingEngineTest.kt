package tribixbite.cleverkeys.stt.moonshine

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume.assumeTrue
import org.junit.Test
import tribixbite.cleverkeys.stt.SpeechEngineOptions
import tribixbite.cleverkeys.stt.SpeechEngineRegistry
import tribixbite.cleverkeys.stt.SpeechHypothesis
import tribixbite.cleverkeys.stt.VoicePackManager
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The real Moonshine v2 tiny-streaming model on the app's ORT 1.21.1 natives (pure JVM, the
 * bionic `.so` from `extractOrtNative`), through the same path the keyboard uses: the voice
 * pack ZIP is imported by [VoicePackManager], the engine is resolved by [SpeechEngineRegistry],
 * and the audio is streamed in small chunks.
 *
 * Model weights are never committed. The test runs when these exist locally, and is skipped
 * (Assume) otherwise — the same gating as the androidTest `ctc_bench` encoders:
 *  - the pack ZIP built by `scripts/build_voice_pack.py`: `-PvoicePack=<zip>` or
 *    `build/voicepacks/voicepack-moonshine-v2-tiny-en.zip`;
 *  - LibriSpeech test-clean clips (CC BY 4.0) with reference text: `-PvoiceSamples=<dir>` or
 *    `~/.cache/cleverkeys-test/voice-samples/` holding `<id>.wav` (16 kHz mono PCM16) and
 *    `<id>.txt`.
 */
class MoonshineV2StreamingEngineTest {

    private val pack: File = File(System.getProperty("voicePack") ?: "build/voicepacks/voicepack-moonshine-v2-tiny-en.zip")
    private val samples: File = File(
        System.getProperty("voiceSamples") ?: "${System.getProperty("user.home")}/.cache/cleverkeys-test/voice-samples"
    )

    private fun clips(): List<Pair<File, String>> =
        samples.listFiles().orEmpty().filter { it.extension == "wav" }.sortedBy { it.name }
            .mapNotNull { wav -> File(wav.path.removeSuffix(".wav") + ".txt").takeIf { it.isFile }?.let { wav to it.readText().trim() } }

    @Test
    fun streamsLibriSpeechClipsThroughAnImportedPack() {
        assumeTrue("voice pack not built ($pack)", pack.isFile)
        val clips = clips()
        assumeTrue("no sample clips in $samples", clips.isNotEmpty())

        val scratch = kotlin.io.path.createTempDirectory("ck-voice-engine").toFile()
        try {
            val manager = VoicePackManager(File(scratch, "voicepacks"), File(scratch, "cache").apply { mkdirs() }, Int.MAX_VALUE)
            val imported = manager.import(pack.inputStream())
            assertWithMessage("import: $imported").that(imported).isInstanceOf(VoicePackManager.ImportResult.Success::class.java)
            val installed = manager.activePack("en")!!
            val resolution = SpeechEngineRegistry.DEFAULT.resolve(installed.manifest) as SpeechEngineRegistry.Resolution.Supported
            resolution.factory.load(installed.dir, SpeechEngineOptions(threads = 2)).use { engine ->
                println("voice engine: ${engine.memoryReport()}")
                var errors = 0
                var words = 0
                for ((wav, reference) in clips) {
                    val pcm = readWav(wav)
                    val t0 = System.nanoTime()
                    val session = engine.newSession()
                    val partials = ArrayList<SpeechHypothesis>()
                    // 320 ms pieces, like AudioRecord reads: exercises the engine's own 1 s chunking.
                    var off = 0
                    while (off < pcm.size) {
                        val n = minOf(5_120, pcm.size - off)
                        session.accept(pcm.copyOfRange(off, off + n))?.let { partials += it }
                        off += n
                    }
                    val final = session.finish()
                    session.close()
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    val e = wordErrors(reference, final.text)
                    errors += e
                    words += norm(reference).size
                    println("${wav.name} ${pcm.size / 16_000.0}s ${ms}ms passes=${partials.size} errors=$e/${norm(reference).size} | ${final.text}")
                    assertThat(final.isFinal).isTrue()
                    assertWithMessage("a clip over 2 s yields partials").that(partials).isNotEmpty()
                    for (i in 1 until partials.size) {
                        assertWithMessage("stable words never shrink").that(partials[i].stableWordCount)
                            .isAtLeast(partials[i - 1].stableWordCount)
                    }
                    // Committed words of the last partial are a prefix of the final text.
                    val committed = partials.last().stableWords
                    assertThat(final.words.take(committed.size)).isEqualTo(committed)
                }
                val wer = errors.toDouble() / words
                println("voice engine WER $errors/$words = ${"%.4f".format(wer)}")
                // Research §5.2: 5.0 % on the 5 s set, 3.25 % on the 15 s set (streaming).
                assertWithMessage("streaming WER").that(wer).isLessThan(0.10)
            }
        } finally {
            scratch.deleteRecursively()
        }
    }

    @Test
    fun cancelStopsASessionAndLaterAudioIsIgnored() {
        assumeTrue("voice pack not built ($pack)", pack.isFile)
        val clip = clips().firstOrNull()
        assumeTrue("no sample clips in $samples", clip != null)
        val scratch = kotlin.io.path.createTempDirectory("ck-voice-cancel").toFile()
        try {
            val manager = VoicePackManager(File(scratch, "voicepacks"), File(scratch, "cache").apply { mkdirs() }, Int.MAX_VALUE)
            manager.import(pack.inputStream())
            val installed = manager.activePack("en")!!
            MoonshineV2StreamingEngine.Factory.load(installed.dir, SpeechEngineOptions()).use { engine ->
                val pcm = readWav(clip!!.first)
                val s = engine.newSession()
                s.accept(pcm.copyOfRange(0, 32_000))
                s.cancel()
                assertThat(s.isCancelled).isTrue()
                assertThat(s.accept(pcm)).isNull()
                assertThat(s.finish().text).isEmpty()
                // The engine itself is still usable for a new session.
                val again = engine.newSession()
                again.accept(pcm.copyOfRange(0, 32_000))
                assertThat(again.finish().text).isNotEmpty()
            }
        } finally {
            scratch.deleteRecursively()
        }
    }

    /**
     * A recogniser that fell behind real time gets the backlog in ONE accept (the buffered
     * microphone drains everything queued). Decoding it once — not once per second of
     * backlog — is what lets it catch up: each decode re-reads the whole utterance, so
     * per-second decoding of a backlog only grows it (Saga 2026-10-29: passes 2–6 s apart
     * after ~40 s of speech). The text must be as good as streaming it.
     */
    @Test
    fun aBacklogDeliveredInOneAcceptIsDecodedOnce() {
        assumeTrue("voice pack not built ($pack)", pack.isFile)
        val clips = clips()
        assumeTrue("no sample clips in $samples", clips.isNotEmpty())
        val scratch = kotlin.io.path.createTempDirectory("ck-voice-backlog").toFile()
        try {
            val manager = VoicePackManager(File(scratch, "voicepacks"), File(scratch, "cache").apply { mkdirs() }, Int.MAX_VALUE)
            manager.import(pack.inputStream())
            val installed = manager.activePack("en")!!
            MoonshineV2StreamingEngine.Factory.load(installed.dir, SpeechEngineOptions(threads = 2)).use { engine ->
                var errors = 0
                var words = 0
                for ((wav, reference) in clips.take(4)) {
                    val pcm = readWav(wav)
                    val s = engine.newSession() as MoonshineV2StreamingEngine.Session
                    // 1 s of live audio, then a 3.5 s backlog in one call.
                    s.accept(pcm.copyOfRange(0, 16_000))
                    assertThat(s.decodePasses).isEqualTo(1)
                    val backlog = s.accept(pcm.copyOfRange(16_000, minOf(pcm.size, 72_000)))
                    assertWithMessage("a backlog of whole chunks yields one partial").that(backlog).isNotNull()
                    assertWithMessage("the backlog is decoded once").that(s.decodePasses).isEqualTo(2)
                    if (pcm.size > 72_000) s.accept(pcm.copyOfRange(72_000, pcm.size))
                    val final = s.finish()
                    s.close()
                    errors += wordErrors(reference, final.text)
                    words += norm(reference).size
                    println("backlog ${wav.name} decodes=${s.decodePasses} | ${final.text}")
                }
                assertWithMessage("WER with a coalesced backlog").that(errors.toDouble() / words).isLessThan(0.10)
            }
        } finally {
            scratch.deleteRecursively()
        }
    }

    /**
     * The engine's declared [MoonshineV2StreamingEngine.maxUtteranceSeconds] is where the
     * keyboard stops listening by itself, so an utterance that long must still come out right.
     * LibriSpeech clips joined with 0.8 s pauses, streamed in 320 ms pieces. Beyond the bound
     * the decoder loops (46 s: "some use of some use of…", 151 errors for 125 words).
     */
    @Test
    fun anUtteranceAsLongAsTheEngineAllowsStaysCoherent() {
        assumeTrue("voice pack not built ($pack)", pack.isFile)
        val clips = clips()
        assumeTrue("no sample clips in $samples", clips.isNotEmpty())
        val scratch = kotlin.io.path.createTempDirectory("ck-voice-long").toFile()
        try {
            val manager = VoicePackManager(File(scratch, "voicepacks"), File(scratch, "cache").apply { mkdirs() }, Int.MAX_VALUE)
            manager.import(pack.inputStream())
            val installed = manager.activePack("en")!!
            MoonshineV2StreamingEngine.Factory.load(installed.dir, SpeechEngineOptions(threads = 2)).use { engine ->
                val limitSamples = engine.maxUtteranceSeconds * 16_000
                val gap = FloatArray(12_800)
                var pcm = FloatArray(0)
                val used = ArrayList<String>()
                for ((wav, reference) in clips) {
                    if (pcm.size >= limitSamples) break
                    pcm = if (pcm.isEmpty()) readWav(wav) else pcm + gap + readWav(wav)
                    used += reference
                }
                assumeTrue("the sample clips are shorter than the bound", pcm.size >= limitSamples)
                val s = engine.newSession()
                var off = 0
                while (off < pcm.size) {
                    val k = minOf(5_120, pcm.size - off)
                    s.accept(pcm.copyOfRange(off, off + k))
                    off += k
                }
                val final = s.finish()
                s.close()
                val reference = used.joinToString(" ")
                val errors = wordErrors(reference, final.text)
                val words = norm(reference).size
                println("long ${pcm.size / 16_000.0}s errors=$errors/$words | ${final.text}")
                assertWithMessage("WER at ${pcm.size / 16_000.0} s: ${final.text}").that(errors.toDouble() / words).isLessThan(0.10)
            }
        } finally {
            scratch.deleteRecursively()
        }
    }

    @Test
    fun loadingAPackWithAMissingFileFailsWithASpeechEngineException() {
        val dir = kotlin.io.path.createTempDirectory("ck-voice-missing").toFile()
        try {
            val e = runCatching { MoonshineV2StreamingEngine.Factory.load(dir, SpeechEngineOptions()) }.exceptionOrNull()
            assertThat(e).isInstanceOf(tribixbite.cleverkeys.stt.SpeechEngineException::class.java)
            assertThat(e!!.message).contains("missing")
        } finally {
            dir.deleteRecursively()
        }
    }

    companion object {
        /** 16 kHz mono PCM16 WAV → floats in [-1, 1]. Finds the `data` chunk; rejects other formats. */
        fun readWav(file: File): FloatArray {
            val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            require(String(ByteArray(4).also { b.get(it) }) == "RIFF")
            b.int
            require(String(ByteArray(4).also { b.get(it) }) == "WAVE")
            var channels = 0
            var rate = 0
            var bits = 0
            while (b.remaining() >= 8) {
                val id = String(ByteArray(4).also { b.get(it) })
                val size = b.int
                if (id == "fmt ") {
                    val start = b.position()
                    require(b.short.toInt() == 1) { "PCM only" }
                    channels = b.short.toInt()
                    rate = b.int
                    b.int; b.short
                    bits = b.short.toInt()
                    b.position(start + size)
                } else if (id == "data") {
                    require(channels == 1 && rate == 16_000 && bits == 16) { "need 16 kHz mono PCM16, got $channels ch $rate Hz $bits bit" }
                    val n = size / 2
                    return FloatArray(n) { b.short / 32768f }
                } else {
                    b.position(b.position() + size + (size and 1))
                }
            }
            error("no data chunk in $file")
        }

        /** Lower case, hyphens to spaces, punctuation stripped (asr_metrics.py's normaliser). */
        fun norm(text: String): List<String> =
            text.lowercase().replace('-', ' ').replace(Regex("[^a-z0-9' ]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }

        /** Word-level Levenshtein distance (S + D + I). */
        fun wordErrors(reference: String, hypothesis: String): Int {
            val r = norm(reference)
            val h = norm(hypothesis)
            var prev = IntArray(h.size + 1) { it }
            for (i in 1..r.size) {
                val cur = IntArray(h.size + 1)
                cur[0] = i
                for (j in 1..h.size) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1)
                }
                prev = cur
            }
            return prev[h.size]
        }
    }
}
