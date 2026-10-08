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

    /**
     * Hiding while minimized must put the full keyboard back in the input window. Clearing only
     * the style left the bar/button as the live view with style == null, so a tap on it
     * (expand) did nothing until the next onStartInputView (popover/palette audit, 2026-10-08).
     */
    @Test
    fun aResetWhileMinimized_showsTheFullKeyboardAgain() {
        minimizer.resolve("full")
        minimizer.minimize(MinimizedStyle.BAR) {}
        minimizer.reset()

        assertThat(minimizer.style).isNull()
        assertThat(shown.last()).isEqualTo("full")
    }

    @Test
    fun aResetWhenNotMinimized_showsNothing() {
        minimizer.resolve("full")
        minimizer.reset()
        assertThat(shown).isEmpty()
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

    // ---- FAB side (FabSidePolicy) and insets (MinimizedInsets), audit 2026-10-08 ----

    private val arabic = java.util.Locale.forLanguageTag("ar")
    private val english = java.util.Locale.ENGLISH
    private fun isRtlLocale(locale: java.util.Locale) = locale.language in setOf("ar", "fa", "he", "iw", "ur")

    @Test
    fun theButtonGoesLeftWhenAnyOfTheThreeSourcesIsRtl() {
        fun rtl(service: Boolean, system: java.util.Locale?, app: java.util.Locale?) =
            FabSidePolicy.isRtl(service, { system }, { app }, ::isRtlLocale)
        assertThat(rtl(service = true, system = english, app = english)).isTrue()
        // Saga 2026-10-07: system Arabic, but the app (no Arabic translation) resolved LTR.
        assertThat(rtl(service = false, system = arabic, app = null)).isTrue()
        // Per-app Persian locale while the system and service stay LTR.
        assertThat(rtl(service = false, system = english, app = java.util.Locale.forLanguageTag("fa"))).isTrue()
        assertThat(rtl(service = false, system = english, app = english)).isFalse()
        assertThat(rtl(service = false, system = null, app = null)).isFalse()
    }

    @Test
    fun theLocaleSourcesAreReadLazily_inOrder() {
        val read = mutableListOf<String>()
        val result = FabSidePolicy.isRtl(
            serviceRtl = false,
            systemLocale = { read += "system"; arabic },
            appLocale = { read += "app"; english },
            localeIsRtl = ::isRtlLocale,
        )
        assertThat(result).isTrue()
        assertThat(read).containsExactly("system")
        read.clear()
        FabSidePolicy.isRtl(true, { read += "system"; null }, { read += "app"; null }, ::isRtlLocale)
        assertThat(read).isEmpty()
    }

    @Test
    fun onlyAnAttachedFab_changesTheInsets() {
        var heightReads = 0
        val height = { heightReads++; 2400 }
        assertThat(MinimizedInsets.plan(MinimizedStyle.FAB, attached = true, height))
            .isEqualTo(MinimizedInsetsPlan(topInsets = 2400, touchRegionOnly = true))
        assertThat(MinimizedInsets.plan(MinimizedStyle.BAR, attached = true, height)).isNull()
        assertThat(MinimizedInsets.plan(null, attached = true, height)).isNull()
        assertThat(MinimizedInsets.plan(MinimizedStyle.FAB, attached = false, height)).isNull()
        assertThat(heightReads).isEqualTo(1)
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
