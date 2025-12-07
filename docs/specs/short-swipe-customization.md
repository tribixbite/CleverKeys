# Short Swipe Customization Feature Specification

**Status**: Complete ✅
**Branch**: `main` (merged)
**Created**: 2025-12-05
**Updated**: 2025-12-07

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
    val displayText: String,       // Max 4 chars for display
    val actionType: ActionType,
    val actionValue: String        // Text or command name
)
```

## Available Commands

| Command | Description |
|---------|-------------|
| COPY | Copy selected text |
| PASTE | Paste from clipboard |
| CUT | Cut selected text |
| SELECT_ALL | Select all text |
| UNDO | Undo last action |
| REDO | Redo last action |
| CURSOR_LEFT | Move cursor left |
| CURSOR_RIGHT | Move cursor right |
| CURSOR_UP | Move cursor up |
| CURSOR_DOWN | Move cursor down |
| CURSOR_HOME | Move to line start |
| CURSOR_END | Move to line end |
| CURSOR_DOC_START | Move to document start |
| CURSOR_DOC_END | Move to document end |
| DELETE_WORD | Delete word before cursor |
| WORD_LEFT | Move cursor word left |
| WORD_RIGHT | Move cursor word right |
| SWITCH_IME | Switch input method |
| VOICE_INPUT | Activate voice input |

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

## File Structure

```
src/main/kotlin/tribixbite/cleverkeys/
├── customization/
│   ├── SwipeDirection.kt
│   ├── ActionType.kt
│   ├── ShortSwipeMapping.kt
│   ├── ShortSwipeCustomizationManager.kt
│   └── CustomShortSwipeExecutor.kt
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
- [x] CustomShortSwipeExecutor supporting 143+ commands
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

## Performance Requirements

- Custom mapping lookup: < 1ms
- UI response time: < 16ms (60fps)
- Storage load time: < 100ms

## Testing Strategy

1. Unit tests for data layer
2. Integration tests for gesture handling
3. UI tests for customization flow
4. Manual device testing
