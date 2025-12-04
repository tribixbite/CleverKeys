# Questions for Review

This file contains items flagged for user review during the spec audit and code review process.

## Last Updated
2025-12-04

---

## 1. Neural Prediction Spec Discrepancies - ✅ RESOLVED

**File**: `docs/specs/neural-prediction.md`

**Resolution** (2025-12-04):
1. ✅ FIXED - Consolidated bug status into clean table format at top of TODOs section
2. ✅ FIXED - Added Last Updated field showing 2025-12-04
3. ✅ FIXED - Bug #273 marked as FIXED (SQLite persistence implemented)
4. ⚠️ NEEDS VERIFICATION - Model file naming conventions (user to verify actual asset filenames)

**All status inconsistencies resolved**. Spec now shows:
- P0 bugs: All resolved (Bugs #257, #259, #262, #263, #273-#276)
- P1-P2 remaining: Bugs #270, #271, #277

---

## 2. UK Settings Parity - ✅ RESOLVED

**Context**: Need to compare CleverKeys settings with Unexpected-Keyboard saved user settings (not defaults)

**Resolution**: Full analysis completed in `docs/history/uk-config-todos.md`
- **224 settings** analyzed across both codebases
- **591 verified** as working identically in UK and CK
- **33 UI-only placeholders** (Privacy, Rollback settings not implemented in runtime)
- **24 CK-specific** swipe scoring weights exported but need wiring

**Answers**:
1. UK SharedPreferences: `/data/data/juloo.keyboard2/shared_prefs/juloo.keyboard2_preferences.xml`
2. All settings in Config.kt are persisted; dynamically set vars are runtime-only
3. CK has additional themes (CleverKeysDark, CleverKeysLight, Jewel, etc.) and swipe trail settings

**Action Needed**: Wire up 8 unused CK swipe scoring weights (shape/velocity/location/endpoint)

---

## 3. Swipe Calibration Playground - ✅ RESOLVED

**File**: `SwipeCalibrationActivity.kt`

**Resolution** (2025-12-04):
1. ✅ YES - Playground writes to SharedPreferences at lines 622-628:
   - `neural_beam_width` (int)
   - `neural_max_length` (int)
   - `neural_confidence_threshold` (float)
2. ✅ YES - Settings are persisted via `editor.apply()` and `neuralEngine.setConfig(config)` applies them immediately
3. ⚠️ NO - No "reset to defaults" option in playground. Could be added as enhancement.

**Parameters saved to runtime**:
- Beam width (1-16, default 8)
- Max length (10-50)
- Confidence threshold (0.0-1.0)

---

## 4. Dictionary Management - ✅ RESOLVED

**File**: `DictionaryManagerActivity.kt`

**Resolution** (2025-12-04):
1. ✅ **4 tabs** (not 3):
   - **Active**: Currently enabled dictionary words
   - **Disabled**: Words removed from active dictionary
   - **User Dict**: User-added custom words
   - **Custom**: Custom dictionary entries
2. ⚠️ Export/Import - Needs verification (check BackupRestoreActivity.kt)
3. ✅ Words persisted via `DictionaryManager.kt` and SQLite-backed storage

---

## 5. Theme System

**Questions**:
1. Current theme storage mechanism?
2. What parameters are themeable for swipe trails?
3. Is there keyboard background customization?

---

## Template for Adding Questions

```markdown
## N. [Topic]

**Context**: [Brief context]

**Questions**:
1. Question 1
2. Question 2

**Action Needed**: [What's needed to resolve]
```
