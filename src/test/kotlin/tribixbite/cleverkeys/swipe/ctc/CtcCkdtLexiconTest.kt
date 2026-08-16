package tribixbite.cleverkeys.swipe.ctc

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.BeforeClass
import org.junit.Test
import tribixbite.cleverkeys.swipe.geometric.CkdtDictionaryReader
import java.io.File

/**
 * The non-English CTC lexicon path, over the REAL bundled CKDT dictionaries.
 *
 * The CTC beam emits a–z surfaces, so a CKDT lexicon (whose canonical forms carry
 * accents) has to be projected onto a–z before it can be a trie — and the canonical
 * form has to survive somewhere, or "café" would commit as "cafe". This exercises the
 * exact chain the adapter runs for fr/de/es:
 *
 * ```
 * CkdtDictionaryReader.readEntries → CtcCkdtLexicon.frequencyPairs (freq = max(1,255−rank))
 *   → CtcLexiconMerge.merge (user words) → CtcAzProjection.projectLexicon
 *   → CtcLexiconTrie.loadFromFrequencyMap + the stripped→canonical display map
 * ```
 *
 * Counts are pinned exactly: they are the same numbers the λ sweep harness reported
 * (`docs/eval/2026-08-15-ctc-per-language-lambda.md` — records/untypeable/distinct/
 * collisions), so a dictionary regeneration that silently changes the CTC vocabulary
 * fails here rather than in the field.
 *
 * The pure-test CWD is the project root, so the shipped assets are reachable by
 * relative `File` paths (same convention as `DictionaryBinFormatTest`).
 */
class CtcCkdtLexiconTest {

    companion object {
        private const val DICT_DIR = "src/main/assets/dictionaries"

        /** language → projected lexicon over the unmodified shipped dictionary. */
        private lateinit var projected: Map<String, CtcAzProjection.Projected>

        @JvmStatic
        @BeforeClass
        fun buildLexicons() {
            projected = listOf("fr", "de", "es").associateWith { lang ->
                val file = File("$DICT_DIR/${lang}_enhanced.bin")
                check(file.isFile) { "expected shipped lexicon at ${file.path} (run from project root)" }
                val entries = file.inputStream().use { CkdtDictionaryReader.readEntries(it) }
                val merged = CtcLexiconMerge.merge(
                    CtcCkdtLexicon.frequencyPairs(entries), emptyList(), emptySet()
                )
                CtcAzProjection.projectLexicon(merged)
            }
        }
    }

    // ── The a–z projection itself ──────────────────────────────────────────────────

    @Test
    fun `combining marks are folded onto their base letters`() {
        assertThat(CtcAzProjection.project("café")).isEqualTo("cafe")
        assertThat(CtcAzProjection.project("über")).isEqualTo("uber")
        assertThat(CtcAzProjection.project("niño")).isEqualTo("nino")
        assertThat(CtcAzProjection.project("français")).isEqualTo("francais")
        assertThat(CtcAzProjection.project("können")).isEqualTo("konnen")
        assertThat(CtcAzProjection.project("años")).isEqualTo("anos")
    }

    @Test
    fun `apostrophes and hyphens are stripped like the en trie`() {
        assertThat(CtcAzProjection.project("don't")).isEqualTo("dont")
        assertThat(CtcAzProjection.project("aujourd’hui")).isEqualTo("aujourdhui")
        assertThat(CtcAzProjection.project("co-op")).isEqualTo("coop")
    }

    @Test
    fun `case is folded`() {
        assertThat(CtcAzProjection.project("Paris")).isEqualTo("paris")
        assertThat(CtcAzProjection.project("MÜNCHEN")).isEqualTo("munchen")
    }

    @Test
    fun `letters with no a-z decomposition are untypeable, not mangled`() {
        // ß / œ / æ / ø survive NFD as non-a–z characters. Mangling them (ß→ss) would
        // invent a surface the model never emits for that word; dropping the word is
        // the validated policy (the λ sweep measured de at exactly this policy).
        assertThat(CtcAzProjection.project("straße")).isNull()
        assertThat(CtcAzProjection.project("œuvre")).isNull()
        assertThat(CtcAzProjection.project("brød")).isNull()
        assertThat(CtcAzProjection.project("ærlig")).isNull()
        // …whereas a ring-above DOES decompose (NFD å → a + U+030A), so it folds.
        assertThat(CtcAzProjection.project("måne")).isEqualTo("mane")
    }

    @Test
    fun `non-letters and empties yield null`() {
        assertThat(CtcAzProjection.project("")).isNull()
        assertThat(CtcAzProjection.project("'")).isNull()
        assertThat(CtcAzProjection.project("42")).isNull()
        assertThat(CtcAzProjection.project("e-mail@x")).isNull()
    }

    // ── Frequency scale ────────────────────────────────────────────────────────────

    @Test
    fun `ckdt rank maps onto the inverted 1 to 255 scale`() {
        assertThat(CtcCkdtLexicon.freqForRank(0)).isEqualTo(255.0)
        assertThat(CtcCkdtLexicon.freqForRank(100)).isEqualTo(155.0)
        assertThat(CtcCkdtLexicon.freqForRank(254)).isEqualTo(1.0)
        // Rank 255 would be 0 — floored to the trie's minimum so ln(freq) stays finite.
        assertThat(CtcCkdtLexicon.freqForRank(255)).isEqualTo(1.0)
    }

    @Test
    fun `frequency pairs keep the reader's rank order`() {
        val entries = listOf(
            CkdtDictionaryReader.Entry("zebra", 200),
            CkdtDictionaryReader.Entry("the", 0),
        )
        val pairs = CtcCkdtLexicon.frequencyPairs(entries)
        assertThat(pairs.map { it.first }).containsExactly("zebra", "the").inOrder()
        assertThat(pairs.map { it.second }).containsExactly(55.0, 255.0).inOrder()
    }

    // ── Real bundled dictionaries ──────────────────────────────────────────────────

    @Test
    fun `french projection counts match the sweep harness`() {
        val p = projected.getValue("fr")
        assertThat(p.records).isEqualTo(40_000)
        assertThat(p.untypeable).isEqualTo(31)
        assertThat(p.freqs.size).isEqualTo(37_949)
        assertThat(p.collisions).isEqualTo(2_020)
    }

    @Test
    fun `german projection counts match the sweep harness`() {
        val p = projected.getValue("de")
        assertThat(p.records).isEqualTo(40_000)
        assertThat(p.untypeable).isEqualTo(0)
        assertThat(p.freqs.size).isEqualTo(39_594)
        assertThat(p.collisions).isEqualTo(406)
    }

    @Test
    fun `spanish projection counts match the sweep harness`() {
        val p = projected.getValue("es")
        assertThat(p.records).isEqualTo(50_000)
        assertThat(p.untypeable).isEqualTo(0)
        assertThat(p.freqs.size).isEqualTo(47_955)
        assertThat(p.collisions).isEqualTo(2_045)
    }

    @Test
    fun `records account for every projected word`() {
        for ((lang, p) in projected) {
            assertWithMessage("$lang: records must split into untypeable + collisions + words")
                .that(p.records - p.untypeable - p.collisions).isEqualTo(p.freqs.size)
            assertWithMessage("$lang: every trie key must be pure a–z")
                .that(p.freqs.keys.all { key -> key.all { it in 'a'..'z' } }).isTrue()
            assertWithMessage("$lang: frequencies must stay on the 1..255 scale")
                .that(p.freqs.values.min()).isAtLeast(1.0)
            assertWithMessage("$lang: frequencies must stay on the 1..255 scale")
                .that(p.freqs.values.max()).isAtMost(255.0)
        }
    }

    // ── Accent display ─────────────────────────────────────────────────────────────

    @Test
    fun `accented words are reachable by their stripped surface and display accented`() {
        assertThat(projected.getValue("fr").display["cafe"]).isEqualTo("café")
        assertThat(projected.getValue("fr").display["francais"]).isEqualTo("français")
        assertThat(projected.getValue("de").display["uber"]).isEqualTo("über")
        assertThat(projected.getValue("de").display["konnen"]).isEqualTo("können")
        assertThat(projected.getValue("es").display["nino"]).isEqualTo("niño")
        assertThat(projected.getValue("es").display["anos"]).isEqualTo("años")
    }

    @Test
    fun `display map only carries surfaces whose canonical form differs`() {
        for (p in projected.values) {
            assertThat(p.display.size).isLessThan(p.freqs.size)
            for ((surface, canonical) in p.display) {
                assertThat(surface).isNotEqualTo(canonical)
                assertThat(CtcAzProjection.project(canonical)).isEqualTo(surface)
            }
            // Unaccented words are absent — the adapter falls back to the surface.
            assertThat(p.display).doesNotContainKey("de")
        }
    }

    @Test
    fun `collisions keep the highest-frequency canonical form`() {
        val merged = CtcLexiconMerge.merge(
            listOf("côte" to 200.0, "cote" to 120.0, "coté" to 90.0),
            emptyList(), emptySet()
        )
        val p = CtcAzProjection.projectLexicon(merged)
        assertThat(p.freqs).containsExactly("cote", 200.0)
        assertThat(p.display["cote"]).isEqualTo("côte")
        assertThat(p.collisions).isEqualTo(2)
    }

    @Test
    fun `an unaccented winner clears any earlier accented display entry`() {
        // Ordering must not leak: whichever canonical has the highest frequency wins the
        // display slot, even when the accented form was seen first.
        val merged = CtcLexiconMerge.merge(
            listOf("coté" to 90.0, "cote" to 200.0),
            emptyList(), emptySet()
        )
        val p = CtcAzProjection.projectLexicon(merged)
        assertThat(p.freqs).containsExactly("cote", 200.0)
        assertThat(p.display).doesNotContainKey("cote")
    }

    @Test
    fun `user custom words win the display slot for their surface`() {
        // Custom words clamp to 255 (top of the CKDT scale), so a user's own spelling
        // outranks the bundled canonical for the same stripped surface.
        val merged = CtcLexiconMerge.merge(
            listOf("café" to 144.0),
            listOf("Cafè" to 1000),
            emptySet()
        )
        val p = CtcAzProjection.projectLexicon(merged)
        assertThat(p.freqs["cafe"]).isEqualTo(255.0)
        assertThat(p.display["cafe"]).isEqualTo("Cafè")
    }

    @Test
    fun `disabled words are gone before projection`() {
        val merged = CtcLexiconMerge.merge(
            listOf("café" to 144.0, "cafeteria" to 90.0),
            emptyList(),
            setOf("café")
        )
        val p = CtcAzProjection.projectLexicon(merged)
        assertThat(p.freqs.keys).containsExactly("cafeteria")
        assertThat(p.display).isEmpty()
    }

    // ── The trie the beam actually walks ───────────────────────────────────────────

    @Test
    fun `the projected lexicon builds a full a-z trie`() {
        val alphabet = CharArray(26) { 'a' + it }
        for ((lang, p) in projected) {
            val trie = CtcLexiconTrie.loadFromFrequencyMap(alphabet, p.freqs)
            // loadFromFrequencyMap SKIPS out-of-alphabet words; the projection guarantees
            // there are none, so nothing may be lost between the two.
            assertWithMessage("$lang: the trie must keep every projected word")
                .that(trie.wordCount).isEqualTo(p.freqs.size)
        }
        val fr = CtcLexiconTrie.loadFromFrequencyMap(alphabet, projected.getValue("fr").freqs)
        assertThat(fr.contains("cafe")).isTrue()
        assertThat(fr.contains("francais")).isTrue()
    }
}
