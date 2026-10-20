package tribixbite.cleverkeys.backup

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tribixbite.cleverkeys.contextaware.BigramStore
import tribixbite.cleverkeys.contextaware.TrigramStore
import tribixbite.cleverkeys.persist.InMemoryLearnedStorage
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Audit M2 (2026-10-20): a corrupt learned-data section must not be reported as restored.
 *
 * The restore used to count each section's array SIZE as imported while the stores swallowed
 * every failure, so a backup whose learned data could not be read still produced a success
 * report. [LearnedDataImport] counts what the stores actually merged and flags the rest.
 */
class LearnedDataImportTest {

    private val warnings = mutableListOf<String>()
    private val scheduler = ScheduledThreadPoolExecutor(1)

    @After
    fun tearDown() {
        scheduler.shutdownNow()
        scheduler.awaitTermination(2, TimeUnit.SECONDS)
    }

    private fun apply(
        json: String,
        bigrams: (String, String) -> Int = { _, _ -> error("no bigram section expected") },
        trigrams: (String, String) -> Int = { _, _ -> error("no trigram section expected") },
        vocabulary: (String) -> Int = { error("no vocabulary section expected") },
    ) = LearnedDataImport.apply(json, bigrams, trigrams, vocabulary) { message, _ -> warnings += message }

    @Test
    fun `absent sections are a silent no-op`() {
        val counts = apply("""{"custom_words_by_language":{}}""")

        assertEquals(LearnedDataImport.Counts(), counts)
        assertFalse(counts.incomplete)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `the reported count is what the store merged, not the section size`() {
        val counts = apply(
            """{"learned_bigrams_by_language":{"en":[{},{},{}]}}""",
            bigrams = { _, _ -> 1 },
        )

        assertEquals(1, counts.bigrams)
        assertEquals(2, counts.skippedEntries)
        assertTrue(counts.incomplete)
        assertEquals(1, warnings.size)
    }

    @Test
    fun `a malformed bigram section costs neither the trigrams nor the vocabulary`() {
        val counts = apply(
            """
            {
              "learned_bigrams_by_language": "corrupt",
              "learned_trigrams_by_language": {"en": [{}, {}]},
              "user_vocabulary": [{}, {}, {}]
            }
            """.trimIndent(),
            trigrams = { _, _ -> 2 },
            vocabulary = { 3 },
        )

        assertEquals(0, counts.bigrams)
        assertEquals(2, counts.trigrams)
        assertEquals(3, counts.vocabulary)
        assertEquals(listOf(LearnedDataImport.BIGRAMS), counts.failedSections)
        assertTrue(counts.incomplete)
    }

    @Test
    fun `a language whose value is not an array fails alone`() {
        val seen = mutableListOf<String>()
        val counts = apply(
            """{"learned_bigrams_by_language":{"en":{"oops":1},"fr":[{}]}}""",
            bigrams = { lang, _ -> seen += lang; 1 },
        )

        assertEquals(listOf("fr"), seen)
        assertEquals(1, counts.bigrams)
        assertEquals(listOf("${LearnedDataImport.BIGRAMS}/en"), counts.failedSections)
    }

    @Test
    fun `a store that throws is a failed section, not a crash`() {
        val counts = apply(
            """{"learned_trigrams_by_language":{"en":[{}]},"user_vocabulary":[]}""",
            trigrams = { _, _ -> throw IllegalStateException("storage full") },
            vocabulary = { 0 },
        )

        assertEquals(0, counts.trigrams)
        assertEquals(listOf("${LearnedDataImport.TRIGRAMS}/en"), counts.failedSections)
    }

    @Test
    fun `a payload that is not a JSON object is reported, not swallowed`() {
        val counts = apply("[1, 2")

        assertTrue(counts.incomplete)
        assertEquals(listOf("payload"), counts.failedSections)
        assertEquals(1, warnings.size)
    }

    @Test
    fun `a fully valid payload through the real stores is complete`() {
        val bigramStore = BigramStore(InMemoryLearnedStorage(), 60_000, 120_000, scheduler)
        val trigramStore = TrigramStore(InMemoryLearnedStorage(), 60_000, 120_000, scheduler)
        val counts = LearnedDataImport.apply(
            """
            {
              "learned_bigrams_by_language": {"en": [{"word1":"i","word2":"want","frequency":2}]},
              "learned_trigrams_by_language": {"en": [{"word1":"i","word2":"want","word3":"to","frequency":2}]}
            }
            """.trimIndent(),
            bigramStore::importFromJson, trigramStore::importFromJson, { 0 },
        ) { message, _ -> warnings += message }

        assertEquals(1, counts.bigrams)
        assertEquals(1, counts.trigrams)
        assertFalse(counts.incomplete)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `corrupt entries through the real store are counted as not restored`() {
        val store = BigramStore(InMemoryLearnedStorage(), 60_000, 120_000, scheduler)
        val counts = LearnedDataImport.apply(
            """
            {"learned_bigrams_by_language": {"en": [
              {"word1":"i","word2":"want","frequency":2},
              "garbage",
              {"word1":"see","frequency":1}
            ]}}
            """.trimIndent(),
            store::importFromJson, { _, _ -> 0 }, { 0 },
        ) { message, _ -> warnings += message }

        assertEquals(1, counts.bigrams)
        assertEquals(2, counts.skippedEntries)
        assertTrue(counts.incomplete)
        assertEquals(1, store.getTotalBigramCount("en"))
    }
}
