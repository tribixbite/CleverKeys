package tribixbite.cleverkeys.autocorrect

import android.os.Trace
import android.util.Log
import android.util.Xml
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.kxml2.io.KXmlParser
import tribixbite.cleverkeys.BinaryDictionaryLoader
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.Defaults
import tribixbite.cleverkeys.KeyboardData
import tribixbite.cleverkeys.WordPredictor
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile

/**
 * Neighbour-key typos on a Hebrew board (Saga report, 2026-10-08: Hebrew pack bound to the
 * Hebrew layout — ordinary neighbour typos were not corrected on space, English ones were).
 *
 * Root cause: [KeyAdjacency] positioned only Latin letters, so every Hebrew substitution
 * cost 1.0 whether the keys touch or not. A one-letter neighbour typo then scored no better
 * than any one-letter change, and lost to transpositions (`החא` → `האח`), to insertions and
 * deletions (`רר` → `גרר`, `אתכ` → `את`) or, for two-letter words, failed the threshold.
 *
 * Runs the REAL autocorrect sweep against the REAL shipped Hebrew pack dictionary
 * (`scripts/dictionaries/langpack-he.zip`, loaded by the production [BinaryDictionaryLoader])
 * with the REAL `hebr_1_il` layout installed through the production entry points
 * ([LayoutLetterGrid.of] → [KeyAdjacency.setLayoutLetters]), at the shipped autocorrect
 * defaults. Harness pattern: [AutoCorrectEndToEndTest] (mock tier — `Log`, `Trace` and the
 * XML parser are android.jar stubs).
 *
 * Every case below is a one-key neighbour typo on `hebr_1_il`
 * ([typosAreRealNeighbourTypos] proves it from the layout) of a top-100 Hebrew word, and none
 * of the typos is itself a dictionary word.
 */
class HebrewLayoutAutoCorrectTest {

    companion object {
        private lateinit var predictor: WordPredictor
        private lateinit var grid: Map<Char, Pair<Float, Float>>
        private lateinit var dict: Map<String, Int>

        /** typo → intended word. */
        private val NEIGHBOUR_TYPOS = linkedMapOf(
            "החא" to "הוא",   // ח↔ו; was "corrected" to the anagram האח
            "אהי" to "אני",   // ה↔נ; was האי
            "ביא" to "היא",   // ב↔ה; was באי
            "רחד" to "אחד",   // ר↔א; was חרד
            "אתכ" to "אתה",   // כ↔ה; was the deletion את
            "סאת" to "זאת",   // ס↔ז; was the deletion את
            "ךמה" to "למה",   // ך↔ל; was the deletion מה
            "שםך" to "שלך",   // ם↔ל; was the deletion שם
            "זנ" to "זה",     // ה↔נ; two letters: was the anagram נז
            "טש" to "יש",     // ט↔י; two letters: was the anagram שט
        )

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            mockkStatic(Log::class, Trace::class, Xml::class)
            every { Log.d(any(), any()) } returns 0
            every { Log.i(any(), any()) } returns 0
            every { Log.w(any(), any<String>()) } returns 0
            every { Log.e(any(), any()) } returns 0
            every { Log.e(any(), any(), any()) } returns 0
            every { Trace.beginSection(any()) } returns Unit
            every { Trace.endSection() } returns Unit
            every { Xml.newPullParser() } answers { KXmlParser() }

            // The shipped pack dictionary, through the production loader.
            val bin = File.createTempFile("langpack-he-dictionary", ".bin").apply { deleteOnExit() }
            ZipFile("scripts/dictionaries/langpack-he.zip").use { zip ->
                zip.getInputStream(zip.getEntry("dictionary.bin")).use { input ->
                    bin.outputStream().use { input.copyTo(it) }
                }
            }
            val loaded = HashMap<String, Int>()
            check(BinaryDictionaryLoader.loadDictionaryWithPrefixIndexFromFile(bin, loaded, HashMap())) {
                "Hebrew pack dictionary failed to load"
            }
            dict = loaded

            grid = LayoutLetterGrid.of(
                KeyboardData.load_string_exn(File("src/main/layouts/hebr_1_il.xml").readText())
            )

            // Shipped defaults — the device ran these.
            val config = org.objenesis.ObjenesisStd().newInstance(Config::class.java).apply {
                autocorrect_enabled = true
                autocorrect_min_word_length = Defaults.AUTOCORRECT_MIN_WORD_LENGTH
                autocorrect_char_match_threshold = Defaults.AUTOCORRECT_CHAR_MATCH_THRESHOLD
                autocorrect_max_length_diff = Defaults.AUTOCORRECT_MAX_LENGTH_DIFF
                autocorrect_confidence_min_frequency = Defaults.AUTOCORRECT_MIN_FREQUENCY
                autocorrect_prefix_length = Defaults.AUTOCORRECT_PREFIX_LENGTH
                swipe_debug_detailed_logging = false
            }
            predictor = org.objenesis.ObjenesisStd().newInstance(WordPredictor::class.java)
            setField("dictionary", AtomicReference(loaded))
            setField("contractionAliases", emptyMap<String, String>())
            setField("customAndUserWords", emptySet<String>())
            setField("disabledWords", mutableSetOf<String>())
            setField("config", config)
            setField("cachedMaxFreqForSize", -1)
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            KeyAdjacency.setLayoutLetters(emptyMap())
            KeyAdjacency.resetLayout()
            unmockkAll()
        }

        private fun setField(name: String, value: Any?) {
            val field = WordPredictor::class.java.getDeclaredField(name)
            field.isAccessible = true
            field.set(predictor, value)
        }

        /** Keys whose centres are within 1.5 key pitches: the first ring around a key. */
        private fun touching(a: Char, b: Char): Boolean {
            val pa = grid.getValue(a)
            val pb = grid.getValue(b)
            return kotlin.math.hypot(pa.first - pb.first, pa.second - pb.second) <= 1.5f
        }
    }

    @Test
    fun theLayoutGridPositionsEveryHebrewLetterKey() {
        // 22 letters + the five final forms, all centre keys of hebr_1_il.
        assertThat(grid.keys).containsExactlyElementsIn("אבגדהוזחטיךכלםמןנסעףפץצקרשת".toList())
    }

    @Test
    fun typosAreRealNeighbourTypos() {
        for ((typo, word) in NEIGHBOUR_TYPOS) {
            assertThat(typo.length).isEqualTo(word.length)
            val diffs = typo.indices.filter { typo[it] != word[it] }
            assertWithMessage("$typo vs $word").that(diffs).hasSize(1)
            val i = diffs.single()
            assertWithMessage("$typo: ${typo[i]} and ${word[i]} must be touching keys on hebr_1_il")
                .that(touching(typo[i], word[i])).isTrue()
            assertWithMessage("$typo must not itself be a word").that(dict).doesNotContainKey(typo)
            assertWithMessage("$word must be in the pack").that(dict).containsKey(word)
        }
    }

    @Test
    fun neighbourKeyTyposCorrectToTheIntendedWord() {
        KeyAdjacency.setLayoutLetters(grid)
        val wrong = NEIGHBOUR_TYPOS.mapNotNull { (typo, word) ->
            val out = predictor.autoCorrect(typo)
            if (out == word) null else "$typo → $out (want $word)"
        }
        assertWithMessage("neighbour-key typos on the Hebrew board").that(wrong).isEmpty()
    }

    @Test
    fun hebrewNeighboursAreNeighbours() {
        KeyAdjacency.setLayoutLetters(grid)
        // Same-row and diagonal first-ring pairs, and a far pair.
        assertThat(KeyAdjacency.areNeighbors('ש', 'ד')).isTrue()
        assertThat(KeyAdjacency.areNeighbors('ח', 'ו')).isTrue()
        assertThat(KeyAdjacency.areNeighbors('ק', 'ץ')).isFalse()
        // A Hebrew/Latin pair is never adjacent: the two letters are not on one board.
        assertThat(KeyAdjacency.keyDistance('ש', 'a')).isEqualTo(1f)
    }

    @Test
    fun theHebrewGridLeavesLatinDistancesUnchanged() {
        KeyAdjacency.setLayoutLetters(emptyMap())
        val latin = ('a'..'z').toList() + listOf('é', 'ñ', 'ü', 'ß')
        val before = latin.flatMap { a -> latin.map { b -> KeyAdjacency.keyDistance(a, b) } }
        KeyAdjacency.setLayoutLetters(grid + mapOf('a' to (40f to 40f)))
        val after = latin.flatMap { a -> latin.map { b -> KeyAdjacency.keyDistance(a, b) } }
        assertWithMessage("a letter the Latin table positions keeps its QWERTY position").that(after).isEqualTo(before)
    }

    @Test
    fun theLiveKeyboardInstallsItsLetterGrid() {
        // The fix is inert unless the live view installs the grid on every layout change.
        // setKeyboard (not onLayout, which skips same-size passes), and never from a preview.
        val src = File("src/main/kotlin/tribixbite/cleverkeys/Keyboard2View.kt").readText()
        val body = src.substringAfter("fun setKeyboard(kw: KeyboardData) {").substringBefore("\n    fun ")
        assertThat(body).contains("if (!_previewMode) {")
        assertThat(body.substringAfter("if (!_previewMode) {"))
            .contains("KeyAdjacency.setLayoutLetters(")
        assertThat(body).contains("LayoutLetterGrid.of(kw)")
    }
}
