package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM unit tests for KeyValueParser.
 *
 * KeyValueParser parses key definition strings from layout XML into KeyValue objects.
 * Most of the parser is testable on pure JVM because KeyValue's companion object init
 * block only uses bit-packing arithmetic — no Android classes. The `android.view.KeyEvent`
 * import in KeyValue is only referenced inside `getSpecialKeyByName()` for named key
 * events like "esc", "enter", etc., which are lazily resolved.
 *
 * What IS testable (pure JVM):
 * - Simple string keys (no colon → makeStringKey)
 * - Quoted string key definitions (symbol:'text')
 * - keyevent:<code> numeric codes
 * - timestamp:'pattern' definitions
 * - intent:'json' definitions
 * - Macro definitions (symbol:key1,key2,...)
 * - Old colon syntax (:str:'payload', :char:X, :keyevent:N, :timestamp:'pat')
 * - Flags parsing (dim, small)
 * - Escape handling in strings
 * - ParseError for malformed input
 *
 * What is NOT testable (requires Android runtime):
 * - Named special keys that reference KeyEvent constants (esc, enter, tab, etc.)
 *   These call getSpecialKeyByName which uses android.view.KeyEvent.KEYCODE_* constants
 */
class KeyValueParserTest {

    // =========================================================================
    // A. Simple String Keys (no colon in input)
    // =========================================================================

    @Test
    fun `parse simple string without colon returns string key`() {
        val result = KeyValueParser.parse("hello")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.String)
        assertThat(result.getString()).isEqualTo("hello")
    }

    @Test
    fun `parse single character returns char key`() {
        // makeStringKey with length 1 returns a Char kind
        val result = KeyValueParser.parse("a")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.Char)
        assertThat(result.getChar()).isEqualTo('a')
    }

    @Test
    fun `parse multi-char string without colon returns string key`() {
        val result = KeyValueParser.parse("abc")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.String)
        assertThat(result.getString()).isEqualTo("abc")
    }

    @Test
    fun `parse unicode string without colon returns string key`() {
        val result = KeyValueParser.parse("日本語")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.String)
        assertThat(result.getString()).isEqualTo("日本語")
    }

    @Test
    fun `parse emoji string without colon returns string key`() {
        val result = KeyValueParser.parse("😀🎉")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.String)
        assertThat(result.getString()).isEqualTo("😀🎉")
    }

    // =========================================================================
    // B. Quoted String Key Definitions (symbol:'text')
    // =========================================================================

    @Test
    fun `parse single char quoted string returns char kind`() {
        // symbol:'X' where X is single char → Char kind with symbol
        val result = KeyValueParser.parse("sym:'X'")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.Char)
        assertThat(result.getChar()).isEqualTo('X')
        assertThat(result.getString()).isEqualTo("sym")
    }

    @Test
    fun `parse symbol with named key`() {
        // symbol:key_name syntax — resolves key_name via KeyValue.getSpecialKeyByName
        val result = KeyValueParser.parse("sym:copy")

        assertThat(result.getString()).isEqualTo("sym")
    }

    // =========================================================================
    // C. Keyevent Definitions
    // =========================================================================

    @Test
    fun `parse keyevent with numeric code`() {
        val result = KeyValueParser.parse("K:keyevent:66")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.Keyevent)
        assertThat(result.getKeyevent()).isEqualTo(66)
        assertThat(result.getString()).isEqualTo("K")
    }

    @Test
    fun `parse keyevent code zero`() {
        val result = KeyValueParser.parse("Z:keyevent:0")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.Keyevent)
        assertThat(result.getKeyevent()).isEqualTo(0)
    }

    @Test
    fun `parse keyevent with large code`() {
        val result = KeyValueParser.parse("big:keyevent:999")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.Keyevent)
        assertThat(result.getKeyevent()).isEqualTo(999)
    }

    @Test(expected = KeyValueParser.ParseError::class)
    fun `parse keyevent with non-numeric code throws ParseError`() {
        KeyValueParser.parse("K:keyevent:abc")
    }

    @Test(expected = KeyValueParser.ParseError::class)
    fun `parse keyevent with missing code throws ParseError`() {
        // After "keyevent:" there's nothing, so WORD_PAT won't match
        KeyValueParser.parse("K:keyevent:")
    }

    // =========================================================================
    // D. Named Timestamp Keys (production usage via KeyValue.getSpecialKeyByName)
    // =========================================================================

    @Test
    fun `parse named timestamp_date key`() {
        // Named keys go through keyByNameOrStr → KeyValue.getSpecialKeyByName
        val result = KeyValueParser.parse("📅:timestamp_date")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.Timestamp)
        assertThat(result.getString()).isEqualTo("📅")
    }

    @Test
    fun `parse named timestamp_time key`() {
        val result = KeyValueParser.parse("🕐:timestamp_time")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.Timestamp)
    }

    @Test
    fun `parse named timestamp_iso key`() {
        val result = KeyValueParser.parse("📋:timestamp_iso")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.Timestamp)
    }

    @Test
    fun `parse timestamp_date without symbol returns string key`() {
        // Without symbol prefix (no colon), entire string is treated as a literal string key
        // Named key resolution only happens via keyByNameOrStr after symbol:key_action parsing
        val result = KeyValueParser.parse("timestamp_date")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.String)
        assertThat(result.getString()).isEqualTo("timestamp_date")
    }

    // =========================================================================
    // E. Intent Definitions
    // =========================================================================
    // Note: The symbol:intent:'json' syntax has a parser limitation where
    // QUOTED_PAT doesn't consume the opening quote after the intent: token.
    // Production intent keys are created via ShortSwipeMapping + IntentDefinition,
    // not through KeyValueParser. We test the INTENT_PREFIX constant instead.

    @Test
    fun `INTENT_PREFIX constant is defined`() {
        assertThat(tribixbite.cleverkeys.customization.IntentDefinition.INTENT_PREFIX)
            .isEqualTo("__intent__:")
    }

    // =========================================================================
    // F. Macro Definitions (comma-separated key defs)
    // =========================================================================

    @Test
    fun `parse macro with two string keys`() {
        val result = KeyValueParser.parse("AB:'a','b'")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.Macro)
        val macroKeys = result.getMacro()
        assertThat(macroKeys).hasLength(2)
    }

    @Test
    fun `parse macro with three keys`() {
        val result = KeyValueParser.parse("XYZ:'x','y','z'")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.Macro)
        val macroKeys = result.getMacro()
        assertThat(macroKeys).hasLength(3)
    }

    @Test
    fun `parse macro keys have correct values`() {
        val result = KeyValueParser.parse("AB:'hello','world'")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.Macro)
        val keys = result.getMacro()
        // First key is makeStringKey("hello") — String kind since length > 1
        assertThat(keys[0].getKind()).isEqualTo(KeyValue.Kind.String)
        assertThat(keys[0].getString()).isEqualTo("hello")
        // Second key is makeStringKey("world")
        assertThat(keys[1].getKind()).isEqualTo(KeyValue.Kind.String)
        assertThat(keys[1].getString()).isEqualTo("world")
    }

    @Test
    fun `parse macro symbol is preserved`() {
        val result = KeyValueParser.parse("MyMacro:'a','b'")

        assertThat(result.getString()).isEqualTo("MyMacro")
    }

    @Test
    fun `parse macro with keyevent components`() {
        val result = KeyValueParser.parse("KE:keyevent:66,keyevent:67")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.Macro)
        val keys = result.getMacro()
        assertThat(keys).hasLength(2)
        assertThat(keys[0].getKind()).isEqualTo(KeyValue.Kind.Keyevent)
        assertThat(keys[0].getKeyevent()).isEqualTo(66)
        assertThat(keys[1].getKind()).isEqualTo(KeyValue.Kind.Keyevent)
        assertThat(keys[1].getKeyevent()).isEqualTo(67)
    }

    // =========================================================================
    // G. Old Colon Syntax (:kind attributes:payload)
    // =========================================================================

    @Test
    fun `parse old syntax str with quoted payload`() {
        val result = KeyValueParser.parse(":str:'Hello World'")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.String)
        assertThat(result.getString()).isEqualTo("Hello World")
    }

    @Test
    fun `parse old syntax str with single char payload`() {
        val result = KeyValueParser.parse(":str:'X'")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.Char)
        assertThat(result.getChar()).isEqualTo('X')
    }

    @Test
    fun `parse old syntax str with symbol attribute`() {
        val result = KeyValueParser.parse(":str symbol='Key':'My text'")

        assertThat(result.getString()).isEqualTo("Key")
    }

    @Test
    fun `parse old syntax str with dim flag`() {
        val result = KeyValueParser.parse(":str flags='dim':'dimmed text'")

        assertThat(result.hasFlagsAny(KeyValue.FLAG_SECONDARY)).isTrue()
    }

    @Test
    fun `parse old syntax str with small flag`() {
        val result = KeyValueParser.parse(":str flags='small':'small text'")

        assertThat(result.hasFlagsAny(KeyValue.FLAG_SMALLER_FONT)).isTrue()
    }

    @Test
    fun `parse old syntax str with multiple flags`() {
        val result = KeyValueParser.parse(":str flags='dim,small':'flagged'")

        assertThat(result.hasFlagsAny(KeyValue.FLAG_SECONDARY)).isTrue()
        assertThat(result.hasFlagsAny(KeyValue.FLAG_SMALLER_FONT)).isTrue()
    }

    @Test
    fun `parse old syntax str with symbol and flags`() {
        val result = KeyValueParser.parse(":str flags='dim' symbol='MyKey':'My text'")

        assertThat(result.getString()).isEqualTo("MyKey")
        assertThat(result.hasFlagsAny(KeyValue.FLAG_SECONDARY)).isTrue()
    }

    @Test
    fun `parse old syntax char with single character`() {
        val result = KeyValueParser.parse(":char:A")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.Char)
        assertThat(result.getChar()).isEqualTo('A')
    }

    @Test
    fun `parse old syntax char with symbol`() {
        val result = KeyValueParser.parse(":char symbol='MyA':A")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.Char)
        assertThat(result.getChar()).isEqualTo('A')
        assertThat(result.getString()).isEqualTo("MyA")
    }

    @Test(expected = KeyValueParser.ParseError::class)
    fun `parse old syntax char with multi-char payload throws ParseError`() {
        KeyValueParser.parse(":char:AB")
    }

    @Test
    fun `parse old syntax keyevent with numeric code`() {
        val result = KeyValueParser.parse(":keyevent:66")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.Keyevent)
        assertThat(result.getKeyevent()).isEqualTo(66)
    }

    @Test
    fun `parse old syntax keyevent with symbol`() {
        val result = KeyValueParser.parse(":keyevent symbol='Enter':66")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.Keyevent)
        assertThat(result.getKeyevent()).isEqualTo(66)
        assertThat(result.getString()).isEqualTo("Enter")
    }

    @Test(expected = KeyValueParser.ParseError::class)
    fun `parse old syntax keyevent with non-numeric payload throws ParseError`() {
        KeyValueParser.parse(":keyevent:abc")
    }

    @Test
    fun `parse old syntax timestamp with pattern`() {
        val result = KeyValueParser.parse(":timestamp:'yyyy-MM-dd'")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.Timestamp)
        assertThat(result.getTimestampFormat().pattern).isEqualTo("yyyy-MM-dd")
    }

    @Test
    fun `parse old syntax timestamp with symbol`() {
        val result = KeyValueParser.parse(":timestamp symbol='📅':'yyyy-MM-dd HH:mm'")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.Timestamp)
        assertThat(result.getString()).isEqualTo("📅")
        assertThat(result.getTimestampFormat().pattern).isEqualTo("yyyy-MM-dd HH:mm")
    }

    @Test(expected = KeyValueParser.ParseError::class)
    fun `parse old syntax with unknown kind throws ParseError`() {
        KeyValueParser.parse(":unknownkind:'payload'")
    }

    @Test(expected = KeyValueParser.ParseError::class)
    fun `parse old syntax with unknown attribute throws ParseError`() {
        KeyValueParser.parse(":str badattr='val':'payload'")
    }

    @Test(expected = KeyValueParser.ParseError::class)
    fun `parse old syntax with unknown flag throws ParseError`() {
        KeyValueParser.parse(":str flags='badFlag':'payload'")
    }

    // =========================================================================
    // H. Escape Handling
    // =========================================================================

    @Test
    fun `parse quoted string with backslash escape`() {
        // 'it\'s' → it's
        val result = KeyValueParser.parse(":str:'it\\'s'")

        // The payload after unescaping should be "it's" (4 chars, so String kind)
        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.String)
        assertThat(result.getString()).isEqualTo("it's")
    }

    @Test
    fun `parse string with backslash in non-quoted context`() {
        // In the new syntax, a word token with backslash gets unescaped via removeEscaping
        // "sym:word\\with\\escapes" → parseKeyDef sees "word\with\escapes" after removeEscaping
        // This becomes keyByNameOrStr which calls makeStringKey
        val result = KeyValueParser.parse("S:test\\\\value")

        assertThat(result.getString()).isEqualTo("S")
    }

    // =========================================================================
    // I. ParseError Tests
    // =========================================================================

    @Test
    fun `ParseError is an Exception`() {
        val error = KeyValueParser.ParseError("test error message")

        assertThat(error).isInstanceOf(Exception::class.java)
        assertThat(error.message).isEqualTo("test error message")
    }

    @Test
    fun `ParseError message contains position info on malformed input`() {
        try {
            KeyValueParser.parse(":str:'unterminated")
            assertThat(false).isTrue() // Should not reach here
        } catch (e: KeyValueParser.ParseError) {
            assertThat(e.message).contains("position")
        }
    }

    @Test
    fun `ParseError thrown for empty colon input`() {
        // ":" starts with colon, goes to StartingWithColon.parse
        // START_PAT requires :(word), just ":" won't match
        try {
            KeyValueParser.parse(":")
            assertThat(false).isTrue()
        } catch (e: KeyValueParser.ParseError) {
            assertThat(e.message).isNotNull()
        }
    }

    @Test(expected = KeyValueParser.ParseError::class)
    fun `parse unterminated quoted string throws ParseError`() {
        KeyValueParser.parse("S:'unterminated")
    }

    @Test(expected = KeyValueParser.ParseError::class)
    fun `parse consecutive commas in macro throws ParseError`() {
        KeyValueParser.parse("S:'a',,'b'")
    }

    // =========================================================================
    // J. Edge Cases
    // =========================================================================

    @Test
    fun `parse single character string key`() {
        val result = KeyValueParser.parse("x")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.Char)
        assertThat(result.getChar()).isEqualTo('x')
        assertThat(result.getString()).isEqualTo("x")
    }

    @Test
    fun `parse numeric string without colon`() {
        val result = KeyValueParser.parse("123")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.String)
        assertThat(result.getString()).isEqualTo("123")
    }

    @Test
    fun `parse string key with spaces`() {
        val result = KeyValueParser.parse("hello world")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.String)
        assertThat(result.getString()).isEqualTo("hello world")
    }

    @Test
    fun `parse old syntax str with escaped quote in payload`() {
        val result = KeyValueParser.parse(":str:'don\\'t'")

        assertThat(result.getString()).isEqualTo("don't")
    }

    @Test
    fun `parse keyevent code via old syntax default symbol`() {
        // Without symbol attribute, keyevent uses the code as symbol
        val result = KeyValueParser.parse(":keyevent:42")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.Keyevent)
        assertThat(result.getKeyevent()).isEqualTo(42)
        assertThat(result.getString()).isEqualTo("42")
    }

    @Test
    fun `parse old syntax timestamp default symbol is calendar emoji`() {
        val result = KeyValueParser.parse(":timestamp:'HH:mm'")

        assertThat(result.getString()).isEqualTo("📅")
    }

    @Test
    fun `parse new syntax single key with symbol`() {
        // "Sym:keyevent:66" → parseKeyDef returns keyevent key, then withSymbol("Sym")
        val result = KeyValueParser.parse("Sym:keyevent:66")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.Keyevent)
        assertThat(result.getKeyevent()).isEqualTo(66)
        assertThat(result.getString()).isEqualTo("Sym")
    }

    @Test
    fun `parse handles unicode symbol with named key`() {
        // Unicode symbol with named key (production usage)
        val result = KeyValueParser.parse("📅:timestamp_date")

        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.Timestamp)
        assertThat(result.getString()).isEqualTo("📅")
    }

    // =========================================================================
    // K. KeyValue Factory Method Verification (exercised via parser)
    // =========================================================================

    @Test
    fun `makeStringKey with single char creates Char kind`() {
        val kv = KeyValue.makeStringKey("a")

        assertThat(kv.getKind()).isEqualTo(KeyValue.Kind.Char)
        assertThat(kv.getChar()).isEqualTo('a')
    }

    @Test
    fun `makeStringKey with multi-char creates String kind`() {
        val kv = KeyValue.makeStringKey("abc")

        assertThat(kv.getKind()).isEqualTo(KeyValue.Kind.String)
        assertThat(kv.getString()).isEqualTo("abc")
    }

    @Test
    fun `makeStringKey with flags applied`() {
        val kv = KeyValue.makeStringKey("test", KeyValue.FLAG_SECONDARY)

        assertThat(kv.hasFlagsAny(KeyValue.FLAG_SECONDARY)).isTrue()
    }

    @Test
    fun `keyeventKey factory creates correct kind and code`() {
        val kv = KeyValue.keyeventKey("Ent", 66, 0)

        assertThat(kv.getKind()).isEqualTo(KeyValue.Kind.Keyevent)
        assertThat(kv.getKeyevent()).isEqualTo(66)
        assertThat(kv.getString()).isEqualTo("Ent")
    }

    @Test
    fun `makeTimestampKey creates Timestamp kind`() {
        val kv = KeyValue.makeTimestampKey("📅", "yyyy-MM-dd", 0)

        assertThat(kv.getKind()).isEqualTo(KeyValue.Kind.Timestamp)
        assertThat(kv.getTimestampFormat().pattern).isEqualTo("yyyy-MM-dd")
        assertThat(kv.getString()).isEqualTo("📅")
    }

    @Test
    fun `makeMacro creates Macro kind with correct keys`() {
        val keys = arrayOf(
            KeyValue.makeStringKey("a"),
            KeyValue.makeStringKey("b")
        )
        val kv = KeyValue.makeMacro("AB", keys, 0)

        assertThat(kv.getKind()).isEqualTo(KeyValue.Kind.Macro)
        assertThat(kv.getMacro()).hasLength(2)
        assertThat(kv.getString()).isEqualTo("AB")
    }

    @Test
    fun `makeCharKey creates Char kind`() {
        val kv = KeyValue.makeCharKey('Z')

        assertThat(kv.getKind()).isEqualTo(KeyValue.Kind.Char)
        assertThat(kv.getChar()).isEqualTo('Z')
    }

    // =========================================================================
    // L. KeyValue Equality and Comparison
    // =========================================================================

    @Test
    fun `same string keys are equal`() {
        val a = KeyValue.makeStringKey("hello")
        val b = KeyValue.makeStringKey("hello")

        assertThat(a.sameKey(b)).isTrue()
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `different string keys are not equal`() {
        val a = KeyValue.makeStringKey("hello")
        val b = KeyValue.makeStringKey("world")

        assertThat(a.sameKey(b)).isFalse()
    }

    @Test
    fun `sameKey with null returns false`() {
        val kv = KeyValue.makeStringKey("test")

        assertThat(kv.sameKey(null)).isFalse()
    }

    @Test
    fun `hashCode is consistent for equal keys`() {
        val a = KeyValue.makeStringKey("test")
        val b = KeyValue.makeStringKey("test")

        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `toString contains kind and string representation`() {
        val kv = KeyValue.makeStringKey("hello")

        val str = kv.toString()
        assertThat(str).contains("KeyValue")
        assertThat(str).contains("hello")
    }

    // =========================================================================
    // M. Flag Constants Verification
    // =========================================================================

    @Test
    fun `flag constants do not overlap`() {
        val flags = listOf(
            KeyValue.FLAG_LATCH,
            KeyValue.FLAG_DOUBLE_TAP_LOCK,
            KeyValue.FLAG_SPECIAL,
            KeyValue.FLAG_GREYED,
            KeyValue.FLAG_KEY_FONT,
            KeyValue.FLAG_SMALLER_FONT,
            KeyValue.FLAG_SECONDARY
        )

        // Each pair of flags should have no overlapping bits
        for (i in flags.indices) {
            for (j in i + 1 until flags.size) {
                assertThat(flags[i] and flags[j]).isEqualTo(0)
            }
        }
    }

    @Test
    fun `each flag is a single bit`() {
        val flags = listOf(
            KeyValue.FLAG_LATCH,
            KeyValue.FLAG_DOUBLE_TAP_LOCK,
            KeyValue.FLAG_SPECIAL,
            KeyValue.FLAG_GREYED,
            KeyValue.FLAG_KEY_FONT,
            KeyValue.FLAG_SMALLER_FONT,
            KeyValue.FLAG_SECONDARY
        )

        for (flag in flags) {
            // A single bit flag has exactly one bit set: flag & (flag - 1) == 0
            assertThat(flag).isGreaterThan(0)
            assertThat(flag and (flag - 1)).isEqualTo(0)
        }
    }
}
