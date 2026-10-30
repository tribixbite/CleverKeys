package tribixbite.cleverkeys.customization

import android.util.Log
import android.util.Xml
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.KeyboardData

/**
 * [LayoutSubkeyXml] addresses a key by (row, key) position in the XML text; the per-key dialog
 * finds that position in the PARSED layout. This pins that the two agree for every built-in layout
 * (`src/main/layouts`), and that an edit written into a layout's XML reads back as the intended
 * key with everything else unchanged — the guarantee a modified copy relies on.
 *
 * Mock tier: android.util.Xml is a stub, so the functional kxml2 parser is substituted, the same
 * way LayoutLanguageBindingConfigTest drives production parsing.
 */
class LayoutSubkeyBuiltInLayoutsTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        mockkStatic(Xml::class)
        every { Xml.newPullParser() } answers { KXmlParser() }
    }

    @After
    fun teardown() = unmockkAll()

    private val layouts: List<File> =
        File("src/main/layouts").listFiles { f -> f.name.endsWith(".xml") }!!.sortedBy { it.name }

    @Test
    fun everyBuiltInLayoutIsFound() {
        assertThat(layouts.size).isAtLeast(80)
    }

    @Test
    fun xmlPositionsMatchTheParsedLayoutForEveryBuiltIn() {
        for (file in layouts) {
            val xml = file.readText()
            val parsed = KeyboardData.load_string_exn(xml)
            assertWithMessage(file.name)
                .that(LayoutSubkeyXml.keyCounts(xml))
                .containsExactlyElementsIn(parsed.rows.map { it.keys.size }).inOrder()
        }
    }

    @Test
    fun anEditReadsBackAsTheIntendedKeyAndNothingElseChanges() {
        val voice = KeyValue.getKeyByName("voice_input")
        for (file in layouts) {
            val xml = file.readText()
            val before = KeyboardData.load_string_exn(xml)
            // The last key of the first row, north-east: an arbitrary but real slot.
            val r = 0
            val c = before.rows[0].keys.size - 1
            val edited = LayoutSubkeyXml.rename(
                LayoutSubkeyXml.setSubkey(xml, r, c, SwipeDirection.NE, "voice_input"), "Copy"
            )
            val after = KeyboardData.load_string_exn(edited)
            assertWithMessage("${file.name} edited slot")
                .that(after.rows[r].keys[c].keys[SwipeDirection.NE.subLabelIndex]).isEqualTo(voice)
            assertWithMessage("${file.name} name").that(after.name).isEqualTo("Copy")
            assertWithMessage("${file.name} language kept").that(after.declared_language).isEqualTo(before.declared_language)
            assertWithMessage("${file.name} script kept").that(after.script).isEqualTo(before.script)
            for ((ri, row) in before.rows.withIndex()) {
                for ((ki, key) in row.keys.withIndex()) {
                    val other = after.rows[ri].keys[ki]
                    if (ri == r && ki == c) {
                        val expected = key.keys.toMutableList().also { it[SwipeDirection.NE.subLabelIndex] = voice }
                        assertWithMessage("${file.name} [$ri,$ki] other slots").that(other.keys).isEqualTo(expected)
                    } else {
                        assertWithMessage("${file.name} [$ri,$ki]").that(other).isEqualTo(key)
                    }
                }
            }
        }
    }

    @Test
    fun removingADefinedSubkeyLeavesTheSlotEmpty() {
        val xml = File("src/main/layouts/latn_qwerty_us.xml").readText()
        val before = KeyboardData.load_string_exn(xml)
        val (r, c, dir) = before.rows.withIndex().firstNotNullOf { (ri, row) ->
            row.keys.withIndex().firstNotNullOfOrNull { (ki, key) ->
                SwipeDirection.entries.firstOrNull { key.keys.getOrNull(it.subLabelIndex) != null }?.let { Triple(ri, ki, it) }
            }
        }
        val after = KeyboardData.load_string_exn(LayoutSubkeyXml.setSubkey(xml, r, c, dir, null))
        assertThat(after.rows[r].keys[c].keys.getOrNull(dir.subLabelIndex)).isNull()
    }

    @Test
    fun layoutValuesOfTheCatalogueReadBackAsTheSameKey() {
        // Every command a layout can hold (XmlAttributeMapper.layoutValue non-null) parses back to
        // the key the catalogue names — a name-only command is refused instead of typing its name.
        val xml = "<keyboard><row><key key0=\"a\"/></row></keyboard>"
        for (cmd in CommandRegistry.ALL_COMMANDS) {
            val mapping = ShortSwipeMapping("a", SwipeDirection.N, "x", ActionType.COMMAND, cmd.name)
            val value = XmlAttributeMapper.layoutValue(mapping) ?: continue
            val parsed = KeyboardData.load_string_exn(LayoutSubkeyXml.setSubkey(xml, 0, 0, SwipeDirection.N, value))
            assertWithMessage(cmd.name).that(parsed.rows[0].keys[0].keys[SwipeDirection.N.subLabelIndex])
                .isEqualTo(KeyValue.getSpecialKeyByName(cmd.name))
        }
        assertThat(XmlAttributeMapper.layoutValue(
            ShortSwipeMapping("a", SwipeDirection.N, "1", ActionType.COMMAND, "paste_pinned_1")
        )).isNull()
        // Since 2026-10-30 an intent can live in a layout: KeyEventHandler starts the string key
        // `label:intent:'…'` parses to (KeyEventHandlerLayoutIntentTest).
        val intent = readBack(ShortSwipeMapping("a", SwipeDirection.N, "x", ActionType.INTENT, "{}"))!!
        assertThat(intent.getMacro().single().getString()).isEqualTo(IntentDefinition.INTENT_PREFIX + "{}")
    }

    /** [mapping]'s layout value written into a one-key layout and read back by the parser. */
    private fun readBack(mapping: ShortSwipeMapping): KeyValue? {
        val xml = "<keyboard><row><key key0=\"a\"/></row></keyboard>"
        val value = XmlAttributeMapper.layoutValue(mapping)!!
        val parsed = KeyboardData.load_string_exn(LayoutSubkeyXml.setSubkey(xml, 0, 0, SwipeDirection.N, value))
        return parsed.rows[0].keys[0].keys[SwipeDirection.N.subLabelIndex]
    }

    @Test
    fun textKeyEventTemplateAndTimestampReadBackAsActionsNotQuotedText() {
        // A bare 'text' (what Export XML wrote before 2026-10-30) reads back as the literal string WITH
        // its quotes; the labelled form reads back as the text.
        val macro = readBack(ShortSwipeMapping("a", SwipeDirection.N, "hi", ActionType.TEXT, "it's: ok, \"x\""))!!
        assertThat(macro.getKind()).isEqualTo(KeyValue.Kind.Macro)
        assertThat(macro.getString()).isEqualTo("hi")
        assertThat(macro.getMacro().single().getString()).isEqualTo("it's: ok, \"x\"")

        val char = readBack(ShortSwipeMapping("a", SwipeDirection.N, "€", ActionType.TEXT, "€"))!!
        assertThat(char.getKind()).isEqualTo(KeyValue.Kind.Char)
        assertThat(char.getChar()).isEqualTo('€')

        val enter = readBack(ShortSwipeMapping("a", SwipeDirection.N, "", ActionType.KEY_EVENT, "66"))!!
        assertThat(enter.getKind()).isEqualTo(KeyValue.Kind.Keyevent)
        assertThat(enter.getKeyevent()).isEqualTo(66)

        val stamp = readBack(ShortSwipeMapping("a", SwipeDirection.N, "d:", ActionType.TIMESTAMP, "yyyy-MM-dd"))!!
        assertThat(stamp.getKind()).isEqualTo(KeyValue.Kind.Timestamp)
        assertThat(stamp.getString()).isEqualTo("📅") // a label with ':' falls back

        val glyphLabel = readBack(ShortSwipeMapping("a", SwipeDirection.N, "\uE015", ActionType.TEXT, "hey", useKeyFont = true))!!
        assertThat(glyphLabel.getString()).isEqualTo("hey") // never a key-font glyph as a plain label

        // A backslash is escaped (`\\`) since 2026-10-30; it had no spelling before.
        val backslash = readBack(ShortSwipeMapping("a", SwipeDirection.N, "x", ActionType.TEXT, "back\\slash"))!!
        assertThat(backslash.getMacro().single().getString()).isEqualTo("back\\slash")
    }
}
