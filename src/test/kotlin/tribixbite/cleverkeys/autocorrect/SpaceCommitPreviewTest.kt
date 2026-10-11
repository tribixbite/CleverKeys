package tribixbite.cleverkeys.autocorrect

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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.Defaults
import tribixbite.cleverkeys.ProvenanceNote
import tribixbite.cleverkeys.Suggestion
import tribixbite.cleverkeys.SuggestionMeta
import tribixbite.cleverkeys.SuggestionOrigin
import tribixbite.cleverkeys.WordPredictor
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Bar highlight == space commit (Pixel report 2026-10-11: typing `Helo` highlighted `Hello`,
 * space committed `Help`).
 *
 * Drives the REAL `WordPredictor` — `predictWordsWithContext` (the tap-typing bar's source) and
 * `autoCorrect` (the space path's correction) — over the REAL bundled 98k English dictionary on
 * the device frequency scale with the shipped autocorrect defaults, harnessed like
 * `DroppedDoubleAutocorrectEval` (Objenesis instance, reflection-injected fields, MockK runner
 * for `Log`/`Trace`). Then checks that [SpaceCommitResolver] + [BarHighlightPlanner] make the
 * highlighted entry exactly what space would commit, in every guard state.
 */
class SpaceCommitPreviewTest {

    companion object {
        private lateinit var predictor: WordPredictor
        private lateinit var config: Config

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
            KeyAdjacency.resetLayout()

            val gson = Gson()
            val rawDict: Map<String, Double> = File("src/main/assets/dictionaries/en_enhanced.json")
                .reader().use { gson.fromJson(it, object : TypeToken<Map<String, Double>>() {}.type) }
            // Same rank → frequency conversion as BinaryDictionaryLoader V2 (the device path).
            val dict = HashMap<String, Int>(rawDict.size * 2)
            for ((word, byteFreq) in rawDict) dict[word] = 1_000_000 - (255 - byteFreq.toInt()) * 3900

            // Real contraction aliases, as loadContractionKeysAsync injects them.
            val contractions: Map<String, String> = File("src/main/assets/dictionaries/contractions_en.json")
                .reader().use { gson.fromJson(it, object : TypeToken<Map<String, String>>() {}.type) }
            val realWordBases = readCompanionSet("REAL_WORD_CONTRACTION_BASES")
            val aliases = HashMap<String, String>()
            for ((bareRaw, apoRaw) in contractions) {
                val bare = bareRaw.lowercase()
                val apo = apoRaw.lowercase()
                if (bare in realWordBases) continue
                aliases[bare] = apo
                dict[bare] = dict[apo] ?: dict[bare] ?: 5000
            }
            val index = HashMap<String, MutableSet<String>>()
            for (w in dict.keys) {
                for (len in 1..minOf(3, w.length)) index.getOrPut(w.substring(0, len)) { HashSet() }.add(w)
            }

            config = org.objenesis.ObjenesisStd().newInstance(Config::class.java)
            predictor = org.objenesis.ObjenesisStd().newInstance(WordPredictor::class.java)
            setField("dictionary", AtomicReference<MutableMap<String, Int>>(dict))
            setField("prefixIndex", AtomicReference<MutableMap<String, MutableSet<String>>>(index))
            setField("contractionAliases", aliases)
            setField("customAndUserWords", emptySet<String>())
            setField("disabledWords", mutableSetOf<String>())
            setField("userWordOriginalCase", ConcurrentHashMap<String, String>())
            setField("config", config)
            setField("cachedMaxFreqForSize", -1)
            setField("secondaryIndex", null)
            setField("secondaryLanguageCode", "none")
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

        @Suppress("UNCHECKED_CAST")
        private fun readCompanionSet(name: String): Set<String> {
            val field = WordPredictor::class.java.getDeclaredField(name)
            field.isAccessible = true
            return field.get(null) as Set<String>
        }

        /** Issue #72 I-word re-caser with the setting on (SuggestionHandler.capitalizeIWord's rule). */
        private fun capitalizeI(word: String): String =
            if (word.lowercase() in setOf("i", "i'm", "i'll", "i'd", "i've")) {
                word.replaceFirstChar { it.uppercaseChar() }
            } else {
                word
            }
    }

    @Before
    fun shippedDefaults() {
        // Objenesis skipped Config's initializers: set what autoCorrect and the ranker read.
        config.autocorrect_enabled = true
        config.autocorrect_min_word_length = Defaults.AUTOCORRECT_MIN_WORD_LENGTH
        config.autocorrect_char_match_threshold = Defaults.AUTOCORRECT_CHAR_MATCH_THRESHOLD
        config.autocorrect_max_length_diff = Defaults.AUTOCORRECT_MAX_LENGTH_DIFF
        config.autocorrect_confidence_min_frequency = Defaults.AUTOCORRECT_MIN_FREQUENCY
        config.autocorrect_prefix_length = Defaults.AUTOCORRECT_PREFIX_LENGTH
        config.swipe_debug_detailed_logging = false
        config.prediction_frequency_scale = Defaults.PREDICTION_FREQUENCY_SCALE
        config.prediction_context_boost = Defaults.PREDICTION_CONTEXT_BOOST
        config.context_source = Defaults.CONTEXT_SOURCE
        config.personalization_weight = Defaults.PERSONALIZATION_WEIGHT
        config.secondary_prediction_weight = Defaults.SECONDARY_PREDICTION_WEIGHT
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun resolve(
        typed: String,
        inTerminal: Boolean = false,
        stemUnmerged: Boolean = false,
        nonProse: Boolean = false,
    ): SpaceCommit = SpaceCommitResolver.resolve(
        typed = typed,
        autocorrectEnabled = config.autocorrect_enabled,
        inTerminal = inTerminal,
        stemUnmerged = stemUnmerged,
        capitalizeI = ::capitalizeI,
        isNonProse = { nonProse },
        autoCorrect = { predictor.autoCorrect(it) },
    )

    /** The bar as the predictor ranks it (lowercase typing), before the highlight plan. */
    private fun barOf(typed: String): Triple<List<String>, List<Int>, List<SuggestionMeta>> {
        val r = predictor.predictWordsWithContext(typed, emptyList())
        val words = r.words.map { if (typed[0].isUpperCase()) it.replaceFirstChar(Char::uppercaseChar) else it }
        val metas = words.indices.map { r.metas?.getOrNull(it) ?: SuggestionMeta(SuggestionOrigin.DICTIONARY_PREFIX) }
        return Triple(words, r.scores, metas)
    }

    private fun planFor(typed: String, commit: SpaceCommit = resolve(typed)): BarHighlightPlanner.Plan {
        val (w, s, m) = barOf(typed)
        return BarHighlightPlanner.plan(w, s, m, typed, commit)
    }

    private fun BarHighlightPlanner.Plan.highlighted(): String? = highlightIndex?.let { words[it] }

    // ── the reported case ─────────────────────────────────────────────────

    @Test
    fun helo_highlightsTheWordSpaceCommits() {
        // The mismatch, pinned on the real dictionary: the ranker's first entry is not the
        // space-bar correction (on the device it read `Hello`; this context-free harness ranks
        // another completion first). If autocorrect's rule changes, `help` moves; the equality
        // between highlight and commit below must hold regardless.
        val bar = barOf("helo").first
        val commit = resolve("helo")
        assertNotEquals(commit.commit, bar.first())
        assertEquals(SpaceCommit(SpaceCommit.Kind.AUTOCORRECT, predictor.autoCorrect("helo")), commit)
        assertEquals("help", commit.commit)

        val plan = planFor("helo", commit)
        assertEquals(0, plan.highlightIndex)
        assertEquals(commit.commit, plan.highlighted())
        // Moved or inserted, never duplicated; the rest of the bar keeps the ranker's order.
        assertEquals(1, plan.words.count { it.equals("help", ignoreCase = true) })
        assertEquals(bar.filterNot { it == "help" }, plan.words.drop(1))
    }

    @Test
    fun titleCaseHelo_highlightsTitleCaseHelp() {
        val commit = resolve("Helo")
        assertEquals("Help", commit.commit)
        val plan = planFor("Helo", commit)
        assertEquals("Help", plan.highlighted())
        assertEquals(0, plan.highlightIndex)
    }

    @Test
    fun highlightEqualsSpaceCommit_acrossTypos() {
        // Every correction-bearing typo: highlighted entry == committed text, always slot 0.
        for (typed in listOf("helo", "teh", "becuase", "recieve", "questin", "wuestion", "tge", "gamees", "dont", "pkay")) {
            val commit = resolve(typed)
            val plan = planFor(typed, commit)
            if (commit.replaces) {
                assertEquals("'$typed'", commit.commit, plan.highlighted())
                assertEquals("'$typed'", 0, plan.highlightIndex)
            } else {
                assertTrue("'$typed' must not highlight a replacement (got ${plan.highlighted()})",
                    plan.highlighted() == null || plan.highlighted() == typed)
            }
        }
    }

    // ── no correction → no replacement highlighted ────────────────────────

    @Test
    fun validWord_highlightsOnlyTheTypedWord() {
        val commit = resolve("hello")
        assertEquals(SpaceCommit(SpaceCommit.Kind.KEEP, "hello"), commit)
        val plan = planFor("hello", commit)
        assertEquals("hello", plan.highlighted())
    }

    @Test
    fun incompletePrefix_highlightsNoCompletion() {
        // "hel" is not corrected; the bar still lists completions, none highlighted.
        val commit = resolve("hel")
        assertFalse(commit.replaces)
        val plan = planFor("hel", commit)
        assertTrue("completions still listed", plan.words.isNotEmpty())
        assertNull("no completion may be highlighted (got ${plan.highlighted()})",
            plan.highlightIndex?.takeIf { plan.words[it] != "hel" })
    }

    @Test
    fun autocorrectDisabled_noReplacementHighlighted() {
        config.autocorrect_enabled = false
        val commit = resolve("helo")
        assertEquals(SpaceCommit(SpaceCommit.Kind.KEEP, "helo"), commit)
        assertNull(planFor("helo", commit).highlightIndex)
    }

    @Test
    fun terminalEditor_noReplacementHighlighted() {
        val commit = resolve("helo", inTerminal = true)
        assertFalse(commit.replaces)
        assertNull(planFor("helo", commit).highlightIndex)
        // Terminal also skips I-capitalization, as the space path does.
        assertFalse(resolve("i", inTerminal = true).replaces)
    }

    @Test
    fun nonProseToken_noReplacementHighlighted() {
        val commit = resolve("helo", nonProse = true)
        assertFalse(commit.replaces)
        assertNull(planFor("helo", commit).highlightIndex)
    }

    @Test
    fun unmergedJoinerStem_noReplacementHighlighted() {
        assertFalse(resolve("helo", stemUnmerged = true).replaces)
        assertFalse(resolve("i", stemUnmerged = true).replaces)
    }

    @Test
    fun nonProseRead_onlyWhenACorrectionWouldFire() {
        var reads = 0
        fun run(typed: String) = SpaceCommitResolver.resolve(
            typed, true, false, false, ::capitalizeI, { reads++; false }, { predictor.autoCorrect(it) },
        )
        run("hello")
        run("hel")
        assertEquals("no editor read for a word that would not be corrected", 0, reads)
        run("helo")
        assertEquals(1, reads)
    }

    // ── I-word and contraction targets ────────────────────────────────────

    @Test
    fun iWord_capitalizedBeforeAutocorrect_evenWhenAutocorrectOff() {
        config.autocorrect_enabled = false
        assertEquals(SpaceCommit(SpaceCommit.Kind.CAPITALIZE_I, "I"), resolve("i"))
        val plan = BarHighlightPlanner.plan(
            listOf("in", "is", "I"), listOf(3, 2, 1), List(3) { SuggestionMeta(SuggestionOrigin.DICTIONARY_PREFIX) },
            "i", resolve("i"),
        )
        assertEquals(listOf("I", "in", "is"), plan.words)
        assertEquals(listOf(1, 3, 2), plan.scores)
        assertEquals(0, plan.highlightIndex)
    }

    @Test
    fun contractionAlias_isTheHighlightedTarget() {
        val commit = resolve("dont")
        assertEquals("don't", commit.commit)
        assertEquals("don't", planFor("dont", commit).highlighted())
    }

    // ── planner shape (pure) ──────────────────────────────────────────────

    @Test
    fun absentTarget_isInsertedWithTypoCorrectionProvenance() {
        val metas = List(2) { SuggestionMeta(SuggestionOrigin.DICTIONARY_PREFIX) }
        val plan = BarHighlightPlanner.plan(
            listOf("hello", "helot"), listOf(50, 10), metas, "helo",
            SpaceCommit(SpaceCommit.Kind.AUTOCORRECT, "help"),
        )
        assertEquals(listOf("help", "hello", "helot"), plan.words)
        assertEquals(listOf(50, 50, 10), plan.scores)
        assertEquals(SuggestionMeta(SuggestionOrigin.TYPO_CORRECTION, note = ProvenanceNote.TypoCorrectionOf("helo")), plan.metas[0])
        assertEquals(0, plan.highlightIndex)
    }

    @Test
    fun emptyBar_withCorrection_showsTheTarget() {
        val plan = BarHighlightPlanner.plan(emptyList(), emptyList(), emptyList(), "xqzt",
            SpaceCommit(SpaceCommit.Kind.AUTOCORRECT, "text"))
        assertEquals(listOf("text"), plan.words)
        assertEquals(0, plan.highlightIndex)
    }

    @Test
    fun protocolEntries_areNeverTargetsOrHighlights() {
        val exactAdd = Suggestion.ExactAdd("helo").wire
        val metas = List(2) { SuggestionMeta(SuggestionOrigin.DICTIONARY_PREFIX) }
        val keep = BarHighlightPlanner.plan(listOf("hello", exactAdd), listOf(2, 0), metas, "helo",
            SpaceCommit(SpaceCommit.Kind.KEEP, "helo"))
        assertNull("the exact-add chip is an action, not the commit item", keep.highlightIndex)
        val fix = BarHighlightPlanner.plan(listOf("hello", exactAdd), listOf(2, 0), metas, "helo",
            SpaceCommit(SpaceCommit.Kind.AUTOCORRECT, "help"))
        assertEquals(listOf("help", "hello", exactAdd), fix.words)
    }

    @Test
    fun existingTarget_isShownAsCommitted() {
        // Listed under another casing: shown exactly as space will write it.
        val metas = listOf(SuggestionMeta(SuggestionOrigin.DICTIONARY_PREFIX), SuggestionMeta(SuggestionOrigin.TYPO_CORRECTION))
        val plan = BarHighlightPlanner.plan(listOf("Hello", "Help"), listOf(9, 5), metas, "HELO",
            SpaceCommit(SpaceCommit.Kind.AUTOCORRECT, "HELP"))
        assertEquals(listOf("HELP", "Hello"), plan.words)
        assertEquals(listOf(5, 9), plan.scores)
        assertEquals(SuggestionOrigin.TYPO_CORRECTION, plan.metas[0].origin)
    }

    // ── single source of truth: the memo the space path reuses ────────────

    @Test
    fun memo_reusesBarResultForSameWord_recomputesOtherwise() {
        val memo = AutocorrectMemo()
        var sweeps = 0
        val compute: (String) -> String = { sweeps++; predictor.autoCorrect(it) }
        val owner = Any()
        assertEquals("help", memo.get("helo", owner, 1, compute))
        assertEquals("help", memo.get("helo", owner, 1, compute))
        assertEquals("space reuses the bar's sweep", 1, sweeps)
        // The typed word changed since the bar: recompute, never reuse.
        assertEquals("hello", memo.get("hello", owner, 1, compute))
        assertEquals(2, sweeps)
        // Settings fingerprint or predictor changed: recompute.
        memo.get("hello", owner, 2, compute)
        memo.get("hello", Any(), 2, compute)
        assertEquals(4, sweeps)
        memo.clear()
        memo.get("hello", owner, 2, compute)
        assertEquals(5, sweeps)
    }

    @Test
    fun preserveCapitalization_patterns() {
        assertEquals("THE", SpaceCommitResolver.preserveCapitalization("TEH", "the"))
        assertEquals("The", SpaceCommitResolver.preserveCapitalization("Teh", "the"))
        assertEquals("the", SpaceCommitResolver.preserveCapitalization("teh", "the"))
    }

    // ── cost: is a per-keystroke autoCorrect affordable? ──────────────────

    @Test
    fun perKeystrokeAutocorrectCost_isReported_andBounded() {
        // Every prefix of typo'd and valid words, i.e. what the bar now resolves keystroke by
        // keystroke (on the prediction worker, not the UI thread).
        val typedWords = listOf("helo", "becuase", "wuestion", "somethimg", "questin", "tomorow", "xorblaxx", "beautiful")
        val inputs = typedWords.flatMap { w -> (1..w.length).map { w.substring(0, it) } }
        fun resolveOnly(s: String) = resolve(s)
        fun barOnly(s: String) = predictor.predictWordsWithContext(s, emptyList())
        repeat(3) { inputs.forEach { resolveOnly(it); barOnly(it) } } // JIT warm-up
        val rounds = 10
        val ac = LongArray(inputs.size)
        val bar = LongArray(inputs.size)
        for (r in 0 until rounds) {
            inputs.forEachIndexed { i, s ->
                var t0 = System.nanoTime()
                barOnly(s)
                bar[i] += System.nanoTime() - t0
                t0 = System.nanoTime()
                resolveOnly(s)
                ac[i] += System.nanoTime() - t0
            }
        }
        val acUs = ac.map { it / rounds / 1000 }
        val barUs = bar.map { it / rounds / 1000 }
        inputs.indices.forEach { i ->
            println("[space-commit-latency]   %-10s bar=%6dµs resolve=%6dµs".format(inputs[i], barUs[i], acUs[i]))
        }
        val mean = acUs.average()
        val worst = inputs.indices.maxByOrNull { acUs[it] }!!
        println("[space-commit-latency] resolve: mean=${"%.0f".format(mean)}µs worst=${acUs[worst]}µs ('${inputs[worst]}'); " +
            "bar mean=${"%.0f".format(barUs.average())}µs over ${inputs.size} prefixes × $rounds rounds")
        // Generous ceilings for the shared, loaded 4-core dev phone JVM (measured 2026-10-11 at
        // load ~10: mean ≈20ms, worst ≈63ms; valid words and 1-3 letter prefixes ≈0). It runs on
        // the prediction worker, never the UI thread, and the space press reuses the result.
        assertTrue("mean per-keystroke resolve ${mean}µs exceeds 40ms", mean < 40_000)
        assertTrue("worst per-keystroke resolve ${acUs[worst]}µs exceeds 150ms", acUs[worst] < 150_000)
    }
}
