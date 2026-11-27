package tribixbite.cleverkeys

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.a11y.ShortSwipeAccessibilityActions
import tribixbite.cleverkeys.clipboard.ClipboardBulkPlans
import tribixbite.cleverkeys.customization.SwipeDirection

/**
 * The paste short swipe (south-east on `v`) pastes the clipping as edited in CleverKeys' pane.
 * Maintainer 2026-11-23: "modify the top entry, close clipboard pane, and try pasting with the
 * subkey short swipe paste function" gave the unedited system clip. An edit only changed the
 * history row; the paste key asks the app to paste Android's clipboard, which still held the old
 * text. Editing the History row that IS the system clip now puts the edited text there too.
 *
 * Real IME, real field, real clipboard: a copy made in the app reaches CleverKeys' history, the
 * row is edited through the pane's own call ([ClipboardHistoryService.editEntryContent]), and the
 * paste runs through the `v` key's accessibility short-swipe action, which is the swipe's own
 * dispatch ([Keyboard2View] performAccessibilityShortSwipe). Removes its rows afterwards.
 */
@RunWith(AndroidJUnit4::class)
class EditedClipPasteInstrumentedTest {

    @get:Rule
    val showImeWithHardKeyboard = ShowImeWithHardKeyboardRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var originalIme = ""

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText().trim() }

    private fun within(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var ok = false
            instrumentation.runOnMainSync { ok = condition() }
            if (ok) return true
            Thread.sleep(50L)
        }
        return false
    }

    private fun keyboardIn(view: View): Keyboard2View? {
        if (view is Keyboard2View) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) keyboardIn(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun keyboard(): Keyboard2View? =
        CleverKeysService.getInstance()?.window?.window?.decorView?.let(::keyboardIn)

    /**
     * The south-east short swipe on `v` (paste), through the key's accessibility short-swipe
     * action, which is the swipe's own dispatch; returns what reached the field.
     */
    private fun pasteWithTheSubkey(activity: ImeFieldKeepWindowTestActivity): String {
        var problem: String? = null
        instrumentation.runOnMainSync {
            val kb = keyboard()
            val provider = kb?.accessibilityNodeProvider
            val id = provider?.let { p ->
                kb.accessibilityVirtualViewIdsForTest().firstOrNull {
                    p.createAccessibilityNodeInfo(it)?.contentDescription?.toString().equals("v", ignoreCase = true)
                }
            }
            problem = when {
                provider == null -> "no keyboard view or accessibility provider"
                id == null -> "no v key on the keyboard"
                !provider.performAction(id, ShortSwipeAccessibilityActions.actionId(SwipeDirection.SE), null) ->
                    "v has no south-east short swipe"
                else -> null
            }
        }
        problem?.let { throw AssertionError(it) }
        var fieldText = ""
        val pasted = within(3_000L) {
            fieldText = activity.field.text.toString()
            fieldText.isNotEmpty()
        }
        assertTrue("nothing was pasted", pasted)
        return fieldText
    }

    @Before
    fun setUp() {
        originalIme = shell("settings get secure default_input_method")
        val component = "${context.packageName}/tribixbite.cleverkeys.CleverKeysService"
        shell("ime enable $component")
        shell("ime set $component")
    }

    @After
    fun tearDown() {
        if (originalIme.isNotBlank() && originalIme != "null") shell("ime set $originalIme")
    }

    @Test
    fun theSubkeyPasteGivesTheEditedClipping() {
        val nonce = SystemClock.uptimeMillis()
        val original = "ck paste original $nonce"
        val edited = "ck paste edited $nonce"
        val history = requireNotNull(ClipboardHistoryService.get_service(context)) { "no clipboard service" }
        try {
            ActivityScenario.launch(ImeFieldKeepWindowTestActivity::class.java).use { scenario ->
                lateinit var activity: ImeFieldKeepWindowTestActivity
                scenario.onActivity { activity = it }
                assertTrue("the keyboard", within(15_000L) {
                    CleverKeysService.getInstance()?.isInputViewShown == true && (keyboard()?.height ?: 0) > 0
                })
                // A copy made in the app: Android's clipboard and CleverKeys' history get it.
                instrumentation.runOnMainSync {
                    activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("test", original))
                }
                assertTrue("the copy reached CleverKeys' history", within(5_000L) { history.historyEntryTimestamp(original) != null })

                // The user edits that row in the pane (ClipboardHistoryView.save_edit's call).
                var result: EditEntryResult? = null
                instrumentation.runOnMainSync { result = history.editEntryContent(original, edited, ClipboardTab.HISTORY) }
                assertEquals(EditEntryResult.Success, result)
                Thread.sleep(500L)

                assertEquals("the paste short swipe must give the clipping as edited", edited, pasteWithTheSubkey(activity))
                assertTrue(
                    "the edit must not leave a second row for the same clipping",
                    history.historyEntryTimestamp(original) == null,
                )
            }
        } finally {
            instrumentation.runOnMainSync {
                history.removeHistoryEntry(original)
                history.removeHistoryEntry(edited)
            }
        }
    }

    /**
     * Maintainer 2026-11-26: they copied `https://github.com/rustdesk/rustdesk-server`, edited it
     * into `…/tribixbite/rustdesk-server` in the pane, and "short swipe paste function didn't paste
     * it". A cut inside the editor had put the fragment on Android's clipboard, so at Save the clip
     * was no longer the row and the edit did not follow. The editor passes its own cut
     * (`ClipboardHistoryView.exposeEditSelection` → `editorClip`); the paste then gives the edit.
     */
    @Test
    fun theSubkeyPasteGivesTheEditAfterTheEditorCutPartOfIt() {
        val nonce = SystemClock.uptimeMillis()
        val original = "https://github.com/rustdesk/rustdesk-server?ck=$nonce"
        val fragment = "rustdesk"
        val edited = "https://github.com/tribixbite/rustdesk-server?ck=$nonce"
        val history = requireNotNull(ClipboardHistoryService.get_service(context)) { "no clipboard service" }
        try {
            ActivityScenario.launch(ImeFieldKeepWindowTestActivity::class.java).use { scenario ->
                lateinit var activity: ImeFieldKeepWindowTestActivity
                scenario.onActivity { activity = it }
                assertTrue("the keyboard", within(15_000L) {
                    CleverKeysService.getInstance()?.isInputViewShown == true && (keyboard()?.height ?: 0) > 0
                })
                val clipboard = activity.getSystemService(ClipboardManager::class.java)
                instrumentation.runOnMainSync { clipboard.setPrimaryClip(ClipData.newPlainText("test", original)) }
                assertTrue("the copy reached CleverKeys' history", within(5_000L) { history.historyEntryTimestamp(original) != null })

                // The editor's cut of the org segment, as exposeEditSelection writes it, while the
                // clip it replaces is the row being edited.
                var working = false
                instrumentation.runOnMainSync {
                    working = history.systemClipIsWorkingClip(ClipboardTab.HISTORY, original, null)
                    clipboard.setPrimaryClip(ClipData.newPlainText("CleverKeys", fragment))
                }
                assertTrue("the copied row is the clip the edit works with", working)

                var result: EditEntryResult? = null
                instrumentation.runOnMainSync {
                    result = history.editEntryContent(original, edited, ClipboardTab.HISTORY, editorClip = fragment)
                }
                assertEquals(EditEntryResult.Success, result)
                Thread.sleep(500L)

                assertEquals("the paste short swipe must give the clipping as edited", edited, pasteWithTheSubkey(activity))
            }
        } finally {
            instrumentation.runOnMainSync {
                history.removeHistoryEntry(original)
                history.removeHistoryEntry(edited)
                history.removeHistoryEntry(fragment)
            }
        }
    }

    /**
     * Maintainer 2026-11-24: "i just cleaned a clipping and then tried to paste via subkey function
     * and nothing happened". Android 13+ clears its clipboard an hour after the last copy
     * (device_config `clipboard/auto_clear_enabled`, true on the S25U and the Pixel); Clean then
     * found no system clip to replace, and the paste key asked the app to paste an empty clipboard.
     * One clipping cleaned now fills an EMPTY clipboard. The clear here is Android's own call
     * (`clearPrimaryClip`, API 28+), the one its hourly auto-clear makes.
     */
    @Test
    fun aCleanedClippingFillsAnEmptyClipboard() {
        assumeTrue("clearPrimaryClip is API 28+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        val nonce = SystemClock.uptimeMillis()
        val original = "ck clean   me $nonce"
        val cleaned = "ck clean me $nonce"
        val history = requireNotNull(ClipboardHistoryService.get_service(context)) { "no clipboard service" }
        try {
            ActivityScenario.launch(ImeFieldKeepWindowTestActivity::class.java).use { scenario ->
                lateinit var activity: ImeFieldKeepWindowTestActivity
                scenario.onActivity { activity = it }
                assertTrue("the keyboard", within(15_000L) {
                    CleverKeysService.getInstance()?.isInputViewShown == true && (keyboard()?.height ?: 0) > 0
                })
                val clipboard = activity.getSystemService(ClipboardManager::class.java)
                instrumentation.runOnMainSync { clipboard.setPrimaryClip(ClipData.newPlainText("test", original)) }
                assertTrue("the copy reached CleverKeys' history", within(5_000L) { history.historyEntryTimestamp(original) != null })

                // An hour later Android has cleared its clipboard; the history row stays.
                instrumentation.runOnMainSync { clipboard.clearPrimaryClip() }
                assertTrue("Android's clipboard is empty", within(2_000L) { !clipboard.hasPrimaryClip() })

                // The pane's Clean on that one clipping (ClipboardManager.confirmClean's plan, run
                // off the main thread as ClipboardHistoryView.runBulk runs it).
                val plan = ClipboardBulkPlans.planClean(listOf(ClipboardEntry(original, history.historyEntryTimestamp(original) ?: 0L)))
                val result = history.applyClean(ClipboardTab.HISTORY, plan).getOrThrow()
                assertEquals(1, result.cleaned)
                assertTrue("the result reports the cleaned text on the clipboard", result.onSystemClipboard)
                Thread.sleep(500L)

                assertEquals("the paste short swipe must give the cleaned clipping", cleaned, pasteWithTheSubkey(activity))
            }
        } finally {
            instrumentation.runOnMainSync {
                history.removeHistoryEntry(original)
                history.removeHistoryEntry(cleaned)
            }
        }
    }
}
