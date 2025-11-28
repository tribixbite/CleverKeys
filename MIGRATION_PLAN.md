# CleverKeys Migration Plan
## Porting Features from Unexpected-Keyboard (juloo.keyboard2)

**Date**: 2025-11-28
**Author**: tribixbite
**Source**: Unexpected-Keyboard (../Unexpected-Keyboard)
**Target**: CleverKeys (this repo)

---

## Executive Summary

CleverKeys has a modern Material 3 settings UI and correct branding (racoon logo, CleverKeys name), but the core keyboard functionality is broken or incomplete. Unexpected-Keyboard has a fully working keyboard with 16 sessions of improvements including:
- Neural swipe typing with ONNX models
- Smart punctuation
- Auto-capitalization fixes
- Advanced gesture tuning
- GitHub update checking
- And 150+ other features

This migration will port all working functionality from Unexpected-Keyboard to CleverKeys.

---

## Current State Analysis

### Unexpected-Keyboard (SOURCE - WORKING)
- **Kotlin files**: 135 in `srcs/juloo.keyboard2/`
- **Version**: v1.32.962
- **Status**: Fully functional, deployed, tested
- **Key Components**:
  - `Keyboard2.kt` - Main InputMethodService
  - `Keyboard2View.kt` - Keyboard rendering & touch handling
  - `KeyEventHandler.kt` - Key event processing, autocap, smart punctuation
  - `InputCoordinator.kt` - Swipe typing coordination
  - `SuggestionHandler.kt` - Word suggestions & predictions
  - `Config.kt` - All settings with 50+ configurable options
  - `Pointers.kt` - Multi-touch & gesture handling

### CleverKeys (TARGET - BROKEN)
- **Kotlin files**: 164 in `src/main/kotlin/tribixbite/keyboard2/`
- **Version**: ~v2.x
- **Status**: Keyboard doesn't load, splash screen works, settings UI modern but non-functional
- **Problems**:
  - `CleverKeysService.kt` incomplete/wrong architecture
  - Missing `Keyboard2View` equivalent
  - Missing `KeyEventHandler` equivalent
  - Config.kt doesn't match settings
  - 191 obsolete MD files cluttering repo

---

## Migration Phases

### Phase 0: Cleanup (Priority: HIGH)
**Goal**: Remove bloat and prepare for migration

1. Archive/delete 191 obsolete MD files in root directory
2. Keep only: README.md, LICENSE, CLAUDE.md, CHANGELOG.md, CONTRIBUTING.md
3. Create `archive/` folder for any files we might need later
4. Clean up duplicate/backup files

**Files to keep**:
```
README.md
LICENSE
CLAUDE.md
CHANGELOG.md
CONTRIBUTING.md
SECURITY.md
CODE_OF_CONDUCT.md
```

---

### Phase 1: Core Keyboard Engine (Priority: CRITICAL)
**Goal**: Make keyboard load and display

**Copy from Unexpected-Keyboard** (with namespace changes):
| Source File | Target File | Notes |
|-------------|-------------|-------|
| `Keyboard2.kt` | Keep `CleverKeysService.kt` | Merge working parts |
| `Keyboard2View.kt` | `CleverKeysView.kt` | CRITICAL - rendering engine |
| `KeyEventHandler.kt` | `KeyEventHandler.kt` | Direct copy + rebrand |
| `KeyValue.kt` | `KeyValue.kt` | Compare & merge |
| `KeyboardData.kt` | `KeyboardData.kt` | Compare & merge |
| `Pointers.kt` | `Pointers.kt` | Compare & merge |

**Namespace changes**:
- `juloo.keyboard2` → `tribixbite.keyboard2`
- `Keyboard2` → `CleverKeys` (class names)
- Author references: `juloo` → `tribixbite`

---

### Phase 2: Input Processing (Priority: HIGH)
**Goal**: Make typing work

**Copy from Unexpected-Keyboard**:
| Source File | Target File |
|-------------|-------------|
| `InputCoordinator.kt` | `InputCoordinator.kt` |
| `SuggestionHandler.kt` | `SuggestionHandler.kt` |
| `Autocapitalisation.kt` | Already exists - verify |
| `ComposeKey.kt` | Already exists - verify |

---

### Phase 3: Configuration System (Priority: HIGH)
**Goal**: Make settings functional

1. Compare `Config.kt` files - Unexpected has 50+ settings
2. Port all missing settings from Unexpected-Keyboard
3. Update `res/xml/settings.xml` with all preference keys
4. Ensure settings UI binds correctly to Config values

**Key settings to port**:
- smart_punctuation (NEW in v1.32.962)
- autocapitalisation
- swipe_enabled
- neural_prediction settings
- gesture timing settings
- All Advanced Gesture Tuning settings

---

### Phase 4: Neural/ML Components (Priority: MEDIUM)
**Goal**: Make swipe typing work

**Verify these exist and work**:
- `OnnxSwipePredictor.kt`
- `NeuralSwipeEngine.kt`
- `SwipePredictionService.kt`
- ONNX model files in `assets/models/`

**Copy if missing**:
- `BeamSearchDecoder.kt`
- `SwipeGestureRecognizer.kt`
- Model loading/initialization code

---

### Phase 5: Layouts & Resources (Priority: MEDIUM)
**Goal**: All keyboard layouts work

1. Compare `res/` directories
2. Copy missing layout XML files
3. Verify `srcs/layouts/` content matches
4. Update `gen_layouts.py` if needed

---

### Phase 6: Branding & Polish (Priority: LOW)
**Goal**: Proper branding everywhere

1. Update app icon to racoon (already done in launcher)
2. Verify all strings reference "CleverKeys" not "Unexpected"
3. Update author info in all files
4. Update GitHub references
5. Clean up any remaining "juloo" references

---

## File-by-File Migration Checklist

### Critical Files (Copy & Adapt)
- [ ] `Keyboard2View.kt` → `CleverKeysView.kt`
- [ ] `KeyEventHandler.kt` → `KeyEventHandler.kt`
- [ ] `InputCoordinator.kt` → `InputCoordinator.kt`
- [ ] `SuggestionHandler.kt` → `SuggestionHandler.kt`
- [ ] `Config.kt` - Merge settings
- [ ] `Pointers.kt` - Compare & update

### Important Files (Verify/Update)
- [ ] `KeyValue.kt`
- [ ] `KeyboardData.kt`
- [ ] `Theme.kt`
- [ ] `ExtraKeys.kt`
- [ ] `Autocapitalisation.kt`

### Settings/Preferences
- [ ] `res/xml/settings.xml`
- [ ] `res/xml/method.xml`
- [ ] All preference classes in `prefs/`

### Resources
- [ ] `res/layout/` XMLs
- [ ] `res/values/strings.xml`
- [ ] `res/drawable/` icons
- [ ] `srcs/layouts/` keyboard layouts

---

## Search & Replace Patterns

When copying files, apply these replacements:

```
juloo.keyboard2 → tribixbite.keyboard2
juloo → tribixbite
Keyboard2 → CleverKeys (class names only, careful!)
Unexpected Keyboard → CleverKeys
```

---

## Testing Checklist

After each phase, verify:
- [ ] App compiles without errors
- [ ] App installs on device
- [ ] Keyboard can be enabled in Android settings
- [ ] Keyboard appears when tapping text field
- [ ] Keys respond to touch
- [ ] Text is inserted correctly
- [ ] Swipe typing works
- [ ] Settings screen opens
- [ ] Settings changes persist

---

## Risk Assessment

| Risk | Impact | Mitigation |
|------|--------|------------|
| Breaking existing CleverKeys features | High | Git branch for each phase |
| Namespace conflicts | Medium | Careful find/replace |
| Missing dependencies | Medium | Compare build.gradle files |
| UI styling conflicts | Low | Material 3 should adapt |

---

## Timeline Estimate

- Phase 0 (Cleanup): 30 minutes
- Phase 1 (Core Engine): 2-3 hours
- Phase 2 (Input Processing): 1-2 hours
- Phase 3 (Config): 1-2 hours
- Phase 4 (Neural): 1 hour (verify only)
- Phase 5 (Layouts): 1 hour
- Phase 6 (Branding): 30 minutes

**Total**: ~8-10 hours of work

---

## Next Steps

1. **Immediate**: Start Phase 0 cleanup
2. **Then**: Copy Keyboard2View.kt as CleverKeysView.kt
3. **Then**: Wire up CleverKeysService to use new view
4. **Then**: Test keyboard loads
5. **Iterate**: Fix issues, add features

---

## Git Strategy

```bash
# For each phase:
git checkout -b migration/phase-X-description
# Make changes
git add -A
git commit -m "feat(migration): Phase X - description"
git push origin migration/phase-X-description
# Create PR or merge directly
```

---

*This document will be updated as migration progresses.*
