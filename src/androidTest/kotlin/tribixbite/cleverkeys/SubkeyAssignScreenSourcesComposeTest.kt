package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.a11y.KeyLabels
import tribixbite.cleverkeys.customization.ActionType
import tribixbite.cleverkeys.customization.LayoutSubkeyEditor
import tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.customization.SubkeyKeyContext
import tribixbite.cleverkeys.customization.SwipeDirection
import tribixbite.cleverkeys.popover.SubkeyAssignRequest
import tribixbite.cleverkeys.popover.SubkeyAssignScreen
import tribixbite.cleverkeys.prefs.LayoutsPreference
import tribixbite.cleverkeys.prefs.ListGroupPreference
import tribixbite.cleverkeys.theme.CleverKeysTheme

/**
 * The subkey popover's assign/edit screen follows the per-key dialog's rule (maintainer,
 * 2026-10-30, "yes fix both"; docs/specs/subkey-customization-current.md "One policy for both
 * screens"): it shows the slot's source badge and what a custom mapping replaces, and removing or
 * changing a subkey the current BUILT-IN layout defines asks first — **Create copy** writes
 * "<layout> (modified)" in the layout's place, **Change in all layouts** keeps the global custom
 * mapping. Before, the screen always wrote a global custom mapping (or `removed` blank).
 *
 * Real resources, raw layout XML, preferences and mapping storage; the `layouts` preference and
 * q's NW mapping are snapshotted and restored.
 */
@RunWith(AndroidJUnit4::class)
class SubkeyAssignScreenSourcesComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var manager: ShortSwipeCustomizationManager
    private var savedLayouts: String? = null
    private var hadLayouts = false
    private var savedMapping: ShortSwipeMapping? = null
    private val done = AtomicBoolean(false)

    private val qwerty = LayoutsPreference.NamedLayout("latn_qwerty_us", language = "en")
    private val nw = SwipeDirection.NW

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
        manager = ShortSwipeCustomizationManager.getInstance(context)
        runBlocking {
            manager.loadMappings()
            savedMapping = manager.getMapping("q", nw)
            manager.removeMapping("q", nw)
        }
    }

    @After
    fun tearDown() {
        val editor = prefs.edit()
        if (hadLayouts) editor.putString(LayoutsPreference.KEY, savedLayouts) else editor.remove(LayoutsPreference.KEY)
        check(editor.commit())
        runBlocking {
            val saved = savedMapping
            if (saved != null) manager.setMapping(saved) else manager.removeMapping("q", nw)
        }
    }

    private fun entries() = LayoutSubkeyEditor(prefs, context.resources).entries()

    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    /** q on the built-in QWERTY (US) entry, as the running keyboard would resolve it. */
    private fun qContext(editor: LayoutSubkeyEditor): SubkeyKeyContext {
        val q = editor.definition(qwerty)!!.rows[0].keys[0]
        return SubkeyKeyContext(
            liveKey = q, rowHeight = 1f, definedKey = q, layoutOwned = true, entryIndex = 0, entry = qwerty,
            row = 0, col = 0, layoutName = editor.displayName(qwerty), copyName = editor.copyName(qwerty),
        )
    }

    private fun show(request: SubkeyAssignRequest) {
        compose.setContent {
            CleverKeysTheme {
                SubkeyAssignScreen(request, onDone = { done.set(true) }, resolveKey = ::qContext)
            }
        }
        compose.waitForIdle()
    }

    private val editLayoutTilde = SubkeyAssignRequest(
        keyCode = "q", direction = nw, mode = SubkeyAssignRequest.Mode.EDIT,
        hasDefault = true, isCustom = false, currentLabel = "~",
    )

    private fun removeAndAnswer(answer: Int) {
        show(editLayoutTilde)
        compose.onNodeWithText(s(R.string.subkey_source_layout)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.subkey_edit_remove)).performClick()
        compose.onNodeWithText(s(R.string.subkey_layout_copy_title)).assertIsDisplayed()
        compose.onNodeWithText(s(answer)).performClick()
        compose.waitUntil(10_000) { done.get() }
    }

    @Test
    fun removingABuiltInLayoutsSubkeyAsksThenWritesTheModifiedCopy() {
        removeAndAnswer(R.string.subkey_layout_copy_confirm)

        val copy = entries().single() as LayoutsPreference.CustomLayout
        val editor = LayoutSubkeyEditor(prefs, context.resources)
        assertEquals(s(R.string.subkey_layout_copy_name, editor.builtInDisplayName("latn_qwerty_us")), copy.parsed!!.name)
        assertEquals("latn_qwerty_us", copy.origin)
        assertNull(copy.parsed!!.rows[0].keys[0].keys.getOrNull(nw.subLabelIndex))
        // The layout changed, not a global mapping.
        assertNull(manager.getMapping("q", nw))
    }

    @Test
    fun changeInAllLayoutsKeepsTheGlobalCustomMapping() {
        removeAndAnswer(R.string.subkey_layout_copy_global)

        assertEquals(listOf<LayoutsPreference.Layout>(qwerty), entries())
        assertTrue("${manager.getMapping("q", nw)}", manager.getMapping("q", nw)?.isRemoval == true)
    }

    @Test
    fun aCustomMappingShowsItsBadgeAndTheSubkeyItReplaces() {
        runBlocking { manager.setMapping(ShortSwipeMapping("q", nw, "v", ActionType.COMMAND, "voice_input")) }
        show(editLayoutTilde.copy(isCustom = true, currentLabel = "v"))

        compose.onNodeWithText(s(R.string.subkey_source_custom)).assertIsDisplayed()
        val tilde = KeyLabels.describe(KeyValue.getKeyByName("~"), context::getString)
        compose.onNodeWithText(s(R.string.subkey_slot_replaces, tilde)).assertIsDisplayed()
    }
}
