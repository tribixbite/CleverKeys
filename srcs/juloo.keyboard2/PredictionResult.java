package juloo.keyboard2;

import java.util.List;

/**
 * Result container for word predictions with scores
 * Used by both legacy and neural prediction systems
 */
public class PredictionResult
{
  public final List<String> words;
  public final List<Integer> scores; // Scores as integers (0-1000 range)
  
  public PredictionResult(List<String> words, List<Integer> scores)
  {
    this.words = words;
    this.scores = scores;
  }
}