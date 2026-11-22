package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.swipe.ctc.CtcAzProjection
import tribixbite.cleverkeys.swipe.ctc.CtcCkdtLexicon
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconMerge
import tribixbite.cleverkeys.swipe.geometric.CkdtDictionaryReader
import java.io.File

/**
 * Accent misspellings stay out of the shipped dictionaries and next-word models of the languages
 * after Italian (2026-11-20; Italian has its own [ItalianAccentSpellingDriftTest], the method's
 * first run). Languages: fr, es, pt, de (2026-11-20) and sv (2026-11-22,
 * `docs/eval/2026-11-22-swedish-accent-spellings.md`).
 *
 * wordfreq counts the web's unaccented typing (`francais`, `deja`, `etre`) as words of their
 * own, so the word-list classifier kept them beside `français`, `déjà`, `être`. Since the CTC
 * collision alternates (2026-11-18) a swipe offered the misspelling as a second form (the full
 * instrumented run of 2026-11-20 caught `francais` in the French slate), a typed `francais` was
 * a dictionary word that nothing corrected, and the next-word model could name it.
 *
 * Source of truth: `scripts/dictionaries/<lang>/<lang>_blocklist.txt`, each entry followed by
 * `#` and its accepted spelling(s), found by `scripts/accent_twin_audit.py` and reviewed (the
 * keep decisions and their reasons: `docs/eval/2026-11-20-accent-spellings.md`, and the Swedish
 * note for sv). This test reads
 * the blocklist itself, so an entry added there and not regenerated into the assets fails here,
 * and it pins words that collide and are real, so an over-eager blocklist fails too.
 *
 * Project root as CWD (same convention as [StaticLmAssetDriftTest]).
 */
class AccentSpellingDriftTest {

    /**
     * One language's expectations.
     *
     * @property blockedCount entries in its blocklist.
     * @property misspellings spellings the audit found and the blocklist drops, checked by name
     *   (the blocklist count already covers the rest).
     * @property kept colliding words that are real or were kept on review: they stay.
     * @property singleForm typed or swiped surface → the one form a user is offered.
     * @property bothForms surface → the forms of a real pair, all offered.
     * @property modelNames accented words the next-word model must still name; empty for a
     *   language that ships no model (es: its LM stays unshipped).
     */
    private class Lang(
        val code: String,
        val blockedCount: Int,
        val misspellings: List<String>,
        val kept: List<String>,
        val singleForm: Map<String, String>,
        val bothForms: Map<String, List<String>>,
        val modelNames: List<String>,
    ) {
        val blocklist = File("scripts/dictionaries/$code/${code}_blocklist.txt")

        /** word → its accepted spellings, as the blocklist records them (`word  # a/b`). */
        val blocked: Map<String, List<String>> by lazy {
            check(blocklist.isFile) { "expected ${blocklist.path} (run from project root)" }
            blocklist.readLines().mapNotNull { line ->
                val word = line.substringBefore('#').trim()
                if (word.isEmpty()) null
                else word to line.substringAfter('#').trim().substringBefore(' ').split('/')
            }.toMap()
        }

        val entries: List<CkdtDictionaryReader.Entry> by lazy {
            File("src/main/assets/dictionaries/${code}_enhanced.bin").inputStream()
                .use { CkdtDictionaryReader.readEntries(it) }
        }

        val lexicon: Set<String> by lazy { entries.mapTo(HashSet()) { it.word } }
    }

    private val languages = listOf(
        Lang(
            code = "fr",
            blockedCount = 313,
            misspellings = listOf(
                "francais", "francaise", "deja", "etre", "ete", "etait", "tres", "apres", "meme",
                "probleme", "ecole", "cafe", "hotel", "video", "education", "montreal", "quebec",
                "ca", "trés", "déja", "aprés", "réglement", "théatre", "celà", "oú",
            ),
            kept = listOf(
                // Real words that collide.
                "la", "là", "ou", "où", "du", "dû", "sur", "sûr", "des", "dès", "mur", "mûr",
                "tache", "tâche", "cote", "côté", "age", "âge", "aine", "aîné",
                // The 1990 reform spellings are correct (the oracle is "toutes variantes").
                "apparaitre", "aout", "connaitre", "maitre", "boite",
                // Kept on review: words French text uses as they are, abbreviations, fragments,
                // and names that exist unaccented in other languages or are spelled their own way.
                "the", "he", "email", "edit", "real", "beta", "metal", "museum", "arena", "ref",
                "pêle", "aigues", "indé", "adele", "felix", "noel", "zoe", "kévin",
            ),
            singleForm = mapOf("francais" to "français", "deja" to "déjà", "etre" to "être", "tres" to "très"),
            bothForms = mapOf("ou" to listOf("ou", "où"), "la" to listOf("la", "là")),
            modelNames = listOf("français", "déjà", "être", "été", "très", "après"),
        ),
        Lang(
            code = "es",
            blockedCount = 668,
            misspellings = listOf(
                "tambien", "despues", "segun", "ningun", "aqui", "asi", "dia", "pais", "policia",
                "mexico", "peru", "queres", "chevere", "fué", "dió", "jóven", "exámen", "díficil",
            ),
            kept = listOf(
                "mas", "más", "esta", "está", "ésta", "tu", "tú", "el", "él", "si", "sí", "se", "sé",
                "solo", "sólo", "closet", "comic", "poster", "garcia", "lopez", "martin", "idem",
                "coctel", "podés", "guión", "taiwán",
            ),
            singleForm = mapOf("tambien" to "también", "despues" to "después", "segun" to "según", "aqui" to "aquí"),
            bothForms = mapOf("esta" to listOf("esta", "está", "ésta"), "tu" to listOf("tu", "tú")),
            modelNames = emptyList(),
        ),
        Lang(
            code = "pt",
            blockedCount = 160,
            misspellings = listOf("nao", "voce", "voces", "tambem", "entao", "ja", "so", "mae", "saude", "familia", "brasilia"),
            kept = listOf(
                "esta", "está", "pais", "país", "avó", "avô",
                // The pre-2009 orthography, names and loanwords kept on review.
                "idéia", "freqüente", "vôo", "antonio", "junior", "luiza", "taxi", "karate",
            ),
            singleForm = mapOf("nao" to "não", "voce" to "você", "tambem" to "também", "entao" to "então"),
            bothForms = mapOf("esta" to listOf("esta", "está"), "pais" to listOf("pais", "país")),
            modelNames = listOf("não", "você", "também", "então", "já"),
        ),
        Lang(
            code = "de",
            blockedCount = 4,
            misspellings = listOf("fur", "munchen", "uber", "ol"),
            kept = listOf(
                // `wahrend` is a real word (wahren, to preserve), so it stays beside `während`.
                "schon", "schön", "hatte", "hätte", "ware", "wäre", "wahrend", "während",
                "blatter", "bose", "burger", "hä", "wär", "mässig",
            ),
            singleForm = mapOf("fur" to "für", "munchen" to "münchen", "uber" to "über"),
            bothForms = mapOf("schon" to listOf("schon", "schön"), "hatte" to listOf("hatte", "hätte")),
            modelNames = listOf("für", "über", "schön", "während"),
        ),
        // 2026-11-22 (docs/eval/2026-11-22-swedish-accent-spellings.md).
        Lang(
            code = "sv",
            blockedCount = 33,
            misspellings = listOf(
                "ocksa", "maste", "aven", "tva", "pa", "over", "fran", "gar", "goteborg", "malmo",
                "endå", "löfvén", "muséet", "kvalite", "entre",
            ),
            kept = listOf(
                // Real words that collide.
                "har", "här", "hår", "for", "för", "hall", "håll", "las", "läs", "lås",
                // Kept on review: English words Swedish text uses as they are, abbreviations and
                // agency names, names spelled their owner's way, colloquial and dialect forms,
                // and the common supine `hållt`.
                "are", "on", "at", "lost", "save", "stand", "popular", "brå", "öb", "nas", "aka", "mar",
                "francois", "jose", "kruger", "ramón", "sånna", "äre", "åsså", "dä", "hä", "jå", "mä",
                "gö", "pöjk", "ba", "hållt",
            ),
            singleForm = mapOf(
                "ocksa" to "också", "maste" to "måste", "tva" to "två", "over" to "över", "malmo" to "malmö",
            ),
            bothForms = mapOf(
                "har" to listOf("har", "här", "hår"), "for" to listOf("for", "för"), "on" to listOf("on", "ön"),
            ),
            modelNames = listOf("också", "måste", "även", "två", "på", "över", "från", "går"),
        ),
    )

    @Test
    fun `each blocklist records its misspellings, each with an accepted spelling that is not blocked`() {
        for (l in languages) {
            assertWithMessage("${l.code} blocklist size").that(l.blocked).hasSize(l.blockedCount)
            for ((word, twins) in l.blocked) {
                assertWithMessage("${l.code} entry '$word' names no accepted spelling").that(twins).isNotEmpty()
                assertWithMessage("${l.code} entry '$word' names itself").that(twins).doesNotContain(word)
                assertWithMessage("${l.code} entry '$word' names a blocked spelling")
                    .that(twins.filter { it in l.blocked }).isEmpty()
            }
            assertWithMessage("${l.code} named misspellings missing from the blocklist")
                .that(l.misspellings.filter { it !in l.blocked }).isEmpty()
        }
    }

    @Test
    fun `no dictionary holds a blocked spelling, and every accepted spelling and kept word stays`() {
        for (l in languages) {
            assertWithMessage("${l.code}_enhanced.bin still holds blocked spellings — re-run " +
                "`python3 scripts/build_wordlist.py --lang ${l.code} --write`")
                .that(l.blocked.keys.filter { it in l.lexicon }).isEmpty()
            assertWithMessage("${l.code} accepted spellings missing from the dictionary")
                .that(l.blocked.values.flatten().toSet().filter { it !in l.lexicon }).isEmpty()
            assertWithMessage("${l.code} real or kept words dropped from the dictionary")
                .that(l.kept.filter { it !in l.lexicon }).isEmpty()
        }
    }

    @Test
    fun `a typed unaccented word finds only the accented spelling`() {
        for (l in languages) {
            // The tap predictor's accent index: a typed word is looked up by its accent-free key.
            val index = NormalizedPrefixIndex(language = l.code, indexPrefixes = false)
            for (e in l.entries) index.addWord(e.word, e.rank)
            fun forms(typed: String) = index.lookup(index.normalizeKey(typed))?.canonicals.orEmpty()
            for ((typed, form) in l.singleForm) {
                assertWithMessage("${l.code} typed '$typed'").that(forms(typed)).containsExactly(form)
            }
            for ((typed, both) in l.bothForms) {
                assertWithMessage("${l.code} typed '$typed'").that(forms(typed)).containsExactlyElementsIn(both)
            }
        }
    }

    @Test
    fun `a swipe offers no misspelled second form`() {
        for (l in languages) {
            // CtcEngineAdapter's lexicon for a CKDT language: rank → frequency, merge, a–z projection.
            val p = CtcAzProjection.projectLexicon(
                CtcLexiconMerge.merge(CtcCkdtLexicon.frequencyPairs(l.entries), emptyList(), emptySet())
            )
            fun forms(surface: String) =
                listOfNotNull(p.display[surface] ?: surface.takeIf { it in p.freqs }) +
                    p.alternates[surface].orEmpty().map { it.word }
            for ((surface, form) in l.singleForm) {
                assertWithMessage("${l.code} swipe '$surface'").that(forms(surface)).containsExactly(form)
            }
            for ((surface, both) in l.bothForms) {
                assertWithMessage("${l.code} swipe '$surface'").that(forms(surface)).containsExactlyElementsIn(both)
            }
        }
    }

    @Test
    fun `no next-word model names a blocked spelling`() {
        for (l in languages.filter { it.modelNames.isNotEmpty() }) {
            val lm = StaticContextLm.parse(StaticLmLanguageData.asset(l.code).readBytes())
            assertWithMessage("${l.code}.cklm names blocked spellings")
                .that(l.blocked.keys.filter { lm.contains(it) }).isEmpty()
            for (word in l.modelNames) {
                assertWithMessage("${l.code}.cklm names $word").that(lm.contains(word)).isTrue()
            }
        }
    }
}
