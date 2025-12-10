# User Settings vs Defaults (2025-12-10)

## Settings Differing from Default

| Setting | Your Value | Default | Impact |
|---------|------------|---------|--------|
| **Visual/Theme** ||||
| `theme` | `decorative_neon_cyberpunk` | `cleverkeysdark` | Cyberpunk neon theme |
| `swipe_trail_color` | `-65281` (magenta) | `-4144960` (silver) | Bright pink trail |
| `keyboard_height_percent` | `28` | `27` | 1% taller keyboard |
| **Neural Prediction** ||||
| `neural_beam_width` | `6` | `4` | 50% more candidates (slower, more accurate) |
| `neural_beam_alpha` | `1.548` | `1.0` | Higher length normalization |
| `neural_beam_score_gap` | `50.0` | `20.0` | 2.5x more aggressive pruning |
| `neural_beam_prune_confidence` | `0.327` | `0.178` | 84% higher confidence pruning |
| `neural_use_quantized` | `false` | `true` | Full precision (slower, ~2% more accurate) |
| **Swipe Gesture Tuning** ||||
| `swipe_dist` | `23` | `15` | 53% higher swipe sensitivity |
| `swipe_min_distance` | `71.8px` | `46.4px` | 55% longer min swipe travel |
| `swipe_min_key_distance` | `38.2px` | `35.2px` | 8% longer key-to-key distance |
| `short_gesture_min_distance` | `37` | `40` | Short swipes trigger 8% earlier |
| `short_gesture_max_distance` | `141` | `200` (disabled) | Short swipe cutoff at 141% key size |
| `swipe_common_words_boost` | `1.0625` | `1.0` | 6% boost to common words |
| **Custom Dictionary** ||||
| `disabled_words` | `{aa, b, ral}` | `{}` (empty) | 3 words blocklisted |

## Settings Matching Defaults (Verified)

- `neural_prediction_enabled`: true
- `word_prediction_enabled`: true
- `swipe_prediction_source`: 80
- `neural_max_length`: 20
- `neural_batch_beams`: false
- `neural_confidence_threshold`: ~0.01
- `autocorrect_prefix_length`: 1
- `autocorrect_confidence_min_frequency`: 100
- `swipe_rare_words_penalty`: 1.0
- `number_row`: no_number_row
- `number_entry_layout`: pin
- `pref_enable_multilang`: false
- `privacy_collect_swipe`: false
- `privacy_collect_performance`: false

## Summary

Tuned for **higher accuracy over speed**:
- Wider beam search (6 vs 4)
- Full precision models (not quantized)
- More aggressive pruning thresholds
- Longer minimum swipe distances
- Short gesture enabled with 141% cutoff
