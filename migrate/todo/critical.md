# Critical TODOs

This file lists showstopper bugs and immediate fixes required to get the keyboard functional.

---

## 🔧 IMMEDIATE FIXES NEEDED (Priority Order)

### **PRIORITY 1: QUICK WINS (Get keyboard functional - 1-2 days)**

**Fix #51: Config.handler = null (SHOWSTOPPER)**
- File: src/main/kotlin/tribixbite/keyboard2/CleverKeysService.kt:109
- Change: `Config.initGlobalConfig(prefs, resources, null, false)`
- To: `Config.initGlobalConfig(prefs, resources, keyEventHandler, false)`
- Impact: **FIXES KEYS NOT WORKING** ✅
- Time: 5 minutes

**Fix #52: Container Architecture (CRITICAL)**
- File: src/main/kotlin/tribixbite/keyboard2/CleverKeysService.kt:351-413
- Change: Create LinearLayout container in onCreateInputView()
- Add suggestion bar on top, keyboard view below
- Impact: **FIXES PREDICTION BAR + BOTTOM BAR** ✅
- Time: 2-3 hours

**Fix #53: Text Size Calculation (HIGH)**
- File: src/main/kotlin/tribixbite/keyboard2/Keyboard2View.kt:487-488
- Replace hardcoded `keyWidth * 0.4f` with Java's dynamic calculation
- Add Config.characterSize, labelTextSize, sublabelTextSize multipliers
- Impact: **FIXES TEXT SIZE** ✅
- Time: 1-2 hours

**TOTAL TIME FOR FUNCTIONAL KEYBOARD**: ~4-6 hours

### **PRIORITY 2: CRITICAL MISSING FILES (1-2 weeks)**

**KeyValueParser.java → KeyValueParser.kt (CRITICAL)**
- Status: 96% missing (276/289 lines)
- Port all 5 syntax modes, regex patterns, error handling
- Impact: **FIXES CHINESE CHARACTER BUG** ✅
- Time: 2-3 days

**Missing 12+ Keyboard2 components:**
- updateContext(), handlePredictionResults(), onSuggestionSelected()
- handleRegularTyping(), handleBackspace(), updatePredictionsForCurrentWord()
- calculateDynamicKeyboardHeight(), handleSwipeTyping() (complete version)
- Impact: Full feature parity with Java
- Time: 1-2 weeks

### **PRIORITY 3: SYSTEMATIC REVIEW (12-16 weeks)**

Continue file-by-file review of remaining 247 files:
- 25+ Java files completely missing from Kotlin
- Detailed method-by-method comparison of 80+ shared files
- Resource file validation (layouts, values, drawables)

---

## 📝 NEXT STEPS WHEN SESSION RESUMES

1. **Apply Fix #51 (handler)** - 5 minute fix for keys working
2. **Build and test** - Verify keys work with 1-line change
3. **Apply Fix #52 (container)** - 2-3 hour fix for prediction/bottom bar
4. **Apply Fix #53 (text size)** - 1-2 hour fix for label sizing
5. **Build and test** - Verify keyboard is now functional
6. **Port KeyValueParser** - Fix Chinese character bug (2-3 days)
7. **Continue systematic review** - Files 5-251

---

## 🚨 CRITICAL: When resuming, start with Fix #51 (5-minute fix)

```kotlin
// CleverKeysService.kt line 109
// BEFORE:
Config.initGlobalConfig(prefs, resources, null, false)

// AFTER:
Config.initGlobalConfig(prefs, resources, keyEventHandler, false)
```

This single line change will make keys work!
