package tribixbite.keyboard2

import android.content.Context
import android.content.SharedPreferences

/**
 * Direct boot aware preferences access
 * Kotlin object for singleton pattern
 */
object DirectBootAwarePreferences {
    
    private const val PREF_NAME = "keyboard_preferences"
    
    /**
     * Get shared preferences instance
     */
    fun get_shared_preferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Copy preferences to protected storage (simplified implementation)
     */
    fun copy_preferences_to_protected_storage(context: Context, prefs: SharedPreferences) {
        // In a real implementation, this would copy to device protected storage
        // For now, this is a no-op since we're using regular shared preferences
    }
}