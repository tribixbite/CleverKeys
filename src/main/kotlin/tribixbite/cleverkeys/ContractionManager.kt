package tribixbite.cleverkeys

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import org.json.JSONObject
import tribixbite.cleverkeys.langpack.LanguagePackManager
import tribixbite.cleverkeys.swipe.SwipeContractionPolicy
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Manages contraction mappings for apostrophe insertion in predictions.
 *
 * Handles two types of contractions:
 * 1. Non-paired: apostrophe-free forms that map to single contractions
 *    Example: "dont" -> "don't", "cant" -> "can't"
 * 2. Paired: base words that have multiple contraction variants
 *    Example: "well" -> ["we'll", "well"], "id" -> ["I'd", "id"]
 *
 * This class is extracted from CleverKeysService.java for better separation of concerns
 * and testability (v1.32.341).
 */
class ContractionManager(private val context: Context) {
    // Non-paired contractions: apostrophe-free form -> contraction with apostrophe
    // Example: "dont" -> "don't", "wholl" -> "who'll"
    private val nonPairedContractions: MutableMap<String, String> = mutableMapOf()

    // Paired contractions: base word (valid English word) -> contraction variants
    // Example: "its" -> ["it's"], "well" -> ["we'll"]
    // These are words where the base form IS a valid word, so both forms should appear
    private val pairedContractions: MutableMap<String, MutableList<String>> = mutableMapOf()

    // Set of all known contractions (both non-paired and paired) for quick lookup
    // Used to identify contractions in predictions and prevent unwanted autocorrect
    private val knownContractions: MutableSet<String> = mutableSetOf()

    private val assetManager: AssetManager = context.assets

    /**
     * Loads contraction mappings from assets/dictionaries/.
     *
     * OPTIMIZATION v1 (perftodos2.md Todo 4): Uses binary format for faster loading.
     *
     * Strategy:
     * 1. Try binary format first (contractions.bin) - fastest
     * 2. Fall back to JSON if binary doesn't exist or fails
     *
     * Binary format is 3-5x faster than JSON parsing.
     *
     * Must be called before using isKnownContraction() or getNonPairedMapping().
     */
    fun loadMappings() {
        try {
            // v1.2.1: Clear existing mappings before loading to prevent stale data on reload
            // Without this, language toggle could leave old contractions mixed with new
            nonPairedContractions.clear()
            pairedContractions.clear()
            knownContractions.clear()

            // Try binary format first (fastest)
            if (loadBinaryContractions()) {
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                    Log.d(TAG, "Loaded contractions from binary format")
                }
                // Binary format merges all contractions into nonPairedContractions,
                // misclassifying paired entries (e.g., "well"→"we'll" where "well" IS
                // a valid word). Load JSON paired data to fix the classification.
                loadPairedContractions()
            } else {
                // Fall back to JSON format (slower, but always works)
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                    Log.d(TAG, "Binary format not available, loading from JSON")
                }
                loadNonPairedContractions()
                loadPairedContractions()
            }

            // 2026-07-23 (multilingual audit): COMPLETE the paired-classification fix.
            // Both sources put paired bases into the non-paired map (the shipped binary
            // carries well→we'll, were→we're, hell→he'll, girls→girls', states→states'),
            // which violates getNonPairedMapping's documented contract ("only where the
            // apostrophe-free form is not a valid word") and made the typing pipeline
            // REPLACE those real words in the suggestion bar (typing "wer" showed "we're"
            // and no "were"). Paired bases belong ONLY in pairedContractions, where
            // consumers inject the variant ALONGSIDE the base instead of replacing it.
            val pairedBases = pairedContractions.keys.intersect(nonPairedContractions.keys)
            if (pairedBases.isNotEmpty()) {
                pairedBases.forEach { nonPairedContractions.remove(it) }
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                    Log.d(TAG, "Reclassified ${pairedBases.size} paired bases out of the non-paired map")
                }
            }

            Log.d(TAG, "Loaded ${nonPairedContractions.size} non-paired contractions, " +
                    "${knownContractions.size} total known contractions")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load contraction mappings", e)
        }
    }

    /**
     * Loads the contraction mappings a SWIPE ENGINE may overlay onto a slate decoded in
     * [langCode] — and nothing else. The single loader for both swipe adapters
     * (`CtcEngineAdapter` / `GeometricEngineAdapter`), so the two can never drift apart.
     *
     * Policy ([SwipeContractionPolicy] — read its KDoc for the product rule):
     *
     *  - **English** (incl. `en-GB`/`en_US`, and a blank/unknown-yet code): the bundled base
     *    set then `contractions_en.json`, i.e. byte-for-byte the pre-2026-08-16 behavior
     *    both adapters had. This path is test-2400-validated and deliberately unchanged.
     *  - **Any other language**: the previously loaded set is DROPPED and ONLY that
     *    language's own files are loaded (both of them — see [loadLanguageContractions] for
     *    the REPLACE/APPEND split). Without the drop, the bundled ENGLISH base
     *    (`contractions.bin`'s 1,183 paired display forms — 1,116 of them possessives — plus
     *    `contraction_pairings.json`) stays resident and injects English morphology into a
     *    non-English slate: a `fr` decode of the real French word `franco` also offered
     *    `franco's`, whose base `francos` is absent from the French lexicon
     *    (`CtcMultiLanguageInstrumentedTest`, 2026-08-16).
     *
     * This mirrors the v1.1.88 vocabulary fix (`OptimizedVocabulary`, since deleted: clear the English
     * contractions before loading the target language's) — the geo/CTC adapters were the
     * last engines still contaminating non-English slates.
     *
     * NOTE — swipe only. The TYPING pipeline's shared manager (`ManagerInitializer` /
     * `PreferenceUIUpdateHandler`) keeps its own load order; changing that is a separate
     * decision with a separate blast radius.
     *
     * Idempotent, and safe to call repeatedly on ONE instance across language switches:
     * both branches start from a cleared state, so no previous language's mappings survive.
     *
     * @param langCode active decode language (ISO 639-1, optionally region-suffixed).
     */
    fun loadSwipeDisplayMappings(langCode: String) {
        if (SwipeContractionPolicy.usesEnglishBase(langCode)) {
            loadMappings()
            loadLanguageContractions(SwipeContractionPolicy.ENGLISH)
            return
        }
        val dropped = nonPairedContractions.size + pairedContractions.size
        // The drop is the fix: loadLanguageContractions is EARLIER-WINS and never clears,
        // so anything still resident here would shadow — and outlive — the active language.
        nonPairedContractions.clear()
        pairedContractions.clear()
        knownContractions.clear()
        loadLanguageContractions(langCode)
        Log.d(
            TAG,
            "Swipe contractions for '$langCode': dropped $dropped English/previous-language " +
                "mappings, loaded ${nonPairedContractions.size} non-paired + " +
                "${pairedContractions.size} paired"
        )
    }

    /**
     * Load language-specific contractions for the given language.
     * Called when keyboard language changes to load appropriate contraction mappings.
     *
     * A language ships its mappings in TWO bundled files, and which file an entry lives in
     * IS its display mode (see [tribixbite.cleverkeys.swipe.ContractionOverlay]):
     *
     *  - `contractions_<lang>.json` → [nonPairedContractions]: the key has no reading of its
     *    own (`cest`, `jai`, `gehts`), so the display form REPLACES it and keeps its slot.
     *  - `contraction_pairs_<lang>.json` → [pairedContractions]: the key IS a word of the
     *    language (`lune`, `danse`, `lago`), so it is KEPT and the elision is APPENDED as a
     *    variant. Both spellings stay reachable, which is the product requirement — the
     *    keyboard may not decide for the user whether they meant the moon or "l'une".
     *
     * The split is resolved at data-generation time from corpus attestation of the bare form
     * (`scripts/extract_apostrophe_words.py`), NOT at runtime from its frequency rank: rank
     * works for English by luck and destroyed common French/Italian words, which rank past
     * `REAL_WORD_ORDINAL_MAX` (fr `lune` is 2,055th). The overlay's rank guard stays as
     * defense in depth for IMPORTED language packs, whose `contractions.json` is uncurated.
     *
     * Tries to load from:
     * 1. Installed language pack (files/langpacks/{code}/contractions.json) — REPLACE-mode
     *    only; a pack that supplies its own file wins outright and the bundled files for
     *    that language are skipped, exactly as before.
     * 2. Bundled assets (assets/dictionaries/contractions_{code}.json + the pairs file).
     *
     * @param langCode Language code (e.g., "fr", "it", "de", "es", "pt", "nl")
     */
    fun loadLanguageContractions(langCode: String) {
        // First try loading from installed language pack
        val langPackManager = LanguagePackManager.getInstance(context)
        val packContractionsFile = langPackManager.getContractionsPath(langCode)

        if (packContractionsFile != null) {
            try {
                val count = loadContractionsFromFile(packContractionsFile)
                Log.d(TAG, "Loaded $count contractions for $langCode from language pack (total: ${nonPairedContractions.size})")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load contractions from language pack for $langCode: ${e.message}")
            }
        }

        // Fall back to bundled assets
        val filename = "dictionaries/contractions_$langCode.json"
        try {
            val inputStream = assetManager.open(filename)
            val count = loadContractionsFromStream(inputStream)
            Log.d(TAG, "Loaded $count contractions for $langCode from assets (total: ${nonPairedContractions.size})")
        } catch (e: java.io.FileNotFoundException) {
            Log.d(TAG, "No contraction file for $langCode (this is normal for some languages)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load contractions for $langCode: ${e.message}")
        }
        loadLanguagePairedContractions(langCode)
    }

    /**
     * Loads [langCode]'s APPEND-mode mappings from
     * `assets/dictionaries/contraction_pairs_<lang>.json` (`{word: [variant, …]}`) into
     * [pairedContractions] — see [loadLanguageContractions] for why the file exists.
     *
     * Deliberately a different shape from English's `contraction_pairings.json`
     * (`{word: [{contraction, frequency}]}`): that file carries the frequency the English
     * possessive generator was built around, which a per-language elision list has no
     * equivalent of, and inventing one would be fabricated data.
     *
     * Absent file → no-op: only fr/it ship one, and es/pt/sv/id/ms/tl/sw must stay at zero
     * mappings (`ContractionManagerTest`).
     *
     * @return the number of variants added.
     */
    private fun loadLanguagePairedContractions(langCode: String): Int {
        val filename = "dictionaries/contraction_pairs_$langCode.json"
        return try {
            val jsonObj = JSONObject(readStream(assetManager.open(filename)))
            val keys = jsonObj.keys()
            var count = 0
            while (keys.hasNext()) {
                val baseWord = keys.next()
                val lowerBase = baseWord.lowercase()
                val variants = jsonObj.getJSONArray(baseWord)
                for (i in 0 until variants.length()) {
                    val variant = variants.getString(i).lowercase()
                    knownContractions.add(variant)
                    // EARLIER-WINS on the variant list, mirroring loadContractionsFromStream:
                    // the first language loaded takes precedence and a re-load is idempotent.
                    val existing = pairedContractions.getOrPut(lowerBase) { mutableListOf() }
                    if (variant !in existing) {
                        existing.add(variant)
                        count++
                    }
                }
            }
            Log.d(TAG, "Loaded $count paired contractions for $langCode from assets")
            count
        } catch (e: java.io.FileNotFoundException) {
            0 // Most languages ship no APPEND-mode mappings — not an error.
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load paired contractions for $langCode: ${e.message}")
            0
        }
    }

    /**
     * Load contractions from a File (used for imported language packs).
     */
    private fun loadContractionsFromFile(file: File): Int {
        return loadContractionsFromStream(file.inputStream())
    }

    /**
     * Load contractions from an InputStream.
     */
    private fun loadContractionsFromStream(inputStream: InputStream): Int {
        val jsonString = readStream(inputStream)
        val jsonObj = JSONObject(jsonString)
        val keys = jsonObj.keys()
        var count = 0

        while (keys.hasNext()) {
            val withoutApostrophe = keys.next()
            val withApostrophe = jsonObj.getString(withoutApostrophe)

            // Don't overwrite existing mappings (first language loaded takes precedence)
            if (!nonPairedContractions.containsKey(withoutApostrophe.lowercase())) {
                nonPairedContractions[withoutApostrophe.lowercase()] = withApostrophe.lowercase()
                knownContractions.add(withApostrophe.lowercase())
                count++
            }
        }

        return count
    }

    /**
     * Load contractions from optimized binary format.
     *
     * OPTIMIZATION v1 (perftodos2.md Todo 4): Fast binary loading without JSON parsing.
     *
     * @return true if loaded successfully, false if binary doesn't exist or failed
     */
    private fun loadBinaryContractions(): Boolean {
        return try {
            val data = BinaryContractionLoader.loadContractions(
                context,
                "dictionaries/contractions.bin"
            ) ?: return false

            nonPairedContractions.putAll(data.nonPairedContractions)
            knownContractions.addAll(data.knownContractions)

            // Derive paired contraction mappings from known contractions
            // Binary format doesn't store base→contraction mappings for paired entries,
            // so we derive them: for any contraction NOT in nonPaired values, the base
            // word is the apostrophe-removed form (e.g., "it's" → base "its")
            val nonPairedValues = data.nonPairedContractions.values.toSet()
            for (contraction in data.knownContractions) {
                if (contraction !in nonPairedValues) {
                    val baseWord = contraction.replace("'", "")
                    pairedContractions.getOrPut(baseWord) { mutableListOf() }.add(contraction)
                }
            }

            true
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                Log.d(TAG, "Binary contractions not available: ${e.message}")
            }
            false
        }
    }

    /**
     * Checks if a word is a known contraction (has apostrophe).
     *
     * @param word Word to check (case-insensitive)
     * @return true if word is in the known contractions set
     *
     * Examples:
     * - isKnownContraction("don't") -> true
     * - isKnownContraction("we'll") -> true
     * - isKnownContraction("hello") -> false
     */
    fun isKnownContraction(word: String): Boolean {
        return knownContractions.contains(word.lowercase())
    }

    /**
     * Checks if a word is a contraction key (apostrophe-free form that maps to contraction).
     * Used to skip autocorrect for words like "cest" that should become "c'est".
     *
     * @param word Word to check (case-insensitive)
     * @return true if word is a key in nonPairedContractions
     *
     * Examples:
     * - isContractionKey("cest") -> true (maps to "c'est")
     * - isContractionKey("jai") -> true (maps to "j'ai")
     * - isContractionKey("hello") -> false
     */
    fun isContractionKey(word: String): Boolean {
        return nonPairedContractions.containsKey(word.lowercase())
    }

    /**
     * Gets the contraction with apostrophe for an apostrophe-free form.
     *
     * Only works for non-paired contractions (where the apostrophe-free form
     * is not a valid English word).
     *
     * @param withoutApostrophe Apostrophe-free form (case-insensitive)
     * @return Contraction with apostrophe, or null if not found
     *
     * Examples:
     * - getNonPairedMapping("dont") -> "don't"
     * - getNonPairedMapping("wholl") -> "who'll"
     * - getNonPairedMapping("well") -> null (paired contraction)
     */
    fun getNonPairedMapping(withoutApostrophe: String): String? {
        return nonPairedContractions[withoutApostrophe.lowercase()]
    }

    /**
     * Gets paired contraction variants for a base word that is also a valid English word.
     *
     * @param baseWord Base word (case-insensitive)
     * @return List of contraction variants, or null if not a paired contraction base
     *
     * Examples:
     * - getPairedContractions("its") -> ["it's"]
     * - getPairedContractions("well") -> ["we'll"]
     * - getPairedContractions("hello") -> null
     */
    fun getPairedContractions(baseWord: String): List<String>? {
        return pairedContractions[baseWord.lowercase()]
    }

    /**
     * Every alias KEY currently loaded — the apostrophe-free surfaces the overlay can
     * rewrite, from BOTH the REPLACE file ([nonPairedContractions]) and the APPEND file
     * ([pairedContractions]).
     *
     * The swipe engines use this to make the mapping table actually reachable: a key the
     * decoder cannot spell is an inert mapping, so `CtcContractionKeys.inject` inserts these
     * into the lexicon trie (at a floor frequency — see that class). Keys, not variants:
     * the variants carry apostrophes and are never decoder output.
     */
    fun getAliasKeys(): Set<String> {
        val keys = HashSet<String>(nonPairedContractions.size + pairedContractions.size)
        keys.addAll(nonPairedContractions.keys)
        keys.addAll(pairedContractions.keys)
        return keys
    }

    /**
     * Gets the number of non-paired contractions loaded.
     * Useful for testing and diagnostics.
     */
    fun getNonPairedCount(): Int = nonPairedContractions.size

    /**
     * Gets the total number of known contractions (non-paired + paired).
     * Useful for testing and diagnostics.
     */
    fun getTotalKnownCount(): Int = knownContractions.size

    /**
     * Generates a possessive form for a given word.
     *
     * OPTIMIZATION v5 (perftodos5.md): Rule-based possessive generation.
     * Instead of storing 1700+ possessive entries, generate them dynamically.
     *
     * Rules:
     * - Most words: add 's (cat -> cat's, dog -> dog's)
     * - Words ending in 's': add 's (Charles -> Charles's) [modern style]
     * - Never generate for pronouns/function words (handled by contractions)
     *
     * @param word Base word to make possessive
     * @return Possessive form (word + 's)
     *
     * Examples:
     * - generatePossessive("cat") -> "cat's"
     * - generatePossessive("dog") -> "dog's"
     * - generatePossessive("James") -> "James's"
     */
    fun generatePossessive(word: String?): String? {
        if (word.isNullOrEmpty()) {
            return null
        }

        val wordLower = word.lowercase()

        // Don't generate possessives for known contractions
        // (e.g., don't turn "don't" into "don't's")
        if (isKnownContraction(wordLower)) {
            return null
        }

        // Don't generate for function words/pronouns that have special contractions
        // These are already handled by the true contractions in the binary file
        if (wordLower in FUNCTION_WORDS) {
            return null
        }

        // Generate possessive: word + 's
        // Modern style: even words ending in 's' get 's (James's, not James')
        return "$word's"
    }

    /**
     * Checks if a word should have a possessive variant generated.
     *
     * OPTIMIZATION v5 (perftodos5.md): Determine if possessive makes sense.
     *
     * @param word Word to check
     * @return true if possessive should be generated
     */
    fun shouldGeneratePossessive(word: String): Boolean {
        return generatePossessive(word) != null
    }

    /**
     * Loads non-paired contractions from contractions_non_paired.json.
     *
     * Format: {"dont": "don't", "cant": "can't", ...}
     *
     * These are apostrophe-free forms that are NOT valid English words.
     * The decoder predicts "dont", we replace with "don't".
     */
    private fun loadNonPairedContractions() {
        val inputStream = assetManager.open("dictionaries/contractions_non_paired.json")
        val jsonString = readStream(inputStream)

        val jsonObj = JSONObject(jsonString)
        val keys = jsonObj.keys()

        while (keys.hasNext()) {
            val withoutApostrophe = keys.next()
            val withApostrophe = jsonObj.getString(withoutApostrophe)

            nonPairedContractions[withoutApostrophe.lowercase()] = withApostrophe.lowercase()
            knownContractions.add(withApostrophe.lowercase())
        }

        Log.d(TAG, "Loaded ${nonPairedContractions.size} non-paired contractions")
    }

    /**
     * Loads paired contractions from contraction_pairings.json.
     *
     * Format: {"well": [{"contraction": "we'll", "frequency": 243}], ...}
     *
     * These are base words that ARE valid English words but also have
     * contraction variants. Both forms should appear in predictions.
     * Example: "well" (adverb) and "we'll" (we will) are both valid.
     */
    private fun loadPairedContractions() {
        val inputStream = assetManager.open("dictionaries/contraction_pairings.json")
        val jsonString = readStream(inputStream)

        val jsonObj = JSONObject(jsonString)
        val keys = jsonObj.keys()
        var pairedCount = 0

        while (keys.hasNext()) {
            val baseWord = keys.next()
            val contractions = jsonObj.getJSONArray(baseWord)
            val lowerBase = baseWord.lowercase()

            for (i in 0 until contractions.length()) {
                val contractionObj = contractions.getJSONObject(i)
                val contraction = contractionObj.getString("contraction").lowercase()

                knownContractions.add(contraction)
                pairedContractions.getOrPut(lowerBase) { mutableListOf() }.add(contraction)
                pairedCount++
            }
        }

        Log.d(TAG, "Loaded $pairedCount paired contractions (${pairedContractions.size} base words)")
    }

    /**
     * Reads an InputStream into a String.
     * Helper method for JSON file loading.
     */
    private fun readStream(inputStream: InputStream): String {
        BufferedReader(InputStreamReader(inputStream)).use { reader ->
            return reader.readText()
        }
    }

    companion object {
        private const val TAG = "ContractionManager"

        // Function words/pronouns that have special contractions
        private val FUNCTION_WORDS = setOf(
            "i", "you", "he", "she", "it", "we", "they",
            "who", "what", "that", "there", "here",
            "will", "would", "shall", "should",
            "can", "could", "may", "might", "must",
            "do", "does", "did",
            "is", "am", "are", "was", "were",
            "have", "has", "had", "let"
        )
    }
}
