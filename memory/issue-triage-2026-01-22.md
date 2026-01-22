# GitHub Issue Triage - 2026-01-22

## Summary

| Status | Count |
|--------|-------|
| Already Fixed | 5 |
| Simple Fix | 3 |
| Medium Complexity | 4 |
| Complex/Architectural | 3 |

---

## ✅ ALREADY FIXED (Close These)

### #85 - Clipboard can't be set to unlimited
- **Status**: FIXED in commit `07e3eb2c` (today)
- **Fix**: Changed slider from 1-50 to 0-500, 0 = unlimited
- **Action**: Close issue

### #76 - Add emoticons, emoji window replace keyboard
- **Status**: IMPLEMENTED in v1.2.6
- **Fix**: 119 emoticons added, searchable via keywords
- **Note**: Emoji pane already replaces suggestion bar (not full keyboard - by design)
- **Action**: Close issue, comment on replacement behavior

### #74 - Haptic not disabled when Vibrate Feedback disabled
- **Status**: FIXED in v1.2.6 (commit `ef7369a0`)
- **Fix**: Vibration toggle now properly disables all haptics
- **Action**: Close issue - if user still sees bug, request reproduction steps

### #72 - Capitalize suggestions for I and proper nouns
- **Status**: IMPLEMENTED in v1.2.6
- **Fix**: Auto-capitalize "I" and contractions, preserve proper noun case
- **Commits**: `a16a95f5`, `29dd10e6`, `05050b47`, `5d18e039`
- **Action**: Close issue

### #71 - Clipboard causes device freeze (TransactionTooLargeException)
- **Status**: FIXED in v1.2.6 (commit `17203125`)
- **Fix**: Chunked clipboard handling for large entries
- **Action**: Close issue

---

## 🟢 SIMPLE FIX (Include in v1.2.7)

### #82 - Option to disable automatic spaces
- **Complexity**: Simple
- **Analysis**: Code already handles this for Termux mode (SuggestionHandler.kt:629-637)
- **Implementation**: Add user setting to disable trailing space after suggestions
- **Files**:
  - `Config.kt`: Add `auto_space_after_suggestion = true`
  - `SettingsActivity.kt`: Add toggle in Input section
  - `SuggestionHandler.kt`: Check setting instead of just Termux detection
- **Effort**: ~30 min

### #81 - Separate key repeat for Backspace vs Character keys
- **Complexity**: Simple-Medium
- **Analysis**: Current `keyrepeat_enabled` is global (Pointers.kt:600)
- **Implementation**: Split into two settings
- **Files**:
  - `Config.kt`: Add `keyrepeat_backspace_only = false`
  - `SettingsActivity.kt`: Add toggle "Backspace Only" under Key Repeat
  - `Pointers.kt`: Check key type before allowing repeat
- **Effort**: ~45 min

### #77 - Cannot disable Greek/Math toggle on numeric layer
- **Complexity**: Simple
- **Analysis**: Greek/Math toggle is hardcoded in numeric layout
- **Implementation**: Check ExtraKeysPreference settings before showing
- **Files**:
  - `KeyModifier.kt:251`: Check if greekmath is enabled
  - `ExtraKeysPreference.kt`: Already has the setting
- **Effort**: ~30 min

---

## 🟡 MEDIUM COMPLEXITY

### #84 - Smart Punctuation with threshold interval
- **Complexity**: Medium
- **Analysis**: Current smart punctuation is immediate (KeyEventHandler.kt:254-291)
- **Implementation**: Add timing check before applying punctuation attachment
- **Files**:
  - `Config.kt`: Add `smart_punctuation_threshold_ms = 0` (0 = always, else threshold)
  - `SettingsActivity.kt`: Add slider 0-500ms
  - `KeyEventHandler.kt`: Track last key timestamp, check threshold
- **Effort**: ~1-2 hours

### #83 - Short swipe directional keys not working with average length swipes
- **Complexity**: Medium (needs debugging)
- **Analysis**: Conflict between short swipe detection and directional subkey system
- **Root cause**: Short swipe threshold vs directional key threshold overlap
- **Files**:
  - `Pointers.kt`: onTouchMove short swipe handling (~line 475+)
  - `Config.kt`: short_swipe_distance_threshold
- **Investigation needed**: Test with SwipeDebugActivity
- **Effort**: ~2-3 hours (debugging required)

### #79 - Settings UI header flickering during scroll
- **Complexity**: Medium (Compose UI)
- **Analysis**: Likely LazyColumn recomposition issue
- **Files**:
  - `SettingsActivity.kt`: Search/header Compose code
- **Fix options**:
  - Add `remember` for header state
  - Use `derivedStateOf` for scroll position
  - Check for unnecessary recompositions
- **Effort**: ~1-2 hours

### #78 - Word prediction doesn't replace text in Termux/some apps
- **Complexity**: Medium-High
- **Analysis**: InputConnection handling differs by app. Works in test field.
- **Root cause**: Some apps (Termux) have non-standard InputConnection behavior
- **Files**:
  - `InputCoordinator.kt`: setComposingText/commitText handling
  - `SuggestionHandler.kt`: Text replacement logic
- **Note**: Already have Termux-specific handling, may need expansion
- **Effort**: ~2-4 hours (needs testing with multiple apps)

---

## 🔴 COMPLEX / ARCHITECTURAL

### #80 - Clipboard suggestion strip & navigation improvements
- **Complexity**: High (New UI feature)
- **Requests**:
  1. Show recent clipboard in suggestion strip - needs SuggestionBar redesign
  2. Add visible X/Back button in panels - relatively simple
  3. Replace keyboard with clipboard - against current design philosophy
- **Recommendation**:
  - Part 2 (X button): Simple, include in v1.2.7
  - Part 1 (clipboard in strip): Defer to v1.3.0, needs design
  - Part 3 (replace keyboard): Discuss with user
- **Files**:
  - `SuggestionBar.kt`, `SuggestionBarInitializer.kt`
  - `res/layout/clipboard_pane.xml`, `res/layout/emoji_pane.xml`
- **Effort**: 4-8 hours for full implementation

### #75 - Swiss French QWERTZ swipe behavior incorrect
- **Complexity**: High (Neural model issue)
- **Analysis**: Neural swipe model was trained on QWERTY layout
- **Root cause**: Model uses key positions, not key labels
- **Fix options**:
  1. Retrain model with QWERTZ layouts (major effort)
  2. Add key position remapping layer (medium effort)
  3. Document limitation (workaround)
- **Files**:
  - `SwipePredictorOrchestrator.kt`
  - `neural/` model files
  - Layout XML files
- **Effort**: 8-40 hours depending on approach

### #70 - Import/export via Intent (truncated in API response)
- **Complexity**: Medium-High
- **Analysis**: Need to add Intent receivers for backup/restore
- **Files**:
  - `BackupRestoreManager.kt`
  - `AndroidManifest.xml`: Add intent-filter
- **Effort**: 2-4 hours

---

## RECOMMENDED FOR v1.2.7

### Must Include (Already Done)
- [x] #85 - Clipboard unlimited (FIXED)

### Should Include (Simple)
- [ ] #82 - Disable auto-space option
- [ ] #81 - Separate backspace key repeat
- [ ] #77 - Respect Greek/Math disabled setting
- [ ] #80 (partial) - Add X button to emoji/clipboard panes

### Consider (If Time)
- [ ] #84 - Smart punctuation threshold
- [ ] #79 - Settings UI flicker fix

### Defer to v1.3.0
- [ ] #80 (full) - Clipboard in suggestion strip
- [ ] #75 - QWERTZ swipe model
- [ ] #83 - Needs more debugging first
- [ ] #78 - Needs more testing with various apps

---

## Closed Issues to Verify

Before releasing v1.2.7, verify these are actually working:
1. #74 - Haptic toggle (user reported in v1.2.5)
2. #71 - Clipboard freeze with large items
3. #72 - I and proper noun capitalization

---

## Files Most Frequently Touched

| File | Issue Count |
|------|-------------|
| `SettingsActivity.kt` | 6 |
| `Config.kt` | 5 |
| `Pointers.kt` | 2 |
| `SuggestionHandler.kt` | 2 |
| `KeyEventHandler.kt` | 2 |

---

*Generated: 2026-01-22*
