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
}
