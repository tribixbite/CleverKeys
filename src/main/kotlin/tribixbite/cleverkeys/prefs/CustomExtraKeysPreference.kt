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
 * Allows to enter custom keys to be added to the keyboard. This shows up at
 * the top of the "Add keys to the keyboard" option.
 */
class CustomExtraKeysPreference(
    context: Context,
    attrs: AttributeSet?
) : ListGroupPreference<String>(context, attrs) {

    init {
        key = KEY
    }

    override fun labelOfValue(value: String, i: Int): String = value

    override fun select(callback: SelectionCallback<String>, oldValue: String?) {
        val content = View.inflate(context, R.layout.dialog_edit_text, null)
        (content.findViewById<View>(R.id.text) as TextView).text = oldValue

        AlertDialog.Builder(context)
            .setView(content)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val input = (dialog as AlertDialog).findViewById<EditText>(R.id.text)
                val k = input.text.toString()
                if (k.isNotEmpty()) {
                    callback.select(k)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun getSerializer(): Serializer<String> = SERIALIZER

    companion object {
        /** This pref stores a list of strings encoded as JSON. */
        const val KEY = "custom_extra_keys"
        val SERIALIZER: Serializer<String> = StringSerializer()

        @JvmStatic
        fun get(prefs: SharedPreferences): Map<KeyValue, KeyboardData.PreferredPos> {
            val kvs = mutableMapOf<KeyValue, KeyboardData.PreferredPos>()
            val keyNames = loadFromPreferences(KEY, prefs, null, SERIALIZER)
            if (keyNames != null) {
                for (keyName in keyNames) {
                    kvs[KeyValue.getKeyByName(keyName)] = KeyboardData.PreferredPos.DEFAULT
                }
            }
            return kvs
        }
    }
}
