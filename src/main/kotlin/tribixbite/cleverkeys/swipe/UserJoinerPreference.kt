package tribixbite.cleverkeys.swipe

import tribixbite.cleverkeys.swipe.ctc.CtcAzProjection
import java.util.Locale

/**
 * The swipe display preference a **joiner user word** expresses — a personal-dictionary word
 * spelled with an apostrophe or a hyphen (`she'd`, `l'une`, `co-op`). Pure JVM.
 *
 * ## Why a joiner user word needs its own rule (follow-up to the 2026-09-26 learning audit)
 *
 * No swipe engine can SPELL a joiner: the CTC beam walks an a–z (or script) trie, and the
 * geometric templates skip non-letter keys. So a joiner user word is only ever decoded as its
 * joiner-free SURFACE (`shed`, `lune`, `coop`), and what the user sees for that surface is a
 * display decision. Before this rule the decision ignored the user word entirely:
 *
 *  - EN CTC filed `she'd` under `shed` in the trie (lifting `shed`, the word the user corrected
 *    away from) while [ContractionOverlay] placed `she'd` vs `shed` from PAIRING and LEXICON
 *    frequencies a user word never touches — `shed` stayed the auto-insert;
 *  - a hyphen word had no overlay entry and the EN branch no display map, so `co-op` only
 *    ever surfaced as `coop`;
 *  - the CKDT languages let the user word win the surface's accent-display slot outright,
 *    which silently replaced the real homograph (`lune` became unswipeable for a user with
 *    `l'une` — the contraction-system skill §2 casualty, per user).
 *
 * The personal-dictionary entry is therefore read as what it is — an explicit user claim that
 * this surface should be SHOWN this way — rather than as a frequency lift. [ContractionOverlay]
 * puts the preferred form ahead of the decoded surface, at whatever rank the surface was
 * decoded, and keeps the surface itself right behind it when the surface is a real word of the
 * lexicon (so `lune` survives one slot away, never destroyed). The frequency the entry also
 * gives the surface in the CTC trie stays: it makes the trace the user keeps swiping decode to
 * that surface more readily, which is the point of preferring it.
 *
 * ## When no preference is produced
 *
 *  - **The surface itself is a letters-only user word** (`shed` AND `she'd` both in the
 *    dictionary): the user has claimed both readings. The base keeps its slot — the reverse
 *    preference (`ContractionPromotionUserWordTest`: a user `id` beats a promoted `i'd`) is the
 *    older, shipped contract, and a tie between two explicit entries must not flip the
 *    auto-insert of the traced literal. The swipe offer does not offer a joiner word in that
 *    state (`SwipeCorrectionPolicy.joinerSurface`), so it never makes a promise this rule
 *    would not keep.
 *  - **Two joiner user words share a surface** (`she'd`, `s-hed`): the higher-frequency one,
 *    earlier-listed on a tie (user-word merge order), takes it; the other stays unshown as
 *    before — there is one slot ahead of the surface.
 *  - **Not a joiner word**, or no surface (nothing but joiners, or characters the engine's
 *    projection rejects).
 *
 * The inputs are the engine's user-word list (custom words + platform user-dictionary rows,
 * `UserDictionarySnapshot.mergeWithCustom`), so the preference exists exactly while the entry
 * does: removing the word (Dictionary Manager, or tap-again undo of "Added …") changes the
 * `custom_words_<lang>` content the lexicon memo keys on, and the next build has no preference.
 */
object UserJoinerPreference {

    /** The joiners a user word may carry: ASCII apostrophe, typographic apostrophe, hyphen. */
    private val JOINERS = charArrayOf('\'', '’', '-')

    /**
     * @property form the user word exactly as stored — what the slate shows.
     * @property replacesSurface true when the decoded surface is NOT a word of the lexicon
     *   (it exists in the decoder only because of this user word, e.g. `xray` for `x-ray`), so
     *   showing it beside [form] would offer a non-word; false keeps it one slot behind.
     */
    data class Preference(val form: String, val replacesSurface: Boolean)

    /** True for a word holding at least one joiner AND at least one letter. */
    fun isJoinerWord(word: String): Boolean =
        word.any { it in JOINERS } && word.any { it.isLetter() }

    /**
     * [word] minus its joiners, lowercased ([Locale.ROOT]; accents kept), or null when anything
     * but letters and joiners remains or no letter does. This is the surface key for an engine
     * whose decoded words are canonical dictionary forms (the geometric engine), and the
     * SwipeCorrectionPolicy's notion of a joiner word's letters.
     */
    fun joinerFree(word: String): String? {
        val sb = StringBuilder(word.length)
        for (ch in word.lowercase(Locale.ROOT)) {
            if (ch in JOINERS) continue
            if (!ch.isLetter()) return null
            sb.append(ch)
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    /**
     * The surface `CtcLexiconTrie.loadStrippingNonAlphabet` files [word] under (lowercase,
     * every character outside [alphabet] dropped) — the CTC en surface key. Null when empty.
     */
    fun stripToAlphabet(word: String, alphabet: Set<Char>): String? {
        val sb = StringBuilder(word.length)
        for (ch in word.lowercase()) if (ch in alphabet) sb.append(ch)
        return if (sb.isEmpty()) null else sb.toString()
    }

    /**
     * Surface key → preference, over the engine's [userWords].
     *
     * @param userWords `(word, stored frequency)` in merge order.
     * @param surfaceOf the key [ContractionOverlay] will see for a decoded word spelling
     *   [userWords]' entry — the engine's own projection (CTC en: a–z strip; CTC CKDT: the
     *   projection resolved through the accent-display map; geometric: [joinerFree]). Null
     *   drops the word. The result is lowercased here.
     * @param isLexiconWord whether a (lowercase) surface key is a word of the lexicon other
     *   than through a joiner user word — decides [Preference.replacesSurface].
     */
    fun build(
        userWords: List<Pair<String, Int>>,
        surfaceOf: (String) -> String?,
        isLexiconWord: (String) -> Boolean,
    ): Map<String, Preference> {
        if (userWords.none { isJoinerWord(it.first) }) return emptyMap()

        // Surfaces the user claimed as letters-only words — those keep their slot (class KDoc).
        val claimedSurfaces = HashSet<String>()
        for ((word, _) in userWords) {
            if (word.isBlank() || isJoinerWord(word)) continue
            surfaceOf(word)?.let { claimedSurfaces.add(it.lowercase(Locale.ROOT)) }
        }

        // Stable sort: frequency descending, merge order on a tie.
        val joiners = userWords.withIndex()
            .filter { isJoinerWord(it.value.first) }
            .sortedWith(compareByDescending<IndexedValue<Pair<String, Int>>> { it.value.second }.thenBy { it.index })

        val out = HashMap<String, Preference>()
        for ((_, entry) in joiners) {
            val key = surfaceOf(entry.first)?.lowercase(Locale.ROOT) ?: continue
            if (key in claimedSurfaces || key in out) continue
            out[key] = Preference(entry.first, replacesSurface = !isLexiconWord(key))
        }
        return out
    }

    /**
     * The CKDT-source projection (`CtcEngineAdapter`, fr/de/it/…/ru and imported packs) with
     * joiner USER words kept out of the accent-display map.
     *
     * [projectLexicon] gives a surface's display slot to its highest-frequency canonical form,
     * and a user word carries the calibrated user frequency — so a user `l'une` used to take
     * `lune`'s slot and REPLACE every decoded `lune` (the §2 casualty, per user). Here the
     * joiner user words are projected separately: each still raises its surface's trie
     * frequency (max, the same retention rule), but owns no display entry; the overlay shows
     * it through [build] instead, with the real word kept one slot behind.
     *
     * A joiner user word that is also a base word (`aujourd'hui` added by hand) was deduped to
     * the user copy by the merge, so its display is likewise restored by the overlay rather
     * than the map — same result on screen.
     *
     * @param merged the canonical merged lexicon (`CtcLexiconMerge.merge`), user words included.
     * @param userWords the same user-word list the merge received.
     * @param projectLexicon the engine's projection (`CtcAzProjection.projectLexicon` or the
     *   script twin).
     * @param project the single-word projection [projectLexicon] applies.
     */
    fun projectWithoutJoinerDisplay(
        merged: LinkedHashMap<String, Double>,
        userWords: List<Pair<String, Int>>,
        projectLexicon: (Map<String, Double>) -> CtcAzProjection.Projected,
        project: (String) -> String?,
    ): CtcAzProjection.Projected {
        val joinerLower = HashSet<String>()
        for ((word, _) in userWords) if (isJoinerWord(word)) joinerLower.add(word.lowercase(Locale.ROOT))
        if (joinerLower.isEmpty()) return projectLexicon(merged)

        val rest = LinkedHashMap<String, Double>(merged.size * 2)
        val joinerEntries = ArrayList<Pair<String, Double>>()
        for ((word, freq) in merged) {
            if (word.lowercase(Locale.ROOT) in joinerLower) joinerEntries.add(word to freq) else rest[word] = freq
        }
        val projected = projectLexicon(rest)
        var untypeable = projected.untypeable
        var collisions = projected.collisions
        for ((word, freq) in joinerEntries) {
            val surface = project(word)
            if (surface == null) {
                untypeable++
                continue
            }
            val existing = projected.freqs[surface]
            if (existing != null) collisions++
            if (existing == null || freq > existing) projected.freqs[surface] = freq
        }
        return CtcAzProjection.Projected(projected.freqs, projected.display, merged.size, untypeable, collisions)
    }
}
