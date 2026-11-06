package tribixbite.cleverkeys.stt

import android.os.Debug
import android.os.Process
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
 * (`VmHWM`) belongs to that setting alone. `-e voiceBenchRounds N` repeats the clip set on one
 * engine (a long dictation; RSS per round must plateau) and `-e voiceBenchReloads K` then loads,
 * uses and closes the engine K times (the keyboard's idle release; RSS after close must not climb);
 * `-e voiceBenchSettleS S` then waits S seconds and logs RSS once more.
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
        val rounds = args.getString("voiceBenchRounds")?.toIntOrNull() ?: 1
        val reloads = args.getString("voiceBenchReloads")?.toIntOrNull() ?: 0
        val settleSeconds = args.getString("voiceBenchSettleS")?.toIntOrNull() ?: 0
        val assets = testContext.assets
        assumeTrue("voice pack asset $pack absent", pack in assets.list("voicepack").orEmpty())
        val names = assets.list("voicepack/bench").orEmpty().filter { it.endsWith(".wav") }.sorted()
        assumeTrue("no bench clips", names.isNotEmpty())
        val clips = joinClips(names.map(::readClip), args.getString("voiceBenchJoinS")?.toIntOrNull() ?: 0)

        val root = File(appContext.cacheDir, "voice-bench/voicepacks")
        val scratch = File(appContext.cacheDir, "voice-bench/scratch").apply { mkdirs() }
        try {
            val manager = VoicePackManager(root, scratch, Int.MAX_VALUE)
            val result = assets.open("voicepack/$pack").use { manager.import(it) }
            assertTrue("import: $result", result is VoicePackManager.ImportResult.Success)
            val installed = manager.activePack("en")!!
            val factory = (SpeechEngineRegistry.DEFAULT.resolve(installed.manifest) as SpeechEngineRegistry.Resolution.Supported).factory
            val rssBefore = statusKb("VmRSS")
            val options = SpeechEngineOptions(engineHints = hints)
            // The keyboard keeps one engine for a whole dictation (one session per segment) and
            // releases it when idle; rounds model a long dictation, reloads the idle release.
            factory.load(installed.dir, options).use { engine ->
                val report = engine.memoryReport()
                var first: RoundStats? = null
                for (round in 1..rounds) {
                    val s = runRound(engine, pack, clips, logClips = round == 1)
                    if (first == null) first = s
                    Log.i(
                        TAG,
                        "VOICE_BENCH_ROUND $pack hints=$hints round=$round/$rounds median=${s.median}ms p90=${s.p90}ms " +
                            "over1s=${s.over} errors=${s.errors}/${s.words} cpuPerAudioS=${"%.3f".format(s.cpuPerAudioSecond)} " +
                            "rss=${statusKb("VmRSS") / 1024}MB peak=${statusKb("VmHWM") / 1024}MB",
                    )
                }
                val s = first!!
                Log.i(
                    TAG,
                    "VOICE_BENCH $pack hints=$hints | ${report.detail} | load=${report.loadMillis}ms clips=${clips.size} audio=${"%.1f".format(s.audioSeconds)}s " +
                        "passes=${s.passes} median=${s.median}ms p90=${s.p90}ms max=${s.max}ms over1s=${s.over} " +
                        "finalMedian=${s.finalMedian}ms errors=${s.errors}/${s.words} cpuPerAudioS=${"%.3f".format(s.cpuPerAudioSecond)} " +
                        "rssBeforeLoad=${rssBefore / 1024}MB rssAfter=${statusKb("VmRSS") / 1024}MB peak=${statusKb("VmHWM") / 1024}MB",
                )
                assertTrue("WER ${s.errors}/${s.words}", s.errors.toDouble() / s.words < 0.2)
            }
            logReload(pack, hints, 0)
            // Load, run one clip, close: RSS after each close must come back to the same level.
            for (cycle in 1..reloads) {
                factory.load(installed.dir, options).use { engine -> runRound(engine, pack, clips.take(1), logClips = false) }
                logReload(pack, hints, cycle)
            }
            // RSS after close includes memory the native allocator freed but has not yet returned
            // to the kernel; after a pause it shows what a released engine really leaves behind.
            if (settleSeconds > 0) {
                Runtime.getRuntime().gc()
                Thread.sleep(settleSeconds * 1000L)
                logReload(pack, hints, -1)
            }
        } finally {
            File(appContext.cacheDir, "voice-bench").deleteRecursively()
        }
    }

    /** cycle -1 = after the settle pause. nativeLive = bytes the native heap still has allocated. */
    private fun logReload(pack: String, hints: Map<String, String>, cycle: Int) = Log.i(
        TAG,
        "VOICE_BENCH_RELOAD $pack hints=$hints cycle=${if (cycle < 0) "settled" else cycle} " +
            "rssAfterClose=${statusKb("VmRSS") / 1024}MB nativeLive=${Debug.getNativeHeapAllocatedSize() shr 20}MB",
    )

    private class RoundStats(
        val passes: Int, val median: Long, val p90: Long, val max: Long, val over: Int,
        val finalMedian: Long, val errors: Int, val words: Int, val audioSeconds: Double, val cpuPerAudioSecond: Double,
    )

    /**
     * Every clip once, fed in 1 s chunks (one pass each, as audio arrives in the keyboard), then
     * finished. CPU time is the whole process's (all ORT/XNNPACK threads) per second of audio:
     * wall-clock speed alone hides thread-pool busy-waiting, which costs battery.
     */
    /** One utterance fed to one engine session. */
    private class Clip(val id: String, val pcm: FloatArray, val reference: String)

    private fun readClip(name: String): Clip {
        val assets = testContext.assets
        val pcm = assets.open("voicepack/bench/$name").use { readWav(it.readBytes()) }
        val reference = assets.open("voicepack/bench/${name.removeSuffix(".wav")}.txt").use { it.readBytes().toString(Charsets.UTF_8).trim() }
        return Clip(name.removeSuffix(".wav"), pcm, reference)
    }

    /**
     * `-e voiceBenchJoinS N`: consecutive clips joined into utterances of at most N seconds, so one
     * session sees a segment as long as the keyboard's cap (an arena sized by the longest input
     * shows its worst case only there). 0 keeps every clip on its own.
     */
    private fun joinClips(clips: List<Clip>, maxSeconds: Int): List<Clip> {
        if (maxSeconds <= 0) return clips
        val maxSamples = maxSeconds * 16_000
        val out = ArrayList<Clip>()
        var group = ArrayList<Clip>()
        fun flush() {
            if (group.isEmpty()) return
            val pcm = FloatArray(group.sumOf { it.pcm.size })
            var off = 0
            for (c in group) { c.pcm.copyInto(pcm, off); off += c.pcm.size }
            out += Clip(group.joinToString("+") { it.id }, pcm, group.joinToString(" ") { it.reference })
            group = ArrayList()
        }
        for (c in clips) {
            if (group.isNotEmpty() && group.sumOf { it.pcm.size } + c.pcm.size > maxSamples) flush()
            group += c
        }
        flush()
        return out
    }

    private fun runRound(engine: SpeechEngine, pack: String, clips: List<Clip>, logClips: Boolean): RoundStats {
        val passes = ArrayList<Long>()
        val finals = ArrayList<Long>()
        var errors = 0
        var words = 0
        var audioSeconds = 0.0
        var cpuMillis = 0L
        for (clip in clips) {
            val pcm = clip.pcm
            val reference = clip.reference
            val cpu0 = Process.getElapsedCpuTime()
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
            cpuMillis += Process.getElapsedCpuTime() - cpu0
            val e = wordErrors(reference, final.text)
            errors += e
            words += norm(reference).size
            audioSeconds += pcm.size / 16_000.0
            if (logClips) Log.i(TAG, "VOICE_BENCH_CLIP $pack ${clip.id} ${"%.1f".format(pcm.size / 16_000.0)}s errors=$e/${norm(reference).size} | ${final.text}")
        }
        val sorted = passes.sorted()
        fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
        return RoundStats(
            passes = passes.size, median = pct(0.5), p90 = pct(0.9), max = sorted.last(), over = passes.count { it > 1_000 },
            finalMedian = finals.sorted()[finals.size / 2], errors = errors, words = words, audioSeconds = audioSeconds,
            cpuPerAudioSecond = cpuMillis / 1000.0 / audioSeconds,
        )
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
