# Contraction handling — swipe (CTC + geometric) and tap: findings and fix proposal

**Date**: 2026-08-18 · **Status**: PROPOSAL (no code changed) · **Scope**: fr/it/de/es/nl/pt/sv
contraction end-to-end behaviour on all three input paths, verified against the SHIPPED assets
and the post-neural-removal code (HEAD `83220634`).

Every data claim below was verified by parsing the shipped binaries/JSON directly
(`CkdtDictionaryReader` V2 layout re-implemented in a scratch script; `contractions.bin`
CTRB-V1 layout likewise), not by reading docs.

---

## 1. How each path works today (verified, with evidence)

### 1.1 Swipe — CTC (en, fr, de, es on a–z-complete Latin layouts; the default `ctc` mode)

Router: `InputCoordinator.kt:499-524` (mode+layout), language gate `InputCoordinator.kt:654-668`
→ non-supported language falls through to geometric. Supported set:
`swipe/ctc/CtcLanguageSupport.kt:63-68` — **en, fr, de, es only**.

Per decode, `CtcEngineAdapter`:
1. Builds the merged lexicon trie; for fr/de/es the CKDT words are projected onto a–z with the
   accented canonical kept for display (`CtcEngineAdapter.kt:442-455`,
   `swipe/ctc/CtcAzProjection.kt:90-113` — highest-frequency canonical owns a surface).
2. **Injects every contraction alias key** (`ContractionManager.getAliasKeys()` — both the
   REPLACE and the APPEND file) that is a–z-spellable and not already a trie word, at the
   frequency FLOOR `MIN_FREQ = 1.0` → log-frequency bonus ≈ 0
   (`CtcEngineAdapter.kt:465`, `swipe/ctc/CtcContractionKeys.kt:79-89`,
   `swipe/ctc/CtcLexiconMerge.kt:26`).
3. After the beam: accents first, then `ContractionOverlay.apply`
   (`CtcEngineAdapter.kt:610-616`): PAIRED base → keep + append variant;
   NON-PAIRED with lexicon ordinal < 1200 → keep + append; else **replace in-slot**
   (`swipe/ContractionOverlay.kt:78-112`, threshold at `:39`).
4. Mappings are scoped to the ACTIVE decode language
   (`ContractionManager.loadSwipeDisplayMappings`, `ContractionManager.kt:136-155`;
   policy `swipe/SwipeContractionPolicy.kt:73-76`). English base loads ONLY for en.

### 1.2 Swipe — geometric (it, pt, sv, nl + any non-a–z layout, or `geometric` mode)

`GeometricEngineAdapter` applies the same `ContractionOverlay` with the same
language-scoped loader (`GeometricEngineAdapter.kt:200-226`, applied at `:276-282`) —
**but performs NO alias-key injection**. The engine can only rank words that exist in the
CKDT dictionary (`dictionaryFor`, `GeometricEngineAdapter.kt:499-559`), so an alias key
that is not itself a dictionary word is unreachable and its mapping is inert. This is the
asymmetry the CTC KDoc itself predicts (`CtcContractionKeys.kt:8-19`) but that was never
closed on the geometric side.

### 1.3 Tap typing

Two *different* `ContractionManager` instances exist:

- **Swipe instances** (one per adapter): language-scoped loader, correct.
- **Tap instance** (`ManagerInitializer.kt:86-97`): `loadMappings()` — the **English
  binary base, unconditionally** — then the PRIMARY language's file, then
  `contractions_en.json` again. Reloaded only on a primary-language *preference* change
  (`PreferenceUIUpdateHandler.kt:91-103`), same order. **The secondary language's
  contractions are never loaded into the tap instance, and English is always resident.**

Prediction flow (`SuggestionHandler.updatePredictionsForCurrentWord`,
`SuggestionHandler.kt:1847-1941`):
- exact typed partial that is a non-paired key → display form injected top-of-bar,
  score +1000 (`:1893-1899`) — **no real-word/ordinal guard**;
- paired base → variants appended, but **only for partials ≥ 3 chars** (`:1905`);
- every predicted word is transformed through `getNonPairedMapping` (`:1917-1919`) —
  **also unguarded** (the swipe paths' `ContractionOverlay` ordinal guard has no tap
  equivalent).

`WordPredictor` separately makes alias keys *predictable*: primary language keys are added
to the dictionary + prefix index (freq fallthrough → 5000 anchor,
`WordPredictor.kt:1248-1319`); secondary language keys go into the
`NormalizedPrefixIndex` at rank floor 254 (`WordPredictor.kt:45,1361-1378`). The
`contractionAliases` map additionally re-routes autocorrect winners to the display form
(`WordPredictor.kt:2100,2461-2467`) — populated from the **last-loaded primary** file only.

Live typing treats an apostrophe as a NON-letter: `SuggestionHandler.kt:1556-1574` only
extends the current word for `text[0].isLetter()`, so typing `m` `'` `a`… ends the word at
`m` and starts a new word at `a`. The apostrophe-aware dual search (`:1862-1880`) only fires
on cursor-sync. Hyphen is a hard word boundary (`PredictionContextTracker.kt:59-64`).

---

## 2. Findings table

Shipped-asset facts used below (all verified by parsing the assets):

| Asset | Fact |
|---|---|
| `contractions_fr.json` | 17,931 REPLACE entries; `mappelle→m'appelle`, `aujourdhui→aujourd'hui` present; `questce`, `estce`, `peutetre`, `yatil` **absent**; 33 keys carry accents (non-injectable) |
| `contraction_pairs_fr.json` | 183 APPEND entries; `ma→[m'a]`, `la→[l'a]`, `tas→[t'as]` present |
| `contractions_it.json` / pairs | 21,214 / 148; `dellacqua→dell'acqua`, `unaltra→un'altra` present; `lago→[l'ago]` in pairs; `cè→c'è`, `nè→n'è`, `sè→s'è`, `chè→ch'è` keyed on **accented** keys |
| `fr_enhanced.bin` (40,000 words, 0 with apostrophe/hyphen) | native concatenated aliases: `jai`@8349, `cest`@5192, `quil`@15444, `nest`@21458, `sil`@20152, `quest`@17420, `aujourdhui`@23573; **`mappelle`, `dabord` NOT present**; `jail` NOT present |
| `it_enhanced.bin` | `lago`@2143, `cè`@26689, `nè`@2522, `sè`@4606, `tè`@4623; `dellacqua`/`lacqua`/`unaltra` NOT present |
| `de_enhanced.bin` | `gehts`@3043, `gibts`@1970 native; `im`@16 |
| Alias keys native in the dictionary | fr: **84 / 17,931** replace keys; it: **18 / 21,214**; fr pairs 154/183 bases, it pairs 101/148 bases |
| `contractions_{es,pt,sv}.json` | literally `{}` — nothing mapped, nothing wrongly mapped (es `del`/`al` are fused forms and correctly appear only as ordinary dictionary words, es ordinals 11/18) |
| `contractions_de.json` | 21 entries (gehts/gibts/wenns… + d'or place names) |
| `contractions_nl.json` | 118 entries (plural possessives `autos→auto's` etc.) — but **no `nl_enhanced.bin` ships**, so nl has no bundled swipe dictionary at all |
| `en_enhanced.json` (CTC en lexicon) | natively contains the French strings `jai`(173), `cest`(156), `quest`(190) and `jail`(197) |
| `contractions.bin` + `contraction_pairings.json` + `contractions_en.json` (English base) | 120 non-paired keys, 1,183 paired display forms; **no** `jai`/`cest`/`quest` mapping |
| English-base keys colliding with the top-3000 words of another language | fr: `dont`@104 → "don't"; de: `im`@16 → "I'm"; es: none; it: none |

### Per-case verdicts

| Case (language) | Swipe CTC | Swipe geometric | Tap | Verdict |
|---|---|---|---|---|
| `m'appelle` (fr) — key `mappelle` | **WORKS**: key absent from lexicon → injected at floor (`CtcEngineAdapter.kt:465`); beam can spell it; overlay rule 2b replaces in-slot → bar shows `m'appelle`. Residual risk: floor key has 0 log-freq bonus vs ~9–11 for real words (λ=2, freq≤255), so it must win on emission evidence alone — reachability asserted only at trie level (`CtcContractionKeysTest.kt:47`), never through the real decoder | **BROKEN**: `mappelle` not in `fr_enhanced.bin`, no injection on this path → undecodable; mapping inert | **WORKS (primary=fr)**: alias in dict/prefix-index @5000 (`WordPredictor.kt:1295-1297`); exact partial hits `SuggestionHandler.kt:1893` → `m'appelle` top. **BROKEN (fr as secondary)**: predictable via rank-254 alias but SH's manager never loads fr → bar shows literal `mappelle` | PARTIAL |
| `j'ai`, `c'est`, `qu'il`, `n'est`, `s'il` (fr) | **WORKS**, and better than the productive tail: keys are NATIVE lexicon words (real frequencies; injection skips them, `CtcContractionKeys.kt:84`) → replaced in-slot (ordinals all > 1200) | **WORKS** — same native keys are geometric-decodable, overlay replaces | **WORKS (primary=fr)** via `:1893` exact hit; predictions of `jai` transformed at `:1917` | WORKS (primary); PARTIAL (secondary) |
| `l'homme`, `d'abord` (fr) | `lhomme` native@37304 → WORKS; `dabord` injected-floor → WORKS with the same floor caveat as `mappelle` | `lhomme` WORKS (native); `dabord` BROKEN (absent, no injection) | as `mappelle` | PARTIAL |
| `m'a`, `t'as`, `l'a` (fr APPEND pairs) | **WORKS**: pair bases native (`ma`@84, `tas`@2336, `la`@1) → kept + variant appended after engine candidates (`ContractionOverlay.kt:84-88,109-111`) | **WORKS** (bases decodable) | **BROKEN for 2-char bases**: `SuggestionHandler.kt:1905` gates paired injection at partial ≥ 3 chars → `ma`/`la`/`ta` never offer `m'a`/`l'a`/`t'a`. `tas` (3 chars) works | PARTIAL |
| `qu'est-ce` (fr) — hyphen compound | **BROKEN — data, not structure**: no entry with key `questce` exists in any asset. The often-cited structural limit is wrong: only the KEY must be a–z (`CtcContractionKeys.isInjectable`, `:61-68`); the VALUE may carry apostrophes, hyphens and accents — the shipped files already do (`l'abbé`, `c'è`, `d'estaing`), and `ContractionOverlay`/commit handle the value as an opaque string. `quest→qu'est` DOES exist and works, so today a user gets `qu'est` and must type `-ce` by hand | BROKEN (no key, no injection) | BROKEN (no entry; `:1893` would work if one existed — hyphen boundary only affects the *typed* side, not the injected suggestion) | BROKEN (fixable with data only) |
| `peut-être`, `est-ce`, `y a-t-il`, `aujourd'hui` (fr multi-token) | `aujourdhui→aujourd'hui` **WORKS today** (entry present AND key native@23573 — existence proof for the compound class). `peutetre`/`estce`/`yatil` absent → BROKEN (same data gap; `y a-t-il` additionally needs a space in the value — untested territory) | `aujourd'hui` WORKS; others BROKEN | same split | PARTIAL |
| `dell'acqua`, `un'altra`, `l'acqua` (it) | n/a — **it is not CTC-served** (`CtcLanguageSupport.kt:63-68`); falls through to geometric | **BROKEN**: keys not in `it_enhanced.bin`, no injection. Only 18/21,214 replace keys are geometric-reachable — the productive Italian elision class is effectively dead on the only swipe engine Italian has | **WORKS (primary=it)** via `:1893`/`:1917`; BROKEN as secondary | BROKEN on swipe |
| `l'ago` (it pair) | n/a | **WORKS**: `lago`@2143 native → kept + `l'ago` appended | WORKS (primary=it, 4 chars ≥ 3) | WORKS |
| `c'è`, `n'è`, `s'è` (it) | n/a | **WORKS on layouts with an `è` key**: keys are accented (`cè`), `cè`@26689 IS an it dictionary word → decodable → replace → `c'è`. **BROKEN on plain QWERTY** (no `è` to swipe; key non-injectable anyway) | WORKS if the user types the accented `è` (exact-match at `:1893`); typing plain `ce` gives nothing (`ce`@292 has no mapping — correctly, it's a real word) | PARTIAL |
| `geht's`, `gibt's` (de) | **WORKS**: `gehts`@3043 / `gibts`@1970 native in the de lexicon → replaced in-slot. Note: replace (not append) means a user preferring the informal apostrophe-free spelling `gehts` loses it from the slot — `gehts` ordinal is > 1200 so the guard doesn't keep it | WORKS (native keys) | WORKS (primary=de) | WORKS |
| es (`del`/`al`) | Nothing mapped (file `{}`) — correct; fused forms are plain dictionary words | same | same; English-base leak has zero top-3000 es collisions (`id` is paired-classified and 2 chars < 3 gate, so inert) | WORKS (by absence) |
| nl / pt / sv | pt/sv: files `{}`, nothing to overlay — intended (`SwipeContractionPolicy.kt:69-71`). nl: 118 mappings but **no bundled dictionary at all** → no swipe; tap only via langpack | — | — | OUT OF SCOPE (below) |
| **de `im` / fr `dont` on TAP** | swipe immune (language-scoped loader + ordinal guard) | swipe immune | **BROKEN**: English base always resident in the tap manager (`ManagerInitializer.kt:88`) + unguarded transform (`SuggestionHandler.kt:1893,1917`) → German typing `im` (16th most common word) gets `I'm` top and the prediction `im` rewritten; French typing `dont` (104th) gets `don't`. This is the open P2 in `memory/todo.md:438-449` | BROKEN |

---

## 3. Root causes

**Data problems**
- D1. **No hyphen-compound entries.** The generator (`scripts/extract_apostrophe_words.py`)
  only extracts apostrophe words and derives the key by removing apostrophes; corpus
  tokenization splits on hyphens, so `qu'est-ce`/`est-ce`/`peut-être` never become entries.
  The 2026-08-17 trim then removed accent/hyphen-KEYED leftovers — correct in itself, but it
  cemented the false belief that hyphen-carrying *forms* are unreachable. Only keys are
  constrained to a–z; values are free.
- D2. **38 accent-keyed entries** (33 fr, 5 it — incl. Italian's `cè→c'è`, arguably the most
  common Italian contraction) are non-injectable on CTC and unreachable on QWERTY. On
  layouts bearing the accented letter, geometric reaches them only because the accented
  concatenation happens to be an it dictionary word.
- D3. **`en_enhanced.json` contains French debris** (`jai` 173, `cest` 156, `quest` 190) with
  no English mapping for them → an English CTC decode can put literal `jai`/`cest` on the
  bar. Pre-existing (this lexicon is test-2400-pinned), not introduced by the removal.
- D4. **16 fr / 2 it replace keys are shadowed by an accented display sibling** on CTC-style
  accent recovery (e.g. `nes`: beam surface displays as `nés` per `CtcAzProjection`
  freq-wins rule, so `n'es` can never fire). Low value; most shadowed elisions are rarer
  than the accented word that wins.

**Code problems**
- C1. **Geometric path has no alias injection** (`GeometricEngineAdapter` — no counterpart
  to `CtcEngineAdapter.kt:465`). Since Italian is geometric-only, Italian's entire
  productive elision class (21k entries, 0.1% reachable) is dead on swipe. This is the
  single biggest functional gap found.
- C2. **Tap manager loads the English base for every language and never loads the
  secondary language** (`ManagerInitializer.kt:86-97`, `PreferenceUIUpdateHandler.kt:91-103`),
  and the tap transform/injection is unguarded (`SuggestionHandler.kt:1893,1917`) — the
  documented open P2 (de `im`, fr `dont`). The language-scoped loader that fixes it already
  exists (`loadSwipeDisplayMappings`).
- C3. **Paired injection ≥ 3-char gate** (`SuggestionHandler.kt:1905`) was written for
  English possessive noise but kills the French 2-letter clitic pairs (`ma→m'a`, `la→l'a`).
- C4. **Live typing treats `'` as a word terminator** (`SuggestionHandler.kt:1556-1574`),
  so typing `m'appelle` with the apostrophe gets no prediction/completion help at all
  (the committed characters are fine; only assistance is absent). Cursor-sync already has
  the dual-search; live typing does not.

---

## 4. Lost test coverage (folded in per coordinator request)

`ContractionFrequencyTest.kt` was deleted in `64f401d2` with the neural engine. What it
actually pinned: **pure arithmetic over `VocabularyUtils.calculateCombinedScore` with
hard-coded simulated frequencies** ("qu'est rank ~20", "jai rank 5") — it never read a real
asset, never ran a decoder, and its `jai_vs_jail` / `quest vs qu'est` names promised more
than the assertions delivered. Nevertheless the *scenarios* are live requirements and are
now untested end-to-end.

**Did the frequency floor invert the asserted orderings? NO — verified:**
- In the **French** lexicon, `jai` and `quest` are NATIVE words (rank bytes 145/162 →
  λ·ln bonuses ≈ 9.4/9.1). `CtcContractionKeys.inject` explicitly skips existing keys
  (`CtcContractionKeys.kt:84`), so the floor never touches them; the overlay then replaces
  them in-slot with `j'ai`/`qu'est`. `jail` does not exist in `fr_enhanced.bin`, so the
  jai-vs-jail contest cannot occur in a French decode at all.
- In the **English** lexicon the contest also cannot occur as imagined: `jai` sits in
  `en_enhanced.json` at 173 vs `jail` 197, but there is no en mapping to `j'ai`, and since
  2026-08-16 French forms in an English slate are *by policy* a bug
  (`SwipeContractionPolicy.kt`), so the old mixed-vocabulary premise is obsolete — the
  correct en-side worry is D3 (literal `jai` junk), not ordering.
- The floor ONLY affects keys absent from the lexicon (`mappelle`, `dabord`, the ~17.8k fr /
  ~21k it tail). Those never had an asserted ordering; their risk is "beam never surfaces a
  0-bonus path" — real, deliberate (KDoc: reachable, never preferred), and untested.

**No regression was shipped today for the previously asserted pairs.** The genuine loss is
that nothing now exercises contraction *ranking through a real decoder on real assets* —
replacement tests are items F6/F7 below.

---

## 5. Prioritised fix proposal

Ordered by user impact ÷ risk. "Pure" = runs in `runPureTests` on ARM64.

| # | Fix | Type | Files | Risk | Test |
|---|---|---|---|---|---|
| **F1** | **Close the tap P2**: route the tap `ContractionManager` through `loadSwipeDisplayMappings`-style language scoping — load primary + secondary languages' files, English base only when en is one of them; reload on language change (the hook at `PreferenceUIUpdateHandler.kt:94` already exists). This kills de `im→I'm` and fr `dont→don't` and simultaneously fixes the secondary-language literal-alias bar (`mappelle` shown raw). Keep `isKnownContraction`/`isContractionKey` autocorrect protection fed from the same set | Code | `ManagerInitializer.kt`, `PreferenceUIUpdateHandler.kt`, possibly a small `ContractionManager` loader variant (multi-language union, earlier-wins already exists) | Medium — hot typing path, en behaviour must stay byte-identical when primary=en/secondary=none; the todo's own note says mirror, don't invent a second policy | Pure: extend `PipelineOracleJvmTest`/new `TapContractionScopeTest` with a primary/secondary matrix (en-only, fr-only, en+fr, de+en) asserting `im`/`dont` survive and `m'appelle` appears; instrumented smoke via ew-cli |
| **F2** | **Geometric alias reachability** (the Italian fix): merge injectable alias keys into the geometric dictionary at tail ordinals (append after base words, mirroring `CtcContractionKeys`' floor philosophy — the engine's `−λ_f·ln(1+rank)` prior then maximally penalises but does not exclude them). Alternative considered and rejected: enabling `it` on CTC — blocked on validation data (zero it rows in the FUTO corpus, `CtcLanguageSupport` KDoc) | Code | `GeometricEngineAdapter.kt` (`dictionaryFor` merge step), reuse `ContractionManager.getAliasKeys` + an `isInjectable`-equivalent over the layout's letter set | Medium — 21k extra candidates in the it template index; measure decode p95 (existing instrumented latency gate) and memory before shipping; ranking risk is bounded by the tail-rank prior | Pure: `GeometricSwipeEngine` decode over a synthesized `dellacqua` trace on the real `it_enhanced.bin` + merged aliases; assert `dell'acqua` reaches the slate. Reuse the geometric oracle harness |
| **F3** | **Hyphen-compound data**: extend `scripts/extract_apostrophe_words.py` to also emit corpus-attested hyphen/apostrophe compounds with fully a–z-stripped keys — `questce→qu'est-ce`, `estce→est-ce`, `peutetre→peut-être` (REPLACE bucket; none of the keys collides with a real fr word — verified absent from `fr_enhanced.bin`). Regenerate fr/it assets. `aujourd'hui` already proves the class works end-to-end | **Data only** | `scripts/extract_apostrophe_words.py`, `contractions_fr.json`, `contractions_it.json` (+ the count pins in `BundledContractionDataTest`) | Low — additive entries; keys verified non-words; values are opaque strings to overlay/commit | Pure: add the new keys to `BundledContractionDataTest` expectations; a `ContractionOverlay` case asserting a hyphen-carrying value replaces in-slot |
| **F4** | **Accent-keyed Italian entries**: re-key `cè/nè/sè/chè` additionally as APPEND pairs on the real words users can actually swipe on QWERTY is wrong (`ce`@292 → appending `c'è` to every `ce` swipe is plausible but noisy) — instead (a) keep the accented-key entries for accented layouts, and (b) add pairs entries `ce→[c'è]`, `ne→[n'è]`, `se→[s'è]` gated by the existing append semantics (variant appended last, never displacing). Decide with the maintainer; data-only either way | Data | `contraction_pairs_it.json` | Low mechanically; product judgement needed on noise | Pure: `ContractionOverlay` matrix + `BundledContractionDataTest` |
| **F5** | **Drop the ≥3 gate for paired injection when the pair base is an exact 2-char match in a language whose pairs file carries 2-char bases** (fr `ma/la/ta`), or simply lower the gate to ≥2 for non-en. The gate exists for English possessive noise (`t→t's`), which the per-language pairs files don't contain | Code (2 lines) | `SuggestionHandler.kt:1905` | Low | Pure: tap-path oracle asserting typing `ma` (fr) offers `m'a` |
| **F6** | **Replacement coverage for the deleted `ContractionFrequencyTest`, done right**: a pure test that builds the REAL fr trie through the shipping path (`CkdtDictionaryReader` + `CtcCkdtLexicon` + `CtcAzProjection` + `CtcLexiconMerge` + `CtcContractionKeys.inject` — all pure) and asserts: (a) `jai`/`quest`/`cest` are native with freq > floor; (b) `mappelle`/`dabord` are injected and `contains()`-reachable; (c) overlay maps each to its display form in the right bucket; (d) `jail` is absent from fr. Plus a decoder-level case: run `CtcBeamDecoder` with a synthetic ideal-path emission fixture for `mappelle` on the real fr trie and assert the floor key survives to top-K (this is the one assertion nothing covers today) | Test | new `src/test/.../swipe/ctc/CtcContractionRankingTest.kt` | None (test-only); the emission fixture needs care — reuse the existing golden-fixture pattern (`CtcParityTest`) | Pure (`runPureTests`) |
| **F7** | Clean D3: strip `jai`/`cest`/`quest`-class French debris from `en_enhanced.json` OR add en-side mappings — **do neither casually**: the lexicon is λ=4.0/test-2400-pinned. Recommend: leave the lexicon, add the three strings to the en *disabled-by-default* candidates list only if user reports surface; document | Data (deferred) | — | Touching the pinned en lexicon invalidates the validated λ | — |
| **F8** | C4 (apostrophe ends the live-typed word): extend the `:1862` dual-search to the live path by letting `'` extend `currentWord` when between letters (the extractor already has that rule, `PredictionContextTracker.kt:648-666`). Benefits all languages incl. English (`don'` mid-word) | Code | `SuggestionHandler.kt:1556-1574`, `PredictionContextTracker` | Medium — word-boundary semantics feed autocorrect/undo; needs the BackspaceUndo suite re-run | Pure: context-tracker unit tests + tap oracle |

Suggested order: **F1 → F6 → F3 → F2 → F5**, then F4/F8 as follow-ups, F7 documented-only.
F3 is the only item needed to make `qu'est-ce` producible at all; F2 is the only one that
makes Italian swipe contractions real.

## 6. Not worth fixing

- **`y a-t-il` and other space-carrying values** — committing multi-word suggestions
  crosses into next-word-prediction territory (context tracking, backspace-undo, learn
  funnel all assume single tokens). If wanted later, it's a next-word feature, not a
  contraction entry.
- **pt/sv/es contraction files** — correctly empty; es fusions (`del`, `al`) are ordinary
  words (ordinals 11/18) and need no apparatus. Nothing is wrongly mapped (verified: zero
  English-base collisions in the es/it top-3000).
- **nl** — has 118 mappings but ships no dictionary (`nl_enhanced.bin` does not exist);
  contraction plumbing is moot until an nl langpack/dictionary exists. Not a contraction bug.
- **D4 (16 fr / 2 it accent-shadowed replace keys)** — the accented dictionary word that
  wins the surface is almost always what the user wanted (`nés` over `n'es`); fixing would
  require per-surface multi-display plumbing for marginal gain.
- **de `gehts` replaced rather than kept** — Duden prefers `geht's`; users wanting the
  informal spelling can tap-through or add a custom word. Moving 21 de entries to pairs is
  a 5-minute data change if ever requested.
- **CTC for Italian** — blocked on evaluation data, not effort (zero it/pt/sv rows in the
  FUTO corpus); F2 is the honest path.

## 7. Where the stated assumptions were wrong (evidence)

1. **"`qu'est-ce` may be structurally impossible on the swipe path" — FALSE for CTC.** Only
   the *key* must be a–z (`CtcContractionKeys.isInjectable`); the *value* is an opaque
   display string. Shipped counter-examples already ship: `aujourdhui→aujourd'hui` (works
   end-to-end today, key even lexicon-native@23573), `labbe→l'abbé`, de `destaing→d'estaing`.
   The 2026-08-17 trim removed accent/hyphen-**keyed** entries; `questce` has an a–z key and
   was simply never generated. It IS structurally impossible on geometric today (C1) and on
   any path until the data exists (D1).
2. **"The floor may have inverted `j'ai` vs `jail`" — NO.** `jai`/`quest`/`cest` are native
   CKDT words in fr (injection skips them, so they carry real frequencies), and `jail`
   doesn't exist in the fr lexicon; in en the contest is policy-obsolete. The deleted test
   was formula arithmetic over simulated numbers, not an asset-backed pin (§4).
3. **The maintainer's mental model "fr swipe = the alias-injection path"** undersells the
   data: the 6 most common French elisions (`jai`, `cest`, `quil`, `nest`, `sil`, `quest`)
   plus `lhomme`/`aujourdhui` are lexicon-NATIVE concatenations with real frequencies —
   injection only carries the productive tail. That is why French feels fine on CTC while
   Italian (geometric, no injection, only 18 native keys) is broken.
4. **"English forms must not appear in a French slate" is enforced on swipe but NOT on
   tap** — `ManagerInitializer.kt:88` loads the English base unconditionally; fr `dont`
   (ordinal 104) → `don't` is live today on tap. The open P2 in `memory/todo.md:438` is
   real and its blast radius is exactly two high-frequency words (fr `dont`, de `im`) plus
   the missing-secondary-language gap.
5. **`ContractionOverlay`'s ordinal guard is doing no work for fr/it replace data** — 0 of
   the 84 fr (18 it) dictionary-native replace keys rank < 1200 (the d496b682 regeneration
   already moved real-word collisions to the pairs file). It remains load-bearing only for
   uncurated imported langpacks, exactly as its KDoc claims.
