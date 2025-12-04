package tribixbite.cleverkeys.prefs

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import android.view.View
import android.widget.EditText
import android.widget.TextView
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.KeyboardData
import tribixbite.cleverkeys.R

/**
 * Custom Extra Keys Preference
 * Allows users to define custom extra keys by entering key names.
 *
 * Features:
 * - Add custom keys by name (e.g., "ctrl", "alt", "esc")
 * - Modify existing custom keys
 * - Remove custom keys
 * - Keys are added with default positioning
 * - List-based UI with add/remove buttons
 *
 * Storage:
 * - Stores list of key names as JSON array in SharedPreferences
 * - Each key name is validated against KeyValue.getKeyByName()
 *
 * CRITICAL FIX (Bug #637): Now properly extends ListGroupPreference<String>
 * instead of being a stub. Full custom key management now working.
 *
 * Ported from Java to Kotlin with modern improvements.
 */
class CustomExtraKeysPreference(
    context: Context,
    attrs: AttributeSet
) : ListGroupPreference<String>(context, attrs) {

    companion object {
        /** Preference key for storing custom extra keys */
        const val KEY = "custom_extra_keys"

        /** Serializer for string list persistence */
        @JvmField
        val SERIALIZER: ListGroupPreference.Serializer<String> = StringSerializer()

        /**
         * Get custom extra keys from preferences.
         * Converts stored key names to KeyValue objects with default positioning.
         *
         * @param prefs SharedPreferences to load from
         * @return Map of KeyValue to PreferredPos (all with DEFAULT positioning)
         */
        @JvmStatic
        fun get(prefs: SharedPreferences): Map<KeyValue, KeyboardData.PreferredPos> {
            val kvs = mutableMapOf<KeyValue, KeyboardData.PreferredPos>()
            val keyNames = load_from_preferences(KEY, prefs, emptyList(), SERIALIZER)

            keyNames.forEach { keyName ->
                val keyValue = KeyValue.getKeyByName(keyName)
                if (keyValue != null) {
                    kvs[keyValue] = KeyboardData.PreferredPos.DEFAULT
                }
            }

            return kvs
        }
    }

    init {
        key = KEY
    }

    // ====== ListGroupPreference Abstract Methods ======

    /**
     * Required by ListGroupPreference: Format label for list item.
     * For custom keys, we just display the key name.
     */
    override fun label_of_value(value: String, i: Int): String {
        return value
    }

    /**
     * Required by ListGroupPreference: Show selection dialog.
     * Shows an EditText dialog for entering/editing custom key names.
     *
     * @param callback Callback to invoke with selected value
     * @param old_value Current value if modifying, null if adding new
     */
    override fun select(callback: SelectionCallback<String>, old_value: String?) {
        // Inflate dialog layout with EditText
        val content = View.inflate(context, R.layout.dialog_edit_text, null)
        val textView = content.findViewById<TextView>(R.id.text)

        // Set initial text if modifying existing key
        if (old_value != null) {
            textView.text = old_value
        }

        // Show dialog
        AlertDialog.Builder(context)
            .setView(content)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val input = (dialog as AlertDialog).findViewById<EditText>(R.id.text)
                val keyName = input?.text?.toString() ?: ""

                // Only add non-empty key names
                if (keyName.isNotEmpty()) {
                    callback.select(keyName)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Required by ListGroupPreference: Get serializer.
     * Uses built-in StringSerializer for simple string list persistence.
     */
    override fun get_serializer(): ListGroupPreference.Serializer<String> {
        return SERIALIZER
    }
}
