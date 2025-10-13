#!/bin/bash
# Run standalone Kotlin ONNX test without APK installation

echo "Compiling Kotlin test..."

# Use local lib directory (no Gradle cache dependency)
ONNX_RUNTIME="lib/onnx/onnxruntime-android-1.20.0-runtime.jar"
ONNX_API="lib/onnx/onnxruntime-android-1.20.0-api.jar"
ONNX_NATIVE="lib/onnx/native/arm64-v8a"

if [ ! -f "$ONNX_RUNTIME" ] || [ ! -f "$ONNX_API" ]; then
    echo "❌ ONNX JAR files not found in lib/onnx/"
    echo "Expected:"
    echo "  - $ONNX_RUNTIME"
    echo "  - $ONNX_API"
    exit 1
fi

if [ ! -d "$ONNX_NATIVE" ]; then
    echo "❌ ONNX native libraries not found at $ONNX_NATIVE"
    exit 1
fi

echo "✅ Found ONNX runtime: $ONNX_RUNTIME"
echo "✅ Found ONNX API: $ONNX_API"
echo "✅ Found native libs: $ONNX_NATIVE"

# Compile
echo ""
echo "Compiling TestOnnxPrediction.kt..."
kotlinc -cp "$ONNX_RUNTIME:$ONNX_API" TestOnnxPrediction.kt -include-runtime -d TestOnnxPrediction.jar

if [ $? -ne 0 ]; then
    echo "❌ Compilation failed"
    exit 1
fi

echo "✅ Compilation successful: TestOnnxPrediction.jar"
echo ""

# Run
echo "Running test..."
java -Djava.library.path="$ONNX_NATIVE" -cp "$ONNX_RUNTIME:$ONNX_API:TestOnnxPrediction.jar" TestOnnxPredictionKt
