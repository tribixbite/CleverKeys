package tribixbite.cleverkeys.swipe.geometric

import java.io.InputStream

/**
 * Pure-JVM loader for the flat `{ "word": byteScore, ... }` JSON dictionaries
 * (`en_enhanced.json`) — the frequency-ordering CROSS-CHECK loader for the CKDT
 * reader (both cover the same 98,140-word English lexicon; identical ordinal order
 * up to tie-break proves neither loader silently reorders).
 *
 * JSON values are byte-scores (verified range 134–255) where a HIGHER score = more
 * common. Ordinal order (index i == rank r(w)) is therefore `(byteScore descending,
 * file order ascending)` — the same descending-frequency contract as [CkdtDictionaryReader],
 * with insertion order as the deterministic tie-break (NFR-4).
 *
 * The parser is a minimal, purpose-built scanner for exactly this flat shape (top-level
 * object, string keys, numeric values) — NOT a general JSON library (external deps are
 * forbidden by NFR-3). It handles the escape sequences the dictionaries actually use
 * (`\"`, `\\`, `\/`, `\uXXXX`, `\n`, `\t`, …) and preserves key order.
 */
object FlatJsonDictionaryLoader {

    /** Load from an [InputStream] (UTF-8, fully consumed). */
    fun read(input: InputStream, language: String, version: Long = 1L): GeometricDictionary =
        read(String(input.readBytes(), Charsets.UTF_8), language, version)

    /** Load from an already-decoded JSON string. */
    fun read(json: String, language: String, version: Long = 1L): GeometricDictionary {
        // Parse to (word, score) pairs in file order.
        val words = ArrayList<String>()
        val scores = ArrayList<Int>()
        parseFlatObject(json) { key, score ->
            words.add(key)
            scores.add(score)
        }

        // Ordinal order = (score DESC, file order ASC). We sort an index list stably by
        // negated score; `sortedBy` is stable so equal scores keep file order.
        val order = words.indices.sortedBy { -scores[it] }
        val ordered = Array(words.size) { words[order[it]] }
        return ArrayBackedDictionary(language, version, ordered)
    }

    /**
     * Scan a flat top-level JSON object, invoking [onEntry] for each `"key": number`
     * pair in file order. Numbers are truncated to Int (the byte-scores are already
     * integers). Throws [IllegalArgumentException] on malformed input.
     */
    private inline fun parseFlatObject(s: String, onEntry: (String, Int) -> Unit) {
        var i = skipWs(s, 0)
        require(i < s.length && s[i] == '{') { "expected top-level JSON object at index $i" }
        i = skipWs(s, i + 1)
        if (i < s.length && s[i] == '}') return // empty object
        while (i < s.length) {
            i = skipWs(s, i)
            require(i < s.length && s[i] == '"') { "expected string key at index $i" }
            val (key, afterKey) = parseString(s, i)
            i = skipWs(s, afterKey)
            require(i < s.length && s[i] == ':') { "expected ':' after key at index $i" }
            i = skipWs(s, i + 1)
            val (num, afterNum) = parseNumber(s, i)
            onEntry(key, num)
            i = skipWs(s, afterNum)
            require(i < s.length) { "unterminated object" }
            when (s[i]) {
                ',' -> { i++; continue }
                '}' -> return
                else -> throw IllegalArgumentException("expected ',' or '}' at index $i, got '${s[i]}'")
            }
        }
        throw IllegalArgumentException("unterminated JSON object")
    }

    /** Advance past ASCII whitespace. */
    private fun skipWs(s: String, from: Int): Int {
        var i = from
        while (i < s.length) {
            val c = s[i]
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++ else break
        }
        return i
    }

    /**
     * Parse a JSON string starting at the opening quote [start]; returns the decoded
     * value and the index just past the closing quote.
     */
    private fun parseString(s: String, start: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var i = start + 1 // skip opening quote
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> return sb.toString() to (i + 1)
                c == '\\' -> {
                    require(i + 1 < s.length) { "dangling escape at index $i" }
                    when (val esc = s[i + 1]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            require(i + 5 < s.length) { "truncated \\u escape at index $i" }
                            val hex = s.substring(i + 2, i + 6)
                            sb.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> throw IllegalArgumentException("bad escape '\\$esc' at index $i")
                    }
                    i += 2
                }
                else -> { sb.append(c); i++ }
            }
        }
        throw IllegalArgumentException("unterminated string starting at index $start")
    }

    /**
     * Parse a JSON number starting at [start]; returns its truncated Int value and the
     * index just past the last digit. The byte-scores are non-negative integers, but a
     * fractional/exponent form is tolerated and truncated toward zero.
     */
    private fun parseNumber(s: String, start: Int): Pair<Int, Int> {
        var i = start
        val begin = i
        if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
        while (i < s.length) {
            val c = s[i]
            if (c in '0'..'9' || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++ else break
        }
        require(i > begin) { "expected a number at index $start" }
        val token = s.substring(begin, i)
        val value = token.toDoubleOrNull()
            ?: throw IllegalArgumentException("malformed number '$token' at index $start")
        return value.toInt() to i
    }
}
