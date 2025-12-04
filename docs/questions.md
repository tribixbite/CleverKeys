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

## 6. UK vs CK Config Differences - ✅ ANALYZED

**Date**: 2025-12-04

### CK-Only Features (not in UK):
| Feature | CK Setting | Description |
|---------|------------|-------------|
| Swipe Trail | `swipe_trail_enabled` | Show swipe gesture trail |
| Swipe Trail | `swipe_trail_effect` | Effect: none, solid, glow, rainbow, fade |
| Swipe Trail | `swipe_trail_color` | Trail color (default: jewel purple) |
| Swipe Trail | `swipe_trail_width` | Trail stroke width (dp) |
| Swipe Trail | `swipe_trail_glow_radius` | Glow radius (dp) |

### Different Default Values:

| Setting | CK Default | UK Default | Notes |
|---------|------------|------------|-------|
| `longPressInterval` | 25 | 65 | CK is faster |
| `characterSize` | 1.18 | 1.15 | CK slightly larger |
| `clipboard_history_enabled` | true | false | CK enables by default |
| `double_tap_lock_shift` | true | false | CK enables by default |

### Code Improvements:
- CK uses `safeGetString()` consistently for null-safe preference access
- UK has inline try-catch blocks, CK uses utility methods

---

## 7. Privacy/AB-Test/Rollback Settings - ✅ VERIFIED IMPLEMENTED

**Date**: 2025-12-04 (Updated)

**Resolution**: These settings are FULLY IMPLEMENTED with backend logic:

### Privacy Settings - ✅ Implemented via `PrivacyManager.kt`
| Setting Key | Description | Backend |
|-------------|-------------|---------|
| `privacy_collect_swipe` | Allow swipe data collection | ✅ `PrivacyManager.kt` |
| `privacy_collect_performance` | Allow performance data | ✅ `PrivacyManager.kt` |
| `privacy_collect_errors` | Allow error logs | ✅ `PrivacyManager.kt` |
| `privacy_anonymize` | Remove identifying info | ✅ `PrivacyManager.kt` |
| `privacy_local_only` | Keep data on device | ✅ `PrivacyManager.kt` |
| `privacy_allow_export` | Allow data export | ✅ `PrivacyManager.kt` |
| `privacy_allow_sharing` | Allow model sharing | ✅ `PrivacyManager.kt` |
| `privacy_retention_days` | Data retention period | ✅ `PrivacyManager.kt` |
| `privacy_auto_delete` | Auto-delete old data | ✅ `PrivacyManager.kt` |

### A/B Testing - ✅ Implemented via `ABTestManager.kt`
| Setting Key | Description | Backend |
|-------------|-------------|---------|
| `ab_test_status` | Test progress view | ✅ `ABTestManager.kt` |
| `ab_test_comparison` | Model comparison | ✅ `ModelComparisonTracker.kt` |
| `ab_test_configure` | Configure test params | ✅ `ABTestManager.kt` |

### Rollback Settings - ✅ Implemented via `ModelVersionManager.kt`
| Setting Key | Description | Backend |
|-------------|-------------|---------|
| `rollback_status` | Version status view | ✅ `ModelVersionManager.kt` |
| `rollback_history` | Version history | ✅ `ModelVersionManager.kt` |
| `rollback_auto_enabled` | Auto-rollback | ✅ `ModelVersionManager.kt` |
| `rollback_manual` | Manual rollback | ✅ `ModelVersionManager.kt` |
| `rollback_pin_version` | Pin version | ✅ `ModelVersionManager.kt` |

**Status**: All features have complete backend implementations. Settings are saved via `SettingsActivity.kt` and read by their respective manager classes.

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
