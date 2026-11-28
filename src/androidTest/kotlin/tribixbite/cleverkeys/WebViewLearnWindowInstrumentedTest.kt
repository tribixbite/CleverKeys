package tribixbite.cleverkeys

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * The learn window in a real WebView with two textareas, through the real keyboard service
 * (test-gap audit 2026-11-28, item 11). WebView textareas all report the same package, field id
 * and input type, so the keyboard must tell them apart by their text (the mock harness models
 * this with `chromeField()`; Note10+ r21/r22 found cross-field pairs on devices).
 *
 *  1. `red fix ` in textarea a, a tap on textarea b, `toy box `: no `fix → toy`, while
 *     `red → fix` and `toy → box` are learned (positive controls, final Fable audit M4).
 *  2. `blue car ` in textarea a, the page clears it from JavaScript, `now here `: no `car → now`
 *     and no `blue → now` (the cleared words' learn window is closed), while `blue → car` and
 *     `now → here` are learned.
 *
 * Compile-checked on the dev box; run on a test phone or emulator.wtf. Restores the keyboard and
 * learning preferences ([RealServiceLearning]).
 */
@RunWith(AndroidJUnit4::class)
class WebViewLearnWindowInstrumentedTest {

    @get:Rule
    val showImeWithHardKeyboard = ShowImeWithHardKeyboardRule()

    private val live = RealServiceLearning()

    @Before
    fun setUp() = live.setUp()

    @After
    fun tearDown() = live.tearDown()

    /** A real tap (down, up) at a screen point, as a finger on the page. */
    private fun tap(point: Pair<Float, Float>) {
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.first, point.second, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue("tap $action not injected", live.instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
            Thread.sleep(60L)
        }
    }

    @Volatile
    private var focusedId: String? = null

    /** Refreshes [focusedId] from the page (off the main thread). */
    private fun WebViewTextareaTestActivity.refreshFocusedId() {
        focusedId = evaluate("document.activeElement && document.activeElement.id")?.trim('"')
    }

    private fun ActivityScenario<WebViewTextareaTestActivity>.page(): WebViewTextareaTestActivity {
        var activity: WebViewTextareaTestActivity? = null
        onActivity { activity = it }
        return activity ?: throw AssertionError("no activity")
    }

    /** The keyboard's current EditorInfo (Chrome's input restart for a new textarea replaces it). */
    private fun editorInfo(): EditorInfo? = live.onMain { live.service()?.currentInputEditorInfo }

    /** Textarea [id]'s text before its caret, read from the page. */
    private fun WebViewTextareaTestActivity.textBeforeCaret(id: String): String? =
        evaluate("(function(){var t=document.getElementById('$id');return t.value.substring(0,t.selectionStart);})()")
            ?.removeSurrounding("\"")

    /**
     * Taps textarea [id] and waits until the keyboard is bound to it: the page focused it, the
     * keyboard is shown, its input started again for this textarea (a new EditorInfo: Chrome
     * restarts the input on a focus change) and its connection reads this textarea's text.
     * Conditions, not a settle time (final Fable audit M4).
     */
    private fun ActivityScenario<WebViewTextareaTestActivity>.tapInto(id: String) {
        val page = page()
        assertTrue("the page loaded", page.loaded.await(10, TimeUnit.SECONDS))
        val infoBefore = editorInfo()
        tap(page.textareaCentreOnScreen(id))
        live.waitFor("textarea $id focused with the keyboard bound to it") {
            page.refreshFocusedId()
            focusedId == id && live.shown() && editorInfo().let { it != null && it !== infoBefore } &&
                page.textBeforeCaret(id)?.let { live.editorText() == it } == true
        }
    }

    @Test
    fun switchingTextareasFormsNoCrossPair() {
        ActivityScenario.launch(WebViewTextareaTestActivity::class.java).use { scenario ->
            scenario.tapInto("a")
            // Baselines once the keyboard service exists (M4: read before, they had no service).
            val baseline = live.bigram("fix", "toy")
            val sameFieldBaseline = live.bigram("red", "fix")
            val controlBaseline = live.bigram("toy", "box")
            live.type("red fix ")
            // Positive control in a (M4): a pair in one textarea IS learned, so the cross-field
            // check below cannot pass with learning off.
            live.waitFor("red → fix learned in a (baseline $sameFieldBaseline)", 5_000L) {
                live.bigram("red", "fix") == sameFieldBaseline + 1
            }
            scenario.tapInto("b")
            live.type("toy box ")
            // And in b: `toy → box` is learned at `box`'s commit, after `toy`'s (the one that
            // would pair it with a's `fix`), so once it is there that pair has been decided.
            live.waitFor("toy → box learned in b (baseline $controlBaseline)", 5_000L) {
                live.bigram("toy", "box") == controlBaseline + 1
            }

            assertEquals("no fix → toy across two textareas", baseline, live.bigram("fix", "toy"))
        }
    }

    @Test
    fun aTextareaClearedFromJavaScriptClosesTheLearnWindow() {
        ActivityScenario.launch(WebViewTextareaTestActivity::class.java).use { scenario ->
            scenario.tapInto("a")
            val baselines = listOf("blue", "car").associateWith { live.bigram(it, "now") }
            val sameFieldBaseline = live.bigram("blue", "car")
            val controlBaseline = live.bigram("now", "here")
            live.type("blue car ")
            // Positive control before the clear (M4): this textarea learns.
            live.waitFor("blue → car learned (baseline $sameFieldBaseline)", 5_000L) {
                live.bigram("blue", "car") == sameFieldBaseline + 1
            }
            scenario.page().evaluate("document.getElementById('a').value = ''; 'cleared'")
            live.waitFor("textarea a empty") { live.editorText().isEmpty() }
            live.type("now here ")
            // And after it: `now → here` is learned at `here`'s commit, after `now`'s (the one
            // that would pair `now` with a cleared word), so those pairs have been decided.
            live.waitFor("now → here learned (baseline $controlBaseline)", 5_000L) {
                live.bigram("now", "here") == controlBaseline + 1
            }

            for ((word, baseline) in baselines) {
                assertEquals("no $word → now after the page cleared the textarea", baseline, live.bigram(word, "now"))
            }
        }
    }

}
