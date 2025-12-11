# CleverKeys Documentation - Table of Contents

**Last Updated**: 2025-12-11
**Review Status**: Files 251 of 251 (100% complete) ✅

## 📋 Quick Navigation

### Essential Documents
- **Primary Instructions**: `CLAUDE.md` - Main development workflow and commands
- **Project Status**: `README.md` - Production status and overview
- **Current Tasks**: `memory/todo.md` - Active todo list
- **History**: `docs/history/session_log_dec_2025.md` - Recent completed work

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

#### Features & Issues
| File | Purpose | Status |
|------|---------|--------|
| `memory/todo.md` | **Active Task List** | ✅ Active |
| `docs/history/` | Historical logs and archives | 📚 Reference |

#### Model & Neural Pipeline
| File | Purpose | Status |
|------|---------|--------|
| `docs/specs/neural-prediction.md` | ONNX pipeline spec | ✅ Active |
| `CLI_TEST_README.md` | CLI testing guide | ✅ Active |

#### Testing
| File | Purpose | Status |
|------|---------|--------|
| `MANUAL_TESTING_GUIDE.md` | Manual testing procedures | ✅ Active |
| `test-keyboard-automated.sh` | ADB testing script | ✅ Active |

### `/docs/specs/` Specifications
*Spec-driven development - All major systems documented*

| File | Purpose | Status |
|------|---------|--------|
| `README.md` | Master ToC for specs | ✅ Active |
| `SPEC_TEMPLATE.md` | Template for new specs | ✅ Active |
| `core-keyboard-system.md` | Core keyboard operations | ✅ Implemented |
| `gesture-system.md` | Gesture recognition | ✅ Implemented |
| `neural-prediction.md` | ONNX prediction pipeline | ✅ Implemented |
| `layout-system.md` | Layout & extra keys | ✅ Implemented |
| `settings-system.md` | Settings & preferences | ✅ Implemented |
| `ui-material3-modernization.md` | Material 3 UI | ✅ Implemented |
| `performance-optimization.md` | Performance & monitoring | ✅ Complete |
| `test-suite.md` | Testing infrastructure | ✅ Complete |
| `short-swipe-customization.md` | **NEW** Short Swipe System | ✅ Implemented |
| `profile_system_restoration.md` | **NEW** Profile Import/Export | ✅ Implemented |
| `architectural-decisions.md` | Architectural Decision Records | ✅ Active |

### `/docs/history/` History
| File | Purpose | Status |
|------|---------|--------|
| `session_log_dec_2025.md` | December 2025 Work Log | ✅ Archived |
| `ONNX_DECODE_PIPELINE.md` | ONNX pipeline docs | ✅ Active |
| `PRODUCTION_READY_NOV_16_2025.md` | Production readiness report | 📚 Reference |

## 🔄 Consolidation Status

**Verification**:
- Legacy `migrate/todo` directory has been cleared/consolidated ✅
- `memory/todo.md` is the single source of truth for active tasks ✅
- Specs are up-to-date with recent features (Short Swipes, Profiles) ✅

## 🎯 Spec-Driven Development Workflow

### Adding New Features
1. **Define Spec**: Create `docs/specs/feature-name.md` using template
2. **Update TOC**: Add to this TABLE_OF_CONTENTS.md
3. **Plan Tasks**: Add tasks to spec file's TODO section or `memory/todo.md`
4. **Implement**: Follow spec requirements
5. **Move History**: Completed work → `docs/history/`
6. **Update Status**: Mark complete in spec

### Session Startup Protocol
1. `cd ~/git/swype/cleverkeys`
2. Check: `cat memory/todo.md` (current tasks)
3. Check: `cat docs/TABLE_OF_CONTENTS.md` (navigation)
4. Check relevant spec: `cat docs/specs/[feature].md`