---
title: Performance
description: Optimize keyboard speed and responsiveness
category: Troubleshooting
difficulty: intermediate
---

# Performance

Optimize CleverKeys for better speed, responsiveness, and battery efficiency.

## Quick Summary

| Issue | Common Cause | Solution |
|-------|--------------|----------|
| **Slow typing** | Neural predictions | Reduce beam width |
| **Lag on open** | Large dictionary | Reduce history |
| **Battery drain** | Haptics, animations | Reduce feedback |

## Typing Speed Issues

### Issue: Predictions appear slowly

**Causes:**

| Cause | Impact |
|-------|--------|
| High beam width | More processing |
| Many languages | Multiple dictionaries |
| Large vocabulary | Longer search |

**Solutions:**

1. **Reduce beam width**
   - Settings > Neural > Beam Width
   - Try 3-5 instead of 8-10

2. **Reduce prediction count**
   - Settings > Predictions > Count
   - Show fewer suggestions

3. **Disable unused languages**
   - Settings > Languages
   - Keep only languages you use

### Issue: Key press feels delayed

**Solutions:**

1. **Reduce animations**
   - Settings > Appearance > Press Animation
   - Set to "None" or "Highlight"

2. **Disable key popup**
   - Settings > Appearance > Key Pop-up
   - Set to "Off"

3. **Check haptic timing**
   - Haptic feedback adds slight delay
   - Try reducing intensity

## Startup Performance

### Issue: Keyboard slow to appear

**Solutions:**

1. **Reduce clipboard history**
   - Settings > Privacy > History Size
   - Use smaller limit

2. **Clear unused profiles**
   - Settings > Profiles
   - Delete unused profiles

3. **Reduce installed layouts**
   - Settings > Layouts
   - Remove layouts you don't use

### Issue: First swipe word slow

**Explanation:**

Neural model loads on first use. This is normal.

**Solutions:**

1. **Enable background loading**
   - Settings > Neural > Background Processing
   - Model preloads during idle time

## Memory Usage

### Factors Affecting Memory

| Factor | Memory Impact |
|--------|---------------|
| **Language packs** | 5-30 MB each |
| **Clipboard history** | Varies with content |
| **Neural model** | ~10 MB |
| **Personal dictionary** | Usually < 1 MB |

### Reduce Memory Usage

1. **Remove unused language packs**
   - Settings > Language Packs > Downloaded
   - Delete unneeded packs

2. **Reduce clipboard history**
   - Settings > Privacy > History Size
   - Smaller = less memory

3. **Clear cache periodically**
   - Android Settings > Apps > CleverKeys > Storage
   - Clear Cache

## Battery Optimization

### Battery Impact Factors

| Feature | Battery Impact |
|---------|----------------|
| **Haptics** | Medium |
| **Animations** | Low-Medium |
| **Neural predictions** | Medium |
| **Background processing** | Low |

### Reduce Battery Usage

1. **Reduce haptics**
   - Settings > Haptics > Intensity
   - Use "Light" or disable

2. **Disable unneeded haptic events**
   - Settings > Haptics
   - Turn off events you don't need

3. **Reduce animations**
   - Settings > Appearance > Press Animation
   - Set to "None"

4. **Disable background processing**
   - Settings > Neural > Background Processing
   - Turn off if battery is critical

## Performance Settings

### Quick Performance Profile

For maximum speed:

| Setting | Value |
|---------|-------|
| Beam Width | 3 |
| Prediction Count | 3 |
| Press Animation | None |
| Key Pop-up | Off |
| Haptics | Off or Light |

### Quality vs Speed

| Priority | Settings |
|----------|----------|
| **Speed** | Lower beam, fewer predictions, no animations |
| **Quality** | Higher beam, more predictions |
| **Balanced** | Default settings |

## Device-Specific Tips

### Older Devices

- Use lower neural settings
- Disable animations
- Keep fewer layouts installed
- Use compact keyboard height

### Low-Memory Devices

- Remove unused language packs
- Reduce clipboard history to 10 items
- Clear cache weekly
- Disable background processing

### High-Performance Devices

- Can use maximum neural settings
- Enable all animations
- Keep many languages

## Monitoring Performance

### Check Response Time

Settings > About > Show Debug Info

Shows:
- Prediction time
- Render time
- Memory usage

### Identify Issues

If prediction time > 100ms:
- Reduce beam width
- Disable unused languages

If render time > 16ms:
- Reduce animations
- Simplify theme

## Tips for Best Performance

- **Regular cleanup**: Clear cache monthly
- **Update regularly**: Updates often include performance improvements
- **Report issues**: Unexpected slowness may be a bug
- **Test changes**: After adjusting, test typing feel

> [!TIP]
> If keyboard suddenly becomes slow, try clearing cache first. Many issues resolve with a cache clear.

## Common Questions

### Q: Will disabling predictions make typing faster?

A: Yes, but you lose a key feature. Try reducing instead of disabling.

### Q: Does keyboard size affect performance?

A: Minimally. Render time is slightly higher for larger keyboards.

### Q: Why is swipe typing slower than tap typing?

A: Swipe requires neural processing. This is expected behavior.

### Q: Should I use battery optimization for CleverKeys?

A: No. Battery optimization may cause keyboard to be killed, making it slow to appear.

## Related Topics

- [Neural Settings](../settings/neural-settings.md) - Prediction configuration
- [Appearance](../settings/appearance.md) - Animation settings
- [Haptics](../settings/haptics.md) - Vibration settings
