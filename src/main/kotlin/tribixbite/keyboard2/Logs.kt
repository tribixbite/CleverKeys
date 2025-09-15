package tribixbite.keyboard2

import android.util.Log

/**
 * Centralized logging system for CleverKeys
 * Kotlin object with configurable logging levels
 */
object Logs {
    
    private var debugEnabled = true
    private var verboseEnabled = false
    
    /**
     * Debug config migration
     */
    fun debug_config_migration(savedVersion: Int, currentVersion: Int) {
        Log.d("Config", "Migration: $savedVersion → $currentVersion")
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