# Feature Specification: Context Learning, Privacy Gate & Next-Word Prediction

## Feature Overview
**Feature Name**: Persistent Context LM + On-Device Learning Privacy Gate + Next-Word Prediction + Pipeline Transparency + Learned-Data Manager
**Priority**: P1
**Status**: Complete (commits `997d8f78`, `295edc43`, `f6824477`, 2026-08-06)
**Target Version**: post-v1.5.x

### Summary
One coordinated feature wave that (1) makes the learned context language model persistent
across restarts, (2) puts ALL typing-behavior learning behind a single master privacy gate,
(3) adds Gboard-style next-word prediction (learned n-gram store + shipped static model;
default ON and independent of the learning gate for the shipped tier since 2026-09-26), (4) makes
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
The `on_device_learning_enabled` preference (**default OFF on fresh installs since v2.0 —
opt-IN**; upgrades are seeded `true` by `LearningMigration`) is the
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

READ gates: `canUseLearnedContext(master, contextAwareEnabled)` (dynamic context boost,
swipe rescoring), `canUseLearnedNextWord(master, contextAwareEnabled, fieldAllows)` (the
next-word LEARNED tier — continuations, personalization re-rank, learned-vocabulary
allow-list; §3) and `canUseAdaptation(master)` (adaptation re-rank multiplier +
add-to-dictionary prompt suppression; review H3). Personalization boost returns 0 once the
engine is disabled. The next-word STATIC tier (shipped model) is deliberately outside every
learning gate — it is not learned data (maintainer decision 2026-09-26, §3).

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
closes the next-word LEARNED tier (`fieldAllowsPersonalizedLearning` parameter throughout).
The shipped static next-word tier still shows there, like prefix predictions do (§3).

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
| `BigramStore` | `MAX_BIGRAMS_PER_WORD = 20` established + 4 grace slots (`MAX_RETAINED_PER_WORD = 24`) | `MAX_TOTAL_BIGRAMS = 10000` per language, batch-pruned to 90% (sub-floor first, never the pair just recorded) | `ContinuationBudget` (W3, 2026-09-26): newcomers compete for grace slots by staleness; established entries by frequency aged with a 100-observation half-life; the entry recorded in the current call is never evicted. `DEFAULT_MIN_FREQUENCY = 2` to surface. Typo hygiene: only n-grams of `LearnableWordPolicy`-learnable words are recorded; low-frequency (≤2) n-grams with an unlearnable word are purged once after upgrade, then weekly |
| `TrigramStore` | `MAX_TRIGRAMS_PER_PREFIX = 10` established + 3 grace slots (`MAX_RETAINED_PER_PREFIX = 13`) | `MAX_TOTAL_TRIGRAMS = 10000` per language, batch-pruned to 90% | same `ContinuationBudget` policy and typo hygiene as bigrams; `DEFAULT_MIN_FREQUENCY = 2` to surface |
| `UserVocabulary` (personalization) | — | **user-configurable** via `personalization_max_words` (default `Defaults.PERSONALIZATION_MAX_WORDS = 5000`, slider 1000–20000, floor `MIN_VOCABULARY_CAP = 100`) | rolling least-value eviction (lowest `getPersonalizationBoost` first) on add; `enforceCap()` trims down on load and when the user lowers the cap; stale words (>90 days, or single-use >30 days) cleaned daily |
| `UserAdaptationManager` / `SelectionHistory` | — | `MAX_TRACKED_WORDS = SelectionHistory.DEFAULT_MAX_TRACKED_WORDS = 1000` selection-count entries | over cap, least-selected words pruned down to 80% of capacity (`PRUNE_KEEP_FRACTION = 0.8`); counts HALVE every 30 days (`DECAY_HALF_LIFE_MS`, W4 — replaced a 30-day wholesale wipe); debounced write-back flushed at `flushLearnedData` (W6) |

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

## 3. Next-Word Prediction (default ON since 2026-09-26)

**Files**: `NextWordPredictor.kt` (pure JVM gating + generation + the gated read path),
`SuggestionHandler.kt` (impure wiring: `nextWordTiers`, `maybeShowNextWordPredictions`,
`appendNextWordToSwipeAlternates`, `generateNextWordCandidates`, `handleCursorParkPrediction`)

### Gating — two tiers (`NextWordPredictor.decideTiers`, maintainer decision 2026-09-26)

Next-word has two sources with very different privacy weight, so it has two decisions
(`NextWordPredictor.TierGate`):

| Tier | Source | Requires |
|---|---|---|
| **Static** (`showStatic`) | shipped context LM / curated bigram seed — identical on every install, nothing personal | `next_word_prediction_enabled` ∧ `word_prediction_enabled` ∧ ¬password ∧ ¬special prompt (autocorrect-undo / add-to-dictionary / swipe-preference offer) ∧ ¬Termux field ∧ non-empty context |
| **Learned** (`useLearned`) | the user's bigram/trigram stores, plus the personalization boost and learned-vocabulary allow-list that rank/filter them | static-tier conditions ∧ `LearningGate.canUseLearnedNextWord` = `on_device_learning_enabled` ∧ `context_aware_predictions_enabled` ∧ field allows personalized learning |

`useLearned ⇒ showStatic` is enforced by `TierGate`'s constructor. Until 2026-09-26 one
boolean (`shouldShow`) required the learning gates for both tiers, so next-word was dead
for every fresh v2.0 install (learning is opt-in) even though the shipped tier holds no
personal data.

**Why the static tier ignores each learning control:**
- *Master learning gate* — its contract is that nothing typing-derived is recorded or read.
  The static tier records nothing and reads only the previous word, which every ordinary
  prediction already reads.
- *`context_aware_predictions_enabled`* — its Settings copy is "Learn from typing patterns
  (N-gram model)": it controls the LEARNED LM (`canLearnContext` / `canUseLearnedContext`).
  The shipped model's other consumer, the prefix-scoring static multiplier
  (`WordPredictor.resolveScoreBreakdown` step 3a), is not gated by it either.
- *Incognito flag* (`IME_FLAG_NO_PERSONALIZED_LEARNING`) — it forbids learning from, and
  personalizing on, the field's text. A continuation shipped to everyone is neither; it is
  the same class of generic suggestion as the prefix completions the field already gets.
  The learned tier stays closed there.

**The read path is one function**: `NextWordPredictor.candidatesFor(gate, context, predictor)`
serves all call sites. With `useLearned` false it does not call
`getNextWordCandidates`, `getPersonalizationBoostFor` or `isInUserVocabulary` at all (the
last is ungated inside `WordPredictor` and reads the personalization tally), and filters
the static seed by dictionary membership + Dictionary Manager disables only. Dictionary
membership is `isInDictionary(word, TierGate.fieldAllowsPersonalizedLearning)`: that check
can also admit a word through selection-adaptation history (master-gated, H3), and the field
flag keeps that learned read out of incognito fields. (Until 2026-09-26 it ran under the
master gate alone, so an incognito field with learning ON could have its static bar widened
by selection history; pinned now by `NextWordStaticTierTest.incognitoFieldKeepsSelectionHistoryOutOfTheStaticTierFilter`.)
Outside incognito, with the learned tier closed only by the context-aware pref, the
master-gated adaptation widening still applies — it is selection-adaptation data, not n-gram
learning.

**Nothing is written by next-word.** Accepting a candidate is an ordinary bar selection: the
committed word goes through `LearningGate.learnCommittedWord` and the gated adaptation
recorder like any other commit, so with the master off (or in an incognito field) nothing
is learned. Pinned end to end by `NextWordStaticTierTest`.

### Candidate generation (`NextWordPredictor.generate`)
Input (learned tier only): probability-ranked `ContextModel.getNextWordCandidates`
(trigram-preferred with bigram backoff), max `LEARNED_LOOKUP_LIMIT = 10`. Filters:

1. **Confidence floor**: learned frequency ≥ `MIN_LEARNED_FREQUENCY = 2` AND conditional
   probability ≥ `MIN_LEARNED_PROBABILITY = 0.05` — an EMPTY next-word bar is the designed
   common case; show nothing rather than noise.
2. **Self-repetition**: drop the just-committed word.
3. **`isWordAllowed`**: must be in dictionary or (learned tier open only) user vocabulary
   AND not disabled in Dictionary Manager (blocks typo'd garbage the n-gram stores may have
   absorbed).
4. Dedup (first occurrence wins).

Ranking score = `probability × (1 + personalizationBoost/4) × 1000` (same personalization
conversion as `WordPredictor.calculateUnifiedScore`); personalization can reorder within
the surfaced set. Caps: `MAX_SUGGESTIONS = 3` (whole-bar), `MAX_SWIPE_APPEND = 2`
(appended after swipe alternates).

### Cold-start tier (static seed, ARC-020 — as built 2026-08-28)

Until 2026-08-28 the section above was the whole story, and it made the feature dead on a
fresh install: the learned store yields nothing until a phrase has been typed twice at ≥5%
conditional probability, so a user who turned next-word on saw an empty bar for days. The
`staticSeed` argument to `NextWordPredictor.generate` closes that. It carries the shipped
`assets/bigrams/<lang>_bigrams.json` continuations of the last context word — read through
`WordPredictor.getStaticNextWordSeed` → `BigramModel.getPredictions`, ranked by
`StaticBigramSeed` — and it fills ONLY the bar slots the learned tier left empty. The two
tiers are concatenated, never merged and re-sorted: the learned list is scored and sorted
first, then seeded entries are appended, so a curated 0.94 rank can never displace a
learned 0.05-probability candidate. Seeded entries reuse the self-repetition, dedup, and
`isWordAllowed` filters, skip the learned confidence floors (a shipped pair has no
observation count to floor), take no personalization multiplier, and score inside a band
below `STATIC_SEED_SCORE_CEILING = 49` — one below the lowest score a learned candidate can
reach — so the debug-score column stays monotonic with the displayed order. An established
user whose learned store fills all three slots never consults the seed at all.

Gating (revised 2026-09-26): the seed is not personal data, so `getStaticNextWordSeed`
adds no gate read of its own, and it is now read under the STATIC-tier gate only (feature
pref + suggestion-bar guards) — with learning off, context-aware off, or in an incognito
field it is the whole bar. It is read solely inside `NextWordPredictor.candidatesFor`,
after `decideTiers`; `LearningWiringDriftTest` pins that no call site reads it (or any
learned next-word source) directly.

### Static context LM (en, 2026-09-26)

For English the shipped static context is no longer the hand-authored tables: `assets/lm/en.cklm`
(`StaticContextLm`, CKLM v1, built by `scripts/build_static_lm.py` from the Leipzig Corpora
Collection web-2018 300K sample + Tatoeba at weight 0.1; 74,797 pairs, 439 KB, ~0.67 MB heap)
loads on `BigramModel`'s seed thread and then serves BOTH static products:

- **tap multiplier** — `getContextMultiplier = clamp(P(w|prev) / P(w), 0.1, 10)`, same clamp as
  before; a word not among the previous word's top-20 continuations gets that word's backoff
  ratio (slightly below 1). `context_source` semantics are unchanged: in the default `both`, the
  applied multiplier is still `max(static, learned)`, so the backoff penalty never bites there.
- **next-word cold-start seed** — `getPredictions` returns the LM's continuations ranked by
  conditional probability; the curated `en_bigrams.json` pairs only fill slots the LM leaves
  empty (14 of its 319 pairs, e.g. "good morning", are outside the corpus top-20).

Other languages keep the JSON seeds (and, for es/fr/de/en before load, their own hardcoded
pairs) until they get an LM. Since 2026-09-29 German, French, Italian, Portuguese and Swedish have
one (below).

**Legacy multiplier fix (2026-09-29).** Without an LM, `hardcodedContextMultiplier` now moves the
tap multiplier only for a LISTED `(prev, word)` pair of the language's OWN table, never below 1;
everything else is 1.0, and the device path uses the requested language. Before, the formula read
the handful of listed pairs as the whole conditional distribution (every unlisted table unigram →
0.1×) and languages without tables were scored by ENGLISH's tables — `static_only` prefix-1 top-3
on UD Spanish-GSD test went −22.65 → +0.04 pt vs no context. `both` was ≈ 0 before because
`max(static, learned)` with an empty store is `max(static, 1)`: it discards every sub-1 static
value — which also means an LM's backoff penalty never applies in the default mode.

**Contraction keys (2026-09-29 lookup fix).** The model names contractions by display form
(`don't`, `c'est`); the dictionaries — and so the tap candidates — hold the apostrophe-free key
(`dont`, `cest`). `StaticContextLm.withReplaceAliases` resolves every REPLACE key to its display
form in all lookups (multiplier, seed, previous word); `BigramModel` builds the map at load from
`ContractionManager.loadSwipeDisplayMappings(language)`. PAIRED bases (`well`, `lune`) never
alias. Before the fix `i → dont` scored 0.51 (backoff) against `i → don't` 27.5.

**Multilingual follow-through (2026-09-27 → 2026-09-29):** runtime asset lookup already uses
`lm/<language>.cklm`. The builder is now configured per language (`LangConfig` in
`scripts/build_static_lm.py` `CONFIGS`: pinned Leipzig web corpus + Tatoeba detailed export +
UD dev/test, the language's shipped lexicon — `en_enhanced.json` or the CKDT
`<lang>_enhanced.bin` canonical words — ∪ its REPLACE + PAIRED contraction display forms,
wordfreq artefact reference, NFC composition and train/eval-overlap exclusion). `--lang` accepts
exactly the configured codes (en es de fr it pt sv); en rebuilds byte-identically. Weight
selection is restricted to weights whose model fits the 512 KiB cap.

**Shipped per language (2026-09-29): en, de, fr, it, then pt and sv** (retry below). The maintainer's rule is that each
language ships on its OWN pre-registered S1 gate. After the contraction-lookup fix, one
re-evaluation on the unchanged gates (OOD prefix-1 top-3 Δ): de +5.16, fr +6.75, it +7.48 pass
and ship; es +4.69, pt +4.19, sv +4.59 (prefix-2 +1.92) fail and stay unshipped (es/pt/sv have no
REPLACE contractions, so the fix could not move them). For de/fr/it the LM's continuations are
the whole next-word seed wherever it covers the previous word; the legacy seed (hardcoded pairs
+ `bigrams/<lang>_bigrams.json`) is the fallback before load and for unknown previous words.
English keeps its curated gap-fill. Budgets: ≤ 475 KB asset, ≤ 0.91 MB heap per language,
3.15 MB with all four resident. Tables, before/after and S2:
`docs/eval/2026-09-29-static-lm-multilingual.md`. The failed S3 swipe gate remains in force for
every language: no swipe rescoring.

**es/pt/sv retry (pre-registered, 2026-09-29).** Changed input: a second Leipzig corpus mixed into
the web corpus (weights sum to 1) plus the Tatoeba weight, chosen on the OOD **dev** split by the
gate metric itself (`StaticLmTapEvalTest` `STATIC_LM_EVAL_SPLIT=dev`), then one test evaluation per
language on the unchanged gates and treebanks. **pt SHIPS** (Portugal web + Brazilian news 2011,
0.5/0.5, no Tatoeba — UD Bosque is 45 % Brazilian news; prefix-1 +5.41), **sv SHIPS** (news 2023 +
Tatoeba × 2.0; +5.98 / prefix-2 +2.95), **es FAILS again** (Wikipedia 2021 × 0.75; +4.96) and stays
unshipped. pt/sv retire their legacy seed where the LM covers the previous word, like de/fr/it.
Budgets now: ≤ 475 KB asset and ≤ 0.91 MB heap per language, 4.55 MB with all six resident.

Contraction continuations (fixed 2026-09-29): `NextWordPredictor.candidatesFor` judges an
apostrophe form (`don't`, `c'est`, straight or typographic apostrophe) by its apostrophe-free
dictionary key, because every bundled dictionary stores contractions that way; a Dictionary
Manager disable of the key blocks the display form too (`NextWordContractionAllowTest`).

# TODO: es needs a new stated reason before any further attempt (e.g. a larger Spanish test
# population; GSD test is 339 sentences, ±0.7 pt); measure an LM contextRatio floored at 1 on dev
# (lm_both beats lm_static at prefix-1 by 0.6–1.1 pt); imported-pack LM support would need a
# separate importer contract.
Evaluation:
`docs/eval/2026-09-26-static-lm-replay.md` (tap S1 and swipe S3); provenance:
`scripts/data/PROVENANCE.md`; attribution: `NOTICE`, Settings → Help & FAQ.

### The four call-sites (audit §4.4)

| # | Trigger | Behavior |
|---|---------|----------|
| 1 | Typed word completed with a **space** (`SuggestionHandler` single-char commit path, `text == " "` only) | Bar would otherwise clear → show up to 3 context-only candidates. After sentence-final punctuation the context was just cleared, so nothing shows. |
| 2 | **Manual tap** on a suggestion (`onSuggestionSelected`, `isManualSelection` only — review H2) | Context just grew → chain another round ("want" → tap "to" → suggests "go/see/be"). The swipe AUTO-insert must NOT route here (it would replace the alternates bar and break swipe correction) — it composes via call-site 3 instead. |
| 3 | **Swipe auto-insert** results displayed (`appendNextWordToSwipeAlternates`) | KEEP the swipe alternates (user may still correct the swipe) and APPEND ≤2 next-word candidates after them, tagged with per-suggestion `NEXT_WORD` metas so a tap APPENDS the word instead of replacing the auto-inserted swipe word. Runs on the shared `predictionTasks` executor (review L3 — first lookup lazily loads persisted n-gram blobs; inline it caused first-swipe jank). |
| 4 | **Cursor parked** after existing text with no partial word under it (`handleCursorParkPrediction`, routed from InputCoordinator's empty-prefix cursor-sync branch) | Gboard-style tap-into-text predictions. **L5 RESOLVED — the editor scan SHIPPED**: `SuggestionHandler.readEditorParkContext` (`:1508-1529`) does a guarded `getTextBeforeCursor(EDITOR_PARK_CONTEXT_CHARS, 0)` and tokenizes it with the pure `NextWordPredictor.contextFromEditorText` (`:182-219`, sentence-boundary aware, last `LearningGate.CONTEXT_WINDOW` tokens), so parking into an unrelated paragraph predicts from THAT paragraph — including text typed in an earlier session. The read is gated on the CHEAP static-tier prerequisites first (`nextWordTiers(editorInfo, hasContext = true).showStatic`: feature pref, word prediction, password / prompt / Termux) so a field that could never surface a candidate is never even read — since 2026-09-26 the learning prefs and incognito flag no longer block it, because the static tier needs the previous word with learning off; the words are used for that lookup and never recorded; `null` on read failure falls back to session context, while an empty list is a real "parked at a sentence start → show nothing". Pinned by `LearningWiringDriftTest` (`:189-204`, `:255`, `:260`). |

### Staleness + dismissal
- Bar-generation guard (review M6): the async post aborts if `SuggestionBar.contentGeneration()`
  changed between submit and post (user typed, new swipe, prompt appeared).
- `nextWordSuggestionsActive` state: any selection consumes it; **backspace with no partial
  word dismisses** the candidates (the only new state next-word introduces); typing a letter
  replaces them with normal prefix predictions; multi-char input (paste) clears.
- Display: stored-lowercase words are restored via I-word capitalization +
  user-dictionary proper-noun case (`applyUserWordCaseToList`).

### Next-word UX walkthrough (what the user actually sees)

Preconditions: Settings → ⌨️ Input Behavior → Word Prediction → **Next-Word Prediction ON**
(default since 2026-09-26). With 🔒 Privacy & Data → **Learn From My Typing OFF** (the v2.0
fresh-install default) only the shipped continuations appear (e.g. after "want": `to  you  a`
from `en.cklm`). The learned entries in the walkthrough below additionally need learning ON
and the phrases typed at least twice before (floor: seen ≥2×, ≥5% conditional probability).

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
shows `Source: Next-word prediction` plus, for a learned entry, the statistics behind it —
`After "want to": seen 14×, 63%` (trigram context shows the last two words; bigram shows
one). A cold-start entry from the static seed says so instead —
`After "the": common continuation (built-in, not learned)` — because it has no observation
count and printing `seen 0×, 0%` would read as evidence that does not exist. The origin
label dropped its `(learned)` suffix for the same reason: the tier is a per-suggestion fact,
not a per-origin one. With **Suggestion Origin Markers** enabled (Advanced), next-word
entries carry a distinct colored dot distinguishing them from swipe alternates in mixed bars.

**When next-word will NOT appear:** feature pref explicitly off; password fields; Termux; while an
autocorrect-undo or add-to-dictionary prompt is showing; empty session context; word
prediction disabled; or nothing learned above the floor (or the learned tier closed —
learning off, context-aware off, incognito field) AND no shipped continuation for the
last word (the static seed covers de/en/es/fr/it/pt only, and only the ~100–320 previous
words each asset lists).

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
  words/scores lists. `note` is a structured `ProvenanceNote`, never a rendered sentence;
  breakdown is non-null only for the dictionary-prefix path.
- **Long-press** any suggestion → provenance popup (`ProvenanceFormatter.format`). The Android
  layer passes resource-resolved labels/templates plus structured personalization fields, so
  origin labels, notes, score components, and usage details all follow the active locale.
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
  payload (`learned_bigrams_by_language` / `learned_trigrams_by_language` /
  `user_vocabulary` keys in `BackupRestoreManager`). Trigrams were added by ARC-022
  (2026-08-28) — before that the claim above was true for bigrams only, which migrated the
  blunter half of the model. Import treats every key as optional, so a pre-ARC-022 export
  (no `learned_trigrams_by_language`) still imports; both n-gram orders MERGE (frequencies
  add, probabilities recompute), the vocabulary is REPLACED.

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
| Next-Word Prediction | `next_word_prediction_enabled` | `true` (was `false` until 2026-09-26; an explicit stored `false` is kept) | bool — always enabled in Settings (no longer disabled while Context-Aware is off) | ⌨️ Input Behavior → Word Prediction |
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
| `NextWordPredictorTest` | Two-tier gating matrix (exhaustive over all 2^9 inputs: static = bar guards, learned = static ∧ master ∧ context-aware ∧ field), floors, self-repetition, dedup, personalization reorder, static cold-start tier (fill-only, sub-floor scores, no faked stats) |
| `NextWordStaticTierTest` (mock tier) | Real `SuggestionHandler` + `WordPredictor` over the shipped `lm/en.cklm`: static next-word shows with learning OFF, context-aware OFF and in an incognito field; learned tier never read (no `getNextWordCandidates` / boost / user-vocabulary call) in those states; learned entry leads when learning is on; password / Termux / explicit-off show nothing; cursor park reads the editor for the static tier; accepting a next-word with the gate off learns nothing; default ON pinned |
| `StaticBigramSeedTest` | Shipped `assets/bigrams/*` schema against the real files, asset-wins merge, hardcoded fallback index |
| `StaticContextLmTest`, `BigramModelStaticLmTest`, `StaticLmAssetDriftTest` | CKLM v1 loader contract, the BigramModel adapter (multiplier + seed + gap fill), drift pins for every shipped `lm/<lang>.cklm` — en/de/fr/it (sidecar sha256, vocab, caps, heap incl. the alias index, attribution, REPLACE-only aliases) |
| `SuggestionProvenanceTest` | UnifiedScore combine + breakdown + formatter |
| `BigramStorePersistenceTest`, `TrigramStorePersistenceTest`, `UserVocabularyPersistenceTest` | Language keying, legacy migration, debounced write-back |
| `UserVocabularyCapTest` | Configurable `personalization_max_words` cap: default, live provider changes, least-value eviction at capacity, lower-cap trim (enforceCap/on-load/import), floor clamp |
| `DebouncedPersisterTest` | Debounce/max-delay/flush semantics |
| `ContextModelTrigramTest`, `ContextModelLanguageTest` | Backoff order, language isolation |
| `ContextLearningBoundaryTest`, `SelectionHistoryTest`, `LearnedStoreForgetRaceTest`, `LearningWiringDriftTest` | Review-fix regression coverage |

## Deferred / known limitations

- ~~Cursor-park next-word reads session context only, not editor text (review L5) — an
  InputConnection editor scan per park is deferred until the (default-OFF) feature earns
  it.~~ **RESOLVED — shipped.** `readEditorParkContext` +
  `NextWordPredictor.contextFromEditorText` do the per-park editor scan; see call-site 4
  in the table above. Residual limitation: the scan reads only
  `EDITOR_PARK_CONTEXT_CHARS` before the cursor and falls back to session context when
  the InputConnection is unavailable.
- ~~Next-word has no cold-start source: learned-only, so it is dead until a phrase has been
  typed twice at ≥5% conditional probability (audit §4.2-2's static seed unadopted).~~
  **RESOLVED 2026-08-28 (ARC-020)** — see "Cold-start tier" above. Residual limitation: the
  seed covers six languages and only the previous words those hand-curated assets list
  (~100–320 each), so it thins out quickly outside common openers.
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
