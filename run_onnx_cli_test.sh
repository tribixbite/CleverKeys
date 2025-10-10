#!/bin/bash
# Complete ONNX Neural Pipeline CLI Test Runner
# Loads real ONNX models and runs actual inference

set -e

echo "🧪 CleverKeys Complete ONNX CLI Test"
echo "======================================="
echo ""

# Check for required tools
if ! command -v kotlinc &> /dev/null; then
    echo "❌ kotlinc not installed"
    echo ""
    echo "Install on Termux:"
    echo "   pkg install kotlin"
    echo ""
    exit 1
fi

# Check for ONNX models
MODELS_DIR="assets/models"
ENCODER_MODEL="$MODELS_DIR/swipe_model_character_quant.onnx"
DECODER_MODEL="$MODELS_DIR/swipe_decoder_character_quant.onnx"

if [ ! -f "$ENCODER_MODEL" ]; then
    echo "❌ Encoder model not found: $ENCODER_MODEL"
    exit 1
fi

if [ ! -f "$DECODER_MODEL" ]; then
    echo "❌ Decoder model not found: $DECODER_MODEL"
    exit 1
fi

echo "✅ ONNX models found:"
echo "   Encoder: $(du -h "$ENCODER_MODEL" | cut -f1)"
echo "   Decoder: $(du -h "$DECODER_MODEL" | cut -f1)"
echo ""

# Download ONNX Runtime if not present
ONNX_JAR="onnxruntime-1.20.0.jar"
ONNX_URL="https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.20.0/onnxruntime-1.20.0.jar"

if [ ! -f "$ONNX_JAR" ]; then
    echo "📥 Downloading ONNX Runtime..."
    if command -v curl &> /dev/null; then
        curl -L -o "$ONNX_JAR" "$ONNX_URL"
    elif command -v wget &> /dev/null; then
        wget -O "$ONNX_JAR" "$ONNX_URL"
    else
        echo "❌ Neither curl nor wget available"
        exit 1
    fi
    echo "✅ ONNX Runtime downloaded"
else
    echo "✅ ONNX Runtime found: $(du -h "$ONNX_JAR" | cut -f1)"
fi
echo ""

# Compile test
echo "🔨 Compiling test..."
kotlinc test_onnx_cli.kt \
    -classpath "$ONNX_JAR" \
    -include-runtime \
    -d test_onnx_cli.jar 2>&1 | grep -v "warning:" | head -20

if [ ! -f "test_onnx_cli.jar" ]; then
    echo "❌ Compilation failed"
    exit 1
fi

echo "✅ Compilation successful"
echo ""

# Run test
echo "🚀 Running complete ONNX neural prediction test..."
echo ""
java -classpath "test_onnx_cli.jar:$ONNX_JAR" TestOnnxCliKt

exit $?
