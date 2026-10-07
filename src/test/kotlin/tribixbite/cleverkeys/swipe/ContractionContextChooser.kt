package tribixbite.cleverkeys.swipe

import tribixbite.cleverkeys.NextWordPredictor
import tribixbite.cleverkeys.StaticContextLm
import java.util.Locale
import kotlin.math.ln

/**
 * Context-driven choice between the two DISPLAY forms of one swiped letter surface — `its` /
 * `it's`, `were` / `we're`, `teams` / `team's` — using the previous word and the shipped static
 * context LM (`assets/lm/en.cklm`).
 *
 * ## Why this exists, and why here
 *
 * No swipe engine can spell an apostrophe: CTC and geometric decode letters only, and
 * [ContractionOverlay] adds the apostrophe forms afterwards. When a surface has two readings the
 * trace is IDENTICAL for both, so only a prior can separate them. The overlay's prior is
 * context-free (pairing vs lexicon frequency, [ContractionOverlay.PROMOTION_MARGIN]) because it
 * runs inside the engine adapters, where no context exists. The maintainer's direction
 * (2026-10-07) is that grammar/context should decide — "of its", "the cats" — rather than any
 * in-stroke apostrophe gesture.
 *
 * The decision is Bayes on the one thing that differs: with the trace likelihood equal for both
 * forms, the posterior ratio is `P(a | prev) / P(b | prev)`. The static LM already answers
 * `P(w | prev)` (listed pairs, else backoff mass spread by unigram), names contractions by
 * display form, and resolves REPLACE aliases ([StaticContextLm.withReplaceAliases]) — so no new
 * data and no hand rules are needed.
 *
 * ## What it may change — deliberately tiny
 *
 * Only ever SWAPS slots 0 and 1, and only when they are two forms of ONE surface (the overlay
 * splices a sibling right beside its base). Everything else on the slate keeps its place, and the
 * form that loses the swap is still one tap away at slot 1 — a ranking, never a forced form. That
 * matters for pairs the previous word cannot settle (`the cats` / `the cat's` both follow "the";
 * the FOLLOWING word decides, and it is not typed yet).
 *
 * It never acts when: the language is not English (no other language's display-form choice has
 * been evaluated — fr/it elision placement stays curated), there is no previous word in the
 * current sentence segment, the LM has no continuations for that word, either form is unknown
 * to the LM, slot 0 is a user word (the user's explicit claim — `UserJoinerPreference` rule 0,
 * or a user `id` over `i'd` — always wins), or the lead does not clear [Params.minLogOdds].
 *
 * This is NOT the closed swipe context rescoring ([SwipeContextRescorer], default-OFF by
 * maintainer decision): that reorders DISTINCT decoded words using learned history. This chooses
 * between spellings of the same decoded letters with a static, shipped model; no learned data
 * is read and nothing is learned.
 *
 * ## Status: EVALUATED, NOT SHIPPED (2026-10-07) — which is why this lives in TEST sources
 *
 * `docs/eval/2026-10-07-apostrophe-context.md`: stage 1 (LM held-out + UD EWT, pre-registered)
 * did not meet its bar (the possessive-promoting arm missed OOD and `years`); the frozen
 * non-possessive arm then failed its independent Common Voice confirmation (+0.39 pt against a
 * +1.0 pt bar), with `shed`/`shell` REVERSING (85 % → 15 %, 76 % → 38 %): the LM's web/Tatoeba
 * prior says `she'd`, literary text says `shed`. The gain is real but domain-dependent and
 * concentrated on `its`. It is kept here, with its decision tests, as the instrument of that
 * evaluation and the starting point for the next attempt (see the doc's "Next steps"); moving
 * it to `src/main` and wiring it is a NEW decision that needs its own passing evaluation.
 *
 * Pure JVM (no Android imports) so the rule and its evaluation run in `runPureTests`.
 */
object ContractionContextChooser {

    /** How strong the LM's evidence must be before the challenger may take slot 0. */
    enum class Evidence {
        /** Any [StaticContextLm.probability] (a backoff estimate counts). */
        ANY,

        /** The challenger's pair must be one of `prev`'s stored continuations. */
        LISTED,
    }

    /**
     * @property minLogOdds the challenger takes slot 0 only when
     *   `ln P(challenger | prev) − ln P(current | prev)` is STRICTLY greater than this.
     * @property evidence see [Evidence].
     * @property promotePossessives whether a possessive ([ContractionOverlay.isPossessive]) may be
     *   swapped into slot 0.
     */
    data class Params(
        val minLogOdds: Double,
        val evidence: Evidence,
        val promotePossessives: Boolean,
    )

    /** The probabilities the decision needs — [forStaticLm] adapts the shipped model. */
    interface FormModel {
        /** Does [prev] have stored continuations (i.e. any context evidence at all)? */
        fun hasContext(prev: String): Boolean

        /** P([form] | [prev]) with backoff; 0 when [form] is unknown to the model. */
        fun probability(prev: String, form: String): Float

        /** P([form] | [prev]) only when the pair is stored, else 0. */
        fun listedProbability(prev: String, form: String): Float
    }

    /** [FormModel] over a [StaticContextLm] — pass the model with its REPLACE aliases installed. */
    fun forStaticLm(lm: StaticContextLm): FormModel = object : FormModel {
        override fun hasContext(prev: String) = lm.hasContext(prev)
        override fun probability(prev: String, form: String) = lm.probability(prev, form)
        override fun listedProbability(prev: String, form: String) = lm.listedProbability(prev, form)
    }

    /**
     * The previous word for the decision: the last token of the CURRENT sentence segment of
     * [textBeforeCursor], tokenized exactly as the LM's training text and the next-word path
     * ([NextWordPredictor.contextFromEditorText]); typographic apostrophes folded to ASCII as the
     * LM builder folds them. Null at a sentence start or when no text is available.
     */
    fun previousWord(textBeforeCursor: CharSequence?): String? {
        if (textBeforeCursor.isNullOrEmpty()) return null
        val folded = textBeforeCursor.toString().replace('’', '\'').replace('‘', '\'')
        return NextWordPredictor.contextFromEditorText(folded, maxWords = 1).lastOrNull()
    }

    /**
     * True when [a] and [b] are two DIFFERENT display forms of one letter surface: equal once
     * apostrophes (ASCII or typographic) and hyphens are removed and case is folded, but not
     * equal as written (case-insensitively).
     */
    fun sameSurface(a: String, b: String): Boolean {
        val la = a.lowercase(Locale.ROOT).replace('’', '\'')
        val lb = b.lowercase(Locale.ROOT).replace('’', '\'')
        return la != lb && surfaceKey(la) == surfaceKey(lb)
    }

    private fun surfaceKey(form: String): String = form.filterNot { it == '\'' || it == '’' || it == '-' }

    /**
     * The slate with slots 0 and 1 swapped when context prefers slot 1's form, else the SAME
     * list instances unchanged (see the class doc for every no-action case).
     *
     * @param words overlaid candidates, best first (case as they will be shown).
     * @param scores parallel scores; kept POSITIONAL, so they stay non-increasing.
     * @param language active decode language; only English (incl. regional variants) is acted on.
     * @param previousWord the previous word ([previousWord]), or null.
     * @param model the static LM for [language] ([forStaticLm]), or null when none is loaded.
     * @param isUserWord true for a word the user put in their dictionary (case-insensitive).
     */
    fun choose(
        words: List<String>,
        scores: List<Int>,
        language: String?,
        previousWord: String?,
        model: FormModel?,
        isUserWord: (String) -> Boolean,
        params: Params,
    ): Pair<List<String>, List<Int>> {
        val unchanged = words to scores
        if (model == null || words.size < 2 || scores.size != words.size) return unchanged
        if (SwipeContractionPolicy.baseSubtag(language) != SwipeContractionPolicy.ENGLISH) return unchanged
        val prev = previousWord?.trim()?.lowercase(Locale.ROOT)?.replace('’', '\'')
        if (prev.isNullOrEmpty()) return unchanged

        val current = words[0]
        val challenger = words[1]
        if (!sameSurface(current, challenger)) return unchanged
        if (isUserWord(current)) return unchanged
        if (!params.promotePossessives && ContractionOverlay.isPossessive(challenger)) return unchanged
        if (!model.hasContext(prev)) return unchanged

        val currentLower = current.lowercase(Locale.ROOT).replace('’', '\'')
        val challengerLower = challenger.lowercase(Locale.ROOT).replace('’', '\'')
        val pCurrent = model.probability(prev, currentLower)
        val pChallenger = when (params.evidence) {
            Evidence.ANY -> model.probability(prev, challengerLower)
            Evidence.LISTED -> model.listedProbability(prev, challengerLower)
        }
        if (pCurrent <= 0f || pChallenger <= 0f) return unchanged
        val logOdds = ln(pChallenger.toDouble()) - ln(pCurrent.toDouble())
        if (logOdds <= params.minLogOdds) return unchanged

        val swapped = ArrayList<String>(words)
        swapped[0] = challenger
        swapped[1] = current
        return swapped to scores
    }
}
