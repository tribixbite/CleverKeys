package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.JsonParser
import org.junit.BeforeClass
import org.junit.Test
import tribixbite.cleverkeys.swipe.ctc.CtcAzProjection
import tribixbite.cleverkeys.swipe.ctc.CtcCkdtLexicon
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconMerge
import tribixbite.cleverkeys.swipe.geometric.CkdtDictionaryReader
import java.io.File

/**
 * The swipe engines must not put ENGLISH morphology into a non-English slate
 * (`SwipeContractionPolicy` — code-switching is a bug, not a feature).
 *
 * ### The bug this pins
 *
 * Both swipe adapters used to load the bundled ENGLISH contraction base for EVERY language
 * before the active language's file. `CtcMultiLanguageInstrumentedTest` caught the
 * consequence on device (2026-08-16): a `fr` decode of the real French word `franco` also
 * offered the English possessive `franco's`, whose base `francos` is (correctly) absent from
 * the 37,949-word French lexicon — so it could not have come from the beam.
 *
 * The injection route is `contraction_pairings.json` (1,744 English bases, 1,178 of them
 * possessives keyed on the bare noun) and `contractions.bin` (1,183 paired display forms,
 * 1,116 possessives). [ContractionOverlay]'s real-word ordinal guard could NOT contain it:
 * the PAIRED rule fires before the guard is consulted (`franco` ranks 2,937 in French, well
 * past `REAL_WORD_ORDINAL_MAX`, and was injected anyway).
 *
 * ### What is asserted
 *
 * The tests run the REAL [ContractionOverlay] over the REAL shipped assets — the bundled
 * English contraction files, each language's `contractions_<lang>.json`, and the frequency
 * ordinals derived from the bundled CKDT dictionaries through the SAME chain the CTC adapter
 * uses ([CkdtDictionaryReader] → [CtcCkdtLexicon] → [CtcLexiconMerge.ordinals]) — under both
 * the PRE-FIX and the SHIPPED loading policies, so the leak and its absence are both
 * demonstrated on production data.
 *
 * The WIRING (that both adapters actually load through
 * `ContractionManager.loadSwipeDisplayMappings`) is pinned by
 * `CoreImeHygieneDriftTest.swipeAdaptersScopeContractionsToTheActiveLanguage`; the loader's
 * real behavior over a real `AssetManager` is covered by `ContractionManagerTest`
 * (instrumented) and the end-to-end slate by `CtcMultiLanguageInstrumentedTest`.
 *
 * The pure-test CWD is the project root, so the shipped assets are reachable by relative
 * `File` paths (same convention as `CtcContractionDisplayTest`).
 */
class SwipeContractionLanguageIsolationTest {

    /** The two contraction-loading policies, as executed by the swipe adapters. */
    private enum class Policy {
        /** Pre-2026-08-16: bundled English base FIRST, then the active language's file. */
        PRE_FIX_ENGLISH_BASE,

        /** Shipped: whatever [SwipeContractionPolicy] says for the active language. */
        SHIPPED,
    }

    /** The two lookup tables [ContractionOverlay] consults, for one (language, policy). */
    private class Mappings(
        val nonPaired: Map<String, String>,
        val paired: Map<String, List<String>>,
    )

    private companion object {
        const val DICT_DIR = "src/main/assets/dictionaries"

        /** Languages with a bundled CKDT dictionary AND a bundled contraction file. */
        val ORDINAL_LANGUAGES = listOf("fr", "de", "es", "it")

        /** Bundled contraction files that are literally `{}` — no contractions at all. */
        val EMPTY_CONTRACTION_FILES = listOf("es", "pt", "sv", "id", "ms", "tl", "sw")

        /** `contractions_non_paired.json`, the JSON twin of `contractions.bin`'s section. */
        lateinit var englishNonPaired: Map<String, String>

        /** `contraction_pairings.json` — base word → English contraction/possessive forms. */
        lateinit var englishPaired: Map<String, List<String>>

        /** language → `contractions_<lang>.json` (REPLACE mode). */
        lateinit var languageFiles: Map<String, Map<String, String>>

        /**
         * language → `contraction_pairs_<lang>.json` (APPEND mode) — the keys that ARE words
         * of the language, so the overlay keeps them and merely offers the elision too
         * (`lune` + `l'une`). Empty for a language that ships no such file.
         */
        lateinit var languagePairFiles: Map<String, Map<String, List<String>>>

        /** language → lowercase word → frequency ordinal over the bundled CKDT lexicon. */
        lateinit var ordinals: Map<String, HashMap<String, Int>>

        /** language → the a–z surfaces the beam can emit for it (projected lexicon). */
        lateinit var lexicons: Map<String, Set<String>>

        private fun jsonObject(name: String): Map<String, String> {
            val file = File("$DICT_DIR/$name")
            check(file.isFile) { "expected shipped asset at ${file.path} (run from project root)" }
            val root = JsonParser.parseString(file.readText()).asJsonObject
            val out = LinkedHashMap<String, String>(root.size() * 2)
            for ((key, value) in root.entrySet()) out[key.lowercase()] = value.asString.lowercase()
            return out
        }

        @JvmStatic
        @BeforeClass
        fun loadShippedAssets() {
            englishNonPaired = jsonObject("contractions_non_paired.json")

            val pairingsRoot = JsonParser
                .parseString(File("$DICT_DIR/contraction_pairings.json").readText()).asJsonObject
            val paired = LinkedHashMap<String, List<String>>(pairingsRoot.size() * 2)
            for ((base, variants) in pairingsRoot.entrySet()) {
                paired[base.lowercase()] = variants.asJsonArray.map {
                    it.asJsonObject["contraction"].asString.lowercase()
                }
            }
            englishPaired = paired

            val languages = (ORDINAL_LANGUAGES + EMPTY_CONTRACTION_FILES + "nl" + "en").distinct()
            languageFiles = languages.associateWith { jsonObject("contractions_$it.json") }
            languagePairFiles = languages.associateWith { language ->
                val file = File("$DICT_DIR/contraction_pairs_$language.json")
                if (!file.isFile) return@associateWith emptyMap<String, List<String>>()
                val root = JsonParser.parseString(file.readText()).asJsonObject
                val out = LinkedHashMap<String, List<String>>(root.size() * 2)
                for ((base, variants) in root.entrySet()) {
                    out[base.lowercase()] = variants.asJsonArray.map { it.asString.lowercase() }
                }
                out
            }

            val ord = HashMap<String, HashMap<String, Int>>()
            val lex = HashMap<String, Set<String>>()
            for (language in ORDINAL_LANGUAGES) {
                val bin = File("$DICT_DIR/${language}_enhanced.bin")
                check(bin.isFile) { "expected shipped lexicon at ${bin.path}" }
                val entries = bin.inputStream().use { CkdtDictionaryReader.readEntries(it) }
                val merged = CtcLexiconMerge.merge(
                    CtcCkdtLexicon.frequencyPairs(entries), emptyList(), emptySet()
                )
                ord[language] = CtcLexiconMerge.ordinals(merged)
                lex[language] = CtcAzProjection.projectLexicon(merged).freqs.keys.toSet()
            }
            ordinals = ord
            lexicons = lex
        }
    }

    // ── the two policies, over the real assets ──────────────────────────────────────

    /**
     * The English base as `ContractionManager.loadMappings` leaves it: the non-paired file
     * MINUS the bases that are also paired (the manager reclassifies those — a paired base
     * is a real word and must never be REPLACED by its contraction), plus the pairings file.
     */
    private fun englishBase(): Mappings {
        val pairedBases = englishPaired.keys
        return Mappings(
            nonPaired = englishNonPaired.filterKeys { it !in pairedBases },
            paired = englishPaired,
        )
    }

    /** The mappings a swipe decoding [language] sees under [policy]. */
    private fun mappingsFor(language: String, policy: Policy): Mappings {
        val languageFile = languageFiles[language] ?: emptyMap()
        val languagePairs = languagePairFiles[language] ?: emptyMap()
        val english = policy == Policy.PRE_FIX_ENGLISH_BASE ||
            SwipeContractionPolicy.usesEnglishBase(language)
        // `ContractionManager.loadLanguageContractions` loads BOTH of the language's files:
        // the REPLACE map and the APPEND map. Only English additionally keeps the bundled
        // base below them (SwipeContractionPolicy).
        if (!english) return Mappings(nonPaired = languageFile, paired = languagePairs)
        // EARLIER-WINS: `loadContractionsFromStream` skips any key already mapped, so the
        // English base shadows the active language's file. That is the pre-fix order, and
        // (for English itself) still the shipped one.
        val base = englishBase()
        val merged = LinkedHashMap(base.nonPaired)
        for ((key, value) in languageFile) merged.putIfAbsent(key, value)
        return Mappings(nonPaired = merged, paired = base.paired)
    }

    private fun overlay(
        language: String,
        policy: Policy,
        words: List<String>,
        scores: List<Int> = words.indices.map { 900 - it * 10 },
    ): List<String> {
        val mappings = mappingsFor(language, policy)
        val ranks = ordinals[language] ?: HashMap()
        return ContractionOverlay.apply(
            words = words,
            scores = scores,
            pairedVariants = { mappings.paired[it] },
            nonPairedMapping = { mappings.nonPaired[it] },
            wordOrdinal = { ranks[it] },
        ).first
    }

    // ── 1. the policy itself ────────────────────────────────────────────────────────

    @Test
    fun `the English base is gated on the ACTIVE decode language`() {
        for (english in listOf("en", "EN", "en-GB", "en_US", "en-us")) {
            assertWithMessage("'$english' must keep the bundled English base (unchanged path)")
                .that(SwipeContractionPolicy.usesEnglishBase(english)).isTrue()
        }
        for (other in listOf("fr", "de", "es", "it", "pt", "sv", "nl", "fr-CA", "de_AT", "xx")) {
            assertWithMessage(
                "'$other' must NOT load the English contraction base — English morphology " +
                    "may not bleed into a sentence typed in another language"
            ).that(SwipeContractionPolicy.usesEnglishBase(other)).isFalse()
        }
        // A blank/absent code means "we do not know the language yet", not a deliberate
        // non-English selection: keep the pre-fix behavior (en is the app default).
        assertThat(SwipeContractionPolicy.usesEnglishBase(null)).isTrue()
        assertThat(SwipeContractionPolicy.usesEnglishBase("")).isTrue()
        assertThat(SwipeContractionPolicy.usesEnglishBase("  ")).isTrue()

        assertThat(SwipeContractionPolicy.baseSubtag("fr-CA")).isEqualTo("fr")
        assertThat(SwipeContractionPolicy.baseSubtag("PT_BR")).isEqualTo("pt")
        assertThat(SwipeContractionPolicy.baseSubtag(null)).isEmpty()
    }

    // ── 2. the regression ───────────────────────────────────────────────────────────

    @Test
    fun `a French slate no longer offers the English possessive of a French word`() {
        val slate = listOf("franco", "france", "franc")
        for (word in slate) {
            assertWithMessage("fixture word '$word' must be a real French lexicon word")
                .that(lexicons.getValue("fr")).contains(word)
        }

        val leaked = overlay("fr", Policy.PRE_FIX_ENGLISH_BASE, slate)
        assertWithMessage(
            "characterization of the reported bug: with the English base loaded, a French " +
                "slate gains English possessives whose bases are not French words"
        ).that(leaked).containsAtLeast("franco's", "france's")
        assertWithMessage("the injected possessive's base is NOT a French word")
            .that(lexicons.getValue("fr")).doesNotContain("francos")

        // The real-word ordinal guard could never have contained this: the PAIRED rule
        // fires before the guard is consulted, whatever the base's rank.
        assertThat(ordinals.getValue("fr")["franco"]!!)
            .isGreaterThan(ContractionOverlay.REAL_WORD_ORDINAL_MAX)

        val fixed = overlay("fr", Policy.SHIPPED, slate)
        assertWithMessage(
            "SHIPPED policy: a French slate of French words must come back untouched — no " +
                "English contraction or possessive variant may be appended"
        ).that(fixed).containsExactlyElementsIn(slate).inOrder()
    }

    @Test
    fun `the same leak is gone for German and Spanish`() {
        // "franco" is a word in all three bundled lexicons, so the pairing matched everywhere.
        for (language in listOf("de", "es")) {
            assertWithMessage("'franco' must be a $language lexicon word for this fixture")
                .that(lexicons.getValue(language)).contains("franco")
            assertThat(overlay(language, Policy.PRE_FIX_ENGLISH_BASE, listOf("franco")))
                .contains("franco's")
            assertWithMessage("$language: no English possessive may survive the fix")
                .that(overlay(language, Policy.SHIPPED, listOf("franco")))
                .containsExactly("franco")
        }
    }

    @Test
    fun `English non-paired aliases no longer rewrite words of another language`() {
        // "im" is the 16th most common GERMAN word and an English alias for "i'm"; "dont"
        // is the 104th most common FRENCH word and an English alias for "don't". Before the
        // fix only ContractionOverlay's ordinal guard kept them from being REPLACED, and
        // they still gained an English variant. Now they are not mapped at all.
        assertThat(englishNonPaired).containsKey("im")
        assertThat(englishNonPaired).containsKey("dont")
        assertThat(ordinals.getValue("de")["im"]!!)
            .isLessThan(ContractionOverlay.REAL_WORD_ORDINAL_MAX)
        assertThat(ordinals.getValue("fr")["dont"]!!)
            .isLessThan(ContractionOverlay.REAL_WORD_ORDINAL_MAX)

        assertThat(overlay("de", Policy.PRE_FIX_ENGLISH_BASE, listOf("im"))).contains("i'm")
        assertThat(overlay("fr", Policy.PRE_FIX_ENGLISH_BASE, listOf("dont"))).contains("don't")

        assertThat(overlay("de", Policy.SHIPPED, listOf("im"))).containsExactly("im")
        assertThat(overlay("fr", Policy.SHIPPED, listOf("dont"))).containsExactly("dont")
    }

    @Test
    fun `every word a non-English slate carries stays inside that language's lexicon`() {
        // The invariant CtcMultiLanguageInstrumentedTest.assertSlateIsCanonical asserts on
        // device: every displayed word projects back to a beam-reachable lexicon surface.
        val slates = mapOf(
            "fr" to listOf("franco", "cest", "jai", "dont", "la", "quil", "dor", "france"),
            "de" to listOf("franco", "im", "dor", "destaing", "und", "gehts", "gibts", "wenns"),
            "es" to listOf("franco", "como", "del"),
        )
        for ((language, slate) in slates) {
            val lexicon = lexicons.getValue(language)
            for (word in slate) {
                assertWithMessage("fixture: '$word' must be a $language lexicon word")
                    .that(lexicon).contains(word)
            }
            for (word in overlay(language, Policy.SHIPPED, slate)) {
                val surface = CtcAzProjection.project(word)
                assertWithMessage("$language: '$word' has no a–z surface").that(surface).isNotNull()
                assertWithMessage(
                    "$language: '$word' projects to '$surface', which is not in the lexicon — " +
                        "the display overlay invented a word"
                ).that(lexicon).contains(surface!!)
            }
        }
    }

    // ── 3. the risk of the change: in-language contractions must SURVIVE ────────────

    @Test
    fun `French contractions still fire under the shipped policy`() {
        // Junk aliases (rank past the guard) are REPLACED by the display form, keeping the
        // base's slot — the user swipes the letters and gets the apostrophe back.
        val expected = mapOf(
            "cest" to "c'est",
            "jai" to "j'ai",
            "dor" to "d'or",
            "quil" to "qu'il",
            "quest" to "qu'est",
            "sil" to "s'il",
            "nest" to "n'est",
            "aujourdhui" to "aujourd'hui",
        )
        for ((alias, display) in expected) {
            assertWithMessage("'$alias' must be a French lexicon word (else no beam can emit it)")
                .that(lexicons.getValue("fr")).contains(alias)
            assertThat(languageFiles.getValue("fr")[alias]).isEqualTo(display)
            assertWithMessage("fr: '$alias' must still present as '$display'")
                .that(overlay("fr", Policy.SHIPPED, listOf(alias))).containsExactly(display)
        }
    }

    @Test
    fun `Italian contractions still fire under the shipped policy`() {
        // it is a GEOMETRIC-engine language (no CTC λ sweep yet) — the same adapter change
        // applies to it. `contractions_it.json` used to hold 22,474 entries of which only 119
        // could ever be decoded; it now holds exactly the alias-only remainder. These are
        // REPLACE-mode: no Italian reading, so the display form takes the slot.
        val expected = mapOf("daccordo" to "d'accordo", "mama" to "m'ama", "lun" to "l'un")
        for ((alias, display) in expected) {
            assertThat(lexicons.getValue("it")).contains(alias)
            assertThat(languageFiles.getValue("it")[alias]).isEqualTo(display)
            assertWithMessage("it: '$alias' must still present as '$display'")
                .that(overlay("it", Policy.SHIPPED, listOf(alias))).containsExactly(display)
        }
    }

    @Test
    fun `German clitic contractions fire under the shipped policy`() {
        // Dropping the English base left German with only four French-origin proper-noun
        // elisions (`d'Estaing`, `d'Ivoire`, `d'Italia`, `d'or`) — no GERMAN form at all,
        // because `scripts/extract_apostrophe_words.py`'s source (the AnySoftKeyboard German
        // wordlist) holds just 18 apostrophe words, all proper nouns, and its `'s` filter
        // would have dropped the clitics anyway. `contractions_de.json` now also carries the
        // one genuine German apostrophe contraction: the elided "e" of the clitic "es"
        // (Duden D 16 — "geht's", "gibt's", "hab's"), hand-curated so that every key is a
        // word the German beam can actually emit ([BundledContractionDataTest]).
        val expected = mapOf(
            "gehts" to "geht's",
            "gibts" to "gibt's",
            "ists" to "ist's",
            "habs" to "hab's",
            "wenns" to "wenn's",
            "weils" to "weil's",
            "wirds" to "wird's",
            "kanns" to "kann's",
            "reichts" to "reicht's",
        )
        for ((alias, display) in expected) {
            assertWithMessage("'$alias' must be a German lexicon word (else no beam can emit it)")
                .that(lexicons.getValue("de")).contains(alias)
            assertThat(languageFiles.getValue("de")[alias]).isEqualTo(display)
            assertWithMessage(
                "de: '$alias' is a misspelling of '$display' with no other reading, so the " +
                    "overlay must REPLACE it and keep the slot"
            ).that(overlay("de", Policy.SHIPPED, listOf(alias))).containsExactly(display)
            assertWithMessage("de: '$alias' must be past the real-word guard to be replaced")
                .that(ordinals.getValue("de")[alias]!!)
                .isGreaterThan(ContractionOverlay.REAL_WORD_ORDINAL_MAX)
        }

        // The four pre-existing French-origin proper-noun elisions still fire.
        for ((alias, display) in mapOf(
            "destaing" to "d'estaing",
            "ditalia" to "d'italia",
            "divoire" to "d'ivoire",
            "dor" to "d'or",
        )) {
            assertThat(languageFiles.getValue("de")[alias]).isEqualTo(display)
            assertWithMessage("'$alias' must be a German lexicon word for this to be reachable")
                .that(lexicons.getValue("de")).contains(alias)
            assertThat(overlay("de", Policy.SHIPPED, listOf(alias))).containsExactly(display)
        }
    }

    @Test
    fun `the German clitics are German morphology, not the English base coming back`() {
        // The point of the whole change: German now has something OF ITS OWN, and it is
        // reached without re-admitting a single English mapping. A mixed German slate must
        // come back with German apostrophes only — no "i'm" for "im", no English possessive.
        val slate = listOf("gehts", "im", "und", "gibts", "franco", "wenns")
        for (word in slate) {
            assertWithMessage("fixture: '$word' must be a German lexicon word")
                .that(lexicons.getValue("de")).contains(word)
        }

        val fixed = overlay("de", Policy.SHIPPED, slate)
        assertWithMessage("de: the clitics are displayed with their apostrophe")
            .that(fixed).containsExactly("geht's", "im", "und", "gibt's", "franco", "wenn's")
            .inOrder()
        assertWithMessage("de: no English morphology may appear")
            .that(fixed.filter { it == "i'm" || it.endsWith("o's") }).isEmpty()

        // Pre-fix, the SAME slate leaked English on top: the base's `im` → "i'm" alias and
        // the `franco` possessive pairing both fired. (The clitics are read from the German
        // file under either policy, so only the English additions differ here.)
        val leaked = overlay("de", Policy.PRE_FIX_ENGLISH_BASE, slate)
        assertThat(leaked).containsAtLeast("franco's", "i'm")
    }

    @Test
    fun `languages that ship no contractions get no overlay at all`() {
        for (language in EMPTY_CONTRACTION_FILES) {
            assertWithMessage("contractions_$language.json is expected to be empty")
                .that(languageFiles.getValue(language)).isEmpty()
        }
        val slate = listOf("franco", "como", "del", "espana")
        assertWithMessage(
            "es ships an EMPTY contraction file: the shipped policy must leave its slate " +
                "exactly as decoded (no contractions beats another language's contractions)"
        ).that(overlay("es", Policy.SHIPPED, slate)).containsExactlyElementsIn(slate).inOrder()
    }

    @Test
    fun `the real-word ordinal guard is still load-bearing for an UNCURATED file`() {
        // The guard's job moved, it did not disappear. The BUNDLED files are curated: a key
        // that is a real word now lives in `contraction_pairs_<lang>.json` and reaches the
        // PAIRED rule directly, so the bundled REPLACE files contain nothing the guard has
        // to rescue (pinned by BundledContractionDataTest).
        for (language in listOf("fr", "it")) {
            val ranks = ordinals.getValue(language)
            val rescued = languageFiles.getValue(language).keys.filter {
                (ranks[it] ?: Int.MAX_VALUE) < ContractionOverlay.REAL_WORD_ORDINAL_MAX
            }
            assertWithMessage("$language: the curated REPLACE file must need no rank rescue")
                .that(rescued).isEmpty()
        }

        // An IMPORTED language pack ships only `contractions.json` (LanguagePackManager has
        // no pairs file), so its mappings are uncurated and the guard is the ONLY thing that
        // stops a common word from being replaced. Feed such a file — the pre-curation fr
        // mappings for `la`/`les`/`ma` — through the real overlay with the real fr ordinals.
        val uncurated = mapOf("la" to "l'a", "les" to "l'es", "ma" to "m'a")
        for ((alias, display) in uncurated) {
            assertThat(ordinals.getValue("fr")[alias]!!)
                .isLessThan(ContractionOverlay.REAL_WORD_ORDINAL_MAX)
            val out = ContractionOverlay.apply(
                words = listOf(alias),
                scores = listOf(900),
                pairedVariants = { null },
                nonPairedMapping = { uncurated[it] },
                wordOrdinal = { ordinals.getValue("fr")[it] },
            ).first
            assertWithMessage("fr: '$alias' must keep the top slot")
                .that(out.first()).isEqualTo(alias)
            assertWithMessage("fr: '$display' must be appended, not substituted")
                .that(out).contains(display)
        }
    }

    // ── 3b. the APPEND classification: both spellings stay reachable ────────────────

    @Test
    fun `swiping lune in French offers both the word lune and the elision l'une`() {
        // THE requirement. "lune" (the moon) is the 2,055th most common French word — past
        // REAL_WORD_ORDINAL_MAX — so the rank guard could not save it and the overlay
        // REPLACED it with "l'une": the user could not swipe the moon at all. The word is
        // now APPEND-mode, so both spellings are on the slate and the user picks.
        assertWithMessage("fixture: 'lune' must be a French lexicon word")
            .that(lexicons.getValue("fr")).contains("lune")
        assertThat(ordinals.getValue("fr")["lune"]!!)
            .isGreaterThan(ContractionOverlay.REAL_WORD_ORDINAL_MAX)

        val slate = overlay("fr", Policy.SHIPPED, listOf("lune", "lutte"))
        assertWithMessage(
            "fr: the swiped word keeps its slot and the elision is APPENDED after the " +
                "engine's own candidates (ContractionOverlay's variant placement)"
        ).that(slate).containsExactly("lune", "lutte", "l'une").inOrder()

        // The same for the rest of the class of French words that used to be destroyed.
        for ((word, elision) in mapOf(
            "danse" to "d'anse",
            "lion" to "l'ion",
            "larme" to "l'arme",
            "laide" to "l'aide",
            "lavoir" to "l'avoir",
        )) {
            val out = overlay("fr", Policy.SHIPPED, listOf(word))
            assertWithMessage("fr: '$word' must survive its own elision")
                .that(out).containsExactly(word, elision).inOrder()
        }
    }

    @Test
    fun `swiping lago in Italian offers both the word lago and the elision l'ago`() {
        assertWithMessage("fixture: 'lago' must be an Italian lexicon word")
            .that(lexicons.getValue("it")).contains("lago")
        assertThat(overlay("it", Policy.SHIPPED, listOf("lago")))
            .containsExactly("lago", "l'ago").inOrder()

        for ((word, elision) in mapOf(
            "luna" to "l'una",
            "lira" to "l'ira",
            "signora" to "s'ignora",
            "duomo" to "d'uomo",
            // Modern Italian writes these solid (Treccani); the apostrophe form is the
            // variant, so replacing them destroyed the STANDARD spelling.
            "tuttora" to "tutt'ora",
            "finora" to "fin'ora",
        )) {
            assertWithMessage("it: '$word' must survive its own elision")
                .that(overlay("it", Policy.SHIPPED, listOf(word)))
                .containsExactly(word, elision).inOrder()
        }
    }

    @Test
    fun `an alias with no reading of its own is still REPLACED, never offered bare`() {
        // The other half of the split: these keys are not words, so keeping them would put a
        // misspelling on the bar. "cest" is in the French lexicon (that is why the beam can
        // emit it) and is still not a French word — dictionary membership is not the test.
        for ((alias, display) in mapOf(
            "cest" to "c'est",
            "jai" to "j'ai",
            "quil" to "qu'il",
            "lhomme" to "l'homme",
        )) {
            assertThat(lexicons.getValue("fr")).contains(alias)
            val out = overlay("fr", Policy.SHIPPED, listOf(alias))
            assertWithMessage("fr: '$alias' must be replaced by '$display'")
                .that(out).containsExactly(display)
            assertWithMessage("fr: the bare alias must not be offered")
                .that(out).doesNotContain(alias)
        }
        assertThat(overlay("it", Policy.SHIPPED, listOf("daccordo"))).containsExactly("d'accordo")
    }

    // ── 4. English must be byte-for-byte unchanged ──────────────────────────────────

    @Test
    fun `English keeps the bundled base, its contractions and its possessives`() {
        assertThat(overlay("en", Policy.SHIPPED, listOf("dont", "done")))
            .containsExactly("don't", "done").inOrder()
        assertThat(overlay("en", Policy.SHIPPED, listOf("cant"))).containsExactly("can't")

        val well = overlay("en", Policy.SHIPPED, listOf("well", "wall"))
        assertWithMessage("the paired base 'well' keeps its slot, \"we'll\" is appended")
            .that(well).containsExactly("well", "wall", "we'll").inOrder()

        // English possessive augmentation from the pairings file is INTENDED for English.
        assertThat(overlay("en", Policy.SHIPPED, listOf("franco"))).contains("franco's")

        // The shipped en path must be identical to the pre-fix en path, mapping for mapping.
        val shipped = mappingsFor("en", Policy.SHIPPED)
        val preFix = mappingsFor("en", Policy.PRE_FIX_ENGLISH_BASE)
        assertThat(shipped.nonPaired).isEqualTo(preFix.nonPaired)
        assertThat(shipped.paired).isEqualTo(preFix.paired)
    }

    // ── 5. the alias → display projection invariant the on-device assertion rests on ─

    @Test
    fun `every swipeable alias in a bundled language file projects back to itself`() {
        // `slate ⊆ lexicon` (by a–z projection) only holds for non-English languages because
        // each language file maps an a–z key to a display form that differs ONLY by
        // apostrophes/hyphens. Keys that are not pure a–z (fr "chef-dœuvre") can never be a
        // beam surface, so they are excluded — as is English, whose possessive pairings
        // deliberately project elsewhere ("africa" → "africa's" → "africas").
        for (language in listOf("fr", "de", "it", "nl")) {
            val mappings = languageFiles.getValue(language).map { (k, v) -> k to v } +
                languagePairFiles.getValue(language).flatMap { (k, vs) -> vs.map { k to it } }
            var checked = 0
            for ((alias, display) in mappings) {
                if (CtcAzProjection.project(alias) != alias) continue // not swipeable
                assertWithMessage(
                    "$language: '$alias' → '$display' projects to " +
                        "'${CtcAzProjection.project(display)}', so displaying it would put a " +
                        "word outside the lexicon on the bar"
                ).that(CtcAzProjection.project(display)).isEqualTo(alias)
                checked++
            }
            assertWithMessage("$language: expected swipeable aliases to check").that(checked)
                .isGreaterThan(0)
        }
    }

    // ── TYPING pipeline scoping (2026-08-19) ──────────────────────────────────────

    /**
     * The typing pipeline must not put English morphology in a non-English slate either.
     *
     * ### The bug this pins
     *
     * `ManagerInitializer` (now `KeyboardComponentGraph`) and `PreferenceUIUpdateHandler` both hand-rolled the same load
     * order: English base, then the primary language, then `loadLanguageContractions("en")`
     * AGAIN, unconditionally. Both loaders are earlier-wins, so the English base — loaded
     * first — owned every colliding key for every user of every language.
     *
     * Measured casualty, from the real shipped assets: `contractions_en.json` maps
     * `im -> i'm`, and `contractions_de.json` has no `im` entry of its own. So a German user
     * typing **im** (an ordinary German preposition-article contraction, corpus rank 16) was
     * offered **I'm**. French `dont` (rank 104) -> `don't` is the same shape.
     *
     * The swipe path was fixed on 2026-08-16; its KDoc explicitly deferred the typing side as
     * "a separate decision with a separate blast radius". This asserts that decision, over the
     * REAL assets, under both the old order and the shipped one — so the leak and its absence
     * are both demonstrated rather than asserted.
     */
    @Test
    fun typingScopingKeepsEnglishOutOfANonEnglishSlate() {
        val germanOwn = languageFiles["de"].orEmpty()
        assertWithMessage(
            "precondition: contractions_de.json must NOT define `im` — the whole point is that " +
                "German has no mapping of its own here, so whatever English supplies wins"
        ).that(germanOwn).doesNotContainKey("im")
        assertWithMessage("precondition: English must map `im`, or this test proves nothing")
            .that(englishNonPaired).containsKey("im")

        // OLD order: English base first, then the language, then English again.
        val oldOrder = LinkedHashMap<String, String>()
        englishNonPaired.forEach { (k, v) -> oldOrder.putIfAbsent(k, v) }
        germanOwn.forEach { (k, v) -> oldOrder.putIfAbsent(k, v) }
        assertWithMessage(
            "the OLD load order leaks English into a German slate — this is the bug, and it " +
                "must stay demonstrated so the fix cannot be quietly reverted"
        ).that(oldOrder["im"]).isEqualTo(englishNonPaired["im"])

        // SHIPPED order for a German-primary user with no English secondary: the English base
        // is never loaded at all, so `im` has no mapping and the typed word stands.
        val scoped = LinkedHashMap<String, String>()
        germanOwn.forEach { (k, v) -> scoped.putIfAbsent(k, v) }
        assertWithMessage(
            "a German-primary user with no English secondary must not be offered `I'm` for `im`"
        ).that(scoped).doesNotContainKey("im")
        assertWithMessage("German keeps its OWN clitics — scoping must not empty the language")
            .that(scoped).containsKey("gehts")
    }

    /**
     * Precedence: the PRIMARY language owns a colliding key, even when English is the
     * secondary language and its base is therefore loaded.
     *
     * This is why `loadTypingMappings` loads primary -> secondary -> English base rather than
     * reusing the swipe path's simpler two-branch rule. Selecting English as a SECONDARY
     * language re-admits English morphology (the user asked for it), but it must be ADDITIVE:
     * it cannot take a key the primary language already defines. With the old base-first order
     * it always did.
     */
    @Test
    fun englishAsSecondaryIsAdditiveNotDominant() {
        val french = languageFiles["fr"].orEmpty()
        val collisions = french.keys.intersect(englishNonPaired.keys)
        assumeTrueThat(collisions.isNotEmpty())

        // primary(fr) -> secondary(en base): earlier-wins means French keeps its own keys.
        val scoped = LinkedHashMap<String, String>()
        french.forEach { (k, v) -> scoped.putIfAbsent(k, v) }
        englishNonPaired.forEach { (k, v) -> scoped.putIfAbsent(k, v) }

        for (key in collisions) {
            assertWithMessage(
                "French is PRIMARY, so `$key` must keep the French reading even though the " +
                    "English base is loaded for the English secondary"
            ).that(scoped[key]).isEqualTo(french[key])
        }
        // ...and English still contributes the keys French does not define.
        val englishOnly = englishNonPaired.keys - french.keys
        assumeTrueThat(englishOnly.isNotEmpty())
        val sample = englishOnly.first()
        assertWithMessage("selecting English as secondary must still ADD its own mappings")
            .that(scoped[sample]).isEqualTo(englishNonPaired[sample])
    }

    /** Tiny helper so a precondition that is data-dependent reads as intent, not an assert. */
    private fun assumeTrueThat(condition: Boolean) {
        assertWithMessage("shipped assets no longer satisfy this test's precondition")
            .that(condition).isTrue()
    }
}
