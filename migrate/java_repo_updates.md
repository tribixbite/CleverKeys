# Java Repository Updates Migration Todo

This document tracks features and fixes from the original Unexpected-Keyboard Java repository that need to be ported to CleverKeys Kotlin implementation.

**Analysis Date**: 2025-11-13
**Commits Analyzed**: Last 100 commits from Unexpected-Keyboard
**Total Commits Since 2024-10-01**: 421

---

## Critical Algorithm Updates

### 1. CGR Algorithm Improvements (HIGH PRIORITY)

#### Commits
```
464e9d8c fix(predictions): eliminate result mixing that corrupts KeyboardSwipeRecognizer rankings
185cf348 fix(letter-detection): solve over-detection issue with intelligent sampling
c04ef3a7 fix(sequence-score): improve letter detection with larger key zones and debugging
d443574e fix(compilation): remove stray KeyboardSwipeRecognizer reference
```

**Impact**: Core swipe recognition accuracy improvements
**Status**: ⚠️ NEEDS INVESTIGATION
**Action Items**:
- [ ] Review letter detection algorithm changes
- [ ] Port larger key zone logic
- [ ] Implement intelligent sampling fix
- [ ] Verify KeyboardSwipeRecognizer references removed
- [ ] Test swipe accuracy improvements

**Files to Review**:
- `srcs/juloo.keyboard2/KeyboardSwipeRecognizer.java`
- `srcs/juloo.keyboard2/SwipeGestureRecognizer.java` (if exists)

---

### 2. Coordinate System Fixes (HIGH PRIORITY)

#### Commits
```
c9743f47 feat(coordinate-system): implement 100% accurate real key position mapping
ca70ef85 fix(main-keyboard): resolve coordinate system mismatch causing wrong key detection
6b91180f feat(main-keyboard): implement dynamic keyboard dimension support matching calibration
52829cb5 fix(main-keyboard): add missing keyboard dimensions to CGR recognizer
```

**Impact**: Fixes coordinate system mismatch that causes wrong key detection
**Status**: 🔴 CRITICAL - May affect CleverKeys accuracy
**Action Items**:
- [ ] Audit CleverKeys coordinate system implementation
- [ ] Compare with Java "100% accurate real key position mapping"
- [ ] Verify keyboard dimensions passed to recognizer
- [ ] Test coordinate accuracy with various screen sizes
- [ ] Port dynamic dimension support

**Files to Review**:
- `srcs/juloo.keyboard2/Keyboard2View.java`
- `srcs/juloo.keyboard2/KeyValue.java`
- `srcs/juloo.keyboard2/KeyboardData.java`

---

### 3. Fuzzy Matching Implementation (MEDIUM PRIORITY)

#### Commits
```
a4e338f5 Revert "feat(sequence-scoring): implement fuzzy matching for dramatic accuracy improvement"
0fba7a91 feat(sequence-scoring): implement fuzzy matching for dramatic accuracy improvement
```

**Impact**: "Dramatic accuracy improvement" (later reverted - investigate why)
**Status**: ⚠️ NEEDS INVESTIGATION
**Action Items**:
- [ ] Review why fuzzy matching was reverted
- [ ] Understand the original implementation
- [ ] Determine if partial implementation is beneficial
- [ ] Test accuracy impact

**Files to Review**:
- Commit diffs for `0fba7a91` and `a4e338f5`

---

### 4. Dictionary Expansion (LOW PRIORITY)

#### Commits
```
2eafdd41 feat(dictionary): expand from 5,000 to 10,000 words for comprehensive recognition
```

**Impact**: Improved word recognition with larger dictionary
**Status**: ✅ EASY PORT
**Action Items**:
- [ ] Copy expanded dictionary from Java repo
- [ ] Update CleverKeys dictionary asset
- [ ] Verify no performance regression
- [ ] Test word recognition accuracy

**Files to Copy**:
- `res/raw/dictionary.txt` (or equivalent)

---

## New Features

### 5. Calibration/Playground Feature (MEDIUM PRIORITY)

#### Commits
```
017c6a6e feat(calibration): comprehensive UI overhaul for improved usability and debugging
a65a5afd feat(calibration): add comprehensive proximity score calculation breakdown
fcb4b536 fix(calibration): implement actual calculated DTW equation values and fix parameter flow
3744eab9 feat(calibration): implement comprehensive algorithm equation breakdown with real calculated values
5c11a27a debug(calibration): add comprehensive logging and fix DTW equation display order
e9c409a9 feat(playground): implement comprehensive parameter export/import system
4d955b12 feat(browser): add Template Browser and comprehensive score debugging
```

**Impact**: New developer/debugging tool for algorithm tuning
**Status**: 🤔 CONSIDER - Useful for development, not end-user feature
**Action Items**:
- [ ] Review calibration UI implementation
- [ ] Determine if needed for CleverKeys (debugging vs production)
- [ ] If needed: Port calibration activity
- [ ] If needed: Port parameter export/import
- [ ] If needed: Port template browser

**Decision**: Should CleverKeys include calibration tools or focus on production features?

**Files to Review**:
- `srcs/juloo.keyboard2/CalibrationActivity.java` (if exists)
- `srcs/juloo.keyboard2/PlaygroundActivity.java` (if exists)

---

### 6. Algorithm DTW → CGR Migration (CRITICAL)

#### Commits
```
234d159d feat(main-keyboard): replace broken DTW with working CGR algorithm
123b784d fix(calibration): replace broken DTW with working CGR algorithm equation breakdown
```

**Impact**: Core algorithm change from DTW to CGR
**Status**: 🔴 CRITICAL - CleverKeys uses ONNX, but may need fallback
**Action Items**:
- [ ] Understand why DTW was "broken"
- [ ] Review CGR implementation details
- [ ] Determine if CGR should be CleverKeys fallback (when ONNX unavailable)
- [ ] Compare CGR vs ONNX accuracy
- [ ] Consider hybrid approach

**Files to Review**:
- `srcs/juloo.keyboard2/CurveGestureRecognizer.java` (CGR implementation)
- `srcs/juloo.keyboard2/DTWRecognizer.java` (old DTW - for comparison)

---

## Bug Fixes

### 7. Crash Fixes & Safety (HIGH PRIORITY)

#### Commits
```
1828c333 fix(critical): resolve exact crash source identified by Gemini expert analysis
1f1c04d3 fix(crashes): comprehensive array bounds safety for all prediction pipeline arrays
cce72842 fix(algorithm): implement simplified crash-free recognition algorithm
```

**Impact**: Prevents crashes in prediction pipeline
**Status**: ⚠️ NEEDS AUDIT
**Action Items**:
- [ ] Review array bounds safety implementations
- [ ] Audit CleverKeys for similar crash risks
- [ ] Add bounds checking where needed
- [ ] Port crash-free algorithm improvements

**Files to Review**:
- All prediction pipeline files
- Array access patterns in swipe recognition

---

### 8. UI Layout Fixes (LOW PRIORITY)

#### Commits
```
7adc7eeb fix(layout): comprehensive UI layout fixes based on corrected screenshot analysis
f807c2ef fix(ui): remove Delete Samples button and identify remaining issues
a6f70884 fix(critical): restore swipe data display and fix layout structure
```

**Impact**: UI improvements and bug fixes
**Status**: ✅ COMPARE & PORT
**Action Items**:
- [ ] Compare Java UI layouts with CleverKeys
- [ ] Port applicable UI improvements
- [ ] Verify no regressions in CleverKeys UI

---

### 9. Gesture Handling Fixes (MEDIUM PRIORITY)

#### Commits
```
d2d8e701 fix(main-keyboard): resolve gesture interruption causing two separate predictions
309ab80e debug(main-keyboard): fix swipe detection and add comprehensive logging
294b7a79 debug(swipe): add comprehensive coordinate and key detection comparison logging
```

**Impact**: Prevents swipe gesture interruptions
**Status**: ⚠️ TEST IN CLEVERKEYS
**Action Items**:
- [ ] Review gesture interruption fix
- [ ] Test CleverKeys for similar issues
- [ ] Port fix if applicable
- [ ] Add logging for debugging

---

## Implementation Priority

### P0 - Critical (Implement First)
1. **Coordinate System Fixes** (#2)
   - Directly affects accuracy
   - High risk of wrong key detection

2. **DTW → CGR Migration** (#6)
   - Core algorithm change
   - May need as ONNX fallback

3. **Crash Fixes & Safety** (#7)
   - Production stability
   - User experience

### P1 - High Priority (Implement Soon)
4. **CGR Algorithm Improvements** (#1)
   - Accuracy improvements
   - Letter detection fixes

5. **Gesture Handling Fixes** (#9)
   - User experience
   - Prevents double predictions

### P2 - Medium Priority (Consider)
6. **Fuzzy Matching** (#3)
   - Investigate revert reason first
   - Potential accuracy boost

7. **Calibration/Playground** (#5)
   - Developer tool, not end-user
   - Useful for debugging

### P3 - Low Priority (Nice to Have)
8. **Dictionary Expansion** (#4)
   - Easy port
   - Incremental improvement

9. **UI Layout Fixes** (#8)
   - Cosmetic improvements

---

## Migration Workflow

### Step 1: Investigation Phase
For each priority item:
1. **Read Java commits**: Understand what changed and why
2. **Review diffs**: See actual code changes
3. **Identify impact**: Does it affect CleverKeys?
4. **Test CleverKeys**: Does the same bug exist?

### Step 2: Decision Phase
1. **Port directly**: If feature/fix is applicable
2. **Adapt for Kotlin**: If architecture differs
3. **Skip**: If not relevant (ONNX vs CGR)
4. **Document**: Record decision in this file

### Step 3: Implementation Phase
1. **Create feature branch**: `feature/port-java-XXXX`
2. **Port code**: Kotlin idiomatic implementation
3. **Write tests**: Validate functionality
4. **Update docs**: Specs, CLAUDE.md
5. **Commit**: Reference Java commit hash

### Step 4: Validation Phase
1. **Unit tests**: Logic validation
2. **Integration tests**: System integration
3. **Manual testing**: Real device validation
4. **Performance tests**: No regressions

---

## Tracking

### Commits Reviewed: 50/100

**Next Batch to Review**:
```
e2140930 feat(functional): implement fully functional playground with live parameter updates
... (50 more commits)
```

### Ported Features: 0/9

**Progress**: 0%

---

## Notes

- **ONNX vs CGR**: CleverKeys uses ONNX neural prediction instead of CGR algorithm. Need to decide if CGR should be fallback or if ONNX supersedes it entirely.

- **Calibration Feature**: Java repo has extensive calibration/debugging UI. Determine if this is needed for CleverKeys development or skip for production focus.

- **Fuzzy Matching Revert**: Investigate why "dramatic accuracy improvement" was reverted. May indicate algorithm stability issue.

- **Coordinate System**: Most critical issue. Affects all swipe recognition. Must audit CleverKeys implementation.

---

**Last Updated**: 2025-11-13
**Reviewed By**: Claude Code
**Status**: Initial Analysis Complete - Implementation Pending
