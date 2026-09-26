package tribixbite.cleverkeys

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tribixbite.cleverkeys.personalization.UserVocabulary
import tribixbite.cleverkeys.persist.InMemoryLearnedStorage
import java.io.File
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A word the user REJECTED (swipe auto-insert replaced from the bar, backspace-undone, an
 * autocorrect reverted) must leave no learned trace (learning-system audit 2026-09-26, W2
 * follow-up). The n-gram half is pinned by `NgramRollbackTest`; this pins the two halves that
 * were missing:
 *
 * - the personalization vocabulary's +1 (it was left in place as "benign", but a rejected
 *   swipe word is not a dictionary-produced autocorrect, and the count now also feeds the
 *   typo-hygiene repeat tally);
 * - the prediction-context tracker, which kept conditioning next-word context on the
 *   rejected word.
 */
class RejectedWordRollbackTest {

    private val scheduler = ScheduledThreadPoolExecutor(1)
    private val vocabulary = UserVocabulary(InMemoryLearnedStorage(), 60_000, 120_000, scheduler)

    @After
    fun tearDown() {
        scheduler.shutdownNow()
        scheduler.awaitTermination(2, TimeUnit.SECONDS)
    }

    @Test
    fun `record then rollback leaves an existing word's count unchanged`() {
        repeat(3) { vocabulary.recordWordUsage("got", timestamp = 1_000L) }
        val before = vocabulary.getWordUsage("got")!!

        vocabulary.recordWordUsage("got", timestamp = 2_000L)
        assertTrue(vocabulary.unrecordWordUsage("got"))

        assertEquals(before.usageCount, vocabulary.getWordUsage("got")!!.usageCount)
    }

    @Test
    fun `rolling back a word's only usage removes it entirely`() {
        vocabulary.recordWordUsage("gti")
        assertTrue(vocabulary.unrecordWordUsage("GTI"))
        assertNull(vocabulary.getWordUsage("gti"))
        assertFalse(vocabulary.hasWord("gti"))
    }

    @Test
    fun `rolling back an unknown word is a no-op and touches no other word`() {
        vocabulary.recordWordUsage("git", timestamp = 5_000L)
        val git = vocabulary.getWordUsage("git")
        assertFalse(vocabulary.unrecordWordUsage("never-typed"))
        assertEquals(git, vocabulary.getWordUsage("git"))
    }

    @Test
    fun `tracker drops the rejected word only when it is the newest context word`() {
        val tracker = PredictionContextTracker()
        tracker.commitWord("i", PredictionSource.UNKNOWN, autoInserted = false)
        tracker.commitWord("got", PredictionSource.UNKNOWN, autoInserted = true)

        assertFalse("not the newest word", tracker.rollbackLastWord("i"))
        assertTrue(tracker.rollbackLastWord("Got"))
        assertEquals(listOf("i"), tracker.getContextWords())
    }

    @Test
    fun `WordPredictor rolls back n-grams BEFORE the vocabulary tally under their own gates`() {
        val src = File("src/main/kotlin/tribixbite/cleverkeys/WordPredictor.kt").readText()
        val body = src.substringAfter("override fun rollbackCommittedWord(").substringBefore("\n    }\n")
        val ngram = body.indexOf("contextModel?.rollbackCommit(")
        val vocab = body.indexOf("personalizationEngine?.unrecordWordTyped(normalized)")
        assertTrue("n-gram rollback missing", ngram >= 0)
        assertTrue("vocabulary rollback missing", vocab >= 0)
        assertTrue("vocabulary must be decremented after the n-grams", vocab > ngram)
        assertTrue(body.contains("LearningGate.canLearnPersonalization(master, personalized)"))

        val handler = File("src/main/kotlin/tribixbite/cleverkeys/SuggestionHandler.kt").readText()
        val rollback = handler.substringAfter("private fun rollbackRejectedWord(").substringBefore("\n    }\n")
        assertTrue(rollback.contains("contextTracker.rollbackLastWord(word)"))
        // Every undo path goes through the one helper (no direct predictor rollback left).
        assertEquals(1, Regex("""\.rollbackCommittedWord\(""").findAll(handler).count())
    }
}
