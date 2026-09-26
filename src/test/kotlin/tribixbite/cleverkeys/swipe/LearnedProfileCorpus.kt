package tribixbite.cleverkeys.swipe

import org.json.JSONObject
import tribixbite.cleverkeys.personalization.UserWordUsage
import tribixbite.cleverkeys.swipe.ctc.CtcLearnedPrior
import java.io.File
import java.util.Locale

/**
 * Loads a REAL learned-usage profile — the `user_vocabulary` array of an app dictionary export
 * (Settings → Backup & Restore → export dictionaries) — into the evidence shape
 * [CtcLearnedPrior] consumes.
 *
 * **The file is never committed and nothing read from it may be.** It is one person's typing
 * record; this repo is public. Callers report aggregates only.
 *
 * ## What the export carries, and what it does not
 *
 * Each entry is `{word, usageCount, lastUsed, firstUsed}` — the personalization vocabulary.
 * It does NOT carry manual selections (those live in `SelectionHistory`, which the export
 * omits), so every profile loaded here has `manualSelections = 0`. The selection-margin half
 * of the prior is therefore exercised only by the synthetic profiles.
 *
 * Recency is the vocabulary's own decay ([UserWordUsage.getRecencyScore]) evaluated at the
 * export instant — taken as the newest `lastUsed` in the file, the latest moment the record
 * is known to describe — so the profile is what the device would have used on that day.
 *
 * ## Contamination (why P_real is conservative)
 *
 * Before the 2026-09-26 learning fixes, swipe AUTO-INSERT was recorded as usage (audit W1),
 * so this vocabulary also counts words the engine chose for the user, including wrong ones.
 * That inflates exactly the words the engine already prefers — the prior then reinforces the
 * status quo more than a clean record would, which biases P_real toward FEWER fixes. It
 * cannot manufacture fixes the user did not earn.
 */
object LearnedProfileCorpus {

    /** A loaded profile plus the aggregate facts a report may quote. */
    data class Profile(
        val evidence: Map<String, CtcLearnedPrior.WordEvidence>,
        /** Entries in the export's `user_vocabulary`. */
        val total: Int,
        /** Entries with `effectiveUses ≥ MIN_EFFECTIVE_USES` at the reference time. */
        val eligible: Int,
        /** The reference instant recency was evaluated at (ms since epoch). */
        val referenceTime: Long,
    ) {
        fun lookup(word: String): CtcLearnedPrior.WordEvidence? = evidence[word]
    }

    fun parse(exportFile: File): Profile {
        require(exportFile.isFile) { "no export at ${exportFile.path} (local-only, never committed)" }
        val arr = JSONObject(exportFile.readText()).optJSONArray("user_vocabulary")
            ?: return Profile(emptyMap(), 0, 0, 0L)
        val usages = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val last = o.getLong("lastUsed")
            UserWordUsage(
                word = o.getString("word").lowercase(Locale.ROOT),
                usageCount = o.getInt("usageCount"),
                lastUsed = last,
                firstUsed = o.optLong("firstUsed", last),
            )
        }
        val reference = usages.maxOfOrNull { it.lastUsed } ?: 0L
        val evidence = HashMap<String, CtcLearnedPrior.WordEvidence>(usages.size * 2)
        for (u in usages) {
            // A duplicate (should not happen — the store is keyed by normalized word) keeps
            // the larger count rather than silently the last one read.
            val ev = CtcLearnedPrior.WordEvidence(
                usage = u.usageCount,
                recency = u.getRecencyScore(reference).toDouble(),
                manualSelections = 0,
            )
            val prev = evidence[u.word]
            if (prev == null || prev.usage < ev.usage) evidence[u.word] = ev
        }
        val eligible = evidence.values.count {
            it.effectiveUses >= CtcLearnedPrior.USAGE_MIN_EFFECTIVE_USES
        }
        return Profile(evidence, usages.size, eligible, reference)
    }
}

/**
 * The local real-trace pool and the synthetic `git` shapes shared by the learned-prior replays
 * ([LearnedUnigramReplayTest], [CorrectionPriorReplayTest]) — one loader, one split rule, so
 * the two evaluations are measured on byte-identical trace sets and halves.
 *
 * The pool (`combined_english_swipes.jsonl.gz`) is local-only and never committed.
 */
object LearnedPriorTracePool {

    /** The pool file under `$CLEVERKEYS_TEST_CACHE` (default `~/.cache/cleverkeys-test`). */
    val traceFile: File = run {
        val override = System.getenv("CLEVERKEYS_TEST_CACHE")
        val dir = if (!override.isNullOrEmpty()) File(override)
            else File(System.getProperty("user.home"), ".cache/cleverkeys-test")
        File(dir, "combined_english_swipes.jsonl.gz")
    }

    /** One usable real trace in the CTC engine's normalized frame. */
    class Row(val word: String, val x: DoubleArray, val y: DoubleArray, val t: DoubleArray) {
        /** Trace identity without context — the unit of the tune/confirm split. */
        val id: String = "$word|${x.size}|${x.firstOrNull()}"

        /** The context-rescoring split convention: `id.hashCode()` parity. */
        val tuneHalf: Boolean get() = ((id.hashCode() % 2) + 2) % 2 == 0
    }

    /** EVERY usable trace (no per-word cap), timestamp-filtered by [TraceCorpusQuality]. */
    fun loadAll(file: File = traceFile): List<Row> {
        val rows = ArrayList<Row>()
        java.util.zip.GZIPInputStream(file.inputStream()).bufferedReader().useLines { lines ->
            for (line in lines) {
                val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                val word = o.optString("word").lowercase()
                if (word.isEmpty()) continue
                val pts = o.optJSONArray("pts") ?: continue
                val n = pts.length()
                if (n < 3) continue
                val x = DoubleArray(n); val y = DoubleArray(n); val t = DoubleArray(n)
                var malformed = false
                for (i in 0 until n) {
                    val p = pts.optJSONArray(i)
                    if (p == null) { malformed = true; break }
                    x[i] = p.optDouble(0); y[i] = p.optDouble(1); t[i] = p.optDouble(2)
                }
                if (malformed || !TraceCorpusQuality.hasUsableTimestamps(t)) continue
                rows.add(Row(word, x, y, t))
            }
        }
        return rows
    }

    /**
     * A straight g→(i..o)→t trace whose middle point sits [towardO] of the way from `i` to
     * `o` — the AMBIGUOUS middle key behind the audit's `git` → `got` report (the clean
     * [CtcTraceShapes] shapes already decode `git` at rank 1 without any prior).
     */
    fun ambiguousGit(
        layout: tribixbite.cleverkeys.swipe.ctc.CtcLayout,
        towardO: Double,
    ): Triple<DoubleArray, DoubleArray, DoubleArray> {
        fun center(ch: Char): Pair<Double, Double> {
            val k = layout.alphabet.indexOf(ch)
            return layout.keyCentersX[k].toDouble() to layout.keyCentersY[k].toDouble()
        }
        val (ix, iy) = center('i'); val (ox, oy) = center('o')
        val pts = listOf(center('g'), (ix + (ox - ix) * towardO) to (iy + (oy - iy) * towardO), center('t'))
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val ts = ArrayList<Double>()
        var time = 0.0
        for (i in 0 until pts.size - 1) for (s in 0 until 12) {
            val f = s / 12.0
            xs.add(pts[i].first + (pts[i + 1].first - pts[i].first) * f)
            ys.add(pts[i].second + (pts[i + 1].second - pts[i].second) * f)
            ts.add(time); time += 16.0
        }
        xs.add(pts.last().first); ys.add(pts.last().second); ts.add(time)
        return Triple(xs.toDoubleArray(), ys.toDoubleArray(), ts.toDoubleArray())
    }

    /** Middle-point positions between `i` (0) and `o` (1) the git pins measure. */
    val AMBIGUOUS_FRACTIONS = doubleArrayOf(0.3, 0.4, 0.5, 0.6)
}
