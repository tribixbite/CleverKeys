package tribixbite.cleverkeys.theme

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.pow

/**
 * Theme Manager's scene thumbnails ([ScenePreview]): every scene look shows its backdrop as the
 * keyboard paints it, and in true black when that is on.
 */
class ScenePreviewTest {

    private fun light(px: IntArray): Double {
        var sum = 0.0
        for (c in px) for (sh in intArrayOf(16, 8, 0)) sum += ((c shr sh and 0xFF) / 255.0).pow(2.2)
        return sum / px.size / 3
    }

    @Test
    fun everySceneLookHasAThumbnailAndTrueBlackDarkensIt() {
        for (look in KeyboardLooks.SCENES) {
            val palette = look.dark
            val backdrop = palette.backdrop!!
            val plain = ScenePreview.pixels(backdrop, palette.keyboardBackground, 120, 76, 120 / 411f, trueBlack = false)
            val black = ScenePreview.pixels(backdrop, palette.keyboardBackground, 120, 76, 120 / 411f, trueBlack = true)
            assertWithMessage("${look.id} thumbnail").that(plain).isNotNull()
            assertThat(plain!!.size).isEqualTo(120 * 76)
            assertWithMessage("${look.id} is opaque").that(plain.all { it ushr 24 == 0xFF }).isTrue()
            assertWithMessage("${look.id} in true black gives off less light")
                .that(light(black!!)).isLessThan(light(plain))
        }
    }

    @Test
    fun theAuroraInTrueBlackKeepsItsCurtainsOverABlackSky() {
        val palette = KeyboardLooks.auroraLook.dark
        val black = ScenePreview.pixels(palette.backdrop!!, palette.keyboardBackground, 120, 76, 120 / 411f, trueBlack = true)!!
        assertWithMessage("sky pixels are off").that(black.count { it and 0xFFFFFF == 0 }).isGreaterThan(black.size / 10)
        assertWithMessage("curtains still show").that(black.count { it and 0xFFFFFF != 0 }).isGreaterThan(black.size / 4)
    }

    @Test
    fun aBackdropThatPaintsNothingHasNoThumbnail() {
        val empty = LookBackdrop(glows = emptyList())
        assertThat(ScenePreview.pixels(empty, 0xFF000000.toInt(), 120, 76, 0.3f, trueBlack = false)).isNull()
        assertThat(ScenePreview.pixels(KeyboardLooks.auroraLook.dark.backdrop!!, 0, 0, 76, 0.3f, false)).isNull()
    }
}
