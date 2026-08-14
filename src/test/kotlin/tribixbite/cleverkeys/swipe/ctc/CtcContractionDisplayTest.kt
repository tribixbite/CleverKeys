package tribixbite.cleverkeys.swipe.ctc

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import org.junit.BeforeClass
import org.junit.Test
import tribixbite.cleverkeys.swipe.ContractionOverlay
import java.io.File

/**
 * G5 audit H1 — CTC contraction DISPLAY mapping, end-to-end over the SHIPPED
 * lexicon: `en_enhanced.json` contains zero apostrophe words (contractions are
 * a-z aliases), so a raw CTC decode surfaces "dont"/"im". The adapter overlays
 * [ContractionOverlay] with ordinals from [CtcLexiconMerge.ordinals]; this test
 * runs that exact combination (real asset ranks + production-shaped contraction
 * fixtures, mirroring `ContractionOverlayTest`'s en fixtures) and locks in:
 *
 *  - junk aliases are REPLACED: dont → don't, im → I'm, cant → can't;
 *  - paired real-word bases are KEPT with the variant appended: "well" stays
 *    "well" (+ "we'll" at the end) — the paired-contraction real-word guard;
 *  - the frequency-descending ordinal ranking of the shipped asset actually
 *    exhibits the separation `ContractionOverlay.REAL_WORD_ORDINAL_MAX` = 1200
 *    assumes (junk aliases deep, real-word bases shallow) — the threshold
 *    oracle for THIS ranking, the CTC analogue of the geometric CKDT numbers
 *    measured in the 2026-07-23 audit.
 */
class CtcContractionDisplayTest {

    companion object {
        private const val DICT_ASSET = "src/main/assets/dictionaries/en_enhanced.json"

        /** Lowercase word → frequency ordinal over the shipped, unmodified lexicon. */
        private lateinit var ordinals: HashMap<String, Int>

        @JvmStatic
        @BeforeClass
        fun loadShippedLexicon() {
            val file = File(DICT_ASSET)
            check(file.isFile) { "expected shipped lexicon at ${file.path} (run from project root)" }
            val root = JsonParser.parseString(file.readText()).asJsonObject
            val base = ArrayList<Pair<String, Double>>(root.size())
            for ((word, freq) in root.entrySet()) {
                base.add(word to freq.asDouble)
            }
            val merged = CtcLexiconMerge.merge(base, emptyList(), emptySet())
            ordinals = CtcLexiconMerge.ordinals(merged)
        }
    }

    // Production-shaped contraction fixtures (contractions.bin / contractions_en.json),
    // including the binary store's paired-entry pollution of the non-paired map that
    // makes the paired-first rule load-bearing (see ContractionOverlayTest).
    private val enPaired = mapOf(
        "well" to listOf("we'll"),
        "its" to listOf("it's"),
    )
    private val enNonPaired = mapOf(
        "dont" to "don't",
        "im" to "I'm",
        "cant" to "can't",
        "theyd" to "they'd",
        "well" to "we'll",
        "its" to "it's",
    )

    private fun applyCtc(words: List<String>, scores: List<Int>) = ContractionOverlay.apply(
        words, scores,
        pairedVariants = { enPaired[it] },
        nonPairedMapping = { enNonPaired[it] },
        wordOrdinal = { ordinals[it] },
    )

    // ── H1 decode-result mapping ────────────────────────────────────────────────────

    @Test
    fun `dont presents as don't`() {
        val (words, scores) = applyCtc(listOf("dont", "done"), listOf(900, 800))
        assertThat(words).containsExactly("don't", "done").inOrder()
        assertThat(scores).containsExactly(900, 800).inOrder()
    }

    @Test
    fun `im presents as I'm`() {
        val (words, _) = applyCtc(listOf("im"), listOf(900))
        assertThat(words).containsExactly("I'm")
    }

    @Test
    fun `cant presents as can't`() {
        val (words, _) = applyCtc(listOf("cant"), listOf(900))
        assertThat(words).containsExactly("can't")
    }

    @Test
    fun `well stays well — paired base keeps its slot, variant appended last`() {
        val (words, scores) = applyCtc(listOf("well", "wall"), listOf(900, 800))
        assertThat(words).containsExactly("well", "wall", "we'll").inOrder()
        assertThat(scores).containsExactly(900, 800, 899).inOrder()
    }

    @Test
    fun `its stays its with the variant appended`() {
        val (words, _) = applyCtc(listOf("its"), listOf(900))
        assertThat(words).containsExactly("its", "it's").inOrder()
    }

    @Test
    fun `unmapped words pass through untouched`() {
        val input = listOf("hello", "world")
        val (words, scores) = applyCtc(input, listOf(900, 800))
        assertThat(words).isEqualTo(input)
        assertThat(scores).containsExactly(900, 800).inOrder()
    }

    // ── Threshold oracle over the shipped ranking ───────────────────────────────────

    @Test
    fun `shipped lexicon ordinals separate junk aliases from real-word bases at 1200`() {
        val max = ContractionOverlay.REAL_WORD_ORDINAL_MAX
        // Non-paired junk aliases must rank DEEP (replacement is safe).
        for (alias in listOf("dont", "im", "cant", "theyd", "wont")) {
            assertThat(ordinals[alias]).isNotNull()
            assertThat(ordinals[alias]!!).isAtLeast(max)
        }
        // Real-word contraction bases must rank SHALLOW (never replaced, only
        // augmented) — the guard's protected side.
        for (word in listOf("were", "its", "well", "hell")) {
            assertThat(ordinals[word]!!).isLessThan(max)
        }
    }
}
