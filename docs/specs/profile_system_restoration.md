# Profile System: Layout Import/Export with Short Swipe Integration

## Overview
**Feature Name**: Profile System (Unified Layout + Gesture Export)
**Status**: Planned (Not Yet Implemented)
**Priority**: P3 (Future Enhancement)

### Summary
The Profile System will enable importing and exporting keyboard layouts as XML files with embedded short swipe customizations. Users will be able to share complete keyboard configurations as portable "profiles" that include both layout structure and custom gesture mappings.

### Key Design Decision
The original design proposed a proprietary `<short_swipes>` XML extension. This was **rejected** in favor of injecting gestures as standard Unexpected Keyboard XML attributes (`nw`, `ne`, `sw`, `se`, etc.). This approach:
- Maintains 100% backward compatibility with original UK layouts
- Allows exported profiles to work in any UK-compatible keyboard
- Avoids format fragmentation

---

## Proposed Architecture

### Components (To Be Created)

| Component | File | Responsibility |
|-----------|------|----------------|
| **XmlLayoutExporter** | `XmlLayoutExporter.kt` | Parse layout XML, inject short swipe mappings into `<key>` tags |
| **XmlAttributeMapper** | `XmlAttributeMapper.kt` | Convert `ShortSwipeMapping` → UK XML attributes (e.g., `copy`, `keyevent:66`) |
| **ShortSwipeCustomizationManager** | `ShortSwipeCustomizationManager.kt` | Add `importFromMappings()` method |
| **LayoutManagerActivity** | `LayoutManagerActivity.kt` | Add Import/Export buttons using SAF |

### Data Flow

```
Export:
  ShortSwipeCustomizationManager.getAllMappings()
    → XmlAttributeMapper.toXmlAttribute(mapping)
    → XmlLayoutExporter.injectMappings(layoutXml, mappings)
    → SAF CreateDocument → File

Import:
  SAF OpenDocument → File
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
| Text insertion | `sw="my@email.com"` |
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

### XmlAttributeMapper Conversion Logic

```kotlin
fun toXmlAttribute(mapping: ShortSwipeMapping): String {
    return when (mapping.actionType) {
        ActionType.COMMAND -> mapCommandToLegacy(mapping.actionValue)
        ActionType.TEXT -> mapping.actionValue  // Direct text insertion
        ActionType.KEY_EVENT -> "keyevent:${mapping.actionValue}"
    }
}
```

---

## UI Implementation

### CustomLayoutEditorDialog (To Be Updated)

**Import XML Button**:
1. Opens SAF file picker (`OpenDocument` with `text/xml`)
2. Reads XML content
3. Validates with `KeyboardData.load_string_exn()`
4. Populates editor text field
5. Swipes defined via standard attributes work immediately

**Export XML Button**:
1. Validates current editor XML
2. Retrieves global mappings from `ShortSwipeCustomizationManager`
3. Calls `XmlLayoutExporter.injectMappings()` to embed gestures
4. Opens SAF create dialog (`CreateDocument`)
5. Writes enhanced XML to file

---

## Expected Usage

1. Open **Settings** → **Keyboard Layouts**
2. Select **Custom Layout** or edit existing
3. **Export XML**: Saves layout + all custom gesture overrides
4. **Import XML**: Loads layout; gestures from standard attributes work immediately

---

## Compatibility Matrix

| Scenario | Expected Result |
|----------|--------|
| Import standard UK layout | Works (no gestures) |
| Import CleverKeys exported profile | Works (gestures included) |
| Export to UK format | Works (gestures baked into standard attributes) |
| Import in original UK keyboard | Works (gestures visible as sub-labels) |

---

## Security Considerations

- **File Access**: Will use Android Storage Access Framework (SAF) - no broad storage permissions
- **Validation**: XML must be parsed and validated before application
- **No Network**: All operations are local

---

**Last Updated**: 2025-12-11
**Note**: Implementation todos moved to `memory/todo.md`
