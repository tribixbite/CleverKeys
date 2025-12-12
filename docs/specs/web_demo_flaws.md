# CleverKeys Web Demo - Flaw Analysis & TODO List

**Created**: 2025-12-12
**Status**: Deep Research Complete
**Live URL**: https://tribixbite.github.io/CleverKeys/

---

## Summary

The CleverKeys web demo (`/web_demo/`) provides a neural swipe prediction demonstration using ONNX Runtime Web. **However, the demo is fundamentally broken** - it uses the same ONNX models as the Android app but with completely wrong feature preprocessing, resulting in garbage predictions.

---

## ARCHITECTURE MISMATCH (P0-CRITICAL) - Model Input Broken

### Web Demo vs Android App Comparison

| Feature | Web Demo | Android App | Impact |
|---------|----------|-------------|--------|
| **Sequence Length** | 150 | **250** | Model expects 250 |
| **Velocity Calc** | `x[i] - x[i-1]` | `(x[i] - x[i-1]) / dt` | Wrong scale |
| **Acceleration** | `v[i] - v[i-1]` | `(v[i] - v[i-1]) / dt` | Wrong scale |
| **Value Clipping** | None | `[-10, 10]` | Out of range values |
| **Timestamps** | Not collected | Yes | Can't compute dt |
| **Resampling** | None | SwipeResampler | Variable input length |

### Root Cause

The web demo (line 1220-1239) calculates velocity as simple position difference:
```javascript
// WRONG - web demo
trajectoryData[baseIdx + 2] = point.x - prevPoint.x; // vx
```

The Android app (`TrajectoryFeatureCalculator.kt` line 87-89) divides by time delta:
```kotlin
// CORRECT - Android app
vx[i] = (xs[i] - xs[i - 1]) / dt[i]
```

The models were trained with time-normalized features. The web demo feeds position-only differences, producing **garbage predictions**.

### Fix Required

1. Collect timestamps during swipe tracking
2. Implement proper velocity: `vx = dx / dt`
3. Implement proper acceleration: `ax = dvx / dt`
4. Add clipping to [-10, 10]
5. Change MAX_SEQUENCE_LENGTH from 150 to 250
6. Add resampling to ensure consistent input length

---

## UI BUGS (P0) - Must Fix

### 1. Empty Duplicate File
**File**: `web_demo/niche-word-loader.js` (0 bytes)
**Issue**: This appears to be a typo/duplicate of `niche-words-loader.js` (7.5KB). The empty file should be deleted.

**Fix**:
```bash
rm web_demo/niche-word-loader.js
```

---

### 2. Shift Key Doesn't Produce Uppercase Letters
**Location**: `swipe-onnx.html` lines 1028-1046, 989, 2042

**Issue**: The shift key only toggles a visual CSS class (`key-active`) and auto-disables after 3 seconds. However, the character input functions always force lowercase:

```javascript
// Line 989 - handleSingleCharacterInput()
inputText += keyValue.toLowerCase();

// Line 2042 - handleKeyTap()
currentTypedWord += keyValue.toLowerCase();
```

**Expected Behavior**: When shift is active, typed characters should be uppercase.

**Fix Required**: Check shift state before adding character:
```javascript
const isShiftActive = document.querySelector('.key[data-key="shift"]')?.classList.contains('key-active');
const char = isShiftActive ? keyValue.toUpperCase() : keyValue.toLowerCase();
inputText += char;
// Also auto-disable shift after typing one character
if (isShiftActive) {
    document.querySelector('.key[data-key="shift"]')?.classList.remove('key-active');
}
```

---

### 3. Number Mode Layout Switching is Broken
**Location**: `swipe-onnx.html` line 2817-2829

**Issue**: `updateKeyboardToNumbers()` uses incorrect CSS selector:
```javascript
const keys = document.querySelectorAll(`[data-row="${rowIndex}"] .key`);
```

This selector looks for `.key` elements **inside** elements with `data-row`, but `data-row` is ON the key buttons themselves, not parent containers. The selector returns empty NodeLists.

**HTML Structure** (lines 313-322):
```html
<button class="key" data-key="q" data-row="0" data-col="0">Q</button>
<!-- data-row is ON the button, not a parent container -->
```

**Fix Required**: Use correct selector pattern:
```javascript
// Get the parent row div by finding first key in that row
const firstKeyInRow = document.querySelector(`[data-row="${rowIndex}"]`);
const rowDiv = firstKeyInRow?.parentElement;
const keys = rowDiv?.querySelectorAll('.key');
```

---

## HIGH PRIORITY (P1) - Should Fix

### 4. Emoji Mode Layout Switching - Fragile Implementation
**Location**: `swipe-onnx.html` line 2867

**Issue**: `showEmojiKeyboard()` uses a different (also fragile) approach:
```javascript
const rowDiv = document.querySelector(`[data-row="${rowIndex}"]`)?.parentElement;
```

This works by accident - it finds the first key with that `data-row` and gets its parent. However, this is:
- Inconsistent with `updateKeyboardToNumbers()`
- Depends on DOM structure staying exactly the same
- Brittle if keyboard layout changes

**Recommendation**: Refactor both functions to use consistent, robust selectors.

---

### 5. No Accessibility Support
**Issue**: Zero accessibility attributes found in the entire demo:
- No `aria-*` attributes
- No `role` attributes
- No `tabindex` for keyboard navigation
- No focus management
- No screen reader support

**Impact**: The demo is completely inaccessible to:
- Screen reader users
- Keyboard-only users
- Users with motor impairments

**Fix Required**: Add semantic attributes:
```html
<button
    class="key"
    data-key="q"
    role="button"
    aria-label="Letter Q"
    tabindex="0">Q</button>
```

---

## MEDIUM PRIORITY (P2) - Nice to Have

### 6. Large Initial Load (~12.5MB)
**Issue**: ONNX models load synchronously on page load:
- `swipe_model_character_quant.onnx`: 5.2MB
- `swipe_decoder_character_quant.onnx`: 7.2MB
- `swipe_vocabulary.json`: 5.2MB

**Impact**: Long initial wait time, especially on mobile networks.

**Recommendation**:
- Show meaningful progress indicators
- Consider lazy loading or on-demand model loading
- Add model caching with IndexedDB

---

### 7. No Offline Support
**Issue**: No Service Worker or PWA functionality.

**Impact**: Demo requires internet connection every time; models re-download on each visit.

**Recommendation**: Implement PWA with service worker for:
- Offline functionality
- Model caching
- Faster subsequent loads

---

### 8. No Persistent Settings
**Issue**: User preferences (theme, custom dictionary) aren't saved.

**Impact**: Users must reconfigure on each visit.

**Recommendation**: Save settings to localStorage.

---

## LOW PRIORITY (P3) - Tech Debt

### 9. Debug Code in Production
**Location**: `swipe-onnx.html` lines 2742-2751

**Issue**: Test functions exposed globally:
```javascript
window.testCustomDictionary = testCustomDictionary;
window.testTapFunctionality = testTapFunctionality;
window.testAutoPrediction = testAutoPrediction;
// ... 6 more test functions
```

**Recommendation**: Wrap in `DEBUG` flag or remove for production.

---

### 10. Excessive Console Logging
**Issue**: Heavy emoji-prefixed logging throughout:
```javascript
console.log('📝 Added character: "${keyValue}"');
console.log('🔢 Number mode enabled');
console.log('😊 Emoji mode enabled');
```

**Recommendation**: Gate behind `DEBUG.enabled` flag that already exists but isn't used consistently.

---

## File Inventory

| File | Size | Purpose | Status |
|------|------|---------|--------|
| `swipe-onnx.html` | 138KB | Main demo | Has bugs |
| `custom-dictionary.js` | 12KB | Personal dictionaries | Working |
| `niche-words-loader.js` | 7.5KB | Slang/tech terms | Working |
| `niche-word-loader.js` | 0 bytes | **DELETE** | Empty duplicate |
| `swipe-vocabulary.js` | 10KB | Vocabulary class | Working |
| `swipe_vocabulary.json` | 5.2MB | Word list | Working |
| `swipe_model_character_quant.onnx` | 5.2MB | Encoder model | Working |
| `swipe_decoder_character_quant.onnx` | 7.2MB | Decoder model | Working |
| `tokenizer_config.json` | 1KB | Tokenizer config | Working |
| `model_config.json` | 1KB | Model config | Working |

---

## Deployment

GitHub Pages workflow (`.github/workflows/deploy-web-demo.yml`) is correctly configured:
- Triggers on push to main (paths: web_demo/**, README.md)
- Uses Git LFS for large model files
- Renames `swipe-onnx.html` to `index.html`

---

## Recommended Fix Order

1. **Delete empty file** - 1 minute
2. **Fix shift key uppercase** - 15 minutes
3. **Fix number mode selector** - 15 minutes
4. **Fix emoji mode selector** - 10 minutes
5. **Add basic accessibility** - 1 hour
6. **Remove debug code** - 15 minutes

**Total estimated effort**: ~2.5 hours for P0+P1 fixes

---

## Testing Checklist

After fixes, verify:
- [ ] Shift key produces uppercase on next character
- [ ] Shift auto-disables after typing one character
- [ ] Number mode shows numbers (1234567890) on first row
- [ ] Emoji mode shows emojis
- [ ] ABC button returns to letters from number/emoji mode
- [ ] Backspace works in all modes
- [ ] Space/Return work in all modes
- [ ] Swipe prediction still works after mode switches
