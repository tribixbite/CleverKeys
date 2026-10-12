package tribixbite.cleverkeys

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The English TAP-typing dictionary exactly as the device loads it, for JVM harnesses of
 * `WordPredictor` (autocorrect, suggestion bar, `SpaceCommitResolver`).
 *
 * On device both `AsyncDictionaryLoader` and `WordPredictor.loadDictionary` read
 * `dictionaries/en_enhanced.bin` through `BinaryDictionaryLoader.loadDictionaryWithPrefixIndex`,
 * which parses it with [BinaryDictionaryParser] (V2: uint8 rank → `1 000 000 − rank · 3 900`).
 * This fixture calls that same parser on the same file, so harness frequencies cannot drift
 * from the device's ([ShippedTapDictionaryDriftTest] pins it).
 *
 * Do NOT model the tap predictor on `en_enhanced.json`: that file is the CTC SWIPE lexicon's
 * compressed 134..255 byte scale, not `255 − rank` of the `.bin` (98,139 of 98,140 words
 * differ; `help`/`hello` are 39 rank steps apart on device but 20 in a JSON-derived map).
 * Swipe/CTC tests correctly keep using the JSON.
 *
 * Android-free: usable from both `runPureTests` and `runMockTests`.
 */
internal object ShippedTapDictionary {

    /** The bundled asset the device's tap predictor loads for `en` (no language pack). */
    val BIN: File = File("src/main/assets/dictionaries/en_enhanced.bin")

    /** The bundled contraction alias file `loadContractionKeysIntoMaps` merges for `en`. */
    val CONTRACTIONS: File = File("src/main/assets/dictionaries/contractions_en.json")

    /** Parsed once; callers always receive fresh mutable copies. */
    private val parsed: Pair<Map<String, Int>, Map<String, Set<String>>> by lazy {
        val dict = HashMap<String, Int>(200_000)
        val index = HashMap<String, MutableSet<String>>()
        val buffer = ByteBuffer.wrap(BIN.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val result = BinaryDictionaryParser.parseWithPrefixIndex(buffer, dict, index)
        check(result is BinaryDictionaryParser.Result.Loaded) { "en_enhanced.bin did not parse: $result" }
        dict to index
    }

    /** Word → device frequency, as loaded (no contraction aliases). Fresh copy. */
    fun frequencies(): HashMap<String, Int> = HashMap(parsed.first)

    /** The loader's 1–3 character prefix index (no contraction aliases). Fresh deep copy. */
    fun prefixIndex(): HashMap<String, MutableSet<String>> =
        parsed.second.mapValuesTo(HashMap()) { HashSet(it.value) }

    /** Dictionary, prefix index and alias map after the contraction merge. */
    class Loaded(
        val dictionary: HashMap<String, Int>,
        val prefixIndex: HashMap<String, MutableSet<String>>,
        val aliases: Map<String, String>,
    )

    /**
     * The device's post-load state for `en`: [frequencies] + [prefixIndex], then the contraction
     * alias merge of `WordPredictor.loadContractionKeysIntoMaps` / `loadPrimaryContractionKeys`:
     * skip `REAL_WORD_CONTRACTION_BASES`, alias `bare → apostrophe form`, set
     * `dict[bare] = dict[apo] ?: dict[bare] ?: 5000`, and index the bare key's 1–3 prefixes.
     * Keys and values are lowercased, as `ContractionJsonReader` does (it is an
     * `android.util.JsonReader` client, so Gson stands in for it here).
     */
    fun withContractionAliases(): Loaded {
        val dict = frequencies()
        val index = prefixIndex()
        val type = object : TypeToken<Map<String, String>>() {}.type
        val contractions: Map<String, String> = CONTRACTIONS.reader().use { Gson().fromJson(it, type) }
        val realWordBases = realWordContractionBases()
        val aliases = LinkedHashMap<String, String>()
        for ((bareRaw, apoRaw) in contractions) {
            val bare = bareRaw.lowercase()
            val apo = apoRaw.lowercase()
            if (bare in realWordBases) continue
            aliases[bare] = apo
            dict[bare] = dict[apo] ?: dict[bare] ?: 5000
            for (len in 1..minOf(3, bare.length)) index.getOrPut(bare.substring(0, len)) { HashSet() }.add(bare)
        }
        return Loaded(dict, index, aliases)
    }

    /** `WordPredictor.REAL_WORD_CONTRACTION_BASES` (private companion set), read reflectively. */
    @Suppress("UNCHECKED_CAST")
    fun realWordContractionBases(): Set<String> {
        val field = WordPredictor::class.java.getDeclaredField("REAL_WORD_CONTRACTION_BASES")
        field.isAccessible = true
        return field.get(null) as Set<String>
    }
}
