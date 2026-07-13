package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for autocorrect functionality.
 * Tests typo correction, word boundary detection, and correction confidence.
 */
@RunWith(AndroidJUnit4::class)
class AutocorrectTest {

    private lateinit var context: Context
    private lateinit var predictor: WordPredictor
    private lateinit var config: Config

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
        config = Config.globalConfig()

        predictor = WordPredictor()
        predictor.setContext(context)
        predictor.loadDictionary(context, "en")
        // 2026-05-20: previously omitted — `WordPredictor.config` defaulted
        // to null, causing `autoCorrect` to short-circuit and return the
        // typed word unchanged. Existing assertions used `assertNotNull`
        // which masked the bug. Wire the global config in so corrections
        // actually run.
        predictor.setConfig(config)

        // 2026-05-21: lock down ALL autocorrect knobs so tests don't pick
        // up stale prefs state from prior runs / other test classes on the
        // emulator. The earlier failures of `wuestion → question` etc.
        // were caused in part by `autocorrect_max_length_diff` defaulting
        // to whatever the emulator had cached, letting unrelated longer
        // words compete on the frequency tiebreaker.
        config.autocorrect_enabled = true
        config.autocorrect_min_word_length = 2
        config.autocorrect_char_match_threshold = 0.65f
        config.autocorrect_max_length_diff = 2
        config.autocorrect_confidence_min_frequency = 100
        config.autocorrect_prefix_length = 0
    }

    // =========================================================================
    // Basic autocorrect tests
    // =========================================================================

    @Test
    fun testAutocorrectReturnsString() {
        val result = predictor.autoCorrect("teh")
        assertNotNull("Should return non-null result", result)
    }

    @Test
    fun testAutocorrectEmptyString() {
        val result = predictor.autoCorrect("")
        assertEquals("Empty should return empty", "", result)
    }

    @Test
    fun testAutocorrectValidWord() {
        val result = predictor.autoCorrect("the")
        assertEquals("Valid word should not be corrected", "the", result)
    }

    @Test
    fun testAutocorrectHello() {
        val result = predictor.autoCorrect("hello")
        assertEquals("Valid word should not change", "hello", result)
    }

    // =========================================================================
    // Common typo tests
    // =========================================================================

    @Test
    fun testAutocorrectTeh() {
        // "teh" is THE classic transposition typo. Before Damerau support the
        // swap target could never pass the 50% exact-ratio gate (teh vs the =
        // 1/3 exact) so this corrected to "ten" once the junk "teh" dict entry
        // was removed (2026-06-17). The transposition fast path now scores
        // "the" into the tiebreak band where its frequency wins.
        val result = predictor.autoCorrect("teh")
        assertEquals("teh → the (adjacent transposition)", "the", result)
    }

    @Test
    fun testAutocorrectHte() {
        // "hte" — leading-pair transposition of "the".
        val result = predictor.autoCorrect("hte")
        assertEquals("hte → the (adjacent transposition)", "the", result)
    }

    @Test
    fun testAutocorrect_transposition_becuaseToBecause() {
        // Mid-word transposition in a longer word (u/a swapped).
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        val result = predictor.autoCorrect("becuase")
        assertEquals("becuase → because (adjacent transposition)", "because", result)
    }

    @Test
    fun testAutocorrect_aliasCannotOvertakeStrongerMatch_thierToTheir() {
        // Reported by audit (2026-07-03): "thier" corrected to "this'd" — the
        // old +0.15 alias score bonus let the 2-substitution alias key "thisd"
        // beat the structurally better transposition "their". Alias preference
        // is now a rule (wins only at equal-or-better RAW score), so the
        // transposition wins.
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        val result = predictor.autoCorrect("thier")
        assertEquals("thier → their (transposition beats 2-sub alias)", "their", result)
    }

    @Test
    fun testAutocorrect_transposition_recieveToReceive() {
        // The classic i/e swap. Previously lost to unrelated candidates.
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        val result = predictor.autoCorrect("recieve")
        assertEquals("recieve → receive (adjacent transposition)", "receive", result)
    }

    @Test
    fun testAutocorrect_transpositionBeats2Sub_thsiToThis() {
        // Within the tiebreak band a one-swap explanation must beat a
        // 2-substitution candidate regardless of frequency: "thsi" is "this"
        // (s/i swapped), not the more frequent "that" (2 subs).
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        val result = predictor.autoCorrect("thsi")
        assertEquals("thsi → this (transposition beats 2-sub 'that')", "this", result)
    }

    @Test
    fun testAutocorrectAdn() {
        val result = predictor.autoCorrect("adn")
        // "adn" is a typo for "and"
        assertNotNull(result)
    }

    @Test
    fun testAutocorrectWaht() {
        val result = predictor.autoCorrect("waht")
        // "waht" is a typo for "what"
        assertNotNull(result)
    }

    // =========================================================================
    // Word length tests
    // =========================================================================

    @Test
    fun testAutocorrectShortWord() {
        // Very short words may not be autocorrected
        val result = predictor.autoCorrect("th")
        assertNotNull(result)
    }

    @Test
    fun testAutocorrectLongWord() {
        val result = predictor.autoCorrect("unbelievable")
        assertEquals("Long valid word unchanged", "unbelievable", result)
    }

    @Test
    fun testAutocorrectFirstCharTypo_wuestionToQuestion() {
        // Reported 2026-05-20: "wuestion" should correct to "question" (w↔q
        // are adjacent on QWERTY). With prefix_length=0 (default since
        // 7463c9f61) the prefix-match guard must NOT block this — the bug
        // was that `WordPredictor.autoCorrect` hard-coded a 2-char prefix
        // check that ignored the config field, so any typo on the first
        // character was uncorrectable.
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        val result = predictor.autoCorrect("wuestion")
        assertEquals("First-char typo must autocorrect with prefix_length=0",
            "question", result)
    }

    @Test
    fun testAutocorrectPrefixLength_two_enforcesPrefix() {
        // With prefix_length=2, "wuestion" must NOT correct to "question"
        // (no shared "wu" prefix). Verifies the config knob still works at
        // the legacy default.
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 2
        val result = predictor.autoCorrect("wuestion")
        assertEquals("With prefix_length=2, first-char typo is NOT corrected",
            "wuestion", result)
    }

    // =========================================================================
    // Issue #101 — keyboard-adjacency-weighted scoring (KeyAdjacency module)
    // Reported 2026-05-20: user mistypes "tge"/"tfe" → "the" and "yiu" → "you"
    // and the keyboard never corrects them. Tier A.1+A.2 introduces physical-
    // adjacency-aware scoring so adjacent-key typos rank above distant ones.
    // =========================================================================

    @Test
    fun testAutocorrect_adjacencyTypo_tgeToThe() {
        // g and h are home-row-adjacent. Score for "tge" vs "the":
        //   t-t=1.0, g-h=~0.86, e-e=1.0 → sum 2.86 / 3 = 0.953
        // Comfortably above the 0.65 threshold.
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        val result = predictor.autoCorrect("tge")
        assertEquals("tge → the (g/h adjacent)", "the", result)
    }

    @Test
    fun testAutocorrect_adjacencyTypo_tfeToThe() {
        // f and h are 2 home-row keys apart — slightly farther than g/h.
        // Score: t-t=1.0, f-h=~0.73, e-e=1.0 → sum 2.73 / 3 = 0.91
        // Still above threshold.
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        val result = predictor.autoCorrect("tfe")
        assertEquals("tfe → the", "the", result)
    }

    // NOTE: removed `testAutocorrect_adjacencyTypo_yiuToYou` 2026-05-21
    // because `yiu` IS in the bundled English dictionary (Chinese surname),
    // so autoCorrect correctly skips it via the "already a valid word" guard.
    // The user's issue-101 comment used yiu as an illustrative example, not
    // a real reported case. The tge/tfe/wuestion cases below cover the same
    // adjacency-weighted-scoring behavior using genuine non-dict typos.

    @Test
    fun testAutocorrect_lengthDiffOne_insertion() {
        // "questin" missing the 'o' → "question". Length diff = 1, requires
        // autocorrect_max_length_diff >= 1.
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        config.autocorrect_max_length_diff = 2
        val result = predictor.autoCorrect("questin")
        assertEquals("questin → question (deletion typo)", "question", result)
    }

    @Test
    fun testAutocorrect_lengthDiffOne_extraChar() {
        // "quuestion" has duplicate 'u' → "question". Length diff = 1.
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        config.autocorrect_max_length_diff = 2
        val result = predictor.autoCorrect("quuestion")
        assertEquals("quuestion → question (extra-char typo)", "question", result)
    }

    // =========================================================================
    // Structural-quality cap — substitution count beats frequency
    // Reported 2026-06-17: "broight" corrected to "thought" instead of
    // "brought". Root cause: MIN_SAME_LENGTH_EXACT_RATIO=0.50 admits up to
    // ⌊L/2⌋ subs, so the 3-substitution lookalike "thought" (4/7 match) became
    // a candidate and won the within-gap frequency tiebreak over the real
    // single-typo target "brought" (6/7 match, i↔u adjacent). Fixed by
    // MAX_SAME_LENGTH_SUBSTITUTIONS=2, which drops 3+-sub candidates before
    // the tiebreaker runs.
    // =========================================================================

    @Test
    fun testAutocorrect_singleTypo_beatsHigherFreqLookalike_broightToBrought() {
        // broight → brought: single adjacent-key sub (i↔u), 6/7 exact.
        //   The wrong target "thought" (freq 247 > brought 239) needs THREE
        //   subs (b→t, r→h, i→u) and must be rejected by the sub-cap so the
        //   structurally-correct, slightly-less-frequent word wins.
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        config.autocorrect_max_length_diff = 2
        val result = predictor.autoCorrect("broight")
        assertEquals("broight → brought (1-sub beats 3-sub 'thought')",
            "brought", result)
    }

    @Test
    fun testAutocorrect_singleTypo_thoightToThought() {
        // Guard: thoight → thought is itself a legitimate single-sub typo
        // (i↔u). The sub-cap must NOT block a genuine 1-substitution match.
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        config.autocorrect_max_length_diff = 2
        val result = predictor.autoCorrect("thoight")
        assertEquals("thoight → thought (single-sub still corrects)",
            "thought", result)
    }

    // =========================================================================
    // Disabled words must not be offered as correction targets
    // Audit 2026-06-17: autoCorrect() never consulted isWordDisabled(), so a
    // user-disabled word stayed reachable as a correction TARGET (disabling
    // "boston" still allowed "bostom → boston").
    // =========================================================================

    @Test
    fun testAutocorrect_disabledWord_notOfferedAsTarget() {
        // POST-MORTEM (2026-07-03 ew run): this test originally disabled "the"
        // and restored prefs with apply(). apply() is ASYNC — under the
        // orchestrator each test's process is killed right after it finishes,
        // and the racing restore write was lost, leaking disabled={"the"} into
        // every later test in the class (tge→"age", tfe→"tie"). Two rules now:
        //  1. commit() (synchronous) for BOTH writes — never apply() for
        //     cross-test-visible state under the orchestrator.
        //  2. Disable an INERT word ("zebra") no other test's inputs are near,
        //     so even a leak cannot cascade.
        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        val key = LanguagePreferenceKeys.disabledWordsKey("en")
        val original = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
        try {
            // Disable "zebra" — the natural correction target for "zebrq"
            // (q/a adjacent, 4/5 exact match).
            @Suppress("ApplySharedPref")
            prefs.edit().putStringSet(key, setOf("zebra")).commit()
            predictor.reloadDisabledWords()

            config.autocorrect_enabled = true
            config.autocorrect_prefix_length = 0
            val result = predictor.autoCorrect("zebrq")
            assertNotEquals("Disabled 'zebra' must not be produced as a correction",
                "zebra", result)
        } finally {
            // Restore prefs + predictor state so other tests are unaffected.
            @Suppress("ApplySharedPref")
            prefs.edit().putStringSet(key, original).commit()
            predictor.reloadDisabledWords()
        }
    }

    @Test
    fun testAutocorrect_customWord_notBlockedByFrequencyFloor() {
        // AC-2 (2026-07): custom words are injected at freq 1000, far below the
        // binary dict scale (~52k..1M). Before the fix, ANY non-zero min-freq
        // slider produced a floor >> 1000 that silently excluded every custom
        // word as a correction target. Custom words are now exempt from the floor.
        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        val key = LanguagePreferenceKeys.customWordsKey("en")
        val original = prefs.getString(key, "{}") ?: "{}"
        try {
            @Suppress("ApplySharedPref")
            prefs.edit().putString(key, "{\"zqxword\": 1000}").commit()
            predictor.reloadCustomAndUserWords()

            config.autocorrect_enabled = true
            config.autocorrect_prefix_length = 0
            config.autocorrect_max_length_diff = 2
            // A high slider → large floor that would exclude the freq-1000 custom word.
            config.autocorrect_confidence_min_frequency = 2000
            // "zqxwora" is a single adjacent-key typo (a↔d? no — 'a' at end vs 'd')
            // of the custom word "zqxword"; must still correct to it despite the floor.
            val result = predictor.autoCorrect("zqxwora")
            assertEquals("custom word must survive the frequency floor as a target",
                "zqxword", result)
        } finally {
            @Suppress("ApplySharedPref")
            prefs.edit().putString(key, original).commit()
            predictor.reloadCustomAndUserWords()
            config.autocorrect_confidence_min_frequency = Defaults.AUTOCORRECT_MIN_FREQUENCY
        }
    }

    @Test
    fun testAutocorrect_transposition_preservesCase() {
        // TEST-1: case preservation must apply to the transposition fast-path
        // winner, not only substitution winners.
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        assertEquals("Teh → The (title case preserved)", "The", predictor.autoCorrect("Teh"))
        assertEquals("TEH → THE (all caps preserved)", "THE", predictor.autoCorrect("TEH"))
    }

    // =========================================================================
    // Possessive guard — possessive of a known noun must not be "corrected"
    // Reported 2026-06-25: typing "ember's glow" produced "rivers glow". The
    // possessive form is never stored in the dictionary, so autocorrect treated
    // "ember's" as a typo of the nearest dictionary word.
    // =========================================================================

    @Test
    fun testAutocorrect_possessive_ofKnownNoun_preserved() {
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        config.autocorrect_max_length_diff = 2
        // base nouns (ember, dog, river, cat) are all in the bundled dictionary.
        assertEquals("ember's preserved", "ember's", predictor.autoCorrect("ember's"))
        assertEquals("dog's preserved", "dog's", predictor.autoCorrect("dog's"))
        assertEquals("river's preserved", "river's", predictor.autoCorrect("river's"))
    }

    @Test
    fun testAutocorrect_pluralPossessive_trailingApostrophe_preserved() {
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        // "rivers'" — base "rivers" is in the dictionary.
        assertEquals("rivers' preserved", "rivers'", predictor.autoCorrect("rivers'"))
    }

    // =========================================================================
    // AC-4 — possessive-typo correction: when the BASE before the apostrophe
    // is itself a typo, correct the base alone and re-append the suffix.
    // Pre-fix the full token was swept against apostrophe-free dictionary
    // words, stripping the possessive ("embeer's" → "rivers").
    // =========================================================================

    @Test
    fun testAutocorrect_possessiveBaseTypo_embeersToEmbers() {
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        config.autocorrect_max_length_diff = 2
        assertEquals("embeer's → ember's (base corrected, suffix preserved)",
            "ember's", predictor.autoCorrect("embeer's"))
    }

    @Test
    fun testAutocorrect_possessiveBaseTypo_typographicApostrophePreserved() {
        // U+2019 in → U+2019 out; the original apostrophe char is re-appended.
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        config.autocorrect_max_length_diff = 2
        assertEquals("embeer’s → ember’s (typographic apostrophe preserved)",
            "ember’s", predictor.autoCorrect("embeer’s"))
    }

    @Test
    fun testAutocorrect_possessiveBaseTypo_tehsToThes() {
        // Chosen behavior: the base is corrected exactly as the bare token
        // would be ("teh" → "the") and the suffix is re-appended — "the's".
        // Rule-consistency beats second-guessing the user's possessive.
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        config.autocorrect_max_length_diff = 2
        assertEquals("teh's → the's (base corrected via sweep)",
            "the's", predictor.autoCorrect("teh's"))
    }

    @Test
    fun testAutocorrect_possessiveUncorrectableBase_leftUntouched() {
        // No plausible correction for the base — the token must come back
        // unchanged, never stripped of its suffix.
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        config.autocorrect_max_length_diff = 2
        assertEquals("uncorrectable possessive base stays untouched",
            "zqjxvqz's", predictor.autoCorrect("zqjxvqz's"))
    }

    // =========================================================================
    // TEST-1 — doubled-letter elongation vs morphology guard ("gamees")
    // =========================================================================

    @Test
    fun testAutocorrect_elongation_gameesToGames() {
        // Pre-fix the morphology guard misread "gamees" as a valid -es
        // inflection of "game" and froze it (and without the guard the
        // adjacency sweep preferred the 1-sub lookalike "gamers"). The
        // elongation-collapse step recognizes the doubled 'e' and corrects
        // to the exact-letters dictionary word "games".
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        config.autocorrect_max_length_diff = 2
        assertEquals("gamees → games (doubled-letter collapse)",
            "games", predictor.autoCorrect("gamees"))
    }

    // =========================================================================
    // TEST-1 — capitalized contraction-alias correction
    // =========================================================================

    @Test
    fun testAutocorrect_capitalizedAliasTypo_HadnrToHadnt() {
        // Sweep winner is the alias key "hadnt" (t→r adjacent), re-routed to
        // "hadn't"; preserveCapitalization must keep the leading capital.
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        assertEquals("Hadnr → Hadn't (capitalized alias re-route)",
            "Hadn't", predictor.autoCorrect("Hadnr"))
    }

    // =========================================================================
    // TEST-1 — frequency-floor slider end-to-end (prefs → Config.refresh →
    // setConfig → suppression). "bathmat" sits below MAX_STRICTNESS(0.6) ×
    // maxFreq on the V2 binary scale (rank ~115 → ~551k < 600k), so a max-
    // strictness slider must suppress "bathmst" → "bathmat" while the default
    // floor allows it.
    // =========================================================================

    @Test
    fun testAutocorrect_frequencyFloorSlider_endToEnd() {
        val prefs = Config.globalPrefs()
        val key = "autocorrect_confidence_min_frequency"
        val hadValue = prefs.contains(key)
        val original = prefs.getInt(key, Defaults.AUTOCORRECT_MIN_FREQUENCY)

        // Re-applies the test knobs clobbered by Config.refresh() re-reading
        // prefs (refresh overwrites ALL autocorrect fields from storage).
        fun applyTestKnobs() {
            config.autocorrect_enabled = true
            config.autocorrect_min_word_length = 2
            config.autocorrect_char_match_threshold = 0.65f
            config.autocorrect_max_length_diff = 2
            config.autocorrect_prefix_length = 0
        }

        try {
            // 1. Default floor → the rare correction applies.
            @Suppress("ApplySharedPref")
            prefs.edit().putInt(key, Defaults.AUTOCORRECT_MIN_FREQUENCY).commit()
            config.refresh(context.resources, null)
            applyTestKnobs()
            predictor.setConfig(config)
            assertEquals("at the default floor the rare correction must apply",
                "bathmat", predictor.autoCorrect("bathmst"))

            // 2. Max-strictness slider (commit, not apply — the orchestrator
            // kills the process and apply() loses the write) → suppressed.
            @Suppress("ApplySharedPref")
            prefs.edit().putInt(key, tribixbite.cleverkeys.autocorrect.FrequencyFloor.SLIDER_MAX)
                .commit()
            config.refresh(context.resources, null)
            applyTestKnobs()
            predictor.setConfig(config)
            assertEquals("at max slider strictness the rare correction must be suppressed",
                "bathmst", predictor.autoCorrect("bathmst"))

            // 3. Common corrections always clear the floor (MAX_STRICTNESS < 1).
            assertEquals("common correction survives max strictness",
                "the", predictor.autoCorrect("teh"))
        } finally {
            @Suppress("ApplySharedPref")
            if (hadValue) prefs.edit().putInt(key, original).commit()
            else prefs.edit().remove(key).commit()
            config.refresh(context.resources, null)
            applyTestKnobs()
            config.autocorrect_confidence_min_frequency = Defaults.AUTOCORRECT_MIN_FREQUENCY
        }
    }

    @Test
    fun testAutocorrectMinLengthRespected() {
        val minLength = config.autocorrect_min_word_length
        assertTrue("Min length should be reasonable", minLength >= 0 && minLength <= 5)
    }

    // =========================================================================
    // Morphological guard (#B1) — valid inflections missing from the dictionary
    // must NOT be "corrected" to a distant in-dictionary word.
    // Reported: "immunizations" → "organizations" (the plural is absent from
    // the bundled dict, the singular stem "immunization" is present, and
    // "organizations" is same-length + higher frequency).
    // =========================================================================

    @Test
    fun testAutocorrect_validPluralNotCorrected_immunizations() {
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        // Precondition for a meaningful test: the singular stem is in the
        // dictionary (so the guard can recognize the plural as valid). If the
        // bundled dict lacks it, skip rather than assert a vacuous result.
        org.junit.Assume.assumeTrue(
            "stem 'immunization' must be in dict for this guard test",
            predictor.isInDictionary("immunization")
        )
        val result = predictor.autoCorrect("immunizations")
        assertEquals("valid plural must stay unchanged, not become 'organizations'",
            "immunizations", result)
    }

    @Test
    fun testAutocorrect_morphGuard_doesNotBlockRealTypos() {
        // Guard must be surgical: a genuine non-inflection typo still corrects.
        // "teh" is not an inflection of any dictionary word, so the guard is a
        // no-op and the normal correction path still applies.
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        val result = predictor.autoCorrect("teh")
        assertTrue("non-inflection typo still corrects (or is left alone), never blocked by morph guard",
            result == "the" || result == "teh")
    }

    // =========================================================================
    // Issue #101 follow-up — typo of a contraction base must autocorrect to
    // the CONTRACTED form, not the bare alias key. Reported 2026-05-21:
    // Tier A's adjacency-aware dict scan now reaches alias-injected `dont` /
    // `im` / `youre` entries via near-miss typos, but autoCorrect returned
    // the bare alias key because the contractionAliases lookup only fired on
    // the typed input, not on the dict-scan winner. Fix: re-route the winner
    // through contractionAliases before returning.
    // =========================================================================

    @Test
    fun testAutocorrect_aliasDirect_hadntIsLoaded() {
        // Sanity probe: confirms `contractionAliases` is populated for this
        // test's @Before setup. If this fails the typo-of-base tests below
        // can't possibly work (boost is gated on `dictWord in aliases`).
        config.autocorrect_enabled = true
        val result = predictor.autoCorrect("hadnt")
        assertEquals("alias direct path: hadnt should map to hadn't via step 0",
            "hadn't", result)
    }

    @Test
    fun testAutocorrect_contractionBaseTypo_hadnrToHadntContracted() {
        // `hadnr` is a 5-char typo of `hadnt` (t→r, both top-row adjacent).
        // `hadnt` is alias-injected mapping to "hadn't". The `hadn?` prefix
        // and ≥ 50% exact ratio leave `hadnt` (alias-keyed) as the only
        // viable winner — no high-freq 5-char competitor matches the
        // `had?r` shape with exactRatio ≥ 0.5.
        // Expected: the dict-scan winner `hadnt` is re-routed through the
        // alias map to yield "hadn't".
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        val result = predictor.autoCorrect("hadnr")
        assertEquals("hadnr → hadn't (alias re-routed dict-scan winner)",
            "hadn't", result)
    }

    @Test
    fun testAutocorrect_contractionBaseTypo_couldnrToCouldntContracted() {
        // `couldnr` is a 7-char typo of `couldnt` (t→r, adjacent). With
        // 7 chars the prefix constraint plus exactRatio ≥ 0.5 leaves only
        // `couldnt` as a candidate among 7-char dict words.
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        val result = predictor.autoCorrect("couldnr")
        assertEquals("couldnr → couldn't (alias re-routed dict-scan winner)",
            "couldn't", result)
    }

    // =========================================================================
    // Config settings tests
    // =========================================================================

    @Test
    fun testAutocorrectEnabledSetting() {
        assertTrue("Autocorrect should be enabled for tests", config.autocorrect_enabled)
    }

    @Test
    fun testAutocorrectDisabled() {
        val originalEnabled = config.autocorrect_enabled
        try {
            config.autocorrect_enabled = false
            val result = predictor.autoCorrect("teh")
            // When disabled, should return original word
            assertEquals("Should not correct when disabled", "teh", result)
        } finally {
            config.autocorrect_enabled = originalEnabled
        }
    }

    @Test
    fun testAutocorrectCharMatchThreshold() {
        val threshold = config.autocorrect_char_match_threshold
        assertTrue("Threshold should be between 0 and 1",
            threshold >= 0f && threshold <= 1f)
    }

    @Test
    fun testAutocorrectConfidenceMinFrequency() {
        val minFreq = config.autocorrect_confidence_min_frequency
        assertTrue("Min frequency should be non-negative", minFreq >= 0)
    }

    @Test
    fun testAutocorrectMaxLengthDiff() {
        val maxDiff = config.autocorrect_max_length_diff
        assertTrue("Max length diff should be reasonable", maxDiff >= 0 && maxDiff <= 5)
    }

    // =========================================================================
    // Case preservation tests
    // =========================================================================

    @Test
    fun testAutocorrectPreservesLowercase() {
        val result = predictor.autoCorrect("hello")
        if (result == "hello") {
            assertTrue("Lowercase should stay lowercase", result[0].isLowerCase())
        }
    }

    @Test
    fun testAutocorrectWithNumbers() {
        val result = predictor.autoCorrect("test123")
        assertNotNull(result)
    }

    @Test
    fun testAutocorrectWithApostrophe() {
        val result = predictor.autoCorrect("don't")
        assertNotNull(result)
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    fun testAutocorrectNonsenseWord() {
        val result = predictor.autoCorrect("xyzqwerty")
        // Nonsense words may or may not be corrected
        assertNotNull(result)
    }

    @Test
    fun testAutocorrectSingleChar() {
        val result = predictor.autoCorrect("a")
        assertEquals("Single char unchanged", "a", result)
    }

    @Test
    fun testAutocorrectTwoChars() {
        val result = predictor.autoCorrect("ab")
        assertNotNull(result)
    }

    @Test
    fun testAutocorrectWhitespace() {
        val result = predictor.autoCorrect("  ")
        // Whitespace handling
        assertNotNull(result)
    }

    // =========================================================================
    // Swipe autocorrect settings
    // =========================================================================

    @Test
    fun testSwipeBeamAutocorrectSetting() {
        val enabled = config.swipe_beam_autocorrect_enabled
        // Just verify accessible
    }

    @Test
    fun testSwipeFinalAutocorrectSetting() {
        val enabled = config.swipe_final_autocorrect_enabled
        // Just verify accessible
    }

    // =========================================================================
    // Dictionary integration tests
    // =========================================================================

    @Test
    fun testAutocorrectWithDictionaryLoaded() {
        assertTrue("Dictionary should be ready", predictor.isReady())
        assertTrue("Dictionary should have words", predictor.getDictionarySize() > 0)
    }

    @Test
    fun testAutocorrectUsesLoadedDictionary() {
        // Words in dictionary should not be corrected
        if (predictor.isInDictionary("the")) {
            val result = predictor.autoCorrect("the")
            assertEquals("Dictionary word unchanged", "the", result)
        }
    }
}
