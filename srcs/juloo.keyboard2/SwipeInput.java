package juloo.keyboard2;

import android.graphics.PointF;
import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates all data from a swipe gesture for prediction
 */
public class SwipeInput
{
  public final List<PointF> coordinates;
  public final List<Long> timestamps;
  public final List<KeyboardData.Key> touchedKeys;
  public final String keySequence;
  public final float pathLength;
  public final float duration;
  public final int directionChanges;
  public final float averageVelocity;
  public final List<Float> velocityProfile;
  public final PointF startPoint;
  public final PointF endPoint;
  public final float keyboardCoverage;
  
  public SwipeInput(List<PointF> coordinates, List<Long> timestamps, List<KeyboardData.Key> touchedKeys)
  {
    this.coordinates = new ArrayList<>(coordinates);
    this.timestamps = new ArrayList<>(timestamps);
    this.touchedKeys = new ArrayList<>(touchedKeys);
    
    // Build key sequence
    StringBuilder seq = new StringBuilder();
    for (KeyboardData.Key key : touchedKeys)
    {
      if (key != null && key.keys[0] != null)
      {
        KeyValue kv = key.keys[0];
        if (kv.getKind() == KeyValue.Kind.Char)
        {
          seq.append(kv.getChar());
        }
      }
    }
    this.keySequence = seq.toString();
    
    // Calculate metrics
    this.pathLength = calculatePathLength();
    this.duration = calculateDuration();
    this.directionChanges = calculateDirectionChanges();
    this.velocityProfile = calculateVelocityProfile();
    this.averageVelocity = calculateAverageVelocity();
    this.startPoint = coordinates.isEmpty() ? new PointF(0, 0) : coordinates.get(0);
    this.endPoint = coordinates.isEmpty() ? new PointF(0, 0) : coordinates.get(coordinates.size() - 1);
    this.keyboardCoverage = calculateKeyboardCoverage();
  }
  
  private float calculatePathLength()
  {
    float length = 0;
    for (int i = 1; i < coordinates.size(); i++)
    {
      PointF p1 = coordinates.get(i - 1);
      PointF p2 = coordinates.get(i);
      float dx = p2.x - p1.x;
      float dy = p2.y - p1.y;
      length += Math.sqrt(dx * dx + dy * dy);
    }
    return length;
  }
  
  private float calculateDuration()
  {
    if (timestamps.size() < 2)
      return 0;
    return (timestamps.get(timestamps.size() - 1) - timestamps.get(0)) / 1000.0f; // in seconds
  }
  
  private int calculateDirectionChanges()
  {
    if (coordinates.size() < 3)
      return 0;
      
    int changes = 0;
    float prevAngle = 0;
    
    for (int i = 2; i < coordinates.size(); i++)
    {
      PointF p1 = coordinates.get(i - 2);
      PointF p2 = coordinates.get(i - 1);
      PointF p3 = coordinates.get(i);
      
      float angle1 = (float)Math.atan2(p2.y - p1.y, p2.x - p1.x);
      float angle2 = (float)Math.atan2(p3.y - p2.y, p3.x - p2.x);
      
      float angleDiff = Math.abs(angle2 - angle1);
      if (angleDiff > Math.PI)
        angleDiff = (float)(2 * Math.PI - angleDiff);
      
      // Count as direction change if angle difference > 45 degrees
      if (angleDiff > Math.PI / 4)
        changes++;
    }
    
    return changes;
  }
  
  private List<Float> calculateVelocityProfile()
  {
    List<Float> velocities = new ArrayList<>();
    
    for (int i = 1; i < coordinates.size() && i < timestamps.size(); i++)
    {
      PointF p1 = coordinates.get(i - 1);
      PointF p2 = coordinates.get(i);
      long t1 = timestamps.get(i - 1);
      long t2 = timestamps.get(i);
      
      float dx = p2.x - p1.x;
      float dy = p2.y - p1.y;
      float distance = (float)Math.sqrt(dx * dx + dy * dy);
      float timeDelta = (t2 - t1) / 1000.0f; // in seconds
      
      if (timeDelta > 0)
      {
        velocities.add(distance / timeDelta);
      }
    }
    
    return velocities;
  }
  
  private float calculateAverageVelocity()
  {
    if (duration > 0)
      return pathLength / duration;
    return 0;
  }
  
  private float calculateKeyboardCoverage()
  {
    if (coordinates.isEmpty())
      return 0;
      
    float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
    float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
    
    for (PointF p : coordinates)
    {
      minX = Math.min(minX, p.x);
      maxX = Math.max(maxX, p.x);
      minY = Math.min(minY, p.y);
      maxY = Math.max(maxY, p.y);
    }
    
    float width = maxX - minX;
    float height = maxY - minY;
    
    // Approximate coverage as ratio of bounding box diagonal to expected keyboard size
    // This is a rough estimate - should be calibrated based on actual keyboard dimensions
    return (float)Math.sqrt(width * width + height * height);
  }
  
  /**
   * Check if this input represents a high-quality swipe
   */
  public boolean isHighQualitySwipe()
  {
    return pathLength > 100 && // Minimum path length
           duration > 0.1f && // Minimum duration
           duration < 3.0f && // Maximum duration
           directionChanges >= 2 && // Has some complexity
           !coordinates.isEmpty() &&
           !timestamps.isEmpty();
  }
  
  /**
   * Calculate confidence that this is a swipe vs regular typing
   */
  public float getSwipeConfidence()
  {
    float confidence = 0;
    
    // Path length factor (longer = more likely swipe)
    if (pathLength > 200) confidence += 0.3f;
    else if (pathLength > 100) confidence += 0.2f;
    else if (pathLength > 50) confidence += 0.1f;
    
    // Duration factor (swipes are typically 0.3-1.5 seconds)
    if (duration > 0.3f && duration < 1.5f) confidence += 0.25f;
    else if (duration > 0.2f && duration < 2.0f) confidence += 0.15f;
    
    // Direction changes (swipes have multiple direction changes)
    if (directionChanges >= 3) confidence += 0.25f;
    else if (directionChanges >= 2) confidence += 0.15f;
    
    // Key sequence length (swipes touch many keys)
    if (keySequence.length() > 6) confidence += 0.2f;
    else if (keySequence.length() > 4) confidence += 0.1f;
    
    return Math.min(1.0f, confidence);
  }
}