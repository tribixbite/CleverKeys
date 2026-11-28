package tribixbite.cleverkeys

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
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
 *  1. `fix ` in textarea a, a tap on textarea b, `toy `: no `fix → toy`.
 *  2. `blue car ` in textarea a, the page clears it from JavaScript, `now `: no `car → now`
 *     and no `blue → now` (the cleared words' learn window is closed).
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

    /** [focus], polling the page's focused element off the main thread. */
    private fun ActivityScenario<WebViewTextareaTestActivity>.tapInto(id: String) {
        val page = page()
        assertTrue("the page loaded", page.loaded.await(10, TimeUnit.SECONDS))
        tap(page.textareaCentreOnScreen(id))
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (SystemClock.uptimeMillis() < deadline) {
            page.refreshFocusedId()
            if (focusedId == id && live.within(200L) { live.shown() }) break
            Thread.sleep(100L)
        }
        assertEquals("textarea $id focused with the keyboard shown (${live.state()})", id, focusedId)
        Thread.sleep(SETTLE_MS)
    }

    @Test
    fun switchingTextareasFormsNoCrossPair() {
        ActivityScenario.launch(WebViewTextareaTestActivity::class.java).use { scenario ->
            val baseline = live.bigram("fix", "toy")
            scenario.tapInto("a")
            live.type("fix ")
            scenario.tapInto("b")
            live.type("toy ")
            live.waitFor("toy typed in b") { live.editorText().equals("toy ", ignoreCase = true) }

            assertEquals("no fix → toy across two textareas", baseline, live.bigram("fix", "toy"))
        }
    }

    @Test
    fun aTextareaClearedFromJavaScriptClosesTheLearnWindow() {
        ActivityScenario.launch(WebViewTextareaTestActivity::class.java).use { scenario ->
            val baselines = listOf("blue", "car").associateWith { live.bigram(it, "now") }
            scenario.tapInto("a")
            live.type("blue car ")
            scenario.page().evaluate("document.getElementById('a').value = ''; 'cleared'")
            live.waitFor("textarea a empty") { live.editorText().isEmpty() }
            Thread.sleep(SETTLE_MS)
            live.type("now ")
            live.waitFor("now typed") { live.editorText().equals("now ", ignoreCase = true) }

            for ((word, baseline) in baselines) {
                assertEquals("no $word → now after the page cleared the textarea", baseline, live.bigram(word, "now"))
            }
        }
    }

    private companion object {
        /** Longer than Chrome's input restart after a focus change and the IME's show animation. */
        const val SETTLE_MS = 700L
    }
}
