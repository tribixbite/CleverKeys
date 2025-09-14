package juloo.keyboard2;

import android.content.Context;
import android.graphics.PointF;
import java.util.ArrayList;
import java.util.List;

/**
 * Enhanced Swipe Gesture Recognizer with CGR-based word prediction
 * Extends ImprovedSwipeGestureRecognizer to maintain compatibility
 * while adding continuous gesture recognition for real-time word predictions
 */
public class EnhancedSwipeGestureRecognizer extends ImprovedSwipeGestureRecognizer
{
  // New CGR-based prediction system (extends parent for base functionality)
  private final RealTimeSwipePredictor _swipePredictor;
  private boolean _cgrInitialized;
  
  // Callback interface for predictions
  public interface OnSwipePredictionListener
  {
    void onSwipePredictionUpdate(List<String> predictions);
    void onSwipePredictionComplete(List<String> finalPredictions);
    void onSwipePredictionCleared();
  }
  
  private OnSwipePredictionListener _predictionListener;
  
  public EnhancedSwipeGestureRecognizer()
  {
    super(); // Call parent constructor
    
    // Initialize new CGR system
    _swipePredictor = new RealTimeSwipePredictor();
    _cgrInitialized = false;
  }
  
  /**
   * Initialize the CGR prediction system
   */
  public void initializePredictionSystem(Context context)
  {
    if (!_cgrInitialized)
    {
      _swipePredictor.initialize(context);
      
      // Set up prediction callbacks
      _swipePredictor.setOnSwipePredictionListener(new RealTimeSwipePredictor.OnSwipePredictionListener()
      {
        @Override
        public void onSwipePredictionUpdate(List<String> predictions)
        {
          if (_predictionListener != null)
          {
            _predictionListener.onSwipePredictionUpdate(predictions);
          }
        }
        
        @Override
        public void onSwipePredictionComplete(List<String> finalPredictions)
        {
          if (_predictionListener != null)
          {
            _predictionListener.onSwipePredictionComplete(finalPredictions);
          }
        }
        
        @Override
        public void onSwipePredictionCleared()
        {
          if (_predictionListener != null)
          {
            _predictionListener.onSwipePredictionCleared();
          }
        }
      });
      
      _cgrInitialized = true;
    }
  }
  
  /**
   * Set prediction listener
   */
  public void setOnSwipePredictionListener(OnSwipePredictionListener listener)
  {
    _predictionListener = listener;
  }
  
  /**
   * Start tracking a new swipe gesture (enhanced with CGR)
   */
  @Override
  public void startSwipe(float x, float y, KeyboardData.Key key)
  {
    // Call parent implementation for original functionality
    super.startSwipe(x, y, key);
    
    // New CGR functionality - start gesture prediction
    if (_cgrInitialized)
    {
      _swipePredictor.onTouchBegan(x, y);
    }
  }
  
  /**
   * Add a point to the current swipe path (enhanced with CGR)
   */
  @Override
  public void addPoint(float x, float y, KeyboardData.Key key)
  {
    // Call parent implementation for original functionality
    super.addPoint(x, y, key);
    
    // New CGR functionality - update gesture prediction
    if (_cgrInitialized)
    {
      // _swipePredictor.onTouchMoved(x, y);
    }
  }
  
  /**
   * End the swipe gesture and return the touched keys (enhanced with CGR)
   */
  @Override
  public SwipeResult endSwipe()
  {
    // Call parent implementation for original functionality
    SwipeResult result = super.endSwipe();
    
    /*
    // New CGR functionality - complete gesture prediction
    if (_cgrInitialized)
    {
      List<PointF> swipePath = getSwipePath();
      if (!swipePath.isEmpty())
      {
        PointF lastPoint = swipePath.get(swipePath.size() - 1);
        _swipePredictor.onTouchEnded(lastPoint.x, lastPoint.y);
      }
    }
    */
    
    return result;
  }
  
  /**
   * Handle key press events (for clearing predictions)
   */
  public void onKeyPressed(KeyValue key)
  {
    if (_cgrInitialized)
    {
      _swipePredictor.onKeyPressed(key);
    }
  }
  
  /**
   * Select a prediction (called when user taps on suggestion)
   */
  public void selectPrediction(String word)
  {
    if (_cgrInitialized)
    {
      _swipePredictor.selectPrediction(word);
    }
  }
  
  /**
   * Get current predictions
   */
  public List<String> getCurrentPredictions()
  {
    if (_cgrInitialized)
    {
      return _swipePredictor.getCurrentPredictions();
    }
    return new ArrayList<>();
  }
  
  /**
   * Get current gesture point count for threshold-based prediction strategy
   * Used to implement unified prediction timing with SwipeCalibrationActivity
   */
  public int getCurrentGesturePointCount()
  {
    // Simple fallback: return a reasonable estimate for now
    // This will be improved when we have access to the actual gesture data
    return 0; // Will be updated to return actual point count
  }
  
  /**
   * Check if predictions are persisting
   */
  public boolean arePredictionsPersisting()
  {
    return _cgrInitialized && _swipePredictor.arePredictionsPersisting();
  }
  
  /**
   * Reset the recognizer for a new gesture (enhanced)
   */
  @Override
  public void reset()
  {
    // Call parent reset for original functionality
    super.reset();
    
    // New CGR functionality - only reset if not persisting predictions
    if (_cgrInitialized && !_swipePredictor.arePredictionsPersisting())
    {
      _swipePredictor.reset();
    }
  }
  
  /**
   * Check if CGR system is initialized
   */
  public boolean isPredictionSystemInitialized()
  {
    return _cgrInitialized && _swipePredictor.isInitialized();
  }
  
  /**
   * Clear all predictions (force clear including persistent ones)
   */
  public void clearAllPredictions()
  {
    if (_cgrInitialized)
    {
      _swipePredictor.clearPredictions();
    }
  }
}