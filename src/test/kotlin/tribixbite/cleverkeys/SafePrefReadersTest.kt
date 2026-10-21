package tribixbite.cleverkeys

import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Pins what the four type-recovering readers (`Config.safeGetInt/Float/Boolean/String`) return for
 * every stored type, so the audit-L3 rewrite (one shared fallback chain instead of four nested
 * try/catch towers) is provably behaviour-preserving, and pins audit L8 for `get_dip_pref`: a
 * value no reader accepts is LOGGED before the default is used (it used to be swallowed silently).
 *
 * Uses [TypedFakePreferences], whose getters throw [ClassCastException] on a type mismatch exactly
 * like Android's `SharedPreferencesImpl`, which is the condition every fallback exists for.
 */
class SafePrefReadersTest {

    private val warnings = mutableListOf<String>()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        val message = slot<String>()
        every { Log.w(any(), capture(message)) } answers { warnings += message.captured; 0 }
        every { Log.w(any(), any<String>(), any()) } answers { warnings += secondArg<String>(); 0 }
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        unmockkStatic(TypedValue::class)
    }

    private fun prefs(value: Any) = TypedFakePreferences(mapOf(KEY to value))
    private val empty = TypedFakePreferences()

    // ------------------------------------------------------------------ safeGetInt

    @Test
    fun safeGetInt_readsEveryRecoverableType() {
        assertThat(Config.safeGetInt(prefs(12), KEY, 5)).isEqualTo(12)
        assertThat(Config.safeGetInt(prefs(3.7f), KEY, 5)).isEqualTo(3)
        assertThat(Config.safeGetInt(prefs("41"), KEY, 5)).isEqualTo(41)
        assertThat(Config.safeGetInt(empty, KEY, 5)).isEqualTo(5)
    }

    @Test
    fun safeGetInt_fallsBackToTheDefaultForUnusableValues() {
        // A stored value of a readable type that does not parse is the default, silently.
        assertThat(Config.safeGetInt(prefs("x"), KEY, 5)).isEqualTo(5)
        assertThat(Config.safeGetInt(prefs(Float.MIN_VALUE), KEY, 5)).isEqualTo(5)
        assertThat(warnings.filter { KEY in it }).isEmpty()
        // A type no reader accepts is the default, logged once per read.
        assertThat(Config.safeGetInt(prefs(true), KEY, 5)).isEqualTo(5)
        assertThat(Config.safeGetInt(prefs(9L), KEY, 5)).isEqualTo(5)
        assertThat(warnings.filter { KEY in it }).hasSize(2)
        assertThat(warnings.last()).contains("int")
    }

    // ---------------------------------------------------------------- safeGetFloat

    @Test
    fun safeGetFloat_readsEveryRecoverableType() {
        assertThat(Config.safeGetFloat(prefs(1.5f), KEY, 0.25f)).isEqualTo(1.5f)
        assertThat(Config.safeGetFloat(prefs(4), KEY, 0.25f)).isEqualTo(4f)
        assertThat(Config.safeGetFloat(prefs("2.5"), KEY, 0.25f)).isEqualTo(2.5f)
        assertThat(Config.safeGetFloat(empty, KEY, 0.25f)).isEqualTo(0.25f)
    }

    @Test
    fun safeGetFloat_fallsBackToTheDefaultForUnusableValues() {
        assertThat(Config.safeGetFloat(prefs("x"), KEY, 0.25f)).isEqualTo(0.25f)
        assertThat(Config.safeGetFloat(prefs(Int.MIN_VALUE), KEY, 0.25f)).isEqualTo(0.25f)
        warnings.clear()
        assertThat(Config.safeGetFloat(prefs(false), KEY, 0.25f)).isEqualTo(0.25f)
        assertThat(warnings.single { KEY in it }).contains("float")
    }

    // -------------------------------------------------------------- safeGetBoolean

    @Test
    fun safeGetBoolean_readsEveryRecoverableType() {
        assertThat(Config.safeGetBoolean(prefs(true), KEY, false)).isTrue()
        for (yes in listOf("true", "TRUE", "1", "yes")) {
            assertThat(Config.safeGetBoolean(prefs(yes), KEY, false)).isTrue()
        }
        for (no in listOf("false", "0", "No")) {
            assertThat(Config.safeGetBoolean(prefs(no), KEY, true)).isFalse()
        }
        assertThat(Config.safeGetBoolean(prefs(1), KEY, false)).isTrue()
        assertThat(Config.safeGetBoolean(prefs(0), KEY, true)).isFalse()
        assertThat(Config.safeGetBoolean(empty, KEY, true)).isTrue()
    }

    @Test
    fun safeGetBoolean_fallsBackToTheDefaultForUnusableValues() {
        assertThat(Config.safeGetBoolean(prefs("maybe"), KEY, true)).isTrue()
        assertThat(Config.safeGetBoolean(prefs(5), KEY, false)).isFalse()
        warnings.clear()
        assertThat(Config.safeGetBoolean(prefs(2.0f), KEY, true)).isTrue()
        assertThat(warnings.single { KEY in it }).contains("boolean")
    }

    // --------------------------------------------------------------- safeGetString

    @Test
    fun safeGetString_readsEveryRecoverableType() {
        assertThat(Config.safeGetString(prefs("abc"), KEY, "d")).isEqualTo("abc")
        assertThat(Config.safeGetString(prefs(7), KEY, "d")).isEqualTo("7")
        assertThat(Config.safeGetString(prefs(1.5f), KEY, "d")).isEqualTo("1.5")
        assertThat(Config.safeGetString(prefs(true), KEY, "d")).isEqualTo("true")
        assertThat(Config.safeGetString(empty, KEY, "d")).isEqualTo("d")
    }

    @Test
    fun safeGetString_fallsBackToTheDefaultForUnusableValues() {
        assertThat(Config.safeGetString(prefs(Int.MIN_VALUE), KEY, "d")).isEqualTo("d")
        assertThat(Config.safeGetString(prefs(Float.MIN_VALUE), KEY, "d")).isEqualTo("d")
        warnings.clear()
        assertThat(Config.safeGetString(prefs(3L), KEY, "d")).isEqualTo("d")
        assertThat(warnings.single { KEY in it }).contains("string")
    }

    // ----------------------------------------------------------- get_dip_pref (L8)

    /** Invokes the real private `get_dip_pref` on a constructor-less Config; dp→px is identity. */
    private fun dipPref(stored: Any?, default: Float): Float {
        mockkStatic(TypedValue::class)
        every { TypedValue.applyDimension(any(), any(), any()) } answers { secondArg() }
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val config = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, Config::class.java) as Config
        Config::class.java.getDeclaredField("_prefs").apply { isAccessible = true }
            .set(config, if (stored == null) empty else prefs(stored))
        val method = Config::class.java.getDeclaredMethod(
            "get_dip_pref", DisplayMetrics::class.java, String::class.java, Float::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(config, mockk<DisplayMetrics>(), KEY, default) as Float
    }

    @Test
    fun getDipPref_readsIntFloatAndNumericString() {
        assertThat(dipPref(3, 1f)).isEqualTo(3f)
        assertThat(dipPref(2.5f, 1f)).isEqualTo(2.5f)
        assertThat(dipPref("4.5", 1f)).isEqualTo(4.5f)
        assertThat(dipPref(null, 1f)).isEqualTo(1f)
        // A negative stored value has always meant "use the default".
        assertThat(dipPref(-2, 1f)).isEqualTo(1f)
        assertThat(warnings.filter { KEY in it }).isEmpty()
    }

    @Test
    fun getDipPref_logsAValueNoReaderAccepts() {
        assertThat(dipPref(true, 1f)).isEqualTo(1f)
        assertThat(dipPref("wide", 1f)).isEqualTo(1f)
        assertThat(warnings.filter { KEY in it }).hasSize(2)
    }

    private companion object {
        const val KEY = "probe_key"
    }
}
