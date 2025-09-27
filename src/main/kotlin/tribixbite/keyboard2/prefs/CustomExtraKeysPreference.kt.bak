package tribixbite.keyboard2.prefs

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.preference.Preference
import android.preference.PreferenceCategory
import android.util.AttributeSet
import android.view.View
import android.widget.EditText
import android.widget.TextView
import tribixbite.keyboard2.*
import tribixbite.keyboard2.data.KeyValue
import tribixbite.keyboard2.data.KeyboardData

/**
 * Custom extra keys preference for CleverKeys.
 * Allows users to define custom keys to be added to the keyboard layout.
 *
 * Features:
 * - User-defined key names through text input dialog
 * - Dynamic validation of key names
 * - Default positioning for custom keys
 * - Integration with keyboard layout system
 */
class CustomExtraKeysPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ListGroupPreference<String>(context, attrs) {

    companion object {
        /** Preference key for storing custom extra keys */
        const val KEY = "custom_extra_keys"

        /** String serializer for custom keys */
        @JvmField
        val SERIALIZER: ListGroupPreference.StringSerializer = ListGroupPreference.StringSerializer()

        /**
         * Get custom extra keys from SharedPreferences.
         * Returns map of KeyValue to default preferred positions.
         */
        @JvmStatic
        fun get(prefs: SharedPreferences): Map<KeyValue, KeyboardData.PreferredPos> {
            val keyValueMap = mutableMapOf<KeyValue, KeyboardData.PreferredPos>()
            val keyNames = loadFromPreferences(KEY, prefs, null, SERIALIZER)

            if (keyNames != null) {
                for (keyName in keyNames) {
                    try {
                        val keyValue = KeyValue.getKeyByName(keyName)
                        keyValueMap[keyValue] = KeyboardData.PreferredPos.DEFAULT
                    } catch (e: Exception) {
                        // Skip invalid key names
                        continue
                    }
                }
            }

            return keyValueMap
        }
    }

    init {
        key = KEY
    }

    /**
     * Get display label for a custom key value.
     */
    override fun labelOfValue(value: String, index: Int): String = value

    /**
     * Show dialog for entering/editing custom key name.
     */
    override fun select(callback: SelectionCallback, oldValue: String?) {
        val dialogView = View.inflate(context, R.layout.dialog_edit_text, null)
        val textView = dialogView.findViewById<TextView>(R.id.text)

        // Set existing value if editing
        if (oldValue != null) {
            textView.text = oldValue
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.pref_custom_extra_keys_title)
            .setMessage(R.string.pref_custom_extra_keys_message)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val editText = (dialog as AlertDialog).findViewById<EditText>(R.id.text)
                val keyName = editText.text.toString().trim()

                if (keyName.isNotEmpty() && isValidKeyName(keyName)) {
                    callback.select(keyName)
                } else {
                    // Show error for invalid key name
                    showInvalidKeyNameError(keyName)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Validate that a key name is valid and can be used.
     */
    private fun isValidKeyName(keyName: String): Boolean {
        return try {
            // Try to create a KeyValue from the name
            KeyValue.getKeyByName(keyName)
            true
        } catch (e: Exception) {
            // Check if it's a simple character or Unicode name
            when {
                keyName.length == 1 -> true // Single character
                keyName.startsWith("\\u") && keyName.length == 6 -> true // Unicode escape
                keyName.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*")) -> true // Valid identifier
                else -> false
            }
        }
    }

    /**
     * Show error dialog for invalid key names.
     */
    private fun showInvalidKeyNameError(keyName: String) {
        AlertDialog.Builder(context)
            .setTitle(R.string.error_invalid_key_name_title)
            .setMessage(context.getString(R.string.error_invalid_key_name_message, keyName))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun getSerializer(): ListGroupPreference.Serializer<String> = SERIALIZER
}