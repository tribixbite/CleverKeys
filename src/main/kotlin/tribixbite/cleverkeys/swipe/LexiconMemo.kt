package tribixbite.cleverkeys.swipe

/**
 * The per-language lexicon memo both swipe adapters keep: [CtcEngineAdapter]'s trie memo and
 * [GeometricEngineAdapter]'s dictionary memo.
 *
 * A bounded, access-ordered LRU of at most [capacity] languages (two: the bilingual pair), so a
 * third language evicts the least recently used one. An entry is valid only while the caller's
 * `isCurrent` predicate accepts it: the adapters check the language AND the
 * [LexiconContentVersion] the entry was built for.
 *
 * **A miss releases the stale entry before the caller builds its replacement** ([current]).
 * Until 2026-11-26 the stale entry stayed in the map for the whole rebuild and was replaced only
 * at the end, so one custom-word add held the old English trie (28.25 MiB retained) and the new
 * one at the same time (`docs/audit/2026-11-26-memory-analysis.md`, candidate 2). A stale entry is
 * never served again once its version stopped matching, so releasing it early changes no output.
 *
 * Not thread-safe, by design: both adapters confine every memo access to the single background
 * thread of their `PredictionTaskRunner` (`docs/specs/ctc-swipe-engine.md`, concurrency contract).
 * A decode therefore never runs while its own thread is building. A decode that keeps a strong
 * reference to the value it got from [current] decodes on that snapshot to the end, however the
 * memo changes afterwards; a decode that cancels a build finds the slot empty and builds the
 * complete replacement itself, so it can never see a partial value.
 *
 * Pure JVM: `kotlin.*` and `java.util.*` only.
 */
class LexiconMemo<T : Any>(private val capacity: Int) {

    init {
        require(capacity >= 1) { "capacity must be >= 1, was $capacity" }
    }

    private val entries = object : LinkedHashMap<String, T>(capacity + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?): Boolean =
            size > capacity
    }

    /**
     * The entry for [language] when [isCurrent] accepts it (a hit also marks it most recently
     * used). On a miss the stale entry, if any, is dropped from the memo before this returns, so
     * the caller's rebuild does not run with the old value still reachable from here.
     */
    fun current(language: String, isCurrent: (T) -> Boolean): T? {
        val entry = entries[language] ?: return null
        if (isCurrent(entry)) return entry
        entries.remove(language)
        return null
    }

    /** Stores the freshly built [value] for [language] (evicting the eldest language at capacity). */
    operator fun set(language: String, value: T) {
        entries[language] = value
    }

    /** Drops [language]'s entry only; the other language's entry is kept. */
    fun remove(language: String) {
        entries.remove(language)
    }

    /** The memoized languages, least recently used first. */
    fun languages(): List<String> = entries.keys.toList()
}
