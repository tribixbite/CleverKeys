# Subkey Customization System - Current Implementation

## Overview

CleverKeys supports customizing the short-swipe subkeys (corner actions) on any key. This document describes the current architecture, data flow, and the "Export XML" workflow that allows baking customizations into standalone layout files.

## Architecture

### Two-Layer System

```
┌─────────────────────────────────────────────────────────────────┐
│                     LAYOUT XML (Static)                         │
│  Location: res/raw/*.xml or CustomLayout XML string             │
│  Defines: Base key layout with default subkeys                  │
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

### Storage Locations

| Component | Location | Format | Scope |
|-----------|----------|--------|-------|
| Built-in layouts | `res/raw/*.xml` | XML | Read-only, per-layout |
| Custom layouts | SharedPreferences `layouts` | JSON-encoded XML strings | Per-layout |
| Subkey customizations | `short_swipe_customizations.json` | JSON | **Global** |
| Extra keys | SharedPreferences `extra_keys` | JSON | Global |

## Runtime Gesture Handling

When a user performs a short swipe gesture:

```kotlin
// Pointers.kt - Gesture detection flow

1. Detect swipe direction (NW, N, NE, W, E, SW, S, SE)
2. Get key identifier (e.g., "a", "space", "shift")

3. CHECK JSON CUSTOMIZATIONS FIRST:
   val customMapping = _customSwipeManager.getMapping(keyCode, swipeDir)
   if (customMapping != null) {
       // Execute custom action (TEXT, COMMAND, or KEY_EVENT)
       _handler.onCustomShortSwipe(customMapping)
       return
   }

4. FALL BACK TO LAYOUT XML:
   val gestureValue = getNearestKeyAtDirection(ptr, direction)
   // Use the subkey defined in XML (key1-key8 attributes)
```

### Lookup Priority

1. **JSON customization** (`short_swipe_customizations.json`) - checked first
2. **Layout XML subkey** (`key1` through `key8` attributes) - fallback

## Data Models

### ShortSwipeMapping (JSON)

```kotlin
data class ShortSwipeMapping(
    val keyCode: String,        // e.g., "a", "space"
    val direction: SwipeDirection,  // NW, N, NE, W, E, SW, S, SE
    val actionType: ActionType,     // TEXT, COMMAND, KEY_EVENT
    val actionValue: String,        // The action payload
    val displayLabel: String?       // Optional custom label
)

enum class ActionType {
    TEXT,      // Insert literal text: "hello" → commits "hello"
    COMMAND,   // Execute command: "copy", "paste", "undo"
    KEY_EVENT  // Send key event: "66" (Enter), "67" (Backspace)
}
```

### Storage Key Format

```
"keyCode:direction" → ShortSwipeMapping

Examples:
  "a:NW" → { actionType: TEXT, actionValue: "@" }
  "space:E" → { actionType: COMMAND, actionValue: "cursor_right" }
  "e:SE" → { actionType: TEXT, actionValue: "€" }
```

## Backup/Restore Integration

### Export (BackupRestoreManager.exportConfig)

```json
{
  "metadata": { "app_version": "1.1.71", ... },
  "preferences": { ... },
  "short_swipe_customizations": {
    "mappings": {
      "a:NW": { "actionType": "TEXT", "actionValue": "!" },
      "space:E": { "actionType": "COMMAND", "actionValue": "cursor_right" }
    }
  }
}
```

### Import (BackupRestoreManager.importConfig)

1. Parses `preferences` → writes to SharedPreferences
2. If `short_swipe_customizations` present → `ShortSwipeCustomizationManager.importFromJson()`

## Export XML Workflow

The **Export XML** button in the Layout Manager provides a way to "bake" global customizations into a standalone layout file.

### Flow

```
┌─────────────────────────────────────────────────────────────────┐
│  User editing CustomLayout in LayoutManagerActivity             │
│  Current XML: <key c="a" nw="@"/>                              │
│  Global customizations: a:SW="test", a:SE=copy                 │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ Click "Export XML"
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  XmlLayoutExporter.injectMappings(xmlText, mappings)           │
│                                                                 │
│  1. Parse XML to DOM                                           │
│  2. For each <key> element:                                    │
│     - Find matching mappings by keyCode                        │
│     - Convert mapping to XML attribute value                   │
│     - Add/overwrite direction attributes                       │
│  3. Serialize back to XML string                               │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  Output XML: <key c="a" nw="@" sw="'test'" se="copy"/>        │
│                                                                 │
│  User saves via Storage Access Framework                        │
└─────────────────────────────────────────────────────────────────┘
```

### XML Attribute Mapping

| ActionType | JSON Value | XML Output |
|------------|------------|------------|
| TEXT | `"hello"` | `'hello'` (single-quoted) |
| COMMAND | `"copy"` | `copy` (keyword) |
| COMMAND | `"cursor_left"` | `cursor_left` |
| KEY_EVENT | `"66"` | `keyevent:66` |

### Use Cases

1. **Share customized layout**: Export XML with customizations baked in, share file
2. **Backup layout**: Export preserves both structure and customizations
3. **Create template**: Customize globally, export, then reset global customizations

## Current Limitations

### 1. Global Scope Only

All customizations apply to ALL layouts. If you set `a:NW = "!"` on QWERTY, it also affects Cyrillic, Greek, etc.

**Workaround**: Use Export XML to bake customizations into specific layouts, then import as separate CustomLayouts.

### 2. No Per-Layout Storage

The JSON format doesn't include layout context:

```json
// Current format - no layout awareness
{ "a:NW": { ... } }

// Would need for per-layout
{ "qwerty_us:a:NW": { ... } }
```

### 3. CustomLayout Identity

CustomLayouts are identified by their XML content hash. Editing a CustomLayout changes its identity, which would orphan per-layout customizations if they existed.

## Manual Per-Layout Workflow (Current)

Users can achieve per-layout customizations manually:

1. Select desired layout in settings
2. Open Customize Per-Key Actions
3. Make customizations (stored globally)
4. Go to Layout Manager → edit layout as CustomLayout
5. Click **Export XML** (bakes in customizations)
6. Save exported file
7. Import as new CustomLayout
8. Reset global customizations
9. Repeat for other layouts

This is cumbersome but functional.

## Related Files

| File | Purpose |
|------|---------|
| `ShortSwipeCustomizationManager.kt` | CRUD operations, JSON persistence |
| `ShortSwipeMapping.kt` | Data model |
| `CustomShortSwipeExecutor.kt` | Executes TEXT/COMMAND/KEY_EVENT actions |
| `XmlLayoutExporter.kt` | Injects mappings into XML |
| `XmlAttributeMapper.kt` | Converts mappings to XML attribute values |
| `BackupRestoreManager.kt` | Backup/restore including customizations |
| `Pointers.kt` | Runtime gesture detection and lookup |
| `LayoutManagerActivity.kt` | UI for Export XML button |

## Future Considerations

See [per-layout-subkey-customization.md](per-layout-subkey-customization.md) for proposed per-layout architecture.

Key questions for feedback:
1. Is the current global + Export XML workflow sufficient?
2. Should per-layout be automatic or opt-in?
3. How should SystemLayout (locale-dynamic) be handled?
4. Should exported XMLs include only modified subkeys or all subkeys?

---
*Created: 2024-12-21*
*Status: Current Implementation Documentation*
