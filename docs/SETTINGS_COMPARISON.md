# Settings Feature Parity Analysis
**Date**: 2025-11-18
**Source**: Unexpected Keyboard (Java) vs CleverKeys (Kotlin)
**Status**: Analysis from 10 screenshot comparisons + code review

---

## 📊 **Executive Summary**

**Java Repo Settings**: 45+ distinct settings across 8 categories
**CleverKeys Settings**: 42 settings in UI + 8 hidden in Config.kt
**Missing in UI**: 18 settings (40% gap)
**Partially Implemented**: 8 settings exist in Config.kt but not exposed in UI

---

## 🟢 **COMPLETE - Settings Fully Implemented in CleverKeys**

### **Neural Prediction** (5/5 ✅)
| Setting | Java Repo | CleverKeys UI | Notes |
|---------|-----------|---------------|-------|
| Enable Neural Prediction | ✅ | ✅ SettingsActivity:336-344 | Toggle switch |
| Beam Width | ✅ | ✅ SettingsActivity:347-358 | Slider 1-32, default 8 |
| Max Length | ✅ | ✅ SettingsActivity:360-371 | Slider 10-50, default 35 |
| Confidence Threshold | ✅ | ✅ SettingsActivity:373-384 | Slider 0.0-1.0, default 0.1 |
| Advanced Neural Settings | ✅ | ✅ SettingsActivity:386-392 | Button to NeuralSettingsActivity |

### **Appearance / Style** (15/15 ✅)
| Setting | Java Repo | CleverKeys UI | Notes |
|---------|-----------|---------------|-------|
| Theme | ✅ | ✅ SettingsActivity:397-412 | System/Light/Dark/Black |
| Keyboard Height (Portrait) | ✅ | ✅ SettingsActivity:414-425 | 20-60%, default 35% |
| Keyboard Height (Landscape) | ✅ | ✅ SettingsActivity:427-438 | 20-60%, default 50% |
| Bottom Margin (Portrait) | ✅ | ✅ SettingsActivity:440-451 | 0-30dp, default 7dp |
| Bottom Margin (Landscape) | ✅ | ✅ SettingsActivity:453-464 | 0-30dp, default 3dp |
| Horizontal Margin (Portrait) | ✅ | ✅ SettingsActivity:466-477 | 0-50dp, default 3dp |
| Horizontal Margin (Landscape) | ✅ | ✅ SettingsActivity:479-490 | 0-50dp, default 28dp |
| Label Brightness | ✅ | ✅ SettingsActivity:492-503 | 0-100%, default 100% |
| Keyboard Opacity | ✅ | ✅ SettingsActivity:505-516 | 0-100%, default 100% |
| Key Opacity | ✅ | ✅ SettingsActivity:518-529 | 0-100%, default 100% |
| Activated Key Opacity | ✅ | ✅ SettingsActivity:531-542 | 0-100%, default 100% |
| Character Size | ✅ | ✅ SettingsActivity:544-555 | 50-200%, default 115% |
| Key Vertical Margin | ✅ | ✅ SettingsActivity:557-568 | 0-500%, default 1.5% |
| Key Horizontal Margin | ✅ | ✅ SettingsActivity:570-581 | 0-500%, default 2.0% |
| Custom Border Config | ✅ | ✅ SettingsActivity:583-619 | Toggle + radius + width sliders |

### **Input Behavior** (12/12 ✅)
| Setting | Java Repo | CleverKeys UI | Notes |
|---------|-----------|---------------|-------|
| Auto Capitalization | ✅ | ✅ SettingsActivity:640-648 | Toggle, default true |
| Clipboard History Enabled | ✅ | ✅ SettingsActivity:650-658 | Toggle, default true |
| Vibration Enabled | ✅ | ✅ SettingsActivity:660-668 | Toggle, default false |
| Swipe Distance Threshold | ✅ | ✅ SettingsActivity:670-681 | 5-30 units, default 15 |
| Circle Gesture Sensitivity | ✅ | ✅ SettingsActivity:683-694 | 1-5, default 2 |
| Long Press Timeout | ✅ | ✅ SettingsActivity:696-707 | 200-1000ms, default 600ms |
| Long Press Interval | ✅ | ✅ SettingsActivity:709-720 | 25-200ms, default 65ms |
| Key Repeat Enabled | ✅ | ✅ SettingsActivity:722-730 | Toggle, default true |
| Double Tap Shift for Caps Lock | ✅ | ✅ SettingsActivity:732-740 | Toggle, default false |
| Immediate Keyboard Switching | ✅ | ✅ SettingsActivity:742-750 | Toggle, default false |
| Number Row | ✅ | ✅ SettingsActivity:752-771 | Hidden/Numbers/Numbers+Symbols |
| Show Numpad | ✅ | ✅ SettingsActivity:773-792 | Never/Landscape/Always |
| Numpad Layout | ✅ | ✅ SettingsActivity:794-803 | High First/Low First |
| Pin Entry Layout | ✅ | ✅ SettingsActivity:805-813 | Toggle, default false |

### **Accessibility** (3/3 ✅)
| Setting | Java Repo | CleverKeys UI | Notes |
|---------|-----------|---------------|-------|
| Sticky Keys Enabled | ✅ | ✅ SettingsActivity:818-826 | Toggle, default false |
| Sticky Keys Timeout | ✅ | ✅ SettingsActivity:828-841 | 1-10 seconds, default 5s |
| Voice Guidance Enabled | ✅ | ✅ SettingsActivity:843-858 | Toggle, default false |

### **Advanced** (2/2 ✅)
| Setting | Java Repo | CleverKeys UI | Notes |
|---------|-----------|---------------|-------|
| Debug Mode | ✅ | ✅ SettingsActivity:887-895 | Toggle, default false |
| Calibration | ✅ | ✅ SettingsActivity:897-902 | Button to SwipeCalibrationActivity |

### **System Actions** (3/3 ✅)
| Setting | Java Repo | CleverKeys UI | Notes |
|---------|-----------|---------------|-------|
| Version Info | ✅ | ✅ SettingsActivity:1102-1132 | Build/Commit/Date display |
| Reset All Settings | ✅ | ✅ SettingsActivity:1301-1341 | Button with confirmation dialog |
| Check for Updates | ✅ | ✅ SettingsActivity:1343-1380 | Button, searches for APK files |

---

## 🟡 **PARTIAL - Settings Exist in Config.kt But NOT in UI**

These settings are implemented in the backend (Config.kt) but have no user-facing controls:

| Setting | Config.kt Location | Java Repo UI | Priority |
|---------|-------------------|--------------|----------|
| Word Prediction Enabled | Config.kt:181 | ✅ Toggle in Typing section | **P1** |
| Suggestion Bar Opacity | Config.kt:182 | ✅ Slider (0-100%) | **P1** |
| Termux Mode | Config.kt:189 | ✅ Neural Settings screen | **P1** |
| Swipe Debug Scores | Config.kt:180 | ✅ "Swipe Debug Log" toggle | **P2** |
| Vibration Duration | Config.kt:142 | ✅ Slider when vibration enabled | **P2** |
| Auto-Correct Enabled | Config.kt:200 | ✅ Toggle in Typing section | **P1** |
| Auto-Correct Min Word Length | Config.kt:201 | ✅ Slider in Auto-Correction screen | **P2** |
| Auto-Correct Char Match Threshold | Config.kt:202 | ✅ Slider in Auto-Correction screen | **P2** |
| Auto-Correct Min Frequency | Config.kt:203 | ✅ Slider in Auto-Correction screen | **P2** |

**Impact**: 9 settings are functional but invisible to users. Users cannot configure these features without code changes.

---

## 🔴 **MISSING - Settings Not Implemented at All**

### **High Priority (P1) - Core Functionality** (9 settings)

#### **1. Typing Section Settings**
- **Enable Word Predictions** (exists in Config, needs UI)
- **Show Word Suggestions While Typing** (new setting)
- **Suggestion Bar Opacity** (exists in Config, needs UI)
- **Advanced Word Prediction Settings** (new screen needed)
  - Fine-tune scoring weights
  - Frequency scale
  - Context boost multiplier

#### **2. Auto-Correction UI** (exists in Config, needs full UI)
- **Enable Auto-Correction** (exists in Config, needs toggle)
- **Auto-Correction Settings Screen** (new screen)
  - About/explanation text
  - Min word length slider (exists in Config)
  - Char match threshold slider (exists in Config)
  - Min frequency slider (exists in Config)

#### **3. Neural Settings - Termux Mode**
- **Termux Mode Toggle** (exists in Config, needs UI in Neural Settings)
  - Insert predictions in Termux-compatible way for terminal usage

#### **4. Dictionary Manager** (DictionaryManagerActivity exists, needs verification)
- **4-Tab UI**: Active / Disabled / User Dict / Custom
- **Word Management**:
  - Search words functionality
  - Filter by frequency/status
  - Add new words with frequency
  - Edit existing words
  - Delete words
  - Frequency display for each word
- **Statistics Display**:
  - Active word count
  - Disabled word count
  - Custom word count
  - User dictionary count

### **Medium Priority (P2) - Enhanced Features** (6 settings)

#### **5. Swipe Settings Enhancement**
- **Swipe Corrections Settings** (adjust accuracy parameters)
  - Legacy swipe weights already in Config
  - Need UI sliders for:
    - Shape weight
    - Location weight
    - Frequency weight
    - Velocity weight
    - First/last letter weights
    - Endpoint bonus weight
- **Swipe Debug Log** (exists in Config, needs toggle in Swipe section)

#### **6. Gesture Settings**
- **Enable Short Gestures** (new setting)
- **Short Gesture Sensitivity** (percentage slider)
- **Space Bar Slider Sensitivity** (High/Medium/Low dropdown)

#### **7. Vibration Enhancement**
- **Vibration Intensity Slider** (exists in Config, needs UI when vibration enabled)
  - Duration in milliseconds (current: 20ms default)

#### **8. Clipboard History Enhancement** (7 sub-settings)
- **History Limit Type** (Count vs Size selector)
- **History Limit Slider** (Unlimited option + numeric)
- **Maximum Total Size** (megabytes)
- **Clipboard Pane Height** (percentage slider)
- **Pinned Section Size** (number of pinned items)
- **Maximum Size Per Item** (kilobytes)
- **Usage Statistics Display** (X active, Y pinned)

### **Low Priority (P3) - Advanced Features** (3 settings)

#### **9. Backup & Restore System** (6 sub-features)
- **Export Configuration** (save all settings to JSON)
- **Import Configuration** (restore from JSON)
- **Export Custom Dictionary** (save words to JSON)
- **Import Custom Dictionary** (import/merge words)
- **Export Clipboard History** (save entries to JSON)
- **Import Clipboard History** (import/merge entries)

#### **10. Neural Settings - Advanced Screens**
- **Beam Search Configuration Screen** (detailed parameters)
- **Confidence Filtering Screen** (threshold tuning)
- **Model Configuration Screen**:
  - Select model version
  - Trajectory processing options

---

## 📋 **Implementation TODO List**

### **Phase 1: Expose Existing Settings (Quick Wins)** ⚡
*Settings that already exist in Config.kt but need UI controls*

- [ ] **T1.1**: Add Word Prediction toggle to Typing section
- [ ] **T1.2**: Add Suggestion Bar Opacity slider to Typing section
- [ ] **T1.3**: Add Auto-Correct toggle to Typing section
- [ ] **T1.4**: Add Termux Mode toggle to Neural Settings screen
- [ ] **T1.5**: Add Vibration Duration slider (conditional on vibration enabled)
- [ ] **T1.6**: Add Swipe Debug Log toggle to Swipe section

**Estimated Effort**: 2-3 hours (simple UI additions to existing activities)

---

### **Phase 2: Auto-Correction Settings Screen** 🎯
*Create dedicated screen for auto-correction configuration*

- [ ] **T2.1**: Create `AutoCorrectionSettingsActivity.kt`
  - Compose UI with Material 3 design
  - Enable/disable toggle at top
  - Explanation text about auto-correction
- [ ] **T2.2**: Add Min Word Length slider (3-10 chars, default 3)
- [ ] **T2.3**: Add Char Match Threshold slider (0.5-1.0, default 0.67)
- [ ] **T2.4**: Add Min Frequency slider (100-2000, default 500)
- [ ] **T2.5**: Add "Auto-Correction Settings" button to main settings
- [ ] **T2.6**: Wire up all sliders to Config.kt preferences
- [ ] **T2.7**: Add live preview/testing section (optional)

**Estimated Effort**: 4-6 hours

---

### **Phase 3: Advanced Swipe Settings** 🔧
*Expose legacy swipe weights for power users*

- [ ] **T3.1**: Create `SwipeSettingsActivity.kt`
  - Compose UI with expert warning banner
  - Reset to defaults button
- [ ] **T3.2**: Add Shape Weight slider (0-200%, default 90%)
- [ ] **T3.3**: Add Location Weight slider (0-200%, default 130%)
- [ ] **T3.4**: Add Frequency Weight slider (0-200%, default 80%)
- [ ] **T3.5**: Add Velocity Weight slider (0-200%, default 60%)
- [ ] **T3.6**: Add First Letter Weight slider (0-300%, default 150%)
- [ ] **T3.7**: Add Last Letter Weight slider (0-300%, default 150%)
- [ ] **T3.8**: Add Endpoint Bonus slider (0-300%, default 200%)
- [ ] **T3.9**: Add Require Endpoints toggle
- [ ] **T3.10**: Add Debug Scores toggle
- [ ] **T3.11**: Link from Calibration activity

**Estimated Effort**: 6-8 hours

---

### **Phase 4: Enhanced Clipboard History** 📋
*Add advanced clipboard management features*

- [ ] **T4.1**: Create `ClipboardSettingsActivity.kt`
- [ ] **T4.2**: Add History Limit Type selector (Count / Size)
- [ ] **T4.3**: Add History Limit slider (1-100 or Unlimited)
- [ ] **T4.4**: Add Max Total Size slider (1-100 MB)
- [ ] **T4.5**: Add Pane Height slider (20-50%, default 30%)
- [ ] **T4.6**: Add Pinned Section Size slider (0-20 items)
- [ ] **T4.7**: Add Max Item Size slider (1-10 MB)
- [ ] **T4.8**: Add Usage Statistics display
  - Active entry count
  - Pinned entry count
  - Total size used
- [ ] **T4.9**: Update Config.kt with new clipboard properties
- [ ] **T4.10**: Update ClipboardHistoryManager to respect new limits

**Estimated Effort**: 8-10 hours (requires backend changes)

---

### **Phase 5: Gesture Settings** 👆
*Add missing gesture configuration options*

- [ ] **T5.1**: Create `GestureSettingsActivity.kt` or add section to main settings
- [ ] **T5.2**: Add Enable Short Gestures toggle
- [ ] **T5.3**: Add Short Gesture Sensitivity slider (0-100%)
- [ ] **T5.4**: Add Space Bar Slider Sensitivity dropdown (Low/Medium/High)
- [ ] **T5.5**: Update gesture recognizers to respect new settings
- [ ] **T5.6**: Add Config.kt properties for gesture settings

**Estimated Effort**: 6-8 hours (includes gesture logic updates)

---

### **Phase 6: Dictionary Manager Enhancement** 📚
*Complete the DictionaryManagerActivity implementation*

**Prerequisites**: Verify current state of DictionaryManagerActivity

- [ ] **T6.1**: Audit existing DictionaryManagerActivity
  - Document what's implemented
  - Identify missing features
- [ ] **T6.2**: Implement 4-tab layout
  - Active tab (words currently in use)
  - Disabled tab (temporarily disabled words)
  - User Dict tab (user-added words)
  - Custom tab (imported/custom words)
- [ ] **T6.3**: Add search functionality
  - Real-time filtering
  - Search by prefix/contains/exact
- [ ] **T6.4**: Add filter options
  - Filter by frequency range
  - Filter by usage count
  - Sort by frequency/alphabetical
- [ ] **T6.5**: Add word management UI
  - Add new word dialog with frequency input
  - Edit word dialog (word + frequency)
  - Delete word confirmation
  - Bulk actions (enable/disable/delete multiple)
- [ ] **T6.6**: Add frequency display
  - Show frequency number for each word
  - Visual indicator (high/medium/low)
- [ ] **T6.7**: Add statistics display
  - Tab counters (Active: X, Disabled: Y, etc.)
  - Total words, average frequency, etc.

**Estimated Effort**: 12-16 hours (complex UI + database operations)

---

### **Phase 7: Backup & Restore System** 💾
*Add import/export functionality for settings and data*

- [ ] **T7.1**: Create `BackupRestoreActivity.kt` or add to main settings
- [ ] **T7.2**: Implement Configuration Export
  - Serialize all SharedPreferences to JSON
  - Include timestamp and version info
  - Save to `/sdcard/CleverKeys/config-backup.json`
- [ ] **T7.3**: Implement Configuration Import
  - Read JSON file
  - Validate structure and version
  - Merge/replace confirmation dialog
  - Apply settings with validation
- [ ] **T7.4**: Implement Dictionary Export
  - Query all custom/user words from database
  - Serialize to JSON with frequency
  - Save to `/sdcard/CleverKeys/dictionary-backup.json`
- [ ] **T7.5**: Implement Dictionary Import
  - Parse JSON dictionary file
  - Merge with existing words (conflict resolution)
  - Bulk insert to database
- [ ] **T7.6**: Implement Clipboard History Export
  - Query clipboard entries from database
  - Serialize to JSON with timestamps
  - Save to `/sdcard/CleverKeys/clipboard-backup.json`
- [ ] **T7.7**: Implement Clipboard History Import
  - Parse JSON clipboard file
  - Merge with existing entries
  - Respect history limits
- [ ] **T7.8**: Add backup directory management
  - Create CleverKeys directory if missing
  - List available backups
  - Delete old backups
- [ ] **T7.9**: Add error handling and validation
  - File access permissions
  - JSON parsing errors
  - Version compatibility checks
  - Rollback on failure

**Estimated Effort**: 16-20 hours (file I/O, serialization, error handling)

---

### **Phase 8: Advanced Neural Settings** 🧠
*Add detailed neural model configuration screens*

- [ ] **T8.1**: Create `BeamSearchConfigActivity.kt`
  - Beam width (already in main settings)
  - Beam pruning threshold
  - Length normalization alpha
  - Coverage penalty beta
- [ ] **T8.2**: Create `ConfidenceFilteringActivity.kt`
  - Min confidence threshold (already in main settings)
  - Max confidence threshold
  - Confidence decay rate
  - Fallback behavior
- [ ] **T8.3**: Create `ModelConfigActivity.kt`
  - Model version selector
  - Trajectory processing options
  - Quantization settings
  - Batch size (for multi-touch)
- [ ] **T8.4**: Add links from NeuralSettingsActivity
- [ ] **T8.5**: Update neural prediction engine to use new parameters

**Estimated Effort**: 12-16 hours (requires neural engine modifications)

---

### **Phase 9: Layout Manager UI** 🗂️
*Expose layout and extra keys configuration in main settings*

**Current State**: Buttons exist in SettingsActivity (lines 625-638) but might need enhancement

- [ ] **T9.1**: Verify LayoutManagerActivity completeness
- [ ] **T9.2**: Verify ExtraKeysConfigActivity completeness
- [ ] **T9.3**: Add descriptive text in main settings
- [ ] **T9.4**: Consider adding quick layout switcher to main settings
- [ ] **T9.5**: Add "Add alternate layout" button to main settings

**Estimated Effort**: 2-4 hours (verification + minor UI enhancements)

---

## 📊 **Implementation Summary**

| Phase | Tasks | Effort (hours) | Priority | Dependencies |
|-------|-------|----------------|----------|--------------|
| **Phase 1** | 6 | 2-3 | **P1** | None |
| **Phase 2** | 7 | 4-6 | **P1** | None |
| **Phase 3** | 11 | 6-8 | **P2** | None |
| **Phase 4** | 10 | 8-10 | **P2** | Phase 1 |
| **Phase 5** | 6 | 6-8 | **P2** | None |
| **Phase 6** | 7 | 12-16 | **P1** | None |
| **Phase 7** | 9 | 16-20 | **P3** | Phase 2, 4, 6 |
| **Phase 8** | 5 | 12-16 | **P3** | None |
| **Phase 9** | 5 | 2-4 | **P1** | None |
| **TOTAL** | **66** | **68-91** | - | - |

**Recommended Implementation Order**:
1. **Phase 1** (Quick wins - expose existing settings)
2. **Phase 9** (Layout manager UI enhancement)
3. **Phase 2** (Auto-correction screen)
4. **Phase 6** (Dictionary manager - critical for parity)
5. **Phase 5** (Gesture settings)
6. **Phase 3** (Advanced swipe settings)
7. **Phase 4** (Enhanced clipboard)
8. **Phase 7** (Backup/restore)
9. **Phase 8** (Advanced neural settings)

**Total Implementation Time**: 68-91 hours (1.7-2.3 weeks of full-time development)

---

## 🎯 **Priority Assessment**

### **Critical for Feature Parity (P1)**
- Phase 1: Expose existing settings (2-3 hours) ⚡
- Phase 2: Auto-correction UI (4-6 hours)
- Phase 6: Dictionary manager (12-16 hours)
- Phase 9: Layout manager enhancement (2-4 hours)

**Total P1**: 20-29 hours (~3-4 days)

### **Important for User Experience (P2)**
- Phase 3: Advanced swipe settings (6-8 hours)
- Phase 4: Enhanced clipboard (8-10 hours)
- Phase 5: Gesture settings (6-8 hours)

**Total P2**: 20-26 hours (~3 days)

### **Nice to Have (P3)**
- Phase 7: Backup/restore (16-20 hours)
- Phase 8: Advanced neural settings (12-16 hours)

**Total P3**: 28-36 hours (~4-5 days)

---

## 📝 **Notes**

1. **DictionaryManagerActivity Status**: Needs verification - button exists in settings but unclear if fully implemented
2. **NeuralSettingsActivity Status**: Button exists, but need to verify what's actually on that screen
3. **SwipeCalibrationActivity Status**: Button exists, need to verify completeness
4. **ExtraKeysConfigActivity Status**: Button exists, need to verify UI completeness
5. **LayoutManagerActivity Status**: Button exists, need to verify UI completeness

6. **Code Locations**:
   - Main settings: `src/main/kotlin/tribixbite/keyboard2/SettingsActivity.kt`
   - Config: `src/main/kotlin/tribixbite/keyboard2/Config.kt`
   - Preference keys: Config.kt lines 60-354

7. **Testing Requirements**:
   - Each new screen needs comprehensive testing
   - Settings persistence across app restarts
   - Settings migration for existing users
   - Edge cases (invalid values, file I/O errors, etc.)

---

## ✅ **Next Steps**

1. **Verify Existing Activities**:
   - Read NeuralSettingsActivity.kt
   - Read DictionaryManagerActivity.kt
   - Read SwipeCalibrationActivity.kt
   - Read ExtraKeysConfigActivity.kt
   - Read LayoutManagerActivity.kt

2. **Start Phase 1** (Quick wins to boost feature parity immediately)

3. **Prioritize Dictionary Manager** (Phase 6) - highest user value

4. **Create Specs** for each major screen:
   - `docs/specs/auto-correction-screen.md`
   - `docs/specs/dictionary-manager.md`
   - `docs/specs/backup-restore.md`
   - `docs/specs/swipe-settings.md`

5. **Update Project Status** in `migrate/project_status.md`

---

**Last Updated**: 2025-11-18 04:45
**Analysis By**: Claude (Sonnet 4.5)
**Session**: Part 6.18 - Settings Comparison & TODO Generation
