package tribixbite.cleverkeys

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which alternatives a committed swipe / autocorrect word carries ([SuggestionSpanPolicy]). */
class SuggestionSpanPolicyTest {

    @Test
    fun theSpanCoversTheWordNotItsAutoSpaces() {
        assertEquals(SuggestionSpanPolicy.Plan(1, 6, listOf("hallo")), SuggestionSpanPolicy.plan(" hello ", "hello", listOf("hallo")))
        assertEquals(SuggestionSpanPolicy.Plan(0, 5, listOf("hallo")), SuggestionSpanPolicy.plan("hello ", "hello", listOf("hallo")))
    }

    @Test
    fun alternativesAreDistinctBoundedAndExcludeTheWordAndSpecialEntries() {
        val plan = SuggestionSpanPolicy.plan(
            "the ", "the",
            listOf("the", "they", "raw:then", "they", "", "two words", "x".repeat(60), "them", "thee", "then", "this", "that"),
        )!!
        // "then" appears once (raw: stripped), the word itself and invalid entries are dropped.
        assertEquals(listOf("they", "then", "them", "thee", "this"), plan.suggestions)
        assertEquals(SuggestionSpanPolicy.MAX_SUGGESTIONS, plan.suggestions.size)
        // A case variant is a real alternative ("US" for "us").
        assertEquals(listOf("US"), SuggestionSpanPolicy.plan("us ", "us", listOf("us", "US"))!!.suggestions)
    }

    @Test
    fun noPlanWithoutAnAlternativeOrForAnOverlongWord() {
        assertNull(SuggestionSpanPolicy.plan("hello ", "hello", listOf("hello")))
        assertNull(SuggestionSpanPolicy.plan("hello ", "hello", emptyList()))
        val long = "y".repeat(SuggestionSpanPolicy.MAX_WORD_LENGTH + 1)
        assertNull(SuggestionSpanPolicy.plan("$long ", long, listOf("z")))
    }

    @Test
    fun onlyTextFieldsThatAllowSuggestionsGetSpans() {
        val text = InputType.TYPE_CLASS_TEXT
        assertTrue(SuggestionSpanPolicy.fieldAccepts(text))
        assertTrue(SuggestionSpanPolicy.fieldAccepts(text or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE))
        assertTrue(SuggestionSpanPolicy.fieldAccepts(text or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertFalse(SuggestionSpanPolicy.fieldAccepts(text or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
        for (variation in listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_FILTER,
        )) assertFalse("variation $variation", SuggestionSpanPolicy.fieldAccepts(text or variation))
        assertFalse(SuggestionSpanPolicy.fieldAccepts(InputType.TYPE_CLASS_NUMBER))
        assertFalse("TYPE_NULL (blind field)", SuggestionSpanPolicy.fieldAccepts(InputType.TYPE_NULL))
    }
}
