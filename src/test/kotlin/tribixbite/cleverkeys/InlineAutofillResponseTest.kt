package tribixbite.cleverkeys

import android.view.inputmethod.InlineSuggestionsResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Test
import org.objenesis.ObjenesisStd

/**
 * An empty inline-suggestions response is the platform telling the keyboard to remove the
 * autofill chips it shows (AOSP `InlineSuggestionSession` skips an empty response only when the
 * previous one was empty too). The keyboard ignored it, so chips from a field that no longer
 * matches stayed in the bar (found reading the code during the 2026-11-18 device pass).
 */
class InlineAutofillResponseTest {
    @After
    fun teardown() = unmockkAll()

    private fun serviceWithBar(bar: SuggestionBar): CleverKeysService {
        val service = ObjenesisStd().newInstance(CleverKeysService::class.java)
        CleverKeysService::class.java.getDeclaredField("_suggestionBar").apply {
            isAccessible = true
            set(service, bar)
        }
        return service
    }

    @Test
    fun anEmptyResponseClearsTheChipsAlreadyShown() {
        val bar = mockk<SuggestionBar>(relaxed = true)
        val response = mockk<InlineSuggestionsResponse>()
        every { response.inlineSuggestions } returns emptyList()

        serviceWithBar(bar).onInlineSuggestionsResponse(response)

        verify(exactly = 1) { bar.setInlineAutofillView(null) }
    }
}
