package tribixbite.cleverkeys.ui.settings

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * #94 — long-press on the settings Version Information card copies the version block.
 *
 * Two halves, tested at the tier each honestly supports:
 *
 *  1. **The payload assembly** ([buildVersionCopyPayload]) is pure and runs for real here:
 *     what lands on the clipboard is the card's own displayed strings (title + build line),
 *     plus commit/date lines only when `version_info.txt` carries them — the reproducible
 *     release recipe writes only `version=`, so the usual payload is exactly two lines.
 *  2. **The gesture wiring** is a `@Composable` (needs an instrumented host), so — per the
 *     `TestKeyboardSectionTest` idiom — the long-press → clipboard → toast chain is pinned
 *     by scanning the composition source. The a11y surface and card presence run for real
 *     on-device in `Issue94VersionCopyComposeTest` (androidTest).
 *
 * The copy is deliberately a NORMAL `setPrimaryClip` copy: version info is not sensitive,
 * and entering CleverKeys' own clipboard history is part of the bug-reporting flow the
 * feature exists for — that choice is pinned too (no `privateCopy` here).
 *
 * Pure tier: `scripts/gradle-guard.sh runPureTests -PtestClass=ui.settings.VersionCopyPayloadTest`.
 */
class VersionCopyPayloadTest {

    // =========================================================================
    // Payload assembly (behavioural)
    // =========================================================================

    @Test
    fun payloadIsTitleThenBuildLine() {
        val payload = buildVersionCopyPayload(
            title = "📱 Version Information",
            buildText = "Build: 1.2.8",
            commit = null,
            date = null,
        )
        assertThat(payload).isEqualTo("📱 Version Information\nBuild: 1.2.8")
    }

    @Test
    fun commitAndDateEachContributeALineOnlyWhenPresent() {
        assertThat(buildVersionCopyPayload("T", "B", commit = "abc1234", date = null))
            .isEqualTo("T\nB\nabc1234")
        assertThat(buildVersionCopyPayload("T", "B", commit = null, date = "2026-09-05"))
            .isEqualTo("T\nB\n2026-09-05")
        assertThat(buildVersionCopyPayload("T", "B", commit = "abc1234", date = "2026-09-05"))
            .isEqualTo("T\nB\nabc1234\n2026-09-05")
    }

    @Test
    fun payloadHasNoTrailingNewline() {
        // A trailing newline would paste an extra blank line into every bug report.
        assertThat(buildVersionCopyPayload("T", "B", null, null)).doesNotMatch("""[\s\S]*\n$""")
        assertThat(buildVersionCopyPayload("T", "B", "c", "d")).doesNotMatch("""[\s\S]*\n$""")
    }

    // =========================================================================
    // Gesture wiring (source pin — Compose needs an instrumented host)
    // =========================================================================

    private val cardSource by lazy {
        val file = File("src/main/kotlin/tribixbite/cleverkeys/ui/settings/SettingsInfoCards.kt")
        check(file.isFile) { "${file.path} not found — run with the project root as CWD." }
        file.readText()
    }

    @Test
    fun longPressCopiesThePayloadAndConfirms() {
        assertWithMessage("the card must react to LONG press (combinedClickable onLongClick), per #94")
            .that(Regex("""onLongClick\s*=\s*\{""").containsMatchIn(cardSource)).isTrue()
        assertWithMessage("the long-press must copy the assembled payload")
            .that(cardSource).contains("buildVersionCopyPayload(")
        assertWithMessage("the copy must be a normal primary-clip copy so it enters clipboard history")
            .that(cardSource).contains("setPrimaryClip(")
        assertWithMessage("version info is not sensitive — it must NOT use the private no-history copy path")
            .that(cardSource).doesNotContain("privateCopy")
        assertWithMessage("the user must get feedback via the localized confirmation toast")
            .that(cardSource).contains("R.string.settings_version_copied")
        assertWithMessage("the gesture must be discoverable to TalkBack via the localized description")
            .that(cardSource).contains("R.string.settings_copy_version_desc")
    }

    @Test
    fun copyStringsAreLocalizedEverywhere() {
        // MissingTranslation is lint-error-enforced; this pins it at test tier too so a new
        // locale directory cannot land without the copy feature's strings.
        val localeDirs = File("res").listFiles { f ->
            f.isDirectory && f.name.startsWith("values") &&
                // Non-locale qualifiers carry no translations.
                f.name != "values-night" && !Regex("""values-v\d+""").matches(f.name)
        }.orEmpty()
        assertWithMessage("expected the 22 translated locale dirs + base values/")
            .that(localeDirs.size).isAtLeast(22)
        for (dir in localeDirs) {
            val strings = File(dir, "strings.xml").readText()
            for (key in listOf("settings_version_copied", "settings_copy_version_desc")) {
                assertWithMessage("${dir.name}/strings.xml must translate $key")
                    .that(strings).contains("name=\"$key\"")
            }
        }
    }
}
