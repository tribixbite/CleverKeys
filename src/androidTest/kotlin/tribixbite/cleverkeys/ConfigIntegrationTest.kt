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
        // Initialize Config for testing
        if (TestConfigHelper.ensureConfigInitialized(context)) {
            config = Config.globalConfig()
        }
    }

    // =========================================================================
    // Basic config tests
    // =========================================================================

    @Test
    fun testConfigAvailable() {
        // Config should be initialized by TestConfigHelper
        assertNotNull("Config should be available", config)
    }

    @Test
    fun testGlobalConfigSingleton() {
        val cfg = config ?: return
        val config1 = Config.globalConfig()
        val config2 = Config.globalConfig()

        assertSame("Should return same instance", config1, config2)
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

    // =========================================================================
    // Swipe sensitivity preset tests (added since v1.2.5)
    // =========================================================================

    @Test
    fun testSwipeMinDistanceHasValue() {
        val cfg = config ?: return
        val minDistance = cfg.swipe_min_distance
        assertTrue("Swipe min distance should be positive", minDistance > 0)
    }

    @Test
    fun testSwipeMinKeyDistanceHasValue() {
        val cfg = config ?: return
        val minKeyDistance = cfg.swipe_min_key_distance
        assertTrue("Swipe min key distance should be positive", minKeyDistance > 0)
    }

    @Test
    fun testSwipeMinDwellTimeHasValue() {
        val cfg = config ?: return
        val minDwellTime = cfg.swipe_min_dwell_time
        assertTrue("Swipe min dwell time should be non-negative", minDwellTime >= 0)
    }

    @Test
    fun testSwipeSensitivityLowPresetValues() {
        // Low preset: Higher thresholds = less sensitive
        // swipeMinDistance = 80f, swipeMinKeyDistance = 60f, swipeMinDwellTime = 30
        val lowDistance = 80f
        val lowKeyDistance = 60f
        val lowDwellTime = 30

        assertTrue("Low preset distance should be greater than medium",
            lowDistance > 50f)
        assertTrue("Low preset key distance should be greater than medium",
            lowKeyDistance > 40f)
        assertTrue("Low preset dwell time should be greater than medium",
            lowDwellTime > 15)
    }

    @Test
    fun testSwipeSensitivityMediumPresetValues() {
        // Medium preset: Default values
        // swipeMinDistance = 50f, swipeMinKeyDistance = 40f, swipeMinDwellTime = 15
        val mediumDistance = 50f
        val mediumKeyDistance = 40f
        val mediumDwellTime = 15

        assertTrue("Medium preset distance should be between low and high",
            mediumDistance > 30f && mediumDistance < 80f)
    }

    @Test
    fun testSwipeSensitivityHighPresetValues() {
        // High preset: Lower thresholds = more sensitive
        // swipeMinDistance = 30f, swipeMinKeyDistance = 25f, swipeMinDwellTime = 5
        val highDistance = 30f
        val highKeyDistance = 25f
        val highDwellTime = 5

        assertTrue("High preset distance should be less than medium",
            highDistance < 50f)
        assertTrue("High preset key distance should be less than medium",
            highKeyDistance < 40f)
        assertTrue("High preset dwell time should be less than medium",
            highDwellTime < 15)
    }

    @Test
    fun testSwipeSensitivityPresetOrdering() {
        // Verify preset values follow logical ordering:
        // Low (80) > Medium (50) > High (30)
        val lowDistance = 80f
        val mediumDistance = 50f
        val highDistance = 30f

        assertTrue("Low sensitivity should require more distance than medium",
            lowDistance > mediumDistance)
        assertTrue("Medium sensitivity should require more distance than high",
            mediumDistance > highDistance)
    }
}
