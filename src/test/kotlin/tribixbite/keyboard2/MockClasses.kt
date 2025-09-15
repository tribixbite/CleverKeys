package tribixbite.keyboard2

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources

/**
 * Mock classes for testing CleverKeys components
 * Kotlin implementation with test-friendly interfaces
 */

/**
 * Mock SharedPreferences for testing
 */
class MockSharedPreferences : SharedPreferences {
    
    private val data = mutableMapOf<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    
    override fun getAll(): Map<String, *> = data.toMap()
    
    override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = data[key] as? Set<String> ?: defValues
    override fun getInt(key: String, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = data.containsKey(key)
    
    override fun edit(): SharedPreferences.Editor = MockEditor()
    
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.add(listener)
    }
    
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.remove(listener)
    }
    
    // Test helpers
    fun putInt(key: String, value: Int) = data.put(key, value)
    fun putFloat(key: String, value: Float) = data.put(key, value)
    fun putBoolean(key: String, value: Boolean) = data.put(key, value)
    fun putString(key: String, value: String) = data.put(key, value)
    
    private inner class MockEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        
        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }
        
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor {
            pending[key] = values
            return this
        }
        
        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            pending[key] = value
            return this
        }
        
        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            pending[key] = value
            return this
        }
        
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            pending[key] = value
            return this
        }
        
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            pending[key] = value
            return this
        }
        
        override fun remove(key: String): SharedPreferences.Editor {
            pending[key] = null
            return this
        }
        
        override fun clear(): SharedPreferences.Editor {
            pending.clear()
            data.clear()
            return this
        }
        
        override fun commit(): Boolean {
            data.putAll(pending)
            pending.clear()
            notifyListeners()
            return true
        }
        
        override fun apply() {
            commit()
        }
        
        private fun notifyListeners() {
            listeners.forEach { listener ->
                pending.keys.forEach { key ->
                    listener.onSharedPreferenceChanged(this@MockSharedPreferences, key)
                }
            }
        }
    }
}

/**
 * Mock Context for testing
 */
class MockContext : Context() {
    
    private val mockAssets = MockAssetManager()
    private val mockResources = MockResources()
    
    override fun getAssets() = mockAssets
    override fun getResources() = mockResources
    override fun getPackageName() = "tribixbite.keyboard2.test"
    override fun getApplicationContext() = this
    
    // Stub implementations for required methods
    override fun getSystemService(name: String): Any? = null
    override fun getString(resId: Int): String = "test_string"
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = MockSharedPreferences()
    
    // Other required overrides with minimal implementations
    override fun getTheme(): android.content.res.Resources.Theme? = null
    override fun getClassLoader(): ClassLoader = javaClass.classLoader
    override fun getMainLooper(): android.os.Looper = android.os.Looper.getMainLooper()
    override fun getApplicationInfo(): android.content.pm.ApplicationInfo = android.content.pm.ApplicationInfo()
}

/**
 * Mock AssetManager for testing
 */
class MockAssetManager : android.content.res.AssetManager() {
    
    private val mockFiles = mapOf(
        "dictionaries/en.txt" to "the\nand\nfor\nyou\nthat\nhello\nworld\ntest",
        "dictionaries/en_enhanced.txt" to "keyboard\nswipe\ntyping\nneural\nprediction"
    )
    
    override fun open(fileName: String): java.io.InputStream {
        val content = mockFiles[fileName] ?: throw java.io.FileNotFoundException("Mock file not found: $fileName")
        return content.byteInputStream()
    }
}

/**
 * Mock Resources for testing
 */
class MockResources : Resources(MockAssetManager(), android.util.DisplayMetrics(), android.content.res.Configuration()) {
    
    override fun getIdentifier(name: String, defType: String, defPackage: String): Int {
        return when (name) {
            "app_name" -> 1
            "latn_qwerty_us" -> 2
            else -> 0
        }
    }
    
    override fun getString(id: Int): String {
        return when (id) {
            1 -> "CleverKeys Test"
            else -> "test_string_$id"
        }
    }
    
    override fun getDimension(id: Int): Float = 16f
}