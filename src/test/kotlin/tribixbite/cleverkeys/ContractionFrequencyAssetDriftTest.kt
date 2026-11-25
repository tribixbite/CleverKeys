package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.swipe.ctc.CtcCkdtLexicon
import tribixbite.cleverkeys.swipe.ctc.CtcContractionKeys
import tribixbite.cleverkeys.swipe.geometric.CkdtDictionaryReader
import java.io.File

/**
 * `contraction_frequencies_<lang>.json` (2026-11-25): a swipe ranks a contraction alias by how
 * often the contraction is written only in a language whose table passed a HUMAN swipe-corpus
 * gate. French elisions (todo item 35, `docs/eval/2026-11-25-elision-own-frequency.md`, FUTO
 * azerty) and English pronoun contractions (todo item 8,
 * `docs/eval/2026-11-25-english-contraction-frequencies.md`, FUTO swipe-1/2/3) passed. Italian
 * has no human corpus, and its in-band ordering (the only variant safe without one) failed its
 * own pre-registered probe, so it ships no table.
 *
 * Project root as CWD (same convention as [AccentSpellingDriftTest]).
 */
class ContractionFrequencyAssetDriftTest {

    private val dictionaries = File("src/main/assets/dictionaries")

    /** Languages whose table passed the human-corpus gate. */
    private val gated = setOf("en", "fr")

    private fun tables(): Map<String, File> =
        (dictionaries.listFiles() ?: emptyArray())
            .mapNotNull { f -> Regex("contraction_frequencies_([a-z]+)\\.json").matchEntire(f.name)?.let { it.groupValues[1] to f } }
            .toMap()

    @Test
    fun `only gated languages ship an elision frequency table`() {
        assertWithMessage("expected ${dictionaries.path} (run from project root)").that(dictionaries.isDirectory).isTrue()
        assertWithMessage("languages with a table").that(tables().keys).isEqualTo(gated)
    }

    @Test
    fun `every table entry is an alias key raised above its lexicon's floor`() {
        for ((lang, file) in tables()) {
            val table = CtcContractionKeys.parseAttestedFrequencies(file.readText())
            assertWithMessage("$lang table is empty").that(table).isNotEmpty()
            // The lexicon the CTC engine builds its trie from: English's JSON, else the CKDT bin.
            val frequencies: Map<String, Double> = if (lang == "en") {
                Regex("^  \"([^\"]+)\": (\\d+),?$", RegexOption.MULTILINE)
                    .findAll(File(dictionaries, "en_enhanced.json").readText())
                    .associate { it.groupValues[1] to it.groupValues[2].toDouble() }
            } else {
                File(dictionaries, "${lang}_enhanced.bin").inputStream()
                    .use { CkdtDictionaryReader.readEntries(it) }
                    .associate { it.word to CtcCkdtLexicon.freqForRank(it.rank) }
            }
            val floor = CtcContractionKeys.derivedFloor(frequencies.values)
            val aliasFiles = if (lang == "en") listOf("contractions_non_paired.json", "contraction_pairings.json")
                else listOf("contractions_$lang.json", "contraction_pairs_$lang.json")
            val aliases = Regex("\"([^\"]+)\"\\s*:").findAll(
                aliasFiles.map { File(dictionaries, it) }.filter { it.isFile }.joinToString("\n") { it.readText() }
            ).mapTo(HashSet()) { it.groupValues[1].lowercase() }
            assertWithMessage("$lang keys that are no alias key").that(table.keys.filter { it !in aliases }).isEmpty()
            // A lexicon-native key (fr `quil`, en `youre`) is raised by the injection, never lowered,
            // so its value must be above its own lexicon frequency to mean anything.
            assertWithMessage("$lang lexicon keys at or below their own lexicon frequency")
                .that(table.filter { (k, v) -> frequencies[k]?.let { v <= it } ?: false }.keys).isEmpty()
            assertWithMessage("$lang values at or below the floor $floor, or above 255")
                .that(table.filterValues { it <= floor || it > 255.0 }.keys).isEmpty()
        }
    }
}
