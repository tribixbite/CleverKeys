package tribixbite.cleverkeys.minimize

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.customization.CommandRegistry

/**
 * gh #175: which view the input window shows while the keyboard is minimized.
 * Views are plain strings here; the state machine is generic over the view type.
 */
class KeyboardMinimizerTest {

    private val shown = mutableListOf<String>()
    private var created = 0
    private val minimizer = KeyboardMinimizer<String>(
        show = { shown += it },
        createMinimized = { created++; "mini" },
    )

    @Test
    fun minimizeShowsTheMinimizedView_andExpandRestoresTheLastFullView() {
        minimizer.resolve("full")
        minimizer.minimize(MinimizedStyle.FAB) {}
        assertThat(minimizer.style).isEqualTo(MinimizedStyle.FAB)
        assertThat(shown).containsExactly("mini")

        minimizer.expand()
        assertThat(minimizer.style).isNull()
        assertThat(shown).containsExactly("mini", "full").inOrder()
    }

    @Test
    fun aViewTheKeyboardShowsWhileMinimized_isRememberedButTheMinimizedViewStays() {
        minimizer.resolve("full")
        minimizer.minimize(MinimizedStyle.BAR) {}
        // e.g. onStartInputView re-shows the prediction container, or a theme change inflates
        assertThat(minimizer.resolve("full2")).isEqualTo("mini")
        minimizer.expand()
        assertThat(shown.last()).isEqualTo("full2")
    }

    @Test
    fun hidingTheKeyboardResets_soItsNextShowIsFullSize() {
        minimizer.resolve("full")
        minimizer.minimize(MinimizedStyle.FAB) {}
        minimizer.reset()
        assertThat(minimizer.resolve("full")).isEqualTo("full")
    }

    @Test
    fun theMinimizedViewIsCreatedOnce_andRestyledEachTime() {
        val styled = mutableListOf<MinimizedStyle>()
        minimizer.resolve("full")
        minimizer.minimize(MinimizedStyle.BAR) { styled += MinimizedStyle.BAR }
        minimizer.expand()
        minimizer.minimize(MinimizedStyle.FAB) { styled += MinimizedStyle.FAB }
        assertThat(created).isEqualTo(1)
        assertThat(styled).containsExactly(MinimizedStyle.BAR, MinimizedStyle.FAB).inOrder()
        // Showing the minimized view itself is not mistaken for a new full view.
        assertThat(minimizer.resolve("mini")).isEqualTo("mini")
        minimizer.expand()
        assertThat(shown.last()).isEqualTo("full")
    }

    @Test
    fun nothingToComeBackTo_minimizeIsIgnored() {
        minimizer.minimize(MinimizedStyle.FAB) {}
        assertThat(minimizer.style).isNull()
        assertThat(shown).isEmpty()
    }

    @Test
    fun expandWhenNotMinimizedIsANoOp() {
        minimizer.resolve("full")
        minimizer.expand()
        assertThat(shown).isEmpty()
    }

    /** Both commands are in the palette and resolve to their keyboard events. */
    @Test
    fun theCommandsAreCatalogued_andAreEventKeys() {
        assertThat(CommandRegistry.getByName("minimize_bar")).isNotNull()
        assertThat(CommandRegistry.getByName("minimize_fab")).isNotNull()
        assertThat(KeyValue.getKeyByName("minimize_bar").getEvent()).isEqualTo(KeyValue.Event.MINIMIZE_BAR)
        assertThat(KeyValue.getKeyByName("minimize_fab").getEvent()).isEqualTo(KeyValue.Event.MINIMIZE_FAB)
        // gh #175's explicit dismiss ("∨") sits beside the minimize commands.
        assertThat(CommandRegistry.getByName("hide_keyboard")).isNotNull()
        assertThat(KeyValue.getKeyByName("hide_keyboard").getEvent()).isEqualTo(KeyValue.Event.HIDE_KEYBOARD)
    }
}
