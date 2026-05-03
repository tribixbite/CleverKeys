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
 */
class BackupRestoreResultMessagesTest {

    // -------------------------------------------------------------------
    // Settings — buildSettingsResultMessage
    // -------------------------------------------------------------------

    @Test
    fun settings_minimal_appliedCountAndRestartFooter() {
        val result = BackupRestoreManager.ImportResult().apply {
            importedCount = 5
        }

        val msg = buildSettingsResultMessage(result)

        assertTrue("opening line", msg.contains("Import completed."))
        assertTrue("applied line", msg.contains("• Applied: 5"))
        assertTrue("footer", msg.contains("Restart the keyboard for changes to take effect."))
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

        val msg = buildSettingsResultMessage(result)

        assertTrue(msg.contains("• Excluded by you: 2"))
    }

    @Test
    fun settings_skippedCount_rendersInvalidSkippedLine() {
        val result = BackupRestoreManager.ImportResult().apply {
            importedCount = 1
            skippedCount = 4
        }

        val msg = buildSettingsResultMessage(result)

        assertTrue(msg.contains("• Invalid/skipped: 4"))
    }

    @Test
    fun settings_shortSwipeCustomizationsImported_rendersShortSwipeLine() {
        val result = BackupRestoreManager.ImportResult().apply {
            importedCount = 0
            shortSwipeCustomizationsImported = 7
        }

        val msg = buildSettingsResultMessage(result)

        assertTrue(msg.contains("• Short-swipe applied: 7"))
    }

    @Test
    fun settings_sourceVersionNotUnknown_rendersSourceVersionLine() {
        val result = BackupRestoreManager.ImportResult().apply {
            importedCount = 1
            sourceVersion = "1.4.0"
        }

        val msg = buildSettingsResultMessage(result)

        assertTrue(msg.contains("• Source version: 1.4.0"))
    }

    @Test
    fun settings_sourceVersionUnknown_omitsSourceVersionLine() {
        val result = BackupRestoreManager.ImportResult().apply {
            importedCount = 1
            sourceVersion = "unknown"
        }

        val msg = buildSettingsResultMessage(result)

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

        val msg = buildSettingsResultMessage(result)

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

        val msg = buildSettingsResultMessage(result)

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

        val msg = buildSettingsResultMessage(result)

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

        val msg = buildDictResultMessage(result)

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

        val msg = buildDictResultMessage(result)

        assertTrue(msg.contains("• Excluded by you: 2"))
    }

    @Test
    fun dict_sourceVersionNotUnknown_rendersSourceVersionLine() {
        val result = BackupRestoreManager.DictionaryImportResult().apply {
            userWordsImported = 5
            sourceVersion = "1.3.5"
        }

        val msg = buildDictResultMessage(result)

        assertTrue(msg.contains("• Source version: 1.3.5"))
    }

    @Test
    fun dict_sourceVersionUnknown_omitsSourceVersionLine() {
        val result = BackupRestoreManager.DictionaryImportResult().apply {
            userWordsImported = 5
            sourceVersion = "unknown"
        }

        val msg = buildDictResultMessage(result)

        assertFalse(msg.contains("Source version"))
    }
}
