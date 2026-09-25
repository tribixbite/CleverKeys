package tribixbite.cleverkeys.pinyin

/**
 * Late-bound seam between a pipeline that produces pinyin surfaces and the composing
 * session. Implemented by [PinyinController]; consumed by `SuggestionHandler` so the
 * swipe pipeline can hand the decoded spelling to the IME instead of auto-committing it.
 *
 * Kept as a separate interface so `SuggestionHandler` (a core class with no pinyin
 * dependency) only ever sees this contract.
 */
interface PinyinComposingHook {

    /**
     * Feed the decoded swipe slate into the composing session.
     *
     * @return true when a composing session consumed the slate — the caller MUST NOT
     *   auto-insert, rescore, or display the words as English suggestions. False when no
     *   session is active; the caller's normal swipe flow runs untouched.
     */
    fun onSwipeDecoded(predictions: List<String>, scores: List<Int>): Boolean
}
