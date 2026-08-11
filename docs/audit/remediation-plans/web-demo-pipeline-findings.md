# Web Demo Pipeline — Findings & Statuses After CTC Engine Rework

**Date:** 2026-08-11. Re-audit of `web_demo/` after the CTC engine rework (38021523..62c9419f, 2026-08-08) against the prior review of the a22b76ad production-parity port (2026-07-21). Verified at source HEAD; `swipe-vocabulary.js` and `custom-dictionary.js` are untouched since a22b76ad (`git log a22b76ad..HEAD -- <files>` is empty), so all four prior findings carry forward unchanged.

## Findings table (status as of 2026-08-11)

> **All six findings are remediated** — see "Remediation landed" below for the
> per-finding change, verification and residual caveats.

| Id | Sev | Description | Status | Evidence |
|---|---|---|---|---|
| F1 | major | "Full 150k" `swipe_vocabulary.json` mode functionally gutted: rare-word gate `max(minFreqByLength, CONFIG_MIN_FREQ=0.01)` applies the APK-scale floor to raw probabilities. Measured: **6 of 150,252** words have freq ≥ 0.01; every tier-0 word (~138k) is filtered, leaving only the ~12k tier-listed (`common_words` 7,002 ∪ `top_5000` 5,000) surfaceable. | **FIXED** (35cbaee3) | `web_demo/swipe-vocabulary.js:21` (CONFIG_MIN_FREQ), `:219-222` (tier synthesis), `:328-331` (gate) |
| F2 | minor | Tap-typing pools shrunk: `findWordCompletions` iterates `commonWords` (now 100 in default APK mode) then `top5000` (now 3,000); `findFuzzyMatches` slices `commonWords` to 200 but it only holds 100. Repurposing the sets as tier ranks starved the readers. | **FIXED** (35cbaee3) | `web_demo/demo/index.html:3105-3120`, `:3166`; sets built at `web_demo/swipe-vocabulary.js:164-165` |
| F3 | cosmetic | `removePersonalWord` → `unboostWord` deletes from `wordFreq`/`commonWords`/`top5000` but never removes the word from (or invalidates) the masking trie — removed word stays beam-reachable until page reload. | **FIXED** (35cbaee3) | `web_demo/custom-dictionary.js:291-298`, `:204-217`; no `trieRoot` touch |
| F4 | pre-existing | `applyContractionFixup` rewrites PAIRED contraction bases unconditionally (`well`→`we'll`, `were`→`we're`); production skips paired bases via `contractionPairings` (`OptimizedVocabulary.kt:463-466`, v1.2.2 fix). **Scope WIDENED by the rework**: the same fixup now also runs on CTC suggestion chips and on auto-insert for all three engines. | **FIXED** (35cbaee3) | `web_demo/demo/index.html:1976-1984` (fixup), `:1803` (CTC chips), `:1954` (auto-insert); `web_demo/contractions_en.json:89-90` |

## Rework regression check (transformer path): NO REGRESSION

- **Trie masking / prod rerank intact**: `processSwipeTransformer` is the a22b76ad path plus timing/return plumbing (`index.html:1896-1945`); `applyTrieMasking` parity block still in `runInference` (`:2184`), `filterPredictions` rerank + keep-best dedupe still in `displayPredictions` (`:2434-2450`).
- **Custom-word trie insertion still on all add paths**: every add route (`addPersonalWord`, imports at `:2802/:2827/:2921`) funnels through `updateVocabularyWithCustomWord`, which calls `insertWordIntoTrie` (`:2702-2703`).
- **Settings persistence sound**: same `cleverkeys.demo.config.v2` key; the disabled wasm-threads toggle is deliberately NOT read back on apply, so a saved `true` isn't clobbered by the disabled-checkbox-reads-false trap (`:4038-4042`). Correct fix.
- **Vendored ORT didn't change validateFile**: same HEAD + range-GET size probe with LFS-pointer-scale floors, still run on the four transformer files (`:634-687`, `:738-741`). CTC assets skip `validateFile` but are guarded by the `CKCTCV1` magic check (`ctc-engine.js:215-219`) and ONNX parse. `initOnnxRuntime` pins `wasmPaths='vendor/ort/'`, `numThreads=1` (`:710-712`); engine failures degrade independently (`syncEngineOptions`, `:1715-1735`).

## CTC additions (assessed on their own terms)

The CTC path is **fully independent** of `SwipeVocabulary`: `CtcTrie` over `ctc_vocab.bin` (Uint16 freqs → `log(freq+1e-10)`, `ctc-engine.js:273-274`), FUTO Viterbi beam with its own final scoring `score/L^γ + β·depth + λ·logFreq` (`:466`). There is **no min-frequency gate at all**, so it does NOT inherit the F1 probability-scale trap.

| Id | Sev | New finding | Status |
|---|---|---|---|
| F5 | minor | CTC engines ignore custom/personal words entirely — the 147k lexicon is fixed at blob-build time with no insertion API, so a word added via the demo's personal-dictionary UI is unreachable whenever a CTC engine is selected. Demo-only engines, but the UI doesn't say so. (`ctc-engine.js:211-343`; only `swipeVocabulary` gets custom words) | **FIXED** (35cbaee3) |
| F6 | cosmetic | README overclaims "there is no CDN dependency" (`web_demo/README.md:12`) while the page still pulls Tailwind from `cdn.tailwindcss.com` (`demo/index.html:7`) — already logged as a follow-up in `memory/todo.md` by 62c9419f, but the README asserts the opposite. | **FIXED** (35cbaee3) |

**Sweep-test claims spot-check (1663e4bf): sound.** `browser_test.mjs` replays the same synthetic trajectories `ctc_reference.py` decoded, through the production entry point `window.processSwipe`. Parity = featurizer elementwise vs Python float32 (`results.json`: maxAbsDiff **0**) + beam top-1 string, full top-8 ordering, and greedy string vs Python (3/3 words × both engines all match; maxScoreDiff ~2e-6). Honest caveats are in-file: the beam comparison runs on browser-WASM emissions vs native-Python emissions (end-to-end, not beam-isolated), and the transformer's 5/9 synthetic top-1 is labeled out-of-distribution, not a quality claim. `maxScoreDiff` stops accumulating at the first ordering mismatch (`browser_test.mjs:192-197`) — irrelevant while top-8 matches, but would under-report drift if it ever diverged.

## Live deploy verification (62c9419f)

Per the todo record: no workflow change needed (`deploy-web-demo.yml` already recursed `web_demo/demo/` into `site/dist/demo/`; dist 36 MB, run 31290929882). Live check at cleverkeys.app/demo with real mouse-path swipes (curved/eased/dwell, key centres from live DOM), 4 words × 3 engines: ch128 4/4 (6.1–11.3 ms), resbn80 4/4 (3.9–4.7 ms), transformer 3/4 (`hello`→`herpetological`, 327–472 ms). All model/vocab/wasm requests 200, `ort-wasm-simd.wasm` served `application/wasm`; only console error is the site-wide `/favicon.ico` 404. Follow-ups filed: favicon, Tailwind CDN.

## Prioritized remediation (as planned)

1. **F1** — in `loadFromJSON` (full-dict mode), scale the rare-word gate to the dict's own distribution: skip `CONFIG_MIN_FREQ` (it encodes an APK byte-score scale) and rely on the dict's shipped `min_frequency_by_length`, or renormalize frequencies at load the way `loadFromFlatFreq` does. One function, restores the entire optional mode.
2. **F4** — port the production paired-base skip: build the paired set from `contractions_en.json` entries whose key is itself a dictionary word (or vendor the APK's `contractionPairings`), and leave those bases unrewritten in `applyContractionFixup`. Fixes transformer AND CTC display in one place.
3. **F2** — decouple tap-typing pools from tier sets: have `findWordCompletions`/`findFuzzyMatches` walk `wordFreq`/`wordsByLength` (or a dedicated top-N list) instead of `commonWords`/`top5000`.
4. **F5** — either merge custom words into the CTC blob path at trie-build time (CSR trie is constructed in-browser from the buffer, so an append hook is feasible) or show a one-line "custom words: transformer only" note when a CTC engine is active.
5. **F3** — on `removePersonalWord`, set `swipeVocabulary.trieRoot = null` (lazy rebuild on next swipe) — cheaper than implementing trie node deletion.
6. **F6** — reword the README claim to "no CDN dependency for models/runtime; Tailwind styling still loads from CDN (follow-up filed)" or land the Tailwind vendoring follow-up and keep the claim.

## Remediation landed (2026-08-11, 35cbaee3)

**F1 — scale-aware frequency handling** (`swipe-vocabulary.js`). `SwipeVocabulary`
now records which scale `wordFreq` is on (`freqScale`: `'normalized'` for the
byte-score dicts, `'probability'` otherwise), and `filterPredictions` only
applies `CONFIG_MIN_FREQ` on the normalized scale — on `en_enhanced` that floor
rejects 0 of 98,140 words, i.e. production parity is unchanged. The plan's
"rely on the shipped per-length floors" option was rejected on its own: it fixes
the gate but leaves the *linear* frequency term (`0.2·0.57·freq`) at ~1e-7 for a
probability dict, so ranking silently degenerates to confidence-only. Instead
`loadFromJSON` maps the shipped probabilities onto the production `[0.001, 1.0]`
band once, at load, with a monotone log10 min-max transform (6.72 decades for
this dict) and pushes `min_frequency_by_length` through the same transform.
Result: one scale end-to-end, the production filter runs verbatim, and the
dictionary author's per-length intent survives (those floors reject 9.2% of
tier-0 words raw, 10.7% after mapping + the 0.01 floor — versus 100% before).
The pre-a22b76ad `log10(freq + 1e-10) / -10` mapping was deliberately NOT
restored: it is rank-inverting (rarer words scored higher). Divergence is
demo-only — production never loads this dictionary.

**F4 — paired-base skip** (`demo/index.html`, `contraction_pairings_en.json`).
The 1,744 paired bases are vendored verbatim from the APK asset
`src/main/assets/dictionaries/contraction_pairings.json` (its key set) and
fetched alongside `contractions_en.json`; `applyContractionFixup` returns paired
bases unchanged, mirroring `OptimizedVocabulary.kt:463-466`. 14 of the shipped
contraction map's 120 keys are affected: `editors girls hell hes intl readers
shed shell shes states wed well were whore`. Runtime derivation ("key is itself
a dictionary word") was evaluated and REJECTED — `en_enhanced.json` contains 119
of the 120 contraction keys (aliases are baked into the dictionary), so that
test would have disabled the rewrite entirely. A 14-word built-in fallback
covers a failed fetch. `i` → `I` capitalisation is now checked *before* the
paired-base skip (`i` is itself a paired base). One fixup function serves chip
render, auto-insert, `selectWord` and the CTC chips.

**F2 — dedicated tap pools** (`swipe-vocabulary.js`, `demo/index.html`).
`ensureTapPools()` builds `tapCompletionPool` (top 20,000 of `wordFreq`,
freq-descending) and `tapFuzzyPool` (top 2,000), lazily and invalidated on every
vocabulary mutation. `findWordCompletions`/`findFuzzyMatches` read those; the
`slice(0, 200)`-of-100 nit is gone with the tier-set reads. `commonWords` /
`top5000` keep their production tier-rank meaning untouched. Fixing this
surfaced a latent crash: `levenshteinDistance` declared its two rows `const` and
then swapped them, so **every** fuzzy-match call threw `Assignment to constant
variable` — fixed to `let` (pre-existing, unrelated to the tier-set change, but
`findFuzzyMatches` could not be verified without it).

**F5 — CTC custom words** (`demo/ctc-engine.js`, `demo/index.html`). `CtcTrie`
gained a mutable overlay: node attribute arrays grow in place
(`NODE_GROWTH_CHUNK = 256`), post-build children live in
`extraChildren` (node → flat `[char, target, …]`), and the beam's descend step
consults it behind a single null check (zero cost when no custom words exist).
`insert()` / `remove()` / `customWordCount` mirror production's custom-word
insertion into `VocabularyTrie`; a boosted blob word is restored rather than
deleted on removal. Custom words get AOSP-scale frequency 128 (blob range
1..222, median 50) so they beat ~97% of the lexicon without outranking `the`.
Wired through `updateVocabularyWithCustomWord` (covers add / import / niche
words), `removePersonalWord`, `clearCustomDictionary`, plus a one-shot
`seedCtcCustomWords()` for words restored from localStorage. No UI disclosure
was needed since the functional fix landed. Residual: nodes created for a
since-removed custom word stay allocated (a handful of dead edges the beam can
walk into but never finalise on) — cheaper than compacting CSR.

**F3 — masking-trie removal** (`swipe-vocabulary.js`, `custom-dictionary.js`).
Real removal, not the planned `trieRoot = null` invalidation (which would
re-pay the full 98k trie build on the next swipe): `removeWordFromTrie()` walks
the node chain, clears the end marker and prunes nodes that become empty,
refusing to unmap anything still reachable from `wordFreq` or the contraction
aliases. `unboostWord` calls it after the `wordFreq` delete and invalidates the
tap pools.

**F6 — README** (`web_demo/README.md`). Claim corrected: no CDN takes part in
decoding, Tailwind styling is still CDN-loaded, vendoring stays a filed
follow-up. Vendoring Tailwind was not attempted — the page is ~200 KB of utility
classes plus an inline `tailwind.config`, so a hand-rolled subset is the larger
and riskier change.

### Verification

Headless harness (`$TMPDIR/ckdemo/smoke_f1f6.js`, same VM + DOM-stub +
onnxruntime-web pattern as the a22b76ad parity smoke): runs the **shipped**
inline script from `demo/index.html` with the real models, dictionaries and
`ctc-engine.js`. **56/56 pass**, including: full-dict tier-0 words surviving the
gate (`ethereum`/`albinism`/`serendipity`) with a witness that the pre-fix
arithmetic dropped them; equal-confidence ranking now following frequency;
`well`/`were`/`hell`/`wed`/`shed`/`shell`/`hes` unrewritten while
`dont`→`don't`, `cant`→`can't`, `i`→`I` still hold, asserted on the CTC chip,
auto-insert and `selectWord` paths; tap completions returning `program…` /
`zone…` from the restored pool; a personal word added through the UI reaching
BOTH tries, decoding through the CTC beam, then disappearing from both on
removal with the trie pruned back to its pre-add shape; and the transformer
parity smoke still **7/7**. A separate stress run inserts 600 words to exercise
multiple array reallocations (9/9 pass, blob decoding unchanged throughout).

Repo harness `web_demo/tests/run_browser_tests.mjs` (headless Chromium,
`serve.py`): top-1 results are **identical to the committed `results.json`
baseline** — transformer 5/9, ctc_ch128 8/9, ctc_resbn80 8/9, featurizer
max|diff| vs Python 0, beam top-1/top-8 parity on all 3 words × 2 engines
(maxScoreDiff ~1e-6), same single pre-existing page error (favicon 404). The new
`contraction_pairings_en.json` is served correctly through the deploy-layout
flatten (`/demo/contraction_pairings_en.json` → 200), and CI's
`for pattern in '*.json'` copy step picks it up with no workflow change.
