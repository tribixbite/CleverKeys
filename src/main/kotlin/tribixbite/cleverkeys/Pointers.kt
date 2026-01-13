package tribixbite.cleverkeys

import android.content.Context
import android.graphics.PointF
import android.os.Handler
import android.os.Message
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.customization.SwipeDirection
import java.util.NoSuchElementException
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Manage pointers (fingers) on the screen and long presses.
 * Call back to IPointerEventHandler.
 */
class Pointers(
    private val _handler: IPointerEventHandler,
    private val _config: Config,
    context: Context
) : Handler.Callback {

    private val _longpress_handler: Handler = Handler(this)
    private val _ptrs = ArrayList<Pointer>()
    private val _gestureClassifier = GestureClassifier(context)
    val _swipeRecognizer = EnhancedSwipeGestureRecognizer()

    /** Custom short swipe manager for user-defined gesture mappings */
    private val _customSwipeManager = ShortSwipeCustomizationManager.getInstance(context)

    init {
        // Load custom short swipe mappings on keyboard startup
        // This ensures user-defined per-key actions work immediately without
        // needing to open the customization activity first
        CoroutineScope(Dispatchers.IO).launch {
            _customSwipeManager.loadMappings()
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "Custom short swipe mappings loaded on startup")
        }
    }

    /** Return the list of modifiers currently activated. */
    fun getModifiers(): Modifiers {
        return getModifiers(false)
    }

    /** When [skip_latched] is true, don't take flags of latched keys into account. */
    private fun getModifiers(skip_latched: Boolean): Modifiers {
        val n_ptrs = _ptrs.size
        val mods = Array<KeyValue?>(n_ptrs) { null }
        var n_mods = 0
        for (i in 0 until n_ptrs) {
            val p = _ptrs[i]
            if (p.value != null &&
                !(skip_latched && p.hasFlagsAny(FLAG_P_LATCHED) &&
                    (p.flags and FLAG_P_LOCKED) == 0)) {
                // v1.2.2 FIX: Don't include non-latched latchable keys (e.g., shift being swiped over)
                // A modifier should only be "active" if it's LATCHED or LOCKED, not just touched.
                // This prevents short swipes over shift from showing uppercase keyboard.
                val isLatchable = (p.flags and FLAG_P_LATCHABLE) != 0
                val isLatched = p.hasFlagsAny(FLAG_P_LATCHED)
                val isLocked = (p.flags and FLAG_P_LOCKED) != 0
                if (isLatchable && !isLatched && !isLocked) {
                    // Skip non-latched latchable keys - they're just being touched/swiped
                    continue
                }
                mods[n_mods++] = p.value
            }
        }
        return Modifiers.ofArray(mods, n_mods)
    }

    fun clear() {
        for (p in _ptrs) {
            stopLongPress(p)
        }
        _ptrs.clear()
    }

    fun isKeyDown(k: KeyboardData.Key): Boolean {
        for (p in _ptrs) {
            if (p.key == k) {
                return true
            }
        }
        return false
    }

    /** See [FLAG_P_*] flags. Returns [-1] if the key is not pressed. */
    fun getKeyFlags(kv: KeyValue): Int {
        for (p in _ptrs) {
            if (p.value != null && p.value == kv) {
                return p.flags
            }
        }
        return -1
    }

    /** The key must not be already latched. */
    internal fun add_fake_pointer(key: KeyboardData.Key, kv: KeyValue, locked: Boolean) {
        var flags = pointer_flags_of_kv(kv) or FLAG_P_FAKE or FLAG_P_LATCHED
        if (locked) {
            flags = flags or FLAG_P_LOCKED
        }
        val ptr = Pointer(-1, key, kv, 0f, 0f, Modifiers.EMPTY, flags)
        _ptrs.add(ptr)
        _handler.onPointerFlagsChanged(null)
    }

    /**
     * Set whether a key is latched or locked by adding a "fake" pointer, a
     * pointer that is not due to user interaction.
     * This is used by auto-capitalisation.
     *
     * When [lock] is true, [latched] control whether the modifier is locked or disabled.
     * When [lock] is false, an existing locked pointer is not affected.
     */
    fun set_fake_pointer_state(
        key: KeyboardData.Key,
        kv: KeyValue,
        latched: Boolean,
        lock: Boolean
    ) {
        val ptr = getLatched(key, kv)
        if (ptr == null) {
            // No existing pointer, latch the key.
            if (latched) {
                add_fake_pointer(key, kv, lock)
                _handler.onPointerFlagsChanged(null)
            }
        } else if ((ptr.flags and FLAG_P_FAKE) == 0) {
            // Key already latched but not by a fake ptr, do nothing.
        } else if (lock) {
            // Acting on locked modifiers, replace the pointer each time.
            removePtr(ptr)
            if (latched) {
                add_fake_pointer(key, kv, lock)
            }
            _handler.onPointerFlagsChanged(null)
        } else if ((ptr.flags and FLAG_P_LOCKED) != 0) {
            // Existing ptr is locked but [lock] is false, do not continue.
        } else if (!latched) {
            // Key is latched by a fake ptr. Unlatch if requested.
            removePtr(ptr)
            _handler.onPointerFlagsChanged(null)
        }
    }

    // Receiving events

    fun onTouchUp(pointerId: Int) {
        val ptr = getPtr(pointerId) ?: return

        if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "=== onTouchUp START: ptr_value=${ptr.value}, flags=0x${ptr.flags.toString(16)}, pointerId=$pointerId ===")

        // Handle swipe typing completion
        if (_config.swipe_typing_enabled && ptr.hasFlagsAny(FLAG_P_SWIPE_TYPING)) {
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "Path: SWIPE_TYPING completion")
            _handler.onSwipeEnd(_swipeRecognizer)
            _swipeRecognizer.reset()
            removePtr(ptr)
            return
        }

        // Handle TrackPoint mode completion
        if (ptr.hasFlagsAny(FLAG_P_TRACKPOINT_MODE)) {
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "Path: TRACKPOINT completion")
            removePtr(ptr)
            return
        }

        if (ptr.hasFlagsAny(FLAG_P_SLIDING)) {
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "Path: SLIDING")
            clearLatched()
            ptr.sliding?.onTouchUp(ptr)
            return
        }
        stopLongPress(ptr)
        val ptr_value = ptr.value

        // UNIFIED GESTURE CLASSIFICATION: Use GestureClassifier to decide TAP vs SWIPE
        // This eliminates race conditions between multiple prediction systems
        // Allow entry if either Swipe Typing (Char keys) OR Short Gestures (Any key) is enabled
        val isCharKey = ptr_value != null && ptr_value.getKind() == KeyValue.Kind.Char
        val canSwipeType = _config.swipe_typing_enabled && isCharKey
        val canShortGesture = _config.short_gestures_enabled && ptr_value != null

        if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "Gesture check: isCharKey=$isCharKey, canSwipeType=$canSwipeType, canShortGesture=$canShortGesture, " +
            "gesture=${ptr.gesture}, hasExcludedFlags=${ptr.hasFlagsAny(FLAG_P_SLIDING or FLAG_P_SWIPE_TYPING or FLAG_P_LATCHED)}, hasKey=${ptr.key != null}")

        if ((canSwipeType || canShortGesture) && ptr.gesture == null &&
            !ptr.hasFlagsAny(FLAG_P_SLIDING or FLAG_P_SWIPE_TYPING or FLAG_P_LATCHED) &&
            ptr.key != null
        ) {
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "ENTERING gesture classification block")
        } else {
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "SKIPPING gesture block: canSwipe=$canSwipeType canShort=$canShortGesture " +
                "gesture=${ptr.gesture} excluded=${ptr.hasFlagsAny(FLAG_P_SLIDING or FLAG_P_SWIPE_TYPING or FLAG_P_LATCHED)} hasKey=${ptr.key != null}")
        }
        if ((canSwipeType || canShortGesture) && ptr.gesture == null &&
            !ptr.hasFlagsAny(FLAG_P_SLIDING or FLAG_P_SWIPE_TYPING or FLAG_P_LATCHED) &&
            ptr.key != null
        ) {
            // Collect gesture data for classification
            val swipePath = _swipeRecognizer.getSwipePath()
            var totalDistance = 0.0f

            if (swipePath != null && swipePath.size > 1) {
                // Calculate total path distance
                for (i in 1 until swipePath.size) {
                    val p1 = swipePath[i - 1]
                    val p2 = swipePath[i]
                    val dx = p2.x - p1.x
                    val dy = p2.y - p1.y
                    totalDistance += sqrt(dx * dx + dy * dy)
                }
            }

            val timeElapsed = System.currentTimeMillis() - ptr.downTime
            val keyWidth = _handler.getKeyWidth(ptr.key)

            val gestureData = GestureClassifier.GestureData(
                ptr.hasLeftStartingKey,
                totalDistance,
                timeElapsed,
                keyWidth
            )

            val gestureType = _gestureClassifier.classify(gestureData)

            // CRITICAL FIX: Only Char keys can trigger Neural Swipe Typing
            // For non-Char keys (like Backspace), even if the gesture is long (classified as SWIPE),
            // we must treat it as a TAP/Short Gesture to allow directional actions (e.g. Delete Word)
            val effectiveGestureType = if (!isCharKey && gestureType == GestureClassifier.GestureType.SWIPE) {
                GestureClassifier.GestureType.TAP
            } else {
                gestureType
            }

            Log.d(
                "Pointers", "Gesture classified as: $gestureType (effective=$effectiveGestureType) " +
                    "(hasLeftKey=${ptr.hasLeftStartingKey} " +
                    "distance=$totalDistance " +
                    "time=${timeElapsed}ms)"
            )

            if (effectiveGestureType == GestureClassifier.GestureType.SWIPE) {
                // This is a swipe gesture - send to neural predictor
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "Sending to neural predictor")
                _handler.onSwipeEnd(_swipeRecognizer)
                clearLatched() // Clear shift after swipe word completes
                _swipeRecognizer.reset()
                removePtr(ptr)
                return
            } else {
                // This is a TAP - check for short gesture (within-key directional swipe)
                Log.d(
                    "Pointers", "TAP path: short_gestures=${_config.short_gestures_enabled} " +
                        "hasLeftKey=${ptr.hasLeftStartingKey} " +
                        "pathSize=${swipePath?.size ?: 0} " +
                        "modifiers=${ptr.modifiers.size()}"
                )

                // CRITICAL FIX v1.32.923: Changed from swipePath.size > 1 to >= 1
                // Some gestures only collect 1 point (downX,downY) before UP fires
                // We can still calculate direction from ptr.downX/downY to the last point
                //
                // v1.32.927: Short gestures now work with shift active
                // - Sublabels trigger regardless of shift state
                // - If sublabel is a word (letters/apostrophe only), capitalize first char when shift active
                // - Non-word sublabels (punctuation, symbols) output as-is

                // CRITICAL FIX: Allow leaving the key bounds for non-Char keys (like Backspace)
                // This allows "Short Swipe over Backspace" (which often leaves the key) to trigger delete_last_word
                val allowLeftKey = !isCharKey

                // Use tracked last position instead of relying on swipePath (which might be filtered/smoothed)
                val dx = ptr.lastX - ptr.downX
                val dy = ptr.lastY - ptr.downY
                val distance = sqrt(dx * dx + dy * dy)

                // UPDATED v1.32.926: Check custom mappings BEFORE blocking check
                // Custom user-defined mappings should ALWAYS work, even with shift active
                // Only built-in sublabel gestures are blocked when modifiers are active on char keys
                if (_config.short_gestures_enabled && (!ptr.hasLeftStartingKey || allowLeftKey) &&
                    distance > 0 // Basic check that we moved
                ) {
                    
                    // CRITICAL FIX: Calculate keyHypotenuse since it's needed for threshold calculation
                    val keyHypotenuse = _handler.getKeyHypotenuse(ptr.key)

                    // Calculate MIN threshold: Use MIN of percentage-based and absolute threshold
                    // This ensures wide keys (like Backspace) don't require huge swipes,
                    // while small keys still respect the percentage to avoid accidental triggers.
                    val percentMinThreshold = keyHypotenuse * (_config.short_gesture_min_distance / 100.0f)
                    val absoluteThreshold = _config.swipe_dist_px.toFloat()

                    // RELAXED THRESHOLD: Use 0.8 factor to approximate Manhattan->Euclidean conversion
                    // (Manhattan distance is ~1.4x Euclidean for diagonals).
                    // Also removed the 1.5f multiplier which made triggering too hard.
                    val effectiveAbsolute = if (absoluteThreshold > 0) absoluteThreshold * 0.8f else Float.MAX_VALUE

                    // Use the easier (smaller) of the two thresholds for minimum
                    val minDistance = min(percentMinThreshold, effectiveAbsolute)

                    // Max distance is now handled by hasLeftStartingKey calculation during MOVE
                    // If we got here, hasLeftStartingKey is false, meaning we're within max_distance
                    Log.d(
                        "Pointers", "Short gesture check: distance=$distance " +
                            "minDistance=$minDistance " +
                            "(min=${_config.short_gesture_min_distance}% max=${_config.short_gesture_max_distance}% of $keyHypotenuse) " +
                            "hasLeftKey=${ptr.hasLeftStartingKey}"
                    )

                    if (distance >= minDistance) {
                        // Trigger short gesture - calculate direction (same as original repo)
                        val a = atan2(dy, dx) + Math.PI
                        // a is between 0 and 2pi, 0 is pointing to the left
                        // add 12 to align 0 to the top
                        val direction = ((a * 8 / Math.PI).toInt() + 12) % 16

                        // Detailed logging for direction debugging
                        val angleDeg = Math.toDegrees(a)
                        val keyIndex = DIRECTION_TO_INDEX[direction]
                        val posNames = arrayOf("c", "nw", "ne", "sw", "se", "w", "e", "n", "s")
                        val posName = if (keyIndex in posNames.indices) posNames[keyIndex] else "?"

                        Log.d(
                            "Pointers", String.format(
                                "SHORT_SWIPE: key=%s dx=%.1f dy=%.1f dist=%.1f angle=%.1f° dir=%d→idx=%d(%s)",
                                ptr.key.keys[0], dx, dy, distance, angleDeg, direction, keyIndex, posName
                            )
                        )

                        // CUSTOM MAPPING CHECK: Check user-defined mappings first
                        // Convert 16-direction to 8-direction SwipeDirection
                        val swipeDir = directionToSwipeDirection(direction)
                        val keyCode = ptr.key.keys[0]?.getString() ?: ""
                        val customMapping = _customSwipeManager.getMapping(keyCode, swipeDir)

                        if (customMapping != null) {
                            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "CUSTOM_SHORT_SWIPE: Found custom mapping for $keyCode:$swipeDir -> ${customMapping.actionType}:${customMapping.actionValue}")
                            // Delegate to handler for custom mapping execution
                            // Custom mappings work even with shift active - this is intentional
                            _handler.onCustomShortSwipe(customMapping)
                            clearLatched()
                            _swipeRecognizer.reset()
                            removePtr(ptr)
                            return
                        }

                        // Use getNearestKeyAtDirection to search nearby if exact direction not defined
                        var gestureValue = getNearestKeyAtDirection(ptr, direction)

                        Log.d(
                            "Pointers", String.format(
                                "SHORT_SWIPE_RESULT: dir=%d found=%s",
                                direction, gestureValue?.toString() ?: "null"
                            )
                        )

                        if (gestureValue != null) {
                            // v1.32.927: Apply shift capitalization to word sublabels
                            // If shift is active and sublabel is a word (letters/apostrophe only),
                            // capitalize the first character
                            val hasShift = ptr.modifiers.has(KeyValue.Modifier.SHIFT)
                            if (hasShift && gestureValue.getKind() == KeyValue.Kind.Char) {
                                val str = gestureValue.getString()
                                if (str.isNotEmpty() && str.all { it.isLetter() || it == '\'' }) {
                                    // It's a word - capitalize first letter
                                    val capitalized = str.replaceFirstChar { it.uppercaseChar() }
                                    if (capitalized != str) {
                                        gestureValue = if (capitalized.length == 1) {
                                            KeyValue.makeCharKey(capitalized[0])
                                        } else {
                                            KeyValue.makeStringKey(capitalized)
                                        }
                                        if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "SHORT_GESTURE: Shift active, capitalized word '$str' -> '$capitalized'")
                                    }
                                }
                            }

                            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "SHORT_GESTURE SUCCESS: triggering ${gestureValue}")

                            // v1.1.88: Handle latchable keys (modifiers/dead keys) correctly
                            // Dead keys like accent_aigu should LATCH, not output immediately
                            val gestureFlags = pointer_flags_of_kv(gestureValue)
                            if ((gestureFlags and FLAG_P_LATCHABLE) != 0) {
                                // This is a latchable key (dead key/modifier) - create a latched pointer
                                if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "SHORT_GESTURE: Latchable key detected, creating latched pointer")
                                // Clear existing latched modifiers if this is a non-special latchable key
                                if ((gestureFlags and FLAG_P_CLEAR_LATCHED) != 0) {
                                    clearLatched()
                                }
                                // Create a new latched pointer for this modifier
                                val latchedFlags = gestureFlags or FLAG_P_LATCHED
                                val latchedPtr = Pointer(-1, ptr.key, gestureValue, ptr.downX, ptr.downY, Modifiers.EMPTY, latchedFlags)
                                _ptrs.add(latchedPtr)
                                _handler.onPointerFlagsChanged(null)
                                _swipeRecognizer.reset()
                                removePtr(ptr)
                                return
                            }

                            // Non-latchable key - trigger normally
                            // Note: Navigation keys are handled during onTouchMove (hold-to-repeat mode)
                            _handler.onPointerDown(gestureValue, false)
                            _handler.onPointerUp(gestureValue, ptr.modifiers)
                            clearLatched() // Clear shift after gesture completes
                            _swipeRecognizer.reset()
                            removePtr(ptr)
                            return
                        } else {
                            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "SHORT_GESTURE FAILED: getNearestKeyAtDirection returned null for direction $direction")
                        }
                    } else {
                        if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "SHORT_GESTURE SKIP: distance $distance < minDistance $minDistance")
                    }
                } else {
                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "SHORT_GESTURE BLOCKED: short_gestures_enabled=${_config.short_gestures_enabled} " +
                        "hasLeftStartingKey=${ptr.hasLeftStartingKey} allowLeftKey=$allowLeftKey " +
                        "distance=$distance")
                }

                // Regular TAP - output the key character only if it was deferred
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "TAP path: deferred=${ptr.hasFlagsAny(FLAG_P_DEFERRED_DOWN)}")
                if (ptr.hasFlagsAny(FLAG_P_DEFERRED_DOWN)) {
                    _handler.onPointerDown(ptr_value, false)
                }
                _swipeRecognizer.reset()
            }
        }
        if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "After gesture block, proceeding to latched logic")
        // Log all current pointers to understand state
        val latchedPtrs = _ptrs.filter { it.hasFlagsAny(FLAG_P_LATCHED) }
        if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "Current latched pointers: ${latchedPtrs.map { "${it.value}(flags=0x${it.flags.toString(16)})" }}")
        // REMOVED: Legacy gesture.pointer_up() call - curved gestures obsolete
        val latched = getLatched(ptr)
        if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "onTouchUp path: latched=$latched, ptr.flags=0x${ptr.flags.toString(16)}, isLatchable=${(ptr.flags and FLAG_P_LATCHABLE) != 0}")
        if (latched != null) { // Already latched
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "Path: Already latched, latched.flags=0x${latched.flags.toString(16)}")
            removePtr(ptr) // Remove duplicate
            // Toggle lockable key, except if it's a fake pointer
            if ((latched.flags and (FLAG_P_FAKE or FLAG_P_DOUBLE_TAP_LOCK)) == FLAG_P_DOUBLE_TAP_LOCK) {
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "Path: Locking pointer (double-tap)")
                lockPointer(latched)
            } else { // Otherwise, unlatch
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "Path: Unlatching")
                removePtr(latched)
                _handler.onPointerUp(ptr_value, ptr.modifiers)
            }
        } else if ((ptr.flags and FLAG_P_LATCHABLE) != 0) {
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "Path: Latchable key - will latch")
            // Latchable but non-special keys must clear latched.
            if ((ptr.flags and FLAG_P_CLEAR_LATCHED) != 0) {
                clearLatched()
            }
            ptr.flags = ptr.flags or FLAG_P_LATCHED
            ptr.pointerId = -1
            _handler.onPointerFlagsChanged(null)
        } else {
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "Regular key up: clearing latched, value=$ptr_value, ptrs_before=${_ptrs.size}")
            clearLatched()
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "After clearLatched: ptrs_after=${_ptrs.size}")
            removePtr(ptr)
            _handler.onPointerUp(ptr_value, ptr.modifiers)
        }
    }

    fun onTouchCancel() {
        clear()
        _handler.onPointerFlagsChanged(null)  // No haptic on cancel
    }

    /** Whether an other pointer is down on a non-special key. */
    private fun isOtherPointerDown(): Boolean {
        for (p in _ptrs) {
            val value = p.value
            if (!p.hasFlagsAny(FLAG_P_LATCHED) &&
                (value == null || !value.hasFlagsAny(KeyValue.FLAG_SPECIAL))
            ) {
                return true
            }
        }
        return false
    }

    /** Count active (non-latched) pointers for swipe detection. */
    private fun countActivePointers(): Int {
        var count = 0
        for (p in _ptrs) {
            if (!p.hasFlagsAny(FLAG_P_LATCHED)) {
                count++
            }
        }
        return count
    }

    fun onTouchDown(x: Float, y: Float, pointerId: Int, key: KeyboardData.Key) {
        // Ignore new presses while a sliding key is active. On some devices, ghost
        // touch events can happen while the pointer travels on top of other keys.
        if (isSliding()) {
            return
        }

        // Initialize swipe recognizer if swipe typing OR short gestures enabled
        // Short gestures (e.g. arrow key SW/NW for home/end) also need path tracking
        // Use countActivePointers() == 0 instead of _ptrs.isEmpty() to handle latched Shift
        if ((_config.swipe_typing_enabled || _config.short_gestures_enabled) &&
            countActivePointers() == 0 && key != null) {
            _swipeRecognizer.startSwipe(x, y, key)
        }

        // Don't take latched modifiers into account if an other key is pressed.
        // The other key already "own" the latched modifiers and will clear them.
        val mods = getModifiers(isOtherPointerDown())
        val value = _handler.modifyKey(key.keys[0], mods)
        val ptr = make_pointer(pointerId, key, value, x, y, mods)
        _ptrs.add(ptr)

        // CRITICAL FIX: Detect if this might be the start of a swipe gesture
        // Don't output character immediately, but DO start long press timer for key repeat
        // This allows holding space/letters to repeat while still supporting swipe typing
        // Use countActivePointers() instead of _ptrs.size to handle latched modifiers (Shift)
        val firstKey = key?.keys?.get(0)
        val mightBeSwipe = _config.swipe_typing_enabled && countActivePointers() == 1 &&
            key != null && firstKey != null &&
            firstKey.getKind() == KeyValue.Kind.Char

        if (mightBeSwipe) {
            ptr.flags = ptr.flags or FLAG_P_DEFERRED_DOWN
        }

        // Start long press timer for key repeat - even for potential swipe keys
        // The handleLongPress will check if swipe typing has been activated and skip repeat if so
        // This allows holding space/letters to trigger key repeat when not moving
        if (!(_config.swipe_typing_enabled && _swipeRecognizer.isSwipeTyping())) {
            startLongPress(ptr)
        }

        // Don't output character immediately when swipe typing might start
        // The character will be output in onTouchUp if it turns out not to be a swipe
        // OR in handleLongPress if key repeat triggers
        if (!mightBeSwipe) {
            _handler.onPointerDown(value, false)
        }
    }

    /**
     * [direction] is an int between [0] and [15] that represent 16 sections of a
     * circle, clockwise, starting at the top.
     */
    private fun getKeyAtDirection(k: KeyboardData.Key, direction: Int): KeyValue? {
        return k.keys[DIRECTION_TO_INDEX[direction]]
    }

    /**
     * Get the key nearest to [direction] that is not key0. Take care
     * of applying [_handler.modifyKey] to the selected key in the same
     * operation to be sure to treat removed keys correctly.
     * Return [null] if no key could be found in the given direction or
     * if the selected key didn't change.
     */
    private fun getNearestKeyAtDirection(ptr: Pointer, direction: Int): KeyValue? {
        // v1.2.2 FIX: Reduced from +/-3 to +/-1 (was scanning 43%, now 19% = ~67°)
        // This prevents horizontal swipes from accidentally triggering diagonal subkeys
        // when the exact direction doesn't have a key defined.
        // With ±2, direction 4 (E) - 2 = direction 2 (NE), causing 'we' swipe to trigger '2'
        // [i] is [0, -1, +1], scanning ~19% of the circle's area (3 directions = 67.5°)
        var i = 0
        while (i > -2) {  // Changed from -3 to -2 (±1 range)
            val d = (direction + i + 16) % 16
            // Don't make the difference between a key that doesn't exist and a key
            // that is removed by [_handler]. Triggers side effects.
            val k = _handler.modifyKey(getKeyAtDirection(ptr.key, d), ptr.modifiers)
            if (k != null) {
                // When the nearest key is a slider, it is only selected if it's placed
                // within 18% of the original swipe direction.
                // This reduces accidental swipes on the slider and allow typing circle
                // gestures without being interrupted by the slider.
                if (k.getKind() == KeyValue.Kind.Slider && abs(i) >= 2) {
                    i = (i.inv() shr 31) - i
                    continue
                }
                return k
            }
            i = (i.inv() shr 31) - i
        }
        return null
    }

    fun onTouchMove(x: Float, y: Float, pointerId: Int) {
        val ptr = getPtr(pointerId) ?: return

        // Update last known position
        ptr.lastX = x
        ptr.lastY = y

        Log.d(
            "Pointers", "onTouchMove: id=$pointerId pos=($x,$y) " +
                "value=${ptr.value} " +
                "hasLeftKey=${ptr.hasLeftStartingKey} " +
                "flags=${ptr.flags}"
        )

        if (ptr.hasFlagsAny(FLAG_P_SLIDING)) {
            ptr.sliding?.onTouchMove(ptr, x, y)
            return
        }

        // Skip normal gesture processing if already confirmed as swipe typing
        if (ptr.hasFlagsAny(FLAG_P_SWIPE_TYPING)) {
            _handler.onSwipeMove(x, y, _swipeRecognizer)
            return
        }

        // Handle TrackPoint mode: finger movement controls cursor direction
        if (ptr.hasFlagsAny(FLAG_P_TRACKPOINT_MODE)) {
            val dx = x - ptr.downX
            val dy = y - ptr.downY
            val movementDist = sqrt(dx * dx + dy * dy)

            if (movementDist >= TRACKPOINT_MOVEMENT_THRESHOLD) {
                // Calculate direction and find nav key
                val a = atan2(dy, dx) + Math.PI
                val direction = ((a * 8 / Math.PI).toInt() + 12) % 16
                val navKey = getNavKeyForDirection(ptr, direction)

                if (navKey != null) {
                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "TRACKPOINT: Moving cursor, direction=$direction, dist=$movementDist")
                    _handler.onPointerDown(navKey, false)
                    _handler.onPointerUp(navKey, ptr.modifiers)
                }

                // Reset reference point for continuous movement
                ptr.downX = x
                ptr.downY = y
            }
            return // Don't process other gesture logic in TrackPoint mode
        }

        // Track if pointer has left the starting key (for short gesture detection on UP)
        // Use short_gesture_max_distance to define the boundary (as % of key diagonal)
        // Higher values = more lenient, allows swipes further from key center
        if (ptr.key != null && !ptr.hasLeftStartingKey) {
            val keyHypotenuse = _handler.getKeyHypotenuse(ptr.key)
            val maxAllowedDistance = keyHypotenuse * (_config.short_gesture_max_distance / 100.0f)

            // Calculate distance from key center
            val keyCenterX = ptr.downX  // Approximation: touch down point is roughly key center
            val keyCenterY = ptr.downY
            val distanceFromStart = sqrt((x - keyCenterX) * (x - keyCenterX) + (y - keyCenterY) * (y - keyCenterY))

            if (distanceFromStart > maxAllowedDistance) {
                ptr.hasLeftStartingKey = true
            }
        }

        // SUBKEY CHECK: Must happen BEFORE swipe typing path collection
        // If user is swiping toward a slider or event key (e.g., cursor_left/right, switch_forward/backward),
        // handle it immediately instead of entering swipe typing mode.
        // The position in IME windows is clamped to view - adjust for edge swipes
        var adjustedY = y
        if (y == 0.0f) adjustedY = -400f
        val dx = x - ptr.downX
        val dy = adjustedY - ptr.downY
        val dist = abs(dx) + abs(dy)

        if (dist >= _config.swipe_dist_px && ptr.gesture == null) {
            // Pointer moved significantly - check for special key activation FIRST
            val a = atan2(dy, dx) + Math.PI
            val direction = ((a * 8 / Math.PI).toInt() + 12) % 16

            val subkeyValue = getNearestKeyAtDirection(ptr, direction)
            if (subkeyValue != null) {
                when (subkeyValue.getKind()) {
                    KeyValue.Kind.Slider -> {
                        // Slider key detected - enter sliding mode instead of swipe typing
                        if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "Slider detected at direction $direction, starting sliding mode")
                        ptr.gesture = Gesture(direction)
                        ptr.value = subkeyValue
                        ptr.flags = pointer_flags_of_kv(subkeyValue)
                        startSliding(ptr, x, adjustedY, dx, dy, subkeyValue)
                        _handler.onPointerDown(subkeyValue, true)
                        return
                    }
                    KeyValue.Kind.Event -> {
                        // Event key detected (e.g., switch_forward, switch_backward)
                        // Trigger immediately and prevent swipe typing from intercepting
                        if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "Event key detected at direction $direction: ${subkeyValue.getEvent()}")
                        ptr.gesture = Gesture(direction)
                        ptr.value = subkeyValue
                        ptr.flags = pointer_flags_of_kv(subkeyValue)
                        _handler.onPointerDown(subkeyValue, true)
                        return
                    }
                    else -> {
                        // Other key types (including Keyevent nav keys) - let short gesture handling in onTouchUp handle them
                    }
                }
            }
        }

        // CRITICAL: For potential swipe typing, ALWAYS track path during movement
        // Short gesture detection should only happen on touch UP, not during MOVE
        // Also collect path for short gestures on non-Char keys (backspace, ctrl, etc.)
        // Use countActivePointers() to handle latched modifiers (Shift) correctly
        // EXCEPTION: Nav keys use TrackPoint mode instead of short gestures
        val ptrValue = ptr.value
        val activePointers = countActivePointers()
        val isNavKey = isNavigationKey(ptrValue)
        val isSwipeTypingKey = _config.swipe_typing_enabled && activePointers == 1 &&
            ptrValue != null && ptrValue.getKind() == KeyValue.Kind.Char
        val isShortGestureKey = _config.short_gestures_enabled && activePointers == 1 && ptrValue != null && !isNavKey
        val shouldCollectPath = isSwipeTypingKey || isShortGestureKey

        Log.d(
            "Pointers", "Path collection check: " +
                "swipeEnabled=${_config.swipe_typing_enabled} " +
                "shortGesturesEnabled=${_config.short_gestures_enabled} " +
                "ptrsSize=${_ptrs.size} " +
                "hasValue=${ptrValue != null} " +
                "isChar=${ptrValue != null && ptrValue.getKind() == KeyValue.Kind.Char} " +
                "shouldCollect=$shouldCollectPath"
        )

        if (shouldCollectPath) {
            // Track swipe movement for path collection
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "onTouchMove: collecting point ($x, $y) for potential swipe")
            _handler.onSwipeMove(x, y, _swipeRecognizer)

            // Check if this has become a confirmed multi-key swipe typing gesture
            if (_swipeRecognizer.isSwipeTyping()) {
                ptr.flags = ptr.flags or FLAG_P_SWIPE_TYPING
                stopLongPress(ptr)
            }

            // Skip normal gesture processing while tracking potential swipe
            return
        }

        // SIMPLIFIED: Legacy curved gesture system removed.
        // Swipe-to-corner gestures now handled in onTouchUp for unified logic.
        // Slider mode handled earlier (before swipe typing path collection).
    }

    // Pointers management

    private fun getPtr(pointerId: Int): Pointer? {
        for (p in _ptrs) {
            if (p.pointerId == pointerId) {
                return p
            }
        }
        return null
    }

    private fun removePtr(ptr: Pointer) {
        _ptrs.remove(ptr)
    }

    private fun getLatched(target: Pointer): Pointer? {
        return getLatched(target.key, target.value)
    }

    private fun getLatched(k: KeyboardData.Key, v: KeyValue?): Pointer? {
        if (v == null) {
            return null
        }
        for (p in _ptrs) {
            if (p.key == k && p.hasFlagsAny(FLAG_P_LATCHED) &&
                p.value != null && p.value == v
            ) {
                return p
            }
        }
        return null
    }

    /**
     * Clears all latched (but not locked) modifier pointers.
     * Called after swipe word insertion to clear shift state.
     * Internal visibility allows Keyboard2View to expose it.
     */
    internal fun clearLatched() {
        var clearedCount = 0
        for (i in _ptrs.size - 1 downTo 0) {
            val ptr = _ptrs[i]
            // Latched and not locked, remove
            if (ptr.hasFlagsAny(FLAG_P_LATCHED) && (ptr.flags and FLAG_P_LOCKED) == 0) {
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "clearLatched: removing latched key ${ptr.value} (flags=0x${ptr.flags.toString(16)})")
                _ptrs.removeAt(i)
                clearedCount++
            } else if ((ptr.flags and FLAG_P_LATCHABLE) != 0) {
                // Not latched but pressed, don't latch once released and stop long press.
                ptr.flags = ptr.flags and FLAG_P_LATCHABLE.inv()
            }
        }
        if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "clearLatched: cleared $clearedCount pointers, remaining=${_ptrs.size}")
        // Notify handler to update keyboard view when modifiers change
        if (clearedCount > 0) {
            _handler.onPointerFlagsChanged(null)
        }
    }

    /** Make a pointer into the locked state. */
    private fun lockPointer(ptr: Pointer, hapticEvent: HapticEvent? = null) {
        ptr.flags = (ptr.flags and FLAG_P_DOUBLE_TAP_LOCK.inv()) or FLAG_P_LOCKED
        _handler.onPointerFlagsChanged(hapticEvent)
    }

    internal fun isSliding(): Boolean {
        for (ptr in _ptrs) {
            if (ptr.hasFlagsAny(FLAG_P_SLIDING)) {
                return true
            }
        }
        return false
    }

    // Key repeat

    /** Message from [_longpress_handler]. */
    override fun handleMessage(msg: Message): Boolean {
        for (ptr in _ptrs) {
            if (ptr.timeoutWhat == msg.what) {
                handleLongPress(ptr)
                return true
            }
        }
        return false
    }

    private fun startLongPress(ptr: Pointer) {
        val what = uniqueTimeoutWhat++
        ptr.timeoutWhat = what
        _longpress_handler.sendEmptyMessageDelayed(what, _config.longPressTimeout.toLong())
    }

    private fun stopLongPress(ptr: Pointer) {
        _longpress_handler.removeMessages(ptr.timeoutWhat)
    }

    private fun restartLongPress(ptr: Pointer) {
        stopLongPress(ptr)
        startLongPress(ptr)
    }

    /** A pointer is long pressing. */
    private fun handleLongPress(ptr: Pointer) {
        // Skip if already in TrackPoint mode (movement handled in onTouchMove)
        if (ptr.hasFlagsAny(FLAG_P_TRACKPOINT_MODE)) {
            return
        }

        // Skip if pointer entered swipe typing mode (moved significantly)
        if (ptr.hasFlagsAny(FLAG_P_SWIPE_TYPING)) {
            return
        }

        // For deferred keys (potential swipes), check if user has moved beyond small threshold
        val dx = ptr.lastX - ptr.downX
        val dy = ptr.lastY - ptr.downY
        val movementDist = kotlin.math.sqrt(dx * dx + dy * dy)

        if (ptr.hasFlagsAny(FLAG_P_DEFERRED_DOWN)) {
            // Check if this is a nav key - use higher tolerance for TrackPoint activation
            val isNavKey = isNavKeyPressed(ptr)
            // Nav keys get higher tolerance (30px) to allow for finger jitter during hold
            // Non-nav keys use stricter threshold (15px) for swipe vs tap detection
            val movementThreshold = if (isNavKey) 30f else 15f

            if (movementDist > movementThreshold) {
                // User has moved - don't trigger key repeat, let swipe typing take over
                return
            }

            // TrackPoint mode: If pressed key IS a nav key and user held without moving, enter TrackPoint mode
            if (_config.keyrepeat_enabled && isNavKey) {
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d("Pointers", "TRACKPOINT: Entering TrackPoint mode for nav key ${ptr.value}")
                ptr.flags = (ptr.flags and FLAG_P_DEFERRED_DOWN.inv()) or FLAG_P_TRACKPOINT_MODE
                // Store current position as reference for movement detection
                ptr.downX = ptr.lastX
                ptr.downY = ptr.lastY
                // Vibrate to indicate TrackPoint mode activation
                _handler.onPointerFlagsChanged(HapticEvent.TRACKPOINT_ACTIVATE)
                return
            }
        }

        // Long press toggle lock on modifiers
        if ((ptr.flags and FLAG_P_LATCHABLE) != 0) {
            if (!ptr.hasFlagsAny(FLAG_P_CANT_LOCK)) {
                lockPointer(ptr, HapticEvent.LONG_PRESS)
            }
            return
        }
        // Latched key, no key
        val value = ptr.value
        if (ptr.hasFlagsAny(FLAG_P_LATCHED) || value == null) {
            return
        }
        // Key is long-pressable
        val kv = KeyModifier.modify_long_press(value)
        if (kv != value) {
            ptr.value = kv
            _handler.onPointerDown(kv, true)
            return
        }
        // Special keys
        if (kv.hasFlagsAny(KeyValue.FLAG_SPECIAL)) {
            return
        }
        // For every other keys, key-repeat
        if (_config.keyrepeat_enabled) {
            // If this was a deferred down (potential swipe), output the initial character first
            // This handles holding space/letters without moving - outputs initial char then repeats
            if (ptr.hasFlagsAny(FLAG_P_DEFERRED_DOWN)) {
                ptr.flags = ptr.flags and FLAG_P_DEFERRED_DOWN.inv()  // Clear deferred flag
                _handler.onPointerDown(kv, false)  // Output initial character
            }
            _handler.onPointerHold(kv, ptr.modifiers)
            _longpress_handler.sendEmptyMessageDelayed(
                ptr.timeoutWhat,
                _config.longPressInterval.toLong()
            )
        }
    }

    // Sliding

    /**
     * When sliding is ongoing, key events are handled by the [Slider] class.
     * [kv] must be of kind [Slider].
     */
    private fun startSliding(ptr: Pointer, x: Float, y: Float, dx: Float, dy: Float, kv: KeyValue) {
        val r = kv.getSliderRepeat()
        val dirx = if (dx < 0) -r else r
        val diry = if (dy < 0) -r else r
        stopLongPress(ptr)
        ptr.flags = ptr.flags or FLAG_P_SLIDING
        ptr.sliding = Sliding(x, y, dirx, diry, kv.getSlider())
    }

    /** Return the [FLAG_P_*] flags that correspond to pressing [kv]. */
    internal fun pointer_flags_of_kv(kv: KeyValue): Int {
        var flags = 0
        if (kv.hasFlagsAny(KeyValue.FLAG_LATCH)) {
            // Non-special latchable key must clear modifiers and can't be locked
            if (!kv.hasFlagsAny(KeyValue.FLAG_SPECIAL)) {
                flags = flags or FLAG_P_CLEAR_LATCHED or FLAG_P_CANT_LOCK
            }
            flags = flags or FLAG_P_LATCHABLE
        }
        if (_config.double_tap_lock_shift &&
            kv.hasFlagsAny(KeyValue.FLAG_DOUBLE_TAP_LOCK)
        ) {
            flags = flags or FLAG_P_DOUBLE_TAP_LOCK
        }
        return flags
    }

    /** Check if a KeyValue is a navigation key (arrow keys). */
    private fun isNavigationKey(kv: KeyValue?): Boolean {
        if (kv == null) return false
        if (kv.getKind() != KeyValue.Kind.Keyevent) return false
        return when (kv.getKeyevent()) {
            android.view.KeyEvent.KEYCODE_DPAD_UP,
            android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> true
            else -> false
        }
    }

    /** Check if the pressed key is a navigation key (for TrackPoint mode). */
    private fun isNavKeyPressed(ptr: Pointer): Boolean {
        // Check if the primary key value is a navigation key
        return isNavigationKey(ptr.value)
    }

    /** Get any navigation key in the given direction - check subkeys first, then nearby keys. */
    private fun getNavKeyForDirection(ptr: Pointer, direction: Int): KeyValue? {
        // First check if there's a nav subkey in this direction
        val subkey = getNearestKeyAtDirection(ptr, direction)
        if (subkey != null && isNavigationKey(subkey)) {
            return subkey
        }
        // Map direction to a standard nav key if the pressed key itself is nav
        if (isNavigationKey(ptr.value)) {
            // Return the appropriate nav key based on direction
            // Directions: 0=N, 4=E, 8=S, 12=W
            // Uses symbol codes from KeyValue.kt: up=0xE005, right=0xE006, down=0xE007, left=0xE008
            return when {
                direction in 14..15 || direction in 0..2 ->
                    KeyValue.keyeventKey(0xE005, android.view.KeyEvent.KEYCODE_DPAD_UP, 0)  // N region
                direction in 2..6 ->
                    KeyValue.keyeventKey(0xE006, android.view.KeyEvent.KEYCODE_DPAD_RIGHT, 0)  // E region
                direction in 6..10 ->
                    KeyValue.keyeventKey(0xE007, android.view.KeyEvent.KEYCODE_DPAD_DOWN, 0)  // S region
                direction in 10..14 ->
                    KeyValue.keyeventKey(0xE008, android.view.KeyEvent.KEYCODE_DPAD_LEFT, 0)  // W region
                else -> null
            }
        }
        return null
    }

    // Gestures
    // REMOVED: apply_gesture() and modify_key_with_extra_modifier()
    // These methods supported curved gestures (Roundtrip, Circle, Anticircle)
    // which are now obsolete with the new swipe typing system.

    // Pointers

    private fun make_pointer(
        p: Int,
        k: KeyboardData.Key,
        v: KeyValue?,
        x: Float,
        y: Float,
        m: Modifiers
    ): Pointer {
        val flags = if (v == null) 0 else pointer_flags_of_kv(v)
        return Pointer(p, k, v, x, y, m, flags)
    }

    internal class Pointer(
        /** -1 when latched. */
        var pointerId: Int,
        /** The Key pressed by this Pointer */
        val key: KeyboardData.Key,
        /** Selected value with [modifiers] applied. */
        var value: KeyValue?,
        var downX: Float,
        var downY: Float,
        /** Modifier flags at the time the key was pressed. */
        val modifiers: Modifiers,
        /** See [FLAG_P_*] flags. */
        var flags: Int
    ) {
        /** Last known X position. */
        var lastX: Float = downX
        /** Last known Y position. */
        var lastY: Float = downY

        /** Gesture state, see [Gesture]. [null] means the pointer has not moved out of the center region. */
        var gesture: Gesture? = null

        /** Time when touch began (for gesture classification). */
        val downTime: Long = System.currentTimeMillis()

        /** Identify timeout messages. */
        var timeoutWhat: Int = -1

        /** [null] when not in sliding mode. */
        var sliding: Sliding? = null

        /** Track if swipe has ever left the starting key's bounds (for short gesture detection). */
        var hasLeftStartingKey: Boolean = false

        fun hasFlagsAny(has: Int): Boolean {
            return (flags and has) != 0
        }
    }

    inner class Sliding(
        x: Float,
        y: Float,
        /** Direction of the initial movement, positive if sliding to the right and
         * negative if sliding to the left. */
        val direction_x: Int,
        val direction_y: Int,
        /** The property which is being slided. */
        val slider: KeyValue.Slider
    ) {
        /** Accumulated distance since last event. */
        var d = 0f

        /** The slider speed changes depending on the pointer speed. */
        var speed = 1.0f  // Start at full speed for responsive cursor movement

        /** Coordinate of the last move. */
        var last_x = x
        var last_y = y

        /**
         * [System.currentTimeMillis()] at the time of the last move. Equals to
         * [-1] when the sliding hasn't started yet.
         */
        var last_move_ms: Long = -1

        internal fun onTouchMove(ptr: Pointer, x: Float, y: Float) {
            // Start sliding after minimal travel distance for responsive cursor control.
            // Using just slide_step_px (not swipe_dist_px + slide_step_px) makes it
            // much easier to initiate cursor movement.
            val travelled = abs(x - last_x) + abs(y - last_y)
            if (last_move_ms == -1L) {
                if (travelled < _config.slide_step_px) {
                    return
                }
                last_move_ms = System.currentTimeMillis()
            }
            d += ((x - last_x) * speed * direction_x +
                (y - last_y) * speed * SLIDING_SPEED_VERTICAL_MULT * direction_y) /
                _config.slide_step_px
            update_speed(travelled, x, y)
            // Send an event when [abs(d)] exceeds [1].
            val d_ = d.toInt()
            if (d_ != 0) {
                d -= d_
                _handler.onPointerHold(KeyValue.sliderKey(slider, d_), ptr.modifiers)
            }
        }

        /**
         * Handle a sliding pointer going up. Latched modifiers are not
         * cleared to allow easy adjustments to the cursors. The pointer is
         * cancelled.
         */
        internal fun onTouchUp(ptr: Pointer) {
            removePtr(ptr)
            _handler.onPointerFlagsChanged(null)
        }

        /**
         * [speed] is computed from the elapsed time and distance traveled
         * between two move events. Exponential smoothing is used to smooth out
         * the noise. Sets [last_move_ms] and [last_pos].
         */
        private fun update_speed(travelled: Float, x: Float, y: Float) {
            val now = System.currentTimeMillis()
            val instant_speed = min(getSlidingSpeedMax(), travelled / (now - last_move_ms) + 1f)
            speed = speed + (instant_speed - speed) * getSlidingSpeedSmoothing()
            last_move_ms = now
            last_x = x
            last_y = y
        }
    }

    /**
     * Represent modifiers currently activated.
     * Sorted in the order they should be evaluated.
     */
    class Modifiers private constructor(
        private val _mods: Array<KeyValue?>,
        private val _size: Int
    ) {
        operator fun get(i: Int): KeyValue? = _mods[_size - 1 - i]
        fun size(): Int = _size

        fun has(m: KeyValue.Modifier): Boolean {
            for (i in 0 until _size) {
                val kv = _mods[i]
                when (kv?.getKind()) {
                    KeyValue.Kind.Modifier -> {
                        if (kv.getModifier() == m) {
                            return true
                        }
                    }
                    else -> {}
                }
            }
            return false
        }

        /** Return a copy of this object with an extra modifier added. */
        fun with_extra_mod(m: KeyValue): Modifiers {
            val newmods = _mods.copyOf(_size + 1)
            newmods[_size] = m
            return ofArray(newmods, newmods.size)
        }

        /** Returns the activated modifiers that are not in [m2]. */
        fun diff(m2: Modifiers): Iterator<KeyValue> {
            return ModifiersDiffIterator(this, m2)
        }

        override fun hashCode(): Int = _mods.contentHashCode()

        override fun equals(other: Any?): Boolean {
            if (other !is Modifiers) return false
            return _mods.contentEquals(other._mods)
        }

        companion object {
            @JvmField
            val EMPTY = Modifiers(emptyArray(), 0)

            @JvmStatic
            fun ofArray(mods: Array<KeyValue?>, size: Int): Modifiers {
                var actualSize = size
                // Sort and remove duplicates and nulls.
                if (actualSize > 1) {
                    mods.sortWith(nullsLast(naturalOrder()), 0, actualSize)
                    var j = 0
                    for (i in 0 until actualSize) {
                        val m = mods[i]
                        if (m != null && (i + 1 >= actualSize || m != mods[i + 1])) {
                            mods[j] = m
                            j++
                        }
                    }
                    actualSize = j
                }
                return Modifiers(mods, actualSize)
            }
        }

        /** Returns modifiers that are in [m1_] but not in [m2_]. */
        private class ModifiersDiffIterator(
            private val m1: Modifiers,
            private val m2: Modifiers
        ) : Iterator<KeyValue> {
            private var i1 = 0
            private var i2 = 0

            init {
                advance()
            }

            override fun hasNext(): Boolean {
                return i1 < m1._size
            }

            override fun next(): KeyValue {
                if (i1 >= m1._size) {
                    throw NoSuchElementException()
                }
                val m = m1._mods[i1]!!
                i1++
                advance()
                return m
            }

            /**
             * Advance to the next element if [i1] is not a valid element. The end
             * is reached when [i1 = m1.size()].
             */
            private fun advance() {
                while (i1 < m1.size()) {
                    val m = m1._mods[i1]!!
                    while (true) {
                        if (i2 >= m2._size) {
                            return
                        }
                        val d = m.compareTo(m2._mods[i2]!!)
                        if (d < 0) {
                            return
                        }
                        i2++
                        if (d == 0) {
                            break
                        }
                    }
                    i1++
                }
            }
        }
    }

    interface IPointerEventHandler {
        /** Key can be modified or removed by returning [null]. */
        fun modifyKey(k: KeyValue?, mods: Modifiers): KeyValue?

        /**
         * A key is pressed. [getModifiers()] is uptodate. Might be called after a
         * press or a swipe to a different value. Down events are not paired with
         * up events.
         */
        fun onPointerDown(k: KeyValue?, isSwipe: Boolean)

        /**
         * Key is released. [k] is the key that was returned by
         * [modifySelectedKey] or [modifySelectedKey].
         */
        fun onPointerUp(k: KeyValue?, mods: Modifiers)

        /** Flags changed because latched or locked keys or cancelled pointers.
         *  @param hapticEvent The haptic event to trigger, or null for no haptic feedback. */
        fun onPointerFlagsChanged(hapticEvent: HapticEvent?)

        /** Key is repeating. */
        fun onPointerHold(k: KeyValue, mods: Modifiers)

        /** Track swipe movement for swipe typing. */
        fun onSwipeMove(x: Float, y: Float, recognizer: ImprovedSwipeGestureRecognizer)

        /** Swipe typing gesture completed. */
        fun onSwipeEnd(recognizer: ImprovedSwipeGestureRecognizer)

        /** Check if a point is within a key's bounding box. */
        fun isPointWithinKey(x: Float, y: Float, key: KeyboardData.Key): Boolean

        /** Check if point is within key bounds with tolerance (as fraction of key size) */
        fun isPointWithinKeyWithTolerance(
            x: Float,
            y: Float,
            key: KeyboardData.Key,
            tolerance: Float
        ): Boolean

        /** Get the hypotenuse (diagonal length) of a key in pixels. */
        fun getKeyHypotenuse(key: KeyboardData.Key): Float

        /** Get the width of a key in pixels. */
        fun getKeyWidth(key: KeyboardData.Key): Float

        /** Execute a custom short swipe mapping defined by the user. */
        fun onCustomShortSwipe(mapping: ShortSwipeMapping)
    }

    companion object {
        const val FLAG_P_LATCHABLE = 1
        const val FLAG_P_LATCHED = 1 shl 1
        const val FLAG_P_FAKE = 1 shl 2
        const val FLAG_P_DOUBLE_TAP_LOCK = 1 shl 3
        const val FLAG_P_LOCKED = 1 shl 4
        const val FLAG_P_SLIDING = 1 shl 5

        /** Clear latched (only if also FLAG_P_LATCHABLE set). */
        const val FLAG_P_CLEAR_LATCHED = 1 shl 6

        /** Can't be locked, even when long pressing. */
        const val FLAG_P_CANT_LOCK = 1 shl 7

        /** Pointer is part of a swipe typing gesture. */
        const val FLAG_P_SWIPE_TYPING = 1 shl 8

        /** Key down event was deferred (waiting for gesture classification). */
        const val FLAG_P_DEFERRED_DOWN = 1 shl 9

        /** TrackPoint mode - hold on key with nav subkeys, then move finger to move cursor. */
        const val FLAG_P_TRACKPOINT_MODE = 1 shl 10

        /** Minimum movement (px) to trigger nav event in TrackPoint mode. */
        const val TRACKPOINT_MOVEMENT_THRESHOLD = 15f

        private var uniqueTimeoutWhat = 0

        // Maps 16 directions (0-15) to 9 key positions (c=0, nw=1, ne=2, sw=3, se=4, w=5, e=6, n=7, s=8)
        // Direction wheel: 0=N, 4=E, 8=S, 12=W (each step is 22.5°)
        // v1.2.2 FIX: Restored dir 4 to E (was SE, causing horizontal swipes to trigger NE subkey)
        @JvmField
        val DIRECTION_TO_INDEX = intArrayOf(
            7, 2, 2, 6, 6, 4, 4, 8, 8, 3, 3, 5, 5, 1, 1, 7
        //  n ne ne  e  e se se  s  s sw sw  w  w nw nw  n
        //  0  1  2  3  4  5  6  7  8  9 10 11 12 13 14 15
        )

        // Sliding constants - use Config where available, fallback to defaults
        // These are used in Sliding class which may not have direct Config access
        const val SLIDING_SPEED_VERTICAL_MULT = 0.5f

        // Helper functions to get configurable slider values
        @JvmStatic
        fun getSlidingSpeedSmoothing(): Float = Config.globalConfig().slider_speed_smoothing
        @JvmStatic
        fun getSlidingSpeedMax(): Float = Config.globalConfig().slider_speed_max

        /**
         * Maps 16-direction index to 8-direction SwipeDirection.
         * The 16-direction system divides circle into 22.5° slices starting from top (N).
         * Direction 0 = top/north, direction 4 = right/east, etc.
         */
        @JvmField
        // v1.2.2 FIX: Aligned with DIRECTION_TO_INDEX - dir 4 is E not SE
        val DIRECTION_TO_SWIPE_DIRECTION = arrayOf(
            SwipeDirection.N,   // 0 - top
            SwipeDirection.NE,  // 1 - top-right (upper)
            SwipeDirection.NE,  // 2 - top-right (lower)
            SwipeDirection.E,   // 3 - right (upper)
            SwipeDirection.E,   // 4 - right (lower) - was SE, now E for consistency
            SwipeDirection.SE,  // 5 - bottom-right (upper)
            SwipeDirection.SE,  // 6 - bottom-right (lower)
            SwipeDirection.S,   // 7 - bottom (right)
            SwipeDirection.S,   // 8 - bottom (left)
            SwipeDirection.SW,  // 9 - bottom-left (upper)
            SwipeDirection.SW,  // 10 - bottom-left (lower)
            SwipeDirection.W,   // 11 - left (lower)
            SwipeDirection.W,   // 12 - left (upper)
            SwipeDirection.NW,  // 13 - top-left (lower)
            SwipeDirection.NW,  // 14 - top-left (upper)
            SwipeDirection.N    // 15 - top (left side)
        )

        /**
         * Convert 16-direction to SwipeDirection enum.
         */
        @JvmStatic
        fun directionToSwipeDirection(direction: Int): SwipeDirection {
            return DIRECTION_TO_SWIPE_DIRECTION[direction and 0x0F]
        }
    }
}
