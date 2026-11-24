package tribixbite.cleverkeys.ui.settings

import android.content.SharedPreferences
import tribixbite.cleverkeys.Config

/*
 * The Settings screen's type-tolerant preference readers. A value can be stored with a type other
 * than the one its screen reads: the import writes each key with the import table's type
 * (`custom_border_line_width` as a Float, while its slider writes an Int), an older backup wrote
 * numbers as strings, and a hand-edited file can hold anything. A raw getter throws
 * ClassCastException on such a value, and in the change handler that throw takes down the whole
 * process, keyboard included (device test 2026-11-23). These delegate to Config's readers, so the
 * Settings screen and the keyboard recover the same value from the same stored data (pinned by
 * SafePrefReadersTest.theSettingsScreenReadersAgreeWithConfigForEveryStoredType).
 */

/** An Int preference, also when it is stored as a Float (truncated) or a numeric String. */
internal fun SharedPreferences.getSafeInt(key: String, default: Int): Int =
    Config.safeGetInt(this, key, default)

/** A Float preference, also when it is stored as an Int or a numeric String. */
internal fun SharedPreferences.getSafeFloat(key: String, default: Float): Float =
    Config.safeGetFloat(this, key, default)

/** A String preference, also when it is stored as an Int, a Float or a Boolean. */
internal fun SharedPreferences.getSafeString(key: String, default: String): String =
    Config.safeGetString(this, key, default)

/** A Boolean preference, also when it is stored as "true"/"1"/"yes"-style text or as 1/0. */
internal fun SharedPreferences.getSafeBoolean(key: String, default: Boolean): Boolean =
    Config.safeGetBoolean(this, key, default)
