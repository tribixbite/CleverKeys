package tribixbite.cleverkeys

import tribixbite.cleverkeys.swipe.UserDictionarySnapshot

/**
 * Epoch-gated cache for platform user-dictionary snapshots (ARC-102).
 *
 * A snapshot may be reused only while at least one [UserDictionaryObserver] is registered.
 * Every provider callback advances [epoch], invalidating every language at once because the
 * platform callback does not identify which locale changed. When the final observer stops the
 * cache is cleared and disabled; otherwise an edit made while nobody was listening could leave a
 * process-lifetime stale snapshot and reintroduce ARC-081.
 *
 * Methods are synchronized because provider reads happen on swipe/lexicon worker threads while
 * observer lifecycle and callbacks normally run on the main thread. Holding the lock across
 * [read] is deliberate: it prevents a provider callback from advancing the epoch between the read
 * and cache insertion, which could publish pre-change rows under the post-change epoch.
 */
internal class UserDictionarySnapshotCache {
    private data class Entry(val epoch: Long, val snapshot: UserDictionarySnapshot)

    private val entries = HashMap<String, Entry>()
    private var epoch = 0L
    private var observerCount = 0

    internal val isCaching: Boolean
        @Synchronized get() = observerCount > 0

    @Synchronized
    fun snapshot(language: String, read: (String) -> UserDictionarySnapshot): UserDictionarySnapshot {
        if (observerCount <= 0) return read(language)
        entries[language]?.let { if (it.epoch == epoch) return it.snapshot }
        val fresh = read(language)
        entries[language] = Entry(epoch, fresh)
        return fresh
    }

    @Synchronized
    fun providerChanged() {
        epoch++
        entries.clear()
    }

    @Synchronized
    fun observerStarted() {
        observerCount++
        // A newly registered observer cannot account for changes that occurred before start.
        entries.clear()
    }

    @Synchronized
    fun observerStopped() {
        if (observerCount > 0) observerCount--
        if (observerCount == 0) entries.clear()
    }
}
