# Web Demo Pipeline — Findings & Statuses After CTC Engine Rework

**Date:** 2026-08-11. Re-audit of `web_demo/` after the CTC engine rework (38021523..62c9419f, 2026-08-08) against the prior review of the a22b76ad production-parity port (2026-07-21). Verified at source HEAD; `swipe-vocabulary.js` and `custom-dictionary.js` are untouched since a22b76ad (`git log a22b76ad..HEAD -- <files>` is empty), so all four prior findings carry forward unchanged.

## Findings table (status as of 2026-08-11)

| Id | Sev | Description | Status | Evidence |
|---|---|---|---|---|
| F1 | major | "Full 150k" `swipe_vocabulary.json` mode functionally gutted: rare-word gate `max(minFreqByLength, CONFIG_MIN_FREQ=0.01)` applies the APK-scale floor to raw probabilities. Measured: **6 of 150,252** words have freq ≥ 0.01; every tier-0 word (~138k) is filtered, leaving only the ~12k tier-listed (`common_words` 7,002 ∪ `top_5000` 5,000) surfaceable. | **STILL OPEN** — rework never touched this file | `web_demo/swipe-vocabulary.js:21` (CONFIG_MIN_FREQ), `:219-222` (tier synthesis), `:328-331` (gate) |
| F2 | minor | Tap-typing pools shrunk: `findWordCompletions` iterates `commonWords` (now 100 in default APK mode) then `top5000` (now 3,000); `findFuzzyMatches` slices `commonWords` to 200 but it only holds 100. Repurposing the sets as tier ranks starved the readers. | **STILL OPEN** | `web_demo/demo/index.html:3105-3120`, `:3166`; sets built at `web_demo/swipe-vocabulary.js:164-165` |
| F3 | cosmetic | `removePersonalWord` → `unboostWord` deletes from `wordFreq`/`commonWords`/`top5000` but never removes the word from (or invalidates) the masking trie — removed word stays beam-reachable until page reload. | **STILL OPEN** | `web_demo/custom-dictionary.js:291-298`, `:204-217`; no `trieRoot` touch |
| F4 | pre-existing | `applyContractionFixup` rewrites PAIRED contraction bases unconditionally (`well`→`we'll`, `were`→`we're`); production skips paired bases via `contractionPairings` (`OptimizedVocabulary.kt:463-466`, v1.2.2 fix). **Scope WIDENED by the rework**: the same fixup now also runs on CTC suggestion chips and on auto-insert for all three engines. | **STILL OPEN, scope grew** | `web_demo/demo/index.html:1976-1984` (fixup), `:1803` (CTC chips), `:1954` (auto-insert); `web_demo/contractions_en.json:89-90` |

## Rework regression check (transformer path): NO REGRESSION

- **Trie masking / prod rerank intact**: `processSwipeTransformer` is the a22b76ad path plus timing/return plumbing (`index.html:1896-1945`); `applyTrieMasking` parity block still in `runInference` (`:2184`), `filterPredictions` rerank + keep-best dedupe still in `displayPredictions` (`:2434-2450`).
- **Custom-word trie insertion still on all add paths**: every add route (`addPersonalWord`, imports at `:2802/:2827/:2921`) funnels through `updateVocabularyWithCustomWord`, which calls `insertWordIntoTrie` (`:2702-2703`).
- **Settings persistence sound**: same `cleverkeys.demo.config.v2` key; the disabled wasm-threads toggle is deliberately NOT read back on apply, so a saved `true` isn't clobbered by the disabled-checkbox-reads-false trap (`:4038-4042`). Correct fix.
- **Vendored ORT didn't change validateFile**: same HEAD + range-GET size probe with LFS-pointer-scale floors, still run on the four transformer files (`:634-687`, `:738-741`). CTC assets skip `validateFile` but are guarded by the `CKCTCV1` magic check (`ctc-engine.js:215-219`) and ONNX parse. `initOnnxRuntime` pins `wasmPaths='vendor/ort/'`, `numThreads=1` (`:710-712`); engine failures degrade independently (`syncEngineOptions`, `:1715-1735`).

## CTC additions (assessed on their own terms)

The CTC path is **fully independent** of `SwipeVocabulary`: `CtcTrie` over `ctc_vocab.bin` (Uint16 freqs → `log(freq+1e-10)`, `ctc-engine.js:273-274`), FUTO Viterbi beam with its own final scoring `score/L^γ + β·depth + λ·logFreq` (`:466`). There is **no min-frequency gate at all**, so it does NOT inherit the F1 probability-scale trap.

| Id | Sev | New finding |
|---|---|---|
| F5 | minor | CTC engines ignore custom/personal words entirely — the 147k lexicon is fixed at blob-build time with no insertion API, so a word added via the demo's personal-dictionary UI is unreachable whenever a CTC engine is selected. Demo-only engines, but the UI doesn't say so. (`ctc-engine.js:211-343`; only `swipeVocabulary` gets custom words) |
| F6 | cosmetic | README overclaims "there is no CDN dependency" (`web_demo/README.md:12`) while the page still pulls Tailwind from `cdn.tailwindcss.com` (`demo/index.html:7`) — already logged as a follow-up in `memory/todo.md` by 62c9419f, but the README asserts the opposite. |

**Sweep-test claims spot-check (1663e4bf): sound.** `browser_test.mjs` replays the same synthetic trajectories `ctc_reference.py` decoded, through the production entry point `window.processSwipe`. Parity = featurizer elementwise vs Python float32 (`results.json`: maxAbsDiff **0**) + beam top-1 string, full top-8 ordering, and greedy string vs Python (3/3 words × both engines all match; maxScoreDiff ~2e-6). Honest caveats are in-file: the beam comparison runs on browser-WASM emissions vs native-Python emissions (end-to-end, not beam-isolated), and the transformer's 5/9 synthetic top-1 is labeled out-of-distribution, not a quality claim. `maxScoreDiff` stops accumulating at the first ordering mismatch (`browser_test.mjs:192-197`) — irrelevant while top-8 matches, but would under-report drift if it ever diverged.

## Live deploy verification (62c9419f)

Per the todo record: no workflow change needed (`deploy-web-demo.yml` already recursed `web_demo/demo/` into `site/dist/demo/`; dist 36 MB, run 31290929882). Live check at cleverkeys.app/demo with real mouse-path swipes (curved/eased/dwell, key centres from live DOM), 4 words × 3 engines: ch128 4/4 (6.1–11.3 ms), resbn80 4/4 (3.9–4.7 ms), transformer 3/4 (`hello`→`herpetological`, 327–472 ms). All model/vocab/wasm requests 200, `ort-wasm-simd.wasm` served `application/wasm`; only console error is the site-wide `/favicon.ico` 404. Follow-ups filed: favicon, Tailwind CDN.

## Prioritized remediation

1. **F1** — in `loadFromJSON` (full-dict mode), scale the rare-word gate to the dict's own distribution: skip `CONFIG_MIN_FREQ` (it encodes an APK byte-score scale) and rely on the dict's shipped `min_frequency_by_length`, or renormalize frequencies at load the way `loadFromFlatFreq` does. One function, restores the entire optional mode.
2. **F4** — port the production paired-base skip: build the paired set from `contractions_en.json` entries whose key is itself a dictionary word (or vendor the APK's `contractionPairings`), and leave those bases unrewritten in `applyContractionFixup`. Fixes transformer AND CTC display in one place.
3. **F2** — decouple tap-typing pools from tier sets: have `findWordCompletions`/`findFuzzyMatches` walk `wordFreq`/`wordsByLength` (or a dedicated top-N list) instead of `commonWords`/`top5000`.
4. **F5** — either merge custom words into the CTC blob path at trie-build time (CSR trie is constructed in-browser from the buffer, so an append hook is feasible) or show a one-line "custom words: transformer only" note when a CTC engine is active.
5. **F3** — on `removePersonalWord`, set `swipeVocabulary.trieRoot = null` (lazy rebuild on next swipe) — cheaper than implementing trie node deletion.
6. **F6** — reword the README claim to "no CDN dependency for models/runtime; Tailwind styling still loads from CDN (follow-up filed)" or land the Tailwind vendoring follow-up and keep the claim.
