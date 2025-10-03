package tribixbite.keyboard2.prefs

import android.content.Context
import android.content.SharedPreferences
import android.preference.Preference
import android.util.AttributeSet
import tribixbite.keyboard2.KeyValue
import tribixbite.keyboard2.KeyboardData

/**
 * Custom Extra Keys Preference
 * Allows users to define custom extra keys with specific positions.
 *
 * TODO: Full implementation pending
 * - Key picker dialog with all available keys
 * - Position configuration (row, column, direction)
 * - Persistence to SharedPreferences
 * - Visual keyboard preview for positioning
 *
 * For now, this is a placeholder to prevent crashes when referenced in settings.xml
 */
class CustomExtraKeysPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    companion object {
        private const val PREF_KEY = "custom_extra_keys_data"

        /**
         * Get custom extra keys from preferences.
         *
         * TODO: Parse and return user-defined custom keys
         * Format: JSON array of {keyName, row, col, direction}
         */
        @JvmStatic
        fun get(prefs: SharedPreferences): Map<KeyValue, KeyboardData.PreferredPos> {
            // TODO: Implement custom key parsing from preferences
            // val customKeysJson = prefs.getString(PREF_KEY, "[]")
            // return parseCustomKeys(customKeysJson)
            return emptyMap()
        }

        /**
         * Save custom extra keys to preferences.
         *
         * TODO: Serialize custom keys to JSON and save
         */
        @JvmStatic
        fun save(prefs: SharedPreferences, keys: Map<KeyValue, KeyboardData.PreferredPos>) {
            // TODO: Implement serialization
            // val json = serializeCustomKeys(keys)
            // prefs.edit().putString(PREF_KEY, json).apply()
        }
    }

    init {
        title = "Custom Extra Keys"
        summary = "Add your own custom keys (Feature coming soon)"
        isEnabled = false // Disable until implemented
    }

    override fun onClick() {
        // TODO: Show custom key picker dialog
        // - Grid of all available keys
        // - Position configuration
        // - Preview of keyboard with selected keys
        android.widget.Toast.makeText(
            context,
            "Custom extra keys feature is under development",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}
