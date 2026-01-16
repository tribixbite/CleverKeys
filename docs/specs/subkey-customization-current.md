# Subkey Customization System

## Overview

Two-layer system for customizing short-swipe subkeys (corner actions) on any key. Layout XML defines static defaults; runtime JSON customizations override them globally. Export XML workflow allows baking customizations into standalone layout files.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/customization/ShortSwipeCustomizationManager.kt` | CRUD operations | JSON persistence |
| `src/main/kotlin/tribixbite/cleverkeys/customization/ShortSwipeMapping.kt` | Data model | Mapping structure |
| `src/main/kotlin/tribixbite/cleverkeys/customization/CustomShortSwipeExecutor.kt` | `execute()` | 137 commands |
| `src/main/kotlin/tribixbite/cleverkeys/customization/XmlLayoutExporter.kt` | `injectMappings()` | XML export |
| `src/main/kotlin/tribixbite/cleverkeys/customization/XmlAttributeMapper.kt` | `toXmlValue()` | Mapping → XML |
| `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt` | Gesture detection | Runtime lookup |

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     LAYOUT XML (Static)                         │
│  Location: res/raw/*.xml or CustomLayout XML string             │
│  Example: <key c="a" nw="@" ne="#" sw="1" se="2"/>             │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ Loaded at startup
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                  RUNTIME CUSTOMIZATIONS (Dynamic)               │
│  Location: short_swipe_customizations.json                      │
│  Scope: GLOBAL (applies to ALL layouts)                         │
│  Format: { "mappings": { "a:NW": {...}, "a:NE": {...} } }      │
└─────────────────────────────────────────────────────────────────┘
```

## Storage

| Component | Location | Format | Scope |
|-----------|----------|--------|-------|
| Built-in layouts | `res/raw/*.xml` | XML | Read-only, per-layout |
| Custom layouts | SharedPreferences `layouts` | JSON-encoded XML | Per-layout |
| Subkey customizations | `short_swipe_customizations.json` | JSON | **Global** |

## Data Model

```kotlin
data class ShortSwipeMapping(
    val keyCode: String,            // "a", "space", "shift"
    val direction: SwipeDirection,  // NW, N, NE, W, E, SW, S, SE
    val actionType: ActionType,     // TEXT, COMMAND, KEY_EVENT
    val actionValue: String,        // Action payload
    val displayLabel: String?       // Optional custom label
)

enum class ActionType {
    TEXT,      // Insert literal text
    COMMAND,   // Execute command: "copy", "paste", "undo"
    KEY_EVENT  // Send key event: "66" (Enter)
}
```

### Storage Key Format

```
"keyCode:direction" → ShortSwipeMapping

Examples:
  "a:NW"    → { actionType: TEXT, actionValue: "@" }
  "space:E" → { actionType: COMMAND, actionValue: "cursor_right" }
  "e:SE"    → { actionType: TEXT, actionValue: "€" }
```

## Runtime Gesture Handling

```kotlin
// Pointers.kt - Gesture detection flow

1. Detect swipe direction (NW, N, NE, W, E, SW, S, SE)
2. Get key identifier (e.g., "a", "space")

3. CHECK JSON CUSTOMIZATIONS FIRST:
   val customMapping = _customSwipeManager.getMapping(keyCode, swipeDir)
   if (customMapping != null) {
       _handler.onCustomShortSwipe(customMapping)
       return
   }

4. FALL BACK TO LAYOUT XML:
   val gestureValue = getNearestKeyAtDirection(ptr, direction)
```

## Export XML Workflow

Bakes global customizations into standalone layout file:

```
User editing CustomLayout
    ↓
Click "Export XML"
    ↓
XmlLayoutExporter.injectMappings(xmlText, mappings)
    ↓
1. Parse XML to DOM
2. For each <key> element:
   - Find matching mappings by keyCode
   - Convert mapping to XML attribute
   - Add/overwrite direction attributes
3. Serialize back to XML
    ↓
Output: <key c="a" nw="@" sw="'test'" se="copy"/>
```

### XML Attribute Mapping

| ActionType | JSON Value | XML Output |
|------------|------------|------------|
| TEXT | `"hello"` | `'hello'` (single-quoted) |
| COMMAND | `"copy"` | `copy` (keyword) |
| KEY_EVENT | `"66"` | `keyevent:66` |

## Backup/Restore Integration

```json
{
  "metadata": { "app_version": "1.1.71" },
  "preferences": { ... },
  "short_swipe_customizations": {
    "mappings": {
      "a:NW": { "actionType": "TEXT", "actionValue": "!" },
      "space:E": { "actionType": "COMMAND", "actionValue": "cursor_right" }
    }
  }
}
```

## Lookup Priority

1. **JSON customization** (`short_swipe_customizations.json`) - checked first
2. **Layout XML subkey** (`key1` through `key8` attributes) - fallback

## Current Limitation

All customizations apply to ALL layouts. Workaround: Use Export XML to bake customizations into specific layouts, then import as separate CustomLayouts.

See [per-layout-subkey-customization.md](per-layout-subkey-customization.md) for proposed per-layout architecture.
