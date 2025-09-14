# Neural Swipe Prediction Models

This directory contains the ONNX models for neural swipe prediction:

## Expected Files:
- `swipe_encoder.onnx` - Transformer encoder model (trajectory -> memory)
- `swipe_decoder.onnx` - Transformer decoder model (memory -> words) 
- `tokenizer.json` - Character tokenizer configuration

## Model Architecture:
- **Input**: Trajectory features [x, y, vx, vy, ax, ay] with nearest key indices
- **Encoder**: Transformer encoder producing memory states
- **Decoder**: Transformer decoder with beam search for word generation
- **Output**: Top-k word predictions with confidence scores

## Training:
Models should be trained using the web demo as reference and converted to ONNX format.
The training pipeline should use calibration data collected from SwipeCalibrationActivity.

## Fallback Behavior:
If models are missing, the system will automatically fall back to the legacy 
Bayesian/DTW prediction system with no user-visible changes.