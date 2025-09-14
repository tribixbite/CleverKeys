package juloo.keyboard2.prefs

import android.content.SharedPreferences
import juloo.keyboard2.KeyValue
import juloo.keyboard2.KeyboardData

/**
 * Extra keys preference management
 */
object ExtraKeysPreference {
    
    /**
     * Get extra keys configuration
     */
    fun get_extra_keys(prefs: SharedPreferences): Map<KeyValue, KeyboardData.PreferredPos> {
        return emptyMap()
    }
}