package tribixbite.cleverkeys.swipe.ctc

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The per-language CTC preset table — the app-side half of the λ sweep recorded in
 * `docs/eval/2026-08-15-ctc-per-language-lambda.md`.
 *
 * λ multiplies the trie's `ln(freq)` term, so it is calibrated against the FREQUENCY
 * SCALE of the lexicon, not against the language: `en_enhanced.json`'s compressed
 * 134–255 byte scores need λ 4.0, while a CKDT `.bin` read at `freq = max(1, 255−rank)`
 * spans the full 1–255 range and needs λ 2.0 (λ 4.0 measured −1.5 to −3.2 pt there).
 * These cases pin that mapping, the language table it is keyed by, and the invariant
 * that NOTHING ELSE in the ship preset moves per language.
 */
class CtcLanguagePresetTest {

    // ── λ selection ────────────────────────────────────────────────────────────────

    @Test
    fun `english keeps the fitted lambda of 4`() {
        assertThat(CtcScoringParams.presetFor("en").lambda).isEqualTo(4.0)
    }

    @Test
    fun `ckdt-scale languages use lambda 2`() {
        for (lang in listOf("fr", "de", "es")) {
            assertThat(CtcScoringParams.presetFor(lang).lambda).isEqualTo(2.0)
        }
    }

    @Test
    fun `unknown language falls back to the english preset`() {
        // Defense-in-depth only: the adapter never decodes an unsupported language.
        for (lang in listOf("ru", "zz", "", "it", "pt", "sv")) {
            assertThat(CtcScoringParams.presetFor(lang).lambda)
                .isEqualTo(CtcScoringParams.tunedV2().lambda)
        }
    }

    @Test
    fun `language matching is case and region insensitive`() {
        assertThat(CtcScoringParams.presetFor("FR").lambda).isEqualTo(2.0)
        assertThat(CtcScoringParams.presetFor("fr-FR").lambda).isEqualTo(2.0)
        assertThat(CtcScoringParams.presetFor("de_DE").lambda).isEqualTo(2.0)
        assertThat(CtcScoringParams.presetFor("EN-US").lambda).isEqualTo(4.0)
    }

    // ── Everything else in tunedV2 is language-INVARIANT ───────────────────────────

    @Test
    fun `only lambda varies across languages`() {
        val en = CtcScoringParams.presetFor("en")
        val fr = CtcScoringParams.presetFor("fr")
        assertThat(fr.copy(lambda = en.lambda)).isEqualTo(en)
    }

    @Test
    fun `english preset is byte-identical to tunedV2`() {
        assertThat(CtcScoringParams.presetFor("en", beamWidth = 100, topK = 4))
            .isEqualTo(CtcScoringParams.tunedV2(beamWidth = 100, topK = 4))
    }

    @Test
    fun `ship constants stay at the validated values`() {
        for (lang in listOf("en", "fr", "de", "es", "zz")) {
            val p = CtcScoringParams.presetFor(lang)
            assertThat(p.gamma).isEqualTo(0.9)
            assertThat(p.beta).isEqualTo(0.25)
            assertThat(p.alpha).isEqualTo(0.0)
            assertThat(p.gammaPrune).isEqualTo(0.25)
            assertThat(p.betaPrune).isEqualTo(0.9882)
        }
    }

    @Test
    fun `beam width and topK pass through`() {
        val p = CtcScoringParams.presetFor("es", beamWidth = 137, topK = 8)
        assertThat(p.beamWidth).isEqualTo(137)
        assertThat(p.topK).isEqualTo(8)
    }

    @Test
    fun `default beam width matches the campaign decode width`() {
        assertThat(CtcScoringParams.presetFor("fr").beamWidth).isEqualTo(100)
    }

    // ── The language table ─────────────────────────────────────────────────────────

    @Test
    fun `supported languages are exactly the validated four`() {
        assertThat(CtcLanguageSupport.SUPPORTED.keys)
            .containsExactly("en", "fr", "de", "es")
    }

    @Test
    fun `bundled but unvalidated languages are not enabled`() {
        // it/pt/sv ship a dictionary but have neither an alt-layout model bar nor a λ
        // sweep — they stay on the existing fallback until a validation round exists.
        for (lang in listOf("it", "pt", "sv")) {
            assertThat(CtcLanguageSupport.isSupported(lang)).isFalse()
            assertThat(CtcLanguageSupport.NEEDS_VALIDATION).contains(lang)
        }
        assertThat(CtcLanguageSupport.SUPPORTED.keys)
            .containsNoneIn(CtcLanguageSupport.NEEDS_VALIDATION)
    }

    @Test
    fun `lexicon source is json for english and ckdt for the rest`() {
        assertThat(CtcLanguageSupport.sourceFor("en"))
            .isEqualTo(CtcLanguageSupport.LexiconSource.EN_JSON)
        for (lang in listOf("fr", "de", "es")) {
            assertThat(CtcLanguageSupport.sourceFor(lang))
                .isEqualTo(CtcLanguageSupport.LexiconSource.CKDT_BIN)
        }
        assertThat(CtcLanguageSupport.sourceFor("ru")).isNull()
    }

    @Test
    fun `asset paths match the bundled dictionary names`() {
        assertThat(CtcLanguageSupport.assetFor("en")).isEqualTo("dictionaries/en_enhanced.json")
        assertThat(CtcLanguageSupport.assetFor("fr")).isEqualTo("dictionaries/fr_enhanced.bin")
        assertThat(CtcLanguageSupport.assetFor("de")).isEqualTo("dictionaries/de_enhanced.bin")
        assertThat(CtcLanguageSupport.assetFor("es")).isEqualTo("dictionaries/es_enhanced.bin")
        assertThat(CtcLanguageSupport.assetFor("ru")).isNull()
    }

    @Test
    fun `isSupported normalizes like presetFor`() {
        assertThat(CtcLanguageSupport.isSupported("EN")).isTrue()
        assertThat(CtcLanguageSupport.isSupported("fr-CA")).isTrue()
        assertThat(CtcLanguageSupport.isSupported("es_MX")).isTrue()
        assertThat(CtcLanguageSupport.isSupported(null)).isFalse()
        assertThat(CtcLanguageSupport.isSupported("")).isFalse()
        assertThat(CtcLanguageSupport.isSupported("ru")).isFalse()
    }

    @Test
    fun `every supported language has an asset and a lambda`() {
        // Adding a language must be a TABLE ENTRY, not a refactor: this asserts the
        // table stays internally total.
        for (lang in CtcLanguageSupport.SUPPORTED.keys) {
            assertThat(CtcLanguageSupport.assetFor(lang)).isNotNull()
            assertThat(CtcScoringParams.presetFor(lang).lambda).isGreaterThan(0.0)
        }
    }
}
