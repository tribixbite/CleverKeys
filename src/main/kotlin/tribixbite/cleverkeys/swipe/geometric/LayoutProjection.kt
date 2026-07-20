package tribixbite.cleverkeys.swipe.geometric

import java.text.Normalizer
import java.util.Locale

/**
 * Deterministic, preference-ordered projection of a dictionary word onto a
 * sequence of [LayoutGeometry] key ids — the geometric engine's per-layout
 * vocabulary filter and template anchor source.
 *
 * The tiers (Geometry & Script Abstraction) are applied PER FOLDED CODEPOINT, in
 * strict order, first-match-wins:
 *  1. **Single-codepoint center match** — the codepoint is a letter-node center
 *     (`chars` tier-1 entry). Catches `й` (never NFD-corrupted to `и` because this
 *     fires before tier 3), `ς`, Hebrew finals, Arabic center keys, `ё` where it is
 *     a real center.
 *  2. **Multi-codepoint-label membership** — the codepoint is a *component* of an
 *     all-letter multi-codepoint center label ("ch" digraph keys, T9 grouped keys);
 *     these are the tier-2 fallback entries the [LayoutGeometry.Builder] seeded into
 *     `chars`. On Arabic PC this contributes nothing for `ل`/`ا` (both have dedicated
 *     keys — tier 1 wins — so the `لا` ligature key is effectively ignored), but a
 *     fully-digraph-grouped custom layout projects instead of dying.
 *  3. **NFD base match** — strip Mn (non-spacing mark) codepoints from this
 *     codepoint's Unicode decomposition and retry tiers 1–2 on the base. Two
 *     per-codepoint decisions gate this tier: mark DETECTION is
 *     `Character.getType == NON_SPACING_MARK`, and the SCRIPT gate (which scripts
 *     the strip applies to at all) is the per-codepoint Unicode block
 *     ([nfdStripAppliesTo]) — NEVER the untrustworthy layout `script` attribute
 *     (grek_qwerty.xml WAS mis-declared `script="latin"` until 2026-07-20, fixed in
 *     d988b23e — proof the attribute can't be trusted) and NEVER the dictionary
 *     language code (wrong for loanwords). Full Mn coverage (Latin accents, Greek
 *     tonos, Arabic
 *     harakat U+064B–0652 + U+0670 the legacy `AccentNormalizer` regex misses,
 *     Hebrew niqqud). é→`e` on QWERTY *and* AZERTY — "école" passes over `e`.
 *  4. **Corner-host match** — the codepoint is a (loc-resolved) corner label in the
 *     [LayoutGeometry.aliases] table → the host key centroid. `ё`→`е`-host,
 *     `ъ`→`ь`-host, Ukrainian ї/є/і/ґ, Arabic `أ`→`ا`, `إ`→`غ`, `آ`→`ى`, `ذ`→`د`.
 *  5. **No match** — the word contains a codepoint untypeable on this layout →
 *     the whole word is skipped (FR-4; never guessed).
 *
 * Purity (NFR-3): only `java.text.Normalizer` / `java.util.Locale` / `kotlin.*` —
 * no `android.*`. `Locale.ROOT` case folding avoids the Turkish `I→ı` trap.
 */
object LayoutProjection {

    /**
     * Project [word] onto a sequence of key ids on [layout], or return `null` if any
     * codepoint is untypeable (the word is silently skipped, FR-4).
     *
     * The returned array is the PRE-COLLAPSE key-id sequence (consecutive duplicates
     * NOT yet removed) so the caller ([TemplateGenerator]) can detect doubled letters
     * for the loop variant. Each entry is a valid index into [LayoutGeometry.keys].
     *
     * @param word canonical (accent-preserving) dictionary form; folded per-codepoint.
     * @return key-id sequence (never empty for a non-empty typeable word), or `null`.
     */
    fun project(word: String, layout: LayoutGeometry): IntArray? {
        if (word.isEmpty()) return null
        val out = ArrayList<Int>(word.length)
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            i += Character.charCount(cp)
            // Skip codepoints that never carry a key of their own and instead modify
            // an adjacent base letter (which projects on its own turn):
            //  - non-spacing marks (Mn): Arabic harakat, Greek/Hebrew combining accents,
            //    Latin combining diacritics in a decomposed word ("café" as e+U+0301);
            //  - default-ignorable format controls: ZWNJ/ZWJ, Arabic tatweel.
            // Whitespace is NOT skipped — a space means this is not a single swipe word.
            if (isNonSpacingMark(cp) || isIgnorableFormat(cp)) continue
            val folded = foldCodepoint(cp)
            val keyId = projectCodepoint(folded, layout) ?: return null
            out.add(keyId)
        }
        // A word that was entirely ignorable-format has no anchors → untypeable.
        if (out.isEmpty()) return null
        return out.toIntArray()
    }

    /**
     * Resolve a single (already folded) codepoint to a key id via the preference
     * tiers, or `null` if untypeable. Public so [TemplateGenerator] / tests can probe
     * individual codepoints (e.g. the Ukrainian loc-corner cases).
     */
    fun projectCodepoint(folded: Int, layout: LayoutGeometry): Int? {
        // Tier 1 (single-codepoint center) + tier 2 (multi-cp component fallback):
        // both live in `chars`. The Builder seeds tier-1 single-codepoint centers and
        // tier-2 component codepoints of multi-codepoint centers into the same map,
        // so one lookup covers both — deterministically returning the FIRST (lowest)
        // key id when a codepoint maps to several keys (duplicate labels).
        firstKeyId(layout.chars[folded])?.let { return it }

        // Tier 3: NFD base match. Strip Mn marks from this codepoint's decomposition
        // and retry tiers 1–2 on the base. Only fires because tiers 1–2 already failed
        // (so `й` — a real center — never reaches here).
        //
        // GATED per-codepoint by Unicode block ([nfdStripAppliesTo] — the spec's
        // `Character.UnicodeScript` concept implemented via `Character.UnicodeBlock`
        // because UnicodeScript is Android API 24+; see the NOTE there). Never the
        // layout `script` attr, never the dictionary language. The NFD strip is the
        // ACCENT-RECOVERY model — correct for scripts whose combining marks are
        // diacritics that don't change letter identity (Latin é→e, Greek ώ→ω, Cyrillic
        // ё→е / ў→у). It is DELIBERATELY NOT applied to Arabic/Hebrew precomposed
        // letters: `إ`/`آ`/`ؤ`/`ئ` NFD-decompose to a hamza-carrier base (ا/و/ي) that
        // discards the letter's real identity and physical key placement. Those forms
        // are distinct keys on the layout and resolve via the tier-4 corner-host table
        // (`إ`→`غ`, `آ`→`ى`), which is where the user's finger actually goes. Free
        // harakat/niqqud are already skipped as standalone Mn in `project`.
        if (nfdStripAppliesTo(folded)) {
            val base = nfdStripMarks(folded)
            if (base != null) {
                // The base is normally a single codepoint (é→e, ё→е, ά→α), but a
                // decomposition can yield several base letters; take the first typeable.
                var j = 0
                while (j < base.length) {
                    val bcp = base.codePointAt(j)
                    j += Character.charCount(bcp)
                    if (Character.getType(bcp) == Character.NON_SPACING_MARK.toInt()) continue
                    firstKeyId(layout.chars[foldCodepoint(bcp)])?.let { return it }
                }
            }
        }

        // Tier 4: corner-host alias (loc-resolved corners included). `ё`→`е`-host,
        // Ukrainian ї/є/і/ґ, Arabic hamza forms, `ъ`→`ь`, `ذ`→`د`.
        layout.aliases[folded]?.let { return it }

        // Tier 5: no match → untypeable on this layout.
        return null
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** First (lowest) key id of a `chars` bucket, or `null` for an empty/absent bucket. */
    private fun firstKeyId(ids: IntArray?): Int? {
        if (ids == null || ids.isEmpty()) return null
        // Buckets are populated in row-major id order (Builder), so ids[0] is already
        // the lowest id; scan defensively so a future non-sorted insert can't regress.
        var min = ids[0]
        for (k in 1 until ids.size) if (ids[k] < min) min = ids[k]
        return min
    }

    /**
     * Per-codepoint Unicode-BLOCK gate for the tier-3 NFD strip (the spec's
     * `Character.UnicodeScript` gate, implemented via blocks — see the NOTE below).
     *
     * `true` for scripts where combining marks are pure diacritics (accent recovery is
     * the right model): Latin, Greek, Cyrillic, plus COMMON/INHERITED so a stray
     * combining sequence still resolves. `false` for Arabic/Hebrew and everything else,
     * where precomposed forms are distinct letters resolved by their tier-4 corner
     * placement rather than collapsed to an identity-losing base.
     *
     * A new diacritic-model script family (or a new Latin/Cyrillic extension block)
     * MUST be added here explicitly, or its decomposable codepoints silently skip
     * tier 3 and the containing words are dropped (FR-4-safe but coverage-narrowing).
     */
    private fun nfdStripAppliesTo(cp: Int): Boolean = when (Character.UnicodeBlock.of(cp)) {
        // NOTE: implemented via UnicodeBlock, NOT Character.UnicodeScript — UnicodeScript
        // requires Android API 24 (crashes API 21-23; minSdk is 21) and android.os.Build
        // version-guards are barred here by GeoPurityDriftTest (this engine package must
        // stay pure JVM). Blocks below cover the same gate: Latin/Greek/Cyrillic letter
        // blocks, combining-mark blocks (~script INHERITED), and Basic Latin/Latin-1 +
        // General Punctuation (~the COMMON codepoints reachable from keyboard layouts).
        // API constraint: only UnicodeBlock CONSTANTS available at minSdk 21 may appear
        // (a missing static field is a NoSuchFieldError at class-init on old devices) —
        // COMBINING_DIACRITICAL_MARKS_EXTENDED and CYRILLIC_EXTENDED_C are Android
        // API 26+ and are therefore deliberately absent; codepoints there fall to the
        // `else -> false` FR-4-safe skip.
        Character.UnicodeBlock.BASIC_LATIN,
        Character.UnicodeBlock.LATIN_1_SUPPLEMENT,
        Character.UnicodeBlock.LATIN_EXTENDED_A,
        Character.UnicodeBlock.LATIN_EXTENDED_B,
        Character.UnicodeBlock.LATIN_EXTENDED_C,
        Character.UnicodeBlock.LATIN_EXTENDED_D,
        Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL,
        Character.UnicodeBlock.GREEK,
        Character.UnicodeBlock.GREEK_EXTENDED,
        Character.UnicodeBlock.CYRILLIC,
        Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY,
        Character.UnicodeBlock.CYRILLIC_EXTENDED_A,
        Character.UnicodeBlock.CYRILLIC_EXTENDED_B,
        Character.UnicodeBlock.GENERAL_PUNCTUATION,
        Character.UnicodeBlock.COMBINING_DIACRITICAL_MARKS,
        Character.UnicodeBlock.COMBINING_DIACRITICAL_MARKS_SUPPLEMENT -> true
        else -> false
    }

    /**
     * NFD-decompose the single codepoint [cp] and return its Mn-stripped base string,
     * or `null` if the decomposition is a no-op (base == self, no marks). The result
     * may be several base codepoints. Precomposed forms decompose (é → e + U+0301);
     * atomic letters (ς, ء) return `null` (nothing to strip).
     */
    private fun nfdStripMarks(cp: Int): String? {
        val s = String(Character.toChars(cp))
        val nfd = Normalizer.normalize(s, Normalizer.Form.NFD)
        if (nfd == s) return null
        val sb = StringBuilder(nfd.length)
        var i = 0
        var strippedAny = false
        while (i < nfd.length) {
            val c = nfd.codePointAt(i)
            i += Character.charCount(c)
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) {
                strippedAny = true
            } else {
                sb.appendCodePoint(c)
            }
        }
        // Only meaningful when a mark was actually removed AND a base remains.
        return if (strippedAny && sb.isNotEmpty()) sb.toString() else null
    }

    /**
     * Codepoints that carry no key and must be skipped mid-word rather than failing
     * projection: ZWNJ/ZWJ (U+200C/D), the Arabic tatweel U+0640 (a rendering-only
     * kashida), and other default-ignorable format characters. Whitespace is NOT
     * ignorable — a space inside a token means the token is not a single swipe word.
     */
    private fun isIgnorableFormat(cp: Int): Boolean = when (cp) {
        0x200C, 0x200D, 0x0640 -> true
        else -> Character.getType(cp) == Character.FORMAT.toInt()
    }

    /** True iff [cp] is a Unicode non-spacing mark (Mn) — a combining accent/harakat. */
    private fun isNonSpacingMark(cp: Int): Boolean =
        Character.getType(cp) == Character.NON_SPACING_MARK.toInt()

    /**
     * Per-codepoint `Locale.ROOT` lowercase fold. Uses the `String` path (not
     * `Character.toLowerCase`) so full special-casing applies while ROOT avoids the
     * Turkish `I→ı` trap documented at `Keyboard2View.kt:1147-1149`.
     *
     * A single codepoint can lowercase to several (rare — German ẞ→ss is uppercase
     * only, not relevant here); we take the first codepoint of the folded form, which
     * matches how the layout `chars` map is keyed (single-codepoint centers).
     */
    fun foldCodepoint(cp: Int): Int {
        val lowered = String(Character.toChars(cp)).lowercase(Locale.ROOT)
        return lowered.codePointAt(0)
    }
}
