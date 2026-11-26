package tribixbite.cleverkeys

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lets Dictionary Manager parse one language's word list at a time, in the order its tabs show
 * the languages. Pure coroutine code (`DictionaryLoadGateTest`).
 *
 * One at a time is the 2026-11-18 OOM fix: every language tab is created at once and used to
 * parse its list in parallel, on top of the keyboard's heap in the same process. The order was
 * then whichever tab reached the lock first, so with German primary and Russian secondary the
 * Russian tab often won and the German list appeared only after the Russian pack had loaded
 * (Saga / Note10+ timing 2026-11-26, list filled 0.4–1.25 s).
 *
 * Now the activity names its languages in tab order ([prioritize]). A language waits until every
 * language ahead of it has finished loading (or failed), and only then takes the one-at-a-time
 * lock; the first language never waits for a later one. A language ahead that never asks (its tab
 * was not created, its request was cancelled) holds the others back for at most
 * [maxPriorityWaitMs], so no order mistake can leave a list empty. A language that was not named
 * waits for every named one still pending, bounded the same way.
 *
 * @param maxPriorityWaitMs the longest a language waits for the ones ahead of it to finish
 */
internal class DictionaryLoadGate(private val maxPriorityWaitMs: Long = DEFAULT_MAX_PRIORITY_WAIT_MS) {

    /** The parse itself: one language at a time. */
    private val mutex = Mutex()

    /** The named languages that have not finished loading, in tab order. */
    private val pending = MutableStateFlow<List<String>>(emptyList())

    /**
     * The tabs' languages in display order, [alreadyLoaded] (cached) ones left out: they are not
     * waited for. Replaces any earlier order (a rebuilt tab set, a recreated activity).
     */
    fun prioritize(languages: List<String>, alreadyLoaded: Set<String>) {
        pending.value = languages.distinct().filter { it !in alreadyLoaded }
    }

    /** [language]'s list is available (loaded now, or found cached): nobody waits for it. */
    fun markLoaded(language: String) {
        pending.update { if (language in it) it - language else it }
    }

    /** Forget the order (Dictionary Manager closed). */
    fun clear() {
        pending.value = emptyList()
    }

    /**
     * Run [block] (the parse of [language]) once the languages ahead of it are done and no other
     * parse runs. [language] counts as loaded when [block] ends, whether it returned or threw.
     */
    suspend fun <T> withTurn(language: String, block: () -> T): T {
        withTimeoutOrNull(maxPriorityWaitMs) {
            pending.first { order -> ahead(language, order).isEmpty() }
        }
        return try {
            mutex.withLock { block() }
        } finally {
            markLoaded(language)
        }
    }

    /** The pending languages [language] waits for: those before it, or all for an unnamed one. */
    private fun ahead(language: String, order: List<String>): List<String> {
        val index = order.indexOf(language)
        return if (index >= 0) order.subList(0, index) else order
    }

    companion object {
        /** Long enough for one German or English list on the slowest test phone (1.25 s). */
        const val DEFAULT_MAX_PRIORITY_WAIT_MS = 3_000L
    }
}
