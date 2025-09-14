package juloo.keyboard2;

import android.util.Log;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple performance profiler for tracking execution times of critical sections
 */
public class PerformanceProfiler
{
  private static final String TAG = "PerfProfiler";
  private static final boolean ENABLED = true; // Set to false in production
  
  private static final Map<String, Long> _startTimes = new ConcurrentHashMap<>();
  private static final Map<String, Statistics> _statistics = new ConcurrentHashMap<>();
  
  /**
   * Statistics for a profiled section
   */
  private static class Statistics
  {
    long count = 0;
    long totalTime = 0;
    long minTime = Long.MAX_VALUE;
    long maxTime = 0;
    
    synchronized void record(long time)
    {
      count++;
      totalTime += time;
      minTime = Math.min(minTime, time);
      maxTime = Math.max(maxTime, time);
    }
    
    long getAverage()
    {
      return count > 0 ? totalTime / count : 0;
    }
  }
  
  /**
   * Start timing a section
   */
  public static void start(String section)
  {
    if (!ENABLED) return;
    _startTimes.put(section, System.currentTimeMillis());
  }
  
  /**
   * End timing a section and record statistics
   */
  public static void end(String section)
  {
    if (!ENABLED) return;
    
    Long startTime = _startTimes.remove(section);
    if (startTime == null)
    {
      Log.w(TAG, "No start time for section: " + section);
      return;
    }
    
    long duration = System.currentTimeMillis() - startTime;
    
    Statistics stats = _statistics.computeIfAbsent(section, k -> new Statistics());
    stats.record(duration);
    
    // Log if this operation took longer than expected
    if (duration > getThreshold(section))
    {
      Log.w(TAG, String.format("%s took %dms (threshold: %dms)", 
                               section, duration, getThreshold(section)));
    }
  }
  
  /**
   * Get performance thresholds for different operations
   */
  private static long getThreshold(String section)
  {
    // Define expected maximum times for operations
    if (section.startsWith("DTW."))
      return 30; // DTW should complete within 30ms
    if (section.startsWith("Swipe."))
      return 50; // Swipe prediction should complete within 50ms
    if (section.startsWith("Type."))
      return 20; // Regular typing prediction within 20ms
    if (section.startsWith("Gaussian."))
      return 10; // Gaussian model within 10ms
    if (section.startsWith("Ngram."))
      return 5; // N-gram lookup within 5ms
    return 100; // Default threshold
  }
  
  /**
   * Print performance report
   */
  public static void report()
  {
    if (!ENABLED || _statistics.isEmpty()) return;
    
    Log.d(TAG, "===== PERFORMANCE REPORT =====");
    for (Map.Entry<String, Statistics> entry : _statistics.entrySet())
    {
      String section = entry.getKey();
      Statistics stats = entry.getValue();
      
      Log.d(TAG, String.format("%s: count=%d, avg=%dms, min=%dms, max=%dms, total=%dms",
                              section, stats.count, stats.getAverage(),
                              stats.minTime, stats.maxTime, stats.totalTime));
    }
    Log.d(TAG, "==============================");
  }
  
  /**
   * Clear all statistics
   */
  public static void reset()
  {
    _startTimes.clear();
    _statistics.clear();
  }
  
  /**
   * Log a single timing event without tracking statistics
   */
  public static void logTiming(String operation, long timeMs)
  {
    if (!ENABLED) return;
    
    if (timeMs > getThreshold(operation))
    {
      Log.w(TAG, String.format("%s: %dms (SLOW)", operation, timeMs));
    }
    else
    {
      Log.d(TAG, String.format("%s: %dms", operation, timeMs));
    }
  }
}