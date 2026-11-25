package tribixbite.cleverkeys.swipe.ctc

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.ln

/**
 * The contraction-alias INJECTION policy for the CTC lexicon trie ([CtcContractionKeys]).
 *
 * Injection is what makes the contraction mapping table reachable at all: the overlay can
 * only rewrite a surface the beam produced, and a productive elision (fr `dabaissement` →
 * `d'abaissement`) is not a dictionary word. The three properties that make injection SAFE
 * rather than merely possible are asserted here:
 *
 *  1. an injected pseudo-word carries a ~0 log-frequency, so the beam's `lambda * logFreq`
 *     term gives it NO boost while every real word gets a positive one;
 *  2. injecting never lowers (or raises) the frequency of a word already in the lexicon;
 *  3. a key that cannot be spelled from the alphabet is silently skipped, never inserted
 *     and never thrown on.
 */
class CtcContractionKeysTest {

    private val alphabet = CharArray(26) { 'a' + it }

    private fun trieOf(vararg words: Pair<String, Double>): CtcLexiconTrie =
        CtcLexiconTrie(alphabet).apply { words.forEach { (w, f) -> insert(w, f) } }

    @Test
    fun `a pure a-z key is injectable`() {
        assertThat(CtcContractionKeys.isInjectable("dabaissement", alphabet)).isTrue()
        assertThat(CtcContractionKeys.isInjectable("quil", alphabet)).isTrue()
        // Case-folded: the trie lowercases at insert, so an upper-case key is injectable.
        assertThat(CtcContractionKeys.isInjectable("Quil", alphabet)).isTrue()
    }

    @Test
    fun `a key carrying anything outside the alphabet is not injectable`() {
        // The two shapes the generator still trims as genuinely dead.
        assertThat(CtcContractionKeys.isInjectable("high-falutin", alphabet)).isFalse()
        assertThat(CtcContractionKeys.isInjectable("cest-à-dire", alphabet)).isFalse()
        assertThat(CtcContractionKeys.isInjectable("ceût", alphabet)).isFalse()
        assertThat(CtcContractionKeys.isInjectable("", alphabet)).isFalse()
    }

    @Test
    fun `injection makes an alias key that is not a dictionary word reachable`() {
        val trie = trieOf("abaissement" to 40.0)
        assertThat(trie.contains("dabaissement")).isFalse()

        val inserted = CtcContractionKeys.inject(trie, listOf("dabaissement"))

        assertThat(inserted).isEqualTo(1)
        assertThat(trie.contains("dabaissement")).isTrue()
        assertThat(trie.wordCount).isEqualTo(2)
    }

    @Test
    fun `an injected pseudo-word gets no frequency boost while real words do`() {
        val trie = trieOf("abaissement" to 40.0)
        CtcContractionKeys.inject(trie, listOf("dabaissement"))

        val injectedLogFreq = trie.logFrequencyOf("dabaissement")!!
        val realLogFreq = trie.logFrequencyOf("abaissement")!!

        // ln(1 + 1e-10) is ~1e-10: the beam's lambda * logFreq term contributes nothing.
        assertThat(injectedLogFreq).isWithin(1e-6).of(0.0)
        assertThat(realLogFreq).isWithin(1e-9).of(ln(40.0 + 1e-10))
        assertWithMessage("an injected alias must never outrank real vocabulary on frequency")
            .that(injectedLogFreq).isLessThan(realLogFreq)
    }

    @Test
    fun `injecting a key that is already a real word leaves its frequency untouched`() {
        // fr "la" is both a common word and the alias key of "l'a"; the overlay's real-word
        // ordinal guard depends on it keeping its real rank.
        val trie = trieOf("la" to 250.0)
        val before = trie.logFrequencyOf("la")!!

        val inserted = CtcContractionKeys.inject(trie, listOf("la"))

        assertThat(inserted).isEqualTo(0)
        assertThat(trie.wordCount).isEqualTo(1)
        assertThat(trie.logFrequencyOf("la")).isEqualTo(before)
    }

    @Test
    fun `non-injectable keys are skipped without inserting or throwing`() {
        val trie = trieOf("word" to 100.0)

        val inserted = CtcContractionKeys.inject(
            trie,
            listOf("high-falutin", "cest-à-dire", "", "quil")
        )

        assertThat(inserted).isEqualTo(1)
        assertThat(trie.contains("quil")).isTrue()
        assertThat(trie.contains("highfalutin")).isFalse()
        assertThat(trie.wordCount).isEqualTo(2)
    }

    @Test
    fun `the injected frequency is the bottom of the scale the tuned lambda expects`() {
        assertThat(CtcContractionKeys.INJECTED_FREQUENCY).isEqualTo(CtcLexiconMerge.MIN_FREQ)
    }

    // ── Attested elision frequencies (2026-11-25, todo item 35) ────────────────────────────

    @Test
    fun `an attested elision is injected at its measured frequency`() {
        // fr `d'être` occurs 1,613 times in the Leipzig web corpus: contraction_frequencies_fr.json
        // holds 168 for `detre`, so the beam can rank it by how often people write it.
        val trie = trieOf("etre" to 144.0, "dette" to 120.0)
        CtcContractionKeys.inject(trie, listOf("detre", "dete"), frequency = 67.0, attested = mapOf("detre" to 168.0))
        assertThat(trie.logFrequencyOf("detre")!!).isWithin(1e-9).of(ln(168.0 + 1e-10))
        assertWithMessage("an unattested key keeps the floor").that(trie.logFrequencyOf("dete")!!)
            .isWithin(1e-9).of(ln(67.0 + 1e-10))
    }

    @Test
    fun `an attested value never lowers a key below the floor`() {
        val trie = trieOf("etre" to 144.0)
        CtcContractionKeys.inject(trie, listOf("detre"), frequency = 67.0, attested = mapOf("detre" to 20.0))
        assertThat(trie.logFrequencyOf("detre")!!).isWithin(1e-9).of(ln(67.0 + 1e-10))
    }

    @Test
    fun `a lexicon word that is an attested key is raised to its measured value, never lowered`() {
        // fr `quil` is a list word at the apostrophe-free token's frequency (96), though `qu'il`
        // is written 5,601 times (mapped 204.5); `la` (l'a) is far more common as itself.
        val trie = trieOf("quil" to 96.0, "la" to 250.0)
        val inserted = CtcContractionKeys.inject(
            trie, listOf("quil", "la"), frequency = 67.0, attested = mapOf("quil" to 204.5, "la" to 150.0),
        )
        assertWithMessage("nothing new was inserted").that(inserted).isEqualTo(0)
        assertThat(trie.logFrequencyOf("quil")!!).isWithin(1e-9).of(ln(204.5 + 1e-10))
        assertThat(trie.logFrequencyOf("la")!!).isWithin(1e-9).of(ln(250.0 + 1e-10))
    }

    @Test
    fun `the attested table parses the shipped asset shape`() {
        val parsed = CtcContractionKeys.parseAttestedFrequencies("{\n\"dabord\": 158.0,\n\"detre\": 168.0\n}\n")
        assertThat(parsed).containsExactly("dabord", 158.0, "detre", 168.0)
        assertThat(CtcContractionKeys.parseAttestedFrequencies("{}")).isEmpty()
    }
}
