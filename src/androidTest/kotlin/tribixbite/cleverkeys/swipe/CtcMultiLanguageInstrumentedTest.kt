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
import tribixbite.cleverkeys.ContractionManager
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.KeyboardData
import tribixbite.cleverkeys.PredictionResult
import tribixbite.cleverkeys.TestConfigHelper
import tribixbite.cleverkeys.a11y.KeyboardGeometry
import tribixbite.cleverkeys.swipe.ctc.CtcAzProjection
import tribixbite.cleverkeys.swipe.ctc.CtcCkdtLexicon
import tribixbite.cleverkeys.swipe.ctc.CtcContractionKeys
import tribixbite.cleverkeys.swipe.ctc.CtcLanguageSupport
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconMerge
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconTrie
import tribixbite.cleverkeys.swipe.ctc.CtcScoringParams
import tribixbite.cleverkeys.swipe.ctc.CtcScriptSupport
import tribixbite.cleverkeys.swipe.geometric.CkdtDictionaryReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device coverage for the multilingual CTC swipe path. French, German and Spanish have
 * frozen evaluated shapes; Italian, Portuguese and Swedish have provisional packaging checks.
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
 *  4. **The cache and dual-language hazards.** The bounded LRU caches are keyed by language,
 *     and a dual-language swipe must run one encoder pass before rank-merging two independent
 *     lexicon decodes. These tests exercise a real instantiated adapter.
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
         * Bundled NON-Latin layouts — the a–z alphabet gate must reject both today
         * (LOW-9 / plan step A0). Cyrillic and Greek are the two scripts Milestone A
         * widens first, so these are precisely the layouts whose verdict must flip
         * deliberately rather than drift.
         */
        val NON_LATIN_LAYOUTS = listOf("cyrl_jcuken_ru", "grek_qwerty")

        /**
         * Projection shapes measured on the shipped dictionaries (the same numbers the λ
         * sweep harness reported and `CtcCkdtLexiconTest` pins off the filesystem). A
         * dictionary regeneration, a packaging change or a projection-policy edit moves
         * these — which is the point.
         */
        val SHAPES = mapOf(
            "fr" to LexiconShape(records = 40_000, untypeable = 0, words = 37_958, collisions = 2_042),
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
        secondaryLanguage: String? = null,
    ): PredictionResult {
        val (path, times) = traceFor(kd, params, word)
        var result: PredictionResult? = null
        var failed = false
        val latch = CountDownLatch(1)
        onMain {
            adapter.decodeAsync(
                keyboard = kd,
                params = params,
                frameWidthPx = FRAME_W,
                frameHeightPx = FRAME_H,
                swipePath = path,
                timestamps = times,
                language = language,
                secondaryLanguage = secondaryLanguage,
                // ARC-083 gave decodeAsync a THIRD outcome: a transient failure calls back here
                // instead of onResult, and defaults to a no-op for callers with no fallback
                // engine to offer. Without this the failure would be indistinguishable from a
                // hang and surface 60 seconds later as a latch timeout — the same "empty is not
                // failure" confusion the production dispatcher was fixed for.
                onDecodeFailure = {
                    failed = true
                    latch.countDown()
                },
            ) {
                result = it
                latch.countDown()
            }
        }
        assertTrue(
            "$language: decode of '$word' must complete",
            latch.await(60, TimeUnit.SECONDS)
        )
        assertTrue(
            "$language: decode of '$word' FAILED (CtcEngineAdapter routed it to the geometric " +
                "fallback) — see the CtcEngineAdapter error in logcat for the cause",
            !failed
        )
        return result!!
    }

    /**
     * Every word the adapter hands the pipeline must trace back to the lexicon — its a–z
     * projection has to be a path in [trie] — and none of them may be a BARE surface whose
     * canonical form carries an accent (that is the display overlay silently not running).
     *
     * For a NON-ENGLISH language the lexicon check is unconditional, apostrophes included.
     * It briefly was not: the adapters used to load the bundled ENGLISH contraction base for
     * every language, so a `fr` decode of the real French word "franco" also offered the
     * English possessive "franco's" — whose projection "francos" is (correctly) absent from
     * the French trie, i.e. no beam could have produced it. Contractions are now scoped to
     * the ACTIVE DECODE LANGUAGE (`SwipeContractionPolicy` /
     * `ContractionManager.loadSwipeDisplayMappings`), and every bundled non-English
     * contraction file maps an a–z key to a display form that differs only by
     * apostrophes/hyphens — so the display form projects back onto the beam surface it came
     * from, and `slate ⊆ lexicon` holds exactly. Guarding it here is what keeps it holding.
     *
     * **English keeps one narrow exemption**, unchanged by that fix and asserted nowhere
     * else: `contraction_pairings.json` augments an English slate with POSSESSIVES, and
     * 1,178 of its 1,744 entries have a display form whose projection differs from the base
     * ("africa" → "africa's" → "africas"), 400 of which are not `en_enhanced.json` words. An
     * unconditional check would therefore fail on English by beam luck, not by regression.
     */
    private fun assertSlateIsCanonical(
        language: String,
        result: PredictionResult,
        trie: CtcLexiconTrie,
        display: Map<String, String>,
    ) {
        val englishPossessiveExemption = language == "en"
        assertEquals(
            "$language: one score per word — the bar zips the two lists",
            result.words.size, result.scores.size
        )
        // Case-insensitive: the display overlay may re-case an entry, and a slate that
        // offers the same word twice burns a bar slot the user cannot act on.
        val lowered = result.words.map { it.lowercase() }
        assertEquals(
            "$language: the slate must not repeat a word: ${result.words}",
            lowered.distinct(), lowered
        )
        for ((i, word) in result.words.withIndex()) {
            assertFalse("$language: slate entry #$i is blank: ${result.words}", word.isBlank())
            // `CtcEngineAdapter.toPredictionResult` softmaxes to 0..1000 and coerces; the
            // overlay and the fuzzy rescue only ever copy or floor those values.
            val score = result.scores[i]
            assertTrue(
                "$language: score #$i for '$word' is $score, outside the engine-relative " +
                    "0..1000 band the adapter documents",
                score in 0..1000
            )
            val surface = CtcAzProjection.project(word)
            assertNotNull("$language: '$word' has no a–z surface — it cannot be a beam result", surface)
            val apostrophe = word.contains('\'') || word.contains('’')
            if (!englishPossessiveExemption || !apostrophe) {
                assertTrue(
                    "$language: '$word' projects to '$surface', which is not in the lexicon — " +
                        "the display overlay invented a word (English morphology leaking into " +
                        "a non-English slate is the 2026-08-16 regression)",
                    trie.contains(surface!!)
                )
            }
            assertNull(
                "$language: '$word' was presented as a bare a–z surface; its canonical form " +
                    "is '${display[word]}' — CtcEngineAdapter.applyCanonicalDisplay did not run",
                display[word]
            )
        }
    }

    /**
     * The bundled display forms of [language]'s OWN contraction files, read out of the APK —
     * BOTH `dictionaries/contractions_<lang>.json` (REPLACE mode) and
     * `dictionaries/contraction_pairs_<lang>.json` (APPEND mode, the keys that are real
     * words of the language: fr `lune` → `l'une`). Empty when the language ships neither,
     * as es/pt/sv do — those files are literally `{}`.
     */
    private fun contractionDisplayForms(language: String): Set<String> {
        val forms = HashSet<String>()
        try {
            val json = context.assets.open("dictionaries/contractions_$language.json")
                .use { org.json.JSONObject(it.readBytes().decodeToString()) }
            val keys = json.keys()
            while (keys.hasNext()) forms.add(json.getString(keys.next()).lowercase())
        } catch (e: java.io.FileNotFoundException) {
            // Language ships no REPLACE-mode file.
        }
        try {
            val json = context.assets.open("dictionaries/contraction_pairs_$language.json")
                .use { org.json.JSONObject(it.readBytes().decodeToString()) }
            val keys = json.keys()
            while (keys.hasNext()) {
                val variants = json.getJSONArray(keys.next())
                for (i in 0 until variants.length()) forms.add(variants.getString(i).lowercase())
            }
        } catch (e: java.io.FileNotFoundException) {
            // Language ships no APPEND-mode file.
        }
        return forms
    }

    /**
     * No word in a non-English slate may carry an apostrophe form that [language]'s OWN
     * contraction file does not define — i.e. no ENGLISH contraction or possessive. States
     * the product rule directly (code-switching is a bug: English forms may only appear
     * when the user is actually decoding English) on top of the lexicon check above, which
     * implies it only indirectly.
     */
    private fun assertNoForeignContractions(language: String, result: PredictionResult) {
        val own = contractionDisplayForms(language)
        for (word in result.words) {
            if (!word.contains('\'') && !word.contains('’')) continue
            assertTrue(
                "$language: '$word' is an apostrophe form that contractions_$language.json " +
                    "does not define — an English contraction/possessive in a $language " +
                    "slate is the code-switching bug fixed on 2026-08-16 " +
                    "(SwipeContractionPolicy). Slate: ${result.words}",
                own.contains(word.lowercase())
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
                    "$language: words with no supported a–z spelling after deterministic expansion",
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

                // The adapter's trie is the projection PLUS the contraction alias keys that
                // `CtcContractionKeys.inject` adds, so it is legitimately larger than the
                // projection — for `fr` by ~17.9k since the 2026-08-17 restore. Comparing
                // against `expected.words` alone made this test fail the moment injection
                // shipped (it did: this assertion was red from `8230333b` until 2026-08-18,
                // unnoticed because only the pure suite was re-run).
                //
                // The delta is COMPUTED here rather than frozen as a second magic number:
                // injecting the same keys into an independent projection trie must reproduce
                // the adapter's word count exactly. That keeps the original intent — a
                // mismatch still means user custom/disabled words leaked in or the two paths
                // diverged — while surviving any future change to the contraction assets.
                // Alphabet taken from the adapter's own trie rather than re-declared here:
                // the two must agree by construction, not by a constant kept in sync by hand.
                val independent = CtcLexiconTrie.loadFromFrequencyMap(
                    trie!!.alphabet, projected.freqs
                )
                val aliasKeys = ContractionManager(context)
                    .apply { loadSwipeDisplayMappings(language) }
                    .getAliasKeys()
                val injected = CtcContractionKeys.inject(independent, aliasKeys)
                assertEquals(
                    "$language: the adapter's shipping merge path must build the same " +
                        "vocabulary as the projection + contraction injection (a mismatch " +
                        "means user custom/disabled words leaked in, or the two paths diverged)",
                    expected.words + injected, trie.wordCount
                )
                // Injection may only ADD, and may only add keys drawn from the alias set —
                // `injected >= 0` used to stand here, which a count can never violate.
                val injectable = aliasKeys.count { CtcContractionKeys.isInjectable(it, trie.alphabet) }
                assertTrue(
                    "$language: injected $injected keys but only $injectable of " +
                        "${aliasKeys.size} alias keys are spellable from the trie alphabet — " +
                        "injection invented paths",
                    injected <= injectable
                )
                assertTrue(
                    "$language: injection must never remove or replace a lexicon word " +
                        "(projection had ${expected.words}, trie has ${trie.wordCount})",
                    trie.wordCount >= expected.words
                )
                // The POINT of injection: after it, every spellable alias key is a beam-
                // reachable path. The count equality above is satisfied by any set of the
                // right size; this pins the actual keys.
                val unreachable = aliasKeys
                    .filter { CtcContractionKeys.isInjectable(it, trie.alphabet) }
                    // Locale.ROOT to match CtcContractionKeys' own case-folding policy.
                    .filterNot { trie.contains(it.lowercase(java.util.Locale.ROOT)) }
                assertTrue(
                    "$language: ${unreachable.size} spellable alias keys are not trie paths, " +
                        "so ContractionOverlay can never fire for them — the beam cannot " +
                        "produce a surface the lexicon lacks. First 5: ${unreachable.take(5)}",
                    unreachable.isEmpty()
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
                adapter.supportsLayout(kd, params, FRAME_W, FRAME_H, "en")
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
                assertNoForeignContractions(language, result)
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
     * LOW-9 / `docs/plans/2026-08-25-ctc-multiscript-wiring-plan.md` step A0/A4 — the layout
     * gate, now asserted as the **per-language** predicate it became.
     *
     * ## The deliberate flip
     * This test used to assert `supportsLayout(cyrl_jcuken_ru) == false` and
     * `supportsLayout(grek_qwerty) == false`, full stop, and its own KDoc said: "Milestone A4
     * replaces the hardcoded a-z ALPHABET with a per-script one, at which point these two
     * verdicts flip. Pinning them now makes that flip a deliberate, visible edit to this test
     * rather than a silent widening nobody reviews." **This is that edit.**
     *
     * What flipped is narrow and worth stating exactly: `supportsLayout` no longer asks "are all
     * 26 a-z centre keys present", it asks "are all of THIS LANGUAGE's alphabet keys present".
     * So a Cyrillic board still fails for `en` — the enduring half of the old assertion — and
     * now passes for `ru`, whose 31-letter alphabet is exactly that board's 31 centre keys.
     *
     * ## What this gate does and does NOT decide
     * Passing here does not mean a swipe reaches CTC. Three other gates still apply, and the
     * language one is what holds an unwired script back: `grek_qwerty` + `el` builds a layout
     * fine, but `el` is absent from [CtcLanguageSupport.SUPPORTED], so
     * `InputCoordinator.performCtcSwipeTyping` hands the swipe to the geometric engine. That
     * separation is the point of rule 4 — the layout gate is about KEYS, not about whether the
     * model, the trie and the fixture exist.
     *
     * ## Why instrumented and not pure (the CK-150-032 feasibility ruling)
     * Both halves of the call are Android-bound and neither is reachable from `runPureTests`:
     *  - `KeyboardData` only parses layout XML through `Xml.newPullParser()`
     *    (`KeyboardData.load` / `load_string`); `parse_keyboard` is private, so there is no
     *    JDK-parser entry point. The pure geometric tests sidestep this deliberately —
     *    `GeoLayoutFixtures` re-implements the parse with `javax.xml.parsers` into its own
     *    `LayoutGeometry`, which is NOT a `KeyboardData` and cannot be handed to
     *    `supportsLayout`.
     *  - `CtcEngineAdapter`'s constructor needs a `Context` and eagerly builds a
     *    `Handler(Looper.getMainLooper())` and an `OrtEnvironment`. `runPureTests` runs
     *    JUnitCore on a plain JVM **without android.jar on the classpath** (that is what the
     *    separate `runMockTests` task exists for), and the ORT JVM natives are not built for
     *    this host anyway.
     * Faking either half would test the fake, not the gate, so the assertion lives here.
     *
     * The Latin positive control is asserted in the same method on purpose: without it a
     * `supportsLayout` that returned false unconditionally would pass the negative assertions.
     */
    @Test
    fun theLayoutGateIsPerLanguage() {
        val adapter = CtcEngineAdapter(context)
        try {
            val latin = loadLayout(LAYOUT)
            assertTrue(
                "positive control: CTC must still accept $LAYOUT for en — without this a gate " +
                    "that rejects everything would satisfy the negative assertions below",
                adapter.supportsLayout(latin, paramsFor(latin), FRAME_W, FRAME_H, "en")
            )

            // The enduring negative: a Latin language's a-z alphabet is not on a non-Latin
            // board, so an en/fr/de user who switches to one of these layouts still falls
            // through to geometric. This half never flips.
            for (layoutName in NON_LATIN_LAYOUTS) {
                val kd = loadLayout(layoutName)
                assertFalse(
                    "$layoutName must never be CTC-eligible for a LATIN language: none of the " +
                        "26 a-z keys is on that board, so buildMappedLayout cannot produce a " +
                        "CtcLayout and the caller has to hand the swipe to geometric.",
                    adapter.supportsLayout(kd, paramsFor(kd), FRAME_W, FRAME_H, "en")
                )
            }

            // The flip: each script layout DOES expose its own language's alphabet. Read off
            // CtcScriptSupport rather than hard-coded, so adding a script row extends this
            // assertion automatically instead of leaving the new script unchecked.
            for ((language, wiring) in CtcScriptSupport.SCRIPTS) {
                val name = wiring.layoutXml.removeSuffix(".xml")
                val kd = loadLayout(name)
                assertTrue(
                    "$name must expose every one of $language's ${wiring.alphabet.length} " +
                        "alphabet letters as CENTRE keys — that is what makes the alphabet the " +
                        "model's emission slot order. A miss means either the table's alphabet " +
                        "string or the layout XML is wrong, and a decode built on it would be " +
                        "silently permuted rather than failing.",
                    adapter.supportsLayout(kd, paramsFor(kd), FRAME_W, FRAME_H, language)
                )
                // And the converse: a script board is not eligible for a DIFFERENT script's
                // language. ru and uk are both `cyrillic` and both 31 letters, but they are
                // different 31 letters (uk has no ы/э/ъ, ru has no є/і), so the boards are not
                // interchangeable and the gate must say so.
                for ((other, otherWiring) in CtcScriptSupport.SCRIPTS) {
                    if (other == language || otherWiring.alphabet == wiring.alphabet) continue
                    assertFalse(
                        "$name must NOT be eligible for '$other' — different alphabet, " +
                            "different slot order, silently permuted decode if admitted",
                        adapter.supportsLayout(kd, paramsFor(kd), FRAME_W, FRAME_H, other)
                    )
                }
            }
        } finally {
            adapter.shutdown()
        }
    }

    /**
     * Rule 4, on device: a script may only be routed when its model, its trie and its fixture
     * all exist. The pure `CtcScriptSupportTest` checks the table against the repo tree; this
     * checks it against the PACKAGED APK, which is the artifact that actually ships.
     */
    @Test
    fun everyRoutedScriptShipsItsModelAssetInTheApk() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val models = target.assets.list("models")?.toSet().orEmpty()
        assertTrue(
            "assets/models is empty in the packaged APK — the CTC encoders did not ship",
            models.isNotEmpty()
        )
        // Without this the loop below would exercise only its not-ROUTED branch and read
        // as coverage of a rule it never checked.
        assertTrue(
            "no ROUTED script in CtcScriptSupport — the ROUTED half of this test is vacuous",
            CtcScriptSupport.SCRIPTS.values.any { it.status == CtcScriptSupport.Status.ROUTED }
        )
        for ((language, wiring) in CtcScriptSupport.SCRIPTS) {
            if (wiring.status != CtcScriptSupport.Status.ROUTED) {
                assertFalse(
                    "$language is not ROUTED, so CtcLanguageSupport must not serve it — the " +
                        "language gate is what holds an unwired script back",
                    CtcLanguageSupport.isSupported(language)
                )
                continue
            }
            val asset = wiring.modelAsset!!.removePrefix("models/")
            assertTrue(
                "$language is ROUTED but $asset is not packaged under assets/models — the " +
                    "router would send swipes to an encoder that cannot load. Packaged: $models",
                asset in models
            )
            assertTrue(
                "$language is ROUTED so CtcLanguageSupport must serve it",
                CtcLanguageSupport.isSupported(language)
            )
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
    fun everyProvisionalLanguageBuildsAWorkingTrieFromItsBundledAsset() {
        // it/pt/sv were enabled on 2026-08-18 by scale transfer, with no per-language corpus
        // to measure a bar against. That makes THIS the load-bearing check: the packaged
        // asset parses on-device, projects onto a–z, and yields a trie the beam can actually
        // walk. Without it, "enabled" would rest entirely on a table edit compiling.
        //
        // Deliberately NOT pinned to frozen shapes like the fr/de/es test above — those
        // numbers came from the λ sweep harness, which never ran for these three. Asserting
        // invented constants would be worse than asserting none; these are the properties
        // that actually have to hold.
        val adapter = CtcEngineAdapter(context)
        try {
            for (language in CtcLanguageSupport.PROVISIONAL) {
                // Wave-J fix (2026-09-02): PROVISIONAL no longer implies BUNDLED. el joined
                // the tier with LexiconSource.CKDT_LANGPACK — its lexicon is the importable
                // langpack, by design (HANDOFF "Russian, exactly" precedent), so it has no
                // bundled asset and, on a clean emulator with no pack installed, must not
                // even claim a lexicon source. Its serving path is covered end-to-end by
                // CtcImportedPackInstrumentedTest; here we pin the clean-device contract.
                if (CtcLanguageSupport.sourceFor(language) == CtcLanguageSupport.LexiconSource.CKDT_LANGPACK) {
                    assertEquals(
                        "$language is langpack-sourced and must have NO bundled asset",
                        null, CtcLanguageSupport.assetFor(language)
                    )
                    continue
                }
                val asset = CtcLanguageSupport.assetFor(language)
                assertEquals(
                    "$language must resolve to its bundled CKDT asset",
                    "dictionaries/${language}_enhanced.bin", asset
                )

                val trie = adapter.trieFor(language)
                assertNotNull("$language: the adapter built no trie from the bundled asset", trie)

                // A CKDT dictionary that failed to parse yields a tiny or empty trie rather
                // than throwing, so a bare non-null check would pass on a corrupt asset. The
                // bundled .bin files are ~1 MB / tens of thousands of records; 10k is far
                // below any of them while being far above any parse-failure remnant.
                assertTrue(
                    "$language: trie holds only ${trie!!.wordCount} words — the bundled " +
                        "dictionary failed to parse or project",
                    trie.wordCount > 10_000
                )

                assertEquals(
                    "$language: alphabet must be the a–z the projection targets",
                    26, trie.alphabet.size
                )

                // Contraction injection is a headline reason these moved to CTC: geometric
                // has none, so after the neural removal only 18 of 21,214 Italian alias keys
                // were reachable. The alphabet check above does NOT prove that (it was the
                // only assertion under this comment until ARC-044); this does — every alias
                // key the alphabet can spell must be a beam-reachable trie path, because the
                // overlay can only rewrite a surface the beam actually produced.
                val aliasKeys = ContractionManager(context)
                    .apply { loadSwipeDisplayMappings(language) }
                    .getAliasKeys()
                val spellable = aliasKeys
                    .filter { CtcContractionKeys.isInjectable(it, trie.alphabet) }
                val unreachable = spellable
                    .filterNot { trie.contains(it.lowercase(java.util.Locale.ROOT)) }
                assertTrue(
                    "$language: ${unreachable.size} of ${spellable.size} spellable alias keys " +
                        "are not trie paths — injection did not run on the provisional path. " +
                        "First 5: ${unreachable.take(5)}",
                    unreachable.isEmpty()
                )
                Log.i(TAG, "provisional $language: ${aliasKeys.size} alias keys, " +
                    "${spellable.size} spellable, all reachable")

                Log.i(TAG, "provisional $language: ${trie.wordCount} words, " +
                    "${trie.nodeCount} nodes, λ=${CtcScoringParams.presetFor(language).lambda}")
            }
        } finally {
            adapter.shutdown()
        }
    }

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

        // 2026-08-18: it/pt/sv moved from NEEDS_VALIDATION to SUPPORTED-but-PROVISIONAL, so
        // the old "these stay OFF" loop would now iterate an EMPTY set and assert nothing.
        // A vacuous test is worse than none — it reads as coverage. Assert the live
        // invariant instead: a provisional language is routed to CTC AND decodes at the
        // CKDT-scale λ, which is the entire basis on which it was enabled.
        assertTrue(
            "PROVISIONAL must not be empty while it/pt/sv are enabled on scale transfer",
            CtcLanguageSupport.PROVISIONAL.isNotEmpty()
        )
        for (language in CtcLanguageSupport.PROVISIONAL) {
            assertTrue(
                "$language is enabled provisionally, so it must route to CTC",
                CtcEngineAdapter.supportsLanguage(language)
            )
            assertEquals(
                "$language reads a CKDT .bin, so it must take the λ fitted on that scale — " +
                    "the English 4.0 is fitted for an 8x narrower log-frequency spread",
                CtcScoringParams.LAMBDA_CKDT_SCALE,
                CtcScoringParams.presetFor(language).lambda,
                0.0
            )
        }
        assertTrue(
            "NEEDS_VALIDATION is empty now that every bundled dictionary is served; if a new " +
                "language is added it belongs there until its tier is decided",
            CtcLanguageSupport.NEEDS_VALIDATION.isEmpty()
        )
    }

    /**
     * GAP 4 — the stale-cache hazard. `CtcEngineAdapter` keeps bounded LRU trie/decoder caches
     * keyed by language. Reusing another language's entry would decode French against English
     * words (and at English's λ), and nothing downstream would notice.
     *
     * Asserted three ways: cache identity (same language reuses and two active languages fit
     * without eviction), CONTENT (en-only probe words are absent from the fr trie), and
     * behaviour (the same trace decoded under en then fr yields an ASCII
     * English slate and then the accented French word — impossible if the decoder still
     * held the en trie).
     */
    @Test
    fun languageSwitch_usesLanguageKeyedBoundedCaches_neverCrossContaminatingLexicons() {
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
            assertNotSame("en and fr must have independent trie instances", enTrie, frTrie!!)
            // A LOWER BOUND, not the exact count: the adapter's trie is the projection plus
            // whatever `CtcContractionKeys.inject` adds, and pinning the exact number here
            // duplicated `bundledCkdtAssets_...`'s job while being the assertion that broke
            // when the fr contraction restore landed. What THIS test is about is that the
            // en→fr switch rebuilt against French data at all — `assertNotSame` above and
            // the EN_ONLY_PROBES below carry that; the size only needs to be plausible.
            assertTrue(
                "fr trie must hold the French vocabulary (projection is " +
                    "${SHAPES.getValue("fr").words} words; got ${frTrie.wordCount})",
                frTrie.wordCount >= SHAPES.getValue("fr").words
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
            assertSame(
                "the two-entry cache must retain English while French is active",
                enTrie, enTrieAgain!!,
            )
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
            // The contraction manager is memoized by language on the SAME adapter instance,
            // so this fr decode also proves the en→fr switch dropped the English base.
            assertNoForeignContractions("fr", frSlate)
        } finally {
            adapter.shutdown()
        }
    }

    /**
     * A French-primary swipe can surface an English-only word from the configured secondary
     * language. This proves the shipping adapter decodes both tries and rank-merges the slates;
     * a primary-only decode is the control that keeps the assertion from passing by fixture luck.
     */
    @Test
    fun secondaryLanguageDecode_surfacesAnEnglishOnlyWordWithoutReplacingPrimaryRankOne() {
        val kd = loadLayout(LAYOUT)
        val params = paramsFor(kd)
        val adapter = CtcEngineAdapter(context)
        val probe = "wednesday"
        try {
            assertFalse("fixture: '$probe' must be absent from French", adapter.trieFor("fr")!!.contains(probe))
            assertTrue("fixture: '$probe' must be present in English", adapter.trieFor("en")!!.contains(probe))

            val primaryOnly = decodeBlocking(adapter, kd, params, probe, "fr")
            // HARD, not conditional (the audit m-5 precedent): an EMPTY control decode makes
            // the assertion below vacuous AND silently drops the rank-one pin at the end of
            // this test. A French beam over ~38k words must produce something for a
            // nine-letter trace; nothing at all means the fr path regressed.
            assertTrue(
                "the French-only control decoded nothing — it cannot control anything, and " +
                    "an empty slate would silently skip the rank-one assertion below",
                primaryOnly.words.isNotEmpty(),
            )
            assertFalse(
                "French-only control unexpectedly surfaced '$probe': ${primaryOnly.words}",
                primaryOnly.words.contains(probe),
            )

            val dual = decodeBlocking(adapter, kd, params, probe, "fr", secondaryLanguage = "en")
            Log.i(TAG, "fr+en '$probe' → ${dual.words}")
            assertTrue(
                "French-primary + English-secondary must surface '$probe'. Got: ${dual.words}",
                dual.words.contains(probe),
            )
            assertEquals(
                "secondary decoding must not replace the primary language's rank one",
                primaryOnly.words.first(), dual.words.first(),
            )
            // The rank merger interleaves two independently scored slates, which is exactly
            // where a word can arrive twice (once per lane) or lose its score partner.
            for ((label, slate) in listOf("primary-only" to primaryOnly, "dual" to dual)) {
                assertEquals(
                    "$label: one score per word after the merge",
                    slate.words.size, slate.scores.size
                )
                val lowered = slate.words.map { it.lowercase() }
                assertEquals(
                    "$label: rank-merging two lexicons must not offer the same word twice: " +
                        "${slate.words}",
                    lowered.distinct(), lowered
                )
            }
        } finally {
            adapter.shutdown()
        }
    }

    /**
     * The reported regression, decoded on device: a `fr` swipe of the real French word
     * `franco` must not offer the English possessive `franco's`.
     *
     * `franco` is a French lexicon word (rank ~2,937), so the beam legitimately emits it —
     * and `contraction_pairings.json` pairs it with `franco's`, which the adapters used to
     * load for EVERY language. `ContractionOverlay`'s real-word ordinal guard cannot stop
     * this: the PAIRED rule fires before the guard is consulted. The only fix is not having
     * the English mappings loaded at all, which is what
     * `ContractionManager.loadSwipeDisplayMappings` now guarantees.
     *
     * The equivalent data-level matrix (every language, both policies, over the shipped
     * assets) is `SwipeContractionLanguageIsolationTest` in `runPureTests`; this leg proves
     * the packaged app really loads that way.
     */
    @Test
    fun frenchSwipe_neverOffersAnEnglishPossessiveOfAFrenchWord() {
        val kd = loadLayout(LAYOUT)
        val params = paramsFor(kd)
        val adapter = CtcEngineAdapter(context)
        try {
            val trie = adapter.trieFor("fr")
            assertNotNull("fr: no trie", trie)
            assertTrue(
                "fixture: 'franco' must be a French lexicon word (else the probe is vacuous)",
                trie!!.contains("franco")
            )
            assertFalse(
                "fixture: 'francos' must NOT be a French word — that is what makes an " +
                    "appended \"franco's\" provably foreign",
                trie.contains("francos")
            )

            val result = decodeBlocking(adapter, kd, params, "franco", "fr")
            Log.i(TAG, "fr 'franco' → ${result.words}")
            assertTrue("fr decode of 'franco' returned nothing", result.words.isNotEmpty())
            assertFalse(
                "the English possessive \"franco's\" leaked into a French slate: " +
                    "${result.words}",
                result.words.any { it.lowercase() == "franco's" }
            )
            assertNoForeignContractions("fr", result)
            assertSlateIsCanonical("fr", result, trie, projectionFor("fr").display)
        } finally {
            adapter.shutdown()
        }
    }
}
