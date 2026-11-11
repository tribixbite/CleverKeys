package tribixbite.cleverkeys.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.customization.CommandRegistry

/**
 * The theme commands (maintainer 2026-11-11: "theme + and - … moves and applies the currently
 * selected theme"): stepping wraps at both ends, an unknown current theme starts from an end,
 * and both commands are in the catalogue as keyboard events.
 */
class ThemeOrderTest {

    private val order = listOf("look_ios", "look_aurora", "dark", "custom_1", "decorative_ruby")

    @Test
    fun stepsForwardAndBackAndWraps() {
        assertThat(ThemeOrder.step(order, "look_aurora", 1)).isEqualTo("dark")
        assertThat(ThemeOrder.step(order, "look_aurora", -1)).isEqualTo("look_ios")
        assertThat(ThemeOrder.step(order, "decorative_ruby", 1)).isEqualTo("look_ios")
        assertThat(ThemeOrder.step(order, "look_ios", -1)).isEqualTo("decorative_ruby")
        assertThat(ThemeOrder.step(order, "dark", 7)).isEqualTo("decorative_ruby")
    }

    @Test
    fun anUnknownThemeStartsFromAnEnd() {
        // A deleted custom theme or an old id: forward starts at the first, back at the last.
        assertThat(ThemeOrder.step(order, "custom_gone", 1)).isEqualTo("look_ios")
        assertThat(ThemeOrder.step(order, "custom_gone", -1)).isEqualTo("decorative_ruby")
        assertThat(ThemeOrder.step(emptyList(), "dark", 1)).isNull()
    }

    @Test
    fun bothCommandsAreKeyboardEvents() {
        for ((name, event) in listOf("theme_next" to KeyValue.Event.THEME_NEXT, "theme_previous" to KeyValue.Event.THEME_PREVIOUS)) {
            val command = CommandRegistry.getByName(name)
            assertThat(command).isNotNull()
            assertThat(command!!.category).isEqualTo(CommandRegistry.Category.EVENTS)
            assertThat(KeyValue.getKeyByName(name).getEvent()).isEqualTo(event)
        }
    }
}
