package tribixbite.cleverkeys.ui.settings.io

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * The import/export result dialogs name the file a person picked or created.
 *
 * Bug (2026-10-26, Pixel 8 Pro): "File: 932". The dialogs printed `uri.lastPathSegment`, which
 * for a Downloads/MediaStore document URI is the provider's numeric document id. The fix
 * resolves `OpenableColumns.DISPLAY_NAME` (Android half: `DocumentDisplayNameResolveTest`, a
 * mock test) and falls back through [DocumentNamePolicy.choose], pinned here; the wiring test
 * below keeps every result dialog on the shared helper.
 */
class DocumentNamePolicyTest {

    @Test
    fun `the provider display name wins over the document id`() {
        assertThat(
            DocumentNamePolicy.choose(
                "cleverkeys-dictionary.json",
                "932",
                "content://com.android.providers.downloads.documents/document/932",
            )
        ).isEqualTo("cleverkeys-dictionary.json")
    }

    @Test
    fun `a blank display name falls back to the path`() {
        assertThat(DocumentNamePolicy.choose("  ", "backup.zip", "file:///sdcard/backup.zip"))
            .isEqualTo("backup.zip")
    }

    @Test
    fun `an external-storage document id keeps only the file name`() {
        // lastPathSegment is already percent-decoded: primary%3ADownload%2Fx.json -> primary:Download/x.json
        assertThat(
            DocumentNamePolicy.choose(
                null,
                "primary:Download/CleverKeys/cleverkeys-settings.json",
                "content://com.android.externalstorage.documents/document/primary%3ADownload%2FCleverKeys%2Fcleverkeys-settings.json",
            )
        ).isEqualTo("cleverkeys-settings.json")
    }

    @Test
    fun `a prefixed media id keeps its tail`() {
        assertThat(DocumentNamePolicy.choose(null, "msf:932", "content://x/document/msf%3A932"))
            .isEqualTo("932")
    }

    @Test
    fun `with no usable segment the whole uri is shown, never an empty label`() {
        assertThat(DocumentNamePolicy.choose(null, null, "content://provider")).isEqualTo("content://provider")
        assertThat(DocumentNamePolicy.choose(null, "trailing:", "content://p/trailing%3A"))
            .isEqualTo("content://p/trailing%3A")
    }

    /**
     * Wiring: every result dialog that prints `common_file_name` passes the resolved name, and no
     * Settings import/export handler reads `lastPathSegment` for display any more.
     */
    @Test
    fun `every file-name dialog line uses the shared resolver`() {
        val ioDir = File("src/main/kotlin/tribixbite/cleverkeys/ui/settings/io")
        val handlers = ioDir.listFiles { f -> f.name.endsWith(".kt") && f.name != "DocumentDisplayName.kt" }!!
        assertThat(handlers).isNotEmpty()
        var fileNameLines = 0
        for (file in handlers) {
            val text = file.readText()
            assertWithMessage("${file.name} reads lastPathSegment for display")
                .that(text).doesNotContain("lastPathSegment")
            Regex("""R\.string\.common_file_name,\s*([^)\n]*\)?)""").findAll(text).forEach { m ->
                fileNameLines++
                assertWithMessage("${file.name}: common_file_name argument")
                    .that(m.groupValues[1]).startsWith("documentDisplayName(uri)")
            }
        }
        // Settings, full backup, dictionary, clipboard JSON and clipboard ZIP exports.
        assertThat(fileNameLines).isAtLeast(5)
    }
}
