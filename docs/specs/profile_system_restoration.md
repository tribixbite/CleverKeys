# Profile System: Layout Import/Export with Short Swipe Integration

## Overview
**Feature Name**: Profile System (Unified Layout + Gesture Export)
**Status**: Implemented (commit 34f3a353, Dec 10 2025)
**Priority**: P2

### Summary
The Profile System enables importing and exporting keyboard layouts as XML files with embedded short swipe customizations. Users can share complete keyboard configurations as portable "profiles" that include both layout structure and custom gesture mappings.

### Key Design Decision
The original design proposed a proprietary `<short_swipes>` XML extension. This was **rejected** in favor of injecting gestures as standard Unexpected Keyboard XML attributes (`nw`, `ne`, `sw`, `se`, etc.). This approach:
- Maintains 100% backward compatibility with original UK layouts
- Allows exported profiles to work in any UK-compatible keyboard
- Avoids format fragmentation

---

## Architecture

### Components

| Component | File | Responsibility |
|-----------|------|----------------|
| **XmlLayoutExporter** | `customization/XmlLayoutExporter.kt` | Parses layout XML, injects short swipe mappings into `<key>` tags |
| **XmlAttributeMapper** | `customization/XmlAttributeMapper.kt` | Converts `ShortSwipeMapping` → UK XML attributes (e.g., `copy`, `keyevent:66`) |
| **ShortSwipeCustomizationManager** | `customization/ShortSwipeCustomizationManager.kt` | Stores/loads gesture mappings |
| **LayoutManagerActivity** | `LayoutManagerActivity.kt` | UI with Import/Export buttons using SAF |

### Data Flow

```
Export:
  ShortSwipeCustomizationManager.getMappings()
    → XmlAttributeMapper.toXmlValue(mapping)
    → XmlLayoutExporter.injectMappings(layoutXml, mappings)
    → ActivityResultContracts.CreateDocument → File

Import:
  ActivityResultContracts.OpenDocument → File
    → KeyboardData.load_string_exn(xml) [validates]
    → Editor text updated
    → Swipes from standard attributes work automatically
```

---

## Technical Specification

### XML Attribute Mapping

Short swipe mappings are converted to standard UK XML attributes:

| Mapping Type | XML Attribute Example |
|--------------|----------------------|
| Command (copy) | `nw="copy"` |
| Command (selectAll) | `se="selectAll"` |
| Text insertion | `sw="'Hello World'"` |
| Key event | `ne="keyevent:66"` (Enter) |

### Direction → Attribute Mapping

| SwipeDirection | XML Attribute |
|----------------|---------------|
| NW | `nw` (key1) |
| N | `n` (key7) |
| NE | `ne` (key2) |
| W | `w` (key6) |
| E | `e` (key5) |
| SW | `sw` (key3) |
| S | `s` (key8) |
| SE | `se` (key4) |

### XmlAttributeMapper.toXmlValue()

```kotlin
fun toXmlValue(mapping: ShortSwipeMapping): String {
    return when (mapping.actionType) {
        ActionType.TEXT -> "'${mapping.actionValue.replace("'", "'\'")}'"
        ActionType.COMMAND -> mapCommandToKeyword(mapping.getCommand()) ?: mapping.actionValue
        ActionType.KEY_EVENT -> "keyevent:${mapping.actionValue}"
    }
}
```

### Supported Command Mappings

| AvailableCommand | UK XML Keyword |
|-----------------|----------------|
| COPY | `copy` |
| PASTE | `paste` |
| CUT | `cut` |
| SELECT_ALL | `selectAll` |
| UNDO | `undo` |
| REDO | `redo` |
| CURSOR_LEFT/RIGHT/UP/DOWN | `cursor_left/right/up/down` |
| CURSOR_HOME/END | `home`, `end` |
| DELETE_WORD | `delete_word` |
| SWITCH_IME | `change_method` |
| VOICE_INPUT | `voice_typing` |

---

## UI Implementation

### CustomLayoutEditorDialog (LayoutManagerActivity.kt)

**Import XML Button** (line 638):
1. Opens SAF file picker (`ActivityResultContracts.OpenDocument` with `text/xml`)
2. Reads XML content
3. Validates with `KeyboardData.load_string_exn()`
4. Populates editor text field
5. Swipes defined via standard attributes work immediately

**Export XML Button** (line 649):
1. Validates current editor XML
2. Retrieves global mappings from `ShortSwipeCustomizationManager`
3. Calls `XmlLayoutExporter.injectMappings()` to embed gestures
4. Opens SAF create dialog (`ActivityResultContracts.CreateDocument`)
5. Writes enhanced XML to file

---

## Usage

1. Open **Settings** → **Keyboard Layouts**
2. Select **Custom Layout** or edit existing
3. **Export XML**: Saves layout + all custom gesture overrides
4. **Import XML**: Loads layout; gestures from standard attributes work immediately

---

## Compatibility

| Scenario | Result |
|----------|--------|
| Import standard UK layout | ✅ Works (no gestures) |
| Import CleverKeys exported profile | ✅ Works (gestures included) |
| Export to UK format | ✅ Works (gestures baked into standard attributes) |
| Import in original UK keyboard | ✅ Works (gestures visible as sub-labels) |

---

## Files

- `src/main/kotlin/tribixbite/cleverkeys/customization/XmlLayoutExporter.kt` (116 lines)
- `src/main/kotlin/tribixbite/cleverkeys/customization/XmlAttributeMapper.kt` (77 lines)
- `src/main/kotlin/tribixbite/cleverkeys/customization/ShortSwipeCustomizationManager.kt` (updated)
- `src/main/kotlin/tribixbite/cleverkeys/LayoutManagerActivity.kt` (Import/Export UI)

---

## Security

- **File Access**: Uses Android Storage Access Framework (SAF) - no broad storage permissions
- **Validation**: XML parsed and validated before application
- **No Network**: All operations are local

---

## XML Parsing Behavior

### Parsed Attributes

`KeyboardData.Key.parse()` reads these attributes from `<key>` tags:

| Attribute | Purpose |
|-----------|---------|
| `c` / `key0` | Center key value |
| `nw`/`ne`/`sw`/`se` / `key1-4` | Corner swipe keys |
| `n`/`s`/`w`/`e` / `key5-8` | Cardinal swipe keys |
| `anticircle` | Anti-clockwise circle gesture key |
| `width` | Key width (relative units) |
| `shift` | Left padding (relative units) |
| `indication` | Display string override |

### Ignored Attributes

| Attribute | Status | Notes |
|-----------|--------|-------|
| `slider="true"` | Ignored | Not parsed by `Key.parse()`. However, cursor keys (`cursor_left`, `cursor_right`) are inherently slider keys in `KeyValue.kt`, so slider behavior still works. |

### Supported Key Names

All UK key names are supported. See `KeyValue.getSpecialKeyByName()` for the full list including:
- Modifiers: `shift`, `ctrl`, `alt`, `fn`, `meta`
- Navigation: `up`, `down`, `left`, `right`, `home`, `end`, `page_up`, `page_down`
- Editing: `copy`, `paste`, `cut`, `selectAll`, `undo`, `redo`, `delete_word`
- Special: `space`, `enter`, `backspace`, `delete`, `tab`, `esc`
- Switches: `switch_numeric`, `switch_emoji`, `switch_clipboard`, `config`, `action`
- Cursor sliders: `cursor_left`, `cursor_right`, `cursor_up`, `cursor_down`
- `loc ` prefix: Makes key invisible (placeholder for optional extras)

---

**Implemented**: Dec 10, 2025 (commit 34f3a353)
**Last Updated**: 2025-12-11
