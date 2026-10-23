# Dictionary and Multi-Language System

## Overview

The dictionary system manages word lookup, frequency ranking, and multi-language support through a tiered architecture. It combines static dictionaries, user-defined words, and the swipe decoders' lexicons into a "Language Pack" system with automatic language detection for bilingual typing.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/DictionaryManager.kt` | `DictionaryManager` | Per-language dictionary loading/coordination (`OptimizedVocabulary`/`OptimizedVocabularyImpl` were deleted with the neural engine, 2026-08-18 — ADR-011; the swipe engines' lexicons are now `swipe/ctc/CtcLexiconTrie`+`CtcCkdtLexicon`+`CtcLexiconMerge` and `GeometricEngineAdapter`'s merge) |
| `src/main/kotlin/tribixbite/cleverkeys/LanguageDetector.kt` | `LanguageDetector` | Word-based language detection |
| `src/main/kotlin/tribixbite/cleverkeys/WordPredictor.kt` | `WordPredictor` | Unified prediction pipeline |
| `src/main/assets/dictionaries/{lang}_enhanced.bin` | Binary dictionaries | Trie-based word storage |

## Architecture

### Language Pack Structure

An imported ZIP contains `manifest.json`, V2 `dictionary.bin`, optional
`unigrams.txt`, `contractions.json`, `prefix_boost.bin`, `model.onnx` and `NOTICE.txt`.
Installed members live under `files/langpacks/{code}/`. Models are optional;
the shipped registry must also approve their hash before ONNX loading.

### Import and loader bounds (GH #184)

- Maximum canonical dictionary count: **100,000**, read from the binary header,
  never trusted from manifest metadata. Larger packs are refused with a localized
  message; no vocabulary is silently truncated. The bundled English dictionary has
  98,140 entries and published packs normally have 50,000.
- Maximum decompressed dictionary/other member: **16 MiB**. Model: **8 MiB**.
  Manifest and NOTICE: **64 KiB** each. Entire archive: **64 MiB**, at most **64 entries**.
- Extraction streams bounded chunks, rejects traversal and duplicate flattened basenames,
  and accepts benign wrapper folders. Validation happens before the staged install swap;
  preflight and staging-copy failures preserve the existing pack and clean scratch data.
- Install/update swap (`LanguagePackSwap`, todo item 11, 2026-10-12). Under
  `files/langpacks/`: rename `<code>` → `.backup-<code>`, rename the complete
  `.staging-<code>-<ms>` (manifest copied last) → `<code>`, then delete the backup. All
  renames stay in one directory, so each is atomic; there is never a moment with no
  complete copy. A refused staged rename renames the backup straight back (old pack kept,
  `InstallFailed`); if that also fails the backup survives for recovery. The old code
  deleted `<code>` first, so a refused rename, a process death or a partial delete
  (ENOTEMPTY on the rename) lost the installed pack.
- Removal renames `<code>` → `.trash-<code>-<ms>` before deleting, so a partial delete
  never leaves a manifest-only ghost; if the rename is refused it deletes in place.
- Recovery (`LanguagePackSwap.recover`) runs once per `LanguagePackManager` (once per
  process for the singleton) under the install lock, before the first listing, path
  lookup, import or removal. Rules: a backup beside a complete `<code>` is stale and
  deleted; otherwise the backup is restored. Staging directories are deleted, except the
  newest complete one for a code with no install and no backup, which is promoted (an
  interrupted first install, or the state the old swap left). Trash is deleted. Recovery
  is idempotent and safe to interrupt. Dot-prefixed directories are never listed as packs.
- Readers that open `langpacks/<code>/…` directly (`GeometricEngineAdapter`, CTC lexicon
  paths) do not trigger recovery. After a crash mid-swap they see the pack as absent until
  some `LanguagePackManager` query runs; the primary dictionary loads for a pack language
  (`AsyncDictionaryLoader`, `WordPredictor`) query it first.
- Coverage: `LanguagePackSwapTest` (pure) refuses or kills each rename/delete of install,
  removal and recovery, including partial deletes. `LanguagePackImportTest` (mock) covers
  the same cases through the real importer and a real undeletable old-pack subdirectory.
- Tap, geometric and CTC dictionary readers share byte/count limits, so previously
  installed oversized dictionaries also fail loading before count-sized allocation.
  These limits bound inputs; they do not guarantee arbitrary bilingual heap usage.
- TODO: verify refusal, retained old pack and continued typing on the connected Seeker;
  broader malformed-record validation remains separate from the count/byte guard.

### Dictionary Layers

```
┌─────────────────────────────────────────────────────────────┐
│        WordPredictor.predictInternal (inline merge)          │
│  All layers load into ONE dictionary map; candidates are     │
│  scored by calculateUnifiedScore (custom/user words are      │
│  calibrated onto the base scale — UserWordFrequency)         │
└─────────────────────────────────────────────────────────────┘
                            │
       ┌────────────────────┼────────────────────┐
       │                    │                    │
       ▼                    ▼                    ▼
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│   Layer 1    │    │   Layer 2    │    │   Layer 3    │
│ Main Dict    │    │ User Dict    │    │ Custom Dict  │
│ (Read-Only)  │    │ (System)     │    │ (App-Local)  │
│              │    │              │    │              │
│ Language     │    │ Android      │    │ SharedPrefs  │
│ Pack Asset   │    │ UserDictionary│   │ user_dict    │
└──────────────┘    └──────────────┘    └──────────────┘
       │                    │                    │
       └────────────────────┼────────────────────┘
                            │
                            ▼
                   ┌──────────────┐
                   │   Layer 4    │
                   │ Disabled     │
                   │ Words Filter │
                   └──────────────┘
```

**Resolution Logic:**
1. Gather candidates from Layers 1, 2, 3
2. Filter out words in Layer 4 (disabled)
3. Score by frequency, source priority (Custom > User > Main), decoder confidence

## Implementation Details

### Accent Handling

The Latin CTC route projects supported accented spellings onto its a–z alphabet while
preserving canonical words for display. Script-specific CTC encoders use their own
token inventories, and geometric decoding follows supported layout nodes. A blanket
mark-stripping policy is unsuitable for Bangla: vowel signs and conjuncts need explicit
representation. See `docs/guides/adding-a-new-language.md` for the staged Bangla path.

Latin accent lookup:

```kotlin
// Accent mapping: normalized → canonical forms
data class AccentMapping(
    val normalized: String,         // "cafe"
    val canonicalForms: List<String>, // ["café"]
    val frequencies: List<Int>
)

// Lookup flow:
// 1. User swipes "café" → trajectory matches "cafe" pattern
// 2. Prefix lookup: "caf" → finds normalized candidates
// 3. Each candidate maps to its accented canonical form
```

### Binary Dictionary Format (v2)

CKDT uses a **48-byte little-endian header**: magic `CKDT` (uint32),
version 2 (uint32), four-byte language code, canonical word count (uint32),
then canonical/normalized/accent-map section offsets and reserved fields.
Canonical records contain a uint16 UTF-8 byte length, word bytes, and uint8
frequency rank. See `BinaryDictionaryLoader.kt` and `scripts/build_dictionary.py`
for normalized/accent-map records.

**Frequency Ranking:**
- Rank 0 = most frequent word
- Rank 255 = least frequent
- Log-scaled quantization preserves relative ordering

### Language Detection (as built)

There is no unigram-profile model and no `LanguageScore`/`shouldSwitch` (the
`UnigramLanguageDetector` was deleted 2026-08-28, ARC-006). The shipped detector is
`LanguageDetector` (`LanguageDetector.kt`): per-language **character-frequency tables** plus
**common-word arrays** for en, es, fr, pt, de and sv (`initialize*Patterns`), scored by
`detectLanguageFromWordsWithConfidence(words): DetectionResult(language, confidence)`.

Trigger (`WordPredictor.tryAutoLanguageDetection`, `WordPredictor.kt:1074`): after each commit,
once `recentWords.size >= 5` (window capped at `MAX_RECENT_WORDS = 20`), and only when
`auto_detect_language` is on (default `true`) and the layout has **no** language binding
(`Config.layout_bound_language == null`, GH #186/#61). It calls
`MultiLanguageManager.detectAndSwitch(recentWords, language_detection_sensitivity)` (default
threshold 0.6); on a switch the predictor invalidates its exact-learning commit, moves
`bigramModel`/`contextModel` to the new language and clears the window so prior-language
words are not learned under the new one.

### Dual-Dictionary Mode (as built)

Bilingual typing merges the secondary `NormalizedPrefixIndex` inline in
`WordPredictor.predictInternal` (`WordPredictor.kt:~2630-2660`), and again in the typo-tolerant
pass (`~2990-3010`). There is no neural confidence term:

```kotlin
// WordPredictor.kt — real signatures
private fun calculateUnifiedScore(word: String, keySequence: String, frequency: Int, context: List<String>): Int =
    resolveScoreBreakdown(word, keySequence, frequency, context)?.finalScore ?: 0     // :2745
private fun secondaryRankToFrequency(rank: Int): Int = ((255 - rank) * 4000) + 1000   // :3044

// per secondary prefix hit (dedup against primary, disabled words skipped):
val frequency = secondaryRankToFrequency(result.bestFrequencyRank)
val baseScore = calculateUnifiedScore(result.bestCanonical, lowerSequence, frequency, context)
val score = (baseScore * (config?.secondary_prediction_weight ?: Defaults.SECONDARY_PREDICTION_WEIGHT)).toInt()
```

`secondary_prediction_weight` (`pref_secondary_prediction_weight`, default `0.9f`, Config.kt:411)
is the only secondary-specific factor; it is a fixed multiplier, not a detector-driven
"language context" term. A word present in both dictionaries is kept once (the primary
candidate wins; the secondary loop `continue`s on words already present). A contraction alias
key absent from the secondary dictionary is indexed at rank `CONTRACTION_ALIAS_RANK = 254`
(score 5,000) so it is reachable by prefix but never outranks a real word.

### Manual Language Switching (as built)

`MultiLanguageManager.switchLanguage(newLanguage): Boolean` (`MultiLanguageManager.kt:66`) only
validates the code (`isLanguageSupported`) and records `activeLanguage`; it loads **no models**
(there is no ONNX session per language — the swipe encoders are per-script packs owned by the
CTC engine, see `ctc-swipe-engine.md`). Dictionary/n-gram state follows through
`DictionaryManager.setLanguage(code)` (`DictionaryManager.kt:157`), `BigramModel.setLanguage`,
and the `WordPredictor` reload driven by `PredictionCoordinator`. A layout with a `language`
binding (GH #186/#61) sets `Config.layout_bound_language` and wins over both the manual
selection and auto-detection; the Globe/long-press-space keys switch layouts, and the
primary/secondary toggles are the `primaryLangToggle` / `secondaryLangToggle` commands.

### Data Structures (as built)

None of `DictionaryWord`, `WordSource` or `LanguageState` exist. The types in play are:

- `NormalizedPrefixIndex` (`NormalizedPrefixIndex.kt`): accent-normalized key → `bestCanonical`
  + `bestFrequencyRank` (0 = most common … 255); `normalizeKey`, `lookup`, `contains`.
- `WordPredictor.WordCandidate(word, score, fuzzyPrefixScore, fromSecondary)` — the merged
  candidate; `fromSecondary` is the only provenance bit for the secondary dictionary.
- `LanguageDetector.DetectionResult(language, confidence)`.
- `Config.active_secondary_language: String?` (the loaded secondary),
  `Config.layout_bound_language: String?` (per-layout binding), `Config.auto_detect_language`,
  `Config.language_detection_sensitivity`, `Config.secondary_prediction_weight`.

### Key Classes

| Class | Purpose |
|-------|---------|
| `DictionaryManager` | Singleton holding active WordPredictor instances, handles language lifecycle |
| `MultiLanguageManager` | Tracks the active language code, validates switches (`switchLanguage`), runs `detectAndSwitch` over `LanguageDetector`; owns no ONNX sessions |
| `WordPredictor` (inline) | Merges primary + secondary candidates in `predictInternal` via `calculateUnifiedScore` × `secondary_prediction_weight` (the standalone `SuggestionRanker` had zero production callers post-ADR-011 and was deleted 2026-09-06 — audit C-6) |
| `LanguageDetector` | Word/character-pattern language detection (the unigram-frequency `UnigramLanguageDetector` was deleted 2026-08-28 — ARC-006, write-only after `OptimizedVocabulary` went) |
| `AccentNormalizer` | Unicode normalization (NFD) + accent stripping |

### Dictionary lifecycle and memory

PredictionCoordinator owns the serving WordPredictor. DictionaryManager does not
cache additional predictors. The predictor has a primary dictionary/prefix index
and an optional secondary NormalizedPrefixIndex. A vocabulary bound limits each
index, but each remains substantial; see the measured EN+IT stages in
[`2026-09-10-memory-oom-root-cause.md`](../audit/2026-09-10-memory-oom-root-cause.md).

AsyncDictionaryLoader uses one process-wide worker and two replaceable request
slots per owner, primary and secondary. Replacing or cancelling a request removes
its queued task and main-handler callbacks. Token checks also reject callbacks
already dequeued before cancellation. Secondary disable invalidates its slot
without cancelling primary loading. A synchronous primary reload invalidates the
pending asynchronous primary request before replacing its maps.

Primary dictionary, prefix index, casing, custom-word, shadowed-frequency and
contraction metadata build privately. The current request publishes them on main;
obsolete work cannot modify the serving language's metadata. Secondary indexes
also build privately and publish on main. Binary and overlay loops cooperate
with interruption, and expected cancellation does not trigger fallback loading.
Individual I/O/native calls are not guaranteed to stop immediately.

PredictionCoordinator.shutdown checkpoints learning, then calls the predictor's
terminal shutdown. This cancels both load slots, stops dictionary observation,
and replaces large dictionary references with empty state. It never shuts down
the shared executor or clears a worker's privately owned maps. Production lifecycle
and publication calls run on the main thread. Later load/observation requests on
the retired predictor are ignored.

Swipe dictionaries are separately owned by their adapters, with bounded,
content-versioned language memos. InputCoordinator detaches those adapters during
teardown. CtcEngineAdapter closes its ONNX sessions only after the worker has
terminated; its existing 250 ms timeout avoids closing a session beneath native
inference. See [`ctc-swipe-engine.md`](ctc-swipe-engine.md) for that contract.

## Per-layout language binding (GH #186, GH #61 — 2026-10-07)

### Problem

Languages were global: two primaries and two secondaries reached through
`primaryLangToggle`/`secondaryLangToggle`. Switching layouts (for example a Latin board
and a Persian board) never changed the language, and `WordPredictor.autoCorrect` consults
only the PRIMARY dictionary, so typos on the second layout were never corrected (#186).
Users of three or more languages, each on its own layout, had no way to keep words from
mixing (#61).

### Design

**Binding storage — per layout entry.** Each entry of the `layouts` preference may carry a
`language` field. Unbound entries serialize exactly as before (a bare string for a named
layout, `{"kind":"custom","xml":…}` for a custom one, `{"kind":"system"}` for System), so
an existing user's preference is byte-identical until they choose a binding. A bound named
layout becomes `{"kind":"named","name":…,"language":…}`. Keeping the binding inside the
entry means reordering, editing the XML (rename) and deleting a layout carry or remove its
binding with no separate cleanup step, and Backup & Restore exports/imports it inside the
existing `layouts` JSON blob (no new preference key).

**Entry values.** `null` = no user choice (use the XML default, see below); a code = bound;
`"none"` = explicitly unbound (overrides an XML default). Every value is normalised by
`LayoutLanguageBinding.normalizeEntry`: lower-cased, trimmed and checked against the same
code shape language packs must have (`^[a-z]{2,3}(?:[_-][a-z0-9]{1,16}){0,4}$`). An
invalid value read from a backup or older build is dropped (treated as `null`).

**XML default.** A custom layout may declare `<keyboard language="fa" …>`. The parser keeps
the raw value (`KeyboardData.declared_language`); loading stays lenient so an invalid
value never makes a stored layout disappear, while the layout editor refuses to save an
invalid value with a localized message. The user's per-entry choice overrides it.
Built-in layouts declare no language, so the default for everyone is "no binding".

**Resolution — `LayoutLanguageBinding.resolve`.** Config computes, on every refresh and on
every `set_current_layout`, the ACTIVE languages from the current layout index (same
out-of-range clamp as `LayoutManager.current_layout_unmodified`):

| Current layout | Active primary | Active secondary |
|---|---|---|
| unbound | `pref_primary_language` | `pref_secondary_language` when Multi-Language is on (unchanged behaviour) |
| bound to X | X | none — single-language mode |

`Config.primary_language` is the ACTIVE primary; `Config.active_secondary_language` the
ACTIVE secondary; `Config.layout_bound_language` is non-null while a bound layout is
active. The user's own choices stay in the preferences and are what Settings shows; a
binding never rewrites them, so leaving a bound layout returns to them exactly.

**Secondary while bound.** A bound layout is single-language (#61: "no mixing"). Making
this a per-binding choice was considered and rejected: it would need a second control per
layout and a third state for the secondary slot, and the users who asked want separation.
A user who wants bilingual predictions on one board leaves that board unbound.

**One language-change path — `ActiveLanguageSync`.** Every language change — a Settings
selector, a `primaryLangToggle`/`secondaryLangToggle` swap, a layout switch
(`switch_forward`/`switch_backward`, the layout picker, a subtype change re-pointing the
selection), rotation between the portrait/landscape selections, editing the layout list or
a backup import — ends in a Config refresh, and `CleverKeysService.onConfigChanged` hands
`Config.activeLanguages()` to the single `ActiveLanguageSync` built by
`KeyboardComponentGraph`. It diffs against what is loaded and, only for what changed:
reloads the primary dictionary (`PredictionCoordinator.reloadWordPredictorDictionary`,
which also moves `DictionaryManager`'s language, the n-gram/static LM, the context model
and learning), loads or unloads the secondary dictionary, reloads contractions through
`ContractionManager.loadTypingMappings`, and then re-warms the serving swipe engine
(ARC-014; after the reload so the prewarm reads the new language). The previous key-based
reload in `PreferenceUIUpdateHandler` was removed so there is exactly one implementation.
Swipe routing (CTC vs geometric per language) and CTC's secondary trie read the same
active values (`InputCoordinator`), so they follow the binding too. Startup needs no
special case: Config resolves the active languages before the predictor loads.

**Language toggles while bound.** On a bound layout `primaryLangToggle` and
`secondaryLangToggle` change nothing: they leave the preferences alone and the suggestion
bar says "This layout sets the language: X" instead of announcing a language that would
not be active. On unbound layouts they work as before. Auto language detection is
suspended while a bound layout is active.

**Missing language pack.** Binding to a language with neither a bundled dictionary nor an
installed pack is allowed (the user may import the pack later). Layout Manager shows a
warning on that layout, and switching to it shows "Language: X (no dictionary installed)"
in the suggestion bar. The layout keeps working for typing; predictions and autocorrect
have no vocabulary until the pack is imported.

**Availability scan (2026-10-08).** "Has a dictionary" is answered by
`LanguageAvailability` (bundled `assets/dictionaries/<code>_enhanced.bin` plus installed
packs). It caches one scan per process — Layout Manager's picker and the keyboard's
layout-switch message both ran it on the main thread — and `LanguagePackManager`
invalidates it after every import attempt and every delete (Settings and the keyboard share
the process). A failed scan is not cached and still reports "available" to `isAvailable`.

**Settings note.** Multi-Language always explains that a layout's own language replaces
these settings while that layout is active; when any layout is bound (the user's choice or
an XML default), a second line names the bound languages ("Layouts with their own language:
Persian, Russian"), refreshed on every Settings resume because Layout Manager edits land
while Settings is paused.

**3+ languages.** Bind each layout; cycling with `switch_forward` walks the languages in
layout order. There is no limit on the number of bound layouts.

**Downgrade note.** A build without this feature reads a bound NAMED entry
(`{"kind":"named"}`) as the System layout. Unbound entries are unaffected.

### Tests

- `LayoutLanguageBindingTest` (pure): code validation, entry precedence, resolution table,
  out-of-range clamp, three-language cycle.
- `ActiveLanguageSyncTest` (pure): only changed languages reload; unbound switches reload
  nothing; contraction reload and swipe re-warm order.
- `LayoutLanguageBindingConfigTest` (mock): `Config.set_current_layout` to a layout bound
  to fa makes fa the active primary (prediction/autocorrect language) and drops the
  secondary; unbound keeps the preference languages; serializer round trip of bindings
  and rejection of invalid codes from a backup; the XML `language` attribute default.
- `LanguageAvailabilityCacheTest` (mock): one scan for repeated queries; delete and any
  import attempt invalidate; manifest-only packs are listed but not available; a failed
  scan is not cached.
- `LearningFunnelBookkeepingTest#autoDetectionIsSkippedWhileTheLayoutBindsALanguage`
  (mock): detection never runs on a bound layout and runs again once unbound.
- `LayoutManagerLanguageBindingComposeTest` (androidTest): the Language chip, the
  "not installed" warning on the row and in the picker, and unbinding via "Follow
  Multi-Language settings".

## Persian half-space and Arabic-script lookup fold (2026-10-23)

Maintainer decisions (b), (c), (d) of `docs/research/2026-10-21-persian-zwnj.md` §7.

### Word characters — `WordChars`

U+200C (ZWNJ, the Persian half-space) is a word character **only** when the nearest base
characters on both sides (combining marks skipped) are Arabic-script letters
(`Character.isLetter` and `UnicodeScript.ARABIC`). Cursor edge: a half-space as the last
character before the cursor, after an Arabic-script letter, belongs to the word when the first
character after the cursor is an Arabic-script letter, a non-letter/digit, or absent (`می‌|`).
Everything else — Latin/Cyrillic/Greek/Hebrew/Indic/Sinhala ZWNJ, edges, digits, Arabic next
to Latin — is a boundary. U+200D is not covered. Call sites (each keeps its own base rule and
ORs the half-space clause in): `TextActionPolicy.wordAtCursor`, `PredictionContextTracker`
prefix/suffix extraction, `EditorTextNavigation.isWordAt/isWordBefore`,
`VerifiedSuffixEdit.isWordContinuationAt`, `NextWordPredictor.contextFromEditorText`,
`SuggestionHandler` (typing branch, `trimJoiners`, whole-token check, #78 editor scan),
`KeyEventHandler` double-space check, `EmojiSearchManager.extractWordBeforeCursor`,
`SwipeCorrectionTracker.editorContinuesAnchor`. Reviewed and left alone:
`InlineSwipeInput.insertion` (visual fusion only) and the owed-space repair in
`KeyEventHandler` (a half-space never starts a word).

**One deliberate behaviour change outside Persian:** `PredictionContextTracker` used to keep
U+200C in the composing word in EVERY script (it fell through to "word char"); it now follows
the shared rule like the other sites, so a Latin or Sinhala ZWNJ ends the cursor-synced word,
as it already did for typing, selection, navigation and the tokeniser.

Typing: U+200C typed after an Arabic-script letter appends to the composing word (no reset,
unlike the apostrophe/hyphen W7 path), so the bar completes `می‌ش` → `می‌شود`. A dangling
half-space is trimmed from the learned token (`می‌` + space learns `می`).

Swipe: `CtcScriptProjection` and `UserJoinerPreference` strip a joining half-space (the trace
`میشود` reaches `می‌شود`, displayed in its dictionary form); a non-joining U+200C still makes a
word untypeable / non-joiner. `CtcAzProjection` is a–z only and needs nothing. The geometric
`LayoutProjection` already ignored U+200C.

### Autocorrect and the offer — `HalfSpaceVariants`

Autocorrect never adds, removes or moves a half-space (`WordChars.crossesHalfSpace`, applied in
`WordPredictor.gatedCandidates`, so both rankings and the bar highlight share it); a word with
an edge half-space is never corrected. A correction that keeps the half-space count (a typo fix
inside a half-space word) still applies. Instead `predictInternal` offers the other spellings as
whole-word candidates (direct-match prefix score): typed `میشود` → `می‌شود`, typed `می‌شود` →
`میشود` when it is a dictionary word, `نامهای` → both `نامه‌ای` and `نام‌های`. Space keeps the
typed word, so the offer is never highlighted. The index is built from the primary dictionary's
keys and cached per map instance and size. `UserJoinerPreference` was considered for recording
the user's choice and does not fit the tap path (it is a swipe display rule built from user
words); a chosen spelling is learned by the ordinary learn funnel. Not offered for the secondary
dictionary, and not for prefixes (# TODO in `WordPredictor.addHalfSpaceVariants`).

### Lookup fold — `ArabicScriptFold`

Lookup keys only, never the user's text, one code unit for one code unit:

| Language | Fold |
|---|---|
| `fa` | ي U+064A → ی U+06CC, ك U+0643 → ک U+06A9, ى U+0649 → ی U+06CC, ۀ U+06C0 → ه U+0647, ة U+0629 → ه U+0647, ە U+06D5 → ه U+0647 |
| `ckb` | ي → ی, ك → ک only (ە and ه are distinct Sorani letters) |
| anything else (ar, ur, Latin, …) | none |

Never folded: آ and the hamza carriers ء أ إ ؤ ئ. Scoping follows the language of the dictionary
being loaded or queried: `WordPredictor.lookupKey` uses `currentLanguage` (the active primary,
`Config.primary_language`, per-layout bindings included), the load paths re-key the loaded map
and prefix index with the loading language (`foldDictionaryKeys`, max frequency on a merge),
and `NormalizedPrefixIndex(language)` folds before accent stripping for the secondary
dictionary. A typed word whose folded key is a dictionary word is returned AS TYPED by
autocorrect. `PredictionContextTracker.normalizeForPrediction` no longer runs NFD over
Arabic-script characters (NFD turned ۀ into Kurdish ە + hamza and stripped hamza/madda from
أ ؤ ئ آ); it applies the fold and drops harakat. The `fa` pack builder applies the same `fa`
table (the shipped `langpack-fa.zip` has 0 of the six variant letters).
