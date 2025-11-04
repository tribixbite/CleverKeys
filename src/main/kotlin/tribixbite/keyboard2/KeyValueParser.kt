package tribixbite.keyboard2

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Parser for keyboard key definitions with support for both modern and legacy syntax.
 *
 * Modern Syntax:
 * - [(symbol):(key_action)]
 * - Symbol before colon, action after
 * - Example: "a:'A'" → String key "A" with symbol "a"
 * - Example: "enter:keyevent:66" → Keyevent with code 66
 * - Example: "macro:a,b,c" → Macro of multiple keys
 *
 * Legacy Syntax (backwards compatibility):
 * - [:kind attributes:payload]
 * - Starts with colon
 * - Example: ":str flags=dim,small symbol='MyKey':'My string'"
 * - Example: ":char:'a'"
 * - Example: ":keyevent:66"
 *
 * Key Actions:
 * - 'Arbitrary string' → String key
 * - (key_name) → Special key by name (shift, ctrl, etc.)
 * - keyevent:(code) → Android key event
 * - (action),(action),... → Macro sequence
 *
 * Attributes (legacy):
 * - flags: dim, small
 * - symbol: Custom symbol override
 *
 * For detailed documentation, see doc/Possible-key-values.md
 *
 * Ported from Java to Kotlin with improvements.
 */
object KeyValueParser {

    /**
     * Exception thrown when key definition parsing fails
     */
    class ParseError(message: String) : Exception(message)

    /**
     * Convert Int flags bitmask to Flag varargs
     */
    private fun intToFlags(flagsInt: Int): Array<KeyValue.Flag> {
        val flags = mutableListOf<KeyValue.Flag>()

        if (flagsInt and (1 shl 6) != 0) flags.add(KeyValue.Flag.SECONDARY)
        if (flagsInt and (1 shl 5) != 0) flags.add(KeyValue.Flag.SMALLER_FONT)
        if (flagsInt and (1 shl 0) != 0) flags.add(KeyValue.Flag.LATCH)
        if (flagsInt and (1 shl 1) != 0) flags.add(KeyValue.Flag.DOUBLE_TAP_LOCK)
        if (flagsInt and (1 shl 2) != 0) flags.add(KeyValue.Flag.SPECIAL)
        if (flagsInt and (1 shl 3) != 0) flags.add(KeyValue.Flag.GREYED)
        if (flagsInt and (1 shl 4) != 0) flags.add(KeyValue.Flag.KEY_FONT)

        return flags.toTypedArray()
    }

    // Lazy initialization of regex patterns
    private val KEYDEF_TOKEN by lazy {
        Pattern.compile("'|,|keyevent:|(?:[^\\\\',]+|\\\\.)+")
    }

    private val QUOTED_PAT by lazy {
        Pattern.compile("((?:[^'\\\\]+|\\\\')*)'")
    }

    private val WORD_PAT by lazy {
        Pattern.compile("[a-zA-Z0-9_]+|.")
    }

    /**
     * Parse a key definition string into a KeyValue object
     *
     * @param input Key definition string
     * @return Parsed KeyValue object
     * @throws ParseError If syntax is invalid
     */
    @Throws(ParseError::class)
    fun parse(input: String): KeyValue {
        // Find first colon to determine syntax variant
        val symbolEnds = input.indexOf(':')

        when {
            // Legacy syntax: starts with ':'
            symbolEnds == 0 -> {
                return StartingWithColon.parse(input)
            }

            // No colon: simple string key
            symbolEnds < 0 -> {
                return KeyValue.makeStringKey(input)
            }

            // Modern syntax: symbol before colon
            else -> {
                val symbol = input.substring(0, symbolEnds)
                val matcher = KEYDEF_TOKEN.matcher(input)
                matcher.region(symbolEnds + 1, input.length)

                // Parse first key definition
                val firstKey = parseKeyDef(matcher)

                // Check if this is a macro (has comma-separated actions)
                if (!parseComma(matcher)) {
                    // Single key with symbol
                    return firstKey.withSymbol(symbol)
                }

                // Macro: collect all key definitions
                val keyDefs = mutableListOf(firstKey)
                do {
                    keyDefs.add(parseKeyDef(matcher))
                } while (parseComma(matcher))

                return KeyValue.makeMacro(symbol, keyDefs.toTypedArray())
            }
        }
    }

    /**
     * Get key by name or create string key
     */
    private fun keyByNameOrStr(str: String): KeyValue {
        return KeyValue.getSpecialKeyByName(str) ?: KeyValue.makeStringKey(str)
    }

    /**
     * Parse a single key definition token
     */
    @Throws(ParseError::class)
    private fun parseKeyDef(matcher: Matcher): KeyValue {
        if (!match(matcher, KEYDEF_TOKEN)) {
            parseError("Expected key definition", matcher)
        }

        val token = matcher.group(0)

        return when (token) {
            "'" -> parseStringKeyDef(matcher)
            "," -> {
                parseError("Unexpected comma", matcher)
                throw IllegalStateException("Unreachable")
            }
            "keyevent:" -> parseKeyEventKeyDef(matcher)
            else -> keyByNameOrStr(removeEscaping(token))
        }
    }

    /**
     * Parse quoted string key definition
     */
    @Throws(ParseError::class)
    private fun parseStringKeyDef(matcher: Matcher): KeyValue {
        if (!match(matcher, QUOTED_PAT)) {
            parseError("Unterminated quoted string", matcher)
        }
        return KeyValue.makeStringKey(removeEscaping(matcher.group(1)))
    }

    /**
     * Parse keyevent key definition
     */
    @Throws(ParseError::class)
    private fun parseKeyEventKeyDef(matcher: Matcher): KeyValue {
        if (!match(matcher, WORD_PAT)) {
            parseError("Expected keyevent code", matcher)
        }

        val eventCode = try {
            matcher.group(0).toInt()
        } catch (e: NumberFormatException) {
            parseError("Expected an integer payload", matcher)
            0 // Unreachable
        }

        return KeyValue.makeKeyEventKey("", eventCode)
    }

    /**
     * Parse comma separator
     *
     * @return true if comma found, false if at end of input
     * @throws ParseError if unexpected token found
     */
    @Throws(ParseError::class)
    private fun parseComma(matcher: Matcher): Boolean {
        if (!match(matcher, KEYDEF_TOKEN)) {
            return false
        }

        val token = matcher.group(0)
        if (token != ",") {
            parseError("Expected comma instead of '$token'", matcher)
        }

        return true
    }

    /**
     * Remove escape sequences from string
     */
    private fun removeEscaping(s: String): String {
        if (!s.contains('\\')) {
            return s
        }

        val out = StringBuilder(s.length)
        var prev = 0

        for (i in s.indices) {
            if (s[i] == '\\') {
                out.append(s, prev, i)
                prev = i + 1
            }
        }

        out.append(s, prev, s.length)
        return out.toString()
    }

    /**
     * Match pattern at current position
     */
    private fun match(matcher: Matcher, pattern: Pattern): Boolean {
        try {
            matcher.region(matcher.end(), matcher.regionEnd())
        } catch (e: Exception) {
            // Region is exhausted
        }
        matcher.usePattern(pattern)
        return matcher.lookingAt()
    }

    /**
     * Throw parse error with context
     */
    @Throws(ParseError::class)
    private fun parseError(msg: String, matcher: Matcher, position: Int = matcher.regionStart()): Nothing {
        val errorMsg = buildString {
            append("Syntax error")

            // Try to include current token
            try {
                append(" at token '")
                append(matcher.group(0))
                append("'")
            } catch (e: IllegalStateException) {
                // No match available
            }

            append(" at position ")
            append(position)
            append(": ")
            append(msg)
        }

        throw ParseError(errorMsg)
    }

    /**
     * Parser for legacy syntax starting with ':'
     *
     * Kept for backwards compatibility with older key definitions.
     * Format: [:kind attributes:payload]
     */
    private object StartingWithColon {

        // Lazy initialization of regex patterns
        private val START_PAT by lazy {
            Pattern.compile(":(\\w+)")
        }

        private val ATTR_PAT by lazy {
            Pattern.compile("\\s*(\\w+)\\s*=")
        }

        private val QUOTED_PAT by lazy {
            Pattern.compile("'(([^'\\\\]+|\\\\')*)'")
        }

        private val PAYLOAD_START_PAT by lazy {
            Pattern.compile("\\s*:")
        }

        private val WORD_PAT by lazy {
            Pattern.compile("[a-zA-Z0-9_]*")
        }

        /**
         * Parse legacy syntax key definition
         */
        @Throws(ParseError::class)
        fun parse(str: String): KeyValue {
            var symbol: String? = null
            var flags = 0

            // Parse kind
            val matcher = START_PAT.matcher(str)
            if (!matcher.lookingAt()) {
                parseError("Expected kind, for example \":str ...\".", matcher)
            }
            val kind = matcher.group(1)

            // Parse attributes
            while (true) {
                if (!match(matcher, ATTR_PAT)) {
                    break
                }

                val attrName = matcher.group(1)
                val attrValue = parseSingleQuotedString(matcher)

                when (attrName) {
                    "flags" -> flags = parseFlags(attrValue, matcher)
                    "symbol" -> symbol = attrValue
                    else -> parseError("Unknown attribute $attrName", matcher)
                }
            }

            // Parse payload
            if (!match(matcher, PAYLOAD_START_PAT)) {
                parseError("Unexpected character", matcher)
            }

            return when (kind) {
                "str" -> {
                    val payload = parseSingleQuotedString(matcher)
                    val flagsArray = intToFlags(flags)
                    val key = KeyValue.makeStringKey(payload, *flagsArray)
                    symbol?.let { key.withSymbol(it) } ?: key
                }

                "char" -> {
                    val payload = parsePayloadWord(matcher)
                    if (payload.length != 1) {
                        parseError("Expected a single character payload", matcher)
                    }
                    val flagsArray = intToFlags(flags)
                    KeyValue.makeCharKey(payload[0], symbol, *flagsArray)
                }

                "keyevent" -> {
                    val payload = parsePayloadWord(matcher)
                    val eventCode = try {
                        payload.toInt()
                    } catch (e: NumberFormatException) {
                        parseError("Expected an integer payload", matcher)
                        0 // Unreachable
                    }
                    val flagsArray = intToFlags(flags)
                    KeyValue.makeKeyEventKey(symbol ?: eventCode.toString(), eventCode, *flagsArray)
                }

                else -> parseError("Unknown kind '$kind'", matcher, 1)
            }
        }

        /**
         * Parse single-quoted string
         */
        @Throws(ParseError::class)
        private fun parseSingleQuotedString(matcher: Matcher): String {
            if (!match(matcher, QUOTED_PAT)) {
                parseError("Expected quoted string", matcher)
            }
            return matcher.group(1).replace("\\'", "'")
        }

        /**
         * Parse word payload
         */
        @Throws(ParseError::class)
        private fun parsePayloadWord(matcher: Matcher): String {
            if (!match(matcher, WORD_PAT)) {
                parseError("Expected a word after ':' made of [a-zA-Z0-9_]", matcher)
            }
            return matcher.group(0)
        }

        /**
         * Parse flags attribute
         */
        @Throws(ParseError::class)
        private fun parseFlags(s: String, matcher: Matcher): Int {
            var flags = 0

            for (flag in s.split(",")) {
                flags = flags or when (flag) {
                    "dim" -> (1 shl 6)  // SECONDARY
                    "small" -> (1 shl 5)  // SMALLER_FONT
                    else -> parseError("Unknown flag $flag", matcher)
                }
            }

            return flags
        }

        /**
         * Match pattern at current position
         */
        private fun match(matcher: Matcher, pattern: Pattern): Boolean {
            try {
                matcher.region(matcher.end(), matcher.regionEnd())
            } catch (e: Exception) {
                // Region is exhausted
            }
            matcher.usePattern(pattern)
            return matcher.lookingAt()
        }

        /**
         * Throw parse error with context
         */
        @Throws(ParseError::class)
        private fun parseError(
            msg: String,
            matcher: Matcher,
            position: Int = matcher.regionStart()
        ): Nothing {
            val errorMsg = buildString {
                append("Syntax error")

                try {
                    append(" at token '")
                    append(matcher.group(0))
                    append("'")
                } catch (e: IllegalStateException) {
                    // No match available
                }

                append(" at position ")
                append(position)
                append(": ")
                append(msg)
            }

            throw ParseError(errorMsg)
        }
    }
}
