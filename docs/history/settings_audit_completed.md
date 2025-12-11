# Settings UI Audit (2025-12-10)

## Executive Summary

Comprehensive review of all settings in `SettingsActivity.kt` to identify:
- Duplicate settings appearing in multiple sections
- Settings in wrong categories
- UX restructuring recommendations

## Status: MOSTLY FIXED

**Fixed (commit 8a1315af):**
- ✅ Removed duplicate `clipboard_history_enabled` from Input section
- ✅ Deleted legacy XML fallback UI (~105 lines removed)

**Fixed (commit e35d2642):**
- ✅ Moved Terminal Mode from Neural to Advanced section
- ✅ Moved Short Gestures from Input to Gesture Tuning section
- ✅ Consolidated autocorrect settings into single "Auto-Correction" section with subsections

**Remaining (Low Priority):**
- 5+ confusing similar settings (swipe distances with different units)
- Could further merge sections (14 → 11) but current organization is functional

---

## 1. DUPLICATE SETTINGS

### 1.1 True Duplicates (Same preference key saved twice in UI)

| Setting | Locations | Status |
|---------|-----------|--------|
| **clipboard_history_enabled** | ~~Input section + Clipboard section~~ | ✅ FIXED - removed from Input section |

### 1.2 Legacy Fallback UI Duplicates

~~These existed in both Compose UI and Legacy XML fallback UI (useLegacySettingsUI):~~
- ~~`neural_prediction_enabled`~~
- ~~`neural_beam_width`~~

**Status**: ✅ FIXED - Legacy UI deleted entirely

---

## 2. CONFUSING SIMILAR/RELATED SETTINGS

### 2.1 Swipe Distance Settings (HIGH CONFUSION)

| Setting | Section | Preference Key | Current Value Range |
|---------|---------|----------------|---------------------|
| "Swipe Distance Threshold" | Input | `swipe_dist` | 5-30 (units) |
| "Minimum Swipe Distance" | Gesture Tuning | `swipe_min_distance` | 20-100 (pixels) |
| "Minimum Key Distance" | Gesture Tuning | `swipe_min_key_distance` | 15-80 (pixels) |
| "Short Gesture Min Distance" | Input | `short_gesture_min_distance` | 10-60 (% key diagonal) |
| "Short Gesture Max Distance" | Input | `short_gesture_max_distance` | 50-200 (% key diagonal) |

**Problem**: 5 different distance settings across 2 sections with different units!

**Recommendation**:
1. Consolidate all distance settings into Gesture Tuning section
2. Standardize units (either all pixels or all % of key size)
3. Add clear unit labels to each setting

### 2.2 Autocorrect Settings ~~(SPLIT ACROSS SECTIONS)~~ ✅ FIXED

~~**In Input Section (lines 1308-1358):**~~
~~**In Swipe Corrections Section (lines 1624-1782):**~~

**Status**: ✅ FIXED (commit e35d2642) - All autocorrect settings consolidated into single "Auto-Correction" section with:
- Master toggle at top
- Basic Settings subsection
- Swipe Correction subsection
- Advanced subsection
- Word Scoring subsection

### 2.3 Short Gesture Settings ~~(SPLIT ACROSS SECTIONS)~~ ✅ FIXED

**Status**: ✅ FIXED (commit e35d2642) - All short gesture settings moved to Gesture Tuning section

---

## 3. SETTINGS IN WRONG CATEGORIES

| Setting | Current Section | Better Section | Status |
|---------|-----------------|----------------|--------|
| Clipboard History toggle | ~~Input~~ | Clipboard | ✅ FIXED - Removed duplicate |
| Terminal Mode | ~~Neural~~ | Advanced | ✅ FIXED - Moved to Advanced |
| Short Gestures (all) | ~~Input~~ | Gesture Tuning | ✅ FIXED - Moved to Gesture Tuning |
| Vibration | Input | Appearance or Accessibility | Low priority |
| Smart Punctuation | Input | Auto-Correction | Low priority |
| Pin Entry Layout | Input | Appearance | Low priority |

---

## 4. SECTION REORGANIZATION PROPOSAL

### Current Sections (14 sections):
1. Neural Network Prediction
2. Appearance
3. Swipe Trail
4. Word Prediction/Input
5. Swipe Corrections
6. Gesture Tuning
7. Accessibility
8. Dictionary
9. Clipboard
10. Backup & Restore
11. Multi-Language
12. Privacy & Data
13. Advanced
14. Info

### Proposed Sections (11 sections):

1. **Swipe Typing** (merged Neural + Swipe settings)
   - Enable Swipe Typing (master)
   - Enable Neural Prediction
   - Beam Width, Max Length, Confidence
   - Advanced Neural Settings (collapsible)

2. **Word Predictions** (renamed from Input)
   - Enable Word Predictions
   - Suggestion Bar Opacity
   - Context-Aware, Personalized Learning
   - Advanced Settings (collapsible)

3. **Auto-Correction** (NEW - consolidated)
   - Enable Auto-Correction (master)
   - All correction settings from Input + Swipe Corrections

4. **Gestures** (merged Input gesture + Gesture Tuning)
   - Short Gestures (all settings)
   - Tap/Swipe thresholds
   - Slider behavior
   - Short Swipe Customization

5. **Appearance** (keep)
   - Theme Manager
   - Keyboard Height
   - Margins
   - Opacity/Brightness
   - Border Config

6. **Swipe Trail** (keep or merge into Appearance)

7. **Typing Behavior** (renamed from Input remainder)
   - Auto Capitalization
   - Smart Punctuation
   - Double Tap Shift
   - Number Row/Numpad

8. **Clipboard** (keep, remove duplicate toggle)

9. **Accessibility** (keep)

10. **Advanced** (keep)
    - Debug settings
    - Terminal Mode
    - Calibration

11. **Info & Backup** (merged)
    - Version Info
    - Backup/Restore
    - Dictionary Management

---

## 5. SPECIFIC FIXES COMPLETED

### ✅ Priority 1: Remove True Duplicate (commit 8a1315af)
Removed duplicate `clipboard_history_enabled` from Input section

### ✅ Priority 2: Move Terminal Mode (commit e35d2642)
Moved from Neural section to Advanced section

### ✅ Priority 3: Consolidate Autocorrect (commit e35d2642)
All autocorrect settings consolidated into single "Auto-Correction" section

### ✅ Priority 4: Move Short Gestures (commit e35d2642)
All short gesture settings moved to Gesture Tuning section

### Remaining (Low Priority): Distance Settings
5 distance-related settings with different units could be clarified:
- Consider adding a "Swipe Sensitivity" preset (Low/Medium/High) that sets multiple values
- Or add clearer unit labels to each setting

---

## 6. METRICS

| Metric | Before | After Fixes |
|--------|--------|-------------|
| Total sections | 14 | 13 (Auto-Correction renamed) |
| Duplicate settings | 1 | 0 ✅ |
| Settings in wrong category | 6 | 3 (low priority) |
| Confusing similar settings | 5+ | 5 (distance units - low priority) |
| Lines changed | - | -19 net (210 insertions, 229 deletions) |

---

## 7. IMPLEMENTATION PLAN

1. **Phase 1** ✅ COMPLETE (commit 8a1315af):
   - ✅ Removed duplicate `clipboard_history_enabled` from Input section
   - ✅ Deleted legacy XML fallback UI

2. **Phase 2** ✅ COMPLETE (commit e35d2642):
   - ✅ Moved Terminal Mode to Advanced section
   - ✅ Consolidated autocorrect settings into single section
   - ✅ Moved Short Gesture settings to Gesture Tuning

3. **Phase 2.5** ✅ COMPLETE (commit f85a2a33):
   - ✅ Created `Defaults` object as single source of truth for all ~100 defaults
   - ✅ Updated Config.kt to use `Defaults.X` instead of hardcoded values
   - ✅ Updated SettingsActivity.kt loadCurrentSettings() to use `Defaults.X`
   - ✅ Updated onSharedPreferenceChanged() listener to use `Defaults.X`
   - **Bug fixed**: Settings UI showing different values than keyboard used

4. **Phase 3** (Future - Low Priority):
   - Add sensitivity presets for distance settings
   - Standardize units across distance settings
   - Consider further section merges (14 → 11)
   - Move remaining misplaced settings (vibration, smart punctuation, pin entry)

---

**Last Updated**: 2025-12-10
