# Profile System

> **Status (2026-10-30):** as built; direction table and root attributes re-transcribed from
> `KeyboardData.kt:380-392` and `activities/LayoutManagerActivity.kt`; "Export XML values" from
> `customization/XmlAttributeMapper.kt` (`layoutValue`) and `KeyValueParser.kt`.

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
    → XmlLayoutExporter.injectMappings(layoutXml, mappings)
        → XmlAttributeMapper.layoutValue(mapping) per mapped slot
        → LayoutSubkeyXml.setSubkey (text-level edit of that key's start tag)
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
| Text insertion | `sw="Hell:'Hello World'"` |
| Key event | `ne="⌨:keyevent:66"` (Enter) |
| Template | `n="{}:template:'Dear {cursor},'"` |
| Timestamp | `s="TS:timestamp:'yyyy-MM-dd HH:mm'"` |
| Intent | `w="Shar:intent:'{&quot;name&quot;:&quot;Share&quot;,…}'"` |

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

### Export XML values (2026-10-30)

One serializer, `XmlAttributeMapper.layoutValue(mapping)`, writes a short swipe into layout XML
for Export XML AND for the per-key dialog's / subkey popover's modified layout copies
(subkey-customization-current.md). Every value it writes reads back through the layout parser
(`KeyValue.getKeyByName`, as `KeyboardData.parse_key_attr` reads a subkey attribute) as the same
action — `XmlActionRoundTripTest` (pure) and `XmlLayoutExporterRoundTripTest` (mock, the whole file
through kxml2) pin that for every action type.

| ActionType | Written as | Read back as |
|------------|------------|--------------|
| COMMAND | the key name (`copy`); a legacy AvailableCommand spelling as the editing key it runs (`SELECT_ALL` → `selectAll`, `CommandRouting.legacyEditingName`) | that key |
| TEXT | `label:'text'` | a key typing the text (a Char key for one character) |
| KEY_EVENT | `label:keyevent:66` | the key event |
| TEMPLATE | `label:template:'…'` | the template key |
| TIMESTAMP | `label:timestamp:'pattern'` | the timestamp key |
| INTENT | `label:intent:'{json}'` | a string key `__intent__:{json}`, which `KeyEventHandler` STARTS (custom short swipe's `CustomShortSwipeExecutor.executeIntent`) instead of typing |

**Label.** The mapping's own label when a layout can carry it — not blank, no `:` (it ends the
label), no control or key-font (private-use) character, not starting with `loc ` (the parser's
localized-key marker). Otherwise: the text's first characters (at most 4 UTF-16 units, never half
a surrogate pair), the intent's name, `⌨`, `{}` or `📅`; else `…`.

**Escaping.** Inside the quotes a backslash escapes the next character: `\` is a backslash, `'`
a quote (`KeyValueParser`; before 2026-10-30 a plain quoted string accepted only `'`, so text
with a backslash had no spelling). XML escaping is the text-level edit's (`LayoutSubkeyXml`):
`&amp;` `&lt;` `&quot;` and line breaks / tabs as `&#10;` `&#13;` `&#9;`; every other character,
including those outside the BMP (emoji, `𝕨`), is written as itself.

**Not written** (`layoutValue` is null): a catalogue command that only the custom-swipe executor
knows by name (`paste_pinned_1`, `primaryLangToggle` …, which the parser would read as a key
typing its name), legacy cursor/IME commands whose executor code no layout key matches, empty
text, a non-numeric key event, and text XML 1.0 cannot carry (C0 controls other than tab and line
breaks, unpaired surrogates). Export XML leaves such a slot as the layout defines it and says so
(`layout_manager_export_not_written`: "a layout cannot hold their action … they still work on
this device"). A `removed` blank removes the slot's attributes (both `nw` and `key1` spellings).

**Before 2026-10-30** Export XML wrote a bare `'text'`, `keyevent:66`, `intent:'…'`,
`template:'…'` and `timestamp:'…'`. The parser reads an action only after a label, so those keys
TYPED their source: `'hello'` with its quotes; `keyevent:66` typed "66" under the label
"keyevent"; the prefixed forms typed their payload under the label `intent` / `template` /
`timestamp`. The serializer also went through a DOM round trip whose serializer wrote characters
outside the BMP as numeric references, which kxml2 (Android's parser) reads back truncated to 16
bits (`😀` → `함`). **Old files import exactly as before** — the parser is unchanged for every input
it accepted (`XmlActionRoundTripTest.oldExportFormsStillImportAsBefore`): such a subkey still types
its quoted source. Re-export the layout from Layout Manager to get the actions back. The only
parser change (a backslash escape inside quotes) affects only definitions that were a syntax error
before, which `getKeyByName` turned into a key typing the whole definition.

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
injectMappings(xmlContent, mappings)` rewrites only the start tags of mapped keys in the XML
text, so both root attributes (and the `<theme>` element, comments, formatting) round-trip
byte for byte; the Layout Manager editor exposes them as entry fields
and refuses to save an invalid value (`LayoutManagerActivity.kt:870-873`). Details:
`layout-system.md` "Language binding" / "Per-layout theme binding".

### Export fixes (2026-10-30)

`XmlLayoutExporter.injectMappings` now (1) removes a direction's numbered synonym before writing
its short name — a key with `key1="…"` that received `nw="…"` carried both, which the parser
rejects ("'key1' and 'nw' are synonyms") — and (2) matches mappings by RUNTIME key code
(`ShortSwipeKeyCodes`), so a mapping on Enter (stored under Enter's glyph) is injected into
`key0="enter"`. Modified copies of built-in layouts (subkey-customization-current.md) are custom
layouts and export like any other.

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
