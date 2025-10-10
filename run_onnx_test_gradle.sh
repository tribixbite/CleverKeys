#!/bin/bash
# Run Complete ONNX CLI Test using Gradle
# This loads real ONNX models and runs actual inference

set -e

echo "🧪 CleverKeys Complete ONNX CLI Test (Gradle)"
echo "==============================================="
echo ""

# Check for models
MODELS_DIR="assets/models"
ENCODER_MODEL="$MODELS_DIR/swipe_model_character_quant.onnx"
DECODER_MODEL="$MODELS_DIR/swipe_decoder_character_quant.onnx"

if [ ! -f "$ENCODER_MODEL" ] || [ ! -f "$DECODER_MODEL" ]; then
    echo "❌ ONNX models not found in $MODELS_DIR"
    echo ""
    echo "Required files:"
    echo "   - swipe_model_character_quant.onnx (encoder)"
    echo "   - swipe_decoder_character_quant.onnx (decoder)"
    echo ""
    exit 1
fi

echo "✅ ONNX models found:"
echo "   Encoder: $(du -h "$ENCODER_MODEL" | cut -f1)"
echo "   Decoder: $(du -h "$DECODER_MODEL" | cut -f1)"
echo ""

# Build and run using Gradle
cd cli-test

echo "🔨 Building test with Gradle..."
../gradlew --quiet build

if [ $? -ne 0 ]; then
    echo "❌ Build failed"
    exit 1
fi

echo "✅ Build successful"
echo ""

echo "🚀 Running complete ONNX neural prediction test..."
echo ""

../gradlew --quiet runTest

exit $?
