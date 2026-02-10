package tribixbite.cleverkeys

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * MockK-based JVM tests for VibratorCompat.
 *
 * VibratorCompat is a Kotlin `object` (singleton) with @JvmStatic methods.
 * We test through the public API and reset internal state between tests.
 */
class VibratorCompatTest {

    private lateinit var mockView: View
    private lateinit var mockConfig: Config
    private lateinit var mockContext: Context
    private lateinit var mockVibrator: Vibrator

    @Before
    fun setup() {
        // Mock android.util.Log (not used directly but may be called transitively)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0

        mockContext = mockk(relaxed = true)
        mockVibrator = mockk(relaxed = true)
        mockView = mockk(relaxed = true)
        mockConfig = mockk(relaxed = true)

        every { mockView.context } returns mockContext

        // Config uses @JvmField var — MockK can't intercept field access, so we set them directly.
        // Objenesis (used by MockK) creates the instance without calling the constructor,
        // so fields start at JVM defaults (false/0). We set the test defaults here.
        mockConfig.haptic_enabled = true
        mockConfig.haptic_key_press = true
        mockConfig.haptic_prediction_tap = true
        mockConfig.haptic_trackpoint_activate = true
        mockConfig.haptic_long_press = true
        mockConfig.haptic_swipe_complete = true
        mockConfig.vibrate_custom = false
        mockConfig.vibrate_duration = 0L

        // performHapticFeedback succeeds by default
        every { mockView.performHapticFeedback(any(), any()) } returns true

        // Reset VibratorCompat internal state between tests
        resetVibratorService()
        VibratorCompat.invalidateSettingsCache()
    }

    @After
    fun teardown() {
        unmockkStatic(android.util.Log::class)
        resetVibratorService()
    }

    /**
     * Reset the cached vibratorService field via reflection so tests are independent.
     */
    private fun resetVibratorService() {
        try {
            val field = VibratorCompat::class.java.getDeclaredField("vibratorService")
            field.isAccessible = true
            field.set(VibratorCompat, null)
        } catch (_: Exception) {
            // Field might not exist in test environment — safe to ignore
        }
    }

    // =========================================================================
    // Master toggle tests
    // =========================================================================

    @Test
    fun `vibrate with haptic_enabled false does nothing`() {
        mockConfig.haptic_enabled = false

        VibratorCompat.vibrate(mockView, mockConfig, HapticEvent.KEY_PRESS)

        // performHapticFeedback should never be called
        verify(exactly = 0) { mockView.performHapticFeedback(any(), any()) }
    }

    @Test
    fun `vibrate with haptic_enabled true calls performHapticFeedback`() {
        VibratorCompat.vibrate(mockView, mockConfig, HapticEvent.KEY_PRESS)

        verify(exactly = 1) { mockView.performHapticFeedback(any(), any()) }
    }

    // =========================================================================
    // Per-event toggle tests
    // =========================================================================

    @Test
    fun `vibrate with key_press disabled does nothing`() {
        mockConfig.haptic_key_press = false

        VibratorCompat.vibrate(mockView, mockConfig, HapticEvent.KEY_PRESS)

        verify(exactly = 0) { mockView.performHapticFeedback(any(), any()) }
    }

    @Test
    fun `vibrate with prediction_tap disabled does nothing`() {
        mockConfig.haptic_prediction_tap = false

        VibratorCompat.vibrate(mockView, mockConfig, HapticEvent.PREDICTION_TAP)

        verify(exactly = 0) { mockView.performHapticFeedback(any(), any()) }
    }

    @Test
    fun `vibrate with trackpoint_activate disabled does nothing`() {
        mockConfig.haptic_trackpoint_activate = false

        VibratorCompat.vibrate(mockView, mockConfig, HapticEvent.TRACKPOINT_ACTIVATE)

        verify(exactly = 0) { mockView.performHapticFeedback(any(), any()) }
    }

    @Test
    fun `vibrate with long_press disabled does nothing`() {
        mockConfig.haptic_long_press = false

        VibratorCompat.vibrate(mockView, mockConfig, HapticEvent.LONG_PRESS)

        verify(exactly = 0) { mockView.performHapticFeedback(any(), any()) }
    }

    @Test
    fun `vibrate with swipe_complete disabled does nothing`() {
        mockConfig.haptic_swipe_complete = false

        VibratorCompat.vibrate(mockView, mockConfig, HapticEvent.SWIPE_COMPLETE)

        verify(exactly = 0) { mockView.performHapticFeedback(any(), any()) }
    }

    // =========================================================================
    // System haptic feedback path
    // =========================================================================

    @Test
    fun `vibrate uses performHapticFeedback with ignore flags`() {
        VibratorCompat.vibrate(mockView, mockConfig, HapticEvent.KEY_PRESS)

        val expectedFlags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING

        verify { mockView.performHapticFeedback(any(), expectedFlags) }
    }

    @Test
    fun `vibrate falls back to vibrator when performHapticFeedback returns false`() {
        every { mockView.performHapticFeedback(any(), any()) } returns false

        // Set up vibrator retrieval (pre-S path via VIBRATOR_SERVICE)
        setSdkInt(26) // API 26 (O) — can't use Build.VERSION_CODES.O as it's 0 in android.jar stubs
        every { mockContext.getSystemService(Context.VIBRATOR_SERVICE) } returns mockVibrator
        every { mockVibrator.hasVibrator() } returns true

        // Mock VibrationEffect.createOneShot — android.jar stub throws Stub!
        val mockEffect = mockk<VibrationEffect>()
        mockkStatic(VibrationEffect::class)
        every { VibrationEffect.createOneShot(any(), any()) } returns mockEffect

        VibratorCompat.vibrate(mockView, mockConfig, HapticEvent.KEY_PRESS)

        // Vibrator.vibrate should be called as fallback
        verify { mockVibrator.vibrate(any<VibrationEffect>()) }
        unmockkStatic(VibrationEffect::class)
    }

    // =========================================================================
    // Custom duration path
    // =========================================================================

    @Test
    fun `vibrate with custom duration uses vibrator directly`() {
        mockConfig.vibrate_custom = true
        mockConfig.vibrate_duration = 25L

        setSdkInt(26) // API 26 (O) — can't use Build.VERSION_CODES.O as it's 0 in android.jar stubs
        every { mockContext.getSystemService(Context.VIBRATOR_SERVICE) } returns mockVibrator
        every { mockVibrator.hasVibrator() } returns true

        // Mock VibrationEffect.createOneShot — android.jar stub throws Stub!
        val mockEffect = mockk<VibrationEffect>()
        mockkStatic(VibrationEffect::class)
        every { VibrationEffect.createOneShot(any(), any()) } returns mockEffect

        VibratorCompat.vibrate(mockView, mockConfig, HapticEvent.KEY_PRESS)

        // Should NOT use performHapticFeedback
        verify(exactly = 0) { mockView.performHapticFeedback(any(), any()) }
        // Should use Vibrator directly
        verify { mockVibrator.vibrate(any<VibrationEffect>()) }
        unmockkStatic(VibrationEffect::class)
    }

    // =========================================================================
    // HapticEvent enum tests
    // =========================================================================

    @Test
    fun `HapticEvent enum has exactly 5 values`() {
        assertThat(HapticEvent.values()).hasLength(5)
    }

    @Test
    fun `HapticEvent enum contains expected events`() {
        val names = HapticEvent.values().map { it.name }
        assertThat(names).containsExactly(
            "KEY_PRESS",
            "PREDICTION_TAP",
            "TRACKPOINT_ACTIVATE",
            "LONG_PRESS",
            "SWIPE_COMPLETE"
        )
    }

    // =========================================================================
    // invalidateSettingsCache test
    // =========================================================================

    @Test
    fun `invalidateSettingsCache resets cached setting without error`() {
        // Just verify it doesn't throw — the field is private, but we can verify
        // the method runs cleanly
        VibratorCompat.invalidateSettingsCache()
        // Call again to verify idempotency
        VibratorCompat.invalidateSettingsCache()
        // No assertion needed — no crash = pass
    }

    // =========================================================================
    // Multiple event types use distinct haptic constants
    // =========================================================================

    @Test
    fun `different events call performHapticFeedback with different constants`() {
        val capturedConstants = mutableListOf<Int>()
        every { mockView.performHapticFeedback(capture(capturedConstants), any()) } returns true

        VibratorCompat.vibrate(mockView, mockConfig, HapticEvent.KEY_PRESS)
        VibratorCompat.vibrate(mockView, mockConfig, HapticEvent.LONG_PRESS)

        // KEY_PRESS and LONG_PRESS should produce different haptic constants
        assertThat(capturedConstants).hasSize(2)
        // LONG_PRESS always uses HapticFeedbackConstants.LONG_PRESS (0)
        // KEY_PRESS uses KEYBOARD_TAP or VIRTUAL_KEY depending on SDK
        // They should be different
        assertThat(capturedConstants[0]).isNotEqualTo(capturedConstants[1])
    }

    // =========================================================================
    // Helper: set Build.VERSION.SDK_INT via reflection
    // =========================================================================

    /**
     * Set Build.VERSION.SDK_INT via Unsafe reflection for branch testing.
     * Java 17+ removed Field.modifiers access, so we use sun.misc.Unsafe
     * which can modify any field regardless of final/accessibility.
     */
    private fun setSdkInt(sdkInt: Int) {
        try {
            val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null)
            val unsafeClass = unsafe.javaClass

            val field = Build.VERSION::class.java.getField("SDK_INT")
            val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java)
                .invoke(unsafe, field)
            val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java)
                .invoke(unsafe, field) as Long
            unsafeClass.getMethod("putInt", Object::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(unsafe, base, offset, sdkInt)
        } catch (_: Exception) {
            // If Unsafe fails, SDK_INT stays at android.jar default (0)
        }
    }
}
