package tribixbite.cleverkeys.stt.moonshine

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume.assumeTrue
import org.junit.Test
import tribixbite.cleverkeys.stt.SegmentEndReason
import tribixbite.cleverkeys.stt.SegmentedRecognizer
import tribixbite.cleverkeys.stt.SpeechEngine
import tribixbite.cleverkeys.stt.SpeechEngineOptions
import tribixbite.cleverkeys.stt.SpeechEngineRegistry
import tribixbite.cleverkeys.stt.SpeechHypothesis
import tribixbite.cleverkeys.stt.VoiceFieldKind
import tribixbite.cleverkeys.stt.VoicePackManager
import tribixbite.cleverkeys.stt.VoiceTextOp
import tribixbite.cleverkeys.stt.VoiceTextPlanner
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
 *    `<id>.txt`;
 *  - for the long-dictation (endpointing) tests, ten more clips (79 s) in
 *    `-PvoiceLongSamples=<dir>` or `~/.cache/cleverkeys-test/voice-samples-long/`, same layout,
 *    joined in file-name order.
 */
class MoonshineV2StreamingEngineTest {

    private val pack: File = File(System.getProperty("voicePack") ?: "build/voicepacks/voicepack-moonshine-v2-tiny-en.zip")
    private val samples: File = File(
        System.getProperty("voiceSamples") ?: "${System.getProperty("user.home")}/.cache/cleverkeys-test/voice-samples"
    )

    private val longSamples: File = File(
        System.getProperty("voiceLongSamples") ?: "${System.getProperty("user.home")}/.cache/cleverkeys-test/voice-samples-long"
    )

    private fun clips(dir: File = samples): List<Pair<File, String>> =
        dir.listFiles().orEmpty().filter { it.extension == "wav" }.sortedBy { it.name }
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
     * The engine's declared [MoonshineV2StreamingEngine.maxSegmentSeconds] is the longest
     * segment the keyboard ever hands one session (forced at that bound without a pause), so an
     * utterance that long must still come out right.
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
                val limitSamples = engine.maxSegmentSeconds * 16_000
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

    /**
     * Endpointing (spec FR-10) on the real model: ten LibriSpeech clips (79 s of speech) joined
     * by 0.9–1.2 s pauses of quiet room noise into one ~88 s period, streamed in 320 ms pieces
     * through [SegmentedRecognizer] and committed by [VoiceTextPlanner] into a model editor —
     * the keyboard's path minus the microphone. Before segmentation one session over this audio
     * looped (46 s: 151 errors for 125 words) and the keyboard stopped at 30 s instead.
     *
     * Asserts: WER close to the same clips transcribed one by one; no repetition loop (no word
     * 4-gram more frequent than in the reference); a segment per pause; per-segment cost flat
     * (the slowest pass of the last segments no slower than of the first ones, within noise).
     */
    @Test
    fun longDictationIsSegmentedAtPausesAndStaysCoherent() {
        val run = longRun(gapSeconds = doubleArrayOf(0.9, 1.2)) ?: return
        val (perClip, seg) = run
        assertWithMessage("a segment per pause").that(seg.segments.size).isIn(com.google.common.collect.Range.closed(seg.clips, seg.clips * 2))
        assertWithMessage("every segment ended at a pause").that(seg.segments.all { it.reason != SegmentEndReason.MAX_LENGTH }).isTrue()
        assertCoherent(perClip, seg, slack = 0.02)
        // Flat: compare the slowest pass of the first and last thirds of the segments.
        val maxes = seg.segments.map { it.maxPassMillis.toDouble() }
        val third = maxOf(1, maxes.size / 3)
        val early = maxes.take(third).average()
        val late = maxes.takeLast(third).average()
        println("long: early segments max pass ${"%.0f".format(early)} ms, late ${"%.0f".format(late)} ms")
        assertWithMessage("per-segment latency stays flat").that(late).isAtMost(early * 2.0 + 300)
    }

    /**
     * Continuous speech with no pause the hangover accepts (0.2 s gaps between the clips) is
     * still cut at the engine's bound — at the quietest point of the last two seconds — so no
     * session ever exceeds [MoonshineV2StreamingEngine.maxSegmentSeconds] and the text stays
     * coherent. The control is the same audio in one session (the old behaviour, minus the 30 s
     * stop): printed for the record.
     */
    @Test
    fun continuousSpeechIsCutAtTheEngineBoundAndStaysCoherent() {
        val run = longRun(gapSeconds = doubleArrayOf(0.2), maxSeconds = 50.0, control = true) ?: return
        val (perClip, seg) = run
        assertWithMessage("forced boundaries").that(seg.segments.count { it.reason == SegmentEndReason.MAX_LENGTH }).isAtLeast(2)
        for (s in seg.segments) assertThat(s.seconds).isAtMost(MoonshineV2StreamingEngine.MAX_SEGMENT_SECONDS + 0.05)
        // A forced cut may split a word at worst; allow a little more than at pauses.
        assertCoherent(perClip, seg, slack = 0.04)
    }

    /** Per-clip baseline and the segmented run over the joined clips. */
    private data class LongRun(val perClip: Score, val segmented: Segmented)

    private data class Score(val errors: Int, val words: Int) {
        val wer get() = errors.toDouble() / words
    }

    private data class SegmentStats(val reason: SegmentEndReason, val seconds: Double, val passes: Int, val maxPassMillis: Long, val finalMillis: Long)

    private data class Segmented(val clips: Int, val audioSeconds: Double, val score: Score, val text: String, val reference: String, val segments: List<SegmentStats>, val wallMillis: Long)

    private fun assertCoherent(perClip: Score, seg: Segmented, slack: Double) {
        println("per-clip WER ${perClip.errors}/${perClip.words} = ${"%.4f".format(perClip.wer)}; " +
            "segmented ${"%.1f".format(seg.audioSeconds)} s WER ${seg.score.errors}/${seg.score.words} = ${"%.4f".format(seg.score.wer)}")
        assertWithMessage("segmented WER vs per-clip: ${seg.text}").that(seg.score.wer).isAtMost(perClip.wer + slack)
        assertWithMessage("repetition loop: ${seg.text}").that(repeatedNgrams(seg.reference, seg.text)).isEmpty()
    }

    /**
     * Join the long clips (until [maxSeconds]) with pauses cycling through [gapSeconds] of
     * -65 dBFS Gaussian noise; measure each clip alone, then the joined audio segmented.
     * Null (test skipped) when the pack or the clips are absent.
     */
    private fun longRun(gapSeconds: DoubleArray, maxSeconds: Double = Double.MAX_VALUE, control: Boolean = false): LongRun? {
        assumeTrue("voice pack not built ($pack)", pack.isFile)
        val clips = clips(longSamples)
        assumeTrue("no long sample clips in $longSamples", clips.size >= 4)
        val scratch = kotlin.io.path.createTempDirectory("ck-voice-segmented").toFile()
        try {
            val manager = VoicePackManager(File(scratch, "voicepacks"), File(scratch, "cache").apply { mkdirs() }, Int.MAX_VALUE)
            manager.import(pack.inputStream())
            val installed = manager.activePack("en")!!
            MoonshineV2StreamingEngine.Factory.load(installed.dir, SpeechEngineOptions(threads = 2)).use { engine ->
                val noise = java.util.Random(65)
                val noiseSd = Math.pow(10.0, -65.0 / 20)
                val parts = ArrayList<FloatArray>()
                val refs = ArrayList<String>()
                var perErrors = 0
                var perWords = 0
                var seconds = 0.0
                for ((i, clip) in clips.withIndex()) {
                    if (seconds >= maxSeconds) break
                    val (wav, reference) = clip
                    val pcm = readWav(wav)
                    if (parts.isNotEmpty()) {
                        val gap = gapSeconds[(i - 1) % gapSeconds.size]
                        parts += FloatArray((gap * 16_000).toInt()) { (noise.nextGaussian() * noiseSd).toFloat() }
                        seconds += gap
                    }
                    parts += pcm
                    seconds += pcm.size / 16_000.0
                    refs += reference
                    val alone = transcribeAlone(engine, pcm)
                    perErrors += wordErrors(reference, alone)
                    perWords += norm(reference).size
                }
                val audio = FloatArray(parts.sumOf { it.size }).also { a -> var o = 0; for (p in parts) { p.copyInto(a, o); o += p.size } }
                val reference = refs.joinToString(" ")
                val seg = segmented(engine, audio, refs.size, reference)
                for ((k, s) in seg.segments.withIndex()) {
                    println("  segment ${k + 1}: ${"%.1f".format(s.seconds)} s ${s.reason} passes=${s.passes} maxPass=${s.maxPassMillis} ms final=${s.finalMillis} ms")
                }
                println("segmented ${refs.size} clips ${"%.1f".format(seg.audioSeconds)} s audio, ${seg.segments.size} segments, wall ${seg.wallMillis} ms | ${seg.text}")
                if (control) {
                    val t0 = System.nanoTime()
                    val one = transcribeAlone(engine, audio)
                    val e = wordErrors(reference, one)
                    println("control: one session over ${"%.1f".format(audio.size / 16_000.0)} s: errors $e/${norm(reference).size}, " +
                        "${(System.nanoTime() - t0) / 1_000_000} ms, loops=${repeatedNgrams(reference, one)} | $one")
                }
                return LongRun(Score(perErrors, perWords), seg)
            }
        } finally {
            scratch.deleteRecursively()
        }
    }

    private fun transcribeAlone(engine: SpeechEngine, pcm: FloatArray): String {
        val s = engine.newSession()
        var off = 0
        while (off < pcm.size) {
            val n = minOf(5_120, pcm.size - off)
            s.accept(pcm.copyOfRange(off, off + n))
            off += n
        }
        return s.finish().text.also { s.close() }
    }

    /** Stream [audio] through the segmenter and the keyboard's text planner; time every pass. */
    private fun segmented(engine: SpeechEngine, audio: FloatArray, clips: Int, reference: String): Segmented {
        val r = SegmentedRecognizer(engine)
        val planner = VoiceTextPlanner(VoiceFieldKind.TEXT, textBefore = "")
        val editor = StringBuilder()
        var composing = ""
        fun apply(ops: List<VoiceTextOp>) {
            for (op in ops) when (op) {
                is VoiceTextOp.Commit -> { editor.append(op.text); composing = "" }
                is VoiceTextOp.SetComposing -> composing = op.text
                VoiceTextOp.ClearComposing -> composing = ""
            }
        }
        val stats = ArrayList<SegmentStats>()
        var passes = 0
        var maxPass = 0L
        val t0 = System.nanoTime()
        var off = 0
        while (off < audio.size) {
            val n = minOf(5_120, audio.size - off)
            val start = System.nanoTime()
            val updates = r.accept(audio.copyOfRange(off, off + n))
            val ms = (System.nanoTime() - start) / 1_000_000
            off += n
            for (u in updates) when (u) {
                is SegmentedRecognizer.Update.Partial -> {
                    passes++
                    maxPass = maxOf(maxPass, ms)
                    apply(planner.onHypothesis(u.hypothesis))
                }
                is SegmentedRecognizer.Update.SegmentFinal -> {
                    maxPass = maxOf(maxPass, ms)
                    stats += SegmentStats(u.reason, u.samples / 16_000.0, passes, maxPass, ms)
                    passes = 0
                    maxPass = 0
                    apply(planner.onSegmentEnd(u.hypothesis))
                }
            }
        }
        val start = System.nanoTime()
        val wasOpen = r.inSegment
        apply(planner.onHypothesis(r.finish()))
        if (wasOpen) stats += SegmentStats(SegmentEndReason.FLUSH, r.lastSegmentSamples / 16_000.0, passes, maxPass, (System.nanoTime() - start) / 1_000_000)
        r.close()
        assertThat(composing).isEmpty()
        val text = editor.toString()
        return Segmented(clips, audio.size / 16_000.0, Score(wordErrors(reference, text), norm(reference).size), text, reference, stats,
            (System.nanoTime() - t0) / 1_000_000)
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

        /**
         * Word 4-grams that occur more often in [hypothesis] than in [reference] and more than
         * once: the signature of a decoder loop ("some use of some use of …"). Empty when none.
         */
        fun repeatedNgrams(reference: String, hypothesis: String, n: Int = 4): Map<String, Int> {
            fun grams(words: List<String>) = words.windowed(n).groupingBy { it.joinToString(" ") }.eachCount()
            val ref = grams(norm(reference))
            return grams(norm(hypothesis)).filter { (g, c) -> c > 1 && c > (ref[g] ?: 0) }
        }

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
