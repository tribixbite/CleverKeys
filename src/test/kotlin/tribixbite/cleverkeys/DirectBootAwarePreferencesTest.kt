package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * MockK-based JVM tests for DirectBootAwarePreferences.
 *
 * Tests the object singleton's copy_preferences_to_protected_storage and
 * the device-protected storage routing on API >= 24.
 *
 * NOTE: Tests involving PreferenceManager (pre-N path, migration) are excluded
 * because androidx.preference.PreferenceManager is not on the JavaExec runtime
 * classpath (it's an AAR dependency). Those paths require Robolectric.
 *
 * Build.VERSION.SDK_INT from android.jar stubs defaults to 0 (< 24), so we
 * use Unsafe reflection to set it for API >= 24 tests.
 */
class DirectBootAwarePreferencesTest {

    private lateinit var mockContext: Context
    private lateinit var mockDeContext: Context
    private lateinit var mockProtectedPrefs: SharedPreferences
    private lateinit var mockProtectedEditor: SharedPreferences.Editor

    @Before
    fun setup() {
        // Mock android.util.Log
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        // Setup mock Context
        mockContext = mockk(relaxed = true)
        every { mockContext.packageName } returns "tribixbite.cleverkeys"

        // Setup device-protected storage context
        mockDeContext = mockk(relaxed = true)
        every { mockContext.createDeviceProtectedStorageContext() } returns mockDeContext

        // Setup protected prefs (DE storage)
        mockProtectedPrefs = mockk(relaxed = true)
        mockProtectedEditor = mockk(relaxed = true)
        every { mockDeContext.getSharedPreferences(any(), any()) } returns mockProtectedPrefs
        every { mockProtectedPrefs.edit() } returns mockProtectedEditor
        every { mockProtectedEditor.putBoolean(any(), any()) } returns mockProtectedEditor
        every { mockProtectedEditor.putFloat(any(), any()) } returns mockProtectedEditor
        every { mockProtectedEditor.putInt(any(), any()) } returns mockProtectedEditor
        every { mockProtectedEditor.putLong(any(), any()) } returns mockProtectedEditor
        every { mockProtectedEditor.putString(any(), any()) } returns mockProtectedEditor
        every { mockProtectedEditor.putStringSet(any(), any()) } returns mockProtectedEditor
        every { mockProtectedEditor.apply() } just runs
    }

    @After
    fun teardown() {
        unmockkStatic(Log::class)
    }

    // =========================================================================
    // copy_preferences_to_protected_storage — all value types
    // =========================================================================

    @Test
    fun `copy_preferences_to_protected_storage copies on API 24 plus`() {
        setSdkInt(26)
        val srcPrefs = mockk<SharedPreferences>()
        val srcMap = mapOf<String, Any?>(
            "key_bool" to true,
            "key_str" to "value"
        )
        every { srcPrefs.all } returns srcMap

        DirectBootAwarePreferences.copy_preferences_to_protected_storage(mockContext, srcPrefs)

        verify { mockProtectedEditor.putBoolean("key_bool", true) }
        verify { mockProtectedEditor.putString("key_str", "value") }
        verify { mockProtectedEditor.apply() }
    }

    @Test
    fun `copy_preferences_to_protected_storage is no-op on pre-N`() {
        setSdkInt(23)
        val srcPrefs = mockk<SharedPreferences>()

        DirectBootAwarePreferences.copy_preferences_to_protected_storage(mockContext, srcPrefs)

        // Should not attempt to create DE context or copy anything
        verify(exactly = 0) { mockContext.createDeviceProtectedStorageContext() }
    }

    @Test
    fun `copy_preferences handles all SharedPreferences value types`() {
        setSdkInt(26)
        val srcPrefs = mockk<SharedPreferences>()
        val stringSet = setOf("a", "b", "c")
        val srcMap = mapOf<String, Any?>(
            "pref_bool" to true,
            "pref_float" to 3.14f,
            "pref_int" to 42,
            "pref_long" to 123456789L,
            "pref_string" to "hello",
            "pref_set" to stringSet
        )
        every { srcPrefs.all } returns srcMap

        DirectBootAwarePreferences.copy_preferences_to_protected_storage(mockContext, srcPrefs)

        verify { mockProtectedEditor.putBoolean("pref_bool", true) }
        verify { mockProtectedEditor.putFloat("pref_float", 3.14f) }
        verify { mockProtectedEditor.putInt("pref_int", 42) }
        verify { mockProtectedEditor.putLong("pref_long", 123456789L) }
        verify { mockProtectedEditor.putString("pref_string", "hello") }
        @Suppress("UNCHECKED_CAST")
        verify { mockProtectedEditor.putStringSet("pref_set", stringSet) }
        verify { mockProtectedEditor.apply() }
    }

    @Test
    fun `copy_preferences handles empty source prefs`() {
        setSdkInt(26)
        val srcPrefs = mockk<SharedPreferences>()
        every { srcPrefs.all } returns emptyMap()

        DirectBootAwarePreferences.copy_preferences_to_protected_storage(mockContext, srcPrefs)

        // Only apply() should be called, no put operations
        verify { mockProtectedEditor.apply() }
        verify(exactly = 0) { mockProtectedEditor.putBoolean(any(), any()) }
        verify(exactly = 0) { mockProtectedEditor.putString(any(), any<String>()) }
    }

    // =========================================================================
    // Helper: set Build.VERSION.SDK_INT via Unsafe reflection
    // =========================================================================

    private fun setSdkInt(value: Int) {
        try {
            val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null)
            val unsafeClass = unsafe.javaClass

            val field = Build.VERSION::class.java.getField("SDK_INT")
            val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java)
                .invoke(unsafe, field)
            val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java)
                .invoke(unsafe, field) as Long
            unsafeClass.getMethod("putInt", Object::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(unsafe, base, offset, value)
        } catch (_: Exception) {
            // If Unsafe fails, SDK_INT stays at 0 (pre-N path)
        }
    }
}
