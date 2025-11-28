package tribixbite.keyboard2

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Collects and analyzes typing statistics for performance insights and personalization.
 *
 * Provides comprehensive typing metrics including speed, accuracy, key usage patterns,
 * and error analysis for adaptive keyboard improvements.
 *
 * Features:
 * - Real-time typing speed calculation (WPM, CPM)
 * - Accuracy tracking with error rate analysis
 * - Key usage frequency statistics
 * - Swipe vs tap gesture ratio tracking
 * - Session-based statistics
 * - Historical data aggregation
 * - Performance insights and recommendations
 *
 * Bug #336 - LOW: Complete implementation of missing TypingStatisticsCollector.java
 *
 * @param context Application context for accessing resources
 */
class TypingStatisticsCollector(
    private val context: Context
) {
    companion object {
        private const val TAG = "TypingStatsCollector"

        // Time windows for statistics
        private const val SESSION_TIMEOUT_MS = 5 * 60 * 1000L  // 5 minutes inactivity = new session
        private const val WPM_WINDOW_MS = 60 * 1000L           // 1 minute window for WPM calculation

        // Average word length for WPM calculation
        private const val AVG_WORD_LENGTH = 5                   // Characters per word

        // Statistics thresholds
        private const val MIN_SAMPLE_SIZE = 10                  // Minimum events for statistics
    }

    /**
     * Typing session statistics.
     */
    data class SessionStats(
        val sessionId: Long,
        val startTime: Long,
        val endTime: Long,
        val totalCharacters: Int,
        val totalWords: Int,
        val totalKeyPresses: Int,
        val totalBackspaces: Int,
        val totalSwipes: Int,
        val totalTaps: Int,
        val durationMs: Long,
        val wordsPerMinute: Float,
        val charactersPerMinute: Float,
        val accuracy: Float,
        val swipeRatio: Float
    ) {
        val errorRate: Float
            get() = if (totalKeyPresses > 0) totalBackspaces.toFloat() / totalKeyPresses else 0f
    }

    /**
     * Key usage statistics.
     */
    data class KeyStats(
        val key: String,
        val pressCount: Int,
        val frequency: Float,
        val avgPressDuration: Long
    )

    /**
     * Real-time statistics snapshot.
     */
    data class RealTimeStats(
        val currentWpm: Float,
        val currentAccuracy: Float,
        val keyPressesPerMinute: Int,
        val swipeRatio: Float,
        val sessionDuration: Long
    )

    /**
     * Callback interface for statistics events.
     */
    interface Callback {
        /**
         * Called when session statistics are updated.
         *
         * @param stats Current session statistics
         */
        fun onSessionStatsUpdated(stats: SessionStats)

        /**
         * Called when a typing session ends.
         *
         * @param stats Final session statistics
         */
        fun onSessionEnded(stats: SessionStats)

        /**
         * Called when typing speed milestone is reached.
         *
         * @param wpm Words per minute achieved
         */
        fun onSpeedMilestone(wpm: Float)

        /**
         * Called when accuracy milestone is reached.
         *
         * @param accuracy Accuracy percentage
         */
        fun onAccuracyMilestone(accuracy: Float)
    }

    // Current session tracking
    private var currentSessionId: Long = System.currentTimeMillis()
    private var sessionStartTime: Long = System.currentTimeMillis()
    private var lastActivityTime: Long = System.currentTimeMillis()

    // Statistics counters (thread-safe)
    private val totalCharacters = AtomicInteger(0)
    private val totalWords = AtomicInteger(0)
    private val totalKeyPresses = AtomicInteger(0)
    private val totalBackspaces = AtomicInteger(0)
    private val totalSwipes = AtomicInteger(0)
    private val totalTaps = AtomicInteger(0)

    // Key usage tracking
    private val keyPressCount = ConcurrentHashMap<String, AtomicInteger>()
    private val keyPressDurations = ConcurrentHashMap<String, MutableList<Long>>()

    // WPM calculation
    private val recentCharTimestamps = mutableListOf<Long>()

    // Session history
    private val sessionHistory = mutableListOf<SessionStats>()
    private val maxHistorySize = 100

    // Callback
    private var callback: Callback? = null

    // Coroutine scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Flow for real-time stats
    private val _statsFlow = MutableStateFlow(getRealTimeStats())
    val statsFlow: StateFlow<RealTimeStats> = _statsFlow.asStateFlow()

    init {
        logD("Initializing TypingStatisticsCollector")
        startSessionMonitoring()
    }

    /**
     * Start monitoring for session timeout.
     */
    private fun startSessionMonitoring() {
        scope.launch {
            while (isActive) {
                delay(1000) // Check every second

                val timeSinceLastActivity = System.currentTimeMillis() - lastActivityTime
                if (timeSinceLastActivity > SESSION_TIMEOUT_MS) {
                    endSession()
                }

                // Update real-time stats
                _statsFlow.value = getRealTimeStats()
            }
        }
    }

    /**
     * Record a character typed.
     *
     * @param char The character
     * @param isSwipe true if typed via swipe gesture
     */
    fun recordCharacter(char: Char, isSwipe: Boolean = false) {
        updateActivity()

        totalCharacters.incrementAndGet()
        totalKeyPresses.incrementAndGet()

        if (isSwipe) {
            totalSwipes.incrementAndGet()
        } else {
            totalTaps.incrementAndGet()
        }

        // Track for WPM calculation
        synchronized(recentCharTimestamps) {
            recentCharTimestamps.add(System.currentTimeMillis())
            cleanupOldTimestamps()
        }

        // Count words (space indicates word completion)
        if (char == ' ' || char == '\n') {
            totalWords.incrementAndGet()
        }

        // Track key usage
        val keyStr = char.toString()
        keyPressCount.getOrPut(keyStr) { AtomicInteger(0) }.incrementAndGet()

        checkMilestones()
    }

    /**
     * Record a key press.
     *
     * @param key The key identifier
     * @param durationMs Press duration in milliseconds
     * @param isSwipe true if from swipe gesture
     */
    fun recordKeyPress(key: String, durationMs: Long = 0, isSwipe: Boolean = false) {
        updateActivity()

        totalKeyPresses.incrementAndGet()

        if (isSwipe) {
            totalSwipes.incrementAndGet()
        } else {
            totalTaps.incrementAndGet()
        }

        // Track key usage
        keyPressCount.getOrPut(key) { AtomicInteger(0) }.incrementAndGet()

        // Track duration
        if (durationMs > 0) {
            keyPressDurations.getOrPut(key) { mutableListOf() }.add(durationMs)
        }

        checkMilestones()
    }

    /**
     * Record a backspace/delete action.
     */
    fun recordBackspace() {
        updateActivity()
        totalBackspaces.incrementAndGet()
        totalKeyPresses.incrementAndGet()
        totalTaps.incrementAndGet()

        keyPressCount.getOrPut("BACKSPACE") { AtomicInteger(0) }.incrementAndGet()
    }

    /**
     * Record a word typed.
     *
     * @param word The word
     * @param isSwipe true if typed via swipe
     */
    fun recordWord(word: String, isSwipe: Boolean = false) {
        updateActivity()

        totalWords.incrementAndGet()
        totalCharacters.addAndGet(word.length)

        if (isSwipe) {
            totalSwipes.incrementAndGet()
        } else {
            totalTaps.addAndGet(word.length)
        }

        checkMilestones()
    }

    /**
     * Update last activity time.
     */
    private fun updateActivity() {
        lastActivityTime = System.currentTimeMillis()
    }

    /**
     * Cleanup old timestamps outside WPM window.
     */
    private fun cleanupOldTimestamps() {
        val cutoffTime = System.currentTimeMillis() - WPM_WINDOW_MS
        recentCharTimestamps.removeAll { it < cutoffTime }
    }

    /**
     * Calculate current words per minute.
     */
    private fun calculateWpm(): Float {
        synchronized(recentCharTimestamps) {
            if (recentCharTimestamps.isEmpty()) return 0f

            cleanupOldTimestamps()

            val charCount = recentCharTimestamps.size
            val timeSpanMs = System.currentTimeMillis() - recentCharTimestamps.first()

            if (timeSpanMs == 0L) return 0f

            val charactersPerMs = charCount.toFloat() / timeSpanMs
            val charactersPerMinute = charactersPerMs * 60000f
            return charactersPerMinute / AVG_WORD_LENGTH
        }
    }

    /**
     * Calculate current accuracy.
     */
    private fun calculateAccuracy(): Float {
        val presses = totalKeyPresses.get()
        if (presses == 0) return 100f

        val backspaces = totalBackspaces.get()
        return ((presses - backspaces).toFloat() / presses * 100f).coerceIn(0f, 100f)
    }

    /**
     * Calculate swipe ratio.
     */
    private fun calculateSwipeRatio(): Float {
        val swipes = totalSwipes.get()
        val taps = totalTaps.get()
        val total = swipes + taps

        return if (total > 0) swipes.toFloat() / total else 0f
    }

    /**
     * Get real-time statistics.
     */
    fun getRealTimeStats(): RealTimeStats {
        val sessionDuration = System.currentTimeMillis() - sessionStartTime
        val keyPressesPerMinute = if (sessionDuration > 0) {
            (totalKeyPresses.get().toFloat() / sessionDuration * 60000f).toInt()
        } else {
            0
        }

        return RealTimeStats(
            currentWpm = calculateWpm(),
            currentAccuracy = calculateAccuracy(),
            keyPressesPerMinute = keyPressesPerMinute,
            swipeRatio = calculateSwipeRatio(),
            sessionDuration = sessionDuration
        )
    }

    /**
     * Get current session statistics.
     */
    fun getSessionStats(): SessionStats {
        val now = System.currentTimeMillis()
        val duration = now - sessionStartTime
        val words = totalWords.get()
        val characters = totalCharacters.get()

        val wpm = if (duration > 0) {
            words.toFloat() / duration * 60000f
        } else {
            0f
        }

        val cpm = if (duration > 0) {
            characters.toFloat() / duration * 60000f
        } else {
            0f
        }

        return SessionStats(
            sessionId = currentSessionId,
            startTime = sessionStartTime,
            endTime = now,
            totalCharacters = characters,
            totalWords = words,
            totalKeyPresses = totalKeyPresses.get(),
            totalBackspaces = totalBackspaces.get(),
            totalSwipes = totalSwipes.get(),
            totalTaps = totalTaps.get(),
            durationMs = duration,
            wordsPerMinute = wpm,
            charactersPerMinute = cpm,
            accuracy = calculateAccuracy(),
            swipeRatio = calculateSwipeRatio()
        )
    }

    /**
     * Get key usage statistics.
     */
    fun getKeyStats(): List<KeyStats> {
        val totalPresses = totalKeyPresses.get()
        if (totalPresses == 0) return emptyList()

        return keyPressCount.map { (key, count) ->
            val pressCount = count.get()
            val frequency = pressCount.toFloat() / totalPresses
            val durations = keyPressDurations[key] ?: emptyList()
            val avgDuration = if (durations.isNotEmpty()) {
                durations.average().toLong()
            } else {
                0L
            }

            KeyStats(
                key = key,
                pressCount = pressCount,
                frequency = frequency,
                avgPressDuration = avgDuration
            )
        }.sortedByDescending { it.pressCount }
    }

    /**
     * Get most frequently used keys.
     *
     * @param limit Number of keys to return
     * @return List of most used keys
     */
    fun getMostUsedKeys(limit: Int = 10): List<KeyStats> {
        return getKeyStats().take(limit)
    }

    /**
     * Get session history.
     *
     * @return List of past session statistics
     */
    fun getSessionHistory(): List<SessionStats> {
        return sessionHistory.toList()
    }

    /**
     * Get aggregate statistics across all sessions.
     */
    fun getAggregateStats(): SessionStats? {
        if (sessionHistory.isEmpty()) return null

        val totalChars = sessionHistory.sumOf { it.totalCharacters }
        val totalWords = sessionHistory.sumOf { it.totalWords }
        val totalPresses = sessionHistory.sumOf { it.totalKeyPresses }
        val totalBackspaces = sessionHistory.sumOf { it.totalBackspaces }
        val totalSwipes = sessionHistory.sumOf { it.totalSwipes }
        val totalTaps = sessionHistory.sumOf { it.totalTaps }
        val totalDuration = sessionHistory.sumOf { it.durationMs }

        val avgWpm = if (totalDuration > 0) {
            totalWords.toFloat() / totalDuration * 60000f
        } else {
            0f
        }

        val avgCpm = if (totalDuration > 0) {
            totalChars.toFloat() / totalDuration * 60000f
        } else {
            0f
        }

        val avgAccuracy = if (totalPresses > 0) {
            ((totalPresses - totalBackspaces).toFloat() / totalPresses * 100f).coerceIn(0f, 100f)
        } else {
            100f
        }

        val swipeRatio = if (totalSwipes + totalTaps > 0) {
            totalSwipes.toFloat() / (totalSwipes + totalTaps)
        } else {
            0f
        }

        return SessionStats(
            sessionId = 0L,
            startTime = sessionHistory.first().startTime,
            endTime = sessionHistory.last().endTime,
            totalCharacters = totalChars,
            totalWords = totalWords,
            totalKeyPresses = totalPresses,
            totalBackspaces = totalBackspaces,
            totalSwipes = totalSwipes,
            totalTaps = totalTaps,
            durationMs = totalDuration,
            wordsPerMinute = avgWpm,
            charactersPerMinute = avgCpm,
            accuracy = avgAccuracy,
            swipeRatio = swipeRatio
        )
    }

    /**
     * Check and notify milestones.
     */
    private fun checkMilestones() {
        val stats = getRealTimeStats()

        // Speed milestones (every 10 WPM)
        val wpmMilestone = (stats.currentWpm / 10).toInt() * 10
        if (wpmMilestone > 0 && stats.currentWpm >= wpmMilestone) {
            callback?.onSpeedMilestone(wpmMilestone.toFloat())
        }

        // Accuracy milestones (every 10%)
        val accuracyMilestone = (stats.currentAccuracy / 10).toInt() * 10
        if (accuracyMilestone > 0 && stats.currentAccuracy >= accuracyMilestone) {
            callback?.onAccuracyMilestone(accuracyMilestone.toFloat())
        }

        // Update callback with current stats
        scope.launch {
            callback?.onSessionStatsUpdated(getSessionStats())
        }
    }

    /**
     * End current session and start a new one.
     */
    private fun endSession() {
        val finalStats = getSessionStats()

        // Only save sessions with meaningful data
        if (finalStats.totalKeyPresses >= MIN_SAMPLE_SIZE) {
            sessionHistory.add(finalStats)

            // Limit history size
            while (sessionHistory.size > maxHistorySize) {
                sessionHistory.removeAt(0)
            }

            logD("Session ended: ${finalStats.totalWords} words, ${finalStats.wordsPerMinute} WPM, ${finalStats.accuracy}% accuracy")
            callback?.onSessionEnded(finalStats)
        }

        // Start new session
        resetSession()
    }

    /**
     * Reset current session statistics.
     */
    private fun resetSession() {
        currentSessionId = System.currentTimeMillis()
        sessionStartTime = System.currentTimeMillis()
        lastActivityTime = System.currentTimeMillis()

        totalCharacters.set(0)
        totalWords.set(0)
        totalKeyPresses.set(0)
        totalBackspaces.set(0)
        totalSwipes.set(0)
        totalTaps.set(0)

        keyPressCount.clear()
        keyPressDurations.clear()

        synchronized(recentCharTimestamps) {
            recentCharTimestamps.clear()
        }

        logD("New session started: $currentSessionId")
    }

    /**
     * Clear all statistics.
     */
    fun clearAll() {
        resetSession()
        sessionHistory.clear()
        logD("All statistics cleared")
    }

    /**
     * Set callback for statistics events.
     *
     * @param callback Callback to receive events
     */
    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    /**
     * Release all resources and cleanup.
     */
    fun release() {
        logD("Releasing TypingStatisticsCollector resources...")

        try {
            // End current session if it has data
            if (totalKeyPresses.get() >= MIN_SAMPLE_SIZE) {
                endSession()
            }

            scope.cancel()
            callback = null

            logD("✅ TypingStatisticsCollector resources released")
        } catch (e: Exception) {
            logE("Error releasing typing statistics collector resources", e)
        }
    }

    // Logging helpers
    private fun logD(message: String) = Log.d(TAG, message)
    private fun logE(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
    }
}
