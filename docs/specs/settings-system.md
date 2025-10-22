# Settings System Specification

## Feature Overview
**Feature Name**: Settings & Preferences System
**Priority**: P1 (High)
**Status**: Maintenance & Enhancement
**Target Version**: v1.0.0

### Summary
The settings system manages user preferences, storage permissions, configuration persistence, and settings UI. It provides a modern Material 3 interface for configuring keyboard behavior.

### Motivation
Users need a comprehensive, intuitive settings system to customize keyboard behavior. The system must handle Android 11+ storage changes, persist preferences reliably, and expose all configurable options.

---

## ⚠️ KNOWN ISSUES (From Historical Review)

### CRITICAL Issues (Fixed)

#### ✅ Issue #5: Termux Mode Setting Missing from UI
**File:** `res/xml/settings.xml`
**Status**: ⚠️ NEEDS VERIFICATION
**Original Problem**: `termux_mode_enabled` checkbox not present in neural settings screen
**Impact**: Users cannot enable Termux-compatible prediction insertion
**Current State**: Variable exists in Config.kt but may not be exposed in settings XML
**Action Required**:
- [ ] Check res/xml/settings.xml for `termux_mode_enabled` preference
- [ ] If missing, add checkbox after `neural_prediction_enabled`
- [ ] Verify Config.kt reads this preference correctly
- [ ] Test Termux mode functionality
- [ ] Document Termux mode behavior in user guide

### HIGH PRIORITY Issues (Fixed)

#### ✅ Issue #9: External Storage Permissions (Android 11+) - VERIFIED FIXED
**File:** `AndroidManifest.xml:11-15`
**Status**: ✅ VERIFIED (2025-10-21)
**Original Problem**: READ/WRITE_EXTERNAL_STORAGE deprecated on Android 11+ (API 30+)
**Impact**: Would cause file access failures on modern Android versions
**Verification Result**: **PROPERLY CONFIGURED** ✅
- AndroidManifest.xml:11 - `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion="29"` ✅
- AndroidManifest.xml:12 - `READ_EXTERNAL_STORAGE` with `maxSdkVersion="29"` ✅
- Lines 9-15 have proper comments explaining scoped storage for Android 11+
- Comment confirms: "For Android 11+, use scoped storage via MediaStore or SAF"

**Action Required** (Testing):
- [x] Review AndroidManifest.xml permissions ✅
- [x] Verify `maxSdkVersion="29"` is set for READ/WRITE_EXTERNAL_STORAGE ✅
- [x] Comments document scoped storage strategy ✅
- [ ] Test file access on Android 11+ devices (verify scoped storage works)
- [ ] Test file access on Android 10 and below (verify legacy permissions work)
- [ ] Verify app-specific directories (getExternalFilesDir()) work without permissions

### MEDIUM PRIORITY Issues (Fixed)

#### ✅ Issue #15: Theme Propagation Incomplete
**File:** `src/main/kotlin/tribixbite/keyboard2/CleverKeysService.kt:654`
**Status**: ⚠️ NEEDS VERIFICATION
**Original Problem**: `// TODO: Propagate theme changes to active UI components`
**Impact**: Theme changes may not apply until restart
**Action Required**:
- [ ] Review CleverKeysService.kt:654
- [ ] Implement theme update notification system
- [ ] Test theme switching without restart
- [ ] Verify all UI components update correctly
- [ ] Document theme propagation mechanism

#### ✅ Issue #17: Emoji Preferences Not Loaded
**File:** `src/main/kotlin/tribixbite/keyboard2/Emoji.kt:102`
**Status**: ⚠️ NEEDS VERIFICATION
**Original Problem**: `// TODO: Load from preferences`
**Impact**: Emoji preferences don't persist (recent/favorite emoji)
**Action Required**:
- [ ] Review Emoji.kt:102
- [ ] Implement SharedPreferences loading for emoji data
- [ ] Persist recent emoji list
- [ ] Persist favorite emoji list
- [ ] Test emoji persistence across app restarts
- [ ] Limit recent emoji list size (e.g., 30 items)

#### ✅ Issue #18: ConfigurationManager Theme Application
**File:** `src/main/kotlin/tribixbite/keyboard2/ConfigurationManager.kt:306`
**Status**: ⚠️ NEEDS VERIFICATION
**Original Problem**: `// TODO: Fix Theme.initialize(context).applyThemeToView(view, theme)`
**Impact**: Theme not fully applied to all UI components
**Action Required**:
- [ ] Review ConfigurationManager.kt:306
- [ ] Fix theme application to all views
- [ ] Verify Material 3 theming works correctly
- [ ] Test with KeyboardTheme integration
- [ ] Document theme application flow

### LOW PRIORITY Issues

#### Issue #23: Deprecated API Suppressions
**Files:**
- `ClipboardHistoryService.kt:165`
- `VibratorCompat.kt:20,38`
**Status**: FUTURE WORK
**Problem**: Using deprecated Android APIs with @Suppress annotations
**Action Required** (Future):
- [ ] Migrate ClipboardHistoryService to modern ClipboardManager API
- [ ] Migrate VibratorCompat to VibrationEffect (API 26+)
- [ ] Remove @Suppress annotations
- [ ] Test on various Android versions

#### Issue #24: Hardcoded Strings
**File:** `src/main/kotlin/tribixbite/keyboard2/CustomLayoutEditDialog.kt:46,54`
**Status**: FUTURE WORK
**Problem**: Hardcoded "Custom layout" and "Remove layout" strings
**Action Required** (Future):
- [ ] Create string resources in res/values/strings.xml
- [ ] Replace hardcoded strings with R.string references
- [ ] Prepare for i18n/localization

---

## Requirements

### Functional Requirements
1. **FR-1**: All keyboard settings must be accessible from settings UI
2. **FR-2**: Settings must persist correctly using SharedPreferences
3. **FR-3**: Storage access must work on Android 11+ (scoped storage)
4. **FR-4**: Theme changes must apply immediately without restart
5. **FR-5**: Emoji preferences (recent/favorites) must persist
6. **FR-6**: Termux mode must be configurable from UI
7. **FR-7**: Settings UI must use Material 3 design

### Non-Functional Requirements
1. **NFR-1**: Performance - Settings load in <100ms
2. **NFR-2**: Usability - Settings organized logically, searchable
3. **NFR-3**: Reliability - No data loss on crashes

---

## Technical Design

### Architecture
```
SettingsActivity (Material 3 Compose)
    ├── PreferenceScreen
    │   ├── Neural Prediction Settings
    │   ├── Layout Settings
    │   ├── Theme Settings
    │   └── Advanced Settings
    ├── Config (reads SharedPreferences)
    └── ConfigurationManager (applies settings)

Storage Strategy:
    ├── SharedPreferences (settings data)
    ├── App-specific storage (getExternalFilesDir)
    └── Scoped storage (Android 11+)
```

### Component Breakdown
1. **SettingsActivity**: Material 3 Compose UI for settings
2. **NeuralSettingsActivity**: Neural prediction-specific settings
3. **Config**: Global configuration singleton
4. **ConfigurationManager**: Runtime configuration application
5. **Theme System**: KeyboardTheme, MaterialThemeManager

---

## TODO: Verification & Implementation Tasks

### Phase 1: Critical Settings Issues (P0)
**Duration**: 2-3 hours
**Tasks**:
- [ ] Add Termux mode checkbox to settings.xml
- [ ] Verify Config.kt reads termux_mode_enabled
- [ ] Test Termux mode functionality
- [ ] Review and fix Android 11+ storage permissions

### Phase 2: Medium Priority Settings (P1)
**Duration**: 3-4 hours
**Tasks**:
- [ ] Implement theme propagation without restart
- [ ] Implement emoji preferences persistence
- [ ] Fix ConfigurationManager theme application
- [ ] Test all settings apply correctly

### Phase 3: Low Priority Polish (P2)
**Duration**: 1-2 hours
**Tasks**:
- [ ] Plan migration from deprecated APIs
- [ ] Extract hardcoded strings to resources
- [ ] Prepare i18n structure

---

## Testing Strategy

### Unit Tests
- Test case 1: SharedPreferences read/write
- Test case 2: Config initialization
- Test case 3: Theme propagation

### Integration Tests
- Test case 1: Settings UI → Config → Runtime application
- Test case 2: Storage permissions on Android 11+
- Test case 3: Theme switching without restart

### Manual Tests
- Test case 1: All settings save and load correctly
- Test case 2: Termux mode works as expected
- Test case 3: Emoji recent/favorites persist
- Test case 4: Theme changes apply immediately

---

## Dependencies

### Internal Dependencies
- theme/KeyboardTheme.kt
- config/Config.kt
- config/ConfigurationManager.kt

### External Dependencies
- AndroidX Preference library
- Material 3 Compose
- SharedPreferences API

---

## Success Metrics
- All settings accessible from UI
- 100% preference persistence rate
- Theme changes apply in <200ms
- Zero storage permission errors on Android 11+
- All emoji preferences persist correctly

---

**Created**: 2025-10-21
**Last Updated**: 2025-10-21
**Owner**: CleverKeys Development Team
**Status**: Historical issues documented, verification tasks created
