package tribixbite.cleverkeys

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Pins the localization of user-facing copy that the swipe/backup work added, across all
 * 21 shipped locales.
 *
 * CK-150-032b closed two blind spots: the original test only asserted that `name="…"`
 * appeared somewhere in each locale file, so
 *
 *  - a `<string>` where the default declares `<plurals>` passed (the runtime
 *    `getQuantityString` call would then throw / fall back), and
 *  - a wholesale copy of the English value passed as "translated".
 *
 * Both are now checked with a real XML parse (`javax.xml.parsers`) rather than substring
 * matching, so a name that appears only inside a comment or another string's body no
 * longer counts as present either.
 */
class TranslationCoverageDriftTest {

    /**
     * ARC-103: the `%1$d … (s)`-style count strings the wave-D pl/uk lanes flagged. A single
     * form cannot render correctly under Slavic/Baltic plural rules, so these were converted
     * from `<string>` to `<plurals>`; the consumers format them via
     * `getQuantityString`/`pluralStringResource`.
     */
    private val convertedCountPlurals = setOf(
        "multilang_installed_count",
        "multilang_pack_stats",
        "learning_data_pairs_for_language",
        "learning_data_triples_suffix",
        "learning_data_word_count",
        "learning_data_forget_phrases_body",
        "learning_data_forget_words_body",
        "gif_pack_stats",
        "gif_grid_columns_display",
        "short_swipe_mappings_summary",
        "command_palette_count_results",
        "command_palette_char_count",
        "import_preview_short_swipe_count",
        "import_preview_short_swipe_warning",
        "import_preview_learned_vocabulary",
        // ARC-109: converted after ARC-103 (needed plumbing — the provenance sheet's
        // pure formatter now takes a quantity-aware resolver instead of a raw template).
        "provenance_usage_count",
    )

    /** Names that MUST be `<plurals>` everywhere — the code calls `getQuantityString`. */
    private val requiredPlurals = setOf(
        "dict_word_too_long_for_swipe_msg",
        "collision_warning_body",
    ) + convertedCountPlurals

    private val required = setOf(
        "collision_warning_title", "collision_warning_body", "collision_warning_examples",
        "dict_word_too_long_for_swipe_title", "dict_word_too_long_for_swipe_msg",
        "gesture_finger_occlusion_title", "gesture_finger_occlusion_desc",
        "gesture_touch_smoothing_title", "gesture_touch_smoothing_desc",
        "swipe_context_rescoring_title", "swipe_context_rescoring_desc",
        "swipe_engine_fallback_title", "swipe_engine_fallback_desc",
        // CK-150-030: backup passphrase protection-state surface (settings block + headless toast).
        "backup_protection_state_keystore", "backup_protection_state_legacy",
        "backup_protection_state_not_set", "backup_protection_status",
        "backup_passphrase_storage_unavailable",
    ) + convertedCountPlurals

    /**
     * How many of the 21 locales must carry a value textually DIFFERENT from the default
     * (English) one. A majority threshold rather than "all": legitimate cognates exist
     * (a title that is genuinely the same word in a related language), while a wholesale
     * English copy-paste — the failure this catches — leaves nearly every locale identical.
     */
    private val minDistinctLocales = 12

    /** One resource entry: its element name (`string`/`plurals`/…) and flattened text. */
    private data class Entry(val element: String, val value: String)

    private fun parse(file: File): Map<String, Entry> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val doc = factory.newDocumentBuilder().parse(file)
        val out = LinkedHashMap<String, Entry>()
        val children = doc.documentElement.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            val name = node.getAttribute("name")
            if (name.isNullOrEmpty()) continue
            // textContent flattens <item> children of a <plurals> and any inline markup,
            // which is exactly the granularity the copy-paste guard needs.
            out[name] = Entry(node.tagName, node.textContent.orEmpty().trim())
        }
        return out
    }

    private val defaultEntries by lazy { parse(File("res/values/strings.xml")) }

    private val localeDirs by lazy {
        File("res").listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") && it.name != "values-night" && it.name != "values-v29" }
            .sortedBy { it.name }
    }

    @Test fun newSwipeCopyExistsInEverySupportedLocale() {
        assertEquals("expected all 21 locale directories", 21, localeDirs.size)
        for (dir in localeDirs) {
            val entries = parse(File(dir, "strings.xml"))
            for (name in required) {
                assertTrue("${dir.name} is missing $name", entries.containsKey(name))
            }
        }
    }

    /**
     * A `<string>` where the code calls `getQuantityString` is a crash/fallback at runtime
     * and is invisible to a name-presence check — the exact shape CK-150-032 flagged.
     */
    @Test fun pinnedNamesKeepTheirElementTypeInEveryLocale() {
        for (name in required) {
            val expected = if (name in requiredPlurals) "plurals" else "string"
            val default = defaultEntries[name]
                ?: throw AssertionError("res/values/strings.xml is missing $name")
            assertEquals(
                "res/values/strings.xml: $name must be a <$expected>",
                expected, default.element
            )
            for (dir in localeDirs) {
                val entry = parse(File(dir, "strings.xml"))[name]
                    ?: throw AssertionError("${dir.name} is missing $name")
                assertEquals(
                    "${dir.name}: $name must be a <$expected> like the default locale " +
                        "(a <${entry.element}> here breaks the runtime lookup)",
                    expected, entry.element
                )
            }
        }
    }

    /** Parses one `<plurals>` element into its quantity→text item map. */
    private fun parsePluralItems(file: File, name: String): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val doc = factory.newDocumentBuilder().parse(file)
        val children = doc.documentElement.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            if (node.tagName != "plurals" || node.getAttribute("name") != name) continue
            val items = LinkedHashMap<String, String>()
            val itemNodes = node.childNodes
            for (j in 0 until itemNodes.length) {
                val item = itemNodes.item(j) as? Element ?: continue
                if (item.tagName != "item") continue
                items[item.getAttribute("quantity")] = item.textContent.orEmpty().trim()
            }
            return items
        }
        throw AssertionError("${file.path} has no <plurals name=\"$name\">")
    }

    /**
     * ARC-103 conversion guard: the default (English) locale must actually INFLECT each
     * converted count string — `getQuantityString(…, 1, 1)` and `getQuantityString(…, 5, 5)`
     * resolve to the `one` and `other` items, so those two items differing is exactly
     * "n=1 renders differently from n=5". A conversion that pasted the plural text into
     * both items would silently reintroduce the "(s)" hack.
     */
    @Test fun convertedCountPluralsInflectInEnglish() {
        val en = File("res/values/strings.xml")
        for (name in convertedCountPlurals) {
            val items = parsePluralItems(en, name)
            val one = items["one"]
                ?: throw AssertionError("res/values/strings.xml: $name has no quantity=\"one\" item")
            val other = items["other"]
                ?: throw AssertionError("res/values/strings.xml: $name has no quantity=\"other\" item")
            assertTrue(
                "res/values/strings.xml: $name must inflect — its one/other items are identical",
                one != other
            )
        }
    }

    /**
     * Every `<item>` of every converted plural, in every locale, must carry the same
     * multiset of positional format specifiers as the default locale's `other` item —
     * a dropped `%1$d` in one quantity bucket is a runtime `MissingFormatArgumentException`
     * (or silently wrong output) only for speakers of that language at that count.
     */
    @Test fun convertedCountPluralItemsKeepPlaceholdersPerQuantityItem() {
        val spec = Regex("%[0-9]+\\$[sd]")
        val expected = convertedCountPlurals.associateWith { name ->
            val other = parsePluralItems(File("res/values/strings.xml"), name).getValue("other")
            spec.findAll(other).map { it.value }.sorted().toList()
        }
        for (dir in localeDirs + File("res/values")) {
            for (name in convertedCountPlurals) {
                for ((quantity, text) in parsePluralItems(File(dir, "strings.xml"), name)) {
                    val found = spec.findAll(text).map { it.value }.sorted().toList()
                    assertEquals(
                        "${dir.name}: $name item quantity=\"$quantity\" placeholder mismatch",
                        expected.getValue(name), found
                    )
                }
            }
        }
    }

    /**
     * Guards against "localized" copy that is really the English string pasted into all
     * 21 files — the other way a name-presence check false-greens.
     */
    @Test fun pinnedNamesAreActuallyTranslatedNotEnglishCopies() {
        val perLocale = localeDirs.associate { it.name to parse(File(it, "strings.xml")) }
        for (name in required) {
            val default = defaultEntries.getValue(name).value
            val copies = perLocale.filterValues { it[name]?.value == default }.keys
            val distinct = localeDirs.size - copies.size
            assertTrue(
                "$name: only $distinct/${localeDirs.size} locales differ from the default " +
                    "(need $minDistinctLocales). Untranslated: $copies",
                distinct >= minDistinctLocales
            )
        }
    }
}
