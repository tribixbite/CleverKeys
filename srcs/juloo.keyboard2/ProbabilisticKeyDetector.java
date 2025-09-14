package juloo.keyboard2;

import android.graphics.PointF;
import android.graphics.RectF;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.Comparator;

/**
 * Probabilistic key detection using Gaussian weighting based on distance from swipe path
 */
public class ProbabilisticKeyDetector
{
  private final KeyboardData _keyboard;
  private final float _keyboardWidth;
  private final float _keyboardHeight;
  
  // Parameters for Gaussian probability
  private static final float SIGMA_FACTOR = 0.5f; // Key width/height multiplier for standard deviation
  private static final float MIN_PROBABILITY = 0.01f; // Minimum probability to consider a key
  private static final float PROBABILITY_THRESHOLD = 0.3f; // Minimum cumulative probability to register key
  
  public ProbabilisticKeyDetector(KeyboardData keyboard, float width, float height)
  {
    _keyboard = keyboard;
    _keyboardWidth = width;
    _keyboardHeight = height;
  }
  
  /**
   * Detect keys along a swipe path using probabilistic weighting
   */
  public List<KeyboardData.Key> detectKeys(List<PointF> swipePath)
  {
    if (swipePath == null || swipePath.isEmpty() || _keyboard == null)
      return new ArrayList<>();
    
    // Calculate probability map for all keys
    Map<KeyboardData.Key, Float> keyProbabilities = new HashMap<>();
    
    // Process each point in the swipe path
    for (PointF point : swipePath)
    {
      processPathPoint(point, keyProbabilities);
    }
    
    // Convert probabilities to ordered key sequence
    return extractKeySequence(keyProbabilities, swipePath);
  }
  
  /**
   * Process a single point on the swipe path
   */
  private void processPathPoint(PointF point, Map<KeyboardData.Key, Float> keyProbabilities)
  {
    // Find keys near this point
    List<KeyWithDistance> nearbyKeys = findNearbyKeys(point);
    
    // Calculate probability for each nearby key
    for (KeyWithDistance kwd : nearbyKeys)
    {
      float probability = calculateGaussianProbability(kwd.distance, kwd.key);
      
      if (probability > MIN_PROBABILITY)
      {
        // Accumulate probability
        float currentProb = keyProbabilities.getOrDefault(kwd.key, 0f);
        keyProbabilities.put(kwd.key, currentProb + probability);
      }
    }
  }
  
  /**
   * Find keys within reasonable distance of a point
   */
  private List<KeyWithDistance> findNearbyKeys(PointF point)
  {
    List<KeyWithDistance> nearbyKeys = new ArrayList<>();
    
    if (_keyboard.rows == null)
      return nearbyKeys;
    
    // Check all keys (could be optimized with spatial indexing)
    float y = 0;
    for (KeyboardData.Row row : _keyboard.rows)
    {
      float x = 0;
      float rowHeight = row.height * _keyboardHeight;
      
      for (KeyboardData.Key key : row.keys)
      {
        if (key == null || key.keys[0] == null)
        {
          x += key.width * _keyboardWidth;
          continue;
        }
        
        // Check if alphabetic
        if (!isAlphabeticKey(key))
        {
          x += key.width * _keyboardWidth;
          continue;
        }
        
        float keyWidth = key.width * _keyboardWidth;
        
        // Calculate key center
        float keyCenterX = x + keyWidth / 2;
        float keyCenterY = y + rowHeight / 2;
        
        // Calculate distance from point to key center
        float dx = point.x - keyCenterX;
        float dy = point.y - keyCenterY;
        float distance = (float)Math.sqrt(dx * dx + dy * dy);
        
        // Only consider keys within 2x key width
        float maxDistance = Math.max(keyWidth, rowHeight) * 2;
        if (distance < maxDistance)
        {
          nearbyKeys.add(new KeyWithDistance(key, distance, keyWidth, rowHeight));
        }
        
        x += keyWidth;
      }
      y += rowHeight;
    }
    
    return nearbyKeys;
  }
  
  /**
   * Calculate Gaussian probability based on distance
   */
  private float calculateGaussianProbability(float distance, KeyboardData.Key key)
  {
    // Estimate key size (would be better to have actual dimensions)
    float keySize = _keyboardWidth / 10; // Approximate for QWERTY
    float sigma = keySize * SIGMA_FACTOR;
    
    // Gaussian formula: exp(-(distance^2) / (2 * sigma^2))
    float probability = (float)Math.exp(-(distance * distance) / (2 * sigma * sigma));
    
    return probability;
  }
  
  /**
   * Extract ordered key sequence from probability map
   */
  private List<KeyboardData.Key> extractKeySequence(Map<KeyboardData.Key, Float> keyProbabilities, 
                                                    List<PointF> swipePath)
  {
    // Filter keys by probability threshold
    List<KeyCandidate> candidates = new ArrayList<>();
    for (Map.Entry<KeyboardData.Key, Float> entry : keyProbabilities.entrySet())
    {
      float normalizedProb = entry.getValue() / swipePath.size();
      if (normalizedProb > PROBABILITY_THRESHOLD)
      {
        candidates.add(new KeyCandidate(entry.getKey(), normalizedProb));
      }
    }
    
    // Sort by probability
    Collections.sort(candidates, new Comparator<KeyCandidate>() {
      @Override
      public int compare(KeyCandidate a, KeyCandidate b) {
        return Float.compare(b.probability, a.probability);
      }
    });
    
    // Order keys by their appearance along the path
    List<KeyboardData.Key> orderedKeys = orderKeysByPath(candidates, swipePath);
    
    return orderedKeys;
  }
  
  /**
   * Order keys based on when they appear along the swipe path
   */
  private List<KeyboardData.Key> orderKeysByPath(List<KeyCandidate> candidates, List<PointF> swipePath)
  {
    // For each candidate, find its first strong appearance in the path
    for (KeyCandidate candidate : candidates)
    {
      candidate.pathIndex = findKeyPathIndex(candidate.key, swipePath);
    }
    
    // Sort by path index
    Collections.sort(candidates, new Comparator<KeyCandidate>() {
      @Override
      public int compare(KeyCandidate a, KeyCandidate b) {
        return Integer.compare(a.pathIndex, b.pathIndex);
      }
    });
    
    // Extract ordered keys
    List<KeyboardData.Key> orderedKeys = new ArrayList<>();
    for (KeyCandidate candidate : candidates)
    {
      if (candidate.pathIndex >= 0)
      {
        orderedKeys.add(candidate.key);
      }
    }
    
    return orderedKeys;
  }
  
  /**
   * Find where along the path a key most strongly appears
   */
  private int findKeyPathIndex(KeyboardData.Key key, List<PointF> swipePath)
  {
    // This is simplified - would need key position information
    // For now, return middle of path
    return swipePath.size() / 2;
  }
  
  /**
   * Check if key is alphabetic
   */
  private boolean isAlphabeticKey(KeyboardData.Key key)
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
   * Helper class for key with distance
   */
  private static class KeyWithDistance
  {
    final KeyboardData.Key key;
    final float distance;
    final float keyWidth;
    final float keyHeight;
    
    KeyWithDistance(KeyboardData.Key key, float distance, float width, float height)
    {
      this.key = key;
      this.distance = distance;
      this.keyWidth = width;
      this.keyHeight = height;
    }
  }
  
  /**
   * Helper class for key candidates
   */
  private static class KeyCandidate
  {
    final KeyboardData.Key key;
    final float probability;
    int pathIndex;
    
    KeyCandidate(KeyboardData.Key key, float probability)
    {
      this.key = key;
      this.probability = probability;
      this.pathIndex = -1;
    }
  }
  
  /**
   * Apply Ramer-Douglas-Peucker algorithm for path simplification
   */
  public static List<PointF> simplifyPath(List<PointF> points, float epsilon)
  {
    if (points == null || points.size() < 3)
      return points;
    
    // Find point with maximum distance from line between first and last
    float maxDist = 0;
    int maxIndex = 0;
    
    PointF first = points.get(0);
    PointF last = points.get(points.size() - 1);
    
    for (int i = 1; i < points.size() - 1; i++)
    {
      float dist = perpendicularDistance(points.get(i), first, last);
      if (dist > maxDist)
      {
        maxDist = dist;
        maxIndex = i;
      }
    }
    
    // If max distance is greater than epsilon, recursively simplify
    if (maxDist > epsilon)
    {
      // Recursive call
      List<PointF> firstPart = simplifyPath(points.subList(0, maxIndex + 1), epsilon);
      List<PointF> secondPart = simplifyPath(points.subList(maxIndex, points.size()), epsilon);
      
      // Combine results
      List<PointF> result = new ArrayList<>(firstPart.subList(0, firstPart.size() - 1));
      result.addAll(secondPart);
      return result;
    }
    else
    {
      // Return just the endpoints
      List<PointF> result = new ArrayList<>();
      result.add(first);
      result.add(last);
      return result;
    }
  }
  
  /**
   * Calculate perpendicular distance from point to line
   */
  private static float perpendicularDistance(PointF point, PointF lineStart, PointF lineEnd)
  {
    float dx = lineEnd.x - lineStart.x;
    float dy = lineEnd.y - lineStart.y;
    
    if (dx == 0 && dy == 0)
    {
      // Line start and end are the same
      dx = point.x - lineStart.x;
      dy = point.y - lineStart.y;
      return (float)Math.sqrt(dx * dx + dy * dy);
    }
    
    float t = ((point.x - lineStart.x) * dx + (point.y - lineStart.y) * dy) / (dx * dx + dy * dy);
    t = Math.max(0, Math.min(1, t));
    
    float nearestX = lineStart.x + t * dx;
    float nearestY = lineStart.y + t * dy;
    
    dx = point.x - nearestX;
    dy = point.y - nearestY;
    
    return (float)Math.sqrt(dx * dx + dy * dy);
  }
}