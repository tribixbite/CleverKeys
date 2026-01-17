package tribixbite.cleverkeys

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Build.VERSION
import android.os.UserManager
import android.widget.Toast

class ClipboardHistoryService private constructor(ctx: Context) {
    private val _context: Context = ctx.applicationContext
    private val _database: ClipboardDatabase = ClipboardDatabase.getInstance(_context)
    private val _cm: ClipboardManager = _context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private var _pasteCallback: ClipboardPasteCallback? = null
    private var _listener: OnClipboardHistoryChange? = null
    private var _isListenerRegistered = false

    init {
        // Clean up expired entries on startup
        _database.cleanupExpiredEntries()

        // Note: Listener registration is deferred to attemptToRegisterListener()
        // which will be called from on_startup() and can be retried when keyboard gains focus
    }

    /**
     * Register clipboard listener for system-wide monitoring.
     * On Android 10+, being the default IME grants clipboard access even when keyboard is hidden.
     * This listener persists for the entire InputMethodService lifetime.
     * Should be called ONCE from InputMethodService.onCreate().
     */
    fun registerClipboardListener() {
        if (_isListenerRegistered) return

        // On Android 10+ (API 29+), being default IME grants system-wide clipboard access
        if (VERSION.SDK_INT >= 29 && !isDefaultIme()) {
            android.util.Log.w("ClipboardHistory", "Clipboard access requires this keyboard to be set as default input method")
            // User notification will be handled by settings UI showing clipboard status
            return
        }

        try {
            _cm.addPrimaryClipChangedListener(SystemListener())
            _isListenerRegistered = true
            android.util.Log.i("ClipboardHistory", "Clipboard listener registered for system-wide monitoring")

            // Add current clip in case it changed while listener was not active
            addCurrentClip()
        } catch (e: SecurityException) {
            _isListenerRegistered = false
            android.util.Log.e("ClipboardHistory", "Clipboard access denied: " + e.message)
        } catch (e: Exception) {
            _isListenerRegistered = false
            android.util.Log.e("ClipboardHistory", "Failed to register clipboard listener", e)
        }
    }

    /**
     * Unregister clipboard listener. Call from InputMethodService.onDestroy().
     */
    fun unregisterClipboardListener() {
        if (!_isListenerRegistered) return

        try {
            // Note: We cannot remove a specific listener instance, so this may not work as expected
            // The listener will be automatically cleaned up when the service process is destroyed
            android.util.Log.i("ClipboardHistory", "Clipboard listener cleanup on service destroy")
            _isListenerRegistered = false
        } catch (e: Exception) {
            android.util.Log.e("ClipboardHistory", "Error cleaning up clipboard listener", e)
        }
    }

    /**
     * Check if this keyboard is set as the default input method.
     * Required for clipboard access on Android 10+.
     */
    private fun isDefaultIme(): Boolean {
        return try {
            val defaultIme = android.provider.Settings.Secure.getString(
                _context.contentResolver,
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD
            )
            defaultIme != null && defaultIme.startsWith(_context.packageName)
        } catch (e: Exception) {
            android.util.Log.e("ClipboardHistory", "Failed to check default IME status", e)
            false
        }
    }

    /**
     * Get clipboard feature status for user feedback.
     * Returns status message indicating if clipboard monitoring is active.
     */
    fun getClipboardStatus(): String {
        if (!Config.globalConfig().clipboard_history_enabled)
            return "Clipboard history disabled in settings"

        if (!_isListenerRegistered) {
            if (VERSION.SDK_INT >= 29 && !isDefaultIme())
                return "Clipboard access requires setting this keyboard as default input method"
            return "Clipboard monitoring inactive - open keyboard to activate"
        }

        val activeEntries = _database.getActiveEntryCount()
        return String.format("Clipboard monitoring active (%d entries)", activeEntries)
    }

    fun clearExpiredAndGetHistory(): List<ClipboardEntry> {
        // Clean up expired entries and return active ones
        _database.cleanupExpiredEntries()
        return try {
            val entries = _database.getActiveClipboardEntries()
            // Issue #71: Limit number of entries to prevent TransactionTooLargeException
            // Display truncation is handled by the adapter (ClipboardHistoryView)
            // Full content is preserved for paste operations
            if (entries.size > MAX_DISPLAY_ENTRIES) {
                android.util.Log.w("ClipboardHistory", "Limiting clipboard display to $MAX_DISPLAY_ENTRIES entries")
                entries.take(MAX_DISPLAY_ENTRIES)
            } else {
                entries
            }
        } catch (e: Exception) {
            android.util.Log.e("ClipboardHistory", "Error retrieving clipboard history: ${e.message}")
            emptyList()
        }
    }

    /** This will call [on_clipboard_history_change]. */
    fun removeHistoryEntry(clip: String) {
        // Check if this is the most recent clipboard entry
        val currentHistory = _database.getActiveClipboardEntries()
        val isCurrentClip = currentHistory.isNotEmpty() && currentHistory[0].content == clip

        // If removing the current clipboard, clear the system clipboard
        if (isCurrentClip) {
            try {
                if (VERSION.SDK_INT >= 28)
                    _cm.clearPrimaryClip()
                else
                    _cm.setPrimaryClip(ClipData.newPlainText("", ""))
            } catch (e: SecurityException) {
                // Android 10+ may deny clipboard access when app is not in focus
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                    android.util.Log.d("ClipboardHistory", "Cannot clear clipboard (app not in focus): " + e.message)
                }
            }
        }

        // Remove from database
        val removed = _database.removeClipboardEntry(clip)
        if (removed)
            _listener?.on_clipboard_history_change()
    }

    /** Add clipboard entries to the history, skipping consecutive duplicates and
        empty strings. */
    fun addClip(clip: String?) {
        if (!Config.globalConfig().clipboard_history_enabled) return

        if (clip == null || clip.trim().isEmpty()) return

        // Check maximum item size limit
        val maxSizeKb = Config.globalConfig().clipboard_max_item_size_kb
        if (maxSizeKb > 0) {
            try {
                val sizeBytes = clip.toByteArray(java.nio.charset.StandardCharsets.UTF_8).size
                val maxSizeBytes = maxSizeKb * 1024

                if (sizeBytes > maxSizeBytes) {
                    // Item exceeds size limit - reject and notify user
                    android.util.Log.w("ClipboardHistory", "Clipboard item too large: $sizeBytes bytes (limit: $maxSizeBytes bytes)")

                    // Show toast notification to user
                    val message = String.format("Clipboard item too large (%d KB). Limit is %d KB.",
                        sizeBytes / 1024, maxSizeKb)
                    Toast.makeText(_context, message, Toast.LENGTH_LONG).show()
                    return // Don't add to clipboard history
                }
            } catch (e: Exception) {
                android.util.Log.e("ClipboardHistory", "Error checking clipboard item size: " + e.message)
                // Continue with add if size check fails
            }
        }

        // Calculate expiry time
        val expiryTime = System.currentTimeMillis() + HISTORY_TTL_MS

        // Add to database (handles duplicate detection automatically)
        val added = _database.addClipboardEntry(clip, expiryTime)

        if (added) {
            // Apply size limits if configured (based on limit type)
            val limitType = Config.globalConfig().clipboard_limit_type
            if ("size" == limitType) {
                // Apply size-based limit (total MB)
                val maxSizeMB = Config.globalConfig().clipboard_size_limit_mb
                if (maxSizeMB > 0) {
                    _database.applySizeLimitBytes(maxSizeMB)
                }
            } else {
                // Apply count-based limit (default)
                val maxHistorySize = Config.globalConfig().clipboard_history_limit
                if (maxHistorySize > 0) {
                    _database.applySizeLimit(maxHistorySize)
                }
            }

            _listener?.on_clipboard_history_change()
        }
    }

    fun clearHistory() {
        _database.clearAllEntries()
        _listener?.on_clipboard_history_change()
    }

    fun setOnClipboardHistoryChange(l: OnClipboardHistoryChange?) {
        _listener = l
    }

    /** Pin or unpin a clipboard entry to prevent it from expiring */
    fun setPinnedStatus(clip: String, isPinned: Boolean) {
        val updated = _database.setPinnedStatus(clip, isPinned)
        if (updated)
            _listener?.on_clipboard_history_change()
    }

    /** Get all pinned clipboard entries */
    fun getPinnedEntries(): List<ClipboardEntry> {
        return _database.getPinnedEntries()
    }

    /** Get statistics about clipboard storage */
    fun getStorageStats(): String {
        val stats = _database.getStorageStats()

        // Format size in human-readable format (KB/MB)
        val activeSize = formatBytes(stats.activeSizeBytes)
        val pinnedSize = formatBytes(stats.pinnedSizeBytes)

        // Build multi-line summary with active and pinned breakdown
        val sb = StringBuilder()
        sb.append(String.format("%d active entries (%s)", stats.activeEntries, activeSize))

        if (stats.pinnedEntries > 0) {
            sb.append(String.format("\n%d pinned (%s)", stats.pinnedEntries, pinnedSize))
        }

        return sb.toString()
    }

    /** Format bytes into human-readable string (KB or MB) */
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        }
    }

    fun interface OnClipboardHistoryChange {
        fun on_clipboard_history_change()
    }

    /**
     * Get the package name of the currently running foreground app.
     * Returns null if detection fails or permission not granted.
     */
    @Suppress("DEPRECATION")
    private fun getForegroundAppPackage(): String? {
        return try {
            if (VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                // Android 5.1+: Use UsageStatsManager (requires PACKAGE_USAGE_STATS permission)
                val usageStatsManager = _context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                if (usageStatsManager != null) {
                    val endTime = System.currentTimeMillis()
                    val startTime = endTime - 5000 // Last 5 seconds
                    val usageStats = usageStatsManager.queryUsageStats(
                        UsageStatsManager.INTERVAL_DAILY, startTime, endTime
                    )
                    if (!usageStats.isNullOrEmpty()) {
                        // Find the most recently used app
                        val recentApp = usageStats.maxByOrNull { it.lastTimeUsed }
                        if (recentApp != null && recentApp.lastTimeUsed > startTime) {
                            return recentApp.packageName
                        }
                    }
                }
            }

            // Fallback: Use ActivityManager (deprecated but works on older APIs)
            val activityManager = _context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (activityManager != null) {
                val runningTasks = activityManager.getRunningTasks(1)
                if (!runningTasks.isNullOrEmpty()) {
                    return runningTasks[0].topActivity?.packageName
                }
            }
            null
        } catch (e: SecurityException) {
            // Permission not granted - this is expected, silently return null
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                android.util.Log.d("ClipboardHistory", "Cannot detect foreground app: ${e.message}")
            }
            null
        } catch (e: Exception) {
            android.util.Log.w("ClipboardHistory", "Error detecting foreground app: ${e.message}")
            null
        }
    }

    /**
     * Check if the given package is a known password manager.
     */
    private fun isPasswordManagerApp(packageName: String?): Boolean {
        if (packageName == null) return false
        return Defaults.PASSWORD_MANAGER_PACKAGES.contains(packageName)
    }

    /** Add what is currently in the system clipboard into the history. */
    private fun addCurrentClip() {
        try {
            // Check if password manager exclusion is enabled
            if (Config.globalConfig().clipboard_exclude_password_managers) {
                val foregroundApp = getForegroundAppPackage()
                if (isPasswordManagerApp(foregroundApp)) {
                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                        android.util.Log.d("ClipboardHistory", "Skipping clipboard from password manager: $foregroundApp")
                    }
                    return // Don't store clipboard from password managers
                }
            }

            val clip = _cm.primaryClip ?: return
            val count = clip.itemCount
            for (i in 0 until count) {
                val text = clip.getItemAt(i).text
                if (text != null)
                    addClip(text.toString())
            }
        } catch (e: SecurityException) {
            // Android 10+ denies clipboard access when app is not in focus
            // This is expected behavior - we can only access clipboard when keyboard is visible
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                android.util.Log.d("ClipboardHistoryService", "Clipboard access denied (app not in focus): " + e.message)
            }
        }
    }

    inner class SystemListener : ClipboardManager.OnPrimaryClipChangedListener {
        override fun onPrimaryClipChanged() {
            addCurrentClip()
        }
    }

    // HistoryEntry class removed - now using SQLite database storage

    fun interface ClipboardPasteCallback {
        fun paste_from_clipboard_pane(content: String)
    }

    companion object {
        // Stored callback for deferred initialization
        private var _pendingCallback: ClipboardPasteCallback? = null
        private var _pendingContext: Context? = null

        /**
         * Check if user has unlocked the device (Direct Boot compatibility).
         */
        private fun isUserUnlocked(ctx: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= 24) {
                val userManager = ctx.getSystemService(Context.USER_SERVICE) as? UserManager
                userManager?.isUserUnlocked ?: true
            } else {
                true // Pre-N doesn't have Direct Boot
            }
        }

        /** Start the service on startup and start listening to clipboard changes.
         *  IMPORTANT: This should be called from InputMethodService.onCreate() to ensure
         *  system-wide clipboard monitoring for the entire service lifetime.
         *
         *  DIRECT BOOT: Clipboard history uses SQLite which requires Credential Encrypted
         *  storage. If the device is locked, initialization is deferred until user unlocks. */
        @JvmStatic
        fun on_startup(ctx: Context, cb: ClipboardPasteCallback) {
            if (isUserUnlocked(ctx)) {
                // Device is unlocked, initialize immediately
                initializeService(ctx, cb)
            } else {
                // Device is locked, defer initialization
                android.util.Log.i("ClipboardHistory", "Device locked - deferring clipboard initialization")
                _pendingCallback = cb
                _pendingContext = ctx.applicationContext
                DirectBootManager.getInstance(ctx).registerUnlockCallback {
                    android.util.Log.i("ClipboardHistory", "Device unlocked - initializing clipboard service")
                    _pendingContext?.let { context ->
                        _pendingCallback?.let { callback ->
                            initializeService(context, callback)
                        }
                    }
                    _pendingCallback = null
                    _pendingContext = null
                }
            }
        }

        /**
         * Actually initialize the clipboard service (called when user is unlocked).
         */
        private fun initializeService(ctx: Context, cb: ClipboardPasteCallback) {
            val service = get_service(ctx)
            if (service != null) {
                service._pasteCallback = cb
                // Register listener immediately on service startup for system-wide monitoring
                service.registerClipboardListener()
            }
        }

        /** Cleanup and unregister listener. Call from InputMethodService.onDestroy(). */
        @JvmStatic
        fun on_shutdown() {
            _service?.unregisterClipboardListener()
        }

        /** Start the service if it hasn't been started before. Returns [null] if the
            feature is unsupported. */
        @JvmStatic
        fun get_service(ctx: Context): ClipboardHistoryService? {
            if (VERSION.SDK_INT <= 11) return null
            if (_service == null)
                _service = ClipboardHistoryService(ctx)
            return _service
        }

        @JvmStatic
        fun set_history_enabled(e: Boolean) {
            Config.globalConfig().set_clipboard_history_enabled(e)
            if (_service == null) return

            if (e) {
                // Re-enable: add current clip and re-register listener if needed
                _service!!.addCurrentClip()
                _service!!.registerClipboardListener()
            }
            // NOTE: When disabling, we DO NOT clear history data
            // This preserves user data and allows re-enabling without data loss
            // History will simply stop recording new clipboard changes
        }

        /** Send the given string to the editor. */
        @JvmStatic
        fun paste(clip: String) {
            if (_service != null && _service!!._pasteCallback != null)
                _service!!._pasteCallback!!.paste_from_clipboard_pane(clip)
            else
                android.util.Log.w("ClipboardHistory", "Cannot paste - callback not initialized")
        }

        /** Clipboard history is persistently stored in SQLite database and survives app restarts.
            Entries expire after HISTORY_TTL_MS unless pinned. The configurable size limit
            (clipboard_history_limit) controls maximum entries (0 = unlimited). */
        /** Time in ms until history entries expire.
         *  Set to 7 days to maintain useful history across app updates and restarts.
         *  Use pinning for permanent entries. */
        const val HISTORY_TTL_MS = 7 * 24 * 60 * 60 * 1000L // 7 days

        /** Issue #71: Maximum entries to display at once to prevent TransactionTooLargeException.
         *  Android Binder has ~1MB limit; large clipboard lists can exceed this during IPC. */
        private const val MAX_DISPLAY_ENTRIES = 100

        private var _service: ClipboardHistoryService? = null

        // Deprecated snake_case aliases for Java compatibility
        @Deprecated("Use on_startup", ReplaceWith("on_startup(ctx, cb)"))
        @JvmStatic
        fun onStartup(ctx: Context, cb: ClipboardPasteCallback) = on_startup(ctx, cb)

        @Deprecated("Use on_shutdown", ReplaceWith("on_shutdown()"))
        @JvmStatic
        fun onShutdown() = on_shutdown()

        @Deprecated("Use get_service", ReplaceWith("get_service(ctx)"))
        @JvmStatic
        fun getService(ctx: Context) = get_service(ctx)

        @Deprecated("Use set_history_enabled", ReplaceWith("set_history_enabled(e)"))
        @JvmStatic
        fun setHistoryEnabled(e: Boolean) = set_history_enabled(e)
    }
}
