package tribixbite.cleverkeys.swipe

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlin.math.abs
import org.junit.AfterClass
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import tribixbite.cleverkeys.a11y.KeyboardGeometry
import tribixbite.cleverkeys.swipe.ctc.CtcAzProjection
import tribixbite.cleverkeys.swipe.ctc.CtcCkdtLexicon
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconMerge
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconTrie
import tribixbite.cleverkeys.swipe.ctc.CtcAlternateForm
import tribixbite.cleverkeys.swipe.ctc.CtcScoringParams
import tribixbite.cleverkeys.swipe.ctc.CtcSurfaceForms
import tribixbite.cleverkeys.swipe.ctc.CtcSwipeDecoder
import tribixbite.cleverkeys.swipe.geometric.CkdtDictionaryReader
import tribixbite.cleverkeys.swipe.geometric.GeoIdealTrace
import tribixbite.cleverkeys.swipe.geometric.GeoLayoutFixtures
import tribixbite.cleverkeys.swipe.geometric.GeometricEngineConfig
import tribixbite.cleverkeys.swipe.geometric.GeometricSwipeEngine
import tribixbite.cleverkeys.swipe.geometric.TemplateGenerator
import tribixbite.cleverkeys.swipe.geometric.GeoTestFixtures

/**
 * Accented letters that are TOP-LEVEL keys (maintainer report 2026-10-25: on bépo, swiping
 * `été` over the `é` key gave `tp`, while swiping `ete` over the `e` key gave `été`).
 *
 * The CTC emission alphabet is a–z, so the `é` key had no slot: the encoder saw a path over a
 * spot where no key existed and read the nearest a–z keys instead (`p`/`b`/`u` around bépo's
 * `é`). On boards whose accented keys sit OUTSIDE the a–z keys' box (QWERTZ `ü` right of `p`,
 * `ö ä` right of `l`; Swedish `å ö ä`; Spanish `ñ`) the path was additionally clipped to the
 * box edge. The fix ([CtcMappedLayoutBuilder]) gives each such key an ALIAS slot at its own
 * centre, folds the alias's emission into its base letter's column, and builds the model frame
 * from every letter key. Evidence on REAL human traces: see `docs/eval/2026-10-25-ctc-accent-keys.md`.
 *
 * This class decodes straight synthetic traces through the SHIPPED stack (real ONNX encoder,
 * real CKDT lexicon projected as `CtcEngineAdapter.lexiconFor` does, shipped `presetFor`
 * preset, the production layout builder) over each shipped layout file's real key centres,
 * passing over the accented KEY where the word has one. Synthetic traces are a pin, not
 * ranking evidence.
 */
class CtcAccentKeyLayoutReplayTest {

    private class Lexicon(
        val trie: CtcLexiconTrie,
        val display: Map<String, String>,
        val alternates: Map<String, Array<CtcAlternateForm>>,
    )

    private companion object {
        val AZ = CharArray(26) { 'a' + it }
        const val MODEL = "src/main/assets/models/ctc_swipe_encoder.onnx"
        val PARAMS = KeyboardGeometry.Params(keyWidth = 100f, rowHeight = 160f, marginTop = 0f, marginLeft = 0f)

        var env: OrtEnvironment? = null
        var session: OrtSession? = null
        val lexicons = HashMap<String, Lexicon>()

        /** Common Danish words with `æ`, at mid custom-word frequency (see [danishSwipesOverTheAeKey]). */
        val DANISH_AE = listOf("være" to 200, "næste" to 180, "læse" to 160, "æble" to 140, "hæld" to 120)

        @BeforeClass
        @JvmStatic
        fun setUp() {
            if (!CtcReplayEngine.ortAvailable()) return
            env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setIntraOpNumThreads(2)
            session = env!!.createSession(File(MODEL).readBytes(), options)
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            runCatching { session?.close() }
        }

        /** The CKDT branch of `CtcEngineAdapter.lexiconFor`, without user words. */
        fun lexicon(lang: String): Lexicon = lexicons.getOrPut(lang) { lexiconOf(lang, emptyList()) }

        /**
         * [lexicon] with [extra] words merged in the way custom words are (calibrated onto the
         * base scale). Used to give the Swedish base a handful of Danish `æ` words: no Danish
         * dictionary ships, so that board has no real lexicon to decode against.
         */
        fun lexiconOf(lang: String, extra: List<Pair<String, Int>>): Lexicon {
            val entries = File("src/main/assets/dictionaries/${lang}_enhanced.bin").inputStream()
                .use { CkdtDictionaryReader.readEntries(it) }
            val merged = CtcLexiconMerge.merge(CtcCkdtLexicon.frequencyPairs(entries), extra, emptySet())
            val projected = CtcAzProjection.projectLexicon(merged)
            return Lexicon(CtcLexiconTrie.loadFromFrequencyMap(AZ, projected.freqs), projected.display, projected.alternates)
        }
    }

    private class Board(val rects: List<KeyboardGeometry.KeyRect>, val built: CtcMappedLayoutBuilder.Built)

    private fun board(layoutFile: String): Board {
        val rects = KeyboardGeometry.computeKeyRects(LayoutXmlRows.parse(File("src/main/layouts/$layoutFile")), PARAMS)
        val built = CtcMappedLayoutBuilder.build(rects, AZ, CtcAzProjection::project)
        assertWithMessage("$layoutFile must stay CTC-eligible (all a–z centre keys)").that(built).isNotNull()
        return Board(rects, built!!)
    }

    /**
     * Straight trace through the centres of the keys the user actually touches for [word]: the
     * key whose centre value IS the character (`é` on bépo), else the a–z key of its projection
     * (`é` on QWERTY is typed on `e`). 12 steps per segment, 16 ms per step.
     */
    private fun trace(word: String, board: Board): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val b = board.built
        val pts = word.lowercase().map { ch ->
            val rect = board.rects.firstOrNull { KeyLetter.centreLetterOf(it.kv) == ch }
                ?: board.rects.first { KeyLetter.centreLetterOf(it.kv) == CtcAzProjection.project(ch.toString())!!.single() }
            val x = ((rect.bounds.left + rect.bounds.right) / 2f - b.originX) * b.invW
            val y = ((rect.bounds.top + rect.bounds.bottom) / 2f - b.originY) * b.invH
            x.toDouble() to y.toDouble()
        }
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val ts = ArrayList<Double>()
        var t = 0.0
        for (i in 0 until pts.size - 1) {
            for (s in 0 until 12) {
                val f = s / 12.0
                xs.add(pts[i].first + (pts[i + 1].first - pts[i].first) * f)
                ys.add(pts[i].second + (pts[i + 1].second - pts[i].second) * f)
                ts.add(t); t += 16.0
            }
        }
        xs.add(pts.last().first); ys.add(pts.last().second); ts.add(t)
        return Triple(xs.toDoubleArray(), ys.toDoubleArray(), ts.toDoubleArray())
    }

    /**
     * Decoded slate in DISPLAY form (the canonical accented word the user sees), through the
     * shipped [CtcSurfaceForms.expand] — every canonical form of a decoded surface, as
     * `CtcEngineAdapter.decodeAsync` builds it (the fuzzy rescue, which only fills spare slots,
     * is left out).
     */
    private fun slate(word: String, board: Board, lang: String, lex: Lexicon = lexicon(lang)): List<String> {
        val params = CtcScoringParams.presetFor(lang, topK = 8)
        val decoder = CtcSwipeDecoder(OnnxCtcEmissionModel(env!!, session!!), board.built.layout, lex.trie, params)
        val (x, y, t) = trace(word, board)
        return CtcSurfaceForms.expand(decoder.decode(x, y, t), lex.display, lex.alternates, params.lambda, 8)
            .map { it.word }
    }

    /**
     * The geometric engine on the same board: its ideal trace runs over the key each letter
     * projects to (`LayoutProjection` tier 1 — the `é` KEY on bépo), so this is the same gesture.
     * Reported, not asserted: geometric was already correct here (it maps é as a key of its own).
     */
    private fun geometricSlate(word: String, layoutFile: String, lang: String): List<String> {
        val dict = when (lang) {
            "fr" -> GeoTestFixtures.frenchCkdt()
            "de" -> GeoTestFixtures.germanCkdt()
            "es" -> GeoTestFixtures.spanishCkdt()
            else -> return emptyList()
        }
        val config = GeometricEngineConfig()
        val layout = GeoLayoutFixtures.loadShipped(layoutFile.removeSuffix(".xml"))
        val trace = GeoIdealTrace.of(word, layout, TemplateGenerator(config)) ?: return listOf("<untypeable>")
        val engine = GeometricSwipeEngine(config)
        engine.warmUp(layout, dict)
        return engine.decode(GeoIdealTrace.request(trace, layout, dict)).words.take(5)
    }

    /**
     * Every word must reach the top 3 (the visible bar) and [rankOne] words rank 1. Top 3 rather
     * than rank 1 across the board because a straight synthetic line is not a human trace: on
     * bépo it reads `était` as `état` first (both pass over the same keys), a ranking question
     * this fix does not touch.
     */
    private fun assertDecodes(
        layoutFile: String,
        lang: String,
        words: List<String>,
        rankOne: Set<String>,
        lex: Lexicon? = null,
        expected: (String) -> String = { it },
        within: Int = 3,
    ) {
        Assume.assumeTrue("ONNX natives absent — run via gradle runPureTests", session != null)
        val board = board(layoutFile)
        val failures = ArrayList<String>()
        for (w in words) {
            val got = if (lex == null) slate(w, board, lang) else slate(w, board, lang, lex)
            val want = expected(w)
            println("[accent-keys] $layoutFile/$lang '$w' → CTC $got | geometric ${geometricSlate(w, layoutFile, lang)}")
            val rank = got.indexOf(want)
            if (rank !in 0 until within || (w in rankOne && rank != 0)) failures += "'$w' (want '$want') → $got"
        }
        assertWithMessage(
            "$layoutFile ($lang): a swipe over the accented KEY must decode the accented word " +
                "(top $within; rank 1 for $rankOne)"
        ).that(failures).isEmpty()
    }

    @Test
    fun bepoSwipesOverTheEAcuteKey() = assertDecodes(
        "latn_bepo_fr.xml", "fr", listOf("été", "écrit", "idée", "était", "bonjour", "merci"),
        // `merci` is second behind `mère` on this straight line before and after the fix.
        rankOne = setOf("été", "bonjour"),
    )

    @Test
    fun qwertzGermanSwipesOverTheUmlautKeys() = assertDecodes(
        "latn_qwertz_de.xml", "de", listOf("für", "über", "können", "müssen", "hören", "danke"),
        rankOne = setOf("danke"),
    )

    /**
     * Surface collisions (known issue fixed 2026-11-18): `schön` projects to the same a–z
     * surface as the more frequent `schon` (`würde`/`wurde`, `hätte`/`hatte`, `wäre`/`ware` the
     * same), so the trie holds that surface once. [CtcSurfaceForms.expand] offers the less
     * frequent form beside the winner; before, only the winner could ever be swiped. Swiped
     * over the umlaut key or over the base letter, the path is the same surface.
     */
    @Test
    fun qwertzGermanOffersBothFormsOfACollidingSurface() = assertDecodes(
        "latn_qwertz_de.xml", "de", listOf("schön", "würde", "hätte", "schon", "wurde", "hatte"),
        // The more frequent form of each pair keeps rank 1.
        rankOne = setOf("schon", "wurde", "hatte"),
    )

    /**
     * `ß` as a CENTRE key (Neo 2, Bone): one key, the `ss` of the a–z surface (`straße` projects
     * to `strasse`), so the key is an alias of `s` and the beam takes the second `s` from a later
     * frame over the same key. Before 2026-11-18 the key had no slot at all and a path over it
     * read the nearest a–z keys. Until 2026-11-25 the German dictionary had no `ß` word
     * (wordfreq casefolds it), so the slate showed `strasse`; it now shows Germany's spelling.
     */
    @Test
    fun neo2SwipesOverTheSharpSKey() = assertDecodes(
        "latn_neo2.xml", "de", listOf("straße", "große", "weiß", "heißen"),
        rankOne = emptySet(),
    )

    @Test
    fun boneSwipesOverTheSharpSKey() = assertDecodes(
        "latn_bone.xml", "de", listOf("straße", "große", "weiß", "heißen"),
        rankOne = emptySet(),
    )

    /**
     * Danish `æ` as a centre key: the lexicon spells it `ae`, so the key folds into BOTH `a` and
     * `e` ([CtcLayout.aliasSecondTargets]). No Danish dictionary ships, so the words are merged
     * into the Swedish one as custom words, and common Swedish words (`var`, `vår`, `hos`) outrank
     * them — so this asserts the 8-word slate, not the bar: a pin of the key mapping, not ranking
     * evidence. Before the fix none of these words reached the slate at all.
     *
     * Not `æble`: on this straight synthetic line the path leaves the word-initial `æ` key at
     * once, and `ae` needs two frames on that key (one per letter); it decodes `anar`/`annat`
     * before and after. A real swipe starts with a short dwell; unmeasured, no corpus.
     */
    @Test
    fun danishSwipesOverTheAeKey() = assertDecodes(
        "latn_qwerty_da.xml", "sv", DANISH_AE.map { it.first }.filter { it != "æble" },
        rankOne = emptySet(),
        lex = lexiconOf("sv", DANISH_AE),
        within = 8,
    )

    /** The same `æ` words on the Icelandic board, whose `æ` key is written `c="æ"`. */
    @Test
    fun icelandicSwipesOverTheAeKey() = assertDecodes(
        "latn_qwerty_is.xml", "sv", DANISH_AE.map { it.first }.filter { it != "æble" },
        rankOne = emptySet(),
        lex = lexiconOf("sv", DANISH_AE),
        within = 8,
    )

    @Test
    fun swedishSwipesOverTheRingAndUmlautKeys() = assertDecodes(
        "latn_qwerty_se.xml", "sv", listOf("på", "även", "där", "också", "hej"),
        rankOne = setOf("hej"),
    )

    @Test
    fun spanishSwipesOverTheEnyeKey() = assertDecodes(
        "latn_qwerty_es.xml", "es", listOf("año", "niño", "mañana", "hola"),
        rankOne = setOf("hola"),
    )

    @Test
    fun swissFrenchSwipesOverItsAccentKeys() = assertDecodes(
        "latn_qwertz_fr_ch.xml", "fr", listOf("été", "déjà", "très", "bonjour"),
        rankOne = setOf("été", "bonjour"),
    )

    /**
     * Two-letter and doubled-letter keys get an alias slot (2026-11-18): `æ` folds into `a` and
     * `e`, `ß` into `s` only (its two letters are the same). Pure — no model needed.
     */
    @Test
    fun twoLetterKeysGetAliasSlotsFoldedIntoEachLetter() {
        /** The letters [letter]'s alias slot folds into, or null when the key has no slot. */
        fun folds(layoutFile: String, letter: Char): List<Char>? {
            val rects = KeyboardGeometry.computeKeyRects(LayoutXmlRows.parse(File("src/main/layouts/$layoutFile")), PARAMS)
            val built = CtcMappedLayoutBuilder.build(rects, AZ, CtcAzProjection::project)!!
            val layout = built.layout
            val rect = rects.first { KeyLetter.centreLetterOf(it.kv) == letter }
            val x = ((rect.bounds.left + rect.bounds.right) / 2f - built.originX) * built.invW
            val y = ((rect.bounds.top + rect.bounds.bottom) / 2f - built.originY) * built.invH
            val j = layout.aliasTargets.indices.firstOrNull {
                abs(layout.aliasCentersX[it] - x) < 1e-5f && abs(layout.aliasCentersY[it] - y) < 1e-5f
            } ?: return null
            val second = layout.aliasSecondTargets.getOrElse(j) { -1 }
            return listOfNotNull(layout.alphabet[layout.aliasTargets[j]], layout.alphabet.getOrNull(second))
        }
        assertWithMessage("Danish æ").that(folds("latn_qwerty_da.xml", 'æ')).isEqualTo(listOf('a', 'e'))
        assertWithMessage("Icelandic æ").that(folds("latn_qwerty_is.xml", 'æ')).isEqualTo(listOf('a', 'e'))
        assertWithMessage("Neo 2 ß").that(folds("latn_neo2.xml", 'ß')).isEqualTo(listOf('s'))
        assertWithMessage("Bone ß").that(folds("latn_bone.xml", 'ß')).isEqualTo(listOf('s'))
    }

    /** A board without accented centre keys keeps exactly the a–z layout it had before. */
    @Test
    fun anAzOnlyBoardGetsNoAliasesAndAnUnchangedFrame() {
        val rects = KeyboardGeometry.computeKeyRects(LayoutXmlRows.parse(File("src/main/layouts/latn_qwerty_us.xml")), PARAMS)
        val plain = CtcMappedLayoutBuilder.build(rects, AZ)!!
        val aliased = CtcMappedLayoutBuilder.build(rects, AZ, CtcAzProjection::project)!!
        assertWithMessage("alias slots on QWERTY").that(aliased.layout.aliasTargets.size).isEqualTo(0)
        assertWithMessage("x centres").that(aliased.layout.keyCentersX.toList()).isEqualTo(plain.layout.keyCentersX.toList())
        assertWithMessage("y centres").that(aliased.layout.keyCentersY.toList()).isEqualTo(plain.layout.keyCentersY.toList())
        assertWithMessage("origin").that(listOf(aliased.originX, aliased.originY, aliased.invW, aliased.invH))
            .isEqualTo(listOf(plain.originX, plain.originY, plain.invW, plain.invH))
    }
}
