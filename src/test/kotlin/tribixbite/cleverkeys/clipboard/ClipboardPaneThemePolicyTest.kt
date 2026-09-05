package tribixbite.cleverkeys.clipboard

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.ClipboardPaneThemePolicy
import tribixbite.cleverkeys.ClipboardPaneThemePolicy.RuntimeColors
import tribixbite.cleverkeys.ClipboardPaneThemePolicy.ThemeSignature

/**
 * gh #130: the pure color-resolution + pane-rebuild decisions the clipboard pane runs on.
 *
 * Companion to ClipboardPaneThemeInvalidationTest (which proves ClipboardManager.setConfig
 * actually consults these decisions) — here the decisions themselves are executed:
 *
 *  - effectiveColor: which color a programmatic read uses — runtime theme's value when
 *    active and defined, else the XML-resolved ?attr, else the fallback;
 *  - needsRebuild: when a cached pane painted under one theme signature must be dropped —
 *    including the name-unchanged case of EDITING the active custom theme's colors.
 */
class ClipboardPaneThemePolicyTest {

    private val purple = 0xFF6650A4.toInt() // the CleverKeysDark base — the report's "purple"
    private val userBg = 0xFF102030.toInt()
    private val userLabel = 0xFFEEDDCC.toInt()

    private fun colors(bg: Int = userBg, label: Int = userLabel) =
        RuntimeColors(keyboardBackground = bg, key = 0xFF223344.toInt(), label = label,
            subLabel = 0xFF99AABB.toInt(), activated = 0xFF33FF99.toInt())

    // ------------------------------------------------------------- effectiveColor

    @Test
    fun runtimeThemeColorBeatsTheBaseStyleAttr() {
        assertWithMessage(
            "#130's report: with a custom theme active, ?attr resolves to the base style " +
                "(purple) — the custom theme's own color must win"
        ).that(
            ClipboardPaneThemePolicy.effectiveColor(
                isRuntimeTheme = true, runtimeColor = userBg, xmlResolved = purple, fallback = 0
            )
        ).isEqualTo(userBg)
    }

    @Test
    fun undefinedRuntimeColorFallsBackToTheResolvedAttr() {
        // 0 = "the theme doesn't define this color" — the attr the pane inflated with stands.
        assertThat(
            ClipboardPaneThemePolicy.effectiveColor(
                isRuntimeTheme = true, runtimeColor = 0, xmlResolved = purple, fallback = 1
            )
        ).isEqualTo(purple)
    }

    @Test
    fun xmlStyleThemesIgnoreAnyStaleRuntimeColor() {
        // After switching custom -> built-in, a stale runtime color must never leak through.
        assertThat(
            ClipboardPaneThemePolicy.effectiveColor(
                isRuntimeTheme = false, runtimeColor = userBg, xmlResolved = purple, fallback = 1
            )
        ).isEqualTo(purple)
    }

    @Test
    fun unresolvableAttrLandsOnTheFallback() {
        assertThat(
            ClipboardPaneThemePolicy.effectiveColor(
                isRuntimeTheme = false, runtimeColor = 0, xmlResolved = null, fallback = purple
            )
        ).isEqualTo(purple)
    }

    // --------------------------------------------------------------- needsRebuild

    @Test
    fun unchangedSignatureKeepsTheCachedPane() {
        val sig = ThemeSignature("custom_abc", 0, colors())
        assertWithMessage("re-propagating an unchanged config must not cost a re-inflation")
            .that(ClipboardPaneThemePolicy.needsRebuild(sig.copy(), sig)).isFalse()
    }

    @Test
    fun switchingThemesRebuilds() {
        val custom = ThemeSignature("custom_abc", 0, colors())
        val dark = ThemeSignature("dark", 2131099999, null)
        assertThat(ClipboardPaneThemePolicy.needsRebuild(custom, dark)).isTrue()
        assertThat(ClipboardPaneThemePolicy.needsRebuild(dark, custom)).isTrue()
    }

    @Test
    fun editingTheActiveCustomThemesColorsRebuildsEvenThoughTheNameIsUnchanged() {
        val before = ThemeSignature("custom_abc", 0, colors(bg = userBg))
        val after = ThemeSignature("custom_abc", 0, colors(bg = 0xFF000000.toInt()))
        assertWithMessage(
            "editing the active custom theme keeps its name — only the color values " +
                "change, and that must still drop the cached pane"
        ).that(ClipboardPaneThemePolicy.needsRebuild(before, after)).isTrue()
    }

    @Test
    fun switchingBetweenTwoBuiltInStylesRebuilds() {
        val dark = ThemeSignature("dark", 100, null)
        val light = ThemeSignature("light", 101, null)
        assertThat(ClipboardPaneThemePolicy.needsRebuild(dark, light)).isTrue()
    }

    @Test
    fun unknownAppliedSignatureRebuildsConservatively() {
        // A pane exists but nothing recorded what painted it: wrongly keeping it shows
        // wrong colors; wrongly dropping it costs one re-inflation. Drop it.
        assertThat(
            ClipboardPaneThemePolicy.needsRebuild(null, ThemeSignature("dark", 100, null))
        ).isTrue()
    }
}
