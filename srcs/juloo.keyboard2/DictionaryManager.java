package juloo.keyboard2;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Manages word dictionaries for different languages and user custom words
 */
public class DictionaryManager
{
  private final Context _context;
  private final SharedPreferences _userDictPrefs;
  private final Map<String, WordPredictor> _predictors;
  private final Set<String> _userWords;
  private String _currentLanguage;
  private WordPredictor _currentPredictor;
  
  private static final String USER_DICT_PREFS = "user_dictionary";
  private static final String USER_WORDS_KEY = "user_words";
  
  public DictionaryManager(Context context)
  {
    _context = context;
    _userDictPrefs = context.getSharedPreferences(USER_DICT_PREFS, Context.MODE_PRIVATE);
    _predictors = new HashMap<>();
    _userWords = new HashSet<>();
    loadUserWords();
    setLanguage(Locale.getDefault().getLanguage());
  }
  
  /**
   * Set the active language for prediction
   */
  public void setLanguage(String languageCode)
  {
    if (languageCode == null)
      languageCode = "en";
      
    _currentLanguage = languageCode;
    
    // Get or create predictor for this language
    _currentPredictor = _predictors.get(languageCode);
    if (_currentPredictor == null)
    {
      _currentPredictor = new WordPredictor();
      _currentPredictor.loadDictionary(_context, languageCode);
      _predictors.put(languageCode, _currentPredictor);
    }
  }
  
  /**
   * Get word predictions for the given key sequence
   */
  public List<String> getPredictions(String keySequence)
  {
    if (_currentPredictor == null)
      return new ArrayList<>();
      
    List<String> predictions = _currentPredictor.predictWords(keySequence);
    
    // Add user words that match
    String lowerSequence = keySequence.toLowerCase();
    for (String userWord : _userWords)
    {
      if (userWord.toLowerCase().startsWith(lowerSequence) && !predictions.contains(userWord))
      {
        predictions.add(0, userWord); // Add at beginning
        if (predictions.size() > 5)
          predictions.remove(predictions.size() - 1);
      }
    }
    
    return predictions;
  }
  
  /**
   * Add a word to the user dictionary
   */
  public void addUserWord(String word)
  {
    if (word == null || word.isEmpty())
      return;
      
    _userWords.add(word);
    saveUserWords();
  }
  
  /**
   * Remove a word from the user dictionary
   */
  public void removeUserWord(String word)
  {
    _userWords.remove(word);
    saveUserWords();
  }
  
  /**
   * Check if a word is in the user dictionary
   */
  public boolean isUserWord(String word)
  {
    return _userWords.contains(word);
  }
  
  /**
   * Clear the user dictionary
   */
  public void clearUserDictionary()
  {
    _userWords.clear();
    saveUserWords();
  }
  
  /**
   * Load user words from preferences
   */
  private void loadUserWords()
  {
    Set<String> words = _userDictPrefs.getStringSet(USER_WORDS_KEY, new HashSet<String>());
    _userWords.clear();
    _userWords.addAll(words);
  }
  
  /**
   * Save user words to preferences
   */
  private void saveUserWords()
  {
    SharedPreferences.Editor editor = _userDictPrefs.edit();
    editor.putStringSet(USER_WORDS_KEY, new HashSet<>(_userWords));
    editor.apply();
  }
  
  /**
   * Get the current language code
   */
  public String getCurrentLanguage()
  {
    return _currentLanguage;
  }
  
  /**
   * Preload dictionaries for given languages
   */
  public void preloadLanguages(String[] languageCodes)
  {
    for (String code : languageCodes)
    {
      if (!_predictors.containsKey(code))
      {
        WordPredictor predictor = new WordPredictor();
        predictor.loadDictionary(_context, code);
        _predictors.put(code, predictor);
      }
    }
  }
}