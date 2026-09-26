package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Assume
import org.junit.Test
import tribixbite.cleverkeys.contextaware.ContextContinuation
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stage S1 evaluation of the shipped static context LM (`assets/lm/en.cklm`) on TAP prediction —
 * a measurement instrument, not a regression gate (it prints a report and asserts only that it
 * measured something).
 *
 * ## Populations
 *
 * - **OOD** (the gate population): UD English-EWT *test* surface sentences — web reviews, email,
 *   forum and answers text the model never saw. Eval-only; UD is CC BY-SA and is never shipped.
 * - **In-domain**: a deterministic ~10% sample of the builder's own held-out split (Leipzig +
 *   Tatoeba sentences whose hash put them outside training).
 *
 * Both files are written by `scripts/build_static_lm.py` to
 * `~/.cache/cleverkeys-corpora/static-lm-eval/` and are LOCAL ONLY; the test Assume-skips when
 * they are absent. Every sentence is tokenized here by the device's own
 * [NextWordPredictor.contextFromEditorText], so the eval sees exactly the context the keyboard
 * would, whatever the builder's tokenizer did.
 *
 * ## Metrics
 *
 * 1. **Next-word** top-1/top-3: the continuation list for the previous word, against the
 *    most-frequent-words baseline, and through the REAL [NextWordPredictor.generate] tiering
 *    (learned first, static fill) for the learned/both arms.
 * 2. **Prefix re-rank** top-3 at prefix lengths 1–3: every lexicon completion of the typed prefix
 *    scored through the REAL [UnifiedScore.combine] with the shipped default settings
 *    (context boost 0.5, frequency scale 100, no adaptation/personalization), the frequency the
 *    tap predictor derives from `en_enhanced.bin`, and the tap predictor's prefix score.
 *
 * ## Arms (the `context_source` pref, plus the status quo)
 *
 * | arm | static multiplier | learned boost | source |
 * |---|---|---|---|
 * | none | 1 | 1 | — (baseline) |
 * | legacy_static | today's hardcoded `BigramModel` table | 1 | static_only |
 * | lm_static | this LM | 1 | static_only |
 * | learned_only | 1 | device export | learned_only |
 * | legacy_both | hardcoded table | device export | both (today's default) |
 * | lm_both | this LM | device export | both (the new default) |
 *
 * The learned arms read `~/.cache/cleverkeys-corpora/device_bigrams.json` — the maintainer's own
 * export, PRIVATE: only aggregate rates are printed, never a pair.
 *
 * ## Exactness of the fast path
 *
 * Scoring all ~98k lexicon words for every position would take hours on this box, so for each
 * position only the candidates whose multiplier can DIFFER from the arm's uniform default (the
 * listed continuations / learned pairs / hardcoded words) are scored individually; every other
 * candidate shares one multiplier, so their relative order is the context-free order, and the top
 * `BAR + |special|` of them suffice. Every score that decides a rank still goes through
 * [UnifiedScore.combine].
 */
class StaticLmTapEvalTest {

    private val corpora = File(System.getProperty("user.home"), ".cache/cleverkeys-corpora")
    private val evalDir = File(corpora, "static-lm-eval")

    /** One arm's static/learned sources. */
    private enum class Arm(val static: StaticSource, val learned: Boolean, val source: String) {
        NONE(StaticSource.NONE, false, UnifiedScore.SOURCE_BOTH),
        LEGACY_STATIC(StaticSource.LEGACY, false, UnifiedScore.SOURCE_STATIC_ONLY),
        LM_STATIC(StaticSource.LM, false, UnifiedScore.SOURCE_STATIC_ONLY),
        LEARNED_ONLY(StaticSource.NONE, true, UnifiedScore.SOURCE_LEARNED_ONLY),
        LEGACY_BOTH(StaticSource.LEGACY, true, UnifiedScore.SOURCE_BOTH),
        LM_BOTH(StaticSource.LM, true, UnifiedScore.SOURCE_BOTH),
    }

    private enum class StaticSource { NONE, LEGACY, LM }

    /** Hits per arm for one metric cell. */
    private class Cell {
        var n = 0
        val top1 = IntArray(Arm.entries.size)
        val top3 = IntArray(Arm.entries.size)
        fun pct(hits: Int) = if (n == 0) 0.0 else 100.0 * hits / n
    }

    private class Lexicon(val words: Array<String>, val freq: IntArray) {
        val index: Map<String, Int> = words.withIndex().associate { it.value to it.index }
        /** prefix → word indices sorted by the context-free score for that prefix length. */
        val byPrefix = HashMap<String, IntArray>()
        /** positions[p-1][word] = the word's index in its length-p prefix list (−1 if shorter). */
        val positions = Array(3) { IntArray(words.size) { -1 } }
    }

    @Test
    fun evaluateStaticLmOnTapPrediction() {
        // OPT-IN measurement (minutes on a loaded box), same switch as the replay instruments:
        //   scripts/gradle-guard.sh runPureTests -PtestClass=StaticLmTapEvalTest -PgeoFull=true
        if (System.getProperty("geoFull") != "true") {
            println("[skip] static LM tap eval — set -PgeoFull=true to run")
            return
        }
        val lmFile = File("src/main/assets/lm/en.cklm")
        val oodFile = File(evalDir, "ood_test_en.txt")
        val heldFile = File(evalDir, "heldout_en.txt")
        Assume.assumeTrue("no shipped LM at ${lmFile.path}", lmFile.exists())
        Assume.assumeTrue("no eval sentences in $evalDir — run scripts/build_static_lm.py (local only)",
            oodFile.exists() && heldFile.exists())

        val loadStart = System.nanoTime()
        val lm = StaticContextLm.parse(lmFile.readBytes())
        val loadMs = (System.nanoTime() - loadStart) / 1e6
        val lexicon = loadLexicon(File("src/main/assets/dictionaries/en_enhanced.bin"))
        buildPrefixIndex(lexicon)
        val allowed = lexicon.index.keys + contractionForms()
        val learned = loadLearned(File(corpora, "device_bigrams.json"))

        println("═══════════════════════════════════════════════════════════════")
        println("  STATIC LM — TAP EVAL (S1)  lm=${lm.language} vocab=${lm.vocabSize} prevs=${lm.prevCount} " +
            "pairs=${lm.pairCount} bytes=${lmFile.length()} parse=%.1f ms".format(loadMs))
        println("  learned arm: " + if (learned == null) "ABSENT (device export missing — learned arms = none)"
            else "device export, ${learned.values.sumOf { it.size }} confident pairs over ${learned.size} prev words")

        val ood = readSentences(oodFile, sampleEvery = 1)
        val held = readSentences(heldFile, sampleEvery = HELD_SAMPLE)
        for ((label, sents) in listOf("OOD (UD EWT test) — GATE POPULATION" to ood, "IN-DOMAIN held-out (1/$HELD_SAMPLE sample)" to held)) {
            report(label, sents, lm, lexicon, allowed, learned)
        }
        println("═══════════════════════════════════════════════════════════════")
        assertThat(ood).isNotEmpty()
    }

    // ── report ──────────────────────────────────────────────────────────────────────────────

    private fun report(
        label: String,
        sentences: List<List<String>>,
        lm: StaticContextLm,
        lexicon: Lexicon,
        allowed: Set<String>,
        learned: Map<String, List<ContextContinuation>>?,
    ) {
        val nextWord = Cell()
        val prefix = Array(3) { Cell() }
        val emptyCtx = Array(3) { Cell() }
        var positions = 0
        var targetOov = 0
        var prevKnown = 0
        val unigramTop = topUnigrams(lm, allowed, 3)
        val legacySeed = legacySeed()

        for (toks in sentences) {
            for (i in toks.indices) {
                val target = toks[i]
                // Sentence-initial: context empty — every arm must equal the baseline.
                if (i == 0) {
                    for (p in 1..3) rankPrefix(target, p, null, lm, lexicon, learned)?.let { tally(emptyCtx[p - 1], it) }
                    continue
                }
                positions++
                val prev = toks[i - 1]
                if (lm.hasContext(prev)) prevKnown++
                if (target !in allowed) { targetOov++ } else {
                    nextWord.n++
                    val lists = nextWordLists(prev, lm, learned, legacySeed, unigramTop, allowed)
                    for (arm in Arm.entries) {
                        val list = lists.getValue(arm)
                        if (list.firstOrNull() == target) nextWord.top1[arm.ordinal]++
                        if (target in list.take(3)) nextWord.top3[arm.ordinal]++
                    }
                }
                for (p in 1..3) rankPrefix(target, p, prev, lm, lexicon, learned)?.let { tally(prefix[p - 1], it) }
            }
        }

        println("  ─────────────────────────────────────────────────────────────")
        println("  $label: ${sentences.size} sentences, $positions positions with context; " +
            "target outside lexicon∪contractions ${pct(targetOov, positions)}; prev word known to LM ${pct(prevKnown, positions)}")
        println("     NEXT-WORD (n=${nextWord.n}; baseline 'none' = corpus top unigrams ${unigramTop})")
        printCell(nextWord)
        for (p in 1..3) {
            println("     PREFIX RE-RANK, prefix=$p (n=${prefix[p - 1].n}) — target in top-3 of UnifiedScore.combine")
            printCell(prefix[p - 1])
        }
        for (p in 1..3) {
            val c = emptyCtx[p - 1]
            val base = c.top3[Arm.NONE.ordinal]
            val maxDev = Arm.entries.maxOf { kotlin.math.abs(c.top3[it.ordinal] - base) }
            println("     EMPTY CONTEXT prefix=$p (n=${c.n}): top-3 %.2f%%; max arm deviation from none = %d cases"
                .format(c.pct(base), maxDev))
        }
        val gate1 = prefix[0].pct(prefix[0].top3[Arm.LM_STATIC.ordinal]) - prefix[0].pct(prefix[0].top3[Arm.NONE.ordinal])
        val gate2 = prefix[1].pct(prefix[1].top3[Arm.LM_STATIC.ordinal]) - prefix[1].pct(prefix[1].top3[Arm.NONE.ordinal])
        val gate3 = prefix[2].pct(prefix[2].top3[Arm.LM_STATIC.ordinal]) - prefix[2].pct(prefix[2].top3[Arm.NONE.ordinal])
        println("     GATE A (lm_static vs none): prefix-1 %+.2f pt (need ≥ +5), prefix-2 %+.2f pt (need ≥ +2), prefix-3 %+.2f pt (need ≥ 0)"
            .format(gate1, gate2, gate3))
        val both3 = prefix[2].pct(prefix[2].top3[Arm.LM_BOTH.ordinal]) - prefix[2].pct(prefix[2].top3[Arm.LEGACY_BOTH.ordinal])
        println("     default-mode change at prefix-3 (lm_both vs legacy_both): %+.2f pt".format(both3))
    }

    private fun printCell(c: Cell) {
        val base1 = c.pct(c.top1[Arm.NONE.ordinal])
        val base3 = c.pct(c.top3[Arm.NONE.ordinal])
        for (arm in Arm.entries) {
            val t1 = c.pct(c.top1[arm.ordinal])
            val t3 = c.pct(c.top3[arm.ordinal])
            println("        %-14s top-1 %6.2f%% (%+6.2f)   top-3 %6.2f%% (%+6.2f)".format(
                arm.name.lowercase(), t1, t1 - base1, t3, t3 - base3))
        }
    }

    private fun tally(cell: Cell, ranks: IntArray) {
        cell.n++
        for (arm in Arm.entries) {
            val r = ranks[arm.ordinal]
            if (r == 0) cell.top1[arm.ordinal]++
            if (r in 0..2) cell.top3[arm.ordinal]++
        }
    }

    // ── next-word ───────────────────────────────────────────────────────────────────────────

    private fun nextWordLists(
        prev: String,
        lm: StaticContextLm,
        learned: Map<String, List<ContextContinuation>>?,
        legacySeed: StaticBigramSeed.Index,
        unigramTop: List<String>,
        allowed: Set<String>,
    ): Map<Arm, List<String>> {
        val lmSeed = lm.top(prev, NextWordPredictor.MAX_SUGGESTIONS + 1)
            .map { StaticBigramSeed.Continuation(it.word, it.probability) }
        val oldSeed = legacySeed.top(prev, NextWordPredictor.MAX_SUGGESTIONS + 1)
        val learnedList = learned?.get(prev).orEmpty()
        fun gen(l: List<ContextContinuation>, seed: List<StaticBigramSeed.Continuation>): List<String> =
            NextWordPredictor.generate(
                learned = l,
                lastCommittedWord = prev,
                personalizationBoost = { 0f },
                isWordAllowed = { it in allowed },
                staticSeed = seed,
            ).map { it.word }
        return mapOf(
            Arm.NONE to unigramTop,
            Arm.LEGACY_STATIC to gen(emptyList(), oldSeed),
            Arm.LM_STATIC to gen(emptyList(), lmSeed),
            Arm.LEARNED_ONLY to gen(learnedList, emptyList()),
            Arm.LEGACY_BOTH to gen(learnedList, oldSeed),
            Arm.LM_BOTH to gen(learnedList, lmSeed),
        )
    }

    // ── prefix re-rank ──────────────────────────────────────────────────────────────────────

    /** Rank (0-based) of [target] under every arm, or null when the position is not scorable. */
    private fun rankPrefix(
        target: String,
        plen: Int,
        prev: String?,
        lm: StaticContextLm,
        lexicon: Lexicon,
        learned: Map<String, List<ContextContinuation>>?,
    ): IntArray? {
        if (target.length <= plen) return null
        val targetIdx = lexicon.index[target] ?: return null
        val prefix = target.substring(0, plen)
        val cands = lexicon.byPrefix[prefix] ?: return null
        val posOf = lexicon.positions[plen - 1]
        val ranks = IntArray(Arm.entries.size)

        // The words whose multiplier can differ from the arm's uniform default at this position.
        val lmSpecial = if (prev == null) emptyList() else lm.top(prev, StaticContextLm.MAX_CONTINUATIONS).map { it.word }
        val learnedFor: Map<String, Float> = if (prev == null) emptyMap()
            else learned?.get(prev).orEmpty().associate { it.word to boostOf(it) }

        for (arm in Arm.entries) {
            val staticOf: (String) -> Float = when (arm.static) {
                StaticSource.NONE -> { _ -> 1f }
                StaticSource.LM -> { w -> if (prev == null) 1f else clampMult(lm.contextRatio(prev, w)) }
                StaticSource.LEGACY -> { w -> LegacyEnglishContext.multiplier(w, prev) }
            }
            val learnedOf: (String) -> Float = if (arm.learned) { w -> learnedFor[w] ?: 1f } else { _ -> 1f }
            val special = HashSet<Int>()
            when (arm.static) {
                StaticSource.LM -> lmSpecial.forEach { w -> lexicon.index[w]?.let(special::add) }
                StaticSource.LEGACY -> if (prev != null) LegacyEnglishContext.WORDS.forEach { w -> lexicon.index[w]?.let(special::add) }
                StaticSource.NONE -> Unit
            }
            if (arm.learned) learnedFor.keys.forEach { w -> lexicon.index[w]?.let(special::add) }

            // Scored: every special matching the prefix + the first (BAR + |special|) others in
            // context-free order (they share one multiplier, so that order is theirs).
            val scored = ArrayList<Int>(BAR + 2 * special.size)
            val need = BAR + special.size
            var others = 0
            for (wi in cands) {
                if (wi in special) continue
                scored.add(wi)
                if (++others >= need) break
            }
            for (wi in special) if (lexicon.words[wi].startsWith(prefix)) scored.add(wi)

            var bestRank = Int.MAX_VALUE
            val targetScore = score(targetIdx, prefix, arm, staticOf, learnedOf, lexicon)
            if (targetIdx !in scored) { ranks[arm.ordinal] = bestRank; continue }
            val targetPos = posOf[targetIdx]
            var ahead = 0
            for (wi in scored) {
                if (wi == targetIdx) continue
                val s = score(wi, prefix, arm, staticOf, learnedOf, lexicon)
                // Ties keep the context-free order (the predictor's stable sort over its candidates).
                if (s > targetScore || (s == targetScore && posOf[wi] < targetPos)) ahead++
            }
            bestRank = ahead
            ranks[arm.ordinal] = bestRank
        }
        return ranks
    }

    private fun score(
        wi: Int,
        prefix: String,
        arm: Arm,
        staticOf: (String) -> Float,
        learnedOf: (String) -> Float,
        lexicon: Lexicon,
    ): Int {
        val w = lexicon.words[wi]
        return UnifiedScore.combine(
            prefixScore = prefixScore(w, prefix),
            adaptationMultiplier = 1f,
            staticContextMultiplier = staticOf(w),
            dynamicContextBoost = learnedOf(w),
            contextSource = arm.source,
            personalizationBoost = 0f,
            personalizationWeight = Defaults.PERSONALIZATION_WEIGHT,
            frequency = lexicon.freq[wi],
            frequencyScale = Defaults.PREDICTION_FREQUENCY_SCALE,
            contextBoost = Defaults.PREDICTION_CONTEXT_BOOST,
        ).finalScore
    }

    /**
     * `WordPredictor.calculatePrefixScore`, restated: it is private at this commit (a direct match
     * scores 1000; a completion 800 + 50 per typed letter − 10 per letter beyond six).
     * TODO(static-lm-eval): call `WordPredictor.completionPrefixScore` once that internal
     * accessor is committed, so this copy cannot drift.
     */
    private fun prefixScore(word: String, prefix: String): Int =
        if (word == prefix) 1000
        else 800 + prefix.length * 50 - maxOf(0, (word.length - 6) * 10)

    // ── data ────────────────────────────────────────────────────────────────────────────────

    /** en_enhanced.bin (CKDT v2): rank byte → the tap predictor's `1_000_000 − rank × 3900`. */
    private fun loadLexicon(file: File): Lexicon {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        check(b.int == 0x54444B43 && b.int == 2) { "unexpected dictionary header" }
        b.position(b.position() + 4)
        val count = b.int
        val canonical = b.int
        b.position(canonical)
        val words = arrayOfNulls<String>(count)
        val freq = IntArray(count)
        for (i in 0 until count) {
            val len = b.short.toInt() and 0xFFFF
            val bytes = ByteArray(len).also { b.get(it) }
            words[i] = String(bytes, Charsets.UTF_8).lowercase()
            freq[i] = 1_000_000 - (b.get().toInt() and 0xFF) * 3900
        }
        @Suppress("UNCHECKED_CAST")
        return Lexicon(words as Array<String>, freq)
    }

    private fun buildPrefixIndex(lex: Lexicon) {
        val buckets = HashMap<String, MutableList<Int>>()
        for ((i, w) in lex.words.withIndex()) {
            for (p in 1..minOf(3, w.length)) buckets.getOrPut(w.substring(0, p)) { ArrayList() }.add(i)
        }
        for ((prefix, list) in buckets) {
            val base = list.associateWith { wi ->
                UnifiedScore.combine(prefixScore(lex.words[wi], prefix), 1f, 1f, 1f, UnifiedScore.SOURCE_BOTH,
                    0f, Defaults.PERSONALIZATION_WEIGHT, lex.freq[wi], Defaults.PREDICTION_FREQUENCY_SCALE,
                    Defaults.PREDICTION_CONTEXT_BOOST).finalScore
            }
            val sorted = list.sortedWith(compareByDescending<Int> { base.getValue(it) }.thenBy { lex.words[it] })
                .toIntArray()
            lex.byPrefix[prefix] = sorted
            for ((pos, wi) in sorted.withIndex()) lex.positions[prefix.length - 1][wi] = pos
        }
    }

    private fun contractionForms(): Set<String> {
        val dir = File("src/main/assets/dictionaries")
        val out = HashSet<String>()
        for (name in listOf("contractions_en.json", "contractions_non_paired.json")) {
            val o = org.json.JSONObject(File(dir, name).readText())
            for (k in o.keys()) out.add(o.getString(k).lowercase())
        }
        val pairings = org.json.JSONObject(File(dir, "contraction_pairings.json").readText())
        for (k in pairings.keys()) {
            val arr = pairings.getJSONArray(k)
            for (i in 0 until arr.length()) out.add(arr.getJSONObject(i).getString("contraction").lowercase())
        }
        return out
    }

    /** Device export → prev → confident continuations (store floors applied), probability-ranked. */
    private fun loadLearned(file: File): Map<String, List<ContextContinuation>>? {
        if (!file.exists()) return null
        val rows = org.json.JSONObject(file.readText()).getJSONObject("learned_bigrams_by_language").optJSONArray("en")
            ?: return null
        val out = HashMap<String, MutableList<ContextContinuation>>()
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val freq = r.getInt("frequency")
            val p = r.getDouble("probability").toFloat()
            if (freq < LEARNED_MIN_FREQUENCY || p < LEARNED_MIN_PROB) continue
            out.getOrPut(r.getString("word1").lowercase()) { ArrayList() }
                .add(ContextContinuation(r.getString("word2").lowercase(), freq, p, false))
        }
        out.values.forEach { l -> l.sortWith(compareByDescending<ContextContinuation> { it.probability }.thenBy { it.word }) }
        return out
    }

    /** `ContextModel.calculateBoost`: (1 + p)² clamped to [1, 5]. */
    private fun boostOf(c: ContextContinuation): Float =
        ((1.0 + c.probability) * (1.0 + c.probability)).toFloat().coerceIn(1f, 5f)

    private fun legacySeed(): StaticBigramSeed.Index = StaticBigramSeed.build(
        StaticBigramSeed.parseAsset(File("src/main/assets/bigrams/en_bigrams.json").readText()),
        LegacyEnglishContext.PAIRS,
    )

    private fun topUnigrams(lm: StaticContextLm, allowed: Set<String>, k: Int): List<String> {
        val all = ArrayList<Pair<String, Float>>()
        lm.forEachWord { w, p -> if (w in allowed) all.add(w to p) }
        return all.sortedWith(compareByDescending<Pair<String, Float>> { it.second }.thenBy { it.first }).take(k).map { it.first }
    }

    private fun readSentences(file: File, sampleEvery: Int): List<List<String>> =
        file.readLines().asSequence()
            .filter { sampleEvery == 1 || Math.floorMod(it.hashCode(), sampleEvery) == 0 }
            .map { NextWordPredictor.contextFromEditorText(it, Int.MAX_VALUE) }
            .filter { it.size >= 2 }
            .toList()

    private fun clampMult(r: Float): Float = r.coerceIn(0.1f, 10f)

    private fun pct(a: Int, n: Int) = "%.1f%%".format(if (n == 0) 0.0 else 100.0 * a / n)

    private companion object {
        const val BAR = 3
        const val HELD_SAMPLE = 20
        const val LEARNED_MIN_FREQUENCY = 2
        /** `ContextModel.MIN_BIGRAM_PROB` — the store's confident-probability floor for a boost. */
        const val LEARNED_MIN_PROB = 0.01f
    }
}
