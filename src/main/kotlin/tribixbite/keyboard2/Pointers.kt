package tribixbite.keyboard2

import android.os.Handler
import android.os.Looper
import android.os.Message
import kotlin.math.*

/**
 * Modern Kotlin implementation of pointer management and gesture recognition
 * Handles touch events, long presses, gestures, and swipe typing with neural integration
 */
class Pointers(
    private val handler: IPointerEventHandler,
    private val config: Config
) : Handler.Callback {

    companion object {
        // Pointer flags
        const val FLAG_P_LATCHABLE = 1
        const val FLAG_P_LATCHED = 1 shl 1
        const val FLAG_P_FAKE = 1 shl 2
        const val FLAG_P_DOUBLE_TAP_LOCK = 1 shl 3
        const val FLAG_P_LOCKED = 1 shl 4
        const val FLAG_P_SLIDING = 1 shl 5
        const val FLAG_P_CLEAR_LATCHED = 1 shl 6
        const val FLAG_P_CANT_LOCK = 1 shl 7
        const val FLAG_P_SWIPE_TYPING = 1 shl 8

        // Direction mapping for gestures
        private val DIRECTION_TO_INDEX = intArrayOf(
            7, 2, 2, 6, 6, 4, 4, 8, 8, 3, 3, 5, 5, 1, 1, 7
        )

        // Speed constants for slider operations
        const val SPEED_SMOOTHING = 0.7f
        const val SPEED_MAX = 4f
        const val SPEED_VERTICAL_MULT = 0.5f

        private var uniqueTimeoutWhat = 0
    }

    private val longPressHandler = Handler(Looper.getMainLooper(), this)
    private val pointers = mutableListOf<Pointer>()
    val swipeRecognizer = EnhancedSwipeGestureRecognizer()

    init {
        // Configure swipe recognizer threshold from config
        swipeRecognizer.setMinSwipeTypingDistance(config.swipe_typing_min_distance)
    }

    /**
     * Pointer event handler interface
     */
    interface IPointerEventHandler {
        fun onPointerDown(keyValue: KeyValue, isSwipe: Boolean)
        fun onPointerUp(keyValue: KeyValue, modifiers: Modifiers)
        fun onPointerHold(keyValue: KeyValue, modifiers: Modifiers)
        fun onPointerFlagsChanged(shouldVibrate: Boolean)
        fun onSwipeMove(x: Float, y: Float, recognizer: EnhancedSwipeGestureRecognizer)
        fun onSwipeEnd(recognizer: EnhancedSwipeGestureRecognizer)
        fun modifyKey(keyValue: KeyValue?, modifiers: Modifiers): KeyValue
    }

    /**
     * Get current modifier state
     */
    fun getModifiers(): Modifiers = getModifiers(false)

    private fun getModifiers(skipLatched: Boolean): Modifiers {
        val keyValues = mutableListOf<KeyValue>()

        for (pointer in pointers) {
            val value = pointer.value
            if (value != null &&
                !(skipLatched && pointer.hasFlagsAny(FLAG_P_LATCHED) &&
                  (pointer.flags and FLAG_P_LOCKED) == 0)) {
                keyValues.add(value)
            }
        }

        return Modifiers.ofArray(keyValues.toTypedArray(), keyValues.size)
    }

    /**
     * Clear all pointers
     */
    fun clear() {
        for (pointer in pointers) {
            stopLongPress(pointer)
        }
        pointers.clear()
    }

    /**
     * Check if a key is currently pressed
     */
    fun isKeyDown(key: KeyboardData.Key): Boolean {
        return pointers.any { it.key == key }
    }

    /**
     * Get flags for a specific key value
     */
    fun getKeyFlags(keyValue: KeyValue): Int {
        return pointers.firstOrNull { it.value == keyValue }?.flags ?: -1
    }

    /**
     * Check if a key value is currently locked (double-tap locked)
     */
    fun isKeyLocked(keyValue: KeyValue): Boolean {
        val pointer = pointers.firstOrNull { it.value == keyValue } ?: return false
        return (pointer.flags and FLAG_P_LOCKED) != 0
    }

    /**
     * Check if a key is currently latched (single press, not locked)
     */
    fun isKeyLatched(keyValue: KeyValue): Boolean {
        val pointer = pointers.firstOrNull { it.value == keyValue } ?: return false
        return (pointer.flags and FLAG_P_LATCHED) != 0 && (pointer.flags and FLAG_P_LOCKED) == 0
    }

    /**
     * Add a fake pointer for programmatic key latching
     */
    fun addFakePointer(key: KeyboardData.Key, keyValue: KeyValue, locked: Boolean) {
        var flags = pointerFlagsOfKeyValue(keyValue) or FLAG_P_FAKE or FLAG_P_LATCHED
        if (locked) {
            flags = flags or FLAG_P_LOCKED
        }

        val pointer = Pointer(-1, key, keyValue, 0f, 0f, Modifiers.EMPTY, flags)
        pointers.add(pointer)
        handler.onPointerFlagsChanged(false)
    }

    /**
     * Set fake pointer state for auto-capitalization and modifier management
     */
    fun setFakePointerState(key: KeyboardData.Key, keyValue: KeyValue, latched: Boolean, lock: Boolean) {
        val existingPointer = getLatched(key, keyValue)

        if (existingPointer == null) {
            // No existing pointer, latch the key if requested
            if (latched) {
                addFakePointer(key, keyValue, lock)
                handler.onPointerFlagsChanged(false)
            }
        } else if ((existingPointer.flags and FLAG_P_FAKE) == 0) {
            // Key already latched but not by a fake pointer, do nothing
        } else if (lock) {
            // Acting on locked modifiers, replace the pointer each time
            removePointer(existingPointer)
            if (latched) {
                addFakePointer(key, keyValue, lock)
            }
            handler.onPointerFlagsChanged(false)
        } else if ((existingPointer.flags and FLAG_P_LOCKED) != 0) {
            // Existing pointer is locked but lock is false, do not continue
        } else if (!latched) {
            // Key is latched by a fake pointer, unlatch if requested
            removePointer(existingPointer)
            handler.onPointerFlagsChanged(false)
        }
    }

    // Touch event handlers

    /**
     * Handle touch down event
     */
    fun onTouchDown(x: Float, y: Float, pointerId: Int, key: KeyboardData.Key) {
        // Ignore new presses while sliding
        if (isSliding()) return

        // Initialize swipe typing if enabled
        if (config.swipe_typing_enabled && pointers.isEmpty() && key != null) {
            swipeRecognizer.startSwipe(x, y, key)
        }

        // Get current modifiers, excluding latched if another key is down
        val modifiers = getModifiers(isOtherPointerDown())
        val value = handler.modifyKey(key.keys.getOrNull(0), modifiers)
        val pointer = makePointer(pointerId, key, value, x, y, modifiers)
        pointers.add(pointer)

        // Don't start long press timer if we might be swipe typing
        if (!(config.swipe_typing_enabled && swipeRecognizer.isSwipeTyping())) {
            startLongPress(pointer)
        }

        handler.onPointerDown(value, false)
    }

    /**
     * Handle touch move event
     */
    fun onTouchMove(x: Float, y: Float, pointerId: Int) {
        val pointer = getPointer(pointerId) ?: return

        // Handle swipe typing movement
        if (config.swipe_typing_enabled && !pointer.hasFlagsAny(FLAG_P_SWIPE_TYPING)) {
            swipeRecognizer.addPoint(x, y)
            handler.onSwipeMove(x, y, swipeRecognizer)

            // Check if this has become a swipe typing gesture
            if (swipeRecognizer.isSwipeTyping()) {
                pointer.flags = pointer.flags or FLAG_P_SWIPE_TYPING
                stopLongPress(pointer)
            }
        }

        // Handle sliding mode
        if (pointer.hasFlagsAny(FLAG_P_SLIDING)) {
            pointer.sliding?.onTouchMove(pointer, x, y)
            return
        }

        // Skip normal gesture processing if swipe typing
        if (pointer.hasFlagsAny(FLAG_P_SWIPE_TYPING)) return

        // Clamp y position for better up swipe behavior
        val adjustedY = if (y == 0f) -400f else y
        val dx = x - pointer.downX
        val dy = adjustedY - pointer.downY
        val distance = abs(dx) + abs(dy)

        // Use standard swipe distance threshold for direction gestures (matches Java)
        if (distance < config.swipe_dist_px) {
            // Pointer is still in center
            pointer.gesture?.let { gesture ->
                if (gesture.isInProgress()) {
                    // Gesture ended
                    gesture.movedToCenter()
                    pointer.value = applyGesture(pointer, gesture.getGesture())
                    pointer.flags = 0
                }
            }
        } else {
            // Pointer is in a direction
            val angle = atan2(dy.toDouble(), dx.toDouble()) + PI
            val direction = (((angle * 8 / PI).toInt() + 12) % 16)

            if (pointer.gesture == null) {
                // Gesture starts
                pointer.gesture = Gesture(direction)
                val newValue = getNearestKeyAtDirection(pointer, direction)
                if (newValue != null) {
                    pointer.value = newValue
                    pointer.flags = pointerFlagsOfKeyValue(newValue)

                    // Start sliding mode for slider keys
                    if (newValue.isSlider()) {
                        startSliding(pointer, x, adjustedY, dx, dy, newValue)
                    }
                    handler.onPointerDown(newValue, true)
                }
            } else {
                pointer.gesture?.let { gesture ->
                    if (gesture.changedDirection(direction)) {
                        // Gesture changed state
                        if (!gesture.isInProgress()) {
                            // Gesture ended
                            handler.onPointerFlagsChanged(true)
                        } else {
                            pointer.value = applyGesture(pointer, gesture.getGesture())
                            restartLongPress(pointer)
                            pointer.flags = 0
                            handler.onPointerFlagsChanged(true)
                        }
                    }
                }
            }
        }
    }

    /**
     * Handle touch up event
     */
    fun onTouchUp(pointerId: Int) {
        val pointer = getPointer(pointerId) ?: return

        // Handle swipe typing completion
        if (config.swipe_typing_enabled && pointer.hasFlagsAny(FLAG_P_SWIPE_TYPING)) {
            handler.onSwipeEnd(swipeRecognizer)
            swipeRecognizer.reset()
            removePointer(pointer)
            return
        }

        // Handle sliding mode
        if (pointer.hasFlagsAny(FLAG_P_SLIDING)) {
            clearLatched()
            pointer.sliding?.onTouchUp(pointer)
            return
        }

        stopLongPress(pointer)
        val pointerValue = pointer.value

        // Handle gesture completion
        if (pointer.gesture?.isInProgress() == true) {
            pointer.gesture?.pointerUp()
        }

        val latched = getLatched(pointer)
        if (latched != null) {
            // Already latched
            removePointer(pointer) // Remove duplicate

            // Toggle lockable key, except if it's a fake pointer
            if ((latched.flags and (FLAG_P_FAKE or FLAG_P_DOUBLE_TAP_LOCK)) == FLAG_P_DOUBLE_TAP_LOCK) {
                lockPointer(latched, false)
            } else {
                // Otherwise, unlatch
                removePointer(latched)
                if (pointerValue != null) {
                    handler.onPointerUp(pointerValue, pointer.modifiers)
                }
            }
        } else if ((pointer.flags and FLAG_P_LATCHABLE) != 0) {
            // Latchable but non-special keys must clear latched
            if ((pointer.flags and FLAG_P_CLEAR_LATCHED) != 0) {
                clearLatched()
            }
            pointer.flags = pointer.flags or FLAG_P_LATCHED
            pointer.pointerId = -1
            handler.onPointerFlagsChanged(false)
        } else {
            clearLatched()
            removePointer(pointer)
            if (pointerValue != null) {
                handler.onPointerUp(pointerValue, pointer.modifiers)
            }
        }
    }

    /**
     * Handle touch cancel event
     */
    fun onTouchCancel() {
        clear()
        handler.onPointerFlagsChanged(true)
    }

    // Long press handling

    override fun handleMessage(msg: Message): Boolean {
        for (pointer in pointers) {
            if (pointer.timeoutWhat == msg.what) {
                handleLongPress(pointer)
                return true
            }
        }
        return false
    }

    private fun startLongPress(pointer: Pointer) {
        val what = uniqueTimeoutWhat++
        pointer.timeoutWhat = what
        longPressHandler.sendEmptyMessageDelayed(what, config.longPressTimeout)
    }

    private fun stopLongPress(pointer: Pointer) {
        longPressHandler.removeMessages(pointer.timeoutWhat)
    }

    private fun restartLongPress(pointer: Pointer) {
        stopLongPress(pointer)
        startLongPress(pointer)
    }

    private fun handleLongPress(pointer: Pointer) {
        // Long press toggle lock on modifiers
        if ((pointer.flags and FLAG_P_LATCHABLE) != 0) {
            if (!pointer.hasFlagsAny(FLAG_P_CANT_LOCK)) {
                lockPointer(pointer, true)
            }
            return
        }

        // Skip latched keys or keys without values
        if (pointer.hasFlagsAny(FLAG_P_LATCHED) || pointer.value == null) return

        // Safe access to pointer value
        val currentValue = pointer.value ?: return

        // Try to apply long press modifier
        val longPressValue = KeyModifier.modifyLongPress(currentValue)
        if (longPressValue != currentValue) {
            pointer.value = longPressValue
            handler.onPointerDown(longPressValue, true)
            return
        }

        // Skip special keys
        if (longPressValue.hasFlag(KeyValue.Flag.SPECIAL)) return

        // Key repeat for other keys
        if (config.keyrepeat_enabled) {
            handler.onPointerHold(longPressValue, pointer.modifiers)
            longPressHandler.sendEmptyMessageDelayed(
                pointer.timeoutWhat,
                config.longPressInterval
            )
        }
    }

    // Sliding functionality

    private fun startSliding(pointer: Pointer, x: Float, y: Float, dx: Float, dy: Float, keyValue: KeyValue) {
        val slider = keyValue.getSliderValue() ?: return
        val repeat = keyValue.getSliderRepeat()
        val dirX = if (dx < 0) -repeat else repeat
        val dirY = if (dy < 0) -repeat else repeat

        stopLongPress(pointer)
        pointer.flags = pointer.flags or FLAG_P_SLIDING
        pointer.sliding = Sliding(x, y, dirX, dirY, slider)
    }

    // Gesture handling

    private fun applyGesture(pointer: Pointer, gesture: Gesture.Name): KeyValue {
        return when (gesture) {
            Gesture.Name.NONE -> pointer.value ?: KeyValue.SPACE
            Gesture.Name.SWIPE -> pointer.value ?: KeyValue.SPACE
            Gesture.Name.ROUNDTRIP -> {
                val keyAtDirection = getNearestKeyAtDirection(pointer, pointer.gesture?.currentDirection() ?: 0)
                modifyKeyWithExtraModifier(pointer, keyAtDirection, KeyValue.Modifier.GESTURE)
            }
            Gesture.Name.CIRCLE -> {
                modifyKeyWithExtraModifier(pointer, pointer.key.keys.firstOrNull(), KeyValue.Modifier.GESTURE)
            }
            Gesture.Name.ANTICIRCLE -> {
                handler.modifyKey(pointer.key.anticircle, pointer.modifiers)
            }
        }
    }

    private fun modifyKeyWithExtraModifier(pointer: Pointer, keyValue: KeyValue?, extraMod: KeyValue.Modifier): KeyValue {
        val extraModifier = KeyValue.makeInternalModifier(extraMod)
        val newModifiers = pointer.modifiers.withExtraMod(extraModifier)
        return handler.modifyKey(keyValue, newModifiers)
    }

    /**
     * Get the key nearest to [direction] that is not key0. Takes care
     * of applying [handler.modifyKey] to the selected key in the same
     * operation to be sure to treat removed keys correctly.
     * Returns null if no key could be found in the given direction or
     * if the selected key didn't change.
     */
    private fun getNearestKeyAtDirection(pointer: Pointer, direction: Int): KeyValue? {
        // Search in an arc around the direction: [0, -1, +1, -2, +2, -3, +3]
        // This scans 43% of the circle's area, centered on the initial swipe direction
        var i = 0
        while (i > -4) {
            val d = (direction + i + 16) % 16
            val index = DIRECTION_TO_INDEX.getOrNull(d) ?: continue
            val rawKey = pointer.key.keys.getOrNull(index)

            // Don't make the difference between a key that doesn't exist and a key
            // that is removed by handler. Triggers side effects.
            val k = handler.modifyKey(rawKey, pointer.modifiers)
            if (k != null) {
                // When the nearest key is a slider, it is only selected if it's placed
                // within 18% of the original swipe direction (abs(i) < 2)
                if (k.isSlider() && kotlin.math.abs(i) >= 2) {
                    i = if (i <= 0) -i + 1 else -i
                    continue
                }
                return k
            }
            // Compute next i: [0, -1, +1, -2, +2, -3, +3]
            i = if (i <= 0) -i + 1 else -i
        }
        return null
    }

    // Pointer management

    private fun getPointer(pointerId: Int): Pointer? {
        return pointers.firstOrNull { it.pointerId == pointerId }
    }

    private fun removePointer(pointer: Pointer) {
        pointers.remove(pointer)
    }

    private fun getLatched(pointer: Pointer): Pointer? {
        return getLatched(pointer.key, pointer.value)
    }

    private fun getLatched(key: KeyboardData.Key, value: KeyValue?): Pointer? {
        if (value == null) return null
        return pointers.firstOrNull { pointer ->
            pointer.key == key &&
            pointer.hasFlagsAny(FLAG_P_LATCHED) &&
            pointer.value == value
        }
    }

    private fun clearLatched() {
        val iterator = pointers.iterator()
        while (iterator.hasNext()) {
            val pointer = iterator.next()
            // Remove latched and not locked pointers
            if (pointer.hasFlagsAny(FLAG_P_LATCHED) && (pointer.flags and FLAG_P_LOCKED) == 0) {
                iterator.remove()
            }
            // Remove latchable flag from non-latched but pressed keys
            else if ((pointer.flags and FLAG_P_LATCHABLE) != 0) {
                pointer.flags = pointer.flags and FLAG_P_LATCHABLE.inv()
            }
        }
    }

    private fun lockPointer(pointer: Pointer, shouldVibrate: Boolean) {
        pointer.flags = (pointer.flags and FLAG_P_DOUBLE_TAP_LOCK.inv()) or FLAG_P_LOCKED
        handler.onPointerFlagsChanged(shouldVibrate)
    }

    private fun isSliding(): Boolean {
        return pointers.any { it.hasFlagsAny(FLAG_P_SLIDING) }
    }

    private fun isOtherPointerDown(): Boolean {
        return pointers.any { pointer ->
            !pointer.hasFlagsAny(FLAG_P_LATCHED) &&
            (pointer.value?.hasFlag(KeyValue.Flag.SPECIAL) != true)
        }
    }

    private fun pointerFlagsOfKeyValue(keyValue: KeyValue): Int {
        var flags = 0

        if (keyValue.hasFlag(KeyValue.Flag.LATCH)) {
            // Non-special latchable keys must clear modifiers and can't be locked
            if (!keyValue.hasFlag(KeyValue.Flag.SPECIAL)) {
                flags = flags or FLAG_P_CLEAR_LATCHED or FLAG_P_CANT_LOCK
            }
            flags = flags or FLAG_P_LATCHABLE
        }

        if (config.double_tap_lock_shift && keyValue.hasFlag(KeyValue.Flag.DOUBLE_TAP_LOCK)) {
            flags = flags or FLAG_P_DOUBLE_TAP_LOCK
        }

        return flags
    }

    private fun makePointer(pointerId: Int, key: KeyboardData.Key, value: KeyValue?, x: Float, y: Float, modifiers: Modifiers): Pointer {
        val flags = if (value == null) 0 else pointerFlagsOfKeyValue(value)
        return Pointer(pointerId, key, value, x, y, modifiers, flags)
    }

    /**
     * Internal pointer representation
     */
    private data class Pointer(
        var pointerId: Int, // -1 when latched
        val key: KeyboardData.Key,
        var value: KeyValue?,
        val downX: Float,
        val downY: Float,
        val modifiers: Modifiers,
        var flags: Int,
        var timeoutWhat: Int = -1,
        var gesture: Gesture? = null,
        var sliding: Sliding? = null
    ) {
        fun hasFlagsAny(checkFlags: Int): Boolean = (flags and checkFlags) != 0
    }

    /**
     * Sliding state for continuous input
     */
    private inner class Sliding(
        private var lastX: Float,
        private var lastY: Float,
        private val directionX: Int,
        private val directionY: Int,
        private val slider: KeyValue.Slider
    ) {
        private var distance = 0f
        private var speed = 0.5f
        private var lastMoveMs = -1L


        fun onTouchMove(pointer: Pointer, x: Float, y: Float) {
            val travelled = abs(x - lastX) + abs(y - lastY)

            if (lastMoveMs == -1L) {
                if (travelled < (config.swipe_dist_px + config.slide_step_px)) {
                    return
                }
                lastMoveMs = System.currentTimeMillis()
            }

            distance += ((x - lastX) * speed * directionX +
                        (y - lastY) * speed * SPEED_VERTICAL_MULT * directionY) / config.slide_step_px

            updateSpeed(travelled, x, y)

            // Send event when distance exceeds 1
            val distanceInt = distance.toInt()
            if (distanceInt != 0) {
                distance -= distanceInt
                handler.onPointerHold(
                    KeyValue.makeSlider(slider, distanceInt),
                    pointer.modifiers
                )
            }
        }

        fun onTouchUp(pointer: Pointer) {
            removePointer(pointer)
            handler.onPointerFlagsChanged(false)
        }

        private fun updateSpeed(travelled: Float, x: Float, y: Float) {
            val currentMs = System.currentTimeMillis()
            val elapsed = currentMs - lastMoveMs

            if (elapsed > 0) {
                val currentSpeed = travelled / elapsed
                speed = speed * SPEED_SMOOTHING + currentSpeed * (1 - SPEED_SMOOTHING)
                speed = min(speed, SPEED_MAX)
            }

            lastX = x
            lastY = y
            lastMoveMs = currentMs
        }
    }

    /**
     * Gesture recognition state
     */
    private data class Gesture(private var direction: Int) {
        enum class Name { NONE, SWIPE, ROUNDTRIP, CIRCLE, ANTICIRCLE }

        private var inProgress = true

        fun isInProgress(): Boolean = inProgress
        fun currentDirection(): Int = direction
        fun getGesture(): Name = Name.SWIPE // Simplified for now

        fun changedDirection(newDirection: Int): Boolean {
            if (direction != newDirection) {
                direction = newDirection
                return true
            }
            return false
        }

        fun movedToCenter() {
            inProgress = false
        }

        fun pointerUp() {
            inProgress = false
        }
    }

    /**
     * Modifier state representation
     */
    data class Modifiers(private val keys: Array<KeyValue>, private val size: Int) {
        companion object {
            val EMPTY = Modifiers(emptyArray(), 0)

            fun ofArray(keys: Array<KeyValue>, size: Int): Modifiers {
                val truncatedKeys = Array(size) { keys[it] }
                return Modifiers(truncatedKeys, size)
            }
        }

        fun withExtraMod(extraMod: KeyValue): Modifiers {
            val newKeys = Array(size + 1) { i ->
                if (i < size) keys[i] else extraMod
            }
            return Modifiers(newKeys, size + 1)
        }

        fun isEmpty(): Boolean = size == 0

        /**
         * Check if this modifier set contains a specific modifier
         */
        fun contains(modifier: KeyValue.Modifier): Boolean {
            for (i in 0 until size) {
                val key = keys[i]
                if (key is KeyValue.ModifierKey && key.modifier == modifier) {
                    return true
                }
            }
            return false
        }

        /**
         * Get all modifier keys in this set
         */
        fun getModifiers(): List<KeyValue.Modifier> {
            val modifiers = mutableListOf<KeyValue.Modifier>()
            for (i in 0 until size) {
                val key = keys[i]
                if (key is KeyValue.ModifierKey) {
                    modifiers.add(key.modifier)
                }
            }
            return modifiers
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Modifiers) return false
            if (size != other.size) return false
            return keys.contentEquals(other.keys)
        }

        override fun hashCode(): Int {
            var result = keys.contentHashCode()
            result = 31 * result + size
            return result
        }
    }
}