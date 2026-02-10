package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for DirectBootAwarePreferences and DirectBootManager.
 *
 * Covers paths that cannot be tested on pure JVM:
 * - PreferenceManager.getDefaultSharedPreferences() (AAR dependency)
 * - get_shared_preferences() pre-N fallback path
 * - checkNeedMigration() with real PreferenceManager
 * - DirectBootManager.registerUnlockCallback() with receiver lifecycle
 * - Device-protected storage access on real Android
 */
@RunWith(AndroidJUnit4::class)
class DirectBootInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    // =========================================================================
    // DirectBootAwarePreferences — PreferenceManager dependent paths
    // =========================================================================

    @Test
    fun getSharedPreferencesReturnsNonNull() {
        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        assertNotNull("get_shared_preferences should return non-null", prefs)
    }

    @Test
    fun getSharedPreferencesIsConsistentAcrossCalls() {
        val prefs1 = DirectBootAwarePreferences.get_shared_preferences(context)
        val prefs2 = DirectBootAwarePreferences.get_shared_preferences(context)
        // Both should reference the same underlying prefs (values should be consistent)
        prefs1.edit().putString("test_consistency", "value1").apply()
        assertEquals("value1", prefs2.getString("test_consistency", null))
        // Cleanup
        prefs1.edit().remove("test_consistency").apply()
    }

    @Test
    fun getSharedPreferencesUsesDeviceProtectedStorageOnApi24Plus() {
        if (Build.VERSION.SDK_INT < 24) return // Skip on pre-N devices

        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        // Write a test value
        prefs.edit().putString("direct_boot_test", "DE_storage").apply()

        // Verify it's in device-protected storage
        val deName = "${context.packageName}_preferences"
        val deContext = context.createDeviceProtectedStorageContext()
        val dePrefs = deContext.getSharedPreferences(deName, Context.MODE_PRIVATE)
        assertEquals("DE_storage", dePrefs.getString("direct_boot_test", null))

        // Cleanup
        prefs.edit().remove("direct_boot_test").apply()
    }

    @Test
    fun copyPreferencesToProtectedStorageCopiesAllTypes() {
        if (Build.VERSION.SDK_INT < 24) return

        // Create source prefs with various types
        val src = context.getSharedPreferences("copy_test_src", Context.MODE_PRIVATE)
        src.edit()
            .putBoolean("bool_key", true)
            .putFloat("float_key", 3.14f)
            .putInt("int_key", 42)
            .putLong("long_key", 123456789L)
            .putString("string_key", "hello")
            .putStringSet("set_key", setOf("a", "b", "c"))
            .apply()

        // Copy to protected storage
        DirectBootAwarePreferences.copy_preferences_to_protected_storage(context, src)

        // Verify in protected storage
        val deName = "${context.packageName}_preferences"
        val deContext = context.createDeviceProtectedStorageContext()
        val dst = deContext.getSharedPreferences(deName, Context.MODE_PRIVATE)

        assertTrue(dst.getBoolean("bool_key", false))
        assertEquals(3.14f, dst.getFloat("float_key", 0f), 0.01f)
        assertEquals(42, dst.getInt("int_key", 0))
        assertEquals(123456789L, dst.getLong("long_key", 0L))
        assertEquals("hello", dst.getString("string_key", null))
        assertEquals(setOf("a", "b", "c"), dst.getStringSet("set_key", null))

        // Cleanup
        src.edit().clear().apply()
        dst.edit()
            .remove("bool_key").remove("float_key").remove("int_key")
            .remove("long_key").remove("string_key").remove("set_key")
            .apply()
    }

    @Test
    fun checkNeedMigrationRunsOnFirstUse() {
        if (Build.VERSION.SDK_INT < 24) return

        // Clear the migration flag to simulate first boot
        val deName = "${context.packageName}_preferences"
        val deContext = context.createDeviceProtectedStorageContext()
        val dePrefs = deContext.getSharedPreferences(deName, Context.MODE_PRIVATE)
        dePrefs.edit().putBoolean("need_migration", true).apply()

        // Write a value to default prefs (source for migration)
        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        defaultPrefs.edit().putString("migration_test_key", "migrated_value").apply()

        // Calling get_shared_preferences should trigger migration
        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)

        // Verify migration flag was cleared
        assertFalse("need_migration should be false after migration",
            prefs.getBoolean("need_migration", true))

        // Cleanup
        defaultPrefs.edit().remove("migration_test_key").remove("need_migration").apply()
        dePrefs.edit().remove("migration_test_key").remove("need_migration").apply()
    }

    @Test
    fun preferenceManagerGetDefaultSharedPreferencesWorks() {
        // Directly test that AndroidX PreferenceManager works in instrumented context
        // (This is the exact call that fails on JVM with "NoClassDefFoundError: PreferenceManager")
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        assertNotNull("PreferenceManager.getDefaultSharedPreferences should work", prefs)

        // Write and read back
        prefs.edit().putString("pref_mgr_test", "works").apply()
        assertEquals("works", prefs.getString("pref_mgr_test", null))

        // Cleanup
        prefs.edit().remove("pref_mgr_test").apply()
    }

    // =========================================================================
    // DirectBootManager — BroadcastReceiver dependent paths
    // =========================================================================

    @Test
    fun directBootManagerSingletonWorks() {
        val instance1 = DirectBootManager.getInstance(context)
        val instance2 = DirectBootManager.getInstance(context)
        assertSame("getInstance should return same instance", instance1, instance2)
    }

    @Test
    fun directBootManagerIsUserUnlockedOnRunningDevice() {
        // On a running, unlocked emulator, isUserUnlocked should be true
        val manager = DirectBootManager.getInstance(context)
        assertTrue("User should be unlocked on running emulator", manager.isUserUnlocked)
    }

    @Test
    fun registerUnlockCallbackInvokesImmediatelyWhenUnlocked() {
        val manager = DirectBootManager.getInstance(context)
        // Device is unlocked on emulator — callback should fire immediately
        var callbackFired = false
        manager.registerUnlockCallback { callbackFired = true }
        assertTrue("Callback should fire immediately on unlocked device", callbackFired)
    }

    @Test
    fun registerMultipleUnlockCallbacksAllInvokedWhenUnlocked() {
        val manager = DirectBootManager.getInstance(context)
        var count = 0
        manager.registerUnlockCallback { count++ }
        manager.registerUnlockCallback { count++ }
        manager.registerUnlockCallback { count++ }
        assertEquals("All 3 callbacks should fire", 3, count)
    }

    @Test
    fun cleanupIsSafe() {
        val manager = DirectBootManager.getInstance(context)
        // Should not throw even with no receiver registered
        manager.cleanup()
        // Should still work after cleanup
        assertTrue(manager.isUserUnlocked)
    }

    @Test
    fun getDeviceProtectedPreferencesReturnsValidPrefs() {
        if (Build.VERSION.SDK_INT < 24) return
        val manager = DirectBootManager.getInstance(context)
        val prefs = manager.getDeviceProtectedPreferences("test_de_prefs")
        assertNotNull("Device-protected prefs should not be null", prefs)

        // Write and read back
        prefs.edit().putString("de_test", "value").apply()
        assertEquals("value", prefs.getString("de_test", null))

        // Cleanup
        prefs.edit().remove("de_test").apply()
    }
}
