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

    /**
     * D2: Swiss German spelling, `ss` for `ß` ([SwissSpelling]). One switch for every German
     * lexicon (primary, secondary, a layout's bound language); it changes nothing in any other
     * language. Absent until German is first served, then seeded from the system languages
     * ([seedGermanSwissSpelling]).
     */
    const val GERMAN_SWISS_SPELLING = "pref_german_swiss_spelling"

    /**
     * The keys whose change alters what the served lexicons hold (Settings broadcasts
     * LANGUAGE_CHANGED on them). The keyboard itself does not go by keys: every preference
     * change refreshes Config, and [ActiveLanguageSync] reloads what the refresh changed, the
     * German lexicons included when [GERMAN_SWISS_SPELLING] flips.
     */
    val SERVING_KEYS: Set<String> = setOf(PRIMARY, SECONDARY, MULTILANG, GERMAN_SWISS_SPELLING)

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

    /**
     * The STORED Swiss-spelling choice. Lexicon loaders read this rather than the in-memory
     * Config: the swipe engines build off the main thread from the stored preferences, and Config
     * is stale in a process where the keyboard is not running (CLAUDE.md, 2026-11-24).
     */
    fun germanSwissSpelling(prefs: SharedPreferences): Boolean =
        Config.safeGetBoolean(prefs, GERMAN_SWISS_SPELLING, Defaults.GERMAN_SWISS_SPELLING)

    /**
     * Give [GERMAN_SWISS_SPELLING] its first value, once: when the key is absent and German is
     * among [servedLanguages], write [SwissSpelling.seedDefault] of the system [locales] (on for
     * `de-CH` / `de-LI`). Written explicitly and synchronously (`commit`), so the value no longer
     * follows a later change of the system language, a backup carries it, and the lexicon load
     * that follows reads it.
     *
     * A stored value (the user's switch, a restored backup, a settings reset) is never replaced.
     *
     * @param servedLanguages every language now served or selected: the primary, the secondary,
     *   layout-bound languages; nulls are ignored
     * @return the value written, or null when nothing was written
     */
    fun seedGermanSwissSpelling(
        prefs: SharedPreferences,
        servedLanguages: Collection<String?>,
        locales: List<java.util.Locale>,
    ): Boolean? {
        if (prefs.contains(GERMAN_SWISS_SPELLING)) return null
        if (servedLanguages.none { SwissSpelling.isGerman(it) }) return null
        val seeded = SwissSpelling.seedDefault(locales)
        prefs.edit().putBoolean(GERMAN_SWISS_SPELLING, seeded).commit()
        return seeded
    }

    /** The system languages of [configuration], most preferred first (the seed's input). */
    fun systemLocales(configuration: android.content.res.Configuration): List<java.util.Locale> {
        val list = configuration.locales
        return List(list.size()) { list[it] }
    }
}
