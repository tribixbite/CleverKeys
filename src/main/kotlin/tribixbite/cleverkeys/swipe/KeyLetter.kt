package tribixbite.cleverkeys.swipe

import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.KeyboardData
import java.util.Locale

/**
 * THE single definition of "the letter a key's CENTRE value contributes to CTC eligibility".
 *
 * Two things consume this question and they MUST agree, or the settings card lies about what
 * the engine will do:
 *  - `CtcEngineAdapter.buildMappedLayout` — the real routing gate: a layout is CTC-eligible
 *    only if every alphabet letter is `seen` among the centre letters of its key rects.
 *  - `SwipeEngineFallback` — the settings-card predicate that EXPLAINS a layout-caused
 *    fallback to the user (unrouted script / alphabet incomplete / letters on corners only).
 *
 * They briefly held two private copies of this function (found in the 2026-09-01 audit of
 * commit `5fb58037`), which agreed on every practical input but had nothing tying them
 * together — the exact silent-drift class the one-implementation rule exists for.
 * `SwipeEngineFallbackTest` pins that both files consume this helper and neither re-declares
 * a private variant.
 *
 * Semantics (union of both former copies, strictest of each):
 *  - only `Char`/`String` key values qualify; anything else is not a letter key;
 *  - the RAW value must be a single code unit BEFORE case folding (a `"ss"` ligature
 *    expansion or multi-char string never counts as a centre letter);
 *  - folding is `Locale.ROOT` lowercase, and must still be a single code unit after folding
 *    (Turkish dotted-İ folds to two code units and is correctly rejected — dotless-ı keeps
 *    Turkish geometric for exactly this family of reasons);
 *  - the result must satisfy [Char.isLetter] (digits/punctuation keys answer null; both
 *    callers additionally filter against the model alphabet downstream, so this filter is
 *    behavior-neutral there and exists to keep THIS function's answer honest on its own).
 *
 * Deliberately NOT alphabet-filtered: "is this key a single letter" stays a pure question;
 * the alphabet is applied by each caller (`buildMappedLayout` via `slotOf`, the fallback
 * card via its missing-letters set). Corner values (`key1..key4`, `ne/nw/se/sw`) are the
 * CALLER's concern — `KeyboardGeometry.computeKeyRects` emits `keys[0]` only, which is why
 * ЙЦУКЕН's `ё`/`ъ` are not emission slots and are folded away by the projection instead.
 */
internal object KeyLetter {

    fun centreLetterOf(kv: KeyValue?): Char? {
        val raw = when (kv?.getKind()) {
            KeyValue.Kind.Char -> kv.getChar().toString()
            KeyValue.Kind.String -> kv.getString()
            else -> return null
        }
        if (raw.length != 1) return null
        val folded = raw.lowercase(Locale.ROOT)
        return folded.singleOrNull()?.takeIf(Char::isLetter)
    }

    /**
     * THE rule for which key a word swipe may START on (Seeker report, 2026-10-07): only a key
     * whose centre value is a single letter. Backspace, Shift, Enter, Space, Ctrl/Fn, digits
     * and punctuation keep their own tap/short-swipe handling even when the finger then
     * crosses letters — a swipe-left from Backspace over `m n b` used to latch swipe typing
     * and insert "mb". Consumed by every word-swipe route in `Pointers` (move-time latch,
     * touch-up SWIPE classification, word-candidate subkeys, return-trip rescue) and by
     * `Keyboard2View.onSwipeStart` (continuous phrase sessions). It is the same predicate the
     * recognizer applies when registering keys, so a letter that starts a swipe is also the
     * swipe's first registered key. A gesture that starts on a letter and later crosses the
     * spacebar (continuous multi-word swipe) is unaffected.
     *
     * `Pointers.mayBeWordSwipe` adds one exception for single-word swipes (typing audit,
     * 2026-10-08): a non-letter CHARACTER start (space, digit, punctuation) is promoted once the
     * finger has left that key and the recognizer has registered two letters — a sloppy start
     * one key off the first letter. Non-character keys (Backspace, Shift, Enter, Tab,
     * modifiers) never are. Continuous phrase sessions keep this letter-only rule: they are
     * armed at touch-down, before the gesture can qualify.
     */
    fun startsWordSwipe(key: KeyboardData.Key?): Boolean = centreLetterOf(key?.keys?.getOrNull(0)) != null
}
