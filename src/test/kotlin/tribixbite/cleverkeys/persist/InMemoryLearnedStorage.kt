package tribixbite.cleverkeys.persist

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory [LearnedDataStorage] for pure-JVM tests.
 *
 * Counts writes so debounce-coalescing tests can assert a bounded number of
 * storage writes for many record calls. Constructing a NEW store instance over
 * the same InMemoryLearnedStorage simulates process death + restart (only
 * flushed data survives).
 */
class InMemoryLearnedStorage : LearnedDataStorage {
    private val map = ConcurrentHashMap<String, String>()
    val putCount = AtomicInteger(0)
    val removeCount = AtomicInteger(0)

    override fun getString(key: String): String? = map[key]

    override fun putString(key: String, value: String) {
        putCount.incrementAndGet()
        map[key] = value
    }

    override fun remove(key: String) {
        removeCount.incrementAndGet()
        map.remove(key)
    }

    override fun keys(): Set<String> = map.keys.toSet()

    /** Direct seeding for migration/corruption tests (does not bump counters). */
    fun seed(key: String, value: String) {
        map[key] = value
    }
}
