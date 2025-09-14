package juloo.keyboard2;

import android.graphics.PointF;
import java.util.List;

/**
 * Sophisticated swipe detection using multiple factors
 */
public class SwipeDetector
{
  // Thresholds for swipe detection
  private static final float MIN_PATH_LENGTH = 50.0f;
  private static final float MIN_DURATION = 0.15f; // seconds
  private static final float MAX_DURATION = 3.0f; // seconds
  private static final int MIN_DIRECTION_CHANGES = 1;
  private static final float MIN_KEYBOARD_COVERAGE = 100.0f;
  private static final float MIN_AVERAGE_VELOCITY = 50.0f; // pixels per second
  private static final float MAX_VELOCITY_VARIATION = 500.0f;
  
  /**
   * Classification result for input
   */
  public static class SwipeClassification
  {
    public final boolean isSwipe;
    public final float confidence;
    public final String reason;
    public final SwipeQuality quality;
    
    public enum SwipeQuality
    {
      HIGH,     // Clear, deliberate swipe
      MEDIUM,   // Acceptable swipe
      LOW,      // Ambiguous, might be typing
      NOT_SWIPE // Definitely not a swipe
    }
    
    public SwipeClassification(boolean isSwipe, float confidence, String reason, SwipeQuality quality)
    {
      this.isSwipe = isSwipe;
      this.confidence = confidence;
      this.reason = reason;
      this.quality = quality;
    }
  }
  
  /**
   * Classify input as swipe or regular typing
   */
  public SwipeClassification classifyInput(SwipeInput input)
  {
    // Quick rejection checks
    if (input.coordinates.size() < 3)
    {
      return new SwipeClassification(false, 0.0f, "Too few points", 
                                    SwipeClassification.SwipeQuality.NOT_SWIPE);
    }
    
    if (input.duration < MIN_DURATION)
    {
      return new SwipeClassification(false, 0.1f, "Too fast (likely tap)", 
                                    SwipeClassification.SwipeQuality.NOT_SWIPE);
    }
    
    if (input.duration > MAX_DURATION)
    {
      return new SwipeClassification(false, 0.1f, "Too slow (likely typing)", 
                                    SwipeClassification.SwipeQuality.NOT_SWIPE);
    }
    
    // Calculate multi-factor confidence score
    float confidence = 0;
    StringBuilder reasoning = new StringBuilder();
    
    // Factor 1: Path length (30% weight)
    float pathLengthScore = calculatePathLengthScore(input.pathLength);
    confidence += pathLengthScore * 0.3f;
    if (pathLengthScore > 0.5f)
      reasoning.append("Good path length; ");
    
    // Factor 2: Duration (20% weight)
    float durationScore = calculateDurationScore(input.duration);
    confidence += durationScore * 0.2f;
    if (durationScore > 0.5f)
      reasoning.append("Good duration; ");
    
    // Factor 3: Direction changes (20% weight)
    float directionScore = calculateDirectionScore(input.directionChanges);
    confidence += directionScore * 0.2f;
    if (directionScore > 0.5f)
      reasoning.append("Multiple directions; ");
    
    // Factor 4: Velocity consistency (15% weight)
    float velocityScore = calculateVelocityScore(input.velocityProfile, input.averageVelocity);
    confidence += velocityScore * 0.15f;
    if (velocityScore > 0.5f)
      reasoning.append("Consistent velocity; ");
    
    // Factor 5: Keyboard coverage (15% weight)
    float coverageScore = calculateCoverageScore(input.keyboardCoverage);
    confidence += coverageScore * 0.15f;
    if (coverageScore > 0.5f)
      reasoning.append("Good coverage; ");
    
    // Determine classification
    boolean isSwipe = confidence > 0.5f;
    SwipeClassification.SwipeQuality quality;
    
    if (confidence > 0.8f)
    {
      quality = SwipeClassification.SwipeQuality.HIGH;
    }
    else if (confidence > 0.6f)
    {
      quality = SwipeClassification.SwipeQuality.MEDIUM;
    }
    else if (confidence > 0.4f)
    {
      quality = SwipeClassification.SwipeQuality.LOW;
    }
    else
    {
      quality = SwipeClassification.SwipeQuality.NOT_SWIPE;
    }
    
    String reason = reasoning.length() > 0 ? reasoning.toString() : "Low confidence factors";
    
    android.util.Log.d("SwipeDetector", String.format(
      "Classification: isSwipe=%s, confidence=%.2f, quality=%s, reason=%s",
      isSwipe, confidence, quality, reason));
    
    return new SwipeClassification(isSwipe, confidence, reason, quality);
  }
  
  private float calculatePathLengthScore(float pathLength)
  {
    if (pathLength < MIN_PATH_LENGTH)
      return 0;
    if (pathLength > 500)
      return 1.0f;
    // Linear interpolation between min and optimal
    return (pathLength - MIN_PATH_LENGTH) / (500 - MIN_PATH_LENGTH);
  }
  
  private float calculateDurationScore(float duration)
  {
    // Optimal swipe duration is 0.3 - 1.2 seconds
    if (duration < 0.3f || duration > 2.0f)
      return 0.2f;
    if (duration >= 0.3f && duration <= 1.2f)
      return 1.0f;
    // Gradual decrease outside optimal range
    if (duration < 0.3f)
      return duration / 0.3f;
    else
      return Math.max(0.2f, 2.0f - duration);
  }
  
  private float calculateDirectionScore(int directionChanges)
  {
    if (directionChanges < MIN_DIRECTION_CHANGES)
      return 0;
    if (directionChanges >= 5)
      return 1.0f;
    // More direction changes = more likely a swipe
    return directionChanges / 5.0f;
  }
  
  private float calculateVelocityScore(List<Float> velocityProfile, float averageVelocity)
  {
    if (velocityProfile.isEmpty())
      return 0;
      
    // Check if velocity is reasonable
    if (averageVelocity < MIN_AVERAGE_VELOCITY)
      return 0.1f;
    
    // Calculate velocity variation
    float sum = 0;
    float sumSquared = 0;
    for (float v : velocityProfile)
    {
      sum += v;
      sumSquared += v * v;
    }
    
    float mean = sum / velocityProfile.size();
    float variance = (sumSquared / velocityProfile.size()) - (mean * mean);
    float stdDev = (float)Math.sqrt(variance);
    
    // Swipes have relatively consistent velocity
    if (stdDev > MAX_VELOCITY_VARIATION)
      return 0.3f;
    
    // Lower variation = higher score
    float variationScore = Math.max(0, 1.0f - (stdDev / MAX_VELOCITY_VARIATION));
    
    // Combine with average velocity score
    float avgScore = Math.min(1.0f, averageVelocity / 300.0f);
    
    return (variationScore * 0.6f) + (avgScore * 0.4f);
  }
  
  private float calculateCoverageScore(float keyboardCoverage)
  {
    if (keyboardCoverage < MIN_KEYBOARD_COVERAGE)
      return 0;
    if (keyboardCoverage > 400)
      return 1.0f;
    // Linear interpolation
    return (keyboardCoverage - MIN_KEYBOARD_COVERAGE) / (400 - MIN_KEYBOARD_COVERAGE);
  }
  
  /**
   * Determine if we should use DTW prediction based on swipe quality
   */
  public boolean shouldUseDTW(SwipeClassification classification)
  {
    return classification.quality == SwipeClassification.SwipeQuality.HIGH ||
           classification.quality == SwipeClassification.SwipeQuality.MEDIUM;
  }
}