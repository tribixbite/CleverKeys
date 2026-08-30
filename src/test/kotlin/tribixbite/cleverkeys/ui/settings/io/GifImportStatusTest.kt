package tribixbite.cleverkeys.ui.settings.io

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * ARC-075 — the GIF import status is a TYPE, not a message that happens to be English.
 *
 * `GifPanelSection` used to decide whether an import status was a failure by testing
 * `status.startsWith("Error")` against a string produced three layers away in
 * `SettingsGifHandlers`. That coupling is invisible to both ends: the producer is free to reword
 * its message, and — the case that actually bites — the moment those messages are localized (or
 * a provider hands back a non-English `Exception.message`, which happens TODAY on a localized
 * ROM) the section renders a failure in the success colour with no code change anywhere.
 *
 * The fix is a typed status carrying the message verbatim, so the section branches on the
 * variant and never reads the text. These tests pin both halves:
 *
 *  - **behaviour** — classification comes from the import RESULT type, so a French/Greek/Turkish
 *    failure message classifies exactly like an English one, and the message survives untouched;
 *  - **wiring** — the section reads no message text, and the producer emits the typed value.
 *
 * Pure: `GifImportStatus` and `GifPackImportResult` are plain Kotlin types (their enclosing
 * files reach Android, but these classes do not), so `runPureTests` exercises the real thing.
 */
class GifImportStatusTest {

    private fun source(relative: String): String {
        val f = File("src/main/kotlin", relative)
        assertWithMessage("expected source file to exist: ${f.path} (run from project root)")
            .that(f.isFile).isTrue()
        return f.readText()
    }

    // ── behaviour: the variant decides, in every language ────────────────────────────

    /**
     * The regression itself. `GifPackManager` surfaces `Exception.message` and
     * `ContentResolver`/zip failures straight through, and those are localized by the platform —
     * so this is the shipping case, not a hypothetical future one.
     */
    @Test
    fun `a failure classifies as a failure whatever language its message is in`() {
        val messages = listOf(
            "Échec : impossible d'ouvrir le fichier",      // fr
            "Σφάλμα: μη έγκυρο manifest.json",             // el
            "Dosya açılamıyor",                            // tr
            "无法打开文件",                                  // zh
        )
        for (message in messages) {
            val status = GifImportStatus.forImportResult(
                tribixbite.cleverkeys.gif.GifPackImportResult.Error(message)
            )
            assertWithMessage(
                "ARC-075: '$message' is a failed import and must render as one; the pre-fix " +
                    "rule (startsWith(\"Error\")) reads it as a success and paints it in the " +
                    "primary colour."
            ).that(status).isInstanceOf(GifImportStatus.Failed::class.java)
            assertWithMessage("the pre-fix English-prefix rule misses this message entirely")
                .that(message.startsWith("Error")).isFalse()
            assertWithMessage("the message must reach the user exactly as produced")
                .that(status.message).isEqualTo(message)
        }
    }

    @Test
    fun `a successful import is never a failure, whatever the pack is called`() {
        val ok = GifImportStatus.forImportResult(
            tribixbite.cleverkeys.gif.GifPackImportResult.Success("err.pack", "Error Handling", 3)
        )
        assertThat(ok).isInstanceOf(GifImportStatus.Ok::class.java)
        assertWithMessage("a pack NAME must not be able to steer the render branch")
            .that(ok.message).contains("Error Handling")

        val installed = GifImportStatus.forImportResult(
            tribixbite.cleverkeys.gif.GifPackImportResult.AlreadyInstalled("err.pack", "Error")
        )
        assertThat(installed).isInstanceOf(GifImportStatus.Ok::class.java)
    }

    @Test
    fun `progress and failure are distinguishable without reading the text`() {
        assertThat(GifImportStatus.Ok("Importing…")).isInstanceOf(GifImportStatus.Ok::class.java)
        assertThat(GifImportStatus.Failed("boom").message).isEqualTo("boom")
    }

    // ── wiring: neither end may read the message to decide anything ──────────────────

    @Test
    fun `the section branches on the status type and renders the message verbatim`() {
        val section = source("tribixbite/cleverkeys/ui/settings/sections/GifPanelSection.kt")

        assertWithMessage(
            "ARC-075: the section must not classify a status by its English text — that couples " +
                "a render decision to copy produced in SettingsGifHandlers."
        ).that(section).doesNotContain("startsWith(\"Error\")")

        assertWithMessage("the error branch must be selected by the status VARIANT")
            .that(section).contains("GifImportStatus.Failed")

        assertWithMessage(
            "and the message must be rendered as produced — no prefix stripping, no rewording."
        ).that(section).contains("status.message")
    }

    @Test
    fun `the producer emits the typed status, not a prefixed string`() {
        val handlers = source("tribixbite/cleverkeys/ui/settings/io/SettingsGifHandlers.kt")

        assertWithMessage(
            "ARC-075: every gifImportStatus assignment must carry the typed value; a raw string " +
                "assignment is how the English-prefix protocol got established in the first place."
        ).that(handlers).doesNotContain("gifImportStatus = \"")

        assertWithMessage("the failure paths must construct the failure variant explicitly")
            .that(handlers).contains("GifImportStatus.Failed(")
    }
}
