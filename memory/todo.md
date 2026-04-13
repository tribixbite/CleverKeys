# CleverKeys TODO

## ✅ Memory/Performance Optimization (2026-04-09, continued 2026-04-10)

### VocabularyTrie Compact Nodes (f183e1515)
VocabularyTrie used `mutableMapOf<Char, TrieNode>()` (LinkedHashMap) per node.
For 50k English words (~180k nodes), each node cost ~250 bytes average due to
LinkedHashMap overhead (Entry objects, boxed Char keys, linked-list pointers).
Total: ~45MB per trie instance.

**Fix**: Replaced with compact parallel arrays (`CharArray` + `Array<TrieNode?>`).
Leaf nodes share static empty-array singletons (36 bytes each). Linear scan on
≤26 children is faster than HashMap due to CPU cache locality.

**Estimated savings**: ~34MB per trie (45MB → 11MB). ~68MB for bilingual users.

### Dictionary Manager Cache Eviction (b08c21859)
`MainDictionarySource.sharedCache` (companion object) held 50k-word lists + prefix
indices (~7MB per language) for the app's entire lifecycle. The Dictionary Manager
is rarely used after initial setup.

**Fix**: `DictionaryManagerActivity.onDestroy()` calls `MainDictionarySource.invalidateCache()`.

**Estimated savings**: ~7-14MB (1-2 cached languages freed on activity close).

### HashSet Prefix Indices (b08c21859)
`WordPredictor`, `BinaryDictionaryLoader`, and `AsyncDictionaryLoader` used
`mutableSetOf()` (LinkedHashSet) for prefix index values. The linked-list overhead
adds ~16 bytes per entry across ~250k entries.

**Fix**: Switched to `HashSet()` — same uniqueness guarantees, no linked-list overhead.

**Estimated savings**: ~4MB.

### VocabularyTrie Hot Path Allocation Fix (ac5066acd)
Gemini 3 Pro review found `hasPrefix()`, `containsWord()`, `getAllowedNextChars()`
each called `String.lowercase()`, allocating a new String per invocation. During beam
search (beam_width=6, max_length=20), that's ~120 String allocations per swipe — all
immediately GC-eligible.

**Fix**: Replaced with per-character `Char.lowercaseChar()` (primitive, zero-alloc).
Also switched `LinkedHashSet<Char>` to `HashSet<Char>` in `getAllowedNextChars()`.

### Clipboard Filter Icon Theme Fix (23881fe20)
Filter icon and filter dialog tag checkboxes used `android.R.attr.colorAccent` and
`android.R.attr.textColorPrimary` — framework attrs that don't resolve in IME theme
context. Fell back to hardcoded cyan `0xFF4FC3F7` in all themes.

**Fix**: Replaced with `R.attr.colorLabelActivated` and `R.attr.colorLabel` — custom
theme attrs defined by every keyboard theme. Also added `TodoEntry.VALID_STATUSES`
validation on clipboard import to guard against corrupted status values.

### Clipboard UX Fixes (2ba5831c5)
Three issues found during manual testing:

1. **Edit maxLines cap**: EditText had `maxLines="10"` — entries with 11+ lines were
   clipped during editing. Raised to 50.
2. **Todo button silent dedup**: Tapping "add to todo" on a duplicate showed no feedback.
   Now shows toast: "Added to todos" / "Already in todos". Service `addToTodo()` returns
   Boolean for caller feedback.
3. **Expand state lost after actions**: `applyPagination()` unconditionally cleared
   `expandedStates` on every `loadDataAsync()` call. Entries collapsed after tapping
   any action button (pin, todo, delete). Now only clears on filter/page/tab changes.
   Stale entries pruned via `retainAll(visibleTimestamps)`.

### README Rewrite (013a0e265)
README over-emphasized "only open source swipe engine" — restructured to showcase full
feature set: clipboard system, multi-language hot-swap, GIF panel, TrackPoint cursor,
autocorrect with contractions, 208 short-swipe actions, tags, regex search, etc.

### Cross-Source Cache Coherence (b83109146)
Enabling words in Disabled tab didn't make them appear in Active tab. Root cause:
`DisabledDictionarySource.toggleWord()` updates SharedPreferences but `MainDictionarySource`
caches 50k DictionaryWord objects with stale `.enabled` flags.

**Fix**: Added `DictionaryDataSource.onRefresh()` interface method. `MainDictionarySource`
overrides to re-scan cached words against current disabled set. O(n) scan ~50k words <5ms.

### Coil Memory Cache Cap
Coil 2.x defaults to 25% of available RAM (~250MB) for memory cache. For an IME loading
80px WebP thumbnails (~5KB each), this was the primary contributor to the 500MB+ running
services footprint. Capped at 32MB — holds ~6000 thumbnails, 64x the 100-item page size.

**Fix (05404568e)**: `GifGridView.kt` — explicit `MemoryCache.Builder.maxSizeBytes(32MB)`.

### Predictor Eviction + ONNX Cleanup
`DictionaryManager.predictors` used `getOrPut()` but never removed entries. Each
WordPredictor holds ~5-10MB. Switching N languages leaked N×5-10MB for the keyboard's
lifetime. Additionally, `PredictionCoordinator.shutdown()` set `neuralEngine = null`
without calling `cleanup()`, leaking native OrtSession resources.

**Fix (220df6204, c985bf372)**:
- `DictionaryManager.setLanguage()` evicts predictors not in configured language set
- Configured set = up to 4 languages (primary, secondary, alt_primary, alt_secondary)
- `loadDictionaryAsync` callback guards against orphaned observer (`predictors[code] === this`)
- `DictionaryManager.cleanup()` releases all predictor instances on service destroy
- `PredictionCoordinator.shutdown()` calls `neuralEngine.cleanup()` + `dictionaryManager.cleanup()`
- Note: shutdown only fires on app update/disable, not during normal use

**Estimated savings**: ~220MB (Coil ~218MB + predictors ~5-10MB per unused language).

## ✅ Clipboard Edit Mode — Visibility Check Fix (2026-04-09)
Edit mode on pinned/todo tabs appeared to work (UI showed EditText) but typed keys had
no visible effect. Root cause: `activeEditingEditText` fast path checked `windowToken != null`
but NOT `visibility == VISIBLE`. When `loadDataAsync()` completed during edit mode (tab switch
race), `notifyDataSetChanged()` recycled the cached editField to a different position (GONE)
while it retained a valid windowToken. All setText() calls went to the invisible recycled view.

**Fix (6835ba57c)**: Added `et.visibility == VISIBLE` check to fast path. Falls through to
slow path (child search) when cached ref is recycled/GONE.

**Repro**: Edit + delete entry in history → switch to pinned → edit entry → type = no response.

## Clipboard Entry Duration Fix — Settings Bug + Stale Expiry Rescue (2026-03-31)
Entry Duration slider was missing from main SettingsActivity — only existed in dead
ClipboardSettingsActivity (not in manifest, never launched). Users could not configure
how long entries persist. Three root causes of silent entry deletion:

1. **Dead class**: Duration slider only in `ClipboardSettingsActivity` (unreachable)
2. **Default mismatch**: ClipboardSettingsActivity defaulted `clipboard_history_enabled`
   to `false` (hiding all config UI) while Config defaults to `true`
3. **Stale expiry**: Old entries carried 7-day TTL from before `-1` (never) default

**Fixes (ff6a03b, 03739d1)**:
- Added Entry Duration slider to SettingsActivity (9 presets: 1h→30d→Never)
- Added `clipboardHistoryDuration` state var + prefs loading + searchable setting
- Fixed ClipboardSettingsActivity defaults → use `Defaults.*` constants
- Added `ClipboardDatabase.rescueExpiredEntries()` — updates stale expiry→MAX_VALUE
- ClipboardHistoryService: rescue instead of delete when duration is "never"
- `clearExpiredAndGetHistory()`: skip time-based cleanup when TTL is infinite
- Warning text when count unlimited but duration finite

**Remaining**: Device testing — verify Duration slider visible in Clipboard settings section,
verify "Never expire" stops auto-deletion, verify old entries rescued after app restart.

## Clipboard UI Overhaul — Icons, Tap-to-Expand, Tags & Status (2026-03-28)
Replaced emoji tab icons (📋📌✓) with vector drawable ImageViews for consistent theming.
Restructured entry layout: vertical button column on right side (primary → secondary → edit
rows stacked). Reduces default button clutter from 6 to 3 buttons.

**Changes (4c273da, b7947f7)**:
- 6 new icons: ic_tab_history, ic_tab_pinned, ic_tab_todos, ic_tag, ic_unpin, ic_status_cycle
- 5 existing icons updated to outline style for visual consistency
- ClipboardEntry gains `tags: List<String>` + `todoStatus: String?` fields
- getPinnedEntries()/getTodoEntries() now SELECT tags/status columns
- ClipboardHistoryService wrappers: setPinnedTags, setTodoTags, getAllPinnedTags, getAllTodoTags
- Tab-aware secondary buttons: History(pin/todo), Pinned(unpin/todo/tags), Todos(done/status/tags)
- Delete button gated behind edit mode (edit_buttons row 3, under cancel)
- Done button for todos: one-tap toggle between active↔completed
- cycleTodoStatus(): active→planned→completed with [plan]/[done] prefix + strikethrough
- Non-breaking spaces in timestamps prevent date wrapping mid-unit
- ClipboardTagDialog: AlertDialog with FlowLayout chips, suggestions, new tag input
- ClipboardManager tab fields: TextView? → ImageView?
- Test fix: clipboard_entry_normal_buttons → primary_buttons (Espresso)

**Build**: compileDebugKotlin OK, 1041 pure tests pass, release APK builds.
**Device tested**: History tab verified — buttons stack vertically on right side, secondary row
reveals on expand with correct tab-specific icons, no whitespace issues, timestamps don't wrap.
**Additional fixes (9868e4c, b597b0f)**:
- Delete button isolated on own row (row 4) below save/cancel — prevents accidental presses
- Tag dialog constrained to 60% of panel height (max 320dp), positioned at top
- Tag input key routing: full chain KeyEventHandler→KeyboardReceiver→ClipboardManager→View
- Tag mode checked before edit mode in key routing priority

**Inline tag panel conversion (5f15ad5)**:
- Replaced AlertDialog with inline panel in clipboard_pane.xml (mutually exclusive with entry list)
- ClipboardTagDialog.kt → ClipboardTagPanel: populate() builds into provided LinearLayout
- Tag EditText ownership moved from ClipboardHistoryView to ClipboardManager
- ClipboardHistoryView.onTagPanelRequested callback replaces direct dialog call
- Header row with truncated entry preview + close button
- Theme-aware colors via resolveThemeColor (colorLabel, colorSubLabel, colorKey)
- Panel auto-hides on tab switch or pane close

**Skill files created (.claude/skills/)**:
- ime-key-routing.md — IME key routing chain pattern, priority order, how to add new modes
- clipboard-panel-architecture.md — Panel layout, tabs, modes, data flow, common gotchas

**Tag panel input fixes (90d95a6, 946ce9b, a3e5693)**:
- Fix 1: setText()+setSelection() pattern instead of append()/editable.replace() (IME-owned views)
- Fix 2: State machine mutual exclusion — exitEditMode()+clearSearch() on tag open, tag guards on
  tab clicks, edit mode callback closes tag panel, setTagModeLockUI() dims tabs during tagging
- Fix 3 (ROOT CAUSE): KeyEventReceiverBridge.kt was missing tag mode delegation methods
  (isClipboardTagMode, insertToClipboardTag, backspaceClipboardTag). KeyEventHandler.recv is the
  bridge, not KeyboardReceiver directly — calls fell through to IReceiver defaults (false/no-op).
  Same bug class as emoji search (#41 v7). Skill updated: .claude/skills/ime-key-routing.md

**Filter dialog extension (023b5da)**:
- Renamed clipboard_date_filter_dialog → clipboard_filter_dialog (ScrollView-wrapped)
- TODOS tab: status checkboxes (Active/Planned/Completed) with apply guard
- PINNED+TODOS tabs: dynamic tag checkboxes with OR/AND match toggle
- HISTORY tab: date section only (tags/status sections hidden)
- Filter icon tints accent when any filter is active
- clearAllFilters() resets all dimensions, tag/status reset on tab switch

**Remaining (manual device verification)**:
- [ ] Filter dialog: HISTORY tab shows date section only
- [ ] Filter dialog: PINNED tab shows date + tags (no status)
- [ ] Filter dialog: TODOS tab shows date + status + tags
- [ ] Filter dialog: status checkboxes filter correctly
- [ ] Filter dialog: tag checkboxes filter correctly (OR + AND modes)
- [ ] Filter dialog: Clear resets all, icon tint resets
- [ ] Filter dialog: can't Apply with all status unchecked
- [ ] Tag panel: typing works into tag EditText (input captured, not sent to app)
- [ ] Tag panel: chip add/remove, close restores list
- [ ] Pinned tab: unpin/todo/tags buttons on expand
- [ ] Todos tab: done/status/tags buttons on expand
- [ ] Edit mode: delete on own row below save/cancel
- [x] Remove diagnostic Toast+Log after tag input confirmed — fixed in 18ec94094
- [x] Edit mode: arrow keys + Enter routed to inline EditText — fixed in 2da276127
- [x] Edit mode: full setText+setSelection rewrite (editable.replace/dispatchKeyEvent broken on IME EditText) — f1e80d0ff
- [x] Edit mode: TextWatcher cursor corruption fix (setText resets cursor to 0, TextWatcher captured wrong pos) — f1e80d0ff
- [x] Edit mode: delete_entry exits edit mode (prevents isEditing() blocking all buttons) — f1e80d0ff
- [x] Todo status filter: default (active only) was skipping filter pass, showing planned/completed — f1e80d0ff
- [x] Edit mode: inputType="none" → "textMultiLine" — 3f0a25c55 — REVERTED: textMultiLine causes restartInput() after newline
- [x] Edit mode: revert to inputType="none" (TYPE_NULL is IME-safe, Editable spans work regardless) — 03c0ab424
- [x] Edit mode: use editingCursorPosition as source of truth instead of et.selectionStart — 3f0a25c55
- [x] Edit mode: delete_entry always cancels edit mode (was only matching on content) — 3f0a25c55
- [x] Edit mode: reverse cursor priority — trust et.selectionStart first (textMultiLine makes it reliable), editingCursorPosition as view-recycling fallback — 824c3f764
- [x] Edit mode: restore selection handling in insertEditText (selectAll+type replaces) and backspaceEditText (selectAll+backspace clears) — 824c3f764
- [x] Edit mode: rescue cursor from active EditText in getView() to preserve tap-to-reposition — 0fa3f53c9 — REMOVED: unnecessary with scrap view guard
- [x] Edit mode: guard editingEditText against ListView scrap view theft — 4139fd00d
  Root cause: Enter → height change → measureHeightOfChildren() → getView(scrap) → editingEditText overwritten with invisible detached view → all subsequent input lost
- [x] Edit mode: dynamic activeEditingEditText property (windowToken + child search fallback) — 00d8b7381
  Belt+suspenders: guard prevents scrap theft, dynamic property recovers if reference goes stale.
  Also: direct editingInProgressText sync in insertEditText/backspaceEditText/cutFromEditText,
  save_edit() reads editingInProgressText instead of potentially-stale EditText widget.
- [x] Edit mode: restructure getView guard — cursor/listeners/TextWatcher outside guard — 002a89409
  Guard was too broad: setSelection, setOnClickListener, TextWatcher all inside guard.
  After ListView recycles view (Enter → height change), new view missed all setup.
  Bug 1: cursor jumped to 0 after Enter (setText resets, setSelection was guarded).
  Bug 2: save/cancel/delete buttons stopped working (new widget, no listeners).
  Bug 3: Enter appeared broken on pinned/todos (manifestation of bugs 1+2).
  Fix: guard only protects editingEditText=editField. All other setup runs every getView call.
  TextWatcher uses tag-based cleanup (getTag/setTag) to prevent accumulation.
  Documented in skills: ime-key-routing.md "ListView Scrap View Architecture".
- [x] Edit mode: Gemini review — TextWatcher cleanup in normal-mode, isEditing() guard, save_edit simplify — 3bff571f9
  Gemini 3 Pro confirmed: guard restructure correct, scrap windowToken=null (safe), architecture sound.
- [ ] Edit mode: verify Enter, arrows, paste, backspace, tap-to-reposition, save/cancel/delete work on all tabs

## Clipboard Regex Search — COMPLETE (2026-03-27)
VSCode-style `.*` toggle button in search bar. OFF = plain substring match (unchanged),
ON = regex with case-insensitive matching. Glob shorthand: bare `*` → `.*`, bare `?` → `.`.
Full regex power available (anchors, alternation, character classes, escape sequences).
Invalid regex shows red text, matches nothing, no crash. State resets on search clear.
**Files**: ClipboardSearchUtils.kt (pure Kotlin), ClipboardHistoryView.kt (filter logic),
ClipboardManager.kt (toggle wiring + error feedback), clipboard_pane.xml (toggle button).
**Tests (fe60dfc)**: 31 JVM tests — shorthand expansion (17), regex matching (10), error handling (4).
**Remaining**: Device testing — verify toggle appears, glob matching, `^http` anchoring, invalid regex red text.

## Inline Clipboard Editing — COMPLETE (2026-03-27)
Tap pencil icon on text entry → inline EditText with save/cancel. Key routing via IReceiver
pattern (same as search bar). Database: EditEntryResult + per-table update with dedup guard.
COPY semantics preserved across tabs. Mutual exclusion with search mode.
**Bug fixes (8413dc6)**: 3 user-reported bugs resolved with 10 regression tests:
1. Search/edit conflict — entering edit now clears search; routing order swapped (edit before search)
2. Cut drift — content-identity tracking replaces position-based (survives list shifts from cut)
3. Empty text breaks typing — editingInProgressText + TextWatcher + cursor clamping + reload suppression
**Tests (c7c7253, 76bdb2c, 57bfa8b)**: 45 instrumented tests total:
- 23 original (20 direct getView + 3 Activity-hosted Bug #3 lifecycle)
- 16 new: paste (4), cut (5), selectAll (4), save_edit (3) — discovered ClipboardManager name shadow bug
- 6 Espresso UI tests: tap edit/save/cancel buttons, verify view state transitions, media entry guard
All pass on Pixel7 API 34 via emulator.wtf.
**Bug fix (76bdb2c)**: ClipboardManager name shadow — Kotlin resolved same-package class instead of android.content.ClipboardManager, silently breaking paste/cut/long-press copy. Fixed with import alias.
**Bug fix (25f2584)**: Search filter preserved when entering edit mode — only disables search input routing, keeps filter visible.
**Bug fix (bbff54c)**: UI lockdown during edit mode — search bar, tabs, pagination, date filter, entry actions all blocked during edit. Save button disabled when content blank. Paste-from-clipboard-pane uses sendTextDirect() to bypass edit routing. onEditModeExited callback restores UI state.
**Feature tests (38a12ca)**: 55 ClipboardFeatureTest covering tags (16), todo status (7), text-only export (5), export/import roundtrip (5), COPY semantics (4), media model/ref tracking (8), config toggles (2), v2 compat (1), long-press media copy bug (2), export format (4), pinned position (1).
**Feature tests batch 2 (048670d)**: 48 ClipboardFeatureTest2 covering getFormattedText spans (4: strikethrough, prefix, color), toClipboardEntry conversions (3), getRelativeTime boundaries (8: 60s/60m/24h/yesterday/days/week/timestamp fields), formatDate consistency (1), isMedia on PinnedEntry/TodoEntry (2), tagsToJson roundtrip (2), StorageStats accuracy (4: empty/counts/size/expired), edit entry dedup/blank/trim/hash/preserve (8), empty DB export (2), import dedup (2), Unicode roundtrip (2), forward compat (1), entry ordering (3), large content 50KB (2).
**Known bugs documented in tests**:
- Long-press media entry copies filename as plain text instead of URI+image (ClipData.newPlainText vs newUri)
- SettingsActivity export only does JSON — ZIP export exists in BackupRestoreManager but isn't wired from settings
**Remaining**: Device testing — verify delete-until-empty + retype works, search tap blocked during edit, tab switch blocked.

## Content URI Support + Media Clipboard (v3→v4) — COMPLETE (2026-03-26)
All 9 phases implemented and compiling:
1. ✅ Schema v4 migration (mime_type, thumbnail_blob, media_path columns)
2. ✅ ClipboardMediaManager (MIME-aware storage, thumbnails, cleanup)
3. ✅ Content URI streaming in addCurrentClip (IO dispatch + Binder bypass)
4. ✅ MIME-agnostic media capture from system clipboard
5. ✅ Database CRUD for media-aware entries (enriched return types)
6. ✅ Media rendering in clipboard history view (thumbnails + MIME icons)
7. ✅ Media paste via commitContent + FileProvider (all MIME types)
8. ✅ Dual export format (JSON text-only + ZIP full backup with media)
9. ✅ Media clipboard settings + orphan cleanup

**Documentation**: All 8 docs updated for v4 (a8d5127) — spec rewrite, wiki guides, FAQ, privacy, backup-restore, TOC.
**Remaining**: Device testing — build release APK, install, test image/video/PDF copy-paste end-to-end.

## Open Issues — Corrected from GitHub (2026-02-25)

### Phase 1 — Simple Fixes (completed)
- ✅ **#51** KEYBOARD_OPACITY 81→100 (transparent background on first use)
- ✅ **#96** WordListFragment.refresh() preserves search/sort state
- ✅ **#50** Swedish language detection patterns added to LanguageDetector
- ✅ **IssueRegressionTest.kt** — 57 pure JVM tests covering all open bugs

### Phase 2 — Close Already-Completed Issues (user will close)
Issues already implemented, need user verification + close:
- **#21** Subkey system (long-press alternate chars) — already closed
- **#48** Context-aware predictions / inline autofill — already closed
- **#50** Swedish language detection — implemented, still open
- **#62** Password manager clipboard exclusion — already closed
- ✅ **#70** Scoped storage import fix — 6-step fallback + json_base64 bypass (6888f77f7)
- **#74** Haptic not disabled when Vibrate Feedback disabled — per-event controls exist
- **#81** Separate Long-Press Repeat for Backspace vs Character — config exists
- ✅ **#82** Auto-space before tapped suggestion — new `auto_space_before_suggestion` toggle (821f943fb)

### Bugs — Confirmed Real Issues
- **#30** Per-key short swipe customization to keyboard events does nothing
  - v1.1.7.8, Android 16. Customizing P/O/K/J short swipes to keyboard events
- ✅ **#35** Overly dark darkmode — fixed in commit 9213de835 (settings uses DayNight theme)
- **#55** Crashes on ancient phone — Nexus 6 / Android 11 / LineageOS
  - Keyboard exits immediately on use; likely ONNX or memory issue on old device
- **#71** Opening clipboard causes device freeze for 2-3 seconds — **FIX NEXT (priority 1)**
  - v1.2.5, Android 15. ClipboardHistoryView.init{} calls clearExpiredAndGetHistory() sync on UI thread
  - Fix: manual CoroutineScope(SupervisorJob()+Dispatchers.Main) in onAttachedToWindow, cancel in
    onDetachedFromWindow. Do NOT use findViewTreeLifecycleOwner (null in IME context).
  - Move db call to withContext(Dispatchers.IO), replace history reference atomically on Main thread
  - Register OnClipboardHistoryChange callback safely — handler must tolerate pre-load empty state
  - Risk: medium. Isolated to ClipboardHistoryView.kt. Gemini 3.1 Pro validated (2026-03-02)
- **#75** Swipe behaviour broken on Swiss French QWERTZ layout
  - Pixel 8a, Android 16 LineageOS. Swiping on QWERTZ layout misbehaves
- **#77** Cannot completely disable Greek/Math toggle in custom XML layout
  - v1.2.5, Android 15. bottom_row="false" custom layout, Greek/Math key persists
- **#78** Word prediction doesn't replace typed text (flicker issue) — **FIX LAST (priority 3, high risk)**
  - v1.2.5, Android 15. SuggestionHandler calls deleteSurroundingText then commitText
  - Root cause: two separate IPC calls cause visible flicker. Missing batch edit wrapping.
  - Phase 1 (safe): wrap NON-TERMUX path in beginBatchEdit/endBatchEdit. Skip Termux path
    (KEYCODE_DEL/DPAD_RIGHT bypass IC batching, wrapping is useless). Apps that ignore
    batch edit degrade gracefully to current behavior (no worse).
  - Phase 1 bonus: batch edit coalesces onUpdateSelection → contextTracker sees one update
    instead of intermediate "deleted but not replaced" state. Actually HELPS cursor sync.
  - Phase 2 (deferred): composing-aware replacement with setComposingRegion (risky, varies by app)
  - Do NOT call finishComposingText() before commitText (causes duplication)
  - Test in: Chrome URL bar (hardest), mid-word cursor replacement, Samsung OneUI
  - Risk: high (touches typing core). Gemini 3.1 Pro validated (2026-03-02)
- **#79** UI/Header flickering at top of screen during settings scrolling — **FIX SECOND (priority 2)**
  - v1.2.5, Android 15. AnimatedVisibility + expandVertically in 11+ CollapsibleSettingsSections
    inside Column+verticalScroll forces full remeasure on expand/collapse during scroll
  - Phase 1 (now): replace AnimatedVisibility with Modifier.animateContentSize() on inner Column.
    ~5 line change per section. animateContentSize handles conditional if(expanded) content fine.
    Memory concern (always-composed collapsed) is negligible for settings sections.
  - Phase 2 (deferred): LazyColumn migration. Only if scroll jump on collapse is unacceptable.
  - Known limitation: scroll position may jump slightly when section above viewport collapses.
    Only LazyColumn has proper scroll anchoring. Acceptable for Phase 1 (cosmetic fix for cosmetic bug).
  - Risk: low. Gemini 3.1 Pro validated (2026-03-02)
- **#83** "Keys per direction" not used on average length swipe
  - v1.2.5, Android 12. Short swipe keys inaccessible at normal swipe length
- ✅ **#92** Custom background color ignored in custom themes — fixed 737dd0c16
  - Theme.colorKeyboardBackground populated from scheme, Keyboard2View applies programmatically
- **#96** Dictionary search resets after dis/enabling a word
  - v1.2.8, Android 16. Already fixed (WordListFragment.refresh preserves state)
- ✅ **#104** Turning off compose key breaks touchpad/arrow keys — fixed 279890c43
  - Short swipe on null key0 now computes direction directly in deferred nav handler

### Features — Low/Medium Effort
- ✅ **#35** (already fixed) Settings dark mode follows system theme (DayNight)
- **#59** Clipboard delete option + individual item delete + copy timestamps
- **#67** Script error — build_all_languages.py references missing get_wordlist.py
  - `python3 build_all_languages.py --lang fr` fails; get_wordlist.py not in repo
- **#72** Capitalize suggestions for "I" and proper nouns
  - I and contractions (I'll, I'm, I'd) + names should retain capitalization
- **#84** Smart Punctuation with configurable time threshold
  - Only apply smart punctuation within a time interval; precise formatting needed
- **#87** Long swipes should map to short swipes when swipe typing disabled
  - Currently long swipes treated as taps when swipe typing off; should be short swipes
- **#93** Custom Themes: add hex color input field
  - Sliders are hard to use for precise color matching; hex input requested
- **#94** Copy version info on long press of Version Information modal
  - For easier bug reporting — long press to copy version to clipboard
- **#97** Request to disable/remove English dictionary
  - Polish user: English dictionary suggests wrong words; wants to disable it
- **#99** Unclear/outdated documentation of build_langpack.py
  - Hungarian user tried to build langpack; docs incomplete/outdated
- ✅ **#107** Clipboard: Long-press copies to system clipboard (c5e1eb6fc)
- ✅ **#108** Clipboard: Move re-copied text to top of list (72adabb9f)
- ✅ **#109** Improve the look of the autofill suggestion style (72adabb9f + display cutoff fix)
- ✅ **#113** Paste shortcut works in Termux — Ctrl+V fallback for terminal apps (72adabb9f + c5e1eb6fc short swipe fix)
- ✅ **#110** Backspace undo swipe (fixed bug) + backspace undo autocorrect (new feature)

### Features — High Effort / Community
- **#26** Docs: clarify language support + update README comparison table
  - README implies broader language support than actual; needs accuracy
- **#31** Next word prediction / Cyrillic layout prediction
  - T9/prediction only works for English; Cyrillic layouts get no suggestions
- **#49** Turkish language support — dictionary + keyboard layout
- **#52** MessageEase layout contribution + gesture tuning concepts
  - Community layout contribution; novel gesture/UX interaction patterns
- **#58** Scaling number keyboard — number pad wastes space, should resize
- **#61** Switch between more than 2 languages actively
- **#68** Greek dictionary — suggestion to use HeliBoard dictionaries
- **#69** Two finger swiping — unique feature request (Nintype-style)
- **#80** Clipboard Suggestion Strip + UI navigation improvements
- **#88** Arabic language support
- **#90** Custom size for keyboard/row — bottom_row="false" sizing bug
- **#101** Autocorrect/gesture recognition training game
  - FUTO-style training mode to improve neural model in safe environment
- **#111** Expanded comparison table for Urik keyboard

### Deferred
- **#89** Google Play Store Release — requires Play Store account + compliance

### GIF Panel — COMPLETE
- All 9 implementation steps done. File-picker import working.
  User has tested and confirmed GIF pack import/removal/display functional.

---

## Completed (2026-02-28)
- ✅ **Text limit raised + paste_pinned commands** (e78d1a47b):
  - Removed arbitrary 100-char limit on TEXT actions (now 4096, matching MAX_ACTION_LENGTH)
  - Added paste_pinned_1..5 commands to CommandRegistry (CLIPBOARD category)
  - CustomShortSwipeExecutor reads Nth pinned entry via ClipboardDatabase.getInstance()
  - Graceful error: toast "Pinned entry #N not found (X pinned)" when index exceeds pins
  - **ew-cli**: 14/14 PastePinnedCommandTest pass; full suite 789 tests, 788 pass, 1 flaky (VocabularyRankingTest)
  - Wiki/spec docs updated: per-key-actions.md, clipboard-history.md, short-swipe-customization.md
- ✅ **Paste in custom text input** (905bd8d2e):
  - Compose OutlinedTextField in Dialog doesn't receive performContextMenuAction(paste) from IME
  - Added trailing paste icon button using ClipboardManager.primaryClip directly
  - Auto-focuses text field on section open via FocusRequester + LaunchedEffect
- ✅ **#113 short swipe paste in Termux** (c5e1eb6fc):
  - CustomShortSwipeExecutor.handlePaste() sends Ctrl+V in terminal apps
  - Both AvailableCommand.PASTE and CommandRegistry "paste" paths fixed
  - Previously only KeyEventHandler.handlePaste() had terminal fallback
- ✅ **#107 clipboard long-press copy** (c5e1eb6fc):
  - Long-press on clipboard history entry copies text to system clipboard
  - Uses ClipboardManager.setPrimaryClip() with toast confirmation
- ✅ **ClipboardDatabaseTest dedup fix** (c5e1eb6fc):
  - Test expected `false` for duplicate add, but #108 changed to move-to-top (returns `true`)
  - Updated assertion: `assertFalse` → `assertTrue`, message updated

## Completed (2026-03-15)
- ✅ **Contraction suggestion flicker — pipeline symmetry fix** (f6584c33e, c021fc8dd):
  - **Root cause**: SuggestionHandler (typing path) and InputCoordinator (cursor sync path)
    posted different predictions to the same SuggestionBar. Cursor sync fires ~100ms after
    typing, overwriting with stale/different results → visible flicker
  - **Fix**: Made both pipelines produce identical output (paired/non-paired contractions,
    I-word capitalization, exact_add, prefix guard). SuggestionBar deduplicates identical content
  - **Paired contraction prefix guard** (ADR-009): `prefix.length >= 3` blocks single-char
    entries like "t"→"t's" from corrupting frequency ranking
  - **exact_add in cursor sync** (ADR-008): InputCoordinator now appends `exact_add:$word`
    for non-dictionary words, matching SuggestionHandler behavior
  - **Context clearance** (ADR-010): `contextTracker.clearAll()` in onFinishInputView()
    prevents cross-app text leaking
  - Handler.post() replaces View.post() for detached-view safety
- ✅ **Contraction flicker tests** (5960751d0, 0bb56b844):
  - ContractionFlickerTest: 20 instrumented tests (prefix guard, pipeline symmetry)
  - ContractionFlickerIntegrationTest: 7 instrumented tests (real component wiring)
  - JVM source-scanning tests verify both pipelines contain required logic
  - 987 JVM tests, 887 instrumented tests — all pass
- ✅ **Flaky SettingsSearchTest fix** (639c7b989):
  - "backspace" query returned 5 results overflowing 200dp search results card
  - Changed to "backspace undo" (2 results, both visible without scrolling)
- ✅ **Spec/doc updates** (9d730ac48):
  - ADR-008, ADR-009, ADR-010 in architectural-decisions.md
  - Dual pipeline section in core-keyboard-system.md
  - Cursor sync pipeline in cursor-aware-predictions.md
  - Updated test counts in testing-strategy.md, ew-cli skill, MEMORY.md

## Completed (2026-02-27)
- ✅ **#110 backspace undo swipe + autocorrect** (8d4bdf19f, 3a030003d):
  - **Bug fix 1**: swipe undo broken — `wasLastInputSwipe` cleared by `onSuggestionSelected()`
    before backspace handler could check it. Replaced with `lastAutocorrectOriginalWord` null-check
  - **Bug fix 2**: `KeyEventReceiverBridge` never delegated the 5 IReceiver backspace undo methods,
    so both features were dead code at runtime. Bridge now delegates to KeyboardReceiver.
  - **New feature**: GBoard-style backspace undo autocorrect — pressing backspace immediately
    after autocorrect reverts to original word. Does NOT add to dictionary (lighter than suggestion bar undo)
  - New `backspace_undo_autocorrect` toggle (default ON), gated by autocorrect_enabled
  - 26 source-scanning + context tracker tests (BackspaceUndoTest.kt)
- ✅ **#108 clipboard dedup reorder** (72adabb9f):
  - Duplicate clipboard entries now move to top instead of being silently ignored
  - Updates timestamp + expiry on existing row (no delete/reinsert)
- ✅ **#113 Termux paste** (72adabb9f):
  - Paste key sends Ctrl+V key event in terminal apps (Termux, ConnectBot, etc.)
  - Uses TerminalUtils.isTerminalApp() for detection
  - Added getCurrentEditorInfo() to KeyEventHandler.IReceiver interface
- ✅ **#109 autofill chip style + display cutoff** (72adabb9f + fix):
  - Larger text (14sp title, 11sp subtitle), 8dp horizontal padding
  - Max chip width uses display width (was hardcoded 740px)
  - Chip background: lighter gray (#353535), subtle border, 20dp corner radius
  - **Display cutoff fix**: SuggestionBar 8dp padding was stealing 16dp from 40dp container,
    leaving only 24dp for autofill chips. Padding now removed in password/autofill mode.
  - InlinePresentationSpec height aligned to actual 40dp container (was 44dp from resource)
  - Explicit `visibility = VISIBLE` in setInlineAutofillView() for safety
  - **ew-cli instrumented tests**: 15/15 pass on Pixel7 API 34 (SuggestionBarAutofillTest.kt)
- ✅ **#82 auto_space_before_suggestion** (823f2f16e):
  - New boolean toggle: controls leading space before tapped suggestions
  - When disabled: "this:" + tap "english" → "this:english" (no leading space)
  - Swipe auto-inserts always add leading space (preserves word separation)
  - Both code paths gated (SuggestionHandler.kt + InputCoordinator.kt)
  - Settings UI: toggle in Word Prediction section, searchable
- ✅ **GIF panel merged to main** (6d4e6ca6d):
  - Merged feature/gif-panel-clean branch (39 commits)
  - 866 pure JVM tests pass (790 main + 76 GIF)

## Completed (2026-02-25)
- ✅ **56 new instrumented tests** (5e4762c33):
  - TypingSimulationTest: +25 tests (REAL_WORD_CONTRACTION_BASES + extended autocorrect)
  - DictionaryDataSourceTest: +19 tests (disabled filtering, shared cache, toggle coherence)
  - VocabularyRankingTest: +12 tests (contraction scoring, trie, paired base handling)
  - TypingSimulationTest refactored to shared singleton predictor (OOM-resilient)
  - ew-cli: 650 tests, 2 OOM flakes (unrelated), 0 real failures
- ✅ **3 UX fixes** (b13402e88):
  - **Contraction swipe ranking**: Promoted contraction vocabulary entries from
    tier 1 (0.6f freq, 1.0× boost) to tier 2 (0.88f freq, 1.3× boost). Existing
    binary dict synthetic entries also upgraded. Variant fallback raised to 0.85f.
    Words like "don't", "doesn't", "she'll" now compete fairly with common words.
  - **Disabled words in ACTIVE tab**: Added `enabled == true` filter in
    `WordListFragment.loadWords()` and `filter()` for ACTIVE tab only.
    Disabled words now only appear in the DISABLED tab as expected.
  - **Dictionary Manager slow load**: Shared `MainDictionarySource` cache
    (50k words + prefix index) across instances via companion object static
    cache. First open still loads from disk; subsequent opens are instant.
    Added `invalidateCache()` method for language changes.
- ✅ **Prior session fix** (f29fa33eb): Replaced contraction_pairings.json
  file I/O with curated `REAL_WORD_CONTRACTION_BASES` constant set (11 words)
  to avoid false positives ("hes"/"shes"/"intl" are not real English words)

## Completed (2026-02-24)
- ✅ **5 prediction/dictionary bug fixes** (7c509a3d):
  - **Bug 4**: Dictionary Manager toggle not updating UI — stale `cachedWords` in
    `MainDictionarySource.getAllWords()`. Fix: `DictionaryWord.enabled` → `var`,
    update cache in-place on toggle (no 50k-word reload)
  - **Bug 5**: Contraction frequency N+1 perf regression in `OptimizedVocabulary.kt`
    — `getWordsWithPrefix()` called per contraction during scoring. Fix: build
    `contractionFrequencyCache` at load time, O(1) lookup in hot path
  - **Bug 21**: Custom word "Boston" overridden by disabled "boston" in tap typing —
    `isWordDisabled()` unconditional. Fix: track `customAndUserWords` set, exempt
    from disabled check (custom word wins over disabled)
  - **Bug 22**: Paired contractions missing for tap-typing (its → it's) —
    `SuggestionHandler` only called `getNonPairedMapping()`. Fix: add
    `pairedContractions` map to `ContractionManager`, inject paired variants
  - **Bug 23**: Autocorrect not applying contractions (im → I'm) — contraction
    aliases added to dictionary made `autoCorrect()` skip them. Fix: track
    `contractionAliases` map, check before dictionary containsKey
- ✅ **TypingSimulationTest.kt**: 37 instrumented end-to-end tests — ALL PASSING on ew-cli
  - Paired contraction lookup (its, well, were, hell)
  - Non-paired contraction mapping (dont, cant, im, wont)
  - Autocorrect contraction expansion with I-capitalization
  - Dictionary toggle cache coherence (toggle + re-fetch without reload)
  - Custom word override of disabled dictionary entries
  - End-to-end typing scenarios (contraction-heavy sentences)
  - Prediction pipeline integration (scores, ordering, empty input)
  - Verified: 675 total instrumented tests, 0 failures (Pixel7 API 34)
- ✅ **3 additional fixes discovered during ew-cli testing** (4ba4609, baedaea):
  - Test wiring: TypingSimulationTest missing `predictor.setConfig(config)` call
  - Contraction aliases: binary dict contains synthetic "dont"/"cant" entries —
    now uses `contraction_pairings.json` as real-word authority instead of dict check
  - Paired classification: `loadMappings()` now loads JSON paired data after binary
    to fix misclassification of "well"/"were"/"hell" in binary derivation

## Completed (2026-02-17)
- ✅ **Dictionary pipeline spec + skill**: Full architecture documentation
  - `docs/specs/english-dictionary-pipeline.md` — build flow, contraction system, quality issues
  - `.claude/skills/dictionary-pipeline.md` — build commands, key files, pitfalls
  - Traced en_enhanced.bin lineage: V1 (upstream 49k) → V3 (curated 52k, 15 typos removed)
  - Identified remaining misspellings: teh, wich, hav still in V3 dictionary
  - Identified vestigial files: en_enhanced.txt (V1, not loaded), contractions_en.json (duplicate)
- ✅ **Docs fix**: README + wiki langpack commands were broken (missing --input/--dict)
  - Verified all 3 build commands actually run on Termux/ARM64
- ✅ **Misspelling detection pipeline**: `scripts/detect_misspellings.py`
  - Multi-stage: whitelist(NLTK 239k + pyspell 160k + hunspell + British + contractions + possessives)
    → edit-distance-1 matching → zipf frequency gap ≥1.5 → foreign language filter (15 langs)
  - V3 scan results: ~347 wrong plurals, ~592 review candidates in `scripts/misspelling_review.txt`
  - Consulted Gemini 2.5 Pro via PAL for approach guidance (phonetic + frequency + multi-source whitelist)
  - Updated spec and skill with pipeline documentation

## Completed (2026-02-11)
- ✅ **#48 autofill fix**: 3 changes for inline autofill on password fields
  - Removed password mode block in SuggestionBar.setInlineAutofillView() (was silently blocking all autofill)
  - Added android:supportsInlineSuggestions="true" to method.xml
  - Added error logging for null inflate results in InlineAutofillUtils.kt
- ✅ **#50 docs fix**: Fixed README.md and wiki language-packs.md (missing --name argument)
- ✅ **Comprehensive test expansion**: 132 new tests (4 files)
  - TerminalUtilsTest.kt: 28 MockK tests for #70 terminal app detection
  - KeyRepeatLogicTest.kt: 22 MockK tests for #81 backspace-only logic truth table
  - AutoSpaceLogicTest.kt: 25 pure JVM tests for #82 auto-space decision logic
  - IssueRegressionTest.kt: 57 pure JVM tests covering all open issue config defaults
  - Fix #51: KEYBOARD_OPACITY 81→100 (fully opaque default)
  - Fix #96: WordListFragment.refresh()/toggleWord()/deleteWord() preserve search state
  - Fix #50: Swedish character frequency + common word patterns in LanguageDetector
  - LanguageDetectorTest: 4 new Swedish detection instrumented tests
  - ConfigDefaultsTest updated for new opacity default
  - All 781 pure JVM + 176 MockK tests pass
  - **Total tests**: ~1,561 (781 pure + 176 mock + ~604 instrumented)
- ✅ **Persistent memory file**: memory/issue-action-plan.md with full phase plan
- ✅ **Tier 2 instrumented tests**: 58 new tests on emulator.wtf (542 → 600 total)
  - SwipeMLDataStoreTest (36): SQLite CRUD, async store/load, search, pagination, statistics,
    batch operations, SwipeMLData model (JSON round-trip, validation, dedup, normalization)
  - ClipboardDatabaseTest (39): add/retrieve, expiry, pin/todo management, size limits,
    export/import round-trip, ClipboardEntry model, ordering, storage stats
  - LanguageDetectorTest enhanced (18 → 30): detectLanguageWithConfidence,
    detectLanguageFromWordsWithConfidence, unsupported language checks, DetectionResult data class
- ✅ **Production bug fix**: PersonalizationManager.applyFrequencyDecay
  - Fixed ConcurrentHashMap.Entry.setValue UnsupportedOperationException on API 34
  - Replaced removeIf+setValue with explicit iterate+put/remove pattern
- ✅ **OOM test failures fixed**: WordPredictorTest + SwipePredictionTest (12 failures → 0)
  - WordPredictorTest: reflection-based 57-word test dictionary injection
  - SwipePredictionTest: OOM guard on NeuralSwipeTypingEngine construction
- **Total test coverage**: ~898 local (770 pure + 128 mock) + 600 instrumented = ~1,498 tests

## Completed (2026-02-10)
- ✅ **Major feature instrumented tests**: 94 new tests on emulator.wtf (427 → 521 total)
  - SuggestionRankerTest (20): scoring formula, ranking, merge/dedup, language context, prefix boost
  - PersonalizationManagerTest (23): word frequency, bigrams, predictions, decay bug documentation
  - ContractionManagerTest (23): binary/JSON loading, lookup, possessives, language contractions
  - EditorInfoHelperTest (22): action extraction, label/resource mapping, swap enter flag
  - UserAdaptationManagerTest (22): singleton, selection tracking, multipliers, persistence
  - BackupRestoreManagerTest (12): config/dict export-import round-trip, metadata, screen mismatch
- ✅ **Instrumented gap-fill tests**: 36 new tests on emulator.wtf (391 → 427 total)
  - PrivacyManagerInstrumentedTest (13): org.json audit trail, exportSettings JSON, full lifecycle
  - DirectBootInstrumentedTest (11): PreferenceManager paths, device-protected storage,
    copy_preferences all types, checkNeedMigration, DirectBootManager singleton/callback/cleanup
  - DebugLoggingManagerInstrumentedTest (12): BroadcastReceiver register/unregister, debug mode
    toggle via broadcast, sendDebugLog with real Intent, listener notification, full lifecycle
  - Fixed EmojiSearchTest: testGetEmojiNameForUnknownReturnsNull was wrong — isEmoticon heuristic
    classifies ASCII strings >2 chars as emoticons, returning "emoticon" not null
  - All 13 MockK-excluded paths now covered by instrumented tests on real Android
- ✅ **MockK-based test suite**: 128 tests for Android-dependent code (699 → 827 total)
  - PrivacyManagerTest (43): consent, data collection, anonymization, retention, audit trail
  - DirectBootManagerTest (15): singleton lifecycle, unlock detection, DE preferences
  - DirectBootAwarePreferencesTest (4): copy to protected storage, API level branching
  - DebugLoggingManagerTest (9): debug state, listener management, close safety
  - AutocapitalisationTest (12): state machine (started/typed/event_sent/selection/pause)
  - DictionaryManagerTest (14): user word CRUD, legacy migration, language switching, JSON format
  - VibratorCompatTest (11): master/per-event haptic toggles, feedback constants, vibrator fallback
  - SwipePrunerTest (20): already existed, moved from pure to mock runner
  - Added `runMockTests` Gradle task (JavaExec + android.jar on classpath)
  - Added `runAllTests` task combining pure (699) + MockK (128) = 827 total
  - Key techniques: Unsafe reflection for SDK_INT on Java 21, mockkStatic with function refs
    for @JvmStatic, direct field assignment for @JvmField var, Objenesis constructor bypass
  - Excluded paths (require Robolectric): BroadcastReceiver anonymous subclasses,
    Intent constructors, org.json stubs, PreferenceManager AAR dependency
- ✅ **Comprehensive test suite upgrade**: 5-agent team, 287 new tests (412 → 699 total)
  - GestureTest: state machine directions, edge cases
  - ModmapTest: modifier key mapping (Shift/Fn/Ctrl)
  - ComposeKeyPureTest: compose key sequence constants
  - KeyValueParserTest: 73 tests for key definition parsing (named keys, keyevents, macros, flags)
  - CoordinateNormalizerTest: coordinate normalization, key sequence extraction, QWERTY bounds
  - TrajectoryFeatureCalculatorTest: velocity/acceleration from trajectory points
  - NeuralPredictionPureTest: 32 tests salvaged from Robolectric (data classes, quality tiers)
  - IntegrationPureTest: 14 tests salvaged from Robolectric (pipeline integration)
  - onnx/PrefixBoostTrieTest: binary trie loading, prefix boost, failure links
  - onnx/BroadcastSupportTest: tensor shape broadcasting rules
  - SwipePrunerTest: written but excluded from pureTestClasses (requires android.util.Log + MockK)
  - Fixed 12 test failures: parser syntax mismatches, reflection exception wrapping, dedup logic
  - All 699 tests pass on ARM64 via `./gradlew runPureTests`

## Completed (2026-02-09)
- ✅ **Website, Wiki & CI review and fixes**: 3-agent review + 4-agent fix team
  - Wiki: Added 6 missing pages to wiki-config.json (FAQ, quick-settings, password-fields,
    smart-punctuation, user-dictionary, profiles)
  - Wiki: Created docs/wiki/layouts/profiles.md from orphaned HTML (was missing source)
  - Wiki: Regenerated all 42 wiki HTML pages + updated search-index.json (was 36, now 42)
  - Workflows: Deleted redundant setup-pages.yml (conflicted with deploy-web-demo.yml)
  - Workflows: Pinned aquasecurity/trivy-action@0.28.0 (was @master, security risk)
  - Workflows: Added permissions blocks to build.yml and build-apk.yml
  - Specs: Fixed generate-specs.js template (meta viewport, table rendering, code blocks)
  - Specs: Regenerated all 12 spec HTML pages with improved quality
  - Homepage: Synced content with README.md, improved demo placeholder
  - Deploy: Updated workflow to copy search-index.json and all wiki assets
- ✅ **Intent action type review fixes**: addressed 13 issues from 3-agent code review
  - CRITICAL: Fixed setData/setType mutual clearing → setDataAndType()
  - CRITICAL: Fixed executeCommand always returning true (discarded when result)
  - Added null-safe Gson deserialization (IntentDefinition.parseFromGson) for Unsafe bypass
  - Added INTENT_PREFIX constant to IntentDefinition (DRY with KeyValueParser)
  - Fixed URI validation: scheme check instead of useless try/catch on Uri.parse()
  - Extended package validation to SERVICE and BROADCAST targets
  - Fixed single-quote escaping in XmlAttributeMapper TEXT round-trip
  - Fixed CommandPaletteDialog label max (hardcoded 8 → MAX_DISPLAY_LENGTH=4)
  - IntentEditorDialog: removed unused imports, .values()→.entries, variable shadowing fix,
    added contentDescription for accessibility, deduplicate extras before add
  - Compilation verified on ARM64 Termux (1186 classes, 0 errors)
- ✅ **Intent feature unit tests**: comprehensive pure JVM test coverage
  - Created ShortSwipeIntentTest.kt with 55 tests covering all non-Android functionality
  - IntentDefinition.parseFromGson: null-safety, default values, malformed JSON handling
  - IntentDefinition.PRESETS: validation of all 11 preset intents
  - ActionType: fromString case-insensitivity, enum values, display info
  - ShortSwipeMapping: constants, factory methods, getIntentDefinition(), storage keys
  - XmlAttributeMapper.toXmlValue: TEXT quotes, COMMAND mapping, KEY_EVENT prefix, INTENT JSON escaping
  - ShortSwipeCustomizations: round-trip storage format conversion
  - AvailableCommand: fromString, groupedByCategory, display properties
  - All tests follow existing patterns (Truth assertions, @Test annotations, pure JVM)
  - Registered in build.gradle pureTestClasses list for ARM64 runner
- ✅ **Spec updated with INTENT action type**: `docs/specs/short-swipe-customization.md`
  - Added IntentDefinition data class, IntentTargetType enum, parseFromGson docs
  - Added intent validation rules (action/package, installed check, URI scheme)
  - Added execution flow (setDataAndType pitfall, dispatch by target type)
  - Added 11 intent presets table, XML round-trip format, factory/accessor methods
  - Updated architecture diagram, key files table, ActionType enum, storage format example
- ✅ **Cleanup**: restored squoosh submodule to committed pointer, added `.tmp-*.sh` to .gitignore

## Completed (2026-02-04)
- ✅ **Intent action type for short swipe customization**: complete feature implementation
  - Added ActionType.INTENT enum with full serialization support
  - IntentDefinition data class with 11 common presets (browser, share, dial, email, settings, camera, maps, search, termux command)
  - IntentEditorDialog with edit mode, preset selection via FlowRow chips
  - KeyValueParser intent: syntax for XML import/export round-trip
  - Intent validation: package existence check, URI validation, activity resolution
  - canExecuteIntent() for UI validation before saving
  - getIntentDefinition() helper on ShortSwipeMapping
  - XmlAttributeMapper uses quoted JSON: intent:'json'
  - All 357 tests pass

## Completed (2026-02-02)
- ✅ **BeamSearchEngine testability refactor**: extracted DecoderSessionInterface, OrtDecoderSession adapter
  - BeamSearchEngine now fully ONNX-free (all tensor ops behind interface)
  - Shared processLogitsForBeam() eliminates seq/batch duplication
  - Replaced android.util.Log with debugLogger callback
  - 12 beam search tests: scoring, trie masking, batched parity, temperature, dedup
- ✅ **calculateMatchQuality fix**: non-edit-distance mode now uses maxLen denominator
  - "cat" vs "caterpillar" was 1.0 (wrong), now 3/11 ≈ 0.27 (correct)
- ✅ **selectMiddleIndices exposed**: internal visibility + 5 weighted distribution tests
- ✅ **Pipeline integration tests**: 7 tests chaining SwipeResampler → BeamSearchEngine → VocabularyTrie → VocabularyUtils → AccentNormalizer
- ✅ **Gradle runPureTests task**: JavaExec-based JVM test runner, no proot needed
  - Usage: `./gradlew runPureTests [-PtestClass=ClassName]`
- ✅ **ONNX Runtime aarch64 native libs**: tested, blocked by glibc dependency (libdl.so.2 not available on Termux/bionic). Real inference benchmarks require Android instrumented tests.
- ✅ Test suite: 357 tests across 12 classes, all passing

## Completed (2026-01-29)
- ✅ Settings UI: moved Batch Processing, Greedy Search, ONNX Threads from main settings to NeuralSettingsActivity
- ✅ Settings UI: replaced Advanced Neural Settings expander with "Full Neural Settings" button
- ✅ Settings UI: replaced Reset Defaults with Cancel button in NeuralSettingsActivity
- ✅ Wiki audit: completed full pass of ALL ~35 wiki pages against source code
- ✅ Wiki audit: fixed ALL 39 HTML bottom nav buttons (broken relative paths)
- ✅ Wiki audit: fixed installation page (minSdk 21 not 26, ~25MB not 65MB, correct permissions)
- ✅ Wiki audit: added Obtainium and F-Droid installation instructions
- ✅ Wiki audit: fixed first-time-setup default height (30%/40% not 100%)
- ✅ Wiki audit: fixed neural-settings (ONNX threads default 2 not "auto", max length 20 not "varies")
- ✅ Wiki audit: fixed common-issues (removed nonexistent "Export Debug Info", removed "Android 16")
- ✅ Wiki audit: fixed basic-typing (suggestion bar best-match-on-left, removed nonexistent long-press)
- ✅ Wiki audit: fixed smart-punctuation (double-space location → Gesture Tuning)
- ✅ Wiki audit: updated neural-settings page for removed Advanced section → Full Neural Settings button
- ✅ Wiki audit: verified all remaining pages against source code (typing, gestures, customization, settings, clipboard, troubleshooting, layouts, FAQ)
- ✅ Spec files: created neural-settings-spec.md with source references
- ✅ Spec files: updated installation-spec, setup-spec, haptics-spec, appearance-spec with source location tables
- ✅ Deep line-by-line wiki audit (batches 7-10, 35+ pages re-verified):
  - swipe-typing.md: fixed suggestion bar layout (Center/Left/Right → left-to-right by confidence)
  - swipe-typing.md: fixed "Encoder-only" → "Encoder-decoder transformer"
  - autocorrect.md: removed fabricated "tap prediction twice to never autocorrect"
  - special-characters.md: major rewrite — removed fabricated long-press popup section entirely
  - emoji.md: fixed tooltip duration 2.5s → 2s (code: 2000ms)
  - short-swipes.md: fixed long press comparison ("Popup menu" → "Key repeat")
  - extra-keys.md: removed fabricated "Drag to reorder", "Key Order/Size" settings
  - per-key-actions.md: removed fabricated "Reset Customizations Only", "Export Profile"
  - neural-settings.md: fixed "Max Sequence Length" → "Max Word Length", encoder-decoder
  - clipboard-history.md: fixed Per-Key Customization path (added "Activities >")
  - shortcuts.md: removed fabricated "Long-press settings key", "Long-press paste", "Double-tap paste"
  - text-selection.md: fixed TrackPoint activation ("Long swipe from spacebar" → "Long-press nav key")
  - performance.md: removed fabricated "key popup" reference
  - backup-restore.md: fixed all paths to include "Activities >" prefix
  - reset-defaults.md: fixed "Export/Import Settings" → "Export/Import Config"
  - multi-language.md: removed "Long-press or" from subkey access

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
- ✅ Fixed Capitalize I Words for swipe (use globalConfig in InputCoordinator/SuggestionHandler)
- ✅ Renamed "Prediction Tap" to "Suggestion Tap" in haptic settings
- ✅ Fixed master vibration toggle not updating Config.haptic_enabled immediately
- ✅ Added SWIPE_COMPLETE haptic trigger when swipe word is auto-inserted
- ✅ Moved vibration/haptic settings to Accessibility section
- ✅ Fixed vibration not triggering (vibrate_custom must be true for duration to work)
- ✅ v1.2.8 released (includes all v1.2.6 changes for F-Droid)
- ✅ Verified Direct Boot safety: swipe_on_password_fields, dictionary loading all use DirectBootAwarePreferences

## Completed (2026-01-27)
- ✅ Wiki hallucination fixes (batch 4 - 6 pages from subagent audit):
  - privacy.md: removed fabricated Personal Dictionary/Usage Patterns sections
  - privacy.md: fixed clipboard settings (50 items, not 24h duration)
  - language-packs.md: removed fabricated download UI and server browsing
  - language-packs.md: corrected to file import via Backup & Restore
  - autocorrect.md: fixed settings path to Word Prediction section
  - autocorrect.md: removed fabricated "Suggest Contact Names" setting
  - user-dictionary.md: removed fabricated "Long-Press on Suggestion" method
  - common-issues.md: fixed all settings paths to actual section names
  - common-issues.md: removed fabricated "Prediction Bar Height" setting
  - performance.md: removed fabricated "Background Processing" setting
  - performance.md: removed fabricated "Prediction Count" setting
- ✅ Final verification audit (batch 5 - 3 pages):
  - adding-layouts.md: removed fabricated "Language Packs" activity
  - per-key-actions.md: fixed path "Customization" → "Activities"
  - extra-keys.md: fixed path to "Activities > Extra Keys"
- ✅ Comprehensive line-by-line audit (batch 6 - 10 fixes from deep verification):
  - short-swipes.md: fixed path "Settings > Customization" → "Settings > Activities > Per-Key Customization"
  - short-swipes.md: fixed location "Input Behavior" → "Gesture Tuning" for Enable Short Swipes
  - clipboard-history.md: fixed default capacity "25 items" → "50 items"
  - timestamp-keys.md: fixed path "Settings → Layout → Extra Keys" → "Settings → Activities → Extra Keys"
  - timestamp-keys.md: fixed path "Settings → Per-Key Customization" → "Settings → Activities → Per-Key Customization"
  - language-packs.md: fixed 3 paths missing "Activities" level for Backup & Restore
  - adding-layouts.md: removed fabricated "Language Packs activity" reference
  - backup-restore.md: fixed fabricated "View Collected Data" path → "Privacy & Data section"
  - themes.md: fixed path "Settings > Backup & Restore" → "Settings > Activities > Backup & Restore"
- ✅ All 69 wiki pages audited, all settings paths verified against SettingsActivity.kt

## Completed (2026-01-26)
- ✅ Added "Calibrate Per-Key Gestures" as third setup box on launcher screen
- ✅ Updated calibration activity text: "trigger up to 8 subkey actions per key based on direction"
- ✅ Created FAQ document (docs/wiki/FAQ.md) covering common questions
- ✅ Added Help & FAQ section to Settings (bottom, collapsible with expandable FAQ items)
- ✅ Added "Open Full Wiki" button linking to https://tribixbite.github.io/CleverKeys/wiki
- ✅ Made FAQ searchable via Settings search
- ✅ Fixed 240px overflow in calibration slider (50dp → 60dp)
- ✅ Added shared PerKeyCustomizationButton composable (DRY between Settings/Calibration)
- ✅ Corrected FAQ content with verified code behavior:
  - Q subkey: NORTHEAST not north
  - Spacebar cursor: proportional to distance
  - TrackPoint: long-press nav keys, not spacebar
  - Emoji: switch_emoji event, layout-dependent
  - Removed non-existent long-press popup references
- ✅ Wiki audit: identified extensive hallucinations in multiple pages (accessibility, themes, backup-restore, layouts)
- ✅ Further FAQ corrections:
  - TrackPoint: only nav key (between spacebar and enter), not generic "nav keys"
  - Language switch: primary/secondary toggle, not switch_forward/switch_backward
  - Emoji: SW on Fn key, not Ctrl
  - Added clipboard FAQ (Fn swipe, History/Pinned/Todos tabs)
- ✅ Calibration step persistence: shows checked forever after first click
- ✅ Wiki hallucination fixes (batch 1 - 5 pages):
  - FAQ.md synced with SettingsActivity (DRY)
  - installation.md: Android 5.0 not 8.0
  - accessibility.md: rewrote with actual features (haptics/sound only)
  - backup-restore.md: removed fabricated auto-backup, QR, cloud features
  - themes.md: corrected to 35+ themes
- ✅ Wiki hallucination fixes (batch 2 - 7 pages):
  - appearance.md: removed fabricated presets, animation, pop-up settings
  - input-behavior.md: removed fabricated auto-cap modes, delete behavior
  - cursor-navigation.md: removed fabricated Home/End on 'A' key
  - first-time-setup.md: fixed Haptics→Accessibility, beam max 10→20
  - swipe-typing.md: removed Neural Profile, Prediction Count
  - custom-layouts.md: removed fabricated visual editor (it's text-based XML)
  - adding-layouts.md: removed fabricated "Programmer" layout
- ✅ Wiki hallucination fixes (batch 3 - 6 pages):
  - haptics.md: section name Haptics→Accessibility
  - neural-settings.md: removed Show Suggestions, Neural Profiles
  - trackpoint-mode.md: fixed trigger to nav key (not arrow keys)
  - emoji.md: removed fabricated long-press Enter/comma access
  - clipboard-history.md: fixed NW→SW, removed long-press features
  - short-swipes.md: fixed numbers direction N→NE

## Pre-Release Audit (v1.3.0-rc — feature/gif-panel-clean)

### Feature Changelog Since v1.2.9

**New Features:**
1. **GIF Panel (Offline)** — Full offline GIF system with no INTERNET permission
   - File-picker import of ZIP packs (SAF/ACTION_OPEN_DOCUMENT)
   - V4→V5 schema: FTS4 search, pack management, gif_pack_membership table
   - Category browsing (17 emotion categories + Recently Used + All)
   - GIF search via keyboard, compound word fallback (eyeroll → eye* roll*)
   - Tap inserts Giphy URL, long-press shows copy/share PopupWindow
   - Pagination (100 items/page) matching clipboard pattern
   - Settings UI: enable toggle, grid columns, import/remove/list packs
   - Share intent filter for ZIP files
   - 7 new source files: Gif, GifCategory, GifDatabase, GifAssetManager, GifPackManager, GifGridView, GifGroupButtonsBar
2. **Intent Action Type for Short Swipes** — Launch Android intents from swipe gestures
   - IntentDefinition data class with 12 presets (browser, share, termux, maps, etc.)
   - IntentEditorDialog with preset chips + full custom editing
   - Termux: background + visible tab presets with SESSION_ACTION
   - Toast feedback on all dispatch failures
   - KeyValueParser `intent:'json'` syntax for XML round-trip
3. **Discord GIF Pack Pipeline** — Build packs from real Discord usage data
   - build_discord_pack.py: metadata→convert→thumbnail→pack.db→ZIP
   - backfill_metadata.py: Tenor URL resolution + media_url slug parsing
   - Category-based pack builder (build_all_packs.py) with 7 groups
   - Byte-based splitting (~100 MB per pack for GitHub downloads)

**Bug Fixes:**
- Fix: Gif.matchesQuery() case-insensitive (was only lowering query, not searchText)
- Fix: PopupWindow isFocusable=false (prevents content pane disappearing on long-press)
- Fix: GIF search routing through KeyEventReceiverBridge
- Fix: FTS5→FTS4 migration (FTS5 not available on all Android builds)
- Fix: Termux intent SESSION_ACTION + background/visible presets
- Fix: Intent dispatch toast feedback on all failure paths

**Test Coverage:**
- 857 pure JVM tests (was ~781 at v1.2.9)
- 75 new GIF tests (GifTest: 43, GifCategoryTest: 32)
- ShortSwipeIntentTest updated for Termux preset changes
- All tests pass on ARM64 via `./gradlew runPureTests`
- All instrumented tests pass on emulator.wtf Pixel7 API 34
  - Run: `8ab94295-e067-4b42-919b-ccb51e1d6ec4`
  - https://emulator.wtf/o/64da92b3-67fb-427a-b56d-11e62fff8751/r/8ab94295-e067-4b42-919b-ccb51e1d6ec4

**Infrastructure:**
- BeamSearchEngine testability refactor (ONNX-free, DecoderSessionInterface)
- Pipeline integration tests with fake decoder
- calculateMatchQuality length mismatch fix

### Regression Risk Matrix (Manual Testing)

| Area | Risk | What to Test | Why |
|------|------|-------------|-----|
| **GIF panel open/close** | HIGH | Enable GIFs → tap GIF key → panel opens → tap again → closes | New panel may interfere with emoji/clipboard panel state |
| **GIF import** | HIGH | Settings → Import Pack → select ZIP → verify count | Core new feature, ATTACH DATABASE could fail on edge cases |
| **GIF search** | HIGH | Open GIF panel → type search → results update | New search routing through KeyEventReceiverBridge |
| **GIF long-press** | MED | Long-press GIF → popup menu → Copy/Share | PopupWindow focus steal fixed but needs device testing |
| **GIF pagination** | MED | Scroll to bottom → "Next" button → page 2 loads | Offset-based queries, boundary conditions |
| **GIF pack remove** | MED | Settings → remove pack → verify GIFs gone | DELETE + VACUUM + file cleanup chain |
| **Keyboard typing** | HIGH | Normal typing, swipe typing, autocorrect | Any regression in core typing is critical |
| **Short swipe intents** | MED | Configure Termux intent → swipe → command runs | New executor paths with toast feedback |
| **Emoji/clipboard panels** | MED | Open emoji → close → open clipboard → close | GIF panel shares ViewFlipper with these |
| **Content pane switching** | HIGH | GIF → emoji → clipboard → close → type → reopen | Panel state machine with 3 panels + keyboard |
| **Settings search** | LOW | Search "GIF" → settings appear | New searchable entries added |
| **Profile export/import** | LOW | Export profile → import on fresh install | Intent mappings in export format |
| **FTS4 search quality** | MED | Search "laughing" → relevant results | FTS4 compound word tokenization |
| **Pack ZIP validation** | LOW | Import corrupt/invalid ZIP → graceful error | Error handling in GifPackManager |
| **Memory under load** | MED | Import large pack (5000+ GIFs) → browse → type | Coil image loading + RecyclerView recycling |

### Test Commands
```bash
# Build and install release APK
cd ../cleverkeys-gif-module && ./build-on-termux.sh

# Run all pure JVM tests (857 tests)
./gradlew runPureTests

# Run single test class
./gradlew runPureTests -PtestClass=GifTest
```

---

## In Progress
- 🔄 Subkey System Unification (Option D) - awaiting user answers to clarifying questions
  - See: `memory/subkey-unification-research.md`
- 🔄 **GIF Panel (Offline)** — merged to main, pending device testing
  - Spec: `docs/specs/gif-panel-spec.md`
  - Release: https://github.com/tribixbite/CleverKeys/releases/tag/CleverKeys-GIF (prerelease)
  - Architecture: No INTERNET permission. ZIP pack import via file picker (SAF).
  - Schema: V5 with FTS4, gif_pack_membership table
  - All 9 implementation steps complete + bug fixes applied
  - Discord community pack: 6 ZIPs (575 MB, 5,232 GIFs) on GitHub pre-release
  - Remaining:
    - [ ] Test import of discord-community packs on device
    - [ ] Build release APK and install on device
    - [ ] Manual regression testing (see Regression Risk Matrix above)
    - [ ] Verify: search "eyeroll", long-press popup, pagination controls
    - [ ] Rebuild Giphy packs with updated pipeline (compound word indexing)
    - [ ] Publish packs to GitHub Releases
    - [ ] Legal review of GIF content redistribution

## Completed (2026-01-25)
- ✅ Subkey system investigation: XML subkeys, ShortSwipeCustomizationActivity, ExtraKeys
- ✅ Created research doc with Options A-D analysis
- ✅ Documented 5 clarifying questions for Option D implementation

## Completed (2026-01-24)
- ✅ French contraction frequency ordering (qu'est can now appear before quest if higher frequency)
- ✅ Full AndroidX migration (ExtraKeysPreference, ListGroupPreference)
- ✅ Added ONNX JVM runtime for unit tests (onnxruntime:1.20.0)

## Completed (2026-01-23)
- ✅ Fixed French contractions showing both forms (quest + qu'est)
- ✅ Fixed CI test failures (ComposeKeyTest, OnnxPredictionTest)
- ✅ Partial AndroidX migration (PreferenceManager classes)
- ✅ Fixed 3 broken wiki links

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
- [x] Move Vibration settings to Accessibility section (v1.2.8)
- [ ] Move Smart Punctuation to Auto-Correction section

### Web Demo P2
- [ ] Lazy loading for 12.5MB models
- [ ] PWA/Service Worker for offline support

### Clipboard Performance (from post-v1.3.0 review)
- [ ] RecyclerView Migration: `ClipboardHistoryView` extends `NonScrollListView` — migrate to RecyclerView for scroll perf with many entries (ViewHolder, LayoutManager, drag/swipe/long-press callbacks)
- [ ] Explicit State Machine: Replace supplementary booleans in `KeyboardReceiver` + `ClipboardManager` (`isContentPaneShowing`, `gifSearchActive`, `searchMode`, `tagMode`, `isEditing()`) with single `ModalState` enum to prevent impossible state combinations

### Legacy Code Cleanup
- [x] Migrate deprecated `android.preference.*` to `androidx.preference.*` (v1.2.10)

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
# Instrumented (emulator.wtf) — 1128 tests, 0 failures
ew-cli --app build/outputs/apk/debug/CleverKeys-v1.3.0-x86_64.apk \
       --test build/outputs/apk/androidTest/debug/CleverKeys-debug-androidTest.apk \
       --device model=Pixel7,version=34 --use-orchestrator --timeout 15m \
       --outputs-dir ~/ew-output

# Local JVM (Gradle — preferred)
./gradlew runPureTests                           # 1046 pure JVM tests
./gradlew runMockTests                           # 176 MockK tests (needs android.jar)
./gradlew runAllTests                            # all ~1222 tests
./gradlew runPureTests -PtestClass=ClassName     # single class (pure)
./gradlew runMockTests -PtestClass=ClassName     # single class (mock)
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
