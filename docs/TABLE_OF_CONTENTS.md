# CleverKeys Documentation - Table of Contents

**Last Updated**: 2026-08-15
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

#### Model & Swipe Pipeline
| File | Purpose | Status |
|------|---------|--------|
| `docs/specs/ctc-swipe-engine.md` | CTC swipe engine spec | ✅ Active |
| `docs/specs/geometric-swipe-engine.md` | Geometric swipe engine spec | ✅ Active |
| `docs/history/neural-engine/` | The removed ONNX transformer engine (ADR-011) | 📚 Archived |
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
| `ctc-swipe-engine.md` | CTC swipe engine | ✅ Implemented |
| `layout-system.md` | Layout & extra keys | ✅ Implemented |
| `settings-system.md` | Settings & preferences | ✅ Implemented |
| `ui-material3-modernization.md` | Material 3 UI | ✅ Implemented |
| `performance-optimization.md` | Performance & monitoring | ✅ Complete |
| `testing-strategy.md` | Testing infrastructure (2050+ tests) | ✅ Active |
| `short-swipe-customization.md` | **NEW** Short Swipe System | ✅ Implemented |
| `profile_system_restoration.md` | **NEW** Profile Import/Export | ✅ Implemented |
| `geometric-swipe-engine.md` | Layout-agnostic geometric swipe decoder (standalone) | ✅ Implemented |
| `context-learning-and-next-word.md` | **NEW 2026-08-06** Persistent context LM, master learning privacy gate, opt-in next-word prediction, suggestion provenance, learned-data manager | ✅ Implemented |
| `ctc-swipe-engine.md` | **UPDATED 2026-08-15** CTC trie-beam swipe engine — WIRED opt-in `ctc` mode (2026-08-08): CleverKeys-trained ONNX encoder, router/adapter/settings/provenance As-Built | ✅ Implemented |
| `cursor-aware-predictions.md` | Cursor sync + cursor-park next-word integration | ✅ Implemented |
| `architectural-decisions.md` | Architectural Decision Records | ✅ Active |

### `/docs/eval/` Decoder Evaluations (2026-07/08)
*CTC / geometric / FUTO-reference head-to-head evidence base*

| File | Purpose | Status |
|------|---------|--------|
| `2026-07-24-test2400-head2head.md` | Same-split 2,400-row head-to-head (transformer/geo/FUTO floor+ceiling) + fusion go/no-go; held-out VAL (9,918) corroboration; **2026-08-08 addendum: shipped CTC engine (89.31 t1) now tops the table** | ✅ Complete |
| `2026-07-23-futo100k-head2head.md` | FUTO 100k corpus head-to-head | ✅ Complete |
| `2026-08-06-offline-decoder-speedup.md` | Offline ONNX decode speedup investigation — verdict: adopt neither (XNNPACK 0.80× slower; decoder already int8-dynamic-quantized) | ✅ Complete |
| `2026-07-24-harness-conversion-audit.md` | Eval harness conversion fixes | ✅ Complete |
| `2026-07-24-swipedata-onnx-validation.md` | Swipedata → ONNX input validation | ✅ Complete |
| `futo-decoder-eval-notes.md` | FUTO reference decoder porting notes (floor + Viterbi-beam ceiling) | ✅ Complete |

### `/docs/guides/` Guides

| File | Purpose | Status |
|------|---------|--------|
| `train-ctc-swipe-model.md` | End-to-end CTC swipe-model training → ONNX export guide (for a GPU box) | ✅ Active |

### `/docs/audit/` Key Decision Records (2026-08-06)

| File | Purpose | Status |
|------|---------|--------|
| `2026-08-06-futo-engine-integration-decision.md` | FUTO engine adoption decision — .pte→ONNX infeasible; ExecuTorch-in-app costly; **recommended: retrain own CTC model → ONNX** (license-clean) | ✅ Decided |
| `2026-08-06-futo-decoder-integration-study.md` | Algorithm ground truth for the CTC decode port | ✅ Complete |
| `2026-08-06-context-lm-review-findings.md` | Independent review of context-LM/privacy wave (H1-H3, M1-M7, L1-L10 — all resolved) | ✅ Resolved |

### `/docs/history/` History
| File | Purpose | Status |
|------|---------|--------|
| `session_log_dec_2025.md` | December 2025 Work Log | ✅ Archived |
| `neural-engine/` | The removed ONNX transformer engine: specs, decode pipeline, settings docs (ADR-011) | 📚 Archived |
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