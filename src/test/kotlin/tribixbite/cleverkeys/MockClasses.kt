package tribixbite.cleverkeys

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
 * Mock Context for testing using ContextWrapper
 * This avoids having to implement all abstract Context methods
 */
class MockContext : android.content.ContextWrapper(null) {

    private val mockPrefs = MockSharedPreferences()

    override fun getPackageName() = "tribixbite.cleverkeys.test"
    override fun getApplicationContext(): Context = this

    // Stub implementations for required methods
    override fun getSystemService(name: String): Any? = null
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = mockPrefs

    override fun getClassLoader(): ClassLoader = javaClass.classLoader
    override fun getMainLooper(): android.os.Looper = android.os.Looper.getMainLooper()
    override fun getApplicationInfo(): android.content.pm.ApplicationInfo = android.content.pm.ApplicationInfo()
}