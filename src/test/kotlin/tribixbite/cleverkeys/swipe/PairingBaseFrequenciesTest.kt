package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.JsonParser
import org.junit.BeforeClass
import org.junit.Test
import tribixbite.cleverkeys.ContractionManager
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconMerge
import java.io.File

/**
 * Geometric ↔ CTC promotion parity for [ContractionOverlay] (2026-09-26).
 *
 * The overlay puts a paired variant AHEAD of its base (`i'd` over `id`) only when its pairing
 * frequency beats the base's lexicon frequency by [ContractionOverlay.PROMOTION_MARGIN], both on
 * `en_enhanced.json`'s byte scale. The CTC adapter reads the base frequency from its own merged
 * lexicon; the geometric adapter decodes against the CKDT (ranks, not bytes) and used to pass
 * none, so it spliced but never promoted. It now derives the same numbers from the same asset
 * via [PairingBaseFrequencies.fromEnLexiconJson]. This class pins:
 *
 *  - the two derivations agree for EVERY base with a pairing frequency, with and without user
 *    words (custom-word calibration and disabled words included);
 *  - the overlay therefore places every shipped projection pair identically on both engines;
 *  - both adapters are actually wired through the shared helper (source pin — the adapters are
 *    Android classes and cannot run here).
 */
class PairingBaseFrequenciesTest {

    companion object {
        private const val DICT_DIR = "src/main/assets/dictionaries"
        private const val SWIPE_SRC = "src/main/kotlin/tribixbite/cleverkeys/swipe"

        private lateinit var lexiconJson: String

        /** The CTC route's base pairs: a JSON-object parse in file order, like org.json. */
        private lateinit var ctcBasePairs: List<Pair<String, Double>>

        private lateinit var pairings: Map<String, List<ContractionManager.PairedVariant>>

        /** Mirrors `ContractionManager.getPairedFrequencyBases` for the English load. */
        private lateinit var bases: Set<String>

        @JvmStatic
        @BeforeClass
        fun load() {
            val file = File("$DICT_DIR/en_enhanced.json")
            check(file.isFile) { "expected shipped lexicon at ${file.path} (run from project root)" }
            lexiconJson = file.readText()
            ctcBasePairs = JsonParser.parseString(lexiconJson).asJsonObject.entrySet()
                .map { (word, freq) -> word to freq.asInt.toDouble() }
            pairings = ContractionManager.parsePairings(
                File("$DICT_DIR/contraction_pairings.json").readText()
            )
            bases = pairings.filterValues { vs -> vs.any { it.frequency != null } }.keys
        }
    }

    /** The CTC adapter's derivation: merged lexicon → [PairingBaseFrequencies.select]. */
    private fun ctcRoute(custom: List<Pair<String, Int>>, disabled: Set<String>) =
        PairingBaseFrequencies.select(CtcLexiconMerge.merge(ctcBasePairs, custom, disabled), bases)

    /** The geometric adapter's derivation. */
    private fun geoRoute(custom: List<Pair<String, Int>>, disabled: Set<String>) =
        PairingBaseFrequencies.fromEnLexiconJson(lexiconJson, custom, disabled, bases)

    @Test
    fun `geometric and CTC derive identical base frequencies over the shipped lexicon`() {
        val t0 = System.nanoTime()
        val geo = geoRoute(emptyList(), emptySet())
        val ms = (System.nanoTime() - t0) / 1e6
        println("[pairing-base-freq] geometric derivation %.1f ms, %d bases".format(ms, geo.size))

        assertThat(geo).isEqualTo(ctcRoute(emptyList(), emptySet()))
        // 1,731 of the pairing file's bases are lexicon words (the rest are injected keys).
        assertThat(geo.size).isAtLeast(1700)
        // The values CtcContractionDisplayTest's pronoun table is built on.
        assertThat(geo["id"]).isEqualTo(196)
        assertThat(geo["ill"]).isEqualTo(198)
        assertThat(geo["wed"]).isEqualTo(178)
        assertThat(geo["shed"]).isEqualTo(189)
        assertThat(geo["its"]).isEqualTo(225)
        assertThat(geo["well"]).isEqualTo(223)
        assertThat(geo["whys"]).isEqualTo(157)
    }

    @Test
    fun `user words reach both derivations identically`() {
        // A custom "shed" at the stored maximum is calibrated onto the base scale (-> 255), and
        // a disabled "id" has no base frequency at all (-> never promotes) — on both engines.
        val custom = listOf("shed" to 255, "zzcustom" to 100)
        val disabled = setOf("id")
        val geo = geoRoute(custom, disabled)
        assertThat(geo).isEqualTo(ctcRoute(custom, disabled))
        assertThat(geo["shed"]).isEqualTo(255)
        assertThat(geo).doesNotContainKey("id")
    }

    @Test
    fun `the overlay places every shipped projection pair identically on both engines`() {
        val ctc = ctcRoute(emptyList(), emptySet())
        val geo = geoRoute(emptyList(), emptySet())
        fun slate(base: String, baseFreq: Map<String, Int>) = ContractionOverlay.apply(
            listOf(base, "zzfill"), listOf(900, 100),
            pairedVariants = { b -> pairings[b]?.map { it.contraction } },
            nonPairedMapping = { null },
            wordOrdinal = { null },
            pairedVariantFrequency = { b, v -> pairings[b]?.firstOrNull { it.contraction == v }?.frequency },
            baseFrequency = { baseFreq[it] },
        ).first
        var promoted = 0
        for (base in bases) {
            val onGeo = slate(base, geo)
            assertWithMessage("swiped '$base'").that(onGeo).isEqualTo(slate(base, ctc))
            if (onGeo[0] != base) promoted++
        }
        // The pronoun set the parity was requested for: rank 0 is the contraction on geometric
        // too (before 2026-09-26 geometric kept `id`/`ill`/`wed`/`hes`/`shes` first).
        for ((key, top) in listOf(
            "id" to "i'd", "ill" to "i'll", "wed" to "we'd", "hes" to "he's", "shes" to "she's",
            "shed" to "shed", "its" to "its", "well" to "well",
        )) {
            assertWithMessage("geometric swiped '$key'").that(slate(key, geo)[0]).isEqualTo(top)
        }
        println("[pairing-base-freq] shipped bases promoted on both engines: $promoted")
        assertThat(promoted).isAtLeast(7)
    }

    @Test
    fun `both adapters derive base frequencies through the shared helper`() {
        val geo = File("$SWIPE_SRC/GeometricEngineAdapter.kt").readText()
        val ctc = File("$SWIPE_SRC/CtcEngineAdapter.kt").readText()
        assertWithMessage("geometric must pass a real base frequency, not the pre-parity null")
            .that(geo).doesNotContain("baseFrequency = { null }")
        assertThat(geo).contains("PairingBaseFrequencies.fromEnLexiconJson(")
        assertThat(geo).contains("baseFrequency = { pairingBaseFrequencies[it] }")
        assertThat(ctc).contains("PairingBaseFrequencies.select(")
        assertThat(ctc).contains("baseFrequency = { pairingBaseFrequencies[it] }")
    }
}
