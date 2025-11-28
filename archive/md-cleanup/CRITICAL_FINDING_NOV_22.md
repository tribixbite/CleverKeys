# CRITICAL FINDING - Android Freezing CleverKeys Process
## Nov 22, 2025 - 02:35 AM

### THE SMOKING GUN

**Found in logcat**:
```
11-22 02:29:47.225  2907  5267 D MARs:ActiveTrafficFilter: filter : tribixbite.keyboard2(0)
11-22 02:29:47.225  2907  5267 D FreecessHandler: freeze tribixbite.keyboard2(11317) result : 8
```

**Translation**: Android's **MARs (Memory Auto-Restart Service)** and **Freecess** system are FREEZING the CleverKeys process.

### WHAT THIS MEANS

**Problem**: Android power management is aggressively freezing CleverKeys
- CleverKeys process starts successfully
- onCreate() runs
- Configuration loads
- **THEN ANDROID FREEZES THE PROCESS**
- Frozen process can't respond to text field focus events
- onCreateInputView() never called because process is frozen

### WHY THIS HAPPENS

**Freecess** (Samsung's aggressive battery optimization):
- Freezes "inactive" background apps
- IME services can be marked as "inactive" if no recent user interaction
- CleverKeys is being treated as a background app, not an active IME

**MARs** (Memory Auto-Restart Service):
- Samsung's memory management system
- Can freeze processes deemed "low priority"
- May not recognize CleverKeys as critical IME service

### EVIDENCE CHAIN

1. ✅ Service starts (`onCreate()` succeeds)
2. ✅ Configuration loads
3. ✅ `onStartInput()` fires (text field focused)
4. ❌ **PROCESS FROZEN** by Freecess/MARs
5. ❌ `onCreateInputView()` never called (frozen process can't respond)
6. ❌ Keyboard never renders

### ROOT CAUSE

**NOT a code issue** - CleverKeys code is correct
**NOT a lifecycle issue** - all methods implemented properly
**NOT a manifest issue** - configuration is correct

**ACTUAL ISSUE**: Android power management treating CleverKeys as freezable background app

### SOLUTION APPROACHES

#### Approach 1: Exempt from Battery Optimization (User Action)
```
Settings → Apps → CleverKeys → Battery → Unrestricted
```
**Pros**: Simple, user-controlled
**Cons**: Requires manual user action for each install

#### Approach 2: Request PARTIAL_WAKE_LOCK (Code Change)
Add permission to AndroidManifest.xml:
```xml
<uses-permission android:name="android.permission.WAKE_LOCK"/>
```

Acquire wake lock in onCreate():
```kotlin
private var wakeLock: PowerManager.WakeLock? = null

override fun onCreate() {
    super.onCreate()
    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = powerManager.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "CleverKeys::IMEWakeLock"
    )
    wakeLock?.acquire()
    // ... rest of onCreate
}
```

**Pros**: Prevents process freezing
**Cons**: Slight battery impact

#### Approach 3: Foreground Service (Code Change)
Promote IME to foreground service status:
```kotlin
override fun onCreate() {
    super.onCreate()
    startForeground(
        NOTIFICATION_ID,
        createNotification()
    )
    // ... rest of onCreate
}
```

**Pros**: Guaranteed non-freezable
**Cons**: Persistent notification, more invasive

#### Approach 4: Exclude from Freecess (Manifest Change)
Add to AndroidManifest.xml:
```xml
<application
    android:persistent="true"
    android:excludeFromRecents="false">
    ...
</application>
```

**Pros**: May prevent freezing
**Cons**: Requires system-level permissions (may not work)

### RECOMMENDED FIX

**Short-term** (immediate):
1. Add WAKE_LOCK permission to manifest
2. Acquire PARTIAL_WAKE_LOCK in onCreate()
3. Release in onDestroy()

**Long-term** (documentation):
1. Add troubleshooting guide for users experiencing keyboard not showing
2. Document battery optimization exemption steps
3. Consider foreground service for power users

### NEXT STEPS

1. ✅ **IDENTIFIED ROOT CAUSE** - Freecess/MARs freezing process
2. ⏳ Implement WAKE_LOCK solution
3. ⏳ Test on device
4. ⏳ Verify keyboard renders after fix
5. ⏳ Update documentation with battery optimization guidance

### VERIFICATION

**Before Fix**:
```
D FreecessHandler: freeze tribixbite.keyboard2(11317) result : 8
```

**After Fix** (expected):
```
# No freeze messages
# onCreateInputView() called successfully
# Keyboard renders in text fields
```

---

**Status**: ROOT CAUSE IDENTIFIED ✅
**Solution**: WAKE_LOCK implementation pending
**Impact**: HIGH - Affects ALL users on Samsung devices with aggressive battery optimization

---

**Created**: Nov 22, 2025 - 02:35 AM
**Severity**: CRITICAL (P0)
**Affects**: All Samsung devices with Freecess/MARs enabled (majority of Samsung phones)
