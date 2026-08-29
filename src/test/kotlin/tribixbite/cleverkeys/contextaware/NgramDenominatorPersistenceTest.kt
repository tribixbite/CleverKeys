package tribixbite.cleverkeys.contextaware

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tribixbite.cleverkeys.NextWordPredictor
import tribixbite.cleverkeys.persist.InMemoryLearnedStorage
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * ARC-080: the persisted n-gram DENOMINATOR must survive a process restart.
 *
 * Both stores cap the continuations they keep per context ([BigramStore] 20,
 * [TrigramStore] 10) while the observed total counts EVERY observation, dropped
 * ones included. The persisted blob used to carry entries only, so a reload
 * reconstructed the denominator as the sum of the SURVIVING entries — a strictly
 * smaller number. The stored probabilities looked right until the next
 * observation renormalized the prefix, at which point every survivor inflated.
 *
 * The concrete regression this pins: a continuation sitting at 4.95%, just below
 * [NextWordPredictor.MIN_LEARNED_PROBABILITY] and therefore correctly suppressed,
 * crossed the floor after a restart and started surfacing in the next-word bar.
 *
 * The saturation fixtures below are shared by the bigram and trigram cases and
 * produce identical arithmetic:
 *   target        5 observations
 *   fillers      24 continuations x 4 observations = 96
 *   TRUE TOTAL  101  ->  P(target) = 5/101 = 0.049505  (suppressed)
 * Only the number of SURVIVORS differs, because the caps differ:
 *   bigram  cap 20 -> target + 19 fillers survive, sum-of-survivors = 81
 *   trigram cap 10 -> target +  9 fillers survive, sum-of-survivors = 41
 */
class NgramDenominatorPersistenceTest {

    private companion object {
        /**
         * Chosen so the target is the single most probable continuation and can
         * therefore never be evicted by the probability-ranked per-context cap.
         */
        const val TARGET_FREQUENCY = 5
        const val FILLER_FREQUENCY = 4
        const val FILLER_COUNT = 24

        /** Every observation ever made against the context word / prefix. */
        const val TRUE_TOTAL = TARGET_FREQUENCY + FILLER_COUNT * FILLER_FREQUENCY // 101

        /** P(target | context) as computed while the data is still in RAM. */
        const val TARGET_PROBABILITY = TARGET_FREQUENCY.toFloat() / TRUE_TOTAL // 0.049505

        const val EXACT = 1e-9f
    }

    private val scheduler = ScheduledThreadPoolExecutor(1)

    @After
    fun tearDown() {
        scheduler.shutdownNow()
        scheduler.awaitTermination(2, TimeUnit.SECONDS)
    }

    private fun newBigramStore(storage: InMemoryLearnedStorage) =
        BigramStore(storage, 60_000, 120_000, scheduler)

    private fun newTrigramStore(storage: InMemoryLearnedStorage) =
        TrigramStore(storage, 60_000, 120_000, scheduler)

    /** Saturate `the -> *` past the 20-entry cap; see the class doc for the arithmetic. */
    private fun saturateBigrams(store: BigramStore) {
        repeat(TARGET_FREQUENCY) { store.recordBigram("en", "the", "target") }
        for (i in 1..FILLER_COUNT) {
            repeat(FILLER_FREQUENCY) { store.recordBigram("en", "the", "f$i") }
        }
    }

    /** Saturate `i want -> *` past the 10-entry cap; same arithmetic as the bigram fixture. */
    private fun saturateTrigrams(store: TrigramStore) {
        repeat(TARGET_FREQUENCY) { store.recordTrigram("en", "i", "want", "target") }
        for (i in 1..FILLER_COUNT) {
            repeat(FILLER_FREQUENCY) { store.recordTrigram("en", "i", "want", "f$i") }
        }
    }

    // ------------------------------------------------------------------ bigrams

    @Test
    fun `bigram fixture is genuinely capped and genuinely suppressed`() {
        val store = newBigramStore(InMemoryLearnedStorage())
        saturateBigrams(store)

        // The cap really did drop entries — otherwise the regression cannot occur.
        val kept = store.getAllBigrams("en", "the")
        assertEquals(20, kept.size)
        assertEquals("target", kept.first().word2)
        assertTrue("fixture must overflow the cap", FILLER_COUNT + 1 > kept.size)

        assertEquals(TARGET_PROBABILITY, store.getProbability("en", "the", "target"), EXACT)
        assertTrue(
            "fixture must start BELOW the learned-probability floor",
            TARGET_PROBABILITY < NextWordPredictor.MIN_LEARNED_PROBABILITY
        )
    }

    @Test
    fun `bigram probabilities are identical across a restart once learning continues`() {
        val storage = InMemoryLearnedStorage()
        val live = newBigramStore(storage)
        saturateBigrams(live)
        live.flush()

        val revived = newBigramStore(storage)
        // Reload alone already agreed — the persisted probabilities were correct.
        assertEquals(
            live.getProbability("en", "the", "target"),
            revived.getProbability("en", "the", "target"),
            EXACT
        )

        // ...but the very next observation renormalizes the whole context against
        // the stored denominator, which is where a reconstructed one diverges.
        live.recordBigram("en", "the", "f1")
        revived.recordBigram("en", "the", "f1")

        val expected = live.getProbability("en", "the", "target")
        val actual = revived.getProbability("en", "the", "target")
        assertEquals(
            "restart inflated P(target) from $expected to $actual",
            expected,
            actual,
            EXACT
        )
        assertEquals(TARGET_FREQUENCY.toFloat() / (TRUE_TOTAL + 1), actual, EXACT)
    }

    @Test
    fun `a suppressed bigram continuation stays suppressed after a restart`() {
        val storage = InMemoryLearnedStorage()
        val live = newBigramStore(storage)
        saturateBigrams(live)
        live.flush()

        val revived = newBigramStore(storage)
        revived.recordBigram("en", "the", "f1")

        val after = revived.getProbability("en", "the", "target")
        assertTrue(
            "a continuation below MIN_LEARNED_PROBABILITY ($TARGET_PROBABILITY) must not " +
                "cross the floor by restarting — got $after",
            after < NextWordPredictor.MIN_LEARNED_PROBABILITY
        )
    }

    @Test
    fun `bigram denominators survive a SECOND restart`() {
        val storage = InMemoryLearnedStorage()
        newBigramStore(storage).also { saturateBigrams(it); it.flush() }

        // First restart: load, learn one more observation, persist again.
        val once = newBigramStore(storage)
        once.recordBigram("en", "the", "f1")
        once.flush()

        // Second restart: the re-serialized blob must still carry the true total.
        val twice = newBigramStore(storage)
        twice.recordBigram("en", "the", "f1")
        once.recordBigram("en", "the", "f1")

        assertEquals(
            once.getProbability("en", "the", "target"),
            twice.getProbability("en", "the", "target"),
            EXACT
        )
        assertEquals(
            TARGET_FREQUENCY.toFloat() / (TRUE_TOTAL + 2),
            twice.getProbability("en", "the", "target"),
            EXACT
        )
    }

    // ----------------------------------------------------------------- trigrams

    @Test
    fun `trigram fixture is genuinely capped and genuinely suppressed`() {
        val store = newTrigramStore(InMemoryLearnedStorage())
        saturateTrigrams(store)

        val kept = store.getPredictions("en", "i", "want", maxResults = 50, minProbability = 0f)
        assertEquals(10, kept.size)
        assertEquals("target", kept.first().word3)
        assertEquals(TARGET_PROBABILITY, store.getProbability("en", "i", "want", "target"), EXACT)
    }

    @Test
    fun `trigram probabilities are identical across a restart once learning continues`() {
        val storage = InMemoryLearnedStorage()
        val live = newTrigramStore(storage)
        saturateTrigrams(live)
        live.flush()

        val revived = newTrigramStore(storage)
        assertEquals(
            live.getProbability("en", "i", "want", "target"),
            revived.getProbability("en", "i", "want", "target"),
            EXACT
        )

        live.recordTrigram("en", "i", "want", "f1")
        revived.recordTrigram("en", "i", "want", "f1")

        val expected = live.getProbability("en", "i", "want", "target")
        val actual = revived.getProbability("en", "i", "want", "target")
        assertEquals(
            "restart inflated P(target | i want) from $expected to $actual",
            expected,
            actual,
            EXACT
        )
        assertEquals(TARGET_FREQUENCY.toFloat() / (TRUE_TOTAL + 1), actual, EXACT)
    }

    @Test
    fun `a suppressed trigram continuation stays suppressed after a restart`() {
        val storage = InMemoryLearnedStorage()
        val live = newTrigramStore(storage)
        saturateTrigrams(live)
        live.flush()

        val revived = newTrigramStore(storage)
        revived.recordTrigram("en", "i", "want", "f1")

        val after = revived.getProbability("en", "i", "want", "target")
        assertTrue(
            "a continuation below MIN_LEARNED_PROBABILITY ($TARGET_PROBABILITY) must not " +
                "cross the floor by restarting — got $after",
            after < NextWordPredictor.MIN_LEARNED_PROBABILITY
        )
    }

    @Test
    fun `trigram denominators survive a SECOND restart`() {
        val storage = InMemoryLearnedStorage()
        newTrigramStore(storage).also { saturateTrigrams(it); it.flush() }

        val once = newTrigramStore(storage)
        once.recordTrigram("en", "i", "want", "f1")
        once.flush()

        val twice = newTrigramStore(storage)
        twice.recordTrigram("en", "i", "want", "f1")
        once.recordTrigram("en", "i", "want", "f1")

        assertEquals(
            once.getProbability("en", "i", "want", "target"),
            twice.getProbability("en", "i", "want", "target"),
            EXACT
        )
        assertEquals(
            TARGET_FREQUENCY.toFloat() / (TRUE_TOTAL + 2),
            twice.getProbability("en", "i", "want", "target"),
            EXACT
        )
    }

    // ------------------------------------------------------- persisted format

    /**
     * FORMAT CONTRACT for the internal persistence blob (NOT the backup payload,
     * which stays a bare array — see `exportToJson`). A versioned object wrapper
     * carrying `entries` plus the `totals` denominators; a bare array is the
     * implicit version 1 that shipped before ARC-080.
     */
    @Test
    fun `persisted bigram blob carries the true denominators`() {
        val storage = InMemoryLearnedStorage()
        newBigramStore(storage).also { saturateBigrams(it); it.flush() }

        val root = JSONObject(storage.getString(BigramStore.storageKey("en"))!!)
        assertEquals(2, root.getInt("version"))
        assertEquals(20, root.getJSONArray("entries").length())
        assertEquals(TRUE_TOTAL, root.getJSONObject("totals").getInt("the"))
    }

    @Test
    fun `persisted trigram blob carries the true denominators keyed by the two-word prefix`() {
        val storage = InMemoryLearnedStorage()
        newTrigramStore(storage).also { saturateTrigrams(it); it.flush() }

        val root = JSONObject(storage.getString(TrigramStore.storageKey("en"))!!)
        assertEquals(2, root.getInt("version"))
        assertEquals(10, root.getJSONArray("entries").length())
        assertEquals(TRUE_TOTAL, root.getJSONObject("totals").getInt("i want"))
    }

    // -------------------------------------------------- legacy (v1) compatibility

    /**
     * Data already on users' devices is a bare JSON array with no totals section.
     * It must keep loading, and must keep behaving exactly as it did: denominators
     * reconstructed as the sum of the surviving entries. The persisted
     * probabilities below deliberately imply a larger true total (10) that the v1
     * format never recorded — proving we do NOT invent one.
     */
    @Test
    fun `legacy bigram array loads with sum-of-survivors denominators`() {
        val storage = InMemoryLearnedStorage()
        storage.seed(
            BigramStore.storageKey("en"),
            """[{"word1":"want","word2":"to","frequency":3,"probability":0.3},
                {"word1":"want","word2":"food","frequency":1,"probability":0.1}]"""
        )

        val store = newBigramStore(storage)
        assertEquals(2, store.getTotalBigramCount("en"))
        // Loaded as persisted — the reload itself never rewrites probabilities.
        assertEquals(0.3f, store.getProbability("en", "want", "to"), 1e-6f)

        // The reconstructed denominator is 3 + 1 = 4, so one more observation of
        // "to" gives 4/5, not 4/11. Unchanged pre-ARC-080 behaviour.
        store.recordBigram("en", "want", "to")
        assertEquals(0.8f, store.getProbability("en", "want", "to"), 1e-6f)
        assertEquals(0.2f, store.getProbability("en", "want", "food"), 1e-6f)
    }

    @Test
    fun `legacy trigram array loads with sum-of-survivors denominators`() {
        val storage = InMemoryLearnedStorage()
        storage.seed(
            TrigramStore.storageKey("en"),
            """[{"word1":"i","word2":"want","word3":"to","frequency":3,"probability":0.3},
                {"word1":"i","word2":"want","word3":"food","frequency":1,"probability":0.1}]"""
        )

        val store = newTrigramStore(storage)
        assertEquals(2, store.getTotalTrigramCount("en"))
        assertEquals(0.3f, store.getProbability("en", "i", "want", "to"), 1e-6f)

        store.recordTrigram("en", "i", "want", "to")
        assertEquals(0.8f, store.getProbability("en", "i", "want", "to"), 1e-6f)
        assertEquals(0.2f, store.getProbability("en", "i", "want", "food"), 1e-6f)
    }

    /**
     * The legacy un-keyed `bigrams_json` migration blob is v1 too, and is loaded
     * through the same path — it must not regress into the corruption fallback.
     */
    @Test
    fun `legacy un-keyed migration blob still loads`() {
        val storage = InMemoryLearnedStorage()
        storage.seed(
            BigramStore.LEGACY_KEY_BIGRAMS,
            """[{"word1":"i","word2":"am","frequency":7,"probability":1.0}]"""
        )

        val store = newBigramStore(storage)
        assertEquals(7, store.getAllBigrams("en", "i").first().frequency)
    }

    // ------------------------------------------------------ malformed v2 blobs

    @Test
    fun `a v2 blob with no totals section falls back to sum-of-survivors`() {
        val storage = InMemoryLearnedStorage()
        storage.seed(
            BigramStore.storageKey("en"),
            """{"version":2,"entries":[
                 {"word1":"want","word2":"to","frequency":3,"probability":0.3}]}"""
        )

        val store = newBigramStore(storage)
        assertEquals(1, store.getTotalBigramCount("en"))
        store.recordBigram("en", "want", "to")
        assertEquals(1.0f, store.getProbability("en", "want", "to"), 1e-6f)
    }

    @Test
    fun `a total smaller than the surviving entries is clamped, never yielding p greater than 1`() {
        val storage = InMemoryLearnedStorage()
        storage.seed(
            BigramStore.storageKey("en"),
            """{"version":2,
                "entries":[{"word1":"want","word2":"to","frequency":8,"probability":0.5}],
                "totals":{"want":2}}"""
        )

        val store = newBigramStore(storage)
        // Clamped to the survivor sum (8) and renormalized against it — the stored
        // 0.5 disagreed with its own denominator and is corrected, not trusted.
        assertEquals(1.0f, store.getProbability("en", "want", "to"), 1e-6f)

        // Proves the denominator really is 8 and not the bogus 2: one more
        // observation gives 9/9, where a denominator of 2 would give 9/3 = 3.0.
        store.recordBigram("en", "want", "to")
        assertEquals(1.0f, store.getProbability("en", "want", "to"), 1e-6f)
        assertEquals(9, store.getAllBigrams("en", "want").single().frequency)
    }

    @Test
    fun `a corrupted blob still falls back to an empty table in both stores`() {
        val bigramStorage = InMemoryLearnedStorage()
        bigramStorage.seed(BigramStore.storageKey("en"), "{not valid json][")
        assertEquals(0, newBigramStore(bigramStorage).getTotalBigramCount("en"))

        val trigramStorage = InMemoryLearnedStorage()
        trigramStorage.seed(TrigramStore.storageKey("en"), "{not valid json][")
        assertEquals(0, newTrigramStore(trigramStorage).getTotalTrigramCount("en"))
    }
}
