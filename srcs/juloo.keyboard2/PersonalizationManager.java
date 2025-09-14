package juloo.keyboard2;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages personalization and learning from user typing patterns
 * Features:
 * - Track word usage frequency
 * - Learn new words automatically
 * - Adapt predictions based on user behavior
 * - Context-aware word suggestions
 */
public class PersonalizationManager
{
  private static final String PREFS_NAME = "swipe_personalization";
  private static final String WORD_FREQ_PREFIX = "freq_";
  private static final String BIGRAM_PREFIX = "bigram_";
  private static final String LAST_WORD_KEY = "last_word";
  
  private final Context _context;
  private final SharedPreferences _prefs;
  private final ConcurrentHashMap<String, Integer> _wordFrequencies;
  private final ConcurrentHashMap<String, Map<String, Integer>> _bigrams;
  private String _lastWord = "";
  
  // Learning parameters
  private static final int MIN_WORD_LENGTH = 2;
  private static final int MAX_WORD_LENGTH = 20;
  private static final int FREQUENCY_INCREMENT = 10;
  private static final int MAX_FREQUENCY = 10000;
  private static final int BIGRAM_INCREMENT = 5;
  private static final int DECAY_FACTOR = 2; // Reduce old frequencies by half periodically
  
  public PersonalizationManager(Context context)
  {
    _context = context;
    _prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    _wordFrequencies = new ConcurrentHashMap<>();
    _bigrams = new ConcurrentHashMap<>();
    loadUserData();
  }
  
  /**
   * Record that a word was typed by the user
   */
  public void recordWordUsage(String word)
  {
    if (word == null || word.length() < MIN_WORD_LENGTH || word.length() > MAX_WORD_LENGTH)
      return;
    
    word = word.toLowerCase().trim();
    
    // Update word frequency
    int currentFreq = _wordFrequencies.getOrDefault(word, 0);
    int newFreq = Math.min(currentFreq + FREQUENCY_INCREMENT, MAX_FREQUENCY);
    _wordFrequencies.put(word, newFreq);
    
    // Update bigram (word pair) frequency
    if (!_lastWord.isEmpty())
    {
      String bigramKey = _lastWord + " " + word;
      Map<String, Integer> lastWordBigrams = _bigrams.computeIfAbsent(_lastWord, 
        k -> new ConcurrentHashMap<>());
      
      int bigramFreq = lastWordBigrams.getOrDefault(word, 0);
      lastWordBigrams.put(word, Math.min(bigramFreq + BIGRAM_INCREMENT, MAX_FREQUENCY));
    }
    
    _lastWord = word;
    
    // Save periodically (every 10 words)
    if (_wordFrequencies.size() % 10 == 0)
    {
      saveUserData();
    }
  }
  
  /**
   * Get personalized frequency for a word
   */
  public float getPersonalizedFrequency(String word)
  {
    if (word == null)
      return 0;
    
    word = word.toLowerCase();
    Integer freq = _wordFrequencies.get(word);
    
    if (freq == null)
      return 0;
    
    // Normalize to 0-1 range
    return (float)freq / MAX_FREQUENCY;
  }
  
  /**
   * Get next word predictions based on context
   */
  public Map<String, Float> getNextWordPredictions(String previousWord, int maxPredictions)
  {
    Map<String, Float> predictions = new HashMap<>();
    
    if (previousWord == null || previousWord.isEmpty())
      return predictions;
    
    previousWord = previousWord.toLowerCase();
    Map<String, Integer> bigrams = _bigrams.get(previousWord);
    
    if (bigrams != null)
    {
      // Sort by frequency and take top predictions
      bigrams.entrySet().stream()
        .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
        .limit(maxPredictions)
        .forEach(entry -> {
          float score = (float)entry.getValue() / MAX_FREQUENCY;
          predictions.put(entry.getKey(), score);
        });
    }
    
    return predictions;
  }
  
  /**
   * Boost scores for words based on personalization
   */
  public float adjustScoreWithPersonalization(String word, float baseScore)
  {
    float personalFreq = getPersonalizedFrequency(word);
    
    // Combine base score with personal frequency
    // Give 30% weight to personalization
    return baseScore * 0.7f + personalFreq * 0.3f;
  }
  
  /**
   * Check if user has typed this word before
   */
  public boolean isKnownWord(String word)
  {
    return _wordFrequencies.containsKey(word.toLowerCase());
  }
  
  /**
   * Clear personalization data
   */
  public void clearPersonalizationData()
  {
    _wordFrequencies.clear();
    _bigrams.clear();
    _lastWord = "";
    
    SharedPreferences.Editor editor = _prefs.edit();
    editor.clear();
    editor.apply();
  }
  
  /**
   * Apply decay to reduce influence of old words
   */
  public void applyFrequencyDecay()
  {
    for (Map.Entry<String, Integer> entry : _wordFrequencies.entrySet())
    {
      int newFreq = entry.getValue() / DECAY_FACTOR;
      if (newFreq > 0)
      {
        entry.setValue(newFreq);
      }
      else
      {
        _wordFrequencies.remove(entry.getKey());
      }
    }
    
    // Also decay bigrams
    for (Map<String, Integer> bigramMap : _bigrams.values())
    {
      bigramMap.entrySet().removeIf(entry -> {
        int newFreq = entry.getValue() / DECAY_FACTOR;
        if (newFreq > 0)
        {
          entry.setValue(newFreq);
          return false;
        }
        return true;
      });
    }
    
    saveUserData();
  }
  
  /**
   * Load user data from preferences
   */
  private void loadUserData()
  {
    Map<String, ?> allPrefs = _prefs.getAll();
    
    for (Map.Entry<String, ?> entry : allPrefs.entrySet())
    {
      String key = entry.getKey();
      
      if (key.startsWith(WORD_FREQ_PREFIX))
      {
        String word = key.substring(WORD_FREQ_PREFIX.length());
        int freq = (Integer)entry.getValue();
        _wordFrequencies.put(word, freq);
      }
      else if (key.startsWith(BIGRAM_PREFIX))
      {
        String bigramKey = key.substring(BIGRAM_PREFIX.length());
        String[] parts = bigramKey.split("_", 2);
        if (parts.length == 2)
        {
          String firstWord = parts[0];
          String secondWord = parts[1];
          int freq = (Integer)entry.getValue();
          
          Map<String, Integer> bigramMap = _bigrams.computeIfAbsent(firstWord, 
            k -> new ConcurrentHashMap<>());
          bigramMap.put(secondWord, freq);
        }
      }
      else if (key.equals(LAST_WORD_KEY))
      {
        _lastWord = (String)entry.getValue();
      }
    }
  }
  
  /**
   * Save user data to preferences
   */
  private void saveUserData()
  {
    SharedPreferences.Editor editor = _prefs.edit();
    
    // Save word frequencies (only top 1000 to limit storage)
    _wordFrequencies.entrySet().stream()
      .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
      .limit(1000)
      .forEach(entry -> {
        editor.putInt(WORD_FREQ_PREFIX + entry.getKey(), entry.getValue());
      });
    
    // Save bigrams (only top 500)
    int bigramCount = 0;
    for (Map.Entry<String, Map<String, Integer>> entry : _bigrams.entrySet())
    {
      String firstWord = entry.getKey();
      for (Map.Entry<String, Integer> bigramEntry : entry.getValue().entrySet())
      {
        if (bigramCount++ >= 500)
          break;
        
        String secondWord = bigramEntry.getKey();
        int freq = bigramEntry.getValue();
        editor.putInt(BIGRAM_PREFIX + firstWord + "_" + secondWord, freq);
      }
    }
    
    editor.putString(LAST_WORD_KEY, _lastWord);
    editor.apply();
  }
  
  /**
   * Get statistics about personalization data
   */
  public PersonalizationStats getStats()
  {
    PersonalizationStats stats = new PersonalizationStats();
    stats.totalWords = _wordFrequencies.size();
    stats.totalBigrams = _bigrams.values().stream()
      .mapToInt(Map::size)
      .sum();
    
    if (!_wordFrequencies.isEmpty())
    {
      stats.mostFrequentWord = _wordFrequencies.entrySet().stream()
        .max(Map.Entry.comparingByValue())
        .map(Map.Entry::getKey)
        .orElse("");
    }
    
    return stats;
  }
  
  /**
   * Statistics about personalization data
   */
  public static class PersonalizationStats
  {
    public int totalWords = 0;
    public int totalBigrams = 0;
    public String mostFrequentWord = "";
    
    @Override
    public String toString()
    {
      return String.format("Words: %d, Bigrams: %d, Most frequent: %s",
        totalWords, totalBigrams, mostFrequentWord);
    }
  }
}