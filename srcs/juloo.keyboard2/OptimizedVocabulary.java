package juloo.keyboard2;

import android.content.Context;
import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;

/**
 * Optimized vocabulary filtering for neural swipe predictions
 * Ports web app swipe-vocabulary.js optimizations to Android
 * 
 * Features:
 * - Common words fast-path for instant lookup
 * - Hierarchical vocabulary (common -> top5000 -> full)
 * - Combined confidence + frequency scoring
 * - Length-based filtering and word lookup
 */
public class OptimizedVocabulary
{
  private static final String TAG = "OptimizedVocabulary";
  
  // Fast-path vocabulary sets for performance
  private Set<String> commonWords;           // ~100 most frequent words
  private Set<String> top5000;               // Top 5000 words for quick filtering  
  private Map<String, Float> wordFrequencies; // Full vocabulary with frequencies
  private Map<Integer, List<String>> wordsByLength; // Length-based lookup
  
  // Scoring parameters (from web app)
  private static final float CONFIDENCE_WEIGHT = 0.6f;
  private static final float FREQUENCY_WEIGHT = 0.4f;
  private static final float COMMON_WORDS_BOOST = 1.2f;
  private static final float TOP5000_BOOST = 1.0f;
  private static final float RARE_WORDS_PENALTY = 0.9f;
  
  // Filtering thresholds
  private Map<Integer, Float> minFrequencyByLength;
  
  private boolean isLoaded = false;
  private Context context;
  
  public OptimizedVocabulary(Context context)
  {
    this.context = context;
    this.commonWords = new HashSet<>();
    this.top5000 = new HashSet<>();
    this.wordFrequencies = new HashMap<>();
    this.wordsByLength = new HashMap<>();
    this.minFrequencyByLength = new HashMap<>();
  }
  
  /**
   * Load vocabulary from assets with frequency data
   * Creates hierarchical structure for fast filtering
   */
  public boolean loadVocabulary()
  {
    try
    {
      Log.d(TAG, "Loading optimized vocabulary from assets...");
      
      // Load full vocabulary with frequencies from dictionary
      loadWordFrequencies();
      
      // Create fast-path sets for performance
      createFastPathSets();
      
      // Initialize minimum frequency thresholds by word length
      initializeFrequencyThresholds();
      
      // Create length-based word lookup
      createLengthBasedLookup();
      
      isLoaded = true;
      Log.d(TAG, String.format("Vocabulary loaded: %d total words, %d common, %d top5000", 
        wordFrequencies.size(), commonWords.size(), top5000.size()));
      
      return true;
    }
    catch (Exception e)
    {
      Log.e(TAG, "Failed to load vocabulary - NO FALLBACK ALLOWED", e);
      throw new RuntimeException("Dictionary loading failed - fallback vocabulary deleted", e);
    }
  }
  
  /**
   * Filter and rank neural network predictions using vocabulary optimization
   * Implements fast-path lookup and combined scoring from web app
   */
  public List<FilteredPrediction> filterPredictions(List<CandidateWord> rawPredictions, SwipeStats swipeStats)
  {
    if (!isLoaded)
    {
      Log.w(TAG, "Vocabulary not loaded, returning raw predictions");
      return convertToFiltered(rawPredictions);
    }
    
    List<FilteredPrediction> validPredictions = new ArrayList<>();
    
    for (CandidateWord candidate : rawPredictions)
    {
      String word = candidate.word.toLowerCase().trim();
      
      // Skip invalid word formats
      if (!word.matches("^[a-z]+$"))
      {
        continue;
      }
      
      // OPTIMIZATION: Fast path for common words (biggest speedup)
      if (commonWords.contains(word))
      {
        Float freq = wordFrequencies.get(word);
        if (freq != null)
        {
          float score = calculateCombinedScore(candidate.confidence, freq, COMMON_WORDS_BOOST);
          validPredictions.add(new FilteredPrediction(word, score, candidate.confidence, freq, "common"));
          continue;
        }
      }
      
      // Check top 5000 words (second fast path)
      if (top5000.contains(word))
      {
        Float freq = wordFrequencies.get(word);
        if (freq != null)
        {
          float score = calculateCombinedScore(candidate.confidence, freq, TOP5000_BOOST);
          validPredictions.add(new FilteredPrediction(word, score, candidate.confidence, freq, "top5000"));
          continue;
        }
      }
      
      // Check full vocabulary with frequency threshold
      Float freq = wordFrequencies.get(word);
      if (freq != null)
      {
        float minFreq = getMinFrequency(word.length());
        if (freq >= minFreq)
        {
          float score = calculateCombinedScore(candidate.confidence, freq, RARE_WORDS_PENALTY);
          validPredictions.add(new FilteredPrediction(word, score, candidate.confidence, freq, "vocabulary"));
        }
      }
    }
    
    // Sort by combined score (confidence + frequency)
    validPredictions.sort((a, b) -> Float.compare(b.score, a.score));
    
    // Apply swipe-specific filtering if needed
    if (swipeStats != null && swipeStats.expectedLength > 0)
    {
      return filterByExpectedLength(validPredictions, swipeStats.expectedLength);
    }
    
    return validPredictions.subList(0, Math.min(validPredictions.size(), 10));
  }
  
  /**
   * Calculate combined score from NN confidence and word frequency
   * Implements web app scoring algorithm with logarithmic frequency scaling
   */
  private float calculateCombinedScore(float confidence, float frequency, float boost)
  {
    // Logarithmic frequency scaling to normalize to ~0-1 range
    float freqScore = (float)(Math.log10(frequency + 1e-10) / -10.0);
    freqScore = Math.max(0.0f, Math.min(1.0f, freqScore)); // Clamp to [0,1]
    
    // Weighted combination with boost factor
    return (CONFIDENCE_WEIGHT * confidence + FREQUENCY_WEIGHT * freqScore) * boost;
  }
  
  /**
   * Load word frequencies from dictionary files
   */
  private void loadWordFrequencies()
  {
    try
    {
      // Load English dictionary (most comprehensive)
      InputStream inputStream = context.getAssets().open("dictionaries/en.txt");
      BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
      
      String line;
      int wordCount = 0;
      while ((line = reader.readLine()) != null)
      {
        line = line.trim().toLowerCase();
        if (!line.isEmpty() && line.matches("^[a-z]+$"))
        {
          // Assign frequency based on position (higher = more frequent)
          float frequency = 1.0f / (wordCount + 1.0f);
          wordFrequencies.put(line, frequency);
          wordCount++;
          
          // Limit to prevent memory issues (150k words max)
          if (wordCount >= 150000)
          {
            break;
          }
        }
      }
      
      reader.close();
      Log.d(TAG, "Loaded " + wordCount + " words with frequencies");
    }
    catch (IOException e)
    {
      Log.e(TAG, "Failed to load word frequencies", e);
      throw new RuntimeException("Could not load vocabulary", e);
    }
  }
  
  /**
   * Create fast-path sets for common words and top 5000
   */
  private void createFastPathSets()
  {
    // Sort words by frequency (highest first)
    List<Map.Entry<String, Float>> sortedWords = new ArrayList<>(wordFrequencies.entrySet());
    sortedWords.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));
    
    // Common words: top 100 most frequent
    for (int i = 0; i < Math.min(100, sortedWords.size()); i++)
    {
      commonWords.add(sortedWords.get(i).getKey());
    }
    
    // Top 5000: for quick filtering
    for (int i = 0; i < Math.min(5000, sortedWords.size()); i++)
    {
      top5000.add(sortedWords.get(i).getKey());
    }
  }
  
  /**
   * Initialize minimum frequency thresholds by word length
   */
  private void initializeFrequencyThresholds()
  {
    // Longer words can have lower frequency thresholds
    minFrequencyByLength.put(1, 1e-4f);
    minFrequencyByLength.put(2, 1e-5f);
    minFrequencyByLength.put(3, 1e-6f);
    minFrequencyByLength.put(4, 1e-6f);
    minFrequencyByLength.put(5, 1e-7f);
    minFrequencyByLength.put(6, 1e-7f);
    minFrequencyByLength.put(7, 1e-8f);
    minFrequencyByLength.put(8, 1e-8f);
    // 9+ words
    for (int i = 9; i <= 20; i++)
    {
      minFrequencyByLength.put(i, 1e-9f);
    }
  }
  
  /**
   * Create length-based word lookup for similarity matching
   */
  private void createLengthBasedLookup()
  {
    for (String word : wordFrequencies.keySet())
    {
      int length = word.length();
      wordsByLength.computeIfAbsent(length, k -> new ArrayList<>()).add(word);
    }
    
    // Sort each length group by frequency (most frequent first)
    for (List<String> wordsOfLength : wordsByLength.values())
    {
      wordsOfLength.sort((a, b) -> Float.compare(
        wordFrequencies.getOrDefault(b, 0.0f), 
        wordFrequencies.getOrDefault(a, 0.0f)
      ));
    }
  }
  
  /**
   * Get minimum frequency threshold for word length
   */
  private float getMinFrequency(int length)
  {
    return minFrequencyByLength.getOrDefault(length, 1e-9f);
  }
  
  /**
   * Filter predictions by expected word length with tolerance
   */
  private List<FilteredPrediction> filterByExpectedLength(List<FilteredPrediction> predictions, int expectedLength)
  {
    int tolerance = 2; // Allow ±2 characters
    
    List<FilteredPrediction> filtered = new ArrayList<>();
    for (FilteredPrediction pred : predictions)
    {
      int lengthDiff = Math.abs(pred.word.length() - expectedLength);
      if (lengthDiff <= tolerance)
      {
        filtered.add(pred);
      }
    }
    
    return filtered.size() > 0 ? filtered : predictions.subList(0, Math.min(predictions.size(), 5));
  }
  
  
  /**
   * Convert raw predictions to filtered format
   */
  private List<FilteredPrediction> convertToFiltered(List<CandidateWord> rawPredictions)
  {
    List<FilteredPrediction> result = new ArrayList<>();
    for (CandidateWord candidate : rawPredictions)
    {
      result.add(new FilteredPrediction(candidate.word, candidate.confidence, 
        candidate.confidence, 0.0f, "raw"));
    }
    return result;
  }
  
  /**
   * Check if vocabulary is loaded
   */
  public boolean isLoaded()
  {
    return isLoaded;
  }
  
  /**
   * Get vocabulary statistics
   */
  public VocabularyStats getStats()
  {
    return new VocabularyStats(
      wordFrequencies.size(),
      commonWords.size(), 
      top5000.size(),
      isLoaded
    );
  }
  
  /**
   * Input candidate word
   */
  public static class CandidateWord
  {
    public final String word;
    public final float confidence;
    
    public CandidateWord(String word, float confidence)
    {
      this.word = word;
      this.confidence = confidence;
    }
  }
  
  /**
   * Filtered prediction with combined scoring
   */
  public static class FilteredPrediction
  {
    public final String word;
    public final float score;         // Combined confidence + frequency score
    public final float confidence;    // Original NN confidence
    public final float frequency;     // Word frequency
    public final String source;       // "common", "top5000", "vocabulary", "raw"
    
    public FilteredPrediction(String word, float score, float confidence, float frequency, String source)
    {
      this.word = word;
      this.score = score;
      this.confidence = confidence;
      this.frequency = frequency;
      this.source = source;
    }
  }
  
  /**
   * Swipe statistics for length-based filtering
   */
  public static class SwipeStats
  {
    public final int expectedLength;
    public final float pathLength;
    public final float speed;
    
    public SwipeStats(int expectedLength, float pathLength, float speed)
    {
      this.expectedLength = expectedLength;
      this.pathLength = pathLength;
      this.speed = speed;
    }
  }
  
  /**
   * Vocabulary statistics
   */
  public static class VocabularyStats
  {
    public final int totalWords;
    public final int commonWords;
    public final int top5000;
    public final boolean isLoaded;
    
    public VocabularyStats(int totalWords, int commonWords, int top5000, boolean isLoaded)
    {
      this.totalWords = totalWords;
      this.commonWords = commonWords;
      this.top5000 = top5000;
      this.isLoaded = isLoaded;
    }
  }
}