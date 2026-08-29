package tribixbite.cleverkeys

/**
 * Which PAIRED contraction variants may be injected alongside a typed partial on the TAP path,
 * and in what order.
 *
 * PAIRED mode means the typed key IS a real word (`its`, `well`, `id`), so the literal keeps its
 * slot and the contraction is offered ALONGSIDE it — never in place of it. See
 * `.claude/skills/contraction-system.md` §2 for why "which bucket an entry lives in" is the data
 * model. This object owns only the *injection* question; the bucket itself is resolved at data
 * generation time.
 *
 * ## Why a length floor exists at all
 *
 * `contraction_pairings.json` is dominated by POSSESSIVES (1,178 of 1,744 bases), and the
 * apostrophe-free key of a single-letter possessive is a one- or two-letter string: `t` → `t's`,
 * `a` → `a's`, `as` → `a's`, `cd` → `cd's`. Injecting those at `top score + 500` put `t's` ahead
 * of `the` for a one-letter partial, which is why [MIN_BASE_LENGTH] was introduced.
 *
 * The floor is also load-bearing for the genuine two-letter pronoun bases — `it` → `it'll/it's/
 * it'd`, `we` → `we'd/we'll/we're/we've`, `he` → `he'd/he'll/he's`, `do` → `don't`. Those are
 * extremely high-frequency literals; injecting three or four variants ahead of them would bury
 * the word the user is overwhelmingly likely to be typing. They stay blocked deliberately —
 * changing that is a ranking decision that needs its own measurement, not a side effect of this
 * one.
 *
 * ## The exception: first-person contractions (ARC-013 / UT-7)
 *
 * The I-contractions are a closed set — `i'm`, `i'll`, `i've`, `i'd` — and the codebase already
 * treats them as a family ([SuggestionHandler]'s `capitalizeIWord`, issue #72). Three of the four
 * have three-letter bases (`im`, `ill`, `ive`) and already inject. The fourth, `id` → `i'd`, has a
 * TWO-letter base and was the only member the floor excluded, so `i'd` was absent from the bar
 * entirely — measured on-device 2026-08-28, `docs/eval/2026-08-28-arc019-ctc-local-head2head.md`
 * §4: typed `id` produced `[id, idea, ideas, ideal, idiot]`.
 *
 * The data was never missing (`contraction_pairings.json` has carried `id → i'd` all along) and
 * the fix is therefore NOT a data change — no regeneration, no new collision sidecar, no new
 * REPLACE key. `id` stays a PAIRED base, so the real word "id" keeps its slot and remains typeable
 * and autocorrect-safe (the user-word guard in `replaceModeContractionFor` is a REPLACE-mode
 * concern and is not reached by this path).
 *
 * [isFirstPersonContraction] is deliberately shape-based rather than an allowlist so a future
 * pairing entry in the same family is covered automatically, and it excludes the `'s` form
 * specifically: `is` pairs to `i's` (the plural of the letter I), which must NOT surface when the
 * user types the word "is". Verified against the full shipped table: exactly ONE base changes
 * behaviour under this policy, `id`.
 */
object ContractionInjectionPolicy {

    /** Shortest PAIRED base whose variants inject unconditionally. */
    const val MIN_BASE_LENGTH = 3

    /** Shortest PAIRED base that may inject a first-person contraction ([isFirstPersonContraction]). */
    const val MIN_FIRST_PERSON_BASE_LENGTH = 2

    /**
     * True when [variant] is a first-person contraction (`i'm`, `i'll`, `i've`, `i'd`) rather than
     * the possessive/plural `i's`.
     *
     * Variants are lowercased by both `ContractionManager` load paths; the fold here is defence in
     * depth for a caller that passes a display surface.
     */
    fun isFirstPersonContraction(variant: String): Boolean {
        val v = variant.lowercase()
        return v.length > 2 && v.startsWith("i'") && !v.endsWith("'s")
    }

    /**
     * The PAIRED variants that may be injected for [partial], in source order.
     *
     * Duplicates are removed case-insensitively, keeping the first occurrence: the two English
     * load paths (`contractions.bin`'s derived pairs and `contraction_pairings.json`) overlap on
     * 599 bases, and before the loader-side fix that overlap reached the bar as a visibly doubled
     * suggestion (`[I'll, I'll, ill, …]` for typed `ill`). The loader is the root-cause fix; this
     * is the second line so no future third source can re-open it.
     *
     * @param partial the typed partial, as the user typed it (case-insensitive here).
     * @param variants whatever `ContractionManager.getPairedContractions(partial)` returned.
     * @return the variants to inject — empty when none apply.
     */
    fun injectableVariants(partial: String, variants: List<String>?): List<String> {
        if (variants.isNullOrEmpty()) return emptyList()

        val allowed: List<String> = when {
            partial.length >= MIN_BASE_LENGTH -> variants
            partial.length >= MIN_FIRST_PERSON_BASE_LENGTH ->
                variants.filter { isFirstPersonContraction(it) }
            else -> return emptyList()
        }
        if (allowed.isEmpty()) return emptyList()

        val seen = HashSet<String>(allowed.size * 2)
        return allowed.filter { seen.add(it.lowercase()) }
    }
}
