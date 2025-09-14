package juloo.keyboard2;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.content.ClipData;
import android.content.ClipboardManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import juloo.keyboard2.ml.SwipeMLData;
import juloo.keyboard2.ml.SwipeMLDataStore;

/**
 * Pure neural swipe calibration with ONNX transformer prediction
 * Matches web demo trace collection and animation styling
 */
public class SwipeCalibrationActivity extends Activity
{
  private static final String TAG = "NeuralCalibration";
  
  // Calibration settings
  private static final int WORDS_PER_SESSION = 20;
  
  
  // Full vocabulary for random word selection
  private List<String> fullVocabulary = null;
  private Random random = new Random();
  
  // QWERTY layout
  private static final String[][] KEYBOARD_LAYOUT = {
    {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
    {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
    {"z", "x", "c", "v", "b", "n", "m"}
  };
  
  // UI Components
  private TextView _instructionText;
  private TextView _currentWordText;
  private TextView _progressText;
  private TextView _benchmarkText;
  private ProgressBar _progressBar;
  private NeuralKeyboardView _keyboardView;
  private Button _nextButton;
  private Button _skipButton;
  private Button _exportButton;
  private Button _benchmarkButton;
  
  // Results logging
  private TextView _resultsTextBox;
  private Button _copyResultsButton;
  private StringBuilder _resultsLog = new StringBuilder();
  
  // Swipe data collection (needed by keyboard view)
  private List<PointF> _currentSwipePoints = new ArrayList<>();
  private List<Long> _currentSwipeTimestamps = new ArrayList<>();
  private long _swipeStartTime;
  
  // Neural prediction
  private NeuralSwipeTypingEngine _neuralEngine;
  private Config _config;
  
  // Calibration state
  private int _currentIndex = 0;
  private String _currentWord;
  private List<String> _sessionWords;
  private SwipeMLDataStore _mlDataStore;
  private int _screenWidth;
  private int _screenHeight;
  private int _keyboardHeight;
  
  // Performance tracking  
  private List<Long> _predictionTimes = new ArrayList<>();
  private int _correctPredictions = 0;
  private int _totalPredictions = 0;
  
  
  // User configuration (needed for keyboard layout)
  private float _characterSize = 1.15f;
  private float _labelTextSize = 0.33f;
  private float _keyVerticalMargin = 0.015f;
  private float _keyHorizontalMargin = 0.02f;
  
  @Override
  protected void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    
    Log.d(TAG, "=== NEURAL CALIBRATION ACTIVITY STARTED ===");
    
    // Initialize ML data store
    _mlDataStore = SwipeMLDataStore.getInstance(this);
    
    // Initialize neural prediction engine
    SharedPreferences prefs = DirectBootAwarePreferences.get_shared_preferences(this);
    Config.initGlobalConfig(prefs, getResources(), null, false);
    _config = Config.globalConfig();
    _neuralEngine = new NeuralSwipeTypingEngine(this, _config);
    // Set up logging callback for neural engine
    _neuralEngine.setDebugLogger(this::logToResults);
    
    try
    {
      _neuralEngine.initialize();
      Log.d(TAG, "Neural engine initialized successfully");
      logToResults("✅ Neural engine initialized successfully");
    }
    catch (Exception e)
    {
      Log.e(TAG, "Failed to initialize neural engine", e);
      logToResults("❌ Neural engine initialization failed: " + e.getMessage());
      showErrorDialog("Neural models failed to load. Error: " + e.getMessage());
      return;
    }
    
    // Get screen dimensions
    android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
    getWindowManager().getDefaultDisplay().getMetrics(metrics);
    _screenWidth = metrics.widthPixels;
    _screenHeight = metrics.heightPixels;
    
    // Load user's keyboard height setting properly
    FoldStateTracker foldTracker = new FoldStateTracker(this);
    boolean foldableUnfolded = foldTracker.isUnfolded();
    
    // Get keyboard height percentage from user settings
    boolean isLandscape = getResources().getConfiguration().orientation == 
                          android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    
    int keyboardHeightPref;
    if (isLandscape)
    {
      String key = foldableUnfolded ? "keyboard_height_landscape_unfolded" : "keyboard_height_landscape";
      keyboardHeightPref = prefs.getInt(key, 50);
      Log.d(TAG, "Reading landscape height from key '" + key + "': " + keyboardHeightPref);
    }
    else
    {
      String key = foldableUnfolded ? "keyboard_height_unfolded" : "keyboard_height";
      keyboardHeightPref = prefs.getInt(key, 35);
      Log.d(TAG, "Reading portrait height from key '" + key + "': " + keyboardHeightPref);
    }
    
    // Calculate keyboard height using user setting
    float keyboardHeightPercent = keyboardHeightPref / 100.0f;
    _keyboardHeight = (int)(_screenHeight * keyboardHeightPercent);
    Log.d(TAG, "Calculated keyboard height: " + _keyboardHeight + " pixels (" + keyboardHeightPref + "% of " + _screenHeight + ")");
    
    // Load user's text and margin settings  
    _characterSize = prefs.getFloat("character_size", 1.15f);
    _labelTextSize = 0.33f;
    _keyVerticalMargin = prefs.getFloat("key_vertical_margin", 1.5f) / 100;
    _keyHorizontalMargin = prefs.getFloat("key_horizontal_margin", 2.0f) / 100;
    
    // OPTIMIZATION: Prepare random session words from full vocabulary
    _sessionWords = prepareRandomSessionWords();
    
    setupUI();
    setupKeyboard();
    showNextWord();
  }
  
  /**
   * OPTIMIZATION: Load full vocabulary and select random test words
   * Replaces fixed word list with truly random sampling from available dictionaries
   */
  private List<String> prepareRandomSessionWords()
  {
    // Load full vocabulary if not already loaded
    if (fullVocabulary == null)
    {
      loadFullVocabulary();
    }
    
    List<String> sessionWords = new ArrayList<>();
    
    if (fullVocabulary != null && fullVocabulary.size() > WORDS_PER_SESSION)
    {
      // Select completely random words from full vocabulary
      Set<String> selectedWords = new HashSet<>(); // Prevent duplicates
      
      while (selectedWords.size() < WORDS_PER_SESSION)
      {
        int randomIndex = random.nextInt(fullVocabulary.size());
        String word = fullVocabulary.get(randomIndex);
        selectedWords.add(word); // No filtering - use any word from dictionary
      }
      
      sessionWords.addAll(selectedWords);
      Log.d(TAG, "Selected " + sessionWords.size() + " completely random words from " + fullVocabulary.size() + " total vocabulary");
    }
    else
    {
      Log.e(TAG, "No vocabulary loaded - cannot generate session words");
      return new ArrayList<>(); // Return empty list if no vocabulary
    }
    
    return sessionWords;
  }
  
  /**
   * Load full vocabulary from dictionary assets
   * Loads from both en.txt and en_enhanced.txt for maximum word variety
   */
  private void loadFullVocabulary()
  {
    try
    {
      Log.d(TAG, "Loading full vocabulary from multiple dictionaries for random test words...");
      
      fullVocabulary = new ArrayList<>();
      Set<String> uniqueWords = new HashSet<>(); // Prevent duplicates across files
      
      // Load from both dictionary files for maximum variety
      String[] dictFiles = {"dictionaries/en.txt", "dictionaries/en_enhanced.txt"};
      
      for (String dictFile : dictFiles)
      {
        try
        {
          InputStream inputStream = getAssets().open(dictFile);
          BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
          
          String line;
          int fileWordCount = 0;
          
          while ((line = reader.readLine()) != null)
          {
            line = line.trim().toLowerCase();
            if (!line.isEmpty() && uniqueWords.add(line)) // Only add if not already present
            {
              fullVocabulary.add(line);
              fileWordCount++;
            }
          }
          
          reader.close();
          Log.d(TAG, "Loaded " + fileWordCount + " words from " + dictFile);
        }
        catch (Exception e)
        {
          Log.w(TAG, "Failed to load " + dictFile + ": " + e.getMessage());
        }
      }
      
      Log.d(TAG, "Total loaded: " + fullVocabulary.size() + " unique words for random selection");
    }
    catch (Exception e)
    {
      Log.e(TAG, "Failed to load full vocabulary", e);
      throw new RuntimeException("Vocabulary loading failed - no fallback allowed", e);
    }
  }
  
  
  private void setupUI()
  {
    // Main RelativeLayout like original
    android.widget.RelativeLayout mainLayout = new android.widget.RelativeLayout(this);
    mainLayout.setBackgroundColor(Color.BLACK);
    
    // Create top content layout
    LinearLayout topLayout = new LinearLayout(this);
    topLayout.setOrientation(LinearLayout.VERTICAL);
    topLayout.setPadding(40, 40, 40, 20);
    android.widget.RelativeLayout.LayoutParams topParams = new android.widget.RelativeLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    topParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP);
    topLayout.setLayoutParams(topParams);
    
    // Title
    TextView title = new TextView(this);
    title.setText("🧠 Neural Swipe Calibration");
    title.setTextSize(24);
    title.setTextColor(0xFF00d4ff); // Neon blue
    title.setPadding(0, 0, 0, 20);
    topLayout.addView(title);
    
    // Instructions
    _instructionText = new TextView(this);
    _instructionText.setText("Swipe the word shown below - auto-advances on completion");
    _instructionText.setTextColor(Color.GRAY);
    _instructionText.setPadding(0, 0, 0, 10);
    topLayout.addView(_instructionText);
    
    // Current word display
    _currentWordText = new TextView(this);
    _currentWordText.setTextSize(32);
    _currentWordText.setTextColor(Color.CYAN);
    _currentWordText.setPadding(0, 20, 0, 20);
    topLayout.addView(_currentWordText);
    
    // Progress
    _progressText = new TextView(this);
    _progressText.setTextColor(Color.WHITE);
    topLayout.addView(_progressText);
    
    _progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    _progressBar.setMax(WORDS_PER_SESSION);
    topLayout.addView(_progressBar);
    
    // Benchmark display
    _benchmarkText = new TextView(this);
    _benchmarkText.setTextColor(0xFF00d4ff);
    _benchmarkText.setTextSize(14);
    _benchmarkText.setPadding(0, 10, 0, 10);
    topLayout.addView(_benchmarkText);
    
    // Playground button
    Button playgroundButton = new Button(this);
    playgroundButton.setText("🎮 Neural Playground");
    playgroundButton.setTextSize(14);
    playgroundButton.setOnClickListener(v -> showNeuralPlayground());
    playgroundButton.setBackgroundColor(0xFF4CAF50);
    playgroundButton.setTextColor(Color.WHITE);
    playgroundButton.setPadding(8, 8, 8, 8);
    topLayout.addView(playgroundButton);
    
    // Results textbox with copy button
    LinearLayout resultsHeaderLayout = new LinearLayout(this);
    resultsHeaderLayout.setOrientation(LinearLayout.HORIZONTAL);
    resultsHeaderLayout.setPadding(16, 16, 16, 8);
    
    TextView resultsLabel = new TextView(this);
    resultsLabel.setText("🔍 Neural Results Log:");
    resultsLabel.setTextSize(14);
    resultsLabel.setTextColor(0xFF00d4ff);
    resultsLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    resultsLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
    resultsHeaderLayout.addView(resultsLabel);
    
    _copyResultsButton = new Button(this);
    _copyResultsButton.setText("📋");
    _copyResultsButton.setTextSize(16);
    _copyResultsButton.setOnClickListener(v -> copyResultsToClipboard());
    _copyResultsButton.setBackgroundColor(0xFF4CAF50);
    _copyResultsButton.setTextColor(Color.WHITE);
    _copyResultsButton.setPadding(8, 8, 8, 8);
    resultsHeaderLayout.addView(_copyResultsButton);
    
    topLayout.addView(resultsHeaderLayout);
    
    // Results textbox
    _resultsTextBox = new TextView(this);
    _resultsTextBox.setText("Neural system starting...\n");
    _resultsTextBox.setTextSize(10);
    _resultsTextBox.setPadding(12, 12, 12, 12);
    _resultsTextBox.setTextColor(Color.WHITE);
    _resultsTextBox.setBackgroundColor(0xFF1A1A1A);
    _resultsTextBox.setTypeface(android.graphics.Typeface.MONOSPACE);
    _resultsTextBox.setMaxLines(8);
    _resultsTextBox.setVerticalScrollBarEnabled(true);
    _resultsTextBox.setMovementMethod(new android.text.method.ScrollingMovementMethod());
    topLayout.addView(_resultsTextBox);
    
    // Control buttons
    LinearLayout buttonLayout = new LinearLayout(this);
    buttonLayout.setOrientation(LinearLayout.HORIZONTAL);
    buttonLayout.setPadding(16, 16, 16, 8);
    buttonLayout.setGravity(android.view.Gravity.CENTER);
    
    _skipButton = new Button(this);
    _skipButton.setText("Skip Word");
    _skipButton.setOnClickListener(v -> skipWord());
    _skipButton.setBackgroundColor(0xFFFF5722);
    _skipButton.setTextColor(Color.WHITE);
    buttonLayout.addView(_skipButton);
    
    _nextButton = new Button(this);
    _nextButton.setText("Next Word");  
    _nextButton.setOnClickListener(v -> nextWord());
    _nextButton.setBackgroundColor(0xFF4CAF50);
    _nextButton.setTextColor(Color.WHITE);
    buttonLayout.addView(_nextButton);
    
    _exportButton = new Button(this);
    _exportButton.setText("Export Data");
    _exportButton.setOnClickListener(v -> exportTrainingData());
    _exportButton.setBackgroundColor(0xFF2196F3);
    _exportButton.setTextColor(Color.WHITE);
    buttonLayout.addView(_exportButton);
    
    Button testTensorsButton = new Button(this);
    testTensorsButton.setText("Test Tensors");
    testTensorsButton.setOnClickListener(v -> testTensorCreation());
    testTensorsButton.setBackgroundColor(0xFFE91E63);
    testTensorsButton.setTextColor(Color.WHITE);
    buttonLayout.addView(testTensorsButton);
    
    topLayout.addView(buttonLayout);
    mainLayout.addView(topLayout);
    
    setContentView(mainLayout);
  }
  
  private Button createNeonButton(String text, int color)
  {
    Button button = new Button(this);
    button.setText(text);
    button.setTextColor(0xFFFFFFFF);
    button.setBackgroundColor(color);
    button.setTextSize(14);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
      0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
    params.setMargins(5, 5, 5, 5);
    button.setLayoutParams(params);
    return button;
  }
  
  private void setupKeyboard()
  {
    _keyboardView = new NeuralKeyboardView(this);
    
    // Position keyboard at bottom using RelativeLayout params like original
    android.widget.RelativeLayout.LayoutParams keyboardParams = new android.widget.RelativeLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, _keyboardHeight);
    keyboardParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM);
    _keyboardView.setLayoutParams(keyboardParams);
    
    // Add keyboard to main RelativeLayout
    ViewGroup mainLayout = (ViewGroup) findViewById(android.R.id.content);
    ((android.widget.RelativeLayout) mainLayout.getChildAt(0)).addView(_keyboardView);
    
    // Set keyboard dimensions for neural engine
    _neuralEngine.setKeyboardDimensions(_screenWidth, _keyboardHeight);
  }
  
  private void showNextWord()
  {
    if (_currentIndex >= _sessionWords.size())
    {
      showCompletionMessage();
      return;
    }
    
    _currentWord = _sessionWords.get(_currentIndex);
    _currentWordText.setText(_currentWord.toUpperCase());
    _progressText.setText(String.format("Word %d of %d", _currentIndex + 1, WORDS_PER_SESSION));
    _progressBar.setProgress(_currentIndex);
    
    updateBenchmarkDisplay();
    
    Log.d(TAG, "Showing word: " + _currentWord);
  }
  
  private void updateBenchmarkDisplay()
  {
    if (_totalPredictions > 0)
    {
      float accuracy = (_correctPredictions * 100.0f) / _totalPredictions;
      long avgTime = _predictionTimes.stream().mapToLong(Long::longValue).sum() / _predictionTimes.size();
      
      _benchmarkText.setText(String.format(
        "📊 Neural Performance: %.1f%% accuracy, %.1fms avg prediction time",
        accuracy, avgTime / 1000000.0f)); // Convert nanoseconds to milliseconds
    }
    else
    {
      _benchmarkText.setText("📊 Neural Performance: No data yet");
    }
  }
  
  private void nextWord()
  {
    _currentIndex++;
    showNextWord();
  }
  
  private void skipWord()
  {
    Log.d(TAG, "Skipped word: " + _currentWord);
    _currentIndex++;
    showNextWord();
  }
  
  private void exportTrainingData()
  {
    List<SwipeMLData> allData = _mlDataStore.loadDataBySource("neural_calibration");
    
    // Export in format matching web demo
    StringBuilder export = new StringBuilder();
    export.append("[\n");
    
    for (int i = 0; i < allData.size(); i++)
    {
      SwipeMLData data = allData.get(i);
      export.append("  {\n");
      export.append("    \"word\": \"").append(data.getTargetWord()).append("\",\n");
      export.append("    \"trajectory\": [\n");
      
      for (SwipeMLData.TracePoint point : data.getTracePoints())
      {
        export.append(String.format("      {\"x\": %.4f, \"y\": %.4f, \"t\": %d},\n",
          point.x, point.y, point.tDeltaMs));
      }
      
      export.append("    ],\n");
      export.append("    \"keys\": ").append(data.getRegisteredKeys()).append("\n");
      export.append("  }").append(i < allData.size() - 1 ? "," : "").append("\n");
    }
    
    export.append("]\n");
    
    // Copy to clipboard
    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
    ClipData clip = ClipData.newPlainText("Neural Training Data", export.toString());
    clipboard.setPrimaryClip(clip);
    
    Toast.makeText(this, "Training data exported to clipboard", Toast.LENGTH_LONG).show();
  }
  
  private void runBenchmark()
  {
    logToResults("🧪 Testing minimal neural prediction pipeline...");
    
    try {
      // Create minimal test swipe 
      List<PointF> testPoints = new ArrayList<>();
      List<Long> testTimestamps = new ArrayList<>();
      long baseTime = System.currentTimeMillis();
      
      // Simple 5-point swipe
      for (int i = 0; i < 5; i++) {
        testPoints.add(new PointF(i * 50f, 200f)); // Horizontal line
        testTimestamps.add(baseTime + i * 100);
      }
      
      SwipeInput testSwipe = new SwipeInput(testPoints, testTimestamps, new ArrayList<>());
      logToResults("Test swipe created with " + testPoints.size() + " points");
      
      // Test neural prediction
      PredictionResult result = _neuralEngine.predict(testSwipe);
      
      logToResults("✅ Neural prediction test successful!");
      logToResults("   Predictions: " + result.words.size());
      
      for (int i = 0; i < Math.min(3, result.words.size()); i++) {
        logToResults("   " + (i + 1) + ". " + result.words.get(i) + " (score: " + result.scores.get(i) + ")");
      }
      
    } catch (Exception e) {
      logToResults("💥 Neural prediction test failed: " + e.getMessage());
    }
  }
  
  private void testTensorCreation()
  {
    logToResults("🔧 Testing tensor creation directly...");
    
    try {
      ai.onnxruntime.OrtEnvironment env = ai.onnxruntime.OrtEnvironment.getEnvironment();
      
      // Test 1D boolean array
      boolean[] mask1D = new boolean[100];
      for (int i = 50; i < 100; i++) mask1D[i] = true;
      
      ai.onnxruntime.OnnxTensor tensor1D = ai.onnxruntime.OnnxTensor.createTensor(env, mask1D);
      logToResults("1D boolean[100] creates shape: " + java.util.Arrays.toString(tensor1D.getInfo().getShape()));
      tensor1D.close();
      
      // Test 2D boolean array
      boolean[][] mask2D = new boolean[1][100];
      for (int i = 50; i < 100; i++) mask2D[0][i] = true;
      
      ai.onnxruntime.OnnxTensor tensor2D = ai.onnxruntime.OnnxTensor.createTensor(env, mask2D);
      logToResults("2D boolean[1][100] creates shape: " + java.util.Arrays.toString(tensor2D.getInfo().getShape()));
      tensor2D.close();
      
      // Test with explicit shape parameter
      boolean[] mask1DExplicit = new boolean[100];
      for (int i = 50; i < 100; i++) mask1DExplicit[i] = true;
      
      // Test current approach
      logToResults("Current approach uses boolean[1][100] - should create [1, 100] tensor");
      
      // Test if the issue is with our tensor creation specifically
      try {
        boolean[][] testMask = new boolean[1][10];
        testMask[0][5] = true;
        ai.onnxruntime.OnnxTensor testTensor = ai.onnxruntime.OnnxTensor.createTensor(env, testMask);
        logToResults("Small test boolean[1][10] shape: " + java.util.Arrays.toString(testTensor.getInfo().getShape()));
        testTensor.close();
      } catch (Exception e) {
        logToResults("❌ Small test failed: " + e.getMessage());
      }
      
      logToResults("✅ Tensor creation tests complete");
      
    } catch (Exception e) {
      logToResults("💥 Tensor creation test failed: " + e.getMessage());
      Log.e(TAG, "Tensor test failed", e);
    }
  }
  
  private void showCompletionMessage()
  {
    _instructionText.setText("🎉 Neural Calibration Complete!");
    _currentWordText.setText("✓");
    _currentWordText.setTextColor(0xFF4CAF50);
    _progressBar.setProgress(_progressBar.getMax());
    
    updateBenchmarkDisplay();
    
    Log.d(TAG, "=== NEURAL CALIBRATION COMPLETE ===");
  }
  
  private void showNeuralPlayground()
  {
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle("🧠 Neural Parameters Playground");
    
    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(40, 20, 40, 20);
    
    // Beam width control
    addSliderControl(layout, "Beam Width", _config.neural_beam_width, 1, 16, 
      value -> _config.neural_beam_width = value);
    
    // Max length control
    addSliderControl(layout, "Max Length", _config.neural_max_length, 10, 50,
      value -> _config.neural_max_length = value);
    
    // Confidence threshold control  
    addFloatSliderControl(layout, "Confidence Threshold", _config.neural_confidence_threshold, 0.0f, 1.0f,
      value -> _config.neural_confidence_threshold = (float)value);
    
    builder.setView(layout);
    builder.setPositiveButton("Apply", (dialog, which) -> {
      // Save settings to SharedPreferences for persistence
      SharedPreferences prefs = DirectBootAwarePreferences.get_shared_preferences(this);
      SharedPreferences.Editor editor = prefs.edit();
      editor.putInt("neural_beam_width", _config.neural_beam_width);
      editor.putInt("neural_max_length", _config.neural_max_length);
      editor.putFloat("neural_confidence_threshold", _config.neural_confidence_threshold);
      editor.apply();
      
      _neuralEngine.setConfig(_config);
      Toast.makeText(this, "Neural parameters saved and applied", Toast.LENGTH_SHORT).show();
    });
    builder.setNegativeButton("Cancel", null);
    builder.show();
  }
  
  private void addSliderControl(LinearLayout parent, String name, int currentValue, int min, int max,
    java.util.function.IntConsumer setter)
  {
    TextView label = new TextView(this);
    label.setText(String.format("%s: %d", name, currentValue));
    label.setTextColor(0xFFFFFFFF);
    parent.addView(label);
    
    android.widget.SeekBar slider = new android.widget.SeekBar(this);
    slider.setMax(max - min);
    slider.setProgress(currentValue - min);
    slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
        int value = min + progress;
        label.setText(String.format("%s: %d", name, value));
        setter.accept(value);
      }
      @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
      @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
    });
    parent.addView(slider);
  }
  
  private void addFloatSliderControl(LinearLayout parent, String name, float currentValue, float min, float max,
    java.util.function.DoubleConsumer setter)
  {
    TextView label = new TextView(this);
    label.setText(String.format("%s: %.3f", name, currentValue));
    label.setTextColor(0xFFFFFFFF);
    parent.addView(label);
    
    android.widget.SeekBar slider = new android.widget.SeekBar(this);
    slider.setMax(1000); // Fine granularity
    slider.setProgress((int)((currentValue - min) * 1000 / (max - min)));
    slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
        float value = min + (progress / 1000.0f) * (max - min);
        label.setText(String.format("%s: %.3f", name, value));
        setter.accept(value);
      }
      @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
      @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
    });
    parent.addView(slider);
  }
  
  private void showErrorDialog(String message)
  {
    new AlertDialog.Builder(this)
      .setTitle("Neural Engine Error")
      .setMessage(message)
      .setPositiveButton("OK", (dialog, which) -> finish())
      .setCancelable(false)
      .show();
  }
  
  // Results logging methods
  private void logToResults(String message)
  {
    String timestamp = new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date());
    String logEntry = "[" + timestamp + "] " + message + "\n";
    _resultsLog.append(logEntry);
    
    if (_resultsTextBox != null)
    {
      _resultsTextBox.setText(_resultsLog.toString());
      // Auto-scroll to bottom
      _resultsTextBox.post(() -> {
        android.text.Layout layout = _resultsTextBox.getLayout();
        if (layout != null) {
          int scrollAmount = layout.getLineTop(_resultsTextBox.getLineCount()) - _resultsTextBox.getHeight();
          if (scrollAmount > 0) {
            _resultsTextBox.scrollTo(0, scrollAmount);
          }
        }
      });
    }
  }
  
  private void copyResultsToClipboard()
  {
    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
    ClipData clip = ClipData.newPlainText("Neural Results Log", _resultsLog.toString());
    clipboard.setPrimaryClip(clip);
    Toast.makeText(this, "Results copied to clipboard", Toast.LENGTH_SHORT).show();
  }
  
  /**
   * Restored keyboard view with proper 4-row QWERTY layout and touch handling
   */
  private class NeuralKeyboardView extends View
  {
    private Paint _keyPaint;
    private Paint _keyBorderPaint;
    private Paint _textPaint;
    private Paint _swipePaint;
    private Paint _overlayPaint;
    private Map<String, KeyButton> _keys;
    private Path _swipePath;
    private Path _overlayPath;
    private List<PointF> _swipePoints;
    private boolean _swiping;
    
    public NeuralKeyboardView(Context context)
    {
      super(context);
      init();
    }
    
    private void init()
    {
      _keyPaint = new Paint();
      _keyPaint.setColor(0xFF2B2B2B); // Darker gray similar to real keyboard
      _keyPaint.setStyle(Paint.Style.FILL);
      _keyPaint.setAntiAlias(true);
      
      _keyBorderPaint = new Paint();
      _keyBorderPaint.setColor(0xFF1A1A1A); // Even darker for border
      _keyBorderPaint.setStyle(Paint.Style.STROKE);
      _keyBorderPaint.setStrokeWidth(2);
      _keyBorderPaint.setAntiAlias(true);
      
      _textPaint = new Paint();
      _textPaint.setColor(Color.WHITE);
      _textPaint.setTextAlign(Paint.Align.CENTER);
      _textPaint.setAntiAlias(true);
      _textPaint.setSubpixelText(true);
      
      _swipePaint = new Paint();
      _swipePaint.setColor(Color.CYAN);
      _swipePaint.setStrokeWidth(8);
      _swipePaint.setStyle(Paint.Style.STROKE);
      _swipePaint.setAlpha(180);
      _swipePaint.setAntiAlias(true);
      _swipePaint.setStrokeCap(Paint.Cap.ROUND);
      _swipePaint.setStrokeJoin(Paint.Join.ROUND);
      
      _overlayPaint = new Paint();
      _overlayPaint.setColor(Color.GREEN);
      _overlayPaint.setStrokeWidth(10);
      _overlayPaint.setStyle(Paint.Style.STROKE);
      _overlayPaint.setAlpha(200);
      _overlayPaint.setAntiAlias(true);
      _overlayPaint.setStrokeCap(Paint.Cap.ROUND);
      _overlayPaint.setStrokeJoin(Paint.Join.ROUND);
      
      _swipePath = new Path();
      _overlayPath = null;
      _swipePoints = new ArrayList<>();
      _keys = new HashMap<>();
      
      setBackgroundColor(Color.BLACK);
    }
    
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh)
    {
      super.onSizeChanged(w, h, oldw, oldh);
      layoutKeys(w, h);
      
      // Update neural engine with keyboard dimensions and key positions
      _neuralEngine.setKeyboardDimensions(w, h);
      
      Map<Character, PointF> keyPositions = new HashMap<>();
      for (Map.Entry<String, KeyButton> entry : _keys.entrySet())
      {
        String keyStr = entry.getKey();
        if (keyStr.length() == 1)
        {
          char keyChar = keyStr.charAt(0);
          KeyButton button = entry.getValue();
          keyPositions.put(keyChar, new PointF(
            button.x + button.width/2, 
            button.y + button.height/2));
        }
      }
      _neuralEngine.setRealKeyPositions(keyPositions);
    }
    
    private void layoutKeys(int width, int height)
    {
      _keys.clear();
      
      // Use user's configuration for dimensions
      float keyWidth = width / 10f;
      float rowHeight = height / 4f; // 4 rows including bottom row
      float verticalMargin = _keyVerticalMargin * rowHeight;
      float horizontalMargin = _keyHorizontalMargin * keyWidth;
      
      // Calculate text size using actual config values
      float characterSize = _characterSize;
      float labelTextSize = _labelTextSize;
      
      // Match the real keyboard's text size calculation
      float baseSize = Math.min(
        rowHeight - verticalMargin,
        (keyWidth - horizontalMargin) * 3f/2f
      );
      float textSize = baseSize * characterSize * labelTextSize;
      _textPaint.setTextSize(textSize);
      
      // Layout QWERTY keyboard with 4 rows
      String[][] fullLayout = {
        {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
        {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
        {"shift", "z", "x", "c", "v", "b", "n", "m", "backspace"},
        {"?123", ",", "space", ".", "enter"}
      };
      
      for (int row = 0; row < fullLayout.length; row++)
      {
        String[] rowKeys = fullLayout[row];
        
        if (row == 0) // Top row (q-p)
        {
          for (int col = 0; col < rowKeys.length; col++)
          {
            String key = rowKeys[col];
            float x = col * keyWidth + horizontalMargin / 2;
            float y = row * rowHeight + verticalMargin / 2;
            
            KeyButton button = new KeyButton(key, x, y, 
              keyWidth - horizontalMargin, rowHeight - verticalMargin);
            _keys.put(key, button);
          }
        }
        else if (row == 1) // Second row (a-l) - offset by half key
        {
          float rowOffset = keyWidth * 0.5f;
          for (int col = 0; col < rowKeys.length; col++)
          {
            String key = rowKeys[col];
            float x = rowOffset + col * keyWidth + horizontalMargin / 2;
            float y = row * rowHeight + verticalMargin / 2;
            
            KeyButton button = new KeyButton(key, x, y, 
              keyWidth - horizontalMargin, rowHeight - verticalMargin);
            _keys.put(key, button);
          }
        }
        else if (row == 2) // Third row (shift, z-m, backspace)
        {
          float currentX = horizontalMargin / 2;
          for (int col = 0; col < rowKeys.length; col++)
          {
            String key = rowKeys[col];
            float keyW = keyWidth - horizontalMargin;
            
            // Special keys are wider
            if (key.equals("shift") || key.equals("backspace"))
            {
              keyW = keyWidth * 1.5f - horizontalMargin;
            }
            
            float y = row * rowHeight + verticalMargin / 2;
            
            KeyButton button = new KeyButton(key, currentX, y, 
              keyW, rowHeight - verticalMargin);
            _keys.put(key, button);
            
            currentX += keyW + horizontalMargin;
          }
        }
        else if (row == 3) // Bottom row (?123, comma, space, period, enter)
        {
          float currentX = horizontalMargin / 2;
          for (int col = 0; col < rowKeys.length; col++)
          {
            String key = rowKeys[col];
            float keyW = keyWidth - horizontalMargin;
            
            // Special key widths
            if (key.equals("space"))
            {
              keyW = keyWidth * 5f - horizontalMargin; // Space bar is 5 keys wide
            }
            else if (key.equals("?123") || key.equals("enter"))
            {
              keyW = keyWidth * 1.5f - horizontalMargin;
            }
            
            float y = row * rowHeight + verticalMargin / 2;
            
            KeyButton button = new KeyButton(key, currentX, y, 
              keyW, rowHeight - verticalMargin);
            _keys.put(key, button);
            
            currentX += keyW + horizontalMargin;
          }
        }
      }
    }
    
    @Override
    protected void onDraw(Canvas canvas)
    {
      super.onDraw(canvas);
      
      // Draw keys
      for (KeyButton key : _keys.values())
      {
        key.draw(canvas, _keyPaint, _keyBorderPaint, _textPaint);
      }
      
      // Draw swipe path
      if (!_swipePath.isEmpty())
      {
        canvas.drawPath(_swipePath, _swipePaint);
      }
      
      // Draw overlay path (displayed after swipe completion)
      if (_overlayPath != null)
      {
        canvas.drawPath(_overlayPath, _overlayPaint);
      }
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event)
    {
      float x = event.getX();
      float y = event.getY();
      
      switch (event.getAction())
      {
        case MotionEvent.ACTION_DOWN:
          Log.d(TAG, "🔥 ACTION_DOWN - Starting swipe");
          _swiping = true;
          _swipeStartTime = System.currentTimeMillis();
          _swipePath.reset();
          _swipePath.moveTo(x, y);
          _swipePoints.clear();
          _swipePoints.add(new PointF(x, y));
          _currentSwipeTimestamps.clear();
          _currentSwipeTimestamps.add(System.currentTimeMillis());
          invalidate();
          return true;
          
        case MotionEvent.ACTION_MOVE:
          if (_swiping)
          {
            _swipePath.lineTo(x, y);
            _swipePoints.add(new PointF(x, y));
            _currentSwipeTimestamps.add(System.currentTimeMillis());
            invalidate();
          }
          return true;
          
        case MotionEvent.ACTION_UP:
          if (_swiping)
          {
            _swiping = false;
            Log.d(TAG, "🔥 ACTION_UP detected, swipe points: " + _swipePoints.size());
            if (_swipePoints.size() > 5) // Minimum points for valid swipe
            {
              Log.d(TAG, "🔥 About to call recordSwipe with " + _swipePoints.size() + " points");
              recordSwipe(new ArrayList<>(_swipePoints));
            }
            _currentSwipeTimestamps.clear();
          }
          return true;
      }
      
      return super.onTouchEvent(event);
    }
    
    public void reset()
    {
      _swipePath.reset();
      _swipePoints.clear();
      _currentSwipeTimestamps.clear();
      _overlayPath = null;
      invalidate();
    }
    
    public void setSwipeOverlay(Path path)
    {
      _overlayPath = path;
      invalidate();
    }
    
    public void clearOverlay()
    {
      _overlayPath = null;
      invalidate();
    }
    
    public String getKeyAt(float x, float y)
    {
      for (Map.Entry<String, KeyButton> entry : _keys.entrySet())
      {
        if (entry.getValue().contains(x, y))
        {
          return entry.getKey();
        }
      }
      return null;
    }
    
    public Map<String, KeyButton> getKeyPositions()
    {
      return new HashMap<>(_keys);
    }
    
    public void displaySwipeTrace(List<PointF> points)
    {
      if (points == null || points.isEmpty()) return;
      
      _overlayPath = new Path();
      if (!points.isEmpty()) {
        _overlayPath.moveTo(points.get(0).x, points.get(0).y);
      }
      
      for (int i = 1; i < points.size(); i++)
      {
        _overlayPath.lineTo(points.get(i).x, points.get(i).y);
      }
      
      invalidate();
    }
    
    public void clearSwipeOverlay()
    {
      _overlayPath = null;
      invalidate();
    }
  }
  
  // Add recordSwipe method needed by keyboard view
  private void recordSwipe(List<PointF> points)
  {
    Log.d(TAG, "🔥 recordSwipe called with " + points.size() + " points");
    if (points.isEmpty()) return;
    
    long duration = System.currentTimeMillis() - _swipeStartTime;
    
    // Create SwipeInput for neural prediction
    String keySequence = "";
    for (PointF p : points)
    {
      String keyChar = _keyboardView.getKeyAt(p.x, p.y);
      if (keyChar != null && keyChar.length() == 1)
      {
        keySequence += keyChar;
      }
    }
    
    SwipeInput swipeInput = new SwipeInput(points, _currentSwipeTimestamps, new ArrayList<>());
    
    // Record ML data
    SwipeMLData mlData = new SwipeMLData(_currentWord, "neural_calibration",
                                         _screenWidth, _screenHeight, _keyboardHeight);
    
    // Add trace points with actual timestamps
    for (int i = 0; i < points.size() && i < _currentSwipeTimestamps.size(); i++)
    {
      PointF p = points.get(i);
      long timestamp = _currentSwipeTimestamps.get(i);
      mlData.addRawPoint(p.x, p.y, timestamp);
      
      // Add registered key
      String key = _keyboardView.getKeyAt(p.x, p.y);
      if (key != null)
      {
        mlData.addRegisteredKey(key);
      }
    }
    
    _mlDataStore.storeSwipeData(mlData);
    
    // Run neural prediction with full logging for performance debugging
    logToResults("🌀 Swipe recorded for '" + _currentWord + "': " + points.size() + " points, " + duration + "ms, keys: " + keySequence);
    
    try
    {
      Log.d(TAG, "🔥 About to call neural prediction");
      long startTime = System.nanoTime();
      PredictionResult result = _neuralEngine.predict(swipeInput);
      long endTime = System.nanoTime();
      Log.d(TAG, "🔥 Neural prediction completed");
      
      _predictionTimes.add(endTime - startTime);
      _totalPredictions++;
      
      // Log detailed prediction timing for debugging slow performance
      long predTimeMs = (endTime - startTime) / 1000000;
      logToResults("🧠 Neural prediction completed in " + predTimeMs + "ms");
      logToResults("   Predictions: " + result.words.size() + " candidates");
      
      // Log all predictions to debug quality
      for (int i = 0; i < Math.min(5, result.words.size()); i++)
      {
        logToResults("   " + (i + 1) + ". " + result.words.get(i) + " (score: " + result.scores.get(i) + ")");
      }
      
      // Check if prediction is correct
      boolean correct = false;
      int rank = -1;
      for (int i = 0; i < result.words.size(); i++)
      {
        if (result.words.get(i).equals(_currentWord))
        {
          correct = true;
          rank = i + 1;
          _correctPredictions++;
          logToResults("✅ Correct! Target '" + _currentWord + "' found at rank " + rank);
          break;
        }
      }
      
      if (!correct)
      {
        logToResults("❌ Incorrect. Expected '" + _currentWord + "', got: " + 
          (result.words.isEmpty() ? "no predictions" : "'" + result.words.get(0) + "'"));
      }
      
      updateBenchmarkDisplay();
    }
    catch (Exception e)
    {
      logToResults("💥 Neural prediction FAILED: " + e.getClass().getSimpleName() + " - " + e.getMessage());
      Log.e(TAG, "Neural prediction failed", e);
      Toast.makeText(this, "Neural prediction error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
    }
    
    // Auto-advance after delay
    new Handler().postDelayed(() -> nextWord(), 1500);
  }
  
  /**
   * Key button with proper styling and touch detection
   */
  static class KeyButton
  {
    String label;
    float x, y, width, height;
    
    KeyButton(String label, float x, float y, float width, float height)
    {
      this.label = label;
      this.x = x;
      this.y = y;
      this.width = width;
      this.height = height;
    }
    
    void draw(Canvas canvas, Paint keyPaint, Paint borderPaint, Paint textPaint)
    {
      // Draw key background with rounded corners
      android.graphics.RectF rect = new android.graphics.RectF(x, y, x + width, y + height);
      float cornerRadius = Math.min(width, height) * 0.15f; // 15% of smallest dimension
      canvas.drawRoundRect(rect, cornerRadius, cornerRadius, keyPaint);
      
      // Draw border
      canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint);
      
      // Draw text centered properly (accounting for text metrics)
      String displayLabel = label;
      
      // Handle special key labels
      if (label.equals("space"))
      {
        displayLabel = " "; // Space bar typically has no label
      }
      else if (label.equals("shift"))
      {
        displayLabel = "⇧"; // Shift arrow symbol
      }
      else if (label.equals("backspace"))
      {
        displayLabel = "⌫"; // Backspace symbol
      }
      else if (label.equals("enter"))
      {
        displayLabel = "↵"; // Enter/return symbol
      }
      else if (label.equals("?123"))
      {
        displayLabel = "?123"; // Keep as is
      }
      else
      {
        displayLabel = label.toUpperCase(); // Regular keys in uppercase
      }
      
      float textY = y + (height - textPaint.ascent() - textPaint.descent()) / 2f;
      canvas.drawText(displayLabel, x + width / 2, textY, textPaint);
    }
    
    boolean contains(float px, float py)
    {
      return px >= x && px <= x + width && py >= y && py <= y + height;
    }
  }
}