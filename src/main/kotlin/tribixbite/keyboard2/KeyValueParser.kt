package tribixbite.keyboard2

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
Parse a key definition. The syntax for a key definition is:
- [(symbol):(key_action)]
- [:(kind) (attributes):(payload)].
- If [str] doesn't start with a [:] character, it is interpreted as an
  arbitrary string key.

[key_action] is:
- ['Arbitrary string']
- [(key_action),(key_action),...]
- [keyevent:(code)]
- [(key_name)]

For the different kinds and attributes, see doc/Possible-key-values.md.

Examples:
- [:str flags=dim,small symbol='MyKey':'My arbitrary string'].
- [:str:'My arbitrary string'].

*/
object KeyValueParser {
    private lateinit var KEYDEF_TOKEN: Pattern
    private lateinit var QUOTED_PAT: Pattern
    private lateinit var WORD_PAT: Pattern

    @JvmStatic
    @Throws(ParseError::class)
    fun parse(input: String): KeyValue {
        val input_len = input.length
        var symbol_ends = 0
        while (symbol_ends < input_len && input[symbol_ends] != ':') {
            symbol_ends++
        }

        if (symbol_ends == 0) { // Old syntax
            return StartingWithColon.parse(input)
        }
        if (symbol_ends == input_len) { // String key
            return KeyValue.makeStringKey(input)
        }

        val symbol = input.substring(0, symbol_ends)
        init()
        val m = KEYDEF_TOKEN.matcher(input)
        m.region(symbol_ends + 1, input_len)
        val first_key = parseKeyDef(m)

        if (!parseComma(m)) { // Input is a single key def with a specified symbol
            return first_key.withSymbol(symbol)
        }

        // Input is a macro
        val keydefs = mutableListOf(first_key)
        do {
            keydefs.add(parseKeyDef(m))
        } while (parseComma(m))

        return KeyValue.makeMacro(symbol, keydefs.toTypedArray(), 0)
    }

    private fun init() {
        if (::KEYDEF_TOKEN.isInitialized) return

        KEYDEF_TOKEN = Pattern.compile("'|,|keyevent:|(?:[^\\\\',]+|\\\\.)+")
        QUOTED_PAT = Pattern.compile("((?:[^'\\\\]+|\\\\')*)'")
        WORD_PAT = Pattern.compile("[a-zA-Z0-9_]+|.")
    }

    private fun keyByNameOrStr(str: String): KeyValue {
        return KeyValue.getSpecialKeyByName(str) ?: KeyValue.makeStringKey(str)
    }

    private fun parseKeyDef(m: Matcher): KeyValue {
        if (!match(m, KEYDEF_TOKEN)) {
            parseError("Expected key definition", m)
        }
        val token = m.group(0)
        return when (token) {
            "'" -> parseStringKeydef(m)
            "," -> {
                parseError("Unexpected comma", m)
                KeyValue.makeStringKey("") // Unreachable
            }
            "keyevent:" -> parseKeyeventKeydef(m)
            else -> keyByNameOrStr(removeEscaping(token))
        }
    }

    private fun parseStringKeydef(m: Matcher): KeyValue {
        if (!match(m, QUOTED_PAT)) {
            parseError("Unterminated quoted string", m)
        }
        return KeyValue.makeStringKey(removeEscaping(m.group(1)))
    }

    private fun parseKeyeventKeydef(m: Matcher): KeyValue {
        if (!match(m, WORD_PAT)) {
            parseError("Expected keyevent code", m)
        }
        val eventcode = try {
            m.group(0).toInt()
        } catch (e: Exception) {
            parseError("Expected an integer payload", m)
            0 // Unreachable
        }
        return KeyValue.keyeventKey("", eventcode, 0)
    }

    /** Returns [true] if the next token is a comma, [false] if it is the end of the input. Throws an error otherwise. */
    private fun parseComma(m: Matcher): Boolean {
        if (!match(m, KEYDEF_TOKEN)) {
            return false
        }
        val token = m.group(0)
        if (token != ",") {
            parseError("Expected comma instead of '$token'", m)
        }
        return true
    }

    private fun removeEscaping(s: String): String {
        if (!s.contains("\\")) {
            return s
        }
        val out = StringBuilder(s.length)
        val len = s.length
        var prev = 0
        var i = 0
        while (i < len) {
            if (s[i] == '\\') {
                out.append(s, prev, i)
                prev = i + 1
            }
            i++
        }
        out.append(s, prev, i)
        return out.toString()
    }

    /**
      Parse a key definition starting with a [:]. This is the old syntax and is
      kept for compatibility.
      */
    object StartingWithColon {
        private lateinit var START_PAT: Pattern
        private lateinit var ATTR_PAT: Pattern
        private lateinit var QUOTED_PAT: Pattern
        private lateinit var PAYLOAD_START_PAT: Pattern
        private lateinit var WORD_PAT: Pattern

        @JvmStatic
        fun parse(str: String): KeyValue {
            var symbol: String? = null
            var flags = 0
            init()

            // Kind
            val m = START_PAT.matcher(str)
            if (!m.lookingAt()) {
                parseError("Expected kind, for example \":str ...\".", m)
            }
            val kind = m.group(1)

            // Attributes
            while (true) {
                if (!match(m, ATTR_PAT)) break

                val attr_name = m.group(1)
                val attr_value = parseSingleQuotedString(m)

                when (attr_name) {
                    "flags" -> flags = parseFlags(attr_value, m)
                    "symbol" -> symbol = attr_value
                    else -> parseError("Unknown attribute $attr_name", m)
                }
            }

            // Payload
            if (!match(m, PAYLOAD_START_PAT)) {
                parseError("Unexpected character", m)
            }

            val payload: String
            when (kind) {
                "str" -> {
                    payload = parseSingleQuotedString(m)
                    return if (symbol == null) {
                        KeyValue.makeStringKey(payload, flags)
                    } else {
                        KeyValue.makeStringKey(payload, flags).withSymbol(symbol)
                    }
                }

                "char" -> {
                    payload = parsePayloadWord(m)
                    if (payload.length != 1) {
                        parseError("Expected a single character payload", m)
                    }
                    return KeyValue.makeCharKey(payload[0], symbol, flags)
                }

                "keyevent" -> {
                    payload = parsePayloadWord(m)
                    val eventcode = try {
                        payload.toInt()
                    } catch (e: Exception) {
                        parseError("Expected an integer payload", m)
                        0 // Unreachable
                    }
                    val finalSymbol = symbol ?: eventcode.toString()
                    return KeyValue.keyeventKey(finalSymbol, eventcode, flags)
                }

                else -> {
                    parseError("Unknown kind '$kind'", m, 1)
                    return KeyValue.makeStringKey("") // Unreachable
                }
            }
        }

        private fun parseSingleQuotedString(m: Matcher): String {
            if (!match(m, QUOTED_PAT)) {
                parseError("Expected quoted string", m)
            }
            return m.group(1).replace("\\'", "'")
        }

        private fun parsePayloadWord(m: Matcher): String {
            if (!match(m, WORD_PAT)) {
                parseError("Expected a word after ':' made of [a-zA-Z0-9_]", m)
            }
            return m.group(0)
        }

        private fun parseFlags(s: String, m: Matcher): Int {
            var flags = 0
            for (f in s.split(",")) {
                when (f) {
                    "dim" -> flags = flags or KeyValue.FLAG_SECONDARY
                    "small" -> flags = flags or KeyValue.FLAG_SMALLER_FONT
                    else -> parseError("Unknown flag $f", m)
                }
            }
            return flags
        }

        private fun match(m: Matcher, pat: Pattern): Boolean {
            try {
                m.region(m.end(), m.regionEnd())
            } catch (e: Exception) {
            }
            m.usePattern(pat)
            return m.lookingAt()
        }

        private fun init() {
            if (::START_PAT.isInitialized) return

            START_PAT = Pattern.compile(":(\\w+)")
            ATTR_PAT = Pattern.compile("\\s*(\\w+)\\s*=")
            QUOTED_PAT = Pattern.compile("'(([^'\\\\]+|\\\\')*)'")
            PAYLOAD_START_PAT = Pattern.compile("\\s*:")
            WORD_PAT = Pattern.compile("[a-zA-Z0-9_]*")
        }
    }

    private fun match(m: Matcher, pat: Pattern): Boolean {
        try {
            m.region(m.end(), m.regionEnd())
        } catch (e: Exception) {
        }
        m.usePattern(pat)
        return m.lookingAt()
    }

    private fun parseError(msg: String, m: Matcher, i: Int = m.regionStart()): Nothing {
        val msg_ = StringBuilder("Syntax error")
        try {
            msg_.append(" at token '").append(m.group(0)).append("'")
        } catch (e: IllegalStateException) {
        }
        msg_.append(" at position ")
        msg_.append(i)
        msg_.append(": ")
        msg_.append(msg)
        throw ParseError(msg_.toString())
    }

    class ParseError(msg: String) : Exception(msg)
}
