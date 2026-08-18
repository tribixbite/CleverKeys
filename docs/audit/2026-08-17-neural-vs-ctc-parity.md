# Neural → CTC parity audit: what would be LOST if the neural engine is deleted and the geometric fallback removed

**Date**: 2026-08-17
**Type**: read-only architectural audit (no code changed)
**Scope**: `swipe_engine_mode = ctc` becoming the only engine — deleting `NeuralSwipeTypingEngine`
/ `SwipePredictorOrchestrator` / `OptimizedVocabulary`, and deleting the geometric fallback that
`SwipeEngineRouter` + `InputCoordinator.performCtcSwipeTyping` currently route to.

## The premise under test

> "CTC is layout and language agnostic and beats geo on every config tested — there should be no
> geo fallback when CTC is selected, and both should work for any layout, any language, INCLUDING
> custom user-imported languages AND completely custom user-generated keyboard layouts."

**Verdict up front: the premise is half true and half unsupported.**

* The *encoder architecture* is genuinely layout- and script-agnostic (key geometry is a runtime
  tensor, `OnnxCtcEmissionModel.kt:19-26`; "the alphabet is data, not architecture",
  CleverKeys-ML `ctc/PHASE_I_DATA.md:317-318`), and within Latin script that is validated.
* But the *shipped artifact* is not universal, and the ML campaign says so explicitly. A joint
  en+ru model was built and **rejected** — en top-1 −0.42 against a 0.3 tolerance, ru a tie at
  best, greedy 37.07 → 23.68 (`ctc/PHASE_J.md:678-710`). The recorded plan is
  "**separate per-script models remain the plan … not one model for everything**"
  (`ctc/APP_INTEGRATION_PLAN.md:2109-2117`).
* The *app code* is NOT script-agnostic: `CtcEngineAdapter` hard-codes an a–z alphabet
  (`CtcEngineAdapter.kt:113`) and `CtcTrieNode` has a hard 26-child ceiling that
  **throws on the 27th distinct child character** (`CtcLexiconTrie.kt:86-95, 115`).
* "Beats geo on every config tested" is **not supported by the recorded numbers**: exactly ONE
  configuration was ever measured on the same dataset for both engines (English QWERTY,
  FUTO test-2400). Every per-language CTC-vs-geo pair cited is on different corpora slices,
  different N, and different lexicon sizes. There are corpora where geo was measured and CTC
  never was — including the one where geo *beat neural* (LOCAL combined, 8,521 traces).
* "Any language" is false today: CTC serves **4 of the 7 bundled dictionary languages** and
  **zero imported langpacks**, by deliberate design (`CtcLanguageSupport.kt:50-73`,
  `CtcEngineAdapter.kt:344-361`).
* Removing the geometric fallback under `ctc` mode would delete swipe typing entirely for
  **36 of the 83 bundled layouts** (16 non-Latin scripts) and for every imported langpack.
* And closing that gap is blocked on **data, not effort**: no real human swipe corpus exists in
  **any** non-Latin script under a clean licence (`ctc/DATASET_SCOUT.md:420-431`), and the
  synthesis method that substitutes for one explicitly disclaims validity for scripts whose
  writing direction or key density departs further from QWERTY than ЙЦУКЕН's
  (`ctc/cyrillic_synth.py:38-41`) — which excludes Arabic, Hebrew and Devanagari.

---

# 1. Feature parity — what the neural pipeline owns that CTC does not

Legend: **COVERED** = CTC has a working equivalent. **PARTIAL** = something equivalent exists
but with materially different behaviour or reach. **MISSING** = nothing on the CTC side.

## 1.1 `OptimizedVocabulary` — candidate filtering and re-ranking

`OptimizedVocabulary.filterPredictions` (`OptimizedVocabulary.kt:347-963`) is the neural
engine's entire post-beam stage. CTC has no equivalent stage at all: `CtcBeamDecoder.decode`
scores inside the beam (`ctc/CtcBeamDecoder.kt:154-171`) and `CtcEngineAdapter.toPredictionResult`
(`CtcEngineAdapter.kt:708-716`) only softmaxes the final scores. Item by item:

| # | Neural behaviour | Where | CTC equivalent | Verdict | User-visible consequence of the gap |
|---|---|---|---|---|---|
| 1 | Combined score `confW·conf + freqW·freq`, boosted by tier (`0.6/0.4` defaults, user-tunable to `0.80/0.20·0.57` etc.) | `OptimizedVocabulary.kt:81-85, 521-526`; `VocabularyUtils.calculateCombinedScore` | In-beam `ctc/len^γ + β·len + λ·ln(freq)` | **COVERED (different math)** | None per se — but the two are not interchangeable, and none of the neural tuning sliders map onto λ/γ/β. |
| 2 | Tier boosts: tier 2 (top-100) ×1.3, tier 1 (top-3000) ×1.0, tier 0 ×0.75 rare penalty | `OptimizedVocabulary.kt:489-519, 1027-1033` | CTC has a single continuous `λ·ln(freq)` term, no tiers | **PARTIAL** | Rare-word suppression is smooth instead of stepped. Probably fine; not measured either way. |
| 3 | Per-length minimum-frequency thresholds | `OptimizedVocabulary.kt:1097-1113, 500-515` | none | **MISSING (low impact)** | The hard-coded table (1e-4…1e-9) is inert against the 0.001 frequency floor; the *live* threshold is `autocorrect_confidence_min_frequency/10000` (default 0.01), which does drop bottom-of-scale words. CTC keeps them. |
| 4 | Disabled-word filtering (Dictionary Manager) | `OptimizedVocabulary.kt:416-419, 660-663` | Disabled words removed at trie-build time — `CtcLexiconMerge.kt:40-53`; adapter reads `LanguagePreferenceKeys.disabledWordsKey` at `CtcEngineAdapter.kt:375` | **COVERED** | — |
| 5 | Custom (user) words merged into candidates | `OptimizedVocabulary.kt:559-609` (fuzzy match against raw beam) | Custom words merged into the trie, freq clamped 1..255, custom overrides disabled — `CtcLexiconMerge.kt:43-47`, `CtcEngineAdapter.kt:412-427` | **COVERED (stronger on CTC)** | CTC can *decode* a custom word directly; neural could only fuzzy-rescue it. |
| 6 | **Custom-word fuzzy autocorrect**: a custom word that is *not* a beam output gets injected if it fuzzy-matches a raw beam candidate (cubic match-quality × tier boost) | `OptimizedVocabulary.kt:555-609` | none | **MISSING** | A user custom word only appears if the CTC beam actually spells it. Mostly redundant (see 5), but the "I swiped sloppily and my custom word rescued it" behaviour is gone. |
| 7 | **Main-dictionary fuzzy rescue**: when <3 candidates survive, rejected beam outputs are fuzzy-matched against length-bucketed vocabulary, best match wins, 0.8× rescue penalty | `OptimizedVocabulary.kt:611-746` | none | **MISSING** | On a bad swipe the neural bar could still offer *something* plausible. CTC's bar is simply whatever the trie beam found — possibly nothing. This is the single largest behavioural deletion. |
| 8 | Start-letter enforcement (`autocorrect_prefix_length` + re-check after fuzzy rescue) | `OptimizedVocabulary.kt:403-413, 711-724` | none | **MISSING** | — |
| 9 | `neural_strict_start_char` hard constraint in the beam | `onnx/BeamSearchEngine.kt:136`, `SwipePredictorOrchestrator.kt:451, 479` | none | **MISSING** | A user pref that stops existing. |
| 10 | Contraction key injection at `WordInfo(0.88f, tier 2)` (the "big one" in the brief) | `OptimizedVocabulary.kt:460-466` | `CtcContractionKeys.inject(...)` at `INJECTED_FREQUENCY = 1.0`, the bottom of the scale — `swipe/ctc/CtcContractionKeys.kt:47-89`, wired at `CtcEngineAdapter.kt:465` | **COVERED — and CTC's choice is the better one** | The neural 0.88/tier-2 path only fires when English fallback is OFF (`OptimizedVocabulary.kt:452-466`); the in-vocabulary injection is itself now a floor (`CONTRACTION_ALIAS_FREQUENCY = 0.001f`, `OptimizedVocabulary.kt:46, 2073-2074`), so the two engines already agree. |
| 11 | Paired-contraction variants filtered by "did the NN actually predict the apostrophe-free form" | `OptimizedVocabulary.kt:761-804` | `ContractionOverlay.apply` — paired-first keep+variant, junk-alias replace (`swipe/ContractionOverlay.kt`, `CtcEngineAdapter.kt:593-616`) | **COVERED** | — |
| 12 | Non-paired real-word guard `frequency > 0.65f` (v1.2.2 "quest"+"qu'est") | `OptimizedVocabulary.kt:811-865` | Ordinal-rank guard `REAL_WORD_ORDINAL_MAX = 1200` over the merged lexicon (`ctc/CtcLexiconMerge.kt:73-89`) | **COVERED (different mechanism, measured separation documented)** | — |
| 13 | Accent recovery via `NormalizedPrefixIndex` ("cafe"→"café"), tier-1, freq from `1 - rank/255` | `OptimizedVocabulary.kt:428-447` | a–z projection with a canonical-display map (`ctc/CtcAzProjection.kt:89-108`, `CtcEngineAdapter.kt:583-590`) | **PARTIAL** | Two real regressions: (a) words with **no a–z decomposition are DROPPED**, not folded — German `ß`, and `œ æ ø` (`CtcAzProjection.kt:26-30, 45-55`); (b) on an accent collision only the **highest-frequency** canonical form is reachable, so French `à` is unreachable when `a` outranks it. |
| 14 | Expected-length filtering (±2 chars) | `OptimizedVocabulary.kt:958-962, 1118-1130` | in-beam `β·len` length bonus | **PARTIAL** | — |
| 15 | `neural_frequency_weight` multiplier (0.0 = NN only … 2.0 = heavy freq) exposed in Neural Settings | `OptimizedVocabulary.kt:110, 525` | `ctc_beam_width` only (`Config.kt:686`); λ/γ/β are compile-time constants | **MISSING** | The whole Neural Settings tuning surface stops affecting swipe. |
| 16 | The entire **Auto-Correction settings section** feeding swipe: `autocorrect_prefix_length`, `_max_length_diff`, `_char_match_threshold`, `_min_word_length`, `_max_beam_candidates`, `_confidence_min_frequency`, `_use_edit_distance`, `swipe_autocorrect_enabled` | `OptimizedVocabulary.kt:206-215`; UI `ui/settings/sections/AutoCorrectionSection.kt:59-197` | none | **MISSING** | Eight user-facing sliders become no-ops for swipe (they still affect the *typed-word* autocorrect via `WordPredictor.autoCorrect`). |

## 1.2 Secondary-language blending

| Item | Where | CTC | Verdict |
|---|---|---|---|
| Secondary dictionary candidates injected into the swipe slate, scored `conf·0.6 + rankScore·0.3·langMultiplier` | `OptimizedVocabulary.kt:879-938` | none | **MISSING** |
| `_currentLanguageMultiplier` driven by live language detection over committed words | `OptimizedVocabulary.kt:1540-1580`; fed by `SwipePredictorOrchestrator.trackCommittedWord` `onnx/SwipePredictorOrchestrator.kt:704-716`, called from `SuggestionHandler.kt:1529` | none | **MISSING** |
| `pref_secondary_prediction_weight` slider | `Config.kt:609, 909`; UI `ui/settings/sections/MultiLanguageSection.kt:113-120` | affects the **tap** path only (`WordPredictor.kt:1852-1853`) | **MISSING for swipe** |

**Consequence**: a bilingual user (Primary=French, Secondary=English) currently gets English words
in the swipe slate on a QWERTY layout. Under CTC they get **one language's trie only**
(`CtcEngineAdapter.lexiconFor` `:363-474`, single `trieMemo` slot keyed by language). The geometric
engine has the same limitation, so this is lost the moment neural goes — regardless of the geo
fallback question. `swipe_engine_mode = ctc` today already silently drops it for en/fr/de/es users.

Also note: the language *detector* itself lives inside the neural orchestrator. Deleting neural
deletes auto-switch language detection (`SwipePredictorOrchestrator.kt:686-738`) unless it is
relocated — and `SuggestionHandler.kt:1529` calls into it on **every** committed word, including
CTC and geometric commits.

## 1.3 Personalization / adaptation — traced end to end

Confirmed by tracing `InputCoordinator.handlePredictionResults` (`:403-427`) →
`SuggestionHandler.handleSwipePredictionResults` (`:471-612`):

| Feature | Applied to swipe results? | Engine-conditional? | Cite |
|---|---|---|---|
| `SuggestionOrigin` (NEURAL_BEAM / GEOMETRIC / CTC) | tag only | **no functional branch anywhere** — only the bar dot colour (`SuggestionBar.kt:405-414`), the long-press label (`SuggestionProvenance.kt:217-227`), and a score-breakdown skip (`SuggestionHandler.kt:333`) | `SuggestionHandler.kt:525-528` |
| Possessive augmentation ('s) | yes, **en only** | no | `SuggestionHandler.kt:513-519, 2160-2194` |
| Password-field guard | yes | no | `SuggestionHandler.kt:485-492` |
| Shift / caps-lock transform | yes | no | `SuggestionHandler.kt:502-506` |
| User-dictionary case restoration ("boston"→"Boston") | yes | no | `SuggestionHandler.kt:502-503` → `WordPredictor.kt:598-600` |
| Final autocorrect on the swipe auto-insert | yes | no | `SuggestionHandler.kt:813-830` |
| ML trace capture (engine + layout tagged) | yes | no | `InputCoordinator.kt:588-625, 662, 766, 868` |
| Next-word candidates appended after the slate | yes | no | `SuggestionHandler.kt:609, 629-670` |
| **`UserVocabulary` / `PersonalizationEngine` boosts** | **NO — tap path only** | n/a | read sites are only `WordPredictor.kt:796, 805, 1984-1988` |
| **`UserAdaptationManager` / `SelectionHistory` multipliers** | **recorded on swipe commit, never APPLIED to a swipe slate** | n/a | record `SuggestionHandler.kt:837-841`; apply only `WordPredictor.kt:576, 1956-1960, 2126-2127` |
| **Context-LM bigram/trigram boost** | **NO — tap path only** | n/a | `WordPredictor.kt:1962-1977` |

**Verdict — this is good news for the migration.** Personalization and context-LM never reached
*any* swipe engine, neural included. Deleting neural loses **nothing** here. The one caveat is
item 1.2: the language-detector feed is neural-owned and is called on every commit.

## 1.4 Neural-only prefs with real behaviour

| Pref | Behaviour | Cite | CTC |
|---|---|---|---|
| `neural_prefix_boost_multiplier` / `_max` / `neural_max_cumulative_boost` | Aho-Corasick prefix-boost trie applied inside the beam; **loaded from an imported langpack** (`getPrefixBoostPath`) or assets | `onnx/SwipePredictorOrchestrator.kt:111-112, 150-151, 474-479, 586-605`; `onnx/BeamSearchEngine.kt:41-46, 353-356, 441-467` | **MISSING** — and a langpack data file becomes dead |
| `neural_strict_start_char` | hard first-character constraint | `onnx/BeamSearchEngine.kt:136` | **MISSING** |
| `neural_resampling_mode`, `neural_user_max_seq_length` | trajectory resampling strategy | `Config.kt:706-707` | **MISSING** — CTC's resampler is fixed FUTO two-stage 60 Hz→64 (`ctc/CtcFeaturizer.kt:58-122`) |
| `neural_beam_width`, `_max_length`, `_beam_alpha`, `_beam_prune_confidence`, `_beam_score_gap`, `_adaptive_width_step`, `_score_gap_step`, `_temperature`, `_greedy_search`, `_batch_beams`, `_confidence_threshold` | beam tuning | `Config.kt:672-702` | **MISSING** — CTC exposes only `ctc_beam_width` |
| `finger_occlusion_offset` (Gesture Tuning → touch Y-offset as % of row height) | shifts the trace before inference | `NeuralLayoutHelper.kt:277-285` → `SwipeTrajectoryProcessor.kt:366` | **MISSING** — CTC normalizes raw view px over the letter-key bounding box with no offset (`CtcEngineAdapter.kt:673-675`); geometric does not apply it either |

**A whole settings screen** (`NeuralSettingsActivity.kt`, `ui/settings/sections/NeuralPredictionSection.kt`)
and most of the Auto-Correction section become inert for swipe.

## 1.5 What deleting neural *gains*

* **10.29 MB of assets**: `models/swipe_encoder_android.onnx` (5,317,537 B) +
  `models/swipe_decoder_android.onnx` (4,975,510 B). CTC's encoder is 3,052,318 B.
* **~30 MB Java heap** at IME startup — already documented as a contributor to 9 fatal startup
  OOMs, which is why `PredictionCoordinator.shouldPreloadNeuralEngine` (`:213-243`) exists.
* Removal of `OptimizedVocabulary`'s ~231k-node trie + the 98k word map.

This is a real and legitimate motivation. The question is only what has to be built first.

---

# 2. Is CTC genuinely universal?

## 2.1 Scripts

**The model is architecturally script-agnostic. The app code is not, and the shipped weights are
Latin-trained.**

Architecture (confirmed in both repos):

* `log_emissions [1, 32, 65]` — 64 **key slots** + blank at column 64
  (`swipe/OnnxCtcEmissionModel.kt:19-26, 43-44`; ML `model.py:44-46`, `export_onnx.py:141-149`,
  opset 17, fully static).
* Key identity comes from the runtime tensors `layout_keys [1,64,2]` + `layout_mask [1,64]`
  (`OnnxCtcEmissionModel.kt:21-23`, built by `ctc/CtcFeaturizer.buildPaddedLayout` `:182-193`).
  ML side: "the key embedding is a function of key geometry only, **never of slot index**"
  (`model.py:32-34`); slot-permutation invariance measured at **≤3.815e-06**
  (`ALT_LAYOUT_EVAL.md:369-374`); the ru ONNX is byte-size-identical to every Latin resbn80
  artifact — "**the alphabet is data, not architecture**" (`PHASE_I_DATA.md:317-318`).
* `CtcEmissions.sliceFromHead` maps slot *c* → `alphabet[c]` for whatever alphabet is supplied
  (`ctc/CtcEmissions.kt:65-85`).
* Alphabet sizes already exercised in the ML repo: en 26, azerty/dvorak/spanish **27**,
  german **29**, toki-pona 14, `ru_jcuken_default` **31**, `ru_jcuken_extra` **33** — all inside
  the 64-slot ceiling.

So there is **no baked a–z alphabet in the graph, and the architecture is genuinely
script-agnostic.** The premise is correct at the model level. Everything that blocks non-Latin is
in the app, in the training data, or in the evidence policy:

| Blocker | Kind | Where | What it needs |
|---|---|---|---|
| `ALPHABET = CharArray(26) { 'a' + it }` — the only alphabet the adapter can build | **app-code** | `CtcEngineAdapter.kt:113`, used at `:297, 439, 447` | Make the alphabet come from the layout, not a constant. S. |
| `buildMappedLayout` accepts only `'a'..'z'` center labels and returns null unless all 26 are present | **app-code** | `CtcEngineAdapter.kt:249-301` (`letterOf` `:249-259`, gate `:288`) | Generalize to "the layout's letter set, ≤64 keys". S–M. |
| `letterOf` requires a **single-char** label | **app-code** | `CtcEngineAdapter.kt:249-259` | Blocks Devanagari conjuncts, Hangul jamo, any multi-codepoint key. M. |
| **`CtcTrieNode.MAX_CHILDREN = 26` — a node's child array is capped at 26 and `addChild` writes past the end on the 27th distinct child** | **app-code (hard crash)** | `ctc/CtcLexiconTrie.kt:86-95` (`grown = minOf(chars.size*2, MAX_CHILDREN)`) + `:115` | For any alphabet >26 the **root node alone** will exceed this → `ArrayIndexOutOfBoundsException` on trie build. Affects Cyrillic (33), Arabic (28), Armenian (38), Georgian (33), Devanagari (48+). Greek (24) and Hebrew (22) are under the cap. Trivial fix (S) but it means "just pass a bigger alphabet" is not a one-liner. |
| `MAX_KEYS = 64` slot capacity | **model** | `ctc/CtcFeaturizer.kt:26`, `OnnxCtcEmissionModel.kt:51-55` | Hard ceiling on alphabet size. Fine for Cyrillic/Greek/Arabic/Hebrew; not for Devanagari-class scripts. Requires a re-export to change. |
| 32 output frames | **model** | `OnnxCtcEmissionModel.kt:24` | Max decodable word length ≈32 characters. Tight for long German compounds. |
| Shipped weights are Latin-trained | **model** | ship model = CleverKeys-ML `phaseM_kd_fresh_w1_s1234_fp16w.onnx`, 3,052,318 B (matches the shipped asset byte-for-byte), `CtcEngineAdapter.kt:98-99` | See §2.1.1. The campaign's **eleven bars are 5 English val strata + 6 Latin-script layouts**; Cyrillic was tracked entirely separately and **never entered the bar set** (CleverKeys-ML `ctc/PHASE_K.md:15-20`). |
| **The test seal is spent — permanently** | **validation process** | CleverKeys-ML `ctc/PHASE_M.md:479-498` ("ledger 3 → 4; there is no fifth") | Any *new* CTC configuration — a multi-script alphabet, a langpack lexicon, a re-swept preset — can only ever be **val-validated**. The project's own "TEST-VALIDATED" tier is unreachable for anything built after this point. |
| No non-Latin lexicon is bundled | **lexicon** | `dictionaries/` holds en/de/es/fr/it/pt/sv only — all Latin | A non-Latin language needs a langpack, which CTC cannot read (§2.3). Also a per-script **projection policy** — ru's is lowercase + strip `-`/`'` + ё→е + ъ→ь and **no NFD** (`eval_cyrillic.py:13-15`), i.e. `CtcAzProjection` is Latin-specific and would need a sibling. |
| **No real human swipe corpus exists in ANY non-Latin script under a clean licence** | **data** | ML `DATASET_SCOUT.md:420-431` — Arabic, Hebrew, Greek, Thai, Hangul, Devanagari, Japanese, Chinese pinyin all searched, all empty; "synthesis is the multi-script path" | The one real Cyrillic corpus (Yandex Cup 2023) is **eval-only by licence** (`PHASE_I_DATA.md:196-199`). |
| **Synthesis — the only path — is explicitly bounded and excludes RTL and dense scripts** | **data/method** | `cyrillic_synth.py:38-41`: it "CANNOT" model "per-script motor idiosyncrasies … **nothing about scripts whose writing direction or key density departs further than ЙЦУКЕН's from QWERTY's**" | Arabic/Hebrew (RTL) and Devanagari (density) are outside the stated validity of the only available method. |
| No non-Latin validation exists | **validation** | `docs/specs/ctc-swipe-engine.md` evidence tier; the only Cyrillic datapoint is **val-only, permanently** (`ctc/CtcScoringParams.kt:193-196`) | A full sweep + accuracy bar per script. |
| Training harness: the in-train beam validator's vocab loader is **a–z-hardcoded** | **tooling** | `PHASE_I_DATA.md:238-245` (ru trains with `--beam-val-rows 0`, greedy selection; the lexicon beam runs offline in `eval_cyrillic.py`) | Per-script training works but loses in-training beam selection. |

### 2.1.1 What the ML campaign actually measured for a second script

This is the part of the premise that the evidence contradicts most directly. CleverKeys-ML
`ctc/PHASE_J.md` ran the "one universal model for all scripts" experiment and **rejected it**.

**§6.8 `phaseJ-joint`** — base recipe + **1,000,000 synthetic ru rows** on the `ru_jcuken`
geometry, **one 65-wide head serving both scripts**, no Yandex training rows
(`PHASE_J.md:678-707`):

| axis | joint en+ru model | reference (script-specific) | Δ |
|---|---|---|---|
| ru in-dict top-1 (real Yandex val, n = 8,471) | 76.56 | 76.21 (`phaseIB-ru-synth` bar) | +0.35 (inside 1 binomial SE, ±0.46) |
| ru top-3 / top-5 | 88.16 / 91.12 | 88.53 / 91.42 | **−0.37 / −0.30** |
| **ru greedy** | **23.68** | **37.07** | **−13.39** |
| **en val top-1** | **87.90** | 88.32 (`resbn192i` s1234) | **−0.42** (stated tolerance: 0.3) |
| en t3 / t5 / ≤3 / 4+ | 92.49 / 93.24 / 90.50 / 86.55 | 92.70 / 93.25 / 91.21 / 86.83 | −0.21 / −0.01 / −0.71 / −0.28 |

Verbatim verdict (`PHASE_J.md:701-707`):

> "the joint model does not beat the Cyrillic bar, and it fails the en tolerance. … Two scripts in
> one 65-wide head is demonstrably *feasible* — and the greedy collapse (37.07 → 23.68) shows what
> it costs: the shared head's per-slot emissions get much blurrier, and only the lexicon beam hides
> it. **Not adopted.**"

**The ML repo's own stated plan is the opposite of "one universal model"**
(`APP_INTEGRATION_PLAN.md:2109-2117`):

> "Verdict: feasible, not adopted — **separate per-script models remain the plan** … the
> multi-language route is **a per-script model plus a per-language preset**, not one model for
> everything."

(Also `PHASE_I_DATA.md:374`: "one model per script".)

Further facts that bound the claim:

* **Cyrillic was never one of the campaign's bars.** The eleven bars are 5 English val strata + 6
  **Latin-script** layouts; ru was tracked separately throughout (`PHASE_K.md:15-20`) and Phase K/L/M
  leave it untouched (`PHASE_K.md:4-5, 205, 372-373, 505-506`). Cyrillic is logged as an unbeaten
  "stone" at the close of Phase J (`PHASE_J.md:965-978`).
* **Scaling made Cyrillic worse, not better.** `phaseJ-ru192` at ch 192 / 188 k scored
  **73.53 / 86.80 / 90.17** against ch 80 / 94 k's **76.21 / 88.53 / 91.42** — overfitting to the
  synthetic generator, with `last.pt` (73.30) ruling out a checkpoint-selection artefact
  (`PHASE_J.md:500-535`).
* **The shippable Cyrillic model is 100 % synthetic, and pays ~13 points for it.** A paired
  real-trained arm reached **89.64 / 95.82 / 96.97**; the synth-trained arm that could actually ship
  reached **76.21 / 88.53 / 91.42** — a **−13.4 pt in-dict top-1** gap
  (`PHASE_I_DATA.md:322, 350, 358-363`). λ 2.0 lifts the shippable model to **≈77.4**
  (`PHASE_J.md:766-789`). The real corpus (Yandex Cup 2023, 6 M rows) is **eval-only by licence**
  (`PHASE_I_DATA.md:196-199`).
* **All Cyrillic numbers are val-only, permanently** — and the reason is stronger than "the seal
  is spent" (corrected 2026-08-18): `ctc/test2400_seal.json` holds only **two** sealed splits,
  `test-2400` and `futo-test-49970`, and **both are English**. No sealed Cyrillic split has ever
  existed, so no Cyrillic model could have been test-decoded regardless of ledger state
  (`CtcScoringParams.kt:193-196`, `PHASE_M.md:479-498`, `MODELS_TABLE.md:798-800`).

**Implication for the app**: script coverage means **per-script models plus a model-selection
layer**, plus a per-script footing/λ decision, plus a per-script lexicon and projection policy.
The good news is that the marginal cost is small: the ru model is the standard resbn80 graph at
**1,142,727 B** (~0.55 MiB as fp16w) (`PHASE_I_DATA.md:371-373`), synthesis runs at ~1,141 rows/s
(≈15 min per 1 M rows), and training is **29.1 min wall at ch 80 / 94 k, ~1.5 GPU-h for 3 seeds**
(`ALT_LAYOUT_EVAL.md:504-507`) — not the ~10 GPU-h the ch 192 coupled-pair recipe costs
(`PHASE_L.md:178-180, 613-615`).

The bad news is upstream of effort: per `PHASE_L.md:186-192` a new script needs **a new geometry
and a donor corpus**, not just a wordlist (the ru and en synthesizers share their warp machinery
precisely because both targeted canonical QWERTY geometry) — and per `DATASET_SCOUT.md:420-431`
**no clean-licence real corpus exists in any non-Latin script at all**, with synthesis explicitly
disclaiming validity for scripts whose writing direction or key density departs further from QWERTY
than ЙЦУКЕН does (`cyrillic_synth.py:38-41`).

Even at ≈77.4 top-1, Cyrillic sits **~12 points below** the English number the "CTC beats
everything" claim rests on — and geometric's (synthetic) ru/JCUKEN TYPICAL top-1 is **91.3**
(`docs/specs/geometric-swipe-engine.md:599-605`). The two are not measured on comparable data, but
there is certainly no evidence that CTC would beat geometric on Cyrillic.

### 2.1.3 ~~An open footing question~~ — RESOLVED 2026-08-18, and it found a different error

**The slot-mismatch suspicion is REFUTED.** This section originally warned that the ML repo's
layout geometries carry 27 letter slots for azerty/dvorak/spanish and 29 for german while
`CtcEngineAdapter.buildMappedLayout` always builds exactly 26, and asked which configuration the
four bars were decoded at. Answer: **the `az26` arm — 26 slots, matching the app exactly.**
`ctc/ALT_LAYOUT_EVAL.md:189-200` defines both arms and states `az26` "matches the training regime
exactly: 26 active slots"; the `full` arm was measured and buys nothing (dvorak +0.05, azerty
+0.10, german 0.00, spanish −0.23, `:303-311`), concluding "the `az26` arm is used for every
headline number." The `MODELS_TABLE.md` audit makes this binding registry-wide (`:831`, `:132`,
`:397`, `:422`, `:475`, `:539`). Our own λ sweep already runs `az26`
(`scripts/ctc_lang_lambda_sweep.py:46-47`).

**But checking it surfaced a real error: the numbers named the wrong model.** azerty 83.81 /
qwertz 83.01 / german 80.64 / spanish 88.45 and dvorak 89.87 / 88.98 are **`sw2345`**'s
(`MODELS_TABLE.md:139`) — a superseded Phase-J model that was *never decoded on test* — not the
shipped `phaseM_kd_fresh_w1_s1234_fp16w`'s (`:113`), which are **azerty 84.53 / qwertz 83.97 /
german 81.30 / spanish 89.53** (euro-mean 84.83) and **dvorak 91.82 / dvorak-app 91.10**. The
error was conservative — every corrected value is higher — but the citation was wrong. Corrected
at `CtcLanguageSupport.kt`, `SwipeEngineRouter.kt` and `docs/specs/ctc-swipe-engine.md`.
The campaign **bars** these clear are a third set again: azerty 83.60 / qwertz 82.50 / german
79.64 / spanish 88.28 (`MODELS_TABLE.md:132-136`).

### 2.1.2 Practical reading

Even with every app-code blocker fixed, shipping Cyrillic starts with a **footing decision**, not a
λ lookup: the app preset `tunedV2` (γ 0.9 / β 0.25 / γ_prune 0.25) and the benchmark preset `E1`
(γ 1.05 / β 0.2 / γ_prune 0.3734) the ru sweep was run on **disagree on three of five constants**,
and neither sweep transfers to the other's base — this is stated in the code itself
(`ctc/CtcScoringParams.kt:167-204`).

## 2.2 Custom user layouts

Custom layouts are user-supplied XML parsed by `KeyboardData.parse_keyboard`
(`KeyboardData.kt:532-567`) via `prefs/LayoutsPreference.CustomLayout.parse` (`:165-177`).

Routing depends on two attributes that a hand-written layout may simply omit:

| Custom-layout case | Router result | Dispatch result | Works today? |
|---|---|---|---|
| `script="latin"`, all 26 a–z as **center** labels | `Engine.CTC` (`SwipeEngineRouter.kt:135`) | `supportsLayout` true → CTC | **Yes** — CTC serves it |
| `script="latin"`, one letter missing or only on a **corner** | `Engine.CTC` | `supportsLayout` false (`CtcEngineAdapter.kt:288`) → geometric (`InputCoordinator.kt:756-762`) | Only via geometric |
| `script="latin"`, a letter **duplicated** | `Engine.CTC` | first occurrence wins, row-major (`CtcEngineAdapter.kt:279-280`) → CTC | Yes, silently picking one |
| **`script` attribute absent** (`script == null`) | `isLatinScript(null) == false` → `Engine.GEOMETRIC` (`SwipeEngineRouter.kt:135-136, 140-141`) | geometric | **CTC never sees it** |
| `script="cyrillic"` / any non-Latin | `Engine.GEOMETRIC` | geometric | CTC never sees it |
| `name` absent (`name == null`) | falls to the Latin-script branch → CTC if `script="latin"` | CTC | Yes |

**Two findings the maintainer should know:**

1. A custom layout that omits `script="latin"` gets **geometric only** today and would get
   **nothing** if the fallback were removed. There is no diagnostic anywhere telling the author
   that the missing attribute cost them the engine.
2. `KeyboardGeometry.computeKeyRects` reads only `key.keys[0]` — the **center** symbol
   (`a11y/KeyboardGeometry.kt:188-202`). Letters that exist only as corner/swipe symbols do not
   count. Two *bundled* layouts already fail on exactly this (§3).

**Unvalidated risk**: alt-layout validation covered dvorak / azerty / qwertz / german / spanish —
all conventional 3-row staggered grids. A genuinely custom geometry (4 rows, split, circular,
oversized keys) is inside the model's *input contract* but outside anything ever measured. CTC will
return *a* slate; whether it is good is unknown, and unlike geometric there is no
"I can't serve this" signal — `supportsLayout` only checks letter completeness, never plausibility.

## 2.3 Custom user-imported languages (langpacks)

**Today an imported langpack gets zero CTC.** `CtcEngineAdapter.lexiconFor` reads bundled assets
only — `dictionaries/en_enhanced.json` or `dictionaries/<lang>_enhanced.bin`
(`CtcEngineAdapter.kt:363-410`, table in `ctc/CtcLanguageSupport.kt:97-104`) — and
`supportsLanguage` gates on the fixed 4-row `SUPPORTED` table (`CtcLanguageSupport.kt:50-55`).

By contrast **both other engines do read langpacks**:

* geometric: `File(context.filesDir, "langpacks/$lang/dictionary.bin")`
  (`swipe/GeometricEngineAdapter.kt:516-533`);
* neural: `LanguagePackManager.getDictionaryPath(language)`
  (`OptimizedVocabulary.kt:1265-1286`), plus the langpack prefix-boost trie
  (`onnx/SwipePredictorOrchestrator.kt:586-605`).

What CTC would need, per axis:

| Need | Kind | Why | Effort |
|---|---|---|---|
| A langpack lexicon loader (read CKDT `dictionary.bin` from `filesDir/langpacks/<code>/`) | **app-code** | mechanical — `CkdtDictionaryReader` is already imported by the adapter (`CtcEngineAdapter.kt:34`) | **S** |
| A per-scale λ | **already solved for the CKDT scale** | the langpack `dictionary.bin` uses the same `255 − rank` scale as the bundled `.bin` files, and λ = 2.0 is fitted for exactly that scale (`ctc/CtcScoringParams.kt:116-129`, `CtcCkdtLexicon`) | **none** |
| Removing the fixed `SUPPORTED` table gate | **app-code + policy** | today "supported" means "we measured it"; a user-imported language can never have been measured | **S code / L policy** |
| Per-language accuracy validation | **validation** | the enablement rule requires *both* a model bar and a λ sweep (`CtcLanguageSupport.kt:12-25`), and the corpus does not exist for it/pt/sv, let alone arbitrary user languages | **L, possibly impossible** |
| Non-Latin langpack support | **app-code + model + validation** | everything in §2.1 | **L** |

**The brief's premise that the CKDT-scale λ is the blocker is not right.** λ for the CKDT scale is
already fitted and shipping (fr/de/es use it). The real blockers for langpacks are (a) a ~20-line
loader that was never written, and (b) the project's own **evidence policy**, which says a language
ships only when it has been measured — a policy that is fundamentally incompatible with
"any user-imported language". One of the two has to give.

An important second-order effect: for **English**, CTC deliberately ignores an installed `en`
langpack and always reads the bundled JSON (`CtcEngineAdapter.kt:344-352`). So an English user who
imported a better/larger English dictionary sees it on the tap path and on geometric, but **not** on
CTC. That divergence is documented as a known limitation but is invisible to the user.

---

# 3. Can the geometric fallback be removed under `ctc` mode?

## 3.0 Framing: `ctc` is not the default today

`Defaults.SWIPE_ENGINE_MODE = "neural"` (`Config.kt:320`). Out of the box a user gets neural on
QWERTY-Latin and **`Engine.NONE` — no swipe at all — on every other layout**
(`SwipeEngineRouter.kt:136`). So switching the default to `ctc` is, on its own, a large coverage
*win*: 15 Latin non-QWERTY layouts and 36 non-Latin layouts go from "no swipe" to CTC/geometric
respectively. That change should be decoupled from the deletion question — it is safe today and
does not require deleting anything.

## 3.1 The layout inventory (measured, not estimated)

Scan of `srcs/layouts/*.xml` (83 layouts with a `script` attribute), counting center-label letters
(`c=` / `key0=`), which is what `KeyboardGeometry.computeKeyRects` + `CtcEngineAdapter.letterOf`
actually see:

* **47 Latin** layouts, of which **45 expose all 26 a–z as center keys** → CTC-servable.
* **2 Latin layouts are a–z incomplete**: `latn_qwerty_az.xml` and `latn_qwerty_tly.xml` both put
  `w` only on a *corner* (`key4="w"`), so `supportsLayout` returns false and they fall to geometric.
* **30** of the Latin layouts are QWERTY-named → also neural-capable.
* **36 non-Latin** layouts across **16 scripts**: cyrillic ×11, arabic ×5, devanagari ×3,
  persian ×2, bengali ×2, georgian ×2, hebrew ×2, and one each of armenian, greek, gujarati,
  hangul, kannada, shavian, sinhala, tamil, urdu.

## 3.2 Routing table under `ctc` mode — every cell

Routing = `SwipeEngineRouter.route` (`:114-137`) then
`InputCoordinator.performCtcSwipeTyping` (`:713-787`). Language is checked **before** layout
(`InputCoordinator.kt:725`).

| # | Layout | Language | Engine today | Cite | If geo fallback removed (neural also deleted) |
|---|---|---|---|---|---|
| 1 | Latin QWERTY-named, a–z complete (30) | en / fr / de / es | **CTC** | `SwipeEngineRouter.kt:126-128` | **Working** |
| 2 | Latin non-QWERTY, a–z complete (dvorak, colemak, azerty, qwertz, bépo, neo2, bone, workman, …15) | en / fr / de / es | **CTC** | `SwipeEngineRouter.kt:135` | **Working** (validated for dvorak/azerty/qwertz/german/spanish only; bépo/neo2/bone/workman/colemak never measured) |
| 3 | Unresolved SystemLayout (`layout == null`) | en / fr / de / es | **CTC** | `SwipeEngineRouter.kt:118` | **Working** |
| 4 | Latin QWERTY-named | **it / pt / sv** (bundled dicts exist) | **NEURAL** | `InputCoordinator.kt:732-736` | **DEAD BAR** |
| 5 | Latin non-QWERTY | it / pt / sv | **GEOMETRIC** | `InputCoordinator.kt:738-741` | **DEAD BAR** |
| 6 | Latin QWERTY-named | any imported langpack language (nl, pl, tr, …) | **NEURAL** | `InputCoordinator.kt:732-736` | **DEAD BAR** |
| 7 | Latin non-QWERTY | any imported langpack language | **GEOMETRIC** | `InputCoordinator.kt:738-741` | **DEAD BAR** |
| 8 | `latn_qwerty_az`, `latn_qwerty_tly` (Latin, missing center `w`) | en / fr / de / es | **GEOMETRIC** | `CtcEngineAdapter.kt:288` → `InputCoordinator.kt:756-762` | **DEAD BAR** |
| 9 | `latn_qwerty_az`, `latn_qwerty_tly` | az / tly (langpack) | **NEURAL** (QWERTY-named) | `InputCoordinator.kt:732-736` | **DEAD BAR** |
| 10 | **All 36 non-Latin layouts** (cyrillic, arabic, hebrew, greek, devanagari, georgian, armenian, hangul, bengali, tamil, kannada, gujarati, sinhala, urdu, persian, shavian) | any | **GEOMETRIC** | `SwipeEngineRouter.kt:135-136` | **DEAD BAR — total loss of swipe for 16 scripts** |
| 11 | Custom XML, `script="latin"`, 26 center letters | en / fr / de / es | **CTC** | `SwipeEngineRouter.kt:135` | **Working** (geometry unvalidated) |
| 12 | Custom XML, `script="latin"`, 26 letters | any other language | NEURAL if name contains "QWERTY", else GEOMETRIC | `InputCoordinator.kt:732-741` | **DEAD BAR** |
| 13 | Custom XML, `script="latin"`, a letter missing / corner-only | any | **GEOMETRIC** | `CtcEngineAdapter.kt:288` | **DEAD BAR** |
| 14 | Custom XML, **`script` attribute omitted** | any | **GEOMETRIC** | `SwipeEngineRouter.kt:140-141` | **DEAD BAR** |
| 15 | Custom XML, non-Latin `script` | any | **GEOMETRIC** | `SwipeEngineRouter.kt:135-136` | **DEAD BAR** |

Note on 10: geometric on a Cyrillic layout still needs a `ru` **langpack** to have a dictionary
(`GeometricEngineAdapter.kt:516-533`); with no dictionary the bar is already empty. But that is a
*user-fixable* state today (import a pack). Under a CTC-only build it becomes unfixable.

**Score: 4 of 15 cells survive fallback removal. 11 go dead.**

## 3.3 "Beats geo on every config tested" — the actual comparison

### The one genuinely same-dataset comparison (FUTO test-2400, N=2,400, OOV=miss)

| engine | top-1 / top-3 / top-5 | ≤3-char t1 | 4+-char t1 |
|---|---|---|---|
| **CTC (shipping footing)** | **89.31 / 93.79 / 94.50** | **93.70** | **87.05** |
| our neural (beam 6, production) | 74.62 / 84.33 / 87.42 | 89.45 | 67.00 |
| our geometric (SHARK2) | 67.50 / 78.88 / 81.79 | 69.33 | 66.56 |

(`docs/eval/2026-07-24-test2400-head2head.md:49-52, 185-188`; CTC row is the 3-seed mean recorded
2026-08-08, `docs/specs/ctc-swipe-engine.md:308-310, 326`.)

On this one configuration the claim holds decisively: **+21.8 pt top-1 over geo, +14.7 over neural.**

### Where the claim does NOT hold up

**(a) The per-language "CTC vs geo" pairs are not comparable.** Both used FUTO `swipe-5` traces on
the same committed layout geometries, but different row filters, different N, and — per the docs as
written — different lexicon sizes:

| layout / lang | geo (2026-07-20) t1/t3/t5, n | CTC (2026-08-15) confirm half, n | why not apples-to-apples |
|---|---|---|---|
| dvorak / en | 76.8 / 79.9 / 80.4, n=2444 | 92.72 / 97.14 / 97.87, n=1223 | CTC = in-dict only, single-finger, one random half; the λ doc itself says these absolutes are not cross-comparable |
| azerty / fr | 78.2 / 91.1 / 94.2, n=1994 | 86.25 / 95.16 / 97.29, n=1033 | same; geo doc records a **25k** fr dictionary, CTC a **37,949**-word trie |
| qwertz / de | 77.3 / 88.7 / 91.3, n=1139 | 87.85 / 97.17 / 98.50, n=601 | same; halves differ ~5 pt at matched λ per the doc's own warning |
| german / de | 71.8 / 82.5 / 85.2, n=2114 | 81.57 (λ3.0) / 91.15 / 93.50, n=1107 | same — **and the shipped λ is 2.0, whose confirm t1 is 81.66, not 81.57** |
| spanish / es | 73.4 / 86.1 / 88.5, n=1758 | 89.33 / 94.94 / 96.07, n=890 | same |

The direction is consistently CTC-favourable and probably real, but "beats geo" here is an
inference, not a measurement.

**(b) Configurations where geo was measured and CTC never was.**

| corpus | geo | neural | CTC |
|---|---|---|---|
| held-out val, 9,918 | 67.69 / 78.36 / 81.49 | 76.01 / 85.53 / 87.82 | **never measured** |
| **LOCAL combined, 8,521 traces** | **55.2 / 68.0 / 71.7** | 53.7 / 63.2 / 66.7 | **never measured** |
| FUTO swipe-1/test, 3,912 | 75.2 / 85.4 / 87.9 | never | **never measured** |
| FUTO 100k train sample | 75.3 t1 | never completed | **never measured** |
| synthetic CLEAN/TYPICAL/**SLOPPY** tiers × 6 layouts | full table (`docs/specs/geometric-swipe-engine.md:599-605`) | never | **never measured** |
| ru / JCUKEN | 91.3 / 98.5 / 98.9 TYPICAL (synthetic) | never | val-only, different footing |

Two of these matter a lot:

* **LOCAL combined is the corpus where geometric BEAT neural** (55.2 vs 53.7 top-1). It is this
  project's own held-out real-user data. CTC has never been run on it. Claiming CTC beats geo
  "on every config tested" while the one corpus geo won on was never contested is not defensible.
* **The SLOPPY synthetic tier is the only recorded robustness measurement of any engine**
  (geo drops from 83.4 → 63.8 top-1 on en/QWERTY between TYPICAL and SLOPPY). CTC has no
  robustness number at all.

**(c) No CTC accuracy number was ever produced by the shipped Kotlin engine.** Every CTC number
above comes from the Python harness (CleverKeys-ML campaign / `scripts/ctc_lang_lambda_sweep.py`).
The Kotlin path's only tie-in is golden-fixture parity — identical top-k words, scores within 1e-4
(`docs/specs/ctc-swipe-engine.md:408, 729-733`). That is strong evidence of a correct port, but it
is not an end-to-end accuracy measurement of what ships.

**(d) The preset was never fitted for this model.** `0.9/4.0/0.25/0.25/0.9882` was fitted on
`resbn80g` and "has never been swept for this model family on the app trie … it is not this model's
own optimum, which was never sought" (`docs/specs/ctc-swipe-engine.md:351-355`).

**(e) The fp16w artifact that actually ships was never decoded** — fp32 was, and fp16w was shown
equal to it on **val**, not on test (`docs/specs/ctc-swipe-engine.md:344-349`).

## 3.4 Verdict on removing the geometric fallback

**No. Not today, and not with any amount of CTC tuning alone.**

The geometric engine is not a "fallback for when CTC is weak". It is the **only script-agnostic
decoder in the app**: it works on Unicode codepoints (`Character.isLetter`,
`GeometricEngineAdapter.kt:476-495`, `swipe/geometric/LayoutGeometry.kt`), reads imported
langpacks, and needs no per-language validation. Deleting it deletes swipe for every non-Latin
script, every letter-incomplete layout, every custom layout missing a `script` attribute, and every
language outside en/fr/de/es.

A defensible sequencing is: **delete neural first** (it is genuinely dominated on the one
configuration where all three were compared, and its unique features are mostly tuning knobs) —
but **keep geometric as the universal floor** until CTC can serve non-Latin scripts and imported
langpacks, at which point the geo question can be re-asked with evidence.

---

# 4. What must be built before neural can be deleted / geo removed

Effort: S ≈ <1 day, M ≈ a few days, L ≈ weeks+ (or blocked on data).

## Tier 0 — required before deleting NEURAL (geo retained)

| # | Item | Effort | Risk | Why |
|---|---|---|---|---|
| 0.1 | Re-home the **language detector** out of `SwipePredictorOrchestrator` (`:686-738`) — `SuggestionHandler.kt:1529` calls it on every commit | S | Low | Otherwise a CTC/geo commit either crashes into a dead singleton or silently constructs the whole neural stack you just tried to delete |
| 0.2 | Route `it / pt / sv` (bundled dicts, no CTC support) explicitly to **geometric** in `performCtcSwipeTyping` instead of neural (`InputCoordinator.kt:732-736`) | S | Low | Today those users are on neural; deleting it without this change is a dead bar for three bundled languages |
| 0.3 | Same for imported-langpack languages on QWERTY (same code path) | S | Low | Same |
| 0.4 | Delete/hide the neural-only settings surface, or relabel it: `NeuralSettingsActivity`, `NeuralPredictionSection`, the 8 Auto-Correction sliders that only feed `OptimizedVocabulary`, `finger_occlusion_offset` | M | Low | Leaving live sliders that do nothing is worse than removing them |
| 0.5 | Decide the fate of `SwipeCalibrationActivity` (renders its own QWERTY grid, drives the neural engine, `SwipeCalibrationActivity.kt:49-69, 1077`) | M | Medium | A user-visible feature with no CTC equivalent |
| 0.6 | Decide the fate of the **langpack `prefix_boost` file** (`LanguagePackManager.getPrefixBoostPath`) — becomes dead data | S | Low | Langpack format/manifest implications |
| 0.7 | Accept & document the loss of secondary-language swipe blending (§1.2) | S | **Medium-High** | This is a genuine bilingual-user regression that `ctc` mode already ships today |
| 0.8 | Measure the CTC **Kotlin** engine end-to-end on at least one corpus | M | Medium | There is currently no accuracy number for the code that actually runs |

## Tier 1 — required before removing the GEOMETRIC fallback

| # | Item | Kind | Effort | Risk |
|---|---|---|---|---|
| 1.1 | Fix `CtcTrieNode.MAX_CHILDREN` (`ctc/CtcLexiconTrie.kt:86-95, 115`) — currently throws on the 27th child | app-code | S | Low (but a crash today for any >26 alphabet) |
| 1.2 | Make the alphabet layout-derived instead of `CharArray(26){'a'+it}` (`CtcEngineAdapter.kt:113, 249-301`) | app-code | M | Medium — touches the memo keys, the display overlays, and the golden fixtures |
| 1.3 | Multi-codepoint key labels in `letterOf` | app-code | M | Medium |
| 1.4 | Langpack CKDT lexicon loader for CTC (`filesDir/langpacks/<code>/dictionary.bin`) | app-code | S | Low — λ 2.0 already fits that scale |
| 1.5 | Replace the fixed `CtcLanguageSupport.SUPPORTED` table with a capability check (has-lexicon + alphabet-fits) | app-code + **policy** | S code / L policy | **High** — directly contradicts the project's evidence rule |
| 1.6 | A per-script CTC encoder + model-selection layer in the adapter (the joint universal model was tried and **rejected**, §2.1.1) | **model + app-code** | M per script *if a donor corpus and geometry exist* (~1.5 GPU-h, ~0.55 MiB fp16w) — **L otherwise** | **High** — no clean-licence real corpus exists in ANY non-Latin script (`DATASET_SCOUT.md:420-431`); synthesis disclaims RTL and dense scripts (`cyrillic_synth.py:38-41`) |
| 1.6b | A per-script projection policy (the ru one is lowercase + strip `-`/`'` + ё→е + ъ→ь, **no NFD**) — `CtcAzProjection` is Latin-only | app-code | S per script | Low |
| 1.7 | Per-script / per-language λ validation on real traces | **validation** | L | **High — blocked on data**: the multi-layout human-swipe corpus has **0 rows** for it/pt/sv/nl, and none for non-Latin scripts. Note the test seal is spent, so nothing new can ever exceed the val tier (`PHASE_M.md:479-498`) |
| 1.8 | Handle letter-incomplete layouts without a dead bar (e.g. decode on the subset, or an explicit user-facing "this layout can't be swiped" state) | app-code + UX | M | Medium |
| 1.9 | Default `script` handling for custom layouts (infer Latin, or warn the author) | app-code + UX | S | Low |
| 1.10 | A robustness measurement for CTC comparable to geo's SLOPPY tier | validation | M | Medium |
| 1.11 | CTC vs geo head-to-head on **LOCAL combined** (the corpus geo won on) and on held-out val | validation | M | Medium |
| 1.12 | Re-confirm λ with a **user dictionary present** — no campaign run included one, and λ multiplies the frequency term that user words are injected at the top of | validation | M | Medium |

**Items 1.6 and 1.7 are the load-bearing ones, and both are L with a real chance of being blocked
on data availability rather than effort.**

---

# 5. Findings that contradict the stated premises

Stated plainly, with evidence:

0. **The ML campaign's own conclusion is the opposite of "one universal engine/model".**
   `CleverKeys-ML/ctc/APP_INTEGRATION_PLAN.md:2109-2117`: *"Verdict: feasible, not adopted —
   **separate per-script models remain the plan** … the multi-language route is **a per-script
   model plus a per-language preset**, not one model for everything."* The joint en+ru model was
   built, measured, and rejected: en top-1 **−0.42** against a stated tolerance of 0.3, ru a tie at
   best, greedy **37.07 → 23.68** (`PHASE_J.md:678-710`). "CTC is universal" is true of the
   *architecture* and false of the *artifact*.

1. **"CTC is layout and language agnostic" — the model is; the app is not.**
   `CtcEngineAdapter.kt:113` hard-codes a–z; `CtcLexiconTrie.kt:86-95` throws on a 27th child
   character; `CtcLanguageSupport.kt:50-55` is a hard-coded 4-language table. CTC today serves
   **4 of 7 bundled languages and 0 imported langpacks**. Note also that the ML repo's own
   assessment of the app-side gap — *"`CtcLayout.kt` is already alphabet-agnostic (CharArray); the
   gap there is a ru trie + layout wiring, **not engine work**"* (`PHASE_I_DATA.md:238-245`) — is
   **out of date**: `CtcLayout` is indeed agnostic, but the adapter above it and the trie below it
   are not. Anyone planning from that sentence will under-scope the work.

2. **"Beats geo on every config tested" — one config was tested.**
   The only same-dataset three-way comparison is English QWERTY on FUTO test-2400. All five
   per-language "CTC vs geo" comparisons use different filters, different N, and (per the docs)
   different lexicon sizes. CTC was **never measured** on held-out val, on LOCAL combined, on
   FUTO swipe-1/test, on the FUTO 100k sample, or on any synthetic robustness tier.

3. **The one corpus where geo beat neural was never contested by CTC.**
   LOCAL combined, 8,521 real traces: geo 55.2 t1 vs neural 53.7 t1
   (`docs/specs/geometric-swipe-engine.md:774-776, 846-852`). CTC has no number there.

4. **No accuracy number exists for the shipped Kotlin CTC engine.** Every CTC number is from the
   Python harness; the Kotlin port is validated by golden-fixture parity only
   (`docs/specs/ctc-swipe-engine.md:408`).

5. **Removing the geo fallback deletes swipe for 36 of 83 bundled layouts across 16 scripts**, plus
   two Latin layouts (`latn_qwerty_az`, `latn_qwerty_tly`) that are a–z incomplete, plus every
   custom layout that omits `script="latin"`.

6. **"Custom user-generated keyboard layouts" already work — but only if the author sets
   `script="latin"` and puts all 26 letters on key *centers*.** Neither requirement is documented or
   surfaced anywhere. Corner-only letters silently disqualify the layout
   (`a11y/KeyboardGeometry.kt:188-202`, `CtcEngineAdapter.kt:249-259, 288`).

7. **The λ-scale story for langpacks is not the blocker the brief assumes.** λ = 2.0 is already
   fitted for the CKDT `255 − rank` scale that langpack `dictionary.bin` files use
   (`ctc/CtcScoringParams.kt:116-129`) and is shipping for fr/de/es. The actual blockers are a
   missing ~20-line loader and the project's own evidence policy, which cannot in principle be
   satisfied for an arbitrary user-imported language.

8. **German loses vocabulary under CTC that geometric keeps.** `CtcAzProjection` **drops** words
   with no a–z decomposition — `ß`, `œ`, `æ`, `ø` (`ctc/CtcAzProjection.kt:26-30, 45-55`). Under
   accent collisions only the highest-frequency canonical form is reachable, so French `à` cannot
   be produced when `a` outranks it.

9. **Personalization was never wired to swipe — for any engine.** `UserVocabulary`,
   `PersonalizationEngine`, `UserAdaptationManager` multipliers and the context-LM bigram boost are
   all tap-path-only (`WordPredictor.kt:576, 796, 805, 1956-1988, 2126-2127`). Selections are
   *recorded* on swipe commit but never applied back to a swipe slate. This is good news for the
   migration and bad news for the product.

10. **Bilingual swipe blending is already gone in `ctc` mode today**, before any deletion. It lives
    only in `OptimizedVocabulary.kt:879-938`. An en/fr user who switches the engine to `ctc` loses
    it silently.

11. **The neural engine is not currently preloaded in `ctc` mode for supported languages**
    (`PredictionCoordinator.kt:238-243`), so much of the memory benefit of deletion is already
    banked. The remaining gain is the 10.29 MB of ONNX assets.

12. **"Any language" runs into a data wall, not an effort wall.** No real human swipe corpus exists
    in **any** non-Latin script under a clean licence (`DATASET_SCOUT.md:420-431`); the one real
    Cyrillic corpus is eval-only by licence; and the synthesis method that substitutes for it
    explicitly disclaims scripts whose writing direction or key density departs further from QWERTY
    than ЙЦУКЕН's (`cyrillic_synth.py:38-41`) — which rules out Arabic and Hebrew (RTL) and
    Devanagari (density), i.e. 10 of the 36 non-Latin bundled layouts before you even start. Even
    for Cyrillic, synthetic training costs **−13.4 pt top-1** versus the (unshippable) real-trained
    arm (`PHASE_I_DATA.md:322, 350`).

13. **The alt-layout bars may have been measured at a different slot count than the app uses.**
    The ML layout JSONs carry 27 letter slots for azerty/dvorak/spanish and 29 for german; the app
    always builds 26 (`CtcEngineAdapter.kt:249-301`). Only dvorak has a published
    "app-geometry" counterpart (88.98 vs 89.87). Resolve before quoting azerty 83.81 / qwertz 83.01
    / german 80.64 / spanish 88.45 as app-relevant.

14. **The German shipped-λ number quoted in the spec is the wrong row.**
    `docs/specs/ctc-swipe-engine.md:235` quotes "confirm 87.85 / 81.57" for German, but 81.57 is the
    **λ 3.0** confirm value on the de-german corpus; the shipped λ is 2.0, whose confirm t1 is
    **81.66** (`docs/eval/2026-08-15-ctc-per-language-lambda.md:42-43`). Cosmetic, but it is the
    number a "CTC beats geo for German" comparison would be built on.

---

## Appendix — engine capability matrix

| Capability | Neural | Geometric | CTC |
|---|---|---|---|
| Latin QWERTY layouts | ✅ | ✅ | ✅ |
| Latin non-QWERTY layouts | ❌ | ✅ | ✅ |
| Non-Latin scripts | ❌ | ✅ | ❌ |
| Letter-incomplete layouts | ❌ | ✅ | ❌ |
| Imported langpacks | ✅ | ✅ | ❌ |
| Bundled it / pt / sv | ✅ | ✅ | ❌ |
| Accent recovery | ✅ (index) | ✅ (canonical) | ⚠️ (drops ß/œ/æ/ø, collision-lossy) |
| Contraction display | ✅ | ✅ | ✅ |
| Custom / disabled words | ✅ | ✅ | ✅ |
| Secondary-language blending | ✅ | ❌ | ❌ |
| Fuzzy rescue of rejected candidates | ✅ | ❌ | ❌ |
| User-tunable decode parameters | ✅ (many) | ✅ (3 geo knobs) | ⚠️ (beam width only) |
| Finger-occlusion Y offset | ✅ | ❌ | ❌ |
| Same-dataset accuracy vs the other two | 74.62 t1 (test-2400) | 67.50 t1 (test-2400) | **89.31 t1 (test-2400)** |
| Measured on this project's own real-user corpus (LOCAL combined) | ✅ 53.7 | ✅ 55.2 | ❌ never |
