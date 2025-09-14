package juloo.keyboard2.prefs

import android.content.SharedPreferences
import juloo.keyboard2.KeyValue
import juloo.keyboard2.KeyboardData

/**
 * Custom extra keys preference management
 */
object CustomExtraKeysPreference {
    
    /**
     * Get custom extra keys
     */
    fun get(prefs: SharedPreferences): Map<KeyValue, KeyboardData.PreferredPos> {
        return emptyMap()
    }
}