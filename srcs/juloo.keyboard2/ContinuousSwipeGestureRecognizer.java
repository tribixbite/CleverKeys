package juloo.keyboard2;

import android.graphics.PointF;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Continuous Swipe Gesture Recognizer Integration
 * 
 * Based on Main.lua usage patterns, this class integrates the CGR library
 * with Android touch handling for swipe typing recognition.
 * 
 * PERFORMANCE OPTIMIZED: Uses background thread and throttling to prevent UI lag
 */
public class ContinuousSwipeGestureRecognizer
{
  private final ContinuousGestureRecognizer cgr;
  private final List<ContinuousGestureRecognizer.Point> gesturePointsList;
  private final List<ContinuousGestureRecognizer.Result> results;
  private boolean newTouch;
  private boolean gestureActive;
  private int minPointsForPrediction = 4; // Start predictions after 4 points (lowered for short swipes)
  
  // Performance optimization fields
  private HandlerThread backgroundThread;
  private Handler backgroundHandler;
  private Handler mainHandler;
  private final AtomicBoolean recognitionInProgress = new AtomicBoolean(false);
  private long lastPredictionTime = 0;
  private static final long PREDICTION_THROTTLE_MS = 100; // Reasonable prediction frequency
  
  // Callback interface for real-time predictions
  public interface OnGesturePredictionListener
  {
    void onGesturePrediction(List<ContinuousGestureRecognizer.Result> predictions);
    void onGestureComplete(List<ContinuousGestureRecognizer.Result> finalPredictions);
    void onGestureCleared();
  }
  
  private OnGesturePredictionListener predictionListener;
  
  public ContinuousSwipeGestureRecognizer()
  {
    cgr = new ContinuousGestureRecognizer();
    gesturePointsList = new ArrayList<>();
    results = new ArrayList<>(); // Pre-allocated results list like in Lua
    gestureActive = false;
    newTouch = false;
    
    // Initialize background processing
    backgroundThread = new HandlerThread("CGR-Recognition");
    backgroundThread.start();
    backgroundHandler = new Handler(backgroundThread.getLooper());
    mainHandler = new Handler(Looper.getMainLooper());
    
    // Don't initialize with directional templates - they cause issues with FIXED_POINT_COUNT
    // Templates will be set later when word templates are loaded
    // cgr.setTemplateSet(ContinuousGestureRecognizer.createDirectionalTemplates());
  }
  
  /**
   * Set the prediction listener for real-time callbacks
   */
  public void setOnGesturePredictionListener(OnGesturePredictionListener listener)
  {
    this.predictionListener = listener;
  }
  
  /**
   * Set template set for recognition
   */
  public void setTemplateSet(List<ContinuousGestureRecognizer.Template> templates)
  {
    cgr.setTemplateSet(templates);
  }
  
  /**
   * Handle touch begin event (equivalent to CurrentTouch.state == BEGAN)
   */
  public void onTouchBegan(float x, float y)
  {
    clearPoints(gesturePointsList);
    gesturePointsList.add(new ContinuousGestureRecognizer.Point(x, y));
    newTouch = true;
    gestureActive = true;
    
    // Clear any existing predictions
    if (predictionListener != null)
    {
      predictionListener.onGestureCleared();
    }
  }
  
  /**
   * Handle touch move event (equivalent to CurrentTouch.state == MOVING)
   * OPTIMIZED: Uses throttling and background processing to prevent UI lag
   */
  public void onTouchMoved(float x, float y)
  {
    if (!gestureActive) return;
    
    gesturePointsList.add(new ContinuousGestureRecognizer.Point(x, y));
    
    // Throttle predictions to reasonable frequency (record all events but predict sparingly)
    long now = System.currentTimeMillis();
    
    // DISABLED: Real-time predictions during swipe (causes performance issues)
    // Only predict at swipe completion to prevent memory/performance overhead
    
    // COMMENTED OUT FOR PERFORMANCE:
    // boolean shouldPredict = gesturePointsList.size() >= minPointsForPrediction &&
    //     now - lastPredictionTime > PREDICTION_THROTTLE_MS;
    //     
    // if (shouldPredict)
    // {
    //   lastPredictionTime = now;
    //   
    //   // Create copy of points for background processing
    //   final List<ContinuousGestureRecognizer.Point> pointsCopy = 
    //     new ArrayList<>(gesturePointsList);
    //   
    //   // Run recognition on background thread
    //   backgroundHandler.post(() -> {
    //     try
    //     {
    //       List<ContinuousGestureRecognizer.Result> currentResults = cgr.recognize(pointsCopy);
    //       
    //       // Post results back to main thread
    //       if (currentResults != null && !currentResults.isEmpty() && predictionListener != null)
    //       {
    //         mainHandler.post(() -> {
    //           predictionListener.onGesturePrediction(currentResults);
    //         });
    //       }
    //     }
    //     catch (Exception e)
    //     {
    //       android.util.Log.w("ContinuousSwipeGestureRecognizer", "Recognition error during move: " + e.getMessage());
    //     }
    //     finally
    //     {
    //       // Recognition complete - no blocking state to clear
    //     }
    //   });
    // }
    
    android.util.Log.d("ContinuousSwipeGestureRecognizer", "Touch move recorded (real-time prediction disabled for performance)");
  }
  
  /**
   * Handle touch end event (equivalent to CurrentTouch.state == ENDED)
   * OPTIMIZED: Uses background processing for final recognition
   */
  public void onTouchEnded(float x, float y)
  {
    if (!gestureActive) return;
    
    gesturePointsList.add(new ContinuousGestureRecognizer.Point(x, y));
    
    if (newTouch)
    {
      newTouch = false;
      
      // ALWAYS perform final recognition on background thread - guarantee prediction
      if (gesturePointsList.size() >= 2) // Need at least 2 points for recognition
      {
        final List<ContinuousGestureRecognizer.Point> finalPointsCopy = 
          new ArrayList<>(gesturePointsList);
          
        // Clear any pending background tasks to prioritize final results
        backgroundHandler.removeCallbacksAndMessages(null);
          
        backgroundHandler.post(() -> {
          try
          {
            List<ContinuousGestureRecognizer.Result> finalResults = cgr.recognize(finalPointsCopy);
            
            // ALWAYS notify with results (even if empty) to guarantee callback
            mainHandler.post(() -> {
              // Store results for persistence
              results.clear();
              if (finalResults != null)
              {
                results.addAll(finalResults);
              }
              
              // ALWAYS notify listener - guarantee prediction shown after swipe
              if (predictionListener != null)
              {
                if (finalResults != null && !finalResults.isEmpty())
                {
                  predictionListener.onGestureComplete(finalResults);
                  android.util.Log.d("ContinuousSwipeGestureRecognizer", "Final prediction delivered: " + finalResults.size() + " results");
                }
                else
                {
                  // Even if no good results, still notify (may show fallback)
                  predictionListener.onGestureComplete(new ArrayList<>());
                  android.util.Log.d("ContinuousSwipeGestureRecognizer", "No final predictions available");
                }
              }
              
              // Debug logging (like CGR_printResults in Lua)
              if (finalResults != null && !finalResults.isEmpty())
              {
                printResults(finalResults);
              }
            });
          }
          catch (Exception e)
          {
            android.util.Log.e("ContinuousSwipeGestureRecognizer", "Recognition error on end: " + e.getMessage());
            // Still notify listener even on error to guarantee callback
            mainHandler.post(() -> {
              if (predictionListener != null)
              {
                predictionListener.onGestureComplete(new ArrayList<>());
              }
            });
          }
        });
      }
    }
    
    gestureActive = false;
  }
  
  /**
   * Clear gesture points (equivalent to clearPoints in Lua)
   */
  private void clearPoints(List<ContinuousGestureRecognizer.Point> points)
  {
    points.clear();
  }
  
  /**
   * Check if gesture is currently active
   */
  public boolean isGestureActive()
  {
    return gestureActive;
  }
  
  /**
   * Get current gesture points for visualization
   */
  public List<PointF> getCurrentGesturePoints()
  {
    List<PointF> androidPoints = new ArrayList<>();
    for (ContinuousGestureRecognizer.Point pt : gesturePointsList)
    {
      androidPoints.add(new PointF((float)pt.x, (float)pt.y));
    }
    return androidPoints;
  }
  
  /**
   * Get the last recognition results (for persistence)
   */
  public List<ContinuousGestureRecognizer.Result> getLastResults()
  {
    return new ArrayList<>(results);
  }
  
  /**
   * Get the best prediction from last results
   */
  public ContinuousGestureRecognizer.Result getBestPrediction()
  {
    if (results.isEmpty()) return null;
    return results.get(0); // Results are sorted by probability
  }
  
  /**
   * Clear stored results (called on space/punctuation)
   */
  public void clearResults()
  {
    results.clear();
    if (predictionListener != null)
    {
      predictionListener.onGestureCleared();
    }
  }
  
  /**
   * Set minimum points required before starting predictions
   */
  public void setMinPointsForPrediction(int minPoints)
  {
    this.minPointsForPrediction = Math.max(2, minPoints);
  }
  
  /**
   * Print results for debugging (equivalent to CGR_printResults in Lua)
   */
  private void printResults(List<ContinuousGestureRecognizer.Result> resultList)
  {
    for (ContinuousGestureRecognizer.Result result : resultList)
    {
      android.util.Log.d("ContinuousSwipeGestureRecognizer", 
        "Result: " + result.template.id + " : " + result.prob);
    }
  }
  
  /**
   * Check results quality (equivalent to CGR_checkResults in Lua)
   * Returns true if the best result is confident enough
   */
  public boolean isResultConfident()
  {
    if (results.size() < 2) return false;
    
    ContinuousGestureRecognizer.Result r1 = results.get(0);
    ContinuousGestureRecognizer.Result r2 = results.get(1);
    
    double similarity = (r2.prob / r1.prob) * r2.prob;
    
    if (r1.prob > 0.7)
    {
      if (similarity < 95)
      {
        android.util.Log.d("ContinuousSwipeGestureRecognizer", 
          "CHECK: Using: " + r1.template.id + " : " + r1.prob);
        return true;
      }
      else
      {
        android.util.Log.d("ContinuousSwipeGestureRecognizer", 
          "CHECK: First two probabilities too close to call");
        return false;
      }
    }
    else
    {
      android.util.Log.d("ContinuousSwipeGestureRecognizer", 
        "CHECK: Probability not high enough (<0.7), discarding user input");
      return false;
    }
  }
  
  /**
   * Reset the recognizer state
   */
  public void reset()
  {
    clearPoints(gesturePointsList);
    results.clear();
    gestureActive = false;
    newTouch = false;
    lastPredictionTime = 0;
    
    if (predictionListener != null)
    {
      predictionListener.onGestureCleared();
    }
  }
  
  /**
   * Clean up background thread (call when done with recognizer)
   */
  public void cleanup()
  {
    if (backgroundThread != null)
    {
      backgroundThread.quitSafely();
      try
      {
        backgroundThread.join();
      }
      catch (InterruptedException e)
      {
        Thread.currentThread().interrupt();
      }
      backgroundThread = null;
      backgroundHandler = null;
    }
  }
}