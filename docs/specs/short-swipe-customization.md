# Short Swipe Customization Feature Specification

**Status**: Complete ✅
**Branch**: `main` (merged)
**Created**: 2025-12-05
**Updated**: 2025-12-23

## Overview

Allow users to fully customize short swipe gestures for every key on the keyboard through a dedicated settings UI.

## User Flow

```
Settings -> Short Swipe Customization
    |
    v
+------------------------------------------+
|  [Interactive Keyboard Preview]          |
|                                          |
|   q  w  e  r  t  y  u  i  o  p           |
|   a  s  d  f  g  h  j  k  l              |
|   z  x  c  v  b  n  m                    |
|                                          |
|  +------------------------------------+  |
|  |   Press a key to customize         |  |
|  +------------------------------------+  |
+------------------------------------------+
    |
    | (user taps 'e' key)
    v
+------------------------------------------+
|         Key Customization Modal          |
|                                          |
|              [NW] [N] [NE]               |
|               \   |   /                  |
|          [W] - [E] - [E]                 |
|               /   |   \                  |
|              [SW] [S] [SE]               |
|                                          |
|  Tap a direction to edit binding         |
|  [Reset Key] [Close]                     |
+------------------------------------------+
    |
    | (user taps NE position)
    v
+------------------------------------------+
|         Direction Editor                 |
|                                          |
|  Direction: Northeast (NE)               |
|                                          |
|  Display Text: [____] (max 4 chars)      |
|                                          |
|  Action Type: [Text v]                   |
|    - Text Input                          |
|    - Command                             |
|    - Key Event                           |
|                                          |
|  Action Value: [________________]        |
|  (up to 100 characters for text)         |
|                                          |
|  [Delete] [Cancel] [Save]                |
+------------------------------------------+
```

## Data Model

```kotlin
// SwipeDirection.kt
enum class SwipeDirection {
    N,   // North (up)
    NE,  // Northeast
    E,   // East (right)
    SE,  // Southeast
    S,   // South (down)
    SW,  // Southwest
    W,   // West (left)
    NW   // Northwest
}

// ActionType.kt
enum class ActionType {
    TEXT,     // Insert text string
    COMMAND,  // Execute editing command
    KEY_EVENT // Send key event
}

// ShortSwipeMapping.kt
data class ShortSwipeMapping(
    val keyCode: String,           // Key identifier
    val direction: SwipeDirection,
    val displayText: String,       // Max 4 chars for display (icon or text)
    val actionType: ActionType,
    val actionValue: String,       // Text or command name
    val useKeyFont: Boolean = false // Use special_font.ttf for icons
)
```

## Available Commands

Commands are organized in 18 categories with 200+ total commands. See `CommandRegistry.kt` for the complete list.

### Core Categories

| Category | Description | Example Commands |
|----------|-------------|------------------|
| CLIPBOARD | Clipboard operations | copy, paste, cut, paste_plain |
| EDITING | Edit operations | undo, redo, select_all |
| CURSOR | Cursor movement | cursor_left, cursor_right, home, end |
| NAVIGATION | Document navigation | page_up, page_down, doc_home, doc_end |
| SELECTION | Selection operations | select_all, selection_mode |
| DELETE | Delete operations | delete_word, forward_delete_word |
| MODIFIERS | Modifier keys | shift, ctrl, alt, meta, fn |
| FUNCTION_KEYS | Function keys | f1-f12 |
| SPECIAL_KEYS | Special keyboard keys | escape, tab, insert, print_screen |
| EVENTS | Keyboard events | config, change_method, action, caps_lock |

### Character & Symbol Categories

| Category | Description | Example Commands |
|----------|-------------|------------------|
| SPACES | Space & formatting | nbsp (non-breaking space), zwj, zwnj |
| DIACRITICS | Combining diacritics | combining_grave, combining_acute, etc. |
| DIACRITICS_SLAVONIC | Slavonic combining marks | combining_titlo, combining_palatalization, etc. |
| DIACRITICS_ARABIC | Arabic combining marks | arabic_fatha, arabic_kasra, arabic_sukun, etc. |
| HEBREW | Hebrew niqqud marks | hebrew_dagesh, hebrew_qamats, hebrew_tsere, etc. |
| TEXT | Text input | Various character keys |

### Media & System Categories (New 2025-12-21)

| Category | Description | Example Commands |
|----------|-------------|------------------|
| MEDIA | Media playback controls | media_play_pause, media_next, media_previous, volume_up, volume_down, volume_mute |
| SYSTEM | System/app launcher keys | search, calculator, calendar, contacts, explorer, notification, brightness_up, brightness_down, zoom_in, zoom_out |

### Most Used Commands

| Command | Description |
|---------|-------------|
| copy | Copy selected text |
| paste | Paste from clipboard |
| cut | Cut selected text |
| select_all | Select all text |
| undo | Undo last action |
| redo | Redo last action |
| cursor_left | Move cursor left |
| cursor_right | Move cursor right |
| home | Move to line start |
| end | Move to line end |
| doc_home | Move to document start (Ctrl+Home) |
| doc_end | Move to document end (Ctrl+End) |
| page_up | Scroll/move page up |
| page_down | Scroll/move page down |
| delete_word | Delete word before cursor |
| selection_mode | Toggle selection mode |
| change_method | Switch input method |
| voice_typing | Activate voice input |
| media_play_pause | Toggle media playback |
| volume_up | Increase volume |
| volume_down | Decrease volume |

## Storage Format

File: `short_swipe_customizations.json`

```json
{
  "version": 1,
  "mappings": {
    "a": {
      "N": { "displayText": "@", "actionType": "TEXT", "actionValue": "@" },
      "NE": { "displayText": "sel", "actionType": "COMMAND", "actionValue": "SELECT_ALL" }
    },
    "e": {
      "NW": { "displayText": "!", "actionType": "TEXT", "actionValue": "!" }
    }
  }
}
```

## Integration Points

1. **Pointers.kt** - `handleShortGesture()` checks custom mappings first
2. **KeyEventHandler.kt** - Executes custom commands
3. **KeyboardView.kt** - Renders custom sub-labels (future enhancement)
4. **BackupRestoreManager.kt** - Export/import custom mappings
5. **XmlAttributeMapper.kt** - Converts ShortSwipeMapping to XML attribute values for layout export
6. **KeyValue.kt** - Source of truth for all key definitions (300+ keys, 12 kinds)

## File Structure

```
src/main/kotlin/tribixbite/cleverkeys/
├── customization/
│   ├── SwipeDirection.kt
│   ├── ActionType.kt
│   ├── ShortSwipeMapping.kt
│   ├── ShortSwipeCustomizationManager.kt
│   ├── CustomShortSwipeExecutor.kt    # Executes commands via InputConnection
│   ├── CommandRegistry.kt             # 200+ searchable commands by category
│   ├── CommandPaletteDialog.kt        # UI for command selection
│   └── XmlAttributeMapper.kt          # JSON→XML export support
├── KeyValue.kt                        # Key definitions (source of truth)
└── ui/customization/
    ├── ShortSwipeCustomizationActivity.kt
    ├── InteractiveKeyboardPreview.kt
    ├── KeyCustomizationModal.kt
    └── DirectionEditorDialog.kt
```

## Implementation Phases

### Phase 1: Data Layer ✅
- [x] Data models (SwipeDirection.kt, ActionType.kt, ShortSwipeMapping.kt)
- [x] ShortSwipeCustomizationManager with JSON persistence
- [x] Backup/restore integration
- [x] MappingSelection data class for separate label/action control

### Phase 2: Integration ✅
- [x] Pointers.kt integration
- [x] CustomShortSwipeExecutor supporting 200+ commands
- [x] KeyEventHandler integration
- [x] CommandRegistry with searchable keywords

### Phase 3: UI ✅
- [x] ShortSwipeCustomizationActivity (v4 with actual keyboard)
- [x] KeyMagnifierView showing existing layout + custom mappings
- [x] KeyCustomizationDialog with 8-direction selector
- [x] CommandPaletteDialog with search filter
- [x] LabelConfirmationDialog for custom display labels
- [x] DirectionTouchOverlay (Compose 3x3 grid) for reliable touch detection
- [x] Settings navigation

### Phase 4: Polish ✅
- [x] Reset to defaults (delete individual mappings)
- [x] Edge case handling (command name validation)
- [x] Touch event handling fix (AndroidView interop)
- [x] Colored direction zones with labels (8 distinct colors + direction labels)
- [x] Shift+custom swipe support (custom mappings work even with shift active)
- [ ] Manual device testing (ongoing)

### Phase 5: Modifier Key Support ✅ (2025-12-07)
- [x] Custom short swipe mappings bypass modifier blocking check
- [x] Built-in sublabel gestures still blocked when shift/fn/ctrl active
- [x] Restructured Pointers.kt gesture handling logic
- [x] Renamed `shouldBlockGesture` → `shouldBlockBuiltInGesture` for clarity

### Phase 6: UI Enhancement ✅ (2025-12-07)
- [x] DirectionZone colored backgrounds for visual identification:
  - NW: Red (#FF6B6B), N: Teal (#4ECDC4), NE: Yellow (#FFE66D)
  - W: Mint (#95E1D3), E: Coral (#F38181)
  - SW: Purple (#AA96DA), S: Cyan (#72D4E8), SE: Pink (#FCBAD3)
- [x] Direction labels (NW, N, NE, W, E, SW, S, SE) displayed in each zone
- [x] Center zone transparent (no action)

### Phase 7: Bug Fixes ✅ (2025-12-07)
- [x] Fixed LazyColumn crash when scrolling command palette
  - Root cause: Duplicate command names in CommandRegistry.kt
  - Removed 6 duplicates: compose, compose_cancel, doc_home, doc_end, zwj, zwnj
  - Commands reduced from 143 to 137 (all unique)
  - Error: `IllegalArgumentException: Key was already used`

### Phase 8: Command Expansion ✅ (2025-12-21)
- [x] Audited KeyValue.kt vs CommandRegistry.kt to identify 51 missing commands
- [x] Added 32 new Android KeyEvent codes to KeyValue.kt:
  - Media keys: play_pause, play, pause, stop, next, previous, rewind, fast_forward, record
  - Volume keys: volume_up, volume_down, volume_mute
  - Brightness keys: brightness_up, brightness_down
  - Zoom keys: zoom_in, zoom_out
  - System/app keys: search, calculator, calendar, contacts, explorer, notification
- [x] Added 75+ new commands to CommandRegistry.kt:
  - 5 new categories: MEDIA, SYSTEM, DIACRITICS_SLAVONIC, DIACRITICS_ARABIC, HEBREW
  - 10 Slavonic combining marks (titlo, palatalization, pokrytie, etc.)
  - 14 Arabic vowel marks (fatha, kasra, damma, sukun, shadda, etc.)
  - 20 Hebrew niqqud marks (dagesh, qamats, patah, tsere, etc.)
  - Media/system/navigation commands
- [x] Updated CustomShortSwipeExecutor with fallback handler for character-based commands
- [x] Updated XmlAttributeMapper to support CommandRegistry names directly for XML export
- [x] Updated CommandPaletteDialog with icons for new categories
- [x] Total commands now: 200+ (up from 137)

### Phase 9: Icon Font Support ✅ (2025-12-22)
- [x] Added `useKeyFont` field to ShortSwipeMapping data model
- [x] Updated DirectionMapping storage to v2 schema with useKeyFont field
- [x] Added CommandRegistry.getDisplayInfo() to extract icon + font flag from KeyValue
- [x] Updated KeyMagnifierView to render custom mappings with special_font.ttf
- [x] Updated Keyboard2View.drawCustomSubLabel() to use theme's sublabel_paint
- [x] Updated CommandPaletteDialog to auto-detect icon mode:
  - If command has KeyValue with FLAG_KEY_FONT, use icon mode by default
  - If user customizes label text, switch to text mode
- [x] Custom mappings now match font size/style of layout's default subkeys

### Phase 10: Color & Preview Fixes ✅ (2025-12-23)
- [x] Fixed custom sublabel color: use `subLabelColor` instead of `activatedColor`
  - Keyboard2View.drawCustomMappings() now uses theme's subLabelColor
  - KeyMagnifierView.drawSubLabelForDirection() uses consistent subLabelColor
- [x] Improved icon preview in LabelConfirmationDialog:
  - PUA characters (Private Use Area) can't render in Compose UI
  - Now shows readable description like [Tab], [Home] instead of boxes
  - Clear UX: "Leave blank to use default icon. Type text for a custom label."
- [x] Added isIconMode and iconPreviewText parameters to LabelConfirmationDialog

## Performance Requirements

- Custom mapping lookup: < 1ms
- UI response time: < 16ms (60fps)
- Storage load time: < 100ms

## Testing Strategy

1. Unit tests for data layer
2. Integration tests for gesture handling
3. UI tests for customization flow
4. Manual device testing
