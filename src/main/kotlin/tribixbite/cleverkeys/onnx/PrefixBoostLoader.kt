package tribixbite.cleverkeys.onnx

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Loads and manages language-specific prefix boosts for beam search.
 *
 * These boosts compensate for prefixes that are common in the target language
 * but rare in English (which the English-trained NN under-predicts).
 *
 * Usage:
 * ```
 * val loader = PrefixBoostLoader(context)
 * loader.loadBoosts("fr")  // Load French boosts
 * val boost = loader.getBoost("ve", 'u')  // Returns ~8.0 for French
 * ```
 */
class PrefixBoostLoader(private val context: Context) {

    companion object {
        private const val TAG = "PrefixBoostLoader"
        private const val ASSET_PATH = "prefix_boosts"
    }

    // Current loaded boosts: prefix → (char → boost_value)
    private var boosts: Map<String, Map<Char, Float>> = emptyMap()
    private var loadedLanguage: String? = null

    /**
     * Load prefix boosts for a language.
     *
     * @param langCode Language code (e.g., "fr", "es")
     * @return true if loaded successfully
     */
    fun loadBoosts(langCode: String): Boolean {
        if (langCode == loadedLanguage && boosts.isNotEmpty()) {
            return true  // Already loaded
        }

        // English doesn't need boosts (it's the base language)
        if (langCode == "en") {
            boosts = emptyMap()
            loadedLanguage = "en"
            Log.d(TAG, "No boosts needed for English")
            return true
        }

        val assetPath = "$ASSET_PATH/$langCode.json"
        return try {
            context.assets.open(assetPath).use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                val content = reader.readText()
                val json = JSONObject(content)

                val boostsJson = json.getJSONObject("boosts")
                val newBoosts = mutableMapOf<String, Map<Char, Float>>()

                for (prefix in boostsJson.keys()) {
                    val charBoosts = boostsJson.getJSONObject(prefix)
                    val charMap = mutableMapOf<Char, Float>()

                    for (char in charBoosts.keys()) {
                        // Handle both single chars and special chars (é, ê, etc.)
                        if (char.isNotEmpty()) {
                            charMap[char[0]] = charBoosts.getDouble(char).toFloat()
                        }
                    }

                    if (charMap.isNotEmpty()) {
                        newBoosts[prefix] = charMap
                    }
                }

                boosts = newBoosts
                loadedLanguage = langCode
                Log.i(TAG, "Loaded ${boosts.size} prefix boosts for $langCode")
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "No prefix boosts available for $langCode: ${e.message}")
            boosts = emptyMap()
            loadedLanguage = null
            false
        }
    }

    /**
     * Unload current boosts to free memory.
     */
    fun unloadBoosts() {
        boosts = emptyMap()
        loadedLanguage = null
    }

    /**
     * Get boost value for a prefix + next character combination.
     *
     * Uses longest-match strategy: tries prefix lengths from longest to shortest.
     * This prevents double-boosting when both "ve" and "veu" have entries.
     *
     * @param currentPrefix The current decoded prefix (e.g., "ve")
     * @param nextChar The candidate next character (e.g., 'u')
     * @param maxPrefixLen Maximum prefix length to check (default 4)
     * @return Boost value (positive = boost, 0 = no boost)
     */
    fun getBoost(currentPrefix: String, nextChar: Char, maxPrefixLen: Int = 4): Float {
        if (boosts.isEmpty()) return 0f

        val prefix = currentPrefix.lowercase()
        val char = nextChar.lowercaseChar()

        // Try longest matching suffix first (longest-match-only strategy)
        val startLen = minOf(maxPrefixLen, prefix.length)
        for (len in startLen downTo 1) {
            val checkPrefix = prefix.takeLast(len)
            boosts[checkPrefix]?.get(char)?.let { boost ->
                return boost
            }
        }

        return 0f
    }

    /**
     * Apply boosts to a logits array in-place.
     *
     * @param currentPrefix The current decoded prefix
     * @param logits The logits array (modified in place)
     * @param tokenizer Used to map indices to characters
     * @param boostMultiplier Scaling factor for boosts (default 1.0)
     * @param maxBoost Maximum boost value to apply (clamping)
     */
    fun applyBoostsToLogits(
        currentPrefix: String,
        logits: FloatArray,
        tokenToChar: (Int) -> Char,
        boostMultiplier: Float = 1.0f,
        maxBoost: Float = 5.0f
    ) {
        if (boosts.isEmpty() || boostMultiplier == 0f) return

        for (i in logits.indices) {
            val char = tokenToChar(i)
            if (!char.isLetter()) continue

            val boost = getBoost(currentPrefix, char)
            if (boost > 0f) {
                // Apply scaled and clamped boost
                val scaledBoost = (boost * boostMultiplier).coerceIn(-maxBoost, maxBoost)
                logits[i] += scaledBoost
            }
        }
    }

    /**
     * Check if boosts are loaded for current language.
     */
    fun hasBoosts(): Boolean = boosts.isNotEmpty()

    /**
     * Get current loaded language.
     */
    fun getLoadedLanguage(): String? = loadedLanguage

    /**
     * Get statistics about loaded boosts.
     */
    fun getStats(): BoostStats {
        if (boosts.isEmpty()) {
            return BoostStats(0, 0, 0f, 0f)
        }

        var totalBoosts = 0
        var maxBoost = 0f
        var sumBoost = 0f

        for (charBoosts in boosts.values) {
            for (boost in charBoosts.values) {
                totalBoosts++
                maxBoost = maxOf(maxBoost, boost)
                sumBoost += boost
            }
        }

        return BoostStats(
            prefixCount = boosts.size,
            boostCount = totalBoosts,
            maxBoost = maxBoost,
            avgBoost = if (totalBoosts > 0) sumBoost / totalBoosts else 0f
        )
    }

    data class BoostStats(
        val prefixCount: Int,
        val boostCount: Int,
        val maxBoost: Float,
        val avgBoost: Float
    )
}
