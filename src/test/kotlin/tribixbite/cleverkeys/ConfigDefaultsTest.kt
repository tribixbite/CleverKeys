package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Comprehensive pure JVM tests for Config.Defaults values.
 *
 * Every default documented in the wiki MUST have a corresponding test here.
 * Tests validate exact values AND valid ranges to catch regressions.
 *
 * Wiki source: docs/wiki/settings/, docs/wiki/typing/, docs/wiki/gestures/,
 *              docs/wiki/customization/, docs/wiki/clipboard/, docs/wiki/FAQ.md
 */
class ConfigDefaultsTest {

    // =========================================================================
    // Appearance Settings (wiki: settings/appearance.md)
    // =========================================================================

    @Test
    fun `theme default is cleverkeysdark`() {
        assertThat(Defaults.THEME).isEqualTo("cleverkeysdark")
    }

    @Test
    fun `keyboard height portrait default is 30 percent`() {
        assertThat(Defaults.KEYBOARD_HEIGHT_PORTRAIT).isEqualTo(30)
    }

    @Test
    fun `keyboard height landscape default is 40 percent`() {
        assertThat(Defaults.KEYBOARD_HEIGHT_LANDSCAPE).isEqualTo(40)
    }

    @Test
    fun `label brightness default is 100`() {
        assertThat(Defaults.LABEL_BRIGHTNESS).isEqualTo(100)
    }

    @Test
    fun `keyboard opacity default is 81`() {
        assertThat(Defaults.KEYBOARD_OPACITY).isEqualTo(81)
    }

    @Test
    fun `key opacity default is 100`() {
        assertThat(Defaults.KEY_OPACITY).isEqualTo(100)
    }

    @Test
    fun `key activated opacity default is 100`() {
        assertThat(Defaults.KEY_ACTIVATED_OPACITY).isEqualTo(100)
    }

    @Test
    fun `character size default is 1_18`() {
        assertThat(Defaults.CHARACTER_SIZE).isWithin(0.01f).of(1.18f)
    }

    @Test
    fun `key vertical margin default is 1_5`() {
        assertThat(Defaults.KEY_VERTICAL_MARGIN).isWithin(0.01f).of(1.5f)
    }

    @Test
    fun `key horizontal margin default is 2_0`() {
        assertThat(Defaults.KEY_HORIZONTAL_MARGIN).isWithin(0.01f).of(2.0f)
    }

    @Test
    fun `border config disabled by default`() {
        assertThat(Defaults.BORDER_CONFIG).isFalse()
    }

    @Test
    fun `custom border radius default is 0`() {
        assertThat(Defaults.CUSTOM_BORDER_RADIUS).isEqualTo(0)
    }

    @Test
    fun `custom border line width default is 0`() {
        assertThat(Defaults.CUSTOM_BORDER_LINE_WIDTH).isEqualTo(0)
    }

    @Test
    fun `show numpad default is never`() {
        assertThat(Defaults.SHOW_NUMPAD).isEqualTo("never")
    }

    @Test
    fun `numpad layout default is default`() {
        assertThat(Defaults.NUMPAD_LAYOUT).isEqualTo("default")
    }

    @Test
    fun `number row default is no_number_row`() {
        assertThat(Defaults.NUMBER_ROW).isEqualTo("no_number_row")
    }

    @Test
    fun `number entry layout default is pin`() {
        assertThat(Defaults.NUMBER_ENTRY_LAYOUT).isEqualTo("pin")
    }

    @Test
    fun `scale numpad height default is true`() {
        assertThat(Defaults.SCALE_NUMPAD_HEIGHT).isTrue()
    }

    // =========================================================================
    // Margins (wiki: settings/appearance.md)
    // =========================================================================

    @Test
    fun `margin bottom portrait default is 0`() {
        assertThat(Defaults.MARGIN_BOTTOM_PORTRAIT).isEqualTo(0)
    }

    @Test
    fun `margin bottom landscape default is 0`() {
        assertThat(Defaults.MARGIN_BOTTOM_LANDSCAPE).isEqualTo(0)
    }

    @Test
    fun `margin left portrait default is 1`() {
        assertThat(Defaults.MARGIN_LEFT_PORTRAIT).isEqualTo(1)
    }

    @Test
    fun `margin left landscape default is 5`() {
        assertThat(Defaults.MARGIN_LEFT_LANDSCAPE).isEqualTo(5)
    }

    @Test
    fun `margin right portrait default is 1`() {
        assertThat(Defaults.MARGIN_RIGHT_PORTRAIT).isEqualTo(1)
    }

    @Test
    fun `margin right landscape default is 5`() {
        assertThat(Defaults.MARGIN_RIGHT_LANDSCAPE).isEqualTo(5)
    }

    @Test
    fun `horizontal margin portrait default is 3`() {
        assertThat(Defaults.HORIZONTAL_MARGIN_PORTRAIT).isEqualTo(3)
    }

    @Test
    fun `horizontal margin landscape default is 28`() {
        assertThat(Defaults.HORIZONTAL_MARGIN_LANDSCAPE).isEqualTo(28)
    }

    // =========================================================================
    // Haptics / Accessibility (wiki: settings/haptics.md, accessibility.md)
    // =========================================================================

    @Test
    fun `vibrate custom disabled by default`() {
        assertThat(Defaults.VIBRATE_CUSTOM).isFalse()
    }

    @Test
    fun `vibrate duration default is 20ms`() {
        assertThat(Defaults.VIBRATE_DURATION).isEqualTo(20)
    }

    @Test
    fun `haptic enabled by default`() {
        assertThat(Defaults.HAPTIC_ENABLED).isTrue()
    }

    @Test
    fun `haptic key press enabled by default`() {
        assertThat(Defaults.HAPTIC_KEY_PRESS).isTrue()
    }

    @Test
    fun `haptic prediction tap enabled by default`() {
        assertThat(Defaults.HAPTIC_PREDICTION_TAP).isTrue()
    }

    @Test
    fun `haptic trackpoint activate enabled by default`() {
        assertThat(Defaults.HAPTIC_TRACKPOINT_ACTIVATE).isTrue()
    }

    @Test
    fun `haptic long press enabled by default`() {
        assertThat(Defaults.HAPTIC_LONG_PRESS).isTrue()
    }

    @Test
    fun `haptic swipe complete disabled by default`() {
        // wiki: "Swipe Complete is disabled by default as it can be distracting"
        assertThat(Defaults.HAPTIC_SWIPE_COMPLETE).isFalse()
    }

    // =========================================================================
    // Input Behavior (wiki: settings/input-behavior.md)
    // =========================================================================

    @Test
    fun `longpress timeout default is 600ms`() {
        // wiki: "Long-Press Timeout - Time before key repeat starts (default: 600ms)"
        assertThat(Defaults.LONGPRESS_TIMEOUT).isEqualTo(600)
    }

    @Test
    fun `longpress interval default is 25ms`() {
        assertThat(Defaults.LONGPRESS_INTERVAL).isEqualTo(25)
    }

    @Test
    fun `key repeat enabled by default`() {
        assertThat(Defaults.KEYREPEAT_ENABLED).isTrue()
    }

    @Test
    fun `key repeat backspace only disabled by default`() {
        assertThat(Defaults.KEYREPEAT_BACKSPACE_ONLY).isFalse()
    }

    @Test
    fun `double tap lock shift enabled by default`() {
        assertThat(Defaults.DOUBLE_TAP_LOCK_SHIFT).isTrue()
    }

    @Test
    fun `autocapitalisation enabled by default`() {
        assertThat(Defaults.AUTOCAPITALISATION).isTrue()
    }

    @Test
    fun `switch input immediate disabled by default`() {
        assertThat(Defaults.SWITCH_INPUT_IMMEDIATE).isFalse()
    }

    // =========================================================================
    // Smart Punctuation (wiki: typing/smart-punctuation.md)
    // =========================================================================

    @Test
    fun `smart punctuation enabled by default`() {
        assertThat(Defaults.SMART_PUNCTUATION).isTrue()
    }

    @Test
    fun `double space to period enabled by default`() {
        // wiki: "Double Space to Period | Settings > Gesture Tuning | On"
        assertThat(Defaults.DOUBLE_SPACE_TO_PERIOD).isTrue()
    }

    @Test
    fun `double space threshold default is 500ms`() {
        // wiki: "Threshold | Settings > Gesture Tuning | 500ms"
        assertThat(Defaults.DOUBLE_SPACE_THRESHOLD).isEqualTo(500)
    }

    @Test
    fun `tap duration threshold default is 150ms`() {
        assertThat(Defaults.TAP_DURATION_THRESHOLD).isEqualTo(150)
    }

    // =========================================================================
    // Swipe / Gesture Settings (wiki: gestures/short-swipes.md)
    // =========================================================================

    @Test
    fun `swipe dist default is 23`() {
        assertThat(Defaults.SWIPE_DIST).isEqualTo("23")
    }

    @Test
    fun `swipe dist fallback is 23`() {
        assertThat(Defaults.SWIPE_DIST_FALLBACK).isWithin(0.01f).of(23f)
    }

    @Test
    fun `slider sensitivity default is 30`() {
        assertThat(Defaults.SLIDER_SENSITIVITY).isEqualTo("30")
    }

    @Test
    fun `circle sensitivity default is 2`() {
        assertThat(Defaults.CIRCLE_SENSITIVITY).isEqualTo("2")
    }

    @Test
    fun `short gestures enabled by default`() {
        assertThat(Defaults.SHORT_GESTURES_ENABLED).isTrue()
    }

    @Test
    fun `short gesture min distance default is 28`() {
        assertThat(Defaults.SHORT_GESTURE_MIN_DISTANCE).isEqualTo(28)
    }

    @Test
    fun `short gesture max distance default is 141`() {
        assertThat(Defaults.SHORT_GESTURE_MAX_DISTANCE).isEqualTo(141)
    }

    @Test
    fun `short gesture min is less than max`() {
        assertThat(Defaults.SHORT_GESTURE_MIN_DISTANCE)
            .isLessThan(Defaults.SHORT_GESTURE_MAX_DISTANCE)
    }

    @Test
    fun `short gesture min distance is within valid range 10-95`() {
        assertThat(Defaults.SHORT_GESTURE_MIN_DISTANCE).isAtLeast(10)
        assertThat(Defaults.SHORT_GESTURE_MIN_DISTANCE).isAtMost(95)
    }

    @Test
    fun `short gesture max distance is within valid range 50-200`() {
        assertThat(Defaults.SHORT_GESTURE_MAX_DISTANCE).isAtLeast(50)
        assertThat(Defaults.SHORT_GESTURE_MAX_DISTANCE).isAtMost(200)
    }

    // =========================================================================
    // Selection Delete (wiki: gestures/selection-delete.md)
    // =========================================================================

    @Test
    fun `selection delete vertical threshold default is 40 percent`() {
        // wiki: "Vertical Threshold: 40%"
        assertThat(Defaults.SELECTION_DELETE_VERTICAL_THRESHOLD).isEqualTo(40)
    }

    @Test
    fun `selection delete vertical speed default is 0_4x`() {
        // wiki: "Vertical Speed: 0.4x (slower than horizontal)"
        assertThat(Defaults.SELECTION_DELETE_VERTICAL_SPEED).isWithin(0.01f).of(0.4f)
    }

    // =========================================================================
    // Swipe Trail (wiki: typing/swipe-typing.md)
    // =========================================================================

    @Test
    fun `swipe trail enabled by default`() {
        assertThat(Defaults.SWIPE_TRAIL_ENABLED).isTrue()
    }

    @Test
    fun `swipe trail effect default is sparkle`() {
        assertThat(Defaults.SWIPE_TRAIL_EFFECT).isEqualTo("sparkle")
    }

    @Test
    fun `swipe trail width default is 8`() {
        assertThat(Defaults.SWIPE_TRAIL_WIDTH).isWithin(0.01f).of(8.0f)
    }

    @Test
    fun `swipe trail glow radius default is 6`() {
        assertThat(Defaults.SWIPE_TRAIL_GLOW_RADIUS).isWithin(0.01f).of(6.0f)
    }

    // =========================================================================
    // Swipe Detection Thresholds
    // =========================================================================

    @Test
    fun `swipe min distance default is 72`() {
        assertThat(Defaults.SWIPE_MIN_DISTANCE).isWithin(0.01f).of(72f)
    }

    @Test
    fun `swipe min key distance default is 38`() {
        assertThat(Defaults.SWIPE_MIN_KEY_DISTANCE).isWithin(0.01f).of(38f)
    }

    @Test
    fun `swipe min dwell time default is 7`() {
        assertThat(Defaults.SWIPE_MIN_DWELL_TIME).isEqualTo(7)
    }

    @Test
    fun `swipe noise threshold default is 1_26`() {
        assertThat(Defaults.SWIPE_NOISE_THRESHOLD).isWithin(0.01f).of(1.26f)
    }

    @Test
    fun `swipe high velocity threshold default is 1000`() {
        assertThat(Defaults.SWIPE_HIGH_VELOCITY_THRESHOLD).isWithin(0.01f).of(1000f)
    }

    @Test
    fun `finger occlusion offset default is 12_5 percent`() {
        assertThat(Defaults.FINGER_OCCLUSION_OFFSET).isWithin(0.01f).of(12.5f)
    }

    @Test
    fun `slider speed smoothing default is 0_6`() {
        assertThat(Defaults.SLIDER_SPEED_SMOOTHING).isWithin(0.01f).of(0.6f)
    }

    @Test
    fun `slider speed max default is 6`() {
        assertThat(Defaults.SLIDER_SPEED_MAX).isWithin(0.01f).of(6.0f)
    }

    // =========================================================================
    // Neural Prediction (wiki: settings/neural-settings.md)
    // =========================================================================

    @Test
    fun `neural beam width default is 6`() {
        assertThat(Defaults.NEURAL_BEAM_WIDTH).isEqualTo(6)
    }

    @Test
    fun `neural max length default is 20`() {
        // wiki: "Max Word Length - Maximum predicted word length (default: 20)"
        assertThat(Defaults.NEURAL_MAX_LENGTH).isEqualTo(20)
    }

    @Test
    fun `neural confidence threshold default is 0_01`() {
        // wiki: "Confidence Threshold (0.01-0.5)"
        assertThat(Defaults.NEURAL_CONFIDENCE_THRESHOLD).isWithin(0.001f).of(0.01f)
    }

    @Test
    fun `neural batch beams disabled by default`() {
        assertThat(Defaults.NEURAL_BATCH_BEAMS).isFalse()
    }

    @Test
    fun `neural greedy search disabled by default`() {
        assertThat(Defaults.NEURAL_GREEDY_SEARCH).isFalse()
    }

    @Test
    fun `neural beam alpha default is 1_0`() {
        assertThat(Defaults.NEURAL_BEAM_ALPHA).isWithin(0.01f).of(1.0f)
    }

    @Test
    fun `neural beam prune confidence default is 0_8`() {
        assertThat(Defaults.NEURAL_BEAM_PRUNE_CONFIDENCE).isWithin(0.01f).of(0.8f)
    }

    @Test
    fun `neural beam score gap default is 80`() {
        assertThat(Defaults.NEURAL_BEAM_SCORE_GAP).isWithin(0.01f).of(80.0f)
    }

    @Test
    fun `neural adaptive width step default is 12`() {
        assertThat(Defaults.NEURAL_ADAPTIVE_WIDTH_STEP).isEqualTo(12)
    }

    @Test
    fun `neural score gap step default is 12`() {
        assertThat(Defaults.NEURAL_SCORE_GAP_STEP).isEqualTo(12)
    }

    @Test
    fun `neural temperature default is 1_0`() {
        assertThat(Defaults.NEURAL_TEMPERATURE).isWithin(0.01f).of(1.0f)
    }

    @Test
    fun `neural frequency weight default is 0_57`() {
        assertThat(Defaults.NEURAL_FREQUENCY_WEIGHT).isWithin(0.01f).of(0.57f)
    }

    @Test
    fun `swipe smoothing window default is 3`() {
        assertThat(Defaults.SWIPE_SMOOTHING_WINDOW).isEqualTo(3)
    }

    // =========================================================================
    // Neural Prefix Boost
    // =========================================================================

    @Test
    fun `prefix boost multiplier default is 1_0`() {
        assertThat(Defaults.NEURAL_PREFIX_BOOST_MULTIPLIER).isWithin(0.01f).of(1.0f)
    }

    @Test
    fun `prefix boost max default is 5_0`() {
        assertThat(Defaults.NEURAL_PREFIX_BOOST_MAX).isWithin(0.01f).of(5.0f)
    }

    @Test
    fun `max cumulative boost default is 15_0`() {
        assertThat(Defaults.NEURAL_MAX_CUMULATIVE_BOOST).isWithin(0.01f).of(15.0f)
    }

    @Test
    fun `strict start char disabled by default`() {
        assertThat(Defaults.NEURAL_STRICT_START_CHAR).isFalse()
    }

    @Test
    fun `neural resampling mode default is discard`() {
        assertThat(Defaults.NEURAL_RESAMPLING_MODE).isEqualTo("discard")
    }

    @Test
    fun `neural user max seq length default is 0`() {
        // 0 means use model default
        assertThat(Defaults.NEURAL_USER_MAX_SEQ_LENGTH).isEqualTo(0)
    }

    // =========================================================================
    // Swipe Typing (wiki: typing/swipe-typing.md, FAQ.md)
    // =========================================================================

    @Test
    fun `swipe typing enabled by default`() {
        assertThat(Defaults.SWIPE_TYPING_ENABLED).isTrue()
    }

    @Test
    fun `swipe on password fields disabled by default`() {
        // wiki: "Swipe on Password Fields | Off"
        assertThat(Defaults.SWIPE_ON_PASSWORD_FIELDS).isFalse()
    }

    @Test
    fun `word prediction enabled by default`() {
        assertThat(Defaults.WORD_PREDICTION_ENABLED).isTrue()
    }

    @Test
    fun `suggestion bar opacity default is 80`() {
        assertThat(Defaults.SUGGESTION_BAR_OPACITY).isEqualTo(80)
    }

    @Test
    fun `show exact typed word enabled by default`() {
        assertThat(Defaults.SHOW_EXACT_TYPED_WORD).isTrue()
    }

    @Test
    fun `context aware predictions enabled by default`() {
        assertThat(Defaults.CONTEXT_AWARE_PREDICTIONS_ENABLED).isTrue()
    }

    @Test
    fun `personalized learning enabled by default`() {
        // wiki: user-dictionary.md personalized learning
        assertThat(Defaults.PERSONALIZED_LEARNING_ENABLED).isTrue()
    }

    @Test
    fun `learning aggression default is BALANCED`() {
        assertThat(Defaults.LEARNING_AGGRESSION).isEqualTo("BALANCED")
    }

    @Test
    fun `prediction context boost default is 0_5`() {
        assertThat(Defaults.PREDICTION_CONTEXT_BOOST).isWithin(0.01f).of(0.5f)
    }

    @Test
    fun `prediction frequency scale default is 100`() {
        assertThat(Defaults.PREDICTION_FREQUENCY_SCALE).isWithin(0.01f).of(100.0f)
    }

    // =========================================================================
    // Autocorrect (wiki: typing/autocorrect.md)
    // =========================================================================

    @Test
    fun `autocorrect enabled by default`() {
        assertThat(Defaults.AUTOCORRECT_ENABLED).isTrue()
    }

    @Test
    fun `autocorrect min word length default is 3`() {
        assertThat(Defaults.AUTOCORRECT_MIN_WORD_LENGTH).isEqualTo(3)
    }

    @Test
    fun `autocorrect char match threshold default is 0_67`() {
        assertThat(Defaults.AUTOCORRECT_CHAR_MATCH_THRESHOLD).isWithin(0.01f).of(0.67f)
    }

    @Test
    fun `autocorrect min frequency default is 100`() {
        assertThat(Defaults.AUTOCORRECT_MIN_FREQUENCY).isEqualTo(100)
    }

    @Test
    fun `autocorrect max length diff default is 2`() {
        assertThat(Defaults.AUTOCORRECT_MAX_LENGTH_DIFF).isEqualTo(2)
    }

    @Test
    fun `autocorrect prefix length default is 1`() {
        assertThat(Defaults.AUTOCORRECT_PREFIX_LENGTH).isEqualTo(1)
    }

    @Test
    fun `autocorrect max beam candidates default is 3`() {
        assertThat(Defaults.AUTOCORRECT_MAX_BEAM_CANDIDATES).isEqualTo(3)
    }

    @Test
    fun `swipe beam autocorrect enabled by default`() {
        assertThat(Defaults.SWIPE_BEAM_AUTOCORRECT_ENABLED).isTrue()
    }

    @Test
    fun `swipe final autocorrect enabled by default`() {
        assertThat(Defaults.SWIPE_FINAL_AUTOCORRECT_ENABLED).isTrue()
    }

    @Test
    fun `autocapitalize I words enabled by default`() {
        // wiki: input-behavior.md "Capitalize I Words"
        assertThat(Defaults.AUTOCAPITALIZE_I_WORDS).isTrue()
    }

    @Test
    fun `swipe fuzzy match mode default is edit_distance`() {
        assertThat(Defaults.SWIPE_FUZZY_MATCH_MODE).isEqualTo("edit_distance")
    }

    @Test
    fun `swipe prediction source default is 80`() {
        assertThat(Defaults.SWIPE_PREDICTION_SOURCE).isEqualTo(80)
    }

    @Test
    fun `swipe common words boost default is 1_0`() {
        assertThat(Defaults.SWIPE_COMMON_WORDS_BOOST).isWithin(0.01f).of(1.0f)
    }

    @Test
    fun `swipe top5000 boost default is 1_0`() {
        assertThat(Defaults.SWIPE_TOP5000_BOOST).isWithin(0.01f).of(1.0f)
    }

    @Test
    fun `swipe rare words penalty default is 1_0`() {
        assertThat(Defaults.SWIPE_RARE_WORDS_PENALTY).isWithin(0.01f).of(1.0f)
    }

    // =========================================================================
    // Clipboard (wiki: clipboard/clipboard-history.md, settings/privacy.md)
    // =========================================================================

    @Test
    fun `clipboard history enabled by default`() {
        // wiki: "Clipboard History | On"
        assertThat(Defaults.CLIPBOARD_HISTORY_ENABLED).isTrue()
    }

    @Test
    fun `clipboard history limit default is 50`() {
        // wiki: "History Size | 50 items"
        assertThat(Defaults.CLIPBOARD_HISTORY_LIMIT).isEqualTo("50")
        assertThat(Defaults.CLIPBOARD_HISTORY_LIMIT_FALLBACK).isEqualTo(50)
    }

    @Test
    fun `clipboard pane height percent default is 30`() {
        assertThat(Defaults.CLIPBOARD_PANE_HEIGHT_PERCENT).isEqualTo(30)
    }

    @Test
    fun `clipboard max item size default is 256 KB`() {
        assertThat(Defaults.CLIPBOARD_MAX_ITEM_SIZE_KB).isEqualTo("256")
        assertThat(Defaults.CLIPBOARD_MAX_ITEM_SIZE_KB_FALLBACK).isEqualTo(256)
    }

    @Test
    fun `clipboard limit type default is count`() {
        assertThat(Defaults.CLIPBOARD_LIMIT_TYPE).isEqualTo("count")
    }

    @Test
    fun `clipboard size limit default is 5 MB`() {
        assertThat(Defaults.CLIPBOARD_SIZE_LIMIT_MB).isEqualTo("5")
        assertThat(Defaults.CLIPBOARD_SIZE_LIMIT_MB_FALLBACK).isEqualTo(5)
    }

    @Test
    fun `clipboard exclude password managers enabled by default`() {
        // wiki: "Exclude Password Managers | On"
        assertThat(Defaults.CLIPBOARD_EXCLUDE_PASSWORD_MANAGERS).isTrue()
    }

    @Test
    fun `clipboard respect sensitive flag enabled by default`() {
        // wiki: "Respect Sensitive Flag | On"
        assertThat(Defaults.CLIPBOARD_RESPECT_SENSITIVE_FLAG).isTrue()
    }

    // =========================================================================
    // Multi-Language (wiki: layouts/multi-language.md)
    // =========================================================================

    @Test
    fun `multilang disabled by default`() {
        assertThat(Defaults.ENABLE_MULTILANG).isFalse()
    }

    @Test
    fun `primary language default is en`() {
        assertThat(Defaults.PRIMARY_LANGUAGE).isEqualTo("en")
    }

    @Test
    fun `auto detect language enabled by default`() {
        assertThat(Defaults.AUTO_DETECT_LANGUAGE).isTrue()
    }

    @Test
    fun `language detection sensitivity default is 0_6`() {
        // wiki: "Detection sensitivity (0.4-0.9)"
        assertThat(Defaults.LANGUAGE_DETECTION_SENSITIVITY).isWithin(0.01f).of(0.6f)
    }

    @Test
    fun `secondary prediction weight default is 0_9`() {
        assertThat(Defaults.SECONDARY_PREDICTION_WEIGHT).isWithin(0.01f).of(0.9f)
    }

    // =========================================================================
    // ONNX Runtime (wiki: settings/neural-settings.md)
    // =========================================================================

    @Test
    fun `XNNPACK threads default is 2`() {
        // wiki: "ONNX Threads - Default: 2 (optimal for most ARM devices)"
        assertThat(Defaults.ONNX_XNNPACK_THREADS).isEqualTo(2)
    }

    @Test
    fun `XNNPACK threads default is within valid range 1-8`() {
        assertThat(Defaults.ONNX_XNNPACK_THREADS).isAtLeast(1)
        assertThat(Defaults.ONNX_XNNPACK_THREADS).isAtMost(8)
    }

    // =========================================================================
    // Debug Settings (should be off by default)
    // =========================================================================

    @Test
    fun `debug disabled by default`() {
        assertThat(Defaults.DEBUG_ENABLED).isFalse()
    }

    @Test
    fun `debug show scores disabled by default`() {
        assertThat(Defaults.SWIPE_SHOW_DEBUG_SCORES).isFalse()
    }

    @Test
    fun `debug detailed logging disabled by default`() {
        assertThat(Defaults.SWIPE_DEBUG_DETAILED_LOGGING).isFalse()
    }

    @Test
    fun `debug show raw output disabled by default`() {
        assertThat(Defaults.SWIPE_DEBUG_SHOW_RAW_OUTPUT).isFalse()
    }

    @Test
    fun `debug show raw beam predictions disabled by default`() {
        assertThat(Defaults.SWIPE_SHOW_RAW_BEAM_PREDICTIONS).isFalse()
    }

    // =========================================================================
    // Privacy (wiki: settings/privacy.md, FAQ.md)
    // =========================================================================

    @Test
    fun `privacy collect swipe disabled by default`() {
        assertThat(Defaults.PRIVACY_COLLECT_SWIPE).isFalse()
    }

    @Test
    fun `privacy collect performance disabled by default`() {
        assertThat(Defaults.PRIVACY_COLLECT_PERFORMANCE).isFalse()
    }

    @Test
    fun `privacy collect errors disabled by default`() {
        assertThat(Defaults.PRIVACY_COLLECT_ERRORS).isFalse()
    }

    // =========================================================================
    // Accessibility (wiki: settings/accessibility.md)
    // =========================================================================

    @Test
    fun `auto space after suggestion enabled by default`() {
        // wiki: input-behavior.md "Auto-Space After Suggestion"
        assertThat(Defaults.AUTO_SPACE_AFTER_SUGGESTION).isTrue()
    }

    @Test
    fun `sticky keys disabled by default`() {
        assertThat(Defaults.STICKY_KEYS_ENABLED).isFalse()
    }

    @Test
    fun `sticky keys timeout default is 5000ms`() {
        assertThat(Defaults.STICKY_KEYS_TIMEOUT).isEqualTo(5000)
    }

    @Test
    fun `voice guidance disabled by default`() {
        assertThat(Defaults.VOICE_GUIDANCE_ENABLED).isFalse()
    }

    // =========================================================================
    // Cross-Validation: Range/Consistency Checks
    // =========================================================================

    @Test
    fun `all neural defaults have valid values for backup restore`() {
        assertThat(Defaults.NEURAL_BEAM_WIDTH).isAtLeast(1)
        assertThat(Defaults.NEURAL_BEAM_WIDTH).isAtMost(20)
        assertThat(Defaults.NEURAL_MAX_LENGTH).isAtLeast(5)
        assertThat(Defaults.NEURAL_MAX_LENGTH).isAtMost(50)
        assertThat(Defaults.NEURAL_CONFIDENCE_THRESHOLD).isAtLeast(0f)
        assertThat(Defaults.NEURAL_CONFIDENCE_THRESHOLD).isAtMost(1f)
    }

    @Test
    fun `keyboard heights are reasonable percentages`() {
        assertThat(Defaults.KEYBOARD_HEIGHT_PORTRAIT).isAtLeast(15)
        assertThat(Defaults.KEYBOARD_HEIGHT_PORTRAIT).isAtMost(70)
        assertThat(Defaults.KEYBOARD_HEIGHT_LANDSCAPE).isAtLeast(15)
        assertThat(Defaults.KEYBOARD_HEIGHT_LANDSCAPE).isAtMost(70)
    }

    @Test
    fun `haptic duration is reasonable`() {
        assertThat(Defaults.VIBRATE_DURATION).isAtLeast(1)
        assertThat(Defaults.VIBRATE_DURATION).isAtMost(100)
    }

    @Test
    fun `longpress timeout is reasonable`() {
        assertThat(Defaults.LONGPRESS_TIMEOUT).isAtLeast(100)
        assertThat(Defaults.LONGPRESS_TIMEOUT).isAtMost(2000)
    }

    @Test
    fun `double space threshold is reasonable`() {
        assertThat(Defaults.DOUBLE_SPACE_THRESHOLD).isAtLeast(100)
        assertThat(Defaults.DOUBLE_SPACE_THRESHOLD).isAtMost(2000)
    }

    @Test
    fun `language detection sensitivity in valid range`() {
        assertThat(Defaults.LANGUAGE_DETECTION_SENSITIVITY).isAtLeast(0.1f)
        assertThat(Defaults.LANGUAGE_DETECTION_SENSITIVITY).isAtMost(1.0f)
    }

    @Test
    fun `neural temperature is positive`() {
        assertThat(Defaults.NEURAL_TEMPERATURE).isGreaterThan(0f)
    }

    @Test
    fun `clipboard history limit is positive`() {
        assertThat(Defaults.CLIPBOARD_HISTORY_LIMIT_FALLBACK).isGreaterThan(0)
    }

    @Test
    fun `selection delete vertical speed is positive fraction`() {
        assertThat(Defaults.SELECTION_DELETE_VERTICAL_SPEED).isGreaterThan(0f)
        assertThat(Defaults.SELECTION_DELETE_VERTICAL_SPEED).isAtMost(1.0f)
    }

    @Test
    fun `autocorrect thresholds are consistent`() {
        // min word length should be reasonable
        assertThat(Defaults.AUTOCORRECT_MIN_WORD_LENGTH).isAtLeast(1)
        assertThat(Defaults.AUTOCORRECT_MIN_WORD_LENGTH).isAtMost(10)
        // char match threshold should be a fraction
        assertThat(Defaults.AUTOCORRECT_CHAR_MATCH_THRESHOLD).isGreaterThan(0f)
        assertThat(Defaults.AUTOCORRECT_CHAR_MATCH_THRESHOLD).isAtMost(1f)
    }

    @Test
    fun `swipe trail dimensions are positive`() {
        assertThat(Defaults.SWIPE_TRAIL_WIDTH).isGreaterThan(0f)
        assertThat(Defaults.SWIPE_TRAIL_GLOW_RADIUS).isGreaterThan(0f)
    }

    @Test
    fun `termux mode enabled by default`() {
        assertThat(Defaults.TERMUX_MODE_ENABLED).isTrue()
    }
}
