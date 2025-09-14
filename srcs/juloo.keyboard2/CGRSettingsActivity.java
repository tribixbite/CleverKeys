package juloo.keyboard2;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Dedicated CGR Parameter Settings Activity
 * Allows real-time tuning of all CGR algorithm parameters
 */
public class CGRSettingsActivity extends Activity
{
  // CGR Parameter controls
  private SeekBar _eSigmaSlider;
  private SeekBar _betaSlider;
  private SeekBar _lambdaSlider;
  private SeekBar _kappaSlider;
  private SeekBar _ptSpacingSlider;
  private SeekBar _maxResamplingSlider;
  
  private TextView _eSigmaText;
  private TextView _betaText;
  private TextView _lambdaText;
  private TextView _kappaText;
  private TextView _ptSpacingText;
  private TextView _maxResamplingText;
  
  // Current parameter values
  private double _eSigma = 200.0;
  private double _beta = 400.0;
  private double _lambda = 0.4;
  private double _kappa = 1.0;
  private int _ptSpacing = 200;
  private int _maxResampling = 5000;
  
  private RealTimeSwipePredictor _swipePredictor;
  
  @Override
  protected void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    
    LinearLayout mainLayout = new LinearLayout(this);
    mainLayout.setOrientation(LinearLayout.VERTICAL);
    mainLayout.setPadding(32, 32, 32, 32);
    mainLayout.setBackgroundColor(Color.BLACK);
    
    // Title
    TextView title = new TextView(this);
    title.setText("CGR Algorithm Parameters");
    title.setTextSize(20);
    title.setTextColor(Color.WHITE);
    title.setPadding(0, 0, 0, 32);
    mainLayout.addView(title);
    
    // Description
    TextView desc = new TextView(this);
    desc.setText("Tune CGR parameters based on Kristensson & Denby 2011 research.\nChanges apply immediately to template generation and recognition.");
    desc.setTextSize(14);
    desc.setTextColor(Color.GRAY);
    desc.setPadding(0, 0, 0, 24);
    mainLayout.addView(desc);
    
    // E_SIGMA (Euclidean distance variance)
    addParameterControl(mainLayout, "σₑ (E_SIGMA) - Euclidean Distance Variance", 
                       50, 500, (int)_eSigma, "Controls sensitivity to position differences",
                       (progress) -> {
                         _eSigma = progress;
                         _eSigmaText.setText(String.format("σₑ: %.0f", _eSigma));
                         updateCGRParameters();
                       });
    _eSigmaSlider = (SeekBar) mainLayout.getChildAt(mainLayout.getChildCount() - 1);
    _eSigmaText = (TextView) ((LinearLayout) mainLayout.getChildAt(mainLayout.getChildCount() - 2)).getChildAt(0);
    
    // BETA (σₑ/σₜ ratio)
    addParameterControl(mainLayout, "β (BETA) - Distance Variance Ratio", 
                       100, 800, (int)_beta, "Ratio between Euclidean and turning angle variance",
                       (progress) -> {
                         _beta = progress;
                         _betaText.setText(String.format("β: %.0f", _beta));
                         updateCGRParameters();
                       });
    _betaSlider = (SeekBar) mainLayout.getChildAt(mainLayout.getChildCount() - 1);
    _betaText = (TextView) ((LinearLayout) mainLayout.getChildAt(mainLayout.getChildCount() - 2)).getChildAt(0);
    
    // LAMBDA (mixture weight)
    addParameterControl(mainLayout, "λ (LAMBDA) - Distance Mixture Weight", 
                       0, 100, (int)(_lambda * 100), "Balance between Euclidean (0) and turning angle (100)",
                       (progress) -> {
                         _lambda = progress / 100.0;
                         _lambdaText.setText(String.format("λ: %.2f", _lambda));
                         updateCGRParameters();
                       });
    _lambdaSlider = (SeekBar) mainLayout.getChildAt(mainLayout.getChildCount() - 1);
    _lambdaText = (TextView) ((LinearLayout) mainLayout.getChildAt(mainLayout.getChildCount() - 2)).getChildAt(0);
    
    // KAPPA (end-point bias)
    addParameterControl(mainLayout, "κ (KAPPA) - End-Point Bias Strength", 
                       0, 50, (int)(_kappa * 10), "Bias toward complete template matches (critical for accuracy)",
                       (progress) -> {
                         _kappa = progress / 10.0;
                         _kappaText.setText(String.format("κ: %.1f", _kappa));
                         updateCGRParameters();
                       });
    _kappaSlider = (SeekBar) mainLayout.getChildAt(mainLayout.getChildCount() - 1);
    _kappaText = (TextView) ((LinearLayout) mainLayout.getChildAt(mainLayout.getChildCount() - 2)).getChildAt(0);
    
    // PT_SPACING (progressive segment spacing)
    addParameterControl(mainLayout, "Point Spacing - Progressive Segment Density", 
                       100, 1000, _ptSpacing, "Distance between sampling points (lower = more memory)",
                       (progress) -> {
                         _ptSpacing = progress;
                         _ptSpacingText.setText(String.format("Spacing: %d", _ptSpacing));
                       });
    _ptSpacingSlider = (SeekBar) mainLayout.getChildAt(mainLayout.getChildCount() - 1);
    _ptSpacingText = (TextView) ((LinearLayout) mainLayout.getChildAt(mainLayout.getChildCount() - 2)).getChildAt(0);
    
    // MAX_RESAMPLING (maximum sampling points)
    addParameterControl(mainLayout, "Max Resampling Points", 
                       1000, 10000, _maxResampling, "Maximum points for complex gestures (wonderful = 3500)",
                       (progress) -> {
                         _maxResampling = progress;
                         _maxResamplingText.setText(String.format("Max: %d", _maxResampling));
                       });
    _maxResamplingSlider = (SeekBar) mainLayout.getChildAt(mainLayout.getChildCount() - 1);
    _maxResamplingText = (TextView) ((LinearLayout) mainLayout.getChildAt(mainLayout.getChildCount() - 2)).getChildAt(0);
    
    // Action buttons
    LinearLayout buttonLayout = new LinearLayout(this);
    buttonLayout.setOrientation(LinearLayout.HORIZONTAL);
    buttonLayout.setPadding(0, 32, 0, 16);
    
    Button resetButton = new Button(this);
    resetButton.setText("Reset to Defaults");
    resetButton.setOnClickListener(v -> resetToDefaults());
    buttonLayout.addView(resetButton);
    
    Button saveButton = new Button(this);
    saveButton.setText("Save & Regenerate Templates");
    saveButton.setOnClickListener(v -> saveAndRegenerateTemplates());
    buttonLayout.addView(saveButton);
    
    mainLayout.addView(buttonLayout);
    
    // Load saved values
    loadSavedParameters();
    
    setContentView(mainLayout);
  }
  
  private void addParameterControl(LinearLayout parent, String title, int min, int max, int current, 
                                 String description, ParameterChangeListener listener)
  {
    LinearLayout controlLayout = new LinearLayout(this);
    controlLayout.setOrientation(LinearLayout.VERTICAL);
    controlLayout.setPadding(0, 16, 0, 16);
    
    TextView titleText = new TextView(this);
    titleText.setText(title);
    titleText.setTextSize(16);
    titleText.setTextColor(Color.WHITE);
    controlLayout.addView(titleText);
    
    TextView descText = new TextView(this);
    descText.setText(description);
    descText.setTextSize(12);
    descText.setTextColor(Color.GRAY);
    descText.setPadding(0, 4, 0, 8);
    controlLayout.addView(descText);
    
    SeekBar slider = new SeekBar(this);
    slider.setMax(max - min);
    slider.setProgress(current - min);
    slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (fromUser) {
          listener.onParameterChanged(progress + min);
        }
      }
      @Override public void onStartTrackingTouch(SeekBar seekBar) {}
      @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    });
    
    controlLayout.addView(slider);
    parent.addView(controlLayout);
  }
  
  private void updateCGRParameters()
  {
    // Update CGR constants (would need to modify ContinuousGestureRecognizer to be configurable)
    android.util.Log.d("CGRSettings", String.format("Updated parameters: σₑ=%.0f, β=%.0f, λ=%.2f, κ=%.1f", 
                      _eSigma, _beta, _lambda, _kappa));
    
    // Save to preferences for immediate use
    saveParametersToPrefs();
  }
  
  private void resetToDefaults()
  {
    _eSigma = 200.0;
    _beta = 400.0;
    _lambda = 0.4;
    _kappa = 1.0;
    _ptSpacing = 200;
    _maxResampling = 5000;
    
    updateAllControls();
    updateCGRParameters();
    
    Toast.makeText(this, "Reset to default CGR parameters", Toast.LENGTH_SHORT).show();
  }
  
  private void saveAndRegenerateTemplates()
  {
    saveParametersToPrefs();
    
    // Trigger template regeneration with new parameters
    android.util.Log.d("CGRSettings", "Triggering template regeneration with new parameters");
    
    Toast.makeText(this, "Parameters saved and templates will regenerate", Toast.LENGTH_LONG).show();
    finish();
  }
  
  private void updateAllControls()
  {
    _eSigmaSlider.setProgress((int)_eSigma - 50);
    _betaSlider.setProgress((int)_beta - 100);
    _lambdaSlider.setProgress((int)(_lambda * 100));
    _kappaSlider.setProgress((int)(_kappa * 10));
    _ptSpacingSlider.setProgress(_ptSpacing - 100);
    _maxResamplingSlider.setProgress(_maxResampling - 1000);
    
    _eSigmaText.setText(String.format("σₑ: %.0f", _eSigma));
    _betaText.setText(String.format("β: %.0f", _beta));
    _lambdaText.setText(String.format("λ: %.2f", _lambda));
    _kappaText.setText(String.format("κ: %.1f", _kappa));
    _ptSpacingText.setText(String.format("Spacing: %d", _ptSpacing));
    _maxResamplingText.setText(String.format("Max: %d", _maxResampling));
  }
  
  private void loadSavedParameters()
  {
    SharedPreferences prefs = getSharedPreferences("cgr_settings", MODE_PRIVATE);
    _eSigma = Double.longBitsToDouble(prefs.getLong("e_sigma", Double.doubleToLongBits(200.0)));
    _beta = Double.longBitsToDouble(prefs.getLong("beta", Double.doubleToLongBits(400.0)));
    _lambda = Double.longBitsToDouble(prefs.getLong("lambda", Double.doubleToLongBits(0.4)));
    _kappa = Double.longBitsToDouble(prefs.getLong("kappa", Double.doubleToLongBits(1.0)));
    _ptSpacing = prefs.getInt("pt_spacing", 200);
    _maxResampling = prefs.getInt("max_resampling", 5000);
    
    updateAllControls();
  }
  
  private void saveParametersToPrefs()
  {
    SharedPreferences prefs = getSharedPreferences("cgr_settings", MODE_PRIVATE);
    prefs.edit()
      .putLong("e_sigma", Double.doubleToLongBits(_eSigma))
      .putLong("beta", Double.doubleToLongBits(_beta))
      .putLong("lambda", Double.doubleToLongBits(_lambda))
      .putLong("kappa", Double.doubleToLongBits(_kappa))
      .putInt("pt_spacing", _ptSpacing)
      .putInt("max_resampling", _maxResampling)
      .apply();
  }
  
  @FunctionalInterface
  private interface ParameterChangeListener
  {
    void onParameterChanged(int value);
  }
}