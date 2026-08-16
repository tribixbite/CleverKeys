package tribixbite.cleverkeys.swipe

import android.content.Context
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.KeyboardData
import tribixbite.cleverkeys.PredictionResult
import tribixbite.cleverkeys.TestConfigHelper
import tribixbite.cleverkeys.a11y.KeyboardGeometry
import tribixbite.cleverkeys.swipe.ctc.CtcAzProjection
import tribixbite.cleverkeys.swipe.ctc.CtcCkdtLexicon
import tribixbite.cleverkeys.swipe.ctc.CtcLanguageSupport
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconMerge
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconTrie
import tribixbite.cleverkeys.swipe.ctc.CtcScoringParams
import tribixbite.cleverkeys.swipe.geometric.CkdtDictionaryReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device coverage for the non-English CTC swipe path (fr / de / es), enabled by
 * `feat(ctc): enable French, German and Spanish` — everything that commit shipped was
 * validated OFFLINE (repo-root `File` reads in `runPureTests`, plus the λ sweep), so this
 * class closes the four gaps only a real device can close:
 *
 *  1. **The lexicon really builds from the packaged APK assets.** The pure
 *     `CtcCkdtLexiconTest` reads the `src/main/assets/dictionaries` bins off the filesystem;
 *     here the same three dictionaries come out of `Context.getAssets()` (the APK), are
 *     parsed by the SHIPPING [CkdtDictionaryReader] on the device's JVM, and are projected
 *     by [CtcAzProjection] — then cross-checked against what
 *     [CtcEngineAdapter.trieFor] independently produced through the production merge path.
 *     Asset packaging, aapt compression, `ByteBuffer` behaviour and the projection all
 *     have to agree or the counts move.
 *  2. **The accent round trip actually reaches the user.** A decoded a–z surface
 *     ("francais") must be presented as its canonical accented form ("français") by the
 *     adapter's display overlay — asserted through the real `decodeAsync` slate, not
 *     through the projection map alone.
 *  3. **The per-language preset the adapter selects.** λ = 4.0 for the en JSON scale,
 *     λ = 2.0 for the CKDT scale ([CtcScoringParams.presetFor]).
 *  4. **The stale-memo hazard.** The trie/decoder memos are single-slot and keyed by
 *     language; a switch must REBUILD rather than silently decode the new language against
 *     the old language's trie and λ. Until now that was pinned only by a source-scan drift
 *     test — this exercises a real instantiated adapter.
 *
 * ### Why `latn_qwerty_us` and not azerty/qwertz
 * The shipped encoder is layout-agnostic (it consumes key CENTERS, and the router's gate
 * was widened to any Latin layout), so the layout is free. The bundled US QWERTY's letter
 * keys normalize — over the letter-key bounding box, which is exactly what
 * `CtcEngineAdapter` does — onto the SAME centers as the golden fixture's canonical
 * keyboard (`ctc/ctc_golden.json`: a=(0.10,0.50), e=(0.25,0.167), b=(0.60,0.833) …). That
 * makes the expected decodes below reproducible offline against the same ONNX graph, which
 * is how the target words in [TARGETS] were chosen: each is rank-1 with a wide margin on a
 * straight-line trace, so a failure here means a real regression, not fixture luck.
 *
 * Traces are straight-line interpolations through letter-key centers at 12 steps/segment —
 * the same synthetic shape the golden fixture's `model_cat` / `model_keyboard` cases use
 * (and which that model decodes correctly), and the same generator
 * `GeometricSwipeOracleTest` uses for the geometric engine.
 *
 * No preferences are written: every assertion reads bundled assets and pure state, so
 * there is nothing to leak into the next test's process.
 */
@RunWith(AndroidJUnit4::class)
class CtcMultiLanguageInstrumentedTest {

    /** Frozen shape of one language's a–z projection over its bundled CKDT dictionary. */
    private data class LexiconShape(
        val records: Int,
        val untypeable: Int,
        val words: Int,
        val collisions: Int,
    )

    /**
     * A word whose trie PATH is a–z but whose display form carries accents — the pair the
     * round trip has to preserve end to end.
     */
    private data class AccentTarget(val surface: String, val canonical: String)

    private companion object {
        const val TAG = "CtcMultiLang"

        /** Bundled US QWERTY — see the class KDoc for why the layout choice is free. */
        const val LAYOUT = "latn_qwerty_us"
        const val FRAME_W = 1080f
        const val FRAME_H = 640f

        /** The languages `67f57e1a` enabled; en is covered by the existing CTC tests. */
        val CKDT_LANGUAGES = listOf("fr", "de", "es")

        /**
         * Projection shapes measured on the shipped dictionaries (the same numbers the λ
         * sweep harness reported and `CtcCkdtLexiconTest` pins off the filesystem). A
         * dictionary regeneration, a packaging change or a projection-policy edit moves
         * these — which is the point.
         */
        val SHAPES = mapOf(
            "fr" to LexiconShape(records = 40_000, untypeable = 31, words = 37_949, collisions = 2_020),
            "de" to LexiconShape(records = 40_000, untypeable = 0, words = 39_594, collisions = 406),
            "es" to LexiconShape(records = 50_000, untypeable = 0, words = 47_955, collisions = 2_045),
        )

        /**
         * Decode targets: long, geometrically distinctive paths with no repeated adjacent
         * letter, each the top-1 beam result offline at the shipped preset (λ 2.0,
         * beam 100) on the canonical QWERTY geometry, and each carrying an accent so the
         * display overlay is exercised by the assertion itself.
         */
        val TARGETS = mapOf(
            "fr" to AccentTarget("francais", "français"),
            "de" to AccentTarget("wahrend", "während"),
            "es" to AccentTarget("tambien", "también"),
        )

        /**
         * Words present in `en_enhanced.json` and absent from every bundled CKDT lexicon —
         * the probe that proves a language switch really swapped tries (a stale en memo
         * serving "fr" would still contain them).
         */
        val EN_ONLY_PROBES = listOf("wednesday", "strength", "neighbour")

        /**
         * `en_enhanced.json` yields 98,081 distinct a–z surfaces. A LOWER BOUND, not an
         * equality: other instrumented tests legitimately add en custom words
         * (`TypingSimulationTest`, `AutocorrectTest`), which the adapter merges in.
         */
        const val EN_TRIE_MIN_WORDS = 90_000

        /** Beam-search step count per straight segment (golden-fixture generator shape). */
        const val TRACE_STEPS_PER_SEGMENT = 12

        /** ~60 Hz sample spacing, matching the golden fixture's traces. */
        const val TRACE_STEP_MS = 16L
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // decodeAsync reads `ctc_beam_width` off the global Config; without it every
        // decode would fail into the adapter's empty-result path.
        assertTrue(
            "Config must initialize — the adapter reads ctc_beam_width from it",
            TestConfigHelper.ensureConfigInitialized(context)
        )
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────

    /**
     * The a–z projection of [language]'s bundled CKDT dictionary, read out of the APK
     * (`targetContext.assets`) through the exact chain `CtcEngineAdapter.lexiconFor` runs:
     * [CkdtDictionaryReader] → [CtcCkdtLexicon] → [CtcLexiconMerge] → [CtcAzProjection],
     * with no user words (the merge's identity case).
     */
    private fun projectionFor(language: String): CtcAzProjection.Projected {
        val asset = CtcLanguageSupport.assetFor(language)
        assertEquals(
            "$language must resolve to its bundled CKDT asset",
            "dictionaries/${language}_enhanced.bin", asset
        )
        val entries = context.assets.open(asset!!).use { CkdtDictionaryReader.readEntries(it) }
        val merged = CtcLexiconMerge.merge(
            CtcCkdtLexicon.frequencyPairs(entries), emptyList(), emptySet()
        )
        return CtcAzProjection.projectLexicon(merged)
    }

    /** Layout XML ships as a RAW resource (build.gradle copies `src/main/layouts` into raw). */
    private fun loadLayout(layoutName: String): KeyboardData {
        val id = context.resources.getIdentifier(layoutName, "raw", context.packageName)
        assertTrue("layout resource $layoutName must exist", id != 0)
        val kd = KeyboardData.load(context.resources, id)
        assertNotNull("layout $layoutName must parse", kd)
        return kd!!
    }

    private fun paramsFor(kd: KeyboardData) = KeyboardGeometry.Params(
        keyWidth = FRAME_W / kd.keysWidth,
        rowHeight = FRAME_H / kd.keysHeight,
        marginTop = 0f,
        marginLeft = 0f,
    )

    /** Center of the letter key whose primary value is [c], in frame pixels. */
    private fun letterCenter(kd: KeyboardData, params: KeyboardGeometry.Params, c: Char): PointF {
        val rect = KeyboardGeometry.computeKeyRects(kd, params).firstOrNull { r ->
            r.kv.getKind() == KeyValue.Kind.Char && r.kv.getChar().lowercaseChar() == c
        } ?: throw AssertionError("no key for '$c' on $LAYOUT")
        return PointF(
            (rect.bounds.left + rect.bounds.right) / 2f,
            (rect.bounds.top + rect.bounds.bottom) / 2f,
        )
    }

    /** Straight-line trace through the letter-key centers of [word] (fixture shape). */
    private fun traceFor(
        kd: KeyboardData,
        params: KeyboardGeometry.Params,
        word: String,
    ): Pair<List<PointF>, List<Long>> {
        val centers = word.map { letterCenter(kd, params, it) }
        val points = ArrayList<PointF>()
        val times = ArrayList<Long>()
        var t = 0L
        for (i in 0 until centers.size - 1) {
            val a = centers[i]
            val b = centers[i + 1]
            for (s in 0 until TRACE_STEPS_PER_SEGMENT) {
                val f = s / TRACE_STEPS_PER_SEGMENT.toFloat()
                points.add(PointF(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f))
                times.add(t)
                t += TRACE_STEP_MS
            }
        }
        points.add(centers.last())
        times.add(t)
        return points to times
    }

    private fun onMain(block: () -> Unit) {
        val latch = CountDownLatch(1)
        var thrown: Throwable? = null
        Handler(Looper.getMainLooper()).post {
            try { block() } catch (t: Throwable) { thrown = t } finally { latch.countDown() }
        }
        assertTrue("main-thread block must run", latch.await(10, TimeUnit.SECONDS))
        thrown?.let { throw it }
    }

    /** Full production decode of [word]'s trace in [language], blocking for the slate. */
    private fun decodeBlocking(
        adapter: CtcEngineAdapter,
        kd: KeyboardData,
        params: KeyboardGeometry.Params,
        word: String,
        language: String,
    ): PredictionResult {
        val (path, times) = traceFor(kd, params, word)
        var result: PredictionResult? = null
        val latch = CountDownLatch(1)
        onMain {
            adapter.decodeAsync(kd, params, FRAME_W, FRAME_H, path, times, language) {
                result = it
                latch.countDown()
            }
        }
        assertTrue(
            "$language: decode of '$word' must complete",
            latch.await(60, TimeUnit.SECONDS)
        )
        return result!!
    }

    /**
     * Every word the adapter hands the pipeline must be a real lexicon word — its a–z
     * projection has to be a path in [trie] — and none of them may be a BARE surface whose
     * canonical form carries an accent (that is the display overlay silently not running).
     */
    private fun assertSlateIsCanonical(
        language: String,
        result: PredictionResult,
        trie: CtcLexiconTrie,
        display: Map<String, String>,
    ) {
        assertEquals("$language: one score per word", result.words.size, result.scores.size)
        for (word in result.words) {
            val surface = CtcAzProjection.project(word)
            assertNotNull("$language: '$word' has no a–z surface — it cannot be a beam result", surface)
            assertTrue(
                "$language: '$word' projects to '$surface', which is not in the lexicon — " +
                    "the display overlay invented a word",
                trie.contains(surface!!)
            )
            assertNull(
                "$language: '$word' was presented as a bare a–z surface; its canonical form " +
                    "is '${display[word]}' — CtcEngineAdapter.applyCanonicalDisplay did not run",
                display[word]
            )
        }
    }

    // ═══════════════════════════════════ tests ═══════════════════════════════════

    /**
     * GAP 1 — the packaged assets, parsed on-device, produce exactly the vocabulary the λ
     * sweep was run against, and the adapter's own merge path agrees word-for-word.
     *
     * Protects against: a dictionary regenerated without re-sweeping λ, an asset dropped or
     * corrupted by packaging, a [CkdtDictionaryReader] change that reads the rank byte
     * differently on Android, and a projection-policy edit (ß/œ handling, joiner stripping)
     * that would silently change which words are swipeable. Also pins BOTH halves of the
     * accent round trip at rest: the surface is a trie path, the canonical is in the
     * display map.
     */
    @Test
    fun bundledCkdtAssets_projectOntoTheFrozenLexiconShapes_andTheAdapterAgrees() {
        val adapter = CtcEngineAdapter(context)
        try {
            for (language in CKDT_LANGUAGES) {
                val expected = SHAPES.getValue(language)
                val projected = projectionFor(language)
                assertEquals(
                    "$language: CKDT records read from the APK asset",
                    expected.records, projected.records
                )
                assertEquals(
                    "$language: words with no a–z spelling (ß/œ/æ/ø are DROPPED, not mangled)",
                    expected.untypeable, projected.untypeable
                )
                assertEquals(
                    "$language: accent-strip collisions",
                    expected.collisions, projected.collisions
                )
                assertEquals(
                    "$language: distinct a–z surfaces",
                    expected.words, projected.freqs.size
                )

                val trie = adapter.trieFor(language)
                assertNotNull("$language: the adapter built no trie from the bundled asset", trie)
                assertEquals(
                    "$language: the adapter's shipping merge path must build the same " +
                        "vocabulary as the projection above (a mismatch means user " +
                        "custom/disabled words leaked in, or the two paths diverged)",
                    expected.words, trie!!.wordCount
                )

                val target = TARGETS.getValue(language)
                assertTrue(
                    "$language: '${target.surface}' must be reachable as a trie path",
                    trie.contains(target.surface)
                )
                assertEquals(
                    "$language: '${target.surface}' must display as its canonical form",
                    target.canonical, projected.display[target.surface]
                )
                Log.i(
                    TAG,
                    "$language: ${projected.records} records → ${trie.wordCount} words, " +
                        "${projected.untypeable} untypeable, ${projected.collisions} collisions, " +
                        "${projected.display.size} display forms"
                )
            }
        } finally {
            adapter.shutdown()
        }
    }

    /**
     * GAP 2 — the whole non-English path on real hardware: featurize → ONNX encoder → trie
     * beam → display overlay, once per newly-enabled language.
     *
     * Protects against: a decode that returns nothing (dead layout mapping, model load
     * failure, empty trie), a slate containing words that are not in the language's
     * lexicon, and — the actual point — an a–z surface reaching the user un-accented. The
     * accented target must be IN the slate, so the assertion cannot pass vacuously.
     */
    @Test
    fun nonEnglishSwipe_decodesRealWords_andPresentsTheAccentedCanonicalForm() {
        val kd = loadLayout(LAYOUT)
        val params = paramsFor(kd)
        val adapter = CtcEngineAdapter(context)
        try {
            assertTrue(
                "CTC must accept the bundled $LAYOUT layout (all 26 a–z keys present)",
                adapter.supportsLayout(kd, params, FRAME_W, FRAME_H)
            )
            for (language in CKDT_LANGUAGES) {
                val target = TARGETS.getValue(language)
                val projected = projectionFor(language)
                val trie = adapter.trieFor(language)
                assertNotNull("$language: no trie", trie)

                val result = decodeBlocking(adapter, kd, params, target.surface, language)
                Log.i(TAG, "$language '${target.surface}' → ${result.words}")
                assertTrue(
                    "$language: decode of '${target.surface}' returned nothing — the model, " +
                        "the layout mapping or the bundled lexicon regressed",
                    result.words.isNotEmpty()
                )
                assertSlateIsCanonical(language, result, trie!!, projected.display)
                assertTrue(
                    "$language: expected '${target.canonical}' in the slate for a " +
                        "straight-line '${target.surface}' trace (it is the top-1 beam result " +
                        "offline at the shipped preset). Got: ${result.words}",
                    result.words.contains(target.canonical)
                )
            }
        } finally {
            adapter.shutdown()
        }
    }

    /**
     * GAP 3 — the per-language scoring preset the adapter selects for its decoder.
     *
     * λ is calibrated against a lexicon's frequency SCALE, so the en JSON scale (compressed
     * 134–255 byte scores) and the CKDT scale (`max(1, 255 − rank)`) need different values.
     * Pure logic, asserted here in the packaged app because it is the constant that decides
     * whether the newly-enabled languages decode at the value their sweep validated.
     */
    @Test
    fun perLanguagePreset_selectsTheCkdtLambdaForTheNewlyEnabledLanguages() {
        val en = CtcScoringParams.presetFor("en")
        assertEquals(
            "en keeps the fitted λ of the compressed en_enhanced.json byte scale",
            CtcScoringParams.LAMBDA_EN_JSON_SCALE, en.lambda, 0.0
        )
        assertTrue("en must stay CTC-supported", CtcEngineAdapter.supportsLanguage("en"))

        for (language in CKDT_LANGUAGES) {
            val preset = CtcScoringParams.presetFor(language)
            assertEquals(
                "$language: λ must be the CKDT-scale value from the 2026-08-15 sweep",
                CtcScoringParams.LAMBDA_CKDT_SCALE, preset.lambda, 0.0
            )
            assertEquals(
                "$language: λ is the ONLY constant that varies by language",
                en.copy(lambda = CtcScoringParams.LAMBDA_CKDT_SCALE), preset
            )
            assertTrue(
                "$language must be CTC-supported after the enablement commit",
                CtcEngineAdapter.supportsLanguage(language)
            )
        }

        // Region subtags resolve to the base language — a fr-CA user must not silently
        // fall back to the English λ.
        assertEquals(CtcScoringParams.LAMBDA_CKDT_SCALE, CtcScoringParams.presetFor("fr-CA").lambda, 0.0)
        assertEquals(CtcScoringParams.LAMBDA_CKDT_SCALE, CtcScoringParams.presetFor("es_MX").lambda, 0.0)
        assertEquals(CtcScoringParams.LAMBDA_CKDT_SCALE, CtcScoringParams.presetFor("de-AT").lambda, 0.0)

        // Bundled-but-unvalidated languages stay OFF (they keep neural/geometric coverage).
        for (language in CtcLanguageSupport.NEEDS_VALIDATION) {
            assertFalse(
                "$language ships a dictionary but has no model bar and no λ sweep — " +
                    "it must not be routed to CTC",
                CtcEngineAdapter.supportsLanguage(language)
            )
        }
    }

    /**
     * GAP 4 — the stale-memo hazard. `CtcEngineAdapter` keeps ONE trie memo and ONE decoder
     * memo, both keyed by language; a switch must rebuild both. Reusing the previous
     * language's trie would decode French against English words (and at English's λ), and
     * nothing downstream would notice.
     *
     * Asserted three ways: memo identity (same language reuses, a switch does not), memo
     * CONTENT (en-only probe words are absent from the fr trie and come back on the way
     * back to en), and behaviour (the same trace decoded under en then fr yields an ASCII
     * English slate and then the accented French word — impossible if the decoder still
     * held the en trie).
     */
    @Test
    fun languageSwitch_rebuildsTheLexiconAndDecoder_neverReusingThePreviousLanguage() {
        val kd = loadLayout(LAYOUT)
        val params = paramsFor(kd)
        val adapter = CtcEngineAdapter(context)
        try {
            val enTrie = adapter.trieFor("en")
            assertNotNull("en: the adapter built no trie", enTrie)
            assertTrue(
                "en trie looks empty (${enTrie!!.wordCount} words)",
                enTrie.wordCount > EN_TRIE_MIN_WORDS
            )
            assertSame(
                "a repeat call for the SAME language must hit the memo, not rebuild",
                enTrie, adapter.trieFor("en")
            )

            val frTrie = adapter.trieFor("fr")
            assertNotNull("fr: the adapter built no trie", frTrie)
            assertNotSame("switching en→fr must rebuild the trie", enTrie, frTrie!!)
            assertEquals(
                "fr trie must hold the French vocabulary",
                SHAPES.getValue("fr").words, frTrie.wordCount
            )
            for (probe in EN_ONLY_PROBES) {
                assertTrue("en trie must contain the probe '$probe'", enTrie.contains(probe))
                assertFalse(
                    "the fr trie contains the en-only word '$probe' — the adapter served a " +
                        "stale English trie for French",
                    frTrie.contains(probe)
                )
            }

            val enTrieAgain = adapter.trieFor("en")
            assertNotNull("en: no trie after switching back", enTrieAgain)
            assertNotSame("switching fr→en must rebuild the trie", frTrie, enTrieAgain!!)
            assertTrue(
                "en trie after the round trip looks wrong (${enTrieAgain.wordCount} words)",
                enTrieAgain.wordCount > EN_TRIE_MIN_WORDS
            )
            for (probe in EN_ONLY_PROBES) {
                assertTrue(
                    "'$probe' must be back after switching fr→en",
                    enTrieAgain.contains(probe)
                )
            }

            // Behavioural leg: ONE trace, two languages, on the same adapter instance.
            val target = TARGETS.getValue("fr")
            val enSlate = decodeBlocking(adapter, kd, params, target.surface, "en")
            Log.i(TAG, "en '${target.surface}' → ${enSlate.words}")
            assertTrue("en decode returned nothing", enSlate.words.isNotEmpty())
            assertTrue(
                "the en slate must stay ASCII — the en lexicon has no display map, so a " +
                    "non-ASCII word means a CKDT display map leaked across the switch: " +
                    "${enSlate.words}",
                enSlate.words.all { word -> word.all { it.code < 128 } }
            )
            assertSlateIsCanonical("en", enSlate, enTrieAgain, emptyMap())

            val frSlate = decodeBlocking(adapter, kd, params, target.surface, "fr")
            Log.i(TAG, "fr '${target.surface}' → ${frSlate.words}")
            assertTrue(
                "fr decode after an en decode must produce '${target.canonical}' — a stale " +
                    "decoder memo would still be walking the English trie. Got: ${frSlate.words}",
                frSlate.words.contains(target.canonical)
            )
        } finally {
            adapter.shutdown()
        }
    }
}
