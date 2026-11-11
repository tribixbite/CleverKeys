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
            // A still layer is a foreground in front of a moving image (the snow's ground).
            val moving = a.layers.filter { it.motion.kind != LayerMotionKind.STILL }
            assertWithMessage("$kind moves by a second image or by its layers, not both")
                .that(a.alt != null && moving.isNotEmpty()).isFalse()
            // The river rocks' painting is still: the rain over it is the look's particles, and
            // it maps where that rain lands on stone (most of it) or on water (the gaps).
            if (kind == SceneKind.RIVER_ROCKS) {
                assertThat(a.alt).isNull()
                assertThat(a.layers).isEmpty()
                assertThat(a.surface!!.solidShare).isIn(com.google.common.collect.Range.open(0.5f, 0.97f))
                continue
            }
            // Something moves: the cross-faded image, or the layers between two moments.
            val still = SceneMotion.composite(a, 0.0)
            val later = SceneMotion.composite(a, 1.7, altShare = 1f)
            val changed = still.indices.count { still[it] != later[it] }
            assertWithMessage("$kind moves").that(changed).isGreaterThan(still.size / 200)
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

    /** Grass bands sway from their roots, branches from the top, the beach's water rolls and washes; the stones lie still. */
    @Test
    fun eachSceneMovesTheWayItsSubjectDoes() {
        fun kinds(kind: SceneKind) = SceneRaster.paint(kind, 200, 140, 0.5f).layers.map { it.motion.kind }.toSet()
        assertThat(kinds(SceneKind.MEADOW)).containsExactly(LayerMotionKind.SWAY_UP)
        assertThat(kinds(SceneKind.FOREST)).containsExactly(LayerMotionKind.SWAY_DOWN, LayerMotionKind.SWAY_UP)
        assertWithMessage("the stream's light flows downstream").that(kinds(SceneKind.STREAM)).containsExactly(LayerMotionKind.FLOW)
        assertThat(kinds(SceneKind.BEACH)).containsExactly(LayerMotionKind.ROLL, LayerMotionKind.WASH)
        assertWithMessage("rain, not rippling light (it falls as particles)").that(kinds(SceneKind.RIVER_ROCKS)).isEmpty()
        assertWithMessage("the snow's hills and pines stand still before its moving sky").that(kinds(SceneKind.SNOW))
            .containsExactly(LayerMotionKind.STILL)
    }

    /**
     * The snow's aurora ripples as the aurora look's curtains do (maintainer 2026-11-11): its sky is
     * a cross-faded pair that drifts with the aurora's style, at the aurora's frame rate, while the
     * ground in front stays where it was painted (nothing of it moves between two moments).
     */
    @Test
    fun theSnowsAuroraSkyMovesLikeTheAuroraLook() {
        assertThat(MotionStyle.forScene(SceneKind.SNOW)).isSameInstanceAs(MotionStyle.AURORA)
        assertThat(MotionStyle.forScene(null)).isSameInstanceAs(MotionStyle.AURORA)
        assertThat(MotionStyle.forScene(SceneKind.STREAM)).isSameInstanceAs(MotionStyle.GENTLE)
        assertThat(SceneKind.SNOW.frameMs).isEqualTo(AuroraMotion.AURORA_FRAME_MS)
        val p = SceneRaster.paint(SceneKind.SNOW, 200, 140, 0.5f)
        assertWithMessage("a second sky image to cross-fade").that(p.alt).isNotNull()
        val ground = p.layers.single()
        val opaque = ground.pixels.indices.filter { ground.pixels[it] ushr 24 == 0xFF }
        assertWithMessage("the ground covers the lower part").that(opaque.size).isGreaterThan(ground.pixels.size / 5)
        assertWithMessage("the sky above it shows through")
            .that((0 until ground.width * ground.height / 4).count { ground.pixels[it] ushr 24 == 0 })
            .isGreaterThan(ground.width * ground.height / 8)
        val a = SceneMotion.composite(p, 0.0, altShare = 0f)
        val b = SceneMotion.composite(p, 2.5, altShare = 1f)
        assertWithMessage("the opaque ground does not change with the sky")
            .that(opaque.count { a[it] != b[it] }).isEqualTo(0)
    }

    @Test
    fun anEmptyViewPaintsNothing() {
        for (kind in SceneKind.entries) {
            assertThat(SceneRaster.paint(kind, 0, 100, 1f).base).isEmpty()
            assertThat(SceneRaster.paint(kind, 100, 0, 1f).layers).isEmpty()
        }
    }
}
