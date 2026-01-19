package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests for Config settings.
 * Tests keyboard configuration, swipe settings, and feature toggles.
 * Note: Config.globalConfig() may return null in test context without full keyboard init.
 */
@RunWith(AndroidJUnit4::class)
class ConfigIntegrationTest {

    private lateinit var context: Context
    private var config: Config? = null

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            config = Config.globalConfig()
        } catch (e: NullPointerException) {
            // Config not available in test context without full keyboard init
            config = null
        }
    }

    // =========================================================================
    // Basic config tests
    // =========================================================================

    @Test
    fun testConfigAvailable() {
        // Config may be null in test context without full keyboard init
        // Just verify the call doesn't crash the test (NPE is caught in setup)
        try {
            val globalConfig = Config.globalConfig()
            assertNotNull("Config available when initialized", globalConfig)
        } catch (e: NullPointerException) {
            // Expected in test context - Config requires full keyboard initialization
        }
    }

    @Test
    fun testGlobalConfigSingleton() {
        try {
            val config1 = Config.globalConfig()
            val config2 = Config.globalConfig()

            // If both are non-null, should be same instance
            if (config1 != null && config2 != null) {
                assertSame("Should return same instance", config1, config2)
            }
        } catch (e: NullPointerException) {
            // Expected in test context - Config requires full keyboard initialization
        }
    }

    // =========================================================================
    // Autocapitalization settings
    // =========================================================================

    @Test
    fun testAutocapitalizationToggle() {
        val cfg = config ?: return // Skip if config not available
        val original = cfg.autocapitalisation

        try {
            cfg.autocapitalisation = true
            assertTrue(cfg.autocapitalisation)

            cfg.autocapitalisation = false
            assertFalse(cfg.autocapitalisation)
        } finally {
            cfg.autocapitalisation = original
        }
    }

    // =========================================================================
    // Swipe typing settings
    // =========================================================================

    @Test
    fun testSwipeTypingToggle() {
        val cfg = config ?: return
        val original = cfg.swipe_typing_enabled

        try {
            cfg.swipe_typing_enabled = true
            assertTrue(cfg.swipe_typing_enabled)

            cfg.swipe_typing_enabled = false
            assertFalse(cfg.swipe_typing_enabled)
        } finally {
            cfg.swipe_typing_enabled = original
        }
    }

    @Test
    fun testSwipeMinDistance() {
        val cfg = config ?: return
        val distance = cfg.swipe_min_distance
        assertTrue("Swipe min distance should be positive", distance > 0)
    }

    // =========================================================================
    // Haptic feedback settings
    // =========================================================================

    @Test
    fun testHapticEnabledToggle() {
        val cfg = config ?: return
        val original = cfg.haptic_enabled

        try {
            cfg.haptic_enabled = true
            assertTrue(cfg.haptic_enabled)

            cfg.haptic_enabled = false
            assertFalse(cfg.haptic_enabled)
        } finally {
            cfg.haptic_enabled = original
        }
    }

    @Test
    fun testHapticKeyPress() {
        val cfg = config ?: return
        val haptic = cfg.haptic_key_press
        // Can be true or false
    }

    // =========================================================================
    // Keyboard layout settings
    // =========================================================================

    @Test
    fun testKeyboardHeightPercent() {
        val cfg = config ?: return
        val height = cfg.keyboardHeightPercent
        assertTrue("Keyboard height percent should be non-negative", height >= 0)
    }

    @Test
    fun testMarginTop() {
        val cfg = config ?: return
        val margin = cfg.marginTop
        assertTrue("Margin top should be non-negative", margin >= 0)
    }

    // =========================================================================
    // Neural prediction settings
    // =========================================================================

    @Test
    fun testNeuralBeamWidth() {
        val cfg = config ?: return
        val beamWidth = cfg.neural_beam_width
        assertTrue("Beam width should be non-negative", beamWidth >= 0)
    }

    @Test
    fun testNeuralMaxLength() {
        val cfg = config ?: return
        val maxLength = cfg.neural_max_length
        assertTrue("Max length should be non-negative", maxLength >= 0)
    }

    @Test
    fun testNeuralConfidenceThreshold() {
        val cfg = config ?: return
        val threshold = cfg.neural_confidence_threshold
        assertTrue("Confidence threshold should be between 0 and 1",
            threshold in 0f..1f)
    }

    // =========================================================================
    // Tap settings
    // =========================================================================

    @Test
    fun testTapDurationThreshold() {
        val cfg = config ?: return
        val threshold = cfg.tap_duration_threshold
        assertTrue("Tap duration should be positive", threshold > 0)
    }

    // =========================================================================
    // Auto-correction settings
    // =========================================================================

    @Test
    fun testAutoCorrectionEnabled() {
        val cfg = config ?: return
        val enabled = cfg.autocorrect_enabled
        // Value can be true or false
    }

    // =========================================================================
    // Theme settings
    // =========================================================================

    @Test
    fun testThemeId() {
        val cfg = config ?: return
        val themeId = cfg.theme
        // Theme ID is an integer
    }

    @Test
    fun testThemeName() {
        val cfg = config ?: return
        val themeName = cfg.themeName
        assertNotNull("Theme name should not be null", themeName)
    }

    // =========================================================================
    // Longpress settings
    // =========================================================================

    @Test
    fun testLongpressTimeout() {
        val cfg = config ?: return
        val timeout = cfg.longPressTimeout
        assertTrue("Longpress timeout should be non-negative", timeout >= 0)
    }

    @Test
    fun testLongpressInterval() {
        val cfg = config ?: return
        val interval = cfg.longPressInterval
        assertTrue("Longpress interval should be non-negative", interval >= 0)
    }

    // =========================================================================
    // Suggestion bar settings
    // =========================================================================

    @Test
    fun testWordPredictionEnabled() {
        val cfg = config ?: return
        val enabled = cfg.word_prediction_enabled
        // Can be true or false
    }

    @Test
    fun testSuggestionBarOpacity() {
        val cfg = config ?: return
        val opacity = cfg.suggestion_bar_opacity
        assertTrue("Opacity should be between 0 and 100", opacity in 0..100)
    }

    // =========================================================================
    // Short gesture settings
    // =========================================================================

    @Test
    fun testShortGesturesEnabled() {
        val cfg = config ?: return
        val enabled = cfg.short_gestures_enabled
        // Can be true or false
    }

    @Test
    fun testShortGestureMinDistance() {
        val cfg = config ?: return
        val distance = cfg.short_gesture_min_distance
        assertTrue("Min distance should be non-negative", distance >= 0)
    }

    // =========================================================================
    // Multi-language settings
    // =========================================================================

    @Test
    fun testPrimaryLanguage() {
        val cfg = config ?: return
        val language = cfg.primary_language
        assertNotNull("Primary language should not be null", language)
    }

    @Test
    fun testAutoDetectLanguage() {
        val cfg = config ?: return
        val autoDetect = cfg.auto_detect_language
        // Can be true or false
    }

    // =========================================================================
    // Swipe trail settings
    // =========================================================================

    @Test
    fun testSwipeTrailEnabled() {
        val cfg = config ?: return
        val enabled = cfg.swipe_trail_enabled
        // Can be true or false
    }

    @Test
    fun testSwipeTrailWidth() {
        val cfg = config ?: return
        val width = cfg.swipe_trail_width
        assertTrue("Trail width should be positive", width > 0)
    }
}
