# CleverKeys Working TODO List

**Last Updated**: 2026-01-09
**Status**: v1.2.0 - Language toggles + text menu + multilanguage swipe typing

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
- [x] Enabled auto-update (UpdateCheckMode: Tags ^v[0-9]+\.[0-9]+\.[0-9]+$)
- [x] Created first official release: v1.0.0
- [x] GitHub Actions v1.0.0 release succeeded (3 APKs published)
- [x] F-Droid pipeline 2212215842: ALL 8 JOBS SUCCESS with auto-update enabled!
- [x] Fix permission warnings for clean install experience (2025-12-13)
  - Removed RECORD_AUDIO (voice typing uses external IME)
  - Removed REQUEST_INSTALL_PACKAGES (F-Droid handles updates)
  - Removed RECEIVE_BOOT_COMPLETED, WAKE_LOCK, storage permissions
  - Only VIBRATE and READ_USER_DICTIONARY remain
- [x] Remove self-update feature (2025-12-13)
  - Removed legacy external storage APIs (Environment.getExternalStorageDirectory)
  - Removed checkForUpdates, installUpdateFromDefault, APK picker functions
  - F-Droid handles updates - no need for in-app update mechanism
  - Storage usage now fully scoped storage compliant
- [x] Fix neural settings defaults mismatch (2025-12-13)
  - NeuralSettingsActivity had hardcoded defaults (4, 35) instead of Defaults.* (6, 20)
  - SwipePredictorOrchestrator had hardcoded fallbacks instead of Defaults.*
  - All neural parameter defaults now use Defaults.* constants for consistency
- [x] Add short swipe customizations to backup system (2025-12-13)
  - Short swipe customizations stored in separate JSON file were NOT backed up
  - Now exported as `short_swipe_customizations` in config backup JSON
  - Import restores customizations automatically
- [x] Fix resetToDefaults() in NeuralSettingsActivity (2025-12-13)
  - resetToDefaults() had hardcoded values (4, 35, 0.1f, etc.)
  - Now uses Defaults.* constants for consistency with all settings screens
- [x] Rebase F-Droid MR (2025-12-13)
  - Rebased cleverkeys branch against upstream/master
  - Added v1.0.1 build entry to metadata
  - Pipeline passed successfully
- [x] Fix app name bug in release builds (2025-12-13)
  - resValue was using "@string/app_name_release" literal (doesn't resolve)
  - Fixed to use literal strings: "CleverKeys" for release, "CleverKeys (Debug)" for debug
  - Released as v1.0.2
- [x] Fix version mismatch bug in build.gradle (2025-12-13)
  - defaultConfig had hardcoded versionCode 10000/versionName "1.0.0"
  - ext.versionCode was updated to newer versions
  - APKs were built with wrong versionCode, breaking F-Droid reproducibility
  - Fixed by syncing both defaultConfig and ext values
  - Removed v1.0.2 from F-Droid metadata (had the bug)
  - Released as v1.0.3 with fix
- [x] Play Protect now shows "App safe" (2025-12-13)
  - After v1.0.2 fix, Play Protect no longer flags as suspicious
- [x] F-Droid pipeline SUCCESS (2025-12-13)
  - Removed binary verification (source builds don't match GitHub release byte-for-byte)
  - Added output/novcheck for proper ABI-specific APK handling
  - All 3 APKs (armv7, arm64, x86_64) built successfully
  - Pipeline 2213182520: ALL JOBS PASSED
- [x] Fix neural prediction not working in release builds (2025-12-13)
  - Root cause: proguard-rules.pro had wrong package name (tribixbite.keyboard2 → tribixbite.cleverkeys)
  - R8 was stripping ONNX/neural classes in release APKs
  - Added comprehensive keep rules for all neural prediction classes
- [x] Fix APK naming for Obtainium (2025-12-13)
  - Changed arm64.apk → arm64-v8a.apk, armv7.apk → armeabi-v7a.apk
  - Proper ABI names for app store compatibility
- [x] Released v1.0.4 (2025-12-13)
- [x] Fix swipe prediction accuracy regression in release builds (2025-12-13)
  - Root cause: R8 stripping ONNX inner classes (PredictionPostProcessor.Result, BeamSearchEngine.BeamState)
  - Added `$**` pattern to keep all inner classes in onnx package
  - Added Keyboard2View field preservation for NeuralLayoutHelper reflection access
  - Added comprehensive rules for dictionary, vocabulary, customization, theme classes
  - Added JNI-specific rules and Kotlin metadata attributes
  - 128 new proguard rules total
- [x] Verified ONNX execution provider configuration (2025-12-13)
  - ModelLoader tries XNNPACK first (FP32, most stable), then NNAPI as fallback
  - NNAPI's potential FP16 precision issues are avoided
  - SessionConfigurator.kt is dead code (not used by prediction pipeline)
- [x] Released v1.0.5 (2025-12-13)
- [x] Added fastlane changelogs for v1.0.3-1.0.5 (2025-12-14)
- [x] Fixed version mismatch: synced defaultConfig with ext values (2025-12-14)
  - linsui flagged that build.gradle#L143 had wrong versionCode/versionName
  - ext had 1.0.6 but defaultConfig had 1.0.4 - now both are 1.0.6
- [x] Released v1.0.6 with correct versions (2025-12-14)
- [x] Added v1.0.6 builds to fdroiddata metadata (2025-12-14)
- [x] Rebased fdroiddata fork onto upstream/master (2025-12-14)
- [x] Implemented single source of truth versioning system (2025-12-16)
  - VERSION_MAJOR/MINOR/PATCH as single source in build.gradle ext block
  - defaultConfig references ext values (no duplication)
  - CI verification step in release.yml to fail if tag doesn't match version
- [x] Fixed compose_data.bin determinism issue (2025-12-16)
  - Added sorted() to compose_files iteration in generate_compose_bin.py
  - os.listdir() returned arbitrary filesystem-dependent order
- [x] Removed novcheck from all fdroiddata metadata entries (2025-12-16)
- [x] Fixed status/navigation bar color overlay on OEM devices (2025-12-16)
  - Added enableEdgeToEdge() API in LauncherActivity
  - Updated launcherTheme to use transparent system bars
  - Restructured Compose layout for proper edge-to-edge display
- [x] Fixed keyboard navigation bar showing background color (2025-12-16)
  - Set keyboard navigation bar to transparent in Keyboard2View
  - Allows keyboard to extend behind nav bar on gesture nav devices
- [x] Fixed suggestion bar collapse when empty (2025-12-16)
  - Added minimum width (200dp) to SuggestionBar
  - Enabled fillViewport on suggestion scroll view
- [x] Moved ui-tooling to debugImplementation for reproducible builds (2025-12-16)
  - Jetpack Compose ui-tooling can embed machine-specific paths
  - Now excluded from release APKs, only included in debug builds
- [x] **REPRODUCIBILITY SPRINT** (2025-12-18)
  - Identified build-tools 35.0.0 breaks apksigcopier (F-Droid issue #3299)
  - Downgraded to build-tools 34.0.0 in all environments
  - Released v1.1.27 with fixed toolchain
  - Got Gemini second opinion via zen-mcp on reproducibility config
  - Implemented Gemini recommendations:
    - [x] Exact Temurin 21.0.9+10 JDK download in F-Droid metadata
    - [x] TZ=UTC, LANG=en_US.UTF-8, LC_ALL=en_US.UTF-8 env vars
    - [x] Updated GitHub workflow with same locale/timezone settings
    - [x] Updated build-on-termux.sh for local consistency
  - F-Droid metadata ready in fdroiddata_temp/ (not tracked in git)
- [x] Submit updated F-Droid metadata MR
- [x] Multiple reproducibility fixes (v1.1.56-v1.1.70)
  - v1.1.65: Removed inline python script per linsui feedback
  - v1.1.66: Added `--internal` flag to fix zipalign compatibility
  - v1.1.67: Removed META-INF rm-files step (not needed for reproducibility)
  - v1.1.68: Tested without fix-pg-map-id - build passed, checkupdates timing issue
  - v1.1.69: Restored fix-pg-map-id as fallback
  - v1.1.70: Confirmed fix-pg-map-id NOT needed! Simplest possible config works!
- [x] Comprehensive F-Droid metadata update (2025-12-20)
  - Updated short_description (48 chars, emphasizes Termux)
  - Rewrote full_description with Termux focus, per-key gestures emphasis
  - Added featureGraphic.jpg for F-Droid Latest tab
  - Reorganized screenshots (Termux first, clean numbered names)
  - Added v1.1.70 changelogs
  - Added video.txt with per-key customization demo
  - Added Spanish (es) translation for F-Droid Latest tab visibility
  - Fixed "Sub-200ms" → "Sub-100ms" for accurate latency claim
- [x] F-Droid MR #30449 merged (2025-12-21) - CleverKeys now on F-Droid!
- [x] Cron monitoring: checks MR status every 5 min, notifies on merge
- [x] Fix web demo model loading on CDN edge cases (2025-12-21)
  - validateFile() now falls back to range request when HEAD lacks content-length
  - Fixes "Tokenizer config appears incomplete (0KB < 0.5KB)" error on some CDNs
- [x] Fix decoder src_mask mismatch (2025-12-21)
  - Decoder was using all-zeros src_mask (attending to padded garbage)
  - Now passes actualSrcLength from encoder to decoder
  - Decoder creates matching mask: 1 for padded positions, 0 for real data
- [x] Expand CommandRegistry with 75+ new commands (2025-12-21)
  - Added 32 new Android KeyEvent codes (media, volume, brightness, zoom, system/app keys)
  - Added 5 new categories: MEDIA, SYSTEM, DIACRITICS_SLAVONIC, DIACRITICS_ARABIC, HEBREW
  - Added 10 Slavonic, 14 Arabic, 20 Hebrew diacritical marks
  - Updated CustomShortSwipeExecutor with fallback for character-based commands
  - Updated XmlAttributeMapper for XML export compatibility
  - Total commands now 200+ (up from 137)
- [x] Add icon font support for custom swipe mappings (2025-12-22)
  - ShortSwipeMapping now has useKeyFont field for icon rendering
  - DirectionMapping storage updated to v2 schema with useKeyFont
  - CommandRegistry.getDisplayInfo() extracts icon + font flag from KeyValue
  - KeyMagnifierView renders custom mappings with proper special_font.ttf
  - Keyboard2View uses theme's sublabel_paint for consistent sizing
  - CommandPaletteDialog auto-detects icon mode from command's KeyValue
  - Custom mappings now match font size/style of layout's default subkeys
- [x] Fix custom sublabel color and icon preview (2025-12-23)
  - Keyboard2View: use subLabelColor instead of activatedColor for custom mappings
  - KeyMagnifierView: use consistent subLabelColor for both custom and built-in sublabels
  - CommandPaletteDialog: show readable description [Tab], [Home] for PUA icons
  - Clear UX guidance in label dialog for icon vs text mode
- [x] Fix keyboard overlapping navigation bar on API 30-34 (2025-12-24)
  - onApplyWindowInsets() only processed insets on API 35+, but edge-to-edge was enabled on API 29+
  - API 30+: Use modern WindowInsets.Type.systemBars() API
  - API 21-29: Fall back to deprecated systemWindowInsets
  - Keyboard now properly accounts for nav bar height on all supported API levels
- [x] Switch margin settings from dp to percentages (2025-12-24)
  - Changed margin_bottom to % of screen height (0-30%)
  - Split horizontal_margin into margin_left and margin_right (0-45% each)
  - Added 90% total horizontal margin cap with dynamic slider ranges
  - Keyboard2View uses Config.margin_left/margin_right instead of horizontal_margin
  - BackupRestoreManager migrates legacy dp-based configs to percentages
  - Migration converts old horizontal_margin to symmetric left/right percentages
- [x] Fix Direct Boot crash in PrivacyManager (2025-12-24)
  - SharedPreferences in credential-encrypted storage unavailable at lock screen
  - Caused keyboard crash before device unlock, locking users out
  - Now uses createDeviceProtectedStorageContext() on API 24+
  - Matches DirectBootAwarePreferences pattern used elsewhere
- [x] Comprehensive Direct Boot compatibility fix (2025-12-24)
  - Bug present since v1.0.0 - multiple SharedPreferences classes crashed at lock screen
  - Created DirectBootManager utility for deferred PII initialization
  - Moved non-PII managers to Device Encrypted storage:
    - CustomThemeManager, MaterialThemeManager
    - ModelVersionManager, NeuralModelMetadata, NeuralPerformanceStats
  - Deferred PII components until user unlock via ACTION_USER_UNLOCKED:
    - DictionaryManager, UserAdaptationManager, WordPredictor
    - ClipboardHistoryService (uses SQLite, needs CE storage)
  - Privacy: PII data stays in secure CE storage, only deferred until unlock
  - Added cleanup in CleverKeysService.onDestroy()
- [x] Block clipboard pane access on lock screen (2025-12-24)
  - Security fix: clipboard history contains PII, was accessible on lock screen
  - Initial fix used isUserUnlocked (Direct Boot state) - only blocked before first unlock
  - Fixed: Added isDeviceLocked property using KeyguardManager.isKeyguardLocked()
  - isUserUnlocked: false only before FIRST unlock since boot (Direct Boot)
  - isDeviceLocked: true whenever screen is currently locked (keyguard showing)
  - KeyboardReceiver now uses isDeviceLocked to block clipboard on lock screen
- [x] Fix margin prefs restored by Android Auto-Backup (2025-12-24)
  - Bug: Old dp-based margin values restored from Google Drive backup
  - Interpreted as percentages (14dp → 14%, way too large)
  - Added `margin_prefs_version` flag to track if migration occurred
  - Added `migrateMarginPrefs()` that runs on every startup
  - If flag missing, ALL margin values converted from dp to percentages
  - No threshold guessing needed - flag distinguishes old vs new installs
- [x] Touch typing suggestion bar improvements (2025-12-25)
  - Added trailing space after tapping suggestion (better touch typing flow)
  - Only skip trailing space when actually IN Termux app, not just mode enabled
  - Applied shift/capitalization to touch typing predictions in suggestion bar
  - First letter capitalized if user started typing with Shift
  - Fixed potential word deletion bug: clear lastAutoInsertedWord when starting new typed word
  - Prevents incorrectly deleting swiped word when user types then taps prediction
- [x] Fix keyboard below nav bar on first load (2025-12-25)
  - onApplyWindowInsets wasn't triggering re-layout after insets changed
  - Added requestLayout() call when insets change
  - Added onAttachedToWindow() override that calls requestApplyInsets()
  - Keyboard now correctly positions above nav bar immediately
- [x] Fix keyboard height setting not applying (2025-12-25)
  - ROOT CAUSE: Settings saved to "keyboard_height_percent" but Config read from "keyboard_height"
  - Fixed key name mismatch in SettingsActivity.kt (3 locations)
  - Also: Theme cache key didn't include config.version
  - Added config.version to cache key to invalidate on any config change
- [x] Fix landscape margins not applying on rotation (2025-12-25)
  - ROOT CAUSE: Missing onConfigurationChanged() override in CleverKeysService
  - Config.refresh() was never called on orientation change
  - Added override that calls refresh_config() to update landscape margin values
- [x] Fix swipe NN key coordinate mapping for non-uniform margins (2025-12-25)
  - **Part 1: ProbabilisticKeyDetector (nearest key detection during swipe)**
    - ROOT CAUSE: Key positions calculated starting at x=0 instead of marginLeft
    - Added marginLeft parameter to constructor and key position calculations
    - Fixed width calculation: pass key area width only (excluding margins)
  - **Part 2: SwipeTrajectoryProcessor (neural network input normalization)**
    - ROOT CAUSE: X normalization divided by total width, not key area width
    - Before: `x = rawX / keyboardWidth` (wrong when margins present)
    - After: `x = (rawX - marginLeft) / keyAreaWidth`
    - Added setMargins(left, right) method threaded through orchestrator chain
    - NeuralLayoutHelper now passes config.margin_left/margin_right to neural engine
  - Both fixes required for correct swipe typing with non-uniform margins
- [x] Fix ONNX init not retrying after Direct Boot failure (2025-12-26)
  - ROOT CAUSE: SwipePredictorOrchestrator set isInitialized=true in finally block
  - Even when model loading failed (e.g., during lock screen), flag prevented retry
  - After device unlock, subsequent keyboard opens returned cached failure result
  - FIX: Only set isInitialized=true when isModelLoaded is true
  - Also reset isInitialized in cleanup() to allow re-initialization
  - Symptoms: swipe typing not working until manually toggled off/on in settings
- [x] Password field eye toggle feature (2025-12-30)
  - Detect password/PIN input fields (all Android InputType variations)
  - Disable predictions and autocorrect in password fields
  - Material Design visibility icons (ic_visibility.xml, ic_visibility_off.xml)
  - Eye toggle in suggestion bar with theme colors
  - RelativeLayout with START_OF constraint for fixed icon position
  - HorizontalScrollView with requestDisallowInterceptTouchEvent for scrolling
  - InputConnectionProvider syncs with actual field content
  - Dots (●) when hidden, actual text when visible
  - Centered when short, scrollable when long
  - Files: SuggestionBar.kt, SuggestionHandler.kt, CleverKeysService.kt
  - Spec: docs/specs/password-field-mode.md

- [x] SwipeDebugActivity UI overhaul (2025-12-30)
  - Added back arrow, title "Swipe Debug Log", auto-focus input
  - Single-line scrollable input with debug log viewer
  - Copy and save icons for debug log output
  - Save to file uses Storage Access Framework file picker
- [x] Wire debug logger through inference pipeline (2025-12-31)
  - Fixed setDebugLogger in SwipePredictorOrchestrator (was TODO stub)
  - Chain: CleverKeysService → PredictionCoordinator → NeuralSwipeTypingEngine → SwipePredictorOrchestrator
  - Added debugModeActive flag to gate expensive string building
  - Propagation through DebugModePropagator → NeuralSwipeTypingEngine → SwipePredictorOrchestrator → SwipeTrajectoryProcessor
- [x] Comprehensive debug logging for swipe inference (2025-12-31)
  - Touch trace coordinates (first/last 5 points)
  - Detected key sequence with start/end key analysis
  - Out-of-bounds point counting
  - Normalization parameters (keyboard dims, margins, QWERTY bounds, Y-offset)
  - Raw-to-normalized coordinate transformations with clamping warnings
  - Timing breakdown (feature extraction, encoder, decoder, post-processing)
  - Raw beam search output before vocabulary filtering
- [x] Deep analysis: Training vs Android feature calculation (2026-01-01)
  - Restored training files from git history to `model/` folder
  - Verified feature calculation matches Python training:
    - Timestamps: milliseconds with 1e-6 minimum ✓
    - Velocity: dx/dt (normalized coords / ms) ✓
    - Acceleration: dvx/dt ✓
    - Clipping: [-10, 10] ✓
    - Token mapping: a=4..z=29 ✓
  - Small velocities (0.0001 range) are EXPECTED - model was trained on this
  - Attempted ms→s conversion made predictions WORSE (confirmed training used ms)
- [x] Fix debug logging to use proper debugLogger pattern (2026-01-01)
  - Previous logging used android.util.Log.e() which goes to logcat
  - Replaced with debugLogger?.invoke() to send to SwipeDebugActivity
  - Added debugLogger field and setDebugLogger() to InputCoordinator
  - Wired up in CleverKeysService.onCreate() via DebugLoggingManager
  - Debug messages now gated behind user's debug mode setting
- [x] Beam search deduplication fix (2026-01-01)
  - Added HashSet<List<Long>> to deduplicate beams by token sequence
  - Prevents identical words appearing multiple times in predictions
  - Fixed SOS/PAD token masking (set to -infinity instead of skipping)
- [x] Beam search early termination fix for long words (2026-01-01)
  - Root cause: ADAPTIVE_WIDTH_STEP=5 and SCORE_GAP_STEP=3 terminated too early
  - Short words like "danger" (6 chars) finished before "dangerously" (11 chars) could complete
  - Increased ADAPTIVE_WIDTH_STEP: 5→12 (don't prune width until longest common words done)
  - Increased SCORE_GAP_STEP: 3→10 (don't early-stop until long words have a chance)
  - Increased scoreGapThreshold: 5.0→8.0 (wider gap before triggering early stop)
- [x] SwipeDebugActivity text overflow fix (2026-01-01)
  - Changed input field from right-aligned to left-aligned (gravity: start)
  - Added HorizontalScrollView with proper scroll calculation
  - Text scrolls to show cursor position as user types
- [x] Custom short swipe support for Event and Editing commands (2026-01-07)
  - Issue: Per-key customization commands not working: settings, clipboard, voice typing, numeric switch, AI assistant, text replace
  - Root cause: CustomShortSwipeExecutor only handled InputConnection-based commands
  - Event-type commands (config, switch_clipboard, switch_numeric, voice_typing, voice_typing_chooser) require service.triggerKeyboardEvent()
  - Editing-type commands (replaceText, textAssist, autofill) require InputConnection.performContextMenuAction()
  - Fix: Added KeyValue-based execution path in onCustomShortSwipe() for Event and Editing kinds
  - Also fixed VOICE_INPUT in legacy AvailableCommand fallback
- [x] Icon preview fix in per-key customization UI (2026-01-07)
  - Issue: PUA characters (icons for settings, clipboard, voice, etc.) displayed as Chinese characters
  - Root cause: Compose Text() uses system font which doesn't support Private Use Area chars
  - Fix: Use AndroidView with Theme.getKeyFont() for icon rendering in:
    - MappingListItem: Shows icon when useKeyFont=true
    - CommandPaletteDialog preview: Renders actual icon instead of [Icon: name]

## Active Investigation: English Words in French-Only Mode

**Status**: DIAGNOSTIC LOGGING ADDED (83ea45f7) - Awaiting test results

**Problem**: User reports English words like "every", "word", "this" appear when Primary=French, Secondary=None (French-only mode), even after service restart.

**Investigation Progress**:
1. Verified French contraction fix works ("mappelle" → "m'appelle") ✓
2. Verified Italian contractions use same generic fix ✓
3. French dictionary (fr_enhanced.bin) exists and has 616KB (~25k words)
4. `loadPrimaryDictionary()` code path looks correct:
   - Creates new `languageTrie` from French normalized words
   - Adds contraction keys to trie
   - Replaces `activeBeamSearchTrie` with French trie
5. `getVocabularyTrie()` dynamically returns `activeBeamSearchTrie`
6. `BeamSearchEngine` gets trie on every prediction (not cached)

**Diagnostic Logging Added**:
- `getVocabularyTrie()`: logs isLanguageTrie, trieWords, primaryLanguage, englishFallback
- `loadPrimaryDictionary()`: logs before/after trie replacement status

**Potential Root Causes to Verify**:
1. Preference `pref_primary_language` not being read correctly
2. French dictionary not loading (file missing or corrupt)
3. Something resetting `activeBeamSearchTrie` after initialization
4. Race condition between async init and first prediction

**User Testing Required**:
1. Force stop app
2. Start app and open keyboard
3. Make a swipe gesture
4. Check logcat: `adb logcat | grep "getVocabularyTrie\|loadPrimaryDictionary"`
5. Expected logs should show:
   - `loadPrimaryDictionary() called with language='fr'`
   - `isLanguageTrie=true` (NOT false)
   - `trieWords=~25000` (French trie, NOT ~50000 English)

**Key Files**:
- `OptimizedVocabulary.kt`: Trie initialization and getVocabularyTrie()
- `SwipePredictorOrchestrator.kt`: loadPrimaryDictionaryFromPrefs()
- `BeamSearchEngine.kt`: applyTrieMasking() using vocabTrie

---

## Active Investigation: Long Word Prediction

**Status**: CRITICAL FIX APPLIED (22fc3279) - Length normalization in beam search confidence

**Previous Bug**: "dangerously" (11 chars) couldn't beat shorter words like "dames" (5 chars)

**Root Cause #1 (Fixed 2026-01-01)**: Beam search early termination too aggressive
- ADAPTIVE_WIDTH_STEP: 5→12, SCORE_GAP_STEP: 3→10, scoreGapThreshold: 5.0→8.0

**Root Cause #2 (CRITICAL - Fixed 2026-01-02)**: Final confidence NOT length-normalized!
- Length normalization was only applied during beam search SORTING (to keep candidates alive)
- But final confidence in `convertToCandidate()` used raw score: `exp(-score)`
- Longer words accumulate more NLL (negative log-likelihood) over more decoding steps
- Even with perfect per-step probability, longer words ALWAYS had lower confidence

**Before Fix**:
- "dames" (5 chars, NLL ~1.05) → confidence = exp(-1.05) = **0.35**
- "dangerously" (11 chars, NLL ~1.97) → confidence = exp(-1.97) = **0.14**

**After Fix** (same normalization formula as beam sorting):
- normFactor = (5 + len)^alpha / 6^alpha
- "dames": exp(-1.05/1.87) = exp(-0.56) = **0.57**
- "dangerously": exp(-1.97/3.58) = exp(-0.55) = **0.58**

Now confidence values are COMPARABLE across word lengths!

**Cleanup Complete (2026-01-02)**:
- [x] Removed length bonus feature entirely (was redundant after core fix)
- [x] Kept beam alpha (Length Penalty) as the proper GNMT tuning knob
- [x] Removed vestigial neural_model_version setting (stored but never used)

**Neural Settings Enhancements (2026-01-02)**:
- [x] Fixed NEURAL_MAX_LENGTH default: 15→20 (match model config)
- [x] Added Temperature setting (0.1-3.0) for softmax confidence tuning
- [x] Added Frequency Weight setting (0-2) for NN vs vocabulary frequency balance
- [x] Fixed repair defaults to use Defaults.* constants consistently
- [x] Added NeuralPreset enum (Speed/Balanced/Accuracy) in Config.kt
- [x] Added preset selector UI with FilterChips in NeuralSettingsActivity
- [x] Written KV cache optimization spec (docs/specs/kv-cache-optimization.md)
- [x] Written MemoryPool optimization spec (docs/specs/memory-pool-optimization.md)
- [x] Wired temperature into BeamSearchEngine logSoftmax (c40ec131)
  - Applied as `logits / temperature` before softmax
  - Lower temp = sharper, higher = more uniform distribution
- [x] Wired neural_frequency_weight into OptimizedVocabulary scoring (c40ec131)
  - Applied as multiplier on existing frequency weight
  - 0.0 = NN only, 1.0 = normal, 2.0 = heavy frequency influence
  - Applied consistently in main scoring and dictionary fuzzy matching

**Defaults Aligned (2026-01-02 ed79d668)**:
- NEURAL_FREQUENCY_WEIGHT: 1.0f → 0.57f (trust NN more, less freq bias)
- NEURAL_SCORE_GAP_STEP: 10 → 12 (delay early stopping)
- Matched values from working config export

**Testing Needed**:
- [ ] Test first-load NN fix: clear app data, verify swipe works on first try
- [ ] Test: swipe "dangerously" in SwipeDebugActivity
- [ ] Verify confidence values are now length-normalized
- [ ] Confirm long words now competitive with short words
- [ ] Test neural presets (Speed/Balanced/Accuracy) in NeuralSettingsActivity
- [ ] Test temperature slider effect on prediction sharpness
- [ ] Test frequency weight slider (0=pure NN, 2=heavy frequency)

**Completed (2026-01-03)**:
- [x] Add View and Delete buttons to Collected Data settings (27a5bccc)
  - View: Opens dialog showing all collected swipes with stats
  - Delete: Confirmation dialog to clear all data
  - Dialog shows: target word, date, keys traversed, trace points, collection source

**Completed (2026-01-04)**:
- [x] Fix NN/swipe typing not working on very first app load (7aebb6a1)
  - Root cause: Race condition between async engine init and layout listener
  - OnGlobalLayoutListener fired before neural engine finished loading
  - Listener removed itself after first layout, never calling setNeuralKeyboardLayout()
  - Fix: Keep listener active until BOTH conditions met (engine ready AND layout done)
  - Added post-initialization requestLayout() to trigger listener after engine loads
- [x] Reverted unnecessary GC optimization in trajectory processor (263ec18f)
  - User questioned added complexity; optimization was imperceptible to users
- [x] Fix keyboard behind nav bar on first display (system resource fallback)
  - Root cause: onMeasure runs before onApplyWindowInsets callback
  - Fix: Get nav bar height from `navigation_bar_height` system resource in onAttachedToWindow
- [x] Fix GitHub issue #34: Android 8.1 (API 27) crash
  - Error: NoSuchMethodError for getSystemWindowInsets()
  - Root cause: wi.systemWindowInsets compiles to getSystemWindowInsets() returning Insets (API 29+)
  - Fix: Split API branches - API 29 uses systemWindowInsets, API 21-28 uses individual systemWindowInsetLeft/Right/Bottom
- [x] Multilanguage architecture finalized with Gemini consultation
  - Accent handling: Normalize NN output (cafe) → lookup canonical (café)
  - Binary format v2: Trie-based with normalized keys, frequency ranks (0-255)
  - Multi-dict merging: SuggestionRanker with unified scoring
  - Language detection: Word-based unigram frequency model
  - Dictionary sources: AOSP (CC BY 4.0) + wordfreq/FrequencyWords (CC BY-SA 4.0)
  - Updated docs/specs/dictionary-and-language-system.md with full implementation plan

---

## Multilanguage Implementation Roadmap

### Phase 1: Foundation (v1.2.0) ✅ COMPLETE
- [x] Implement `AccentNormalizer` (Unicode NFD + accent stripping)
- [x] Create `NormalizedPrefixIndex` mapping normalized → canonical
- [x] Update `BinaryDictionaryLoader` for v2 format
- [x] Build script: `scripts/build_dictionary.py`
- [x] Generate Spanish dictionary from AOSP (236k words, 31.5% accented)

### Phase 2: Multi-Dictionary (v1.2.1) ✅ COMPLETE
- [x] Implement `SuggestionRanker` for unified scoring
  - WordSource enum with priority weights (CUSTOM > USER > SECONDARY > MAIN)
  - Scoring formula: nnConfidence × rankScore × langMultiplier × sourcePriority
  - rankAndMerge() combines primary + secondary with deduplication
- [x] Wire NormalizedPrefixIndex into OptimizedVocabulary
  - loadSecondaryDictionary() loads V2 binary format
  - createSecondaryCandidates() generates ranker candidates from NN predictions
  - getAccentedForm() maps 26-letter NN output to accented canonical forms
- [x] Add V2 support to MultiLanguageDictionaryManager
  - normalizedIndexes ConcurrentHashMap for V2 dictionaries
  - loadNormalizedIndex() with caching and error handling
  - createCandidatesFromNnPredictions() for SuggestionRanker integration
- [x] Spanish dictionary included in assets (es_enhanced.bin - 236k words)
- [x] UI: Settings → Multi-Language → Secondary Language picker (Phase 2b)
  - Dynamically detects available V2 dictionaries in assets
  - Shows display names for 25+ supported languages
  - Persists to pref_secondary_language preference
- [x] Wire preference to load secondary dictionary on startup
  - SwipePredictorOrchestrator.loadSecondaryDictionaryFromPrefs()
  - Automatically loads/unloads based on user preference
- [x] Integrate secondary dictionary into filterPredictions
  - Maps 26-letter NN output to accented forms
  - Scoring: NN confidence × 0.6 + frequency rank × 0.3 × secondary penalty
  - Deduplication prevents duplicates from both dictionaries
  - Debug logging with 🌍 emoji for secondary matches

### Phase 3: Language Detection (v1.2.2) ✅ COMPLETE
- [x] Implement `UnigramLanguageDetector` (word-based)
  - Sliding window of 10 recent words
  - Weighted scoring by frequency rank
  - Cached scores with invalidation
- [x] Ship unigram lists for bundled languages
  - `generate_unigrams.py` script using wordfreq library
  - EN: 5000 words (top frequency, alphabetic only)
  - ES: 5000 words (top frequency, alphabetic only)
- [x] Wire language detection into prediction flow
  - SuggestionHandler.updateContext() → trackCommittedWord()
  - CleverKeysService.onStartInputView() → clearLanguageHistory()
  - SwipePredictorOrchestrator manages detector lifecycle
  - Debug logging with 🌍 emoji for language scores

### Phase 4: Auto-Switching (v1.2.3) ✅ COMPLETE
- [x] Add dynamic language multiplier in OptimizedVocabulary
  - `updateLanguageMultiplier()` adjusts scoring based on detected language
  - `setAutoSwitchConfig()` configures threshold and secondary language
- [x] Wire to existing Settings UI
  - "Auto-Detect Language" toggle → `pref_auto_detect_language`
  - "Detection Sensitivity" slider → `pref_language_detection_sensitivity`
- [x] Multiplier formula:
  - Secondary language > threshold: boost (1.1 + bonus)
  - Primary language > threshold: penalty (0.85)
  - Balanced: neutral (1.0)
- [x] Update language scores after each committed word

### Phase 5: Language Packs (v1.1.84) ✅ COMPLETE
- [x] Language Pack ZIP format spec
  - manifest.json: code, name, version, author, wordCount
  - dictionary.bin: V2 binary dictionary with accent normalization
  - unigrams.txt: word frequency list for language detection
- [x] LanguagePackManager: import, validation, storage
  - ZIP import via Storage Access Framework (no internet needed)
  - Validates manifest.json and dictionary.bin magic/version
  - Stores in app internal storage: files/langpacks/{code}/
- [x] Settings UI: Import Pack + Manage dialog
- [x] Build scripts:
  - build_langpack.py: creates language pack ZIPs from word lists
  - get_wordlist.py: extracts word lists from wordfreq library
- [x] Dictionary loading integration (v1.1.84):
  - BinaryDictionaryLoader.loadIntoNormalizedIndexFromFile() for file paths
  - OptimizedVocabulary.loadSecondaryDictionary() checks packs first, then assets

---

## Previously Verified (Feature Calculation)

| Aspect | Python | Android | Match |
|--------|--------|---------|-------|
| Timestamps | ms, min 1e-6 | ms, min 1e-6 | ✅ |
| Velocity | dx/dt | dx/dt | ✅ |
| Acceleration | dvx/dt | dvx/dt | ✅ |
| Clipping | [-10, 10] | [-10, 10] | ✅ |
| Token map | a=4..z=29 | a=4..z=29 | ✅ |
| Coordinates | [0,1] normalized | [0,1] normalized | ✅ |

**Current Version**: 1.1.84 (versionCode 101843 for x86_64)
**GitHub Release**: https://github.com/tribixbite/CleverKeys/releases/tag/v1.1.84
**F-Droid MR**: https://gitlab.com/fdroid/fdroiddata/-/merge_requests/30449
**Final Config**: No srclibs, no postbuild - just gradle + prebuild sed!

---

## Release Process Quick Reference

### Version Locations (Single Source of Truth)
```
build.gradle (lines 51-53):
  ext.VERSION_MAJOR = 1
  ext.VERSION_MINOR = 1
  ext.VERSION_PATCH = 78
```

### VersionCode Formula
```
ABI versionCodes (per-APK for F-Droid):
  armeabi-v7a: MAJOR * 100000 + MINOR * 1000 + PATCH * 10 + 1  (e.g., 101721)
  arm64-v8a:   MAJOR * 100000 + MINOR * 1000 + PATCH * 10 + 2  (e.g., 101722)
  x86_64:      MAJOR * 100000 + MINOR * 1000 + PATCH * 10 + 3  (e.g., 101723)
```

### New Release Workflow
```bash
# 1. Bump VERSION_PATCH in build.gradle
# 2. Add changelogs: fastlane/metadata/android/en-US/changelogs/{versionCode}.txt

# 3. Commit and tag
git add -A && git commit -m "v1.1.XX: description"
git tag v1.1.XX && git push && git push origin v1.1.XX

# 4. Wait for GitHub Actions Release workflow to complete

# 5. Update F-Droid metadata
cd ~/git/fdroiddata-fork
git fetch origin cleverkeys && git reset --hard FETCH_HEAD
# Edit metadata/tribixbite.cleverkeys.yml - add new version build entries
git add . && git commit -m "Update CleverKeys to vX.X.XX" && git push origin cleverkeys

# 6. Monitor F-Droid pipeline
curl -s "https://gitlab.com/api/v4/projects/fdroid%2Ffdroiddata/merge_requests/30449/pipelines" | jq '.[0]'
```

### F-Droid Metadata Location
```
~/git/fdroiddata-fork/metadata/tribixbite.cleverkeys.yml
```

### F-Droid Build Entry Format
```yaml
  - versionName: 1.1.72
    versionCode: 101721  # or 101722, 101723 for other ABIs
    commit: {full-commit-hash}
    gradle:
      - yes
    binary: https://github.com/tribixbite/CleverKeys/releases/download/v%v/CleverKeys-v%v-{abi}.apk
    prebuild: sed -i -e "s/include 'armeabi-v7a'.*/include '{abi}'/" build.gradle
```

### Fastlane Changelogs
```
fastlane/metadata/android/en-US/changelogs/{versionCode}.txt
```
One changelog file per ABI versionCode (101711.txt, 101712.txt, 101713.txt)

### Legacy Code Audit (2025-12-17)
Technical debt identified but not blocking F-Droid submission:

**Activities using legacy base classes:**
- [ ] `DictionaryManagerActivity` → `AppCompatActivity` (should migrate to ComponentActivity)
- [ ] `SwipeCalibrationActivity` → `Activity` (very old base class)
- [ ] `SwipeDebugActivity` → `Activity`
- [ ] `TemplateBrowserActivity` → `Activity`

**Ghost Activities in AndroidManifest (no source files):**
- [x] `tribixbite.cleverkeys.NeuralBrowserActivity` - REMOVED from manifest
- [x] `tribixbite.cleverkeys.neural.NeuralBrowserActivityM3` - REMOVED from manifest
- [x] `tribixbite.cleverkeys.TestActivity` - REMOVED from manifest

**Legacy Themes:**
- [ ] `appTheme` uses `Theme.AppCompat.DayNight.DarkActionBar` (used by settingsTheme)
- [x] Fixed `launcherTheme` to use `Theme.Material3.Dark.NoActionBar` (2025-12-17)
- [x] Fixed `windowDrawsSystemBarBackgrounds=true` for proper edge-to-edge (2025-12-17)

**Deprecated APIs in prefs package:**
- Multiple `android.preference.*` deprecation warnings (ListGroupPreference, LayoutsPreference)
- Should migrate to `androidx.preference.*` eventually

### Versioning Workflow
1. Development: `dev-{sha}` with versionCode 1
2. Release: Tag with `vX.Y.Z` and push
3. GitHub Actions automatically creates release with APKs
4. F-Droid automatically detects new tags and builds

---

## Pending Items

### Web Demo Fixes (P0 - Critical) ✅ COMPLETED
*See full analysis: `docs/specs/web_demo_flaws.md`*

**Architecture Mismatch (Model Input) - FIXED 2025-12-12**:
- [x] Fix velocity calc: use `dx/dt` not just `dx` (time-normalized)
- [x] Fix acceleration calc: use `dv/dt` not just `dv`
- [x] Add value clipping to [-10, 10] range
- [x] Collect timestamps during swipe tracking
- [x] Change MAX_SEQUENCE_LENGTH from 150 to 250
- [x] Update model_config.json max_seq_length to 250

**UI Bugs - FIXED 2025-12-12**:
- [x] Delete empty file `web_demo/niche-word-loader.js` (0 bytes duplicate)
- [x] Fix shift key - now produces uppercase correctly
- [x] Fix number mode - fixed CSS selector pattern
- [x] Gate console.log behind DEBUG flag (global wrapper)

### Web Demo P1 Fixes ✅ COMPLETED
*See full analysis: `docs/specs/web_demo_flaws_v2.md`*

**State Management - FIXED 2025-12-12**:
- [x] handleBackspace state sync (inputText vs currentTypedWord)
- [x] handleSpace commits currentTypedWord properly, prevents double spaces
- [x] handleReturn commits pending typed word before newline

**Mode Toggle Conflicts - FIXED 2025-12-12**:
- [x] toggleNumberMode/toggleEmojiMode mutual exclusion
- [x] resetModeButtons() helper for consistent styling

**Keyboard Layout - FIXED 2025-12-12**:
- [x] Number mode row count (was 10 items, fixed to 9)
- [x] resizeCanvas updates keyboardBounds (orientation changes)

### Custom Dictionary Fixes ✅ COMPLETED (2025-12-13)
- [x] Fix constructor not calling mergeIntoVocabulary on page load
- [x] Fix removeWord not unboosting from vocabulary (added originalFrequencies tracking)
- [x] Allow boosting existing vocabulary words (removed rejection)
- [x] Fix clearAll to properly reset vocabulary state

### Web Demo Improvements (P2) - PARTIALLY COMPLETED
- [x] Add accessibility attributes (aria-*, role, tabindex) - 2025-12-13
- [x] Remove debug test functions from global scope - 2025-12-13
- [x] Improve model loading error handling - 2025-12-13
  - Pre-flight validation with size checks (catches incomplete LFS downloads)
  - Better error categorization (404, incomplete, network, WASM, memory)
  - Retry button in error UI
- [ ] Consider lazy loading for 12.5MB of models
- [ ] Add PWA/Service Worker for offline support

### Settings UI Polish (from settings_audit.md)
- [ ] Add "Swipe Sensitivity" preset (Low/Medium/High) to simplify 5 distance settings
- [ ] Standardize units across distance settings (all pixels or all % of key size)
- [ ] Consider further section merges (14 → 11 sections per audit proposal)
- [ ] Move Vibration setting from Input to Appearance or Accessibility
- [ ] Move Smart Punctuation from Input to Auto-Correction
- [ ] Move Pin Entry Layout from Input to Appearance

### Documentation
- [ ] Update `docs/specs` with any new architectural changes

---

## Verified Working (Dec 2025)

### Import/Export (from Settings -> Backup & Restore)
- Config import/export with proper metadata/preferences structure
- Dictionary import handles both old (user_words array) and new (custom_words object) formats
- Clipboard import with duplicate detection
- **New**: Layout Profile Import/Export (with Custom Gestures)

### Theme Manager (from Settings -> Appearance -> Theme Manager card)
- Theme selection now applies correctly (saves to "theme" preference)
- Gemstone themes: Ruby, Sapphire, Emerald
- Neon themes: Electric Blue, Hot Pink, Lime Green

### Short Swipe Customization
- Full 8-direction customization per key
- Colored direction zones
- Shift key support
- "Select All" and other commands fully functional

---

## Session Notes (Dec 20, 2025)

### Fixed: Spacebar Subkey Gestures Blocked by Swipe Typing
**Commits**: `17b0d301`, `c6c89705`

**Problem**: Horizontal and vertical swipes on spacebar (cursor_left/right, switch_forward/backward) only produced 2-3 actions instead of the expected range (15-88 for cursor, layout switch for vertical).

**Root Cause**: Spacebar's `key0="space"` is `Char` kind, so `shouldCollectPath=true` for swipe typing. This caused an early return in `onTouchMove()` before Slider or Event key activation could occur.

**Fix**: Added pre-swipe-typing check in `Pointers.kt` that detects Slider and Event keys BEFORE swipe typing path collection:
- Slider keys (cursor_left/right): Enter sliding mode immediately
- Event keys (switch_forward/backward): Trigger event immediately

**Layout Switching Note**: `switch_forward`/`switch_backward` require 2+ named layouts configured in Settings → Layouts. The default `SystemLayout` returns null and doesn't count as switchable.

---

*See `docs/history/session_log_dec_2025.md` for completed items from recent sprints.*