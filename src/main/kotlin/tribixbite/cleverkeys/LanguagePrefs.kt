package tribixbite.cleverkeys

import android.content.SharedPreferences

/**
 * The Multi-Language preference keys and THE reader of the user's language choice
 * (code-quality audit 2026-10-20, L6 / L13).
 *
 * The key literals used to be restated across Config, Settings, the language toggles, Dictionary
 * Manager and WordPredictor, and the "primary + (secondary when Multi-Language is on and one is
 * chosen)" rule was pasted three times. Every reader now goes through [userLanguages], which
 * delegates the rule itself to [LayoutLanguageBinding.resolve] — the same function Config uses for
 * the ACTIVE languages — so the two can never disagree about an unbound layout.
 */
object LanguagePrefs {
    /** The user's primary language (`pref_primary_language`). */
    const val PRIMARY = "pref_primary_language"
    /** The user's secondary language; [LanguageDisplayNames.NONE] = none. */
    const val SECONDARY = "pref_secondary_language"
    /** The v1.2.0 quick-toggle alternates swapped in by `primaryLangToggle` / `secondaryLangToggle`. */
    const val PRIMARY_ALT = "pref_primary_language_alt"
    const val SECONDARY_ALT = "pref_secondary_language_alt"
    /** Multi-Language master switch. */
    const val MULTILANG = "pref_enable_multilang"

    /** The keys whose change alters which languages are served (Settings broadcasts LANGUAGE_CHANGED on them). */
    val SERVING_KEYS: Set<String> = setOf(PRIMARY, SECONDARY, MULTILANG)

    /**
     * The user's own language choice from [prefs], as the languages an UNBOUND layout serves:
     * the primary, plus the secondary while Multi-Language is on and a secondary is chosen.
     *
     * This deliberately does not consult a layout's language binding: the spec
     * (`docs/specs/dictionary-and-language-system.md`, "Per-layout language binding") keeps the
     * user's choices in the preferences and has Settings show them, and a binding lives in the
     * IME's Config, which the Settings process may not have. Code in the IME that needs what is
     * serving RIGHT NOW reads `Config.activeLanguages()` instead.
     *
     * Type-corrupted values (an imported backup) are recovered like every Config read.
     */
    fun userLanguages(prefs: SharedPreferences): ActiveLanguages = LayoutLanguageBinding.resolve(
        bindings = emptyList(),
        currentIndex = 0,
        userPrimary = Config.safeGetString(prefs, PRIMARY, Defaults.PRIMARY_LANGUAGE),
        multilangEnabled = Config.safeGetBoolean(prefs, MULTILANG, Defaults.ENABLE_MULTILANG),
        userSecondary = Config.safeGetString(prefs, SECONDARY, Defaults.SECONDARY_LANGUAGE),
    )
}
