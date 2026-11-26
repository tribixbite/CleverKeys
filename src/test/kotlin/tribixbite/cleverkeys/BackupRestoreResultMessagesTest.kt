package tribixbite.cleverkeys

import org.junit.Assert.*
import org.junit.Test

/**
 * Pure JVM coverage of the `buildSettingsResultMessage` /
 * `buildDictResultMessage` matrices extracted from
 * `BackupRestoreActivity`.
 *
 * Each test pins one user-visible state of the result dialog body so
 * future copy edits can't silently drop a conditional line, and so the
 * `driftCount` field stays logcat-only (NEVER user-visible).
 *
 * Since the 2026-09-29 i18n sweep the builders take a [ResultText]; these tests resolve
 * through [EnglishResourceText] (the English `res/values` text), so they still pin the
 * rendered English copy AND prove every line is a real resource with matching arguments.
 */
class BackupRestoreResultMessagesTest {

    // -------------------------------------------------------------------
    // Settings — buildSettingsResultMessage
    // -------------------------------------------------------------------

    @Test
    fun settings_minimal_appliedCountAndNoRestartLine() {
        val result = BackupRestoreManager.ImportResult().apply {
            importedCount = 5
        }

        val msg = buildSettingsResultMessage(result, EnglishResourceText)

        assertTrue("opening line", msg.contains("Import completed."))
        assertTrue("applied line", msg.contains("• Applied: 5"))
        // Pixel 2026-11-24: an import changed the number row and the height of the keyboard on
        // screen, same process, no restart. "Restart the keyboard for changes to take effect"
        // asked for something nothing needs.
        assertFalse("no restart line: $msg", msg.contains("Restart the keyboard"))
        assertEquals("no trailing blank line", msg.trimEnd(), msg)
        // The optional sections must NOT appear when their counters are 0
        assertFalse(msg.contains("Excluded by you"))
        assertFalse(msg.contains("Invalid/skipped"))
        assertFalse(msg.contains("Short-swipe applied"))
        assertFalse(msg.contains("Source version"))
        assertFalse(msg.contains("Screen-size mismatch"))
    }

    @Test
    fun settings_excludedByUserCount_rendersExcludedLine() {
        val result = BackupRestoreManager.ImportResult().apply {
            importedCount = 3
            excludedByUserCount = 2
        }

        val msg = buildSettingsResultMessage(result, EnglishResourceText)

        assertTrue(msg.contains("• Excluded by you: 2"))
    }

    @Test
    fun settings_skippedCount_rendersInvalidSkippedLine() {
        val result = BackupRestoreManager.ImportResult().apply {
            importedCount = 1
            skippedCount = 4
        }

        val msg = buildSettingsResultMessage(result, EnglishResourceText)

        assertTrue(msg.contains("• Not importable (internal/derived): 4"))
    }

    @Test
    fun settings_shortSwipeCustomizationsImported_rendersShortSwipeLine() {
        val result = BackupRestoreManager.ImportResult().apply {
            importedCount = 0
            shortSwipeCustomizationsImported = 7
        }

        val msg = buildSettingsResultMessage(result, EnglishResourceText)

        assertTrue(msg.contains("• Short-swipe applied: 7"))
    }

    @Test
    fun settings_sourceVersionNotUnknown_rendersSourceVersionLine() {
        val result = BackupRestoreManager.ImportResult().apply {
            importedCount = 1
            sourceVersion = "1.4.0"
        }

        val msg = buildSettingsResultMessage(result, EnglishResourceText)

        assertTrue(msg.contains("• Source version: 1.4.0"))
    }

    @Test
    fun settings_sourceVersionUnknown_omitsSourceVersionLine() {
        val result = BackupRestoreManager.ImportResult().apply {
            importedCount = 1
            sourceVersion = "unknown"
        }

        val msg = buildSettingsResultMessage(result, EnglishResourceText)

        assertFalse(msg.contains("Source version"))
    }

    @Test
    fun settings_screenMismatch_rendersScreenMismatchLine() {
        val result = BackupRestoreManager.ImportResult().apply {
            importedCount = 1
            sourceScreenWidth = 720
            sourceScreenHeight = 1280
            currentScreenWidth = 1080
            currentScreenHeight = 2400
        }

        val msg = buildSettingsResultMessage(result, EnglishResourceText)

        assertTrue(msg.contains("Screen-size mismatch"))
        assertTrue(msg.contains("source 720x1280"))
        assertTrue(msg.contains("current 1080x2400"))
        assertTrue(msg.contains("Some visual settings may need adjustment."))
    }

    @Test
    fun settings_screenWidthZero_omitsScreenMismatchLine() {
        val result = BackupRestoreManager.ImportResult().apply {
            importedCount = 1
            sourceScreenWidth = 0
            currentScreenWidth = 1080
        }

        val msg = buildSettingsResultMessage(result, EnglishResourceText)

        assertFalse(msg.contains("Screen-size mismatch"))
    }

    @Test
    fun settings_driftCount_isNeverUserVisible() {
        // Drift is logcat-only telemetry per spec — must never appear in the
        // user-visible message body. This locks down the contract.
        val result = BackupRestoreManager.ImportResult().apply {
            importedCount = 1
            driftCount = 7
        }

        val msg = buildSettingsResultMessage(result, EnglishResourceText)

        assertFalse(
            "drift count must not leak to user-visible message",
            msg.contains("drift", ignoreCase = true)
        )
        assertFalse(msg.contains("7 keys"))
    }

    // -------------------------------------------------------------------
    // Dictionary — buildDictResultMessage
    // -------------------------------------------------------------------

    @Test
    fun dict_minimal_rendersAppliedCounters() {
        val result = BackupRestoreManager.DictionaryImportResult().apply {
            userWordsImported = 3
            disabledWordsImported = 1
        }

        val msg = buildDictResultMessage(result, EnglishResourceText)

        assertTrue(msg.contains("Dictionary import completed."))
        assertTrue(msg.contains("• Custom words applied: 3"))
        assertTrue(msg.contains("• Disabled words applied: 1"))
        assertFalse(msg.contains("Excluded by you"))
        assertFalse(msg.contains("Source version"))
    }

    @Test
    fun dict_excludedByUserCount_rendersExcludedLine() {
        val result = BackupRestoreManager.DictionaryImportResult().apply {
            userWordsImported = 5
            excludedByUserCount = 2
        }

        val msg = buildDictResultMessage(result, EnglishResourceText)

        assertTrue(msg.contains("• Excluded by you: 2"))
    }

    @Test
    fun dict_sourceVersionNotUnknown_rendersSourceVersionLine() {
        val result = BackupRestoreManager.DictionaryImportResult().apply {
            userWordsImported = 5
            sourceVersion = "1.3.5"
        }

        val msg = buildDictResultMessage(result, EnglishResourceText)

        assertTrue(msg.contains("• Source version: 1.3.5"))
    }

    @Test
    fun dict_sourceVersionUnknown_omitsSourceVersionLine() {
        val result = BackupRestoreManager.DictionaryImportResult().apply {
            userWordsImported = 5
            sourceVersion = "unknown"
        }

        val msg = buildDictResultMessage(result, EnglishResourceText)

        assertFalse(msg.contains("Source version"))
    }

    /** Audit M2: learned data that was not restored is said so, not hidden behind success. */
    @Test
    fun dict_learnedDataIncomplete_rendersNotRestoredLine() {
        val result = BackupRestoreManager.DictionaryImportResult().apply {
            userWordsImported = 5
            learnedDataIncomplete = true
        }

        val msg = buildDictResultMessage(result, EnglishResourceText)

        assertTrue(msg.contains("• Learned language data: some entries could not be read and were skipped"))
    }

    @Test
    fun dict_learnedDataComplete_omitsNotRestoredLine() {
        val msg = buildDictResultMessage(BackupRestoreManager.DictionaryImportResult(), EnglishResourceText)

        assertFalse(msg.contains("Learned language data"))
    }

    // -------------------------------------------------------------------
    // Settings — words a 1.x settings file carries (2026-11-24)
    // -------------------------------------------------------------------

    @Test
    fun settings_wordsFromALegacySettingsFile_renderTheirCounts() {
        val result = BackupRestoreManager.ImportResult().apply {
            importedCount = 2
            customWordsAdded = 133
            disabledWordsAdded = 9
        }

        val msg = buildSettingsResultMessage(result, EnglishResourceText)

        assertTrue(msg, msg.contains("• Custom words applied: 133"))
        assertTrue(msg, msg.contains("• Disabled words applied: 9"))
    }

    @Test
    fun settings_noWords_omitsTheWordLines() {
        val msg = buildSettingsResultMessage(
            BackupRestoreManager.ImportResult().apply { importedCount = 2 }, EnglishResourceText
        )

        assertFalse(msg.contains("Custom words applied"))
        assertFalse(msg.contains("Disabled words applied"))
    }

    // -------------------------------------------------------------------
    // Clipboard — buildClipboardImportResultMessage (2026-11-24)
    // -------------------------------------------------------------------

    /** Pixel 2026-11-24: "• Source version: 2026-09-08 12:18:21" — the file's export date. */
    @Test
    fun clipboardZip_showsTheExportDateUnderItsOwnLabelNotAsAVersion() {
        val result = BackupRestoreManager.ClipboardImportResult().apply {
            importedCount = 18
            skippedCount = 5559
            mediaFilesRestored = 1
            exportDate = "2026-09-08 12:18:21"
        }

        val msg = buildClipboardImportResultMessage(result, withMedia = true, text = EnglishResourceText)

        assertTrue(msg, msg.startsWith("Full clipboard import completed."))
        assertTrue(msg, msg.contains("• Imported: 18 entries"))
        assertTrue(msg, msg.contains("• Skipped: 5559 duplicates"))
        assertTrue(msg, msg.contains("• Media files restored: 1"))
        assertTrue(msg, msg.contains("• Exported: 2026-09-08 12:18:21"))
        assertFalse(msg, msg.contains("Source version"))
        assertTrue(msg, msg.endsWith("All media files and thumbnails have been restored."))
    }

    @Test
    fun fullBackup_listsEachPart_andAsksForNoRestart() {
        // Settings, words and learned data reach the running keyboard through its preference
        // listener and the shared stores, so the restore needs no keyboard restart either.
        val result = BackupRestoreManager.FullBackupImportResult(
            success = true,
            configImported = true,
            configKeysApplied = 46,
            customWordsImported = 628,
            disabledWordsImported = 15,
            clipboardEntriesImported = 6969,
            clipboardEntriesSkipped = 2,
            mediaFilesRestored = 20,
            sourceAppVersion = "2.0.0",
        )

        val msg = buildFullBackupResultMessage(result, EnglishResourceText)

        assertEquals(
            listOf(
                "Full backup import completed.",
                "",
                "• Settings applied: 46",
                "• Custom words: 628",
                "• Disabled words: 15",
                "• Clipboard imported: 6969",
                "• Clipboard skipped: 2 (duplicates)",
                "• Media files restored: 20",
                "• Source version: 2.0.0",
            ),
            msg.lines(),
        )
    }

    @Test
    fun fullBackup_namesThemesAndUnreadLearnedData_onlyWhenThereAreAny() {
        val base = BackupRestoreManager.FullBackupImportResult(
            success = true, configImported = true, configKeysApplied = 1, customWordsImported = 0,
            disabledWordsImported = 0, clipboardEntriesImported = 0, clipboardEntriesSkipped = 0,
            mediaFilesRestored = 0,
        )
        val plain = buildFullBackupResultMessage(base, EnglishResourceText)
        assertFalse(plain, plain.contains("Custom themes restored"))
        assertFalse(plain, plain.contains("Learned language data"))
        assertFalse("no version line without one: $plain", plain.contains("Source version"))

        val full = buildFullBackupResultMessage(
            base.copy(customThemesImported = 2, learnedDataIncomplete = true),
            EnglishResourceText,
        )
        assertTrue(full, full.contains("• Custom themes restored: 2"))
        assertTrue(full, full.contains("• Learned language data: some entries could not be read and were skipped"))
    }

    /** Swipe-priority levels (from dictionaries.json or a 1.x config.json) get a line when any were added. */
    @Test
    fun fullBackup_namesSwipePriorities_onlyWhenAnyWereAdded() {
        val base = BackupRestoreManager.FullBackupImportResult(
            success = true, configImported = true, configKeysApplied = 1, customWordsImported = 2,
            disabledWordsImported = 0, clipboardEntriesImported = 0, clipboardEntriesSkipped = 0,
            mediaFilesRestored = 0,
        )
        val none = buildFullBackupResultMessage(base, EnglishResourceText)
        assertFalse(none, none.contains("Swipe priorities"))

        val some = buildFullBackupResultMessage(base.copy(swipePrioritiesImported = 3), EnglishResourceText)
        assertEquals(
            "directly after the word lines",
            listOf("• Custom words: 2", "• Disabled words: 0", "• Swipe priorities applied: 3"),
            some.lines().subList(3, 6),
        )
    }

    @Test
    fun clipboardZip_withNoMediaRestored_doesNotClaimMediaWasRestored() {
        val result = BackupRestoreManager.ClipboardImportResult().apply {
            importedCount = 0
            skippedCount = 40
            mediaFilesRestored = 0
        }

        val msg = buildClipboardImportResultMessage(result, withMedia = true, text = EnglishResourceText)

        assertTrue(msg, msg.contains("• Media files restored: 0"))
        assertFalse(msg, msg.contains("All media files and thumbnails have been restored."))
        assertTrue(msg, msg.endsWith("Imports merge with existing history without overwriting."))
    }

    @Test
    fun clipboardJson_namesTheAppVersionWhenTheFileHasOne_andHasNoMediaLine() {
        val result = BackupRestoreManager.ClipboardImportResult().apply {
            importedCount = 1
            sourceVersion = "2.0.0"
            exportDate = "2026-11-24 10:54:56"
        }

        val msg = buildClipboardImportResultMessage(result, withMedia = false, text = EnglishResourceText)

        assertTrue(msg, msg.startsWith("Clipboard import completed."))
        assertTrue(msg, msg.contains("• Imported: 1 entry"))
        assertTrue(msg, msg.contains("• Source version: 2.0.0"))
        assertTrue(msg, msg.contains("• Exported: 2026-11-24 10:54:56"))
        assertFalse(msg, msg.contains("Media files restored"))
        assertTrue(msg, msg.endsWith("Imports merge with existing history without overwriting."))
    }

    /** Review 2026-11-25 S9: a 1.x settings file's swipe priorities are reported like its words. */
    @Test
    fun settings_legacySwipePriorities_haveTheirOwnLine() {
        val result = BackupRestoreManager.ImportResult().apply { swipePrioritiesAdded = 3 }
        val msg = buildSettingsResultMessage(result, EnglishResourceText)
        assertTrue(msg, msg.contains("• Swipe priorities applied: 3"))
    }
}
