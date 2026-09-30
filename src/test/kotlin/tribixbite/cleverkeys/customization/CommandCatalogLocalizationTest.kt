package tribixbite.cleverkeys.customization

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.util.Locale
import org.junit.Test
import tribixbite.cleverkeys.EnglishResourceText
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.ResultText
import tribixbite.cleverkeys.TranslationResources

/**
 * The command palette catalog ([CommandRegistry]) is localized (2026-09-30): every command's
 * name and description and every category header is a string resource, present in all 21
 * locales, keyed by the STABLE command id.
 *
 * The ids themselves must never change (they are persisted in short-swipe bindings and
 * backups), so the resource names are derived from them mechanically and pinned here: a
 * resource can only be renamed together with this rule, never drift away from its command.
 */
class CommandCatalogLocalizationTest {

    /** Ids that are not identifier-shaped get spelled-out resource names. */
    private val spelledOut = mapOf(
        "\\t" to "backslash_t", "\\n" to "backslash_n",
        "b(" to "bidi_paren_open", "b)" to "bidi_paren_close",
        "b[" to "bidi_bracket_open", "b]" to "bidi_bracket_close",
        "b{" to "bidi_brace_open", "b}" to "bidi_brace_close",
    )

    /** `selectAll` -> `cmd_select_all`; `f11_placeholder` -> `cmd_f11_placeholder`. */
    private fun resourceKeyFor(id: String): String {
        spelledOut[id]?.let { return "cmd_$it" }
        val snake = id.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase(Locale.ROOT)
        return "cmd_" + snake.replace(Regex("[^a-z0-9]+"), "_").trim('_')
    }

    private val stringNames: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    /** Every resource the catalog renders: category headers, names, descriptions. */
    private fun catalogKeys(): List<String> =
        CommandRegistry.Category.values().map { stringNames.getValue(it.labelRes) } +
            CommandRegistry.ALL_COMMANDS.flatMap {
                listOf(stringNames.getValue(it.nameRes), stringNames.getValue(it.descriptionRes))
            }

    @Test fun resourceNamesAreDerivedFromTheStableCommandIds() {
        val wrong = CommandRegistry.ALL_COMMANDS.mapNotNull { cmd ->
            val key = resourceKeyFor(cmd.name)
            val name = stringNames.getValue(cmd.nameRes)
            val desc = stringNames.getValue(cmd.descriptionRes)
            if (name == key && desc == "${key}_desc") null else "${cmd.name}: $name / $desc (want $key)"
        }
        assertWithMessage("command resources must be cmd_<id> / cmd_<id>_desc").that(wrong).isEmpty()
        val keys = CommandRegistry.ALL_COMMANDS.map { resourceKeyFor(it.name) }
        assertWithMessage("two command ids map to one resource name").that(keys).containsNoDuplicates()
        for (category in CommandRegistry.Category.values()) {
            assertThat(stringNames.getValue(category.labelRes))
                .isEqualTo("command_category_" + category.name.lowercase(Locale.ROOT))
        }
    }

    @Test fun everyCatalogStringExistsInEveryLocale() {
        val keys = catalogKeys()
        assertThat(keys.size).isEqualTo(CommandRegistry.Category.values().size + 2 * CommandRegistry.totalCount)
        val english = TranslationResources.strings(TranslationResources.defaultDir)
        val missing = mutableListOf<String>()
        for (key in keys) if (english[key].isNullOrBlank()) missing += "values/$key"
        for (dir in TranslationResources.localeDirs) {
            val locale = TranslationResources.strings(dir)
            for (key in keys) if (locale[key].isNullOrBlank()) missing += "${dir.name}/$key"
        }
        assertWithMessage("catalog strings missing (${missing.size})").that(missing).isEmpty()
    }

    /**
     * Guards against English pasted into a locale file. Some entries are legitimately the same in
     * every language (F1–F12, Ctrl, ZWNJ, ISO 8601, transliterated Hebrew mark names), so the
     * floor is a large majority, not all.
     */
    @Test fun everyLocaleActuallyTranslatesTheCatalog() {
        val keys = catalogKeys()
        val english = TranslationResources.strings(TranslationResources.defaultDir)
        for (dir in TranslationResources.localeDirs) {
            val locale = TranslationResources.strings(dir)
            val same = keys.count { locale[it] == english[it] }
            assertWithMessage("${dir.name}: $same of ${keys.size} catalog strings are the English text")
                .that(same.toDouble() / keys.size).isLessThan(0.25)
        }
    }

    // ── search matches the UI language, with English as a second chance ───────────────

    /** [ResultText] over one locale's strings.xml (unescaped), for pure-JVM search tests. */
    private class LocaleText(locale: String) : ResultText {
        private val strings = TranslationResources.strings(File("res/values-$locale"))
        private val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        override fun string(id: Int, vararg args: Any): String =
            TranslationResources.unescape(strings.getValue(names.getValue(id)))
        override fun plural(id: Int, count: Int, vararg args: Any): String = error("unused")
    }

    private fun localizedName(locale: ResultText, id: String): String =
        locale.string(CommandRegistry.getByName(id)!!.nameRes)

    @Test fun searchFindsCommandsByTheirLocalizedName() {
        for (loc in listOf("de", "hu", "fa", "ja", "ru")) {
            val text = LocaleText(loc)
            for (id in listOf("copy", "undo", "cursor_left", "paste_pinned_1")) {
                val name = localizedName(text, id)
                assertWithMessage("$loc: searchRanked('$name') must find $id")
                    .that(CommandRegistry.searchRanked(name, text).map { it.name }).contains(id)
                assertWithMessage("$loc: exact localized name ranks $id first")
                    .that(CommandRegistry.searchRanked(name, text).first().name).isEqualTo(id)
            }
        }
    }

    @Test fun englishNamesStillMatchUnderAnotherUiLanguage() {
        val fa = LocaleText("fa")
        assertWithMessage("without the English fallback, an English name finds nothing extra")
            .that(CommandRegistry.search("Private Copy", fa).map { it.name }).doesNotContain("copy_private")
        assertThat(CommandRegistry.search("Private Copy", fa, EnglishResourceText).map { it.name })
            .contains("copy_private")
        // Ids and English keywords keep working in every language.
        assertThat(CommandRegistry.searchRanked("selectAll", fa).first().name).isEqualTo("selectAll")
        assertThat(CommandRegistry.search("ctrl+z", fa).map { it.name }).contains("undo")
    }
}
