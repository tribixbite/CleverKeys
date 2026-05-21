package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.provider.UserDictionary
import android.util.Log
import tribixbite.cleverkeys.autocorrect.KeyAdjacency
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
class WordPredictor {
    companion object {
        private const val TAG = "WordPredictor"
        private const val MAX_PREDICTIONS_TYPING = 5
        private const val MAX_PREDICTIONS_SWIPE = 10

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

        /**
         * Score bonus applied to alias-key candidates (bare-form
         * contractions like `dont`, `cant`, `hadnt`) in the dict-scan
         * tiebreaker. Sized so an alias-key's effective score clears
         * [SCORE_TIEBREAK_GAP] above a tied non-alias — that flips ties
         * toward the contraction (`donr → don't` instead of `done`)
         * without overriding clearly-better non-alias matches.
         */
        private const val ALIAS_SCORE_BONUS = 0.15f

        /**
         * Score-difference threshold for score-vs-frequency tiebreaker.
         * When two candidates differ by MORE than this in effective
         * score, the higher-scoring one wins regardless of frequency.
         * When the gap is within this band, frequency is the tiebreaker
         * (the more popular word wins).
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
         * Calibrated against:
         *   - `donr → dont` (alias bonus pushes gap to 0.15) → alias wins
         *   - `tge → the` (gap 0.149 vs `weve`) → score wins (the)
         *   - `tfe → the` (gap 0.037 vs `tfw`) → freq wins (the)
         *   - `quuestion → question` (gap 0.049 vs `quotation`) → freq wins
         *   - `questin → question` (gap 0.052 vs `quentin`) → freq wins
         */
        private const val SCORE_TIEBREAK_GAP = 0.10f
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
            Log.d(TAG, "Reload signal set - all instances will reload on next prediction")
        }
    }

    // OPTIMIZATION v4 (perftodos4.md): Use AtomicReference for lock-free atomic map swapping
    // Allows O(1) atomic swap instead of O(n) putAll() on main thread during async loading
    private val dictionary: AtomicReference<MutableMap<String, Int>> = AtomicReference(mutableMapOf())
    private val prefixIndex: AtomicReference<MutableMap<String, MutableSet<String>>> = AtomicReference(mutableMapOf())
    private var bigramModel: BigramModel? = BigramModel.getInstance(null)
    private var contextModel: ContextModel? = null // Phase 7.1: Dynamic N-gram model
    private var personalizationEngine: PersonalizationEngine? = null // Phase 7.2: Personalized learning
    private var personalizedScorer: PersonalizedScorer? = null // Phase 7.2: Adaptive scoring
    private var languageDetector: LanguageDetector? = LanguageDetector()
    private var multiLanguageManager: MultiLanguageManager? = null // Phase 8.3: Multi-language models
    private var multiLanguageDictManager: MultiLanguageDictionaryManager? = null // Phase 8.4: Multi-language dictionaries
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

        // Phase 7.1: Initialize ContextModel for dynamic N-gram predictions
        if (contextModel == null) {
            contextModel = ContextModel(context)
            Log.d(TAG, "ContextModel initialized for dynamic N-gram predictions")
        }

        // Phase 7.2: Initialize PersonalizationEngine for personalized learning
        if (personalizationEngine == null) {
            personalizationEngine = PersonalizationEngine(context)
            personalizedScorer = PersonalizedScorer(personalizationEngine!!)
            Log.d(TAG, "PersonalizationEngine and PersonalizedScorer initialized for adaptive predictions")
        }

        // Phase 8.3 & 8.4: Initialize Multi-Language support if enabled
        val enableMultiLang = config?.enable_multilang ?: false
        if (enableMultiLang) {
            if (multiLanguageManager == null) {
                val primaryLang = config?.primary_language ?: "en"
                multiLanguageManager = MultiLanguageManager(context, primaryLang)
                Log.d(TAG, "MultiLanguageManager initialized (primary: $primaryLang)")
            }
            if (multiLanguageDictManager == null) {
                multiLanguageDictManager = MultiLanguageDictionaryManager(context)
                Log.d(TAG, "MultiLanguageDictionaryManager initialized")
            }
        }

        // Initialize dictionary observer for automatic updates
        if (dictionaryObserver == null) {
            dictionaryObserver = UserDictionaryObserver(context).apply {
                setChangeListener(object : UserDictionaryObserver.ChangeListener {
                    override fun onUserDictionaryChanged(addedWords: Map<String, Int>, removedWords: Set<String>) {
                        handleIncrementalUpdate(addedWords, removedWords)
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
    fun stopObservingDictionaryChanges() {
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
        Log.d(TAG, "Loaded ${disabledWords.size} disabled words for '$currentLanguage'")
    }

    /**
     * Check if a word is disabled
     */
    private fun isWordDisabled(word: String): Boolean {
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
    fun reloadCustomAndUserWords() {
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
            Log.d(TAG, "Auto-reloaded dictionaries due to signal")
        }
    }

    /**
     * Set the config for weight access
     */
    fun setConfig(config: Config) {
        this.config = config

        // Phase 7.2: Update personalization engine settings when config changes
        personalizationEngine?.let { engine ->
            engine.setEnabled(config.personalized_learning_enabled)

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
    }

    /**
     * Set the active language for N-gram predictions
     *
     * v1.1.91: Also updates UserDictionaryObserver to filter by new language.
     * v1.1.92: Reloads disabled words for language-specific key.
     */
    fun setLanguage(language: String) {
        currentLanguage = language
        bigramModel?.let {
            it.setLanguage(language)
            Log.d(TAG, "N-gram language set to: $language")
        }

        // Phase 8.3: Switch multi-language models if enabled
        multiLanguageManager?.let {
            val switched = it.switchLanguage(language)
            if (switched) {
                Log.d(TAG, "MultiLanguageManager switched to: $language")
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
    fun isInDictionary(word: String): Boolean {
        if (word.isEmpty()) return false
        val lowerWord = word.lowercase()
        // Check main dictionary
        if (dictionary.get().containsKey(lowerWord)) {
            return true
        }
        // Check if user has typed it frequently (adaptation manager)
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
    fun applyUserWordCaseToList(words: List<String>): List<String> {
        return words.map { applyUserWordCase(it) }
    }

    /**
     * Add a word to the recent words list for language detection
     */
    fun addWordToContext(word: String?) {
        if (word.isNullOrBlank()) return

        val normalizedWord = word.lowercase().trim()
        recentWords.add(normalizedWord)

        // Keep only the most recent words
        while (recentWords.size > MAX_RECENT_WORDS) {
            recentWords.removeAt(0)
        }

        // Phase 7.1: Record word sequences for dynamic N-gram learning
        // Only record if feature is enabled and we have at least 2 words (minimum for bigrams)
        val contextAwareEnabled = config?.context_aware_predictions_enabled ?: true
        if (contextAwareEnabled && recentWords.size >= 2 && contextModel != null) {
            // Record last few words as a sequence (up to 4 words for trigram future-proofing)
            val sequenceLength = kotlin.math.min(4, recentWords.size)
            val sequence = recentWords.takeLast(sequenceLength)
            contextModel?.recordSequence(sequence)
        }

        // Phase 7.2: Record word usage for personalized learning
        // Learn individual word frequencies for adaptive prediction boosting
        val personalizationEnabled = config?.personalized_learning_enabled ?: true
        if (personalizationEnabled && personalizationEngine != null) {
            personalizationEngine?.recordWordTyped(normalizedWord)
        }

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
    fun clearContext() {
        recentWords.clear()
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
        // incorrectly "correct" valid neural predictions (issue #63)
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
            Log.d(TAG, "Binary dictionary not available, falling back to JSON")

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
                Log.d(TAG, "Loaded JSON dictionary: $jsonFilename with ${dictionary.get().size} words")
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
                    Log.d(TAG, "Loaded text dictionary: $textFilename with ${dictionary.get().size} words")
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to load dictionary: ${e2.message}")
                }
            }

            // Build prefix index for fast lookup (only needed if JSON/text was loaded)
            buildPrefixIndex()
            Log.d(TAG, "Built prefix index: ${prefixIndex.get().size} prefixes for ${dictionary.get().size} words")
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
    fun loadDictionaryAsync(context: Context, language: String, callback: Runnable?) {
        // v1.2.0: Don't ignore reload requests - AsyncDictionaryLoader will cancel previous task
        // This fixes language toggle not reloading dictionary when initial load is in progress
        if (isLoadingState) {
            Log.i(TAG, "Dictionary load in progress, will cancel and reload for '$language'")
            isLoadingState = false  // Reset flag so new load can proceed
        }

        asyncLoader.loadDictionaryAsync(context, language, object : AsyncDictionaryLoader.LoadCallback {
            override fun onLoadStarted(lang: String) {
                isLoadingState = true
                Log.d(TAG, "Started async dictionary load: $lang")
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
                    Log.d(TAG, "Added $contractionKeys contraction keys during async load for '$language'")
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

                callback?.run()
            }

            override fun onLoadFailed(lang: String, error: Exception) {
                isLoadingState = false
                Log.e(TAG, "Async dictionary load failed: $lang", error)

                // Fall back to synchronous loading
                Log.d(TAG, "Falling back to synchronous dictionary load")
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
    fun isLoading(): Boolean {
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
    fun loadSecondaryDictionary(language: String): Boolean {
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
                // v1.1.94: Also load custom words for secondary language
                val customWordsAdded = loadSecondaryCustomWords(ctx, index, language)

                // v1.2.6: Also add contraction keys (apostrophe-free forms) for secondary language
                // This allows typing "dont" to find "don't" in secondary English dictionary
                val contractionsAdded = loadSecondaryContractionKeys(ctx, index, language)

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
    fun unloadSecondaryDictionary() {
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
                    Log.d(TAG, "Added $count custom words to secondary index for '$language'")
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

            inputStream.use { stream ->
                val reader = java.io.BufferedReader(java.io.InputStreamReader(stream))
                val jsonBuilder = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    jsonBuilder.append(line)
                }

                val jsonObj = org.json.JSONObject(jsonBuilder.toString())
                val keys = jsonObj.keys()

                val aliases = mutableMapOf<String, String>()

                while (keys.hasNext()) {
                    val withoutApostrophe = keys.next().lowercase(java.util.Locale.ROOT)
                    val withApostrophe = jsonObj.getString(withoutApostrophe).lowercase(java.util.Locale.ROOT)

                    // Skip real English words that are also contraction bases
                    if (withoutApostrophe in REAL_WORD_CONTRACTION_BASES) continue

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
                    Log.d(TAG, "No contractions file for primary language '$language'")
                    return 0
                }
            }

            inputStream.use { stream ->
                val reader = java.io.BufferedReader(java.io.InputStreamReader(stream))
                val jsonBuilder = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    jsonBuilder.append(line)
                }

                val jsonObj = org.json.JSONObject(jsonBuilder.toString())
                val keys = jsonObj.keys()

                val currentDict = dictionary.get()
                val currentPrefixIndex = prefixIndex.get()
                val aliases = mutableMapOf<String, String>()

                while (keys.hasNext()) {
                    val withoutApostrophe = keys.next().lowercase(java.util.Locale.ROOT)
                    val withApostrophe = jsonObj.getString(withoutApostrophe).lowercase(java.util.Locale.ROOT)

                    // Skip real English words that also happen to be contraction bases
                    // (e.g., "well" should stay "well", not autocorrect to "we'll")
                    if (withoutApostrophe in REAL_WORD_CONTRACTION_BASES) continue

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
                    // and multiplies by `Config.neural_frequency_weight`, so
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
                Log.d(TAG, "Added $count contraction keys to primary prefix index for '$language'")
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
                    Log.d(TAG, "No contractions file for secondary language '$language'")
                    return 0
                }
            }

            inputStream.use { stream ->
                val reader = java.io.BufferedReader(java.io.InputStreamReader(stream))
                val jsonBuilder = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    jsonBuilder.append(line)
                }

                val jsonObj = org.json.JSONObject(jsonBuilder.toString())
                val keys = jsonObj.keys()

                while (keys.hasNext()) {
                    val withoutApostrophe = keys.next().lowercase(java.util.Locale.ROOT)
                    val withApostrophe = jsonObj.getString(withoutApostrophe).lowercase(java.util.Locale.ROOT)

                    // Add the apostrophe-free form as an alias pointing to the contraction
                    // Use high frequency (low rank = common word) so it shows up in predictions
                    index.addWord(withoutApostrophe, 50) // rank 50 = common

                    count++
                }
            }

            if (count > 0) {
                Log.d(TAG, "Added $count contraction keys to secondary index for '$language'")
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
            // v1.1.90: Filter by locale to prevent English contamination in non-English modes
            // v1.1.91: Use LIKE for partial locale match (e.g., "fr" matches "fr", "fr_FR", "fr_CA")
            // v1.2.0: Only include null-locale words for English (untagged words are typically English)
            try {
                // Match: exact language code, or locale starting with language code (e.g., fr_FR)
                // Only include null locale (untagged words) for English to prevent contamination
                val selection: String
                val selectionArgs: Array<String>
                if (language == "en") {
                    // For English: include untagged words (likely English anyway)
                    selection = "${UserDictionary.Words.LOCALE} = ? OR ${UserDictionary.Words.LOCALE} LIKE ? OR ${UserDictionary.Words.LOCALE} IS NULL"
                    selectionArgs = arrayOf(language, "$language%")
                } else {
                    // For other languages: exclude untagged words to prevent English contamination
                    selection = "${UserDictionary.Words.LOCALE} = ? OR ${UserDictionary.Words.LOCALE} LIKE ?"
                    selectionArgs = arrayOf(language, "$language%")
                }

                val cursor = context.contentResolver.query(
                    UserDictionary.Words.CONTENT_URI,
                    arrayOf(
                        UserDictionary.Words.WORD,
                        UserDictionary.Words.FREQUENCY
                    ),
                    selection,
                    selectionArgs,
                    null
                )

                cursor?.use {
                    val wordIndex = it.getColumnIndex(UserDictionary.Words.WORD)
                    val freqIndex = it.getColumnIndex(UserDictionary.Words.FREQUENCY)
                    var userCount = 0

                    while (it.moveToNext()) {
                        val originalWord = it.getString(wordIndex)
                        val lowerWord = originalWord.lowercase()
                        val frequency = if (freqIndex >= 0) it.getInt(freqIndex) else 1000
                        targetMap[lowerWord] = frequency  // Write to target map, not dictionary
                        loadedWords.add(lowerWord)
                        // v1.2.7: Preserve original case for proper nouns (Issue #72)
                        if (originalWord != lowerWord) {
                            userWordOriginalCase[lowerWord] = originalWord
                        }
                        userCount++
                    }

                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                        Log.d(TAG, "Loaded $userCount user dictionary words for locale '$language' into new map")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load user dictionary", e)
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
            // v1.1.90: Filter by locale to prevent English contamination in non-English modes
            // v1.1.91: Use LIKE for partial locale match (e.g., "fr" matches "fr", "fr_FR", "fr_CA")
            // v1.2.0: Only include null-locale words for English (untagged words are typically English)
            try {
                // Match: exact language code, or locale starting with language code (e.g., fr_FR)
                // Only include null locale (untagged words) for English to prevent contamination
                val selection: String
                val selectionArgs: Array<String>
                if (language == "en") {
                    // For English: include untagged words (likely English anyway)
                    selection = "${UserDictionary.Words.LOCALE} = ? OR ${UserDictionary.Words.LOCALE} LIKE ? OR ${UserDictionary.Words.LOCALE} IS NULL"
                    selectionArgs = arrayOf(language, "$language%")
                } else {
                    // For other languages: exclude untagged words to prevent English contamination
                    selection = "${UserDictionary.Words.LOCALE} = ? OR ${UserDictionary.Words.LOCALE} LIKE ?"
                    selectionArgs = arrayOf(language, "$language%")
                }

                val cursor = context.contentResolver.query(
                    UserDictionary.Words.CONTENT_URI,
                    arrayOf(
                        UserDictionary.Words.WORD,
                        UserDictionary.Words.FREQUENCY
                    ),
                    selection,
                    selectionArgs,
                    null
                )

                cursor?.use {
                    val wordIndex = it.getColumnIndex(UserDictionary.Words.WORD)
                    val freqIndex = it.getColumnIndex(UserDictionary.Words.FREQUENCY)
                    var userCount = 0

                    while (it.moveToNext()) {
                        val originalWord = it.getString(wordIndex)
                        val lowerWord = originalWord.lowercase()
                        val frequency = if (freqIndex >= 0) it.getInt(freqIndex) else 1000
                        dictionary.get()[lowerWord] = frequency
                        loadedWords.add(lowerWord)  // Track loaded word
                        // v1.2.7: Preserve original case for proper nouns (Issue #72)
                        if (originalWord != lowerWord) {
                            userWordOriginalCase[lowerWord] = originalWord
                        }
                        userCount++
                    }

                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                        Log.d(TAG, "Loaded $userCount user dictionary words for locale '$language'")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load user dictionary", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading custom/user words", e)
        }

        return loadedWords
    }

    /**
     * Reset the predictor state - called after space/punctuation
     */
    fun reset() {
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
    fun predictWordsWithContext(keySequence: String, context: List<String>): PredictionResult {
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
            Log.d(TAG, "Predicting for: '$lowerSequence' (lang=$currentLanguage, dictSize=${dictionary.get().size})")
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

            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                Log.d(TAG, "Final predictions (${casedPredictions.size}): $casedPredictions")
                Log.d(TAG, "Scores: $scores")
            }

            return PredictionResult(casedPredictions, scores)
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
        // 1. Base score from prefix match quality
        val prefixScore = calculatePrefixScore(word, keySequence)
        if (prefixScore == 0) return 0 // Should not happen if caller does prefix check

        // 2. User adaptation multiplier (learns user's vocabulary)
        val adaptationMultiplier = adaptationManager?.getAdaptationMultiplier(word) ?: 1.0f

        // 3a. Static context multiplier (bigram probability boost from hardcoded model)
        val staticContextMultiplier = if (bigramModel != null && context.isNotEmpty()) {
            bigramModel?.getContextMultiplier(word, context) ?: 1.0f
        } else {
            1.0f
        }

        // 3b. Phase 7.1: Dynamic context boost from learned N-gram model
        // ContextModel provides personalized boost based on user's actual typing patterns
        // Only apply if feature is enabled in settings
        val contextAwareEnabled = config?.context_aware_predictions_enabled ?: true
        val dynamicContextBoost = if (contextAwareEnabled && contextModel != null && context.isNotEmpty()) {
            contextModel?.getContextBoost(word, context) ?: 1.0f
        } else {
            1.0f
        }

        // 3c. Combine static and dynamic context signals
        // Both contribute to final context multiplier: static for common phrases, dynamic for personal patterns
        // Maximum of the two to take best prediction (user patterns override static when stronger)
        val contextMultiplier = kotlin.math.max(staticContextMultiplier, dynamicContextBoost)

        // 3d. Phase 7.2: Personalization boost from user vocabulary
        // Boosts words the user frequently types, independent of context
        // Combines frequency and recency scoring for adaptive predictions
        val personalizationEnabled = config?.personalized_learning_enabled ?: true
        val personalizationMultiplier = if (personalizationEnabled && personalizationEngine != null) {
            val boost = personalizationEngine?.getPersonalizationBoost(word) ?: 0.0f
            // Convert boost (0-6 range) to multiplier (1.0-2.5 range)
            // Formula: 1.0 + (boost / 4.0) gives smooth 1.0-2.5x range
            1.0f + (boost / 4.0f)
        } else {
            1.0f
        }

        // 4. Frequency scaling (log to prevent common words from dominating)
        // Using log1p helps balance: "the" (freq ~10000) vs "think" (freq ~100)
        // Without log: "the" would always win. With log: context can override frequency
        // Scale factor is configurable (default: 1000.0)
        val frequencyScale = config?.prediction_frequency_scale ?: 1000.0f
        val frequencyFactor = 1.0f + ln1p((frequency / frequencyScale).toDouble()).toFloat()

        // COMBINE ALL SIGNALS
        // Formula: prefixScore × adaptation × personalization × (1 + boosted_context) × freq_factor
        // Context boost is configurable (default: 2.0)
        // Higher boost = context has more influence on predictions
        val contextBoost = config?.prediction_context_boost ?: 2.0f
        val finalScore = prefixScore *
            adaptationMultiplier *
            personalizationMultiplier *  // Phase 7.2: Personalization boost
            (1.0f + (contextMultiplier - 1.0f) * contextBoost) *  // Configurable context boost
            frequencyFactor

        return finalScore.toInt()
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
    fun autoCorrect(typedWord: String): String {
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
            Log.d(TAG, "AUTO-CORRECT (contraction): '$typedWord' → '$corrected'")
            return corrected
        }

        // 1. Do not correct words already in dictionary or user's vocabulary
        if (dictionary.get().containsKey(lowerTypedWord) ||
            (adaptationManager?.getAdaptationMultiplier(lowerTypedWord) ?: 0f) > 1.0f
        ) {
            return typedWord
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
        val frequencyFloor = config?.autocorrect_confidence_min_frequency ?: 500
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
            val score: Float = if (lengthDiff == 0) {
                var exactCount = 0
                var weightedSum = 0f
                for (i in 0 until wordLength) {
                    val a = lowerTypedWord[i]
                    val b = dictWord[i]
                    if (a == b) exactCount++
                    weightedSum += KeyAdjacency.substitutionScore(a, b)
                }
                val exactRatio = exactCount.toFloat() / wordLength
                val weightedScore = weightedSum / wordLength
                if (exactRatio >= MIN_SAME_LENGTH_EXACT_RATIO) weightedScore else -1f
            } else {
                val ed = KeyAdjacency.weightedEditDistance(lowerTypedWord, dictWord)
                val maxEd = lengthDiff + LENGTH_DIFF_ED_BUDGET
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
                // Two-level tiebreaker:
                //   1. SCORE PRIMARY — higher-scoring candidate wins. This
                //      stops a freq-popular but distantly-aligned word from
                //      beating a high-quality match: e.g. `wuestion → within`
                //      (lenDiff=2 weighted-ed-passes-budget, freq 245)
                //      should NOT beat `wuestion → question` (lenDiff=0,
                //      near-perfect score 0.986, freq 243).
                //   2. FREQ SECONDARY — when scores are equal (the common
                //      "single adjacent-key sub" case where multiple words
                //      tie), the more common word wins.
                //
                // Alias-keys get a small `ALIAS_SCORE_BONUS` to flip ties
                // toward the contraction (`donr → don't` not `done`),
                // sized small enough that a clearly-better non-alias still
                // wins (`tge → the` not `we've`).
                val isAlias = dictWord in contractionAliases
                val effectiveScore = if (isAlias) score + ALIAS_SCORE_BONUS else score
                val bothAliases = isAlias && (bestCandidate?.word in contractionAliases)
                val better = when {
                    bestCandidate == null -> true
                    // Strictly better score by more than the tiebreak gap → win.
                    effectiveScore > bestCandidate.effectiveScore + SCORE_TIEBREAK_GAP -> true
                    // Strictly worse score by more than the gap → lose.
                    effectiveScore < bestCandidate.effectiveScore - SCORE_TIEBREAK_GAP -> false
                    // Alias vs alias: structural closeness (raw score) wins, NOT
                    // frequency. Sibling contractions (`hadnt` vs `hasnt`) all
                    // sit at similar freqs, and typing `hadnr` (d/t-adjacent) means
                    // `hadnt` — `hasnt` only matches via TWO substitutions and
                    // should lose to the single-typo match regardless of dict
                    // popularity.
                    bothAliases -> effectiveScore > bestCandidate.effectiveScore
                    // Within the gap, normal case → frequency wins (the more
                    // popular word for close-quality matches).
                    candidateFrequency > bestCandidate.frequency -> true
                    candidateFrequency < bestCandidate.frequency -> false
                    // Score-close AND freq-tied → deterministic by score.
                    else -> effectiveScore > bestCandidate.effectiveScore
                }
                if (better) {
                    bestCandidate = AutocorrectCandidate(dictWord, effectiveScore, candidateFrequency)
                }
            }
        }

        // 5. Apply correction only if confident candidate found.
        if (bestCandidate != null && bestCandidate.frequency >= frequencyFloor) {
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
            Log.d(TAG, "AUTO-CORRECT: '$typedWord' → '$corrected' " +
                "(winner=$winnerWord score=${"%.3f".format(bestCandidate.effectiveScore)} " +
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
            Log.d(TAG, "AUTO-CORRECT-REJECT: '$typedWord' [$reason]  top=[$top]")
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
     * `effectiveScore` is the adjacency-weighted match quality plus any
     * alias bonus, in [0, ~1.05]. `frequency` is the raw dictionary
     * frequency (scale varies by loader — binary 5K-1M, JSON 100-10K).
     * Selection sorts by effectiveScore desc, then frequency desc.
     *
     * Kept distinct from `WordCandidate` so the prediction path's
     * Int-score contract isn't conflated with the autocorrect path's
     * Float-score-+-Int-freq pair.
     */
    private data class AutocorrectCandidate(
        val word: String,
        val effectiveScore: Float,
        val frequency: Int
    )

    /**
     * Result class containing predictions and their scores
     */
    data class PredictionResult(@JvmField val words: List<String>, @JvmField val scores: List<Int>)
}
