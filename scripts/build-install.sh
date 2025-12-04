#!/data/data/com.termux/files/usr/bin/bash
# CleverKeys Build & Install Script
# Builds APK and automatically installs it

set -e

echo "========================================="
echo "CleverKeys Build & Install"
echo "========================================="
echo ""

# Step 1: Clean build
echo "🧹 Cleaning build artifacts..."
./gradlew clean > /dev/null 2>&1

# Step 2: Generate layouts
echo "📐 Generating keyboard layouts..."
if [ -f "gen_layouts.py" ]; then
    python3 gen_layouts.py > /dev/null 2>&1 || echo "⚠️  Layout generation skipped"
fi

# Step 3: Build APK
echo "📦 Building APK..."
echo ""

if ! ./gradlew assembleDebug --console=plain 2>&1 | tail -20; then
    echo ""
    echo "❌ Build failed!"
    exit 1
fi

echo ""
echo "✅ Build successful!"
echo ""

# Step 2: Install APK
echo "📲 Installing APK..."
echo ""

exec ./install.sh
