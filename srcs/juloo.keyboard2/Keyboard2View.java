package juloo.keyboard2;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.inputmethodservice.InputMethodService;
import android.os.Build.VERSION;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Keyboard2View extends View
  implements View.OnTouchListener, Pointers.IPointerEventHandler
{
  private KeyboardData _keyboard;

  /** The key holding the shift key is used to set shift state from
      autocapitalisation. */
  private KeyValue _shift_kv;
  private KeyboardData.Key _shift_key;

  /** Used to add fake pointers. */
  private KeyValue _compose_kv;
  private KeyboardData.Key _compose_key;

  private Pointers _pointers;

  private Pointers.Modifiers _mods;

  private static int _currentWhat = 0;

  private Config _config;
  
  private EnhancedSwipeGestureRecognizer _swipeRecognizer;
  private Paint _swipeTrailPaint;
  
  // Swipe typing integration
  private WordPredictor _wordPredictor;
  
  // CGR prediction storage
  private List<String> _cgrPredictions = new ArrayList<>();
  private boolean _cgrFinalPredictions = false;
  private Keyboard2 _keyboard2;

  private float _keyWidth;
  private float _mainLabelSize;
  private float _subLabelSize;
  private float _marginRight;
  private float _marginLeft;
  private float _marginBottom;
  private int _insets_left = 0;
  private int _insets_right = 0;
  private int _insets_bottom = 0;

  private Theme _theme;
  private Theme.Computed _tc;

  private static RectF _tmpRect = new RectF();

  enum Vertical
  {
    TOP,
    CENTER,
    BOTTOM
  }

  public Keyboard2View(Context context, AttributeSet attrs)
  {
    super(context, attrs);
    _theme = new Theme(getContext(), attrs);
    _config = Config.globalConfig();
    _pointers = new Pointers(this, _config);
    _swipeRecognizer = _pointers._swipeRecognizer; // Share the recognizer
    
    // Initialize CGR prediction system now that we have context
    if (_swipeRecognizer != null)
    {
      _swipeRecognizer.initializePredictionSystem(getContext());
      
      // Connect CGR predictions to UI (note: suggestion bar will be available via parent service)
      _swipeRecognizer.setOnSwipePredictionListener(new EnhancedSwipeGestureRecognizer.OnSwipePredictionListener()
      {
        @Override
        public void onSwipePredictionUpdate(List<String> predictions)
        {
          // DISABLED: No real-time predictions during swipe
          // All predictions now wait for gesture completion like SwipeCalibrationActivity
          android.util.Log.d("Keyboard2View", "CGR real-time predictions DISABLED - waiting for completion");
        }
        
        @Override
        public void onSwipePredictionComplete(List<String> finalPredictions)
        {
          android.util.Log.d("Keyboard2View", "CGR final predictions: " + finalPredictions.size() + " words");
          // Store final predictions
          storeCGRPredictions(finalPredictions, true);
        }
        
        @Override
        public void onSwipePredictionCleared()
        {
          android.util.Log.d("Keyboard2View", "CGR predictions cleared");
          // Clear stored predictions
          clearCGRPredictions();
        }
      });
      
      android.util.Log.d("Keyboard2View", "CGR prediction system initialized with UI integration");
    }
    
    initSwipeTrailPaint();
    refresh_navigation_bar(context);
    setOnTouchListener(this);
    int layout_id = (attrs == null) ? 0 :
      attrs.getAttributeResourceValue(null, "layout", 0);
    if (layout_id == 0)
      reset();
    else
      setKeyboard(KeyboardData.load(getResources(), layout_id));
  }
  
  private void initSwipeTrailPaint()
  {
    _swipeTrailPaint = new Paint();
    _swipeTrailPaint.setColor(0xFF1976D2); // Default blue color
    _swipeTrailPaint.setStrokeWidth(3.0f * getResources().getDisplayMetrics().density);
    _swipeTrailPaint.setStyle(Paint.Style.STROKE);
    _swipeTrailPaint.setAntiAlias(true);
    _swipeTrailPaint.setStrokeCap(Paint.Cap.ROUND);
    _swipeTrailPaint.setStrokeJoin(Paint.Join.ROUND);
    _swipeTrailPaint.setAlpha(180); // Semi-transparent
  }

  private Window getParentWindow(Context context)
  {
    if (context instanceof InputMethodService)
      return ((InputMethodService)context).getWindow().getWindow();
    if (context instanceof ContextWrapper)
      return getParentWindow(((ContextWrapper)context).getBaseContext());
    return null;
  }

  public void refresh_navigation_bar(Context context)
  {
    if (VERSION.SDK_INT < 21)
      return;
    // The intermediate Window is a [Dialog].
    Window w = getParentWindow(context);
    w.setNavigationBarColor(_theme.colorNavBar);
    if (VERSION.SDK_INT < 26)
      return;
    int uiFlags = getSystemUiVisibility();
    if (_theme.isLightNavBar)
      uiFlags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
    else
      uiFlags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
    setSystemUiVisibility(uiFlags);
  }

  public void setKeyboard(KeyboardData kw)
  {
    _keyboard = kw;
    _shift_kv = KeyValue.getKeyByName("shift");
    _shift_key = _keyboard.findKeyWithValue(_shift_kv);
    _compose_kv = KeyValue.getKeyByName("compose");
    _compose_key = _keyboard.findKeyWithValue(_compose_kv);
    KeyModifier.set_modmap(_keyboard.modmap);
    
    // Initialize CGR swipe recognizer if not already created
    if (_swipeRecognizer == null)
    {
      _swipeRecognizer = new EnhancedSwipeGestureRecognizer();
      _swipeRecognizer.initializePredictionSystem(getContext());
    }
    
    // Set keyboard for swipe recognizer's probabilistic detection  
    if (_swipeRecognizer != null && _keyboard != null)
    {
      DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
      // Parent class handles keyboard setup - no need for setKeyboardDimensions
    }
    
    reset();
  }

  public void reset()
  {
    _mods = Pointers.Modifiers.EMPTY;
    _pointers.clear();
    requestLayout();
    invalidate();
  }

  /**
   * Clear swipe typing state after suggestion selection
   */
  public void clearSwipeState()
  {
    // Clear any ongoing swipe gestures
    _pointers.clear();
    invalidate();
  }

  void set_fake_ptr_latched(KeyboardData.Key key, KeyValue kv, boolean latched,
      boolean lock)
  {
    if (_keyboard == null || key == null)
      return;
    _pointers.set_fake_pointer_state(key, kv, latched, lock);
  }

  /** Called by auto-capitalisation. */
  public void set_shift_state(boolean latched, boolean lock)
  {
    set_fake_ptr_latched(_shift_key, _shift_kv, latched, lock);
  }

  /** Called from [KeyEventHandler]. */
  public void set_compose_pending(boolean pending)
  {
    set_fake_ptr_latched(_compose_key, _compose_kv, pending, false);
  }

  /** Called from [Keybard2.onUpdateSelection].  */
  public void set_selection_state(boolean selection_state)
  {
    set_fake_ptr_latched(KeyboardData.Key.EMPTY,
        KeyValue.getKeyByName("selection_mode"), selection_state, true);
  }

  public KeyValue modifyKey(KeyValue k, Pointers.Modifiers mods)
  {
    return KeyModifier.modify(k, mods);
  }

  public void onPointerDown(KeyValue k, boolean isSwipe)
  {
    updateFlags();
    _config.handler.key_down(k, isSwipe);
    invalidate();
    vibrate();
  }

  public void onPointerUp(KeyValue k, Pointers.Modifiers mods)
  {
    // [key_up] must be called before [updateFlags]. The latter might disable
    // flags.
    _config.handler.key_up(k, mods);
    updateFlags();
    invalidate();
  }

  public void onPointerHold(KeyValue k, Pointers.Modifiers mods)
  {
    _config.handler.key_up(k, mods);
    updateFlags();
  }

  public void onPointerFlagsChanged(boolean shouldVibrate)
  {
    updateFlags();
    invalidate();
    if (shouldVibrate)
      vibrate();
  }

  private void updateFlags()
  {
    _mods = _pointers.getModifiers();
    _config.handler.mods_changed(_mods);
  }
  
  public void onSwipeMove(float x, float y, ImprovedSwipeGestureRecognizer recognizer)
  {
    KeyboardData.Key key = getKeyAtPosition(x, y);
    recognizer.addPoint(x, y, key);
    if (recognizer.isSwipeTyping())
    {
      invalidate(); // Redraw to show swipe trail
    }
  }
  
  public void onSwipeEnd(ImprovedSwipeGestureRecognizer recognizer)
  {
    if (recognizer.isSwipeTyping())
    {
      ImprovedSwipeGestureRecognizer.SwipeResult result = recognizer.endSwipe();
      if (_keyboard2 != null && result != null && result.keys != null && !result.keys.isEmpty())
      {
        // Pass full swipe data for ML collection
        _keyboard2.handleSwipeTyping(result.keys, result.path, result.timestamps);
      }
    }
    else
    {
      recognizer.endSwipe(); // Clean up even if not swipe typing
    }
    recognizer.reset();
    invalidate(); // Clear the trail
  }
  
  public void setSwipeTypingComponents(WordPredictor predictor, Keyboard2 keyboard2)
  {
    _wordPredictor = predictor;
    _keyboard2 = keyboard2;
  }
  
  /**
   * Extract real key positions for accurate coordinate mapping
   * Returns map of character to actual center coordinates
   */
  public java.util.Map<Character, android.graphics.PointF> getRealKeyPositions()
  {
    java.util.Map<Character, android.graphics.PointF> keyPositions = new java.util.HashMap<>();
    
    if (_keyboard == null || _tc == null) {
      android.util.Log.w("Keyboard2View", "Cannot extract key positions - layout not ready");
      return keyPositions;
    }
    
    float y = _config.marginTop;
    
    for (KeyboardData.Row row : _keyboard.rows)
    {
      float x = _marginLeft;
      
      for (KeyboardData.Key key : row.keys)
      {
        float xLeft = x + key.shift * _keyWidth;
        float xRight = xLeft + key.width * _keyWidth;
        float yTop = y + row.shift * _tc.row_height;
        float yBottom = yTop + row.height * _tc.row_height;
        
        // Calculate center coordinates
        float centerX = (xLeft + xRight) / 2f;
        float centerY = (yTop + yBottom) / 2f;
        
        // Extract character from key (if alphabetic) using simplified approach
        try {
          String keyString = key.toString();
          if (keyString != null && keyString.length() == 1 && Character.isLetter(keyString.charAt(0))) {
            char keyChar = keyString.toLowerCase().charAt(0);
            keyPositions.put(keyChar, new android.graphics.PointF(centerX, centerY));
            android.util.Log.d("KeyPositions", "Real position: '" + keyChar + "' = (" + centerX + "," + centerY + ")");
          }
        } catch (Exception e) {
          // Skip keys that can't be extracted
        }
        
        x = xRight;
      }
      
      y += (row.shift + row.height) * _tc.row_height;
    }
    
    android.util.Log.i("KeyPositions", "Extracted " + keyPositions.size() + " real key positions");
    return keyPositions;
  }

  @Override
  public boolean onTouch(View v, MotionEvent event)
  {
    // GESTURE INTERRUPTION DEBUGGING - log events that might segment swipes
    int action = event.getActionMasked();
    if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
      android.util.Log.e("SwipeDebug", "🎯 KEY EVENT: " + MotionEvent.actionToString(action) + 
        " at (" + event.getX() + "," + event.getY() + ") viewHeight=" + getHeight());
    }
    
    int p;
    switch (action)
    {
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_POINTER_UP:
        _pointers.onTouchUp(event.getPointerId(event.getActionIndex()));
        break;
      case MotionEvent.ACTION_DOWN:
      case MotionEvent.ACTION_POINTER_DOWN:
        p = event.getActionIndex();
        float tx = event.getX(p);
        float ty = event.getY(p);
        KeyboardData.Key key = getKeyAtPosition(tx, ty);
        if (key != null)
          _pointers.onTouchDown(tx, ty, event.getPointerId(p), key);
        break;
      case MotionEvent.ACTION_MOVE:
        for (p = 0; p < event.getPointerCount(); p++)
          _pointers.onTouchMove(event.getX(p), event.getY(p), event.getPointerId(p));
        break;
      case MotionEvent.ACTION_CANCEL:
        _pointers.onTouchCancel();
        break;
      default:
        return (false);
    }
    return (true);
  }

  private KeyboardData.Row getRowAtPosition(float ty)
  {
    float y = _config.marginTop;
    android.util.Log.e("KeyDetection", "🔍 COORDINATE DEBUG: touch_y=" + ty + ", marginTop=" + y + ", viewHeight=" + getHeight() + ", rowHeight=" + _tc.row_height);
    
    if (ty < y) {
      android.util.Log.v("KeyDetection", "❌ Y too small: " + ty + " < " + y);
      return null;
    }
    
    int rowIndex = 0;
    for (KeyboardData.Row row : _keyboard.rows)
    {
      float rowBottom = y + (row.shift + row.height) * _tc.row_height;
      android.util.Log.v("KeyDetection", "Row " + rowIndex + ": y=" + y + "-" + rowBottom);
      
      if (ty < rowBottom) {
        android.util.Log.v("KeyDetection", "✅ Found row " + rowIndex + " for y=" + ty);
        return row;
      }
      y = rowBottom;
      rowIndex++;
    }
    
    android.util.Log.v("KeyDetection", "❌ No row found for y=" + ty + " (past last row)");
    return null;
  }

  private KeyboardData.Key getKeyAtPosition(float tx, float ty)
  {
    KeyboardData.Row row = getRowAtPosition(ty);
    float x = _marginLeft;
    if (row == null) {
      android.util.Log.e("KeyDetection", "❌ No row found for y=" + ty + " (marginTop=" + _config.marginTop + ")");
      return null;
    }
    
    // Log coordinate mapping for debugging
    android.util.Log.v("KeyDetection", "🎯 Touch at (" + tx + "," + ty + ") → row found, checking keys...");
    
    // Check if this row contains 'a' and 'l' keys (middle letter row in QWERTY)
    boolean hasAAndLKeys = rowContainsAAndL(row);
    KeyboardData.Key aKey = null;
    KeyboardData.Key lKey = null;
    
    if (hasAAndLKeys) {
      // Find the 'a' and 'l' keys in this row
      for (KeyboardData.Key key : row.keys) {
        if (isCharacterKey(key, 'a')) aKey = key;
        if (isCharacterKey(key, 'l')) lKey = key;
      }
    }
    
    // Check if touch is before the first key and we have 'a' key - extend its touch zone
    if (tx < x && aKey != null) {
      return aKey;
    }
    
    if (tx < x)
      return null;
      
    for (KeyboardData.Key key : row.keys)
    {
      float xLeft = x + key.shift * _keyWidth;
      float xRight = xLeft + key.width * _keyWidth;
      
      // Log 'a' key coordinates for debugging
      if (isCharacterKey(key, 'a')) {
        android.util.Log.e("KeyDetection", "📍 'A' KEY POSITION: x=" + xLeft + "-" + xRight + ", y=row, touch=(" + tx + "," + ty + ")");
      }
      
      if (tx < xLeft)
        return null;
      if (tx < xRight) {
        android.util.Log.v("KeyDetection", "✅ Found key at (" + tx + "," + ty + ") → " + key.toString());
        return key;
      }
      x = xRight;
    }
    
    // Check if touch is after the last key and we have 'l' key - extend its touch zone
    if (lKey != null) {
      return lKey;
    }
    
    return null;
  }
  
  /**
   * Check if this row contains both 'a' and 'l' keys (the middle QWERTY row)
   */
  private boolean rowContainsAAndL(KeyboardData.Row row) {
    boolean hasA = false;
    boolean hasL = false;
    for (KeyboardData.Key key : row.keys) {
      if (isCharacterKey(key, 'a')) hasA = true;
      if (isCharacterKey(key, 'l')) hasL = true;
      if (hasA && hasL) return true;
    }
    return false;
  }
  
  /**
   * Check if a key represents the specified character
   */
  private boolean isCharacterKey(KeyboardData.Key key, char character) {
    if (key.keys[0] == null) 
      return false;
    KeyValue kv = key.keys[0];
    return kv != null && kv.getKind() == KeyValue.Kind.Char && 
           kv.getChar() == character;
  }

  private void vibrate()
  {
    VibratorCompat.vibrate(this, _config);
  }

  @Override
  public void onMeasure(int wSpec, int hSpec)
  {
    int width;
    // FIXED: Use actual measured width instead of screen width (layout calculation bug)
    width = MeasureSpec.getSize(wSpec);
    android.util.Log.d("Keyboard2View", "Layout fix: using measured width " + width + " instead of screen width");
    _marginLeft = Math.max(_config.horizontal_margin, _insets_left);
    _marginRight = Math.max(_config.horizontal_margin, _insets_right);
    _marginBottom = _config.margin_bottom + _insets_bottom;
    _keyWidth = (width - _marginLeft - _marginRight) / _keyboard.keysWidth;
    _tc = new Theme.Computed(_theme, _config, _keyWidth, _keyboard);
    // Compute the size of labels based on the width or the height of keys. The
    // margin around keys is taken into account. Keys normal aspect ratio is
    // assumed to be 3/2 for a 10 columns layout. It's generally more, the
    // width computation is useful when the keyboard is unusually high.
    float labelBaseSize = Math.min(
        _tc.row_height - _tc.vertical_margin,
        (width / 10 - _tc.horizontal_margin) * 3/2
        ) * _config.characterSize;
    _mainLabelSize = labelBaseSize * _config.labelTextSize;
    _subLabelSize = labelBaseSize * _config.sublabelTextSize;
    int height =
      (int)(_tc.row_height * _keyboard.keysHeight
          + _config.marginTop + _marginBottom);
    setMeasuredDimension(width, height);
  }

  @Override
  public void onLayout(boolean changed, int left, int top, int right, int bottom)
  {
    if (!changed)
      return;
    if (VERSION.SDK_INT >= 29)
    {
      // Disable the back-gesture on the keyboard area
      Rect keyboard_area = new Rect(
          left + (int)_marginLeft,
          top + (int)_config.marginTop,
          right - (int)_marginRight,
          bottom - (int)_marginBottom);
      setSystemGestureExclusionRects(Arrays.asList(keyboard_area));
    }
  }

  @Override
  public WindowInsets onApplyWindowInsets(WindowInsets wi)
  {
    // LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS is set in [Keyboard2#updateSoftInputWindowLayoutParams] for SDK_INT >= 35.
    if (VERSION.SDK_INT < 35)
      return wi;
    int insets_types =
      WindowInsets.Type.systemBars()
      | WindowInsets.Type.displayCutout();
    Insets insets = wi.getInsets(insets_types);
    _insets_left = insets.left;
    _insets_right = insets.right;
    _insets_bottom = insets.bottom;
    return WindowInsets.CONSUMED;
  }

  /** Horizontal and vertical position of the 9 indexes. */
  static final Paint.Align[] LABEL_POSITION_H = new Paint.Align[]{
    Paint.Align.CENTER, Paint.Align.LEFT, Paint.Align.RIGHT, Paint.Align.LEFT,
    Paint.Align.RIGHT, Paint.Align.LEFT, Paint.Align.RIGHT,
    Paint.Align.CENTER, Paint.Align.CENTER
  };

  static final Vertical[] LABEL_POSITION_V = new Vertical[]{
    Vertical.CENTER, Vertical.TOP, Vertical.TOP, Vertical.BOTTOM,
    Vertical.BOTTOM, Vertical.CENTER, Vertical.CENTER, Vertical.TOP,
    Vertical.BOTTOM
  };

  @Override
  protected void onDraw(Canvas canvas)
  {
    // Set keyboard background opacity
    getBackground().setAlpha(_config.keyboardOpacity);
    float y = _tc.margin_top;
    for (KeyboardData.Row row : _keyboard.rows)
    {
      y += row.shift * _tc.row_height;
      float x = _marginLeft + _tc.margin_left;
      float keyH = row.height * _tc.row_height - _tc.vertical_margin;
      for (KeyboardData.Key k : row.keys)
      {
        x += k.shift * _keyWidth;
        float keyW = _keyWidth * k.width - _tc.horizontal_margin;
        boolean isKeyDown = _pointers.isKeyDown(k);
        Theme.Computed.Key tc_key = isKeyDown ? _tc.key_activated : _tc.key;
        drawKeyFrame(canvas, x, y, keyW, keyH, tc_key);
        if (k.keys[0] != null)
          drawLabel(canvas, k.keys[0], keyW / 2f + x, y, keyH, isKeyDown, tc_key);
        for (int i = 1; i < 9; i++)
        {
          if (k.keys[i] != null)
            drawSubLabel(canvas, k.keys[i], x, y, keyW, keyH, i, isKeyDown, tc_key);
        }
        drawIndication(canvas, k, x, y, keyW, keyH, _tc);
        x += _keyWidth * k.width;
      }
      y += row.height * _tc.row_height;
    }
    
    // Draw swipe trail if swipe typing is enabled and active
    if (_config.swipe_typing_enabled && _swipeRecognizer != null && _swipeRecognizer.isSwipeTyping())
    {
      drawSwipeTrail(canvas);
    }
  }
  
  private void drawSwipeTrail(Canvas canvas)
  {
    List<PointF> swipePath = _swipeRecognizer.getSwipePath();
    if (swipePath.size() < 2)
      return;
      
    Path path = new Path();
    PointF firstPoint = swipePath.get(0);
    path.moveTo(firstPoint.x, firstPoint.y);
    
    for (int i = 1; i < swipePath.size(); i++)
    {
      PointF point = swipePath.get(i);
      path.lineTo(point.x, point.y);
    }
    
    canvas.drawPath(path, _swipeTrailPaint);
  }

  @Override
  public void onDetachedFromWindow()
  {
    super.onDetachedFromWindow();
  }

  /** Draw borders and background of the key. */
  void drawKeyFrame(Canvas canvas, float x, float y, float keyW, float keyH,
      Theme.Computed.Key tc)
  {
    float r = tc.border_radius;
    float w = tc.border_width;
    float padding = w / 2.f;
    _tmpRect.set(x + padding, y + padding, x + keyW - padding, y + keyH - padding);
    canvas.drawRoundRect(_tmpRect, r, r, tc.bg_paint);
    if (w > 0.f)
    {
      float overlap = r - r * 0.85f + w; // sin(45°)
      drawBorder(canvas, x, y, x + overlap, y + keyH, tc.border_left_paint, tc);
      drawBorder(canvas, x + keyW - overlap, y, x + keyW, y + keyH, tc.border_right_paint, tc);
      drawBorder(canvas, x, y, x + keyW, y + overlap, tc.border_top_paint, tc);
      drawBorder(canvas, x, y + keyH - overlap, x + keyW, y + keyH, tc.border_bottom_paint, tc);
    }
  }

  /** Clip to draw a border at a time. This allows to call [drawRoundRect]
      several time with the same parameters but a different Paint. */
  void drawBorder(Canvas canvas, float clipl, float clipt, float clipr,
      float clipb, Paint paint, Theme.Computed.Key tc)
  {
    float r = tc.border_radius;
    canvas.save();
    canvas.clipRect(clipl, clipt, clipr, clipb);
    canvas.drawRoundRect(_tmpRect, r, r, paint);
    canvas.restore();
  }

  private int labelColor(KeyValue k, boolean isKeyDown, boolean sublabel)
  {
    if (isKeyDown)
    {
      int flags = _pointers.getKeyFlags(k);
      if (flags != -1)
      {
        if ((flags & Pointers.FLAG_P_LOCKED) != 0)
          return _theme.lockedColor;
        return _theme.activatedColor;
      }
    }
    if (k.hasFlagsAny(KeyValue.FLAG_SECONDARY | KeyValue.FLAG_GREYED))
    {
      if (k.hasFlagsAny(KeyValue.FLAG_GREYED))
        return _theme.greyedLabelColor;
      return _theme.secondaryLabelColor;
    }
    return sublabel ? _theme.subLabelColor : _theme.labelColor;
  }

  private void drawLabel(Canvas canvas, KeyValue kv, float x, float y,
      float keyH, boolean isKeyDown, Theme.Computed.Key tc)
  {
    kv = modifyKey(kv, _mods);
    if (kv == null)
      return;
    float textSize = scaleTextSize(kv, true);
    Paint p = tc.label_paint(kv.hasFlagsAny(KeyValue.FLAG_KEY_FONT), labelColor(kv, isKeyDown, false), textSize);
    canvas.drawText(kv.getString(), x, (keyH - p.ascent() - p.descent()) / 2f + y, p);
  }

  private void drawSubLabel(Canvas canvas, KeyValue kv, float x, float y,
      float keyW, float keyH, int sub_index, boolean isKeyDown,
      Theme.Computed.Key tc)
  {
    Paint.Align a = LABEL_POSITION_H[sub_index];
    Vertical v = LABEL_POSITION_V[sub_index];
    kv = modifyKey(kv, _mods);
    if (kv == null)
      return;
    float textSize = scaleTextSize(kv, false);
    Paint p = tc.sublabel_paint(kv.hasFlagsAny(KeyValue.FLAG_KEY_FONT), labelColor(kv, isKeyDown, true), textSize, a);
    float subPadding = _config.keyPadding;
    if (v == Vertical.CENTER)
      y += (keyH - p.ascent() - p.descent()) / 2f;
    else
      y += (v == Vertical.TOP) ? subPadding - p.ascent() : keyH - subPadding - p.descent();
    if (a == Paint.Align.CENTER)
      x += keyW / 2f;
    else
      x += (a == Paint.Align.LEFT) ? subPadding : keyW - subPadding;
    String label = kv.getString();
    int label_len = label.length();
    // Limit the label of string keys to 3 characters
    if (label_len > 3 && kv.getKind() == KeyValue.Kind.String)
      label_len = 3;
    canvas.drawText(label, 0, label_len, x, y, p);
  }

  private void drawIndication(Canvas canvas, KeyboardData.Key k, float x,
      float y, float keyW, float keyH, Theme.Computed tc)
  {
    if (k.indication == null || k.indication.equals(""))
      return;
    Paint p = tc.indication_paint;
    p.setTextSize(_subLabelSize);
    canvas.drawText(k.indication, 0, k.indication.length(),
        x + keyW / 2f, (keyH - p.ascent() - p.descent()) * 4/5 + y, p);
  }

  private float scaleTextSize(KeyValue k, boolean main_label)
  {
    float smaller_font = k.hasFlagsAny(KeyValue.FLAG_SMALLER_FONT) ? 0.75f : 1.f;
    float label_size = main_label ? _mainLabelSize : _subLabelSize;
    return label_size * smaller_font;
  }
  
  public Theme getTheme()
  {
    return _theme;
  }
  
  /**
   * Find the key at the given coordinates
   */
  private KeyboardData.Key getKeyAt(float x, float y)
  {
    if (_keyboard == null)
      return null;
    
    float yPos = _tc.margin_top;
    for (KeyboardData.Row row : _keyboard.rows)
    {
      yPos += row.shift * _tc.row_height;
      float keyH = row.height * _tc.row_height - _tc.vertical_margin;
      
      // Check if y coordinate is within this row
      if (y >= yPos && y < yPos + keyH)
      {
        float xPos = _marginLeft + _tc.margin_left;
        for (KeyboardData.Key key : row.keys)
        {
          xPos += key.shift * _keyWidth;
          float keyW = _keyWidth * key.width - _tc.horizontal_margin;
          
          // Check if x coordinate is within this key
          if (x >= xPos && x < xPos + keyW)
          {
            return key;
          }
          xPos += _keyWidth * key.width;
        }
        break; // Y is in this row but X didn't match any key
      }
      yPos += row.height * _tc.row_height;
    }
    return null;
  }
  
  /**
   * CGR Prediction Support Methods
   */
  
  /**
   * Store CGR predictions and immediately display them
   */
  private void storeCGRPredictions(List<String> predictions, boolean isFinal)
  {
    _cgrPredictions.clear();
    if (predictions != null)
    {
      _cgrPredictions.addAll(predictions);
    }
    _cgrFinalPredictions = isFinal;
    
    android.util.Log.d("Keyboard2View", "Stored " + _cgrPredictions.size() + 
      " CGR predictions (final: " + isFinal + "): " + _cgrPredictions);
    
    // Immediately trigger display update by calling parent service method
    post(() -> {
      try
      {
        // Find the parent Keyboard2 service and update predictions
        Context context = getContext();
        while (context instanceof ContextWrapper && !(context instanceof Keyboard2))
        {
          context = ((ContextWrapper) context).getBaseContext();
        }
        if (context instanceof Keyboard2)
        {
          ((Keyboard2) context).checkCGRPredictions();
        }
      }
      catch (Exception e)
      {
        android.util.Log.e("Keyboard2View", "Failed to update CGR predictions: " + e.getMessage());
      }
    });
  }
  
  /**
   * Clear CGR predictions
   */
  private void clearCGRPredictions()
  {
    _cgrPredictions.clear();
    _cgrFinalPredictions = false;
    android.util.Log.d("Keyboard2View", "Cleared CGR predictions");
  }
  
  /**
   * Get current CGR predictions (for access by keyboard service)
   */
  public List<String> getCGRPredictions()
  {
    return new ArrayList<>(_cgrPredictions);
  }
  
  /**
   * Check if CGR predictions are final (persisting)
   */
  public boolean areCGRPredictionsFinal()
  {
    return _cgrFinalPredictions;
  }
  
  // Removed reloadCGRSystem method - causing crashes
}
