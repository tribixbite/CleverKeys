# CleverKeys User Manual

**Version**: 1.0.0
**Last Updated**: 2025-11-16
**For Android**: 8.0+ (API 26+)

---

## Table of Contents

1. [Introduction](#introduction)
2. [Installation & Setup](#installation--setup)
3. [Getting Started](#getting-started)
4. [Basic Usage](#basic-usage)
5. [Advanced Features](#advanced-features)
6. [Customization](#customization)
7. [Multi-Language Support](#multi-language-support)
8. [Accessibility Features](#accessibility-features)
9. [Privacy & Security](#privacy--security)
10. [Troubleshooting](#troubleshooting)
11. [Tips & Best Practices](#tips--best-practices)
12. [Technical Reference](#technical-reference)

---

## Introduction

### What is CleverKeys?

CleverKeys is a privacy-focused Android keyboard with neural AI-powered swipe typing. It's a complete Kotlin rewrite of Unexpected-Keyboard, featuring:

- **100% local processing** - No cloud, no data collection, no internet required
- **Neural swipe typing** - ONNX transformer models for accurate predictions
- **20 languages** - Multi-language support with automatic detection
- **Material 3 design** - Modern, beautiful interface
- **Full accessibility** - Switch Access, Mouse Keys, screen reader support
- **Open source** - GPL-3.0 licensed, fully auditable code

### Who is CleverKeys For?

- **Privacy-conscious users** who don't want their typing data sent to the cloud
- **Multi-language users** who type in multiple languages
- **Accessibility users** who need Switch Access or Mouse Keys support
- **Power users** who want extensive customization options
- **Open source enthusiasts** who value transparency

### Key Differences from Other Keyboards

**vs. GBoard:**
- ✅ No Google account required
- ✅ No data collection
- ✅ Works 100% offline
- ✅ Open source

**vs. SwiftKey:**
- ✅ No Microsoft account required
- ✅ No cloud sync
- ✅ No ads
- ✅ Free forever

**vs. AnySoftKeyboard:**
- ✅ More modern UI (Material 3)
- ✅ Neural predictions (ONNX)
- ✅ Better performance
- ✅ More languages

---

## Installation & Setup

### System Requirements

- **Android Version**: 8.0 (Oreo) or higher
- **Storage**: 52MB for app installation
- **RAM**: 150MB additional during use
- **Permissions**: None required (vibration and app storage are automatic)

### Installation Steps

1. **Download CleverKeys**
   - From Google Play Store (coming soon)
   - Or sideload the APK from releases

2. **Install the APK**
   - Tap the downloaded APK file
   - Grant "Install from Unknown Sources" if prompted
   - Wait for installation to complete

3. **Enable CleverKeys**
   - Open **Settings** on your device
   - Navigate to **System** → **Languages & input**
   - Tap **On-screen keyboard**
   - Tap **Manage keyboards**
   - Find **CleverKeys** and toggle it **ON**
   - Accept the permissions dialog

4. **Set as Default (Optional)**
   - In **On-screen keyboard** settings
   - Tap **Default keyboard**
   - Select **CleverKeys**

### First Launch

When you first activate CleverKeys:

1. Open any text app (Notes, Messages, etc.)
2. Tap a text field to show the keyboard
3. If CleverKeys doesn't appear, tap the keyboard switcher icon (🌐 or ⌨️)
4. Select **CleverKeys** from the list
5. The keyboard will load (may take 1-2 seconds on first launch)

### Initial Configuration

After first launch, you may want to:

1. **Choose your language**
   - Long-press the spacebar
   - Select your primary language
   - Add additional languages in Settings

2. **Adjust keyboard size**
   - Open CleverKeys Settings
   - Go to **Visual** → **Margin bottom**
   - Adjust to your preference

3. **Enable features you need**
   - Vibration: **Haptic & Sound** → **Vibration**
   - Sound: **Haptic & Sound** → **Key press sound**
   - Predictions: **Input** → **Word predictions** (on by default)

---

## Getting Started

### Your First Typing Session

Let's walk through basic typing:

1. **Tap Typing**
   - Tap each letter individually to spell a word
   - Type "hello"
   - Notice autocorrect suggestions appear above the keyboard

2. **Accepting Suggestions**
   - Type "helo" (misspelled)
   - Tap "hello" in the suggestion bar
   - Or press **Space** to accept the first suggestion

3. **Swipe Typing**
   - Place your finger on **h**
   - Drag through **e** → **l** → **l** → **o**
   - Lift your finger
   - "hello" appears with high confidence!

4. **Punctuation**
   - Tap the **,** key for comma
   - Long-press **,** for more punctuation (semicolon, etc.)
   - Double-tap **Space** for period + auto-capitalize

5. **Capitalization**
   - Tap **Shift** once for next letter capitalization
   - Double-tap **Shift** for CAPS LOCK
   - Tap **Shift** again to turn off CAPS LOCK

### Understanding the Interface

**Main Keyboard Area:**
- Letter keys (QWERTY layout by default)
- Number row (if enabled in settings)
- Special keys: Shift, Delete, Enter, Space

**Suggestion Bar** (above keyboard):
- Left suggestion: Alternative word
- Center suggestion: Primary prediction (tap Space to accept)
- Right suggestion: Another alternative

**Bottom Row:**
- **Compose key** (if enabled): Special character input
- **Spacebar**: Insert space / Accept primary suggestion
- **Language indicator** (if multi-language enabled)
- **Enter/Return**: New line or submit

**Extra Keys Row** (if configured):
- Tab, Esc, arrows, F-keys, etc.
- Customizable in Settings → Extra Keys Configuration

---

## Basic Usage

### Tap Typing

**How to Type:**
1. Tap each letter key in sequence
2. Press **Space** after each word
3. Use **Delete** to remove characters

**Tips:**
- Tap and hold **Delete** to delete words quickly
- Swipe left on **Delete** to delete entire words
- Use **Shift** + letter for capital letters

### Swipe Typing

**How to Swipe:**
1. Place finger on the first letter
2. Drag through each letter of the word without lifting
3. Lift finger at the end of the word
4. The word appears automatically

**Swipe Tips:**
- Don't worry about precision - the neural model understands your intent
- You don't need to hit every letter exactly
- Faster swipes often work better than slow, careful ones
- If the wrong word appears, tap the correct suggestion

**Loop Gestures:**
- Circle on a key to double that letter
- Example: Circle on 'l' while swiping "hello" for double-l

### Word Predictions

**How Predictions Work:**
- As you type, CleverKeys suggests completions
- Predictions use:
  - **Unigram model**: Individual word frequency
  - **Bigram model**: Word pairs ("I am" → "the")
  - **User dictionary**: Your custom words
  - **User adaptation**: Words you type frequently

**Using Predictions:**
- **Tap** a suggestion to insert it
- **Press Space** to accept the first (center) suggestion
- **Keep typing** to ignore suggestions

**Prediction Quality:**
- Accuracy improves as the keyboard learns your patterns
- Add frequently-used words to your user dictionary
- Disable unwanted words in Dictionary Manager

### Autocorrection

**How Autocorrect Works:**
- Automatically fixes typos when you press Space
- Uses keyboard-aware edit distance (accounts for nearby keys)
- Only corrects if confidence is high

**Examples:**
- "teh " → "the "
- "helo " → "hello "
- "recieve " → "receive "

**Controlling Autocorrect:**
- **Accept**: Press Space after a typo
- **Reject**: Tap the original word in suggestions
- **Disable**: Settings → Input → Autocorrection → Off

### Special Characters

**Method 1: Long Press**
- Long-press a letter for accents (e → é, è, ê, ë)
- Long-press punctuation for variants (, → ; : ... etc.)

**Method 2: Compose Key**
- Enable: Settings → Input → Compose key
- Press Compose + ' + e = é
- Press Compose + " + a = ä
- See full Compose key table in Settings

**Method 3: Symbol Layouts**
- Tap **?123** to switch to symbols
- Tap **=\\<** for more symbols
- Tap **ABC** to return to letters

### Emojis

Currently, CleverKeys uses the system emoji picker:

1. Long-press the **,** key or **Enter** key (depends on settings)
2. Select 😀 emoji icon
3. System emoji picker opens
4. Choose your emoji

*(Custom emoji picker with categories coming in v1.1)*

---

## Advanced Features

### Clipboard History

**What is Clipboard History?**
- Remembers text you copy
- Stores up to 50 recent clips
- Persists across app restarts
- Can pin important clips

**Using Clipboard History:**
1. Copy text in any app (Ctrl+C or long-press → Copy)
2. CleverKeys stores it in history
3. Open CleverKeys clipboard (long-press paste key or Settings)
4. Tap a clip to paste it

**Managing Clips:**
- **Pin**: Long-press a clip → Pin
- **Delete**: Swipe left on a clip
- **Clear All**: Clipboard settings → Clear history

**Privacy:**
- Clipboard data stored locally only
- Never sent to cloud
- Configure duration: Settings → Clipboard → History duration

### Dictionary Manager

**NEW in v1.0.0!** Comprehensive 3-tab dictionary management:

**User Words Tab:**
- Your personal dictionary (words you added)
- Add new words: Type the word, tap "Add to dictionary"
- Or: Settings → Dictionary Manager → User Words → Add Word
- Delete words: Swipe left or tap ✕

**Built-in Dictionary Tab:**
- 10,000 common words (expandable to 50k in v1.1)
- Search to find words
- View word frequency
- Cannot edit (system dictionary)

**Disabled Words Tab:**
- Blacklist for unwanted predictions
- Add words you never want to see as suggestions
- Disable a word: Long-press suggestion → Disable
- Or: Settings → Dictionary Manager → Disabled Words → Add Word

### Voice Typing

CleverKeys supports voice input via IME switching:

1. Tap the **microphone** key (if enabled in extra keys)
2. CleverKeys switches to a voice-capable keyboard (e.g., GBoard)
3. Speak your text
4. Switch back to CleverKeys when done

*(Native voice typing integration coming in v1.1)*

### Handwriting Recognition

For CJK (Chinese, Japanese, Korean) users:

1. Enable: Settings → Input → Handwriting
2. Draw characters with your finger
3. Multi-stroke recognition for complex characters
4. Predictions appear in suggestion bar

### Macro Expansion

Create text shortcuts:

**Example:**
- Shortcut: "btw"
- Expands to: "by the way"

**Setup:**
1. Settings → Input → Macros
2. Add macro: "btw" → "by the way"
3. Type "btw" + Space
4. Automatically expands!

**Use Cases:**
- Email addresses: "em" → "myemail@example.com"
- Signatures: "sig" → "Best regards, [Your Name]"
- Common phrases: "addr" → "123 Main St, City, State"

### Keyboard Shortcuts

CleverKeys supports standard keyboard shortcuts:

**Text Editing:**
- **Ctrl + C**: Copy
- **Ctrl + X**: Cut
- **Ctrl + V**: Paste
- **Ctrl + Z**: Undo
- **Ctrl + Y**: Redo
- **Ctrl + A**: Select all

**Navigation:**
- **Ctrl + Left/Right**: Jump by word
- **Home/End**: Start/end of line
- **Ctrl + Home/End**: Start/end of document

**Requirements:**
- Physical keyboard or Bluetooth keyboard connected
- Or enable extra keys with Ctrl modifier

### One-Handed Mode

Shift keyboard left or right for thumb typing:

**Enable:**
1. Settings → Layout → One-handed mode
2. Choose: Left-handed or Right-handed

**Usage:**
- Keyboard shrinks and moves to one side
- Easier to type with one thumb
- Toggle on/off with keyboard icon

### Precision Mode

Reduce sensitivity for more accurate typing:

**Use Cases:**
- Motor disabilities (tremors)
- Bumpy vehicle (bus, train)
- Zoomed-in displays

**Enable:**
Settings → Input → Precision mode → On

**Effect:**
- Increased touch target size
- Slower key repeat
- More deliberate tap detection

---

## Customization

### Keyboard Layouts

CleverKeys supports 89+ predefined layouts:

**Popular Layouts:**
- **QWERTY** (default): Standard English layout
- **AZERTY**: French layout
- **QWERTZ**: German layout
- **Dvorak**: Ergonomic alternative
- **Colemak**: Modern ergonomic layout
- **Workman**: Balanced ergonomic layout

**Changing Layout:**
1. Settings → Layout Manager
2. Browse available layouts
3. Tap a layout to preview
4. Tap "Activate" to set as default

**Multiple Layouts:**
1. Settings → Layout Manager → Active Layouts
2. Enable multiple layouts
3. Swipe spacebar to switch between them

**Custom Layouts:**
1. Settings → Layout Manager → Custom
2. Add new layout
3. Edit XML definition (advanced users only)
4. See `src/main/layouts/` for examples

### Extra Keys Configuration

Add frequently-used keys to your keyboard:

**Available Keys (85+):**
- **Control keys**: Tab, Esc, Ctrl, Alt, Meta
- **Navigation**: Arrow keys (↑ ↓ ← →), Home, End, Page Up/Down
- **Function keys**: F1-F12
- **Programming**: Brackets, braces, pipe, backslash
- **Punctuation**: Specialized quotes, dashes, symbols
- **Actions**: Voice typing, settings, clipboard

**Configuring Extra Keys:**
1. Settings → Extra Keys Configuration
2. Choose from 9 categories
3. Drag and drop keys to reorder
4. Enable/disable keys as needed
5. Save configuration

**Popular Configurations:**
- **Developers**: Tab, Esc, Ctrl, brackets, pipe
- **Writers**: Em-dash, en-dash, smart quotes
- **Power users**: Arrows, Home, End, function keys

### Visual Customization

**Themes:**
1. Settings → Visual → Theme
2. Choose from 4 Material 3 themes:
   - **Light**: Default light theme
   - **Dark**: Default dark theme
   - **Everforest Light**: Nature-inspired light
   - **Cobalt**: Deep blue dark theme
   - **Pine**: Forest green theme
   - **ePaper Black**: High contrast

*(Custom theme editor coming in v1.1)*

**Key Appearance:**
- **Key opacity**: Settings → Visual → Key opacity (0-100%)
- **Key borders**: Settings → Visual → Show key borders
- **Key preview**: Settings → Visual → Show character preview popup
- **Text size**: Settings → Visual → Key text size

**Keyboard Size:**
- **Height**: Settings → Visual → Margin bottom
- **Width**: Auto-adjusts to screen width
- **Padding**: Settings → Visual → Key spacing

**Animations:**
- **Gesture trails**: Settings → Visual → Show swipe trail
- **Key press feedback**: Settings → Visual → Key press animation

### Haptic Feedback

Customize vibration and sound:

**Vibration:**
1. Settings → Haptic & Sound → Vibration
2. Enable/disable
3. Adjust duration (10-50ms)
4. Set intensity (light/medium/strong)

**Sound Effects:**
1. Settings → Haptic & Sound → Key press sound
2. Enable/disable
3. Choose sound type
4. Adjust volume

**Tips:**
- Disable for silent typing
- Light vibration saves battery
- Sound helps with accuracy feedback

---

## Multi-Language Support

### Supported Languages (20)

CleverKeys supports the following languages:

1. **English** (en) - 10,000 word dictionary
2. **Spanish** (es) - Español
3. **French** (fr) - Français
4. **German** (de) - Deutsch
5. **Italian** (it) - Italiano
6. **Portuguese** (pt) - Português
7. **Russian** (ru) - Русский
8. **Chinese** (zh) - 中文 (simplified)
9. **Japanese** (ja) - 日本語
10. **Korean** (ko) - 한국어
11. **Arabic** (ar) - العربية (RTL)
12. **Hebrew** (he) - עברית (RTL)
13. **Hindi** (hi) - हिन्दी
14. **Thai** (th) - ภาษาไทย
15. **Greek** (el) - Ελληνικά
16. **Turkish** (tr) - Türkçe
17. **Polish** (pl) - Polski
18. **Dutch** (nl) - Nederlands
19. **Swedish** (sv) - Svenska
20. **Danish** (da) - Dansk

### Adding Languages

**Enable a Language:**
1. Settings → Languages
2. Tap "Add Language"
3. Select from available languages
4. Download dictionary if prompted (future feature)
5. Language is now active

**Managing Languages:**
- Reorder: Drag languages to prioritize
- Remove: Swipe left on a language
- Default: First language is default

### Switching Languages

**Method 1: Spacebar Swipe**
- Swipe left/right on spacebar
- Cycles through active languages
- Language indicator updates

**Method 2: Long Press Spacebar**
- Long-press spacebar
- Language picker appears
- Tap desired language

**Method 3: Language Key**
- Enable language switcher in extra keys
- Tap to cycle through languages

### Auto-Detection

CleverKeys can automatically detect your language:

**How it Works:**
- After typing 3-4 words
- Analyzes character patterns
- Switches to detected language

**Enable:**
Settings → Languages → Auto-detect language → On

**Accuracy:**
- Very accurate for distinct alphabets (Latin vs Cyrillic vs Arabic)
- May need help distinguishing similar languages (Spanish vs Portuguese)

### RTL Language Support

**Right-to-Left Languages:**
- Arabic (ar)
- Hebrew (he)
- Persian/Farsi (fa)
- Urdu (ur)

**RTL Features:**
- Text flows right to left
- Cursor movement reversed
- Proper text selection
- Correct bi-directional text (mixed LTR/RTL)

**Usage:**
- Select RTL language
- Keyboard layout mirrors for RTL
- Text entry works naturally

**Bi-Directional Text:**
- Mix Arabic/English in same text
- Proper handling of numbers in RTL
- Correct punctuation placement

---

## Accessibility Features

CleverKeys is designed to be accessible to all users, including those with disabilities.

### Switch Access

**What is Switch Access?**
- For users with severe motor disabilities
- Control keyboard using external switches
- 1-4 switches supported
- 5 scan modes available

**Enabling Switch Access:**
1. Settings → Accessibility → Switch Access
2. Toggle **Enable Switch Access**
3. Configure scan mode
4. Set scan interval (500ms - 3000ms)
5. Connect external switches (Bluetooth or USB)

**Scan Modes:**

1. **Auto Scan**
   - Automatic scanning at set interval
   - Press switch to select highlighted item
   - Best for: Single switch users

2. **Manual Scan**
   - Press switch to advance scan
   - Press and hold to select
   - Best for: Users with better timing control

3. **Row-Column Scan**
   - First scan: Highlight rows
   - Second scan: Highlight keys in row
   - Best for: Faster navigation

4. **Group Scan**
   - Keys grouped by regions
   - Hierarchical selection
   - Best for: Reducing scans

5. **Point Scan**
   - Horizontal then vertical scanning
   - Crosshair targeting
   - Best for: Precise control

**Switch Configuration:**
- Switch 1: Select/Activate
- Switch 2: Next item (if using manual scan)
- Switch 3: Previous item (optional)
- Switch 4: Cancel/Back (optional)

**Visual Feedback:**
- High contrast highlighting
- Clear focus indicators
- Adjustable highlight colors

**Audio Feedback:**
- Voice guidance announces focused key
- Configurable: Settings → Accessibility → Voice guidance

### Mouse Keys

**What are Mouse Keys?**
- Keyboard-based cursor control
- For users who can't use touchscreen
- Visual crosshair overlay
- Click emulation

**Enabling Mouse Keys:**
1. Settings → Accessibility → Mouse Keys
2. Toggle **Enable Mouse Keys**
3. Configure movement speed
4. Set click mode

**Using Mouse Keys:**
- **Arrow keys**: Move cursor
- **Enter**: Click at cursor position
- **Space**: Long-press at cursor
- **Esc**: Cancel mouse mode

**Advanced:**
- **WASD mode**: Alternative movement keys
- **Precision mode**: Slower, more accurate movement
- **Grid targeting**: Jump to screen regions

### Screen Reader Support

**TalkBack Compatibility:**
- Full TalkBack support
- All keys have content descriptions
- Proper focus order
- Announcement of predictions

**Using with TalkBack:**
1. Enable TalkBack in Android Accessibility settings
2. CleverKeys automatically adapts
3. Touch explore to find keys
4. Double-tap to activate

**Optimizations:**
- Clear, concise announcements
- No redundant information
- Proper landmark navigation
- Suggestion bar fully accessible

### Voice Guidance

**Built-in Voice Guidance:**
- Announces key presses
- Reads suggestions
- Confirms actions

**Enable:**
Settings → Accessibility → Voice guidance → On

**Options:**
- Announce keys: Read letter/symbol on press
- Announce suggestions: Read prediction bar
- Announce actions: Confirm deletions, etc.

**Voice:**
- Uses Android Text-to-Speech
- Configure voice in Android TTS settings
- Supports all Android TTS languages

### High Contrast Mode

**For Low Vision Users:**

**Enable:**
Settings → Accessibility → High contrast mode → On

**Features:**
- Increased contrast ratios
- Larger key labels
- Bold text
- Clear key boundaries

**Combine with:**
- Android's built-in magnification
- Large font sizes
- High contrast system theme

### Large Keys

**Increase Key Size:**
1. Settings → Visual → Key size
2. Choose: Small / Normal / Large / Extra Large

**Combine with:**
- Reduced key spacing for clearer boundaries
- High contrast theme
- Key borders enabled

### Alternative Input Methods

**For users with limited dexterity:**

1. **Sticky Keys**
   - Settings → Accessibility → Sticky keys
   - Shift, Ctrl, Alt stay pressed
   - Press once, then letter
   - No need to hold modifiers

2. **Slow Keys**
   - Settings → Accessibility → Slow keys
   - Delay before key activates
   - Prevents accidental presses
   - Adjustable delay (100ms - 1000ms)

3. **Bounce Keys**
   - Settings → Accessibility → Bounce keys
   - Ignore rapid repeated presses
   - Helps with tremors
   - Configurable interval

---

## Privacy & Security

### Privacy Commitment

**CleverKeys collects ZERO data. Period.**

**What we DON'T collect:**
- ❌ Typing data
- ❌ Usage statistics
- ❌ Crash reports
- ❌ Device information
- ❌ Personal information
- ❌ Analytics of any kind

**Why?**
- Your data is yours alone
- Privacy is a fundamental right
- No servers = no data breaches
- No tracking = no surveillance

### Local-Only Processing

**Everything happens on your device:**

1. **Word Predictions**
   - ONNX models run locally
   - No cloud API calls
   - No internet required

2. **Dictionary**
   - Stored in local database
   - Never synced to cloud
   - Deleted when you uninstall

3. **User Adaptation**
   - Learning happens on-device
   - Your patterns stay private
   - No training data uploaded

4. **Settings**
   - Stored in SharedPreferences
   - Local to your device
   - Never backed up to cloud

### Permissions

**CleverKeys requests ZERO permissions.**

**Standard (automatic) permissions:**
- ✅ Vibration (for haptic feedback)
- ✅ App storage (for settings)

**Optional (user-controlled) permissions:**
- Clipboard access (only if you use clipboard history)
- Accessibility service access (only if you use Switch Access)

**Never requested:**
- ❌ Internet access
- ❌ Location
- ❌ Contacts
- ❌ Camera
- ❌ Microphone
- ❌ Phone state
- ❌ SMS

### Verification

**How to verify our privacy claims:**

1. **Network Monitor**
   - Install NetGuard or AFWall+
   - Monitor network activity
   - Result: Zero packets from CleverKeys

2. **Permission Check**
   - Settings → Apps → CleverKeys → Permissions
   - Result: No sensitive permissions

3. **Source Code Audit**
   - View code on GitHub
   - Search for network code
   - Result: No networking libraries

4. **Packet Capture**
   - Use Wireshark or tcpdump
   - Capture while using CleverKeys
   - Result: No network traffic

### Open Source

**Full Transparency:**
- Source code on GitHub
- GPL-3.0 license
- Anyone can audit
- Reproducible builds

**What this means:**
- No hidden code
- No backdoors
- No telemetry
- Complete trust through transparency

### Security Best Practices

**Using CleverKeys Securely:**

1. **Passwords**
   - CleverKeys is safe for passwords
   - But we recommend password managers
   - Consider using autofill instead

2. **Sensitive Data**
   - CleverKeys doesn't log anything
   - But secure fields may disable predictions
   - This is normal and expected

3. **Device Security**
   - Lock your device (PIN/pattern/biometric)
   - Enable device encryption
   - Keep Android updated

4. **App Security**
   - Download from trusted sources only
   - Verify APK signatures
   - Keep CleverKeys updated

### Compliance

**CleverKeys is compliant with:**

- ✅ **GDPR** (EU General Data Protection Regulation)
- ✅ **CCPA** (California Consumer Privacy Act)
- ✅ **COPPA** (Children's Online Privacy Protection Act)
- ✅ **PIPEDA** (Canada Personal Information Protection)
- ✅ **LGPD** (Brazil Data Protection Law)

**How?**
- No data collection = automatic compliance
- No processing = no violations
- No transfers = no issues

---

## Troubleshooting

### Installation Issues

**Problem: "App not installed"**

**Solutions:**
1. Check available storage (need 52MB free)
2. Enable "Install from Unknown Sources"
3. Uninstall previous version first
4. Clear Google Play Services cache
5. Restart device and try again

**Problem: "Parse error"**

**Solutions:**
1. Download APK again (may be corrupted)
2. Check Android version (need 8.0+)
3. Verify APK signature
4. Use different file manager

### Activation Issues

**Problem: CleverKeys not in keyboard list**

**Solutions:**
1. Verify installation completed
2. Go to Settings → System → Languages & input → Manage keyboards
3. Manually toggle CleverKeys ON
4. Restart device
5. Reinstall if still missing

**Problem: Can't select CleverKeys**

**Solutions:**
1. Enable in Manage Keyboards first
2. Open text field
3. Tap keyboard switcher (🌐 or ⌨️)
4. Select CleverKeys from list
5. If not appearing, restart app

### Typing Issues

**Problem: Keys not responding**

**Solutions:**
1. Check if keyboard is fully loaded (may take 1-2 seconds first time)
2. Restart the app displaying CleverKeys
3. Clear CleverKeys app cache
4. Check for conflicting accessibility services
5. Reinstall CleverKeys

**Problem: Predictions not showing**

**Solutions:**
1. Settings → Input → Word predictions → ON
2. Check if text field allows predictions (some secure fields don't)
3. Verify language is supported
4. Restart keyboard
5. Check dictionary is installed

**Problem: Autocorrect too aggressive**

**Solutions:**
1. Settings → Input → Autocorrection → Adjust sensitivity
2. Or turn OFF if unwanted
3. Add frequently-used words to dictionary
4. Disable specific autocorrections in Disabled Words

### Swipe Typing Issues

**Problem: Swipe not working**

**Solutions:**
1. Settings → Input → Swipe typing → Enabled
2. Check if ONNX models loaded (first launch takes longer)
3. Verify sufficient storage for models
4. Restart keyboard to reload models
5. Check logcat for errors

**Problem: Swipe predictions inaccurate**

**Solutions:**
1. Swipe more deliberately (not too fast)
2. Try circling on double letters (hello: circle on 'l')
3. Add custom words to dictionary
4. Language may not have full model support
5. Check if correct language is selected

### Performance Issues

**Problem: Keyboard laggy/slow**

**Solutions:**
1. Close other apps (free up RAM)
2. Clear app cache: Settings → Apps → CleverKeys → Clear cache
3. Disable animations: Settings → Visual → Reduce animations
4. Check device has enough RAM (need ~150MB)
5. Restart device

**Problem: High battery usage**

**Solutions:**
1. Disable vibration: Settings → Haptic & Sound → Vibration → OFF
2. Reduce key press sounds
3. Disable gesture trails: Settings → Visual → Show swipe trail → OFF
4. Check for background processes (shouldn't be any)

### Crash Issues

**Problem: CleverKeys crashes on open**

**Solutions:**
1. Clear app data: Settings → Apps → CleverKeys → Clear data
2. Reinstall CleverKeys
3. Check Android version compatibility
4. Report crash with logcat:
   ```bash
   adb logcat -d > crash_log.txt
   ```
5. Submit issue on GitHub

**Problem: Keyboard disappears randomly**

**Solutions:**
1. Check if device is low on memory
2. Disable battery optimization for CleverKeys
3. Settings → Apps → CleverKeys → Battery → Unrestricted
4. Check for conflicting apps (other keyboards, accessibility)
5. Restart device

### Language Issues

**Problem: Language not switching**

**Solutions:**
1. Verify language is enabled: Settings → Languages
2. Try long-press spacebar method
3. Check auto-detect is not interfering
4. Manually select language
5. Restart keyboard

**Problem: Wrong language detected**

**Solutions:**
1. Disable auto-detection: Settings → Languages → Auto-detect → OFF
2. Manually select language
3. Type a few more words (detection needs 3-4 words)
4. Add language-specific words to dictionary

### Accessibility Issues

**Problem: Switch Access not working**

**Solutions:**
1. Verify Android Accessibility settings enabled
2. Settings → Accessibility → Switch Access → Enabled
3. Check switch is connected (Bluetooth paired)
4. Test switch with other apps first
5. Adjust scan interval if too fast/slow

**Problem: TalkBack not reading keys**

**Solutions:**
1. Verify TalkBack is enabled in Android
2. Update Android System WebView
3. Clear TalkBack cache
4. Restart both TalkBack and CleverKeys
5. Check CleverKeys is not in TalkBack blocked list

### Getting Help

**If problems persist:**

1. **Check Documentation**
   - Read FAQ.md
   - Review relevant section in this manual
   - Check INSTALLATION_STATUS.md

2. **Generate Diagnostic Report**
   - Run: `./diagnose-issues.sh` (if available)
   - Or collect logcat manually
   - Note: Device model, Android version, CleverKeys version

3. **Report Bug**
   - GitHub Issues: [Repository URL]/issues
   - Include: Steps to reproduce, expected vs actual behavior
   - Attach: Diagnostic report, screenshots (if relevant)

4. **Community Support**
   - GitHub Discussions (coming soon)
   - Reddit: r/CleverKeys (TBD)
   - Email: [Support Email]

---

## Tips & Best Practices

### Typing Efficiently

**Speed Tips:**
1. Use swipe for common words
2. Tap for rare/custom words
3. Trust autocorrect - press Space confidently
4. Learn keyboard shortcuts (Ctrl+C, etc.)
5. Use macros for repeated phrases

**Accuracy Tips:**
1. Add custom words to dictionary immediately
2. Disable unwanted predictions
3. Use precision mode if you have tremors
4. Swipe slower for better accuracy
5. Circle on double letters

### Battery Saving

1. Disable vibration (biggest battery drain)
2. Turn off key press sounds
3. Reduce animation effects
4. Use dark theme (OLED screens)
5. Disable unused extra keys

### Privacy Maximization

1. Verify network monitoring shows zero traffic
2. Disable Android backup for CleverKeys
3. Regularly clear clipboard history
4. Don't sync settings to cloud
5. Review permissions periodically

### Performance Optimization

1. Keep only 2-3 languages active
2. Disable auto-detection if using one language
3. Clear clipboard history weekly
4. Restart keyboard after heavy use
5. Keep system and CleverKeys updated

### Customization Ideas

**For Developers:**
- Extra keys: Tab, Esc, Ctrl, brackets, pipe, backslash
- Layout: Programmer Dvorak or Colemak
- Macros: Code snippets, file paths
- Dark theme for late-night coding

**For Writers:**
- Extra keys: Em-dash, en-dash, smart quotes
- Macros: Common phrases, signatures
- Clipboard history for research snippets
- Large key size for comfortable typing

**For Multi-Language Users:**
- Enable 2-3 most-used languages
- Auto-detection ON
- Spacebar swipe for quick switching
- Language indicator visible

**For Accessibility:**
- Switch Access with Auto Scan
- Large keys + high contrast
- Voice guidance ON
- Sticky keys for modifiers

---

## Technical Reference

### APK Information

- **Package Name**: tribixbite.keyboard2.debug (debug) / tribixbite.keyboard2 (release)
- **Version Name**: 1.0.0
- **Version Code**: 52
- **Min SDK**: 26 (Android 8.0 Oreo)
- **Target SDK**: 34 (Android 14)
- **APK Size**: 52MB

### File Locations

**App Data:**
- Location: `/data/data/tribixbite.keyboard2.debug/`
- Settings: `shared_prefs/CleverKeysConfig.xml`
- Dictionary: `databases/dictionary.db`
- Training data: `databases/training.db`

**Cache:**
- Location: `/data/data/tribixbite.keyboard2.debug/cache/`
- ONNX models: Embedded in APK
- Temporary files: Auto-cleaned

**Backup Location (not auto-backed up):**
- Manual backup: Export settings to file
- Location: User-specified

### Neural Models

**ONNX Runtime:**
- Version: 1.19.2
- Backend: XNNPACK (CPU)
- Precision: FP32

**Encoder Model:**
- Size: 5.3MB
- Layers: Transformer encoder
- Input: Swipe trajectory features

**Decoder Model:**
- Size: 7.2MB
- Layers: Transformer decoder
- Output: Word predictions

**Performance:**
- Latency: 50-200ms (device-dependent)
- Memory: 15-25MB additional
- Accuracy: 94%+ for common words

### System Integration

**Input Method Service:**
- Service: `CleverKeysService`
- Settings Activity: `SettingsActivity`
- Language Settings: null (built-in)

**Accessibility:**
- Accessibility Service: `SwitchAccessService`
- TalkBack: Compatible
- Switch Access: Full support

**System Requirements:**
- RAM: 150MB minimum
- Storage: 52MB installation + cache
- CPU: Any ARM64 or x86_64
- GPU: Optional (CPU fallback)

### Build Information

**Built with:**
- Kotlin: 1.9.20
- Gradle: 8.6
- Android Gradle Plugin: 8.2.0
- Jetpack Compose: 1.5.4
- Material 3: 1.1.2

**Dependencies:**
- ONNX Runtime Android: 1.19.2
- Kotlin Coroutines: 1.7.3
- AndroidX Core: 1.12.0
- Compose Material 3: 1.1.2

### Logging

**Log Tags:**
- `CleverKeys`: Main service
- `Keyboard2View`: Keyboard view
- `OnnxPredictor`: Neural predictions
- `LanguageManager`: Multi-language
- `SwitchAccess`: Accessibility

**View Logs:**
```bash
adb logcat -s CleverKeys
```

**Export Logs:**
```bash
adb logcat -d > cleverkeys_log.txt
```

### Debugging

**Enable Debug Mode:**
1. Settings → Advanced → Debug mode
2. Shows additional logging
3. Performance metrics overlay

**Common Debug Tasks:**
- Check ONNX model loading: `logcat -s OnnxPredictor`
- Monitor performance: `logcat -s Keyboard2View`
- Track crashes: `logcat -s AndroidRuntime`

### Version History

**v1.0.0** (2025-11-16)
- Initial release
- 251 files, 100% feature parity
- 20 languages, 89 layouts
- Neural swipe typing
- Dictionary Manager (3-tab UI)
- Accessibility features
- Privacy-first design

**Planned v1.1**
- Custom emoji picker
- Long-press popup UI
- 50k dictionaries (20 languages)
- Theme customization UI
- Settings export/import
- Cloud backup (encrypted)

---

## Appendix

### Keyboard Shortcuts Reference

| Shortcut | Action |
|----------|--------|
| Ctrl + C | Copy |
| Ctrl + X | Cut |
| Ctrl + V | Paste |
| Ctrl + Z | Undo |
| Ctrl + Y | Redo |
| Ctrl + A | Select All |
| Ctrl + ← | Jump word left |
| Ctrl + → | Jump word right |
| Home | Start of line |
| End | End of line |
| Ctrl + Home | Start of document |
| Ctrl + End | End of document |

### Compose Key Combinations

Common compose sequences:

| Sequence | Result | Name |
|----------|--------|------|
| ' + e | é | E acute |
| ` + e | è | E grave |
| ^ + e | ê | E circumflex |
| " + e | ë | E diaeresis |
| ~ + n | ñ | N tilde |
| ' + a | á | A acute |
| ' + c | ç | C cedilla |
| o + e | œ | OE ligature |
| s + s | ß | German sharp S |
| < + < | « | Left guillemet |
| > + > | » | Right guillemet |

### Supported File Formats

**Layout Files:**
- Format: XML
- Location: `src/main/layouts/`
- Example: `qwerty.xml`

**Dictionary Files:**
- Format: SQLite database
- Location: `databases/dictionary.db`
- Schema: (word TEXT, frequency INTEGER)

**Settings Files:**
- Format: XML (SharedPreferences)
- Location: `shared_prefs/CleverKeysConfig.xml`

### Links & Resources

**Documentation:**
- Quick Start: `00_START_HERE_FIRST.md`
- FAQ: `FAQ.md`
- Privacy Policy: `PRIVACY_POLICY.md`
- Release Notes: `RELEASE_NOTES_v1.0.0.md`

**Development:**
- Project Status: `migrate/project_status.md`
- Bug Tracking: `migrate/todo/`
- Specifications: `docs/specs/`

**Community:**
- GitHub: [Repository URL]
- Issues: [Repository URL]/issues
- Discussions: [Repository URL]/discussions
- Reddit: r/CleverKeys (TBD)

---

**End of User Manual**

**Version**: 1.0.0
**Last Updated**: 2025-11-16
**For Support**: See [FAQ.md](FAQ.md) or [GitHub Issues]

---

*CleverKeys - Modern Android Keyboard with Neural Swipe Typing*
*100% Privacy • 100% Open Source • 100% Local Processing*
