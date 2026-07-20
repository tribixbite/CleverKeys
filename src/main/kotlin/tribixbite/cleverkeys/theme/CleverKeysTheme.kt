package tribixbite.cleverkeys.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Canonical app-wide theme wrapper for CleverKeys' Compose surfaces.
 *
 * This is the single source of truth for theming every `setContent { … }` screen
 * (Settings, Theme picker, Short-swipe customization, Extra keys, Neural, Clipboard,
 * Layout manager, etc.). It replaces the per-activity inline
 * `MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme())`
 * re-rolls that silently drifted from the branded scheme.
 *
 * Behaviour (all resolved through [MaterialThemeManager] so the app's dynamic-color /
 * branded scheme logic stays authoritative):
 * - Material 3 [ColorScheme] via [MaterialThemeManager.getColorScheme] — honours
 *   Material You (dynamic color) on Android 12+ when the user enabled it, otherwise
 *   the CleverKeys-branded blue light/dark scheme.
 * - [KeyboardColorScheme] (keyboard-specific semantic tokens) exposed via
 *   [LocalKeyboardColorScheme].
 * - [KeyboardTypography] (touch-optimised, larger-than-default type scale).
 * - [KeyboardShapes] (12dp keys / 16dp dialogs shape scale).
 *
 * @param darkTheme Whether to use the dark scheme (defaults to the system day/night setting).
 * @param dynamicColor Whether Material You dynamic color may be used when available.
 * @param colorSchemeOverride When non-null, this exact [ColorScheme] is applied instead of
 *   the one resolved from [MaterialThemeManager]. Used only by screens with a *deliberate*
 *   bespoke palette that must not follow the branded/dynamic scheme (e.g. the Theme picker's
 *   purple identity). Typography, shapes and the keyboard color tokens are still applied so
 *   those screens stay visually consistent with the rest of the app.
 * @param content Composable content to theme.
 */
@Composable
fun CleverKeysTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    colorSchemeOverride: ColorScheme? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    // Theme manager is cheap but keying it to the context avoids re-instantiating on
    // every recomposition (it only reads prefs + exposes a StateFlow).
    val themeManager = remember(context) { MaterialThemeManager(context) }

    // Subscribe to theme config via collectAsState so recomposition is triggered when the
    // underlying StateFlow emits (initial value == themeConfig.value).
    val themeConfig by themeManager.themeConfig.collectAsState()

    // Reconcile the caller's dynamic-color intent with persisted config (mirrors the
    // historical KeyboardTheme behaviour so the "force off" path keeps working).
    if (themeConfig.useDynamicColor != dynamicColor) {
        themeManager.updateTheme(themeConfig.copy(useDynamicColor = dynamicColor))
    }

    val colorScheme = colorSchemeOverride ?: themeManager.getColorScheme(darkTheme)
    val keyboardColorScheme = themeManager.getKeyboardColorScheme(darkTheme)

    CompositionLocalProvider(LocalKeyboardColorScheme provides keyboardColorScheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = KeyboardTypography,
            shapes = KeyboardShapes,
            content = content
        )
    }
}
