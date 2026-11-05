package tribixbite.cleverkeys.stt

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.stt.VoiceTestAudio.norm
import tribixbite.cleverkeys.stt.VoiceTestAudio.readWav
import tribixbite.cleverkeys.stt.VoiceTestAudio.wordErrors
import java.io.File

/**
 * On-device timing of a voice pack (research `docs/research/2026-10-28-stt-engine-deep-dive.md`
 * §6.7): every 1 s streaming pass of the real engine, as the keyboard runs it, on the phone's
 * own ORT. Opt-in, so it never slows an ordinary instrumented run:
 *
 * ```
 * adb shell am instrument -w -e class tribixbite.cleverkeys.stt.VoiceEngineDeviceBenchmark \
 *     -e voiceBench 1 -e voicePack voicepack-moonshine-v2-small-en.zip -e voiceBenchHints arena=off,ep=cpu \
 *     tribixbite.cleverkeys.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * One pack and one engine setting per invocation: each runs in a fresh process, so the peak RSS
 * (`VmHWM`) belongs to that setting alone.
 *
 * Assets (androidTest APK only, git-ignored): `voicepack/<pack>.zip` and `voicepack/bench/<id>.wav`
 * + `<id>.txt` (16 kHz mono PCM16 LibriSpeech clips and their transcripts).
 *
 * Each clip is fed in 1 s chunks (one pass each, as audio arrives in the keyboard) and then
 * finished. Logged: `VOICE_BENCH` lines with load time, per-pass median / p90 / max, passes over
 * 1 s (the keyboard's cadence), the final pass, word errors and RSS.
 */
@RunWith(AndroidJUnit4::class)
class VoiceEngineDeviceBenchmark {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext = instrumentation.targetContext
    private val testContext = instrumentation.context
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun timeEveryStreamingPassOnDevice() {
        assumeTrue("opt-in: -e voiceBench 1", args.getString("voiceBench") == "1")
        val pack = args.getString("voicePack") ?: "voicepack-moonshine-v2-tiny-en.zip"
        // "arena=on,ep=xnnpack" → engine hints; the keyboard itself passes none.
        val hints = args.getString("voiceBenchHints").orEmpty().split(',').filter { '=' in it }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        val assets = testContext.assets
        assumeTrue("voice pack asset $pack absent", pack in assets.list("voicepack").orEmpty())
        val clips = assets.list("voicepack/bench").orEmpty().filter { it.endsWith(".wav") }.sorted()
        assumeTrue("no bench clips", clips.isNotEmpty())

        val root = File(appContext.cacheDir, "voice-bench/voicepacks")
        val scratch = File(appContext.cacheDir, "voice-bench/scratch").apply { mkdirs() }
        try {
            val manager = VoicePackManager(root, scratch, Int.MAX_VALUE)
            val result = assets.open("voicepack/$pack").use { manager.import(it) }
            assertTrue("import: $result", result is VoicePackManager.ImportResult.Success)
            val installed = manager.activePack("en")!!
            val factory = (SpeechEngineRegistry.DEFAULT.resolve(installed.manifest) as SpeechEngineRegistry.Resolution.Supported).factory
            val rssBefore = statusKb("VmRSS")
            factory.load(installed.dir, SpeechEngineOptions(engineHints = hints)).use { engine ->
                val report = engine.memoryReport()
                val passes = ArrayList<Long>()
                val finals = ArrayList<Long>()
                var errors = 0
                var words = 0
                var audioSeconds = 0.0
                for (name in clips) {
                    val pcm = assets.open("voicepack/bench/$name").use { readWav(it.readBytes()) }
                    val reference = assets.open("voicepack/bench/${name.removeSuffix(".wav")}.txt").use { it.readBytes().toString(Charsets.UTF_8).trim() }
                    val session = engine.newSession()
                    var off = 0
                    while (off < pcm.size) {
                        val n = minOf(CHUNK, pcm.size - off)
                        val t0 = System.nanoTime()
                        session.accept(pcm.copyOfRange(off, off + n))
                        // A short tail only buffers; finish() decodes it (timed below).
                        if (n == CHUNK) passes += (System.nanoTime() - t0) / 1_000_000
                        off += n
                    }
                    val t0 = System.nanoTime()
                    val final = session.finish()
                    finals += (System.nanoTime() - t0) / 1_000_000
                    session.close()
                    val e = wordErrors(reference, final.text)
                    errors += e
                    words += norm(reference).size
                    audioSeconds += pcm.size / 16_000.0
                    Log.i(TAG, "VOICE_BENCH_CLIP $pack ${name.removeSuffix(".wav")} errors=$e/${norm(reference).size} | ${final.text}")
                }
                val sorted = passes.sorted()
                fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
                val over = passes.count { it > 1_000 }
                Log.i(
                    TAG,
                    "VOICE_BENCH $pack hints=$hints | ${report.detail} | load=${report.loadMillis}ms clips=${clips.size} audio=${"%.1f".format(audioSeconds)}s " +
                        "passes=${passes.size} median=${pct(0.5)}ms p90=${pct(0.9)}ms max=${sorted.last()}ms over1s=$over " +
                        "finalMedian=${finals.sorted()[finals.size / 2]}ms errors=$errors/$words " +
                        "rssBeforeLoad=${rssBefore / 1024}MB rssAfter=${statusKb("VmRSS") / 1024}MB peak=${statusKb("VmHWM") / 1024}MB",
                )
                assertTrue("WER $errors/$words", errors.toDouble() / words < 0.2)
            }
        } finally {
            File(appContext.cacheDir, "voice-bench").deleteRecursively()
        }
    }

    private companion object {
        const val TAG = "VoiceBench"

        /** One second of 16 kHz audio: one engine pass, the keyboard's cadence. */
        const val CHUNK = 16_000

        /** A `/proc/self/status` field in kB (VmRSS, VmHWM), or -1. */
        fun statusKb(field: String): Long =
            File("/proc/self/status").readLines().firstOrNull { it.startsWith("$field:") }
                ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull() ?: -1
    }
}
