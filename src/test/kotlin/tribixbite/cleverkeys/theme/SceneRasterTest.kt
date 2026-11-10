package tribixbite.cleverkeys.theme

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The nature scenes (maintainer 2026-11-10): every scene paints an opaque, deterministic picture
 * of any size, its second image differs (that difference is all a moving scene shows), and an
 * empty view paints nothing. Legibility over each scene: `KeyboardLooksTest`.
 */
class SceneRasterTest {

    @Test
    fun everySceneIsOpaqueDeterministicAndHasASecondImage() {
        for (kind in SceneKind.entries) {
            val a = SceneRaster.render(kind, 160, 110, 0.5f, 0)
            assertThat(a.size).isEqualTo(160 * 110)
            assertWithMessage("$kind is opaque").that(a.all { it ushr 24 == 0xFF }).isTrue()
            assertWithMessage("$kind is the same picture for the same size")
                .that(a).isEqualTo(SceneRaster.render(kind, 160, 110, 0.5f, 0))
            val b = SceneRaster.render(kind, 160, 110, 0.5f, 1)
            val changed = a.indices.count { a[it] != b[it] }
            assertWithMessage("$kind moves between its two images").that(changed).isGreaterThan(a.size / 200)
            assertWithMessage("$kind is not a flat colour").that(a.toSet().size).isGreaterThan(50)
        }
    }

    @Test
    fun anEmptyViewPaintsNothing() {
        for (kind in SceneKind.entries) {
            assertThat(SceneRaster.render(kind, 0, 100, 1f, 0)).isEmpty()
            assertThat(SceneRaster.render(kind, 100, 0, 1f, 1)).isEmpty()
        }
    }
}
