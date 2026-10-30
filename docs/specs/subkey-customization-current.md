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

Unchanged by the 2026-10-30 work below: a custom mapping (global) still wins over the layout's
own subkey in the same direction, in every layout.

## Runtime key codes (2026-10-30)

A mapping is stored and looked up under the key's RUNTIME code: the lowercased string of the
key's main value (`Pointers`: `key.keys[0].getString()`). For a key drawn with the key font that
string is a private-use glyph — Enter is U+E00E, Space U+E00D, Tab U+E00F.

Settings → Short Swipe Customization learns which key was tapped from the text it types into a
hidden field, so it used to store Enter's mappings under `"\n"` and Space's under `" "`. Those
never fired and were never drawn; the Enter swipe kept the bottom row's own subkey (NW
`voice_typing`, which switches to another keyboard's voice input) while the dialog showed the
mapping, and the dialog title printed the newline (maintainer screenshots 9/10). A mapping stored
with an upper-case code fired but was not drawn (the draw index kept the raw code).

`ShortSwipeKeyCodes.canonical` maps typed text to the key that types it and lowercases;
`ShortSwipeCustomizationManager` applies it on save, on load (so a saved `"\n"` mapping moves to
Enter) and on every lookup. `ShortSwipeKeyCodes.displayName` names a key for titles and toasts
through `KeyLabels` ("Enter", "Space", "A"), never a control or private-use character. Toasts
and lists in the system font name a command (`Command: Voice input (on device)`) and never print a
key-font glyph (`ShortSwipeAssignment.textLabel`); TalkBack already used
`ShortSwipeAccessibilityActions.describeMapping`. Tests: `ShortSwipeKeyCodesTest` (pure),
`ShortSwipeRuntimeKeyCodeTest`, `ShortSwipeLabelTextTest` (mock).

## Sources in the per-key dialog (2026-10-30)

The per-key dialog (`KeyCustomizationDialog`) lists all eight directions, clockwise from north,
each with what a swipe that way does NOW and a badge for where that comes from
(`KeySubkeySources` / `SubkeySource`, pure):

| Badge | Source | Editing it |
|-------|--------|------------|
| Layout | the current layout's own XML defines it | edits the layout (copy-on-write, below) |
| Hidden | the layout defines it, but it is a `loc` subkey that Extra Keys has off | edits the layout |
| Built-in | the shared bottom row (Space, Enter, Ctrl, Fn, Compose) or a key the keyboard adds (number row) | custom mapping |
| Extra key | placed by Settings → Extra Keys | custom mapping (a `removed` blank to hide it) |
| Custom | a custom short swipe; the row also says what it replaces ("Replaces @") | custom mapping; the trailing button restores what it replaces |
| Removed | a custom `removed` blank hides what is underneath | the trailing button restores it |
| (empty) | nothing | a new custom mapping |

The key is resolved against the keyboard's current `layouts` entry
(`CleverKeysService.layoutSourceSnapshot`: entry index, the layout before Extra Keys and the bottom
row are added, whether a pane is showing, and the System entry's built-in name via
`KeyboardData.cachedResourceIdOf`). `KeySubkeySources.locate` finds the key by its main value in
the entry's own layout (first match). A key not found there is looked up in `res/xml/bottom_row.xml`
(Built-in); otherwise its live subkeys count as Built-in. A pane (numeric, emoji …) has no entry,
so its keys are never layout-editable.

## Layout subkeys and modified copies (2026-10-30)

Changing or removing a subkey whose effective entry is the layout's OWN (`SubkeySlot.editsLayout`:
no custom mapping there, defined by the entry's XML) writes the layout, never a built-in layout:

1. A built-in entry (named, or the System entry's locale layout) first asks *"Change a subkey of
   the layout?"* — **Create copy** or **Change in all layouts** (the old behaviour: a global custom
   mapping) or Cancel.
2. **Create copy** (`LayoutSubkeyEditor.apply`): the built-in XML (raw resource) gets the edit
   (`LayoutSubkeyXml.setSubkey`, addressed by row/key position, removing both synonyms `nw`/`key1`
   before writing the short name), is renamed "<name> (modified)" (`subkey_layout_copy_name`),
   must parse (`KeyboardData.load_string_exn`), and replaces the entry IN PLACE in the `layouts`
   preference as a `CustomLayout` with the entry's language, theme and key-geometry choices and
   `origin` = the built-in name (`"system"` for the System entry). The keyboard reloads the
   preference; the current-layout index is unchanged, so the copy is what is now shown.
3. Later edits of that layout (and of any other custom layout) change its XML directly, without
   the question; the copy keeps its name and origin.
4. The value written is `XmlAttributeMapper.layoutValue(mapping)`; an action a layout cannot hold
   (a name-only command such as `paste_pinned_1`, which the parser would read as a string key
   typing its own name) is saved as a custom mapping instead, with a toast saying so. The mapping's
   own label is not stored in the XML: a layout key's label comes from its value.
5. If the `layouts` entry changed between opening the dialog and saving (Layout Manager, a
   restore), nothing is written (`Outcome.Failed`).

**Revert.** Layout Manager shows a copy as "Modified copy of <name>" with a **Revert to original**
chip; confirming replaces the copy with the built-in entry (`CustomLayout.revertedFrom`), keeping
its bindings and discarding the changed subkeys. Editing a copy's XML in Layout Manager keeps its
origin.

**Backup / export.** `origin` is an optional field of the custom entry in the `layouts` JSON
(`LayoutsPreference.Serializer`; a value that is not `system` or a raw-resource name is dropped on
load), so Backup & Restore carries copies like any custom layout, and **Export XML** exports a
copy's XML like any custom layout's.

Tests: `KeySubkeySourcesTest`, `LayoutSubkeyXmlTest` (pure); `LayoutSubkeyBuiltInLayoutsTest`
(every built-in layout: XML positions match the parser, an edit reads back exactly, catalogue
commands round-trip), `LayoutSubkeyEditorTest` (mock); `KeyCustomizationDialogSourcesComposeTest`,
`LayoutSubkeyCopyInstrumentedTest` (instrumented).

Not covered: the subkey popover's assign/edit screen (`SubkeyAssignActivity`) still edits a
layout default through a global custom mapping or `removed` blank, as before. # TODO: route its
layout-default edits through `LayoutSubkeyEditor` too.

## Current Limitation

Custom mappings apply to ALL layouts. Per-layout changes are now made by editing the layout's own
subkeys (a modified copy, above); Export XML still bakes the global mappings into a layout file.

See [per-layout-subkey-customization.md](per-layout-subkey-customization.md) for proposed per-layout architecture.
