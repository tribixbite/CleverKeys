package tribixbite.cleverkeys

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * v6 config migration (2026-11-10): the key outline got its own opacity (`key_border_opacity`),
 * separate from the fill's `key_opacity` (maintainer: "key opacity 0 but key border opacity
 * 100%"). Until v6 `key_opacity` drew both, so an upgrade starts the border at its key opacity and
 * draws exactly as before; a fresh install gets [Defaults.KEY_BORDER_OPACITY].
 */
class KeyBorderOpacityMigrationTest {

    @Test
    fun anUpgradeStartsTheBorderAtItsKeyOpacity() {
        assertThat(KeyBorderOpacityMigration.seed(isFreshInstall = false, savedVersion = 5, hasExplicitChoice = false, keyOpacityPercent = 40))
            .isEqualTo(40)
    }

    @Test
    fun nothingIsSeededWhenThereIsNothingToKeep() {
        // Fresh install, a device already at v6, an explicit border setting (a restored backup),
        // or a key opacity never changed (the default border opacity is the same picture).
        assertThat(KeyBorderOpacityMigration.seed(true, 0, false, 40)).isNull()
        assertThat(KeyBorderOpacityMigration.seed(false, 6, false, 40)).isNull()
        assertThat(KeyBorderOpacityMigration.seed(false, 5, true, 40)).isNull()
        assertThat(KeyBorderOpacityMigration.seed(false, 5, false, null)).isNull()
    }

    @Test
    fun freshInstallsDrawTheOutlineFully() {
        assertThat(Defaults.KEY_BORDER_OPACITY).isEqualTo(100)
    }

    private fun prefsAt(version: Int?, keyOpacity: Int?, border: Int? = null): Pair<SharedPreferences, SharedPreferences.Editor> {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.contains("version") } returns (version != null)
        every { prefs.getInt("version", 0) } returns (version ?: 0)
        every { prefs.contains("key_opacity") } returns (keyOpacity != null)
        if (keyOpacity != null) every { prefs.getInt("key_opacity", any()) } returns keyOpacity
        every { prefs.contains(KeyBorderOpacityMigration.PREF_KEY) } returns (border != null)
        every { prefs.edit() } returns editor
        return prefs to editor
    }

    @Test
    fun migrateCopiesTheKeyOpacityOnAV5Device() {
        val (prefs, editor) = prefsAt(version = 5, keyOpacity = 35)
        Config.migrate(prefs)
        verify { editor.putInt(KeyBorderOpacityMigration.PREF_KEY, 35) }
        verify { editor.putInt("version", 6) }
    }

    @Test
    fun migrateLeavesARestoredBorderOpacityAlone() {
        val (prefs, editor) = prefsAt(version = 5, keyOpacity = 35, border = 90)
        Config.migrate(prefs)
        verify(exactly = 0) { editor.putInt(KeyBorderOpacityMigration.PREF_KEY, any()) }
    }

    @Test
    fun migrateOnAFreshInstallWritesNoBorderOpacity() {
        val (prefs, editor) = prefsAt(version = null, keyOpacity = null)
        Config.migrate(prefs)
        verify(exactly = 0) { editor.putInt(KeyBorderOpacityMigration.PREF_KEY, any()) }
    }
}
