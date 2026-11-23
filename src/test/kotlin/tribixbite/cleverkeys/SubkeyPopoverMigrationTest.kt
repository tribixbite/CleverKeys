package tribixbite.cleverkeys

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v5 config migration for the hold-then-select subkey popover (owner request 2026-09-30:
 * "default on settings toggle for new installs, default off for existing users"; revised
 * 2026-11-23: "upgrading users should get the popup keys by default unless we had a good reason
 * for not").
 *
 * The compile-time default is ON. An upgrading install (a `version` marker below 5) is seeded
 * an explicit OFF only when its letter keys repeat (key repeat on, "Backspace only repeat" off),
 * the one thing the popover would take from it, and unless it already holds an explicit value
 * (restored backup, or the user toggled it on a build that shipped the setting before this
 * migration ran). On 1.5's defaults a held letter did nothing, so those upgrades get the popover.
 */
class SubkeyPopoverMigrationTest {

    @Test
    fun freshInstallDefaultIsOn() {
        assertTrue(Defaults.SUBKEY_POPOVER_ENABLED)
    }

    // ── Decision matrix (pure) ───────────────────────────────────────────────

    @Test
    fun anUpgradeWhoseLetterKeysRepeatIsSeededOff() {
        assertTrue(SubkeyPopoverMigration.seedsOff(isFreshInstall = false, savedVersion = 4, hasExplicitChoice = false, repeatsLetters = true))
        assertTrue(SubkeyPopoverMigration.seedsOff(isFreshInstall = false, savedVersion = 3, hasExplicitChoice = false, repeatsLetters = true))
    }

    @Test
    fun anUpgradeWhoseLettersDoNotRepeatKeepsThePopover() {
        assertFalse(SubkeyPopoverMigration.seedsOff(isFreshInstall = false, savedVersion = 3, hasExplicitChoice = false, repeatsLetters = false))
    }

    @Test
    fun lettersRepeatOnlyWithKeyRepeatOnAndBackspaceOnlyOff() {
        assertTrue(SubkeyPopoverMigration.repeatsLetters(keyRepeatEnabled = true, backspaceOnlyRepeat = false))
        assertFalse(SubkeyPopoverMigration.repeatsLetters(keyRepeatEnabled = true, backspaceOnlyRepeat = true))  // 1.5's defaults
        assertFalse(SubkeyPopoverMigration.repeatsLetters(keyRepeatEnabled = false, backspaceOnlyRepeat = false))
    }

    @Test
    fun anExplicitChoiceIsLeftAlone() {
        assertFalse(SubkeyPopoverMigration.seedsOff(isFreshInstall = false, savedVersion = 4, hasExplicitChoice = true, repeatsLetters = true))
    }

    @Test
    fun aFreshInstallKeepsTheDefault() {
        assertFalse(SubkeyPopoverMigration.seedsOff(isFreshInstall = true, savedVersion = 0, hasExplicitChoice = false, repeatsLetters = true))
    }

    @Test
    fun aDeviceAlreadyAtV5IsNotReseeded() {
        assertFalse(SubkeyPopoverMigration.seedsOff(isFreshInstall = false, savedVersion = 5, hasExplicitChoice = false, repeatsLetters = true))
    }

    // ── Config.migrate wiring ────────────────────────────────────────────────

    private fun prefsAt(version: Int?, explicit: Boolean? = null): Pair<SharedPreferences, SharedPreferences.Editor> {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.contains("version") } returns (version != null)
        every { prefs.getInt("version", 0) } returns (version ?: 0)
        every { prefs.contains(SubkeyPopoverMigration.PREF_KEY) } returns (explicit != null)
        if (explicit != null) every { prefs.getBoolean(SubkeyPopoverMigration.PREF_KEY, any()) } returns explicit
        every { prefs.edit() } returns editor
        return prefs to editor
    }

    @Test
    fun migrateSeedsAV4DeviceWhoseLettersRepeatOff() {
        val (prefs, editor) = prefsAt(version = 4)
        every { prefs.getBoolean(any(), any()) } answers { if (firstArg<String>() == "keyrepeat_backspace_only") false else secondArg() }
        Config.migrate(prefs)
        verify { editor.putBoolean(SubkeyPopoverMigration.PREF_KEY, false) }
        verify { editor.putInt("version", 6) }  // v6 since 2026-11-10 (KeyBorderOpacityMigration)
    }

    @Test
    fun migrateLeavesAnExplicitOnAlone() {
        val (prefs, editor) = prefsAt(version = 4, explicit = true)
        Config.migrate(prefs)
        verify(exactly = 0) { editor.putBoolean(SubkeyPopoverMigration.PREF_KEY, any()) }
    }

    @Test
    fun migrateOnAFreshInstallWritesNothingForThePopover() {
        val (prefs, editor) = prefsAt(version = null)
        Config.migrate(prefs)
        verify(exactly = 0) { editor.putBoolean(SubkeyPopoverMigration.PREF_KEY, any()) }
    }

    // ── Revised 2026-11-23: only an upgrade whose letter keys repeat keeps the old hold ──────
    // Maintainer: "upgrading users should get the popup keys by default unless we had a good
    // reason for not". The reason was the letter-key repeat a hold gave; 1.5 shipped "Backspace
    // only repeat" on, so on the defaults a held letter did nothing there.

    /** [prefsAt] whose boolean reads answer the read's own default unless [booleans] sets the key. */
    private fun upgradeFromV15(booleans: Map<String, Boolean> = emptyMap()): Pair<SharedPreferences, SharedPreferences.Editor> {
        val (prefs, editor) = prefsAt(version = 3)
        every { prefs.getBoolean(any(), any()) } answers { booleans[firstArg()] ?: secondArg() }
        return prefs to editor
    }

    @Test
    fun migrateLeavesAV15DeviceOnTheDefaultsWithThePopover() {
        val (prefs, editor) = upgradeFromV15()
        Config.migrate(prefs)
        verify(exactly = 0) { editor.putBoolean(SubkeyPopoverMigration.PREF_KEY, any()) }
    }

    @Test
    fun migrateSeedsOffADeviceWhoseLetterKeysRepeat() {
        val (prefs, editor) = upgradeFromV15(mapOf("keyrepeat_backspace_only" to false))
        Config.migrate(prefs)
        verify { editor.putBoolean(SubkeyPopoverMigration.PREF_KEY, false) }
    }

    @Test
    fun migrateLeavesTheDefaultWithKeyRepeatOff() {
        val (prefs, editor) = upgradeFromV15(mapOf("keyrepeat_enabled" to false, "keyrepeat_backspace_only" to false))
        Config.migrate(prefs)
        verify(exactly = 0) { editor.putBoolean(SubkeyPopoverMigration.PREF_KEY, any()) }
    }
}
