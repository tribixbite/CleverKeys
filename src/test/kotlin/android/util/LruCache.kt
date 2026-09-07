package android.util

/**
 * Test-classpath replacement for the SDK stub `android.util.LruCache` (the
 * `android.graphics.PointF` pattern: both custom runners put compiled test classes
 * BEFORE `android.jar`, so this functional access-ordered LRU wins at runtime).
 *
 * Needed so `LayoutModifier.modify_layout`'s cache (audit H-5: name-keyed collision)
 * can be exercised on the JVM instead of dying in the throwing stub constructor.
 * Mirrors the AOSP class as consumed by production code — synchronized get/put with
 * access-order eviction at [maxSize]. Do not add behaviour the on-device class does
 * not have.
 */
open class LruCache<K : Any, V : Any>(private val maxSize: Int) {

    private val map = LinkedHashMap<K, V>(0, 0.75f, true)

    @Synchronized
    fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V): V? {
        val previous = map.put(key, value)
        while (map.size > maxSize) {
            val eldest = map.entries.iterator()
            eldest.next()
            eldest.remove()
        }
        return previous
    }

    @Synchronized
    fun remove(key: K): V? = map.remove(key)

    @Synchronized
    fun evictAll() {
        map.clear()
    }

    @Synchronized
    fun size(): Int = map.size

    @Synchronized
    fun snapshot(): Map<K, V> = LinkedHashMap(map)
}
