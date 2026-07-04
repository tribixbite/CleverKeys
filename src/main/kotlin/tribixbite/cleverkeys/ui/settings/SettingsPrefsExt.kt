package tribixbite.cleverkeys.ui.settings

import android.content.SharedPreferences

/** Safely get an Int preference, handling cases where the value is stored as a different type */
internal fun SharedPreferences.getSafeInt(key: String, default: Int): Int {
    return try {
        getInt(key, default)
    } catch (e: ClassCastException) {
        // Value might be stored as String, try to parse it
        try {
            getString(key, null)?.toIntOrNull() ?: default
        } catch (e2: Exception) {
            default
        }
    }
}

/** Safely get a Float preference, handling cases where the value is stored as a different type */
internal fun SharedPreferences.getSafeFloat(key: String, default: Float): Float {
    return try {
        getFloat(key, default)
    } catch (e: ClassCastException) {
        try {
            getString(key, null)?.toFloatOrNull() ?: default
        } catch (e2: Exception) {
            default
        }
    }
}

/** Safely get a String preference, handling cases where the value is stored as Int or other types */
internal fun SharedPreferences.getSafeString(key: String, default: String): String {
    return try {
        getString(key, default) ?: default
    } catch (e: ClassCastException) {
        // Value might be stored as Int (e.g., from config import)
        try {
            getInt(key, -999999).let {
                if (it == -999999) default else it.toString()
            }
        } catch (e2: ClassCastException) {
            // Try Float
            try {
                getFloat(key, Float.MIN_VALUE).let {
                    if (it == Float.MIN_VALUE) default else it.toString()
                }
            } catch (e3: Exception) {
                default
            }
        } catch (e2: Exception) {
            default
        }
    }
}

/** Safely get a Boolean preference, handling cases where the value is stored as String or Int */
internal fun SharedPreferences.getSafeBoolean(key: String, default: Boolean): Boolean {
    return try {
        getBoolean(key, default)
    } catch (e: ClassCastException) {
        // Value might be stored as String or Int
        try {
            val stringVal = getString(key, null)
            when (stringVal?.lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> default
            }
        } catch (e2: ClassCastException) {
            try {
                getInt(key, -1).let {
                    when (it) {
                        1 -> true
                        0 -> false
                        else -> default
                    }
                }
            } catch (e3: Exception) {
                default
            }
        } catch (e2: Exception) {
            default
        }
    }
}
