package tribixbite.cleverkeys.stt

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.customization.CommandRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Voice input on a real Android runtime (docs/specs/voice-input.md).
 *
 *  - The app declares RECORD_AUDIO and the MicPermissionActivity that requests it is not exported.
 *  - The `voice_input` key and command resolve.
 *  - When the androidTest APK carries the voice pack and a sample, the pack is imported through
 *    [VoicePackManager] into the app's own storage and the Moonshine engine — through the
 *    registry, as the keyboard does — transcribes the clip on the device's ORT (x86_64 on the
 *    emulator, arm64 on a phone).
 *
 * ## Assets (androidTest APK only — never the app APK; git-ignored)
 * ```
 * src/androidTest/assets/voicepack/voicepack-moonshine-v2-tiny-en.zip   (scripts/build_voice_pack.py)
 * src/androidTest/assets/voicepack/sample.wav + sample.txt               (16 kHz mono PCM16 + reference)
 * ```
 * Without them the engine test is skipped (Assume), like the `ctc_bench` encoders.
 */
@RunWith(AndroidJUnit4::class)
class VoiceInputInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext = instrumentation.targetContext
    private val testContext = instrumentation.context

    @Test
    fun microphoneIsDeclaredAndThePromptActivityIsPrivate() {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(Manifest.permission.RECORD_AUDIO in info.requestedPermissions?.toList().orEmpty())
        // Whether it is granted is not asserted: test runners may install with every runtime
        // permission granted (`adb install -g`). The request policy is pinned by the pure
        // MicPermissionGateTest.
        val activity = appContext.packageManager.getPackageInfo(appContext.packageName, PackageManager.GET_ACTIVITIES)
            .activities.orEmpty().single { it.name == MicPermissionActivity::class.java.name }
        assertFalse(activity.exported)
    }

    @Test
    fun voiceInputKeyAndCommandResolve() {
        assertEquals(KeyValue.Event.VOICE_INPUT, KeyValue.getKeyByName("voice_input").getEvent())
        assertNotNull(CommandRegistry.getByName("voice_input"))
    }

    @Test
    fun theImportedPackTranscribesASampleOnDevice() {
        val assets = testContext.assets
        val names = runCatching { assets.list("voicepack")?.toList() }.getOrNull().orEmpty()
        assumeTrue("voice pack asset absent", PACK in names && "sample.wav" in names && "sample.txt" in names)

        val root = File(appContext.cacheDir, "voice-it/voicepacks")
        val scratch = File(appContext.cacheDir, "voice-it/scratch").apply { mkdirs() }
        try {
            val manager = VoicePackManager(root, scratch, Int.MAX_VALUE)
            val result = assets.open("voicepack/$PACK").use { manager.import(it) }
            assertTrue("import: $result", result is VoicePackManager.ImportResult.Success)
            val pack = manager.activePack("en")!!
            val factory = (SpeechEngineRegistry.DEFAULT.resolve(pack.manifest) as SpeechEngineRegistry.Resolution.Supported).factory
            val pcm = assets.open("voicepack/sample.wav").use { readWav(it.readBytes()) }
            val reference = assets.open("voicepack/sample.txt").use { it.readBytes().toString(Charsets.UTF_8).trim() }
            factory.load(pack.dir, SpeechEngineOptions()).use { engine ->
                val t0 = System.nanoTime()
                val session = engine.newSession()
                var off = 0
                var partials = 0
                while (off < pcm.size) {
                    val n = minOf(5_120, pcm.size - off)
                    if (session.accept(pcm.copyOfRange(off, off + n)) != null) partials++
                    off += n
                }
                val final = session.finish()
                session.close()
                val ms = (System.nanoTime() - t0) / 1_000_000
                val errors = wordErrors(reference, final.text)
                val words = norm(reference).size
                Log.i(TAG, "VOICE_IT ${engine.memoryReport()} audio=${pcm.size / 16_000.0}s compute=${ms}ms passes=$partials errors=$errors/$words | ${final.text}")
                assertTrue("no partial results", partials >= 1)
                assertTrue("WER on the sample: ${final.text}", errors.toDouble() / words < 0.2)
            }
        } finally {
            File(appContext.cacheDir, "voice-it").deleteRecursively()
        }
    }

    /**
     * Endpointing on the device's ORT (spec FR-10): the sample twice with 1.5 s of silence
     * between, through [SegmentedRecognizer] as the keyboard runs it. The pause must end the
     * first segment (its own session, finalised while audio continues) and both halves must be
     * transcribed; silence is never decoded.
     */
    @Test
    fun aPauseSplitsDictationIntoTwoSegmentsOnDevice() {
        val assets = testContext.assets
        val names = runCatching { assets.list("voicepack")?.toList() }.getOrNull().orEmpty()
        assumeTrue("voice pack asset absent", PACK in names && "sample.wav" in names && "sample.txt" in names)

        val root = File(appContext.cacheDir, "voice-it-seg/voicepacks")
        val scratch = File(appContext.cacheDir, "voice-it-seg/scratch").apply { mkdirs() }
        try {
            val manager = VoicePackManager(root, scratch, Int.MAX_VALUE)
            val result = assets.open("voicepack/$PACK").use { manager.import(it) }
            assertTrue("import: $result", result is VoicePackManager.ImportResult.Success)
            val pack = manager.activePack("en")!!
            val factory = (SpeechEngineRegistry.DEFAULT.resolve(pack.manifest) as SpeechEngineRegistry.Resolution.Supported).factory
            val clip = assets.open("voicepack/sample.wav").use { readWav(it.readBytes()) }
            val reference = assets.open("voicepack/sample.txt").use { it.readBytes().toString(Charsets.UTF_8).trim() }
            val pcm = clip + FloatArray(24_000) + clip + FloatArray(16_000)
            factory.load(pack.dir, SpeechEngineOptions()).use { engine ->
                val r = SegmentedRecognizer(engine)
                val finals = ArrayList<SegmentedRecognizer.Update.SegmentFinal>()
                var off = 0
                while (off < pcm.size) {
                    val n = minOf(5_120, pcm.size - off)
                    r.accept(pcm.copyOfRange(off, off + n)).filterIsInstance<SegmentedRecognizer.Update.SegmentFinal>().forEach { finals += it }
                    off += n
                }
                val last = r.finish()
                r.close()
                Log.i(TAG, "VOICE_IT_SEG segments=${finals.size} ${finals.map { "${it.reason}/${it.samples}" }} last='${last.text}' | ${finals.joinToString(" | ") { it.hypothesis.text }}")
                assertEquals("both halves end at a pause", 2, finals.size)
                assertTrue(finals.all { it.reason == SegmentEndReason.PAUSE })
                // Each segment is the clip plus look-behind and hangover, never the silence too.
                for (f in finals) assertTrue("segment ${f.samples} samples", f.samples < clip.size + 16_000)
                for (f in finals) {
                    val errors = wordErrors(reference, f.hypothesis.text)
                    assertTrue("WER of a segment: ${f.hypothesis.text}", errors.toDouble() / norm(reference).size < 0.2)
                }
                assertEquals("", last.text)
            }
        } finally {
            File(appContext.cacheDir, "voice-it-seg").deleteRecursively()
        }
    }

    private companion object {
        const val TAG = "VoiceInputIT"
        const val PACK = "voicepack-moonshine-v2-tiny-en.zip"

        fun readWav(bytes: ByteArray): FloatArray {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            b.position(12)
            while (b.remaining() >= 8) {
                val id = String(ByteArray(4).also { b.get(it) })
                val size = b.int
                if (id == "data") return FloatArray(size / 2) { b.short / 32768f }
                b.position(b.position() + size + (size and 1))
            }
            error("no data chunk")
        }

        fun norm(text: String): List<String> =
            text.lowercase().replace('-', ' ').replace(Regex("[^a-z0-9' ]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }

        fun wordErrors(reference: String, hypothesis: String): Int {
            val r = norm(reference)
            val h = norm(hypothesis)
            var prev = IntArray(h.size + 1) { it }
            for (i in 1..r.size) {
                val cur = IntArray(h.size + 1)
                cur[0] = i
                for (j in 1..h.size) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1)
                prev = cur
            }
            return prev[h.size]
        }
    }
}
