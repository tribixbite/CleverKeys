# CleverKeys TODO

## Completed (2026-01-22)
- ✅ Emoji long-press tooltip (PopupWindow, positioned above pressed emoji)
- ✅ Emoji name lookup with Unicode fallback for unmapped emojis
- ✅ Emoticon search keywords (shrug, lenny, tableflip, kaomoji, etc)
- ✅ Emoticon grid overlap fix (padding, minHeight, grid spacing)
- ✅ Flag emoji name mappings (260+ countries, shows "japan" not "regional indicator")
- ✅ Emoticon name mappings (100+ smileys/kaomoji, shows "shrug" not "emoticon")
- ✅ Cleanup: removed debug logs from KeyboardReceiver, unused tooltip XML files
- ✅ Updated wiki (emoji.md) and spec (suggestion-bar-content-pane.md)
- ✅ Created skills: emoji-panel.md, content-pane-layout.md
- ✅ Created release-process.md skill (F-Droid API, fastlane changelogs, version codes)
- ✅ Released v1.2.6 - Emoji Panel Polish (tooltip, name mappings, gap fixes)
- ✅ Fixed clipboard history limit slider (0-500, 0 = unlimited) (#85)
- ✅ GitHub issues triage (memory/issue-triage-2026-01-22.md)
- ✅ Clipboard tabs: History (📋), Pinned (📌), Todos (✓) with icon-only UI
- ✅ Close buttons for emoji/clipboard panes (#80)
- ✅ Database migration v2: added is_todo column
- ✅ Backwards-compatible todo export/import (export_version 2)
- ✅ Fixed emoji close button triggering wrong event
- ✅ Clipboard pagination (100 items per page, search all items)
- ✅ Fixed clipboard import (fresh expiry, correct count indices)
- ✅ Option to disable auto-space after suggestion (#82)
- ✅ Separate backspace key repeat option (#81)
- ✅ Respect Greek/Math disabled in numeric layer (#77)
- ✅ Password manager clipboard exclusions: KeePassDX Libre, Chrome, Edge, Firefox (#86)
- ✅ Android 13+ IS_SENSITIVE flag support (user setting, defaults on) (#86)
- ✅ Created ew-cli testing skill (.claude/skills/ew-cli-testing.md)
- ✅ Settings toggles now update Config immediately (fixes #81, #82 taking effect)
- ✅ Added SettingsToggleTest for #81, #82, #86 verification
- ✅ Fixed auto-space after tap suggestion (#82) - bug was in SuggestionHandler.kt
- ✅ Fixed autocapitalization toggle not updating Config immediately
- ✅ Added missing "Capitalize I Words" toggle UI (#72)
- ✅ Fixed swipe capitalization after period (_mods not updated in set_shift_state)
- ✅ Debug settings defaults: show_raw_output and show_raw_beam_predictions now OFF
- ✅ Fixed raw predictions appearing at front of suggestions (now stay at end)
- ✅ Added SettingsToggleTest tests for debug defaults, autocapitalization, I-words
- ✅ All ew-cli instrumented tests passing
- ✅ Fixed swipe predictions not applying user word case preservation (proper nouns)
- ✅ Created wiki-documentation.md skill for consistent docs
- ✅ Created user-dictionary wiki + spec pair (proper noun case preservation)
- ✅ Fixed Android user dictionary case preservation (both sync/async loading paths)
- ✅ Test keyboard fields use KeyboardCapitalization.Sentences (splash + settings)
- ✅ Splash screen animation pauses when keyboard opens (eliminates input lag)
- ✅ Smart punctuation respects manual spacebar (only attaches if space was auto-inserted)
- ✅ Created smart punctuation wiki + spec pair (user guide + technical spec)
- ✅ Fixed swipe capitalization at swipe START (captures shift state when swipe begins)
- ✅ Smart punct adds space after sentence-ending punct (. ! ?) for autocap trigger

## In Progress
- 🔄 Build and test v1.2.8 features

## Completed (2026-01-20)
- ✅ Swedish language pack (sv_enhanced.bin, sv.bin, sv_unigrams.txt)
- ✅ Emoticons category in emoji picker (#76) - 119 text emoticons
- ✅ Tests for resolved issues (#41 emoji search, #71 clipboard, #72 I-words)
- ✅ Tests for swipe sensitivity presets (Low/Medium/High/Custom)
- ✅ Emoticons display fix (length-based text scaling for kaomoji)
- ✅ Emoji/clipboard toggle behavior (tap to open/close)
- ✅ Gap fix: ViewFlipper swaps suggestion bar/content pane with dynamic height
- ✅ App-switch reset: Content pane state resets when keyboard hides

## Code TODOs

### SettingsActivity.kt:3434
**Error Reports toggle has no backend**
- Toggle is hidden in UI
- Need async file logging implementation
- Low priority

---

## Investigations

### English Words in French-Only Mode
- **Issue**: English words appear when Primary=French, Secondary=None
- **Diagnostic**: Logging added in commit 83ea45f7
- **Debug**: `adb logcat | grep "getVocabularyTrie\|loadPrimaryDictionary"`
- **Files**: `OptimizedVocabulary.kt`, `SwipePredictorOrchestrator.kt`

### Long Word Prediction
- **Fix applied**: Length normalization in beam search (22fc3279)
- **Verify**: Swipe "dangerously" in SwipeDebugActivity
- **Check**: Confidence values are length-normalized

---

## Features

### Settings UI Polish
- [ ] Standardize units across distance settings (px vs dp vs %)
- [ ] Move Vibration settings to Accessibility section
- [ ] Move Smart Punctuation to Auto-Correction section

### Web Demo P2
- [ ] Lazy loading for 12.5MB models
- [ ] PWA/Service Worker for offline support

### Legacy Code Cleanup
- [ ] Migrate deprecated `android.preference.*` to `androidx.preference.*`

### Documentation
- [ ] Consolidate duplicate specs (top-level → wiki/specs)
- [ ] See `docs/DOCS_AUDIT.md` for full analysis

---

## Reference

### Build
```bash
./build-on-termux.sh          # Full build + install
./gradlew compileDebugKotlin  # Compilation check
```

### Test
```bash
# Instrumented (emulator.wtf)
ew-cli --app build/outputs/apk/debug/CleverKeys-v1.2.5-x86_64.apk \
       --test build/outputs/apk/androidTest/debug/CleverKeys-debug-androidTest.apk \
       --device model=Pixel7,version=35 --use-orchestrator --clear-package-data

# Local JVM
./scripts/run-pure-tests.sh
```

### Key Files
| Purpose | File |
|---------|------|
| Settings | `Config.kt` |
| Settings UI | `SettingsActivity.kt` |
| Swipe prediction | `SwipePredictorOrchestrator.kt` |
| Word prediction | `WordPredictor.kt` |
| Gesture handling | `Pointers.kt` |

---

*Archive: `memory/archive/todo/`*
*Docs: `docs/TABLE_OF_CONTENTS.md`*
