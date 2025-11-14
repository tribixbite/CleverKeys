# CleverKeys Feature Specifications

**Last Updated**: 2025-11-14
**Total Specs**: 10

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
   - Status: 🟡 Partially Implemented (Bug #267)
   - Covers: Swipe detection, multi-touch gestures, gesture trails
   - Critical Issues: Gesture trails causing memory leaks
   - Outstanding: Fix memory leaks, test multi-touch gestures

3. **[Layout System](./layout-system.md)** - P1
   - Status: 🟡 Partially Implemented (Bug #266)
   - Covers: ExtraKeys customization, layout switching, custom layouts
   - Critical Issues: ExtraKeys not applied from settings
   - Outstanding: Fix configuration persistence, implement live preview

4. **[Neural Prediction](./neural-prediction.md)** - P0
   - Status: 🟢 Implemented (Outstanding bugs)
   - Covers: ONNX transformer-based prediction, training pipeline
   - Recent Updates: ✅ WordPredictor integration complete (2025-11-14)
   - Recent Updates: ✅ BigramModel integration complete (2025-11-14)
   - Recent Updates: ✅ LanguageDetector integration complete (2025-11-14)
   - Recent Updates: ✅ UserAdaptationManager integration complete (2025-11-14)
   - Recent Updates: ✅ Initialization order bug fixed (2025-11-14)
   - Outstanding: Training data persistence, advanced gesture analysis

### Supporting System Specifications

5. **[Settings System](./settings-system.md)** - P1
   - Status: 🟡 Partially Implemented
   - Covers: User preferences, theme settings, keyboard behavior
   - Recent Updates: ✅ User preferences integration complete (2025-11-14)
   - Outstanding: Settings UI improvements, export/import functionality

6. **[UI & Material 3 Modernization](./ui-material3-modernization.md)** - P2
   - Status: 🟢 Implemented
   - Covers: Material 3 theming, dark mode, animations
   - Recent Updates: ✅ Material 3 integration complete (previous sessions)
   - Outstanding: Theme customization UI

7. **[Performance Optimization](./performance-optimization.md)** - P2
   - Status: 🟡 In Progress
   - Covers: Rendering optimization, memory management, ONNX performance
   - Outstanding: Profile ONNX inference, optimize gesture detection

8. **[Test Suite](./test-suite.md)** - P2
   - Status: 🟡 Partially Implemented
   - Covers: Unit tests, integration tests, UI tests, automated testing
   - Recent Updates: ✅ Automated keyboard testing script created (2025-11-14)
   - Outstanding: Expand test coverage, fix unit test build issues

### Architectural Documents

9. **[Architectural Decisions](./architectural-decisions.md)** - Reference
   - Status: ✅ Complete (Living document)
   - Contains: 6 ADRs (ONNX-only architecture, coroutines, etc.)
   - Last Updated: 2025-10-20
   - Outstanding: Add ADR for recent initialization order fix

10. **[SPEC_TEMPLATE.md](./SPEC_TEMPLATE.md)** - Template
    - Template for creating new feature specifications
    - Use this when creating new specs

---

## 🎯 Current Focus (2025-11-14)

### Recently Completed ✅
- **Prediction Pipeline Integration** (2025-11-14)
  - WordPredictor fully integrated with BigramModel, LanguageDetector, UserAdaptationManager
  - Dictionary system integrated with SwipePruner
  - Long press handlers implemented (auto-repeat, alternate selection)
  - Critical initialization order bug fixed
  - 30/31 TODOs resolved in CleverKeysService.kt

### In Testing Phase 🧪
- **Manual Testing Ready** (2025-11-14)
  - APK built and installed (51MB)
  - Comprehensive testing documentation created:
    - MANUAL_TESTING_GUIDE.md
    - ASSET_FILES_NEEDED.md
    - SESSION_SUMMARY_2025-11-14.md
    - TESTING_READINESS.md
  - Awaiting manual validation of all integrated features

### Outstanding Critical Issues 🔴
1. **Bug #267**: Gesture trail memory leaks (gesture-system.md)
2. **Bug #266**: ExtraKeys not applied (layout-system.md)
3. **Bug #273**: Training data not persisting (neural-prediction.md)
4. **Missing Assets**: Dictionary and bigram JSON files (non-blocking)

---

## 📊 Status Summary

### By Priority
- **P0 (Critical)**: 3 specs (Core, Gesture, Neural)
  - Core: ✅ Implemented
  - Gesture: 🟡 Partially (1 bug)
  - Neural: 🟢 Implemented (4 bugs, 4 fixed in latest session)

- **P1 (High)**: 2 specs (Layout, Settings)
  - Layout: 🟡 Partially (1 bug)
  - Settings: 🟡 Partially (1 bug, user prefs ✅ complete)

- **P2 (Medium)**: 3 specs (UI, Performance, Testing)
  - UI: 🟢 Implemented
  - Performance: 🟡 In Progress
  - Testing: 🟡 Partially (automated script ✅ complete)

### By Status
- ✅ **Fully Implemented**: 2 (Core, UI)
- 🟢 **Implemented with Bugs**: 2 (Neural, Testing)
- 🟡 **Partially Implemented**: 4 (Gesture, Layout, Settings, Performance)
- 🔴 **Not Started**: 0

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
