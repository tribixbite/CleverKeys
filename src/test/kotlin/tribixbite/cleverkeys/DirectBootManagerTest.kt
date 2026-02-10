package tribixbite.cleverkeys

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * MockK-based JVM tests for DirectBootManager.
 *
 * Tests singleton lifecycle, unlock detection, callback registration,
 * and device-protected preferences access.
 *
 * android.util.Log, Build.VERSION, UserManager, and KeyguardManager are all
 * mocked to avoid android.jar "Stub!" exceptions on the host JVM.
 */
class DirectBootManagerTest {

    private lateinit var mockContext: Context
    private lateinit var mockAppContext: Context
    private lateinit var mockUserManager: UserManager
    private lateinit var mockKeyguardManager: KeyguardManager

    @Before
    fun setup() {
        // Mock android.util.Log
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        // Reset singleton between tests via reflection
        resetSingleton()

        // Setup mock Context chain
        mockAppContext = mockk(relaxed = true)
        mockContext = mockk(relaxed = true)
        every { mockContext.applicationContext } returns mockAppContext

        // Setup UserManager mock (default: unlocked)
        mockUserManager = mockk()
        every { mockAppContext.getSystemService(Context.USER_SERVICE) } returns mockUserManager
        every { mockUserManager.isUserUnlocked } returns true

        // Setup KeyguardManager mock
        mockKeyguardManager = mockk()
        every { mockAppContext.getSystemService(Context.KEYGUARD_SERVICE) } returns mockKeyguardManager
        every { mockKeyguardManager.isKeyguardLocked } returns false

        // Set SDK_INT to 26 (post-N) by default
        setSdkInt(26)
    }

    @After
    fun teardown() {
        resetSingleton()
        unmockkStatic(Log::class)
        try {
            unmockkStatic(Build.VERSION::class)
        } catch (_: Exception) {
            // May not have been mocked in all tests
        }
    }

    // =========================================================================
    // Singleton lifecycle
    // =========================================================================

    @Test
    fun `getInstance returns same instance for same context`() {
        val instance1 = DirectBootManager.getInstance(mockContext)
        val instance2 = DirectBootManager.getInstance(mockContext)
        assertThat(instance1).isSameInstanceAs(instance2)
    }

    @Test
    fun `getInstance uses applicationContext`() {
        val manager = DirectBootManager.getInstance(mockContext)
        assertThat(manager).isNotNull()
        verify { mockContext.applicationContext }
    }

    // =========================================================================
    // isUserUnlocked — delegates to UserManager on API >= 24
    // =========================================================================

    @Test
    fun `isUserUnlocked returns true when UserManager says unlocked`() {
        every { mockUserManager.isUserUnlocked } returns true
        val manager = DirectBootManager.getInstance(mockContext)
        assertThat(manager.isUserUnlocked).isTrue()
    }

    @Test
    fun `isUserUnlocked returns false when UserManager says locked`() {
        every { mockUserManager.isUserUnlocked } returns false
        val manager = DirectBootManager.getInstance(mockContext)
        assertThat(manager.isUserUnlocked).isFalse()
    }

    @Test
    fun `isUserUnlocked returns true when UserManager is null`() {
        // If getSystemService returns null, checkUserUnlocked defaults to true
        every { mockAppContext.getSystemService(Context.USER_SERVICE) } returns null
        val manager = DirectBootManager.getInstance(mockContext)
        assertThat(manager.isUserUnlocked).isTrue()
    }

    @Test
    fun `isUserUnlocked always true on pre-N devices`() {
        setSdkInt(23) // Marshmallow, before Direct Boot
        // UserManager should NOT be consulted
        every { mockAppContext.getSystemService(Context.USER_SERVICE) } returns null
        val manager = DirectBootManager.getInstance(mockContext)
        assertThat(manager.isUserUnlocked).isTrue()
    }

    // =========================================================================
    // isDeviceLocked — delegates to KeyguardManager
    // =========================================================================

    @Test
    fun `isDeviceLocked returns true when keyguard is locked`() {
        every { mockUserManager.isUserUnlocked } returns true
        every { mockKeyguardManager.isKeyguardLocked } returns true
        val manager = DirectBootManager.getInstance(mockContext)
        assertThat(manager.isDeviceLocked).isTrue()
    }

    @Test
    fun `isDeviceLocked returns false when keyguard is not locked`() {
        every { mockKeyguardManager.isKeyguardLocked } returns false
        val manager = DirectBootManager.getInstance(mockContext)
        assertThat(manager.isDeviceLocked).isFalse()
    }

    @Test
    fun `isDeviceLocked returns false when KeyguardManager is null`() {
        every { mockAppContext.getSystemService(Context.KEYGUARD_SERVICE) } returns null
        val manager = DirectBootManager.getInstance(mockContext)
        assertThat(manager.isDeviceLocked).isFalse()
    }

    // =========================================================================
    // registerUnlockCallback
    // =========================================================================

    @Test
    fun `registerUnlockCallback invokes immediately when already unlocked`() {
        every { mockUserManager.isUserUnlocked } returns true
        val manager = DirectBootManager.getInstance(mockContext)

        var callbackInvoked = false
        manager.registerUnlockCallback { callbackInvoked = true }
        assertThat(callbackInvoked).isTrue()
    }

    // NOTE: Tests for registerUnlockCallback with locked device, unlock receiver firing,
    // and cleanup with receiver are excluded because registerUnlockReceiver() creates
    // `object : BroadcastReceiver()` which calls android.jar stub constructor → Stub!
    // These tests require Robolectric.

    @Test
    fun `cleanup is safe to call when no receiver registered`() {
        every { mockUserManager.isUserUnlocked } returns true
        val manager = DirectBootManager.getInstance(mockContext)
        // No callbacks registered, so no receiver exists — cleanup should not throw
        manager.cleanup()
    }

    // =========================================================================
    // getDeviceProtectedPreferences
    // =========================================================================

    @Test
    fun `getDeviceProtectedPreferences uses DE context on API 24 plus`() {
        setSdkInt(26)
        val deContext = mockk<Context>()
        val dePrefs = mockk<SharedPreferences>()
        every { mockAppContext.createDeviceProtectedStorageContext() } returns deContext
        every { deContext.getSharedPreferences(any(), any()) } returns dePrefs

        val manager = DirectBootManager.getInstance(mockContext)
        val prefs = manager.getDeviceProtectedPreferences("test_prefs")
        assertThat(prefs).isSameInstanceAs(dePrefs)
    }

    @Test
    fun `getDeviceProtectedPreferences uses regular context on pre-N`() {
        setSdkInt(23)
        val regularPrefs = mockk<SharedPreferences>()
        every { mockAppContext.getSharedPreferences(any(), any()) } returns regularPrefs

        val manager = DirectBootManager.getInstance(mockContext)
        val prefs = manager.getDeviceProtectedPreferences("test_prefs")
        assertThat(prefs).isSameInstanceAs(regularPrefs)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Reset the DirectBootManager singleton via reflection so each test
     * starts with a fresh instance.
     */
    private fun resetSingleton() {
        try {
            val field = DirectBootManager::class.java.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        } catch (_: Exception) {
            // Field may not exist in some compilation variants; ignore
        }
    }

    /**
     * Set Build.VERSION.SDK_INT via Unsafe reflection for branch testing.
     * Java 17+ removed Field.modifiers access, so we use sun.misc.Unsafe
     * which can modify any field regardless of final/accessibility.
     */
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
            // If Unsafe fails, SDK_INT stays at android.jar default (0)
        }
    }
}
