package tribixbite.cleverkeys

import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Shared, file-based view of the SHIPPED static context LMs (`src/main/assets/lm/<lang>.cklm`)
 * and the vocabulary each is allowed to name — used by [StaticLmAssetDriftTest] and
 * [StaticLmTapEvalTest]. Project root as CWD.
 *
 * The allowed vocabulary is read from each model's sidecar (`model.lexicon`,
 * `model.contractionFiles`, written by `scripts/build_static_lm.py` from its `LangConfig`), so
 * the tests follow the builder's per-language configuration instead of a second hardcoded copy.
 */
object StaticLmLanguageData {

    /** The shipped models. The drift test always reads these. */
    val LM_DIR = File("src/main/assets/lm")

    /**
     * Where [StaticLmTapEvalTest] reads models from: `STATIC_LM_MODEL_DIR` when set (a CANDIDATE
     * built with `build_static_lm.py --out-dir <dir>`, evaluated before it is copied into the
     * assets — so no unevaluated model is ever packaged by a build of the shared tree), else [LM_DIR].
     */
    val EVAL_LM_DIR: File = System.getenv("STATIC_LM_MODEL_DIR")?.takeIf { it.isNotBlank() }?.let(::File) ?: LM_DIR
    val DICT_DIR = File("src/main/assets/dictionaries")

    /** Language codes with a `.cklm` in [dir] (default: shipped), sorted. */
    fun shippedLanguages(dir: File = LM_DIR): List<String> =
        (dir.listFiles { f -> f.name.endsWith(".cklm") } ?: emptyArray())
            .map { it.name.removeSuffix(".cklm") }
            .sorted()

    fun asset(language: String, dir: File = LM_DIR) = File(dir, "$language.cklm")

    fun sidecar(language: String, dir: File = LM_DIR) = JSONObject(File(dir, "$language.json").readText())

    /** A CKDT v2 dictionary's canonical section: words (lowercased, file order) + rank bytes. */
    class Ckdt(val words: Array<String>, val ranks: IntArray)

    /** Read `<file>` (CKDT v2: magic, version, reserved, count, canonical offset; u16 len + UTF-8 + u8 rank). */
    fun readCkdt(file: File): Ckdt {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        check(b.int == CKDT_MAGIC && b.int == 2) { "unexpected dictionary header in $file" }
        b.position(b.position() + 4)
        val count = b.int
        val canonical = b.int
        b.position(canonical)
        val words = Array(count) { "" }
        val ranks = IntArray(count)
        for (i in 0 until count) {
            val len = b.short.toInt() and 0xFFFF
            val bytes = ByteArray(len).also { b.get(it) }
            words[i] = String(bytes, Charsets.UTF_8).lowercase()
            ranks[i] = b.get().toInt() and 0xFF
        }
        return Ckdt(words, ranks)
    }

    /** The lexicon the builder drew [language]'s vocabulary from (sidecar `model.lexicon`). */
    fun lexiconWords(language: String, dir: File = LM_DIR): Set<String> {
        val name = sidecar(language, dir).getJSONObject("model").getString("lexicon")
        val file = File(DICT_DIR, name)
        return if (name.endsWith(".json")) {
            val out = HashSet<String>()
            JSONObject(file.readText()).keys().forEach { out.add(it.lowercase()) }
            out
        } else {
            readCkdt(file).words.toHashSet()
        }
    }

    /**
     * Display forms from [language]'s contraction files (sidecar `model.contractionFiles`). A
     * value is a form (REPLACE files), a list of forms (PAIRED files), or a list of
     * `{"contraction": form}` objects (the English pairings) — the builder's `contraction_forms`.
     */
    fun contractionForms(language: String, dir: File = LM_DIR): Set<String> {
        val files = sidecar(language, dir).getJSONObject("model").getJSONArray("contractionFiles")
        val out = HashSet<String>()
        for (i in 0 until files.length()) {
            val o = JSONObject(File(DICT_DIR, files.getString(i)).readText())
            for (k in o.keys()) {
                when (val v = o.get(k)) {
                    is String -> out.add(v.lowercase())
                    is org.json.JSONArray -> for (j in 0 until v.length()) {
                        when (val e = v.get(j)) {
                            is JSONObject -> out.add(e.getString("contraction").lowercase())
                            else -> out.add(e.toString().lowercase())
                        }
                    }
                    else -> error("unexpected contraction value for $k in ${files.getString(i)}")
                }
            }
        }
        return out
    }

    /**
     * [language]'s REPLACE bucket (apostrophe-free key → display form) as
     * `ContractionManager.loadSwipeDisplayMappings(language)` leaves it for a single language —
     * the map `BigramModel` hands to [StaticContextLm.withReplaceAliases] on the device. A
     * file-based mirror because `ContractionManager` needs an Android `Context`:
     *
     *  - English: `contractions_non_paired.json` (the JSON twin of `contractions.bin`) minus every
     *    `contraction_pairings.json` base (the 2026-07-23 reclassification), then
     *    `contractions_en.json` EARLIER-WINS, again skipping pairing bases (the re-add guard in
     *    `loadContractionsFromStream`). `.claude/skills/contraction-system.md` §3: 107 keys.
     *  - Every other language: `contractions_<lang>.json`. Its PAIRED file
     *    (`contraction_pairs_<lang>.json`) never aliases — those keys are words (`lune`).
     */
    fun replaceAliases(language: String): Map<String, String> {
        fun replaceFile(name: String): Map<String, String> {
            val file = File(DICT_DIR, name)
            if (!file.isFile) return emptyMap()
            val o = JSONObject(file.readText())
            val out = LinkedHashMap<String, String>()
            for (k in o.keys()) out[k.lowercase()] = o.getString(k).lowercase()
            return out
        }
        if (language != "en") return replaceFile("contractions_$language.json")
        val pairingBases = JSONObject(File(DICT_DIR, "contraction_pairings.json").readText()).keys()
            .asSequence().map { it.lowercase() }.toHashSet()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in replaceFile("contractions_non_paired.json")) if (k !in pairingBases) out[k] = v
        for ((k, v) in replaceFile("contractions_en.json")) if (k !in pairingBases) out.putIfAbsent(k, v)
        return out
    }

    private const val CKDT_MAGIC = 0x54444B43
}
