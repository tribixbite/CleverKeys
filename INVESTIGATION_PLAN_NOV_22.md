# Investigation Plan - Keyboard Not Rendering Issue
## Nov 22, 2025 - 02:30 AM

### CURRENT STATUS

**Reality**: Keyboard service initializes but view NEVER renders
**Evidence**: `inputType=0` in logs indicates Android doesn't recognize text input

### WHAT WE KNOW WORKS

✅ Service starts (`onCreate()` called)
✅ Configuration loads
✅ `onStartInput()` fires when text field focused
✅ Code compiles (183 Kotlin files, zero errors)
✅ APK builds (52MB)
✅ All 4 lifecycle methods added (from systematic comparison)

### WHAT DOESN'T WORK

❌ `onCreateInputView()` NEVER called
❌ Keyboard view NEVER rendered
❌ `inputType=0` in logs (means NO INPUT TYPE)

### ROOT CAUSE HYPOTHESIS

**Primary Theory**: Android doesn't recognize focused fields as requiring keyboard input

**Evidence**:
```
Input started: package=com.microsoft.emmx, restarting=false
  inputType=0, imeOptions=301989888  ← inputType=0 = NO INPUT!
```

### INVESTIGATION STEPS

#### Step 1: Test Simple Apps (NOT browser)
**Goal**: Determine if `inputType=0` is browser-specific or systemic

**Test Apps**:
1. ~~Messaging app~~ (couldn't find standard messaging package)
2. Settings search field ← **TESTING NOW**
3. Notes app
4. Any app with basic EditText

**Expected**: If `inputType=0` persists across all apps → Android manifest issue
**Expected**: If `inputType!=0` in other apps → Browser compatibility issue

#### Step 2: Check AndroidManifest.xml
**Goal**: Verify IME configuration is correct

**Check**:
- [ ] `<service>` declaration with correct intent filter
- [ ] `android:permission="android.permission.BIND_INPUT_METHOD"`
- [ ] `<meta-data android:name="android.view.im" android:resource="@xml/method"/>`
- [ ] Input method capabilities declared

**Files to review**:
- `src/main/AndroidManifest.xml`
- `src/main/res/xml/method.xml` (IME metadata)

#### Step 3: Compare with Original Implementation
**Goal**: Find what original Unexpected-Keyboard does differently

**Compare**:
- [ ] AndroidManifest.xml declarations (Java vs Kotlin)
- [ ] res/xml/method.xml configuration
- [ ] InputMethodService subclass declaration
- [ ] View inflation approach

#### Step 4: Check Input View Creation
**Goal**: Verify `onCreateInputView()` implementation

**Check**:
- [ ] Does method exist in CleverKeysService.kt?
- [ ] Does it return non-null View?
- [ ] Is view properly inflated?
- [ ] Are there any exceptions during view creation?

#### Step 5: Logcat Deep Dive
**Goal**: Find Android framework errors we're missing

**Search for**:
- InputMethodService framework errors
- View inflation errors
- Permission errors
- Configuration errors

**Command**:
```bash
adb logcat -d | grep -E "InputMethod|IME|keyboard|tribixbite" | head -200
```

### WHAT NOT TO DO

❌ Don't add more lifecycle methods without evidence they're missing
❌ Don't claim "keyboard works" based on service initialization
❌ Don't test complex features before basic rendering works
❌ Don't reboot device (explicit user directive)

### SUCCESS CRITERIA

**Minimum** (to consider this issue debugged):
1. Identify WHY `inputType=0` occurs
2. Identify WHY `onCreateInputView()` never called
3. Have concrete next step (not speculation)

**Ideal** (to consider this issue FIXED):
1. `inputType` != 0 in logs
2. `onCreateInputView()` called successfully
3. Keyboard view visible in text fields
4. Screenshot proof of keyboard rendering

### NEXT ACTIONS

1. **Test in Settings search** (simple EditText, not browser)
   - If same issue → manifest/IME registration problem
   - If works → browser-specific issue

2. **Review AndroidManifest.xml** against original
   - Compare service declarations
   - Compare permissions
   - Compare metadata

3. **Check res/xml/method.xml**
   - Verify input types supported
   - Verify subtypes declared
   - Compare with original implementation

4. **Deep logcat analysis**
   - Look for framework errors
   - Search for permission denials
   - Find view inflation failures

### REALITY CHECK

**This is NOT a lifecycle method issue** (we've added all missing methods)
**This is NOT a code quality issue** (compiles successfully)
**This is likely a CONFIGURATION or MANIFEST issue** (Android doesn't activate IME properly)

---

**Created**: Nov 22, 2025 - 02:30 AM
**Status**: Investigation in progress
**Current Step**: Testing in Settings search field
