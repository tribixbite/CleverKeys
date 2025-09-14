package juloo.keyboard2;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Advanced swipe typing settings that can be tuned by users
 * These parameters affect swipe recognition accuracy and behavior
 */
public class SwipeAdvancedSettings
{
  private static SwipeAdvancedSettings _instance;
  private final SharedPreferences _prefs;
  
  // Gaussian model parameters (affects key hit detection)
  private float _gaussianSigmaXFactor = 0.4f;  // Default: 40% of key width
  private float _gaussianSigmaYFactor = 0.35f; // Default: 35% of key height
  private float _gaussianMinProbability = 0.01f;
  
  // DTW parameters
  private int _dtwSamplingPoints = 200; // Number of points to resample gestures to
  private float _sakoeChibaBandWidth = 0.2f; // Width of DTW optimization band (0-1)
  
  // Calibration blending
  private float _calibrationWeight = 0.7f; // Weight given to calibration data vs dictionary
  private float _calibrationBoost = 0.8f;  // Score multiplier for calibrated words
  
  // Path pruning parameters
  private float _minPathLengthRatio = 0.3f; // Minimum path length relative to word length
  private float _maxPathLengthRatio = 3.0f; // Maximum path length relative to word length
  
  // Loop detection parameters  
  private float _loopDetectionThreshold = 0.15f; // Distance threshold for loop detection
  private int _loopMinPoints = 5; // Minimum points to detect a loop
  
  // Turning point detection
  private float _turningPointThreshold = 30.0f; // Angle threshold in degrees
  
  // N-gram model parameters
  private float _ngramSmoothingFactor = 0.1f; // Smoothing for unseen bigrams
  private int _contextWindowSize = 2; // Number of previous words to consider
  
  private SwipeAdvancedSettings(Context context)
  {
    _prefs = context.getSharedPreferences("swipe_advanced", Context.MODE_PRIVATE);
    loadSettings();
  }
  
  public static synchronized SwipeAdvancedSettings getInstance(Context context)
  {
    if (_instance == null)
    {
      _instance = new SwipeAdvancedSettings(context);
    }
    return _instance;
  }
  
  private void loadSettings()
  {
    _gaussianSigmaXFactor = _prefs.getFloat("gaussian_sigma_x", 0.4f);
    _gaussianSigmaYFactor = _prefs.getFloat("gaussian_sigma_y", 0.35f);
    _gaussianMinProbability = _prefs.getFloat("gaussian_min_prob", 0.01f);
    
    _dtwSamplingPoints = _prefs.getInt("dtw_sampling_points", 200);
    _sakoeChibaBandWidth = _prefs.getFloat("sakoe_chiba_width", 0.2f);
    
    _calibrationWeight = _prefs.getFloat("calibration_weight", 0.7f);
    _calibrationBoost = _prefs.getFloat("calibration_boost", 0.8f);
    
    _minPathLengthRatio = _prefs.getFloat("min_path_length_ratio", 0.3f);
    _maxPathLengthRatio = _prefs.getFloat("max_path_length_ratio", 3.0f);
    
    _loopDetectionThreshold = _prefs.getFloat("loop_threshold", 0.15f);
    _loopMinPoints = _prefs.getInt("loop_min_points", 5);
    
    _turningPointThreshold = _prefs.getFloat("turning_point_threshold", 30.0f);
    
    _ngramSmoothingFactor = _prefs.getFloat("ngram_smoothing", 0.1f);
    _contextWindowSize = _prefs.getInt("context_window", 2);
  }
  
  public void saveSettings()
  {
    SharedPreferences.Editor editor = _prefs.edit();
    
    editor.putFloat("gaussian_sigma_x", _gaussianSigmaXFactor);
    editor.putFloat("gaussian_sigma_y", _gaussianSigmaYFactor);
    editor.putFloat("gaussian_min_prob", _gaussianMinProbability);
    
    editor.putInt("dtw_sampling_points", _dtwSamplingPoints);
    editor.putFloat("sakoe_chiba_width", _sakoeChibaBandWidth);
    
    editor.putFloat("calibration_weight", _calibrationWeight);
    editor.putFloat("calibration_boost", _calibrationBoost);
    
    editor.putFloat("min_path_length_ratio", _minPathLengthRatio);
    editor.putFloat("max_path_length_ratio", _maxPathLengthRatio);
    
    editor.putFloat("loop_threshold", _loopDetectionThreshold);
    editor.putInt("loop_min_points", _loopMinPoints);
    
    editor.putFloat("turning_point_threshold", _turningPointThreshold);
    
    editor.putFloat("ngram_smoothing", _ngramSmoothingFactor);
    editor.putInt("context_window", _contextWindowSize);
    
    editor.apply();
  }
  
  // Getters
  public float getGaussianSigmaXFactor() { return _gaussianSigmaXFactor; }
  public float getGaussianSigmaYFactor() { return _gaussianSigmaYFactor; }
  public float getGaussianMinProbability() { return _gaussianMinProbability; }
  
  public int getDtwSamplingPoints() { return _dtwSamplingPoints; }
  public float getSakoeChibaBandWidth() { return _sakoeChibaBandWidth; }
  
  public float getCalibrationWeight() { return _calibrationWeight; }
  public float getCalibrationBoost() { return _calibrationBoost; }
  
  public float getMinPathLengthRatio() { return _minPathLengthRatio; }
  public float getMaxPathLengthRatio() { return _maxPathLengthRatio; }
  
  public float getLoopDetectionThreshold() { return _loopDetectionThreshold; }
  public int getLoopMinPoints() { return _loopMinPoints; }
  
  public float getTurningPointThreshold() { return _turningPointThreshold; }
  
  public float getNgramSmoothingFactor() { return _ngramSmoothingFactor; }
  public int getContextWindowSize() { return _contextWindowSize; }
  
  // Setters with validation
  public void setGaussianSigmaXFactor(float value) 
  { 
    _gaussianSigmaXFactor = Math.max(0.1f, Math.min(1.0f, value));
    saveSettings();
  }
  
  public void setGaussianSigmaYFactor(float value) 
  { 
    _gaussianSigmaYFactor = Math.max(0.1f, Math.min(1.0f, value));
    saveSettings();
  }
  
  public void setGaussianMinProbability(float value) 
  { 
    _gaussianMinProbability = Math.max(0.001f, Math.min(0.1f, value));
    saveSettings();
  }
  
  public void setDtwSamplingPoints(int value) 
  { 
    _dtwSamplingPoints = Math.max(50, Math.min(500, value));
    saveSettings();
  }
  
  public void setSakoeChibaBandWidth(float value) 
  { 
    _sakoeChibaBandWidth = Math.max(0.05f, Math.min(0.5f, value));
    saveSettings();
  }
  
  public void setCalibrationWeight(float value) 
  { 
    _calibrationWeight = Math.max(0.0f, Math.min(1.0f, value));
    saveSettings();
  }
  
  public void setCalibrationBoost(float value) 
  { 
    _calibrationBoost = Math.max(0.5f, Math.min(2.0f, value));
    saveSettings();
  }
  
  public void setMinPathLengthRatio(float value) 
  { 
    _minPathLengthRatio = Math.max(0.1f, Math.min(1.0f, value));
    saveSettings();
  }
  
  public void setMaxPathLengthRatio(float value) 
  { 
    _maxPathLengthRatio = Math.max(1.5f, Math.min(5.0f, value));
    saveSettings();
  }
  
  public void setLoopDetectionThreshold(float value) 
  { 
    _loopDetectionThreshold = Math.max(0.05f, Math.min(0.5f, value));
    saveSettings();
  }
  
  public void setLoopMinPoints(int value) 
  { 
    _loopMinPoints = Math.max(3, Math.min(20, value));
    saveSettings();
  }
  
  public void setTurningPointThreshold(float value) 
  { 
    _turningPointThreshold = Math.max(10.0f, Math.min(90.0f, value));
    saveSettings();
  }
  
  public void setNgramSmoothingFactor(float value) 
  { 
    _ngramSmoothingFactor = Math.max(0.01f, Math.min(1.0f, value));
    saveSettings();
  }
  
  public void setContextWindowSize(int value) 
  { 
    _contextWindowSize = Math.max(1, Math.min(5, value));
    saveSettings();
  }
  
  /**
   * Reset all settings to defaults
   */
  public void resetToDefaults()
  {
    _gaussianSigmaXFactor = 0.4f;
    _gaussianSigmaYFactor = 0.35f;
    _gaussianMinProbability = 0.01f;
    
    _dtwSamplingPoints = 200;
    _sakoeChibaBandWidth = 0.2f;
    
    _calibrationWeight = 0.7f;
    _calibrationBoost = 0.8f;
    
    _minPathLengthRatio = 0.3f;
    _maxPathLengthRatio = 3.0f;
    
    _loopDetectionThreshold = 0.15f;
    _loopMinPoints = 5;
    
    _turningPointThreshold = 30.0f;
    
    _ngramSmoothingFactor = 0.1f;
    _contextWindowSize = 2;
    
    saveSettings();
  }
}