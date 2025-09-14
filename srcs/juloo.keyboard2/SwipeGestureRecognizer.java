package juloo.keyboard2;

import android.graphics.PointF;
import java.util.ArrayList;
import java.util.List;

/**
 * Recognizes swipe gestures across keyboard keys and tracks the path
 * for word prediction.
 */
public class SwipeGestureRecognizer
{
  private final List<PointF> _swipePath;
  private final List<KeyboardData.Key> _touchedKeys;
  private final List<Long> _timestamps;
  private boolean _isSwipeTyping;
  private long _startTime;
  private float _totalDistance;
  private KeyboardData.Key _lastKey;
  private LoopGestureDetector _loopDetector;
  
  // Minimum distance to consider it a swipe typing gesture
  private static final float MIN_SWIPE_DISTANCE = 50.0f;
  // Maximum time between touch points to continue swipe
  private static final long MAX_POINT_INTERVAL_MS = 500;
  // Velocity threshold in pixels per millisecond (based on FlorisBoard's 0.10 dp/ms)
  private static final float VELOCITY_THRESHOLD = 0.15f;
  // Minimum distance between points to register (based on FlorisBoard's key_width/4)
  private static final float MIN_POINT_DISTANCE = 25.0f;
  // Minimum dwell time on a key to register it (milliseconds)
  private static final long MIN_DWELL_TIME_MS = 30;
  
  public SwipeGestureRecognizer()
  {
    _swipePath = new ArrayList<>();
    _touchedKeys = new ArrayList<>();
    _timestamps = new ArrayList<>();
    _isSwipeTyping = false;
    // Initialize loop detector with approximate key dimensions
    // These will be updated when actual keyboard dimensions are known
    _loopDetector = new LoopGestureDetector(100.0f, 80.0f);
  }
  
  /**
   * Set keyboard dimensions for loop detection
   */
  public void setKeyboardDimensions(float keyWidth, float keyHeight)
  {
    _loopDetector = new LoopGestureDetector(keyWidth, keyHeight);
  }
  
  /**
   * Start tracking a new swipe gesture
   */
  public void startSwipe(float x, float y, KeyboardData.Key key)
  {
    reset();
    _swipePath.add(new PointF(x, y));
    // android.util.Log.d("SwipeGesture", "startSwipe at " + x + "," + y);
    if (key != null && key.keys[0] != null && isAlphabeticKey(key.keys[0]))
    {
      // android.util.Log.d("SwipeGesture", "Started on alphabetic key: " + key.keys[0].getString());
      _touchedKeys.add(key);
      _lastKey = key;
    }
    else
    {
      // android.util.Log.d("SwipeGesture", "Started on non-alphabetic key");
    }
    _startTime = System.currentTimeMillis();
    _timestamps.add(_startTime);
    _totalDistance = 0;
  }
  
  /**
   * Add a point to the current swipe path
   */
  public void addPoint(float x, float y, KeyboardData.Key key)
  {
    if (_swipePath.isEmpty())
      return;
      
    long now = System.currentTimeMillis();
    long timeSinceStart = now - _startTime;
    
    // Check if this should be considered swipe typing
    if (!_isSwipeTyping && timeSinceStart > 100 && _totalDistance > MIN_SWIPE_DISTANCE)
    {
      _isSwipeTyping = shouldConsiderSwipeTyping();
      // android.util.Log.d("SwipeGesture", "Swipe typing check: " + _isSwipeTyping);
    }
    
    PointF lastPoint = _swipePath.get(_swipePath.size() - 1);
    float dx = x - lastPoint.x;
    float dy = y - lastPoint.y;
    float distance = (float)Math.sqrt(dx * dx + dy * dy);
    
    // Apply distance-based filtering (like FlorisBoard)
    if (distance < MIN_POINT_DISTANCE && _swipePath.size() > 1)
    {
      // Skip this point - too close to previous
      return;
    }
    
    _totalDistance += distance;
    
    _swipePath.add(new PointF(x, y));
    _timestamps.add(now);
    
    // Calculate velocity for filtering (like FlorisBoard)
    long timeDelta = _timestamps.size() > 0 ? 
                     now - _timestamps.get(_timestamps.size() - 1) : 0;
    float velocity = timeDelta > 0 ? distance / timeDelta : 0;
    
    // Add key if it's different from the last one and is alphabetic
    if (key != null && key != _lastKey && key.keys[0] != null && isAlphabeticKey(key.keys[0]))
    {
      // Apply velocity-based filtering (skip if moving too fast)
      if (velocity > VELOCITY_THRESHOLD && timeDelta < MIN_DWELL_TIME_MS)
      {
        // Moving too fast - likely transitioning between keys
        // android.util.Log.d("SwipeGesture", "Skipping key due to high velocity: " + velocity);
        return;
      }
      
      // Check if this key is already in recent keys (avoid duplicates)
      boolean isDuplicate = false;
      int size = _touchedKeys.size();
      if (size >= 3)
      {
        // Check last 3 keys for duplicates (increased from 2)
        for (int i = Math.max(0, size - 3); i < size; i++)
        {
          if (_touchedKeys.get(i) == key)
          {
            isDuplicate = true;
            break;
          }
        }
      }
      
      // Only add if not a recent duplicate and we've moved enough
      if (!isDuplicate && (distance > 35.0f || _touchedKeys.isEmpty()))
      {
        // android.util.Log.d("SwipeGesture", "Adding key: " + key.keys[0].getString());
        _touchedKeys.add(key);
        _lastKey = key;
      }
    }
  }
  
  /**
   * End the swipe gesture and return the touched keys if it was swipe typing
   */
  public List<KeyboardData.Key> endSwipe()
  {
    // android.util.Log.d("SwipeGesture", "endSwipe: isSwipeTyping=" + _isSwipeTyping + 
    //                     ", touchedKeys=" + _touchedKeys.size());
    
    // Log detailed swipe data for analysis
    logSwipeData();
    
    if (_isSwipeTyping && _touchedKeys.size() >= 2)
    {
      // android.util.Log.d("SwipeGesture", "Returning " + _touchedKeys.size() + " keys");
      return new ArrayList<>(_touchedKeys);
    }
    // android.util.Log.d("SwipeGesture", "Not enough keys or not swipe typing");
    return null;
  }
  
  /**
   * Check if the current gesture should be considered swipe typing
   */
  private boolean shouldConsiderSwipeTyping()
  {
    // Need at least 2 alphabetic keys
    if (_touchedKeys.size() < 2)
      return false;
      
    // Check if all touched keys are alphabetic
    for (KeyboardData.Key key : _touchedKeys)
    {
      if (key.keys[0] == null || !isAlphabeticKey(key.keys[0]))
        return false;
    }
    
    return true;
  }
  
  /**
   * Check if a KeyValue represents an alphabetic character
   */
  private boolean isAlphabeticKey(KeyValue kv)
  {
    if (kv.getKind() != KeyValue.Kind.Char)
      return false;
    char c = kv.getChar();
    return Character.isLetter(c);
  }
  
  /**
   * Get the current swipe path for rendering
   */
  public List<PointF> getSwipePath()
  {
    return new ArrayList<>(_swipePath);
  }
  
  /**
   * Check if currently in swipe typing mode
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
    _swipePath.clear();
    _touchedKeys.clear();
    _timestamps.clear();
    _isSwipeTyping = false;
    _lastKey = null;
    _totalDistance = 0;
  }
  
  /**
   * Get the sequence of characters from touched keys
   */
  public String getKeySequence()
  {
    if (_touchedKeys.isEmpty())
      return "";
      
    StringBuilder sb = new StringBuilder();
    for (KeyboardData.Key key : _touchedKeys)
    {
      if (key.keys[0] != null && key.keys[0].getKind() == KeyValue.Kind.Char)
      {
        char c = key.keys[0].getChar();
        if (Character.isLetter(c))
          sb.append(c);
      }
    }
    return sb.toString();
  }
  
  /**
   * Get the enhanced key sequence with loop detection for repeated letters
   */
  public String getEnhancedKeySequence()
  {
    String baseSequence = getKeySequence();
    if (baseSequence.isEmpty() || _swipePath.size() < 10)
      return baseSequence;
    
    // Detect loops in the swipe path
    List<LoopGestureDetector.Loop> loops = _loopDetector.detectLoops(_swipePath, _touchedKeys);
    
    if (loops.isEmpty())
      return baseSequence;
    
    // Apply loop detection to enhance the sequence
    String enhanced = _loopDetector.applyLoops(baseSequence, loops, _swipePath);
    
    // android.util.Log.d("SwipeGesture", "Enhanced sequence: " + baseSequence + " -> " + enhanced);
    return enhanced;
  }
  
  /**
   * Get timestamps for ML data collection
   */
  public List<Long> getTimestamps()
  {
    return new ArrayList<>(_timestamps);
  }
  
  /**
   * Log comprehensive swipe data for analysis and debugging
   */
  private void logSwipeData()
  {
    if (_swipePath.isEmpty())
      return;
    
    // android.util.Log.d("SwipeAnalysis", "===== SWIPE DATA ANALYSIS =====");
    // android.util.Log.d("SwipeAnalysis", "Total points: " + _swipePath.size());
    // android.util.Log.d("SwipeAnalysis", "Total distance: " + _totalDistance);
    // android.util.Log.d("SwipeAnalysis", "Duration: " + (System.currentTimeMillis() - _startTime) + "ms");
    // android.util.Log.d("SwipeAnalysis", "Key sequence: " + getKeySequence());
    // android.util.Log.d("SwipeAnalysis", "Was swipe typing: " + _isSwipeTyping);
    
    // Log path coordinates for calibration analysis
    StringBuilder pathStr = new StringBuilder("Path: ");
    for (int i = 0; i < Math.min(_swipePath.size(), 20); i++)
    {
      PointF p = _swipePath.get(i);
      pathStr.append(String.format("(%.0f,%.0f) ", p.x, p.y));
    }
    if (_swipePath.size() > 20)
      pathStr.append("... (" + (_swipePath.size() - 20) + " more points)");
    // android.util.Log.d("SwipeAnalysis", pathStr.toString());
    
    // Log touched keys
    StringBuilder keysStr = new StringBuilder("Touched keys: ");
    for (KeyboardData.Key key : _touchedKeys)
    {
      if (key.keys[0] != null && key.keys[0].getKind() == KeyValue.Kind.Char)
      {
        keysStr.append(key.keys[0].getChar()).append(" ");
      }
    }
    // android.util.Log.d("SwipeAnalysis", keysStr.toString());
    
    // Log velocity and gesture characteristics
    if (_swipePath.size() >= 2)
    {
      float avgVelocity = _totalDistance / (System.currentTimeMillis() - _startTime);
      // android.util.Log.d("SwipeAnalysis", "Average velocity: " + avgVelocity + " px/ms");
      
      // Calculate straightness ratio
      PointF start = _swipePath.get(0);
      PointF end = _swipePath.get(_swipePath.size() - 1);
      float directDistance = (float)Math.sqrt(
        Math.pow(end.x - start.x, 2) + Math.pow(end.y - start.y, 2)
      );
      float straightnessRatio = directDistance / _totalDistance;
      // android.util.Log.d("SwipeAnalysis", "Straightness ratio: " + straightnessRatio);
    }
    
    // android.util.Log.d("SwipeAnalysis", "================================");
  }
}
