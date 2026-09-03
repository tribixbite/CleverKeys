package tribixbite.cleverkeys.theme

import android.content.Context
import android.os.Build
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Pins the published "**Monet theme crash on Android < 12 (#1107)**" fix (v1.2.6, re-published
 * in v1.2.8), anchored at `CleverKeysTheme(dynamicColor = …)`.
 *
 * ## What actually crashed and what actually fixes it
 *
 * `CleverKeysTheme` does not resolve colours itself: it forwards the caller's `dynamicColor`
 * intent into [MaterialThemeManager] (`themeManager.updateTheme(config.copy(useDynamicColor …))`)
 * and then asks it for a [androidx.compose.material3.ColorScheme]. The crash lived one level
 * down, in [MaterialThemeManager.getColorScheme]: `dynamicLightColorScheme` /
 * `dynamicDarkColorScheme` read the `android.R.color.system_accent*` resources, which only
 * exist from Android 12 (API 31). A user on Android 8–11 with dynamic colour switched on hit a
 * `Resources.NotFoundException` on every themed screen.
 *
 * The fix is the `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&` conjunct in front of that
 * branch, so **an enabled dynamic-colour preference is simply ignored below API 31** and the
 * CleverKeys-branded scheme is used instead. This test drives both sides of that gate.
 *
 * `MaterialThemeManager`'s constructor opens device-protected `SharedPreferences` and builds a
 * `CustomThemeManager`, so the instance here is allocated without running it and only the two
 * fields `getColorScheme` reads are populated.
 */
class MonetDynamicColorGateTest {

    private companion object {
        /** `createCleverKeysLightColorScheme` — Blue 700. */
        val BRANDED_LIGHT_PRIMARY = Color(0xFF1976D2)

        /** `createCleverKeysDarkColorScheme` — Blue 300. */
        val BRANDED_DARK_PRIMARY = Color(0xFF64B5F6)
    }

    private var originalSdkInt = 0

    @Before
    fun setup() {
        originalSdkInt = Build.VERSION.SDK_INT
    }

    @After
    fun teardown() {
        setSdkInt(originalSdkInt)
    }

    /** A [MaterialThemeManager] with no constructor run — only `context` and `_themeConfig`. */
    private fun managerWith(context: Context, useDynamicColor: Boolean): MaterialThemeManager {
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val manager = unsafe.javaClass
            .getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, MaterialThemeManager::class.java) as MaterialThemeManager

        MaterialThemeManager::class.java.getDeclaredField("context")
            .apply { isAccessible = true }.set(manager, context)
        MaterialThemeManager::class.java.getDeclaredField("_themeConfig")
            .apply { isAccessible = true }
            .set(manager, MutableStateFlow(ThemeConfig(useDynamicColor = useDynamicColor)))
        return manager
    }

    @Test
    fun belowApi31_anEnabledDynamicColourPreferenceIsIgnoredAndCannotReachTheSystemPalette() {
        for (sdk in listOf(26, 28, 30)) {
            setSdkInt(sdk)
            val context = mockk<Context>(relaxed = true)
            val manager = managerWith(context, useDynamicColor = true)

            assertThat(manager.getColorScheme(darkTheme = false).primary)
                .isEqualTo(BRANDED_LIGHT_PRIMARY)
            assertThat(manager.getColorScheme(darkTheme = true).primary)
                .isEqualTo(BRANDED_DARK_PRIMARY)

            // The branded factories are constant expressions. Touching the Context at all on
            // these API levels means the Monet path was entered — which is the #1107 crash.
            verify { context wasNot Called }
        }
    }

    @Test
    fun api31AndAbove_anEnabledDynamicColourPreferenceReachesThePlatformPalette() {
        setSdkInt(Build.VERSION_CODES.S)
        val context = mockk<Context>(relaxed = true)
        val manager = managerWith(context, useDynamicColor = true)

        // dynamic*ColorScheme resolves android.R.color.system_accent* off the Context. Off
        // device that either yields a non-branded scheme or throws inside the platform stub;
        // both prove the gate opened exactly at API 31 rather than being dead code.
        val outcome = runCatching { manager.getColorScheme(darkTheme = false) }
        val enteredDynamicBranch =
            outcome.isFailure || outcome.getOrNull()?.primary != BRANDED_LIGHT_PRIMARY
        assertThat(enteredDynamicBranch).isTrue()
    }

    @Test
    fun api31AndAbove_dynamicColourOffStillYieldsTheBrandedScheme() {
        setSdkInt(Build.VERSION_CODES.S)
        val context = mockk<Context>(relaxed = true)
        val manager = managerWith(context, useDynamicColor = false)

        // `CleverKeysTheme(dynamicColor = false)` is the documented "force off" path; it must
        // keep working on the very versions where dynamic colour is available.
        assertThat(manager.getColorScheme(darkTheme = false).primary)
            .isEqualTo(BRANDED_LIGHT_PRIMARY)
        assertThat(manager.getColorScheme(darkTheme = true).primary)
            .isEqualTo(BRANDED_DARK_PRIMARY)
        verify { context wasNot Called }
    }

    @Test
    fun themeConfigDefault_requestsDynamicColour_soTheGateIsAlwaysOnThePath() {
        // Dynamic colour is ON by default, which is why the API gate — not the preference —
        // is what protects Android 8-11 users.
        assertThat(ThemeConfig().useDynamicColor).isTrue()
        assertThat(ThemeConfig().darkMode).isFalse()
    }

    /** See `WindowLayoutUtilsTest.setSdkInt` — same Unsafe write, same loud verification. */
    private fun setSdkInt(sdkInt: Int) {
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val unsafeClass = unsafe.javaClass

        val field = Build.VERSION::class.java.getField("SDK_INT")
        val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java)
            .invoke(unsafe, field)
        val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java)
            .invoke(unsafe, field) as Long
        unsafeClass.getMethod(
            "putInt",
            Object::class.java,
            Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        ).invoke(unsafe, base, offset, sdkInt)

        assertThat(Build.VERSION.SDK_INT).isEqualTo(sdkInt)
    }
}
