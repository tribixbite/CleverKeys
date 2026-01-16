# Profile System

## Overview

The Profile System enables importing and exporting keyboard layouts as XML files with embedded short swipe customizations. Layouts are exported with gestures baked into standard Unexpected Keyboard XML attributes for full backward compatibility.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/customization/XmlLayoutExporter.kt` | `XmlLayoutExporter` | Parses layout XML, injects short swipe mappings |
| `src/main/kotlin/tribixbite/cleverkeys/customization/XmlAttributeMapper.kt` | `XmlAttributeMapper` | Converts mappings to UK XML attributes |
| `src/main/kotlin/tribixbite/cleverkeys/customization/ShortSwipeCustomizationManager.kt` | Gesture storage | Stores/loads gesture mappings |
| `src/main/kotlin/tribixbite/cleverkeys/LayoutManagerActivity.kt` | Import/Export UI | SAF-based file operations |

## Architecture

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

## Implementation Details

### Key Design Decision

Gestures are injected as **standard UK XML attributes** (`nw`, `ne`, `sw`, `se`, etc.) rather than a proprietary format. This maintains:
- 100% backward compatibility with original UK layouts
- Exported profiles work in any UK-compatible keyboard
- No format fragmentation

### XML Attribute Mapping

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
        ActionType.TEXT -> "'${mapping.actionValue.replace("'", "'\"")}'"
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

### Parsed Key Attributes

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

### Compatibility

| Scenario | Result |
|----------|--------|
| Import standard UK layout | Works (no gestures) |
| Import CleverKeys exported profile | Works (gestures included) |
| Export to UK format | Works (gestures baked into standard attributes) |
| Import in original UK keyboard | Works (gestures visible as sub-labels) |

## Security

- **File Access**: Uses Android Storage Access Framework (SAF) - no broad storage permissions
- **Validation**: XML parsed and validated before application
- **No Network**: All operations are local
