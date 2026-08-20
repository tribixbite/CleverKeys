package tribixbite.cleverkeys

/**
 * Demotes REPLACE-mode contraction keys that are real words of ANOTHER active language.
 *
 * ## The defect this closes
 *
 * A `contractions_<lang>.json` entry is REPLACE mode: the key is an alias with no reading of its
 * own, so the display form substitutes for it and keeps its slot. That judgement is made per
 * language at data-generation time, and it is only true PER LANGUAGE.
 *
 * [ContractionManager.loadTypingMappings] merges the primary language's mappings, the secondary
 * language's, and the English base into one map with no provenance. So a key with no reading in
 * language L gets applied to a word that IS a reading in language M, and the real word is
 * destroyed in its own slot. Measured on the shipped assets before this landed:
 *
 * | user's languages | types | got | source of the mapping |
 * |---|---|---|---|
 * | fr + en | French `dont` (relative pronoun) | `don't` | en REPLACE key |
 * | de + en | German `im` (in dem) | `I'm` | en REPLACE key |
 * | de + en | English `hats` | `hat's` | de's curated clitic table |
 *
 * `im` was destroyed in EVERY non-English bundled language, and `dont` is among the commonest
 * words in French — so this was not an edge case.
 *
 * This is the same defect the 2026-07-23 multilingual audit fixed WITHIN English, where
 * `well`→`we'll` was destroying the word "well" (see [ContractionManager.loadEnglishBase]'s
 * reclassification). That fix moved paired bases out of the non-paired map; this one moves
 * cross-language collisions into the same PAIRED bucket, so the fix reuses semantics that are
 * already shipped and tested rather than inventing new ones: the base keeps its slot and its own
 * dictionary score, and the elision is offered alongside it. Nothing becomes unreachable.
 *
 * ## Why the collision set is DATA, not a runtime lexicon check
 *
 * The same reasoning that put the REPLACE/PAIRED split in the shipped files rather than in a
 * runtime rank test: the fact is fully knowable offline, changes only when the repo's own assets
 * change, and belongs somewhere reviewable and diff-able. Concretely, a runtime check is not
 * even available — `DictionaryManager` holds one predictor for the CURRENT language, so the
 * other active language's lexicon is simply not resident while typing.
 *
 * The sidecars are built by `scripts/build_contraction_collisions.py` and pinned against a
 * recomputation from the shipped lexicons by `ContractionCollisionDataTest`, which is what stops
 * them going stale after a lexicon or contraction regeneration.
 *
 * ## Why the per-key language LIST matters
 *
 * The sidecar stores which languages collide, not merely that some language does. Demoting on a
 * boolean would demote fr's `cest` for an fr+es user because `cest` happens to be an obscure
 * English lexicon entry — a language that user has not enabled. Intersecting against the ACTIVE
 * set means a monolingual user is affected by nothing at all: their intersection is always empty
 * and the maps come out byte-identical.
 *
 * ## Not applied to the swipe path
 *
 * [ContractionManager.loadSwipeDisplayMappings] loads exactly ONE language into a separate
 * manager instance per engine adapter, and the decode lexicon is per-language, so no
 * cross-language merge exists there to guard. Single-language REPLACE is correct on that path,
 * and `ContractionOverlay`'s rank guard remains its defense in depth for imported packs.
 */
object ContractionCollisionDemotion {

    /** Asset name holding [langCode]'s `key -> [colliding language, …]` table. */
    fun assetName(langCode: String): String = "dictionaries/contraction_collisions_$langCode.json"

    /**
     * Moves every colliding REPLACE key into the PAIRED map, in place.
     *
     * @param nonPaired the REPLACE map, mutated: a demoted key is removed.
     * @param paired the APPEND map, mutated: the demoted key's display form is appended as a
     *   variant of the base, or added as a new entry if the base had no variants yet.
     * @param collisionsByKey merged `key -> colliding languages` over every loaded language.
     * @param activeLanguages base subtags the user actually has enabled.
     * @return how many keys were demoted, for logging.
     */
    fun demote(
        nonPaired: MutableMap<String, String>,
        paired: MutableMap<String, MutableList<String>>,
        collisionsByKey: Map<String, Set<String>>,
        activeLanguages: Set<String>,
    ): Int {
        if (collisionsByKey.isEmpty() || activeLanguages.isEmpty()) return 0
        var demoted = 0
        for ((key, collidesWith) in collisionsByKey) {
            // The key's OWN language is in activeLanguages too, but it is never listed in its own
            // collision entry (the generator excludes self), so this cannot self-trigger.
            if (collidesWith.none { it in activeLanguages }) continue
            val display = nonPaired.remove(key) ?: continue
            // Idempotent: re-running must not append a duplicate variant. `loadTypingMappings`
            // clears both maps before loading, so this should not arise in production — but the
            // guard costs nothing and makes the function safe to call twice, which the unit test
            // relies on to pin exactly that.
            val variants = paired.getOrPut(key) { mutableListOf() }
            if (variants.none { it.equals(display, ignoreCase = true) }) {
                variants.add(display)
            }
            demoted++
        }
        return demoted
    }
}
