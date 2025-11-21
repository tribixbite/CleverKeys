# Testing Required - November 21, 2025

## Status: READY FOR TESTING (Device Currently Offline)

**APK Built:** `build/outputs/apk/debug/tribixbite.keyboard2.apk` (51MB)
**Change:** Removed applicationIdSuffix - now `tribixbite.keyboard2` instead of `tribixbite.keyboard2.debug`

---

## What Was Discovered

### Critical Finding:
**BOTH services fail to instantiate:**
- CleverKeysService (complex, 4000+ lines)
- MinimalTestService (simple, 20 lines, zero dependencies)

**This proves the problem is NOT in the code** - it's a systemic APK/build configuration issue.

### Theory Being Tested:
The `application IdSuffix ".debug"` might be preventing InputMethodManagerService from properly binding services.

---

## Testing Instructions (When Device Reconnects)

### Step 1: Reconnect ADB
```bash
adb connect 192.168.1.247:36589
# OR wait for device to reconnect automatically
```

### Step 2: Uninstall Old Version
```bash
# Remove the .debug version
adb uninstall tribixbite.keyboard2.debug

# Verify it's gone
adb shell pm list packages | grep tribixbite
```

### Step 3: Install New Version
```bash
cd /data/data/com.termux/files/home/git/swype/cleverkeys
adb install build/outputs/apk/debug/tribixbite.keyboard2.apk
```

### Step 4: Enable MinimalTestService
```bash
# Enable the minimal test service
adb shell ime enable tribixbite.keyboard2/tribixbite.keyboard2.MinimalTestService

# Set it as active
adb shell ime set tribixbite.keyboard2/tribixbite.keyboard2.MinimalTestService

# Verify it's set
adb shell ime list -s | grep tribixbite
```

### Step 5: Test and Capture Logs
```bash
# Clear logs
adb logcat -c

# Open messaging app and tap text field
adb shell am start -a android.intent.action.SENDTO -d sms:1234567890
sleep 2
adb shell input tap 360 1300
sleep 2

# Check logs
adb logcat -d | grep -i "minimaltest\|cleverkeys\|tribixbite" | tail -50
```

---

## Expected Outcomes

### If MinimalTestService Works (applicationIdSuffix was the problem):
```
D MinimalTest: ✅ MinimalTestService onCreate() SUCCESS!
D MinimalTest: ✅ onCreateInputView() called
```

**Next Steps:**
1. Test CleverKeysService:
   ```bash
   adb shell ime set tribixbite.keyboard2/tribixbite.keyboard2.CleverKeysService
   # Tap text field again and check logs
   ```

2. If CleverKeysService ALSO works, then:
   - **ROOT CAUSE:** applicationIdSuffix was breaking service binding
   - **FIX:** Remove applicationIdSuffix permanently OR find why it breaks
   - All lazy initialization fixes are still correct and needed

3. If CleverKeysService still fails but MinimalTestService works:
   - Problem is in CleverKeysService code (but we narrowed it down)
   - Use binary search: add features back to MinimalTestService one by one
   - Find exactly which code causes the crash

### If MinimalTestService Still Fails (applicationIdSuffix was NOT the problem):
```
(No logs appear at all)
```

**Next Steps:**
1. Test Priority 2: Remove directBootAware flag
   - Edit AndroidManifest.xml
   - Remove `android:directBootAware="true"` from both services
   - Rebuild and test

2. If that fails, Priority 3: Add ProGuard keep rules
   - Create `proguard-rules.pro` with InputMethodService keep rules
   - Rebuild and test

3. If that fails, Priority 4: Check MultiDex initialization
   - Create CleverKeysApplication class
   - Add to AndroidManifest
   - Rebuild and test

4. If that fails, Priority 5: Enable verbose class loading
   ```bash
   adb shell setprop log.tag.dalvikvm VERBOSE
   adb shell setprop log.tag.art VERBOSE
   ```

---

## Files Modified This Session

1. `src/main/kotlin/tribixbite/keyboard2/MinimalTestService.kt` - NEW
   - Ultra-minimal test service with zero dependencies

2. `AndroidManifest.xml` - MODIFIED
   - Added MinimalTestService declaration
   - Placed BEFORE CleverKeysService so it appears first in IME list

3. `build.gradle` - MODIFIED
   - Commented out `applicationIdSuffix ".debug"`

4. `src/main/kotlin/tribixbite/keyboard2/CleverKeysService.kt` - FROM PREVIOUS SESSION
   - Lazy initialization fixes (lifecycleRegistry, savedStateRegistryController, serviceScope)
   - Ultra-minimal onCreate() (just logging)

---

## Git Status

```bash
git status
# Modified: AndroidManifest.xml, build.gradle, CleverKeysService.kt
# New:      MinimalTestService.kt, CRITICAL_DISCOVERY_NOV_21_1100.md, TESTING_REQUIRED_NOV_21.md
```

**Latest Commit:** `test: create MinimalTestService to isolate crash - both services fail`

**Changes NOT YET COMMITTED:**
- build.gradle (removed applicationIdSuffix)

**Pending Commit Message:**
```
test: remove applicationIdSuffix to test if it causes crash

- Removed '.debug' suffix from debug build
- APK now tribixbite.keyboard2 instead of tribixbite.keyboard2.debug
- Testing if applicationIdSuffix breaks InputMethodService binding
- Waiting for device reconnect to test
```

---

## Quick Test Command (Copy-Paste Ready)

Once device is connected:
```bash
adb uninstall tribixbite.keyboard2.debug
adb install build/outputs/apk/debug/tribixbite.keyboard2.apk
adb shell ime enable tribixbite.keyboard2/tribixbite.keyboard2.MinimalTestService
adb shell ime set tribixbite.keyboard2/tribixbite.keyboard2.MinimalTestService
adb logcat -c
adb shell am start -a android.intent.action.SENDTO -d sms:123 && sleep 2 && adb shell input tap 360 1300 && sleep 2
adb logcat -d | grep -i "minimaltest\|cleverkeys" | tail -30
```

**Look for:** `D MinimalTest: ✅ MinimalTestService onCreate() SUCCESS!`

---

## Investigation Timeline

**Nov 21, 05:00 UTC** - Previous session: Fixed lazy initialization issues
**Nov 21, 10:00 UTC** - This session: Created MinimalTestService
**Nov 21, 11:00 UTC** - Critical discovery: Both services fail (systemic issue)
**Nov 21, 11:30 UTC** - Built APK without applicationIdSuffix
**Nov 21, 11:40 UTC** - Device offline, waiting for reconnect

**Total Investigation Time:** ~6.5 hours across 2 sessions

---

## Confidence Assessment

**HIGH (90%):** Problem is applicationIdSuffix or directBootAware
**MEDIUM (60%):** applicationIdSuffix specifically is the culprit
**LOW (20%):** Problem is more complex (ProGuard, MultiDex, dependencies)

---

**Status:** BLOCKED - Waiting for device to reconnect for testing
**Next Action:** Run test commands above when device is available
**Fallback:** If no device access, revert applicationIdSuffix change and try next priority
