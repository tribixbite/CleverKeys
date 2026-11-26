package tribixbite.cleverkeys

/**
 * The ONE language-change path (GH #186 / GH #61).
 *
 * Every language change — a Settings language selector, a `primaryLangToggle` /
 * `secondaryLangToggle` swap, Multi-Language on/off, a layout switch onto or off a layout with a
 * language binding (switch_forward/backward, the layout picker, a subtype change re-pointing the
 * selection, a portrait/landscape selection change), an edit of the layout list or a backup
 * import — lands in a Config refresh. `CleverKeysService.onConfigChanged` then hands
 * [Config.activeLanguages] to [apply], which diffs against what is loaded and reloads ONLY what
 * changed, in a fixed order:
 *
 *  1. the primary dictionary — `PredictionCoordinator.reloadWordPredictorDictionary`, which also
 *     moves DictionaryManager's language, the n-gram/static LM, the context model and learning;
 *  2. the secondary dictionary (load, or unload when single-language);
 *  3. contractions — `ContractionManager.loadTypingMappings(primary, secondary)`, which owns the
 *     language-scoping precedence;
 *  4. ARC-014: re-warm the SERVING swipe engine. AFTER the reloads on purpose: the dictionary
 *     reload sets `DictionaryManager.currentLanguage` synchronously and the prewarm reads it, so
 *     warming first would warm the language the user just left.
 *
 * This replaced the key-based reload that lived in `PreferenceUIUpdateHandler`: that path saw
 * only preference KEYS, so a layout switch (which changes the active language without touching
 * any language preference) could never reach it, and the Multi-Language toggle reloaded the
 * secondary dictionary even when switching it OFF.
 *
 * Pure (no Android types) so the ordering and the diff are pinned in `runPureTests`
 * (`ActiveLanguageSyncTest`); the composition root wires the [Sink].
 *
 * ## Swiss German spelling (D2)
 *
 * `pref_german_swiss_spelling` changes what a German lexicon HOLDS without changing which
 * language is served, so the language diff above cannot see it. [applySwissSpelling] is fed
 * Config's value after the same refresh and reloads the German slots when it flips; every loader
 * reads the stored preference itself ([SwissSpelling], [LanguagePrefs.germanSwissSpelling]).
 *
 * @param initial the languages the components were constructed with, so the first refresh after
 *   startup does not reload dictionaries that are already loaded.
 * @param initialSwissSpelling the Swiss-spelling value those components were loaded with.
 */
class ActiveLanguageSync(
    initial: ActiveLanguages,
    private val sink: Sink,
    initialSwissSpelling: Boolean = false,
) {

    /** Side effects of a language change, in the order [apply] calls them. */
    interface Sink {
        /** Load [language] as the primary (prediction/autocorrect/learning) dictionary. */
        fun reloadPrimary(language: String)

        /** Load [language] as the secondary dictionary; null unloads it. */
        fun reloadSecondary(language: String?)

        /** Re-scope contraction mappings to the active languages. */
        fun reloadContractions(primary: String, secondary: String?)

        /** Re-warm the serving swipe engine for the new (layout, language) pair. */
        fun rewarmSwipe()

        /** Tell the user a layout binding changed the language (suggestion-bar message). */
        fun announce(active: ActiveLanguages)
    }

    /** The languages the components currently serve. Main thread only. */
    var applied: ActiveLanguages = initial
        private set

    /**
     * Bring the serving components to [target]. Main thread only.
     *
     * @return true when [target] differs from [applied] (including a change of binding state
     *   alone, which reloads nothing).
     */
    fun apply(target: ActiveLanguages): Boolean {
        val previous = applied
        if (target == previous) return false

        val primaryChanged = target.primary != previous.primary
        val secondaryChanged = target.secondary != previous.secondary
        if (primaryChanged) sink.reloadPrimary(target.primary)
        if (secondaryChanged) sink.reloadSecondary(target.secondary)
        if (primaryChanged || secondaryChanged) {
            sink.reloadContractions(target.primary, target.secondary)
            // ARC-014: after the dictionary reloads (see class KDoc).
            sink.rewarmSwipe()
        }
        // Only a binding that actually changed the serving language is announced; switching
        // between unbound layouts stays silent exactly as before.
        if (primaryChanged && target.boundLayoutLanguage != null) sink.announce(target)
        // Recorded only once every sink ran: a sink that throws leaves [applied] at the old state,
        // so the next apply() retries instead of treating the language as served (review 2026-11-14).
        applied = target
        return true
    }

    /** The Swiss-spelling value the German lexicons were last loaded with. Main thread only. */
    var appliedSwissSpelling: Boolean = initialSwissSpelling
        private set

    /**
     * Bring the German lexicons to the Swiss-spelling preference [enabled]. Main thread only;
     * call it after [apply], so [applied] already names the languages of this refresh.
     *
     * Reloads the primary and/or the secondary dictionary when that slot is German, then re-warms
     * the swipe engine (whose lexicon memo is keyed on the same value). Contractions do not
     * depend on the spelling and are not reloaded. With no German slot nothing is reloaded: the
     * value is recorded, and a later switch to German loads with it through [apply].
     *
     * @return true when [enabled] differs from [appliedSwissSpelling]
     */
    fun applySwissSpelling(enabled: Boolean): Boolean {
        if (enabled == appliedSwissSpelling) return false
        val primaryGerman = SwissSpelling.isGerman(applied.primary)
        val secondaryGerman = SwissSpelling.isGerman(applied.secondary)
        if (primaryGerman) sink.reloadPrimary(applied.primary)
        if (secondaryGerman) sink.reloadSecondary(applied.secondary)
        if (primaryGerman || secondaryGerman) sink.rewarmSwipe()
        // After the sinks, like [apply]: a sink that throws leaves the old value for a retry.
        appliedSwissSpelling = enabled
        return true
    }
}
