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
 * "default on settings toggle for new installs, default off for existing users").
 *
 * The compile-time default is ON, so a fresh install gets the popover. An upgrading install
 * (it has a `version` marker below 5) is seeded an explicit OFF, so holding a letter keeps
 * repeating it as before, unless it already holds an explicit value (restored backup, or the
 * user toggled it on a build that shipped the setting before this migration ran).
 */
class SubkeyPopoverMigrationTest {

    @Test
    fun freshInstallDefaultIsOn() {
        assertTrue(Defaults.SUBKEY_POPOVER_ENABLED)
    }

    // ── Decision matrix (pure) ───────────────────────────────────────────────

    @Test
    fun upgradeWithoutAChoiceIsSeededOff() {
        assertTrue(SubkeyPopoverMigration.seedsOff(isFreshInstall = false, savedVersion = 4, hasExplicitChoice = false))
        assertTrue(SubkeyPopoverMigration.seedsOff(isFreshInstall = false, savedVersion = 3, hasExplicitChoice = false))
    }

    @Test
    fun anExplicitChoiceIsLeftAlone() {
        assertFalse(SubkeyPopoverMigration.seedsOff(isFreshInstall = false, savedVersion = 4, hasExplicitChoice = true))
    }

    @Test
    fun aFreshInstallKeepsTheDefault() {
        assertFalse(SubkeyPopoverMigration.seedsOff(isFreshInstall = true, savedVersion = 0, hasExplicitChoice = false))
    }

    @Test
    fun aDeviceAlreadyAtV5IsNotReseeded() {
        assertFalse(SubkeyPopoverMigration.seedsOff(isFreshInstall = false, savedVersion = 5, hasExplicitChoice = false))
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
    fun migrateSeedsAV4DeviceOff() {
        val (prefs, editor) = prefsAt(version = 4)
        Config.migrate(prefs)
        verify { editor.putBoolean(SubkeyPopoverMigration.PREF_KEY, false) }
        verify { editor.putInt("version", 5) }
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
}
