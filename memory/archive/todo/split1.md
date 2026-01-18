# CleverKeys Working TODO List

**Last Updated**: 2026-01-17
**Status**: v1.2.11 - Bug fixes and Issue #71, #72

---

## Session Progress (2026-01-17)

### Autocapitalisation Fix
- ✅ Fixed: Auto-capitalize not working in some text fields (3ef810ca)
- **Issue**: `capsMode` only checked for `CAP_MODE_SENTENCES`, missing `CAP_MODE_WORDS`
- **Fix**: Changed to use `SUPPORTED_CAPS_MODES` which checks both sentence and word capitalization
- **Cause**: Some text fields (like search bars) specify CAP_WORDS but not CAP_SENTENCES

### Issue #71: Clipboard TransactionTooLargeException
- ✅ Fixed: Prevent crashes with large clipboard histories (17203125)
- **Issue**: Clipboard with large items (2.2MB HTML file) could exceed Android Binder ~1MB limit
- **Fix**:
  - Added `MAX_DISPLAY_ENTRIES` constant (100) to limit clipboard list IPC size
  - Updated default limits: 50 entries max, 256KB max item size, 5MB total
  - Added try-catch protection in `clearExpiredAndGetHistory()`
  - Entries truncated for display only; full content preserved for paste

### Issue #72: Auto-Capitalize "I" Words + Proper Noun Support
- ✅ Implemented: Automatically capitalize "I" and contractions (a16a95f5)
- **Feature**: Auto-capitalizes "i", "i'm", "i'll", "i'd", "i've" when typing or swiping
- **Implementation**:
  - Added `AUTOCAPITALIZE_I_WORDS` default and `autocapitalize_i_words` setting
  - Added `capitalizeIWord()` helper in SuggestionHandler
  - Applied in: prediction transforms, suggestion selection, word completion
- **Works independently**: Capitalizes even if autocorrect is disabled
- ✅ Fixed: Autocorrect now preserves capitalization (29dd10e6)
  - "Teh" → "The" (not "the"), "TEH" → "THE"
  - Added `preserveCapitalization()` helper
- ✅ Fixed: Proper noun case preserved in user dictionary (05050b47)
  - **Root cause**: `loadCustomAndUserWords()` lowercased all words for dictionary lookup
  - **Fix**: Added `userWordOriginalCase` map in WordPredictor to track original case
  - When user adds "Boston", stored as "Boston", predicted as "Boston"
  - Added `applyUserWordCase()` and `applyUserWordCaseToList()` helpers
  - Applied to prediction output in `predictWordsWithContext()`
- ✅ Fixed: Swipe I-words now capitalize (5d18e039)
  - **Issue**: Swiping "im" inserted "i'm" instead of "I'm"
  - **Fix**: Added `capitalizeIWord()` to `InputCoordinator.onSuggestionSelected()`
  - Applied after autocorrect to handle both direct and corrected words
- ✅ Fixed: Add-to-dictionary prompts preserve case (5d18e039)
  - **Issue**: Typing "Boston" showed "+boston" and "add boston to dictionary?"
  - **Root cause**: `PredictionContextTracker.synchronizeWithCursor()` used normalized (lowercase) prefix
  - **Fix**: Changed to use `rawPrefix`/`rawSuffix` to preserve original case
  - Prediction lookups still work via internal normalization in WordPredictor

### Settings Search Coverage Expansion
- ✅ Expanded: Settings search from 38 to ~120 entries (fd6c7747)
- **Issue**: Only ~16% of settings were searchable (38 of 150+ settings)
- **Fix**: Added comprehensive search mappings for all categories:
  - Activities (9 entries)
  - Neural Prediction (6 entries)
  - Word Prediction & Autocorrect (10 entries)
  - Appearance (16 entries)
  - Swipe Trail (5 entries)
  - Input Behavior (7 entries)
  - Gesture Tuning (18 entries)
  - Accessibility & Haptics (8 entries)
  - Clipboard (6 entries)
  - Multi-Language (7 entries)
  - Privacy (3 entries)
  - Advanced (4 entries)
- **Result**: Search coverage increased to ~80%

### Code Review (v1.2.5 → HEAD)
- Reviewed 39 changed Kotlin files, 3000+ lines added
- No critical issues found
- Minor TODOs noted:
  - `EmojiGridView.kt:43` - `migrateOldPrefs()` removal (future cleanup)
  - `MultiLanguageManager.kt:102` - Phase 8.2 language dictionaries (planned)
- Emoji search architecture well-designed: lazy background loading, Trie structure

---

## Session Progress (2026-01-16)

### White Navbar Icons on Android 8-9 (#1116)
- ✅ Fixed: White navigation buttons now visible on white/light themes (5bc83f97)
- **Issue**: On Android 9 with white theme, navbar icons were white on white (invisible)
- **Fix**: `Keyboard2View.kt:refresh_navigation_bar()` now explicitly sets `SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR`
  for API 26-28 when using light themes, ensuring dark icons on light backgrounds
- **Cause**: WindowInsetsController compat shim doesn't work reliably for IME windows on older APIs

### Space Key Selection Behavior (#1142)
- ✅ Fixed: Space key now types space when text selected (4b5e016f)
- **Issue**: Pressing space while text selected would cancel selection (Esc behavior)
- **Fix**: Modified `KeyModifier.kt:applySelectionMode()` to let character keys pass through
- **Result**: Space (and all characters) now replace selected text, matching standard keyboard behavior

### Emoji Search by Name (#41)
- ✅ v1: Initial implementation with EditText in emoji pane (633d6548)
- ✅ v2: Redesigned to use suggestion bar instead (2e3f416b)
- ✅ v3: Expanded emoji search coverage with 500+ name mappings (252d72a6)
- ✅ v4: Complete UI redesign with visible EditText (b9b12f08)
- ✅ v5: Fixed text routing - IME can't type into own views (71fa531e)
  - **Root cause**: EditText inside IME view hierarchy, but typing goes to app's InputConnection
  - **Fix**: Re-added IReceiver routing to programmatically update EditText
  - Added `appendToSearch(text)` and `backspaceSearch()` methods to EmojiSearchManager
  - Added `isEmojiPaneOpen()`, `appendToEmojiSearch()`, `backspaceEmojiSearch()` to IReceiver
  - Fixed clear button visibility: added `android:tint` for dark theme support
- ✅ v6: Fixed searchActive flag routing (2cae0d25)
  - **Root cause**: `isEmojiPaneOpen()` checked `isInitialized && searchInput != null` which didn't track pane state
  - **Fix**: Mirrored ClipboardManager.searchMode pattern with simple `searchActive` boolean
  - Set `searchActive = true` in `onPaneOpened()`, `false` in `onPaneClosed()`
  - `isEmojiPaneOpen()` now returns `searchActive` flag
- ✅ v7: Fixed KeyEventReceiverBridge missing emoji methods (d3806bb5)
  - **Root cause**: `KeyEventReceiverBridge` had clipboard search delegation but NOT emoji search
  - Missing: `isEmojiPaneOpen()`, `appendToEmojiSearch()`, `backspaceEmojiSearch()`
  - Bridge fell back to `IReceiver` defaults (always `false`/no-op)
  - **Fix**: Added all three emoji search methods to bridge
  - Also: Always focus search input on pane open (e243be9a)
- **Architecture (v7)**:
  - EditText shows query visually but receives input programmatically (not via IME's InputConnection)
  - Full routing chain: KeyEventHandler → KeyEventReceiverBridge → KeyboardReceiver → EmojiSearchManager
  - Bridge pattern required because KeyEventHandler created before KeyboardReceiver
  - TextWatcher on EditText handles search as text changes
  - `searchActive` flag tracks pane visibility (same pattern as clipboard search)
- ✅ v8: Fixed emoji tap routing and app switch issues
  - **Issue 1**: Tapping emoji added to search instead of inserting into app
  - **Fix**: Added `onEmojiSelected()` / `onEmojiInserted()` to temporarily disable search routing
  - **Issue 2**: Switching apps while emoji picker open broke keyboard
  - **Fix**: Call `onPaneClosed()` in `onFinishInputView()` to reset searchActive flag
- ✅ v9: Comprehensive emoji keyword index (9,800+ keywords)
  - Created `EmojiKeywordIndex.kt` - Trie-based lazy-loaded index
  - Sources: Discord/Twemoji, Slack, GitHub, Google Noto, CLDR
  - Created `tools/generate_emoji_tsv.js` to generate keyword data
  - Created `assets/emoji_keywords.tsv` (314KB, 9,833 keywords → 26,713 mappings)
  - Background loading on IO thread (doesn't block keyboard startup)
  - Prefix matching: "fi" finds fire, fireworks, fish, etc.
- ✅ v10: UI improvements (close button, divider, long-press name) (0d7779f5, e9703c62)
  - Added close button (down arrow icon) to dismiss emoji pane
  - Added visual divider between category buttons and emoji grid
  - Added long-press handler to show emoji name in suggestion bar
  - Added `Emoji.getEmojiName()` reverse lookup (emoji → name)
  - Fixed: Toast suppressed on Android 13+ IME, uses `showSuggestionBarMessage()` instead
- ✅ DRY refactor: Toast → suggestion bar in IME context (c5f6f6e7)
  - Refactored `showNoTextSelectedToast()` → `showNoTextSelectedMessage()`
  - Replaced 7 Toast.makeText() calls with `_keyboard2?.showSuggestionBarMessage()`
  - Removed unused Toast import from Keyboard2View.kt
  - Kept Toast in non-IME contexts (SettingsActivity, etc.)
- **Fuzzy matching**: Case-insensitive partial name matching via Emoji.searchByName()
- **Emoji.kt initNameMap()**: 500+ entries covering all major categories

### Swipe on Password Fields (#39)
- ✅ Added option to enable swipe typing on password fields
- **Config.kt**: Added `SWIPE_ON_PASSWORD_FIELDS` default (false) and `swipe_on_password_fields` field
- **SuggestionBar.kt**: Added `allowSwipeInPasswordMode` flag and `setAllowSwipeInPasswordMode()` method
- **SuggestionHandler.kt**: Modified `handlePredictionResults()` to allow swipe in password mode when enabled
- **CleverKeysService.kt**: Wire up setting from config when entering password field
- **SettingsActivity.kt**: Added UI toggle in Neural Prediction section
- **BackupRestoreManager.kt**: Added setting for backup/restore

### Vibration Toggle Fix (#46)
- ✅ Fixed vibration feedback toggle not working (ef7369a0)
- Issue: Settings saved to "vibration_enabled" but Config read "vibrate_custom"
- Added `haptic_enabled` master toggle in Config.kt
- VibratorCompat now checks master toggle before any haptic feedback
- Fixed SettingsActivity defaults from VIBRATE_CUSTOM to HAPTIC_ENABLED
- Added to BackupRestoreManager for backup/restore support

### Numpad Scaling Fix (#58)
- ✅ Fixed "keyboard for number input has same size as letter input" complaint
- **Horizontal scaling** (be23db40): Removed explicit `width="6.0"` from pin.xml
  - Layout auto-computes to 5.0 units (4 keys + 1 shift margin)
  - Keys are now 20% wider (eliminating right-side padding)
- **Vertical scaling** (74580c22): Added `scale_numpad_height` setting
  - When enabled and `bottom_row=false`, uses actual `keysHeight` as divisor
  - Rows scale up to fill full keyboard height for easier tapping
  - Theme.kt modified row_height calculation logic

### Settings Theme + Intent Automation (9213de83)
- ✅ Fixed #35: Settings now follow system dark/light mode
  - Removed hardcoded `darkTheme=true` from 6 activities
  - LauncherActivity intentionally stays dark (matrix aesthetic)
- ✅ Fixed #70: Programmatic backup/restore via Intent
  - 6 Intent actions: EXPORT_SETTINGS, IMPORT_SETTINGS, EXPORT_DICTIONARIES,
    IMPORT_DICTIONARIES, EXPORT_CLIPBOARD, IMPORT_CLIPBOARD
  - Usage: `am start -a tribixbite.cleverkeys.action.EXPORT_SETTINGS -d file:///path/to/backup.json`

### Greek Language Pack + Keyboard Command Routing (0fb56dc0)
- ✅ Created Greek (Ελληνικά) language pack (langpack-el.zip, 46k words, 632KB)
- ✅ Improved CustomShortSwipeExecutor command routing (#30)
  - Keyboard-level commands (config, switch_clipboard, switch_numeric, voice_typing,
    timestamp_*, etc.) now properly route through KeyValue-based handling
  - Fixed: per-key actions for keyboard events now work correctly

### Timestamp UI Commands + Language Packs (ba53b955)
- ✅ Added timestamp commands to CommandRegistry for short swipe UI
  - 8 timestamp formats: date, time, datetime, time_seconds, date_short, date_long, time_12h, iso
  - Users can now assign timestamp insertion to any key's short swipe gesture
- ✅ Created Turkish language pack (langpack-tr.zip, 50k words, 567KB)
- ✅ Created Swedish language pack (langpack-sv.zip, 50k words, 582KB)
  - Both packs include unigrams for language detection
  - Install via Settings > Multi-Language

### Tap-to-Add Dictionary Feature (#42)
- ✅ Implemented tap-to-add-to-dictionary during typing (7c4054d4)
- When typing an unknown word, shows exact typed string with "+" prefix as last suggestion
- Tapping commits the word, adds to user dictionary, and inserts trailing space
- **Config.kt**: Added `show_exact_typed_word` setting (default: true)
- **SuggestionBar.kt**: Added `exact_add:` prefix handling with bold italic styling
- **SuggestionHandler.kt**: Added `handleExactWordAdd()` function and modified
  `updatePredictionsForCurrentWord()` to include exact typed word when:
  - Setting enabled AND word length >= 2
  - Word not already in predictions
  - Word not in user dictionary or main dictionary

### Specs Rewrite for LLM Agents
- ✅ Rewrote 25 docs/specs files for LLM coding agent consumption
- ✅ Removed: dates, TODOs, sprints, bugs, verification checklists
- ✅ Kept: architecture diagrams, code examples, config tables, key files
- ✅ Deleted 6 obsolete meta files (CORE_LOGIC_REFACTORING_PLAN, TEST_STATUS_REPORT, test-suite, ui-material3-modernization, web_demo_flaws v1/v2)
- ✅ Net result: -3933 lines removed (f697f528, 3603fc01, 43fc91ee, 832dd0b2)

### Wiki Documentation Audit & Fixes
- ✅ Fixed clipboard-history.md: removed non-existent "paste key", fixed access method
- ✅ Fixed themes.md: removed non-existent "System" theme, corrected theme names
- ✅ Fixed first-time-setup.md: corrected all Settings navigation paths
- ✅ Fixed switching-layouts.md: removed non-existent "globe key"
- ✅ Fixed command-palette.md: clarified it only exists in per-key customization
- ✅ Fixed haptics.md: corrected to show only 5 actual haptic events (not 12+)
- ✅ Fixed neural-settings.md: corrected beam width default (6, not 5)
- ✅ Fixed adding-layouts.md: removed non-existent visual editor, QR code features
- ✅ Fixed multi-language.md: accurate description of dual-dictionary system
- ✅ Regenerated wiki HTML with all corrections (4e1afd78, 93cbfb27)

### Earlier Today
- ✅ Updated CHANGELOG.md with v1.2.4, v1.2.8, v1.2.9 releases (315d8662)
- ✅ Regenerated wiki pages with improved generator (37 pages)
- ✅ Fixed wiki-config.json to match existing content files
- ✅ Updated wiki generator with better styling and navigation
- ✅ Simplified macro delay logic per #1108 (e55629ed)
- ✅ Added timestamp-keys wiki page (91d611d7)
- ✅ Updated ROADMAP.md with v1.2.4-v1.2.9 features (474f84ac)
- ✅ Added command-palette to wiki TABLE_OF_CONTENTS (b9e67308)
- ✅ Updated first-time-setup with test keyboard tip (546c54a0)
- ✅ Regenerated wiki HTML pages (7f9c92de)
- ✅ Updated specs README with v1.2.x status (136325a8)
- ✅ Updated README.md with v1.2.x features and documentation section (b68b8b19)
- ✅ Added quick-settings-tile and clipboard-privacy to specs HTML (457644ef)
- ✅ Added Android version fixes and test keyboard to troubleshooting (680f3ea8)
- ✅ Improved circumflex and accents documentation for all layouts (935dd25b)
- ✅ Fixed GitHub repo references to tribixbite/CleverKeys (026dd719)
- ✅ Added transparent background fix to troubleshooting for #51 (dbc91c2b)
- ✅ Added older device crash troubleshooting for #55 (b9b02277)
- ✅ Fixed build_all_languages.py helper script validation for #67 (e20cc275)
- ✅ Regenerated common-issues HTML (5126256a)
- ✅ Updated copyright year to 2026 (afa2c49c)
- ✅ Added custom language pack creation guide for #50/#49 (72411d3d)

**Recent Commits**:
- `8f9d9249` docs: add AZERTY dead key instructions for circumflex (#1130)
- `8c9770a6` docs: update emoji spec with search architecture (#41)
- `d9a2a3f1` docs: update emoji wiki with new search feature (#41)
- `5bc83f97` fix: white navbar icons on Android 8-9 with light themes (#1116)
- `4b5e016f` fix: space key now types space when text selected (#1142)
- `252d72a6` feat: expand emoji search to 500+ name mappings (#41)
- `2e3f416b` feat: redesign emoji search to use suggestion bar (#41)
- `9213de83` feat: settings follow system theme + Intent automation (#35, #70)
- `0fb56dc0` feat: add Greek language pack + improve keyboard command routing (#68, #30)
- `a9dbc7fb` fix: add TIMESTAMP case to CommandPaletteDialog when expression
- `ba53b955` feat: add timestamp commands to UI + Turkish/Swedish language packs
- `7c4054d4` feat: add tap-to-add-to-dictionary for exact typed words (#42)
- `74580c22` feat: add numpad height scaling for larger keys (#58)
- `be23db40` fix: enlarge PIN keyboard keys by 20% (#58)
- `ef7369a0` fix: vibration feedback toggle now properly disables all haptics (#46)
- `f697f528` docs(specs): complete spec rewrite for LLM agents
- `3603fc01` docs(specs): rewrite 4 more specs for LLM agent audience
- `43fc91ee` docs(specs): rewrite layout, neural, dictionary, termux specs
- `832dd0b2` docs(specs): rewrite 4 more specs for LLM agent audience
- `93cbfb27` chore: regenerate wiki HTML with corrected documentation
- `4e1afd78` fix: correct wiki documentation errors
- `72411d3d` docs: add custom language pack creation instructions to wiki
- `afa2c49c` chore: update copyright year to 2026
- `5126256a` docs: regenerate common-issues HTML with older device fix
- `e20cc275` fix: add helper script validation to build_all_languages.py (#67)
- `b9b02277` docs: add troubleshooting for older device crashes (#55)
- `dbc91c2b` docs: add transparent background fix to troubleshooting (#51)
- `026dd719` fix: correct GitHub repo references to tribixbite/CleverKeys
- `935dd25b` docs: improve circumflex and accents documentation
- `680f3ea8` docs: add Android version fixes and test keyboard to troubleshooting
- `457644ef` docs: add quick-settings-tile and clipboard-privacy to specs
- `b68b8b19` docs: add v1.2.x features and documentation section to README
- `136325a8` docs: update specs README with v1.2.x status
- `7f9c92de` docs: regenerate wiki HTML pages
- `546c54a0` docs: mention test keyboard field in first-time-setup guide
- `b9e67308` docs: add command-palette to wiki table of contents
- `474f84ac` docs: update ROADMAP with v1.2.4-v1.2.9 features
- `91d611d7` docs: add timestamp-keys wiki page
- `e55629ed` fix: always apply delay between macro keys (#1108)
- `722cfb9a` docs: update wiki generator and regenerate 37 pages
- `315d8662` docs: update CHANGELOG with v1.2.4, v1.2.8, v1.2.9 releases

---

## GitHub Issue Triage (2026-01-16)

**CleverKeys Issues (tribixbite/CleverKeys)**:
- #51 ✅ Transparent background - Added troubleshooting guide (opacity setting)
- #55 ✅ Crashes on older devices - Added troubleshooting guide (disable swipe typing)
- #56 ✅ First-time user tutorial - Wiki documentation available
- #59 ✅ Clipboard delete option - IMPLEMENTED (v1.2.8)
- #62 ✅ Password manager clipboard exclusion - IMPLEMENTED (v1.2.8)
- #67 ✅ Script error - Fixed: added helper script validation
- #46 ✅ Vibration feedback toggle - FIXED: master haptic toggle now works (ef7369a0)

**Open CleverKeys Issues** (commented with guidance):
- #61 Active multi-language switching - Feature request, discussed implementation path
- #52 MessageEase layout contribution - Provided gesture tuning tips, offered to add layout

**Recently Fixed**:
- #70 ✅ Programmatic Intent automation - Added 6 backup/restore Intent actions (9213de83)
- #35 ✅ Settings dark mode - Now follows system theme (9213de83)
- #68 ✅ Greek language support - CREATED langpack-el.zip (0fb56dc0)
- #50 ✅ Swedish language support - CREATED langpack-sv.zip (ba53b955)
- #49 ✅ Turkish language support - CREATED langpack-tr.zip (ba53b955)
- #30 ✅ Per-key keyboard events - FIXED routing in CustomShortSwipeExecutor (0fb56dc0)
- #42 ✅ Tap-to-add dictionary - FIXED (7c4054d4)
  - Shows exact typed word with "+" prefix when unknown
  - Tapping commits and adds to user dictionary
- #58 ✅ Scaling number keyboard - FIXED (be23db40, 74580c22)
  - Horizontal: PIN keys 20% wider via auto-width calculation
  - Vertical: numpad rows scale to fill keyboard height

**Already Implemented (needs user documentation)**:
- #48 ✅ Password Manager autofill - ALREADY IMPLEMENTED (b769b0fc)
  - Requires Android 11+ (API 30)
  - Password manager must support inline suggestions
  - InlineAutofillUtils.kt handles display in SuggestionBar

---

## Password Manager Clipboard Exclusion - IMPLEMENTED (2026-01-15)

**Feature**: Don't store clipboard entries from password managers for privacy.

**Implementation**:
- **Config.kt**: Added `CLIPBOARD_EXCLUDE_PASSWORD_MANAGERS` default (true) + package list
- **ClipboardHistoryService.kt**: Foreground app detection via UsageStatsManager/ActivityManager
- **SettingsActivity.kt**: UI toggle in Clipboard section

**Supported Apps** (20+ packages):
- Bitwarden, 1Password, LastPass, Dashlane
- KeePass variants (keepass2android, KunziSoft Free/Pro, OpenKeePass)
- Enpass, NordPass, RoboForm, Keeper
- Proton Pass, SafeInCloud, mSecure, Zoho Vault, Sticky Password

**Settings UI**: Clipboard → "Exclude Password Managers" toggle

**Commit**: `edfac50f feat: add password manager clipboard exclusion`

---

## Timestamp Key Feature - IMPLEMENTED (2026-01-15)

**Feature**: Insert current date/time formatted with custom patterns (#1103).

**Syntax**:
- Short: `timestamp:'yyyy-MM-dd'` or `📅:timestamp:'yyyy-MM-dd'`
- Long: `:timestamp symbol='📅':'yyyy-MM-dd HH:mm'`

**Implementation**:
- **KeyValue.kt**: Added `Kind.Timestamp` enum + `TimestampFormat` data class + `makeTimestampKey()` factory
- **KeyValueParser.kt**: Added `timestamp:` prefix parsing + `:timestamp` kind in legacy syntax
- **KeyEventHandler.kt**: Added `handleTimestampKey()` using DateTimeFormatter (API 26+) with SimpleDateFormat fallback

**Pre-defined Keys**:
| Key Name | Output |
|----------|--------|
| `timestamp_date` | 2026-01-15 |
| `timestamp_time` | 14:30 |
| `timestamp_datetime` | 2026-01-15 14:30 |
| `timestamp_date_long` | Wednesday, January 15, 2026 |
| `timestamp_iso` | 2026-01-15T14:30:45 |

**Custom Patterns**: Use `timestamp:'pattern'` with DateTimeFormatter syntax

**Spec**: `docs/specs/timestamp-keys.md`

---

## Test Keyboard Field in Settings - IMPLEMENTED (2026-01-15)

**Feature**: Test keyboard without leaving settings (#1134).

**Implementation**:
- **SettingsActivity.kt**:
  - Added `testKeyboardExpanded` and `testKeyboardText` state variables
  - Added collapsible "⌨️ Test Keyboard" section after search bar
  - OutlinedTextField with 3-5 lines, placeholder text, Clear button

**Commit**: `39a3214f feat: add test keyboard field in settings`

---

## Clipboard Delete Individual Items - IMPLEMENTED (2026-01-15)

**Feature**: Delete individual clipboard history entries (#940).

**Implementation**:
- **clipboard_history_entry.xml**: Added delete button with ic_delete icon
- **ClipboardHistoryView.kt**: Added `delete_entry(pos: Int)` method
  - Calls `service.removeHistoryEntry(clip)` to remove from database
  - Clears expanded state and refreshes list

**Commit**: `bd9403d6 feat: add delete button for clipboard history entries`

---

## Quick Settings Tile - IMPLEMENTED (2026-01-15)

**Feature**: Add Quick Settings tile for keyboard switching (#1113).

**Implementation**:
- **KeyboardTileService.kt**: TileService implementation (Android 7.0+)
  - Shows active/inactive state based on current input method
  - Tapping opens system input method picker
- **AndroidManifest.xml**: Register service with BIND_QUICK_SETTINGS_TILE permission

**Commit**: `7db976d1 feat: add Quick Settings tile for keyboard switching`

---

## White Nav Bar Buttons Fix (Android 8-9) - FIXED (2026-01-15)

**Bug**: White nav bar buttons invisible on light theme on Android 9 (#1116).

**Cause**: Transparent nav bar + light theme = white icons on white background.

**Fix**:
- **Keyboard2View.kt**: Use theme's nav bar color instead of transparent on API < 29 for light themes

**Commit**: `c9cbefe1 fix: prevent invisible nav bar icons on light theme for Android 8-9`

---

## Android 15 Nav Bar Overlap Fix - FIXED (2026-01-15)

**Bug**: Clipboard/emoji pane bottom row obscured by nav bar on Android 15 (#1131).

**Cause**: `contentPaneContainer` height was percentage of screen without nav bar insets.

**Fix**:
- **SuggestionBarInitializer.kt**: Added WindowInsets listener to apply bottom padding
- **KeyboardReceiver.kt**: Request insets when pane becomes visible

**Commit**: `c4cd5368 fix: prevent clipboard/emoji pane from overlapping nav bar on Android 15`

---

## Monet Theme Crash Fix - FIXED (2026-01-15)

**Bug**: Monet theme crashed on Android 9 (#1107).

**Cause**: Monet/Material You requires Android 12+ (API 31).

**Fix**:
- **ThemeSettingsActivity.kt**: Filter Monet themes from list on Android < 12
- **Config.kt**: Fallback to Light/Dark if Monet selected on older devices

**Commit**: `551b2250 fix: prevent Monet theme crash on Android < 12`

---

## User Guide Wiki System - COMPLETE & VERIFIED (2026-01-15)

**Goal**: Create comprehensive user guide wiki with paired technical specs.

**Deployment Verified**: https://tribixbite.github.io/CleverKeys/wiki/
- ✅ Wiki index loads with 8 category navigation
- ✅ All 36 user guides accessible
- ✅ Search index with 28 indexed articles functional
- ✅ Main site has green "Wiki" button in navigation

**Completed** (61 pages total):
- ✅ Wiki directory structure (`docs/wiki/[8 categories]/`)
- ✅ TABLE_OF_CONTENTS.md with all 61 pages
- ✅ wiki-config.json with categories, search, navigation
- ✅ generate-wiki.js with callouts, TOC sidebar, prev/next nav, search
- ✅ Main site updated with Wiki link (green button)
- ✅ GitHub Pages deployment workflow updated

**User Guides Created (36/36)**:
- Getting Started (4): installation, enabling-keyboard, first-time-setup, basic-typing
- Typing (4): swipe-typing, autocorrect, special-characters, emoji
- Gestures (5): short-swipes, cursor-navigation, selection-delete, trackpoint-mode, circle-gestures
- Customization (4): per-key-actions, extra-keys, themes, command-palette
- Layouts (6): adding-layouts, switching-layouts, multi-language, language-packs, custom-layouts, profiles
- Settings (6): appearance, input-behavior, haptics, neural-settings, privacy, accessibility
- Clipboard (3): clipboard-history, text-selection, shortcuts
- Troubleshooting (4): common-issues, reset-defaults, backup-restore, performance

**Tech Specs Created (25/25)**:
- getting-started (2): installation-spec, setup-spec
- typing (4): swipe-typing-spec, autocorrect-spec, special-characters-spec, emoji-spec
- gestures (5): short-swipes-spec, cursor-navigation-spec, selection-delete-spec, trackpoint-mode-spec, circle-gestures-spec
- customization (4): per-key-actions-spec, extra-keys-spec, themes-spec, command-palette-spec
- layouts (4): adding-layouts-spec, switching-layouts-spec, multi-language-spec, language-packs-spec
- settings (3): appearance-spec, input-behavior-spec, haptics-spec
- clipboard (2): clipboard-history-spec, text-selection-spec
- troubleshooting (1): common-issues-spec

**Files Created**:
- `docs/wiki/TABLE_OF_CONTENTS.md`
- `docs/wiki/[category]/*.md` (36 user guides)
- `docs/wiki/specs/[category]/*-spec.md` (25 tech specs)
- `web_demo/wiki-config.json`
- `web_demo/generate-wiki.js`
- `web_demo/wiki/*.html` (38 generated files including index and search)

**URL**: https://tribixbite.github.io/CleverKeys/wiki/

---

## Cursor-Aware Predictions - IMPLEMENTED (2026-01-15)

**Problem**: Predictions didn't work when cursor moved mid-word (via tap, cut/paste, arrow keys). Selecting a prediction mid-word left word fragments behind.

**Root Cause**:
- `PredictionContextTracker` had no cursor sync - `currentWord` stayed stale
- `onUpdateSelection()` only notified Autocapitalisation
- `deleteSurroundingText(n, 0)` only deleted BEFORE cursor

**Solution Implemented** (see `docs/specs/cursor-aware-predictions.md`):

**PredictionContextTracker.kt** (+250 lines):
- Added `currentWordSuffix` StringBuilder for chars after cursor
- Added `rawPrefixForDeletion`/`rawSuffixForDeletion` for accurate deletion
- Added `expectingSelectionUpdate` flag to skip programmatic changes
- Added `wasSyncedFromCursor` flag to track sync state
- Implemented `synchronizeWithCursor(ic, language, editorInfo)`:
  - Reads text before/after cursor via InputConnection
  - Extracts word prefix/suffix with `isWordChar()` logic
  - Handles contractions (apostrophe between letters)
  - Normalizes accents for lookup (café→cafe), keeps raw for deletion
- Added CJK detection to skip sync for non-space-delimited languages
- Added input type filtering (skip password, URL, email fields)

**InputCoordinator.kt** (+80 lines):
- Added `onCursorMoved()` with 100ms debouncing via Handler
- Added `triggerPredictionsForPrefix()` for cursor-synced predictions
- Modified `onSuggestionSelected()` to use `getCharsToDeleteForPrediction()`
  - Now deletes BOTH prefix AND suffix when cursor is mid-word
- Added `resetCursorSyncState()` call when user starts typing

**CleverKeysService.kt** (+10 lines):
- Extended `onUpdateSelection()` to call `_inputCoordinator.onCursorMoved()`
- Added `cancelPendingCursorSync()` in `onFinishInputView()`

**Multi-Language Support**:
- ✅ CJK scripts: Skip sync entirely (HAN, HIRAGANA, KATAKANA, THAI, HANGUL)
- ✅ RTL languages: InputConnection positions are logical, no special handling
- ✅ Contractions: Apostrophe within word is NOT a boundary (don't, l'homme)
- ✅ Accents: NFD normalization for lookup, raw char count for deletion

**Bug Fixes (v1.2.6)**:
1. ✅ Mid-word selection left fragments - fixed immediate sync in `onSuggestionSelected()`
2. ✅ Autocorrect undo/add-to-dict prompt disappeared - fixed race condition in SuggestionHandler:
   - Added `specialPromptActive` flag to prevent async prediction task from overwriting
   - Cancel pending task before showing special prompts
   - Check flag in async task before posting to UI
3. ✅ Mid-word suffix not deleted - SuggestionHandler now calls `synchronizeWithCursor()` before deletion
   and uses `deleteSurroundingText(prefixDelete, suffixDelete)` for both-sided deletion
4. ✅ Swipe predictions disappeared quickly - InputCoordinator now checks `lastCommitSource == NEURAL_SWIPE`
   before clearing suggestions on cursor move
5. ✅ Toast "Added to dictionary" too fast - SuggestionBar now checks `isShowingTemporaryMessage` before clearing/overwriting
6. ✅ Double space on mid-sentence replacement - SuggestionHandler checks `hasSpaceAfter` cursor to skip trailing space
7. ✅ Capitalized words cursor-synced not capitalized - InputCoordinator now uses `rawPrefix` (not normalized) for case check
8. ✅ Contractions cursor past apostrophe no suggestions - InputCoordinator combines prefix+suffix for full word lookup,
   also searches de-apostrophed form (e.g., "dont" finds "don't")
9. ✅ Secondary language contractions not showing - WordPredictor now loads contraction keys into secondary NormalizedPrefixIndex

**Bug Fixes (v1.2.7)**:
10. ✅ Suffix not deleted when cursor mid-word (ca|n't → canteen n't) - CRITICAL fix:
    - SuggestionHandler and InputCoordinator now clear `expectingSelectionUpdate` flag before calling `synchronizeWithCursor()`
    - Stale flag from previous deletion could cause sync to skip, leaving `rawSuffixForDeletion` empty
11. ✅ Contraction predictions not showing for PRIMARY language - WordPredictor now loads:
    - `loadPrimaryContractionKeys()` adds apostrophe-free forms to prefix index (e.g., "cant" → "can't")
    - `loadContractionKeysIntoMaps()` for async loading path
    - Both sync and async dictionary loading now include contraction keys
12. ✅ Dictionary Manager Card moved to TOP of Activities section
13. ✅ Cursor mid-word showed wrong predictions (per|fect → "perfect" instead of "per" words):
    - `onCursorMoved()` now uses PREFIX ONLY for prediction lookup, not prefix+suffix
    - When cursor at "per|fect", predictions show "person", "perhaps", etc. (prefix matches)
    - Suffix still tracked for deletion when prediction is selected
14. ✅ Dictionary Manager dropdown changed from filter to sort mechanism:
    - Options: Freq (default), Match, A-Z, Z-A
    - Freq: Sort by frequency (highest first)
    - Match: Sort by match quality (exact > prefix > other), then by frequency
    - A-Z/Z-A: Alphabetical ascending/descending

**Settings UI Changes**:
- Removed redundant Dictionary section (Dictionary Manager accessible from Activities section)

**Status**: Implemented and testing

---

## Defaults & Reset Settings Fix - COMPLETE (2026-01-15)

**Config.kt Default Changes**:
- `SHORT_GESTURE_MIN_DISTANCE`: 37 → 28%
- `KEYBOARD_HEIGHT_PORTRAIT`: 28 → 30%
- `KEYBOARD_HEIGHT_LANDSCAPE`: 50 → 40%
- `MARGIN_BOTTOM_PORTRAIT`: 2 → 0%
- `MARGIN_BOTTOM_LANDSCAPE`: 2 → 0%

**resetAllSettings() Fix** (`SettingsActivity.kt:4576`):
- Now uses `Defaults.*` constants instead of hardcoded values
- Applies BALANCED neural profile (beam_width=6, maxLength=20, etc.)
- Sets correct theme (`cleverkeysdark` instead of `jewel`)
- Sets all margin, haptic, and input behavior defaults properly

**Release Recreation**:
- Deleted old v1.2.5 release and tag
- Created new tag pointing to fix commit (07142bf4)
- Uploaded new APKs with fix
- Updated release notes with new Default Settings section

---

## Web Demo & Spec Documentation System - COMPLETE (2026-01-14)

**Web Demo Updates**:
1. **Under Construction Page** (`web_demo/demo/index.html`)
   - Replaced broken neural demo with styled placeholder
   - Explains demo is being rebuilt with new architecture
   - Links to F-Droid and GitHub releases for app download

**Spec Documentation System**:
1. **New Specs Created**:
   - `selection-delete-mode.md`: Backspace swipe-hold text selection
   - `trackpoint-navigation-mode.md`: Nav key joystick cursor control

2. **Webpage Automation** (`web_demo/generate-specs.js`):
   - Node.js script generates HTML pages from markdown specs
   - Creates category-organized index page
   - Consistent dark theme styling matching main site

3. **Configuration** (`web_demo/specs-config.json`):
   - Defines which specs are reviewed and published
   - Category colors and metadata for each spec
   - Easy to add new specs by editing JSON

4. **Generated Pages** (`web_demo/specs/`):
