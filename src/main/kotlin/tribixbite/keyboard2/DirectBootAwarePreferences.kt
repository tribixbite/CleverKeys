package tribixbite.keyboard2

import android.annotation.TargetApi
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.preference.PreferenceManager

/**
 * Direct Boot Aware Preferences for CleverKeys
 *
 * On API >= 24, preferences are read from device protected storage.
 * This storage is less protected than the default - no personal or sensitive
 * information is stored there (only keyboard settings). This storage is
 * accessible during boot and allows the keyboard to read its settings and
 * enable typing the storage password.
 *
 * Bug #82 fix: Complete implementation with device-protected storage,
 * migration logic, and proper API level handling.
 */
@TargetApi(24)
object DirectBootAwarePreferences {

    /**
     * Get shared preferences instance.
     * On API >= 24: Returns device-protected storage preferences.
     * On API < 24: Returns default shared preferences.
     */
    fun get_shared_preferences(context: Context): SharedPreferences {
        if (Build.VERSION.SDK_INT < 24) {
            return PreferenceManager.getDefaultSharedPreferences(context)
        }

        val protectedPrefs = get_protected_prefs(context)
        check_need_migration(context, protectedPrefs)
        return protectedPrefs
    }

    /**
     * Copy preferences to device protected storage.
     * Not using Context.moveSharedPreferencesFrom() because the settings activity
     * still uses PreferenceActivity, which can't work on a non-default shared
     * preference file.
     */
    fun copy_preferences_to_protected_storage(context: Context, src: SharedPreferences) {
        if (Build.VERSION.SDK_INT >= 24) {
            copy_shared_preferences(src, get_protected_prefs(context))
        }
    }

    /**
     * Get device-protected storage preferences.
     * Internal helper for API 24+.
     */
    private fun get_protected_prefs(context: Context): SharedPreferences {
        val prefName = PreferenceManager.getDefaultSharedPreferencesName(context)
        return context.createDeviceProtectedStorageContext()
            .getSharedPreferences(prefName, Context.MODE_PRIVATE)
    }

    /**
     * Check if migration from credential-encrypted to device-protected storage is needed.
     * Performs one-time migration on first access after upgrade to API 24+.
     */
    private fun check_need_migration(appContext: Context, protectedPrefs: SharedPreferences) {
        if (!protectedPrefs.getBoolean("need_migration", true)) {
            return // Migration already completed
        }

        val credentialPrefs = try {
            PreferenceManager.getDefaultSharedPreferences(appContext)
        } catch (e: Exception) {
            // Device is locked - credential-encrypted storage not accessible
            // Migration will happen later when device is unlocked
            return
        }

        // Mark migration as complete to prevent re-running
        credentialPrefs.edit()
            .putBoolean("need_migration", false)
            .apply()

        // Copy all preferences from credential-encrypted to device-protected storage
        copy_shared_preferences(credentialPrefs, protectedPrefs)
    }

    /**
     * Copy all entries from source SharedPreferences to destination.
     * Handles all SharedPreferences value types (Boolean, Float, Int, Long, String, StringSet).
     */
    private fun copy_shared_preferences(src: SharedPreferences, dst: SharedPreferences) {
        val editor = dst.edit()
        val entries = src.all

        for ((key, value) in entries) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
                // Ignore null values and unknown types
            }
        }

        editor.apply()
    }
}
