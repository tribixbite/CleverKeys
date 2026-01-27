---
title: Common Issues
description: Solutions for frequently encountered problems
category: Troubleshooting
difficulty: beginner
related_spec: ../specs/troubleshooting/common-issues-spec.md
---

# Common Issues

Solutions for the most frequently encountered problems with CleverKeys.

## Quick Summary

| Issue Type | Common Causes |
|------------|---------------|
| **Keyboard not showing** | Not enabled in system settings |
| **Typing issues** | Settings misconfiguration |
| **Gesture problems** | Threshold settings |
| **App compatibility** | App-specific behavior |

## Keyboard Not Appearing

### Issue: Keyboard doesn't show when tapping text field

**Solutions:**

1. **Check if enabled**
   - Go to Android Settings > System > Languages & input
   - Tap "On-screen keyboard" or "Virtual keyboard"
   - Ensure CleverKeys is enabled

2. **Set as default**
   - Settings > System > Languages & input
   - Tap "Default keyboard"
   - Select CleverKeys

3. **Force stop and restart**
   - Settings > Apps > CleverKeys
   - Tap "Force Stop"
   - Try again

### Issue: Keyboard shows briefly then disappears

**Solutions:**

1. Clear app cache:
   - Settings > Apps > CleverKeys > Storage
   - Tap "Clear Cache"

2. Check for conflicts:
   - Temporarily disable other keyboards
   - Disable accessibility services one by one

## Typing Issues

### Issue: Wrong characters appear

**Possible causes:**

| Cause | Solution |
|-------|----------|
| Wrong layout | Check active layout in Layout Manager |
| Autocorrect | Adjust autocorrect in Word Prediction section |
| Language mismatch | Check language configuration |

### Issue: Autocorrect making wrong corrections

**Solutions:**

1. **Adjust autocorrect settings**
   - Settings > Word Prediction section (expand it)
   - Toggle Autocorrect on/off or adjust Min Word Length

2. **Add words to dictionary**
   - When corrected wrongly, tap suggestion bar
   - Select the word you intended
   - It will be learned

3. **Disable for certain apps**
   - Some apps (code editors) should have autocorrect off

### Issue: No predictions showing

**Solutions:**

1. Check if predictions enabled:
   - Settings > Word Prediction section
   - Ensure **Word Predictions** is enabled

2. Check language support:
   - Predictions require a dictionary for your language
   - English is bundled by default

## Gesture Issues

### Issue: Short swipes not working

**Solutions:**

1. **Adjust swipe distance**
   - Settings > Gesture Tuning section
   - Adjust **Short Gesture Min Distance**

2. **Check if enabled**
   - Settings > Gesture Tuning > Short Gestures toggle

3. **Practice technique**
   - Swipe must be from key center, not edge
   - Movement should be quick, not slow drag

### Issue: TrackPoint mode won't activate

**Solutions:**

1. **Check gesture**
   - Long-press the nav key (between spacebar and enter)
   - Wait for haptic feedback

2. **Adjust threshold**
   - Settings > Input section > Long Press Timeout

3. **Wait for feedback**
   - Haptic vibration indicates activation
   - Enable haptics in Accessibility section

### Issue: Circle gestures not recognized

**Solutions:**

1. **Circle must be complete**
   - Draw full circle, not arc
   - End near start point

2. **Adjust sensitivity**
   - Settings > Gesture Tuning > Circle Sensitivity

3. **Practice size**
   - Circle should span 2-3 keys
   - Too small or too large won't register

## App Compatibility

### Issue: Keyboard behaves differently in certain apps

**Explanation:**

Some apps override keyboard behavior:

| App Type | Common Issues |
|----------|---------------|
| **Terminal** | May need special key settings |
| **Games** | May block keyboard |
| **Password managers** | May show own keyboard |
| **Some social apps** | Custom input handling |

**Solutions:**

1. **Add extra keys for terminals**
   - Settings > Activities > Extra Keys
   - Add Ctrl, Tab, Escape

2. **Check app permissions**
   - Some apps require explicit keyboard permission

### Issue: Keyboard keeps getting deselected

**Solutions:**

1. Check Android battery optimization:
   - Settings > Apps > CleverKeys > Battery
   - Select "Don't optimize" or "Unrestricted"

2. Check app cleaner:
   - If using RAM cleaner, whitelist CleverKeys

## Performance on Older Devices

### Issue: Keyboard crashes when swipe typing

**Affected:** Older devices (pre-2018), devices with limited RAM, or slow processors

**Symptoms:**
- Keyboard crashes immediately when trying to swipe type
- App force closes during gesture input
- Works fine with tap typing, crashes with swipe

**Solution:**

1. **Disable neural swipe typing**
   - Open CleverKeys Settings
   - Go to **Neural Prediction** section
   - Disable **Swipe Typing**
   - Use tap typing instead

2. **Why this happens**
   - Neural swipe prediction uses an ONNX machine learning model
   - Older devices may lack sufficient memory or CPU power
   - The model requires real-time inference during gestures

> [!NOTE]
> CleverKeys' neural prediction is optimized for modern devices. If your device is from 2017 or earlier, disabling swipe typing provides a stable experience while retaining all other features.

### Issue: Keyboard is slow or laggy

**Solutions:**

1. **Reduce prediction beam width**
   - Settings > Neural Prediction > Beam Width
   - Try 3-4 instead of 6+

2. **Disable haptic feedback**
   - Settings > Accessibility section
   - Disable Vibration
   - Haptics can cause lag on some devices

3. **Clear app cache**
   - Android Settings > Apps > CleverKeys > Storage
   - Tap "Clear Cache"

## Android Version-Specific Issues

### Issue: Navigation bar covers keyboard (Android 15)

**Status:** Fixed in v1.2.8

If you see this issue, update to v1.2.8 or later. The keyboard now properly handles Android 15's edge-to-edge display mode.

### Issue: White navigation buttons (Android 8-9)

**Status:** Fixed in v1.2.8

Update to v1.2.8 or later. Navigation button colors now properly adapt to theme.

### Issue: Monet theme crash on tablets

**Status:** Fixed in v1.2.8

Tablets running Android 12+ with limited color extraction now fall back gracefully to default themes.

## Visual Issues

### Issue: Keyboard background is transparent

**Common on:** Android 16 with blur disabled in accessibility settings

**Solution:**

1. Go to **Settings > Appearance > Keyboard Opacity**
2. Increase to 100% for solid background
3. Also check **Key Opacity** if keys appear transparent

> [!TIP]
> If using a custom theme, ensure both key and background opacity are set to your preference in the theme creator.

### Issue: Keyboard too small/large

**Solution:**

- Settings > Appearance section > Key Height
- Adjust to preference

### Issue: Keys hard to see

**Solutions:**

1. **Try different theme**
   - Settings > Activities > Theme Manager
   - Choose high contrast theme

2. **Increase label visibility**
   - Settings > Appearance > Label Brightness

3. **Enable key borders**
   - Settings > Appearance > Key Borders

### Issue: Theme not applying

**Solutions:**

1. Force stop and restart keyboard
2. Check if custom colors override theme
3. Reset to default theme first

## Sound/Haptic Issues

### Issue: No haptic feedback

**Solutions:**

1. **Check CleverKeys setting**
   - Settings > Accessibility section > Vibration

2. **Check system setting**
   - Android Settings > Sound > Vibration

3. **Check device capability**
   - Some tablets lack haptic motor

### Issue: Haptics too weak/strong

**Solution:**

- Settings > Accessibility section > Vibration Duration
- Adjust to preference

## Testing Your Keyboard

Use the built-in test field to troubleshoot without switching apps:

1. Open CleverKeys Settings
2. Look for the test keyboard area at the top
3. Test gestures, layouts, and features

## Still Having Issues?

If problems persist:

1. **Check for updates**
   - App store may have newer version

2. **Try reset**
   - See [Reset Defaults](reset-defaults.md)

3. **Collect logs**
   - Settings > About > Export Debug Info
   - Include with bug report

4. **Report issue**
   - Include device model, Android version
   - Describe exact steps to reproduce

## Related Topics

- [Reset Defaults](reset-defaults.md) - Start fresh
- [Backup & Restore](backup-restore.md) - Preserve settings
- [Performance](performance.md) - Speed issues

## Technical Details

See [Common Issues Technical Specification](../specs/troubleshooting/common-issues-spec.md).
