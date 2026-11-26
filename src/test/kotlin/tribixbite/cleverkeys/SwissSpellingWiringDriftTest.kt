package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * D2: Swiss German spelling is applied where each German lexicon is LOADED, and nowhere else.
 * The behaviour of each fold is pinned in [SwissSpellingFoldTest] and `WordPredictorSharpSTest`;
 * this class pins that every loader actually calls it, in the order that keeps the user's own
 * words out of it, and that the preference reaches every settings table. A source scan, because
 * the loaders need an Android context (assets, preferences) no JVM test has.
 */
class SwissSpellingWiringDriftTest {

    private fun source(path: String): String = File("src/main/kotlin/tribixbite/cleverkeys/$path").readText()

    private fun assertOrder(where: String, text: String, first: String, then: String) {
        val a = text.indexOf(first)
        val b = text.indexOf(then)
        assertWithMessage("$where: '$first' must be present").that(a).isAtLeast(0)
        assertWithMessage("$where: '$then' must be present").that(b).isAtLeast(0)
        assertWithMessage("$where: '$first' must come before '$then'").that(a).isLessThan(b)
    }

    @Test
    fun theTapPredictorFoldsTheShippedListBeforeTheUsersWords() {
        val predictor = source("WordPredictor.kt")
        val sync = predictor.substringAfter("    fun loadDictionary(context: Context, language: String) {")
            .substringBefore("    override fun loadDictionaryAsync(")
        assertOrder(
            "sync load", sync,
            "primarySwissSpelling = foldSwissSpelling(context, dictionary.get(), prefixIndex.get(), language)",
            "loadCustomAndUserWords(context, language)",
        )
        val async = predictor.substringAfter("override fun onLoadCustomWords(").substringBefore("override fun onLoadComplete(")
        assertOrder(
            "async load", async,
            "loadedSwissSpelling = foldSwissSpelling(ctx, dictionary, prefixIndex, language)",
            "loadCustomAndUserWordsIntoMap(",
        )
        assertWithMessage("the flag is published with the maps it describes")
            .that(predictor.substringAfter("override fun onLoadComplete(").substringBefore("override fun onLoadFailed("))
            .contains("primarySwissSpelling = loadedSwissSpelling")
        val secondary = predictor.substringAfter("private fun buildSecondaryDictionary(").substringBefore("override fun unloadSecondaryDictionary()")
        assertOrder(
            "secondary load", secondary,
            "index.respellCanonicals(SwissSpelling::fold)",
            "loadSecondaryCustomWords(ctx, index, language)",
        )
        assertWithMessage("disabled words follow the respelling")
            .that(predictor.substringAfter("private fun loadDisabledWords() {").substringBefore("override fun isWordDisabled("))
            // `language` is the DICTIONARY language (Fable audit 2026-11-26), not the next-word one.
            .contains("SwissSpelling.foldDisabled(swissSpellingFor(ctx, language), disabledSet)")
        assertWithMessage("the loaders read the STORED preference, not the in-memory Config")
            .that(predictor.substringAfter("private fun swissSpellingFor(").substringBefore("private fun foldSwissSpelling("))
            .contains("LanguagePrefs.germanSwissSpelling(DirectBootAwarePreferences.get_shared_preferences(ctx))")
    }

    @Test
    fun bothSwipeEnginesFoldTheBaseListAndKeyTheirMemoOnTheChoice() {
        val ctc = source("swipe/CtcEngineAdapter.kt").substringAfter("private fun lexiconFor(language: String): TrieMemo? {")
        assertWithMessage("CTC reads the stored preference")
            .that(ctc).contains("SwissSpelling.appliesTo(LanguagePrefs.germanSwissSpelling(prefs), lang)")
        assertOrder("CTC", ctc, "SwissSpelling.lexiconVariant(swissSpelling, lang)", "trieMemos.current(lang)")
        assertWithMessage("CTC disabled words follow the respelling")
            .that(ctc).contains("val disabled = SwissSpelling.foldDisabled(swissSpelling, inputs.disabled)")
        assertOrder(
            "CTC", ctc,
            "val basePairs = SwissSpelling.foldBaseLexicon(swissSpelling, shippedPairs)",
            "CtcLexiconMerge.merge(basePairs, userWordPairs, disabled)",
        )

        val geometric = source("swipe/GeometricEngineAdapter.kt").substringAfter("private fun dictionaryFor(language: String): DictMemo? {")
        assertWithMessage("geometric reads the stored preference")
            .that(geometric).contains("SwissSpelling.appliesTo(LanguagePrefs.germanSwissSpelling(prefs), lang)")
        assertOrder("geometric", geometric, "SwissSpelling.lexiconVariant(swissSpelling, lang)", "dictionaryMemos.current(lang)")
        assertOrder("geometric", geometric, "SwissSpelling::foldWords", "GeometricUserWordMerge.merge(base, userWords")
        assertWithMessage("geometric disabled words follow the respelling")
            .that(geometric).contains("val disabled = SwissSpelling.foldDisabled(swissSpelling, storedDisabled)")
    }

    @Test
    fun aFlipReloadsTheGermanLexiconsThroughTheOneLanguagePath() {
        val service = source("CleverKeysService.kt")
        val onConfigChanged = service.substringAfter("override fun onConfigChanged(newConfig: Config)").substringBefore("\n    }\n")
        assertOrder(
            "onConfigChanged", onConfigChanged,
            "_activeLanguageSync?.apply(newConfig.activeLanguages())",
            "_activeLanguageSync?.applySwissSpelling(newConfig.german_swiss_spelling)",
        )
        assertOrder("onConfigChanged", onConfigChanged, "seedGermanSwissSpelling(newConfig)", "_configPropagator?.propagateConfig(")
        assertWithMessage("the sync starts from the value the dictionaries were loaded with")
            .that(source("wiring/KeyboardComponentGraph.kt")).contains("initialSwissSpelling = config.german_swiss_spelling")
        assertWithMessage("Config reads the preference on every refresh")
            .that(source("Config.kt")).contains("german_swiss_spelling = LanguagePrefs.germanSwissSpelling(_prefs)")
    }

    @Test
    fun theNextWordModelIsReachedThroughAliasesAndItsOutputRespelled() {
        assertWithMessage("the model is installed with the Swiss lookup aliases")
            .that(source("BigramModel.kt"))
            .contains("parsed.withReplaceAliases(lookupAliases(parsed, language, replaceAliasesFor(context, language)))")
        assertWithMessage("the seed is respelled while the primary list is")
            .that(source("WordPredictor.kt").substringAfter("override fun getStaticNextWordSeed(").substringBefore("override fun getPersonalizationBoostFor("))
            .contains("SwissSpelling.foldDistinct(seed")
    }

    @Test
    fun theSwitchIsShownOnlyWhileGermanIsSelectedAndKeepsItsStateInStep() {
        val section = source("ui/settings/sections/MultiLanguageSection.kt")
        assertOrder(
            "Multi-Language section", section,
            "if (germanSelected) {",
            "title = stringResource(R.string.multilang_swiss_spelling_title)",
        )
        assertWithMessage("saved under the one key").that(section).contains("saveSetting(LanguagePrefs.GERMAN_SWISS_SPELLING, it)")
        val persistence = source("ui/settings/SettingsPersistence.kt")
        assertWithMessage("an import or reset moves the switch")
            .that(persistence).contains("LanguagePrefs.GERMAN_SWISS_SPELLING -> { germanSwissSpelling = LanguagePrefs.germanSwissSpelling(prefs) }")
        assertWithMessage("choosing German in Settings seeds the value there too (no keyboard process needed)")
            .that(persistence).contains("LanguagePrefs.PRIMARY, LanguagePrefs.SECONDARY, LanguagePrefs.MULTILANG -> refreshGermanSwissSpelling()")
    }

    @Test
    fun theKeyLiteralLivesOnlyInLanguagePrefs() {
        val literal = "\"${LanguagePrefs.GERMAN_SWISS_SPELLING}\""
        val root = File("src/main/kotlin")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "LanguagePrefs.kt" && literal in it.readText() }
            .map { it.relativeTo(root).path }.toList()
        assertWithMessage("pref_german_swiss_spelling must come from LanguagePrefs").that(offenders).isEmpty()
    }
}
