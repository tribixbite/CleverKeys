package tribixbite.cleverkeys.autocorrect

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.WordPredictor
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end JVM tests for `WordPredictor.autoCorrect` against the REAL
 * bundled English dictionary (98k words) + real contraction aliases.
 *
 * # Why a MockK-harness test (runMockTests, not runPureTests)
 *
 * `autoCorrect` calls `android.util.Log` unconditionally, so it cannot run on
 * the pure runner (no android.jar). The mock runner has android.jar stubs and
 * MockK intercepts `Log` before the stubs throw. The `WordPredictor` instance
 * is created via Objenesis (constructor skipped — it would build an
 * `AsyncDictionaryLoader` → `Handler(Looper)` android stub chain) and its
 * private fields are reflection-injected, mirroring `DictionaryManagerTest`.
 *
 * # Dictionary fidelity
 *
 * `en_enhanced.json` stores a byte-compressed rank (134..255, higher = more
 * common); the device's primary path loads `en_enhanced.bin` and converts
 * rank → frequency via `1_000_000 - rank * 3900` (BinaryDictionaryLoader V2).
 * We apply the same conversion (`byte b` ⇔ `rank 255 - b`) so the harness
 * runs on the DEVICE frequency scale — the FrequencyFloor fraction math and
 * frequency tiebreaks behave exactly as on-device. Contraction aliases are
 * injected with the same `dict[apo] ?: dict[bare] ?: 5000` freq rule as
 * `loadContractionKeysAsync`.
 *
 * Covers AC-4 (possessive-typo base correction) and TEST-1 leftovers
 * ("gamees" elongation, capitalized "Hadnr" alias, frequency-floor
 * suppression end-to-end).
 */
class AutoCorrectEndToEndTest {

    companion object {
        private lateinit var predictor: WordPredictor
        private lateinit var config: Config

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            mockkStatic(Log::class)
            every { Log.d(any(), any()) } returns 0
            every { Log.i(any(), any()) } returns 0
            every { Log.w(any(), any<String>()) } returns 0
            every { Log.e(any(), any()) } returns 0
            every { Log.e(any(), any(), any()) } returns 0

            val gson = Gson()
            val mapType = object : TypeToken<Map<String, Double>>() {}.type
            val stringMapType = object : TypeToken<Map<String, String>>() {}.type

            // Real dictionary, converted to the V2-binary runtime freq scale.
            val rawDict: Map<String, Double> = File(
                "src/main/assets/dictionaries/en_enhanced.json"
            ).reader().use { gson.fromJson(it, mapType) }
            val dict = HashMap<String, Int>(rawDict.size * 2)
            for ((word, byteFreq) in rawDict) {
                val rank = 255 - byteFreq.toInt()
                dict[word] = 1_000_000 - rank * 3900
            }

            // Real contraction aliases, mirroring loadContractionKeysAsync:
            // skip real-word bases, inject bare key at the apostrophe form's
            // freq (or existing bare freq, or the 5000 floor).
            val contractions: Map<String, String> = File(
                "src/main/assets/dictionaries/contractions_en.json"
            ).reader().use { gson.fromJson(it, stringMapType) }
            val realWordBases = readCompanionSet("REAL_WORD_CONTRACTION_BASES")
            val aliases = mutableMapOf<String, String>()
            for ((bareRaw, apoRaw) in contractions) {
                val bare = bareRaw.lowercase()
                val apo = apoRaw.lowercase()
                if (bare in realWordBases) continue
                aliases[bare] = apo
                dict[bare] = dict[apo] ?: dict[bare] ?: 5000
            }

            config = org.objenesis.ObjenesisStd().newInstance(Config::class.java)

            predictor = org.objenesis.ObjenesisStd().newInstance(WordPredictor::class.java)
            setField("dictionary", AtomicReference(dict))
            setField("contractionAliases", aliases)
            setField("customAndUserWords", emptySet<String>())
            setField("disabledWords", mutableSetOf<String>())
            setField("config", config)
            setField("cachedMaxFreqForSize", -1)
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            unmockkStatic(Log::class)
        }

        private fun setField(name: String, value: Any?) {
            val field = WordPredictor::class.java.getDeclaredField(name)
            field.isAccessible = true
            field.set(predictor, value)
        }

        @Suppress("UNCHECKED_CAST")
        private fun readCompanionSet(name: String): Set<String> {
            val field = WordPredictor::class.java.getDeclaredField(name)
            field.isAccessible = true
            return field.get(null) as Set<String>
        }
    }

    @Before
    fun resetConfig() {
        // Same knob lockdown as the instrumented AutocorrectTest.
        config.autocorrect_enabled = true
        config.autocorrect_min_word_length = 2
        config.autocorrect_char_match_threshold = 0.65f
        config.autocorrect_max_length_diff = 2
        config.autocorrect_confidence_min_frequency = FrequencyFloor.SLIDER_MIN
        config.autocorrect_prefix_length = 0
        config.swipe_debug_detailed_logging = false
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC-4: possessive-typo — correct the BASE, re-append the suffix
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun possessiveTypo_baseCorrected_suffixPreserved() {
        // "embeer" is a doubled-letter typo of "ember"; the possessive suffix
        // must survive. Pre-AC-4 the full token was swept against the
        // apostrophe-free dictionary and produced "rivers".
        assertEquals("ember's", predictor.autoCorrect("embeer's"))
    }

    @Test
    fun possessiveTypo_typographicApostrophe_preserved() {
        // U+2019 in, U+2019 out — the original apostrophe char is re-appended.
        assertEquals("ember’s", predictor.autoCorrect("embeer’s"))
    }

    @Test
    fun possessiveTypo_pluralPossessive_baseCorrected() {
        // Trailing-apostrophe plural possessive with a doubled-letter base
        // typo: base "embeers" collapses to "embers", suffix "'" preserved.
        assertEquals("embers'", predictor.autoCorrect("embeers'"))
    }

    @Test
    fun possessiveTypo_baseCorrectsViaSweep_tehs() {
        // Chosen behavior for "teh's": the base is corrected exactly as the
        // bare token would be ("teh" → "the") and the suffix is re-appended —
        // "the's". Rule-consistency beats second-guessing whether the user
        // "really" wanted a possessive of "the".
        assertEquals("the's", predictor.autoCorrect("teh's"))
    }

    @Test
    fun possessiveTypo_uncorrectableBase_leftUntouched() {
        // No plausible correction for the base — the token must be returned
        // unchanged, never stripped of its suffix.
        assertEquals("zqjxvqz's", predictor.autoCorrect("zqjxvqz's"))
    }

    @Test
    fun possessiveTypo_capitalizedBase_preservesCase() {
        assertEquals("Ember's", predictor.autoCorrect("Embeer's"))
    }

    @Test
    fun possessive_ofKnownNoun_unchanged_regression() {
        // The original possessive guard (da78b98e2) must keep holding.
        assertEquals("ember's", predictor.autoCorrect("ember's"))
        assertEquals("dog's", predictor.autoCorrect("dog's"))
        assertEquals("rivers'", predictor.autoCorrect("rivers'"))
    }

    // ─────────────────────────────────────────────────────────────────────
    // TEST-1: "gamees" — doubled-letter elongation vs morphology guard
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun elongation_gamees_correctsToGames() {
        // Pre-fix, the morphology guard misread "gamees" as a valid -es
        // inflection of "game" and froze it; without the guard the adjacency
        // sweep preferred the 1-substitution lookalike "gamers". The
        // elongation-collapse step must recognize the doubled 'e' and correct
        // to the exact-letters dictionary word "games".
        assertEquals("games", predictor.autoCorrect("gamees"))
    }

    @Test
    fun elongation_doesNotFire_whenCollapseIsNotAWord() {
        // "broight" has no doubled letter; sweep behavior is untouched.
        assertEquals("brought", predictor.autoCorrect("broight"))
    }

    // ─────────────────────────────────────────────────────────────────────
    // TEST-1: capitalized contraction-alias correction
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun capitalizedAliasTypo_HadnrToHadnt() {
        // Sweep winner is the alias key "hadnt" (t→r adjacent), re-routed to
        // "hadn't"; preserveCapitalization must keep the leading capital.
        assertEquals("Hadn't", predictor.autoCorrect("Hadnr"))
    }

    @Test
    fun lowercaseAliasTypo_hadnrToHadnt_regression() {
        assertEquals("hadn't", predictor.autoCorrect("hadnr"))
    }

    // ─────────────────────────────────────────────────────────────────────
    // TEST-1: frequency-floor strictness — end-to-end suppression
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun frequencyFloor_rareCorrection_succeedsAtDefault_suppressedAtStrict() {
        // "bathmat" is a rare word (json byte 140 → device freq 551,500 —
        // below MAX_STRICTNESS(0.6) × maxFreq(1M) = 600,000). "bathmst" is a
        // single adjacent-key typo (a↔s) of it.
        config.autocorrect_confidence_min_frequency = FrequencyFloor.SLIDER_MIN
        assertEquals(
            "at the default floor the rare correction must apply",
            "bathmat", predictor.autoCorrect("bathmst")
        )

        config.autocorrect_confidence_min_frequency = FrequencyFloor.SLIDER_MAX
        assertEquals(
            "at max slider strictness the rare correction must be suppressed",
            "bathmst", predictor.autoCorrect("bathmst")
        )
    }

    @Test
    fun frequencyFloor_commonCorrection_neverSuppressed() {
        // MAX_STRICTNESS < 1 guarantees top words always clear the floor.
        config.autocorrect_confidence_min_frequency = FrequencyFloor.SLIDER_MAX
        assertEquals("the", predictor.autoCorrect("teh"))
    }
}
