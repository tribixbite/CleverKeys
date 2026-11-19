package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.BeforeClass
import org.junit.Test
import tribixbite.cleverkeys.swipe.ctc.CtcAzProjection
import tribixbite.cleverkeys.swipe.ctc.CtcCkdtLexicon
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconMerge
import tribixbite.cleverkeys.swipe.geometric.CkdtDictionaryReader
import java.io.File

/**
 * Italian accent misspellings stay out of every shipped Italian asset (maintainer, 2026-11-19:
 * "fix the Italian unaccented words").
 *
 * wordfreq counts the web's unaccented typing (`perche`, `piu`, `citta`) and its wrong accents
 * (`perchè`, `pò`) as words of their own, so the classifier kept them beside `perché`, `più`,
 * `città`. A swipe then offered the misspelling as a second form, a typed `perche` was a
 * dictionary word that nothing corrected, and the static next-word model named both spellings.
 * The ASK elision lists added `qual'è` (the standard spelling is `qual è`) and a few masculine
 * `un'` forms.
 *
 * The source of truth is `scripts/dictionaries/it/it_blocklist.txt` (each entry with its correct
 * twin after `#`) and `MISSPELLED_DISPLAY` in `scripts/extract_apostrophe_words.py`; this test
 * reads the blocklist itself, so a word added there and not regenerated into the assets fails
 * here. It also pins the collisions that are real words and must stay, so an over-eager
 * blocklist fails too. Evidence and the keep/drop table:
 * `docs/eval/2026-11-19-italian-accent-spellings.md`.
 *
 * Project root as CWD (same convention as [StaticLmAssetDriftTest]).
 */
class ItalianAccentSpellingDriftTest {

    companion object {
        private val DICT_DIR = File("src/main/assets/dictionaries")
        private val BLOCKLIST = File("scripts/dictionaries/it/it_blocklist.txt")
        private val GENERATOR = File("scripts/extract_apostrophe_words.py")

        /** The six misspelled elisions `MISSPELLED_DISPLAY["it"]` removes. */
        val MISSPELLED_ELISIONS = listOf(
            "qual'è", "qual'era", "un'altrettanto", "un'appassionato", "un'importate", "un'insegnate",
        )

        /**
         * Words that collide with an accented twin and are REAL, so they stay: function words
         * and verb forms (si/sì, lascio/lasciò), nouns (papa/papà, pero/però), the names `sara`
         * and `fara` (the lexicon is lowercase-only and autocorrect keeps the typed capital, so
         * dropping them would turn "Sara" into "Sarà"), the acute í/ú convention (`piú`), and
         * `cè`/`sè`/`nè`/`chè`, the keys of the c'è/s'è/n'è/ch'è elision entries.
         */
        val KEPT = listOf(
            "si", "sì", "la", "là", "ne", "né", "se", "sé", "da", "dà", "di", "dì",
            "te", "tè", "li", "lì", "papa", "papà", "pero", "però", "cosi", "così", "meta", "metà",
            "unita", "unità", "lascio", "lasciò", "sara", "sarà", "fara", "farà", "po", "dò",
            "piú", "cè", "sè", "nè", "chè", "vabbè",
        )

        /** word → its standard spelling, as `it_blocklist.txt` records it. */
        private lateinit var blocked: Map<String, String>
        private lateinit var lexicon: Set<String>
        private lateinit var lexiconEntries: List<CkdtDictionaryReader.Entry>

        @JvmStatic
        @BeforeClass
        fun load() {
            check(BLOCKLIST.isFile) { "expected ${BLOCKLIST.path} (run from project root)" }
            val out = LinkedHashMap<String, String>()
            for (line in BLOCKLIST.readLines()) {
                val word = line.substringBefore('#').trim()
                if (word.isEmpty()) continue
                // "word   # twin (note)": the first token after '#' is the standard spelling.
                out[word] = line.substringAfter('#').trim().substringBefore(' ')
            }
            blocked = out
            lexiconEntries = File(DICT_DIR, "it_enhanced.bin").inputStream()
                .use { CkdtDictionaryReader.readEntries(it) }
            lexicon = lexiconEntries.mapTo(HashSet()) { it.word }
        }

        private fun lm(): StaticContextLm =
            StaticContextLm.parse(StaticLmLanguageData.asset("it").readBytes())
    }

    @Test
    fun `the blocklist records 78 misspellings, each with its standard spelling`() {
        assertThat(blocked).hasSize(78)
        for ((word, twin) in blocked) {
            assertWithMessage("blocklist entry '$word' names no different standard spelling")
                .that(twin).isNotEqualTo(word)
            assertWithMessage("blocklist entry '$word' names '$twin', which is itself blocked")
                .that(blocked).doesNotContainKey(twin)
        }
        assertThat(blocked).containsEntry("perche", "perché")
        assertThat(blocked).containsEntry("pò", "po'")
    }

    @Test
    fun `the italian dictionary holds no blocked misspelling`() {
        val present = blocked.keys.filter { it in lexicon }
        assertWithMessage("it_enhanced.bin still holds blocked spellings — re-run " +
            "`python3 scripts/build_wordlist.py --lang it --write`").that(present).isEmpty()
        for (word in listOf("perche", "piu", "puo", "citta", "gia", "attivita", "perchè", "pò", "quì")) {
            assertWithMessage("it_enhanced.bin holds '$word'").that(lexicon).doesNotContain(word)
        }
    }

    @Test
    fun `every standard spelling stays, and so do the real words that collide`() {
        // The twin `po'` is an elision, not a dictionary word: its apostrophe-free key `po` is.
        val twins = blocked.values.map { it.removeSuffix("'") }.toSet()
        assertWithMessage("standard spellings missing from it_enhanced.bin")
            .that(twins.filter { it !in lexicon }).isEmpty()
        assertWithMessage("real words dropped from it_enhanced.bin")
            .that(KEPT.filter { it !in lexicon }).isEmpty()
    }

    @Test
    fun `a typed unaccented word finds only the accented spelling`() {
        // The tap predictor's accent index: a typed word is looked up by its accent-free key.
        val index = NormalizedPrefixIndex(language = "it", indexPrefixes = false)
        for (e in lexiconEntries) index.addWord(e.word, e.rank)
        fun forms(typed: String) = index.lookup(index.normalizeKey(typed))?.canonicals.orEmpty()
        assertThat(forms("perche")).containsExactly("perché")
        assertThat(forms("citta")).containsExactly("città")
        assertThat(forms("puo")).containsExactly("può")
        assertThat(forms("piu")).containsExactly("più", "piú")
        assertThat(forms("pero")).containsExactly("pero", "però")
        assertThat(forms("sara")).containsExactly("sara", "sarà")
    }

    @Test
    fun `a swipe offers no misspelled second form`() {
        // CtcEngineAdapter's lexicon for a CKDT language: rank → frequency, merge, a–z projection.
        val p = CtcAzProjection.projectLexicon(
            CtcLexiconMerge.merge(CtcCkdtLexicon.frequencyPairs(lexiconEntries), emptyList(), emptySet())
        )
        fun forms(surface: String) =
            listOfNotNull(p.display[surface] ?: surface.takeIf { it in p.freqs }) +
                p.alternates[surface].orEmpty().map { it.word }
        assertThat(forms("perche")).containsExactly("perché")
        assertThat(forms("citta")).containsExactly("città")
        assertThat(forms("attivita")).containsExactly("attività")
        assertThat(forms("po")).containsExactly("po")
        assertThat(forms("pero")).containsExactly("però", "pero")
    }

    @Test
    fun `the italian elision tables offer no misspelled elision`() {
        val replace = JSONObject(File(DICT_DIR, "contractions_it.json").readText())
        val pairs = JSONObject(File(DICT_DIR, "contraction_pairs_it.json").readText())
        val values = HashSet<String>()
        for (k in replace.keys()) values.add(replace.getString(k))
        for (k in pairs.keys()) {
            val forms: JSONArray = pairs.getJSONArray(k)
            for (i in 0 until forms.length()) values.add(forms.getString(i))
        }
        assertWithMessage("misspelled elisions still offered")
            .that(MISSPELLED_ELISIONS.filter { it in values }).isEmpty()
        assertWithMessage("`quale` is a word: no PAIRED entry offers qual'è beside it")
            .that(pairs.has("quale")).isFalse()
        // The correct neighbours stay.
        for ((key, form) in listOf(
            "unaltra" to "un'altra", "unaltrettanta" to "un'altrettanta",
            "unimportante" to "un'importante", "uninsegnante" to "un'insegnante",
        )) {
            assertWithMessage("contractions_it.json $key").that(replace.optString(key)).isEqualTo(form)
        }
    }

    @Test
    fun `the generator drops every misspelled elision on regeneration`() {
        // MISSPELLED_DISPLAY is what keeps the ASK extraction from bringing them back.
        val source = GENERATOR.readText()
        val table = source.substringAfter("MISSPELLED_DISPLAY = {").substringBefore("\n}\n")
        for (form in MISSPELLED_ELISIONS) {
            assertWithMessage("MISSPELLED_DISPLAY[it] lacks $form").that(table).contains("\"$form\"")
        }
    }

    @Test
    fun `the italian next-word model names no blocked spelling`() {
        val lm = lm()
        assertWithMessage("it.cklm names blocked spellings")
            .that(blocked.keys.filter { lm.contains(it) }).isEmpty()
        assertWithMessage("it.cklm names misspelled elisions")
            .that(MISSPELLED_ELISIONS.filter { lm.contains(it) }).isEmpty()
        for (word in listOf("perché", "più", "può", "città", "già", "attività")) {
            assertWithMessage("it.cklm names $word").that(lm.contains(word)).isTrue()
        }
    }
}
