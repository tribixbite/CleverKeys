# CleverKeys Working TODO List

**Last Updated**: 2025-12-07
**Session**: Short swipe with shift key fix

---

## Completed This Session (2025-12-07)

### Custom Short Swipes Work with Shift Active
- [x] Issue: Custom short swipe mappings were blocked when shift/fn/ctrl was active on char keys
- [x] Root cause: `shouldBlockGesture` check in Pointers.kt blocked ALL gestures when modifiers were active
- [x] Fix: Restructured logic to check custom mappings BEFORE the blocking check
  - Custom user-defined mappings now bypass modifier block (work even with shift held)
  - Built-in sublabel gestures still blocked when modifiers active (prevents accidental punctuation)
- [x] Renamed `shouldBlockGesture` to `shouldBlockBuiltInGesture` for clarity
- [x] Fixed CustomDictionarySource prefs in WordListFragment to use correct file ("user_dictionary")
- [x] Removed unused R import from ThemeProvider.kt (build fix)
- [x] Commit: 93c32b82 - feat: custom short swipes work with shift key active

### Colored Direction Zones in Short Swipe Customization
- [x] Issue: Direction zones in key customization dialog were invisible (no visual feedback)
- [x] Fix: Added distinct colored backgrounds to each of the 8 direction zones
  - NW: Red (#FF6B6B), N: Teal (#4ECDC4), NE: Yellow (#FFE66D)
  - W: Mint (#95E1D3), E: Coral (#F38181)
  - SW: Purple (#AA96DA), S: Cyan (#72D4E8), SE: Pink (#FCBAD3)
- [x] Added direction labels (NW, N, NE, W, E, SW, S, SE) in each zone
- [x] Center zone remains transparent (no action)
- [x] Commit: 1a68c58c - fix: add colored direction zones to short swipe customization

---

## Completed Previous Session (2025-12-06)

### Short Swipe Mapping - Separate Label and Action Fields
- [x] Fixed app crash when adding certain short gesture mappings
- [x] Root cause: ShortSwipeMapping validated command names against AvailableCommand enum (SCREAMING_SNAKE)
  - But CommandRegistry uses camelCase names (selectAll vs SELECT_ALL)
  - Creating mapping with "selectAll" threw IllegalArgumentException
- [x] Added MappingSelection data class with displayLabel, actionType, actionValue
- [x] Added LabelConfirmationDialog for customizing display label separately from action
- [x] Updated CommandPaletteDialog with onMappingSelected callback for full control
- [x] Removed strict command validation from ShortSwipeMapping.kt
- [x] Added executeRegistryCommand() to CustomShortSwipeExecutor.kt for 143+ commands
- [x] Updated ShortSwipeCustomizationActivity.kt to use new onMappingSelected flow
- [x] Users can now select 'Select All' as action and 's(a)' as custom label

### Touch Event Handling Fix for KeyCustomizationDialog
- [x] AndroidView inside Compose Dialog wasn't receiving touch events (interop issue)
- [x] pointerInteropFilter approach didn't work reliably
- [x] Created DirectionTouchOverlay - pure Compose 3x3 grid overlay
- [x] Each zone maps to SwipeDirection (NW, N, NE, W, E, SW, S, SE)
- [x] Center zone has no action (taps on center key letter are ignored)
- [x] Overlays the KeyMagnifierView for reliable touch detection
- [x] Successfully tested: S key SW zone → selectAll mapping saved as "sa"

### CommandRegistry Expansion
- [x] Added 30+ new commands from KeyValue.getSpecialKeyByName:
  - Combining diacritics (acute, grave, circumflex, tilde, trema, etc.)
  - Compose key and compose_cancel
  - Document navigation (doc_home, doc_end)
  - Bidi brackets/parentheses (b(, b), b[, b], b{, b}, blt, bgt)
  - Zero-width joiner/non-joiner (zwj, zwnj, halfspace)
  - Additional editing commands (replaceText, textAssist, autofill)
  - Removed placeholder key
- [x] Total available commands now ~120+
- [x] All commands have searchable keywords

### Short Swipe Customization v4
- Already implemented KeyMagnifierView shows actual key mappings:
  - Shows key.keys[1-8] sub-labels from KeyboardData.Key
  - Custom mappings override and display in theme accent color
  - Uses proper 3x3 grid layout matching keyboard rendering
  - Direction indices match KeyboardData.Key layout (1=NW, 7=N, 2=NE, etc.)
- Already implemented CommandPaletteDialog has search filter
- Already implemented KeyCustomizationDialog shows "existing layout mappings + custom mappings"

### Aspect Ratio Fix
- [x] Fixed KeyMagnifierView.onMeasure() to handle MeasureSpec modes (EXACTLY, AT_MOST, UNSPECIFIED)
- [x] Added aspectRatio modifier to Compose AndroidView container (key.width / rowHeight)
- [x] Key preview now maintains correct proportions (~0.85 width/height ratio)
- [x] Tested: "g" key shows correct mappings (-, go, _) with proper aspect ratio

### Special Font Fix for Private Use Area Characters
- [x] Fixed Chinese characters appearing in S and D key SE positions ("鯨" and "符")
- [x] Root cause: KeyMagnifierView wasn't using special_font.ttf for private use area Unicode chars
- [x] Added keyFont lazy property using Theme.getKeyFont(context)
- [x] Added specialSubLabelPaint with special font typeface
- [x] Updated drawSubLabels() to check FLAG_KEY_FONT on each KeyValue
- [x] Updated drawSubLabelForDirection() to accept useKeyFont parameter
- [x] Tested: S and D keys now show correct cursor arrow symbols

### NullPointerException Fix in ShortSwipeCustomizationActivity
- [x] Fixed NPE when selecting command or text mapping in Toast.makeText()
- [x] Root cause: editingDirection set to null before being used in Toast message
- [x] Fix: Save direction to local variable before nullifying editingDirection

### Custom Mapping Display on Keyboard
- [x] Custom short swipe mappings now render directly on keyboard keys
- [x] Added ShortSwipeCustomizationManager reference to Keyboard2View
- [x] Added drawCustomMappings() to render custom labels during key drawing
- [x] Added drawCustomSubLabel() for accent-colored custom mapping text
- [x] Added directionToSubIndex() to map SwipeDirection → sublabel positions (1-8)
- [x] Custom mappings display with theme's activatedColor for visual distinction
- [x] Commit: 571d6519 - feat: display custom short swipe mappings on keyboard

---

## Completed Previous Session (2025-12-05)

### Per-Key Short Swipe Customization Feature (NEW)
- [x] Phase 1: Data layer (`ShortSwipeCustomization.kt`, `ShortSwipeCustomizationManager.kt`)
- [x] Phase 2: Integration with `Pointers.kt` and `Keyboard2View.kt`
- [x] Phase 3: UI - `ShortSwipeCustomizationActivity.kt`
  - Interactive keyboard preview (pure Compose with Card(onClick))
  - 8-direction radial selector modal (EnhancedDirectionButton)
  - Editor for display text (max 4 chars), action type, action value
  - Supports TEXT (up to 100 chars), COMMAND (copy/paste/etc), KEY_EVENT
  - Theme integration via `ThemeProvider`
  - Corner indicators showing mapped directions
- [x] Phase 4: Fix touch handling - replace Box+clickable with Card(onClick)
  - Box+clickable had unreliable touch event propagation
  - Card with onClick parameter provides reliable built-in click handling
  - Added @OptIn(ExperimentalMaterial3Api::class) annotations

### README Redesign (ImageToolbox Style)
- [x] Add centered badge row (API, Kotlin, ONNX, Material 3, Downloads, Stars, Release)
- [x] Create feature_banner.png with gradient background and screenshot collage
- [x] Create social_preview.png for Discord/Twitter embeds
- [x] Add Buy Me a Coffee section with Solana address
- [x] Add prominent Download APK section (GitHub only)
- [x] Add extensive theme engine documentation with DIY creator details
- [x] Reorganize sections with improved visual hierarchy

### Swipe Prediction Investigation
- [x] Deep dive on swipe prediction pipeline
- [x] Investigate why "asshole" outputs as "asso" (truncated)
- [x] Fix contraction mapping: "doesnt" → "doesn't" now works when model outputs it
- [x] REVERTED: Beam search modifications (vocab validation, boosting, 4→8 width)
  - Changes degraded accuracy and increased latency
  - Root cause is model training data, not post-processing
- [x] Fix Theme.kt runtime theme support for KeyboardColorScheme rendering

### Layout Cleanup
- [x] Remove 'as'/'at' short swipes from A key
- [x] Remove 'be'/'by' short swipes from B key
- [x] Remove 'hi' short swipe from H key
- [x] Remove 'to' short swipe from T key
- [x] Remove 'or' short swipe from O key

### Theme Creator Defaults
- [x] Fix theme creator to use current theme colors as defaults
- [x] Add getBuiltInColorScheme() to ThemeProvider for all 17 built-in themes

### CI/CD
- [x] Reverse release logic: pushes to main are full releases, tags are prereleases

### Dictionary Import Fix (CRITICAL)
- [x] Root cause: BackupRestoreManager used wrong SharedPreferences file
  - Was using: `cleverkeys_prefs` with `user_word_{hash}` keys
  - Should use: `user_dictionary` file with `user_words` StringSet key
- [x] Fix importDictionaries() to use same file/key as DictionaryManager
- [x] Fix exportDictionaries() to match new format
- [x] Fix disabled_words to use DirectBootAwarePreferences (matches WordPredictor)

### LauncherActivity.kt Compilation Fix
- [x] Add missing animation imports (AnimatorSet, ObjectAnimator, ValueAnimator)
- [x] Add missing android.graphics.Canvas import for RaccoonAnimationView.onDraw()
- [x] Fix Path type mismatch (use Compose Path instead of android.graphics.Path)
- [x] Add RaccoonMascot Composable wrapper for AndroidView integration
- [x] Add @OptIn annotation for ExperimentalMaterial3Api Card onClick

### Arrow Key Short Gesture Fix (CRITICAL)
- [x] Root cause: `startSwipe()` only called when `swipe_typing_enabled=true`
  - Short gestures on non-Char keys (like compose/arrow key) need path tracking too
  - Without path initialization, `getSwipePath()` returns empty list
  - Direction calculation fails and short gestures don't trigger
- [x] Fix: Initialize swipe recognizer when EITHER `swipe_typing_enabled` OR `short_gestures_enabled` is true
- [x] File changed: `Pointers.kt` line 416 - added `|| _config.short_gestures_enabled` to condition

### Document Navigation Keys (Arrow Key Corners)
- [x] Issue: NW (key1) and SE (key3) on arrow key moved to line start/end, not document start/end
  - `KEYCODE_MOVE_HOME` and `KEYCODE_MOVE_END` only navigate within current line on Android
  - User expectation: corner swipes should navigate to document boundaries
- [x] Solution: Add new Editing enum entries and send Ctrl+Home/Ctrl+End key events
  - Added `CURSOR_DOC_START` and `CURSOR_DOC_END` to `KeyValue.Editing` enum
  - Added `doc_home` and `doc_end` key definitions using same icons as home/end (0xE00B, 0xE00C)
  - Added handler in `KeyEventHandler.handleEditingKey()` to send `Ctrl+MOVE_HOME` and `Ctrl+MOVE_END`
- [x] Files changed:
  - `KeyValue.kt` - Added enum entries and key definitions
  - `KeyEventHandler.kt` - Added Ctrl+Home/End handling
  - `res/xml/bottom_row.xml` - Changed key1 from `loc home` to `doc_home`, key3 from `loc end` to `doc_end`
  - `res/values/strings.xml` - Added `key_descr_doc_home` and `key_descr_doc_end`

---

## Completed Previous Session (2025-12-04)

### Theme Manager Migration (Major UI Overhaul)
- [x] Add 18 built-in XML themes to ThemeSettingsActivity with preview cards
- [x] Each theme shows name, description, and mini keyboard preview
- [x] Selected theme highlighted with purple border and checkmark
- [x] Built-in themes save correct IDs that Config.kt recognizes
- [x] Remove theme dropdown from SettingsActivity Appearance section
- [x] Remove unused theme helper functions (getThemeIndexFromName, etc.)
- [x] Theme Manager card is now the sole entry point for theme selection

### Theme Application Bug Fix (CRITICAL)
- [x] Root cause: ThemeSettingsActivity used `PreferenceManager.getDefaultSharedPreferences()`
- [x] But CleverKeysService uses `DirectBootAwarePreferences.get_shared_preferences()`
- [x] On API 24+, these are TWO DIFFERENT FILES (default vs device protected storage)
- [x] Fix: Call `DirectBootAwarePreferences.copy_preferences_to_protected_storage()` after saving theme

### Import/Export System Overhaul (Previous)
- [x] Fix dictionary import - SettingsActivity now uses BackupRestoreManager
- [x] Fix clipboard import - SettingsActivity now uses ClipboardDatabase.importFromJSON()
- [x] Fix config (kb-config) import - uses BackupRestoreManager.importConfig() with proper validation
- [x] Fix dictionary export - uses BackupRestoreManager.exportDictionaries()
- [x] Fix clipboard export - uses BackupRestoreManager.exportClipboardHistory()
- [x] Fix config export - uses BackupRestoreManager.exportConfig()

### Default Settings Fixes
- [x] Set default portrait keyboard height to 27% (was 35%)
- [x] Set default Swipe Pattern Data collection to OFF (privacy-friendly default)
- [x] Set default Performance Metrics collection to OFF (privacy-friendly default)

### BackupRestoreManager Dictionary Import Fix (Previous Session)
- [x] Handle both `custom_words` (object format) and `user_words` (array format)
- [x] Export format uses `custom_words: { "word": frequency }` object structure

### ClipboardDatabase Import Fix (Previous Session)
- [x] Fix duplicate check logic - was using `return@use` which didn't skip loop iteration

---

## Verified Working

### Import/Export (from Settings -> Backup & Restore)
- Config import/export with proper metadata/preferences structure
- Dictionary import handles both old (user_words array) and new (custom_words object) formats
- Clipboard import with duplicate detection

### Theme Manager (from Settings -> Appearance -> Theme Manager card)
- Theme selection now applies correctly (saves to "theme" preference)
- Gemstone themes: Ruby, Sapphire, Emerald
- Neon themes: Electric Blue, Hot Pink, Lime Green

### Default Settings
- Portrait keyboard height defaults to 27%
- Privacy settings default to OFF (data collection disabled by default)

---

## Pending (Future Sessions)

- [x] Manual device testing for all import/export features
  - Tested 2025-12-06: BackupRestoreActivity UI functional
  - Export Settings, Import Settings buttons working
  - Dictionary Backup Export/Import buttons visible
  - Clipboard History Backup Export/Import buttons visible
  - Important Notes section displayed correctly
- [x] ~~Migrate legacy XML PreferenceScreen to Compose Material 3~~ - Already using Compose M3 in SettingsActivity

---

## Important Technical Notes

### Import/Export Architecture
SettingsActivity uses BackupRestoreManager for all import/export operations:
- `performConfigImport/Export` -> BackupRestoreManager.importConfig/exportConfig
- `performDictionaryImport/Export` -> BackupRestoreManager.importDictionaries/exportDictionaries
- `performClipboardImport/Export` -> BackupRestoreManager.exportClipboardHistory / ClipboardDatabase.importFromJSON

### Theme System (IMPORTANT)
- ThemeSettingsActivity saves to SharedPreferences key `"theme"`
- **CRITICAL**: Must also call `DirectBootAwarePreferences.copy_preferences_to_protected_storage()`
  after saving for keyboard to see the change (API 24+ uses device protected storage)
- Config.kt reads from `"theme"` preference via `getThemeId()`
- Theme IDs: cleverkeysdark, cleverkeyslight, dark, light, black, altblack, white,
  rosepine, cobalt, pine, desert, jungle, epaper, epaperblack, monet, monetlight,
  monetdark, everforestlight

### Privacy Defaults
PrivacyManager defaults changed in 4 places:
- canCollectSwipeData() default: false
- canCollectPerformanceData() default: false
- getSettings().collectSwipeData default: false
- getSettings().collectPerformanceData default: false

---

## Files Modified This Session
- `src/main/kotlin/tribixbite/cleverkeys/customization/ShortSwipeCustomization.kt` - Data models for per-key short swipe
- `src/main/kotlin/tribixbite/cleverkeys/customization/ShortSwipeCustomizationManager.kt` - Persistence and state management
- `src/main/kotlin/tribixbite/cleverkeys/customization/KeyboardPreviewView.kt` - Native View for actual keyboard rendering
- `src/main/kotlin/tribixbite/cleverkeys/ShortSwipeCustomizationActivity.kt` - Full UI for customization
- `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt` - Integration with custom short swipe mappings
- `src/main/kotlin/tribixbite/cleverkeys/Keyboard2View.kt` - Custom short swipe visual rendering
- `src/main/kotlin/tribixbite/cleverkeys/SettingsActivity.kt` - Added Short Swipe Customization entry
- `src/main/kotlin/tribixbite/cleverkeys/OptimizedVocabulary.kt` - Contraction mapping fix (doesnt → doesn't)
- `src/main/kotlin/tribixbite/cleverkeys/ThemeSettingsActivity.kt` - Theme Manager UI, DirectBootAwarePreferences fix
- `src/main/kotlin/tribixbite/cleverkeys/SettingsActivity.kt` - Removed theme dropdown, kept Theme Manager card
- `src/main/kotlin/tribixbite/cleverkeys/Config.kt` - Portrait height default 27%, beam width 5
- `src/main/kotlin/tribixbite/cleverkeys/PrivacyManager.kt` - Privacy defaults to OFF
- `src/main/kotlin/tribixbite/cleverkeys/BackupRestoreManager.kt` - Dictionary import fix (use user_dictionary file with user_words StringSet)
- `src/main/kotlin/tribixbite/cleverkeys/ClipboardDatabase.kt` - Duplicate check fix (previous session)
- `src/main/kotlin/tribixbite/cleverkeys/LauncherActivity.kt` - Compilation fixes (imports, Path type, RaccoonMascot, Material3 opt-in)
- `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt` - Arrow key short gesture fix (startSwipe for short_gestures_enabled)
- `src/main/kotlin/tribixbite/cleverkeys/customization/KeyMagnifierView.kt` - Special font support for private use area chars
