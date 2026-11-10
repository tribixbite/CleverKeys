package tribixbite.cleverkeys.theme

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The painted nature scenes behind the keys of the scene looks ([LookBackdrop.scene]): meadow,
 * stream, river rocks, forest, snow and beach (maintainer 2026-11-10). Each is painted once per
 * keyboard size, at half resolution like the aurora's curtains, into opaque ARGB pixels.
 *
 * [frame] 0 is the still picture; frame 1 is the same scene a moment later (blades bent the
 * other way, ripples and foam moved on, leaves shifted). A moving backdrop ([AuroraMotion])
 * cross-fades between the two and drifts them, so nothing is painted while it moves.
 *
 * Pure JVM and deterministic for the same size: `SceneRasterTest` pins it and measures the
 * legibility of each scene look's keys over the painting.
 */
object SceneRaster {

    /** The opaque pixels ([width] × [height], row-major) of [kind]'s [frame] (0 or 1). */
    fun render(kind: SceneKind, width: Int, height: Int, density: Float, frame: Int): IntArray {
        if (width <= 0 || height <= 0) return IntArray(0)
        val p = Paint2D(width, height)
        val d = density.coerceAtLeast(0.25f)
        val seed = kind.ordinal * 7919L + width * 31L + height
        when (kind) {
            SceneKind.MEADOW -> meadow(p, d, frame, seed)
            SceneKind.STREAM -> stream(p, d, frame, seed)
            SceneKind.RIVER_ROCKS -> riverRocks(p, d, frame, seed)
            SceneKind.FOREST -> forest(p, d, frame, seed)
            SceneKind.SNOW -> snow(p, d, frame, seed)
            SceneKind.BEACH -> beach(p, d, frame, seed)
        }
        return p.toArgb()
    }

    // ---- Meadow: dusk sky over a field of grass blades and a few flowers ----
    private fun meadow(p: Paint2D, d: Float, frame: Int, seed: Long) {
        p.verticalGradient(0f to 0xFF1B2A3A.toInt(), 0.35f to 0xFF3A4A3C.toInt(), 0.55f to 0xFF233A22.toInt(), 1f to 0xFF0E1F0D.toInt())
        // Low rolling hills behind the grass.
        p.ridge(0.42f, 0.05f, 1.3f, seed, 0xFF1C3320.toInt())
        val rng = Random(seed)
        val bladeCount = (p.w * p.h / (d * d) / 18f).toInt().coerceIn(200, 4000)
        // Wind travels across the field: each blade leans by its own phase of one gust wave.
        val gust = if (frame == 0) 0.0 else PI / 2
        repeat(bladeCount) {
            val x = rng.nextFloat() * p.w
            val baseY = p.h * (0.48f + 0.55f * rng.nextFloat())
            val len = (14f + 26f * rng.nextFloat()) * d * (0.5f + baseY / p.h)
            val lean = (rng.nextFloat() - 0.5f) * 0.5f
            val sway = (1.6f * d * sin(2 * PI * x / p.w * 1.7 + gust)).toFloat()
            val shade = rng.nextFloat()
            val color = lerpColor(0xFF2E5A24.toInt(), 0xFF7FAE4A.toInt(), shade * (baseY / p.h))
            p.blade(x, baseY, len, lean * len + sway * (len / (30f * d)), (1.1f + 0.9f * rng.nextFloat()) * d, color, 0.9f)
        }
        // Flowers, sparse, sitting on the grass.
        repeat(bladeCount / 40) {
            val x = rng.nextFloat() * p.w
            val y = p.h * (0.55f + 0.42f * rng.nextFloat())
            val c = listOf(0xFFE8D36A.toInt(), 0xFFE0E4F0.toInt(), 0xFFC98BD8.toInt())[rng.nextInt(3)]
            p.disc(x + (if (frame == 0) 0f else 0.8f * d), y, 1.6f * d, c, 0.85f)
        }
        p.multiply(0.8f)
    }

    // ---- Stream: dark water with flowing ripples and bright ripple crests ----
    private fun stream(p: Paint2D, d: Float, frame: Int, seed: Long) {
        p.verticalGradient(0f to 0xFF0B2230.toInt(), 0.5f to 0xFF0E3340.toInt(), 1f to 0xFF07202A.toInt())
        // The flow runs left to right: light catches long, wavering streaks along it, broken up
        // by the current. Frame 1 is the same water a moment downstream.
        val shift = if (frame == 0) 0f else 0.6f
        val band = 9f * d
        for (y in 0 until p.h) for (x in 0 until p.w) {
            val u = x / (90f * d) - shift
            val v = y / band
            val warp = 2.2f * Noise.fbm(u * 0.7f, v * 0.15f, 3, seed)
            val crest = sin(2 * PI * (v + warp)).toFloat()
            val broken = Noise.fbm(u * 3f, v * 0.5f, 3, seed + 5)
            val c3 = max(0f, crest).let { it * it * it * it }
            val light = c3 * ((broken - 0.35f) * 2.2f).coerceIn(0f, 1f)
            p.addAt(x, y, 0xFF8FD2E0.toInt(), 0.42f * light)
        }
        // A dark bank at the top so the bar meets calm water.
        p.verticalShade(0f, 0.12f, 0xFF061820.toInt(), 0.6f)
    }

    // ---- River rocks: rounded wet pebbles, shaded from the top left, with specular sheen ----
    private fun riverRocks(p: Paint2D, d: Float, frame: Int, seed: Long) {
        p.fill(0xFF101416.toInt())
        val rng = Random(seed)
        val palette = intArrayOf(0xFF4A4F55.toInt(), 0xFF5C554C.toInt(), 0xFF3E4A52.toInt(), 0xFF6A6560.toInt(), 0xFF4F463E.toInt(), 0xFF585E62.toInt())
        // Stones placed big-first without overlapping (a little gap of wet dark between them).
        val stones = ArrayList<Stone>()
        val tries = (p.w * p.h / (d * d) / 6f).toInt().coerceIn(200, 20000)
        repeat(tries) {
            val t = it.toFloat() / tries
            val r = (26f - 18f * t) * d * (0.8f + 0.4f * rng.nextFloat())
            val ry = r * (0.62f + 0.3f * rng.nextFloat())
            val x = rng.nextFloat() * p.w; val y = rng.nextFloat() * p.h
            val gap = 1.5f * d
            if (stones.none { o -> (o.x - x) * (o.x - x) + (o.y - y) * (o.y - y) < (o.ry + ry + gap) * (o.ry + ry + gap) }) {
                stones += Stone(x, y, r, ry, (rng.nextFloat() * PI).toFloat(), palette[rng.nextInt(palette.size)], rng.nextFloat())
            }
        }
        val sheenShift = if (frame == 0) 0f else 0.12f
        for (s in stones) p.pebble(s, sheenShift)
    }

    // ---- Forest: twilight trunks under a leafy canopy, moss on the floor ----
    private fun forest(p: Paint2D, d: Float, frame: Int, seed: Long) {
        p.verticalGradient(0f to 0xFF1A2618.toInt(), 0.45f to 0xFF223424.toInt(), 1f to 0xFF0D150C.toInt())
        val rng = Random(seed)
        // Far trunks pale, near trunks dark and wide.
        for (layer in 0..2) {
            val n = (p.w / d / (60f - layer * 12f)).toInt().coerceAtLeast(3)
            val color = intArrayOf(0xFF33402F.toInt(), 0xFF262F22.toInt(), 0xFF171D14.toInt())[layer]
            repeat(n) {
                val x = rng.nextFloat() * p.w
                val wTop = (3f + 3f * layer + 3f * rng.nextFloat()) * d
                p.trunk(x, wTop, wTop * 1.35f, color, (p.h * (0.8f + 0.05f * layer + 0.04f * rng.nextFloat())).toInt())
            }
        }
        // Canopy: clusters of leaves across the top third, swaying a little in frame 1.
        val sway = if (frame == 0) 0f else 1.3f * d
        val leaves = (p.w * p.h / (d * d) / 30f).toInt().coerceIn(150, 3000)
        repeat(leaves) {
            val x = rng.nextFloat() * p.w
            val y = p.h * (0.42f * rng.nextFloat() * rng.nextFloat())
            val c = lerpColor(0xFF2F5A2A.toInt(), 0xFF8FB84E.toInt(), rng.nextFloat() * (1f - y / (p.h * 0.42f)))
            p.ellipse(x + sway * (1f - y / (p.h * 0.42f)), y, (2.6f + 2f * rng.nextFloat()) * d, (1.4f + rng.nextFloat()) * d,
                (rng.nextFloat() * PI).toFloat(), c, 0.75f)
        }
        // The forest floor, then moss and ferns on it.
        p.ridge(0.84f, 0.02f, 2.2f, seed + 3, 0xFF1A2416.toInt())
        repeat(leaves / 3) {
            val x = rng.nextFloat() * p.w
            val y = p.h * (0.82f + 0.18f * rng.nextFloat())
            p.ellipse(x, y, (3f + 3f * rng.nextFloat()) * d, 1.4f * d, (rng.nextFloat() * 0.6f - 0.3f), 0xFF3D5E2E.toInt(), 0.55f)
        }
    }

    // ---- Snow: a blue night over snowy hills with a few pines; the snowfall is particles ----
    private fun snow(p: Paint2D, d: Float, frame: Int, seed: Long) {
        p.verticalGradient(0f to 0xFF141C2C.toInt(), 0.5f to 0xFF27344C.toInt(), 1f to 0xFF3A4660.toInt())
        val rng = Random(seed)
        // Pines on the far ridge.
        val pines = (p.w / d / 28f).toInt().coerceAtLeast(4)
        repeat(pines) {
            val x = rng.nextFloat() * p.w
            val base = p.h * (0.62f + 0.08f * rng.nextFloat())
            val hgt = (30f + 40f * rng.nextFloat()) * d
            p.pine(x, base, hgt, 0xFF1A2434.toInt())
        }
        // Two snowy hills, lit faintly blue; the glitter on their crust moves between frames.
        p.ridge(0.66f, 0.06f, 0.9f, seed + 1, 0xFF8A9CB8.toInt())
        p.ridge(0.80f, 0.05f, 1.6f, seed + 2, 0xFFB4C2D8.toInt())
        val glitter = Random(seed + 10 + frame)
        repeat((p.w * p.h / (d * d) / 90f).toInt().coerceIn(40, 2000)) {
            val x = glitter.nextFloat() * p.w
            val y = p.h * (0.7f + 0.3f * glitter.nextFloat())
            p.disc(x, y, 0.6f * d, 0xFFFFFFFF.toInt(), 0.5f)
        }
    }

    // ---- Beach: sea at the top, a foam line washing over wet sand, dry sand below ----
    private fun beach(p: Paint2D, d: Float, frame: Int, seed: Long) {
        val reach = if (frame == 0) 0f else 0.035f // the wash runs further up the sand in frame 1
        for (y in 0 until p.h) for (x in 0 until p.w) {
            val u = x.toFloat() / p.w
            val fy = y.toFloat() / p.h
            val shore = 0.34f + reach + 0.03f * sin(2 * PI * (u * 1.4f + 0.2f)).toFloat() +
                0.02f * Noise.fbm(u * 6f, 0.5f, 3, seed)
            val color = when {
                fy < shore - 0.012f -> {
                    // Sea: deep blue to turquoise near the shore, with soft swell bands.
                    val t = fy / shore
                    val swell = 0.06f * sin(2 * PI * (fy * 9f + u * 0.8f + reach * 4f)).toFloat()
                    lerpColor(0xFF0A2A44.toInt(), 0xFF1E7A86.toInt(), (t + swell).coerceIn(0f, 1f))
                }
                fy < shore + 0.02f -> 0xFFE6EFEE.toInt() // foam line
                fy < shore + 0.16f -> {
                    // Wet sand: darker, with a sheen that fades down the beach.
                    val t = (fy - shore - 0.02f) / 0.14f
                    lerpColor(0xFF7A6A52.toInt(), 0xFFA38C68.toInt(), t)
                }
                else -> 0xFFB79E76.toInt()
            }
            p.setAt(x, y, color)
            // Sand grain, wet sheen and the bubbles of the foam.
            val grain = Noise.value(x * 0.9f / d, y * 0.9f / d, seed + 3) - 0.5f
            if (fy > shore) p.addAt(x, y, 0xFFFFFFFF.toInt(), 0.06f * grain)
            if (fy in shore - 0.012f..shore + 0.02f) p.addAt(x, y, 0xFF5A8A90.toInt(), -0.3f * Noise.value(x / (1.5f * d), y / (1.5f * d), seed + 4))
        }
        // Dim the scene: keys stay legible over even the brightest sand.
        p.multiply(0.62f)
    }

    // ---- toolkit ----

    private class Stone(val x: Float, val y: Float, val rx: Float, val ry: Float, val angle: Float, val color: Int, val sheen: Float)

    /** A float RGB canvas with the few anti-aliased primitives the scenes need. */
    private class Paint2D(val w: Int, val h: Int) {
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)

        fun fill(c: Int) {
            r.fill(ch(c, 16)); g.fill(ch(c, 8)); b.fill(ch(c, 0))
        }

        fun setAt(x: Int, y: Int, c: Int) {
            val i = y * w + x
            r[i] = ch(c, 16); g[i] = ch(c, 8); b[i] = ch(c, 0)
        }

        /** Adds (or with negative [a], subtracts) light of colour [c]. */
        fun addAt(x: Int, y: Int, c: Int, a: Float) {
            if (a == 0f) return
            val i = y * w + x
            r[i] += ch(c, 16) * a; g[i] += ch(c, 8) * a; b[i] += ch(c, 0) * a
        }

        fun over(i: Int, c: Int, a: Float) {
            if (a <= 0f) return
            val k = a.coerceAtMost(1f)
            r[i] += (ch(c, 16) - r[i]) * k; g[i] += (ch(c, 8) - g[i]) * k; b[i] += (ch(c, 0) - b[i]) * k
        }

        fun multiply(f: Float) {
            for (i in r.indices) { r[i] *= f; g[i] *= f; b[i] *= f }
        }

        fun verticalGradient(vararg stops: Pair<Float, Int>) {
            for (y in 0 until h) {
                val c = gradientAt(stops, (y + 0.5f) / h)
                for (x in 0 until w) setAt(x, y, c)
            }
        }

        /** Darkens towards [c] from [from] to [to] (fractions of the height), strongest at [from]. */
        fun verticalShade(from: Float, to: Float, c: Int, strength: Float) {
            val y0 = (from * h).toInt().coerceIn(0, h); val y1 = (to * h).toInt().coerceIn(0, h)
            for (y in y0 until y1) {
                val a = strength * (1f - (y - y0).toFloat() / max(1, y1 - y0))
                for (x in 0 until w) over(y * w + x, c, a)
            }
        }

        /** A filled landform whose top edge waves around [level] (fraction of the height). */
        fun ridge(level: Float, amp: Float, waves: Float, seed: Long, c: Int) {
            for (x in 0 until w) {
                val u = x.toFloat() / w
                val top = h * (level + amp * (sin(2 * PI * (u * waves + seed % 7 * 0.13)).toFloat() * 0.7f +
                    0.3f * (Noise.fbm(u * 4f, seed % 13 * 0.1f, 3, seed) - 0.5f) * 2f))
                val y0 = top.toInt().coerceIn(0, h)
                for (y in y0 until h) over(y * w + x, c, if (y == y0) 1f - (top - y0) else 1f)
            }
        }

        fun disc(cx: Float, cy: Float, rad: Float, c: Int, a: Float) {
            val x0 = floor(cx - rad - 1).toInt().coerceAtLeast(0); val x1 = (cx + rad + 1).toInt().coerceAtMost(w - 1)
            val y0 = floor(cy - rad - 1).toInt().coerceAtLeast(0); val y1 = (cy + rad + 1).toInt().coerceAtMost(h - 1)
            for (y in y0..y1) for (x in x0..x1) {
                val dist = sqrt((x + 0.5f - cx) * (x + 0.5f - cx) + (y + 0.5f - cy) * (y + 0.5f - cy))
                over(y * w + x, c, a * (rad + 0.5f - dist).coerceIn(0f, 1f))
            }
        }

        fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, angle: Float, c: Int, a: Float) {
            val ext = max(rx, ry) + 1
            val ca = cos(angle); val sa = sin(angle)
            val x0 = floor(cx - ext).toInt().coerceAtLeast(0); val x1 = (cx + ext).toInt().coerceAtMost(w - 1)
            val y0 = floor(cy - ext).toInt().coerceAtLeast(0); val y1 = (cy + ext).toInt().coerceAtMost(h - 1)
            for (y in y0..y1) for (x in x0..x1) {
                val dx = x + 0.5f - cx; val dy = y + 0.5f - cy
                val u = (dx * ca + dy * sa) / rx; val v = (-dx * sa + dy * ca) / ry
                val q = sqrt(u * u + v * v)
                over(y * w + x, c, a * ((1f - q) * min(rx, ry) + 0.5f).coerceIn(0f, 1f))
            }
        }

        /** A grass blade: a tapering curve from its root up [len] px, its tip moved [bend] px sideways. */
        fun blade(x: Float, y: Float, len: Float, bend: Float, width: Float, c: Int, a: Float) {
            val steps = (len / 1.2f).toInt().coerceIn(4, 60)
            for (s in 0..steps) {
                val t = s.toFloat() / steps
                val px = x + bend * t * t
                val py = y - len * t
                disc(px, py, (width * (1f - t)).coerceAtLeast(0.35f), c, a * (0.75f + 0.25f * t))
            }
        }

        /** A tree trunk from the bottom to above the top, slightly flared at the root. */
        fun trunk(x: Float, wTop: Float, wBottom: Float, c: Int, bottom: Int = h) {
            for (y in 0 until bottom.coerceAtMost(h)) {
                val half = (wTop + (wBottom - wTop) * (y.toFloat() / h)) / 2
                val x0 = floor(x - half - 1).toInt().coerceAtLeast(0); val x1 = (x + half + 1).toInt().coerceAtMost(w - 1)
                for (xx in x0..x1) over(y * w + xx, c, (half + 0.5f - abs(xx + 0.5f - x)).coerceIn(0f, 1f))
            }
        }

        /** A pine silhouette: stacked triangles narrowing to the top. */
        fun pine(x: Float, base: Float, height: Float, c: Int) {
            val y0 = (base - height).toInt().coerceAtLeast(0); val y1 = base.toInt().coerceAtMost(h - 1)
            for (y in y0..y1) {
                val t = (y - (base - height)) / height
                val tier = (t * 4f) % 1f
                val half = height * 0.28f * t * (0.55f + 0.45f * tier)
                val xa = floor(x - half - 1).toInt().coerceAtLeast(0); val xb = (x + half + 1).toInt().coerceAtMost(w - 1)
                for (xx in xa..xb) over(y * w + xx, c, (half + 0.5f - abs(xx + 0.5f - x)).coerceIn(0f, 1f))
            }
        }

        /** A rounded stone lit from the upper left, with a wet specular sheen ([sheenShift] moves it). */
        fun pebble(s: Stone, sheenShift: Float) {
            val ext = max(s.rx, s.ry) + 1
            val ca = cos(s.angle); val sa = sin(s.angle)
            val x0 = floor(s.x - ext).toInt().coerceAtLeast(0); val x1 = (s.x + ext).toInt().coerceAtMost(w - 1)
            val y0 = floor(s.y - ext).toInt().coerceAtLeast(0); val y1 = (s.y + ext).toInt().coerceAtMost(h - 1)
            val lx = -0.55f + sheenShift; val ly = -0.65f
            for (y in y0..y1) for (x in x0..x1) {
                val dx = x + 0.5f - s.x; val dy = y + 0.5f - s.y
                val u = (dx * ca + dy * sa) / s.rx; val v = (-dx * sa + dy * ca) / s.ry
                val q2 = u * u + v * v
                if (q2 > 1.08f) continue
                val edge = ((1f - sqrt(q2)) * min(s.rx, s.ry) + 0.5f).coerceIn(0f, 1f)
                // Normal of an ellipsoid dome, back in screen space.
                val nz = sqrt((1f - q2).coerceAtLeast(0f))
                val nxs = u * ca - v * sa; val nys = u * sa + v * ca
                val diffuse = (0.45f + 0.55f * (-(nxs * lx + nys * ly) * 0.7f + nz * 0.6f)).coerceIn(0.25f, 1.1f)
                val spec = exp(-((nxs - lx * 0.6f) * (nxs - lx * 0.6f) + (nys - ly * 0.6f) * (nys - ly * 0.6f)) * 14f) * (0.25f + 0.5f * s.sheen)
                val i = y * w + x
                val cr = ch(s.color, 16) * diffuse + 255f * spec
                val cg = ch(s.color, 8) * diffuse + 255f * spec
                val cb = ch(s.color, 0) * diffuse + 255f * spec
                // A dark rim where the stone meets its neighbours.
                val rim = (0.55f + 0.45f * nz)
                r[i] += (cr * rim - r[i]) * edge; g[i] += (cg * rim - g[i]) * edge; b[i] += (cb * rim - b[i]) * edge
            }
        }

        fun toArgb(): IntArray = IntArray(w * h) { i ->
            val cr = r[i].coerceIn(0f, 255f).toInt(); val cg = g[i].coerceIn(0f, 255f).toInt(); val cb = b[i].coerceIn(0f, 255f).toInt()
            (0xFF shl 24) or (cr shl 16) or (cg shl 8) or cb
        }
    }

    private fun ch(c: Int, shift: Int): Float = ((c shr shift) and 0xFF).toFloat()

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun mix(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * k).toInt()
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun gradientAt(stops: Array<out Pair<Float, Int>>, t: Float): Int {
        if (t <= stops.first().first) return stops.first().second
        for (i in 1 until stops.size) {
            val (t1, c1) = stops[i]
            if (t <= t1) {
                val (t0, c0) = stops[i - 1]
                return lerpColor(c0, c1, (t - t0) / max(1e-6f, t1 - t0))
            }
        }
        return stops.last().second
    }

    /** Seeded value noise and its fractal sum, in 0..1. */
    private object Noise {
        private fun hash(x: Int, y: Int, seed: Long): Float {
            var h = x * 374761393 + y * 668265263 + (seed xor (seed ushr 32)).toInt() * 1442695041
            h = (h xor (h ushr 13)) * 1274126177
            return ((h xor (h ushr 16)) and 0xFFFFFF) / 16777215f
        }

        fun value(x: Float, y: Float, seed: Long): Float {
            val xi = floor(x).toInt(); val yi = floor(y).toInt()
            val fx = x - xi; val fy = y - yi
            val sx = fx * fx * (3 - 2 * fx); val sy = fy * fy * (3 - 2 * fy)
            val a = hash(xi, yi, seed); val b = hash(xi + 1, yi, seed)
            val c = hash(xi, yi + 1, seed); val e = hash(xi + 1, yi + 1, seed)
            return (a + (b - a) * sx) + ((c + (e - c) * sx) - (a + (b - a) * sx)) * sy
        }

        fun fbm(x: Float, y: Float, octaves: Int, seed: Long): Float {
            var sum = 0f; var amp = 0.5f; var f = 1f; var norm = 0f
            repeat(octaves) { o ->
                sum += amp * value(x * f, y * f, seed + o * 101); norm += amp; amp *= 0.5f; f *= 2f
            }
            return sum / norm
        }
    }
}

/** The nature scenes [SceneRaster] paints. */
enum class SceneKind { MEADOW, STREAM, RIVER_ROCKS, FOREST, SNOW, BEACH }
