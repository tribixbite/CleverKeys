# Password Field Mode

## Overview

Password field mode disables predictions/autocorrect and provides a visibility toggle in the suggestion bar for password and PIN input fields. The mode is automatically activated when Android reports a password-type InputType, and includes an eye icon toggle to show/hide the entered password.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/SuggestionBar.kt` | Password mode UI | RelativeLayout, eye toggle, text display |
| `src/main/kotlin/tribixbite/cleverkeys/SuggestionHandler.kt` | `isPasswordField()` | Password field detection |
| `src/main/kotlin/tribixbite/cleverkeys/CleverKeysService.kt` | Password mode wiring | InputConnectionProvider, field detection |
| `res/drawable/ic_visibility.xml` | Eye icon | Material Design eye (visible state) |
| `res/drawable/ic_visibility_off.xml` | Eye with slash | Material Design eye (hidden state) |

## Architecture

```
EditorInfo (from app)
       |
       v
+------------------+
| isPasswordField()| -- Check InputType flags
+------------------+
       |
       v (if password)
+------------------+
| Disable          | -- Skip predictions, autocorrect
| Predictions      |
+------------------+
       |
       v
+------------------+
| Show Password    | -- RelativeLayout with eye toggle
| Mode UI          |
+------------------+
       |
       v
+------------------+
| Sync with        | -- Read actual field content
| InputConnection  |
+------------------+
```

## Implementation Details

### Password Field Detection

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

### Prediction Disabling

When in password mode:
- Word predictions are disabled
- Autocorrect is disabled
- Swipe typing predictions are disabled
- SuggestionHandler bypasses all prediction logic

### Eye Toggle UI

**Layout Structure:**

```
SuggestionBar (LinearLayout)
└── RelativeLayout (MATCH_PARENT)
    ├── ImageView (eye icon)
    │   ├── ALIGN_PARENT_END (fixed to right)
    │   └── CENTER_VERTICAL
    └── HorizontalScrollView
        ├── ALIGN_PARENT_START
        ├── START_OF(icon)
        ├── fillViewport=true
        ├── OnTouchListener -> requestDisallowInterceptTouchEvent(true)
        └── LinearLayout (Wrapper)
            ├── gravity=CENTER
            └── TextView (password display)
```

**Icon States:**
- Hidden: `ic_visibility_off.xml` - Eye with slash, `theme.subLabelColor` (gray)
- Visible: `ic_visibility.xml` - Open eye, `theme.activatedColor` (cyan/accent)

**Password Display:**
- Hidden: Shows bullet dots `●●●●●●●●` with letter spacing `0.15f`
- Visible: Shows actual password text in monospace font

### InputConnection Sync

Password text syncs with actual field content:

```kotlin
fun refreshPasswordFromField() {
    val ic = inputConnectionProvider?.getInputConnection() ?: return
    val beforeCursor = ic.getTextBeforeCursor(1000, 0)?.toString() ?: ""
    val afterCursor = ic.getTextAfterCursor(1000, 0)?.toString() ?: ""
    currentPasswordText.clear()
    currentPasswordText.append(beforeCursor + afterCursor)
}
```

This handles select-all+delete, cursor movement before backspace, paste operations, and external autocomplete.

### Touch Event Handling

Critical fix for scrolling in keyboard environment:

```kotlin
horizontalScrollView.setOnTouchListener { v, event ->
    when (event.action) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
            v.parent.requestDisallowInterceptTouchEvent(true)
        }
    }
    false
}
```

Without this, parent gesture detectors intercept horizontal drags and prevent scrolling.

### Security Considerations

1. Password text only stored in memory (volatile `StringBuilder`)
2. Cleared on field change or mode exit
3. Only shown when user explicitly toggles visibility
4. InputConnection read-only (no modification of field content)
