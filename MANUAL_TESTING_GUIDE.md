# Manual Testing Guide - CleverKeys Service Crash Fix

## 🚨 CRITICAL TEST - Application ID Suffix Theory

**APK Location:** `/storage/emulated/0/Download/CleverKeys_TEST_NO_DEBUG_SUFFIX.apk`
**Size:** 51MB
**Package Name:** `tribixbite.keyboard2` (NO `.debug` suffix)
**Build Date:** November 21, 2025

---

## What Changed

Removed `.debug` suffix from package name:
- **Old:** tribixbite.keyboard2.debug
- **New:** tribixbite.keyboard2

InputMethodManagerService may fail to bind services with package name suffixes.

---

## Quick Test (5 Minutes)

### 1. Uninstall Old Version
Settings → Apps → CleverKeys (Debug) → Uninstall

### 2. Install New Version
Files → Downloads → CleverKeys_TEST_NO_DEBUG_SUFFIX.apk → Install

### 3. Enable Minimal Test Keyboard
Settings → System → Languages & Input → On-screen keyboard → Manage keyboards
→ Enable "Minimal Test Keyboard" → Set as default

### 4. Test
Open messaging app → Tap text field → CHECK: Does keyboard appear?

---

## Expected Results

### ✅ SUCCESS: Keyboard appears (even if blank)
- The .debug suffix WAS the problem!
- Switch to "CleverKeys Neural Keyboard" and test
- If that works too, problem SOLVED! 🎉

### ❌ FAILURE: No keyboard appears
- The .debug suffix was NOT the problem
- Need to test next theory

---

## What To Report

1. Did MinimalTestService keyboard appear? (YES/NO)
2. Did CleverKeys keyboard appear? (YES/NO)
3. Any screenshots of keyboard selection or keyboard visible

---

**This is our best theory - test ASAP!**

If successful, we've solved an 8-hour debugging mystery! 🎉
