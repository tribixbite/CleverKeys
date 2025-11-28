# CleverKeys Crash Debugging Checklist

**Issue:** Keyboard crashes on load
**Status:** Device currently offline - waiting to debug

---

## Step 1: Get Detailed Crash Logs

When device reconnects, run these commands:

```bash
# Clear old logs
adb logcat -c

# Set CleverKeys as default
adb shell ime set tribixbite.keyboard2.debug/tribixbite.keyboard2.CleverKeysService

# Open an app with text input to trigger keyboard
adb shell am start -a android.intent.action.VIEW -d "https://www.google.com"
adb shell input tap 360 600

# Capture crash logs immediately
adb logcat -d > cleverkeys_crash.log

# Or filter for relevant entries
adb logcat -d | grep -E "CleverKeys|tribixbite|AndroidRuntime|FATAL" > crash_filtered.log
```

---

## Step 2: Common Crash Causes to Check

### 1. Missing/Incorrect Manifest Entries
**File:** `android/app/src/main/AndroidManifest.xml`

Check for:
- CleverKeysService declared with correct intent filters
- BIND_INPUT_METHOD permission
- Metadata for input method settings

### 2. Missing Layout Resources
**Possible Issues:**
- Layout XML files not found
- Missing drawable resources
- Incorrect resource references

**Check:**
```bash
ls -la android/app/src/main/res/xml/
ls -la android/app/src/main/res/layout/
```

### 3. Kotlin Initialization Issues
**Common causes:**
- Null pointer exceptions in onCreate()
- Missing dependency injection setup
- Uninitialized lateinit variables

### 4. ONNX Model Loading
**Possible Issues:**
- Model file not found in assets
- Incorrect model path
- ONNX runtime initialization failure

**Check:**
```bash
ls -la android/app/src/main/assets/
```

### 5. Permission Issues
**Missing permissions:**
- INTERNET (if needed)
- READ_EXTERNAL_STORAGE (for clipboard)
- Other runtime permissions

---

## Step 3: Typical Stack Trace Patterns

### Pattern 1: NullPointerException
```
FATAL EXCEPTION: main
java.lang.NullPointerException: Attempt to invoke virtual method '...' on a null object reference
    at tribixbite.keyboard2.CleverKeysService.onCreate(...)
```
**Fix:** Initialize object before use

### Pattern 2: ResourceNotFoundException
```
android.content.res.Resources$NotFoundException: Resource ID #0x...
    at tribixbite.keyboard2.CleverKeysService.onCreateInputView(...)
```
**Fix:** Check layout resource exists and R.id references are correct

### Pattern 3: ClassNotFoundException
```
java.lang.ClassNotFoundException: Didn't find class "tribixbite.keyboard2.CleverKeysService"
```
**Fix:** Check proguard rules, ensure class not obfuscated

### Pattern 4: IllegalStateException
```
java.lang.IllegalStateException: lateinit property ... has not been initialized
```
**Fix:** Initialize lateinit vars in onCreate or use lazy delegation

---

## Step 4: Quick Fixes to Try

### Fix 1: Simplify Service Initialization
Create minimal CleverKeysService that only inflates a simple view:

```kotlin
class CleverKeysService : InputMethodService() {
    override fun onCreateInputView(): View {
        return TextView(this).apply {
            text = "CleverKeys Test"
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.WHITE)
        }
    }
}
```

### Fix 2: Add Crash Logging
```kotlin
override fun onCreate() {
    try {
        Log.d("CleverKeys", "Service onCreate started")
        super.onCreate()
        // initialization code
        Log.d("CleverKeys", "Service onCreate completed")
    } catch (e: Exception) {
        Log.e("CleverKeys", "Crash in onCreate", e)
        throw e
    }
}
```

### Fix 3: Check Build Configuration
```bash
# Verify APK contains our classes
unzip -l android/app/build/outputs/apk/debug/app-debug.apk | grep CleverKeys

# Check for missing resources
unzip -l android/app/build/outputs/apk/debug/app-debug.apk | grep "res/xml\|res/layout"
```

---

## Step 5: Debugging Commands Reference

```bash
# Check which IME is active
adb shell settings get secure default_input_method

# List all available IMEs
adb shell ime list -s

# Enable our IME
adb shell ime enable tribixbite.keyboard2.debug/tribixbite.keyboard2.CleverKeysService

# Set as default
adb shell ime set tribixbite.keyboard2.debug/tribixbite.keyboard2.CleverKeysService

# Check if service is running
adb shell ps | grep tribixbite

# Get detailed system info
adb shell dumpsys input_method

# Watch logs in real-time
adb logcat -s CleverKeys:V AndroidRuntime:E
```

---

## Step 6: Verify Fix

After implementing fix:

1. **Rebuild:**
   ```bash
   ./build-and-install.sh clean
   ```

2. **Clear logs:**
   ```bash
   adb logcat -c
   ```

3. **Install and set as default:**
   ```bash
   adb install -r android/app/build/outputs/apk/debug/app-debug.apk
   adb shell ime set tribixbite.keyboard2.debug/tribixbite.keyboard2.CleverKeysService
   ```

4. **Trigger keyboard:**
   ```bash
   adb shell am start -a android.intent.action.SENDTO -d sms:1234567890
   adb shell input tap 360 1300
   ```

5. **Check logs:**
   ```bash
   adb logcat -d | grep -i "cleverkeys\|fatal"
   ```

6. **Success criteria:**
   - No FATAL exceptions
   - Service onCreate completes
   - onCreateInputView returns successfully
   - Keyboard view displays on screen

---

## Expected Success Logs

```
D CleverKeys: Service onCreate started
D CleverKeys: Initializing keyboard configuration
D CleverKeys: Loading ONNX model
D CleverKeys: Service onCreate completed
D CleverKeys: onCreateInputView called
D CleverKeys: Keyboard view created successfully
I InputMethodManagerService: Showing input for client ...
```

---

## Next Steps

1. Wait for device to reconnect
2. Capture crash logs with commands above
3. Identify crash location from stack trace
4. Apply appropriate fix from common causes
5. Test fix thoroughly
6. Document solution in commit message

**Remember:** User is currently using old keyboard (juloo.keyboard2) so they can continue working while we debug.
