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
 *     (CleverKeys-ML `ctc/`): azerty 83.81, qwertz 83.01, german 80.64, spanish 88.45
 *     top-1 — i.e. fr / de / es, plus en on the QWERTY family.
 *  2. **A decoder preset validated on THIS language's lexicon scale**: the λ sweep in
 *     `docs/eval/2026-08-15-ctc-per-language-lambda.md`.
 *
 * `it`, `pt` and `sv` ship a bundled dictionary but have NEITHER (see
 * [NEEDS_VALIDATION]); they stay on the pre-existing fallback (neural on QWERTY-Latin,
 * geometric elsewhere) until a validation round exists. Enabling one later is a row in
 * [SUPPORTED] plus its evidence — not a refactor.
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
    )

    /**
     * Bundled-dictionary languages deliberately NOT enabled: no alt-layout model bar and
     * no λ sweep. Documented here so the omission reads as a decision, not an oversight.
     *
     * **The blocker is DATA, not effort (verified 2026-08-16).** Both validations need a
     * real swipe corpus in the language, and the only multi-layout human-swipe source this
     * project has — HuggingFace `futo-org/swipe.futo.org`, config `swipe-5` — contains
     * **zero rows** for `it`, `pt` and `sv` (queried via the datasets-server `/filter`
     * endpoint: `language='it'|'pt'|'sv'|'nl'` all return `num_rows_total = 0`, against
     * en 47,364 / fr 3,124). So these languages cannot be swept or model-validated until a
     * corpus is acquired — do not re-attempt a sweep expecting to find data.
     *
     * They are NOT broken meanwhile: they keep the pre-existing neural (QWERTY) /
     * geometric (elsewhere) coverage. Enabling one later needs (a) a swipe corpus in that
     * language, then (b) a [SUPPORTED] row plus its evidence.
     */
    val NEEDS_VALIDATION: Set<String> = setOf("it", "pt", "sv")

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
