package tribixbite.cleverkeys

import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tribixbite.cleverkeys.backup.SkipKind
import tribixbite.cleverkeys.backup.crypto.BackupFormatException
import tribixbite.cleverkeys.backup.crypto.EncryptedBackupFormat

/**
 * Import/export/backup failures reach the user as a localized reason, never as the exception's
 * English (or ROM-localized) message inside a translated "…failed: %1$s" wrapper.
 *
 * 2026-09-29 device finding (fa/hu): the wrappers were translated but the detail was
 * `e.message` — e.g. "Backup ZIP contains duplicate entry 'x'" inside a Persian sentence.
 * Pins:
 *  - [IoFailureClassifier] maps the known exception categories (and walks wrapper causes);
 *  - every [IoFailureReason], [PackImportFailure] sentence and [SkipKind] label exists in every
 *    locale with the English placeholders;
 *  - [PackImportFailure] renders its arguments into the localized sentence;
 *  - no settings I/O handler formats an exception or manager message into a string resource.
 */
class IoFailureLocalizationTest {

    private fun classify(t: Throwable?) = IoFailureClassifier.classify(t)

    // ── classification ────────────────────────────────────────────────────────────────

    @Test fun platformExceptionsMapToTheirCategory() {
        assertEquals(IoFailureReason.FILE_NOT_FOUND, classify(FileNotFoundException("/x: open failed: ENOENT")))
        assertEquals(
            "EACCES inside a FileNotFoundException is a permission problem, not a missing file",
            IoFailureReason.PERMISSION_DENIED,
            classify(FileNotFoundException("/x: open failed: EACCES (Permission denied)")),
        )
        assertEquals(IoFailureReason.PERMISSION_DENIED, classify(SecurityException("Permission Denial: reading uri")))
        assertEquals(
            IoFailureReason.OUT_OF_SPACE,
            classify(IOException("write failed: ENOSPC (No space left on device)")),
        )
        assertEquals(IoFailureReason.INVALID_FORMAT, classify(ZipException("invalid entry size")))
        assertEquals(IoFailureReason.INVALID_FORMAT, classify(EOFException("Unexpected end of ZLIB input stream")))
        assertEquals(IoFailureReason.INVALID_FORMAT, classify(com.google.gson.JsonSyntaxException("bad json")))
        assertEquals(IoFailureReason.INVALID_FORMAT, classify(NumberFormatException("For input string: \"x\"")))
        assertEquals(
            IoFailureReason.WRONG_PASSWORD_OR_CORRUPT,
            classify(javax.crypto.AEADBadTagException("Tag mismatch")),
        )
        assertEquals(IoFailureReason.READ_WRITE, classify(IOException("Stream closed")))
        assertEquals(IoFailureReason.UNKNOWN, classify(IllegalStateException("something else")))
        assertEquals(IoFailureReason.UNKNOWN, classify(null))
    }

    @Test fun wrappersAreSeenThroughAndTheMostSpecificCauseWins() {
        // BackupRestoreManager wraps as Exception("Import failed: ${e.message}", e).
        val wrapped = Exception("Import failed: invalid entry size", ZipException("invalid entry size"))
        assertEquals(IoFailureReason.INVALID_FORMAT, classify(wrapped))
        // A generic IOException outside a specific cause must not mask it.
        val layered = IOException("copy failed", IOException("ENOSPC (No space left on device)"))
        assertEquals(IoFailureReason.OUT_OF_SPACE, classify(layered))
        val generic = RuntimeException("x", IOException("Stream closed"))
        assertEquals(IoFailureReason.READ_WRITE, classify(generic))
    }

    @Test fun typedThrowSitesCarryTheirReason() {
        assertEquals(
            IoFailureReason.TOO_LARGE,
            classify(Exception("wrap", ClassifiedIoException(IoFailureReason.TOO_LARGE, "limit"))),
        )
        // A CKENC header from a newer app version vs. one that is simply damaged.
        val newer = EncryptedBackupFormat.MAGIC.copyOf(EncryptedBackupFormat.HEADER_LEN).also {
            it[EncryptedBackupFormat.MAGIC.size] = (EncryptedBackupFormat.FORMAT_VERSION + 1).toByte()
        }
        val newerError = runCatching { EncryptedBackupFormat.parse(newer) }.exceptionOrNull()
        assertTrue("expected BackupFormatException, got $newerError", newerError is BackupFormatException)
        assertEquals(IoFailureReason.NEWER_VERSION, classify(newerError))
        val damaged = runCatching { EncryptedBackupFormat.parse(ByteArray(3)) }.exceptionOrNull()
        assertEquals(IoFailureReason.INVALID_FORMAT, classify(damaged))
    }

    // ── rendering ────────────────────────────────────────────────────────────────────

    @Test fun packFailuresRenderArgumentsIntoTheLocalizedSentence() {
        val text = EnglishResourceText
        assertEquals("The pack does not contain dictionary.bin.", PackImportFailure.MissingMember("dictionary.bin").render(text))
        assertEquals("manifest.json in the pack is empty or damaged.", PackImportFailure.InvalidMember("manifest.json").render(text))
        assertEquals(
            "The pack declares an invalid language code: ../x",
            PackImportFailure.InvalidLanguageCode("../x").render(text),
        )
        assertEquals(
            "model.onnx is larger than the 64 MiB limit.",
            PackImportFailure.ModelTooLarge("model.onnx", 64).render(text),
        )
        val io = PackImportFailure.fromException(Exception("Import failed", ZipException("bad")))
        assertEquals(PackImportFailure.Io(IoFailureReason.INVALID_FORMAT, "Import failed: Import failed"), io)
        assertEquals("The file is not in the expected format, or it is damaged.", io.render(text))
        assertNotEquals("the raw log text must never be what the user reads", io.logMessage, io.render(text))
    }

    // ── every sentence exists in every locale ────────────────────────────────────────

    private val stringNames: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    private val placeholder = Regex("%[0-9]+\\$[sd]")

    private fun keysUnderTest(): Set<String> {
        val ids = IoFailureReason.values().map { it.messageRes } + SkipKind.values().map { it.labelRes }
        val packKeys = TranslationResources.strings(TranslationResources.defaultDir).keys
            .filter { it.startsWith("pack_error_") || it.startsWith("gif_pack_error_") }
        return ids.map { stringNames.getValue(it) }.toSet() + packKeys
    }

    @Test fun everyFailureSentenceIsTranslatedInEveryLocaleWithItsPlaceholders() {
        val english = TranslationResources.strings(TranslationResources.defaultDir)
        val keys = keysUnderTest()
        assertTrue("expected the failure/skip vocabulary, found ${keys.size}", keys.size >= 26)
        val problems = mutableListOf<String>()
        for (dir in TranslationResources.localeDirs) {
            val locale = TranslationResources.strings(dir)
            for (key in keys) {
                val en = english[key]
                if (en == null) { problems += "res/values is missing $key"; continue }
                val tr = locale[key]
                if (tr == null) { problems += "${dir.name} is missing $key"; continue }
                if (key != "common_unknown_error" && tr == en) problems += "${dir.name}/$key is the English text"
                val want = placeholder.findAll(en).map { it.value }.sorted().toList()
                val got = placeholder.findAll(tr).map { it.value }.sorted().toList()
                if (want != got) problems += "${dir.name}/$key placeholders $got, English has $want"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    // ── wiring: no raw message formatted into user copy ──────────────────────────────

    /**
     * `getString(R.string.x, …e.message…)` or `…result.message…` in the settings I/O handlers is
     * the shape this change removed. The clipboard custom-rules status is exempt: its detail is
     * the parse error in the USER'S OWN rule text (which rule, which position), shown so the user
     * can fix the rule.
     */
    @Test fun settingsIoHandlersNeverFormatARawMessageIntoCopy() {
        val allowed = setOf("clipboard_rules_saved_malformed", "clipboard_rules_invalid_detail")
        val rawMessage = Regex("getString\\(\\s*R\\.string\\.(\\w+)[^\\n]*\\b(?:e|result|it)\\.message")
        val files = File("src/main/kotlin/tribixbite/cleverkeys/ui/settings/io").listFiles().orEmpty()
            .filter { it.extension == "kt" } +
            File("src/main/kotlin/tribixbite/cleverkeys/activities/BackupRestoreActivity.kt")
        assertTrue(files.size >= 8)
        val offenders = mutableListOf<String>()
        for (f in files) {
            KotlinSourceScan.stripComments(f.readText()).lines().forEachIndexed { i, line ->
                val m = rawMessage.find(line)
                if (m != null && m.groupValues[1] !in allowed) offenders += "${f.name}:${i + 1}: ${line.trim()}"
            }
        }
        assertTrue(
            "raw exception/manager message formatted into a string resource — use " +
                "ioFailureText(e) / PackImportFailure.render:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
