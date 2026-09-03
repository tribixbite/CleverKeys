package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

/**
 * Pins the shipped **keyboard-layout catalogue** and the two US-QWERTY promises that were
 * published as release notes.
 *
 * Release-record rows guarded here (`docs/RELEASE_RECORD.md`):
 *
 * | version | published note |
 * |---|---|
 * | v1.0.0  | "100+ keyboard layouts" (layout catalogue) |
 * | v1.2.4  | "Updated US QWERTY layout subkeys" |
 * | v1.2.5  | "LAYOUT NOTE: The default US QWERTY layout has repositioned some subkeys to the keyboard perimeter. This reduces conflicts between short swipe gestures and word swipes (e.g., east subkey on 'W' vs swiping 'we'). To restore the classic layout, go to Settings > Layout Manager and select 'QWERTY Latin US (Julow)'." |
 *
 * ## Why the catalogue is testable in pure JVM
 *
 * [LayoutManager] never holds the catalogue: it indexes into `config.layouts`, which
 * `Config` builds from the generated resource arrays in `res/values/layouts.xml`
 * (`gen_layouts.py`) and loads from `@raw/<name>`, copied out of `src/main/layouts/` by the
 * `copyLayoutDefinitions` Gradle task. Both are plain files in the repo, so the promise —
 * *which* layouts a user can choose, and *what subkeys they carry* — is checkable without a
 * device. What is NOT checkable here is rendering; that is `Keyboard2View`'s job.
 *
 * ## Finding recorded by this test (v1.0.0 "100+ keyboard layouts")
 *
 * The catalogue has never contained 100 layouts. It shipped **83** selectable layouts at
 * v1.0.0 (85 array entries minus the `system` and `custom` sentinels) and holds **84**
 * today. [catalogue_selectableLayoutCount_neverRegressesBelowTheV100Baseline] therefore
 * pins the real floor (83) rather than the published figure, which no build has ever met.
 */
class LayoutCatalogueTest {

    private companion object {
        val LAYOUTS_XML = File("res/values/layouts.xml")
        val LAYOUT_DIR = File("src/main/layouts")

        /** Sentinels in `pref_layout_values` that are not backed by a layout file. */
        val SENTINELS = setOf("system", "custom")

        /** Selectable layouts shipped in v1.0.0 (85 array entries − 2 sentinels). */
        const val V1_0_0_SELECTABLE_LAYOUTS = 83

        /**
         * Corner/edge attribute names a layout key may carry. `key0` is the centre in the
         * numeric-style notation; `c` is the centre in the cardinal notation used by the
         * Latin layouts.
         */
        val DIRECTIONS = listOf("nw", "n", "ne", "w", "e", "sw", "s", "se")
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun stringArray(name: String): List<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(LAYOUTS_XML)
        val arrays = doc.getElementsByTagName("string-array")
        for (i in 0 until arrays.length) {
            val el = arrays.item(i) as Element
            if (el.getAttribute("name") == name) return itemsOf(el)
        }
        val ints = doc.getElementsByTagName("integer-array")
        for (i in 0 until ints.length) {
            val el = ints.item(i) as Element
            if (el.getAttribute("name") == name) return itemsOf(el)
        }
        throw AssertionError("array '$name' missing from ${LAYOUTS_XML.path}")
    }

    private fun itemsOf(array: Element): List<String> {
        val items = array.getElementsByTagName("item")
        return (0 until items.length).map { items.item(it).textContent }
    }

    /** Every `<key>` of a layout file, keyed by its centre symbol (`c` or `key0`). */
    private fun keysOf(layoutFile: File): Map<String, Element> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(layoutFile)
        val keys = doc.getElementsByTagName("key")
        val out = LinkedHashMap<String, Element>()
        for (i in 0 until keys.length) {
            val el = keys.item(i) as Element
            val centre = el.getAttribute("c").ifEmpty { el.getAttribute("key0") }
            if (centre.isNotEmpty()) out[centre] = el
        }
        return out
    }

    private fun keyboardName(layoutFile: File): String {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(layoutFile)
        return (doc.getElementsByTagName("keyboard").item(0) as Element).getAttribute("name")
    }

    /**
     * Directional symbols of a key, with the layout format's `\` escape stripped so a test
     * can name the symbol a user sees (`?`) rather than its source spelling (`\?`).
     */
    private fun subSymbols(key: Element): Map<String, String> =
        DIRECTIONS.mapNotNull { dir ->
            val v = key.getAttribute(dir)
            if (v.isEmpty()) null else dir to v.removePrefix("\\")
        }.toMap()

    private fun defaultQwerty() = File(LAYOUT_DIR, "latn_qwerty_us.xml")

    private fun julowQwerty() = File(LAYOUT_DIR, "latn_qwerty_us_julow.xml")

    // =========================================================================
    // v1.0.0 — "100+ keyboard layouts"
    // =========================================================================

    @Test
    fun catalogue_threeArraysAreParallelAndSentinelBounded() {
        val values = stringArray("pref_layout_values")
        val entries = stringArray("pref_layout_entries")
        val ids = stringArray("layout_ids")

        // A ListPreference reads the three arrays positionally: any length mismatch mislabels
        // every layout after the offending index.
        assertThat(entries).hasSize(values.size)
        assertThat(ids).hasSize(values.size)

        assertThat(values.first()).isEqualTo("system")
        assertThat(values.last()).isEqualTo("custom")
        // The `system` sentinel is "follow the system locale" — it has no raw resource.
        assertThat(ids.first()).isEqualTo("-1")
        assertThat(values.toSet()).hasSize(values.size)
    }

    @Test
    fun catalogue_everySelectableLayoutResolvesToAShippedLayoutFile() {
        val values = stringArray("pref_layout_values")
        val ids = stringArray("layout_ids")

        val missingFiles = mutableListOf<String>()
        val misalignedIds = mutableListOf<String>()
        values.forEachIndexed { i, name ->
            if (name in SENTINELS) return@forEachIndexed
            if (!File(LAYOUT_DIR, "$name.xml").isFile) missingFiles += name
            // copyLayoutDefinitions copies src/main/layouts/*.xml to res/raw, so the id must
            // be exactly @raw/<name> or the picker loads a different layout than it names.
            if (ids[i] != "@raw/$name") misalignedIds += "$name -> ${ids[i]}"
        }
        assertThat(missingFiles).isEmpty()
        assertThat(misalignedIds).isEmpty()
    }

    @Test
    fun catalogue_selectableLayoutCount_neverRegressesBelowTheV100Baseline() {
        val values = stringArray("pref_layout_values")
        val selectable = values.filterNot { it in SENTINELS }

        // v1.0.0 published "100+ keyboard layouts". It shipped 83 and the catalogue has grown
        // by one since; the published figure has never been met. This pins the real floor.
        assertThat(selectable.size).isAtLeast(V1_0_0_SELECTABLE_LAYOUTS)
        assertThat(selectable).contains("latn_qwerty_us")
        assertThat(selectable).contains("latn_qwerty_us_julow")
    }

    @Test
    fun catalogue_defaultLayoutIsUsQwertyAtIndexOne() {
        val values = stringArray("pref_layout_values")
        val entries = stringArray("pref_layout_entries")
        // Index 1 is the first concrete layout after the `system` sentinel — the one a user
        // lands on when they pick a layout explicitly for the first time.
        assertThat(values[1]).isEqualTo("latn_qwerty_us")
        assertThat(entries[1]).isEqualTo("QWERTY (US)")
    }

    // =========================================================================
    // v1.2.5 — "restore the classic layout … select 'QWERTY Latin US (Julow)'"
    // =========================================================================

    @Test
    fun julowLayout_isSelectableFromTheLayoutManager() {
        val values = stringArray("pref_layout_values")
        val entries = stringArray("pref_layout_entries")
        val idx = values.indexOf("latn_qwerty_us_julow")

        // The release note tells users to select it by name, so the escape hatch only exists
        // if the entry is in the picker AND is labelled recognisably.
        assertThat(idx).isGreaterThan(0)
        assertThat(entries[idx]).contains("Julow")
        assertThat(julowQwerty().isFile).isTrue()
        assertThat(keyboardName(julowQwerty())).isEqualTo("Julow QWERTY (US)")
    }

    @Test
    fun defaultQwerty_movesConflictProneSubkeysToThePerimeterKeys() {
        val keys = keysOf(defaultQwerty())
        assertThat(keyboardName(defaultQwerty())).isEqualTo("QWERTY (US)")

        // `q` and `p` are the row-1 edge keys, `shift` the row-3 edge key: a swipe path that
        // crosses the middle of the keyboard cannot start or end on them by accident.
        assertThat(subSymbols(keys.getValue("q"))).containsEntry("nw", "~")
        assertThat(subSymbols(keys.getValue("q"))).containsEntry("sw", "`")
        assertThat(subSymbols(keys.getValue("p"))).containsEntry("se", "|")
        assertThat(subSymbols(keys.getValue("shift"))).containsAtLeastEntriesIn(
            mapOf("nw" to "home", "ne" to "end", "se" to "loc tab", "sw" to "loc capslock")
        )
    }

    @Test
    fun defaultQwerty_homeRowCentreKeysCarryNoSubkeys() {
        val keys = keysOf(defaultQwerty())
        // d/f/g/h sit in the middle of the home row — nearly every word swipe crosses them.
        // Leaving them bare is what removes the short-swipe/word-swipe conflict.
        for (c in listOf("d", "f", "g", "h")) {
            assertThat(subSymbols(keys.getValue(c))).isEmpty()
        }
    }

    @Test
    fun defaultQwerty_hasNoWordShortcutSubkeys() {
        val keys = keysOf(defaultQwerty())
        // The note's worked example is the east subkey on 'W' vs swiping "we". Word shortcuts
        // are spelled with a trailing space ("we ", "to ", "on "); the default must carry none.
        val wordShortcuts = keys.entries.flatMap { (centre, el) ->
            subSymbols(el).filterValues { it.endsWith(" ") }.map { (dir, v) -> "$centre.$dir=$v" }
        }
        assertThat(wordShortcuts).isEmpty()
        assertThat(subSymbols(keys.getValue("w")).values).doesNotContain("we ")
    }

    @Test
    fun julowLayout_keepsTheClassicSubkeyPositionsTheDefaultVacated() {
        val julow = keysOf(julowQwerty())
        val default = keysOf(defaultQwerty())

        // Exactly the symbols the default moved to q/p/shift live on interior keys here.
        assertThat(subSymbols(julow.getValue("w"))).containsEntry("nw", "~")
        assertThat(subSymbols(julow.getValue("a"))).containsEntry("ne", "`")
        assertThat(subSymbols(julow.getValue("l"))).containsEntry("ne", "|")
        // …and the home-row centre keys the default left bare are loaded.
        assertThat(subSymbols(julow.getValue("g"))).containsAtLeastEntriesIn(
            mapOf("nw" to "-", "sw" to "_")
        )
        assertThat(subSymbols(julow.getValue("h"))).containsAtLeastEntriesIn(
            mapOf("ne" to "=", "sw" to "+")
        )
        // The note's own example: the conflicting "we " shortcut survives only in Julow.
        assertThat(subSymbols(julow.getValue("w"))).containsEntry("se", "we ")
        assertThat(subSymbols(default.getValue("w")).values).doesNotContain("we ")
    }

    // =========================================================================
    // v1.2.4 — "Updated US QWERTY layout subkeys"
    // =========================================================================

    @Test
    fun defaultQwerty_shippedSubkeyMapIsTheOneAnnouncedInV124() {
        val keys = keysOf(defaultQwerty())

        // Digits stay on the north-east corner of the top row, 1..0 left to right.
        val digitRow = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
        val digits = digitRow.map { subSymbols(keys.getValue(it)).getValue("ne") }
        assertThat(digits).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
            .inOrder()

        // Brackets are grouped on k, parentheses/slashes on l, maths on j.
        assertThat(subSymbols(keys.getValue("k"))).containsExactlyEntriesIn(
            mapOf("nw" to "{", "ne" to "}", "sw" to "[", "se" to "]")
        )
        assertThat(subSymbols(keys.getValue("l"))).containsExactlyEntriesIn(
            mapOf("nw" to "(", "ne" to ")", "sw" to "/", "se" to "\\")
        )
        assertThat(subSymbols(keys.getValue("j"))).containsExactlyEntriesIn(
            mapOf("ne" to "+", "se" to "=")
        )
        // Sentence punctuation on the bottom row.
        assertThat(subSymbols(keys.getValue("c"))).containsExactlyEntriesIn(mapOf("sw" to "."))
        assertThat(subSymbols(keys.getValue("v"))).containsExactlyEntriesIn(mapOf("sw" to ","))
        assertThat(subSymbols(keys.getValue("b"))).containsExactlyEntriesIn(
            mapOf("sw" to "<", "se" to ">")
        )
        assertThat(subSymbols(keys.getValue("n"))).containsExactlyEntriesIn(
            mapOf("sw" to ";", "se" to ":")
        )
        assertThat(subSymbols(keys.getValue("m"))).containsExactlyEntriesIn(
            mapOf("nw" to "?", "ne" to "\"", "sw" to "'", "se" to "!")
        )
        // Backspace keeps delete / delete-last-word on its corners.
        assertThat(subSymbols(keys.getValue("backspace"))).containsExactlyEntriesIn(
            mapOf("nw" to "delete_last_word", "ne" to "delete")
        )
    }
}
