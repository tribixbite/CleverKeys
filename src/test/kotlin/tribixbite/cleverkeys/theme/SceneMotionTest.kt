package tribixbite.cleverkeys.theme

import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.abs

/**
 * How a scene's layers move ([SceneMotion], maintainer 2026-11-10: grass that sways instead of
 * pulsing, leaves that sway, "subtle full waves"): roots and branch tops stay put while the free
 * ends bend, the breeze travels across the field, waves surge and ebb without a jump, and no
 * frame steps further than reads as smooth at the scene's frame rate.
 */
class SceneMotionTest {

    private val grass = LayerMotion(
        LayerMotionKind.SWAY_UP, anchorY = 100f, reach = 60f, amplitude = 10f, bias = 0.3f, periodS = 4.6f, wavelength = 300f,
    )

    private fun dx(m: LayerMotion, x: Float, y: Float, t: Double): Float {
        val out = FloatArray(2)
        SceneMotion.displace(m, x, y, t, out)
        return out[0]
    }

    @Test
    fun grassBendsFromItsRootsAndMovesMostAtTheTips() {
        var t = 0.0
        while (t < 20.0) {
            assertWithMessage("roots stay put at $t").that(dx(grass, 50f, 100f, t)).isZero()
            assertThat(dx(grass, 50f, 130f, t)).isZero()
            val mid = abs(dx(grass, 50f, 70f, t))
            val tip = abs(dx(grass, 50f, 40f, t))
            assertWithMessage("the tip moves at least as far as the middle").that(tip).isAtLeast(mid)
            assertWithMessage("never past its amplitude (plus the wind's lean)").that(tip).isAtMost(grass.amplitude * (1f + grass.bias) + 1e-3f)
            t += 0.37
        }
        val swing = (0 until 200).map { dx(grass, 50f, 40f, it * 0.1) }
        assertWithMessage("it actually sways both ways around its lean").that(swing.max() - swing.min()).isGreaterThan(grass.amplitude)
    }

    @Test
    fun theBreezeTravelsAcrossTheField() {
        // At one moment, blades a gust apart lean differently: the field does not sway in lockstep.
        val leans = (0 until 10).map { dx(grass, it * 40f, 40f, 1.0) }
        assertThat(leans.max() - leans.min()).isGreaterThan(grass.amplitude * 0.5f)
    }

    @Test
    fun branchesSwingFromTheTop() {
        val branch = grass.copy(kind = LayerMotionKind.SWAY_DOWN, anchorY = 0f)
        for (i in 0 until 50) {
            assertThat(dx(branch, 30f, 0f, i * 0.3)).isZero()
            assertThat(abs(dx(branch, 30f, 60f, i * 0.3))).isAtLeast(abs(dx(branch, 30f, 20f, i * 0.3)))
        }
    }

    @Test
    fun aWashSurgesAndEbbsWithoutAJump() {
        val wash = LayerMotion(LayerMotionKind.WASH, amplitude = 50f, periodS = 8f, fade = 0.85f)
        var t = 0.0
        var lastY = Float.NaN
        var lastA = Float.NaN
        val step = 1.0 / 12
        var furthest = 0f
        while (t < 24.0) {
            val out = FloatArray(2)
            SceneMotion.displace(wash, 0f, 0f, t, out)
            val a = SceneMotion.alpha(wash, t)
            assertThat(out[1]).isIn(Range.closed(0f, 50f))
            assertThat(a).isIn(Range.closed(1f - wash.fade - 1e-4f, 1f))
            if (!lastY.isNaN()) {
                assertWithMessage("no jump at $t (wrap included)").that(abs(out[1] - lastY)).isAtMost(50f * 0.25f)
                assertThat(abs(a - lastA)).isAtMost(0.1f)
            }
            furthest = maxOf(furthest, out[1])
            lastY = out[1]; lastA = a
            t += step
        }
        assertWithMessage("it reaches its full run").that(furthest).isGreaterThan(45f)
    }

    @Test
    fun aSwellIsInvisibleWhereItWrapsAround() {
        val swell = LayerMotion(LayerMotionKind.ROLL, amplitude = 80f, periodS = 8f)
        assertThat(SceneMotion.alpha(swell, 0.0)).isWithin(1e-3f).of(0f)
        assertThat(SceneMotion.alpha(swell, 8.0)).isWithin(1e-3f).of(0f)
        assertThat(SceneMotion.alpha(swell, 4.0)).isWithin(1e-3f).of(1f)
    }

    /** At each scene's frame rate, no point of any layer steps more than a few raster pixels. */
    @Test
    fun consecutiveFramesStepSmoothly() {
        for (kind in SceneKind.entries) {
            val painting = SceneRaster.paint(kind, 400, 360, 2.625f / kind.paintScale)
            val dt = kind.frameMs / 1000.0
            for (layer in painting.layers) {
                val m = layer.motion
                var t = 0.0
                val a = FloatArray(2); val b = FloatArray(2)
                while (t < 16.0) {
                    for (y in listOf(layer.top.toFloat(), layer.top + layer.height / 2f, (layer.top + layer.height).toFloat())) {
                        SceneMotion.displace(m, 100f, y, t, a)
                        SceneMotion.displace(m, 100f, y, t + dt, b)
                        val step = abs(b[0] - a[0]) + abs(b[1] - a[1])
                        // ROLL wraps while invisible; WASH surges fastest.
                        val limit = if (m.kind == LayerMotionKind.ROLL && SceneMotion.alpha(m, t) < 0.05f) Float.MAX_VALUE
                        else if (m.kind == LayerMotionKind.WASH) 4.5f * 2.625f else 2.6f * 2.625f / kind.paintScale
                        assertWithMessage("$kind ${m.kind} at $t, y $y").that(step).isAtMost(limit)
                    }
                    t += 0.29
                }
            }
        }
    }

    @Test
    fun theMeshIsTheDisplacementOfAGrid() {
        val layer = SceneLayer(-10, 40, 100, 60, IntArray(100 * 60), grass)
        val cols = SceneMotion.meshColumns(layer.width)
        val rows = SceneMotion.meshRows(layer.height)
        val verts = FloatArray(2 * (cols + 1) * (rows + 1))
        SceneMotion.mesh(layer, 2.0, 2f, verts)
        val d = FloatArray(2)
        SceneMotion.displace(grass, -10f, 40f, 2.0, d)
        assertThat(verts[0]).isWithin(1e-3f).of((-10f + d[0]) * 2f)
        assertThat(verts[1]).isWithin(1e-3f).of((40f + d[1]) * 2f)
        val last = verts.size - 2
        SceneMotion.displace(grass, 90f, 100f, 2.0, d)
        assertThat(verts[last]).isWithin(1e-3f).of((90f + d[0]) * 2f)
        assertThat(verts[last + 1]).isWithin(1e-3f).of((100f + d[1]) * 2f)
    }

    @Test
    fun aStillLayerCompositesInPlace() {
        val px = IntArray(4 * 4) { if (it == 5) 0xFFFF0000.toInt() else 0 }
        val still = LayerMotion(LayerMotionKind.SWAY_UP, anchorY = 100f, reach = 10f, amplitude = 0f, periodS = 1f)
        val painting = ScenePainting(8, 8, IntArray(64) { 0xFF000000.toInt() }, null, listOf(SceneLayer(2, 2, 4, 4, px, still)))
        val out = SceneMotion.composite(painting, 3.0)
        assertThat(out[(2 + 1) * 8 + (2 + 1)]).isEqualTo(0xFFFF0000.toInt())
        assertThat(out.count { it != 0xFF000000.toInt() }).isEqualTo(1)
    }
}
