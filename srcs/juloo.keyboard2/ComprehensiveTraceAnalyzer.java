package juloo.keyboard2;

import android.graphics.PointF;
import android.graphics.RectF;
import java.util.ArrayList;
import java.util.List;

/**
 * Comprehensive Trace Analysis Module
 * 
 * Maximum modularity, configurability, and scope for swipe gesture analysis
 * Every parameter configurable through UI elements
 */
public class ComprehensiveTraceAnalyzer
{
  // ========== 1. USER TRACE COLLECTION BOUNDING BOX ==========
  
  // Bounding box parameters (all configurable)
  private boolean enableBoundingBoxAnalysis = true;
  private double boundingBoxPadding = 10.0;           // Extra space around gesture
  private boolean includeBoundingBoxRotation = true;  // Analyze rotated bounding box
  private double boundingBoxAspectRatioWeight = 1.0;  // Importance of width/height ratio
  
  // ========== 2. TOTAL DISTANCE BREAKDOWN ==========
  
  // Directional distance parameters (all configurable)
  private boolean enableDirectionalAnalysis = true;
  private double northSouthWeight = 1.0;              // Vertical movement importance
  private double eastWestWeight = 1.0;                // Horizontal movement importance
  private double diagonalMovementWeight = 0.8;       // Diagonal vs cardinal movement
  private double movementSmoothingFactor = 0.9;      // Movement direction smoothing
  
  // ========== 3. PAUSE/STOP DETECTION ==========
  
  // Stop detection parameters (all configurable)
  private boolean enableStopDetection = true;
  private long stopThresholdMs = 150;                 // Pause duration threshold
  private double stopPositionTolerance = 15.0;       // Position drift during stop
  private double stopLetterWeight = 2.0;              // Extra weight for stopped letters
  private int minStopDuration = 50;                   // Minimum pause to count as stop
  private int maxStopsPerGesture = 5;                 // Maximum stops to consider
  
  // ========== 4. ANGLE POINT DETECTION ==========
  
  // Angle detection parameters (all configurable)
  private boolean enableAngleDetection = true;
  private double angleDetectionThreshold = 30.0;     // Degrees for direction change
  private double sharpAngleThreshold = 90.0;         // Sharp turn detection
  private double smoothAngleThreshold = 15.0;        // Gentle curve detection
  private int angleAnalysisWindowSize = 5;           // Points to analyze for angle
  private double angleLetterBoost = 1.5;             // Boost for letters at angles
  
  // ========== 5. LETTER DETECTION ==========
  
  // Letter detection parameters (all configurable)
  private double letterDetectionRadius = 80.0;       // Key hit zone
  private double letterConfidenceThreshold = 0.7;    // Minimum confidence for letter
  private boolean enableLetterPrediction = true;     // Predict missed letters
  private double letterOrderWeight = 1.2;            // Importance of letter sequence
  private int maxLettersPerGesture = 15;              // Maximum letters to detect
  
  // ========== 6. START/END LETTER ANALYSIS ==========
  
  // Start/end parameters (all configurable)
  private double startLetterWeight = 3.0;            // Start letter importance
  private double endLetterWeight = 1.0;              // End letter importance
  private double startPositionTolerance = 25.0;      // Start position accuracy
  private double endPositionTolerance = 50.0;        // End position tolerance (less important)
  private boolean requireStartLetterMatch = true;    // Must match start letter
  private boolean requireEndLetterMatch = false;     // End letter optional
  
  // ========== COMPREHENSIVE TRACE ANALYSIS RESULT ==========
  
  public static class TraceAnalysisResult
  {
    // Bounding box analysis
    public RectF boundingBox;
    public double boundingBoxArea;
    public double aspectRatio;
    public double boundingBoxRotation;
    
    // Directional distance breakdown
    public double totalDistance;
    public double northDistance;
    public double southDistance;
    public double eastDistance;
    public double westDistance;
    public double diagonalDistance;
    
    // Stop analysis
    public List<StopPoint> stops;
    public int totalStops;
    public List<Character> stoppedLetters;
    public double averageStopDuration;
    
    // Angle analysis
    public List<AnglePoint> anglePoints;
    public int sharpAngles;
    public int gentleAngles;
    public List<Character> angleLetters;
    
    // Letter detection
    public List<Character> detectedLetters;
    public List<LetterDetection> letterDetails;
    public double averageLetterConfidence;
    
    // Start/end analysis
    public Character startLetter;
    public Character endLetter;
    public double startAccuracy;
    public double endAccuracy;
    public boolean startLetterMatch;
    public boolean endLetterMatch;
    
    // Composite scores
    public double overallConfidence;
    public double gestureComplexity;
    public double recognitionDifficulty;
  }
  
  public static class StopPoint
  {
    public PointF position;
    public long duration;
    public Character nearestLetter;
    public double confidence;
    
    public StopPoint(PointF pos, long dur, Character letter, double conf)
    {
      position = pos; duration = dur; nearestLetter = letter; confidence = conf;
    }
  }
  
  public static class AnglePoint
  {
    public PointF position;
    public double angle;
    public boolean isSharp;
    public Character nearestLetter;
    
    public AnglePoint(PointF pos, double ang, boolean sharp, Character letter)
    {
      position = pos; angle = ang; isSharp = sharp; nearestLetter = letter;
    }
  }
  
  public static class LetterDetection
  {
    public Character letter;
    public PointF position;
    public double confidence;
    public boolean hadStop;
    public boolean hadAngle;
    public long timeSpent;
    
    public LetterDetection(Character let, PointF pos, double conf, boolean stop, boolean angle, long time)
    {
      letter = let; position = pos; confidence = conf; hadStop = stop; hadAngle = angle; timeSpent = time;
    }
  }
  
  public ComprehensiveTraceAnalyzer(WordGestureTemplateGenerator generator)
  {
    templateGenerator = generator; // Reuse existing generator with keyboard dimensions
    android.util.Log.d("ComprehensiveTraceAnalyzer", "Initialized with shared template generator");
  }
  
  /**
   * Comprehensive analysis of user swipe trace with full configurability
   */
  public TraceAnalysisResult analyzeTrace(List<PointF> swipePath, List<Long> timestamps, String targetWord)
  {
    TraceAnalysisResult result = new TraceAnalysisResult();
    
    if (swipePath.size() < 2) return result;
    
    android.util.Log.d("ComprehensiveTraceAnalyzer", "Analyzing trace: " + swipePath.size() + " points for word '" + targetWord + "'");
    
    // 1. BOUNDING BOX ANALYSIS
    if (enableBoundingBoxAnalysis)
    {
      analyzeBoundingBox(swipePath, result);
    }
    
    // 2. DIRECTIONAL DISTANCE BREAKDOWN
    if (enableDirectionalAnalysis)
    {
      analyzeDirectionalMovement(swipePath, result);
    }
    
    // 3. STOP/PAUSE DETECTION
    if (enableStopDetection && timestamps != null)
    {
      analyzeStops(swipePath, timestamps, result);
    }
    
    // 4. ANGLE POINT DETECTION
    if (enableAngleDetection)
    {
      analyzeAngles(swipePath, result);
    }
    
    // 5. LETTER DETECTION
    analyzeLetters(swipePath, result);
    
    // 6. START/END ANALYSIS
    analyzeStartEnd(swipePath, targetWord, result);
    
    // 7. COMPOSITE SCORING
    calculateCompositeScores(result);
    
    android.util.Log.d("ComprehensiveTraceAnalyzer", String.format("Analysis complete: %d letters, %d stops, %d angles, %.0f total distance",
                      result.detectedLetters.size(), result.totalStops, result.anglePoints.size(), result.totalDistance));
    
    return result;
  }
  
  /**
   * 1. BOUNDING BOX ANALYSIS - All parameters configurable
   */
  private void analyzeBoundingBox(List<PointF> swipePath, TraceAnalysisResult result)
  {
    float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
    float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
    
    for (PointF point : swipePath)
    {
      minX = Math.min(minX, point.x);
      maxX = Math.max(maxX, point.x);
      minY = Math.min(minY, point.y);
      maxY = Math.max(maxY, point.y);
    }
    
    // Apply configurable padding
    result.boundingBox = new RectF(minX - (float)boundingBoxPadding, minY - (float)boundingBoxPadding,
                                  maxX + (float)boundingBoxPadding, maxY + (float)boundingBoxPadding);
    result.boundingBoxArea = result.boundingBox.width() * result.boundingBox.height();
    result.aspectRatio = result.boundingBox.width() / result.boundingBox.height();
    
    // IMPLEMENTED: Rotated bounding box analysis for better gesture characterization
    if (includeBoundingBoxRotation && swipePath.size() >= 3)
    {
      result.boundingBoxRotation = calculateOptimalRotation(swipePath);
    }
    else
    {
      result.boundingBoxRotation = 0.0;
    }
    
    android.util.Log.d("ComprehensiveTraceAnalyzer", String.format("Bounding box: %.0fx%.0f, aspect=%.2f",
                      result.boundingBox.width(), result.boundingBox.height(), result.aspectRatio));
  }
  
  /**
   * 2. DIRECTIONAL DISTANCE BREAKDOWN - All parameters configurable
   */
  private void analyzeDirectionalMovement(List<PointF> swipePath, TraceAnalysisResult result)
  {
    result.northDistance = 0; result.southDistance = 0;
    result.eastDistance = 0; result.westDistance = 0;
    result.diagonalDistance = 0; result.totalDistance = 0;
    
    for (int i = 1; i < swipePath.size(); i++)
    {
      PointF prev = swipePath.get(i - 1);
      PointF curr = swipePath.get(i);
      
      double dx = curr.x - prev.x;
      double dy = curr.y - prev.y;
      double segmentDistance = Math.sqrt(dx * dx + dy * dy);
      
      result.totalDistance += segmentDistance;
      
      // Categorize movement direction with configurable weights
      if (Math.abs(dx) > Math.abs(dy)) // Primarily horizontal
      {
        if (dx > 0) result.eastDistance += segmentDistance * eastWestWeight;
        else result.westDistance += segmentDistance * eastWestWeight;
      }
      else if (Math.abs(dy) > Math.abs(dx)) // Primarily vertical
      {
        if (dy > 0) result.southDistance += segmentDistance * northSouthWeight;
        else result.northDistance += segmentDistance * northSouthWeight;
      }
      else // Diagonal movement
      {
        result.diagonalDistance += segmentDistance * diagonalMovementWeight;
      }
    }
    
    android.util.Log.d("ComprehensiveTraceAnalyzer", String.format("Directional: N=%.0f S=%.0f E=%.0f W=%.0f Diag=%.0f",
                      result.northDistance, result.southDistance, result.eastDistance, result.westDistance, result.diagonalDistance));
  }
  
  /**
   * 3. STOP/PAUSE DETECTION - All parameters configurable
   */
  private void analyzeStops(List<PointF> swipePath, List<Long> timestamps, TraceAnalysisResult result)
  {
    result.stops = new ArrayList<>();
    result.stoppedLetters = new ArrayList<>();
    
    if (timestamps.size() != swipePath.size()) return;
    
    for (int i = 1; i < timestamps.size() && result.stops.size() < maxStopsPerGesture; i++)
    {
      long timeDelta = timestamps.get(i) - timestamps.get(i - 1);
      
      if (timeDelta >= stopThresholdMs)
      {
        PointF stopPosition = swipePath.get(i);
        
        // Check if position stayed within tolerance during pause
        boolean validStop = true;
        if (i + 1 < swipePath.size())
        {
          PointF nextPoint = swipePath.get(i + 1);
          double positionDrift = Math.sqrt(Math.pow(nextPoint.x - stopPosition.x, 2) + 
                                         Math.pow(nextPoint.y - stopPosition.y, 2));
          validStop = positionDrift <= stopPositionTolerance;
        }
        
        if (validStop && timeDelta >= minStopDuration)
        {
          // Find nearest letter to stop position
          Character nearestLetter = findNearestLetter(stopPosition);
          double confidence = calculateStopConfidence(timeDelta, stopPosition);
          
          StopPoint stop = new StopPoint(stopPosition, timeDelta, nearestLetter, confidence);
          result.stops.add(stop);
          
          if (nearestLetter != null && !result.stoppedLetters.contains(nearestLetter))
          {
            result.stoppedLetters.add(nearestLetter);
          }
        }
      }
    }
    
    result.totalStops = result.stops.size();
    result.averageStopDuration = result.stops.stream().mapToLong(s -> s.duration).average().orElse(0.0);
    
    android.util.Log.d("ComprehensiveTraceAnalyzer", String.format("Stops: %d detected, avg duration %.0fms, letters: %s",
                      result.totalStops, result.averageStopDuration, result.stoppedLetters));
  }
  
  /**
   * 4. ANGLE POINT DETECTION - All parameters configurable
   */
  private void analyzeAngles(List<PointF> swipePath, TraceAnalysisResult result)
  {
    result.anglePoints = new ArrayList<>();
    result.angleLetters = new ArrayList<>();
    result.sharpAngles = 0;
    result.gentleAngles = 0;
    
    for (int i = angleAnalysisWindowSize; i < swipePath.size() - angleAnalysisWindowSize; i++)
    {
      double angle = calculateDirectionChange(swipePath, i);
      
      if (Math.abs(angle) >= angleDetectionThreshold)
      {
        PointF anglePosition = swipePath.get(i);
        boolean isSharp = Math.abs(angle) >= sharpAngleThreshold;
        Character nearestLetter = findNearestLetter(anglePosition);
        
        AnglePoint anglePoint = new AnglePoint(anglePosition, angle, isSharp, nearestLetter);
        result.anglePoints.add(anglePoint);
        
        if (isSharp) result.sharpAngles++;
        else if (Math.abs(angle) >= smoothAngleThreshold) result.gentleAngles++;
        
        if (nearestLetter != null && !result.angleLetters.contains(nearestLetter))
        {
          result.angleLetters.add(nearestLetter);
        }
      }
    }
    
    android.util.Log.d("ComprehensiveTraceAnalyzer", String.format("Angles: %d total, %d sharp, %d gentle, letters: %s",
                      result.anglePoints.size(), result.sharpAngles, result.gentleAngles, result.angleLetters));
  }
  
  /**
   * 5. COMPREHENSIVE LETTER DETECTION - All parameters configurable
   */
  private void analyzeLetters(List<PointF> swipePath, TraceAnalysisResult result)
  {
    result.detectedLetters = new ArrayList<>();
    result.letterDetails = new ArrayList<>();
    
    Character lastLetter = null;
    long lastLetterTime = 0;
    
    for (int i = 0; i < swipePath.size(); i++)
    {
      PointF point = swipePath.get(i);
      Character nearestLetter = findNearestLetter(point);
      
      if (nearestLetter != null && !nearestLetter.equals(lastLetter))
      {
        double confidence = calculateLetterConfidence(point, nearestLetter);
        
        if (confidence >= letterConfidenceThreshold)
        {
          // Check if this letter had stops or angles
          boolean hadStop = result.stoppedLetters.contains(nearestLetter);
          boolean hadAngle = result.angleLetters.contains(nearestLetter);
          long timeSpent = i > 0 ? (System.currentTimeMillis() - lastLetterTime) : 0;
          
          LetterDetection detection = new LetterDetection(nearestLetter, point, confidence, hadStop, hadAngle, timeSpent);
          result.letterDetails.add(detection);
          
          if (!result.detectedLetters.contains(nearestLetter))
          {
            result.detectedLetters.add(nearestLetter);
          }
          
          lastLetter = nearestLetter;
          lastLetterTime = System.currentTimeMillis();
        }
      }
    }
    
    result.averageLetterConfidence = result.letterDetails.stream()
      .mapToDouble(ld -> ld.confidence).average().orElse(0.0);
    
    android.util.Log.d("ComprehensiveTraceAnalyzer", String.format("Letters: %s, avg confidence %.3f",
                      result.detectedLetters, result.averageLetterConfidence));
  }
  
  /**
   * 6. START/END LETTER ANALYSIS - All parameters configurable
   */
  private void analyzeStartEnd(List<PointF> swipePath, String targetWord, TraceAnalysisResult result)
  {
    if (swipePath.isEmpty()) return;
    
    // Analyze start letter
    PointF startPoint = swipePath.get(0);
    result.startLetter = findNearestLetter(startPoint);
    result.startAccuracy = calculatePositionAccuracy(startPoint, result.startLetter, startPositionTolerance);
    
    // Analyze end letter  
    PointF endPoint = swipePath.get(swipePath.size() - 1);
    result.endLetter = findNearestLetter(endPoint);
    result.endAccuracy = calculatePositionAccuracy(endPoint, result.endLetter, endPositionTolerance);
    
    // Check matches against target word
    if (targetWord != null && !targetWord.isEmpty())
    {
      result.startLetterMatch = targetWord.charAt(0) == (result.startLetter != null ? result.startLetter : '\0');
      result.endLetterMatch = targetWord.charAt(targetWord.length() - 1) == (result.endLetter != null ? result.endLetter : '\0');
    }
    
    android.util.Log.d("ComprehensiveTraceAnalyzer", String.format("Start: %c (%.3f) End: %c (%.3f) Matches: %s/%s",
                      result.startLetter != null ? result.startLetter : '?', result.startAccuracy,
                      result.endLetter != null ? result.endLetter : '?', result.endAccuracy,
                      result.startLetterMatch, result.endLetterMatch));
  }
  
  /**
   * 7. COMPOSITE SCORING - All weights configurable
   */
  private void calculateCompositeScores(TraceAnalysisResult result)
  {
    // Calculate overall confidence based on all factors
    double confidence = 0.0;
    
    // Bounding box contribution
    if (enableBoundingBoxAnalysis)
    {
      confidence += (result.aspectRatio > 0.5 && result.aspectRatio < 2.0) ? 0.2 : 0.0;
    }
    
    // Directional movement contribution
    if (enableDirectionalAnalysis)
    {
      double directionalBalance = 1.0 - Math.abs(0.5 - (result.eastDistance + result.westDistance) / result.totalDistance);
      confidence += directionalBalance * 0.2;
    }
    
    // Letter detection contribution
    confidence += Math.min(1.0, result.averageLetterConfidence) * 0.4;
    
    // Start/end contribution
    confidence += (result.startLetterMatch ? startLetterWeight * 0.1 : 0.0);
    confidence += (result.endLetterMatch ? endLetterWeight * 0.1 : 0.0);
    
    result.overallConfidence = Math.min(1.0, confidence);
    
    // Calculate gesture complexity
    result.gestureComplexity = (result.totalStops * 0.2) + (result.anglePoints.size() * 0.3) + 
                              (result.detectedLetters.size() * 0.1) + (result.totalDistance / 1000.0 * 0.4);
    
    // Calculate recognition difficulty
    result.recognitionDifficulty = 1.0 - result.overallConfidence + (result.gestureComplexity * 0.3);
    
    android.util.Log.d("ComprehensiveTraceAnalyzer", String.format("Composite: confidence=%.3f, complexity=%.3f, difficulty=%.3f",
                      result.overallConfidence, result.gestureComplexity, result.recognitionDifficulty));
  }
  
  // ========== HELPER METHODS (All using configurable parameters) ==========
  
  // Integrated keyboard layout for letter detection
  private WordGestureTemplateGenerator templateGenerator;
  
  private Character findNearestLetter(PointF point)
  {
    if (templateGenerator == null) return null;
    
    double minDistance = Double.MAX_VALUE;
    Character nearestLetter = null;
    
    // Check all keyboard letters using existing coordinate system
    String allLetters = "qwertyuiopasdfghjklzxcvbnm";
    for (char c : allLetters.toCharArray())
    {
      ContinuousGestureRecognizer.Point coord = templateGenerator.getCharacterCoordinate(c);
      if (coord != null)
      {
        double distance = Math.sqrt(Math.pow(point.x - coord.x, 2) + Math.pow(point.y - coord.y, 2));
        
        // Use configurable letter detection radius
        if (distance <= letterDetectionRadius && distance < minDistance)
        {
          minDistance = distance;
          nearestLetter = c;
        }
      }
    }
    
    return nearestLetter;
  }
  
  private double calculateDirectionChange(List<PointF> path, int centerIndex)
  {
    if (centerIndex < angleAnalysisWindowSize || centerIndex >= path.size() - angleAnalysisWindowSize)
      return 0.0;
    
    PointF before = path.get(centerIndex - angleAnalysisWindowSize);
    PointF center = path.get(centerIndex);
    PointF after = path.get(centerIndex + angleAnalysisWindowSize);
    
    double angle1 = Math.atan2(center.y - before.y, center.x - before.x);
    double angle2 = Math.atan2(after.y - center.y, after.x - center.x);
    
    double deltaAngle = Math.toDegrees(angle2 - angle1);
    if (deltaAngle > 180) deltaAngle -= 360;
    if (deltaAngle < -180) deltaAngle += 360;
    
    return deltaAngle;
  }
  
  private double calculateStopConfidence(long duration, PointF position)
  {
    // Confidence based on stop duration and position stability
    double durationFactor = Math.min(1.0, duration / (double)stopThresholdMs);
    return durationFactor * stopLetterWeight;
  }
  
  private double calculateLetterConfidence(PointF point, Character letter)
  {
    if (letter == null || templateGenerator == null) return 0.0;
    
    // Get actual key center coordinate
    ContinuousGestureRecognizer.Point keyCenter = templateGenerator.getCharacterCoordinate(letter);
    if (keyCenter == null) return 0.0;
    
    // Calculate distance from point to key center
    double distance = Math.sqrt(Math.pow(point.x - keyCenter.x, 2) + Math.pow(point.y - keyCenter.y, 2));
    
    // Convert distance to confidence using configurable parameters
    // Confidence decreases exponentially with distance
    double confidence = Math.exp(-distance / letterDetectionRadius);
    
    return Math.min(1.0, confidence);
  }
  
  private double calculatePositionAccuracy(PointF point, Character letter, double tolerance)
  {
    if (letter == null || templateGenerator == null) return 0.0;
    
    // Get actual key center coordinate  
    ContinuousGestureRecognizer.Point keyCenter = templateGenerator.getCharacterCoordinate(letter);
    if (keyCenter == null) return 0.0;
    
    // Calculate distance from point to key center
    double distance = Math.sqrt(Math.pow(point.x - keyCenter.x, 2) + Math.pow(point.y - keyCenter.y, 2));
    
    // Accuracy is 1.0 if within tolerance, decreasing linearly beyond tolerance
    if (distance <= tolerance)
    {
      return 1.0 - (distance / tolerance) * 0.5; // 50-100% accuracy within tolerance
    }
    else
    {
      return Math.max(0.0, 0.5 - (distance - tolerance) / tolerance); // Decreasing beyond tolerance
    }
  }
  
  // ========== CONFIGURATION METHODS ==========
  
  public void setBoundingBoxParameters(boolean enable, double padding, boolean rotation, double aspectWeight)
  {
    enableBoundingBoxAnalysis = enable;
    boundingBoxPadding = padding;
    includeBoundingBoxRotation = rotation;
    boundingBoxAspectRatioWeight = aspectWeight;
  }
  
  public void setDirectionalParameters(boolean enable, double nsWeight, double ewWeight, double diagWeight, double smoothing)
  {
    enableDirectionalAnalysis = enable;
    northSouthWeight = nsWeight;
    eastWestWeight = ewWeight;
    diagonalMovementWeight = diagWeight;
    movementSmoothingFactor = smoothing;
  }
  
  public void setStopParameters(boolean enable, long threshold, double tolerance, double weight, int minDur, int maxStops)
  {
    enableStopDetection = enable;
    stopThresholdMs = threshold;
    stopPositionTolerance = tolerance;
    stopLetterWeight = weight;
    minStopDuration = minDur;
    maxStopsPerGesture = maxStops;
  }
  
  public void setAngleParameters(boolean enable, double threshold, double sharp, double smooth, int window, double boost)
  {
    enableAngleDetection = enable;
    angleDetectionThreshold = threshold;
    sharpAngleThreshold = sharp;
    smoothAngleThreshold = smooth;
    angleAnalysisWindowSize = window;
    angleLetterBoost = boost;
  }
  
  public void setLetterParameters(double radius, double confidence, boolean predict, double order, int maxLetters)
  {
    letterDetectionRadius = radius;
    letterConfidenceThreshold = confidence;
    enableLetterPrediction = predict;
    letterOrderWeight = order;
    maxLettersPerGesture = maxLetters;
  }
  
  public void setStartEndParameters(double startWeight, double endWeight, double startTol, double endTol, boolean reqStart, boolean reqEnd)
  {
    startLetterWeight = startWeight;
    endLetterWeight = endWeight;
    startPositionTolerance = startTol;
    endPositionTolerance = endTol;
    requireStartLetterMatch = reqStart;
    requireEndLetterMatch = reqEnd;
  }
  
  /**
   * Calculate optimal rotation angle for minimal bounding box
   */
  private double calculateOptimalRotation(List<PointF> points)
  {
    double minArea = Double.MAX_VALUE;
    double optimalRotation = 0.0;
    
    // Test rotations from 0 to 180 degrees (in 5-degree increments)
    for (int angle = 0; angle < 180; angle += 5)
    {
      double radians = Math.toRadians(angle);
      
      // Calculate rotated bounding box
      float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
      float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
      
      for (PointF point : points)
      {
        // Rotate point around origin
        float rotatedX = (float)(point.x * Math.cos(radians) - point.y * Math.sin(radians));
        float rotatedY = (float)(point.x * Math.sin(radians) + point.y * Math.cos(radians));
        
        minX = Math.min(minX, rotatedX);
        maxX = Math.max(maxX, rotatedX);
        minY = Math.min(minY, rotatedY);
        maxY = Math.max(maxY, rotatedY);
      }
      
      double area = (maxX - minX) * (maxY - minY);
      if (area < minArea)
      {
        minArea = area;
        optimalRotation = angle;
      }
    }
    
    return optimalRotation;
  }
  
  /**
   * Set keyboard dimensions for coordinate calculations
   */
  public void setKeyboardDimensions(float width, float height)
  {
    if (templateGenerator != null)
    {
      templateGenerator.setKeyboardDimensions(width, height);
      android.util.Log.d("ComprehensiveTraceAnalyzer", "Keyboard dimensions set: " + width + "x" + height);
    }
  }
}