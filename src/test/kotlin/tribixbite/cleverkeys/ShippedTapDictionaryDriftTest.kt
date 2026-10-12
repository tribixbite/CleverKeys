package tribixbite.cleverkeys

import android.content.Context
import android.content.res.AssetManager
import android.os.Trace
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pins [ShippedTapDictionary] — the dictionary every JVM tap-predictor harness
 * (`AutoCorrectEndToEndTest`, `SpaceCommitPreviewTest`, `WordPredictorFuzzyTypingTest`,
 * `FuzzyPrefixMatcherTest`, the dropped-double evals) runs on — to what the device loads for
 * `en`.
 *
 * Why (2026-10-12): the harnesses converted `en_enhanced.json` bytes as `rank = 255 − b` and
 * claimed that equalled the device path. The device's tap predictor loads `en_enhanced.bin`;
 * the JSON is the CTC swipe lexicon's compressed 134..255 scale and 98,139 of 98,140 words
 * disagreed, so DD-M was tuned on a scale the phone never runs (`Helo` → `Help` on a Pixel
 * while every JVM test said `Hello`).
 *
 * MockK runner: the production loader under comparison uses `Log`/`Trace` and a `Context`.
 */
class ShippedTapDictionaryDriftTest {

    @Before
    fun mockAndroid() {
        mockkStatic(Log::class, Trace::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Trace.beginSection(any()) } just runs
        every { Trace.endSection() } just runs
    }

    @After
    fun unmockAndroid() {
        unmockkStatic(Log::class, Trace::class)
    }

    /**
     * The fixture equals what `BinaryDictionaryLoader.loadDictionaryWithPrefixIndex` — the call
     * `AsyncDictionaryLoader` and `WordPredictor.loadDictionary` make for a bundled language —
     * produces from the asset `dictionaries/en_enhanced.bin`: same words, same frequencies,
     * same prefix index.
     */
    @Test
    fun fixtureEqualsTheProductionAssetLoad() {
        val assets = mockk<AssetManager>()
        every { assets.open(ASSET_PATH) } answers { ShippedTapDictionary.BIN.inputStream() }
        val context = mockk<Context>()
        every { context.assets } returns assets

        val dict = HashMap<String, Int>()
        val index = HashMap<String, MutableSet<String>>()
        assertThat(BinaryDictionaryLoader.loadDictionaryWithPrefixIndex(context, ASSET_PATH, dict, index)).isTrue()

        val fixture = ShippedTapDictionary.frequencies()
        assertThat(fixture.size).isGreaterThan(90_000)
        assertWithMessage("fixture frequencies vs the production asset load").that(fixture).isEqualTo(dict)
        assertWithMessage("fixture prefix index vs the production asset load")
            .that(ShippedTapDictionary.prefixIndex()).isEqualTo(index)
    }

    /**
     * An independent decode of the V2 file (header + canonical section, written here from the
     * format spec, not via [BinaryDictionaryParser]) with the documented conversion
     * `1 000 000 − rank · 3 900` agrees with the fixture on every word. Guards the conversion
     * itself, which the asset-load comparison above cannot (both sides share the parser).
     */
    @Test
    fun independentV2DecodeAgreesOnEveryWord() {
        val buf = ByteBuffer.wrap(ShippedTapDictionary.BIN.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(buf.getInt(0)).isEqualTo(0x54444B43) // "CKDT"
        assertThat(buf.getInt(4)).isEqualTo(2)
        val count = buf.getInt(12)
        buf.position(buf.getInt(16))
        val decoded = HashMap<String, Int>(count * 2)
        repeat(count) {
            val bytes = ByteArray(buf.short.toInt() and 0xFFFF)
            buf.get(bytes)
            decoded[String(bytes, Charsets.UTF_8)] = 1_000_000 - (buf.get().toInt() and 0xFF) * 3_900
        }
        assertThat(ShippedTapDictionary.frequencies()).isEqualTo(decoded)
    }

    /**
     * The device's tap load paths still read the `.bin` asset through the shared parser — if
     * either stopped doing so, the fixture would silently describe a different dictionary.
     */
    @Test
    fun deviceTapLoadPathsUseTheBinThroughTheSharedParser() {
        val binProbe = "\"dictionaries/\${language}_enhanced.bin\""
        for (path in listOf("AsyncDictionaryLoader.kt", "WordPredictor.kt")) {
            val src = File(SRC, path).readText()
            assertWithMessage("$path loads $binProbe").that(src).contains(binProbe)
            assertWithMessage("$path loads it with BinaryDictionaryLoader.loadDictionaryWithPrefixIndex")
                .that(src).contains("BinaryDictionaryLoader.loadDictionaryWithPrefixIndex(")
            // The JSON branch is only a fallback after the bin probe.
            assertWithMessage("$path probes the .bin before the JSON fallback")
                .that(src.indexOf(binProbe)).isLessThan(src.indexOf("\"dictionaries/\${language}_enhanced.json\""))
        }
        val loader = File(SRC, "BinaryDictionaryLoader.kt").readText()
        val loaderBody = loader.substringAfter("object BinaryDictionaryLoader")
        assertWithMessage("both prefix-index loaders (asset + language pack) parse via BinaryDictionaryParser")
            .that(Regex("BinaryDictionaryParser\\.parseWithPrefixIndex\\(").findAll(loaderBody).count())
            .isEqualTo(2)
    }

    /**
     * No test that injects a `WordPredictor` dictionary may build it from `en_enhanced.json`
     * (the swipe lexicon's scale). Swipe/CTC tests that read the JSON for the swipe engine do
     * not inject a `WordPredictor` dictionary and are unaffected.
     */
    @Test
    fun noTapHarnessBuildsItsDictionaryFromTheSwipeJson() {
        val injects = Regex("""(setField|getDeclaredField)\(\s*"dictionary"""")
        val readsJson = Regex(""""[^"\n]*en_enhanced\.json"""")
        val offenders = File("src/test/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().let { injects.containsMatchIn(it) && readsJson.containsMatchIn(it) } }
            .map { it.path }.toList()
        assertWithMessage("tap harnesses must use ShippedTapDictionary, not en_enhanced.json")
            .that(offenders).isEmpty()
    }

    private companion object {
        const val ASSET_PATH = "dictionaries/en_enhanced.bin"
        val SRC = File("src/main/kotlin/tribixbite/cleverkeys")
    }
}
