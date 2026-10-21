package tribixbite.cleverkeys

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Audit L16 (2026-10-20): `DirectBootAwarePreferences.get_shared_preferences` allocated a fresh
 * device-protected-storage Context and re-checked the credential→device migration on EVERY call,
 * including the per-decode reads of the swipe engines. It now caches the resolved preferences per
 * Context and stops checking once the migration is known to be done — while a locked device (the
 * credential store unreadable) still retries on the next call, so the copy runs exactly once, as
 * soon as it can.
 */
class DirectBootPrefsCacheTest {

    private val dePrefs = TypedFakePreferences()
    private val credentialPrefs = TypedFakePreferences(mapOf("theme" to "jewel", "keyboard_height" to 31))
    private var deviceLocked = false
    private var originalSdk = 0

    private val deContext: Context = mockk {
        every { getSharedPreferences(any(), any()) } returns dePrefs
    }

    /** A fresh caller context (an IME service, an activity) over the same app. */
    private fun appContext(): Context = mockk {
        every { packageName } returns "tribixbite.cleverkeys"
        every { createDeviceProtectedStorageContext() } returns deContext
    }

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        mockkStatic(PreferenceManager::class)
        every { PreferenceManager.getDefaultSharedPreferences(any()) } answers {
            if (deviceLocked) throw IllegalStateException("credential storage locked") else credentialPrefs
        }
        originalSdk = Build.VERSION.SDK_INT
        setSdkInt(26)
        resetCache()
    }

    @After
    fun tearDown() {
        resetCache()
        setSdkInt(originalSdk)
        unmockkStatic(PreferenceManager::class)
        unmockkStatic(Log::class)
    }

    @Test
    fun repeatedReadsFromOneContextCreateTheProtectedContextOnce() {
        val context = appContext()
        val first = DirectBootAwarePreferences.get_shared_preferences(context)
        repeat(50) {
            assertThat(DirectBootAwarePreferences.get_shared_preferences(context)).isSameInstanceAs(first)
        }
        assertThat(first).isSameInstanceAs(dePrefs)
        verify(exactly = 1) { context.createDeviceProtectedStorageContext() }
    }

    @Test
    fun theMigrationCopiesTheCredentialStoreExactlyOnce() {
        val context = appContext()
        repeat(5) { DirectBootAwarePreferences.get_shared_preferences(context) }
        assertThat(dePrefs.values["theme"]).isEqualTo("jewel")
        assertThat(dePrefs.values["need_migration"]).isEqualTo(false)
        assertThat(dePrefs.commits).isEqualTo(1)
        verify(exactly = 1) { PreferenceManager.getDefaultSharedPreferences(any()) }

        // A second caller context resolves its own prefs but finds the migration already done.
        DirectBootAwarePreferences.get_shared_preferences(appContext())
        assertThat(dePrefs.commits).isEqualTo(1)
    }

    @Test
    fun aLockedDeviceRetriesTheMigrationUntilItCanRun() {
        val context = appContext()
        deviceLocked = true
        repeat(3) { DirectBootAwarePreferences.get_shared_preferences(context) }
        assertThat(dePrefs.values).doesNotContainKey("theme")

        deviceLocked = false
        repeat(3) { DirectBootAwarePreferences.get_shared_preferences(context) }
        assertThat(dePrefs.values["theme"]).isEqualTo("jewel")
        assertThat(dePrefs.commits).isEqualTo(1)
    }

    /** Drops the process-wide cache so each test starts cold (and leaves none for other classes). */
    private fun resetCache() {
        DirectBootAwarePreferences::class.java.declaredFields
            .filter { it.name == "cached" }
            .forEach { it.isAccessible = true; it.set(DirectBootAwarePreferences, null) }
    }

    private fun setSdkInt(value: Int) {
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val unsafeClass = unsafe.javaClass
        val field = Build.VERSION::class.java.getField("SDK_INT")
        val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java)
            .invoke(unsafe, field)
        val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java)
            .invoke(unsafe, field) as Long
        unsafeClass.getMethod("putInt", Any::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(unsafe, base, offset, value)
    }
}
