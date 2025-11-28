package tribixbite.keyboard2

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Sticky keys manager for modifier latching and locking
 * Enables single-handed typing and supports users with motor impairments
 * Fix for Bug #373 (CATASTROPHIC): NO sticky keys - violates ADA/WCAG
 */
class StickyKeysManager(
    private val context: Context,
    private val prefs: SharedPreferences
) {

    companion object {
        private const val TAG = "StickyKeysManager"
        private const val PREF_STICKY_KEYS_ENABLED = "sticky_keys_enabled"
        private const val PREF_STICKY_TIMEOUT_MS = "sticky_keys_timeout_ms"
        private const val DEFAULT_TIMEOUT_MS = 5000L // 5 seconds
    }

    /**
     * Modifier state
     */
    enum class ModifierState {
        OFF,      // Not active
        LATCHED,  // Active for next keypress only (single press)
        LOCKED    // Active until turned off (double press)
    }

    /**
     * Modifier tracking
     */
    private data class ModifierTracking(
        var state: ModifierState = ModifierState.OFF,
        var lastPressTime: Long = 0L
    )

    // State tracking for each modifier
    private val modifiers = mutableMapOf<KeyValue.Modifier, ModifierTracking>()
    private val handler = Handler(Looper.getMainLooper())

    // Callbacks
    private var onModifierStateChanged: ((KeyValue.Modifier, ModifierState) -> Unit)? = null
    private var onVisualFeedback: ((KeyValue.Modifier, ModifierState) -> Unit)? = null

    /**
     * Initialize sticky keys manager
     */
    fun initialize() {
        // Initialize all supported modifiers
        KeyValue.Modifier.values().forEach { modifier ->
            modifiers[modifier] = ModifierTracking()
        }

        Log.d(TAG, "Sticky keys manager initialized (enabled: ${isStickyKeysEnabled()})")
    }

    /**
     * Check if sticky keys is enabled
     */
    fun isStickyKeysEnabled(): Boolean {
        return prefs.getBoolean(PREF_STICKY_KEYS_ENABLED, false)
    }

    /**
     * Set sticky keys enabled state
     */
    fun setStickyKeysEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_STICKY_KEYS_ENABLED, enabled).apply()

        if (!enabled) {
            // Clear all latched/locked modifiers
            clearAllModifiers()
        }

        Log.d(TAG, "Sticky keys ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Get sticky keys timeout
     */
    fun getStickyKeysTimeout(): Long {
        return prefs.getLong(PREF_STICKY_TIMEOUT_MS, DEFAULT_TIMEOUT_MS)
    }

    /**
     * Set sticky keys timeout
     */
    fun setStickyKeysTimeout(timeoutMs: Long) {
        prefs.edit().putLong(PREF_STICKY_TIMEOUT_MS, timeoutMs).apply()
        Log.d(TAG, "Sticky keys timeout set to ${timeoutMs}ms")
    }

    /**
     * Handle modifier key press
     * Returns true if handled by sticky keys, false otherwise
     */
    fun handleModifierPress(modifier: KeyValue.Modifier): Boolean {
        if (!isStickyKeysEnabled()) {
            return false // Let normal modifier handling take over
        }

        val tracking = modifiers[modifier] ?: return false
        val now = System.currentTimeMillis()
        val timeSinceLastPress = now - tracking.lastPressTime

        // Detect double-press (within 500ms)
        val isDoublePressModifier = modifier == KeyValue.Modifier.SHIFT ||
                                   modifier == KeyValue.Modifier.CTRL ||
                                   modifier == KeyValue.Modifier.ALT

        Log.d(TAG, "Modifier press: $modifier, current state: ${tracking.state}, time since last: ${timeSinceLastPress}ms")

        when (tracking.state) {
            ModifierState.OFF -> {
                // First press - latch the modifier
                tracking.state = ModifierState.LATCHED
                tracking.lastPressTime = now
                notifyStateChanged(modifier, ModifierState.LATCHED)

                // Schedule auto-release after timeout
                scheduleModifierRelease(modifier)

                Log.d(TAG, "$modifier LATCHED")
            }

            ModifierState.LATCHED -> {
                if (timeSinceLastPress < 500 && isDoublePressModifier) {
                    // Double-press detected - lock the modifier
                    tracking.state = ModifierState.LOCKED
                    tracking.lastPressTime = now
                    cancelScheduledRelease(modifier)
                    notifyStateChanged(modifier, ModifierState.LOCKED)
                    Log.d(TAG, "$modifier LOCKED (double-press)")
                } else {
                    // Single press while latched - turn off
                    tracking.state = ModifierState.OFF
                    cancelScheduledRelease(modifier)
                    notifyStateChanged(modifier, ModifierState.OFF)
                    Log.d(TAG, "$modifier turned OFF")
                }
            }

            ModifierState.LOCKED -> {
                // Press while locked - unlock
                tracking.state = ModifierState.OFF
                notifyStateChanged(modifier, ModifierState.OFF)
                Log.d(TAG, "$modifier UNLOCKED")
            }
        }

        return true // Handled by sticky keys
    }

    /**
     * Handle regular key press (consumes latched modifiers)
     */
    fun handleRegularKeyPress() {
        if (!isStickyKeysEnabled()) {
            return
        }

        // Consume all latched modifiers (not locked)
        modifiers.entries.forEach { (modifier, tracking) ->
            if (tracking.state == ModifierState.LATCHED) {
                tracking.state = ModifierState.OFF
                cancelScheduledRelease(modifier)
                notifyStateChanged(modifier, ModifierState.OFF)
                Log.d(TAG, "$modifier consumed (latched → off)")
            }
        }
    }

    /**
     * Get current modifier state
     */
    fun getModifierState(modifier: KeyValue.Modifier): ModifierState {
        return modifiers[modifier]?.state ?: ModifierState.OFF
    }

    /**
     * Check if modifier is active (latched or locked)
     */
    fun isModifierActive(modifier: KeyValue.Modifier): Boolean {
        val state = getModifierState(modifier)
        return state == ModifierState.LATCHED || state == ModifierState.LOCKED
    }

    /**
     * Get all active modifiers
     */
    fun getActiveModifiers(): List<KeyValue.Modifier> {
        return modifiers.entries
            .filter { it.value.state != ModifierState.OFF }
            .map { it.key }
    }

    /**
     * Clear all modifiers
     */
    fun clearAllModifiers() {
        modifiers.entries.forEach { (modifier, tracking) ->
            if (tracking.state != ModifierState.OFF) {
                tracking.state = ModifierState.OFF
                cancelScheduledRelease(modifier)
                notifyStateChanged(modifier, ModifierState.OFF)
            }
        }
        Log.d(TAG, "All modifiers cleared")
    }

    /**
     * Set callback for modifier state changes
     */
    fun setOnModifierStateChangedListener(callback: (KeyValue.Modifier, ModifierState) -> Unit) {
        onModifierStateChanged = callback
    }

    /**
     * Set callback for visual feedback (for updating key appearance)
     */
    fun setOnVisualFeedbackListener(callback: (KeyValue.Modifier, ModifierState) -> Unit) {
        onVisualFeedback = callback
    }

    /**
     * Schedule auto-release for latched modifier
     */
    private fun scheduleModifierRelease(modifier: KeyValue.Modifier) {
        val timeout = getStickyKeysTimeout()

        handler.postDelayed({
            val tracking = modifiers[modifier] ?: return@postDelayed

            if (tracking.state == ModifierState.LATCHED) {
                tracking.state = ModifierState.OFF
                notifyStateChanged(modifier, ModifierState.OFF)
                Log.d(TAG, "$modifier auto-released after ${timeout}ms")
            }
        }, timeout)
    }

    /**
     * Cancel scheduled release for modifier
     */
    private fun cancelScheduledRelease(modifier: KeyValue.Modifier) {
        handler.removeCallbacksAndMessages(null) // Cancel all scheduled releases
    }

    /**
     * Notify modifier state change
     */
    private fun notifyStateChanged(modifier: KeyValue.Modifier, state: ModifierState) {
        onModifierStateChanged?.invoke(modifier, state)
        onVisualFeedback?.invoke(modifier, state)
    }

    /**
     * Get state description for logging/debugging
     */
    fun getStateDescription(): String {
        val activeModifiers = modifiers.entries
            .filter { it.value.state != ModifierState.OFF }
            .map { (mod, track) -> "${mod.name}:${track.state}" }

        return if (activeModifiers.isEmpty()) {
            "No active modifiers"
        } else {
            "Active: ${activeModifiers.joinToString(", ")}"
        }
    }

    /**
     * Get statistics for accessibility settings
     */
    data class StickyKeysStats(
        val enabled: Boolean,
        val timeoutMs: Long,
        val activeModifiers: List<Pair<KeyValue.Modifier, ModifierState>>
    )

    fun getStats(): StickyKeysStats {
        return StickyKeysStats(
            enabled = isStickyKeysEnabled(),
            timeoutMs = getStickyKeysTimeout(),
            activeModifiers = modifiers.entries
                .filter { it.value.state != ModifierState.OFF }
                .map { it.key to it.value.state }
        )
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        clearAllModifiers()
        onModifierStateChanged = null
        onVisualFeedback = null
        Log.d(TAG, "Sticky keys manager cleaned up")
    }
}
