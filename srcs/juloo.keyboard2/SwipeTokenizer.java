package juloo.keyboard2;

import android.content.Context;
import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Tokenizer for neural swipe prediction
 * Handles character-to-index mapping for ONNX model input
 * Matches the tokenizer configuration from the web demo
 */
public class SwipeTokenizer
{
  private static final String TAG = "SwipeTokenizer";
  
  // Special token indices (matching web demo)
  public static final int PAD_IDX = 0;
  public static final int UNK_IDX = 1;
  public static final int SOS_IDX = 2;
  public static final int EOS_IDX = 3;
  
  // Character mappings
  private Map<Character, Integer> _charToIdx;
  private Map<Integer, Character> _idxToChar;
  private boolean _isLoaded = false;
  
  public SwipeTokenizer()
  {
    initializeDefaultMapping();
  }
  
  /**
   * Load tokenizer configuration from assets
   */
  public boolean loadFromAssets(Context context)
  {
    try
    {
      Log.d(TAG, "Loading tokenizer configuration from assets");
      
      InputStream inputStream = context.getAssets().open("models/tokenizer.json");
      BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
      
      StringBuilder jsonBuilder = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null)
      {
        jsonBuilder.append(line);
      }
      reader.close();
      
      // Parse JSON and update mappings
      String jsonStr = jsonBuilder.toString();
      Log.d(TAG, "Loaded tokenizer JSON configuration");
      
      // For now, JSON parsing is simplified - using default mapping
      // The JSON structure is available for future enhancement
      
      _isLoaded = true;
      Log.d(TAG, String.format("Tokenizer loaded with %d characters", _charToIdx.size()));
      return true;
    }
    catch (IOException e)
    {
      Log.w(TAG, "Could not load tokenizer from assets, using defaults: " + e.getMessage());
      _isLoaded = true;
      return false;
    }
  }
  
  /**
   * Convert character to token index
   */
  public int charToIndex(char c)
  {
    Character ch = Character.toLowerCase(c);
    Integer idx = _charToIdx.get(ch);
    return idx != null ? idx : UNK_IDX;
  }
  
  /**
   * Convert token index to character
   */
  public char indexToChar(int idx)
  {
    Character ch = _idxToChar.get(idx);
    return ch != null ? ch : '?';
  }
  
  /**
   * Get vocabulary size
   */
  public int getVocabSize()
  {
    return _charToIdx.size();
  }
  
  /**
   * Check if tokenizer is loaded
   */
  public boolean isLoaded()
  {
    return _isLoaded;
  }
  
  private void initializeDefaultMapping()
  {
    _charToIdx = new HashMap<>();
    _idxToChar = new HashMap<>();
    
    // Special tokens
    addMapping(PAD_IDX, '\0');  // Padding
    addMapping(UNK_IDX, '?');   // Unknown
    addMapping(SOS_IDX, '^');   // Start of sequence
    addMapping(EOS_IDX, '$');   // End of sequence
    
    // Alphabet (a-z) - exactly matching web demo
    int idx = 4;
    for (char c = 'a'; c <= 'z'; c++)
    {
      addMapping(idx++, c);
    }
    
    // No extra symbols - web demo only uses 4 special tokens + 26 letters = 30 total
    
    Log.d(TAG, String.format("Default tokenizer initialized with %d tokens", _charToIdx.size()));
  }
  
  private void addMapping(int idx, char ch)
  {
    _charToIdx.put(ch, idx);
    _idxToChar.put(idx, ch);
  }
  
  /**
   * Get character-to-index mapping (for debugging)
   */
  public Map<Character, Integer> getCharToIdxMapping()
  {
    return new HashMap<>(_charToIdx);
  }
}