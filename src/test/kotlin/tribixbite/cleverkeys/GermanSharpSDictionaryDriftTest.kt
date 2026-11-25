package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.swipe.ctc.CtcAzProjection
import tribixbite.cleverkeys.swipe.ctc.CtcCkdtLexicon
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconMerge
import tribixbite.cleverkeys.swipe.geometric.CkdtDictionaryReader
import java.io.File

/**
 * The shipped German dictionary and next-word model spell `ß` as Germany and Austria write it
 * (2026-11-25, maintainer: "rebuild the german dictionary with ß").
 *
 * wordfreq casefolds `ß` to `ss`, so until then `de_enhanced.bin` held `strasse`, `gross`,
 * `weiss` and no `ß` word at all: a swipe showed `Strasse`, a tapped `groß` was no word (space
 * committed `gros`, fixed first by `WordPredictor.sharpSDictionaryKey`), and `de.cklm` counted no
 * `ß` token of its corpora. `scripts/sharp_s.py` now respells the stream before classification:
 * LibreOffice's de_DE_frami decides each `ss`, the Leipzig German web 2021 counts choose between
 * readings and split the frequency of two real words (`Masse`/`Maße`).
 *
 * Reads the assets from the project root (same convention as [AccentSpellingDriftTest]).
 */
class GermanSharpSDictionaryDriftTest {

    private val entries: List<CkdtDictionaryReader.Entry> by lazy {
        val f = File("src/main/assets/dictionaries/de_enhanced.bin")
        check(f.isFile) { "expected ${f.path} (run from project root)" }
        f.inputStream().use { CkdtDictionaryReader.readEntries(it) }
    }
    private val lexicon: Set<String> by lazy { entries.mapTo(HashSet()) { it.word } }

    /** Germany's spelling → the casefolded (and Swiss) spelling the list must not hold. */
    private val sharpS = mapOf(
        "straße" to "strasse", "weiß" to "weiss", "groß" to "gross", "große" to "grosse",
        "größe" to "grösse", "heißt" to "heisst", "außerdem" to "ausserdem", "fuß" to "fuss",
        "spaß" to "spass", "schließlich" to "schliesslich", "regelmäßig" to "regelmässig",
        "mäßig" to "mässig",
    )

    /** The 1996 reform writes these with `ss`; their old `ß` spellings stay out. */
    private val reformedSs = mapOf(
        "dass" to "daß", "muss" to "muß", "fluss" to "fluß", "schloss" to "schloß",
        "prozess" to "prozeß",
    )

    /** Two real words on one a–z surface: both stay (`Masse` mass, `Maße` measures). */
    private val pairs = mapOf("masse" to listOf("maße", "masse"), "busse" to listOf("busse", "buße"))

    @Test
    fun `the list spells sharp s where Germany does, and not its ss twin`() {
        assertWithMessage("ß spellings missing").that(sharpS.keys.filter { it !in lexicon }).isEmpty()
        assertWithMessage("casefolded ss spellings still present — re-run " +
            "`python3 scripts/build_wordlist.py --lang de --write`")
            .that(sharpS.values.filter { it in lexicon }).isEmpty()
    }

    @Test
    fun `reformed ss words stay and their old spellings stay out`() {
        assertWithMessage("reformed ss words missing").that(reformedSs.keys.filter { it !in lexicon }).isEmpty()
        assertWithMessage("old ß spellings present").that(reformedSs.values.filter { it in lexicon }).isEmpty()
        for ((_, both) in pairs) {
            assertWithMessage("both words of $both").that(both.filter { it !in lexicon }).isEmpty()
        }
    }

    @Test
    fun `a swipe shows sharp s, and both words of a real pair`() {
        // CtcEngineAdapter's lexicon for a CKDT language: rank → frequency, merge, a–z projection.
        val p = CtcAzProjection.projectLexicon(
            CtcLexiconMerge.merge(CtcCkdtLexicon.frequencyPairs(entries), emptyList(), emptySet())
        )
        fun forms(surface: String) =
            listOfNotNull(p.display[surface] ?: surface.takeIf { it in p.freqs }) +
                p.alternates[surface].orEmpty().map { it.word }
        // Contains, not exactly: a surface may hold two real words (`grosse` is große and größe).
        for ((word, ss) in sharpS) {
            val surface = CtcAzProjection.project(word)!!  // straße → strasse, größe → grosse
            assertWithMessage("swipe '$surface'").that(forms(surface)).contains(word)
            assertWithMessage("swipe '$surface'").that(forms(surface)).doesNotContain(ss)
        }
        for ((surface, both) in pairs) {
            assertWithMessage("swipe '$surface'").that(forms(surface)).containsExactlyElementsIn(both)
        }
    }

    @Test
    fun `the next-word model names the sharp s spellings and not the casefolded ones`() {
        val lm = StaticContextLm.parse(StaticLmLanguageData.asset("de").readBytes())
        for (word in listOf("weiß", "weißt", "groß", "große", "heißt", "außerdem", "straße")) {
            assertWithMessage("de.cklm names $word").that(lm.contains(word)).isTrue()
        }
        assertWithMessage("de.cklm names casefolded spellings")
            .that(sharpS.values.filter { lm.contains(it) } + listOf("weisst").filter { lm.contains(it) }).isEmpty()
    }
}
