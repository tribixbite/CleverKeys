# CRITICAL DISCOVERY - November 21, 2025, 11:00 UTC

## 🚨 ROOT CAUSE IDENTIFIED: SYSTEMIC APK ISSUE

**Status:** CRITICAL - Problem is NOT in CleverKeysService code

---

## Discovery Process

### 1. Initial Investigation: Missing Dependencies?
Checked if AndroidX libraries were missing from APK:
```bash
unzip -l *.apk | grep "androidx/lifecycle"  # No results
unzip -l *.apk | grep "androidx/savedstate"  # No results
unzip -l *.apk | grep "kotlinx/coroutines"  # No results
```

**Initial Conclusion:** Dependencies appear missing from APK!

**BUT:** This was a false alarm - grep doesn't work on DEX files inside APK. Classes ARE compiled into the 10 DEX files.

### 2. Testing Minimal Service
Created **MinimalTestService.kt** - the SIMPLEST possible InputMethodService:
- Zero dependencies
- Zero interfaces
- Zero complex initialization
- Just logging in onCreate()

```kotlin
class MinimalTestService : InputMethodService() {
    override fun onCreate() {
        super.onCreate()
        Log.d("MinimalTest", "✅ onCreate() SUCCESS!")
    }
}
```

### 3. Build and Install
```bash
./gradlew assembleDebug  # ✅ SUCCESS
adb install -r *.apk     # ✅ SUCCESS
adb shell ime enable tribixbite.keyboard2.debug/tribixbite.keyboard2.MinimalTestService  # ✅ SUCCESS
adb shell ime set tribixbite.keyboard2.debug/tribixbite.keyboard2.MinimalTestService     # ✅ SUCCESS
```

### 4. Test Results
Triggered keyboard by opening messaging app and tapping text field.

**Result:** NO LOGS APPEARED. MinimalTestService onCreate() was NEVER called.

---

## Critical Finding

**BOTH services fail to start:**
- ❌ CleverKeysService (4000+ lines, complex initialization)
- ❌ MinimalTestService (20 lines, zero dependencies)

**This proves:**
1. The problem is NOT in CleverKeysService code
2. The problem is NOT with lazy initialization
3. The problem is NOT with "leaking this"
4. The problem is NOT with onCreate() complexity

**The problem is SYSTEMIC** - something in the build process or APK structure prevents ANY InputMethodService from starting.

---

## Likely Root Causes

### 1. Application ID Suffix Issue (.debug)
**File:** build.gradle line 170
```gradle
debug {
    applicationIdSuffix ".debug"
}
```

**Theory:** The `.debug` suffix might be causing InputMethodManagerService to fail binding the service.

**Test:** Build without suffix and see if services start.

### 2. MultiDex Configuration Issue
**File:** build.gradle line 95
```gradle
multiDexEnabled true
```

With 10 DEX files, MultiDex is active. If not properly initialized, classes might not load.

**Theory:** InputMethodService classes might be in a secondary DEX that's not being loaded.

**Test:** Check if MultiDex.install() is called (should be automatic with androidx.multidex).

### 3. ProGuard/R8 Stripping Service Metadata
Even with `minifyEnabled false`, some R8 optimizations still run.

**Theory:** Service metadata or required classes are being stripped.

**Test:** Add explicit keep rules for InputMethodService subclasses.

### 4. AndroidManifest Service Declaration Issue
**Current declaration:**
```xml
<service android:name="tribixbite.keyboard2.MinimalTestService"
         android:permission="android.permission.BIND_INPUT_METHOD"
         android:exported="true"
         android:directBootAware="true">
```

**Theory:** `android:directBootAware="true"` might require additional setup that's missing.

**Test:** Remove directBootAware and test.

### 5. Missing Dependencies at APK Level
**File:** build.gradle lines 6-45

All dependencies are declared, but might not be included in APK due to:
- Transitive dependency resolution issues
- Scope issues (should be `implementation`, not `api` or `compileOnly`)

**Test:** Check build logs for dependency resolution warnings.

---

## Next Debugging Steps

### Priority 1: Build Without Application ID Suffix
```gradle
debug {
    applicationIdSuffix ""  // Remove .debug
    // ... rest of config
}
```

Test if service starts with `tribixbite.keyboard2` instead of `tribixbite.keyboard2.debug`.

### Priority 2: Remove directBootAware
```xml
<service android:name="tribixbite.keyboard2.MinimalTestService"
         android:permission="android.permission.BIND_INPUT_METHOD"
         android:exported="true">
    <!-- REMOVED: android:directBootAware="true" -->
```

### Priority 3: Add Explicit ProGuard Keep Rules
Create/update `proguard-rules.pro`:
```proguard
# Keep all InputMethodService subclasses
-keep class * extends android.inputmethodservice.InputMethodService { *; }

# Keep AndroidX
-keep class androidx.** { *; }
-dontwarn androidx.**

# Keep Kotlin coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
```

### Priority 4: Check MultiDex Initialization
Verify MultiDex is properly initialized. If using `androidx.multidex:multidex:2.0.1`, it should be automatic. But create an Application class to be sure:

```kotlin
package tribixbite.keyboard2

import androidx.multidex.MultiDexApplication

class CleverKeysApplication : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        Log.d("CleverKeys", "Application onCreate() - MultiDex initialized")
    }
}
```

Add to AndroidManifest.xml:
```xml
<application android:name=".CleverKeysApplication" ...>
```

### Priority 5: Enable Verbose Class Loading
```bash
adb shell setprop log.tag.dalvikvm VERBOSE
adb shell setprop log.tag.art VERBOSE
adb logcat -d | grep -i "art\|dalvik\|tribixbite"
```

This will show exactly what classes fail to load and why.

---

## What We've Fixed (But Doesn't Solve the Problem)

1. ✅ Lazy initialization of lifecycleRegistry
2. ✅ Lazy initialization of savedStateRegistryController
3. ✅ Lazy initialization of serviceScope
4. ✅ Reduced onCreate() to minimal (just logging)
5. ✅ Created ultra-minimal test service with zero dependencies

**All fixes were correct, but don't solve the underlying APK issue.**

---

## Files Created/Modified This Session

### New Files:
- `src/main/kotlin/tribixbite/keyboard2/MinimalTestService.kt` - Test service

### Modified Files:
- `AndroidManifest.xml` - Added MinimalTestService declaration
- `src/main/kotlin/tribixbite/keyboard2/CleverKeysService.kt` - Lazy initialization fixes (from previous session)

### Documentation:
- `LAZY_INIT_FIX_NOV_21.md` - Lazy initialization details
- `CRASH_INVESTIGATION_STATUS.md` - Investigation theories
- `FINAL_SESSION_SUMMARY_NOV_21.md` - Previous session summary
- `CRITICAL_DISCOVERY_NOV_21_1100.md` - This document

---

## Recommended Immediate Action

**Test with application ID suffix removed:**
1. Edit build.gradle - remove `.debug` suffix
2. Clean build: `./gradlew clean`
3. Build: `./gradlew assembleDebug`
4. Install: `adb install -r *.apk`
5. Enable: `adb shell ime enable tribixbite.keyboard2/tribixbite.keyboard2.MinimalTestService`
6. Set: `adb shell ime set tribixbite.keyboard2/tribixbite.keyboard2.MinimalTestService`
7. Test: Open text field and check logs

**If this works**, the problem was the applicationIdSuffix all along.

**If this doesn't work**, proceed to Priority 2 (remove directBootAware).

---

## Timeline

**Session Start:** Nov 21, 2025 05:00 UTC (from previous session)
**Critical Discovery:** Nov 21, 2025 11:00 UTC (this session)
**Total Time:** ~6 hours of investigation across 2 sessions
**Status:** Root cause narrowed to APK configuration issue

---

## Confidence Level

**HIGH** that the problem is NOT in CleverKeysService code.
**MEDIUM** that the problem is applicationIdSuffix or directBootAware.
**LOW** that the problem is more complex (MultiDex, ProGuard, dependencies).

---

**Next Session:** Remove applicationIdSuffix and test. If that fails, systematically test each theory.
