package tribixbite.cleverkeys.contextaware

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Test
import tribixbite.cleverkeys.persist.InMemoryLearnedStorage
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * [ContextModel.getContextEvidence] and the non-loading store peeks behind it.
 *
 * Step 2 of `docs/specs/ctc-context-rescoring-and-tunables.md`. The swipe rescorer needs the raw
 * `(frequency, probability, fromTrigram)` rather than a collapsed boost, because a promotion into
 * rank 1 must clear stricter floors than a mere reordering — and those floors are expressed in
 * counts a multiplier cannot reconstruct.
 *
 * Two properties matter most here, and both are about what must NOT happen:
 *
 *  1. **The evidence lookup must follow exactly the same backoff as [ContextModel.getContextBoost]**
 *     — trigram preferred, bigram fallback. If they diverged, the boost deciding the SORT and the
 *     counts deciding the PROMOTION would describe different n-grams.
 *  2. **Neither may trigger a store load.** This path runs on the main thread during a swipe,
 *     where building a language's tables from persisted storage is the documented cause of
 *     first-swipe jank.
 */
class ContextEvidenceLookupTest {

    private val scheduler = ScheduledThreadPoolExecutor(1)
    private val bigramStorage = InMemoryLearnedStorage()
    private val trigramStorage = InMemoryLearnedStorage()
    private val bigramStore = BigramStore(bigramStorage, 60_000, 120_000, scheduler)
    private val trigramStore = TrigramStore(trigramStorage, 60_000, 120_000, scheduler)
    private val model = ContextModel(bigramStore, trigramStore, "en")

    @After
    fun tearDown() {
        scheduler.shutdownNow()
        scheduler.awaitTermination(2, TimeUnit.SECONDS)
    }

    /** Record a sequence enough times to clear the stores' min-frequency floor. */
    private fun learn(vararg words: String, times: Int = 3) {
        repeat(times) { model.recordSequence(words.toList()) }
    }

    // ── the lookup agrees with the boost it will be sorted by ────────────────────────

    @Test
    fun `evidence and boost agree on the bigram path`() {
        learn("go", "home")

        val evidence = model.getContextEvidence("home", listOf("go"))
        assertWithMessage("a thrice-seen bigram must clear the store's frequency floor")
            .that(evidence).isNotNull()
        assertThat(evidence!!.word).isEqualTo("home")
        assertThat(evidence.frequency).isAtLeast(2)
        assertThat(evidence.fromTrigram).isFalse()

        assertWithMessage(
            "getContextBoost must be non-neutral for exactly the cases getContextEvidence " +
                "returns — they are the sort term and the promotion term for the same n-gram"
        ).that(model.getContextBoost("home", listOf("go"))).isGreaterThan(1.0f)
    }

    @Test
    fun `the trigram path is preferred when two words of context exist`() {
        learn("i", "want", "to")

        val evidence = model.getContextEvidence("to", listOf("i", "want"))
        assertThat(evidence).isNotNull()
        assertWithMessage(
            "with two context words the sharper model must be the source — this is the same " +
                "preference getContextBoost applies, and the two must not diverge"
        ).that(evidence!!.fromTrigram).isTrue()
    }

    @Test
    fun `a single context word falls back to the bigram model`() {
        learn("i", "want", "to")

        val evidence = model.getContextEvidence("to", listOf("want"))
        assertThat(evidence).isNotNull()
        assertThat(evidence!!.fromTrigram).isFalse()
    }

    @Test
    fun `an unlearned continuation yields no evidence`() {
        learn("go", "home")
        assertThat(model.getContextEvidence("banana", listOf("go"))).isNull()
        assertWithMessage("and the boost agrees it is neutral")
            .that(model.getContextBoost("banana", listOf("go"))).isEqualTo(1.0f)
    }

    @Test
    fun `a once-seen continuation is below the store floor and yields nothing`() {
        // The stores ignore hapax legomena: a single observation must not hand a candidate
        // near-max confidence just because its conditional probability is 1.0 on one sample.
        learn("rare", "sequence", times = 1)
        assertThat(model.getContextEvidence("sequence", listOf("rare"))).isNull()
    }

    @Test
    fun `empty inputs yield no evidence`() {
        learn("go", "home")
        assertThat(model.getContextEvidence("home", emptyList())).isNull()
        assertThat(model.getContextEvidence("", listOf("go"))).isNull()
    }

    // ── the non-loading contract (§4's cold-start hazard) ────────────────────────────

    @Test
    fun `a language that was never touched reports itself unloaded`() {
        assertWithMessage(
            "isLanguageLoaded must PEEK, not build. If it loaded, the main-thread swipe path " +
                "would take the blob-load stall this check exists to avoid."
        ).that(bigramStore.isLanguageLoaded("sv")).isFalse()
        assertThat(trigramStore.isLanguageLoaded("sv")).isFalse()
    }

    @Test
    fun `the confident-entry lookups return null for an unloaded language without loading it`() {
        assertThat(bigramStore.getConfidentEntry("sv", "gå", "hem")).isNull()
        assertThat(trigramStore.getConfidentEntry("sv", "jag", "vill", "gå")).isNull()

        assertWithMessage(
            "the lookups must not have loaded the language as a side effect — that is the whole " +
                "point of the non-loading variants"
        ).that(bigramStore.isLanguageLoaded("sv")).isFalse()
        assertThat(trigramStore.isLanguageLoaded("sv")).isFalse()
    }

    @Test
    fun `a language becomes loaded once it is actually learned`() {
        assertThat(bigramStore.isLanguageLoaded("en")).isFalse()
        learn("i", "want", "to") // 3 words, so BOTH stores are touched
        assertWithMessage("recording is a write path and legitimately loads the table")
            .that(bigramStore.isLanguageLoaded("en")).isTrue()
        assertThat(trigramStore.isLanguageLoaded("en")).isTrue()
        assertThat(model.isLoadedInMemory()).isTrue()
    }

    @Test
    fun `isLoadedInMemory requires BOTH stores, not just the bigram one`() {
        // A two-word sequence populates bigrams and never touches the trigram store, so a
        // trigram lookup would still hit the blob load this check exists to avoid. Requiring
        // both is therefore correct, not over-strict — but it does mean rescoring stays off
        // until something warms the trigram side, which the next-word path does on the executor.
        learn("go", "home")
        assertThat(bigramStore.isLanguageLoaded("en")).isTrue()
        assertThat(trigramStore.isLanguageLoaded("en")).isFalse()
        assertWithMessage(
            "a half-warm model must report NOT loaded — otherwise the trigram branch of " +
                "getContextEvidence would trigger a synchronous load on the main thread"
        ).that(model.isLoadedInMemory()).isFalse()
    }

    @Test
    fun `isLoadedInMemory is false until both stores hold the language`() {
        assertWithMessage(
            "a cold model must report false so the caller skips rescoring wholesale, rather " +
                "than discovering emptiness one candidate at a time"
        ).that(model.isLoadedInMemory()).isFalse()
    }

    @Test
    fun `the confident-entry floor matches getConfidentProbability exactly`() {
        // Both must gate on the same min-frequency. If getConfidentEntry were more permissive,
        // the rescorer would apply promotion counts for an n-gram the boost scores as neutral.
        learn("edge", "case", times = 1)
        assertThat(bigramStore.getConfidentProbability("en", "edge", "case")).isEqualTo(0f)
        assertThat(bigramStore.getConfidentEntry("en", "edge", "case")).isNull()

        learn("edge", "case", times = 2) // now 3 total, above the floor
        assertThat(bigramStore.getConfidentProbability("en", "edge", "case")).isGreaterThan(0f)
        assertThat(bigramStore.getConfidentEntry("en", "edge", "case")).isNotNull()
    }
}
