# Comprehensive 9-subsystem audit — master todo (2026-09-06)

**HEAD audited**: `e412ef7a` (working tree clean at audit time).
**Method**: nine parallel subsystem auditors (A: IME core service + input pipeline; B: swipe
engines end to end; C: prediction + dictionary + suggestions; D: clipboard end to end;
E: emoji + GIF panels; F: settings + config; G: backup/restore + langpack + autofill;
H: theming + layout + rendering; I: ml/persist/debug/privacy root files) under a
verified-only contract: each finding required a code-level proof chain, an active
refutation attempt, and dedupe against `docs/audit/2026-08-28-archive-verification.md`,
`memory/HANDOFF.md`, and `docs/audit/gh-issue-resolution.md`. Findings are reasoned from
source unless a finding says otherwise (D-2 ran an executed regex simulation); nothing was
executed on-device.

**Counts** (70 raw findings; 69 after merging the one confirmed cross-auditor duplicate,
A-3 ≡ E-2):

| | P1 | P2 | P3 | total |
|---|---|---|---|---|
| **by severity** | 8 | 24 | 37 | 69 |

| class | bug | missed | stub |
|---|---|---|---|
| count | 49 | 14 | 6 |

| subsystem (post-merge ownership) | A | B | C | D | E | F | G | H | I |
|---|---|---|---|---|---|---|---|---|---|
| items | 5 | 2 | 9 | 10 | 10 | 10 | 6 | 9 | 8 |

**Merge decisions**: A-3 and E-2 are the same defect (pane openers never clear the other
panes' search-routing flags; both auditors traced the identical
`KeyboardReceiver` opener branches and `KeyEventHandler.sendText` priority chain) — merged
as one item citing both, keeping A-3's three concrete failure scenarios plus E-2's DEL-ladder
observation. No other pair collapsed to one defect: D-7/E-10 are the same *bug class*
(Toast in IME context) at disjoint code sites; F-4/F-9/F-10/G-5 are the same *class*
(validator/UI/Config range disagreement) on disjoint keys; C-2/C-3 are distinct defects in
the same function; I-1/I-8 are a chain (I-8 weaponizes I-1's recording path) but distinct
injection points. These stay separate rows, clustered by wave, and are named in
§Cross-cutting.

---

## Master todo table

Ordered P1 → P2 → P3; within a severity, clustered by fix-wave (§Fix-wave partition).
`where` keeps each auditor's primary proof pointer; full proof chains live in the source
findings (scratchpad `audit6/findings-{A..I}.md`) and are summarized one-line here.

### P1 (8)

| id | P | class | one-line | where | fix-shape | test-shape |
|---|---|---|---|---|---|---|
| B-1 | P1 | bug | Recognizer key gate is hard-coded a–z, so swipe typing can never trigger on any non-Latin board — all six routed script languages (ru/el/uk/bg/mk/he), their shipped models/langpacks, and geometric's non-Latin coverage are unreachable from a real touch stream | gesture/ImprovedSwipeGestureRecognizer.kt:402-410 (#isValidAlphabeticKey); ProbabilisticKeyDetector.kt:232 | Replace the `'a'..'z'` test with `Char.isLetter` (same predicate KeyLetter.centreLetterOf already uses) in both files | ew-cli: drive the recognizer over a built cyrl_jcuken_ru KeyboardData; assert promoteWordCandidacy() true and endSwipe().keys non-null (fails today); or extract the predicate into a pure helper and pin in runPureTests |
| A-2 | P1 | bug | Theme change replaces `_keyboardView` but never re-points KeyboardReceiver/LayoutBridge/InputCoordinator/ConfigPropagator, all of which captured the OLD view — layout-switch keys (123/ABC/cycle) and autocap shift go dead until process restart | CleverKeysService.kt:586-604 (#onThemeChanged); KeyboardReceiver.kt:56-66,194-203,447-461,727-737; wiring/KeyboardComponentGraph.kt:364-379 | Recreate the receiver after view recreation, or make the view reference late-bound (provider lambda `() -> Keyboard2View`) in KeyboardReceiver/LayoutBridge/InputCoordinator/KeyboardComponentGraph | Mock-tier: construct receiver with view A, swap to view B as onThemeChanged does, fire SWITCH_NUMERIC, assert setKeyboard landed on B (fails: lands on A) |
| E-1 | P1 | bug | `recordGifUsage` uses `INSERT ... ON CONFLICT DO UPDATE` (SQLite ≥3.24); API 24-28 ship 3.9.2-3.22, so every GIF tap on those devices throws SQLiteException out of an unhandled `scope.launch` — IME process crash | gif/GifDatabase.kt:209-218 (#recordGifUsage); crash path gif/GifGridView.kt:221 | Portable two-statement upsert (UPDATE then INSERT if 0 changed, or INSERT OR IGNORE + UPDATE) inside the existing withContext(IO) | ew-cli pinned to an API 28 device: recordGifUsage twice, assert use_count==2 (fails with SQLiteException); pure-JVM impossible (needs Android's SQLite parser) |
| C-4 | P1 | bug | `DictionaryManager.saveUserWords` rewrites the whole `custom_words_<lang>` pref from a stale in-RAM membership set — the next IME-side add silently DELETES custom words added via the Dictionary Manager UI or a backup import (and resurrects UI-deleted ones) | DictionaryManager.kt:292-306 (#saveUserWords), :262-282 (#loadUserWords); SuggestionHandler.kt:1724,1774,1844 (IME add paths) | Merge membership against the freshly-read stored map (union stored keys minus explicit removals), or re-run loadUserWords() before every mutation | Mock-tier: write `{"flurble":255}` directly to the pref, call addUserWord("zeb"), re-read pref — assert BOTH words present (currently only "zeb") |
| F-1 | P1 | bug | "Reset Settings" does `editor.clear()` on the shared prefs file and re-seeds only ~45 settings keys — silently destroys `custom_words_<lang>`/`disabled_words_<lang>` (the custom dictionary), `layouts`, `extra_keys*`, per-language prefs, and migration markers, while the dialog promises only "reset all settings" | ui/settings/SettingsResetPresets.kt:62-63 (#resetAllSettings) | Drop `editor.clear()`; explicitly remove/re-put only settings keys, or clear-with-exclusions preserving `custom_words_*`, `disabled_words_*`, `layouts`, `extra_keys*`, INTERNAL_KEYS markers | Pure JVM with in-memory prefs: seed `custom_words_en` + markers, run the reset body (extracted), assert `custom_words_en` survives (fails today) |
| G-1 | P1 | bug | langpack `manifest.code` is used unvalidated as a filesystem path — a pack ZIP with `code=".."`/`"../.."` makes the importer `deleteRecursively()` filesDir or the app-data root and installs there (zip ENTRY names are sanitized, manifest.code is not) | langpack/LanguagePackManager.kt:124-128 (#importFromStream); parseManifest :170 | Validate `manifest.code` against `[a-z]{2,3}(_[a-z0-9]{2,8})?` (at minimum reject empty/`.`/`/`/`\`) and canonical-path-check `packDir` stays under langpacksDir | LanguagePackImportTest: pack with `"code":"../evil"` + a sentinel file in filesDir; import must return Error and the sentinel must survive (today: Success, installs to files/evil/) |
| I-1 | P1 | bug | Playground trace recording is bound to onCreate/onDestroy — Home-backgrounding SwipeDebugActivity leaves IME debugMode ON, so every subsequent swipe in ANY app (committed word + full trace + geometry + candidates) persists to swipe_ml_data.db, deliberately bypassing all privacy gates | activities/SwipeDebugActivity.kt:210,222; SuggestionHandler.kt:915; ml/PlaygroundTraceRecorder.kt:55-63 | Move enable/disable to onStart/onStop (or onResume/onPause) AND have the recording branch verify the target editor's packageName == this app's, so a stale flag can never record foreign-app typing | ew-cli: launch SwipeDebugActivity, moveTaskToBack, swipe in a test EditText, assert `countBySource("playground")` did not grow |
| H-2 | P1 | bug | Deleting the currently-active custom theme leaves `theme=custom_<uuid>` dangling — the next Keyboard2View inflation throws `IllegalStateException("Custom theme not found")` uncaught → IME crash loop until the theme pref changes (same class for stale `decorative_*` ids via backup restore) | theme/ThemeProvider.kt:252-256 (#loadCustomTheme); activities/ThemeSettingsActivity.kt:447; Keyboard2View.kt:173-177; CleverKeysService.kt:407,588,628,986-993 | Make loadCustomTheme/loadDecorativeTheme fall back to a base theme (log + loadBuiltInTheme), and/or reset the `theme` pref when the active custom theme is deleted | `ThemeProvider.getTheme("custom_nonexistent")` currently throws — assert it returns a usable Theme after the fix; instrumented repro: select custom theme, delete it, inflate keyboard → InflateException today |

### P2 (24)

| id | P | class | one-line | where | fix-shape | test-shape |
|---|---|---|---|---|---|---|
| A-1 | P2 | bug | KeyEventReceiverBridge does not delegate `showPrivateCopyFeedback` (3rd instance of the documented bridge-gap class) — every in-IME `copy_private` feedback message (success, "no selection", failures) silently drops to the interface no-op default; a failed private copy is indistinguishable from success | wiring/KeyEventReceiverBridge.kt (missing override); KeyEventHandler.kt:742-754,1072; KeyboardReceiver.kt:752 | Add the missing override; add a reflection drift test asserting the bridge overrides every IReceiver member so a 4th instance is impossible | Pure JVM: bridge + mock receiver, call showPrivateCopyFeedback("x") through the IReceiver interface, verify the mock received it (fails: default no-op) |
| A-3, E-2 | P2 | bug | Pane openers (SWITCH_EMOJI/CLIPBOARD/GIF) evict the showing pane via `removeAllViews()` but clear none of its routing flags — a direct pane-to-pane switch leaves a stale higher-priority flag shadowing the live pane: typing (and the DEL ladder) is swallowed by a DETACHED search EditText, clipboard search filters a hidden list, and the GIF key toggle-inverts (closes everything instead of opening GIF) | KeyboardReceiver.kt:205-263,265-299,301-402 (openers) vs :404-425 (SWITCH_BACK does clear); emoji/EmojiSearchManager.kt:254-256; KeyEventHandler.kt:318-350,109-115 | Extract a `closeCurrentPaneRoutingState()` helper (emoji onPaneClosed + clipboard resetSearchOnHide + gif flag/input clear — the SWITCH_BACK prologue) and call it at the top of each opener | Extend KeyboardReceiverPaneHostTest: open emoji, fire SWITCH_CLIPBOARD (or SWITCH_GIF), assert `isEmojiPaneOpen()` false (fails: stays true); companion: gif→emoji→gif must OPEN gif, not close all |
| A-5 | P2 | bug | Double-space-to-period trusts `lastTypedChar==' '` (never invalidated by backspace, cursor moves, or swipe commits) and never checks a space actually precedes the cursor — space→backspace→space deletes a letter ("hix"→"hi. "); space→swipe→space turns the auto-space into an unrequested period | KeyEventHandler.kt:362-381 (#sendText double-space branch), :43-47 | Require `textBefore?.length == 2 && textBefore[1] == ' '` alongside the existing alphanumeric check; reset lastTypedChar on backspace | Pure JVM with a scripted InputConnection fake holding "hix": " ", KEYCODE_DEL, " " within threshold; assert final "hix " (fails: "hi. ") |
| E-3 | P2 | bug | GIF compound-word search fallback ("eyeroll"→"eye* roll*") runs only at offset==0 but countSearchResults applies it unconditionally — pagination advertises N pages and every page after the first is blank | gif/GifDatabase.kt:51-60 (#searchGifs), :81-90 (#countSearchResults); gif/GifGridView.kt:176-187 | Cache the effective FTS query chosen by the fallback and paginate against it (or run the fallback probe regardless of offset) | DB with 150 "eye roll" rows, none "eyeroll": searchGifs("eyeroll",100,100) returns 0 while countSearchResults returns 150 |
| E-4 | P2 | bug | Init ALL-fallback never updates `currentCategory` (stays RECENTLY_USED) — fresh install with a big pack: next-page reloads recently-used (empty) and blanks the grid; also getGifsByCategory ignores offset for RECENTLY_USED (page 2 repeats page 1) and search("") reset has no ALL fallback | gif/GifGridView.kt:87-94 (#init), :162-187; gif/GifDatabase.kt:141-148 | Set `currentCategory = ALL` in the init fallback (or route through setCategory); thread offset through getRecentlyUsedGifs | Seeded DB (500 gifs, empty usage): after init, nextPage() should render items 101-200; today gifList is empty |
| E-5 | P2 | bug | GIF "No results" indicator reads getResultCount() synchronously right after the async (150ms-debounced) search launch — it reflects the PREVIOUS query and effectively never shows for a zero-match query | KeyboardReceiver.kt:359-366 (#SWITCH_GIF TextWatcher); gif/GifGridView.kt:113-140 | Fire a results-count callback from the end of search's coroutine (or extend onPaginationChanged) and set noResults visibility there | Instrumented: type a no-match query, advance past debounce, assert gif_no_results VISIBLE (fails: GONE) |
| E-6 | P2 | bug | GIF category bar fires selection on ACTION_DOWN inside a HorizontalScrollView (19×44dp always overflows) — every scroll gesture switches category and wipes the in-progress search before the HSV can intercept | gif/GifGroupButtonsBar.kt:108-117 (#onTouch), :44-76; wiring KeyboardReceiver.kt:396-401 | Fire on ACTION_UP within touch slop (or setOnClickListener, which cooperates with scroll interception) | Instrumented: with a query set, dispatch DOWN on a button, MOVE beyond slop, UP elsewhere; assert query survives and category unchanged (fails today) |
| H-3 | P2 | bug | Emoji and GIF panes never receive runtime (custom/decorative) theme colors — all their colors are XML `?attr/*` resolved against the hardcoded CleverKeysDark base style, with no post-inflation repaint (the #130 class on two unfixed surfaces) | res/layout/emoji_pane.xml, res/layout/gif_pane.xml (all `?attr/color*`); CleverKeysService.kt:986-993 (#inflate_view); Config.kt:1144-1151 (#getThemeId) | Apply the clipboard-pane pattern (a7940256): after inflation paint backgrounds/labels from `keyboardView.getTheme()` when `snap.isRuntimeTheme` | Instrumented: select a runtime theme with a distinctive background, open emoji pane, assert the pane root color equals the scheme color (today: CleverKeysDark's) |
| C-1 | P2 | bug | `WordPredictor.needsReload` is set by Dictionary Manager edits and never cleared — after ONE edit, EVERY subsequent prediction re-reads prefs + provider and rebuilds the full ~98k-word prefix index, for the rest of the process lifetime | WordPredictor.kt:183,453-460 (#checkAndReload), :190-193; activities/DictionaryManagerActivity.kt:501 | Replace the boolean with a signal counter honored once per instance (or simply clear the flag — one instance exists since ARC-079) | Pure/mock: signalReloadNeeded(), invoke predictWordsWithScores twice, count custom-word-load invocations — currently 2, expected 1 |
| C-2 | P2 | bug | Wave-U2 calibration missed `handleIncrementalUpdate` — observer-delivered words (platform dictionary rows, backup-imported custom words) enter the calibrated dictionary at RAW 1..255, ranking below the entire base dictionary until a full reload; also misses `customAndUserWords` (disabled-word override + floor exemption) | WordPredictor.kt:359-379 (#handleIncrementalUpdate); UserDictionaryObserver.kt:283,341; commit 4525eb9c | Calibrate inside handleIncrementalUpdate via `baseFrequencySpanOf` + `UserWordFrequency.scaleOnto`; add added words to customAndUserWords | Mock: load a binary-scale map, fire onCustomWordsChanged(mapOf("flurble" to 255)), assert dictionary["flurble"] ≈ scale ceiling (currently 255, below base floor) |
| C-3 | P2 | bug | Removing a custom word that shadows a base dictionary word deletes the BASE word from the serving dictionary (custom overwrote the shared map entry; removal removes it; no reload path re-reads the base) — bundled word unreachable until language switch/restart | WordPredictor.kt:362-366 (removal), :1726-1727 (custom overwrites base) | On removal, restore the base entry (retain a shadowed-base-value map at custom-load time, or consult the base source) | Mock: seed base hello→800000, add custom hello→255, remove it via onCustomWordsChanged; assert dictionary.containsKey("hello") (currently false) |
| C-5 | P2 | missed | Autocorrect's contraction step 0 bypasses contraction guard #4 — `contractionAliases[lowerTypedWord]` rewrite runs BEFORE the in-dictionary short-circuit with no `isUserWordIgnoringCase` check, so a personal-dictionary word that is an alias key (fr "dangle"→"d'angle"; en "dont"/"im") is rewritten on commit — the exact in-slot destruction the guard exists to prevent | WordPredictor.kt:2198-2210 (#autoCorrect step 0) vs SuggestionHandler.kt:313-317 (where the guard lives) | Check the user-word set in autoCorrect step 0 (and reroutes for symmetry) before returning the alias mapping | Mock (AutoCorrectEndToEndTest injects contractionAliases): add "dont" to custom words, autoCorrect("dont") must return "dont" (currently "don't") |
| D-1 | P2 | missed | "Exclude Password Managers" (default ON) can essentially never exclude anything: UsageStats needs the PACKAGE_USAGE_STATS app-op the manifest never declares (user can't even grant it), and the getRunningTasks fallback is dead since API 21 — PM clips ARE captured on any device <33 / any PM not setting IS_SENSITIVE | AndroidManifest.xml:5-14; clipboard/ClipboardHistoryService.kt:585-625 (#getForegroundAppPackage), :646-657; ui/settings/sections/ClipboardSection.kt:172-177 | Declare PACKAGE_USAGE_STATS + a grant-usage-access affordance — or drop the package-list mechanism, make IS_SENSITIVE the documented mechanism, and re-word the setting (maintainer choice, see §Deferred) | Instrumented: toggle ON, no usage grant, fire the clip listener with a PM "foreground" — assert clip NOT stored; fails today (detection returns null) |
| D-2 | P2 | bug | URL sanitizer redirection results are never percent-decoded (upstream ClearURLs decodes; this port takes the capture verbatim, and the bundled rules capture percent-ENCODED groups) — cleaned redirector links become broken `https%3A%2F%2F...` strings, optionally written back to the OS clipboard | clipboard/sanitize/UrlSanitizer.kt:84-93 (#sanitizeOne redirections) | For the `replacement == null` branch, repeatedly URLDecoder.decode the captured group until stable (mirroring upstream decodeURL), try/catch to raw capture | Pure-JVM UrlSanitizerTest against bundled clearurls.json: process an l.facebook.com share link, assert result starts `https://example.com/` (fails today; executed regex simulation confirms) |
| D-3 | P2 | bug | TODOS tab default status filter (active-only) hides planned/completed todos while `hasActiveFilters()` reports "no filters" (icon untinted) — cycling a todo to planned/completed makes it vanish looking like deletion; contradicts the todo-skill contract ("all three checked on first open") AND itself (applyFilter's own comment calls the state a filter) | clipboard/ClipboardHistoryView.kt:133-136, :394-401, :1043-1052, :269-284 | Either default all three true (matching the skill) or keep active-only AND make hasActiveFilters tint for it — plus align the skill doc (default choice → §Deferred; the tint disagreement is a straight bug) | Seed one active + one completed todo, open TODOS with defaults → both visible per skill (fails); or assert hasActiveFilters()==true whenever applyFilter's hasStatusFilter is true (fails for T,F,F) |
| F-2 | P2 | bug | Reset also wipes `margin_prefs_version` — next process start re-runs migrateMarginPrefs, reinterpreting the freshly-written PERCENT margins as legacy dp (`value*density/screenWidth*100`): a post-reset 10% becomes ~2%, the reseeded 1% default becomes 0% | ui/settings/SettingsResetPresets.kt:63 + Config.kt:1602-1693 (#migrateMarginPrefs) | Reset must re-put the markers it clears (`margin_prefs_version`, `version`, `vibrate_custom_migration_v1`) — or stop using clear() per F-1, which fixes this too | In-memory prefs: run reset body, assert margin_prefs_version == MARGIN_PREFS_VERSION; or reset + migrateMarginPrefs, assert margin_left_portrait unchanged (fails today) |
| F-3 | P2 | bug | Space-slider sensitivity 0% is UI-selectable → `slide_step_px = 0f` → `d += Δ/0f` = ±Infinity → after Int saturation + short truncation, a right-swipe emits repeat −1 (cursor moves LEFT once per move event) and a left-swipe emits 0 (nothing) | Pointers.kt:1728-1730 (#Sliding.onTouchMove); ui/settings/sections/InputBehaviorSection.kt:344 (valueRange 0f..100f); Config.kt:711-712 | Floor sensitivity at read time (`coerceAtLeast` before scaling, or `slide_step_px.coerceAtLeast(ε)`), or make the UI minimum 1 | Pure JVM: ConfigSnapshot fixture with slide_step_px=0f, drive Sliding.onTouchMove rightward, assert emitted repeat positive (fails: emits −1) |
| F-4 | P2 | bug | Export→import round trip rejects values the settings UI legitimately allows on 3 keys: `longpress_interval` (UI 25..200 vs validator 5..100), `character_size` (UI 0.5..2.0 vs 0.75..1.5), `custom_border_line_width` (UI 0..10 vs 0..5) — user's own setting comes back "skipped: out of range" | backup/SettingsValidation.kt:314,358,367 vs ui/settings/sections/InputBehaviorSection.kt:370, AppearanceSection.kt:187,265 | Widen the three validator ranges to the UI ranges via a single shared constant per key (GeoKnobRanges-style); add a drift test comparing slider valueRange literals against SettingsValidation ranges | `validate("longpress_interval", IntV(150))` — expect null, currently "out of range" (same for FloatV(1.8f) character_size, FloatV(8f) border width) |
| F-5 | P2 | bug | Default swipe-trail effect "sparkle" (fully implemented in the renderer) is missing from the Swipe Trail dropdown — fresh installs DISPLAY "Glow" while sparkle renders, and first touch of the dropdown makes the shipped default permanently unreachable from the UI; glow-radius slider also hidden while sparkle (which uses the glow base) is active | ui/settings/sections/SwipeTrailSection.kt:35-54; Config.kt:128; Keyboard2View.kt:339,1622 | Add "Sparkle" to the options list with index mapping (and show the glow-radius slider for sparkle too) | Source-scan drift test: every effect literal the renderer branches on must appear in SwipeTrailSection's option mapping (fails today) |
| F-6 | P2 | stub | "Pin Entry Layout" switch writes `pin_entry_enabled`, whose only runtime reader is Config.migrate's one-time seeding of `number_entry_layout` — which runs at first launch before Settings can ever be opened, so the switch changes nothing on any install (bonus: declared default true vs UI read-site default false) | ui/settings/sections/InputBehaviorSection.kt:483-491; SettingsPersistence.kt:151-153,316; Config.kt:1531-1544 | Make the switch write `number_entry_layout` ("pin"/"number") directly — or delete the control and deprecate the pref | Drift-style pure test: every section-written key must have a runtime reader outside the settings surface (whitelisted); pin_entry_enabled fails today |
| H-1 | P2 | bug | Custom short-swipe `switch_forward`/`switch_backward` (Command Palette form) executes TWICE — the Kind.Event branch dispatches without returning, then the legacy `getCommand()` block dispatches again; with 2 layouts enabled the swipe appears dead (wraps back) | Keyboard2View.kt:777-820 (#onCustomShortSwipe); customization/CustomShortSwipeExecutor.kt:565-568; ShortSwipeMapping.kt:179-183 | `return` after successful Event/Editing dispatch in the keyValue branch (or skip the legacy `when` once handled) | Install a COMMAND/"switch_forward" mapping, invoke onCustomShortSwipe, count triggerKeyboardEvent on a recording service stub — expect 1, get 2 |
| H-4 | P2 | missed | Nine Theme-Creator color fields (suggestionText/Background/HighConfidence, ripple, keyLocked, keyModifier, keySpecial, keyBorderActivated, keyboardSurface) are editable + persisted but consumed by nothing — the whole "Suggestion Bar" editor section is a silent no-op; only swipeTrail escapes (and even it goes stale when the ACTIVE theme is edited, since sync runs only on select) | theme/KeyboardColorScheme.kt:21-50; Theme.kt:102-134 (discards the rest); activities/ThemeSettingsActivity.kt:839-863,191-218 | Wire the fields through Theme (or remove the dead editor rows — §Deferred); re-sync swipe_trail_color on save of the active theme | Pure: Theme(context, scheme) with sentinel values in the nine fields, assert they surface (fails: e.g. lockedColor == keyLabel, not keyLocked) |
| H-5 | P2 | bug | `modify_layout` cache keyed by nullable layout NAME — two enabled layouts sharing a name (default custom-layout flow seeds `name="QWERTY (US)"`, duplicating stock QWERTY; unnamed customs share "") get served each other's boards on switch, and the later version-bump refresh only re-lays-out the SAME stale keyboard | LayoutModifier.kt:31-32 (#modify_layout cacheKey); KeyboardData.kt:547; Config.kt:1015-1026 (no sync version bump) | Key the cache on layout identity (KeyboardData instance in an IdentityHashMap, or identityHashCode) plus version, not the nullable name | JVM mock-tier: two unnamed KeyboardData with different rows, modify_layout on both under one config version, assert the second result matches its input (fails: returns the first's rows) |
| I-2 | P2 | missed | Data-retention/auto-delete/anonymization layer is dead code — MLDataCollector's KDoc promises retention enforcement, PrivacyManager defaults autoDeleteEnabled=true/90 days, but nothing calls shouldPerformCleanup/getDataRetentionCutoff and SwipeMLDataStore has no delete-by-age — once collection is enabled, the DB grows unboundedly forever | PrivacyManager.kt:355-379; MLDataCollector.kt:25-26,48-49,64; ml/SwipeMLDataStore.kt (no age-delete path) | On store (or IME create, daily-throttled), delete rows older than getDataRetentionCutoff() when auto-delete enabled; implement or delete the anonymization surface and fix the KDoc | Robolectric/instrumented: insert a 91-day-old row, run the cleanup entry point, assert gone — today no such entry point exists |

### P3 (37)

| id | P | class | one-line | where | fix-shape | test-shape |
|---|---|---|---|---|---|---|
| B-2 | P3 | stub | gesture/SwipeInput + gesture/SwipePruner are production-dead (ADR-011 orphans); a stale ProGuard keep ships SwipeInput in every minified release under a false "prediction input handling" comment | gesture/SwipeInput.kt:9; gesture/SwipePruner.kt:12; proguard-rules.pro:128-129 | Delete both classes, their tests, the build.gradle SwipePrunerTest references, and the keep rule | Verifiable by rg (no production refs) and R8 usage.txt no longer listing SwipeInput |
| A-4 | P3 | bug | Selection sliders leak to the target app's InputConnection during clipboard edit mode — the edit-mode branch maps only plain Cursor_* keys; Selection_cursor_* falls through to `moveCursorSel` on the APP's connection, silently moving the hidden field's selection during modal edit | KeyEventHandler.kt:798-824 (#handleSlider) | In the edit-mode branch, map Selection_cursor_* to shift+DPAD dispatches into the edit field or swallow them | Pure JVM, mock IReceiver with isClipboardEditMode()=true: fire Selection_cursor_right, assert app InputConnection received no setSelection (fails today) |
| E-7 | P3 | bug | sanitizeFtsQuery strips `"'*-` but not `:` — a colon compiles to FTS4 column-filter syntax, the MATCH errors on the unknown column, the catch swallows it, search reports zero results; also the constant `gid` token is prefix-matched by 1-3 char queries on all new-pack rows | gif/GifDatabase.kt:593-603 (#sanitizeFtsQuery), :116-119 | Strip non-`[a-z0-9 ]` (matching the compound-fallback normalization) before starring tokens; optionally drop a bare `gid` token | Seeded DB: searchGifs("re: hello") should return the "hello" row; today returns empty |
| E-8 | P3 | missed | `INSERT OR IGNORE` means a rebuilt/overlapping pack never delivers the #149 gid: fix over legacy rows (no URL fallback), and the only mechanism that could — replaceExisting=true — is unreachable (sole call site hardcodes false; AlreadyInstalled dead-ends with a toast) | gif/GifDatabase.kt:358-364; ui/settings/io/SettingsGifHandlers.kt:80; gif/GifPackManager.kt:88-99 | On AlreadyInstalled, offer "Replace pack?" re-invoking with replaceExisting=true; for cross-pack overlap, UPDATE search_text when the incoming row carries gid: and the existing doesn't | Import pack A (row 5), then pack B (row 5 with gid:AbC); getGifById(5).getGiphyId() should be "AbC" — today null |
| E-9 | P3 | missed | importPack returns the pack's total row count, not "GIFs actually imported" (OR IGNORE skips are counted) — the "Imported: N GIFs" toast and the step-8b `imported > 0` guard run on an overcount | gif/GifDatabase.kt:322-323,388-391 (#importPack) | Return `SELECT changes()` after the gifs insert, or reword the contract/copy to "N GIFs in pack" (§Deferred) | Import the same pack.db twice under two pack_ids; second should report 0 (or honest copy) — today reports full N |
| E-10 | P3 | bug | GIF long-press popup's "URL copied"/"GIF copied" feedback uses Toast — which sibling paths in the SAME file convert to suggestion-bar feedback precisely because "Toasts are IME-suppressed on Android 13+" (#156 pattern) | KeyboardReceiver.kt:672,693 (#showGifPopup) vs :551-553 | Swap both Toasts for showSuggestionBarMessage | Source-scan drift test: no Toast.makeText in KeyboardReceiver's GIF section |
| E-11 | P3 | bug | Partial thumbnail import (disk full mid-copy) passes the ARC-038 gate — per-file failures are swallowed, rollback fires only on thumbCount==0, so a 10%-complete import returns Success with ~90% blank tiles and no user-visible signal | gif/GifPackManager.kt:117-135 (step 8/8b); gif/GifAssetManager.kt:154-174 (#importThumbnails) | Compare thumbCount against the pack's own webp count and roll back / warn on a large shortfall; or stop swallowing ENOSPC | Pure JVM: importThumbnails into a destination that becomes unwritable after k files; assert failure surfaces — today returns k and Success |
| C-6 | P3 | missed | SuggestionRanker (258 lines, own scoring formula) has zero production callers — the real merge is inline in WordPredictor.predictInternal with a different formula — yet two specs still present it as the live multi-dictionary merging layer | SuggestionRanker.kt:29; docs/specs/dictionary-and-language-system.md:35,212; docs/specs/secondary-language-integration.md:15,29 | Delete class + test, or fix the two specs to describe the real inline merge | n/a (deadness by reference count) |
| C-7 | P3 | stub | PersonalizationManager is a dead, LearningGate-free learning store still cited by the geometric spec — a privacy landmine if ever wired as the spec suggests (persists typed words with no gating, no incognito param) | PersonalizationManager.kt:16,30; docs/specs/geometric-swipe-engine.md:951 | Delete the class and correct the spec's reference to PersonalizationEngine | n/a — unreachable today; recorded because the spec invites wiring it |
| C-8 | P3 | stub | PersonalizedScorer is constructed on every WordPredictor init and never invoked — the real path queries personalizationEngine directly; 257 dead lines allocated per predictor | WordPredictor.kt:212,277; personalization/PersonalizedScorer.kt:30 | Delete the field + class (or actually route the personalization term through it; deletion is the honest default) | n/a (deadness by reference count) |
| C-9 | P3 | bug | `userWordOriginalCase` is never cleared on the full dictionary-load paths — case mappings from language A's custom words keep rewriting language B's predictions (en "LaTeX" restyles fr "latex") until a Dictionary Manager visit triggers the one clearing path | WordPredictor.kt:229, :931-1038 (no clear), :1064-1088 (no clear), :434-435 (only clear) | Clear userWordOriginalCase at the top of loadDictionary and in the async onLoadCustomWords before repopulating | Mock: load lang A with custom "LaTeX", load lang B containing dictionary "latex", assert applyUserWordCase("latex")=="latex" (currently "LaTeX") |
| D-4 | P3 | bug | Deleting the newest history entry clears the OS clipboard by comparing against history[0], not the actual primaryClip — deleting a #156 private entry (whose text never touched the OS clipboard) or an entry superseded by a skipped-capture clip wipes the user's real clipboard | clipboard/ClipboardHistoryService.kt:242-260 (#removeHistoryEntry) | Compare against the actual `_cm.primaryClip` text (best-effort) and never clear for isPrivate rows | MockK: top DB entry = private text P, cm.primaryClip = "other"; removeHistoryEntry(P); verify clearPrimaryClip never called (fails today) |
| D-5 | P3 | bug | Count-based pruning (the DEFAULT limit type) orphans media files — applySizeLimit DELETEs rows without collecting media_path (unlike the size-based twin), so pruned media files persist on disk until the next process start's cleanupOrphans (IMEs live for days) | clipboard/ClipboardDatabase.kt:1181-1205 (#applySizeLimit); ClipboardHistoryService.kt:378-384,825-830 | Make applySizeLimit SELECT the doomed rows' media_path first (like applySizeLimitBytes) and return them for reference-checked deletion | DB test: insert maxSize+1 entries, oldest = media; applySizeLimit must surface the media path (today returns Int only) |
| D-6 | P3 | bug | Every WebP media entry shows the "animated" play badge (the `mediaPath != null` conjunct is vacuous — always set for saved media); the real header-parsing isAnimated detector has zero callers | clipboard/ClipboardHistoryView.kt:1244-1246; ClipboardMediaManager.kt:184-194 (#isAnimated, unused) | Detect at save time via mediaManager.isAnimated and persist a flag (or resolve lazily with cache); at minimum drop the webp branch | Drive getView with a static-webp entry (ClipboardMediaDeleteAffordanceTest pattern) → assert play_badge GONE (fails today) |
| D-7 | P3 | bug | Tag-add and edit-save errors use IME-invisible Toasts (violating the project's own documented rule, which even claims these paths use inline state); on DuplicateConflict/InvalidContent save_edit also unconditionally cancelEdit()s — the user's edit is silently discarded with feedback they cannot see | clipboard/ClipboardTagDialog.kt:255,259; ClipboardHistoryView.kt:589-598 (#save_edit) | Route errors through an in-pane channel (inline label / pulse / suggestion-bar); on DuplicateConflict keep edit mode open instead of cancelEdit() | Unit-drive save_edit with DuplicateConflict → assert isEditing() remains true (fails today) |
| D-8 | P3 | missed | The `completedAt` timestamp the todo skill documents (with a DB-layer contract) exists nowhere — no column, no field, no write | .claude/skills/clipboard-todo-system.md:13,159 vs ClipboardDatabase.kt:860-879,1980-1996; TodoEntry.kt:23-37 | Add the column + write in a V6 migration (if anything will consume it) or delete the two skill-doc paragraphs (§Deferred) | n/a for code; a docs-drift check would flag the skill lines |
| D-9 | P3 | stub | ClipboardHistoryCheckBox is dead-but-present view code (AttributeSet-only constructor, no XML names it) — companion to the tracked ClipboardPinView | clipboard/ClipboardHistoryCheckBox.kt:8-21 | Delete alongside ClipboardPinView in the planned cleanup pass | n/a (deletion) |
| D-10 | P3 | bug | pin/todo/delete/paste row handlers index `paginatedHistory[pos]` unguarded — an async reload that shrinks the list between render and click dispatch throws IndexOutOfBoundsException and crashes the IME; edit_entry alone has the guard (evidence the hazard was known) | clipboard/ClipboardHistoryView.kt:486-487,516-517,537-538,844-845 vs :559-560 | `paginatedHistory.getOrNull(pos) ?: return` in the four unguarded handlers | Unit: shrink paginatedHistory to n, call delete_entry(n) → IOOBE today, clean return after fix |
| H-9 | P3 | missed | The new media delete affordance (d3cd8dc6) — and every clipboardEntryButton in the row — is a bare View with no contentDescription: TalkBack focuses it and announces nothing, so the just-added media delete path does not exist for screen-reader users | res/layout/clipboard_history_entry.xml:226-240; clipboard/ClipboardHistoryView.kt:1126,1342 | Add contentDescription string resources to the entry action buttons (at minimum delete); convert bare Views to ImageButton where sensible | Instrumented: expand a media row, assert clipboard_entry_delete.contentDescription non-empty (null today) |
| F-7 | P3 | missed | Five dead `cgr_*` keys (neural-era) are seeded into every settings export and round-trip back into prefs — SETTINGS_DEFAULTS's comment about them is false; the ARC-051/085 dead-key-looks-alive class | backup/SettingsDefaults.kt:177-181; BackupRestoreManager.kt:800-810,855-871 | Move the five cgr_* keys (and clipboard_pinned_rows when ClipboardPinView dies) to SettingsValidation.DEPRECATED_KEYS | Extend SettingsDefaultsDriftTest with the reverse assertion: every SETTINGS_DEFAULTS key needs a read site outside backup/ + settings surface (cgr_* fail) |
| F-8 | P3 | missed | Three prefs gate real behavior (`clipboard_media_enabled`, `clipboard_max_media_size_mb`, `show_exact_typed_word` #42) but no settings control writes them — permanently at defaults for everyone who doesn't hand-edit a backup file | Config.kt:799-800,817; clipboard/ClipboardHistoryService.kt:732-734; SuggestionHandler.kt:2345 | Add the two media controls to ClipboardSection + a "Show exact typed word" switch to prediction settings — or reclassify as internal with a comment (§Deferred) | Same reverse-drift test as F-6 — these three fail today |
| F-9 | P3 | stub | AutoCorrectionSettingsActivity is unreachable (its only launcher has zero callers) and its slider ranges drifted from the live section AND the validator (2..10 vs 2..5; 0.5..1.0 vs 0.5..0.9) — re-wiring it would instantly reproduce the F-4 class | activities/AutoCorrectionSettingsActivity.kt:260,274; ui/settings/SettingsNavigation.kt:55-56; AndroidManifest.xml:98 | Delete the activity + manifest entry + dead navigation helper (the inline AutoCorrectionSection is the live surface) | Source-scan test: every `fun open*` in SettingsNavigation must have a caller |
| F-10 | P3 | bug | `clipboard_max_item_size_kb` floors disagree (UI/state coerces 64..1024, Config coerces 1..1024) — an imported sub-64 value displays as "64KB" while the service enforces the raw value; the write-back guard fires only for oversize, so the lie persists indefinitely | ui/settings/SettingsPersistence.kt:247-251 vs Config.kt:783 | One shared clamp constant on both sides (floor 64, matching the slider) and a bidirectional write-back (`if (coerced != raw)`) | In-memory prefs with "32": run both read paths, assert they agree (fails: 64 vs 32) |
| G-5 | P3 | bug | `clipboard_history_limit`'s deliberate 0..500 bound never runs for the CANONICAL string form (the key was removed from isIntKey, validateString falls to `else -> true`), and the Config read site has no clamp — "9999"/"abc"/"-5" import cleanly and apply | backup/SettingsValidation.kt:328,393-433,458 vs Config.kt:779; ClipboardHistoryService.kt:229,380,826 | In validateString, for numeric-string keys ∩ INT_RANGES, parse toIntOrNull() and apply the range (reject non-numeric); or clamp at the Config read site like clipboard_max_item_size_kb | fromJson with `"clipboard_history_limit":"9999"` must land in parseSkippedKeys (today: lands in changes and applies) |
| G-2 | P3 | bug | Short-swipe preview counts a "legacy flat" section the applier's Gson model cannot parse (imports 0) — and in REPLACE mode the clear has already run, so a preview promising N mappings wipes existing customizations for a 0-mapping import | backup/SettingsImportPlanBuilder.kt:141-150 vs customization/ShortSwipeCustomizationManager.kt:273-295 (#importFromJson); ShortSwipeMapping.kt:225-227 | Drop flat-shape support from the counter (or normalize flat→wrapped before the importer) AND make importFromJson return-0-without-clearing when the parsed list is empty in REPLACE mode | Flat-section backup through fromJson (size ≥1) then apply with REPLACE over one existing mapping: imported==0 and the mapping is gone (both wrong today) |
| G-3 | P3 | missed | MERGE mode's label/contract/plan all promise "fill gaps, preserve existing" but the implementation is file-wins on collision (unconditional put) — under the RECOMMENDED default mode | customization/ShortSwipeCustomizationManager.kt:280-288 vs backup/ShortSwipeImportMode.kt:6-7, res/values/strings.xml:1230 | Make merge additive (`if (key !in mappingCache)`) to match every written promise, or reword the label/doc to "file entries win on conflict" (§Deferred) | Manager with a:NE→"@"; merge-import a:NE→"%"; assert the survivor — today "%", shipped copy says "@" |
| G-4 | P3 | bug | SettingsImportApplier counts every ADDED-with-known-default row as "drifted" (compares the builder's effective-default `current` against the still-absent key) — driftCount and the diagnostic log are wrong for every fresh-install import | backup/SettingsImportApplier.kt:48-49,92-103 vs SettingsImportPlanBuilder.kt:127-134 | Carry `type` into the drift check: for ADDED, `now == null` (or equal-to-default) means no drift | Plan with ADDED change + empty prefs → assert driftCount == 0 (fails: 1) |
| G-6 | P3 | bug | Langpack reimport deletes the installed pack BEFORE copying the replacement — a mid-copy IO failure (disk full) loses the working pack and leaves a manifest-only ghost that getInstalledPacks() lists but every dictionary consumer treats as absent | langpack/LanguagePackManager.kt:124-152 (#importFromStream); :222-228 | Copy validated files into a sibling staging dir and swap with renameTo (delete old only after staging completes); or at minimum copy dictionary.bin before manifest.json | Force the dictionary copy to fail mid-reimport of v2 over v1; assert v1 still works OR pack fully absent — today: manifest-only limbo, v1 gone |
| I-3 | P3 | missed | Swipe-ML export cannot round-trip: the only importer reads a `"swipes"`/flat schema no exporter writes (exporters write `"data"` + nested metadata) — and the importer has zero callers anyway | ml/SwipeMLDataStore.kt:595-660 (#importFromJSON) vs :371,:439; SwipeMLData.kt:250-262 | Delete importFromJSON, or rewrite it against the actual export schema and wire an SAF import handler | Pure JVM: feed a real export to importFromJSON — throws JSONException on getJSONArray("swipes") |
| I-4 | P3 | bug | `first_stat_timestamp` is never written — "Days tracked" is permanently 0 in the perf-stats viewer and every JSON export, while selections keep advancing (not on the KDoc's deliberately-dead list) | SwipePerformanceStats.kt:56,109,152-160 (#getDaysSinceStart); export read SettingsPrivacyDataHandlers.kt:120 | In recordSelection, seed KEY_FIRST_STAT_TIME when 0 | Robolectric/instrumented: fresh prefs → recordSelection(0) → assert getFirstStatTimestamp() > 0 (fails today) |
| I-5 | P3 | bug | Playground export (the primary data-donation path) uses the whole-DB-in-memory exportToJSON() — materializing the table three times over — while the streaming overload exists specifically "to avoid OOM with large datasets" on the documented 256MB-limit device | activities/SwipeDebugActivity.kt:366; ml/SwipeMLDataStore.kt:345-395 vs :429 | Add/use a streaming-to-File export (cursor → FileWriter, same loop as the OutputStream overload) in exportRecordedTraces | Drift test: exportRecordedTraces must call the streaming variant (OOM itself is not unit-testable) |
| I-6 | P3 | bug | user_selection rows get a first t_delta_ms skewed −1000ms vs playground rows of the SAME swipe (MLDataCollector's manual rebuild anchors `runningTimestamp = now − 1000` against a fresh `lastAbsoluteTimestamp = now`; copyWith copies deltas verbatim) — inconsistent first-point timing across the two row types in any exported corpus | MLDataCollector.kt:95-104 vs SwipeMLData.kt:118,205,409-422 | Replace the manual denormalize/renormalize loop with `currentSwipeData.copyWith(cleanWord, "user_selection")` — the method exists for exactly this case | Pure JVM: known-delta capture through both paths, compare first t_delta_ms (differs by 1000 today) |
| I-7 | P3 | missed | "Once per session" default-IME prompt is once per INSTALL — the flag is a persistent pref, resetSessionPrompt has zero callers, and three in-repo artifacts (key name, KDoc, backup comment) document the opposite contract; the toast also names "Unexpected Keyboard" in an app named CleverKeys | IMEStatusHelper.kt:37-41,101,142; backup/SettingsValidation.kt:40 | Clear the flag in CleverKeysService.onCreate before checking (session = service lifetime) and fix the toast string to the app-name resource (session semantics → §Deferred; the string is a straight fix) | Robolectric: flag=true, simulate new session entry, assert flag cleared / prompt eligible |
| I-8 | P3 | bug | The SET_DEBUG_MODE dynamic receiver is spoofable by any installed app on API 24-25 (the comment's "not reachable by other apps pre-26" claim is false for DYNAMIC receivers) — a hostile broadcast silently switches on the I-1 recording path with every consent toggle off | DebugLoggingManager.kt:122-131 (#registerDebugModeReceiver), :113-119 | Require a sender check (signature-protected permission on registerReceiver, or a shared-secret extra), or restrict the pre-26 path to debug builds | Instrumented on API-25: broadcast from a second test app, assert isDebugMode() stayed false (fails today) |
| H-6 | P3 | bug | Key border paint sets `alpha = keyOpacity` BEFORE `setColor(color)` — setColor overwrites the full ARGB, so borders render opaque even on a translucent keyboard (the sibling bg_paint does it in the correct order) | Theme.kt:288-295 (#init_border_paint) vs :247,:257 | Swap the two lines (setColor before alpha) | Instrumented one-liner: Theme.Computed with keyOpacity=100, assert border_paint.alpha == 100 (255 today) |
| H-7 | P3 | bug | The five custom text-action short swipes (primaryLangToggle/secondaryLangToggle/textAssist/replaceText/showTextMenu) `return` before the success haptic at the end of onCustomShortSwipe — silent success while every other successful custom swipe vibrates | Keyboard2View.kt:764-775,:857 (#onCustomShortSwipe) | Replace the early return with a flag (or move the haptic before the fallback dispatch) | View-level seam counting performHapticFeedback per actionValue class; red for "primaryLangToggle" |
| H-8 | P3 | bug | The #77 loc-strip leaves the default numeric pane's `switch_greekmath` cell as an all-null Key that onDraw still frames — default installs show a blank dead key-colored rect where the toggle used to be (no shipped layout has an intentionally key0-less cell) | LayoutModifier.kt:161-167 (#modify_numpad strip); Keyboard2View.kt:1547-1555 (#onDraw); src/main/layouts/numeric.xml:24 | Drop keys whose 9 slots are all null and fold their width into the preceding key's shift — or guard drawKeyFrame on `key.keys.any { it != null }` | JVM: modify_numpad with greekmath unchecked → assert no Key remains with all-null slots (fails today) |

---

## Fix-wave partition

Seven waves with disjoint file fences for parallel execution. Every item is assigned;
cross-wave file contacts are flagged explicitly at the end of this section.

### W1 — swipe-gate + gesture dead code (S)
The highest-user-impact single fix, deliberately isolated so no other wave touches
gesture files.
- **Items**: B-1 (P1), B-2 (P3).
- **Fence**: `gesture/ImprovedSwipeGestureRecognizer.kt`, `gesture/ProbabilisticKeyDetector.kt`,
  `gesture/SwipeInput.kt` (delete), `gesture/SwipePruner.kt` (delete), `proguard-rules.pro`
  (one keep rule), `build.gradle` (SwipePrunerTest exclusion lines only).

### W2 — IME core + panes/routing (L; 17 items, largest wave)
IME-core items merged with the pane wave because A-2's fix reaches into
KeyboardReceiver's constructor seam and A-4/A-5 live in KeyEventHandler, which the
routing fixes also cite — one owner avoids the crossing entirely.
- **Items**: A-1 (P2), A-2 (P1), A-3/E-2 (P2), A-4 (P3), A-5 (P2), E-1 (P1), E-3 (P2),
  E-4 (P2), E-5 (P2), E-6 (P2), E-7 (P3), E-8 (P3), E-9 (P3), E-10 (P3), E-11 (P3),
  H-3 (P2).
- **Fence**: `CleverKeysService.kt`, `KeyEventHandler.kt`, `KeyboardReceiver.kt`,
  `wiring/KeyEventReceiverBridge.kt`, `wiring/KeyboardComponentGraph.kt`,
  `wiring/LayoutBridge.kt`, `InputCoordinator.kt`, `ConfigPropagator.kt`,
  `emoji/EmojiSearchManager.kt`, `gif/*` (GifDatabase, GifGridView, GifGroupButtonsBar,
  GifPackManager, GifAssetManager), `ui/settings/io/SettingsGifHandlers.kt`,
  `res/layout/emoji_pane.xml`, `res/layout/gif_pane.xml`.

### W3 — dictionary/prediction (L)
- **Items**: C-1 (P2), C-2 (P2), C-3 (P2), C-4 (P1), C-5 (P2), C-6 (P3), C-7 (P3),
  C-8 (P3), C-9 (P3).
- **Fence**: `WordPredictor.kt`, `DictionaryManager.kt`, `UserDictionaryObserver.kt`,
  `SuggestionRanker.kt` (delete), `PersonalizationManager.kt` (delete),
  `personalization/PersonalizedScorer.kt` (delete), `activities/DictionaryManagerActivity.kt`,
  spec files `docs/specs/dictionary-and-language-system.md`,
  `docs/specs/secondary-language-integration.md`, `docs/specs/geometric-swipe-engine.md`
  (reference corrections only).

### W4 — clipboard/sanitize (M/L)
- **Items**: D-1 (P2), D-2 (P2), D-3 (P2), D-4 (P3), D-5 (P3), D-6 (P3), D-7 (P3),
  D-8 (P3), D-9 (P3), D-10 (P3), H-9 (P3).
- **Fence**: `clipboard/*` (ClipboardHistoryService, ClipboardDatabase,
  ClipboardHistoryView, ClipboardTagDialog, ClipboardMediaManager,
  ClipboardHistoryCheckBox delete, sanitize/UrlSanitizer), `AndroidManifest.xml`
  (permission line, if the D-1 permission route is chosen),
  `res/layout/clipboard_history_entry.xml`, `.claude/skills/clipboard-todo-system.md`
  (D-8 doc side).

### W5 — settings/config/validation (L)
Takes G-5 (moved from the backup wave) because its fix lands in
`backup/SettingsValidation.kt`, which F-4 also edits — same file, same range-mismatch
class.
- **Items**: F-1 (P1), F-2 (P2), F-3 (P2), F-4 (P2), F-5 (P2), F-6 (P2), F-7 (P3),
  F-8 (P3), F-9 (P3), F-10 (P3), G-5 (P3).
- **Fence**: `ui/settings/SettingsResetPresets.kt`, `ui/settings/SettingsPersistence.kt`,
  `ui/settings/SettingsNavigation.kt`, `ui/settings/sections/*` (InputBehavior,
  SwipeTrail, Clipboard, Appearance as needed), `backup/SettingsValidation.kt`,
  `backup/SettingsDefaults.kt`, `Config.kt`,
  `activities/AutoCorrectionSettingsActivity.kt` (delete), manifest entry for it.

### W6 — backup/langpack + ml/privacy/debug (M/L)
- **Items**: G-1 (P1), G-2 (P3), G-3 (P3), G-4 (P3), G-6 (P3), I-1 (P1), I-2 (P2),
  I-3 (P3), I-4 (P3), I-5 (P3), I-6 (P3), I-7 (P3), I-8 (P3).
- **Fence**: `langpack/LanguagePackManager.kt`, `backup/SettingsImportPlanBuilder.kt`,
  `backup/SettingsImportApplier.kt`, `customization/ShortSwipeCustomizationManager.kt`,
  `backup/ShortSwipeImportMode.kt` + `res/values/strings.xml` merge-label (G-3, if reword
  chosen), `ml/*` (SwipeMLDataStore, SwipeMLData, PlaygroundTraceRecorder),
  `MLDataCollector.kt`, `PrivacyManager.kt`, `activities/SwipeDebugActivity.kt`,
  `DebugLoggingManager.kt`, `IMEStatusHelper.kt`, `SwipePerformanceStats.kt`.
  Prefer implementing I-1's package-check inside `ml/PlaygroundTraceRecorder` (not
  SuggestionHandler) to keep the fence clean.

### W7 — theme/render/layout (M)
- **Items**: H-1 (P2), H-2 (P1), H-4 (P2), H-5 (P2), H-6 (P3), H-7 (P3), H-8 (P3).
- **Fence**: `Keyboard2View.kt`, `Theme.kt`, `theme/*` (ThemeProvider,
  KeyboardColorScheme), `activities/ThemeSettingsActivity.kt`, `LayoutModifier.kt`.

### Flagged cross-wave file contacts
- **`backup/` is split**: W5 owns SettingsValidation + SettingsDefaults; W6 owns
  SettingsImportPlanBuilder + SettingsImportApplier + ShortSwipeImportMode. Disjoint at
  file level, but both waves touch the backup test suites — coordinate test-file edits.
- **`ui/settings/sections/ClipboardSection.kt`** is fenced to W5, but two W4 items want
  UI affordances there (D-1's grant-usage-access affordance, and F-8's media controls are
  already W5). Resolution: W4 does the manifest/service half of D-1; the ClipboardSection
  affordance for D-1 rides W5 alongside F-8. If D-1 resolves via the "reword the setting"
  route instead, the string edit also lands in W5's fence.
- **`res/values/strings.xml`** may be touched by W4 (D-7 in-pane strings), W5 (F
  controls), W6 (G-3 label, I-7 app name), W7 (H-9 was moved to W4). Strings are
  append-mostly; merge conflicts are trivial, but sequence commits or use distinct string
  blocks.
- **`AndroidManifest.xml`**: W4 (D-1 permission) and W5 (F-9 activity removal) both touch
  it — different lines, still coordinate.
- **`SuggestionHandler.kt`** is cited by C-5 and I-1 but neither fix needs to edit it if
  C-5 is fixed inside WordPredictor and I-1 inside PlaygroundTraceRecorder + the activity
  — keep it that way.

---

## Deferred / behavior-choice candidates (maintainer decisions)

These items each contain a fork the maintainer should choose before implementation; the
mechanical halves (noted per item) are not deferred.

- **D-1 (PM exclusion)** — declaring `PACKAGE_USAGE_STATS` has real costs (F-Droid
  anti-feature optics, privacy posture of a keyboard requesting usage access). The honest
  alternative — drop the package-list mechanism, document IS_SENSITIVE as the mechanism,
  and reword the setting so it stops promising exclusion it can't deliver — changes
  advertised behavior. Maintainer picks; either way the current false promise must go.
- **D-3 (todo default filter)** — active-only-by-default may be the better UX even though
  the skill doc promises show-everything. Maintainer picks the default; the
  applyFilter/hasActiveFilters tint disagreement is a straight bug and gets fixed under
  either choice, as does the skill-doc alignment.
- **G-3 (merge semantics)** — additive merge (matching every written promise) vs
  file-wins (matching the diff card). Both are one-line outcomes; the choice is a
  user-contract decision.
- **I-7 (prompt session semantics)** — "once ever" may be deliberate anti-annoyance
  design contradicted by its own naming; maintainer confirms intent. The "Unexpected
  Keyboard" toast string is a straight fix regardless.
- **D-8 (completedAt)** — schema addition (V6 migration for a field nothing consumes yet)
  vs deleting two doc paragraphs. Doc-delete is the default unless a consumer is planned.
- **F-8 (unwritable live prefs)** — surface the three controls vs reclassify as internal.
  Product-surface decision.
- **F-6 (Pin Entry Layout switch)** — wire it to `number_entry_layout` vs delete the
  control. Product decision; the switch doing nothing is not deferrable.
- **H-4 (theme-creator dead fields)** — wiring nine fields through Theme is real design
  work (which surfaces should honor suggestion*/ripple/keySpecial?); removing the dead
  editor rows is the cheap honest alternative. The stale-swipeTrail-on-active-edit
  sub-bug is a straight fix either way.
- **E-9 (import count)** — `changes()`-based honest count vs rewording the toast to "N
  GIFs in pack". Trivial either way; copy decision.

---

## Cross-cutting observations — recurring bug classes and their ratchets

Each line: the class, its instances in this audit, and the drift-test/ratchet shape that
would prevent recurrence permanently. These are candidate meta-fixes to implement inside
the owning waves.

1. **Bridge-delegation gap** (A-1 — the documented 3rd instance): a new IReceiver method
   gets a KeyboardReceiver implementation but no bridge override. *Ratchet*: reflection
   drift test asserting `KeyEventReceiverBridge` overrides every `IReceiver` member —
   makes a 4th instance impossible. (W2)
2. **Stomp / lost-update over a shared store** (C-4 membership rewrite from stale RAM;
   F-1 `editor.clear()` + partial reseed; G-2 REPLACE-clears-before-parse-validated):
   writers that rewrite whole stores keyed on stale in-memory state destroy other
   writers' data. *Ratchet*: every whole-store rewrite must merge against a fresh read
   (or be scoped to owned keys); pure tests that interleave a foreign write before each
   rewrite path. (W3/W5/W6)
3. **Scale/offset mismatch between producer and consumer** (C-2 raw-1..255 rows in a
   calibrated map; I-6 −1000ms first-delta skew): parallel write paths that skip the
   canonical conversion helper. *Ratchet*: one calibration/copy helper (scaleOnto /
   copyWith) and a drift test enumerating every writer into the store, asserting each
   routes through it. (W3/W6)
4. **Toast-in-IME-context** (D-7, E-10; A-1's dropped feedback is the same rule's
   motivation; E-1's crash rides an adjacent unhandled-launch): the project's own skills
   state Toasts are invisible from the IME. *Ratchet*: source-scan drift test banning
   `Toast.makeText` in IME-context files (KeyboardReceiver, clipboard pane views, dialogs
   hosted in the pane) with an explicit whitelist. (W2/W4)
5. **Range mismatch across UI / validator / runtime clamp** (F-4 three keys, F-9 dead
   activity drifted, F-10 floor disagreement, G-5 string-form bypass; the tracked margin
   90-vs-45 was the exemplar): three places restate each key's range and drift
   independently. *Ratchet*: shared per-key range constants (GeoKnobRanges pattern)
   consumed by slider, validator, and Config clamp, plus a drift test comparing slider
   `valueRange` literals against SettingsValidation. (W5)
6. **Stale routing/lifecycle flag detached from the thing it describes** (A-3/E-2 pane
   flags survive eviction; E-4 `currentCategory` survives a fallback load; I-1 debugMode
   survives backgrounding): a boolean stands in for "this view/session is live" without
   being tied to its lifecycle. *Ratchet*: single `closeCurrentPaneRoutingState()` called
   by every opener + test that opening any pane clears all others' flags; lifecycle flags
   bound to onStart/onStop, never onCreate/onDestroy. (W2/W6)
7. **`editor.clear()` on a shared prefs file** (F-1/F-2): the settings file also holds
   dictionaries, layouts, and migration markers; clear-then-reseed destroys everything
   unlisted. *Ratchet*: source-scan test banning `editor.clear()`/`.clear()` on
   DirectBootAwarePreferences-backed editors outside a whitelisted migration. (W5)
8. **Constructor-captured identity vs live identity** (A-2 receiver holds the dead view;
   H-5 cache keyed by nullable name instead of instance): captured references/keys
   outlive the object they identify. *Ratchet*: late-bound providers for the view seam;
   cache keys must include object identity; mock-tier test that swaps the view and
   asserts calls land on the new one. (W2/W7)
9. **Preview/apply divergence** (G-2 counts a shape apply can't parse; G-4 drift
   miscount; E-9 import overcount): the number shown is computed by different code than
   the action. *Ratchet*: preview counts derived from the same parse the applier uses;
   tests asserting preview N == applied N for every supported input shape. (W6/W2)
10. **Dead code that looks alive** (B-2 + stale keep rule, C-6/C-7/C-8, D-9, D-6's unused
    detector, F-7 exported dead keys, F-9 unreachable activity, I-3 caller-less importer;
    C-7 is additionally a privacy landmine a spec invites wiring): specs, keep rules, and
    defaults keep advertising deleted subsystems. *Ratchet*: reverse drift tests — every
    SETTINGS_DEFAULTS key needs a reader, every SettingsNavigation `open*` needs a
    caller, every ProGuard keep needs a production reference. (W1/W3/W4/W5)

---

## Coverage consolidation — what this audit did NOT examine

Merged from the nine auditors' Coverage sections; these are the honest holes.

**Global limits**
- **Nothing was executed on-device.** All findings are reasoned from source (one executed
  regex simulation for D-2). Specifically unexecuted: E-1's SQLite-version premise (from
  the platform version table — the strongest candidate for an emulator confirm), F-3's
  float→int saturation chain (JVM-spec semantics), B-1 (recognizer needs
  android.graphics.PointF). Instrumented tests were read for refutation, never run.
- **Speculative races were excluded per the brief**: EmojiKeywordIndex non-volatile
  publication, a <50ms Autocapitalisation resync window, clipboard check-then-insert
  dedup across Main/IO, LIMIT/OFFSET-without-ORDER-BY nondeterminism.

**Per-area holes**
- **Swipe decode math**: PathScorer/TemplateIndex/LayoutGeometry/TemplateGenerator were
  structure-skimmed only (accuracy-harness-pinned pure math); no pure replay
  (CtcReplayEngine) was executed. No script language has ever had an on-device real-swipe
  check (consistent with B-1 never having been observed).
- **Pointers gesture state machine**: A read all 2034 lines at the routing level; H
  deliberately skipped its internals. The macro-embedded-Slider double-fire was noted as
  inherited upstream structure, not filed.
- **SuggestionBar view binding** internals, **TrigramStore** (mirrored from BigramStore,
  surface-checked only), **NextWordPredictor** beyond gates/tiering.
- **BackupRestoreManager's clipboard-import and full-backup ZIP paths** (tracked
  ARC-033/034/036/091/094 territory) and the **BackupRestoreActivity headless intent
  surface** (ARC-031/032/033) were not re-audited; crypto (CKENC1) was verified clean.
- **tools/gif_pipeline** (worktree) — the gid: writer side was taken on the ledger's
  word. **docs/specs/gif-panel-spec.md** FTS5 staleness already canonized, not
  re-recorded.
- **Emoji static name/when tables** (~900 lines) skimmed as data; emoji skin-tone support
  absent but promised nowhere.
- **ui/settings presentation internals** (SettingsDialogs/InfoCards/Controls),
  ShortSwipeCustomizationActivity + customization/ per-key UI internals,
  ThemeSettingsActivity beyond pref writes and the delete path, LauncherActivity beyond
  pref writes.
- **Root files swept only at TODO/stub-marker level** by I: DirectBootManager,
  FoldStateTracker, Logs, plus KeyboardReceiver outside the pane/routing sections that A
  and E line-audited.
- **Wiki/site docs** and migration-history docs were consulted only for dedupe.
- **Verified-clean areas** (checked, no findings — recorded so they aren't re-audited
  blind): CTC pack-verdict/trie cache invalidation, dual-language secondary filter,
  delivery generation guards, occlusion math; private-copy security invariants at HEAD
  (ARC-001 fixed); backup crypto; autofill; DebouncedPersister; SubtypeManager #160
  residuals; UserDictionaryObserver register/unregister balance; the #167 inset ladder;
  snapshot-mirror stomp hunt (the #161/#154 class is closed at HEAD).
- **Noted for the maintainer, below the finding bar**: ModelLoader writes a `.ort`
  optimized-model file every load and never reads it back ("faster subsequent loads"
  doesn't happen — correctness unaffected); ime-key-routing.md says the backspace
  priority chain lives in `key_down()` but it is implemented in `key_up()` (contract
  honored, prose stale); stale "minSdk is 21" comments post-ARC-113.

— Fable 5
