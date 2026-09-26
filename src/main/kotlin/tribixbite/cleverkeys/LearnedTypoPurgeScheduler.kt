package tribixbite.cleverkeys

import java.util.concurrent.Executor

/**
 * Decides WHEN the typo-hygiene purge ([tribixbite.cleverkeys.contextaware.ContextModel.purgeUnlearnable])
 * may run and freezes the lexicon it judges against. Pure JVM; the production instance lives
 * in `WordPredictor`, which reports each lexicon publication here. Unit-tested in
 * `LearnedTypoPurgeSchedulerTest`.
 *
 * ## Every configured lexicon source must be published first
 *
 * The purge deletes low-frequency n-grams containing a word no lexicon knows. A bilingual
 * user's commits all land in the PRIMARY language's store, so their secondary-language words
 * are "known" only through the secondary lexicon. That lexicon loads asynchronously and
 * normally publishes AFTER the primary one; judging the store before it exists deletes the
 * user's second language (review of `59bd4159`, HIGH) and — worse — writes the weekly stamp,
 * so nothing corrects it for seven days.
 *
 * Rule: a purge runs only when the secondary lexicon is not pending, i.e. no secondary
 * language is configured, or the configured one is the one published. Otherwise the request
 * is DEFERRED (nothing runs, no stamp is written) and re-evaluated at the next secondary
 * publication, so the purge runs from whichever source publishes LAST. A secondary that never
 * publishes (a failed load, an unreadable configuration) keeps the purge deferred: a typo that
 * survives a little longer is cheap, a deleted second language is not.
 *
 * Other lexicon sources need no deferral because they publish WITH the primary map: an
 * imported language pack (CKDT) or bundled dictionary is the map itself, custom and platform
 * user-dictionary words and the contraction alias keys are merged into it before the swap,
 * and later user-dictionary edits are applied to it in place (hence the copy below).
 *
 * ## A frozen copy, taken when the purge is queued
 *
 * The purge body runs on the learned-data persistence thread, while the main thread keeps
 * mutating the live dictionary map (user-dictionary observer, custom-word reload, a
 * synchronous reload that empties and refills it). Iterating the live map from another thread
 * judged words against whatever state it was in at that moment (review, MEDIUM) — including
 * an empty map mid-reload, which would make every word unknown. The key sets are copied here,
 * on the publishing thread, and only when a purge is actually due, so the weekly-idle common
 * path costs one stamp read.
 *
 * Thread-safety: all entry points are `@Synchronized`; publications arrive on the main thread
 * (async loads) or a loader thread (the blocking fallbacks).
 *
 * @param executor the learned-data persistence thread
 * @param isDue cheap check of the per-language purge stamp
 * @param runPurge the purge itself, given the language and the frozen "may be learned" test
 */
class LearnedTypoPurgeScheduler(
    private val executor: Executor,
    private val isDue: (language: String) -> Boolean,
    private val runPurge: (language: String, isLearnable: (String) -> Boolean) -> Int?,
    private val log: (String) -> Unit = {}
) {
    /**
     * The predictor's lexicon sources at one instant.
     *
     * @property language language of the published PRIMARY lexicon ([lexicon])
     * @property lexicon the live primary map (lexicon + user words + contraction alias keys)
     * @property userWords tracked custom/platform user-dictionary words
     * @property configuredSecondary the secondary language the user configured, or null
     * @property publishedSecondary the language whose secondary index is published, or null
     * @property secondaryContains membership in the published secondary index (immutable after
     *   publication, so it is not copied), or null when none is published
     * @property observationCount the personalization repeat tally
     */
    class LexiconState(
        val language: String,
        val lexicon: Map<String, *>,
        val userWords: Set<String>,
        val configuredSecondary: String?,
        val publishedSecondary: String?,
        val secondaryContains: ((String) -> Boolean)?,
        val observationCount: (String) -> Int
    ) {
        /** A secondary language is configured but its lexicon is not the one published. */
        val secondaryPending: Boolean
            get() = configuredSecondary != null && configuredSecondary != publishedSecondary
    }

    /** Language whose purge waits for the secondary lexicon, or null. */
    private var deferredLanguage: String? = null

    /** The primary lexicon for [LexiconState.language] was just published. */
    @Synchronized
    fun onPrimaryPublished(state: LexiconState) {
        // A new primary publication supersedes any older deferral (possibly for another language).
        deferredLanguage = null
        if (state.secondaryPending) {
            deferredLanguage = state.language
            log("Typo purge for '${state.language}' deferred until the '${state.configuredSecondary}' lexicon is published")
            return
        }
        schedule(state)
    }

    /**
     * The secondary lexicon was published, replaced or unloaded. [LexiconState.language] is
     * the language of the primary lexicon published NOW; a deferral recorded for a different
     * language is stale (that language's lexicon is no longer served) and is dropped.
     */
    @Synchronized
    fun onSecondaryChanged(state: LexiconState) {
        val deferred = deferredLanguage ?: return
        if (deferred != state.language) {
            deferredLanguage = null
            return
        }
        if (state.secondaryPending) return
        deferredLanguage = null
        schedule(state)
    }

    private fun schedule(state: LexiconState) {
        if (state.lexicon.isEmpty()) return // nothing to judge against — retried at the next load
        val language = state.language
        if (!isDue(language)) return
        // The frozen copy (see the class doc).
        val lexicon: Set<String> = HashSet(state.lexicon.keys)
        val userWords: Set<String> = HashSet(state.userWords)
        val secondary = state.secondaryContains
        val policy = LearnableWordPolicy(
            lexiconReady = { true },
            isKnownWord = { w -> w in lexicon || w in userWords || secondary?.invoke(w) == true },
            observationCount = state.observationCount
        )
        executor.execute {
            val removed = runPurge(language, policy::isLearnable)
            if (removed != null) log("Typo purge for '$language': removed $removed learned n-gram(s)")
        }
    }
}
