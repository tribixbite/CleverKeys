#!/bin/bash
# Run standalone Kotlin ONNX test without APK installation

echo "Compiling Kotlin test..."

# Find ONNX runtime JAR
ONNX_JAR=$(find .gradle/caches -name "onnxruntime-android-*.jar" | head -1)

if [ -z "$ONNX_JAR" ]; then
    echo "❌ ONNX runtime JAR not found in Gradle cache"
    echo "Running gradle build to download dependencies..."
    ./gradlew dependencies >/dev/null 2>&1
    ONNX_JAR=$(find .gradle/caches -name "onnxruntime-android-*.jar" | head -1)
fi

if [ -z "$ONNX_JAR" ]; then
    echo "❌ Still can't find ONNX runtime JAR"
    exit 1
fi

echo "✅ Found ONNX runtime: $ONNX_JAR"

# Compile
echo "Compiling TestOnnxPrediction.kt..."
kotlinc -cp "$ONNX_JAR:." TestOnnxPrediction.kt -include-runtime -d TestOnnxPrediction.jar

if [ $? -ne 0 ]; then
    echo "❌ Compilation failed"
    exit 1
fi

echo "✅ Compilation successful"
echo ""

# Run
echo "Running test..."
java -cp "$ONNX_JAR:TestOnnxPrediction.jar" TestOnnxPredictionKt
