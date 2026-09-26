package tribixbite.cleverkeys

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
 * 2. **Y is a letters-only word of at least 2 characters.** Single letters are never swiped.
 *    An apostrophe/hyphen form is excluded because accepting the offer would not make swipes
 *    produce it — on the default EN CTC path it would make the REJECTED word likelier
 *    (investigated 2026-09-26; pinned by `swipe.SwipePreferJoinerWordTest`):
 *    - the EN trie is built by `CtcLexiconTrie.loadStrippingNonAlphabet`, which files the user
 *      word `she'd` under the a–z surface `shed` and keeps the max frequency per surface — so
 *      the entry lifts `shed`, the word being corrected away from, to the user ceiling;
 *    - the displayed form of a decoded `shed` is `ContractionOverlay`'s call: `she'd` goes
 *      ahead only when its PAIRING frequency beats `shed`'s merged-lexicon frequency by
 *      `PROMOTION_MARGIN`, and neither number is touched by a user word `she'd` — `shed` stays
 *      the auto-insert;
 *    - a hyphen word has no overlay entry and the EN branch keeps no display map, so `co-op`
 *      only ever surfaces as `coop`.
 *    (The CKDT languages do keep a display map and a user word would win its surface's display
 *    slot, but it would also make the apostrophe-free homograph — fr `lune` for `l'une` —
 *    unswipeable, and the geometric engine skips joiner forms. One rule for all engines: out.)
 *    Making "prefer" work for these needs decoder changes — a user-word term in the overlay's
 *    promotion rule and an EN display map for joiner user words — not an offer change.
 * 3. **Y is a real word**: in the lexicon / user dictionary, or learnable by repetition
 *    (`WordPredictor.isInDictionary` ∨ `isInUserVocabulary`), and not disabled. A typo typed
 *    after an undo is not evidence about the decoder.
 * 4. **Y is plausibly the same gesture**, either
 *    - **Y was one of the engine candidates for X's swipe** (the slate shown in the bar — a bar
 *      tap always satisfies this, which is why the tap route relies on rules 1–3 plus the
 *      consent step below), or
 *    - **Y starts and ends on the same letters as X and differs in length by at most 1**
 *      (letters of X only, so `I'd` compares as `id`). A swipe starts on the first letter's key
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
        if (y.length < MIN_WORD_LENGTH || !y.all { it.isLetter() }) return false
        if (!isRealWord(y)) return false
        if (rejected.slate.any { it.lowercase(Locale.ROOT) == y }) return true
        val xLetters = x.filter { it.isLetter() }
        return xLetters.isNotEmpty() &&
            y.first() == xLetters.first() &&
            y.last() == xLetters.last() &&
            abs(y.length - xLetters.length) <= 1
    }

    /** The rejected swipes of [correction] that pass [isPlausible] against its chosen word. */
    fun plausibleRejections(
        correction: SwipeCorrectionTracker.Correction,
        isRealWord: (String) -> Boolean,
    ): List<SwipeCorrectionTracker.SwipeRecord> =
        correction.rejected.filter { isPlausible(it, correction.chosen, isRealWord) }

    /**
     * Offer "Prefer “Y” when swiping?" once Y has [OFFER_MIN_CORRECTIONS] recorded corrections,
     * unless Y is already in the personal dictionary (nothing to offer — it already carries the
     * user frequency) or the user declined Y before.
     */
    fun shouldOffer(corrections: Int, isUserDictionaryWord: Boolean, declined: Boolean): Boolean =
        corrections >= OFFER_MIN_CORRECTIONS && !isUserDictionaryWord && !declined
}
