package tribixbite.cleverkeys

import android.annotation.TargetApi
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.preference.PreferenceManager
import java.lang.ref.WeakReference

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
        cached?.let { entry ->
            if (entry.context.get() === context) {
                if (!entry.migrated) entry.migrated = checkNeedMigration(context, entry.prefs)
                return entry.prefs
            }
        }
        val prefs = getProtectedPrefs(context)
        cached = CacheEntry(WeakReference(context), prefs, migrated = checkNeedMigration(context, prefs))
        return prefs
    }

    /**
     * The last resolution (audit L16, 2026-10-20). `get_shared_preferences` used to create a
     * device-protected-storage Context and re-check the migration on every call, and it is
     * called per swipe decode by both engines (CTC lexiconFor, geometric dictionaryFor). The hot
     * callers pass the same Context every time (the IME service), so one entry keyed by that
     * Context's IDENTITY serves them; a different Context — an activity, a test's isolated
     * ContextWrapper whose storage may differ — is resolved afresh and replaces the entry.
     * The Context is held weakly so an activity is never leaked by the cache.
     *
     * [migrated] stays false while the credential store is unreadable (device locked), so the
     * migration is retried on the next call until it has run once.
     */
    private class CacheEntry(
        val context: WeakReference<Context>,
        val prefs: SharedPreferences,
        @Volatile var migrated: Boolean,
    )

    /** See [CacheEntry]. A race between two threads only costs one extra resolution. */
    @Volatile
    private var cached: CacheEntry? = null

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

    // Scoped to this method (not the whole object): createDeviceProtectedStorageContext()
    // is API 24+ — always available since ARC-113 raised minSdk to 24. Callers still gate
    // this behind a runtime SDK_INT check; those guards are vestigial (always true) and
    // harmless, so annotation and guards are retained as-is.
    @TargetApi(24)
    private fun getProtectedPrefs(context: Context): SharedPreferences {
        // AndroidX PreferenceManager.getDefaultSharedPreferencesName is private
        // The standard default name is "${packageName}_preferences"
        val prefName = "${context.packageName}_preferences"
        return context.createDeviceProtectedStorageContext()
            .getSharedPreferences(prefName, Context.MODE_PRIVATE)
    }

    /**
     * Copy the credential-encrypted preferences into [protectedPrefs] once.
     *
     * @return true when no migration is (any longer) needed; false when it could not run yet
     *   because the device is locked, so the caller must ask again later.
     */
    private fun checkNeedMigration(appContext: Context, protectedPrefs: SharedPreferences): Boolean {
        if (!protectedPrefs.getBoolean("need_migration", true)) {
            return true
        }
        val prefs = try {
            PreferenceManager.getDefaultSharedPreferences(appContext)
        } catch (e: Exception) {
            // Device is locked, migrate later
            return false
        }
        prefs.edit().putBoolean("need_migration", false).apply()
        copySharedPreferences(prefs, protectedPrefs)
        return true
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
