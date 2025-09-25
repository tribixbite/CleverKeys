package tribixbite.keyboard2

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Modern Kotlin clipboard history service with reactive programming patterns.
 *
 * Features:
 * - Coroutine-based async operations for non-blocking UI
 * - Flow-based reactive updates for real-time history changes
 * - SQLite persistence with automatic cleanup
 * - Pin/unpin functionality for important clips
 * - Configurable TTL and size limits
 * - Thread-safe operations with mutex protection
 */
object ClipboardHistoryService {

    /** Maximum time entries stay in history (5 minutes) */
    const val HISTORY_TTL_MS = 5L * 60 * 1000

    // Internal state
    @Volatile
    private var _service: ClipboardHistoryServiceImpl? = null
    @Volatile
    private var _pasteCallback: ClipboardPasteCallback? = null

    private val serviceMutex = Mutex()

    /**
     * Initialize the service on app startup and begin listening to clipboard changes.
     * @param ctx Application context
     * @param cb Callback for paste operations
     */
    suspend fun onStartup(ctx: Context, cb: ClipboardPasteCallback) {
        serviceMutex.withLock {
            getService(ctx)
            _pasteCallback = cb
        }
    }

    /**
     * Get or create the clipboard service instance.
     * Returns null if clipboard monitoring is unsupported (API < 11).
     */
    suspend fun getService(ctx: Context): ClipboardHistoryServiceImpl? {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.HONEYCOMB) {
            return null
        }

        return serviceMutex.withLock {
            _service ?: ClipboardHistoryServiceImpl(ctx).also { _service = it }
        }
    }

    /**
     * Enable or disable clipboard history tracking.
     * When disabled, clears all history.
     */
    suspend fun setHistoryEnabled(enabled: Boolean) {
        Config.globalConfig().setClipboardHistoryEnabled(enabled)

        _service?.let { service ->
            if (enabled) {
                service.addCurrentClip()
            } else {
                service.clearHistory()
            }
        }
    }

    /**
     * Send the given string to the active editor via callback.
     */
    fun paste(clip: String) {
        _pasteCallback?.pasteFromClipboardPane(clip)
    }

    /**
     * Get storage statistics for debugging/monitoring.
     */
    suspend fun getStorageStats(): String? {
        return _service?.getStorageStats()
    }
}

/**
 * Implementation class for clipboard history management.
 * Handles actual clipboard monitoring, database operations, and event dispatching.
 */
class ClipboardHistoryServiceImpl(private val context: Context) {

    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val database = ClipboardDatabase.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Event flows for reactive programming
    private val _historyChanges = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val historyChanges: SharedFlow<Unit> = _historyChanges.asSharedFlow()

    private val _clipboardEntries = MutableStateFlow<List<String>>(emptyList())
    val clipboardEntries: StateFlow<List<String>> = _clipboardEntries.asStateFlow()

    private val operationMutex = Mutex()

    init {
        // Set up clipboard monitoring
        clipboardManager.addPrimaryClipChangedListener(SystemClipboardListener())

        // Clean up expired entries on startup
        scope.launch {
            database.cleanupExpiredEntries()
            refreshEntryCache()
        }

        // Set up periodic cleanup (every 30 seconds)
        scope.launch {
            while (isActive) {
                delay(30_000)
                database.cleanupExpiredEntries()
                refreshEntryCache()
            }
        }
    }

    /**
     * Get current clipboard history, automatically cleaning expired entries.
     */
    suspend fun clearExpiredAndGetHistory(): List<String> = operationMutex.withLock {
        database.cleanupExpiredEntries()
        val entries = database.getActiveClipboardEntries()
        _clipboardEntries.value = entries
        entries
    }

    /**
     * Remove a specific entry from clipboard history.
     * If it's the current system clipboard, also clears the system clipboard.
     */
    suspend fun removeHistoryEntry(clip: String) = operationMutex.withLock {
        val currentHistory = database.getActiveClipboardEntries()
        val isCurrentClip = currentHistory.isNotEmpty() && currentHistory[0] == clip

        // Clear system clipboard if removing current clip
        if (isCurrentClip) {
            withContext(Dispatchers.Main) {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                        clipboardManager.clearPrimaryClip()
                    }
                    else -> {
                        @Suppress("DEPRECATION")
                        clipboardManager.text = ""
                    }
                }
            }
        }

        // Remove from database
        val removed = database.removeClipboardEntry(clip)
        if (removed) {
            refreshEntryCache()
            _historyChanges.tryEmit(Unit)
        }
    }

    /**
     * Add a new clipboard entry to history.
     * Handles deduplication, size limits, and TTL automatically.
     */
    suspend fun addClip(clip: String) = operationMutex.withLock {
        if (!Config.globalConfig().clipboardHistoryEnabled) return@withLock

        val trimmedClip = clip.trim()
        if (trimmedClip.isEmpty()) return@withLock

        val expiryTime = System.currentTimeMillis() + ClipboardHistoryService.HISTORY_TTL_MS
        val added = database.addClipboardEntry(trimmedClip, expiryTime)

        if (added) {
            // Apply size limits if configured
            val maxHistorySize = Config.globalConfig().clipboardHistoryLimit
            if (maxHistorySize > 0) {
                database.applySizeLimit(maxHistorySize)
            }

            refreshEntryCache()
            _historyChanges.tryEmit(Unit)
        }
    }

    /**
     * Clear all clipboard history.
     */
    suspend fun clearHistory() = operationMutex.withLock {
        database.clearAllEntries()
        refreshEntryCache()
        _historyChanges.tryEmit(Unit)
    }

    /**
     * Pin or unpin a clipboard entry to prevent/allow expiration.
     */
    suspend fun setPinnedStatus(clip: String, isPinned: Boolean) = operationMutex.withLock {
        val updated = database.setPinnedStatus(clip, isPinned)
        if (updated) {
            refreshEntryCache()
            _historyChanges.tryEmit(Unit)
        }
    }

    /**
     * Get storage statistics for monitoring.
     */
    suspend fun getStorageStats(): String {
        val total = database.getTotalEntryCount()
        val active = database.getActiveEntryCount()
        return "Clipboard: $active active entries ($total total in database)"
    }

    /**
     * Add the current system clipboard content to history.
     */
    suspend fun addCurrentClip() {
        withContext(Dispatchers.Main) {
            val clip = clipboardManager.primaryClip ?: return@withContext

            // Process all items in the clipboard
            for (i in 0 until clip.itemCount) {
                val text = clip.getItemAt(i).text
                if (text != null) {
                    // Switch back to IO context for database operations
                    withContext(Dispatchers.IO) {
                        addClip(text.toString())
                    }
                }
            }
        }
    }

    /**
     * Subscribe to clipboard history changes.
     * Returns a Flow that emits whenever the history is modified.
     */
    fun subscribeToHistoryChanges(): Flow<List<String>> {
        return historyChanges
            .onStart { emit(Unit) } // Emit immediately on subscription
            .flatMapLatest {
                flow {
                    val entries = clearExpiredAndGetHistory()
                    emit(entries)
                }
            }
            .flowOn(Dispatchers.IO)
            .distinctUntilChanged()
    }

    /**
     * Get real-time clipboard entries as StateFlow.
     */
    fun getClipboardEntriesFlow(): StateFlow<List<String>> = clipboardEntries

    /**
     * Refresh the cached entry list from database.
     */
    private suspend fun refreshEntryCache() {
        val entries = database.getActiveClipboardEntries()
        _clipboardEntries.value = entries
    }

    /**
     * Clean up resources when service is destroyed.
     */
    fun destroy() {
        scope.cancel()
    }

    /**
     * System clipboard change listener.
     * Automatically adds new clipboard content to history.
     */
    private inner class SystemClipboardListener : ClipboardManager.OnPrimaryClipChangedListener {
        override fun onPrimaryClipChanged() {
            scope.launch {
                try {
                    addCurrentClip()
                } catch (e: Exception) {
                    // Log error but don't crash
                    android.util.Log.w("ClipboardHistory", "Error processing clipboard change", e)
                }
            }
        }
    }
}

/**
 * Interface for clipboard history change notifications.
 * Legacy interface maintained for compatibility.
 */
interface OnClipboardHistoryChange {
    fun onClipboardHistoryChange()
}

/**
 * Callback interface for paste operations.
 */
interface ClipboardPasteCallback {
    fun pasteFromClipboardPane(content: String)
}

/**
 * Extension functions for enhanced clipboard operations.
 */

/**
 * Format clipboard entry for display with length and type information.
 */
fun String.formatForClipboard(): String {
    val preview = if (length > 50) take(47) + "..." else this
    val type = when {
        matches(Regex("https?://.*")) -> "URL"
        matches(Regex("\\d+")) -> "Number"
        matches(Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")) -> "Email"
        contains('\n') -> "Multi-line"
        else -> "Text"
    }
    return "$preview ($type, ${length} chars)"
}

/**
 * Check if clipboard content is considered sensitive.
 */
fun String.isSensitiveContent(): Boolean {
    val lowerContent = lowercase()
    val sensitivePatterns = listOf(
        "password", "passwd", "pwd", "pin", "secret", "token", "key",
        "credit card", "ssn", "social security"
    )
    return sensitivePatterns.any { pattern -> lowerContent.contains(pattern) }
}

/**
 * Sanitize clipboard content for safe display.
 */
fun String.sanitizeForDisplay(): String {
    return if (isSensitiveContent()) {
        "*** Sensitive content (${length} chars) ***"
    } else {
        formatForClipboard()
    }
}