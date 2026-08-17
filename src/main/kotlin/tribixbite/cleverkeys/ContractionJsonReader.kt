package tribixbite.cleverkeys

import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale

/**
 * Streaming reader for the flat `{"alias": "display form"}` contraction assets
 * (`dictionaries/contractions_<lang>.json` and an imported language pack's
 * `contractions.json`).
 *
 * ## Why streaming
 *
 * Every caller used to do the same thing: read the whole file line-by-line into a
 * `StringBuilder`, `toString()` it, and hand that to `org.json.JSONObject`. For the restored
 * French file (18,114 mappings, ~570 KB) that peaks at roughly
 *
 *  - ~1.7 MB of `StringBuilder` `char[]` (it doubles as it grows, so the old array is live
 *    alongside the new one),
 *  - ~1.1 MB for the `toString()` copy — and these files carry accented display forms, so ART
 *    cannot store them as compressed Latin-1,
 *  - ~2 MB for the `JSONObject`'s `LinkedHashMap` plus a `String` per key AND per value,
 *
 * i.e. ~5 MB of transient garbage per call, on a device whose Java heap was already at 99.8%
 * when `WordPredictor.loadSecondaryDictionary` ran — the allocation that actually threw was
 * 16 bytes. Streaming holds one key and one value at a time instead.
 *
 * [forEachKey] additionally never materializes the values at all, which is what the two
 * prefix-index loaders need: they index the apostrophe-free KEY and discard the display form.
 *
 * Malformed input is logged and terminates the scan, matching the previous behaviour of
 * letting the `JSONObject` constructor throw into the caller's catch block: a corrupt
 * language pack degrades contraction display, it does not break dictionary loading.
 */
object ContractionJsonReader {

    private const val TAG = "ContractionJsonReader"

    /**
     * Visit every `key -> value` mapping of the object in [stream], both lowercased with
     * [Locale.ROOT] (every consumer lowercases, and doing it here avoids a second `String`).
     *
     * Closes [stream].
     *
     * @return the number of mappings visited.
     */
    fun forEachEntry(stream: InputStream, onEntry: (key: String, value: String) -> Unit): Int =
        read(stream) { reader, count ->
            val key = reader.nextName().lowercase(Locale.ROOT)
            if (reader.peek() == JsonToken.STRING) {
                onEntry(key, reader.nextString().lowercase(Locale.ROOT))
                count + 1
            } else {
                // A non-string value is not a contraction mapping; skip it rather than
                // aborting the whole file.
                reader.skipValue()
                count
            }
        }

    /**
     * Visit every KEY of the object in [stream], lowercased, without ever constructing the
     * value strings.
     *
     * Closes [stream].
     *
     * @return the number of keys visited.
     */
    fun forEachKey(stream: InputStream, onKey: (key: String) -> Unit): Int =
        read(stream) { reader, count ->
            val key = reader.nextName().lowercase(Locale.ROOT)
            reader.skipValue()
            onKey(key)
            count + 1
        }

    /**
     * Drives the shared object-scan loop; [step] consumes exactly one member and returns the
     * updated count.
     */
    private inline fun read(
        stream: InputStream,
        step: (reader: JsonReader, count: Int) -> Int,
    ): Int {
        var count = 0
        try {
            JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                    // An empty asset ships as `{}`; anything else (a list, a bare value) is
                    // not a contraction file.
                    Log.w(TAG, "Contraction asset is not a JSON object — ignoring")
                    return 0
                }
                reader.beginObject()
                while (reader.hasNext()) {
                    count = step(reader, count)
                }
                reader.endObject()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Malformed contraction asset after $count mappings", e)
        }
        return count
    }
}
