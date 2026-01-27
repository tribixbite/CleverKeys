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
| **Lag on open** | Large clipboard | Reduce history limit |
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
   - Settings > Neural Prediction section > Beam Width
   - Try 3-4 instead of 6+

2. **Reduce languages**
   - Keep only languages you actively use
   - Each language adds processing overhead

### Issue: Key press feels delayed

**Solutions:**

1. **Check haptic timing**
   - Haptic feedback adds slight delay
   - Settings > Accessibility > Vibration Duration
   - Try reducing or disabling

2. **Disable key popup**
   - If your device has slow rendering
   - Key popup can add visual delay

## Startup Performance

### Issue: Keyboard slow to appear

**Solutions:**

1. **Reduce clipboard history**
   - Settings > Clipboard section > History Limit
   - Use smaller limit (e.g., 25 instead of 50)

2. **Clear unused profiles**
   - Settings > Activities > Backup & Restore
   - Delete unused exports

3. **Reduce installed layouts**
   - Settings > Activities > Layout Manager
   - Remove layouts you don't use

### Issue: First swipe word slow

**Explanation:**

Neural model loads on first use. This is normal behavior.

**Why this happens:**
- The ONNX model is loaded into memory on first swipe
- Subsequent swipes are fast as the model is cached
- This is a one-time delay per keyboard session

## Memory Usage

### Factors Affecting Memory

| Factor | Memory Impact |
|--------|---------------|
| **Language dictionaries** | 5-30 MB each |
| **Clipboard history** | Varies with content |
| **Neural model** | ~10 MB |
| **Personal dictionary** | Usually < 1 MB |

### Reduce Memory Usage

1. **Remove unused language packs**
   - Via Settings > Activities > Layout Manager
   - Delete unneeded layouts

2. **Reduce clipboard history**
   - Settings > Clipboard > History Limit
   - Smaller = less memory

3. **Clear cache periodically**
   - Android Settings > Apps > CleverKeys > Storage
   - Clear Cache

## Battery Optimization

### Battery Impact Factors

| Feature | Battery Impact |
|---------|----------------|
| **Haptics** | Medium |
| **Neural predictions** | Medium |
| **Clipboard monitoring** | Low |

### Reduce Battery Usage

1. **Reduce haptics**
   - Settings > Accessibility > Vibration Duration
   - Use lower duration or disable

2. **Disable unneeded haptic events**
   - Settings > Accessibility section
   - Turn off Haptic Key Press, Haptic Suggestion Tap, etc.

## Performance Settings

### Quick Performance Profile

For maximum speed on older devices:

| Setting | Value |
|---------|-------|
| Beam Width | 3-4 |
| Vibration | Off or Light |
| Clipboard Limit | 25 items |

### Quality vs Speed

| Priority | Settings |
|----------|----------|
| **Speed** | Lower beam width, fewer haptics |
| **Quality** | Higher beam width (6-10), full haptics |
| **Balanced** | Default settings |

## Device-Specific Tips

### Older Devices

- Use lower neural settings (Beam Width 3-4)
- Disable haptics if laggy
- Keep fewer layouts installed
- Reduce clipboard history

### Low-Memory Devices

- Remove unused language packs
- Reduce clipboard history to 10-25 items
- Clear cache weekly

### High-Performance Devices

- Can use maximum neural settings (Beam Width 10-12)
- Enable all haptics
- Keep many languages

## Tips for Best Performance

- **Regular cleanup**: Clear cache monthly
- **Update regularly**: Updates often include performance improvements
- **Report issues**: Unexpected slowness may be a bug
- **Test changes**: After adjusting, test typing feel

> [!TIP]
> If keyboard suddenly becomes slow, try clearing cache first. Many issues resolve with a cache clear.

## Common Questions

### Q: Will disabling predictions make typing faster?

A: Yes, but you lose a key feature. Try reducing beam width instead of disabling.

### Q: Does keyboard size affect performance?

A: Minimally. Render time is slightly higher for larger keyboards.

### Q: Why is swipe typing slower than tap typing?

A: Swipe requires neural processing. This is expected behavior.

### Q: Should I use battery optimization for CleverKeys?

A: No. Battery optimization may cause keyboard to be killed, making it slow to appear.

## Related Topics

- [Neural Settings](../settings/neural-settings.md) - Prediction configuration
- [Haptics](../settings/haptics.md) - Vibration settings
- [Common Issues](common-issues.md) - General troubleshooting
