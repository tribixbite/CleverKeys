package tribixbite.cleverkeys.theme

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The painted nature scenes behind the keys of the scene looks ([LookBackdrop.scene]): meadow,
 * stream, river rocks, forest, snow and beach (maintainer 2026-11-10). Each is painted once per
 * keyboard size into a [ScenePainting]: a still base, and either a second image to cross-fade
 * (the stream's water, the snow's glitter) or the parts that move on their own ([SceneLayer]:
 * grass bands that bend in a travelling breeze, branches and ferns that sway, waves that roll in
 * and wash up the sand), which `BackdropDrawable` warps per frame through [SceneMotion] instead of
 * painting anything again. The river rocks are still: the rain over them is the look's particles.
 *
 * The detailed scenes paint at full resolution ([SceneKind.paintScale] 1: blades, leaves and
 * stone banding stay sharp); the soft ones at half. Pure JVM and deterministic for the same size:
 * `SceneRasterTest` pins it, `KeyboardLooksTest` measures the keys' legibility over it.
 */
object SceneRaster {

    /**
     * [kind] painted for a raster of [width] × [height] px at [density] raster px per dp (the
     * screen's density divided by [SceneKind.paintScale]).
     */
    fun paint(kind: SceneKind, width: Int, height: Int, density: Float): ScenePainting {
        if (width <= 0 || height <= 0) return ScenePainting(max(0, width), max(0, height), IntArray(0), null, emptyList())
        val d = density.coerceAtLeast(0.25f)
        val seed = kind.ordinal * 7919L + width * 31L + height
        return when (kind) {
            SceneKind.MEADOW -> meadow(width, height, d, seed)
            SceneKind.STREAM -> ScenePainting(width, height, stream(width, height, d, 0, seed), stream(width, height, d, 1, seed), emptyList())
            SceneKind.RIVER_ROCKS -> riverRocks(width, height, d, seed)
            SceneKind.FOREST -> forest(width, height, d, seed)
            SceneKind.SNOW -> ScenePainting(width, height, snow(width, height, d, 0, seed), snow(width, height, d, 1, seed), emptyList())
            SceneKind.BEACH -> beach(width, height, d, seed)
        }
    }

    // ---- Meadow: dusk over a field of grass that bends in a travelling breeze, with flowers ----

    private fun meadow(w: Int, h: Int, d: Float, seed: Long): ScenePainting {
        val p = Paint2D(w, h, opaque = true)
        p.verticalGradient(0f to 0xFF121A2C.toInt(), 0.22f to 0xFF252E47.toInt(), 0.40f to 0xFF55504E.toInt(), 0.52f to 0xFF4C4C3A.toInt())
        // A low sun behind the hills.
        p.glow(0.78f * w, 0.47f * h, 0.42f * w, 0xFFF2B26E.toInt(), 0.24f)
        p.ridge(0.43f, 0.03f, 1.1f, seed, 0xFF2B3A37.toInt())
        p.ridge(0.475f, 0.025f, 2.3f, seed + 1, 0xFF22382B.toInt())
        p.ridge(0.515f, 0.012f, 3.1f, seed + 2, 0xFF1C361B.toInt())
        p.shadeDown(0.52f, 1f, 0xFF081407.toInt(), 0.75f)
        val rng = Random(seed)
        // Far grass: short, dim and still, a texture under the moving bands.
        repeat(((w / d) * 2.2f).toInt().coerceIn(150, 2400)) {
            val x = rng.nextFloat() * w
            val y = h * (0.52f + 0.07f * rng.nextFloat())
            val len = (2.5f + 4f * rng.nextFloat()) * d
            blade(p, x, y, len, 0.45f * d, (rng.nextFloat() - 0.4f) * 0.5f, 0.2f, 0xFF16301A.toInt(), 0xFF3E5A34.toInt(), 0.9f)
        }

        // The field in five depth bands, far to near, each bending as one layer.
        val edges = floatArrayOf(0.56f, 0.62f, 0.69f, 0.78f, 0.89f, 1.04f)
        val layers = ArrayList<SceneLayer>()
        for (k in 0 until edges.size - 1) {
            val f = (k + 1f) / (edges.size - 1)
            val amp = (2.5f + 9f * f) * d
            val maxLen = (8f + 26f * f + 6f + 14f * f) * d
            val margin = (amp + 6 * d).toInt()
            val rootTop = edges[k] * h
            val rootBottom = edges[k + 1] * h
            val top = (rootTop - maxLen * 1.05f).toInt().coerceAtLeast(0)
            val bottom = min(h, rootBottom.toInt() + 2)
            if (bottom <= top) continue
            val lw = w + 2 * margin
            val lh = bottom - top
            val lp = Paint2D(lw, lh, opaque = false)
            val spacing = (1.9f + 2.6f * f) * d
            val count = ((w + 2f * margin) * (rootBottom - rootTop) / (spacing * spacing)).toInt().coerceIn(80, 2200)
            val roots = Array(count) { floatArrayOf(rng.nextFloat() * lw, rootTop + rng.nextFloat() * (rootBottom - rootTop) - top) }
            roots.sortBy { it[1] }
            val haze = (1f - f).pow(1.5f) * 0.45f
            val light = 0.6f + 0.4f * f
            for ((i, root) in roots.withIndex()) {
                val len = (8f + 26f * f + rng.nextFloat() * (6f + 14f * f)) * d
                val width = (0.45f + 1.05f * f) * d * (0.75f + 0.5f * rng.nextFloat())
                val rootC = lerpColor(0xFF0E220F.toInt(), 0xFF1C3A17.toInt(), rng.nextFloat())
                val pick = rng.nextFloat()
                var tip = when {
                    pick < 0.72f -> lerpColor(0xFF5A8C31.toInt(), 0xFF9CC456.toInt(), rng.nextFloat())
                    pick < 0.9f -> lerpColor(0xFF9AB04E.toInt(), 0xFFC2CB66.toInt(), rng.nextFloat())
                    else -> lerpColor(0xFFB49C5E.toInt(), 0xFFD6C38A.toInt(), rng.nextFloat())
                }
                tip = scale(lerpColor(tip, 0xFF46524A.toInt(), haze), light)
                val lean = (rng.nextFloat() - 0.5f) * 0.55f
                val curl = (rng.nextFloat() - 0.5f) * 0.9f + 0.22f
                blade(lp, root[0], root[1], len, width, lean, curl, scale(rootC, light), tip, 1f)
                if (f > 0.3f && rng.nextFloat() < 0.07f) seedHead(lp, root[0], root[1], len, lean, curl, f * d, scale(0xFFC9B97A.toInt(), light))
                if (f >= 0.5f && i % 26 == 0) flower(lp, rng, root[0], root[1], len, f, d, light)
            }
            layers += SceneLayer(
                left = -margin, top = top, width = lw, height = lh, pixels = lp.toArgb(),
                motion = LayerMotion(
                    LayerMotionKind.SWAY_UP, anchorY = rootBottom, reach = maxLen, amplitude = amp, bias = 0.3f,
                    periodS = 4.6f, phase = -0.05f * k, wavelength = 0.85f * w,
                ),
            )
        }
        return ScenePainting(w, h, p.toArgb(), null, layers)
    }

    /** A grass blade rooted at ([x], [y]): [len] tall, [lean] radians off vertical, curling by [curl]. */
    private fun blade(p: Paint2D, x: Float, y: Float, len: Float, width: Float, lean: Float, curl: Float, root: Int, tip: Int, alpha: Float) {
        val n = 5
        val xs = FloatArray(n); val ys = FloatArray(n); val rs = FloatArray(n)
        val sa = sin(lean); val ca = cos(lean)
        for (s in 0 until n) {
            val t = s.toFloat() / (n - 1)
            xs[s] = x + len * (sa * t + 0.5f * curl * t * t)
            ys[s] = y - len * ca * t * (1f - 0.08f * curl * curl * t)
            rs[s] = width * (1f - t).pow(0.9f) + 0.22f
        }
        p.stroke(xs, ys, rs, root, tip, alpha, sideLight = 0.22f)
    }

    /** Oat-like grains along the top of a blade. */
    private fun seedHead(p: Paint2D, x: Float, y: Float, len: Float, lean: Float, curl: Float, size: Float, c: Int) {
        val sa = sin(lean); val ca = cos(lean)
        for (g in 0 until 6) {
            val t = 0.74f + 0.05f * g
            val gx = x + len * (sa * t + 0.5f * curl * t * t)
            val gy = y - len * ca * t
            val side = if (g % 2 == 0) 1f else -1f
            p.ellipse(gx + side * 0.9f * size, gy, 1.5f * size, 0.6f * size, side * 0.9f + lean, c, 0.95f)
        }
    }

    /** A flower on a stem among the blades: daisy, buttercup, clover or cornflower. */
    private fun flower(p: Paint2D, rng: Random, x: Float, y: Float, grassLen: Float, f: Float, d: Float, light: Float) {
        val stem = grassLen * (0.5f + 0.35f * rng.nextFloat())
        val lean = (rng.nextFloat() - 0.5f) * 0.4f
        val hx = x + stem * sin(lean) + 0.12f * stem
        val hy = y - stem * cos(lean)
        p.stroke(floatArrayOf(x, x + 0.5f * stem * sin(lean) + 0.03f * stem, hx), floatArrayOf(y, y - 0.5f * stem, hy),
            floatArrayOf(0.55f * d * f, 0.45f * d * f, 0.35f * d * f), scale(0xFF1E4019.toInt(), light), scale(0xFF4C7C33.toInt(), light), 1f)
        val r = (2.2f + 1.6f * rng.nextFloat()) * d * f
        when (rng.nextInt(4)) {
            0 -> { // daisy
                for (k in 0 until 11) {
                    val a = (2 * PI * k / 11).toFloat()
                    p.ellipse(hx + cos(a) * r * 0.55f, hy + sin(a) * r * 0.38f, r * 0.55f, r * 0.17f, a, scale(lerpColor(0xFFF4F1E6.toInt(), 0xFFD8D4E4.toInt(), (sin(a) + 1) / 2), light), 1f)
                }
                p.disc(hx, hy, r * 0.3f, scale(0xFFE9B530.toInt(), light), 1f)
            }
            1 -> { // buttercup
                for (k in 0 until 5) {
                    val a = (2 * PI * k / 5 + 0.3).toFloat()
                    p.disc(hx + cos(a) * r * 0.42f, hy + sin(a) * r * 0.3f, r * 0.42f, scale(0xFFF2C62E.toInt(), light), 1f)
                }
                p.disc(hx, hy, r * 0.22f, scale(0xFFC98A1C.toInt(), light), 1f)
            }
            2 -> { // clover head
                for (k in 0 until 14) {
                    val a = rng.nextFloat() * 2 * PI.toFloat(); val q = sqrt(rng.nextFloat())
                    p.disc(hx + cos(a) * q * r * 0.6f, hy + sin(a) * q * r * 0.5f - 0.2f * r, r * 0.26f,
                        scale(lerpColor(0xFFC56CAC.toInt(), 0xFFE6A6D2.toInt(), rng.nextFloat()), light), 1f)
                }
            }
            else -> { // cornflower
                for (k in 0 until 8) {
                    val a = (2 * PI * k / 8).toFloat()
                    p.ellipse(hx + cos(a) * r * 0.5f, hy + sin(a) * r * 0.36f, r * 0.5f, r * 0.15f, a, scale(0xFF4F7FE4.toInt(), light), 1f)
                }
                p.disc(hx, hy, r * 0.18f, scale(0xFF2E3C8C.toInt(), light), 1f)
            }
        }
    }

    // ---- Stream: dark water with flowing ripples and bright ripple crests (unchanged) ----

    private fun stream(w: Int, h: Int, d: Float, frame: Int, seed: Long): IntArray {
        val p = Paint2D(w, h, opaque = true)
        p.verticalGradient(0f to 0xFF0B2230.toInt(), 0.5f to 0xFF0E3340.toInt(), 1f to 0xFF07202A.toInt())
        // The flow runs left to right: light catches long, wavering streaks along it, broken up
        // by the current. Frame 1 is the same water a moment downstream.
        val shift = if (frame == 0) 0f else 0.6f
        val band = 9f * d
        for (y in 0 until h) for (x in 0 until w) {
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
        return p.toArgb()
    }

    // ---- River rocks: wet, many-coloured stones — slate, jasper, jade, a few banded agates — in the rain ----

    private enum class StoneKind { AGATE, SLATE, GEM, SPECKLED }

    private class Stone(
        val x: Float, val y: Float, val r: Float, val aspect: Float, val angle: Float,
        val kind: StoneKind, val c1: Int, val c2: Int, val seed: Long, val sheen: Float,
    )

    /** Body / band colour pairs per stone kind, wet and saturated. */
    private val AGATE_COLORS = intArrayOf(
        0xFFB2462A.toInt(), 0xFFE9C9A1.toInt(), // carnelian
        0xFF4E78B6.toInt(), 0xFFCFDDEE.toInt(), // blue lace
        0xFF7A4A9E.toInt(), 0xFFE4D2F0.toInt(), // amethyst banded
        0xFF3E8C6E.toInt(), 0xFFCDE8D8.toInt(), // moss agate
        0xFFC07A2C.toInt(), 0xFFF1DEB4.toInt(), // honey
    )
    private val SLATE_COLORS = intArrayOf(
        0xFF46566E.toInt(), 0xFF8494AC.toInt(), // blue slate
        0xFF5E3C58.toInt(), 0xFF9C7896.toInt(), // plum slate
        0xFF8A4A2A.toInt(), 0xFFC48660.toInt(), // rust
        0xFF4C6638.toInt(), 0xFF8EA870.toInt(), // green slate
        0xFF3A3F46.toInt(), 0xFF7E858E.toInt(), // charcoal
    )
    private val GEM_COLORS = intArrayOf(
        0xFF2F9A7A.toInt(), 0xFF8FE0C4.toInt(), // jade
        0xFF8A4CC0.toInt(), 0xFFD6B2F2.toInt(), // amethyst
        0xFFD49A28.toInt(), 0xFFF6DA86.toInt(), // citrine
        0xFF2A8FB0.toInt(), 0xFF96DCEE.toInt(), // turquoise
        0xFFC24A5E.toInt(), 0xFFF0A6B4.toInt(), // rhodonite
    )
    private val SPECKLE_COLORS = intArrayOf(
        0xFF9A8A86.toInt(), 0xFF2E2A2A.toInt(),
        0xFFB09A8C.toInt(), 0xFF4A3A36.toInt(),
    )

    /**
     * How many stones are banded agates (maintainer 2026-11-10: the "white topo-like circles" on
     * all but a few of them go): the largest stones that lie well inside the view, so all of
     * them show.
     */
    private const val AGATE_STONES = 4

    private fun riverRocks(w: Int, h: Int, d: Float, seed: Long): ScenePainting {
        val p = Paint2D(w, h, opaque = true)
        p.fill(0xFF080D0F.toInt())
        val rng = Random(seed)
        val stones = ArrayList<Stone>()
        var agates = 0
        // Placed big-first without overlapping, down to gravel that fills the gaps; a grid of
        // cells one largest stone wide keeps the overlap test local.
        val maxR = 36f * d
        val gridCell = 2 * maxR
        val gcols = (w / gridCell).toInt() + 3
        val grows = (h / gridCell).toInt() + 3
        val grid = Array(gcols * grows) { ArrayList<Stone>(4) }
        fun gridAt(x: Float, y: Float) = ((y / gridCell).toInt() + 1).coerceIn(0, grows - 1) * gcols + ((x / gridCell).toInt() + 1).coerceIn(0, gcols - 1)
        val tries = (w * h / (d * d) / 1.2f).toInt().coerceIn(1000, 120000)
        repeat(tries) {
            val t = it.toFloat() / tries
            val r = (30f - 27f * t.pow(0.6f)) * d * (0.8f + 0.4f * rng.nextFloat())
            val aspect = 0.62f + 0.33f * rng.nextFloat()
            val x = rng.nextFloat() * (w + 2 * r) - r
            val y = rng.nextFloat() * (h + 2 * r) - r
            val gap = (0.3f + 0.6f * (1f - t)) * d
            val reachR = r * (0.5f + 0.5f * aspect)
            val gx = ((x / gridCell).toInt() + 1).coerceIn(0, gcols - 1)
            val gy = ((y / gridCell).toInt() + 1).coerceIn(0, grows - 1)
            var free = true
            loop@ for (oy in -1..1) for (ox in -1..1) {
                val cx = gx + ox; val cy = gy + oy
                if (cx < 0 || cy < 0 || cx >= gcols || cy >= grows) continue
                for (o in grid[cy * gcols + cx]) {
                    val dx = o.x - x; val dy = o.y - y
                    val q = o.r * (0.5f + 0.5f * o.aspect) + reachR + gap
                    if (dx * dx + dy * dy < q * q) { free = false; break@loop }
                }
            }
            if (free) {
                val kindPick = rng.nextFloat()
                // Stones are placed largest first, so the first big ones well inside the view
                // that draw "agate" are the few banded ones; every later pick goes to the others.
                val inside = x > r && y > r && x < w - r && y < h - r
                val kind = when {
                    kindPick < 0.32f && agates < AGATE_STONES && inside && r > 16f * d -> StoneKind.AGATE.also { agates++ }
                    kindPick < 0.48f -> StoneKind.GEM
                    kindPick < 0.80f -> StoneKind.SLATE
                    kindPick < 0.92f -> StoneKind.GEM
                    else -> StoneKind.SPECKLED
                }
                val table = when (kind) {
                    StoneKind.AGATE -> AGATE_COLORS; StoneKind.SLATE -> SLATE_COLORS
                    StoneKind.GEM -> GEM_COLORS; StoneKind.SPECKLED -> SPECKLE_COLORS
                }
                val pair = rng.nextInt(table.size / 2) * 2
                // Gravel is darker and duller than the stones above it.
                val dull = if (r < 7f * d) 0.6f else 1f
                val stone = Stone(x, y, r, aspect, rng.nextFloat() * PI.toFloat(), kind, scale(table[pair], dull), scale(table[pair + 1], dull), rng.nextLong(), rng.nextFloat())
                stones += stone
                grid[gridAt(x, y)] += stone
            }
        }
        // Soft contact shadows first, offset away from the light (upper left).
        for (s in stones) p.softShadow(s.x + 1.6f * d, s.y + 2.2f * d, s.r * (0.5f + 0.5f * s.aspect) + 2.5f * d, 3.5f * d, 0.55f)
        for (s in stones) paintStone(p, s, d)
        // A film of clear water over everything.
        p.tint(0xFF0C3440.toInt(), 0.08f)
        p.multiply(0.88f)

        // Still: the rain falling on it is the look's particles (ParticleShape.RAIN), which
        // replaced moving caustic light (maintainer 2026-11-10: "a rain effect instead of
        // 'glistening'").
        return ScenePainting(w, h, p.toArgb(), null, emptyList())
    }

    // The stones' light: from the upper left, towards the viewer; and the half vector between it
    // and the viewer (straight above) for the wet highlight.
    private const val LIGHT_X = -0.48f
    private const val LIGHT_Y = -0.56f
    private const val LIGHT_Z = 0.67f
    private val HALF_LEN = sqrt(LIGHT_X * LIGHT_X + LIGHT_Y * LIGHT_Y + (LIGHT_Z + 1f) * (LIGHT_Z + 1f))
    private val HALF_X = LIGHT_X / HALF_LEN
    private val HALF_Y = LIGHT_Y / HALF_LEN
    private val HALF_Z = (LIGHT_Z + 1f) / HALF_LEN

    /** One stone: irregular outline, its kind's pattern, lit from the upper left and wet. */
    private fun paintStone(p: Paint2D, s: Stone, d: Float) {
        val ext = s.r * 1.15f + 2
        val ca = cos(s.angle); val sa = sin(s.angle)
        val x0 = floor(s.x - ext).toInt().coerceAtLeast(0); val x1 = (s.x + ext).toInt().coerceAtMost(p.w - 1)
        val y0 = floor(s.y - ext).toInt().coerceAtLeast(0); val y1 = (s.y + ext).toInt().coerceAtMost(p.h - 1)
        if (x0 > x1 || y0 > y1) return
        val ry = s.r * s.aspect
        val ph1 = (s.seed and 0xFF) / 40f; val ph2 = ((s.seed shr 8) and 0xFF) / 40f
        val lx = LIGHT_X; val ly = LIGHT_Y; val lz = LIGHT_Z
        for (y in y0..y1) for (x in x0..x1) {
            val dx = x + 0.5f - s.x; val dy = y + 0.5f - s.y
            val u = (dx * ca + dy * sa) / s.r
            val v = (-dx * sa + dy * ca) / ry
            val e = sqrt(u * u + v * v)
            val theta = atan2(v, u)
            val outline = 1f + 0.06f * sin(3 * theta + ph1) + 0.045f * sin(5 * theta + ph2) + 0.03f * sin(7 * theta + ph1 * 2)
            val q = e / outline
            if (q >= 1.04f) continue
            val cover = ((1f - q) * ry + 0.5f).coerceIn(0f, 1f)
            if (cover <= 0f) continue
            // A flattened dome: river stones are flatter than spheres.
            val nzRaw = sqrt((1f - min(1f, q * q)).coerceAtLeast(0f))
            val nz = 0.35f + 0.65f * nzRaw
            val gx = (u * ca - v * sa) * q; val gy = (u * sa + v * ca) * q
            val nl = sqrt(gx * gx + gy * gy + nz * nz)
            val nx = gx / nl; val ny = gy / nl; val nzz = nz / nl
            val diffuse = (0.42f + 0.7f * (nx * lx + ny * ly + nzz * lz)).coerceIn(0.18f, 1.15f)
            // The stone's own colour.
            var c = when (s.kind) {
                StoneKind.AGATE -> {
                    val warp = 0.9f * Noise.fbm(x / (9f * d), y / (9f * d), 3, s.seed)
                    val bands = sin(2 * PI * (q * 5.5f + warp)).toFloat()
                    val t = (0.5f + 0.5f * bands).pow(3f)
                    // A translucent core, the bands brightest towards the rim.
                    lerpColor(lerpColor(s.c1, s.c2, t * (0.35f + 0.65f * q)), scale(s.c1, 1.25f), (1f - q) * 0.25f)
                }
                StoneKind.SLATE -> {
                    val across = (u * 0.8f + v * 0.6f) * s.r / (3.2f * d) + 1.4f * Noise.fbm(x / (14f * d), y / (14f * d), 3, s.seed)
                    // Broad, faint tonal strata: sharp light lines read as contour rings
                    // (maintainer 2026-11-10: banding on a few agates only).
                    val strata = (0.5f + 0.5f * sin(2 * PI * across * 0.5f).toFloat()).pow(2f)
                    val mottled = Noise.fbm(x / (5f * d), y / (5f * d), 2, s.seed + 1)
                    lerpColor(scale(s.c1, 0.85f + 0.3f * mottled), s.c2, 0.18f * strata)
                }
                StoneKind.GEM -> {
                    val mottled = Noise.fbm(x / (6f * d), y / (6f * d), 3, s.seed)
                    lerpColor(s.c1, s.c2, ((1f - q) * 0.45f + (mottled - 0.5f) * 0.6f).coerceIn(0f, 1f))
                }
                StoneKind.SPECKLED -> {
                    // Granite: soft dark grains in a mottled body.
                    val speck = Noise.fbm(x / (1.4f * d), y / (1.4f * d), 2, s.seed)
                    lerpColor(scale(s.c1, 0.9f + 0.2f * Noise.value(x / (3f * d), y / (3f * d), s.seed + 1)), s.c2, ((speck - 0.6f) * 5f).coerceIn(0f, 1f))
                }
            }
            c = scale(c, diffuse * (0.7f + 0.3f * nzRaw))
            // Wet: a sharp highlight and a broad sheen (Blinn-Phong, the viewer straight above).
            val ndh = (nx * HALF_X + ny * HALF_Y + nzz * HALF_Z).coerceAtLeast(0f)
            val gloss = ndh.pow(90f) * (0.9f + 0.5f * s.sheen) + ndh.pow(12f) * 0.18f
            val rr = ch(c, 16) + 255f * gloss; val gg = ch(c, 8) + 255f * gloss; val bb = ch(c, 0) + 255f * gloss
            // The rim darkens where the stone turns away into the wet gaps.
            val rim = 0.55f + 0.45f * nzRaw.pow(0.5f)
            p.blendRgb(y * p.w + x, rr * rim, gg * rim, bb * rim, cover)
        }
    }

    // ---- Forest: a misty glade under swaying leafy branches, ferns in the foreground ----

    private val LEAF_GREENS = intArrayOf(
        0xFF2C6A2A.toInt(), 0xFF3C8434.toInt(), 0xFF56A23C.toInt(), 0xFF7DBE48.toInt(),
        0xFF1F5426.toInt(), 0xFF9BCB54.toInt(), 0xFF467A2E.toInt(),
    )

    private fun forest(w: Int, h: Int, d: Float, seed: Long): ScenePainting {
        val p = Paint2D(w, h, opaque = true)
        p.verticalGradient(0f to 0xFF0E1C16.toInt(), 0.3f to 0xFF1A3427.toInt(), 0.62f to 0xFF284031.toInt(), 1f to 0xFF0A150F.toInt())
        // Sun through the canopy, top right, and its shafts falling to the left.
        p.glow(0.74f * w, 0.02f * h, 0.62f * w, 0xFFD2E8A4.toInt(), 0.22f)
        val rng = Random(seed)
        repeat(4) { k ->
            val sx = (0.55f + 0.12f * k + 0.05f * rng.nextFloat()) * w
            val width = (10f + 16f * rng.nextFloat()) * d
            p.shaft(sx, 0f, -0.36f, width, 0.75f * h, 0xFFE6F0C0.toInt(), 0.06f + 0.03f * rng.nextFloat())
        }
        // Distant trunks: wide, soft, fading into the mist (never thin lines).
        val trunks = (w / d / 90f).toInt().coerceIn(3, 7)
        repeat(trunks) { k ->
            val x = (k + 0.2f + 0.6f * rng.nextFloat()) * w / trunks
            val tw = (16f + 18f * rng.nextFloat()) * d
            p.trunk(x, tw, tw * 1.25f, 0.88f * h, 0xFF16261C.toInt(), 0.55f + 0.25f * rng.nextFloat(), d, seed + k)
        }
        // Mist in the glade, then the forest floor.
        p.band(0.55f, 0.86f, 0xFF41584A.toInt(), 0.14f)
        p.ridge(0.86f, 0.015f, 2.4f, seed + 3, 0xFF132014.toInt())
        for (y in (0.86f * h).toInt() until h) for (x in 0 until w) {
            val m = Noise.fbm(x / (6f * d), y / (4f * d), 3, seed + 4)
            p.over(y * w + x, if (m > 0.55f) 0xFF2E4A22.toInt() else 0xFF0E180C.toInt(), 0.35f * abs(m - 0.5f) * 2f)
        }
        // The far canopy: small dark leaves across the top, still.
        repeat(((w / d) * 4f).toInt().coerceIn(200, 3000)) {
            val x = rng.nextFloat() * w
            val y = h * 0.3f * rng.nextFloat().pow(1.6f) - 2 * d
            val c = scale(lerpColor(0xFF1C3A26.toInt(), 0xFF2E5634.toInt(), rng.nextFloat()), 0.75f)
            p.leaf(x, y, (5f + 3f * rng.nextFloat()) * d, (1.8f + rng.nextFloat()) * d, rng.nextFloat() * 2 * PI.toFloat(), c, 0f, 0.9f)
        }

        val layers = ArrayList<SceneLayer>()
        // Middle branches hanging from the top, then two big near branches from the corners.
        layers += branchLayer(w, h, d, Random(seed + 10), near = false)
        layers += branchLayer(w, h, d, Random(seed + 11), near = true)
        layers += fernLayer(w, h, d, Random(seed + 12))
        return ScenePainting(w, h, p.toArgb(), null, layers)
    }

    /** A layer of leafy branches hanging into the view from above, swaying from the top edge. */
    private fun branchLayer(w: Int, h: Int, d: Float, rng: Random, near: Boolean): SceneLayer {
        val amp = (if (near) 7f else 4.5f) * d
        val margin = (amp + 30 * d).toInt()
        val lh = ((if (near) 0.5f else 0.46f) * h).toInt().coerceAtLeast(1)
        val lw = w + 2 * margin
        val lp = Paint2D(lw, lh, opaque = false)
        val branches = if (near) listOf(-0.04f to 0.36f, 1.04f to 0.64f, 0.5f to 0.42f)
        else List((w / d / 55f).toInt().coerceIn(3, 9)) { (it + rng.nextFloat()) / (w / d / 55f).toInt().coerceIn(3, 9) to 0f }
        for ((startU, endU) in branches) {
            val sx = margin + startU * w
            val sy = -4f * d
            val ex = if (near) margin + endU * w else sx + (rng.nextFloat() - 0.5f) * 60f * d
            val ey = (if (near) 0.26f + 0.12f * rng.nextFloat() else 0.18f + 0.22f * rng.nextFloat()) * h
            val bend = (rng.nextFloat() - 0.5f) * 40f * d
            val n = 6
            val xs = FloatArray(n); val ys = FloatArray(n); val rs = FloatArray(n)
            for (s in 0 until n) {
                val t = s.toFloat() / (n - 1)
                xs[s] = sx + (ex - sx) * t + bend * sin(PI.toFloat() * t)
                ys[s] = sy + (ey - sy) * t + (if (near) 0.08f * h * t * t else 0f)
                rs[s] = ((if (near) 2.6f else 1.5f) * (1f - 0.65f * t)) * d
            }
            lp.stroke(xs, ys, rs, 0xFF2A2016.toInt(), 0xFF4A3A26.toInt(), 1f, sideLight = 0.3f)
            // Leaves alternate along the branch and gather at its end.
            val leafLen = (if (near) 15f else 9.5f) * d
            val step = (if (near) 5.2f else 4.2f) * d
            val total = sqrt((ex - sx) * (ex - sx) + (ey - sy) * (ey - sy)) + 0.1f * h
            var along = 0.12f * total
            var side = 1f
            while (along < total * 1.02f) {
                val t = (along / total).coerceAtMost(1f)
                val bx = sx + (ex - sx) * t + bend * sin(PI.toFloat() * t)
                val by = sy + (ey - sy) * t + (if (near) 0.08f * h * t * t else 0f)
                val dirX = (ex - sx) / total; val dirY = (ey - sy) / total
                val base = atan2(dirY, dirX)
                // Leaves hang off both sides, drooping with gravity.
                val a = base + side * (0.7f + 0.5f * rng.nextFloat()) + 0.35f * (PI.toFloat() / 2 - base) * 0.4f
                val len = leafLen * (0.75f + 0.45f * rng.nextFloat()) * (1f - 0.3f * t)
                val green = LEAF_GREENS[rng.nextInt(LEAF_GREENS.size)]
                // Leaves towards the sun (right, top) are lit and warmer.
                val sun = ((bx - margin) / w).coerceIn(0f, 1f) * (1f - by / lh) * 0.6f
                val c = scale(lerpColor(green, 0xFFC4DC74.toInt(), sun * 0.4f), if (near) 1f else 0.78f)
                lp.leaf(bx, by, len, len * (0.32f + 0.08f * rng.nextFloat()), a, c, sun, 1f)
                side = -side
                along += step * (0.8f + 0.4f * rng.nextFloat())
            }
        }
        return SceneLayer(-margin, 0, lw, lh, lp.toArgb(), LayerMotion(
            LayerMotionKind.SWAY_DOWN, anchorY = 0f, reach = lh.toFloat(), amplitude = amp, bias = 0.1f,
            periodS = if (near) 6.3f else 5.4f, phase = if (near) 0.35f else 0.1f, wavelength = 0.9f * w,
        ))
    }

    /** Fern fronds rising from the bottom edge, swaying from their roots. */
    private fun fernLayer(w: Int, h: Int, d: Float, rng: Random): SceneLayer {
        val amp = 4f * d
        val margin = (amp + 20 * d).toInt()
        val top = (0.55f * h).toInt()
        val lw = w + 2 * margin
        val lh = h - top
        val lp = Paint2D(lw, lh, opaque = false)
        val fronds = (w / d / 34f).toInt().coerceIn(5, 16)
        repeat(fronds) { k ->
            val rx = margin + (k + rng.nextFloat()) * w / fronds
            val ry = lh + (2f + 6f * rng.nextFloat()) * d
            val len = (0.24f + 0.16f * rng.nextFloat()) * h
            val lean = (if (rng.nextBoolean()) 1f else -1f) * (0.25f + 0.5f * rng.nextFloat())
            val n = 7
            val xs = FloatArray(n); val ys = FloatArray(n); val rs = FloatArray(n)
            for (s in 0 until n) {
                val t = s.toFloat() / (n - 1)
                xs[s] = rx + len * (sin(lean) * t + 0.35f * lean * t * t)
                ys[s] = ry - len * cos(lean) * t + 0.25f * len * t * t * abs(lean)
                rs[s] = (1.1f * (1f - 0.7f * t)) * d
            }
            lp.stroke(xs, ys, rs, 0xFF1C3A16.toInt(), 0xFF4E8A34.toInt(), 1f)
            // Paired leaflets, shrinking to the tip.
            val pairs = 14
            for (j in 1 until pairs) {
                val t = j.toFloat() / pairs
                val i = (t * (n - 1)).toInt().coerceAtMost(n - 2)
                val ft = t * (n - 1) - i
                val px = xs[i] + (xs[i + 1] - xs[i]) * ft; val py = ys[i] + (ys[i + 1] - ys[i]) * ft
                val dir = atan2(ys[i + 1] - ys[i], xs[i + 1] - xs[i])
                val ll = len * 0.17f * (1f - t).pow(0.7f) + 1.5f * d
                val c = lerpColor(0xFF2C6A2A.toInt(), 0xFF7CB84A.toInt(), t * 0.7f + 0.2f * rng.nextFloat())
                for (side in intArrayOf(-1, 1)) {
                    lp.leaf(px, py, ll, ll * 0.24f, dir + side * 1.15f - 0.15f, c, 0.15f, 1f)
                }
            }
        }
        return SceneLayer(-margin, top, lw, lh, lp.toArgb(), LayerMotion(
            LayerMotionKind.SWAY_UP, anchorY = h.toFloat(), reach = 0.4f * h, amplitude = amp, bias = 0.15f,
            periodS = 4.8f, phase = 0.6f, wavelength = 0.7f * w,
        ))
    }

    // ---- Snow: a blue night over snowy hills with a few pines; the snowfall is particles (unchanged) ----

    private fun snow(w: Int, h: Int, d: Float, frame: Int, seed: Long): IntArray {
        val p = Paint2D(w, h, opaque = true)
        p.verticalGradient(0f to 0xFF141C2C.toInt(), 0.5f to 0xFF27344C.toInt(), 1f to 0xFF3A4660.toInt())
        val rng = Random(seed)
        // Pines on the far ridge.
        val pines = (w / d / 28f).toInt().coerceAtLeast(4)
        repeat(pines) {
            val x = rng.nextFloat() * w
            val base = h * (0.62f + 0.08f * rng.nextFloat())
            val hgt = (30f + 40f * rng.nextFloat()) * d
            p.pine(x, base, hgt, 0xFF1A2434.toInt())
        }
        // Two snowy hills, lit faintly blue; the glitter on their crust moves between frames.
        p.ridge(0.66f, 0.06f, 0.9f, seed + 1, 0xFF8A9CB8.toInt())
        p.ridge(0.80f, 0.05f, 1.6f, seed + 2, 0xFFB4C2D8.toInt())
        val glitter = Random(seed + 10 + frame)
        repeat((w * h / (d * d) / 90f).toInt().coerceIn(40, 2000)) {
            val x = glitter.nextFloat() * w
            val y = h * (0.7f + 0.3f * glitter.nextFloat())
            p.disc(x, y, 0.6f * d, 0xFFFFFFFF.toInt(), 0.5f)
        }
        return p.toArgb()
    }

    // ---- Beach: a sea whose swells roll in and wash up textured sand ----

    /** Where the sea meets the sand at [x], raster px. */
    private fun shoreAt(x: Float, w: Int, h: Int, seed: Long): Float =
        h * (0.30f + 0.022f * sin(2 * PI * (x / w * 1.2f + 0.15f)).toFloat() + 0.012f * (Noise.fbm(x / w * 6f, 0.5f, 3, seed) - 0.5f) * 2f)

    private fun beach(w: Int, h: Int, d: Float, seed: Long): ScenePainting {
        val p = Paint2D(w, h, opaque = true)
        for (y in 0 until h) for (x in 0 until w) {
            val fx = x + 0.5f; val fy = y + 0.5f
            val shore = shoreAt(fx, w, h, seed)
            val i = y * w + x
            if (fy < shore) {
                // The sea: deep blue to turquoise in the shallows, with swell lines and a little glitter.
                val t = (fy / shore).coerceIn(0f, 1f)
                var c = lerpColor(0xFF0B2E4C.toInt(), 0xFF1B6A84.toInt(), t.pow(1.3f))
                c = lerpColor(c, 0xFF2E9A9A.toInt(), ((t - 0.72f) / 0.28f).coerceIn(0f, 1f) * 0.8f)
                val swell = sin(2 * PI * (fy / ((5f + 6f * t) * d) + 0.7f * Noise.fbm(fx / (45f * d), fy / (14f * d), 3, seed))).toFloat()
                c = scale(c, 1f + 0.10f * max(0f, swell).pow(3f) - 0.05f * max(0f, -swell))
                p.setAt(x, y, c)
                if (Noise.hash01(x, y, seed + 7) > 0.9988f - 0.0008f * (1f - t)) p.over(i, 0xFFFFF4DC.toInt(), 0.6f)
            } else {
                val wet = shore + 0.11f * h
                val c = if (fy < wet) {
                    // Wet sand: glossy, mirroring the sky in streaks, drying to the dry sand's colour.
                    val t = (fy - shore) / (wet - shore)
                    var s = lerpColor(0xFF6E6252.toInt(), 0xFF9C8462.toInt(), t.pow(0.7f))
                    val sheen = Noise.fbm(fx / (70f * d), fy / (2.5f * d), 3, seed + 8)
                    s = lerpColor(s, 0xFFA9C2CC.toInt(), (0.18f + 0.3f * ((sheen - 0.4f) * 2f).coerceIn(0f, 1f)) * (1f - t).pow(1.5f))
                    s
                } else {
                    // Dry sand: wind ripples, grain, darker and lighter grains.
                    val big = Noise.fbm(fx / (90f * d), fy / (60f * d), 3, seed + 9)
                    var s = lerpColor(0xFFAE9268.toInt(), 0xFFC9AF84.toInt(), big)
                    val ripple = sin(2 * PI * (fy / (4.6f * d) + 0.9f * Noise.fbm(fx / (55f * d), fy / (28f * d), 3, seed + 10) + fx / (80f * d))).toFloat()
                    s = scale(s, 1f + 0.07f * ripple.coerceAtLeast(0f).pow(2f) - 0.06f * (-ripple).coerceAtLeast(0f).pow(1.5f))
                    s
                }
                val grain = Noise.hash01(x, y, seed + 11) - 0.5f
                p.setAt(x, y, scale(c, 1f + 0.12f * grain))
                val coarse = Noise.hash01(x / max(1, (1.2f * d).toInt()), y / max(1, (1.2f * d).toInt()), seed + 12)
                if (coarse > 0.985f) p.over(i, if (coarse > 0.993f) 0xFFEDE2CC.toInt() else 0xFF5C4C3A.toInt(), 0.5f)
            }
        }
        // The line of the last high wash: a thin, darker wrack line with bits of shell.
        val rng = Random(seed + 13)
        repeat((w / d / 3f).toInt().coerceIn(30, 400)) {
            val x = rng.nextFloat() * w
            val y = shoreAt(x, w, h, seed) + 0.105f * h + (rng.nextFloat() - 0.5f) * 2f * d
            if (rng.nextFloat() < 0.75f) p.ellipse(x, y, (0.8f + rng.nextFloat()) * d, 0.5f * d, rng.nextFloat(), 0xFF4A3C2C.toInt(), 0.6f)
            else p.ellipse(x, y, (1.4f + 1.2f * rng.nextFloat()) * d, (0.9f + 0.6f * rng.nextFloat()) * d, rng.nextFloat() * 3f,
                lerpColor(0xFFF0DDD0.toInt(), 0xFFE6B8A8.toInt(), rng.nextFloat()), 0.85f)
        }
        // Shells and pebbles scattered on the dry sand.
        repeat((w * h / (d * d) / 3500f).toInt().coerceIn(6, 120)) {
            val x = rng.nextFloat() * w
            val y = h * (0.5f + 0.5f * rng.nextFloat())
            val r = (1.2f + 1.6f * rng.nextFloat()) * d
            if (rng.nextBoolean()) shell(p, x, y, r, rng.nextFloat() * PI.toFloat())
            else p.ellipse(x, y, r, r * 0.7f, rng.nextFloat() * 3f, lerpColor(0xFF6A5E54.toInt(), 0xFF8E8478.toInt(), rng.nextFloat()), 0.9f)
        }
        // Dimmed so the keys stay legible over the brightest sand.
        p.multiply(0.64f)

        val layers = ArrayList<SceneLayer>()
        val meanShore = 0.30f * h
        // Two swells rolling in, the second smaller, half a cycle apart.
        for ((k, strength) in listOf(1f, 0.6f).withIndex()) {
            val startY = (0.05f + 0.06f * k) * h
            layers += swellLayer(w, h, d, seed + 20 + k, startY, strength, LayerMotion(
                LayerMotionKind.ROLL, amplitude = meanShore - startY - 4f * d, periodS = 8f, phase = 0.3f + 0.5f * k,
            ))
        }
        // And the water each one sends up the sand.
        for ((k, reach) in listOf(1f, 0.55f).withIndex()) {
            layers += washLayer(w, h, d, seed, seed + 30 + k, reach, LayerMotion(
                LayerMotionKind.WASH, amplitude = 0.075f * h * reach, periodS = 8f, phase = 0.3f + 0.5f * k, fade = 0.85f,
            ))
        }
        return ScenePainting(w, h, p.toArgb(), null, layers)
    }

    /** A small ribbed shell. */
    private fun shell(p: Paint2D, x: Float, y: Float, r: Float, angle: Float) {
        val c = 0xFFE9D6C8.toInt()
        p.ellipse(x, y, r, r * 0.8f, angle, scale(c, 0.85f), 0.9f)
        for (k in -2..2) {
            val a = angle + PI.toFloat() / 2 + k * 0.32f
            p.stroke(floatArrayOf(x - cos(angle) * r * 0.1f, x + cos(a) * r * 0.85f), floatArrayOf(y + r * 0.2f, y + sin(a) * r * 0.85f - r * 0.3f),
                floatArrayOf(0.22f * r, 0.12f * r), 0xFFB89A8A.toInt(), 0xFFF6EAE0.toInt(), 0.7f)
        }
    }

    /** A swell's crest: a lighter wave face under a broken line of white water, full width. */
    private fun swellLayer(w: Int, h: Int, d: Float, seed: Long, y: Float, strength: Float, motion: LayerMotion): SceneLayer {
        val margin = (4 * d).toInt()
        val lh = (18f * d).toInt().coerceAtLeast(4)
        val top = (y - lh * 0.3f).toInt()
        val lp = Paint2D(w + 2 * margin, lh, opaque = false)
        val crestBase = lh * 0.3f
        for (yy in 0 until lh) for (xx in 0 until lp.w) {
            val x = xx - margin + 0.5f
            val crest = crestBase + 1.6f * d * sin(2 * PI * (x / (w * 0.9f) + 0.2f)).toFloat() + 1.2f * d * (Noise.fbm(x / (30f * d), 0.3f, 2, seed) - 0.5f)
            val dy = yy + 0.5f - crest
            val i = yy * lp.w + xx
            if (dy < 0f) {
                // The trough before the crest, a little darker.
                lp.over(i, 0xFF06243A.toInt(), 0.18f * strength * exp(-(dy * dy) / (6f * d * d)))
            } else {
                // The lit face of the wave, fading towards the shore.
                lp.over(i, 0xFF4CC2BE.toInt(), 0.5f * strength * exp(-dy / (5f * d)))
                // White water along the crest, broken up.
                val broken = Noise.fbm(x / (5f * d), 0.7f, 3, seed + 1)
                val foam = ((broken - 0.42f) * 3f).coerceIn(0f, 1f) * exp(-(dy * dy) / (1.4f * d * d))
                lp.over(i, 0xFFF2F8F6.toInt(), 0.85f * strength * foam)
            }
        }
        return SceneLayer(-margin, top, lp.w, lh, lp.toArgb(), motion)
    }

    /**
     * The wash: a sheet of shallow water laced with foam, its leading edge a line of white. The
     * sheet reaches back into the sea by its whole run ([LayerMotion.amplitude]) in the sea's own
     * (dimmed) colour, so as it runs up the sand the water stays one body with the sea.
     */
    private fun washLayer(w: Int, h: Int, d: Float, beachSeed: Long, seed: Long, reach: Float, motion: LayerMotion): SceneLayer {
        val back = abs(motion.amplitude) + 4f * d
        val top = (0.30f * h - 0.04f * h - back).toInt().coerceAtLeast(0)
        val bottom = (0.30f * h + 0.04f * h + 0.06f * h * reach + 3f * d).toInt().coerceAtMost(h)
        val lh = (bottom - top).coerceAtLeast(4)
        val lp = Paint2D(w, lh, opaque = false)
        val cell = 2.4f * d
        for (yy in 0 until lh) for (x in 0 until w) {
            val fx = x + 0.5f
            val y = top + yy + 0.5f
            val shore = shoreAt(fx, w, h, beachSeed)
            // The sheet reaches from the water's edge to its leading line.
            val edge = shore + (0.035f + 0.02f * Noise.fbm(fx / (40f * d), 0.4f, 3, seed)) * h * reach
            if (y < shore - back || y > edge + 1.5f * d) continue
            val i = yy * w + x
            val inSheet = ((edge - y) / (1.5f * d)).coerceIn(0f, 1f)
            if (y < shore) {
                // Over the sea: the shallows' colour, fading in from nothing at the top.
                lp.over(i, SHALLOWS, 0.9f * ((y - (shore - back)) / back).coerceIn(0f, 1f))
                continue
            }
            val nearFront = ((y - shore) / max(1f, edge - shore)).coerceIn(0f, 1f)
            // Shallow water, the sea's turquoise thinning to clear towards the leading edge.
            lp.over(i, lerpColor(SHALLOWS, 0xFF6C908A.toInt(), nearFront), (0.9f - 0.6f * nearFront) * inSheet)
            // Foam lace in drifts: thin bubble walls, dense towards the leading edge.
            val lace = exp(-Worley.edge(fx / cell, y / cell, seed + 1) / 0.07f)
            val drift = ((Noise.fbm(fx / (18f * d), y / (6f * d), 3, seed + 3) - 0.3f) * 2f).coerceIn(0f, 1f)
            lp.over(i, 0xFFDDE6E4.toInt(), 0.4f * lace * drift * inSheet * (0.3f + 0.7f * nearFront))
            val front = exp(-((y - edge) * (y - edge)) / (1.1f * d * d)) * ((Noise.fbm(fx / (6f * d), 0.2f, 2, seed + 2) - 0.3f) * 2f).coerceIn(0.3f, 1f)
            lp.over(i, 0xFFF2F6F4.toInt(), 0.85f * front)
        }
        return SceneLayer(0, top, w, lh, lp.toArgb(), motion)
    }

    /** The sea's colour at the shore, after the beach's dimming: what the wash is made of. */
    private val SHALLOWS = 0xFF1D6263.toInt()

    // ---- toolkit ----

    /**
     * A float RGBA canvas (premultiplied; [opaque] starts black and stays opaque, otherwise
     * transparent) with the anti-aliased primitives the scenes need.
     */
    private class Paint2D(val w: Int, val h: Int, opaque: Boolean) {
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)
        val a = FloatArray(w * h).also { if (opaque) it.fill(1f) }

        fun fill(c: Int) {
            r.fill(ch(c, 16)); g.fill(ch(c, 8)); b.fill(ch(c, 0)); a.fill(1f)
        }

        fun setAt(x: Int, y: Int, c: Int) {
            val i = y * w + x
            r[i] = ch(c, 16); g[i] = ch(c, 8); b[i] = ch(c, 0); a[i] = 1f
        }

        /** Adds (or with negative [k], subtracts) light of colour [c] (opaque canvases). */
        fun addAt(x: Int, y: Int, c: Int, k: Float) {
            if (k == 0f) return
            val i = y * w + x
            r[i] += ch(c, 16) * k; g[i] += ch(c, 8) * k; b[i] += ch(c, 0) * k
        }

        /** Colour [c] over pixel [i] at coverage [k]. */
        fun over(i: Int, c: Int, k: Float) {
            if (k <= 0f) return
            val q = k.coerceAtMost(1f)
            r[i] = ch(c, 16) * q + r[i] * (1 - q); g[i] = ch(c, 8) * q + g[i] * (1 - q); b[i] = ch(c, 0) * q + b[i] * (1 - q)
            a[i] = q + a[i] * (1 - q)
        }

        /** Unpremultiplied channels ([cr], [cg], [cb] 0..255+) over pixel [i] at coverage [k]. */
        fun blendRgb(i: Int, cr: Float, cg: Float, cb: Float, k: Float) {
            val q = k.coerceIn(0f, 1f)
            r[i] = cr * q + r[i] * (1 - q); g[i] = cg * q + g[i] * (1 - q); b[i] = cb * q + b[i] * (1 - q)
            a[i] = q + a[i] * (1 - q)
        }

        fun multiply(f: Float) {
            for (i in r.indices) { r[i] *= f; g[i] *= f; b[i] *= f }
        }

        /** [c] over the whole canvas at [k]. */
        fun tint(c: Int, k: Float) {
            for (i in r.indices) over(i, c, k * a[i])
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
                val k = strength * (1f - (y - y0).toFloat() / max(1, y1 - y0))
                for (x in 0 until w) over(y * w + x, c, k)
            }
        }

        /** Towards [c] from [from] to [to], strongest at [to]. */
        fun shadeDown(from: Float, to: Float, c: Int, strength: Float) {
            val y0 = (from * h).toInt().coerceIn(0, h); val y1 = (to * h).toInt().coerceIn(0, h)
            for (y in y0 until y1) {
                val k = strength * ((y - y0).toFloat() / max(1, y1 - y0))
                for (x in 0 until w) over(y * w + x, c, k)
            }
        }

        /** [c] over a horizontal band from [from] to [to], strongest in its middle. */
        fun band(from: Float, to: Float, c: Int, strength: Float) {
            val y0 = (from * h).toInt().coerceIn(0, h); val y1 = (to * h).toInt().coerceIn(0, h)
            for (y in y0 until y1) {
                val k = strength * sin(PI.toFloat() * (y - y0) / max(1, y1 - y0))
                for (x in 0 until w) over(y * w + x, c, k)
            }
        }

        /** Light of colour [c] added in a soft disc of [radius] around ([cx], [cy]). */
        fun glow(cx: Float, cy: Float, radius: Float, c: Int, strength: Float) {
            val x0 = max(0, (cx - radius).toInt()); val x1 = min(w - 1, (cx + radius).toInt())
            val y0 = max(0, (cy - radius).toInt()); val y1 = min(h - 1, (cy + radius).toInt())
            for (y in y0..y1) for (x in x0..x1) {
                val q = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) / radius
                if (q < 1f) addAt(x, y, c, strength * (1f - q) * (1f - q))
            }
        }

        /** A shaft of light from ([x], [y]) down at [slope] (dx per dy), [width] wide, fading over [length]. */
        fun shaft(x: Float, y: Float, slope: Float, width: Float, length: Float, c: Int, strength: Float) {
            for (yy in max(0, y.toInt()) until min(h, (y + length).toInt())) {
                val along = (yy - y) / length
                val cx = x + slope * (yy - y)
                val x0 = max(0, (cx - 2 * width).toInt()); val x1 = min(w - 1, (cx + 2 * width).toInt())
                for (xx in x0..x1) {
                    val q = (xx - cx) / width
                    addAt(xx, yy, c, strength * exp(-q * q) * (1f - along))
                }
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

        fun disc(cx: Float, cy: Float, rad: Float, c: Int, k: Float) {
            val x0 = floor(cx - rad - 1).toInt().coerceAtLeast(0); val x1 = (cx + rad + 1).toInt().coerceAtMost(w - 1)
            val y0 = floor(cy - rad - 1).toInt().coerceAtLeast(0); val y1 = (cy + rad + 1).toInt().coerceAtMost(h - 1)
            for (y in y0..y1) for (x in x0..x1) {
                val dist = sqrt((x + 0.5f - cx) * (x + 0.5f - cx) + (y + 0.5f - cy) * (y + 0.5f - cy))
                over(y * w + x, c, k * (rad + 0.5f - dist).coerceIn(0f, 1f))
            }
        }

        /** A soft dark shadow disc around ([cx], [cy]): opaque to [rad], fading over [soft]. */
        fun softShadow(cx: Float, cy: Float, rad: Float, soft: Float, k: Float) {
            val ext = rad + soft
            val x0 = floor(cx - ext).toInt().coerceAtLeast(0); val x1 = (cx + ext).toInt().coerceAtMost(w - 1)
            val y0 = floor(cy - ext).toInt().coerceAtLeast(0); val y1 = (cy + ext).toInt().coerceAtMost(h - 1)
            for (y in y0..y1) for (x in x0..x1) {
                val dist = sqrt((x + 0.5f - cx) * (x + 0.5f - cx) + (y + 0.5f - cy) * (y + 0.5f - cy))
                over(y * w + x, 0xFF000000.toInt(), k * (1f - ((dist - rad) / soft).coerceIn(0f, 1f)))
            }
        }

        fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, angle: Float, c: Int, k: Float) {
            val ext = max(rx, ry) + 1
            val ca = cos(angle); val sa = sin(angle)
            val x0 = floor(cx - ext).toInt().coerceAtLeast(0); val x1 = (cx + ext).toInt().coerceAtMost(w - 1)
            val y0 = floor(cy - ext).toInt().coerceAtLeast(0); val y1 = (cy + ext).toInt().coerceAtMost(h - 1)
            for (y in y0..y1) for (x in x0..x1) {
                val dx = x + 0.5f - cx; val dy = y + 0.5f - cy
                val u = (dx * ca + dy * sa) / rx; val v = (-dx * sa + dy * ca) / ry
                val q = sqrt(u * u + v * v)
                over(y * w + x, c, k * ((1f - q) * min(rx, ry) + 0.5f).coerceIn(0f, 1f))
            }
        }

        /**
         * A tapered stroke through the points ([xs], [ys]) with radii [rs], coloured from [c0] at
         * its start to [c1] at its end; [sideLight] brightens one side and darkens the other, so a
         * blade or a stem reads as rounded. Each pixel is covered once (its distance to the
         * whole stroke), so overlapping segments never double the alpha.
         */
        fun stroke(xs: FloatArray, ys: FloatArray, rs: FloatArray, c0: Int, c1: Int, k: Float, sideLight: Float = 0f) {
            val n = xs.size
            if (n < 2) return
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            var maxR = 0f
            for (i in 0 until n) {
                minX = min(minX, xs[i]); maxX = max(maxX, xs[i]); minY = min(minY, ys[i]); maxY = max(maxY, ys[i]); maxR = max(maxR, rs[i])
            }
            val x0 = floor(minX - maxR - 1).toInt().coerceAtLeast(0); val x1 = (maxX + maxR + 1).toInt().coerceAtMost(w - 1)
            val y0 = floor(minY - maxR - 1).toInt().coerceAtLeast(0); val y1 = (maxY + maxR + 1).toInt().coerceAtMost(h - 1)
            if (x0 > x1 || y0 > y1) return
            for (y in y0..y1) for (x in x0..x1) {
                val px = x + 0.5f; val py = y + 0.5f
                var best = Float.MAX_VALUE; var bestT = 0f; var bestSide = 0f
                for (i in 0 until n - 1) {
                    val ax = xs[i]; val ay = ys[i]; val bx = xs[i + 1]; val by = ys[i + 1]
                    val vx = bx - ax; val vy = by - ay
                    val len2 = vx * vx + vy * vy
                    val t = if (len2 <= 1e-6f) 0f else (((px - ax) * vx + (py - ay) * vy) / len2).coerceIn(0f, 1f)
                    val cx = ax + vx * t; val cy = ay + vy * t
                    val dist = sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
                    val rad = rs[i] + (rs[i + 1] - rs[i]) * t
                    val sd = dist - rad
                    if (sd < best) {
                        best = sd
                        bestT = (i + t) / (n - 1)
                        bestSide = if (rad > 0f) ((vx * (py - ay) - vy * (px - ax)) / max(1e-3f, sqrt(len2))) / rad else 0f
                    }
                }
                val cover = (0.5f - best).coerceIn(0f, 1f)
                if (cover <= 0f) continue
                var c = lerpColor(c0, c1, bestT.pow(0.7f))
                if (sideLight != 0f) c = scale(c, 1f + sideLight * bestSide.coerceIn(-1f, 1f))
                over(y * w + x, c, k * cover)
            }
        }

        /**
         * A leaf from its stem at ([x], [y]), [len] long and [halfWidth] wide at its widest,
         * pointing along [angle]: an ovate blade with a pointed tip, a midrib and faint side
         * veins, one half lit; [sun] warms and lightens it as light through a leaf does.
         */
        fun leaf(x: Float, y: Float, len: Float, halfWidth: Float, angle: Float, c: Int, sun: Float, k: Float) {
            val ext = len + 1
            val ca = cos(angle); val sa = sin(angle)
            val x0 = floor(x - ext).toInt().coerceAtLeast(0); val x1 = (x + ext).toInt().coerceAtMost(w - 1)
            val y0 = floor(y - ext).toInt().coerceAtLeast(0); val y1 = (y + ext).toInt().coerceAtMost(h - 1)
            if (x0 > x1 || y0 > y1) return
            val lit = lerpColor(c, 0xFFD8EC8C.toInt(), 0.18f + 0.3f * sun)
            val dark = scale(c, 0.72f)
            for (yy in y0..y1) for (xx in x0..x1) {
                val dx = xx + 0.5f - x; val dy = yy + 0.5f - y
                val along = dx * ca + dy * sa
                val across = -dx * sa + dy * ca
                val u = along / len
                if (u <= -0.02f || u >= 1.02f) continue
                val uu = u.coerceIn(0f, 1f)
                // Widest a third of the way up, rounded at the stem, pointed at the tip.
                val hw = halfWidth * sin(PI.toFloat() * uu.pow(0.75f)).coerceAtLeast(0f).pow(0.85f)
                val cover = min((hw - abs(across) + 0.5f).coerceIn(0f, 1f), (min(along, len - along) + 0.5f).coerceIn(0f, 1f))
                if (cover <= 0f) continue
                val side = if (hw > 0f) across / hw else 0f
                var col = if (side < 0f) lerpColor(c, lit, -side * 0.8f) else lerpColor(c, dark, side * 0.5f)
                // Midrib, side veins and a darker rim.
                if (abs(across) < 0.35f + 0.05f * halfWidth) col = lerpColor(col, lit, 0.45f)
                else {
                    val vein = uu * 7f - abs(side) * 1.3f
                    if (vein - floor(vein) < 0.09f) col = lerpColor(col, lit, 0.18f)
                }
                if (abs(across) > hw - 0.8f) col = lerpColor(col, dark, 0.35f)
                over(yy * w + xx, col, k * cover)
            }
        }

        /** A tree trunk from the top down to [bottom], softly lit from the right, [k] opaque (mist). */
        fun trunk(x: Float, wTop: Float, wBottom: Float, bottom: Float, c: Int, k: Float, d: Float, seed: Long) {
            val soft = 2.5f * d
            for (y in 0 until min(h, bottom.toInt())) {
                val t = y.toFloat() / bottom
                val half = (wTop + (wBottom - wTop) * t) / 2
                // The mist thickens towards the ground.
                val fadeK = k * (1f - 0.45f * t)
                val x0 = floor(x - half - soft).toInt().coerceAtLeast(0); val x1 = (x + half + soft).toInt().coerceAtMost(w - 1)
                for (xx in x0..x1) {
                    val q = (xx + 0.5f - x) / half
                    val cover = ((half - abs(xx + 0.5f - x)) / soft + 0.5f).coerceIn(0f, 1f)
                    if (cover <= 0f) continue
                    val bark = Noise.fbm((xx - x) / (2.2f * d), y / (18f * d), 3, seed)
                    val shade = 0.75f + 0.35f * q.coerceIn(-1f, 1f) + 0.25f * (bark - 0.5f)
                    over(y * w + xx, scale(c, shade), fadeK * cover)
                }
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

        /** Non-premultiplied ARGB; an opaque canvas comes out fully opaque. */
        fun toArgb(): IntArray = IntArray(w * h) { i ->
            val al = a[i]
            if (al <= 0.002f) 0 else {
                val inv = 1f / al
                val cr = (r[i] * inv).coerceIn(0f, 255f).toInt(); val cg = (g[i] * inv).coerceIn(0f, 255f).toInt(); val cb = (b[i] * inv).coerceIn(0f, 255f).toInt()
                ((al * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or (cr shl 16) or (cg shl 8) or cb
            }
        }
    }

    private fun ch(c: Int, shift: Int): Float = ((c shr shift) and 0xFF).toFloat()

    /** [c] with its RGB multiplied by [f] (clamped), opaque. */
    private fun scale(c: Int, f: Float): Int {
        fun s(shift: Int) = (((c shr shift) and 0xFF) * f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (s(16) shl 16) or (s(8) shl 8) or s(0)
    }

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
        fun hash01(x: Int, y: Int, seed: Long): Float {
            var h = x * 374761393 + y * 668265263 + (seed xor (seed ushr 32)).toInt() * 1442695041
            h = (h xor (h ushr 13)) * 1274126177
            return ((h xor (h ushr 16)) and 0xFFFFFF) / 16777215f
        }

        fun value(x: Float, y: Float, seed: Long): Float {
            val xi = floor(x).toInt(); val yi = floor(y).toInt()
            val fx = x - xi; val fy = y - yi
            val sx = fx * fx * (3 - 2 * fx); val sy = fy * fy * (3 - 2 * fy)
            val a = hash01(xi, yi, seed); val b = hash01(xi + 1, yi, seed)
            val c = hash01(xi, yi + 1, seed); val e = hash01(xi + 1, yi + 1, seed)
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

    /** Cellular (Worley) noise: the gap between the nearest two jittered points, 0 on a cell edge. */
    private object Worley {
        fun edge(x: Float, y: Float, seed: Long): Float {
            val xi = floor(x).toInt(); val yi = floor(y).toInt()
            var f1 = Float.MAX_VALUE; var f2 = Float.MAX_VALUE
            for (oy in -1..1) for (ox in -1..1) {
                val cx = xi + ox; val cy = yi + oy
                val px = cx + Noise.hash01(cx, cy, seed); val py = cy + Noise.hash01(cx, cy, seed + 1)
                val dd = (px - x) * (px - x) + (py - y) * (py - y)
                if (dd < f1) { f2 = f1; f1 = dd } else if (dd < f2) f2 = dd
            }
            return sqrt(f2) - sqrt(f1)
        }
    }
}

/**
 * The nature scenes [SceneRaster] paints.
 *
 * @property paintScale view pixels per painted pixel: 1 keeps blades, leaves and stone banding
 *   sharp; 2 for the soft paintings (a quarter of the cost and the memory).
 * @property frameMs time between frames while moving: the sharp, swaying scenes need more frames
 *   than a soft cross-fade for their motion to read as smooth.
 */
enum class SceneKind(val paintScale: Int, val frameMs: Long) {
    MEADOW(1, 83L), STREAM(2, 125L), RIVER_ROCKS(1, 100L), FOREST(1, 83L), SNOW(2, 125L), BEACH(1, 83L)
}
