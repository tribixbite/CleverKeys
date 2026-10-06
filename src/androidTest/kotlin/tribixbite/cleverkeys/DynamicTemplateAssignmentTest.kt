package tribixbite.cleverkeys

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.customization.*

/** Both per-key and popover assignment share this actual editor, including the label step. */
@RunWith(AndroidJUnit4::class)
class DynamicTemplateAssignmentTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)
    private var result: MappingSelection? = null
    private fun show(mapping: ShortSwipeMapping? = null) {
        rule.setContent { MaterialTheme { CommandPaletteDialog(onDismiss = {}, onCommandSelected = {}, onTextSelected = {}, onMappingSelected = { result = it }, initialMapping = mapping) } }
    }
    @Test fun newTemplateKeepsExplicitTypeThroughLabelConfirmation() {
        show()
        rule.onNodeWithText(text(R.string.command_palette_template_title)).performClick()
        rule.onNode(hasSetTextAction()).performTextInput("[{selection}]({cursor})")
        rule.onNodeWithText(text(R.string.command_palette_use_text)).performClick()
        rule.onNodeWithText(text(R.string.command_palette_confirm)).performClick()
        rule.runOnIdle { assertEquals(ActionType.TEMPLATE,result?.actionType);assertEquals("[{selection}]({cursor})",result?.actionValue) }
    }
    @Test fun editingSavedTemplatePreservesTypeAndPayload() {
        show(ShortSwipeMapping("a",SwipeDirection.N,"wrap",ActionType.TEMPLATE,"[{selection}]"))
        rule.onNode(hasSetTextAction()).assertTextContains("[{selection}]")
        rule.onNodeWithText(text(R.string.command_palette_use_text)).performClick()
        rule.onNodeWithText(text(R.string.command_palette_confirm)).performClick()
        rule.runOnIdle { assertEquals(ActionType.TEMPLATE,result?.actionType);assertEquals("wrap",result?.displayLabel) }
    }
    @Test fun existingTextActionWithTokenSyntaxStaysLiteral() {
        show(ShortSwipeMapping("a",SwipeDirection.N,"uuid",ActionType.TEXT,"{uuid}"))
        rule.onNodeWithText(text(R.string.command_palette_use_text)).performClick()
        rule.onNodeWithText(text(R.string.command_palette_confirm)).performClick()
        rule.runOnIdle { assertEquals(ActionType.TEXT,result?.actionType);assertEquals("{uuid}",result?.actionValue) }
    }
    @Test fun invalidTemplateCannotAdvanceToLabelConfirmation() {
        show(ShortSwipeMapping("a",SwipeDirection.N,"{}",ActionType.TEMPLATE,"{cursor}"))
        rule.onNode(hasSetTextAction()).performTextReplacement("{cursor}{cursor}")
        rule.onNodeWithText(text(R.string.dynamic_template_invalid)).assertExists()
        rule.onNodeWithText(text(R.string.command_palette_use_text)).assertIsNotEnabled()
        rule.runOnIdle { assertNull(result) }
    }
}
