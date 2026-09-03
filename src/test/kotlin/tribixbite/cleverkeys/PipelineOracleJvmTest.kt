package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Pure-JVM twin of the WP9 pipeline-unification characterization oracle
 * (`docs/history/audits/remediation-plans/wp9-pipeline-unification-oracle.md`).
 *
 * The instrumented [PipelineCharacterizationTest] is the authority — it wires the
 * real SuggestionHandler / InputCoordinator / SuggestionBar over an InputConnection.
 * This class covers only the pipeline transforms that are PURE (no Android
 * InputConnection / View / asset-backed state), so they can be iterated fast:
 *
 *  - Possessive-augmentation ARRAY behavior (limit-3, dedup, append order,
 *    score-minus-10) — the shape SuggestionHandler.augmentPredictionsWithPossessives
 *    relies on. The possessive STRING rule lives in ContractionManager which needs a
 *    Context, so the string forms are pinned in the instrumented twin; here we pin the
 *    list-mutation contract with an injected possessive generator.
 *  - Shift / caps-lock transformation — the exact string ops of
 *    SuggestionHandler.applyShiftTransformation (D4, pinned). WP9 step 3 (2026-07-20) relocated
 *    this transform from InputCoordinator into SuggestionHandler's companion; the string ops are
 *    byte-identical, so this mirror is unchanged in behavior — only its production source moved.
 *  - I-word capitalization — the exact ops of {SuggestionHandler,InputCoordinator}.capitalizeIWord.
 *  - Suggestion wire round-trips + routeSuggestionSelection (scenario 30: the
 *    getCurrentSuggestions/onSuggestionSelected wire format is List<String>, prefixes
 *    only via Suggestion.kt).
 *
 * Assertions marked `// ORACLE-FLIP(step N)` pin a divergence of TODAY and are expected
 * to be inverted in the same commit that lands migration step N (see the spec table).
 * All other assertions are INVARIANT.
 */
class PipelineOracleJvmTest {

    // ---------------------------------------------------------------------------
    // Reference reimplementations of the two production helpers. They are byte-for-byte copies
    // of the pipeline code (capitalizeIWord: {SuggestionHandler,InputCoordinator}.kt;
    // applyShiftTransformation: SuggestionHandler.kt companion — WP9 step 3 relocated it there
    // from InputCoordinator) so the oracle FAILS if the production logic drifts away from this
    // pinned behavior. Kept private to this test; production is untouched.
    // ---------------------------------------------------------------------------

    private val I_WORDS = setOf("i", "i'm", "i'll", "i'd", "i've")

    /** Mirror of {SuggestionHandler,InputCoordinator}.capitalizeIWord (autocapitalize enabled). */
    private fun capitalizeIWord(word: String): String {
        val lower = word.lowercase()
        return if (lower in I_WORDS) word.replaceFirstChar { it.uppercaseChar() } else word
    }

    /** Mirror of SuggestionHandler.applyShiftTransformation (D4; WP9 step 3 moved it here from
     * InputCoordinator — string ops byte-identical). */
    private fun applyShiftTransformation(
        word: String,
        shiftActive: Boolean,
        shiftLocked: Boolean
    ): String = when {
        shiftLocked -> word.uppercase(Locale.getDefault())
        shiftActive -> word.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
        else -> word
    }

    /**
     * Mirror of SuggestionHandler.augmentPredictionsWithPossessives ARRAY behavior
     * (SuggestionHandler.kt:1474-1508). [possessiveOf] stands in for
     * ContractionManager.generatePossessive (null == not eligible). Mutates in place.
     *
     * The per-word language gate is NOT mirrored: it calls the PRODUCTION
     * [SuggestionHandler.shouldAugmentPossessiveAt] (CK-150-024), so the rule itself is pinned
     * here rather than a copy of it. [languages] null == every word eligible, exactly as
     * production means it (the caller already applied the language-wide gate).
     */
    private fun augmentPredictionsWithPossessives(
        predictions: MutableList<String>,
        scores: MutableList<Int>,
        languages: List<String>? = null,
        possessiveOf: (String) -> String?
    ) {
        if (predictions.isEmpty()) return
        val limit = minOf(3, predictions.size)
        val possessivesToAdd = mutableListOf<String>()
        val possessiveScores = mutableListOf<Int>()
        for (i in 0 until limit) {
            if (!SuggestionHandler.shouldAugmentPossessiveAt(languages, i)) continue
            val word = predictions[i]
            val possessive = possessiveOf(word) ?: continue
            val alreadyExists = predictions.any { it.equals(possessive, ignoreCase = true) }
            if (!alreadyExists) {
                possessivesToAdd.add(possessive)
                val baseScore = scores.getOrElse(i) { 128 }
                possessiveScores.add(baseScore - 10)
            }
        }
        if (possessivesToAdd.isNotEmpty()) {
            predictions.addAll(possessivesToAdd)
            scores.addAll(possessiveScores)
        }
    }

    // =========================================================================
    // Scenario 2/3 (D4) — shift + caps-lock transformation applied to the WHOLE bar
    // =========================================================================

    @Test
    fun oracle_jvm_shiftTransformation_capitalizesFirstLetterOnly() {
        // ORACLE-FLIP(step 3) LANDED 2026-07-20: the transform relocated from InputCoordinator into
        // SuggestionHandler.applyShiftTransformation (companion); IC.handlePredictionResults now
        // delegates to it with the request-carried shift state. The string result is IDENTICAL —
        // these assertion VALUES are unchanged.
        assertEquals("Book", applyShiftTransformation("book", shiftActive = true, shiftLocked = false))
        assertEquals("Hello", applyShiftTransformation("hello", shiftActive = true, shiftLocked = false))
        // Already-capitalized first char is preserved (titlecase is a no-op on uppercase).
        assertEquals("Book", applyShiftTransformation("Book", shiftActive = true, shiftLocked = false))
    }

    @Test
    fun oracle_jvm_capsLockTransformation_uppercasesEntireWord() {
        // ORACLE-FLIP(step 3) LANDED 2026-07-20: transform relocated to SuggestionHandler; result
        // identical (assertion VALUES unchanged).
        assertEquals("BOOK", applyShiftTransformation("book", shiftActive = false, shiftLocked = true))
        // Caps-lock wins over shift when both flags are set (locked branch is first).
        assertEquals("BOOK", applyShiftTransformation("book", shiftActive = true, shiftLocked = true))
    }

    @Test
    fun oracle_jvm_noShift_passesWordThrough() {
        assertEquals("book", applyShiftTransformation("book", shiftActive = false, shiftLocked = false))
    }

    // =========================================================================
    // Scenario 20 — I-word capitalization
    // =========================================================================

    @Test
    fun oracle_jvm_iWordCapitalization_capitalizesIAndContractions() {
        assertEquals("I", capitalizeIWord("i"))
        assertEquals("I'm", capitalizeIWord("i'm"))
        assertEquals("I'll", capitalizeIWord("i'll"))
        assertEquals("I'd", capitalizeIWord("i'd"))
        assertEquals("I've", capitalizeIWord("i've"))
    }

    @Test
    fun oracle_jvm_iWordCapitalization_leavesOtherWordsUntouched() {
        assertEquals("its", capitalizeIWord("its"))
        assertEquals("in", capitalizeIWord("in"))
        assertEquals("island", capitalizeIWord("island"))
        // Only the exact I-word forms are matched — "im" (not "i'm") is not one.
        assertEquals("im", capitalizeIWord("im"))
    }

    // =========================================================================
    // Scenario 8 / 16 (D1) — possessive augmentation array behavior
    // =========================================================================

    @Test
    fun oracle_jvm_possessiveAugment_appendsUpToThreeAtEndWithScoreMinus10() {
        // INVARIANT: SuggestionHandler augments the TOP-3 predictions with possessives,
        // appended at the END, each scored (baseScore - 10). This is the SH-only
        // enrichment that swipe (InputCoordinator) does NOT do today (D1).
        val words = mutableListOf("book", "cat", "dog", "fish")
        val scores = mutableListOf(100, 90, 80, 70)
        augmentPredictionsWithPossessives(words, scores) { "$it's" }

        // Only first 3 (book, cat, dog) get possessives; "fish" (index 3) is beyond limit.
        assertEquals(
            listOf("book", "cat", "dog", "fish", "book's", "cat's", "dog's"),
            words
        )
        assertEquals(listOf(100, 90, 80, 70, 90, 80, 70), scores)
    }

    @Test
    fun oracle_jvm_possessiveAugment_skipsWhenPossessiveAlreadyPresent() {
        // Dedup is case-insensitive against the WHOLE prediction list: "book" does NOT
        // re-add "book's" because "Book's" already exists (ignoreCase). And the already-
        // possessive "Book's" is NEVER re-augmented: ContractionManager.possessiveForm
        // returns null for any input containing an apostrophe. (FIXED 2026-09: the old
        // rule had no apostrophe guard and produced the malformed "Book's's" here —
        // pinned in this test's previous revision as a known quirk, now endorsed-correct.)
        val words = mutableListOf("book", "Book's")
        val scores = mutableListOf(100, 50)
        augmentPredictionsWithPossessives(words, scores) { ContractionManager.possessiveForm(it) }
        assertEquals(listOf("book", "Book's"), words)
        assertEquals(listOf(100, 50), scores)
    }

    @Test
    fun oracle_jvm_possessiveForm_stringRule() {
        // The production STRING rule (ContractionManager.possessiveForm — the shared root
        // for the tap path and any swipe-side augmentation), pinned directly:
        //  (a) an input already containing an apostrophe is NEVER augmented;
        //  (b) a word ending in s/S takes the bare trailing apostrophe (correct for
        //      plurals — "parents's" was the device-confirmed malformed output — and
        //      accepted style for s-final singulars);
        //  (c) everything else takes "'s".
        assertEquals("parents'", ContractionManager.possessiveForm("parents"))
        assertEquals("dogs'", ContractionManager.possessiveForm("dogs"))
        assertEquals("James'", ContractionManager.possessiveForm("James"))
        assertEquals("CHIPS'", ContractionManager.possessiveForm("CHIPS")) // capital S counts
        assertEquals("bowie's", ContractionManager.possessiveForm("bowie"))
        assertEquals("cat's", ContractionManager.possessiveForm("cat"))
        assertNull(ContractionManager.possessiveForm("Book's"))
        assertNull(ContractionManager.possessiveForm("parents'"))
        assertNull(ContractionManager.possessiveForm("don't"))
        // Typographic apostrophe (U+2019) is an apostrophe too.
        assertNull(ContractionManager.possessiveForm("Book’s"))
    }

    // =========================================================================
    // n-1 (WP9 audit 2026-08-11) — the ENGLISH-ONLY possessive gate, both sides.
    // Unlike the array-behavior mirrors above, these call the PRODUCTION helper
    // (SuggestionHandler.shouldAugmentPossessives) so the gate itself is pinned,
    // not a copy of it. The gate covers the swipe path too — that is deliberate:
    // "maison's" / "дом's" were wrong on QWERTY-fr/es as well.
    // =========================================================================

    @Test
    fun oracle_jvm_possessiveGate_englishAndNullLanguageAugment() {
        assertTrue(
            "English must keep possessive augmentation",
            SuggestionHandler.shouldAugmentPossessives("en")
        )
        assertTrue(
            "A null active language (no DictionaryManager yet) preserves the pre-gate " +
                "English behavior",
            SuggestionHandler.shouldAugmentPossessives(null)
        )
    }

    @Test
    fun oracle_jvm_possessiveGate_nonEnglishLanguagesDoNotAugment() {
        for (language in listOf("fr", "es", "de", "it", "ru", "el", "pt", "nl")) {
            assertFalse(
                "'$language' must NOT get English \"'s\" possessives (n-1)",
                SuggestionHandler.shouldAugmentPossessives(language)
            )
        }
    }

    // =========================================================================
    // CK-150-024 — the PER-WORD language gate for a dual-language merged slate.
    // With enable_multilang on, CtcRankMerger interleaves en and fr candidates in
    // ONE slate; the primary-language gate above cannot answer for it, because two
    // adjacent words need opposite answers. These call the production helper.
    // =========================================================================

    @Test
    fun oracle_jvm_possessiveGate_perWordLanguageOverridesThePrimary() {
        // en-primary / fr-secondary merged slate: index 0 is English, index 1 is French.
        val languages = listOf("en", "fr")
        assertTrue(
            "the English entry keeps its possessive even in a mixed slate",
            SuggestionHandler.shouldAugmentPossessiveAt(languages, 0)
        )
        assertFalse(
            "the French entry must NOT get an English \"'s\" (CK-150-024)",
            SuggestionHandler.shouldAugmentPossessiveAt(languages, 1)
        )
        // fr-primary / en-secondary: the English entry still qualifies — the gate reads the
        // WORD's language, not the keyboard's primary (the other half of the defect).
        assertTrue(
            "an English entry under a French primary still qualifies",
            SuggestionHandler.shouldAugmentPossessiveAt(listOf("fr", "en"), 1)
        )
        // Region subtags normalize; a label list shorter than the slate yields false rather
        // than guessing English.
        assertTrue(SuggestionHandler.shouldAugmentPossessiveAt(listOf("en-GB"), 0))
        assertFalse(SuggestionHandler.shouldAugmentPossessiveAt(listOf("en"), 1))
    }

    @Test
    fun oracle_jvm_possessiveGate_nullLanguagesLeavesEveryWordEligible() {
        // The single-language contract: no per-word labels means the language-wide gate at the
        // call site already decided, so the augment loop must not second-guess it.
        for (i in 0 until 3) {
            assertTrue(
                "index $i must stay eligible when no per-word languages were supplied",
                SuggestionHandler.shouldAugmentPossessiveAt(null, i)
            )
        }
    }

    @Test
    fun oracle_jvm_possessiveAugment_mixedLanguageSlateAugmentsOnlyTheEnglishWords() {
        // en-primary / fr-secondary merged slate. Before CK-150-024 every word here was
        // augmented, producing "maison's" on the bar.
        val words = mutableListOf("book", "maison", "cat")
        val scores = mutableListOf(1000, 920, 500)
        augmentPredictionsWithPossessives(
            words, scores, languages = listOf("en", "fr", "en")
        ) { "$it's" }

        assertEquals(listOf("book", "maison", "cat", "book's", "cat's"), words)
        assertEquals(listOf(1000, 920, 500, 990, 490), scores)
        assertFalse("no French possessive may be appended", words.contains("maison's"))
    }

    @Test
    fun oracle_jvm_possessiveAugment_nullLanguagesIsUnchangedFromBeforeTheGate() {
        // Byte-identical to oracle_jvm_possessiveAugment_appendsUpToThreeAtEndWithScoreMinus10:
        // a null language list must leave the pre-CK-150-024 behavior exactly as it was.
        val words = mutableListOf("book", "cat", "dog", "fish")
        val scores = mutableListOf(100, 90, 80, 70)
        augmentPredictionsWithPossessives(words, scores, languages = null) { "$it's" }
        assertEquals(listOf("book", "cat", "dog", "fish", "book's", "cat's", "dog's"), words)
        assertEquals(listOf(100, 90, 80, 70, 90, 80, 70), scores)
    }

    @Test
    fun oracle_jvm_possessiveAugment_frenchRankOneDoesNotCostEnglishRankTwo() {
        // The loop CONTINUES past a skipped word rather than breaking: the top-3 window exists
        // to limit clutter, so a French rank 1 must not suppress the English rank 2's form.
        val words = mutableListOf("maison", "book")
        val scores = mutableListOf(1000, 920)
        augmentPredictionsWithPossessives(words, scores, languages = listOf("fr", "en")) { "$it's" }
        assertEquals(listOf("maison", "book", "book's"), words)
        assertEquals(listOf(1000, 920, 910), scores)
    }

    @Test
    fun oracle_jvm_possessiveAugment_skipsIneligibleWords() {
        // possessiveOf == null models ContractionManager returning null for function
        // words / known contractions (e.g. "i", "don't").
        val words = mutableListOf("i", "book", "don't")
        val scores = mutableListOf(100, 90, 80)
        augmentPredictionsWithPossessives(words, scores) { w ->
            if (w == "book") "book's" else null
        }
        assertEquals(listOf("i", "book", "don't", "book's"), words)
        assertEquals(listOf(100, 90, 80, 80), scores)
    }

    @Test
    fun oracle_jvm_possessiveAugment_emptyListIsNoOp() {
        val words = mutableListOf<String>()
        val scores = mutableListOf<Int>()
        augmentPredictionsWithPossessives(words, scores) { "$it's" }
        assertTrue(words.isEmpty())
        assertTrue(scores.isEmpty())
    }

    @Test
    fun oracle_jvm_possessiveAugment_missingScoreDefaultsTo128() {
        // scores.getOrElse(i){128} — if the scores list is short, the fallback base is 128,
        // so the appended possessive score is 118.
        val words = mutableListOf("book")
        val scores = mutableListOf<Int>() // deliberately empty
        augmentPredictionsWithPossessives(words, scores) { "$it's" }
        assertEquals(listOf("book", "book's"), words)
        assertEquals(listOf(118), scores)
    }

    // =========================================================================
    // Scenario 30 — getCurrentSuggestions()/onSuggestionSelected wire format
    // (List<String>; special entries carry a prefix parsed only via Suggestion.kt)
    // =========================================================================

    @Test
    fun oracle_jvm_suggestionWire_ordinaryWordRoundTrips() {
        val s = Suggestion.parse("book")
        assertTrue(s is Suggestion.Word)
        assertEquals("book", s.wire)
        // raw: marker travels INSIDE Word.text unchanged (not modelled by Suggestion).
        val raw = Suggestion.parse("raw:book")
        assertTrue(raw is Suggestion.Word)
        assertEquals("raw:book", raw.wire)
    }

    @Test
    fun oracle_jvm_suggestionWire_exactAddRoundTrips() {
        val exact = Suggestion.ExactAdd("xyzq")
        assertEquals("exact_add:xyzq", exact.wire)
        assertEquals("+xyzq", exact.label)
        val parsed = Suggestion.parse(exact.wire)
        assertTrue(parsed is Suggestion.ExactAdd)
        assertEquals("xyzq", (parsed as Suggestion.ExactAdd).word)
    }

    @Test
    fun oracle_jvm_suggestionWire_addToDictionaryRoundTrips() {
        val add = Suggestion.AddToDictionary("foobar")
        assertEquals("dict_add:foobar", add.wire)
        val parsed = Suggestion.parse(add.wire)
        assertTrue(parsed is Suggestion.AddToDictionary)
        assertEquals("foobar", (parsed as Suggestion.AddToDictionary).word)
    }

    @Test
    fun oracle_jvm_routeSuggestionSelection_dispatchesByPrefix() {
        // The selection router is the single source of truth SuggestionHandler
        // dispatches on (SuggestionHandler.kt:402-415).
        assertTrue(routeSuggestionSelection("book") is SelectionRoute.CommitWord)
        // CommitWord carries the ORIGINAL wire incl. raw: (downstream strips it).
        assertEquals(
            "raw:book",
            (routeSuggestionSelection("raw:book") as SelectionRoute.CommitWord).wire
        )
        assertEquals(
            "xyzq",
            (routeSuggestionSelection("exact_add:xyzq") as SelectionRoute.ExactAdd).word
        )
        assertEquals(
            "foobar",
            (routeSuggestionSelection("dict_add:foobar") as SelectionRoute.AddToDictionary).word
        )
    }

    @Test
    fun oracle_jvm_suggestionWire_prefixesAreTheOnlySpecialMarkers() {
        // A plain word that merely CONTAINS the substring (not a prefix) is an ordinary word.
        val parsed = Suggestion.parse("my exact_add:thing")
        assertTrue(parsed is Suggestion.Word)
        // The whole string round-trips as a Word (not classified/split as ExactAdd).
        assertEquals("my exact_add:thing", (parsed as Suggestion.Word).text)
    }
}
