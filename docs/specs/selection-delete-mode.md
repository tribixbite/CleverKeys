# Selection-Delete Mode Specification

## Feature Overview
**Feature Name**: Selection-Delete Mode
**Priority**: P1
**Status**: Complete
**Target Version**: v1.2.4

### Summary
A gesture mode that enables text selection via swipe-hold on the backspace key, with automatic deletion on release.

### Motivation
Traditional text selection on mobile keyboards is cumbersome, requiring switching to cursor mode or long-pressing. This feature enables rapid text selection and deletion with a single fluid gesture on the backspace key.

## Requirements

### Functional Requirements
1. **FR-1**: Short swipe + hold on backspace key activates selection-delete mode
2. **FR-2**: Horizontal finger movement selects characters (left/right)
3. **FR-3**: Vertical finger movement selects lines (up/down)
4. **FR-4**: Releasing finger deletes all selected text
5. **FR-5**: Short swipe + immediate release performs normal subkey action (delete_last_word)
6. **FR-6**: Regular hold without movement triggers normal key repeat
7. **FR-7**: Bidirectional movement - direction changes dynamically with finger position
8. **FR-8**: Diagonal movement supported (X and Y axes fire independently)

### Non-Functional Requirements
1. **NFR-1**: Performance - Selection speed proportional to finger distance from activation center
2. **NFR-2**: Usability - Configurable vertical threshold and speed via Settings
3. **NFR-3**: Reliability - Must not interfere with normal backspace operations

### User Stories
- **As a** power user, **I want** to select and delete multiple words quickly, **so that** I can correct mistakes efficiently
- **As a** user, **I want** to select entire lines, **so that** I can delete paragraphs without switching modes

## Technical Design

### Architecture
```
User Input (backspace key)
       |
       v
+------------------+
| onTouchDown()    | -- Defers backspace handling for gesture detection
+------------------+
       |
       v
+------------------+
| Short Swipe      | -- Detects initial swipe direction
| Detection        |
+------------------+
       |
       v (if hold detected)
+------------------+
| FLAG_P_SELECTION | -- Activates selection-delete mode
| _DELETE_MODE     |
+------------------+
       |
       v
+------------------+
| handleSelection  | -- Repeating handler for continuous selection
| DeleteRepeat()   |
+------------------+
       |
       v (on release)
+------------------+
| Delete selected  | -- Sends DEL key to remove selection
| text             |
+------------------+
```

### Component Breakdown
1. **Pointers.kt**: Handles touch input, gesture detection, and selection state
2. **Config.kt**: Stores vertical threshold and speed settings
3. **SettingsActivity.kt**: UI for configuring threshold and speed

### Data Structures
```kotlin
// State flag for mode tracking
private const val FLAG_P_SELECTION_DELETE_MODE = 128

// Timer identifier
private val selectionDeleteWhat = Any()

// Configuration settings
selection_delete_vertical_threshold: Int  // 20-80%, default 40%
selection_delete_vertical_speed: Float    // 0.1x-1.0x, default 0.4x
```

### API/Interface Design
```kotlin
// Selection handling in Pointers.kt
private fun handleSelectionDeleteRepeat(ptr: Pointer) {
    // Track X/Y axes independently
    val dx = ptr.currentX - ptr.selectionCenterX
    val dy = ptr.currentY - ptr.selectionCenterY

    // Horizontal: Shift+Left or Shift+Right
    // Vertical: Shift+Up or Shift+Down (with threshold)
}

// Shift state management
private fun makeInternalModifier(mod: Modifier): KeyValue
private fun with_extra_mod(value: KeyValue, extra: Modifier): KeyValue
```

### State Management
- `FLAG_P_SELECTION_DELETE_MODE` flag tracks active state
- `selectionCenterX/Y` stores activation point for distance calculation
- Timer-based repeat with speed scaling based on finger distance
- Shift modifier held during selection via `with_extra_mod()`

## Implementation Plan

### Phase 1: Core Selection Mode
**Status**: Complete
**Deliverables**:
- [x] Short swipe + hold detection on backspace
- [x] Horizontal selection (character-by-character)
- [x] Release-to-delete functionality
- [x] Bidirectional movement support

### Phase 2: Vertical Selection & Configuration
**Status**: Complete
**Deliverables**:
- [x] Vertical line selection (up/down)
- [x] Configurable vertical threshold setting
- [x] Configurable vertical speed multiplier
- [x] Settings UI integration

## Testing Strategy

### Unit Tests
- Test case 1: Short swipe triggers subkey action, not selection mode
- Test case 2: Hold without movement triggers key repeat
- Test case 3: Selection mode activates after threshold time

### Integration Tests
- Test case 1: Text selection works across app boundaries
- Test case 2: Shift state properly maintained during selection
- Test case 3: Settings changes take effect immediately

### UI/UX Tests
- Test case 1: Vertical threshold slider responds correctly
- Test case 2: Speed setting affects selection rate proportionally

## Dependencies

### Internal Dependencies
- `Pointers.kt` touch handling system
- `Config.kt` settings storage
- `KeyValue` and `Modifier` from layout system

### External Dependencies
- Android InputConnection for selection manipulation

### Breaking Changes
- [ ] This feature introduces breaking changes

## Error Handling
- Invalid threshold values: Clamped to valid range (20-80%)
- Invalid speed values: Clamped to valid range (0.1-1.0)
- No text to select: Mode still activates, selection commands sent harmlessly

## Success Metrics
- Metric 1: Users can select and delete multiple words in <2 seconds
- Metric 2: Vertical selection triggers reliably within threshold
- Acceptance criteria: All test cases pass, no reported regressions

---

**Created**: 2026-01-14
**Last Updated**: 2026-01-14
**Owner**: CleverKeys Development
**Reviewers**: tribixbite
