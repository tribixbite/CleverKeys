package tribixbite.cleverkeys

import java.util.ArrayDeque
import kotlin.math.sqrt

/**
 * Pure (android-free) key-registration filter for swipe gestures — the single place that
 * decides whether the key under the current touch sample joins the gesture's key path.
 * Extracted from [ImprovedSwipeGestureRecognizer.registerKeyWithFiltering] (ARC-112) so the
 * registration decision is testable on the pure JVM: the recognizer itself cannot be
 * instantiated there (android.graphics.PointF / android.util.Log are "Stub!" throws), and its
 * wall-clock coupling makes dense-sampling scenarios untestable deterministically.
 *
 * Filter gates, in order (all preserved from the original):
 *  1. Same key as the last registration — a finger resting on a key must not re-register it
 *     on every touch sample.
 *  2. Dwell/velocity — a sample that arrives faster than [minDwellTimeMs] while the finger is
 *     moving above [highVelocityThreshold] is "just passing through" a key on the way to
 *     another; it must not register.
 *  3. Recent-duplicate window — a key already registered within the last
 *     [duplicateCheckWindow] registrations is jitter back onto old ground, not a new letter.
 *  4. Minimum travel ([minKeyDistance]) — the finger must have moved at least this far from
 *     the point where the PREVIOUS key was registered. This suppresses boundary chatter: a
 *     finger hovering on the seam between two keys oscillates a few px and would otherwise
 *     register the neighbour as a genuine new letter.
 *
 * ARC-112 fix: gate 4 originally compared [minKeyDistance] against the PER-SAMPLE step
 * length. On a high-report-rate digitizer a slow smooth swipe produces steps of a few px, so
 * once a key was registered no later sample could ever clear the gate — the whole gesture
 * registered one key and died silently downstream. The gate now measures straight-line
 * displacement from the last REGISTRATION POINT, which is invariant under sample density
 * while still enforcing the same "must genuinely travel away before a new key counts"
 * property (boundary jitter stays within [minKeyDistance] of the registration point, so it
 * is still filtered).
 *
 * Thresholds are lambdas because the recognizer reads them from live [Config]; tests supply
 * constants.
 */
class SwipeKeyRegistrar<K : Any>(
    private val minKeyDistance: () -> Float,
    private val minDwellTimeMs: () -> Long,
    private val highVelocityThreshold: () -> Float,
    private val duplicateCheckWindow: Int = DEFAULT_DUPLICATE_CHECK_WINDOW,
) {

    companion object {
        /** Check the last 5 registrations for duplicates (historic recognizer constant). */
        const val DEFAULT_DUPLICATE_CHECK_WINDOW = 5
    }

    /**
     * Keys registered so far, in order. Exposed mutably: the recognizer's endpoint
     * stabilization rewrites the first/last entry in place after the gesture ends.
     */
    val touchedKeys: MutableList<K> = ArrayList()

    /** The most recently registered key, or null before the first registration. */
    var lastRegisteredKey: K? = null
        private set

    private val recentKeys = ArrayDeque<K>()

    /** Position of the last registration; only meaningful while [lastRegisteredKey] != null. */
    private var lastRegisteredX = 0f
    private var lastRegisteredY = 0f

    /**
     * Unconditionally register the gesture's starting key (validity is the caller's
     * concern — the recognizer only forwards alphabetic keys).
     */
    fun registerStartKey(key: K, x: Float, y: Float) {
        register(key, x, y)
    }

    /**
     * Offer the key under the current touch sample at ([x], [y]).
     * [timeDeltaMs] is the time since the previous accepted sample; [velocityPxPerSec] the
     * instantaneous finger velocity. Returns true iff the key was registered.
     */
    fun offer(key: K, x: Float, y: Float, timeDeltaMs: Long, velocityPxPerSec: Float): Boolean {
        // Gate 1: same as last registered key.
        if (key == lastRegisteredKey) {
            return false
        }

        // Gate 2: moving too fast to dwell — just passing through.
        if (timeDeltaMs < minDwellTimeMs() && velocityPxPerSec > highVelocityThreshold()) {
            return false
        }

        // Gate 3: recently registered — jitter back onto old ground.
        if (recentKeys.contains(key)) {
            return false
        }

        // Gate 4 (ARC-112 basis): minimum straight-line travel from the point where the
        // LAST key was registered — never the per-sample step, so sample density cannot
        // starve registration.
        if (lastRegisteredKey != null) {
            val dx = x - lastRegisteredX
            val dy = y - lastRegisteredY
            if (sqrt(dx * dx + dy * dy) < minKeyDistance()) {
                return false
            }
        }

        register(key, x, y)
        return true
    }

    private fun register(key: K, x: Float, y: Float) {
        touchedKeys.add(key)
        lastRegisteredKey = key
        lastRegisteredX = x
        lastRegisteredY = y
        recentKeys.offer(key)
        if (recentKeys.size > duplicateCheckWindow) {
            recentKeys.poll()
        }
    }

    /** Clear all gesture state for a new swipe. */
    fun reset() {
        touchedKeys.clear()
        recentKeys.clear()
        lastRegisteredKey = null
        lastRegisteredX = 0f
        lastRegisteredY = 0f
    }
}
