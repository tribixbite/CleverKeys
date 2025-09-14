package juloo.keyboard2;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PointF;
import juloo.keyboard2.DirectBootAwarePreferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Continuous Gesture Recognizer Library (CGR)
 * 
 * Port of the CGR library from Lua to Java
 * 
 * Original copyright notice:
 * 
 * If you use this code for your research then please remember to cite our paper:
 *
 * Kristensson, P.O. and Denby, L.C. 2011. Continuous recognition and visualization
 * of pen strokes and touch-screen gestures. In Procceedings of the 8th Eurographics
 * Symposium on Sketch-Based Interfaces and Modeling (SBIM 2011). ACM Press: 95-102.
 *
 * Copyright (C) 2011 by Per Ola Kristensson, University of St Andrews, UK.
 */
public class ContinuousGestureRecognizer
{
  // Configurable parameters (KEYBOARD-OPTIMAL defaults, not paper defaults)
  private double currentESigma = 120.0;  // Lower for keyboard constraints (was 200)
  private double currentBeta = 400.0;    // Keep same variance ratio
  private double currentLambda = 0.65;   // Higher Euclidean weight for predictable positions (was 0.4)
  private double currentKappa = 2.5;     // Higher end-point bias for specific keys (was 1.0)
  private double currentLengthFilter = 0.70; // User-configurable length similarity threshold
  
  // Default constants for fallback
  private static final double DEFAULT_E_SIGMA = 200.0;
  private static final double DEFAULT_BETA = 400.0;
  private static final double DEFAULT_LAMBDA = 0.4;
  private static final double DEFAULT_KAPPA = 1.0;
  
  private static final int MAX_RESAMPLING_PTS = 5000; // Increased to support long words like 'wonderful' (was 3500)
  private static final int SAMPLE_POINT_DISTANCE = 10; // Restored original value for accuracy
  
  // Normalized space
  private static final Rect NORMALIZED_SPACE = new Rect(0, 0, 1000, 1000);
  
  // Global pattern set with permanent partitioning for parallel processing
  private final List<Pattern> patterns;
  private final List<List<Pattern>> patternPartitions; // Each thread gets permanent subset
  private static final int THREAD_COUNT = Math.min(4, Runtime.getRuntime().availableProcessors()); // Conservative: 1x CPU cores, max 4
  private static final ExecutorService parallelExecutor = Executors.newFixedThreadPool(THREAD_COUNT);
  
  public ContinuousGestureRecognizer()
  {
    patterns = new ArrayList<>();
    patternPartitions = new ArrayList<>();
    android.util.Log.d("CGR", "Parallel executor initialized with " + THREAD_COUNT + " threads (CPU cores: " + 
      Runtime.getRuntime().availableProcessors() + ")");
  }
  
  /**
   * Point class (equivalent to vec2 in Lua)
   */
  public static class Point
  {
    public double x;
    public double y;
    
    public Point(double x, double y)
    {
      this.x = x;
      this.y = y;
    }
    
    public Point(Point other)
    {
      this.x = other.x;
      this.y = other.y;
    }
  }
  
  /**
   * Rectangle class
   */
  public static class Rect
  {
    public double x;
    public double y;
    public double width;
    public double height;
    
    public Rect(double x, double y, double width, double height)
    {
      this.x = x;
      this.y = y;
      this.width = width;
      this.height = height;
    }
  }
  
  /**
   * Centroid class
   */
  public static class Centroid
  {
    public double x;
    public double y;
    
    public Centroid(double x, double y)
    {
      this.x = x;
      this.y = y;
    }
  }
  
  /**
   * Template class
   */
  public static class Template
  {
    public String id;
    public List<Point> pts;
    
    public Template(String id, List<Point> pts)
    {
      this.id = id;
      this.pts = new ArrayList<>(pts);
    }
  }
  
  /**
   * Pattern class
   */
  public static class Pattern
  {
    public Template template;
    public List<List<Point>> segments;
    
    public Pattern(Template template, List<List<Point>> segments)
    {
      this.template = template;
      this.segments = new ArrayList<>(segments);
    }
  }
  
  /**
   * IncrementalResult class
   */
  public static class IncrementalResult
  {
    public Pattern pattern;
    public double prob;
    public int indexOfMostLikelySegment;
    
    public IncrementalResult(Pattern pattern, double prob, int indexOfMostLikelySegment)
    {
      this.pattern = pattern;
      this.prob = prob;
      this.indexOfMostLikelySegment = indexOfMostLikelySegment;
    }
  }
  
  /**
   * Result class
   */
  public static class Result
  {
    public Template template;
    public double prob;
    public List<Point> pts;
    
    public Result(Template template, double prob, List<Point> pts)
    {
      this.template = template;
      this.prob = prob;
      this.pts = new ArrayList<>(pts);
    }
  }
  
  /**
   * Convert points list to array
   */
  private double[] toArray(List<Point> points)
  {
    double[] out = new double[points.size() * 2];
    for (int i = 0; i < points.size(); i++)
    {
      out[i * 2] = points.get(i).x;
      out[i * 2 + 1] = points.get(i).y;
    }
    return out;
  }
  
  /**
   * Deep copy points list
   */
  private List<Point> deepCopyPts(List<Point> p1)
  {
    List<Point> out = new ArrayList<>();
    for (Point pt : p1)
    {
      out.add(new Point(pt.x, pt.y));
    }
    return out;
  }
  
  /**
   * Get bounding box of points
   */
  private Rect getBoundingBox(List<Point> pts)
  {
    double minX = 1000000;
    double minY = 1000000;
    double maxX = -1000000;
    double maxY = -1000000;
    
    for (Point pt : pts)
    {
      if (pt.x < minX) minX = pt.x;
      if (pt.x > maxX) maxX = pt.x;
      if (pt.y < minY) minY = pt.y;
      if (pt.y > maxY) maxY = pt.y;
    }
    
    return new Rect(minX, minY, maxX - minX, maxY - minY);
  }
  
  /**
   * Get centroid of points
   */
  private Centroid getCentroid(List<Point> pts)
  {
    double totalMass = pts.size();
    double xIntegral = 0.0;
    double yIntegral = 0.0;
    
    for (Point pt : pts)
    {
      xIntegral += pt.x;
      yIntegral += pt.y;
    }
    
    return new Centroid(xIntegral / totalMass, yIntegral / totalMass);
  }
  
  /**
   * Translate points by dx, dy
   */
  private void translate(List<Point> pts, double dx, double dy)
  {
    for (Point pt : pts)
    {
      pt.x = Math.floor(dx) + pt.x;
      pt.y = Math.floor(dy) + pt.y;
    }
  }
  
  /**
   * Scale points
   */
  private void scale(List<Point> pts, double sx, double sy)
  {
    for (Point pt : pts)
    {
      pt.x = pt.x * sx;
      pt.y = pt.y * sy;
    }
  }
  
  /**
   * Scale points with origin
   */
  private void scale(List<Point> pts, double sx, double sy, double originX, double originY)
  {
    translate(pts, -originX, -originY);
    scale(pts, sx, sy);
    translate(pts, originX, originY);
  }
  
  /**
   * Calculate distance between two points
   */
  private double distance(double x1, double y1, double x2, double y2)
  {
    double dx = x2 - x1;
    if (dx < 0) dx = -dx;
    
    double dy = y2 - y1;
    if (dy < 0) dy = -dy;
    
    double fac = (dx > dy) ? dy : dx;
    return (dx + dy - (fac / 2));
  }
  
  /**
   * Calculate distance between two points
   */
  private double distance(Point p1, Point p2)
  {
    return distance(p1.x, p1.y, p2.x, p2.y);
  }
  
  /**
   * Get spatial length of path
   */
  private double getSpatialLength(List<Point> pts)
  {
    double len = 0.0;
    Point prev = null;
    
    for (Point pt : pts)
    {
      if (prev != null)
      {
        len += distance(prev, pt);
      }
      prev = pt;
    }
    
    return Math.floor(len);
  }
  
  /**
   * Get spatial length of array path
   */
  private double getSpatialLength(double[] pat, int n)
  {
    double l = 0;
    int m = 2 * n;
    
    if (m > 2)
    {
      double x1 = pat[0];
      double y1 = pat[1];
      
      for (int i = 2; i < m; i += 2)
      {
        double x2 = pat[i];
        double y2 = pat[i + 1];
        l += distance(x1, y1, x2, y2);
        x1 = x2;
        y1 = y2;
      }
      
      return Math.floor(l);
    }
    
    return 0;
  }
  
  /**
   * Get resampling point count
   */
  private int getResamplingPointCount(List<Point> pts, int samplePointDistance)
  {
    double len = getSpatialLength(pts);
    return (int)Math.floor((len / samplePointDistance) + 1);
  }
  
  /**
   * Get segment points
   */
  private double getSegmentPoints(double[] pts, int n, double length, double[] buffer)
  {
    int m = n * 2;
    double rest = 0.0;
    double x1 = pts[0];
    double y1 = pts[1];
    
    for (int i = 2; i < m; i += 2)
    {
      double x2 = pts[i];
      double y2 = pts[i + 1];
      double currentLen = distance(x1, y1, x2, y2);
      currentLen = currentLen + rest;
      rest = 0.0;
      double ps = currentLen / length;
      
      if (ps == 0)
      {
        rest = rest + currentLen;
      }
      else
      {
        rest = rest + currentLen - (ps * length);
      }
      
      if (i == 2 && ps == 0)
      {
        ps = 1;
      }
      
      buffer[(i / 2) - 1] = ps;
      x1 = x2;
      y1 = y2;
    }
    
    return rest;
  }
  
  /**
   * Resample points (two parameter version)
   */
  public List<Point> resample(List<Point> points, int numTargetPoints)
  {
    List<Point> r = new ArrayList<>();
    double[] inArray = toArray(points);
    double[] outArray = new double[numTargetPoints * 2];
    resample(inArray, outArray, points.size(), numTargetPoints);
    
    for (int i = 0; i < outArray.length; i += 2)
    {
      r.add(new Point(outArray[i], outArray[i + 1]));
    }
    
    return r;
  }
  
  /**
   * Resample points (four parameter version)
   */
  private void resample(double[] template, double[] buffer, int n, int numTargetPoints)
  {
    double[] segment_buf = new double[n];
    int m = n * 2;
    double l = getSpatialLength(template, n);
    double segmentLen = l / (numTargetPoints - 1);
    getSegmentPoints(template, n, segmentLen, segment_buf);
    
    double horizRest = 0.0;
    double verticRest = 0.0;
    double x1 = template[0];
    double y1 = template[1];
    int a = 0;
    int maxOutputs = numTargetPoints * 2;
    
    for (int i = 2; i < m; i += 2)
    {
      double x2 = template[i];
      double y2 = template[i + 1];
      double segmentPoints = segment_buf[(i / 2) - 1];
      double dx = -1.0;
      double dy = -1.0;
      
      if ((segmentPoints - 1) <= 0)
      {
        dx = 0.0;
        dy = 0.0;
      }
      else
      {
        dx = (x2 - x1) / segmentPoints;
        dy = (y2 - y1) / segmentPoints;
      }
      
      if (segmentPoints > 0)
      {
        for (int j = 1; j <= segmentPoints; j++)
        {
          if (j == 1)
          {
            if (a < maxOutputs - 1)
            {
              buffer[a] = x1 + horizRest;
              buffer[a + 1] = y1 + verticRest;
              horizRest = 0.0;
              verticRest = 0.0;
              a += 2;
            }
          }
          else
          {
            if (a < maxOutputs - 1)
            {
              buffer[a] = x1 + j * dx;
              buffer[a + 1] = y1 + j * dy;
              a += 2;
            }
          }
        }
      }
      
      x1 = x2;
      y1 = y2;
    }
    
    int theEnd = (numTargetPoints * 2) - 2;
    if (a < theEnd && a >= 2)
    {
      for (int i = a; i < theEnd; i += 2)
      {
        // Add bounds checking to prevent array access errors
        if (i >= 2)
        {
          buffer[i] = (buffer[i - 2] + template[m - 2]) / 2;
          buffer[i + 1] = (buffer[i - 1] + template[m - 1]) / 2;
        }
      }
    }
    
    buffer[maxOutputs - 2] = template[m - 2];
    buffer[maxOutputs - 1] = template[m - 1];
  }
  
  /**
   * Generate equidistant progressive subsequences
   */
  private List<List<Point>> generateEquiDistantProgressiveSubSequences(List<Point> pts, int ptSpacing)
  {
    List<List<Point>> sequences = new ArrayList<>();
    int nSamplePoints = getResamplingPointCount(pts, ptSpacing);
    List<Point> resampledPts = resample(pts, nSamplePoints);
    
    for (int i = 1; i <= resampledPts.size(); i++)
    {
      List<Point> subList = new ArrayList<>();
      for (int j = 0; j < i; j++)
      {
        subList.add(resampledPts.get(j));
      }
      
      List<Point> seq = deepCopyPts(subList);
      sequences.add(seq);
    }
    
    return sequences;
  }
  
  /**
   * Scale to target bounds
   */
  private void scaleTo(List<Point> pts, Rect targetBounds)
  {
    Rect bounds = getBoundingBox(pts);
    double a1 = targetBounds.width;
    double a2 = targetBounds.height;
    double b1 = bounds.width;
    double b2 = bounds.height;
    double scale = Math.sqrt(a1 * a1 + a2 * a2) / Math.sqrt(b1 * b1 + b2 * b2);
    scale(pts, scale, scale, bounds.x, bounds.y);
  }
  
  /**
   * Normalize points
   */
  private List<Point> normalize(List<Point> pts, Double x, Double y, Double width, Double height)
  {
    List<Point> out = null;
    
    if (x != null)
    {
      out = deepCopyPts(pts);
      scaleTo(out, new Rect(0, 0, width - x, height - y));
      Centroid c = getCentroid(out);
      translate(out, -c.x, -c.y);
      translate(out, width - x, height - y);
    }
    else
    {
      scaleTo(pts, NORMALIZED_SPACE);
      Centroid c = getCentroid(pts);
      translate(pts, -c.x, -c.y);
    }
    
    return out;
  }
  
  /**
   * Normalize points (simple version)
   */
  private void normalize(List<Point> pts)
  {
    normalize(pts, null, null, null, null);
  }
  
  /**
   * Complete normalization per research paper (centering + bounding box scaling)
   */
  private void normalizeCompletely(List<Point> pts)
  {
    if (pts.isEmpty()) return;
    
    // Step 1: Calculate centroid and translate to origin
    Centroid centroid = getCentroid(pts);
    translate(pts, -centroid.x, -centroid.y);
    
    // Step 2: Scale to unit bounding box (CRITICAL - was missing!)
    Rect bounds = getBoundingBox(pts);
    double maxDimension = Math.max(bounds.width, bounds.height);
    
    if (maxDimension > 0)
    {
      double scaleFactor = 1.0 / maxDimension; // Scale to unit bounding box
      scale(pts, scaleFactor, scaleFactor);
    }
  }
  
  /**
   * Set template set (simplified for parallel processing)
   */
  public void setTemplateSet(List<Template> templates)
  {
    patterns.clear();
    
    android.util.Log.d("CGR", "Processing " + templates.size() + " templates for parallel recognition...");
    
    for (Template t : templates)
    {
      // FIX: Don't normalize templates here - they should already be in correct coordinate space
      // normalize(t.pts); // REMOVED: This was corrupting template coordinates
      
      // MEMORY OPTIMIZATION: Choose between real-time vs memory efficiency
      List<List<Point>> segments;
      
      // OPTION 1: Real-time predictions (COMMENTED OUT due to memory constraints)
      // TO RE-ENABLE REAL-TIME PREDICTIONS:
      // 1. Uncomment the line below
      // 2. Comment out the single segment option
      // 3. Test on device with more memory or reduce vocabulary size
      // segments = generateEquiDistantProgressiveSubSequences(t.pts, 400); // Fewer segments for memory
      
      // OPTION 2: Memory-efficient single segment (CURRENT - prevents OutOfMemoryError)
      segments = new ArrayList<>();
      segments.add(deepCopyPts(t.pts)); // Use copy to preserve original template
      
      Pattern pattern = new Pattern(t, segments);
      patterns.add(pattern);
    }
    
    // FIX: Skip template preprocessing that corrupts coordinates
    // Templates should be used as-is from WordGestureTemplateGenerator
    // The paper's approach normalizes during comparison, not during template creation
    
    // Create permanent partitions for parallel processing (no copying during recognition)
    patternPartitions.clear();
    int partitionSize = (patterns.size() + THREAD_COUNT - 1) / THREAD_COUNT; // Round up division
    
    for (int i = 0; i < THREAD_COUNT; i++)
    {
      int startIdx = i * partitionSize;
      int endIdx = Math.min(startIdx + partitionSize, patterns.size());
      
      if (startIdx < patterns.size())
      {
        List<Pattern> partition = patterns.subList(startIdx, endIdx);
        patternPartitions.add(partition);
      }
    }
    
    android.util.Log.d("CGR", "Created " + patternPartitions.size() + " permanent partitions for " + patterns.size() + " patterns");
  }
  
  /**
   * Marginalize incremental results
   */
  private void marginalizeIncrementalResults(List<IncrementalResult> results)
  {
    double totalMass = 0.0;
    for (IncrementalResult r : results)
    {
      totalMass += r.prob;
    }
    
    for (IncrementalResult r : results)
    {
      r.prob = r.prob / totalMass;
    }
  }
  
  /**
   * Get squared Euclidean distance
   */
  private double getSquaredEuclideanDistance(Point pt1, Point pt2)
  {
    return (pt1.x - pt2.x) * (pt1.x - pt2.x) + (pt1.y - pt2.y) * (pt1.y - pt2.y);
  }
  
  /**
   * Get Euclidean distance
   */
  private double getEuclideanDistance(Point pt1, Point pt2)
  {
    return Math.sqrt(getSquaredEuclideanDistance(pt1, pt2));
  }
  
  /**
   * Get Euclidean distance by list
   */
  private double getEuclideanDistanceByList(List<Point> pts1, List<Point> pts2)
  {
    int n = pts1.size();
    if (pts1.size() != pts2.size())
    {
      n = Math.min(pts1.size(), pts2.size());
    }
    
    double td = 0;
    for (int i = 0; i < n; i++)
    {
      td += getEuclideanDistance(pts1.get(i), pts2.get(i));
    }
    
    return td / n;
  }
  
  /**
   * Get turning angle distance between two line segments
   */
  private double getTurningAngleDistance(Point ptA1, Point ptA2, Point ptB1, Point ptB2)
  {
    double len_a = getEuclideanDistance(ptA1, ptA2);
    double len_b = getEuclideanDistance(ptB1, ptB2);
    
    if (len_a == 0 || len_b == 0)
    {
      return 0.0;
    }
    
    double cos = (((ptA1.x - ptA2.x) * (ptB1.x - ptB2.x) + (ptA1.y - ptA2.y) * (ptB1.y - ptB2.y)) / (len_a * len_b));
    
    if (Math.abs(cos) > 1.0)
    {
      return 0.0;
    }
    
    return Math.acos(cos);
  }
  
  /**
   * Get turning angle distance between point lists
   */
  private double getTurningAngleDistance(List<Point> pts1, List<Point> pts2)
  {
    int n = pts1.size();
    if (pts1.size() != pts2.size())
    {
      n = Math.min(pts1.size(), pts2.size());
    }
    
    double td = 0;
    for (int i = 0; i < n - 1; i++)
    {
      td += Math.abs(getTurningAngleDistance(pts1.get(i), pts1.get(i + 1), pts2.get(i), pts2.get(i + 1)));
    }
    
    if (Double.isNaN(td))
    {
      return 0.0;
    }
    
    return td / (n - 1);
  }
  
  /**
   * Get likelihood of match
   */
  private double getLikelihoodOfMatch(List<Point> pts1, List<Point> pts2, double eSigma, double aSigma, double lambda)
  {
    if (eSigma <= 0)
    {
      throw new IllegalArgumentException("eSigma must be positive");
    }
    
    if (aSigma <= 0)
    {
      throw new IllegalArgumentException("aSigma must be positive");
    }
    
    if (lambda < 0 || lambda > 1)
    {
      throw new IllegalArgumentException("lambda must be in the range between zero and one");
    }
    
    // DEBUG: Check if turning angle calculation is causing zero probabilities
    double x_e = getEuclideanDistanceByList(pts1, pts2);
    double x_a = getTurningAngleDistance(pts1, pts2);
    
    android.util.Log.d("CGR", String.format("Distance calc: x_e=%.6f, x_a=%.6f, eSigma=%.1f, aSigma=%.1f", 
                      x_e, x_a, eSigma, aSigma));
    
    // Temporarily restore combined distance to debug
    double result = Math.exp(-(x_e * x_e / (eSigma * eSigma) * lambda + x_a * x_a / (aSigma * aSigma) * (1 - lambda)));
    android.util.Log.d("CGR", String.format("Probability result: %.6f", result));
    
    return result;
  }
  
  /**
   * Get incremental result (ORIGINAL - kept for compatibility)
   */
  private IncrementalResult getIncrementalResult(List<Point> unkPts, Pattern pattern, double beta, double lambda, double e_sigma)
  {
    List<List<Point>> segments = pattern.segments;
    double maxProb = 0.0;
    int maxIndex = -1;
    
    for (int i = 0; i < segments.size(); i++)
    {
      List<Point> templatePts = segments.get(i);
      // PAPER'S APPROACH: Resample user gesture to template size, then center both (no resizing)
      List<Point> userResampledToTemplate = resample(unkPts, templatePts.size());
      
      // Apply paper's normalization: "translating them so their centroids are at origin"
      // Template bounding box already matches keyboard - just center both for translation invariance
      List<Point> centeredUser = deepCopyPts(userResampledToTemplate);
      List<Point> centeredTemplate = deepCopyPts(templatePts);
      
      // Complete normalization per research paper (centering + unit bounding box)
      normalizeCompletely(centeredUser);
      normalizeCompletely(centeredTemplate);
      
      double prob = getLikelihoodOfMatch(centeredUser, centeredTemplate, e_sigma, e_sigma / beta, lambda);
      
      if (prob > maxProb)
      {
        maxProb = prob;
        maxIndex = i;
      }
    }
    
    return new IncrementalResult(pattern, maxProb, maxIndex);
  }
  
  /**
   * Get incremental result (OPTIMIZED - no repeated resampling)
   */
  private IncrementalResult getIncrementalResultOptimized(List<Point> standardizedInput, Pattern pattern, double beta, double lambda, double e_sigma)
  {
    List<List<Point>> segments = pattern.segments;
    double maxProb = 0.0;
    int maxIndex = -1;
    
    for (int i = 0; i < segments.size(); i++)
    {
      List<Point> pts = segments.get(i);
      // OPTIMIZATION: Use pre-resampled standardized input (all segments now have FIXED_POINT_COUNT)
      double prob = getLikelihoodOfMatch(standardizedInput, pts, e_sigma, e_sigma / beta, lambda);
      
      if (prob > maxProb)
      {
        maxProb = prob;
        maxIndex = i;
      }
    }
    
    return new IncrementalResult(pattern, maxProb, maxIndex);
  }
  
  /**
   * Get results from incremental results
   */
  public List<Result> getResults(List<IncrementalResult> incrResults)
  {
    List<Result> results = new ArrayList<>();
    
    for (IncrementalResult ir : incrResults)
    {
      Result r = new Result(ir.pattern.template, ir.prob, ir.pattern.segments.get(ir.indexOfMostLikelySegment));
      results.add(r);
    }
    
    return results;
  }
  
  /**
   * Get incremental results (MEMORY OPTIMIZED - permanent partitions)
   */
  private List<IncrementalResult> getIncrementalResults(List<Point> input, double beta, double lambda, double kappa, double e_sigma)
  {
    List<IncrementalResult> incrResults = new ArrayList<>();
    // FIX: Don't normalize input here - normalize during comparison per paper's approach
    List<Point> unkPts = deepCopyPts(input);
    
    // KEYBOARD OPTIMIZATION: Pre-filter by length before expensive recognition
    double userGestureLength = getSpatialLength(unkPts);
    List<List<Pattern>> lengthFilteredPartitions = new ArrayList<>();
    
    for (List<Pattern> partition : patternPartitions)
    {
      List<Pattern> lengthFiltered = new ArrayList<>();
      for (Pattern pattern : partition)
      {
        double templateLength = getSpatialLength(pattern.template.pts);
        double lengthRatio = Math.min(templateLength, userGestureLength) / Math.max(templateLength, userGestureLength);
        
        // Only process templates with user-configurable length similarity (keyboard constraint)
        if (lengthRatio > currentLengthFilter) // User-configurable threshold (default 70%)
        {
          lengthFiltered.add(pattern);
        }
      }
      lengthFilteredPartitions.add(lengthFiltered);
    }
    
    // Use permanent partitions (no copying) for memory efficiency
    List<Future<List<IncrementalResult>>> futures = new ArrayList<>();
    
    int totalCandidates = lengthFilteredPartitions.stream().mapToInt(List::size).sum();
    android.util.Log.d("CGR", "Length-filtered " + patterns.size() + " to " + totalCandidates + 
                      " candidates (user length: " + String.format("%.0f", userGestureLength) + ")");
    
    // Submit each filtered partition to thread pool (no data copying)
    for (int i = 0; i < lengthFilteredPartitions.size(); i++)
    {
      final List<Pattern> partition = lengthFilteredPartitions.get(i);
      final int partitionId = i;
      
      Future<List<IncrementalResult>> future = parallelExecutor.submit(() -> {
        android.util.Log.d("CGR", "Thread " + partitionId + " processing " + partition.size() + " patterns");
        return processPartition(unkPts, partition, beta, lambda, kappa, e_sigma);
      });
      futures.add(future);
    }
    
    // Collect results from all partitions
    try
    {
      for (Future<List<IncrementalResult>> future : futures)
      {
        List<IncrementalResult> partitionResults = future.get(2000, TimeUnit.MILLISECONDS); // 2 second timeout
        incrResults.addAll(partitionResults);
      }
    }
    catch (Exception e)
    {
      android.util.Log.e("CGR", "Parallel processing error: " + e.getMessage());
      // Fallback to single-threaded processing
      return getIncrementalResultsSingleThreaded(input, beta, lambda, kappa, e_sigma);
    }
    
    marginalizeIncrementalResults(incrResults);
    return incrResults;
  }
  
  /**
   * Process a permanent partition of patterns (no copying)
   */
  private List<IncrementalResult> processPartition(List<Point> unkPts, List<Pattern> partition, double beta, double lambda, double kappa, double e_sigma)
  {
    List<IncrementalResult> batchResults = new ArrayList<>();
    
    for (Pattern pattern : partition)
    {
      IncrementalResult result = getIncrementalResult(unkPts, pattern, beta, lambda, e_sigma);
      List<Point> lastSegmentPts = pattern.segments.get(pattern.segments.size() - 1);
      // PAPER'S APPROACH: Resample user gesture to match pre-computed template size
      List<Point> userResampledToTemplate = resample(unkPts, lastSegmentPts.size());
      double completeProb = getLikelihoodOfMatch(userResampledToTemplate, lastSegmentPts, e_sigma, e_sigma / beta, lambda);
      double x = 1 - completeProb;
      result.prob = (1 + kappa * Math.exp(-x * x)) * result.prob;
      batchResults.add(result);
    }
    
    return batchResults;
  }
  
  /**
   * Create batches of patterns for parallel processing
   */
  private List<List<Pattern>> createBatches(List<Pattern> allPatterns, int batchSize)
  {
    List<List<Pattern>> batches = new ArrayList<>();
    
    for (int i = 0; i < allPatterns.size(); i += batchSize)
    {
      int endIndex = Math.min(i + batchSize, allPatterns.size());
      batches.add(new ArrayList<>(allPatterns.subList(i, endIndex)));
    }
    
    return batches;
  }
  
  /**
   * Fallback single-threaded processing (FIXED: Now includes length filtering)
   */
  private List<IncrementalResult> getIncrementalResultsSingleThreaded(List<Point> input, double beta, double lambda, double kappa, double e_sigma)
  {
    List<IncrementalResult> incrResults = new ArrayList<>();
    // FIX: Don't normalize input here - normalize during comparison per paper's approach  
    List<Point> unkPts = deepCopyPts(input);
    
    // CRITICAL FIX: Add same length filtering as parallel path
    double userGestureLength = getSpatialLength(unkPts);
    List<Pattern> lengthFilteredPatterns = new ArrayList<>();
    
    for (Pattern pattern : patterns)
    {
      double templateLength = getSpatialLength(pattern.template.pts);
      double lengthRatio = Math.min(templateLength, userGestureLength) / Math.max(templateLength, userGestureLength);
      
      // Apply same length filter as parallel processing
      if (lengthRatio > currentLengthFilter)
      {
        lengthFilteredPatterns.add(pattern);
      }
    }
    
    android.util.Log.d("CGR", "FALLBACK: Length-filtered " + patterns.size() + " to " + lengthFilteredPatterns.size() + 
                      " candidates (filter=" + String.format("%.0f", currentLengthFilter * 100) + "%)");
    
    for (Pattern pattern : lengthFilteredPatterns)
    {
      IncrementalResult result = getIncrementalResult(unkPts, pattern, beta, lambda, e_sigma);
      List<Point> lastSegmentPts = pattern.segments.get(pattern.segments.size() - 1);
      // PAPER'S APPROACH: Resample user gesture to match pre-computed template size
      List<Point> userResampledToTemplate = resample(unkPts, lastSegmentPts.size());
      double completeProb = getLikelihoodOfMatch(userResampledToTemplate, lastSegmentPts, e_sigma, e_sigma / beta, lambda);
      double x = 1 - completeProb;
      result.prob = (1 + kappa * Math.exp(-x * x)) * result.prob;
      incrResults.add(result);
    }
    
    return incrResults;
  }
  
  
  /**
   * Load CGR parameters from preferences (called automatically)
   */
  public void loadParametersFromPreferences(Context context)
  {
    try
    {
      // Use DirectBootAwarePreferences to match settings system
      SharedPreferences prefs = DirectBootAwarePreferences.get_shared_preferences(context);
      
      // Load CGR algorithm parameters with immediate effect
      currentESigma = prefs.getInt("cgr_e_sigma", 120);  // Keyboard-optimal default
      currentBeta = prefs.getInt("cgr_beta", 400);
      currentLambda = prefs.getInt("cgr_lambda", 65) / 100.0; // Keyboard-optimal: 65%
      currentKappa = prefs.getInt("cgr_kappa", 25) / 10.0;    // Keyboard-optimal: 2.5
      currentLengthFilter = prefs.getInt("cgr_length_filter", 70) / 100.0; // User-configurable filter
      
      android.util.Log.d("CGR", String.format("Parameters loaded from settings: σₑ=%.1f, β=%.1f, λ=%.2f, κ=%.1f, LengthFilter=%.1f%%", 
                        currentESigma, currentBeta, currentLambda, currentKappa, currentLengthFilter * 100));
    }
    catch (Exception e)
    {
      android.util.Log.w("CGR", "Failed to load parameters, using defaults: " + e.getMessage());
      // Keep default values if loading fails
    }
  }
  
  /**
   * Set CGR parameters for tuning (called from settings)
   */
  public void setCGRParameters(double eSigma, double beta, double lambda, double kappa)
  {
    currentESigma = eSigma;
    currentBeta = beta;
    currentLambda = lambda;
    currentKappa = kappa;
    
    android.util.Log.d("CGR", String.format("Parameters updated: σₑ=%.1f, β=%.1f, λ=%.2f, κ=%.1f", 
                      eSigma, beta, lambda, kappa));
  }
  
  /**
   * Main recognition function (uses current configurable parameters)
   */
  public List<Result> recognize(List<Point> input)
  {
    return recognize(input, currentBeta, currentLambda, currentKappa, currentESigma);
  }
  
  /**
   * Main recognition function with parameters
   */
  public List<Result> recognize(List<Point> input, double beta, double lambda, double kappa, double e_sigma)
  {
    if (input.size() < 2)
    {
      throw new IllegalArgumentException("CGR_recognize: Input must consist of at least two points");
    }
    
    List<IncrementalResult> incResults = getIncrementalResults(input, beta, lambda, kappa, e_sigma);
    List<Result> results = getResults(incResults);
    
    Collections.sort(results, new Comparator<Result>()
    {
      @Override
      public int compare(Result a, Result b)
      {
        return Double.compare(b.prob, a.prob); // Descending order
      }
    });
    
    return results;
  }
  
  /**
   * Create directional templates (compass points)
   */
  public static List<Template> createDirectionalTemplates()
  {
    List<Template> templates = new ArrayList<>();
    
    // North
    List<Point> nPoints = new ArrayList<>();
    nPoints.add(new Point(0, 0));
    nPoints.add(new Point(0, -1));
    templates.add(new Template("North", nPoints));
    
    // South
    List<Point> sPoints = new ArrayList<>();
    sPoints.add(new Point(0, 0));
    sPoints.add(new Point(0, 1));
    templates.add(new Template("South", sPoints));
    
    // West
    List<Point> wPoints = new ArrayList<>();
    wPoints.add(new Point(0, 0));
    wPoints.add(new Point(-1, 0));
    templates.add(new Template("West", wPoints));
    
    // East
    List<Point> ePoints = new ArrayList<>();
    ePoints.add(new Point(0, 0));
    ePoints.add(new Point(1, 0));
    templates.add(new Template("East", ePoints));
    
    // NorthWest
    List<Point> nwPoints = new ArrayList<>();
    nwPoints.add(new Point(0, 0));
    nwPoints.add(new Point(-1, -1));
    templates.add(new Template("NorthWest", nwPoints));
    
    // NorthEast
    List<Point> nePoints = new ArrayList<>();
    nePoints.add(new Point(0, 0));
    nePoints.add(new Point(1, -1));
    templates.add(new Template("NorthEast", nePoints));
    
    // SouthWest
    List<Point> swPoints = new ArrayList<>();
    swPoints.add(new Point(0, 0));
    swPoints.add(new Point(-1, 1));
    templates.add(new Template("SouthWest", swPoints));
    
    // SouthEast
    List<Point> sePoints = new ArrayList<>();
    sePoints.add(new Point(0, 0));
    sePoints.add(new Point(1, 1));
    templates.add(new Template("SouthEast", sePoints));
    
    return templates;
  }
  
  /**
   * Create templates from PointF array (for Android integration)
   */
  public static List<Point> fromPointFList(List<PointF> pointFs)
  {
    List<Point> points = new ArrayList<>();
    for (PointF pf : pointFs)
    {
      points.add(new Point(pf.x, pf.y));
    }
    return points;
  }
  
  /**
   * Convert back to PointF array (for Android integration)
   */
  public static List<PointF> toPointFList(List<Point> points)
  {
    List<PointF> pointFs = new ArrayList<>();
    for (Point p : points)
    {
      pointFs.add(new PointF((float)p.x, (float)p.y));
    }
    return pointFs;
  }
}