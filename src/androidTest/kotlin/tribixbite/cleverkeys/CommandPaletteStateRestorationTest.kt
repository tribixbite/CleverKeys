package tribixbite.cleverkeys

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.customization.ActionType
import tribixbite.cleverkeys.customization.CommandPaletteDialog
import tribixbite.cleverkeys.customization.MappingSelection

/**
 * Rotating mid-edit keeps the palette's state (popover/palette audit, 2026-10-08): the open
 * step, the typed text and the pending label are rememberSaveable. Recreation is emulated with
 * [StateRestorationTester], which drops every `remember` and keeps only saved state.
 */
@RunWith(AndroidJUnit4::class)
class CommandPaletteStateRestorationTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)
    private var result: MappingSelection? = null

    private fun show(): StateRestorationTester = StateRestorationTester(rule).apply {
        setContent { MaterialTheme { CommandPaletteDialog(onDismiss = {}, onMappingSelected = { result = it }) } }
    }

    @Test
    fun typedCustomTextAndTheOpenStepSurviveRecreation() {
        val restoration = show()
        rule.onNodeWithText(text(R.string.command_palette_custom_text_title)).performClick()
        rule.onNode(hasSetTextAction()).performTextInput("see you soon")

        restoration.emulateSavedInstanceStateRestore()

        rule.onNodeWithText(text(R.string.command_palette_title_custom_text)).assertExists()
        rule.onNodeWithText("see you soon").assertExists()
        rule.onNodeWithText(text(R.string.command_palette_use_text)).performClick()
        rule.onNodeWithText(text(R.string.command_palette_confirm)).performClick()
        rule.runOnIdle {
            assertEquals(ActionType.TEXT, result?.actionType)
            assertEquals("see you soon", result?.actionValue)
        }
    }

    @Test
    fun thePendingLabelStepSurvivesRecreation() {
        val restoration = show()
        rule.onNodeWithText(text(R.string.command_palette_custom_text_title)).performClick()
        rule.onNode(hasSetTextAction()).performTextInput("signature")
        rule.onNodeWithText(text(R.string.command_palette_use_text)).performClick()
        // The label step is open, prefilled with the first four characters; the user edits it.
        rule.onNodeWithText("sign").performTextReplacement("SIG")

        restoration.emulateSavedInstanceStateRestore()

        rule.onNodeWithText("SIG").assertExists()
        rule.onNodeWithText(text(R.string.command_palette_confirm)).performClick()
        rule.runOnIdle {
            assertEquals("signature", result?.actionValue)
            assertEquals("SIG", result?.displayLabel)
        }
    }
}
