package juloo.keyboard2;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * View component that displays word suggestions above the keyboard
 */
public class SuggestionBar extends LinearLayout
{
  private final List<TextView> _suggestionViews;
  private OnSuggestionSelectedListener _listener;
  private List<String> _currentSuggestions;
  private List<Integer> _currentScores;
  private int _selectedIndex = -1;
  private Theme _theme;
  private boolean _showDebugScores = false;
  private int _opacity = 90; // default opacity
  private boolean _alwaysVisible = true; // Keep bar visible even when empty (default enabled)
  
  public interface OnSuggestionSelectedListener
  {
    void onSuggestionSelected(String word);
  }
  
  public SuggestionBar(Context context)
  {
    this(context, (AttributeSet)null);
  }
  
  public SuggestionBar(Context context, Theme theme)
  {
    super(context);
    _suggestionViews = new ArrayList<>();
    _currentSuggestions = new ArrayList<>();
    _currentScores = new ArrayList<>();
    _theme = theme;
    initialize(context);
  }
  
  public SuggestionBar(Context context, AttributeSet attrs)
  {
    super(context, attrs);
    _suggestionViews = new ArrayList<>();
    _currentSuggestions = new ArrayList<>();
    _currentScores = new ArrayList<>();
    // Initialize theme to get colors
    _theme = new Theme(context, attrs);
    initialize(context);
  }
  
  private void initialize(Context context)
  {
    setOrientation(HORIZONTAL);
    setGravity(Gravity.CENTER_VERTICAL);
    
    updateBackgroundOpacity();
    
    
    int padding = dpToPx(context, 8);
    setPadding(padding, padding, padding, padding);
    
    // Create suggestion text views
    for (int i = 0; i < 5; i++)
    {
      TextView suggestionView = createSuggestionView(context, i);
      _suggestionViews.add(suggestionView);
      
      // Add divider except for the last item
      if (i < 4)
      {
        View divider = createDivider(context);
        addView(divider);
      }
    }
  }
  
  private TextView createSuggestionView(Context context, final int index)
  {
    TextView textView = new TextView(context);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
      0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
    textView.setLayoutParams(params);
    textView.setGravity(Gravity.CENTER);
    textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
    // Use theme label color for text with fallback
    if (_theme != null && _theme.labelColor != 0)
    {
      textView.setTextColor(_theme.labelColor);
    }
    else
    {
      // Fallback to white text if theme not initialized
      textView.setTextColor(Color.WHITE);
    }
    textView.setPadding(dpToPx(context, 8), 0, dpToPx(context, 8), 0);
    textView.setMaxLines(2);
    textView.setClickable(true);
    textView.setFocusable(true);
    
    // Set click listener
    textView.setOnClickListener(new OnClickListener()
    {
      @Override
      public void onClick(View v)
      {
        if (index < _currentSuggestions.size() && _listener != null)
        {
          _listener.onSuggestionSelected(_currentSuggestions.get(index));
        }
      }
    });
    
    addView(textView);
    return textView;
  }
  
  private View createDivider(Context context)
  {
    View divider = new View(context);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
      dpToPx(context, 1), ViewGroup.LayoutParams.MATCH_PARENT);
    params.setMargins(0, dpToPx(context, 4), 0, dpToPx(context, 4));
    divider.setLayoutParams(params);
    // Use theme sublabel color with some transparency for divider
    int dividerColor = _theme.subLabelColor;
    dividerColor = Color.argb(100, Color.red(dividerColor), 
                              Color.green(dividerColor), 
                              Color.blue(dividerColor));
    divider.setBackgroundColor(dividerColor);
    return divider;
  }
  
  /**
   * Set whether to show debug scores
   */
  public void setShowDebugScores(boolean show)
  {
    _showDebugScores = show;
  }
  
  /**
   * Set whether the suggestion bar should always remain visible
   * This prevents UI rerendering issues from constant appear/disappear
   */
  public void setAlwaysVisible(boolean alwaysVisible)
  {
    _alwaysVisible = alwaysVisible;
    if (_alwaysVisible)
    {
      setVisibility(View.VISIBLE);
    }
  }
  
  /**
   * Set the opacity of the suggestion bar
   * @param opacity Opacity value from 0 to 100
   */
  public void setOpacity(int opacity)
  {
    _opacity = Math.max(0, Math.min(100, opacity));
    updateBackgroundOpacity();
  }
  
  /**
   * Update the background color with the current opacity
   */
  private void updateBackgroundOpacity()
  {
    // Calculate alpha value from opacity percentage (0-100 -> 0-255)
    int alpha = (_opacity * 255) / 100;
    
    // Use theme colors with user-defined opacity
    if (_theme != null && _theme.colorKey != 0)
    {
      int backgroundColor = _theme.colorKey;
      backgroundColor = Color.argb(alpha, Color.red(backgroundColor), 
                                   Color.green(backgroundColor), 
                                   Color.blue(backgroundColor));
      setBackgroundColor(backgroundColor);
    }
    else
    {
      // Fallback colors if theme is not properly initialized
      setBackgroundColor(Color.argb(alpha, 50, 50, 50)); // Dark grey background
    }
  }
  
  /**
   * Update the displayed suggestions
   */
  public void setSuggestions(List<String> suggestions)
  {
    setSuggestionsWithScores(suggestions, null);
  }
  
  /**
   * Update the displayed suggestions with scores
   */
  public void setSuggestionsWithScores(List<String> suggestions, List<Integer> scores)
  {
    _currentSuggestions.clear();
    _currentScores.clear();
    if (suggestions != null)
    {
      _currentSuggestions.addAll(suggestions);
      if (scores != null && scores.size() == suggestions.size())
      {
        _currentScores.addAll(scores);
      }
    }
    
    // Update text views
    for (int i = 0; i < _suggestionViews.size(); i++)
    {
      TextView textView = _suggestionViews.get(i);
      if (i < _currentSuggestions.size())
      {
        String suggestion = _currentSuggestions.get(i);
        
        // Add debug score if enabled and available
        if (_showDebugScores && i < _currentScores.size() && !_currentScores.isEmpty())
        {
          int score = _currentScores.get(i);
          suggestion = suggestion + "\n" + score;
        }
        
        textView.setText(suggestion);
        textView.setVisibility(View.VISIBLE);
        
        // Highlight first suggestion with activated color
        if (i == 0)
        {
          textView.setTypeface(Typeface.DEFAULT_BOLD);
          textView.setTextColor(_theme != null && _theme.activatedColor != 0 ? _theme.activatedColor : Color.CYAN);
        }
        else
        {
          textView.setTypeface(Typeface.DEFAULT);
          textView.setTextColor(_theme != null && _theme.labelColor != 0 ? _theme.labelColor : Color.WHITE);
        }
      }
      else
      {
        textView.setText("");
        textView.setVisibility(View.GONE);
      }
    }
    
    // Show or hide the entire bar based on suggestions (unless always visible mode)
    if (_alwaysVisible)
    {
      setVisibility(View.VISIBLE); // Always keep visible to prevent UI rerendering
    }
    else
    {
      setVisibility(_currentSuggestions.isEmpty() ? View.GONE : View.VISIBLE);
    }
  }
  
  /**
   * Clear all suggestions (MODIFIED: always keep bar visible when CGR active)
   */
  public void clearSuggestions()
  {
    // ALWAYS show empty suggestions instead of hiding - prevents UI disappearing
    setSuggestions(new ArrayList<>());
    android.util.Log.d("SuggestionBar", "clearSuggestions called - showing empty list instead of hiding");
  }
  
  /**
   * Set the listener for suggestion selection
   */
  public void setOnSuggestionSelectedListener(OnSuggestionSelectedListener listener)
  {
    _listener = listener;
  }
  
  /**
   * Get the currently displayed suggestions
   */
  public List<String> getCurrentSuggestions()
  {
    return new ArrayList<>(_currentSuggestions);
  }
  
  /**
   * Convert dp to pixels
   */
  private int dpToPx(Context context, int dp)
  {
    float density = context.getResources().getDisplayMetrics().density;
    return Math.round(dp * density);
  }
}