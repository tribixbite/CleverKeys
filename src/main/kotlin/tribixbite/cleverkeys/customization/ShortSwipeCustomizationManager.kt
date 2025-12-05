package tribixbite.cleverkeys.customization

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
     * Save current mappings to disk.
     */
    private suspend fun saveMappings() {
        fileMutex.withLock {
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
     * @param keyCode The key identifier
     * @return Map of direction to mapping
     */
    fun getMappingsForKey(keyCode: String): Map<SwipeDirection, ShortSwipeMapping> {
        val normalizedKey = keyCode.lowercase()
        return mappingCache.values
            .filter { it.keyCode == normalizedKey }
            .associateBy { it.direction }
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
        mappingCache[mapping.toStorageKey()] = mapping
        saveMappings()
    }

    /**
     * Remove a custom mapping.
     *
     * @param keyCode The key identifier
     * @param direction The swipe direction
     * @return true if mapping existed and was removed
     */
    suspend fun removeMapping(keyCode: String, direction: SwipeDirection): Boolean {
        val key = "${keyCode.lowercase()}:${direction.name}"
        val removed = mappingCache.remove(key) != null
        if (removed) {
            saveMappings()
        }
        return removed
    }

    /**
     * Remove all mappings for a specific key.
     *
     * @param keyCode The key identifier
     * @return Number of mappings removed
     */
    suspend fun removeMappingsForKey(keyCode: String): Int {
        val normalizedKey = keyCode.lowercase()
        val toRemove = mappingCache.keys.filter { it.startsWith("$normalizedKey:") }
        toRemove.forEach { mappingCache.remove(it) }

        if (toRemove.isNotEmpty()) {
            saveMappings()
        }
        return toRemove.size
    }

    /**
     * Reset all custom mappings.
     */
    suspend fun resetAll() {
        mappingCache.clear()
        saveMappings()
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
            val customizations = gson.fromJson(json, ShortSwipeCustomizations::class.java)
            val mappingList = customizations.toMappingList()

            if (!merge) {
                mappingCache.clear()
            }

            mappingList.forEach { mapping ->
                mappingCache[mapping.toStorageKey()] = mapping
            }

            saveMappings()
            Log.i(TAG, "Imported ${mappingList.size} mappings (merge=$merge)")
            mappingList.size
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import mappings from JSON", e)
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
