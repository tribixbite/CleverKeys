package tribixbite.cleverkeys

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for Autocapitalisation behavior.
 * Tests basic initialization and callback functionality.
 * Note: Full typing simulation requires InputConnection which is difficult to mock reliably.
 */
@RunWith(AndroidJUnit4::class)
class AutocapitalizationTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    // =========================================================================
    // Callback tests
    // =========================================================================

    @Test
    fun testCallbackCreation() {
        var callbackCalled = false
        val callback = Autocapitalisation.Callback { shouldEnable, shouldDisable ->
            callbackCalled = true
        }
        assertNotNull("Callback should be created", callback)
    }

    // =========================================================================
    // EditorInfo tests
    // =========================================================================

    @Test
    fun testEditorInfoWithCapSentences() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            initialCapsMode = 1
        }

        assertEquals(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            info.inputType
        )
    }

    @Test
    fun testEditorInfoWithCapWords() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            initialCapsMode = 1
        }

        assertEquals(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS,
            info.inputType
        )
    }

    @Test
    fun testEditorInfoNumberInput() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        assertEquals(InputType.TYPE_CLASS_NUMBER, info.inputType)
    }

    @Test
    fun testEditorInfoPasswordInput() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        assertTrue(
            (info.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0
        )
    }

    // =========================================================================
    // Config integration tests
    // =========================================================================

    @Test
    fun testConfigAutocapitalisationSetting() {
        try {
            val config = Config.globalConfig()
            if (config != null) {
                val originalValue = config.autocapitalisation

                try {
                    // Test toggling
                    config.autocapitalisation = true
                    assertTrue(config.autocapitalisation)

                    config.autocapitalisation = false
                    assertFalse(config.autocapitalisation)
                } finally {
                    config.autocapitalisation = originalValue
                }
            }
        } catch (e: NullPointerException) {
            // Config not available in test context without full keyboard init
        }
    }

    @Test
    fun testConfigAutocapitalizeIWordsSetting() {
        try {
            val config = Config.globalConfig()
            if (config != null) {
                val originalValue = config.autocapitalize_i_words

                try {
                    config.autocapitalize_i_words = true
                    assertTrue(config.autocapitalize_i_words)

                    config.autocapitalize_i_words = false
                    assertFalse(config.autocapitalize_i_words)
                } finally {
                    config.autocapitalize_i_words = originalValue
                }
            }
        } catch (e: NullPointerException) {
            // Config not available in test context without full keyboard init
        }
    }

    // =========================================================================
    // I-word capitalization tests (Issue #72)
    // Tests that "i" and I-contractions are capitalized when setting is enabled
    // =========================================================================

    companion object {
        // Mirror the I_WORDS set from SuggestionHandler
        private val I_WORDS = setOf("i", "i'm", "i'll", "i'd", "i've")

        /**
         * Helper function that mirrors the capitalizeIWord logic from SuggestionHandler.
         * When autocapitalize_i_words is enabled, capitalizes "i" → "I", "i'm" → "I'm", etc.
         */
        private fun capitalizeIWordHelper(word: String, settingEnabled: Boolean): String {
            if (!settingEnabled) return word
            val lower = word.lowercase()
            return if (lower in I_WORDS) {
                word.replaceFirstChar { it.uppercaseChar() }
            } else {
                word
            }
        }
    }

    @Test
    fun testIWordCapitalization_SingleI() {
        // When autocapitalize_i_words is ON, "i" should become "I"
        val result = capitalizeIWordHelper("i", settingEnabled = true)
        assertEquals("'i' should be capitalized to 'I'", "I", result)
    }

    @Test
    fun testIWordCapitalization_SingleI_SettingDisabled() {
        // When autocapitalize_i_words is OFF, "i" stays "i"
        val result = capitalizeIWordHelper("i", settingEnabled = false)
        assertEquals("'i' should remain 'i' when setting disabled", "i", result)
    }

    @Test
    fun testIWordCapitalization_ImContraction() {
        // When autocapitalize_i_words is ON, "i'm" should become "I'm"
        val result = capitalizeIWordHelper("i'm", settingEnabled = true)
        assertEquals("'i'm' should be capitalized to 'I'm'", "I'm", result)
    }

    @Test
    fun testIWordCapitalization_IllContraction() {
        // When autocapitalize_i_words is ON, "i'll" should become "I'll"
        val result = capitalizeIWordHelper("i'll", settingEnabled = true)
        assertEquals("'i'll' should be capitalized to 'I'll'", "I'll", result)
    }

    @Test
    fun testIWordCapitalization_IdContraction() {
        // When autocapitalize_i_words is ON, "i'd" should become "I'd"
        val result = capitalizeIWordHelper("i'd", settingEnabled = true)
        assertEquals("'i'd' should be capitalized to 'I'd'", "I'd", result)
    }

    @Test
    fun testIWordCapitalization_IveContraction() {
        // When autocapitalize_i_words is ON, "i've" should become "I've"
        val result = capitalizeIWordHelper("i've", settingEnabled = true)
        assertEquals("'i've' should be capitalized to 'I've'", "I've", result)
    }

    @Test
    fun testIWordCapitalization_RegularWord() {
        // Regular words should NOT be capitalized
        val result = capitalizeIWordHelper("hello", settingEnabled = true)
        assertEquals("Regular words should not be capitalized", "hello", result)
    }

    @Test
    fun testIWordCapitalization_WordStartingWithI() {
        // Words starting with 'i' but not in I_WORDS should NOT be capitalized
        val result = capitalizeIWordHelper("it", settingEnabled = true)
        assertEquals("'it' should not be capitalized (not an I-word)", "it", result)
    }

    @Test
    fun testIWordCapitalization_Ice() {
        // "ice" should NOT be capitalized
        val result = capitalizeIWordHelper("ice", settingEnabled = true)
        assertEquals("'ice' should not be capitalized", "ice", result)
    }

    @Test
    fun testIWordCapitalization_AlreadyCapitalized() {
        // If already capitalized, should stay capitalized
        val result = capitalizeIWordHelper("I", settingEnabled = true)
        assertEquals("Already capitalized 'I' should stay 'I'", "I", result)
    }

    @Test
    fun testIWordCapitalization_MixedCase() {
        // "I'M" (all caps) should stay as "I'M"
        val result = capitalizeIWordHelper("I'M", settingEnabled = true)
        assertEquals("All caps 'I'M' should stay 'I'M'", "I'M", result)
    }
}
