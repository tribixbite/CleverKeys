package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import tribixbite.cleverkeys.VocabularyCache
import tribixbite.cleverkeys.Config // Assuming Config is in this package or imported
import tribixbite.cleverkeys.langpack.LanguagePackManager
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

/**
 * Optimized vocabulary filtering for neural swipe predictions
 * Ports web app swipe-vocabulary.js optimizations to Android
 *
 * Features:
 * - Common words fast-path for instant lookup
 * - Hierarchical vocabulary (common -> top5000 -> full)
 * - Combined confidence + frequency scoring
 * - Length-based filtering and word lookup
 * - Accent-aware lookups via NormalizedPrefixIndex (multilanguage support)
 */
class OptimizedVocabulary(private val context: Context) {

    private val TAG = "OptimizedVocabulary"

    // OPTIMIZATION: Single unified lookup structure (1 hash lookup instead of 3)
    private val vocabulary: MutableMap<String, WordInfo> = HashMap()

    // OPTIMIZATION Phase 2: Trie for constrained beam search (eliminates invalid paths)
    // This is the English vocabulary trie, always loaded
    private val vocabularyTrie: VocabularyTrie = VocabularyTrie()

    // MULTILANGUAGE: Language-specific trie for beam search constraint
    // When Primary=English, this points to vocabularyTrie
    // When Primary=French, this is built from French normalized words
    // Beam search uses this trie, not vocabularyTrie directly
    // v1.1.92: CRITICAL FIX - Must be @Volatile for cross-thread visibility!
    // loadPrimaryDictionary() writes on init thread, getVocabularyTrie() reads on main thread
    @Volatile
    private var activeBeamSearchTrie: VocabularyTrie = vocabularyTrie

    // OPTIMIZATION Phase 2: Length-based buckets for fuzzy matching (reduces 50k iteration to ~2k)
    // Maps word length -> list of words with that length
    private val vocabularyByLength: MutableMap<Int, MutableList<String>> = HashMap()

    // MULTILANGUAGE: Accent-aware lookup for primary/secondary dictionaries
    // Maps normalized (accent-free) → canonical (with accents) + frequency rank
    // v1.1.92: Must be @Volatile for cross-thread visibility (written on init, read on swipe)
    @Volatile
    private var normalizedIndex: NormalizedPrefixIndex? = null
    @Volatile
    private var secondaryNormalizedIndex: NormalizedPrefixIndex? = null

    // Scoring parameters (tuned for 50k vocabulary)
    private val CONFIDENCE_WEIGHT = 0.6f
    private val FREQUENCY_WEIGHT = 0.4f
    private val COMMON_WORDS_BOOST = 1.3f  // Increased for 50k vocab
    private val TOP5000_BOOST = 1.0f
    private val RARE_WORDS_PENALTY = 0.75f // Strengthened for 50k vocab

    // Filtering thresholds
    private val minFrequencyByLength: MutableMap<Int, Float> = HashMap()

    // Disabled words filter (for Dictionary Manager integration)
    private var disabledWords: MutableSet<String> = HashSet()

    // Contraction handling (for apostrophe display)
    // Maps base word -> list of contraction variants (e.g., "well" -> ["we'll"])
    private var contractionPairings: MutableMap<String, MutableList<String>> = HashMap()
    // Maps apostrophe-free -> with apostrophe (e.g., "dont" -> "don't")
    private var nonPairedContractions: MutableMap<String, String> = HashMap()

    @Volatile
    private var isLoaded = false
    private var contractionsLoadedFromCache = false // v1.32.522: Track if contractions cached

    // OPTIMIZATION Phase 1 FIX: Cache ALL config settings to avoid SharedPreferences reads on every swipe
    // These are updated via updateConfig() when settings change
    private var _debugMode = false
    private var _confidenceWeight = CONFIDENCE_WEIGHT
    private var _frequencyWeight = FREQUENCY_WEIGHT
    private var _neuralFrequencyWeight = 1.0f // Multiplier from NeuralSettingsActivity (0.0 = NN only, 2.0 = heavy freq)
    private var _commonBoost = COMMON_WORDS_BOOST
    private var _top5000Boost = TOP5000_BOOST
    private var _rarePenalty = RARE_WORDS_PENALTY
    private var _swipeAutocorrectEnabled = true
    private var _maxLengthDiff = 2
    private var _prefixLength = 2
    private var _maxBeamCandidates = 3
    private var _minWordLength = 2
    private var _charMatchThreshold = 0.67f
    private var _useEditDistance = true
    private var _autocorrect_confidence_min_frequency: Int = 500 // Added for user-configured min frequency

    // LANGUAGE DETECTION: Auto-switch configuration
    // When detected language score exceeds threshold, boost that language's dictionary
    private var _autoSwitchEnabled = false
    private var _autoSwitchThreshold = 0.6f // Switch when secondary language score > 60%
    // v1.1.92: Language config vars must be @Volatile - written by setPrimaryLanguageConfig,
    // read by getVocabularyTrie() on different thread
    @Volatile
    private var _secondaryLanguageCode = "none" // Current secondary language code (e.g., "es")
    @Volatile
    private var _primaryLanguageCode = "en" // Current primary language code
    @Volatile
    private var _englishFallbackEnabled = true // Whether to use English vocabulary as fallback
    @Volatile
    private var _currentLanguageMultiplier = 0.9f // Dynamic multiplier based on detected language
    private var _secondaryPredictionWeight = 0.9f // v1.1.94: Base weight for secondary predictions (from config)

    // OPTIMIZATION Phase 2: Cache parsed custom words to avoid JSON parsing on every swipe
    // Maps custom word -> frequency
    private val _cachedCustomWords: MutableMap<String, Int> = HashMap()
    private var _lastCustomWordsJson = "" // Track last parsed JSON to avoid redundant parsing

    /**
     * Get the active vocabulary trie for constrained beam search.
     * Returns the language-appropriate trie based on primary language setting:
     * - Primary=English: Returns English vocabulary trie
     * - Primary=French: Returns French normalized words trie
     *
     * CRITICAL v1.1.89: If primary is non-English but activeBeamSearchTrie still points
     * to vocabularyTrie (English), return null to disable trie constraining rather than
     * using the wrong language's vocabulary.
     *
     * @return The active beam search trie, or null if not loaded or language mismatch
     */
    fun getVocabularyTrie(): VocabularyTrie? {
        if (!isLoaded) return null

        val isLanguageTrie = activeBeamSearchTrie !== vocabularyTrie
        val stats = activeBeamSearchTrie.getStats()

        // CRITICAL CHECK: If primary is non-English, we MUST have a language-specific trie
        // If we're still pointing to vocabularyTrie (English), something went wrong with
        // loadPrimaryDictionary() - disable trie constraining to avoid English contamination
        if (_primaryLanguageCode != "en" && !isLanguageTrie) {
            Log.e(TAG, "🚨 LANGUAGE MISMATCH: primary=$_primaryLanguageCode but using English trie! " +
                "Disabling trie constraint to avoid contamination. " +
                "Call loadPrimaryDictionary() to fix.")
            return null  // Return null to disable trie constraining
        }

        Log.d(TAG, "getVocabularyTrie(): isLanguageTrie=$isLanguageTrie, " +
            "trieWords=${stats.first}, primary=$_primaryLanguageCode, englishFallback=$_englishFallbackEnabled")

        // v1.1.89 DIAGNOSTIC: Check if English words are in the trie (they shouldn't be for non-English primary)
        if (_primaryLanguageCode != "en") {
            val englishTestWords = listOf("the", "brother", "open", "still", "that", "this", "what", "with", "have")
            val foundEnglish = englishTestWords.filter { activeBeamSearchTrie.containsWord(it) }
            if (foundEnglish.isNotEmpty()) {
                Log.e(TAG, "🚨 TRIE CONTAMINATION: English words found in $_primaryLanguageCode trie: $foundEnglish")
            } else {
                Log.d(TAG, "✅ Trie clean: No English test words found")
            }
        }

        return activeBeamSearchTrie
    }

    /**
     * CRITICAL FIX: Update cached config settings to eliminate SharedPreferences reads in hot path
     * Call this from NeuralSwipeTypingEngine.updateConfig() when settings change
     */
    fun updateConfig(config: Config?) {
        if (config == null) return

        _debugMode = config.swipe_debug_detailed_logging

        // Use pre-calculated weights from Config.java
        _confidenceWeight = config.swipe_confidence_weight
        _frequencyWeight = config.swipe_frequency_weight

        // Boost/penalty values (use defaults if not set)
        _commonBoost = if (config.swipe_common_words_boost > 0) config.swipe_common_words_boost else COMMON_WORDS_BOOST
        _top5000Boost = if (config.swipe_top5000_boost > 0) config.swipe_top5000_boost else TOP5000_BOOST
        _rarePenalty = if (config.swipe_rare_words_penalty > 0) config.swipe_rare_words_penalty else RARE_WORDS_PENALTY

        // Autocorrect settings
        _swipeAutocorrectEnabled = config.swipe_beam_autocorrect_enabled

        // Neural frequency weight multiplier (0.0 = NN only, 1.0 = normal, 2.0 = double freq influence)
        _neuralFrequencyWeight = if (config.neural_frequency_weight >= 0f) config.neural_frequency_weight else 1.0f

        _maxLengthDiff = config.autocorrect_max_length_diff
        _prefixLength = config.autocorrect_prefix_length
        _maxBeamCandidates = config.autocorrect_max_beam_candidates
        _minWordLength = config.autocorrect_min_word_length
        _charMatchThreshold = config.autocorrect_char_match_threshold
        _useEditDistance = "edit_distance" == config.swipe_fuzzy_match_mode
        _autocorrect_confidence_min_frequency = config.autocorrect_confidence_min_frequency // Cache this value
        _secondaryPredictionWeight = config.secondary_prediction_weight // v1.1.94: Secondary dictionary weight

        // OPTIMIZATION Phase 2: Parse and cache custom words here instead of on every swipe
        // v1.1.86: Uses language-specific keys
        try {
            val prefs = DirectBootAwarePreferences.get_shared_preferences(context)

            // Run migration if needed (idempotent)
            LanguagePreferenceKeys.migrateToLanguageSpecific(prefs)

            val customWordsKey = LanguagePreferenceKeys.customWordsKey(_primaryLanguageCode)
            val customWordsJson = prefs.getString(customWordsKey, "{}")

            // Only parse if content changed
            if (customWordsJson != _lastCustomWordsJson) {
                _cachedCustomWords.clear()
                if (customWordsJson != "{}") {
                    val jsonObj = org.json.JSONObject(customWordsJson)
                    val keys = jsonObj.keys()
                    while (keys.hasNext()) {
                        val customWord = keys.next().toLowerCase(Locale.ROOT)
                        val customFreq = jsonObj.optInt(customWord, 1000)
                        _cachedCustomWords[customWord] = customFreq
                    }
                    Log.d(TAG, "Cached ${_cachedCustomWords.size} custom words for $_primaryLanguageCode")
                }
                _lastCustomWordsJson = customWordsJson ?: "" // Handle null string
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse custom words JSON", e)
            _cachedCustomWords.clear()
            _lastCustomWordsJson = "{}" // Reset on error
        }

        Log.d(TAG, "Config cached: confidenceWeight=" + _confidenceWeight + ", autocorrect=" + _swipeAutocorrectEnabled)
    }

    /**
     * Load vocabulary from assets with frequency data
     * Creates hierarchical structure for fast filtering
     *
     * @param primaryLanguageCode The primary language code for contraction loading (default "en")
     */
    fun loadVocabulary(primaryLanguageCode: String = "en"): Boolean {
        try {
            // v1.1.93 CRITICAL FIX: Do NOT set _primaryLanguageCode here!
            // Setting _primaryLanguageCode before loadPrimaryDictionary() completes creates a race:
            // - getVocabularyTrie() sees _primaryLanguageCode="fr" but activeBeamSearchTrie is still English
            // - This returns null (language mismatch), causing unconstrained beam search → English words
            // Instead, use a local variable for contraction loading logic.
            // _primaryLanguageCode will be set in loadPrimaryDictionary() AFTER trie is ready.
            val targetLanguage = primaryLanguageCode
            // OPTIMIZATION: Load vocabulary with fast-path sets built during loading
            val t0 = System.currentTimeMillis()
            loadWordFrequencies()
            val t1 = System.currentTimeMillis()
            Log.d(TAG, "⏱️ loadWordFrequencies: " + (t1 - t0) + "ms")

            // Load custom words and user dictionary for beam search
            loadCustomAndUserWords()
            val t2 = System.currentTimeMillis()
            Log.d(TAG, "⏱️ loadCustomAndUserWords: " + (t2 - t1) + "ms")

            // Load disabled words to filter from predictions
            loadDisabledWords()
            val t3 = System.currentTimeMillis()
            Log.d(TAG, "⏱️ loadDisabledWords: " + (t3 - t2) + "ms")

            // OPTIMIZATION v1.32.522: Contractions also cached in binary format
            // Load contraction mappings for apostrophe display (only if not cached)
            if (!contractionsLoadedFromCache) {
                loadContractionMappings()
            } else if (targetLanguage != "en") {
                // v1.1.87: Cache contains English contractions only
                // CRITICAL FIX v1.1.88: Clear English contractions before loading language-specific ones
                // Without this, English contraction keys contaminate the language trie
                val priorCount = nonPairedContractions.size
                nonPairedContractions.clear()
                contractionPairings.clear()
                Log.d(TAG, "Cleared $priorCount English contractions before loading $targetLanguage")
                loadLanguageContractions(targetLanguage)
            }
            val t4 = System.currentTimeMillis()
            Log.d(TAG, "⏱️ loadContractions: " + (t4 - t3) + "ms")

            // Initialize minimum frequency thresholds by word length
            initializeFrequencyThresholds()
            val t5 = System.currentTimeMillis()
            Log.d(TAG, "⏱️ initFrequencyThresholds: " + (t5 - t4) + "ms")

            // OPTIMIZATION v1.32.524: Save binary cache AFTER all components loaded
            // Now includes vocabulary + contractions in V2 format
            if (!contractionsLoadedFromCache) {
                VocabularyCache.saveBinaryCache(
                    context,
                    vocabulary,
                    contractionPairings,
                    nonPairedContractions
                )
            }

            // v1.1.89: Add contraction keys to vocabularyTrie for English mode
            // For non-English primary languages, loadPrimaryDictionary() creates a separate trie
            // But for English, we use vocabularyTrie directly so contractions must be in it
            if (targetLanguage == "en" && nonPairedContractions.isNotEmpty()) {
                val contractionKeys = nonPairedContractions.keys.toList()
                vocabularyTrie.insertAll(contractionKeys)
                Log.d(TAG, "Added ${contractionKeys.size} contraction keys to English vocabularyTrie")
            }

            isLoaded = true

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load vocabulary - NO FALLBACK ALLOWED", e)
            throw RuntimeException("Dictionary loading failed - fallback vocabulary deleted", e)
        }
    }

    /**
     * Filter and rank neural network predictions using vocabulary optimization
     * Implements fast-path lookup and combined scoring from web app
     */
    fun filterPredictions(rawPredictions: List<CandidateWord>, swipeStats: SwipeStats): List<FilteredPrediction> {
        // Always log raw beam outputs for debugging prediction issues
        if (rawPredictions.isNotEmpty()) {
            val debug = StringBuilder("🔥 RAW BEAM (${rawPredictions.size}): ")
            val numToShow = min(8, rawPredictions.size)
            for (i in 0 until numToShow) {
                val c = rawPredictions[i]
                if (i > 0) debug.append(", ")
                debug.append("${c.word}(${"%.2f".format(c.confidence)})")
            }
            Log.d(TAG, debug.toString())
        }
        if (!isLoaded) {
            Log.w(TAG, "Vocabulary not loaded, returning raw predictions")
            return convertToFiltered(rawPredictions)
        }

        // CRITICAL FIX: Use CACHED config values instead of reading SharedPreferences on every swipe
        // These are updated via updateConfig() when settings change (called from NeuralSwipeTypingEngine)
        val debugMode = _debugMode
        val confidenceWeight = _confidenceWeight
        val frequencyWeight = _frequencyWeight
        val commonBoost = _commonBoost
        val top5000Boost = _top5000Boost
        val rarePenalty = _rarePenalty
        val swipeAutocorrectEnabled = _swipeAutocorrectEnabled
        val maxLengthDiff = _maxLengthDiff
        val prefixLength = _prefixLength
        val maxBeamCandidates = _maxBeamCandidates
        val minWordLength = _minWordLength
        val charMatchThreshold = _charMatchThreshold
        val useEditDistance = _useEditDistance

        if (debugMode && rawPredictions.isNotEmpty()) {
            val debug = StringBuilder("\n🔍 VOCABULARY FILTERING DEBUG (top 10 beam search outputs):\n")
            debug.append("─────────────────────────────────────────────────────────\n")
            val numToShow = min(10, rawPredictions.size)
            for (i in 0 until numToShow) {
                val candidate = rawPredictions[i]
                debug.append(String.format("#%d: \"%s\" (NN confidence: %.4f)\n", i + 1, candidate.word, candidate.confidence))
            }
            val debugMsg = debug.toString()
            Log.d(TAG, debugMsg)
            sendDebugLog(debugMsg)
        }

        // Build set of raw predictions for contraction filtering
        // Used to determine which contraction variant to create based on NN output
        // Example: NN predicts "whatd" → only create "what'd" (not what'll, what's, etc.)
        val rawPredictionWords = HashSet<String>()
        for (candidate in rawPredictions) {
            rawPredictionWords.add(candidate.word.toLowerCase(Locale.ROOT).trim())
        }

        val validPredictions = ArrayList<FilteredPrediction>()
        val detailedLog = if (debugMode) StringBuilder("\n📊 DETAILED FILTERING PROCESS:\n") else null
        if (debugMode) detailedLog?.append("─────────────────────────────────────────────────────────\n")

        for (candidate in rawPredictions) {
            val word = candidate.word.toLowerCase(Locale.ROOT).trim()

            // Skip invalid word formats
            if (!word.matches("^[a-z]+$".toRegex())) {
                if (debugMode) detailedLog?.append(String.format("  ❌ \"%s\" - invalid format (not a-z only)\n", word))
                continue
            }

            // v1.32.513: Filter by starting letter accuracy (autocorrect_prefix_length setting)
            // If prefixLength > 0 and we have a firstChar, ensure prediction starts with correct prefix
            if (prefixLength > 0 && swipeStats.firstChar != '\u0000' && word.isNotEmpty()) {
                val expectedFirst = Character.toLowerCase(swipeStats.firstChar)
                val actualFirst = word[0]
                if (actualFirst != expectedFirst) {
                    if (debugMode) detailedLog?.append(String.format("  ❌ \"%s\" - wrong starting letter (expected '%c', got '%c')\n",
                        word, expectedFirst, actualFirst))
                    continue
                }
            }

            // FILTER OUT DISABLED WORDS (Dictionary Manager integration)
            if (disabledWords.contains(word)) {
                if (debugMode) detailedLog?.append(String.format("❌ \"%s\" - DISABLED by user\n", word))
                continue // Skip disabled words from beam search
            }

            // Primary language dictionary lookup (FIRST priority when non-English primary set)
            // When user sets Primary Language to French, check French dictionary FIRST
            // This enables accent recovery: "cafe" → "café", "ecole" → "école"
            var info: WordInfo? = null
            var displayWord = word
            var fromPrimaryDict = false

            if (normalizedIndex != null) {
                // Non-English primary language - check primary dictionary FIRST
                val accentedForm = getPrimaryAccentedForm(word)
                if (accentedForm != null) {
                    // Found in primary dictionary - use accented form
                    displayWord = accentedForm
                    fromPrimaryDict = true
                    // Create WordInfo from primary dictionary frequency rank
                    val matches = normalizedIndex!!.getWordsWithPrefix(word)
                    val exactMatch = matches.find { it.normalized == word }
                    val freq = if (exactMatch != null) {
                        // Convert rank (0-255, 0=most common) to frequency (0-1, 1=most common)
                        1.0f - (exactMatch.bestFrequencyRank / 255.0f)
                    } else {
                        0.5f // Default mid-range frequency
                    }
                    info = WordInfo(freq, 1.toByte()) // tier 1 for primary dict matches
                    if (debugMode) detailedLog?.append(String.format("🌐 \"%s\" → \"%s\" (from primary dictionary)\n", word, accentedForm))
                }
            }

            // Fall back to English vocabulary ONLY if English fallback is enabled
            // When Primary=French, Secondary=None: English fallback is DISABLED
            // When Primary=French, Secondary=English: English fallback is ENABLED
            if (info == null && _englishFallbackEnabled) {
                info = vocabulary[word]
            }

            // v1.1.88: Check for contractions BEFORE filtering out the word
            // This allows "mappelle" → "m'appelle" even when English fallback is disabled
            // Contractions were added to vocabulary in loadContractionsFromInputStream()
            // but vocabulary lookup is guarded by _englishFallbackEnabled
            if (info == null && nonPairedContractions.containsKey(word)) {
                // Word is a contraction key - accept it with mid-range frequency
                info = WordInfo(0.6f, 1.toByte())
                displayWord = nonPairedContractions[word]!!
                if (debugMode) detailedLog?.append(String.format("🔤 \"%s\" → \"%s\" (contraction mapping)\n", word, displayWord))
            }

            if (info == null) {
                if (debugMode) detailedLog?.append(String.format("❌ \"%s\" - NOT IN VOCABULARY (not in main/custom/user/primary dict)\n", word))
                continue // Word not in vocabulary
            }

            // Check if this word should be displayed as a contraction (e.g., "doesnt" -> "doesn't")
            // This happens AFTER vocabulary lookup succeeds - the word is valid, just needs apostrophe
            // Skip contraction mapping for primary dictionary matches (already have accented form)
            // v1.1.88: Also skip if we already set displayWord from contraction check above
            if (!fromPrimaryDict && displayWord == word) {
                displayWord = nonPairedContractions[word] ?: word
            }

            // OPTIMIZATION: Tier is embedded in WordInfo (no additional lookups!)
            // v1.33+: Use configurable boost values instead of hardcoded constants
            val boost: Float
            val source: String

            when (info.tier) {
                2.toByte() -> { // common (top 100)
                    boost = commonBoost  // v1.33+: configurable (default: 1.3)
                    source = "common"
                }
                1.toByte() -> { // top5000
                    boost = top5000Boost  // v1.33+: configurable (default: 1.0)
                    source = "top5000"
                }
                else -> { // regular (tier 0)
                    // Check frequency threshold for rare words
                    val hardcodedMinFreq = getMinFrequency(word.length)
                    
                    // Normalize the user's configured min frequency
                    val configMinFreqValue = _autocorrect_confidence_min_frequency
                    // Use a slightly different scale for Config frequency to avoid 0.0 for values like 100
                    val configNormalizedMinFreq = max(0.0f, configMinFreqValue.toFloat() / 10000.0f) // Scale 0-10000 -> 0-1.0
                    
                    // Take the maximum of the hardcoded baseline and the user's configured min frequency
                    // This ensures the word passes both (hardcoded baseline is still important for very rare words)
                    val effectiveMinFreq = max(hardcodedMinFreq, configNormalizedMinFreq)

                    if (info.frequency < effectiveMinFreq) {
                        if (debugMode) detailedLog?.append(String.format("❌ \"%s\" - BELOW FREQUENCY THRESHOLD (freq=%.4f < effective_min=%.4f (hardcoded=%.4f, config=%.4f) for length %d)\n",
                            word, info.frequency, effectiveMinFreq, hardcodedMinFreq, configNormalizedMinFreq, word.length))
                        continue // Below threshold
                    }
                    boost = rarePenalty
                    source = "vocabulary"
                }
            }

            // v1.33+: Pass configurable weights to scoring function
            // Note: Length normalization is now handled in BeamSearchEngine.convertToCandidate()
            // using the GNMT formula with lengthPenaltyAlpha parameter
            // Apply neural_frequency_weight as a multiplier (0.0 = NN only, 1.0 = normal, 2.0 = heavy freq)
            val effectiveFrequencyWeight = frequencyWeight * _neuralFrequencyWeight
            val score = VocabularyUtils.calculateCombinedScore(candidate.confidence, info.frequency, boost, confidenceWeight, effectiveFrequencyWeight)

            // Use displayWord for contractions (e.g., "doesn't" instead of "doesnt")
            // For primary dictionary matches, label as "primary"
            val sourceLabel = when {
                fromPrimaryDict -> "primary"
                displayWord != word -> "$source-contraction"
                else -> source
            }
            validPredictions.add(FilteredPrediction(displayWord, score, candidate.confidence, info.frequency, sourceLabel))

            // DEBUG: Show successful candidates with all scoring details
            if (debugMode) {
                val displayInfo = if (displayWord != word) " [mapped from \"$word\"]" else ""
                detailedLog?.append(String.format("✅ \"%s\"%s - KEPT (tier=%d, freq=%.4f, boost=%.2fx, NN=%.4f → score=%.4f) [%s]\n",
                    displayWord, displayInfo, info.tier, info.frequency, boost, candidate.confidence, score, sourceLabel))
            }
        }

        if (debugMode && detailedLog != null) {
            detailedLog.append("─────────────────────────────────────────────────────────\n")
            val detailedMsg = detailedLog.toString()
            Log.d(TAG, detailedMsg)
            sendDebugLog(detailedMsg)
        }

        // Sort by combined score (confidence + frequency)
        validPredictions.sortWith { a, b -> b.score.compareTo(a.score) }

        // AUTOCORRECT FOR SWIPE: Fuzzy match top beam candidates against custom words
        // This allows "parametrek" (custom) to match "parameters" (beam output)
        // v1.33+: OPTIMIZED - uses pre-loaded config from top of method (no redundant prefs reads)
        // v1.33.1: CRITICAL FIX - removed isEmpty check and match against raw beam outputs
        if (swipeAutocorrectEnabled && rawPredictions.isNotEmpty()) {
            try {
                // OPTIMIZATION Phase 2: Use cached custom words instead of reading SharedPreferences
                if (_cachedCustomWords.isNotEmpty()) {
                    // For each custom word, check if it fuzzy matches any top beam candidate
                    for ((customWord, customFreq) in _cachedCustomWords) {
                        // Check top N RAW beam candidates for fuzzy match (v1.33.1: CRITICAL FIX - was using validPredictions)
                        // This allows autocorrect to work even when ALL beam outputs are rejected by vocabulary filtering
                        for (i in 0 until min(maxBeamCandidates, rawPredictions.size)) {
                            val beamWord = rawPredictions[i].word

                            // v1.33+: Configurable fuzzy matching (uses pre-loaded params)
                            if (VocabularyUtils.fuzzyMatch(customWord, beamWord, charMatchThreshold, maxLengthDiff, prefixLength, minWordLength)) {
                                // Add custom word as autocorrect suggestion
                                val normalizedFreq = max(0.0f, (customFreq - 1).toFloat() / 9999.0f)
                                val tier = if (customFreq >= 8000) 2.toByte() else 1.toByte()
                                // v1.33+: Use configurable boost values
                                val boost = if (tier == 2.toByte()) commonBoost else top5000Boost

                                // Use RAW beam candidate's confidence for scoring (v1.33.1: CRITICAL FIX - was using validPredictions)
                                val confidence = rawPredictions[i].confidence

                                // v1.33.3: MULTIPLICATIVE SCORING - match quality dominates
                                // Custom words: base_score = NN_confidence (ignore frequency)
                                // final_score = base_score × (match_quality^3) × tier_boost
                                // Note: Length normalization handled in BeamSearchEngine
                                val matchQuality = VocabularyUtils.calculateMatchQuality(customWord, beamWord, useEditDistance)
                                val matchPower = matchQuality * matchQuality * matchQuality // Cubic
                                val baseScore = confidence  // Ignore frequency for custom words
                                val score = baseScore * matchPower * boost

                                validPredictions.add(FilteredPrediction(customWord, score, confidence, normalizedFreq, "autocorrect"))

                                if (debugMode) {
                                    val matchMsg = String.format("🔄 AUTOCORRECT: \"%s\" (custom) matches \"%s\" (beam) → added with score=%.4f\n",
                                        customWord, beamWord, score)
                                    Log.d(TAG, matchMsg)
                                    sendDebugLog(matchMsg)
                                }
                                break // Only match once per custom word
                            }
                        }
                    }

                    // Re-sort after adding autocorrect suggestions
                    validPredictions.sortWith { a, b -> b.score.compareTo(a.score) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply autocorrect to beam candidates", e)
            }
        }

        // MAIN DICTIONARY FUZZY MATCHING: Match rejected beam outputs against dictionary words
        // v1.33.1: NEW - allows "proxity" (beam) to match "proximity" (dict)
        // Only run if autocorrect is enabled and we have few/no valid predictions
        // v1.1.89 FIX: Skip English vocabulary fuzzy matching when primary is non-English
        // The vocabularyByLength buckets contain English words which would contaminate French-only results
        val shouldFuzzyMatch = swipeAutocorrectEnabled &&
            validPredictions.size < 3 &&
            rawPredictions.isNotEmpty() &&
            (_primaryLanguageCode == "en" || _englishFallbackEnabled)

        if (shouldFuzzyMatch) {
            try {
                if (debugMode) {
                    val fuzzyMsg = String.format("\n🔍 MAIN DICTIONARY FUZZY MATCHING (validPredictions=%d, trying to rescue rejected beam outputs):\n", validPredictions.size)
                    Log.d(TAG, fuzzyMsg)
                    sendDebugLog(fuzzyMsg)
                }

                // Check top beam candidates that were rejected by vocabulary filtering
                for (i in 0 until min(maxBeamCandidates, rawPredictions.size)) {
                    val beamWord = rawPredictions[i].word.toLowerCase(Locale.ROOT).trim()
                    val beamConfidence = rawPredictions[i].confidence

                    // Skip if this beam word already passed vocabulary filtering
                    if (vocabulary.containsKey(beamWord)) {
                        continue // Already in validPredictions
                    }

                    // OPTIMIZATION Phase 2: Use length-based buckets instead of iterating entire vocabulary
                    // This reduces iteration from 50k+ words to ~2k words (only similar lengths)
                    // v1.33.2: CRITICAL FIX - find BEST match (highest score), not FIRST match
                    val targetLength = beamWord.length
                    var bestMatch: String? = null
                    var bestScore = 0.0f
                    var bestFrequency = 0.0f
                    var bestSource: String? = null

                    // Iterate only through length buckets within maxLengthDiff range
                    val minLengthBucket = max(1, targetLength - maxLengthDiff)
                    val maxLengthBucket = targetLength + maxLengthDiff

                    for (len in minLengthBucket..maxLengthBucket) {
                        val bucket = vocabularyByLength[len]
                        if (bucket == null) continue // No words of this length

                        for (dictWord in bucket) {
                            val info = vocabulary[dictWord]
                            if (info == null) continue // Shouldn't happen

                            // Skip disabled words
                            if (disabledWords.contains(dictWord)) {
                                continue
                            }

                            // Try fuzzy matching
                            if (VocabularyUtils.fuzzyMatch(dictWord, beamWord, charMatchThreshold, maxLengthDiff, prefixLength, minWordLength)) {
                                // Determine tier boost for matched word
                                val boost: Float
                                val source: String
                                when (info.tier) {
                                    2.toByte() -> {
                                        boost = commonBoost
                                        source = "dict-fuzzy-common"
                                    }
                                    1.toByte() -> {
                                        boost = top5000Boost
                                        source = "dict-fuzzy-top5k"
                                    }
                                    else -> {
                                        boost = rarePenalty
                                        source = "dict-fuzzy"
                                    }
                                }

                                // v1.33.3: MULTIPLICATIVE SCORING - match quality dominates
                                // Dict fuzzy: Use configured weights but penalize rescue (0.8x) to prefer direct matches
                                // Note: Length normalization handled in BeamSearchEngine
                                // Apply neural_frequency_weight as a multiplier (same as main scoring)
                                val matchQuality = VocabularyUtils.calculateMatchQuality(dictWord, beamWord, useEditDistance)
                                val matchPower = matchQuality * matchQuality * matchQuality // Cubic
                                val effectiveFuzzyFreqWeight = frequencyWeight * _neuralFrequencyWeight

                                var baseScore = (confidenceWeight * beamConfidence) + (effectiveFuzzyFreqWeight * info.frequency)
                                baseScore *= 0.8f // Penalty for not being a direct beam match

                                val score = baseScore * matchPower * boost

                                // Keep track of best match (v1.33.2: don't break on first match!)
                                if (score > bestScore) {
                                    bestScore = score
                                    bestMatch = dictWord
                                    bestFrequency = info.frequency
                                    bestSource = source
                                }
                            }
                        } // End for dictWord in bucket
                    } // End for len in length range

                    // Add the best match found for this beam word (if any)
                    if (bestMatch != null) {
                        // RE-APPLY STARTING LETTER ACCURACY CHECK (CRITICAL FIX)
                        if (prefixLength > 0 && swipeStats.firstChar != '\u0000' && bestMatch.isNotEmpty()) {
                            val expectedFirst = Character.toLowerCase(swipeStats.firstChar)
                            val actualFirst = bestMatch[0]
                            if (actualFirst != expectedFirst) {
                                if (debugMode) {
                                    val matchMsg = String.format("❌ DICT FUZZY REJECTED: \"%s\" (dict) for \"%s\" (beam #%d, NN=%.4f) - wrong starting letter (expected '%c', got '%c')\n",
                                        bestMatch, beamWord, i + 1, beamConfidence, expectedFirst, actualFirst)
                                    Log.d(TAG, matchMsg)
                                    sendDebugLog(matchMsg)
                                }
                                bestMatch = null // Mark as invalid
                            }
                        }

                        if (bestMatch != null) { // Only add if still valid after re-check
                            validPredictions.add(FilteredPrediction(bestMatch, bestScore, beamConfidence, bestFrequency, bestSource!!))

                            if (debugMode) {
                                val matchMsg = String.format("🔄 DICT FUZZY: \"%s\" (dict) matches \"%s\" (beam #%d, NN=%.4f) → added with score=%.4f\n",
                                    bestMatch, beamWord, i + 1, beamConfidence, bestScore)
                                Log.d(TAG, matchMsg)
                                sendDebugLog(matchMsg)
                            }
                        }
                    }
                }

                // Re-sort after adding fuzzy matches
                if (validPredictions.isNotEmpty()) {
                    validPredictions.sortWith { a, b -> b.score.compareTo(a.score) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply dictionary fuzzy matching", e)
            }
        }

        // CONTRACTION HANDLING: Add paired variants and modify non-paired contractions
        if (contractionPairings.isNotEmpty() || nonPairedContractions.isNotEmpty()) {
            try {
                val contractionVariants = ArrayList<FilteredPrediction>()

                // Process each prediction for contractions
                for (i in 0 until validPredictions.size) {
                    val pred = validPredictions[i]
                    val word = pred.word

                    // Check for paired contractions (base word exists: "well" -> "we'll")
                    // Filter by raw NN predictions to show only relevant contractions
                    // Example: NN predicted "whatd" → only create "what'd" (not what'll, what's, etc.)
                    if (contractionPairings.containsKey(word)) {
                        val contractions = contractionPairings[word]!!

                        for (contraction in contractions) {
                            // Get apostrophe-free form of this contraction (what'd → whatd)
                            val apostropheFree = contraction.replace("'", "").toLowerCase(Locale.ROOT)

                            // Only create this contraction variant if NN predicted the apostrophe-free form
                            // Example: only create "what'd" if raw predictions contain "whatd"
                            if (!rawPredictionWords.contains(apostropheFree)) {
                                // Skip this contraction - NN didn't predict this variant
                                if (debugMode) {
                                    val msg = String.format("📝 CONTRACTION FILTERED: \"%s\" → skipped \"%s\" (NN didn't predict \"%s\")\n",
                                        word, contraction, apostropheFree)
                                    Log.d(TAG, msg)
                                    sendDebugLog(msg)
                                }
                                continue
                            }

                            // Add contraction variant with slightly lower score (0.95x)
                            // This ensures base word appears first, followed by contraction
                            // CRITICAL: word = contraction (for insertion), displayText = contraction (for UI)
                            // Both must be the contraction so tapping "we'll" inserts "we'll" not "well"
                            val variantScore = pred.score * 0.95f
                            contractionVariants.add(
                                FilteredPrediction(
                                    contraction,             // word for insertion (with apostrophe: "we'll")
                                    contraction,             // displayText for UI (with apostrophe: "we'll")
                                    variantScore,
                                    pred.confidence,
                                    pred.frequency,
                                    pred.source + "-contraction"
                                )
                            )

                            if (debugMode) {
                                val msg = String.format("📝 CONTRACTION PAIRING: \"%s\" → added variant \"%s\" (NN predicted \"%s\")\n",
                                    word, contraction, apostropheFree)
                                Log.d(TAG, msg)
                                sendDebugLog(msg)
                            }
                        }
                    }

                    // Check for non-paired contractions (apostrophe-free form -> contraction)
                    // REPLACE the apostrophe-free form with the contraction
                    // Example: "cant" (not a real word) → "can't" (the actual word)
                    // Note: Valid words like "well", "were", "id" are NOT in nonPairedContractions
                    if (nonPairedContractions.containsKey(word)) {
                        val contraction = nonPairedContractions[word]!!

                        // REPLACE the current prediction with the contraction (same score)
                        // This prevents invalid forms like "cant", "dont" from appearing
                        validPredictions[i] = FilteredPrediction(
                            contraction,             // word for insertion (with apostrophe: "can't")
                            contraction,             // displayText for UI (with apostrophe: "can't")
                            pred.score,              // Keep same score (not a variant, a replacement)
                            pred.confidence,
                            pred.frequency,
                            pred.source + "-contraction"
                        )

                        if (debugMode) {
                            val msg = String.format("📝 NON-PAIRED CONTRACTION: \"%s\" → REPLACED with \"%s\" (score=%.4f)\n",
                                word, contraction, pred.score)
                            Log.d(TAG, msg)
                            sendDebugLog(msg)
                        }
                    }
                }

                // Add all contraction variants
                if (contractionVariants.isNotEmpty()) {
                    validPredictions.addAll(contractionVariants)
                    // Re-sort after adding variants
                    validPredictions.sortWith { a, b -> b.score.compareTo(a.score) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply contraction modifications", e)
            }
        }

        // SECONDARY DICTIONARY: Add matches from secondary language dictionary
        // This enables bilingual predictions (e.g., English + Spanish)
        val secondaryIndex = secondaryNormalizedIndex
        if (secondaryIndex != null && rawPredictions.isNotEmpty()) {
            try {
                for (candidate in rawPredictions) {
                    val word = candidate.word.toLowerCase(Locale.ROOT).trim()

                    // Skip if invalid format (NN outputs 26-letter only)
                    if (!word.matches("^[a-z]+$".toRegex())) continue

                    // Look up in secondary dictionary (accent-aware)
                    val normalized = AccentNormalizer.normalize(word)
                    val results = secondaryIndex.getWordsWithPrefix(normalized).filter {
                        it.normalized == normalized // Exact match only
                    }

                    for (result in results) {
                        // Check if this canonical form is already in predictions
                        val alreadyPresent = validPredictions.any {
                            AccentNormalizer.normalize(it.word) == result.normalized
                        }
                        if (alreadyPresent) continue

                        // Score: NN confidence * frequency rank score * language multiplier
                        // Language multiplier is dynamic based on detected language:
                        // - 0.9 (penalty) when primary language dominant
                        // - 1.0 (neutral) when balanced
                        // - 1.1+ (boost) when secondary language detected
                        val rankScore = 1.0f - (result.bestFrequencyRank / 255f)
                        val langMultiplier = _currentLanguageMultiplier
                        val score = candidate.confidence * 0.6f + rankScore * 0.3f * langMultiplier

                        validPredictions.add(
                            FilteredPrediction(
                                result.bestCanonical,  // Accented form (e.g., "español")
                                score,
                                candidate.confidence,
                                rankScore,
                                "secondary"
                            )
                        )

                        if (debugMode) {
                            val msg = String.format("🌍 SECONDARY: \"%s\" → \"%s\" (score=%.4f)\n",
                                word, result.bestCanonical, score)
                            Log.d(TAG, msg)
                            sendDebugLog(msg)
                        }
                    }
                }

                // Re-sort after adding secondary matches
                if (validPredictions.isNotEmpty()) {
                    validPredictions.sortWith { a, b -> b.score.compareTo(a.score) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply secondary dictionary lookup", e)
            }
        }

        // DEBUG: Show final ranking
        if (debugMode && validPredictions.isNotEmpty()) {
            val ranking = StringBuilder("\n🏆 FINAL RANKING (after combining NN + frequency):\n")
            ranking.append("─────────────────────────────────────────────────────────\n")
            val numToShow = min(10, validPredictions.size)
            for (i in 0 until numToShow) {
                val pred = validPredictions[i]
                val displayInfo = if (pred.word == pred.displayText) "" else " (display=\"" + pred.displayText + "\")"
                ranking.append(String.format("#%d: \"%s\"%s (score=%.4f, NN=%.4f, freq=%.4f) [%s]\n",
                    i + 1, pred.word, displayInfo, pred.score, pred.confidence, pred.frequency, pred.source))
            }
            ranking.append("─────────────────────────────────────────────────────────\n")
            val rankingMsg = ranking.toString()
            Log.d(TAG, rankingMsg)
            sendDebugLog(rankingMsg)
        }

        // Apply swipe-specific filtering if needed
        return if (swipeStats.expectedLength > 0) {
            filterByExpectedLength(validPredictions, swipeStats.expectedLength)
        } else {
            validPredictions.subList(0, min(validPredictions.size, 10))
        }
    }

    /**
     * Load word frequencies from dictionary files
     * OPTIMIZATION: Single-lookup structure with tier embedded (1 lookup instead of 3)
     */
    private fun loadWordFrequencies() {
        // OPTIMIZATION v1.32.520: Try pre-processed binary cache first (100x faster!)
        // Binary format avoids JSON parsing and sorting overhead
        if (VocabularyCache.tryLoadBinaryCache(
                context,
                vocabulary,
                vocabularyTrie,
                vocabularyByLength,
                contractionPairings,
                nonPairedContractions
            )) {
            contractionsLoadedFromCache = true // Set the flag in OptimizedVocabulary
            return
        }

        // Fall back to JSON format with on-demand cache generation
        try {
            val inputStream = context.assets.open("dictionaries/en_enhanced.json")
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonBuilder = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                jsonBuilder.append(line)
            }
            reader.close()

            // Parse JSON object
            val jsonDict = org.json.JSONObject(jsonBuilder.toString())
            val keys = jsonDict.keys()
            var wordCount = 0

            // First pass: collect all words with frequencies to determine tiers
            val wordFreqList = ArrayList<MutableMap.MutableEntry<String, Int>>()
            while (keys.hasNext()) {
                val word = keys.next().toLowerCase(Locale.ROOT)
                if (word.matches("^[a-z]+$".toRegex())) {
                    val freq = jsonDict.getInt(word)
                    wordFreqList.add(AbstractMap.SimpleEntry(word, freq))
                }
            }

            // Sort by frequency descending (highest frequency first)
            // BOTTLENECK: O(n log n) sort of 50k items takes ~500ms on ARM devices
            wordFreqList.sortWith { a, b -> b.value.compareTo(a.value) }

            // Second pass: assign tiers based on sorted position
            for (i in 0 until min(wordFreqList.size, 150000)) {
                val entry = wordFreqList[i]
                val word = entry.key
                val rawFreq = entry.value

                // Normalize frequency from 128-255 range to 0-1 range
                val frequency = (rawFreq - 128).toFloat() / 127.0f

                // Determine tier based on sorted position
                // Tightened thresholds for 50k vocabulary (was top 5000, now top 3000)
                val tier: Byte
                if (i < 100) {
                    tier = 2 // common (top 100)
                } else if (i < 3000) {
                    tier = 1 // top3000 (6% of 50k vocab)
                } else {
                    tier = 0 // regular
                }

                vocabulary[word] = WordInfo(frequency, tier)
                vocabularyTrie.insert(word) // OPTIMIZATION Phase 2: Build trie during vocab load

                // OPTIMIZATION Phase 2: Add to length-based buckets for fuzzy matching
                val wordLength = word.length
                vocabularyByLength.getOrPut(wordLength) { ArrayList() }.add(word)

                wordCount++
            }

            Log.d(TAG, "Loaded JSON vocabulary: $wordCount words with frequency tiers")
            vocabularyTrie.logStats() // Log trie statistics

            // DO NOT save cache here - contractions haven't been loaded yet!
            // Cache will be saved after loadVocabulary() completes
        } catch (e: Exception) {
            Log.w(TAG, "JSON vocabulary not found, falling back to text format: " + e.message)

            // Fall back to text format (position-based frequency)
            try {
                val inputStream = context.assets.open("dictionaries/en.txt")
                val reader = BufferedReader(InputStreamReader(inputStream))

                var line: String?
                var wordCount = 0
                while (reader.readLine().also { line = it } != null) {
                    line = line!!.trim().toLowerCase(Locale.ROOT)
                    if (line!!.isNotEmpty() && line!!.matches("^[a-z]+$".toRegex())) {
                        // Position-based frequency
                        val frequency = 1.0f / (wordCount + 1.0f)

                        // Determine tier based on position
                        val tier: Byte
                        if (wordCount < 100) {
                            tier = 2 // common
                        } else if (wordCount < 5000) {
                            tier = 1 // top5000
                        } else {
                            tier = 0 // regular
                        }

                        vocabulary[line!!] = WordInfo(frequency, tier)
                        wordCount++

                        if (wordCount >= 150000) break
                    }
                }

                reader.close()
                Log.d(TAG, "Loaded text vocabulary: $wordCount words")
            } catch (e2: IOException) {
                Log.e(TAG, "Failed to load word frequencies", e2)
                throw RuntimeException("Could not load vocabulary", e2)
            }
        }
    }

    /**
     * Initialize minimum frequency thresholds by word length
     */
    private fun initializeFrequencyThresholds() {
        // Longer words can have lower frequency thresholds
        minFrequencyByLength[1] = 1e-4f
        minFrequencyByLength[2] = 1e-5f
        minFrequencyByLength[3] = 1e-6f
        minFrequencyByLength[4] = 1e-6f
        minFrequencyByLength[5] = 1e-7f
        minFrequencyByLength[6] = 1e-7f
        minFrequencyByLength[7] = 1e-8f
        minFrequencyByLength[8] = 1e-8f
        // 9+ words
        for (i in 9..20) {
            minFrequencyByLength[i] = 1e-9f
        }
    }

    private fun getMinFrequency(length: Int): Float {
        return minFrequencyByLength.getOrDefault(length, 1e-9f)
    }
    
    /**
     * Filter predictions by expected word length with tolerance
     */
    private fun filterByExpectedLength(predictions: List<FilteredPrediction>, expectedLength: Int): List<FilteredPrediction> {
        val tolerance = 2 // Allow ±2 characters
        
        val filtered = ArrayList<FilteredPrediction>()
        for (pred in predictions) {
            val lengthDiff = abs(pred.word.length - expectedLength)
            if (lengthDiff <= tolerance) {
                filtered.add(pred)
            }
        }
        
        return if (filtered.isNotEmpty()) filtered else predictions.subList(0, min(predictions.size, 5))
    }
    
    /**
     * Convert raw predictions to filtered format
     */
    private fun convertToFiltered(rawPredictions: List<CandidateWord>): List<FilteredPrediction> {
        val result = ArrayList<FilteredPrediction>()
        for (candidate in rawPredictions) {
            result.add(FilteredPrediction(candidate.word, candidate.confidence, 
                candidate.confidence, 0.0f, "raw"))
        }
        return result
    }
    
    /**
     * Check if vocabulary is loaded
     */
    fun isLoaded(): Boolean {
        return isLoaded
    }

    /**
     * Check if a word is a contraction key (apostrophe-free form that maps to contraction).
     * Used by autocorrect to skip correction for words like "cest" → "c'est".
     *
     * @param word The word to check (should be lowercase)
     * @return true if word is a contraction key, false otherwise
     */
    fun isContractionKey(word: String): Boolean {
        return nonPairedContractions.containsKey(word.lowercase())
    }

    /**
     * Get the contraction form for a given key.
     * Example: "cest" → "c'est", "jai" → "j'ai"
     *
     * @param word The apostrophe-free form
     * @return The contraction with apostrophe, or null if not found
     */
    fun getContraction(word: String): String? {
        return nonPairedContractions[word.lowercase()]
    }

    /**
     * Reload custom words, user dictionary, and disabled words without reloading main vocabulary
     * Called when Dictionary Manager makes changes
     * PERFORMANCE: Only reloads small dynamic sets, not the 10k main dictionary
     */
    fun reloadCustomAndDisabledWords() {
        if (!isLoaded) return

        // Clear old custom/user/disabled data
        disabledWords.clear()

        try {
            // Reload custom and user words (overwrites old entries)
            loadCustomAndUserWords()

            // Reload disabled words filter
            loadDisabledWords()

            Log.d(TAG, "Reloaded custom/user/disabled words (vocabulary size: " + vocabulary.size + ")")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reload custom/user/disabled words", e)
        }
    }
    
    /**
     * Get vocabulary statistics
     */
    fun getStats(): VocabularyStats {
        // Count by tier from unified structure
        var common = 0
        var top5k = 0
        for (info in vocabulary.values) {
            if (info.tier == 2.toByte()) common++
            else if (info.tier == 1.toByte()) top5k++
        }

        return VocabularyStats(
            vocabulary.size,
            common,
            top5k,
            isLoaded
        )
    }

    // ==================== MULTILANGUAGE SUPPORT ====================

    /**
     * Load a primary language dictionary (V2 binary format) for accent-aware lookups.
     *
     * The primary dictionary enables typing in non-English QWERTY languages:
     * - NN outputs 26-letter predictions (e.g., "cafe")
     * - NormalizedPrefixIndex maps to canonical forms (e.g., "café")
     * - Works for any QWERTY-compatible language (French, Spanish, Portuguese, etc.)
     *
     * Note: For English, this is not needed since the vocabulary is already English.
     *
     * @param language Language code (e.g., "fr", "de", "pt")
     * @return true if loaded successfully
     */
    fun loadPrimaryDictionary(language: String): Boolean {
        Log.i(TAG, "🔄 loadPrimaryDictionary() called with language='$language'")
        Log.i(TAG, "   Before: activeBeamSearchTrie === vocabularyTrie? ${activeBeamSearchTrie === vocabularyTrie}")

        // English doesn't need accent normalization for primary
        if (language == "en") {
            normalizedIndex = null
            Log.i(TAG, "Primary language is English - no accent normalization needed")
            return true
        }

        val index = NormalizedPrefixIndex()
        var loaded = false

        // First try loading from installed language packs
        try {
            val packManager = tribixbite.cleverkeys.langpack.LanguagePackManager.getInstance(context)
            val dictFile = packManager.getDictionaryPath(language)
            if (dictFile != null) {
                loaded = BinaryDictionaryLoader.loadIntoNormalizedIndexFromFile(dictFile, index)
                if (loaded) {
                    Log.i(TAG, "Loaded primary dictionary from language pack: $language")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load from language pack: $language", e)
        }

        // Fall back to bundled dictionary in assets
        if (!loaded) {
            val filename = "dictionaries/${language}_enhanced.bin"
            loaded = BinaryDictionaryLoader.loadIntoNormalizedIndex(context, filename, index)
            if (loaded) {
                Log.i(TAG, "Loaded primary dictionary from assets: $language")
            }
        }

        if (loaded) {
            normalizedIndex = index

            // Build language-specific trie from normalized words
            // This trie is used by beam search instead of the English trie
            // NN outputs 26 English letters → beam search constrains to valid normalized words
            // → post-processing converts normalized to accented (e.g., "etre" → "être")
            val normalizedWords = index.getAllNormalizedWords()
            val languageTrie = VocabularyTrie()
            languageTrie.insertAll(normalizedWords)

            // v1.1.88 CRITICAL FIX: Clear English contractions and reload for target language
            // Without this, English contraction keys like "dont", "cant", "wed", "hell" etc.
            // contaminate the language trie and allow English words in beam search
            val priorContractionCount = nonPairedContractions.size
            nonPairedContractions.clear()
            contractionPairings.clear()
            Log.i(TAG, "Cleared $priorContractionCount cached contractions before loading $language")

            // Load primary language contractions (e.g., French "mappelle" → "m'appelle")
            loadLanguageContractions(language)
            Log.i(TAG, "Loaded ${nonPairedContractions.size} contractions for $language")

            // DUAL LANGUAGE: If secondary language is set, also load its contractions
            // This allows typing secondary language contractions while in dual mode
            if (_secondaryLanguageCode != "none" && _secondaryLanguageCode != language) {
                val beforeSecondary = nonPairedContractions.size
                loadLanguageContractions(_secondaryLanguageCode)
                Log.i(TAG, "Dual mode: added ${nonPairedContractions.size - beforeSecondary} $_secondaryLanguageCode contractions")
            }

            // Add contraction keys to the new trie (primary + optional secondary)
            val contractionKeys = nonPairedContractions.keys
            if (contractionKeys.isNotEmpty()) {
                languageTrie.insertAll(contractionKeys)
                Log.i(TAG, "Added ${contractionKeys.size} contraction keys to language trie")
            }

            // v1.1.94 CRITICAL FIX: Set _primaryLanguageCode BEFORE activeBeamSearchTrie
            // Due to Java Memory Model volatile ordering:
            // - If reader sees French trie, they're guaranteed to see "fr" (write #1 happens-before write #2)
            // - This ensures filterPredictions() sees correct language when checking shouldFuzzyMatch
            // Previous fix (v1.1.93) had writes in wrong order - reader could see French trie
            // but stale "en" for _primaryLanguageCode, causing English fuzzy matching to run
            _primaryLanguageCode = language
            activeBeamSearchTrie = languageTrie

            Log.i(TAG, "✅ Primary dictionary loaded: $language")
            Log.i(TAG, "  - ${index.size()} canonical words (with accents)")
            Log.i(TAG, "  - ${normalizedWords.size} normalized words (beam search trie)")
            Log.i(TAG, "   After: activeBeamSearchTrie === vocabularyTrie? ${activeBeamSearchTrie === vocabularyTrie}")
            Log.i(TAG, "   After: activeBeamSearchTrie.words=${activeBeamSearchTrie.getStats().first}, vocabularyTrie.words=${vocabularyTrie.getStats().first}")

            // v1.1.89 DIAGNOSTIC: Verify language trie doesn't contain English words
            val englishTestWords = listOf("the", "brother", "open", "still", "that", "this", "what", "with", "have", "get", "feeling")
            val foundInNewTrie = englishTestWords.filter { languageTrie.containsWord(it) }
            if (foundInNewTrie.isNotEmpty()) {
                Log.e(TAG, "🚨 TRIE BUILT WITH ENGLISH: New $language trie contains: $foundInNewTrie")
            } else {
                Log.i(TAG, "✅ New $language trie clean - no English test words found")
            }

            return true
        } else {
            Log.w(TAG, "Failed to load primary dictionary: $language")
            return false
        }
    }

    /**
     * Unload the primary dictionary and reset to English trie.
     */
    fun unloadPrimaryDictionary() {
        normalizedIndex = null
        activeBeamSearchTrie = vocabularyTrie  // Reset to English trie
        // v1.1.93: Reset _primaryLanguageCode when unloading (must be after activeBeamSearchTrie reset)
        _primaryLanguageCode = "en"
        Log.i(TAG, "Unloaded primary dictionary, reset to English beam search trie")
    }

    /**
     * Check if a primary dictionary is loaded.
     */
    fun hasPrimaryDictionary(): Boolean {
        return normalizedIndex != null
    }

    /**
     * Get the accented form of a word from the primary dictionary.
     * Used to convert NN output (26-letter) to proper accented form.
     *
     * @param normalized The 26-letter normalized word (e.g., "cafe")
     * @return The accented canonical form (e.g., "café"), or null if not found
     */
    fun getPrimaryAccentedForm(normalized: String): String? {
        val index = normalizedIndex ?: return null
        val results = index.getWordsWithPrefix(normalized)
        // Return exact match if exists (compare normalized forms)
        val exactMatch = results.find { it.normalized == normalized }
        return exactMatch?.bestCanonical
    }

    /**
     * Load a secondary language dictionary (V2 binary format) for accent-aware lookups.
     *
     * The secondary dictionary enables bilingual typing without manual language switching:
     * - NN outputs 26-letter predictions (e.g., "espanol")
     * - NormalizedPrefixIndex maps to canonical forms (e.g., "español")
     * - SuggestionRanker merges results from both dictionaries
     *
     * @param language Language code (e.g., "es", "fr", "de")
     * @return true if loaded successfully
     */
    fun loadSecondaryDictionary(language: String): Boolean {
        val index = NormalizedPrefixIndex()
        var loaded = false

        // First try loading from installed language packs
        try {
            val packManager = tribixbite.cleverkeys.langpack.LanguagePackManager.getInstance(context)
            val dictFile = packManager.getDictionaryPath(language)
            if (dictFile != null) {
                loaded = BinaryDictionaryLoader.loadIntoNormalizedIndexFromFile(dictFile, index)
                if (loaded) {
                    Log.i(TAG, "Loaded secondary dictionary from language pack: $language")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load from language pack: $language", e)
        }

        // Fall back to bundled dictionary in assets
        if (!loaded) {
            val filename = "dictionaries/${language}_enhanced.bin"
            loaded = BinaryDictionaryLoader.loadIntoNormalizedIndex(context, filename, index)
            if (loaded) {
                Log.i(TAG, "Loaded secondary dictionary from assets: $language")
            }
        }

        if (loaded) {
            // v1.1.94: Also load custom words for secondary language
            val customWordsAdded = loadSecondaryCustomWords(index, language)

            secondaryNormalizedIndex = index
            _secondaryLanguageCode = language
            Log.i(TAG, "Secondary dictionary loaded: $language (${index.size()} normalized forms, +$customWordsAdded custom)")
            return true
        } else {
            Log.w(TAG, "Failed to load secondary dictionary: $language")
            return false
        }
    }

    /**
     * v1.1.94: Load custom words for secondary language into NormalizedPrefixIndex.
     *
     * @param index The NormalizedPrefixIndex to add words to
     * @param language Language code for custom words key
     * @return Number of custom words added
     */
    private fun loadSecondaryCustomWords(index: NormalizedPrefixIndex, language: String): Int {
        var count = 0
        try {
            val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
            val customWordsKey = LanguagePreferenceKeys.customWordsKey(language)
            val customWordsJson = prefs.getString(customWordsKey, "{}") ?: "{}"

            if (customWordsJson != "{}") {
                val jsonObj = org.json.JSONObject(customWordsJson)
                val keys = jsonObj.keys()

                while (keys.hasNext()) {
                    val word = keys.next()
                    val frequency = jsonObj.optInt(word, 1000)
                    // Convert frequency to rank (0-255): higher frequency = lower rank
                    val rank = kotlin.math.max(0, kotlin.math.min(255, 255 - (frequency / 4000)))
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
     * Unload the secondary dictionary to free memory.
     */
    fun unloadSecondaryDictionary() {
        secondaryNormalizedIndex = null
        _secondaryLanguageCode = "none"
        Log.i(TAG, "Unloaded secondary dictionary")
    }

    /**
     * Check if a secondary dictionary is loaded.
     */
    fun hasSecondaryDictionary(): Boolean {
        return secondaryNormalizedIndex != null
    }

    /**
     * Update the language detection multiplier based on detected language scores.
     * Called by SwipePredictorOrchestrator after each word is committed.
     *
     * When auto-switch is enabled:
     * - If secondary language score > threshold: boost secondary dictionary (multiplier > 1.0)
     * - If primary language dominant: penalize secondary dictionary (multiplier < 1.0)
     * - Otherwise: neutral (multiplier = 1.0)
     *
     * @param languageScores Map of language code → confidence (0.0-1.0)
     */
    fun updateLanguageMultiplier(languageScores: Map<String, Float>) {
        if (!_autoSwitchEnabled || _secondaryLanguageCode == "none") {
            _currentLanguageMultiplier = _secondaryPredictionWeight // v1.1.94: Use config value
            return
        }

        val secondaryScore = languageScores[_secondaryLanguageCode] ?: 0f
        val primaryScore = languageScores["en"] ?: 0f

        _currentLanguageMultiplier = when {
            // Secondary language dominant: boost secondary dictionary
            secondaryScore > _autoSwitchThreshold -> 1.1f + (secondaryScore - _autoSwitchThreshold) * 0.5f
            // Primary language dominant: penalize secondary dictionary
            primaryScore > _autoSwitchThreshold -> 0.85f
            // Balanced: neutral
            else -> 1.0f
        }

        if (_debugMode) {
            Log.d(TAG, "🌍 Language multiplier updated: $_currentLanguageMultiplier " +
                "(en=$primaryScore, $_secondaryLanguageCode=$secondaryScore, threshold=$_autoSwitchThreshold)")
        }
    }

    /**
     * Configure auto-switch settings.
     *
     * @param enabled Whether auto-switch is enabled
     * @param threshold Score threshold to trigger switch (0.0-1.0)
     * @param secondaryLanguage Secondary language code (e.g., "es")
     */
    fun setAutoSwitchConfig(enabled: Boolean, threshold: Float, secondaryLanguage: String) {
        _autoSwitchEnabled = enabled
        _autoSwitchThreshold = threshold.coerceIn(0.3f, 0.9f) // Reasonable bounds
        _secondaryLanguageCode = secondaryLanguage

        // Reset multiplier to default when config changes
        // v1.1.94: Use config value when disabled (no dynamic adjustment)
        _currentLanguageMultiplier = if (enabled) 1.0f else _secondaryPredictionWeight

        Log.i(TAG, "Auto-switch config: enabled=$enabled, threshold=$threshold, lang=$secondaryLanguage")
    }

    /**
     * Configure primary language settings.
     * This determines whether English vocabulary is used as fallback.
     *
     * English fallback is ENABLED when:
     * - Primary = English (en)
     * - Primary = non-English AND Secondary = English (en)
     *
     * English fallback is DISABLED when:
     * - Primary = non-English AND Secondary = None or another non-English language
     *
     * @param primaryLanguage Primary language code (e.g., "fr", "de", "en")
     * @param secondaryLanguage Secondary language code (e.g., "en", "es", "none")
     */
    fun setPrimaryLanguageConfig(primaryLanguage: String, secondaryLanguage: String) {
        // v1.1.93 CRITICAL FIX: For non-English primary, _primaryLanguageCode is set in
        // loadPrimaryDictionary() AFTER activeBeamSearchTrie is updated.
        // This prevents race condition where getVocabularyTrie() sees _primaryLanguageCode="fr"
        // but activeBeamSearchTrie still points to English trie.
        // For English primary, set it here since loadPrimaryDictionary() is not called.
        if (primaryLanguage == "en") {
            _primaryLanguageCode = primaryLanguage
        }
        // Note: _primaryLanguageCode for non-English is set in loadPrimaryDictionary()

        _secondaryLanguageCode = secondaryLanguage

        // English fallback is enabled if:
        // 1. Primary is English, OR
        // 2. Secondary is English (user explicitly wants English as backup)
        _englishFallbackEnabled = (primaryLanguage == "en") || (secondaryLanguage == "en")

        Log.i(TAG, "Primary language config: primary=$primaryLanguage, secondary=$secondaryLanguage, englishFallback=$_englishFallbackEnabled, _primaryLanguageCode=$_primaryLanguageCode")
    }

    /**
     * Get the secondary dictionary's NormalizedPrefixIndex for direct access.
     * Used by SuggestionRanker for unified scoring.
     */
    fun getSecondaryIndex(): NormalizedPrefixIndex? {
        return secondaryNormalizedIndex
    }

    /**
     * Look up a normalized (accent-free) word in the secondary dictionary.
     *
     * @param normalizedWord The NN output (26-letter only, e.g., "espanol")
     * @return List of canonical forms with accents, sorted by frequency
     */
    fun lookupSecondaryCanonicals(normalizedWord: String): List<NormalizedPrefixIndex.LookupResult> {
        val index = secondaryNormalizedIndex ?: return emptyList()
        return index.getWordsWithPrefix(normalizedWord).filter {
            it.normalized == normalizedWord // Exact match, not prefix
        }
    }

    /**
     * Get accent-aware predictions from secondary dictionary.
     *
     * Given an NN prediction like "espanol", returns the canonical "español"
     * if it exists in the secondary dictionary.
     *
     * @param nnPrediction The NN output word (26-letter)
     * @return The best canonical form, or null if not found
     */
    fun getAccentedForm(nnPrediction: String): String? {
        val index = secondaryNormalizedIndex ?: return null
        val normalized = AccentNormalizer.normalize(nnPrediction)
        val results = index.getWordsWithPrefix(normalized).filter {
            it.normalized == normalized
        }
        return results.firstOrNull()?.bestCanonical
    }

    /**
     * Create SuggestionRanker candidates from secondary dictionary lookup.
     *
     * @param nnPredictions List of NN predictions with confidence scores
     * @param languageCode Language code for these candidates
     * @return List of candidates ready for ranking
     */
    fun createSecondaryCandidates(
        nnPredictions: List<CandidateWord>,
        languageCode: String
    ): List<SuggestionRanker.Candidate> {
        val index = secondaryNormalizedIndex ?: return emptyList()
        val candidates = mutableListOf<SuggestionRanker.Candidate>()

        for (prediction in nnPredictions) {
            val normalized = AccentNormalizer.normalize(prediction.word)
            val results = index.getWordsWithPrefix(normalized).filter {
                it.normalized == normalized
            }

            for (result in results) {
                candidates.add(
                    SuggestionRanker.Candidate(
                        word = result.bestCanonical,
                        normalized = result.normalized,
                        frequencyRank = result.bestFrequencyRank,
                        source = SuggestionRanker.WordSource.SECONDARY,
                        nnConfidence = prediction.confidence,
                        languageCode = languageCode
                    )
                )
            }
        }

        return candidates
    }

    // ==================== END MULTILANGUAGE SUPPORT ====================

    /**
     * Load custom words and Android user dictionary into beam search vocabulary
     * High frequency ensures they appear in predictions
     *
     * @since v1.1.86 - Uses language-specific keys for custom words
     */
    private fun loadCustomAndUserWords() {
        if (context == null) return // Redundant check, context is non-null

        try {
            val prefs = DirectBootAwarePreferences.get_shared_preferences(context)

            // v1.1.86: Run migration if needed (copy global keys to English keys)
            LanguagePreferenceKeys.migrateToLanguageSpecific(prefs)
            // v1.1.88: Also migrate legacy user_dictionary SharedPreferences file
            LanguagePreferenceKeys.migrateUserDictionary(context, prefs)

            // 1. Load custom words from SharedPreferences (language-specific key)
            val customWordsKey = LanguagePreferenceKeys.customWordsKey(_primaryLanguageCode)
            val customWordsJson = prefs.getString(customWordsKey, "{}")
            if (customWordsJson != "{}") {
                try {
                    val jsonObj = org.json.JSONObject(customWordsJson)
                    val keys = jsonObj.keys()
                    var customCount = 0
                    while (keys.hasNext()) {
                        val word = keys.next().toLowerCase(Locale.ROOT)
                        val frequency = jsonObj.optInt(word, 1000) // Raw frequency 1-10000

                        // Normalize frequency to 0.0-1.0 range (1.0 = most frequent)
                        // Aligns with main dictionary normalization
                        val normalizedFreq = max(0.0f, (frequency - 1).toFloat() / 9999.0f)

                        // Assign tier dynamically based on frequency
                        // Very high frequency (>=8000) = tier 2 (common boost)
                        // Otherwise = tier 1 (top5000 boost)
                        val tier = if (frequency >= 8000) 2.toByte() else 1.toByte()

                        vocabulary[word] = WordInfo(normalizedFreq, tier)
                        customCount++

                        // DEBUG: Log each custom word loaded
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            val debugMsg = String.format("  Custom word loaded: \"%s\" (freq=%d → normalized=%.4f, tier=%d)\n",
                                word, frequency, normalizedFreq, tier)
                            Log.d(TAG, debugMsg)
                            sendDebugLog(debugMsg)
                        }
                    }
                    val loadMsg = "Loaded $customCount custom words into beam search (frequency-based tiers)"
                    Log.d(TAG, loadMsg)
                    sendDebugLog(loadMsg + "\n")
                } catch (e: org.json.JSONException) {
                    Log.e(TAG, "Failed to parse custom words JSON", e)
                }
            }

            // 2. Load Android user dictionary
            try {
                val cursor = context.contentResolver.query(
                    android.provider.UserDictionary.Words.CONTENT_URI,
                    arrayOf(
                        android.provider.UserDictionary.Words.WORD,
                        android.provider.UserDictionary.Words.FREQUENCY
                    ),
                    null,
                    null,
                    null
                )

                cursor?.use {
                    val wordIndex = it.getColumnIndex(android.provider.UserDictionary.Words.WORD)
                    var userCount = 0

                    while (it.moveToNext()) {
                        val word = it.getString(wordIndex).toLowerCase(Locale.ROOT)
                        // User dictionary words should rank HIGH - user explicitly added them
                        // CRITICAL: Previous value (250 → 0.025) ranked user words at position 48,736!
                        val frequency = 9000

                        // Normalize to 0-1 range (~0.90)
                        val normalizedFreq = max(0.0f, (frequency - 1).toFloat() / 9999.0f)

                        // Assign tier 2 (common boost) - user words are important
                        val tier: Byte = 2

                        vocabulary[word] = WordInfo(normalizedFreq, tier)
                        userCount++
                    }

                    Log.d(TAG, "Loaded $userCount user dictionary words into beam search")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load user dictionary for beam search", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading custom/user words for beam search", e)
        }
    }

    /**
     * Load disabled words from preferences for filtering beam search results.
     *
     * @since v1.1.86 - Uses language-specific keys for disabled words
     */
    private fun loadDisabledWords() {
        if (context == null) { // Redundant check, context is non-null
            disabledWords = HashSet()
            return
        }

        try {
            val prefs = DirectBootAwarePreferences.get_shared_preferences(context)

            // v1.1.86: Run migration if needed (idempotent)
            LanguagePreferenceKeys.migrateToLanguageSpecific(prefs)

            // Use language-specific key
            val disabledKey = LanguagePreferenceKeys.disabledWordsKey(_primaryLanguageCode)
            val disabledSet = prefs.getStringSet(disabledKey, HashSet())
            disabledWords = HashSet(disabledSet)
            Log.d(TAG, "Loaded ${disabledWords.size} disabled words for $disabledKey")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load disabled words", e)
            disabledWords = HashSet()
        }
    }

    /**
     * Load contraction mappings for apostrophe display support.
     *
     * Loads both:
     * - Paired contractions (base word exists: "well" -> "we'll") - English only
     * - Non-paired contractions (without apostrophe -> with apostrophe)
     *
     * For non-English languages (FR, IT, PT, DE), loads language-specific
     * contraction files that map apostrophe-free forms to apostrophe forms:
     * - French: "cest" -> "c'est", "jai" -> "j'ai", etc.
     * - Italian: "luomo" -> "l'uomo", "ce" -> "c'è", etc.
     *
     * @since v1.1.87 - Added multilanguage contraction support
     */
    private fun loadContractionMappings() {
        if (context == null) {
            contractionPairings = HashMap()
            nonPairedContractions = HashMap()
            return
        }

        try {
            // Load English paired contractions (base word -> list of contraction variants)
            // This is English-specific (possessives like "we'll", "they're")
            loadEnglishPairedContractions()

            // Load non-paired contractions for the current language
            // This maps apostrophe-free -> with apostrophe
            loadLanguageContractions(_primaryLanguageCode)

            // If primary is not English, also load English contractions as fallback
            if (_primaryLanguageCode != "en") {
                loadLanguageContractions("en")
            }

            Log.d(TAG, "Loaded contractions for $_primaryLanguageCode: " +
                    "${nonPairedContractions.size} mappings, ${contractionPairings.size} paired bases")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading contraction mappings", e)
            contractionPairings = HashMap()
            nonPairedContractions = HashMap()
        }
    }

    /**
     * Load English paired contractions (base word -> contraction variants).
     * Example: "well" -> ["we'll"], "they" -> ["they're", "they've", "they'd"]
     */
    private fun loadEnglishPairedContractions() {
        try {
            val inputStream = context!!.assets.open("dictionaries/contraction_pairings.json")
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonBuilder = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                jsonBuilder.append(line)
            }
            reader.close()

            val jsonObj = org.json.JSONObject(jsonBuilder.toString())
            val keys = jsonObj.keys()
            var pairingCount = 0

            while (keys.hasNext()) {
                val baseWord = keys.next().lowercase(Locale.ROOT)
                val contractionArray = jsonObj.getJSONArray(baseWord)
                val contractionList = ArrayList<String>()

                for (i in 0 until contractionArray.length()) {
                    val contractionObj = contractionArray.getJSONObject(i)
                    val contraction = contractionObj.getString("contraction").lowercase(Locale.ROOT)
                    contractionList.add(contraction)
                }

                contractionPairings[baseWord] = contractionList
                pairingCount += contractionList.size
            }

            Log.d(TAG, "Loaded $pairingCount English paired contractions")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load English paired contractions: ${e.message}")
            contractionPairings = HashMap()
        }
    }

    /**
     * Load language-specific contraction mappings.
     *
     * Tries to load from:
     * 1. Installed language pack (files/langpacks/{code}/contractions.json)
     * 2. Bundled assets (assets/dictionaries/contractions_{code}.json)
     *
     * Contraction files map apostrophe-free forms to apostrophe forms:
     * - contractions_fr.json: "cest" -> "c'est", "jai" -> "j'ai"
     * - contractions_it.json: "luomo" -> "l'uomo", "ce" -> "c'è"
     * - contractions_en.json: "dont" -> "don't", "cant" -> "can't"
     * - contractions_nl.json: "autos" -> "auto's", "zon" -> "zo'n"
     *
     * @param langCode Language code (e.g., "fr", "it", "en", "nl")
     */
    private fun loadLanguageContractions(langCode: String) {
        // First try loading from installed language pack
        val langPackManager = LanguagePackManager.getInstance(context!!)
        val packContractionsFile = langPackManager.getContractionsPath(langCode)

        if (packContractionsFile != null) {
            try {
                val count = loadContractionsFromInputStream(packContractionsFile.inputStream())
                Log.d(TAG, "Loaded $count contractions for $langCode from language pack")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load contractions from language pack for $langCode: ${e.message}")
            }
        }

        // Fall back to bundled assets
        val filename = "dictionaries/contractions_$langCode.json"
        try {
            val inputStream = context!!.assets.open(filename)
            val count = loadContractionsFromInputStream(inputStream)
            Log.d(TAG, "Loaded $count contractions for $langCode from assets")
        } catch (e: java.io.FileNotFoundException) {
            Log.d(TAG, "No contraction file for $langCode (this is normal for some languages)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load contractions for $langCode: ${e.message}")
        }
    }

    /**
     * Load contractions from an InputStream.
     * Also adds contraction keys (apostrophe-free forms) to vocabulary so they pass
     * the vocabulary filter and can be converted to their apostrophe form.
     *
     * Example: "mappelle" -> "m'appelle"
     * - "mappelle" is added to vocabulary (so NN prediction passes filter)
     * - contraction map converts display to "m'appelle"
     */
    private fun loadContractionsFromInputStream(inputStream: InputStream): Int {
        val reader = BufferedReader(InputStreamReader(inputStream))
        val jsonBuilder = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            jsonBuilder.append(line)
        }
        reader.close()

        val jsonObj = org.json.JSONObject(jsonBuilder.toString())
        val keys = jsonObj.keys()
        var count = 0

        while (keys.hasNext()) {
            val withoutApostrophe = keys.next().lowercase(Locale.ROOT)
            val withApostrophe = jsonObj.getString(withoutApostrophe).lowercase(Locale.ROOT)
            // Don't overwrite existing mappings (primary language takes precedence)
            if (!nonPairedContractions.containsKey(withoutApostrophe)) {
                nonPairedContractions[withoutApostrophe] = withApostrophe

                // Add contraction key to vocabulary so it passes the filter
                // This allows NN predictions like "mappelle" to be accepted and then
                // converted to "m'appelle" via the contraction map
                // Use tier 1 (top5000 equivalent) with mid-range frequency
                // NOTE: Do NOT insert into activeBeamSearchTrie here - it may point to wrong trie!
                // Trie insertion is handled by loadPrimaryDictionary() after creating the language trie
                if (!vocabulary.containsKey(withoutApostrophe)) {
                    vocabulary[withoutApostrophe] = WordInfo(0.6f, 1.toByte())
                    // Add to length-based buckets for fuzzy matching
                    vocabularyByLength.getOrPut(withoutApostrophe.length) { ArrayList() }
                        .add(withoutApostrophe)
                }
                count++
            }
        }

        return count
    }

    /**
     * Send debug log message to SwipeDebugActivity if available
     * Sends broadcast to be picked up by debug activity
     */
    private fun sendDebugLog(message: String) {
        if (context == null) return // Redundant check, context is non-null

        try {
            val intent = android.content.Intent("tribixbite.cleverkeys.DEBUG_LOG")
            intent.`package` = context.packageName
            intent.putExtra("log_message", message)
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            // Silently fail - debug activity might not be running
        }
    }
}
