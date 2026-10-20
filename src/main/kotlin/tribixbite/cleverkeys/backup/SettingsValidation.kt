package tribixbite.cleverkeys.backup

import tribixbite.cleverkeys.SettingsRanges
import tribixbite.cleverkeys.TerminalUtils
import tribixbite.cleverkeys.autocorrect.AutocorrectTuning
import tribixbite.cleverkeys.autocorrect.FrequencyFloor

/**
 * Single source of truth for SharedPreferences-backup validation rules.
 *
 * The legacy IO-driven import path AND the new pure plan builder both
 * call into here. Adding a rule in one place is enough — diverging means
 * the preview lies about what will land in prefs.
 *
 * Source of truth port: `BackupRestoreManager.validateIntPreference` +
 * `validateFloatPreference` + `validateStringPreference` +
 * `isInternalPreference` + `isFloatPreference`. Lines 750-1068 of
 * BackupRestoreManager.kt as of v1.4.0.
 */
object SettingsValidation {

    // Autocorrect "Ranking & Tuning" keys (2026-10-18), by type. Their ranges and allowed values
    // come from AutocorrectTuning.Keys — the definition the runtime reader clamps with and the
    // settings sliders use — so the import validator cannot drift from either.
    private val TUNING_FLOATS: Map<String, AutocorrectTuning.FloatKey> =
        AutocorrectTuning.Keys.FLOATS.associateBy { it.key }
    private val TUNING_INTS: Map<String, AutocorrectTuning.IntKey> =
        AutocorrectTuning.Keys.INTS.associateBy { it.key }
    private val TUNING_STRINGS: Map<String, AutocorrectTuning.StringKey> =
        AutocorrectTuning.Keys.STRINGS.associateBy { it.key }

    /**
     * Service-managed state that should never be imported or exported.
     * These are migration version markers + transient runtime state that
     * is meaningless across devices.
     *
     * `BackupRestoreManager.isInternalPreference` delegates here — this is
     * the single source of truth. Filtered from BOTH export (so backup
     * files never contain them) AND import (so legacy backups with these
     * keys are silently dropped).
     *
     * `SettingsDefaultsDriftTest` recognizes membership here as a valid
     * reason for a pref-read key to be absent from `SETTINGS_DEFAULTS`.
     */
    val INTERNAL_KEYS: Set<String> = setOf(
        "version",                          // Config migration version
        "current_layout_portrait",          // Runtime layout selection (per device)
        "current_layout_landscape",         // Runtime layout selection (per device)
        "margin_prefs_version",             // Margin-units migration version
        "need_migration",                   // DirectBoot prefs-migration tracker
        "lang_pref_migration_version",      // Language-prefs migration version
        "vibrate_custom_migration_v1",      // One-time #154 vibrate_custom cleanup marker
        "selection_history_reset_pending",  // v4 learning-consent migration: one-time selection-history wipe request (device-bound, consumed by UserAdaptationManager)
        "voice_ime_known",                  // Runtime voice-IME state (per device)
        "voice_ime_last_used",              // Runtime voice-IME state (per device)
        "ime_prompt_shown_this_session",    // Legacy pre-I-7 prompt flag (retired 2026-09-08; never portable)
        "ime_prompt_last_boot_ms",          // I-7: boot instant of the last default-IME prompt (device-bound)
        // Backup-encryption state (Stage B). NEVER exported: exporting the
        // wrapped passphrase inside the settings backup it protects would be
        // circular, and the intent-passphrase toggle / rate-limit timestamp are
        // device-local automation state.
        "backup_passphrase_ciphertext",     // Keystore-wrapped backup passphrase (base64)
        "backup_passphrase_iv",             // GCM IV for the wrapped passphrase (base64)
        "backup_allow_intent_passphrase",   // Opt-in: accept --es passphrase on headless IMPORT
        "backup_last_headless_action_ms",   // In-prefs rate-limit timestamp for headless actions
        // CTC imported-language-pack eligibility verdicts (2026-08-29). A DERIVED cache keyed by
        // the length+mtime of files in THIS device's filesDir — exporting it would restore one
        // device's measurement of one device's packs onto another, where the fingerprints cannot
        // match and the entries are at best dead weight. Recomputed on demand; see
        // swipe.CtcInstalledPacks.PREF_KEY.
        "ctc_langpack_verdicts",
        // Dictionary Manager: which old-default-frequency (100) custom words the user dismissed
        // the raise offer for (2026-10-10, tribixbite.cleverkeys.LegacyCustomWordFrequency).
        // Per-device UI state: NOT exported, so a restore elsewhere re-offers imported legacy
        // entries instead of inheriting another device's "not now".
        "legacy_custom_freq_offer_dismissed",
    )

    fun isInternalPreference(key: String): Boolean = key in INTERNAL_KEYS

    /**
     * Pref keys that the EXPORT used to seed as defaults but that no code
     * READS. Removing them from the live defaults map (and seeding logic)
     * means new backups don't contain them. But legacy backups in the
     * wild DO contain them — so the import plan builder filters these
     * out before the diff, so the user doesn't see a "(unset) → default"
     * noise row for a key that's about to be a no-op write into prefs
     * that nothing ever reads.
     *
     * The keys come from a historical state where `getAllDefaultPreferences()`
     * in BackupRestoreManager seeded both the "real" Config key (e.g.
     * `pref_enable_multilang`) AND a no-prefix duplicate (`enable_multilang`)
     * — and a few other cases of stale key names that survived migrations.
     */
    val DEPRECATED_KEYS: Set<String> = setOf(
        // Duplicates of Config's `pref_*` keys, never read:
        "enable_multilang",
        "primary_language",
        "auto_detect_language",
        "language_detection_sensitivity",
        // Renamed/superseded keys, never read at this name:
        "double_tap_lock_shift",          // Config reads `lock_double_tap`
        "autocorrect_min_frequency",      // Config reads `autocorrect_confidence_min_frequency`
        "keyboard_height_percent",        // Superseded by `keyboard_height`
        "extra_key_switch_greekmath",     // Never read; legacy
        "clipboard_pinned_rows",          // Reader (ClipboardPinView) deleted, D-9 2026-09-07
        // Removed with first-class TalkBack support (dead accessibility toggles):
        // sticky-keys duplicated shipped modifier latching; voice-guidance was
        // superseded by the ExploreByTouchHelper virtual-view tree. No runtime
        // reader ever existed. Legacy backups may still carry them.
        "sticky_keys_enabled",
        "sticky_keys_timeout",
        "sticky_keys_timeout_ms",         // SettingsActivity duplicate of the above
        "voice_guidance_enabled",
        // WP9 R-1 step 6 (2026-07-21): step-4's QA escape hatch for the unified swipe
        // pipeline, removed together with the legacy InputCoordinator-only path — the
        // SuggestionHandler pipeline is the only one. v1.5.x backups may still carry it.
        "unified_swipe_pipeline",
        // 2026-10-07: the "Terminal Mode" switch ("Show Ctrl, Meta, PageUp/Down keys for
        // terminal apps"). Its last reader (the tap trailing-space suppression) went with the
        // unified pipeline in WP9 step 6, and it showed no keys; terminal handling is
        // automatic (TerminalUtils + custom_terminal_packages). v1.x/v2.0-dev backups carry it.
        "termux_mode_enabled",
        // ARC-085 (2026-08-29): the "Correction Style" dropdown (Strict/Balanced/Lenient)
        // wrote this on every selection and NOTHING ever read it — not Config, not a
        // predictor, not an engine adapter — so the control responded to touch and changed
        // nothing. It shipped in SETTINGS_DEFAULTS, which means every export from every
        // released version carries it; without this tombstone those backups would surface a
        // meaningless import-preview row and write the dead key straight back into prefs.
        "swipe_correction_preset",
        // Neural swipe engine removal (2026-08-18). Every key below was READ ONLY by the
        // transformer decoder, its vocabulary or its settings screen, all deleted. They are
        // MOVED here rather than copied: SettingsDefaultsDriftTest asserts the four
        // classification buckets are disjoint, so leaving a stale SETTINGS_DEFAULTS entry
        // behind fails the build. Backups from v1.5.x and earlier still carry them, and
        // without this list the import preview would show ~20 meaningless rows and then
        // write dead keys into prefs.
        "neural_beam_width",
        "neural_max_length",
        "neural_confidence_threshold",
        "neural_batch_beams",
        "neural_greedy_search",
        "neural_beam_alpha",
        "neural_beam_prune_confidence",
        "neural_beam_score_gap",
        "neural_adaptive_width_step",
        "neural_score_gap_step",
        "neural_temperature",
        "neural_frequency_weight",
        "neural_prefix_boost_multiplier",
        "neural_prefix_boost_max",
        "neural_max_cumulative_boost",
        "neural_strict_start_char",
        "neural_resampling_mode",
        "neural_user_max_seq_length",
        "neural_preset",
        // ── Word-scoring knobs orphaned by the same removal (2026-08-19) ──────────────
        // These do not carry the `neural_` prefix, so the removal sweep missed them: their
        // only consumer was `OptimizedVocabulary`'s ranking and fuzzy-rescue code, which went
        // with the engine. They kept rendering as live sliders in Auto-Correction and kept
        // persisting, while driving nothing. The controls are gone; the keys are deprecated
        // here so an old backup cannot resurrect them as import-preview noise.
        // NOTE the autocorrect_* sliders are NOT in this list and must not be added — those
        // are tap-path (WordPredictor), unaffected by the engine removal, and still live.
        "swipe_prediction_source",
        "swipe_common_words_boost",
        "swipe_top5000_boost",
        "swipe_rare_words_penalty",
        "swipe_fuzzy_match_mode",
        "autocorrect_max_beam_candidates",
        // Beam-search-only autocorrect and the two raw-beam debug toggles: all three named
        // stages of the deleted decoder.
        "swipe_beam_autocorrect_enabled",
        "swipe_debug_show_raw_output",
        "swipe_show_raw_beam_predictions",
        // F-7 (2026-09-06): CGR calibration knobs. Their SwipeCalibrationActivity died with
        // the neural engine (2026-08-18) leaving NO reader or writer, but they stayed in
        // SETTINGS_DEFAULTS under a comment claiming live read sites — so every export
        // seeded them and every import wrote them back (the ARC-051/085 class).
        "cgr_beta",
        "cgr_e_sigma",
        "cgr_kappa",
        "cgr_lambda",
        "cgr_length_filter",
        // F-6 (2026-09-06): the Pin Entry Layout switch wrote this while its only runtime
        // reader was Config.migrate's ONE-TIME seeding of number_entry_layout (which runs
        // before Settings can ever open) — a no-op control on every install. The switch
        // now reads/writes number_entry_layout directly; migrate still consults this key
        // for legacy first-launch seeding, but it must not round-trip through backups.
        "pin_entry_enabled",
    )

    /**
     * Deprecated keys whose name carries a language suffix (`neural_prefix_boost_max_fr`).
     * [DEPRECATED_KEYS] is an exact-match set, so the per-language variants — which the
     * Multi-Language settings screen wrote one-per-language — need prefix matching or they
     * would slip through the filter and surface as import-preview noise for every language
     * the user ever selected.
     */
    private val DEPRECATED_KEY_PREFIXES = listOf(
        "neural_prefix_boost_multiplier_",
        "neural_prefix_boost_max_",
    )

    fun isDeprecatedPreference(key: String): Boolean =
        key in DEPRECATED_KEYS || DEPRECATED_KEY_PREFIXES.any { key.startsWith(it) }

    /**
     * Per-language dictionary words and disabled-words lists. These are
     * imported via the SEPARATE `DictImportPlan` flow (with its own
     * preview dialog), not as settings rows. The settings preview filters
     * these out so they don't appear as "(unset) → {huge JSON blob}" rows
     * that the user can't act on usefully.
     *
     * Keys: `custom_words_<lang>`, `disabled_words_<lang>` and (2026-10-08)
     * `swipe_priority_<lang>` — the per-word swipe priority of personal-dictionary
     * words, which travels with them in the dictionaries payload — where `<lang>`
     * is any 2+ letter language code.
     */
    private val DICTIONARY_KEY_PREFIXES = listOf("custom_words_", "disabled_words_", "swipe_priority_")

    fun isDictionaryPreference(key: String): Boolean =
        DICTIONARY_KEY_PREFIXES.any { key.startsWith(it) }

    /**
     * Mirrors BackupRestoreManager.isFloatPreference (lines 976-1009).
     * Drives the IntV vs FloatV dispatch in
     * SettingsImportPlanBuilder.parsePrefValue — using JSON shape instead of
     * key membership corrupts integer-multiple float values (e.g.
     * swipe_trail_width=5.0). Verbatim port of the full when-block + the two
     * prefix patterns.
     */
    fun isFloatPreference(key: String): Boolean {
        return when (key) {
            // Character and UI sizing
            "character_size", "secondary_label_size_scale", "key_vertical_margin", "key_horizontal_margin", "custom_border_line_width",
            // Prediction weights
            "prediction_context_boost", "prediction_frequency_scale",
            // Auto-correction threshold
            "autocorrect_char_match_threshold",
            // Swipe typing boost parameters (SlideBarPreference floats)
            "swipe_rare_words_penalty", "swipe_common_words_boost", "swipe_top5000_boost",
            // Advanced gesture tuning floats
            "slider_speed_smoothing", "slider_speed_max",
            "swipe_min_distance", "swipe_min_key_distance", "swipe_noise_threshold",
            "swipe_high_velocity_threshold",
            // Language detection
            "pref_language_detection_sensitivity",
            // Swipe trail appearance
            "swipe_trail_width", "swipe_trail_glow_radius",
            -> true
            // Autocorrect tuning floats: a JSON `10` for the inflection cost must stay a Float.
            in TUNING_FLOATS -> true
            else -> false
        }
    }

    /**
     * Returns null if the proposed value is valid for the key, or an
     * error message suitable for `SkippedKey.reason` if not.
     *
     * The rule set is exactly the union of the four `validate*Preference`
     * methods in BackupRestoreManager (lines 750-1068 as of v1.4.0). The
     * legacy validators are version-tolerant: unknown keys default to
     * accept (return true). The pure plan builder mirrors that — unknown
     * keys are accepted so this validator is purely a constraint guard.
     *
     * Type mismatch (e.g. PrefValue.Str passed for an Int-typed key) is
     * reported as an error string. The legacy IO path silently fell back
     * to the wrong validator for the JSON shape — this port is stricter.
     */
    fun validate(key: String, value: PrefValue): String? {
        if (key == "custom_terminal_packages" && value !is PrefValue.Str) {
            return "expected String"
        }
        return when (value) {
            is PrefValue.Bool -> validateBool(key, value.v)
            is PrefValue.IntV -> validateInt(key, value.v)
            is PrefValue.FloatV -> validateFloat(key, value.v)
            is PrefValue.Str -> validateString(key, value.v)
            is PrefValue.JsonBlob -> null   // JSON-blob prefs are validated by their own consumers (ListGroupPreference)
            is PrefValue.Unset -> "value is unset"
        }
    }

    /**
     * Bool prefs have no explicit range checks in the legacy validator;
     * any boolean key is accepted (else branch returned true). Reject
     * type-mismatch only when an int-allowlisted key receives a Bool.
     */
    private fun validateBool(key: String, @Suppress("UNUSED_PARAMETER") value: Boolean): String? {
        if (isIntKey(key)) return "expected Int, got Bool"
        if (isFloatPreference(key)) return "expected Float, got Bool"
        if (isStringValidatedKey(key)) return "expected String, got Bool"
        return null
    }

    /**
     * Accepted value range for every int-validated preference key.
     *
     * Port of BackupRestoreManager.validateIntPreference (lines 750-806), held as
     * DATA rather than as an inline `when` block so the same bounds serve two
     * callers instead of one:
     *
     *   1. [validateInt] — reject an out-of-range value with a visible skip row.
     *   2. [intRangeFor] — let `SettingsImportPlanBuilder` clamp a value it is
     *      already migrating out of a legacy representation (ARC-093), so the
     *      migrated number lands inside the same bounds the validator enforces
     *      instead of being rewritten silently at Config read time.
     *
     * A key absent from this table is version-tolerant: unknown integer prefs are
     * accepted unchanged, matching the legacy validator's `else -> true`.
     */
    private val INT_RANGES: Map<String, IntRange> = buildMap {
        // Ranges come from SettingsRanges — the same constant the slider and the Config read
        // clamp use (audit M8, 2026-10-20). A key whose import range is deliberately WIDER than
        // its slider reads SettingsRanges.ImportEnvelope instead, which says why.

        // CTC beam width — the same bounds CtcEngineAdapter and Config clamp to, so a hostile
        // or hand-edited backup cannot smuggle a value the engine would silently clamp anyway:
        // the import preview rejects it visibly instead. This key previously fell through to
        // `else -> true` while the DELETED engine's neural_beam_width was still the one named
        // in this file.
        put("ctc_beam_width", SettingsRanges.CTC_BEAM_WIDTH)

        // ONNX Runtime threads (M8: was accepted unvalidated, then clamped at read).
        put("onnx_xnnpack_threads", SettingsRanges.ONNX_XNNPACK_THREADS)

        // Finger-occlusion offset: SIGNED percent of one key row, 0 = off.
        put("finger_occlusion_offset", SettingsRanges.FINGER_OCCLUSION_OFFSET)

        // Opacity values (0-100)
        for (k in listOf(
            "label_brightness", "keyboard_opacity", "key_opacity",
            "key_activated_opacity", "suggestion_bar_opacity",
        )) put(k, SettingsRanges.OPACITY_PERCENT)

        // Keyboard height percentages
        // One range for all four heights: the sliders' (SettingsRanges.KEYBOARD_HEIGHT).
        for (k in tribixbite.cleverkeys.KeyboardHeightPrefs.ALL_KEYS) put(k, tribixbite.cleverkeys.SettingsRanges.KEYBOARD_HEIGHT)

        // Bottom margins (0-30% of screen height)
        for (k in listOf(
            "margin_bottom_portrait", "margin_bottom_landscape",
            "margin_bottom_portrait_unfolded", "margin_bottom_landscape_unfolded",
        )) put(k, SettingsRanges.MARGIN_BOTTOM_PERCENT)

        // Left/right margins (0-45% of screen width each; the 90% total cap is the sliders')
        for (k in listOf(
            "margin_left_portrait", "margin_left_landscape",
            "margin_left_portrait_unfolded", "margin_left_landscape_unfolded",
            "margin_right_portrait", "margin_right_landscape",
            "margin_right_portrait_unfolded", "margin_right_landscape_unfolded",
        )) put(k, SettingsRanges.MARGIN_SIDE_PERCENT)

        // Legacy horizontal_margin (kept for backward compatibility, 0-200 dp)
        for (k in listOf(
            "horizontal_margin_portrait", "horizontal_margin_landscape",
            "horizontal_margin_portrait_unfolded", "horizontal_margin_landscape_unfolded",
        )) put(k, 0..200)

        // Border radius, percent of key width. Import envelope 0-100 (the pre-2025-12 slider);
        // today's slider offers 0-20 (audit M9).
        put("custom_border_radius", SettingsRanges.ImportEnvelope.CUSTOM_BORDER_RADIUS)

        // Timing values (milliseconds). Import envelopes, wider than the sliders (audit M9).
        put("vibrate_duration", SettingsRanges.ImportEnvelope.VIBRATE_DURATION)
        put("longpress_timeout", SettingsRanges.ImportEnvelope.LONGPRESS_TIMEOUT)
        // F-4: shared with the slider + Config clamp (was 5..100 while the UI
        // offered 25..200 — a user's own exported 150 came back "out of range").
        put("longpress_interval", SettingsRanges.LONGPRESS_INTERVAL)

        // Short gesture distance: min accepts the pre-2025-12 slider's 10-95% (audit M9),
        // max is the slider's 50-200%.
        put("short_gesture_min_distance", SettingsRanges.ImportEnvelope.SHORT_GESTURE_MIN_DISTANCE)
        put("short_gesture_max_distance", SettingsRanges.SHORT_GESTURE_MAX_DISTANCE)

        // Gesture resampling window
        put("swipe_smoothing_window", SettingsRanges.SWIPE_SMOOTHING_WINDOW)

        // Clipboard pane height and GIF grid columns (M8: both were accepted unvalidated).
        put("clipboard_pane_height_percent", SettingsRanges.CLIPBOARD_PANE_HEIGHT_PERCENT)
        put("gif_thumbnail_columns", SettingsRanges.GIF_THUMBNAIL_COLUMNS)

        // Auto-correction parameters
        put("autocorrect_min_word_length", SettingsRanges.AUTOCORRECT_MIN_WORD_LENGTH)
        // The domain FrequencyFloor maps the slider over (AC-1: values above it clamp away).
        put(
            "autocorrect_confidence_min_frequency",
            FrequencyFloor.SLIDER_MIN..FrequencyFloor.SLIDER_MAX,
        )

        // Clipboard history limit (0 = unlimited). Shared with the slider,
        // validateString's canonical string form (G-5) and the Config clamp.
        put("clipboard_history_limit", SettingsRanges.CLIPBOARD_HISTORY_LIMIT)

        // Clipboard media size cap in MB — F-8: shared with the new slider and
        // the Config read-site clamp (historic coerceIn 1..50).
        put("clipboard_max_media_size_mb", SettingsRanges.CLIPBOARD_MAX_MEDIA_SIZE_MB)

        // Circle sensitivity
        put("circle_sensitivity", SettingsRanges.CIRCLE_SENSITIVITY)

        // Autocorrect tuning ints (max substitutions, dropped-double margin, minimum stem).
        for (k in TUNING_INTS.values) put(k.key, k.range)

        // GH #190: follow-finger backspace selection step (dp). Shared with slider + Config clamp.
        put("selection_delete_step_dp", SettingsRanges.SELECTION_DELETE_STEP_DP)
    }

    /**
     * The validated range for [key], or `null` when the key has no int range
     * (unknown / not int-validated). See [INT_RANGES] for why this is public.
     */
    fun intRangeFor(key: String): IntRange? = INT_RANGES[key]

    /**
     * Port of BackupRestoreManager.validateIntPreference (lines 750-806) — the
     * bounds now live in [INT_RANGES]; the accept/reject decision and its message
     * are unchanged.
     */
    private fun validateInt(key: String, value: Int): String? {
        if (isFloatPreference(key)) return "expected Float, got Int"
        // Unknown integer preference - allow it (version-tolerant)
        val range = INT_RANGES[key] ?: return null
        return if (value in range) null else "out of range, got $value"
    }

    /**
     * Verbatim port of BackupRestoreManager.validateFloatPreference (lines 812-858).
     */
    private fun validateFloat(key: String, value: Float): String? {
        val ok = when (key) {
            // Autocorrect tuning weights: the key's own range (NaN is never in range).
            in TUNING_FLOATS -> value in TUNING_FLOATS.getValue(key).range

            // Character size — F-4: shared with the slider (0.5-2.0; was 0.75-1.5)
            "character_size" -> value in SettingsRanges.CHARACTER_SIZE

            // #133: Secondary (flick) label size multiplier (0.5-2.0)
            "secondary_label_size_scale" -> value in 0.5f..2.0f

            // Margins (0-5%)
            "key_vertical_margin", "key_horizontal_margin" -> value in 0f..5f

            // Border line width — F-4: shared with the slider (0-10 dp; was 0-5)
            "custom_border_line_width" -> value in SettingsRanges.CUSTOM_BORDER_LINE_WIDTH

            // Prediction weights
            "prediction_context_boost" -> value in 0.5f..5.0f
            "prediction_frequency_scale" -> value in 100.0f..5000.0f

            // Auto-correction threshold
            "autocorrect_char_match_threshold" -> value in 0.5f..0.9f

            // Swipe typing boost parameters (0.0-2.0 range)
            "swipe_rare_words_penalty", "swipe_common_words_boost", "swipe_top5000_boost" -> value in 0.0f..2.0f

            // Unknown float preference - allow it (version-tolerant)
            else -> true
        }
        return if (ok) null else "out of range, got $value"
    }

    /**
     * Verbatim port of BackupRestoreManager.validateStringPreference (lines 1037-1068).
     * Adds a type-mismatch guard: if the key is in the int- or float-typed
     * allowlist, reject — the legacy IO path got away with this because Gson
     * tagged the JSON primitive as String long before reaching the int/float
     * validators, but the pure builder routes by PrefValue and must not
     * silently accept a stringly-typed value for a numeric pref.
     */
    private fun validateString(key: String, value: String?): String? {
        if (value == null) return "value is null"
        if (isIntKey(key)) return "expected Int, got String"
        if (isFloatPreference(key)) return "expected Float, got String"

        if (key == "custom_terminal_packages") {
            return when (val result = TerminalUtils.parseCustomPackages(value)) {
                is TerminalUtils.PackageListResult.Valid -> null
                is TerminalUtils.PackageListResult.Invalid -> result.reason.name
            }
        }

        val ok = when (key) {
            // Autocorrect ranking / neighbour tiebreak: one of the key's stored values exactly.
            in TUNING_STRINGS -> value in TUNING_STRINGS.getValue(key).values

            // Theme values - relaxed validation for forward compatibility
            "theme" -> value.isNotEmpty()

            // Number row options
            "number_row" -> value.matches(Regex("no_number_row|no_symbols|symbols"))

            // Show numpad options
            "show_numpad" -> value.matches(Regex("never|always|landscape|[0-9]+"))

            // Numpad layout
            "numpad_layout" -> value.matches(Regex("high_first|low_first|default"))

            // Number entry layout
            "number_entry_layout" -> value.matches(Regex("pin|number"))

            // Circle sensitivity (string representation)
            "circle_sensitivity" -> value.matches(Regex("[1-5]"))

            // Slider sensitivity (string representation)
            "slider_sensitivity" -> value.matches(Regex("[0-9]+"))

            // G-5: clipboard_history_limit is STORED as a String (see the isIntKey
            // note below), so real exports carry the string form — which used to
            // fall through to `else -> true`, bypassing the deliberate 0..500
            // bound entirely ("9999"/"abc"/"-5" imported cleanly and applied).
            // Parse and enforce the same shared range the IntV form checks.
            "clipboard_history_limit" ->
                value.toIntOrNull()?.let { it in SettingsRanges.CLIPBOARD_HISTORY_LIMIT } == true

            // Swipe distance (string representation)
            "swipe_dist" -> value.matches(Regex("[0-9]+(\\.[0-9]+)?"))

            // Swipe engine mode. Deliberately NOT strict: SwipeEngineRouter.fromPref maps any
            // unrecognised value (including the removed "neural"/"hybrid") onto the ctc default
            // rather than crashing, so rejecting an old value here would block importing an
            // otherwise-valid v1.5.x backup. Assert only that it is non-empty — an empty string
            // is a corrupt write rather than a legacy value.
            "swipe_engine_mode" -> value.isNotEmpty()

            // GH #190: backspace swipe+hold selection mode
            "selection_delete_mode" -> value.matches(Regex("speed|follow"))

            // Unknown string preference - allow it (version-tolerant)
            else -> true
        }
        return if (ok) null else "invalid string value, got \"$value\""
    }

    /**
     * Keys with an [INT_RANGES] bound whose value is STORED as a String (`Defaults.CIRCLE_SENSITIVITY
     * = "2"`, `Defaults.CLIPBOARD_HISTORY_LIMIT = "0"`; their read sites use `safeGetString`). Their
     * imports carry the string form, which [validateString] range-checks; treating them as Int
     * keys would reject every real export as "expected Int, got String".
     */
    private val STRING_STORED_NUMERIC: Set<String> = setOf("clipboard_history_limit", "circle_sensitivity")

    /**
     * `true` if the key is stored as an Int — used for type-mismatch detection when a Bool or a
     * String arrives for it. Derived from [INT_RANGES] (audit M11, 2026-10-20): the hand-copied
     * key list this replaced had already lost `ctc_beam_width` and `finger_occlusion_offset`,
     * so a Bool or String import for either passed type checking.
     */
    private fun isIntKey(key: String): Boolean = key in INT_RANGES && key !in STRING_STORED_NUMERIC

    /**
     * Helper: `true` if the key is in the string-validated allowlist (used for
     * type-mismatch detection).
     */
    private fun isStringValidatedKey(key: String): Boolean = when (key) {
        "theme", "number_row", "show_numpad", "numpad_layout",
        "number_entry_layout", "circle_sensitivity", "slider_sensitivity",
        "swipe_dist", "clipboard_history_limit", "custom_terminal_packages",
        "selection_delete_mode" -> true
        in TUNING_STRINGS -> true
        else -> false
    }
}
