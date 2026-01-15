---
title: Appearance Settings - Technical Specification
user_guide: ../../settings/appearance.md
status: implemented
version: v1.2.7
---

# Appearance Settings Technical Specification

## Overview

The appearance system manages keyboard dimensions, key styling, animations, and visual feedback rendering.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| KeyboardView | `KeyboardView.kt` | Main rendering |
| DimensionCalculator | `DimensionCalculator.kt` | Size calculations |
| AnimationController | `AnimationController.kt` | Key animations |
| Config | `Config.kt` | Appearance preferences |

## Dimension Calculation

### Height Calculation

```kotlin
// DimensionCalculator.kt
fun calculateKeyboardHeight(config: Config, screenMetrics: DisplayMetrics): Int {
    val baseHeight = when (config.keyboard_height_preset) {
        HeightPreset.EXTRA_SMALL -> 180.dp
        HeightPreset.SMALL -> 210.dp
        HeightPreset.NORMAL -> 240.dp
        HeightPreset.LARGE -> 280.dp
        HeightPreset.EXTRA_LARGE -> 320.dp
        HeightPreset.CUSTOM -> config.keyboard_height_custom.dp
    }

    // Apply orientation multiplier
    val orientationFactor = if (isLandscape) {
        config.landscape_height_ratio
    } else 1.0f

    // Account for prediction bar
    val predictionHeight = when (config.prediction_bar_height) {
        PredictionBarHeight.HIDDEN -> 0
        PredictionBarHeight.COMPACT -> 36.dp
        PredictionBarHeight.NORMAL -> 44.dp
        PredictionBarHeight.EXPANDED -> 56.dp
    }

    return (baseHeight * orientationFactor).toInt() + predictionHeight
}
```

### Row and Key Sizing

```kotlin
// DimensionCalculator.kt
fun calculateKeyDimensions(
    rowWidth: Int,
    keysInRow: List<LayoutKey>,
    config: Config
): List<KeyDimension> {
    // Sum of all key weights
    val totalWeight = keysInRow.sumOf { it.width.toDouble() }

    // Available width after gaps
    val gapWidth = config.key_gap.dp * (keysInRow.size - 1)
    val availableWidth = rowWidth - config.horizontal_margin.dp * 2 - gapWidth

    return keysInRow.map { key ->
        KeyDimension(
            width = (availableWidth * key.width / totalWeight).toInt(),
            height = rowHeight - config.key_gap.dp
        )
    }
}
```

## Key Rendering

### Key Drawing

```kotlin
// KeyboardView.kt
private fun drawKey(canvas: Canvas, key: Key, bounds: RectF) {
    // Background
    val bgPaint = when {
        key.isPressed -> pressedKeyPaint
        key.isModifier -> modifierKeyPaint
        else -> normalKeyPaint
    }

    // Shape based on config
    val cornerRadius = when (config.key_shape) {
        KeyShape.SQUARE -> 0f
        KeyShape.ROUNDED -> 8.dp
        KeyShape.PILL -> bounds.height() / 2
        KeyShape.MINIMAL -> 4.dp
    }

    canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, bgPaint)

    // Border (optional)
    if (config.key_border_enabled) {
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, borderPaint)
    }

    // Label
    drawKeyLabel(canvas, key, bounds)

    // Subkey hints
    if (config.show_subkey_hints) {
        drawSubkeyHints(canvas, key, bounds)
    }
}
```

### Animation System

```kotlin
// AnimationController.kt
class KeyAnimationController {
    private val activeAnimations = mutableMapOf<Key, ValueAnimator>()

    fun animateKeyPress(key: Key, view: KeyboardView) {
        when (config.press_animation) {
            PressAnimation.NONE -> return

            PressAnimation.HIGHLIGHT -> {
                animateHighlight(key, view)
            }

            PressAnimation.SCALE -> {
                animateScale(key, view)
            }

            PressAnimation.BOTH -> {
                animateHighlight(key, view)
                animateScale(key, view)
            }
        }
    }

    private fun animateScale(key: Key, view: KeyboardView) {
        ValueAnimator.ofFloat(1.0f, 1.1f, 1.0f).apply {
            duration = 100
            addUpdateListener {
                key.scale = it.animatedValue as Float
                view.invalidateKey(key)
            }
            start()
        }
    }
}
```

## Key Pop-up

```kotlin
// KeyPopupView.kt
class KeyPopupView : View {
    fun showPopup(key: Key, anchor: RectF) {
        if (!config.key_popup_enabled) return
        if (config.key_popup_mode == PopupMode.HOLD_ONLY && !key.isLongPressed) return

        // Position above key
        val popupY = anchor.top - popupHeight - 8.dp

        // Draw popup
        popupDrawable.bounds = Rect(
            anchor.centerX().toInt() - popupWidth / 2,
            popupY.toInt(),
            anchor.centerX().toInt() + popupWidth / 2,
            popupY.toInt() + popupHeight
        )

        // Show with animation
        alpha = 0f
        visibility = VISIBLE
        animate().alpha(1f).setDuration(50).start()
    }
}
```

## Prediction Bar

```kotlin
// PredictionBarView.kt
class PredictionBarView : ViewGroup {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = when (config.prediction_bar_height) {
            PredictionBarHeight.HIDDEN -> 0
            PredictionBarHeight.COMPACT -> 36.dp
            PredictionBarHeight.NORMAL -> 44.dp
            PredictionBarHeight.EXPANDED -> 56.dp
        }
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            height
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val count = config.prediction_count
        val itemWidth = width / count

        predictions.forEachIndexed { i, pred ->
            val child = getChildAt(i)
            child.layout(
                i * itemWidth,
                0,
                (i + 1) * itemWidth,
                height
            )
        }
    }
}
```

## Configuration

| Setting | Key | Default | Range |
|---------|-----|---------|-------|
| **Height Preset** | `keyboard_height_preset` | NORMAL | XS/S/M/L/XL/Custom |
| **Custom Height** | `keyboard_height_custom` | 240 | 150-400 |
| **Bottom Margin** | `bottom_margin` | 0 | 0-48 |
| **Side Margins** | `horizontal_margin` | 0 | 0-32 |
| **Key Shape** | `key_shape` | ROUNDED | Square/Rounded/Pill/Minimal |
| **Key Gap** | `key_gap` | 4 | 0-12 |
| **Press Animation** | `press_animation` | HIGHLIGHT | None/Highlight/Scale/Both |
| **Key Popup** | `key_popup_enabled` | true | bool |
| **Popup Mode** | `key_popup_mode` | ALWAYS | Always/HoldOnly |
| **Prediction Height** | `prediction_bar_height` | NORMAL | Hidden/Compact/Normal/Expanded |
| **Prediction Count** | `prediction_count` | 5 | 3-7 |

## Related Specifications

- [Themes](../customization/themes-spec.md) - Color system
- [Settings System](../../../specs/settings-system.md) - Preferences
- [Layout System](../../../specs/layout-system.md) - Key layout
