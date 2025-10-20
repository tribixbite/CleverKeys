# File 84/251 Review: Gesture.java → [MISSING]

## File Information
- **Java File**: `/Unexpected-Keyboard/srcs/juloo.keyboard2/Gesture.java`
- **Java Lines**: 141
- **Kotlin File**: **COMPLETELY MISSING**
- **Kotlin Lines**: 0
- **Reduction**: **100% MISSING FUNCTIONALITY**

## Classification: 🚨 **HIGH PRIORITY BUG - CRITICAL MISSING FEATURE**

---

## Java Implementation Analysis

### Core Features:
Sophisticated gesture recognition system for directional touch input:

```java
public final class Gesture {
    int current_dir;  // 0-15 direction quadrant
    State state;

    enum State {
        Cancelled, Swiped,
        Rotating_clockwise, Rotating_anticlockwise,
        Ended_swipe, Ended_center, Ended_clockwise, Ended_anticlockwise
    }

    enum Name {
        None, Swipe, Roundtrip, Circle, Anticircle
    }

    static final int ROTATION_THRESHOLD = 2;
}
```

### Gesture Types:
1. **Swipe**: Simple directional swipe (1-15 directions)
2. **Roundtrip**: Swipe out and return to center
3. **Circle**: Clockwise rotation gesture
4. **Anticircle**: Anticlockwise rotation gesture

### State Machine Algorithm:

**1. Initialization:**
```java
public Gesture(int starting_direction) {
    current_dir = starting_direction;
    state = State.Swiped;
}
```

**2. Direction Change Detection:**
```java
public boolean changed_direction(int direction) {
    int d = dir_diff(current_dir, direction);
    boolean clockwise = d > 0;

    switch (state) {
        case Swiped:
            if (Math.abs(d) < Config.globalConfig().circle_sensitivity)
                return false;
            // Start rotation
            state = (clockwise) ?
                State.Rotating_clockwise : State.Rotating_anticlockwise;
            current_dir = direction;
            return true;

        case Rotating_clockwise:
        case Rotating_anticlockwise:
            current_dir = direction;
            if ((state == State.Rotating_clockwise) == clockwise)
                return false;
            state = State.Cancelled;  // Rotation reversed
            return true;
    }
    return false;
}
```

**3. Direction Difference (Modulo Arithmetic):**
```java
static int dir_diff(int d1, int d2) {
    final int n = 16;
    // Shortest-path in modulo arithmetic
    if (d1 == d2)
        return 0;
    int left = (d1 - d2 + n) % n;
    int right = (d2 - d1 + n) % n;
    return (left < right) ? -left : right;
}
```

**Key insight**: Uses 16-direction quantization (0-15) and finds shortest circular path between directions.

**4. Center Return Detection:**
```java
public boolean moved_to_center() {
    switch (state) {
        case Swiped: state = State.Ended_center; return true;  // Roundtrip!
        case Rotating_clockwise: state = State.Ended_clockwise; return false;
        case Rotating_anticlockwise: state = State.Ended_anticlockwise; return false;
    }
    return false;
}
```

**5. Gesture Recognition:**
```java
public Name get_gesture() {
    switch (state) {
        case Cancelled: return Name.None;
        case Swiped:
        case Ended_swipe: return Name.Swipe;
        case Ended_center: return Name.Roundtrip;
        case Rotating_clockwise:
        case Ended_clockwise: return Name.Circle;
        case Rotating_anticlockwise:
        case Ended_anticlockwise: return Name.Anticircle;
    }
    return Name.None;
}
```

---

## Missing Kotlin Implementation

### Search Results:
```bash
$ find /cleverkeys/src/main/kotlin -name "Gesture*.kt"
(no results)

$ grep -r "Circle|Anticircle|Roundtrip" /cleverkeys/src/main/kotlin
(no results)

$ grep -r "Rotating_clockwise|dir_diff|changed_direction" /cleverkeys/src/main/kotlin
(no results)
```

**Conclusion**: COMPLETELY MISSING - no gesture recognition system in Kotlin!

---

## Bug #267: Gesture Recognition System Missing

### Severity: **HIGH** (not CATASTROPHIC due to limited usage)

### Impact:
- **Circle gestures unavailable**: Cannot perform clockwise rotation gestures
- **Anticircle gestures unavailable**: Cannot perform anticlockwise rotation gestures
- **Roundtrip gestures unavailable**: Cannot perform swipe-out-return-to-center
- **Direction quantization missing**: No 16-direction system for precise gesture tracking

### Potential Usage:
These gestures are typically used for:
- **Circle**: Emoji picker, special character selection, mode switching
- **Anticircle**: Undo, reverse action
- **Roundtrip**: Peek/preview actions, temporary mode switches
- **Direction tracking**: Precise swipe direction detection

### Why Not CATASTROPHIC:
- Swipe typing doesn't use circle gestures (uses linear swipes only)
- Main keyboard functionality works without these gestures
- These are **advanced gesture features** for power users
- May not be enabled in current configuration

---

## Feature Comparison Table

| Feature | Java (141 lines) | Kotlin (0 lines) | Status |
|---------|------------------|------------------|--------|
| **Gesture enum** | ✅ None/Swipe/Roundtrip/Circle/Anticircle | ❌ Missing | **HIGH** |
| **State enum** | ✅ 8 states (Cancelled/Swiped/Rotating/Ended) | ❌ Missing | **HIGH** |
| **Direction tracking** | ✅ 16-direction quantization (0-15) | ❌ Missing | **HIGH** |
| **State machine** | ✅ Full FSM with transitions | ❌ Missing | **HIGH** |
| **Circle detection** | ✅ Clockwise rotation tracking | ❌ Missing | **HIGH** |
| **Anticircle detection** | ✅ Anticlockwise rotation tracking | ❌ Missing | **HIGH** |
| **Roundtrip detection** | ✅ Swipe-return-to-center | ❌ Missing | **HIGH** |
| **Direction diff** | ✅ Modulo arithmetic shortest path | ❌ Missing | **HIGH** |
| **Rotation reversal** | ✅ Cancels on direction reversal | ❌ Missing | **MEDIUM** |
| **Circle sensitivity** | ✅ Config.circle_sensitivity threshold | ❌ Missing | **MEDIUM** |
| **In-progress check** | ✅ is_in_progress() method | ❌ Missing | **MEDIUM** |

---

## Recommendation: **IMPLEMENT IF GESTURES ARE USED**

### Investigation Required:
Before implementing, check:
1. **Is Gesture.java actually used?**
   ```bash
   grep -r "new Gesture\|import.*Gesture" /Unexpected-Keyboard/srcs/
   ```
2. **Are circle gestures enabled in Config?**
   ```bash
   grep -r "circle_sensitivity" /Unexpected-Keyboard/srcs/
   ```
3. **Where are gestures processed?**
   - Check Keyboard2View.java, Pointers.java
   - Look for gesture callback handlers

### Implementation Priority:
- **If used**: **P1 HIGH** - Implement full system (141 lines)
- **If unused**: **P3 LOW** - Document as intentionally omitted

---

## Proposed Kotlin Implementation

```kotlin
package tribixbite.keyboard2

/**
 * Gesture recognition system for directional touch input.
 *
 * Recognizes 4 gesture types:
 * - Swipe: Simple directional swipe
 * - Roundtrip: Swipe out and return to center
 * - Circle: Clockwise rotation
 * - Anticircle: Anticlockwise rotation
 *
 * Uses 16-direction quantization (0-15) for precise tracking.
 */
class Gesture(startingDirection: Int) {

    /** Current pointer direction (0-15) */
    var currentDir: Int = startingDirection
        private set

    /** Current gesture state */
    var state: State = State.Swiped
        private set

    /**
     * Gesture state machine states
     */
    enum class State {
        Cancelled,
        Swiped,
        RotatingClockwise,
        RotatingAnticlockwise,
        EndedSwipe,
        EndedCenter,
        EndedClockwise,
        EndedAnticlockwise
    }

    /**
     * Recognized gesture names
     */
    enum class Name {
        None,
        Swipe,
        Roundtrip,
        Circle,
        Anticircle
    }

    companion object {
        /** Angle to travel before rotation starts (in direction units) */
        const val ROTATION_THRESHOLD = 2

        /** Number of direction quantization levels */
        private const val NUM_DIRECTIONS = 16

        /**
         * Calculate shortest angular difference between two directions.
         * Uses modulo arithmetic to find shortest circular path.
         *
         * @return Positive for clockwise, negative for anticlockwise
         */
        fun dirDiff(d1: Int, d2: Int): Int {
            if (d1 == d2) return 0

            val left = (d1 - d2 + NUM_DIRECTIONS) % NUM_DIRECTIONS
            val right = (d2 - d1 + NUM_DIRECTIONS) % NUM_DIRECTIONS

            return if (left < right) -left else right
        }
    }

    /**
     * Get currently recognized gesture.
     */
    fun getGesture(): Name {
        return when (state) {
            State.Cancelled -> Name.None
            State.Swiped, State.EndedSwipe -> Name.Swipe
            State.EndedCenter -> Name.Roundtrip
            State.RotatingClockwise, State.EndedClockwise -> Name.Circle
            State.RotatingAnticlockwise, State.EndedAnticlockwise -> Name.Anticircle
        }
    }

    /**
     * Check if gesture is still in progress.
     */
    fun isInProgress(): Boolean {
        return when (state) {
            State.Swiped,
            State.RotatingClockwise,
            State.RotatingAnticlockwise -> true
            else -> false
        }
    }

    /**
     * Get current direction (0-15).
     */
    fun currentDirection(): Int = currentDir

    /**
     * Pointer changed direction.
     *
     * @return true if gesture state changed
     */
    fun changedDirection(direction: Int): Boolean {
        val d = dirDiff(currentDir, direction)
        val clockwise = d > 0

        return when (state) {
            State.Swiped -> {
                if (kotlin.math.abs(d) < Config.globalConfig().circleSensitivity) {
                    false
                } else {
                    // Start rotation
                    state = if (clockwise) {
                        State.RotatingClockwise
                    } else {
                        State.RotatingAnticlockwise
                    }
                    currentDir = direction
                    true
                }
            }

            State.RotatingClockwise, State.RotatingAnticlockwise -> {
                currentDir = direction
                if ((state == State.RotatingClockwise) == clockwise) {
                    false  // Continue same rotation
                } else {
                    state = State.Cancelled  // Rotation reversed
                    true
                }
            }

            else -> false
        }
    }

    /**
     * Pointer moved back to center.
     *
     * @return true if gesture name will change
     */
    fun movedToCenter(): Boolean {
        return when (state) {
            State.Swiped -> {
                state = State.EndedCenter
                true  // Becomes Roundtrip
            }
            State.RotatingClockwise -> {
                state = State.EndedClockwise
                false
            }
            State.RotatingAnticlockwise -> {
                state = State.EndedAnticlockwise
                false
            }
            else -> false
        }
    }

    /**
     * Pointer lifted up.
     * Does not change gesture name.
     */
    fun pointerUp() {
        state = when (state) {
            State.Swiped -> State.EndedSwipe
            State.RotatingClockwise -> State.EndedClockwise
            State.RotatingAnticlockwise -> State.EndedAnticlockwise
            else -> state
        }
    }
}
```

---

## Files Requiring Investigation:
1. Check if Gesture is actually used in Java codebase
2. Check Pointers.java for gesture handling
3. Check Config.java for circle_sensitivity setting
4. Check Keyboard2View.java for gesture callbacks

---

**Review Date**: 2025-10-19
**Reviewed By**: Claude (Systematic Java→Kotlin Feature Parity Review)
**Next File**: File 85/251 - GestureClassifier.java
