package juloo.keyboard2;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles swipe predictions asynchronously to prevent UI blocking.
 * Uses a dedicated thread for prediction processing and cancels
 * pending predictions when new input arrives.
 */
public class AsyncPredictionHandler
{
  private static final String TAG = "AsyncPredictionHandler";
  
  // Message types
  private static final int MSG_PREDICT = 1;
  private static final int MSG_CANCEL_PENDING = 2;
  
  // Callback interface for prediction results  
  public interface PredictionCallback
  {
    void onPredictionsReady(List<String> predictions, List<Integer> scores);
    void onPredictionError(String error);
  }
  
  private final HandlerThread _workerThread;
  private final Handler _workerHandler;
  private final Handler _mainHandler;
  private final NeuralSwipeTypingEngine _neuralEngine;
  private final AtomicInteger _requestId;
  private volatile int _currentRequestId;
  
  public AsyncPredictionHandler(NeuralSwipeTypingEngine neuralEngine)
  {
    _neuralEngine = neuralEngine;
    _requestId = new AtomicInteger(0);
    _currentRequestId = 0;
    
    // Create worker thread for predictions
    _workerThread = new HandlerThread("SwipePredictionWorker");
    _workerThread.start();
    
    // Handler for worker thread
    _workerHandler = new Handler(_workerThread.getLooper())
    {
      @Override
      public void handleMessage(Message msg)
      {
        switch (msg.what)
        {
          case MSG_PREDICT:
            handlePredictionRequest(msg);
            break;
          case MSG_CANCEL_PENDING:
            // Just update the current request ID to cancel older requests
            _currentRequestId = msg.arg1;
            break;
        }
      }
    };
    
    // Handler for main thread callbacks
    _mainHandler = new Handler(Looper.getMainLooper());
  }
  
  /**
   * Request predictions for swipe input asynchronously
   */
  public void requestPredictions(SwipeInput input, PredictionCallback callback)
  {
    // Cancel any pending predictions
    int newRequestId = _requestId.incrementAndGet();
    _currentRequestId = newRequestId;
    
    // Send cancel message first
    _workerHandler.obtainMessage(MSG_CANCEL_PENDING, newRequestId, 0).sendToTarget();
    
    // Create prediction request
    PredictionRequest request = new PredictionRequest(input, callback, newRequestId);
    Message msg = _workerHandler.obtainMessage(MSG_PREDICT, request);
    _workerHandler.sendMessage(msg);
    
    Log.d(TAG, "Prediction requested (ID: " + newRequestId + ")");
  }
  
  /**
   * Cancel all pending predictions
   */
  public void cancelPendingPredictions()
  {
    int newRequestId = _requestId.incrementAndGet();
    _currentRequestId = newRequestId;
    _workerHandler.obtainMessage(MSG_CANCEL_PENDING, newRequestId, 0).sendToTarget();
    _workerHandler.removeMessages(MSG_PREDICT);
    
    Log.d(TAG, "All pending predictions cancelled");
  }
  
  /**
   * Handle prediction request on worker thread
   */
  private void handlePredictionRequest(Message msg)
  {
    PredictionRequest request = (PredictionRequest) msg.obj;
    
    // Check if this request has been cancelled
    if (request.requestId != _currentRequestId)
    {
      Log.d(TAG, "Prediction cancelled (ID: " + request.requestId + ")");
      return;
    }
    
    try
    {
      // Start timing
      long startTime = System.currentTimeMillis();
      
      // Perform prediction (this is the potentially blocking operation)
      PredictionResult result = _neuralEngine.predict(request.input);
      
      // Check again if cancelled during prediction
      if (request.requestId != _currentRequestId)
      {
        Log.d(TAG, "Prediction cancelled after processing (ID: " + request.requestId + ")");
        return;
      }
      
      // Extract words and scores directly (neural system uses integers)
      final List<String> words = result.words;
      final List<Integer> scores = result.scores;
      
      long duration = System.currentTimeMillis() - startTime;
      Log.d(TAG, "Prediction completed in " + duration + "ms (ID: " + request.requestId + ")");
      
      // Post results to main thread
      _mainHandler.post(new Runnable()
      {
        @Override
        public void run()
        {
          // Final check before delivering results
          if (request.requestId == _currentRequestId)
          {
            request.callback.onPredictionsReady(words, scores);
          }
        }
      });
    }
    catch (final Exception e)
    {
      Log.e(TAG, "Prediction error", e);
      
      // Post error to main thread
      _mainHandler.post(new Runnable()
      {
        @Override
        public void run()
        {
          if (request.requestId == _currentRequestId)
          {
            request.callback.onPredictionError(e.getMessage());
          }
        }
      });
    }
  }
  
  /**
   * Clean up resources
   */
  public void shutdown()
  {
    cancelPendingPredictions();
    _workerThread.quit();
  }
  
  /**
   * Container for prediction request data
   */
  private static class PredictionRequest
  {
    final SwipeInput input;
    final PredictionCallback callback;
    final int requestId;
    
    PredictionRequest(SwipeInput input, PredictionCallback callback, int requestId)
    {
      this.input = input;
      this.callback = callback;
      this.requestId = requestId;
    }
  }
}