package tribixbite.cleverkeys.backup

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * ARC-035 pure-JVM tests for [BackupExportNaming].
 *
 * The troubleshooting wiki has told users since backup encryption shipped that encrypted exports
 * carry a `.ckenc` suffix; no code produced one. These tests pin the naming rule *and* the MIME
 * switch that makes the suffix survive `DocumentsProvider.createDocument` (which rewrites an
 * extension that disagrees with the requested MIME type).
 */
class BackupExportNamingTest {

    // ── Plaintext exports keep today's names and types ─────────────────────

    @Test
    fun plaintextJsonExport_keepsPlainNameAndMime() {
        val name = BackupExportNaming.forExport(
            "cleverkeys-config.json", "application/json", willEncrypt = false
        )
        assertThat(name.fileName).isEqualTo("cleverkeys-config.json")
        assertThat(name.mimeType).isEqualTo("application/json")
    }

    @Test
    fun plaintextZipExport_keepsPlainNameAndMime() {
        val name = BackupExportNaming.forExport(
            "cleverkeys_full_backup_2026-08-28.zip", "application/zip", willEncrypt = false
        )
        assertThat(name.fileName).isEqualTo("cleverkeys_full_backup_2026-08-28.zip")
        assertThat(name.mimeType).isEqualTo("application/zip")
    }

    // ── Encrypted exports gain .ckenc — appended, not substituted ──────────

    @Test
    fun encryptedJsonExport_appendsCkencAfterTheOriginalExtension() {
        // The wiki's exact promise: the suffix is APPENDED to the normal name, so the payload
        // kind stays legible (`.json.ckenc`, not `.ckenc` replacing `.json`).
        val name = BackupExportNaming.forExport(
            "cleverkeys-config.json", "application/json", willEncrypt = true
        )
        assertThat(name.fileName).isEqualTo("cleverkeys-config.json.ckenc")
    }

    @Test
    fun encryptedZipExport_appendsCkencAfterTheOriginalExtension() {
        val name = BackupExportNaming.forExport(
            "cleverkeys-clipboard-full.zip", "application/zip", willEncrypt = true
        )
        assertThat(name.fileName).isEqualTo("cleverkeys-clipboard-full.zip.ckenc")
    }

    @Test
    fun encryptedExport_requestsTheDefaultMimeSoTheSuffixSurvivesTheProvider() {
        // Load-bearing, not cosmetic: asking for application/json while suggesting a `.ckenc`
        // name makes AOSP's FileUtils.splitFileName append the MIME's own extension, producing
        // `…​.json.ckenc.json`. MIME_TYPE_DEFAULT implies no extension, so `.ckenc` is preserved.
        val json = BackupExportNaming.forExport("x.json", "application/json", willEncrypt = true)
        val zip = BackupExportNaming.forExport("x.zip", "application/zip", willEncrypt = true)
        assertThat(json.mimeType).isEqualTo("application/octet-stream")
        assertThat(zip.mimeType).isEqualTo("application/octet-stream")
    }

    // ── Cross-file invariants ──────────────────────────────────────────────

    @Test
    fun everyImportPickerStillAcceptsWildcardSoCkencFilesStaySelectable() {
        // Import detection sniffs the CKENC1 magic, never the extension — but a picker whose MIME
        // filter excluded `.ckenc` would make an encrypted backup unselectable in the first place,
        // which is exactly the trap this rename could have introduced. `.ckenc` has no registered
        // MIME mapping, so it only resolves under the `*/*` entry each picker carries.
        val ioDir = File("src/main/kotlin/tribixbite/cleverkeys/ui/settings/io")
        check(ioDir.isDirectory) {
            "io handler dir not found at ${ioDir.absolutePath} — test must run with project root as CWD."
        }
        val launches = Regex("""ImportLauncher\.launch\(arrayOf\(([^)]*)\)\)""")
        val offenders = mutableListOf<String>()
        var found = 0
        ioDir.listFiles { f -> f.name.endsWith(".kt") }.orEmpty().forEach { file ->
            launches.findAll(file.readText()).forEach { m ->
                found++
                if (!m.groupValues[1].contains("\"*/*\"")) {
                    offenders += "${file.name}: ${m.value}"
                }
            }
        }
        check(found > 0) { "No import-launcher MIME arrays found — the scanner is broken, not a real pass." }
        assertThat(offenders).isEmpty()
    }

    @Test
    fun everyEncryptableExportSeedsThePickerThroughTheNamingRule() {
        // A new encryptable exporter that calls launch("literal.json") would silently reintroduce
        // the gap this item closed. Encryptable exporters are exactly those that resolve an
        // EncryptionPolicy via exportPolicy(); each must name its file through exportName().
        val ioDir = File("src/main/kotlin/tribixbite/cleverkeys/ui/settings/io")
        val encryptableLaunchers = mutableSetOf<String>()
        val namedLaunchers = mutableSetOf<String>()
        ioDir.listFiles { f -> f.name.endsWith(".kt") }.orEmpty().forEach { file ->
            val text = file.readText()
            Regex("""(\w+ExportLauncher)\.launch\(""").findAll(text).forEach {
                encryptableLaunchers += it.groupValues[1]
            }
            Regex("""(\w+ExportLauncher)\.launch\(\s*(?://[^\n]*\n\s*)?exportName\(""")
                .findAll(text).forEach { namedLaunchers += it.groupValues[1] }
        }
        // Swipe-ML and perf-stats exports are never encrypted (they don't go through
        // exportPolicy), so they legitimately keep a literal name.
        val neverEncrypted = setOf(
            "swipeDataJsonExportLauncher",
            "swipeDataNdjsonExportLauncher",
            "perfStatsExportLauncher",
        )
        check(encryptableLaunchers.isNotEmpty()) { "No export launchers found — scanner broken." }
        assertThat(encryptableLaunchers - neverEncrypted - namedLaunchers).isEmpty()
    }
}
