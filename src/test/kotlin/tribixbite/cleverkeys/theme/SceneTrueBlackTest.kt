package tribixbite.cleverkeys.theme

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.pow

/**
 * True black scenes ([SceneTrueBlack]): broad, even areas go to pure black (OLED pixels off),
 * light, detail and colour stay, and no pixel ever gets brighter than it was painted.
 */
class SceneTrueBlackTest {

    private val meadow = SceneTrueBlack.paramsFor(SceneKind.MEADOW)
    private val beach = SceneTrueBlack.paramsFor(SceneKind.BEACH)

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun rgbOf(c: Int) = c and 0xFFFFFF

    /** Mean linear light of [px] (sRGB treated as gamma 2.2), what an OLED panel roughly spends. */
    private fun meanLight(px: IntArray): Double {
        var sum = 0.0
        for (c in px) for (sh in intArrayOf(16, 8, 0)) sum += ((c shr sh and 0xFF) / 255.0).pow(2.2)
        return sum / px.size / 3
    }

    @Test
    fun aFlatBrightAreaTurnsPureBlackAndStaysOpaque() {
        val sand = argb(0xFF, 0xE0, 0xC8, 0x90)
        val px = IntArray(40 * 30) { sand }
        val cutoff = SceneTrueBlack.lumaQuantile(px, meadow.lightQuantile)
        val out = SceneTrueBlack.darken(px, 40, 30, 2, cutoff, meadow)
        assertThat(out.toSet()).containsExactly(0xFF000000.toInt())
    }

    @Test
    fun aThinBrightLineOnDarkGroundKeepsItsLight() {
        val w = 40
        val h = 30
        val ground = argb(0xFF, 0x20, 0x30, 0x20)
        val blade = argb(0xFF, 0xC0, 0xE0, 0x80)
        val px = IntArray(w * h) { if (it % w == 20) blade else ground }
        // The cut-off from the ground alone: the line is kept by its detail, not by the light term.
        val out = SceneTrueBlack.darken(px, w, h, 2, cutoff = 1f, params = meadow)
        val lit = out[10 * w + 20]
        assertWithMessage("the blade").that(rgbOf(lit)).isNotEqualTo(0)
        assertWithMessage("the blade keeps its hue (green over red over blue)")
            .that((lit shr 8 and 0xFF) > (lit shr 16 and 0xFF) && (lit shr 16 and 0xFF) > (lit and 0xFF)).isTrue()
        assertWithMessage("the ground far from it").that(rgbOf(out[10 * w + 5])).isEqualTo(0)
    }

    @Test
    fun colourKeepsTheSeaOnlyWhereTheSceneAsksForIt() {
        val sea = argb(0xFF, 0x10, 0x70, 0x88)
        val px = IntArray(30 * 20) { sea }
        assertWithMessage("beach: a saturated sea stays")
            .that(rgbOf(SceneTrueBlack.darken(px, 30, 20, 2, cutoff = 1f, params = beach)[300])).isNotEqualTo(0)
        assertWithMessage("meadow: colour alone keeps nothing")
            .that(rgbOf(SceneTrueBlack.darken(px, 30, 20, 2, cutoff = 1f, params = meadow)[300])).isEqualTo(0)
    }

    @Test
    fun aLayerKeepsItsAlphaAndItsTransparentSurroundingsAreNotAnEdge() {
        val w = 30
        val h = 20
        val clear = 0
        val leaf = argb(0x80, 0x30, 0x50, 0x30)
        // A flat translucent block in a transparent field: its own border must not light up.
        val px = IntArray(w * h) { i -> if (i % w in 10..19 && i / w in 5..14) leaf else clear }
        val out = SceneTrueBlack.darken(px, w, h, 2, cutoff = 1f, params = meadow)
        for (i in px.indices) {
            assertWithMessage("alpha at $i").that(out[i] ushr 24).isEqualTo(px[i] ushr 24)
            if (px[i] == clear) assertWithMessage("transparent at $i").that(out[i]).isEqualTo(0)
        }
        assertWithMessage("the block's edge (x 10) reads as flat").that(rgbOf(out[10 * w + 10])).isEqualTo(0)
    }

    @Test
    fun theLightTermFadesInAboveTheCutOff() {
        val ramp = IntArray(256) { v -> argb(0xFF, v, v, v) }
        val out = SceneTrueBlack.darken(ramp, 256, 1, 1, cutoff = 0.5f, params = meadow)
        // The ramp's own steps are 1/255 apart: far below the detail threshold.
        assertWithMessage("below the cut-off").that(rgbOf(out[100])).isEqualTo(0)
        val lows = (140..250 step 10).map { out[it] and 0xFF }
        assertWithMessage("brightness rises with the painted brightness").that(lows).isInStrictOrder()
        assertWithMessage("never more than PEAK of white").that(out[255] and 0xFF)
            .isAtMost((255 * SceneTrueBlack.PEAK + 0.5f).toInt())
    }

    @Test
    fun theQuantileIgnoresTransparentPixels() {
        val px = IntArray(100) { if (it < 50) 0 else argb(0xFF, 200, 200, 200) }
        assertThat(SceneTrueBlack.lumaQuantile(px, 0.1f)).isWithin(0.01f).of(200 / 255f)
        assertThat(SceneTrueBlack.lumaQuantile(IntArray(4), 0.5f)).isEqualTo(1f)
    }

    /**
     * Every scene, painted small: every pixel is at most as bright as painted, most of the light
     * is gone, a good share of the pixels are off, and something is still drawn.
     */
    @Test
    fun everySceneDarkensWithoutBrighteningAnyPixel() {
        val w = 252
        val h = 215
        val density = 0.66f
        for (kind in SceneKind.entries) {
            val painted = SceneRaster.paint(kind, w, h, density)
            val dark = SceneTrueBlack.apply(painted, kind, density)
            assertWithMessage("$kind size").that(dark.base.size).isEqualTo(painted.base.size)
            assertWithMessage("$kind cross-fade image").that(dark.alt == null).isEqualTo(painted.alt == null)
            assertWithMessage("$kind layers").that(dark.layers.size).isEqualTo(painted.layers.size)
            for (i in painted.base.indices) {
                val a = painted.base[i]
                val b = dark.base[i]
                for (sh in intArrayOf(16, 8, 0)) {
                    if ((b shr sh and 0xFF) > (a shr sh and 0xFF)) {
                        throw AssertionError("$kind pixel $i channel $sh brighter: ${a.toUInt().toString(16)} -> ${b.toUInt().toString(16)}")
                    }
                }
            }
            painted.layers.zip(dark.layers).forEach { (p, d) ->
                assertWithMessage("$kind layer placement").that(d.placement).isEqualTo(p.placement)
                for (i in p.pixels.indices) {
                    assertWithMessage("$kind layer alpha").that(d.pixels[i] ushr 24).isEqualTo(p.pixels[i] ushr 24)
                }
            }
            // Measured on the picture as seen, layers included: the snow's hills are a layer over
            // a dark sky, and a base-only check passed while they stayed lit (Fable audit).
            val seenBefore = SceneMotion.composite(painted, 0.0)
            val seenAfter = SceneMotion.composite(dark, 0.0)
            val before = meanLight(seenBefore)
            val after = meanLight(seenAfter)
            val black = seenAfter.count { rgbOf(it) == 0 }.toDouble() / seenAfter.size
            assertWithMessage("$kind keeps under half its light ($before -> $after)").that(after).isLessThan(before * 0.5)
            assertWithMessage("$kind turns a fifth of its pixels off ($black)").that(black).isAtLeast(0.2)
            assertWithMessage("$kind still shows something").that(after).isGreaterThan(0.0)
        }
    }

    @Test
    fun theSettingIsPartOfTheTuningAndOffByDefault() {
        assertThat(SceneMotionTuning.DEFAULT.trueBlack).isFalse()
        val on = SceneMotionTuning.fromPercent(speed = 100, intensity = 100, twinkle = 55, crisp = false, trueBlack = true)
        assertThat(on.trueBlack).isTrue()
        assertThat(on.copy(trueBlack = false)).isEqualTo(SceneMotionTuning.DEFAULT)
    }
}
