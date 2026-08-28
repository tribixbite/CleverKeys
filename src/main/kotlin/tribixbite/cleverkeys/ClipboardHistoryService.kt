package tribixbite.cleverkeys

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION
import android.os.UserManager
import android.widget.Toast
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tribixbite.cleverkeys.clipboard.sanitize.SanitizationConfig
import tribixbite.cleverkeys.clipboard.sanitize.systemClipboardRewrite
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class ClipboardHistoryService private constructor(ctx: Context) {
    private val _context: Context = ctx.applicationContext
    private val _database: ClipboardDatabase = ClipboardDatabase.getInstance(_context)
    private val _cm: ClipboardManager = _context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private var _pasteCallback: ClipboardPasteCallback? = null
    private var _listener: OnClipboardHistoryChange? = null
    private var _isListenerRegistered = false
    private var _systemListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    // Coroutine scope for IO-dispatched clipboard reads (survives entire service lifetime)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Media manager for clipboard media file storage and thumbnails
    private val _mediaManager: ClipboardMediaManager by lazy { ClipboardMediaManager(_context) }

    // URL sanitizer config — lazy because Config may not be initialised at construction
    private val _sanitizationConfig: SanitizationConfig by lazy {
        SanitizationConfig(_context)
    }

    /**
     * Receives [SettingsActivity.ACTION_SANITIZATION_RULES_CHANGED] from the settings
     * UI (toggle change, custom-rules import). Drops the cached sanitizer so the next
     * clipboard insert rebuilds it from current Config + on-disk custom file.
     */
    private val _sanitizationRulesReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == SettingsActivity.ACTION_SANITIZATION_RULES_CHANGED) {
                // Refresh the live Config BEFORE dropping the cache: the settings UI persists
                // the toggle to SharedPreferences but never pushes it into the in-memory Config,
                // so build() would otherwise re-read the stale startup value (sanitizer would
                // appear to do nothing until a full keyboard restart).
                try {
                    Config.globalConfig().reloadSanitizationSettings()
                } catch (e: Exception) {
                    android.util.Log.w("ClipboardHistory", "Config not ready on sanitization rules change", e)
                }
                _sanitizationConfig.rebuild()
            }
        }
    }
    private var _sanitizationReceiverRegistered = false

    init {
        // Listen for sanitization toggle/file changes so the cached ruleset stays fresh.
        // Mirrors the dictionary-import receiver pattern used by DictionaryManagerActivity.
        // Defensive: a receiver-registration failure (e.g. LocalBroadcastManager unavailable in an
        // edge/headless context) must NOT abort service construction — the core clipboard store
        // still works; only the live sanitization-rules refresh is lost until next process start.
        try {
            LocalBroadcastManager.getInstance(_context).registerReceiver(
                _sanitizationRulesReceiver,
                IntentFilter(SettingsActivity.ACTION_SANITIZATION_RULES_CHANGED)
            )
            _sanitizationReceiverRegistered = true
        } catch (e: Throwable) {
            android.util.Log.w("ClipboardHistory", "Sanitization-rules receiver not registered: ${e.message}")
        }
    }

    init {
        // Handle expired entries based on current user setting
        val ttlMs = getHistoryTtlMs()
        if (ttlMs == Long.MAX_VALUE) {
            // User has "never expire" — rescue entries with stale expiry timestamps
            // from when the default was 7 days. Don't delete anything by time.
            _database.rescueExpiredEntries()
        } else {
            // User has a finite duration — clean up expired entries and orphaned media
            val (_, expiredMediaPaths) = _database.cleanupExpiredEntries()
            for (path in expiredMediaPaths) {
                if (!_database.isMediaPathReferenced(path)) {
                    _mediaManager.deleteMedia(path)
                }
            }
        }

        // Reconcile media files with DB on startup — delete orphan files not in any table
        serviceScope.launch(Dispatchers.IO) {
            val referencedPaths = _database.getAllReferencedMediaPaths()
            _mediaManager.cleanupOrphans(referencedPaths)
        }

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
            val listener = SystemListener()
            _cm.addPrimaryClipChangedListener(listener)
            _systemListener = listener
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
     * Properly removes the stored listener instance to prevent memory leaks
     * (SystemListener is an inner class holding a reference to this service).
     */
    fun unregisterClipboardListener() {
        // Always tear down the sanitization broadcast receiver, even if the system
        // clipboard listener never registered (e.g. non-default IME path).
        if (_sanitizationReceiverRegistered) {
            try {
                LocalBroadcastManager.getInstance(_context)
                    .unregisterReceiver(_sanitizationRulesReceiver)
            } catch (e: Exception) {
                android.util.Log.w("ClipboardHistory", "Error unregistering sanitization receiver", e)
            }
            _sanitizationReceiverRegistered = false
        }

        if (!_isListenerRegistered) return

        try {
            _systemListener?.let { _cm.removePrimaryClipChangedListener(it) }
            _systemListener = null
            _isListenerRegistered = false
            android.util.Log.i("ClipboardHistory", "Clipboard listener unregistered")
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
        return String.format(java.util.Locale.ROOT, "Clipboard monitoring active (%d entries)", activeEntries)
    }

    fun clearExpiredAndGetHistory(): List<ClipboardEntry> {
        // Only run time-based cleanup when user has a finite duration set
        val ttlMs = getHistoryTtlMs()
        if (ttlMs != Long.MAX_VALUE) {
            val (_, expiredMediaPaths) = _database.cleanupExpiredEntries()
            expiredMediaPaths.forEach { mediaPath ->
                if (!_database.isMediaPathReferenced(mediaPath)) {
                    _mediaManager.deleteMedia(mediaPath)
                }
            }
        }
        return try {
            val entries = _database.getActiveClipboardEntries()
            // Issue #71: Original 100-entry limit was for TransactionTooLargeException prevention,
            // but since ClipboardHistoryView accesses service in-process (no IPC), this isn't needed.
            // Respect user's configured limit (0 = unlimited, otherwise use their setting)
            val configLimit = Config.globalConfig().clipboard_history_limit
            if (configLimit > 0 && entries.size > configLimit) {
                entries.take(configLimit)
            } else {
                entries  // Return all entries (unlimited)
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

        // Remove from database — returns media_path if entry had associated media file
        val mediaPath = _database.removeClipboardEntry(clip)
        // Clean up the media file if no other table still references it
        if (mediaPath != null && !_database.isMediaPathReferenced(mediaPath)) {
            _mediaManager.deleteMedia(mediaPath)
        }
        _listener?.on_clipboard_history_change()
    }

    /** Add clipboard entries to the history, skipping consecutive duplicates and
        empty strings. */
    fun addClip(clip: String?) {
        if (!Config.globalConfig().clipboard_history_enabled) return
        // OS-listener path: sanitize + optionally rewrite the OS clipboard, non-private insert.
        storeClip(clip, rewriteOsClipboard = true, isPrivate = false, sourcePackage = null)
    }

    /**
     * #156 private-copy write path. Stores [text] directly into the clipboard DB marked private,
     * and — critically — NEVER calls [systemClipboardRewrite]/setPrimaryClip. That is the whole
     * security point: the plaintext must not reach the OS clipboard. Enforced by review and by
     * PrivateCopyServiceTest's `verify(exactly = 0) { setPrimaryClip(any()) }`.
     *
     * Own gate (Decision #4): unlike [addClip], this works even when `clipboard_history_enabled`
     * is false — that pref governs OS-clipboard *monitoring*, and a privacy-focused user may want
     * monitoring off with private copy as the only capture route.
     */
    fun addPrivateClip(text: String?, sourcePackage: String?) {
        storeClip(text, rewriteOsClipboard = false, isPrivate = true, sourcePackage = sourcePackage)
    }

    /**
     * Shared core of [addClip] and [addPrivateClip]. The two paths differ ONLY in:
     *   - [rewriteOsClipboard]: the private path passes `false`, so the [systemClipboardRewrite]
     *     branch (the only setPrimaryClip caller here) is structurally unreachable for it.
     *   - [isPrivate] / [sourcePackage]: threaded into the DB insert.
     * Size-cap, sanitize, insert and pruning are identical (store hygiene applies to both).
     */
    private fun storeClip(
        clip: String?,
        rewriteOsClipboard: Boolean,
        isPrivate: Boolean,
        sourcePackage: String?
    ) {
        if (clip == null || clip.trim().isEmpty()) return

        // Null-safe Config reads: storeClip is reachable on a cold-start exported-activity path
        // (PROCESS_TEXT / editing-key) where the IME never ran, so the global Config may be
        // uninitialized. Fall back to the documented defaults rather than throwing (matches
        // getHistoryTtlMs). PrivateCopyProcessTextActivity inits Config eagerly, but this keeps the
        // service self-consistent for any future cold-start caller.
        val config = Config.globalConfigOrNull()

        // Check maximum item size limit
        val maxSizeKb = config?.clipboard_max_item_size_kb ?: Defaults.CLIPBOARD_MAX_ITEM_SIZE_KB_FALLBACK
        if (maxSizeKb > 0) {
            try {
                val sizeBytes = clip.toByteArray(java.nio.charset.StandardCharsets.UTF_8).size
                val maxSizeBytes = maxSizeKb * 1024

                if (sizeBytes > maxSizeBytes) {
                    // Item exceeds size limit - reject and notify user
                    android.util.Log.w("ClipboardHistory", "Clipboard item too large: $sizeBytes bytes (limit: $maxSizeBytes bytes)")

                    // Show toast notification to user. Shares the string resource with the
                    // PROCESS_TEXT entry point (Finding 10) so both surfaces show identical wording.
                    val message = _context.getString(
                        R.string.private_copy_too_large, sizeBytes / 1024, maxSizeKb)
                    Toast.makeText(_context, message, Toast.LENGTH_LONG).show()
                    return // Don't add to clipboard history
                }
            } catch (e: Exception) {
                android.util.Log.e("ClipboardHistory", "Error checking clipboard item size: " + e.message)
                // Continue with add if size check fails
            }
        }

        // Calculate expiry time from user-configured duration (minutes, -1 = never expire)
        val ttlMs = getHistoryTtlMs()
        val expiryTime = if (ttlMs == Long.MAX_VALUE) Long.MAX_VALUE else System.currentTimeMillis() + ttlMs

        // URL sanitization (text/plain only). No-op when all three toggles are off.
        // Sanitizing the *stored* content is store hygiene — applies to private entries too.
        val processed = _sanitizationConfig.sanitizer().process(clip)

        // OS-clipboard rewrite: ONLY on the OS-listener path. If sanitization actually cleaned
        // the URL and the user opted in, overwrite the Android system clipboard so pastes from ANY
        // app deliver the sanitized URL. Idempotent (re-fired listener re-sanitizes → no loop).
        // #156 SECURITY INVARIANT: the private path passes rewriteOsClipboard=false, so this
        // setPrimaryClip-bearing branch never runs for a private copy — plaintext never reaches
        // the OS clipboard. See [systemClipboardRewrite].
        if (rewriteOsClipboard) {
            systemClipboardRewrite(
                original = clip,
                processed = processed,
                enabled = Config.globalConfig().clipboard_sanitize_system_clipboard,
            )?.let { rewriteSystemClipboard(it) }
        }

        // Add to database (handles duplicate detection + sticky-privacy merge automatically)
        val added = _database.addClipboardEntry(processed, expiryTime, isPrivate, sourcePackage)

        if (added) {
            // Apply size limits if configured (based on limit type). Null-safe: same cold-start
            // rationale as the size cap above — fall back to the documented defaults.
            val limitType = config?.clipboard_limit_type ?: Defaults.CLIPBOARD_LIMIT_TYPE
            if ("size" == limitType) {
                // Apply size-based limit (total MB — includes text + thumbnails + media files)
                val maxSizeMB = config?.clipboard_size_limit_mb ?: Defaults.CLIPBOARD_SIZE_LIMIT_MB_FALLBACK
                if (maxSizeMB > 0) {
                    val (_, mediaPaths) = _database.applySizeLimitBytes(maxSizeMB, _context.filesDir)
                    // Delete media files of pruned entries (only if no other table references them)
                    for (path in mediaPaths) {
                        if (!_database.isMediaPathReferenced(path)) _mediaManager.deleteMedia(path)
                    }
                }
            } else {
                // Apply count-based limit (default)
                val maxHistorySize = config?.clipboard_history_limit ?: Defaults.CLIPBOARD_HISTORY_LIMIT_FALLBACK
                if (maxHistorySize > 0) {
                    _database.applySizeLimit(maxHistorySize)
                }
            }

            _listener?.on_clipboard_history_change()
        }
    }

    /**
     * Replace the system clipboard's primary clip with [sanitized] (the cleaned URL text).
     *
     * Posted to the main thread: `setPrimaryClip` requires a Looper thread and [addClip] may
     * run on `Dispatchers.IO` for content-URI text (see [processClipUri]); `serviceScope` is
     * `Dispatchers.Main.immediate`. Best-effort — Android 10+ throws [SecurityException] when
     * the IME isn't focused, swallowed exactly like the read path in [addCurrentClip].
     *
     * Note: the original clip's label/metadata is not preserved (we only receive the text).
     * For sanitized URLs a neutral label is fine.
     */
    private fun rewriteSystemClipboard(sanitized: String) {
        serviceScope.launch {
            try {
                _cm.setPrimaryClip(ClipData.newPlainText("CleverKeys", sanitized))
            } catch (e: SecurityException) {
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                    android.util.Log.d("ClipboardHistory",
                        "Cannot rewrite system clipboard (app not in focus): " + e.message)
                }
            } catch (e: Exception) {
                android.util.Log.w("ClipboardHistory", "System clipboard rewrite failed: " + e.message)
            }
        }
    }

    fun clearHistory() {
        val result = _database.clearAllEntries()
        // Clean up media files that are no longer referenced by any table
        result.getOrNull()?.second?.forEach { mediaPath ->
            if (!_database.isMediaPathReferenced(mediaPath)) {
                _mediaManager.deleteMedia(mediaPath)
            }
        }
        _listener?.on_clipboard_history_change()
    }

    fun setOnClipboardHistoryChange(l: OnClipboardHistoryChange?) {
        _listener = l
    }

    /** Pin a clipboard entry (copies to independent pinned_entries table). Returns true if new, false if duplicate. */
    fun pinEntry(clip: String, createdTimestamp: Long = System.currentTimeMillis(),
                 mimeType: String = ClipboardEntry.MIME_TEXT_PLAIN,
                 thumbnailBlob: ByteArray? = null, mediaPath: String? = null): Boolean {
        // #156 COPY semantics: preserve the private marker + provenance on the pinned copy.
        val (isPrivate, sourcePackage) = _database.getPrivateMarker(clip)
        val added = _database.pinEntry(clip, createdTimestamp, mimeType, thumbnailBlob, mediaPath, isPrivate, sourcePackage)
        if (added) _listener?.on_clipboard_history_change()
        return added
    }

    /** Unpin a clipboard entry (removes from pinned_entries; history copy unaffected) */
    fun unpinEntry(clip: String) {
        val mediaPath = _database.unpinEntry(clip)
        if (mediaPath != null && !_database.isMediaPathReferenced(mediaPath)) {
            _mediaManager.deleteMedia(mediaPath)
        }
        _listener?.on_clipboard_history_change()
    }

    /** Check if content is pinned */
    fun isPinned(clip: String): Boolean = _database.isPinned(clip)

    /** Add content to todo list (copies to independent todo_entries table).
     * @return true if added, false if already a todo (duplicate) */
    fun addToTodo(clip: String, createdTimestamp: Long = System.currentTimeMillis(),
                  mimeType: String = ClipboardEntry.MIME_TEXT_PLAIN,
                  thumbnailBlob: ByteArray? = null, mediaPath: String? = null): Boolean {
        // #156 COPY semantics: preserve the private marker + provenance on the todo copy.
        val (isPrivate, sourcePackage) = _database.getPrivateMarker(clip)
        val added = _database.addTodoEntry(clip, createdTimestamp, mimeType, thumbnailBlob, mediaPath, isPrivate, sourcePackage)
        if (added) _listener?.on_clipboard_history_change()
        return added
    }

    /** Remove content from todo list (removes from todo_entries; history copy unaffected) */
    fun removeFromTodo(clip: String) {
        val mediaPath = _database.removeTodoEntry(clip)
        if (mediaPath != null && !_database.isMediaPathReferenced(mediaPath)) {
            _mediaManager.deleteMedia(mediaPath)
        }
        _listener?.on_clipboard_history_change()
    }

    /** Update todo entry status (active/planned/completed) */
    fun setTodoStatus(clip: String, status: String) {
        val updated = _database.setTodoEntryStatus(clip, status)
        if (updated) _listener?.on_clipboard_history_change()
    }

    /**
     * Edit the content of a clipboard entry in-place (inline edit).
     * Routes to the correct database table based on [tab].
     * Validates size limit. Content is compared and stored EXACTLY (UT-4):
     * whitespace/newline-only changes are real edits and persist verbatim.
     * COPY semantics: only the entry in the specified tab is modified;
     * copies in other tabs are unaffected.
     *
     * @return EditEntryResult indicating success, duplicate conflict, or error
     */
    fun editEntryContent(oldContent: String, newContent: String, tab: ClipboardTab): EditEntryResult {
        // Shared exact-comparison policy (no trim — see ClipboardEditPolicy KDoc)
        when (ClipboardEditPolicy.decide(oldContent, newContent)) {
            is ClipboardEditPolicy.Decision.NoOp -> return EditEntryResult.Success
            is ClipboardEditPolicy.Decision.Invalid -> return EditEntryResult.InvalidContent
            is ClipboardEditPolicy.Decision.Apply -> { /* fall through to size check + DB write */ }
        }

        // Validate size limit
        val maxSizeKb = Config.globalConfig().clipboard_max_item_size_kb
        if (maxSizeKb > 0) {
            val sizeBytes = newContent.toByteArray(java.nio.charset.StandardCharsets.UTF_8).size
            if (sizeBytes > maxSizeKb * 1024) {
                return EditEntryResult.InvalidContent
            }
        }

        // Route to correct table (exact strings — DB layer stores newContent verbatim)
        val result = when (tab) {
            ClipboardTab.HISTORY -> _database.updateHistoryEntryContent(oldContent, newContent)
            ClipboardTab.PINNED -> _database.updatePinnedEntryContent(oldContent, newContent)
            ClipboardTab.TODOS -> _database.updateTodoEntryContent(oldContent, newContent)
        }

        if (result is EditEntryResult.Success) {
            _listener?.on_clipboard_history_change()
        }
        return result
    }

    /** Get all pinned clipboard entries */
    fun getPinnedEntries(): List<ClipboardEntry> {
        return _database.getPinnedEntries()
    }

    // ─── Tag management wrappers (database methods exist, service exposes + notifies) ───

    /** Set tags for a pinned entry. Returns true if updated. */
    fun setPinnedTags(clip: String, tags: List<String>): Boolean {
        val updated = _database.setPinnedEntryTags(clip, tags)
        if (updated) _listener?.on_clipboard_history_change()
        return updated
    }

    /** Set tags for a todo entry. Returns true if updated. */
    fun setTodoTags(clip: String, tags: List<String>): Boolean {
        val updated = _database.setTodoEntryTags(clip, tags)
        if (updated) _listener?.on_clipboard_history_change()
        return updated
    }

    /** Get all unique tags across pinned entries */
    fun getAllPinnedTags(): Set<String> = _database.getAllPinnedTags()

    /** Get all unique tags across todo entries */
    fun getAllTodoTags(): Set<String> = _database.getAllTodoTags()

    /** Get statistics about clipboard storage */
    fun getStorageStats(): String {
        val stats = _database.getStorageStats()

        // Format size in human-readable format (KB/MB)
        val activeSize = formatBytes(stats.activeSizeBytes)
        val pinnedSize = formatBytes(stats.pinnedSizeBytes)

        // Build multi-line summary with active and pinned breakdown
        val sb = StringBuilder()
        sb.append(String.format(java.util.Locale.ROOT, "%d active entries (%s)", stats.activeEntries, activeSize))

        if (stats.pinnedEntries > 0) {
            sb.append(String.format(java.util.Locale.ROOT, "\n%d pinned (%s)", stats.pinnedEntries, pinnedSize))
        }

        return sb.toString()
    }

    /** Format bytes into human-readable string (KB or MB) */
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0)
            else -> String.format(java.util.Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0))
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

    /**
     * Add what is currently in the system clipboard into the history.
     *
     * Reads ClipData metadata on the main thread (fast Binder IPC for small metadata),
     * then dispatches URI content streaming to Dispatchers.IO to prevent ANR.
     *
     * When an item has text, it goes through addClip() as before.
     * When an item has a content:// URI instead:
     * - text MIME: stream text via ContentResolver.openInputStream (bypasses Binder limit)
     * - media MIME: save file via ClipboardMediaManager, store thumbnail in DB
     */
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

            // #86: Android 13+ (API 33): Respect IS_SENSITIVE flag set by password managers
            // This is a more robust detection than package blocklisting
            if (Config.globalConfig().clipboard_respect_sensitive_flag &&
                VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val extras = clip.description?.extras
                if (extras != null) {
                    val isSensitive = extras.getBoolean("android.content.extra.IS_SENSITIVE", false)
                    if (isSensitive) {
                        if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                            android.util.Log.d("ClipboardHistory", "Skipping sensitive clipboard content (IS_SENSITIVE flag)")
                        }
                        return // Don't store sensitive content
                    }
                }
            }

            val count = clip.itemCount
            for (i in 0 until count) {
                val item = clip.getItemAt(i)
                val text = item.text

                if (text != null) {
                    // Standard text content — handle synchronously (already on main, fast)
                    addClip(text.toString())
                } else if (item.uri != null) {
                    // Content URI — dispatch to IO thread to prevent ANR
                    // Android grants temp read access while IME is active; read promptly
                    val uri = item.uri
                    serviceScope.launch(Dispatchers.IO) {
                        processClipUri(uri)
                    }
                }
            }
        } catch (e: SecurityException) {
            // Android 10+ denies clipboard access when app is not in focus
            // This is expected behavior - we can only access clipboard when keyboard is visible
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                android.util.Log.d("ClipboardHistoryService", "Clipboard access denied (app not in focus): " + e.message)
            }
        } catch (e: Exception) {
            // Catches TransactionTooLargeException (Binder IPC limit ~1MB) and other
            // unexpected exceptions from _cm.primaryClip. The clipboard content is too
            // large to cross the Binder boundary — our per-item size limit (clipboard_max_item_size_kb)
            // cannot help because the data never reaches our code.
            android.util.Log.w("ClipboardHistoryService",
                "Clipboard read failed (${e.javaClass.simpleName}): ${e.message?.take(100)}")
        }
    }

    /**
     * Process a content:// URI from the clipboard on IO thread.
     *
     * Routes by MIME type:
     * - text: stream to String, add via addClip() (bypasses Binder IPC limit for large text)
     * - media: save file + thumbnail via ClipboardMediaManager, add via addMediaClip()
     */
    private fun processClipUri(uri: Uri) {
        try {
            if (!Config.globalConfig().clipboard_history_enabled) return

            val mimeType = _context.contentResolver.getType(uri) ?: "application/octet-stream"

            if (mimeType.startsWith("text/")) {
                // Text content via URI — stream directly (bypasses Binder ~1MB limit)
                val text = readTextFromUri(uri)
                if (text != null) {
                    addClip(text)
                }
            } else {
                // Media content (image, video, PDF, etc.) — skip in text-only or media-disabled mode
                val cfg = Config.globalConfig()
                if (cfg.clipboard_text_only || !cfg.clipboard_media_enabled) return

                val maxMediaBytes = Config.globalConfig().clipboard_max_media_size_mb * 1024L * 1024L
                val result = _mediaManager.saveMedia(uri, mimeType, maxMediaBytes) ?: return

                addMediaClip(
                    content = result.displayName,
                    mimeType = result.mimeType,
                    thumbnailBlob = result.thumbnailBlob,
                    mediaPath = result.mediaPath,
                    contentHash = result.contentHash
                )
            }
        } catch (e: SecurityException) {
            // URI permission may have expired (clipboard changed before we read)
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                android.util.Log.d("ClipboardHistory", "URI permission expired: ${e.message}")
            }
        } catch (e: Exception) {
            android.util.Log.w("ClipboardHistory", "Failed to process clip URI: ${e.message}")
        }
    }

    /**
     * Stream text content from a content:// URI.
     * Respects clipboard_max_item_size_kb limit. Returns null if stream fails or exceeds limit.
     */
    private fun readTextFromUri(uri: Uri): String? {
        val maxSizeKb = Config.globalConfig().clipboard_max_item_size_kb
        val maxSizeBytes = if (maxSizeKb > 0) maxSizeKb * 1024 else Int.MAX_VALUE

        return try {
            val inputStream = _context.contentResolver.openInputStream(uri) ?: return null
            inputStream.use { stream ->
                val reader = InputStreamReader(stream, StandardCharsets.UTF_8)
                val sb = StringBuilder()
                val buffer = CharArray(4096)
                var charsRead = reader.read(buffer)
                while (charsRead != -1) {
                    sb.append(buffer, 0, charsRead)
                    // Approximate byte check (UTF-8 can be 1-4 bytes per char)
                    if (sb.length * 2 > maxSizeBytes) {
                        android.util.Log.w("ClipboardHistory",
                            "Text URI content too large (>${maxSizeKb}KB), truncating")
                        return sb.substring(0, maxSizeBytes / 2).toString()
                    }
                    charsRead = reader.read(buffer)
                }
                val result = sb.toString().trim()
                if (result.isEmpty()) null else result
            }
        } catch (e: Exception) {
            android.util.Log.w("ClipboardHistory", "Failed to read text from URI: ${e.message}")
            null
        }
    }

    /**
     * Add a media clipboard entry (image, video, PDF, etc.) to the database.
     * Uses content hash for dedup instead of String.hashCode().
     */
    private fun addMediaClip(
        content: String,
        mimeType: String,
        thumbnailBlob: ByteArray?,
        mediaPath: String,
        contentHash: String
    ) {
        if (!Config.globalConfig().clipboard_history_enabled) return

        val ttlMs = getHistoryTtlMs()
        val expiryTime = if (ttlMs == Long.MAX_VALUE) Long.MAX_VALUE else System.currentTimeMillis() + ttlMs

        val added = _database.addMediaClipboardEntry(
            content = content,
            expiryTimestamp = expiryTime,
            mimeType = mimeType,
            thumbnailBlob = thumbnailBlob,
            mediaPath = mediaPath,
            contentHash = contentHash
        )

        if (added) {
            // Apply size limits (includes text + thumbnails + media files on disk)
            val limitType = Config.globalConfig().clipboard_limit_type
            if ("size" == limitType) {
                val maxSizeMB = Config.globalConfig().clipboard_size_limit_mb
                if (maxSizeMB > 0) {
                    val (_, mediaPaths) = _database.applySizeLimitBytes(maxSizeMB, _context.filesDir)
                    for (path in mediaPaths) {
                        if (!_database.isMediaPathReferenced(path)) _mediaManager.deleteMedia(path)
                    }
                }
            } else {
                val maxHistorySize = Config.globalConfig().clipboard_history_limit
                if (maxHistorySize > 0) {
                    _database.applySizeLimit(maxHistorySize)
                }
            }

            _listener?.on_clipboard_history_change()
        }
    }

    inner class SystemListener : ClipboardManager.OnPrimaryClipChangedListener {
        override fun onPrimaryClipChanged() {
            addCurrentClip()
        }
    }

    // HistoryEntry class removed - now using SQLite database storage

    interface ClipboardPasteCallback {
        fun paste_from_clipboard_pane(content: String)
        /**
         * Paste media content via commitContent (API 25+). Returns true if successful.
         *
         * [isPrivate] threads the entry's #156 private marker to the implementation: for a
         * private entry the system-clipboard fallback MUST fail rather than fall back
         * (design §5.6 / ARC-001) — putting the media URI on the OS clipboard is exactly
         * the exposure private copy exists to prevent.
         */
        fun paste_media_from_clipboard_pane(
            mimeType: String,
            mediaPath: String,
            isPrivate: Boolean,
        ): Boolean = false
    }

    companion object {
        // Stored callback for deferred initialization
        private var _pendingCallback: ClipboardPasteCallback? = null
        // Only ever holds ctx.applicationContext (assigned in on_startup) and is nulled after
        // deferred init runs — never retains an Activity/Service context.
        @SuppressLint("StaticFieldLeak")
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
            feature is unsupported. Thread-safe via double-checked locking. */
        @JvmStatic
        fun get_service(ctx: Context): ClipboardHistoryService? {
            // minSdk 21 always exceeds the old API<=11 unsupported floor, so no gate is needed.
            return _service ?: synchronized(this) {
                _service ?: ClipboardHistoryService(ctx).also { _service = it }
            }
        }

        /**
         * #156 entry point for both the in-IME "Private copy" action and the PROCESS_TEXT activity.
         * Resolves (or lazily constructs) the singleton service and stores [text] privately.
         * Safe from a bare activity context — the constructor does NOT register the OS-clipboard
         * listener, so this never accidentally starts clipboard monitoring. Returns true on success.
         *
         * NEVER touches the OS clipboard (delegates to [addPrivateClip], the no-setPrimaryClip path).
         */
        @JvmStatic
        fun privateCopy(ctx: Context, text: String?, sourcePackage: String?): Boolean {
            if (text.isNullOrEmpty()) return false
            val service = get_service(ctx) ?: run {
                android.util.Log.w("ClipboardHistory", "privateCopy: service unavailable (unsupported SDK)")
                return false
            }
            service.addPrivateClip(text, sourcePackage)
            return true
        }

        @JvmStatic
        fun set_history_enabled(e: Boolean) {
            Config.globalConfig().set_clipboard_history_enabled(e)
            if (_service == null) return

            if (e) {
                // Re-enable: rescue stale entries if "never expire" is set, then re-register
                if (getHistoryTtlMs() == Long.MAX_VALUE) {
                    _service!!._database.rescueExpiredEntries()
                }
                _service!!.addCurrentClip()
                _service!!.registerClipboardListener()
            }
            // NOTE: When disabling, we DO NOT clear history data
            // This preserves user data and allows re-enabling without data loss
            // History will simply stop recording new clipboard changes
        }

        /** Send the given string to the editor (text entries). */
        @JvmStatic
        fun paste(clip: String) {
            if (_service != null && _service!!._pasteCallback != null)
                _service!!._pasteCallback!!.paste_from_clipboard_pane(clip)
            else
                android.util.Log.w("ClipboardHistory", "Cannot paste - callback not initialized")
        }

        /** Send media content to the editor via commitContent (v4 media entries). */
        @JvmStatic
        fun pasteMedia(mimeType: String, mediaPath: String, isPrivate: Boolean): Boolean {
            val cb = _service?._pasteCallback
            if (cb == null) {
                android.util.Log.w("ClipboardHistory", "Cannot paste media - callback not initialized")
                return false
            }
            return cb.paste_media_from_clipboard_pane(mimeType, mediaPath, isPrivate)
        }

        /**
         * Called from SettingsActivity when the user changes the Entry Duration slider.
         * Re-reads the duration from Config and rescues entries with stale expiry timestamps
         * if the new duration is "never expire". This ensures mid-session changes take
         * effect immediately without requiring a keyboard restart.
         */
        @JvmStatic
        fun onDurationSettingChanged() {
            if (_service == null) return
            // Re-read fresh config from SharedPreferences
            Config.globalConfig().reloadClipboardDuration()
            val ttlMs = getHistoryTtlMs()
            if (ttlMs == Long.MAX_VALUE) {
                _service!!._database.rescueExpiredEntries()
            }
        }

        /** Clipboard history is persistently stored in SQLite database and survives app restarts.
            Entries expire based on clipboard_history_duration config (default: never expire) unless pinned.
            The configurable size limit (clipboard_history_limit) controls maximum entries (0 = unlimited). */
        /** Compute the TTL in ms from user-configured clipboard_history_duration (minutes).
         *  -1 = never expire (Long.MAX_VALUE). Default = -1 (never expire). */
        @JvmStatic
        fun getHistoryTtlMs(): Long {
            // Null-safe: the service can construct on a cold-start exported-activity path
            // (PROCESS_TEXT / editing-key) where the IME never ran, so Config isn't initialized.
            // Fall back to the documented default (-1 = never expire) rather than throwing.
            val durationMinutes = Config.globalConfigOrNull()?.clipboard_history_duration ?: -1
            return if (durationMinutes >= 0) {
                java.util.concurrent.TimeUnit.MINUTES.toMillis(durationMinutes.toLong())
            } else {
                Long.MAX_VALUE
            }
        }

        // Process-lifetime singleton. The service's own constructor stores only
        // ctx.applicationContext (see `_context`), so this never leaks a shorter-lived context.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var _service: ClipboardHistoryService? = null

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
