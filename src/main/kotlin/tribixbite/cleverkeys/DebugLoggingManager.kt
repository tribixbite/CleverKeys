package tribixbite.cleverkeys

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
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
 *   adb shell am broadcast -a tribixbite.cleverkeys.SET_DEBUG_MODE --ez debug_enabled true
 *
 * Responsibilities:
 * - File-based logging (the writer opens on first use)
 * - Register/unregister broadcast receiver for debug mode control
 * - Send debug messages to SwipeDebugActivity
 * - Propagate debug mode changes to components (SuggestionHandler, via DebugModePropagator)
 *
 * NOT included (remains in CleverKeysService):
 * - Lifecycle management (onCreate/onDestroy calls)
 * - Component reference management (SuggestionHandler)
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
        /** Debug log lines leave the IME only while [editorPackage] is CleverKeys itself ([ownPackage]). */
        @JvmStatic
        fun forwardsLogsFor(editorPackage: String?, ownPackage: String): Boolean = editorPackage == ownPackage

        private const val DEBUG_MODE_ACTION = "tribixbite.cleverkeys.SET_DEBUG_MODE"
        private const val EXTRA_DEBUG_ENABLED = "debug_enabled"
        private const val LOG_FILE_NAME = "swipe_log.txt"
        private const val TAG = "DebugLoggingManager"

        /**
         * I-8 (comprehensive audit 2026-09-06): signature-protected permission gating the
         * SET_DEBUG_MODE receiver on API 24-25, where RECEIVER_NOT_EXPORTED is unavailable.
         * Declared in AndroidManifest.xml with `protectionLevel="signature"` and requested
         * via `<uses-permission>`, so only apps signed with this app's certificate (i.e.
         * this app itself) can deliver the broadcast. Without it, ANY installed app could
         * flip debug mode on those API levels — silently enabling the playground
         * recording path (I-1) with every consent toggle off.
         *
         * The name is `<applicationId>.permission.SET_DEBUG_MODE`, matching the manifest's
         * `${applicationId}` placeholder: `tribixbite.cleverkeys.permission.SET_DEBUG_MODE`
         * in release (unchanged since v1.5.0) and `tribixbite.cleverkeys.debug.…` in debug,
         * so the two differently signed packages never define the same permission and can
         * be installed side by side.
         */
        const val PERMISSION_SET_DEBUG_MODE = BuildConfig.APPLICATION_ID + ".permission.SET_DEBUG_MODE"
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

    // App-private storage path — always writable and portable across devices/UIDs.
    // (Was previously hardcoded to a Termux home path that only worked on the dev's box.)
    private val logFilePath: String = java.io.File(context.filesDir, LOG_FILE_NAME).absolutePath

    private var logWriter: BufferedWriter? = null
    private var debugModeReceiver: BroadcastReceiver? = null
    private var debugMode: Boolean = false
    private val debugModeListeners = mutableListOf<DebugModeListener>()

    /**
     * The swipe-analysis log writer, opened on first use (review 2026-11-14, low): the service used
     * to open it and append a "Started" line on every create, for a sink nothing wrote to, so the
     * file only ever grew and an fd was held for the service's lifetime. Null when it cannot open
     * (file logging is then silently off).
     */
    private fun openLogWriter(): BufferedWriter? = logWriter ?: try {
        BufferedWriter(FileWriter(logFilePath, true)).also { logWriter = it }
    } catch (e: IOException) {
        null
    }

    /**
     * Register broadcast receiver for debug mode control.
     *
     * The receiver listens for SET_DEBUG_MODE broadcasts and updates debug mode state.
     * When debug mode changes, all registered listeners are notified.
     *
     * @param context Application context for receiver registration
     */
    // App-private on every API level through PrivateReceivers: the not-exported flag is
    // honoured only from API 33, so below it the signature permission is the I-8 protection
    // (review 2026-11-14 M3 — the old O guard left API 26-32 exported).
    fun registerDebugModeReceiver(context: Context) {
        if (debugModeReceiver != null) return // Already registered

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (DEBUG_MODE_ACTION == intent.action) {
                    val newDebugMode = intent.getBooleanExtra(EXTRA_DEBUG_ENABLED, false)
                    setDebugMode(newDebugMode)
                }
            }
        }

        debugModeReceiver = receiver
        val filter = IntentFilter(DEBUG_MODE_ACTION)
        // A dynamic receiver without protection receives matching implicit broadcasts from ANY
        // installed app ("exported=false protects only manifest components" — I-8). Below API
        // 33 delivery then requires the SENDER to hold [PERMISSION_SET_DEBUG_MODE], which only
        // same-signature apps can, so the `adb shell am broadcast` toggle works there only via
        // the in-app playground, mirroring the NOT_EXPORTED posture on 33+.
        PrivateReceivers.register(context, receiver, filter)
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
        // Only while CleverKeys' own field (the debug playground) is being edited: in split-screen
        // the debug screen stays started while the user types in ANOTHER app, and cursor-sync
        // lines carry the text around the cursor (review 2026-11-14 M4).
        val editorPackage = (context as? android.inputmethodservice.InputMethodService)
            ?.currentInputEditorInfo?.packageName
        if (!forwardsLogsFor(editorPackage, packageName)) return

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
     * Opens the log on first use; a no-op when it cannot be opened.
     *
     * @param message Message to write to log file
     */
    fun writeToLogFile(message: String) {
        val writer = openLogWriter() ?: return
        try {
            writer.write(message)
            writer.flush()
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
    fun getLogFilePath(): String = logFilePath
}
