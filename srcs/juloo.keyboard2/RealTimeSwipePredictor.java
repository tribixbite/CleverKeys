package juloo.keyboard2;

import android.content.Context;
import android.graphics.PointF;
import java.util.ArrayList;
import java.util.List;

/**
 * Real-time swipe prediction system using CGR library
 * Integrates with existing keyboard to provide live word suggestions during swipe gestures
 */
public class RealTimeSwipePredictor implements ContinuousSwipeGestureRecognizer.OnGesturePredictionListener
{
  private final ContinuousSwipeGestureRecognizer gestureRecognizer;
  private final WordGestureTemplateGenerator templateGenerator;
  private final List<String> currentPredictions;
  private final List<String> persistentPredictions;
  private boolean isInitialized;
  private boolean predictionsPersisting;
  
  // Callback interface for keyboard integration
  public interface OnSwipePredictionListener
  {
    void onSwipePredictionUpdate(List<String> predictions);
    void onSwipePredictionComplete(List<String> finalPredictions);
    void onSwipePredictionCleared();
  }
  
  private OnSwipePredictionListener predictionListener;
  
  public RealTimeSwipePredictor()
  {
    gestureRecognizer = new ContinuousSwipeGestureRecognizer();
    templateGenerator = new WordGestureTemplateGenerator();
    currentPredictions = new ArrayList<>();
    persistentPredictions = new ArrayList<>();
    isInitialized = false;
    predictionsPersisting = false;
    
    // Set up gesture recognizer callbacks
    gestureRecognizer.setOnGesturePredictionListener(this);
    gestureRecognizer.setMinPointsForPrediction(4); // Start after minimal swipe (lowered for better coverage)
  }
  
  /**
   * Initialize the predictor with dictionary data
   */
  public void initialize(Context context)
  {
    if (isInitialized) return;
    
    android.util.Log.d("RealTimeSwipePredictor", "Initializing swipe predictor...");
    
    // Load dictionary in background thread to avoid blocking UI
    new Thread(() -> {
      try
      {
        long startTime = System.currentTimeMillis();
        android.util.Log.d("RealTimeSwipePredictor", "Starting dictionary load...");
        
        templateGenerator.loadDictionary(context);
        long dictTime = System.currentTimeMillis();
        android.util.Log.d("RealTimeSwipePredictor", "Dictionary loaded in " + (dictTime - startTime) + "ms");
        
        // Generate templates for most frequent words (FULL VOCABULARY - RESTORED)
        List<ContinuousGestureRecognizer.Template> templates = 
          templateGenerator.generateBalancedWordTemplates(3000); // FULL vocabulary restored
        long templateTime = System.currentTimeMillis();
        android.util.Log.d("RealTimeSwipePredictor", "Templates generated in " + (templateTime - dictTime) + "ms");
        
        // CGR parameter loading not needed - using KeyboardSwipeRecognizer for new predictions
        android.util.Log.d("RealTimeSwipePredictor", "Using KeyboardSwipeRecognizer for new algorithm");
        
        // Set templates in gesture recognizer
        long setTime;
        try
        {
          gestureRecognizer.setTemplateSet(templates);
          setTime = System.currentTimeMillis();
          android.util.Log.d("RealTimeSwipePredictor", "Templates set in " + (setTime - templateTime) + "ms");
        }
        catch (Exception templateException)
        {
          android.util.Log.e("RealTimeSwipePredictor", "Failed to set templates: " + templateException.getMessage());
          return; // Don't mark as initialized if template setting failed
        }
        
        isInitialized = true;
        android.util.Log.d("RealTimeSwipePredictor", 
          "Swipe predictor fully initialized with " + templates.size() + " word templates in " + 
          (setTime - startTime) + "ms total");
      }
      catch (Exception e)
      {
        android.util.Log.e("RealTimeSwipePredictor", "Failed to initialize: " + e.getMessage());
      }
    }).start();
  }
  
  /**
   * Set the prediction callback listener
   */
  public void setOnSwipePredictionListener(OnSwipePredictionListener listener)
  {
    this.predictionListener = listener;
  }
  
  /**
   * Handle touch begin event
   */
  public void onTouchBegan(float x, float y)
  {
    if (!isInitialized) return;
    
    // Clear any persistent predictions when starting new gesture
    clearPersistentPredictions();
    
    gestureRecognizer.onTouchBegan(x, y);
  }
  
  /**
   * Handle touch move event
   */
  public void onTouchMoved(float x, float y)
  {
    if (!isInitialized) return;
    
    gestureRecognizer.onTouchMoved(x, y);
  }
  
  /**
   * Handle touch end event
   */
  public void onTouchEnded(float x, float y)
  {
    if (!isInitialized) return;
    
    gestureRecognizer.onTouchEnded(x, y);
  }
  
  /**
   * Check if gesture is currently active
   */
  public boolean isGestureActive()
  {
    return gestureRecognizer.isGestureActive();
  }
  
  /**
   * Get current gesture path for visualization
   */
  public List<PointF> getCurrentGesturePath()
  {
    return gestureRecognizer.getCurrentGesturePoints();
  }
  
  /**
   * Clear predictions (called on space or non-letter key)
   */
  public void clearPredictions()
  {
    currentPredictions.clear();
    // Don't clear persistent predictions UI - just clear internal state
    persistentPredictions.clear();
    predictionsPersisting = false;
    gestureRecognizer.clearResults();
    
    // Don't call clearPersistentPredictions() which triggers UI clearing
    android.util.Log.d("RealTimeSwipePredictor", "Cleared predictions internally (UI kept stable)");
  }
  
  /**
   * Clear persistent predictions
   */
  private void clearPersistentPredictions()
  {
    if (predictionsPersisting)
    {
      persistentPredictions.clear();
      predictionsPersisting = false;
      
      if (predictionListener != null)
      {
        predictionListener.onSwipePredictionCleared();
      }
    }
  }
  
  /**
   * Get current predictions (persistent if available, otherwise current)
   */
  public List<String> getCurrentPredictions()
  {
    if (predictionsPersisting && !persistentPredictions.isEmpty())
    {
      return new ArrayList<>(persistentPredictions);
    }
    return new ArrayList<>(currentPredictions);
  }
  
  /**
   * Check if predictions are currently persisting
   */
  public boolean arePredictionsPersisting()
  {
    return predictionsPersisting;
  }
  
  /**
   * Reset predictor state
   */
  public void reset()
  {
    currentPredictions.clear();
    clearPersistentPredictions();
    gestureRecognizer.reset();
  }
  
  // ContinuousSwipeGestureRecognizer.OnGesturePredictionListener implementation
  
  @Override
  public void onGesturePrediction(List<ContinuousGestureRecognizer.Result> predictions)
  {
    // Convert CGR results to word predictions
    currentPredictions.clear();
    
    android.util.Log.d("RealTimeSwipePredictor", 
      "Real-time predictions received: " + predictions.size() + 
      " results, best prob: " + (predictions.isEmpty() ? "none" : predictions.get(0).prob));
    
    int maxPredictions = Math.min(5, predictions.size()); // Show top 5 predictions
    for (int i = 0; i < maxPredictions; i++)
    {
      ContinuousGestureRecognizer.Result result = predictions.get(i);
      if (result.prob > 0.001) // Lowered threshold for 3000-word vocabulary (was 0.1)
      {
        currentPredictions.add(result.template.id);
        android.util.Log.d("RealTimeSwipePredictor", 
          "Adding real-time prediction: " + result.template.id + " (prob: " + result.prob + ")");
      }
    }
    
    android.util.Log.d("RealTimeSwipePredictor", 
      "Real-time predictions to show: " + currentPredictions.size() + " words");
    
    // Only update UI if we're not in persistent mode and we have predictions
    if (!predictionsPersisting && !currentPredictions.isEmpty())
    {
      if (predictionListener != null)
      {
        predictionListener.onSwipePredictionUpdate(new ArrayList<>(currentPredictions));
      }
    }
  }
  
  @Override
  public void onGestureComplete(List<ContinuousGestureRecognizer.Result> finalPredictions)
  {
    // Convert final results to persistent predictions
    persistentPredictions.clear();
    
    android.util.Log.d("RealTimeSwipePredictor", 
      "Final predictions received: " + finalPredictions.size() + 
      " results, best prob: " + (finalPredictions.isEmpty() ? "none" : finalPredictions.get(0).prob));
    
    int maxPredictions = Math.min(5, finalPredictions.size());
    for (int i = 0; i < maxPredictions; i++)
    {
      ContinuousGestureRecognizer.Result result = finalPredictions.get(i);
      if (result.prob > 0.0005) // Lowered threshold for final predictions with large vocabulary (was 0.05)
      {
        persistentPredictions.add(result.template.id);
        android.util.Log.d("RealTimeSwipePredictor", 
          "Adding final prediction: " + result.template.id + " (prob: " + result.prob + ")");
      }
    }
    
    android.util.Log.d("RealTimeSwipePredictor", 
      "Final predictions to persist: " + persistentPredictions.size() + " words");
    
    // Enable persistence if we have good predictions
    if (!persistentPredictions.isEmpty())
    {
      predictionsPersisting = true;
      
      if (predictionListener != null)
      {
        predictionListener.onSwipePredictionComplete(new ArrayList<>(persistentPredictions));
      }
      
      android.util.Log.d("RealTimeSwipePredictor", 
        "Persisting " + persistentPredictions.size() + " predictions: " + persistentPredictions);
    }
    else
    {
      // No good predictions, clear everything
      android.util.Log.d("RealTimeSwipePredictor", "No final predictions met threshold - clearing");
      if (predictionListener != null)
      {
        predictionListener.onSwipePredictionCleared();
      }
    }
  }
  
  @Override
  public void onGestureCleared()
  {
    currentPredictions.clear();
    // Don't clear persistent predictions here - they should persist until space/punctuation
  }
  
  /**
   * Check if initialized
   */
  public boolean isInitialized()
  {
    return isInitialized;
  }
  
  /**
   * Get dictionary size (for debugging)
   */
  public int getDictionarySize()
  {
    return templateGenerator.getDictionarySize();
  }
  
  /**
   * Handle key press (space clears predictions but keeps UI bar visible)
   */
  public void onKeyPressed(KeyValue key)
  {
    if (key == null) return;
    
    // Clear predictions on space or non-letter keys (but keep UI bar visible)
    if (key.getKind() == KeyValue.Kind.Char)
    {
      char c = key.getChar();
      if (c == ' ' || !Character.isLetter(c))
      {
        clearPredictionsKeepBarVisible();
      }
    }
    else if (key.getKind() != KeyValue.Kind.Char)
    {
      // Any non-character key (like backspace, enter, etc.)
      clearPredictionsKeepBarVisible();
    }
  }
  
  /**
   * Clear predictions but keep suggestion bar visible (no UI disappearing)
   */
  private void clearPredictionsKeepBarVisible()
  {
    currentPredictions.clear();
    persistentPredictions.clear();
    predictionsPersisting = false;
    gestureRecognizer.clearResults();
    
    // Don't call predictionListener.onSwipePredictionCleared() - that hides the UI
    // Instead, show empty predictions to keep bar visible
    if (predictionListener != null)
    {
      predictionListener.onSwipePredictionUpdate(new ArrayList<>());
    }
    
    android.util.Log.d("RealTimeSwipePredictor", "Cleared predictions (UI bar kept visible)");
  }
  
  /**
   * Select a prediction (called when user taps on suggestion)
   */
  public void selectPrediction(String word)
  {
    android.util.Log.d("RealTimeSwipePredictor", "Selected prediction: " + word);
    clearPredictions();
  }
}