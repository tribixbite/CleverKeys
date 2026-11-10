package tribixbite.cleverkeys.theme

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The nature scenes (maintainer 2026-11-10): every scene paints an opaque, deterministic picture
 * of any size, and moves — the stream and the snow by a second image to cross-fade, the others by
 * layers that bend, sway, roll and wash (round two: "grass just pulses to blurry instead of
 * sways", forest leaves that fall instead of sway, a beach shoreline that pulses). An empty view
 * paints nothing. Legibility over each scene: `KeyboardLooksTest`; the motion itself:
 * `SceneMotionTest`.
 */
class SceneRasterTest {

    @Test
    fun everySceneIsOpaqueDeterministicAndMoves() {
        for (kind in SceneKind.entries) {
            val a = SceneRaster.paint(kind, 160, 110, 0.5f)
            assertThat(a.base.size).isEqualTo(160 * 110)
            assertWithMessage("$kind is opaque").that(a.base.all { it ushr 24 == 0xFF }).isTrue()
            assertWithMessage("$kind is the same picture for the same size")
                .that(a.base).isEqualTo(SceneRaster.paint(kind, 160, 110, 0.5f).base)
            assertWithMessage("$kind is not a flat colour").that(a.base.toSet().size).isGreaterThan(50)
            for (layer in a.layers) {
                assertThat(layer.pixels.size).isEqualTo(layer.width * layer.height)
                assertWithMessage("$kind: a layer is a part, not a sheet").that(layer.pixels.any { it ushr 24 == 0 }).isTrue()
            }
            // Something moves: the cross-faded image, or the layers between two moments.
            val still = SceneMotion.composite(a, 0.0)
            val later = SceneMotion.composite(a, 1.7, altShare = 1f)
            val changed = still.indices.count { still[it] != later[it] }
            assertWithMessage("$kind moves").that(changed).isGreaterThan(still.size / 200)
            assertWithMessage("$kind moves either by a second image or by its layers, not both")
                .that((a.alt != null) xor a.layers.isNotEmpty()).isTrue()
        }
    }

    /** The scenes that were "blurry" paint at full resolution; the soft ones at half. */
    @Test
    fun theDetailedScenesPaintAtFullResolution() {
        for (kind in listOf(SceneKind.MEADOW, SceneKind.FOREST, SceneKind.RIVER_ROCKS, SceneKind.BEACH)) {
            assertWithMessage("$kind").that(kind.paintScale).isEqualTo(1)
        }
        assertThat(SceneKind.STREAM.paintScale).isEqualTo(2)
        assertThat(SceneKind.SNOW.paintScale).isEqualTo(2)
    }

    /** Grass bands sway from their roots, branches from the top, the beach's water rolls and washes. */
    @Test
    fun eachSceneMovesTheWayItsSubjectDoes() {
        fun kinds(kind: SceneKind) = SceneRaster.paint(kind, 200, 140, 0.5f).layers.map { it.motion.kind }.toSet()
        assertThat(kinds(SceneKind.MEADOW)).containsExactly(LayerMotionKind.SWAY_UP)
        assertThat(kinds(SceneKind.FOREST)).containsExactly(LayerMotionKind.SWAY_DOWN, LayerMotionKind.SWAY_UP)
        assertThat(kinds(SceneKind.BEACH)).containsExactly(LayerMotionKind.ROLL, LayerMotionKind.WASH)
        assertThat(kinds(SceneKind.RIVER_ROCKS)).containsExactly(LayerMotionKind.RIPPLE)
    }

    @Test
    fun anEmptyViewPaintsNothing() {
        for (kind in SceneKind.entries) {
            assertThat(SceneRaster.paint(kind, 0, 100, 1f).base).isEmpty()
            assertThat(SceneRaster.paint(kind, 100, 0, 1f).layers).isEmpty()
        }
    }
}
