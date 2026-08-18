# Feature Specification: CTC Swipe Engine (`ctc` mode — WIRED, opt-in)

**Status (2026-08-08):** WIRED behind the opt-in Prediction Engine dropdown (default stays
`neural`). The CleverKeys-trained CTC encoder ships as `models/ctc_swipe_encoder.onnx`
(CleverKeys-ML `phaseM_kd_fresh_w1_s1234_fp16w`, 2.91 MB — TEST-VALIDATED on the shipping
configuration: en_enhanced STRIP trie at preset 0.9/4.0/0.25/0.25/0.9882 → test-2400
seed-mean 89.31/93.79/94.50 t1/3/5, beating FUTO's ceiling and our neural on every
stratum; UNSEALING_4). Integration per `CleverKeys-ML/ctc/APP_INTEGRATION_PLAN.md`
(commits 3b9dd666..d99dd41f, seam-audit fixes fb77b422): `OnnxCtcEmissionModel` +
`CtcEngineAdapter` + `SwipeEngineRouter.Mode.CTC` (QWERTY→CTC, other layouts→geometric
hedge) + `CtcSettingsActivity` (beam-width knob, default 100). **Languages: en, fr, de,
es** (fr/de/es added 2026-08-16 — see "Per-language enablement" below); any other language
under `ctc` mode falls through to the NEURAL flow on QWERTY and to GEOMETRIC elsewhere
(audit M1), so selecting CTC never yields less coverage than `hybrid`. The two-model
ensemble, the rescorer, and contract-v2 remain future options recorded in the plan.
**Package:** `tribixbite.cleverkeys.swipe.ctc` (`src/main/kotlin/.../swipe/ctc/`), with the
Android-side adapter at `swipe/CtcEngineAdapter.kt` + `swipe/OnnxCtcEmissionModel.kt`.
**Origin:** Track (ii) of `docs/audit/2026-08-06-futo-upgrade-plan.md`; algorithm ground
truth is the integration study `docs/audit/2026-08-06-futo-decoder-integration-study.md`
(cited "study §N") + the Python port `scripts/futo_decoder_{eval,ceiling}.py` and FUTO C++
`~/.cache/cleverkeys-test/swipe-library-src` (`resampler.cpp`, `beam_search.cpp`).

> **Reading guide.** The "As-Built" section below is the current-behavior reference.
> Later sections marked **SUPERSEDED (design-era)** describe the pre-wiring plan and are
> kept for history/rationale — where they conflict with As-Built, As-Built wins.

---

## As-Built (2026-08-08, commits 3b9dd666..743b58fa; layout gate widened 2026-08-15, languages fr/de/es added 2026-08-16)

### Routing: mode × layout × language → engine

`swipe_engine_mode` (Settings → Swipe Typing → "Prediction Engine") selects a
`SwipeEngineRouter.Mode`; the router itself is **layout-only** (`SwipeEngineRouter.route`,
QWERTY-Latin gate = `Config.isSwipeTypingSupportedForLayout`). Language is runtime state
the router deliberately doesn't see — the `ctc` mode's language dimension is handled one
level up, in `InputCoordinator.performCtcSwipeTyping` (audit M1).

| `swipe_engine_mode` | QWERTY-Latin + supported language | QWERTY-Latin + other language | Non-QWERTY layout |
|---|---|---|---|
| `neural` (default) | NEURAL | NEURAL | none (no swipe) |
| `hybrid` | NEURAL | NEURAL | GEOMETRIC |
| `geometric` | GEOMETRIC | GEOMETRIC | GEOMETRIC |
| `ctc` | **CTC** | NEURAL (M1 fallthrough) | **CTC** if Latin script + all a–z present (gate widened 2026-08-15) + supported language; GEOMETRIC otherwise, and GEOMETRIC for an unsupported language there |

"Supported language" = `swipe/ctc/CtcLanguageSupport.SUPPORTED` = **en, fr, de, es**
(evidence in "Per-language enablement" below). Net `ctc` semantics: CTC(supported language
on an a–z-complete Latin layout) / neural(other language on QWERTY) / geometric(everything
else) — **never less coverage than `hybrid`**. The active language is read BEFORE dispatch
(`DictionaryManager.getCurrentLanguage()`, falling back to `config.primary_language`); an
unsupported-language swipe takes `dispatchNeuralSwipeTyping`, the SAME flow
`Engine.NEURAL` takes. The adapter keeps its own gate
(`CtcEngineAdapter.supportsLanguage(language)` checked in both `decodeAsync` and
`warmUpAsync`) as defense-in-depth. Unknown/legacy pref values parse to
`NEURAL` (`Mode.fromPref`); the pref is case-canonicalized at read (`Config.kt` refresh,
audit L1), so an imported `"CTC"` behaves exactly like `"ctc"` in the router, provenance
tagging, and the settings UI.

### The seam fixes (fb77b422 — audit H1/M1/M2)

- **H1 — contraction display.** The bundled `en_enhanced.json` has ZERO apostrophe words:
  contractions exist only as a–z aliases (`dont`, `im`, `theyd`), so a raw decode would
  present/commit "dont". `CtcEngineAdapter.applyContractionDisplay` overlays decoded alias
  surfaces with their apostrophe forms via the shared pure `swipe/ContractionOverlay`
  (paired-first keep+variant, real-word ordinal guard, junk-alias replace) using the ACTIVE
  LANGUAGE's `ContractionManager` mappings + the merged-lexicon frequency ordinals
  (`CtcLexiconMerge.ordinals`) — exact parity with `GeometricEngineAdapter`'s duty. This
  happens IN the adapter, before the shared pipeline (the pipeline does not map aliases).
- **M1 — unsupported-language fallback.** See the routing table above: `ctc` mode on a
  QWERTY layout with an unsupported active language dispatches the neural flow instead of
  showing an empty bar.
- **M2 — engine-true provenance.** Suggestion origin markers/long-press sheets tag the
  ROUTED engine, not the configured mode: `SuggestionProvenance.forRoutedEngine(engine)`
  is threaded from `InputCoordinator` through `handleSwipePredictionResults`. A non-QWERTY
  swipe under `ctc` mode is tagged GEOMETRIC (it was decoded geometrically); an en-QWERTY
  swipe is tagged `SuggestionOrigin.CTC` ("CTC swipe (trie beam)", indigo marker
  `SuggestionBar.kt`). The old mode-keyed derivation (`forSwipeEngineMode`) remains only
  as the null-default for callers that don't thread an origin — it mislabeled hybrid's
  geometric swipes as NEURAL_BEAM and would have mislabeled ctc's.

### Fixed 2026-08-16: contractions are scoped to the ACTIVE decode language

**Found:** `CtcMultiLanguageInstrumentedTest` on device — a `fr` decode of the real French
word `franco` also offered the English possessive `franco's`, whose base `francos` is
(correctly) absent from the 37,949-word French trie, so no beam could have produced it.

**Cause:** `CtcEngineAdapter.contractionsFor` and `GeometricEngineAdapter.contractionsFor`
both loaded the bundled ENGLISH base (`contractions.bin` — 120 non-paired aliases + 1,183
paired display forms, 1,116 of them possessives — plus `contraction_pairings.json`'s 1,744
bases) for EVERY language before the active language's file. Loading is EARLIER-WINS, so
English also SHADOWED same-key mappings from the active language. The real-word ordinal
guard did **not** contain it, contrary to the earlier note here: `ContractionOverlay`'s
PAIRED rule fires before the guard is consulted, so the possessive was injected even though
`franco` ranks 2,937 in French (far past `REAL_WORD_ORDINAL_MAX` = 1200).

**Product decision (maintainer, 2026-08-16): code-switching is a BUG, not a feature.**
English words may only come out of a swipe when the user has selected English, and English
morphology must never bleed into a sentence being typed in another language.

**Fix:** the gate is the ACTIVE DECODE LANGUAGE, not "en is somewhere in the configured
set". `getCurrentLanguage()` can only ever return a CONFIGURED language (manual switch and
auto-detect both select from `DictionaryManager.getConfiguredLanguages()`), so an
active-language gate already satisfies the rule — and it additionally fixes the fr+en
bilingual case, where an "en ∈ configured" gate would still leak English forms into French
sentences. Implementation:

- `swipe/SwipeContractionPolicy` (pure) — `usesEnglishBase(language)`: English (incl.
  `en-GB`/`en_US`, and a blank "not known yet" code) keeps the bundled base; every other
  code does not.
- `ContractionManager.loadSwipeDisplayMappings(langCode)` — the ONE loader both adapters
  call. English: `loadMappings()` + `contractions_en.json`, byte-for-byte the previous
  behavior. Otherwise: CLEAR the non-paired/paired/known maps, then load only
  `contractions_<lang>.json`. The clear is required because `loadLanguageContractions` never
  clears and the adapters reuse one manager instance across language switches.

This aligns geo + CTC with what the NEURAL engine already did in v1.1.88
(`OptimizedVocabulary`: clear the English contractions before loading the target language's)
and with the shared pipeline's English-gated possessive augmentation
(`SuggestionHandler.shouldAugmentPossessives`).

**This changes GEOMETRIC behavior too, not just CTC** — the geometric engine serves every
non-CTC language (it, pt, sv, nl, ru, …), so the leak was broader there.

**User-visible consequence (intended):** a fr+en bilingual typing French no longer sees
English contractions or possessives in the swipe bar. Languages that ship no contraction
data (`contractions_{es,pt,sv,id,ms,tl,sw}.json` are literally `{}`) now get NO contraction
overlay instead of the English one. English is untouched, including its possessive
augmentation.

#### Per-language contraction DATA (2026-08-16)

Dropping the English base exposed which languages had nothing of their own. The verdict per
bundled language, and what was done:

| lang | verdict |
|---|---|
| `de` | **Real gap, filled.** German's one genuine apostrophe contraction is the elided `e` of the clitic `es` (Duden D 16: `geht's`, `gibt's`, `hab's`). `contractions_de.json` held only four French-origin proper-noun elisions (`d'Estaing`, `d'Italia`, `d'Ivoire`, `d'or`) because its generator's only source — the ASK German wordlist — has 18 apostrophe words, all proper nouns, and the extractor drops every `'s` token as a possessive. 17 curated clitic mappings added, each with its key verified present in `de_enhanced.bin`. The solid preposition+article fusions (`ins`, `zum`, `vom`, `aufs`, `fürs`, `durchs`, `ums`) are ordinary words and must never be given an apostrophe. |
| `es` | **Correctly empty.** `al` (a + el) and `del` (de + el) are the only standard contractions and both are written solid; RAE never inserts an apostrophe. |
| `pt` | **Correctly empty.** `do`/`da`/`no`/`na`/`pelo` are solid. The genuine `d'`-forms (`d'água`, `d'alho`, `d'olho`, `d'angola`) are absent from `pt_enhanced.bin`, so a mapping could never fire; the two swipeable near-candidates (`douro`, `dalva`) are the apostrophe-FREE canonical spellings and mapping them would be a regression. |
| `sv` | **Correctly empty.** No apostrophe genitive, and the reduced forms are written solid in modern Swedish (`stan`, not `sta'n`). |
| `fr`, `it` | **Substantial but polluted.** Both were bulk-extracted from the ASK wordlists without checking the key against CleverKeys' own dictionary: of 27,494 fr entries only 206 have a key the beam can emit (17,875 dead a–z keys + 9,413 non-a–z keys); it is 116 live out of 22,474. Part of the live remainder is actively harmful — the key is a common word of the same language ranked past `REAL_WORD_ORDINAL_MAX`, so the overlay REPLACES it (`lune`→`l'une`, `larme`→`l'arme`, `davantage`→`d'avantage`, `lago`→`l'ago`, `luna`→`l'una`). Curating that list is an open product decision; the counts are pinned in `BundledContractionDataTest` so they stay visible and cannot grow. |

`scripts/extract_apostrophe_words.py` carries a `CURATED_CONTRACTIONS` overlay merged on top
of the extraction, so re-running the generator cannot wipe the hand-curated German data.

**Guarded by:** `SwipeContractionLanguageIsolationTest` (pure — both policies over the real
assets: the `franco` leak, in-language survival for fr/it/de including the German clitics,
the still-load-bearing real-word guard for fr `la`/`les`/`ma` and it `del`, English parity),
`BundledContractionDataTest` (pure — the dead-data guard, the apostrophes-and-hyphens-only
value invariant, and the linguistic-correctness pins for the empty es/pt/sv files),
`CoreImeHygieneDriftTest.swipeAdaptersScopeContractionsToTheActiveLanguage` (neither adapter
may hand-roll a load order again), `ContractionManagerTest` (instrumented loader over real
assets) and `CtcMultiLanguageInstrumentedTest` (the on-device slate; its
`assertSlateIsCanonical` is once again a strict `slate ⊆ lexicon` for non-English languages
— English keeps a narrow exemption because its possessive pairings deliberately project
outside the lexicon: "africa" → "africa's" → `africas`).

### `CtcEngineAdapter` — the impurity boundary

`swipe/CtcEngineAdapter.kt` mirrors `GeometricEngineAdapter`'s duties for the `ctc` mode:

1. **Letter-box coordinate normalization.** `KeyboardData` → `CtcLayout` via
   `KeyboardGeometry.computeKeyRects`: the 26 a–z letter-key centers, normalized over the
   **letter-key bounding box** (the model's [0,1] frame — the shipped encoder was trained
   on paths normalized over the letter area with centers passed as `layout_keys`, NOT on
   FUTO's 4/3-aspect device frame; `CtcFeaturizer.normalizeRawY` is deliberately not used
   here). The raw `PointF` trace is normalized under the SAME letter-box affine. Layouts
   missing any a–z letter build no `CtcLayout` → empty result (unexpected behind the
   router's QWERTY gate).
2. **Lexicon, per language** (`CtcLanguageSupport` is the table; see "Per-language
   enablement" for the λ/source matrix). **en** reads the bundled
   `dictionaries/en_enhanced.json` ({word: freq}, frequencies already on the AOSP-like
   134..255 log scale the tuned λ=4.0 was fitted against — NFR-4), a–z-STRIPPED
   (`don't`→`dont`) with NO accent folding — byte-for-byte the vocabulary test-2400
   validated. **fr/de/es** read the bundled CKDT `dictionaries/<lang>_enhanced.bin` — the
   SAME asset the geometric engine uses, through the SAME `CkdtDictionaryReader` (extended
   with `readEntries`, which exposes the uint8 rank byte; `read` is now a projection of it)
   — at `freq = max(1, 255 − rank)` (`CtcCkdtLexicon`), then projected onto a–z
   (`CtcAzProjection`). Both merge the ACTIVE LANGUAGE's user custom words (freq clamped
   1..255; custom overrides disabled) minus its disabled words (`CtcLexiconMerge.merge`,
   unit-tested), read from the per-language pref keys. **Langpack swap is deliberately
   unsupported** (audit L2): an installed en langpack's CKDT `dictionary.bin` stores the
   INVERTED 255−rank scale the en λ was NOT fitted for — swapping THAT source requires its
   own λ validation round (plan §7.1). Known limitation: the CTC en vocabulary can diverge
   from the en dictionary source the other engines see.
3. **Per-decode trie freshness.** The trie memo is keyed by (LANGUAGE, SHA-256
   content-hash over source id + custom-words JSON + disabled-words set), recomputed per
   `lexiconFor(language)` call — any user dictionary mutation rebuilds the trie on the next
   decode with no ContentObserver plumbing, and a language switch can never reuse the
   previous language's trie. The decoder memo carries the language too, because the preset
   (λ) is language-dependent; both are pinned by `CoreImeHygieneDriftTest`.
4. **ONNX session.** Loaded lazily on the decode thread via the existing `ModelLoader`
   (XNNPACK-first, `onnx_xnnpack_threads` pref coerced 1..8). **Bounded model-load retry**
   (audit L5): up to 3 failed attempts (cold-boot transients must not permanently disable
   ctc), then the failure latches off for the IME session (no per-swipe retry storm). On
   shutdown the ORT session is intentionally NOT closed (closing mid-`session.run` is UB
   in ORT; reclaimed at process death, same posture as the neural orchestrator).
5. **Decoder memo** keyed by (mapped layout, trie, beam width) — a beam-width change from
   settings swaps the memoized decoder on the next swipe, no engine rebuild or re-warm
   hook needed.
6. **Warm-up.** `warmUpAsync` front-loads session + trie + layout;
   `InputCoordinator.prewarmGeometricEngine()` (shared prewarm entry, called from
   `CleverKeysService.onStartInputView` — layout switches / rotation) routes to it when
   the router would pick CTC, so the first swipe decodes in warm-path time.

**Concurrency contract** (mirrors the geometric WP9-audit-M-2 shape, pinned by
`CoreImeHygieneDriftTest`): all engine-side state (session, layout/trie/decoder memos,
`ContractionManager`) is confined to the single background thread of a
`PredictionTaskRunner`. `decodeAsync` submits in the FOREGROUND slot (a new swipe cancels
the previous decode — last-swipe-wins — and any in-flight prewarm); `warmUpAsync` submits
in the BACKGROUND slot (supersedes an older prewarm, NEVER cancels a decode). Result
delivery is guarded by a monotonic decode generation (only the newest decode may post to
the main thread; re-checked on the main thread), and `performCtcSwipeTyping`'s callback
additionally applies the `isReplayInputStillCurrent` staleness guard so a late decode
cannot commit into a changed input field (audit M-2 parity).

**Output contract:** top-8 slate (`TOP_K = 8`; the bar renders ~5 and the pipeline
augments possessives), scores engine-relative softmax×1000 — never compared across
engines. Results feed the SAME single seam as neural/geometric:
`InputCoordinator.handlePredictionResults` → `SuggestionHandler.handleSwipePredictionResults`,
inheriting the password guard, possessive augmentation, shift/caps transform, and THE
commit engine. ML trace capture is tagged `SwipeMLData.ENGINE_CTC` + layout name so
exports stay separable per decoder (audit n-2 conventions).

### Per-language enablement (2026-08-16)

The shipped encoder is layout- and language-agnostic (it emits a–z posteriors from
geometry alone), so enabling a language is a LEXICON + PRESET question, not a model one. A
language is enabled only with BOTH kinds of evidence:

| Language | Model evidence (alt-layout top-1, CleverKeys-ML `ctc/`) | λ evidence | Lexicon source | λ |
|---|---|---|---|---|
| `en` | test-2400 89.31 (QWERTY family); dvorak 91.82 / dvorak-app 91.10 | fitted + test-validated | `dictionaries/en_enhanced.json` (134–255 byte scores) | **4.0** |
| `fr` | azerty **84.53** | sweep tune-half winner, confirm 86.25 | `dictionaries/fr_enhanced.bin` (CKDT, `255 − rank`) | **2.0** |
| `de` | qwertz **83.97** / german **81.30** | sweep tune-half winner, confirm 87.85 / **81.66** | `dictionaries/de_enhanced.bin` | **2.0** |
| `es` | spanish **89.53** | sweep tune-half winner, confirm 89.33 | `dictionaries/es_enhanced.bin` | **2.0** |
| `it`, `pt`, `sv` | **none** | **none** | (bundled `.bin` exists, unused by CTC) | — |

> **Two corrections landed 2026-08-18, both from the CleverKeys-ML `MODELS_TABLE.md` audit.**
> (1) The alt-layout column previously read azerty 83.81 / qwertz 83.01 / german 80.64 /
> spanish 88.45 and dvorak 89.87 / 88.98. Those are **`sw2345`**'s numbers
> (`MODELS_TABLE.md:139`) — a superseded Phase-J model that was *never decoded on test* — not
> the shipped `phaseM_kd_fresh_w1_s1234_fp16w`'s (`MODELS_TABLE.md:113`). The error was
> conservative: every corrected value is higher. The campaign **bars** these clear are a
> third set again — azerty 83.60 / qwertz 82.50 / german 79.64 / spanish 88.28.
> (2) German confirm-half read 81.57, which is the **λ 3.0** cell of
> `docs/eval/2026-08-15-ctc-per-language-lambda.md:43`; the shipped λ 2.0 value is **81.66**.
> Note that λ 3.0 beats λ 2.0 on the *confirm* half for de-qwertz and es-spanish. λ 2.0 is
> still correct: selection is made on the **tune** half (the ✔ marks) and confirm is reported,
> never selected on.
>
> All alt-layout numbers are the **`az26`** arm — 26 slots, exactly what
> `CtcEngineAdapter.buildMappedLayout` builds. The `full` arm (27 slots for dvorak/azerty/
> spanish, 29 for german) was measured and buys nothing: +0.05 / +0.10 / 0.00 / −0.23
> (`ctc/ALT_LAYOUT_EVAL.md:303-311`). The suspected app-vs-campaign slot mismatch does not exist.

λ is selected by the lexicon's frequency SCALE, not by the language: `en_enhanced.json`'s
compressed 134–255 byte scores give `ln f ∈ [4.9, 5.54]`, while a CKDT `.bin` read at
`freq = max(1, 255 − rank)` spans `ln f ∈ [0, 5.54]` (~8× the log spread), so the same λ
weights frequency very differently. Measured in
`docs/eval/2026-08-15-ctc-per-language-lambda.md` (tune/confirm split over real FUTO human
traces on the app's own dictionaries): the English control is monotone increasing to λ 4.0
on both halves, while λ 4.0 is never a tune-half winner for a CKDT-scale corpus and costs
−1.5 to −3.2 pt on the confirm half. λ 2.0 also independently matches the earlier Cyrillic
sweep (CleverKeys-ML `PHASE_J.md` §6.9). **Everything else in `tunedV2` (γ 0.9, β 0.25,
α 0.0, γ_prune 0.25, β_prune 0.9882, beam 100) is language-invariant** —
`CtcScoringParams.presetFor(language, …)` varies λ and nothing else.

**it / pt / sv need a validation round before enabling**: no alt-layout accuracy bar and
no λ sweep exist for them. They stay on the pre-existing fallback (neural on QWERTY,
geometric elsewhere). Enabling one is a row in `CtcLanguageSupport.SUPPORTED` plus its
evidence — the code path, memo keys, projection and display map are already general.

#### Accent display (the CKDT duty)

The beam emits a–z surfaces and `CtcLexiconTrie` reconstructs a word from the root→node
path, so a CKDT lexicon has to be projected before it can be a trie, and the canonical
form has to survive somewhere. `CtcAzProjection`:

- **Projection** (`project`): NFD → drop combining marks → drop `'`/`’`/`-` → require a–z,
  else the word is UNTYPEABLE and is DROPPED (not mangled: `ß`, `œ`, `æ`, `ø` have no a–z
  decomposition). This is a 1:1 port of the `project_az` policy the λ sweep's lexicons were
  built with, so the shipped λ matches the shipped vocabulary exactly.
- **Display map**: stripped surface → canonical form, stored ONLY where they differ.
  `CtcEngineAdapter.applyCanonicalDisplay` rewrites the slate through it before the shared
  pipeline (`display[word] ?: word`), the same overlay shape as the contraction mapping and
  the same accent-recovery model the geometric engine uses (it returns CKDT canonical
  forms directly). Composition order is **accents first, contractions second** — contraction
  keys are a–z aliases, so they must see the a–z surface of anything the accent map did not
  rewrite.
- **Collisions**: several canonical words can share one a–z surface (`côte`/`cote`/`coté`).
  The HIGHEST-frequency canonical wins both the frequency and the display slot; ties keep
  insertion order (custom words first, then rank ascending). Known limitation: only that
  form is reachable by swipe — French `à` cannot be produced when `a` outranks it.
- Measured on the shipped dictionaries (pinned by `CtcCkdtLexiconTest`, matching the sweep
  harness's numbers):

| Language | records | untypeable | trie words | accent-strip collisions |
|---|---:|---:|---:|---:|
| `fr` | 40,000 | 31 | 37,949 | 2,020 |
| `de` | 40,000 | 0 | 39,594 | 406 |
| `es` | 50,000 | 0 | 47,955 | 2,045 |

Contractions: the CKDT dictionaries contain ZERO apostrophe words (fr "jai"/"cest", de
"dor"), exactly like `en_enhanced.json`, so `ContractionOverlay` runs for the active
language with ONLY `contractions_<lang>.json` loaded — the same
`ContractionManager.loadSwipeDisplayMappings` call `GeometricEngineAdapter` makes, so both
engines display identically. The bundled ENGLISH base is loaded for English only (see
"Fixed 2026-08-16: contractions are scoped to the ACTIVE decode language"); before that fix
both adapters loaded it EARLIER-WINS for every language, which both shadowed the active
language's own mappings and injected English possessives into non-English slates.

### Shipped model + preset

- Asset `src/main/assets/models/ctc_swipe_encoder.onnx` — 2.91 MB fp16-weight CTC
  emission encoder, trained from scratch by the CleverKeys project (CleverKeys-ML `ctc/`,
  Phases E→M, `phaseM_kd_fresh_w1_s1234_fp16w`) on MIT-licensed corpora (FUTO
  swipe.futo.org + How-We-Swipe; no FUTO weights or model outputs — see repo `NOTICE`).
  Run via `OnnxCtcEmissionModel` (emission slice per `CtcEmissions.sliceFromHead`).
- Ship preset `CtcScoringParams.tunedV2`: γ=0.9, λ=4.0, β=0.25, α=0.0, γ_prune=0.25,
  β_prune=0.9882; beam width default 100 (`Defaults.CTC_BEAM_WIDTH`), adapter topK=8.
  Fitted offline on the app-trie footing; the published-preset control measured −2.3 pt
  top-1, which is why the scoring constants are not user-exposed. The adapter builds its
  decoder through `CtcScoringParams.presetFor(language, …)`, which is `tunedV2` with λ
  swapped for the language's lexicon scale (λ=2.0 for CKDT-scale fr/de/es).
- Validation (test-2400, seed-mean): **89.31 / 93.79 / 94.50** top-1/3/5
  (≤3-char 93.70, 4+ 87.05) — above FUTO's own decoder ceiling (84.83) and our neural
  (74.62) on every stratum; equal-footing McNemar 3/3 seeds p<5e-4. Evidence:
  `CleverKeys-ML/ctc/UNSEALING_4.md`; app-side cross-reference
  `docs/eval/2026-07-24-test2400-head2head.md` (addendum). **Do not quote these numbers
  without the footings and the two limitations that travel with them** — both are spelled
  out in "Evidence tier of the shipped model" immediately below.

### Evidence tier of the shipped model — quote it with its limitations

The fourth and **final** unsealing of the sealed `test-2400` split
(`CleverKeys-ML/ctc/UNSEALING_4.md`, pre-registered and pushed at `b91f179` *before* any
decode; six decodes, one per (config, seed), no retries; ledger 3 → 4, **there is no
fifth**) put this model on both footings:

| footing | seed-mean t1/t3/t5/≤3/4+ | bar | Δ |
|---|---|---|---|
| **A** — AOSP STRIP 146,964 at benchmark preset E1 | 88.931 / 92.681 / 93.361 / 92.597 / 87.045 | FUTO published `84.83/91.04/92.08/89.57/82.40` | +4.10 / +1.64 / +1.28 / +3.03 / +4.64 |
| **B** — the SHIPPING footing: `en_enhanced` STRIP trie 98,081 at the app preset `0.9/4.0/0.25/0.25/0.9882` | **89.306 / 93.792 / 94.500 / 93.701 / 87.045** | trie-matched `84.92/91.54/92.96/89.57/82.52` | +4.39 / +2.25 / +1.54 / +4.13 / +4.53 |
| **equal footing** (both engines val-tuned, same rows/trie/beam/OOV rule) | same as A | `87.12/92.29/92.96/89.94/85.68` | +1.81 / +0.39 / +0.40 / +2.66 / +1.36 |

All five clear on the seed-mean **and on every individual seed** on all three, worst-seed
top-5 margin **+1.50** on the shipping footing. Exact paired two-sided McNemar on top-1
against FUTO's val-tuned per-row output resolved **3 of 3 seeds at p < 5e-4** (+45/+46/+39
rows) → the model is **TEST-VALIDATED** and holds a **qualified equal-footing win** — the
registered ceiling on that claim, *not* a general superiority claim.

**Two limitations travel with those numbers and must not be dropped when quoting them:**

1. **The equal-footing lead is bought entirely on the HWS corpus half.** Per-source top-1:
   FUTO's val-tuned engine **beats us by +0.38 on its own corpus half** (95.89 vs 95.51);
   our +1.81 aggregate comes from the HWS half (+4.05). What is demonstrated is better
   *coverage across two corpora*, not better decoding per se (`UNSEALING_4.md` §8.4).
2. **ch 192 keeps top-5 by 0.14** (93.50 vs our 93.361 on config A) — it is the one metric,
   of the five, on which an earlier model stays ahead, at 6.14 MB against our 2.91 MB.

Further caveats that travel with every test-2400 number: T3 contributor contamination, the
dedup defect, the ~12–14 pt FUTO/HWS internal spread, and the preset asymmetry on
published-bar comparisons (ours tuned, FUTO's published). The fp16w artifact that actually
ships was **not itself decoded** — fp32 was; fp16w ≡ fp32 to 0.00 on all five metrics at the
app footing on val (§2.2 of `UNSEALING_4.md`), so the numbers carry by measurement, not
assumption.

**Preset provenance (a disclosed gap, not a fitted result):** `0.9/4.0/0.25/0.25/0.9882`
was fitted on `resbn80g` and has **never been swept for this model family** on the app
trie. Config B validates it on the sealed split at that preset; it is not this model's own
optimum, which was never sought. The λ term is the one constant since re-fitted per lexicon
scale — see "Per-language enablement" above and `CtcScoringParams.presetFor`.

### The fixture-and-preset rule (why three things move together)

`MODEL_COMPARISON.md` §5.1: the shipped ONNX, the runtime preset, and the golden fixture
**always move together**. The fixture records its own `source_onnx_sha256` and `preset`;
shipping the model at one preset and the fixture at another makes the parity gate assert
against a configuration nothing runs. Current triple:

| corner | value |
|---|---|
| model asset | `src/main/assets/models/ctc_swipe_encoder.onnx` sha256 `84718e6ebc8020176f27b9668e50922a765c96838307b640a8db9ab0549e88e5` |
| fixture (both copies) | `src/test/resources/ctc/ctc_golden.json` + `src/androidTest/assets/ctc/ctc_golden.json`, byte-identical, sha256 `2a449c4f2de19505131b396655ae01d3e3c325e40249446ff6e7a40c2b27559c` (= ML `artifacts/phaseM_kd_fresh_w1_fp16w_golden.json`, regenerated 2026-08-14 at the **ship** preset — the first cut was generated at E1 and is superseded, `PHASE_M.md` §11.1) |
| runtime preset | `CtcScoringParams.tunedV2()` = `0.9 / 4.0 / 0.25 / 0.25 / 0.9882`, beam 100, top-4 — the en-scale λ, which is what the fixture's `en_enhanced` trie is on |

All three corners are pinned by `CtcParityTest.fixture_model_and_shipPreset_travelTogether`
(pure JVM, no device: hashes the asset, compares the fixture preset term-by-term against
`tunedV2`, asserts every beam case decodes at that preset, and pins the two fixture copies
byte-identical). The device half — "the artifact actually *produces* those emissions
through ORT" — is `CtcEmissionModelParityTest` (see Testing Strategy).

The decoy this guards against is real: the superseded `resbn192i_s1234_fp16w` is
**byte-size-identical** to the ship artifact at 3,052,318 B but hashes `d55624cc…`, so a
wrong-artifact swap survives every eyeball check and only a hash catches it.

### Settings surface

| Control | Key | Default | Range | Where |
|---|---|---|---|---|
| Prediction Engine dropdown (Hybrid/Neural/Geometric/CTC) | `swipe_engine_mode` | `"neural"` | 4 values, case-canonicalized at read | `ui/settings/sections/NeuralPredictionSection.kt` |
| CTC Beam Width slider | `ctc_beam_width` | 100 | 10–300 (clamped at load AND per decode) | `CtcSettingsActivity.kt` |

- `CtcSettingsActivity` ("Full CTC Settings" button, shown only under `ctc` mode) exposes
  exactly ONE knob — commit-phase beam width — plus "Reset to Validated Default". The
  adapter re-reads `Config.globalConfig().ctc_beam_width` per decode, so changes apply on
  the next swipe.
- "Full Geometric Settings" stays visible under `ctc` mode too (the non-QWERTY hedge is
  geometric).
- Settings search: "CTC Settings" entry (keywords ctc/futo/swipe engine/beam/trie) is
  deliberately UNGATED by the current engine mode — gating made "ctc" unfindable exactly
  when the user is setting swipe up (`SettingsActivity.kt`).
- Backup & restore: `ctc_beam_width` is in `SETTINGS_DEFAULTS`
  (`backup/SettingsDefaults.kt`); `swipe_engine_mode` diffs case-insensitively at import
  (`SettingsImportPlanBuilder`, audit L1). Reset presets restore `ctc_beam_width` but
  deliberately leave `swipe_engine_mode` alone (engine choice, like the geo knobs'
  precedent, is not a "tuning" preset member — `SettingsResetPresets.kt`).

### Test inventory (as wired)

Pure JVM (`runPureTests`; registered + drift-checked by `TestRunnerListDriftTest`):

| Suite | Cases | What it pins |
|---|---|---|
| `swipe/ctc/CtcParityTest` | 3 | Golden parity vs the Python port: featurizer tensor bit-identical; beam top-k words identical, scores within 1e-4. Plus `fixture_model_and_shipPreset_travelTogether` — the device-free half of the fixture-and-preset rule above (asset sha256 vs the fixture's `source_onnx_sha256`, fixture preset vs `tunedV2` term-by-term, every beam case at the ship preset, both fixture copies byte-identical) |
| `swipe/ctc/CtcModuleTest` | 12 | Emissions slice, trie loaders, preset constants, featurizer branches, beam behavior, facade seam |
| `swipe/ctc/CtcLexiconMergeTest` | 10 | Merge policy: custom-first, 1..255 clamp, custom-overrides-disabled, case-folded dedupe, ordinals |
| `swipe/ctc/CtcContractionDisplayTest` | 7 | Alias→apostrophe display over the real merged-lexicon ordinals (H1) |
| `swipe/ctc/CtcLanguagePresetTest` | 17 | `presetFor` λ-by-lexicon-scale (en 4.0 / fr,de,es 2.0 / unknown→en), language-invariance of every other constant, the `CtcLanguageSupport` table (supported set, it/pt/sv held back, asset paths, normalization). Plus `tunedRuCkdt`'s E1 constants and the pinned fact that it is on a DIFFERENT footing than the shipping axis (see "Recorded, not wired" below) |
| `swipe/ctc/CtcCkdtLexiconTest` | 18 | The a–z projection policy (folding, untypeable ß/œ/ø, joiners) + the REAL bundled fr/de/es dictionaries: record/untypeable/word/collision counts, the `255 − rank` scale, the canonical display map (`cafe`→`café`, `uber`→`über`, `nino`→`niño`), highest-frequency-wins collisions, and trie totality |
| `swipe/ContractionOverlayTest` | 12 | The shared pure overlay decision matrix (geometric + ctc twin duty) |
| `swipe/SwipeEngineRouterTest` | 15 | Routing table incl. `Mode.CTC` rows + `fromPref` canonicalization |
| `SuggestionProvenanceTest` | 12 | `forRoutedEngine` totality + origin labels (M2) |
| `ml/SwipeMLDataProvenanceTest` | 5 | `ENGINE_CTC`/layout tagging of ML captures (n-2) |
| `CoreImeHygieneDriftTest` | 15 (class total) | Source-scan pins incl. the CTC twins of the geometric pins: prewarm stays BACKGROUND slot, decode stays FOREGROUND, staleness guard present, M1 fallthrough present (via `supportsLanguage`, same predicate in dispatch and prewarm), trie+decoder memos keyed by language, both display overlays applied in order |
| `backup/SettingsImportPlanBuilderTest` | 34 | Incl. `swipe_engine_mode` case-insensitive diff cases |

Instrumented (ew-cli, Pixel7/API34 — all green on-device 2026-08-08):

| Suite | Cases | What it gates |
|---|---|---|
| `swipe/CtcEmissionModelParityTest` | 2 | The SHIPPED ONNX asset's on-device emissions/decodes match the golden fixture |
| `swipe/CtcLatencyGateTest` | 1 | Production-path decode budget: median < 150 ms / p90 < 250 ms (ModelLoader+XNNPACK, real `trieFor("en")` merge path — en is the largest bundled lexicon at 98k words vs fr/de 40k and es 50k — `presetFor("en")` beam 100 topK 8, worst-case golden trace) |
| `swipe/ctc/CtcOnnxLatencyBenchmarkTest` | 2 | Loose-bound measurement harness (informational, not the gate) |

Remaining before any v1.6.0 tag: manual QA per plan §4.5 (first-swipe warmup, long-word
feel, non-QWERTY hedge, unsupported-language neural fallback, don't/I'm display,
provenance label, thermals) — plus, for the 2026-08-16 language enablement: an accented
commit per language (fr "café", de "über", es "niño"), a language SWITCH mid-session (the
next swipe must decode against the new language's trie/λ, not the previous one's), and a
first-swipe warmup on a fr/de/es layout. Tag only on explicit user go. See
`memory/todo.md` HANDOFF §B.

---

## Feature Overview

### Summary
A fourth swipe-decode engine in the pattern of `swipe/geometric/`: a **non-autoregressive
CTC trie-beam decoder** that consumes per-frame log-emissions from a CTC-emission encoder
and returns a scored candidate slate. The decode strategy (featurizer + trie + Viterbi CTC
beam) is pure JVM and fully implemented + tested here; the emissions come from the
CleverKeys-trained ONNX encoder above (the model was the last-landed piece — it was the
sole blocker during the design phase).

### Motivation
The measured levers (study §5a, plan "Framing"):
- FUTO's decisive **structural** advantage is CTC's one-NN-call decode: the beam is pure
  CPU, so FUTO affords beam 300 vs our autoregressive beam 6 (study §6 item 1). This is
  the source of its long-word advantage (4+ chars: 77.6% vs our 69.3%, study §5b).
- The single measured **accuracy** lever is the per-layout refinement head (`magic_macaw`):
  **+5.88 pt top-1** (study §5a). The beam algorithm itself was ≈neutral. (Outcome: the
  CleverKeys-trained encoder beat all bars WITHOUT a refinement head — it ships alone.)
- Head-to-head against FUTO's engines was **stratified, not dominated**, so the product
  posture is a *complement behind a router*, not an assumed replacement (plan Key open
  decision 3, O7). (Outcome: the trained encoder ended up leading every stratum, but the
  router posture shipped anyway — `ctc` is opt-in, default stays `neural`.)

---

## Requirements

### Functional Requirements
- **FR-1** Decode a `[frames][K+1]` log-emission matrix + a lexicon trie into a top-k word
  slate via FUTO's single-stream Viterbi trie CTC beam (3 transitions/frame:
  blank / advance-to-child / repeat-char; MAX-merge dedup; length-aware pruning; final
  `ctc/L^gamma + weight*beta*L + lambda*logFreq`). — **DONE** (`CtcBeamDecoder`).
- **FR-2** Featurize a normalized touch path into the encoder's `[2,64]` tensor via FUTO's
  two-stage resample (60 Hz linspace `round(dur/16.667)+1` → fixed-64, index-uniform,
  clamp [0,1]) + build the layout key-center/mask tensors, honoring the 4/3 vertical aspect
  contract. — **DONE** (`CtcFeaturizer`). Note: the shipped model's runtime frame is the
  letter-box normalization done in `CtcEngineAdapter` (As-Built §1), not the 4/3 helper.
- **FR-3** Provide a lexicon trie over the active alphabet with per-word AOSP-scale
  (1..255) log-frequency and the `ITrie` accessors the beam needs, plus loaders that either
  skip or a-z-strip out-of-alphabet words. — **DONE** (`CtcLexiconTrie`).
- **FR-4** Expose a facade (`CtcSwipeDecoder`) that wires featurizer → emission model →
  beam in the one call shape a `ctc` engine mode would invoke. — **DONE**.
- **FR-5** Obtain per-frame emissions from a CTC-emission encoder (+ optional refinement
  head). — **DONE (2026-08-08)**: `OnnxCtcEmissionModel` over the shipped
  `models/ctc_swipe_encoder.onnx` (refinement head not needed — the trained encoder beats
  all bars without it).
- **FR-6** Slot a `ctc` value into `swipe_engine_mode` so the selector routes qualifying
  swipes to this engine. — **DONE (2026-08-08)**: `Mode.CTC`/`Engine.CTC` wired end-to-end
  (router → `CtcEngineAdapter` → the unified suggestion pipeline), opt-in via the
  Prediction Engine dropdown.

### Non-Functional Requirements
- **NFR-1 (purity)** The core never touches Android or SharedPreferences — pure JVM,
  testable via `runPureTests` (matches `swipe/geometric/` NFR-3).
- **NFR-2 (parity)** Beam math runs in `Double`; emission values are read as `Float`
  (float32) then widened — mirroring the Python port so golden top-k words match exactly
  and scores match within libm tolerance.
- **NFR-3 (determinism)** Insertion-ordered dedup + stable descending prune sort reproduce
  the port's tie handling; decode is deterministic for a given input.
- **NFR-4 (frequency scale)** Log-frequency stays on the AOSP 1..255 log scale end-to-end
  (study H5); normalized `[0,1]` frequency would make `lambda` ~2 orders of magnitude too
  weak. This is also why the runtime lexicon is pinned to the bundled `en_enhanced.json`
  (langpack CKDT stores an inverted scale — As-Built "Lexicon").

---

## Technical Design

### Architecture / Module skeleton
```
src/main/kotlin/tribixbite/cleverkeys/swipe/ctc/
├── CtcScoringParams.kt   # scoring presets (tunedV2 SHIP preset + design-era presets)
├── CtcEmissions.kt       # [frames][K+1] log-emission value type + sliceFromHead()
├── CtcLayout.kt          # alphabet (emission-column order) + key centers
├── CtcLexiconTrie.kt     # trie + ITrie-style nodes + freq-map loaders
├── CtcLexiconMerge.kt    # bundled+custom−disabled merge policy + ordinals (H1 guard)
├── CtcFeaturizer.kt      # resampler.cpp port: 60Hz→fixed64, layout tensors, 4/3 aspect
├── CtcBeamDecoder.kt     # greedy CTC + single-stream Viterbi trie beam  (the core)
└── CtcSwipeDecoder.kt    # facade: featurizer → CtcEmissionModel → beam

src/main/kotlin/tribixbite/cleverkeys/swipe/
├── OnnxCtcEmissionModel.kt  # the production CtcEmissionModel (ONNX session)
├── CtcEngineAdapter.kt      # Android boundary (As-Built section above)
└── SwipeEngineRouter.kt     # Mode.CTC / Engine.CTC routing
```
Tests: see the As-Built test inventory. Golden fixture
`src/test/resources/ctc/ctc_golden.json` (regen: `scratchpad/gen_ctc_golden.py`, imports
the real port).

### Algorithm (port of `beam_search.cpp` / `futo_viterbi_beam`, study §3)
Per output frame, each hypothesis `(score, trieNode, blankEnded)` expands into three CTC
moves against that frame's log-probs, deduped by `(nodeId shl 1) or blankEnded` with a
**MAX** merge (Viterbi, not log-sum):
- **A. blank** — stay on node, set `blankEnded`; key `(id shl 1) or 1`, `+= p[blank]`.
- **B. advance** — for each trie child, move to it; key `childId shl 1`, `+= p[childChar]`.
- **C. repeat** — re-emit the node's own char, stay (only if `!blankEnded` and node ≠ root);
  key `id shl 1`, `+= p[nodeChar]`. Unlike textbook CTC there is no required blank between
  distinct chars (`beam_search.cpp:241-242`).

Pruning to `beamWidth` uses the **length-aware** key
`score / max(depth,1)^gammaPrune + betaPrune*depth`, distinct from the final length norm.
Complete-word nodes score `ctc/max(len,1)^gamma + beta*len + lambda*logFreq`, dedup by
surface form (max), truncate to `topK`.

### Data structures / API
- `CtcEmissions(values: FloatArray, frames, numClasses)` — row-major `[frames][K+1]`, blank
  last. `sliceFromHead(fullHead, frames, maxKeys, numLetters)` reproduces
  `engine.cpp::predict_segment`'s slice (blank relocated from column `maxKeys` → `numLetters`).
- `CtcLexiconTrie(alphabet: CharArray)` — `insert(word, freq)`, `contains`, `charIndexOf`;
  `CtcTrieNode` exposes `id / charIdx / depth / isWord / logFreq / children / word()`.
  Loaders `loadFromFrequencyMap` (skip non-alphabet) / `loadStrippingNonAlphabet` (a-z-strip
  apostrophes: `don't`→`dont` — the SHIPPING loader).
- `CtcFeaturizer.featurize(px,py,pt): FloatArray` (`[x0..x63,y0..y63]`),
  `buildPaddedLayout(layout)`, `normalizeRawX/Y` (4/3 aspect + affine).
- `CtcBeamDecoder.decode(emissions, trie, params): List<CtcCandidate>`, `greedy(...)`.
- `CtcSwipeDecoder(model, layout, trie, params).decode(px,py,pt)` — the end-to-end call.

---

## Design-era sections (kept for history)

> **SUPERSEDED (design-era).** Everything below this banner was written while the module
> was a dead-code prototype blocked on a model export. The model has since been trained
> and shipped, and the mode is wired (see As-Built). Statuses like "BLOCKED", "not
> wired", "no production implementation" in these sections are historical.

### The retrain/re-export boundary (as of 2026-08-06 — since resolved)

| Piece | Status then | Outcome |
|---|---|---|
| CTC Viterbi trie beam (3 transitions, MAX-merge, length-aware prune, final score) | DONE, tested | shipped (`CtcBeamDecoder`) |
| Featurizer (60 Hz linspace → fixed-64, [0,1], 4/3 aspect, key-centers tensor) | DONE, tested | shipped (`CtcFeaturizer`) |
| Lexicon trie (a-z, per-word 1..255 log-freq, ITrie accessors, loaders) | DONE, tested | shipped (`CtcLexiconTrie`) |
| `scoring.json` presets (encoder-only / encoder+decoder / fallback) | DONE, tested | superseded by the fitted `tunedV2` SHIP preset |
| Facade wiring featurizer → emissions → beam | DONE (seam) | shipped (`CtcSwipeDecoder`) |
| **Per-frame CTC emission encoder** | BLOCKED — retrain/re-export | **RESOLVED**: CleverKeys-trained encoder → `OnnxCtcEmissionModel` |
| Per-layout refinement head (`magic_macaw`, the +5.88 pt lever) | BLOCKED — retrain (paired) | **NOT NEEDED** — encoder alone beat all bars |
| Context-LM rerank (`hungry_jellyfish`, `alpha·lm`) | BLOCKED — retrain (add-on) | future option (plan) |
| ONNX-vs-ExecuTorch runtime decision (A3 spike) | OPEN decision | **DECIDED: ONNX** (existing runtime, no second engine) |

### Engine-selector integration (the design that was applied)

The design called for: (1) a `"ctc"` pref value, (2) `Mode.CTC`/`Engine.CTC` in the
router, (3) engine construction beside the geometric engine, (4) output into the single
`SuggestionHandler.handleSwipePredictionResults` seam, (5) two-phase decode (preview
beam 32 / commit beam 300). Items 1–4 shipped essentially as designed (see As-Built for
the deltas: opt-in dropdown instead of hidden pref; commit beam default 100, not 300;
`tunedV2` instead of `encoderDecoder` params; language fallthrough added). Item 5
(during-gesture preview decode) was NOT implemented — v1 decodes at gesture end only.

The design note that "a mature model can serve ALL layouts" (the encoder is
layout-parameterized, study D2) has partly landed: the layout gate was widened to any
a–z-complete Latin layout (2026-08-15) and the language gate to en/fr/de/es (2026-08-16).
Non-Latin scripts still need their own alphabet/emission contract.

### Implementation plan (historical)

- **Phase A** (plumbing, no retrain): A1 beam ✅, A2 featurizer ✅, A4 trie ✅, A5 selector
  design ✅; A3 runtime spike → resolved as ONNX; A6 runtime-hygiene backlog (mmap,
  pre-alloc, big-core pinning) → superseded by the shipped adapter's memo/warm-up design +
  the instrumented latency gate.
- **Phase B** (the hard fork): B1 CTC-emission encoder → DONE (CleverKeys-ML, Phases E→M);
  B2 refinement head → not needed; B3 context-LM rerank → future option; B4
  router/complement integration → shipped as the opt-in `ctc` mode.

### Open questions (historical — all resolved)

1. **Licensing** — resolved by training from scratch on MIT-licensed corpora (FUTO corpus
   + How-We-Swipe); no FUTO weights/outputs used. See `NOTICE`.
2. **Runtime** — ONNX (no second inference runtime).
3. **Product posture** — complement behind the router, opt-in; default stays neural.
4. **Beam language** — Kotlin; the instrumented latency gate (median <150 ms at beam 100)
   confirms no JNI drop needed.

### Non-goals (v1, still true)

- No two-finger / multi-stream beam (FUTO's `recognize_multi`) — single-stream only.
- No context-LM in the decode module (a modular reranker over `CtcCandidate` remains a
  recorded future option).
- No during-gesture preview decode (commit-phase only).
- No langpack-backed lexicon (λ-scale constraint, As-Built "Lexicon").

---

## Recorded, not wired — options the app deliberately did not take

Written down so each choice stays visible instead of being rediscovered. Nothing in this
section is reachable from the decoder.

### The Cyrillic λ datapoint (`CtcScoringParams.tunedRuCkdt`)

`ru` is **not** in `CtcLanguageSupport.SUPPORTED`, so the adapter can never build this
preset. It is kept because it is the independent corroboration behind
`LAMBDA_CKDT_SCALE`: a Cyrillic λ sweep (CleverKeys-ML `ctc/PHASE_J.md` §6.9 — tuned on ru
val rows `0:4708`, confirmed on `4708:9416`, over both ru models, worth ≈ +1.2 in-dict
top-1) landed on **λ 2.0** for the CKDT `255 − rank` scale, the same value the Latin sweep
(`docs/eval/2026-08-15-ctc-per-language-lambda.md`) reached for fr/de/es.

**The two sweeps agree on λ and nothing else**, because they were run around different base
presets — E1 (benchmark footing) for ru, `tunedV2` (app footing) for the shipping axis — so
they differ on γ, β and γ_prune. Enabling a Cyrillic path therefore starts with a **footing
decision**, not a λ lookup, and the disagreement is pinned by test so it cannot decay into a
bug. Evidence tier: **val-only, permanently** — no Cyrillic model was decoded on test-2400
and the seal is spent. The λ also needs re-confirming with user-dictionary entries present
(λ multiplies the frequency term, so a larger λ amplifies top-of-scale injected
competitors) before any ru ship.

### "Max accuracy" pair mode — FUTURE-OPTIONAL, **not implemented**

`CleverKeys-ML/ctc/MODEL_COMPARISON.md` and `PHASE_M.md` §11.2 record a second ship option
(**option A**) the app did not take.

**What it is.** `v2pair-s1234`: the **two members of one coupled training run**, run as two
ONNX sessions, with their per-frame emission probabilities **averaged before the beam**
(probability space, one averaged log-emission matrix into the existing `CtcBeamDecoder` —
*not* a fusion of two candidate lists). Members ship at different numeric formats because
that was measured free:

| member | artifact | bytes |
|---|---|---|
| A | `phaseL_v2pair_s1234_a_int8w.onnx` | 1,554,355 |
| B | `phaseL_v2pair_s1234_b_fp16w.onnx` | 3,052,318 |
| | **total** | **4,606,673 (4.39 MB)** |

Against the shipped single model that is **+1,554,355 B ≈ +1.5 MB per ABI** and a second
ORT session (encoder 1.79 ms vs the single model's 0.83 ms class).

**Why the recipe is trustworthy.** Pair compatibility is **trained in, not gated for**: the
members are trained together with a KL coupling term (`--pair-weight`, coupling weight
**0.3**, confirmed interior-optimal on a four-point sweep). Six of six coupled pairs passed
the label-free ≥ 95 % per-frame agreement gate at **98.05–98.33 %**; the identical
`--pair-weight 0` control finished at 92.09 % and its averaged mix **collapsed to greedy
29.10** (individual members 72.6 / 71.8). That is the distinction from the older
`mix2-i8f16` "card", which hit similar numbers as a one-off draw whose recipe demonstrably
**did not** reproduce. The pair reproduces by construction — and it is also the **teacher**
the shipped model was distilled from, so shipping it is not a different bet, it is the
undistilled version of the same one.

**Its evidence tier — val-only, permanently.**

| claim | tier |
|---|---|
| 11 of 11 campaign bars on **all five seeds** (five-seed mean margins +0.12 … +2.76) — the only configuration in the campaign to do so | **val + alt-layout only** |
| val 88.86 / 92.82 / 93.59 / **91.56** / 87.46; dvorak 92.88, dvorak-app 92.59, azerty 84.11, qwertz 84.41, german 82.26, spanish 89.76 | **val + alt-layout only** |
| anything on test-2400 | **none — it was never decoded and never will be** |

The seal is spent: four unsealings, no fifth, by pre-registration. The pair was
*deliberately* not decoded (`UNSEALING_4.md` §1) because only one model ships. So the pair
is more accurate **on val** (s1234 t1 88.90 vs 88.62) with **deeper seed evidence** (5 vs 3),
while the shipped single model is the one with **sealed-split evidence**. Choosing the pair
trades an evidence tier for a few tenths — an accuracy-first call, and the ML campaign's own
recommendation was **B, the single model**.

**What implementing it would touch.**

1. `CtcEngineAdapter` — a second `MODEL_ASSET` + a second `OnnxCtcEmissionModel`, both under
   the existing bounded-retry/latch logic; warm-up and teardown cover both.
2. A new averaging decorator at the `CtcEmissionModel` seam (the `CtcSwipeDecoder`
   constructor arg): run both sessions, exponentiate, mean, re-log, hand one `CtcEmissions`
   to the beam. The beam, featurizer, trie and preset are untouched.
3. **A regenerated golden fixture** from the pair configuration at whatever preset ships —
   the fixture-and-preset rule is not optional. The fixture already stores
   `source_onnx_sha256` as an **array**, so a two-member fixture fits without a schema
   change; `CtcParityTest.fixture_model_and_shipPreset_travelTogether` would extend to hash
   both assets.
4. The app-trie preset would need its own answer: `tunedV2` was fitted on `resbn80g` and
   validated on the *single* model; nothing validates it on averaged emissions.
5. Re-run both device gates — `CtcEmissionModelParityTest` and the latency gate (budget
   roughly doubles on the encoder leg; the beam, which dominates, is unchanged).
6. APK **+~1.5 MB per ABI**, plus a second resident session's memory and a longer cold warm-up.

**Verdict recorded here: not now.** Revisit only if field feedback says the last few tenths
matter more than the sealed-split evidence tier and the size, and never without regenerating
the fixture in the same change.

Also recorded and not scheduled: the 21.8 KB rescorer, contract-v2 T′=64, a two-phase
preview decode, and user-dictionary alpha-boost with a cap (plan §7.3).

---

## Testing Strategy

### Golden-trace parity (`CtcParityTest`) — the core validation
`src/test/resources/ctc/ctc_golden.json` is frozen from the SAME Python port this module
ports (`scripts/futo_decoder_{eval,ceiling}.py`) via `scratchpad/gen_ctc_golden.py`. Cases:
- **Featurizer** (6 cases, exercising every resampler branch incl. single-point,
  zero-duration, non-uniform timestamps, long two-point): asserts the `[2,64]` tensor is
  **bit-identical** float32 to the port.
- **Beam** (6 cases: clear `cat`/`the`, an ambiguous `car/cat/cart/care` ranking under
  encoder+decoder params, two pruning-stress fields under narrow/wide beams, a random
  encoder-params field): asserts **identical greedy-CTC string, identical top-k words**
  (the ranking parity), and top-k final **scores within `1e-4`** (`Math.pow`/`ln` differ
  from the port's C-libm by ≤ ~1 ULP, so word order is the exact assertion, scores are a
  tolerance sanity).

### Unit + instrumented coverage
See the As-Built test inventory for the full wired-mode suite (module/merge/contraction/
router/provenance/hygiene pure tests; on-device model parity + latency gate).

### Verification
`sh gradlew runPureTests -PtestClass=swipe.ctc.CtcParityTest` → OK; full `runPureTests`
1907 green post-seam-fix (fb77b422). Instrumented: full ew-cli sweep green on-device
2026-08-08 (see `memory/todo.md` HANDOFF §B for the gate evidence).
