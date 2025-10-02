# CleverKeys - Comprehensive Issue Report
Generated: October 2, 2025

## 🚨 CRITICAL ISSUES (App-Breaking)

### 1. Keyboard2View Config Initialization Crash Risk
**File:** `src/main/kotlin/tribixbite/keyboard2/Keyboard2View.kt:89`
**Issue:** `config = Config.globalConfig()` called in init block will crash if view is created outside InputMethodService context
**Impact:** Fatal crash if Keyboard2View is inflated from XML or created before CleverKeysService.onCreate()
**Fix Required:** Add null-safety check or lazy initialization

### 2. Missing ExtraKeysPreference Implementation
**File:** `src/main/kotlin/tribixbite/keyboard2/Config.kt:295`
**Issue:** `extra_keys_param = emptyMap() // TODO: Fix ExtraKeysPreference.get_extra_keys(prefs)`
**Impact:** Extra keys feature completely non-functional
**Fix Required:** Implement ExtraKeysPreference.get_extra_keys() or remove feature

### 3. Layout Switching Not Implemented
**Files:**
- `CleverKeysService.kt:198` - Layout switching
- `CleverKeysService.kt:202` - Numeric layout
- `CleverKeysService.kt:206` - Emoji layout
- `CleverKeysService.kt:210` - Settings opening
**Impact:** Users cannot switch between layouts at runtime
**Fix Required:** Implement proper layout switching handlers

### 4. CustomLayoutEditor Save/Load Stubs
**File:** `src/main/kotlin/tribixbite/keyboard2/CustomLayoutEditor.kt:121-127`
**Issue:** Save and load functions just show toast with "TODO"
**Impact:** Custom layouts cannot be persisted
**Fix Required:** Implement persistence to SharedPreferences

### 5. Termux Mode Setting Missing from UI
**File:** `res/xml/settings.xml`
**Issue:** `termux_mode_enabled` checkbox not present in neural settings screen
**Impact:** Users cannot enable Termux-compatible prediction insertion
**Status:** Variable exists in Config.kt but not exposed in settings XML
**Fix Required:** Add checkbox after `neural_prediction_enabled` in settings.xml

### 6. CustomExtraKeysPreference Not Implemented
**File:** Missing from `src/main/kotlin/tribixbite/keyboard2/prefs/`
**Issue:** Custom extra keys preference dialog not implemented
**Impact:** Users cannot configure custom extra keys via UI
**Referenced in:** res/xml/settings.xml line 7
**Fix Required:** Implement CustomExtraKeysPreference.kt with key picker and persistence

## ⚠️ HIGH PRIORITY ISSUES (Functionality Impact)

### 7. Hardware Acceleration Disabled
**File:** `AndroidManifest.xml:2,10`
**Issue:** `android:hardwareAccelerated="false"` globally disabled
**Impact:** Severe rendering performance degradation
**Fix Required:** Enable hardware acceleration, test ONNX compatibility

### 8. Key Event Handlers Not Implemented
**File:** `src/main/kotlin/tribixbite/keyboard2/KeyEventHandler.kt`
**Issues:**
- Line 129: Modifier key handling
- Line 137: Compose key handling
- Line 144: Caps lock toggle
- Line 268: Proper modifier checking
**Impact:** Special keys don't function correctly
**Fix Required:** Implement complete key event logic

### 9. External Storage Permissions (Android 11+)
**File:** `AndroidManifest.xml:7-8`
**Issue:** READ/WRITE_EXTERNAL_STORAGE deprecated on Android 11+
**Impact:** File access will fail on modern Android versions
**Fix Required:** Use scoped storage or MANAGE_EXTERNAL_STORAGE

### 10. Service Integration Broken in Keyboard2.kt
**File:** `src/main/kotlin/tribixbite/keyboard2/Keyboard2.kt:116`
**Issue:** `// TODO: Fix service integration - this@Keyboard2 is not CleverKeysService`
**Impact:** Keyboard2 class cannot communicate with service
**Fix Required:** Pass service reference or use interface

### 11. Missing Error Feedback
**File:** `src/main/kotlin/tribixbite/keyboard2/CleverKeysService.kt:507`
**Issue:** `// TODO: Implement user-visible error feedback`
**Impact:** Users don't see errors when predictions fail
**Fix Required:** Add toast/notification for errors

### 12. Performance Monitoring Not Cleaned Up
**File:** `src/main/kotlin/tribixbite/keyboard2/CleverKeysService.kt:673`
**Issue:** `// TODO: Stop performance monitoring` in onDestroy
**Impact:** Possible memory leak if monitoring not stopped
**Fix Required:** Implement cleanup logic

## 🔍 MEDIUM PRIORITY ISSUES (Quality/Stability)

### 13. Forced Unwraps (17 instances)
**Pattern:** `!!` operator used 17 times
**Risk:** NullPointerException if assumption is wrong
**Recommendation:** Replace with safe calls (?.) or null checks

### 14. Lateinit Properties (19 instances)
**Risk:** UninitializedPropertyAccessException
**Files:**
- SwipeCalibrationActivity.kt: 11 lateinit vars
- Keyboard2View.kt: 3 lateinit vars
- SettingsActivity.kt: 2 lateinit vars
- Others: 3 lateinit vars
**Recommendation:** Add initialization checks or use lazy initialization

### 15. Theme Propagation Incomplete
**File:** `src/main/kotlin/tribixbite/keyboard2/CleverKeysService.kt:654`
**Issue:** `// TODO: Propagate theme changes to active UI components`
**Impact:** Theme changes may not apply until restart
**Fix:** Implement proper theme update notification

### 16. Key Locking Not Implemented
**File:** `src/main/kotlin/tribixbite/keyboard2/Keyboard2View.kt:651`
**Issue:** `// TODO: Implement proper key locking check`
**Impact:** Key locking/sticky keys don't work
**Fix:** Implement lock state tracking

### 17. Emoji Preferences Not Loaded
**File:** `src/main/kotlin/tribixbite/keyboard2/Emoji.kt:102`
**Issue:** `// TODO: Load from preferences`
**Impact:** Emoji preferences don't persist
**Fix:** Load recent/favorite emoji from SharedPreferences

### 18. ConfigurationManager Theme Application
**File:** `src/main/kotlin/tribixbite/keyboard2/ConfigurationManager.kt:306`
**Issue:** `// TODO: Fix Theme.initialize(context).applyThemeToView(view, theme)`
**Impact:** Theme not fully applied to all UI components
**Fix:** Implement proper theme application

### 19. Input Connection Ctrl Modifier
**File:** `src/main/kotlin/tribixbite/keyboard2/InputConnectionManager.kt:343`
**Issue:** `// TODO: Check for ctrl modifier`
**Impact:** Ctrl+key shortcuts don't work
**Fix:** Add modifier state checking

### 20. Keyboard2.kt Layout Switching Duplicates
**File:** `src/main/kotlin/tribixbite/keyboard2/Keyboard2.kt:539-548`
**Issue:** Duplicate TODO for layout/emoji/numeric switching
**Impact:** Same as CleverKeysService issues
**Fix:** Consolidate or implement in one location

### 21. Empty Collection Returns
**Pattern:** Multiple `return emptyList()/emptyMap()` on error
**Risk:** Silent failures - errors not visible
**Files:** OnnxSwipePredictorImpl, Emoji, Utils, etc.
**Recommendation:** Log errors before returning empty collections

## 📝 LOW PRIORITY ISSUES (Cosmetic/Future)

### 22. Gradle Deprecation Warning
**Issue:** Convention API deprecated, scheduled for Gradle 9.0
**Impact:** Build will break in future Gradle versions
**Fix:** Migrate to new API when upgrading

### 23. Deprecated API Suppressions (3 instances)
**Files:**
- ClipboardHistoryService.kt:165
- VibratorCompat.kt:20,38
**Issue:** Using deprecated Android APIs
**Fix:** Migrate to modern alternatives

### 24. Hardcoded Strings in CustomLayoutEditDialog
**File:** `src/main/kotlin/tribixbite/keyboard2/CustomLayoutEditDialog.kt:46,54`
**Issue:** Hardcoded "Custom layout" and "Remove layout" strings
**Fix:** Use string resources (R.string)

### 25. Test Layout Interface Not Implemented
**File:** `src/main/kotlin/tribixbite/keyboard2/CustomLayoutEditor.kt:143-144`
**Issue:** `toast("Test layout (TODO: Implement test interface)")`
**Impact:** Cannot preview custom layouts before saving
**Fix:** Create test/preview activity

### 26. Key Editing Dialog Not Implemented
**File:** `src/main/kotlin/tribixbite/keyboard2/CustomLayoutEditor.kt:223`
**Issue:** `// TODO: Open key editing dialog`
**Impact:** Cannot edit individual keys in custom layouts
**Fix:** Create key properties dialog

### 27. Add Key to Layout Not Implemented
**File:** `src/main/kotlin/tribixbite/keyboard2/CustomLayoutEditor.kt:267`
**Issue:** `// TODO: Add key to layout`
**Impact:** Cannot add new keys to custom layouts
**Fix:** Implement key insertion logic

## 📊 ISSUE SUMMARY

| Priority | Count | Status |
|----------|-------|--------|
| Critical | 6 | 🔴 Blocking core functionality |
| High | 6 | 🟠 Significant feature gaps |
| Medium | 9 | 🟡 Quality/stability concerns |
| Low | 6 | 🟢 Minor improvements |
| **TOTAL** | **27** | |

## ✅ ASSETS VALIDATION

### ONNX Models (assets/models/)
- ✅ swipe_model_character_quant.onnx (5.1MB) - Present
- ✅ swipe_decoder_character_quant.onnx (7.0MB) - Present
- ✅ tokenizer.json (831 bytes) - Present

### Dictionaries (assets/dictionaries/)
- ✅ en.txt (76K) - Present
- ✅ en_enhanced.txt (76K) - Present
- ⚠️ de.txt, es.txt, fr.txt (615-645 bytes) - Suspiciously small

### XML Resources
- ✅ Layouts exist in res/xml/
- ✅ Drawables present
- ✅ Activity layouts present

## 🎯 RECOMMENDED FIX PRIORITY

1. **Fix Critical Issues #1-4** - App may crash or be unusable
2. **Enable Hardware Acceleration #5** - Major performance impact
3. **Implement Layout Switching #3** - Core keyboard feature
4. **Fix Key Event Handlers #6** - Basic keyboard functionality
5. **Address Medium Priority** - Improve stability
6. **Low Priority** - Nice to have improvements

## 📋 COMPILATION STATUS
✅ **Builds successfully** - No compilation errors
⚠️ **Gradle deprecation warning** - Non-blocking
✅ **APK generates** - 48MB debug APK
