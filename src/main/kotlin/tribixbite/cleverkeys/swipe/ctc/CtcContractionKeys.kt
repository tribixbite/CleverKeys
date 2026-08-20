package tribixbite.cleverkeys.swipe.ctc

import java.util.Locale

/**
 * Injection of contraction ALIAS KEYS into a [CtcLexiconTrie].
 *
 * ## The problem this solves
 *
 * `ContractionOverlay` rewrites an a–z surface the beam emitted into its apostrophe form
 * (`cest` → `c'est`). It can only ever rewrite what the beam ALREADY produced, and the beam
 * can only produce paths that exist in the lexicon trie. So a mapping whose key is not a
 * dictionary word is inert: French `dabaissement` → `d'abaissement` never fires, because
 * `dabaissement` is not a French word and never will be — it is the *productive elision* of
 * `abaissement`, which is exactly the construction French speakers type constantly
 * (`l'homme`, `d'abaissement`, `qu'il`).
 *
 * Injecting the alias keys as their own trie paths makes the whole mapping table reachable:
 * the beam can now spell `dabaissement`, and the overlay turns it into `d'abaissement`.
 *
 * ## Frequency: why the floor, and why not the deleted vocabulary's boost
 *
 * The deleted `OptimizedVocabulary` injected contraction keys at `WordInfo(0.88f, tier 2)` — a
 * COMMON-WORD boost. Doing that here would be actively harmful: the CTC beam adds
 * `lambda * ln(freq + 1e-10)` to every candidate, with the lexicon on the AOSP-like
 * 1..255 scale and λ = 2.0 (CKDT) / 4.0 (en JSON). A boosted pseudo-word would then
 * outrank most of the real vocabulary.
 *
 * Injected keys therefore get [INJECTED_FREQUENCY] = [CtcLexiconMerge.MIN_FREQ], the bottom
 * of the scale, whose log-frequency term is `ln(1 + 1e-10) ≈ 0`. An injected pseudo-word
 * consequently receives NO frequency bonus at all, while every real lexicon word receives a
 * positive one — so it can only win a slate slot when the emission evidence alone favours
 * it, which is precisely the "the user really did swipe that path" case.
 *
 * Keys that are already in the trie are SKIPPED rather than re-inserted, so a real word that
 * happens to also be an alias key (fr `la` → `l'a`) keeps its real frequency and its
 * `ContractionOverlay` real-word ordinal guard continues to work unchanged.
 *
 * ## Scope
 *
 * Only keys spelled entirely from the trie's alphabet can be injected — see [isInjectable].
 * A key carrying a hyphen or an accent (en `high-falutin`, fr `cest-à-dire`) has no a–z path
 * and stays unreachable; those are the entries the generator still trims as genuinely dead.
 *
 * Pure JVM (no Android imports) so the policy is unit-testable in `runPureTests`.
 */
object CtcContractionKeys {

    /**
     * Absolute lower bound for an injected key — the bottom of the AOSP-like scale the tuned λ
     * expects. Used only when a lexicon has no headroom above it (see [derivedFloor]).
     */
    const val INJECTED_FREQUENCY: Double = CtcLexiconMerge.MIN_FREQ

    /**
     * The frequency to inject at for a given lexicon: **one below its rarest real word.**
     *
     * ## Why not simply [INJECTED_FREQUENCY]
     *
     * Injecting at 1.0 does preserve "reachable, never preferred" — but by an enormous margin,
     * and measurement showed the margin is so wide the keys become unreachable in practice
     * rather than merely un-preferred.
     *
     * The final beam score is `ctc/len^0.9 + β·len + λ·ln(freq)` ([CtcBeamDecoder]). The
     * emission evidence is DIVIDED by `len^0.9` while the frequency bonus is not, so a
     * competitor's frequency advantage is worth `len^0.9` times more raw evidence than it looks.
     * For an 11-letter French word that multiplier is ~8.6. With injection at 1.0 the gap to the
     * rarest real French word (freq 69, bonus 8.47 nats at λ=2.0) demands roughly **75 nats** of
     * raw emission evidence to overcome — against the **7–10 nats** the shipped model actually
     * produces at a trace start, measured from the golden fixture. Not marginal: an order of
     * magnitude.
     *
     * The consequence was measured on the real fr lexicon: of 17,947 REPLACE keys, 82 are
     * lexicon-native and 8,762 — **49%** — have a drop-first-letter competitor that wins
     * unconditionally. That includes the productive core (`d'abord`, `l'ont`, `n'ont`,
     * `d'entre`, `m'avait`), so half the 2026-08-17 restore was inert through the beam.
     *
     * ## Why DERIVED and never a raised constant
     *
     * Because the rarest real word differs per lexicon, and one constant cannot serve them all:
     * measured floors are fr 69, es 62, it 73, pt 70, sv 70, **de 12**, en-JSON 134. Any constant
     * above 11 breaks the invariant for German — pseudo-words would outrank real German words,
     * which is precisely the `lune` regression that made this a floor in the first place. Any
     * constant at or below 11 leaves French's gap at 4+ nats and changes nothing.
     *
     * Deriving preserves the invariant BY CONSTRUCTION, in every lexicon, including ones not yet
     * added: every real word still strictly outranks every pseudo-word on frequency.
     *
     * @param realFrequencies the merged lexicon's frequencies BEFORE injection.
     */
    fun derivedFloor(realFrequencies: Iterable<Double>): Double {
        val minReal = realFrequencies.minOrNull() ?: return INJECTED_FREQUENCY
        // Strictly below the rarest real word, but never below the scale's own bottom. If a
        // lexicon's rarest word already sits at the bottom there is no headroom and injection
        // falls back to equality — `CtcContractionKeysTest` asserts the invariant so that case
        // is visible rather than silent.
        return maxOf(INJECTED_FREQUENCY, minReal - 1.0)
    }

    /**
     * True when [key] can be spelled from [alphabet] and is therefore injectable as its
     * own trie path.
     *
     * Case-folds with [Locale.ROOT] to match the trie's own lowercase-at-insert policy.
     */
    fun isInjectable(key: String, alphabet: CharArray): Boolean {
        if (key.isEmpty()) return false
        val lowered = key.lowercase(Locale.ROOT)
        for (ch in lowered) {
            if (!alphabet.contains(ch)) return false
        }
        return true
    }

    /**
     * Insert every injectable key of [keys] that the trie does not already contain, at
     * [INJECTED_FREQUENCY].
     *
     * @param trie the merged lexicon trie for the active language; mutated in place.
     * @param keys contraction alias keys (both the REPLACE and the APPEND side —
     *   `ContractionManager.getAliasKeys()`).
     * @return the number of keys actually inserted (skipped ones are not counted).
     */
    fun inject(
        trie: CtcLexiconTrie,
        keys: Iterable<String>,
        frequency: Double = INJECTED_FREQUENCY,
    ): Int {
        var inserted = 0
        for (key in keys) {
            if (!isInjectable(key, trie.alphabet)) continue
            val lowered = key.lowercase(Locale.ROOT)
            if (trie.contains(lowered)) continue
            trie.insert(lowered, frequency)
            inserted++
        }
        return inserted
    }
}
