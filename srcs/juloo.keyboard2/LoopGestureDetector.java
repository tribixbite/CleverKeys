package juloo.keyboard2;

import android.graphics.PointF;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects loop gestures in swipe paths for repeated letters.
 * A loop is detected when the path curves back on itself near a key,
 * indicating the user wants to type that letter multiple times.
 * 
 * Based on analysis of gestures for words like "hello", "book", "coffee", etc.
 */
public class LoopGestureDetector
{
  private static final String TAG = "LoopGestureDetector";
  
  // Minimum angle change to consider a loop (in degrees)
  private static final float MIN_LOOP_ANGLE = 270.0f;
  
  // Maximum angle change for a loop (can't exceed 360 + tolerance)
  private static final float MAX_LOOP_ANGLE = 450.0f;
  
  // Minimum radius for a valid loop (in pixels)
  private static final float MIN_LOOP_RADIUS = 15.0f;
  
  // Maximum radius for a valid loop (relative to key size)
  private static final float MAX_LOOP_RADIUS_FACTOR = 1.5f;
  
  // Minimum points needed to form a loop
  private static final int MIN_LOOP_POINTS = 8;
  
  // Distance threshold to consider points "close" (for loop closure)
  private static final float CLOSURE_THRESHOLD = 30.0f;
  
  /**
   * Represents a detected loop in the swipe path
   */
  public static class Loop
  {
    public final int startIndex;
    public final int endIndex;
    public final PointF center;
    public final float radius;
    public final float totalAngle;
    public final char associatedKey;
    
    public Loop(int start, int end, PointF center, float radius, float angle, char key)
    {
      this.startIndex = start;
      this.endIndex = end;
      this.center = center;
      this.radius = radius;
      this.totalAngle = angle;
      this.associatedKey = key;
    }
    
    public boolean isClockwise()
    {
      return totalAngle > 0;
    }
    
    public int getRepeatCount()
    {
      // Estimate repeat count based on loop completeness
      // Full loop (360°) = 2 occurrences of the letter
      // Half loop (180°) = might be intentional curve, ignore
      float absAngle = Math.abs(totalAngle);
      if (absAngle >= 340.0f)
        return 2; // Full loop
      else if (absAngle >= 520.0f)
        return 3; // 1.5 loops
      else
        return 1; // Partial loop, treat as single occurrence
    }
  }
  
  private final float _keyWidth;
  private final float _keyHeight;
  
  public LoopGestureDetector(float keyWidth, float keyHeight)
  {
    _keyWidth = keyWidth;
    _keyHeight = keyHeight;
  }
  
  /**
   * Detect all loops in a swipe path
   * 
   * @param swipePath The complete swipe path
   * @param touchedKeys Keys that were touched during the swipe
   * @return List of detected loops
   */
  public List<Loop> detectLoops(List<PointF> swipePath, List<KeyboardData.Key> touchedKeys)
  {
    List<Loop> detectedLoops = new ArrayList<>();
    
    if (swipePath.size() < MIN_LOOP_POINTS * 2)
      return detectedLoops; // Not enough points to form a loop
    
    // Scan through the path looking for loop patterns
    for (int i = MIN_LOOP_POINTS; i < swipePath.size() - MIN_LOOP_POINTS; i++)
    {
      Loop loop = detectLoopAtPoint(swipePath, i, touchedKeys);
      if (loop != null)
      {
        detectedLoops.add(loop);
        // Skip past this loop to avoid duplicate detection
        i = loop.endIndex;
      }
    }
    
    android.util.Log.d(TAG, "Detected " + detectedLoops.size() + " loops in swipe path");
    return detectedLoops;
  }
  
  /**
   * Try to detect a loop starting around a specific point
   */
  private Loop detectLoopAtPoint(List<PointF> path, int centerIndex, List<KeyboardData.Key> keys)
  {
    // Look for points that come back close to the starting point
    PointF centerPoint = path.get(centerIndex);
    
    // Search forward for a point that comes back close
    int closureIndex = -1;
    for (int j = centerIndex + MIN_LOOP_POINTS; j < Math.min(centerIndex + 50, path.size()); j++)
    {
      float distance = distance(centerPoint, path.get(j));
      if (distance < CLOSURE_THRESHOLD)
      {
        closureIndex = j;
        break;
      }
    }
    
    if (closureIndex == -1)
      return null; // No loop closure found
    
    // Extract the potential loop segment
    List<PointF> loopSegment = path.subList(centerIndex, closureIndex + 1);
    
    // Calculate loop properties
    PointF loopCenter = calculateCenter(loopSegment);
    float avgRadius = calculateAverageRadius(loopSegment, loopCenter);
    float totalAngle = calculateTotalAngle(loopSegment, loopCenter);
    
    // Validate loop properties
    if (!isValidLoop(avgRadius, totalAngle))
      return null;
    
    // Find the associated key (closest key to loop center)
    char associatedKey = findClosestKey(loopCenter, keys);
    
    return new Loop(centerIndex, closureIndex, loopCenter, avgRadius, totalAngle, associatedKey);
  }
  
  /**
   * Calculate the geometric center of a set of points
   */
  private PointF calculateCenter(List<PointF> points)
  {
    float sumX = 0, sumY = 0;
    for (PointF p : points)
    {
      sumX += p.x;
      sumY += p.y;
    }
    return new PointF(sumX / points.size(), sumY / points.size());
  }
  
  /**
   * Calculate average radius from center to all points
   */
  private float calculateAverageRadius(List<PointF> points, PointF center)
  {
    float sumRadius = 0;
    for (PointF p : points)
    {
      sumRadius += distance(p, center);
    }
    return sumRadius / points.size();
  }
  
  /**
   * Calculate total angle traversed around the center
   * Positive = clockwise, Negative = counter-clockwise
   */
  private float calculateTotalAngle(List<PointF> points, PointF center)
  {
    if (points.size() < 3)
      return 0;
    
    float totalAngle = 0;
    
    for (int i = 1; i < points.size(); i++)
    {
      PointF p1 = points.get(i - 1);
      PointF p2 = points.get(i);
      
      // Calculate angles from center
      float angle1 = (float)Math.atan2(p1.y - center.y, p1.x - center.x);
      float angle2 = (float)Math.atan2(p2.y - center.y, p2.x - center.x);
      
      // Calculate angle difference
      float angleDiff = angle2 - angle1;
      
      // Normalize to [-π, π]
      while (angleDiff > Math.PI)
        angleDiff -= 2 * Math.PI;
      while (angleDiff < -Math.PI)
        angleDiff += 2 * Math.PI;
      
      totalAngle += angleDiff;
    }
    
    // Convert to degrees
    return (float)Math.toDegrees(totalAngle);
  }
  
  /**
   * Validate if the detected pattern is a valid loop
   */
  private boolean isValidLoop(float radius, float totalAngle)
  {
    // Check radius bounds
    if (radius < MIN_LOOP_RADIUS)
      return false;
    
    float maxRadius = Math.min(_keyWidth, _keyHeight) * MAX_LOOP_RADIUS_FACTOR;
    if (radius > maxRadius)
      return false;
    
    // Check angle (must complete most of a circle)
    float absAngle = Math.abs(totalAngle);
    if (absAngle < MIN_LOOP_ANGLE || absAngle > MAX_LOOP_ANGLE)
      return false;
    
    return true;
  }
  
  /**
   * Find the closest key to a point
   */
  private char findClosestKey(PointF point, List<KeyboardData.Key> keys)
  {
    // This is simplified - in practice would need actual key positions
    // For now, return the most recent key
    if (!keys.isEmpty())
    {
      KeyboardData.Key lastKey = keys.get(keys.size() - 1);
      if (lastKey.keys[0] != null && lastKey.keys[0].getKind() == KeyValue.Kind.Char)
      {
        return lastKey.keys[0].getChar();
      }
    }
    return ' ';
  }
  
  /**
   * Calculate Euclidean distance between two points
   */
  private float distance(PointF p1, PointF p2)
  {
    float dx = p2.x - p1.x;
    float dy = p2.y - p1.y;
    return (float)Math.sqrt(dx * dx + dy * dy);
  }
  
  /**
   * Apply loop detection results to modify the recognized key sequence
   * 
   * @param keySequence Original key sequence
   * @param loops Detected loops
   * @param swipePath Original swipe path
   * @return Modified key sequence with repeated letters
   */
  public String applyLoops(String keySequence, List<Loop> loops, List<PointF> swipePath)
  {
    if (loops.isEmpty())
      return keySequence;
    
    StringBuilder result = new StringBuilder();
    int sequenceIndex = 0;
    int pathIndex = 0;
    
    for (Loop loop : loops)
    {
      // Add characters up to the loop
      while (pathIndex < loop.startIndex && sequenceIndex < keySequence.length())
      {
        result.append(keySequence.charAt(sequenceIndex));
        sequenceIndex++;
        pathIndex += swipePath.size() / keySequence.length(); // Approximate
      }
      
      // Add the looped character multiple times
      if (loop.associatedKey != ' ')
      {
        int repeatCount = loop.getRepeatCount();
        for (int i = 0; i < repeatCount; i++)
        {
          result.append(loop.associatedKey);
        }
        // Skip past the single occurrence in the original sequence
        if (sequenceIndex < keySequence.length() && 
            keySequence.charAt(sequenceIndex) == loop.associatedKey)
        {
          sequenceIndex++;
        }
      }
      
      pathIndex = loop.endIndex;
    }
    
    // Add remaining characters
    while (sequenceIndex < keySequence.length())
    {
      result.append(keySequence.charAt(sequenceIndex));
      sequenceIndex++;
    }
    
    return result.toString();
  }
  
  /**
   * Detect if a specific word pattern contains expected loops
   * Useful for validating known words with repeated letters
   */
  public boolean matchesLoopPattern(String word, List<Loop> detectedLoops)
  {
    // Find repeated letters in the word
    List<Integer> repeatPositions = new ArrayList<>();
    for (int i = 1; i < word.length(); i++)
    {
      if (word.charAt(i) == word.charAt(i - 1))
      {
        repeatPositions.add(i);
      }
    }
    
    // Check if we have loops at approximately the right positions
    // This is a simplified check - could be made more sophisticated
    return detectedLoops.size() >= repeatPositions.size();
  }
}