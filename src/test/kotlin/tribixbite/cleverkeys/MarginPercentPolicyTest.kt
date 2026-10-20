package tribixbite.cleverkeys

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.Test
import tribixbite.cleverkeys.ui.settings.sections.sideMarginCeiling

/**
 * Pins the v1.1.74 "Percentage-Based Margins" release note:
 *
 * ```
 * - Left/right margins: % of screen width (0-45% each)
 * - 90% total horizontal cap prevents unusable keyboard
 * ```
 *
 * ## Where each half of that promise lives
 *
 * - **The 0–45% per-side cap is enforced in [Config]**, in `get_percent_pref_oriented_width`:
 *   `safeGetInt(...).coerceIn(SettingsRanges.MARGIN_SIDE_PERCENT)` — the *read* side, so a
 *   stored value from an import, a backup, or an older build can never widen the margin past
 *   45% of the screen.
 * - **The 90% total cap is enforced in the settings UI**, which derives each slider's upper
 *   bound from the other side (`sideMarginCeiling`).
 *
 * `Config` needs `SharedPreferences`, `Resources` and `DisplayMetrics` to construct, so this
 * test allocates one without running its constructor (`Unsafe.allocateInstance`, the same
 * mechanism MockK's Objenesis uses) and drives the real private method with a mocked
 * preference store. That is the only way to exercise the clamp itself rather than a copy of it.
 *
 * ### Finding recorded here (fixed 2026-10-20, audit M8)
 *
 * The two caps used to disagree about a single side: the slider's range was `0..(90 - other)`,
 * so a user with 0% on one side could select **90%** on the other — `Config` then silently
 * clamped it to 45%, and a backup of that value failed import validation (0..45). The slider
 * ceiling is now `min(45, 90 - other)`. [storedValueAboveTheCap_isClampedNotHonoured] still pins
 * the read clamp for values stored by older builds.
 */
class MarginPercentPolicyTest {

    private companion object {
        const val SCREEN_WIDTH = 1000
        val APPEARANCE_SECTION = File(
            "src/main/kotlin/tribixbite/cleverkeys/ui/settings/sections/AppearanceSection.kt"
        )
    }

    /**
     * A [Config] with no constructor run: only the fields
     * `get_percent_pref_oriented_width` reads are populated.
     */
    private fun configWith(prefs: SharedPreferences, landscape: Boolean = false): Config {
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        @Suppress("UNCHECKED_CAST")
        val config = unsafe.javaClass
            .getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, Config::class.java) as Config

        Config::class.java.getDeclaredField("_prefs").apply { isAccessible = true }
            .set(config, prefs)
        config.screenWidthPixels = SCREEN_WIDTH
        config.orientation_landscape = landscape
        config.foldable_unfolded = false
        return config
    }

    /** Invokes the real private `get_percent_pref_oriented_width`. */
    private fun marginPixels(config: Config, base: String, defPortrait: Int, defLandscape: Int): Float {
        val m = Config::class.java.getDeclaredMethod(
            "get_percent_pref_oriented_width",
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        m.isAccessible = true
        return m.invoke(config, base, defPortrait, defLandscape) as Float
    }

    private fun prefsReturning(key: String, value: Int): SharedPreferences {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getInt(any(), any()) } answers { secondArg<Int>() }
        every { prefs.getInt(key, any()) } returns value
        return prefs
    }

    @Test
    fun storedPercentage_isConvertedToPixelsOfScreenWidth() {
        val config = configWith(prefsReturning("margin_left_portrait", 10))
        // 10% of a 1000px-wide screen.
        assertThat(marginPixels(config, "margin_left", 1, 5)).isEqualTo(100f)
    }

    @Test
    fun storedValueAboveTheCap_isClampedNotHonoured() {
        // The settings slider can offer up to 90 for one side; the read side refuses anything
        // over 45% of the screen, which is what keeps the keyboard usable.
        val config = configWith(prefsReturning("margin_left_portrait", 90))
        assertThat(marginPixels(config, "margin_left", 1, 5)).isEqualTo(450f)

        val justOver = configWith(prefsReturning("margin_left_portrait", 46))
        assertThat(marginPixels(justOver, "margin_left", 1, 5)).isEqualTo(450f)

        val atCap = configWith(prefsReturning("margin_left_portrait", 45))
        assertThat(marginPixels(atCap, "margin_left", 1, 5)).isEqualTo(450f)
    }

    @Test
    fun negativeStoredValue_isClampedToZero() {
        // A corrupted/imported negative would otherwise push the keyboard off-screen.
        val config = configWith(prefsReturning("margin_left_portrait", -20))
        assertThat(marginPixels(config, "margin_left", 1, 5)).isEqualTo(0f)
    }

    @Test
    fun bothSidesAtTheirCap_consumeExactlyTheAnnouncedNinetyPercent() {
        val left = configWith(prefsReturning("margin_left_portrait", 45))
        val right = configWith(prefsReturning("margin_right_portrait", 45))
        val total = marginPixels(left, "margin_left", 1, 5) +
            marginPixels(right, "margin_right", 1, 5)

        // 45 + 45 == the announced 90% total cap, so 10% of the screen always remains for keys.
        assertThat(total).isEqualTo(0.90f * SCREEN_WIDTH)
        assertThat(SCREEN_WIDTH - total).isEqualTo(100f)
    }

    @Test
    fun orientationSelectsTheMatchingPreferenceAndDefault() {
        // Portrait and landscape are separate stored margins with separate defaults; picking
        // the wrong suffix silently applies the other orientation's margin.
        val portraitOnly = configWith(prefsReturning("margin_left_portrait", 20), landscape = false)
        assertThat(marginPixels(portraitOnly, "margin_left", 1, 5)).isEqualTo(200f)

        val landscapeOnly = configWith(prefsReturning("margin_left_landscape", 30), landscape = true)
        assertThat(marginPixels(landscapeOnly, "margin_left", 1, 5)).isEqualTo(300f)

        // With nothing stored, the orientation-specific default applies (1% / 5%).
        val defaults = configWith(mockk<SharedPreferences>(relaxed = true).also {
            every { it.getInt(any(), any()) } answers { secondArg<Int>() }
        })
        assertThat(marginPixels(defaults, "margin_left", 1, 5)).isEqualTo(10f)

        val defaultsLandscape = configWith(mockk<SharedPreferences>(relaxed = true).also {
            every { it.getInt(any(), any()) } answers { secondArg<Int>() }
        }, landscape = true)
        assertThat(marginPixels(defaultsLandscape, "margin_left", 1, 5)).isEqualTo(50f)
    }

    @Test
    fun announcedDefaults_areOnePercentPortraitAndFivePercentLandscape() {
        assertThat(Defaults.MARGIN_LEFT_PORTRAIT).isEqualTo(1)
        assertThat(Defaults.MARGIN_RIGHT_PORTRAIT).isEqualTo(1)
        assertThat(Defaults.MARGIN_LEFT_LANDSCAPE).isEqualTo(5)
        assertThat(Defaults.MARGIN_RIGHT_LANDSCAPE).isEqualTo(5)
        // Bottom margin is a % of screen HEIGHT and is announced as 0-30%.
        assertThat(Defaults.MARGIN_BOTTOM_PORTRAIT).isEqualTo(0)
        assertThat(Defaults.MARGIN_BOTTOM_LANDSCAPE).isEqualTo(0)
    }

    @Test
    fun settingsSliders_deriveEachSidesCeilingFromTheOtherSide() {
        // The "90% total cap" half of the note. Compose composables cannot be driven here, but
        // each slider's ceiling comes from one plain function, wired per side below.
        val src = APPEARANCE_SECTION.readText()
        assertThat(src).contains("val maxLeftPortrait = sideMarginCeiling(marginRightPortrait)")
        assertThat(src).contains("val maxRightPortrait = sideMarginCeiling(marginLeftPortrait)")
        assertThat(src).contains("val maxLeftLandscape = sideMarginCeiling(marginRightLandscape)")
        assertThat(src).contains("val maxRightLandscape = sideMarginCeiling(marginLeftLandscape)")
        // Bottom margin keeps its own announced 0-30% range, shared with Config and validation.
        assertThat(src).contains("valueRange = SettingsRanges.MARGIN_BOTTOM_PERCENT.toSliderRange()")
        assertThat(SettingsRanges.MARGIN_BOTTOM_PERCENT).isEqualTo(0..30)

        // The ceiling never exceeds the per-side cap Config enforces (the old `90 - other`
        // offered 90 here) and is lowered so the two sides never sum past 90.
        assertThat(sideMarginCeiling(0)).isEqualTo(45)
        assertThat(sideMarginCeiling(45)).isEqualTo(45)
        // Only a value stored by an older build can exceed 45 on the other side.
        assertThat(sideMarginCeiling(60)).isEqualTo(30)
        assertThat(sideMarginCeiling(95)).isEqualTo(0)
        for (other in 0..45) {
            assertThat(sideMarginCeiling(other) + other).isAtMost(SettingsRanges.MARGIN_SIDES_TOTAL_PERCENT)
            assertThat(sideMarginCeiling(other)).isAtMost(SettingsRanges.MARGIN_SIDE_PERCENT.last)
        }
    }
}
