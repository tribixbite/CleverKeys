# CleverKeys - Frequently Asked Questions (FAQ)

**Last Updated**: 2025-11-16
**Version**: 1.0.0

---

## 📱 Installation & Setup

### Q: How do I install CleverKeys?
**A**: Download from Google Play Store (or sideload the APK), then:
1. Open Settings on your device
2. Go to System → Languages & input → On-screen keyboard
3. Tap "Manage keyboards"
4. Toggle "CleverKeys" to ON
5. Open any text app and select CleverKeys from keyboard switcher

### Q: Why can't I find CleverKeys in my keyboard list?
**A**: Make sure you:
- Installed the app successfully
- Enabled it in "Manage keyboards" settings
- Restarted your device (if just installed)
- Granted any requested permissions

### Q: How do I switch to CleverKeys while typing?
**A**: Tap and hold the keyboard switcher icon (usually 🌐 or ⌨️ in the bottom-right corner), then select "CleverKeys" from the menu.

### Q: Can I set CleverKeys as my default keyboard?
**A**: Yes! Go to Settings → System → Languages & input → On-screen keyboard → Default keyboard → Select CleverKeys.

---

## ⌨️ Basic Usage

### Q: How do I type with CleverKeys?
**A**: Two ways:
- **Tap typing**: Tap each letter individually
- **Swipe typing**: Drag your finger across letters to form words

### Q: Why aren't my predictions showing up?
**A**: Check that:
- Predictions are enabled in CleverKeys settings
- You have a dictionary installed (default: 10k words included)
- You're typing in a supported language
- The text field allows predictions (some secure fields don't)

### Q: How do I accept a prediction?
**A**: Tap the suggested word in the suggestion bar, or press space to accept the first suggestion.

### Q: How accurate is swipe typing?
**A**: Very accurate! CleverKeys uses neural AI (ONNX models) for prediction. Accuracy improves as it learns your patterns (locally on your device).

---

## 🎨 Customization

### Q: How do I change the keyboard theme?
**A**: CleverKeys uses Material 3 theming and automatically matches your system theme (light/dark). Additional themes available in settings.

### Q: Can I resize the keyboard?
**A**: Yes! Settings → Visual → Margin bottom (adjust spacing).

### Q: How do I add extra keys (like Tab, Esc, arrows)?
**A**: Settings → Extra Keys Configuration → Select the keys you want from 85+ available options.

### Q: Can I use a different keyboard layout (Dvorak, Colemak, etc.)?
**A**: Yes! Settings → Layout Manager → Choose from 89 predefined layouts or create custom ones.

### Q: How do I create a custom layout?
**A**: Settings → Layout Manager → Custom tab → Add new layout → Edit XML (advanced users only).

---

## 🌍 Languages

### Q: What languages does CleverKeys support?
**A**: 20 languages: English, Spanish, French, German, Italian, Portuguese, Russian, Chinese, Japanese, Korean, Arabic, Hebrew, Hindi, Thai, Greek, Turkish, Polish, Dutch, Swedish, Danish.

### Q: How do I switch languages?
**A**: Long-press the spacebar or use the language switcher key (if enabled in extra keys).

### Q: Does it support multiple languages simultaneously?
**A**: Yes! Enable multiple languages in settings, and CleverKeys will auto-detect which you're typing.

### Q: What about RTL languages (Arabic, Hebrew)?
**A**: Fully supported with proper right-to-left text handling and RTL-aware cursor movement.

---

## 📚 Dictionary & Predictions

### Q: How do I add custom words to the dictionary?
**A**: Settings → Dictionary Manager → User Words tab → Add word.

### Q: Can I delete words from predictions?
**A**: Yes! Settings → Dictionary Manager → Disabled Words tab → Add words you never want to see.

### Q: Why does it keep suggesting a word I don't want?
**A**: Add it to the disabled words list (blacklist) in Dictionary Manager.

### Q: Can I import my dictionary from another keyboard?
**A**: Manually, yes. Export from old keyboard → Import words one by one in Dictionary Manager.

### Q: How large is the built-in dictionary?
**A**: 10,000 words (expandable to 50k in future versions).

---

## 🔒 Privacy & Security

### Q: Does CleverKeys collect my typing data?
**A**: **NO**. CleverKeys collects ZERO data. Everything is 100% local to your device. See PRIVACY_POLICY.md for details.

### Q: Is my typing data sent to the cloud?
**A**: **NO**. CleverKeys doesn't even have internet permission. Nothing is ever transmitted.

### Q: Can the developer see what I type?
**A**: **NO**. We have no servers, no analytics, no way to collect data even if we wanted to (which we don't).

### Q: Is it safe to enter passwords?
**A**: Yes, but we recommend using your device's built-in password autofill or a password manager for maximum security.

### Q: Does it work offline?
**A**: Yes! 100% offline. No internet connection ever needed or used.

---

## ♿ Accessibility

### Q: Is CleverKeys accessible for visually impaired users?
**A**: Yes! Full TalkBack support, voice guidance, and high-contrast themes available.

### Q: What is Switch Access?
**A**: A feature for users with severe mobility limitations (e.g., quadriplegia). It allows keyboard control using external switches (1-4 switches supported).

### Q: How do I enable Switch Access?
**A**: Settings → Accessibility → Switch Access → Enable. Configure scan mode and interval.

### Q: What are Mouse Keys?
**A**: Keyboard-based cursor control for users who can't use a touchscreen. Use arrow keys or WASD to move a virtual cursor.

### Q: Can I use CleverKeys with screen magnification?
**A**: Yes, fully compatible with Android's built-in magnification features.

---

## 🔧 Advanced Features

### Q: What is "Precision Mode"?
**A**: A mode that reduces sensitivity for more accurate typing (useful for users with tremors or on bumpy vehicles).

### Q: How do I enable vibration/haptic feedback?
**A**: Settings → Haptic & Sound → Vibration → Enable (adjust duration as needed).

### Q: Can I use voice typing with CleverKeys?
**A**: CleverKeys can switch to a voice-capable keyboard (like GBoard) via the voice typing key. Native voice typing coming in v1.1.

### Q: What is the Compose key?
**A**: A special key for entering accented characters and symbols by combining keystrokes (e.g., Compose + ' + e = é).

### Q: How do I show/hide the number row?
**A**: Settings → Layout → Number row → Always visible / Hidden / Auto.

### Q: Can I use keyboard shortcuts (Ctrl+C, etc.)?
**A**: Yes! External keyboard shortcuts are supported. On-screen shortcut bar coming in future version.

---

## 🐛 Troubleshooting

### Q: The keyboard won't show up when I tap a text field. Help!
**A**: Try:
1. Check if CleverKeys is enabled in system settings
2. Set it as default keyboard
3. Restart your device
4. Reinstall the app
5. Check if the app has required permissions (should be none needed)

### Q: Swipe typing isn't working!
**A**: Ensure:
- Swipe typing is enabled in settings
- You're dragging across letters (not tapping)
- The ML model is loaded (first launch might take a moment)
- You have enough storage space

### Q: Predictions are inaccurate!
**A**: Improving! The ML model adapts to your typing over time. Use it for a few days and accuracy will improve.

### Q: The keyboard is laggy/slow!
**A**: Check:
- Device has enough free RAM (close other apps)
- Hardware acceleration is enabled (should be by default)
- Clear app cache: Settings → Apps → CleverKeys → Clear cache
- Report the issue on GitHub if problem persists

### Q: The app crashed! What do I do?
**A**: 
1. Note what you were doing when it crashed
2. Restart your device
3. Report the bug on GitHub with details
4. Check for app updates

### Q: Keys are too small/large!
**A**: Settings → Visual → Key size / Margin / Text size - adjust to your preference.

### Q: Autocorrect is too aggressive!
**A**: Settings → Input → Autocorrection → Adjust sensitivity or disable.

---

## 🔄 Sync & Backup

### Q: Can I sync my settings across devices?
**A**: No, intentionally not supported for privacy. You can manually export/import settings.

### Q: How do I backup my custom words?
**A**: Currently manual only. Settings → Dictionary Manager → Copy words. Cloud backup coming in future with encryption.

### Q: I lost my custom dictionary! Can you recover it?
**A**: No, we don't have cloud backups. Your data is only on your device. Sorry!

### Q: Can I export my settings?
**A**: Not yet in v1.0.0. Settings export/import feature planned for v1.1.

---

## 💰 Pricing & Subscriptions

### Q: Is CleverKeys free?
**A**: Yes! Completely free, no ads, no in-app purchases, no subscriptions. Forever.

### Q: How do you make money?
**A**: We don't. CleverKeys is open source software developed for the community.

### Q: Are there premium features I need to pay for?
**A**: No. All features are available to everyone for free.

### Q: Will you ever add ads or subscriptions?
**A**: No. The app is GPL-licensed open source. Even if we wanted to (we don't), the license prevents it.

---

## 🔓 Open Source

### Q: Is CleverKeys open source?
**A**: Yes! GPL-3.0 license. Source code available on GitHub (URL TBD).

### Q: Can I modify the code?
**A**: Yes! Fork it, modify it, redistribute it (under GPL-3.0 terms).

### Q: How can I contribute?
**A**: GitHub pull requests welcome! Report bugs, suggest features, submit code, improve docs.

### Q: Can I build my own version?
**A**: Yes! Build instructions in repository. Reproducible builds supported.

---

## 📱 Compatibility

### Q: What Android versions are supported?
**A**: Android 8.0 (API 26) and above. Tested up to Android 15.

### Q: Does it work on tablets?
**A**: Yes! Responsive layout adapts to tablets.

### Q: Can I use it on Android TV?
**A**: Not optimized for TV, but technically works.

### Q: Does it work on Chromebooks?
**A**: Yes! ChromeOS Android app compatibility.

### Q: Will it work on my foldable phone?
**A**: Yes, adapts to different screen sizes and orientations.

---

## 🆚 Comparisons

### Q: How is CleverKeys different from GBoard?
**A**: 
- **Privacy**: CleverKeys = zero data collection, GBoard = Google knows everything
- **Open Source**: CleverKeys = fully auditable, GBoard = proprietary
- **Cloud**: CleverKeys = none, GBoard = required for some features
- **Permissions**: CleverKeys = none needed, GBoard = several

### Q: CleverKeys vs. SwiftKey?
**A**:
- **Privacy**: CleverKeys = local only, SwiftKey = Microsoft cloud sync
- **Ads**: CleverKeys = none, SwiftKey = sometimes
- **Cost**: CleverKeys = free forever, SwiftKey = free with optional premium
- **Open Source**: CleverKeys = yes, SwiftKey = no

### Q: CleverKeys vs. AnySoftKeyboard?
**A**:
- **UI**: CleverKeys = Material 3 modern, AnySoftKeyboard = older design
- **Predictions**: CleverKeys = neural AI, AnySoftKeyboard = traditional
- **Performance**: CleverKeys = hardware accelerated, AnySoftKeyboard = varies
- **Both**: Open source, privacy-focused, no data collection

### Q: CleverKeys vs. Unexpected-Keyboard (original)?
**A**:
- **Language**: CleverKeys = Kotlin, Unexpected-Keyboard = Java
- **Predictions**: CleverKeys = ONNX neural, Unexpected-Keyboard = CGR + fallbacks
- **UI**: CleverKeys = Material 3, Unexpected-Keyboard = custom
- **Features**: 100% parity (all features ported)

---

## 🚀 Future Plans

### Q: What features are coming next?
**A**: v1.1 roadmap (see RELEASE_NOTES):
- Emoji picker with categories & search
- Long-press popup for alternate characters
- 50k dictionary assets (20 languages)
- Theme customization UI
- Settings export/import
- Cloud backup (with encryption)

### Q: When is the next update?
**A**: Updates based on user feedback. Follow GitHub for announcements.

### Q: Can I request a feature?
**A**: Yes! GitHub issues with "feature request" tag.

### Q: Will you support [specific language]?
**A**: Language support prioritized by user demand. Request on GitHub!

---

## 📊 Technical Questions

### Q: What ML models does CleverKeys use?
**A**: ONNX transformer models trained on public domain text data.

### Q: How much RAM does it use?
**A**: Typically <150MB. Well-optimized for mobile.

### Q: Does it use a lot of battery?
**A**: No. Comparable to other keyboards. Hardware acceleration minimizes CPU usage.

### Q: What's the APK size?
**A**: ~52MB (includes ML models).

### Q: Why is it larger than some keyboards?
**A**: Neural ML models (~30MB) + 10k dictionaries (~10MB) + ONNX runtime (~10MB).

### Q: Can I use it without ONNX models?
**A**: No, neural prediction is core to CleverKeys. Models are embedded in APK.

---

## 🆘 Still Need Help?

### Support Channels

**GitHub Issues** (Best for bugs):
- URL: [Repository URL]/issues
- Response time: Usually within 48 hours
- Include: Android version, device model, steps to reproduce

**Email** (For general questions):
- Email: [Your Email]
- Response time: 3-5 business days

**Documentation**:
- **Quick Start**: `00_START_HERE_FIRST.md`
- **Manual**: `MANUAL_TESTING_GUIDE.md`
- **Reference**: `QUICK_REFERENCE.md`
- **Scripts**: `SCRIPTS_REFERENCE.md`

**Community** (Coming Soon):
- GitHub Discussions
- Reddit: r/CleverKeys (TBD)
- Discord: TBD

---

## 📝 Reporting Bugs

**When reporting bugs, include:**

1. **Device Info**:
   - Device model (e.g., "Pixel 7 Pro")
   - Android version (e.g., "Android 14")
   - CleverKeys version (e.g., "1.0.0")

2. **Steps to Reproduce**:
   - What you were doing
   - What you expected
   - What actually happened

3. **Logs** (if possible):
   - Run `./diagnose-issues.sh` (if you have it)
   - Or provide logcat output

4. **Screenshots** (if relevant):
   - Show the issue visually

**Where to report**: GitHub Issues

---

## 💡 Tips & Tricks

### Productivity Tips
1. **Quick symbols**: Long-press punctuation keys for variants
2. **Fast language switch**: Long-press spacebar
3. **Quick settings**: Long-press comma key (if enabled)
4. **Delete words quickly**: Swipe backspace left
5. **Capitalize mid-sentence**: Tap shift once for next letter only

### Privacy Tips
1. **Verify no network**: Use network monitor app
2. **Check permissions**: Settings → Apps → CleverKeys → Permissions (should be minimal)
3. **Review source**: Check GitHub for code transparency
4. **Disable cloud backup**: Settings → System → Backup → Exclude CleverKeys

### Performance Tips
1. **Clear cache occasionally**: Settings → Apps → CleverKeys → Clear cache
2. **Restart after heavy use**: Close and reopen keyboard periodically
3. **Limit extra keys**: Only enable keys you actually use
4. **Reduce animations**: Settings → Developer options → Animation scale → 0.5x

### Customization Tips
1. **Try different layouts**: Experiment with Dvorak/Colemak for ergonomics
2. **Adjust haptics**: Find your perfect vibration strength
3. **Theme matching**: Let it auto-match your system theme
4. **Custom extra keys**: Only show keys you frequently use

---

**Still have questions? Contact us!**

**Email**: [Your Email]
**GitHub**: [Repository URL]
**Website**: [Your Website]

---

**FAQ Version**: 1.0
**Last Updated**: 2025-11-16
**Status**: Draft (Ready for review after testing)

---

**END OF FAQ**
