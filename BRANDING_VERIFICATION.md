# CleverKeys Branding Verification Guide

**Created**: November 21, 2025
**Purpose**: Verify the visual branding system is working correctly
**Build**: #1763757874018 (displays as "CleverKeys#4018")

---

## 🎯 What to Verify

The CleverKeys keyboard should display **"CleverKeys#4018"** in jewel-tone purple text on a silver background at the **bottom-right corner of the spacebar key**.

### Visual Specifications

**Text**: "CleverKeys#4018"
**Location**: Bottom-right corner of spacebar
**Font Size**: 20sp (small, subtle)
**Text Color**: Jewel tone purple (#9B59B6 - amethyst)
**Background**: Silver (#C0C0C0)
**Padding**: 1px (DPI-scaled)

---

## ✅ Verification Steps (2 minutes)

### Step 1: Install APK (if not already done)
```bash
# Via ADB (if connected)
adb install -r build/outputs/apk/debug/tribixbite.keyboard2.apk

# OR via file manager
# The APK should be at: ~/storage/shared/CleverKeys-v2-with-backup.apk
# or: build/outputs/apk/debug/tribixbite.keyboard2.apk
```

### Step 2: Enable Keyboard
1. Open **Android Settings**
2. Go to **System** → **Languages & input** → **On-screen keyboard**
3. Tap **Manage keyboards**
4. Find **"CleverKeys"** (NOT "CleverKeys (Debug)")
5. Toggle **ON**

### Step 3: Activate Keyboard
1. Open any text app (Messages, Notes, Chrome, etc.)
2. Tap in a text field to bring up keyboard
3. Tap the keyboard switcher icon (⌨️) in the navigation bar
4. Select **"CleverKeys"**

### Step 4: Verify Branding
1. **LOOK AT THE SPACEBAR**
2. Check the **bottom-right corner**
3. You should see: **"CleverKeys#4018"**
4. Text should be **jewel purple** on **silver background**

### Step 5: Take Screenshot
1. Take a screenshot showing the keyboard
2. Verify branding is visible in screenshot
3. Save for documentation

---

## ❌ Troubleshooting

### "I don't see any branding"

**Possible causes**:
1. **Wrong keyboard active**: You might be using the original Unexpected-Keyboard or another keyboard
   - Solution: Check keyboard switcher, select "CleverKeys"

2. **Old APK installed**: The branding was added Nov 21, 2025
   - Solution: Reinstall the latest APK

3. **Build number rendering issue**: Version info might not be loading
   - Solution: Check logcat for errors

### "I see different branding"

If you see a different build number (not #4018), that's fine! The build number auto-increments with each build. The important part is:
- ✅ "CleverKeys#XXXX" format is present
- ✅ Jewel purple text on silver background
- ✅ Located at bottom-right of spacebar

### "Branding is cut off or not visible"

**Possible causes**:
1. **Small screen size**: On very small screens, the branding might be tiny
   - Solution: Zoom in on screenshot to verify

2. **Theme rendering issue**: Custom themes might affect rendering
   - Solution: Try default theme

---

## 🔍 Verification Checklist

Use this checklist to confirm branding is working:

- [ ] APK installed (tribixbite.keyboard2.apk, 51MB)
- [ ] CleverKeys enabled in Android Settings
- [ ] CleverKeys activated in text field
- [ ] Spacebar is visible on screen
- [ ] Bottom-right corner of spacebar checked
- [ ] "CleverKeys#XXXX" text is visible
- [ ] Text color is jewel purple (purple/amethyst shade)
- [ ] Background is silver/grey
- [ ] Screenshot taken showing branding
- [ ] All keyboard functions work normally

---

## 📸 Expected Screenshot

Your screenshot should show:

```
┌─────────────────────────────────────────────────┐
│                                                 │
│  (Suggestion bar with predictions)              │
│                                                 │
├─────────────────────────────────────────────────┤
│  q   w   e   r   t   y   u   i   o   p         │
│   a   s   d   f   g   h   j   k   l            │
│    z   x   c   v   b   n   m                   │
│  Ctrl  Fn   [  SPACE  CleverKeys#4018 ]  ←     │
│                       └──────────────┘          │
│                    (purple on silver)           │
└─────────────────────────────────────────────────┘
```

**Note**: Layout may vary depending on your keyboard configuration, but the branding on the spacebar should always be visible.

---

## 🐛 Reporting Issues

If branding is not visible or looks incorrect:

### Check Logcat
```bash
adb logcat -s "CleverKeys" "Keyboard2View" "System.err" | grep -i "branding\|version_info"
```

### Verify Version Info Exists
```bash
# Check if version_info.txt was generated
ls -l build/generated-resources/raw/version_info.txt
cat build/generated-resources/raw/version_info.txt
```

### Report with Details
If you need to report an issue, include:
1. Screenshot showing keyboard (with or without branding)
2. APK size and build date
3. Android version
4. Device model
5. Logcat output (if available)

---

## ✅ Success Criteria

**Branding verification is SUCCESSFUL if**:
- ✅ "CleverKeys#XXXX" text appears on spacebar
- ✅ Text is jewel purple on silver background
- ✅ Branding is visible in screenshot
- ✅ Keyboard functions normally with branding

**If all criteria met**: Branding system is working! ✅

---

## 📝 Why This Matters

The branding system ensures:
1. **Visual Proof**: Clear evidence that CleverKeys is active
2. **No Confusion**: Prevents mixing up with original Unexpected-Keyboard
3. **Build Tracking**: Each build has unique identifier
4. **Testing Accuracy**: Ensures tests are run on correct keyboard

Without branding, there's no way to visually distinguish CleverKeys from the original Java implementation, leading to false positive testing.

---

## 🎯 Next Steps After Verification

Once branding is verified:
1. ✅ Mark branding verification as complete
2. ✅ Continue with full keyboard testing
3. ✅ Test all features (swipe, tap, gestures, settings)
4. ✅ Report any bugs found

See `docs/TEST_CHECKLIST.md` for comprehensive testing guide.

---

**End of Verification Guide**

*For questions or issues, see SESSION_NOV_21_2025.md*
*Build: tribixbite.keyboard2.apk (51MB, #1763757874018)*
*Last Updated: 2025-11-21*
