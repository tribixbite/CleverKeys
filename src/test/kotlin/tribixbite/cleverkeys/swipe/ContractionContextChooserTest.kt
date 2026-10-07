package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.StaticContextLm
import tribixbite.cleverkeys.StaticContextLmFixtures
import tribixbite.cleverkeys.swipe.ContractionContextChooser.Evidence
import tribixbite.cleverkeys.swipe.ContractionContextChooser.Params
import kotlin.math.ln

/**
 * [ContractionContextChooser] decision matrix — the context-driven choice between two DISPLAY
 * forms of one swiped letter surface (`its`/`it's`), evaluated in
 * `docs/eval/2026-10-07-apostrophe-context.md`.
 *
 * The model is a hand-built CKLM ([StaticContextLmFixtures]) so the probabilities are exact and
 * the tests hold the decision rule still independently of the shipped asset.
 */
class ContractionContextChooserTest {

    private val unigrams = mapOf(
        "of" to 0.02, "its" to 0.002, "it's" to 0.004, "i" to 0.02, "think" to 0.003,
        "the" to 0.05, "cats" to 0.0004, "cat's" to 0.0001, "we" to 0.01, "were" to 0.003,
        "we're" to 0.001, "they" to 0.01, "well" to 0.002, "we'll" to 0.0005,
    )
    private val table = mapOf(
        // "of its" is listed and strong; "of it's" is not listed (backoff only).
        "of" to mapOf("its" to 0.02, "the" to 0.3),
        // "think it's" listed and strong.
        "think" to mapOf("it's" to 0.05, "the" to 0.05),
        // "the cats" vs "the cat's": both listed, plural ahead.
        "the" to mapOf("cats" to 0.002, "cat's" to 0.001),
        "they" to mapOf("were" to 0.08),
        // Only so the model NAMES these forms (its vocabulary is previous words ∪ continuations).
        "we" to mapOf("we're" to 0.1, "well" to 0.001, "we'll" to 0.2),
    )
    private val lm: StaticContextLm = StaticContextLm.parse(StaticContextLmFixtures.encode(unigrams, table))
    private val model = ContractionContextChooser.forStaticLm(lm)

    private val anyEvidence = Params(minLogOdds = ln(2.0), evidence = Evidence.ANY, promotePossessives = true)

    private fun choose(
        words: List<String>,
        prev: String?,
        params: Params = anyEvidence,
        language: String? = "en",
        isUserWord: (String) -> Boolean = { false },
        scores: List<Int> = List(words.size) { i -> 900 - i * 100 },
    ) = ContractionContextChooser.choose(words, scores, language, prev, model, isUserWord, params)

    // ── the swap ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `after think the contraction takes slot 0 and the literal stays one tap away`() {
        val (words, scores) = choose(listOf("its", "it's", "ots"), prev = "think")
        assertThat(words).containsExactly("it's", "its", "ots").inOrder()
        // Positional scores: still non-increasing.
        assertThat(scores).containsExactly(900, 800, 700).inOrder()
    }

    // ── round 2: the its-only scope (eval doc §6.0, N3) ──────────────────────────────────

    private val itsOnly = Params(0.0, Evidence.ANY, promotePossessives = false, surfaces = setOf("its"))

    @Test
    fun `its-only scope acts on its and it's in either order`() {
        assertThat(choose(listOf("its", "it's"), prev = "think", params = itsOnly).first)
            .containsExactly("it's", "its").inOrder()
        assertThat(choose(listOf("IT'S", "ITS"), prev = "of", params = itsOnly).first)
            .containsExactly("ITS", "IT'S").inOrder()
    }

    @Test
    fun `its-only scope leaves every other same-surface pair untouched`() {
        // were/we're and well/we'll would swap under the unrestricted arm after "we".
        for (slate in listOf(listOf("were", "we're"), listOf("well", "we'll"), listOf("cats", "cat's"))) {
            val out = choose(slate, prev = "we", params = itsOnly).first
            assertThat(out).isSameInstanceAs(slate)
        }
        val unrestricted = choose(listOf("were", "we're"), prev = "we", params = itsOnly.copy(surfaces = null)).first
        assertThat(unrestricted).containsExactly("we're", "were").inOrder()
    }

    @Test
    fun `after of the possessive determiner keeps slot 0`() {
        val (words, _) = choose(listOf("its", "it's"), prev = "of")
        assertThat(words).containsExactly("its", "it's").inOrder()
    }

    @Test
    fun `a promoted contraction is demoted when context prefers the literal`() {
        // The overlay put it's ahead (as it does for i'd/i'll); "of" says its.
        val (words, _) = choose(listOf("it's", "its"), prev = "of")
        assertThat(words).containsExactly("its", "it's").inOrder()
    }

    @Test
    fun `case of each entry is preserved through the swap`() {
        val (words, _) = choose(listOf("ITS", "IT'S"), prev = "think")
        assertThat(words).containsExactly("IT'S", "ITS").inOrder()
    }

    // ── no evidence, no action ─────────────────────────────────────────────────────────

    @Test
    fun `no previous word leaves the slate untouched`() {
        val input = listOf("its", "it's")
        val (words, scores) = choose(input, prev = null)
        assertThat(words).isSameInstanceAs(input)
        assertThat(scores).containsExactly(900, 800).inOrder()
        assertThat(choose(input, prev = "  ").first).isSameInstanceAs(input)
    }

    @Test
    fun `a previous word without LM context leaves the slate untouched`() {
        val input = listOf("its", "it's")
        assertThat(choose(input, prev = "zebra").first).isSameInstanceAs(input)
    }

    @Test
    fun `a form unknown to the LM leaves the slate untouched`() {
        val input = listOf("shed", "she'd")
        assertThat(choose(input, prev = "think").first).isSameInstanceAs(input)
    }

    @Test
    fun `a lead under the margin leaves the slate untouched`() {
        // they → were listed (0.08); we're backs off. The literal leads — and even the reverse
        // question (we're over were) has no lead, so nothing moves.
        val input = listOf("were", "we're")
        assertThat(choose(input, prev = "they").first).isSameInstanceAs(input)
    }

    @Test
    fun `the margin is a strict lower bound on the log-odds`() {
        // Under "think": it's listed at 0.05; its unlisted → alpha(think) * P(its).
        val pIts = model.probability("think", "its")
        val pIt = model.probability("think", "it's")
        val lead = ln(pIt.toDouble()) - ln(pIts.toDouble())
        val at = Params(minLogOdds = lead, evidence = Evidence.ANY, promotePossessives = true)
        assertThat(choose(listOf("its", "it's"), prev = "think", params = at).first)
            .containsExactly("its", "it's").inOrder()
        val under = at.copy(minLogOdds = lead - 1e-6)
        assertThat(choose(listOf("its", "it's"), prev = "think", params = under).first)
            .containsExactly("it's", "its").inOrder()
    }

    @Test
    fun `listed evidence requires the challenger to be a stored continuation`() {
        val listed = anyEvidence.copy(evidence = Evidence.LISTED)
        // think → it's IS listed: swap.
        assertThat(choose(listOf("its", "it's"), prev = "think", params = listed).first)
            .containsExactly("it's", "its").inOrder()
        // Under "of" neither well nor we'll is listed: the backoff lead (unigram ratio) is
        // allowed under ANY…
        val ratioModel = Params(minLogOdds = 0.0, evidence = Evidence.ANY, promotePossessives = true)
        val weAny = choose(listOf("we'll", "well"), prev = "of", params = ratioModel).first
        assertThat(weAny).containsExactly("well", "we'll").inOrder()
        // …but not under LISTED.
        val weListed = choose(listOf("we'll", "well"), prev = "of",
            params = ratioModel.copy(evidence = Evidence.LISTED)).first
        assertThat(weListed).containsExactly("we'll", "well").inOrder()
    }

    // ── what it must never touch ───────────────────────────────────────────────────────

    @Test
    fun `slots 0 and 1 that are different surfaces are never reordered`() {
        // world is not a form of would; the chooser only swaps two forms of ONE surface.
        val input = listOf("its", "ots", "it's")
        assertThat(choose(input, prev = "think").first).isSameInstanceAs(input)
        val single = listOf("its")
        assertThat(choose(single, prev = "think").first).isSameInstanceAs(single)
    }

    @Test
    fun `a user word at slot 0 is never displaced`() {
        // UserJoinerPreference rule 0 put the user's form first; a user base word (`id` over
        // `i'd`) keeps its slot too. Context never overrides an explicit user claim.
        val input = listOf("its", "it's")
        val out = choose(input, prev = "think", isUserWord = { it.equals("its", ignoreCase = true) })
        assertThat(out.first).isSameInstanceAs(input)
    }

    @Test
    fun `non-English slates are never touched`() {
        val input = listOf("its", "it's")
        assertThat(choose(input, prev = "think", language = "fr").first).isSameInstanceAs(input)
        assertThat(choose(input, prev = "think", language = "de-DE").first).isSameInstanceAs(input)
        // en regional variants are English.
        assertThat(choose(input, prev = "think", language = "en-GB").first)
            .containsExactly("it's", "its").inOrder()
    }

    @Test
    fun `no model leaves the slate untouched`() {
        val input = listOf("its", "it's")
        val out = ContractionContextChooser.choose(input, listOf(900, 800), "en", "think", null, { false }, anyEvidence)
        assertThat(out.first).isSameInstanceAs(input)
    }

    @Test
    fun `mismatched score list leaves the slate untouched`() {
        val input = listOf("its", "it's")
        val out = ContractionContextChooser.choose(input, listOf(900), "en", "think", model, { false }, anyEvidence)
        assertThat(out.first).isSameInstanceAs(input)
    }

    @Test
    fun `possessive promotion follows the parameter`() {
        // A model where "the cat's" leads by more than the margin.
        val lm2 = StaticContextLm.parse(StaticContextLmFixtures.encode(
            mapOf("the" to 0.05, "cats" to 0.0004, "cat's" to 0.0001),
            mapOf("the" to mapOf("cat's" to 0.01, "cats" to 0.001)),
        ))
        val m2 = ContractionContextChooser.forStaticLm(lm2)
        val input = listOf("cats", "cat's")
        val allowed = ContractionContextChooser.choose(input, listOf(900, 900), "en", "the", m2, { false }, anyEvidence)
        assertThat(allowed.first).containsExactly("cat's", "cats").inOrder()
        val kept = ContractionContextChooser.choose(input, listOf(900, 900), "en", "the", m2, { false },
            anyEvidence.copy(promotePossessives = false))
        assertThat(kept.first).isSameInstanceAs(input)
    }

    // ── previous-word extraction ───────────────────────────────────────────────────────

    @Test
    fun `previous word is the last token of the current sentence segment`() {
        assertThat(ContractionContextChooser.previousWord("I think ")).isEqualTo("think")
        assertThat(ContractionContextChooser.previousWord("Look at the dog. ")).isNull()
        assertThat(ContractionContextChooser.previousWord("one of ")).isEqualTo("of")
        assertThat(ContractionContextChooser.previousWord("")).isNull()
        assertThat(ContractionContextChooser.previousWord(null)).isNull()
        // Typographic apostrophes fold to ASCII, as the LM builder folds them.
        assertThat(ContractionContextChooser.previousWord("I don’t ")).isEqualTo("don't")
    }

    @Test
    fun `surface key folds apostrophes and case`() {
        assertThat(ContractionContextChooser.sameSurface("It's", "its")).isTrue()
        assertThat(ContractionContextChooser.sameSurface("girls'", "girls")).isTrue()
        assertThat(ContractionContextChooser.sameSurface("it’s", "ITS")).isTrue()
        assertThat(ContractionContextChooser.sameSurface("its", "its")).isFalse() // same form, not two forms
        assertThat(ContractionContextChooser.sameSurface("would", "world")).isFalse()
    }
}
