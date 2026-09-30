package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test
import tribixbite.cleverkeys.ui.settings.SettingsSearchMatch

/**
 * Settings search works in the UI language (2026-09-30 fa/hu device finding).
 *
 * Before: results matched only the English index, and every control registered its scroll
 * position under `settingSlug(visibleTitle)` (`[^a-z0-9]` → `_`), so under fa (and partly under
 * any non-English locale) the control's key never equalled the result's settingId — the result
 * opened the section but did not scroll. Now the ids are the title resource names and controls
 * look them up by their visible title ([SettingsSearchMatch.idsByTitle]).
 */
class SettingsSearchLocalizationTest {

    private val resourceEntries = GENERATED_SEARCH_ENTRIES.filter { it.titleRes != 0 }

    private fun localizedTitles(locale: String): Map<String, String> {
        val strings = TranslationResources.strings(File("res/values-$locale"))
        val english = TranslationResources.strings(TranslationResources.defaultDir)
        return resourceEntries.associate { e ->
            e.settingId to TranslationResources.unescape(strings[e.settingId] ?: english.getValue(e.settingId))
        }
    }

    /** The pre-2026-09-30 scroll key: a slug of the visible title (SettingsSearch.settingSlug). */
    private fun oldSlug(title: String) = title.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    @Test fun scrollTargetsResolveBySettingIdInEveryLocale() {
        assertThat(resourceEntries.size).isAtLeast(100)
        for (dir in TranslationResources.localeDirs) {
            val locale = TranslationResources.localeOf(dir)
            val titles = localizedTitles(locale)
            val idsByTitle = SettingsSearchMatch.idsByTitle(titles.map { (id, title) -> title to id })
            val unreachable = titles.filter { (id, title) -> id !in idsByTitle[title].orEmpty() }.keys
            assertWithMessage("$locale: controls would not register under these search ids")
                .that(unreachable).isEmpty()
        }
    }

    /** Documents the defect this replaced: under fa almost no control key matched its entry. */
    @Test fun theOldTitleSlugKeysMissedMostEntriesUnderPersian() {
        val fa = localizedTitles("fa")
        val english = resourceEntries.associate { it.settingId to it.title }
        val misses = fa.count { (id, title) -> oldSlug(title) != oldSlug(english.getValue(id)) }
        assertWithMessage("fa entries whose old slug key diverged from the English-slug search id")
            .that(misses.toDouble() / fa.size).isGreaterThan(0.9)
    }

    @Test fun hungarianSearchFindsTheHungarianTitle() {
        val hu = localizedTitles("hu")
        val entry = resourceEntries.first { it.settingId == "swipe_enable_title" }
        val title = hu.getValue(entry.settingId)
        val word = title.split(' ').maxBy { it.length }.lowercase()
        assertWithMessage("'$word' is taken from the hu title '$title'")
            .that(SettingsSearchMatch.matches(word, title, entry.title, entry.keywords)).isTrue()
        assertWithMessage("the hu word matches through the localized title, not the English index")
            .that(SettingsSearchMatch.matches(word, "", entry.title, entry.keywords)).isFalse()
        // Accent-insensitive: users without the accented letters still find it.
        val folded = java.text.Normalizer.normalize(word, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        assertThat(SettingsSearchMatch.matches(folded, title, entry.title, entry.keywords)).isTrue()
    }

    @Test fun persianSearchFindsThePersianTitleWhateverTheKeyboard() {
        val fa = localizedTitles("fa")
        val entry = resourceEntries.first { e ->
            fa.getValue(e.settingId).let { 'ی' in it && '‌' in it }
        }
        val title = fa.getValue(entry.settingId)
        assertThat(SettingsSearchMatch.matches(title, title, entry.title, entry.keywords)).isTrue()
        // Typed on an Arabic layout (ARABIC YEH) without the zero-width non-joiner.
        val arabicKeyboard = title.replace('ی', 'ي').replace("‌", "")
        assertWithMessage("'$arabicKeyboard' must still find '$title'")
            .that(SettingsSearchMatch.matches(arabicKeyboard, title, entry.title, entry.keywords)).isTrue()
        // English habits keep working under the Persian UI.
        val englishWord = entry.title.split(' ').maxBy { it.length }
        assertThat(SettingsSearchMatch.matches(englishWord, title, entry.title, entry.keywords)).isTrue()
    }

    @Test fun everyGeneratedSectionHasALocalizedTitle() {
        val search = File("src/main/kotlin/tribixbite/cleverkeys/ui/settings/SettingsSearch.kt").readText()
        val body = search.substringAfter("fun sectionTitleRes(").substringBefore("\n}")
        val mapped = Regex("\"(\\w+)\" -> R\\.string\\.(\\w+)").findAll(body)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val english = TranslationResources.strings(TranslationResources.defaultDir)
        val missing = GENERATED_SEARCH_ENTRIES.map { it.sectionKey }.toSet() - mapped.keys
        assertWithMessage("section keys without a title resource").that(missing).isEmpty()
        assertThat(mapped.values.filterNot { it in english }).isEmpty()
    }
}
