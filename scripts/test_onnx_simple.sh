#!/bin/bash
# Quick test with patched Android ONNX Runtime

echo "🧪 Quick ONNX Test with Android Native Libs"
echo "==========================================="
echo ""

if [ ! -f "onnxruntime-1.20.0-android.jar" ]; then
    echo "❌ Patched Android JAR not found"
    echo "Run the following to create it:"
    echo "  1. mkdir -p ~/tmp/onnx-jar-fix"
    echo "  2. cd ~/tmp/onnx-jar-fix && unzip onnxruntime-1.20.0.jar"
    echo "  3. cp ~/tmp/onnx-android/jni/arm64-v8a/*.so ai/onnxruntime/native/linux-aarch64/"
    echo "  4. jar cf onnxruntime-1.20.0-android.jar *"
    echo "  5. cp onnxruntime-1.20.0-android.jar ~/git/swype/cleverkeys/"
    exit 1
fi

echo "✅ Found patched Android ONNX Runtime JAR"
echo ""

echo "🚀 Running test..."
java -classpath "test_onnx_cli.jar:onnxruntime-1.20.0-android.jar" TestOnnxCliKt

exit $?
