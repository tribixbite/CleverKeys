---
title: Themes - Technical Specification
user_guide: ../../customization/themes.md
status: implemented
version: v1.2.7
---

# Themes Technical Specification

## Overview

The theme system provides customizable color schemes for the keyboard, including built-in themes, custom color options, and system theme integration.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| ThemeManager | `ThemeManager.kt` | Theme loading and application |
| ColorScheme | `ColorScheme.kt` | Color definitions |
| KeyboardView | `KeyboardView.kt` | Theme rendering |
| Config | `Config.kt` | Theme preferences |

## Data Model

### Color Scheme

```kotlin
// ColorScheme.kt
data class ColorScheme(
    val name: String,
    val isDark: Boolean,

    // Background colors
    val keyboardBackground: Int,
    val keyBackground: Int,
    val keyBackgroundPressed: Int,

    // Text colors
    val keyText: Int,
    val keyTextSecondary: Int,
    val subkeyText: Int,

    // Accent colors
    val accent: Int,
    val accentSecondary: Int,

    // Prediction bar
    val predictionBarBackground: Int,
    val predictionText: Int,
    val predictionTextActive: Int,

    // Special keys
    val modifierKeyBackground: Int,
    val enterKeyBackground: Int,

    // Borders and shadows
    val keyBorder: Int?,
    val keyShadow: Int?
)
```

### Built-in Themes

```kotlin
// ThemeManager.kt
object BuiltInThemes {
    val CLEVERKEYS_DARK = ColorScheme(
        name = "CleverKeys Dark",
        isDark = true,
        keyboardBackground = 0xFF1a1a2e.toInt(),
        keyBackground = 0xFF16213e.toInt(),
        keyText = 0xFFe8e8e8.toInt(),
        accent = 0xFF7c3aed.toInt(),  // Purple accent
        // ...
    )

    val MIDNIGHT = ColorScheme(
        name = "Midnight",
        isDark = true,
        keyboardBackground = 0xFF000000.toInt(),
        keyBackground = 0xFF1a1a1a.toInt(),
        keyText = 0xFFffffff.toInt(),
        accent = 0xFF3b82f6.toInt(),  // Blue accent
        // ...
    )

    val OCEAN = ColorScheme(...)
    val FOREST = ColorScheme(...)
    val SUNSET = ColorScheme(...)
    val SNOW = ColorScheme(...)
}
```

## Theme Application

### Rendering Pipeline

```kotlin
// KeyboardView.kt:~200
private fun applyTheme(theme: ColorScheme) {
    // Background
    backgroundPaint.color = theme.keyboardBackground

    // Key paints
    keyPaint.color = theme.keyBackground
    keyTextPaint.color = theme.keyText

    // Accent elements
    accentPaint.color = theme.accent

    // Optional effects
    if (theme.keyBorder != null) {
        borderPaint.color = theme.keyBorder
        borderPaint.strokeWidth = 1.dp
    }

    if (theme.keyShadow != null) {
        keyPaint.setShadowLayer(2.dp, 0f, 1.dp, theme.keyShadow)
    }

    invalidate()
}
```

### Key Drawing

```kotlin
// KeyboardView.kt:~300
private fun drawKey(canvas: Canvas, key: Key, bounds: RectF) {
    // Background
    val bgColor = when {
        key.isPressed -> theme.keyBackgroundPressed
        key.isModifier -> theme.modifierKeyBackground
        key.isEnter -> theme.enterKeyBackground
        else -> theme.keyBackground
    }
    keyPaint.color = bgColor

    // Draw rounded rectangle
    canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, keyPaint)

    // Border (optional)
    if (theme.keyBorder != null) {
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, borderPaint)
    }

    // Key label
    keyTextPaint.color = theme.keyText
    canvas.drawText(key.label, centerX, centerY, keyTextPaint)

    // Subkey hints
    subkeyTextPaint.color = theme.subkeyText
    drawSubkeyHints(canvas, key, bounds)
}
```

## System Theme Integration

```kotlin
// ThemeManager.kt
fun getSystemTheme(context: Context): ColorScheme {
    val isDarkMode = when (context.resources.configuration.uiMode and
                          Configuration.UI_MODE_NIGHT_MASK) {
        Configuration.UI_MODE_NIGHT_YES -> true
        Configuration.UI_MODE_NIGHT_NO -> false
        else -> false
    }

    return if (isDarkMode) {
        BuiltInThemes.CLEVERKEYS_DARK
    } else {
        BuiltInThemes.SNOW
    }
}

// Listen for system theme changes
private val themeChangeReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (config.theme == "system") {
            applyTheme(getSystemTheme(context))
        }
    }
}
```

## Custom Colors

### Color Customization

```kotlin
// ThemeManager.kt
fun createCustomTheme(customColors: Map<String, Int>): ColorScheme {
    val baseTheme = getBaseTheme()

    return baseTheme.copy(
        keyboardBackground = customColors["keyboard_background"]
            ?: baseTheme.keyboardBackground,
        keyBackground = customColors["key_background"]
            ?: baseTheme.keyBackground,
        keyText = customColors["key_text"]
            ?: baseTheme.keyText,
        accent = customColors["accent"]
            ?: baseTheme.accent,
        // ... other customizable colors
    )
}
```

### Storage Format

```kotlin
// Config.kt
// Stored as JSON in SharedPreferences
{
    "theme": "custom",
    "custom_colors": {
        "keyboard_background": "#1a1a2e",
        "key_background": "#16213e",
        "key_text": "#ffffff",
        "accent": "#7c3aed"
    }
}
```

## Opacity Settings

```kotlin
// ThemeManager.kt
fun applyOpacity(color: Int, opacity: Float): Int {
    val alpha = (opacity * 255).toInt()
    return (color and 0x00FFFFFF) or (alpha shl 24)
}

// Applied to key backgrounds
val adjustedKeyBg = applyOpacity(theme.keyBackground, config.key_opacity / 100f)
```

## Configuration

| Setting | Key | Default | Range |
|---------|-----|---------|-------|
| **Theme** | `pref_theme` | "cleverkeys_dark" | Theme name |
| **Key Opacity** | `key_opacity` | 100 | 0-100 |
| **Background Opacity** | `background_opacity` | 100 | 0-100 |
| **Border Style** | `key_border_style` | "none" | none/subtle/visible |

## Related Specifications

- [Profile System](../../../specs/profile_system_restoration.md) - Theme export/import
- [Settings System](../../../specs/settings-system.md) - Preferences
