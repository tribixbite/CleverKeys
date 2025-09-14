package juloo.keyboard2;

import android.content.Context;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Word prediction engine that matches swipe patterns to dictionary words
 */
public class WordPredictor
{
  private final Map<String, Integer> _dictionary;
  private final Map<Character, List<Character>> _adjacentKeys;
  private BigramModel _bigramModel;
  private LanguageDetector _languageDetector;
  private String _currentLanguage;
  private List<String> _recentWords; // For language detection
  private static final int MAX_PREDICTIONS_TYPING = 5;
  private static final int MAX_PREDICTIONS_SWIPE = 10;
  private static final int MAX_EDIT_DISTANCE = 2;
  private static final int MAX_RECENT_WORDS = 20; // Keep last 20 words for language detection
  private Config _config;
  private UserAdaptationManager _adaptationManager;
  
  public WordPredictor()
  {
    _dictionary = new HashMap<>();
    _adjacentKeys = buildAdjacentKeysMap();
    _bigramModel = new BigramModel();
    _languageDetector = new LanguageDetector();
    _currentLanguage = "en"; // Default to English
    _recentWords = new ArrayList<>();
    _config = null;
  }
  
  /**
   * Set the config for weight access
   */
  public void setConfig(Config config)
  {
    _config = config;
  }
  
  /**
   * Set the user adaptation manager for frequency adjustment
   */
  public void setUserAdaptationManager(UserAdaptationManager adaptationManager)
  {
    _adaptationManager = adaptationManager;
  }
  
  /**
   * Set the active language for N-gram predictions
   */
  public void setLanguage(String language)
  {
    _currentLanguage = language;
    if (_bigramModel != null)
    {
      _bigramModel.setLanguage(language);
      android.util.Log.d("WordPredictor", "N-gram language set to: " + language);
    }
  }
  
  /**
   * Get the current active language
   */
  public String getCurrentLanguage()
  {
    return _bigramModel != null ? _bigramModel.getCurrentLanguage() : "en";
  }
  
  /**
   * Check if a language is supported by the N-gram model
   */
  public boolean isLanguageSupported(String language)
  {
    return _bigramModel != null ? _bigramModel.isLanguageSupported(language) : false;
  }
  
  /**
   * Add a word to the recent words list for language detection
   */
  public void addWordToContext(String word)
  {
    if (word == null || word.trim().isEmpty())
      return;
    
    word = word.toLowerCase().trim();
    _recentWords.add(word);
    
    // Keep only the most recent words
    while (_recentWords.size() > MAX_RECENT_WORDS)
    {
      _recentWords.remove(0);
    }
    
    // Try to detect language change if we have enough words
    if (_recentWords.size() >= 5)
    {
      tryAutoLanguageDetection();
    }
  }
  
  /**
   * Try to automatically detect and switch language based on recent words
   */
  private void tryAutoLanguageDetection()
  {
    if (_languageDetector == null)
      return;
    
    String detectedLanguage = _languageDetector.detectLanguageFromWords(_recentWords);
    if (detectedLanguage != null && !detectedLanguage.equals(_currentLanguage))
    {
      // Only switch if the detected language is supported by our N-gram model
      if (_bigramModel.isLanguageSupported(detectedLanguage))
      {
        android.util.Log.d("WordPredictor", "Auto-detected language change from " + _currentLanguage + " to " + detectedLanguage);
        setLanguage(detectedLanguage);
      }
    }
  }
  
  /**
   * Manually detect language from a text sample
   */
  public String detectLanguage(String text)
  {
    return _languageDetector != null ? _languageDetector.detectLanguage(text) : null;
  }
  
  /**
   * Get the list of recent words used for language detection
   */
  public List<String> getRecentWords()
  {
    return new ArrayList<>(_recentWords);
  }
  
  /**
   * Clear the recent words context
   */
  public void clearContext()
  {
    _recentWords.clear();
  }
  
  /**
   * Load dictionary from assets
   */
  public void loadDictionary(Context context, String language)
  {
    _dictionary.clear();
    // Try enhanced dictionary first, fall back to basic
    String filename = "dictionaries/" + language + "_enhanced.txt";
    String fallbackFilename = "dictionaries/" + language + ".txt";
    
    boolean dictionaryLoaded = false;
    
    // Only use enhanced dictionary, no fallback to basic
    try
    {
      BufferedReader reader = new BufferedReader(
        new InputStreamReader(context.getAssets().open(filename)));
      String line;
      while ((line = reader.readLine()) != null)
      {
        String[] parts = line.trim().split("\t");
        if (parts.length >= 1)
        {
          String word = parts[0].toLowerCase();
          int frequency = parts.length > 1 ? Integer.parseInt(parts[1]) : 1000;
          _dictionary.put(word, frequency);
        }
      }
      reader.close();
      dictionaryLoaded = true;
      android.util.Log.d("WordPredictor", "Loaded enhanced dictionary: " + filename + " with " + _dictionary.size() + " words");
    }
    catch (IOException e)
    {
      android.util.Log.e("WordPredictor", "Failed to load enhanced dictionary: " + filename + ", error: " + e.getMessage());
      // Don't fall back to basic dictionary - keep dictionary empty if enhanced not found
    }
    
    // Set the N-gram model language to match the dictionary
    setLanguage(language);
  }
  
  /**
   * Reset the predictor state - called after space/punctuation
   */
  public void reset()
  {
    // This method will be called from Keyboard2 to reset state
    // Dictionary remains loaded, just clears any internal state if needed
    android.util.Log.d("WordPredictor", "===== PREDICTOR RESET CALLED =====");
    android.util.Log.d("WordPredictor", "Stack trace: ", new Exception("Reset trace"));
  }
  
  /**
   * Build a map of adjacent keys on QWERTY keyboard
   */
  private Map<Character, List<Character>> buildAdjacentKeysMap()
  {
    Map<Character, List<Character>> adjacent = new HashMap<>();
    
    // QWERTY layout adjacency - simplified
    String[] rows = {
      "qwertyuiop",
      "asdfghjkl",
      "zxcvbnm"
    };
    
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
          int offset = (r == 2) ? -1 : 0; // Adjust for keyboard stagger
          for (int i = Math.max(0, c + offset); i < Math.min(prevRow.length(), c + offset + 2); i++)
            neighbors.add(prevRow.charAt(i));
        }
        if (r < rows.length - 1)
        {
          String nextRow = rows[r + 1];
          int offset = (r == 1) ? 1 : 0; // Adjust for keyboard stagger
          for (int i = Math.max(0, c - offset); i < Math.min(nextRow.length(), c - offset + 2); i++)
            neighbors.add(nextRow.charAt(i));
        }
        
        adjacent.put(ch, neighbors);
      }
    }
    
    return adjacent;
  }
  
  /**
   * Predict words based on the sequence of touched keys
   * Returns list of predictions (for backward compatibility)
   */
  public List<String> predictWords(String keySequence)
  {
    PredictionResult result = predictWordsWithScores(keySequence);
    return result.words;
  }
  
  /**
   * Predict words with context
   */
  public PredictionResult predictWordsWithContext(String keySequence, List<String> context)
  {
    if (keySequence == null || keySequence.isEmpty())
      return new PredictionResult(new ArrayList<>(), new ArrayList<>());
    
    PerformanceProfiler.start("Type.predictWithContext");
    
    // Get base predictions
    PredictionResult baseResult = predictWordsWithScoresInternal(keySequence);
    
    // Apply contextual reranking if context is available
    if (context != null && !context.isEmpty() && _bigramModel != null)
    {
      List<WordCandidate> candidates = new ArrayList<>();
      for (int i = 0; i < baseResult.words.size(); i++)
      {
        String word = baseResult.words.get(i);
        int baseScore = baseResult.scores.get(i);
        
        // Get context multiplier from bigram model
        float contextMultiplier = _bigramModel.getContextMultiplier(word, context);
        
        // Apply context boost/penalty
        int adjustedScore = (int)(baseScore * contextMultiplier);
        candidates.add(new WordCandidate(word, adjustedScore));
      }
      
      // Re-sort by adjusted scores
      Collections.sort(candidates, new Comparator<WordCandidate>()
      {
        @Override
        public int compare(WordCandidate a, WordCandidate b)
        {
          return Integer.compare(b.score, a.score); // Descending order
        }
      });
      
      // Extract top predictions
      List<String> contextualWords = new ArrayList<>();
      List<Integer> contextualScores = new ArrayList<>();
      int limit = Math.min(candidates.size(), MAX_PREDICTIONS_TYPING);
      
      for (int i = 0; i < limit; i++)
      {
        contextualWords.add(candidates.get(i).word);
        contextualScores.add(candidates.get(i).score);
      }
      
      PerformanceProfiler.end("Type.predictWithContext");
      return new PredictionResult(contextualWords, contextualScores);
    }
    
    PerformanceProfiler.end("Type.predictWithContext");
    return baseResult;
  }
  
  /**
   * Predict words and return with their scores (no context)
   */
  public PredictionResult predictWordsWithScores(String keySequence)
  {
    return predictWordsWithContext(keySequence, null);
  }
  
  /**
   * Internal prediction logic
   */
  private PredictionResult predictWordsWithScoresInternal(String keySequence)
  {
    if (keySequence == null || keySequence.isEmpty())
      return new PredictionResult(new ArrayList<>(), new ArrayList<>());
    
    PerformanceProfiler.start("Type.predictWordsWithScores");
      
    // TWO-PASS PRIORITIZED SYSTEM
    List<WordCandidate> priorityMatches = new ArrayList<>();  // First+last matches
    List<WordCandidate> otherMatches = new ArrayList<>();     // Other candidates
    String lowerSequence = keySequence.toLowerCase();
    
    android.util.Log.d("WordPredictor", "Predicting for: " + lowerSequence + " (len=" + lowerSequence.length() + ")");
    
    // Check if this is likely a swipe sequence (more chars than expected for word length)
    // Swipes often have 2-3x more characters than the target word
    boolean isSwipeSequence = lowerSequence.length() > 6;
    
    // Determine max predictions based on input type
    int maxPredictions = isSwipeSequence ? MAX_PREDICTIONS_SWIPE : MAX_PREDICTIONS_TYPING;
    
    // Find all words that could match the key sequence
    for (Map.Entry<String, Integer> entry : _dictionary.entrySet())
    {
      String word = entry.getKey();
      int frequency = entry.getValue();
      
      // For swipe sequences, use special matching
      if (isSwipeSequence)
      {
        // For swipe sequences, always check first/last character matches
        if (word.length() > 0 && lowerSequence.length() > 0)
        {
          char firstChar = word.charAt(0);
          char lastChar = word.charAt(word.length() - 1);
          char seqFirst = lowerSequence.charAt(0);
          char seqLast = lowerSequence.charAt(lowerSequence.length() - 1);
          
          // PRIORITY MATCHES: First AND last character match
          if (firstChar == seqFirst && lastChar == seqLast)
          {
            // Count inner character matches
            int innerMatches = countInnerMatches(word, lowerSequence);
            // NO FREQUENCY MULTIPLICATION for priority matches
            // Score based purely on match quality
            int baseScore = 10000 + (innerMatches * 100);
            if (_config != null)
            {
              // Apply first letter weight
              baseScore = (int)(baseScore * _config.swipe_first_letter_weight);
              // Apply last letter weight
              baseScore = (int)(baseScore * _config.swipe_last_letter_weight);
              // Apply endpoint bonus weight (both match)
              baseScore = (int)(baseScore * _config.swipe_endpoint_bonus_weight);
            }
            
            // Apply user adaptation multiplier
            if (_adaptationManager != null)
            {
              float adaptationMultiplier = _adaptationManager.getAdaptationMultiplier(word);
              baseScore = (int)(baseScore * adaptationMultiplier);
            }
            
            priorityMatches.add(new WordCandidate(word, baseScore));
            android.util.Log.d("WordPredictor", "PRIORITY match: " + word + " (inner=" + innerMatches + ", score=" + baseScore + ")");
          }
          // SECONDARY: Partial endpoint match (first OR last)
          else if (firstChar == seqFirst || lastChar == seqLast)
          {
            // Skip if strict endpoint mode is enabled
            if (_config != null && _config.swipe_require_endpoints)
            {
              continue; // Skip words that don't match BOTH endpoints in strict mode
            }
            
            int innerMatches = countInnerMatches(word, lowerSequence);
            if (innerMatches >= 1) // At least one inner match required
            {
              // Lower tier scoring with frequency consideration
              int baseScore = 1000 + (innerMatches * 50);
              if (_config != null)
              {
                // Apply appropriate weight based on which endpoint matched
                if (firstChar == seqFirst)
                  baseScore = (int)(baseScore * _config.swipe_first_letter_weight);
                if (lastChar == seqLast)
                  baseScore = (int)(baseScore * _config.swipe_last_letter_weight);
              }
              
              // Apply user adaptation multiplier before frequency multiplication
              if (_adaptationManager != null)
              {
                float adaptationMultiplier = _adaptationManager.getAdaptationMultiplier(word);
                baseScore = (int)(baseScore * adaptationMultiplier);
              }
              
              otherMatches.add(new WordCandidate(word, baseScore * frequency));
              android.util.Log.d("WordPredictor", "Partial match: " + word + " (inner=" + innerMatches + ", score=" + baseScore + ")");
            }
          }
          // OTHER: Standard swipe candidates
          else if (!(_config != null && _config.swipe_require_endpoints) && couldBeFormedFrom(word, lowerSequence))
          {
            // Skip in strict mode since endpoints don't match
            int score = calculateSwipeScore(word, lowerSequence, frequency);
            
            // Apply user adaptation multiplier
            if (score > 0 && _adaptationManager != null)
            {
              float adaptationMultiplier = _adaptationManager.getAdaptationMultiplier(word);
              score = (int)(score * adaptationMultiplier);
            }
            
            if (score > 0)
            {
              otherMatches.add(new WordCandidate(word, score));
              android.util.Log.d("WordPredictor", "Other match: " + word + " (score=" + score + ")");
            }
          }
        }
      }
      else
      {
        // Regular typing - STRICT PREFIX MATCHING for Markov chain behavior
        // Only suggest words that start with the typed sequence
        if (!word.startsWith(lowerSequence))
          continue;  // Skip words that don't start with typed prefix
          
        // Calculate score based on exact prefix match
        int score = calculatePrefixScore(word, lowerSequence);
        
        // Apply user adaptation multiplier before frequency multiplication
        if (_adaptationManager != null)
        {
          float adaptationMultiplier = _adaptationManager.getAdaptationMultiplier(word);
          score = (int)(score * adaptationMultiplier);
        }
        
        // For regular typing, keep frequency multiplication
        otherMatches.add(new WordCandidate(word, score * frequency));
        android.util.Log.d("WordPredictor", "PREFIX match: " + word + " (score=" + score + ", freq=" + frequency + ")");
      }
    }
    
    // Sort each list independently
    Collections.sort(priorityMatches, new Comparator<WordCandidate>() {
      @Override
      public int compare(WordCandidate a, WordCandidate b) {
        return Integer.compare(b.score, a.score);
      }
    });
    
    Collections.sort(otherMatches, new Comparator<WordCandidate>() {
      @Override
      public int compare(WordCandidate a, WordCandidate b) {
        return Integer.compare(b.score, a.score);
      }
    });
    
    // Combine lists with PRIORITY matches FIRST
    List<String> predictions = new ArrayList<>();
    List<Integer> scores = new ArrayList<>();
    
    // Add ALL priority matches (up to max)
    for (WordCandidate candidate : priorityMatches)
    {
      predictions.add(candidate.word);
      scores.add(candidate.score);
      if (predictions.size() >= maxPredictions) break;
    }
    
    // Fill remaining slots with other matches
    for (WordCandidate candidate : otherMatches)
    {
      if (predictions.size() >= maxPredictions) break;
      if (!predictions.contains(candidate.word)) // Avoid duplicates
      {
        predictions.add(candidate.word);
        scores.add(candidate.score);
      }
    }
    
    android.util.Log.d("WordPredictor", "Final predictions (" + predictions.size() + "): " + predictions);
    android.util.Log.d("WordPredictor", "Scores: " + scores);
    
    PerformanceProfiler.end("Type.predictWordsWithScores");
    return new PredictionResult(predictions, scores);
  }
  
  /**
   * Calculate score for prefix-based matching (regular typing)
   */
  private int calculatePrefixScore(String word, String keySequence)
  {
    // Direct match is highest score
    if (word.equals(keySequence))
      return 1000;
      
    // Word starts with sequence (this is guaranteed by caller, but score based on completion ratio)
    if (word.startsWith(keySequence))
    {
      // Score based on how much of the word is already typed
      float completionRatio = (float)keySequence.length() / word.length();
      
      // Higher score for more completion, but prefer shorter completions
      int baseScore = 800;
      
      // Bonus for more typed characters (longer prefix = more specific)
      int prefixBonus = keySequence.length() * 50;
      
      // Slight penalty for very long words to prefer common shorter words
      int lengthPenalty = Math.max(0, (word.length() - 6) * 10);
      
      return baseScore + prefixBonus - lengthPenalty;
    }
    
    return 0; // Should not reach here due to prefix check in caller
  }
  
  /**
   * Calculate how well a word matches the key sequence (legacy - used for swipe sequences)
   */
  private int calculateMatchScore(String word, String keySequence)
  {
    // Direct match is highest score
    if (word.equals(keySequence))
      return 1000;
      
    // Check if word starts with the key sequence (prefix match)
    if (word.startsWith(keySequence))
      return 800;
      
    // Check if key sequence starts with word (over-swipe)
    if (keySequence.startsWith(word))
      return 700;
      
    // Calculate edit distance with adjacency consideration
    int distance = calculateEditDistance(word, keySequence);
    if (distance <= MAX_EDIT_DISTANCE)
    {
      return 500 - (distance * 100);
    }
    
    // Fuzzy match based on common characters
    int commonChars = countCommonCharacters(word, keySequence);
    if (commonChars >= Math.min(word.length(), keySequence.length()) - 1)
    {
      return 200 + commonChars * 10;
    }
    
    return 0;
  }
  
  /**
   * Calculate edit distance between two strings
   */
  private int calculateEditDistance(String s1, String s2)
  {
    int[][] dp = new int[s1.length() + 1][s2.length() + 1];
    
    for (int i = 0; i <= s1.length(); i++)
      dp[i][0] = i;
    for (int j = 0; j <= s2.length(); j++)
      dp[0][j] = j;
      
    for (int i = 1; i <= s1.length(); i++)
    {
      for (int j = 1; j <= s2.length(); j++)
      {
        char c1 = s1.charAt(i - 1);
        char c2 = s2.charAt(j - 1);
        
        if (c1 == c2)
        {
          dp[i][j] = dp[i - 1][j - 1];
        }
        else
        {
          // Check if keys are adjacent (common swipe error)
          boolean adjacent = isAdjacent(c1, c2);
          int substitutionCost = adjacent ? 1 : 2;
          
          dp[i][j] = Math.min(
            dp[i - 1][j] + 1,  // deletion
            Math.min(
              dp[i][j - 1] + 1,  // insertion
              dp[i - 1][j - 1] + substitutionCost  // substitution
            )
          );
        }
      }
    }
    
    return dp[s1.length()][s2.length()];
  }
  
  /**
   * Check if two keys are adjacent on the keyboard
   */
  private boolean isAdjacent(char c1, char c2)
  {
    List<Character> adjacent = _adjacentKeys.get(c1);
    return adjacent != null && adjacent.contains(c2);
  }
  
  /**
   * Count common characters between two strings
   */
  private int countCommonCharacters(String s1, String s2)
  {
    int count = 0;
    int j = 0;
    for (int i = 0; i < s1.length() && j < s2.length(); i++)
    {
      if (s1.charAt(i) == s2.charAt(j))
      {
        count++;
        j++;
      }
      else if (j + 1 < s2.length() && s1.charAt(i) == s2.charAt(j + 1))
      {
        count++;
        j += 2;
      }
    }
    return count;
  }
  
  /**
   * Check if a word could be formed from a swipe sequence
   * All letters of the word should appear in order in the sequence
   */
  private boolean couldBeFormedFrom(String word, String sequence)
  {
    int seqIndex = 0;
    for (char c : word.toCharArray())
    {
      // Find this character in the sequence starting from current position
      int found = sequence.indexOf(c, seqIndex);
      if (found == -1)
        return false;
      seqIndex = found + 1;
    }
    return true;
  }
  
  /**
   * Calculate score for swipe sequence matching
   */
  private int calculateSwipeScore(String word, String sequence, int frequency)
  {
    // Find positions of word characters in sequence
    int seqIndex = 0;
    int totalGaps = 0;
    int matchedChars = 0;
    
    for (char c : word.toCharArray())
    {
      int found = sequence.indexOf(c, seqIndex);
      if (found != -1)
      {
        totalGaps += (found - seqIndex);
        seqIndex = found + 1;
        matchedChars++;
      }
    }
    
    // Score based on:
    // 1. How many characters matched
    // 2. How much of the sequence was used (less gaps = better)
    // 3. Word frequency
    float matchRatio = (float)matchedChars / word.length();
    float gapPenalty = 1.0f / (1.0f + totalGaps / 10.0f);
    
    return (int)(matchRatio * gapPenalty * 1000);
  }
  
  /**
   * Get dictionary size
   */
  public int getDictionarySize()
  {
    return _dictionary.size();
  }
  
  /**
   * Count matching characters in the middle of the word (excluding first/last)
   */
  private int countInnerMatches(String word, String sequence)
  {
    if (word.length() <= 2 || sequence.length() <= 2)
      return 0;
      
    int matches = 0;
    int seqIndex = 1; // Start after first character
    
    // Check inner characters (skip first and last)
    for (int i = 1; i < word.length() - 1; i++)
    {
      char c = word.charAt(i);
      // Look for this character in the remaining sequence
      for (int j = seqIndex; j < sequence.length() - 1; j++)
      {
        if (sequence.charAt(j) == c)
        {
          matches++;
          seqIndex = j + 1;
          break;
        }
      }
    }
    
    return matches;
  }
  
  /**
   * Helper class to store word candidates with scores
   */
  private static class WordCandidate
  {
    final String word;
    final int score;
    
    WordCandidate(String word, int score)
    {
      this.word = word;
      this.score = score;
    }
  }
  
  /**
   * Result class containing predictions and their scores
   */
  public static class PredictionResult
  {
    public final List<String> words;
    public final List<Integer> scores;
    
    public PredictionResult(List<String> words, List<Integer> scores)
    {
      this.words = words;
      this.scores = scores;
    }
  }
}