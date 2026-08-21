package tribixbite.cleverkeys.ui.settings

import android.content.Intent
import android.content.SharedPreferences
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.launch
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.Defaults
import tribixbite.cleverkeys.Logs
import tribixbite.cleverkeys.PrivateCopyProcessTextActivity
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.ui.settings.sections.setPrivateCopyToolbarComponentEnabled
import tribixbite.cleverkeys.ui.settings.io.detectAvailableV2Dictionaries
import tribixbite.cleverkeys.ui.settings.io.recomputeCustomRulesStatus
import tribixbite.cleverkeys.ui.settings.io.refreshInstalledGifPacks
import tribixbite.cleverkeys.ui.settings.io.refreshInstalledLanguagePacks

/**
 * Handles the body of [SettingsActivity.onSharedPreferenceChanged].
 * The override in [SettingsActivity] delegates here with identical parameters.
 */
internal fun SettingsActivity.handlePreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        // Handle preference changes for reactive updates
        when (key) {
            "swipe_typing_enabled" -> {
                swipeTypingEnabled = prefs.getBoolean(key, Defaults.SWIPE_TYPING_ENABLED)
            }
            "swipe_engine_mode" -> {
                // L1: case-canonical at read, matching Config.refresh — an imported "CTC"
                // must select the same dropdown row/knob visibility as "ctc".
                swipeEngineMode = prefs.getSafeString(key, Defaults.SWIPE_ENGINE_MODE)
                    .lowercase(java.util.Locale.ROOT)
            }
            "keyboard_height" -> {
                keyboardHeight = prefs.getInt(key, Defaults.KEYBOARD_HEIGHT_PORTRAIT)
            }
            "vibration_enabled" -> {
                vibrationEnabled = prefs.getBoolean(key, Defaults.HAPTIC_ENABLED)
            }
            "debug_enabled" -> {
                debugEnabled = prefs.getBoolean(key, Defaults.DEBUG_ENABLED)
                Logs.setDebugEnabled(debugEnabled)
            }
            "clipboard_history_enabled" -> {
                clipboardHistoryEnabled = prefs.getBoolean(key, Defaults.CLIPBOARD_HISTORY_ENABLED)
            }
            "gif_enabled" -> {
                gifEnabled = prefs.getBoolean(key, Defaults.GIF_ENABLED)
            }
            "autocapitalisation" -> {
                autoCapitalizationEnabled = prefs.getBoolean(key, Defaults.AUTOCAPITALISATION)
            }
            "autocapitalize_i_words" -> {
                capitalizeIWords = prefs.getBoolean(key, Defaults.AUTOCAPITALIZE_I_WORDS)
            }
            // Adaptive layout settings
            "keyboard_height_landscape" -> {
                keyboardHeightLandscape = prefs.getInt(key, Defaults.KEYBOARD_HEIGHT_LANDSCAPE)
            }
            "margin_bottom_portrait" -> {
                marginBottomPortrait = prefs.getInt(key, Defaults.MARGIN_BOTTOM_PORTRAIT)
            }
            "margin_bottom_landscape" -> {
                marginBottomLandscape = prefs.getInt(key, Defaults.MARGIN_BOTTOM_LANDSCAPE)
            }
            "margin_left_portrait" -> {
                marginLeftPortrait = prefs.getInt(key, Defaults.MARGIN_LEFT_PORTRAIT)
            }
            "margin_left_landscape" -> {
                marginLeftLandscape = prefs.getInt(key, Defaults.MARGIN_LEFT_LANDSCAPE)
            }
            "margin_right_portrait" -> {
                marginRightPortrait = prefs.getInt(key, Defaults.MARGIN_RIGHT_PORTRAIT)
            }
            "margin_right_landscape" -> {
                marginRightLandscape = prefs.getInt(key, Defaults.MARGIN_RIGHT_LANDSCAPE)
            }
            // Gesture sensitivity settings
            "swipe_dist" -> {
                swipeDistance = prefs.getSafeString(key, Defaults.SWIPE_DIST).toIntOrNull() ?: Defaults.SWIPE_DIST_FALLBACK.toInt()
            }
            "circle_sensitivity" -> {
                circleSensitivity = prefs.getSafeString(key, Defaults.CIRCLE_SENSITIVITY).toIntOrNull() ?: Defaults.CIRCLE_SENSITIVITY_FALLBACK
            }
            // Long press settings
            "longpress_timeout" -> {
                longPressTimeout = prefs.getInt(key, Defaults.LONGPRESS_TIMEOUT)
            }
            "longpress_interval" -> {
                longPressInterval = prefs.getInt(key, Defaults.LONGPRESS_INTERVAL)
            }
            "keyrepeat_enabled" -> {
                keyRepeatEnabled = prefs.getBoolean(key, Defaults.KEYREPEAT_ENABLED)
            }
            // Visual customization settings
            "label_brightness" -> {
                labelBrightness = prefs.getInt(key, Defaults.LABEL_BRIGHTNESS)
            }
            "keyboard_opacity" -> {
                keyboardOpacity = prefs.getInt(key, Defaults.KEYBOARD_OPACITY)
            }
            "key_opacity" -> {
                keyOpacity = prefs.getInt(key, Defaults.KEY_OPACITY)
            }
            "key_activated_opacity" -> {
                keyActivatedOpacity = prefs.getInt(key, Defaults.KEY_ACTIVATED_OPACITY)
            }
            // Spacing and sizing settings
            "character_size" -> {
                characterSize = (prefs.getFloat(key, Defaults.CHARACTER_SIZE) * 100).toInt()
            }
            "secondary_label_size_scale" -> {
                secondaryLabelSizeScale = (prefs.getFloat(key, Defaults.SECONDARY_LABEL_SIZE_SCALE) * 100).toInt()
            }
            "key_vertical_margin" -> {
                keyVerticalMargin = (prefs.getFloat(key, Defaults.KEY_VERTICAL_MARGIN) * 100).toInt()
            }
            "key_horizontal_margin" -> {
                keyHorizontalMargin = (prefs.getFloat(key, Defaults.KEY_HORIZONTAL_MARGIN) * 100).toInt()
            }
            // Border customization settings
            "border_config" -> {
                borderConfigEnabled = prefs.getBoolean(key, Defaults.BORDER_CONFIG)
            }
            "custom_border_radius" -> {
                customBorderRadius = prefs.getInt(key, Defaults.CUSTOM_BORDER_RADIUS)
            }
            "custom_border_line_width" -> {
                customBorderLineWidth = prefs.getInt(key, Defaults.CUSTOM_BORDER_LINE_WIDTH)
            }
            // Behavior settings
            "lock_double_tap" -> {
                doubleTapLockShift = prefs.getBoolean(key, Defaults.DOUBLE_TAP_LOCK_SHIFT)
            }
            "switch_input_immediate" -> {
                switchInputImmediate = prefs.getBoolean(key, Defaults.SWITCH_INPUT_IMMEDIATE)
            }
            // Number row and numpad settings
            "number_row" -> {
                numberRowMode = prefs.getSafeString(key, Defaults.NUMBER_ROW)
            }
            "show_numpad" -> {
                showNumpadMode = prefs.getSafeString(key, Defaults.SHOW_NUMPAD)
            }
            "numpad_layout" -> {
                numpadLayout = prefs.getSafeString(key, Defaults.NUMPAD_LAYOUT)
            }
            "pin_entry_enabled" -> {
                pinEntryEnabled = prefs.getBoolean(key, false)
            }
            // Phase 1: Exposed Config.kt settings listeners
            "word_prediction_enabled" -> {
                wordPredictionEnabled = prefs.getBoolean(key, Defaults.WORD_PREDICTION_ENABLED)
            }
            "suggestion_bar_opacity" -> {
                suggestionBarOpacity = Config.safeGetInt(prefs, key, Defaults.SUGGESTION_BAR_OPACITY)
            }
            "autocorrect_enabled" -> {
                autoCorrectEnabled = prefs.getBoolean(key, Defaults.AUTOCORRECT_ENABLED)
            }
            "termux_mode_enabled" -> {
                termuxModeEnabled = prefs.getBoolean(key, Defaults.TERMUX_MODE_ENABLED)
            }
            "vibrate_duration" -> {
                vibrationDuration = prefs.getInt(key, Defaults.VIBRATE_DURATION)
            }
            "swipe_show_debug_scores" -> {
                swipeDebugEnabled = prefs.getBoolean(key, Defaults.SWIPE_SHOW_DEBUG_SCORES)
            }
            // Phase 5: Gesture settings listeners
            "slider_sensitivity" -> {
                sliderSensitivity = prefs.getSafeString(key, Defaults.SLIDER_SENSITIVITY).toIntOrNull() ?: 30
            }
            "swipe_final_autocorrect_enabled" -> {
                swipeFinalAutocorrectEnabled = prefs.getBoolean(key, Defaults.SWIPE_FINAL_AUTOCORRECT_ENABLED)
            }
            "swipe_correction_preset" -> {
                swipeCorrectionPreset = prefs.getSafeString(key, "balanced")
            }
            "swipe_fuzzy_match_mode" -> {
                swipeFuzzyMatchMode = prefs.getSafeString(key, Defaults.SWIPE_FUZZY_MATCH_MODE)
            }
            "autocorrect_max_length_diff" -> {
                autocorrectMaxLengthDiff = Config.safeGetInt(prefs, key, Defaults.AUTOCORRECT_MAX_LENGTH_DIFF)
            }
            "autocorrect_prefix_length" -> {
                autocorrectPrefixLength = Config.safeGetInt(prefs, key, Defaults.AUTOCORRECT_PREFIX_LENGTH)
            }
            "autocorrect_max_beam_candidates" -> {
                autocorrectMaxBeamCandidates = Config.safeGetInt(prefs, key, Defaults.AUTOCORRECT_MAX_BEAM_CANDIDATES)
            }
            "swipe_prediction_source" -> {
                swipePredictionSource = Config.safeGetInt(prefs, key, Defaults.SWIPE_PREDICTION_SOURCE)
            }
            "swipe_common_words_boost" -> {
                swipeCommonWordsBoost = Config.safeGetFloat(prefs, key, Defaults.SWIPE_COMMON_WORDS_BOOST)
            }
            "swipe_top5000_boost" -> {
                swipeTop5000Boost = Config.safeGetFloat(prefs, key, Defaults.SWIPE_TOP5000_BOOST)
            }
            "swipe_rare_words_penalty" -> {
                swipeRareWordsPenalty = Config.safeGetFloat(prefs, key, Defaults.SWIPE_RARE_WORDS_PENALTY)
            }
        }
}

internal fun SettingsActivity.loadCurrentSettings() {
        // Swipe typing master switch
        swipeTypingEnabled = prefs.getSafeBoolean("swipe_typing_enabled", Defaults.SWIPE_TYPING_ENABLED)
        swipeOnPasswordFields = prefs.getSafeBoolean("swipe_on_password_fields", Defaults.SWIPE_ON_PASSWORD_FIELDS)

        // Swipe engine settings (swipe_engine_mode case-canonicalized — L1, see
        // handlePreferenceChanged). The #9 "does this layout support swipe" state was
        // removed with the neural engine (2026-08-18): every layout swipes now.
        swipeEngineMode = prefs.getSafeString("swipe_engine_mode", Defaults.SWIPE_ENGINE_MODE)
            .lowercase(java.util.Locale.ROOT)
        swipeSmoothingWindow =
            Config.safeGetInt(prefs, "swipe_smoothing_window", Defaults.SWIPE_SMOOTHING_WINDOW)
        fingerOcclusionOffset =
            Config.safeGetInt(prefs, "finger_occlusion_offset", Defaults.FINGER_OCCLUSION_OFFSET)
        swipeContextRescoring =
            prefs.getBoolean("swipe_context_rescoring", Defaults.SWIPE_CONTEXT_RESCORING)

        // Appearance settings
        keyboardHeight = prefs.getSafeInt("keyboard_height", Defaults.KEYBOARD_HEIGHT_PORTRAIT)
        keyboardHeightLandscape = prefs.getSafeInt("keyboard_height_landscape", Defaults.KEYBOARD_HEIGHT_LANDSCAPE)

        // Adaptive layout settings (percentages)
        marginBottomPortrait = prefs.getSafeInt("margin_bottom_portrait", Defaults.MARGIN_BOTTOM_PORTRAIT)
        marginBottomLandscape = prefs.getSafeInt("margin_bottom_landscape", Defaults.MARGIN_BOTTOM_LANDSCAPE)
        marginLeftPortrait = prefs.getSafeInt("margin_left_portrait", Defaults.MARGIN_LEFT_PORTRAIT)
        marginLeftLandscape = prefs.getSafeInt("margin_left_landscape", Defaults.MARGIN_LEFT_LANDSCAPE)
        marginRightPortrait = prefs.getSafeInt("margin_right_portrait", Defaults.MARGIN_RIGHT_PORTRAIT)
        marginRightLandscape = prefs.getSafeInt("margin_right_landscape", Defaults.MARGIN_RIGHT_LANDSCAPE)

        // Visual customization settings
        labelBrightness = prefs.getSafeInt("label_brightness", Defaults.LABEL_BRIGHTNESS)
        keyboardOpacity = prefs.getSafeInt("keyboard_opacity", Defaults.KEYBOARD_OPACITY)
        keyOpacity = prefs.getSafeInt("key_opacity", Defaults.KEY_OPACITY)
        keyActivatedOpacity = prefs.getSafeInt("key_activated_opacity", Defaults.KEY_ACTIVATED_OPACITY)

        // Spacing and sizing settings
        characterSize = (prefs.getSafeFloat("character_size", Defaults.CHARACTER_SIZE) * 100).toInt()
        secondaryLabelSizeScale = (prefs.getSafeFloat("secondary_label_size_scale", Defaults.SECONDARY_LABEL_SIZE_SCALE) * 100).toInt()
        keyVerticalMargin = (prefs.getSafeFloat("key_vertical_margin", Defaults.KEY_VERTICAL_MARGIN) * 100).toInt()
        keyHorizontalMargin = (prefs.getSafeFloat("key_horizontal_margin", Defaults.KEY_HORIZONTAL_MARGIN) * 100).toInt()

        // Border customization settings
        borderConfigEnabled = prefs.getSafeBoolean("border_config", Defaults.BORDER_CONFIG)
        customBorderRadius = prefs.getSafeInt("custom_border_radius", Defaults.CUSTOM_BORDER_RADIUS)
        customBorderLineWidth = prefs.getSafeInt("custom_border_line_width", Defaults.CUSTOM_BORDER_LINE_WIDTH)

        // Input behavior settings
        vibrationEnabled = prefs.getSafeBoolean("vibration_enabled", Defaults.HAPTIC_ENABLED)
        clipboardHistoryEnabled = prefs.getSafeBoolean("clipboard_history_enabled", Defaults.CLIPBOARD_HISTORY_ENABLED)
        clipboardHistoryLimit = prefs.getSafeString("clipboard_history_limit", Defaults.CLIPBOARD_HISTORY_LIMIT).toIntOrNull() ?: Defaults.CLIPBOARD_HISTORY_LIMIT_FALLBACK
        clipboardHistoryDuration = prefs.getSafeString("clipboard_history_duration", Defaults.CLIPBOARD_HISTORY_DURATION).toIntOrNull() ?: Defaults.CLIPBOARD_HISTORY_DURATION_FALLBACK
        clipboardPaneHeightPercent = Config.safeGetInt(prefs, "clipboard_pane_height_percent", Defaults.CLIPBOARD_PANE_HEIGHT_PERCENT).coerceIn(10, 50)
        clipboardMaxItemSizeKb = (prefs.getSafeString("clipboard_max_item_size_kb", Defaults.CLIPBOARD_MAX_ITEM_SIZE_KB).toIntOrNull() ?: Defaults.CLIPBOARD_MAX_ITEM_SIZE_KB_FALLBACK).coerceIn(64, 1024)
        // Migrate stale values exceeding Binder limit (was 5000KB max, now 1024KB)
        if (clipboardMaxItemSizeKb < (prefs.getSafeString("clipboard_max_item_size_kb", "0").toIntOrNull() ?: 0)) {
            saveSetting("clipboard_max_item_size_kb", clipboardMaxItemSizeKb)
        }
        clipboardLimitType = prefs.getSafeString("clipboard_limit_type", Defaults.CLIPBOARD_LIMIT_TYPE)
        clipboardSizeLimitMb = prefs.getSafeString("clipboard_size_limit_mb", Defaults.CLIPBOARD_SIZE_LIMIT_MB).toIntOrNull() ?: Defaults.CLIPBOARD_SIZE_LIMIT_MB_FALLBACK
        clipboardExcludePasswordManagers = prefs.getSafeBoolean("clipboard_exclude_password_managers", Defaults.CLIPBOARD_EXCLUDE_PASSWORD_MANAGERS)
        clipboardRespectSensitiveFlag = prefs.getSafeBoolean("clipboard_respect_sensitive_flag", Defaults.CLIPBOARD_RESPECT_SENSITIVE_FLAG)
        clipboardTextOnly = prefs.getSafeBoolean("clipboard_text_only", false)
        clipboardPinnedEnabled = prefs.getSafeBoolean("clipboard_pinned_enabled", true)
        clipboardTodoEnabled = prefs.getSafeBoolean("clipboard_todo_enabled", true)
        // #156: PROCESS_TEXT selection-toolbar entry point — opt-in, default false (design §6.6).
        clipboardPrivateCopyToolbarEnabled = prefs.getSafeBoolean(PrivateCopyProcessTextActivity.PREF_TOOLBAR_ENABLED, false)
        // Reconcile the OS component-enabled state with the persisted pref (e.g. after a restore or
        // reinstall the manifest default is disabled, but the pref may say the user opted in).
        setPrivateCopyToolbarComponentEnabled(clipboardPrivateCopyToolbarEnabled)

        // URL sanitization toggles (Chunk 4) — defaults match Config.kt (all off)
        clipboardSanitizeLinksEnabled = prefs.getSafeBoolean("clipboard_sanitize_links_enabled", false)
        clipboardEmbedEnrichEnabled = prefs.getSafeBoolean("clipboard_embed_enrich_enabled", false)
        clipboardCustomRulesEnabled = prefs.getSafeBoolean("clipboard_custom_rules_enabled", false)
        clipboardSanitizeSystemClipboard = prefs.getSafeBoolean("clipboard_sanitize_system_clipboard", true)
        clipboardCustomRulesUri = prefs.getString("clipboard_custom_rules_uri", null)
        // Status text reflects the on-disk copy of custom.substitutions.json (computed lazily).
        recomputeCustomRulesStatus()

        // GIF Panel
        gifEnabled = prefs.getSafeBoolean("gif_enabled", Defaults.GIF_ENABLED)
        gifThumbnailColumns = Config.safeGetInt(prefs, "gif_thumbnail_columns", Defaults.GIF_THUMBNAIL_COLUMNS).coerceIn(2, 5)
        refreshInstalledGifPacks()

        autoCapitalizationEnabled = prefs.getSafeBoolean("autocapitalisation", Defaults.AUTOCAPITALISATION)
        capitalizeIWords = prefs.getSafeBoolean("autocapitalize_i_words", Defaults.AUTOCAPITALIZE_I_WORDS)

        // Gesture sensitivity settings
        swipeDistance = prefs.getSafeString("swipe_dist", Defaults.SWIPE_DIST).toIntOrNull() ?: Defaults.SWIPE_DIST_FALLBACK.toInt()
        circleSensitivity = prefs.getSafeString("circle_sensitivity", Defaults.CIRCLE_SENSITIVITY).toIntOrNull() ?: Defaults.CIRCLE_SENSITIVITY_FALLBACK
        sliderSensitivity = prefs.getSafeString("slider_sensitivity", Defaults.SLIDER_SENSITIVITY).toIntOrNull() ?: 30

        // Long press settings
        longPressTimeout = prefs.getSafeInt("longpress_timeout", Defaults.LONGPRESS_TIMEOUT)
        longPressInterval = prefs.getSafeInt("longpress_interval", Defaults.LONGPRESS_INTERVAL)
        keyRepeatEnabled = prefs.getSafeBoolean("keyrepeat_enabled", Defaults.KEYREPEAT_ENABLED)
        keyRepeatBackspaceOnly = prefs.getSafeBoolean("keyrepeat_backspace_only", Defaults.KEYREPEAT_BACKSPACE_ONLY)

        // Behavior settings
        doubleTapLockShift = prefs.getSafeBoolean("lock_double_tap", Defaults.DOUBLE_TAP_LOCK_SHIFT)
        switchInputImmediate = prefs.getSafeBoolean("switch_input_immediate", Defaults.SWITCH_INPUT_IMMEDIATE)
        smartPunctuationEnabled = prefs.getSafeBoolean("smart_punctuation", Defaults.SMART_PUNCTUATION)

        // Gesture tuning settings
        tapDurationThreshold = Config.safeGetInt(prefs, "tap_duration_threshold", Defaults.TAP_DURATION_THRESHOLD)
        doubleSpaceToPeriod = prefs.getSafeBoolean("double_space_to_period", Defaults.DOUBLE_SPACE_TO_PERIOD)
        doubleSpaceThreshold = Config.safeGetInt(prefs, "double_space_threshold", Defaults.DOUBLE_SPACE_THRESHOLD)
        swipeMinDistance = Config.safeGetFloat(prefs, "swipe_min_distance", Defaults.SWIPE_MIN_DISTANCE)
        swipeMinKeyDistance = Config.safeGetFloat(prefs, "swipe_min_key_distance", Defaults.SWIPE_MIN_KEY_DISTANCE)
        swipeMinDwellTime = Config.safeGetInt(prefs, "swipe_min_dwell_time", Defaults.SWIPE_MIN_DWELL_TIME)
        swipeNoiseThreshold = Config.safeGetFloat(prefs, "swipe_noise_threshold", Defaults.SWIPE_NOISE_THRESHOLD)
        swipeHighVelocityThreshold = Config.safeGetFloat(prefs, "swipe_high_velocity_threshold", Defaults.SWIPE_HIGH_VELOCITY_THRESHOLD)
        sliderSpeedSmoothing = Config.safeGetFloat(prefs, "slider_speed_smoothing", Defaults.SLIDER_SPEED_SMOOTHING)
        sliderSpeedMax = Config.safeGetFloat(prefs, "slider_speed_max", Defaults.SLIDER_SPEED_MAX)

        // Number row and numpad settings
        numberRowMode = prefs.getSafeString("number_row", Defaults.NUMBER_ROW)
        showNumpadMode = prefs.getSafeString("show_numpad", Defaults.SHOW_NUMPAD)
        numpadLayout = prefs.getSafeString("numpad_layout", Defaults.NUMPAD_LAYOUT)
        pinEntryEnabled = prefs.getSafeBoolean("pin_entry_enabled", false)

        // Advanced settings
        debugEnabled = prefs.getSafeBoolean("debug_enabled", Defaults.DEBUG_ENABLED)

        // Phase 1: Load exposed Config.kt settings
        wordPredictionEnabled = prefs.getSafeBoolean("word_prediction_enabled", Defaults.WORD_PREDICTION_ENABLED)
        autoSpaceAfterSuggestion = prefs.getSafeBoolean("auto_space_after_suggestion", Defaults.AUTO_SPACE_AFTER_SUGGESTION)
        autoSpaceBeforeSuggestion = prefs.getSafeBoolean("auto_space_before_suggestion", Defaults.AUTO_SPACE_BEFORE_SUGGESTION)
        backspaceUndoSwipe = prefs.getSafeBoolean("backspace_undo_swipe", Defaults.BACKSPACE_UNDO_SWIPE)
        backspaceUndoAutocorrect = prefs.getSafeBoolean("backspace_undo_autocorrect", Defaults.BACKSPACE_UNDO_AUTOCORRECT)
        suggestionBarOpacity = Config.safeGetInt(prefs, "suggestion_bar_opacity", Defaults.SUGGESTION_BAR_OPACITY)
        autoCorrectEnabled = prefs.getSafeBoolean("autocorrect_enabled", Defaults.AUTOCORRECT_ENABLED)
        termuxModeEnabled = prefs.getSafeBoolean("termux_mode_enabled", Defaults.TERMUX_MODE_ENABLED)
        vibrationDuration = prefs.getSafeInt("vibrate_duration", Defaults.VIBRATE_DURATION)
        // Per-event haptic feedback
        hapticKeyPress = prefs.getSafeBoolean("haptic_key_press", Defaults.HAPTIC_KEY_PRESS)
        hapticPredictionTap = prefs.getSafeBoolean("haptic_prediction_tap", Defaults.HAPTIC_PREDICTION_TAP)
        hapticTrackpointActivate = prefs.getSafeBoolean("haptic_trackpoint_activate", Defaults.HAPTIC_TRACKPOINT_ACTIVATE)
        hapticLongPress = prefs.getSafeBoolean("haptic_long_press", Defaults.HAPTIC_LONG_PRESS)
        hapticSwipeComplete = prefs.getSafeBoolean("haptic_swipe_complete", Defaults.HAPTIC_SWIPE_COMPLETE)
        swipeDebugEnabled = prefs.getSafeBoolean("swipe_show_debug_scores", Defaults.SWIPE_SHOW_DEBUG_SCORES)

        // Swipe Corrections settings
        swipeFinalAutocorrectEnabled = prefs.getSafeBoolean("swipe_final_autocorrect_enabled", Defaults.SWIPE_FINAL_AUTOCORRECT_ENABLED)
        swipeCorrectionPreset = prefs.getSafeString("swipe_correction_preset", "balanced")
        swipeFuzzyMatchMode = prefs.getSafeString("swipe_fuzzy_match_mode", Defaults.SWIPE_FUZZY_MATCH_MODE)
        autocorrectMaxLengthDiff = Config.safeGetInt(prefs, "autocorrect_max_length_diff", Defaults.AUTOCORRECT_MAX_LENGTH_DIFF)
        autocorrectPrefixLength = Config.safeGetInt(prefs, "autocorrect_prefix_length", Defaults.AUTOCORRECT_PREFIX_LENGTH)
        autocorrectMaxBeamCandidates = Config.safeGetInt(prefs, "autocorrect_max_beam_candidates", Defaults.AUTOCORRECT_MAX_BEAM_CANDIDATES)
        swipePredictionSource = Config.safeGetInt(prefs, "swipe_prediction_source", Defaults.SWIPE_PREDICTION_SOURCE)
        swipeCommonWordsBoost = Config.safeGetFloat(prefs, "swipe_common_words_boost", Defaults.SWIPE_COMMON_WORDS_BOOST)
        swipeTop5000Boost = Config.safeGetFloat(prefs, "swipe_top5000_boost", Defaults.SWIPE_TOP5000_BOOST)
        swipeRareWordsPenalty = Config.safeGetFloat(prefs, "swipe_rare_words_penalty", Defaults.SWIPE_RARE_WORDS_PENALTY)

        // Swipe trail appearance settings
        swipeTrailEnabled = prefs.getSafeBoolean("swipe_trail_enabled", Defaults.SWIPE_TRAIL_ENABLED)
        swipeTrailEffect = prefs.getSafeString("swipe_trail_effect", Defaults.SWIPE_TRAIL_EFFECT)
        swipeTrailColor = prefs.getSafeInt("swipe_trail_color", Defaults.SWIPE_TRAIL_COLOR)
        swipeTrailWidth = prefs.getSafeFloat("swipe_trail_width", Defaults.SWIPE_TRAIL_WIDTH)
        swipeTrailGlowRadius = prefs.getSafeFloat("swipe_trail_glow_radius", Defaults.SWIPE_TRAIL_GLOW_RADIUS)

        // Word Prediction Advanced settings
        contextAwarePredictionsEnabled = prefs.getSafeBoolean("context_aware_predictions_enabled", Defaults.CONTEXT_AWARE_PREDICTIONS_ENABLED)
        personalizedLearningEnabled = prefs.getSafeBoolean("personalized_learning_enabled", Defaults.PERSONALIZED_LEARNING_ENABLED)
        nextWordPredictionEnabled = prefs.getSafeBoolean("next_word_prediction_enabled", Defaults.NEXT_WORD_PREDICTION_ENABLED)
        contextSource = prefs.getSafeString("context_source", Defaults.CONTEXT_SOURCE)
        personalizationWeight = Config.safeGetFloat(prefs, "personalization_weight", Defaults.PERSONALIZATION_WEIGHT)
        personalizationMaxWords = Config.safeGetInt(prefs, "personalization_max_words", Defaults.PERSONALIZATION_MAX_WORDS)
        learningAggression = prefs.getSafeString("learning_aggression", Defaults.LEARNING_AGGRESSION)
        predictionContextBoost = Config.safeGetFloat(prefs, "prediction_context_boost", Defaults.PREDICTION_CONTEXT_BOOST)
        predictionFrequencyScale = Config.safeGetFloat(prefs, "prediction_frequency_scale", Defaults.PREDICTION_FREQUENCY_SCALE)

        // Auto-correction advanced settings
        autocorrectMinWordLength = Config.safeGetInt(prefs, "autocorrect_min_word_length", Defaults.AUTOCORRECT_MIN_WORD_LENGTH)
        autocorrectCharMatchThreshold = Config.safeGetFloat(prefs, "autocorrect_char_match_threshold", Defaults.AUTOCORRECT_CHAR_MATCH_THRESHOLD)
        autocorrectMinFrequency = Config.safeGetInt(prefs, "autocorrect_confidence_min_frequency", Defaults.AUTOCORRECT_MIN_FREQUENCY)

        // Multi-language settings
        multiLangEnabled = prefs.getSafeBoolean("pref_enable_multilang", Defaults.ENABLE_MULTILANG)
        primaryLanguage = prefs.getSafeString("pref_primary_language", Defaults.PRIMARY_LANGUAGE)
        secondaryLanguage = prefs.getSafeString("pref_secondary_language", "none")
        autoDetectLanguage = prefs.getSafeBoolean("pref_auto_detect_language", Defaults.AUTO_DETECT_LANGUAGE)
        languageDetectionSensitivity = Config.safeGetFloat(prefs, "pref_language_detection_sensitivity", Defaults.LANGUAGE_DETECTION_SENSITIVITY)
        secondaryPredictionWeight = Config.safeGetFloat(prefs, "pref_secondary_prediction_weight", Defaults.SECONDARY_PREDICTION_WEIGHT)
        primaryLanguageAlt = prefs.getSafeString("pref_primary_language_alt", "es")
        secondaryLanguageAlt = prefs.getSafeString("pref_secondary_language_alt", "none")

        // Detect available V2 dictionaries for secondary language options
        availableSecondaryLanguages = detectAvailableV2Dictionaries()

        // Load installed language packs
        refreshInstalledLanguagePacks()

        // Privacy settings - collection OFF by default (CleverKeys is fully offline);
        // the MASTER on-device learning gate defaults ON (it is the opt-OUT)
        onDeviceLearningEnabled = prefs.getSafeBoolean("on_device_learning_enabled", Defaults.ON_DEVICE_LEARNING_ENABLED)
        privacyCollectSwipe = prefs.getSafeBoolean("privacy_collect_swipe", Defaults.PRIVACY_COLLECT_SWIPE)
        privacyCollectPerformance = prefs.getSafeBoolean("privacy_collect_performance", Defaults.PRIVACY_COLLECT_PERFORMANCE)

        // Short gesture settings
        shortGesturesEnabled = prefs.getSafeBoolean("short_gestures_enabled", Defaults.SHORT_GESTURES_ENABLED)
        shortGestureMinDistance = Config.safeGetInt(prefs, "short_gesture_min_distance", Defaults.SHORT_GESTURE_MIN_DISTANCE)
        shortGestureMaxDistance = Config.safeGetInt(prefs, "short_gesture_max_distance", Defaults.SHORT_GESTURE_MAX_DISTANCE)

        // Selection-delete mode settings
        selectionDeleteVerticalThreshold = Config.safeGetInt(prefs, "selection_delete_vertical_threshold", Defaults.SELECTION_DELETE_VERTICAL_THRESHOLD)
        selectionDeleteVerticalSpeed = Config.safeGetFloat(prefs, "selection_delete_vertical_speed", Defaults.SELECTION_DELETE_VERTICAL_SPEED)

        // Swipe debug advanced settings
        swipeDebugDetailedLogging = prefs.getSafeBoolean("swipe_debug_detailed_logging", Defaults.SWIPE_DEBUG_DETAILED_LOGGING)
        suggestionProvenanceMarkers = prefs.getSafeBoolean("suggestion_provenance_markers", Defaults.SUGGESTION_PROVENANCE_MARKERS)
}

internal fun SettingsActivity.saveSetting(key: String, value: Any) {
        val _self = this  // capture extension receiver for use inside non-inline lambdas
        lifecycleScope.launch {
            try {
                val editor = prefs.edit()
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Long -> editor.putLong(key, value)
                }
                editor.apply()

                // Update configuration object
                _self.updateConfigFromSettings()

                // Broadcast language changes so other activities can refresh
                if (key in listOf("pref_primary_language", "pref_secondary_language", "pref_enable_multilang")) {
                    LocalBroadcastManager.getInstance(_self)
                        .sendBroadcast(Intent("tribixbite.cleverkeys.LANGUAGE_CHANGED"))
                }

                android.util.Log.d(SettingsActivity.TAG, "Setting saved: $key = $value")

            } catch (e: Exception) {
                android.util.Log.e(SettingsActivity.TAG, "Error saving setting: $key = $value", e)
                Toast.makeText(_self,
                    _self.getString(R.string.settings_toast_error_saving, e.message ?: ""),
                    Toast.LENGTH_SHORT).show()
            }
        }
}

internal fun SettingsActivity.updateConfigFromSettings() {
        // Update global config from current settings
        // Note: Config.theme uses R.style.* resource IDs, converted from theme name
        // vibrate_custom is intentionally NOT set here — it is only set to true when the
        // user explicitly drags the duration slider. Mapping vibrationEnabled → vibrate_custom
        // was the root cause of #154 (it forced every user into the slow createOneShot path).
        config.apply {
            keyboardHeightPercent = keyboardHeight
            swipe_engine_mode = swipeEngineMode
            swipe_context_rescoring = swipeContextRescoring
            // Swipe corrections settings (these update the Config object)
            swipe_final_autocorrect_enabled = swipeFinalAutocorrectEnabled
            autocorrect_max_length_diff = autocorrectMaxLengthDiff
            autocorrect_prefix_length = autocorrectPrefixLength
            // swipe_fuzzy_match_mode, autocorrect_max_beam_candidates and the three word-scoring
            // multipliers were pushed here too. Their Config fields are gone: every consumer
            // died with OptimizedVocabulary, so the fields were being written on every settings
            // change and read by nothing.
        }
}
