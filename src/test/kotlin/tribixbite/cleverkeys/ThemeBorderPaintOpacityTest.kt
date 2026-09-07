package tribixbite.cleverkeys

import android.graphics.Paint
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.objenesis.ObjenesisStd

/**
 * Audit 2026-09-06, H-6: `Theme.Computed`'s border paint ignored the user's key opacity.
 *
 * `init_border_paint` ran `alpha = config.keyOpacity` BEFORE `setColor(color)` —
 * `Paint.setColor` writes the full ARGB including the colour's own alpha, so the opacity
 * assignment was discarded and borders always rendered at the border colour's alpha
 * (opaque for every shipped theme). A translucent keyboard therefore showed translucent
 * key bodies with fully opaque borders. The sibling `bg_paint` does it in the correct
 * order (`color = …` THEN `alpha = …`, Theme.kt Key.init) — this pins the border paint
 * to the same contract.
 *
 * Red (pre-fix): border alpha == 255 (the opaque colour's own alpha), not keyOpacity.
 *
 * Runs against the functional `android.graphics.Paint` test-source shadow, which
 * reproduces the two REAL semantics under test: `setColor` replaces the whole ARGB,
 * `setAlpha`/`getAlpha` touch only the top byte. The factory under test is private, so
 * it is reached by reflection; Config is Objenesis-allocated with only `keyOpacity` set.
 */
class ThemeBorderPaintOpacityTest {

    @Test
    fun borderPaint_honoursKeyOpacity() {
        val config = ObjenesisStd().newInstance(Config::class.java)
        config.keyOpacity = 100

        // The private companion factory under test (Theme.Computed.init_border_paint).
        val companionClass = Class.forName("tribixbite.cleverkeys.Theme\$Computed\$Companion")
        val companion = Class.forName("tribixbite.cleverkeys.Theme\$Computed")
            .getDeclaredField("Companion").get(null)
        val method = companionClass.getDeclaredMethod(
            "init_border_paint",
            Config::class.java,
            Float::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        // An opaque border colour — what every shipped theme and the runtime scheme carry.
        val paint = method.invoke(companion, config, 2f, 0xFF424242.toInt()) as Paint

        assertWithMessage(
            "border_paint must honour keyOpacity — setColor(opaque) after `alpha = keyOpacity` " +
                "discards the opacity (audit H-6); set the colour first, then the alpha"
        ).that(paint.alpha).isEqualTo(100)
    }

    /** The colour channels themselves must survive the reordered assignment. */
    @Test
    fun borderPaint_keepsTheBorderColourChannels() {
        val config = ObjenesisStd().newInstance(Config::class.java)
        config.keyOpacity = 100

        val companionClass = Class.forName("tribixbite.cleverkeys.Theme\$Computed\$Companion")
        val companion = Class.forName("tribixbite.cleverkeys.Theme\$Computed")
            .getDeclaredField("Companion").get(null)
        val method = companionClass.getDeclaredMethod(
            "init_border_paint",
            Config::class.java,
            Float::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        val paint = method.invoke(companion, config, 2f, 0xFF424242.toInt()) as Paint

        assertWithMessage("RGB channels come from the theme's border colour")
            .that(paint.color and 0x00FFFFFF).isEqualTo(0x424242)
    }
}
