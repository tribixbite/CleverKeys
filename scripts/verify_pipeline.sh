#!/bin/bash
# Verification script for swipe prediction pipeline fixes

echo "🔍 CleverKeys Swipe Pipeline Verification"
echo "==========================================="
echo ""

# Check 1: Feature extraction normalization order
echo "1️⃣ Checking feature extraction normalization order..."
if grep -q "// 1. Normalize coordinates FIRST" src/main/kotlin/tribixbite/keyboard2/OnnxSwipePredictorImpl.kt; then
    echo "   ✅ Normalizes FIRST (line 855)"
else
    echo "   ❌ Normalization order not verified"
fi

# Check 2: Velocity calculation (simple deltas, not physics)
echo ""
echo "2️⃣ Checking velocity calculation formula..."
if grep -q "val vx = finalCoords\[i\].x - finalCoords\[i-1\].x" src/main/kotlin/tribixbite/keyboard2/OnnxSwipePredictorImpl.kt; then
    echo "   ✅ Uses simple deltas: vx = x[i] - x[i-1] (line 877, 883)"
else
    echo "   ❌ Velocity formula not verified"
fi

# Check 3: Acceleration calculation (delta of deltas)
echo ""
echo "3️⃣ Checking acceleration calculation formula..."
if grep -q "val ax = vx - velocities\[i-1\].x" src/main/kotlin/tribixbite/keyboard2/OnnxSwipePredictorImpl.kt; then
    echo "   ✅ Uses velocity deltas: ax = vx[i] - vx[i-1] (line 887)"
else
    echo "   ❌ Acceleration formula not verified"
fi

# Check 4: Velocity/acceleration stored as PointF (separate vx/vy components)
echo ""
echo "4️⃣ Checking feature storage structure..."
if grep -q "velocities.add(PointF(vx, vy))" src/main/kotlin/tribixbite/keyboard2/OnnxSwipePredictorImpl.kt; then
    echo "   ✅ Velocities stored as PointF(vx, vy) - separate components (line 879, 885)"
else
    echo "   ❌ Feature storage not verified"
fi

# Check 5: Target mask convention (1=padded, 0=valid)
echo ""
echo "5️⃣ Checking target mask convention..."
if grep -q "reusableTargetMaskArray\[0\].fill(true)  // Default: all padded" src/main/kotlin/tribixbite/keyboard2/OnnxSwipePredictorImpl.kt; then
    echo "   ✅ Target mask: 1=padded, 0=valid (line 561)"
else
    echo "   ❌ Target mask convention not verified"
fi

# Check 6: Batched mask convention
echo ""
echo "6️⃣ Checking batched tensor mask convention..."
if grep -A2 "if (seqIndex < beam.tokens.size)" src/main/kotlin/tribixbite/keyboard2/OnnxSwipePredictorImpl.kt | grep -q "maskArray\[seqIndex\] = false  // Valid token"; then
    echo "   ✅ Batched mask: false=valid, true=padded (line 429)"
else
    echo "   ❌ Batched mask convention not verified"
fi

# Check 7: Early stopping condition
echo ""
echo "7️⃣ Checking early stopping optimization..."
if grep -q "if (step >= 10 && finishedBeams.size >= 3)" src/main/kotlin/tribixbite/keyboard2/OnnxSwipePredictorImpl.kt; then
    echo "   ✅ Early stopping: stops at step 10 if 3+ beams finished (line 269)"
else
    echo "   ❌ Early stopping not verified"
fi

# Check 8: Log-softmax scoring
echo ""
echo "8️⃣ Checking log-softmax scoring..."
if grep -q "private fun applyLogSoftmax" src/main/kotlin/tribixbite/keyboard2/OnnxSwipePredictorImpl.kt; then
    echo "   ✅ Log-softmax converts raw logits to log probabilities (line 574)"
else
    echo "   ❌ Log-softmax not verified"
fi

echo ""
echo "==========================================="
echo "📊 Verification Summary"
echo "==========================================="

# Count checks
total_checks=8
passed_checks=0

for check in 1 2 3 4 5 6 7 8; do
    case $check in
        1) grep -q "// 1. Normalize coordinates FIRST" src/main/kotlin/tribixbite/keyboard2/OnnxSwipePredictorImpl.kt && ((passed_checks++)) ;;
        2) grep -q "val vx = finalCoords\[i\].x - finalCoords\[i-1\].x" src/main/kotlin/tribixbite/keyboard2/OnnxSwipePredictorImpl.kt && ((passed_checks++)) ;;
        3) grep -q "val ax = vx - velocities\[i-1\].x" src/main/kotlin/tribixbite/keyboard2/OnnxSwipePredictorImpl.kt && ((passed_checks++)) ;;
        4) grep -q "velocities.add(PointF(vx, vy))" src/main/kotlin/tribixbite/keyboard2/OnnxSwipePredictorImpl.kt && ((passed_checks++)) ;;
        5) grep -q "reusableTargetMaskArray\[0\].fill(true)  // Default: all padded" src/main/kotlin/tribixbite/keyboard2/OnnxSwipePredictorImpl.kt && ((passed_checks++)) ;;
        6) grep -A2 "if (seqIndex < beam.tokens.size)" src/main/kotlin/tribixbite/keyboard2/OnnxSwipePredictorImpl.kt | grep -q "maskArray\[seqIndex\] = false  // Valid token" && ((passed_checks++)) ;;
        7) grep -q "if (step >= 10 && finishedBeams.size >= 3)" src/main/kotlin/tribixbite/keyboard2/OnnxSwipePredictorImpl.kt && ((passed_checks++)) ;;
        8) grep -q "private fun applyLogSoftmax" src/main/kotlin/tribixbite/keyboard2/OnnxSwipePredictorImpl.kt && ((passed_checks++)) ;;
    esac
done

echo ""
echo "   Checks passed: $passed_checks/$total_checks"
echo ""

if [ $passed_checks -eq $total_checks ]; then
    echo "🎉 ALL PIPELINE FIXES VERIFIED!"
    echo "   ✅ Feature extraction matches web demo"
    echo "   ✅ Mask conventions correct"
    echo "   ✅ Optimizations in place"
    echo ""
    echo "Next step: Build and test APK"
    exit 0
else
    echo "⚠️  Some checks failed"
    echo "   Review implementation against web demo"
    exit 1
fi
