package tribixbite.cleverkeys.swipe.geometric

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Pure-JVM reader for the CleverKeys V2 `CKDT` binary dictionary format, producing a
 * [GeometricDictionary] whose index i IS the word's ordinal frequency rank r(w), or —
 * via [readEntries] — the raw `(canonical word, uint8 rank)` records for consumers that
 * need the rank BYTE rather than the ordinal (the CTC lexicon's `255 − rank` scale).
 * There is ONE parser; [read] is a projection of [readEntries].
 *
 * This PORTS (does not import) the V2 canonical-section layout documented at
 * `BinaryDictionaryLoader.kt:55-90`; that loader imports `android.content.Context` /
 * `android.util.Log`, so it cannot be reused under the pure test classpath (NFR-3).
 * Only the canonical section is read — the normalized/accent-map sections drive the
 * live accent-recovery predictor, not this engine (which projects codepoints
 * geometrically and returns the canonical accented form).
 *
 * ## V2 header (48 bytes, little-endian)
 *  - magic `CKDT` (4)  · version 2 (4)  · language 4 (e.g. `"ru\0\0"`)
 *  - wordCount uint32 (4)  · canonicalOffset uint32 (4)
 *  - normalizedOffset / accentMapOffset / reserved (unread here)
 *
 * ## Canonical section (at canonicalOffset)
 *  - per word: length uint16 · UTF-8 bytes · frequency-rank uint8 (0 = most common).
 *
 * ## Ordinal ordering (deterministic, NFR-4)
 * Words are STABLE-sorted by `(uint8 rank ascending, original file order ascending)`.
 * Ascending rank = descending frequency, so index 0 is the most-frequent word. The
 * file-order tie-break disambiguates the thousands of words that share one uint8
 * rank, giving a single fixed ordering every run.
 */
object CkdtDictionaryReader {

    /** "CKDT" little-endian, matching `BinaryDictionaryLoader.MAGIC_V2`. */
    private const val MAGIC_V2 = 0x54444B43
    private const val EXPECTED_VERSION_V2 = 2
    private const val HEADER_SIZE_V2 = 48

    /**
     * Read a CKDT dictionary from an [InputStream] (fully consumed) into a retained
     * [GeometricDictionary].
     *
     * @param language BCP-47-ish code for the cache key (the header language is read
     *   but the caller's explicit code wins — a langpack may carry a different tag).
     * @param version generation token for the cache key (bump on any word-set change).
     * @throws IllegalArgumentException on a bad magic / version.
     */
    fun read(input: InputStream, language: String, version: Long = 1L): GeometricDictionary {
        val bytes = input.readBytes()
        return read(bytes, language, version)
    }

    /** Read a CKDT dictionary from an in-memory byte array. */
    fun read(bytes: ByteArray, language: String, version: Long = 1L): GeometricDictionary {
        val ordered = readEntries(bytes)
        return ArrayBackedDictionary(language, version, Array(ordered.size) { ordered[it].word })
    }

    /**
     * One canonical record: the accent-preserving word and its uint8 frequency rank
     * (0 = most common, 255 = least).
     *
     * The rank BYTE is what a frequency-scored consumer needs — the CTC lexicon reads it
     * as `freq = max(1, 255 − rank)` ([tribixbite.cleverkeys.swipe.ctc.CtcCkdtLexicon]) —
     * whereas [GeometricDictionary] exposes only the ORDINAL (array index), which is a
     * different quantity: thousands of words share one rank byte.
     */
    data class Entry(val word: String, val rank: Int)

    /**
     * Parse the canonical section into [Entry] records in the same deterministic order
     * [read] uses: rank ascending, original file order as the (stable) tie-break.
     *
     * This is THE parser — [read] is a thin projection of it onto [GeometricDictionary].
     */
    fun readEntries(input: InputStream): List<Entry> = readEntries(input.readBytes())

    /** [readEntries] over an in-memory byte array. */
    fun readEntries(bytes: ByteArray): List<Entry> {
        require(bytes.size >= HEADER_SIZE_V2) {
            "CKDT file too short: ${bytes.size} bytes < header $HEADER_SIZE_V2"
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val magic = buffer.int
        require(magic == MAGIC_V2) {
            "not a CKDT V2 dictionary: magic 0x%08X (expected 0x%08X)".format(magic, MAGIC_V2)
        }
        val ver = buffer.int
        require(ver == EXPECTED_VERSION_V2) { "unsupported CKDT version $ver (expected $EXPECTED_VERSION_V2)" }

        // Language field (4 bytes, NUL-padded); read but the caller-supplied code wins.
        val langBytes = ByteArray(4)
        buffer.get(langBytes)

        val wordCount = buffer.int
        require(wordCount >= 0) { "negative wordCount $wordCount" }
        val canonicalOffset = buffer.int
        // Skip normalized/accent-map offsets + reserved — unused by this engine.

        // Read the canonical section: (word, rank) pairs preserving file order.
        buffer.position(canonicalOffset)
        val words = arrayOfNulls<String>(wordCount)
        val ranks = IntArray(wordCount)
        for (i in 0 until wordCount) {
            val len = buffer.short.toInt() and 0xFFFF
            val wb = ByteArray(len)
            buffer.get(wb)
            words[i] = String(wb, Charsets.UTF_8)
            ranks[i] = buffer.get().toInt() and 0xFF
        }

        // Stable ordinal order = (rank asc, file order asc). Sort an index array by
        // rank; ties keep file order because we sort the ascending 0..n index list
        // with a stable comparator keyed on rank only.
        val order = (0 until wordCount).sortedBy { ranks[it] }  // sortedBy is stable
        return List(wordCount) { Entry(words[order[it]]!!, ranks[order[it]]) }
    }

    /**
     * ru-zip route: read a CKDT `dictionary.bin` entry out of a language-pack zip
     * (e.g. `scripts/dictionaries/langpack-ru.zip`) via [ZipFile] — the pure-JVM path
     * for the ЙЦУКЕН harness lexicon (the langpack's `unigrams.txt` is a bare 5k list
     * with no frequencies and is a frequency cross-check only, never the harness dict).
     *
     * @param zip the language-pack archive.
     * @param entryName the CKDT entry to read (default `"dictionary.bin"`).
     */
    fun readFromZip(
        zip: File,
        language: String,
        version: Long = 1L,
        entryName: String = "dictionary.bin",
    ): GeometricDictionary {
        require(zip.exists()) { "language-pack zip not found: ${zip.absolutePath}" }
        ZipFile(zip).use { zf ->
            val entry = zf.getEntry(entryName)
                ?: throw IllegalArgumentException("zip ${zip.name} has no entry '$entryName'")
            zf.getInputStream(entry).use { input ->
                return read(input, language, version)
            }
        }
    }
}
