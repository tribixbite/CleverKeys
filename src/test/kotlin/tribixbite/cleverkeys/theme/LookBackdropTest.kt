package tribixbite.cleverkeys.theme

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * [LookStars]: the star field of a [LookBackdrop] is static (the same sky for the same keyboard
 * size, so a relayout never makes it flicker), inside the view, sized by area and capped. Pure JVM.
 */
class LookBackdropTest {

    private val field = KeyboardLooks.CLEVERKEYS_AURORA.stars!!

    @Test
    fun theSameSizeAlwaysGetsTheSameSky() {
        val a = LookStars.place(field, 1080, 760, 2.625f)
        val b = LookStars.place(field, 1080, 760, 2.625f)
        assertThat(a).isEqualTo(b)
        assertWithMessage("a rotated keyboard gets its own sky").that(LookStars.place(field, 2340, 600, 2.625f)).isNotEqualTo(a)
    }

    @Test
    fun starsStayInsideTheViewWithinTheirRanges() {
        val density = 2.625f
        val stars = LookStars.place(field, 1080, 760, density)
        // 411 × 290 dp ≈ 11.9 units of 100 × 100 dp at 14 per unit (2026-11-10: shimmering stars).
        assertThat(stars.size).isEqualTo(167)
        for (s in stars) {
            assertThat(s.x).isAtLeast(0f); assertThat(s.x).isLessThan(1080f)
            assertThat(s.y).isAtLeast(0f); assertThat(s.y).isLessThan(760f)
            assertThat(s.radius).isAtLeast(field.minRadiusDp * density - 1e-3f)
            assertThat(s.radius).isAtMost(field.maxRadiusDp * density + 1e-3f)
            assertThat(s.alpha).isAtLeast(field.minAlpha); assertThat(s.alpha).isAtMost(field.maxAlpha)
        }
        assertWithMessage("every 3rd star glints").that(stars.count { it.glint }).isEqualTo(56)
    }

    @Test
    fun anEmptyOrHugeViewIsBounded() {
        assertThat(LookStars.place(field, 0, 760, 2f)).isEmpty()
        assertThat(LookStars.place(field, 1080, 760, 0f)).isEmpty()
        assertThat(LookStars.place(field, 20_000, 20_000, 1f).size).isEqualTo(LookStars.MAX_STARS)
    }

    @Test
    fun glowsAreTranslucentAndTheTopFadesIntoTheBar() {
        val aurora = KeyboardLooks.CLEVERKEYS_AURORA
        for (g in aurora.glows) {
            val alpha = (g.color ushr 24) and 0xFF
            assertWithMessage("glow alpha").that(alpha).isIn(com.google.common.collect.Range.closed(0x10, 0x66))
        }
        assertThat(aurora.topFade).isIn(com.google.common.collect.Range.openClosed(0f, 0.5f))
    }

    /**
     * The curtains (maintainer 2026-11-09: the glows alone were "too cloud like"): rayed, lit
     * over the base and never dimmer than it, brightest at a curtain's rim, the same picture for
     * the same size.
     */
    @Test
    fun theAuroraIsRayedCurtainsOverTheBase() {
        val aurora = KeyboardLooks.CLEVERKEYS_AURORA
        assertWithMessage("the look paints curtains, not only glows").that(aurora.ribbons).isNotEmpty()
        val base = 0xFF0A080E.toInt()
        val w = 200
        val h = 120
        val a = AuroraRaster.render(base, aurora.ribbons, w, h, 0.5f)
        assertThat(a.size).isEqualTo(w * h)
        assertWithMessage("deterministic").that(a).isEqualTo(AuroraRaster.render(base, aurora.ribbons, w, h, 0.5f))
        fun channelSum(c: Int) = ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
        val baseSum = channelSum(base)
        for (c in a) {
            assertThat(c ushr 24).isEqualTo(0xFF)
            assertThat(channelSum(c)).isAtLeast(baseSum)
        }
        assertWithMessage("some of the view is lit").that(a.count { channelSum(it) > baseSum + 30 }).isGreaterThan(w * h / 20)
        assertWithMessage("and some is left as dark sky").that(a.count { channelSum(it) <= baseSum + 3 }).isGreaterThan(w * h / 10)

        // Rays: along a row through the near curtain, brightness varies column to column.
        val near = aurora.ribbons.first()
        val rays = AuroraRaster.rayProfile(near, w, 0.5f)
        assertThat(rays.all { it in 0.15f..1f }).isTrue()
        assertWithMessage("rays stand out of the band").that(rays.maxOrNull()!! - rays.minOrNull()!!).isGreaterThan(0.4f)

        assertThat(AuroraRaster.render(base, aurora.ribbons, 0, h, 0.5f)).isEmpty()
        assertWithMessage("no curtains = the base alone")
            .that(AuroraRaster.render(base, emptyList(), 4, 3, 1f).toSet()).containsExactly(base)
    }

    /**
     * Scene particles (leaves, snow, sparkles): a leaf field picks each particle's colour from its
     * tints and a tilt; a plain star field keeps exactly its sky (no extra random draws). The
     * forest no longer drops leaves (maintainer 2026-11-10: "swaying leaves not falling brown
     * specs"); its leaves sway as scene layers.
     */
    @Test
    fun leavesGetTheirOwnColoursAndTiltsAndStarsAreUnchanged() {
        val leaves = LookStarfield(
            perSquare100Dp = 2.5f, seed = 0xF0AE57L, color = 0xFFD98A3A.toInt(), minAlpha = 120, maxAlpha = 220,
            minRadiusDp = 1.2f, maxRadiusDp = 2.0f, glintEvery = 0, shape = ParticleShape.LEAF,
            tints = listOf(0xFFD98A3A.toInt(), 0xFFC4682C.toInt(), 0xFF9CB84A.toInt(), 0xFFE0B048.toInt()),
            driftXDpPerS = 4f, driftYDpPerS = 7f,
        )
        assertWithMessage("the forest's leaves sway; none fall").that(KeyboardLooks.forestLook.dark.backdrop!!.stars).isNull()
        val placed = LookStars.place(leaves, 1080, 760, 2.625f)
        val tints = leaves.tints.map { it and 0xFFFFFF }.toSet()
        assertThat(placed).isNotEmpty()
        for (s in placed) {
            assertThat(s.tint).isIn(tints)
            assertThat(s.angle).isIn(com.google.common.collect.Range.closedOpen(0f, 180f))
        }
        assertWithMessage("several colours in use").that(placed.map { it.tint }.toSet().size).isGreaterThan(1)
        for (s in LookStars.place(field, 1080, 760, 2.625f)) {
            assertThat(s.tint).isEqualTo(0)
            assertThat(s.angle).isEqualTo(0f)
        }
        val snow = KeyboardLooks.snowLook.dark.backdrop!!.stars!!
        assertWithMessage("snow falls").that(snow.driftYDpPerS).isGreaterThan(0f)
        // Maintainer 2026-11-10: "a rain effect instead of 'glistening'" over the river rocks,
        // then: "splattering on the rocks from above", not streaks crossing the screen.
        val rain = KeyboardLooks.riverRocksLook.dark.backdrop!!.stars!!
        assertThat(rain.shape).isEqualTo(ParticleShape.RAIN)
        assertWithMessage("splashes land where they fall; nothing slides across the view")
            .that(rain.driftXDpPerS == 0f && rain.driftYDpPerS == 0f).isTrue()
    }

    /**
     * Rain seen from above ([RainSplashes]): a drop strikes, its wet spot fades over the lifetime
     * while its age runs from 0, the slot rests, then splashes again somewhere else; every
     * splash lies in the view, and the same moment is always the same picture.
     */
    @Test
    fun rainSplashesFadeAndLandSomewhereNew() {
        val rain = KeyboardLooks.riverRocksLook.dark.backdrop!!.stars!!
        val w = 1008; val h = 910; val d = 2.625f
        val slots = RainSplashes.slots(rain, w, h, d)
        assertThat(slots).isGreaterThan(20)
        val buf = FloatArray(RainSplashes.STRIDE * slots)
        val again = FloatArray(buf.size)
        val seen = HashSet<Pair<Int, Int>>()
        var t = 0.0
        while (t < 30.0) {
            val n = RainSplashes.at(rain, w, h, d, t, buf)
            assertWithMessage("deterministic").that(RainSplashes.at(rain, w, h, d, t, again)).isEqualTo(n)
            assertThat(again.copyOf(n * RainSplashes.STRIDE)).isEqualTo(buf.copyOf(n * RainSplashes.STRIDE))
            assertWithMessage("some rain is always landing, never all of it at once").that(n).isIn(com.google.common.collect.Range.closed(1, slots * 4 / 5))
            for (j in 0 until n) {
                val o = j * RainSplashes.STRIDE
                assertThat(buf[o]).isIn(com.google.common.collect.Range.closedOpen(0f, w.toFloat()))
                assertThat(buf[o + 1]).isIn(com.google.common.collect.Range.closedOpen(0f, h.toFloat()))
                assertThat(buf[o + 2]).isIn(com.google.common.collect.Range.closed(rain.minRadiusDp * d - 1e-3f, rain.maxRadiusDp * d + 1e-3f))
                assertThat(buf[o + 3]).isIn(com.google.common.collect.Range.closed(0f, 1f))
                assertThat(buf[o + 6].toDouble()).isIn(com.google.common.collect.Range.closedOpen(0.0, RainSplashes.LIFETIME_S))
                seen += buf[o].toInt() / 50 to buf[o + 1].toInt() / 50
            }
            t += 0.1
        }
        assertWithMessage("splashes land all over, not in a few fixed spots").that(seen.size).isGreaterThan(150)

        // One splash over its life: its age only grows and its wet spot only fades.
        val one = rain.copy(perSquare100Dp = 0.5f)
        var lastAge = -1f; var lastA = 2f; var steps = 0
        var s = 0.0
        while (steps < 10 && s < 60.0) {
            val n = RainSplashes.at(one, w, h, d, s, buf)
            if (n > 0) {
                val age = buf[6]; val a = buf[3]
                if (lastAge >= 0f && age > lastAge) { assertThat(a).isAtMost(lastA); steps++ }
                lastAge = age; lastA = a
            } else { lastAge = -1f; lastA = 2f }
            s += 0.1
        }
        assertWithMessage("a splash was followed through its life").that(steps).isAtLeast(5)
        assertWithMessage("the flash, the beads and the ripples are the start of a splash").that(RainSplashes.FLASH_S).isLessThan(RainSplashes.SPLATTER_S)
        assertThat(RainSplashes.SPLATTER_S).isLessThan(RainSplashes.RIPPLE_S)
        assertThat(RainSplashes.RIPPLE_S).isLessThan(RainSplashes.LIFETIME_S)
    }

    /**
     * A curtain whose colour changes along it (green into lilac, indigo into teal) and whose rays
     * lean towards one radiant overhead (maintainer 2026-11-10: "more in-sync like the real aurora
     * borealis"): the start of the curtain wears its start colour, the end its end colour, and a
     * ray's light moves towards the radiant as it rises.
     */
    @Test
    fun aCurtainChangesColourAlongItAndItsRaysConverge() {
        val base = 0xFF000000.toInt()
        val w = 300
        val h = 200
        val curtain = AuroraRibbon(
            edgeY = 0.9f, amplitude = 0f, waves = 1f, phase = 0f, slope = 0f, height = 0.8f,
            edgeColor = 0xFF00FF00.toInt(), topColor = 0xFF00FF00.toInt(), raysPer100Dp = 0f, rayContrast = 0f, seed = 1L,
            edgeColorEnd = 0xFFFF0000.toInt(), topColorEnd = 0xFFFF0000.toInt(),
        )
        val px = AuroraRaster.render(base, listOf(curtain), w, h, 1f)
        val row = (0.85f * h).toInt() * w
        fun red(c: Int) = (c shr 16) and 0xFF
        fun green(c: Int) = (c shr 8) and 0xFF
        assertWithMessage("green at the start").that(green(px[row + w / 10])).isGreaterThan(red(px[row + w / 10]))
        assertWithMessage("red at the end").that(red(px[row + w * 9 / 10])).isGreaterThan(green(px[row + w * 9 / 10]))

        // One bright ray at the left, rays everywhere else dark: its light moves right as it rises.
        val ray = curtain.copy(edgeColorEnd = null, topColorEnd = null, rayContrast = 1f, convergence = 0.6f, radiantX = 0.9f)
        fun brightestColumn(pixels: IntArray, y: Int): Int = (0 until w).maxBy { x -> green(pixels[y * w + x]) }
        val single = FloatArray(w) { x -> if (x in 40..44) 1f else 0f }
        val lean = AuroraRaster.paintRays(base, ray, single, w, h)
        val straight = AuroraRaster.paintRays(base, ray.copy(convergence = 0f), single, w, h)
        val low = (0.88f * h).toInt()
        val high = (0.4f * h).toInt()
        assertWithMessage("a straight ray stays in its column").that(brightestColumn(straight, high)).isIn(com.google.common.collect.Range.closed(40, 44))
        assertWithMessage("at its foot the leaning ray is where it rises").that(brightestColumn(lean, low)).isIn(com.google.common.collect.Range.closed(40, 46))
        assertWithMessage("higher up it leans towards the radiant").that(brightestColumn(lean, high)).isGreaterThan(60)
    }

    /**
     * Rain varies (maintainer 2026-11-11: "way too aggressive … should vary in drop size,
     * intensity and splash pattern"): showers come and go, most drops are small, and a drop
     * flings its beads lopsidedly, small drops few or none.
     */
    @Test
    fun rainComesInShowersOfMostlySmallDropsSplashingLopsidedly() {
        var lo = 1f; var hi = 0f
        var t = 0.0
        while (t < 600.0) {
            val s = RainSplashes.shower(t)
            assertThat(s).isIn(com.google.common.collect.Range.closed(RainSplashes.SHOWER_MIN, 1f))
            lo = minOf(lo, s); hi = maxOf(hi, s)
            t += 0.5
        }
        assertWithMessage("drizzle to downpour").that(hi - lo).isGreaterThan(0.6f)

        val rain = KeyboardLooks.riverRocksLook.dark.backdrop!!.stars!!
        val w = 1008; val h = 910; val d = 2.625f
        val buf = FloatArray(RainSplashes.STRIDE * RainSplashes.slots(rain, w, h, d))
        var small = 0; var total = 0
        val perSecond = ArrayList<Int>()
        val seenSeeds = HashSet<Int>()
        t = 0.0
        while (t < 300.0) {
            val n = RainSplashes.at(rain, w, h, d, t, buf)
            var fresh = 0
            for (j in 0 until n) {
                val o = j * RainSplashes.STRIDE
                if (buf[o + 6] < 1f && seenSeeds.add(buf[o + 5].toInt() xor (buf[o].toInt() shl 12))) fresh++
                total++
                if (buf[o + 7] < 0.25f) small++
            }
            perSecond += fresh
            t += 1.0
        }
        assertWithMessage("most drops are small").that(small.toDouble() / total).isGreaterThan(0.55)
        val mean = perSecond.average()
        assertWithMessage("a few drops a second on a phone keyboard, not dozens").that(mean).isIn(com.google.common.collect.Range.closed(2.0, 10.0))
        assertWithMessage("the rate swings with the showers").that(perSecond.max() - perSecond.min()).isAtLeast(6)

        val out = FloatArray(RainSplashes.BEAD_STRIDE * RainSplashes.MAX_BEADS)
        var lopsided = 0; var big = 0
        for (seed in 0 until 400) {
            assertWithMessage("a small drop flings few beads").that(RainSplashes.splatter(seed, 0f, out)).isAtMost(2)
            val k = RainSplashes.splatter(seed, 1f, out)
            assertThat(k).isAtMost(RainSplashes.MAX_BEADS)
            if (k < 3) continue
            big++
            var sx = 0f; var sy = 0f
            for (b in 0 until k) {
                val o = b * RainSplashes.BEAD_STRIDE
                assertThat(out[o] * out[o] + out[o + 1] * out[o + 1]).isWithin(1e-4f).of(1f)
                assertThat(out[o + 2]).isIn(com.google.common.collect.Range.closed(0.5f, 2.2f))
                sx += out[o]; sy += out[o + 1]
            }
            // The mean heading of an even ring is zero; a lopsided fan points somewhere.
            if (kotlin.math.hypot(sx / k, sy / k) > 0.4f) lopsided++
        }
        assertThat(big).isGreaterThan(100)
        assertWithMessage("a big drop's beads leave mostly one way").that(lopsided.toDouble() / big).isGreaterThan(0.8)
    }
}

