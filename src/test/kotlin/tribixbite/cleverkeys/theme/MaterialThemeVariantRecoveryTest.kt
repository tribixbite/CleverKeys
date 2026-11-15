package tribixbite.cleverkeys.theme

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Follow-up review 2026-11-15: an unknown stored `theme_variant` (written by a newer build, or
 * hand-edited) made `ThemeVariant.valueOf` throw from [MaterialThemeManager]'s constructor, which
 * every Settings screen composing the app theme runs.
 */
class MaterialThemeVariantRecoveryTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun anUnknownStoredVariantFallsBackToDefault() {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } answers {
            if (firstArg<String>() == "theme_variant") "RETIRED_VARIANT" else secondArg()
        }
        every { prefs.getBoolean(any(), any()) } answers { secondArg() }
        every { prefs.getFloat(any(), any()) } answers { secondArg() }
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.createDeviceProtectedStorageContext() } returns context
        every { context.applicationContext } returns context

        val manager = MaterialThemeManager(context)

        assertEquals(ThemeVariant.DEFAULT, manager.themeConfig.value.themeVariant)
    }

    @Test
    fun aKnownStoredVariantIsKept() {
        assertEquals(ThemeVariant.MINIMAL, ThemeVariant.fromStored("MINIMAL"))
        assertEquals(ThemeVariant.DEFAULT, ThemeVariant.fromStored(null))
    }
}
