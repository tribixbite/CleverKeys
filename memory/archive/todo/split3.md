| Performance Metrics | ✅ | NeuralPerformanceStats checks setting |
| Error Reports | 🔇 | Hidden from UI - no implementation yet |

**Performance Metrics Storage:**
- File: `neural_performance_stats` (device-protected SharedPreferences)
- Path: `/data/user_de/0/tribixbite.cleverkeys/shared_prefs/neural_performance_stats.xml`
- Data: prediction counts, inference times, top-1/top-3 accuracy, model load time
- Access: `NeuralPerformanceStats.getInstance(context).formatSummary()`
- UI: Settings > Privacy > Performance Metrics (View/Export buttons)

**Swipe Data Export (fixed OOM):**
- JSON/NDJSON exports now stream from DB cursor (no memory buildup)
- View dialog: search, pagination (20/page), tap to copy trace JSON

---

## Autocorrect UX Improvements - COMPLETE (2026-01-12)

**Problem 1**: After autocorrect (e.g., "subkeys" → "surveys"), tapping original word in suggestions inserted ANOTHER word instead of replacing.

**Fix 1**: Autocorrect undo functionality
- Track autocorrect state in PredictionContextTracker (lastAutocorrectOriginalWord)
- When user taps original word in suggestions, detect autocorrect undo scenario
- Delete the autocorrected word + space, insert original word + space
- Automatically add original word to user dictionary (prevents future autocorrection)
- Show confirmation message "Added 'word' to dictionary"

**Problem 2**: Unknown words typed without triggering autocorrect had no way to add to dictionary.

**Fix 2**: "Add to dictionary?" prompt
- When unknown word (not in dictionary) is completed with space but not autocorrected
- Show "Add 'word' to dictionary?" prompt in suggestion bar
- Tapping prompt adds word to custom dictionary
- Shows confirmation message "Added 'word' to dictionary"

**Files Modified**:
- `PredictionContextTracker.kt`: Added autocorrect tracking fields and methods
- `WordPredictor.kt`: Added `isInDictionary()` method
- `SuggestionHandler.kt`: Added `handleAutocorrectUndo()` and `handleAddToDictionary()`
- `SuggestionBar.kt`: Transform `dict_add:` prefix to friendly prompt text

**Technical Details**:
- `dict_add:word` prefix format for dictionary prompt (hidden from user)
- Uses existing `showTemporaryMessage()` for confirmations
- Clears tracking when new word started (prevents stale undo state)
- Works with existing DictionaryManager for user word storage

**Problem 3** (2026-01-12): Legacy custom words from older versions wouldn't delete in Dictionary Manager.

**Fix 3**: Storage migration
- Old format: `user_dictionary` SharedPrefs with `user_words` StringSet
- New format: DirectBootAwarePreferences with `custom_words_{lang}` JSON map
- Added `migrateLegacyCustomWords()` to DictionaryManager.kt
- Runs once at init, merges legacy words to new format, clears old data
- Files Modified: `DictionaryManager.kt`

**Code Review** (2026-01-12): Dictionary management system audit (validated by Gemini 2.5 Pro via PAL MCP)

**Issues Fixed**:
1. **HIGH**: Removed vestigial `LanguagePreferenceKeys.migrateUserDictionary()` - duplicated
   DictionaryManager's migration with bugs (hardcoded "en", didn't clear legacy data)
2. **MEDIUM**: Removed redundant calls to deleted function in OptimizedVocabulary & BackupRestoreManager
3. **LOW**: Removed dead code - redundant null check in `OptimizedVocabulary.loadDisabledWords()`

**Verified Working**:
- Autocorrect UX: `dict_add:` prefix, `handleAutocorrectUndo()`, `clearAfter=true`
- Import/Export: Legacy + new format support, proper merge logic
- Storage: Consistent use of DirectBootAwarePreferences + `custom_words_{lang}` JSON

**Layout Update** (2026-01-12): QWERTY US layout reorganization

**Changes**:
- 'a': nw=home, sw=end (navigation keys)
- 'l': nw=(, ne=) (parentheses moved here)
- 'p': nw=| (pipe moved from 'l')
- 'o': nw=_ (underscore moved from 'g')
- shift: nw=esc, se=tab (from 'a' and 'q')
- Number row: special chars reorganized to nw positions

**Bug Fix** (2026-01-12): Short swipe over shift causing uppercase display

**Problem**: When doing a short swipe over shift to activate a subkey (esc, tab, capslock),
the keyboard would display uppercase letters because shift was being included in `getModifiers()`.

**Fix**: Modified `Pointers.getModifiers()` to skip non-latched latchable keys.
A modifier should only be "active" if it's LATCHED or LOCKED, not just touched/swiped over.

**Files Modified**: `Pointers.kt`, `latn_qwerty_us.xml`, `ExtraKeysPreference.kt`

**Bug Fix** (2026-01-12): Horizontal swipe direction detection

**Problem**: Swiping horizontally from 'w' to 'e' triggered NE subkey ('2') instead of swipe typing.

**Root Cause**:
1. `DIRECTION_TO_INDEX` mapped direction 4 (E) to SE (4) instead of E (6)
2. `getNearestKeyAtDirection` searched ±3 directions (135°), too wide for precise direction

**Fix** (revised after consensus with Gemini 3 Pro + Gemini 2.5 Pro):
- Corrected DIRECTION_TO_INDEX: dir 4 now maps to E (6) instead of SE (4)
- Reduced fallback range from ±3 to ±1 directions (~67° arc)
  - ±2 was still too wide: dir 4 - 2 = dir 2 (NE)
- Fixed DIRECTION_TO_SWIPE_DIRECTION[4] from SE to E for consistency

**Files Modified**: `Pointers.kt`

**Bug Fix** (2026-01-12): Contraction system preserving base words

**Problem**: Swiping "were" only showed "we're" prediction, missing the base word "were".
Same issue in French: "dans" only showing "d'ans".

**Root Cause** (revised after debugging): At line 447, `displayWord` was changed
from "were" to "we're" BEFORE the prediction was added to validPredictions. So
"were" was never in the list for the later contraction handling to preserve.

**Fix** (revised):
1. **Primary fix (line 449)**: Skip early contraction mapping for paired contraction
   bases (words in `contractionPairings`). These keep their original form.
2. **Safety net (line 782)**: Skip non-paired replacement for:
   - Paired bases (contractionPairings)
   - Real vocabulary words (frequency > 0.65)

**Files Modified**: `OptimizedVocabulary.kt`

---

## v1.2.1 Language-Specific Prefix Boosts - COMPLETE

**Feature**: Boost prefixes common in target language but rare in English

**Problem**: French word "veux" (rank=79, very common) never appeared in predictions,
but "vérification" (rank=140) did. Root cause: English-biased NN.
- NN gives low P(u|ve) because "veu" is rare in English training data
- Beam width of 6 prunes the "veu" path before it can reach "veux"
- Previous approach (LM fusion with word count) FAILED - it boosted "ver" (79 words)
  over "veu" (9 words), making predictions worse

**Solution** (validated by Gemini 3 Pro + GPT-5.2 via PAL MCP):
Log-odds prefix boosting - boost prefixes where P_target >> P_english:
```
delta = log(P_fr(c|prefix)) - log(P_en(c|prefix))
boost = C * delta (clamped to max B), threshold 1.5
```

Key insight: We need to boost prefixes that are RARE in English but COMMON in French,
not prefixes with more reachable words.

**Implementation v2 (Aho-Corasick Trie - Zero Allocation)**:
Per Gemini 3 Pro consultation: JSON-based lookups caused GC pauses from string allocation
during beam search. Replaced with memory-mapped Aho-Corasick trie for O(1) lookups.

- [x] `scripts/compute_prefix_boosts.py`: Generates sparse binary Aho-Corasick tries
      - Aho-Corasick failure links for longest-suffix backoff
      - Sparse format: ~85% smaller than dense (10MB → 1.5MB per language)
      - Binary format: PBST v2 (NodeOffsets, EdgeKeys, EdgeTargets, FailureLinks, Boosts)
- [x] `assets/prefix_boosts/*.bin`: Binary tries for de, es, fr, it, pt
      - fr.bin: 1.5MB, 90k nodes, threshold 1.5, "ve"+"u" boost = 7.97
      - es.bin: 2.2MB, 132k nodes
      - de.bin: 1.7MB, 100k nodes
      - it.bin: 1.5MB, 88k nodes
      - pt.bin: 1.5MB, 93k nodes
- [x] `onnx/PrefixBoostTrie.kt`: Memory-mapped sparse trie loader (NEW)
      - Zero heap allocation during lookup
      - `getNextState(state, char)`: O(1) amortized Aho-Corasick traversal
      - `getBoost(state, char)`: O(1) boost lookup using state
- [x] `onnx/BeamSearchEngine.kt`: State-based trie integration
      - Added `boostState: Int = 0` to BeamState for tracking trie position
      - `applyPrefixBoosts()` now uses beam.boostState for O(1) lookups
      - Beam expansion advances boostState for child beams
- [x] `onnx/SwipePredictorOrchestrator.kt`: Updated for PrefixBoostTrie
- [x] DELETED: `onnx/PrefixBoostLoader.kt` (replaced by trie)
- [x] `Config.kt`: Add `NEURAL_PREFIX_BOOST_MULTIPLIER` (1.0f) and `NEURAL_PREFIX_BOOST_MAX` (5.0f)
- [x] **Cumulative Boost Cap** (2026-01-11): Prevent runaway boosting on long words
      - Added `cumulativeBoost` field to BeamState for tracking total boost per beam path
      - Now configurable via `neural_max_cumulative_boost` setting (5-30 range, default 15.0)
      - `applyPrefixBoosts()` returns applied boosts array and respects remaining budget
      - Individual boosts capped to `maxCumulativeBoost - beam.cumulativeBoost`
      - English latency unaffected (prefix boosts not loaded for "en")
      - Validated by expert analysis via PAL MCP (Gemini 2.5 Pro)
- [x] **Strict Start Character Toggle** (2026-01-11): Helps short swipes return accurate predictions
      - Added `neural_strict_start_char` toggle (default: false)
      - When enabled, filters beams after step 0 to only keep those matching detected first key
      - First key extracted from `features.nearestKeys` in SwipePredictorOrchestrator
      - Addresses issue where short swipes with boosted prefixes yield extra long words
      - Configurable via main Settings > Multi-Language > Prefix Boost section

**Files Modified/Added**:
- MOD: `scripts/compute_prefix_boosts.py` - sparse binary Aho-Corasick trie generation
- NEW: `src/main/assets/prefix_boosts/{de,es,fr,it,pt}.bin` - binary tries (~1.5-2.2MB each)
- NEW: `onnx/PrefixBoostTrie.kt` - zero-allocation memory-mapped trie loader
- DEL: `onnx/PrefixBoostLoader.kt` - replaced by trie
- MOD: `onnx/BeamSearchEngine.kt` - state-based prefix boost application
- MOD: `onnx/SwipePredictorOrchestrator.kt` - trie integration
- MOD: `Config.kt` - prefix boost settings

**Testing Needed**:
- [ ] Test French "veux" appears in predictions with prefix boosts enabled
- [ ] Test other French words with "veu" prefix: veut, veulent
- [ ] Verify English predictions not affected (boosts only load for non-English primary)
- [ ] Test boost multiplier tuning if needed (default 1.0)
- [ ] Verify zero GC pauses during beam search with trie

---

## v1.2.0 Language Toggle & Text Menu - COMPLETE

**New Features for v1.2.0**:
- [x] "No text selected" toast for Text Assist and Replace Text when no selection
- [x] Show Text Menu command - selects word at cursor, triggers native toolbar
- [x] Primary Language Toggle - swap between two configured primary languages
- [x] Secondary Language Toggle - swap between two configured secondary languages
- [x] New preference keys: `pref_primary_language_alt`, `pref_secondary_language_alt`
- [x] Added 5 new AvailableCommand entries for per-key customization
- [x] Settings UI: "Quick Language Toggle" section with alternate language dropdowns
- [x] **FIX**: Command execution order - check custom commands BEFORE KeyValue lookup
  - Root cause: KeyValue.getKeyByName() has fallback creating String KeyValue for any name
  - This intercepted custom commands (primaryLangToggle, etc.) before they could execute
  - Fix: Check actionValue against custom commands FIRST, return if handled
- [x] **FIX**: DirectBootAwarePreferences for toggle persistence
  - Root cause: Toggle functions used `PreferenceManager.getDefaultSharedPreferences()` (credential-encrypted)
  - But service listens on `DirectBootAwarePreferences.get_shared_preferences()` (device-protected)
  - These are DIFFERENT SharedPreferences files on Android 24+ (Direct Boot)
  - Fix: Changed toggle functions to use DirectBootAwarePreferences
- [x] **FIX**: Suggestion bar message feedback (Android 13+ Toast suppression)
  - Root cause: Android 13+ (API 33+) suppresses Toast from IME services
  - Fix: Added `SuggestionBar.showTemporaryMessage()` for in-keyboard feedback
  - Shows message briefly, then restores previous suggestions
- [x] **FIX**: Touch typing predictions showing English when not primary/secondary
  - Root cause: UserDictionary words with NULL locale were included in all languages
  - Android adds typed words without locale tagging, causing cross-language contamination
  - Fix: Only include null-locale words when language is English
  - For other languages, strictly filter by matching locale only
- [x] **FIX**: Contractions not working after language toggle round-trip (2026-01-09)
  - Root cause: ContractionManager only loaded contractions at startup, not on language change
  - When toggling English → French → English, contractions (dont → don't) stopped working
  - Fix: Added contraction reload in PreferenceUIUpdateHandler when primary language changes
  - Now reloads base contractions + language-specific contractions on each toggle
- [x] **FIX**: English contractions not working for swipe/touch typing after language toggle (2026-01-09)
  - Root cause: Contraction keys (like "dont", "cant") not inserted into vocabularyTrie on reload
  - If app launched with non-English primary, vocabularyTrie never had contraction keys
  - Beam search rejected contraction words before they could reach contraction processing
  - Fix 1: OptimizedVocabulary.unloadPrimaryDictionary() now inserts contraction keys to trie
  - Fix 2: OptimizedVocabulary.loadPrimaryDictionary("en") now inserts contraction keys + sets state
  - Fix 3: ContractionManager.loadMappings() now clears maps before loading to prevent stale data
- [x] **FEAT**: Contraction suggestions in touch typing predictions (2026-01-09)
  - Touch typing now shows contraction forms (e.g., "don't") when user types key like "dont"
  - Contraction appears as first suggestion with higher score
  - Same contraction awareness as swipe typing now available for touch typing
- [x] **FIX**: English word contamination in touch typing (2026-01-09)
  - Root cause: Dictionary reload was blocked if previous load was in progress
  - WordPredictor.loadDictionaryAsync() had `isLoadingState` check that ignored reload requests
  - When user toggled language during initial English dictionary load, new language was ignored
  - Fix: Reset isLoadingState flag and let AsyncDictionaryLoader cancel previous task
  - Added debug logging for verification: sample words, language, dict size

**Technical Details**:
- `showNoTextSelectedToast(actionName)` - toast helper with try/catch
- `showTextContextMenu(ic)` - word boundary detection + setSelection
- `togglePrimaryLanguage()` - swaps pref_primary_language with pref_primary_language_alt
- `toggleSecondaryLanguage()` - swaps pref_secondary_language with pref_secondary_language_alt
- `getLanguageDisplayName(code)` - maps language codes to display names
- `SuggestionBar.showTemporaryMessage(msg, duration)` - temporary feedback display
- `CleverKeysService.showSuggestionBarMessage(msg, duration)` - public API for feedback

---

## v1.1.99 Text Processing Actions Fix - COMPLETE

**textAssist and replaceText Not Triggering Activities**:
- [x] Issue: performContextMenuAction for textAssist/replaceText not supported by most apps
- [x] Root cause: android.R.id.textAssist and android.R.id.replaceText context menu actions are rarely implemented
- [x] Fix: Use ACTION_PROCESS_TEXT intent which is widely supported
- [x] Shows app chooser (Google Assistant, translators, search engines, etc.)
- [x] Falls back to context menu action if no text selected

---

## v1.1.97+ Custom Per-Key Actions Fixes - COMPLETE

**Custom Short Swipe Commands Not Working**:
- [x] Issue: Event-type commands (config, clipboard, voice, numeric) did nothing when assigned
- [x] Root cause: CustomShortSwipeExecutor only handled InputConnection-based commands
- [x] Fix: Added KeyValue-based execution in Keyboard2View.onCustomShortSwipe()
- [x] Event-type commands now call service.triggerKeyboardEvent()
- [x] Editing-type commands call InputConnection.performContextMenuAction()

**PUA Characters Showing as Chinese in Customization UI**:
- [x] Issue: Icon characters (PUA range) rendered as Chinese in per-key selection
- [x] Root cause: Compose Text() uses system font, not special_font.ttf
- [x] Fix: Use AndroidView with Theme.getKeyFont() for icon preview
- [x] Fixed in: ShortSwipeCustomizationActivity (MappingListItem) and CommandPaletteDialog (preview)

**Custom Sublabel Icons Larger Than Built-in Icons**:
- [x] Issue: Custom per-key action icons appeared 33% larger than built-in sublabels
- [x] Root cause: Built-in sublabels with FLAG_SMALLER_FONT use _subLabelSize * 0.75f
- [x] Custom sublabels in drawCustomSubLabel() always used full _subLabelSize
- [x] Fix: Apply 0.75f scaling when useKeyFont is true
- [x] Code change: `val textSize = if (useKeyFont) _subLabelSize * 0.75f else _subLabelSize`

---

## v1.1.96 Fixes - COMPLETE

**OOM Crash on Large Language Packs**:
- [x] Issue: Importing large dictionaries (Spanish 236k words) caused OutOfMemoryError
- [x] Crash occurred in VocabularyTrie.insert when adding ALL secondary words to beam trie
- [x] Fix: Added `getTopNormalizedWords(maxCount, maxRank)` to NormalizedPrefixIndex
- [x] Limited secondary trie insertions to top 30k most frequent words
- [x] Large dictionaries still work - just uses most common words for NN predictions

**Frequency Display in Dictionary Manager**:
- [x] Issue: All words showed frequency=100 regardless of actual frequency
- [x] Root cause: MainDictionarySource.loadBinaryDictionary() hardcoded frequency=100
- [x] V2 binary format DOES store frequency rank (0-255) but it wasn't being read
- [x] Fix: Read bestFrequencyRank from LookupResult and convert to display frequency
- [x] Conversion: rank 0 → 10000, rank 255 → ~50

**Language Dictionary Regeneration**:
- [x] Regenerated all 11 languages with wordfreq for proper frequency ranks
- [x] Bundled: en, es, fr, pt, it, de (50k/25k words)
- [x] Downloadable: nl, id, ms, tl (20k words) [sw removed - wordfreq unsupported]

**Dictionary Manager Not Loading from Language Packs**:
- [x] Issue: Imported lang packs showed empty tabs in Dictionary Manager
- [x] Root cause: MainDictionarySource.getAllWords() only checked bundled assets, not installed packs
- [x] Fix: Added lang pack check via LanguagePackManager.getDictionaryPath() before assets fallback
- [x] Added loadBinaryDictionaryFromFile() and extractWordsFromIndex() helper methods

**Swahili wordfreq Fallback Bug**:
- [x] Issue: Swahili (sw) predictions were all English words
- [x] Root cause: wordfreq silently falls back to English for unsupported languages
- [x] Swahili isn't properly supported - "jambo" has freq 1.2e-7, "the" has 5.4e-2
- [x] Fix: Removed 'sw' from SUPPORTED_LANGUAGES in build_all_languages.py
- [x] Added UNSUPPORTED_LANGUAGES check in get_wordlist.py
- [x] Properly supported via wordfreq: en, es, fr, pt, it, de, nl, id, ms, tl

**Swahili External Corpus Solution (v1.1.97)**:
- [x] wordfreq doesn't support Swahili, but real frequency data IS available
- [x] Source: Kwici Swahili Wikipedia Corpus (2.8M words, CC-BY-SA)
- [x] URL: https://kevindonnelly.org.uk/swahili/swwiki/
- [x] Created parse_swahili_ods.py to extract 168k words with frequencies from ODS file
- [x] Built langpack-sw.zip with 20k words and proper frequency ranks
- [x] Top words verified as real Swahili: ya, na, wa, kwa, katika, ni, la, za, kama, cha
- [x] Rank 0 (ya) = 10000 display freq, properly distributed down to rare words

**English Dictionary V2 Binary Fix (v1.1.97)**:
- [x] Issue: English showed max frequency 255 in Dictionary Manager, other languages showed 10000
- [x] Root cause: DictionaryDataSource.kt line 59 had `if (languageCode != "en")`
- [x] English was SKIPPING V2 binary loading and falling through to JSON with raw 128-255 frequencies
- [x] Fix: Changed `if (languageCode != "en")` to `run {` so all languages use V2 binary path
- [x] Now English properly loads from V2 binary format with normalized 1-10000 scale

**English Frequency Comparison Analysis (v1.1.97)**:
- [x] Created langpack-en-opensubtitles-50k.zip (50k words from OpenSubtitles 2018 corpus)
- [x] Created langpack-en-norvig-50k.zip (50k words from Norvig/Google web corpus)
- [x] 3-way comparison: wordfreq (balanced) vs OpenSubtitles (spoken) vs Norvig (web/forum)
- [x] OpenSubtitles: casual words ranked higher (yeah 7.2x, gonna 5.4x, okay 11.8x)
- [x] Norvig: web/forum text with profanity and technical terms
- [x] Word comparison files saved: norvig_missing.txt, opensub_missing.txt
- [x] Norvig-only words mostly spam/adult content from 2008 web crawl
- [x] OpenSubtitles-only words mostly contractions as separate tokens ('s, 't, 'm, etc.)

**English Contractions Bug Fix (v1.1.97)**:
- [x] Issue: contractions_en.json was missing 18 essential contractions from contractions_non_paired.json
- [x] Missing: im->i'm, ive->i've, hes->he's, shes->she's, thats->that's, etc.
- [x] OptimizedVocabulary only loads contractions_en.json for English (not contractions_non_paired.json)
- [x] Fix: Merged 18 missing entries into both files (now 120 entries each)
- [x] Regenerated contractions.bin (120 non-paired, 1183 paired, 13KB)
- [x] Swipe predictions "im", "ive", "hes" now correctly convert to "i'm", "i've", "he's"

**English Dictionary V3 Creation (v1.1.97)**:
- [x] Issue: V2 dictionary was pure wordfreq, losing V1's curated features
- [x] V2 was missing: single letters, contractions, possessives, custom words
- [x] V2 added 14 new slurs not in V1 (wordfreq tracks actual web usage)
- [x] V3 Strategy: V1 base + V2 valuable additions - typos - specified offensive
- [x] Removed 15 common typos (dissapointed, recieved, thier, definately, etc.)
- [x] Removed only 4 offensive words per user request (retard*, shemale)
- [x] Preserved all V1 features: single letters, contractions, possessives, custom words
- [x] Final V3: 52,042 words (V1: 49,297 + V2 additions: 2,763 - removed: 18)
- [x] JSON and binary now in sync
- [x] Saved 197 accented words for later review: scripts/accented_words_for_review.txt

---

## v1.1.94/95 Features - COMPLETE

**English Duplicate in Primary Dropdown**:
- [x] Issue: English appeared twice (manually added + from availableSecondaryLanguages)
- [x] Fix: Filter out "en" when building primaryOptions

**Custom Words for Secondary Language (Touch Typing)**:
- [x] Issue: Secondary dictionary only loaded binary file, not custom words
- [x] Added `loadSecondaryCustomWords()` to WordPredictor
- [x] Custom words from `custom_words_${lang}` now added to secondary NormalizedPrefixIndex
- [x] Frequency converted to rank (0-255) for proper scoring

**Custom Words for Secondary Language (Swipe Typing)**:
- [x] Issue: Same as touch typing - secondary dict missing custom words
- [x] Added `loadSecondaryCustomWords()` to OptimizedVocabulary
- [x] Custom words now included in swipe beam search secondary lookups

**Secondary Prediction Weight Slider**:
- [x] Added `SECONDARY_PREDICTION_WEIGHT = 0.9f` constant to Config.Defaults
- [x] Added `secondary_prediction_weight` field to Config class
- [x] Added slider UI in SettingsActivity (Multi-Language section, 0.5x-1.5x range)
- [x] Updated WordPredictor.predictInternal() to use config value
- [x] Updated OptimizedVocabulary with cached `_secondaryPredictionWeight` field
- [x] Updated `updateLanguageMultiplier()` and `setAutoSwitchConfig()` to use config value

**Secondary Dictionary for Swipe Typing (True Bilingual)**:
- [x] Issue: Secondary dictionary only did accent recovery, not true bilingual predictions
- [x] NN beam search trie only contained primary language words
- [x] Fix: Add secondary dictionary words to `activeBeamSearchTrie` in `loadSecondaryDictionary()`
- [x] Log confirms: "+20847 added to beam trie" for Italian secondary

**Custom Words for NN Swipe Pipeline**:
- [x] Issue: Custom words added to vocabulary HashMap (for scoring) but NOT to beam search trie
- [x] NN couldn't predict custom words during swipe typing because they weren't in trie
- [x] Fix: Collect custom/user words into list, insert into `activeBeamSearchTrie` at end of `loadCustomAndUserWords()`
- [x] Log confirms: "Custom/user words: 5 words, +2 added to beam trie"

**Android User Dictionary Locale Filter (Swipe Pipeline)**:
- [x] Issue: OptimizedVocabulary loaded ALL Android user dictionary words regardless of language
- [x] This caused cross-language contamination (English words in French mode)
- [x] Fix was already applied to WordPredictor (v1.1.90) but NOT to OptimizedVocabulary
- [x] Fix: Add LOCALE filter to user dictionary query: `LOCALE = ? OR LOCALE LIKE ? OR LOCALE IS NULL`
- [x] Matches exact language code, or locale prefix (fr_FR), or global (null locale)

---

## v1.1.93 Fixes - COMPLETE

**English in Secondary Language Dropdown**:
- [x] Issue: English was explicitly excluded from secondary language options
- [x] Fix: Removed exclusion filter in `detectAvailableV2Dictionaries()`
- [x] Note: English uses V1 format, needs V2 conversion to work as secondary (TODO)

**Secondary Dictionary for Touch Typing**:
- [x] Issue: WordPredictor had no secondary dictionary support
- [x] Added `secondaryIndex: NormalizedPrefixIndex?` field to WordPredictor
- [x] Added `loadSecondaryDictionary()`, `unloadSecondaryDictionary()` methods
- [x] Modified `predictInternal()` to query secondary dictionary and merge results
- [x] Added `reloadWordPredictorSecondaryDictionary()` to PredictionCoordinator
- [x] Wired up preference change handler for secondary language changes

---

## v1.1.92 Fixes - COMPLETE

**Language-Specific Custom Words Keys**:
- [x] Issue: Custom words used legacy global key `"custom_words"` for ALL languages
- [x] This mixed French custom words with English custom words
- [x] Fix: Use `LanguagePreferenceKeys.customWordsKey(language)` → `"custom_words_${lang}"`
- [x] Updated WordPredictor.loadCustomAndUserWordsIntoMap() to use language-specific key
- [x] Updated WordPredictor.loadDisabledWords() to use language-specific key
- [x] Updated UserDictionaryObserver.loadCustomWordsCache() to use language-specific key
- [x] Updated UserDictionaryObserver.checkCustomWordsChanges() to use language-specific key
- [x] Updated SharedPreferences listener to watch for language-specific key changes

**Files Modified**:
- WordPredictor.kt: loadCustomAndUserWordsIntoMap(), loadDisabledWords(), setLanguage()
- UserDictionaryObserver.kt: prefsListener, loadCustomWordsCache(), checkCustomWordsChanges()

---

## v1.1.91 Fixes - COMPLETE

**Locale Format Matching Fix (19b10d9e)**:
- [x] Issue: v1.1.90 used exact locale match (`LOCALE = 'fr'`)
- [x] But Android uses full locale codes like `"en_US"`, `"fr_FR"`, `"fr_CA"`
- [x] Fix: Use `LIKE` for partial match: `LOCALE = ? OR LOCALE LIKE ? OR LOCALE IS NULL`
- [x] Now matches: `"fr"`, `"fr_FR"`, `"fr_CA"`, and `null` (global words)

**UserDictionaryObserver Locale Filtering (19b10d9e)**:
- [x] Issue: Observer had NO language filter - loaded ALL words from system UserDictionary
- [x] This caused English words to appear when user switched to French-only
- [x] Fix: Added `setLanguage(language)` method to observer
- [x] Observer now filters `loadUserDictionaryCache()` and `checkUserDictionaryChanges()` by locale
- [x] `WordPredictor.setLanguage()` now propagates to observer

## v1.1.90 Fixes - COMPLETE

**Touch Typing UserDictionary Contamination Fix (a94ab90d)**:
- [x] Root cause: `WordPredictor` loaded ALL words from Android UserDictionary regardless of language
- [x] This contaminated French-only touch typing with English user dictionary words
- [x] Swipe typing was fixed in v1.1.89 (dictionary regeneration)
- [x] Fix: Filter UserDictionary query by LOCALE column
- [x] Added `language` parameter to `loadCustomAndUserWords()` and `loadCustomAndUserWordsIntoMap()`
- [x] Query: `LOCALE = ? OR LOCALE IS NULL` (matches current lang or global words)
- [x] Pass language through async loading callback chain
- [x] Test: touch typing with French primary language

**Dictionary Regeneration (from previous session)**:
- [x] Root cause: fr/de/pt/it_enhanced.bin contained English "cognate" words
- [x] French: regenerated with 24722 pure words (removed 278 English-only words)
- [x] German: removed 168 English-only words
- [x] Portuguese: removed 180 English-only words
- [x] Italian: removed 153 English-only words
- [x] Swipe typing confirmed fixed by user

## v1.1.93-94 Fixes - COMPLETE

**Race Condition Fix (CRITICAL - 776ce3e8)**:
- [x] Root cause: `_primaryLanguageCode` was set BEFORE `activeBeamSearchTrie` was updated
- [x] Created window where `getVocabularyTrie()` saw:
  - `_primaryLanguageCode = "fr"` (already set by setPrimaryLanguageConfig)
  - `activeBeamSearchTrie` still pointing to English trie (loading not done)
- [x] Language mismatch check correctly returned null (safety)
- [x] Null trie = unconstrained beam search = English-sounding words
- [x] Fix: Set `_primaryLanguageCode` AFTER `activeBeamSearchTrie` in `loadPrimaryDictionary()`
- [x] `loadVocabulary()`: Use local variable for contraction logic, don't set `_primaryLanguageCode`
- [x] `setPrimaryLanguageConfig()`: Only set `_primaryLanguageCode` for English
- [x] `unloadPrimaryDictionary()`: Reset `_primaryLanguageCode` to "en"
- [x] Swipe typing confirmed fixed by user

## v1.1.92 Fixes - COMPLETED

**Thread Visibility Bug (b9a66bfa)**:
- [x] Root cause: `activeBeamSearchTrie` was NOT @Volatile
- [x] `loadPrimaryDictionary()` runs on init thread, writes new French trie
- [x] `getVocabularyTrie()` runs on main thread, reads stale cached English trie reference
- [x] Without @Volatile, CPU caching prevented cross-thread visibility
- [x] Added @Volatile to:
  - `activeBeamSearchTrie` (critical - beam search trie reference)
  - `normalizedIndex`, `secondaryNormalizedIndex` (accent lookups)
  - `_primaryLanguageCode`, `_secondaryLanguageCode`, `_englishFallbackEnabled` (language config)

## v1.1.91 Fixes - COMPLETE

**V2 Dictionary Format Support for WordPredictor (Touch Typing)**:
- [x] `BinaryDictionaryLoader.loadDictionary()` now supports both V1 and V2 formats
- [x] `BinaryDictionaryLoader.loadDictionaryWithPrefixIndex()` now supports V2 format (33979adb)
  - V1: loads pre-built prefix index from file
  - V2: loads canonical words and builds prefix index at runtime
  - This was the CRITICAL missing piece - AsyncDictionaryLoader uses this method
- [x] V1 format (magic 'DICT'): English dictionary, word+frequency pairs
- [x] V2 format (magic 'CKDT'): Non-English dictionaries with canonical words and frequency ranks
- [x] Rank-to-frequency conversion: rank 0 → ~1M, rank 255 → ~5K
- [x] Enables French, German, Italian, Portuguese, Spanish touch typing predictions

**Previous Fixes (v1.1.89-v1.1.90)**:
- [x] `PredictionCoordinator.initialize()` now called from ManagerInitializer (was missing)
- [x] `PreferenceUIUpdateHandler` reloads WordPredictor dictionary when language changes
- [x] Added `reloadWordPredictorDictionary()` method to PredictionCoordinator

**English Words in Beam Search (Diagnosis)**:
- [x] BeamSearchEngine runs UNCONSTRAINED when vocabTrie is null
- [x] `getVocabularyTrie()` returns null on language mismatch (primary≠en but trie is English)
- [x] Root cause identified: Thread visibility - @Volatile was missing (fixed in v1.1.92)

## v1.1.89 Fixes - COMPLETED

**Dictionary Manager Language Fix**:
- [x] `MainDictionarySource` was hardcoded to load `en_enhanced.json`
- [x] Added `languageCode` parameter to load correct language dictionary
- [x] `WordListFragment` now passes language code to `MainDictionarySource`
- [x] Binary dictionary loading added for non-English languages

**Beam Search Trie Defensive Check**:
- [x] `getVocabularyTrie()` now verifies trie matches expected language
- [x] If Primary=non-English but trie is still English, returns null (disables constraining)
- [x] Logs error message to help diagnose initialization issues
- [x] Added diagnostic logging to check for English words in non-English trie

**Autocorrect Language Contamination Fix**:
- [x] `WordPredictor.autoCorrect()` now skips when primary language is non-English
- [x] Fixes: "bereits" being inserted as "berries" in German mode
- [x] Root cause: autocorrect was fuzzy matching against English dictionary
- [x] Solution: Check `config.primary_language` before allowing autocorrect

**English Fuzzy Matching Fix**:
- [x] `OptimizedVocabulary.filterPredictions()` skips English vocab fuzzy matching
- [x] Skip condition: `_primaryLanguageCode != "en" && !_englishFallbackEnabled`
- [x] Prevents English words from "rescuing" rejected beam outputs

**Touch Typing Dictionary Fix**:
- [x] `PredictionCoordinator.initializeWordPredictor()` now loads `config.primary_language`
- [x] Previously hardcoded to "en", causing English predictions in French/German mode
- [x] `PredictionCoordinator.setConfig()` detects language changes and reloads dictionary
- [x] `WordPredictor.autoCorrect()` now uses loaded language dictionary

**Diagnostic Logging**:
- [x] BeamSearchEngine: Logs trie status (null vs active) on first masking call
- [x] BeamSearchEngine: Logs prefix masking details for debugging
- [x] OptimizedVocabulary: Logs if English test words found in non-English trie

---

## v1.1.88 Fixes - RELEASED

**Spanish Accent Key Fix (#40)**:
- [x] Fixed short gesture handling for dead keys (accent modifiers)
- [x] Dead keys like `accent_aigu` now LATCH instead of producing no output
- [x] Swipe SW on "d" → latches accent → tap "a" → produces "á"
- [x] Root cause: `onPointerDown/onPointerUp` doesn't latch modifiers
- [x] Fix: Detect `FLAG_P_LATCHABLE` and create latched pointer instead

**French Contraction Fix**:
- [x] Fixed "mappelle" → "m'appelle" not working when Primary=French, Secondary=None
- [x] Root cause #1: `_englishFallbackEnabled` was false, skipping vocabulary lookup
- [x] Fix #1: Check `nonPairedContractions` BEFORE filtering out the word
- [x] Root cause #2: `loadPrimaryDictionary()` created new trie, discarding contractions
- [x] Fix #2: Add contraction keys to the new language trie after creating it
- [x] Now beam search can discover "mappelle" and convert to "m'appelle"

**Legacy Dictionary Migration (v1.1.88)**:
- [x] `LanguagePreferenceKeys.migrateUserDictionary()` migrates legacy `user_dictionary` SharedPreferences
- [x] `BackupRestoreManager` export now uses language-specific format (`custom_words_by_language`)
- [x] `BackupRestoreManager` import handles both old (array) and new (language map) formats
- [x] Old JSON imports automatically migrate to English language-specific keys
- [x] Migration runs on app startup via `OptimizedVocabulary.loadCustomAndUserWords()`

---

## Language Pack Contractions (v1.1.87) - COMPLETE

**Problem**: Imported language packs (NL, ID, MS, SW, TL) didn't load contractions.

**Implementation**:
- [x] Updated `build_langpack.py` to include `contractions.json` in ZIP if present
- [x] Updated `extract_apostrophe_words.py` with Dutch 's plural handling
- [x] Created contraction files: nl (118 mappings), id/ms/sw/tl (empty - no apostrophes)
- [x] `LanguagePackManager` now extracts `contractions.json` from ZIP during import
- [x] Added `getContractionsPath(code)` method to LanguagePackManager
- [x] `ContractionManager` tries language pack first, falls back to assets
- [x] `OptimizedVocabulary` also updated to load from language packs

**Dictionary Manager Improvements (v1.1.87)**:
- [x] Added language change broadcast from SettingsActivity
- [x] DictionaryManagerActivity listens for `LANGUAGE_CHANGED` broadcasts
- [x] Tabs rebuild automatically when primary/secondary languages change
- [x] Added support for user-imported language pack Custom tabs
- [x] Modularized `setupViewPager()` with helper methods

**Testing Note**: Users with OLD imported langpacks need to re-import to get contractions.json extracted.

---

## Multilanguage Contractions/Apostrophes (v1.1.87) - COMPLETE

**Problem**: Swiping "cest" outputs "cest" instead of "c'est" in French mode.

**Root Causes Fixed**:
1. **preprocess_aosp.py** - `word.isalpha()` was filtering out apostrophe words
2. **Autocorrect** - WordPredictor was matching "cest" to "cent" (75% char match)
3. **OptimizedVocabulary** - `_primaryLanguageCode` defaulted to "en" before contractions loaded

**Implementation**:
- [x] Fixed preprocess_aosp.py to allow apostrophe characters in words
- [x] Created extract_apostrophe_words.py to extract from ASK dictionaries
- [x] Generated language contraction files:
  - contractions_fr.json: 27,494 mappings (cest→c'est, jai→j'ai, etc.)
  - contractions_it.json: 22,474 mappings (luomo→l'uomo, etc.)
  - contractions_de.json: 24 mappings (gehts→geht's, etc.)
  - contractions_en.json: 122 mappings (dont→don't, etc.)
- [x] Added isContractionKey() to ContractionManager for autocorrect bypass
- [x] InputCoordinator now skips autocorrect for contraction keys
- [x] ManagerInitializer loads language-specific contractions at startup
- [x] OptimizedVocabulary.loadVocabulary() now accepts primaryLanguageCode parameter
- [x] SwipePredictorOrchestrator passes primary language from prefs to loadVocabulary
- [x] Added cache reload logic for non-English language contractions

**Testing Verified**:
- [x] Verify "cest" → "c'est" transformation in French mode ✓
- [x] Verify "jai" → "j'ai" transformation ✓
- [x] Verify "dont" → "don't" in English mode ✓
- [x] Verify autocorrect doesn't corrupt contraction keys to similar words ✓

**Key Files Modified**:
- `scripts/preprocess_aosp.py` - Allow apostrophe words
- `scripts/extract_apostrophe_words.py` - NEW: Extract from ASK dictionaries
- `src/main/kotlin/.../ContractionManager.kt` - isContractionKey(), loadLanguageContractions()
- `src/main/kotlin/.../InputCoordinator.kt` - Skip autocorrect for contraction keys
- `src/main/kotlin/.../ManagerInitializer.kt` - Load language-specific contractions
- `src/main/kotlin/.../OptimizedVocabulary.kt` - loadVocabulary(primaryLanguageCode)
- `src/main/kotlin/.../onnx/SwipePredictorOrchestrator.kt` - Pass language to loadVocabulary

---

## Multilanguage Full Support (v1.1.85) - COMPLETE

**Implementation Summary**:
- [x] Primary Language selector (any QWERTY-compatible language)
- [x] Neural network outputs 26 English letters; dictionary provides accent recovery
- [x] 6 bundled languages: EN, ES, FR, PT, IT, DE
- [x] 9 downloadable language packs: FR, PT, IT, DE, NL, ID, MS, SW, TL
- [x] Primary dictionary loads accent mappings (e.g., "cafe" → "café")
- [x] Secondary dictionary unchanged (bilingual support)

**Key Files Modified**:
- `scripts/build_all_languages.py` - Master script to generate all dictionaries
- `scripts/get_wordlist.py` - wordfreq extraction with fallback (large→small→best)
- `src/main/kotlin/tribixbite/cleverkeys/OptimizedVocabulary.kt` - loadPrimaryDictionary(), getPrimaryAccentedForm()
- `src/main/kotlin/tribixbite/cleverkeys/SettingsActivity.kt` - Primary Language dropdown
- `src/main/kotlin/tribixbite/cleverkeys/onnx/SwipePredictorOrchestrator.kt` - loadPrimaryDictionaryFromPrefs()

**Bundled Dictionaries** (in assets/dictionaries/):
| Language | File | Size | Words |
|----------|------|------|-------|
| English | en_enhanced.bin | 1.2MB | ~50k |
| Spanish | es_enhanced.bin | 6.7MB | ~236k |
| French | fr_enhanced.bin | 616KB | 25k |
| Portuguese | pt_enhanced.bin | 619KB | 25k |
| Italian | it_enhanced.bin | 630KB | 25k |
| German | de_enhanced.bin | 650KB | 25k |

**Language Packs** (in scripts/dictionaries/):
| Language | File | Size | Source |
|----------|------|------|--------|
| Dutch | langpack-nl.zip | 244KB | wordfreq |
| Indonesian | langpack-id.zip | 232KB | wordfreq |
| Malay | langpack-ms.zip | 228KB | wordfreq |
| Swahili | langpack-sw.zip | 231KB | Kwici Wikipedia Corpus (CC-BY-SA) |
| Tagalog | langpack-tl.zip | 237KB | wordfreq |

**Bugs Fixed (2026-01-04)**:
- [x] Primary dictionary lookup order: Now checks primary FIRST, then falls back to English
- [x] English fallback disabled: When Primary=French, Secondary=None → only French predictions
- [x] Multilang toggle bug: Dictionary now loads for ANY non-English primary
- [x] **CRITICAL FIX**: Language-specific beam search tries (each language has own trie)
- [x] **CRITICAL FIX**: Language dictionary not reloading on preference change (47cc00ef)
  - PreferenceUIUpdateHandler now triggers reloadPrimaryDictionary/reloadSecondaryDictionary
  - Previously dictionaries only loaded at initialization
  - Changing language in settings now immediately updates beam search trie

**Dictionary Verification (2026-01-04)**:
- [x] FR: V2 format, 29k canonical, 23.7k normalized (être, café, français ✓)
- [x] ES: V2 format, 236k canonical, 223k normalized (niño, español, años ✓)
- [x] PT: V2 format, 30k canonical, 24.5k normalized (você, não, também ✓)
- [x] IT: V2 format, 30k canonical, 24.8k normalized (perché, più, città ✓)
- [x] DE: V2 format, 26k canonical, 24.8k normalized (für, über, größe ✓)

**Architecture Documentation**:
- NEW: `docs/specs/neural-multilanguage-architecture.md` - Complete pipeline documentation

**The Fix Explained** (Refactored - cleaner architecture):
Each language now has its own beam search trie built from normalized words:

```
vocabularyTrie (English)      ← always loaded
activeBeamSearchTrie          ← points to current language's trie

Primary=French:
  French Dict → normalize → French Trie → activeBeamSearchTrie
  Beam search uses ONLY French words
  etre → être (post-processing)

Primary=English:
  activeBeamSearchTrie = vocabularyTrie (English)
```

No mixing of languages in a single trie - clean separation.

**Testing Needed** (manual test required - device locked):
- [x] Dictionary verification completed programmatically (2026-01-04)
- [ ] Verify Primary Language dropdown shows EN, ES, FR, PT, IT, DE
- [ ] Test accent recovery: swipe "cafe" → "café" with French primary
- [ ] Test French-only words: swipe "etre" → "être" (architecture now supports this)
- [ ] Test French-only words: swipe "francais" → "français"
- [ ] Confirm no English-only words appear when Primary=French, Secondary=None
- [ ] Test language reload on preference change (settings → keyboard)

**Completed (v1.1.86)**:
- [x] Split custom/disabled words by language in storage layer (061fc67e)
  - `LanguagePreferenceKeys.kt` - Helper for language-specific preference keys
  - `OptimizedVocabulary.kt` - Uses `custom_words_{lang}` and `disabled_words_{lang}` keys
  - `DisabledDictionarySource` - Accepts optional languageCode parameter
  - Automatic migration from global keys to English keys on first run
  - Spec: `docs/specs/language-specific-dictionary-manager.md`
- [x] Add language-specific tabs to Dictionary Manager UI (32856e92)
  - Multilang mode: Active [EN], Disabled [EN], Custom [EN], User Dict, Active [ES], Disabled [ES], Custom [ES]
  - Single language mode: Standard tabs with language label if non-English
  - Tab layout uses MODE_SCROLLABLE when >4 tabs
  - WordListFragment accepts optional languageCode parameter

---

## F-Droid Submission Status

### MR !30449 - In Progress
- [x] Remove pre-built binaries (JAR, .so, .bin files)
- [x] Add compose source files (srcs/compose/)
- [x] Create scripts/generate_compose_bin.py for build-time generation
- [x] Add generateComposeData gradle task
- [x] Update .gitignore for F-Droid compliance
- [x] Add 512x512 icon.png for fastlane metadata
- [x] Fix python → python3 for F-Droid build environment
- [x] Fix Groovy spread operator incompatibility
- [x] Remove duplicate compileComposeSequences task
- [x] Fix shift constant case mismatch
- [x] Lower SDK from 35 to 34 for androguard compatibility
- [x] Downgrade androidx.core to 1.13.1 for SDK 34 compatibility
- [x] Add novcheck to bypass androguard APK version parsing issue
- [x] Implemented semantic versioning system (vMAJOR.MINOR.PATCH)
  - versionCode = MAJOR * 10000 + MINOR * 100 + PATCH
  - ABI versionCode = base * 10 + abiCode (1=armv7, 2=arm64, 3=x86_64)
- [x] Updated GitHub Actions release workflow for semantic versions
- [x] Created docs/VERSIONING.md documentation
- [x] Fixed F-Droid schema validation (AutoUpdateMode, VercodeOperation array format)
- [x] Fixed APK output pattern (wildcard for arm64-v8a)
- [x] Clean start: deleted old releases (v1.0.0, v1.1.0, v2.0.0) for fresh submission
- [x] Added static version variables in build.gradle for F-Droid checkupdates parsing
