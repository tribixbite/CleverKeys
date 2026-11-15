package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.backup.LangWord

/**
 * Review 2026-11-14 (low): the import previews kept their exclusions and modes in `remember`, so
 * rotating the screen with a preview open reset them to "apply everything".
 */
class BackupPreviewSaveableTest {

    private val source = SourceScan.read("src/main/kotlin/tribixbite/cleverkeys/BackupRestorePreviewDialogs.kt")

    @Test
    fun previewChoicesAreDeclaredSaveable() {
        for (name in listOf("excluded", "shortSwipeMode", "customThemeMode", "excludedCustom", "excludedDisabled")) {
            assertWithMessage("$name must survive rotation")
                .that(Regex("""val\s+$name\s*=\s*rememberSaveable\b""").containsMatchIn(source)).isTrue()
        }
    }

    @Test
    fun langWordSetsRoundTrip() {
        val words = linkedSetOf(LangWord("en", "colour"), LangWord("fa", "کتاب"), LangWord("en", ""))
        // The encode/decode pair behind PreviewStateSavers.LangWordSet (Compose's Saver classes
        // are not on the pure tier's runtime classpath).
        val saved = LangWordSetCodec.encode(words)
        assertThat(LangWordSetCodec.decode(saved)).containsExactlyElementsIn(words).inOrder()
    }
}
