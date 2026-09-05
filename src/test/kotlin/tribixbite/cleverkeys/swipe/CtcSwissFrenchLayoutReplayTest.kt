package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assume
import org.junit.Test
import org.w3c.dom.Element
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.KeyboardData
import tribixbite.cleverkeys.a11y.KeyboardGeometry
import tribixbite.cleverkeys.swipe.ctc.CtcLayout

/**
 * Issue-#75 replay instrument + pins: swipe on the Swiss French QWERTZ layout
 * (`latn_qwertz_fr_ch.xml`).
 *
 * ## The report, and why it is architecture-fixed at HEAD
 *
 * Filed 2026-01-18 against v1.2.1: swiping the VISUAL keys y-e-s on the Swiss QWERTZ board
 * produced "Zeal…"-class words, and swiping z-e-s produced "Yes" — i.e. the engine decoded
 * against a hard QWERTY grid, ignoring the displayed layout (y and z are swapped on QWERTZ).
 * That engine was the neural one, deleted 2026-08-18 (ADR-011). The shipping CTC path is
 * layout-agnostic by construction: `CtcEngineAdapter.buildMappedLayout` builds the
 * `CtcLayout` from the ACTUAL displayed board's key rects (`KeyboardGeometry.computeKeyRects`
 * on the live `KeyboardData`), and the key centers are a model INPUT (`CtcFeaturizer` feeds
 * `layout.keyCentersX/Y` to the encoder). A Swiss QWERTZ user's 'z' emission slot sits at the
 * top-row position their 'z' key actually occupies.
 *
 * ## What this class proves
 *
 *  1. [swissLayoutStaysServedAndQwertz] — the board is still in the served catalogue,
 *     declares `script="latin"`, and keeps the QWERTZ signature (z top row / y bottom row)
 *     plus all 26 a–z centre letters, so the CTC layout-alphabet gate accepts it.
 *  2. [swissGeometryBuildsACompleteCtcLayout] — the mirrored `buildMappedLayout` math over
 *     the file's real geometry yields a complete a–z `CtcLayout` (what
 *     `CtcEngineAdapter.supportsLayout` answers true from).
 *  3. [issueGestureAndFrenchWordsDecodeOnSwissGeometry] — the SHIPPED decode stack
 *     re-bound to the Swiss key centers ([CtcReplayEngine.decoderFor]) decodes the
 *     issue's own gesture and common French words at rank 1 ON THE SWISS GEOMETRY:
 *     y-e-s → "yes" (not a z-word), z-e-a-l → "zeal", plus bonjour / merci / oui / jazz.
 *     Measured 2026-09-05: all six rank 1 (see the diagnostic for slates).
 *
 * The engine replays the shipped EN lexicon (the replay harness is en-only); fr shares the
 * same a–z emission alphabet and the same Latin encoder, so the GEOMETRY claim — the only
 * thing #75 is about — transfers. The French-language lexicon path is exercised on-device
 * by the imported-pack instrumented tests, not here.
 */
class CtcSwissFrenchLayoutReplayTest {

    private fun assumeOrt() {
        Assume.assumeTrue(
            "ONNX natives absent — run via gradle so extractOrtNative + onnxruntime.native.path are set",
            CtcReplayEngine.ortAvailable(),
        )
    }

    // ── the shipped file, parsed with a plain JDK DOM (NumpadKeySizeTest idiom) ──────────

    /** `<row>` elements → hand-built [KeyboardData.Row]s mirroring `KeyboardData.parse`'s
     *  width/shift defaults. Single-char centres become real char keys (including è/é/à —
     *  the gate skips non-alphabet letters WITHOUT admitting their rects to the letter box,
     *  same as production); multi-char centres (shift, backspace) become placeholder cells
     *  that advance the x cursor but produce no rect — their WIDTH still displaces the
     *  letters after them, which is exactly why they cannot just be dropped. */
    private fun parseLetterRows(file: File): List<KeyboardData.Row> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val rows = ArrayList<KeyboardData.Row>()
        val rowNodes = doc.documentElement.getElementsByTagName("row")
        for (r in 0 until rowNodes.length) {
            val rowEl = rowNodes.item(r) as Element
            val keys = ArrayList<KeyboardData.Key>()
            val keyNodes = rowEl.getElementsByTagName("key")
            for (k in 0 until keyNodes.length) {
                val keyEl = keyNodes.item(k) as Element
                val width = keyEl.getAttribute("width").ifEmpty { "1" }.toFloat()
                val shift = keyEl.getAttribute("shift").ifEmpty { "0" }.toFloat()
                val centre = keyEl.getAttribute("key0").ifEmpty { keyEl.getAttribute("c") }
                val key =
                    if (centre.length == 1) {
                        KeyboardData.Key.EMPTY
                            .withKeyValue(0, KeyValue.makeCharKey(centre[0]))
                            .let { KeyboardData.Key(it.keys, it.anticircle, 0, width, shift, null) }
                    } else {
                        KeyboardData.Key(List(9) { null }, null, 0, width, shift, null)
                    }
                keys.add(key)
            }
            val height = rowEl.getAttribute("height").ifEmpty { "1" }.toFloat()
            val rshift = rowEl.getAttribute("shift").ifEmpty { "0" }.toFloat()
            rows.add(KeyboardData.Row(keys, height, rshift))
        }
        return rows
    }

    /**
     * Mirror of `CtcEngineAdapter.buildMappedLayout` over the parsed rows: per-letter key
     * centers normalized into the a–z letter-box (the box is built from the ALPHABET keys'
     * rect edges only — accented/utility keys stay outside it, matching production).
     * Returns null when any a–z letter is missing — the same answer `supportsLayout` gives.
     */
    private fun buildSwissCtcLayout(rows: List<KeyboardData.Row>): CtcLayout? {
        // Absolute px scale cancels in the per-axis normalization; any positive params work.
        val params = KeyboardGeometry.Params(
            keyWidth = 100f, rowHeight = 160f, marginTop = 0f, marginLeft = 0f,
        )
        val rects = KeyboardGeometry.computeKeyRects(rows, params)
        val alphabet = CharArray(26) { ('a' + it) }
        val slotOf = alphabet.withIndex().associate { (i, c) -> c to i }
        val k = alphabet.size
        val cx = FloatArray(k); val cy = FloatArray(k); val seen = BooleanArray(k)
        var left = Float.MAX_VALUE; var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE; var bottom = -Float.MAX_VALUE
        for (rect in rects) {
            val letter = KeyLetter.centreLetterOf(rect.kv) ?: continue
            val i = slotOf[letter] ?: continue
            if (seen[i]) continue
            seen[i] = true
            cx[i] = (rect.bounds.left + rect.bounds.right) / 2f
            cy[i] = (rect.bounds.top + rect.bounds.bottom) / 2f
            if (rect.bounds.left < left) left = rect.bounds.left
            if (rect.bounds.top < top) top = rect.bounds.top
            if (rect.bounds.right > right) right = rect.bounds.right
            if (rect.bounds.bottom > bottom) bottom = rect.bounds.bottom
        }
        if (seen.any { !it }) return null
        val w = right - left
        val h = bottom - top
        if (w <= 0f || h <= 0f) return null
        return CtcLayout(
            alphabet.copyOf(),
            FloatArray(k) { (cx[it] - left) / w },
            FloatArray(k) { (cy[it] - top) / h },
        )
    }

    private fun swissLayout(): CtcLayout {
        val layout = buildSwissCtcLayout(parseLetterRows(File(LAYOUT_XML)))
        assertWithMessage(
            "the Swiss French QWERTZ board must expose every a–z letter as a centre key — " +
                "this is the CtcEngineAdapter.supportsLayout gate; null means the CTC engine " +
                "would refuse the board and #75 would need re-triage"
        ).that(layout).isNotNull()
        return layout!!
    }

    /** Straight-line trace through [word]'s centres ON [layout] — the golden-generator
     *  shape (12 steps/segment, 16 ms/step), same as `CtcDecodeReplayTest.straightTrace`. */
    private fun straightTrace(
        word: String,
        layout: CtcLayout,
        stepsPerSegment: Int = 12,
        stepMs: Double = 16.0,
    ): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val cx = DoubleArray(word.length)
        val cy = DoubleArray(word.length)
        for (i in word.indices) {
            val k = layout.alphabet.indexOf(word[i])
            require(k >= 0) { "'${word[i]}' not on layout" }
            cx[i] = layout.keyCentersX[k].toDouble()
            cy[i] = layout.keyCentersY[k].toDouble()
        }
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        val ts = ArrayList<Double>()
        var t = 0.0
        for (i in 0 until word.length - 1) {
            for (s in 0 until stepsPerSegment) {
                val f = s / stepsPerSegment.toDouble()
                xs.add(cx[i] + (cx[i + 1] - cx[i]) * f)
                ys.add(cy[i] + (cy[i + 1] - cy[i]) * f)
                ts.add(t)
                t += stepMs
            }
        }
        xs.add(cx.last()); ys.add(cy.last()); ts.add(t)
        return Triple(xs.toDoubleArray(), ys.toDoubleArray(), ts.toDoubleArray())
    }

    // ── 1. catalogue + QWERTZ-signature pins (keep the board served & meaningful) ────────

    @Test
    fun swissLayoutStaysServedAndQwertz() {
        val xml = File(LAYOUT_XML).readText()
        assertWithMessage("$LAYOUT_XML must declare script=\"latin\" — the router's gate 1")
            .that(xml).contains("script=\"latin\"")

        // The QWERTZ signature the whole issue hinges on: z is a TOP-row centre, y a
        // BOTTOM-row centre. Without it this test would pin a board #75 isn't about.
        val rowBlocks = xml.split("<row>")
        assertThat(rowBlocks.size).isAtLeast(4)
        assertWithMessage("top row must carry key0=\"z\" (QWERTZ)")
            .that(rowBlocks[1]).contains("key0=\"z\"")
        assertWithMessage("bottom letter row must carry key0=\"y\" (QWERTZ)")
            .that(rowBlocks[3]).contains("key0=\"y\"")

        // All 26 letters as CENTRE keys (the supportsLayout precondition, textually).
        for (c in 'a'..'z') {
            assertWithMessage("'$c' must be a centre (key0) value on the Swiss board")
                .that(xml).contains("key0=\"$c\"")
        }

        // Served: registered in the generated catalogue arrays (id list + @raw ref).
        val catalogue = File("res/values/layouts.xml").readText()
        assertWithMessage("latn_qwertz_fr_ch must stay in the layout id catalogue")
            .that(catalogue).contains("<item>latn_qwertz_fr_ch</item>")
        assertWithMessage("latn_qwertz_fr_ch must keep its @raw resource entry")
            .that(catalogue).contains("<item>@raw/latn_qwertz_fr_ch</item>")
    }

    // ── 2. the layout-alphabet gate over the REAL geometry ───────────────────────────────

    @Test
    fun swissGeometryBuildsACompleteCtcLayout() {
        val layout = swissLayout()
        // Spot-check the swap this issue is about, in normalized geometry: z left of the
        // top row (col 6 of 11), y at the bottom row's left edge — NOT the QWERTY spots.
        val z = layout.alphabet.indexOf('z')
        val y = layout.alphabet.indexOf('y')
        assertWithMessage("z must sit on the TOP letter row of the Swiss board")
            .that(layout.keyCentersY[z]).isLessThan(0.34f)
        assertWithMessage("y must sit on the BOTTOM letter row of the Swiss board")
            .that(layout.keyCentersY[y]).isGreaterThan(0.66f)
        assertWithMessage("y must be LEFT of z horizontally on QWERTZ (y bottom-left, z top-middle)")
            .that(layout.keyCentersX[y]).isLessThan(layout.keyCentersX[z])
    }

    // ── 3. the shipped decoder on the Swiss geometry (the issue's own gesture) ───────────

    @Test
    fun issueGestureAndFrenchWordsDecodeOnSwissGeometry() {
        assumeOrt()
        val swiss = swissLayout()
        CtcReplayEngine.build("en").use { engine ->
            val decoder = engine.decoderFor(swiss)
            println("[75] EP=${CtcReplayEngine.executionProvider}")

            for (word in listOf("yes", "zeal", "bonjour", "merci", "oui", "jazz")) {
                val (x, y, t) = straightTrace(word, swiss)
                val slate = decoder.decode(x, y, t).map { it.word }
                println("[75] '$word' on swiss geometry → $slate")
                assertWithMessage(
                    "#75 REGRESSION: '$word' swiped over the Swiss QWERTZ board's real key " +
                        "centers must decode at rank 1 (the engine must follow the DISPLAYED " +
                        "layout, not a QWERTY grid) — slate was $slate"
                ).that(slate.firstOrNull()).isEqualTo(word)
            }

            // The issue's exact symptom, inverted: the y-e-s gesture must NOT come back as
            // a z- word (that was the hard-QWERTY-grid decode of the deleted engine).
            val (x, y, t) = straightTrace("yes", swiss)
            val top = decoder.decode(x, y, t).first().word
            assertWithMessage("y-e-s on Swiss geometry must not decode as a z- word")
                .that(top.startsWith("z")).isFalse()

            // Diagnostic contrast (not asserted — documents the deleted engine's failure
            // shape): the SAME physical trace pushed through the GOLDEN-QWERTY-bound
            // decoder, i.e. what ignoring the displayed layout used to do.
            val goldenSlate = engine.decode(x, y, t).words
            println("[75] same trace on QWERTY golden geometry → $goldenSlate (old-bug shape)")
        }
    }

    private companion object {
        const val LAYOUT_XML = "src/main/layouts/latn_qwertz_fr_ch.xml"
    }
}
