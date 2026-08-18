package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.JsonParser
import org.junit.BeforeClass
import org.junit.Test
import tribixbite.cleverkeys.swipe.ctc.CtcAzProjection
import tribixbite.cleverkeys.swipe.ctc.CtcCkdtLexicon
import tribixbite.cleverkeys.swipe.ctc.CtcContractionKeys
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconTrie
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconMerge
import tribixbite.cleverkeys.swipe.geometric.CkdtDictionaryReader
import java.io.File

/**
 * DATA-quality guards for the shipped per-language contraction files
 * (`assets/dictionaries/contractions_<lang>.json` and `contraction_pairs_<lang>.json`).
 *
 * ### The two files, and why the split IS the data model
 *
 * A contraction file is a pure DISPLAY overlay: the key is the apostrophe-free surface an
 * engine can produce, the value is what the user is shown ([ContractionOverlay]). The overlay
 * treats the two shipped files differently, and that difference is the whole product rule:
 *
 *  - `contractions_<lang>.json` → the NON-PAIRED map: the key is an alias with no reading of
 *    its own (`cest`, `jai`, `gehts`), so the display form REPLACES it and keeps its slot.
 *  - `contraction_pairs_<lang>.json` → the PAIRED map: the key IS a word of the language
 *    (`lune`, `danse`, `lago`, `signora`), so it is KEPT and the elision is APPENDED as a
 *    variant. Both spellings stay reachable, which is the requirement — a user who swiped
 *    `lune` may have wanted the moon or `l'une`, and the keyboard may not decide for them.
 *
 * Before 2026-08-17 there was only the first file, and the bucket was inferred at RUNTIME from
 * the key's frequency rank ([ContractionOverlay.REAL_WORD_ORDINAL_MAX]). Rank works for
 * English by luck (its aliases `dont`/`im`/`cant` genuinely are not words) and fails for
 * French and Italian, where common words rank past the threshold and were REPLACED: `lune`
 * (rank 2,054) became `l'une`, `danse` → `d'anse`, `lion` → `l'ion`, `signora` → `s'ignora`,
 * `duomo` → `d'uomo`. The discriminator is corpus attestation of the bare form, not rank, so
 * it is resolved once at data-generation time (`scripts/extract_apostrophe_words.py`) and
 * shipped as the file an entry lives in. The rank guard stays as defense in depth for
 * uncurated IMPORTED language packs, which ship only a `contractions.json`.
 *
 * ### What is asserted
 *
 *  1. **every shipped key is REACHABLE** — a mapping whose key no engine can emit can never
 *     fire; it is dead weight in the APK and it hides the real coverage behind a big entry
 *     count. Reachability has TWO routes and both count (see `isReachable`): the bundled
 *     lexicon can emit the key, or the swipe engines INJECT it as its own decodable surface
 *     (`CtcContractionKeys.inject`, and the deleted vocabulary's equivalent). Judging by the
 *     first route alone is what wrongly deleted 27,256 fr + 22,355 it productive elisions on
 *     2026-08-17; they are restored, and what stays trimmed is only the keys no a–z decoder
 *     can spell at all (accents, hyphens).
 *  2. **the two files are disjoint, and the REPLACE file holds no common word** — the
 *     classification is explicit, so nothing is left for the runtime rank guard to catch.
 *  3. **every value differs from its key by apostrophes/hyphens ONLY** — no accent change, no
 *     other letters. A value that changes anything else is either a typo or a word from
 *     another language, and would put a word on the suggestion bar that no engine decoded.
 *  4. **the empty files are empty for a LINGUISTIC reason** — es/pt/sv have no apostrophe
 *     contractions worth displaying, and the tests assert the positive evidence for that
 *     (the relevant words are in the lexicon spelled WITHOUT apostrophes).
 *
 * Pure JVM; the pure-test CWD is the project root, so the shipped assets are reachable by
 * relative `File` paths (same convention as [SwipeContractionLanguageIsolationTest]).
 */
class BundledContractionDataTest {

    /** One shipped contraction file measured against its language's bundled lexicon. */
    private class Coverage(
        /** Total mappings in the file. */
        val entries: Int,
        /** Mappings whose key is a form some shipped engine can emit: the live ones. */
        val live: Int,
    ) {
        /** Keys no engine can ever produce for this language: dead weight. */
        val dead: Int get() = entries - live
    }

    private companion object {
        const val DICT_DIR = "src/main/assets/dictionaries"

        /** Languages that bundle BOTH a CKDT dictionary and a contraction file. */
        val LEXICON_LANGUAGES = listOf("de", "es", "fr", "it", "pt", "sv")

        /** language → `contractions_<lang>.json` (REPLACE mode), keys/values lowercased. */
        lateinit var files: Map<String, Map<String, String>>

        /** language → `contraction_pairs_<lang>.json` (APPEND mode); empty when absent. */
        lateinit var pairFiles: Map<String, Map<String, List<String>>>

        /** language → the a–z surfaces the beam can emit (projected bundled lexicon). */
        lateinit var lexicons: Map<String, Set<String>>

        /** language → a–z surface → the canonical (display) spelling the engine commits. */
        lateinit var canonical: Map<String, Map<String, String>>

        /** language → every string a shipped engine can put on the bar (see [emittedFor]). */
        lateinit var emitted: Map<String, Set<String>>

        /** language → lowercase word → frequency ordinal over the bundled CKDT lexicon. */
        lateinit var ordinals: Map<String, HashMap<String, Int>>

        /** language → the a–z surface → frequency map the production trie is built from. */
        lateinit var projectedFreqs: Map<String, Map<String, Double>>

        /** The CTC emission alphabet — the only characters an injected key may use. */
        val AZ_ALPHABET = CharArray(26) { 'a' + it }

        fun jsonObject(name: String): Map<String, String> {
            val file = File("$DICT_DIR/$name")
            check(file.isFile) { "expected shipped asset at ${file.path} (run from project root)" }
            val root = JsonParser.parseString(file.readText()).asJsonObject
            val out = LinkedHashMap<String, String>(root.size() * 2)
            for ((key, value) in root.entrySet()) out[key.lowercase()] = value.asString.lowercase()
            return out
        }

        /** `contraction_pairs_<lang>.json` — `{base: [variant, …]}`; absent file → empty. */
        fun pairsObject(language: String): Map<String, List<String>> {
            val file = File("$DICT_DIR/contraction_pairs_$language.json")
            if (!file.isFile) return emptyMap()
            val root = JsonParser.parseString(file.readText()).asJsonObject
            val out = LinkedHashMap<String, List<String>>(root.size() * 2)
            for ((key, value) in root.entrySet()) {
                out[key.lowercase()] = value.asJsonArray.map { it.asString.lowercase() }
            }
            return out
        }

        /**
         * Every string a SHIPPED engine can put on the suggestion bar for a lexicon — the
         * union of the emission paths, so a mapping is judged dead only when NO path can
         * reach it:
         *
         *  - **CTC**: the beam walks the a–z projection, then `applyCanonicalDisplay` runs
         *    BEFORE the contraction overlay, so what the overlay sees is
         *    `display[surface] ?: surface` — never the bare surface of an accented word
         *    (swiping `dira` in Italian presents `dirà`, so a `dira` mapping is dead).
         *  - **geometric / ctc / typing**: these carry the canonical dictionary word
         *    itself, including forms that lost their a–z surface to a collision (en `dêtre`
         *    loses `detre` to the equally-ranked `detre`, but is still a word one can type).
         */
        fun emittedFor(merged: Map<String, Double>): Set<String> {
            val projected = CtcAzProjection.projectLexicon(merged)
            val out = HashSet<String>(merged.size * 3)
            for (surface in projected.freqs.keys) {
                out += (projected.display[surface] ?: surface).lowercase()
            }
            for (word in merged.keys) out += word.lowercase()
            return out
        }

        @JvmStatic
        @BeforeClass
        fun loadShippedAssets() {
            files = (LEXICON_LANGUAGES + "nl" + "id" + "ms" + "tl" + "sw")
                .associateWith { jsonObject("contractions_$it.json") }
            pairFiles = (LEXICON_LANGUAGES + "nl" + "id" + "ms" + "tl" + "sw")
                .associateWith { pairsObject(it) }

            val lex = HashMap<String, Set<String>>()
            val canon = HashMap<String, Map<String, String>>()
            val emit = HashMap<String, Set<String>>()
            val ord = HashMap<String, HashMap<String, Int>>()
            val projFreqs = HashMap<String, Map<String, Double>>()
            for (language in LEXICON_LANGUAGES) {
                val bin = File("$DICT_DIR/${language}_enhanced.bin")
                check(bin.isFile) { "expected shipped lexicon at ${bin.path}" }
                val entries = bin.inputStream().use { CkdtDictionaryReader.readEntries(it) }
                val merged = CtcLexiconMerge.merge(
                    CtcCkdtLexicon.frequencyPairs(entries), emptyList(), emptySet()
                )
                val projected = CtcAzProjection.projectLexicon(merged)
                lex[language] = projected.freqs.keys.toSet()
                // `display` only carries surfaces whose canonical form DIFFERS; resolve the
                // way every caller does, so the map is total over the lexicon.
                canon[language] = projected.freqs.keys.associateWith {
                    projected.display[it] ?: it
                }
                emit[language] = emittedFor(merged)
                ord[language] = CtcLexiconMerge.ordinals(merged)
                projFreqs[language] = projected.freqs
            }
            lexicons = lex
            canonical = canon
            emitted = emit
            ordinals = ord
            projectedFreqs = projFreqs
        }
    }

    /**
     * True when [key] can reach the suggestion bar for [language] AT ALL — by either of the
     * two production routes:
     *
     *  1. the bundled lexicon can emit it ([emittedFor]), or
     *  2. the swipe engines INJECT it as its own decodable surface — `CtcEngineAdapter`
     *     inserts every alias key into the lexicon trie via [CtcContractionKeys.inject], and
     *     `OptimizedVocabulary.loadContractionsFromInputStream` does the equivalent for the
     *     deleted vocabulary. Injection is limited to keys spelled from the a–z alphabet,
     *     which is exactly [CtcContractionKeys.isInjectable].
     *
     * Route 2 is why the 2026-08-17 restore is correct: a productive elision such as fr
     * `dabaissement` → `d'abaissement` is not, and never will be, a dictionary word, but it
     * is precisely what a French user swipes. Judging reachability by route 1 alone declared
     * 27,256 fr + 22,355 it mappings dead and deleted them.
     */
    private fun isReachable(language: String, key: String): Boolean =
        key in emitted.getValue(language) || CtcContractionKeys.isInjectable(key, AZ_ALPHABET)

    private fun coverageOf(language: String): Coverage {
        val keys = files.getValue(language).keys + pairFiles.getValue(language).keys
        return Coverage(entries = keys.size, live = keys.count { isReachable(language, it) })
    }

    /** The dead keys of [language], named — a count alone is useless when this breaks. */
    private fun deadKeysOf(language: String): List<String> =
        (files.getValue(language).keys + pairFiles.getValue(language).keys)
            .filterNot { isReachable(language, it) }
            .sorted()

    // ── 1. the dead-data guard ──────────────────────────────────────────────────────

    @Test
    fun `every German contraction maps a word the beam can actually emit`() {
        // The curated file: 100% reachable, no exceptions. This is the guard that makes a
        // future "just add more forms" edit prove itself against the shipped dictionary
        // instead of shipping mappings that can never fire.
        val de = coverageOf("de")
        assertWithMessage(
            "contractions_de.json is hand-curated against de_enhanced.bin: every key must be " +
                "a German lexicon surface, or the mapping is dead weight in the APK"
        ).that(de.dead).isEqualTo(0)
        assertThat(de.live).isEqualTo(de.entries)

        // Name the dead keys, not just the count, when this ever breaks.
        assertThat(deadKeysOf("de")).isEmpty()
        // German needs no APPEND file: not one of its 21 keys is a German word (the clitic
        // elisions are misspellings with no other reading — see the last test in this class).
        assertThat(pairFiles.getValue("de")).isEmpty()
    }

    @Test
    fun `the French and Italian files are fully reachable, and their sizes are pinned`() {
        // THE RATCHET. These numbers may only move when the DICTIONARY or the generator's
        // classifier moves — re-run `scripts/extract_apostrophe_words.py --lang fr,it`.
        //
        // History, because the counts swung twice: the generator originally lifted every
        // apostrophe token out of the AnySoftKeyboard wordlists (fr 27,494 / it 22,474). A
        // 2026-08-17 pass judged reachability by "is the key a form the bundled LEXICON can
        // emit" and deleted 27,256 fr + 22,355 it as dead. That model was wrong: the swipe
        // engines INJECT the alias keys as decodable surfaces, so a productive elision like
        // `dabaissement` → `d'abaissement` — not a dictionary word, and exactly what French
        // users type — IS reachable. The restore keeps every a–z key and drops only the keys
        // no a–z decoder can ever spell (accents, hyphens: `cest-à-dire`, `ceût`).
        assertThat(files.getValue("fr")).hasSize(17_931)
        assertThat(pairFiles.getValue("fr")).hasSize(183)
        val fr = coverageOf("fr")
        assertWithMessage("dead French mappings: ${deadKeysOf("fr")}").that(fr.dead).isEqualTo(0)
        assertThat(fr.entries).isEqualTo(18_114)

        assertThat(files.getValue("it")).hasSize(21_214)
        assertThat(pairFiles.getValue("it")).hasSize(148)
        val it = coverageOf("it")
        assertWithMessage("dead Italian mappings: ${deadKeysOf("it")}").that(it.dead).isEqualTo(0)
        assertThat(it.entries).isEqualTo(21_362)
    }

    @Test
    fun `the restored French elisions are reachable ONLY because the trie injects them`() {
        // The assertion the old `unreachable ⇒ dead` rule replaced. It is not enough that the
        // mapping ships: the beam has to be able to SPELL the key, or the overlay has nothing
        // to rewrite. This walks the real production path — the bundled CKDT lexicon, the
        // same a–z projection `CtcEngineAdapter` uses, then `CtcContractionKeys.inject`.
        val trie = CtcLexiconTrie.loadFromFrequencyMap(
            AZ_ALPHABET, projectedFreqs.getValue("fr")
        )

        // Productive elisions: NOT French words, so the lexicon alone cannot emit them.
        val productive = listOf("dabaissement", "dabandon", "dabaisser")
        for (key in productive) {
            assertWithMessage("$key must be a shipped French mapping").that(files.getValue("fr"))
                .containsKey(key)
            assertWithMessage("$key is not a French word — the lexicon cannot emit it")
                .that(trie.contains(key)).isFalse()
        }

        val keys = files.getValue("fr").keys + pairFiles.getValue("fr").keys
        val inserted = CtcContractionKeys.inject(trie, keys)
        assertWithMessage("injection must add the keys the lexicon lacks")
            .that(inserted).isGreaterThan(17_000)

        for (key in productive) {
            assertWithMessage("$key must be decodable after injection")
                .that(trie.contains(key)).isTrue()
            // ...and it must be the FLOOR frequency, so a pseudo-word can never outrank real
            // vocabulary on the beam's lambda * logFreq term.
            assertThat(trie.logFrequencyOf(key)!!).isWithin(1e-6).of(0.0)
        }

        // The everyday elisions the maintainer called crucial resolve to their apostrophe form.
        assertThat(files.getValue("fr")["lhomme"]).isEqualTo("l'homme")
        assertThat(files.getValue("fr")["dabaissement"]).isEqualTo("d'abaissement")
        assertThat(files.getValue("fr")["quil"]).isEqualTo("qu'il")

        // Injecting a key that IS a real word must not touch its frequency — the overlay's
        // real-word ordinal guard depends on it.
        val realWord = "lune"
        assertThat(pairFiles.getValue("fr")).containsKey(realWord)
        val lexiconFreq = trie.logFrequencyOf(realWord)
        assertWithMessage("$realWord is a French word and must keep its lexicon frequency")
            .that(lexiconFreq!!).isGreaterThan(1.0)
    }

    @Test
    fun `no language ships the same key in both the replace file and the append file`() {
        // A key in both maps is a contradiction: ContractionOverlay checks PAIRED first, so
        // the non-paired entry would be silently unreachable — and the next reader would draw
        // the wrong conclusion about which mode that key is in.
        for (language in files.keys) {
            val overlap = files.getValue(language).keys.intersect(pairFiles.getValue(language).keys)
            assertWithMessage("$language: keys in BOTH contraction files: $overlap")
                .that(overlap).isEmpty()
        }
    }

    @Test
    fun `no replace-mode key is a common word of its own language`() {
        // The invariant the whole split buys: after curation nothing in the REPLACE file is
        // a word frequent enough for ContractionOverlay's rank guard to rescue. Equivalently
        // — the guard now has NOTHING left to catch in the bundled data, because the decision
        // was made from corpus attestation at generation time instead of from rank at
        // runtime. (The guard is still load-bearing for imported language packs, which ship
        // an uncurated `contractions.json`; see SwipeContractionLanguageIsolationTest.)
        for (language in listOf("de", "fr", "it")) {
            val ranks = ordinals.getValue(language)
            val common = files.getValue(language).keys
                .filter { (ranks[it] ?: Int.MAX_VALUE) < ContractionOverlay.REAL_WORD_ORDINAL_MAX }
            assertWithMessage(
                "$language: $common rank inside REAL_WORD_ORDINAL_MAX yet are marked " +
                    "REPLACE — the overlay would keep them and append instead, so either the " +
                    "entry belongs in contraction_pairs_$language.json or it is a real word " +
                    "the generator misjudged"
            ).that(common).isEmpty()
        }
    }

    // ── 2. the projection invariant ─────────────────────────────────────────────────

    @Test
    fun `every contraction value differs from its key by apostrophes and hyphens only`() {
        // Stricter than SwipeContractionLanguageIsolationTest's a–z projection check, which
        // also folds accents away: here the display form may ADD an apostrophe or hyphen and
        // nothing else. That catches a typo ("gibts" → "gibsts"), a wrong-language value
        // ("gehts" → "va bene") and an accent the beam's canonical form does not carry.
        // English is excluded on purpose: its possessive pairings deliberately add a letter
        // ("africa" → "africa's").
        val joiners = charArrayOf('\'', '’', '-')
        for (language in files.keys) {
            val mappings = files.getValue(language).map { (k, v) -> k to v } +
                pairFiles.getValue(language).flatMap { (k, vs) -> vs.map { k to it } }
            for ((key, value) in mappings) {
                val bareValue = value.filterNot { it in joiners }
                val bareKey = key.filterNot { it in joiners }
                assertWithMessage(
                    "$language: '$key' → '$value' strips to '$bareValue' but the key strips to " +
                        "'$bareKey' — the overlay would show a word the beam never decoded"
                ).that(bareValue).isEqualTo(bareKey)
                assertWithMessage(
                    "$language: '$key' → '$value' is an identity mapping, so it does nothing"
                ).that(value).isNotEqualTo(key)
            }
        }
        // Guard the guard: the loop must actually have something to check.
        for (language in listOf("de", "fr", "it", "nl")) {
            assertThat(files.getValue(language)).isNotEmpty()
        }
        for (language in listOf("fr", "it")) {
            assertThat(pairFiles.getValue(language)).isNotEmpty()
        }
    }

    // ── 3. the empty files are CORRECT, not unfinished ──────────────────────────────

    @Test
    fun `Spanish ships no contractions because Spanish amalgams carry no apostrophe`() {
        // `al` (a + el) and `del` (de + el) are the only two contractions in standard Spanish
        // orthography and BOTH are written solid — RAE never inserts an apostrophe. So the
        // empty file is the linguistically correct answer, and the evidence is that the
        // bundled lexicon already spells them the way the user wants to see them.
        assertThat(files.getValue("es")).isEmpty()
        for (word in listOf("al", "del")) {
            assertThat(lexicons.getValue("es")).contains(word)
            assertWithMessage("es: '$word' is already the committed spelling — nothing to overlay")
                .that(canonical.getValue("es")[word]).isEqualTo(word)
        }
    }

    @Test
    fun `Portuguese ships no contractions because its apostrophe forms are not swipeable`() {
        // Portuguese contractions (do/da/no/na/pelo/à) are solid, exactly like Spanish. The
        // genuine apostrophe forms are a handful of frozen "de + vowel" expressions
        // (d'água, d'alho, d'angola, d'olho) — and NONE of their a–z keys is in the bundled
        // Portuguese lexicon, so a mapping for them could never fire.
        assertThat(files.getValue("pt")).isEmpty()
        val ptLexicon = lexicons.getValue("pt")
        for (key in listOf("dagua", "dalho", "dangola", "dolho", "dalma", "darte")) {
            assertWithMessage("pt: '$key' is absent, so a d'-mapping for it would be dead data")
                .that(ptLexicon).doesNotContain(key)
        }
        // The two candidates that ARE in the lexicon must stay unmapped: their canonical
        // spelling is the apostrophe-FREE one (Douro the river/wine region, Dalva the given
        // name), and the overlay would REPLACE it — a regression, not a fix.
        for (word in listOf("douro", "dalva")) {
            assertThat(ptLexicon).contains(word)
            assertThat(canonical.getValue("pt")[word]).isEqualTo(word)
            assertThat(files.getValue("pt")).doesNotContainKey(word)
        }
    }

    @Test
    fun `Swedish ships no contractions because the reduced forms are written solid`() {
        // Swedish has no apostrophe contractions: the genitive takes a bare -s (never 's),
        // and the colloquial reductions the apostrophe used to mark ("sta'n", "da'n") are
        // written solid in modern Swedish — which is exactly how the bundled lexicon spells
        // them, so there is nothing for a display overlay to add.
        assertThat(files.getValue("sv")).isEmpty()
        for (word in listOf("stan", "dan")) {
            assertThat(lexicons.getValue("sv")).contains(word)
            assertWithMessage("sv: '$word' is already the modern solid spelling")
                .that(canonical.getValue("sv")[word]).isEqualTo(word)
        }
    }

    @Test
    fun `the languages with no bundled lexicon still ship an empty contraction file`() {
        for (language in listOf("id", "ms", "tl", "sw")) {
            assertWithMessage("contractions_$language.json must stay empty")
                .that(files.getValue(language)).isEmpty()
        }
    }

    // ── 4. the German file's content, spelled out ───────────────────────────────────

    @Test
    fun `the German file is the clitic-es elisions plus four French-origin proper nouns`() {
        // Duden D 16: the apostrophe marks the elided "e" of the clitic "es" — "geht's",
        // "gibt's", "hab's". Those are the ONLY genuine apostrophe contractions in German;
        // the preposition+article fusions ("ins", "zum", "vom", "aufs", "fürs", "durchs",
        // "ums") are ordinary solid words and must NEVER be given an apostrophe here.
        val de = files.getValue("de")
        val clitics = de.filterValues { it.endsWith("'s") }
        assertThat(clitics.keys).containsExactly(
            "fands", "gabs", "gehts", "gibts", "gings", "habs", "hats", "ichs", "ists",
            "kanns", "mans", "obs", "reichts", "sags", "weils", "wenns", "wirds",
        )
        for ((key, value) in clitics) {
            assertWithMessage("de: '$value' must be '$key' with an apostrophe before the s")
                .that(value).isEqualTo(key.dropLast(1) + "'s")
        }

        // The pre-existing four: French-origin proper-noun elisions that occur in German text
        // (Giscard d'Estaing, Côte d'Ivoire, Banca d'Italia, "d'or").
        assertThat(de.filterValues { !it.endsWith("'s") }).containsExactly(
            "destaing", "d'estaing",
            "ditalia", "d'italia",
            "divoire", "d'ivoire",
            "dor", "d'or",
        )

        // The solid preposition+article fusions are words, not contractions: no apostrophe
        // may ever be attached to them.
        for (solid in listOf("ins", "zum", "vom", "aufs", "furs", "durchs", "ums", "ubers")) {
            assertWithMessage("de: '$solid' is standard solid German — it must stay unmapped")
                .that(de).doesNotContainKey(solid)
        }
        // And the rejected homographs: each IS a distinct German word, so replacing it with a
        // clitic reading would destroy the word the user swiped. "wies" is the past tense of
        // "weisen", "versuchs"/"halts" are genitives ("des Versuchs", "des Halts"), "wars"
        // and "wills" are proper nouns that outrank the clitic reading in German text.
        for (homograph in listOf("wies", "versuchs", "halts", "wars", "wills")) {
            assertWithMessage("de: '$homograph' is a real word — mapping it would be a regression")
                .that(de).doesNotContainKey(homograph)
            assertWithMessage("de: '$homograph' must still be reachable as itself")
                .that(lexicons.getValue("de")).contains(homograph)
        }
    }

    // ── 5. the French/Italian split, spelled out ────────────────────────────────────

    @Test
    fun `the French words the overlay used to destroy are now APPEND-mode`() {
        // Every one of these is a common French word whose rank sits PAST
        // REAL_WORD_ORDINAL_MAX, so the pre-2026-08-17 overlay replaced it with the elision
        // and the word became unreachable: swiping the moon gave you "l'une". They are now
        // in the APPEND file, so the word keeps its slot and the elision is offered too.
        val pairs = pairFiles.getValue("fr")
        val ranks = ordinals.getValue("fr")
        for ((word, elision) in mapOf(
            "lune" to "l'une",           // the moon
            "danse" to "d'anse",         // the dance
            "lion" to "l'ion",           // the lion
            "larme" to "l'arme",         // the tear
            "laide" to "l'aide",         // ugly (fem.)
            "lait" to "l'ait",           // the milk
            "lavoir" to "l'avoir",       // the wash-house
            "quart" to "qu'art",         // the quarter
            "davantage" to "d'avantage", // more
            "démission" to "d'émission", // the resignation
        )) {
            assertWithMessage("fr: '$word' must be APPEND-mode, not a replaced alias")
                .that(pairs[word]).containsExactly(elision)
            assertThat(files.getValue("fr")).doesNotContainKey(word)
            assertWithMessage(
                "fixture: '$word' must rank past the guard, else it was never at risk and " +
                    "proves nothing about the classification"
            ).that(ranks[word]!!).isAtLeast(ContractionOverlay.REAL_WORD_ORDINAL_MAX)
        }

        // …while the genuine alias-only keys stay REPLACE. None of these is a French word:
        // they are the apostrophe-free spelling of an elision and nothing else.
        for ((alias, display) in mapOf(
            "cest" to "c'est",
            "jai" to "j'ai",
            "quil" to "qu'il",
            "nest" to "n'est",
            "sil" to "s'il",
            "aujourdhui" to "aujourd'hui",
            "lhomme" to "l'homme",
            "lautre" to "l'autre",
            "quest" to "qu'est",
            "dor" to "d'or",
        )) {
            assertWithMessage("fr: '$alias' is not a French word — it must be REPLACE-mode")
                .that(files.getValue("fr")[alias]).isEqualTo(display)
            assertThat(pairs).doesNotContainKey(alias)
        }
    }

    @Test
    fun `the Italian words the overlay used to destroy are now APPEND-mode`() {
        val pairs = pairFiles.getValue("it")
        for ((word, elision) in mapOf(
            "lago" to "l'ago",             // the lake
            "luna" to "l'una",             // the moon
            "lira" to "l'ira",             // the lira
            "signora" to "s'ignora",       // the lady
            "duomo" to "d'uomo",           // the cathedral
            "distruzione" to "d'istruzione", // the destruction
            "doveri" to "dov'eri",         // the duties
            "alloro" to "all'oro",         // the laurel
            "nera" to "n'era",             // black (fem.)
            "cera" to "c'era",             // the wax
            // Modern Italian writes both of these SOLID (Treccani) — the apostrophe form is
            // the variant, so replacing the solid spelling destroyed the standard one.
            "tuttora" to "tutt'ora",
            "finora" to "fin'ora",
        )) {
            assertWithMessage("it: '$word' must be APPEND-mode, not a replaced alias")
                .that(pairs[word]).containsExactly(elision)
            assertThat(files.getValue("it")).doesNotContainKey(word)
        }

        for ((alias, display) in mapOf(
            "daccordo" to "d'accordo",
            "mama" to "m'ama",
            "lun" to "l'un",
            "cè" to "c'è",
            "nè" to "n'è",
        )) {
            assertWithMessage("it: '$alias' is not an Italian word — it must be REPLACE-mode")
                .that(files.getValue("it")[alias]).isEqualTo(display)
            assertThat(pairs).doesNotContainKey(alias)
        }
    }

    @Test
    fun `the English file lost its one unreachable mapping and nothing else`() {
        // en is audited, not reclassified: its aliases come from the bundled ENGLISH base
        // (`contractions_non_paired.json` + `contraction_pairings.json`), which is loaded
        // FIRST and shadows every key `contractions_en.json` repeats — so moving an entry
        // inside this file would be a no-op. The one change is the dead mapping:
        // "high-falutin" is in no English lexicon in that spelling (the dictionaries carry
        // the solid "highfalutin"), so nothing could ever have looked it up.
        val en = jsonObject("contractions_en.json")
        assertThat(en).hasSize(119)
        assertThat(en).doesNotContainKey("high-falutin")
        assertThat(en["dont"]).isEqualTo("don't")
        assertThat(en["cant"]).isEqualTo("can't")
        assertThat(File("$DICT_DIR/contraction_pairs_en.json").isFile).isFalse()
    }
}
