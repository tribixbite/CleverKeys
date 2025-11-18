# CleverKeys Roadmap

**Vision**: The most powerful, privacy-first, accessible Android keyboard

This roadmap outlines planned features and improvements for CleverKeys. All features maintain our core principles: **100% local processing, zero data collection, and complete user control**.

---

## ✅ **v1.0.0 - Genesis** (Released 2025-11-16)

**Theme**: Foundation - Complete Kotlin rewrite with neural intelligence

### Completed Features
- ✅ Complete Kotlin rewrite of Unexpected-Keyboard (~50,000 lines Kotlin)
- ✅ Neural ONNX prediction pipeline (53MB models)
- ✅ Material 3 UI with Jetpack Compose
- ✅ Multi-language support (20 languages)
- ✅ Full accessibility (Switch Access, Mouse Keys, TalkBack)
- ✅ Dictionary Manager (3-tab UI: User | Built-in | Disabled)
- ✅ 100+ keyboard layouts
- ✅ Swipe/gesture typing
- ✅ Clipboard history with pin functionality
- ✅ 100% feature parity with Unexpected-Keyboard
- ✅ Professional documentation (6,600+ lines)
- ✅ Complete open source infrastructure

---

## ✅ **v2.0.0 - Data Portability** (Released 2025-11-18)

**Theme**: Backup & Restore - Complete data export/import system

### Completed Features
- ✅ **Settings Export/Import** - Full configuration backup to JSON
- ✅ **Dictionary Export/Import** - User words + disabled words backup
- ✅ **Clipboard Export/Import** - History with timestamps & pins
- ✅ **Non-destructive Merge** - Import adds to existing data
- ✅ **Screen Size Detection** - Warns on settings import across devices
- ✅ **Import Statistics** - Shows new/skipped counts after import
- ✅ **2 Critical Crash Fixes** - Compose lifecycle + Accessibility
- ✅ **Production Ready** - 98/100 score (Grade A+)
- ✅ **Complete Documentation** - 9,000+ lines across 146 files
- ✅ **All Bugs Resolved** - 45/45 P0/P1 bugs fixed

---

## 🚀 **v2.1.0 - Polish** (Planned Q1 2026)

**Theme**: Refinement - User experience improvements

### Priority Features

#### 🎨 **UI Enhancements** (High Priority)
- **Emoji Picker UI** ⭐ Most requested
  - Visual emoji grid with categories
  - Recently used emojis
  - Emoji search functionality
  - Skin tone selector
  - Quick access from keyboard

- **Long-Press Popup UI** ⭐ Essential UX
  - Visual popup for alternate characters
  - Accented characters for international users
  - Symbol variants
  - Custom PopupWindow implementation

- **Theme Customization UI**
  - Color picker for keyboard themes
  - Preview before applying
  - Save custom themes
  - Import/export themes
  - Light/dark variants

#### 📚 **Enhanced Dictionaries** (Medium Priority)
- **50,000-word Dictionaries** (20 languages)
  - Expanded vocabulary coverage
  - Technical terms and slang
  - Proper noun support
  - Regional variations

- **Dictionary Enhancements** (Beyond v2.0.0 export/import)
  - CSV format support (in addition to JSON)
  - Merge/sync between multiple devices
  - Frequency-based word ranking
  - Dictionary sharing within community

#### ⚡ **Performance Optimizations** (Medium Priority)
- Neural model quantization (reduce size from 52MB to ~25MB)
- Faster prediction loading (< 100ms)
- Reduced memory footprint
- Battery optimization
- Animation smoothness improvements

#### 🌐 **Language Additions** (Low Priority)
- Vietnamese
- Indonesian
- Malay
- Filipino
- More language requests from community

### Bug Fixes
- Address all user-reported issues from v2.0.0
- Performance tuning based on real-world usage
- Edge case handling improvements

---

## 🌟 **v2.2.0 - Aurora** (Planned Q2 2026)

**Theme**: Power - Advanced features for power users

### Planned Features

#### ⌨️ **Advanced Input**
- **Custom Gestures**
  - User-defined swipe patterns
  - Gesture macros (complex actions)
  - Gesture recorder
  - Share gesture sets

- **Text Expansion++**
  - Rich text snippets (formatted)
  - Dynamic variables (date, time, clipboard)
  - Conditional snippets
  - Snippet categories and search

- **Advanced Autocorrect**
  - Context-aware suggestions (ML-enhanced)
  - Learn from corrections
  - Domain-specific vocabularies (medical, legal, tech)
  - Autocorrect sensitivity slider

#### 🎯 **Productivity**
- **Clipboard Manager++**
  - Clipboard categories
  - Sync across local apps
  - Clipboard history search
  - Rich content preview (images, formatted text)

- **Quick Actions**
  - Calculator in keyboard
  - Unit converter
  - Currency converter (offline rates)
  - Quick notes
  - URL shortener (local)

#### 🔧 **Customization**
- **Layout Editor UI**
  - Visual layout designer
  - Drag-and-drop key placement
  - Custom key labels and actions
  - Save and share layouts

- **Advanced Settings**
  - Per-app keyboard settings
  - Typing statistics (local only)
  - Keyboard size adjustment
  - Key spacing customization

---

## 🚀 **v2.0.0 - Nova** (Planned Q3-Q4 2026)

**Theme**: Intelligence - Next-generation AI features

### Major Features

#### 🧠 **Enhanced Neural Intelligence**
- **Contextual Predictions**
  - Sentence-level context awareness
  - Multi-word predictions
  - Smart next-word suggestions
  - Writing style adaptation

- **Offline Language Models** (optional downloads)
  - GPT-style text completion (local ONNX models)
  - Grammar checking (local)
  - Spelling suggestions (ML-enhanced)
  - Style recommendations

- **Adaptive Learning**
  - Personal writing style learning
  - Vocabulary expansion from usage
  - Error pattern recognition
  - Custom prediction weights

#### 🎨 **Next-Gen UI**
- **Floating Keyboard Mode**
  - Resizable, movable keyboard
  - One-handed mode improvements
  - Split keyboard for tablets
  - Picture-in-picture keyboard

- **Dynamic Layouts**
  - Context-aware layouts (email, coding, messaging)
  - App-specific optimizations
  - Smart key suggestions based on context

#### 🌐 **Advanced Multi-Language**
- **Real-time Translation** (offline models)
  - Translate as you type (optional)
  - Bilingual keyboard mode
  - Language detection improvements

- **Code Input Mode**
  - Syntax highlighting (if possible in IME)
  - Code completion
  - Bracket matching
  - Language-specific layouts (Python, Java, etc.)

---

## 🔮 **Future Considerations** (v2.1+)

### Under Exploration

#### 🎙️ **Voice & Speech**
- **Offline Voice Typing**
  - Local speech-to-text (privacy-first)
  - Multiple language support
  - Punctuation commands
  - Voice editing commands

- **Accessibility++**
  - Eye tracking support
  - Head tracking input
  - Morse code input
  - Brain-computer interface research

#### 🔌 **Plugin System**
- **Third-party Extensions**
  - SDK for custom features
  - Theme plugin system
  - Language pack plugins
  - Custom input method plugins
  - Sandboxed execution (privacy-safe)

#### 📱 **Platform Expansion**
- **Tablet Optimization**
  - Split keyboard
  - Floating mode
  - Stylus input
  - Multi-finger gestures

- **Foldable Device Support**
  - Adaptive layouts for different orientations
  - Flex mode keyboard
  - Dual-screen optimizations

---

## 🎯 **Long-Term Vision** (2027+)

### Strategic Goals

1. **Most Private Keyboard**
   - Zero telemetry, always
   - Fully auditable code
   - Security certifications
   - Privacy advocacy

2. **Most Accessible Keyboard**
   - WCAG AAA compliance
   - Support for all disabilities
   - Universal design principles
   - Accessibility research partnerships

3. **Most Powerful Keyboard**
   - AI-powered (100% local)
   - Highly customizable
   - Developer-friendly
   - Power-user features

4. **Most Intelligent Keyboard**
   - On-device LLMs (optional)
   - Advanced context understanding
   - Predictive writing assistance
   - Multi-modal input (text, voice, gestures)

5. **Best Open Source Keyboard**
   - Thriving community
   - Regular contributions
   - Transparent development
   - Educational resource

---

## 📊 **Community Priorities**

Features will be prioritized based on:

1. **User Requests** (GitHub Discussions votes)
2. **Privacy Impact** (must maintain zero data collection)
3. **Accessibility Benefits** (prioritize inclusive features)
4. **Technical Feasibility** (available on-device technology)
5. **Resource Availability** (development time, testing)

**Have a feature idea?** Open a discussion on GitHub!
- [Feature Requests](https://github.com/OWNER/cleverkeys/discussions/categories/ideas)
- [Vote on Existing Ideas](https://github.com/OWNER/cleverkeys/discussions)

---

## 🛠️ **Technical Roadmap**

### Architecture Evolution

#### v1.x Focus
- Kotlin coroutines and Flow optimization
- Material 3 component maturity
- ONNX model improvements
- Accessibility infrastructure

#### v2.x Focus
- Advanced ML model integration
- Plugin architecture
- Performance optimization
- Cross-platform considerations

### Technology Adoption

**Investigating**:
- Kotlin Multiplatform (for future desktop support)
- Jetpack Compose multiplatform
- WebAssembly for models (ONNX.js)
- Federated learning (privacy-preserving ML)

**Committed to**:
- 100% Kotlin (no Java code)
- ONNX for ML (standardized)
- Material 3 Design
- Accessibility-first development

---

## 📈 **Success Metrics**

### v2.1 Goals
- [ ] 10,000+ downloads
- [ ] >4.5 star rating on Play Store
- [ ] <5 bug reports per 1,000 users
- [ ] 50+ GitHub stars
- [ ] 10+ community contributors

### v2.2 Goals
- [ ] 50,000+ downloads
- [ ] Featured on Android Authority
- [ ] 100+ GitHub stars
- [ ] Active community (25+ contributors)
- [ ] Accessibility recognition

### v3.0 Goals
- [ ] 100,000+ downloads
- [ ] Top keyboard mentions in tech media
- [ ] 200+ GitHub stars
- [ ] 50+ active contributors
- [ ] Award recognition

### Long-term Goals
- [ ] 1,000,000+ downloads
- [ ] Top 10 keyboard on Play Store
- [ ] Industry standard for privacy
- [ ] Research citations (academic papers)
- [ ] 1,000+ GitHub stars

---

## 🤝 **How to Contribute**

Want to help shape CleverKeys? Here's how:

1. **Feature Requests**: [GitHub Discussions](https://github.com/OWNER/cleverkeys/discussions)
2. **Code Contributions**: See [CONTRIBUTING.md](CONTRIBUTING.md)
3. **Testing**: Join beta program (to be announced)
4. **Documentation**: Help improve guides and translations
5. **Community**: Answer questions, help other users

---

## 📅 **Release Schedule**

**Major Releases**: Every ~3-6 months
- ✅ v1.0.0: November 2025 (Released)
- ✅ v2.0.0: November 2025 (Released)
- v2.1.0: Q1 2026 (March)
- v2.2.0: Q2 2026 (June)
- v3.0.0: Q4 2026 or later

**Minor Releases**: Quarterly
- Bug fixes and small improvements
- Performance optimizations
- Security updates

**Patch Releases**: As needed
- Critical bug fixes
- Security patches
- Hotfixes

---

## ⚠️ **Disclaimer**

This roadmap is **aspirational** and subject to change based on:
- Community feedback
- Development resources
- Technical feasibility
- Privacy considerations
- Market conditions

Features may be:
- Added to earlier versions
- Deferred to later versions
- Replaced with better alternatives
- Cancelled if not feasible

**Principle**: We will never compromise on privacy or accessibility to meet a deadline.

---

## 📞 **Feedback**

Have thoughts on this roadmap?
- 💬 [Discuss on GitHub](https://github.com/OWNER/cleverkeys/discussions)
- 🐛 [Report Issues](https://github.com/OWNER/cleverkeys/issues)
- 💡 [Suggest Features](https://github.com/OWNER/cleverkeys/discussions/categories/ideas)

---

**Last Updated**: 2025-11-17
**Version**: 1.0 (for CleverKeys v1.0.0)
**Status**: Public Draft

🧠 **Think Faster** • ⌨️ **Type Smarter** • 🔒 **Stay Private** • 🚀 **Future Forward**
