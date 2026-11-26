package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dictionary Manager parses one language at a time (the 2026-11-18 OOM), and the order used to be
 * whichever tab reached the lock first. With German primary and Russian secondary the Russian tab
 * often did, so the German list appeared only after the Russian pack had loaded (Saga / Note10+
 * timing 2026-11-26: the list filled 0.4–1.25 s). [DictionaryLoadGate] gives the turn to the
 * languages in the order the tabs show them. Real dispatchers; no virtual time.
 */
class DictionaryLoadGateTest {

    /** The core case: the secondary asks first, the primary a moment later, the primary parses first. */
    @Test
    fun thePrimaryParsesFirstEvenWhenTheSecondaryAsksFirst() = runBlocking {
        val gate = DictionaryLoadGate()
        gate.prioritize(listOf("de", "ru"), alreadyLoaded = emptySet())
        val order = Collections.synchronizedList(mutableListOf<String>())

        val ru = async(Dispatchers.Default) { gate.withTurn("ru") { order += "ru" } }
        delay(150) // the Russian tab is waiting (or, before the fix, already parsing)
        val de = async(Dispatchers.Default) { gate.withTurn("de") { order += "de" } }
        withTimeout(5_000) { de.await(); ru.await() }

        assertThat(order).containsExactly("de", "ru").inOrder()
    }

    /** Still one language at a time: the peak must stay one list, not the sum of all tabs. */
    @Test
    fun neverTwoParsesAtOnce() = runBlocking {
        val gate = DictionaryLoadGate()
        val languages = listOf("de", "ru", "en", "fr")
        gate.prioritize(languages, alreadyLoaded = emptySet())
        val running = AtomicInteger()
        var peak = 0
        val jobs = languages.reversed().map { code ->
            async(Dispatchers.Default) {
                gate.withTurn(code) {
                    val now = running.incrementAndGet()
                    synchronized(this@DictionaryLoadGateTest) { peak = maxOf(peak, now) }
                    Thread.sleep(30)
                    running.decrementAndGet()
                }
            }
        }
        withTimeout(10_000) { jobs.forEach { it.await() } }
        assertThat(peak).isEqualTo(1)
    }

    /** A primary that never asks (its tab never loads) holds the others back only for the bound. */
    @Test
    fun aPrimaryThatNeverAsksHoldsTheOthersOnlyForTheBound() = runBlocking {
        val gate = DictionaryLoadGate(maxPriorityWaitMs = 200)
        gate.prioritize(listOf("de", "ru"), alreadyLoaded = emptySet())
        val started = System.nanoTime()
        withTimeout(5_000) { gate.withTurn("ru") { } }
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        assertWithMessage("waited ${waitedMs}ms").that(waitedMs).isAtLeast(150L)
    }

    /** A language already cached when the tabs are planned is not waited for. */
    @Test
    fun aCachedPrimaryIsNotWaitedFor() = runBlocking {
        val gate = DictionaryLoadGate(maxPriorityWaitMs = 2_000)
        gate.prioritize(listOf("de", "ru"), alreadyLoaded = setOf("de"))
        val started = System.nanoTime()
        withTimeout(5_000) { gate.withTurn("ru") { } }
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1_000L)
    }

    /** A load that fails (empty list, OOM) still releases the languages after it. */
    @Test
    fun aFailedPrimaryLoadReleasesTheSecondary() = runBlocking {
        val gate = DictionaryLoadGate(maxPriorityWaitMs = 4_000)
        gate.prioritize(listOf("de", "ru"), alreadyLoaded = emptySet())
        val ruDone = CompletableDeferred<Long>()
        val started = System.nanoTime()
        val ru = async(Dispatchers.Default) {
            gate.withTurn("ru") { ruDone.complete((System.nanoTime() - started) / 1_000_000) }
        }
        delay(100)
        runCatching { gate.withTurn("de") { throw IllegalStateException("parse failed") } }
        withTimeout(5_000) { ru.await() }
        assertWithMessage("released at once, not after the bound").that(ruDone.await()).isLessThan(2_000L)
    }
}
