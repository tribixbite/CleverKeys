package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Test
import org.objenesis.ObjenesisStd

/**
 * The display size keyboard sizing is measured against (todo items 25 and 27, 2026-10-27).
 *
 * On Android 15+ an IME targeting SDK 34 reads the full display height from its Resources when it
 * is created and a smaller one (no navigation bar, on some devices no status bar) after its first
 * configuration change; emulator.wtf API 36 measured 2400 → 2337 px across one night-mode switch
 * and the keyboard's rows shrank with it (`NightModeKeyboardHeightInstrumentedTest`). Config now
 * takes the size from [Config.screenSize] (the window manager's maximum bounds) and falls back to
 * the display metrics only without one.
 */
class KeyboardSizingScreenTest {

    @After
    fun teardown() {
        Config.screenSize = null
    }

    private fun config(): Config = ObjenesisStd().newInstance(Config::class.java).apply { keyboardHeightPercent = 30 }

    @Test
    fun aRefreshAfterTheDisplayMetricsShrinkKeepsTheSameScreenHeight() {
        Config.screenSize = { ScreenSize(1080, 2400) }
        val config = config()
        config.applyScreenSize(1080, 2400)   // the service's metrics when it was created
        val before = config.screenHeightPixels
        config.applyScreenSize(1080, 2337)   // the same metrics after a night-mode switch
        assertWithMessage("keyboard sizing must not follow the displayMetrics height change")
            .that(config.screenHeightPixels).isEqualTo(before)
        assertThat(config.screenHeightPixels).isEqualTo(2400)
        assertThat(config.screenWidthPixels).isEqualTo(1080)
    }

    @Test
    fun theHookFollowsARotation() {
        var screen = ScreenSize(1080, 2400)
        Config.screenSize = { screen }
        val config = config()
        config.applyScreenSize(1080, 2337)
        screen = ScreenSize(2400, 1080)
        config.applyScreenSize(2337, 1080)
        assertThat(config.screenHeightPixels).isEqualTo(1080)
        assertThat(config.screenWidthPixels).isEqualTo(2400)
    }

    @Test
    fun withoutAHookOrAnAnswerTheDisplayMetricsAreUsed() {
        val config = config()
        config.applyScreenSize(1080, 2337)
        assertThat(config.screenHeightPixels).isEqualTo(2337)
        Config.screenSize = { null }
        config.applyScreenSize(1000, 2000)
        assertThat(config.screenHeightPixels).isEqualTo(2000)
        assertThat(config.screenWidthPixels).isEqualTo(1000)
    }
}
