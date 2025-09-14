package juloo.keyboard2;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages user adaptation by tracking word selection history and adjusting
 * word frequencies based on user preferences.
 */
public class UserAdaptationManager
{
  private static final String TAG = "UserAdaptationManager";
  private static final String PREFS_NAME = "user_adaptation";
  private static final String KEY_WORD_SELECTIONS = "word_selections_";
  private static final String KEY_TOTAL_SELECTIONS = "total_selections";
  private static final String KEY_LAST_RESET = "last_reset";
  
  // Configuration constants
  private static final int MIN_SELECTIONS_FOR_ADAPTATION = 5;
  private static final int MAX_TRACKED_WORDS = 1000;
  private static final float ADAPTATION_STRENGTH = 0.3f; // How much to boost frequently selected words
  private static final long RESET_PERIOD_MS = 30L * 24L * 60L * 60L * 1000L; // 30 days
  
  private final Context _context;
  private final SharedPreferences _prefs;
  private final Map<String, Integer> _selectionCounts;
  private int _totalSelections;
  private boolean _isEnabled;
  
  private static UserAdaptationManager _instance = null;
  
  public static synchronized UserAdaptationManager getInstance(Context context)
  {
    if (_instance == null)
    {
      _instance = new UserAdaptationManager(context.getApplicationContext());
    }
    return _instance;
  }
  
  private UserAdaptationManager(Context context)
  {
    _context = context;
    _prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    _selectionCounts = new HashMap<>();
    _isEnabled = true;
    
    loadSelectionHistory();
    checkForPeriodicReset();
  }
  
  /**
   * Record that a word was selected by the user
   */
  public void recordSelection(String word)
  {
    if (!_isEnabled || word == null || word.trim().isEmpty())
      return;
    
    word = word.toLowerCase().trim();
    
    // Increment selection count
    int currentCount = _selectionCounts.getOrDefault(word, 0);
    _selectionCounts.put(word, currentCount + 1);
    _totalSelections++;
    
    // Limit the number of tracked words to prevent unbounded growth
    if (_selectionCounts.size() > MAX_TRACKED_WORDS)
    {
      pruneOldSelections();
    }
    
    // Save to persistent storage periodically (every 10 selections)
    if (_totalSelections % 10 == 0)
    {
      saveSelectionHistory();
    }
    
    Log.d(TAG, String.format("Recorded selection: '%s' (count: %d, total: %d)", 
                              word, currentCount + 1, _totalSelections));
  }
  
  /**
   * Get the adaptation multiplier for a word based on selection history
   * Returns 1.0 for no adaptation, >1.0 for frequently selected words
   */
  public float getAdaptationMultiplier(String word)
  {
    if (!_isEnabled || word == null || _totalSelections < MIN_SELECTIONS_FOR_ADAPTATION)
      return 1.0f;
    
    word = word.toLowerCase().trim();
    int selectionCount = _selectionCounts.getOrDefault(word, 0);
    
    if (selectionCount == 0)
      return 1.0f;
    
    // Calculate relative frequency (0 to 1)
    float relativeFrequency = (float) selectionCount / _totalSelections;
    
    // Apply adaptation strength to boost frequently selected words
    // Words selected often get up to 30% boost (with default ADAPTATION_STRENGTH)
    float multiplier = 1.0f + (relativeFrequency * ADAPTATION_STRENGTH * 10.0f);
    
    // Cap the maximum boost to prevent any single word from dominating
    multiplier = Math.min(multiplier, 2.0f);
    
    return multiplier;
  }
  
  /**
   * Get selection count for a specific word
   */
  public int getSelectionCount(String word)
  {
    if (word == null)
      return 0;
    return _selectionCounts.getOrDefault(word.toLowerCase().trim(), 0);
  }
  
  /**
   * Get total number of selections recorded
   */
  public int getTotalSelections()
  {
    return _totalSelections;
  }
  
  /**
   * Get number of unique words being tracked
   */
  public int getTrackedWordCount()
  {
    return _selectionCounts.size();
  }
  
  /**
   * Enable or disable user adaptation
   */
  public void setEnabled(boolean enabled)
  {
    _isEnabled = enabled;
    Log.d(TAG, "User adaptation " + (enabled ? "enabled" : "disabled"));
  }
  
  /**
   * Check if user adaptation is enabled
   */
  public boolean isEnabled()
  {
    return _isEnabled;
  }
  
  /**
   * Reset all adaptation data
   */
  public void resetAdaptation()
  {
    _selectionCounts.clear();
    _totalSelections = 0;
    
    // Clear from persistent storage
    SharedPreferences.Editor editor = _prefs.edit();
    editor.clear();
    editor.putLong(KEY_LAST_RESET, System.currentTimeMillis());
    editor.apply();
    
    Log.d(TAG, "User adaptation data reset");
  }
  
  /**
   * Get adaptation statistics for debugging
   */
  public String getAdaptationStats()
  {
    if (!_isEnabled)
      return "User adaptation disabled";
    
    StringBuilder stats = new StringBuilder();
    stats.append(String.format("User Adaptation Stats:\n"));
    stats.append(String.format("- Total selections: %d\n", _totalSelections));
    stats.append(String.format("- Unique words tracked: %d\n", _selectionCounts.size()));
    stats.append(String.format("- Adaptation active: %s\n", 
                               _totalSelections >= MIN_SELECTIONS_FOR_ADAPTATION ? "Yes" : "No"));
    
    if (_totalSelections >= MIN_SELECTIONS_FOR_ADAPTATION)
    {
      stats.append("\nTop 10 most selected words:\n");
      _selectionCounts.entrySet().stream()
        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
        .limit(10)
        .forEach(entry -> {
          float multiplier = getAdaptationMultiplier(entry.getKey());
          stats.append(String.format("- %s: %d selections (%.2fx boost)\n", 
                                     entry.getKey(), entry.getValue(), multiplier));
        });
    }
    
    return stats.toString();
  }
  
  /**
   * Load selection history from persistent storage
   */
  private void loadSelectionHistory()
  {
    _totalSelections = _prefs.getInt(KEY_TOTAL_SELECTIONS, 0);
    
    // Load individual word counts
    Map<String, ?> allPrefs = _prefs.getAll();
    for (Map.Entry<String, ?> entry : allPrefs.entrySet())
    {
      String key = entry.getKey();
      if (key.startsWith(KEY_WORD_SELECTIONS))
      {
        String word = key.substring(KEY_WORD_SELECTIONS.length());
        if (entry.getValue() instanceof Integer)
        {
          _selectionCounts.put(word, (Integer) entry.getValue());
        }
      }
    }
    
    Log.d(TAG, String.format("Loaded adaptation data: %d total selections, %d unique words", 
                              _totalSelections, _selectionCounts.size()));
  }
  
  /**
   * Save selection history to persistent storage
   */
  private void saveSelectionHistory()
  {
    SharedPreferences.Editor editor = _prefs.edit();
    editor.putInt(KEY_TOTAL_SELECTIONS, _totalSelections);
    
    // Save individual word counts
    for (Map.Entry<String, Integer> entry : _selectionCounts.entrySet())
    {
      editor.putInt(KEY_WORD_SELECTIONS + entry.getKey(), entry.getValue());
    }
    
    editor.apply();
    
    Log.d(TAG, "Saved adaptation data to persistent storage");
  }
  
  /**
   * Remove least frequently selected words to prevent unbounded growth
   */
  private void pruneOldSelections()
  {
    // Find the minimum selection count threshold (remove bottom 20%)
    int targetSize = (int) (MAX_TRACKED_WORDS * 0.8);
    
    _selectionCounts.entrySet().stream()
      .sorted(Map.Entry.<String, Integer>comparingByValue())
      .limit(_selectionCounts.size() - targetSize)
      .map(Map.Entry::getKey)
      .forEach(_selectionCounts::remove);
    
    Log.d(TAG, String.format("Pruned selection data from %d to %d words", 
                              _selectionCounts.size() + (_selectionCounts.size() - targetSize), 
                              _selectionCounts.size()));
  }
  
  /**
   * Check if it's time for a periodic reset to prevent stale data
   */
  private void checkForPeriodicReset()
  {
    long lastReset = _prefs.getLong(KEY_LAST_RESET, System.currentTimeMillis());
    long timeSinceReset = System.currentTimeMillis() - lastReset;
    
    if (timeSinceReset > RESET_PERIOD_MS)
    {
      Log.d(TAG, "Performing periodic reset of adaptation data (30 days elapsed)");
      resetAdaptation();
    }
  }
  
  /**
   * Cleanup method to be called when the system is destroyed
   */
  public void cleanup()
  {
    saveSelectionHistory();
  }
}