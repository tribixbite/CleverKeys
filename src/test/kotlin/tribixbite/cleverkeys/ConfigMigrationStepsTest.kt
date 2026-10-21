package tribixbite.cleverkeys

import android.content.SharedPreferences
import android.util.DisplayMetrics
import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Pins the two startup migrations the 2026-10-20 audit flagged as copy-paste, so their rewrites
 * are provably behaviour-preserving:
 *
 *  - **L4** `Config.migrate` versions 0/1/2: the `number_row` and `number_entry_layout` steps were
 *    pasted into each `when` branch to emulate Java's switch fallthrough; now each step runs once
 *    for every version below the one that introduced it.
 *  - **L5** `migrateMarginPrefs` (dp → %): three near-identical loops are now one table. The
 *    bottom margin clamps to [SettingsRanges.MARGIN_BOTTOM_PERCENT] (0..30, the slider's range and
 *    the read clamp; the original loop clamped 0..80, aligned in `ce79c881`), sides to
 *    [SettingsRanges.MARGIN_SIDE_PERCENT].
 */
class ConfigMigrationStepsTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() = unmockkStatic(Log::class)

    // --------------------------------------------------------------------- L4 migrate

    private fun migrated(initial: Map<String, Any>): Map<String, Any> =
        TypedFakePreferences(initial).also { Config.migrate(it) }.values

    @Test
    fun version0_migratesLayoutsNumberRowAndNumberEntry() {
        val after = migrated(mapOf("version" to 0, "number_row" to true, "pin_entry_enabled" to false))
        assertThat(after["number_row"]).isEqualTo("no_symbols")
        assertThat(after["number_entry_layout"]).isEqualTo("number")
        assertThat(after).containsKey("layouts")
    }

    @Test
    fun version1_migratesNumberRowAndNumberEntryButNotLayouts() {
        val after = migrated(mapOf("version" to 1, "number_row" to false))
        assertThat(after["number_row"]).isEqualTo("no_number_row")
        // pin_entry_enabled absent: its default (true) chooses the PIN layout.
        assertThat(after["number_entry_layout"]).isEqualTo("pin")
        assertThat(after).doesNotContainKey("layouts")
    }

    @Test
    fun version2_migratesOnlyNumberEntry() {
        val after = migrated(mapOf("version" to 2, "number_row" to "no_symbols", "pin_entry_enabled" to false))
        assertThat(after["number_row"]).isEqualTo("no_symbols")
        assertThat(after["number_entry_layout"]).isEqualTo("number")
        assertThat(after).doesNotContainKey("layouts")
    }

    @Test
    fun anExplicitNumberEntryLayoutIsNeverOverwritten() {
        for (version in 0..2) {
            val after = migrated(mapOf("version" to version, "number_entry_layout" to "normal", "pin_entry_enabled" to false))
            assertThat(after["number_entry_layout"]).isEqualTo("normal")
        }
    }

    @Test
    fun version3AndLaterRunNoneOfTheLegacySteps() {
        for (version in 3..4) {
            val after = migrated(mapOf("version" to version, "number_row" to true, "pin_entry_enabled" to false))
            assertThat(after["number_row"]).isEqualTo(true)
            assertThat(after).doesNotContainKey("number_entry_layout")
            assertThat(after).doesNotContainKey("layouts")
        }
    }

    // ------------------------------------------------------------- L5 margin migration

    /** 1000×2000 px screen at density 2: 1 dp = 2 px = 0.2 % of width = 0.1 % of height. */
    private fun metrics(): DisplayMetrics = mockk<DisplayMetrics>().apply {
        density = 2f
        widthPixels = 1000
        heightPixels = 2000
    }

    private fun migrateMargins(initial: Map<String, Any>): TypedFakePreferences {
        val prefs = TypedFakePreferences(initial)
        val method = Config.Companion::class.java.getDeclaredMethod(
            "migrateMarginPrefs", SharedPreferences::class.java, DisplayMetrics::class.java
        )
        method.isAccessible = true
        method.invoke(Config.Companion, prefs, metrics())
        return prefs
    }

    @Test
    fun horizontalMarginSplitsIntoLeftAndRightPercentOfWidth() {
        val after = migrateMargins(mapOf("horizontal_margin_landscape_unfolded" to 50)).values
        // 50 dp = 100 px = 10 % of 1000 px.
        assertThat(after["margin_left_landscape_unfolded"]).isEqualTo(10)
        assertThat(after["margin_right_landscape_unfolded"]).isEqualTo(10)
        assertThat(after).doesNotContainKey("horizontal_margin_landscape_unfolded")
        assertThat(after["margin_prefs_version"]).isEqualTo(1)
    }

    @Test
    fun bottomMarginBecomesPercentOfHeightClampedToTheSliderRange() {
        val after = migrateMargins(
            mapOf("margin_bottom_portrait" to 140, "margin_bottom_landscape_unfolded" to 5_000)
        ).values
        // 140 dp = 280 px = 14 % of 2000 px; 5000 dp would be 500 % → clamped to the 30 % ceiling.
        assertThat(after["margin_bottom_portrait"]).isEqualTo(14)
        assertThat(after["margin_bottom_landscape_unfolded"]).isEqualTo(SettingsRanges.MARGIN_BOTTOM_PERCENT.last)
        assertThat(after).doesNotContainKey("margin_bottom_landscape")
    }

    @Test
    fun sideMarginsBecomePercentOfWidthClampedToTheSideRange() {
        val after = migrateMargins(mapOf("margin_left_portrait" to 25, "margin_right_landscape" to 1_000)).values
        // 25 dp = 50 px = 5 %; 1000 dp = 200 % → 45 %.
        assertThat(after["margin_left_portrait"]).isEqualTo(5)
        assertThat(after["margin_right_landscape"]).isEqualTo(SettingsRanges.MARGIN_SIDE_PERCENT.last)
    }

    @Test
    fun anExistingSideKeyWinsOverTheHorizontalKeyItWouldBeSplitFrom() {
        // Both present: the side loop runs after the horizontal split and converts the side key's
        // own dp value, so the side key's value is the one kept (unchanged since the port).
        val after = migrateMargins(mapOf("horizontal_margin_portrait" to 50, "margin_left_portrait" to 25)).values
        assertThat(after["margin_left_portrait"]).isEqualTo(5)
        assertThat(after["margin_right_portrait"]).isEqualTo(10)
    }

    @Test
    fun runsOnceAndOnlyTouchesKeysThatExist() {
        val first = migrateMargins(emptyMap())
        assertThat(first.values).containsExactly("margin_prefs_version", 1)
        val again = migrateMargins(mapOf("margin_prefs_version" to 1, "margin_left_portrait" to 25))
        assertThat(again.values["margin_left_portrait"]).isEqualTo(25)
        assertThat(again.commits).isEqualTo(0)
    }
}
