package juloo.keyboard2;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.os.Build.VERSION;
import android.os.Handler;
import android.os.IBinder;
import android.text.InputType;
import android.util.Log;
import android.util.LogPrinter;
import android.util.TypedValue;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.io.FileWriter;
import java.io.BufferedWriter;
import java.io.IOException;
import juloo.keyboard2.ml.SwipeMLData;
import juloo.keyboard2.ml.SwipeMLDataStore;
import juloo.keyboard2.prefs.LayoutsPreference;

public class Keyboard2 extends InputMethodService
  implements SharedPreferences.OnSharedPreferenceChangeListener,
  SuggestionBar.OnSuggestionSelectedListener
{
  // Unified prediction strategy: All predictions wait for gesture completion
  // to match SwipeCalibrationActivity behavior and eliminate premature predictions
  private Keyboard2View _keyboardView;
  private KeyEventHandler _keyeventhandler;
  /** If not 'null', the layout to use instead of [_config.current_layout]. */
  private KeyboardData _currentSpecialLayout;
  /** Layout associated with the currently selected locale. Not 'null'. */
  private KeyboardData _localeTextLayout;
  private ViewGroup _emojiPane = null;
  private ViewGroup _clipboard_pane = null;
  public int actionId; // Action performed by the Action key.
  private Handler _handler;

  private Config _config;

  private FoldStateTracker _foldStateTracker;
  
  // Swipe typing components
  private DictionaryManager _dictionaryManager;
  private WordPredictor _wordPredictor;
  private NeuralSwipeTypingEngine _neuralEngine;
  private AsyncPredictionHandler _asyncPredictionHandler;
  private SuggestionBar _suggestionBar;
  private LinearLayout _inputViewContainer;
  private StringBuilder _currentWord = new StringBuilder();
  private List<String> _contextWords = new ArrayList<>(); // Track previous words for context
  private BufferedWriter _logWriter = null;
  
  // ML data collection
  private SwipeMLDataStore _mlDataStore;
  private SwipeMLData _currentSwipeData;
  private boolean _wasLastInputSwipe = false;
  
  // User adaptation
  private UserAdaptationManager _adaptationManager;

  /** Layout currently visible before it has been modified. */
  KeyboardData current_layout_unmodified()
  {
    if (_currentSpecialLayout != null)
      return _currentSpecialLayout;
    KeyboardData layout = null;
    int layout_i = _config.get_current_layout();
    if (layout_i >= _config.layouts.size())
      layout_i = 0;
    if (layout_i < _config.layouts.size())
      layout = _config.layouts.get(layout_i);
    if (layout == null)
      layout = _localeTextLayout;
    return layout;
  }

  /** Layout currently visible. */
  KeyboardData current_layout()
  {
    if (_currentSpecialLayout != null)
      return _currentSpecialLayout;
    return LayoutModifier.modify_layout(current_layout_unmodified());
  }

  void setTextLayout(int l)
  {
    _config.set_current_layout(l);
    _currentSpecialLayout = null;
    _keyboardView.setKeyboard(current_layout());
  }

  void incrTextLayout(int delta)
  {
    int s = _config.layouts.size();
    setTextLayout((_config.get_current_layout() + delta + s) % s);
  }

  void setSpecialLayout(KeyboardData l)
  {
    _currentSpecialLayout = l;
    _keyboardView.setKeyboard(l);
  }

  KeyboardData loadLayout(int layout_id)
  {
    return KeyboardData.load(getResources(), layout_id);
  }

  /** Load a layout that contains a numpad. */
  KeyboardData loadNumpad(int layout_id)
  {
    return LayoutModifier.modify_numpad(KeyboardData.load(getResources(), layout_id),
        current_layout_unmodified());
  }

  KeyboardData loadPinentry(int layout_id)
  {
    return LayoutModifier.modify_pinentry(KeyboardData.load(getResources(), layout_id),
        current_layout_unmodified());
  }

  @Override
  public void onCreate()
  {
    super.onCreate();
    SharedPreferences prefs = DirectBootAwarePreferences.get_shared_preferences(this);
    _handler = new Handler(getMainLooper());
    _keyeventhandler = new KeyEventHandler(this.new Receiver());
    _foldStateTracker = new FoldStateTracker(this);
    Config.initGlobalConfig(prefs, getResources(), _keyeventhandler, _foldStateTracker.isUnfolded());
    prefs.registerOnSharedPreferenceChangeListener(this);
    _config = Config.globalConfig();
    _keyboardView = (Keyboard2View)inflate_view(R.layout.keyboard);
    _keyboardView.reset();
    Logs.set_debug_logs(getResources().getBoolean(R.bool.debug_logs));
    ClipboardHistoryService.on_startup(this, _keyeventhandler);
    _foldStateTracker.setChangedCallback(() -> { refresh_config(); });
    
    // Initialize ML data store
    _mlDataStore = SwipeMLDataStore.getInstance(this);
    
    // CGR parameter reloading will be handled through existing preference listener
    
    // Initialize user adaptation manager
    _adaptationManager = UserAdaptationManager.getInstance(this);

    // KeyboardSwipeRecognizer is now handled through SwipeTypingEngine
    
    // Initialize log writer for swipe analysis
    try
    {
      _logWriter = new BufferedWriter(new FileWriter("/data/data/com.termux/files/home/swipe_log.txt", true));
      _logWriter.write("\n=== Keyboard2 Started: " + new java.util.Date() + " ===\n");
      _logWriter.flush();
    }
    catch (IOException e)
    {
      android.util.Log.e("Keyboard2", "Failed to open log file: " + e.getMessage());
    }
    
    // Initialize word prediction components (for both swipe and regular typing)
    if (_config.word_prediction_enabled || _config.swipe_typing_enabled)
    {
      _dictionaryManager = new DictionaryManager(this);
      _dictionaryManager.setLanguage("en");
      _wordPredictor = new WordPredictor();
      _wordPredictor.setConfig(_config);
      _wordPredictor.setUserAdaptationManager(_adaptationManager);
      _wordPredictor.loadDictionary(this, "en");
      
      // Initialize neural predictor for swipe typing only
      if (_config.swipe_typing_enabled)
      {
        _neuralEngine = new NeuralSwipeTypingEngine(this, _config);
        
        // Initialize async prediction handler
        _asyncPredictionHandler = new AsyncPredictionHandler(_neuralEngine);
        
        // CGR recognizer doesn't need separate calibration data loading
        
        // Set keyboard dimensions if available
        if (_keyboardView != null)
        {
          _neuralEngine.setKeyboardDimensions(_keyboardView.getWidth(), _keyboardView.getHeight());
        }
        
        _keyboardView.setSwipeTypingComponents(_wordPredictor, this);
      }
    }
  }

  @Override
  public void onDestroy() {
    super.onDestroy();

    _foldStateTracker.close();
    
    // Cleanup async prediction handler
    if (_asyncPredictionHandler != null)
    {
      _asyncPredictionHandler.shutdown();
      _asyncPredictionHandler = null;
    }
  }

  private List<InputMethodSubtype> getEnabledSubtypes(InputMethodManager imm)
  {
    String pkg = getPackageName();
    for (InputMethodInfo imi : imm.getEnabledInputMethodList())
      if (imi.getPackageName().equals(pkg))
        return imm.getEnabledInputMethodSubtypeList(imi, true);
    return Arrays.asList();
  }

  @TargetApi(12)
  private ExtraKeys extra_keys_of_subtype(InputMethodSubtype subtype)
  {
    String extra_keys = subtype.getExtraValueOf("extra_keys");
    String script = subtype.getExtraValueOf("script");
    if (extra_keys != null)
      return ExtraKeys.parse(script, extra_keys);
    return ExtraKeys.EMPTY;
  }

  private void refreshAccentsOption(InputMethodManager imm, List<InputMethodSubtype> enabled_subtypes)
  {
    List<ExtraKeys> extra_keys = new ArrayList<ExtraKeys>();
    for (InputMethodSubtype s : enabled_subtypes)
      extra_keys.add(extra_keys_of_subtype(s));
    _config.extra_keys_subtype = ExtraKeys.merge(extra_keys);
  }

  InputMethodManager get_imm()
  {
    return (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
  }

  @TargetApi(12)
  private InputMethodSubtype defaultSubtypes(InputMethodManager imm, List<InputMethodSubtype> enabled_subtypes)
  {
    if (VERSION.SDK_INT < 24)
      return imm.getCurrentInputMethodSubtype();
    // Android might return a random subtype, for example, the first in the
    // list alphabetically.
    InputMethodSubtype current_subtype = imm.getCurrentInputMethodSubtype();
    if (current_subtype == null)
      return null;
    for (InputMethodSubtype s : enabled_subtypes)
      if (s.getLanguageTag().equals(current_subtype.getLanguageTag()))
        return s;
    return null;
  }

  private void refreshSubtypeImm()
  {
    InputMethodManager imm = get_imm();
    _config.shouldOfferVoiceTyping = true;
    KeyboardData default_layout = null;
    _config.extra_keys_subtype = null;
    if (VERSION.SDK_INT >= 12)
    {
      List<InputMethodSubtype> enabled_subtypes = getEnabledSubtypes(imm);
      InputMethodSubtype subtype = defaultSubtypes(imm, enabled_subtypes);
      if (subtype != null)
      {
        String s = subtype.getExtraValueOf("default_layout");
        if (s != null)
          default_layout = LayoutsPreference.layout_of_string(getResources(), s);
        refreshAccentsOption(imm, enabled_subtypes);
      }
    }
    if (default_layout == null)
      default_layout = loadLayout(R.xml.latn_qwerty_us);
    _localeTextLayout = default_layout;
  }

  private String actionLabel_of_imeAction(int action)
  {
    int res;
    switch (action)
    {
      case EditorInfo.IME_ACTION_NEXT: res = R.string.key_action_next; break;
      case EditorInfo.IME_ACTION_DONE: res = R.string.key_action_done; break;
      case EditorInfo.IME_ACTION_GO: res = R.string.key_action_go; break;
      case EditorInfo.IME_ACTION_PREVIOUS: res = R.string.key_action_prev; break;
      case EditorInfo.IME_ACTION_SEARCH: res = R.string.key_action_search; break;
      case EditorInfo.IME_ACTION_SEND: res = R.string.key_action_send; break;
      case EditorInfo.IME_ACTION_UNSPECIFIED:
      case EditorInfo.IME_ACTION_NONE:
      default: return null;
    }
    return getResources().getString(res);
  }

  private void refresh_action_label(EditorInfo info)
  {
    // First try to look at 'info.actionLabel', if it isn't set, look at
    // 'imeOptions'.
    if (info.actionLabel != null)
    {
      _config.actionLabel = info.actionLabel.toString();
      actionId = info.actionId;
      _config.swapEnterActionKey = false;
    }
    else
    {
      int action = info.imeOptions & EditorInfo.IME_MASK_ACTION;
      _config.actionLabel = actionLabel_of_imeAction(action); // Might be null
      actionId = action;
      _config.swapEnterActionKey =
        (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0;
    }
  }

  /** Might re-create the keyboard view. [_keyboardView.setKeyboard()] and
      [setInputView()] must be called soon after. */
  private void refresh_config()
  {
    int prev_theme = _config.theme;
    _config.refresh(getResources(), _foldStateTracker.isUnfolded());
    refreshSubtypeImm();
    
    // Update swipe engine config if it exists
    if (_neuralEngine != null)
    {
      _neuralEngine.setConfig(_config);
    }
    if (_wordPredictor != null)
    {
      _wordPredictor.setConfig(_config);
    }
    // Refreshing the theme config requires re-creating the views
    if (prev_theme != _config.theme)
    {
      _keyboardView = (Keyboard2View)inflate_view(R.layout.keyboard);
      _emojiPane = null;
      _clipboard_pane = null;
      setInputView(_keyboardView);
    }
    _keyboardView.reset();
  }

  private KeyboardData refresh_special_layout(EditorInfo info)
  {
    switch (info.inputType & InputType.TYPE_MASK_CLASS)
    {
      case InputType.TYPE_CLASS_NUMBER:
      case InputType.TYPE_CLASS_PHONE:
      case InputType.TYPE_CLASS_DATETIME:
        if (_config.selected_number_layout == NumberLayout.PIN)
          return loadPinentry(R.xml.pin);
        else if (_config.selected_number_layout == NumberLayout.NUMBER)
          return loadNumpad(R.xml.numeric);
      default:
        break;
    }
    return null;
  }

  @Override
  public void onStartInputView(EditorInfo info, boolean restarting)
  {
    refresh_config();
    refresh_action_label(info);
    _currentSpecialLayout = refresh_special_layout(info);
    _keyboardView.setKeyboard(current_layout());
    _keyeventhandler.started(info);
    
    // Re-initialize word prediction components if settings have changed
    if (_config.word_prediction_enabled || _config.swipe_typing_enabled)
    {
      android.util.Log.d("Keyboard2", "onStartInputView: word_prediction=" + _config.word_prediction_enabled + 
                         " swipe_typing=" + _config.swipe_typing_enabled);
      
      // Initialize predictors if not already initialized
      if (_wordPredictor == null)
      {
        android.util.Log.d("Keyboard2", "Initializing word predictor in onStartInputView");
        _dictionaryManager = new DictionaryManager(this);
        _dictionaryManager.setLanguage("en");
        _wordPredictor = new WordPredictor();
        _wordPredictor.setConfig(_config);
        _wordPredictor.loadDictionary(this, "en");
        android.util.Log.d("Keyboard2", "Word predictor initialized with " + _wordPredictor.getDictionarySize() + " words");
      }
      
      if (_config.swipe_typing_enabled && _neuralEngine == null)
      {
        android.util.Log.d("Keyboard2", "Initializing neural engine in onStartInputView");
        _neuralEngine = new NeuralSwipeTypingEngine(this, _config);
        
        // Initialize async prediction handler
        _asyncPredictionHandler = new AsyncPredictionHandler(_neuralEngine);
        
        // CGR recognizer doesn't need separate calibration data loading
        
        // Set keyboard dimensions
        if (_keyboardView != null)
        {
          _neuralEngine.setKeyboardDimensions(_keyboardView.getWidth(), _keyboardView.getHeight());
        }
        
        _keyboardView.setSwipeTypingComponents(_wordPredictor, this);
      }
      
      // Create suggestion bar if needed
      if (_suggestionBar == null)
      {
        android.util.Log.d("Keyboard2", "Creating suggestion bar in onStartInputView");
        _inputViewContainer = new LinearLayout(this);
        _inputViewContainer.setOrientation(LinearLayout.VERTICAL);
        
        // Get theme from keyboard view if available
        Theme theme = _keyboardView != null ? _keyboardView.getTheme() : null;
        _suggestionBar = theme != null ? new SuggestionBar(this, theme) : new SuggestionBar(this);
        _suggestionBar.setOnSuggestionSelectedListener(this);
        _suggestionBar.setOpacity(_config.suggestion_bar_opacity);
        LinearLayout.LayoutParams suggestionParams = new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 40,
            getResources().getDisplayMetrics()));
        _suggestionBar.setLayoutParams(suggestionParams);
        
        _inputViewContainer.addView(_suggestionBar);
        _inputViewContainer.addView(_keyboardView);
      }
      
      setInputView(_inputViewContainer != null ? _inputViewContainer : _keyboardView);
      
      // CRITICAL: Set correct keyboard dimensions for CGR after view is laid out
      if (_neuralEngine != null && _keyboardView != null) {
        _keyboardView.getViewTreeObserver().addOnGlobalLayoutListener(
          new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
              // Ensure we have valid dimensions
              if (_keyboardView.getWidth() > 0 && _keyboardView.getHeight() > 0) {
                
                // Use dynamic keyboard dimensions based on user settings (like calibration)
                float keyboardWidth = _keyboardView.getWidth();
                float keyboardHeight = calculateDynamicKeyboardHeight();
                
                _neuralEngine.setKeyboardDimensions(keyboardWidth, keyboardHeight);
                
                // CRITICAL: Also set real key positions for 100% accurate coordinate mapping
                java.util.Map<Character, android.graphics.PointF> realKeyPositions = _keyboardView.getRealKeyPositions();
                _neuralEngine.setRealKeyPositions(realKeyPositions);
                
                android.util.Log.e("Keyboard2", "✅ SET DYNAMIC KEYBOARD DIMENSIONS: " + 
                  keyboardWidth + "x" + keyboardHeight + " (user: " + getUserKeyboardHeightPercent() + "%)");
                android.util.Log.e("Keyboard2", "✅ SET REAL KEY POSITIONS: " + realKeyPositions.size() + " keys");
                
                // Remove the listener to avoid repeated calls
                _keyboardView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
              }
            }
          }
        );
      }
    }
    else
    {
      android.util.Log.d("Keyboard2", "Predictions disabled in settings");
      // Clean up if predictions are disabled
      _wordPredictor = null;
      // CGR recognizer cleanup handled by SwipeTypingEngine
      _suggestionBar = null;
      _inputViewContainer = null;
      setInputView(_keyboardView);
    }
    
    Logs.debug_startup_input_view(info, _config);
  }

  @Override
  public void setInputView(View v)
  {
    ViewParent parent = v.getParent();
    if (parent != null && parent instanceof ViewGroup)
      ((ViewGroup)parent).removeView(v);
    super.setInputView(v);
    updateSoftInputWindowLayoutParams();
    v.requestApplyInsets();
  }


  @Override
  public void updateFullscreenMode() {
    super.updateFullscreenMode();
    updateSoftInputWindowLayoutParams();
  }

  private void updateSoftInputWindowLayoutParams() {
    final Window window = getWindow().getWindow();
    // On API >= 35, Keyboard2View behaves as edge-to-edge
    // APIs 30 to 34 have visual artifact when edge-to-edge is enabled
    if (VERSION.SDK_INT >= 35)
    {
      WindowManager.LayoutParams wattrs = window.getAttributes();
      wattrs.layoutInDisplayCutoutMode =
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
      // Allow to draw behind system bars
      wattrs.setFitInsetsTypes(0);
      window.setDecorFitsSystemWindows(false);
    }
    updateLayoutHeightOf(window, ViewGroup.LayoutParams.MATCH_PARENT);
    final View inputArea = window.findViewById(android.R.id.inputArea);

    updateLayoutHeightOf(
            (View) inputArea.getParent(),
            isFullscreenMode()
                    ? ViewGroup.LayoutParams.MATCH_PARENT
                    : ViewGroup.LayoutParams.WRAP_CONTENT);
    updateLayoutGravityOf((View) inputArea.getParent(), Gravity.BOTTOM);

  }

  private static void updateLayoutHeightOf(final Window window, final int layoutHeight) {
    final WindowManager.LayoutParams params = window.getAttributes();
    if (params != null && params.height != layoutHeight) {
      params.height = layoutHeight;
      window.setAttributes(params);
    }
  }

  private static void updateLayoutHeightOf(final View view, final int layoutHeight) {
    final ViewGroup.LayoutParams params = view.getLayoutParams();
    if (params != null && params.height != layoutHeight) {
      params.height = layoutHeight;
      view.setLayoutParams(params);
    }
  }

  private static void updateLayoutGravityOf(final View view, final int layoutGravity) {
    final ViewGroup.LayoutParams lp = view.getLayoutParams();
    if (lp instanceof LinearLayout.LayoutParams) {
      final LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) lp;
      if (params.gravity != layoutGravity) {
        params.gravity = layoutGravity;
        view.setLayoutParams(params);
      }
    } else if (lp instanceof FrameLayout.LayoutParams) {
      final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) lp;
      if (params.gravity != layoutGravity) {
        params.gravity = layoutGravity;
        view.setLayoutParams(params);
      }
    }
  }

  @Override
  public void onCurrentInputMethodSubtypeChanged(InputMethodSubtype subtype)
  {
    refreshSubtypeImm();
    _keyboardView.setKeyboard(current_layout());
  }

  @Override
  public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd, int candidatesStart, int candidatesEnd)
  {
    super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
    _keyeventhandler.selection_updated(oldSelStart, newSelStart);
    if ((oldSelStart == oldSelEnd) != (newSelStart == newSelEnd))
      _keyboardView.set_selection_state(newSelStart != newSelEnd);
  }

  @Override
  public void onFinishInputView(boolean finishingInput)
  {
    super.onFinishInputView(finishingInput);
    _keyboardView.reset();
  }

  @Override
  public void onSharedPreferenceChanged(SharedPreferences _prefs, String _key)
  {
    refresh_config();
    _keyboardView.setKeyboard(current_layout());
    // Update suggestion bar opacity if it exists
    if (_suggestionBar != null)
    {
      _suggestionBar.setOpacity(_config.suggestion_bar_opacity);
    }
  }

  @Override
  public boolean onEvaluateFullscreenMode()
  {
    /* Entirely disable fullscreen mode. */
    return false;
  }

  /** Not static */
  public class Receiver implements KeyEventHandler.IReceiver
  {
    public void handle_event_key(KeyValue.Event ev)
    {
      switch (ev)
      {
        case CONFIG:
          Intent intent = new Intent(Keyboard2.this, SettingsActivity.class);
          intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
          startActivity(intent);
          break;

        case SWITCH_TEXT:
          _currentSpecialLayout = null;
          _keyboardView.setKeyboard(current_layout());
          break;

        case SWITCH_NUMERIC:
          setSpecialLayout(loadNumpad(R.xml.numeric));
          break;

        case SWITCH_EMOJI:
          if (_emojiPane == null)
            _emojiPane = (ViewGroup)inflate_view(R.layout.emoji_pane);
          setInputView(_emojiPane);
          break;

        case SWITCH_CLIPBOARD:
          if (_clipboard_pane == null)
            _clipboard_pane = (ViewGroup)inflate_view(R.layout.clipboard_pane);
          setInputView(_clipboard_pane);
          break;

        case SWITCH_BACK_EMOJI:
        case SWITCH_BACK_CLIPBOARD:
          setInputView(_keyboardView);
          break;

        case CHANGE_METHOD_PICKER:
          get_imm().showInputMethodPicker();
          break;

        case CHANGE_METHOD_AUTO:
          if (VERSION.SDK_INT < 28)
            get_imm().switchToLastInputMethod(getConnectionToken());
          else
            switchToNextInputMethod(false);
          break;

        case ACTION:
          InputConnection conn = getCurrentInputConnection();
          if (conn != null)
            conn.performEditorAction(actionId);
          break;

        case SWITCH_FORWARD:
          incrTextLayout(1);
          break;

        case SWITCH_BACKWARD:
          incrTextLayout(-1);
          break;

        case SWITCH_GREEKMATH:
          setSpecialLayout(loadNumpad(R.xml.greekmath));
          break;

        case CAPS_LOCK:
          set_shift_state(true, true);
          break;

        case SWITCH_VOICE_TYPING:
          if (!VoiceImeSwitcher.switch_to_voice_ime(Keyboard2.this, get_imm(),
                Config.globalPrefs()))
            _config.shouldOfferVoiceTyping = false;
          break;

        case SWITCH_VOICE_TYPING_CHOOSER:
          VoiceImeSwitcher.choose_voice_ime(Keyboard2.this, get_imm(),
              Config.globalPrefs());
          break;
      }
    }

    public void set_shift_state(boolean state, boolean lock)
    {
      _keyboardView.set_shift_state(state, lock);
    }

    public void set_compose_pending(boolean pending)
    {
      _keyboardView.set_compose_pending(pending);
    }

    public void selection_state_changed(boolean selection_is_ongoing)
    {
      _keyboardView.set_selection_state(selection_is_ongoing);
    }

    public InputConnection getCurrentInputConnection()
    {
      return Keyboard2.this.getCurrentInputConnection();
    }

    public Handler getHandler()
    {
      return _handler;
    }
    
    public void handle_text_typed(String text)
    {
      // Reset swipe tracking when regular typing occurs
      _wasLastInputSwipe = false;
      _currentSwipeData = null;
      handleRegularTyping(text);
    }
    
    public void handle_backspace()
    {
      Keyboard2.this.handleBackspace();
    }
  }

  private IBinder getConnectionToken()
  {
    return getWindow().getWindow().getAttributes().token;
  }
  
  // SuggestionBar.OnSuggestionSelectedListener implementation
  /**
   * Update context with a completed word
   */
  private void updateContext(String word)
  {
    if (word == null || word.isEmpty())
      return;
    
    // Add word to context
    _contextWords.add(word.toLowerCase());
    
    // Keep only last 2 words for bigram context
    while (_contextWords.size() > 2)
    {
      _contextWords.remove(0);
    }
    
    // Add word to WordPredictor for language detection
    if (_wordPredictor != null)
    {
      _wordPredictor.addWordToContext(word);
    }
    
    android.util.Log.d("Keyboard2", "Context updated: " + _contextWords);
  }
  
  /**
   * Handle prediction results from async prediction handler
   */
  private void handlePredictionResults(List<String> predictions, List<Integer> scores)
  {
    android.util.Log.d("Keyboard2", "Got " + predictions.size() + " async predictions");
    
    if (predictions.isEmpty())
    {
      android.util.Log.d("Keyboard2", "No predictions to show");
      if (_suggestionBar != null)
      {
        _suggestionBar.clearSuggestions();
      }
      return;
    }
    
    // Log predictions for debugging
    for (int i = 0; i < Math.min(5, predictions.size()); i++)
    {
      android.util.Log.d("Keyboard2", String.format("Neural Prediction %d: %s (score: %d)", 
                                                     i + 1, predictions.get(i), 
                                                     i < scores.size() ? scores.get(i) : 0));
    }
    
    // Update suggestion bar (scores are already integers from neural system)
    if (_suggestionBar != null)
    {
      android.util.Log.d("Keyboard2", "Setting neural suggestions in bar: " + predictions);
      _suggestionBar.setShowDebugScores(_config.swipe_show_debug_scores);
      _suggestionBar.setSuggestionsWithScores(predictions, scores);
      android.util.Log.d("Keyboard2", "===== ASYNC SWIPE PREDICTION END =====");
    }
  }
  
  @Override
  public void onSuggestionSelected(String word)
  {
    // Record user selection for adaptation learning
    if (_adaptationManager != null && word != null && !word.trim().isEmpty())
    {
      _adaptationManager.recordSelection(word.trim());
      android.util.Log.d("Keyboard2", "Recorded user selection for adaptation: " + word.trim());
    }
    
    // Store ML data if this was a swipe prediction selection
    if (_wasLastInputSwipe && _currentSwipeData != null && _mlDataStore != null)
    {
      // Create a new ML data object with the selected word
      android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
      SwipeMLData mlData = new SwipeMLData(word, "user_selection",
                                           metrics.widthPixels, metrics.heightPixels,
                                           _keyboardView.getHeight());
      
      // Copy trace points from the temporary data
      for (SwipeMLData.TracePoint point : _currentSwipeData.getTracePoints())
      {
        // Add points with their original normalized values and timestamps
        // Since they're already normalized, we need to denormalize then renormalize
        // to ensure proper storage
        float rawX = point.x * metrics.widthPixels;
        float rawY = point.y * metrics.heightPixels;
        // Reconstruct approximate timestamp (this is a limitation of the current design)
        long timestamp = System.currentTimeMillis() - 1000 + point.tDeltaMs;
        mlData.addRawPoint(rawX, rawY, timestamp);
      }
      
      // Copy registered keys
      for (String key : _currentSwipeData.getRegisteredKeys())
      {
        mlData.addRegisteredKey(key);
      }
      
      // Store the ML data
      _mlDataStore.storeSwipeData(mlData);
      
      android.util.Log.d("Keyboard2", "Stored ML data for swipe selection: " + word);
    }
    
    // Reset swipe tracking
    _wasLastInputSwipe = false;
    _currentSwipeData = null;
    
    InputConnection ic = getCurrentInputConnection();
    if (ic != null)
    {
      // If we have a current word being typed, delete it first
      if (_currentWord.length() > 0)
      {
        // Delete the partial word
        for (int i = 0; i < _currentWord.length(); i++)
        {
          ic.deleteSurroundingText(1, 0);
        }
      }
      
      // Commit the selected word with a space
      ic.commitText(word + " ", 1);
      
      // Update context with the selected word
      updateContext(word);
      
      // Clear current word and suggestions
      _currentWord.setLength(0);
      if (_suggestionBar != null)
      {
        _suggestionBar.clearSuggestions();
      }
    }
  }
  
  /**
   * Handle regular typing predictions (non-swipe)
   */
  public void handleRegularTyping(String text)
  {
    if (!_config.word_prediction_enabled || _wordPredictor == null || _suggestionBar == null)
    {
      android.util.Log.d("Keyboard2", "Regular typing predictions disabled or not initialized");
      return;
    }
      
    android.util.Log.d("Keyboard2", "handleRegularTyping: '" + text + "'");
    
    // Track current word being typed
    if (text.length() == 1 && Character.isLetter(text.charAt(0)))
    {
      _currentWord.append(text);
      android.util.Log.d("Keyboard2", "Current word: " + _currentWord.toString());
      updatePredictionsForCurrentWord();
    }
    else if (text.length() == 1 && !Character.isLetter(text.charAt(0)))
    {
      // Any non-letter character - update context and reset current word
      android.util.Log.d("Keyboard2", "Non-letter character '" + text + "' detected");
      
      // If we had a word being typed, add it to context before clearing
      if (_currentWord.length() > 0)
      {
        String completedWord = _currentWord.toString();
        updateContext(completedWord);
        android.util.Log.d("Keyboard2", "Added word to context: " + completedWord);
      }
      
      // Reset current word
      _currentWord.setLength(0);
      if (_wordPredictor != null)
      {
        _wordPredictor.reset();
      }
      if (_suggestionBar != null)
      {
        _suggestionBar.clearSuggestions();
      }
    }
    else if (text.length() > 1)
    {
      // Multi-character input (paste, etc) - reset
      android.util.Log.d("Keyboard2", "Multi-character input, resetting predictor");
      _currentWord.setLength(0);
      if (_wordPredictor != null)
      {
        _wordPredictor.reset();
      }
      if (_suggestionBar != null)
      {
        _suggestionBar.clearSuggestions();
      }
    }
  }
  
  /**
   * Handle backspace for prediction tracking
   */
  public void handleBackspace()
  {
    if (_currentWord.length() > 0)
    {
      _currentWord.deleteCharAt(_currentWord.length() - 1);
      if (_currentWord.length() > 0)
      {
        updatePredictionsForCurrentWord();
      }
      else if (_suggestionBar != null)
      {
        _suggestionBar.clearSuggestions();
      }
    }
  }
  
  /**
   * Update predictions based on current partial word
   */
  private void updatePredictionsForCurrentWord()
  {
    if (_currentWord.length() > 0)
    {
      String partial = _currentWord.toString();
      android.util.Log.d("Keyboard2", "Updating predictions for: " + partial + " with context: " + _contextWords);
      
      // Use contextual prediction
      WordPredictor.PredictionResult result = _wordPredictor.predictWordsWithContext(partial, _contextWords);
      
      if (!result.words.isEmpty() && _suggestionBar != null)
      {
        _suggestionBar.setShowDebugScores(_config.swipe_show_debug_scores);
        _suggestionBar.setSuggestionsWithScores(result.words, result.scores);
      }
    }
  }
  
  /**
   * Calculate dynamic keyboard height based on user settings (like calibration page)
   * Supports orientation, foldable devices, and user height preferences
   */
  private float calculateDynamicKeyboardHeight()
  {
    try {
      // Get screen dimensions
      android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
      android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
      wm.getDefaultDisplay().getMetrics(metrics);
      
      // Check foldable state
      FoldStateTracker foldTracker = new FoldStateTracker(this);
      boolean foldableUnfolded = foldTracker.isUnfolded();
      
      // Check orientation
      boolean isLandscape = getResources().getConfiguration().orientation == 
                            android.content.res.Configuration.ORIENTATION_LANDSCAPE;
      
      // Get user height preference (same logic as calibration)
      SharedPreferences prefs = DirectBootAwarePreferences.get_shared_preferences(this);
      int keyboardHeightPref;
      
      if (isLandscape) {
        String key = foldableUnfolded ? "keyboard_height_landscape_unfolded" : "keyboard_height_landscape";
        keyboardHeightPref = prefs.getInt(key, 50);
      } else {
        String key = foldableUnfolded ? "keyboard_height_unfolded" : "keyboard_height";
        keyboardHeightPref = prefs.getInt(key, 35);
      }
      
      // Calculate dynamic height
      float keyboardHeightPercent = keyboardHeightPref / 100.0f;
      float calculatedHeight = metrics.heightPixels * keyboardHeightPercent;
      
      android.util.Log.e("Keyboard2", "📏 DYNAMIC HEIGHT CALC: " + keyboardHeightPref + "% of " + 
        metrics.heightPixels + " = " + calculatedHeight + " (landscape=" + isLandscape + ", foldable=" + foldableUnfolded + ")");
      
      return calculatedHeight;
      
    } catch (Exception e) {
      android.util.Log.e("Keyboard2", "Failed to calculate dynamic height: " + e.getMessage());
      // Fallback to view height
      return _keyboardView.getHeight();
    }
  }
  
  /**
   * Get user keyboard height percentage for logging
   */
  private int getUserKeyboardHeightPercent()
  {
    try {
      FoldStateTracker foldTracker = new FoldStateTracker(this);
      boolean foldableUnfolded = foldTracker.isUnfolded();
      boolean isLandscape = getResources().getConfiguration().orientation == 
                            android.content.res.Configuration.ORIENTATION_LANDSCAPE;
      
      SharedPreferences prefs = DirectBootAwarePreferences.get_shared_preferences(this);
      
      if (isLandscape) {
        String key = foldableUnfolded ? "keyboard_height_landscape_unfolded" : "keyboard_height_landscape";
        return prefs.getInt(key, 50);
      } else {
        String key = foldableUnfolded ? "keyboard_height_unfolded" : "keyboard_height";
        return prefs.getInt(key, 35);
      }
    } catch (Exception e) {
      return 35; // Default
    }
  }
  
  // Called by Keyboard2View when swipe typing completes
  public void handleSwipeTyping(List<KeyboardData.Key> swipedKeys, 
                                List<android.graphics.PointF> swipePath,
                                List<Long> timestamps)
  {
    android.util.Log.e("Keyboard2", "🚨 ===== SWIPE PREDICTION START ===== 🚨");
    android.util.Log.e("Keyboard2", "handleSwipeTyping called with " + swipedKeys.size() + " keys and " + swipePath.size() + " path points");
    
    // COORDINATE DEBUGGING: Log detailed coordinate information
    if (swipePath.size() > 0) {
      android.graphics.PointF first = swipePath.get(0);
      android.graphics.PointF last = swipePath.get(swipePath.size() - 1);
      android.util.Log.e("Keyboard2", "📍 RAW SWIPE COORDINATES:");
      android.util.Log.e("Keyboard2", "- Swipe path: (" + first.x + "," + first.y + ") → (" + last.x + "," + last.y + ")");
      android.util.Log.e("Keyboard2", "- Keyboard dimensions: " + (_keyboardView != null ? _keyboardView.getWidth() + "x" + _keyboardView.getHeight() : "unknown"));
      android.util.Log.e("Keyboard2", "- Screen metrics: " + getResources().getDisplayMetrics().widthPixels + "x" + getResources().getDisplayMetrics().heightPixels);
      
      // Log first few points for analysis
      for (int i = 0; i < Math.min(5, swipePath.size()); i++) {
        android.graphics.PointF p = swipePath.get(i);
        android.util.Log.e("Keyboard2", "  Point " + i + ": (" + p.x + "," + p.y + ")");
      }
    }
    
    // CRITICAL: Log detected keys to compare with calibration
    android.util.Log.e("Keyboard2", "🔤 DETECTED KEYS:");
    StringBuilder detectedKeySeq = new StringBuilder();
    for (int i = 0; i < swipedKeys.size(); i++) {
      KeyboardData.Key key = swipedKeys.get(i);
      if (key != null) {
        // Use key's string representation for debugging
        String keyStr = key.toString();
        detectedKeySeq.append(keyStr).append(" ");
        android.util.Log.e("Keyboard2", "  Key " + i + ": " + keyStr);
      } else {
        android.util.Log.e("Keyboard2", "  Key " + i + ": NULL");
      }
    }
    android.util.Log.e("Keyboard2", "🔤 KEY SEQUENCE: " + detectedKeySeq.toString());
    
    if (!_config.swipe_typing_enabled)
    {
      android.util.Log.e("Keyboard2", "❌ SWIPE TYPING DISABLED IN CONFIG");
      return;
    } else {
      android.util.Log.e("Keyboard2", "✅ Swipe typing enabled, proceeding...");
    }
    
    if (_neuralEngine == null)
    {
      // Fallback to word predictor if engine not initialized
      if (_wordPredictor == null)
      {
        android.util.Log.d("Keyboard2", "No prediction engine available");
        return;
      }
      // Initialize engine on the fly
      _neuralEngine = new NeuralSwipeTypingEngine(this, _config);
      
      // Initialize async handler if not already done
      if (_asyncPredictionHandler == null)
      {
        _asyncPredictionHandler = new AsyncPredictionHandler(_neuralEngine);
      }
      
      if (_keyboardView != null)
      {
        _neuralEngine.setKeyboardDimensions(_keyboardView.getWidth(), _keyboardView.getHeight());
      }
    }
    
    // Mark that last input was a swipe for ML data collection
    _wasLastInputSwipe = true;
    
    // Prepare ML data (will be saved if user selects a prediction)
    android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
    _currentSwipeData = new SwipeMLData("", "user_selection",
                                        metrics.widthPixels, metrics.heightPixels,
                                        _keyboardView.getHeight());
    
    // Add swipe path points with timestamps
    if (swipePath != null && timestamps != null && swipePath.size() == timestamps.size())
    {
      for (int i = 0; i < swipePath.size(); i++)
      {
        android.graphics.PointF point = swipePath.get(i);
        long timestamp = timestamps.get(i);
        _currentSwipeData.addRawPoint(point.x, point.y, timestamp);
      }
    }
      
    // Build key sequence from swiped keys and add to ML data
    StringBuilder keySequence = new StringBuilder();
    for (KeyboardData.Key key : swipedKeys)
    {
      if (key != null && key.keys[0] != null)
      {
        KeyValue kv = key.keys[0];
        if (kv.getKind() == KeyValue.Kind.Char)
        {
          char c = kv.getChar();
          keySequence.append(c);
          // Add to ML data
          if (_currentSwipeData != null)
          {
            _currentSwipeData.addRegisteredKey(String.valueOf(c));
          }
        }
      }
    }
    
    android.util.Log.d("Keyboard2", "Key sequence: " + keySequence.toString());
    android.util.Log.d("Keyboard2", "Dictionary size: " + (_wordPredictor != null ? _wordPredictor.getDictionarySize() : 0));
    
    // Log to file for analysis
    if (_logWriter != null)
    {
      try
      {
        _logWriter.write("[" + new java.util.Date() + "] Swipe: " + keySequence.toString() + "\n");
        _logWriter.flush();
      }
      catch (IOException e)
      {
        android.util.Log.e("Keyboard2", "Failed to write to log: " + e.getMessage());
      }
    }
    
    if (keySequence.length() > 0)
    {
      // Create SwipeInput exactly like SwipeCalibrationActivity (empty swipedKeys)
      // This ensures neural system handles key detection internally for consistency
      SwipeInput swipeInput = new SwipeInput(swipePath != null ? swipePath : new ArrayList<>(),
                                            timestamps != null ? timestamps : new ArrayList<>(),
                                            new ArrayList<>()); // Empty like calibration
      
      // UNIFIED PREDICTION STRATEGY: All predictions wait for gesture completion
      // This matches SwipeCalibrationActivity behavior and eliminates premature predictions
      android.util.Log.d("Keyboard2", "🎯 Gesture completion: " + 
        (swipePath != null ? swipePath.size() : 0) + " points, requesting final prediction");
      
      // Cancel any pending predictions first
      if (_asyncPredictionHandler != null)
      {
        _asyncPredictionHandler.cancelPendingPredictions();
      }
      
      // Request predictions asynchronously - always done on gesture completion
      // which matches the calibration activity's ACTION_UP behavior
      if (_asyncPredictionHandler != null)
      {
        _asyncPredictionHandler.requestPredictions(swipeInput, new AsyncPredictionHandler.PredictionCallback()
        {
          @Override
          public void onPredictionsReady(List<String> predictions, List<Integer> scores)
          {
            // Process predictions on UI thread
            handlePredictionResults(predictions, scores);
          }
          
          @Override
          public void onPredictionError(String error)
          {
            android.util.Log.e("Keyboard2", "Prediction error: " + error);
            // Clear suggestions on error
            if (_suggestionBar != null)
            {
              _suggestionBar.clearSuggestions();
            }
          }
        });
      }
      else
      {
        // Fallback to synchronous prediction if async handler not available
        long startTime = System.currentTimeMillis();
        PredictionResult result = _neuralEngine.predict(swipeInput);
      long predictionTime = System.currentTimeMillis() - startTime;
      List<String> predictions = result.words;
      
      android.util.Log.d("Keyboard2", "Got " + predictions.size() + " predictions in " + predictionTime + "ms");
      if (predictions.size() > 0)
      {
        android.util.Log.d("Keyboard2", "Predictions: " + predictions.toString());
        android.util.Log.d("Keyboard2", "Scores: " + result.scores.toString());
      }
      else
      {
        android.util.Log.d("Keyboard2", "NO PREDICTIONS FOUND for sequence: " + keySequence.toString());
      }
      
      // Log predictions to file
      if (_logWriter != null)
      {
        try
        {
          _logWriter.write("  Predictions: " + predictions + " (" + predictionTime + "ms)\n");
          _logWriter.write("  Scores: " + result.scores + "\n");
          _logWriter.flush();
        }
        catch (IOException e)
        {
          android.util.Log.e("Keyboard2", "Failed to write predictions to log: " + e.getMessage());
        }
      }
      
      // Show suggestions in the bar
      if (_suggestionBar != null && !predictions.isEmpty())
      {
        android.util.Log.d("Keyboard2", "Setting suggestions in bar: " + predictions);
        _suggestionBar.setShowDebugScores(_config.swipe_show_debug_scores);
        _suggestionBar.setSuggestionsWithScores(predictions, result.scores);
        android.util.Log.d("Keyboard2", "===== SWIPE PREDICTION END =====");
        
        // Auto-commit the first suggestion if confidence is high
        if (predictions.size() > 0)
        {
          // For now, just show suggestions - user can tap to select
          // Could auto-commit the first word here if desired
        }
      }
      else
      {
        android.util.Log.d("Keyboard2", "_suggestionBar is " + (_suggestionBar == null ? "null" : "not null"));
        android.util.Log.d("Keyboard2", "predictions.isEmpty() = " + predictions.isEmpty());
        android.util.Log.d("Keyboard2", "===== SWIPE PREDICTION END (no display) =====");
      }
      } // Close fallback else block
    }
    else
    {
      android.util.Log.d("Keyboard2", "Empty key sequence");
    }
  }

  private View inflate_view(int layout)
  {
    return View.inflate(new ContextThemeWrapper(this, _config.theme), layout, null);
  }
  
  /**
   * CGR Prediction Integration Methods
   * These methods are called by the EnhancedSwipeGestureRecognizer to display predictions
   */
  
  /**
   * Update swipe predictions by checking keyboard view for CGR results
   */
  public void updateCGRPredictions()
  {
    if (_suggestionBar != null && _keyboardView != null)
    {
      List<String> cgrPredictions = _keyboardView.getCGRPredictions();
      if (!cgrPredictions.isEmpty())
      {
        _suggestionBar.setSuggestions(cgrPredictions);
        android.util.Log.d("Keyboard2", "Displaying CGR predictions: " + cgrPredictions.size() + " words - " + cgrPredictions);
      }
    }
  }
  
  /**
   * Check and update CGR predictions (call this periodically or on swipe events)
   */
  public void checkCGRPredictions()
  {
    if (_keyboardView != null && _suggestionBar != null)
    {
      // Enable always visible mode to prevent UI flickering
      _suggestionBar.setAlwaysVisible(true);
      
      List<String> cgrPredictions = _keyboardView.getCGRPredictions();
      boolean areFinal = _keyboardView.areCGRPredictionsFinal();
      
      if (!cgrPredictions.isEmpty())
      {
        _suggestionBar.setSuggestions(cgrPredictions);
        android.util.Log.d("Keyboard2", "Updated CGR predictions: " + cgrPredictions.size() + 
          " words (final: " + areFinal + ")");
      }
      else
      {
        // Show empty suggestions but keep bar visible
        _suggestionBar.setSuggestions(new ArrayList<>());
        android.util.Log.d("Keyboard2", "No CGR predictions - showing empty bar");
      }
    }
  }
  
  /**
   * Update swipe predictions in real-time during gesture (legacy method)
   */
  public void updateSwipePredictions(List<String> predictions)
  {
    if (_suggestionBar != null && predictions != null && !predictions.isEmpty())
    {
      _suggestionBar.setSuggestions(predictions);
      android.util.Log.d("Keyboard2", "CGR real-time predictions updated: " + predictions.size() + " words");
    }
  }
  
  /**
   * Complete swipe predictions after gesture ends (legacy method)
   */
  public void completeSwipePredictions(List<String> finalPredictions)
  {
    if (_suggestionBar != null && finalPredictions != null && !finalPredictions.isEmpty())
    {
      _suggestionBar.setSuggestions(finalPredictions);
      android.util.Log.d("Keyboard2", "CGR final predictions completed: " + finalPredictions.size() + " words");
    }
    else
    {
      android.util.Log.d("Keyboard2", "No CGR predictions to display");
    }
  }
  
  /**
   * Clear swipe predictions (legacy method)
   */
  public void clearSwipePredictions()
  {
    if (_suggestionBar != null)
    {
      // Don't actually clear - just show empty suggestions to keep bar visible
      _suggestionBar.setSuggestions(new ArrayList<>());
      android.util.Log.d("Keyboard2", "CGR predictions cleared (bar kept visible)");
    }
  }
  
  // Removed reloadCGRParameters method - causing crashes
}
