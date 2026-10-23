package tribixbite.cleverkeys.langpack

import android.os.Trace
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import tribixbite.cleverkeys.BinaryDictionaryLoader
import tribixbite.cleverkeys.swipe.ctc.CtcImportedPackSupport
import tribixbite.cleverkeys.swipe.ctc.CtcLanguageSupport
import tribixbite.cleverkeys.swipe.geometric.CkdtDictionaryReader
import tribixbite.cleverkeys.swipe.geometric.GeoLayoutFixtures
import tribixbite.cleverkeys.swipe.geometric.LayoutProjection

/**
 * Smoke test of the Persian pack (`scripts/dictionaries/langpack-fa.zip`, 2026-10-23; method and
 * numbers in docs/eval/2026-10-23-fa-wordlist.md) through the app's real readers:
 *
 * - the tap path: [BinaryDictionaryLoader] (what `WordPredictor`/`DictionaryDataSource` use for an
 *   imported pack's `dictionary.bin`) — half-space compounds load as single words, both spellings
 *   of a joined/half-space pair are present, and the core words rank at the top;
 * - the import caps: the canonical section is read by [CkdtDictionaryReader], which enforces
 *   the 100,000-entry / 16 MiB limits;
 * - the swipe path: Arabic-script packs swipe on the GEOMETRIC engine. CTC does not serve `fa`
 *   (no table row, and the imported-pack measurement refuses a non-a–z lexicon), while the
 *   geometric projection types the pack's words on the shipped Persian PC layout, the half-space
 *   being skipped as a default-ignorable control.
 *
 * Mock tier: `Log`/`Trace` are android.jar stubs on the loader path.
 */
class PersianLangpackTest {

    companion object {
        private val PACK = File("scripts/dictionaries/langpack-fa.zip")
        private const val ZWNJ = '‌'

        private lateinit var dict: Map<String, Int>
        private lateinit var bin: ByteArray

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            mockkStatic(Log::class, Trace::class)
            every { Log.d(any(), any()) } returns 0
            every { Log.i(any(), any()) } returns 0
            every { Log.w(any(), any<String>()) } returns 0
            every { Log.e(any(), any()) } returns 0
            every { Log.e(any(), any(), any()) } returns 0
            every { Trace.beginSection(any()) } returns Unit
            every { Trace.endSection() } returns Unit

            bin = ZipFile(PACK).use { zip ->
                zip.getInputStream(zip.getEntry("dictionary.bin")).use { it.readBytes() }
            }
            val file = File.createTempFile("langpack-fa-dictionary", ".bin").apply { deleteOnExit() }
            file.writeBytes(bin)
            val loaded = HashMap<String, Int>()
            check(BinaryDictionaryLoader.loadDictionaryWithPrefixIndexFromFile(file, loaded, HashMap())) {
                "Persian pack dictionary failed to load"
            }
            dict = loaded
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() = unmockkAll()

        /** Words most frequent first, ties by word — the order the bar ranks them in. */
        private fun byFrequency(): List<String> =
            dict.entries.sortedWith(compareBy({ -it.value }, { it.key })).map { it.key }
    }

    @Test
    fun manifestMatchesTheDictionaryAndCarriesTheAttribution() {
        val manifest = ZipFile(PACK).use { zip ->
            zip.getInputStream(zip.getEntry("manifest.json")).use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        }
        assertThat(manifest.getString("code")).isEqualTo("fa")
        assertThat(manifest.getInt("wordCount")).isEqualTo(dict.size)
        assertThat(manifest.has("model")).isFalse() // no CTC encoder: geometric only
        assertThat(manifest.getString("attribution")).contains("Leipzig Corpora Collection")
        assertThat(manifest.getString("attribution")).contains("Lilak")
    }

    @Test
    fun theDictionaryLoadsWithinTheImportCaps() {
        assertThat(bin.size.toLong()).isAtMost(CkdtDictionaryReader.MAX_DICTIONARY_BYTES)
        val entries = CkdtDictionaryReader.readEntries(bin)
        assertThat(entries.size).isAtMost(CkdtDictionaryReader.MAX_WORD_COUNT)
        assertThat(entries.size).isEqualTo(dict.size)
        assertThat(dict.size).isEqualTo(50_000)
    }

    @Test
    fun halfSpaceCompoundsAreSingleWordsAndBothSpellingsArePresent() {
        for (w in listOf("می‌شود", "میشود", "است", "کتاب‌ها", "آن‌ها", "آنها", "نامه‌ای", "نام‌های")) {
            assertWithMessage("'$w' in the pack").that(dict).containsKey(w)
        }
        assertThat("می‌شود").contains(ZWNJ.toString())
        // §3 class (i): the half-space position separates two words.
        assertThat(dict).containsKey("حرفه‌ای")
        assertThat(dict).containsKey("حرف‌های")
    }

    @Test
    fun gluedClicticArtefactsAreDroppedAndReviewedJoinedFormsKept() {
        // Pixel 2026-10-23: the bar completed `می‌شود` to `می‌شودکه` (a missing space). The builder's
        // glued-clitic filter drops X + که/را/و when X is frequent and the joined string is rare.
        for (w in listOf("می‌شودکه", "داردکه", "خودرا", "شمارا", "هزارو", "نفرو", "کاررا")) {
            assertWithMessage("glued artefact '$w' must not be in the pack").that(dict).doesNotContainKey(w)
        }
        // Real joined words: kept by the Lilak oracle (چونکه, کندو) or the reviewed allowlist.
        for (w in listOf("چونکه", "همینکه", "کندو", "وقتیکه", "کسیکه", "زیراکه", "اینو")) {
            assertWithMessage("joined word '$w' must stay").that(dict).containsKey(w)
        }
    }

    @Test
    fun everyWordUsesThePersianAlphabetWithOnlyInternalHalfSpaces() {
        val alphabet = "ابپتثجچحخدذرزژسشصضطظعغفقکگلمنوهیآءأإؤئ".toSet()
        for (w in dict.keys) {
            assertWithMessage("'$w' letters").that(w.all { it in alphabet || it == ZWNJ }).isTrue()
            assertWithMessage("'$w' edge or doubled half-space")
                .that(w.first() != ZWNJ && w.last() != ZWNJ && "$ZWNJ$ZWNJ" !in w).isTrue()
        }
        // The folded Arabic letters never appear (ي ك ى ۀ ة ە → ی ک ی ه ه ه).
        assertThat(dict.keys.none { w -> w.any { it in "يكىۀةە" } }).isTrue()
    }

    @Test
    fun coreWordsRankAtTheTop() {
        val top = byFrequency().take(20)
        for (w in listOf("و", "در", "به", "از", "که", "این", "است", "را", "با")) {
            assertWithMessage("'$w' among the 20 most frequent").that(top).contains(w)
        }
        assertThat(dict.getValue("است")).isGreaterThan(dict.getValue("می‌شود"))
        assertThat(dict.getValue("می‌شود")).isGreaterThan(dict.getValue("حرفه‌ای"))
    }

    @Test
    fun swipeIsServedByTheGeometricEngineNotCtc() {
        assertWithMessage("no static CTC row for fa").that(CtcLanguageSupport.SUPPORTED).doesNotContainKey("fa")
        val ranked = CkdtDictionaryReader.readEntries(bin).map { it.word }
        val report = CtcImportedPackSupport.evaluate("fa", ranked)
        assertWithMessage("an imported fa pack never qualifies for the a–z CTC encoder")
            .that(report.eligible).isFalse()

        val layout = GeoLayoutFixtures.load(
            File("src/main/layouts/arab_pc_ir.xml"),
            includeLocCorners = true, appendBottomRow = false, aspect = GeoLayoutFixtures.DEFAULT_ASPECT,
        )
        val untypeable = ranked.filter { LayoutProjection.project(it, layout) == null }
        // Persian PC has no key or corner for أ ؤ إ (the pack keeps them: hamza is never folded),
        // so exactly the words spelled with one of them cannot be templated — 172 of 50,000 on
        // the 2026-10-23 build. Nothing else may fail.
        assertWithMessage("untypeable fa words on Persian PC that do not use أ ؤ إ")
            .that(untypeable.filterNot { w -> w.any { it in "أؤإ" } }).isEmpty()
        assertWithMessage("share of fa words the geometric engine can template on Persian PC")
            .that(1.0 - untypeable.size.toDouble() / ranked.size).isAtLeast(0.995)
        // The half-space is not a key: both spellings swipe the same letter path.
        assertThat(LayoutProjection.project("می‌شود", layout)!!.toList())
            .isEqualTo(LayoutProjection.project("میشود", layout)!!.toList())
    }
}
