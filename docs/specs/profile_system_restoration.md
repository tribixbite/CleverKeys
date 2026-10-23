# Profile System

> **Status (2026-10-23):** as built; direction table, `toXmlValue` and root attributes
> re-transcribed from `KeyboardData.kt:380-392`, `customization/XmlAttributeMapper.kt:22-52`
> and `activities/LayoutManagerActivity.kt`.

## Overview

The Profile System enables importing and exporting keyboard layouts as XML files with embedded short swipe customizations. Layouts are exported with gestures baked into standard Unexpected Keyboard XML attributes for full backward compatibility.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/customization/XmlLayoutExporter.kt` | `XmlLayoutExporter` | Parses layout XML, injects short swipe mappings |
| `src/main/kotlin/tribixbite/cleverkeys/customization/XmlAttributeMapper.kt` | `XmlAttributeMapper` | Converts mappings to UK XML attributes |
| `src/main/kotlin/tribixbite/cleverkeys/customization/ShortSwipeCustomizationManager.kt` | Gesture storage | Stores/loads gesture mappings |
| `src/main/kotlin/tribixbite/cleverkeys/activities/LayoutManagerActivity.kt` | Import/Export UI | SAF-based file operations |

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
| NE | `ne` (key2) |
| SW | `sw` (key3) |
| SE | `se` (key4) |
| W | `w` (key5) |
| E | `e` (key6) |
| N | `n` (key7) |
| S | `s` (key8) |

(`KeyboardData.Key.parse`, `KeyboardData.kt:382-389`: `get_key_attr(parser, "key5", "w")`, `("key6", "e")`, `("key7", "n")`, `("key8", "s")` — an earlier revision of this table had W/E swapped.)

### XmlAttributeMapper.toXmlValue()

```kotlin
// customization/XmlAttributeMapper.kt:22 — all six ActionTypes
fun toXmlValue(mapping: ShortSwipeMapping): String = when (mapping.actionType) {
    ActionType.TEXT      -> "'${mapping.actionValue.replace("'", "\\'")}'"        // ' escaped as \' (QUOTED_PAT)
    ActionType.COMMAND   -> if (KeyValue.getKeyByName(name) != null) name           // registry names ARE key names
                            else mapLegacyCommandToKeyword(mapping.getCommand()) ?: name
    ActionType.KEY_EVENT -> "keyevent:${mapping.actionValue}"
    ActionType.INTENT    -> "intent:${quotedPayload(mapping.actionValue)}"
    ActionType.TEMPLATE  -> "template:${quotedPayload(mapping.actionValue)}"
    ActionType.TIMESTAMP -> "timestamp:${quotedPayload(mapping.actionValue)}"
}
// quotedPayload: escape backslashes, then quotes: '…' with \\ and \'
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

Root `<keyboard>` attributes carried by a profile (parsed into `KeyboardData.declared_language`
/ `declared_theme`, `KeyboardData.kt:569-571`): `language` (per-layout language binding, GH
#186/#61) and `theme` (per-layout theme binding, `e237fa95`, 2026-10-15). `XmlLayoutExporter.
injectMappings(xmlContent, mappings)` rewrites only key attributes in the XML text, so both
root attributes round-trip unchanged; the Layout Manager editor exposes them as entry fields
and refuses to save an invalid value (`LayoutManagerActivity.kt:870-873`). Details:
`layout-system.md` "Language binding" / "Per-layout theme binding".

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
