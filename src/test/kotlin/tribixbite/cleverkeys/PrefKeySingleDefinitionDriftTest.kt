package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test
import tribixbite.cleverkeys.backup.SETTINGS_DEFAULTS
import tribixbite.cleverkeys.swipe.SwipeEngineRouter

/**
 * Pins the single definitions the 2026-10-20 audit's copy-paste LOW rows introduced, by scanning
 * `src/main/kotlin` (the house pattern of `DefaultsSingleDefinitionDriftTest`):
 *
 *  - **L13** the margin, Multi-Language and `swipe_engine_mode` key literals live only in their
 *    owners ([MarginPrefKeys], [LanguagePrefs], [SwipeEngineRouter.Mode.PREF_KEY]);
 *  - **L2** the orientation suffix is spelled only in [MarginPrefKeys.key] (keyboard height has its
 *    four whole keys in [KeyboardHeightPrefs]);
 *  - **L6** the "primary + secondary when Multi-Language is on" rule is applied only by
 *    [LanguagePrefs.userLanguages] / [LayoutLanguageBinding.resolve];
 *  - **L7** the personal-dictionary inputs of a swipe lexicon are read only by
 *    `swipe.UserLexiconInputs.read`.
 *
 * Moving a literal behind a constant hides it from `SettingsDefaultsDriftTest`'s literal scanner,
 * so the classification of these keys is asserted here directly.
 */
class PrefKeySingleDefinitionDriftTest {

    private val mainRoot = File("src/main/kotlin")

    /** Every main source file as (path relative to the package root, text). */
    private val sources: List<Pair<String, String>> by lazy {
        check(mainRoot.exists()) { "run with the project root as CWD (${mainRoot.absolutePath})" }
        mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.path.substringAfter("tribixbite/cleverkeys/") to it.readText() }
            .toList()
    }

    /** Files other than [owner] that contain [pattern]. */
    private fun filesOtherThan(owner: String, pattern: Regex): List<String> =
        sources.filter { (path, text) -> path != owner && pattern.containsMatchIn(text) }.map { it.first }

    @Test
    fun marginKeyLiteralsLiveOnlyInMarginPrefKeys() {
        val literal = Regex(""""margin_(?:bottom|left|right)(?:_(?:portrait|landscape)(?:_unfolded)?)?"""")
        assertWithMessage("margin keys must come from MarginPrefKeys")
            .that(filesOtherThan("MarginPrefKeys.kt", literal)).isEmpty()
    }

    @Test
    fun orientationSuffixIsSpelledOnce() {
        val suffix = Regex(""""_(?:portrait|landscape)(?:_unfolded)?"""")
        assertWithMessage("orientation suffixes must come from MarginPrefKeys.key")
            .that(filesOtherThan("MarginPrefKeys.kt", suffix)).isEmpty()
    }

    @Test
    fun languageKeyLiteralsLiveOnlyInLanguagePrefs() {
        val literal = Regex(""""pref_(?:primary_language|secondary_language)(?:_alt)?"|"pref_enable_multilang"""")
        assertWithMessage("Multi-Language keys must come from LanguagePrefs")
            .that(filesOtherThan("LanguagePrefs.kt", literal)).isEmpty()
    }

    @Test
    fun swipeEngineModeKeyLivesOnlyInTheRouter() {
        assertWithMessage("swipe_engine_mode must come from SwipeEngineRouter.Mode.PREF_KEY")
            .that(filesOtherThan("swipe/SwipeEngineRouter.kt", Regex(""""swipe_engine_mode""""))).isEmpty()
    }

    @Test
    fun theNoneSentinelRuleIsAppliedOnlyByTheResolver() {
        // The pasted rule compared the stored secondary against the sentinel by hand.
        val handRule = Regex("""!=\s*LanguageDisplayNames\.NONE""")
        assertWithMessage("secondary-language resolution must go through LanguagePrefs.userLanguages")
            .that(filesOtherThan("LayoutLanguageBinding.kt", handRule)).isEmpty()
    }

    @Test
    fun swipeLexiconInputsAreReadOnce() {
        val reads = Regex("""LanguagePreferenceKeys\.(?:customWordsKey|disabledWordsKey|swipePriorityKey)\(""")
        val inSwipe = sources.filter { (path, text) -> path.startsWith("swipe/") && reads.containsMatchIn(text) }
            .map { it.first }
        assertThat(inSwipe).containsExactly("swipe/UserLexiconInputs.kt")
    }

    @Test
    fun theCentralisedKeysStayClassifiedForBackup() {
        val keys = listOf(
            MarginPrefKeys.BOTTOM_PORTRAIT, MarginPrefKeys.BOTTOM_LANDSCAPE,
            MarginPrefKeys.LEFT_PORTRAIT, MarginPrefKeys.LEFT_LANDSCAPE,
            MarginPrefKeys.RIGHT_PORTRAIT, MarginPrefKeys.RIGHT_LANDSCAPE,
            LanguagePrefs.PRIMARY, LanguagePrefs.SECONDARY, LanguagePrefs.PRIMARY_ALT,
            LanguagePrefs.SECONDARY_ALT, LanguagePrefs.MULTILANG,
            SwipeEngineRouter.Mode.PREF_KEY,
        )
        assertThat(SETTINGS_DEFAULTS.keys).containsAtLeastElementsIn(keys)
    }

    @Test
    fun marginKeyTablesAreComplete() {
        assertThat(MarginPrefKeys.BOTTOM_KEYS).containsExactly(
            "margin_bottom_portrait", "margin_bottom_landscape",
            "margin_bottom_portrait_unfolded", "margin_bottom_landscape_unfolded",
        ).inOrder()
        assertThat(MarginPrefKeys.SIDE_KEYS).hasSize(8)
        assertThat(MarginPrefKeys.LEGACY_HORIZONTAL.first())
            .isEqualTo("horizontal_margin_portrait" to listOf("margin_left_portrait", "margin_right_portrait"))
        assertThat(MarginPrefKeys.LEGACY_HORIZONTAL.last()).isEqualTo(
            "horizontal_margin_landscape_unfolded" to
                listOf("margin_left_landscape_unfolded", "margin_right_landscape_unfolded")
        )
        assertThat(MarginPrefKeys.defaultPercentOf("margin_right_landscape_unfolded"))
            .isEqualTo(Defaults.MARGIN_RIGHT_LANDSCAPE)
    }
}
