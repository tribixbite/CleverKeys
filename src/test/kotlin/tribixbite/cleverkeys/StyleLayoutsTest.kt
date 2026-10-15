package tribixbite.cleverkeys

import android.util.Log
import android.util.Xml
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.w3c.dom.Element

/**
 * Pins the three "-style" predefined layouts — `QWERTY (iOS-style)`, `QWERTY (Gboard-style)`
 * and `QWERTY (SwiftKey-style)` — that mimic the default US-English phone keyboards of those
 * products (`docs/wiki/layouts/adding-layouts.md`, "Familiar-style layouts").
 *
 * Mock tier: `android.util.Xml` is a stub off-device, so the functional kxml2 parser is
 * substituted and every file is run through the REAL production parser
 * ([KeyboardData.load_string_exn]) — the same code path `KeyboardData.load` takes for an
 * `@raw/` layout on a device. What is asserted is what each layout promises:
 *
 * - it is registered in the picker arrays under its descriptive "-style" name;
 * - it parses, declares `script="latin"` and exposes all 26 a–z letters as centre keys exactly
 *   once, which is what the CTC and geometric swipe engines need (the engine router's script
 *   and alphabet gates — see [tribixbite.cleverkeys.swipe.LayoutScriptDeclarationTest]);
 * - it replaces the CleverKeys bottom row with the product's own bottom row, in the product's
 *   order and key-width proportions;
 * - every named key value resolves to a real key, so a typo cannot silently become a key that
 *   types its own name;
 * - the per-product letter hints are exactly the researched ones.
 */
class StyleLayoutsTest {

    private companion object {
        val LAYOUT_DIR = File("src/main/layouts")
        val LAYOUTS_XML = File("res/values/layouts.xml")

        const val IOS = "latn_qwerty_us_ios_style"
        const val GBOARD = "latn_qwerty_us_gboard_style"
        const val SWIFTKEY = "latn_qwerty_us_swiftkey_style"

        /** Layout id -> the display name shown in the layout picker. */
        val NAMES = mapOf(
            IOS to "QWERTY (iOS-style)",
            GBOARD to "QWERTY (Gboard-style)",
            SWIFTKEY to "QWERTY (SwiftKey-style)",
        )

        /** Compass attributes a `<key>` may carry, in the cardinal notation these files use. */
        val VALUE_ATTRS = listOf("c", "nw", "ne", "sw", "se", "w", "e", "n", "s")

        val QWERTY_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    }

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        mockkStatic(Xml::class)
        every { Xml.newPullParser() } answers { KXmlParser() }
    }

    @After
    fun teardown() = unmockkAll()

    // ── helpers ──────────────────────────────────────────────────────────────────────

    private fun file(id: String) = File(LAYOUT_DIR, "$id.xml")

    private fun parse(id: String): KeyboardData = KeyboardData.load_string_exn(file(id).readText())

    private fun stringArray(name: String): List<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(LAYOUTS_XML)
        for (tag in listOf("string-array", "integer-array")) {
            val arrays = doc.getElementsByTagName(tag)
            for (i in 0 until arrays.length) {
                val el = arrays.item(i) as Element
                if (el.getAttribute("name") != name) continue
                val items = el.getElementsByTagName("item")
                return (0 until items.length).map { items.item(it).textContent }
            }
        }
        throw AssertionError("array '$name' missing from ${LAYOUTS_XML.path}")
    }

    /** Every `<key>` element of a layout, row by row, as the XML-decoded attribute map. */
    private fun xmlRows(id: String): List<List<Map<String, String>>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file(id))
        val rows = doc.getElementsByTagName("row")
        return (0 until rows.length).map { r ->
            val keys = (rows.item(r) as Element).getElementsByTagName("key")
            (0 until keys.length).map { k ->
                val el = keys.item(k) as Element
                VALUE_ATTRS.filter { el.hasAttribute(it) }.associateWith { el.getAttribute(it) }
            }
        }
    }

    /** Index of the first row whose centre keys start with `q`. */
    private fun topLetterRow(kw: KeyboardData): Int =
        kw.rows.indexOfFirst { row -> row.keys.firstOrNull()?.getKeyValue(0) == key("q") }

    private fun key(name: String): KeyValue = KeyValue.getKeyByName(name)

    /** Non-`loc` corner values of a key (index 1..8), i.e. what is drawn as a hint by default. */
    private fun visibleCorners(k: KeyboardData.Key): List<KeyValue> =
        (1..8).mapNotNull { i ->
            k.getKeyValue(i)?.takeUnless { k.keyHasFlag(i, KeyboardData.Key.F_LOC) }
        }

    // ── registration and naming ──────────────────────────────────────────────────────

    @Test
    fun eachStyleLayoutIsRegisteredInThePickerUnderItsDescriptiveName() {
        val values = stringArray("pref_layout_values")
        val entries = stringArray("pref_layout_entries")
        val ids = stringArray("layout_ids")
        for ((id, name) in NAMES) {
            val i = values.indexOf(id)
            assertWithMessage("$id must be selectable (regenerate with tools/gen_layouts.py)")
                .that(i).isGreaterThan(0)
            assertThat(entries[i]).isEqualTo(name)
            assertThat(ids[i]).isEqualTo("@raw/$id")
            assertThat(parse(id).name).isEqualTo(name)
        }
    }

    @Test
    fun namesAreDescriptiveAndEachFileCarriesTheNonAffiliationNote() {
        for ((id, name) in NAMES) {
            // Nominative, descriptive naming: "<Product>-style", never the bare product or
            // vendor name, and the file says in words that it is not affiliated.
            assertThat(name).endsWith("-style)")
            for (vendor in listOf("Apple", "Google", "Microsoft")) {
                assertThat(name).doesNotContain(vendor)
            }
            // Whitespace-normalised: the note may wrap across comment lines.
            assertThat(file(id).readText().replace(Regex("\\s+"), " "))
                .contains("not affiliated with or endorsed by")
        }
    }

    // ── swipe compatibility ──────────────────────────────────────────────────────────

    @Test
    fun eachLayoutIsLatinAndExposesEveryLetterExactlyOnceAsACentreKey() {
        for (id in NAMES.keys) {
            val kw = parse(id)
            assertWithMessage("$id script").that(kw.script).isEqualTo("latin")

            val centres = kw.rows.flatMap { row -> row.keys.mapNotNull { it.getKeyValue(0) } }
            for (c in 'a'..'z') {
                assertWithMessage("$id: '$c' as a centre key")
                    .that(centres.count { it == key(c.toString()) }).isEqualTo(1)
            }
            // The three letter rows are the standard QWERTY rows, in order.
            val top = topLetterRow(kw)
            assertWithMessage("$id has a q-first letter row").that(top).isAtLeast(0)
            QWERTY_ROWS.forEachIndexed { i, letters ->
                val rowLetters = kw.rows[top + i].keys
                    .mapNotNull { it.getKeyValue(0) }
                    .filter { it.getKind() == KeyValue.Kind.Char && it.getChar() in 'a'..'z' }
                    .joinToString("") { it.getChar().toString() }
                assertWithMessage("$id letter row ${i + 1}").that(rowLetters).isEqualTo(letters)
            }
        }
    }

    // ── geometry and bottom rows ─────────────────────────────────────────────────────

    @Test
    fun eachLayoutIsTenKeysWideWithAHalfKeyIndentOnTheHomeRow() {
        for (id in NAMES.keys) {
            val kw = parse(id)
            assertWithMessage("$id width").that(kw.keysWidth).isEqualTo(10f)
            val home = kw.rows[topLetterRow(kw) + 1]
            assertWithMessage("$id home-row indent").that(home.keys.first().shift).isEqualTo(0.5f)
        }
    }

    /** One expected bottom-row key: what it does, and its width in key units. */
    private data class Slot(val kv: KeyValue, val width: Float)

    private fun numeric(label: String, width: Float) = Slot(key("$label:switch_numeric"), width)

    @Test
    fun eachLayoutReplacesTheBottomRowWithTheProductsOwnOrderAndWidths() {
        val expected = mapOf(
            // iOS: 123 | emoji | space | return
            IOS to listOf(
                numeric("123", 1.25f), Slot(key("switch_emoji"), 1.25f),
                Slot(key("space"), 5f), Slot(key("enter"), 2.5f),
            ),
            // Gboard: ?123 | , | space | . | enter (emoji is the comma's hint)
            GBOARD to listOf(
                numeric("?123", 1.5f), Slot(key(","), 1f), Slot(key("space"), 5f),
                Slot(key("."), 1f), Slot(key("enter"), 1.5f),
            ),
            // SwiftKey: 123 | emoji | , | space | . | enter
            SWIFTKEY to listOf(
                numeric("123", 1f), Slot(key("switch_emoji"), 1f), Slot(key(","), 1f),
                Slot(key("space"), 4.5f), Slot(key("."), 1f), Slot(key("enter"), 1.5f),
            ),
        )
        for ((id, slots) in expected) {
            val kw = parse(id)
            // Without bottom_row="false" LayoutModifier would append the standard bottom row
            // under this one and the board would have two space bars.
            assertWithMessage("$id must disable the built-in bottom row").that(kw.bottom_row).isFalse()
            val bottom = kw.rows.last().keys
            assertWithMessage("$id bottom row")
                .that(bottom.map { Slot(it.getKeyValue(0)!!, it.width) })
                .containsExactlyElementsIn(slots).inOrder()
        }
    }

    @Test
    fun theRelabelledNumbersKeyStillSwitchesToTheNumberPane() {
        for ((id, label) in mapOf(IOS to "123", GBOARD to "?123", SWIFTKEY to "123")) {
            val kv = parse(id).rows.last().keys.first().getKeyValue(0)!!
            assertThat(kv.getKind()).isEqualTo(KeyValue.Kind.Event)
            assertThat(kv.getEvent()).isEqualTo(KeyValue.Event.SWITCH_NUMERIC)
            assertWithMessage("$id numbers-key label").that(kv.getString()).isEqualTo(label)
        }
    }

    @Test
    fun noFunctionOfTheStandardBottomRowIsLost() {
        // Everything the CleverKeys bottom row offers stays reachable somewhere on the board,
        // as a centre key or a corner. `loc` keys count: their Extra Keys checkbox governs them
        // exactly as it does on the standard bottom row.
        val standard = listOf(
            "ctrl", "fn", "meta", "alt", "switch_greekmath", "switch_clipboard",
            "switch_back_clipboard", "change_method", "switch_emoji", "config", "switch_gif",
            "space", "switch_forward", "switch_backward", "cursor_left", "cursor_right",
            "compose", "up", "down", "left", "right", "doc_home", "doc_end", "page_up",
            "page_down", "enter", "voice_typing", "action",
        )
        for (id in NAMES.keys) {
            val present = parse(id).getKeys().keys
            val missing = standard.filterNot { key(it) in present }
            assertWithMessage("$id lacks standard bottom-row keys").that(missing).isEmpty()
            // The relabelled numbers key is checked by event, since its label differs.
            assertThat(present.any {
                it.getKind() == KeyValue.Kind.Event && it.getEvent() == KeyValue.Event.SWITCH_NUMERIC
            }).isTrue()
        }
    }

    // ── key values ───────────────────────────────────────────────────────────────────

    @Test
    fun everyNamedKeyValueResolvesToARealKey() {
        for (id in NAMES.keys) {
            val unresolved = xmlRows(id).flatten().flatMap { it.values }
                .map { it.removePrefix("loc ") }
                // Single characters (after the layout escape `\`) are literal text keys.
                .filter { it.removePrefix("\\").length > 1 }
                .filterNot { name ->
                    if (':' in name) {
                        KeyValue.getKeyByName(name).getKind() == KeyValue.Kind.Event
                    } else {
                        KeyValue.getSpecialKeyByName(name) != null
                    }
                }
            assertWithMessage("$id: names that would fall back to a text key typing the name")
                .that(unresolved).isEmpty()
        }
    }

    @Test
    fun noKeyValueAppearsTwiceOnALayout() {
        // tools/check_layout.py's duplicate rule: KeyboardData.getKeys() is keyed by value, so a
        // duplicate silently loses one position for anything that looks a key up by value.
        for (id in NAMES.keys) {
            val all = parse(id).rows.flatMap { row -> row.keys.flatMap { it.keys.filterNotNull() } }
            val dups = all.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            assertWithMessage("$id duplicate values").that(dups).isEmpty()
        }
    }

    // ── per-product letter hints ─────────────────────────────────────────────────────

    @Test
    fun iosStyleLettersCarryNoVisibleHints() {
        // iOS draws bare letters: accents come from press-and-hold, which maps onto the `loc`
        // slots a language's extra keys fill in. No non-loc corner may sit on a letter.
        val kw = parse(IOS)
        val top = topLetterRow(kw)
        val withHints = (top until top + 3).flatMap { kw.rows[it].keys }
            .filter { it.getKeyValue(0)?.getKind() == KeyValue.Kind.Char }
            .filter { visibleCorners(it).isNotEmpty() }
            .map { it.getKeyValue(0)!!.getString() }
        assertThat(withHints).isEmpty()
        assertWithMessage("iOS has no number row").that(kw.embedded_number_row).isFalse()
    }

    @Test
    fun gboardStyleShowsOnlyTheTopRowDigitHints() {
        val kw = parse(GBOARD)
        val top = topLetterRow(kw)
        assertThat(top).isEqualTo(0)
        // Digits 1..0 on the top-right (ne) of q..p, Gboard's default long-press numbers.
        val digits = kw.rows[0].keys.map { it.getKeyValue(2)?.getString() }
        assertThat(digits).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").inOrder()
        for (k in kw.rows[0].keys) assertThat(visibleCorners(k)).hasSize(1)
        // The other two letter rows are bare, as in Gboard's default.
        val hinted = (1..2).flatMap { kw.rows[it].keys }
            .filter { it.getKeyValue(0)?.getKind() == KeyValue.Kind.Char }
            .filter { visibleCorners(it).isNotEmpty() }
        assertThat(hinted).isEmpty()
        // Gboard draws the emoji key as the comma's top hint.
        val comma = kw.rows.last().keys.first { it.getKeyValue(0) == key(",") }
        assertThat(comma.getKeyValue(7)).isEqualTo(key("switch_emoji"))
        assertWithMessage("Gboard's default has no number row").that(kw.embedded_number_row).isFalse()
    }

    @Test
    fun swiftkeyStyleHasANumberRowAndTheResearchedLongPressSymbols() {
        val kw = parse(SWIFTKEY)
        // Embedded so LayoutModifier does not add CleverKeys' optional number row a second time.
        assertThat(kw.embedded_number_row).isTrue()
        assertThat(kw.rows[0].keys.map { it.getKeyValue(0)!!.getString() })
            .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").inOrder()
        assertThat(kw.rows[0].height).isEqualTo(0.75f)

        // SwiftKey's long-press symbol per letter with the number row on, drawn top-centre
        // (CleverKeys direction n = index 7).
        val expected = mapOf(
            'q' to "%", 'w' to "^", 'e' to "~", 'r' to "|", 't' to "[", 'y' to "]", 'u' to "<",
            'i' to ">", 'o' to "{", 'p' to "}",
            'a' to "@", 's' to "#", 'd' to "&", 'f' to "*", 'g' to "-", 'h' to "+", 'j' to "=",
            'k' to "(", 'l' to ")",
            'z' to "_", 'x' to "$", 'c' to "\"", 'v' to "'", 'b' to ":", 'n' to ";", 'm' to "/",
        )
        val letters = kw.rows.flatMap { it.keys }.filter {
            val c = it.getKeyValue(0)
            c != null && c.getKind() == KeyValue.Kind.Char && c.getChar() in 'a'..'z'
        }
        val actual = letters.associate { it.getKeyValue(0)!!.getChar() to it.getKeyValue(7)?.getString() }
        assertThat(actual).containsExactlyEntriesIn(expected)

        // Dictation is the comma's top hint; ? and ! sit on the period.
        val bottom = kw.rows.last().keys
        val comma = bottom.first { it.getKeyValue(0) == key(",") }
        assertThat(comma.getKeyValue(7)).isEqualTo(key("voice_typing"))
        val period = bottom.first { it.getKeyValue(0) == key(".") }
        assertThat(visibleCorners(period).map { it.getString() }).containsExactly("?", "!")
    }
}
