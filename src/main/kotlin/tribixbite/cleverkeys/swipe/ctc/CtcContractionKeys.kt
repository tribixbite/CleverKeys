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
     * Frequency given to an injected key: the bottom of the AOSP-like scale the tuned λ
     * expects. See the class KDoc for why this is a floor and not a boost.
     */
    const val INJECTED_FREQUENCY: Double = CtcLexiconMerge.MIN_FREQ

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
    fun inject(trie: CtcLexiconTrie, keys: Iterable<String>): Int {
        var inserted = 0
        for (key in keys) {
            if (!isInjectable(key, trie.alphabet)) continue
            val lowered = key.lowercase(Locale.ROOT)
            if (trie.contains(lowered)) continue
            trie.insert(lowered, INJECTED_FREQUENCY)
            inserted++
        }
        return inserted
    }
}
