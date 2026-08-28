# Feature Specification: Context Learning, Privacy Gate & Next-Word Prediction

## Feature Overview
**Feature Name**: Persistent Context LM + On-Device Learning Privacy Gate + Next-Word Prediction + Pipeline Transparency + Learned-Data Manager
**Priority**: P1
**Status**: Complete (commits `997d8f78`, `295edc43`, `f6824477`, 2026-08-06)
**Target Version**: post-v1.5.x

### Summary
One coordinated feature wave that (1) makes the learned context language model persistent
across restarts, (2) puts ALL typing-behavior learning behind a single master privacy gate,
(3) adds opt-in Gboard-style next-word prediction from the learned n-gram store, (4) makes
every suggestion's origin and score inspectable (provenance), and (5) gives users a
browse/delete manager over everything the keyboard has learned.

### Motivation
The pre-existing bigram LM learned in-RAM only and forgot everything on IME restart; the
`UserAdaptationManager` selection store had NO preference gate at all; and users had no way
to see, control, or delete what the keyboard learned. Independent review findings are in
`docs/history/audits/2026-08-06-context-lm-review-findings.md` (H1–H3, M1–M7, L1–L10 — all resolved
in `f6824477`).

---

## 1. Master Privacy Gate (`LearningGate`)

**File**: `src/main/kotlin/tribixbite/cleverkeys/LearningGate.kt` (pure JVM object)

### Contract
The `on_device_learning_enabled` preference (**default ON — this is an opt-OUT**) is the
single source of truth for "may this typing-derived signal be recorded right now?". When
OFF, every learn path is short-circuited **at the write layer**, and the read paths that
surface previously learned data go dark too — the learned stores become fully inert
(neither written nor read), not merely frozen.

| Path | Store | Gate function |
|------|-------|---------------|
| Context LM (bigrams + trigrams) | `BigramStore` / `TrigramStore` | `learnCommittedWord` → `canLearnContext(master, contextAwareEnabled)` |
| Personalization vocabulary | `UserVocabulary` | `learnCommittedWord` → `canLearnPersonalization(master, personalizedLearningEnabled)` (plus `PersonalizationEngine.setEnabled` sync in `WordPredictor.setConfig`) |
| Selection adaptation | `UserAdaptationManager` prefs | `canLearnAdaptation(master)` — call site `SuggestionHandler.onSuggestionSelected`. *Pre-existing privacy gap: this store previously had no preference gate at all.* |
| Swipe-ML traces | `SwipeMLDataStore` | `canCollectSwipeMl(master, collectSwipeEnabled)` — via `PrivacyManager.canCollectSwipeData`, checked by `MLDataCollector` |

READ gates: `canUseLearnedContext(master, contextAwareEnabled)` (dynamic context boost +
next-word candidate source) and `canUseAdaptation(master)` (adaptation re-rank multiplier +
add-to-dictionary prompt suppression; review H3). Personalization boost returns 0 once the
engine is disabled.

### The learn funnel
`LearningGate.learnCommittedWord(...)` is THE funnel for a committed word (production
caller: `WordPredictor.addWordToContext`). With the master off — or the active field
forbidding personalized learning — **neither** sink lambda is invoked, so no in-RAM state
mutates and nothing can be persisted.

- Context window: `CONTEXT_WINDOW = 4` trailing words (trigram-ready).
- The context sink (`ContextModel.recordCommit`) records ONLY the newest bigram/trigram
  ending at the committed word (review M3 — the previous full-window replay re-recorded
  earlier pairs on every commit, inflating frequencies past the "seen ≥2×" floor).

### Incognito fields (review M5)
`LearningGate.IME_FLAG_NO_PERSONALIZED_LEARNING = 0x1000000` mirrors the platform constant
(pinned by `LearningGateTest` against `EditorInfo`). An editor that sets this flag (e.g. a
browser private tab) suppresses BOTH learn paths regardless of user preferences, and also
suppresses next-word display (`fieldAllowsPersonalizedLearning` parameter throughout).

### Deliberate out-of-scope (review L7)
The master gate covers AUTOMATIC recording of typing behavior. Data the user explicitly
creates is governed by its own controls:
- ~~`SwipeCalibrationActivity` traces~~ — that activity was deleted with the neural engine (2026-08-18, ADR-011). Swipe ML trace collection (`privacy_collect_swipe` → `ml/SwipeMLDataStore`) is no longer an exception: since Task A (2026-08-06) it is ANDed with the master gate at the write layer (`LearningGate.canCollectSwipeMl`, enforced in `PrivacyManager.canCollectSwipeData`).
- `SwipePerformanceStats` — behind the separate performance-stats preference (no text content).
- Backup restore — importing a backup repopulates learned stores even with the master off
  (restoring one's own exported data is an explicit act).

### UI
Settings → **🔒 Privacy & Data → On-Device Learning → "Learn From My Typing"**
(`PrivacySection.kt`). Turning it OFF opens a one-tap **"Also forget learned data?"**
dialog that (on confirm, off-main-thread) runs `BigramStore.clearAll()`,
`TrigramStore.clearAll()`, `UserVocabulary.clearAll()`,
`UserAdaptationManager.resetAdaptation()`.

---

## 2. Persistent Context LM

**Files**: `contextaware/BigramStore.kt`, `contextaware/TrigramStore.kt`,
`contextaware/ContextModel.kt`, `persist/DebouncedPersister.kt`,
`persist/LearnedDataStorage.kt`, `persist/SharedPrefsLearnedStorage.kt`

### Before → After
The learned bigram LM previously lived per-`WordPredictor` instance in RAM and evaporated
on service restart (and multiple instances clobbered each other's view). Now:

- **Process-wide singletons** — `BigramStore.getInstance(context)` /
  `TrigramStore.getInstance(context)`: one writer, no clobber.
- **Language-keyed persistence** — each language persists under its own key
  (`bigrams_json_<lang>` in the `bigram_store` SharedPreferences file;
  `trigrams_json_<lang>` in `trigram_store`). Language codes normalized
  (`"" → "en"`, case-insensitive). A legacy un-keyed `bigrams_json` blob is migrated into
  the first language that loads, then deleted.
- **Debounced write-back** — `recordBigram` mutates RAM and marks the store dirty; a
  `DebouncedPersister` (default 5 s debounce, 30 s max delay, shared scheduler) coalesces
  writes so there is never a write per keystroke. Lifecycle call sites
  (`CleverKeysService` / `PredictionCoordinator.shutdown`) checkpoint via
  `flush()` / `requestFlush()`.
- **UserVocabulary save-storm fixed** — `personalization/UserVocabulary.kt` now rides the
  same `DebouncedPersister` discipline instead of eager per-mutation saves.

### Storage limits / capacity

| Store | Per-context cap | Overall cap | Eviction / floor |
|-------|-----------------|-------------|------------------|
| `BigramStore` | `MAX_BIGRAMS_PER_WORD = 20` (per previous word) | `MAX_TOTAL_BIGRAMS = 10000` per language | lowest-frequency trimmed; `DEFAULT_MIN_FREQUENCY = 2` to surface |
| `TrigramStore` | `MAX_TRIGRAMS_PER_PREFIX = 10` (per two-word prefix) | `MAX_TOTAL_TRIGRAMS = 10000` per language | lowest-frequency trimmed; `DEFAULT_MIN_FREQUENCY = 2` to surface |
| `UserVocabulary` (personalization) | — | **user-configurable** via `personalization_max_words` (default `Defaults.PERSONALIZATION_MAX_WORDS = 5000`, slider 1000–20000, floor `MIN_VOCABULARY_CAP = 100`) | rolling least-value eviction (lowest `getPersonalizationBoost` first) on add; `enforceCap()` trims down on load and when the user lowers the cap; stale words (>90 days, or single-use >30 days) cleaned daily |
| `UserAdaptationManager` / `SelectionHistory` | — | `MAX_TRACKED_WORDS = SelectionHistory.DEFAULT_MAX_TRACKED_WORDS = 1000` selection-count entries | over cap, least-selected words pruned down to 80% of capacity (`PRUNE_KEEP_FRACTION = 0.8`) |

The `UserVocabulary` cap is threaded in as a dynamic provider
(`maxWords: () -> Int` reading `Config.personalization_max_words`), so the
process-wide singleton picks up preference changes without reconstruction —
covered by `UserVocabularyCapTest` (pure JVM).

### Trigram → bigram backoff
`ContextModel.getNextWordCandidates(previousWords, maxResults=10)`: when a `TrigramStore`
is wired and ≥2 previous words exist, trigram predictions for the (w1, w2) prefix come
first (`fromTrigram = true`), then bigram predictions for the last word fill remaining
slots, deduped. `ContextContinuation(word, frequency, probability, fromTrigram)` is the
carrier type. TrigramStore has no learn decision of its own — its ONLY production write
path is `ContextModel.recordSequence`, reached exclusively through the gated funnel.

### Sentence boundaries (audit §4.6)
After `.` `?` `!`, `SuggestionHandler` calls `WordPredictor.onSentenceBoundary()` so
`recordSequence` never learns n-grams spanning a sentence boundary.

---

## 3. Next-Word Prediction (opt-in, default OFF)

**Files**: `NextWordPredictor.kt` (pure JVM gating + generation),
`SuggestionHandler.kt` (impure wiring: `maybeShowNextWordPredictions`,
`appendNextWordToSwipeAlternates`, `generateNextWordCandidates`, `handleCursorParkPrediction`)

### Gating (`NextWordPredictor.shouldShow`)
ALL of: `next_word_prediction_enabled` (feature pref, default OFF) ∧
`on_device_learning_enabled` (master — next-word reads the learned store, so it goes dark
with the master off) ∧ field allows personalized learning (M5 incognito) ∧
`word_prediction_enabled` ∧ not password mode ∧ no special prompt active
(autocorrect-undo / add-to-dictionary) ∧ not a Termux field ∧ non-empty committed context.

### Candidate generation (`NextWordPredictor.generate`)
Input: probability-ranked `ContextModel.getNextWordCandidates` (trigram-preferred with
bigram backoff), max 10. Filters:

1. **Confidence floor**: learned frequency ≥ `MIN_LEARNED_FREQUENCY = 2` AND conditional
   probability ≥ `MIN_LEARNED_PROBABILITY = 0.05` — an EMPTY next-word bar is the designed
   common case; show nothing rather than noise.
2. **Self-repetition**: drop the just-committed word.
3. **`isWordAllowed`**: must be in dictionary or user vocabulary AND not disabled in
   Dictionary Manager (blocks typo'd garbage the n-gram stores may have absorbed).
4. Dedup (first occurrence wins).

Ranking score = `probability × (1 + personalizationBoost/4) × 1000` (same personalization
conversion as `WordPredictor.calculateUnifiedScore`); personalization can reorder within
the surfaced set. Caps: `MAX_SUGGESTIONS = 3` (whole-bar), `MAX_SWIPE_APPEND = 2`
(appended after swipe alternates).

### The four call-sites (audit §4.4)

| # | Trigger | Behavior |
|---|---------|----------|
| 1 | Typed word completed with a **space** (`SuggestionHandler` single-char commit path, `text == " "` only) | Bar would otherwise clear → show up to 3 context-only candidates. After sentence-final punctuation the context was just cleared, so nothing shows. |
| 2 | **Manual tap** on a suggestion (`onSuggestionSelected`, `isManualSelection` only — review H2) | Context just grew → chain another round ("want" → tap "to" → suggests "go/see/be"). The swipe AUTO-insert must NOT route here (it would replace the alternates bar and break swipe correction) — it composes via call-site 3 instead. |
| 3 | **Swipe auto-insert** results displayed (`appendNextWordToSwipeAlternates`) | KEEP the swipe alternates (user may still correct the swipe) and APPEND ≤2 next-word candidates after them, tagged with per-suggestion `NEXT_WORD` metas so a tap APPENDS the word instead of replacing the auto-inserted swipe word. Runs on the shared `predictionTasks` executor (review L3 — first lookup lazily loads persisted n-gram blobs; inline it caused first-swipe jank). |
| 4 | **Cursor parked** after existing text with no partial word under it (`handleCursorParkPrediction`, routed from InputCoordinator's empty-prefix cursor-sync branch) | Gboard-style tap-into-text predictions. SCOPE (review L5, accepted): candidates derive from the SESSION's committed-word context, not the editor text before the cursor — parking into text typed in an earlier session usually shows nothing (safe, not Gboard-complete; editor-scan deferred). |

### Staleness + dismissal
- Bar-generation guard (review M6): the async post aborts if `SuggestionBar.contentGeneration()`
  changed between submit and post (user typed, new swipe, prompt appeared).
- `nextWordSuggestionsActive` state: any selection consumes it; **backspace with no partial
  word dismisses** the candidates (the only new state next-word introduces); typing a letter
  replaces them with normal prefix predictions; multi-char input (paste) clears.
- Display: stored-lowercase words are restored via I-word capitalization +
  user-dictionary proper-noun case (`applyUserWordCaseToList`).

### Next-word UX walkthrough (what the user actually sees)

Preconditions: Settings → ⌨️ Input Behavior → Word Prediction → **Next-Word Prediction ON**;
🔒 Privacy & Data → **Learn From My Typing ON** (default); the phrases involved have been
typed at least twice before (floor: seen ≥2×, ≥5% conditional probability).

**Tap-typing "I want to go home":**
1. Type `I` + space → commit. If the LM has learned continuations of "i" (e.g. "want" seen
   14×/63%), the bar — which used to go empty here — shows up to 3 of them:
   `want  am  think` (call-site 1). If nothing clears the floor, the bar simply stays
   empty — that is normal and common early on.
2. Tap `want` in the bar → "want " commits, and the bar immediately re-fills from the new
   context: `to  a  more` (call-site 2, chaining). You can compose whole learned phrases
   by tapping without touching letter keys.
3. Type `t` → next-word candidates vanish, replaced by ordinary prefix predictions for "t".
4. Press backspace while no partial word exists → next-word candidates dismiss and the bar
   clears (they do not re-appear until the next commit).
5. Sentence end: type `.` → context window resets; space after it shows nothing.

**Swipe-typing:**
1. Swipe "want" → the word auto-inserts and the bar shows the swipe ALTERNATES
   (e.g. `want  went  wart`) so a mis-recognized swipe can be corrected by tapping an
   alternate (which REPLACES the auto-inserted word).
2. A beat later, up to 2 learned next-words are APPENDED after the alternates:
   `want  went  wart  |  to  more` (call-site 3). Tapping `to` APPENDS "to" after "want"
   (it does not replace "want"); tapping `went` still replaces the swipe word. The two
   behaviors coexist in one bar, disambiguated by per-suggestion provenance metas.
3. After tapping an appended next-word, chaining continues as in tap flow step 2.

**Tap into existing text** (cursor parks at the end of a sentence, no partial word):
call-site 4 may surface continuations of the current session's last committed words;
with earlier-session text it usually shows nothing (documented L5 scope).

**Transparency during all of this:** long-press any next-word candidate → provenance sheet
shows `Source: Next-word prediction (learned)` plus the learned statistics, e.g.
`After "want to": seen 14×, 63%` (trigram context shows the last two words; bigram shows
one). With **Suggestion Origin Markers** enabled (Advanced), next-word entries carry a
distinct colored dot distinguishing them from swipe alternates in mixed bars.

**When next-word will NOT appear:** feature pref off (default); master learning gate off;
password fields; incognito fields (`IME_FLAG_NO_PERSONALIZED_LEARNING`); Termux; while an
autocorrect-undo or add-to-dictionary prompt is showing; empty session context; nothing
learned above the floor; word prediction disabled.

---

## 4. Pipeline Transparency (`SuggestionProvenance`)

**Files**: `SuggestionProvenance.kt` (pure JVM), `SuggestionBar.kt` (display),
`WordPredictor.kt` (breakdown production)

- **`SuggestionOrigin`** enum tags every bar entry at creation: `GEOMETRIC`, `CTC`,
  `DICTIONARY_PREFIX`, `CONTRACTION`, `POSSESSIVE`, `EXACT_ADD`, `NEXT_WORD`, `AUTOCORRECT`.
  (`NEURAL_BEAM` was deleted with the transformer engine on 2026-08-18 — ADR-011.)
- **`UnifiedScore.combine(...)`** is now THE single implementation of the unified score
  formula — `WordPredictor.calculateUnifiedScore` resolves raw signals and delegates here,
  so the hot-path score and the displayed breakdown can never drift. Formula:
  `prefixScore × adaptation × personalizationMult × (1 + (contextMult−1)×contextBoost) × freqFactor`
  with `contextMult` chosen per `context_source` (both → max(static, learned)),
  `personalizationMult = 1 + boost×weight/4`, `freqFactor = 1 + ln1p(freq/frequencyScale)`.
- **`ScoreBreakdown`** carries every component + `ContextWinner` (STATIC/LEARNED/NONE — which
  context model actually supplied the applied signal).
- **`SuggestionMeta(origin, breakdown?, note?)`** rides alongside the bar's parallel
  words/scores lists; breakdown is non-null only for the dictionary-prefix path (decoder
  confidence / learned-LM probability / injections are scored elsewhere).
- **Long-press** any suggestion → provenance popup (`ProvenanceFormatter.format`): origin
  label, bar score, origin-specific note, full score-component list, and
  `PersonalizationEngine.explainBoost()` text.
- **Origin markers** (opt-in, `suggestion_provenance_markers`, default OFF, Advanced
  section): colored dot per suggestion keyed by origin. Long-press inspection is always
  available regardless.

## 5. Learned-Data Manager

**File**: `ui/settings/sections/LearningDataSection.kt` — rendered inside the Advanced
Prediction block of `InputBehaviorSection` ("Learning & Data").

- **Counts**: per-language bigram pairs + trigram triples ("en: 412 pairs, 96 triples"),
  vocabulary word count + most-used word.
- **Browse phrases**: all learned bigrams across languages, most frequent first (cap 200),
  per-entry delete via `BigramStore.removeBigram`. Trigrams are NOT individually browsable
  — bulk-cleared by "Forget phrases" (documented scope).
- **Browse words**: top personalization-vocabulary words with usage counts, per-entry
  delete via `UserVocabulary.removeWord`.
- **Max Learned Words slider**: sets `personalization_max_words` (default 5000,
  1000–20000 in 500-word steps) — the cap on the personalization vocabulary. Lowering
  it below the current word count evicts least-valuable words down to the new cap
  (debounced, off the main thread, via `UserVocabulary.enforceCap`).
- **Forget phrases / Forget words**: count-bearing confirm dialogs, off-main-thread clears.
- **Backup**: learned n-grams + vocabulary ride the standard Backup & Restore dictionary
  payload (`learned_bigrams_by_language` / `user_vocabulary` keys in
  `BackupRestoreManager`).

## 6. Supporting changes (review fixes, `f6824477`)

- `SelectionHistory.kt` extracted from `UserAdaptationManager` (selection-count store,
  read-gated per H3).
- `LearningWiringDriftTest` — source-scanning drift test forbidding ungated learn-path
  regrowth; `ContextLearningBoundaryTest`, `LearnedStoreForgetRaceTest` (clear-vs-write
  races), `SelectionHistoryTest`.
- Swipe regression (H2) fixed: auto-insert no longer replaces the alternates bar.

---

## Configuration

| Setting | Key | Default | Range/Values | UI location |
|---------|-----|---------|--------------|-------------|
| Learn From My Typing (master gate) | `on_device_learning_enabled` | `true` | bool | 🔒 Privacy & Data → On-Device Learning |
| Next-Word Prediction | `next_word_prediction_enabled` | `false` | bool | ⌨️ Input Behavior → Word Prediction |
| Context Source | `context_source` | `"both"` | `both` \| `learned_only` \| `static_only` | ⌨️ Input Behavior → Word Prediction |
| Personalization Strength | `personalization_weight` | `1.0` | 0.0–2.0 (0 = off, 2 = double) | ⌨️ Input Behavior → Word Prediction |
| Max Learned Words | `personalization_max_words` | `5000` | 1000–20000 (500-word steps) | ⌨️ Input Behavior → Learning & Data |
| Suggestion Origin Markers | `suggestion_provenance_markers` | `false` | bool | 🔧 Advanced |

All six are registered in `Config.kt` and classified in
`backup/SettingsDefaults.kt` (`SETTINGS_DEFAULTS`), so they diff correctly in
Backup & Restore import previews.

Existing related prefs (unchanged keys, now composed with the master gate):
`context_aware_predictions_enabled`, `personalized_learning_enabled` (per-feature gates),
`privacy_collect_swipe` (swipe-ML), `prediction_context_boost`, `prediction_frequency_scale`.

## Test Coverage (pure JVM)

| Suite | Focus |
|-------|-------|
| `LearningGateTest` | Gate matrix, IME flag value pinned against platform |
| `OnDeviceLearningPrivacyTest` | Funnel wired to real stores over in-memory storage — asserts nothing recorded/persisted with master off |
| `NextWordPredictorTest` | Gating matrix, floors, self-repetition, dedup, personalization reorder |
| `SuggestionProvenanceTest` | UnifiedScore combine + breakdown + formatter |
| `BigramStorePersistenceTest`, `TrigramStorePersistenceTest`, `UserVocabularyPersistenceTest` | Language keying, legacy migration, debounced write-back |
| `UserVocabularyCapTest` | Configurable `personalization_max_words` cap: default, live provider changes, least-value eviction at capacity, lower-cap trim (enforceCap/on-load/import), floor clamp |
| `DebouncedPersisterTest` | Debounce/max-delay/flush semantics |
| `ContextModelTrigramTest`, `ContextModelLanguageTest` | Backoff order, language isolation |
| `ContextLearningBoundaryTest`, `SelectionHistoryTest`, `LearnedStoreForgetRaceTest`, `LearningWiringDriftTest` | Review-fix regression coverage |

## Deferred / known limitations

- Cursor-park next-word reads session context only, not editor text (review L5) — an
  InputConnection editor scan per park is deferred until the (default-OFF) feature earns it.
- Trigrams not individually browsable in the Learned-Data manager (bulk clear only).
- Backup restore repopulates learned stores even with the master gate off (documented
  out-of-scope, L7).
- ~~Hybrid swipe mode provenance-tagged as `NEURAL_BEAM`~~ — resolved 2026-08-18: both are deleted.

## Related Documentation

- Audit that drove the design: `docs/history/audits/2026-08-06-context-lm-review-findings.md`
- Recommendation doc: `dbd3843a` (`docs/audit/`, context-LM control/transparency/next-word)
- User guide: `docs/wiki/typing/next-word-prediction.md`
- Paired wiki spec: `docs/wiki/specs/typing/next-word-prediction-spec.md`
- Cursor sync integration: `docs/specs/cursor-aware-predictions.md`
