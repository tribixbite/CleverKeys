package juloo.keyboard2;

import android.content.Context;
import android.graphics.PointF;
import android.util.Log;
import java.util.Map;

/**
 * Neural swipe typing engine using ONNX transformer models
 * Replaces SwipeTypingEngine with neural prediction pipeline
 * Maintains same interface for seamless integration
 */
public class NeuralSwipeTypingEngine
{
  public interface DebugLogger
  {
    void log(String message);
  }
  private static final String TAG = "NeuralSwipeTypingEngine";
  
  private Context _context;
  private Config _config;
  private OnnxSwipePredictor _neuralPredictor;
  
  // State tracking
  private boolean _initialized = false;
  
  // Debug logging callback
  private DebugLogger _debugLogger;
  
  public NeuralSwipeTypingEngine(Context context, Config config)
  {
    _context = context;
    _config = config;
    
    // OPTIMIZATION: Use singleton predictor with session persistence
    _neuralPredictor = OnnxSwipePredictor.getInstance(context);
    
    Log.d(TAG, "NeuralSwipeTypingEngine created - using persistent singleton predictor");
  }
  
  /**
   * Initialize the engine and load models
   */
  public boolean initialize()
  {
    if (_initialized)
    {
      return true;
    }
    
    try
    {
      Log.d(TAG, "Initializing pure neural swipe engine...");
      
      // Propagate debug logger to predictor
      if (_debugLogger != null)
      {
        _neuralPredictor.setDebugLogger(_debugLogger);
      }
      
      // Initialize neural predictor - MUST succeed or throw error
      // Note: Singleton may already be initialized, which is optimal for performance
      boolean neuralReady = _neuralPredictor.initialize();
      
      if (!neuralReady)
      {
        throw new RuntimeException("Failed to initialize ONNX neural models");
      }
      
      _initialized = true;
      
      Log.d(TAG, "Neural engine initialized successfully - pure neural mode");
      
      return true;
    }
    catch (Exception e)
    {
      Log.e(TAG, "Failed to initialize neural engine", e);
      // Pure neural - no fallback variables needed
      _initialized = true;
      return false;
    }
  }
  
  /**
   * Main prediction method - maintains compatibility with legacy interface
   */
  public PredictionResult predict(SwipeInput input)
  {
    // Add stack trace to see who's calling this
    Log.d(TAG, "🔥🔥🔥 NEURAL PREDICTION CALLED FROM:");
    for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
      if (element.getClassName().contains("juloo.keyboard2")) {
        Log.d(TAG, "🔥   " + element.getClassName() + "." + element.getMethodName() + ":" + element.getLineNumber());
      }
    }
    
    if (!_initialized)
    {
      initialize();
    }
    
    Log.d(TAG, "=== PURE NEURAL PREDICTION START ===");
    Log.d(TAG, String.format(
      "Input: keySeq=%s, pathLen=%.1f, duration=%.2fs", 
      input.keySequence, input.pathLength, input.duration));
    
    Log.d(TAG, "Using PURE NEURAL prediction - no classification needed");
    
    try
    {
      PredictionResult result = _neuralPredictor.predict(input);
      
      if (result != null)
      {
        Log.d(TAG, String.format("Neural prediction successful: %d candidates", 
          result.words.size()));
        return result;
      }
      else
      {
        throw new RuntimeException("Neural prediction returned null result");
      }
    }
    catch (Exception e)
    {
      Log.e(TAG, "Neural prediction failed", e);
      throw new RuntimeException("Neural prediction failed: " + e.getMessage());
    }
  }
  
  /**
   * Set keyboard dimensions for coordinate mapping
   */
  public void setKeyboardDimensions(float width, float height)
  {
    if (_neuralPredictor != null)
    {
      _neuralPredictor.setKeyboardDimensions(width, height);
      Log.d(TAG, String.format("Set keyboard dimensions: %.0fx%.0f", width, height));
    }
  }
  
  /**
   * Set real key positions for accurate coordinate mapping
   */
  public void setRealKeyPositions(Map<Character, PointF> realPositions)
  {
    if (_neuralPredictor != null)
    {
      _neuralPredictor.setRealKeyPositions(realPositions);
      Log.d(TAG, String.format("Set key positions: %d keys", 
        realPositions != null ? realPositions.size() : 0));
    }
  }
  
  /**
   * Update configuration
   */
  public void setConfig(Config config)
  {
    _config = config;
    
    // Update neural predictor configuration
    if (_neuralPredictor != null)
    {
      _neuralPredictor.setConfig(config);
    }
    
    Log.d(TAG, "Neural config updated");
  }
  
  /**
   * Check if neural prediction is available
   */
  public boolean isNeuralAvailable()
  {
    return _neuralPredictor != null && _neuralPredictor.isAvailable();
  }
  
  /**
   * Get current prediction mode
   */
  public String getCurrentMode()
  {
    return isNeuralAvailable() ? "neural" : "error";
  }
  
  /**
   * Set debug logger for detailed logging
   */
  public void setDebugLogger(DebugLogger logger)
  {
    _debugLogger = logger;
    if (_neuralPredictor != null)
    {
      _neuralPredictor.setDebugLogger(logger);
    }
  }
  
  private void logDebug(String message)
  {
    if (_debugLogger != null)
    {
      _debugLogger.log(message);
    }
  }
  
  /**
   * Clean up resources
   */
  public void cleanup()
  {
    if (_neuralPredictor != null)
    {
      _neuralPredictor.cleanup();
    }
    
    Log.d(TAG, "Neural swipe engine cleaned up");
  }
}