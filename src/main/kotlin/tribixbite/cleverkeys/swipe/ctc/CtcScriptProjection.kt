package tribixbite.cleverkeys.swipe.ctc

import java.text.Normalizer
import java.util.Locale

/**
 * Projects a canonical lexicon onto the emission alphabet of a NON-LATIN script, and owns the
 * one collision-resolving lexicon loop both projections share.
 *
 * The Latin twin is [CtcAzProjection]; it delegates its lexicon loop here so there is exactly one
 * implementation of "highest frequency wins, canonical form kept for display".
 *
 * ## Why the rules are per script and not one clever normalizer
 *
 * `CleverKeys-ML/ctc/PHASE_O.md` §3.4 (from `script_registry.py`), mirrored exactly — the
 * projection is applied to the lexicon **and** to anything compared against a decode:
 *
 *  * **all scripts** — lowercase; strip `-`, `'`, `’`, `ʼ`, `‘`, `` ` ``.
 *  * **el, he** — NFD → drop combining marks (`Mn`) → NFC. Safe *here* because no letter's
 *    identity depends on a mark: Greek accents/diaeresis and Hebrew niqqud are not keys, and the
 *    el model's 25 slots contain no accented vowel, so an unprojected `λόγος` carries `ό`, a
 *    character with **no emission slot at all**.
 *  * **ru, bg, mk — NO NFD.** It decomposes `й` into `и` + combining breve and destroys the
 *    alphabet. Character folds instead: ru `ё→е`, `ъ→ь`; bg `ѝ→и`; mk `ѐ→е`, `ѝ→и`.
 *  * **el only** — *after* mark stripping, word-final `σ` → `ς` ([CtcGreekOrthography]). Greek
 *    writes sigma two ways and they are distinct keys in different rows, so this is the
 *    difference between "scored against the wrong key" and "scored correctly" for 25.7 % of the
 *    pack. **Both halves or neither**: sigma alone upgrades "one Greek word in four is scored
 *    against the wrong key" to "most of the pack cannot be represented at all".
 *  * **uk** — no folds; a word containing `ї` or `ґ` is **rejected as untypeable** (4.03 % of the
 *    vocabulary). Those live in corner slots, and serving them is a different input mode (flick),
 *    not a swipe.
 *
 * ## The ru fold is not cosmetic
 *
 * `cyrl_jcuken_ru.xml` puts `ё` on `key1` of `е` and `ъ` on `key1` of `ь`. Corner values never
 * become emission slots (`KeyboardGeometry.computeKeyRects` emits `keys[0]` only), so the model
 * has 31 columns and no `ё`/`ъ` among them. Without the fold, every word containing either
 * character is dropped as untypeable instead of being reachable at its base letter.
 */
object CtcScriptProjection {

    /** Combining diacritical marks — the NFD residue to discard (el, he only). */
    private val COMBINING = Regex("[\\p{Mn}]")

    /**
     * Non-alphabet joiners removed rather than rejected (the STRIP convention), for EVERY
     * script. Wider than [CtcAzProjection]'s set by `ʼ`, `‘` and `` ` ``, per PHASE_O §3.4;
     * the Latin path keeps its own narrower set because that is the vocabulary the shipped
     * en/fr/de/es numbers were measured on and it must not move.
     */
    private val JOINERS = charArrayOf('-', '\'', '’', 'ʼ', '‘', '`')

    /** Per-language character folds applied INSTEAD of NFD for the Cyrillic scripts. */
    private val FOLDS: Map<String, Map<Char, Char>> = mapOf(
        // ё (U+0451) → е, ъ (U+044A) → ь. Both are corner keys on the shipped ЙЦУКЕН.
        "ru" to mapOf('ё' to 'е', 'ъ' to 'ь'),
        // ѝ (U+045D) → и. bg's alphabet KEEPS ъ — it is a centre key on cyrl_ueishsht.
        "bg" to mapOf('ѝ' to 'и'),
        // ѐ (U+0450) → е, ѝ (U+045D) → и.
        "mk" to mapOf('ѐ' to 'е', 'ѝ' to 'и'),
    )

    /** Languages whose projection strips combining marks (NFD → drop Mn → NFC). */
    private val MARK_STRIPPING = setOf("el", "he")

    /**
     * Characters that make a uk word untypeable on the shipped layout. `ї` (U+0457) and `ґ`
     * (U+0491) are corner values, not centre keys, so no emission slot exists for them.
     */
    private val UK_REJECTED = charArrayOf('ї', 'ґ')

    /**
     * The projector for [language] over [alphabet]: canonical word → emission surface, or null
     * when the word has no representation on the script's keys.
     *
     * The alphabet is passed in rather than looked up so the caller cannot accidentally project
     * onto a different alphabet from the one the trie and the layout were built over — that
     * mismatch is silent (the trie simply rejects the word) and would look like a thin lexicon.
     */
    fun projectorFor(language: String, alphabet: CharArray): (String) -> String? {
        val code = CtcLanguageSupport.normalize(language)
        val alphabetSet = alphabet.toHashSet()
        val folds = FOLDS[code].orEmpty()
        val stripMarks = code in MARK_STRIPPING
        val greek = code == "el"
        val rejectChars = if (code == "uk") UK_REJECTED else CharArray(0)
        return { word -> project(word, alphabetSet, folds, stripMarks, greek, rejectChars) }
    }

    /**
     * The emission surface of [word], or null when it cannot be represented.
     *
     * Order matters and mirrors `script_registry.py`: lowercase → (marks) → folds → joiners →
     * alphabet check → (final sigma). The sigma repair runs LAST, on the finished surface,
     * because it is defined on the word-FINAL position: a trailing joiner or an accented final
     * vowel would otherwise hide the σ that has to be repaired (`λόγος` → `λογος` → `λογος`
     * with the last character rewritten to `ς`).
     */
    private fun project(
        word: String,
        alphabet: Set<Char>,
        folds: Map<Char, Char>,
        stripMarks: Boolean,
        greek: Boolean,
        rejectChars: CharArray,
    ): String? {
        if (word.isEmpty()) return null
        var text = word.lowercase(Locale.ROOT)
        if (stripMarks) {
            text = Normalizer.normalize(
                COMBINING.replace(Normalizer.normalize(text, Normalizer.Form.NFD), ""),
                Normalizer.Form.NFC,
            )
        }
        val sb = StringBuilder(text.length)
        for (raw in text) {
            if (raw in rejectChars) return null
            val ch = folds[raw] ?: raw
            if (ch in JOINERS) continue
            if (ch !in alphabet) return null
            sb.append(ch)
        }
        if (sb.isEmpty()) return null
        val surface = sb.toString()
        return if (greek) CtcGreekOrthography.repairFinalSigma(surface) else surface
    }

    /**
     * Project [canonical] (word → frequency) onto an emission surface with [project], keeping
     * the canonical form for display.
     *
     * **This is the single implementation of the collision rule**, shared with
     * [CtcAzProjection.projectLexicon]: keep the entry with the higher frequency; ties keep the
     * FIRST seen, so [canonical]'s insertion order (custom words first, then rank-ascending
     * base) is the deterministic tie-break. The winner owns both the frequency and the display
     * form — an unaccented winner clears any earlier accented display entry, so the map can never
     * disagree with the frequency it was chosen for.
     */
    fun projectLexicon(
        canonical: Map<String, Double>,
        project: (String) -> String?,
    ): CtcAzProjection.Projected {
        val freqs = LinkedHashMap<String, Double>(canonical.size * 2)
        val display = HashMap<String, String>()
        var untypeable = 0
        var collisions = 0
        for ((word, freq) in canonical) {
            val surface = project(word)
            if (surface == null) {
                untypeable++
                continue
            }
            val existing = freqs[surface]
            if (existing != null) {
                collisions++
                if (freq <= existing) continue
            }
            freqs[surface] = freq
            if (word == surface) display.remove(surface) else display[surface] = word
        }
        return CtcAzProjection.Projected(freqs, display, canonical.size, untypeable, collisions)
    }
}
