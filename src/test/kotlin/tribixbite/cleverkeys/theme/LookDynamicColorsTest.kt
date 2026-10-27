package tribixbite.cleverkeys.theme

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.theme.LookDynamicColors.MaterialRoles

/**
 * Gboard-style follows the system's wallpaper colours the way Gboard does (2026-10-28,
 * maintainer request; research §2a role mapping). Fixtures: Material Color Utilities
 * SchemeTonalSpot roles for nine seeds — the Pixel 8 Pro's wallpaper seed #5D646B, Android's
 * fallback #1B6EF3, saturated red/green/yellow/purple/teal and the black and white extremes —
 * in light and dark. On the Pixel (Android 16, same seed) the real Gboard drew light background
 * #EAEEF4, white letters and #D3E5F5 function keys: the light rows of the #5D646B fixture.
 */
class LookDynamicColorsTest {

    private val gboard = KeyboardLooks.gboardLook

    private val fixtures: List<Triple<String, Boolean, MaterialRoles>> = listOf(
        Triple("5D646B", true, MaterialRoles(0xFFEBEEF3.toInt(), 0xFFFFFFFF.toInt(), 0xFFE0E3E8.toInt(), 0xFFF7F9FF.toInt(), 0xFFD3E5F5.toInt(), 0xFF384956.toInt(), 0xFF27638A.toInt(), 0xFFFFFFFF.toInt(), 0xFF181C20.toInt(), 0xFF41474D.toInt(), 0xFFC1C7CE.toInt())),
        Triple("5D646B", false, MaterialRoles(0xFF1C2024.toInt(), 0xFF0B0F12.toInt(), 0xFF313539.toInt(), 0xFF363A3E.toInt(), 0xFF384956.toInt(), 0xFFD3E5F5.toInt(), 0xFF96CDF8.toInt(), 0xFF00344F.toInt(), 0xFFE0E3E8.toInt(), 0xFFC1C7CE.toInt(), 0xFF41474D.toInt())),
        Triple("1B6EF3", true, MaterialRoles(0xFFEEEDF4.toInt(), 0xFFFFFFFF.toInt(), 0xFFE2E2E9.toInt(), 0xFFFAF8FF.toInt(), 0xFFDCE2F9.toInt(), 0xFF404659.toInt(), 0xFF475D92.toInt(), 0xFFFFFFFF.toInt(), 0xFF1A1B20.toInt(), 0xFF44464F.toInt(), 0xFFC5C6D0.toInt())),
        Triple("1B6EF3", false, MaterialRoles(0xFF1E1F25.toInt(), 0xFF0C0E13.toInt(), 0xFF33343A.toInt(), 0xFF38393F.toInt(), 0xFF404659.toInt(), 0xFFDCE2F9.toInt(), 0xFFB0C6FF.toInt(), 0xFF152E60.toInt(), 0xFFE2E2E9.toInt(), 0xFFC5C6D0.toInt(), 0xFF44464F.toInt())),
        Triple("B3261E", true, MaterialRoles(0xFFFCEAE7.toInt(), 0xFFFFFFFF.toInt(), 0xFFF1DEDC.toInt(), 0xFFFFF8F7.toInt(), 0xFFFFDAD5.toInt(), 0xFF5D3F3B.toInt(), 0xFF904A42.toInt(), 0xFFFFFFFF.toInt(), 0xFF231918.toInt(), 0xFF534341.toInt(), 0xFFD8C2BE.toInt())),
        Triple("B3261E", false, MaterialRoles(0xFF271D1C.toInt(), 0xFF140C0B.toInt(), 0xFF3D3231.toInt(), 0xFF423735.toInt(), 0xFF5D3F3B.toInt(), 0xFFFFDAD5.toInt(), 0xFFFFB4AA.toInt(), 0xFF561E18.toInt(), 0xFFF1DEDC.toInt(), 0xFFD8C2BE.toInt(), 0xFF534341.toInt())),
        Triple("386A20", true, MaterialRoles(0xFFEDEFE5.toInt(), 0xFFFFFFFF.toInt(), 0xFFE1E4D9.toInt(), 0xFFF8FAF0.toInt(), 0xFFD8E7CB.toInt(), 0xFF3E4A35.toInt(), 0xFF446732.toInt(), 0xFFFFFFFF.toInt(), 0xFF191D16.toInt(), 0xFF43483E.toInt(), 0xFFC3C8BB.toInt())),
        Triple("386A20", false, MaterialRoles(0xFF1D211A.toInt(), 0xFF0C0F0A.toInt(), 0xFF32362F.toInt(), 0xFF373A33.toInt(), 0xFF3E4A35.toInt(), 0xFFD8E7CB.toInt(), 0xFFA9D291.toInt(), 0xFF173807.toInt(), 0xFFE1E4D9.toInt(), 0xFFC3C8BB.toInt(), 0xFF43483E.toInt())),
        Triple("FFDE3F", true, MaterialRoles(0xFFF4EDDF.toInt(), 0xFFFFFFFF.toInt(), 0xFFE8E2D4.toInt(), 0xFFFFF9EE.toInt(), 0xFFEEE2BC.toInt(), 0xFF4E472A.toInt(), 0xFF6D5E0F.toInt(), 0xFFFFFFFF.toInt(), 0xFF1E1B13.toInt(), 0xFF4B4739.toInt(), 0xFFCDC6B4.toInt())),
        Triple("FFDE3F", false, MaterialRoles(0xFF222017.toInt(), 0xFF100E07.toInt(), 0xFF38352B.toInt(), 0xFF3C3930.toInt(), 0xFF4E472A.toInt(), 0xFFEEE2BC.toInt(), 0xFFDBC66E.toInt(), 0xFF3A3000.toInt(), 0xFFE8E2D4.toInt(), 0xFFCDC6B4.toInt(), 0xFF4B4739.toInt())),
        Triple("6750A4", true, MaterialRoles(0xFFF2ECF4.toInt(), 0xFFFFFFFF.toInt(), 0xFFE6E0E9.toInt(), 0xFFFDF7FF.toInt(), 0xFFE8DEF8.toInt(), 0xFF4A4458.toInt(), 0xFF65558F.toInt(), 0xFFFFFFFF.toInt(), 0xFF1D1B20.toInt(), 0xFF49454E.toInt(), 0xFFCAC4CF.toInt())),
        Triple("6750A4", false, MaterialRoles(0xFF211F24.toInt(), 0xFF0F0D13.toInt(), 0xFF36343A.toInt(), 0xFF3B383E.toInt(), 0xFF4A4458.toInt(), 0xFFE8DEF8.toInt(), 0xFFCFBDFE.toInt(), 0xFF36275D.toInt(), 0xFFE6E0E9.toInt(), 0xFFCAC4CF.toInt(), 0xFF49454E.toInt())),
        Triple("00696E", true, MaterialRoles(0xFFE9EFEF.toInt(), 0xFFFFFFFF.toInt(), 0xFFDDE4E4.toInt(), 0xFFF4FAFA.toInt(), 0xFFCCE8E9.toInt(), 0xFF324B4D.toInt(), 0xFF00696E.toInt(), 0xFFFFFFFF.toInt(), 0xFF161D1D.toInt(), 0xFF3F4949.toInt(), 0xFFBEC8C9.toInt())),
        Triple("00696E", false, MaterialRoles(0xFF1A2121.toInt(), 0xFF090F10.toInt(), 0xFF303636.toInt(), 0xFF343A3B.toInt(), 0xFF324B4D.toInt(), 0xFFCCE8E9.toInt(), 0xFF80D4D9.toInt(), 0xFF003739.toInt(), 0xFFDDE4E4.toInt(), 0xFFBEC8C9.toInt(), 0xFF3F4949.toInt())),
        Triple("000000", true, MaterialRoles(0xFFFAEAED.toInt(), 0xFFFFFFFF.toInt(), 0xFFEFDFE1.toInt(), 0xFFFFF8F8.toInt(), 0xFFFFD9E2.toInt(), 0xFF5A3F47.toInt(), 0xFF8C4A60.toInt(), 0xFFFFFFFF.toInt(), 0xFF22191C.toInt(), 0xFF514347.toInt(), 0xFFD5C2C6.toInt())),
        Triple("000000", false, MaterialRoles(0xFF261D20.toInt(), 0xFF140C0E.toInt(), 0xFF3C3235.toInt(), 0xFF413739.toInt(), 0xFF5A3F47.toInt(), 0xFFFFD9E2.toInt(), 0xFFFFB1C8.toInt(), 0xFF541D32.toInt(), 0xFFEFDFE1.toInt(), 0xFFD5C2C6.toInt(), 0xFF514347.toInt())),
        Triple("FFFFFF", true, MaterialRoles(0xFFE9EFF0.toInt(), 0xFFFFFFFF.toInt(), 0xFFDEE3E5.toInt(), 0xFFF5FAFB.toInt(), 0xFFCDE7EC.toInt(), 0xFF334B4F.toInt(), 0xFF006874.toInt(), 0xFFFFFFFF.toInt(), 0xFF171D1E.toInt(), 0xFF3F484A.toInt(), 0xFFBFC8CA.toInt())),
        Triple("FFFFFF", false, MaterialRoles(0xFF1B2122.toInt(), 0xFF090F10.toInt(), 0xFF303637.toInt(), 0xFF343A3B.toInt(), 0xFF334B4F.toInt(), 0xFFCDE7EC.toInt(), 0xFF82D3E0.toInt(), 0xFF00363D.toInt(), 0xFFDEE3E5.toInt(), 0xFFBFC8CA.toInt(), 0xFF3F484A.toInt())),
    )

    private fun roles(seed: String, light: Boolean) = fixtures.single { it.first == seed && it.second == light }.third

    @Test
    fun gboardMapsTheMaterialRolesAsGboardDoes() {
        val light = roles("5D646B", true)
        val p = LookDynamicColors.gboardPalette(light, true, gboard.light)
        assertThat(p.keyboardBackground).isEqualTo(light.surfaceContainer)
        assertThat(p.barBackground).isEqualTo(light.surfaceContainer)
        assertWithMessage("letter keys: surfaceContainerLowest (white) in light mode").that(p.key).isEqualTo(0xFFFFFFFF.toInt())
        assertWithMessage("function keys: secondaryContainer — Pixel Gboard drew #D3E5F5")
            .that(p.modifierKey).isEqualTo(0xFFD3E5F5.toInt())
        assertThat(p.enterKey).isEqualTo(light.secondaryContainer)
        assertThat(p.modifierLabel).isEqualTo(light.onSecondaryContainer)
        assertThat(p.enterLabel).isEqualTo(light.onSecondaryContainer)
        assertThat(p.actionKey).isEqualTo(light.primary)
        assertThat(p.actionLabel).isEqualTo(light.onPrimary)
        assertThat(p.keyLocked).isEqualTo(light.primary)
        assertThat(p.lockedLabel).isEqualTo(light.onPrimary)
        assertThat(p.label).isEqualTo(light.onSurface)
        assertThat(p.barText).isEqualTo(light.onSurface)
        assertThat(p.hint).isEqualTo(light.onSurfaceVariant)
        assertThat(p.barSeparator).isEqualTo(light.outlineVariant)
        assertThat(p.swipeTrail).isEqualTo(light.primary)
        assertThat(p.keyPressed).isEqualTo(light.surfaceContainerHighest)
        assertWithMessage("the preview bubble and popover are letter-key coloured, selection primary")
            .that(LookPopupColors.of(p)).isEqualTo(LookPopupColors(p.key, light.onSurface, light.primary, light.onPrimary))
        assertWithMessage("the shadow is not a role: kept from the fixed palette")
            .that(p.keyShadow).isEqualTo(gboard.light.keyShadow)

        val dark = roles("5D646B", false)
        val d = LookDynamicColors.gboardPalette(dark, false, gboard.dark)
        assertWithMessage("letter keys: surfaceBright in dark mode").that(d.key).isEqualTo(dark.surfaceBright)
        assertThat(d.keyboardBackground).isEqualTo(dark.surfaceContainer)
        assertThat(d.modifierKey).isEqualTo(dark.secondaryContainer)
        assertWithMessage("dark pressed: the label at 10 % over the key")
            .that(d.keyPressed).isEqualTo(LookDynamicColors.blend(dark.onSurface, dark.surfaceBright, 0.10f))
        assertWithMessage("…which is lighter than the key").that(lum(d.keyPressed)).isGreaterThan(lum(d.key))
    }

    @Test
    fun everySeedMeetsTheContrastFloorsInBothModes() {
        for ((seed, light, r) in fixtures) {
            val fixed = gboard.palette(light)
            val p = LookDynamicColors.gboardPalette(r, light, fixed)
            val mode = if (light) "light" else "dark"
            assertWithMessage("$seed $mode maps (no fallback): ${LookContrast.failures(p)}")
                .that(p).isNotSameInstanceAs(fixed)
            assertWithMessage("$seed $mode").that(LookContrast.failures(p)).isEmpty()
        }
    }

    @Test
    fun aMappedPaletteThatMissesAFloorFallsBackToTheFixedPalette() {
        // A broken ROM palette: the label equals the (white) key colour.
        val broken = roles("5D646B", true).copy(onSurface = 0xFFFFFFFF.toInt())
        assertThat(LookDynamicColors.gboardPalette(broken, true, gboard.light)).isSameInstanceAs(gboard.light)
    }

    @Test
    fun translucentRoleValuesAreDrawnOpaque() {
        val r = roles("1B6EF3", true)
        val p = LookDynamicColors.gboardPalette(r.copy(surfaceContainer = r.surfaceContainer and 0x00FFFFFF), true, gboard.light)
        assertThat(p.keyboardBackground ushr 24).isEqualTo(0xFF)
    }

    @Test
    fun onlyGboardFollowsTheWallpaper() {
        assertThat(KeyboardLooks.ALL.filter { it.dynamicColor }.map { it.id }).containsExactly(KeyboardLooks.GBOARD)
    }

    @Test
    fun blendCompositesPerChannel() {
        assertThat(LookDynamicColors.blend(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0.5f)).isEqualTo(0xFF808080.toInt())
        assertThat(LookDynamicColors.blend(0xFF123456.toInt(), 0xFFABCDEF.toInt(), 0f)).isEqualTo(0xFFABCDEF.toInt())
    }

    private fun lum(c: Int) = ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
}
