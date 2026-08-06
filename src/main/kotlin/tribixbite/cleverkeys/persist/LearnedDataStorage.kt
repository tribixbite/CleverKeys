package tribixbite.cleverkeys.persist

/**
 * Minimal string key-value storage abstraction for learned-data stores
 * (bigram LM, user vocabulary).
 *
 * Exists so the persistence logic in [tribixbite.cleverkeys.contextaware.BigramStore]
 * and [tribixbite.cleverkeys.personalization.UserVocabulary] is pure-JVM testable:
 * production wires a SharedPreferences adapter ([SharedPrefsLearnedStorage]),
 * tests wire an in-memory implementation.
 *
 * Implementations must be safe to call from any thread.
 */
interface LearnedDataStorage {
    /** @return stored value for [key], or null if absent. */
    fun getString(key: String): String?

    /** Store [value] under [key], replacing any previous value. */
    fun putString(key: String, value: String)

    /** Remove [key] if present. */
    fun remove(key: String)

    /** @return all keys currently present in this storage. */
    fun keys(): Set<String>
}
