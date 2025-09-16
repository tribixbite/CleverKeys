# CleverKeys Testing Guide 🧪

**Comprehensive testing instructions for CleverKeys v1.1.0**

This guide helps users and developers test CleverKeys functionality after the major fixes in v1.1.0. Use this to validate that the keyboard works properly on your device.

## 🚀 Quick Start Testing

### 📱 **Installation & Setup**
1. **Download APK**: Get `CleverKeys-Working-v1.1.apk` from [GitHub Releases](https://github.com/tribixbite/CleverKeys/releases)
2. **Install**: Enable "Unknown sources" and install APK
3. **Enable Keyboard**: Settings → Languages & Input → Virtual Keyboard → Add CleverKeys
4. **Set as Default**: Choose CleverKeys as your default keyboard
5. **Test Display**: Open any text input - keyboard should appear with cyan debug background

### ⚡ **Critical Functionality Tests**

#### **1. Keyboard Display Validation** 🔥
```
Expected: Bright cyan keyboard background with visible keys
Test: Open any text input (Notes, Messages, Browser search)
Success: Keyboard appears at bottom of screen with QWERTY layout
Failure: No keyboard appears or invisible/transparent keyboard
```

#### **2. Settings System Validation** 🔥
```
Expected: All settings categories load without crashes
Test: Long-press space bar → CleverKeys Settings
Success: Settings open showing 6+ categories with functional sliders
Failure: Settings crash or missing categories/options
```

#### **3. Basic Typing Functionality** 🔥
```
Expected: Key presses register and produce text output
Test: Tap individual letters to type words
Success: Text appears in input field for each key press
Failure: No text output or unresponsive keys
```

## 🧪 **Comprehensive Testing Checklist**

### 📋 **Core Keyboard Functions**

#### **Basic Input** ✅
- [ ] **Key Presses**: Individual letter keys produce correct output
- [ ] **Space Bar**: Space character inserted correctly
- [ ] **Backspace**: Deletes characters properly
- [ ] **Enter/Return**: Line breaks or submits input correctly
- [ ] **Shift**: Capital letters when shift pressed
- [ ] **Numbers**: Access to number input mode

#### **Layout & Appearance** ✅
- [ ] **Keyboard Visibility**: Keyboard appears with cyan debug background
- [ ] **Key Layout**: QWERTY layout displays correctly
- [ ] **Key Sizing**: Keys are appropriately sized for touch
- [ ] **Spacing**: Proper spacing between keys
- [ ] **Orientation**: Works in both portrait and landscape

#### **Touch & Gestures** ✅
- [ ] **Touch Response**: Keys respond to tap events
- [ ] **Touch Accuracy**: Correct key detected for touch position
- [ ] **Swipe Detection**: Swipe gestures recognized (check logs)
- [ ] **Multi-touch**: Multiple fingers don't cause issues
- [ ] **Edge Cases**: Corner taps and edge touches work

### ⚙️ **Settings System Testing**

#### **Settings Categories** 🔥
Test each category opens without crashing:

- [ ] **Layout**: System settings, alternate layouts, number row, NumPad options
- [ ] **Typing**: Word predictions, swipe typing, neural prediction settings
- [ ] **Behavior**: Auto-caps, vibration, timing, modifier behavior
- [ ] **Style**: Opacity sliders, themes, spacing, borders
- [ ] **Clipboard**: History enable/disable, limit settings
- [ ] **Swipe ML Data**: Export/import functionality

#### **Slider Preferences** 🔥
Test all slider controls work (IntSlideBarPreference/SlideBarPreference):

- [ ] **Suggestion Bar Opacity**: 0-100% slider functional
- [ ] **Keyboard Background Opacity**: 0-100% slider works
- [ ] **Key Opacity**: 0-100% adjustment works
- [ ] **Label Brightness**: 50-100% brightness control
- [ ] **Keyboard Height**: 10-100% height adjustment
- [ ] **Vibration Intensity**: 0-100ms vibration setting
- [ ] **Long Press Timeout**: 50-2000ms timing control
- [ ] **Key Repeat Interval**: 5-100ms repeat setting

#### **Neural Prediction Settings** 🧠
- [ ] **Enable Neural**: Toggle neural predictions on/off
- [ ] **Beam Width**: 1-16 neural beam width slider
- [ ] **Max Length**: 10-50 maximum word length slider
- [ ] **Confidence Threshold**: 0.0-1.0 confidence filtering

### 🧠 **Neural Prediction Testing**

#### **Neural System Validation** ⚡
- [ ] **Model Loading**: Check logs for ONNX model loading success
- [ ] **Neural Calibration**: Open neural calibration without crashes
- [ ] **Swipe Prediction**: Perform swipe gestures and check for predictions
- [ ] **Performance**: Monitor prediction latency in logs
- [ ] **Error Handling**: Neural failures don't crash keyboard

#### **Advanced Neural Features** 🎯
- [ ] **Tensor Pooling**: Check logs for pool hit rates and optimization
- [ ] **Pipeline Parallelism**: Monitor encoder/decoder overlap metrics
- [ ] **Memory Management**: Verify tensor cleanup and resource management
- [ ] **Hardware Acceleration**: Check for XNNPACK optimization messages

### 📱 **Device Integration Testing**

#### **System Integration** ✅
- [ ] **Input Method**: Listed in system keyboard settings
- [ ] **Default Selection**: Can be set as default keyboard
- [ ] **App Switching**: Works across different apps
- [ ] **System Restart**: Survives device restart
- [ ] **Permissions**: No permission errors or security issues

#### **Compatibility Testing** 🌍
- [ ] **Android Versions**: Works on your Android version
- [ ] **Device Type**: Phone/tablet compatibility
- [ ] **Screen Sizes**: Adapts to different screen dimensions
- [ ] **Hardware**: No conflicts with device hardware
- [ ] **Memory**: Stable under normal memory conditions

### 🔧 **Advanced Feature Testing**

#### **Accessibility** ♿
- [ ] **Screen Readers**: Works with TalkBack if enabled
- [ ] **High Contrast**: Readable in accessibility modes
- [ ] **Large Text**: Scales with system text size
- [ ] **Voice Commands**: Voice input integration works

#### **Multi-Language** 🌍
- [ ] **Layout Switching**: Can switch between keyboard layouts
- [ ] **Language Detection**: Auto-detects input language
- [ ] **International**: Works with non-English layouts
- [ ] **RTL Support**: Right-to-left language compatibility

## 🐛 **Troubleshooting Guide**

### **Common Issues & Solutions**

#### **Keyboard Not Displaying**
```
Symptoms: No keyboard appears when text input focused
Solutions:
1. Check if CleverKeys is enabled in system settings
2. Set CleverKeys as default keyboard
3. Restart the app using the keyboard
4. Check device logs: adb logcat -s CleverKeysService
```

#### **Settings Crashes**
```
Symptoms: Settings app crashes when opening CleverKeys options
Solutions:
1. Verify v1.1.0 APK installed (contains preference fixes)
2. Clear CleverKeys app data and reconfigure
3. Check for missing preference class errors in logs
4. Reinstall APK if settings still crash
```

#### **Neural Predictions Not Working**
```
Symptoms: Swipe gestures don't produce word predictions
Solutions:
1. Enable neural predictions in settings
2. Check logs for ONNX model loading errors
3. Verify device has sufficient memory (25MB+ available)
4. Test with neural calibration first
```

#### **Performance Issues**
```
Symptoms: Slow typing response or high memory usage
Solutions:
1. Adjust neural beam width to lower value (4-8)
2. Check tensor pool hit rates in logs
3. Monitor device thermal throttling
4. Reduce neural max length if needed
```

### **Debug Information Collection**

#### **Log Collection**
```bash
# Collect CleverKeys logs
adb logcat -s CleverKeysService,CleverKeysView,Neural,ONNX > cleverkeys-debug.log

# Monitor specific components
adb logcat | grep -E "CleverKeys|tribixbite|Neural|ONNX"

# Check for crashes
adb logcat -s AndroidRuntime | grep tribixbite
```

#### **Performance Monitoring**
```bash
# Monitor memory usage
adb shell dumpsys meminfo tribixbite.keyboard2.debug

# Check CPU usage
adb shell top -p $(adb shell pidof tribixbite.keyboard2.debug)

# Monitor neural prediction latency
adb logcat -s CleverKeysView | grep "TENSOR-POOLED INFERENCE"
```

## 📊 **Expected Performance Benchmarks**

### 🎯 **Target Metrics (v1.1.0)**
- **Keyboard Display**: <500ms keyboard appearance
- **Key Response**: <16ms touch-to-visual feedback
- **Settings Loading**: <1000ms settings categories load
- **Neural Prediction**: <200ms with optimizations
- **Memory Usage**: 15-25MB additional RAM
- **Battery Impact**: <2% additional drain

### 📈 **Success Criteria**
- **Basic Functionality**: 100% - keyboard displays and accepts input
- **Settings System**: 100% - all categories load without crashes
- **Neural Predictions**: 80% - ONNX models load and predict
- **Performance**: 90% - meets latency and memory targets
- **Compatibility**: 95% - works across Android versions

## 🎉 **Community Feedback**

### 📢 **Report Issues**
Found a problem? Please report it:
- **GitHub Issues**: [Create Bug Report](https://github.com/tribixbite/CleverKeys/issues/new?template=bug_report.yml)
- **Include**: Device info, Android version, CleverKeys version, logs
- **Priority**: Keyboard display, settings crashes, neural prediction failures

### 💡 **Feature Requests**
Have ideas for improvements?
- **GitHub Discussions**: [Share Ideas](https://github.com/tribixbite/CleverKeys/discussions)
- **Feature Requests**: [Request Features](https://github.com/tribixbite/CleverKeys/issues/new?template=feature_request.yml)

### 🤝 **Contributing**
Want to help improve CleverKeys?
- **Code**: Check [CONTRIBUTING.md](CONTRIBUTING.md) for development setup
- **Testing**: Help test on different devices and Android versions
- **Documentation**: Improve guides and examples
- **Neural Models**: Contribute to prediction accuracy improvements

---

## 🏆 **Success Stories**

Share your CleverKeys experience:
- **Performance Improvements**: How fast are neural predictions on your device?
- **Feature Usage**: Which features do you use most?
- **Comparison**: How does it compare to other keyboards?
- **Workflows**: How has it improved your typing workflow?

---

**CleverKeys v1.1.0**: From broken keyboard to working neural prediction system! 🧠⌨️

Test it thoroughly and let us know how it performs on your device! 🚀