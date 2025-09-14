package juloo.keyboard2;

import android.graphics.PointF;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Prunes candidate words for swipe typing based on extremities.
 * Based on FlorisBoard's pruning approach.
 */
public class SwipePruner
{
  // Map of first-last letter pairs to words
  private final Map<String, List<String>> _extremityMap;
  private final Map<String, Integer> _dictionary;
  
  // Distance threshold for considering a key "close" to a point (in normalized units)
  private static final float KEY_PROXIMITY_THRESHOLD = 0.15f;
  
  // Number of closest keys to consider for start/end points
  private static final int N_CLOSEST_KEYS = 2;
  
  public SwipePruner(Map<String, Integer> dictionary)
  {
    _dictionary = dictionary;
    _extremityMap = new HashMap<>();
    buildExtremityMap();
  }
  
  /**
   * Build a map of first-last letter pairs to words for fast lookup
   */
  private void buildExtremityMap()
  {
    for (String word : _dictionary.keySet())
    {
      if (word.length() < 2)
        continue;
      
      char first = word.charAt(0);
      char last = word.charAt(word.length() - 1);
      String key = first + "" + last;
      
      List<String> words = _extremityMap.get(key);
      if (words == null)
      {
        words = new ArrayList<>();
        _extremityMap.put(key, words);
      }
      words.add(word);
    }
    
    android.util.Log.d("SwipePruner", "Built extremity map with " + _extremityMap.size() + " unique pairs");
  }
  
  /**
   * Find candidate words based on the start and end points of a swipe gesture.
   * This significantly reduces the search space for DTW/prediction algorithms.
   */
  public List<String> pruneByExtremities(List<PointF> swipePath, List<KeyboardData.Key> touchedKeys)
  {
    if (swipePath.size() < 2 || touchedKeys.isEmpty())
      return new ArrayList<>(_dictionary.keySet());
    
    // Get start and end points
    PointF startPoint = swipePath.get(0);
    PointF endPoint = swipePath.get(swipePath.size() - 1);
    
    // Find the closest keys to start and end points
    List<Character> startKeys = findClosestKeys(startPoint, touchedKeys, N_CLOSEST_KEYS);
    List<Character> endKeys = findClosestKeys(endPoint, touchedKeys, N_CLOSEST_KEYS);
    
    // Build candidate list from all combinations
    List<String> candidates = new ArrayList<>();
    for (Character startKey : startKeys)
    {
      for (Character endKey : endKeys)
      {
        String extremityKey = startKey + "" + endKey;
        List<String> words = _extremityMap.get(extremityKey);
        if (words != null)
        {
          candidates.addAll(words);
        }
      }
    }
    
    // If no candidates found with extremities, be less strict
    if (candidates.isEmpty())
    {
      android.util.Log.d("SwipePruner", "No candidates with extremities, falling back to touched keys");
      // Fall back to using first and last touched keys
      if (!touchedKeys.isEmpty())
      {
        KeyboardData.Key firstKey = touchedKeys.get(0);
        KeyboardData.Key lastKey = touchedKeys.get(touchedKeys.size() - 1);
        
        if (firstKey.keys[0] != null && lastKey.keys[0] != null)
        {
          char first = firstKey.keys[0].getString().toLowerCase().charAt(0);
          char last = lastKey.keys[0].getString().toLowerCase().charAt(0);
          String extremityKey = first + "" + last;
          List<String> words = _extremityMap.get(extremityKey);
          if (words != null)
          {
            candidates.addAll(words);
          }
        }
      }
    }
    
    android.util.Log.d("SwipePruner", "Pruned to " + candidates.size() + " candidates from " + _dictionary.size());
    
    return candidates.isEmpty() ? new ArrayList<>(_dictionary.keySet()) : candidates;
  }
  
  /**
   * Find the N closest keys to a given point
   * Since we don't have key positions, use the touched keys list
   */
  private List<Character> findClosestKeys(PointF point, List<KeyboardData.Key> keys, int n)
  {
    List<Character> result = new ArrayList<>();
    
    // Since we don't have key positions in KeyboardData.Key,
    // we'll use the first and last touched keys as approximation
    // This is a simplified approach - ideally we'd have access to key bounds
    
    for (KeyboardData.Key key : keys)
    {
      if (key.keys[0] == null || !isAlphabeticKey(key.keys[0]))
        continue;
      
      char keyChar = key.keys[0].getString().toLowerCase().charAt(0);
      if (!result.contains(keyChar))
      {
        result.add(keyChar);
      }
      
      if (result.size() >= n)
        break;
    }
    
    return result;
  }
  
  /**
   * Calculate distance between two points
   */
  private float distance(float x1, float y1, float x2, float y2)
  {
    float dx = x2 - x1;
    float dy = y2 - y1;
    return (float)Math.sqrt(dx * dx + dy * dy);
  }
  
  /**
   * Check if a key value represents an alphabetic character
   */
  private boolean isAlphabeticKey(KeyValue kv)
  {
    if (kv == null)
      return false;
    
    switch (kv.getKind())
    {
      case Char:
        char c = kv.getChar();
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
      case String:
        String s = kv.getString();
        return s.length() == 1 && (
          (s.charAt(0) >= 'a' && s.charAt(0) <= 'z') ||
          (s.charAt(0) >= 'A' && s.charAt(0) <= 'Z'));
      default:
        return false;
    }
  }
  
  /**
   * Simple class to hold key-distance pairs
   */
  private static class KeyDistance
  {
    final char key;
    final float distance;
    
    KeyDistance(char key, float distance)
    {
      this.key = key;
      this.distance = distance;
    }
  }
  
  /**
   * Prune candidates by path length similarity.
   * Words that are too different in length from the swipe path are removed.
   */
  public List<String> pruneByLength(List<PointF> swipePath, List<String> candidates,
                                    float keyWidth, float lengthThreshold)
  {
    if (swipePath.size() < 2)
      return candidates;
    
    // Calculate total swipe path length
    float pathLength = 0;
    for (int i = 1; i < swipePath.size(); i++)
    {
      PointF p1 = swipePath.get(i - 1);
      PointF p2 = swipePath.get(i);
      pathLength += distance(p1.x, p1.y, p2.x, p2.y);
    }
    
    List<String> filtered = new ArrayList<>();
    
    for (String word : candidates)
    {
      // Estimate ideal path length for this word
      // Approximate as (word.length() - 1) * average key distance
      float idealLength = (word.length() - 1) * keyWidth * 0.8f;
      
      // Check if within threshold
      if (Math.abs(pathLength - idealLength) < lengthThreshold * keyWidth)
      {
        filtered.add(word);
      }
    }
    
    android.util.Log.d("SwipePruner", "Length pruning: " + candidates.size() + " -> " + filtered.size());
    
    return filtered.isEmpty() ? candidates : filtered;
  }
}