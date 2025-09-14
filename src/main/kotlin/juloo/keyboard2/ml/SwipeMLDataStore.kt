package juloo.keyboard2.ml

import android.content.Context

/**
 * ML data storage for training data
 * Kotlin implementation with simplified interface
 */
class SwipeMLDataStore private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "SwipeMLDataStore"
        private var instance: SwipeMLDataStore? = null
        
        fun getInstance(context: Context): SwipeMLDataStore {
            return instance ?: synchronized(this) {
                instance ?: SwipeMLDataStore(context).also { instance = it }
            }
        }
    }
    
    private val storedData = mutableListOf<SwipeMLData>()
    
    /**
     * Store swipe data for training
     */
    fun storeSwipeData(data: SwipeMLData) {
        storedData.add(data)
        android.util.Log.d(TAG, "Stored swipe data for '${data.targetWord}' (${storedData.size} total)")
    }
    
    /**
     * Load data by source
     */
    fun loadDataBySource(source: String): List<SwipeMLData> {
        return storedData.filter { it.collectionSource == source }
    }
    
    /**
     * Get all stored data
     */
    fun getAllData(): List<SwipeMLData> = storedData.toList()
    
    /**
     * Clear all data
     */
    fun clearAll() {
        storedData.clear()
        android.util.Log.d(TAG, "All training data cleared")
    }
    
    /**
     * Get statistics
     */
    fun getStats(): DataStoreStats {
        return DataStoreStats(
            totalRecords = storedData.size,
            calibrationRecords = loadDataBySource("neural_calibration").size,
            userRecords = loadDataBySource("user_typing").size
        )
    }
    
    data class DataStoreStats(
        val totalRecords: Int,
        val calibrationRecords: Int,
        val userRecords: Int
    )
}