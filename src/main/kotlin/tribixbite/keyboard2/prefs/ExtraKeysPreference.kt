package tribixbite.keyboard2.prefs

import android.content.SharedPreferences
import tribixbite.keyboard2.KeyValue
import tribixbite.keyboard2.KeyboardData

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