# CleverKeys Manual Testing Guide

**Version**: 1.32.1 (Build 50+)
**Date**: 2025-11-14
**Focus**: Verify TODO resolution integrations + initialization order fix

## 🐛 Critical Bug Fix (Build 50+)
**Initialization Order Bug Fixed**: WordPredictor now properly receives LanguageDetector and UserAdaptationManager references (previously null due to wrong initialization order).

**Impact**: Language detection and user adaptation features should now work correctly.

---

## 🎯 Priority 1: Prediction Pipeline Integration

### WordPredictor + BigramModel + LanguageDetector
**What Changed**: Integrated data package prediction models with async loading

**Test Procedure**:
1. Open any text field (Chrome, Messages, Notes)
2. Type partial words: "hel" → Should show "hello", "help", "held"
3. Type contextual phrases: "the quick" → Next word should prioritize "brown", "fox"
4. Check logcat for:
   ```
   ✅ BigramModel loaded from assets (bigrams_en.json)
   ✅ WordPredictor dictionary loaded (XXXXX words)
   ```

**Expected Results**:
- ✅ Predictions appear in suggestion bar
- ✅ Context-aware predictions based on previous word
- ✅ Dictionary loads without errors (check logcat)
- ✅ No crashes when typing

**Potential Issues**:
- Empty dictionary if assets fail to load
- Slow predictions if loading blocks UI thread
- Missing bigrams if JSON parsing fails

---

## 🎯 Priority 2: Long Press Auto-Repeat

### Backspace and Arrow Keys
**What Changed**: Implemented auto-repeat via config.handler.key_down()

**Test Procedure**:
1. Type: "hello world test"
2. Long-press backspace key (hold for 2+ seconds)
3. Long-press left arrow key
4. Long-press right arrow key

**Expected Results**:
- ✅ Backspace deletes multiple characters while held
- ✅ Arrows move cursor continuously while held
- ✅ Smooth repetition (50ms intervals)
- ✅ Haptic feedback on initial long press

**Potential Issues**:
- No repeat if LongPressManager not wired to touch handler
- Too fast/slow repeat if intervals wrong
- Crash if config.handler is null

---

## 🎯 Priority 3: Dictionary Integration

### SwipePruner with WordPredictor
**What Changed**: Replaced 8-word placeholder with real 50k+ word dictionary

**Test Procedure**:
1. Perform swipe gestures for common words:
   - Swipe "hello" (h → e → l → l → o)
   - Swipe "world" (w → o → r → l → d)
   - Swipe "keyboard" (k → e → y → b → o → a → r → d)
2. Check logcat for:
   ```
   Dictionary size: XXXXX words
   ⚠️ Dictionary is empty - WordPredictor may still be loading
   ```

**Expected Results**:
- ✅ Dictionary loads (non-zero size in logs)
- ✅ Swipe predictions use real words
- ✅ No "Dictionary is empty" warning after load complete

**Potential Issues**:
- Empty dictionary if getDictionary() called before async load
- SwipePruner initialized with empty map
- Asset loading failure (missing dictionary_en.json)

---

## 🎯 Priority 4: User Adaptation

### Personalized Word Frequency
**What Changed**: Integrated UserAdaptationManager with SharedPreferences

**Test Procedure**:
1. Type a unique word 5 times: "xylophone"
2. Clear and start typing: "xy..."
3. "xylophone" should appear higher in suggestions

**Expected Results**:
- ✅ Word usage tracked in SharedPreferences
- ✅ Frequently typed words boosted in predictions
- ✅ Adaptation multiplier increases with usage

**Potential Issues**:
- No adaptation if manager not wired to WordPredictor
- SharedPreferences not persisting
- Multiplier calculation incorrect

---

## 🎯 Priority 5: Language Detection

### Multi-Language Support
**What Changed**: Using data.LanguageDetector with WordPredictor

**Test Procedure**:
1. Type English text: "hello world"
2. Switch to Spanish layout (if available)
3. Type Spanish text: "hola mundo"
4. Check if language auto-detects

**Expected Results**:
- ✅ Language detector initializes
- ✅ Language switches based on typing patterns
- ✅ Predictions match detected language

**Potential Issues**:
- Detection not working if not wired to WordPredictor
- No language-specific dictionaries loaded
- Auto-detection too aggressive

---

## 🔍 Logcat Monitoring Commands

### Check Initialization Success
```bash
adb logcat -s CleverKeys:D | grep "✅"
```

### Check for Errors
```bash
adb logcat -s CleverKeys:E AndroidRuntime:E
```

### Monitor Prediction Performance
```bash
adb logcat -s CleverKeys:D | grep "completed in"
```

### Check Dictionary Loading
```bash
adb logcat -s CleverKeys:D | grep -E "(BigramModel|WordPredictor|Dictionary)"
```

---

## ⚠️ Known Limitations

### Deferred Features
1. **Emoji Picker UI** (TODO line 4081)
   - switchToEmojiLayout() logs message but doesn't show picker
   - Full emoji system requires separate implementation phase

2. **Long Press Popup UI** (TODO line 967)
   - onLongPress() returns false (no popup shown)
   - Alternate character selection not functional
   - Requires custom PopupWindow implementation

### Expected Warnings
- "Dictionary is empty" during cold start (resolves after async load)
- "BigramModel asset loading failed" if bigrams_XX.json missing
- "WordPredictor dictionary loading failed" if dictionary_XX.json missing

---

## 📊 Success Criteria

### Minimal Viable Product
- ✅ Tap typing shows predictions
- ✅ Swipe typing generates words
- ✅ Auto-repeat works for backspace
- ✅ Dictionary loads (non-zero size)
- ✅ No crashes during normal use

### Full Feature Set
- ✅ Context-aware predictions (bigrams)
- ✅ User adaptation (frequently typed words boosted)
- ✅ Language detection working
- ✅ All 30 TODOs implemented correctly
- ✅ Zero runtime errors in logcat

---

## 🐛 Reporting Issues

If you encounter issues, provide:
1. **Steps to reproduce**
2. **Expected behavior**
3. **Actual behavior**
4. **Logcat output** (CleverKeys:D and AndroidRuntime:E)
5. **APK version** (Build 50+)

Example:
```
Issue: WordPredictor shows no predictions
Steps: 1. Type "hel" in Chrome
Expected: Suggestions appear
Actual: Empty suggestion bar
Logcat: "Dictionary size: 0 words"
```

---

## 📝 Test Results Template

```markdown
## Manual Test Results (Date: ______)

### Prediction Pipeline
- [ ] WordPredictor shows predictions
- [ ] BigramModel loaded from assets
- [ ] Context-aware predictions work
- [ ] Dictionary loads (>10k words)

### Long Press Features
- [ ] Auto-repeat works for backspace
- [ ] Auto-repeat works for arrows
- [ ] Haptic feedback on long press

### Dictionary Integration
- [ ] SwipePruner uses real dictionary
- [ ] Dictionary size non-zero
- [ ] No "empty dictionary" warning

### User Adaptation
- [ ] Frequent words boosted
- [ ] SharedPreferences persists data
- [ ] Adaptation multiplier increases

### Language Detection
- [ ] Language detector initializes
- [ ] Language switches correctly
- [ ] Predictions match language

### Issues Found
1. (None / List issues here)
```
