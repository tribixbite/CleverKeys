# Context-LM / learning / privacy — independent review findings (2026-08-06)

Adversarial review of commits `997d8f78` + `295edc43` by an independent agent. No Critical
(data-destroying / text-logging) findings. Verified-good: `LearningGate.learnCommittedWord`
short-circuits before mutation; adaptation writes + swipe-ML gated; `UnifiedScore.combine`
formula-identical to the pre-refactor score; contraction-dedup merge keeps (word,score,meta)
aligned; backup round-trip wired; `OnDeviceLearningPrivacyTest` exercises real stores.

## HIGH
- **H1 — cross-field/app LEARN leak.** `WordPredictor.clearContext()` (WordPredictor.kt:648-650)
  has ZERO callers; `onFinishInputView` clears `contextTracker` (surface) but not `recentWords`
  (the buffer `recordSequence` reads). Text from app A gets learned/persisted joined to app B and
  surfaced by next-word cross-app. FIX: call `wordPredictor.clearContext()` in `onFinishInputView`
  / `PredictionCoordinator.flushLearnedData()` after flush; also on `setPasswordMode(true)`.
- **H2 — next-word clobbers swipe alternates (regression).** `SuggestionHandler.kt:1087-1090`
  `maybeShowNextWordPredictions` runs for EVERY commit incl. swipe auto-insert
  (`onSuggestionSelected(...isManualSelection=false)`); the posted task replaces the alternates bar
  and sets `nextWordSuggestionsActive=true`, breaking swipe correction. FIX: gate call-site 2 on
  `isManualSelection` (call-site 3 already handles the auto-insert append path). See M6.
- **H3 — adaptation READ not dark when master off.** `WordPredictor.kt:1861`
  `adaptationManager?.getAdaptationMultiplier(word)` has no gate; `UserAdaptationManager.setEnabled`
  is never called in production. Master OFF + declined forget ⇒ learned selection history keeps
  re-ranking + suppressing add-to-dict prompts. FIX: AND `LearningGate.canLearnAdaptation(...)` into
  the multiplier read (+ `isInDictionary`), or sync `adaptationManager.setEnabled` in `setConfig`.

## MEDIUM
- **M1 — forget resurrection race.** `serialize` under lock then `putString` OUTSIDE it
  (BigramStore.kt:393-404; TrigramStore.kt:263-272; UserVocabulary.kt:345-350) can interleave with
  `clear` → the just-forgotten data is re-persisted by an in-flight flush. Privacy-path bug (fires
  exactly on master-off forget). FIX: hold the lock across serialize+putString, or a monotonic
  `clearEpoch` checked before `putString`.
- **M2 — gates fail OPEN on null config + DictionaryManager predictors unconfigured.** gate reads
  default `config?.on_device_learning_enabled ?: true` (WordPredictor.kt:581-583,674,688,1869);
  `DictionaryManager.setLanguage`/`preloadLanguages` build `WordPredictor()`s with no `setConfig`.
  FIX: default `?: false` in gate reads (fail closed) and/or thread config into those predictors.
- **M3 — fake "seen ≥2×" floor.** `recordSequence` records every adjacent pair in the 4-word window
  per commit, so one typing yields freq≥2/3; `NextWordPredictor.MIN_LEARNED_FREQUENCY=2` and
  `BigramStore.DEFAULT_MIN_FREQUENCY=2` pass after a single occurrence. FIX: record only newest
  bigram (`takeLast(2)`)/trigram (`takeLast(3)`) per commit, or raise floors 3×.
- **M4 — BigramStore never renormalizes siblings.** `recordBigram` (:157-179) recomputes only the
  touched entry; siblings keep stale probs → ranking inversions (cat outranks dog forever).
  TrigramStore + importFromJson renormalize; bigram hot path is the outlier. FIX: renormalize the
  20-entry sibling list in `recordBigram`.
- **M5 — incognito `IME_FLAG_NO_PERSONALIZED_LEARNING` honored nowhere.** No reference in src. FIX:
  derive `noLearning` from `editorInfo.imeOptions` in `onStartInputView`; AND into the
  `learnCommittedWord` master arg and `NextWordPredictor.shouldShow`.
- **M6 — stale next-word posts lack a bar-generation guard.** `SuggestionHandler.kt:1161-1172` checks
  only prompt/password/wordLength; any bar state set between submit and post gets overwritten. FIX:
  monotonic bar-generation counter bumped by every `setSuggestionsWithScores`; abort post on mismatch.
- **M7 — UserAdaptationManager unbounded retention + unsync map.** (a) `pruneOldSelections` removes
  in-RAM only; `word_selections_<word>` pref keys never removed → every selected word persists
  forever + resurrects. (b) `selectionCounts` plain map written on main thread, read on executor.
  FIX: `ConcurrentHashMap` + save via clear-and-put (track removals).

## LOW
- **L1** provenance popup not dismissed by `clearSuggestions`/temp-message/`onFinishInputView`
  (SuggestionBar.kt) — sheet can outlive its content. Add `dismissProvenancePopup` there.
- **L2** trigram boost ignores min-frequency (`getProbability` no floor) → once-seen trigram gives max
  4× boost. Apply `minFrequency` in `getProbability`/backoff.
- **L3** `appendNextWordToSwipeAlternates` runs generation + lazy store load on main thread → first-swipe
  jank. Pre-warm at init or move to executor.
- **L4** per-entry deletes (`removeBigram`/`removeWord`) only `markDirty()` → process death ~30s
  resurrects an explicitly-deleted word. `requestFlush()` after user-initiated remove.
- **L5** `handleCursorParkPrediction` generates from session-typed words, not text before cursor.
- **L6** auto language-detect switches `contextModel.language` mid-window while `recentWords` holds
  prior-language words → mixed-language pairs. Clear `recentWords` on language switch.
- **L7** master-scope gaps (undocumented): `NeuralPerformanceStats` (perf pref), `SwipeCalibrationActivity`
  (user-initiated), backup import repopulates with master off. Scope the PrivacySection copy or cover them.
- **L8** `PersonalizationEngine.setEnabled` persists the master-ANDed value into `personalization_enabled`
  → rewrites a redundant source of truth. Make the engine flag session-only.
- **L9** `DebouncedPersister.flush` failure restores `dirty` but `dirtyLanguages` already drained →
  stranded-unpersisted until re-marked. Re-add drained langs on failure.
- **L10** legacy migration dumps the un-keyed blob into whichever language loads first (non-en-primary
  gets en pairs mis-keyed). Migrate to `"en"` explicitly or document.

**Land first:** H2 (one-line regression), H1 (the actual privacy leak), H3 (adaptation read gate).
