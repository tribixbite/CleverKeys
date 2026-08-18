package tribixbite.cleverkeys.swipe.ctc

import java.util.Locale

/**
 * THE table of languages the CTC swipe engine serves, and where each one's lexicon
 * comes from. Pure (no Android) so both the adapter and `runPureTests` read the same
 * source of truth; `CtcScoringParams.presetFor` keys its λ off [LexiconSource].
 *
 * ## Why a table and not a single constant
 *
 * The shipped encoder (`models/ctc_swipe_encoder.onnx`) is layout- and language-agnostic
 * — it emits a–z posteriors from geometry alone — so a language is "supported" exactly
 * when BOTH of these exist for it:
 *
 *  1. **Model-level validation**: an alt-layout accuracy bar from the training campaign
 *     (CleverKeys-ML `ctc/MODELS_TABLE.md:113`). For the model we actually ship
 *     (`phaseM_kd_fresh_w1_s1234_fp16w`): azerty **84.53**, qwertz **83.97**, german
 *     **81.30**, spanish **89.53** top-1 (euro-mean 84.83) — i.e. fr / de / es, plus en
 *     on the QWERTY family. These CLEAR the campaign bars, which are a separate set of
 *     numbers: azerty 83.60 / qwertz 82.50 / german 79.64 / spanish 88.28
 *     (`MODELS_TABLE.md:132-136`).
 *
 *     Measured on the **`az26` arm** — only the 26 a–z keys are given to the model, mask
 *     26 — which is exactly what `CtcEngineAdapter.buildMappedLayout` builds. The `full`
 *     arm (27 slots for dvorak/azerty/spanish, 29 for german) was measured and buys
 *     nothing: +0.05 / +0.10 / 0.00 / −0.23 (`ctc/ALT_LAYOUT_EVAL.md:303-311`).
 *
 *     Until 2026-08-18 this KDoc quoted 83.81 / 83.01 / 80.64 / 88.45. Those belong to
 *     `sw2345` (`MODELS_TABLE.md:139`), a superseded Phase-J model that was **never
 *     decoded on test** — the citation named the wrong model, and understated ours.
 *  2. **A decoder preset validated on THIS language's lexicon scale**: the λ sweep in
 *     `docs/eval/2026-08-15-ctc-per-language-lambda.md`.
 *
 * `it`, `pt` and `sv` have (2) by scale transfer but not (1) — no corpus exists to measure a
 * bar against. They were enabled anyway on 2026-08-18 and are flagged [PROVISIONAL]: the
 * fallback they would otherwise sit on (geometric) has no per-language bar EITHER and is
 * 15–22 points worse wherever both engines were measured, so withholding CTC was protecting
 * an evidence standard the alternative never met. Read [PROVISIONAL] before quoting any
 * number for these three.
 */
object CtcLanguageSupport {

    /** Where a language's CTC lexicon is read from — and therefore its frequency scale. */
    enum class LexiconSource {
        /**
         * `dictionaries/en_enhanced.json` — flat `{word: byteScore}` on the COMPRESSED
         * 134–255 AOSP-like scale (`ln f ∈ [4.9, 5.54]`). Loaded a–z-STRIPPING, without
         * accent folding, exactly as it shipped and was validated (test-2400, 89.31 t1).
         */
        EN_JSON,

        /**
         * `dictionaries/<lang>_enhanced.bin` — CKDT v2, read at `freq = max(1, 255 − rank)`
         * ([CtcCkdtLexicon]), an INVERTED full-range 1–255 scale, then projected onto a–z
         * with the canonical accented form retained for display ([CtcAzProjection]).
         */
        CKDT_BIN,
    }

    /**
     * Language code → lexicon source, for every language the CTC engine may decode.
     * Insertion-ordered so the set reads as "en first, then the 2026-08-15 additions".
     */
    val SUPPORTED: Map<String, LexiconSource> = linkedMapOf(
        "en" to LexiconSource.EN_JSON,
        "fr" to LexiconSource.CKDT_BIN,
        "de" to LexiconSource.CKDT_BIN,
        "es" to LexiconSource.CKDT_BIN,
        // 2026-08-18, PROVISIONAL — see [PROVISIONAL] for the evidence tier and why these
        // are enabled anyway.
        "it" to LexiconSource.CKDT_BIN,
        "pt" to LexiconSource.CKDT_BIN,
        "sv" to LexiconSource.CKDT_BIN,
    )

    /**
     * Languages served on SCALE-TRANSFERRED evidence rather than their own measured bar.
     *
     * ## Why these are enabled without a per-language accuracy bar
     *
     * The honest position: no swipe corpus exists for it/pt/sv (HuggingFace
     * `futo-org/swipe.futo.org` `swipe-5` returns `num_rows_total = 0` for all three), so no
     * per-language bar can be produced and none is claimed here.
     *
     * What DOES transfer is the preset, by the project's own stated principle: λ is calibrated
     * against the LEXICON'S FREQUENCY SCALE, not against the language ([presetFor]'s KDoc).
     * These three read the same CKDT `.bin` scale as fr/de/es, where λ 2.0 won the tune half in
     * 3 of 4 corpora and was independently confirmed by a Cyrillic sweep around a different
     * base preset. The encoder itself is language-agnostic — it emits a–z posteriors from
     * geometry, and never sees a language.
     *
     * The alternative was NOT "wait for evidence". It was geometric, which has no per-language
     * bar either and which CTC beat by 15–22 points on every language where both were measured
     * (test-2400: CTC 89.31 vs geometric 67.50). Declining to enable would have kept these
     * users on the measurably worse engine in the name of an evidence standard the fallback
     * does not meet.
     *
     * It also fixes a real defect: geometric has NO contraction-alias injection, so after the
     * neural engine's removal only 18 of 21,214 Italian alias keys were reachable —
     * `dell'acqua`, `un'altra` and `l'ago` could not be swiped at all. CTC injects
     * ([CtcContractionKeys]), so enabling restores them.
     *
     * **Consequence to respect:** anything measured on these languages is val-tier at best and
     * may never be quoted beside en/fr/de/es's test-validated numbers. Promote a language out
     * of this set only by adding its own bar, never by familiarity.
     */
    val PROVISIONAL: Set<String> = setOf("it", "pt", "sv")

    /**
     * Bundled-dictionary languages CTC does not serve. **Empty since 2026-08-18** — every
     * bundled dictionary now has a [SUPPORTED] row, the last three ([PROVISIONAL]) on
     * scale-transferred evidence.
     *
     * Kept as a named concept rather than deleted, because the distinction it encodes is
     * still the one that matters when a NEW language is added: a bundled dictionary alone is
     * not sufficient, and the entry belongs here until someone decides which tier it enters
     * [SUPPORTED] at.
     *
     * **The missing evidence is DATA, not effort (verified 2026-08-16).** A per-language bar
     * needs a real swipe corpus, and the only multi-layout human-swipe source this project has
     * — HuggingFace `futo-org/swipe.futo.org`, config `swipe-5` — contains **zero rows** for
     * `it`, `pt`, `sv` and `nl` (datasets-server `/filter`, against en 47,364 / fr 3,124). Do
     * not re-attempt that sweep expecting to find data. That is why it/pt/sv shipped
     * provisional rather than measured — see [PROVISIONAL] for why waiting was the worse
     * option.
     */
    val NEEDS_VALIDATION: Set<String> = emptySet()

    /**
     * Canonical lookup key for [language]: lowercased, region subtag dropped
     * (`"fr-CA"` / `"es_MX"` → `"fr"` / `"es"`). Blank/null → `""`, which matches nothing.
     */
    fun normalize(language: String?): String {
        if (language.isNullOrBlank()) return ""
        val lower = language.trim().lowercase(Locale.ROOT)
        val cut = lower.indexOfFirst { it == '-' || it == '_' }
        return if (cut >= 0) lower.substring(0, cut) else lower
    }

    /** The lexicon source for [language], or null when CTC does not serve it. */
    fun sourceFor(language: String?): LexiconSource? = SUPPORTED[normalize(language)]

    /** True when the CTC engine may decode [language]. */
    fun isSupported(language: String?): Boolean = sourceFor(language) != null

    /**
     * The bundled asset path holding [language]'s CTC lexicon, or null when unsupported.
     * Keeping the path here (rather than in the adapter) is what makes adding a language
     * a single table edit.
     */
    fun assetFor(language: String?): String? {
        val code = normalize(language)
        return when (SUPPORTED[code]) {
            LexiconSource.EN_JSON -> "dictionaries/${code}_enhanced.json"
            LexiconSource.CKDT_BIN -> "dictionaries/${code}_enhanced.bin"
            null -> null
        }
    }
}
