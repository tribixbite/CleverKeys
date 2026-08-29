package tribixbite.cleverkeys

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import tribixbite.cleverkeys.autocorrect.FrequencyFloor
import tribixbite.cleverkeys.autocorrect.KeyAdjacency
import tribixbite.cleverkeys.autocorrect.Morphology
import tribixbite.cleverkeys.swipe.SwipeContextRescorer
import tribixbite.cleverkeys.contextaware.ContextModel
import tribixbite.cleverkeys.langpack.LanguagePackManager
import tribixbite.cleverkeys.personalization.PersonalizationEngine
import tribixbite.cleverkeys.personalization.PersonalizedScorer
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min

/**
 * Word prediction engine that matches swipe patterns to dictionary words
 */
class WordPredictor : Predictor {
    companion object {
        private const val TAG = "WordPredictor"
        private const val MAX_PREDICTIONS_TYPING = 5
        private const val MAX_PREDICTIONS_SWIPE = 10

        /**
         * Frequency rank given to a contraction alias key that is NOT itself a word of the
         * secondary dictionary (`NormalizedPrefixIndex` ranks run 0 = most common …
         * 255 = least).
         *
         * 254 is the floor above "absent". The secondary path scores a hit as
         * `((255 - rank) * 4000) + 1000`, so this yields 5,000 — the same anchor
         * [loadPrimaryContractionKeys] already gives a new alias in the primary dictionary,
         * and the bottom of the range real dictionary words occupy. An alias is therefore
         * REACHABLE by prefix search but can never outrank a real word of the language.
         */
        private const val CONTRACTION_ALIAS_RANK = 254

        // ── Autocorrect tuning constants ────────────────────────────
        // Used by the dual-gate scoring in `autoCorrect`. Constants
        // (not config knobs) because they're calibration values that
        // should NOT need per-user tuning — exposing them as config
        // would tempt users into breaking the gate semantics.

        /**
         * Same-length minimum-exact-match-ratio gate. At least half the
         * positions must literally match before we'll consider the
         * candidate, regardless of how adjacency-flattering the
         * substitutions look. Without this, every same-length word
         * scores 0.5+ via adjacency-similarity alone and the frequency
         * tiebreaker picks common-but-unrelated words (`questin →
         * without`).
         */
        private const val MIN_SAME_LENGTH_EXACT_RATIO = 0.50f

        /**
         * Absolute cap on substituted positions for a same-length
         * correction candidate. Complements [MIN_SAME_LENGTH_EXACT_RATIO]:
         * the ratio gate scales with length (50% → ⌊L/2⌋ allowed subs), so
         * for a 7-letter word it still admits THREE wrong keys. That let
         * `broight` "correct" to `thought` (4/7 match, 3 subs `b→t r→h i→u`)
         * which then beat the real target `brought` (6/7 match, single
         * adjacent `i→u` typo) purely on a higher frequency inside
         * [SCORE_TIEBREAK_GAP].
         *
         * A correction that changes 3+ keys of a word is rarely a
         * fat-finger fix and is dangerous to apply silently. Capping at 2
         * substitutions removes those structurally-poor candidates BEFORE
         * the score/frequency tiebreaker, so the deliberate
         * frequency-breaks-near-ties behavior (`questin → question` over
         * `quentin`) is preserved while `broight → brought` is restored.
         * Binds only for length ≥ 6; shorter words remain governed by the
         * ratio gate.
         *
         * Verified accept: `broight → brought` (1 sub), `tge → the` (1),
         *   `donr → dont` (1), `thoight → thought` (1).
         * Verified reject: `broight → thought` (3 subs), `delight`/`troughs`
         *   (3 subs each) — all drop out, brought wins on score.
         */
        private const val MAX_SAME_LENGTH_SUBSTITUTIONS = 2

        /**
         * Length-diff edit-distance budget — caps allowed substitution
         * cost ABOVE the literal length difference. Each insertion or
         * deletion costs 1.0 in our weighted Levenshtein; adjacent-key
         * substitutions cost ~0.137; distant subs cost up to 1.0.
         *
         * Calibrated through three iterations against the ew-cli dictionary:
         *   - 2.0 was way too loose (let unrelated `something` through)
         *   - 1.0 was still too loose (let `season` through at ed ≈ 2.95
         *     for `wuestion → season` lengthDiff=2 case)
         *   - 0.5 is tight enough: only adjacent-key subs fit beyond the
         *     length diff, so unrelated 2-length-diff candidates (which
         *     inevitably need ≥ 1 mid/distant sub in their best alignment)
         *     get rejected, while legitimate single-char-insertion typos
         *     (`questin → question`, ed=1.0) clear `1 + 0.5 = 1.5`.
         *
         * Verified accept:
         *   - `questin → question` (lenDiff=1, ed=1.0): 1.0 ≤ 1.5 ✓
         *   - `quuestion → question` (lenDiff=1, ed=1.0): 1.0 ≤ 1.5 ✓
         *
         * Verified reject:
         *   - `wuestion → season` (lenDiff=2, ed≈2.95): 2.95 > 2.5 ✗
         *   - `wuestion → wuthering` (lenDiff=1, ed≈2.79): 2.79 > 1.5 ✗
         *   - `wuestion → something` (lenDiff=1, ed≈2.71): 2.71 > 1.5 ✗
         */
        private const val LENGTH_DIFF_ED_BUDGET = 0.5f

        // NOTE (2026-07-04): the old ALIAS_SCORE_BONUS (+0.15 to alias-key
        // candidates) is GONE. As a score bump it could beat candidates up to
        // 0.15 STRONGER than the alias — the very thing its own doc said it
        // shouldn't do ("thier" → "this'd" instead of "their"). Alias
        // preference is now a RULE in the tiebreaker: within the gap band an
        // alias wins against a non-alias only at equal-or-better RAW score.

        /**
         * Score-difference threshold for score-vs-frequency tiebreaker.
         * When two candidates differ by MORE than this in raw score, the
         * higher-scoring one wins regardless of frequency. When the gap is
         * within this band, structural rules (alias privilege, transposition
         * beats 2-sub) and then frequency decide.
         *
         * Why hybrid: same-length multi-substitution candidates have an
         * asymmetric scoring advantage over lengthDiff=1 candidates. A
         * user typing `quuestion` almost certainly meant `question`
         * (one extra letter, ed=1) not `quotation` (three subs, score
         * 0.938 vs 0.889 — tantalizingly close by score, semantically
         * miles apart). Pure score-primary picks `quotation`; pure
         * freq-primary picks `quentin` for `questin`. The 0.10 gap
         * threshold separates "clearly better" from "noise" — picks
         * `question` correctly via the freq fallback.
         *
         * Calibrated against (sim, 26/29 on the 98k dictionary, 2026-07-04):
         *   - `donr → don't` (dont/done raw-tied 0.972) → alias privilege
         *   - `thier → their` (transposition 0.97 > alias thisd 0.95) → raw
         *   - `thsi → this` (transposition beats 2-sub `that` in-band)
         *   - `tge → the` (gap 0.149 vs `weve`) → score wins (the)
         *   - `tfe → the` (gap 0.037 vs `tfw`) → freq wins (the)
         *   - `quuestion → question` (gap 0.049 vs `quotation`) → freq wins
         *   - `questin → question` (gap 0.052 vs `quentin`) → freq wins
         */
        private const val SCORE_TIEBREAK_GAP = 0.10f

        /**
         * Length-normalized score penalty for an adjacent-transposition
         * (Damerau) match in the same-length path: score = 1 − penalty/len.
         * Calibrated (0.25 → 0.15, 2026-07-04) so a transposition ranks
         * BETWEEN a single adjacent-key substitution and a 2-substitution
         * candidate — where one swap operation structurally belongs:
         *   "teh"→"the" 0.950 vs "ten" (1 sub) 0.959 → in-band, freq → the ✓
         *   "thier"→"their" 0.970 vs "thisd" (2 subs) 0.950 → raw win ✓
         * Verified accept: teh→the, hte→the, becuase→because, recieve→receive,
         * thsi→this, waht→what, taht→that, jsut→just, liek→like, onyl→only.
         * Note: transposition detection is positional, not keyboard-aware — a swap of
         * physically distant keys scores the same 1−0.15/len, and in rare short-word
         * cases a 1-substitution alias candidate at equal-or-better raw score can win
         * via alias privilege (e.g. hypothetical dnot→snot vs don't); accepted as an
         * edge (2026-07-13 review H4).
         */
        private const val TRANSPOSITION_PENALTY = 0.15f
        private const val MAX_EDIT_DISTANCE = 2
        private const val MAX_RECENT_WORDS = 20 // Keep last 20 words for language detection
        private const val PREFIX_INDEX_MAX_LENGTH = 3 // Index prefixes up to 3 chars

        // Real English words that also appear as contraction bases in contractions_en.json.
        // These must NOT be autocorrected (e.g., "well" should stay "well", not become "we'll").
        // Excludes "hes"/"shes"/"intl" which are NOT real words despite appearing in pairings data.
        private val REAL_WORD_CONTRACTION_BASES = setOf(
            "well", "were", "hell", "shed", "shell", "wed",
            "editors", "girls", "readers", "states", "whore"
        )

        // Static flag to signal all WordPredictor instances need to reload custom/user/disabled words
        @Volatile
        private var needsReload = false

        /**
         * Signal all WordPredictor instances to reload custom/user/disabled words on next prediction
         * Called by Dictionary Manager when user makes changes
         */
        @JvmStatic
        fun signalReloadNeeded() {
            needsReload = true
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Reload signal set - all instances will reload on next prediction")
        }
    }

    // OPTIMIZATION v4 (perftodos4.md): Use AtomicReference for lock-free atomic map swapping
    // Allows O(1) atomic swap instead of O(n) putAll() on main thread during async loading
    private val dictionary: AtomicReference<MutableMap<String, Int>> = AtomicReference(mutableMapOf())
    private val prefixIndex: AtomicReference<MutableMap<String, MutableSet<String>>> = AtomicReference(mutableMapOf())

    // Cached max dictionary frequency, used to scale the autocorrect frequency
    // floor (see FrequencyFloor). Recomputed lazily whenever the dictionary size
    // changes — covers initial load, language switch, and custom-word additions
    // without hooking every mutation site. Value changes that don't alter size
    // (e.g. DictionaryWord.enabled toggles) don't affect frequencies, so a
    // size-keyed cache is sufficient.
    @Volatile private var cachedMaxFreq: Int = 0
    @Volatile private var cachedMaxFreqForSize: Int = -1
    private var bigramModel: BigramModel? = BigramModel.getInstance(null)
    private var contextModel: ContextModel? = null // Phase 7.1: Dynamic N-gram model
    private var personalizationEngine: PersonalizationEngine? = null // Phase 7.2: Personalized learning
    private var personalizedScorer: PersonalizedScorer? = null // Phase 7.2: Adaptive scoring
    private var languageDetector: LanguageDetector? = LanguageDetector()
    private var multiLanguageManager: MultiLanguageManager? = null // Phase 8.3: Multi-language models
    private var currentLanguage: String = "en" // Default to English
    private val recentWords: MutableList<String> = mutableListOf() // For language detection
    private var config: Config? = null
    private var adaptationManager: UserAdaptationManager? = null
    private var context: Context? = null // For accessing SharedPreferences for disabled words
    private var disabledWords: MutableSet<String> = mutableSetOf() // Cache of disabled words
    // Track custom/user-added words — these override disabled status (Issue #72: Boston bug)
    @Volatile
    private var customAndUserWords: Set<String> = emptySet()
    private var lastReloadTime: Long = 0

    // Issue #72: Track original case of user-added words (proper nouns)
    // Maps lowercase word to original case: "boston" -> "Boston"
    // v1.2.7: Use ConcurrentHashMap for thread-safety (accessed from async loader thread)
    private val userWordOriginalCase: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

    // OPTIMIZATION: Async loading state
    @Volatile
    private var isLoadingState: Boolean = false
    private val asyncLoader: AsyncDictionaryLoader = AsyncDictionaryLoader()

    // OPTIMIZATION: UserDictionary and custom words observer
    private var dictionaryObserver: UserDictionaryObserver? = null
    private var observerActive: Boolean = false

    // Track contraction aliases added to dictionary (e.g., "im" → "i'm", "dont" → "don't")
    // These are in the dictionary for prediction purposes but should still be autocorrected
    @Volatile
    private var contractionAliases: Map<String, String> = emptyMap()

    // v1.1.93: Secondary language dictionary for bilingual touch typing
    @Volatile
    private var secondaryIndex: NormalizedPrefixIndex? = null
    private var secondaryLanguageCode: String = "none"

    /**
     * Set context for accessing disabled words from SharedPreferences
     */
    fun setContext(context: Context) {
        this.context = context
        loadDisabledWords()

        // ARC-010: the shipped static bigram asset for the active language is
        // the next-word cold-start seed. Loaded off the main thread — until it
        // lands, BigramModel serves its hardcoded pairs, so there is no state
        // to wait on. Idempotent per language.
        bigramModel?.loadStaticContinuationsAsync(context, currentLanguage)

        // Phase 7.1: Initialize ContextModel for dynamic N-gram predictions
        // (language-keyed view over the singleton BigramStore — 2026-08-06 persistence fix)
        if (contextModel == null) {
            contextModel = ContextModel(context, currentLanguage)
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "ContextModel initialized for dynamic N-gram predictions (lang=$currentLanguage)")
        }

        // Phase 7.2: Initialize PersonalizationEngine for personalized learning
        if (personalizationEngine == null) {
            personalizationEngine = PersonalizationEngine(context)
            personalizedScorer = PersonalizedScorer(personalizationEngine!!)
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "PersonalizationEngine and PersonalizedScorer initialized for adaptive predictions")
        }

        // Phase 8.3: Initialize Multi-Language support if enabled.
        // Phase 8.4's MultiLanguageDictionaryManager was deleted with the neural engine
        // (2026-08-18): it wrapped an OptimizedVocabulary per language and was constructed
        // here but never read — per-language tap dictionaries come from DictionaryManager.
        val enableMultiLang = config?.enable_multilang ?: false
        if (enableMultiLang) {
            if (multiLanguageManager == null) {
                val primaryLang = config?.primary_language ?: "en"
                multiLanguageManager = MultiLanguageManager(context, primaryLang)
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "MultiLanguageManager initialized (primary: $primaryLang)")
            }
        }

        // Initialize dictionary observer for automatic updates
        if (dictionaryObserver == null) {
            dictionaryObserver = UserDictionaryObserver(context).apply {
                setChangeListener(object : UserDictionaryObserver.ChangeListener {
                    override fun onUserDictionaryChanged(addedWords: Map<String, Int>, removedWords: Set<String>) {
                        handleIncrementalUpdate(addedWords, removedWords)
                        // ARC-081/ARC-082: since the platform user dictionary now also feeds the
                        // SWIPE lexicons, a provider change invalidates their memoized trie /
                        // template index too. This listener is the only place the ContentObserver
                        // surfaces, so it owns that trigger; the custom-words half is owned by the
                        // preference seam (PreferenceUIUpdateHandler), and the scheduler coalesces
                        // the two so a single "add word" that writes both costs one rebuild.
                        SwipeRewarmScheduler.requestRewarm()
                    }

                    override fun onCustomWordsChanged(addedOrModified: Map<String, Int>, removed: Set<String>) {
                        handleIncrementalUpdate(addedOrModified, removed)
                    }
                })
            }
        }
    }

    /**
     * Start observing UserDictionary and custom words for changes.
     *
     * OPTIMIZATION: Enables automatic incremental updates without polling.
     * Call this after dictionary is loaded to receive change notifications.
     */
    fun startObservingDictionaryChanges() {
        dictionaryObserver?.let {
            if (!observerActive) {
                it.start()
                observerActive = true
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                    Log.d(TAG, "Started observing dictionary changes")
                }
            }
        }
    }

    /**
     * Stop observing dictionary changes.
     * Call this when WordPredictor is no longer needed.
     */
    override fun stopObservingDictionaryChanges() {
        dictionaryObserver?.let {
            if (observerActive) {
                it.stop()
                observerActive = false
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                    Log.d(TAG, "Stopped observing dictionary changes")
                }
            }
        }
    }

    /**
     * Handle incremental dictionary updates.
     *
     * OPTIMIZATION: Updates dictionary and prefix index without full rebuild.
     *
     * @param addedOrModified Words to add or update (word -> frequency)
     * @param removed Words to remove
     */
    private fun handleIncrementalUpdate(addedOrModified: Map<String, Int>, removed: Set<String>) {
        var hasChanges = false

        // Remove words
        if (removed.isNotEmpty()) {
            removed.forEach { dictionary.get().remove(it) }
            removeFromPrefixIndex(removed)
            hasChanges = true
        }

        // Add or modify words
        if (addedOrModified.isNotEmpty()) {
            dictionary.get().putAll(addedOrModified)
            addToPrefixIndex(addedOrModified.keys)
            hasChanges = true
        }

        if (hasChanges) {
            Log.i(TAG, "Incremental dictionary update: +${addedOrModified.size} words, -${removed.size} words")
        }
    }

    /**
     * Load disabled words from SharedPreferences
     *
     * v1.1.92: Use language-specific key (disabled_words_${lang}) instead of legacy global key
     */
    private fun loadDisabledWords() {
        if (context == null) {
            disabledWords = mutableSetOf()
            return
        }

        val ctx = context
        if (ctx == null) {
            disabledWords = mutableSetOf()
            return
        }
        val prefs = DirectBootAwarePreferences.get_shared_preferences(ctx)
        // v1.1.92: Use language-specific disabled words key
        val disabledWordsKey = LanguagePreferenceKeys.disabledWordsKey(currentLanguage)
        val disabledSet = prefs.getStringSet(disabledWordsKey, emptySet()) ?: emptySet()
        // Create a new HashSet to avoid modifying the original
        disabledWords = disabledSet.toMutableSet()
        if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Loaded ${disabledWords.size} disabled words for '$currentLanguage'")
    }

    /**
     * Check if a word is disabled.
     * Public (2026-07-15): the add-to-dictionary prompt guard (UT-2) needs it
     * so a possessive of a DISABLED base is not suppressed as "known".
     */
    override fun isWordDisabled(word: String): Boolean {
        val lower = word.lowercase()
        // Custom/user-added words override disabled status — if user explicitly added
        // "Boston" after disabling "boston", the custom word wins
        return disabledWords.contains(lower) && !customAndUserWords.contains(lower)
    }

    /**
     * Reload disabled words (called when Dictionary Manager updates the list)
     */
    fun reloadDisabledWords() {
        loadDisabledWords()
    }

    /**
     * Reload custom words and user dictionary (called when Dictionary Manager makes changes)
     * PERFORMANCE: Only reloads small dynamic sets, overwrites existing entries
     * Also rebuilds prefix index to include new words
     *
     * v1.1.90: Uses currentLanguage to filter UserDictionary by locale.
     */
    override fun reloadCustomAndUserWords() {
        context?.let {
            // Issue #72: Clear proper noun case map before reloading
            userWordOriginalCase.clear()
            // v1.1.90: Pass currentLanguage to filter by locale
            val customWords = loadCustomAndUserWords(it, currentLanguage)
            customAndUserWords = customWords  // Track for disabled-word override check
            // NOTE: Full rebuild needed here because we don't track which words were removed
            // Future optimization: track previous custom words to compute diff (added/removed)
            buildPrefixIndex()
            lastReloadTime = System.currentTimeMillis()
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                Log.d(TAG, "Reloaded ${customWords.size} custom/user words for '$currentLanguage' + rebuilt prefix index")
            }
        }
    }

    /**
     * Check if reload is needed and perform it
     * Called at start of prediction
     */
    private fun checkAndReload() {
        if (needsReload && context != null) {
            reloadDisabledWords()
            reloadCustomAndUserWords()
            // Don't clear flag - let all instances reload
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Auto-reloaded dictionaries due to signal")
        }
    }

    /**
     * Set the config for weight access
     */
    override fun setConfig(config: Config) {
        this.config = config

        // H3 (review 2026-08-06): keep the selection-adaptation store's enabled
        // flag synced to the master gate so its multiplier READS are inert with
        // the master off — belt-and-braces alongside the canUseAdaptation guard
        // at every read site below.
        syncAdaptationEnabled()

        // Phase 7.2: Update personalization engine settings when config changes.
        // Task A (2026-08-06): the MASTER on-device-learning gate ANDs into the
        // engine's enabled state, so with the master off the engine neither
        // records (recordWordTyped no-ops) nor boosts (getPersonalizationBoost
        // returns 0) — the learned vocabulary becomes fully inert.
        personalizationEngine?.let { engine ->
            engine.setEnabled(
                LearningGate.canLearnPersonalization(
                    config.on_device_learning_enabled,
                    config.personalized_learning_enabled
                )
            )

            // Parse learning aggression from config
            val aggression = try {
                PersonalizationEngine.LearningAggression.valueOf(config.learning_aggression)
            } catch (e: IllegalArgumentException) {
                PersonalizationEngine.LearningAggression.BALANCED // Default if invalid
            }
            engine.setLearningAggression(aggression)
        }
    }

    /**
     * Set the user adaptation manager for frequency adjustment
     */
    fun setUserAdaptationManager(adaptationManager: UserAdaptationManager) {
        this.adaptationManager = adaptationManager
        // H3: setConfig may already have run — sync the read gate now.
        syncAdaptationEnabled()
    }

    /**
     * H3 (review 2026-08-06): may the learned selection-adaptation history be
     * read right now? Master off (or config not yet supplied — fail CLOSED)
     * makes the store inert, not just frozen.
     */
    private fun canUseAdaptation(): Boolean =
        LearningGate.canUseAdaptation(config?.on_device_learning_enabled ?: false)

    /** Mirror the master gate into the adaptation store's own enabled flag. */
    private fun syncAdaptationEnabled() {
        config?.let { cfg ->
            adaptationManager?.setEnabled(LearningGate.canUseAdaptation(cfg.on_device_learning_enabled))
        }
    }

    /**
     * Set the active language for N-gram predictions
     *
     * v1.1.91: Also updates UserDictionaryObserver to filter by new language.
     * v1.1.92: Reloads disabled words for language-specific key.
     */
    fun setLanguage(language: String) {
        // L6 (review 2026-08-06): the rolling learn window must not straddle a
        // language switch — otherwise the first commits after the switch record
        // mixed-language pairs into the NEW language's store.
        if (language != currentLanguage) {
            recentWords.clear()
        }
        currentLanguage = language
        bigramModel?.let {
            it.setLanguage(language)
            // ARC-010: pull in the new language's shipped static bigram asset
            // (async). No-op when it has already been attempted.
            context?.let { ctx -> it.loadStaticContinuationsAsync(ctx, language) }
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "N-gram language set to: $language")
        }

        // Keep the learned context LM language-isolated (2026-08-06 keying fix)
        contextModel?.language = language

        // Phase 8.3: Switch multi-language models if enabled
        multiLanguageManager?.let {
            val switched = it.switchLanguage(language)
            if (switched) {
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "MultiLanguageManager switched to: $language")
            } else {
                Log.w(TAG, "Failed to switch MultiLanguageManager to: $language")
            }
        }

        // v1.1.91: Update observer to filter by new language
        dictionaryObserver?.setLanguage(language)

        // v1.1.92: Reload disabled words for language-specific key
        loadDisabledWords()
    }

    /**
     * Get the current active language
     */
    fun getCurrentLanguage(): String {
        return bigramModel?.getCurrentLanguage() ?: "en"
    }

    /**
     * Check if a language is supported by the N-gram model
     */
    fun isLanguageSupported(language: String): Boolean {
        return bigramModel?.isLanguageSupported(language) ?: false
    }

    /**
     * Check if a word exists in the dictionary.
     * Used for determining whether to offer "Add to dictionary?" prompt.
     *
     * @param word The word to check (case-insensitive)
     * @return true if word is in dictionary, false otherwise
     */
    override fun isInDictionary(word: String): Boolean {
        if (word.isEmpty()) return false
        val lowerWord = word.lowercase()
        // Check main dictionary
        if (dictionary.get().containsKey(lowerWord)) {
            return true
        }
        // Check if user has typed it frequently (adaptation manager) — gated
        // (H3): with the master off, learned selection history must not
        // suppress add-to-dictionary prompts.
        if (!canUseAdaptation()) return false
        val adaptationMultiplier = adaptationManager?.getAdaptationMultiplier(lowerWord) ?: 0f
        return adaptationMultiplier > 1.0f
    }

    /**
     * Issue #72: Apply original case from user dictionary to a word.
     * If user added "Boston" to dictionary, this transforms "boston" → "Boston".
     *
     * @param word Word to potentially restore case for (should be lowercase)
     * @return Word with original case if found in user dictionary, otherwise unchanged
     */
    fun applyUserWordCase(word: String): String {
        val lowerWord = word.lowercase()
        return userWordOriginalCase[lowerWord] ?: word
    }

    /**
     * Issue #72: Apply original case to a list of predictions.
     *
     * @param words List of predicted words
     * @return List with proper noun case restored where applicable
     */
    override fun applyUserWordCaseToList(words: List<String>): List<String> {
        return words.map { applyUserWordCase(it) }
    }

    /**
     * Add a word to the recent words list for language detection and run the
     * gated learn funnel.
     *
     * @param fieldAllowsPersonalizedLearning false when the active editor set
     *   `IME_FLAG_NO_PERSONALIZED_LEARNING` (M5 incognito contract) — the
     *   language-detection window still updates (detection is not learning),
     *   but NO learn path runs.
     */
    override fun addWordToContext(word: String?, fieldAllowsPersonalizedLearning: Boolean) {
        if (word.isNullOrBlank()) return

        val normalizedWord = word.lowercase().trim()
        recentWords.add(normalizedWord)

        // Keep only the most recent words
        while (recentWords.size > MAX_RECENT_WORDS) {
            recentWords.removeAt(0)
        }

        // THE learn funnel (Task A master privacy gate, 2026-08-06): every
        // typing-derived learn path — context LM (bigrams + trigrams, Phase 7.1)
        // and personalization vocabulary (Phase 7.2) — flows through
        // LearningGate.learnCommittedWord, which short-circuits BEFORE any
        // in-RAM mutation or persistence when the master gate (or the
        // per-feature gate) is off. The gate logic is pure and unit-tested
        // (OnDeviceLearningPrivacyTest).
        //
        // M2 (review 2026-08-06): gate reads fail CLOSED — a predictor whose
        // Config was never supplied (e.g. constructed by DictionaryManager
        // before global config threading) must never learn.
        //
        // M3: the context sink is recordCommit (NEWEST bigram/trigram only) —
        // the previous full-window recordSequence replay re-recorded earlier
        // pairs on every commit, inflating a single typing past the ≥2 floor.
        LearningGate.learnCommittedWord(
            recentWords = recentWords,
            committedWord = normalizedWord,
            onDeviceLearningEnabled = config?.on_device_learning_enabled ?: false,
            contextAwareEnabled = config?.context_aware_predictions_enabled ?: false,
            personalizedLearningEnabled = config?.personalized_learning_enabled ?: false,
            recordSequence = { sequence -> contextModel?.recordCommit(sequence) },
            recordWordUsage = { word -> personalizationEngine?.recordWordTyped(word) },
            fieldAllowsPersonalizedLearning = fieldAllowsPersonalizedLearning
        )

        // Try to detect language change if we have enough words
        if (recentWords.size >= 5) {
            tryAutoLanguageDetection()
        }
    }

    /**
     * Try to automatically detect and switch language based on recent words
     */
    private fun tryAutoLanguageDetection() {
        // Phase 8.3: Skip entirely if auto-detect is disabled
        val autoDetectEnabled = config?.auto_detect_language ?: false
        if (!autoDetectEnabled) return

        // Use MultiLanguageManager for detection and switching if available
        if (multiLanguageManager != null) {
            val sensitivity = config?.language_detection_sensitivity ?: 0.6f
            val detected = multiLanguageManager?.detectAndSwitch(recentWords, sensitivity)
            if (detected != null) {
                currentLanguage = detected
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                    Log.d(TAG, "MultiLanguageManager auto-detected and switched to: $detected")
                }
                bigramModel?.setLanguage(detected)
                contextModel?.language = detected
                // L6: the window that triggered detection holds PRIOR-language
                // words — clear it so the first post-switch commits don't learn
                // mixed-language pairs into the new language's store.
                recentWords.clear()
                return
            }
        }

        // Fallback to legacy detection only if auto-detect enabled but MultiLanguageManager unavailable
        languageDetector ?: return

        val detectedLanguage = languageDetector?.detectLanguageFromWords(recentWords)
        if (detectedLanguage != null && detectedLanguage != currentLanguage) {
            if (bigramModel?.isLanguageSupported(detectedLanguage) == true) {
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                    Log.d(TAG, "Auto-detected language change from $currentLanguage to $detectedLanguage")
                }
                setLanguage(detectedLanguage)
            }
        }
    }

    /**
     * Manually detect language from a text sample
     */
    fun detectLanguage(text: String): String? {
        return languageDetector?.detectLanguage(text)
    }

    /**
     * Get the list of recent words used for language detection
     */
    fun getRecentWords(): List<String> {
        return recentWords.toList()
    }

    /**
     * Clear the recent words context
     */
    override fun clearContext() {
        recentWords.clear()
    }

    /**
     * Autocorrect-undo rollback (2026-08-06): the user REJECTED an autocorrect
     * whose result had already been fed through the learn funnel by
     * [addWordToContext]. Removes the rejected word from the rolling learn
     * window and decrements the n-grams its commit recorded, so:
     * - the undo's replacement commit cannot learn a bogus
     *   `rejected → original` pair (the rejected word is gone from the window),
     * - the `previous → rejected` pair's frequency returns to its pre-commit
     *   value (a once-mislearned pair stays below the ≥2 confidence floors).
     *
     * The store decrement runs only under the SAME gate conditions the learn
     * used ([LearningGate.canLearnContext] + the per-field incognito flag) —
     * if the original record was suppressed, nothing is decremented, so
     * legitimately-accumulated frequencies are never reduced. The one-count
     * personalization usage of the rejected word is deliberately left in place
     * (benign: final autocorrect only ever produces dictionary words).
     *
     * No-op when [word] is not the newest window entry (e.g. a sentence
     * boundary or session flush cleared the window in between).
     *
     * @param word the autocorrected word being undone (as committed)
     * @param fieldAllowsPersonalizedLearning the active field's incognito flag,
     *   as passed to [addWordToContext] for the original commit
     */
    override fun rollbackCommittedWord(word: String, fieldAllowsPersonalizedLearning: Boolean) {
        val normalized = word.lowercase().trim()
        if (recentWords.isEmpty() || recentWords.last() != normalized) return

        val master = (config?.on_device_learning_enabled ?: false) && fieldAllowsPersonalizedLearning
        val contextAware = config?.context_aware_predictions_enabled ?: false
        if (LearningGate.canLearnContext(master, contextAware) && recentWords.size >= 2) {
            val sequenceLength = kotlin.math.min(LearningGate.CONTEXT_WINDOW, recentWords.size)
            contextModel?.rollbackCommit(recentWords.takeLast(sequenceLength))
        }
        recentWords.removeAt(recentWords.size - 1)
    }

    /**
     * Sentence boundary hook (2026-08-06, audit §4.6): called when the user types
     * sentence-final punctuation (`.` `?` `!`). Clears the learning window so
     * [addWordToContext]'s recordSequence never learns bigrams that span a
     * sentence boundary (cross-boundary pairs are noise for both context boosting
     * and next-word generation).
     */
    override fun onSentenceBoundary() {
        recentWords.clear()
    }

    /**
     * Next-word prediction support (audit 2026-08-06 §4): learned continuations
     * for the given committed-word context, ranked by conditional probability.
     * Empty when the context LM is disabled or has no data for the context.
     */
    /**
     * Learned evidence for each word of a swipe slate, or **null** when rescoring must not run.
     *
     * Step 2 of `docs/specs/ctc-context-rescoring-and-tunables.md`. Mirrors
     * [getNextWordCandidates]: the M2 fail-closed gating lives HERE, in the class that owns the
     * config reference, so no caller can reach the learned stores without passing it.
     *
     * ## null vs a list of nulls — the distinction is load-bearing
     *
     * - **null** = do not rescore at all. The caller must pass its slate through untouched, by
     *   reference. Returned when the gate fails or the stores are not resident.
     * - **a list containing [SwipeContextRescorer.Evidence.NONE]** = rescore; these particular
     *   candidates simply had no confident learned continuation. That is the ordinary case, and
     *   an all-NONE list yields the identity ordering anyway, since every boost is then neutral.
     *
     * Collapsing the two would make "learning is off" indistinguishable from "learning is on but
     * knows nothing" — and the privacy contract is that the OFF output is byte-identical to
     * today's, produced by not running rather than by running to a no-op.
     *
     * ## Why the store-residency check is not merely an optimisation
     *
     * The first access to a language's n-gram tables builds them from persisted storage. This
     * runs on the MAIN THREAD during a swipe, where that load is the documented cause of
     * first-swipe jank, so a cold store means SKIP — never block. The next-word append path warms
     * the stores on the executor moments later, so the cost is at most one unrescored swipe.
     *
     * @param words the slate, already in display form (`"don't"`, `"café"`).
     * @param contextWords preceding words, oldest first, as the stores key them.
     */
    override fun getSwipeContextEvidence(
        words: List<String>,
        contextWords: List<String>,
    ): List<SwipeContextRescorer.Evidence>? {
        if (words.isEmpty() || contextWords.isEmpty()) return null

        // Null config fails CLOSED (M2) — same gate as getNextWordCandidates and
        // calculateUnifiedScore's boost read.
        val canUse = LearningGate.canUseLearnedContext(
            config?.on_device_learning_enabled ?: false,
            config?.context_aware_predictions_enabled ?: false
        )
        if (!canUse) return null

        val model = contextModel ?: return null
        if (!model.isLoadedInMemory()) return null

        return words.map { word ->
            val continuation = model.getContextEvidence(word.lowercase(), contextWords)
                ?: return@map SwipeContextRescorer.Evidence.NONE
            SwipeContextRescorer.Evidence(
                // The REAL boost function, not a local copy of `(1 + p)^2`: the formula, its
                // exponent and its clamp must have exactly one definition.
                boost = model.boostFor(continuation).toDouble(),
                frequency = continuation.frequency,
                probability = continuation.probability,
            )
        }
    }

    override fun getNextWordCandidates(
        contextWords: List<String>,
        maxResults: Int
    ): List<tribixbite.cleverkeys.contextaware.ContextContinuation> {
        // Task A: the master gate makes the learned store inert for READS too —
        // next-word candidates come exclusively from learned data. Null config
        // fails CLOSED (M2).
        val canUse = LearningGate.canUseLearnedContext(
            config?.on_device_learning_enabled ?: false,
            config?.context_aware_predictions_enabled ?: false
        )
        if (!canUse) return emptyList()
        return contextModel?.getNextWordCandidates(contextWords, maxResults) ?: emptyList()
    }

    /**
     * ARC-020 cold start: the SHIPPED static continuations of the last context
     * word, used only to FILL next-word slots the learned store could not.
     *
     * Deliberately carries NO LearningGate check, unlike every read above it.
     * This is not learned or personal data — it is the same read-only asset for
     * every install, so gating it on the learning prefs would be theatre. What
     * keeps it honest is the CALLER: both call sites
     * (`SuggestionHandler.generateNextWordCandidates` and
     * `maybeShowNextWordPredictions`) invoke it only after
     * `NextWordPredictor.shouldShow`, so the seed inherits the full next-word
     * gate — feature pref, master learning gate, context-LM pref, incognito
     * field, password/prompt/Termux — without adding a gate read of its own.
     * Pinned by `LearningWiringDriftTest`.
     *
     * @return continuations ranked best-first; empty when the context is empty,
     *   the language has no static data, or the previous word is unknown
     */
    override fun getStaticNextWordSeed(
        contextWords: List<String>,
        maxResults: Int
    ): List<StaticBigramSeed.Continuation> {
        val prevWord = contextWords.lastOrNull() ?: return emptyList()
        return bigramModel?.getPredictions(prevWord, maxResults) ?: emptyList()
    }

    /**
     * Personalization boost (0..6) for a word — 0 when personalization or the
     * master on-device-learning gate is off, or the word is unknown. Used by
     * next-word re-ranking.
     */
    override fun getPersonalizationBoostFor(word: String): Float {
        // Null config fails CLOSED (M2).
        val canUse = LearningGate.canLearnPersonalization(
            config?.on_device_learning_enabled ?: false,
            config?.personalized_learning_enabled ?: false
        )
        if (!canUse) return 0f
        return personalizationEngine?.getPersonalizationBoost(word) ?: 0f
    }

    /**
     * @return true if the word is in the user's learned personal vocabulary.
     * Next-word filter: membership here OR in the dictionary is required so
     * typo'd garbage absorbed by the bigram store never surfaces.
     */
    override fun isInUserVocabulary(word: String): Boolean {
        return personalizationEngine?.hasWord(word) ?: false
    }

    /**
     * Checkpoint all learned data (context LM bigrams + personalization vocabulary)
     * to persistent storage. Asynchronous flush of the debounced write-back stores;
     * cheap no-op when nothing is dirty.
     *
     * Called from the two lifecycle boundaries that remain after ARC-079 deleted
     * DictionaryManager's predictor cache: input-view finish (CleverKeysService →
     * PredictionCoordinator.flushLearnedData) and coordinator shutdown
     * (PredictionCoordinator.shutdown).
     */
    override fun persistLearnedData() {
        contextModel?.save()
        personalizationEngine?.persist()
    }

    /**
     * Load dictionary from language packs or assets.
     * v1.2.5 FIX: Also checks installed language packs (issue #63 root cause)
     */
    fun loadDictionary(context: Context, language: String) {
        dictionary.get().clear()
        prefixIndex.get().clear()

        var loadedBinary = false

        // v1.2.5 FIX: First try loading from installed language packs
        // This fixes autocorrect for languages only available via language pack (e.g., Dutch)
        // Without this, WordPredictor's dictionary would be empty and autocorrect would
        // incorrectly "correct" valid swipe predictions (issue #63)
        try {
            val packManager = LanguagePackManager.getInstance(context)
            val dictFile = packManager.getDictionaryPath(language)
            if (dictFile != null) {
                loadedBinary = BinaryDictionaryLoader.loadDictionaryWithPrefixIndexFromFile(
                    dictFile, dictionary.get(), prefixIndex.get()
                )
                if (loadedBinary) {
                    Log.i(TAG, "Loaded dictionary from language pack: $language (${dictionary.get().size} words)")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load from language pack: $language", e)
        }

        // Fall back to bundled assets if language pack not available
        if (!loadedBinary) {
            // OPTIMIZATION: Try binary format first (5-10x faster than JSON)
            // Binary format includes pre-built prefix index, eliminating runtime computation
            val binaryFilename = "dictionaries/${language}_enhanced.bin"
            loadedBinary = BinaryDictionaryLoader.loadDictionaryWithPrefixIndex(
                context, binaryFilename, dictionary.get(), prefixIndex.get()
            )

            if (loadedBinary) {
                Log.i(TAG, "Loaded binary dictionary from assets with ${dictionary.get().size} words and ${prefixIndex.get().size} prefixes")
            }
        }

        if (!loadedBinary) {
            // Fall back to JSON format if binary not available
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Binary dictionary not available, falling back to JSON")

            val jsonFilename = "dictionaries/${language}_enhanced.json"
            try {
                val reader = BufferedReader(InputStreamReader(context.assets.open(jsonFilename)))
                val jsonBuilder = StringBuilder()
                reader.useLines { lines ->
                    lines.forEach { jsonBuilder.append(it) }
                }

                // Parse JSON object
                val jsonDict = JSONObject(jsonBuilder.toString())
                val keys = jsonDict.keys()
                while (keys.hasNext()) {
                    val word = keys.next().lowercase()
                    val frequency = jsonDict.getInt(word)
                    // Frequency is 128-255, scale to 100-10000 range for better scoring
                    val scaledFreq = 100 + ((frequency - 128) / 127.0 * 9900).toInt()
                    dictionary.get()[word] = scaledFreq
                }
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Loaded JSON dictionary: $jsonFilename with ${dictionary.get().size} words")
            } catch (e: Exception) {
                Log.w(TAG, "JSON dictionary not found, trying text format: ${e.message}")

                // Fall back to text format (word-per-line)
                val textFilename = "dictionaries/${language}_enhanced.txt"
                try {
                    val reader = BufferedReader(InputStreamReader(context.assets.open(textFilename)))
                    reader.useLines { lines ->
                        lines.forEach { line ->
                            val word = line.trim().lowercase()
                            if (word.isNotEmpty()) {
                                dictionary.get()[word] = 1000 // Default frequency
                            }
                        }
                    }
                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Loaded text dictionary: $textFilename with ${dictionary.get().size} words")
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to load dictionary: ${e2.message}")
                }
            }

            // Build prefix index for fast lookup (only needed if JSON/text was loaded)
            buildPrefixIndex()
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Built prefix index: ${prefixIndex.get().size} prefixes for ${dictionary.get().size} words")
        }

        // Load custom words and user dictionary (additive to main dictionary)
        // OPTIMIZATION v2: Use incremental prefix index updates instead of full rebuild
        // v1.1.90: Pass language to filter UserDictionary by locale
        val customWords = loadCustomAndUserWords(context, language)
        customAndUserWords = customWords  // Track for disabled-word override check

        // Add custom words to prefix index (incremental update)
        if (customWords.isNotEmpty()) {
            if (loadedBinary) {
                // Binary format: prefix index is pre-built, just add custom words
                addToPrefixIndex(customWords)
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                    Log.d(TAG, "Added ${customWords.size} custom words to prefix index incrementally")
                }
            } else {
                // JSON/text format: prefix index needs full rebuild anyway (includes custom words)
                buildPrefixIndex()
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                    Log.d(TAG, "Built prefix index with custom words: ${prefixIndex.get().size} prefixes")
                }
            }
        }

        // v1.2.7: Load contraction keys (apostrophe-free forms) into primary dictionary
        // This allows typing "dont" or "cant" to find "don't" or "can't" in predictions
        val contractionKeysAdded = loadPrimaryContractionKeys(context, language)
        if (contractionKeysAdded > 0) {
            Log.i(TAG, "Added $contractionKeysAdded contraction keys to primary prefix index for '$language'")
        }

        // Set the N-gram model language to match the dictionary
        setLanguage(language)
    }

    /**
     * Load dictionary asynchronously on background thread.
     *
     * OPTIMIZATION: Prevents UI freezes during dictionary loading.
     * The callback will be invoked on the main thread when loading completes.
     *
     * @param context Android context for asset access
     * @param language Language code (e.g., "en")
     * @param callback Callback for load completion (optional, can be null)
     */
    override fun loadDictionaryAsync(context: Context, language: String, callback: Runnable?) {
        // v1.2.0: Don't ignore reload requests - AsyncDictionaryLoader will cancel previous task
        // This fixes language toggle not reloading dictionary when initial load is in progress
        if (isLoadingState) {
            Log.i(TAG, "Dictionary load in progress, will cancel and reload for '$language'")
            isLoadingState = false  // Reset flag so new load can proceed
        }

        asyncLoader.loadDictionaryAsync(context, language, object : AsyncDictionaryLoader.LoadCallback {
            override fun onLoadStarted(lang: String) {
                isLoadingState = true
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Started async dictionary load: $lang")
            }

            override fun onLoadCustomWords(
                ctx: Context,
                dictionary: MutableMap<String, Int>,
                prefixIndex: MutableMap<String, MutableSet<String>>
            ): Set<String> {
                // OPTIMIZATION v4 (perftodos4.md): This runs on BACKGROUND THREAD!
                // Load custom words into the maps before they're swapped on main thread
                // v1.1.90: Pass language to filter UserDictionary by locale
                val customWords = loadCustomAndUserWordsIntoMap(ctx, dictionary, language)
                customAndUserWords = customWords  // Track for disabled-word override check

                // Add custom words to prefix index
                if (customWords.isNotEmpty()) {
                    addToPrefixIndexForMap(customWords, prefixIndex)
                }

                // v1.2.7: Load contraction keys (apostrophe-free forms) for primary language
                // This allows typing "dont" or "cant" to find "don't" or "can't"
                val contractionKeys = loadContractionKeysIntoMaps(ctx, dictionary, prefixIndex, language)
                if (contractionKeys > 0) {
                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Added $contractionKeys contraction keys during async load for '$language'")
                }

                return customWords
            }

            override fun onLoadComplete(
                dictionary: Map<String, Int>,
                prefixIndex: Map<String, Set<String>>
            ) {
                // OPTIMIZATION v4 (perftodos4.md): ATOMIC SWAP on main thread
                // All expensive operations (loading, custom words, prefix indexing) happened on background thread
                // This callback just swaps the maps atomically in O(1) time

                // ATOMIC SWAP: Replace entire maps in <1ms operation on main thread
                @Suppress("UNCHECKED_CAST")
                this@WordPredictor.dictionary.set(dictionary as MutableMap<String, Int>)
                @Suppress("UNCHECKED_CAST")
                this@WordPredictor.prefixIndex.set(prefixIndex as MutableMap<String, MutableSet<String>>)

                // Set the N-gram model language
                setLanguage(language)

                isLoadingState = false
                // v1.2.0: Enhanced logging for debugging language toggle issues
                val sampleWords = this@WordPredictor.dictionary.get().keys.take(5).joinToString(", ")
                Log.i(TAG, "Async dictionary load complete for '$language': ${this@WordPredictor.dictionary.get().size} words, " +
                    "${this@WordPredictor.prefixIndex.get().size} prefixes (sample: $sampleWords)")
                MemoryProbe.mark("primary.dictionary", settle = true) {
                    val idx = this@WordPredictor.prefixIndex.get()
                    "lang=$language words=${this@WordPredictor.dictionary.get().size} " +
                        "prefixes=${idx.size} setEntries=${idx.values.sumOf { it.size }}"
                }

                callback?.run()
            }

            override fun onLoadFailed(lang: String, error: Exception) {
                isLoadingState = false
                Log.e(TAG, "Async dictionary load failed: $lang", error)

                // Fall back to synchronous loading
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Falling back to synchronous dictionary load")
                loadDictionary(context, lang)

                callback?.run()
            }
        })
    }

    /**
     * Check if dictionary is currently loading.
     *
     * @return true if dictionary is loading asynchronously
     */
    override fun isLoading(): Boolean {
        return isLoadingState
    }

    /**
     * Check if dictionary is ready for predictions.
     *
     * @return true if dictionary is loaded and ready
     */
    fun isReady(): Boolean {
        return !isLoadingState && dictionary.get().isNotEmpty()
    }

    // ==================== v1.1.93: SECONDARY DICTIONARY SUPPORT ====================

    /**
     * Load a secondary language dictionary for bilingual touch typing.
     *
     * Uses NormalizedPrefixIndex (V2 format) for accent-aware lookups.
     * Secondary dictionary words will be included in touch typing predictions.
     *
     * @param language Language code (e.g., "es", "fr", "de")
     * @return true if loaded successfully
     */
    override fun loadSecondaryDictionary(language: String): Boolean {
        if (language == "none" || language.isEmpty()) {
            unloadSecondaryDictionary()
            return true
        }

        val ctx = context ?: return false

        try {
            Log.i(TAG, "Loading secondary dictionary for touch typing: $language")

            // Try language pack first, then bundled assets
            val packManager = LanguagePackManager.getInstance(ctx)
            val packPath: java.io.File? = packManager.getDictionaryPath(language)

            val index = NormalizedPrefixIndex()
            val loaded = if (packPath != null) {
                BinaryDictionaryLoader.loadIntoNormalizedIndexFromFile(packPath, index)
            } else {
                val filename = "dictionaries/${language}_enhanced.bin"
                BinaryDictionaryLoader.loadIntoNormalizedIndex(ctx, filename, index)
            }

            if (loaded && index.size() > 0) {
                MemoryProbe.mark("secondary.binaryIndex", settle = true) {
                    "lang=$language words=${index.size()} normalized=${index.normalizedCount()}"
                }

                // v1.1.94: Also load custom words for secondary language
                val customWordsAdded = loadSecondaryCustomWords(ctx, index, language)

                // v1.2.6: Also add contraction keys (apostrophe-free forms) for secondary language
                // This allows typing "dont" to find "don't" in secondary English dictionary
                val contractionsAdded = loadSecondaryContractionKeys(ctx, index, language)
                MemoryProbe.mark("secondary.contractionKeys", settle = true) {
                    "lang=$language added=$contractionsAdded custom=$customWordsAdded"
                }

                secondaryIndex = index
                secondaryLanguageCode = language
                Log.i(TAG, "Secondary dictionary loaded: $language (${index.size()} words, +$customWordsAdded custom, +$contractionsAdded contractions)")
                return true
            } else {
                Log.w(TAG, "Failed to load secondary dictionary: $language")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading secondary dictionary: $language", e)
            return false
        }
    }

    /**
     * Unload the secondary dictionary to free memory.
     */
    override fun unloadSecondaryDictionary() {
        secondaryIndex = null
        secondaryLanguageCode = "none"
        Log.i(TAG, "Unloaded secondary dictionary for touch typing")
    }

    /**
     * v1.1.94: Load custom words for secondary language into NormalizedPrefixIndex.
     *
     * @param context Android context
     * @param index The NormalizedPrefixIndex to add words to
     * @param language Language code for custom words key
     * @return Number of custom words added
     */
    private fun loadSecondaryCustomWords(context: Context, index: NormalizedPrefixIndex, language: String): Int {
        var count = 0
        try {
            val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
            val customWordsKey = LanguagePreferenceKeys.customWordsKey(language)
            val customWordsJson = prefs.getString(customWordsKey, "{}") ?: "{}"

            if (customWordsJson != "{}") {
                val jsonObj = JSONObject(customWordsJson)
                val keys = jsonObj.keys()

                while (keys.hasNext()) {
                    val word = keys.next()
                    val frequency = jsonObj.optInt(word, 1000)
                    // Convert frequency to rank (0-255): higher frequency = lower rank
                    val rank = max(0, min(255, 255 - (frequency / 4000)))
                    index.addWord(word, rank)
                    count++
                }

                if (count > 0) {
                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Added $count custom words to secondary index for '$language'")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load secondary custom words for '$language'", e)
        }
        return count
    }

    /**
     * v1.2.7: Load contraction keys into provided maps (for async loading path).
     * This variant accepts maps as parameters instead of using instance fields.
     *
     * @param context Android context
     * @param targetDict Dictionary map to add contraction aliases to
     * @param targetPrefixIndex Prefix index to add lookup keys to
     * @param language Language code
     * @return Number of contraction keys added
     */
    private fun loadContractionKeysIntoMaps(
        context: Context,
        targetDict: MutableMap<String, Int>,
        targetPrefixIndex: MutableMap<String, MutableSet<String>>,
        language: String
    ): Int {
        var count = 0
        try {
            val packManager = LanguagePackManager.getInstance(context)
            val packFile = packManager.getContractionsPath(language)

            val inputStream = if (packFile != null) {
                packFile.inputStream()
            } else {
                try {
                    context.assets.open("dictionaries/contractions_$language.json")
                } catch (e: Exception) {
                    return 0
                }
            }

            run {
                val aliases = mutableMapOf<String, String>()

                // Streaming parse — see [ContractionJsonReader]. The restored fr/it files hold
                // ~18k/~21k mappings, so the old whole-file-into-String parse was a multi-MB
                // transient spike on every dictionary load.
                ContractionJsonReader.forEachEntry(inputStream) { withoutApostrophe, withApostrophe ->
                    // Skip real English words that are also contraction bases
                    if (withoutApostrophe in REAL_WORD_CONTRACTION_BASES) return@forEachEntry

                    // Base form is NOT a real word → create alias and add to dictionary.
                    // Preserve existing freq (same fix as `loadPrimaryContractionKeys`
                    // — see that function for rationale). The async loader path
                    // would otherwise silently downgrade bare-form contractions.
                    aliases[withoutApostrophe] = withApostrophe
                    targetDict[withoutApostrophe] = targetDict[withApostrophe]
                        ?: targetDict[withoutApostrophe]
                        ?: 5000

                    val maxLen = min(PREFIX_INDEX_MAX_LENGTH, withoutApostrophe.length)
                    for (len in 1..maxLen) {
                        val prefix = withoutApostrophe.substring(0, len)
                        targetPrefixIndex.getOrPut(prefix) { HashSet() }.add(withoutApostrophe)
                    }
                    count++
                }

                contractionAliases = aliases
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load contraction keys for '$language' (async)", e)
        }
        return count
    }

    /**
     * v1.2.7: Load contraction keys (apostrophe-free forms) into PRIMARY dictionary's prefix index.
     * This allows typing "dont" or "cant" to find "don't" or "can't" in typing predictions.
     *
     * The dictionary stores words with apostrophes ("can't"), but the prefix index is keyed
     * by actual prefixes. When user types "cant", prefix lookup can't find "can't" because
     * "cant" is not a prefix of "can'" (the apostrophe breaks the match).
     *
     * This method adds the apostrophe-free form as an alias in the prefix index.
     *
     * @param context Android context
     * @param language Language code
     * @return Number of contraction keys added
     */
    private fun loadPrimaryContractionKeys(context: Context, language: String): Int {
        var count = 0
        try {
            // Try language pack first
            val packManager = LanguagePackManager.getInstance(context)
            val packFile = packManager.getContractionsPath(language)

            val inputStream = if (packFile != null) {
                packFile.inputStream()
            } else {
                // Try bundled assets
                try {
                    context.assets.open("dictionaries/contractions_$language.json")
                } catch (e: Exception) {
                    // No contractions file for this language - that's OK
                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "No contractions file for primary language '$language'")
                    return 0
                }
            }

            run {
                val currentDict = dictionary.get()
                val currentPrefixIndex = prefixIndex.get()
                val aliases = mutableMapOf<String, String>()

                // Streaming parse — see [ContractionJsonReader].
                ContractionJsonReader.forEachEntry(inputStream) { withoutApostrophe, withApostrophe ->
                    // Skip real English words that also happen to be contraction bases
                    // (e.g., "well" should stay "well", not autocorrect to "we'll")
                    if (withoutApostrophe in REAL_WORD_CONTRACTION_BASES) return@forEachEntry

                    // Base form is NOT a real word (e.g., "dont", "im", "thats", "hes")
                    // → create autocorrect alias and add to dictionary for predictions.
                    //
                    // Preserve any pre-existing frequency for the bare form (loaded
                    // from the binary/JSON dict) rather than overwriting. The
                    // previous `?: 5000` form was destructive: a bare form like
                    // `hadnt` loaded from binary at ≈ 789K freq would be SILENTLY
                    // DOWNGRADED to 5000 (since `currentDict[withApostrophe]` is
                    // null — the apostrophe form is never in the dict). The fall-
                    // through order is now: apostrophe-form freq if present →
                    // existing bare-form freq if present → 5000 anchor. This
                    // preserves both the binary-loaded ranking signal and the
                    // beam-search ranking (OptimizedVocabulary normalizes to 0-1
                    // and multiplied it by a frequency weight, so
                    // higher input freq → slightly better ranking).
                    aliases[withoutApostrophe] = withApostrophe
                    currentDict[withoutApostrophe] = currentDict[withApostrophe]
                        ?: currentDict[withoutApostrophe]
                        ?: 5000

                    // Add to prefix index so typing "don" finds "dont" → "don't"
                    val maxLen = min(PREFIX_INDEX_MAX_LENGTH, withoutApostrophe.length)
                    for (len in 1..maxLen) {
                        val prefix = withoutApostrophe.substring(0, len)
                        currentPrefixIndex.getOrPut(prefix) { HashSet() }.add(withoutApostrophe)
                    }

                    count++
                }

                contractionAliases = aliases
            }

            if (count > 0) {
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Added $count contraction keys to primary prefix index for '$language'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load primary contraction keys for '$language'", e)
        }
        return count
    }

    /**
     * v1.2.6: Load contraction keys (apostrophe-free forms) into secondary dictionary.
     * This allows typing "dont" to find "don't" in secondary English dictionary.
     *
     * The NormalizedPrefixIndex stores words with apostrophes intact, so prefix search
     * for "dont" won't find "don't". By adding the apostrophe-free form as an alias,
     * both searches will work.
     *
     * @param context Android context
     * @param index The NormalizedPrefixIndex to add keys to
     * @param language Language code
     * @return Number of contraction keys added
     */
    private fun loadSecondaryContractionKeys(
        context: Context,
        index: NormalizedPrefixIndex,
        language: String
    ): Int {
        var count = 0
        try {
            // Try language pack first
            val packManager = LanguagePackManager.getInstance(context)
            val packFile = packManager.getContractionsPath(language)

            val inputStream = if (packFile != null) {
                packFile.inputStream()
            } else {
                // Try bundled assets
                try {
                    context.assets.open("dictionaries/contractions_$language.json")
                } catch (e: Exception) {
                    // No contractions file for this language - that's OK
                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "No contractions file for secondary language '$language'")
                    return 0
                }
            }

            // Keys only: the display form is not needed here (the index stores the
            // apostrophe-free surface), and streaming avoids materializing the whole file —
            // this is the exact call that ran out of heap on a 256 MB device.
            count = ContractionJsonReader.forEachKey(inputStream) { withoutApostrophe ->
                // Add the apostrophe-free form as an alias so prefix search can reach the
                // contraction. Two rules, both load-bearing since the 2026-08-17 restore took
                // the French/Italian files from ~100 curated aliases to 18k/21k:
                //
                //  1. SKIP a key the index already holds. Every English alias (`dont`,
                //     `cant`, …) is itself a dictionary entry, so this preserves its real
                //     frequency instead of overwriting it — and avoids a duplicate canonical.
                //  2. Add a NEW key at the RANK FLOOR, never as a common word. These are
                //     mostly productive elisions (`allabbaiare`, `dabaissement`); at the old
                //     hard-coded rank 50 all 21k of them scored ≈821k — above nearly every
                //     real word — and would have buried genuine Italian suggestions under
                //     pseudo-words. Findable, never preferred: the same call the swipe side
                //     makes in [CtcContractionKeys].
                val normalized = AccentNormalizer.normalize(withoutApostrophe)
                if (normalized.isNotEmpty() && !index.contains(normalized)) {
                    index.addWord(withoutApostrophe, CONTRACTION_ALIAS_RANK)
                }
            }

            if (count > 0) {
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Added $count contraction keys to secondary index for '$language'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load secondary contraction keys for '$language'", e)
        }
        return count
    }

    /**
     * Check if a secondary dictionary is loaded.
     */
    fun hasSecondaryDictionary(): Boolean {
        return secondaryIndex != null
    }

    /**
     * Get the secondary language code.
     */
    fun getSecondaryLanguageCode(): String {
        return secondaryLanguageCode
    }

    /**
     * Build prefix index for fast word lookup during predictions
     * Creates mapping from prefixes (1-3 chars) to sets of matching words
     * Performance: Reduces 50k iterations per keystroke to ~100-500
     */
    private fun buildPrefixIndex() {
        prefixIndex.get().clear()

        for (word in dictionary.get().keys) {
            // Index prefixes of length 1 to PREFIX_INDEX_MAX_LENGTH (3)
            val maxLen = min(PREFIX_INDEX_MAX_LENGTH, word.length)
            for (len in 1..maxLen) {
                val prefix = word.substring(0, len)
                prefixIndex.get().getOrPut(prefix) { HashSet() }.add(word)
            }
        }
    }

    /**
     * Add words to prefix index (for incremental updates)
     */
    private fun addToPrefixIndex(words: Set<String>) {
        for (word in words) {
            val maxLen = min(PREFIX_INDEX_MAX_LENGTH, word.length)
            for (len in 1..maxLen) {
                val prefix = word.substring(0, len)
                prefixIndex.get().getOrPut(prefix) { HashSet() }.add(word)
            }
        }
    }

    /**
     * Remove words from prefix index (for incremental updates)
     * OPTIMIZATION: Allows removing custom/user words without full rebuild
     */
    private fun removeFromPrefixIndex(words: Set<String>) {
        for (word in words) {
            val maxLen = min(PREFIX_INDEX_MAX_LENGTH, word.length)
            for (len in 1..maxLen) {
                val prefix = word.substring(0, len)
                val prefixWords = prefixIndex.get()[prefix]
                prefixWords?.let {
                    it.remove(word)
                    // Clean up empty prefix sets to save memory
                    if (it.isEmpty()) {
                        prefixIndex.get().remove(prefix)
                    }
                }
            }
        }
    }

    /**
     * Load custom and user words into a specific map instance.
     * Used during async loading to populate new map before atomic swap.
     *
     * OPTIMIZATION v4 (perftodos4.md): Allows loading into new map off main thread,
     * then swapping the entire map atomically instead of putAll() on main thread.
     *
     * v1.1.90: Added language parameter to filter UserDictionary by locale.
     * This prevents English words from appearing in French touch typing predictions.
     *
     * @param context Android context for accessing SharedPreferences and ContentProvider
     * @param targetMap The map to load words into (not dictionary)
     * @param language Language code to filter UserDictionary (e.g., "fr", "de")
     * @return Set of all words loaded (for incremental prefix index updates)
     */
    private fun loadCustomAndUserWordsIntoMap(context: Context, targetMap: MutableMap<String, Int>, language: String = "en"): Set<String> {
        val loadedWords = mutableSetOf<String>()

        try {
            val prefs = DirectBootAwarePreferences.get_shared_preferences(context)

            // 1. Load custom words from SharedPreferences
            // v1.1.92: Use language-specific key (custom_words_${lang}) instead of legacy global key
            val customWordsKey = LanguagePreferenceKeys.customWordsKey(language)
            val customWordsJson = prefs.getString(customWordsKey, "{}") ?: "{}"
            if (customWordsJson != "{}") {
                try {
                    // Parse JSON map: {"word": frequency, ...}
                    val jsonObj = JSONObject(customWordsJson)
                    val keys = jsonObj.keys()
                    var customCount = 0
                    while (keys.hasNext()) {
                        val originalWord = keys.next()
                        val lowerWord = originalWord.lowercase()
                        val frequency = jsonObj.optInt(originalWord, 1000)
                        targetMap[lowerWord] = frequency  // Write to target map, not dictionary
                        loadedWords.add(lowerWord)
                        // v1.2.7: Preserve original case for proper nouns (Issue #72)
                        if (originalWord != lowerWord) {
                            userWordOriginalCase[lowerWord] = originalWord
                        }
                        customCount++
                    }
                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                        Log.d(TAG, "Loaded $customCount custom words for '$language' into new map")
                    }
                } catch (e: JSONException) {
                    Log.e(TAG, "Failed to parse custom words JSON", e)
                }
            }

            // 2. Load Android user dictionary
            // The locale-filtered read lives in UserDictionaryWords (ARC-081) — the same rows
            // the swipe adapters now merge into their lexicons, so tap and swipe can never
            // disagree about which personal words exist. Its KDoc owns the filter rationale.
            val userRows = UserDictionaryWords.read(context, language)
            for ((originalWord, frequency) in userRows) {
                val lowerWord = originalWord.lowercase()
                targetMap[lowerWord] = frequency  // Write to target map, not dictionary
                loadedWords.add(lowerWord)
                // v1.2.7: Preserve original case for proper nouns (Issue #72)
                if (originalWord != lowerWord) {
                    userWordOriginalCase[lowerWord] = originalWord
                }
            }
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                Log.d(TAG, "Loaded ${userRows.size} user dictionary words for locale '$language' into new map")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading custom/user words into new map", e)
        }

        return loadedWords
    }

    /**
     * Add words to a specific prefix index map.
     * Used during async loading to populate new index before atomic swap.
     *
     * OPTIMIZATION v4 (perftodos4.md): Allows building prefix index off main thread,
     * then swapping the entire index atomically.
     *
     * @param words Words to add to prefix index
     * @param targetIndex The prefix index to add to (not prefixIndex)
     */
    private fun addToPrefixIndexForMap(words: Set<String>, targetIndex: MutableMap<String, MutableSet<String>>) {
        for (word in words) {
            val maxLen = min(PREFIX_INDEX_MAX_LENGTH, word.length)
            for (len in 1..maxLen) {
                val prefix = word.substring(0, len)
                targetIndex.getOrPut(prefix) { HashSet() }.add(word)
            }
        }
    }

    /**
     * Load custom words and Android user dictionary into predictions
     * Called during dictionary initialization for performance
     *
     * OPTIMIZATION v2: Returns the set of loaded words for incremental prefix index updates
     *
     * v1.1.90: Added language parameter to filter UserDictionary by locale.
     * This prevents English words from appearing in French touch typing predictions.
     *
     * @param context Android context for accessing preferences and content providers
     * @param language Language code to filter UserDictionary (e.g., "fr", "de")
     * @return Set of words that were added to the dictionary
     */
    private fun loadCustomAndUserWords(context: Context, language: String = "en"): Set<String> {
        val loadedWords = mutableSetOf<String>()

        try {
            val prefs = DirectBootAwarePreferences.get_shared_preferences(context)

            // 1. Load custom words from SharedPreferences
            // v1.1.92: Use language-specific key (custom_words_${lang}) instead of legacy global key
            val customWordsKey = LanguagePreferenceKeys.customWordsKey(language)
            val customWordsJson = prefs.getString(customWordsKey, "{}") ?: "{}"
            if (customWordsJson != "{}") {
                try {
                    // Parse JSON map: {"word": frequency, ...}
                    val jsonObj = JSONObject(customWordsJson)
                    val keys = jsonObj.keys()
                    var customCount = 0
                    while (keys.hasNext()) {
                        val originalWord = keys.next()
                        val lowerWord = originalWord.lowercase()
                        val frequency = jsonObj.optInt(originalWord, 1000)
                        dictionary.get()[lowerWord] = frequency
                        loadedWords.add(lowerWord)  // Track loaded word
                        // Issue #72: Preserve original case for proper nouns
                        // Only store if word has uppercase (potential proper noun)
                        if (originalWord != lowerWord) {
                            userWordOriginalCase[lowerWord] = originalWord
                        }
                        customCount++
                    }
                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                        Log.d(TAG, "Loaded $customCount custom words for '$language'")
                    }
                } catch (e: JSONException) {
                    Log.e(TAG, "Failed to parse custom words JSON", e)
                }
            }

            // 2. Load Android user dictionary
            // The locale-filtered read lives in UserDictionaryWords (ARC-081) — the same rows
            // the swipe adapters now merge into their lexicons, so tap and swipe can never
            // disagree about which personal words exist. Its KDoc owns the filter rationale.
            val userRows = UserDictionaryWords.read(context, language)
            for ((originalWord, frequency) in userRows) {
                val lowerWord = originalWord.lowercase()
                dictionary.get()[lowerWord] = frequency
                loadedWords.add(lowerWord)  // Track loaded word
                // v1.2.7: Preserve original case for proper nouns (Issue #72)
                if (originalWord != lowerWord) {
                    userWordOriginalCase[lowerWord] = originalWord
                }
            }
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                Log.d(TAG, "Loaded ${userRows.size} user dictionary words for locale '$language'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading custom/user words", e)
        }

        return loadedWords
    }

    /**
     * Reset the predictor state - called after space/punctuation
     */
    override fun reset() {
        // This method will be called from CleverKeysService to reset state
        // Dictionary remains loaded, just clears any internal state if needed
        if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
            Log.d(TAG, "===== PREDICTOR RESET CALLED =====")
            Log.d(TAG, "Stack trace: ", Exception("Reset trace"))
        }
    }

    /**
     * Get candidate words from prefix index
     * Returns all words starting with the given prefix
     * Performance: O(1) lookup instead of O(n) iteration
     */
    private fun getPrefixCandidates(prefix: String): Set<String> {
        if (prefix.isEmpty()) {
            // For empty prefix, return all words (fallback to full dictionary)
            return dictionary.get().keys
        }

        // Use prefix as-is if <= 3 chars, otherwise use first 3 chars
        val lookupPrefix = if (prefix.length <= PREFIX_INDEX_MAX_LENGTH) {
            prefix
        } else {
            prefix.substring(0, PREFIX_INDEX_MAX_LENGTH)
        }

        val candidates = prefixIndex.get()[lookupPrefix] ?: return emptySet()

        // If typed prefix is longer than indexed prefix, filter further
        if (prefix.length > PREFIX_INDEX_MAX_LENGTH) {
            return candidates.filter { it.startsWith(prefix) }.toSet()
        }

        return candidates
    }

    /**
     * Predict words based on the sequence of touched keys
     * Returns list of predictions (for backward compatibility)
     */
    fun predictWords(keySequence: String): List<String> {
        val result = predictWordsWithScores(keySequence)
        return result.words
    }

    /**
     * Predict words with context (PUBLIC API - delegates to internal unified method)
     */
    override fun predictWordsWithContext(keySequence: String, context: List<String>): PredictionResult {
        return predictInternal(keySequence, context)
    }

    /**
     * Predict words and return with their scores (no context)
     */
    fun predictWordsWithScores(keySequence: String): PredictionResult {
        return predictInternal(keySequence, emptyList())
    }

    /**
     * UNIFIED prediction logic with early fusion of all signals
     * Context is applied to ALL candidates BEFORE selecting top N
     */
    // The beginSection("WordPredictor.predictInternal") below IS balanced: it is immediately
    // followed by a try { ... } finally { Trace.endSection() } (see the finally near the end of
    // this method), so the section always closes on every path. Lint's early-return heuristic
    // can't see the pairing across the try/finally.
    @SuppressLint("UnclosedTrace")
    private fun predictInternal(keySequence: String, context: List<String>): PredictionResult {
        if (keySequence.isEmpty()) {
            return PredictionResult(emptyList(), emptyList())
        }

        // Check if dictionary changes require reload
        checkAndReload()

        // OPTIMIZATION v3 (perftodos3.md): Use android.os.Trace for system-level profiling
        android.os.Trace.beginSection("WordPredictor.predictInternal")
        try {
            // UNIFIED SCORING with EARLY FUSION
            // Context is applied to ALL candidates BEFORE selecting top N
            val candidates = mutableListOf<WordCandidate>()
            val lowerSequence = keySequence.lowercase()

            // OPTIMIZATION: Verbose logging disabled in release builds for performance
            // v1.2.0: Always log prediction language for debugging language toggle issues
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Predicting for: '$lowerSequence' (lang=$currentLanguage, dictSize=${dictionary.get().size})")
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                Log.d(TAG, "Predicting for: $lowerSequence (len=${lowerSequence.length}) with context: $context")
            }

            val maxPredictions = MAX_PREDICTIONS_TYPING

            // Find all words that could match the typed prefix using prefix index
            // PERFORMANCE: Prefix index reduces 50k iterations to ~100-500 (100x speedup)
            // Get candidate words from prefix index (only words starting with typed prefix)
            val candidateWords = getPrefixCandidates(lowerSequence)

            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                Log.d(TAG, "Prefix index lookup: ${candidateWords.size} candidates for prefix '$lowerSequence'")
            }

            for (word in candidateWords) {
                // SKIP DISABLED WORDS - Filter out words disabled via Dictionary Manager
                if (isWordDisabled(word)) {
                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                        Log.d(TAG, "Skipping disabled word: $word")
                    }
                    continue
                }

                // Get frequency for scoring
                val frequency = dictionary.get()[word] ?: continue // Should not happen, but safe guard

                // UNIFIED SCORING: Combine ALL signals into one score BEFORE selection
                val score = calculateUnifiedScore(word, lowerSequence, frequency, context)

                if (score > 0) {
                    candidates.add(WordCandidate(word, score))
                }
            }

            // v1.1.93: SECONDARY DICTIONARY LOOKUP for bilingual touch typing
            val secIndex = secondaryIndex
            if (secIndex != null) {
                val primaryWords = candidates.map { it.word.lowercase() }.toSet()
                val secondaryResults = secIndex.getWordsWithPrefix(lowerSequence)

                for (result in secondaryResults) {
                    // Skip if already in primary dictionary
                    if (result.normalized in primaryWords || result.bestCanonical.lowercase() in primaryWords) {
                        continue
                    }

                    // Skip disabled words
                    if (isWordDisabled(result.bestCanonical)) {
                        continue
                    }

                    // Convert frequency rank (0-255) to frequency score
                    // Rank 0 = most common → high frequency; Rank 255 = rare → low frequency
                    val frequency = ((255 - result.bestFrequencyRank) * 4000) + 1000

                    // Calculate score with secondary penalty (configurable, default 0.9x)
                    val baseScore = calculateUnifiedScore(result.bestCanonical, lowerSequence, frequency, context)
                    val secondaryWeight = config?.secondary_prediction_weight ?: Defaults.SECONDARY_PREDICTION_WEIGHT
                    val score = (baseScore * secondaryWeight).toInt()

                    if (score > 0) {
                        candidates.add(WordCandidate(result.bestCanonical, score))
                    }
                }

                if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                    Log.d(TAG, "Secondary dictionary: ${secondaryResults.size} matches for '$lowerSequence' (lang=$secondaryLanguageCode)")
                }
            }

            // Sort all candidates by score (descending)
            candidates.sortByDescending { it.score }

            // Extract top N predictions
            val predictions = mutableListOf<String>()
            val scores = mutableListOf<Int>()

            for (candidate in candidates) {
                predictions.add(candidate.word)
                scores.add(candidate.score)
                if (predictions.size >= maxPredictions) break
            }

            // Issue #72: Apply proper noun case from user dictionary
            val casedPredictions = applyUserWordCaseToList(predictions)

            // Task B transparency: provenance metas for the FINAL top-N only
            // (N ≤ MAX_PREDICTIONS_TYPING — a handful of cheap map lookups per
            // keystroke, NOT the per-candidate hot loop). Breakdown re-resolves
            // through the same UnifiedScore path that produced the score.
            val metas = predictions.map { predicted ->
                val lower = predicted.lowercase()
                SuggestionMeta(
                    origin = SuggestionOrigin.DICTIONARY_PREFIX,
                    breakdown = dictionary.get()[lower]?.let { freq ->
                        resolveScoreBreakdown(lower, lowerSequence, freq, context)
                    }
                )
            }

            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                Log.d(TAG, "Final predictions (${casedPredictions.size}): $casedPredictions")
                Log.d(TAG, "Scores: $scores")
            }

            return PredictionResult(casedPredictions, scores, metas)
        } finally {
            android.os.Trace.endSection()
        }
    }

    /**
     * UNIFIED SCORING - Combines all prediction signals (early fusion)
     *
     * Combines: prefix quality + frequency + user adaptation + context probability + personalization
     * Context is evaluated for ALL candidates, not just top N (key improvement)
     *
     * Phase 7.1: Includes dynamic N-gram boost from ContextModel alongside static BigramModel
     * Phase 7.2: Includes personalization boost from user's typing frequency and recency
     *
     * @param word The word being scored
     * @param keySequence The typed prefix
     * @param frequency Dictionary frequency (higher = more common)
     * @param context Previous words for contextual prediction (can be empty)
     * @return Combined score
     */
    private fun calculateUnifiedScore(word: String, keySequence: String, frequency: Int, context: List<String>): Int {
        return resolveScoreBreakdown(word, keySequence, frequency, context)?.finalScore ?: 0
    }

    /**
     * Resolve every scoring signal for one candidate and combine them via the
     * pure [UnifiedScore] combiner — the SINGLE implementation shared by the
     * hot-path [calculateUnifiedScore], the top-N provenance metas in
     * [predictInternal], and the long-press [explainScore] sheet (Task B), so
     * the displayed breakdown can never drift from the real score.
     *
     * Signals resolved here:
     * 1. prefix-match quality; 2. selection-adaptation multiplier;
     * 3a. static BigramModel context multiplier; 3b. learned ContextModel boost
     * (per-feature toggle AND — Task A — the master on-device-learning gate:
     * with the master off, learned data is not read at all);
     * 3d. raw personalization boost (engine returns 0 when disabled, incl. via
     * the master-gate sync in [setConfig]).
     * The static-vs-learned combination (context_source, audit §3.2-2), the
     * personalization weight (§3.2-1), the log frequency damping, and the final
     * formula all live in [UnifiedScore.combine].
     *
     * @return null when the word does not prefix-match the sequence (score 0)
     */
    private fun resolveScoreBreakdown(
        word: String,
        keySequence: String,
        frequency: Int,
        context: List<String>
    ): ScoreBreakdown? {
        val prefixScore = calculatePrefixScore(word, keySequence)
        if (prefixScore == 0) return null // Should not happen if caller does prefix check

        // H3: the selection-adaptation multiplier is a READ of learned data —
        // inert (1.0) when the master gate is off or config is absent.
        val adaptationMultiplier = if (canUseAdaptation()) {
            adaptationManager?.getAdaptationMultiplier(word) ?: 1.0f
        } else {
            1.0f
        }

        val staticContextMultiplier = if (bigramModel != null && context.isNotEmpty()) {
            bigramModel?.getContextMultiplier(word, context) ?: 1.0f
        } else {
            1.0f
        }

        // Null config fails CLOSED (M2).
        val canUseLearned = LearningGate.canUseLearnedContext(
            config?.on_device_learning_enabled ?: false,
            config?.context_aware_predictions_enabled ?: false
        )
        val dynamicContextBoost = if (canUseLearned && contextModel != null && context.isNotEmpty()) {
            contextModel?.getContextBoost(word, context) ?: 1.0f
        } else {
            1.0f
        }

        // Personalization boost read: master AND feature gate, fail closed (M2).
        val personalizationEnabled = LearningGate.canLearnPersonalization(
            config?.on_device_learning_enabled ?: false,
            config?.personalized_learning_enabled ?: false
        )
        val personalizationBoost = if (personalizationEnabled && personalizationEngine != null) {
            personalizationEngine?.getPersonalizationBoost(word) ?: 0.0f
        } else {
            0.0f
        }

        return UnifiedScore.combine(
            prefixScore = prefixScore,
            adaptationMultiplier = adaptationMultiplier,
            staticContextMultiplier = staticContextMultiplier,
            dynamicContextBoost = dynamicContextBoost,
            contextSource = config?.context_source ?: Defaults.CONTEXT_SOURCE,
            personalizationBoost = personalizationBoost,
            personalizationWeight = config?.personalization_weight ?: Defaults.PERSONALIZATION_WEIGHT,
            frequency = frequency,
            frequencyScale = config?.prediction_frequency_scale ?: Defaults.PREDICTION_FREQUENCY_SCALE,
            contextBoost = config?.prediction_context_boost ?: Defaults.PREDICTION_CONTEXT_BOOST
        )
    }

    /**
     * Transparency API (Task B): full per-signal breakdown for one candidate,
     * recomputed on demand (long-press provenance sheet). Returns null when the
     * word is not in the primary dictionary or does not match the sequence.
     */
    override fun explainScore(word: String, keySequence: String, context: List<String>): ScoreBreakdown? {
        val lower = word.lowercase()
        val frequency = dictionary.get()[lower] ?: return null
        return resolveScoreBreakdown(lower, keySequence.lowercase(), frequency, context)
    }

    /**
     * Transparency API (Task B): wires the previously dead
     * `PersonalizationEngine.explainBoost()` into the provenance sheet.
     */
    override fun explainPersonalization(word: String): tribixbite.cleverkeys.personalization.BoostExplanation? {
        return personalizationEngine?.explainBoost(word.lowercase())
    }

    /**
     * Calculate base score for prefix-based matching (used by unified scoring)
     */
    private fun calculatePrefixScore(word: String, keySequence: String): Int {
        // Direct match is highest score
        if (word == keySequence) return 1000

        // Word starts with sequence (this is guaranteed by caller, but score based on completion ratio)
        if (word.startsWith(keySequence)) {
            // Higher score for more completion, but prefer shorter completions
            val baseScore = 800

            // Bonus for more typed characters (longer prefix = more specific)
            val prefixBonus = keySequence.length * 50

            // Slight penalty for very long words to prefer common shorter words
            val lengthPenalty = max(0, (word.length - 6) * 10)

            return baseScore + prefixBonus - lengthPenalty
        }

        return 0 // Should not reach here due to prefix check in caller
    }

    /**
     * Auto-correct a typed word after user presses space/punctuation.
     *
     * Finds dictionary words with:
     * - Same length
     * - Same first 2 letters
     * - High positional character match (default: 2/3 chars)
     *
     * Example: "teh" → "the", "Teh" → "The", "TEH" → "THE"
     *
     * @param typedWord The word user just finished typing
     * @return Corrected word, or original if no suitable correction found
     */
    /**
     * True iff [a] and [b] differ ONLY by one swap of two adjacent (distinct)
     * characters — the Damerau transposition typo ("teh"/"the", "becuase"/
     * "because"). Same-length inputs only; O(n), no allocation.
     */
    private fun isAdjacentTransposition(a: String, b: String): Boolean {
        if (a.length != b.length || a.length < 2) return false
        var i = 0
        while (i < a.length && a[i] == b[i]) i++
        if (i >= a.length - 1) return false           // identical or diff at last char only
        if (a[i] != b[i + 1] || a[i + 1] != b[i] || a[i] == a[i + 1]) return false
        for (j in i + 2 until a.length) if (a[j] != b[j]) return false
        return true
    }

    /**
     * Max frequency in the current dictionary, cached and recomputed only when
     * the dictionary size changes. Used by [FrequencyFloor] to scale the
     * autocorrect confidence floor to whatever frequency scale the dictionary
     * was loaded on. O(n) on a size change, O(1) otherwise.
     */
    private fun dictMaxFrequency(dict: Map<String, Int>): Int {
        if (dict.size != cachedMaxFreqForSize) {
            cachedMaxFreq = dict.values.maxOrNull() ?: 0
            cachedMaxFreqForSize = dict.size
        }
        return cachedMaxFreq
    }

    override fun autoCorrect(typedWord: String): String {
        if (config?.autocorrect_enabled != true || typedWord.isEmpty()) {
            return typedWord
        }

        // v1.1.89: Dictionary now loads primary language, so autocorrect uses correct vocabulary
        // The dictionary variable contains words from config.primary_language (loaded in PredictionCoordinator)
        // No need to skip autocorrect for non-English - it will match against the loaded dictionary

        val lowerTypedWord = typedWord.lowercase()

        // 0. Check for contraction aliases FIRST (e.g., "im" → "I'm", "dont" → "don't")
        // These are in the dictionary for prediction purposes but should still be autocorrected
        val contractionTarget = contractionAliases[lowerTypedWord]
        if (contractionTarget != null) {
            // Capitalize I-contractions (im → I'm, ill → I'll, id → I'd)
            val corrected = if (contractionTarget.startsWith("i'")) {
                contractionTarget.replaceFirstChar { it.uppercase() }
            } else {
                preserveCapitalization(typedWord, contractionTarget)
            }
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "AUTO-CORRECT (contraction): '$typedWord' → '$corrected'")
            return corrected
        }

        // 1. Do not correct words already in dictionary or user's vocabulary.
        // A word the user DISABLED in Dictionary Manager is no longer valid
        // vocabulary, so it must NOT short-circuit here — typed "ans" with
        // "ans" disabled falls through to the sweep and corrects like any
        // typo (UT-8; "ans" IS a bundled dictionary word). The explicit
        // disable also outranks the implicit personalization multiplier;
        // custom/user words still override the disable inside isWordDisabled.
        // The `isNotEmpty` guard keeps the common no-disabled-words path free
        // of the extra lowercase-set lookup (same pattern as the sweep).
        val typedWordDisabled = disabledWords.isNotEmpty() && isWordDisabled(lowerTypedWord)
        if (!typedWordDisabled &&
            (dictionary.get().containsKey(lowerTypedWord) ||
                (canUseAdaptation() &&
                    (adaptationManager?.getAdaptationMultiplier(lowerTypedWord) ?: 0f) > 1.0f))
        ) {
            return typedWord
        }

        val dict = dictionary.get()

        // 1.4. Doubled-letter elongation collapse ("gamees" → "games",
        // "embeer" → "ember"). Key auto-repeat / a lingering finger doubles a
        // letter; when removing one half of a doubled pair yields a dictionary
        // word, that word is a structurally near-certain intent — an exact
        // letter match at edit distance 1. It must run BEFORE the morphology
        // guard (which would misread "gamees" as a valid -es inflection of
        // "game" and freeze it) and before the sweep (whose adjacency scoring
        // prefers 1-substitution lookalikes: "gamees" would sweep to "gamers",
        // "embeer" to "embers"). Structural certainty exempts it from the
        // frequency floor — same rationale as the contraction path (step 0) —
        // but disabled words are still never offered. Gated on
        // autocorrect_max_length_diff >= 1: this IS a length-changing
        // correction, and a user who configured same-length-only corrections
        // keeps that guarantee. The collapsed word must still meet the
        // min-word-length bar (typed length must exceed it by the removed char).
        if (lowerTypedWord.length > (config?.autocorrect_min_word_length ?: 3) &&
            (config?.autocorrect_max_length_diff ?: 0) >= 1
        ) {
            var collapseBest: String? = null
            var collapseBestFreq = -1
            for (i in 0 until lowerTypedWord.length - 1) {
                if (lowerTypedWord[i] != lowerTypedWord[i + 1] ||
                    !lowerTypedWord[i].isLetter()
                ) continue
                val collapsed = lowerTypedWord.removeRange(i, i + 1)
                val freq = dict[collapsed] ?: continue
                if (disabledWords.isNotEmpty() && isWordDisabled(collapsed)) continue
                if (freq > collapseBestFreq) {
                    collapseBest = collapsed
                    collapseBestFreq = freq
                }
            }
            if (collapseBest != null) {
                // Re-route alias-keyed hits ("doont" → collapse "dont" →
                // "don't"), mirroring the sweep-winner re-route in step 5.
                val aliasTarget = contractionAliases[collapseBest]
                val outputWord = aliasTarget ?: collapseBest
                val corrected = if (aliasTarget != null && aliasTarget.startsWith("i'")) {
                    aliasTarget.replaceFirstChar { it.uppercase() }
                } else {
                    preserveCapitalization(typedWord, outputWord)
                }
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "AUTO-CORRECT (elongation collapse): '$typedWord' → '$corrected'")
                return corrected
            }
        }

        // 1.5. Morphological guard (#B1): do NOT autocorrect a word that is a
        // regular inflection of a dictionary word. The bundled dictionary has
        // incomplete inflection coverage (e.g. "immunization" is present but
        // the valid plural "immunizations" is not), and without this guard the
        // missing inflection gets "corrected" to a distant same-length word
        // that happens to be in the dictionary ("organizations"), which is
        // worse than leaving it alone. Require stem length >= 4 so short,
        // ambiguous words (e.g. "thes") remain correctable — long technical
        // plurals/inflections (immunization, vaccination, realization, ...)
        // are the real failure mode.
        // UT-8: an explicitly-disabled typed word outranks the inflection
        // heuristic — the user said this exact token is not a word, so don't
        // freeze it just because a plausible stem exists.
        if (!typedWordDisabled &&
            Morphology.inflectionStems(lowerTypedWord).any { it.length >= 4 && dict.containsKey(it) }
        ) {
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "AUTO-CORRECT skip (valid inflection): '$typedWord'")
            return typedWord
        }

        // 1.6. Possessive guard + possessive-typo correction (AC-4). A
        // possessive of a known noun ("ember's", "dog's", "rivers'") is valid
        // English, but the possessive form itself is never stored in the
        // dictionary — so without this guard autocorrect treats it as a typo
        // and "corrects" it to a same-ish dictionary word ("ember's" →
        // "rivers", "dog's" → "does"). If the base (the text before the last
        // apostrophe) is a known word, accept the possessive as-is.
        // Covers singular ('s) and plural/already-ends-in-s (trailing ') forms;
        // suffixes other than "s"/"" (e.g. "'t", "'ll") are left to the
        // contraction-alias path. Both straight (') and curly (’) apostrophes.
        //
        // When the base is NOT a known word it's a typo'd possessive
        // ("embeer's"). Running the normal pipeline on the full token compares
        // it against apostrophe-free dictionary words and strips the suffix
        // ("embeer's" → "rivers") — so instead correct the BASE alone via a
        // recursive call (which reuses every guard: contraction aliases, min
        // length, morphology, disabled words, frequency floor, capitalization)
        // and re-append the suffix with its ORIGINAL apostrophe character.
        // If the base can't be corrected, return the token untouched — never
        // strip the suffix. Recursion terminates: the base is strictly
        // shorter, and a base whose own last apostrophe is at index < 2
        // (e.g. "o'clock") doesn't re-enter this block.
        val apostropheIdx = lowerTypedWord.indexOfLast { it == '\'' || it == '’' }
        if (apostropheIdx >= 2) {
            val base = lowerTypedWord.substring(0, apostropheIdx)
            val suffix = lowerTypedWord.substring(apostropheIdx + 1)
            if (suffix == "s" || suffix.isEmpty()) {
                // UT-8: a DISABLED base is no longer a known word, so its
                // possessive must not be accepted as-is — fall through to the
                // AC-4 base-correction path (whose recursive autoCorrect call
                // applies the disabled check again). isWordDisabled keeps the
                // custom-word override.
                val baseDisabled = disabledWords.isNotEmpty() && isWordDisabled(base)
                if (!baseDisabled &&
                    (dict.containsKey(base) || customAndUserWords.contains(base))
                ) {
                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "AUTO-CORRECT skip (possessive of '$base'): '$typedWord'")
                    return typedWord
                }
                // AC-4: base is a typo — correct it alone, keep the suffix.
                val originalBase = typedWord.substring(0, apostropheIdx)
                val correctedBase = autoCorrect(originalBase)
                if (correctedBase != originalBase) {
                    val corrected = correctedBase + typedWord.substring(apostropheIdx)
                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "AUTO-CORRECT (possessive base): '$typedWord' → '$corrected'")
                    return corrected
                }
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "AUTO-CORRECT skip (uncorrectable possessive base): '$typedWord'")
                return typedWord
            }
        }

        // 2. Enforce minimum word length for correction
        if (lowerTypedWord.length < (config?.autocorrect_min_word_length ?: 3)) {
            return typedWord
        }

        // 3. Required-prefix rule. Configurable via `autocorrect_prefix_length`:
        //   - >0: candidate must share that many leading chars with typed word
        //   - 0:  no prefix required — typo on first char (e.g. "wuestion" →
        //         "question") is correctable. Skips the prefix filter entirely
        //         so the candidate sweep also covers off-by-one-on-first-char.
        // Clamped to typed-word length so a misconfigured huge prefix doesn't
        // short-circuit. Was previously hard-coded to 2, ignoring config.
        val configPrefixLength = (config?.autocorrect_prefix_length ?: 1).coerceAtLeast(0)
        val effectivePrefixLength = minOf(configPrefixLength, lowerTypedWord.length)
        val prefix: String? =
            if (effectivePrefixLength > 0) lowerTypedWord.substring(0, effectivePrefixLength)
            else null

        val wordLength = lowerTypedWord.length
        val charMatchThreshold = config?.autocorrect_char_match_threshold ?: 0.66f
        // The configured value is on the fixed 100..2000 slider scale; the
        // runtime dictionary frequency scale depends on the load path (~5k..1M
        // for the V2 binary, 100..10k for the JSON fallback). Map the slider
        // onto a fraction of THIS dictionary's max frequency so the floor is
        // meaningful and consistent regardless of load path, and can never
        // disable autocorrect outright. See FrequencyFloor.
        val configFloor = config?.autocorrect_confidence_min_frequency
            ?: Defaults.AUTOCORRECT_MIN_FREQUENCY
        val frequencyFloor = FrequencyFloor.effective(configFloor, dictMaxFrequency(dict))
        val maxLengthDiff = (config?.autocorrect_max_length_diff ?: 0).coerceAtLeast(0)

        // Track top-3 candidates for diagnostic logging on rejection.
        var bestCandidate: AutocorrectCandidate? = null
        val rejectionLog = mutableListOf<Pair<String, Float>>()  // (word, score) for top-N
        val diagnosticsEnabled = config?.swipe_debug_detailed_logging == true

        // 4. Iterate through dictionary to find candidates.
        //
        //    Same-length candidates are scored by KEYBOARD-ADJACENCY-WEIGHTED
        //    positional match: each position contributes `1.0` for a perfect
        //    match, `~0.86` for an adjacent-key substitution (e.g. `q↔w`),
        //    down to `0` for distant pairs. Sum / wordLength yields a
        //    score in [0, 1] comparable to the old positional-match ratio.
        //
        //    Different-length candidates (up to `autocorrect_max_length_diff`)
        //    are scored via KeyAdjacency.weightedEditDistance — substitution
        //    cost = keyDistance, insertion/deletion cost = 1.0. Score is
        //    `1 - editDistance / maxLength`.
        //
        //    Why two paths? Position-wise scoring is faster (linear in
        //    word length, no DP) and is the right model when the candidate
        //    aligns with the typed word index-by-index. Levenshtein handles
        //    the harder case where one is a one-off insertion or deletion.
        for ((dictWord, candidateFrequency) in dictionary.get()) {
            val lengthDiff = kotlin.math.abs(dictWord.length - wordLength)
            if (lengthDiff > maxLengthDiff) continue

            // Prefix match (when required by config).
            if (prefix != null && !dictWord.startsWith(prefix)) continue

            // Never offer a user-disabled word as a CORRECTION TARGET.
            // `autoCorrect` previously consulted `isWordDisabled` nowhere, so
            // disabling "boston" still let "bostom → boston", and bundled
            // corpus-noise the user disabled (teh, wich, hav, …) stayed
            // reachable as targets. The `isNotEmpty` guard skips the per-word
            // lowercase in `isWordDisabled` for the common no-disabled-words
            // case (the dictionary scan runs ~52k times per correction).
            if (disabledWords.isNotEmpty() && isWordDisabled(dictWord)) continue

            // Dual-gate scoring. Adjacency-weighted scores reward typos on
            // physically-near keys, but applied naively they let unrelated
            // 7-char words like "without" clear a 0.65 threshold against
            // typed "questin" (every char-pair has SOME adjacency similarity).
            //
            // Same-length path:
            //   - GATE 1: at least 50% of positions must exactly match.
            //     Rejects unrelated same-length words wholesale ("questin"
            //     vs "without" has 0 exact matches → fails).
            //   - GATE 2: weighted score must clear `charMatchThreshold`.
            //     Picks adjacency-rich matches ("tge" vs "the" passes both
            //     because 2/3 exact AND weighted ≈ 0.95).
            //
            // Length-diff path:
            //   - Position-by-position exact-counting is unreliable here
            //     (insertion/deletion shifts the alignment). Instead use
            //     an ABSOLUTE edit-distance budget: `ed ≤ lengthDiff + 2`.
            //     Allows the legitimate-typo case (lengthDiff=1, ed≈1.0
            //     for `questin → question`) while rejecting weakly-aligned
            //     unrelated candidates (ed≈3+ for `wuestion → wuthering`).
            var isTransposition = false
            var isMultiSub = false
            val score: Float = if (lengthDiff == 0) {
                if (isAdjacentTransposition(lowerTypedWord, dictWord)) {
                    // Damerau transposition fast path ("teh" → "the", "becuase"
                    // → "because", "recieve" → "receive"). A swap typo has only
                    // wordLength−2 exact positions, so for short words it can
                    // NEVER pass the 50% exact-ratio gate ("teh" vs "the" is
                    // 1/3 exact) even though it's among the most common typo
                    // classes. Score it as a mild, length-normalized penalty —
                    // just below a single adjacent-key substitution, so a
                    // genuine 1-sub candidate still outranks it on score and
                    // the within-gap frequency tiebreak resolves the rest
                    // ("teh": ten scores 0.959 vs the 0.950, gap < 0.10 →
                    // freq picks "the").
                    isTransposition = true
                    1f - TRANSPOSITION_PENALTY / wordLength
                } else {
                // Pass 1: cheap exact-match count only (`==`, no keyDistance).
                // GATE 1a (ratio) + GATE 1b (sub-cap) depend solely on this, so
                // words that fail the gate — the large majority of the 98k dict
                // for any typed word — skip the expensive adjacency-weighted
                // sum (a keyDistance/hypot per position) entirely.
                var exactCount = 0
                for (i in 0 until wordLength) {
                    if (lowerTypedWord[i] == dictWord[i]) exactCount++
                }
                val substitutions = wordLength - exactCount
                isMultiSub = substitutions >= 2
                if (exactCount.toFloat() / wordLength >= MIN_SAME_LENGTH_EXACT_RATIO &&
                    substitutions <= MAX_SAME_LENGTH_SUBSTITUTIONS
                ) {
                    // Pass 2: adjacency-weighted score, only for gate survivors.
                    var weightedSum = 0f
                    for (i in 0 until wordLength) {
                        weightedSum += KeyAdjacency.substitutionScore(lowerTypedWord[i], dictWord[i])
                    }
                    weightedSum / wordLength
                } else -1f
                }
            } else {
                val maxEd = lengthDiff + LENGTH_DIFF_ED_BUDGET
                // Early-abandon budget: most dict words in the ±length band are
                // unrelated and blow past maxEd after 2-3 DP rows. When ed > maxEd
                // the sweep rejects anyway, so a fast above-budget lower bound is
                // equivalent and avoids the full n×m DP over ~50% of 98k words.
                val ed = KeyAdjacency.weightedEditDistance(lowerTypedWord, dictWord, maxEd)
                if (ed <= maxEd) {
                    val maxLen = maxOf(wordLength, dictWord.length).toFloat()
                    (1f - ed / maxLen).coerceAtLeast(0f)
                } else {
                    -1f
                }
            }

            if (diagnosticsEnabled && score >= 0.4f) {
                rejectionLog += dictWord to score
            }

            if (score >= charMatchThreshold) {
                // Tiebreaker — RAW-score dominance first, then structural
                // rules inside the ±SCORE_TIEBREAK_GAP band, then frequency:
                //   1. SCORE PRIMARY — a candidate more than the gap better
                //      wins outright (`wuestion → question` 0.986 beats the
                //      freq-popular but distant `within`).
                //   2. Within the band, ALIAS PRIVILEGE: a bare-contraction
                //      key wins against a non-alias ONLY at equal-or-better
                //      raw score. This flips true ties toward the contraction
                //      (`donr → don't`, dont/done both 0.972) but — unlike the
                //      old +0.15 score bonus, which could beat candidates up
                //      to 0.15 STRONGER — can no longer override a
                //      structurally better match (`thier → their`, not
                //      `this'd`: the transposition outscores the 2-sub alias).
                //   3. Within the band, TRANSPOSITION beats a 2-substitution
                //      candidate regardless of frequency: one swap is almost
                //      always the intent vs two independent wrong keys
                //      (`thsi → this`, not the more frequent `that`).
                //   4. FREQ — otherwise the more common word wins (the normal
                //      "several 1-sub candidates" case).
                val isAlias = dictWord in contractionAliases
                val bestIsAlias = bestCandidate?.isAlias == true
                val better = when {
                    bestCandidate == null -> true
                    // Raw-score dominance beyond the gap.
                    score > bestCandidate.score + SCORE_TIEBREAK_GAP -> true
                    score < bestCandidate.score - SCORE_TIEBREAK_GAP -> false
                    // Alias vs alias: structural closeness (raw score) wins,
                    // NOT frequency — sibling contractions (`hadnt` vs `hasnt`)
                    // sit at similar freqs and typing `hadnr` means `hadnt`.
                    isAlias && bestIsAlias -> score > bestCandidate.score
                    // Alias privilege: only at equal-or-better raw score.
                    isAlias && !bestIsAlias -> score >= bestCandidate.score
                    bestIsAlias && !isAlias -> score > bestCandidate.score
                    // One Damerau swap beats two independent substitutions.
                    isTransposition && bestCandidate.isMultiSub -> true
                    bestCandidate.isTransposition && isMultiSub -> false
                    // Within the band, normal case → frequency wins.
                    candidateFrequency > bestCandidate.frequency -> true
                    candidateFrequency < bestCandidate.frequency -> false
                    // Score-close AND freq-tied → deterministic by score.
                    else -> score > bestCandidate.score
                }
                if (better) {
                    bestCandidate = AutocorrectCandidate(
                        dictWord, score, candidateFrequency,
                        isAlias, isTransposition, isMultiSub
                    )
                }
            }
        }

        // 5. Apply correction only if confident candidate found.
        // Custom/user-added words are injected at a low placeholder frequency
        // (1000) far below the binary dict's runtime scale (~52k..1M), so any
        // non-zero slider floor would silently exclude EVERY custom word as a
        // correction target. The user added them explicitly, so exempt them
        // from the floor — analogous to the custom-word override in
        // isWordDisabled. (AC-2, 2026-07.)
        val winnerIsCustom = bestCandidate != null &&
            customAndUserWords.contains(bestCandidate.word)
        if (bestCandidate != null && (winnerIsCustom || bestCandidate.frequency >= frequencyFloor)) {
            // Re-route alias-keyed winners through contractionAliases so the
            // returned form is the apostrophe-bearing contraction. Without
            // this, `donr → dont` (the alias-key) would stop there; the
            // user-visible result must be `don't`. The same I-capitalization
            // rule from step 0 applies.
            val winnerWord = bestCandidate.word
            val aliasTarget = contractionAliases[winnerWord]
            val outputWord = aliasTarget ?: winnerWord
            val corrected = if (aliasTarget != null && aliasTarget.startsWith("i'")) {
                aliasTarget.replaceFirstChar { it.uppercase() }
            } else {
                preserveCapitalization(typedWord, outputWord)
            }
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "AUTO-CORRECT: '$typedWord' → '$corrected' " +
                "(winner=$winnerWord score=${"%.3f".format(bestCandidate.score)} " +
                "freq=${bestCandidate.frequency})")
            return corrected
        }

        // Diagnostic logging on rejection. Symmetric with the success log so
        // "why didn't it correct X?" is answerable from logcat.
        if (diagnosticsEnabled) {
            val top = rejectionLog.sortedByDescending { it.second }.take(5)
                .joinToString(", ") { "${it.first}=${"%.3f".format(it.second)}" }
            val reason = when {
                bestCandidate == null -> "no candidate above threshold $charMatchThreshold"
                bestCandidate.frequency < frequencyFloor ->
                    "best='${bestCandidate.word}' freq=${bestCandidate.frequency} < floor=$frequencyFloor"
                else -> "?"
            }
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "AUTO-CORRECT-REJECT: '$typedWord' [$reason]  top=[$top]")
        }

        return typedWord // No suitable correction found
    }

    /**
     * Preserve capitalization of original word when applying correction.
     *
     * Examples:
     * - "teh" + "the" → "the"
     * - "Teh" + "the" → "The"
     * - "TEH" + "the" → "THE"
     */
    private fun preserveCapitalization(originalWord: String, correctedWord: String): String {
        if (originalWord.isEmpty() || correctedWord.isEmpty()) {
            return correctedWord
        }

        // Check if ALL uppercase
        val isAllUpper = originalWord.all { it.isUpperCase() || !it.isLetter() }

        if (isAllUpper) {
            return correctedWord.uppercase()
        }

        // Check if first letter uppercase (Title Case)
        if (originalWord[0].isUpperCase()) {
            return correctedWord[0].uppercase() + correctedWord.substring(1)
        }

        return correctedWord
    }

    /**
     * Get dictionary size
     */
    fun getDictionarySize(): Int {
        return dictionary.get().size
    }

    /**
     * Helper class to store word candidates with scores (used by
     * `predictWords` — score is the unified ranking integer from
     * `calculateUnifiedScore`, NOT a [0,1] match score).
     */
    private data class WordCandidate(val word: String, val score: Int)

    /**
     * Helper class to store autocorrect candidates.
     *
     * `score` is the RAW adjacency-weighted match quality in [0, 1] (no
     * bonuses — alias preference is a tiebreak RULE, not a score bump).
     * `frequency` is the raw dictionary frequency (scale varies by loader —
     * binary 5K-1M, JSON 100-10K). The structural flags feed the in-band
     * tiebreaker: alias privilege (equal-or-better raw only) and
     * transposition-beats-2-substitutions.
     *
     * Kept distinct from `WordCandidate` so the prediction path's
     * Int-score contract isn't conflated with the autocorrect path's
     * Float-score-+-Int-freq pair.
     */
    private data class AutocorrectCandidate(
        val word: String,
        val score: Float,
        val frequency: Int,
        val isAlias: Boolean,
        val isTransposition: Boolean,
        val isMultiSub: Boolean
    )

    /**
     * Result class containing predictions and their scores
     */
    /**
     * @property metas per-suggestion provenance parallel to [words] (Task B
     *   transparency); null on legacy/empty paths. Origin is DICTIONARY_PREFIX
     *   for every unified-scorer word; breakdowns are present for primary-
     *   dictionary words (secondary-dictionary entries carry origin only).
     */
    data class PredictionResult(
        @JvmField val words: List<String>,
        @JvmField val scores: List<Int>,
        @JvmField val metas: List<SuggestionMeta>? = null
    )
}
