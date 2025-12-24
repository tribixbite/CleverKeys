package tribixbite.cleverkeys

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.UserManager
import android.util.Log

/**
 * Manages Direct Boot compatibility for the keyboard.
 *
 * Direct Boot allows the keyboard to run before the user unlocks their device,
 * which is essential for typing passwords on the lock screen. However, some
 * storage types are not available until after the first unlock:
 *
 * - Device Encrypted (DE) storage: Available immediately after boot
 * - Credential Encrypted (CE) storage: Available only after user unlock
 *
 * This manager handles:
 * 1. Detecting whether the device is unlocked
 * 2. Deferring initialization of CE-dependent (PII) components until unlock
 * 3. Providing DE storage access for non-sensitive preferences
 *
 * Usage:
 * ```kotlin
 * val manager = DirectBootManager.getInstance(context)
 *
 * // For non-sensitive prefs (themes, settings)
 * val prefs = manager.getDeviceProtectedPreferences("my_prefs")
 *
 * // For PII components (dictionary, personalization)
 * if (manager.isUserUnlocked) {
 *     initializePiiComponents()
 * } else {
 *     manager.registerUnlockCallback { initializePiiComponents() }
 * }
 * ```
 *
 * @since v1.1.75 - Direct Boot crash fix
 */
class DirectBootManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "DirectBootManager"

        @Volatile
        private var instance: DirectBootManager? = null

        fun getInstance(context: Context): DirectBootManager {
            return instance ?: synchronized(this) {
                instance ?: DirectBootManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val unlockCallbacks = mutableListOf<() -> Unit>()
    private var unlockReceiver: BroadcastReceiver? = null
    private var _isUserUnlocked: Boolean = false

    init {
        // Check initial unlock state
        _isUserUnlocked = checkUserUnlocked()
        Log.d(TAG, "DirectBootManager initialized, userUnlocked=$_isUserUnlocked")
    }

    /**
     * Whether the user has unlocked the device at least once since boot.
     * When false, only Device Encrypted (DE) storage is available.
     */
    val isUserUnlocked: Boolean
        get() = _isUserUnlocked

    /**
     * Check if the user is currently unlocked.
     */
    private fun checkUserUnlocked(): Boolean {
        return if (Build.VERSION.SDK_INT >= 24) {
            val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
            userManager?.isUserUnlocked ?: true
        } else {
            // Pre-N devices don't have Direct Boot, always "unlocked"
            true
        }
    }

    /**
     * Get SharedPreferences from Device Encrypted (DE) storage.
     * These preferences are available before user unlock.
     *
     * IMPORTANT: Only use for non-sensitive, non-PII data like:
     * - Theme preferences
     * - UI settings
     * - Model version info
     *
     * Do NOT use for:
     * - User dictionaries
     * - Learned words
     * - Clipboard history
     * - Any personalization data
     */
    fun getDeviceProtectedPreferences(name: String): SharedPreferences {
        return if (Build.VERSION.SDK_INT >= 24) {
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences(name, Context.MODE_PRIVATE)
        } else {
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
        }
    }

    /**
     * Register a callback to be invoked when the user unlocks the device.
     * If already unlocked, the callback is invoked immediately.
     *
     * @param callback Function to call when device is unlocked
     */
    fun registerUnlockCallback(callback: () -> Unit) {
        if (_isUserUnlocked) {
            // Already unlocked, invoke immediately
            Log.d(TAG, "User already unlocked, invoking callback immediately")
            callback()
            return
        }

        synchronized(unlockCallbacks) {
            unlockCallbacks.add(callback)
        }

        // Register receiver if not already registered
        if (unlockReceiver == null) {
            registerUnlockReceiver()
        }
    }

    /**
     * Register broadcast receiver for user unlock event.
     */
    private fun registerUnlockReceiver() {
        if (Build.VERSION.SDK_INT < 24) return

        unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_UNLOCKED) {
                    Log.i(TAG, "User unlocked, invoking ${unlockCallbacks.size} callbacks")
                    _isUserUnlocked = true

                    // Invoke all callbacks
                    val callbacks: List<() -> Unit>
                    synchronized(unlockCallbacks) {
                        callbacks = unlockCallbacks.toList()
                        unlockCallbacks.clear()
                    }

                    callbacks.forEach { callback ->
                        try {
                            callback()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in unlock callback", e)
                        }
                    }

                    // Unregister receiver
                    unregisterUnlockReceiver()
                }
            }
        }

        val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(unlockReceiver, filter)
        }
        Log.d(TAG, "Registered unlock receiver")
    }

    /**
     * Unregister the unlock broadcast receiver.
     */
    private fun unregisterUnlockReceiver() {
        unlockReceiver?.let {
            try {
                context.unregisterReceiver(it)
                Log.d(TAG, "Unregistered unlock receiver")
            } catch (e: IllegalArgumentException) {
                // Already unregistered, ignore
            }
            unlockReceiver = null
        }
    }

    /**
     * Clean up resources. Call from InputMethodService.onDestroy().
     */
    fun cleanup() {
        unregisterUnlockReceiver()
        synchronized(unlockCallbacks) {
            unlockCallbacks.clear()
        }
    }
}
