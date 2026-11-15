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
 * State is guarded by this object's monitor (provider reads happen on swipe/lexicon worker
 * threads while observer lifecycle and callbacks normally run on the main thread), but the
 * provider [read] itself runs OUTSIDE it (review 2026-11-14: holding the monitor across a binder
 * query blocked the main-thread observer callback for the query's whole duration). A read is
 * cached only if the epoch is unchanged when it completes, so rows read before a change are
 * never published under the post-change epoch. A failed read ([read] returns null) is served as
 * empty and never cached: one transient provider failure must not hide the user's words until
 * the next provider change.
 */
internal class UserDictionarySnapshotCache {
    private data class Entry(val epoch: Long, val snapshot: UserDictionarySnapshot)

    private val entries = HashMap<String, Entry>()
    private var epoch = 0L
    private var observerCount = 0

    internal val isCaching: Boolean
        @Synchronized get() = observerCount > 0

    fun snapshot(language: String, read: (String) -> UserDictionarySnapshot?): UserDictionarySnapshot {
        val startEpoch = synchronized(this) {
            if (observerCount <= 0) {
                NOT_CACHING
            } else {
                entries[language]?.let { if (it.epoch == epoch) return it.snapshot }
                epoch
            }
        }
        val fresh = read(language) ?: return UserDictionarySnapshot.EMPTY
        if (startEpoch != NOT_CACHING) {
            synchronized(this) {
                if (observerCount > 0 && epoch == startEpoch) entries[language] = Entry(epoch, fresh)
            }
        }
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
        // A newly registered observer cannot account for changes that occurred before start;
        // the epoch bump also keeps a read that began before this from being cached.
        epoch++
        entries.clear()
    }

    @Synchronized
    fun observerStopped() {
        if (observerCount > 0) observerCount--
        epoch++
        if (observerCount == 0) entries.clear()
    }

    private companion object {
        /** [snapshot]'s marker for a read made while no observer runs (never cached). */
        const val NOT_CACHING = -1L
    }
}
