package tribixbite.cleverkeys.swipe.ctc

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Reads the English CTC lexicon `dictionaries/en_enhanced.json` (`{word: frequency}`) into the
 * `(word, frequency)` pairs `CtcEngineAdapter.lexiconFor`'s EN_JSON branch merges.
 *
 * **Streamed** (2026-11-26, candidate 2a of `docs/audit/2026-11-26-memory-analysis.md`). The read
 * was `readBytes()` + `JSONObject`, which held the 1.75 MiB file, its decoded text and a
 * 98,140-entry map all at once in the middle of a rebuild — the rebuild a single custom-word add
 * triggers. Gson's [JsonReader] (already a dependency, pure JVM) holds one key at a time.
 *
 * Same pairs as the old parse, pinned against it by `CtcEnLexiconJsonTest`:
 *  - **order**: file order, which is the order Android's `org.json` iterated (its map is a
 *    `LinkedHashMap`) and the beam's and the contraction ordinals' tie-break;
 *  - **values**: `optInt(word, 1)` — a number truncated to Int, a numeric string parsed, anything
 *    else 1;
 *  - **duplicates**: the old parse kept a duplicated key's last value at its first position; this
 *    reader emits every occurrence and `CtcLexiconMerge.merge` keeps the first. The shipped asset
 *    has no duplicate key (pinned by the test), so nothing differs for it.
 */
object CtcEnLexiconJson {

    /**
     * The pairs in file order. [checkCancelled] runs once per key and may throw (the adapter
     * throws `InterruptedException` when its worker was cancelled); the stream is not closed here.
     */
    fun readPairs(input: InputStream, checkCancelled: () -> Unit = {}): ArrayList<Pair<String, Double>> {
        // Sized for the shipped asset (98,140 keys) so the list does not regrow mid-build.
        val pairs = ArrayList<Pair<String, Double>>(EXPECTED_KEYS)
        val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8))
        reader.beginObject()
        while (reader.hasNext()) {
            checkCancelled()
            val word = reader.nextName()
            pairs.add(word to optInt(reader).toDouble())
        }
        reader.endObject()
        return pairs
    }

    /** `org.json`'s `optInt(key, 1)` on the value [reader] is at, consuming it. */
    private fun optInt(reader: JsonReader): Int = when (reader.peek()) {
        // Gson returns a number's literal text; Long first keeps an integer's exact value, as
        // org.json's Integer/Long did, and a fraction truncates like Double.intValue().
        JsonToken.NUMBER, JsonToken.STRING -> numberOrDefault(reader.nextString())
        else -> {
            reader.skipValue()
            DEFAULT_FREQUENCY
        }
    }

    private fun numberOrDefault(text: String): Int =
        text.toLongOrNull()?.toInt()
            ?: text.trim().toDoubleOrNull()?.toInt()
            ?: DEFAULT_FREQUENCY

    /** The old call's fallback: `base.optInt(word, 1)`. */
    private const val DEFAULT_FREQUENCY = 1

    private const val EXPECTED_KEYS = 98_140
}
