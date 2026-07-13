# Post-Decomposition Review — Fixes / Improvements / Remaining (2026-07-13)

Consolidated from a 3-agent read-only review of the July 2026 work: SettingsActivity
decomposition (6,806→686 lines, 33 files), dictionary rebuild (52k→98,140), and the
autocorrect changes. Each item: area, severity, evidence (file:line), fix, and the test
that catches it. **Discipline: every real bug gets a failing test first.**

Status legend: ☐ todo · ◐ in progress · ☑ done

---

## P0 — Real user-facing bugs

### ☐ AC-1 [HIGH] Autocorrect min-frequency default & slider range are inconsistent
- **Evidence**: `AutoCorrectionSettingsActivity.kt:{37,53,101,110,305,308}` hardcode `500`;
  `Config.Defaults.AUTOCORRECT_MIN_FREQUENCY = 100` (Config.kt:184). The live main-UI slider
  `AutoCorrectionSection.kt:86` is `valueRange = 100f..5000f` but `FrequencyFloor.SLIDER_MAX = 2000`
  → values 2001–5000 all clamp to the same floor (user sees 3500, behaves as 2000).
  `SettingsValidation.kt:214` accepts 100..5000.
- **Consequence**: first open shows 500 (真default 100); Reset writes 500. At slider 500,
  `FrequencyFloor.effective(500,1_000_000)=126,315` → excludes ~8.9% of dict + all custom words
  + ~49 alias keys as correction targets. The setting silently over-restricts.
- **Fix**: replace the 5 `500`s in AutoCorrectionSettingsActivity with `Defaults.AUTOCORRECT_MIN_FREQUENCY`;
  change AutoCorrectionSection slider to `100f..2000f` (align to SLIDER_MAX); tighten SettingsValidation
  range to 100..2000.
- **Test**: pure — assert `Defaults.AUTOCORRECT_MIN_FREQUENCY == FrequencyFloor.SLIDER_MIN` and the
  AutoCorrectionSection max == `FrequencyFloor.SLIDER_MAX` (source-scan drift test); instrumented —
  after Reset, `prefs.getInt(...) == 100`.

### ☐ DICT-1 [MED→HIGH latent] gradle task can silently downgrade en bin V2(CKDT)→V1(DICT)
- **Evidence**: `build.gradle generateBinaryDictionaries` regenerates `<lang>_enhanced.bin` from json
  via `generate_binary_dict.py` (writes V1 `DICT`, no accent map) when `json.lastModified() > bin.lastModified()`.
  Shipped `en_enhanced.bin` is V2 `CKDT`. en is the ONLY lang with a json in assets. A stray json
  mtime bump (formatter, partial checkout/stash, CI) rewrites the bin as V1 → loses accent-folding.
- **Fix**: make the task refuse to overwrite a `CKDT` bin with a `DICT` one (read first 4 bytes of the
  existing bin; skip if magic==CKDT), OR exclude en from the task (en's bin comes only from
  `build_en_wordlist.py --write`). Prefer the magic-guard (defensive for all langs).
- **Test**: pure/CI — assert `en_enhanced.bin[0:4] == "CKDT"` (a bin-format guard test).

### ☐ DEC-1 [MED] `gif_enabled` search gate incomplete (3 sites)
- **Evidence**: `SettingsSearch.kt isGateEnabled()` (~121) has no `"gif_enabled"` case → `else→true`
  → gate never fires; `executeSearchAction()` (~138) has no `"gif_enabled"` expand redirect;
  `GifPanelSection.kt:44` "Enable GIF Panel" switch has no `highlightId="gif_enabled"`.
- **Consequence**: search "gif import" while GIF disabled → expands GIF section, scrolls to unrendered
  `gif_import` → silent no-op; no redirect to the enable toggle.
- **Fix**: add `"gif_enabled" -> gifEnabled` to isGateEnabled; `"gif_enabled" -> gifSectionExpanded=true`
  to the gate branch; `highlightId="gif_enabled"` on the enable switch.
- **Test**: extend SettingsSearchCoverageTest — assert every `gatedBy` value in `searchableSettings`
  has a branch in `isGateEnabled()` (source scan). Fails today.

---

## P1 — Medium (correctness / perf / coverage)

### ☐ AC-2 [MED] Custom/user words blocked as correction targets by any nonzero floor
- **Evidence**: custom/user words injected at freq `1000` (WordPredictor.kt ~1310/1441 `optInt(...,1000)`);
  bin min freq ≈52,300. Any slider >~103 → floor >> 1000 → all custom words excluded as targets.
- **Fix**: exempt `customAndUserWords` from the `frequencyFloor` gate (mirror the `isWordDisabled`
  custom-word override), OR inject custom words at a floor-surviving frequency. Prefer the exemption
  (semantically: user-added words are always valid targets).
- **Test**: instrumented — add custom word "zzqword", set slider high, assert a typo of it still corrects to it.

### ☐ PERF-1 [HIGH] weightedEditDistance: no early-abandon → DP runs full on ~49k unrelated words
- **Evidence**: `KeyAdjacency.weightedEditDistance` runs the full n×m DP for every lengthDiff-1..2
  candidate (prefix_length default 0 → no prefilter). L=8 typed ⇒ ~3M float ops/correction.
- **Fix**: after each row, `if (curr.min() > maxEd) break` — but `maxEd` lives in the caller; pass a
  `maxDistance` budget param (default +∞ to preserve existing callers/tests) and early-abandon. Most
  unrelated words exit after ≤2 rows (~70-80% op reduction).
- **Test**: KeyAdjacencyTest — assert `weightedEditDistance(a,b,budget)` equals the unbounded value when
  ≤budget, and returns >budget (or a sentinel) when exceeding; existing distance tests unchanged.

### ☐ PERF-2 [MED] weightedEditDistance: `prev = curr.copyOf()` allocates a FloatArray per row
- **Evidence**: KeyAdjacency.kt:217 (~394k allocs / L=8 correction). `curr` is `val`.
- **Fix**: make `curr` a `var`, swap rows (`val t=prev;prev=curr;curr=t`) — zero allocation. Combine with PERF-1.
- **Test**: covered by existing KeyAdjacencyTest edit-distance cases (behavior identical).

### ☐ DEC-2 [LOW-MED] `collapseAllSections()` omits `testKeyboardExpanded`
- **Evidence**: SettingsSearch.kt ~32-50 resets all top-level expand vars except `testKeyboardExpanded`
  (a top-level CollapsibleSettingsSection). Clicking a search result leaves Test Keyboard open too.
- **Fix**: add `testKeyboardExpanded = false`.
- **Test**: SettingsSearchTest — after executeSearchAction, exactly one section expanded.

### ☐ DEC-3 [STREAMLINE] Remove 9 dead state vars
- **Evidence**: `showSearchResults` (never read; SettingsScreen uses a local), `currentThemeName`,
  `vibrateCustomEnabled`, `numberEntryLayout`, `neuralBeamAlpha/PruneConfidence/ScoreGap`,
  `neuralResamplingMode`, `privacyCollectErrors` — loaded in loadCurrentSettings/handlePreferenceChanged
  but read by no composable (sliders moved to NeuralSettingsActivity; vibrateCustom hardcoded true).
- **Fix**: delete the declarations + their loadCurrentSettings/handlePreferenceChanged lines. Prefs still
  read directly by Config — no behavior change.
- **Test**: SettingsDefaultsDriftTest extension detecting loaded-but-unread vars (optional); compile+suite green.

### ☐ TEST-1 [MISSING] Autocorrect coverage gaps
- Case-preserved transposition winner (`"Teh"→"The"`, `"TEH"→"THE"`).
- Custom-word-as-target under nonzero floor (pairs with AC-2).
- Morphology-guard vs transposition interplay (`"gamees"`).
- Capitalized alias winner (`"Hadnr"→"Hadn't"`).
- Add to AutocorrectTest (instrumented) + a pure FrequencyFloor/default consistency test.

---

## P2 — Low / polish / docs

### Dictionary
- ☐ DICT-2 [LOW] `DictionaryDataSource.kt:137` + `OptimizedVocabulary.kt:985` `^[a-z]+$` filter hides
  207 accented words (café…) from Dictionary Manager in the JSON-fallback path (latent). Drop filter in fallback.
- ☐ DICT-3 [LOW latent] `OptimizedVocabulary.kt:1002` `(raw-128)/127f` → value 128 ⇒ freq 0.0 (excluded).
  Current min is 134; clamp build output ≥129 or floor freq at 0.001f in the loader.
- ☐ DICT-4 [STREAMLINE] Unify blocklists: `generate_binary_dict.ENGLISH_JUNK_BLOCKLIST` (18) should read
  `en_blocklist.txt` (54) when present (no leakage today — all 54 absent from json — but format-downgrade risk).
- ☐ DICT-5 [LOW fragile] `detect_misspellings.py:286` doesn't skip `#` header lines of en_words.txt
  (harmless today; a single-token comment would be analyzed). Skip `#` like build_dictionary.py:96.
- ☐ DICT-6 [MISSING] `en.txt` calibration list contains `alot` (blocklisted, not in 98k → unpredictable
  prompt); review the 105 apostrophe entries. Remove `alot`.

### Decomposition
- ☐ DEC-4 [STREAMLINE] `clearAllPrivacyData()` (SettingsPrivacyDataHandlers.kt:10) is dead (PrivacySection
  calls deleteCollectedData). Wire to a "Clear All" button or delete.
- ☐ DEC-5 [POLISH] `SettingsNavigation.kt:68` FQ `android.net.Uri.parse` (import already present).
- ☐ DEC-6 [POLISH] `SettingsResetPresets.kt:181` `fallbackEncrypted()` misfiled (lifecycle, not presets).
- ☐ DEC-7 [POLISH] `SettingsResetPresets.kt:59` `lifecycleScope.launch{}` wrapping synchronous
  `AlertDialog.Builder.show()` — drop the launch.
- ☐ DEC-8 [POLISH] 3 unused imports: SettingsDialogs.kt (LocalContext), ClipboardSection.kt (FontStyle),
  MultiLanguageSection.kt (CardDefaults).
- ☐ DEC-9 [STREAMLINE] ActivitiesSection.kt:90-259 bypasses SettingsNavigation wrappers
  (openExtraKeysConfig/openLayoutManager/openCalibration) with raw startActivity. Route through wrappers.

### Autocorrect
- ☐ AC-3 [LOW] `OptimizedVocabulary.kt:105` `_autocorrect_confidence_min_frequency = 500` init → align to 100.
- ☐ AC-4 [MISSING/TODO] Possessive-typo (`"embeer's"`) neither corrected nor handled — add TODO +
  optional: correct base, re-append `'s`. WordPredictor.kt ~1907.
- ☐ AC-5 [POLISH] KDoc `isAdjacentTransposition`/`AutocorrectCandidate`: note distant-char alias
  transpositions can lose to a 1-sub competitor (documented edge, no fix).

### Docs / cleanup
- ☐ DOC-1 `.claude/skills/dictionary-pipeline.md:33` (en_enhanced.txt "can be deleted" — already deleted),
  `:92` word_count=52042 → 98140; `docs/specs/english-dictionary-pipeline.md` Vestigial/Rebuild sections stale.
- ☐ DOC-2 Stale "50k" comments: OptimizedVocabulary.kt:992,1005,1010; DictionaryDataSource.kt (×6).
- ☐ DOC-3 `detect_misspellings.py:16` lists `metaphone` dep never imported — drop from docstring.
- ☐ CLEAN-1 Delete superseded committed review artifacts: `scripts/misspelling_review.txt`,
  `scripts/accented_words_for_review.txt`. Audit 8 dead ONNX-era `scripts/*.sh` for removal (separate, careful).

---

## Deferred big items (own sub-projects)

### ☐ T8 — Hoist settings state into a SettingsViewModel (roadmap Task 8)
187 `mutableStateOf` vars (all plain, zero prefs/config in initializers — clean surface). Move into a
`SettingsViewModel : ViewModel()` (repo already uses `by viewModels()` for BackupRestoreViewModel);
Activity keeps `internal var x by vm::x` delegates so the 33 extension files stay untouched. Benefit:
rotation-survivable settings/dialog/expansion state. Launchers + lifecycle stay in the Activity. Full
pure + instrumented gates.

### (PERF-1/2 above are the "autocorrect linear-sweep perf watch at 98k" deferred item — promoted to P1.)
