package juloo.keyboard2;

import android.content.Context;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates gesture templates for words based on ACTUAL keyboard layout
 * Uses real keyboard dimensions and layout calculations for accurate templates
 */
public class WordGestureTemplateGenerator
{
  // Dynamic QWERTY coordinates based on actual keyboard rendering
  private Map<Character, ContinuousGestureRecognizer.Point> keyboardCoords;
  
  private final List<String> dictionary;
  private final Map<String, Integer> wordFrequencies;
  
  // PERFORMANCE: Template cache to avoid regenerating on every swipe
  private Map<String, ContinuousGestureRecognizer.Template> templateCache;
  private String cachedDimensions; // Track when cache is valid
  
  public WordGestureTemplateGenerator()
  {
    dictionary = new ArrayList<>();
    wordFrequencies = new HashMap<>();
    keyboardCoords = new HashMap<>();
    templateCache = new HashMap<>();
    cachedDimensions = "";
    
    // Initialize with default coordinates to prevent crashes
    setKeyboardDimensions(1080f, 400f); // Default fallback dimensions
  }
  
  /**
   * Set keyboard dimensions for dynamic template generation
   * Uses ACTUAL keyboard layout calculations (not fixed 1000x1000)
   */
  public void setKeyboardDimensions(float keyboardWidth, float keyboardHeight)
  {
    if (keyboardWidth <= 0 || keyboardHeight <= 0)
    {
      android.util.Log.w("WordGestureTemplateGenerator", 
        "Invalid keyboard dimensions: " + keyboardWidth + "x" + keyboardHeight + ", using defaults");
      keyboardWidth = 1080f;
      keyboardHeight = 400f;
    }
    
    keyboardCoords.clear();
    
    // Use EXACT same layout calculation as SwipeCalibrationActivity.KeyboardView
    float keyWidth = keyboardWidth / 10f;
    float rowHeight = keyboardHeight / 4f; // 4 rows 
    float verticalMargin = 0.1f * rowHeight;   // Match keyboard rendering
    float horizontalMargin = 0.05f * keyWidth; // Match keyboard rendering
    
    // QWERTY layout using IDENTICAL calculations as keyboard rendering
    String[][] KEYBOARD_LAYOUT = {
      {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
      {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
      {"z", "x", "c", "v", "b", "n", "m"}
    };
    
    for (int row = 0; row < 3; row++) // Only letter rows
    {
      String[] rowKeys = KEYBOARD_LAYOUT[row];
      
      if (row == 0) // Top row (q-p)
      {
        for (int col = 0; col < rowKeys.length; col++)
        {
          String key = rowKeys[col];
          float x = col * keyWidth + horizontalMargin / 2;
          float y = row * rowHeight + verticalMargin / 2;
          
          // Use CENTER of key for template coordinate
          float centerX = x + (keyWidth - horizontalMargin) / 2;
          float centerY = y + (rowHeight - verticalMargin) / 2;
          
          keyboardCoords.put(key.charAt(0), new ContinuousGestureRecognizer.Point(centerX, centerY));
        }
      }
      else if (row == 1) // Middle row (a-l) - with half-key offset
      {
        float rowOffset = keyWidth * 0.5f;
        for (int col = 0; col < rowKeys.length; col++)
        {
          String key = rowKeys[col];
          float x = rowOffset + col * keyWidth + horizontalMargin / 2;
          float y = row * rowHeight + verticalMargin / 2;
          
          float centerX = x + (keyWidth - horizontalMargin) / 2;
          float centerY = y + (rowHeight - verticalMargin) / 2;
          
          keyboardCoords.put(key.charAt(0), new ContinuousGestureRecognizer.Point(centerX, centerY));
        }
      }
      else if (row == 2) // Bottom row (z-m)
      {
        // Calculate starting position to center 7 keys
        float totalKeysWidth = 7 * keyWidth;
        float startX = (keyboardWidth - totalKeysWidth) / 2;
        
        for (int col = 0; col < rowKeys.length; col++)
        {
          String key = rowKeys[col];
          float x = startX + col * keyWidth + horizontalMargin / 2;
          float y = row * rowHeight + verticalMargin / 2;
          
          float centerX = x + (keyWidth - horizontalMargin) / 2;
          float centerY = y + (rowHeight - verticalMargin) / 2;
          
          keyboardCoords.put(key.charAt(0), new ContinuousGestureRecognizer.Point(centerX, centerY));
        }
      }
    }
    
    android.util.Log.d("WordGestureTemplateGenerator", 
      String.format("Generated dynamic keyboard coordinates for %.0fx%.0f keyboard", keyboardWidth, keyboardHeight));
  }
  
  /**
   * Load dictionary from en.txt file
   */
  public void loadDictionary(Context context)
  {
    dictionary.clear();
    wordFrequencies.clear();
    
    try
    {
      BufferedReader reader = new BufferedReader(
        new InputStreamReader(context.getAssets().open("dictionaries/en.txt")));
      String line;
      int wordCount = 0;
      
      while ((line = reader.readLine()) != null && wordCount < 10000) // Expanded to ~10,000 words for better recognition coverage
      {
        // Skip comments and empty lines
        if (line.startsWith("#") || line.trim().isEmpty()) continue;
        
        String word = line.trim().toLowerCase();
        
        // Only include words with letters (3-12 characters for comprehensive gesture typing)
        if (word.matches("[a-z]+") && word.length() >= 3 && word.length() <= 12)
        {
          dictionary.add(word);
          wordFrequencies.put(word, 1000); // Default frequency since no frequencies in new format
          wordCount++;
        }
      }
      reader.close();
      
      android.util.Log.d("WordGestureTemplateGenerator", 
        "Loaded " + dictionary.size() + " words for gesture templates");
    }
    catch (IOException e)
    {
      android.util.Log.e("WordGestureTemplateGenerator", "Failed to load dictionary: " + e.getMessage());
    }
  }
  
  /**
   * Generate gesture template for a single word
   */
  public ContinuousGestureRecognizer.Template generateWordTemplate(String word)
  {
    if (keyboardCoords.isEmpty())
    {
      android.util.Log.w("WordGestureTemplateGenerator", 
        "Keyboard dimensions not set - call setKeyboardDimensions() first");
      return null;
    }
    
    word = word.toLowerCase();
    
    // PERFORMANCE: Check cache first
    if (templateCache.containsKey(word)) {
      return templateCache.get(word);
    }
    
    List<ContinuousGestureRecognizer.Point> points = new ArrayList<>();
    
    for (char c : word.toCharArray())
    {
      ContinuousGestureRecognizer.Point coord = keyboardCoords.get(c);
      if (coord != null)
      {
        points.add(new ContinuousGestureRecognizer.Point(coord.x, coord.y));
      }
      else
      {
        android.util.Log.w("WordGestureTemplateGenerator", 
          "No coordinate found for character: " + c);
        return null; // Skip words with unknown characters
      }
    }
    
    if (points.size() < 2)
    {
      return null; // Need at least 2 points for a gesture
    }
    
    ContinuousGestureRecognizer.Template template = new ContinuousGestureRecognizer.Template(word, points);
    
    // CACHE: Store for future use
    templateCache.put(word, template);
    
    return template;
  }
  
  /**
   * Generate templates for all dictionary words
   */
  public List<ContinuousGestureRecognizer.Template> generateAllWordTemplates()
  {
    List<ContinuousGestureRecognizer.Template> templates = new ArrayList<>();
    int successCount = 0;
    
    for (String word : dictionary)
    {
      ContinuousGestureRecognizer.Template template = generateWordTemplate(word);
      if (template != null)
      {
        templates.add(template);
        successCount++;
      }
    }
    
    android.util.Log.d("WordGestureTemplateGenerator", 
      "Generated " + successCount + " word templates from " + dictionary.size() + " dictionary words");
    
    return templates;
  }
  
  /**
   * Generate templates for most frequent words only
   */
  public List<ContinuousGestureRecognizer.Template> generateFrequentWordTemplates(int maxWords)
  {
    List<ContinuousGestureRecognizer.Template> templates = new ArrayList<>();
    int count = 0;
    
    for (String word : dictionary)
    {
      if (count >= maxWords) break;
      
      ContinuousGestureRecognizer.Template template = generateWordTemplate(word);
      if (template != null)
      {
        templates.add(template);
        count++;
      }
    }
    
    android.util.Log.d("WordGestureTemplateGenerator", 
      "Generated " + count + " frequent word templates");
    
    return templates;
  }
  
  /**
   * Get word frequency for a given word
   */
  public int getWordFrequency(String word)
  {
    return wordFrequencies.getOrDefault(word.toLowerCase(), 0);
  }
  
  /**
   * Check if word is in dictionary
   */
  public boolean isWordInDictionary(String word)
  {
    return wordFrequencies.containsKey(word.toLowerCase());
  }
  
  /**
   * Set real key positions for 100% accurate coordinate mapping (CRITICAL FIX)
   * Replaces simplified grid calculations with actual keyboard layout positions
   */
  public void setRealKeyPositions(java.util.Map<Character, android.graphics.PointF> realPositions)
  {
    if (realPositions == null || realPositions.isEmpty()) {
      android.util.Log.w("WordGestureTemplateGenerator", "No real key positions provided - using grid calculations");
      return;
    }
    
    // Replace calculated coordinates with real positions
    keyboardCoords.clear();
    for (java.util.Map.Entry<Character, android.graphics.PointF> entry : realPositions.entrySet()) {
      char keyChar = entry.getKey();
      android.graphics.PointF realPos = entry.getValue();
      keyboardCoords.put(keyChar, new ContinuousGestureRecognizer.Point(realPos.x, realPos.y));
      android.util.Log.d("WordGestureTemplateGenerator", "Real coord: '" + keyChar + "' = (" + realPos.x + "," + realPos.y + ")");
    }
    
    android.util.Log.i("WordGestureTemplateGenerator", "✅ Using " + keyboardCoords.size() + " REAL key positions instead of grid calculations");
  }
  
  /**
   * Get dictionary size
   */
  public int getDictionarySize()
  {
    return dictionary.size();
  }
  
  /**
   * Get direct access to dictionary words (for efficient candidate generation)
   */
  public List<String> getDictionary()
  {
    return new ArrayList<>(dictionary);
  }
  
  /**
   * Get coordinate for a character (requires keyboard dimensions to be set)
   */
  public ContinuousGestureRecognizer.Point getCharacterCoordinate(char c)
  {
    ContinuousGestureRecognizer.Point coord = keyboardCoords.get(Character.toLowerCase(c));
    return coord != null ? new ContinuousGestureRecognizer.Point(coord.x, coord.y) : null;
  }
  
  /**
   * Calculate gesture path length for a word (for complexity estimation)
   */
  public double calculateGesturePathLength(String word)
  {
    word = word.toLowerCase();
    double totalLength = 0.0;
    ContinuousGestureRecognizer.Point prevPoint = null;
    
    for (char c : word.toCharArray())
    {
      ContinuousGestureRecognizer.Point point = keyboardCoords.get(c);
      if (point != null)
      {
        if (prevPoint != null)
        {
          double dx = point.x - prevPoint.x;
          double dy = point.y - prevPoint.y;
          totalLength += Math.sqrt(dx * dx + dy * dy);
        }
        prevPoint = point;
      }
    }
    
    return totalLength;
  }
  
  /**
   * Get words by length range
   */
  public List<String> getWordsByLength(int minLength, int maxLength)
  {
    List<String> filtered = new ArrayList<>();
    
    for (String word : dictionary)
    {
      if (word.length() >= minLength && word.length() <= maxLength)
      {
        filtered.add(word);
      }
    }
    
    return filtered;
  }
  
  /**
   * Generate templates with complexity filtering
   * Excludes words that would create overly complex or simple gestures
   */
  public List<ContinuousGestureRecognizer.Template> generateBalancedWordTemplates(int maxWords)
  {
    List<ContinuousGestureRecognizer.Template> templates = new ArrayList<>();
    int count = 0;
    
    for (String word : dictionary)
    {
      if (count >= maxWords) break;
      
      // Filter by gesture complexity - MUCH MORE SELECTIVE FOR LENGTH MATCHING
      double pathLength = calculateGesturePathLength(word);
      if (pathLength > 200 && pathLength < 2500) // Stricter complexity filtering for better length matching
      {
        ContinuousGestureRecognizer.Template template = generateWordTemplate(word);
        if (template != null)
        {
          templates.add(template);
          count++;
        }
      }
    }
    
    android.util.Log.d("WordGestureTemplateGenerator", 
      "Generated " + count + " balanced word templates");
    
    return templates;
  }
}