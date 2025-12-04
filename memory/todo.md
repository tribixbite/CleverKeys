# CleverKeys Working TODO List

**Last Updated**: 2025-12-04
**Session**: Comprehensive spec audit and architecture documentation

---

## Current Session Tasks

### Completed
- [x] Run all tests including cli-test (compiles successfully)
- [x] Fix neural-prediction.md inconsistencies (Bug status conflicts resolved)
- [x] Review and update remaining docs/specs files
- [x] Update ARCHITECTURE_MASTER.md with all params/weights/equations
- [x] Review settings discrepancies vs UK user settings
- [x] Remove hardcoded paths like 'sdcard' (none found)
- [x] Verify swipe calibration playground saves to runtime (SharedPreferences)
- [x] Add log viewer/export for swipe debug log (SwipeDebugActivity already has it)
- [x] Verify dictionary management tabs (has 4 tabs, not 3)
- [x] Verify privacy/AB-test/rollback settings (FULLY IMPLEMENTED - see below)
- [x] Check for load/latency inefficiencies (no blocking operations found)

### In Progress
- [ ] Organize and clean archive files

### Pending
- [ ] Create missing spec documents
- [ ] Set up CI/CD with full test coverage
- [ ] Implement on-the-fly theme creation for keyboard
- [ ] Implement swipe trail theme creation with colorwheel

---

## Important Findings

### Privacy/AB-Test/Rollback Settings - VERIFIED IMPLEMENTED
These settings were previously thought to be UI-only but are FULLY IMPLEMENTED:
- **Privacy settings**: `PrivacyManager.kt` - consent, collection, anonymization, retention
- **A/B testing**: `ABTestManager.kt`, `ModelComparisonTracker.kt` - traffic split, metrics
- **Rollback**: `ModelVersionManager.kt` - version history, auto-rollback, pinning

All settings are saved via `SettingsActivity.kt` and used by respective manager classes.

### UK vs CK Differences
- CK has swipe trail settings (5 settings) not in UK
- CK has different defaults for: longPressInterval (25 vs 65), characterSize (1.18 vs 1.15), clipboard_history_enabled (true vs false)

### Documentation Status
- ARCHITECTURE_MASTER.md: Created with comprehensive parameter documentation
- neural-prediction.md: Fixed all status inconsistencies
- questions.md: Created with 7 resolved/analyzed items

---

## Files Modified This Session
- `docs/ARCHITECTURE_MASTER.md` (new)
- `docs/questions.md` (new)
- `docs/specs/neural-prediction.md` (fixed inconsistencies)
- `memory/todo.md` (new)

---

## Reference Links
- See `docs/questions.md` for detailed analysis of each item
- See `docs/specs/README.md` for spec index
- See `docs/TABLE_OF_CONTENTS.md` for master file navigation
