# CleverKeys Documentation - Table of Contents

**Last Updated**: 2025-11-14
**Review Status**: Files 251 of 251 (100% complete) ✅

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
| `MISSING_FEATURES.md` | Feature parity tracking | ✅ Consolidated into TODO files |
| `ISSUES.md` | Known issues log | ✅ Consolidated into TODO files |

#### Model & Neural Pipeline
| File | Purpose | Status |
|------|---------|--------|
| `MODEL_EXPORT_STATUS.md` | ONNX model status | ✅ Active |
| `ONNX_MODEL_UPDATE_REQUIRED.md` | Model update notes | 📚 Reference |
| `CLI_TEST_README.md` | CLI testing guide | ✅ Active |

#### Testing
| File | Purpose | Status |
|------|---------|--------|
| `MANUAL_TESTING_GUIDE.md` | Manual testing procedures | ✅ Active |
| `TESTING_CHECKLIST.md` | Feature checklist | ✅ Active |
| `test-keyboard-automated.sh` | ADB testing script | ✅ Active |

#### Review Files (Files 82-85)
| File | Purpose | Status |
|------|---------|--------|
| `REVIEW_FILE_82_ExtraKeys.md` | ExtraKeys.java review | ✅ Implemented |
| `REVIEW_FILE_83_FoldStateTracker.md` | FoldStateTracker review | ✅ Enhanced |
| `REVIEW_FILE_84_Gesture.md` | Gesture.java review | ✅ Complete (Bug #267 fixed) |
| `REVIEW_FILE_85_GestureClassifier.md` | GestureClassifier review | ✅ Complete |

#### Legacy/Deprecated (All Cleaned Up ✅)
| File | Purpose | Status |
|------|---------|--------|
| `CURRENT_SESSION_STATUS.md` | Old status tracker | ✅ Deleted (migrated) |
| `REVIEW_COMPLETED.md` | Old review log | ✅ Deleted (migrated) |
| `FINAL_STATUS.md` | Old status file | ✅ Deleted (consolidated) |
| `TODO.md` | Legacy TODO | ✅ Deleted (consolidated) |
| `TODONOW.md` | Legacy urgent TODO | ✅ Deleted (consolidated) |
| `TODO_ARCHITECTURAL.md` | Architecture TODOs | ✅ Deleted (consolidated) |
| `TODO_CRITICAL_BUGS.md` | Critical bug list | ✅ Deleted (consolidated) |
| `TODO_HIGH_PRIORITY.md` | High priority list | ✅ Deleted (consolidated) |
| `TODO_MEDIUM_LOW.md` | Medium/low priority | ✅ Deleted (consolidated) |
| `REVIEW_TODO_CORE.md` | Core review TODOs | ✅ Deleted (consolidated) |
| `REVIEW_TODO_GESTURES.md` | Gesture TODOs | ✅ Deleted (consolidated) |
| `REVIEW_TODO_LAYOUT.md` | Layout TODOs | ✅ Deleted (consolidated) |
| `REVIEW_TODO_ML_DATA.md` | ML data TODOs | ✅ Deleted (consolidated) |
| `REVIEW_TODO_NEURAL.md` | Neural TODOs | ✅ Deleted (consolidated) |

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
| `critical.md` | P0 showstoppers | ✅ **ALL RESOLVED** (45 bugs: 38 fixed, 7 false) |
| `core.md` | Core keyboard bugs | ✅ Complete |
| `features.md` | Missing features | ✅ Complete |
| `neural.md` | ONNX pipeline issues | ✅ Complete |
| `settings.md` | Settings bugs | ✅ Complete |
| `ui.md` | UI/UX issues | ✅ Complete |

### `/docs/` Documentation Directory

#### Specifications (`/docs/specs/`)
*Spec-driven development - All major systems documented*
| File | Purpose | Status |
|------|---------|--------|
| `README.md` | **Master ToC for all 10 specs** | ✅ Created 2025-11-14 |
| `SPEC_TEMPLATE.md` | Template for new specs | ✅ Active |
| `core-keyboard-system.md` | Core keyboard operations (P0) | ✅ Implemented |
| `gesture-system.md` | Gesture recognition (P0) | ✅ Implemented (Bug #267 fixed) |
| `neural-prediction.md` | ONNX prediction pipeline (P0) | ✅ Updated 2025-11-14 |
| `layout-system.md` | Layout & extra keys (P1) | ✅ Implemented (Bug #266 fixed) |
| `settings-system.md` | Settings & preferences (P1) | ✅ Implemented |
| `ui-material3-modernization.md` | Material 3 UI (P2) | ✅ Implemented |
| `performance-optimization.md` | Performance & monitoring (P2) | ✅ Complete |
| `test-suite.md` | Testing infrastructure (P2) | ✅ Complete |
| `architectural-decisions.md` | 6 ADRs (Reference) | ✅ Active |

#### History (`/docs/history/`)
| File | Purpose | Status |
|------|---------|--------|
| `ONNX_DECODE_PIPELINE.md` | ONNX pipeline docs | ✅ Active |
| `md_audit.txt` | MD files audit list | ✅ Active |
| `HISTORICAL_ISSUES_MIGRATION.md` | Issues migration log | ✅ Created 2025-10-21 |
| `issues-summary-historical-ARCHIVED-2025-10-21.md` | Original issues (archived) | 📚 Archived |

## 📊 Review Progress

**Java Files Reviewed**: 251/251 (100%) ✅
**APK Build Status**: SUCCESS (52MB)
**Bugs Status**: All P0/P1 resolved (45 total: 38 fixed, 7 false)

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
