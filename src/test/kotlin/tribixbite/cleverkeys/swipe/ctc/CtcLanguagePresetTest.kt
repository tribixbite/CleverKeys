package tribixbite.cleverkeys.swipe.ctc

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
        // `it`/`pt`/`sv` were in this list until 2026-08-18, when they were enabled
        // provisionally — they are now KNOWN CKDT-scale languages and are asserted below to
        // take λ 2.0, not the English fallback.
        for (lang in listOf("ru", "zz", "", "nl", "xx-YY")) {
            assertThat(CtcScoringParams.presetFor(lang).lambda)
                .isEqualTo(CtcScoringParams.tunedV2().lambda)
        }
    }

    @Test
    fun `provisional languages take the ckdt lambda, not the english fallback`() {
        // The scale-transfer argument that justified enabling them IS this assignment: these
        // read a CKDT `.bin`, so they must get the λ fitted on that scale. Landing on the
        // English 4.0 would apply a λ fitted for an 8x narrower log-frequency spread and
        // silently over-weight frequency — the exact failure the per-language sweep found.
        for (lang in CtcLanguageSupport.PROVISIONAL) {
            assertWithMessage("provisional language '$lang' must decode at the CKDT-scale λ")
                .that(CtcScoringParams.presetFor(lang).lambda)
                .isEqualTo(2.0)
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
    fun `supported languages are every bundled dictionary language`() {
        assertThat(CtcLanguageSupport.SUPPORTED.keys)
            .containsExactly("en", "fr", "de", "es", "it", "pt", "sv")
    }

    @Test
    fun `provisional languages are enabled but flagged as scale-transferred`() {
        // 2026-08-18: it/pt/sv were enabled WITHOUT a per-language accuracy bar, because no
        // swipe corpus exists to measure one against and the alternative (geometric) has no
        // bar either while being 15–22 pt worse where both were measured. The flag is what
        // keeps that honest — anything measured on these is val-tier at best and must never
        // be quoted beside the test-validated four.
        for (lang in listOf("it", "pt", "sv")) {
            assertThat(CtcLanguageSupport.isSupported(lang)).isTrue()
            assertThat(CtcLanguageSupport.PROVISIONAL).contains(lang)
            // Scale transfer is the entire justification, so it must actually hold: a
            // provisional language MUST read the CKDT scale λ 2.0 was fitted on. Enabling one
            // on the EN_JSON scale would silently apply the wrong λ.
            assertThat(CtcLanguageSupport.sourceFor(lang))
                .isEqualTo(CtcLanguageSupport.LexiconSource.CKDT_BIN)
        }
        // Provisional is a subset of supported, never a parallel list that can drift.
        assertThat(CtcLanguageSupport.SUPPORTED.keys)
            .containsAtLeastElementsIn(CtcLanguageSupport.PROVISIONAL)
        // The test-validated four must never silently acquire a provisional flag.
        assertThat(CtcLanguageSupport.PROVISIONAL)
            .containsNoneIn(listOf("en", "fr", "de", "es"))
    }

    @Test
    fun `no bundled dictionary language is left unserved`() {
        // Every language with a bundled CTC lexicon is now routed to CTC; NEEDS_VALIDATION is
        // empty. It is retained as a concept for the NEXT language added, so this asserts the
        // invariant rather than the emptiness: nothing may be both bundled and unserved.
        assertThat(CtcLanguageSupport.NEEDS_VALIDATION).isEmpty()
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

    @Test
    fun `the recorded ru preset keeps E1's base and only moves lambda`() {
        val ru = CtcScoringParams.tunedRuCkdt()
        // E1 (1.05 / 1.1 / 0.2 / 0.3734 / 0.9882) with lambda 1.1 -> 2.0. The Cyrillic
        // sweep varied lambda alone, holding E1 fixed (CleverKeys-ML PHASE_J.md 6.9).
        assertThat(ru.gamma).isEqualTo(1.05)
        assertThat(ru.lambda).isEqualTo(CtcScoringParams.LAMBDA_CKDT_SCALE)
        assertThat(ru.beta).isEqualTo(0.2)
        assertThat(ru.alpha).isEqualTo(0.0)
        assertThat(ru.gammaPrune).isEqualTo(0.3734)
        assertThat(ru.betaPrune).isEqualTo(0.9882)
        // beamWidth/topK are pass-through, like every other preset factory.
        val wide = CtcScoringParams.tunedRuCkdt(beamWidth = 250, topK = 8)
        assertThat(wide.beamWidth).isEqualTo(250)
        assertThat(wide.topK).isEqualTo(8)
    }

    @Test
    fun `the ru preset is on a different footing than the shipping axis`() {
        // The two sweeps agree on lambda and on NOTHING ELSE, because they were run
        // around different base presets (E1 = benchmark footing, tunedV2 = app footing).
        // Pinned so the disagreement stays a documented decision instead of decaying
        // into a bug the first time someone enables a Cyrillic path: shipping ru starts
        // with a FOOTING choice, not a lambda lookup.
        val ru = CtcScoringParams.tunedRuCkdt()
        val axis = CtcScoringParams.presetFor("ru")

        // ru is absent from SUPPORTED, so the axis cannot know it is a CKDT-scale
        // language: presetFor falls through to the ENGLISH lambda, not the Cyrillic one.
        // That is correct defense-in-depth, and it is also exactly why tunedRuCkdt has to
        // be requested by name rather than reached through the axis.
        assertThat(axis.lambda).isEqualTo(CtcScoringParams.LAMBDA_EN_JSON_SCALE)
        assertThat(ru.lambda).isEqualTo(CtcScoringParams.LAMBDA_CKDT_SCALE)
        assertThat(ru).isNotEqualTo(axis)

        // The bases differ too — E1 vs tunedV2 on gamma, beta and gammaPrune.
        assertThat(listOf(ru.gamma, ru.beta, ru.gammaPrune))
            .isNotEqualTo(listOf(axis.gamma, axis.beta, axis.gammaPrune))

        // And ru is NOT decodable today, so the adapter can never build either one.
        assertThat(CtcLanguageSupport.isSupported("ru")).isFalse()
    }
}
