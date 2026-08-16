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
 * DATA-quality guards for the shipped per-language contraction files
 * (`assets/dictionaries/contractions_<lang>.json`).
 *
 * ### Why a mapping can be dead
 *
 * A contraction file is a pure DISPLAY overlay: the key is the apostrophe-free a–z surface a
 * swipe can produce, the value is what the user is shown ([ContractionOverlay]). The swipe
 * beam only ever emits a word that is IN the active language's bundled lexicon — it walks a
 * trie built from `<lang>_enhanced.bin` projected onto a–z ([CtcAzProjection]) — so a mapping
 * whose key is not a lexicon surface can never fire. It is dead weight in the APK and, worse,
 * it hides the real coverage number behind a large entry count.
 *
 * This class therefore measures, per language, how much of each file is actually REACHABLE,
 * and pins:
 *
 *  1. **de must be 100% live** — it is hand-curated against the bundled dictionary
 *     (2026-08-16), so any future addition that cannot be swiped fails here immediately.
 *  2. **fr/it carry a large pre-existing dead tail** — they were bulk-extracted from the
 *     AnySoftKeyboard wordlists by `scripts/extract_apostrophe_words.py`, which never checked
 *     the key against CleverKeys' own dictionary. The counts are pinned as a CHARACTERIZATION
 *     baseline (see the test's KDoc for the accompanying live-key hazard), so the numbers stay
 *     visible and cannot silently grow.
 *  3. **every value differs from its key by apostrophes/hyphens ONLY** — no accent change, no
 *     other letters. A value that changes anything else is either a typo or a word from
 *     another language, and would put a word on the suggestion bar that the beam never
 *     decoded.
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
        /** Mappings whose key is already a pure a–z surface (a possible beam output). */
        val azKeys: Int,
        /** …of those, the ones that ARE in the bundled lexicon: the reachable mappings. */
        val live: Int,
    ) {
        /** a–z keys the beam can never emit for this language: dead weight. */
        val dead: Int get() = azKeys - live

        /** Keys carrying an accent/digit/`œ`-style character, so never a beam surface. */
        val nonAz: Int get() = entries - azKeys
    }

    private companion object {
        const val DICT_DIR = "src/main/assets/dictionaries"

        /** Languages that bundle BOTH a CKDT dictionary and a contraction file. */
        val LEXICON_LANGUAGES = listOf("de", "es", "fr", "it", "pt", "sv")

        /** language → `contractions_<lang>.json`, keys and values lowercased. */
        lateinit var files: Map<String, Map<String, String>>

        /** language → the a–z surfaces the beam can emit (projected bundled lexicon). */
        lateinit var lexicons: Map<String, Set<String>>

        /** language → a–z surface → the canonical (display) spelling the engine commits. */
        lateinit var canonical: Map<String, Map<String, String>>

        fun jsonObject(name: String): Map<String, String> {
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
            files = (LEXICON_LANGUAGES + "nl" + "id" + "ms" + "tl" + "sw")
                .associateWith { jsonObject("contractions_$it.json") }

            val lex = HashMap<String, Set<String>>()
            val canon = HashMap<String, Map<String, String>>()
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
            }
            lexicons = lex
            canonical = canon
        }
    }

    private fun coverageOf(language: String): Coverage {
        val file = files.getValue(language)
        val lexicon = lexicons.getValue(language)
        val az = file.keys.filter { CtcAzProjection.project(it) == it }
        return Coverage(
            entries = file.size,
            azKeys = az.size,
            live = az.count { it in lexicon },
        )
    }

    // ── 1. the dead-data guard ──────────────────────────────────────────────────────

    @Test
    fun `every German contraction maps a word the beam can actually emit`() {
        // The curated file: 100% reachable, no exceptions. This is the guard that makes a
        // future "just add more forms" edit prove itself against the shipped dictionary
        // instead of shipping mappings that can never fire.
        val de = coverageOf("de")
        assertThat(de.nonAz).isEqualTo(0)
        assertWithMessage(
            "contractions_de.json is hand-curated against de_enhanced.bin: every key must be " +
                "a German lexicon surface, or the mapping is dead weight in the APK"
        ).that(de.dead).isEqualTo(0)
        assertThat(de.live).isEqualTo(de.entries)

        // Name the dead keys, not just the count, when this ever breaks.
        val lexicon = lexicons.getValue("de")
        assertThat(files.getValue("de").keys.filter { it !in lexicon }).isEmpty()
    }

    @Test
    fun `the bulk-extracted French and Italian files carry a pinned dead tail`() {
        // CHARACTERIZATION, not an endorsement. `scripts/extract_apostrophe_words.py` lifted
        // every apostrophe token out of the AnySoftKeyboard wordlists without checking the
        // apostrophe-free key against CleverKeys' own dictionary, so ~99% of both files can
        // never fire. Worse, part of the live remainder is actively harmful: the key is a
        // COMMON word of the same language whose rank is past ContractionOverlay's
        // REAL_WORD_ORDINAL_MAX, so the overlay REPLACES it — fr "lune" → "l'une",
        // "larme" → "l'arme", "davantage" → "d'avantage"; it "lago" → "l'ago",
        // "luna" → "l'una". Curating that list is a separate, product-owner decision
        // (2026-08-16); these numbers exist so it cannot be forgotten and cannot grow.
        val fr = coverageOf("fr")
        assertThat(fr.entries).isEqualTo(27494)
        assertThat(fr.nonAz).isEqualTo(9413)
        assertThat(fr.live).isEqualTo(206)
        assertThat(fr.dead).isEqualTo(17875)

        val it = coverageOf("it")
        assertThat(it.entries).isEqualTo(22474)
        assertThat(it.nonAz).isEqualTo(1117)
        assertThat(it.live).isEqualTo(116)
        assertThat(it.dead).isEqualTo(21241)
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
        for ((language, file) in files) {
            for ((key, value) in file) {
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
}
