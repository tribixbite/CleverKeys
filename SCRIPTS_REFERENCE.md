# 📜 CleverKeys Scripts Reference

Complete guide to all 25 shell scripts in the project.

---

## 🌟 **Recommended User-Facing Scripts** (Use These First!)

These 5 scripts are the **primary tools** for building, testing, and verifying CleverKeys. All have comprehensive `--help` documentation.

| Script | Size | Purpose | Help |
|--------|------|---------|------|
| **build-and-verify.sh** | 13K | 🚀 **Complete automation pipeline**<br>Clean → Compile → Build → Install → Verify<br>**Usage:** `./build-and-verify.sh [--clean] [--skip-verify]` | ✅ `--help` |
| **run-all-checks.sh** | 11K | ⭐ **Master verification suite**<br>Status check → Diagnostics → Guided testing<br>**Usage:** `./run-all-checks.sh`<br>**Best for:** First-time verification | ✅ `--help` |
| **check-keyboard-status.sh** | 6.8K | 📊 **Quick status verification**<br>Checks: APK install, keyboard enable, activation<br>**Usage:** `./check-keyboard-status.sh` | ✅ `--help` |
| **quick-test-guide.sh** | 13K | 🧪 **Interactive 5-test guide**<br>Tests: typing, predictions, swipe, autocorrect, design<br>**Usage:** `./quick-test-guide.sh`<br>**Prerequisite:** Keyboard must be active | ✅ `--help` |
| **diagnose-issues.sh** | 15K | 🔍 **Comprehensive diagnostics**<br>11 diagnostic sections + timestamped report<br>**Usage:** `./diagnose-issues.sh`<br>**Output:** `cleverkeys-diagnostic-YYYYMMDD-HHMMSS.txt` | ✅ `--help` |

### 💡 Quick Start Workflow
```bash
# New users - verify installation
./run-all-checks.sh

# After code changes - rebuild and verify
./build-and-verify.sh --clean

# Quick status check
./check-keyboard-status.sh

# Troubleshooting
./diagnose-issues.sh
```

---

## 🔧 **Build & Installation Scripts** (Alternative/Legacy)

These scripts provide alternative build and installation methods. Most users should use `build-and-verify.sh` instead, but these are useful for specific scenarios.

| Script | Size | Purpose | Status |
|--------|------|---------|--------|
| **build-on-termux.sh** | 10K | Comprehensive Termux ARM64 build script<br>Handles environment setup, prerequisites check<br>**Usage:** `./build-on-termux.sh [debug\|release]`<br>**Alternative to:** `build-and-verify.sh` (without verification) | Active |
| **install.sh** | 4.9K | Multi-method APK installation<br>Methods: termux-open, ADB wireless, manual copy<br>**Usage:** `./install.sh`<br>**Alternative to:** `build-and-verify.sh` (install step only) | Active |
| **adb-install.sh** | 2.6K | ADB wireless installation (specific method)<br>**Usage:** `./adb-install.sh`<br>**Use case:** When ADB is preferred over termux-open | Active |
| **check-install.sh** | 4.0K | Installation verification<br>Checks if APK is installed correctly<br>**Alternative to:** `check-keyboard-status.sh` (simpler) | Legacy |
| **build-install.sh** | 868 | Simple build + install wrapper<br>**Alternative to:** `build-and-verify.sh` (minimal version) | Legacy |

### 📝 Notes:
- **Recommended:** Use `build-and-verify.sh` for complete workflow
- **Legacy scripts:** Kept for compatibility and specific use cases
- **No --help:** These scripts have inline usage comments but no --help flags

---

## 🧪 **Testing & Verification Scripts** (Development/Internal)

These scripts are primarily for development, debugging, and specialized testing scenarios.

### General Testing
| Script | Size | Purpose |
|--------|------|---------|
| **test-keyboard-automated.sh** | 8.5K | Automated keyboard testing<br>Simulates keyboard interactions |
| **test-activities.sh** | 6.8K | Tests specific Android activities<br>Requires TestActivity in APK |
| **test-runtime.sh** | 4.1K | Runtime environment testing |
| **verify_pipeline.sh** | 5.1K | Pipeline verification tests |

### ONNX & Neural Testing
| Script | Size | Purpose |
|--------|------|---------|
| **run_onnx_cli_test.sh** | 2.1K | ONNX command-line interface tests |
| **run_onnx_test_gradle.sh** | 1.2K | ONNX tests via Gradle |
| **test_onnx_accuracy.sh** | 2.8K | ONNX model accuracy verification |
| **test_onnx_simple.sh** | 842 | Simple ONNX functionality test |
| **test_prediction.sh** | 699 | Prediction system testing |
| **test_beam_search.sh** | 587 | Beam search algorithm testing |
| **test_tensor_format.sh** | 3.4K | Tensor format validation |
| **run_decoding_test.sh** | 2.5K | Decoding pipeline tests |

### Unit Test Runners
| Script | Size | Purpose |
|--------|------|---------|
| **run-tests.sh** | 1.3K | Standard unit test runner |
| **run-test.sh** | 1.4K | Alternative test runner |
| **run_test.sh** | 1.2K | Legacy test runner (duplicate name) |

### 📝 Notes:
- **Target audience:** Developers and contributors
- **Not for regular users:** These test internal functionality
- **Some may fail:** Test scripts may have compilation errors (documented in UNIT_TEST_STATUS.md)
- **ONNX tests:** Specifically test neural prediction pipeline

---

## 📊 **Scripts by Category Summary**

```
Total Scripts: 25 (24 unique + 1 duplicate name)

User-Facing (Recommended):  5 scripts (68K) ✅ All have --help
Build & Installation:       5 scripts (23K)
Testing & Verification:    15 scripts (46K)
                          ___________________
                          25 scripts (137K total)
```

---

## 🆘 **Getting Help**

### Scripts with --help Documentation
All 5 user-facing scripts support `--help` for detailed usage:
```bash
./build-and-verify.sh --help
./run-all-checks.sh --help
./check-keyboard-status.sh --help
./quick-test-guide.sh --help
./diagnose-issues.sh --help
```

### Scripts without --help
For other scripts, check inline comments:
```bash
head -20 script-name.sh     # View header comments
```

### Documentation References
- **Build Scripts:** See `BUILD_SCRIPTS.md`
- **Testing:** See `MANUAL_TESTING_GUIDE.md`
- **Installation:** See `INSTALLATION_STATUS.md`
- **All Scripts:** This file (`SCRIPTS_REFERENCE.md`)

---

## 🎯 **Which Script Should I Use?**

### 🆕 **New User / First Time**
```bash
./run-all-checks.sh    # Complete verification suite
```

### 🔄 **After Code Changes**
```bash
./build-and-verify.sh --clean    # Rebuild from scratch
```

### ✅ **Quick Check**
```bash
./check-keyboard-status.sh       # Is it installed and enabled?
```

### 🐛 **Troubleshooting**
```bash
./diagnose-issues.sh            # Generate diagnostic report
```

### 🧪 **Testing Specific Features**
```bash
./quick-test-guide.sh           # Interactive 5-test guide
```

### 🔧 **Advanced / Development**
```bash
./build-on-termux.sh            # Lower-level build control
./test-keyboard-automated.sh    # Automated testing
./test_onnx_accuracy.sh         # Neural prediction testing
```

---

## 📌 **Script Evolution History**

**Part 6 (Nov 14, 2025):** Created comprehensive user-facing automation infrastructure
- `build-and-verify.sh` - Complete pipeline automation
- `run-all-checks.sh` - Master verification suite
- `check-keyboard-status.sh` - Quick status checks
- `quick-test-guide.sh` - Interactive testing guide
- `diagnose-issues.sh` - Diagnostic tooling
- Added `--help` flags to all 5 new scripts

**Pre-Part 6:** Legacy build and test scripts
- `build-on-termux.sh` - Original Termux build script
- `install.sh` - Original installation script
- Various `test_*.sh` - ONNX and unit testing scripts

**Recommendation:** New users should start with Part 6 scripts (the 5 with --help flags). Legacy scripts are maintained for compatibility and specific use cases.

---

## 🔗 **Related Documentation**

- **Getting Started:** `00_START_HERE_FIRST.md`
- **All Documentation:** `INDEX.md`
- **Build System:** `BUILD_SCRIPTS.md`
- **Testing Guide:** `MANUAL_TESTING_GUIDE.md`
- **Troubleshooting:** `INSTALLATION_STATUS.md`

---

**Last Updated:** 2025-11-14
**Total Scripts:** 25
**Scripts with --help:** 5
**Recommended Scripts:** 5 (user-facing)
