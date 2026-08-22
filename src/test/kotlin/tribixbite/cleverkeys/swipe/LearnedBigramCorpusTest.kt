package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Test
import java.io.File
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * [LearnedBigramCorpus] — stage A of the step-5 harness.
 *
 * Runs entirely on a SYNTHETIC export written to a temp file. It must not depend on a real
 * device export: that file is personal data, is never committed, and will be absent on any
 * machine but the maintainer's. A test that silently skips when it is missing would report green
 * while covering nothing.
 */
class LearnedBigramCorpusTest {

    private val scheduler = ScheduledThreadPoolExecutor(1)

    @After
    fun tearDown() {
        scheduler.shutdownNow()
        scheduler.awaitTermination(2, TimeUnit.SECONDS)
    }

    /** An export shaped exactly like the app's `exportDictionaries` output. */
    private fun writeExport(json: String): File =
        File.createTempFile("cleverkeys-export", ".json").apply {
            writeText(json)
            deleteOnExit()
        }

    private val sample = """
        {
          "user_words": [],
          "learned_bigrams_by_language": {
            "en": [
              {"word1":"in","word2":"the","frequency":27,"probability":0.429},
              {"word1":"its","word2":"it's","frequency":18,"probability":0.545},
              {"word1":"want","word2":"to","frequency":15,"probability":0.536},
              {"word1":"seen","word2":"once","frequency":1,"probability":1.0},
              {"word1":"also","word2":"once","frequency":1,"probability":0.5},
              {"word1":"frequent","word2":"unlikely","frequency":9,"probability":0.01}
            ],
            "fr": [
              {"word1":"tout","word2":"le","frequency":3,"probability":0.4}
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `parses the export shape the app actually writes`() {
        val pairs = LearnedBigramCorpus.parse(writeExport(sample), "en")
        assertThat(pairs).hasSize(6)
        assertThat(pairs[0].word1).isEqualTo("in")
        assertThat(pairs[0].word2).isEqualTo("the")
        assertThat(pairs[0].frequency).isEqualTo(27)
        assertThat(pairs[0].probability).isWithin(1e-4f).of(0.429f)
    }

    @Test
    fun `an absent language yields an empty list rather than throwing`() {
        // A device that never typed Swedish legitimately has no `sv` key. That is a fact to
        // report, not a crash that loses the run.
        assertThat(LearnedBigramCorpus.parse(writeExport(sample), "sv")).isEmpty()
    }

    @Test
    fun `an export with no learned section yields empty`() {
        assertThat(LearnedBigramCorpus.parse(writeExport("""{"user_words":[]}"""), "en")).isEmpty()
    }

    @Test
    fun `a missing file fails with an actionable message, not a NullPointerException`() {
        try {
            LearnedBigramCorpus.parse(File("/nonexistent/export.json"), "en")
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertWithMessage("the message must say how to PRODUCE one, not just that it is absent")
                .that(e.message).contains("export dictionaries")
        }
    }

    // ── the honesty numbers ─────────────────────────────────────────────────────────

    @Test
    fun `the usable count excludes hapax legomena`() {
        val loaded = LearnedBigramCorpus.seed(
            LearnedBigramCorpus.parse(writeExport(sample), "en"), "en", scheduler
        )
        assertThat(loaded.total).isEqualTo(6)
        assertWithMessage(
            "two of the six are frequency 1 and contribute exactly zero boost. Reporting 6 as " +
                "the evidence count overstates the harness's statistical power — which is the " +
                "error this field exists to prevent, at 10x scale on the real export."
        ).that(loaded.usable).isEqualTo(4)
        assertThat(loaded.usableFraction).isWithin(1e-9).of(4.0 / 6.0)
    }

    @Test
    fun `the promotable count additionally applies the stricter rank-1 floors`() {
        val loaded = LearnedBigramCorpus.seed(
            LearnedBigramCorpus.parse(writeExport(sample), "en"), "en", scheduler
        )
        assertWithMessage(
            "`frequent -> unlikely` clears the frequency floor at 9 but its probability is 0.01, " +
                "below MIN_LEARNED_PROBABILITY — frequent enough to reorder alternates, not " +
                "confident enough to silently WRITE the word at rank 1"
        ).that(loaded.promotable).isEqualTo(3)
    }

    @Test
    fun `toString reports the usable fraction, since that is the number that gets quoted`() {
        val loaded = LearnedBigramCorpus.seed(
            LearnedBigramCorpus.parse(writeExport(sample), "en"), "en", scheduler
        )
        assertThat(loaded.toString()).contains("total=6")
        assertThat(loaded.toString()).contains("usable=4")
    }

    // ── seeding goes through the real learn path ────────────────────────────────────

    @Test
    fun `a seeded model produces boosts for pairs above the floor and none below`() {
        val loaded = LearnedBigramCorpus.seed(
            LearnedBigramCorpus.parse(writeExport(sample), "en"), "en", scheduler
        )
        val model = loaded.model

        assertWithMessage("a 27-times pair must be well above the store floor")
            .that(model.getContextBoost("the", listOf("in"))).isGreaterThan(1.0f)
        assertWithMessage(
            "a once-seen pair must contribute NOTHING — seeding replays the real learn path, so " +
                "the store's own floor applies rather than being bypassed by writing counts in"
        ).that(model.getContextBoost("once", listOf("seen"))).isEqualTo(1.0f)
    }

    @Test
    fun `the seeded evidence carries the counts the rank-1 floors need`() {
        val loaded = LearnedBigramCorpus.seed(
            LearnedBigramCorpus.parse(writeExport(sample), "en"), "en", scheduler
        )
        val evidence = loaded.model.getContextEvidence("it's", listOf("its"))
        assertThat(evidence).isNotNull()
        assertWithMessage("replaying recordBigram 18 times must reproduce the frequency")
            .that(evidence!!.frequency).isEqualTo(18)
        assertThat(SwipeContextRescorer.promotableToRankOne(
            SwipeContextRescorer.Evidence(
                loaded.model.boostFor(evidence).toDouble(), evidence.frequency, evidence.probability
            )
        )).isTrue()
    }

    @Test
    fun `seeding one language does not leak into another`() {
        val loaded = LearnedBigramCorpus.seed(
            LearnedBigramCorpus.parse(writeExport(sample), "en"), "en", scheduler
        )
        assertWithMessage("the fr pair was never seeded into the en model")
            .that(loaded.model.getContextBoost("le", listOf("tout"))).isEqualTo(1.0f)
    }

    @Test
    fun `an empty corpus seeds an inert model rather than failing`() {
        val loaded = LearnedBigramCorpus.seed(emptyList(), "en", scheduler)
        assertThat(loaded.total).isEqualTo(0)
        assertThat(loaded.usable).isEqualTo(0)
        assertThat(loaded.usableFraction).isEqualTo(0.0)
        assertThat(loaded.model.getContextBoost("the", listOf("in"))).isEqualTo(1.0f)
    }
}
