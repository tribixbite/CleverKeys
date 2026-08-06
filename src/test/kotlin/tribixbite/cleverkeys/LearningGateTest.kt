package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [LearningGate] (Task A master privacy gate): every gate
 * combination and the learn-funnel dispatch semantics.
 */
class LearningGateTest {

    // ---------------------------------------------------------------- gates

    @Test
    fun `context learning requires master AND feature gate`() {
        assertTrue(LearningGate.canLearnContext(onDeviceLearningEnabled = true, contextAwareEnabled = true))
        assertFalse(LearningGate.canLearnContext(onDeviceLearningEnabled = false, contextAwareEnabled = true))
        assertFalse(LearningGate.canLearnContext(onDeviceLearningEnabled = true, contextAwareEnabled = false))
        assertFalse(LearningGate.canLearnContext(onDeviceLearningEnabled = false, contextAwareEnabled = false))
    }

    @Test
    fun `personalization learning requires master AND feature gate`() {
        assertTrue(LearningGate.canLearnPersonalization(true, true))
        assertFalse(LearningGate.canLearnPersonalization(false, true))
        assertFalse(LearningGate.canLearnPersonalization(true, false))
        assertFalse(LearningGate.canLearnPersonalization(false, false))
    }

    @Test
    fun `adaptation learning follows the master gate alone`() {
        assertTrue(LearningGate.canLearnAdaptation(true))
        assertFalse(LearningGate.canLearnAdaptation(false))
    }

    @Test
    fun `swipe ML collection requires master AND collection toggle`() {
        assertTrue(LearningGate.canCollectSwipeMl(true, true))
        assertFalse(LearningGate.canCollectSwipeMl(false, true))
        assertFalse(LearningGate.canCollectSwipeMl(true, false))
        assertFalse(LearningGate.canCollectSwipeMl(false, false))
    }

    @Test
    fun `learned-context reads are gated too - master off makes the store inert`() {
        assertTrue(LearningGate.canUseLearnedContext(true, true))
        assertFalse(LearningGate.canUseLearnedContext(false, true))
        assertFalse(LearningGate.canUseLearnedContext(true, false))
    }

    // --------------------------------------------------------------- funnel

    private class Recorder {
        val sequences = mutableListOf<List<String>>()
        val words = mutableListOf<String>()
        fun learn(
            recent: List<String>,
            word: String,
            master: Boolean,
            contextAware: Boolean = true,
            personalized: Boolean = true
        ) = LearningGate.learnCommittedWord(
            recentWords = recent,
            committedWord = word,
            onDeviceLearningEnabled = master,
            contextAwareEnabled = contextAware,
            personalizedLearningEnabled = personalized,
            recordSequence = { sequences.add(it) },
            recordWordUsage = { words.add(it) }
        )
    }

    @Test
    fun `funnel records both paths when all gates pass`() {
        val r = Recorder()
        r.learn(listOf("i", "want", "to"), "to", master = true)
        assertEquals(listOf(listOf("i", "want", "to")), r.sequences)
        assertEquals(listOf("to"), r.words)
    }

    @Test
    fun `funnel caps the context window at CONTEXT_WINDOW words`() {
        val r = Recorder()
        r.learn(listOf("a", "b", "c", "d", "e", "f"), "f", master = true)
        assertEquals(listOf("c", "d", "e", "f"), r.sequences.single())
    }

    @Test
    fun `funnel skips the context path below two words`() {
        val r = Recorder()
        r.learn(listOf("hello"), "hello", master = true)
        assertTrue(r.sequences.isEmpty())
        assertEquals(listOf("hello"), r.words)
    }

    @Test
    fun `master off short-circuits BOTH sinks - no lambda runs at all`() {
        val r = Recorder()
        repeat(50) { i -> r.learn(listOf("w$i", "x$i"), "x$i", master = false) }
        assertTrue(r.sequences.isEmpty())
        assertTrue(r.words.isEmpty())
    }

    @Test
    fun `per-feature gates operate independently under master on`() {
        val contextOnly = Recorder()
        contextOnly.learn(listOf("a", "b"), "b", master = true, personalized = false)
        assertEquals(1, contextOnly.sequences.size)
        assertTrue(contextOnly.words.isEmpty())

        val personalOnly = Recorder()
        personalOnly.learn(listOf("a", "b"), "b", master = true, contextAware = false)
        assertTrue(personalOnly.sequences.isEmpty())
        assertEquals(listOf("b"), personalOnly.words)
    }
}
