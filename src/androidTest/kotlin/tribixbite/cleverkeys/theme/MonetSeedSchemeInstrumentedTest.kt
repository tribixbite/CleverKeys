package tribixbite.cleverkeys.theme

import android.os.Build
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.Config

/**
 * [ThemeProvider.getColorScheme] for the Monet themes reads the device's real system palette
 * (todo 13c, 2026-11-20): the seed a theme created while Monet is selected starts from, and the
 * swipe trail choosing Monet sets, are the colours `MonetLight` / `MonetDark` draw. The pure
 * mapping is pinned by `MonetSeedSchemeTest`; this checks the `Context.getColor` path on an
 * Android 12+ device and the variant Monet (Auto) picks there.
 */
@RunWith(AndroidJUnit4::class)
class MonetSeedSchemeInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun scheme(themeId: String) = ThemeProvider.getInstance(context).getColorScheme(themeId)!!

    @Test
    fun monetDarkAndLightSeedTheSystemPaletteTheirStylesDraw() {
        assumeTrue("the system palette exists from Android 12", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val dark = scheme("monetdark")
        assertEquals(context.getColor(android.R.color.system_neutral1_900), dark.keyboardBackground.toArgb())
        assertEquals(context.getColor(android.R.color.system_neutral1_800), dark.keyDefault.toArgb())
        assertEquals(context.getColor(android.R.color.system_neutral1_0), dark.keyLabel.toArgb())
        assertEquals(context.getColor(android.R.color.system_accent1_400), dark.swipeTrail.toArgb())
        val light = scheme("monetlight")
        assertEquals(context.getColor(android.R.color.system_neutral1_100), light.keyboardBackground.toArgb())
        assertEquals(context.getColor(android.R.color.system_neutral1_50), light.keyDefault.toArgb())
        assertEquals(context.getColor(android.R.color.system_neutral1_800), light.keyLabel.toArgb())
    }

    @Test
    fun monetAutoSeedsTheVariantTheSystemModeSelects() {
        assumeTrue("the system palette exists from Android 12", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val expected = if (Config.isSystemLight(context.resources)) "monetlight" else "monetdark"
        assertEquals(
            scheme(expected).keyboardBackground.toArgb(),
            scheme("monet").keyboardBackground.toArgb(),
        )
    }
}
