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

    /**
     * Pre-tag review 2026-11-25 S5: a PAIRED key is a word of its own (`dune` beside `d'une`); the
     * overlay keeps that word in the key's slot and puts the elision at the tail, so a raise
     * boosted `dune` (99 → 204), not `d'une`. Corrected and gated in
     * `docs/eval/2026-11-25-contraction-table-cleanup.md`.
     */
    @Test
    fun `no table raises a paired key`() {
        for ((lang, file) in tables()) {
            val pairs = File(dictionaries, "contraction_pairs_$lang.json")
            if (!pairs.isFile) continue
            val paired = Regex("\"([^\"]+)\"\\s*:\\s*\\[").findAll(pairs.readText())
                .mapTo(HashSet()) { it.groupValues[1].lowercase() }
            assertWithMessage("$lang paired keys (${paired.size}) parsed").that(paired).isNotEmpty()
            val table = CtcContractionKeys.parseAttestedFrequencies(file.readText())
            assertWithMessage("$lang table keys that are paired keys")
                .that(table.keys.filter { it in paired }).isEmpty()
        }
    }

    /**
     * The parser takes a–z keys only; a key with another letter (`dêtre`, `dévaluation`) was in
     * the asset and silently dropped. The builders leave such keys out, so the file says what the
     * app reads.
     */
    @Test
    fun `every table key is one the app reads`() {
        for ((lang, file) in tables()) {
            val text = file.readText()
            val written = Regex("\"([^\"]+)\"\\s*:").findAll(text).map { it.groupValues[1] }.toList()
            assertWithMessage("$lang keys the parser drops")
                .that(written.filter { it !in CtcContractionKeys.parseAttestedFrequencies(text) }).isEmpty()
        }
    }

    /**
     * S4: wordfreq splits these display forms into two tokens (`d'art` → `d` + `art`), so its
     * "frequency" was a phrase estimate (`dart` 176 → 205, `yall` 170 → 204), not the word's.
     */
    @Test
    fun `english keys whose display wordfreq splits are not raised`() {
        val table = CtcContractionKeys.parseAttestedFrequencies(
            File(dictionaries, "contraction_frequencies_en.json").readText()
        )
        val split = listOf("dart", "yall", "maam", "oer", "neer", "detat", "doeuvre", "dazur", "detre", "darezzo", "destaing")
        assertWithMessage("English two-token keys in the table").that(table.keys.filter { it in split }).isEmpty()
        assertWithMessage("…while the single-token pronoun contractions stay").that(table.keys)
            .containsAtLeast("youre", "theyre", "ive", "dont")
    }

    /**
     * S6: one or two corpus hits calibrate to 75, the bottom of the French scale, above the 7,098
     * real words at 68–74 (5,702 keys sat there, `l'x` and `l'ob` among them). A raise needs
     * three hits (`build_elision_frequencies.MIN_COUNT`), which calibrate to 76 and up.
     */
    @Test
    fun `french elisions seen once or twice are not raised`() {
        val table = CtcContractionKeys.parseAttestedFrequencies(
            File(dictionaries, "contraction_frequencies_fr.json").readText()
        )
        assertWithMessage("French values at the one- and two-hit calibration (75)")
            .that(table.filterValues { it <= 75.0 }.keys).isEmpty()
        assertWithMessage("junk keys").that(table.keys.filter { it in setOf("lx", "lob") }).isEmpty()
        assertWithMessage("…while d'être and qu'il keep their raise").that(table["detre"]).isEqualTo(168.0)
        assertWithMessage("qu'il").that(table["quil"]).isEqualTo(188.0)
    }
}
