package juloo.keyboard2;

import android.content.Context;
import android.graphics.PointF;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Enhanced word prediction engine with advanced algorithms inspired by FlorisBoard
 * Features:
 * - Shape-based gesture matching
 * - Location-based scoring
 * - Path smoothing
 * - Trie-based dictionary for O(log n) lookups
 * - Dynamic programming for edit distance
 */
public class EnhancedWordPredictor
{
  private TrieNode _dictionaryRoot;
  private final Map<Character, List<Character>> _adjacentKeys;
  private final Map<Character, PointF> _keyPositions;
  
  // Algorithm parameters from FlorisBoard research
  private static final int MAX_PREDICTIONS = 10;
  private static final int SAMPLING_POINTS = 50; // Number of points to resample gesture to
  private static final float SHAPE_WEIGHT = 0.4f;
  private static final float LOCATION_WEIGHT = 0.3f;
  private static final float FREQUENCY_WEIGHT = 0.3f;
  private static final float LENGTH_PENALTY = 0.1f;
  
  // Path smoothing parameters
  private static final int SMOOTHING_WINDOW = 3;
  private static final float SMOOTHING_FACTOR = 0.5f;
  
  public EnhancedWordPredictor()
  {
    _dictionaryRoot = new TrieNode();
    _adjacentKeys = buildAdjacentKeysMap();
    _keyPositions = buildKeyPositionsMap();
  }
  
  /**
   * Load enhanced dictionary with frequency data
   */
  public void loadEnhancedDictionary(Context context, String language)
  {
    _dictionaryRoot = new TrieNode();
    String filename = "dictionaries/" + language + "_enhanced.txt";
    
    try
    {
      BufferedReader reader = new BufferedReader(
        new InputStreamReader(context.getAssets().open(filename)));
      String line;
      while ((line = reader.readLine()) != null)
      {
        if (line.startsWith("#") || line.trim().isEmpty())
          continue;
          
        String[] parts = line.trim().split("\t");
        if (parts.length >= 2)
        {
          String word = parts[0].toLowerCase();
          int frequency = Integer.parseInt(parts[1]);
          insertWord(word, frequency);
        }
      }
      reader.close();
    }
    catch (IOException e)
    {
      // Fall back to basic dictionary
      loadBasicDictionary();
    }
  }
  
  /**
   * Insert word into trie for fast prefix matching
   */
  private void insertWord(String word, int frequency)
  {
    TrieNode current = _dictionaryRoot;
    for (char c : word.toCharArray())
    {
      if (!current.children.containsKey(c))
      {
        current.children.put(c, new TrieNode());
      }
      current = current.children.get(c);
    }
    current.isWord = true;
    current.frequency = frequency;
    current.word = word;
  }
  
  /**
   * Enhanced prediction using gesture path analysis
   */
  public List<String> predictFromGesture(List<PointF> gesturePath, List<Character> touchedKeys)
  {
    if (gesturePath == null || gesturePath.size() < 2)
      return new ArrayList<>();
    
    // Smooth the gesture path to reduce noise
    List<PointF> smoothedPath = smoothPath(gesturePath);
    
    // Resample path to fixed number of points for comparison
    List<PointF> resampledPath = resamplePath(smoothedPath, SAMPLING_POINTS);
    
    // Normalize path for shape comparison
    List<PointF> normalizedPath = normalizePath(resampledPath);
    
    // Find candidate words using trie traversal
    List<WordCandidate> candidates = findCandidates(touchedKeys, resampledPath, normalizedPath);
    
    // Sort by combined score
    Collections.sort(candidates, (a, b) -> Float.compare(b.score, a.score));
    
    // Return top predictions
    List<String> predictions = new ArrayList<>();
    for (int i = 0; i < Math.min(candidates.size(), MAX_PREDICTIONS); i++)
    {
      predictions.add(candidates.get(i).word);
    }
    
    return predictions;
  }
  
  /**
   * Smooth gesture path using moving average
   */
  private List<PointF> smoothPath(List<PointF> path)
  {
    if (path.size() < SMOOTHING_WINDOW)
      return path;
    
    List<PointF> smoothed = new ArrayList<>();
    
    for (int i = 0; i < path.size(); i++)
    {
      float sumX = 0, sumY = 0;
      int count = 0;
      
      for (int j = Math.max(0, i - SMOOTHING_WINDOW / 2); 
           j <= Math.min(path.size() - 1, i + SMOOTHING_WINDOW / 2); j++)
      {
        sumX += path.get(j).x;
        sumY += path.get(j).y;
        count++;
      }
      
      float smoothX = sumX / count;
      float smoothY = sumY / count;
      
      // Blend with original point
      float origX = path.get(i).x;
      float origY = path.get(i).y;
      
      smoothed.add(new PointF(
        origX * (1 - SMOOTHING_FACTOR) + smoothX * SMOOTHING_FACTOR,
        origY * (1 - SMOOTHING_FACTOR) + smoothY * SMOOTHING_FACTOR
      ));
    }
    
    return smoothed;
  }
  
  /**
   * Resample path to fixed number of points
   */
  private List<PointF> resamplePath(List<PointF> path, int numPoints)
  {
    if (path.size() < 2)
      return path;
    
    List<PointF> resampled = new ArrayList<>();
    float totalLength = calculatePathLength(path);
    float segmentLength = totalLength / (numPoints - 1);
    
    float accumulatedLength = 0;
    resampled.add(new PointF(path.get(0).x, path.get(0).y));
    
    for (int i = 1; i < path.size(); i++)
    {
      PointF prev = path.get(i - 1);
      PointF curr = path.get(i);
      float dist = distance(prev, curr);
      
      if (accumulatedLength + dist >= segmentLength)
      {
        float ratio = (segmentLength - accumulatedLength) / dist;
        float x = prev.x + ratio * (curr.x - prev.x);
        float y = prev.y + ratio * (curr.y - prev.y);
        resampled.add(new PointF(x, y));
        
        if (resampled.size() >= numPoints)
          break;
          
        accumulatedLength = 0;
      }
      else
      {
        accumulatedLength += dist;
      }
    }
    
    // Ensure we have exactly numPoints
    while (resampled.size() < numPoints)
    {
      resampled.add(new PointF(path.get(path.size() - 1).x, path.get(path.size() - 1).y));
    }
    
    return resampled;
  }
  
  /**
   * Normalize path to unit square for shape comparison
   */
  private List<PointF> normalizePath(List<PointF> path)
  {
    if (path.isEmpty())
      return path;
    
    // Find bounding box
    float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
    float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
    
    for (PointF p : path)
    {
      minX = Math.min(minX, p.x);
      maxX = Math.max(maxX, p.x);
      minY = Math.min(minY, p.y);
      maxY = Math.max(maxY, p.y);
    }
    
    float width = maxX - minX;
    float height = maxY - minY;
    
    if (width == 0) width = 1;
    if (height == 0) height = 1;
    
    // Scale to unit square
    List<PointF> normalized = new ArrayList<>();
    for (PointF p : path)
    {
      float x = (p.x - minX) / width;
      float y = (p.y - minY) / height;
      normalized.add(new PointF(x, y));
    }
    
    return normalized;
  }
  
  /**
   * Find candidate words using advanced matching
   */
  private List<WordCandidate> findCandidates(List<Character> touchedKeys, 
                                             List<PointF> resampledPath,
                                             List<PointF> normalizedPath)
  {
    List<WordCandidate> candidates = new ArrayList<>();
    
    // Build approximate key sequence
    String keySequence = buildKeySequence(touchedKeys);
    
    // Use trie to find words with similar prefixes
    findCandidatesFromTrie(_dictionaryRoot, keySequence, "", 0, candidates, 
                          resampledPath, normalizedPath);
    
    return candidates;
  }
  
  /**
   * Recursive trie traversal for candidate finding
   */
  private void findCandidatesFromTrie(TrieNode node, String target, String current, 
                                      int targetIndex, List<WordCandidate> candidates,
                                      List<PointF> resampledPath, List<PointF> normalizedPath)
  {
    if (node.isWord && Math.abs(current.length() - target.length()) <= 2)
    {
      // Calculate score for this word
      float score = calculateWordScore(node.word, node.frequency, target, 
                                       resampledPath, normalizedPath);
      candidates.add(new WordCandidate(node.word, score));
    }
    
    // Continue traversal if we haven't gone too far
    if (targetIndex < target.length() + 2)
    {
      for (Map.Entry<Character, TrieNode> entry : node.children.entrySet())
      {
        char c = entry.getKey();
        
        // Allow character if it matches target or is adjacent
        if (targetIndex < target.length())
        {
          char targetChar = target.charAt(targetIndex);
          if (c == targetChar || isAdjacent(c, targetChar))
          {
            findCandidatesFromTrie(entry.getValue(), target, current + c, 
                                  targetIndex + 1, candidates, resampledPath, normalizedPath);
          }
        }
        
        // Also explore skipping characters (for shorter words)
        if (current.length() < target.length())
        {
          findCandidatesFromTrie(entry.getValue(), target, current + c, 
                                targetIndex, candidates, resampledPath, normalizedPath);
        }
      }
    }
  }
  
  /**
   * Calculate comprehensive score for a word
   */
  private float calculateWordScore(String word, int frequency, String keySequence,
                                   List<PointF> resampledPath, List<PointF> normalizedPath)
  {
    // Shape score - how well does the word's ideal path match the gesture?
    float shapeScore = calculateShapeScore(word, normalizedPath);
    
    // Location score - how close are the gesture points to the expected keys?
    float locationScore = calculateLocationScore(word, resampledPath);
    
    // Frequency score - how common is this word?
    float frequencyScore = (float)frequency / 50000f;
    
    // Length penalty - penalize words that are very different in length
    float lengthDiff = Math.abs(word.length() - keySequence.length());
    float lengthScore = 1.0f / (1.0f + lengthDiff * LENGTH_PENALTY);
    
    // Combine scores
    float totalScore = (shapeScore * SHAPE_WEIGHT + 
                       locationScore * LOCATION_WEIGHT + 
                       frequencyScore * FREQUENCY_WEIGHT) * lengthScore;
    
    return totalScore;
  }
  
  /**
   * Calculate shape similarity between ideal word path and gesture
   */
  private float calculateShapeScore(String word, List<PointF> normalizedPath)
  {
    // Generate ideal path for word
    List<PointF> idealPath = generateIdealPath(word);
    
    if (idealPath.size() < 2)
      return 0;
    
    // Resample and normalize ideal path
    idealPath = resamplePath(idealPath, SAMPLING_POINTS);
    idealPath = normalizePath(idealPath);
    
    // Calculate Euclidean distance between paths
    float totalDistance = 0;
    for (int i = 0; i < Math.min(idealPath.size(), normalizedPath.size()); i++)
    {
      totalDistance += distance(idealPath.get(i), normalizedPath.get(i));
    }
    
    // Convert distance to similarity score (0-1)
    float avgDistance = totalDistance / SAMPLING_POINTS;
    return 1.0f / (1.0f + avgDistance);
  }
  
  /**
   * Calculate location accuracy score
   */
  private float calculateLocationScore(String word, List<PointF> resampledPath)
  {
    List<PointF> idealPath = generateIdealPath(word);
    
    if (idealPath.size() < 2)
      return 0;
    
    idealPath = resamplePath(idealPath, SAMPLING_POINTS);
    
    // Calculate average distance between corresponding points
    float totalDistance = 0;
    for (int i = 0; i < Math.min(idealPath.size(), resampledPath.size()); i++)
    {
      totalDistance += distance(idealPath.get(i), resampledPath.get(i));
    }
    
    float avgDistance = totalDistance / SAMPLING_POINTS;
    
    // Normalize by keyboard size (approximate)
    float normalizedDistance = avgDistance / 100f; // Assuming ~100px key width
    
    return 1.0f / (1.0f + normalizedDistance);
  }
  
  /**
   * Generate ideal swipe path for a word
   */
  private List<PointF> generateIdealPath(String word)
  {
    List<PointF> path = new ArrayList<>();
    
    for (char c : word.toCharArray())
    {
      PointF pos = _keyPositions.get(c);
      if (pos != null)
      {
        path.add(new PointF(pos.x, pos.y));
      }
    }
    
    return path;
  }
  
  /**
   * Build key positions map for QWERTY layout
   */
  private Map<Character, PointF> buildKeyPositionsMap()
  {
    Map<Character, PointF> positions = new HashMap<>();
    
    // QWERTY layout positions (normalized 0-1)
    String[] rows = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
    float[] rowY = {0.25f, 0.5f, 0.75f};
    float[] rowOffsets = {0f, 0.05f, 0.15f};
    
    for (int r = 0; r < rows.length; r++)
    {
      String row = rows[r];
      float y = rowY[r];
      float offset = rowOffsets[r];
      
      for (int c = 0; c < row.length(); c++)
      {
        float x = offset + (c * (1.0f - 2 * offset) / row.length());
        positions.put(row.charAt(c), new PointF(x, y));
      }
    }
    
    return positions;
  }
  
  /**
   * Build adjacent keys map
   */
  private Map<Character, List<Character>> buildAdjacentKeysMap()
  {
    Map<Character, List<Character>> adjacent = new HashMap<>();
    
    String[] rows = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
    
    for (int r = 0; r < rows.length; r++)
    {
      String row = rows[r];
      for (int c = 0; c < row.length(); c++)
      {
        char ch = row.charAt(c);
        List<Character> neighbors = new ArrayList<>();
        
        // Same row neighbors
        if (c > 0) neighbors.add(row.charAt(c - 1));
        if (c < row.length() - 1) neighbors.add(row.charAt(c + 1));
        
        // Adjacent row neighbors
        if (r > 0)
        {
          String prevRow = rows[r - 1];
          for (int i = Math.max(0, c - 1); i <= Math.min(prevRow.length() - 1, c + 1); i++)
            neighbors.add(prevRow.charAt(i));
        }
        if (r < rows.length - 1)
        {
          String nextRow = rows[r + 1];
          for (int i = Math.max(0, c - 1); i <= Math.min(nextRow.length() - 1, c + 1); i++)
            neighbors.add(nextRow.charAt(i));
        }
        
        adjacent.put(ch, neighbors);
      }
    }
    
    return adjacent;
  }
  
  /**
   * Check if two keys are adjacent
   */
  private boolean isAdjacent(char c1, char c2)
  {
    List<Character> adjacent = _adjacentKeys.get(c1);
    return adjacent != null && adjacent.contains(c2);
  }
  
  /**
   * Build key sequence from touched keys
   */
  private String buildKeySequence(List<Character> touchedKeys)
  {
    StringBuilder sb = new StringBuilder();
    for (Character c : touchedKeys)
    {
      if (c != null && Character.isLetter(c))
      {
        sb.append(Character.toLowerCase(c));
      }
    }
    return sb.toString();
  }
  
  /**
   * Calculate distance between two points
   */
  private float distance(PointF p1, PointF p2)
  {
    float dx = p1.x - p2.x;
    float dy = p1.y - p2.y;
    return (float)Math.sqrt(dx * dx + dy * dy);
  }
  
  /**
   * Calculate total path length
   */
  private float calculatePathLength(List<PointF> path)
  {
    float length = 0;
    for (int i = 1; i < path.size(); i++)
    {
      length += distance(path.get(i - 1), path.get(i));
    }
    return length;
  }
  
  /**
   * Load basic dictionary as fallback
   */
  private void loadBasicDictionary()
  {
    String[] words = {
      "the", "and", "you", "that", "was", "for", "are", "with", "his", "they",
      "this", "have", "from", "word", "but", "what", "some", "can", "hello", "world"
    };
    
    for (int i = 0; i < words.length; i++)
    {
      insertWord(words[i], 10000 - i * 100);
    }
  }
  
  /**
   * Trie node for efficient dictionary storage
   */
  private static class TrieNode
  {
    Map<Character, TrieNode> children = new HashMap<>();
    boolean isWord = false;
    String word = null;
    int frequency = 0;
  }
  
  /**
   * Word candidate with score
   */
  private static class WordCandidate
  {
    final String word;
    final float score;
    
    WordCandidate(String word, float score)
    {
      this.word = word;
      this.score = score;
    }
  }
}