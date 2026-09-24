package tribixbite.cleverkeys

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.0.0 learning-consent migration (maintainer decision 2026-09-24, "do both"):
 *
 *  1. `Defaults.ON_DEVICE_LEARNING_ENABLED` flips to **false** — fresh installs learn
 *     nothing until the user turns the master switch on. UPGRADING installs are seeded
 *     an explicit `true` (when they never made an explicit choice), because their
 *     pre-2.0 behavior was learning-on via the old defaults and an upgrade must not
 *     silently change what the keyboard does.
 *  2. Selection history is the one store whose pre-2.0 writes ignored the learning
 *     gate (recorded even with learning off, v1.0–v1.5 — fixed in the 2.0 tree), so
 *     consented and bug-recorded entries are indistinguishable inside it. The v4
 *     migration stamps `selection_history_reset_pending` for UPGRADES ONLY;
 *     [UserAdaptationManager] consumes the flag on its next construction and wipes
 *     that store once. Fresh installs have an empty store and never get the flag.
 *
 * Fresh-vs-upgrade discriminator: `prefs.contains("version")` at [Config.migrate]
 * entry — every install that ever ran wrote the config version marker; a fresh
 * install has not. CONFIG_VERSION bumps 3 → 4 so v1.5.0 devices (version == 3)
 * re-enter the migration at all.
 */
class LearningMigrationTest {

    // ── The flipped compile-time default ─────────────────────────────────────

    @Test
    fun freshInstallDefaultIsLearningOff() {
        assertFalse(
            "Defaults.ON_DEVICE_LEARNING_ENABLED must be false: since v2.0 learning is " +
                "opt-in on fresh installs (upgrades are seeded true by the v4 migration " +
                "to preserve their prior behavior — see LearningMigration)",
            Defaults.ON_DEVICE_LEARNING_ENABLED
        )
    }

    // ── Decision matrix (pure) ───────────────────────────────────────────────

    @Test
    fun upgradeWithoutExplicitChoiceIsSeededOn() {
        assertTrue(
            LearningMigration.seedsMasterGateOn(
                isFreshInstall = false, savedVersion = 3, hasExplicitChoice = false
            )
        )
    }

    @Test
    fun upgradeWithAnExplicitChoiceIsLeftAlone() {
        assertFalse(
            "a stored on_device_learning_enabled value is the user's explicit choice — " +
                "the migration must never overwrite it",
            LearningMigration.seedsMasterGateOn(
                isFreshInstall = false, savedVersion = 3, hasExplicitChoice = true
            )
        )
    }

    @Test
    fun freshInstallIsNeverSeeded() {
        assertFalse(
            LearningMigration.seedsMasterGateOn(
                isFreshInstall = true, savedVersion = 0, hasExplicitChoice = false
            )
        )
    }

    @Test
    fun selectionHistoryResetIsRequestedForUpgradesOnly() {
        assertTrue(
            LearningMigration.requestsSelectionHistoryReset(
                isFreshInstall = false, savedVersion = 3
            )
        )
        assertFalse(
            "a fresh install has an empty store — nothing to reset, no flag to stamp",
            LearningMigration.requestsSelectionHistoryReset(
                isFreshInstall = true, savedVersion = 0
            )
        )
    }

    @Test
    fun aDeviceAlreadyOnV4NeverReRunsEitherHalf() {
        assertFalse(
            LearningMigration.seedsMasterGateOn(
                isFreshInstall = false, savedVersion = 4, hasExplicitChoice = false
            )
        )
        assertFalse(
            LearningMigration.requestsSelectionHistoryReset(
                isFreshInstall = false, savedVersion = 4
            )
        )
    }

    // ── Config.migrate wiring (a v1.5.0 device: version == 3, prefs exist) ──

    private fun prefsAt(version: Int?, keys: Map<String, Any> = emptyMap()): Pair<SharedPreferences, SharedPreferences.Editor> {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.contains("version") } returns (version != null)
        every { prefs.getInt("version", 0) } returns (version ?: 0)
        for ((k, v) in keys) {
            every { prefs.contains(k) } returns true
            if (v is Boolean) every { prefs.getBoolean(k, any()) } returns v
        }
        every { prefs.edit() } returns editor
        return prefs to editor
    }

    @Test
    fun migrateSeedsAnUpgradedDeviceOnAndStampsThePendingReset() {
        val (prefs, editor) = prefsAt(version = 3)
        Config.migrate(prefs)
        verify { editor.putBoolean("on_device_learning_enabled", true) }
        verify { editor.putBoolean(LearningMigration.SELECTION_HISTORY_RESET_PENDING_KEY, true) }
        verify { editor.putInt("version", 4) }
    }

    @Test
    fun migrateLeavesAnUpgradedDevicesExplicitChoiceUntouched() {
        val (prefs, editor) = prefsAt(
            version = 3, keys = mapOf("on_device_learning_enabled" to false)
        )
        Config.migrate(prefs)
        verify(exactly = 0) { editor.putBoolean("on_device_learning_enabled", any()) }
        verify { editor.putBoolean(LearningMigration.SELECTION_HISTORY_RESET_PENDING_KEY, true) }
    }

    @Test
    fun migrateOnAFreshInstallNeitherSeedsNorStampsAnything() {
        val (prefs, editor) = prefsAt(version = null)
        Config.migrate(prefs)
        verify(exactly = 0) { editor.putBoolean("on_device_learning_enabled", any()) }
        verify(exactly = 0) {
            editor.putBoolean(LearningMigration.SELECTION_HISTORY_RESET_PENDING_KEY, any())
        }
        verify { editor.putInt("version", 4) }
    }

    // ── The consuming half (UserAdaptationManager's seam) ────────────────────

    @Test
    fun aPendingFlagResetsOnceAndClearsItself() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val mainPrefs = mockk<SharedPreferences>(relaxed = true)
        every {
            mainPrefs.getBoolean(LearningMigration.SELECTION_HISTORY_RESET_PENDING_KEY, false)
        } returns true
        every { mainPrefs.edit() } returns editor

        var resets = 0
        val consumed = UserAdaptationManager.consumePendingReset(mainPrefs) { resets++ }

        assertTrue(consumed)
        assertEquals("the reset must run exactly once", 1, resets)
        verify { editor.remove(LearningMigration.SELECTION_HISTORY_RESET_PENDING_KEY) }
    }

    @Test
    fun noPendingFlagMeansNoReset() {
        val mainPrefs = mockk<SharedPreferences>(relaxed = true)
        every {
            mainPrefs.getBoolean(LearningMigration.SELECTION_HISTORY_RESET_PENDING_KEY, false)
        } returns false

        var resets = 0
        val consumed = UserAdaptationManager.consumePendingReset(mainPrefs) { resets++ }

        assertFalse(consumed)
        assertEquals(0, resets)
    }
}
