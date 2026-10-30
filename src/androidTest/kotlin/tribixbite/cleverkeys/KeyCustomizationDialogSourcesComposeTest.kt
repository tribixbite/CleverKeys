package tribixbite.cleverkeys

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.customization.ActionType
import tribixbite.cleverkeys.customization.KeySubkeySources
import tribixbite.cleverkeys.customization.ShortSwipeKeyCodes
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.customization.SwipeDirection
import tribixbite.cleverkeys.theme.CleverKeysTheme

/**
 * The per-key dialog of Short Swipe Customization (maintainer request 2026-10-30): every direction
 * with its source badge (Layout / Extra key / Custom / Removed), what a custom mapping replaces,
 * the copy question before a built-in layout's own subkey is changed, and a title that names the
 * key instead of printing its raw text (bug A: `Customize "` + newline + `" Key`).
 */
@RunWith(AndroidJUnit4::class)
class KeyCustomizationDialogSourcesComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var context: Context
    private val edits = mutableListOf<Pair<SwipeDirection, SlotEditTarget>>()
    private val removals = mutableListOf<Pair<SwipeDirection, SlotEditTarget>>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    private fun kv(name: String) = KeyValue.getKeyByName(name)

    private fun key(vararg subs: Pair<SwipeDirection, String>): KeyboardData.Key {
        val keys = MutableList<KeyValue?>(9) { null }
        keys[0] = kv("a")
        for ((dir, value) in subs) keys[dir.subLabelIndex] = kv(value)
        return KeyboardData.Key(keys, null, 0, 1f, 0f, null)
    }

    private fun show(copyName: String?) {
        val defined = key(SwipeDirection.NE to "1", SwipeDirection.NW to "@", SwipeDirection.S to "3")
        val live = key(SwipeDirection.NE to "1", SwipeDirection.NW to "@", SwipeDirection.S to "3", SwipeDirection.E to "f1")
        val customs = mapOf(
            SwipeDirection.NW to ShortSwipeMapping("a", SwipeDirection.NW, "", ActionType.COMMAND, "voice_input", true),
            SwipeDirection.S to ShortSwipeMapping.removal("a", SwipeDirection.S),
        )
        val slots = KeySubkeySources.resolve(defined, true, live, customs)
        val title = ShortSwipeKeyCodes.displayName("\n", null, context::getString)
        compose.setContent {
            CleverKeysTheme {
                KeyCustomizationDialog(
                    title = title,
                    key = live,
                    slots = slots,
                    layoutName = "QWERTY (US)",
                    copyName = copyName,
                    onDismiss = {},
                    onEditSlot = { d, t -> edits += d to t },
                    onRemoveSlot = { d, t -> removals += d to t },
                    onRestoreSlot = {},
                )
            }
        }
    }

    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    @Test
    fun titleNamesTheEnterKeyInsteadOfPrintingANewline() {
        show("QWERTY (US) (modified)")
        val title = s(R.string.short_swipe_customize_key, s(R.string.key_descr_enter))
        compose.onNodeWithText(title).assertIsDisplayed()
        assertEquals(false, title.contains('\n'))
    }

    @Test
    fun everySourceIsBadged() {
        show("QWERTY (US) (modified)")
        for (label in listOf(
            R.string.subkey_source_layout, R.string.subkey_source_extra_key,
            R.string.subkey_source_custom, R.string.subkey_source_removed,
        )) {
            compose.onAllNodesWithText(s(label), useUnmergedTree = true)[0].performScrollTo().assertIsDisplayed()
        }
        // The voice-input mapping says it replaces the layout's "@", and is named, not drawn as a glyph.
        compose.onNodeWithText(s(R.string.subkey_slot_replaces, "@"), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(s(R.string.cmd_voice_input), useUnmergedTree = true)[0].performScrollTo().assertIsDisplayed()
    }

    @Test
    fun changingABuiltInLayoutsSubkeyAsksToCreateACopy() {
        show("QWERTY (US) (modified)")
        compose.onAllNodesWithText("1", useUnmergedTree = true)[0].performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.subkey_layout_copy_title)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.subkey_layout_copy_body, "QWERTY (US)", "QWERTY (US) (modified)")).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.subkey_layout_copy_confirm)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(SwipeDirection.NE to SlotEditTarget.LAYOUT), edits)

        // The other answer keeps today's behaviour: a custom short swipe in every layout.
        compose.onAllNodesWithText("1", useUnmergedTree = true)[0].performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.subkey_layout_copy_global)).performClick()
        compose.waitForIdle()
        assertEquals(SwipeDirection.NE to SlotEditTarget.GLOBAL, edits.last())
    }

    @Test
    fun aCustomLayoutIsEditedInPlaceWithoutTheQuestion() {
        show(copyName = null)
        compose.onAllNodesWithText("1", useUnmergedTree = true)[0].performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText(s(R.string.subkey_layout_copy_title)).fetchSemanticsNodes().size)
        assertEquals(listOf(SwipeDirection.NE to SlotEditTarget.LAYOUT), edits)
    }

    @Test
    fun aCustomMappingIsEditedAsACustomMapping() {
        show("QWERTY (US) (modified)")
        compose.onAllNodesWithText(s(R.string.cmd_voice_input), useUnmergedTree = true)[0].performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(SwipeDirection.NW to SlotEditTarget.GLOBAL), edits)
    }
}
