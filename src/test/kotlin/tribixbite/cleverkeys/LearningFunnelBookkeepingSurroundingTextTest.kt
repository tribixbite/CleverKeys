package tribixbite.cleverkeys

/**
 * Every [LearningFunnelBookkeepingTest] case on the API 31+ read path, with an editor that answers
 * `getSurroundingText` with an absolute offset (TextView's BaseInputConnection). The end-of-field
 * proof ([SuggestionHandler] `caretProvenAtFieldEnd`) and [EditorSelectionReader] then read the
 * surrounding text instead of the extracted text (test-gap audit 2026-11-28, item 1).
 */
class LearningFunnelBookkeepingSurroundingTextTest : LearningFunnelBookkeepingTest() {
    override val readPath: ReadPath = ReadPath.SURROUNDING_ABSOLUTE
}

/**
 * Every [LearningFunnelBookkeepingTest] case on the API 31+ read path, with a Chrome-style editor
 * whose `getSurroundingText` reports offset -1: production must fall back to the extracted text
 * and decide exactly as below API 31 (test-gap audit 2026-11-28, item 1).
 */
class LearningFunnelBookkeepingUnknownOffsetTest : LearningFunnelBookkeepingTest() {
    override val readPath: ReadPath = ReadPath.SURROUNDING_UNKNOWN_OFFSET
}

/**
 * Every [LearningFunnelBookkeepingTest] case with an editor that lags behind the keyboard's DEL:
 * [SuggestionHandler.handleBackspace]'s read right after the DEL still shows the text from before
 * it, as an asynchronous editor (Chrome's) can answer. No rollback, undo or learning decision may
 * change (test-gap audit 2026-11-28, item 4).
 */
class LearningFunnelBookkeepingLaggingEditorTest : LearningFunnelBookkeepingTest() {
    override val editorLagsBehindDel: Boolean = true
}
