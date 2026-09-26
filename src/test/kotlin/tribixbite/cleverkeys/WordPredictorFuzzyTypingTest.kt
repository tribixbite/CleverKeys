package tribixbite.cleverkeys

import android.os.Trace
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.mockk.every
import io.mockk.just
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import tribixbite.cleverkeys.autocorrect.FrequencyFloor
import tribixbite.cleverkeys.autocorrect.KeyAdjacency
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * User report (2026-09-26): "touch typing suggestion bar entries should include autocorrect
 * results — typing 'play', if a user misses the l and taps k there are no suggestions upon
 * reaching 'a'. 'pka' or 'pkay' should suggest 'play'."
 *
 * Drives the REAL `WordPredictor.predictWordsWithContext` (the tap-typing bar's source)
 * over the REAL bundled 98k English dictionary on the device frequency scale, harnessed
 * exactly like `AutoCorrectEndToEndTest` (Objenesis instance + reflection-injected
 * fields; MockK runner because the predictor touches `android.util.Log` and
 * `android.os.Trace`).
 *
 * Pins: typo-tolerant prefix completions (pka → play), whole-word corrections in the bar
 * (pkay → play, and the space-bar autocorrect's own pick is listed), exact prefixes keep
 * priority (pla), no fuzzy noise on 2-letter prefixes, disabled words stay out, user words
 * are reachable, the secondary-language dictionary participates, provenance tags the new
 * entries as autocorrect, and a per-keystroke latency bound.
 */
class WordPredictorFuzzyTypingTest {

    companion object {
        private lateinit var predictor: WordPredictor
        private lateinit var config: Config
        private lateinit var baseDict: HashMap<String, Int>

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            mockkStatic(Log::class)
            every { Log.d(any(), any()) } returns 0
            every { Log.d(any(), any(), any()) } returns 0
            every { Log.i(any(), any()) } returns 0
            every { Log.w(any(), any<String>()) } returns 0
            every { Log.e(any(), any()) } returns 0
            every { Log.e(any(), any(), any()) } returns 0
            mockkStatic(Trace::class)
            every { Trace.beginSection(any()) } just runs
            every { Trace.endSection() } just runs

            // QWERTY geometry — what Keyboard2View pushes for the default layout.
            KeyAdjacency.resetLayout()

            val gson = Gson()
            val mapType = object : TypeToken<Map<String, Double>>() {}.type
            val rawDict: Map<String, Double> = File(
                "src/main/assets/dictionaries/en_enhanced.json"
            ).reader().use { gson.fromJson(it, mapType) }
            // Same rank → frequency conversion as BinaryDictionaryLoader V2 (the device path).
            baseDict = HashMap(rawDict.size * 2)
            for ((word, byteFreq) in rawDict) {
                baseDict[word] = 1_000_000 - (255 - byteFreq.toInt()) * 3900
            }

            config = org.objenesis.ObjenesisStd().newInstance(Config::class.java)
            predictor = org.objenesis.ObjenesisStd().newInstance(WordPredictor::class.java)
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            unmockkStatic(Log::class)
            unmockkStatic(Trace::class)
        }

        private fun setField(name: String, value: Any?) {
            val field = WordPredictor::class.java.getDeclaredField(name)
            field.isAccessible = true
            field.set(predictor, value)
        }

        /** Same shape as WordPredictor.buildPrefixIndex: every word under its 1..3-letter prefixes. */
        private fun prefixIndexOf(words: Collection<String>): MutableMap<String, MutableSet<String>> {
            val index = HashMap<String, MutableSet<String>>()
            for (w in words) {
                for (len in 1..minOf(3, w.length)) index.getOrPut(w.substring(0, len)) { HashSet() }.add(w)
            }
            return index
        }
    }

    @Before
    fun resetState() {
        // Fresh copies per test: some tests add/disable words.
        val dict = HashMap(baseDict)
        setField("dictionary", AtomicReference<MutableMap<String, Int>>(dict))
        setField("prefixIndex", AtomicReference(prefixIndexOf(dict.keys)))
        setField("contractionAliases", emptyMap<String, String>())
        setField("customAndUserWords", emptySet<String>())
        setField("disabledWords", mutableSetOf<String>())
        setField("userWordOriginalCase", ConcurrentHashMap<String, String>())
        setField("config", config)
        setField("cachedMaxFreqForSize", -1)
        setField("secondaryIndex", null)
        setField("secondaryLanguageCode", "none")

        // Scoring knobs at their shipped defaults (Objenesis skipped Config's initializers).
        config.prediction_frequency_scale = Defaults.PREDICTION_FREQUENCY_SCALE
        config.prediction_context_boost = Defaults.PREDICTION_CONTEXT_BOOST
        config.context_source = Defaults.CONTEXT_SOURCE
        config.personalization_weight = Defaults.PERSONALIZATION_WEIGHT
        config.secondary_prediction_weight = Defaults.SECONDARY_PREDICTION_WEIGHT
        // Autocorrect knobs as the end-to-end autocorrect harness locks them.
        config.autocorrect_enabled = true
        config.autocorrect_min_word_length = 2
        config.autocorrect_char_match_threshold = 0.65f
        config.autocorrect_max_length_diff = 2
        config.autocorrect_confidence_min_frequency = FrequencyFloor.SLIDER_MIN
        config.autocorrect_prefix_length = 0
        config.swipe_debug_detailed_logging = false
    }

    private fun bar(typed: String, context: List<String> = emptyList()): WordPredictor.PredictionResult =
        predictor.predictWordsWithContext(typed, context)

    // ── The report ────────────────────────────────────────────────────────

    @Test
    fun pka_missedLForNeighbourK_offersPlay() {
        val result = bar("pka")
        assertTrue("'pka' must not leave the bar empty (got ${result.words})", result.words.isNotEmpty())
        assertTrue("'pka' must offer 'play' (got ${result.words})", "play" in result.words)
    }

    @Test
    fun pkay_wholeWordTypo_offersPlayFirst() {
        val result = bar("pkay")
        assertEquals("'pkay' must lead with 'play' (got ${result.words})", "play", result.words.firstOrNull())
    }

    @Test
    fun pkay_theSpaceBarAutocorrectPick_isAlsoInTheBar() {
        val corrected = predictor.autoCorrect("pkay")
        assertEquals("precondition: space-bar autocorrect fixes pkay", "play", corrected)
        assertTrue("autocorrect's pick must be offered while typing", corrected in bar("pkay").words)
    }

    // ── Ranking guards ────────────────────────────────────────────────────

    @Test
    fun pla_strongExactPrefix_isNotDilutedByFuzzyCandidates() {
        val words = bar("pla").words
        assertTrue("'play' must be offered for 'pla' (got $words)", "play" in words)
        for (w in words) assertTrue("'$w' is not an exact 'pla' completion", w.startsWith("pla"))
        bar("pla").metas!!.forEach {
            assertEquals(SuggestionOrigin.DICTIONARY_PREFIX, it.origin)
        }
    }

    @Test
    fun thw_weakExactPrefix_keepsExactLead_andAddsTheCorrection() {
        val words = bar("thw").words
        assertEquals("the rare exact completion keeps first place (got $words)", "thwart", words.firstOrNull())
        assertTrue("'the' (w↔e neighbour slip) must be offered (got $words)", "the" in words)
    }

    @Test
    fun exactCompletion_outranksFuzzyCandidateOfSimilarFrequency() {
        // Two synthetic words at the SAME frequency and length: typed "zqvlm" is an exact
        // prefix of one and a one-neighbour-slip (z↔x) prefix of the other.
        val dict = dictField()
        dict["zqvlmar"] = 900_000
        dict["xqvlmar"] = 900_000
        rebuildIndex()
        val result = bar("zqvlm")
        assertEquals(listOf("zqvlmar", "xqvlmar"), result.words)
        assertTrue("the exact entry must score strictly higher", result.scores[0] > result.scores[1])
    }

    @Test
    fun weakFullBar_reservesSlotsForTheBestCorrections_withoutTakingTheLead() {
        // "thw" has five exact (rare) completions — the fuzzy "the" would rank 6th on raw
        // score alone; the reservation puts it (and the next correction) in the bar.
        val result = bar("thw")
        val fuzzy = result.metas!!.count { it.origin == SuggestionOrigin.AUTOCORRECT }
        assertEquals("reserved fuzzy slots (got ${result.words})", 2, fuzzy)
        assertEquals(SuggestionOrigin.DICTIONARY_PREFIX, result.metas!![0].origin)
        assertEquals("bar stays in score order", result.scores.sortedDescending(), result.scores)
    }

    @Test
    fun twoLetterPrefix_getsNoFuzzyNoise() {
        val result = bar("pk")
        for (w in result.words) assertTrue("'$w' is fuzzy noise on a 2-letter prefix", w.startsWith("pk"))
        result.metas?.forEach { assertEquals(SuggestionOrigin.DICTIONARY_PREFIX, it.origin) }
    }

    @Test
    fun nonLetterInput_getsNoFuzzyCandidates() {
        assertTrue(bar("123").words.isEmpty())
        assertTrue(bar("a".repeat(100)).words.isEmpty())
    }

    // ── Provenance ────────────────────────────────────────────────────────

    @Test
    fun fuzzyEntries_areTaggedAutocorrect_withTheTypedWordAndABreakdown() {
        val result = bar("pka")
        val i = result.words.indexOf("play")
        assertTrue(i >= 0)
        val meta = result.metas!![i]
        assertEquals(SuggestionOrigin.AUTOCORRECT, meta.origin)
        assertEquals(ProvenanceNote.AutocorrectedFrom("pka"), meta.note)
        val breakdown = meta.breakdown!!
        assertEquals("displayed breakdown must match the ranking score", result.scores[i], breakdown.finalScore)
        assertTrue(
            "fuzzy prefix score must sit below the exact-prefix score for the same word (got ${breakdown.prefixScore})",
            breakdown.prefixScore < predictor.explainScore("play", "pla", emptyList())!!.prefixScore
        )
    }

    // ── Vocabulary gates ──────────────────────────────────────────────────

    @Test
    fun disabledWord_isNeverOfferedAsAFuzzyCandidate() {
        setField("disabledWords", mutableSetOf("play"))
        assertFalse("play" in bar("pka").words)
        assertFalse("play" in bar("pkay").words)
    }

    @Test
    fun userWord_isReachableThroughATypo() {
        val dict = dictField()
        dict["zorblax"] = 1_000_000
        setField("customAndUserWords", setOf("zorblax"))
        rebuildIndex()
        // z↔x are neighbours: the user fat-fingers their own word's first letter.
        assertTrue("user word must be offered for 'xorb' (got ${bar("xorb").words})", "zorblax" in bar("xorb").words)
    }

    @Test
    fun secondaryLanguage_typoIsCorrectedFromTheSecondaryDictionary() {
        val secondary = NormalizedPrefixIndex()
        secondary.addWord("schmetterling", 40)
        secondary.addWord("übermorgen", 60)
        setField("secondaryIndex", secondary)
        setField("secondaryLanguageCode", "de")
        // w↔e neighbour slip inside a German-only word.
        val words = bar("schmettw").words
        assertTrue("secondary word must be offered for 'schmettw' (got $words)", "schmetterling" in words)
        // Accent-free typing with a slip on the secondary's normalized form.
        val uber = bar("ubwrmo").words
        assertTrue("'übermorgen' must be offered for 'ubwrmo' (got $uber)", "übermorgen" in uber)
    }

    // ── Latency ───────────────────────────────────────────────────────────

    @Test
    fun perKeystrokeLatency_staysWithinBudget() {
        // Every prefix of a few typo'd words, i.e. what the bar runs keystroke by keystroke.
        val typedWords = listOf("pkay", "wuestion", "thw", "becauae", "somethimg", "xorblaxx", "qqqqzzzz")
        val inputs = typedWords.flatMap { w -> (1..w.length).map { w.substring(0, it) } }
        repeat(3) { inputs.forEach { bar(it) } } // JIT warm-up
        val rounds = 10
        val perInput = LongArray(inputs.size)
        for (r in 0 until rounds) {
            inputs.forEachIndexed { i, s ->
                val t0 = System.nanoTime()
                bar(s)
                perInput[i] += System.nanoTime() - t0
            }
        }
        val avgUs = perInput.map { it / rounds / 1000 }
        inputs.zip(avgUs).forEach { (s, us) -> println("[fuzzy-latency]   %-10s %6dµs".format(s, us)) }
        // Only 3+ letter prefixes can take the fuzzy path; 1–2 letter prefixes are the
        // pre-existing exact path (e.g. "s" scores ~10k exact completions) and are reported
        // above but not bounded here.
        val fuzzyEligible = inputs.zip(avgUs).filter { it.first.length >= 3 }
        val worst = fuzzyEligible.maxByOrNull { it.second }!!
        val mean = fuzzyEligible.map { it.second }.average()
        println("[fuzzy-latency] 3+ letters: mean=${"%.0f".format(mean)}µs worst=${worst.second}µs ('${worst.first}') over ${fuzzyEligible.size} prefixes × $rounds rounds")
        // Generous ceilings (shared, loaded 4-core dev phone JVM): a typing frame is 16ms.
        assertTrue("mean per-keystroke prediction ${mean}µs exceeds 5ms", mean < 5_000)
        assertTrue("worst per-keystroke prediction ${worst.second}µs ('${worst.first}') exceeds 15ms", worst.second < 15_000)
    }

    // ── helpers ───────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun dictField(): MutableMap<String, Int> {
        val f = WordPredictor::class.java.getDeclaredField("dictionary")
        f.isAccessible = true
        return (f.get(predictor) as AtomicReference<MutableMap<String, Int>>).get()
    }

    private fun rebuildIndex() {
        setField("prefixIndex", AtomicReference(prefixIndexOf(dictField().keys)))
        setField("cachedMaxFreqForSize", -1)
    }
}
