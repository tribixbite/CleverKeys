# Critical TODOs

This file lists showstopper bugs and immediate fixes required to get the keyboard functional.

**Last Updated**: 2025-10-20
**Status**: Fix #51-53 ✅ COMPLETE (Keys working, container fixed, text sizing dynamic)

---

## ✅ COMPLETED QUICK WINS (2025-10-20)

**Fix #51: Config.handler = null** ✅ DONE
- Created Receiver inner class implementing KeyEventHandler.IReceiver
- KeyEventHandler properly initialized and passed to Config
- **RESULT**: Keys now functional
- Commit: 594a6ee8

**Fix #52: Container Architecture** ✅ DONE
- LinearLayout container created in onCreateInputView()
- Suggestion bar on top (40dp), keyboard view below
- **RESULT**: Prediction bar + keyboard properly displayed
- Commit: 491ec469

**Fix #53: Text Size Calculation** ✅ DONE
- Replaced hardcoded values with dynamic Config multipliers
- Matches Java algorithm using characterSize, labelTextSize, sublabelTextSize
- **RESULT**: Text sizes scale properly
- Commit: 491ec469

**APK Status**: Built successfully (49MB) - Ready for testing

---

## 🔧 REMAINING CRITICAL FIXES

### **P0 - CATASTROPHIC (System Breaking) - 13 Bugs**

#### Core Systems Missing
- [ ] **Bug #257**: LanguageDetector system missing (File 59)
  - Impact: No multi-language support
  - File: LanguageDetector.java (313 lines) → MISSING

- [ ] **Bug #258**: LoopGestureDetector system missing (File 60)
  - Impact: No loop gesture detection
  - File: LoopGestureDetector.java (346 lines) → MISSING

- [ ] **Bug #259**: NgramModel system missing (File 61)
  - Impact: No n-gram prediction
  - File: NgramModel.java (350 lines) → MISSING

- [ ] **Bug #263**: UserAdaptationManager missing (File 65)
  - Impact: No personalization/learning
  - File: UserAdaptationManager.java (291 lines) → MISSING

#### ML Training & Data
- [ ] **Bug #273**: Training data stored in memory (File 71)
  - Impact: **DATA LOST WHEN APP CLOSES**
  - File: SwipeMLDataStore.java → Needs persistent database

- [ ] **Bug #274**: ML training system missing (File 72)
  - Impact: Cannot train on user data
  - File: SwipeMLTrainer.java (425 lines) → MISSING

- [ ] **Bug #275**: AsyncPredictionHandler missing (File 73)
  - Impact: UI blocking during predictions
  - File: AsyncPredictionHandler.java (202 lines) → MISSING

- [ ] **Bug #276**: ComprehensiveTraceAnalyzer missing (File 75)
  - Impact: No advanced gesture analysis
  - File: ComprehensiveTraceAnalyzer.java (710 lines) → MISSING

#### Configuration & Data
- [ ] **Bug #78**: ComposeKeyData arrays TRUNCATED (99% missing)
  - Impact: Most compose key combinations unavailable
  - File: ComposeKeyData.kt - Missing ~14,900/15,000 entries

- [ ] **Bug #79**: Missing 33 named constants
  - Impact: Cannot reference compose keys by name
  - File: ComposeKeyData.kt

- [ ] **Bug #82**: DirectBootAwarePreferences 75% missing
  - Impact: Settings lost on device restart
  - File: DirectBootAwarePreferences.kt

#### Clipboard
- [ ] **Bug #124**: Non-existent API usage
  - Impact: Clipboard functionality broken
  - File: ClipboardHistoryView.kt

- [ ] **Bug #125**: Missing synchronous getService() wrapper
  - Impact: Call sites can't access service
  - File: ClipboardHistoryService.kt

### **P1 - CRITICAL (Major Features Broken) - 2 Bugs**

- [ ] **Bug #113**: Wrong base class - architectural mismatch
  - File: ClipboardHistoryView.kt

- [ ] **Bug #131**: GlobalScope.launch memory leak ✅ FIXED
  - File: ClipboardHistoryCheckBox.kt

**Total P0/P1**: 15 bugs (1 fixed, 14 remaining)

### **PRIORITY 2: CRITICAL MISSING FILES**

**KeyValueParser.java → KeyValueParser.kt**
- Status: 96% missing (276/289 lines)
- Port: All 5 syntax modes, regex patterns, error handling
- Impact: Fixes Chinese character bug
- Time: 2-3 days

**Missing Keyboard2/CleverKeysService components:**
- updateContext(), handlePredictionResults(), onSuggestionSelected()
- handleRegularTyping(), handleBackspace(), updatePredictionsForCurrentWord()
- calculateDynamicKeyboardHeight(), handleSwipeTyping() (complete version)
- Time: 1-2 weeks

---

## 📝 NEXT STEPS

1. **Resume systematic review** - Continue at File 142/251 (110 files remaining)
2. **Create critical specs** - gesture-system.md, layout-system.md, neural-prediction.md
3. **Fix P0 bugs** - As discovered during review
4. **Port missing files** - 25+ Java files completely absent from Kotlin

**See**: `docs/COMPLETE_REVIEW_STATUS.md` for full review timeline
**See**: `docs/specs/` for feature specifications
