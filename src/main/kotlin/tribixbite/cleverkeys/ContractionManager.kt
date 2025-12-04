package tribixbite.cleverkeys

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
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
            // Try binary format first (fastest)
            if (loadBinaryContractions()) {
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                    Log.d(TAG, "Loaded contractions from binary format")
                }
                return
            }

            // Fall back to JSON format (slower, but always works)
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                Log.d(TAG, "Binary format not available, loading from JSON")
            }
            loadNonPairedContractions()
            loadPairedContractions()

            Log.d(TAG, "Loaded ${nonPairedContractions.size} non-paired contractions, " +
                    "${knownContractions.size} total known contractions")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load contraction mappings", e)
        }
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
     * The neural network predicts "dont", we replace with "don't".
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

            for (i in 0 until contractions.length()) {
                val contractionObj = contractions.getJSONObject(i)
                val contraction = contractionObj.getString("contraction")

                knownContractions.add(contraction.lowercase())
                pairedCount++
            }
        }

        Log.d(TAG, "Loaded $pairedCount paired contractions")
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
