# Password Field Mode Specification

**Version**: 1.0
**Created**: 2025-12-30
**Status**: Implemented

## Overview

Password field mode disables predictions/autocorrect and provides a visibility toggle in the suggestion bar for password and PIN input fields.

## Features

### 1. Password Field Detection

Automatically detects password/PIN fields via Android `InputType` flags:

```kotlin
fun isPasswordField(info: EditorInfo?): Boolean {
    val inputType = info?.inputType ?: return false
    val variation = inputType and InputType.TYPE_MASK_VARIATION

    return when {
        // Text passwords
        (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT -> {
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        // PIN/numeric passwords
        (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER -> {
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        else -> false
    }
}
```

### 2. Prediction Disabling

When in password mode:
- Word predictions are disabled
- Autocorrect is disabled
- Swipe typing predictions are disabled
- SuggestionHandler bypasses all prediction logic

### 3. Eye Toggle Visibility

**UI Layout** (RelativeLayout-based):
- Eye icon fixed to right edge (`ALIGN_PARENT_END`)
- HorizontalScrollView constrained to left of icon (`START_OF`)
- Password text centered when short, scrollable when long

**Icon States**:
- `ic_visibility_off.xml` - Eye with slash (password hidden, shows dots)
- `ic_visibility.xml` - Open eye (password visible, shows actual text)

**Colors**:
- Hidden state: `theme.subLabelColor` (gray)
- Visible state: `theme.activatedColor` (cyan/accent)

### 4. Password Display

**Hidden Mode** (default):
- Shows bullet dots: `●●●●●●●●`
- Letter spacing for readability: `0.15f`

**Visible Mode** (after toggle):
- Shows actual password text
- Monospace font for consistent width

### 5. InputConnection Sync

Password text syncs with actual field content via `InputConnection`:

```kotlin
fun refreshPasswordFromField() {
    val ic = inputConnectionProvider?.getInputConnection() ?: return
    val beforeCursor = ic.getTextBeforeCursor(1000, 0)?.toString() ?: ""
    val afterCursor = ic.getTextAfterCursor(1000, 0)?.toString() ?: ""
    currentPasswordText.clear()
    currentPasswordText.append(beforeCursor + afterCursor)
}
```

This handles:
- Select-all + delete
- Cursor movement before backspace
- Paste operations
- External autocomplete

## Layout Architecture

```
SuggestionBar (LinearLayout)
└── RelativeLayout (MATCH_PARENT)
    ├── ImageView (eye icon)
    │   ├── ALIGN_PARENT_END (fixed to right)
    │   └── CENTER_VERTICAL
    └── HorizontalScrollView
        ├── ALIGN_PARENT_START
        ├── START_OF(icon) ← Key constraint!
        ├── fillViewport=true (enables centering)
        ├── OnTouchListener -> requestDisallowInterceptTouchEvent(true) (CRITICAL!)
        └── LinearLayout (Wrapper)
            ├── WRAP_CONTENT width
            ├── gravity=CENTER
            └── TextView
                ├── WRAP_CONTENT width
                ├── maxLines=1
                ├── horizontallyScrolling=true
                └── movementMethod=null
```

**Key Insight** (from Gemini):
- **Touch Interception:** The primary reason for scrolling failure in keyboard environments is that parent views (gesture detectors) intercept the horizontal drag.
- **Fix:** Attached an `OnTouchListener` to the `HorizontalScrollView` that calls `requestDisallowInterceptTouchEvent(true)` on `ACTION_DOWN` and `ACTION_MOVE`.
- **Structure:** Reverted to `TextView` wrapped in `LinearLayout` as it's the most semantic and clean layout, now that the touch event issue is resolved.
- `fillViewport=true` + `LinearLayout(gravity=CENTER)` ensures centering when short.
- `TextView(WRAP_CONTENT)` ensures expansion when long.

## Files Modified

| File | Changes |
|------|---------|
| `SuggestionBar.kt` | Password mode UI, RelativeLayout, eye toggle, InputConnection sync |
| `SuggestionHandler.kt` | Password mode bypass, sync calls |
| `CleverKeysService.kt` | Password field detection, InputConnectionProvider wiring |
| `res/drawable/ic_visibility.xml` | Material Design eye icon (visible) |
| `res/drawable/ic_visibility_off.xml` | Material Design eye icon with slash (hidden) |

## Testing Checklist

- [ ] Password field detected in login screens
- [ ] PIN field detected (numeric password)
- [ ] Predictions disabled in password mode
- [ ] Eye icon visible and tappable
- [ ] Toggle shows/hides password text
- [ ] Dots displayed when hidden
- [ ] Actual text displayed when visible
- [ ] Short passwords centered
- [ ] Long passwords scrollable
- [ ] Icon stays fixed during scroll
- [ ] Select-all + delete tracked correctly
- [ ] Cursor movement + backspace tracked correctly
- [ ] Theme colors applied correctly

## Security Considerations

1. Password text only stored in memory (volatile `StringBuilder`)
2. Cleared on field change or mode exit
3. Only shown when user explicitly toggles visibility
4. InputConnection read-only (no modification of field content)
