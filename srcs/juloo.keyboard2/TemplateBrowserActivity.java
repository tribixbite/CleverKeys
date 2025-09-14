package juloo.keyboard2;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * Template Browser for debugging template generation
 * Visualizes templates for all words to verify coordinate accuracy
 */
public class TemplateBrowserActivity extends Activity
{
  private WordGestureTemplateGenerator templateGenerator;
  private ListView wordList;
  private TemplateVisualizationView templateView;
  private TextView templateInfo;
  private List<String> allWords;
  
  @Override
  protected void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    
    LinearLayout mainLayout = new LinearLayout(this);
    mainLayout.setOrientation(LinearLayout.VERTICAL);
    mainLayout.setBackgroundColor(Color.BLACK);
    
    // Title
    TextView title = new TextView(this);
    title.setText("📐 Template Browser");
    title.setTextSize(20);
    title.setTextColor(Color.WHITE);
    title.setPadding(16, 16, 16, 8);
    mainLayout.addView(title);
    
    // Instructions
    TextView instructions = new TextView(this);
    instructions.setText("Select word to view its template visualization and coordinates");
    instructions.setTextSize(14);
    instructions.setTextColor(Color.GRAY);
    instructions.setPadding(16, 0, 16, 16);
    mainLayout.addView(instructions);
    
    // Word list
    wordList = new ListView(this);
    wordList.setLayoutParams(new LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT, 300));
    wordList.setBackgroundColor(0xFF2D2D2D);
    mainLayout.addView(wordList);
    
    // Template visualization
    templateView = new TemplateVisualizationView(this);
    templateView.setLayoutParams(new LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT, 400));
    templateView.setBackgroundColor(0xFF1A1A1A);
    mainLayout.addView(templateView);
    
    // Template info display
    templateInfo = new TextView(this);
    templateInfo.setText("Select a word to see template details...");
    templateInfo.setTextSize(12);
    templateInfo.setTextColor(Color.WHITE);
    templateInfo.setTypeface(android.graphics.Typeface.MONOSPACE);
    templateInfo.setPadding(16, 16, 16, 16);
    templateInfo.setBackgroundColor(0xFF2D2D2D);
    templateInfo.setLayoutParams(new LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT, 200));
    mainLayout.addView(templateInfo);
    
    // Control buttons
    LinearLayout buttonLayout = new LinearLayout(this);
    buttonLayout.setOrientation(LinearLayout.HORIZONTAL);
    buttonLayout.setPadding(16, 16, 16, 16);
    
    Button refreshButton = new Button(this);
    refreshButton.setText("🔄 Refresh Templates");
    refreshButton.setOnClickListener(v -> refreshTemplates());
    buttonLayout.addView(refreshButton);
    
    Button closeButton = new Button(this);
    closeButton.setText("❌ Close");
    closeButton.setOnClickListener(v -> finish());
    buttonLayout.addView(closeButton);
    
    mainLayout.addView(buttonLayout);
    
    setContentView(mainLayout);
    
    // Initialize
    initializeTemplateBrowser();
  }
  
  private void initializeTemplateBrowser()
  {
    // Initialize template generator
    templateGenerator = new WordGestureTemplateGenerator();
    templateGenerator.loadDictionary(this);
    
    // Set keyboard dimensions (use standard size for testing)
    templateGenerator.setKeyboardDimensions(1080f, 400f);
    
    // Load all dictionary words
    allWords = templateGenerator.getDictionary();
    if (allWords == null) allWords = new ArrayList<>();
    
    // Set up word list adapter
    ArrayAdapter<String> adapter = new ArrayAdapter<>(this, 
      android.R.layout.simple_list_item_1, allWords);
    wordList.setAdapter(adapter);
    
    // Set up word selection listener
    wordList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
      @Override
      public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        String selectedWord = allWords.get(position);
        showTemplate(selectedWord);
      }
    });
    
    android.util.Log.d("TemplateBrowser", "Initialized with " + allWords.size() + " words");
  }
  
  private void showTemplate(String word)
  {
    ContinuousGestureRecognizer.Template template = templateGenerator.generateWordTemplate(word);
    
    StringBuilder info = new StringBuilder();
    info.append("WORD: ").append(word.toUpperCase()).append("\n");
    info.append("================\n");
    
    if (template == null) {
      info.append("❌ FAILED: No template generated\n");
      info.append("Check if word contains valid letters\n");
    } else if (template.pts == null) {
      info.append("❌ FAILED: Template has NULL points\n");
    } else if (template.pts.isEmpty()) {
      info.append("❌ FAILED: Template has 0 points\n");
    } else {
      info.append("✅ SUCCESS: Template generated\n");
      info.append("Points: ").append(template.pts.size()).append("\n");
      
      // Show all coordinates
      for (int i = 0; i < template.pts.size(); i++) {
        ContinuousGestureRecognizer.Point pt = template.pts.get(i);
        char letter = word.charAt(i);
        info.append(String.format("  %c: (%.0f, %.0f)\n", letter, pt.x, pt.y));
      }
      
      // Calculate metrics
      double templateLength = calculateTemplateLength(template.pts);
      info.append("\nMetrics:\n");
      info.append(String.format("  Total length: %.0f px\n", templateLength));
      
      if (template.pts.size() >= 2) {
        ContinuousGestureRecognizer.Point start = template.pts.get(0);
        ContinuousGestureRecognizer.Point end = template.pts.get(template.pts.size() - 1);
        info.append(String.format("  Start: (%.0f, %.0f)\n", start.x, start.y));
        info.append(String.format("  End: (%.0f, %.0f)\n", end.x, end.y));
        
        double directDistance = Math.sqrt(Math.pow(end.x - start.x, 2) + Math.pow(end.y - start.y, 2));
        info.append(String.format("  Direct distance: %.0f px\n", directDistance));
        info.append(String.format("  Path efficiency: %.2f\n", directDistance / templateLength));
      }
      
      // Update visualization
      templateView.setTemplate(template);
    }
    
    templateInfo.setText(info.toString());
    android.util.Log.d("TemplateBrowser", "Showing template for: " + word);
  }
  
  private double calculateTemplateLength(List<ContinuousGestureRecognizer.Point> points)
  {
    if (points.size() < 2) return 0;
    
    double length = 0;
    for (int i = 1; i < points.size(); i++) {
      ContinuousGestureRecognizer.Point p1 = points.get(i - 1);
      ContinuousGestureRecognizer.Point p2 = points.get(i);
      length += Math.sqrt(Math.pow(p2.x - p1.x, 2) + Math.pow(p2.y - p1.y, 2));
    }
    return length;
  }
  
  private void refreshTemplates()
  {
    // Reinitialize template generator
    templateGenerator.setKeyboardDimensions(1080f, 400f);
    android.util.Log.d("TemplateBrowser", "Templates refreshed");
    
    // Clear current display
    templateView.setTemplate(null);
    templateInfo.setText("Templates refreshed. Select a word to view updated template.");
  }
  
  /**
   * Custom view for template visualization
   */
  private class TemplateVisualizationView extends View
  {
    private ContinuousGestureRecognizer.Template currentTemplate;
    private Paint linePaint;
    private Paint pointPaint;
    private Paint labelPaint;
    
    public TemplateVisualizationView(android.content.Context context)
    {
      super(context);
      
      linePaint = new Paint();
      linePaint.setColor(Color.CYAN);
      linePaint.setStrokeWidth(4);
      linePaint.setStyle(Paint.Style.STROKE);
      
      pointPaint = new Paint();
      pointPaint.setColor(Color.YELLOW);
      pointPaint.setStyle(Paint.Style.FILL);
      
      labelPaint = new Paint();
      labelPaint.setColor(Color.WHITE);
      labelPaint.setTextSize(24);
      labelPaint.setAntiAlias(true);
    }
    
    public void setTemplate(ContinuousGestureRecognizer.Template template)
    {
      currentTemplate = template;
      invalidate(); // Trigger redraw
    }
    
    @Override
    protected void onDraw(Canvas canvas)
    {
      super.onDraw(canvas);
      
      if (currentTemplate == null || currentTemplate.pts == null || currentTemplate.pts.isEmpty()) {
        canvas.drawText("No template to display", 50, getHeight() / 2, labelPaint);
        return;
      }
      
      // Draw template path
      Path templatePath = new Path();
      List<ContinuousGestureRecognizer.Point> points = currentTemplate.pts;
      
      if (points.size() >= 2) {
        // Scale coordinates to fit view
        float scaleX = (getWidth() - 100) / 1080f;  // Leave margins
        float scaleY = (getHeight() - 100) / 400f;
        
        ContinuousGestureRecognizer.Point firstPoint = points.get(0);
        templatePath.moveTo((float)firstPoint.x * scaleX + 50, (float)firstPoint.y * scaleY + 50);
        
        for (int i = 1; i < points.size(); i++) {
          ContinuousGestureRecognizer.Point point = points.get(i);
          templatePath.lineTo((float)point.x * scaleX + 50, (float)point.y * scaleY + 50);
        }
        
        canvas.drawPath(templatePath, linePaint);
        
        // Draw points and labels
        for (int i = 0; i < points.size(); i++) {
          ContinuousGestureRecognizer.Point point = points.get(i);
          float x = (float)point.x * scaleX + 50;
          float y = (float)point.y * scaleY + 50;
          
          canvas.drawCircle(x, y, 8, pointPaint);
          
          if (i < currentTemplate.id.length()) {
            char letter = currentTemplate.id.charAt(i);
            canvas.drawText(String.valueOf(letter), x - 10, y - 15, labelPaint);
          }
        }
      }
    }
  }
}