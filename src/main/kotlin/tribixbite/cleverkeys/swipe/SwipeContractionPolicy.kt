package tribixbite.cleverkeys.swipe

import tribixbite.cleverkeys.swipe.ctc.CtcLanguageSupport

/**
 * WHICH language's contraction mappings a swipe engine may overlay onto a decoded slate.
 *
 * ## The product rule (2026-08-16)
 *
 * **Code-switching is a BUG, not a feature.** English words may only be output from a swipe
 * when the user has actually selected English — and English MORPHOLOGY (contractions,
 * possessives) must never bleed into a sentence the user is typing in another language.
 *
 * The gate is therefore the **ACTIVE DECODE LANGUAGE**, not "en is somewhere in the
 * configured set": `DictionaryManager.getCurrentLanguage()` can only ever return a
 * CONFIGURED language (both the manual switch and auto-detect select from
 * `getConfiguredLanguages()`), so an active-language gate already satisfies the rule — and
 * it additionally fixes the fr+en bilingual case, where an "en ∈ configured" gate would
 * still leak English forms into French sentences.
 *
 * ## What this fixes
 *
 * Both swipe adapters used to load the bundled ENGLISH base set (`contractions.bin` +
 * `contraction_pairings.json`, 1,183 paired display forms of which 1,116 are English
 * possessives) for EVERY language before the active language's file. An English pairing
 * keyed on a word that also exists in the other language then injected an English variant:
 * `CtcMultiLanguageInstrumentedTest` caught a `fr` decode of the real French word `franco`
 * also offering the English possessive `franco's`, whose base `francos` is (correctly)
 * absent from the 37,949-word French trie.
 *
 * The NEURAL engine already fixed exactly this in v1.1.88 (`OptimizedVocabulary`: clear the
 * English contractions, then load the target language's), and possessive augmentation is
 * already English-gated in the shared pipeline
 * (`SuggestionHandler.shouldAugmentPossessives` — on non-English "it fabricates junk like
 * дом's / maison's"). This object is the geo+CTC half of the same policy.
 *
 * Pure JVM (no Android imports) so the decision is unit-testable in `runPureTests`;
 * `ContractionManager.loadSwipeDisplayMappings` is the loader that executes it.
 */
object SwipeContractionPolicy {

    /** The one language whose bundled contraction BASE set may be loaded. */
    const val ENGLISH = "en"

    /**
     * The base subtag of [language], lowercased: `en-GB` / `en_US` → `en`, `fr-CA` → `fr`.
     *
     * DELEGATES to [CtcLanguageSupport.normalize] rather than reimplementing it. The two
     * were written independently — on two checkouts, from the same requirement — and were
     * character-for-character identical, which is a drift waiting to happen: the
     * contraction gate and the lexicon table MUST agree about which language is active, and
     * two copies can only guarantee that until someone edits one of them.
     *
     * TODO: the canonical definition currently lives under `swipe.ctc` because that is the
     * consumer whose result also picks preference keys, so `swipe` depends on `swipe.ctc`
     * here. If a third consumer appears, lift it to a neutral pure helper instead of adding
     * a copy — `GeometricEngineAdapter.dictionaryFor` is already a near-miss (see its TODO).
     */
    fun baseSubtag(language: String?): String = CtcLanguageSupport.normalize(language)

    /**
     * True when a swipe decoding [language] may use the bundled ENGLISH contraction base
     * (`contractions.bin` + `contraction_pairings.json` + `contractions_en.json`).
     *
     * Only English does — including its regional variants, so an `en-GB` user keeps the
     * exact pre-fix behavior. A BLANK/absent code also keeps it: that means the caller does
     * not know the active language yet (never a user's deliberate non-English selection),
     * and `en` is the app's default language. Any other code — including an unknown one —
     * gets ONLY its own language file, which for a language that ships no contractions
     * (es, pt, sv: the bundled files are literally `{}`) means no overlay at all. That is
     * the intended outcome: no contractions beats another language's contractions.
     */
    fun usesEnglishBase(language: String?): Boolean {
        val base = baseSubtag(language)
        return base.isEmpty() || base == ENGLISH
    }
}
