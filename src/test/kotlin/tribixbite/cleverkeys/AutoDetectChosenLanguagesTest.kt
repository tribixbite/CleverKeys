package tribixbite.cleverkeys

import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Auto-Detect Language chooses between the user's two languages only (pre-tag review S7,
 * 2026-11-25), with the real [LanguageDetector] scores. Mock tier only for `android.util.Log`
 * ([MultiLanguageManager] logs every switch); nothing else is mocked.
 *
 * Two defects this pins:
 * - The detector took the best of ITS six languages and the manager filtered to the chosen two
 *   afterwards, so with English + Spanish chosen, Spanish text that scored a little higher as
 *   Portuguese switched nothing.
 * - A chosen language the detector has no profile for (Italian, Dutch, any imported pack) could
 *   never be switched to, yet Settings offered Auto-Detect for the pair: the keyboard could move
 *   to the other language and never come back.
 */
class AutoDetectChosenLanguagesTest {

    /** Spanish; the detector's six-language argmax is Portuguese (0.427 vs Spanish 0.416). */
    private val spanishScoredAsPortuguese =
        "de que a se por para no como mamá mucho amo todo".split(" ")

    private val english = "the cat is on the table and it is not for you to have".split(" ")

    /** The lowest Detection Sensitivity the Settings slider offers (0.4..0.9). */
    private val loosest = 0.4f

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() = unmockkStatic(Log::class)

    @Test
    fun spanishThatScoresAsPortugueseSwitchesToSpanishWhenEnglishAndSpanishAreChosen() {
        val manager = MultiLanguageManager("en")
        val unrestricted = manager.getLanguageConfidence(spanishScoredAsPortuguese)
        assertWithMessage("precondition: the detector's own argmax is not a chosen language")
            .that(unrestricted?.language).isEqualTo("pt")

        val switched = manager.detectAndSwitch(spanishScoredAsPortuguese, loosest, setOf("en", "es"))
        assertWithMessage("the best of the CHOSEN languages, above the sensitivity").that(switched).isEqualTo("es")
        assertThat(manager.getCurrentLanguage()).isEqualTo("es")
    }

    @Test
    fun theSensitivityStillGatesTheChosenLanguagesBest() {
        val manager = MultiLanguageManager("en")
        assertWithMessage("Spanish scores ~0.42: below a 0.6 sensitivity nothing switches")
            .that(manager.detectAndSwitch(spanishScoredAsPortuguese, 0.6f, setOf("en", "es"))).isNull()
        assertThat(manager.getCurrentLanguage()).isEqualTo("en")
    }

    @Test
    fun aPairWithALanguageTheDetectorCannotScoreNeverSwitches() {
        // Italian is a bundled secondary language with no detector profile. Switching to English
        // on English text would strand the keyboard there: Italian text can never score.
        val manager = MultiLanguageManager("it")
        val unrestricted = manager.getLanguageConfidence(english)
        assertWithMessage("precondition: English text clears the sensitivity as English")
            .that(unrestricted?.language).isEqualTo("en")
        assertThat(unrestricted!!.confidence).isAtLeast(loosest)

        assertWithMessage("no half-working detection for it + en")
            .that(manager.detectAndSwitch(english, loosest, setOf("it", "en"))).isNull()
        assertThat(manager.getCurrentLanguage()).isEqualTo("it")
    }

    @Test
    fun theDetectorChoosesAmongTheCandidatesBeforeTheArgmax() {
        val detector = LanguageDetector()
        val restricted = detector.detectLanguageFromWordsWithConfidence(spanishScoredAsPortuguese, setOf("en", "es"))
        assertThat(restricted?.language).isEqualTo("es")
        val spanishAlone = detector.detectLanguageFromWordsWithConfidence(spanishScoredAsPortuguese, setOf("es"))
        assertWithMessage("a score is absolute: restricting the choice does not change it, so the sensitivity means the same")
            .that(restricted!!.confidence).isEqualTo(spanishAlone!!.confidence)
        assertWithMessage("unrestricted, Portuguese still wins")
            .that(detector.detectLanguageFromWordsWithConfidence(spanishScoredAsPortuguese)?.language).isEqualTo("pt")
        assertWithMessage("no candidate with a profile: nothing to choose")
            .that(detector.detectLanguageFromWordsWithConfidence(english, setOf("it", "nl"))).isNull()
    }

    @Test
    fun autoDetectIsOfferedOnlyForTwoLanguagesTheDetectorCanScore() {
        assertThat(LanguageDetector.canChooseBetween(listOf("en", "es"))).isTrue()
        assertThat(LanguageDetector.canChooseBetween(listOf("sv", "de"))).isTrue()
        assertWithMessage("Italian has no profile").that(LanguageDetector.canChooseBetween(listOf("en", "it"))).isFalse()
        assertWithMessage("an imported pack").that(LanguageDetector.canChooseBetween(listOf("nl", "fa"))).isFalse()
        assertWithMessage("one language is no choice").that(LanguageDetector.canChooseBetween(listOf("en", "en"))).isFalse()
    }

    /**
     * `multilang_auto_detect_unavailable` names the languages the detector recognizes, in every
     * locale. A profile added or removed must update that string everywhere.
     */
    @Test
    fun theSettingsNoteNamesExactlyTheDetectorsLanguages() {
        assertWithMessage("update multilang_auto_detect_unavailable in res/values*/strings.xml")
            .that(LanguageDetector().getSupportedLanguages().toSet())
            .containsExactly("en", "es", "fr", "pt", "de", "sv")
    }

    /** Settings shows the switch only for a pair the detector can score, and says why otherwise. */
    @Test
    fun settingsGatesTheSwitchOnTheDetector() {
        val f = File("src/main/kotlin/tribixbite/cleverkeys/ui/settings/sections/MultiLanguageSection.kt")
        assertWithMessage("expected ${f.path} (run from the project root)").that(f.isFile).isTrue()
        val src = KotlinSourceScan.stripComments(f.readText())
        val gate = Regex("""\bval\s+detectable\s*=\s*LanguageDetector\.canChooseBetween\s*\(\s*listOf\s*\(\s*primaryLanguage\s*,\s*secondaryLanguage\s*\)\s*\)""")
        assertWithMessage("the pair is checked with the detector").that(gate.containsMatchIn(src)).isTrue()
        val guarded = Regex("""\bif\s*\(\s*detectable\s*\)\s*\{\s*SettingsSwitch\s*\(\s*title\s*=\s*stringResource\s*\(\s*R\.string\.multilang_auto_detect_title\b""")
        assertWithMessage("the Auto-Detect switch is inside the gate").that(guarded.containsMatchIn(src)).isTrue()
        assertWithMessage("the note explains an unavailable pair")
            .that(Regex("""\bR\.string\.multilang_auto_detect_unavailable\b""").containsMatchIn(src)).isTrue()
        assertWithMessage("the sensitivity slider follows the gate")
            .that(Regex("""\bif\s*\(\s*detectable\s*&&\s*autoDetectLanguage\s*\)""").containsMatchIn(src)).isTrue()
    }
}
