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
}