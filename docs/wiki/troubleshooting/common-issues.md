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
| Wrong layout | Check active layout in settings |
| Auto-correct | Adjust autocorrect settings |
| Language mismatch | Check language configuration |

### Issue: Autocorrect making wrong corrections

**Solutions:**

1. **Adjust autocorrect strength**
   - Settings > Input > Autocorrect
   - Choose a lower level

2. **Add words to dictionary**
   - When corrected wrongly, tap suggestion bar
   - Select the word you intended
   - It will be learned

3. **Disable for certain apps**
   - Some apps (code editors) should have autocorrect off

### Issue: No predictions showing

**Solutions:**

1. Check if predictions enabled:
   - Settings > Predictions > Enable Predictions

2. Check prediction bar visibility:
   - Settings > Appearance > Prediction Bar Height
   - Ensure not set to "Hidden"

3. Check language pack:
   - Predictions require language pack
   - Go to Settings > Language Packs

## Gesture Issues

### Issue: Short swipes not working

**Solutions:**

1. **Adjust swipe distance**
   - Settings > Gestures > Short Swipe Distance
   - Try "Short" setting

2. **Check if enabled**
   - Settings > Gestures > Enable Short Swipes

3. **Practice technique**
   - Swipe must be from key center, not edge
   - Movement should be quick, not slow drag

### Issue: TrackPoint mode won't activate

**Solutions:**

1. **Check gesture**
   - Long swipe (not tap) from spacebar
   - Continue holding after swipe

2. **Adjust threshold**
   - Settings > Gestures > Long Swipe Threshold
   - Try lower value

3. **Wait for feedback**
   - Haptic vibration indicates activation
   - Ensure haptics are enabled

### Issue: Circle gestures not recognized

**Solutions:**

1. **Circle must be complete**
   - Draw full circle, not arc
   - End near start point

2. **Adjust sensitivity**
   - Settings > Gestures > Circle Sensitivity

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
   - Settings > Extra Keys
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

## Android Version-Specific Issues

### Issue: Navigation bar covers keyboard (Android 15)

**Status:** ✅ Fixed in v1.2.8

If you see this issue, update to v1.2.8 or later. The keyboard now properly handles Android 15's edge-to-edge display mode.

### Issue: White navigation buttons (Android 8-9)

**Status:** ✅ Fixed in v1.2.8

Update to v1.2.8 or later. Navigation button colors now properly adapt to theme.

### Issue: Monet theme crash on tablets

**Status:** ✅ Fixed in v1.2.8

Tablets running Android 12+ with limited color extraction now fall back gracefully to default themes.

## Visual Issues

### Issue: Keyboard too small/large

**Solution:**

- Settings > Appearance > Keyboard Height
- Adjust to preference

### Issue: Keys hard to see

**Solutions:**

1. **Try different theme**
   - Settings > Theme
   - Choose high contrast theme

2. **Increase font size**
   - Settings > Accessibility > Font Size

3. **Enable high contrast**
   - Settings > Accessibility > High Contrast

### Issue: Theme not applying

**Solutions:**

1. Force stop and restart keyboard
2. Check if custom colors override theme
3. Reset to default theme first

## Sound/Haptic Issues

### Issue: No haptic feedback

**Solutions:**

1. **Check CleverKeys setting**
   - Settings > Haptics > Enable

2. **Check system setting**
   - Android Settings > Sound > Vibration

3. **Check device capability**
   - Some tablets lack haptic motor

### Issue: Haptics too weak/strong

**Solution:**

- Settings > Haptics > Intensity
- Adjust to preference

## Testing Your Keyboard

Use the built-in test field to troubleshoot without switching apps:

1. Open CleverKeys Settings
2. Look for "⌨️ Test Keyboard" at the top
3. Tap to expand and test gestures, layouts, and features

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
