package tribixbite.keyboard2

import android.os.Trace
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Performance profiler using Android's standard Trace API.
 *
 * OPTIMIZATION v2: Uses android.os.Trace for system-level profiling
 * - Integrates with Perfetto and Android Studio Profiler
 * - Zero overhead in release builds (traces are compiled out)
 * - Provides accurate system-level performance analysis
 *
 * Legacy statistics tracking is kept for development but disabled by default.
 * Enable ENABLE_STATISTICS in debug builds for detailed timing analysis.
 */
object PerformanceProfiler {
    private const val TAG = "PerfProfiler"

    // OPTIMIZATION: Use BuildConfig for compile-time optimization in release builds
    private val ENABLE_TRACE = BuildConfig.DEBUG // Android Trace (always enabled in debug)
    private const val ENABLE_STATISTICS = false // Legacy stats (disabled for performance)

    private val startTimes = ConcurrentHashMap<String, Long>()
    private val statistics = ConcurrentHashMap<String, Statistics>()

    /**
     * Statistics for a profiled section (legacy, disabled by default)
     */
    private class Statistics {
        var count: Long = 0
        var totalTime: Long = 0
        var minTime: Long = Long.MAX_VALUE
        var maxTime: Long = 0

        @Synchronized
        fun record(time: Long) {
            count++
            totalTime += time
            minTime = min(minTime, time)
            maxTime = max(maxTime, time)
        }

        fun getAverage(): Long = if (count > 0) totalTime / count else 0
    }

    /**
     * Start timing a section.
     *
     * OPTIMIZATION v2: Uses android.os.Trace.beginSection() for system-level profiling.
     * Integrates with Perfetto, Android Studio Profiler, and systrace.
     *
     * @param section Section name (max 127 characters)
     */
    @JvmStatic
    fun start(section: String) {
        // Android Trace API (compiled out in release builds)
        if (ENABLE_TRACE) {
            Trace.beginSection(section)
        }

        // Legacy statistics (optional, disabled by default for performance)
        if (ENABLE_STATISTICS) {
            startTimes[section] = System.currentTimeMillis()
        }
    }

    /**
     * End timing a section.
     *
     * OPTIMIZATION v2: Uses android.os.Trace.endSection() for system-level profiling.
     */
    @JvmStatic
    fun end(section: String) {
        // Android Trace API (compiled out in release builds)
        if (ENABLE_TRACE) {
            Trace.endSection()
        }

        // Legacy statistics (optional, disabled by default)
        if (ENABLE_STATISTICS) {
            val startTime = startTimes.remove(section)
            if (startTime == null) {
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                    Log.w(TAG, "No start time for section: $section")
                }
                return
            }

            val duration = System.currentTimeMillis() - startTime

            val stats = statistics.getOrPut(section) { Statistics() }
            stats.record(duration)

            // Log if this operation took longer than expected
            if (duration > getThreshold(section)) {
                Log.w(TAG, String.format(
                    "%s took %dms (threshold: %dms)",
                    section, duration, getThreshold(section)
                ))
            }
        }
    }

    /**
     * Get performance thresholds for different operations
     */
    private fun getThreshold(section: String): Long {
        // Define expected maximum times for operations
        return when {
            section.startsWith("DTW.") -> 30 // DTW should complete within 30ms
            section.startsWith("Swipe.") -> 50 // Swipe prediction should complete within 50ms
            section.startsWith("Type.") -> 20 // Regular typing prediction within 20ms
            section.startsWith("Gaussian.") -> 10 // Gaussian model within 10ms
            section.startsWith("Ngram.") -> 5 // N-gram lookup within 5ms
            else -> 100 // Default threshold
        }
    }

    /**
     * Print performance report
     */
    @JvmStatic
    fun report() {
        if (!ENABLE_STATISTICS || statistics.isEmpty()) return

        Log.d(TAG, "===== PERFORMANCE REPORT =====")
        for ((section, stats) in statistics) {
            Log.d(TAG, String.format(
                "%s: count=%d, avg=%dms, min=%dms, max=%dms, total=%dms",
                section, stats.count, stats.getAverage(),
                stats.minTime, stats.maxTime, stats.totalTime
            ))
        }
        Log.d(TAG, "==============================")
    }

    /**
     * Clear all statistics
     */
    @JvmStatic
    fun reset() {
        startTimes.clear()
        statistics.clear()
    }

    /**
     * Log a single timing event without tracking statistics
     */
    @JvmStatic
    fun logTiming(operation: String, timeMs: Long) {
        if (!ENABLE_STATISTICS) return

        if (timeMs > getThreshold(operation)) {
            Log.w(TAG, String.format("%s: %dms (SLOW)", operation, timeMs))
        } else {
            Log.d(TAG, String.format("%s: %dms", operation, timeMs))
        }
    }
}
