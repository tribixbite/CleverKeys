package tribixbite.cleverkeys

import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import tribixbite.cleverkeys.autocorrect.AutocorrectTuning
import tribixbite.cleverkeys.prefs.ConfigSnapshot
import tribixbite.cleverkeys.prefs.CustomExtraKeysPreference
import tribixbite.cleverkeys.prefs.ExtraKeysPreference
import tribixbite.cleverkeys.prefs.LayoutsPreference

/**
 * Single source of truth for all app default values.
 * Both Config.kt and SettingsActivity.kt should reference these constants.
 */
object Defaults {
    // Appearance
    const val THEME = "cleverkeysdark"
    // 2026-05-15: lowered from 30% to 27% — feels less cramped on most phones
    // and gives the input field more breathing room above.
    const val KEYBOARD_HEIGHT_PORTRAIT = 27
    const val KEYBOARD_HEIGHT_LANDSCAPE = 40
    const val LABEL_BRIGHTNESS = 100
    const val KEYBOARD_OPACITY = 100  // #51: Must be fully opaque by default
    const val KEY_OPACITY = 100
    // 2026-05-15: lowered from 100 to 80 — a subtle transparency on the
    // activated/pressed state is more visually obvious as press feedback
    // than a pure color shift.
    const val KEY_ACTIVATED_OPACITY = 80
    const val CHARACTER_SIZE = 1.18f
    // #133: Secondary-key (flick) label size as fraction of key height.
    // Exposed so a preference can scale it independently of the primary label.
    const val SUBLABEL_TEXT_SIZE_FACTOR = 0.22f
    // #133: User-facing multiplier on the secondary-label size. 1.0 = unchanged
    // (keeps the historic SUBLABEL_TEXT_SIZE_FACTOR ratio). Lets users shrink
    // crowded flick labels or enlarge them independently of the primary label.
    const val SECONDARY_LABEL_SIZE_SCALE = 1.0f
    /** Primary label size as a fraction of key height (not a preference; L12 names it). */
    const val LABEL_TEXT_SIZE_FACTOR = 0.33f
    const val KEY_VERTICAL_MARGIN = 1.5f
    const val KEY_HORIZONTAL_MARGIN = 2.0f
    const val BORDER_CONFIG = false
    const val CUSTOM_BORDER_RADIUS = 0
    const val CUSTOM_BORDER_LINE_WIDTH = 0

    // Layout
    const val SHOW_NUMPAD = "never"
    const val NUMPAD_LAYOUT = "default"
    const val NUMBER_ROW = "no_number_row"
    const val NUMBER_ENTRY_LAYOUT = "pin"
    // Scale numeric keyboards (PIN/numpad) to fill full keyboard height
    const val SCALE_NUMPAD_HEIGHT = true

    // Margin settings (percentages of screen dimension)
    // Bottom margin as % of screen height
    const val MARGIN_BOTTOM_PORTRAIT = 0      // No bottom margin
    const val MARGIN_BOTTOM_LANDSCAPE = 0     // No bottom margin
    // Left/Right margins as % of screen width
    const val MARGIN_LEFT_PORTRAIT = 1        // ~1% of screen width
    const val MARGIN_LEFT_LANDSCAPE = 5       // ~5% of screen width
    const val MARGIN_RIGHT_PORTRAIT = 1       // ~1% of screen width
    const val MARGIN_RIGHT_LANDSCAPE = 5      // ~5% of screen width

    // Legacy compatibility: old dp-based settings used these names
    // HORIZONTAL_MARGIN_PORTRAIT = 3dp, HORIZONTAL_MARGIN_LANDSCAPE = 28dp
    @Deprecated("Use MARGIN_LEFT_* and MARGIN_RIGHT_* instead")
    const val HORIZONTAL_MARGIN_PORTRAIT = 3
    @Deprecated("Use MARGIN_LEFT_* and MARGIN_RIGHT_* instead")
    const val HORIZONTAL_MARGIN_LANDSCAPE = 28

    // Input behavior
    const val VIBRATE_CUSTOM = false
    const val VIBRATE_DURATION = 20
    // Master haptic toggle - when false, disables ALL vibration/haptic feedback
    const val HAPTIC_ENABLED = true
    // Per-event haptic feedback (all enabled by default, but only if HAPTIC_ENABLED is true)
    const val HAPTIC_KEY_PRESS = true
    const val HAPTIC_PREDICTION_TAP = true
    const val HAPTIC_TRACKPOINT_ACTIVATE = true
    const val HAPTIC_LONG_PRESS = true
    // 2026-05-15: enabled by default — the swipe-completion haptic confirms
    // word recognition, which teaches new users the gesture worked. The
    // "distracting" concern is best handled per-user via the toggle.
    const val HAPTIC_SWIPE_COMPLETE = true
    const val LONGPRESS_TIMEOUT = 600
    const val LONGPRESS_INTERVAL = 25
    const val KEYREPEAT_ENABLED = true
    // 2026-05-15: changed default to true — letter auto-repeat is rarely
    // useful and frequently surprising; modern keyboards (Gboard, SwiftKey)
    // don't repeat letters on long-press. #81: backspace + nav keys still repeat.
    const val KEYREPEAT_BACKSPACE_ONLY = true
    const val DOUBLE_TAP_LOCK_SHIFT = true
    const val AUTOCAPITALISATION = true
    const val SWITCH_INPUT_IMMEDIATE = false
    // #139: OFF keeps the editor-driven Enter/action swap (Send/Search on the Enter key).
    // ON makes Enter insert a newline in multi-line fields; see EditorInfoHelper.shouldSwapEnterActionKey.
    const val ENTER_ALWAYS_NEWLINE = false
    const val SMART_PUNCTUATION = true

    // Gesture settings
    const val SWIPE_DIST = "23"
    const val SWIPE_DIST_FALLBACK = 23f
    const val SLIDER_SENSITIVITY = "30"
    /** Numeric fallback when the stored `slider_sensitivity` string does not parse (L12). */
    const val SLIDER_SENSITIVITY_FALLBACK = 30f
    const val CIRCLE_SENSITIVITY = "2"
    const val CIRCLE_SENSITIVITY_FALLBACK = 2
    const val TAP_DURATION_THRESHOLD = 150
    const val DOUBLE_SPACE_TO_PERIOD = true
    const val DOUBLE_SPACE_THRESHOLD = 500
    const val SWIPE_MIN_DISTANCE = 72f
    // 2026-05-15: lowered from 38px to 15px — 38 is too conservative for
    // narrow keys on compact keyboards; many short strokes weren't catching.
    const val SWIPE_MIN_KEY_DISTANCE = 15f
    const val SWIPE_MIN_DWELL_TIME = 7
    const val SWIPE_NOISE_THRESHOLD = 1.26f
    const val SWIPE_HIGH_VELOCITY_THRESHOLD = 1000f
    const val SLIDER_SPEED_SMOOTHING = 0.6f  // Slightly more responsive smoothing
    const val SLIDER_SPEED_MAX = 6.0f  // Higher max for faster long-distance cursor movement

    // Short gestures
    const val SHORT_GESTURES_ENABLED = true
    const val SHORT_GESTURE_MIN_DISTANCE = 28
    const val SHORT_GESTURE_MAX_DISTANCE = 141
    /**
     * GH #147/#87 "short swipes only": every gesture past the short-gesture MINIMUM resolves
     * to its direction's subkey, whatever its length; word swipes are off while it is on.
     * OFF by default, so the short/long boundary behaves exactly as before.
     */
    const val SHORT_SWIPES_ONLY = false

    // Hold-then-select subkey popover (docs/specs/subkey-popover.md). ON for fresh installs;
    // existing installs are seeded OFF once by SubkeyPopoverMigration (config v5).
    const val SUBKEY_POPOVER_ENABLED = true
    /** Neutral-zone size as % of the key's width / height; releasing inside it does nothing. */
    const val SUBKEY_POPOVER_NEUTRAL_WIDTH = 60
    const val SUBKEY_POPOVER_NEUTRAL_HEIGHT = 60
    const val SUBKEY_POPOVER_NEUTRAL_MIN = 20
    const val SUBKEY_POPOVER_NEUTRAL_MAX = 150

    // Selection-delete mode (backspace swipe+hold)
    const val SELECTION_DELETE_VERTICAL_THRESHOLD = 40  // % of key height - must exceed to trigger vertical
    const val SELECTION_DELETE_VERTICAL_SPEED = 0.4f    // Speed multiplier for vertical (slower than horizontal)
    /**
     * GH #190: how finger movement drives the selection. [SELECTION_DELETE_MODE_SPEED] (the
     * historical behaviour) is a rate-controlled joystick; [SELECTION_DELETE_MODE_FOLLOW]
     * makes the selection length proportional to the finger's travel from the activation
     * point, so moving back shrinks it (HeliBoard/FlorisBoard style).
     */
    const val SELECTION_DELETE_MODE_SPEED = "speed"
    const val SELECTION_DELETE_MODE_FOLLOW = "follow"
    const val SELECTION_DELETE_MODE = SELECTION_DELETE_MODE_SPEED
    /** GH #190: finger travel (dp) per selected character in follow-finger mode. */
    const val SELECTION_DELETE_STEP_DP = 12

    // Swipe trail
    const val SWIPE_TRAIL_ENABLED = true
    const val SWIPE_TRAIL_EFFECT = "sparkle"
    const val SWIPE_TRAIL_COLOR = 0xFFC0C0C0.toInt() // Silver
    const val SWIPE_TRAIL_WIDTH = 8.0f
    const val SWIPE_TRAIL_GLOW_RADIUS = 6.0f

    // Swipe decoding - core parameters

    // Swipe decoding - beam tuning
    // NOTE: These MUST match the working defaults in BeamSearchEngine.kt
    // 2026-05-15: increased from 1.0 to 1.4 — favors longer candidate words
    // slightly. Empirically catches more complete swiped words than the
    // pure-linear normalization on average input.
    /**
     * Signed percent of ONE KEY ROW to shift raw touch Y before decoding. 0 = disabled.
     *
     * Zero deliberately. The pre-2026-08-18 neural engine defaulted this to 12.5% ("users touch
     * ~74 px above key centre"), but no measurement of that figure exists anywhere in this
     * repo's history, and CTC's 89.31 top-1 was measured on UNCORRECTED traces — so a nonzero
     * default would move input off the distribution the shipped model learned. Raise it only
     * behind a device-trace A/B.
     */
    const val FINGER_OCCLUSION_OFFSET = 0

    const val SWIPE_SMOOTHING_WINDOW = 3        // Points for moving average smoothing (1 = disabled, 3 = optimal)

    // Language-specific prefix boost (for non-English primary languages)


    // Word prediction
    const val CONTINUOUS_SWIPE_ENABLED = false
    const val SWIPE_TYPING_ENABLED = true
    const val SWIPE_ON_PASSWORD_FIELDS = false  // #39: Reenable swipe typing on password fields
    const val WORD_PREDICTION_ENABLED = true
    const val SUGGESTION_BAR_OPACITY = 80
    const val SHOW_EXACT_TYPED_WORD = true  // #42: Show exact typed string as tap-to-add-to-dictionary option
    const val CONTEXT_AWARE_PREDICTIONS_ENABLED = true
    const val PERSONALIZED_LEARNING_ENABLED = true
    // Default ON since 2026-09-26 (maintainer decision): the shipped static tier works with
    // on-device learning off, so next-word no longer depends on an opt-in the user may never
    // make. Installs with an explicit stored `false` keep it; unset installs get ON.
    const val NEXT_WORD_PREDICTION_ENABLED = true
    const val CONTEXT_SOURCE = "both" // both | learned_only | static_only (audit §3.2-2)
    const val PERSONALIZATION_WEIGHT = 1.0f // 0=off … 2=double (audit §3.2-1)
    const val PERSONALIZATION_MAX_WORDS = 5000 // learned-vocabulary size cap (least-value rolling eviction)
    const val LEARNING_AGGRESSION = "BALANCED"
    const val PREDICTION_CONTEXT_BOOST = 0.5f
    const val PREDICTION_FREQUENCY_SCALE = 100.0f

    // Autocorrect
    const val AUTOCORRECT_ENABLED = true
    // 2026-05-15: lowered from 3 to 2 — 2-char typos (e.g. "th" → "the")
    // are common and worth correcting.
    const val AUTOCORRECT_MIN_WORD_LENGTH = 2
    // 2026-05-15: lowered from 0.67 to 0.65 — slightly more permissive
    // character-match threshold; catches a few more typos without
    // noticeably increasing false-positive corrections.
    const val AUTOCORRECT_CHAR_MATCH_THRESHOLD = 0.65f
    const val AUTOCORRECT_MIN_FREQUENCY = 100
    const val AUTOCORRECT_MAX_LENGTH_DIFF = 2
    // 2026-05-15: lowered from 1 to 0 — no required prefix match. The
    // beam-candidate ranker still prefers same-prefix corrections, but
    // we no longer hard-require even the first character to match.
    const val AUTOCORRECT_PREFIX_LENGTH = 0
    const val AUTOCORRECT_MAX_BEAM_CANDIDATES = 3
    const val SWIPE_FINAL_AUTOCORRECT_ENABLED = true

    // Autocorrect tuning (2026-10-18; `autocorrect/AutocorrectTuning.kt`, eval
    // docs/eval/2026-10-18-channel-autocorrect-ranking.md). Each band default is the constant
    // that shipped before it was a setting; their calibration notes live on the WordPredictor
    // constants they name.
    /**
     * `band` (score band, then frequency) or `channel` (edit cost + λ · rank). `channel` since
     * 2026-10-19: round-2 TEST passed T1–T5 and failed only T6 (Hebrew HE-adj, 42 distinct breaks
     * against an inherited absolute ceiling, net +270); the maintainer overrode T6
     * (eval doc §6).
     */
    const val AUTOCORRECT_RANKING = "channel"
    const val AUTOCORRECT_BAND_GAP = WordPredictor.SCORE_TIEBREAK_GAP
    const val AUTOCORRECT_TRANSPOSITION_PENALTY = WordPredictor.TRANSPOSITION_PENALTY
    const val AUTOCORRECT_MIN_EXACT_RATIO = WordPredictor.MIN_SAME_LENGTH_EXACT_RATIO
    const val AUTOCORRECT_MAX_SUBSTITUTIONS = WordPredictor.MAX_SAME_LENGTH_SUBSTITUTIONS
    const val AUTOCORRECT_LENGTH_EDIT_BUDGET = WordPredictor.LENGTH_DIFF_ED_BUDGET
    /** `off`, `n1` or `na` — see `WordPredictor.NeighbourTiebreak`. */
    const val AUTOCORRECT_NEIGHBOUR_TIEBREAK = "off"
    const val AUTOCORRECT_DROPPED_DOUBLE_ENABLED = true
    const val AUTOCORRECT_DROPPED_DOUBLE_MARGIN = WordPredictor.DROPPED_DOUBLE_MARGIN_STEPS
    const val AUTOCORRECT_ELONGATION_COLLAPSE_ENABLED = true
    const val AUTOCORRECT_INFLECTION_GUARD_ENABLED = true
    const val AUTOCORRECT_INFLECTION_GUARD_MIN_STEM = 4
    // Channel weights: the round-2 DEV choice (eval doc §4; soundness check 0 mismatches). It is
    // also the §3.4 opt-in fallback, so these hold whichever ranking is the default.
    const val AUTOCORRECT_CHANNEL_DISTANCE_COST = 2.0f
    const val AUTOCORRECT_CHANNEL_INDEL_COST = 1.5f
    const val AUTOCORRECT_CHANNEL_DOUBLE_COST = 1.0f
    const val AUTOCORRECT_CHANNEL_TRANSPOSITION_COST = 1.2f
    const val AUTOCORRECT_CHANNEL_FREQUENCY_WEIGHT = 0.005f
    /**
     * δ: extra cost of deleting onto a word of ≤ 2 letters, applied within
     * [AUTOCORRECT_CHANNEL_SHORT_DELETION_SCOPE]. 0.5 since round 4 (eval doc §11–§12: fresh HE-adj3
     * +86 distinct, 0 breaks; English outputs identical on 35,825 inputs).
     */
    const val AUTOCORRECT_CHANNEL_SHORT_DELETION_COST = 0.5f
    /** Where δ applies: `hebrew` (Hebrew-script words only) or `all` (round 4, eval doc §11). */
    const val AUTOCORRECT_CHANNEL_SHORT_DELETION_SCOPE = "hebrew"
    /**
     * κ: largest edit cost a correction may have; 10 (the slider's top) = off. 3.0 since round 5
     * (eval doc §13–§15: on TEST, 43.8 % of valid unlisted words protected, GTC −0.98 %, S-adj 0).
     */
    const val AUTOCORRECT_CHANNEL_MAX_CORRECTION_COST = 3.0f
    /** β: cost discount of a contraction alias key (round 2, eval doc §3.1). */
    const val AUTOCORRECT_CHANNEL_ALIAS_BONUS = 0.25f
    /** γ (round 2 choice 1.0); 10, the slider's top, means "always keep", the band ranking's guard. */
    const val AUTOCORRECT_CHANNEL_INFLECTION_COST = 1.0f

    // Issue #72: Auto-capitalize "I" and its contractions
    // When enabled, automatically capitalizes "i", "i'm", "i'll", "i'd", "i've"
    const val AUTOCAPITALIZE_I_WORDS = true
    const val SWIPE_FUZZY_MATCH_MODE = "edit_distance"
    const val SWIPE_PREDICTION_SOURCE = 80
    const val SWIPE_COMMON_WORDS_BOOST = 1.0f
    const val SWIPE_TOP5000_BOOST = 1.0f
    const val SWIPE_RARE_WORDS_PENALTY = 1.0f

    // Clipboard
    // Issue #71: Made defaults more robust to prevent TransactionTooLargeException
    // Android Binder has ~1MB limit; conservative defaults prevent crashes
    const val CLIPBOARD_HISTORY_ENABLED = true
    // History limit: 0 = unlimited (no pruning), >0 = max entry count.
    // applySizeLimit() skips when limit <= 0. SettingsActivity slider: 0..500 where 0 = "Unlimited".
    // Backup/restore preserves the raw int (0 for unlimited). DO NOT add "limit > 0" guards that
    // would break unlimited — always use "limit > 0" as the conditional, never "limit >= 0".
    // 2026-05-15: changed default from "50" to "0" (unlimited). Power users
    // hit 50 entries quickly; size pressure is governed by per-item size +
    // total size limits instead. The slider still allows 1..500 if a user
    // wants a count cap.
    const val CLIPBOARD_HISTORY_LIMIT = "0"
    const val CLIPBOARD_HISTORY_LIMIT_FALLBACK = 0
    const val CLIPBOARD_PANE_HEIGHT_PERCENT = 30
    // 2026-05-15: raised from 256 to 512. Android Binder IPC caps at ~1MB
    // so 512 still leaves headroom for the framing+envelope overhead;
    // modern devices comfortably handle larger pastes (long articles,
    // formatted text). The slider lets the user push to 1024 if needed.
    const val CLIPBOARD_MAX_ITEM_SIZE_KB = "512"
    const val CLIPBOARD_MAX_ITEM_SIZE_KB_FALLBACK = 512
    const val CLIPBOARD_LIMIT_TYPE = "count"  // "count" or "size"
    const val CLIPBOARD_SIZE_LIMIT_MB = "5"
    const val CLIPBOARD_SIZE_LIMIT_MB_FALLBACK = 5
    const val CLIPBOARD_EXCLUDE_PASSWORD_MANAGERS = true  // Skip clipboard from password managers
    // Duration: -1 = never expire (Long.MAX_VALUE expiry), >=0 = minutes until auto-deletion.
    // When -1, cleanupExpiredEntries() is skipped entirely; rescueExpiredEntries() runs instead.
    // SettingsActivity auto-links: setting "Never expire" also sets count to unlimited (0).
    const val CLIPBOARD_HISTORY_DURATION = "-1"
    const val CLIPBOARD_HISTORY_DURATION_FALLBACK = -1
    const val CLIPBOARD_RESPECT_SENSITIVE_FLAG = true  // #86: Respect Android 13+ IS_SENSITIVE flag
    // F-8 (surfaced 2026-09-08): media capture toggle + size cap, historic v4 defaults
    const val CLIPBOARD_MEDIA_ENABLED = true
    const val CLIPBOARD_MAX_MEDIA_SIZE_MB = 10
    // v4 clipboard tab/display toggles (audit M3, 2026-10-20: these were literal `false`/`true`
    // in Config, SettingsPersistence, SettingsDefaults and SettingsResetPolicy).
    const val CLIPBOARD_TEXT_ONLY = false      // Hide media entries from all tabs
    const val CLIPBOARD_PINNED_ENABLED = true  // Show the pinned tab
    const val CLIPBOARD_TODO_ENABLED = true    // Show the todo tab
    // #156 PROCESS_TEXT "Private copy" entry: the selection-toolbar item is opt-in, its
    // confirmation toast opt-out (were literals in six readers, audit 2026-10-20 leftover).
    const val CLIPBOARD_PRIVATE_COPY_TOOLBAR_ENABLED = false
    const val CLIPBOARD_PRIVATE_COPY_TOAST_ENABLED = true
    // URL sanitization (Chunk 3). All three transforms default OFF; the system-clipboard
    // write-back defaults ON because it only acts when a transform is on AND changed a URL.
    const val CLIPBOARD_SANITIZE_LINKS_ENABLED = false
    const val CLIPBOARD_EMBED_ENRICH_ENABLED = false
    const val CLIPBOARD_CUSTOM_RULES_ENABLED = false
    const val CLIPBOARD_SANITIZE_SYSTEM_CLIPBOARD = true

    // I-7 (2026-09-08): the default-IME reminder is on by default (honest default —
    // prompting enabled); the Settings "reminder" switch is the don't-ask-again affordance.
    const val IME_DEFAULT_PROMPT_ENABLED = true

    // GIF Panel — opt-in, off by default, zero data shipped in APK
    const val GIF_ENABLED = false           // Master toggle — enables GIF key + pane
    const val GIF_THUMBNAIL_COLUMNS = 3     // Grid columns in GIF picker

    // Common password manager package names (for clipboard exclusion)
    val PASSWORD_MANAGER_PACKAGES = setOf(
        // Bitwarden
        "com.x8bit.bitwarden",
        // 1Password
        "com.onepassword.android",
        "com.agilebits.onepassword",
        // LastPass
        "com.lastpass.lpandroid",
        // Dashlane
        "com.dashlane",
        // KeePass variants
        "keepass2android.keepass2android",
        "keepass2android.keepass2android_nonet",
        "com.kunzisoft.keepass.free",
        "com.kunzisoft.keepass.pro",
        "com.kunzisoft.keepass.libre",  // #86: KeePassDX Libre (F-Droid)
        "de.slackspace.openkeepass",
        // Enpass
        "io.enpass.app",
        // NordPass
        "com.nordpass.android.app.password.manager",
        // RoboForm
        "com.siber.roboform",
        // Keeper
        "com.callpod.android_apps.keeper",
        // Proton Pass
        "proton.android.pass",
        // SafeInCloud
        "com.safeincloud",
        // mSecure
        "com.msecure",
        // Zoho Vault
        "com.zoho.vault",
        // Sticky Password
        "com.stickypassword.android",
        // #86: Browser-based password managers (when copying credentials)
        // Google/Chrome password manager
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        // Microsoft Edge password manager
        "com.microsoft.emmx",
        // Samsung Pass
        "com.samsung.android.samsungpass",
        // Firefox password manager
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix"  // Firefox Nightly
    )

    // Multi-language
    const val ENABLE_MULTILANG = false
    const val PRIMARY_LANGUAGE = "en"
    /** `pref_secondary_language`; [LanguageDisplayNames.NONE] = no secondary language (M10). */
    const val SECONDARY_LANGUAGE = LanguageDisplayNames.NONE
    /** v1.2.0 quick-toggle alternates (`pref_primary_language_alt` / `pref_secondary_language_alt`). */
    const val PRIMARY_LANGUAGE_ALT = "es"
    const val SECONDARY_LANGUAGE_ALT = LanguageDisplayNames.NONE
    const val AUTO_DETECT_LANGUAGE = true
    const val LANGUAGE_DETECTION_SENSITIVITY = 0.6f
    const val SECONDARY_PREDICTION_WEIGHT = 0.9f  // v1.1.94: Multiplier for secondary dictionary predictions

    // ONNX Runtime settings
    const val ONNX_XNNPACK_THREADS = 2  // Default to 2 (optimal for most ARM devices)

    // Debug
    const val DEBUG_ENABLED = false
    const val SWIPE_SHOW_DEBUG_SCORES = false
    // Opt-in per-suggestion origin markers (pipeline transparency, audit §2.3 Tier 2)
    const val SUGGESTION_PROVENANCE_MARKERS = false
    const val SWIPE_DEBUG_DETAILED_LOGGING = false
    const val CUSTOM_TERMINAL_PACKAGES = ""
    // WP9 R-1 step 6: UNIFIED_SWIPE_PIPELINE (the step-4 QA escape hatch) was removed — the
    // SuggestionHandler pipeline is the only one; the pref key is in DEPRECATED_KEYS.
    // WP9 R-1 step 7 (v1.2): swipe prediction engine mode — "ctc" (default: CTC trie-beam
    // on Latin layouts for the validated languages, geometric everywhere else) or
    // "geometric" (SHARK2 on all layouts). The "neural"/"hybrid" modes were removed with
    // the neural engine on 2026-08-18; `SwipeEngineRouter.Mode.fromPref` maps those (and
    // any other legacy/imported string) onto the default. Settings → Swipe Typing →
    // Prediction Engine.
    const val SWIPE_ENGINE_MODE = "ctc"
    // Full Geometric Settings — the three user-tunable geo knobs (defaults MUST equal
    // GeometricEngineConfig's; the rest of the engine's 28 knobs stay code-only because
    // they are calibrated against the spec's measured accuracy floors).
    const val GEO_MAX_RESULTS = 10           // ranked candidates emitted (bar length)
    const val GEO_FREQUENCY_WEIGHT = 0.12f   // λ_f: common-words vs shape-fidelity bias
    const val GEO_ENDPOINT_INSET_KW = 0.30f  // sloppy start/end tolerance (key-widths)
    // CTC engine (G5) — commit-phase trie-beam width. 100 is the width every
    // CleverKeys-ML campaign-2 validation number was decoded at; wider buys little
    // (the beam is Viterbi-max over a 26-ary trie) and costs linear CPU per swipe.
    const val CTC_BEAM_WIDTH = 100

    /**
     * Rescore the swipe slate with the learned bigram/trigram context signal.
     *
     * **Default OFF, and it must stay OFF until the offline replay harness produces evidence**
     * (docs/specs/ctc-context-rescoring-and-tunables.md §7.3: net top-1 gain with promotion
     * errors well under errors fixed). Flipping this default is an evidence-gated release
     * decision, not a code change.
     *
     * Turning it on is inert for a user who has learned nothing: every context boost is then
     * 1.0, and `SwipeContextRescorer` reproduces the engine's own ranking exactly.
     */
    const val SWIPE_CONTEXT_RESCORING = false
    const val AUTO_SPACE_AFTER_SUGGESTION = true  // Add trailing space after selecting suggestion
    const val AUTO_SPACE_BEFORE_SUGGESTION = true  // Add leading space before tapped suggestion
    const val BACKSPACE_UNDO_SWIPE = true  // Backspace after swipe deletes entire swiped word
    const val BACKSPACE_UNDO_AUTOCORRECT = true  // #110: Backspace after autocorrect reverts to original word

    // Privacy
    const val PRIVACY_COLLECT_SWIPE = false
    const val PRIVACY_COLLECT_PERFORMANCE = false
    const val PRIVACY_COLLECT_ERRORS = false
    // MASTER on-device learning gate (Task A, 2026-08-06): opt-OUT switch that
    // short-circuits ALL typing-behavior learning (bigram/trigram context LM,
    // personalization vocabulary, selection adaptation, swipe-ML collection)
    // at the write layer. Default OFF since v2.0: fresh installs learn nothing
    // until the user opts in. UPGRADING installs are seeded an explicit `true`
    // by the v4 migration (LearningMigration) — their pre-2.0 behavior was
    // learning-on via the old defaults, and an upgrade must not silently change
    // what the keyboard does. Learning remains on-device only and clearable.
    const val ON_DEVICE_LEARNING_ENABLED = false

    // Accessibility
    const val STICKY_KEYS_ENABLED = false
    const val STICKY_KEYS_TIMEOUT = 5000
    const val VOICE_GUIDANCE_ENABLED = false
}

/**
 * Validated ranges of the three user-tunable geometric-engine knobs — the SINGLE source of
 * truth shared by the settings sliders ([GeometricSettingsActivity]) and the defensive clamps
 * in `swipe.GeometricEngineAdapter`.
 *
 * WP9 audit m-2 (2026-08-11): the adapter previously clamped far wider (1..32 / 0..1 / 0..2)
 * than the sliders allow, so a hand-edited or imported settings backup could push the engine
 * into un-validated territory (values the UI can neither display nor undo — e.g. a frequency
 * weight of 1.0 that drowns shape fidelity). Both sides now read these constants, so widening
 * a slider automatically widens the clamp and the two can never drift apart again.
 *
 * The bounds themselves are the spec's calibrated envelope: candidate count stays inside the
 * bar's usable depth, λ_f stays below the point where frequency out-votes shape, and the
 * endpoint inset stays under one key width.
 */
object GeoKnobRanges {
    /** Ranked candidates emitted per swipe (`geo_max_results`). */
    val MAX_RESULTS: IntRange = 3..15

    /** λ_f — common-words vs shape-fidelity bias (`geo_frequency_weight`). */
    val FREQUENCY_WEIGHT: ClosedFloatingPointRange<Float> = 0.0f..0.4f

    /** Sloppy start/end tolerance in key widths (`geo_endpoint_inset_kw`). */
    val ENDPOINT_INSET_KW: ClosedFloatingPointRange<Float> = 0.0f..0.8f

    /** Clamps a raw `geo_max_results` (pref/import/Config) into [MAX_RESULTS]. */
    fun clampMaxResults(value: Int): Int = value.coerceIn(MAX_RESULTS.first, MAX_RESULTS.last)

    /** Clamps a raw `geo_frequency_weight` into [FREQUENCY_WEIGHT]. */
    fun clampFrequencyWeight(value: Float): Float =
        value.coerceIn(FREQUENCY_WEIGHT.start, FREQUENCY_WEIGHT.endInclusive)

    /** Clamps a raw `geo_endpoint_inset_kw` into [ENDPOINT_INSET_KW]. */
    fun clampEndpointInsetKw(value: Float): Float =
        value.coerceIn(ENDPOINT_INSET_KW.start, ENDPOINT_INSET_KW.endInclusive)
}

/**
 * Shared per-key setting ranges (audit F-3/F-4/F-10/G-5, 2026-09-06 —
 * the [GeoKnobRanges] pattern applied to the keys whose ranges drifted).
 *
 * Each key's accepted range used to be restated in up to three places — the
 * settings slider, the import validator ([tribixbite.cleverkeys.backup.SettingsValidation])
 * and the Config read-site clamp — and they drifted independently: the
 * validator rejected values the UI legitimately produced (longpress_interval
 * 25..200 vs 5..100; character_size 0.5..2.0 vs 0.75..1.5;
 * custom_border_line_width 0..10 vs 0..5), the two clipboard_max_item_size_kb
 * floors disagreed (UI/state 64 vs Config 1), the slider allowed a 0%
 * space-slider sensitivity that divides by zero in Pointers.Sliding, and
 * clipboard_history_limit's 0..500 bound never ran for its canonical string
 * form. This object is the single source of truth; `SettingsRangeDriftTest`
 * pins every consumer site.
 */
object SettingsRanges {
    /**
     * Keyboard height percent, every orientation and screen (`keyboard_height*`). The sliders
     * have offered exactly this since 2025-09; the reader and backup validation use it too, so an
     * imported out-of-range value cannot produce a height the UI cannot show (audit M1, 2026-10-20).
     */
    val KEYBOARD_HEIGHT: IntRange = 20..60

    /** Key-repeat interval in ms (`longpress_interval`). Matches the slider. */
    val LONGPRESS_INTERVAL: IntRange = 25..200

    /** Primary label scale (`character_size`, stored 0.5–2.0; UI shows percent ×100). */
    val CHARACTER_SIZE: ClosedFloatingPointRange<Float> = 0.5f..2.0f

    /**
     * Custom border line width in dp (`custom_border_line_width`). No Config
     * clamp: the read site converts dp→px via `get_dip_pref`, so the raw dp
     * bound applies at slider + validator only.
     */
    val CUSTOM_BORDER_LINE_WIDTH: ClosedFloatingPointRange<Float> = 0f..10f

    /**
     * Space-slider sensitivity percent (`slider_sensitivity`). The floor is 1,
     * NOT 0: 0 becomes `slide_step_px = 0f` and Pointers.Sliding divides a
     * touch delta by it — ±Infinity, which after Int saturation and the
     * sliderKey short-truncation emits repeat −1 (rightward slide moves the
     * cursor LEFT) or 0 (leftward slide does nothing). F-3.
     */
    val SLIDER_SENSITIVITY_PERCENT: IntRange = 1..100

    /**
     * GH #190: follow-finger selection step in dp (`selection_delete_step_dp`). Shared by
     * the slider, the backup validator and the Config clamp; the floor keeps the step
     * well above touch jitter.
     */
    val SELECTION_DELETE_STEP_DP: IntRange = 4..40

    /** Clipboard history entry cap (`clipboard_history_limit`; 0 = unlimited). */
    val CLIPBOARD_HISTORY_LIMIT: IntRange = 0..500

    /**
     * Clipboard max item size in KB (`clipboard_max_item_size_kb`). Floor 64
     * matches the slider; Config used to floor at 1, so an imported sub-64
     * value displayed as "64KB" while the service enforced the raw value. F-10.
     */
    val CLIPBOARD_MAX_ITEM_SIZE_KB: IntRange = 64..1024

    /**
     * Clipboard media size cap in MB (`clipboard_max_media_size_mb`). F-8:
     * surfaced 2026-09-08 — the value is the historic Config read-site clamp
     * (coerceIn 1..50), now shared by the slider, the import validator and
     * the read site so they cannot drift apart.
     */
    val CLIPBOARD_MAX_MEDIA_SIZE_MB: IntRange = 1..50

    /**
     * Clipboard pane height, percent of the keyboard area (`clipboard_pane_height_percent`).
     * Slider, Config read clamp, settings state and backup validation (audit M8; the key
     * was missing from validation, so any imported int was accepted and then clamped).
     */
    val CLIPBOARD_PANE_HEIGHT_PERCENT: IntRange = 10..50

    /** GIF picker grid columns (`gif_thumbnail_columns`). Missing from validation before M8. */
    val GIF_THUMBNAIL_COLUMNS: IntRange = 2..5

    /**
     * CTC commit-phase beam width (`ctc_beam_width`): settings slider, CTC settings loader,
     * Config clamp, the engine's own defensive clamp and backup validation (audit M8).
     */
    val CTC_BEAM_WIDTH: IntRange = 10..300

    /** ONNX Runtime XNNPACK threads (`onnx_xnnpack_threads`). Missing from validation before M8. */
    val ONNX_XNNPACK_THREADS: IntRange = 1..8

    /** Signed finger-occlusion shift, percent of one key row (`finger_occlusion_offset`). */
    val FINGER_OCCLUSION_OFFSET: IntRange = -25..25

    /**
     * Raw-path moving-average window (`swipe_smoothing_window`; 1 = off). Config now clamps at
     * read too; before M8 only the recognizer did, so Settings could show a value it never used.
     */
    val SWIPE_SMOOTHING_WINDOW: IntRange = 1..7

    /** Bottom margin, percent of screen height (`margin_bottom_*`). */
    val MARGIN_BOTTOM_PERCENT: IntRange = 0..30

    /**
     * Left or right margin, percent of screen width (`margin_left_*` / `margin_right_*`). Each
     * side is capped here; together they never exceed [MARGIN_SIDES_TOTAL_PERCENT].
     */
    val MARGIN_SIDE_PERCENT: IntRange = 0..45

    /** v1.1.74 "90% total horizontal cap": the two side margins together. */
    const val MARGIN_SIDES_TOTAL_PERCENT: Int = 90

    /** Opacity / brightness percent (`label_brightness`, `*_opacity`). */
    val OPACITY_PERCENT: IntRange = 0..100

    /** Circle-gesture sensitivity (`circle_sensitivity`, stored as a String "1".."5"). */
    val CIRCLE_SENSITIVITY: IntRange = 1..5

    /** Shortest word autocorrect touches (`autocorrect_min_word_length`). */
    val AUTOCORRECT_MIN_WORD_LENGTH: IntRange = 2..5

    /** Long-press delay in ms (`longpress_timeout`): the slider since 2025-11. See [ImportEnvelope]. */
    val LONGPRESS_TIMEOUT: IntRange = 200..1000

    /** Short-swipe minimum, percent of key diagonal (`short_gesture_min_distance`). See [ImportEnvelope]. */
    val SHORT_GESTURE_MIN_DISTANCE: IntRange = 10..60

    /** Short/long swipe boundary, percent of key diagonal (`short_gesture_max_distance`). */
    val SHORT_GESTURE_MAX_DISTANCE: IntRange = 50..200

    /** Custom key corner radius, percent of key width (`custom_border_radius`). See [ImportEnvelope]. */
    val CUSTOM_BORDER_RADIUS: IntRange = 0..20

    /** Custom vibration duration in ms (`vibrate_duration`). See [ImportEnvelope]. */
    val VIBRATE_DURATION: IntRange = 5..100

    /**
     * Ranges the backup import ACCEPTS that are wider than today's slider (audit M9,
     * 2026-10-20). Each one is the range of the XML settings screen CleverKeys shipped until
     * ae0ffb00 (2025-12-04), so backups and stored values from those builds can hold them, and
     * every value inside works at runtime. Narrowing would turn those restores into "skipped:
     * out of range" rows, so they stay wide; nothing clamps them on read either, so the stored
     * value IS the effective value (the slider only snaps it if the user drags it).
     */
    object ImportEnvelope {
        /** XML `longpress_timeout` slider, 50–2000 ms; any of it is a usable long-press delay. */
        val LONGPRESS_TIMEOUT: IntRange = 50..2000

        /** XML `short_gesture_min_distance` slider, 10–95 % of the key diagonal. */
        val SHORT_GESTURE_MIN_DISTANCE: IntRange = 10..95

        /**
         * XML `custom_border_radius` slider, 0–100 %. Theme.kt multiplies the fraction by the
         * key width, so 21–100 % are real, rounder corners — not a stale unit.
         */
        val CUSTOM_BORDER_RADIUS: IntRange = 0..100

        /** XML `vibrate_duration` slider, 0–100 ms; 0 makes VibratorCompat skip the custom pulse. */
        val VIBRATE_DURATION: IntRange = 0..100
    }
}

/** A slider's `valueRange` for an int setting range. */
fun IntRange.toSliderRange(): ClosedFloatingPointRange<Float> = first.toFloat()..last.toFloat()

/**
 * One-time cleanup for the stale `vibrate_custom=true` written by the #154 bug.
 *
 * Before the fix (fa00cb0ae / 538122f1d-era), the settings layer force-set
 * `vibrate_custom = true` whenever the master vibration toggle was saved,
 * pushing users onto the slow `VibrationEffect.createOneShot` path instead of
 * the low-latency `performHapticFeedback` default. The forcing code is gone,
 * but existing installs still carry the bug-written flag.
 *
 * Decision logic is a pure function (JVM-testable, no Android deps); the
 * pref wiring lives in [Config.Companion.migrateForcedVibrateCustom].
 */
/**
 * Pure decision logic for the v4 learning-consent migration (2026-09-24). Same
 * extraction pattern as [HapticsMigration]: [Config.migrate] supplies the facts,
 * this object owns the (unit-tested) rules.
 *
 * Background: pre-2.0 the individual learning features defaulted ON behind labeled
 * switches ("Context-Aware Predictions", "Personalized Learning"), and 2.0 adds the
 * `on_device_learning_enabled` master gate whose compile-time default is now OFF.
 * Two consequences this migration owns:
 *
 *  - **Seeding**: an UPGRADING install with no explicit stored choice gets
 *    `on_device_learning_enabled = true` written, so its behavior does not silently
 *    change on update. Fresh installs get nothing — the new opt-in default applies.
 *  - **Selection-history reset**: selection history is the one store whose pre-2.0
 *    writes ignored the learning gate (recorded even with learning off, v1.0–v1.5;
 *    fixed in 2.0), so consented and bug-recorded entries are indistinguishable in
 *    it. Upgrades get [SELECTION_HISTORY_RESET_PENDING_KEY] stamped;
 *    [UserAdaptationManager.consumePendingReset] wipes the store once and clears
 *    the flag. Fresh installs have an empty store — no flag.
 *
 * Fresh-vs-upgrade discriminator: `prefs.contains("version")` at migrate entry —
 * every install that ever ran wrote the config version marker.
 */
object LearningMigration {
    /** Stamped by migrate for upgrades; consumed (and removed) by UserAdaptationManager. */
    const val SELECTION_HISTORY_RESET_PENDING_KEY = "selection_history_reset_pending"

    /** The migration step that introduced both halves. */
    private const val INTRODUCED_IN_VERSION = 4

    fun seedsMasterGateOn(
        isFreshInstall: Boolean,
        savedVersion: Int,
        hasExplicitChoice: Boolean,
    ): Boolean = !isFreshInstall && savedVersion < INTRODUCED_IN_VERSION && !hasExplicitChoice

    fun requestsSelectionHistoryReset(
        isFreshInstall: Boolean,
        savedVersion: Int,
    ): Boolean = !isFreshInstall && savedVersion < INTRODUCED_IN_VERSION
}

/**
 * v5 (2026-09-30): the hold-then-select subkey popover defaults ON for fresh installs
 * (`Defaults.SUBKEY_POPOVER_ENABLED`) but must not change what holding a letter does for
 * someone who already uses the keyboard — for them holding still repeats. Upgrades are seeded
 * OFF once, unless the user already chose (the key exists, e.g. restored from a backup).
 * Same fresh-vs-upgrade discriminator as [LearningMigration].
 */
object SubkeyPopoverMigration {
    const val PREF_KEY = "subkey_popover_enabled"
    private const val INTRODUCED_IN_VERSION = 5

    fun seedsOff(
        isFreshInstall: Boolean,
        savedVersion: Int,
        hasExplicitChoice: Boolean,
    ): Boolean = !isFreshInstall && savedVersion < INTRODUCED_IN_VERSION && !hasExplicitChoice
}

object HapticsMigration {
    /** Persisted on user devices once the migration has run — never rename. */
    const val MIGRATION_MARKER_KEY = "vibrate_custom_migration_v1"

    /**
     * Decide whether the stale `vibrate_custom` flag should be cleared.
     *
     * Clears ONLY when the flag was bug-forced: it is set, the migration has
     * never run, and every custom-path parameter (currently just
     * `vibrate_duration`) is still at its compile-time default — i.e. the
     * user never actually customized anything. If the user changed a
     * parameter, they plausibly chose the custom path; leave it alone.
     * The call site must persist [MIGRATION_MARKER_KEY] unconditionally so
     * this runs exactly once.
     */
    fun shouldClearForcedVibrateCustom(
        vibrateCustom: Boolean,
        alreadyMigrated: Boolean,
        paramsAtDefaults: Boolean
    ): Boolean = !alreadyMigrated && vibrateCustom && paramsAtDefaults
}

class Config private constructor(
    private val _prefs: SharedPreferences,
    res: Resources,
    @JvmField val handler: IKeyEventHandler?,
    foldableUnfolded: Boolean?
) {
    // From resources
    @JvmField val marginTop: Float = res.getDimension(R.dimen.margin_top)
    @JvmField val keyPadding: Float = res.getDimension(R.dimen.key_padding)
    @JvmField val labelTextSize: Float = Defaults.LABEL_TEXT_SIZE_FACTOR
    @JvmField val sublabelTextSize: Float = Defaults.SUBLABEL_TEXT_SIZE_FACTOR
    // #133: user multiplier applied to the secondary (flick) label size only.
    @JvmField var secondary_label_size_scale = Defaults.SECONDARY_LABEL_SIZE_SCALE

    // From preferences.
    // A field that refresh() reads straight from ONE preference is initialised to that
    // preference's Defaults constant, so the pre-refresh value (the seed snapshot below) is the
    // shipped default rather than a second, drifting copy (audit M4, 2026-10-20; pinned by
    // DefaultsSingleDefinitionDriftTest). Derived fields — pixels, scaled opacities, resolved
    // themes — start at 0/false/empty until refresh() computes them.
    // Nullable list preserves indices - null entries represent SystemLayout (use localeTextLayout)
    @JvmField var layouts: List<KeyboardData?> = emptyList()
    @JvmField var show_numpad = false
    @JvmField var inverse_numpad = false
    @JvmField var scale_numpad_height = Defaults.SCALE_NUMPAD_HEIGHT
    @JvmField var add_number_row = false
    @JvmField var number_row_symbols = false
    @JvmField var swipe_dist_px = 0f
    @JvmField var slide_step_px = 0f
    @JvmField var vibrate_custom = Defaults.VIBRATE_CUSTOM
    @JvmField var vibrate_duration = Defaults.VIBRATE_DURATION.toLong()
    // Master haptic toggle - disables ALL vibration when false
    @JvmField var haptic_enabled = Defaults.HAPTIC_ENABLED
    // Per-event haptic feedback toggles (only checked if haptic_enabled is true)
    @JvmField var haptic_key_press = Defaults.HAPTIC_KEY_PRESS
    @JvmField var haptic_prediction_tap = Defaults.HAPTIC_PREDICTION_TAP
    @JvmField var haptic_trackpoint_activate = Defaults.HAPTIC_TRACKPOINT_ACTIVATE
    @JvmField var haptic_long_press = Defaults.HAPTIC_LONG_PRESS
    @JvmField var haptic_swipe_complete = Defaults.HAPTIC_SWIPE_COMPLETE
    @JvmField var longPressTimeout = Defaults.LONGPRESS_TIMEOUT.toLong()
    @JvmField var longPressInterval = Defaults.LONGPRESS_INTERVAL.toLong()
    @JvmField var keyrepeat_enabled = Defaults.KEYREPEAT_ENABLED
    @JvmField var keyrepeat_backspace_only = Defaults.KEYREPEAT_BACKSPACE_ONLY  // #81: Only repeat backspace/nav keys
    @JvmField var margin_bottom = 0f  // In pixels (calculated from % of screen height)
    @JvmField var margin_left = 0f    // In pixels (calculated from % of screen width)
    @JvmField var margin_right = 0f   // In pixels (calculated from % of screen width)
    @JvmField var keyboardHeightPercent = 0
    @JvmField var screenHeightPixels = 0
    @JvmField var screenWidthPixels = 0
    @Deprecated("Use margin_left and margin_right instead")
    @JvmField var horizontal_margin = 0f
    @JvmField var key_vertical_margin = 0f
    @JvmField var key_horizontal_margin = 0f
    @JvmField var labelBrightness = 0
    @JvmField var keyboardOpacity = 0
    @JvmField var customBorderRadius = 0f
    @JvmField var customBorderLineWidth = 0f
    @JvmField var keyOpacity = 0
    @JvmField var keyActivatedOpacity = 0
    @JvmField var double_tap_lock_shift = Defaults.DOUBLE_TAP_LOCK_SHIFT
    @JvmField var characterSize = 0f
    /** Android style id of the EFFECTIVE theme ([themeName]); what the keyboard view inflates with. */
    @JvmField var theme = 0
    /**
     * The EFFECTIVE theme id — what the keyboard renders. Equals the user's global `theme`
     * preference unless the current layout carries a theme binding to a theme that exists on this
     * device ([LayoutThemeBinding.resolve]). Theme Manager reads/writes the PREFERENCE, never this.
     */
    @JvmField var themeName: String = ""
    /** The user's global `theme` preference — the theme of every layout that has no binding. */
    private var user_theme_name = Defaults.THEME
    /**
     * Each layout's EFFECTIVE theme binding (null = follows the global theme), index-aligned with
     * [layouts]. Resolved by [LayoutThemeBinding.effective] when the layouts are loaded.
     */
    @JvmField var layout_themes: List<String?> = emptyList()
    /** Non-null while the current layout's theme binding is rendered (then == [themeName]). */
    @JvmField var layout_bound_theme: String? = null
    /**
     * Non-null when the current layout is bound to a theme that is not on this device; the global
     * theme is rendered instead. Shown by Layout Manager; never written back to the binding.
     */
    @JvmField var layout_missing_theme: String? = null
    @JvmField var autocapitalisation = Defaults.AUTOCAPITALISATION
    @JvmField var switch_input_immediate = Defaults.SWITCH_INPUT_IMMEDIATE
    /** #139: Enter inserts a newline in multi-line fields even when the app asks for an action. */
    @JvmField var enter_always_newline = Defaults.ENTER_ALWAYS_NEWLINE
    @JvmField var selected_number_layout: NumberLayout? = null
    @JvmField var borderConfig = Defaults.BORDER_CONFIG
    @JvmField var circle_sensitivity = Defaults.CIRCLE_SENSITIVITY_FALLBACK
    @JvmField var clipboard_history_enabled = Defaults.CLIPBOARD_HISTORY_ENABLED
    @JvmField var clipboard_history_limit = Defaults.CLIPBOARD_HISTORY_LIMIT_FALLBACK
    @JvmField var clipboard_pane_height_percent = Defaults.CLIPBOARD_PANE_HEIGHT_PERCENT
    @JvmField var clipboard_max_item_size_kb = Defaults.CLIPBOARD_MAX_ITEM_SIZE_KB_FALLBACK
    @JvmField var clipboard_limit_type: String? = Defaults.CLIPBOARD_LIMIT_TYPE
    @JvmField var clipboard_size_limit_mb = Defaults.CLIPBOARD_SIZE_LIMIT_MB_FALLBACK
    @JvmField var clipboard_exclude_password_managers = Defaults.CLIPBOARD_EXCLUDE_PASSWORD_MANAGERS  // Skip clipboard from password managers
    @JvmField var clipboard_respect_sensitive_flag = Defaults.CLIPBOARD_RESPECT_SENSITIVE_FLAG  // #86: Respect Android 13+ IS_SENSITIVE flag
    @JvmField var clipboard_history_duration = Defaults.CLIPBOARD_HISTORY_DURATION_FALLBACK  // Minutes; -1 = never expire
    @JvmField var clipboard_text_only = Defaults.CLIPBOARD_TEXT_ONLY  // v4: Hide media entries from all tabs (text-only display)
    @JvmField var clipboard_pinned_enabled = Defaults.CLIPBOARD_PINNED_ENABLED  // v4: Show/hide pinned tab
    @JvmField var clipboard_todo_enabled = Defaults.CLIPBOARD_TODO_ENABLED  // v4: Show/hide todo tab
    @JvmField var clipboard_media_enabled = Defaults.CLIPBOARD_MEDIA_ENABLED  // v4: Enable media clipboard (images, videos, PDFs)
    @JvmField var clipboard_max_media_size_mb = Defaults.CLIPBOARD_MAX_MEDIA_SIZE_MB  // v4: Max media file size in MB

    // URL sanitization toggles (Chunk 3 — applies to clipboard text inserts only)
    @JvmField var clipboard_sanitize_links_enabled = Defaults.CLIPBOARD_SANITIZE_LINKS_ENABLED
    @JvmField var clipboard_embed_enrich_enabled = Defaults.CLIPBOARD_EMBED_ENRICH_ENABLED
    @JvmField var clipboard_custom_rules_enabled = Defaults.CLIPBOARD_CUSTOM_RULES_ENABLED
    @JvmField var clipboard_custom_rules_uri: String? = null   // SAF persisted URI as String, null = no file picked
    // When sanitization actually cleans a copied URL, also overwrite the Android system
    // clipboard with the cleaned form so pastes from ANY app deliver the sanitized URL
    // (not just CleverKeys' own clipboard panel). Default ON — only acts when a sanitize
    // toggle is enabled AND a URL was actually changed, so it's a no-op otherwise.
    @JvmField var clipboard_sanitize_system_clipboard = Defaults.CLIPBOARD_SANITIZE_SYSTEM_CLIPBOARD

    // GIF Panel
    @JvmField var gif_enabled = Defaults.GIF_ENABLED
    @JvmField var gif_thumbnail_columns = Defaults.GIF_THUMBNAIL_COLUMNS

    @JvmField var continuous_swipe_enabled = Defaults.CONTINUOUS_SWIPE_ENABLED
    @JvmField var swipe_typing_enabled = Defaults.SWIPE_TYPING_ENABLED
    @JvmField var swipe_on_password_fields = Defaults.SWIPE_ON_PASSWORD_FIELDS  // #39: Reenable swipe typing on password fields
    @JvmField var swipe_show_debug_scores = Defaults.SWIPE_SHOW_DEBUG_SCORES
    @JvmField var suggestion_provenance_markers = Defaults.SUGGESTION_PROVENANCE_MARKERS // Opt-in origin markers in the suggestion bar (audit §2.3 Tier 2)
    @JvmField var show_exact_typed_word = Defaults.SHOW_EXACT_TYPED_WORD  // #42: Tap-to-add exact typed word to dictionary
    @JvmField var word_prediction_enabled = Defaults.WORD_PREDICTION_ENABLED
    @JvmField var suggestion_bar_opacity = Defaults.SUGGESTION_BAR_OPACITY

    // Word prediction scoring weights
    @JvmField var prediction_context_boost = Defaults.PREDICTION_CONTEXT_BOOST
    @JvmField var prediction_frequency_scale = Defaults.PREDICTION_FREQUENCY_SCALE
    @JvmField var context_aware_predictions_enabled = Defaults.CONTEXT_AWARE_PREDICTIONS_ENABLED // Phase 7.1: Dynamic N-gram learning
    @JvmField var personalized_learning_enabled = Defaults.PERSONALIZED_LEARNING_ENABLED // Phase 7.2: Personalized word frequency learning
    @JvmField var on_device_learning_enabled = Defaults.ON_DEVICE_LEARNING_ENABLED // MASTER privacy gate over ALL typing-behavior learning (Task A 2026-08-06; opt-in on fresh installs since v2.0, upgrades seeded ON by the v4 migration)
    @JvmField var next_word_prediction_enabled = Defaults.NEXT_WORD_PREDICTION_ENABLED // Gboard-style next-word: shipped LM, plus learned context when learning is on
    @JvmField var context_source = Defaults.CONTEXT_SOURCE // which context LM feeds scoring: both | learned_only | static_only
    @JvmField var personalization_weight = Defaults.PERSONALIZATION_WEIGHT // continuous personalization strength (0=off … 2=double)
    @JvmField var personalization_max_words = Defaults.PERSONALIZATION_MAX_WORDS // learned-vocabulary size cap
    @JvmField var learning_aggression = Defaults.LEARNING_AGGRESSION // Phase 7.2: Learning aggression level

    // Multi-language support (Phase 8.3 & 8.4)
    @JvmField var enable_multilang = Defaults.ENABLE_MULTILANG // Phase 8.3: Enable multi-language support
    /**
     * The ACTIVE primary language — what prediction, autocorrect, learning, the n-gram/static LM,
     * contractions and swipe routing serve. Equals `pref_primary_language` unless the current
     * layout carries a language binding (GH #186/#61), in which case it is the bound language.
     * Settings and the language toggles read/write the PREFERENCE, never this field.
     */
    @JvmField var primary_language = Defaults.PRIMARY_LANGUAGE
    /**
     * GH #186/#61: each layout's EFFECTIVE language binding (null = unbound), index-aligned with
     * [layouts]. Resolved by [LayoutLanguageBinding.effective] when the layouts are loaded.
     */
    @JvmField var layout_languages: List<String?> = emptyList()
    /** `pref_primary_language` — the user's choice for unbound layouts. */
    private var user_primary_language = Defaults.PRIMARY_LANGUAGE
    /** `pref_secondary_language` ("none" = no secondary). */
    private var user_secondary_language = Defaults.SECONDARY_LANGUAGE
    /**
     * The ACTIVE secondary language, or null for single-language typing: the preference
     * secondary while Multi-Language is on and the current layout is unbound; always null on a
     * bound layout (#61 — no word mixing). Every consumer that loads or merges a secondary
     * reads THIS, not the preferences.
     */
    @JvmField var active_secondary_language: String? = null
    /** Non-null while the current layout is bound to a language (then == [primary_language]). */
    @JvmField var layout_bound_language: String? = null
    @JvmField var auto_detect_language = Defaults.AUTO_DETECT_LANGUAGE // Phase 8.3: Auto-detect language from context
    @JvmField var language_detection_sensitivity = Defaults.LANGUAGE_DETECTION_SENSITIVITY // Phase 8.3: Detection sensitivity (0.0-1.0)
    @JvmField var secondary_prediction_weight = Defaults.SECONDARY_PREDICTION_WEIGHT // v1.1.94: Weight for secondary predictions

    // Auto-correction settings
    @JvmField var autocorrect_enabled = Defaults.AUTOCORRECT_ENABLED
    @JvmField var autocorrect_min_word_length = Defaults.AUTOCORRECT_MIN_WORD_LENGTH
    @JvmField var autocorrect_char_match_threshold = Defaults.AUTOCORRECT_CHAR_MATCH_THRESHOLD
    @JvmField var autocorrect_confidence_min_frequency = Defaults.AUTOCORRECT_MIN_FREQUENCY
    /** Issue #72: Auto-capitalize "I" and contractions (i'm → I'm, i'll → I'll) */
    @JvmField var autocapitalize_i_words = Defaults.AUTOCAPITALIZE_I_WORDS

    // Fuzzy matching configuration
    @JvmField var autocorrect_max_length_diff = Defaults.AUTOCORRECT_MAX_LENGTH_DIFF
    @JvmField var autocorrect_prefix_length = Defaults.AUTOCORRECT_PREFIX_LENGTH

    // Swipe scoring weights

    /**
     * Every autocorrect weight and switch beyond the basic settings above, built in [refresh]
     * from prefs. Null (an Objenesis test Config) means `AutocorrectTuning.DEFAULT`; read it
     * through `AutocorrectTuning.of(config)`.
     */
    @JvmField var autocorrect_tuning: AutocorrectTuning? = null

    // Swipe autocorrect configuration
    @JvmField var swipe_final_autocorrect_enabled = Defaults.SWIPE_FINAL_AUTOCORRECT_ENABLED

    // Short gesture configuration
    @JvmField var short_gestures_enabled = Defaults.SHORT_GESTURES_ENABLED
    // Unit-typed (PercentOfKey): consumers must convert via .toPx(keyDiagonal) — raw
    // px-vs-percent comparisons no longer compile. (Value classes can't be @JvmField;
    // the codebase is pure Kotlin, so nothing needed the Java field exposure.)
    var short_gesture_min_distance = PercentOfKey(Defaults.SHORT_GESTURE_MIN_DISTANCE)
    var short_gesture_max_distance = PercentOfKey(Defaults.SHORT_GESTURE_MAX_DISTANCE) // The short/long boundary (50-200): at/below = short swipe, beyond = word swipe. ("200=disabled" was a UI label that was never implemented; retired.)
    /**
     * EFFECTIVE "short swipes only" mode (GH #147/#87): the `short_swipes_only` preference AND
     * [short_gestures_enabled] (the mode is a variant of short swipes, so it means nothing with
     * them off). While true, [refresh] also forces [swipe_typing_enabled] false — a gesture
     * cannot be both "always a short swipe" and "a word swipe past the boundary". The stored
     * `swipe_typing_enabled` preference is untouched, so turning the mode off restores it.
     */
    @JvmField var short_swipes_only = Defaults.SHORT_SWIPES_ONLY

    // Hold-then-select subkey popover
    @JvmField var subkey_popover_enabled = Defaults.SUBKEY_POPOVER_ENABLED
    @JvmField var subkey_popover_neutral_width = Defaults.SUBKEY_POPOVER_NEUTRAL_WIDTH
    @JvmField var subkey_popover_neutral_height = Defaults.SUBKEY_POPOVER_NEUTRAL_HEIGHT

    // Selection-delete mode configuration (backspace swipe+hold)
    @JvmField var selection_delete_vertical_threshold = Defaults.SELECTION_DELETE_VERTICAL_THRESHOLD  // % of key height to trigger vertical selection
    @JvmField var selection_delete_vertical_speed = Defaults.SELECTION_DELETE_VERTICAL_SPEED    // Speed multiplier for vertical (0.1-1.0)
    /** GH #190: true = follow-finger (position-proportional) selection, false = speed by distance. */
    @JvmField var selection_delete_follow_finger = Defaults.SELECTION_DELETE_MODE == Defaults.SELECTION_DELETE_MODE_FOLLOW
    /** GH #190: follow-finger step, px per character (from `selection_delete_step_dp`). */
    @JvmField var selection_delete_step_px = 0f

    // Gesture timing configuration (exposed hardcoded constants)
    @JvmField var tap_duration_threshold = Defaults.TAP_DURATION_THRESHOLD.toLong() // Max duration for a tap gesture (ms)
    @JvmField var double_space_to_period = Defaults.DOUBLE_SPACE_TO_PERIOD // Enable double-space-to-period feature
    @JvmField var double_space_threshold = Defaults.DOUBLE_SPACE_THRESHOLD.toLong() // Max time between spaces for period replacement (ms)
    @JvmField var smart_punctuation = Defaults.SMART_PUNCTUATION // Attach punctuation to end of last word (no space before)
    @JvmField var swipe_min_dwell_time = Defaults.SWIPE_MIN_DWELL_TIME.toLong() // Min time to register a key during swipe (ms)
    @JvmField var swipe_noise_threshold = Defaults.SWIPE_NOISE_THRESHOLD // Min distance to register movement (pixels)
    @JvmField var swipe_high_velocity_threshold = Defaults.SWIPE_HIGH_VELOCITY_THRESHOLD // Velocity threshold for fast swipes (px/sec)
    @JvmField var swipe_min_distance = Defaults.SWIPE_MIN_DISTANCE // Minimum total distance to recognize a swipe (pixels)
    @JvmField var swipe_min_key_distance = Defaults.SWIPE_MIN_KEY_DISTANCE // Minimum distance between keys during swipe (pixels)

    // Slider speed configuration
    @JvmField var slider_speed_smoothing = Defaults.SLIDER_SPEED_SMOOTHING // Smoothing factor for slider speed (0.0-1.0)
    @JvmField var slider_speed_max = Defaults.SLIDER_SPEED_MAX // Maximum slider speed multiplier

    // Swipe trail appearance
    @JvmField var swipe_trail_enabled = Defaults.SWIPE_TRAIL_ENABLED // Show swipe trail during gesture
    @JvmField var swipe_trail_effect = Defaults.SWIPE_TRAIL_EFFECT // Trail effect: none, solid, glow, rainbow, fade
    @JvmField var swipe_trail_color = Defaults.SWIPE_TRAIL_COLOR // Trail color
    @JvmField var swipe_trail_width = Defaults.SWIPE_TRAIL_WIDTH // Trail stroke width in dp
    @JvmField var swipe_trail_glow_radius = Defaults.SWIPE_TRAIL_GLOW_RADIUS // Glow effect radius in dp (smaller = crisper)

    // Swipe prediction configuration
    @JvmField var swipe_debug_detailed_logging = Defaults.SWIPE_DEBUG_DETAILED_LOGGING
    // WP9 R-1 step 7 (v1.2): swipe engine mode — "ctc" | "geometric".
    // G5 adds "ctc".
    @JvmField var swipe_engine_mode = Defaults.SWIPE_ENGINE_MODE
    // Full Geometric Settings knobs (read by GeometricEngineAdapter per decode).
    @JvmField var geo_max_results = Defaults.GEO_MAX_RESULTS
    @JvmField var geo_frequency_weight = Defaults.GEO_FREQUENCY_WEIGHT
    @JvmField var geo_endpoint_inset_kw = Defaults.GEO_ENDPOINT_INSET_KW
    // CTC engine knob (read by CtcEngineAdapter per decode).
    @JvmField var ctc_beam_width = Defaults.CTC_BEAM_WIDTH
    @JvmField var swipe_context_rescoring = Defaults.SWIPE_CONTEXT_RESCORING
    @Volatile @JvmField var custom_terminal_packages: Set<String> = emptySet()
    @JvmField var auto_space_after_suggestion = Defaults.AUTO_SPACE_AFTER_SUGGESTION  // Add trailing space after selecting suggestion
    @JvmField var auto_space_before_suggestion = Defaults.AUTO_SPACE_BEFORE_SUGGESTION  // Add leading space before tapped suggestion
    @JvmField var backspace_undo_swipe = Defaults.BACKSPACE_UNDO_SWIPE  // Backspace after swipe deletes entire swiped word
    @JvmField var backspace_undo_autocorrect = Defaults.BACKSPACE_UNDO_AUTOCORRECT  // #110: Backspace after autocorrect reverts to original word

    // Beam search tuning
    @JvmField var swipe_smoothing_window = Defaults.SWIPE_SMOOTHING_WINDOW

    /** Signed percent of one key row to shift raw touch Y by. 0 = off. See refresh(). */
    @JvmField var finger_occlusion_offset = Defaults.FINGER_OCCLUSION_OFFSET

    // Swipe trajectory resampling
    // NOTE: Custom encoder/decoder paths removed - feature not implemented

    // Language-specific prefix boost (for non-English primary languages)

    // ONNX Runtime settings (requires app restart to take effect)
    @JvmField var onnx_xnnpack_threads = Defaults.ONNX_XNNPACK_THREADS

    // Dynamically set
    @JvmField var shouldOfferVoiceTyping = false
    @JvmField var actionLabel: String? = null
    @JvmField var actionId = 0
    @JvmField var swapEnterActionKey = false
    @JvmField var extra_keys_subtype: ExtraKeys? = null
    @JvmField var extra_keys_param: Map<KeyValue, KeyboardData.PreferredPos> = emptyMap()
    @JvmField var extra_keys_custom: Map<KeyValue, KeyboardData.PreferredPos> = emptyMap()

    @JvmField var orientation_landscape = false
    @JvmField var foldable_unfolded = false
    @JvmField var wide_screen = false
    @JvmField var version = 0

    private var current_layout_narrow = 0
    private var current_layout_wide = 0

    /**
     * Immutable capture of the fields the touch and draw hot paths read (ARC-072).
     *
     * [refresh] rewrites the vars above one assignment at a time, on whatever thread asked
     * for the config change; a gesture or frame already in flight keeps reading them as they
     * change and can mix old and new values. Hot-path consumers therefore capture this
     * reference ONCE per unit of work (a gesture at pointer-down, a frame at draw start) and
     * read every value from the captured object, so a refresh applies cleanly from the next
     * unit onward. See `prefs/ConfigSnapshot.kt`.
     *
     * Seeded here from the declared field defaults purely so the property is non-null for the
     * whole of `init`; the [refresh] call below immediately replaces it with the real
     * preferences-backed capture. Rebuilt only on config change — never per touch or frame.
     */
    @Volatile
    var snapshot: ConfigSnapshot = buildSnapshot()
        private set

    init {
        repairCorruptedFloatPreferences(_prefs)
        refresh(res, foldableUnfolded)
    }

    fun refresh(res: Resources, foldableUnfolded: Boolean?) {
        version++
        val dm = res.displayMetrics
        orientation_landscape = res.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        this.foldable_unfolded = foldableUnfolded ?: false

        var characterSizeScale = 1f
        val show_numpad_s = safeGetString(_prefs, "show_numpad", Defaults.SHOW_NUMPAD)
        show_numpad = "always" == show_numpad_s

        if (orientation_landscape) {
            if ("landscape" == show_numpad_s) show_numpad = true
            keyboardHeightPercent = KeyboardHeightPrefs.percent(_prefs, landscape = true, unfolded = this.foldable_unfolded)
            characterSizeScale = 1.25f
        } else {
            keyboardHeightPercent = KeyboardHeightPrefs.percent(_prefs, landscape = false, unfolded = this.foldable_unfolded)
        }

        // Keep nulls - they represent SystemLayout entries (resolved to localeTextLayout at runtime).
        // GH #186/#61: each entry's effective language binding is loaded in the same pass.
        // Per-layout theme bindings are loaded in the same pass.
        val (loadedLayouts, loadedLanguages, loadedThemes) = LayoutsPreference.loadLayoutsWithBindings(res, _prefs)
        layouts = loadedLayouts
        layout_languages = loadedLanguages
        layout_themes = loadedThemes
        inverse_numpad = safeGetString(_prefs, "numpad_layout", Defaults.NUMPAD_LAYOUT) == "low_first"
        scale_numpad_height = _prefs.getBoolean("scale_numpad_height", Defaults.SCALE_NUMPAD_HEIGHT)

        val number_row = safeGetString(_prefs, "number_row", Defaults.NUMBER_ROW)
        add_number_row = number_row != "no_number_row"
        number_row_symbols = number_row == "symbols"

        val dpi_ratio = maxOf(dm.xdpi, dm.ydpi) / minOf(dm.xdpi, dm.ydpi)
        val swipe_scaling = minOf(dm.widthPixels, dm.heightPixels) / 10f * dpi_ratio
        val swipe_dist_value = safeGetString(_prefs, "swipe_dist", Defaults.SWIPE_DIST).toFloatOrNull() ?: Defaults.SWIPE_DIST_FALLBACK
        swipe_dist_px = swipe_dist_value / 25f * swipe_scaling

        // F-3: floor at 1% (SettingsRanges) — 0 makes slide_step_px 0 and
        // Pointers.Sliding divides by it (right-swipe emits repeat −1).
        val slider_sensitivity = (safeGetString(_prefs, "slider_sensitivity", Defaults.SLIDER_SENSITIVITY).toFloatOrNull() ?: Defaults.SLIDER_SENSITIVITY_FALLBACK)
            .coerceIn(
                SettingsRanges.SLIDER_SENSITIVITY_PERCENT.first.toFloat(),
                SettingsRanges.SLIDER_SENSITIVITY_PERCENT.last.toFloat()
            ) / 100f
        slide_step_px = slider_sensitivity * swipe_scaling

        vibrate_custom = _prefs.getBoolean("vibrate_custom", Defaults.VIBRATE_CUSTOM)
        vibrate_duration = safeGetInt(_prefs, "vibrate_duration", Defaults.VIBRATE_DURATION).toLong()
        // Master haptic toggle - "vibration_enabled" key from Settings UI
        haptic_enabled = _prefs.getBoolean("vibration_enabled", Defaults.HAPTIC_ENABLED)
        // Per-event haptic feedback toggles (only matter if haptic_enabled is true)
        haptic_key_press = _prefs.getBoolean("haptic_key_press", Defaults.HAPTIC_KEY_PRESS)
        haptic_prediction_tap = _prefs.getBoolean("haptic_prediction_tap", Defaults.HAPTIC_PREDICTION_TAP)
        haptic_trackpoint_activate = _prefs.getBoolean("haptic_trackpoint_activate", Defaults.HAPTIC_TRACKPOINT_ACTIVATE)
        haptic_long_press = _prefs.getBoolean("haptic_long_press", Defaults.HAPTIC_LONG_PRESS)
        haptic_swipe_complete = _prefs.getBoolean("haptic_swipe_complete", Defaults.HAPTIC_SWIPE_COMPLETE)
        longPressTimeout = safeGetInt(_prefs, "longpress_timeout", Defaults.LONGPRESS_TIMEOUT).toLong()
        longPressInterval = safeGetInt(_prefs, "longpress_interval", Defaults.LONGPRESS_INTERVAL)
            .coerceIn(SettingsRanges.LONGPRESS_INTERVAL.first, SettingsRanges.LONGPRESS_INTERVAL.last).toLong()
        keyrepeat_enabled = _prefs.getBoolean("keyrepeat_enabled", Defaults.KEYREPEAT_ENABLED)
        keyrepeat_backspace_only = _prefs.getBoolean("keyrepeat_backspace_only", Defaults.KEYREPEAT_BACKSPACE_ONLY)

        // Screen dimensions for percentage calculations
        screenHeightPixels = dm.heightPixels
        screenWidthPixels = dm.widthPixels

        // Margin settings (percentage of screen dimensions)
        margin_bottom = get_percent_pref_oriented_height(
            "margin_bottom",
            Defaults.MARGIN_BOTTOM_PORTRAIT,
            Defaults.MARGIN_BOTTOM_LANDSCAPE
        )
        margin_left = get_percent_pref_oriented_width(
            "margin_left",
            Defaults.MARGIN_LEFT_PORTRAIT,
            Defaults.MARGIN_LEFT_LANDSCAPE
        )
        margin_right = get_percent_pref_oriented_width(
            "margin_right",
            Defaults.MARGIN_RIGHT_PORTRAIT,
            Defaults.MARGIN_RIGHT_LANDSCAPE
        )
        // Legacy fallback for horizontal_margin (used by Theme.kt)
        @Suppress("DEPRECATION")
        horizontal_margin = (margin_left + margin_right) / 2

        key_vertical_margin = get_dip_pref(dm, "key_vertical_margin", Defaults.KEY_VERTICAL_MARGIN) / 100
        key_horizontal_margin = get_dip_pref(dm, "key_horizontal_margin", Defaults.KEY_HORIZONTAL_MARGIN) / 100

        labelBrightness = safeGetInt(_prefs, "label_brightness", Defaults.LABEL_BRIGHTNESS) * 255 / 100
        keyboardOpacity = safeGetInt(_prefs, "keyboard_opacity", Defaults.KEYBOARD_OPACITY) * 255 / 100
        keyOpacity = safeGetInt(_prefs, "key_opacity", Defaults.KEY_OPACITY) * 255 / 100
        keyActivatedOpacity = safeGetInt(_prefs, "key_activated_opacity", Defaults.KEY_ACTIVATED_OPACITY) * 255 / 100

        borderConfig = _prefs.getBoolean("border_config", Defaults.BORDER_CONFIG)
        customBorderRadius = _prefs.getInt("custom_border_radius", Defaults.CUSTOM_BORDER_RADIUS) / 100f
        customBorderLineWidth = get_dip_pref(dm, "custom_border_line_width", Defaults.CUSTOM_BORDER_LINE_WIDTH.toFloat())
        double_tap_lock_shift = _prefs.getBoolean("lock_double_tap", Defaults.DOUBLE_TAP_LOCK_SHIFT)
        characterSize = safeGetFloat(_prefs, "character_size", Defaults.CHARACTER_SIZE)
            .coerceIn(SettingsRanges.CHARACTER_SIZE.start, SettingsRanges.CHARACTER_SIZE.endInclusive) * characterSizeScale
        secondary_label_size_scale = safeGetFloat(_prefs, "secondary_label_size_scale", Defaults.SECONDARY_LABEL_SIZE_SCALE)
        // The global theme preference. The EFFECTIVE theme (themeName/theme) is resolved at the
        // end of refresh, once the layouts, the selection and wide_screen are known.
        user_theme_name = safeGetString(_prefs, "theme", Defaults.THEME)
        autocapitalisation = _prefs.getBoolean("autocapitalisation", Defaults.AUTOCAPITALISATION)
        switch_input_immediate = _prefs.getBoolean("switch_input_immediate", Defaults.SWITCH_INPUT_IMMEDIATE)
        enter_always_newline = safeGetBoolean(_prefs, "enter_always_newline", Defaults.ENTER_ALWAYS_NEWLINE)
        extra_keys_param = ExtraKeysPreference.get_extra_keys(_prefs) ?: emptyMap()
        extra_keys_custom = CustomExtraKeysPreference.get(_prefs) ?: emptyMap()
        selected_number_layout = NumberLayout.of_string(safeGetString(_prefs, "number_entry_layout", Defaults.NUMBER_ENTRY_LAYOUT))
        current_layout_narrow = safeGetInt(_prefs, "current_layout_portrait", 0)
        current_layout_wide = safeGetInt(_prefs, "current_layout_landscape", 0)
        circle_sensitivity = safeGetString(_prefs, "circle_sensitivity", Defaults.CIRCLE_SENSITIVITY).toIntOrNull() ?: Defaults.CIRCLE_SENSITIVITY_FALLBACK
        clipboard_history_enabled = _prefs.getBoolean("clipboard_history_enabled", Defaults.CLIPBOARD_HISTORY_ENABLED)

        // G-5: read-site clamp — pre-fix imports could have written out-of-range
        // (or negative) values that the string-form validator used to accept.
        clipboard_history_limit = (safeGetString(_prefs, "clipboard_history_limit", Defaults.CLIPBOARD_HISTORY_LIMIT).toIntOrNull() ?: Defaults.CLIPBOARD_HISTORY_LIMIT_FALLBACK)
            .coerceIn(SettingsRanges.CLIPBOARD_HISTORY_LIMIT.first, SettingsRanges.CLIPBOARD_HISTORY_LIMIT.last)

        clipboard_pane_height_percent = safeGetInt(_prefs, "clipboard_pane_height_percent", Defaults.CLIPBOARD_PANE_HEIGHT_PERCENT)
            .coerceIn(SettingsRanges.CLIPBOARD_PANE_HEIGHT_PERCENT)

        // F-10: same 64..1024 clamp as the Settings UI/state side (SettingsRanges) —
        // the old floor of 1 let an imported sub-64 value display as "64KB" while
        // the service enforced the raw value.
        clipboard_max_item_size_kb = (safeGetString(_prefs, "clipboard_max_item_size_kb", Defaults.CLIPBOARD_MAX_ITEM_SIZE_KB).toIntOrNull() ?: Defaults.CLIPBOARD_MAX_ITEM_SIZE_KB_FALLBACK)
            .coerceIn(SettingsRanges.CLIPBOARD_MAX_ITEM_SIZE_KB.first, SettingsRanges.CLIPBOARD_MAX_ITEM_SIZE_KB.last)

        clipboard_limit_type = safeGetString(_prefs, "clipboard_limit_type", Defaults.CLIPBOARD_LIMIT_TYPE)

        clipboard_size_limit_mb = safeGetString(_prefs, "clipboard_size_limit_mb", Defaults.CLIPBOARD_SIZE_LIMIT_MB).toIntOrNull() ?: Defaults.CLIPBOARD_SIZE_LIMIT_MB_FALLBACK

        clipboard_exclude_password_managers = _prefs.getBoolean("clipboard_exclude_password_managers", Defaults.CLIPBOARD_EXCLUDE_PASSWORD_MANAGERS)

        clipboard_history_duration = safeGetString(_prefs, "clipboard_history_duration", Defaults.CLIPBOARD_HISTORY_DURATION).toIntOrNull() ?: Defaults.CLIPBOARD_HISTORY_DURATION_FALLBACK

        clipboard_respect_sensitive_flag = _prefs.getBoolean("clipboard_respect_sensitive_flag", Defaults.CLIPBOARD_RESPECT_SENSITIVE_FLAG)

        // v4: Clipboard feature toggles
        clipboard_text_only = _prefs.getBoolean("clipboard_text_only", Defaults.CLIPBOARD_TEXT_ONLY)
        clipboard_pinned_enabled = _prefs.getBoolean("clipboard_pinned_enabled", Defaults.CLIPBOARD_PINNED_ENABLED)
        clipboard_todo_enabled = _prefs.getBoolean("clipboard_todo_enabled", Defaults.CLIPBOARD_TODO_ENABLED)
        clipboard_media_enabled = _prefs.getBoolean("clipboard_media_enabled", Defaults.CLIPBOARD_MEDIA_ENABLED)
        // F-8: clamp through the shared range (slider + validator + this read site agree)
        clipboard_max_media_size_mb = safeGetInt(_prefs, "clipboard_max_media_size_mb", Defaults.CLIPBOARD_MAX_MEDIA_SIZE_MB)
            .coerceIn(SettingsRanges.CLIPBOARD_MAX_MEDIA_SIZE_MB.first, SettingsRanges.CLIPBOARD_MAX_MEDIA_SIZE_MB.last)

        // URL sanitization toggles (Chunk 3): ONE read implementation, shared with the
        // mid-session reload the clipboard service triggers (audit M3).
        reloadSanitizationSettings()

        // GIF Panel
        gif_enabled = _prefs.getBoolean("gif_enabled", Defaults.GIF_ENABLED)
        gif_thumbnail_columns = safeGetInt(_prefs, "gif_thumbnail_columns", Defaults.GIF_THUMBNAIL_COLUMNS)
            .coerceIn(SettingsRanges.GIF_THUMBNAIL_COLUMNS)

        continuous_swipe_enabled = safeGetBoolean(_prefs, "continuous_swipe_enabled", Defaults.CONTINUOUS_SWIPE_ENABLED)
        swipe_typing_enabled = _prefs.getBoolean("swipe_typing_enabled", Defaults.SWIPE_TYPING_ENABLED)
        swipe_on_password_fields = _prefs.getBoolean("swipe_on_password_fields", Defaults.SWIPE_ON_PASSWORD_FIELDS)
        swipe_show_debug_scores = _prefs.getBoolean("swipe_show_debug_scores", Defaults.SWIPE_SHOW_DEBUG_SCORES)
        suggestion_provenance_markers = _prefs.getBoolean("suggestion_provenance_markers", Defaults.SUGGESTION_PROVENANCE_MARKERS)
        show_exact_typed_word = _prefs.getBoolean("show_exact_typed_word", Defaults.SHOW_EXACT_TYPED_WORD)
        word_prediction_enabled = _prefs.getBoolean("word_prediction_enabled", Defaults.WORD_PREDICTION_ENABLED)
        suggestion_bar_opacity = safeGetInt(_prefs, "suggestion_bar_opacity", Defaults.SUGGESTION_BAR_OPACITY)

        prediction_context_boost = safeGetFloat(_prefs, "prediction_context_boost", Defaults.PREDICTION_CONTEXT_BOOST)
        prediction_frequency_scale = safeGetFloat(_prefs, "prediction_frequency_scale", Defaults.PREDICTION_FREQUENCY_SCALE)
        context_aware_predictions_enabled = _prefs.getBoolean("context_aware_predictions_enabled", Defaults.CONTEXT_AWARE_PREDICTIONS_ENABLED)
        personalized_learning_enabled = _prefs.getBoolean("personalized_learning_enabled", Defaults.PERSONALIZED_LEARNING_ENABLED)
        on_device_learning_enabled = _prefs.getBoolean("on_device_learning_enabled", Defaults.ON_DEVICE_LEARNING_ENABLED)
        next_word_prediction_enabled = _prefs.getBoolean("next_word_prediction_enabled", Defaults.NEXT_WORD_PREDICTION_ENABLED)
        context_source = safeGetString(_prefs, "context_source", Defaults.CONTEXT_SOURCE)
        personalization_weight = safeGetFloat(_prefs, "personalization_weight", Defaults.PERSONALIZATION_WEIGHT)
        personalization_max_words = safeGetInt(_prefs, "personalization_max_words", Defaults.PERSONALIZATION_MAX_WORDS)
        learning_aggression = safeGetString(_prefs, "learning_aggression", Defaults.LEARNING_AGGRESSION)

        // Multi-language settings (Phase 8.3 & 8.4)
        enable_multilang = _prefs.getBoolean("pref_enable_multilang", Defaults.ENABLE_MULTILANG)
        // The user's language choices; the ACTIVE languages are resolved against the current
        // layout's binding at the end of refresh (recomputeActiveLanguages).
        user_primary_language = safeGetString(_prefs, "pref_primary_language", Defaults.PRIMARY_LANGUAGE)
        user_secondary_language = safeGetString(_prefs, "pref_secondary_language", Defaults.SECONDARY_LANGUAGE)
        auto_detect_language = _prefs.getBoolean("pref_auto_detect_language", Defaults.AUTO_DETECT_LANGUAGE)
        // SlideBarPreference stores as Float (0.4-0.9), not Int
        language_detection_sensitivity = safeGetFloat(_prefs, "pref_language_detection_sensitivity", Defaults.LANGUAGE_DETECTION_SENSITIVITY)
        // v1.1.94: Secondary prediction weight (0.5-1.5)
        secondary_prediction_weight = safeGetFloat(_prefs, "pref_secondary_prediction_weight", Defaults.SECONDARY_PREDICTION_WEIGHT)

        autocorrect_enabled = _prefs.getBoolean("autocorrect_enabled", Defaults.AUTOCORRECT_ENABLED)
        autocorrect_min_word_length = safeGetInt(_prefs, "autocorrect_min_word_length", Defaults.AUTOCORRECT_MIN_WORD_LENGTH)
        autocorrect_char_match_threshold = safeGetFloat(_prefs, "autocorrect_char_match_threshold", Defaults.AUTOCORRECT_CHAR_MATCH_THRESHOLD)
        autocorrect_confidence_min_frequency = safeGetInt(_prefs, "autocorrect_confidence_min_frequency", Defaults.AUTOCORRECT_MIN_FREQUENCY)
        // Issue #72: Auto-capitalize "I" words
        autocapitalize_i_words = _prefs.getBoolean("autocapitalize_i_words", Defaults.AUTOCAPITALIZE_I_WORDS)

        autocorrect_max_length_diff = safeGetInt(_prefs, "autocorrect_max_length_diff", Defaults.AUTOCORRECT_MAX_LENGTH_DIFF)
        autocorrect_prefix_length = safeGetInt(_prefs, "autocorrect_prefix_length", Defaults.AUTOCORRECT_PREFIX_LENGTH)
        autocorrect_tuning = AutocorrectTuning.fromPrefs(_prefs)

        swipe_final_autocorrect_enabled = _prefs.getBoolean("swipe_final_autocorrect_enabled", Defaults.SWIPE_FINAL_AUTOCORRECT_ENABLED)

        // finger_occlusion_offset: percent of ONE KEY ROW to shift raw touch Y before the
        // decoders normalise it. Restored 2026-08-19 after the neural removal deprecated it as
        // engine-specific — it is not: the fingertip obscures the target, so touches land above
        // the intended key, and that is a property of the finger and the screen. Both engines
        // consume the same coordinates.
        //
        // Default 0, NOT the neural engine's 12.5%. That figure was never measured anywhere in
        // this repo's history, and CTC's 89.31 top-1 was achieved on UNCORRECTED human traces —
        // so a nonzero default would shift input away from the distribution the model was
        // trained on. Signed, because the correction runs both ways: users who overshoot need
        // the opposite sign to users who undershoot.
        finger_occlusion_offset = safeGetInt(_prefs, "finger_occlusion_offset", Defaults.FINGER_OCCLUSION_OFFSET)
            .coerceIn(SettingsRanges.FINGER_OCCLUSION_OFFSET)

        short_gestures_enabled = _prefs.getBoolean("short_gestures_enabled", Defaults.SHORT_GESTURES_ENABLED)
        short_gesture_min_distance = PercentOfKey(safeGetInt(_prefs, "short_gesture_min_distance", Defaults.SHORT_GESTURE_MIN_DISTANCE))
        short_gesture_max_distance = PercentOfKey(safeGetInt(_prefs, "short_gesture_max_distance", Defaults.SHORT_GESTURE_MAX_DISTANCE))
        // GH #147/#87: read after BOTH inputs above. The mode overrides word swipes rather
        // than coexisting with them (see the field KDoc), so the live swipe_typing_enabled —
        // which every swipe consumer reads — is the preference minus this mode.
        short_swipes_only = short_gestures_enabled &&
            safeGetBoolean(_prefs, "short_swipes_only", Defaults.SHORT_SWIPES_ONLY)
        if (short_swipes_only) swipe_typing_enabled = false

        subkey_popover_enabled = _prefs.getBoolean("subkey_popover_enabled", Defaults.SUBKEY_POPOVER_ENABLED)
        subkey_popover_neutral_width = safeGetInt(_prefs, "subkey_popover_neutral_width", Defaults.SUBKEY_POPOVER_NEUTRAL_WIDTH)
            .coerceIn(Defaults.SUBKEY_POPOVER_NEUTRAL_MIN, Defaults.SUBKEY_POPOVER_NEUTRAL_MAX)
        subkey_popover_neutral_height = safeGetInt(_prefs, "subkey_popover_neutral_height", Defaults.SUBKEY_POPOVER_NEUTRAL_HEIGHT)
            .coerceIn(Defaults.SUBKEY_POPOVER_NEUTRAL_MIN, Defaults.SUBKEY_POPOVER_NEUTRAL_MAX)

        // Selection-delete mode configuration
        selection_delete_vertical_threshold = safeGetInt(_prefs, "selection_delete_vertical_threshold", Defaults.SELECTION_DELETE_VERTICAL_THRESHOLD)
        selection_delete_vertical_speed = safeGetFloat(_prefs, "selection_delete_vertical_speed", Defaults.SELECTION_DELETE_VERTICAL_SPEED)
        // GH #190: any value other than "follow" (including a corrupt one) keeps the
        // historical speed-by-distance joystick.
        selection_delete_follow_finger = safeGetString(_prefs, "selection_delete_mode", Defaults.SELECTION_DELETE_MODE)
            .lowercase(java.util.Locale.ROOT) == Defaults.SELECTION_DELETE_MODE_FOLLOW
        selection_delete_step_px = safeGetInt(_prefs, "selection_delete_step_dp", Defaults.SELECTION_DELETE_STEP_DP)
            .coerceIn(SettingsRanges.SELECTION_DELETE_STEP_DP) * dm.density

        // Gesture timing configuration
        tap_duration_threshold = safeGetInt(_prefs, "tap_duration_threshold", Defaults.TAP_DURATION_THRESHOLD).toLong()
        double_space_to_period = _prefs.getBoolean("double_space_to_period", Defaults.DOUBLE_SPACE_TO_PERIOD)
        double_space_threshold = safeGetInt(_prefs, "double_space_threshold", Defaults.DOUBLE_SPACE_THRESHOLD).toLong()
        smart_punctuation = _prefs.getBoolean("smart_punctuation", Defaults.SMART_PUNCTUATION)
        swipe_min_dwell_time = safeGetInt(_prefs, "swipe_min_dwell_time", Defaults.SWIPE_MIN_DWELL_TIME).toLong()
        swipe_noise_threshold = safeGetFloat(_prefs, "swipe_noise_threshold", Defaults.SWIPE_NOISE_THRESHOLD)
        swipe_high_velocity_threshold = safeGetFloat(_prefs, "swipe_high_velocity_threshold", Defaults.SWIPE_HIGH_VELOCITY_THRESHOLD)
        swipe_min_distance = safeGetFloat(_prefs, "swipe_min_distance", Defaults.SWIPE_MIN_DISTANCE)
        swipe_min_key_distance = safeGetFloat(_prefs, "swipe_min_key_distance", Defaults.SWIPE_MIN_KEY_DISTANCE)

        // Slider speed configuration
        slider_speed_smoothing = safeGetFloat(_prefs, "slider_speed_smoothing", Defaults.SLIDER_SPEED_SMOOTHING)
        slider_speed_max = safeGetFloat(_prefs, "slider_speed_max", Defaults.SLIDER_SPEED_MAX)

        // Swipe trail appearance
        swipe_trail_enabled = _prefs.getBoolean("swipe_trail_enabled", Defaults.SWIPE_TRAIL_ENABLED)
        swipe_trail_effect = safeGetString(_prefs, "swipe_trail_effect", Defaults.SWIPE_TRAIL_EFFECT)
        swipe_trail_color = _prefs.getInt("swipe_trail_color", Defaults.SWIPE_TRAIL_COLOR)
        swipe_trail_width = safeGetFloat(_prefs, "swipe_trail_width", Defaults.SWIPE_TRAIL_WIDTH)
        swipe_trail_glow_radius = safeGetFloat(_prefs, "swipe_trail_glow_radius", Defaults.SWIPE_TRAIL_GLOW_RADIUS)

        custom_terminal_packages = (TerminalUtils.parseCustomPackages(
            safeGetString(_prefs, "custom_terminal_packages", Defaults.CUSTOM_TERMINAL_PACKAGES)
        ) as? TerminalUtils.PackageListResult.Valid)?.packages ?: emptySet()
        auto_space_after_suggestion = _prefs.getBoolean("auto_space_after_suggestion", Defaults.AUTO_SPACE_AFTER_SUGGESTION)
        auto_space_before_suggestion = _prefs.getBoolean("auto_space_before_suggestion", Defaults.AUTO_SPACE_BEFORE_SUGGESTION)
        backspace_undo_swipe = _prefs.getBoolean("backspace_undo_swipe", Defaults.BACKSPACE_UNDO_SWIPE)
        backspace_undo_autocorrect = _prefs.getBoolean("backspace_undo_autocorrect", Defaults.BACKSPACE_UNDO_AUTOCORRECT)
        swipe_debug_detailed_logging = _prefs.getBoolean("swipe_debug_detailed_logging", Defaults.SWIPE_DEBUG_DETAILED_LOGGING)
        // L1: canonicalize case at read — an imported/hand-edited "CTC" must drive the
        // router, provenance tagging, and the settings UI exactly like "ctc".
        swipe_engine_mode = safeGetString(_prefs, "swipe_engine_mode", Defaults.SWIPE_ENGINE_MODE)
            .lowercase(java.util.Locale.ROOT)
        geo_max_results = safeGetInt(_prefs, "geo_max_results", Defaults.GEO_MAX_RESULTS)
        geo_frequency_weight = safeGetFloat(_prefs, "geo_frequency_weight", Defaults.GEO_FREQUENCY_WEIGHT)
        geo_endpoint_inset_kw = safeGetFloat(_prefs, "geo_endpoint_inset_kw", Defaults.GEO_ENDPOINT_INSET_KW)
        ctc_beam_width = safeGetInt(_prefs, "ctc_beam_width", Defaults.CTC_BEAM_WIDTH)
            .coerceIn(SettingsRanges.CTC_BEAM_WIDTH)
        swipe_context_rescoring = _prefs.getBoolean(
            "swipe_context_rescoring", Defaults.SWIPE_CONTEXT_RESCORING
        )

        // Clamped here (audit M8) as well as in the recognizer, so the value Settings shows is
        // the value the touch path uses.
        swipe_smoothing_window = safeGetInt(_prefs, "swipe_smoothing_window", Defaults.SWIPE_SMOOTHING_WINDOW)
            .coerceIn(SettingsRanges.SWIPE_SMOOTHING_WINDOW)


        // Language-specific prefix boost settings (per-language keys)
        // Try per-language key first, fall back to global default

        // ONNX Runtime settings (requires app restart to take effect)
        onnx_xnnpack_threads = safeGetInt(_prefs, "onnx_xnnpack_threads", Defaults.ONNX_XNNPACK_THREADS)
            .coerceIn(SettingsRanges.ONNX_XNNPACK_THREADS)

        val screen_width_dp = dm.widthPixels / dm.density
        wide_screen = screen_width_dp >= WIDE_DEVICE_THRESHOLD

        // GH #186/#61: needs layouts, the selection indices AND wide_screen (which of the two
        // selections is current), so it runs after all three are read.
        recomputeActiveLanguages()
        // Per-layout theme binding: same inputs. ConfigurationManager compares the theme before
        // and after this refresh, so a layout switch onto/off a bound layout re-inflates the
        // keyboard exactly when the rendered theme changes — and never otherwise.
        recomputeActiveTheme { getThemeId(res, it) }

        // MUST stay the last statement: every field the hot paths read has now been written,
        // so the published snapshot can never lag the vars it mirrors. refresh() has a single
        // exit by design — an early return here would publish a stale read-model.
        publishSnapshot()
    }

    /**
     * Apply a direct field mutation and re-publish the read-model in one step.
     *
     * Most settings changes reach `Config` through the preference listener, which calls
     * [refresh] and rebuilds the snapshot at its tail. A few callers instead assign a field
     * straight onto the live config (a settings toggle wanting the change to take effect
     * before the listener fires; an instrumented test pinning a threshold). Such a write
     * leaves [snapshot] holding the OLD value until the next refresh, so the touch and draw
     * hot paths keep running on configuration the user already changed. Routing the write
     * through here closes that window — `config?.edit { keyrepeat_backspace_only = value }`.
     *
     * [version] is bumped as well, because a mutation that reached the read-model must be
     * distinguishable from the one before it — `Keyboard2View` keys its rendered-layout and
     * theme caches on [version], so a silent edit would otherwise be served a stale layout.
     *
     * `ConfigSnapshotRatchetTest` forbids the raw `config.<field> = …` shape for any field
     * the snapshot mirrors; the bare receiver-scoped assignment inside this block is the
     * sanctioned form.
     */
    fun edit(block: Config.() -> Unit) {
        block(this)
        version++
        publishSnapshot()
    }

    /**
     * The single write site for [snapshot]. Publication has one implementation and several
     * callers ([refresh], [edit]) rather than several assignment sites that could drift.
     */
    private fun publishSnapshot() {
        snapshot = buildSnapshot()
    }

    /**
     * Capture the hot-path fields into an immutable [ConfigSnapshot]. Called at construction
     * and at the end of every [refresh] — i.e. once per configuration change, never per
     * keystroke. `ConfigSnapshot` has no default values, so adding a field to the read-model
     * fails to compile here rather than silently publishing a placeholder.
     */
    private fun buildSnapshot(): ConfigSnapshot = ConfigSnapshot(
        circle_sensitivity = circle_sensitivity,
        tap_duration_threshold = tap_duration_threshold,
        double_tap_lock_shift = double_tap_lock_shift,
        keyrepeat_enabled = keyrepeat_enabled,
        keyrepeat_backspace_only = keyrepeat_backspace_only,
        longPressTimeout = longPressTimeout,
        longPressInterval = longPressInterval,
        selection_delete_vertical_speed = selection_delete_vertical_speed,
        selection_delete_vertical_threshold = selection_delete_vertical_threshold,
        selection_delete_follow_finger = selection_delete_follow_finger,
        selection_delete_step_px = selection_delete_step_px,
        short_gestures_enabled = short_gestures_enabled,
        short_gesture_min_distance = short_gesture_min_distance,
        short_gesture_max_distance = short_gesture_max_distance,
        short_swipes_only = short_swipes_only,
        subkey_popover_enabled = subkey_popover_enabled,
        subkey_popover_neutral_width = subkey_popover_neutral_width,
        subkey_popover_neutral_height = subkey_popover_neutral_height,
        swipe_dist_px = swipe_dist_px,
        slide_step_px = slide_step_px,
        continuous_swipe_enabled = continuous_swipe_enabled,
        primary_language = primary_language,
        swipe_engine_mode = swipe_engine_mode,
        active_secondary_language = active_secondary_language,
        swipe_typing_enabled = swipe_typing_enabled,
        slider_speed_smoothing = slider_speed_smoothing,
        slider_speed_max = slider_speed_max,
        marginTop = marginTop,
        margin_bottom = margin_bottom,
        margin_left = margin_left,
        margin_right = margin_right,
        keyPadding = keyPadding,
        labelTextSize = labelTextSize,
        sublabelTextSize = sublabelTextSize,
        secondary_label_size_scale = secondary_label_size_scale,
        characterSize = characterSize,
        keyboardOpacity = keyboardOpacity,
        themeName = themeName,
        swipe_trail_enabled = swipe_trail_enabled,
        swipe_trail_effect = swipe_trail_effect,
        swipe_trail_color = swipe_trail_color,
        swipe_trail_width = swipe_trail_width,
        swipe_trail_glow_radius = swipe_trail_glow_radius,
        version = version
    )

    fun get_current_layout(): Int {
        return if (wide_screen) current_layout_wide else current_layout_narrow
    }

    /** The active languages for the current layout (GH #186/#61); see [ActiveLanguageSync]. */
    fun activeLanguages(): ActiveLanguages =
        ActiveLanguages(primary_language, active_secondary_language, layout_bound_language)

    /**
     * Re-resolve the active languages from the current layout's binding and the user's
     * language preferences. @return true when any of them changed.
     */
    private fun recomputeActiveLanguages(): Boolean {
        val before = activeLanguages()
        val active = LayoutLanguageBinding.resolve(
            bindings = layout_languages,
            currentIndex = get_current_layout(),
            userPrimary = user_primary_language,
            multilangEnabled = enable_multilang,
            userSecondary = user_secondary_language,
        )
        primary_language = active.primary
        active_secondary_language = active.secondary
        layout_bound_language = active.boundLayoutLanguage
        return active != before
    }

    /**
     * Re-resolve the effective theme from the current layout's theme binding and the user's
     * global theme ([LayoutThemeBinding.resolve]); [styleIdOf] maps a theme id to its Android
     * style ([getThemeId] in production). Called by [refresh] only — NOT by [set_current_layout]:
     * ConfigurationManager detects a theme change by comparing across the refresh that the
     * selection write triggers, so resolving earlier would hide the change from it.
     *
     * @return true when the effective theme or its binding state changed.
     */
    internal fun recomputeActiveTheme(styleIdOf: (String) -> Int): Boolean {
        val before = Triple(themeName, layout_bound_theme, layout_missing_theme)
        val active = LayoutThemeBinding.resolve(
            bindings = layout_themes,
            currentIndex = get_current_layout(),
            globalTheme = user_theme_name,
            isAvailable = themeAvailability ?: { true },
        )
        themeName = active.themeName
        layout_bound_theme = active.boundTheme
        layout_missing_theme = active.missingTheme
        theme = styleIdOf(active.themeName)
        return Triple(themeName, layout_bound_theme, layout_missing_theme) != before
    }

    fun set_current_layout(l: Int) {
        if (wide_screen) {
            current_layout_wide = l
        } else {
            current_layout_narrow = l
        }
        // GH #186/#61: a layout switch is a language switch when either layout is bound. Resolve
        // now and re-publish the snapshot (gesture hot paths read primary_language from it), so
        // nothing reads the old language between this call and the preference-listener refresh
        // that follows the write below and drives ActiveLanguageSync.
        if (recomputeActiveLanguages()) edit { }
        _prefs.edit().apply {
            putInt("current_layout_portrait", current_layout_narrow)
            putInt("current_layout_landscape", current_layout_wide)
            apply()
        }
    }

    fun set_clipboard_history_enabled(e: Boolean) {
        clipboard_history_enabled = e
        _prefs.edit().putBoolean("clipboard_history_enabled", e).apply()
    }

    fun set_clipboard_history_limit(limit: Int) {
        clipboard_history_limit = limit
        _prefs.edit().putInt("clipboard_history_limit", limit).apply()
    }

    /** Re-read clipboard_history_duration from SharedPreferences.
     *  Called mid-session when the user changes the Entry Duration slider in settings,
     *  so the service picks up the new value without requiring a keyboard restart. */
    fun reloadClipboardDuration() {
        clipboard_history_duration = safeGetString(_prefs, "clipboard_history_duration",
            Defaults.CLIPBOARD_HISTORY_DURATION).toIntOrNull() ?: Defaults.CLIPBOARD_HISTORY_DURATION_FALLBACK
    }

    /** Re-read the URL-sanitization toggles (Chunk 3) from SharedPreferences.
     *  Called mid-session from ClipboardHistoryService's rules-changed receiver when the
     *  user flips a "URL handling" toggle in settings. The settings UI persists the toggle
     *  but does NOT push it into the live Config, so without this the cached sanitizer would
     *  rebuild from the stale startup value and appear to do nothing until a keyboard restart.
     *  [refresh] calls this too, so the Chunk-3 keys and defaults have one read site. */
    fun reloadSanitizationSettings() {
        clipboard_sanitize_links_enabled = _prefs.getBoolean("clipboard_sanitize_links_enabled", Defaults.CLIPBOARD_SANITIZE_LINKS_ENABLED)
        clipboard_embed_enrich_enabled = _prefs.getBoolean("clipboard_embed_enrich_enabled", Defaults.CLIPBOARD_EMBED_ENRICH_ENABLED)
        clipboard_custom_rules_enabled = _prefs.getBoolean("clipboard_custom_rules_enabled", Defaults.CLIPBOARD_CUSTOM_RULES_ENABLED)
        clipboard_custom_rules_uri = _prefs.getString("clipboard_custom_rules_uri", null)
        clipboard_sanitize_system_clipboard = _prefs.getBoolean("clipboard_sanitize_system_clipboard", Defaults.CLIPBOARD_SANITIZE_SYSTEM_CLIPBOARD)
    }

    fun set_gif_enabled(enabled: Boolean) {
        gif_enabled = enabled
        _prefs.edit().putBoolean("gif_enabled", enabled).apply()
    }

    private fun get_dip_pref(dm: DisplayMetrics, pref_name: String, def: Float): Float {
        var value = try {
            _prefs.getInt(pref_name, -1).toFloat()
        } catch (e: Exception) {
            try {
                _prefs.getFloat(pref_name, -1f)
            } catch (e2: Exception) {
                try {
                    _prefs.getString(pref_name, def.toString())?.toFloat() ?: -1f
                } catch (e3: Exception) {
                    -1f
                }
            }
        }
        if (value < 0f) value = def
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, dm)
    }

    /**
     * Get a percentage preference and convert to pixels based on screen width.
     * Used for horizontal margins (left/right).
     */
    private fun get_percent_pref_oriented_width(
        pref_base_name: String,
        def_port: Int,
        def_land: Int
    ): Float = screenWidthPixels * orientedPercent(pref_base_name, def_port, def_land)
        .coerceIn(SettingsRanges.MARGIN_SIDE_PERCENT) / 100f

    /**
     * The orientation/fold-scoped percent stored under `<base>_portrait` / `<base>_landscape`
     * (outer screen) or `<base>_portrait_unfolded` / `<base>_landscape_unfolded` (a foldable's
     * inner screen). An inner-screen key that was never set follows the outer-screen value —
     * the same rule as [KeyboardHeightPrefs.followOuter]. Before 2026-10-20 nothing wrote the
     * `_unfolded` margin keys, so every margin reverted to its default on an inner screen (audit H1).
     */
    private fun orientedPercent(base: String, defPort: Int, defLand: Int): Int =
        KeyboardHeightPrefs.followOuter(
            outerKey = base + if (orientation_landscape) "_landscape" else "_portrait",
            unfolded = foldable_unfolded,
            default = if (orientation_landscape) defLand else defPort,
            contains = _prefs::contains,
        ) { key, def -> safeGetInt(_prefs, key, def) }

    /**
     * Get a percentage preference and convert to pixels based on screen height.
     * Used for vertical margins (bottom).
     */
    private fun get_percent_pref_oriented_height(
        pref_base_name: String,
        def_port: Int,
        def_land: Int
    ): Float = screenHeightPixels * orientedPercent(pref_base_name, def_port, def_land)
        .coerceIn(SettingsRanges.MARGIN_BOTTOM_PERCENT) / 100f

    private fun getThemeId(res: Resources, theme_name: String): Int =
        builtInThemeStyleId(theme_name, isSystemLight(res))

    /**
     * Check if current theme is a runtime theme (decorative or custom).
     * Runtime themes use KeyboardColorScheme instead of XML attributes.
     */
    fun isRuntimeTheme(): Boolean = ConfigSnapshot.isRuntimeThemeName(themeName)

    interface IKeyEventHandler {
        fun key_down(key: KeyValue?, isSwipe: Boolean)
        fun key_up(key: KeyValue?, mods: Pointers.Modifiers, isKeyRepeat: Boolean = false)
        fun canUseEditorActions(): Boolean = false
        fun execute_suffix(suffix: String): Boolean = false
        fun execute_template(template: String): Boolean = false
        fun mods_changed(mods: Pointers.Modifiers)
    }

    companion object {
        const val WIDE_DEVICE_THRESHOLD = 600
        // 3 → 4 (2026-09-24): the learning-consent step — upgrades re-enter migrate()
        // so LearningMigration can seed the master gate and stamp the one-time
        // selection-history reset. v1.5.0 devices sit exactly at version == 3.
        // 4 → 5 (2026-09-30): SubkeyPopoverMigration seeds the popover OFF for upgrades.
        private const val CONFIG_VERSION = 5
        private const val MARGIN_PREFS_VERSION = 1  // For dp→percentage migration

        // `isSwipeTypingSupportedForLayout` (the #9 "QWERTY-Latin only" allowlist) was
        // removed on 2026-08-18 with the neural engine. It existed solely because the
        // transformer was trained on QWERTY-US key positions; CTC takes key geometry as a
        // model input and geometric decodes any layout, so every layout now swipes and the
        // predicate has no meaning. Routing lives in `swipe.SwipeEngineRouter`.

        @Volatile
        private var _globalConfig: Config? = null

        /**
         * Whether a theme id exists on this device (`ThemeProvider.isAvailable`), consulted when a
         * layout's theme binding is resolved. Installed by `CleverKeysService.onCreate` before
         * [initGlobalConfig]; Config has no Context, and custom themes live in another prefs file.
         * Null (not installed, e.g. a Settings-only process) treats every well-formed id as
         * available — ThemeProvider still renders a total fallback for a dangling one.
         */
        @Volatile
        var themeAvailability: ((String) -> Boolean)? = null

        @JvmStatic
        fun initGlobalConfig(
            prefs: SharedPreferences,
            res: Resources,
            handler: IKeyEventHandler?,
            foldableUnfolded: Boolean?
        ) {
            migrate(prefs)
            migrateMarginPrefs(prefs, res.displayMetrics)
            migrateForcedVibrateCustom(prefs)
            val config = Config(prefs, res, handler, foldableUnfolded)
            _globalConfig = config
            LayoutModifier.init(config, res)
        }

        @JvmStatic
        fun globalConfig(): Config = _globalConfig
            // NullPointerException (not IllegalStateException) is deliberate: the original
            // `_globalConfig!!` threw KotlinNullPointerException, and a documented test idiom
            // + TestConfigHelper catch NullPointerException to detect the "not initialized yet"
            // state. Keep the diagnostic message but preserve the exception TYPE for them.
            ?: throw NullPointerException("Config not initialized — initGlobalConfig() must run before globalConfig()")

        /**
         * Null-safe accessor for the global [Config] instance. Returns `null` when
         * [initGlobalConfig] has not yet run, so callers that may execute before
         * IME initialization (e.g. early static init, tests) can branch instead of
         * crashing. Prefer [globalConfig] where init is guaranteed to have happened.
         */
        @JvmStatic
        fun globalConfigOrNull(): Config? = _globalConfig

        @JvmStatic
        fun globalPrefs(): SharedPreferences = globalConfig()._prefs

        @JvmStatic
        fun safeGetInt(prefs: SharedPreferences, key: String, defaultValue: Int): Int {
            return try {
                prefs.getInt(key, defaultValue)
            } catch (e: ClassCastException) {
                // Value might be stored as Float (from JSON import) or String
                try {
                    // Try Float first - JSON numbers are often stored as Float
                    val floatValue = prefs.getFloat(key, Float.MIN_VALUE)
                    if (floatValue == Float.MIN_VALUE) defaultValue else floatValue.toInt()
                } catch (e2: ClassCastException) {
                    // Try String
                    try {
                        val stringValue = prefs.getString(key, null)
                        stringValue?.toIntOrNull() ?: defaultValue
                    } catch (e3: Exception) {
                        Log.w("Config", "Corrupted int preference $key, using default: $defaultValue")
                        defaultValue
                    }
                }
            }
        }

        @JvmStatic
        fun repairCorruptedFloatPreferences(prefs: SharedPreferences) {
            // NOTE: These defaults MUST match Defaults.* constants for consistency
            val floatPrefs = arrayOf(
                arrayOf("character_size", "${Defaults.CHARACTER_SIZE}"),
                arrayOf("secondary_label_size_scale", "${Defaults.SECONDARY_LABEL_SIZE_SCALE}"),
                arrayOf("key_vertical_margin", "${Defaults.KEY_VERTICAL_MARGIN}"),
                arrayOf("key_horizontal_margin", "${Defaults.KEY_HORIZONTAL_MARGIN}"),
                arrayOf("custom_border_line_width", "${Defaults.CUSTOM_BORDER_LINE_WIDTH}"),
                arrayOf("prediction_context_boost", "${Defaults.PREDICTION_CONTEXT_BOOST}"),
                arrayOf("prediction_frequency_scale", "${Defaults.PREDICTION_FREQUENCY_SCALE}"),
                arrayOf("personalization_weight", "${Defaults.PERSONALIZATION_WEIGHT}"),
                arrayOf("autocorrect_char_match_threshold", "${Defaults.AUTOCORRECT_CHAR_MATCH_THRESHOLD}")
                // ARC-052: swipe_rare_words_penalty / swipe_common_words_boost /
                // swipe_top5000_boost are NOT repaired here. All three are in
                // SettingsValidation.DEPRECATED_KEYS, and "repair" writes the key back with
                // editor.putFloat on any type mismatch — so the repair pass resurrected dead
                // keys into SharedPreferences (and thus into the next backup) on devices that
                // had already been migrated off them. Never repair a DEPRECATED_KEYS member;
                // SettingsDefaultsDriftTest asserts this.
            )

            val editor = prefs.edit()
            var needsCommit = false

            for (pref in floatPrefs) {
                val key = pref[0]
                val defaultValue = pref[1].toFloat()

                try {
                    prefs.getFloat(key, defaultValue)
                } catch (e: ClassCastException) {
                    try {
                        val intValue = prefs.getInt(key, defaultValue.toInt())
                        val floatValue = intValue.toFloat()
                        editor.putFloat(key, floatValue)
                        needsCommit = true
                        Log.w("Config", "Repaired corrupted preference $key: int $intValue → float $floatValue")
                    } catch (e2: ClassCastException) {
                        try {
                            val stringValue = prefs.getString(key, defaultValue.toString()) ?: defaultValue.toString()
                            val floatValue = stringValue.toFloat()
                            editor.putFloat(key, floatValue)
                            needsCommit = true
                            Log.w("Config", "Repaired corrupted preference $key: string \"$stringValue\" → float $floatValue")
                        } catch (e3: Exception) {
                            editor.putFloat(key, defaultValue)
                            needsCommit = true
                            Log.w("Config", "Reset corrupted preference $key to default: $defaultValue")
                        }
                    }
                }
            }

            if (needsCommit) {
                editor.apply()
                Log.i("Config", "Applied preference repairs")
            }
        }

        @JvmStatic
        fun safeGetFloat(prefs: SharedPreferences, key: String, defaultValue: Float): Float {
            return try {
                prefs.getFloat(key, defaultValue)
            } catch (e: ClassCastException) {
                // Value might be stored as Int, String, or Boolean
                try {
                    val intValue = prefs.getInt(key, Int.MIN_VALUE)
                    if (intValue == Int.MIN_VALUE) defaultValue else {
                        Log.w("Config", "Float preference $key was stored as int: $intValue")
                        intValue.toFloat()
                    }
                } catch (e2: ClassCastException) {
                    try {
                        val stringValue = prefs.getString(key, null)
                        if (stringValue == null) {
                            defaultValue
                        } else {
                            val parsed = stringValue.toFloatOrNull()
                            if (parsed != null) {
                                Log.w("Config", "Float preference $key was stored as string: $stringValue")
                                parsed
                            } else {
                                defaultValue
                            }
                        }
                    } catch (e3: Exception) {
                        Log.w("Config", "Corrupted float preference $key, using default: $defaultValue")
                        defaultValue
                    }
                }
            }
        }

        /**
         * Safely get a Boolean preference, handling ClassCastException when value is stored as String or Int.
         * This is critical for config import where types may be mismatched.
         */
        @JvmStatic
        fun safeGetBoolean(prefs: SharedPreferences, key: String, defaultValue: Boolean): Boolean {
            return try {
                prefs.getBoolean(key, defaultValue)
            } catch (e: ClassCastException) {
                // Value might be stored as String or Int
                try {
                    val stringVal = prefs.getString(key, null)
                    when (stringVal?.lowercase()) {
                        "true", "1", "yes" -> true
                        "false", "0", "no" -> false
                        else -> defaultValue
                    }
                } catch (e2: ClassCastException) {
                    try {
                        prefs.getInt(key, -1).let {
                            when (it) {
                                1 -> true
                                0 -> false
                                else -> defaultValue
                            }
                        }
                    } catch (e3: Exception) {
                        Log.w("Config", "Corrupted boolean preference $key, using default: $defaultValue")
                        defaultValue
                    }
                } catch (e2: Exception) {
                    Log.w("Config", "Error reading boolean preference $key, using default: $defaultValue")
                    defaultValue
                }
            }
        }

        /**
         * Safely get a String preference, handling ClassCastException when value is stored as Int, Float, or Boolean.
         * This is critical for config import where numeric strings may be stored as integers.
         */
        @JvmStatic
        fun safeGetString(prefs: SharedPreferences, key: String, defaultValue: String): String {
            return try {
                prefs.getString(key, defaultValue) ?: defaultValue
            } catch (e: ClassCastException) {
                // Value might be stored as Int, Float, or Boolean (e.g., from config import)
                try {
                    val intValue = prefs.getInt(key, Int.MIN_VALUE)
                    if (intValue == Int.MIN_VALUE) {
                        defaultValue
                    } else {
                        Log.w("Config", "String preference $key was stored as int: $intValue")
                        intValue.toString()
                    }
                } catch (e2: ClassCastException) {
                    // Try Float
                    try {
                        val floatValue = prefs.getFloat(key, Float.MIN_VALUE)
                        if (floatValue == Float.MIN_VALUE) {
                            defaultValue
                        } else {
                            Log.w("Config", "String preference $key was stored as float: $floatValue")
                            floatValue.toString()
                        }
                    } catch (e3: ClassCastException) {
                        // Try Boolean
                        try {
                            val boolValue = prefs.getBoolean(key, false)
                            Log.w("Config", "String preference $key was stored as boolean: $boolValue")
                            boolValue.toString()
                        } catch (e4: Exception) {
                            Log.w("Config", "Corrupted string preference $key, using default: $defaultValue")
                            defaultValue
                        }
                    } catch (e3: Exception) {
                        Log.w("Config", "Error reading string preference $key, using default: $defaultValue")
                        defaultValue
                    }
                } catch (e2: Exception) {
                    Log.w("Config", "Error reading preference $key, using default: $defaultValue")
                    defaultValue
                }
            }
        }

        /** True when the system is in light mode (`uiMode` night bit NO), the cue every "follow the system" theme reads. */
        @JvmStatic
        fun isSystemLight(res: Resources): Boolean =
            (res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO

        /**
         * THE theme-name → `R.style` map, for the keyboard view's context theme
         * ([getThemeId]) and for [tribixbite.cleverkeys.theme.ThemeProvider]'s built-in
         * themes (magnifier, Short Swipes preview) alike. There used to be two maps, and they
         * disagreed: "Monet (Auto)" followed [systemLight] for the keyboard but was always
         * MonetDark in the previews, and an unset or unknown name fell back differently
         * (code-quality audit 2026-10-20, H2).
         *
         * Runtime themes (`decorative_*`, `custom_*`) take the base style; their colours come
         * from a KeyboardColorScheme. Monet needs Android 12 (API 31, [sdk]); older systems
         * get the plain light/dark style.
         */
        @JvmStatic
        @JvmOverloads
        fun builtInThemeStyleId(themeName: String, systemLight: Boolean, sdk: Int = Build.VERSION.SDK_INT): Int {
            val monet = sdk >= Build.VERSION_CODES.S
            return when {
                themeName.startsWith("decorative_") || themeName.startsWith("custom_") -> R.style.CleverKeysDark
                else -> when (themeName) {
                    "light" -> R.style.Light
                    "black" -> R.style.Black
                    "altblack" -> R.style.AltBlack
                    "dark" -> R.style.Dark
                    "white" -> R.style.White
                    "epaper" -> R.style.ePaper
                    "desert" -> R.style.Desert
                    "jungle" -> R.style.Jungle
                    "monetlight" -> if (monet) R.style.MonetLight else R.style.Light
                    "monetdark" -> if (monet) R.style.MonetDark else R.style.Dark
                    "monet" -> when {
                        monet && systemLight -> R.style.MonetLight
                        monet -> R.style.MonetDark
                        systemLight -> R.style.Light
                        else -> R.style.Dark
                    }
                    "rosepine" -> R.style.RosePine
                    "everforestlight" -> R.style.EverforestLight
                    "cobalt" -> R.style.Cobalt
                    "pine" -> R.style.Pine
                    "epaperblack" -> R.style.ePaperBlack
                    "jewel" -> R.style.Jewel
                    "cleverkeysdark" -> R.style.CleverKeysDark
                    "cleverkeyslight" -> R.style.CleverKeysLight
                    // ARC-111: an unset theme follows the system like every other fallback;
                    // users who explicitly chose a theme are unaffected.
                    "" -> if (systemLight) R.style.CleverKeysLight else R.style.CleverKeysDark
                    else -> if (systemLight) R.style.Light else R.style.Dark
                }
            }
        }

        @JvmStatic
        fun migrate(prefs: SharedPreferences) {
            // Read BEFORE the version stamp below: every install that ever ran wrote
            // "version", so its absence is the fresh-install signal the v4 step needs.
            val isFreshInstall = !prefs.contains("version")
            val saved_version = prefs.getInt("version", 0)
            Logs.debug_config_migration(saved_version, CONFIG_VERSION)
            if (saved_version == CONFIG_VERSION) return

            val e = prefs.edit()
            e.putInt("version", CONFIG_VERSION)

            // v4 (2026-09-24): learning-consent step — rules in [LearningMigration].
            if (LearningMigration.seedsMasterGateOn(
                    isFreshInstall, saved_version,
                    hasExplicitChoice = prefs.contains("on_device_learning_enabled")
                )
            ) {
                e.putBoolean("on_device_learning_enabled", true)
            }
            if (LearningMigration.requestsSelectionHistoryReset(isFreshInstall, saved_version)) {
                e.putBoolean(LearningMigration.SELECTION_HISTORY_RESET_PENDING_KEY, true)
            }
            // v5 (2026-09-30): subkey popover — rules in [SubkeyPopoverMigration].
            if (SubkeyPopoverMigration.seedsOff(
                    isFreshInstall, saved_version,
                    hasExplicitChoice = prefs.contains(SubkeyPopoverMigration.PREF_KEY)
                )
            ) {
                e.putBoolean(SubkeyPopoverMigration.PREF_KEY, false)
            }

            when (saved_version) {
                0 -> {
                    val l = mutableListOf<LayoutsPreference.Layout>()
                    l.add(migrate_layout(safeGetString(prefs, "layout", "system")))
                    val snd_layout = safeGetString(prefs, "second_layout", "none")
                    if (snd_layout != "none")
                        l.add(migrate_layout(snd_layout))
                    val custom_layout = safeGetString(prefs, "custom_layout", "")
                    if (custom_layout.isNotEmpty())
                        l.add(LayoutsPreference.CustomLayout.parse(custom_layout))
                    LayoutsPreference.save_to_preferences(e, l)
                    // Fallthrough to case 1
                    val add_number_row = prefs.getBoolean("number_row", false)
                    e.putString("number_row", if (add_number_row) "no_symbols" else "no_number_row")
                    // Fallthrough to case 2
                    if (!prefs.contains("number_entry_layout")) {
                        e.putString("number_entry_layout", if (prefs.getBoolean("pin_entry_enabled", true)) "pin" else "number")
                    }
                }
                1 -> {
                    val add_number_row = prefs.getBoolean("number_row", false)
                    e.putString("number_row", if (add_number_row) "no_symbols" else "no_number_row")
                    // Fallthrough to case 2
                    if (!prefs.contains("number_entry_layout")) {
                        e.putString("number_entry_layout", if (prefs.getBoolean("pin_entry_enabled", true)) "pin" else "number")
                    }
                }
                2 -> {
                    if (!prefs.contains("number_entry_layout")) {
                        e.putString("number_entry_layout", if (prefs.getBoolean("pin_entry_enabled", true)) "pin" else "number")
                    }
                }
            }
            e.apply()
        }

        /**
         * One-time #154 cleanup: clear a bug-forced `vibrate_custom=true` so
         * affected installs return to the low-latency performHapticFeedback
         * path. Skips users who actually customized `vibrate_duration` (they
         * plausibly chose custom mode). Runs exactly once — the marker is
         * persisted unconditionally, even when nothing is changed.
         * Decision logic: [HapticsMigration.shouldClearForcedVibrateCustom].
         */
        @JvmStatic
        fun migrateForcedVibrateCustom(prefs: SharedPreferences) {
            if (prefs.getBoolean("vibrate_custom_migration_v1", false)) return

            val vibrateCustom = prefs.getBoolean("vibrate_custom", Defaults.VIBRATE_CUSTOM)
            // The custom vibration path (VibratorCompat) reads exactly one
            // user-tunable parameter: vibrate_duration. No amplitude pref exists.
            val paramsAtDefaults =
                safeGetInt(prefs, "vibrate_duration", Defaults.VIBRATE_DURATION) == Defaults.VIBRATE_DURATION

            val e = prefs.edit()
            if (HapticsMigration.shouldClearForcedVibrateCustom(
                    vibrateCustom = vibrateCustom,
                    alreadyMigrated = false, // marker checked above
                    paramsAtDefaults = paramsAtDefaults
                )
            ) {
                e.putBoolean("vibrate_custom", false)
                Log.i("Config", "Cleared bug-forced vibrate_custom (#154 one-time migration)")
            }
            // Always mark migrated so this never runs again.
            e.putBoolean("vibrate_custom_migration_v1", true)
            e.apply()
        }

        private fun migrate_layout(name: String?): LayoutsPreference.Layout {
            return if (name == null || name == "system")
                LayoutsPreference.SystemLayout()
            else
                LayoutsPreference.NamedLayout(name)
        }

        /**
         * Migrate margin preferences from old dp-based values to percentage-based.
         * This runs on every startup but only performs migration once (tracked by margin_prefs_version).
         *
         * Old system: values in dp (e.g., 14dp for bottom margin)
         * New system: values as % of screen dimension (e.g., 2% for bottom margin)
         *
         * Without this, Android Auto-Backup restores old dp values which get
         * interpreted as percentages (14dp becomes 14%, way too large).
         */
        @JvmStatic
        private fun migrateMarginPrefs(prefs: SharedPreferences, dm: DisplayMetrics) {
            val savedVersion = prefs.getInt("margin_prefs_version", 0)
            if (savedVersion >= MARGIN_PREFS_VERSION) return

            Log.i("Config", "Migrating margin preferences from dp to percentage (version $savedVersion → $MARGIN_PREFS_VERSION)")

            val editor = prefs.edit()
            val density = dm.density
            val screenWidth = dm.widthPixels
            val screenHeight = dm.heightPixels

            // Migrate horizontal margins (old: horizontal_margin_*, new: margin_left_* + margin_right_*)
            val horizontalKeys = listOf(
                "horizontal_margin_portrait" to listOf("margin_left_portrait", "margin_right_portrait"),
                "horizontal_margin_landscape" to listOf("margin_left_landscape", "margin_right_landscape"),
                "horizontal_margin_portrait_unfolded" to listOf("margin_left_portrait_unfolded", "margin_right_portrait_unfolded"),
                "horizontal_margin_landscape_unfolded" to listOf("margin_left_landscape_unfolded", "margin_right_landscape_unfolded")
            )

            for ((oldKey, newKeys) in horizontalKeys) {
                if (prefs.contains(oldKey)) {
                    try {
                        val dpValue = safeGetInt(prefs, oldKey, 0)
                        val pixelValue = dpValue * density
                        val percentValue = ((pixelValue / screenWidth) * 100).toInt().coerceIn(SettingsRanges.MARGIN_SIDE_PERCENT)
                        for (newKey in newKeys) {
                            editor.putInt(newKey, percentValue)
                        }
                        editor.remove(oldKey)
                        Log.i("Config", "Migrated $oldKey: ${dpValue}dp → $percentValue%")
                    } catch (e: Exception) {
                        Log.w("Config", "Failed to migrate $oldKey", e)
                    }
                }
            }

            // Migrate bottom margins - ALL existing values are assumed to be dp
            // (no threshold check - prefs_version flag tells us if migration happened)
            val bottomKeys = listOf(
                "margin_bottom_portrait" to Defaults.MARGIN_BOTTOM_PORTRAIT,
                "margin_bottom_landscape" to Defaults.MARGIN_BOTTOM_LANDSCAPE,
                "margin_bottom_portrait_unfolded" to Defaults.MARGIN_BOTTOM_PORTRAIT,
                "margin_bottom_landscape_unfolded" to Defaults.MARGIN_BOTTOM_LANDSCAPE
            )

            for ((key, defaultPercent) in bottomKeys) {
                if (prefs.contains(key)) {
                    try {
                        val oldValue = safeGetInt(prefs, key, defaultPercent)
                        // Convert dp to percentage of screen height
                        val pixelValue = oldValue * density
                        val percentValue = ((pixelValue / screenHeight) * 100).toInt().coerceIn(SettingsRanges.MARGIN_BOTTOM_PERCENT)
                        editor.putInt(key, percentValue)
                        Log.i("Config", "Migrated $key: ${oldValue}dp → $percentValue%")
                    } catch (e: Exception) {
                        Log.w("Config", "Failed to migrate $key", e)
                    }
                }
            }

            // Migrate left/right margins if they exist with dp values
            val leftRightKeys = listOf(
                "margin_left_portrait" to Defaults.MARGIN_LEFT_PORTRAIT,
                "margin_left_landscape" to Defaults.MARGIN_LEFT_LANDSCAPE,
                "margin_left_portrait_unfolded" to Defaults.MARGIN_LEFT_PORTRAIT,
                "margin_left_landscape_unfolded" to Defaults.MARGIN_LEFT_LANDSCAPE,
                "margin_right_portrait" to Defaults.MARGIN_RIGHT_PORTRAIT,
                "margin_right_landscape" to Defaults.MARGIN_RIGHT_LANDSCAPE,
                "margin_right_portrait_unfolded" to Defaults.MARGIN_RIGHT_PORTRAIT,
                "margin_right_landscape_unfolded" to Defaults.MARGIN_RIGHT_LANDSCAPE
            )

            for ((key, defaultPercent) in leftRightKeys) {
                if (prefs.contains(key)) {
                    try {
                        val oldValue = safeGetInt(prefs, key, defaultPercent)
                        // Convert dp to percentage of screen width
                        val pixelValue = oldValue * density
                        val percentValue = ((pixelValue / screenWidth) * 100).toInt().coerceIn(SettingsRanges.MARGIN_SIDE_PERCENT)
                        editor.putInt(key, percentValue)
                        Log.i("Config", "Migrated $key: ${oldValue}dp → $percentValue%")
                    } catch (e: Exception) {
                        Log.w("Config", "Failed to migrate $key", e)
                    }
                }
            }

            // Mark migration complete
            editor.putInt("margin_prefs_version", MARGIN_PREFS_VERSION)
            editor.apply()
            Log.i("Config", "Margin preference migration complete")
        }
    }
}
