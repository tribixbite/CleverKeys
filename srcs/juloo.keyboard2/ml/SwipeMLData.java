package juloo.keyboard2.ml;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PointF;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * ML data model for swipe typing training data.
 * Captures normalized swipe traces with metadata for neural network training.
 */
public class SwipeMLData
{
  private static final String TAG = "SwipeMLData";
  
  // Data fields matching ML requirements
  private final String traceId;
  private final String targetWord;
  private final long timestampUtc;
  private final int screenWidthPx;
  private final int screenHeightPx;
  private final int keyboardHeightPx;
  private final String collectionSource; // "calibration" or "user_selection"
  private final List<TracePoint> tracePoints;
  private final List<String> registeredKeys;
  private int keyboardOffsetY = 0; // Y offset of keyboard from top of screen
  
  // Constructor for new swipe data
  public SwipeMLData(String targetWord, String collectionSource,
                     int screenWidth, int screenHeight, int keyboardHeight)
  {
    this.traceId = UUID.randomUUID().toString();
    this.targetWord = targetWord.toLowerCase();
    this.timestampUtc = System.currentTimeMillis();
    this.screenWidthPx = screenWidth;
    this.screenHeightPx = screenHeight;
    this.keyboardHeightPx = keyboardHeight;
    this.collectionSource = collectionSource;
    this.tracePoints = new ArrayList<>();
    this.registeredKeys = new ArrayList<>();
  }
  
  // Constructor from JSON (for loading stored data)
  public SwipeMLData(JSONObject json) throws JSONException
  {
    this.traceId = json.getString("trace_id");
    this.targetWord = json.getString("target_word");
    
    JSONObject metadata = json.getJSONObject("metadata");
    this.timestampUtc = metadata.getLong("timestamp_utc");
    this.screenWidthPx = metadata.getInt("screen_width_px");
    this.screenHeightPx = metadata.getInt("screen_height_px");
    this.keyboardHeightPx = metadata.getInt("keyboard_height_px");
    this.collectionSource = metadata.getString("collection_source");
    
    // Load trace points
    this.tracePoints = new ArrayList<>();
    JSONArray pointsArray = json.getJSONArray("trace_points");
    for (int i = 0; i < pointsArray.length(); i++)
    {
      JSONObject point = pointsArray.getJSONObject(i);
      tracePoints.add(new TracePoint(
        (float)point.getDouble("x"),
        (float)point.getDouble("y"),
        point.getLong("t_delta_ms")
      ));
    }
    
    // Load registered keys
    this.registeredKeys = new ArrayList<>();
    JSONArray keysArray = json.getJSONArray("registered_keys");
    for (int i = 0; i < keysArray.length(); i++)
    {
      registeredKeys.add(keysArray.getString(i));
    }
  }
  
  /**
   * Add a raw trace point (will be normalized)
   */
  public void addRawPoint(float rawX, float rawY, long timestamp)
  {
    // Normalize coordinates to [0, 1] range
    float normalizedX = rawX / screenWidthPx;
    float normalizedY = rawY / screenHeightPx;
    
    // Calculate time delta from previous point (0 for first point)
    long deltaMs = 0;
    if (!tracePoints.isEmpty())
    {
      TracePoint lastPoint = tracePoints.get(tracePoints.size() - 1);
      // Sum up previous deltas to get absolute time, then calculate new delta
      long lastAbsoluteTime = 0;
      for (int i = 0; i < tracePoints.size() - 1; i++)
      {
        lastAbsoluteTime += tracePoints.get(i).tDeltaMs;
      }
      deltaMs = timestamp - (timestampUtc + lastAbsoluteTime);
    }
    
    tracePoints.add(new TracePoint(normalizedX, normalizedY, deltaMs));
  }
  
  /**
   * Add a registered key from the swipe path
   */
  public void addRegisteredKey(String key)
  {
    // Avoid consecutive duplicates
    if (registeredKeys.isEmpty() || !registeredKeys.get(registeredKeys.size() - 1).equals(key))
    {
      registeredKeys.add(key.toLowerCase());
    }
  }
  
  /**
   * Set keyboard dimensions for accurate position tracking
   */
  public void setKeyboardDimensions(int screenWidth, int keyboardHeight, int keyboardOffsetY)
  {
    this.keyboardOffsetY = keyboardOffsetY;
    // Note: screenWidth and keyboardHeight are already set in constructor
    // This method mainly records the Y offset for position normalization
  }
  
  /**
   * Convert to JSON for storage and export
   */
  public JSONObject toJSON() throws JSONException
  {
    JSONObject json = new JSONObject();
    json.put("trace_id", traceId);
    json.put("target_word", targetWord);
    
    // Metadata
    JSONObject metadata = new JSONObject();
    metadata.put("timestamp_utc", timestampUtc);
    metadata.put("screen_width_px", screenWidthPx);
    metadata.put("screen_height_px", screenHeightPx);
    metadata.put("keyboard_height_px", keyboardHeightPx);
    metadata.put("keyboard_offset_y", keyboardOffsetY);
    metadata.put("collection_source", collectionSource);
    json.put("metadata", metadata);
    
    // Trace points
    JSONArray pointsArray = new JSONArray();
    for (TracePoint point : tracePoints)
    {
      JSONObject p = new JSONObject();
      p.put("x", point.x);
      p.put("y", point.y);
      p.put("t_delta_ms", point.tDeltaMs);
      pointsArray.put(p);
    }
    json.put("trace_points", pointsArray);
    
    // Registered keys
    JSONArray keysArray = new JSONArray();
    for (String key : registeredKeys)
    {
      keysArray.put(key);
    }
    json.put("registered_keys", keysArray);
    
    return json;
  }
  
  /**
   * Validate data quality before storage
   */
  public boolean isValid()
  {
    // Must have at least 2 points for a valid swipe
    if (tracePoints.size() < 2)
      return false;
    
    // Must have at least 2 registered keys
    if (registeredKeys.size() < 2)
      return false;
    
    // Target word must not be empty
    if (targetWord == null || targetWord.isEmpty())
      return false;
    
    // Check for reasonable normalized values
    for (TracePoint point : tracePoints)
    {
      if (point.x < 0 || point.x > 1 || point.y < 0 || point.y > 1)
        return false;
    }
    
    return true;
  }
  
  /**
   * Calculate statistics for this swipe
   */
  public SwipeStatistics calculateStatistics()
  {
    if (tracePoints.size() < 2)
      return null;
    
    float totalDistance = 0;
    long totalTime = 0;
    
    for (int i = 1; i < tracePoints.size(); i++)
    {
      TracePoint prev = tracePoints.get(i - 1);
      TracePoint curr = tracePoints.get(i);
      
      float dx = curr.x - prev.x;
      float dy = curr.y - prev.y;
      totalDistance += Math.sqrt(dx * dx + dy * dy);
      totalTime += curr.tDeltaMs;
    }
    
    // Calculate straightness ratio
    TracePoint start = tracePoints.get(0);
    TracePoint end = tracePoints.get(tracePoints.size() - 1);
    float directDistance = (float)Math.sqrt(
      Math.pow(end.x - start.x, 2) + Math.pow(end.y - start.y, 2)
    );
    float straightnessRatio = totalDistance > 0 ? directDistance / totalDistance : 0;
    
    return new SwipeStatistics(
      tracePoints.size(),
      totalDistance,
      totalTime,
      straightnessRatio,
      registeredKeys.size()
    );
  }
  
  // Getters
  public String getTraceId() { return traceId; }
  public String getTargetWord() { return targetWord; }
  public long getTimestampUtc() { return timestampUtc; }
  public String getCollectionSource() { return collectionSource; }
  public List<TracePoint> getTracePoints() { return new ArrayList<>(tracePoints); }
  public List<String> getRegisteredKeys() { return new ArrayList<>(registeredKeys); }
  
  /**
   * Inner class for normalized trace points
   */
  public static class TracePoint
  {
    public final float x;       // Normalized [0, 1]
    public final float y;       // Normalized [0, 1]
    public final long tDeltaMs; // Time delta from previous point
    
    public TracePoint(float x, float y, long tDeltaMs)
    {
      this.x = x;
      this.y = y;
      this.tDeltaMs = tDeltaMs;
    }
  }
  
  /**
   * Statistics for analysis
   */
  public static class SwipeStatistics
  {
    public final int pointCount;
    public final float totalDistance;
    public final long totalTimeMs;
    public final float straightnessRatio;
    public final int keyCount;
    
    public SwipeStatistics(int pointCount, float totalDistance, long totalTimeMs,
                           float straightnessRatio, int keyCount)
    {
      this.pointCount = pointCount;
      this.totalDistance = totalDistance;
      this.totalTimeMs = totalTimeMs;
      this.straightnessRatio = straightnessRatio;
      this.keyCount = keyCount;
    }
  }
}