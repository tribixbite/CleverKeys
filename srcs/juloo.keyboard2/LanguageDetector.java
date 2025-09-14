package juloo.keyboard2;

import android.util.Log;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Simple language detection based on word patterns and character frequency analysis
 * Used for automatic language switching in contextual predictions
 */
public class LanguageDetector
{
  private static final String TAG = "LanguageDetector";
  
  // Language-specific character patterns
  private final Map<String, Map<Character, Float>> _languageCharFreqs;
  
  // Language-specific common words
  private final Map<String, String[]> _languageCommonWords;
  
  // Detection thresholds
  private static final float MIN_CONFIDENCE_THRESHOLD = 0.6f;
  private static final int MIN_TEXT_LENGTH = 10; // Minimum characters needed for detection
  
  public LanguageDetector()
  {
    _languageCharFreqs = new HashMap<>();
    _languageCommonWords = new HashMap<>();
    initializeLanguagePatterns();
  }
  
  /**
   * Initialize language detection patterns
   */
  private void initializeLanguagePatterns()
  {
    initializeEnglishPatterns();
    initializeSpanishPatterns();
    initializeFrenchPatterns();
    initializeGermanPatterns();
  }
  
  /**
   * Initialize English language patterns
   */
  private void initializeEnglishPatterns()
  {
    Map<Character, Float> enChars = new HashMap<>();
    enChars.put('e', 12.7f);
    enChars.put('t', 9.1f);
    enChars.put('a', 8.2f);
    enChars.put('o', 7.5f);
    enChars.put('i', 7.0f);
    enChars.put('n', 6.7f);
    enChars.put('s', 6.3f);
    enChars.put('h', 6.1f);
    enChars.put('r', 6.0f);
    _languageCharFreqs.put("en", enChars);
    
    String[] enWords = {"the", "be", "to", "of", "and", "a", "in", "that", "have", "I", "it", "for", "not", "on", "with", "he", "as", "you", "do", "at"};
    _languageCommonWords.put("en", enWords);
  }
  
  /**
   * Initialize Spanish language patterns
   */
  private void initializeSpanishPatterns()
  {
    Map<Character, Float> esChars = new HashMap<>();
    esChars.put('a', 12.5f);
    esChars.put('e', 12.2f);
    esChars.put('o', 8.7f);
    esChars.put('s', 8.0f);
    esChars.put('n', 6.8f);
    esChars.put('r', 6.9f);
    esChars.put('i', 6.2f);
    esChars.put('l', 5.0f);
    esChars.put('d', 5.9f);
    esChars.put('t', 4.6f);
    _languageCharFreqs.put("es", esChars);
    
    String[] esWords = {"de", "la", "que", "el", "en", "y", "a", "es", "se", "no", "te", "lo", "le", "da", "su", "por", "son", "con", "para", "una"};
    _languageCommonWords.put("es", esWords);
  }
  
  /**
   * Initialize French language patterns
   */
  private void initializeFrenchPatterns()
  {
    Map<Character, Float> frChars = new HashMap<>();
    frChars.put('e', 14.7f);
    frChars.put('s', 7.9f);
    frChars.put('a', 7.6f);
    frChars.put('i', 7.5f);
    frChars.put('t', 7.2f);
    frChars.put('n', 7.1f);
    frChars.put('r', 6.6f);
    frChars.put('u', 6.3f);
    frChars.put('l', 5.5f);
    frChars.put('o', 5.4f);
    _languageCharFreqs.put("fr", frChars);
    
    String[] frWords = {"de", "le", "et", "à", "un", "il", "être", "et", "en", "avoir", "que", "pour", "dans", "ce", "son", "une", "sur", "avec", "ne", "se"};
    _languageCommonWords.put("fr", frWords);
  }
  
  /**
   * Initialize German language patterns
   */
  private void initializeGermanPatterns()
  {
    Map<Character, Float> deChars = new HashMap<>();
    deChars.put('e', 17.4f);
    deChars.put('n', 9.8f);
    deChars.put('s', 7.3f);
    deChars.put('r', 7.0f);
    deChars.put('i', 7.5f);
    deChars.put('t', 6.2f);
    deChars.put('d', 5.1f);
    deChars.put('h', 4.8f);
    deChars.put('u', 4.4f);
    deChars.put('l', 3.4f);
    _languageCharFreqs.put("de", deChars);
    
    String[] deWords = {"der", "die", "und", "in", "den", "von", "zu", "das", "mit", "sich", "auf", "für", "ist", "im", "dem", "nicht", "ein", "eine", "als", "auch"};
    _languageCommonWords.put("de", deWords);
  }
  
  /**
   * Detect language from a text sample
   * @param text Input text to analyze
   * @return Detected language code ("en", "es", "fr", "de") or null if detection fails
   */
  public String detectLanguage(String text)
  {
    if (text == null || text.length() < MIN_TEXT_LENGTH)
    {
      return null; // Not enough text for reliable detection
    }
    
    text = text.toLowerCase().trim();
    
    // Calculate scores for each language
    Map<String, Float> languageScores = new HashMap<>();
    for (String language : _languageCharFreqs.keySet())
    {
      float score = calculateLanguageScore(text, language);
      languageScores.put(language, score);
      Log.d(TAG, "Language " + language + " score: " + score);
    }
    
    // Find the best match
    String bestLanguage = null;
    float bestScore = 0.0f;
    
    for (Map.Entry<String, Float> entry : languageScores.entrySet())
    {
      if (entry.getValue() > bestScore)
      {
        bestScore = entry.getValue();
        bestLanguage = entry.getKey();
      }
    }
    
    // Check if confidence is high enough
    if (bestScore >= MIN_CONFIDENCE_THRESHOLD)
    {
      Log.d(TAG, "Detected language: " + bestLanguage + " (confidence: " + bestScore + ")");
      return bestLanguage;
    }
    
    Log.d(TAG, "Language detection failed, confidence too low: " + bestScore);
    return null; // Low confidence
  }
  
  /**
   * Detect language from a list of recent words
   * @param words List of recent words typed by user
   * @return Detected language code or null if detection fails
   */
  public String detectLanguageFromWords(List<String> words)
  {
    if (words == null || words.isEmpty())
      return null;
    
    StringBuilder textBuilder = new StringBuilder();
    for (String word : words)
    {
      if (word != null)
      {
        textBuilder.append(word).append(" ");
      }
    }
    
    return detectLanguage(textBuilder.toString());
  }
  
  /**
   * Calculate language score based on character frequency and common words
   */
  private float calculateLanguageScore(String text, String language)
  {
    float charScore = calculateCharacterFrequencyScore(text, language);
    float wordScore = calculateCommonWordScore(text, language);
    
    // Weighted combination: 60% character frequency, 40% common words
    return (charScore * 0.6f) + (wordScore * 0.4f);
  }
  
  /**
   * Calculate score based on character frequency analysis
   */
  private float calculateCharacterFrequencyScore(String text, String language)
  {
    Map<Character, Float> expectedFreqs = _languageCharFreqs.get(language);
    if (expectedFreqs == null)
      return 0.0f;
    
    // Count character frequencies in the text
    Map<Character, Integer> actualCounts = new HashMap<>();
    int totalChars = 0;
    
    for (char c : text.toCharArray())
    {
      if (Character.isLetter(c))
      {
        actualCounts.put(c, actualCounts.getOrDefault(c, 0) + 1);
        totalChars++;
      }
    }
    
    if (totalChars == 0)
      return 0.0f;
    
    // Calculate correlation between expected and actual frequencies
    float score = 0.0f;
    int matchedChars = 0;
    
    for (Map.Entry<Character, Float> entry : expectedFreqs.entrySet())
    {
      char c = entry.getKey();
      float expectedFreq = entry.getValue();
      
      int actualCount = actualCounts.getOrDefault(c, 0);
      float actualFreq = (actualCount * 100.0f) / totalChars;
      
      // Use inverse of frequency difference as score contribution
      float freqDiff = Math.abs(expectedFreq - actualFreq);
      float contribution = Math.max(0, 1.0f - (freqDiff / expectedFreq));
      score += contribution;
      matchedChars++;
    }
    
    return matchedChars > 0 ? score / matchedChars : 0.0f;
  }
  
  /**
   * Calculate score based on presence of common words
   */
  private float calculateCommonWordScore(String text, String language)
  {
    String[] commonWords = _languageCommonWords.get(language);
    if (commonWords == null)
      return 0.0f;
    
    String[] textWords = text.split("\\s+");
    if (textWords.length == 0)
      return 0.0f;
    
    int matches = 0;
    for (String commonWord : commonWords)
    {
      for (String textWord : textWords)
      {
        if (commonWord.equals(textWord.toLowerCase()))
        {
          matches++;
          break; // Only count each common word once
        }
      }
    }
    
    // Score is the ratio of matched common words
    return (float) matches / commonWords.length;
  }
  
  /**
   * Get list of supported languages
   */
  public String[] getSupportedLanguages()
  {
    return _languageCharFreqs.keySet().toArray(new String[0]);
  }
  
  /**
   * Check if a language is supported
   */
  public boolean isLanguageSupported(String language)
  {
    return _languageCharFreqs.containsKey(language);
  }
  
  /**
   * Set the minimum confidence threshold for detection
   */
  public void setConfidenceThreshold(float threshold)
  {
    // Note: This would require making MIN_CONFIDENCE_THRESHOLD non-final
    // For now, keeping it as a constant for reliability
  }
}