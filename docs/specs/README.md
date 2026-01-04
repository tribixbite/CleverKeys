# CleverKeys Feature Specifications

**Last Updated**: 2026-01-04
**Total Specs**: 14 (core) + 4 (optional enhancements)

This directory contains feature specifications and architectural decision records (ADRs) for CleverKeys. All specifications follow the template in `SPEC_TEMPLATE.md`.

---

## 📋 Table of Contents

### Core System Specifications

1. **[Core Keyboard System](./core-keyboard-system.md)** - P0
   - Status: ✅ Implemented (Maintenance & Enhancement)
   - Covers: View initialization, key events, layout management, service integration
   - Recent Updates: Layout switching implemented, hardware acceleration verified
   - Outstanding: Testing hardware acceleration with ONNX models

2. **[Gesture System](./gesture-system.md)** - P0
   - Status: ✅ Implemented (Verified 2025-10-23)
   - Covers: Swipe detection, multi-touch gestures, gesture trails
   - Critical Issues: ~~None~~ (Bug #267 resolved)
   - Outstanding: Device testing and performance profiling

3. **[Layout System](./layout-system.md)** - P1
   - Status: ✅ Implemented (Verified 2025-11-16)
   - Covers: ExtraKeys customization, layout switching, custom layouts
   - Critical Issues: ~~None~~ (Bug #266 resolved)
   - Outstanding: Custom layout editor save/load (future enhancement)

4. **[Neural Prediction](./neural-prediction.md)** - P0
   - Status: ✅ Implemented (Verified 2025-11-14)
   - Covers: ONNX transformer-based prediction, training pipeline
   - Recent Updates: ✅ WordPredictor integration complete (2025-11-14)
   - Recent Updates: ✅ BigramModel integration complete (2025-11-14)
   - Recent Updates: ✅ LanguageDetector integration complete (2025-11-14)
   - Recent Updates: ✅ UserAdaptationManager integration complete (2025-11-14)
   - Recent Updates: ✅ Initialization order bug fixed (2025-11-14)
   - Recent Updates: ✅ Training data persistence via SQLite (Bug #273 fixed 2025-11-02)
   - Outstanding: Optional dictionary/bigram assets for improved accuracy

### Supporting System Specifications

5. **[Settings System](./settings-system.md)** - P1
   - Status: ✅ Complete (**100% feature parity - 45/45 settings** - FINAL!)
   - Covers: User preferences, theme settings, keyboard behavior, layout management, extra keys
   - Recent Updates: ✅ User preferences integration complete (2025-11-14)
   - Recent Updates: ✅ Comprehensive feature parity analysis complete (2025-11-16)
   - Recent Updates: ✅ 25 new settings implemented (2025-11-16) - P1/P2/P3/P4 complete
   - Recent Updates: ✅ Layout Manager Activity complete (2025-11-16)
   - Recent Updates: ✅ Extra Keys Configuration Activity complete (2025-11-16)
   - Outstanding: None - 100% parity achieved!

6. **[Settings-Layout Integration](./settings-layout-integration.md)** - P1
   - Status: ✅ Complete (2025-12-11)
   - Covers: GUI mapping, settings storage, layout system integration, hot-reload
   - Components: Per-Key Actions, Extra Keys, Layout Manager
   - Recent Updates: ✅ SharedPreferences mismatch fixed (2025-12-11)
   - Recent Updates: ✅ switch_forward/switch_backward added to Extra Keys
   - Outstanding: None

8. **[UI & Material 3 Modernization](./ui-material3-modernization.md)** - P2
   - Status: ✅ Complete (All phases implemented - updated 2025-11-16)
   - Covers: Material 3 theming, dark mode, animations
   - Recent Updates: ✅ Material 3 integration complete across all components
   - Outstanding: Theme customization UI (optional enhancement)

9. **[Performance Optimization](./performance-optimization.md)** - P2
   - Status: ✅ Verified (2025-11-16)
   - Covers: Rendering optimization, memory management, ONNX performance
   - Recent Updates: ✅ Hardware acceleration verified enabled (2025-11-16)
   - Recent Updates: ✅ Performance monitoring cleanup verified (90+ components, 2025-11-16)
   - Recent Updates: ✅ Memory leak prevention verified (zero leak vectors, 2025-11-16)
   - Outstanding: Device testing for performance benchmarks (optional)

10. **[Test Suite](./test-suite.md)** - P2
   - Status: 🟡 Partially Implemented
   - Covers: Unit tests, integration tests, UI tests, automated testing
   - Recent Updates: ✅ Automated keyboard testing script created (2025-11-14)
   - Outstanding: Expand test coverage, fix unit test build issues

### Architectural Documents

11. **[Architectural Decisions](./architectural-decisions.md)** - Reference
   - Status: ✅ Complete (Living document)
   - Contains: 7 ADRs (ONNX architecture, coroutines, initialization order, etc.)
   - Last Updated: 2025-11-16
   - Outstanding: None (all documented)

12. **[Short Swipe Customization](./short-swipe-customization.md)** - P1
    - Status: ✅ Complete (Updated 2025-12-07)
    - Covers: Per-key short swipe customization, 8-direction gestures, command palette
    - Recent Updates: ✅ Shift+custom swipe support (2025-12-07)
    - Recent Updates: ✅ Colored direction zones with labels (2025-12-07)
    - Outstanding: Manual device testing

13. **[Cursor Navigation System](./cursor-navigation-system.md)** - P1
    - Status: ✅ Complete (Created 2025-12-19)
    - Covers: Spacebar slider-based cursor, dedicated nav key arrows, swipe_scaling
    - Components: `Pointers.kt` (Sliding class), `KeyValue.kt` (Slider enum), `KeyEventHandler.kt`
    - Key Concepts: swipe_scaling calculation, speed smoothing, distance accumulation
    - Outstanding: None

14. **[SPEC_TEMPLATE.md](./SPEC_TEMPLATE.md)** - Template
    - Template for creating new feature specifications
    - Use this when creating new specs

### Optional Enhancement Specs (Not Yet Created)

These specs would document existing features that don't have formal documentation yet:

12. **Clipboard System** (optional)
    - Components: `ClipboardManager.kt`, `ClipboardDatabase.kt`, `ClipboardHistoryView.kt`
    - Status: ✅ Implemented (no spec needed for current functionality)

13. **Dictionary & Word Prediction** (optional)
    - Components: `DictionaryManager.kt`, `WordPredictor.kt`, `UserAdaptationManager.kt`
    - Status: ✅ Implemented (partially covered in neural-prediction.md)

14. **Privacy & ML Collection** (optional)
    - Components: `PrivacyManager.kt`, `MLDataCollector.kt`, `SwipeMLDataStore.kt`
    - Status: ✅ Implemented (new Phase 6.5 feature)

15. **A/B Testing & Model Versioning** (optional)
    - Components: `ABTestManager.kt`, `ModelVersionManager.kt`, `ModelComparisonTracker.kt`
    - Status: ✅ Implemented (new Phase 6.3-6.4 features)

16. **[Secondary Language Integration](./secondary-language-integration.md)** - P1
    - Status: ✅ COMPLETE (v1.1.84 - 2026-01-04)
    - Covers: Multilanguage typing, accent normalization, language detection, language packs
    - Components: `AccentNormalizer.kt`, `NormalizedPrefixIndex.kt`, `UnigramLanguageDetector.kt`, `LanguagePackManager.kt`
    - Phases: V2 dictionaries → Multi-dictionary → Detection → Auto-switch → Language packs

---

## 🎯 Current Focus (2026-01-04)

### Session Progress (2025-12-04) ✅
- **Spec Audit Complete**: All 10 core specs reviewed and verified
- **Architecture Documentation**: `ARCHITECTURE_MASTER.md` created with all parameters
- **Questions Resolved**: `questions.md` updated (7 items resolved/analyzed)
- **Performance Audit**: No blocking operations found
- **Settings Verification**: Privacy/AB-test/rollback all fully implemented

### All Critical Systems Complete ✅
- ✅ Neural prediction pipeline (ONNX transformer + beam search)
- ✅ Gesture recognition system (swipe detection + trails)
- ✅ Settings system (100% UK parity + CK enhancements)
- ✅ Privacy controls (PrivacyManager.kt - Phase 6.5)
- ✅ A/B testing framework (ABTestManager.kt - Phase 6.3)
- ✅ Model versioning/rollback (ModelVersionManager.kt - Phase 6.4)

### Remaining Work (All Non-Blocking)
1. **CI/CD Setup**: ✅ Complete (7 workflows: ci.yml, build.yml, ui-testing.yml, release.yml, nightly.yml, etc.)
2. **Theme Creation**: ✅ Complete (ThemeSettingsActivity with HSL color picker, custom themes, export/import)
3. **Swipe Trail Themes**: ✅ Complete (KeyboardColorScheme includes swipeTrail, integrated in ThemeSettingsActivity)
4. **Device Testing**: Manual validation (ongoing)

---

## 📊 Status Summary

### By Priority
- **P0 (Critical)**: 3 specs (Core, Gesture, Neural)
  - Core: ✅ Implemented
  - Gesture: ✅ Implemented (Bug #267 resolved)
  - Neural: ✅ Implemented (Bug #273 resolved)

- **P1 (High)**: 3 specs (Layout, Settings, Short Swipe Customization)
  - Layout: ✅ Implemented (Bug #266 resolved)
  - Settings: ✅ Complete ✅ **100% PARITY (45/45 settings)**
  - Short Swipe Customization: ✅ Complete (shift+swipe, colored zones)

- **P2 (Medium)**: 3 specs (UI, Performance, Testing)
  - UI: ✅ Implemented
  - Performance: ✅ Verified (2025-11-16)
  - Testing: 🟡 Partially (automated script ✅ complete, device testing pending)

### By Status
- ✅ **Fully Implemented**: 9 (Core, Gesture, Layout, Neural, Settings, UI, Performance, Architectural, Short Swipe Customization)
- 🟡 **Partially Implemented**: 1 (Testing - automated script ready, device testing pending)
- 🔴 **Not Started**: 0

**Production Readiness**: ✅ **ALL CRITICAL SPECS COMPLETE** (Score: 86/100, Grade A)

---

## 🔄 Spec Update Protocol

When updating specs after implementation work:

1. **Update TODOs Section**: Mark completed items with ✅
2. **Update Status**: Change status indicator if appropriate
3. **Add Implementation Notes**: Document what was done
4. **Update Testing Status**: Note what needs testing
5. **Update Recent Updates**: Add timestamped updates
6. **Commit with Reference**: Link commit to spec

Example commit message:
```
feat: implement prediction pipeline integration

Addresses items in neural-prediction.md:
- ✅ WordPredictor integration
- ✅ BigramModel integration
- ✅ LanguageDetector integration
- ✅ UserAdaptationManager integration
- ✅ Fixed initialization order bug

See: docs/specs/neural-prediction.md
```

---

## 📝 Creating New Specs

1. Copy `SPEC_TEMPLATE.md` to new filename
2. Fill in all sections (remove template instructions)
3. Add TODOs section at top with actionable items
4. Update this README.md with new spec entry
5. Commit with descriptive message

---

## 🔗 Related Documentation

- **[TABLE_OF_CONTENTS.md](../TABLE_OF_CONTENTS.md)** - Master navigation for all docs
- **[COMPLETE_REVIEW_STATUS.md](../COMPLETE_REVIEW_STATUS.md)** - File review progress
- **[project_status.md](../../migrate/project_status.md)** - Current milestone status
- **[todo/](../../migrate/todo/)** - Bug tracking by component

---

## 📈 Spec-Driven Development Workflow

CleverKeys follows a **spec-driven development** approach:

1. **Check Spec**: Before implementing, check if spec exists
2. **Create Spec**: If missing, create from template
3. **Implement**: Follow spec's implementation plan
4. **Test**: Use spec's testing strategy
5. **Update**: Mark TODOs complete, update status
6. **Commit**: Reference spec in commit message

This ensures:
- ✅ Features are fully designed before implementation
- ✅ All stakeholders understand the approach
- ✅ Testing is planned upfront
- ✅ Implementation is tracked and documented
- ✅ Knowledge is preserved for future developers

---

**Note**: This README is automatically updated when specs are added or significantly changed. Always keep it in sync with the actual spec files.
