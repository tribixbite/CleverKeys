package tribixbite.cleverkeys.swipe.geometric

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * TEST-ONLY layout loader: parses the shipped `srcs/layouts` XML files and the committed fixture
 * layouts under `src/test/resources/layouts/` with a plain JDK XML parser
 * (`javax.xml.parsers`), producing a pure [LayoutGeometry]. `KeyboardData` and its
 * Android-bound XML pipeline are left untouched (NFR-3, NON-Goal 5).
 *
 * This carries no `Test` suffix so the `TestRunnerListDriftTest` scanner skips it.
 *
 * ## Semantics faithfully reproduced (so later phases tune on correct centroids)
 *  - Key `key0`/`c` = center (slot 0); compass synonyms `key1..key8` ≡
 *    `nw/ne/sw/se/w/e/n/s` (the exact slot mapping of `KeyboardData.Key.parse`).
 *  - Key `width` default 1.0; key `shift` default 0.0.
 *  - Row `height` default 1.0; row `shift` default 0.0; **row `scale`
 *    renormalization** (`Row.updateWidth`: each key width ×= scale / Σ(width+shift)).
 *  - Backslash-escape stripping (`\@`, `\#`, `\?`, `\\`) and XML entities (handled
 *    natively by the DOM parser).
 *  - `loc ` prefix handling: a `loc ` corner that resolves to a letter enters the
 *    alias table (this is what makes Ukrainian `ї/є/і/ґ` typeable). The
 *    [includeLocCorners] flag models locale resolution — false drops them.
 *  - **Bottom row**: the standard bottom row geometry is appended (as production's
 *    `LayoutModifier` does) so normalized v and aspect match the runtime adapter.
 *
 * Letter rule (Geometry & Script Abstraction): a key is a LETTER NODE iff its
 * center label, after stripping a leading `loc ` and backslash escapes, is 1+
 * codepoints that are ALL `Character.isLetter`. A key with a non-letter center that
 * hosts ≥ 1 letter corner is an ALIAS-HOST-ONLY node (centroid usable via tier 4;
 * center not in `chars`).
 */
object GeoLayoutFixtures {

    /** Default key-area aspect (width/height px). A 10-wide × 4-row keyboard on a
     *  typical portrait phone is close to 2:1; the exact value only scales `d_kw`'s
     *  vertical term and is folded into the fingerprint. */
    const val DEFAULT_ASPECT = 2.0f

    /** Standard bottom row (res/xml/bottom_row.xml), letters-free, widths verbatim.
     *  Total width 1.7+1.1+4.4+1.1+1.7 = 10.0; height 0.95. Appended and rescaled to
     *  the keyboard width by [Builder]-side row-scale handling. */
    private val BOTTOM_ROW_WIDTHS = floatArrayOf(1.7f, 1.1f, 4.4f, 1.1f, 1.7f)
    private const val BOTTOM_ROW_HEIGHT = 0.95f

    /** Project-root-relative path to the shipped layout XMLs. */
    private const val SRC_LAYOUTS = "srcs/layouts"

    /** Project-root-relative path to the committed test fixtures. */
    private const val TEST_LAYOUTS = "src/test/resources/layouts"

    /**
     * Load a shipped layout by base name (e.g. "latn_qwerty_us").
     * @param includeLocCorners whether `loc ` letter corners are resolved into the
     *   alias table (models a locale that keeps them, e.g. Ukrainian on JCUKEN).
     * @param appendBottomRow whether to append the standard bottom row.
     */
    fun loadShipped(
        baseName: String,
        includeLocCorners: Boolean = true,
        appendBottomRow: Boolean = true,
        aspect: Float = DEFAULT_ASPECT,
    ): LayoutGeometry = load(File("$SRC_LAYOUTS/$baseName.xml"), includeLocCorners, appendBottomRow, aspect)

    /** Load a committed test-resource fixture by base name (e.g. "weird_custom"). */
    fun loadFixture(
        baseName: String,
        includeLocCorners: Boolean = true,
        appendBottomRow: Boolean = true,
        aspect: Float = DEFAULT_ASPECT,
    ): LayoutGeometry = load(File("$TEST_LAYOUTS/$baseName.xml"), includeLocCorners, appendBottomRow, aspect)

    /** Parse an arbitrary layout XML file into a [LayoutGeometry]. */
    fun load(
        file: File,
        includeLocCorners: Boolean,
        appendBottomRow: Boolean,
        aspect: Float,
    ): LayoutGeometry {
        require(file.exists()) { "layout XML not found: ${file.absolutePath}" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            // Fixtures are trusted repo files; disable DTD/network for hygiene.
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val doc = factory.newDocumentBuilder().parse(file)
        val keyboard = doc.documentElement
        require(keyboard.tagName == "keyboard") { "root <keyboard> expected in ${file.name}" }

        val builder = LayoutGeometry.Builder(aspect)
        // Only DIRECT <row> children of <keyboard> (getElementsByTagName would also
        // return nested rows, but layouts never nest — still, be explicit).
        val bodyRows = directChildRows(keyboard).map { parseRow(it, includeLocCorners) }
        for (row in bodyRows) builder.addRow(row)

        if (appendBottomRow) {
            // Production `LayoutModifier` inserts the bottom row via
            // `KeyboardData.insert_row`, which rescales the inserted row to the
            // keyboard body width (`row.updateWidth(keysWidth)`) so the bottom row
            // spans the full width. Reproduce that: compute the body width (max row
            // width after each row's own scale) and hand it to the bottom row as its
            // scale, so it lands at the same total width.
            builder.addRow(bottomRow(bodyWidthUnits(bodyRows)))
        }
        return builder.build()
    }

    /** Total keyboard body width = max over rows of Σ(width+shift) after row scale. */
    private fun bodyWidthUnits(rows: List<LayoutGeometry.Builder.RawRow>): Float {
        var maxW = 0f
        for (row in rows) {
            val raw = row.keys.fold(0f) { acc, k -> acc + k.widthUnits + k.shiftUnits }
            val effective = if (row.scaleUnits > 0f) row.scaleUnits else raw
            if (effective > maxW) maxW = effective
        }
        return if (maxW > 0f) maxW else 1f
    }

    /** Direct `<row>` element children of [parent] (excludes deeper descendants). */
    private fun directChildRows(parent: Element): List<Element> {
        val out = ArrayList<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val n = children.item(i)
            if (n is Element && n.tagName == "row") out.add(n)
        }
        return out
    }

    // ── row / key parsing ─────────────────────────────────────────────────────

    private fun parseRow(rowEl: Element, includeLocCorners: Boolean): LayoutGeometry.Builder.RawRow {
        val height = floatAttr(rowEl, "height", 1f).coerceAtLeast(0.5f)
        val shift = floatAttr(rowEl, "shift", 0f).coerceAtLeast(0f)
        val scale = floatAttr(rowEl, "scale", 0f)

        val keys = ArrayList<LayoutGeometry.Builder.RawKey>()
        val children = rowEl.childNodes
        for (k in 0 until children.length) {
            val n = children.item(k)
            if (n is Element && n.tagName == "key") keys.add(parseKey(n, includeLocCorners))
        }
        return LayoutGeometry.Builder.RawRow(keys, height, shift, scale.coerceAtLeast(0f))
    }

    private fun parseKey(keyEl: Element, includeLocCorners: Boolean): LayoutGeometry.Builder.RawKey {
        val width = floatAttr(keyEl, "width", 1f).coerceAtLeast(0f)
        val shift = floatAttr(keyEl, "shift", 0f).coerceAtLeast(0f)

        // Center label: key0 or c (synonyms).
        val rawCenter = attrEither(keyEl, "key0", "c")
        val centerResolved = resolveLabel(rawCenter)  // (text, isLoc)

        // Corner labels: key1..key8 (compass synonyms), collected as candidate aliases.
        val cornerCodepoints = ArrayList<Int>()
        for ((numeric, compass) in CORNER_SLOTS) {
            val raw = attrEither(keyEl, numeric, compass) ?: continue
            val (text, isLoc) = resolveLabel(raw)!!
            if (isLoc && !includeLocCorners) continue
            // Only SINGLE-codepoint all-letter corner labels enter the alias table
            // in v1 (multi-codepoint corners like Arabic لإ are ignored — their
            // component codepoints resolve via their own tiers, per spec).
            if (isSingleLetterCodepoint(text)) {
                cornerCodepoints.add(foldCodepoint(text.codePointAt(0)))
            }
        }

        // Classify the center.
        var isLetterNode = false
        var centerLabel = ""
        val charCps = ArrayList<Int>()
        if (centerResolved != null) {
            val (text, _) = centerResolved
            if (isLetterCenterLabel(text)) {
                isLetterNode = true
                centerLabel = foldLabel(text)
                // tier 1: single-codepoint center; tier 2: each component codepoint
                // of a multi-codepoint all-letter center as a fallback entry.
                var i = 0
                while (i < centerLabel.length) {
                    val cp = centerLabel.codePointAt(i)
                    charCps.add(cp)
                    i += Character.charCount(cp)
                }
            }
        }

        return LayoutGeometry.Builder.RawKey(
            centerLabel = centerLabel,
            widthUnits = width,
            shiftUnits = shift,
            isLetterNode = isLetterNode,
            charCodepoints = charCps.toIntArray(),
            aliasCodepoints = cornerCodepoints.toIntArray(),
        )
    }

    /**
     * The standard bottom row: 5 non-letter keys, no aliases, rescaled to span the
     * keyboard body width [scaleToWidth] (matching `insert_row`'s `updateWidth`).
     */
    private fun bottomRow(scaleToWidth: Float): LayoutGeometry.Builder.RawRow {
        val keys = BOTTOM_ROW_WIDTHS.map { w ->
            LayoutGeometry.Builder.RawKey(
                centerLabel = "",
                widthUnits = w,
                shiftUnits = 0f,
                isLetterNode = false,
                charCodepoints = IntArray(0),
                aliasCodepoints = IntArray(0),
            )
        }
        return LayoutGeometry.Builder.RawRow(keys, BOTTOM_ROW_HEIGHT, 0f, scaleToWidth)
    }

    // ── label / codepoint helpers ─────────────────────────────────────────────

    /** numeric slot name → compass synonym, slots 1..8 (matches KeyboardData.Key.parse). */
    private val CORNER_SLOTS = arrayOf(
        "key1" to "nw", "key2" to "ne", "key3" to "sw", "key4" to "se",
        "key5" to "w", "key6" to "e", "key7" to "n", "key8" to "s",
    )

    /**
     * Resolve a raw XML attribute value into (visibleText, isLoc):
     *  - strips a leading `loc ` prefix (marking the value as a locale hint),
     *  - strips backslash escapes (`\@` → `@`, `\\` → `\`, `\?` → `?`).
     * XML entities (`&amp;`, `&lt;`, …) are already decoded by the DOM parser.
     * Returns null iff [raw] is null.
     */
    internal fun resolveLabel(raw: String?): Pair<String, Boolean>? {
        if (raw == null) return null
        var text = raw
        var isLoc = false
        if (text.startsWith("loc ")) {
            isLoc = true
            text = text.substring(4)
        }
        return stripBackslashEscapes(text) to isLoc
    }

    /** Remove KeyboardData-style backslash escapes: `\c` → `c` for any char c. */
    internal fun stripBackslashEscapes(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                sb.append(s[i + 1]); i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    /**
     * True iff [s] qualifies as a LETTER-NODE center label.
     *
     * Every codepoint must be `Character.isLetter` (excludes digits, punctuation,
     * the Greek layout's `;` key, etc.). Additionally, a multi-character token that
     * is entirely ASCII letters is a **special-key name** (`shift`, `space`, `ctrl`,
     * `fn`, `enter`, `backspace`, `compose`, …) resolved by `KeyValue.getKeyByName`
     * to a Modifier/Event/String key, NOT a character — so it is excluded. Genuine
     * multi-codepoint letter centers (e.g. an Arabic `لا` ligature key or a scripted
     * digraph) contain non-ASCII letters and are kept.
     */
    internal fun isLetterCenterLabel(s: String): Boolean {
        if (!isAllLetter(s)) return false
        if (s.length == 1) return true
        // Multi-char: keep only if it contains a non-ASCII letter (real script
        // digraph); otherwise it is an ASCII special-key identifier.
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp > 0x7F) return true
            i += Character.charCount(cp)
        }
        return false
    }

    /** True iff [s] is 1+ codepoints and every codepoint is `Character.isLetter`. */
    internal fun isAllLetter(s: String): Boolean {
        if (s.isEmpty()) return false
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (!Character.isLetter(cp)) return false
            i += Character.charCount(cp)
        }
        return true
    }

    /** True iff [s] is exactly one codepoint and that codepoint is a letter. */
    private fun isSingleLetterCodepoint(s: String): Boolean {
        if (s.isEmpty()) return false
        val cp = s.codePointAt(0)
        return Character.charCount(cp) == s.length && Character.isLetter(cp)
    }

    /** Per-codepoint `Locale.ROOT` lowercase of a single codepoint. */
    internal fun foldCodepoint(cp: Int): Int {
        // Locale.ROOT lowercasing via String round-trip avoids the Turkish I→ı trap
        // (Character.toLowerCase is locale-independent but does not handle all
        // special-casing; the String path with ROOT is what the spec pins).
        val lowered = String(Character.toChars(cp)).lowercase(Locale.ROOT)
        return lowered.codePointAt(0)
    }

    /** Per-codepoint `Locale.ROOT` lowercase of a whole label. */
    internal fun foldLabel(s: String): String = s.lowercase(Locale.ROOT)

    private fun floatAttr(el: Element, name: String, default: Float): Float {
        val v = el.getAttribute(name)
        return if (v.isNullOrEmpty()) default else v.toFloat()
    }

    private fun attrEither(el: Element, a: String, b: String): String? {
        val va = el.getAttribute(a)
        if (!va.isNullOrEmpty()) return va
        val vb = el.getAttribute(b)
        return if (vb.isNullOrEmpty()) null else vb
    }
}
