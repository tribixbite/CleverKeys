#!/bin/bash
# Smart runner for ONNX CLI test on Termux
# Automatically patches the ONNX Runtime JAR with Android native libraries if needed.

# Configuration
ONNX_ORIG="onnxruntime-1.20.0.jar"
ONNX_PATCHED="onnxruntime-1.20.0-android.jar"
TEST_SRC="tools/standalone_tests/test_onnx_cli.kt"
TEST_JAR="test_onnx_cli.jar"
BUILD_NATIVE_PATH="build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${YELLOW}🧪 CleverKeys ONNX Test Runner${NC}"
echo "========================================"

# 1. Check/Create Patched JAR
if [ ! -f "$ONNX_PATCHED" ]; then
    echo -e "${YELLOW}⚠️  Patched JAR not found. Attempting to build it...${NC}"

    if [ ! -f "$ONNX_ORIG" ]; then
        echo -e "${RED}❌ Error: Original $ONNX_ORIG not found in root.${NC}"
        echo "   Please ensure you have onnxruntime-1.20.0.jar in the project root."
        exit 1
    fi

    if [ ! -d "$BUILD_NATIVE_PATH" ]; then
        echo -e "${RED}❌ Error: Native libs not found in build output.${NC}"
        echo "   Please run './build-on-termux.sh debug' first to generate native libs."
        exit 1
    fi

    echo "   🔨 Patching JAR with Android native libraries..."
    
    # Create temp workspace
    TEMP_DIR=$(mktemp -d)
    
    # Extract original JAR
    unzip -q "$ONNX_ORIG" -d "$TEMP_DIR"
    
    # Create target directory for Termux/ARM64 (it mimics linux-aarch64)
    mkdir -p "$TEMP_DIR/ai/onnxruntime/native/linux-aarch64"
    
    # Copy Android .so files to the linux-aarch64 path expected by the JAR on Termux
    cp "$BUILD_NATIVE_PATH/libonnxruntime.so" "$TEMP_DIR/ai/onnxruntime/native/linux-aarch64/"
    cp "$BUILD_NATIVE_PATH/libonnxruntime4j_jni.so" "$TEMP_DIR/ai/onnxruntime/native/linux-aarch64/"
    
    # Repackage
    jar cf "$ONNX_PATCHED" -C "$TEMP_DIR" .
    
    # Cleanup
    rm -rf "$TEMP_DIR"
    
    if [ -f "$ONNX_PATCHED" ]; then
        echo -e "${GREEN}✅ Successfully created $ONNX_PATCHED${NC}"
    else
        echo -e "${RED}❌ Failed to create patched JAR.${NC}"
        exit 1
    fi
else
    echo -e "${GREEN}✅ Found patched JAR: $ONNX_PATCHED${NC}"
fi

# 2. Compile Test
echo -e "${YELLOW}📦 Compiling test script...${NC}"
kotlinc -cp "$ONNX_PATCHED" "$TEST_SRC" -include-runtime -d "$TEST_JAR"

if [ $? -ne 0 ]; then
    echo -e "${RED}❌ Compilation failed.${NC}"
    exit 1
fi

# 3. Run Test
echo -e "${YELLOW}🚀 Running test...${NC}"
echo "----------------------------------------"
java -cp "$TEST_JAR:$ONNX_PATCHED" Test_onnx_cliKt
EXIT_CODE=$?
echo "----------------------------------------"

if [ $EXIT_CODE -eq 0 ]; then
    echo -e "${GREEN}✅ Test PASSED${NC}"
    # Clean up test artifact (keep the patched jar for reuse)
    rm "$TEST_JAR"
else
    echo -e "${RED}❌ Test FAILED${NC}"
fi

exit $EXIT_CODE