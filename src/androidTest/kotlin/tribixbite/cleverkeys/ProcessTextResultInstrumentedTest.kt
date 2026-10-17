package tribixbite.cleverkeys

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * textAssist / replaceText RESULT path, end to end on a real Android runtime (todo item 30).
 *
 * On the maintainer's phones no installed app ever returned text, so the write-back of commit
 * 9356f280 was unproven. This drives it with nothing simulated on the CleverKeys side:
 *
 * - The editor is [tribixbite.cleverkeys.processtext.ProcessTextHostActivity] and the
 *   text-processing app is [tribixbite.cleverkeys.processtext.ProcessTextUppercaseActivity],
 *   both declared in the androidTest manifest and therefore running in the TEST APK's own
 *   package and process — a foreign editor and a foreign app, as on a phone.
 * - CleverKeys is enabled and set as the IME; the command is pressed through the live
 *   service's KeyEventHandler.key_up — the call Keyboard2View makes for a layout key or a
 *   custom mapping — so the selection check, receipt, ProcessTextReturn bookkeeping,
 *   TextActionLauncher, the transparent ProcessTextActivity trampoline and the SYSTEM chooser
 *   are all the production ones.
 * - The chooser is the platform's. Where it auto-launches a sole target the processor comes up
 *   directly; otherwise UiAutomator taps the processor's label in it. No production seam.
 * - The result travels back through the chooser's FLAG_ACTIVITY_FORWARD_RESULT to the
 *   trampoline, and the service writes it on its own (onStartInput of the returning editor, or
 *   the settle timer). The test only observes: the editor's text/selection as the host
 *   publishes them, the OS clipboard (shell identity), the suggestion-bar message, and the
 *   activity manager's state.
 *
 * Every observation is logged under [TAG] before it is asserted, so a red run still says what
 * the device showed.
 */
@RunWith(AndroidJUnit4::class)
class ProcessTextResultInstrumentedTest {

    /**
     * The suggestion bar that carries the "result copied" message exists on screen only while
     * the input view is shown; the API 36/37 images ship show_ime_with_hard_keyboard=0 with a
     * hardware keyboard configured, so without this the message was never observable there.
     */
    @get:org.junit.Rule
    val showImeWithHardKeyboard = ShowImeWithHardKeyboardRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val testPackage: String = instrumentation.context.packageName
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val component = "${context.packageName}/tribixbite.cleverkeys.CleverKeysService"
    private var originalIme = ""
    private var wasEnabled = false
    private var originalOverlay = ""

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText().trim() }

    @Before
    fun setUp() {
        originalIme = shell("settings get secure default_input_method")
        wasEnabled = component in shell("ime list -s").lines()
        shell("ime enable $component")
        shell("ime set $component")
        // SystemUI's clipboard overlay (API 33+) would cover the chooser's first target after
        // every clip write; switch it off for the test and restore the setting afterwards.
        originalOverlay = shell("device_config get systemui $OVERLAY_FLAG")
        shell("device_config put systemui $OVERLAY_FLAG false")
        ProcessTextReturn.reset()
    }

    @After
    fun tearDown() {
        ProcessTextReturn.reset()
        shell("am force-stop $testPackage")
        if (originalOverlay.isBlank() || originalOverlay == "null") shell("device_config delete systemui $OVERLAY_FLAG")
        else shell("device_config put systemui $OVERLAY_FLAG $originalOverlay")
        if (originalIme.isNotBlank() && originalIme != "null") shell("ime set $originalIme")
        if (!wasEnabled) shell("ime disable $component")
    }

    /** The host's published state: selection and full text. */
    private data class HostState(val selStart: Int, val selEnd: Int, val text: String)

    private fun hostState(): HostState? {
        val raw = device.findObject(By.desc(STATUS_DESC))?.text ?: return null
        val match = Regex("""sel=(-?\d+),(-?\d+)\|(.*)""", RegexOption.DOT_MATCHES_ALL).matchEntire(raw) ?: return null
        return HostState(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3])
    }

    private fun waitFor(what: String, timeoutMs: Long = 15_000L, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(100L)
        }
        dumpWindows("timeout: $what")
        throw AssertionError("Timed out waiting for: $what")
    }

    private fun dumpWindows(stage: String) {
        val out = ByteArrayOutputStream()
        runCatching { device.dumpWindowHierarchy(out) }
        Log.i(TAG, "$stage windows=${out.toString().replace('\n', ' ').take(6_000)}")
    }

    /** Open the foreign editor holding [text] with [selStart]..[selEnd] selected, CleverKeys bound to it. */
    private fun openEditor(text: String, selStart: Int, selEnd: Int) {
        context.startActivity(Intent().apply {
            setClassName(testPackage, HOST_ACTIVITY)
            putExtra("pt.text", text)
            putExtra("pt.selStart", selStart)
            putExtra("pt.selEnd", selEnd)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        waitFor("host editor shows the selection") { hostState() == HostState(selStart, selEnd, text) }
        waitFor("CleverKeys is connected to the foreign editor") {
            var bound = false
            instrumentation.runOnMainSync {
                val svc = CleverKeysService.getInstance()
                bound = svc != null && svc.currentInputEditorInfo?.packageName == testPackage &&
                    svc.currentInputConnection?.getSelectedText(0)?.toString() == text.substring(selStart, selEnd)
            }
            bound
        }
    }

    /** Press textAssist / replaceText exactly as a layout key or custom mapping does. */
    private fun pressCommand(name: String) {
        instrumentation.runOnMainSync {
            val svc = requireNotNull(CleverKeysService.getInstance())
            val handler = CleverKeysService::class.java.getDeclaredField("_keyeventhandler")
                .apply { isAccessible = true }.get(svc) as KeyEventHandler
            handler.key_up(KeyValue.getKeyByName(name), Pointers.Modifiers.EMPTY)
        }
    }

    /**
     * Bring up the processor: directly when the platform chooser auto-launched the sole target,
     * otherwise by tapping its label in the chooser — again every few seconds, since a tap
     * during the sheet's entry animation can miss. Returns the request line it shows.
     */
    private fun reachProcessor(): String {
        val deadline = SystemClock.uptimeMillis() + 25_000L
        var taps = 0
        var nextTap = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            device.findObject(By.textStartsWith(INPUT_PREFIX))?.let { line ->
                Log.i(TAG, "processor reached (chooser taps=$taps): ${line.text}")
                return line.text
            }
            if (SystemClock.uptimeMillis() >= nextTap) {
                dismissClipboardOverlay()
                device.findObject(By.text(PROCESSOR_LABEL))?.let {
                    if (taps == 0) dumpWindows("chooser")
                    it.click()
                    taps++
                    nextTap = SystemClock.uptimeMillis() + 3_000L
                }
            }
            Thread.sleep(200L)
        }
        dumpWindows("processor never appeared")
        throw AssertionError("The process-text processor never appeared (chooser taps=$taps)")
    }

    /**
     * API 33+: any clip write (the test's baseline, or the IME's result in an earlier test)
     * pops SystemUI's clipboard overlay over the bottom-left of the screen, on top of the
     * chooser's first target, and it outlives the test process — a tap meant for the processor
     * opened the overlay's editor instead (emulator.wtf API 33). setUp switches the overlay off
     * through device_config; this dismisses one that still shows (flag not honoured, or shown
     * before the flag was set) before tapping.
     *
     * API 36/37 (emulator.wtf, 2026-10-17): the flag is not honoured AND the overlay exposes no
     * "Dismiss copied text" node — only `clipboard_ui`/`clipboard_preview` — so the button path
     * found nothing and all 8 chooser taps landed on the overlay. There it is swiped off the
     * screen instead (the overlay is swipe-dismissible); a swipe that starts on the overlay never
     * reaches the chooser underneath.
     */
    private fun dismissClipboardOverlay() {
        device.findObject(By.desc(CLIPBOARD_OVERLAY_DISMISS))?.let { dismiss ->
            dismiss.click()
            device.wait(Until.gone(By.desc(CLIPBOARD_OVERLAY_DISMISS)), 3_000L)
            Log.i(TAG, "dismissed the clipboard overlay")
            return
        }
        val overlay = device.findObject(CLIPBOARD_OVERLAY_ROOT) ?: return
        val bounds = overlay.visibleBounds
        device.swipe(bounds.centerX(), bounds.centerY(), device.displayWidth - 1, bounds.centerY(), 5)
        val gone = device.wait(Until.gone(CLIPBOARD_OVERLAY_ROOT), 3_000L) == true
        Log.i(TAG, "swiped the clipboard overlay away (gone=$gone, bounds=$bounds)")
    }

    /** Wait until the system chooser lists the processor (it is NOT tapped). */
    private fun waitForChooser() {
        assertNotNull("the chooser lists the processor",
            device.wait(Until.findObject(By.text(PROCESSOR_LABEL)), 15_000L))
    }

    private fun press(button: String) {
        val target = device.wait(Until.findObject(By.text(button)), 5_000L)
        assertNotNull("processor button $button", target)
        target.click()
    }

    private fun barTexts(): List<String> {
        val texts = mutableListOf<String>()
        instrumentation.runOnMainSync {
            fun walk(view: View) {
                if (view is SuggestionBar) {
                    for (i in 0 until view.childCount) {
                        val chip = view.getChildAt(i)
                        if (chip is TextView && chip.visibility == View.VISIBLE) texts.add(chip.text.toString())
                    }
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
            CleverKeysService.getInstance()?.window?.window?.decorView?.let(::walk)
        }
        return texts
    }

    /** Live and recent-task records of the trampoline, as the activity manager reports them. */
    private fun trampolineRecords(): Pair<Boolean, Boolean> {
        val activities = shell("dumpsys activity activities")
        val recents = shell("dumpsys activity recents")
        val live = TRAMPOLINE in activities
        // The recents dump lists every retained task; a hidden one is still in it, so report
        // both the raw presence and the user-visible listing separately.
        // Without that section header (older dump formats) any mention counts as listed.
        val hasVisibleSection = "Visible recent tasks" in recents
        val visibleSection = if (hasVisibleSection) recents.substringAfter("Visible recent tasks") else recents
        Log.i(TAG, "trampoline live=$live inRecentsDump=${TRAMPOLINE in recents} " +
            "visibleSection=$hasVisibleSection inVisibleRecents=${TRAMPOLINE in visibleSection}")
        return live to (TRAMPOLINE in visibleSection)
    }

    private fun withShellClipboard(block: (ClipboardManager) -> Unit) {
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
        try {
            instrumentation.runOnMainSync {
                block(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            }
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun primaryClipText(): String? {
        var text: String? = null
        withShellClipboard { cm ->
            text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        }
        return text
    }

    private fun assertTrampolineGone() {
        waitFor("the trampoline has finished") { !trampolineRecords().first }
        val (_, visible) = trampolineRecords()
        assertFalse("the trampoline must not be listed in recents", visible)
    }

    @Test
    fun textAssist_resultReplacesTheSelectionInPlace() {
        openEditor(ORIGINAL, SEL_START, SEL_END)
        pressCommand("textAssist")

        val request = reachProcessor()
        assertEquals("the app receives the selection as an editable request",
            "$INPUT_PREFIX${ORIGINAL.substring(SEL_START, SEL_END)}|readonly=false", request)
        press(BUTTON_RETURN)

        val expected = ORIGINAL.substring(0, SEL_START) + RESULT + ORIGINAL.substring(SEL_END)
        val cursor = SEL_START + RESULT.length
        waitFor("result written over the selection") { hostState()?.text == expected }
        // Let any second delivery path (onStartInput post + settle timer) run out before reading.
        Thread.sleep(4_000L)
        val state = hostState()
        Log.i(TAG, "inPlace final=$state")
        assertEquals("selection replaced in place, once, caret after the result",
            HostState(cursor, cursor, expected), state)
        assertFalse("the result was consumed", ProcessTextReturn.hasResult())
        assertTrampolineGone()
    }

    @Test
    fun replaceText_fieldChangedWhileAway_resultGoesToClipboardWithBarMessage() {
        val baseline = "pt-baseline-${SystemClock.uptimeMillis()}"
        withShellClipboard { it.setPrimaryClip(ClipData.newPlainText("baseline", baseline)) }
        assertEquals(baseline, primaryClipText())
        // Either form of the overlay (with a dismiss button on API 33-35, without on 36+).
        device.wait(Until.findObject(CLIPBOARD_OVERLAY_ROOT), 3_000L)
        dismissClipboardOverlay()

        openEditor(ORIGINAL, SEL_START, SEL_END)
        pressCommand("replaceText")

        val request = reachProcessor()
        assertEquals("$INPUT_PREFIX${ORIGINAL.substring(SEL_START, SEL_END)}|readonly=false", request)
        val message = context.getString(R.string.process_text_result_copied)
        press(BUTTON_EDIT_THEN_RETURN)

        // The bar message is transient (1.5 s): watch for it while the delivery runs.
        var sawMessage = false
        waitFor("result on the clipboard") {
            if (message in barTexts()) sawMessage = true
            primaryClipText() == RESULT
        }
        val deadline = SystemClock.uptimeMillis() + 3_000L
        while (!sawMessage && SystemClock.uptimeMillis() < deadline) {
            if (message in barTexts()) sawMessage = true
            Thread.sleep(50L)
        }
        val changed = ORIGINAL + EDIT_SUFFIX
        val state = hostState()
        Log.i(TAG, "changed final=$state clip=${primaryClipText()} sawMessage=$sawMessage")
        assertEquals("field untouched beyond the user's own change", HostState(SEL_START, SEL_END, changed), state)
        assertEquals("result is on the clipboard", RESULT, primaryClipText())
        assertTrue("the bar says why the result went to the clipboard", sawMessage)
        assertFalse("the result was consumed", ProcessTextReturn.hasResult())
        assertTrampolineGone()
    }

    /**
     * A chooser left open (the user pressed Home) must not answer the NEXT request. Fail-first
     * on emulator.wtf API 34: with FLAG_ACTIVITY_NEW_TASK alone the second press only brought
     * the stale trampoline task to the front, showing the old chooser for the old selection;
     * TextActionLauncher.TRAMPOLINE_LAUNCH_FLAGS now clears that task.
     */
    @Test
    fun aChooserLeftOpenDoesNotAnswerTheNextRequest() {
        openEditor(ORIGINAL, SEL_START, SEL_END)
        pressCommand("replaceText")
        waitForChooser()
        device.pressHome()
        Thread.sleep(1_500L)

        val second = "good morning all"
        openEditor(second, 5, 12)
        pressCommand("textAssist")
        val request = reachProcessor()
        assertEquals("the processor gets the NEW selection, not the stale chooser's",
            "${INPUT_PREFIX}morning|readonly=false", request)
        press(BUTTON_RETURN)

        val expected = "good MORNING all"
        waitFor("second result written over the second selection") { hostState()?.text == expected }
        Thread.sleep(4_000L)
        val state = hostState()
        Log.i(TAG, "stale final=$state")
        assertEquals(HostState(12, 12, expected), state)
        assertFalse("the result was consumed", ProcessTextReturn.hasResult())
        assertTrampolineGone()
    }

    private companion object {
        const val TAG = "ProcessTextResultIT"
        const val HOST_ACTIVITY = "tribixbite.cleverkeys.processtext.ProcessTextHostActivity"
        const val TRAMPOLINE = "tribixbite.cleverkeys.ProcessTextActivity"
        const val STATUS_DESC = "pt-status"
        const val CLIPBOARD_OVERLAY_DISMISS = "Dismiss copied text"
        const val SYSTEMUI = "com.android.systemui"

        /** SystemUI's clipboard overlay root (Android 13+ resource id). */
        val CLIPBOARD_OVERLAY_ROOT: androidx.test.uiautomator.BySelector = By.res(SYSTEMUI, "clipboard_ui")
        const val OVERLAY_FLAG = "clipboard_overlay_enabled"
        const val PROCESSOR_LABEL = "CK test uppercase"
        const val INPUT_PREFIX = "PT_INPUT:"
        const val BUTTON_RETURN = "PT_RETURN"
        const val BUTTON_EDIT_THEN_RETURN = "PT_EDIT_THEN_RETURN"
        const val EDIT_SUFFIX = " edited"
        const val ORIGINAL = "say hello world now"
        const val SEL_START = 4
        const val SEL_END = 15
        const val RESULT = "HELLO WORLD"
    }
}
