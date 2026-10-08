package tribixbite.cleverkeys

import tribixbite.cleverkeys.swipe.UserJoinerPreference
import java.util.Locale
import kotlin.math.abs

/**
 * Which reported swipe corrections count, and when to offer "Prefer “Y” when swiping?"
 * (learning-system audit 2026-09-26, Resolution). Pure JVM.
 *
 * ## Why the rule has to be strict
 *
 * The correction-prior replay (`docs/eval/2026-09-26-correction-driven-swipe-prior-replay.md`
 * §5a) showed that correction evidence is only useful while it is high-precision: 2 % of
 * "changed-mind" corrections (the user rejects a CORRECT swipe because they decided to write a
 * different word) turned the best measured point from 9 fixed / 4 broken into 10 / 8. So an
 * (X → Y) pair is recorded only when Y is plausibly **the same gesture** X was decoded from.
 *
 * ## The plausibility rule ([isPlausible]) — all of:
 *
 * 1. **Y ≠ X** (case-insensitively). Re-typing the word you undid is not a correction.
 * 2. **Y is a word of at least 2 letters, spelled with letters and — optionally — the joiners
 *    `'`, `’`, `-`** (never starting or ending with a hyphen). Single letters are never swiped.
 *    Until 2026-09-29 joiner words were excluded, because accepting the offer (a
 *    personal-dictionary entry) made the REJECTED word likelier: the EN trie files a user
 *    `she'd` under the a–z surface `shed`, and `ContractionOverlay` placed `she'd` vs `shed`
 *    from pairing/lexicon frequencies the user word never touched, so `shed` stayed the
 *    auto-insert. The entry is now read as a DISPLAY preference
 *    (`swipe.UserJoinerPreference`): every engine shows the user's form ahead of the decoded
 *    surface, keeps the surface behind it when it is a real word (fr `lune` behind a user
 *    `l'une` — never destroyed) and drops it when it exists only through the user word (`xray`
 *    for `x-ray`). Pinned by `swipe.SwipePreferJoinerWordTest`. One case stays unserved —
 *    see [joinerSurface]: a joiner Y whose surface is itself a user word is not offered.
 *    Geometric residue: that engine cannot decode a surface that exists only through the user
 *    word (its templates skip joiner forms), so there the offer helps only real-word surfaces.
 * 3. **Y is a real word**: in the lexicon / user dictionary, or learnable by repetition
 *    (`WordPredictor.isInDictionary` ∨ `isInUserVocabulary`), or a known contraction display
 *    form (`ContractionManager.isKnownContraction` — the dictionaries store `she'd` only as
 *    `shed`), and not disabled. A typo typed after an undo is not evidence about the decoder.
 * 4. **Y is plausibly the same gesture**, either
 *    - **Y was one of the engine candidates for X's swipe** (the slate shown in the bar — a bar
 *      tap always satisfies this, which is why the tap route relies on rules 1–3 plus the
 *      consent step below), or
 *    - **Y starts and ends on the same letters as X and differs in length by at most 1**
 *      (letters only on both sides, so `I'd` compares as `id` and `co-op` as `coop`). A swipe starts on the first letter's key
 *      and ends on the last one's; the decoder's errors are in the middle (`git`/`got`,
 *      `fix`/`fox`). A retype that changes either end — `hello` undone, `hi` typed — is a
 *      different gesture, i.e. a changed mind, and is dropped.
 *
 * What the rule cannot catch: a changed mind between two gesture neighbours (`soon` → `son`).
 * That residue is why nothing here changes ranking silently: it only counts toward an OFFER the
 * user must accept, and only after [OFFER_MIN_CORRECTIONS] corrections toward the same word.
 */
object SwipeCorrectionPolicy {

    /**
     * Corrections toward the same word Y before the offer appears. Two, because one correction
     * is too often a slip or a changed mind (94 % of the replay's correction evidence was a single
     * correction), while a user who has fixed the same word twice has shown a pattern — and the
     * reported case (`git`) flips after 1–2 corrections in the replay's git pin.
     */
    const val OFFER_MIN_CORRECTIONS = 2

    /** Shortest correction target considered (single letters are not swiped). */
    const val MIN_WORD_LENGTH = 2

    /**
     * Is (rejected → [chosen]) plausibly a correction of the same gesture? See the class KDoc
     * for the rule.
     *
     * @param isRealWord lexicon / user-dictionary / learnable membership for the lowercase word
     */
    fun isPlausible(
        rejected: SwipeCorrectionTracker.SwipeRecord,
        chosen: String,
        isRealWord: (String) -> Boolean,
    ): Boolean {
        val y = chosen.lowercase(Locale.ROOT)
        val x = rejected.word.lowercase(Locale.ROOT)
        if (y == x) return false
        // Letters, plus joiners inside the word (rule 2); the letters alone decide the length
        // and the endpoints, because the swipe traces only them.
        val yLetters = UserJoinerPreference.joinerFree(y) ?: return false
        if (yLetters.length < MIN_WORD_LENGTH || y.startsWith('-') || y.endsWith('-')) return false
        if (!isRealWord(y)) return false
        if (rejected.slate.any { it.lowercase(Locale.ROOT) == y }) return true
        val xLetters = x.filter { it.isLetter() }
        return xLetters.isNotEmpty() &&
            yLetters.first() == xLetters.first() &&
            yLetters.last() == xLetters.last() &&
            abs(yLetters.length - xLetters.length) <= 1
    }

    /**
     * The joiner-free surface of an apostrophe/hyphen word (`she'd` → `shed`), or null for a
     * letters-only word. The offer for a joiner word is suppressed while this surface is itself
     * a personal-dictionary word: the user has then claimed BOTH readings, and
     * `UserJoinerPreference` deliberately produces no preference for that state (the traced
     * literal keeps its slot — the older, shipped reverse contract), so accepting would change
     * nothing on screen. Removing the base word in the Dictionary Manager lifts it.
     */
    fun joinerSurface(word: String): String? =
        if (UserJoinerPreference.isJoinerWord(word)) UserJoinerPreference.joinerFree(word) else null

    /** The rejected swipes of [correction] that pass [isPlausible] against its chosen word. */
    fun plausibleRejections(
        correction: SwipeCorrectionTracker.Correction,
        isRealWord: (String) -> Boolean,
    ): List<SwipeCorrectionTracker.SwipeRecord> =
        correction.rejected.filter { isPlausible(it, correction.chosen, isRealWord) }

    /**
     * What accepting "Prefer “Y” when swiping?" would do, given Y's current personal-dictionary
     * state (user swipe priority, 2026-10-08 — `docs/eval/2026-10-08-user-swipe-priority.md`):
     *
     *  - `null` current (Y is not a personal-dictionary word) → [SwipePriority.NORMAL]: add Y,
     *    which gives it the personal-dictionary frequency (the shipped remedy, enough for
     *    `git` and `somethings`);
     *  - [SwipePriority.NORMAL] → [SwipePriority.HIGH]: Y is already the user's word and the
     *    swipe still keeps getting corrected toward it, so the frequency cap was not enough —
     *    the next step is the bounded priority bonus;
     *  - [SwipePriority.HIGH] / [SwipePriority.HIGHEST] → null: nothing more to offer. The bar
     *    never escalates to HIGHEST: that level takes noticeably more swipes of Y's neighbours
     *    (eval note §3) and is only set deliberately in the Dictionary Manager, where its cost
     *    is explained.
     */
    fun offerLevel(current: SwipePriority?): SwipePriority? = when (current) {
        null -> SwipePriority.NORMAL
        SwipePriority.NORMAL -> SwipePriority.HIGH
        SwipePriority.HIGH, SwipePriority.HIGHEST -> null
    }

    /**
     * Offer "Prefer “Y” when swiping?" once Y has [OFFER_MIN_CORRECTIONS] recorded corrections,
     * unless there is nothing left to offer ([offerLevel] of Y's current state is null — pass
     * null as [offerLevel] for a state with nothing to offer) or the user declined Y before.
     * Accepting resets Y's correction count, so each step needs its own fresh corrections.
     */
    fun shouldOffer(corrections: Int, offerLevel: SwipePriority?, declined: Boolean): Boolean =
        corrections >= OFFER_MIN_CORRECTIONS && offerLevel != null && !declined
}
