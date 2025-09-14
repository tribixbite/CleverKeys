package juloo.keyboard2;

import android.content.Context;
import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Word-level bigram model for contextual predictions.
 * Provides P(word | previous_word) probabilities.
 */
public class BigramModel
{
  private static final String TAG = "BigramModel";
  
  // Language-specific bigram models: "language" -> "prev_word|current_word" -> probability
  private final Map<String, Map<String, Float>> _languageBigramProbs;
  
  // Language-specific unigram models: "language" -> word -> probability  
  private final Map<String, Map<String, Float>> _languageUnigramProbs;
  
  // Current active language
  private String _currentLanguage;
  
  // Smoothing parameters
  private static final float LAMBDA = 0.95f; // Interpolation weight for bigram
  private static final float MIN_PROB = 0.0001f; // Minimum probability for unseen words
  
  public BigramModel()
  {
    _languageBigramProbs = new HashMap<>();
    _languageUnigramProbs = new HashMap<>();
    _currentLanguage = "en"; // Default to English
    initializeLanguageModels();
  }
  
  /**
   * Initialize language models with common bigrams for supported languages
   */
  private void initializeLanguageModels()
  {
    initializeEnglishModel();
    initializeSpanishModel();
    initializeFrenchModel();
    initializeGermanModel();
    // More languages can be added here
  }
  
  /**
   * Initialize English language model
   */
  private void initializeEnglishModel()
  {
    Map<String, Float> enBigrams = new HashMap<>();
    Map<String, Float> enUnigrams = new HashMap<>();
    
    // After "the"
    enBigrams.put("the|end", 0.01f);
    enBigrams.put("the|first", 0.015f);
    enBigrams.put("the|last", 0.012f);
    enBigrams.put("the|best", 0.010f);
    enBigrams.put("the|world", 0.008f);
    enBigrams.put("the|time", 0.007f);
    enBigrams.put("the|day", 0.006f);
    enBigrams.put("the|way", 0.005f);
    
    // After "a"
    enBigrams.put("a|lot", 0.02f);
    enBigrams.put("a|little", 0.015f);
    enBigrams.put("a|few", 0.012f);
    enBigrams.put("a|good", 0.010f);
    enBigrams.put("a|great", 0.008f);
    enBigrams.put("a|new", 0.007f);
    enBigrams.put("a|long", 0.006f);
    
    // After "to"
    enBigrams.put("to|be", 0.03f);
    enBigrams.put("to|have", 0.02f);
    enBigrams.put("to|do", 0.015f);
    enBigrams.put("to|go", 0.012f);
    enBigrams.put("to|get", 0.010f);
    enBigrams.put("to|make", 0.008f);
    enBigrams.put("to|see", 0.007f);
    
    // After "of"
    enBigrams.put("of|the", 0.05f);
    enBigrams.put("of|course", 0.02f);
    enBigrams.put("of|all", 0.015f);
    enBigrams.put("of|this", 0.012f);
    enBigrams.put("of|his", 0.010f);
    enBigrams.put("of|her", 0.008f);
    
    // After "in"
    enBigrams.put("in|the", 0.04f);
    enBigrams.put("in|a", 0.02f);
    enBigrams.put("in|this", 0.015f);
    enBigrams.put("in|order", 0.012f);
    enBigrams.put("in|fact", 0.010f);
    enBigrams.put("in|case", 0.008f);
    
    // After "I"
    enBigrams.put("i|am", 0.03f);
    enBigrams.put("i|have", 0.025f);
    enBigrams.put("i|will", 0.02f);
    enBigrams.put("i|was", 0.018f);
    enBigrams.put("i|can", 0.015f);
    enBigrams.put("i|would", 0.012f);
    enBigrams.put("i|think", 0.010f);
    enBigrams.put("i|know", 0.008f);
    enBigrams.put("i|want", 0.007f);
    
    // After "you"
    enBigrams.put("you|are", 0.025f);
    enBigrams.put("you|can", 0.02f);
    enBigrams.put("you|have", 0.018f);
    enBigrams.put("you|will", 0.015f);
    enBigrams.put("you|want", 0.012f);
    enBigrams.put("you|know", 0.010f);
    enBigrams.put("you|need", 0.008f);
    
    // After "it"
    enBigrams.put("it|is", 0.04f);
    enBigrams.put("it|was", 0.025f);
    enBigrams.put("it|will", 0.015f);
    enBigrams.put("it|would", 0.012f);
    enBigrams.put("it|has", 0.010f);
    enBigrams.put("it|can", 0.008f);
    
    // After "that"
    enBigrams.put("that|is", 0.025f);
    enBigrams.put("that|was", 0.02f);
    enBigrams.put("that|the", 0.015f);
    enBigrams.put("that|it", 0.012f);
    enBigrams.put("that|you", 0.010f);
    enBigrams.put("that|he", 0.008f);
    
    // After "with"
    enBigrams.put("with|the", 0.03f);
    enBigrams.put("with|a", 0.02f);
    enBigrams.put("with|his", 0.015f);
    enBigrams.put("with|her", 0.012f);
    enBigrams.put("with|my", 0.010f);
    enBigrams.put("with|your", 0.008f);
    
    // Common unigram probabilities (fallback)
    enUnigrams.put("the", 0.07f);
    enUnigrams.put("be", 0.04f);
    enUnigrams.put("to", 0.035f);
    enUnigrams.put("of", 0.03f);
    enUnigrams.put("and", 0.028f);
    enUnigrams.put("a", 0.025f);
    enUnigrams.put("in", 0.022f);
    enUnigrams.put("that", 0.02f);
    enUnigrams.put("have", 0.018f);
    enUnigrams.put("i", 0.017f);
    enUnigrams.put("it", 0.015f);
    enUnigrams.put("for", 0.014f);
    enUnigrams.put("not", 0.013f);
    enUnigrams.put("on", 0.012f);
    enUnigrams.put("with", 0.011f);
    enUnigrams.put("he", 0.010f);
    enUnigrams.put("as", 0.009f);
    enUnigrams.put("you", 0.009f);
    enUnigrams.put("do", 0.008f);
    enUnigrams.put("at", 0.008f);
    
    // Store English language models
    _languageBigramProbs.put("en", enBigrams);
    _languageUnigramProbs.put("en", enUnigrams);
  }
  
  /**
   * Initialize Spanish language model
   */
  private void initializeSpanishModel()
  {
    Map<String, Float> esBigrams = new HashMap<>();
    Map<String, Float> esUnigrams = new HashMap<>();
    
    // Common Spanish bigrams
    esBigrams.put("de|la", 0.04f);
    esBigrams.put("de|los", 0.025f);
    esBigrams.put("en|el", 0.035f);
    esBigrams.put("en|la", 0.03f);
    esBigrams.put("el|mundo", 0.012f);
    esBigrams.put("la|vida", 0.015f);
    esBigrams.put("que|es", 0.02f);
    esBigrams.put("que|se", 0.018f);
    esBigrams.put("no|es", 0.015f);
    esBigrams.put("se|puede", 0.012f);
    esBigrams.put("por|favor", 0.025f);
    esBigrams.put("muchas|gracias", 0.03f);
    esBigrams.put("muy|bien", 0.02f);
    esBigrams.put("todo|el", 0.015f);
    
    // Spanish unigrams
    esUnigrams.put("de", 0.05f);
    esUnigrams.put("la", 0.04f);
    esUnigrams.put("que", 0.035f);
    esUnigrams.put("el", 0.03f);
    esUnigrams.put("en", 0.025f);
    esUnigrams.put("y", 0.022f);
    esUnigrams.put("a", 0.02f);
    esUnigrams.put("es", 0.018f);
    esUnigrams.put("se", 0.015f);
    esUnigrams.put("no", 0.014f);
    esUnigrams.put("te", 0.012f);
    esUnigrams.put("lo", 0.011f);
    esUnigrams.put("le", 0.01f);
    esUnigrams.put("da", 0.009f);
    esUnigrams.put("su", 0.008f);
    
    _languageBigramProbs.put("es", esBigrams);
    _languageUnigramProbs.put("es", esUnigrams);
  }
  
  /**
   * Initialize French language model
   */
  private void initializeFrenchModel()
  {
    Map<String, Float> frBigrams = new HashMap<>();
    Map<String, Float> frUnigrams = new HashMap<>();
    
    // Common French bigrams
    frBigrams.put("de|la", 0.045f);
    frBigrams.put("de|le", 0.03f);
    frBigrams.put("dans|le", 0.025f);
    frBigrams.put("sur|le", 0.02f);
    frBigrams.put("avec|le", 0.018f);
    frBigrams.put("pour|le", 0.015f);
    frBigrams.put("il|y", 0.025f);
    frBigrams.put("y|a", 0.03f);
    frBigrams.put("c'est|le", 0.02f);
    frBigrams.put("je|suis", 0.025f);
    frBigrams.put("tu|es", 0.02f);
    frBigrams.put("nous|sommes", 0.015f);
    frBigrams.put("très|bien", 0.018f);
    frBigrams.put("tout|le", 0.022f);
    
    // French unigrams
    frUnigrams.put("de", 0.06f);
    frUnigrams.put("le", 0.045f);
    frUnigrams.put("et", 0.035f);
    frUnigrams.put("à", 0.03f);
    frUnigrams.put("un", 0.025f);
    frUnigrams.put("il", 0.022f);
    frUnigrams.put("être", 0.02f);
    frUnigrams.put("et", 0.018f);
    frUnigrams.put("en", 0.016f);
    frUnigrams.put("avoir", 0.014f);
    frUnigrams.put("que", 0.012f);
    frUnigrams.put("pour", 0.011f);
    frUnigrams.put("dans", 0.01f);
    frUnigrams.put("ce", 0.009f);
    frUnigrams.put("son", 0.008f);
    
    _languageBigramProbs.put("fr", frBigrams);
    _languageUnigramProbs.put("fr", frUnigrams);
  }
  
  /**
   * Initialize German language model
   */
  private void initializeGermanModel()
  {
    Map<String, Float> deBigrams = new HashMap<>();
    Map<String, Float> deUnigrams = new HashMap<>();
    
    // Common German bigrams
    deBigrams.put("der|die", 0.03f);
    deBigrams.put("in|der", 0.035f);
    deBigrams.put("von|der", 0.025f);
    deBigrams.put("mit|der", 0.02f);
    deBigrams.put("auf|der", 0.018f);
    deBigrams.put("zu|der", 0.015f);
    deBigrams.put("ich|bin", 0.025f);
    deBigrams.put("du|bist", 0.02f);
    deBigrams.put("er|ist", 0.022f);
    deBigrams.put("wir|sind", 0.018f);
    deBigrams.put("das|ist", 0.03f);
    deBigrams.put("sehr|gut", 0.02f);
    deBigrams.put("vielen|dank", 0.025f);
    deBigrams.put("guten|tag", 0.015f);
    
    // German unigrams
    deUnigrams.put("der", 0.055f);
    deUnigrams.put("die", 0.045f);
    deUnigrams.put("und", 0.035f);
    deUnigrams.put("in", 0.03f);
    deUnigrams.put("den", 0.025f);
    deUnigrams.put("von", 0.022f);
    deUnigrams.put("zu", 0.02f);
    deUnigrams.put("das", 0.018f);
    deUnigrams.put("mit", 0.016f);
    deUnigrams.put("sich", 0.014f);
    deUnigrams.put("auf", 0.012f);
    deUnigrams.put("für", 0.011f);
    deUnigrams.put("ist", 0.01f);
    deUnigrams.put("im", 0.009f);
    deUnigrams.put("dem", 0.008f);
    
    _languageBigramProbs.put("de", deBigrams);
    _languageUnigramProbs.put("de", deUnigrams);
  }
  
  /**
   * Set the active language for predictions
   */
  public void setLanguage(String language)
  {
    if (_languageBigramProbs.containsKey(language))
    {
      _currentLanguage = language;
      Log.d(TAG, "Language set to: " + language);
    }
    else
    {
      Log.w(TAG, "Language not supported: " + language + ", falling back to English");
      _currentLanguage = "en";
    }
  }
  
  /**
   * Get the current active language
   */
  public String getCurrentLanguage()
  {
    return _currentLanguage;
  }
  
  /**
   * Check if a language is supported
   */
  public boolean isLanguageSupported(String language)
  {
    return _languageBigramProbs.containsKey(language);
  }
  
  /**
   * Load bigram data from a file (future enhancement)
   */
  public void loadFromFile(Context context, String filename)
  {
    // Load comprehensive bigram data from assets for current language
    // Format: prev_word current_word probability
    Map<String, Float> bigramProbs = _languageBigramProbs.get(_currentLanguage);
    if (bigramProbs == null)
    {
      bigramProbs = new HashMap<>();
      _languageBigramProbs.put(_currentLanguage, bigramProbs);
    }
    
    try
    {
      BufferedReader reader = new BufferedReader(
        new InputStreamReader(context.getAssets().open(filename)));
      String line;
      while ((line = reader.readLine()) != null)
      {
        String[] parts = line.split("\\s+");
        if (parts.length >= 3)
        {
          String bigram = parts[0].toLowerCase() + "|" + parts[1].toLowerCase();
          float prob = Float.parseFloat(parts[2]);
          bigramProbs.put(bigram, prob);
        }
      }
      reader.close();
      Log.d(TAG, "Loaded " + bigramProbs.size() + " bigrams for " + _currentLanguage + " from " + filename);
    }
    catch (IOException e)
    {
      Log.e(TAG, "Failed to load bigram file: " + filename, e);
    }
  }
  
  /**
   * Get the probability of a word given the previous word(s)
   * Uses linear interpolation between bigram and unigram probabilities
   */
  public float getContextualProbability(String word, List<String> context)
  {
    if (word == null || word.isEmpty())
      return MIN_PROB;
    
    word = word.toLowerCase();
    
    // Get language-specific probability maps
    Map<String, Float> bigramProbs = _languageBigramProbs.get(_currentLanguage);
    Map<String, Float> unigramProbs = _languageUnigramProbs.get(_currentLanguage);
    
    // Fallback to English if current language not available
    if (bigramProbs == null || unigramProbs == null)
    {
      bigramProbs = _languageBigramProbs.get("en");
      unigramProbs = _languageUnigramProbs.get("en");
    }
    
    // If no context, return unigram probability
    if (context == null || context.isEmpty())
    {
      return unigramProbs.getOrDefault(word, MIN_PROB);
    }
    
    // Get the previous word
    String prevWord = context.get(context.size() - 1).toLowerCase();
    String bigramKey = prevWord + "|" + word;
    
    // Look up bigram probability
    float bigramProb = bigramProbs.getOrDefault(bigramKey, 0.0f);
    
    // Look up unigram probability (fallback)
    float unigramProb = unigramProbs.getOrDefault(word, MIN_PROB);
    
    // Linear interpolation: λ * P(word|prev) + (1-λ) * P(word)
    float interpolatedProb = LAMBDA * bigramProb + (1 - LAMBDA) * unigramProb;
    
    // Ensure minimum probability
    return Math.max(interpolatedProb, MIN_PROB);
  }
  
  /**
   * Score a word based on context (returns log probability for numerical stability)
   */
  public float scoreWord(String word, List<String> context)
  {
    float prob = getContextualProbability(word, context);
    // Return log probability to avoid underflow
    return (float)Math.log(prob);
  }
  
  /**
   * Get a multiplier for prediction scoring (1.0 = neutral, >1.0 = boost, <1.0 = penalty)
   */
  public float getContextMultiplier(String word, List<String> context)
  {
    if (context == null || context.isEmpty())
      return 1.0f;
    
    // Get language-specific unigram probabilities
    Map<String, Float> unigramProbs = _languageUnigramProbs.get(_currentLanguage);
    if (unigramProbs == null)
      unigramProbs = _languageUnigramProbs.get("en"); // Fallback to English
    
    float contextProb = getContextualProbability(word, context);
    float baseProb = unigramProbs.getOrDefault(word.toLowerCase(), MIN_PROB);
    
    // Return ratio of contextual to base probability
    // This gives a boost when context makes the word more likely
    float multiplier = contextProb / baseProb;
    
    // Cap the multiplier to avoid extreme values
    return Math.min(Math.max(multiplier, 0.1f), 10.0f);
  }
  
  /**
   * Add a bigram observation (for user adaptation)
   */
  public void addBigram(String prevWord, String word, float weight)
  {
    Map<String, Float> bigramProbs = _languageBigramProbs.get(_currentLanguage);
    if (bigramProbs == null)
      bigramProbs = _languageBigramProbs.get("en"); // Fallback to English
    
    String bigramKey = prevWord.toLowerCase() + "|" + word.toLowerCase();
    float currentProb = bigramProbs.getOrDefault(bigramKey, 0.0f);
    // Simple exponential smoothing for adaptation
    float newProb = 0.9f * currentProb + 0.1f * weight;
    bigramProbs.put(bigramKey, newProb);
  }
  
  /**
   * Get statistics about the model
   */
  public String getStatistics()
  {
    Map<String, Float> currentBigrams = _languageBigramProbs.get(_currentLanguage);
    Map<String, Float> currentUnigrams = _languageUnigramProbs.get(_currentLanguage);
    
    int totalBigramCount = 0;
    int totalUnigramCount = 0;
    
    for (Map<String, Float> bigramMap : _languageBigramProbs.values())
    {
      totalBigramCount += bigramMap.size();
    }
    
    for (Map<String, Float> unigramMap : _languageUnigramProbs.values())
    {
      totalUnigramCount += unigramMap.size();
    }
    
    return String.format("BigramModel: Current Language: %s (%d bigrams, %d unigrams), Total: %d languages, %d bigrams, %d unigrams",
                        _currentLanguage,
                        currentBigrams != null ? currentBigrams.size() : 0,
                        currentUnigrams != null ? currentUnigrams.size() : 0,
                        _languageBigramProbs.size(),
                        totalBigramCount,
                        totalUnigramCount);
  }
}