package tribixbite.keyboard2

import android.util.Log
import android.util.LogPrinter
import android.view.inputmethod.EditorInfo

/**
 * Centralized logging system for CleverKeys
 * Kotlin object with configurable logging levels
 *
 * Bug #87-89 fixes: Added TAG constant, debug_startup_input_view(), trace()
 */
object Logs {

    // Bug #87 fix: TAG constant for consistent logging
    const val TAG = "tribixbite.keyboard2"

    private var debugEnabled = true
    private var verboseEnabled = false
    private var debugLogs: LogPrinter? = null
    
    /**
     * Set debug logging with LogPrinter for detailed output.
     * Used for startup debugging and detailed tracing.
     */
    fun set_debug_logs(enabled: Boolean) {
        debugLogs = if (enabled) LogPrinter(Log.DEBUG, TAG) else null
        debugEnabled = enabled
    }

    /**
     * Debug startup input view information.
     * Bug #88 fix: Logs EditorInfo details for input debugging.
     */
    fun debug_startup_input_view(info: EditorInfo, conf: Config) {
        val printer = debugLogs ?: return

        info.dump(printer, "")
        if (info.extras != null) {
            printer.println("extras: ${info.extras}")
        }
        printer.println("swapEnterActionKey: ${conf.swapEnterActionKey}")
        printer.println("actionLabel: ${conf.actionLabel}")
    }

    /**
     * Debug config migration
     */
    fun debug_config_migration(savedVersion: Int, currentVersion: Int) {
        debug("Migrating config version from $savedVersion to $currentVersion")
    }

    /**
     * Generic debug message (used by other debug functions).
     */
    fun debug(message: String) {
        debugLogs?.println(message)
    }

    /**
     * Print stack trace for debugging.
     * Bug #89 fix: Trace method for stack trace logging.
     */
    fun trace() {
        debugLogs?.println(Log.getStackTraceString(Exception()))
    }

    /**
     * Enable/disable debug logging
     */
    fun setDebugEnabled(enabled: Boolean) {
        debugEnabled = enabled
    }
    
    /**
     * Enable/disable verbose logging  
     */
    fun setVerboseEnabled(enabled: Boolean) {
        verboseEnabled = enabled
    }
    
    /**
     * Log debug message
     */
    fun d(tag: String, message: String) {
        if (debugEnabled) {
            Log.d(tag, message)
        }
    }
    
    /**
     * Log error message
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
    }
    
    /**
     * Log warning message
     */
    fun w(tag: String, message: String) {
        Log.w(tag, message)
    }
    
    /**
     * Log info message
     */
    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }
    
    /**
     * Log verbose message
     */
    fun v(tag: String, message: String) {
        if (verboseEnabled) {
            Log.v(tag, message)
        }
    }
}