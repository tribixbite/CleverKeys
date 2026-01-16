# Quick Settings Tile

## Overview

CleverKeys provides an Android Quick Settings tile that allows users to quickly access the keyboard switcher from the notification shade. Tapping the tile opens the system input method picker, providing a convenient way to switch between keyboards without navigating to system settings.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/KeyboardTileService.kt` | `KeyboardTileService` | TileService implementation |
| `AndroidManifest.xml` | Service registration | Declares tile service |

## Architecture

```
Quick Settings Panel
       |
       v (user taps tile)
+------------------+
| KeyboardTile     | -- Android calls onClick()
| Service          |
+------------------+
       |
       v
+------------------+
| showInputMethod  | -- Opens system IME picker
| Picker()         |
+------------------+
       |
       v (fallback)
+------------------+
| Open Input       | -- If picker fails, opens settings
| Method Settings  |
+------------------+
```

## Public API

### KeyboardTileService

```kotlin
@RequiresApi(Build.VERSION_CODES.N)
class KeyboardTileService : TileService() {

    // Called when Quick Settings panel opens
    override fun onStartListening() {
        updateTileState()
    }

    // Called when user taps the tile
    override fun onClick() {
        showInputMethodPicker()
    }

    // Called when user adds tile to panel
    override fun onTileAdded() {
        updateTileState()
    }

    // Update tile visual state
    private fun updateTileState() {
        qsTile?.apply {
            state = if (isCleverKeysActive()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    // Show system input method picker
    private fun showInputMethodPicker() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.showInputMethodPicker()
    }
}
```

## Implementation Details

### Tile States

| State | Condition | Visual |
|-------|-----------|--------|
| `STATE_ACTIVE` | CleverKeys is current input method | Highlighted/colored |
| `STATE_INACTIVE` | Another keyboard is active | Dimmed/gray |

### Input Method Detection

```kotlin
private fun isCleverKeysActive(): Boolean {
    val currentIme = Settings.Secure.getString(
        contentResolver,
        Settings.Secure.DEFAULT_INPUT_METHOD
    )
    return currentIme?.contains(packageName) == true
}
```

### Fallback Behavior

If `InputMethodManager.showInputMethodPicker()` fails:

```kotlin
private fun showInputMethodPicker() {
    try {
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.showInputMethodPicker()
    } catch (e: Exception) {
        // Fallback: open system input method settings
        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }
}
```

### AndroidManifest Registration

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

### Requirements

- Android 7.0 (API 24) or higher (TileService introduced in API 24)
- CleverKeys must be installed and enabled as an input method
- User must manually add tile to Quick Settings panel

### User Flow

1. Pull down notification shade
2. Tap "Edit" or pencil icon to customize tiles
3. Find "CleverKeys" in available tiles
4. Drag to active tiles area
5. Tile now appears in Quick Settings
6. Tap tile → system IME picker appears → select keyboard
