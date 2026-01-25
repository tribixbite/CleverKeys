package tribixbite.cleverkeys

import android.annotation.TargetApi
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.preference.PreferenceManager

@TargetApi(24)
object DirectBootAwarePreferences {
    /**
     * On API >= 24, preferences are read from the device protected storage. This
     * storage is less protected than the default, no personal or sensitive
     * information is stored there (only the keyboard settings). This storage is
     * accessible during boot and allow the keyboard to read its settings and
     * allow typing the storage password.
     */
    @JvmStatic
    fun get_shared_preferences(context: Context): SharedPreferences {
        if (Build.VERSION.SDK_INT < 24) {
            return PreferenceManager.getDefaultSharedPreferences(context)
        }
        val prefs = getProtectedPrefs(context)
        checkNeedMigration(context, prefs)
        return prefs
    }

    /**
     * Copy shared preferences to device protected storage. Not using
     * [Context.moveSharedPreferencesFrom] because the settings activity still
     * use [PreferenceActivity], which can't work on a non-default shared
     * preference file.
     */
    @JvmStatic
    fun copy_preferences_to_protected_storage(context: Context, src: SharedPreferences) {
        if (Build.VERSION.SDK_INT >= 24) {
            copySharedPreferences(src, getProtectedPrefs(context))
        }
    }

    private fun getProtectedPrefs(context: Context): SharedPreferences {
        // AndroidX PreferenceManager.getDefaultSharedPreferencesName is private
        // The standard default name is "${packageName}_preferences"
        val prefName = "${context.packageName}_preferences"
        return context.createDeviceProtectedStorageContext()
            .getSharedPreferences(prefName, Context.MODE_PRIVATE)
    }

    private fun checkNeedMigration(appContext: Context, protectedPrefs: SharedPreferences) {
        if (!protectedPrefs.getBoolean("need_migration", true)) {
            return
        }
        val prefs = try {
            PreferenceManager.getDefaultSharedPreferences(appContext)
        } catch (e: Exception) {
            // Device is locked, migrate later
            return
        }
        prefs.edit().putBoolean("need_migration", false).apply()
        copySharedPreferences(prefs, protectedPrefs)
    }

    private fun copySharedPreferences(src: SharedPreferences, dst: SharedPreferences) {
        val editor = dst.edit()
        val entries = src.all
        for ((key, value) in entries) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>)
            }
        }
        editor.apply()
    }
}
