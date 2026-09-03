package tribixbite.cleverkeys

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.Test

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
 *   `safeGetInt(...).coerceIn(0, 45)` — the *read* side, so a stored value from an import, a
 *   backup, or an older build can never widen the margin past 45% of the screen.
 * - **The 90% total cap is enforced in the settings UI**, which derives each slider's upper
 *   bound as `90 - <the other side>`.
 *
 * `Config` needs `SharedPreferences`, `Resources` and `DisplayMetrics` to construct, so this
 * test allocates one without running its constructor (`Unsafe.allocateInstance`, the same
 * mechanism MockK's Objenesis uses) and drives the real private method with a mocked
 * preference store. That is the only way to exercise the clamp itself rather than a copy of it.
 *
 * ### Finding recorded here
 *
 * The two caps disagree about a single side: the slider's range is `0..(90 - other)`, so a user
 * with 0% on one side can select **90%** on the other — and `Config` then silently clamps it to
 * 45%. [storedValueAboveTheCap_isClampedNotHonoured] pins the effective behaviour (the note's
 * "0-45% each" is what the keyboard actually does); the slider headroom above 45 is inert.
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
        // the derivation is a plain expression and a regression to a fixed 0..45 range (or to
        // no cap at all) is exactly what this catches.
        val src = APPEARANCE_SECTION.readText()
        assertThat(src).contains("val maxLeftPortrait = (90 - marginRightPortrait).coerceAtLeast(0)")
        assertThat(src).contains("val maxRightPortrait = (90 - marginLeftPortrait).coerceAtLeast(0)")
        assertThat(src).contains("val maxLeftLandscape = (90 - marginRightLandscape).coerceAtLeast(0)")
        assertThat(src).contains("val maxRightLandscape = (90 - marginLeftLandscape).coerceAtLeast(0)")
        // Bottom margin keeps its own announced 0-30% range.
        assertThat(src).contains("valueRange = 0f..30f")
    }
}
