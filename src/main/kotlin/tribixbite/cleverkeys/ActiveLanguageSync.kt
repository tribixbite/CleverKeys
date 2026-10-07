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
 * @param initial the languages the components were constructed with, so the first refresh after
 *   startup does not reload dictionaries that are already loaded.
 */
class ActiveLanguageSync(initial: ActiveLanguages, private val sink: Sink) {

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
        applied = target

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
        return true
    }
}
