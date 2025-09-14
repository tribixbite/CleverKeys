package juloo.keyboard2;

import android.graphics.PointF;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * Improved swipe gesture recognizer with better noise filtering
 */
public class ImprovedSwipeGestureRecognizer
{
  private final List<PointF> _rawPath;
  private final List<PointF> _smoothedPath;
  private final List<KeyboardData.Key> _touchedKeys;
  private final List<Long> _timestamps;
  private final Queue<KeyboardData.Key> _recentKeys;
  private ProbabilisticKeyDetector _probabilisticDetector;
  private KeyboardData _currentKeyboard;
  
  private boolean _isSwipeTyping;
  private long _startTime;
  private long _lastPointTime;
  private float _totalDistance;
  private KeyboardData.Key _lastKey;
  private KeyboardData.Key _lastRegisteredKey;
  
  // Thresholds for improved filtering
  private static final float MIN_SWIPE_DISTANCE = 100.0f; // Restored original threshold for long words
  private static final long MIN_DWELL_TIME_MS = 20; // Minimum time to register a key
  private static final float MIN_KEY_DISTANCE = 40.0f; // Minimum distance to register new key
  private static final int SMOOTHING_WINDOW = 3; // Points for moving average
  private static final int DUPLICATE_CHECK_WINDOW = 5; // Check last 5 keys for duplicates
  private static final long MAX_POINT_INTERVAL_MS = 500;
  private static final float NOISE_THRESHOLD = 10.0f; // Ignore tiny movements
  
  // For velocity-based filtering
  private float _recentVelocity = 0;
  private static final float HIGH_VELOCITY_THRESHOLD = 500.0f; // pixels/second
  
  public ImprovedSwipeGestureRecognizer()
  {
    _rawPath = new ArrayList<>();
    _smoothedPath = new ArrayList<>();
    _touchedKeys = new ArrayList<>();
    _timestamps = new ArrayList<>();
    _recentKeys = new LinkedList<>();
    _isSwipeTyping = false;
  }
  
  /**
   * Set the current keyboard for probabilistic detection
   */
  public void setKeyboard(KeyboardData keyboard, float width, float height)
  {
    _currentKeyboard = keyboard;
    if (keyboard != null)
    {
      _probabilisticDetector = new ProbabilisticKeyDetector(keyboard, width, height);
    }
  }
  
  /**
   * Start tracking a new swipe gesture
   */
  public void startSwipe(float x, float y, KeyboardData.Key key)
  {
    reset();
    
    PointF startPoint = new PointF(x, y);
    _rawPath.add(startPoint);
    _smoothedPath.add(startPoint);
    
    _startTime = System.currentTimeMillis();
    _lastPointTime = _startTime;
    _timestamps.add(_startTime);
    
    // Only register starting key if it's alphabetic
    if (key != null && isValidAlphabeticKey(key))
    {
      _touchedKeys.add(key);
      _lastKey = key;
      _lastRegisteredKey = key;
      _recentKeys.offer(key);
    }
    
    _totalDistance = 0;
    _recentVelocity = 0;
  }
  
  /**
   * Add a point to the current swipe path with improved filtering
   */
  public void addPoint(float x, float y, KeyboardData.Key key)
  {
    if (_rawPath.isEmpty())
      return;
    
    long now = System.currentTimeMillis();
    long timeSinceLastPoint = now - _lastPointTime;
    
    // Fix timestamp issues - ignore invalid time deltas
    if (timeSinceLastPoint <= 0 || timeSinceLastPoint > MAX_POINT_INTERVAL_MS)
    {
      return; // Skip this point if timing is invalid
    }
    
    PointF lastRawPoint = _rawPath.get(_rawPath.size() - 1);
    float dx = x - lastRawPoint.x;
    float dy = y - lastRawPoint.y;
    float distance = (float)Math.sqrt(dx * dx + dy * dy);
    
    // Ignore tiny movements (noise)
    if (distance < NOISE_THRESHOLD)
    {
      return;
    }
    
    // Calculate velocity
    _recentVelocity = (distance / timeSinceLastPoint) * 1000; // pixels per second
    
    // Add raw point
    _rawPath.add(new PointF(x, y));
    _timestamps.add(now);
    _lastPointTime = now;
    _totalDistance += distance;
    
    // Apply smoothing
    PointF smoothedPoint = applySmoothing(x, y);
    _smoothedPath.add(smoothedPoint);
    
    // Check if this should be considered swipe typing
    if (!_isSwipeTyping && _totalDistance > MIN_SWIPE_DISTANCE)
    {
      _isSwipeTyping = shouldConsiderSwipeTyping();
    }
    
    // Process key registration with improved filtering
    if (key != null && isValidAlphabeticKey(key))
    {
      registerKeyWithFiltering(key, distance, timeSinceLastPoint);
    }
  }
  
  /**
   * Apply moving average smoothing to coordinates
   */
  private PointF applySmoothing(float x, float y)
  {
    if (_rawPath.size() < SMOOTHING_WINDOW)
    {
      return new PointF(x, y);
    }
    
    // Calculate moving average over last N points
    float avgX = 0, avgY = 0;
    int startIdx = Math.max(0, _rawPath.size() - SMOOTHING_WINDOW);
    int count = 0;
    
    for (int i = startIdx; i < _rawPath.size(); i++)
    {
      PointF p = _rawPath.get(i);
      avgX += p.x;
      avgY += p.y;
      count++;
    }
    
    return new PointF(avgX / count, avgY / count);
  }
  
  /**
   * Register key with improved filtering logic
   */
  private void registerKeyWithFiltering(KeyboardData.Key key, float distance, long timeDelta)
  {
    // Skip if same as last key
    if (key == _lastKey)
    {
      return;
    }
    
    // Check dwell time - must be on key for minimum time
    if (timeDelta < MIN_DWELL_TIME_MS && _recentVelocity > HIGH_VELOCITY_THRESHOLD)
    {
      // Moving too fast, likely just passing through
      return;
    }
    
    // Check if key is in recent history (avoid duplicates)
    if (isRecentDuplicate(key))
    {
      return;
    }
    
    // Check minimum distance from last registered key
    if (_lastRegisteredKey != null && distance < MIN_KEY_DISTANCE)
    {
      return;
    }
    
    // Register the key
    _touchedKeys.add(key);
    _lastKey = key;
    _lastRegisteredKey = key;
    
    // Update recent keys queue
    _recentKeys.offer(key);
    if (_recentKeys.size() > DUPLICATE_CHECK_WINDOW)
    {
      _recentKeys.poll();
    }
  }
  
  /**
   * Check if key is a recent duplicate
   */
  private boolean isRecentDuplicate(KeyboardData.Key key)
  {
    for (KeyboardData.Key recentKey : _recentKeys)
    {
      if (recentKey == key)
      {
        return true;
      }
    }
    return false;
  }
  
  /**
   * End the swipe gesture and return the touched keys if it was swipe typing
   */
  public SwipeResult endSwipe()
  {
    // Apply endpoint stabilization
    stabilizeEndpoints();
    
    if (_isSwipeTyping && _touchedKeys.size() >= 2)
    {
      List<KeyboardData.Key> finalKeys;
      
      // Try probabilistic detection if available
      if (_probabilisticDetector != null && _smoothedPath.size() > 5)
      {
        // Apply Ramer-Douglas-Peucker simplification first
        List<PointF> simplifiedPath = ProbabilisticKeyDetector.simplifyPath(_smoothedPath, 15.0f);
        
        // Get probabilistic key detection
        List<KeyboardData.Key> probabilisticKeys = _probabilisticDetector.detectKeys(simplifiedPath);
        
        // If probabilistic detection gives good results, use it
        if (probabilisticKeys != null && probabilisticKeys.size() >= 2)
        {
          finalKeys = probabilisticKeys;
          android.util.Log.d("SwipeRecognizer", "Using probabilistic keys: " + probabilisticKeys.size());
        }
        else
        {
          // Fall back to traditional detection
          finalKeys = applyFinalFiltering(_touchedKeys);
          android.util.Log.d("SwipeRecognizer", "Using traditional keys: " + finalKeys.size());
        }
      }
      else
      {
        // Use traditional detection
        finalKeys = applyFinalFiltering(_touchedKeys);
      }
      
      return new SwipeResult(
        finalKeys,
        new ArrayList<>(_smoothedPath),
        new ArrayList<>(_timestamps),
        _totalDistance,
        _isSwipeTyping
      );
    }
    
    return new SwipeResult(null, null, null, 0, false);
  }
  
  /**
   * Stabilize first and last keys using multiple points
   */
  private void stabilizeEndpoints()
  {
    if (_smoothedPath.size() < 10 || _touchedKeys.size() < 2)
      return;
    
    // Check first key stability
    PointF avgStart = calculateAveragePoint(_smoothedPath, 0, 5);
    KeyboardData.Key stableStartKey = findKeyAtPoint(avgStart);
    if (stableStartKey != null && isValidAlphabeticKey(stableStartKey))
    {
      _touchedKeys.set(0, stableStartKey);
    }
    
    // Check last key stability
    int endIdx = _smoothedPath.size() - 1;
    PointF avgEnd = calculateAveragePoint(_smoothedPath, Math.max(0, endIdx - 5), endIdx);
    KeyboardData.Key stableEndKey = findKeyAtPoint(avgEnd);
    if (stableEndKey != null && isValidAlphabeticKey(stableEndKey))
    {
      _touchedKeys.set(_touchedKeys.size() - 1, stableEndKey);
    }
  }
  
  /**
   * Calculate average point over a range
   */
  private PointF calculateAveragePoint(List<PointF> points, int start, int end)
  {
    float sumX = 0, sumY = 0;
    int count = 0;
    
    for (int i = start; i <= Math.min(end, points.size() - 1); i++)
    {
      PointF p = points.get(i);
      sumX += p.x;
      sumY += p.y;
      count++;
    }
    
    return new PointF(sumX / count, sumY / count);
  }
  
  /**
   * Find key at a given point (placeholder - needs keyboard layout)
   */
  private KeyboardData.Key findKeyAtPoint(PointF point)
  {
    // This would need access to the keyboard layout
    // For now, return null - actual implementation would find closest key
    return null;
  }
  
  /**
   * Apply final filtering to remove obvious noise
   */
  private List<KeyboardData.Key> applyFinalFiltering(List<KeyboardData.Key> keys)
  {
    if (keys.size() <= 3)
      return keys;
    
    List<KeyboardData.Key> filtered = new ArrayList<>();
    
    // Always keep first key
    filtered.add(keys.get(0));
    
    // Filter middle keys - remove obvious zigzag patterns
    for (int i = 1; i < keys.size() - 1; i++)
    {
      KeyboardData.Key prev = keys.get(i - 1);
      KeyboardData.Key curr = keys.get(i);
      KeyboardData.Key next = keys.get(i + 1);
      
      // Skip if this creates a back-and-forth pattern
      if (prev != next || !isLikelyNoise(prev, curr, next))
      {
        filtered.add(curr);
      }
    }
    
    // Always keep last key
    filtered.add(keys.get(keys.size() - 1));
    
    return filtered;
  }
  
  /**
   * Check if middle key is likely noise in a sequence
   */
  private boolean isLikelyNoise(KeyboardData.Key prev, KeyboardData.Key curr, KeyboardData.Key next)
  {
    // This would check keyboard layout to see if curr is between prev and next
    // For now, return false - actual implementation would use key positions
    return false;
  }
  
  /**
   * Check if the current gesture should be considered swipe typing
   */
  private boolean shouldConsiderSwipeTyping()
  {
    // Add debug logging for swipe detection
    android.util.Log.e("ImprovedSwipeGestureRecognizer", "🔍 SWIPE DETECTION CHECK:");
    android.util.Log.e("ImprovedSwipeGestureRecognizer", "- Keys touched: " + _touchedKeys.size());
    android.util.Log.e("ImprovedSwipeGestureRecognizer", "- Total distance: " + _totalDistance + " (need " + MIN_SWIPE_DISTANCE + ")");
    
    // Need at least 2 alphabetic keys
    if (_touchedKeys.size() < 2) {
      android.util.Log.e("ImprovedSwipeGestureRecognizer", "❌ Too few keys: " + _touchedKeys.size() + " < 2");
      return false;
    }
    
    // Check total distance
    if (_totalDistance < MIN_SWIPE_DISTANCE) {
      android.util.Log.e("ImprovedSwipeGestureRecognizer", "❌ Distance too short: " + _totalDistance + " < " + MIN_SWIPE_DISTANCE);
      return false;
    }
    
    // Check if all touched keys are alphabetic
    for (KeyboardData.Key key : _touchedKeys)
    {
      if (!isValidAlphabeticKey(key)) {
        android.util.Log.e("ImprovedSwipeGestureRecognizer", "❌ Non-alphabetic key touched");
        return false;
      }
    }
    
    android.util.Log.e("ImprovedSwipeGestureRecognizer", "✅ SWIPE DETECTED - proceeding with swipe typing");
    return true;
  }
  
  /**
   * Check if a key is a valid alphabetic key
   */
  private boolean isValidAlphabeticKey(KeyboardData.Key key)
  {
    if (key == null || key.keys[0] == null)
      return false;
    
    KeyValue kv = key.keys[0];
    if (kv.getKind() != KeyValue.Kind.Char)
      return false;
    
    char c = kv.getChar();
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }
  
  /**
   * Get the current swipe path
   */
  public List<PointF> getSwipePath()
  {
    return new ArrayList<>(_smoothedPath);
  }
  
  /**
   * Get the timestamps
   */
  public List<Long> getTimestamps()
  {
    return new ArrayList<>(_timestamps);
  }
  
  /**
   * Check if currently swipe typing
   */
  public boolean isSwipeTyping()
  {
    return _isSwipeTyping;
  }
  
  /**
   * Reset the recognizer for a new gesture
   */
  public void reset()
  {
    _rawPath.clear();
    _smoothedPath.clear();
    _touchedKeys.clear();
    _timestamps.clear();
    _recentKeys.clear();
    _isSwipeTyping = false;
    _lastKey = null;
    _lastRegisteredKey = null;
    _totalDistance = 0;
    _recentVelocity = 0;
  }
  
  /**
   * Result class for swipe data
   */
  public static class SwipeResult
  {
    public final List<KeyboardData.Key> keys;
    public final List<PointF> path;
    public final List<Long> timestamps;
    public final float totalDistance;
    public final boolean isSwipeTyping;
    
    public SwipeResult(List<KeyboardData.Key> keys, List<PointF> path, 
                      List<Long> timestamps, float totalDistance, boolean isSwipeTyping)
    {
      this.keys = keys;
      this.path = path;
      this.timestamps = timestamps;
      this.totalDistance = totalDistance;
      this.isSwipeTyping = isSwipeTyping;
    }
  }
}