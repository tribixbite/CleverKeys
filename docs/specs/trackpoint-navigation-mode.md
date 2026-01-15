# TrackPoint Navigation Mode Specification

## Feature Overview
**Feature Name**: TrackPoint Navigation Mode
**Priority**: P1
**Status**: Complete
**Target Version**: v1.2.4

### Summary
A joystick-style cursor control mode activated by holding navigation keys (arrow keys), enabling proportional multi-directional cursor movement.

### Motivation
Traditional arrow key navigation requires repeated taps to move multiple positions. TrackPoint mode allows fluid, continuous cursor movement in any direction by holding a nav key and moving the finger, similar to a ThinkPad TrackPoint.

## Requirements

### Functional Requirements
1. **FR-1**: Touch and hold arrow key (without initial movement) enters TrackPoint mode
2. **FR-2**: Finger movement in any direction moves cursor proportionally
3. **FR-3**: Diagonal movement supported (NE moves up+right simultaneously)
4. **FR-4**: Speed scales with distance from activation center
5. **FR-5**: Distinct haptic feedback on mode activation
6. **FR-6**: Short swipe on nav key still performs single cursor movement
7. **FR-7**: Nav keys excluded from short gesture path collection

### Non-Functional Requirements
1. **NFR-1**: Performance - Smooth cursor movement with <16ms response
2. **NFR-2**: Usability - Clear haptic distinction from normal key press
3. **NFR-3**: Reliability - No interference with short swipe gestures

### User Stories
- **As a** developer, **I want** to navigate code quickly, **so that** I can edit precisely without lifting my finger
- **As a** user, **I want** continuous cursor movement, **so that** I can position cursor efficiently in long documents

## Technical Design

### Architecture
```
User Input (arrow key)
       |
       v
+------------------+
| onTouchDown()    | -- Records initial touch position
+------------------+
       |
       v (no initial movement + hold)
+------------------+
| TrackPoint Mode  | -- FLAG_P_TRACKPOINT_MODE activated
| Activation       | -- CLOCK_TICK haptic feedback
+------------------+
       |
       v
+------------------+
| Continuous       | -- Repeating handler tracks finger position
| Position Track   | -- Calculates direction and distance
+------------------+
       |
       v
+------------------+
| Cursor Movement  | -- Sends arrow key events proportionally
| Handler          | -- Supports all 8 directions
+------------------+
```

### Component Breakdown
1. **Pointers.kt**: Handles touch input, mode activation, and cursor movement
2. **VibratorCompat.kt**: Provides distinct haptic feedback (CLOCK_TICK pattern)
3. **Config.kt**: TrackPoint haptic toggle setting

### Data Structures
```kotlin
// State flag for mode tracking
private const val FLAG_P_TRACKPOINT_MODE = 64

// Timer identifier
private val trackpointWhat = Any()

// Direction constants
enum class TrackPointDirection {
    NORTH, NORTHEAST, EAST, SOUTHEAST,
    SOUTH, SOUTHWEST, WEST, NORTHWEST
}
```

### API/Interface Design
```kotlin
// TrackPoint handling in Pointers.kt
private fun handleTrackPointRepeat(ptr: Pointer) {
    val dx = ptr.currentX - ptr.activationX
    val dy = ptr.currentY - ptr.activationY

    // Calculate direction from delta
    val direction = getTrackPointDirection(dx, dy)

    // Send cursor movement based on direction
    when (direction) {
        NORTH -> sendArrowKey(KeyEvent.KEYCODE_DPAD_UP)
        NORTHEAST -> {
            sendArrowKey(KeyEvent.KEYCODE_DPAD_UP)
            sendArrowKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        }
        // ... other directions
    }
}

// Haptic feedback on activation
private fun triggerTrackPointHaptic() {
    VibratorCompat.vibrate(HapticEvent.TRACKPOINT_ACTIVATE)
}
```

### State Management
- `FLAG_P_TRACKPOINT_MODE` flag tracks active state
- `activationX/Y` stores initial touch point
- Movement tolerance: 30px before short swipe detection (increased from 15px for nav keys)
- Timer-based repeat with speed based on finger distance

## Implementation Plan

### Phase 1: Core TrackPoint Mode
**Status**: Complete
**Deliverables**:
- [x] Hold detection on arrow keys
- [x] 8-direction cursor movement
- [x] Speed scaling based on distance
- [x] Diagonal movement support

### Phase 2: Integration & Polish
**Status**: Complete
**Deliverables**:
- [x] Distinct haptic feedback (CLOCK_TICK)
- [x] Exclusion from short gesture collection
- [x] Increased movement tolerance for nav keys
- [x] Per-event haptic settings toggle

## Testing Strategy

### Unit Tests
- Test case 1: Short swipe triggers single cursor movement
- Test case 2: Hold activates TrackPoint mode
- Test case 3: Distance scaling affects movement speed correctly

### Integration Tests
- Test case 1: Cursor moves correctly in text editors
- Test case 2: Diagonal movement produces expected results
- Test case 3: Mode doesn't interfere with other gestures

### UI/UX Tests
- Test case 1: Haptic feedback is distinctly recognizable
- Test case 2: TrackPoint haptic toggle works correctly

## Dependencies

### Internal Dependencies
- `Pointers.kt` touch handling system
- `VibratorCompat.kt` haptic feedback system
- `Config.kt` haptic settings storage

### External Dependencies
- Android KeyEvent for cursor key codes
- Android Vibrator API for haptics

### Breaking Changes
- [ ] This feature introduces breaking changes

## Configuration

### Haptic Settings
The TrackPoint activation haptic can be toggled in Settings > Accessibility > Haptic Feedback:
- **TrackPoint Mode**: Enable/disable CLOCK_TICK pattern on activation
- Default: Enabled

### Related Settings
- Short Gesture Min Distance: Affects how quickly TrackPoint activates
- Short Gesture Max Distance: Must be less than TrackPoint activation threshold

## Error Handling
- No navigation target: Cursor movement commands sent but have no effect
- Haptic unavailable: Graceful degradation, mode still functions
- Rapid re-activation: Debounced to prevent haptic spam

## Success Metrics
- Metric 1: Users can position cursor in long documents 3x faster than tap-tap-tap
- Metric 2: Diagonal movement feels natural and responsive
- Acceptance criteria: All test cases pass, user feedback positive

---

**Created**: 2026-01-14
**Last Updated**: 2026-01-14
**Owner**: CleverKeys Development
**Reviewers**: tribixbite
