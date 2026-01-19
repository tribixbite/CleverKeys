package tribixbite.cleverkeys

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Helper class to load a small test dictionary for instrumented tests.
 * Avoids OOM by using a minimal dictionary instead of the full production dictionary.
 */
object TestDictionaryHelper {

    // Common English words for testing - small enough to avoid OOM
    private val TEST_WORDS = mapOf(
        "the" to 10000,
        "be" to 9900,
        "to" to 9800,
        "of" to 9700,
        "and" to 9600,
        "a" to 9500,
        "in" to 9400,
        "that" to 9300,
        "have" to 9200,
        "i" to 9100,
        "it" to 9000,
        "for" to 8900,
        "not" to 8800,
        "on" to 8700,
        "with" to 8600,
        "he" to 8500,
        "as" to 8400,
        "you" to 8300,
        "do" to 8200,
        "at" to 8100,
        "this" to 8000,
        "but" to 7900,
        "his" to 7800,
        "by" to 7700,
        "from" to 7600,
        "they" to 7500,
        "we" to 7400,
        "say" to 7300,
        "her" to 7200,
        "she" to 7100,
        "or" to 7000,
        "an" to 6900,
        "will" to 6800,
        "my" to 6700,
        "one" to 6600,
        "all" to 6500,
        "would" to 6400,
        "there" to 6300,
        "their" to 6200,
        "what" to 6100,
        "hello" to 6000,
        "world" to 5900,
        "happy" to 5800,
        "test" to 5700,
        "word" to 5600,
        "help" to 5500,
        "type" to 5400,
        "quick" to 5300,
        "brown" to 5200,
        "fox" to 5100,
        "keyboard" to 5000,
        "swipe" to 4900,
        "typing" to 4800,
        "predict" to 4700,
        "correct" to 4600,
        "spell" to 4500,
        "language" to 4400,
        "english" to 4300,
        "french" to 4200,
        "spanish" to 4100,
        "german" to 4000,
        // Common typos for autocorrect testing
        "teh" to 100,
        "hte" to 100,
        "adn" to 100,
        "waht" to 100,
        "taht" to 100
    )

    /**
     * Load test dictionary from androidTest assets.
     * Uses a minimal JSON dictionary to avoid OOM.
     */
    fun loadTestDictionary(context: Context): Map<String, Int> {
        return try {
            val inputStream = context.assets.open("dictionaries/en_enhanced.json")
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonBuilder = StringBuilder()
            reader.useLines { lines ->
                lines.forEach { jsonBuilder.append(it) }
            }
            reader.close()

            val jsonDict = JSONObject(jsonBuilder.toString())
            val result = mutableMapOf<String, Int>()
            val keys = jsonDict.keys()
            while (keys.hasNext()) {
                val word = keys.next().lowercase()
                val frequency = jsonDict.getInt(word)
                // Scale frequency from 128-255 to 100-10000 range
                val scaledFreq = 100 + ((frequency - 128) / 127.0 * 9900).toInt()
                result[word] = scaledFreq
            }
            result
        } catch (e: Exception) {
            // Fallback to hardcoded test words if asset loading fails
            TEST_WORDS
        }
    }

    /**
     * Get the hardcoded test dictionary (no I/O needed).
     */
    fun getTestWords(): Map<String, Int> = TEST_WORDS

    /**
     * Check if a word should be in the test dictionary.
     */
    fun isTestWord(word: String): Boolean {
        return TEST_WORDS.containsKey(word.lowercase())
    }
}
