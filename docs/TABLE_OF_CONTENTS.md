# CleverKeys Documentation - Table of Contents

**Last Updated**: 2025-10-20
**Review Status**: Files 82-85 of 251 (32.7% complete)

## 📋 Quick Navigation

### Essential Documents
- **Primary Instructions**: `CLAUDE.md` - Main development workflow and commands
- **Project Status**: `migrate/project_status.md` - Current milestone and completion status
- **Critical Fixes**: `migrate/todo/critical.md` - P0 showstopper bugs and quick wins
- **Completed Work**: `migrate/claude_history.md` - Historical development log

### Active TODO Lists (Prioritized)
- `migrate/todo/critical.md` - P0 showstoppers requiring immediate attention
- `migrate/todo/core.md` - Core keyboard logic bugs
- `migrate/todo/features.md` - Missing user-facing features
- `migrate/todo/neural.md` - ONNX prediction pipeline issues
- `migrate/todo/settings.md` - Settings and preferences bugs
- `migrate/todo/ui.md` - UI/UX issues

## 🗺️ Documentation Structure

### `/` Root Directory

#### Development Instructions
| File | Purpose | Status |
|------|---------|--------|
| `CLAUDE.md` | Main development guide | ✅ Active |
| `README.md` | Project overview | ✅ Active |
| `CONTRIBUTING.md` | Contribution guidelines | ✅ Active |
| `DEVELOPMENT.md` | Development setup | ✅ Active |

#### Build & Deployment
| File | Purpose | Status |
|------|---------|--------|
| `BUILD_SCRIPTS.md` | Build automation | ✅ Active |
| `DEPLOYMENT.md` | Deployment procedures | ✅ Active |
| `build-on-termux.sh` | Termux build script | ✅ Active |

#### Architecture & Design
| File | Purpose | Status |
|------|---------|--------|
| `ARCHITECTURE_ANALYSIS.md` | System architecture analysis | 📚 Reference |
| `KOTLIN_MIGRATION.md` | Java→Kotlin migration guide | ✅ Complete |
| `MIGRATION_CHECKLIST.md` | Migration tracking | ✅ Complete |

#### Features & Issues
| File | Purpose | Status |
|------|---------|--------|
| `MISSING_FEATURES.md` | Feature parity tracking | 🔄 Needs consolidation |
| `ISSUES.md` | Known issues log | 🔄 Needs consolidation |

#### Model & Neural Pipeline
| File | Purpose | Status |
|------|---------|--------|
| `MODEL_EXPORT_STATUS.md` | ONNX model status | ✅ Active |
| `ONNX_MODEL_UPDATE_REQUIRED.md` | Model update notes | 📚 Reference |
| `CLI_TEST_README.md` | CLI testing guide | ✅ Active |

#### Review Files (Files 82-85)
| File | Purpose | Status |
|------|---------|--------|
| `REVIEW_FILE_82_ExtraKeys.md` | ExtraKeys.java review | ✅ Implemented |
| `REVIEW_FILE_83_FoldStateTracker.md` | FoldStateTracker review | ✅ Enhanced |
| `REVIEW_FILE_84_Gesture.md` | Gesture.java review | ❌ Missing - HIGH PRIORITY |
| `REVIEW_FILE_85_GestureClassifier.md` | GestureClassifier review | ❓ Needs audit |

#### Legacy/Deprecated (Consolidate)
| File | Purpose | Status |
|------|---------|--------|
| `CURRENT_SESSION_STATUS.md` | Old status tracker | ⚠️ Migrated - Delete |
| `REVIEW_COMPLETED.md` | Old review log | ⚠️ Migrated - Delete |
| `FINAL_STATUS.md` | Old status file | 🔄 Merge or delete |
| `TODO.md` | Legacy TODO | 🔄 Consolidate |
| `TODONOW.md` | Legacy urgent TODO | 🔄 Consolidate |
| `TODO_ARCHITECTURAL.md` | Architecture TODOs | 🔄 Consolidate |
| `TODO_CRITICAL_BUGS.md` | Critical bug list | 🔄 Consolidate |
| `TODO_HIGH_PRIORITY.md` | High priority list | 🔄 Consolidate |
| `TODO_MEDIUM_LOW.md` | Medium/low priority | 🔄 Consolidate |
| `REVIEW_TODO_CORE.md` | Core review TODOs | 🔄 Consolidate |
| `REVIEW_TODO_GESTURES.md` | Gesture TODOs | 🔄 Consolidate |
| `REVIEW_TODO_LAYOUT.md` | Layout TODOs | 🔄 Consolidate |
| `REVIEW_TODO_ML_DATA.md` | ML data TODOs | 🔄 Consolidate |
| `REVIEW_TODO_NEURAL.md` | Neural TODOs | 🔄 Consolidate |

### `/migrate/` Migration Directory

#### Organized Status
| File | Purpose | Status |
|------|---------|--------|
| `README.md` | Migration overview | ✅ Active |
| `project_status.md` | Current project status | ✅ Active - **CHECK FIRST** |
| `claude_history.md` | Historical development log | ✅ Active |
| `completed_reviews.md` | Completed file reviews | ✅ Active |

#### Categorized TODOs (`/migrate/todo/`)
| File | Purpose | Status |
|------|---------|--------|
| `critical.md` | P0 showstoppers | ✅ Active - **FIX #51-53 DONE** |
| `core.md` | Core keyboard bugs | ✅ Active |
| `features.md` | Missing features | ✅ Active |
| `neural.md` | ONNX pipeline issues | ✅ Active |
| `settings.md` | Settings bugs | ✅ Active |
| `ui.md` | UI/UX issues | ✅ Active |

### `/docs/` Documentation Directory

#### Specifications (`/docs/specs/`)
*TO BE CREATED - Following CustomCamera pattern*
| File | Purpose | Status |
|------|---------|--------|
| `SPEC_TEMPLATE.md` | Template for new specs | 📝 To create |
| `core-keyboard-system.md` | Core keyboard spec | 📝 To create |
| `neural-prediction.md` | ONNX prediction spec | 📝 To create |
| `gesture-recognition.md` | Gesture system spec | 📝 To create |
| `layout-customization.md` | Layout engine spec | 📝 To create |
| `settings-system.md` | Settings architecture | 📝 To create |

#### History (`/docs/history/`)
| File | Purpose | Status |
|------|---------|--------|
| `ONNX_DECODE_PIPELINE.md` | ONNX pipeline docs | ✅ Existing |
| `md_audit.txt` | MD files audit list | ✅ Created |

## 📊 Review Progress

**Java Files Reviewed**: 82-85 of 251 (32.7%)
**Critical Fixes Applied**: 3/3 (Fix #51, #52, #53) ✅
**APK Build Status**: SUCCESS (49MB)
**Next Review Files**: 86-100

### Review Tracking
- Files 1-81: Completed (see `migrate/claude_history.md`)
- Files 82-85: Current batch (4 files reviewed)
- Files 86-251: Remaining (165 files)

## 🔄 Consolidation Plan

### Phase 1: Audit (Current)
- [x] Create docs/specs and docs/history directories
- [x] List all MD files systematically
- [ ] Audit each file line-by-line (no tail/head)
- [ ] Categorize: Keep, Consolidate, Archive, Delete

### Phase 2: Consolidate
- [ ] Move TODOs from all sources to `/migrate/todo/`
- [ ] Move history to `/docs/history/`
- [ ] Create specs for major systems
- [ ] Archive deprecated files

### Phase 3: Organize
- [ ] Update CLAUDE.md with spec-driven workflow
- [ ] Create TABLE_OF_CONTENTS.md (this file)
- [ ] Establish clear file ownership
- [ ] Document update protocols

## 🎯 Spec-Driven Development Workflow

### Adding New Features
1. **Define Spec**: Create `docs/specs/feature-name.md` using template
2. **Update TOC**: Add to this TABLE_OF_CONTENTS.md
3. **Plan Tasks**: Add tasks to spec file's TODO section
4. **Implement**: Follow spec requirements
5. **Move History**: Completed work → `docs/history/feature-name-log.md`
6. **Update Status**: Mark complete in spec and project_status.md

### Working on Existing Systems
1. **Check Spec**: Read `docs/specs/system-name.md` first
2. **Review TODOs**: Check spec's TODO section at top
3. **Make Changes**: Follow spec architecture
4. **Update Spec**: Document any architectural changes
5. **Log Work**: Add to spec's history section or separate log

### Session Startup Protocol
1. `cd ~/git/swype/cleverkeys`
2. Check: `cat migrate/project_status.md` (overall status)
3. Check: `cat docs/TABLE_OF_CONTENTS.md` (this file - navigation)
4. Check: `cat migrate/todo/critical.md` (P0 issues)
5. Check relevant spec: `cat docs/specs/[feature].md`

---

**Next Actions**:
1. Complete line-by-line audit of all 40 MD files
2. Create spec files for major systems (keyboard, neural, gestures, layouts, settings)
3. Consolidate 15 TODO files into organized structure
4. Archive or delete deprecated files
5. Update CLAUDE.md with spec-driven workflow
