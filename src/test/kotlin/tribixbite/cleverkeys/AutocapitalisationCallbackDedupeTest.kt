package tribixbite.cleverkeys

import android.os.Handler
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * MockK-based JVM tests for the Autocapitalisation callback dedupe fix (P3).
 *
 * The delayed shift-state callback previously stacked up: a rapid sequence of typed/event
 * calls could leave multiple queued [Runnable]s, each firing with stale shift state. The fix
 * removes any queued callback before (re)posting and before running immediately. These tests
 * verify the removeCallbacks/postDelayed ordering.
 *
 * Setup/enableAutocap/createEditorInfo mirror AutocapitalisationTest.kt.
 */
class AutocapitalisationCallbackDedupeTest {

    private lateinit var mockHandler: Handler
    private lateinit var mockCallback: Autocapitalisation.Callback
    private lateinit var mockIc: InputConnection
    private lateinit var mockConfig: Config
    private lateinit var autocap: Autocapitalisation

    private val runnableSlot = slot<Runnable>()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers { (firstArg<CharSequence?>()?.length ?: 0) == 0 }

        mockConfig = mockk<Config>(relaxed = true)
        mockConfig.autocapitalisation = true
        setGlobalConfig(mockConfig)

        mockHandler = mockk(relaxed = true)
        every { mockHandler.postDelayed(capture(runnableSlot), any()) } returns true
        every { mockHandler.removeCallbacks(any()) } just Runs

        mockCallback = mockk(relaxed = true)

        mockIc = mockk(relaxed = true)
        every { mockIc.getCursorCapsMode(any()) } returns 0

        autocap = Autocapitalisation(mockHandler, mockCallback)
    }

    @After
    fun teardown() {
        unmockkStatic(Log::class)
        unmockkStatic(TextUtils::class)
        setGlobalConfig(null)
    }

    /** Set Config._globalConfig via reflection on the Companion object. */
    private fun setGlobalConfig(config: Config?) {
        try {
            val companion = Config::class.java.getDeclaredField("Companion").get(null)
            val field = companion.javaClass.getDeclaredField("_globalConfig")
            field.isAccessible = true
            field.set(companion, config)
        } catch (_: Exception) {
            try {
                val field = Config::class.java.getDeclaredField("_globalConfig")
                field.isAccessible = true
                field.set(null, config)
            } catch (_: Exception) {
                // If both fail, tests relying on globalConfig will use null
            }
        }
    }

    @Test
    fun typedRemovesStaleCallbackBeforePosting() {
        enableAutocap()
        // Ignore interactions from started()/enableAutocap() — we care about the typed() path.
        clearMocks(mockHandler, answers = false)
        every { mockHandler.postDelayed(capture(runnableSlot), any()) } returns true
        every { mockHandler.removeCallbacks(any()) } just Runs

        // typed(non-trigger) drives callback(false), which must dedupe before re-posting.
        autocap.typed("a")

        // The stale queued callback must be removed BEFORE the fresh one is posted with 50ms delay.
        verifyOrder {
            mockHandler.removeCallbacks(any())
            mockHandler.postDelayed(any(), 50)
        }
    }

    @Test
    fun callbackNowCancelsQueuedCallback() {
        enableAutocap()
        clearMocks(mockHandler, answers = false)
        every { mockHandler.postDelayed(capture(runnableSlot), any()) } returns true
        every { mockHandler.removeCallbacks(any()) } just Runs

        // stop() runs callback_now(true), which must cancel any queued delayed callback
        // (removeCallbacks) before running it immediately — it must NOT postDelayed.
        autocap.stop()

        verify { mockHandler.removeCallbacks(any()) }
        verify(exactly = 0) { mockHandler.postDelayed(any(), any()) }
    }

    // ========================= Helpers (mirror AutocapitalisationTest) =========================

    private fun enableAutocap() {
        mockConfig.autocapitalisation = true
        every { mockIc.getCursorCapsMode(any()) } returns 0

        val info = createEditorInfo(
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_VARIATION_NORMAL,
            initialCapsMode = InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        )
        autocap.started(info, mockIc)
    }

    private fun createEditorInfo(inputType: Int, initialCapsMode: Int): EditorInfo {
        val info = mockk<EditorInfo>(relaxed = true)
        val clazz = EditorInfo::class.java
        try {
            clazz.getField("inputType").set(info, inputType)
            clazz.getField("initialCapsMode").set(info, initialCapsMode)
        } catch (_: Exception) {
            // Fields might not be settable on mock proxy — shouldn't happen with MockK
        }
        return info
    }
}
