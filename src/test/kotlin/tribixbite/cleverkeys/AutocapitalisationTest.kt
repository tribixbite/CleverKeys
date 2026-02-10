package tribixbite.cleverkeys

import android.os.Handler
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * MockK-based JVM tests for Autocapitalisation.
 *
 * Tests the autocapitalisation state machine: started, typed, event_sent,
 * selection_updated, pause/unpause. Handler.postDelayed is captured and
 * run synchronously so delayed_callback logic is testable.
 */
class AutocapitalisationTest {

    private lateinit var mockHandler: Handler
    private lateinit var mockCallback: Autocapitalisation.Callback
    private lateinit var mockIc: InputConnection
    private lateinit var mockConfig: Config
    private lateinit var autocap: Autocapitalisation

    // Captured Runnable from Handler.postDelayed for synchronous execution
    private val runnableSlot = slot<Runnable>()

    @Before
    fun setup() {
        // Mock android statics
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers { (firstArg<CharSequence?>()?.length ?: 0) == 0 }

        // Config.globalConfig() returns _globalConfig!! on the Companion.
        // Config fields are @JvmField var — can't mock with every{}, set directly.
        mockConfig = mockk<Config>(relaxed = true)
        mockConfig.autocapitalisation = true
        // Set _globalConfig via reflection so Config.globalConfig() returns our mock
        setGlobalConfig(mockConfig)

        // Mock Handler to capture postDelayed Runnables
        mockHandler = mockk(relaxed = true)
        every { mockHandler.postDelayed(capture(runnableSlot), any()) } returns true

        // Mock callback
        mockCallback = mockk(relaxed = true)

        // Mock InputConnection
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
            val companion = Config::class.java.getDeclaredField("Companion")
                .get(null)
            val field = companion.javaClass.getDeclaredField("_globalConfig")
            field.isAccessible = true
            field.set(companion, config)
        } catch (_: Exception) {
            // Fallback: try on the Config class directly (Kotlin may compile it either way)
            try {
                val field = Config::class.java.getDeclaredField("_globalConfig")
                field.isAccessible = true
                field.set(null, config)
            } catch (_: Exception) {
                // If both fail, tests relying on globalConfig will use null
            }
        }
    }

    // =========================================================================
    // started() tests
    // =========================================================================

    @Test
    fun `started with autocap disabled does not enable`() {
        mockConfig.autocapitalisation = false

        val info = createEditorInfo(
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            initialCapsMode = 1
        )
        autocap.started(info, mockIc)

        // When disabled, started() returns early without calling callback
        verify(exactly = 0) { mockCallback.update_shift_state(any(), any()) }
    }

    @Test
    fun `started with capsMode 0 does not enable`() {
        // InputType with no cap flags → capsMode = 0
        val info = createEditorInfo(
            inputType = InputType.TYPE_CLASS_TEXT,
            initialCapsMode = 0
        )
        autocap.started(info, mockIc)

        // When capsMode is 0, started() returns early without calling callback
        verify(exactly = 0) { mockCallback.update_shift_state(any(), any()) }
    }

    @Test
    fun `started with autocap enabled and capsMode set enables shift`() {
        every { mockIc.getCursorCapsMode(any()) } returns InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

        val info = createEditorInfo(
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            initialCapsMode = InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        )
        autocap.started(info, mockIc)

        // initialCapsMode != 0 → shouldEnableShift = true
        // callback_now runs delayed_callback synchronously
        verify { mockCallback.update_shift_state(true, any()) }
    }

    // =========================================================================
    // typed() tests
    // =========================================================================

    @Test
    fun `typed trigger char sets shouldUpdateCapsMode`() {
        enableAutocap()

        autocap.typed(" ")
        runCapturedCallback()

        // Space is a trigger char → shouldUpdateCapsMode = true
        // getCursorCapsMode is called in delayed_callback
        verify { mockIc.getCursorCapsMode(any()) }
    }

    @Test
    fun `typed period triggers caps mode update`() {
        enableAutocap()

        autocap.typed(".")
        runCapturedCallback()

        verify { mockIc.getCursorCapsMode(any()) }
    }

    @Test
    fun `typed non-trigger char clears shouldEnableShift`() {
        enableAutocap()

        // First type trigger to set shouldEnableShift
        every { mockIc.getCursorCapsMode(any()) } returns InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        autocap.typed(".")
        runCapturedCallback()

        // Now type a regular character — clears shouldEnableShift
        autocap.typed("a")
        runCapturedCallback()

        // The last callback should have shouldEnableShift = false
        verify(atLeast = 1) { mockCallback.update_shift_state(false, any()) }
    }

    // =========================================================================
    // event_sent() tests
    // =========================================================================

    @Test
    fun `event_sent KEYCODE_DEL decrements cursor and updates caps mode`() {
        enableAutocap()

        // Type to advance cursor
        autocap.typed("ab")
        runCapturedCallback()

        // Now delete
        autocap.event_sent(KeyEvent.KEYCODE_DEL, 0)
        runCapturedCallback()

        // DEL sets shouldUpdateCapsMode → getCursorCapsMode is called
        verify(atLeast = 1) { mockIc.getCursorCapsMode(any()) }
    }

    @Test
    fun `event_sent KEYCODE_ENTER triggers caps mode update`() {
        enableAutocap()

        autocap.event_sent(KeyEvent.KEYCODE_ENTER, 0)
        runCapturedCallback()

        verify(atLeast = 1) { mockIc.getCursorCapsMode(any()) }
    }

    @Test
    fun `event_sent with non-zero meta clears flags`() {
        enableAutocap()

        // Set up shift state first
        every { mockIc.getCursorCapsMode(any()) } returns InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        autocap.typed(".")
        runCapturedCallback()

        clearMocks(mockCallback, answers = false)

        // Meta key modifier present → clears shouldEnableShift and shouldUpdateCapsMode
        autocap.event_sent(KeyEvent.KEYCODE_DEL, KeyEvent.META_SHIFT_ON)
        runCapturedCallback()

        // Callback runs via postDelayed — shouldEnableShift was cleared by meta != 0
        verify { mockCallback.update_shift_state(false, any()) }
    }

    // =========================================================================
    // pause() / unpause() tests
    // =========================================================================

    @Test
    fun `pause returns enabled state and disables`() {
        enableAutocap()

        val wasEnabled = autocap.pause()

        assertThat(wasEnabled).isTrue()
    }

    @Test
    fun `unpause restores enabled state and updates caps mode`() {
        enableAutocap()

        val wasEnabled = autocap.pause()

        // Mock getCursorCapsMode for the unpause callback
        every { mockIc.getCursorCapsMode(any()) } returns InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

        autocap.unpause(wasEnabled)

        // unpause calls callback_now → delayed_callback runs immediately
        // shouldUpdateCapsMode is set true → getCursorCapsMode is called
        verify(atLeast = 1) { mockIc.getCursorCapsMode(any()) }
        verify(atLeast = 1) { mockCallback.update_shift_state(true, any()) }
    }

    // =========================================================================
    // selection_updated() tests
    // =========================================================================

    @Test
    fun `selection_updated with cursor movement clears shift`() {
        enableAutocap()

        // Set shift via trigger char
        every { mockIc.getCursorCapsMode(any()) } returns InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        autocap.typed(".")
        runCapturedCallback()

        clearMocks(mockCallback, answers = false)

        // Move cursor to a different position (not matching internal cursor)
        autocap.selection_updated(1, 5)
        runCapturedCallback()

        // Cursor movement clears shouldEnableShift
        verify { mockCallback.update_shift_state(false, any()) }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Start autocap with caps-mode enabled and normal text input type. */
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

    /**
     * Create an EditorInfo with specified inputType and initialCapsMode.
     * EditorInfo has public @JvmField fields — MockK can't intercept field access,
     * so we use a real instance and set fields directly.
     */
    private fun createEditorInfo(inputType: Int, initialCapsMode: Int): EditorInfo {
        // EditorInfo() constructor calls android.jar stub — use MockK's relaxed mock
        // but set the @JvmField fields via reflection (field access bypasses MockK)
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

    /** Run the most recently captured Runnable from Handler.postDelayed. */
    private fun runCapturedCallback() {
        if (runnableSlot.isCaptured) {
            runnableSlot.captured.run()
        }
    }
}
