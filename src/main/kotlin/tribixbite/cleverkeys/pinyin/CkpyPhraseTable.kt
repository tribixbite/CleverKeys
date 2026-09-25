package tribixbite.cleverkeys.pinyin

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-JVM reader for the CleverKeys V1 `CKPY` pinyin phrase table: a 1:N map from a
 * toneless pinyin key (`"nihao"`, `"xi'an"`) to ranked candidate text (汉字 / 漢字).
 * This is the conversion layer a Pinyin IME needs and that `CKDT` cannot express —
 * `CKDT` stores one display word per lookup key, while one pinyin key has many
 * candidate words, each with its own preference rank.
 *
 * The companion writer is `scripts/build_phrase_table.py`; the pack-level plumbing
 * (`inputMethod` manifest field, `phrases.bin` member, import validation) lives in
 * [tribixbite.cleverkeys.langpack.LanguagePackManager]. Format rationale and the
 * engine plan are in `docs/specs/pinyin-ime.md`.
 *
 * ## V1 header (48 bytes, little-endian)
 *  - magic `CKPY` (4) · version 1 (4) · language 4 (UTF-8, NUL-padded, e.g. `"zh"`)
 *  - keyCount uint32 (4) · dataOffset uint32 (4) · reserved 28 bytes (zeros)
 *
 * ## Data section (at `dataOffset`), `keyCount` records
 * Keys are stored in STRICTLY ASCENDING byte order; one record is:
 *  - length uint16 · UTF-8 bytes · candidateCount uint16
 *  - then candidateCount candidate records:
 *      - length uint16 · UTF-8 bytes · rank uint8 (0 = most preferred)
 *
 * Candidate ranks are non-decreasing within one key, so the on-disk order IS the
 * display order. Ranks are engine-relative (like the `CKDT` rank byte and
 * `PredictionResult.scores`), never probabilities.
 *
 * ## Bounds (untrusted pack input)
 *
 * Every length is validated before it is used to slice the buffer, counts are capped,
 * and the reader refuses trailing bytes, bad UTF-8 replacement characters, duplicate
 * or unsorted keys, and out-of-order ranks. A malformed table fails loudly at parse
 * time instead of producing a silently wrong candidate list.
 *
 * ## Key normalization (build-time and lookup-time, fixed)
 *  - lowercase ASCII `a-z`, `'` only (`v` spells `ü`; tones are dropped by the builder)
 *  - no digits, spaces, hyphens; `xi'an` keeps the apostrophe
 *  - `lookup`/`withPrefix` require an already-normalized query; anything else throws
 *
 * ## Ordinal ordering (deterministic)
 *
 * The file order is the lookup order. [Table.lookup] is a binary search over the
 * parsed list; [Table.withPrefix] is a lower-bound binary search plus a forward scan,
 * so typing `"ni"` finds `"ni"`, `"nian"`, `"niang"`… in one contiguous run.
 */
object CkpyPhraseTable {

    /** "CKPY" little-endian. */
    const val MAGIC = 0x59504B43

    /** The only version this reader understands. */
    const val VERSION = 1

    /** Fixed header size in bytes. */
    const val HEADER_SIZE = 48

    /** UTF-8 bytes reserved for the language tag. */
    const val LANGUAGE_BYTES = 4

    /** Refuse to materialise a phrase table larger than this (untrusted pack input). */
    private const val MAX_FILE_BYTES = 64L * 1024 * 1024

    private const val MAX_KEY_COUNT = 1_000_000
    private const val MAX_KEY_BYTES = 120
    private const val MAX_CANDIDATES_PER_KEY = 512
    private const val MAX_TEXT_BYTES = 64

    private const val STREAM_CHUNK = 64 * 1024

    private val KEY_PATTERN = Regex("^[a-z']+$")

    /** One candidate display form with its preference rank (0 = most preferred). */
    data class Candidate(val text: String, val rank: Int)

    /** One pinyin key and its ranked candidates, in display order. */
    data class Entry(val key: String, val candidates: List<Candidate>)

    /**
     * A parsed, immutable phrase table. Entries are in ascending [Entry.key] order;
     * the [language] tag is informational (the caller's pack `code` wins, exactly as
     * the `CKDT` header tag does).
     */
    class Table internal constructor(
        val language: String,
        val entries: List<Entry>,
    ) {
        /** Exact-key lookup; [key] must be normalized (`^[a-z']+$`). */
        fun lookup(key: String): Entry? {
            require(KEY_PATTERN.matches(key)) {
                "pinyin key \"$key\" is not normalized (lowercase a-z and ' only)"
            }
            val index = entries.binarySearch { it.key.compareTo(key) }
            return if (index >= 0) entries[index] else null
        }

        /**
         * Every entry whose key starts with the normalized [prefix], in key order.
         * An empty prefix returns an empty list (a keyboard with no input shows no
         * candidates, and this keeps the call from scanning the whole table).
         */
        fun withPrefix(prefix: String): List<Entry> {
            require(prefix.all { it in 'a'..'z' || it == '\'' }) {
                "pinyin prefix \"$prefix\" is not normalized (lowercase a-z and ' only)"
            }
            if (prefix.isEmpty()) return emptyList()
            var index = entries.binarySearch { it.key.compareTo(prefix) }
            if (index < 0) index = -index - 1
            val matches = ArrayList<Entry>()
            while (index < entries.size && entries[index].key.startsWith(prefix)) {
                matches.add(entries[index])
                index++
            }
            return matches
        }
    }

    /** Read a CKPY file from disk. */
    fun read(file: File): Table {
        require(file.length() <= MAX_FILE_BYTES) {
            "CKPY file is ${file.length()} bytes, over the $MAX_FILE_BYTES-byte limit"
        }
        return file.inputStream().use { read(it) }
    }

    /** Read a CKPY table from [input], fully consuming it. */
    fun read(input: InputStream): Table = read(readBounded(input))

    /** Read a CKPY table from an in-memory byte array. */
    fun read(bytes: ByteArray): Table {
        require(bytes.size in HEADER_SIZE..MAX_FILE_BYTES.toInt()) {
            "CKPY file too short: ${bytes.size} bytes (header is $HEADER_SIZE)"
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val magic = buffer.int
        require(magic == MAGIC) {
            "not a CKPY phrase table: magic 0x%08X (expected 0x%08X)".format(magic, MAGIC)
        }
        val version = buffer.int
        require(version == VERSION) {
            "unsupported CKPY version $version (expected $VERSION)"
        }

        val languageBytes = ByteArray(LANGUAGE_BYTES)
        buffer.get(languageBytes)
        val language = String(languageBytes, Charsets.UTF_8).trimEnd('\u0000')

        val keyCount = buffer.int
        require(keyCount in 1..MAX_KEY_COUNT) {
            "CKPY keyCount $keyCount out of range 1..$MAX_KEY_COUNT"
        }

        val dataOffset = buffer.int
        require(dataOffset in HEADER_SIZE..bytes.size) {
            "CKPY dataOffset $dataOffset out of range $HEADER_SIZE..${bytes.size}"
        }
        buffer.position(dataOffset)

        val entries = ArrayList<Entry>(keyCount)
        var previousKey: String? = null
        for (i in 0 until keyCount) {
            val keyLength = readLength(buffer, "key length")
            require(keyLength in 1..MAX_KEY_BYTES) {
                "CKPY key length $keyLength out of range 1..$MAX_KEY_BYTES"
            }
            val key = readUtf8(buffer, keyLength, "key")
            require(KEY_PATTERN.matches(key)) {
                "CKPY key \"$key\" is not normalized (lowercase a-z and ' only)"
            }
            require(previousKey == null || key > previousKey!!) {
                "CKPY keys are not strictly ascending: \"$key\" after \"$previousKey\""
            }
            previousKey = key

            val candidateCount = readLength(buffer, "candidate count")
            require(candidateCount in 1..MAX_CANDIDATES_PER_KEY) {
                "CKPY candidate count $candidateCount out of range 1..$MAX_CANDIDATES_PER_KEY"
            }
            val candidates = ArrayList<Candidate>(candidateCount)
            var previousRank = -1
            for (c in 0 until candidateCount) {
                val textLength = readLength(buffer, "candidate length")
                require(textLength in 1..MAX_TEXT_BYTES) {
                    "CKPY candidate length $textLength out of range 1..$MAX_TEXT_BYTES"
                }
                val text = readUtf8(buffer, textLength, "candidate text")
                require(text.isNotEmpty() && !text.contains('\uFFFD')) {
                    "CKPY candidate for \"$key\" is not valid UTF-8 text"
                }
                val rank = buffer.get().toInt() and 0xFF
                require(rank >= previousRank) {
                    "CKPY candidates for \"$key\" are not rank-ordered: $rank after $previousRank"
                }
                previousRank = rank
                candidates.add(Candidate(text, rank))
            }
            entries.add(Entry(key, candidates))
        }

        require(!buffer.hasRemaining()) {
            "CKPY has ${buffer.remaining()} trailing bytes"
        }
        return Table(language, entries)
    }

    /** Read at most [MAX_FILE_BYTES] so a hostile stream cannot exhaust the heap. */
    private fun readBounded(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(STREAM_CHUNK)
        var total = 0L
        while (true) {
            val read = input.read(chunk)
            if (read <= 0) break
            total += read
            require(total <= MAX_FILE_BYTES) {
                "CKPY stream exceeds the $MAX_FILE_BYTES-byte limit"
            }
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    private fun readLength(buffer: ByteBuffer, what: String): Int {
        require(buffer.remaining() >= 2) { "CKPY truncated while reading $what" }
        return buffer.short.toInt() and 0xFFFF
    }

    private fun readUtf8(buffer: ByteBuffer, length: Int, what: String): String {
        require(buffer.remaining() >= length) { "CKPY truncated while reading $what" }
        val bytes = ByteArray(length)
        buffer.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
