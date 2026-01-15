---
title: Switching Layouts - Technical Specification
user_guide: ../../layouts/switching-layouts.md
status: implemented
version: v1.2.7
---

# Switching Layouts Technical Specification

## Overview

Layout switching handles transitions between installed keyboard layouts via gesture, button, or programmatic triggers, including visual feedback and state management.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| LayoutSwitcher | `LayoutSwitcher.kt` | Switch logic and history |
| GlobeKeyHandler | `Pointers.kt:900-1000` | Globe key gestures |
| LayoutIndicator | `KeyboardView.kt:600-700` | Visual feedback |
| LayoutPicker | `LayoutPickerView.kt` | Selection overlay |
| Config | `Config.kt` | Switching preferences |

## State Management

### Current Layout State

```kotlin
// LayoutSwitcher.kt
class LayoutSwitcher {
    var currentLayout: Layout
        private set

    var layoutHistory: ArrayDeque<String> = ArrayDeque(5)
        private set

    val activeLayouts: List<Layout>
        get() = config.active_layouts.map { layoutManager.getLayout(it) }
}
```

### Layout History

```kotlin
// LayoutSwitcher.kt
private fun recordLayoutSwitch(layoutId: String) {
    layoutHistory.addFirst(currentLayout.id)
    if (layoutHistory.size > 5) {
        layoutHistory.removeLast()
    }
}

fun getPreviousLayout(): Layout? {
    return layoutHistory.firstOrNull()?.let { layoutManager.getLayout(it) }
}
```

## Globe Key Handler

### Gesture Detection

```kotlin
// Pointers.kt:~950
private fun handleGlobeKey(ptr: Pointer, event: MotionEvent) {
    when (event.action) {
        MotionEvent.ACTION_DOWN -> {
            globeKeyDownTime = System.currentTimeMillis()
            globeKeyStartX = event.x
        }

        MotionEvent.ACTION_UP -> {
            val duration = System.currentTimeMillis() - globeKeyDownTime
            val deltaX = event.x - globeKeyStartX

            when {
                // Long press - show picker
                duration > LONG_PRESS_THRESHOLD -> {
                    showLayoutPicker()
                }
                // Double tap - toggle last two
                isDoubleTap() -> {
                    toggleLastTwoLayouts()
                }
                // Swipe - directional switch
                abs(deltaX) > SWIPE_THRESHOLD -> {
                    if (deltaX > 0) switchToNextLayout()
                    else switchToPreviousLayout()
                }
                // Single tap - cycle
                else -> {
                    cycleToNextLayout()
                }
            }
        }
    }
}
```

### Double Tap Detection

```kotlin
// Pointers.kt:~980
private var lastGlobeTapTime = 0L
private val DOUBLE_TAP_TIMEOUT = 300L

private fun isDoubleTap(): Boolean {
    val now = System.currentTimeMillis()
    val isDouble = (now - lastGlobeTapTime) < DOUBLE_TAP_TIMEOUT
    lastGlobeTapTime = now
    return isDouble
}

private fun toggleLastTwoLayouts() {
    val previous = layoutHistory.firstOrNull() ?: return
    switchToLayout(previous)
}
```

## Switch Methods

### Cycle Switch

```kotlin
// LayoutSwitcher.kt
fun cycleToNextLayout() {
    val layouts = activeLayouts
    val currentIndex = layouts.indexOfFirst { it.id == currentLayout.id }
    val nextIndex = (currentIndex + 1) % layouts.size

    switchToLayout(layouts[nextIndex].id)
}

fun cycleToPreviousLayout() {
    val layouts = activeLayouts
    val currentIndex = layouts.indexOfFirst { it.id == currentLayout.id }
    val prevIndex = (currentIndex - 1 + layouts.size) % layouts.size

    switchToLayout(layouts[prevIndex].id)
}
```

### Direct Switch

```kotlin
// LayoutSwitcher.kt
fun switchToLayout(layoutId: String) {
    val newLayout = layoutManager.getLayout(layoutId) ?: return

    // Record history
    recordLayoutSwitch(layoutId)

    // Update state
    currentLayout = newLayout
    config.current_layout = layoutId

    // Notify keyboard
    keyboardView.setLayout(newLayout)

    // Show indicator
    showLayoutIndicator(newLayout)

    // Haptic feedback
    triggerHaptic(HapticEvent.LAYOUT_SWITCH)
}
```

## Layout Indicator

```kotlin
// KeyboardView.kt:~650
private fun showLayoutIndicator(layout: Layout) {
    if (!config.show_layout_indicator) return

    layoutIndicatorView.apply {
        text = layout.name
        subtitle = layout.localeTag
        alpha = 1f
        visibility = VISIBLE
    }

    // Fade out after delay
    handler.postDelayed({
        layoutIndicatorView.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction { layoutIndicatorView.visibility = GONE }
            .start()
    }, INDICATOR_DISPLAY_TIME)
}
```

## Layout Picker

```kotlin
// LayoutPickerView.kt
class LayoutPickerView : FrameLayout {
    fun show() {
        // Populate list with all installed layouts
        val layouts = layoutManager.getInstalledLayouts()

        adapter.submitList(layouts.map { layout ->
            LayoutItem(
                layout = layout,
                isActive = layout.id == currentLayout.id,
                isInQuickSwitch = config.active_layouts.contains(layout.id)
            )
        })

        visibility = VISIBLE
        requestFocus()
    }

    fun onLayoutSelected(layoutId: String) {
        hide()
        layoutSwitcher.switchToLayout(layoutId)
    }
}
```

## Spacebar Gesture

```kotlin
// Pointers.kt:~850
private fun handleSpacebarSwipe(ptr: Pointer, dx: Float) {
    if (abs(dx) < LAYOUT_SWITCH_THRESHOLD) return

    if (dx > 0) {
        layoutSwitcher.cycleToNextLayout()
    } else {
        layoutSwitcher.cycleToPreviousLayout()
    }
}
```

## Per-App Layout

```kotlin
// LayoutSwitcher.kt
private val perAppLayouts = mutableMapOf<String, String>()

fun onAppChanged(packageName: String) {
    if (!config.per_app_layout_enabled) return

    val savedLayout = perAppLayouts[packageName]
    if (savedLayout != null && savedLayout != currentLayout.id) {
        switchToLayout(savedLayout, animate = false)
    }
}

fun saveLayoutForApp(packageName: String) {
    perAppLayouts[packageName] = currentLayout.id
    savePerAppLayoutsToPrefs()
}
```

## Configuration

| Setting | Key | Default | Description |
|---------|-----|---------|-------------|
| **Show Globe** | `show_globe_key` | true | Display globe key |
| **Quick Switch Layouts** | `active_layouts` | All | Layouts in cycle |
| **Per-App Layout** | `per_app_layout_enabled` | false | Remember per app |
| **Show Indicator** | `show_layout_indicator` | true | Show switch indicator |
| **Indicator Duration** | `indicator_duration` | 1000ms | Display time |

## Related Specifications

- [Adding Layouts](adding-layouts-spec.md) - Layout management
- [Gesture System](../../../specs/gesture-system.md) - Globe key gestures
- [Layout System](../../../specs/layout-system.md) - Full architecture
