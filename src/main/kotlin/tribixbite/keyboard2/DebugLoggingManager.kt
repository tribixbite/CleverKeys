package tribixbite.keyboard2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import java.io.BufferedWriter
import java.io.FileWriter
import java.io.IOException

/**
 * Manages debug logging infrastructure for Unexpected Keyboard.
 *
 * This class centralizes:
 * - Swipe analysis log file writing
 * - Debug mode broadcast receiver management
 * - Debug log message broadcasting to SwipeDebugActivity
 * - Debug mode propagation to dependent components
 *
 * The debug logging system has two parts:
 * 1. File logging: Persistent logs written to swipe_log.txt for swipe analysis
 * 2. Broadcast logging: Real-time logs sent to SwipeDebugActivity when debug mode is active
 *
 * Debug mode can be toggled at runtime by sending a broadcast:
 *   adb shell am broadcast -a tribixbite.keyboard2.SET_DEBUG_MODE --ez debug_enabled true
 *
 * Responsibilities:
 * - Initialize log writer for file-based logging
 * - Register/unregister broadcast receiver for debug mode control
 * - Send debug messages to SwipeDebugActivity
 * - Propagate debug mode changes to components (SuggestionHandler, NeuralLayoutHelper)
 *
 * NOT included (remains in CleverKeysService):
 * - Lifecycle management (onCreate/onDestroy calls)
 * - Component reference management (SuggestionHandler, NeuralLayoutHelper)
 * - Actual component initialization
 *
 * This utility is extracted from CleverKeysService.java for better code organization
 * and testability (v1.32.384).
 *
 * @since v1.32.384
 */
class DebugLoggingManager(
    private val context: Context,
    private val packageName: String
) {
    companion object {
        private const val DEBUG_MODE_ACTION = "tribixbite.keyboard2.SET_DEBUG_MODE"
        private const val EXTRA_DEBUG_ENABLED = "debug_enabled"
        private const val LOG_FILE_PATH = "/data/data/com.termux/files/home/swipe_log.txt"
        private const val TAG = "DebugLoggingManager"
    }

    /**
     * Callback interface for debug mode changes.
     */
    interface DebugModeListener {
        /**
         * Called when debug mode is enabled or disabled.
         *
         * @param enabled True if debug mode is now enabled, false otherwise
         */
        fun onDebugModeChanged(enabled: Boolean)
    }

    private var logWriter: BufferedWriter? = null
    private var debugModeReceiver: BroadcastReceiver? = null
    private var debugMode: Boolean = false
    private val debugModeListeners = mutableListOf<DebugModeListener>()

    /**
     * Initialize log writer for swipe analysis.
     *
     * Creates/opens the log file and writes a startup message.
     * If initialization fails, file logging will be silently disabled.
     *
     * @return True if log writer was initialized successfully, false otherwise
     */
    fun initializeLogWriter(): Boolean {
        try {
            logWriter = BufferedWriter(FileWriter(LOG_FILE_PATH, true))
            logWriter?.write("\n=== CleverKeysService Started: ${java.util.Date()} ===\n")
            logWriter?.flush()
            return true
        } catch (e: IOException) {
            // Silently fail if log file can't be created
            logWriter = null
            return false
        }
    }

    /**
     * Register broadcast receiver for debug mode control.
     *
     * The receiver listens for SET_DEBUG_MODE broadcasts and updates debug mode state.
     * When debug mode changes, all registered listeners are notified.
     *
     * @param context Application context for receiver registration
     */
    fun registerDebugModeReceiver(context: Context) {
        if (debugModeReceiver != null) return // Already registered

        debugModeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (DEBUG_MODE_ACTION == intent.action) {
                    val newDebugMode = intent.getBooleanExtra(EXTRA_DEBUG_ENABLED, false)
                    setDebugMode(newDebugMode)
                }
            }
        }

        val filter = IntentFilter(DEBUG_MODE_ACTION)
        context.registerReceiver(debugModeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    /**
     * Unregister broadcast receiver for debug mode control.
     *
     * Should be called in onDestroy() to prevent memory leaks.
     */
    fun unregisterDebugModeReceiver(context: Context) {
        if (debugModeReceiver != null) {
            try {
                context.unregisterReceiver(debugModeReceiver)
            } catch (e: Exception) {
                // Already unregistered
            }
            debugModeReceiver = null
        }
    }

    /**
     * Set debug mode and notify all listeners.
     *
     * @param enabled True to enable debug mode, false to disable
     */
    private fun setDebugMode(enabled: Boolean) {
        debugMode = enabled

        if (debugMode) {
            sendDebugLog("=== Debug mode enabled ===\n")
        }

        // Notify all listeners
        debugModeListeners.forEach { it.onDebugModeChanged(enabled) }
    }

    /**
     * Register a debug mode change listener.
     *
     * @param listener Listener to be notified of debug mode changes
     */
    fun registerDebugModeListener(listener: DebugModeListener) {
        if (!debugModeListeners.contains(listener)) {
            debugModeListeners.add(listener)
        }
    }

    /**
     * Unregister a debug mode change listener.
     *
     * @param listener Listener to be removed
     */
    fun unregisterDebugModeListener(listener: DebugModeListener) {
        debugModeListeners.remove(listener)
    }

    /**
     * Send debug log message to SwipeDebugActivity if debug mode is enabled.
     *
     * Only logs when debug mode is active (SwipeDebugActivity is open).
     * Messages are sent via broadcast and displayed in real-time.
     *
     * @param message Debug message to send
     */
    fun sendDebugLog(message: String) {
        if (!debugMode) return

        try {
            val intent = Intent(SwipeDebugActivity.ACTION_DEBUG_LOG)
            intent.setPackage(packageName) // Explicit package for broadcast
            intent.putExtra(SwipeDebugActivity.EXTRA_LOG_MESSAGE, message)
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            // Silently fail if debug activity is not available
        }
    }

    /**
     * Write message to log file.
     *
     * For persistent logging of swipe analysis data.
     * If log writer is not initialized, this is a no-op.
     *
     * @param message Message to write to log file
     */
    fun writeToLogFile(message: String) {
        try {
            logWriter?.write(message)
            logWriter?.flush()
        } catch (e: IOException) {
            // Silently fail if logging fails
        }
    }

    /**
     * Close log writer and release resources.
     *
     * Should be called in onDestroy().
     */
    fun close() {
        try {
            logWriter?.close()
        } catch (e: IOException) {
            // Ignore close errors
        }
        logWriter = null
    }

    /**
     * Check if debug mode is currently enabled.
     *
     * @return True if debug mode is enabled, false otherwise
     */
    fun isDebugMode(): Boolean = debugMode

    /**
     * Get log file path.
     *
     * @return Absolute path to the log file
     */
    fun getLogFilePath(): String = LOG_FILE_PATH
}
