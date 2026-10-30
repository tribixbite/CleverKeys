package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.customization.LayoutSubkeyEditor
import tribixbite.cleverkeys.customization.SwipeDirection
import tribixbite.cleverkeys.prefs.LayoutsPreference
import tribixbite.cleverkeys.prefs.ListGroupPreference

/**
 * Modified copies on a real device runtime (maintainer request 2026-10-30): the real resources,
 * raw layout XML, parser and preferences. A built-in layout's own subkey edit creates
 * "<name> (modified)" in the entry's place, a second edit changes that copy, and Layout Manager's
 * Revert puts the built-in layout back with the entry's bindings.
 *
 * The `layouts` preference is snapshotted and restored with a synchronous commit.
 */
@RunWith(AndroidJUnit4::class)
class LayoutSubkeyCopyInstrumentedTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private var savedLayouts: String? = null
    private var hadLayouts = false

    private val qwerty = LayoutsPreference.NamedLayout("latn_qwerty_us", language = "en")

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
        prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        hadLayouts = prefs.contains(LayoutsPreference.KEY)
        savedLayouts = prefs.getString(LayoutsPreference.KEY, null)
        val editor = prefs.edit()
        ListGroupPreference.saveToPreferences(LayoutsPreference.KEY, editor, listOf(qwerty), LayoutsPreference.SERIALIZER)
        check(editor.commit())
    }

    @After
    fun tearDown() {
        val editor = prefs.edit()
        if (hadLayouts) editor.putString(LayoutsPreference.KEY, savedLayouts) else editor.remove(LayoutsPreference.KEY)
        check(editor.commit())
    }

    private fun entries() = LayoutSubkeyEditor(prefs, context.resources).entries()

    private val nw = SwipeDirection.NW.subLabelIndex

    @Test
    fun copyIsCreatedThenEditedThenRevertedInLayoutManager() {
        val editor = LayoutSubkeyEditor(prefs, context.resources)
        val builtIn = editor.definition(qwerty)!!
        val qKeyBefore = builtIn.rows[0].keys[0]

        // 1. First edit of the built-in layout's own subkey: a modified copy in its place.
        val first = editor.apply(0, qwerty, 0, 0, SwipeDirection.NW, "voice_input")
        assertTrue("$first", first is LayoutSubkeyEditor.Outcome.Saved && first.copyCreated)
        val copy = entries().single() as LayoutsPreference.CustomLayout
        val copyName = context.getString(R.string.subkey_layout_copy_name, editor.builtInDisplayName("latn_qwerty_us"))
        assertEquals(copyName, copy.parsed!!.name)
        assertEquals("latn_qwerty_us", copy.origin)
        assertEquals("en", copy.language)
        assertEquals(KeyValue.getKeyByName("voice_input"), copy.parsed!!.rows[0].keys[0].keys[nw])
        // The built-in layout is unchanged.
        assertEquals(qKeyBefore, LayoutsPreference.layoutOfString(context.resources, "latn_qwerty_us")!!.rows[0].keys[0])

        // 2. Second edit: the copy itself changes, no second copy.
        val second = editor.apply(0, copy, 0, 0, SwipeDirection.NW, null)
        assertTrue("$second", second is LayoutSubkeyEditor.Outcome.Saved && !second.copyCreated)
        val edited = entries().single() as LayoutsPreference.CustomLayout
        assertNull(edited.parsed!!.rows[0].keys[0].keys.getOrNull(nw))
        assertEquals("latn_qwerty_us", edited.origin)

        // 3. Layout Manager offers Revert on the copy; confirming restores the built-in entry.
        ActivityScenario.launch(LayoutManagerActivity::class.java).use {
            val badge = context.getString(R.string.layout_manager_badge_modified_copy, editor.builtInDisplayName("latn_qwerty_us"))
            compose.onNodeWithText(badge).performScrollTo().assertIsDisplayed()
            compose.onNode(hasText(context.getString(R.string.layout_manager_revert))).performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.layout_manager_revert_title)).assertIsDisplayed()
            compose.onAllNodes(hasText(context.getString(R.string.layout_manager_revert)))
                .fetchSemanticsNodes().let { assertTrue(it.size >= 2) }
            // The dialog's confirm button is the last "Revert to original" composed.
            val nodes = compose.onAllNodes(hasText(context.getString(R.string.layout_manager_revert)))
            nodes[nodes.fetchSemanticsNodes().size - 1].performClick()
            compose.waitForIdle()
        }
        assertEquals(listOf<LayoutsPreference.Layout>(qwerty), entries())
    }
}
