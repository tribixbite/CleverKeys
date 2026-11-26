package tribixbite.cleverkeys.swipe.ctc

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.UserWordFrequency
import kotlin.math.ln
import kotlin.math.pow

/**
 * [CtcSlateAssembly]: the step between the per-language CTC decodes and the shared pipeline
 * (device report, Note10+ release r20, 2026-11-26).
 *
 *  - **F1** — `parcero`, added from the bar to the SPANISH list while English was primary, lost
 *    every swipe to the English `parcel` (`parcel | parcero | pardo…`). The rank merge gives a
 *    secondary word at most 920 against the primary's 1000, whatever the path says, so a user
 *    word in the secondary list could never take rank one; in the primary list the same word
 *    won (`zorblat`, `Schnurzel`). A secondary-list user word is now placed where the primary
 *    list would have put it: its own path evidence, the primary's frequency term for its stored
 *    frequency, and its swipe-priority bonus, compared with the primary's own words.
 *  - **F3** — `world`, disabled in Dictionary Manager, still showed among the alternates
 *    (`would | works | worlds | worked | world | word | … world's`). It is a contraction pairing
 *    key (`world` → `world's`), and alias-key injection re-inserts every key the merged lexicon
 *    lacks, so disabling the word put it back at the floor frequency. The slate is now filtered
 *    against the same disabled set the tap path uses, per language.
 *
 * Synthetic candidates carry the shipped presets' own score arithmetic, so every expectation
 * below is computed rather than assumed; [CtcSecondaryListReplayTest][tribixbite.cleverkeys.swipe.CtcSecondaryListReplayTest]
 * repeats both reports through the real encoder.
 */
class CtcSlateAssemblyTest {

    private val en = CtcScoringParams.presetFor("en", topK = 8)
    private val es = CtcScoringParams.presetFor("es", topK = 8)

    /** The en JSON scale's floor, the base a stored user frequency is calibrated onto there. */
    private val enFloor = 134.0

    /** A beam candidate scored exactly as [CtcBeamDecoder] scores it under [params]. */
    private fun cand(
        word: String,
        ctc: Double,
        frequency: Double,
        params: CtcScoringParams,
        priorityBonus: Double = 0.0,
        length: Int = word.length,
    ): CtcCandidate {
        val logFreq = ln(frequency + 1e-10)
        val l = if (length > 1) length else 1
        val final = ctc / l.toDouble().pow(params.gamma) + params.beta * length +
            params.lambda * logFreq + priorityBonus
        return CtcCandidate(word, final, ctc, length, logFreq, 0.0, priorityBonus)
    }

    /** A slate whose display words are its candidates, scored like the adapter scores them. */
    private fun slate(
        language: String,
        params: CtcScoringParams,
        candidates: List<CtcCandidate>,
        userWords: CtcUserWords = CtcUserWords.NONE,
    ) = CtcDecodedSlate(
        language,
        candidates,
        candidates.map { it.word },
        CtcSlateAssembly.softmaxPermille(candidates.map { it.finalScore }),
        params,
        userWords,
    )

    private fun spanishUserWords(vararg words: Pair<String, Int>, disabled: Set<String> = emptySet()) =
        CtcUserWords.of(words.toList(), disabled, calibrationFloor = 1.0)

    private fun englishSlate(vararg candidates: CtcCandidate, disabled: Set<String> = emptySet()) =
        slate("en", en, candidates.toList(), CtcUserWords.of(emptyList(), disabled, enFloor))

    // ── F1: a secondary-list user word is placed as the primary list would place it ────────

    @Test
    fun `a secondary-list custom word the path favours takes rank one from the primary's top word`() {
        val primary = englishSlate(cand("parcel", -6.0, 179.0, en), cand("pardo", -9.0, 157.0, en))
        val secondary = slate(
            "es", es,
            listOf(cand("parcero", -5.0, 255.0, es), cand("parcela", -8.0, 200.0, es)),
            spanishUserWords("parcero" to 255),
        )

        val merged = CtcSlateAssembly.dual(primary, secondary, limit = 8)

        assertWithMessage(
            "the device report: the swipe drew `parcero`, a word in the Spanish custom list, and " +
                "the English `parcel` was inserted. Filed in the English list the same word wins " +
                "(its path plus the primary's prior at its stored 255 beat `parcel`), so it must " +
                "win from the Spanish list too. Got ${merged.map { it.word }}"
        ).that(merged.map { it.word })
            .containsExactly("parcero", "parcel", "pardo", "parcela").inOrder()
        assertWithMessage("the placed word keeps its own language (no English possessive)")
            .that(merged.first().language).isEqualTo("es")
    }

    @Test
    fun `the placement score is the score the primary list itself would give the word`() {
        // Same emission evidence (ctc, length) in both decodes: only the frequency term differs.
        for (stored in listOf(UserWordFrequency.MIN, 100, UserWordFrequency.MAX)) {
            for (bonus in listOf(0.0, 2.0, 4.0)) {
                val inSpanish = cand("parcero", -7.5, stored.toDouble(), es, priorityBonus = bonus)
                val asEnglishCustom = cand(
                    "parcero", -7.5,
                    UserWordFrequency.scaleOnto(stored, enFloor, CtcLexiconMerge.MAX_FREQ),
                    en, priorityBonus = bonus,
                )
                assertWithMessage("stored $stored, bonus $bonus")
                    .that(CtcSlateAssembly.primaryEquivalentScore(inSpanish, stored, en, enFloor))
                    .isWithin(1e-9).of(asEnglishCustom.finalScore)
            }
        }
    }

    @Test
    fun `a secondary-list custom word lands between the primary words it beats and those it does not`() {
        val strong = cand("parked", -0.5, 255.0, en) // beats parcero's placement score
        val primary = englishSlate(strong, cand("parcel", -6.0, 179.0, en), cand("pardo", -9.0, 157.0, en))
        // Third in its own (Spanish) decode, so the plain merge gives it only 920/3.
        val secondary = slate(
            "es", es,
            listOf(cand("paredes", -1.0, 250.0, es), cand("parcela", -2.0, 240.0, es), cand("parcero", -5.0, 255.0, es)),
            spanishUserWords("parcero" to 255),
        )
        val placed = CtcSlateAssembly.primaryEquivalentScore(secondary.candidates[2], 255, en, enFloor)
        assertThat(strong.finalScore).isGreaterThan(placed)
        assertThat(placed).isGreaterThan(primary.candidates[1].finalScore)

        val merged = CtcSlateAssembly.dual(primary, secondary, limit = 8).map { it.word }

        assertWithMessage(
            "in the English list parcero would be second, between `parked` and `parcel`; the " +
                "Spanish top word keeps its own slot above the English second word as before"
        ).that(merged).containsExactly("parked", "paredes", "parcero", "parcel", "parcela", "pardo").inOrder()
    }

    @Test
    fun `a secondary-list custom word the path does not favour keeps its ordinary secondary slot`() {
        val primary = englishSlate(cand("parcel", -6.0, 179.0, en), cand("pardo", -9.0, 157.0, en))
        // A poor path: the primary list would rank it below both English words.
        val secondary = slate("es", es, listOf(cand("parcero", -40.0, 255.0, es)), spanishUserWords("parcero" to 255))

        val merged = CtcSlateAssembly.dual(primary, secondary, limit = 8)

        assertWithMessage("never below where the plain rank merge already put it")
            .that(merged.map { it.word }).isEqualTo(
                CtcRankMerger.merge("en", primary.words, "es", secondary.words, 8).map { it.word }
            )
        assertThat(merged.map { it.word }).containsExactly("parcel", "parcero", "pardo").inOrder()
    }

    @Test
    fun `ordinary secondary words keep the rank merge exactly`() {
        val primary = englishSlate(cand("hello", -3.0, 230.0, en), cand("help", -5.0, 228.0, en))
        // `hola` would outscore `hello` on the English scale, but it is not the user's word.
        val secondary = slate("es", es, listOf(cand("hola", -1.0, 250.0, es), cand("hora", -6.0, 240.0, es)))

        val merged = CtcSlateAssembly.dual(primary, secondary, limit = 8)

        assertThat(merged).isEqualTo(CtcRankMerger.merge("en", primary.words, "es", secondary.words, 8))
    }

    @Test
    fun `a raised swipe priority carries over from the secondary list`() {
        val primary = englishSlate(cand("parcel", -6.0, 179.0, en))
        // Without a bonus the primary list would rank parcero below parcel; High (+2) lifts it over.
        fun secondary(bonus: Double) = slate(
            "es", es, listOf(cand("parcero", -19.7, 255.0, es, priorityBonus = bonus)),
            spanishUserWords("parcero" to 255),
        )
        assertThat(CtcSlateAssembly.dual(primary, secondary(0.0), 8).first().word).isEqualTo("parcel")
        assertThat(CtcSlateAssembly.dual(primary, secondary(2.0), 8).first().word).isEqualTo("parcero")
    }

    @Test
    fun `a secondary user word wins over an empty primary beam`() {
        val primary = englishSlate()
        val secondary = slate("es", es, listOf(cand("parcero", -5.0, 255.0, es)), spanishUserWords("parcero" to 255))

        assertThat(CtcSlateAssembly.dual(primary, secondary, 8).map { it.word }).containsExactly("parcero")
    }

    // ── F3: a disabled word never reaches the slate ─────────────────────────────────────────

    @Test
    fun `a disabled word is removed from a single-language slate and its possessive stays`() {
        // The device slate for a `world` swipe after disabling `world`: the alias-key injection
        // re-inserted the pairing key at the floor, the overlay kept it and added `world's`.
        val words = listOf("would", "works", "worlds", "worked", "world", "word", "world's")
        val scores = listOf(600, 200, 90, 50, 30, 20, 19)
        val slate = CtcDecodedSlate(
            "en", emptyList(), words, scores, en, CtcUserWords.of(emptyList(), setOf("World"), enFloor),
        )

        val (outWords, outScores) = CtcSlateAssembly.single(slate)

        assertWithMessage(
            "`world` is disabled: no swipe alternate may offer it. `world's` is a separate " +
                "dictionary entry (the pairing of `world`/`worlds`) and tap typing offers it too."
        ).that(outWords).containsExactly("would", "works", "worlds", "worked", "word", "world's").inOrder()
        assertThat(outScores).containsExactly(600, 200, 90, 50, 20, 19).inOrder()
    }

    @Test
    fun `a primary-disabled word is hidden from the secondary slate as the tap path hides it`() {
        val primary = englishSlate(cand("would", -1.0, 228.0, en), disabled = setOf("world"))
        // es_enhanced.bin holds `world` (rank 129): the Spanish decode offers it.
        val secondary = slate("es", es, listOf(cand("world", -0.5, 126.0, es), cand("word", -3.0, 99.0, es)))

        val merged = CtcSlateAssembly.dual(primary, secondary, 8).map { it.word }

        assertThat(merged).containsExactly("would", "word").inOrder()
    }

    @Test
    fun `the secondary language's disabled words hide only its own copies`() {
        val primary = englishSlate(cand("pardo", -1.0, 157.0, en))
        val secondary = slate(
            "es", es, listOf(cand("pardo", -0.5, 111.0, es), cand("pardos", -3.0, 80.0, es)),
            spanishUserWords(disabled = setOf("pardo")),
        )

        val merged = CtcSlateAssembly.dual(primary, secondary, 8)

        assertWithMessage("English `pardo` stays: only the Spanish list disabled it")
            .that(merged.map { it.word to it.language })
            .containsExactly("pardo" to "en", "pardos" to "es").inOrder()
    }

    @Test
    fun `a word disabled and added back as a user word is shown`() {
        val userWords = CtcUserWords.of(listOf("World" to 200, " " to 9), setOf("world", "WORD"), enFloor)

        assertThat(userWords.excluded).containsExactly("word")
        assertThat(userWords.storedFrequencies).containsExactly("world", 200)
    }

    @Test
    fun `a duplicate user word keeps its larger stored frequency`() {
        val userWords = CtcUserWords.of(listOf("parcero" to 40, "Parcero" to 255, "parcero" to 7), emptySet(), 1.0)

        assertThat(userWords.storedFrequencies).containsExactly("parcero", 255)
    }

    // ── the shared score scale ─────────────────────────────────────────────────────────────

    @Test
    fun `softmaxPermille is the adapter's within-slate softmax times 1000, rounded`() {
        // exp(0), exp(-1), exp(-3) over their sum 1.41766: 705.4, 259.5, 35.1.
        assertThat(CtcSlateAssembly.softmaxPermille(listOf(3.0, 2.0, 0.0))).containsExactly(705, 259, 35).inOrder()
        assertThat(CtcSlateAssembly.softmaxPermille(emptyList())).isEmpty()
        assertThat(CtcSlateAssembly.softmaxPermille(listOf(-4.2))).containsExactly(1000)
    }
}
