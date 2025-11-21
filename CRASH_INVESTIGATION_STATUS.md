# CleverKeys Crash Investigation Status - November 21, 2025

## Current Status: BLOCKED - Service Won't Instantiate

**Problem:** CleverKeys service is recognized by Android but never instantiates. onCreate() is never called.

---

## Fixes Implemented So Far

### 1. Minimal onCreate() (130 → 2 → 0 init calls) ✅
- Stripped all initialization from onCreate()
- Currently only logs that service started
- **Result:** Compiles, but service still won't start

### 2. Lazy Lifecycle Components ✅
**File:** CleverKeysService.kt:101-102
```kotlin
// BEFORE (BROKEN):
private val lifecycleRegistry = LifecycleRegistry(this)
private val savedStateRegistryController = SavedStateRegistryController.create(this)

// AFTER (FIXED):
private val lifecycleRegistry by lazy { LifecycleRegistry(this) }
private val savedStateRegistryController by lazy { SavedStateRegistryController.create(this) }
```
**Reason:** "Leaking this" before object fully constructed

### 3. Lazy ServiceScope ✅
**File:** CleverKeysService.kt:112-118
```kotlin
// BEFORE (BROKEN):
private val serviceScope = CoroutineScope(
    SupervisorJob() +
    Dispatchers.Main.immediate +
    CoroutineName("CleverKeysService")
)

// AFTER (FIXED):
private val serviceScope by lazy {
    CoroutineScope(
        SupervisorJob() +
        Dispatchers.Main.immediate +
        CoroutineName("CleverKeysService")
    )
}
```
**Reason:** Dispatchers.Main.immediate requires Android framework to be initialized

---

## Symptoms

1. **InputMethodManagerService recognizes CleverKeys:**
   ```
   V InputMethodManagerService: Checking tribixbite.keyboard2.debug/tribixbite.keyboard2.CleverKeysService
   V InputMethodManagerService: Found an input method InputMethodInfo{tribixbite.keyboard2.debug/tribixbite.keyboard2.CleverKeysService...}
   ```

2. **No onCreate() logs appear:**
   - Expected: `D CleverKeys: 🔧 ULTRA-MINIMAL MODE - Testing if service can start at all`
   - Actual: No logs at all

3. **Old keyboard fallback:**
   - System falls back to juloo.keyboard2.debug
   - User sees old keyboard instead of CleverKeys

4. **No crash logs:**
   - No AndroidRuntime exceptions in logcat
   - No stack traces
   - Silent failure

---

## Remaining Suspects

### Class-Level Field Initializations

**Location:** CleverKeysService.kt lines 117-250

There are 133 nullable field declarations:
```kotlin
private var keyboardView: Keyboard2View? = null
private var neuralEngine: NeuralSwipeEngine? = null
private var predictionService: SwipePredictionService? = null
...
```

**Analysis:** These should be safe (all nullable, all null), but may need verification.

### Companion Object

**Location:** CleverKeysService.kt lines 54-80

```kotlin
companion object {
    private const val TAG = "CleverKeys"

    private val TERMINAL_PACKAGES = setOf(
        "com.termux",
        "com.termux.tasker",
        ...
    )

    fun isTerminalApp(packageName: String?): Boolean {
        ...
    }
}
```

**Analysis:** Simple constants and pure function, should be safe.

### Inner Classes

**AutoTerminalModePrefs (lines 86-97):**
```kotlin
private class AutoTerminalModePrefs(
    private val delegate: SharedPreferences,
    private val termuxModeOverride: Boolean
) : SharedPreferences by delegate {
    ...
}
```

**Analysis:** Only instantiated when needed, not at class load time.

### Missing Dependencies

Possible causes:
1. **Missing native libraries** - ONNX models or JNI libraries
2. **Missing AndroidX dependencies** - Lifecycle, SavedState, Compose
3. **Proguard stripping required classes**
4. **Missing permissions** - Though service is recognized by system

---

## Debugging Attempts

### 1. Ultra-Minimal onCreate()
**Result:** Service still won't start
**Conclusion:** Crash is BEFORE onCreate()

### 2. Lazy Initialization of Framework Components
**Result:** Service still won't start
**Conclusion:** More issues remain

### 3. Verbose IME Logging
**Result:** System recognizes service but doesn't instantiate it
**Conclusion:** Crash during class loading or constructor

### 4. Error Log Analysis
**Result:** No error logs found
**Conclusion:** Crash is silent (possibly caught and suppressed by system)

---

## Next Steps to Try

### Option 1: Check for Missing Dependencies
```bash
# List all classes in APK
unzip -l build/outputs/apk/debug/tribixbite.keyboard2.debug.apk | grep "classes.*dex"

# Check for androidx classes
# Check for kotlinx.coroutines classes
```

### Option 2: Simplify Class Further
Remove ALL field declarations:
```kotlin
class CleverKeysService : InputMethodService() {
    override fun onCreate() {
        super.onCreate()
        Log.d("CleverKeys", "SUCCESS!")
    }
}
```

### Option 3: Test with Different Base Class
Try without the interfaces:
```kotlin
class CleverKeysService : InputMethodService() {
    // Remove: SharedPreferences.OnSharedPreferenceChangeListener
    // Remove: ClipboardPasteCallback
    // Remove: LifecycleOwner
    // Remove: SavedStateRegistryOwner
}
```

### Option 4: Check AndroidManifest.xml
Verify service declaration:
```xml
<service
    android:name=".CleverKeysService"
    android:permission="android.permission.BIND_INPUT_METHOD"
    ...>
</service>
```

### Option 5: Test Standalone Service
Create a minimal test service in same package:
```kotlin
package tribixbite.keyboard2

class TestService : InputMethodService() {
    override fun onCreate() {
        super.onCreate()
        Log.d("TestService", "I work!")
    }
}
```

---

## Theories

### Theory 1: Interface Implementation Problem
The service implements 4 interfaces:
- `InputMethodService` (base)
- `SharedPreferences.OnSharedPreferenceChangeListener`
- `ClipboardPasteCallback`
- `LifecycleOwner`
- `SavedStateRegistryOwner`

**Likelihood:** MEDIUM
**Test:** Remove all but InputMethodService

### Theory 2: Kotlin Coroutines Not Initialized
Even with lazy serviceScope, Kotlin coroutines runtime might not be available.

**Likelihood:** LOW
**Reason:** Other Kotlin code would also fail

### Theory 3: Missing Android X Dependencies
Lifecycle and SavedState libraries might not be properly included.

**Likelihood:** HIGH
**Test:** Check build.gradle dependencies, verify classes in APK

### Theory 4: Proguard/R8 Removing Required Code
Build minification might be stripping needed classes.

**Likelihood:** MEDIUM
**Test:** Check proguard-rules.pro, build without minification

### Theory 5: Class Name Conflict
Another class with same name might exist.

**Likelihood:** LOW
**Reason:** Would cause compile error

---

## Build Status

- ✅ Compiles successfully (25s, zero errors)
- ✅ APK builds (53MB)
- ✅ APK installs successfully
- ✅ System recognizes IME
- ❌ Service never instantiates
- ❌ onCreate() never called

---

## Git Commits

1. `a0c0d426` - Critical crash documentation
2. `152653e7` - Comprehensive crash analysis (130+ init problem)
3. `9176d043` - Created minimal onCreate()
4. `4b15cc33` - Success documentation
5. `ed63f0bb` - Installation script
6. `7d527e2b` - Lazy initialize lifecycle components
7. `9fe42196` - Ultra-minimal onCreate() test
8. `86b73f9e` - Lazy initialize serviceScope

---

## Recommendation

**Immediate action:** Test with a completely stripped-down service class (no interfaces, no fields, no nothing) to isolate the problem.

If that works, add back features one at a time:
1. Base InputMethodService only
2. Add interface implementations one by one
3. Add field declarations in groups
4. Add companion object
5. Test after each addition

This binary search approach will identify exactly what's causing the crash.

---

**Status:** Investigation ongoing
**Blocker:** Cannot get service to instantiate
**Priority:** CRITICAL - Keyboard completely non-functional
**Time Spent:** ~4 hours
**Next Session:** Continue with stripped-down service testing
