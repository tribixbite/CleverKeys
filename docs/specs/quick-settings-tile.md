# Quick Settings Tile Specification

**Status**: Implemented
**Version**: 1.2.8
**Last Updated**: 2026-01-15

## Overview

CleverKeys provides a Quick Settings tile that allows users to quickly access the keyboard switcher from the Android notification shade. This provides a convenient way to switch between keyboards without navigating to system settings.

## Requirements

- Android 7.0 (API 24) or higher
- CleverKeys must be installed and enabled as an input method

## Implementation

### KeyboardTileService.kt

Location: `src/main/kotlin/tribixbite/cleverkeys/KeyboardTileService.kt`

```kotlin
@RequiresApi(Build.VERSION_CODES.N)
class KeyboardTileService : TileService()
```

#### Key Methods

| Method | Purpose |
|--------|---------|
| `onStartListening()` | Updates tile state when Quick Settings panel opens |
| `onClick()` | Shows input method picker when tile is tapped |
| `onTileAdded()` | Updates tile state when user adds tile to panel |
| `updateTileState()` | Sets tile active/inactive based on current keyboard |

### Tile States

| State | Condition | Visual |
|-------|-----------|--------|
| `STATE_ACTIVE` | CleverKeys is current input method | Highlighted |
| `STATE_INACTIVE` | Another keyboard is active | Dimmed |

### Android Manifest Registration

```xml
<service
    android:name="tribixbite.cleverkeys.KeyboardTileService"
    android:label="@string/app_name"
    android:icon="@mipmap/ic_launcher"
    android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"
    android:exported="true">
  <intent-filter>
    <action android:name="android.service.quicksettings.action.QS_TILE"/>
  </intent-filter>
</service>
```

## User Experience

### Adding the Tile

1. Pull down notification shade
2. Tap "Edit" or pencil icon
3. Find "CleverKeys" tile in available tiles
4. Drag to active tiles area

### Using the Tile

1. Pull down notification shade
2. Tap CleverKeys tile
3. System input method picker appears
4. Select desired keyboard

## Technical Details

### Input Method Detection

The tile checks if CleverKeys is the current input method by reading:

```kotlin
val currentIme = Settings.Secure.getString(
    contentResolver,
    Settings.Secure.DEFAULT_INPUT_METHOD
)
val isCleverKeysActive = currentIme?.contains(packageName) == true
```

### Fallback Behavior

If `InputMethodManager.showInputMethodPicker()` fails:
- Opens system input method settings as fallback
- Uses `Settings.ACTION_INPUT_METHOD_SETTINGS` intent

## Related Files

- `KeyboardTileService.kt` - TileService implementation
- `AndroidManifest.xml` - Service registration
- `ic_launcher` - Tile icon (uses app launcher icon)

## References

- [Android TileService Documentation](https://developer.android.com/develop/ui/views/quicksettings-tiles)
