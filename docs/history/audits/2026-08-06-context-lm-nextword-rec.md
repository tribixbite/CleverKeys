# Context LM, Transparency & Next-Word Prediction — Audit + Recommendations

**Date:** 2026-08-06
**Scope:** `contextaware/`, `personalization/`, `WordPredictor.kt`, `SuggestionHandler.kt`,
`SuggestionBar.kt`, `Config.kt`, settings sections. Design/analysis only — no code changed.

**Framing — the user's actual goals** (everything below is organized around these):

- **(a) Maximum user control** over the prediction pipeline — per-stage toggles, tunable
  weights, and ownership of the learned data itself.
- **(b) Pipeline transparency** — users can *see* which stage produced each suggestion and why.
- **(c) Optional next-word prediction** — Gboard-style: predict the NEXT word from context
  *before any letter is typed*. Currently absent; the context LM only *boosts* candidates for a
  word already in progress.

The three goals are mutually reinforcing and all sit on the same foundation: a context LM that
actually remembers what it learned. That foundation is currently broken (§1).

---

## 1. BUG — the learned bigram model never persists (learns in RAM, forgets on restart)

### 1.1 Root cause (confirmed)

The dynamic n-gram context model loses everything it learned whenever the IME process dies,
the keyboard service restarts, or a per-language `WordPredictor` is evicted.

Precise chain:

| Step | Location | Fact |
|---|---|---|
| Learn hook fires | `SuggestionHandler.updateContext` (`SuggestionHandler.kt:1064-1082`) → `WordPredictor.addWordToContext` (`WordPredictor.kt:547-579`) | On every committed word, `contextModel?.recordSequence(sequence)` runs (`WordPredictor.kt:561-566`, 4-word window) |
| Sequence recorded | `ContextModel.recordSequence` (`ContextModel.kt:66-80`) | Calls `bigramStore.recordBigram(...)` per pair. **Never calls `save()`** |
| Bigram recorded | `BigramStore.recordBigram` (`BigramStore.kt:63-106`) | Mutates `bigramMap` / `word1Frequencies` in RAM. **Never calls `saveToPreferences()`** |
| The save method | `BigramStore.saveToPreferences` (`BigramStore.kt:221-240`) | Exists, works — but its only callers are `clear()` (`BigramStore.kt:176`) and `importFromJson()` (`BigramStore.kt:318`) |
| The public flush | `ContextModel.save()` (`ContextModel.kt:198-201`) | **Zero callers anywhere in the codebase** |

The docstrings are actively false:

- `BigramStore.kt:27` — "Persistence: Async save to SharedPreferences" (never on the learn path).
- `ContextModel.kt:196` — "Called automatically during recordSequence" (it is not).

Contrast with the personalization side, which *does* persist:
`UserVocabulary.recordWordUsage` calls `saveToPreferencesAsync()` on **every word**
(`UserVocabulary.kt:105-107`, implementation at `UserVocabulary.kt:277-289` — a raw
`thread { }` per keystroke-committed word, serializing the whole 5000-entry vocabulary via
Gson each time). So today the two learned stores fail in opposite directions: bigrams never
save; user vocabulary saves far too often.

### 1.2 Compounding architectural hazards (must be addressed by the fix, not after)

1. **Multi-instance clobber risk.** Each per-language `WordPredictor` creates its **own**
   `ContextModel` (`WordPredictor.kt:196`, init at `WordPredictor.kt:244-247`), and every
   `BigramStore` opens the **same** SharedPreferences file (`PREFS_NAME = "bigram_store"`,
   `BigramStore.kt:31`). `DictionaryManager` keeps up to 4 predictors alive
   (primary/secondary/alt slots). The moment persistence is wired naively, two live stores
   each holding a *different* in-RAM superset will overwrite each other's
   `bigrams_json` blob — last-writer-wins data loss. Today this is masked only because
   nothing ever writes.
2. **No language keying.** Bigrams from all languages mix in one map. `"the" → "chat"` from a
   French session pollutes English predictions and vice versa. This matters little for
   *boosting* (the candidate list is already language-filtered) but is fatal for **next-word
   generation** (§4), where the store itself produces the candidates.
3. **Eviction without flush.** `DictionaryManager.setLanguage` evicts predictors
   (`DictionaryManager.kt:126-127`: `stopObservingDictionaryChanges(); predictors.remove(k)`)
   without any learned-data flush — with persistence added, an eviction must flush first or
   the evicted language's unsaved learning is lost.
4. **`saveToPreferences` spawns a raw `Thread` per call** (`BigramStore.kt:222`). Fine for the
   two current callers; wrong for a hot path. Serializing up to `MAX_TOTAL_BIGRAMS = 10000`
   entries to JSON is real work.

### 1.3 Fix design (recommended)

**Do NOT save per keystroke** (would jank the IME and churn flash writes — and
`UserVocabulary`'s current per-word Gson dump should be fixed to the same pattern, not copied).
Instead: **dirty-flag + debounced async write-back + explicit lifecycle flush.**

**A. Debounced write-back in `BigramStore`:**

- Add `@Volatile private var dirty = false` and a single shared
  `ScheduledExecutorService` (or `Handler` on an HandlerThread) owned by the store (or better:
  a small shared `DebouncedPersister` helper reused by `UserVocabulary` too).
- `recordBigram(...)` sets `dirty = true` and schedules `flush()` with a ~5 s debounce
  (each new record within the window pushes the deadline; hard cap ~30 s so a continuous
  typist still checkpoints).
- `flush()`: if `dirty`, snapshot the map under `synchronized(this)`, serialize **off the
  main thread**, `prefs.edit().putString(...).apply()`, clear `dirty`. Idempotent, safe to
  call from any thread.
- Replace the raw `Thread` in `saveToPreferences` with the same executor.

**B. Lifecycle flush points (belt-and-braces, all cheap because `flush()` no-ops when clean):**

- `CleverKeysService.onFinishInputView` (`CleverKeysService.kt:810-826`) — the natural
  "user left the field" moment; add a call that reaches
  `WordPredictor.persistLearnedData()` → `contextModel?.save()` (route through
  `PredictionCoordinator`/`DictionaryManager` to hit *all* live predictors' models).
- `PredictionCoordinator.shutdown()` (`PredictionCoordinator.kt:643`) — flush **before**
  `dictionaryManager?.cleanup()` and the `wordPredictor = null` teardown.
- `DictionaryManager.setLanguage` eviction (`DictionaryManager.kt:126-127`) — flush the
  evicted predictor's `ContextModel` before `predictors.remove(k)`.

**C. Kill the multi-instance clobber + add language keying in the same change:**

Two viable shapes; recommend the first:

1. **Singleton store, language-keyed entries.** Make `BigramStore` a process-wide singleton
   (companion `getInstance(context)`), keyed internally by language:
   `Map<lang, Map<word1, List<BigramEntry>>>`, persisted as one prefs key per language
   (`bigrams_json_<lang>`; migrate the legacy `bigrams_json` blob into the current primary
   language on first load, then delete it). `ContextModel` gains a `language` parameter (each
   `WordPredictor` already knows its language). One writer ⇒ no clobber; per-language data ⇒
   next-word-ready.
2. Alternative: keep per-predictor stores but have each store own only its language's prefs
   key. Simpler diff, but N stores still race on shared init/prune logic and the singleton is
   barely more work.

**D. Trigram store — activate now or later?** `TrigramEntry.kt` is complete (probability math,
normalization, `matches()`), but there is **no `TrigramStore`** — `ContextModel` has it
commented out (`ContextModel.kt:53-54`, `74-79`, `109-117`, `146-154`).
**Recommendation: do NOT bundle trigram activation into the persistence bug fix.** Rationale:
(i) the bug fix should be a minimal, verifiable change; (ii) trigrams multiply storage and
prune complexity (needs its own `MAX_TOTAL`, back-off weighting vs bigrams); (iii) their real
payoff is next-word quality — activate them as part of Phase 4 (§4), on top of the
already-fixed persistence/keying substrate. Note the learn path is already trigram-ready:
`addWordToContext` records 4-word windows (`WordPredictor.kt:562-565`), so the day
`TrigramStore` exists, `recordSequence` just uncomments its loop.

**E. Fix the false docstrings** (`BigramStore.kt:27`, `ContextModel.kt:194-197`) in the same
commit.

### 1.4 Exact files/methods to change

| File | Change |
|---|---|
| `contextaware/BigramStore.kt` | dirty flag; debounced `flush()`; executor replaces raw `Thread`; singleton + language keying; prefs-key migration; docstring fix |
| `contextaware/ContextModel.kt` | pass language through; `save()` delegates to `flush()`; docstring fix |
| `WordPredictor.kt` | `persistLearnedData()` public flush (contextModel + future stores); construct `ContextModel` with language (`WordPredictor.kt:244-247`) |
| `PredictionCoordinator.kt` | call flush in `shutdown()` before teardown (`:643`); expose a `flushLearnedData()` for the service |
| `DictionaryManager.kt` | flush evicted predictors in `setLanguage` (`:126-127`) |
| `CleverKeysService.kt` | flush call in `onFinishInputView` (`:810`) |
| `personalization/UserVocabulary.kt` | (same pattern, opportunistic) replace per-word `thread{}` save (`:277-289`) with the shared debounced persister |

### 1.5 Tests to add

- **`BigramStorePersistenceTest` (instrumented, ew-cli — needs real SharedPreferences):**
  1. round-trip: `recordBigram` ×N → `flush()` → construct a fresh store → assert
     `getPredictions`/`getProbability`/`getTotalBigramCount` identical;
  2. debounce coalescing: many records ⇒ bounded number of prefs writes (inject a counting
     `SharedPreferences` wrapper or spy);
  3. corrupted-JSON load falls back to empty (existing `loadFromPreferences` catch,
     `BigramStore.kt:274-278`);
  4. language isolation: `en` records invisible to `fr` store view; legacy-blob migration.
- **Eviction flush test (MockK/`runMockTests`):** `DictionaryManager.setLanguage` eviction
  triggers the evicted predictor's flush.
- **Drift guard:** extend the existing docstring-vs-behavior discipline — a JVM test asserting
  `recordBigram` marks dirty and that a flush after record persists (pure if serialization is
  extracted into a pure function; otherwise instrumented).

**Effort: S–M. Risk: low** (additive; the only behavioral change is that learning survives —
plus the deliberate language-keying migration, which needs the migration test above).

---

## 2. PIPELINE TRANSPARENCY — let users see what produced each suggestion

### 2.1 What exists today (dev-facing only)

- `swipe_show_debug_scores` (Config default `false`, `Config.kt:303/533/823`; UI in
  `ui/settings/sections/AdvancedSection.kt:56`) — renders raw score under each word:
  `"${suggestion.text}\n${currentScores[index]}"` (`SuggestionBar.kt:249-253`). A bare
  integer; no indication of *why*.
- `swipe_show_raw_beam_predictions` (`Config.kt:306/626/913`; UI `AdvancedSection.kt:87`) —
  neural-beam raw output, `raw:`-prefixed words.
- `PersonalizationEngine.explainBoost()` (`PersonalizationEngine.kt:248-307`) — a **complete,
  already-implemented, human-readable breakdown** (`BoostExplanation`: usage count, frequency
  score, recency score, base boost, aggression multiplier, final boost). Currently dead weight
  — no UI calls it. This is the seed crystal for the whole feature.
- `PredictionSource` enum (`PredictionSource.kt`) tracks the *commit* source
  (USER_TYPED_TAP / NEURAL_SWIPE / CANDIDATE_SELECTION …) — per-commit, not per-suggestion.

### 2.2 Where provenance is knowable (and currently discarded)

`WordPredictor.calculateUnifiedScore` (`WordPredictor.kt:1733-1795`) computes **every signal
separately** and then collapses them into one `Int`:

- `prefixScore` (`:1735`, function `:1800-1819`)
- `adaptationMultiplier` (`:1739`)
- `staticContextMultiplier` — hardcoded `BigramModel` (`:1742-1746`; `BigramModel.kt:426`)
- `dynamicContextBoost` — learned `ContextModel` (`:1752-1756`)
- combined `max(static, dynamic)` (`:1761`) — which of the two won is known here
- `personalizationMultiplier` (`:1767-1774`)
- `frequencyFactor` (`:1780-1781`)
- final formula (`:1788-1792`)

The typed path also *injects* candidates with knowable identity in
`SuggestionHandler.updatePredictionsForCurrentWord` (`SuggestionHandler.kt:1362-1511`):
contraction injections (`:1408-1427`), possessive augments (swipe path `:392-397`),
`ExactAdd` (`:1461-1486`). The swipe path's words are neural-beam outputs with beam scores
(`handleSwipePredictionResults`, `:352-465`). Every category is distinguishable at creation
time and anonymous by the time it reaches the bar.

### 2.3 Design: per-suggestion provenance, surfaced as a real user feature

**Data model (new, small):**

```kotlin
// tribixbite/cleverkeys/SuggestionProvenance.kt
enum class SuggestionOrigin { NEURAL_BEAM, GEOMETRIC, DICTIONARY_PREFIX,
    CONTRACTION, POSSESSIVE, EXACT_ADD, NEXT_WORD, AUTOCORRECT }

data class ScoreBreakdown(          // filled by calculateUnifiedScore when inspection is on
    val prefixScore: Int, val adaptationMult: Float,
    val staticContextMult: Float, val dynamicContextBoost: Float,
    val contextWinner: ContextWinner,      // STATIC / LEARNED / NONE  (the max() at :1761)
    val personalizationBoost: Float,       // pre-conversion 0–6 value
    val frequencyFactor: Float, val finalScore: Int)

data class SuggestionMeta(val origin: SuggestionOrigin, val breakdown: ScoreBreakdown?)
```

**Plumbing:**

1. `WordPredictor.calculateUnifiedScore` gets an overload/flag that also emits
   `ScoreBreakdown` (allocate only when the inspector pref is on — this is the per-candidate
   hot loop; gate with one boolean check so the default path is unchanged).
2. `PredictionResult` (`WordPredictor.kt` return type of `predictInternal:1601`) carries an
   optional `metas: List<SuggestionMeta>?` parallel to `words`/`scores`.
3. `SuggestionHandler` tags its injections at creation: contraction adds → `CONTRACTION`,
   possessive augment (`augmentPredictionsWithPossessives`, call site `:396`) → `POSSESSIVE`,
   exact-add (`:1480`) → `EXACT_ADD`; swipe words (`handleSwipePredictionResults:352`) →
   `NEURAL_BEAM` (or `GEOMETRIC` per `swipe_engine_mode` routing). Metas ride alongside the
   existing parallel `barWords`/`barScores` lists into the bar.
4. `SuggestionBar.setSuggestionsWithScores` (`SuggestionBar.kt:382`) gains an optional metas
   parameter (stored beside `currentScores`).

**UI — two tiers:**

- **Tier 1: long-press a suggestion → provenance sheet.** `createSuggestionView`
  (`SuggestionBar.kt:120-156`) currently sets only `setOnClickListener` (`:147`); add
  `setOnLongClickListener` → new listener method `onSuggestionInspected(index)` →
  `SuggestionHandler` composes a detail view: origin badge, the `ScoreBreakdown` table, the
  learned-context line ("after 'want', you've typed 'to' 14× → boost 2.1×" — from
  `ContextModel.getProbability` (`ContextModel.kt:182-184`) + `BigramStore` entry frequency),
  and the personalization block straight from `explainBoost()`. Render inside the IME (a
  keyboard-height overlay panel, same pattern as the emoji/clipboard content panes — an IME
  cannot casually launch dialogs), with a "why not X?" affordance later. This works even with
  all debug prefs off — inspection is on-demand and free until invoked (breakdown for the
  *displayed* suggestions only can be recomputed lazily on long-press if we prefer zero
  hot-path cost: the inputs — word, partial, context — are all still known).
- **Tier 2: at-a-glance origin markers (opt-in pref `suggestion_provenance_markers`,
  default off).** Small colored underline/dot per suggestion by origin (e.g. learned-context
  = green, personalization = blue, neural = purple, autocorrect = orange). Renders in
  `bindSuggestionView` (`SuggestionBar.kt:237-289`) — cheap, no layout change, honors themes
  via `theme?` colors. This upgrades `swipe_show_debug_scores` from "numbers for devs" to
  "meaning for users"; keep the numeric mode as-is for devs.

**Also surface the pipeline state, not just per-word data:** a "Prediction Pipeline" screen in
settings (see §3) showing which stages are active (engine mode, context LM on/off + size,
personalization on/off + vocabulary size) — `ContextModel.getStatistics()`
(`ContextModel.kt:212-233`) and `PersonalizationEngine.getStats()`
(`PersonalizationEngine.kt:232-243`) already exist unused.

**Effort: M** (plumbing is mechanical; the sheet UI is the bulk). **Risk: low-medium** — the
one real hazard is hot-path allocation, addressed by the lazy/gated breakdown. Depends on §1
only for the learned-context numbers to be non-trivially populated across sessions.

---

## 3. MAXIMUM USER CONTROL — per-stage toggles, weights, and a learned-data manager

### 3.1 What control already exists (credit where due)

| Control | Where |
|---|---|
| Word prediction master switch | `word_prediction_enabled`, `InputBehaviorSection.kt:57-65` |
| Context-aware toggle | `context_aware_predictions_enabled`, `InputBehaviorSection.kt:138-146` (default ON, `Config.kt:169`) |
| Personalized learning toggle + aggression (Conservative/Balanced/Aggressive) | `InputBehaviorSection.kt:148-179`; engine multiplier `PersonalizationEngine.kt:43-59` |
| **Context boost slider** 0.5–5.0× | `prediction_context_boost`, `InputBehaviorSection.kt:181-192`, applied at `WordPredictor.kt:1787-1791` |
| **Frequency scale slider** 100–5000 | `prediction_frequency_scale`, `InputBehaviorSection.kt:194-205`, applied at `WordPredictor.kt:1780-1781` |
| Engine selection | `swipe_engine_mode` = neural/hybrid/geometric (`Config.kt:620/908`) |
| Autocorrect toggles | `autocorrect_enabled`, `swipe_beam_autocorrect_enabled`, `swipe_final_autocorrect_enabled` (`Config.kt:553/573-574`) |
| Custom/disabled words | Dictionary Manager (`DictionaryManagerActivity.kt`); exported per-language by backup (`BackupRestoreManager.kt:992-1114`) |
| Debug visibility | `swipe_show_debug_scores` / `swipe_show_raw_beam_predictions` (`AdvancedSection.kt:56/87`) |

So the *scoring-weight* story is better than expected — two of the key knobs already exist.

### 3.2 The gaps

1. **No personalization *strength* knob beyond the 3-step aggression enum.** The
   boost→multiplier conversion is hardcoded: `1.0f + (boost / 4.0f)` (`WordPredictor.kt:1771`).
   A `personalization_weight` float (0 = off … 2.0) sliding that divisor gives continuous
   control; the aggression enum can stay as a preset selector on top.
2. **Static-vs-learned context combination is hardcoded `max()`** (`WordPredictor.kt:1761`).
   Users who want *only their own* patterns (or only the shipped bigrams) can't choose. Add
   `context_source` pref: `both` (default, current behavior) / `learned_only` /
   `static_only`. Cheap: three-way branch at `:1742-1761`.
3. **Boost shape constants are frozen** (`ContextModel.kt:37-39`: `MAX_BOOST=5`,
   `BOOST_EXPONENT=2`). Lower priority — exposing `context_max_boost` is enough; don't expose
   the exponent (footgun with no intuition attached).
4. **Config-value drift (bug-adjacent, fix opportunistically):** fallback literals in
   `WordPredictor` disagree with `Defaults` — `prediction_context_boost ?: 2.0f`
   (`WordPredictor.kt:1787`) vs `Defaults.PREDICTION_CONTEXT_BOOST = 0.5f` (`Config.kt:172`);
   `prediction_frequency_scale ?: 1000.0f` (`:1780`) vs default `100.0f` (`Config.kt:173`).
   Harmless while `config` is always set, but the comments ("default: 2.0", "default: 1000")
   are wrong and will mislead the next tuning pass.
5. **THE big gap: no learned-data manager.** The user cannot see, edit, clear, or export what
   the keyboard has learned about them — even though **every needed API already exists and is
   dead code**:
   - `BigramStore`: `exportToJson()` (`:284-296`), `importFromJson()` (`:304-322`), `clear()`
     (`:172-178`), `getStatistics()` (`:334-352`)
   - `ContextModel`: `exportToJson`/`importFromJson`/`clear`/`getStatistics`
     (`ContextModel.kt:189-249`)
   - `UserVocabulary`: `exportToJson`/`importFromJson` (`:222-252`), `clearAll()` (`:209-217`),
     `getTopWords()` (`:151-153`), `getStats()` (`:303-315`)
   - `PersonalizationEngine`: `exportData`/`importData`/`clearAllData`/`getTopWords`/
     `explainBoost` (`:183-307`)
6. **Backup blind spot.** `BackupRestoreManager` exports custom/disabled words
   (`BackupRestoreManager.kt:1069/1088`) but **not** `bigram_store` / `user_vocabulary` /
   `personalization_settings`. A device migration silently discards all learning — same user
   pain as bug §1, at a different lifecycle scale.

### 3.3 Design: "Learning & Data" manager screen

New settings destination (a `ui/settings/` screen or activity, linked from the Advanced
Prediction block at `InputBehaviorSection.kt:136-207`):

- **Overview:** context LM stats (bigram count, context words, top contexts —
  `getStatistics()`), personalization stats (vocab size, most-used, recent —
  `getStats()`), per-language breakdown once §1's keying lands.
- **Browse/edit:** top personal words list (`getTopWords(100)`) with per-word delete
  (needs one new method: `UserVocabulary.removeWord(word)` — trivial) and per-word
  `explainBoost` detail; learned-bigram browser grouped by context word
  (`getAllBigrams(word1)`, `BigramStore.kt:150-153`) with per-entry delete (new
  `BigramStore.removeBigram(w1,w2)` — trivial).
- **Clear:** separate "Forget phrase patterns" (`ContextModel.clear()`) and "Forget word
  usage" (`PersonalizationEngine.clearAllData()`) buttons + a combined reset, each with a
  count-bearing confirmation ("Delete 3,412 learned word pairs?").
- **Export/Import:** SAF file picker → the existing JSON APIs. Also fold both stores into the
  standard backup JSON (new top-level keys, e.g. `learned_bigrams_by_language`,
  `user_vocabulary`) with the import-plan preview showing counts — mirrors the
  `custom_words_by_language` pattern and reuses the `SettingsImportPlanBuilder` machinery.
  Classify any new pref keys in `SETTINGS_DEFAULTS`/`INTERNAL_KEYS` so
  `SettingsDefaultsDriftTest` stays green (bigram/vocab blobs live in their own prefs files,
  outside the drift scanner's scope, but new toggles do not).

**Privacy framing:** this screen is also the honest place for "all learning is on-device;
here is every byte, delete it any time" — turning a compliance nicety into a feature.

**Effort: M** (UI is the bulk; two trivial new store methods). **Risk: low.** Depends on §1
(no point shipping a browser over a store that empties on restart; language keying shapes the
UI). Serves goal (a) directly and (b) partially (the stats/browse views *are* transparency).

---

## 4. OPTIONAL NEXT-WORD PREDICTION — the headline new capability

### 4.1 Today's gap

The context LM is used **only** as a multiplier over candidates generated from a partial word
(`calculateUnifiedScore`, `WordPredictor.kt:1752-1761`) — nothing ever *generates* candidates
from context alone. When the suggestion bar is empty (word just committed, or cursor after a
space), the pipeline is idle: `handleRegularTyping`'s non-letter branch ends in
`predictor.reset()` + `clearSuggestions()` (`SuggestionHandler.kt:1150-1151` and the
space-completion path around `:1222`), and `updatePredictionsForCurrentWord` early-outs when
`getCurrentWordLength() == 0` (`SuggestionHandler.kt:1363`).

Yet the generation API **already exists, fully implemented and unused**:
`ContextModel.getTopPredictions(previousWords, maxResults)`
(`ContextModel.kt:140-163`) → ranked `(word, boost)` pairs from the learned bigram store,
with the trigram upgrade path pre-stubbed (`:146-154`).

### 4.2 Prediction source & ranking

Candidate generation, in blend order:

1. **Learned n-grams (primary):** `contextModel.getTopPredictions(contextWords, N)` — after
   §1 this is persistent and language-keyed; after trigram activation (below) it backs off
   trigram → bigram automatically.
2. **Static seed bigrams (cold-start):** `BigramModel` (hardcoded en/es/fr/de,
   `BigramModel.kt`) currently only exposes `getContextMultiplier(word, context)` (`:426`) —
   add a `getPredictions(context): List<(word, prob)>` accessor over its existing tables so
   day-one users get "the → best/first/most…" instead of nothing.
3. **Personalization re-rank:** multiply each candidate by
   `1 + personalizationEngine.getPersonalizationBoost(word)/4` — same conversion as
   `WordPredictor.kt:1769-1771` (or the §3 weight once it exists).
4. **Filters:** drop words disabled in Dictionary Manager (`isWordDisabled`), drop the word
   just committed (self-repetition), require dictionary membership OR user-vocabulary
   membership (blocks typo'd garbage the bigram store may have absorbed — `recordSequence`
   learns *whatever was committed*, including pre-autocorrect junk).
5. **Ranking score** = `bigramBoost × personalizationMult × log-dampened dictionary
   frequency` — deliberately the same signal family as `calculateUnifiedScore` minus the
   prefix term, so the §2 inspector can show an identical breakdown with origin
   `NEXT_WORD`.
6. **Confidence floor:** show nothing rather than noise — require learned frequency ≥ 2
   (`DEFAULT_MIN_FREQUENCY`, `BigramStore.kt:33`) and probability ≥ ~5% for learned entries;
   static seeds exempt. An empty next-word bar must be a common, acceptable outcome.

**Trigram activation belongs to this phase** (per §1D): implement `TrigramStore` mirroring the
fixed `BigramStore` (same debounced persistence, language keying, prune caps), uncomment the
`ContextModel` trigram paths (`:74-79`, `:109-117`, `:146-154`). Trigrams are what lift
next-word from "plausible filler" to "finishes my sentences."

### 4.3 Preference (default OFF)

- `next_word_prediction_enabled` — `Config.kt` field + `Defaults.NEXT_WORD_PREDICTION_ENABLED
  = false` + load in `refresh()`; switch in the Advanced Prediction block
  (`InputBehaviorSection.kt` after `:146`, gated on `contextAwarePredictionsEnabled` since it
  is meaningless without the context LM); string resources; **classify in
  `SETTINGS_DEFAULTS`** (drift test) and it will ride the standard backup automatically.
- Optional companion (phase 2): `next_word_max_suggestions` (1–5, default 3).
- Default OFF is correct: it changes an idle-bar behavior users may rely on (empty bar =
  nothing pending), and it surfaces learned data proactively — opt-in matches both the
  privacy posture and the user's "optional" requirement.

### 4.4 Exact hook points & UI surfacing

**New method:** `SuggestionHandler.showNextWordPredictions()` — reads
`contextTracker.getContextWords()` (`PredictionContextTracker.kt:265`), runs §4.2 off the UI
thread via the existing `predictionTasks.cancelAndSubmit` executor (same cancellation
semantics as `updatePredictionsForCurrentWord`, `SuggestionHandler.kt:1374`), posts to the bar
via `mainHandler.post` exactly like `:1498-1507`.

**Call sites (all inside `SuggestionHandler` — no new pipeline, honoring the WP9 single-
pipeline invariant):**

1. **After a typed word completes** — the space/punctuation branch of `handleRegularTyping`,
   after `updateContext(completedWord)` (~`:1222`) where today the bar is cleared: call
   `showNextWordPredictions()` instead of leaving it empty (only when the terminator is a
   space/sentence-internal punct; after `.`/`?`/`!` clear context per sentence boundary —
   see 4.6).
2. **After a suggestion tap commit** — end of `onSuggestionSelected` success path (after
   `updateContext(processedWord)`, `:892`).
3. **After a swipe auto-insert** — end of `handleSwipePredictionResults` (`:462`). Nuance:
   the bar deliberately re-displays swipe *alternates* after auto-insert so the user can
   correct the swipe. **Do not replace them.** Recommended composition: keep alternates,
   append up to 2 next-word candidates after a divider; or (simpler v1) skip next-word on the
   swipe path entirely and let the next cursor event trigger it.
4. **Cursor parked after a word+space** — `handleCursorSyncPrediction` (`:1343-1349`)
   currently no-ops when `currentWord` is empty; add: if empty and next-word enabled and
   context is non-empty → `showNextWordPredictions()`. This covers "tap into the middle of
   existing text" like Gboard.

**Guards (reuse the existing ones):** password mode (`isPasswordMode` — same guard as
`:1347`/`:364`), `specialPromptActive`, `isShowingTemporaryMessage`
(`SuggestionBar.kt:389-392`), Termux/terminal fields (same `packageName == "com.termux"`
check as autocorrect, `:1128-1132`), and incognito/no-learning fields if
`no_personalized_learning` IME flags are honored elsewhere.

**Commit path:** tapping a next-word suggestion flows through the **existing**
`onSuggestionSelected` (`:481`) unchanged — auto-space, casing, tracking all inherited. Add
`PredictionSource.NEXT_WORD` to `PredictionSource.kt` and set it as the commit source so
`updateContext` records provenance — which also makes **chained next-word prediction**
(tap "to" → immediately predict after "…want to") fall out of call-site 2 for free.

**Presentation:** reuse `setSuggestionsWithScores` (metas tagged `NEXT_WORD` per §2). Visual
differentiation recommended (Tier-2 provenance marker, or italic via the existing
`bindSuggestionView` styling switch, `SuggestionBar.kt:256-287`) so predicted-ahead words
read differently from completions of typed input. Capitalization: candidates are stored
lowercase (`BigramEntry.normalizeWord`, `BigramEntry.kt:44-46`); apply `capitalizeIWord` +
sentence-start auto-cap (the autocap state machine already knows; mirror `shouldCapitalize`
handling from `:1368/1447-1455`).

**Replacement semantics:** the instant a letter is typed, `handleRegularTyping`'s letter
branch calls `updatePredictionsForCurrentWord()` (`:1117`) which overwrites the bar —
next-word suggestions vanish naturally, zero new state. The only new state is transient:
"bar currently shows next-word candidates," needed so backspace can dismiss them.

### 4.5 Multilingual behavior

- With §1C language keying, generation queries only the **active language's** store
  (`DictionaryManager.getCurrentLanguage()`, same source the swipe path uses at `:394`).
- Static seed fallback exists for en/es/fr/de only (`BigramModel.kt` hardcoded tables);
  other languages are learned-only — cold-start shows nothing, which is correct.
- Language switch mid-text: `contextWords` may contain other-language words; bigram lookup
  simply misses (returns empty) — benign. Auto language detection
  (`tryAutoLanguageDetection`, `WordPredictor.kt:584-615`) is orthogonal.
- Dictionary-membership filter (§4.2-4) uses the active language's dictionary, which also
  keeps cross-language residue (pre-keying legacy data) from surfacing.

### 4.6 Sentence boundaries & privacy

- After `.`/`?`/`!`, context should reset (a learned bigram across a sentence boundary is
  noise); today `recordSequence` happily learns across them since `addWordToContext` never
  clears on punctuation — fixing that during §1 (clear `recentWords` on sentence-final punct
  in the non-letter branch) improves both boost quality and next-word quality.
- Next-word must never fire in password fields (guarded above) and inherits the existing
  cross-app hygiene: `onFinishInputView → contextTracker.clearAll()`
  (`CleverKeysService.kt:822`) means a fresh field starts with no context ⇒ no predictions ⇒
  no cross-app leakage of learned phrases.

### 4.7 Effort / risk

**Effort: M** given §1 is done (generation API exists; the work is call-site wiring, the
seed-bigram accessor, filters, and UI polish). **+M for TrigramStore.**
**Risk: medium** — the suggestion bar is the most contended surface in the app (temporary
messages, special prompts, swipe alternates, exact-add, password mode all share it); the
swipe-path composition (call-site 3) and the "when to clear" rules are where regressions
would live. Mitigate with JVM tests around a pure "should show next-word now?" decision
function + instrumented bar-state tests, and by shipping call-sites 1–2 first, 3–4 second.

---

## 5. PRIORITIZED ROADMAP

| # | Work | Effort | Risk | Depends on | Goal served |
|---|---|---|---|---|---|
| **P0** | **Bigram persistence fix** — debounced write-back, lifecycle flush (`onFinishInputView` / `shutdown` / eviction), singleton + language-keyed store, docstring fixes, `UserVocabulary` save-storm fix, sentence-boundary context clear; round-trip + eviction + migration tests (§1) | **S–M** | Low | — | Foundation for all three; without it, control (§3) manages an amnesiac store and next-word (§4) has no fuel |
| **P1** | **Transparency** — `SuggestionMeta`/`ScoreBreakdown` plumbing, long-press provenance sheet (wires the dead `explainBoost`), opt-in origin markers, pipeline-status stats view (§2) | **M** | Low-Med | P0 (for meaningful learned-context numbers) | (b) directly; builds the metadata rails §4 reuses |
| **P2** | **Control** — Learning & Data manager (browse/edit/clear/export both stores), backup integration, `context_source` selector, `personalization_weight` slider, fallback-literal drift fixes (§3) | **M** | Low | P0 (keyed, persistent store to manage); P1 optional (per-word detail reuses the sheet) | (a) directly; the export/clear surface is also the privacy story |
| **P3** | **Next-word prediction** — pref (default OFF), `showNextWordPredictions()` + call-sites 1–2, static-seed accessor, filters/floor, `PredictionSource.NEXT_WORD`; then call-sites 3–4; then `TrigramStore` activation (§4) | **M** (+M trigrams) | Med | P0 hard; P1 soft (provenance tags); P2 soft (users can prune what it learned) | (c) — the headline; P0–P2 make it persistent, inspectable, and controllable rather than a black box bolted on |

Sequencing rationale: P0 is a genuine bug with a small blast radius — ship it alone and
first. P1 before P2 because the provenance data model is what makes the data manager's
detail views cheap. P3 last not because it matters least but because shipping it *on top of*
persistence + transparency + control is precisely what differentiates this from Gboard: a
next-word predictor the user can watch, tune, edit, and export.

---

### Appendix: minor findings logged along the way

- `WordPredictor.kt:1787` fallback `?: 2.0f` and `:1780` `?: 1000.0f` disagree with
  `Defaults` (0.5f / 100.0f, `Config.kt:172-173`); comments at `:1779/:1786` repeat the
  wrong values.
- `PersonalizationEngine` keeps its own `personalization_enabled` pref
  (`PersonalizationEngine.kt:39/66`) parallel to Config's `personalized_learning_enabled`;
  drift is mitigated because `WordPredictor.setConfig` syncs it (`WordPredictor.kt:439-446`),
  but it's a redundant source of truth worth collapsing during §3.
- `UserVocabulary.saveToPreferencesAsync` spawns an unbounded `Thread` per committed word
  (`UserVocabulary.kt:277-289`) — folded into the P0 debounced-persister work.
- `BigramStore.importFromJson` replays `recordBigram` `frequency` times per entry
  (`BigramStore.kt:313-316`) — O(total frequency) import; fine for small stores, should
  become a direct-merge when the export/import UI (§3) makes imports user-reachable.
