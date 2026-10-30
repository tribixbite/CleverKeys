package tribixbite.cleverkeys.customization

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.KeyValueParser

/**
 * One serializer writes a short-swipe action into layout XML — Export XML and the per-key
 * dialog's modified layout copies both use [XmlAttributeMapper.layoutValue] — and the layout
 * parser must read every value it writes back as the SAME action (maintainer, 2026-10-30).
 *
 * Before: Export XML wrote a bare `'hello'`, `intent:'…'`, `template:'…'`, `timestamp:'…'` and
 * `keyevent:66`. The parser only reads an action after a `label:` prefix, so on import those keys
 * typed their own source (`'hello'` with its quotes; `keyevent:66` typed "66" under the label
 * "keyevent"). Text containing a backslash had no escape at all.
 *
 * Values are read back with [KeyValue.getKeyByName], exactly as the layout parser reads a subkey
 * attribute. Pure tier: [KeyValueParser] is plain Kotlin. The full XML file round trip (escaping, characters
 * outside the BMP, the kxml2 parser) is `XmlLayoutExporterRoundTripTest` (mock tier).
 */
class XmlActionRoundTripTest {

    private fun mapping(type: ActionType, value: String, label: String = "", keyFont: Boolean = false) =
        ShortSwipeMapping("a", SwipeDirection.NE, label, type, value, keyFont)

    private fun roundTrip(m: ShortSwipeMapping): KeyValue {
        val written = XmlAttributeMapper.layoutValue(m)
        assertWithMessage("layout value of $m").that(written).isNotNull()
        // Exactly how a layout file's subkey attribute is read (KeyboardData.parse_key_attr).
        return KeyValue.getKeyByName(written!!)
    }

    private fun assertSameAction(m: ShortSwipeMapping) {
        val parsed = roundTrip(m)
        assertWithMessage("${m.actionType} ${m.actionValue}: parsed $parsed")
            .that(XmlActionRoundTripFixtures.actionOf(parsed))
            .isEqualTo(XmlActionRoundTripFixtures.expectedActionOf(m))
    }

    @Test
    fun textRoundTripsIncludingQuotesBackslashesNewlinesAndUnicode() {
        for (text in listOf(
            "hello", "It's", "C:\\work\\", "a\\'b", "\\", "'", "''", "line1\nline2\r\n\ttab",
            "naïve 日本語 😀👍🏽", "𝕨", "a,b", "x:y:z", "{cursor}", "{uuid}", "@", "€", " ", "loc x", "copy",
        )) {
            assertSameAction(mapping(ActionType.TEXT, text))
        }
    }

    @Test
    fun templatesRoundTripIncludingCursorQuotesAndBackslashes() {
        for (template in listOf(
            "Dear {cursor},", "[{selection}]{cursor}", "C:\\work\\'[{selection}]\\", "it's {clipboard}\n—",
            "{uuid}-{clipboard}", "😀{cursor}😀",
        )) {
            assertSameAction(mapping(ActionType.TEMPLATE, template, "{}"))
        }
    }

    @Test
    fun timestampsRoundTripTheirPatterns() {
        for (pattern in listOf("yyyy-MM-dd", "HH:mm:ss", "yyyy-MM-dd 'at' HH:mm", "yyyy''", "EEE, d MMM", "'日'yyyy")) {
            assertSameAction(mapping(ActionType.TIMESTAMP, pattern, "TS"))
        }
    }

    @Test
    fun intentsRoundTripTheirJsonIncludingExtras() {
        val json = """{"name":"Quoted \"x\"","targetType":"ACTIVITY","action":"android.intent.action.SEND",""" +
            """"type":"text/plain","packageName":"com.example","className":"com.example.Main",""" +
            """"extras":{"android.intent.extra.TEXT":"it's C:\\work\n😀","k,2":"a:b"}}"""
        assertSameAction(mapping(ActionType.INTENT, json, "INT"))
        assertSameAction(mapping(ActionType.INTENT, """{"name":"B","targetType":"BROADCAST","action":"x.y"}"""))
    }

    @Test
    fun keyEventsRoundTripTheirCode() {
        for (code in listOf("66", "61", "4", "122")) assertSameAction(mapping(ActionType.KEY_EVENT, code, "K"))
    }

    @Test
    fun commandsRoundTripAsTheSameKeyIncludingLegacyNames() {
        assertSameAction(mapping(ActionType.COMMAND, "copy"))
        assertSameAction(mapping(ActionType.COMMAND, "voice_input", "\uE001", keyFont = true))
        // A legacy AvailableCommand spelling is the same action as its layout keyword.
        assertThat(roundTrip(mapping(ActionType.COMMAND, "COPY"))).isEqualTo(KeyValue.getKeyByName("copy"))
        assertThat(roundTrip(mapping(ActionType.COMMAND, "SELECT_ALL"))).isEqualTo(KeyValue.getKeyByName("selectAll"))
    }

    @Test
    fun actionsALayoutCannotHoldAreRefusedNotWrittenAsText() {
        // A name only the custom-swipe executor knows would be read as a key TYPING its name.
        assertThat(XmlAttributeMapper.layoutValue(mapping(ActionType.COMMAND, "paste_pinned_1"))).isNull()
        assertThat(XmlAttributeMapper.layoutValue(ShortSwipeMapping.removal("a", SwipeDirection.N))).isNull()
        assertThat(XmlAttributeMapper.layoutValue(mapping(ActionType.TEXT, ""))).isNull()
        // XML 1.0 cannot carry these characters at all.
        assertThat(XmlAttributeMapper.layoutValue(mapping(ActionType.TEXT, "a\u0001b"))).isNull()
        assertThat(XmlAttributeMapper.layoutValue(mapping(ActionType.TEXT, "a\uD800b"))).isNull()
        assertThat(XmlAttributeMapper.layoutValue(mapping(ActionType.KEY_EVENT, "x"))).isNull()
    }

    @Test
    fun theLabelIsTheMappingsOwnWhenALayoutCanCarryIt() {
        assertThat(roundTrip(mapping(ActionType.TEXT, "hello", "hi")).getString()).isEqualTo("hi")
        // No label: the text's first characters, never half of a surrogate pair.
        assertThat(roundTrip(mapping(ActionType.TEXT, "a😀😀z")).getString()).isEqualTo("a😀")
        // ':' ends a label and a key-font glyph has no font in a layout label: a fallback instead.
        assertThat(roundTrip(mapping(ActionType.TEXT, "x", "a:b")).getString()).isEqualTo("x")
        assertThat(roundTrip(mapping(ActionType.KEY_EVENT, "66", "\uE00E", keyFont = true)).getString()).isEqualTo("⌨")
    }

    /**
     * Files exported before the fix import exactly as they did: the parser is unchanged for every
     * input it accepted. A bare quoted value has no `label:` prefix, so it stays a key typing its
     * source (docs/specs/profile_system_restoration.md "Export XML values" says to re-export).
     */
    @Test
    fun oldExportFormsStillImportAsBefore() {
        val text = KeyValue.getKeyByName("'hello'")
        assertThat(text.getKind()).isEqualTo(KeyValue.Kind.String)
        assertThat(text.getString()).isEqualTo("'hello'")
        assertThat(KeyValue.getKeyByName("'It\\'s'").getString()).isEqualTo("'It\\'s'")

        // `prefix:'payload'` read the prefix as the LABEL and typed the payload.
        for ((old, typed) in listOf(
            "intent:'{\"name\":\"x\"}'" to "{\"name\":\"x\"}",
            "template:'Dear {cursor}'" to "Dear {cursor}",
            "timestamp:'HH:mm'" to "HH:mm",
        )) {
            val key = KeyValue.getKeyByName(old)
            assertWithMessage(old).that(key.getKind()).isEqualTo(KeyValue.Kind.Macro)
            assertWithMessage(old).that(key.getString()).isEqualTo(old.substringBefore(':'))
            assertWithMessage(old).that(key.getMacro().single().getString()).isEqualTo(typed)
        }
        // `keyevent:66` typed "66" under the label "keyevent".
        val keyevent = KeyValue.getKeyByName("keyevent:66")
        assertThat(keyevent.getString()).isEqualTo("keyevent")
        assertThat(keyevent.getMacro().single().getString()).isEqualTo("66")
        // Commands were already written in the form the parser reads.
        assertThat(KeyValue.getKeyByName("copy").getKind()).isEqualTo(KeyValue.Kind.Editing)
    }

    /**
     * The one parser change: a quoted string may escape a backslash (`\\`) or any character,
     * as prefixed payloads already could. Every input this changes was a syntax error before.
     */
    @Test
    fun aQuotedStringCanEscapeABackslash() {
        val key = KeyValueParser.parse("x:'C:\\\\work\\\\'")
        assertThat(key.getMacro().single().getString()).isEqualTo("C:\\work\\")
        assertThat(KeyValueParser.parse("x:'it\\'s'").getMacro().single().getString()).isEqualTo("it's")
    }
}

/** Reads a parsed layout value back as an action (shared with the mock-tier XML file round trip). */
internal object XmlActionRoundTripFixtures {

    /** (type, value) a parsed layout key performs; COMMAND keys compare as their [KeyValue]. */
    fun actionOf(kv: KeyValue): Pair<ActionType, Any> = when (kv.getKind()) {
        KeyValue.Kind.Macro -> kv.getMacro().singleOrNull()?.let(::actionOf) ?: (ActionType.COMMAND to kv)
        KeyValue.Kind.Char -> ActionType.TEXT to kv.getChar().toString()
        KeyValue.Kind.String -> {
            val s = kv.getString()
            if (s.startsWith(IntentDefinition.INTENT_PREFIX)) ActionType.INTENT to s.removePrefix(IntentDefinition.INTENT_PREFIX)
            else ActionType.TEXT to s
        }
        KeyValue.Kind.Keyevent -> ActionType.KEY_EVENT to kv.getKeyevent().toString()
        KeyValue.Kind.Template -> ActionType.TEMPLATE to kv.getTemplateFormat().template
        KeyValue.Kind.Timestamp -> ActionType.TIMESTAMP to kv.getTimestampFormat().pattern
        else -> ActionType.COMMAND to kv
    }

    /** What [m] does, in [actionOf]'s terms. */
    fun expectedActionOf(m: ShortSwipeMapping): Pair<ActionType, Any> =
        if (m.actionType == ActionType.COMMAND) ActionType.COMMAND to KeyValue.getKeyByName(m.actionValue)
        else m.actionType to m.actionValue
}
