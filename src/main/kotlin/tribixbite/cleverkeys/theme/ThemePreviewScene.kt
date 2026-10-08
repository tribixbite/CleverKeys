package tribixbite.cleverkeys.theme

/**
 * Pure (Android-free) scene plan for the DIY theme-editor keyboard preview (roadmap §4.1).
 *
 * The preview renders the REAL keyboard ([tribixbite.cleverkeys.Keyboard2View]) and
 * suggestion bar; this object only decides what representative state they show:
 *  - which modifiers are put in which state (Shift latched → the ACTIVATED key frame,
 *    a second modifier locked → the LOCKED frame; modifiers/action keys at rest show the
 *    MODIFIER/SPECIAL frames on their own),
 *  - the static sample swipe trail through the sample word's letter keys,
 *  - the trail effect used when the user's own trail setting would hide the trail.
 *
 * Kept free of android.* types so it is testable on the pure JVM tier (an Android type in
 * a signature can fail class loading there — see CLAUDE.md "Pure helpers beside Android
 * views").
 */
object ThemePreviewScene {

    /**
     * Modifier names tried, in order, for the LOCKED sample key. The default bottom row
     * carries `ctrl`; custom layouts may only carry one of the others. Shift is excluded:
     * it is the ACTIVATED (latched) sample.
     */
    val LOCKED_MODIFIER_CANDIDATES: List<String> = listOf("ctrl", "fn", "alt", "meta")

    /** Effect drawn when the user's trail is disabled or set to "none" (invisible). */
    const val FALLBACK_TRAIL_EFFECT = "solid"

    /** Interpolated samples per key-to-key segment (sparkle draws per sample). */
    const val SAMPLES_PER_SEGMENT = 8

    /** Maximum sample suggestions shown in the preview suggestion bar. */
    const val MAX_SAMPLE_WORDS = 3

    /**
     * The trail effect the preview draws. The preview exists to judge the trail COLOUR,
     * so a trail the user disabled — or styled "none", which paints with alpha 0 — is
     * still drawn, with [FALLBACK_TRAIL_EFFECT]; every other effect is kept as configured.
     */
    fun previewTrailEffect(trailEnabled: Boolean, effect: String): String =
        if (!trailEnabled || effect == "none") FALLBACK_TRAIL_EFFECT else effect

    /**
     * Parse the localized comma-separated sample-word resource into at most
     * [MAX_SAMPLE_WORDS] non-blank words.
     */
    fun sampleWords(raw: String): List<String> =
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_SAMPLE_WORDS)

    /**
     * Static sample trail through the letter keys of [word], as (x, y) points in keyboard
     * view coordinates.
     *
     * [keyCenters] maps lowercase letters to key centres (Keyboard2View.getRealKeyPositions).
     * Letters missing from the layout are skipped and repeated consecutive letters collapse
     * (a real swipe dwells rather than doubling back). When fewer than two keys remain —
     * a sample word in a script the layout does not carry — the trail falls back to the
     * left-most, middle and right-most available letter keys so a trail is still shown.
     * Each segment is linearly interpolated with [samplesPerSegment] points so per-sample
     * effects (sparkle) read like a real trail. Returns an empty list when the layout has
     * fewer than two letter keys.
     */
    fun trailPoints(
        word: String,
        keyCenters: Map<Char, Pair<Float, Float>>,
        samplesPerSegment: Int = SAMPLES_PER_SEGMENT,
    ): List<Pair<Float, Float>> {
        require(samplesPerSegment >= 1) { "samplesPerSegment must be >= 1" }
        val anchors = ArrayList<Pair<Float, Float>>()
        var previous: Char? = null
        for (c in word.lowercase()) {
            if (c == previous) continue
            previous = c
            keyCenters[c]?.let { anchors.add(it) }
        }
        if (anchors.size < 2) {
            anchors.clear()
            val byX = keyCenters.values.sortedBy { it.first }
            if (byX.size < 2) return emptyList()
            anchors.add(byX.first())
            if (byX.size >= 3) anchors.add(byX[byX.size / 2])
            anchors.add(byX.last())
        }
        val points = ArrayList<Pair<Float, Float>>((anchors.size - 1) * samplesPerSegment + 1)
        points.add(anchors[0])
        for (i in 1 until anchors.size) {
            val (x0, y0) = anchors[i - 1]
            val (x1, y1) = anchors[i]
            for (s in 1..samplesPerSegment) {
                val t = s.toFloat() / samplesPerSegment
                points.add((x0 + (x1 - x0) * t) to (y0 + (y1 - y0) * t))
            }
        }
        return points
    }
}
