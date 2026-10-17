package tribixbite.cleverkeys

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.objenesis.ObjenesisStd

/**
 * Preference-driven UI updates reach the views ON SCREEN, not the ones that existed when the
 * handler was created. A theme change — now also a switch onto a layout bound to another theme
 * — replaces the keyboard view and suggestion bar; the handler used to keep the originals, so a
 * later opacity change went to a detached bar (2026-10-17).
 */
class PreferenceUIUpdateHandlerViewsTest {

    @Test
    fun opacityAndLayoutGoToTheCurrentViewsAfterTheyAreReplaced() {
        val config = ObjenesisStd().newInstance(Config::class.java)
        config.suggestion_bar_opacity = 42
        val layout = ObjenesisStd().newInstance(KeyboardData::class.java)
        val bridge = mockk<LayoutBridge> { every { getCurrentLayout() } returns layout }
        val firstBar = mockk<SuggestionBar>(relaxed = true)
        val firstView = mockk<Keyboard2View>(relaxed = true)
        var bar = firstBar
        var view = firstView
        val handler = PreferenceUIUpdateHandler.create(config, bridge, { view }, { bar })

        // The theme changes: both views are rebuilt.
        bar = mockk(relaxed = true)
        view = mockk(relaxed = true)
        handler.handlePreferenceChange("suggestion_bar_opacity")

        verify { bar.setOpacity(42) }
        verify { view.setKeyboard(layout) }
        verify(exactly = 0) { firstBar.setOpacity(any()) }
        verify(exactly = 0) { firstView.setKeyboard(any()) }
    }
}
