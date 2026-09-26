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
            it.effectiveUses >= CtcLearnedPrior.MIN_EFFECTIVE_USES
        }
        return Profile(evidence, usages.size, eligible, reference)
    }
}
