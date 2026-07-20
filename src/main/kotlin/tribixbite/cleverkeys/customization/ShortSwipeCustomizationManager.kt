package tribixbite.cleverkeys.customization

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages custom short swipe mappings with JSON persistence.
 * Provides thread-safe CRUD operations and O(1) lookup for gesture handling.
 *
 * Uses device-protected storage for direct boot compatibility.
 */
class ShortSwipeCustomizationManager private constructor(private val context: Context) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /** Fast lookup map: "keyCode:direction" -> ShortSwipeMapping */
    private val mappingCache = ConcurrentHashMap<String, ShortSwipeMapping>()

    /**
     * Per-key index derived from [mappingCache]: `keyCode` (lowercased) -> (direction -> mapping).
     *
     * Read once per key per frame from the render thread ([Keyboard2View.onDraw] ->
     * `drawCustomMappings` -> [getMappingsForKey]). Previously [getMappingsForKey] did a full
     * `mappingCache.values.filter{}.associateBy{}` scan + two allocations on every read; this
     * pre-computed index makes the read an O(1), zero-allocation lookup (R2, ui-layer audit).
     *
     * Published via `@Volatile` reference-swap of an immutable snapshot. Mutations are rare and
     * always serialized under [fileMutex] (they rebuild + replace this whole reference in
     * [rebuildByKeyIndex]); reads are frequent and lock-free — they just observe the latest
     * fully-built immutable map. The inner maps are immutable snapshots too, so a per-frame reader
     * never sees a partially-populated key.
     */
    @Volatile
    private var byKeyIndex: Map<String, Map<SwipeDirection, ShortSwipeMapping>> = emptyMap()

    /**
     * Rebuild [byKeyIndex] from the current [mappingCache] contents and publish it atomically.
     *
     * MUST be called while holding [fileMutex] (all callers already do) so the rebuild reads a
     * quiescent [mappingCache]. The result is an immutable snapshot assigned to the `@Volatile`
     * field in a single reference write — a concurrent reader sees either the old or the new map,
     * never a half-built one.
     */
    private fun rebuildByKeyIndex() {
        byKeyIndex = buildByKeyIndex(mappingCache.values)
    }

    /** Mutex for file operations */
    private val fileMutex = Mutex()

    /** Observable state for UI updates */
    private val _mappingsFlow = MutableStateFlow<List<ShortSwipeMapping>>(emptyList())
    val mappingsFlow: StateFlow<List<ShortSwipeMapping>> = _mappingsFlow.asStateFlow()

    /** Whether data has been loaded */
    private var isLoaded = false

    /**
     * Get the customizations file path using device-protected storage for direct boot compatibility.
     */
    private fun getCustomizationsFile(): File {
        val storageContext = if (Build.VERSION.SDK_INT >= 24) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        return File(storageContext.filesDir, FILE_NAME)
    }

    /**
     * Load mappings from disk. Should be called once during initialization.
     * Thread-safe and idempotent.
     */
    suspend fun loadMappings() {
        if (isLoaded) return

        fileMutex.withLock {
            if (isLoaded) return // Double-check after acquiring lock

            withContext(Dispatchers.IO) {
                try {
                    val file = getCustomizationsFile()
                    if (file.exists()) {
                        val customizations = FileReader(file).use { reader ->
                            gson.fromJson(reader, ShortSwipeCustomizations::class.java)
                        }

                        customizations?.let {
                            val mappingList = it.toMappingList()
                            mappingCache.clear()
                            mappingList.forEach { mapping ->
                                mappingCache[mapping.toStorageKey()] = mapping
                            }
                            rebuildByKeyIndex()
                            _mappingsFlow.value = mappingList
                            Log.i(TAG, "Loaded ${mappingList.size} custom short swipe mappings")
                        }
                    } else {
                        Log.i(TAG, "No custom short swipe mappings file found, using defaults")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load custom mappings", e)
                }
            }
            isLoaded = true
        }
    }

    /**
     * Save current mappings to disk. Caller MUST hold [fileMutex].
     *
     * All mutators acquire the mutex around their cache mutation + this save so that
     * compound operations serialize with [loadMappings]'s clear+repopulate. Previously
     * mutations updated the cache OUTSIDE the lock: a mapping saved while the IME's
     * init-time load was in flight could be erased by the load's clear() and the loss
     * then persisted -- reproduced by PointersGestureRoutingTest.customMapping_
     * beatsWordCandidate under full-suite IO load.
     */
    private suspend fun saveMappingsLocked() {
        // Refresh the per-key render index from the just-mutated cache BEFORE persisting, so the
        // in-memory lookup used by onDraw stays consistent even if the file write below throws.
        // All mutators route through here under [fileMutex], so this is the single maintenance
        // point for the add/remove/clear/import paths (loadMappings rebuilds separately).
        rebuildByKeyIndex()
        withContext(Dispatchers.IO) {
            try {
                val file = getCustomizationsFile()
                val mappingList = mappingCache.values.toList()
                val customizations = ShortSwipeCustomizations.fromMappingList(mappingList)

                FileWriter(file).use { writer ->
                    gson.toJson(customizations, writer)
                }

                _mappingsFlow.value = mappingList
                Log.i(TAG, "Saved ${mappingList.size} custom short swipe mappings")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save custom mappings", e)
            }
        }
    }

    // ========== CRUD Operations ==========

    /**
     * Get a custom mapping for a specific key and direction.
     * O(1) lookup time.
     *
     * @param keyCode The key identifier (e.g., "a", "space")
     * @param direction The swipe direction
     * @return The custom mapping, or null if none exists
     */
    fun getMapping(keyCode: String, direction: SwipeDirection): ShortSwipeMapping? {
        val key = "${keyCode.lowercase()}:${direction.name}"
        return mappingCache[key]
    }

    /**
     * Get all mappings for a specific key.
     *
     * O(1), zero-allocation lookup against the pre-computed [byKeyIndex] (rebuilt on mutation).
     * Called per key per frame from [Keyboard2View]'s `onDraw`, so it must not allocate. Returns
     * the shared immutable inner map (or [emptyMap] when the key has no custom mappings); callers
     * must treat the result as read-only.
     *
     * @param keyCode The key identifier
     * @return Map of direction to mapping (immutable; empty when none)
     */
    fun getMappingsForKey(keyCode: String): Map<SwipeDirection, ShortSwipeMapping> {
        return byKeyIndex[keyCode.lowercase()] ?: emptyMap()
    }

    /**
     * Get all custom mappings.
     */
    fun getAllMappings(): List<ShortSwipeMapping> {
        return mappingCache.values.toList()
    }

    /**
     * Set or update a custom mapping.
     *
     * @param mapping The mapping to save
     */
    suspend fun setMapping(mapping: ShortSwipeMapping) {
        fileMutex.withLock {
            mappingCache[mapping.toStorageKey()] = mapping
            saveMappingsLocked()
        }
    }

    /**
     * Remove a custom mapping.
     *
     * @param keyCode The key identifier
     * @param direction The swipe direction
     * @return true if mapping existed and was removed
     */
    suspend fun removeMapping(keyCode: String, direction: SwipeDirection): Boolean {
        fileMutex.withLock {
            val key = "${keyCode.lowercase()}:${direction.name}"
            val removed = mappingCache.remove(key) != null
            if (removed) {
                saveMappingsLocked()
            }
            return removed
        }
    }

    /**
     * Remove all mappings for a specific key.
     *
     * @param keyCode The key identifier
     * @return Number of mappings removed
     */
    suspend fun removeMappingsForKey(keyCode: String): Int {
        fileMutex.withLock {
            val normalizedKey = keyCode.lowercase()
            val toRemove = mappingCache.keys.filter { it.startsWith("$normalizedKey:") }
            toRemove.forEach { mappingCache.remove(it) }

            if (toRemove.isNotEmpty()) {
                saveMappingsLocked()
            }
            return toRemove.size
        }
    }

    /**
     * Reset all custom mappings.
     */
    suspend fun resetAll() {
        fileMutex.withLock {
            mappingCache.clear()
            saveMappingsLocked()
        }
        Log.i(TAG, "Reset all custom short swipe mappings")
    }

    // ========== Export/Import ==========

    /**
     * Export mappings to JSON string for backup.
     */
    fun exportToJson(): String {
        val mappingList = mappingCache.values.toList()
        val customizations = ShortSwipeCustomizations.fromMappingList(mappingList)
        return gson.toJson(customizations)
    }

    /**
     * Import mappings from JSON string.
     *
     * @param json The JSON string to import
     * @param merge If true, merge with existing mappings; if false, replace all
     * @return Number of mappings imported
     */
    suspend fun importFromJson(json: String, merge: Boolean = false): Int {
        return try {
            // Parse outside the lock; mutate + persist inside it so the import can't
            // interleave with loadMappings' clear+repopulate or other mutators.
            val customizations = gson.fromJson(json, ShortSwipeCustomizations::class.java)
            val mappingList = customizations.toMappingList()

            fileMutex.withLock {
                if (!merge) {
                    mappingCache.clear()
                }
                mappingList.forEach { mapping ->
                    mappingCache[mapping.toStorageKey()] = mapping
                }
                saveMappingsLocked()
            }
            Log.i(TAG, "Imported ${mappingList.size} mappings (merge=$merge)")
            mappingList.size
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import mappings from JSON", e)
            0
        }
    }

    /**
     * Import mappings from a list of ShortSwipeMapping objects.
     *
     * @param mappings The list of mappings to import
     * @param merge If true, merge with existing mappings; if false, replace all
     * @return Number of mappings imported
     */
    suspend fun importFromMappings(mappings: List<ShortSwipeMapping>, merge: Boolean = false): Int {
        return try {
            fileMutex.withLock {
                if (!merge) {
                    mappingCache.clear()
                }
                mappings.forEach { mapping ->
                    mappingCache[mapping.toStorageKey()] = mapping
                }
                saveMappingsLocked()
            }
            Log.i(TAG, "Imported ${mappings.size} mappings from list (merge=$merge)")
            mappings.size
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import mappings from list", e)
            0
        }
    }

    // ========== Statistics ==========

    /**
     * Get the number of custom mappings.
     */
    val mappingCount: Int
        get() = mappingCache.size

    /**
     * Get the number of keys with custom mappings.
     */
    val customizedKeyCount: Int
        get() = mappingCache.values.map { it.keyCode }.toSet().size

    /**
     * Check if any custom mappings exist.
     */
    val hasCustomMappings: Boolean
        get() = mappingCache.isNotEmpty()

    companion object {
        private const val TAG = "ShortSwipeCustomMgr"
        private const val FILE_NAME = "short_swipe_customizations.json"

        /**
         * Build the per-key render index from a set of mappings.
         *
         * Pure (no Android/IO deps) so it is unit-testable and reused by [rebuildByKeyIndex].
         *
         * Semantics are byte-for-byte equivalent to the former per-frame lookup
         * `values.filter { it.keyCode == normalizedKey }.associateBy { it.direction }`:
         *  - grouped by the mapping's **raw** `keyCode` (NOT re-lowercased), because the old
         *    filter compared `it.keyCode` against the already-lowercased query — a mapping whose
         *    stored keyCode is uppercase was unreachable via a lowercase lookup then, and stays
         *    unreachable now. [getMappingsForKey] lowercases the query key before indexing.
         *  - within a key, later entries win on a direction collision, matching `associateBy`'s
         *    "last wins" behavior. (In practice the storage key is `keyCode:direction`, so the
         *    cache can hold at most one mapping per (keyCode,direction) pair anyway.)
         *
         * @return an immutable map of immutable inner maps, safe to publish and read lock-free.
         */
        internal fun buildByKeyIndex(
            mappings: Collection<ShortSwipeMapping>
        ): Map<String, Map<SwipeDirection, ShortSwipeMapping>> {
            if (mappings.isEmpty()) return emptyMap()
            val grouped = HashMap<String, LinkedHashMap<SwipeDirection, ShortSwipeMapping>>()
            for (m in mappings) {
                grouped.getOrPut(m.keyCode) { LinkedHashMap() }[m.direction] = m
            }
            // Freeze into immutable snapshots (inner maps too) for safe cross-thread publication.
            return grouped.mapValues { (_, inner) -> inner.toMap() }
        }

        // Process-lifetime singleton holding only the applicationContext (see getInstance),
        // so it never leaks an Activity/Service. The reference lives as long as the process.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: ShortSwipeCustomizationManager? = null

        /**
         * Get singleton instance.
         */
        fun getInstance(context: Context): ShortSwipeCustomizationManager {
            return instance ?: synchronized(this) {
                instance ?: ShortSwipeCustomizationManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
