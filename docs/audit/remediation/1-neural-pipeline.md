# Neural Pipeline — Verification & Remediation

Adversarial re-verification of the prior neural-pipeline audit against current source
(HEAD `b2a25742a`). Evidence is fresh file:line. All paths under
`src/main/kotlin/tribixbite/cleverkeys/`.

## Verification Results

| # | Finding | Verdict | Evidence |
|---|---------|---------|----------|
| 1 | P0 — encoder `memory` OnnxTensor + `OrtSession.Result` leaked every swipe | **CONFIRMED** | `EncoderWrapper.encode()` runs `encoderResults = encoderSession.run(...)` (EncoderWrapper.kt:90), extracts `memory = encoderResults.get(0)` (EncoderWrapper.kt:94), returns `EncoderResult(memory,...)` (EncoderWrapper.kt:111); `finally` closes only the 3 **input** tensors (EncoderWrapper.kt:116-121) — never `encoderResults`. `predict()` reads `memory = encoderResult.memory` (SwipePredictorOrchestrator.kt:383) and never closes it. `OrtDecoderSession.cleanup()` closes only `cachedSrcLengthTensor` (OrtDecoderSession.kt:133-139). `BeamSearchEngine.cleanup()` only delegates to `decoderSession.cleanup()` (BeamSearchEngine.kt:590-596). `GreedySearchEngine` never references `memory.close` (only reads `memory` at GreedySearchEngine.kt:61). `rg` over `src/main` shows **zero** `memory.close()` / `encoderResults.close()`. Tensor is f32 `[1, seq_len≤250, d_model=256]` (model_config.json) → up to **256 KB native/off-heap per swipe**, GC-invisible. |
| 2 | P1 — singleton captures arbitrary `Context`, no `applicationContext` | **CONFIRMED** | `SwipePredictorOrchestrator(private val context: Context)` (SwipePredictorOrchestrator.kt:30); `getInstance(context)` stores first caller's context verbatim (kt:45-49), no `.applicationContext`. `rg applicationContext` in onnx/ + engine + calibration = **0 hits**. Activity callers: `DictionaryManagerActivity.kt:466` (`getInstance(this)`), `SwipeCalibrationActivity.kt:127` (`NeuralSwipeTypingEngine(this,...)` → `getInstance(context)` in engine init, NeuralSwipeTypingEngine.kt:41). If an Activity wins the init race, it is pinned for process lifetime (context stored in singleton + captured by `OptimizedVocabulary`, `ModelLoader`, `PrefixBoostTrie`, executor thread). |
| 3 | P1 — greedy path leaks final decoder `Result` on EOS break | **CONFIRMED** | GreedySearchEngine.kt:82-89: `if (bestToken == EOS_IDX) { break }` executes **before** `result.close()` at line 89. The `finally` (kt:90-92) closes only `targetTokensTensor`, not `result`. So the last decoder `Result` (holds the log-probs OnnxTensor) leaks on every greedy prediction that terminates via EOS — i.e. nearly all of them. |
| 4 | P1 — unsynchronized concurrent inference + cleanup race; shared mutable trajectory buffers; TOCTOU on `!!` | **CONFIRMED** | `predict()` is **not** `@Synchronized` (only `initialize()` is, kt:146). `cleanup()` (kt:710-716) is unsynchronized, closes both sessions and sets `isModelLoaded=false`. Concurrent drivers exist: `SuggestionHandler` has its own `predictionExecutor` (SuggestionHandler.kt:167,1182), `InputCoordinator` a separate one (per MEMORY.md dual-pipeline), `SwipeCalibrationActivity` calls `neuralEngine.predict()` on its own thread (SwipeCalibrationActivity.kt:1076) — all target the **same singleton**. `SwipeTrajectoryProcessor` holds shared reusable buffers "cleared and reused on each call" (SwipeTrajectoryProcessor.kt:52-57); concurrent `extractFeatures()` corrupts them. `predict()` dereferences `encoderWrapper!!` (kt:382) / `decoderSession!!` (kt:407,414) with no guard vs a concurrent `cleanup()` nulling `isModelLoaded`/closing sessions → TOCTOU crash (`ORT session closed` / NPE). |
| 5 | P1 — "Pure ONNX NO fallbacks" is inaccurate | **CONFIRMED (all 3 sub-claims)** | (a) `predict()` `catch (e: Exception)` → `return Result(emptyList(), emptyList())` (SwipePredictorOrchestrator.kt:472-476) — silent empty result. (b) `NeuralSwipeTypingEngine.initialize()` `catch` sets `initialized = true; return false` (NeuralSwipeTypingEngine.kt:79-84) — marks initialized even on hard failure. (c) `PredictionPostProcessor.process()` "2. Fallback: Basic filtering" branch when vocab absent (PredictionPostProcessor.kt:50-59). |
| 6 | P2 — ~2,300 lines dead code shipped | **CONFIRMED (larger than claimed)** | Zero external refs (self-TAG only): `ComprehensiveTraceAnalyzer` (657), `onnx/MemoryPool` (191, main-code refs = 0), `onnx/BroadcastSupport` (183), `onnx/SessionConfigurator` (95, only a code comment mentions it). `DecoderWrapper` (285) is constructed (SwipePredictorOrchestrator.kt:220) but **never invoked** — `rg 'decoderWrapper\.'` = 0 hits (predict() uses `OrtDecoderSession`/`GreedySearchEngine` directly); its `decodeSingle`/`decodeBatched` are dead. Dead **cluster**: `TemplateBrowserActivity` (294) is absent from AndroidManifest.xml (only 1 `<activity>` declared, no `TemplateBrowser`) → unlaunchable; it and `ComprehensiveTraceAnalyzer` are the only users of `WordGestureTemplateGenerator` (335), whose only user besides them is dead; `WordGestureTemplateGenerator` + those two are the only users of `ContinuousGestureRecognizer` (916). Whole subgraph is unreachable → **~2,202 lines** (CGR+WGTG+CTA+TemplateBrowser) + 754 (MemoryPool+BroadcastSupport+SessionConfigurator+DecoderWrapper) ≈ **2,956 dead LOC**. |
| 7 | P2 — duplicated dictionary stacks; `DictionaryManager.getPredictions()` no prod caller | **PARTIAL** | Duplication CONFIRMED: `DictionaryManager` holds `predictors: Map<String,WordPredictor>` each running `loadDictionaryAsync` → `BinaryDictionaryLoader` (DictionaryManager.kt:22,133-139); the orchestrator's `OptimizedVocabulary` loads its own `VocabularyTrie` from `en_enhanced.json` (OptimizedVocabulary.kt:41,970) — two parallel dictionary stacks. `getPredictions()` (DictionaryManager.kt:158) has **no production caller** (`rg` hits for `.getPredictions(` outside this class are all `BigramStore`/`ContextModel`, a different symbol) — CONFIRMED dead method. REFUTED sub-claim: `DictionaryManager` itself is **not** dead — it's live for user-word tracking (`addUserWord`/`isUserWord`, SuggestionHandler.kt:775/829/894, InputCoordinator.kt:326). |
| 8 | P2 — `ModelLoader.loadModelBytes` uses `stream.available()`, truncates content:// | **CONFIRMED** | ModelLoader.kt:147: `val buffer = ByteArray(stream.available())`; the read loop (kt:149-153) stops at `buffer.size` and never grows the buffer. For `content://` streams `available()` may under-report → silently truncated model, then `createSession` on a partial file. Affects the imported-model path (kt:119-124). Bundled assets happen to report full size, masking it. |
| 9 | P2 — beam scoring inconsistencies; `NEURAL_BEAM_SCORE_GAP` 80 vs doc 8; dead diversity consts | **PARTIAL** | (a) Ranking normalizer uses `it.tokens.size` incl. SOS (+EOS for finished beams) (BeamSearchEngine.kt:172) while final confidence uses `wordStr.length` (kt:570) — differ by 1-2, so sort-rank-0 ≠ highest-confidence-word. CONFIRMED. (b) `Defaults.NEURAL_BEAM_SCORE_GAP = 80.0f` (Config.kt:147) vs the `BeamSearchEngine` ctor default `scoreGapThreshold = 8.0f` (kt:35) and spec doc `scoreGapThreshold=8.0` (neural-prediction-spec.md). CONFIRMED **doc drift**, but the ctor default is never used in prod (orchestrator always passes 80, kt:419) so it's a documentation/dead-default issue, not a live 10× behavior bug. (c) `DIVERSITY_LAMBDA=0.5f` (kt:67) never read; `parentBeam` only copied never scored (kt:76,93) — dead. CONFIRMED. |
| 10 | P2 — beam hot-loop allocation churn | **PARTIAL** | CONFIRMED: `BeamState(beam)` copy-ctor per candidate per step (kt:339,348; ctor kt:89-96, `ArrayList(other.tokens)` copy); `PriorityQueue<Int>` boxes every vocab index each `getTopKIndices` call (kt:517); `FloatArray(logits.size)` per beam per step in `applyPrefixBoosts` (kt:443) + `logSoftmax` allocates 1-2 new arrays per call (kt:488,504). REFUTED: **no regex** is compiled per candidate — `rg 'Regex\(|toRegex|Pattern.compile'` in BeamSearchEngine = 0 hits; the per-char cost is `ch.toString().startsWith("<")` (kt:386,545), a String alloc, not a regex. |

## Remediation Steps (severity-ordered)

### R1 [P0] — Free the encoder memory tensor + Result every swipe (Finding 1, 3)

**Root cause**: `OrtSession.Result` is `AutoCloseable` and owns its output tensors; closing
the `Result` closes `memory`. Nobody closes either. Two independent fixes: encoder must
not orphan the `Result`, and the greedy path must close its per-step `Result` even on EOS.

**Fix A — make `memory` closeable via the wrapper, close it in `predict()`.**
`EncoderWrapper.encode()` (EncoderWrapper.kt:88-121): the current shape returns a bare
`OnnxTensor` while dropping the owning `Result`. Change the wrapper to keep and expose the
`Result` so the orchestrator can close it (closing the `Result` closes `memory`).

`EncoderWrapper.kt` — before:
```kotlin
data class EncoderResult(val memory: OnnxTensor, val inferenceTimeMs: Double)
...
val encoderResults = encoderSession.run(encoderInputs)
...
val memory = encoderResults.get(0) as? OnnxTensor ?: throw ...
return EncoderResult(memory, inferenceTimeMs)
```
after:
```kotlin
// Result owns `memory`; caller MUST close it after decoding.
data class EncoderResult(
    val memory: OnnxTensor,
    val results: OrtSession.Result,   // owner handle
    val inferenceTimeMs: Double
) : AutoCloseable {
    override fun close() { try { results.close() } catch (_: Exception) {} }
}
...
val encoderResults = encoderSession.run(encoderInputs)   // NOT in try-with-close
...
val memory = encoderResults.get(0) as? OnnxTensor
    ?: run { encoderResults.close(); throw RuntimeException("no memory tensor") }
require(memory.info.shape.size == 3) { ... }   // if this throws, close first
return EncoderResult(memory, encoderResults, inferenceTimeMs)
```
(Guard the `require` blocks so a validation failure also closes `encoderResults`.)

`SwipePredictorOrchestrator.predict()` (SwipePredictorOrchestrator.kt:381-431) — wrap the
decode in try/finally:
```kotlin
val encoderResult = encoderWrapper!!.encode(features)
try {
    val memory = encoderResult.memory
    ... // greedy or beam block, both read `memory`
} finally {
    encoderResult.close()   // frees memory + Result exactly once, all paths
}
```
This also covers the exception path (predict's outer catch) — the `finally` runs before it.

**Fix B — greedy path: close each step `Result` on EOS.**
`GreedySearchEngine.kt:66-92` — before:
```kotlin
val result = decoderSession.run(inputs)
...
if (bestToken == EOS_IDX) { break }   // leaks `result`
tokens.add(bestToken)
result.close()
```
after (move close into the inner `finally`, so it runs on the EOS break too):
```kotlin
val result = decoderSession.run(inputs)
try {
    val logitsTensor = result.get(0) as OnnxTensor
    ...
    if (bestToken == EOS_IDX) break
    tokens.add(bestToken)
} finally {
    result.close()          // closes on EOS-break, normal path, and exception
    targetTokensTensor.close()
}
```
Remove the now-redundant outer `targetTokensTensor.close()`.

**Test**: `EncoderWrapperLeakTest` (pure JVM, uses a fake `OrtSession`/`Result` counting
`close()` calls) asserting `results.close()` fires exactly once per `encode`+`EncoderResult.close`.
Instrumented `NeuralMemoryLeakInstrumentedTest`: run 500 predictions (greedy and beam),
sample `Debug.getNativeHeapAllocatedSize()` before/after, assert delta < a few MB (allowing
allocator slack). Add an EOS-terminating greedy case explicitly.

**Risk**: low. Double-close of an ORT `Result` throws; the guarded `close()` swallows it.
Ensure `memory` is not read after `encoderResult.close()` — the `finally` runs after the
decode block returns its candidate list (already deep-copied to `PredictionPostProcessor.Candidate`),
so no use-after-free. Beam path already deep-copies logits in `OrtDecoderSession.runSequential`
(OrtDecoderSession.kt:73-80), so closing memory after `engine.search()` is safe.

### R2 [P1] — Synchronize predict/cleanup; normalize context; per-call trajectory buffers (Finding 2, 4)

**Fix A — serialize inference and teardown.** Add a private lock and guard both entry points:
```kotlin
private val inferenceLock = Any()

fun predict(input: SwipeInput): PredictionPostProcessor.Result = synchronized(inferenceLock) {
    if (!isModelLoaded) return PredictionPostProcessor.Result(emptyList(), emptyList())
    val enc = encoderWrapper ?: return PredictionPostProcessor.Result(emptyList(), emptyList())
    ... // capture encoderWrapper/decoderSession into locals ONCE, use locals not !!
}

fun cleanup() = synchronized(inferenceLock) {
    encoderSession?.close(); decoderSession?.close()
    encoderSession = null; decoderSession = null
    encoderWrapper = null; decoderWrapper = null
    isModelLoaded = false; isInitialized = false
}
```
Replace every `encoderWrapper!!` / `decoderSession!!` in `predict()` (kt:382,407,414) with the
locals captured inside the lock, eliminating the TOCTOU on `!!`. This costs throughput
(predictions serialize) but the ONNX session is already effectively single-threaded and both
driver executors are single-threaded; contention is only cross-pipeline. Alternatively, use a
`ReentrantReadWriteLock` (read=predict, write=cleanup) to allow overlap only among predicts —
but concurrent predicts still corrupt the shared trajectory buffers (Fix C), so plain
`synchronized` is the safe first cut.

**Fix B — normalize the captured context.** `SwipePredictorOrchestrator.getInstance` (kt:45-49):
```kotlin
fun getInstance(context: Context): SwipePredictorOrchestrator =
    instance ?: synchronized(instanceLock) {
        instance ?: SwipePredictorOrchestrator(context.applicationContext).also { instance = it }
    }
```
Also `NeuralSwipeTypingEngine` (NeuralSwipeTypingEngine.kt:41) and any Activity caller should
pass `applicationContext`. Verify no code path needs an Activity/themed context (grep the
orchestrator's `context` uses — all are prefs/assets/`OrtEnvironment`, none need a UI context).

**Fix C — make trajectory extraction reentrant OR document single-threaded.** Cleanest with
Fix A already serializing: keep the reusable buffers but they're now protected by
`inferenceLock`. If read-concurrency is desired later, `extractFeatures` must allocate
local buffers instead of the shared `reusable*` fields (SwipeTrajectoryProcessor.kt:53-57).

**Test**: `OrchestratorConcurrencyTest` (JVM) spinning N threads calling `predict()` +
one calling `cleanup()`, asserting no exception and no cross-contaminated results (feed
distinct inputs, assert each output matches its input). `ContextLeakTest` asserting the
stored context is `applicationContext` when constructed from a mock Activity.

**Risk**: medium. Serializing predict could add latency if calibration and IME truly run
simultaneously; in practice calibration is a separate screen (IME not active). Confirm no
deadlock: `initialize()` is `@Synchronized` (object monitor) and would be called under a
different lock than `inferenceLock` — keep them distinct and never nest.

### R3 [P1] — Make failure modes honest (Finding 5)

- `NeuralSwipeTypingEngine.initialize()` (kt:79-84): on catch, set `initialized = false`
  (not `true`) so a subsequent call retries, and surface the failure to the caller
  (already returns `false`). Before→after: `initialized = true` → `initialized = false`.
- `SwipePredictorOrchestrator.predict()` catch (kt:472-476): keep returning empty (never
  crash the IME) but add a `@Volatile var lastError: String?` set here and exposed via a
  getter, and always `Log.e` (already present). Optionally emit one throttled user-visible
  signal (debug log) so "no predictions" is distinguishable from "model broke".
- `PredictionPostProcessor` "Basic filtering" fallback (kt:50-59): this branch only runs when
  `vocabulary == null || !isLoaded()`, which in production means vocab failed to load. Either
  (a) treat that as an init failure upstream (preferred — matches "pure ONNX"), or (b) rename
  the comment/branch to "vocab-unavailable passthrough" and log a warning. Do **not** silently
  pass raw NN output as if filtered.

**Test**: `NeuralInitFailureTest` — inject a ModelLoader that throws; assert
`isNeuralAvailable()==false` and a second `initialize()` re-attempts. **Risk**: low; behavior
is strictly more honest. Watch that IME still degrades gracefully (empty suggestions, no crash).

### R4 [P2] — Delete dead code (Finding 6, 7-partial, 9-diversity)

Remove (verify no test-only refs first; tests referencing them should be deleted too):
`ComprehensiveTraceAnalyzer.kt`, `WordGestureTemplateGenerator.kt`,
`ContinuousGestureRecognizer.kt`, `TemplateBrowserActivity.kt` (whole unreachable cluster,
~2,202 LOC), `onnx/MemoryPool.kt` (191), `onnx/BroadcastSupport.kt` (183),
`onnx/SessionConfigurator.kt` (95). Remove `DecoderWrapper.kt` (285) **and** its
construction (SwipePredictorOrchestrator.kt:63,220) — it's assigned but never invoked; drop
the `decoderWrapper` field and the `broadcastEnabled` local. Delete `DictionaryManager.getPredictions()`
(kt:153-180, no prod caller). Delete `DIVERSITY_LAMBDA` and the unused `parentBeam` field
+ its copy-ctor line (BeamSearchEngine.kt:67,76,93).

**Test**: `./gradlew compileDebugKotlin` + `runPureTests` after each deletion; delete
`onnx/MemoryPoolTest.kt`, `onnx/BroadcastSupportTest.kt` (they test dead code).
**Risk**: low but verify the `onnx/` tests and any `TemplateBrowser`/`ComprehensiveTrace`
instrumented tests are removed together, else CI red.

### R5 [P2] — Fix content:// model truncation (Finding 8)

`ModelLoader.loadModelBytes` (kt:146-155) — replace the `available()`-sized buffer with a
size-agnostic read:
```kotlin
return inputStream.use { it.readBytes() }   // Kotlin stdlib, grows dynamically
```
`readBytes()` reads to EOF regardless of `available()`. **Test**: `ModelLoaderTest` feeding a
`content://`-style stream whose `available()` under-reports (e.g. a `BufferedInputStream`
wrapping a pipe) and asserting full byte count. **Risk**: low; `readBytes()` allocates the
whole file (already the case). For very large external models it grows via array-doubling —
acceptable for one-time load.

### R6 [P2] — Reconcile beam scoring + docs (Finding 9, 10)

- Make ranking and final-confidence use the **same** length basis. In the sort comparator
  (BeamSearchEngine.kt:171-176) normalize by the decoded word length (exclude SOS/EOS), matching
  `convertToCandidate` (kt:570), or vice-versa — pick one and reuse a shared
  `private fun normFactor(len: Int): Float`. Before: sort uses `it.tokens.size`; after: uses the
  same char-count used for confidence.
- Align docs: set `neural-prediction-spec.md` / `ARCHITECTURE_MASTER.md` score-gap default to
  `80.0` OR change `Defaults.NEURAL_BEAM_SCORE_GAP` if 8.0 was intended — decide via a quick
  accuracy A/B in `SwipeCalibrationActivity`. Also set the `BeamSearchEngine` ctor default to
  match `Defaults` (8→80) so the never-used default stops contradicting prod.
- Allocation churn (optional perf): reuse a scratch `FloatArray` for `logSoftmax`/`applyPrefixBoosts`,
  replace `PriorityQueue<Int>` in `getTopKIndices` (kt:511-536) with a primitive partial-sort
  over an index array. Defer unless profiling shows GC pressure.

**Test**: `BeamScoringConsistencyTest` — construct beams of varying length, assert the sort
order equals the final-confidence order. **Risk**: **behavioral** — unifying the length basis
changes which word ranks first for finished vs unfinished beams; gate behind the calibration
accuracy harness before shipping.

## Refutations / Corrections to prior audit

1. **Finding 1 mechanism sharpened**: the leak is not just "a bare tensor" — `EncoderWrapper`
   drops the entire `OrtSession.Result` (EncoderWrapper.kt:90) which owns `memory`. The fix
   must close the `Result`, not the tensor field. Size is bounded: `[1, ≤250, 256]` f32 ≈
   256 KB max (not unbounded), but native/off-heap so it accumulates until process death.
2. **Finding 6 undercounts and mis-describes**: `ContinuousGestureRecognizer` is **not**
   "never instantiated in isolation" — it *is* referenced by `WordGestureTemplateGenerator`
   and `TemplateBrowserActivity`. The correct claim is that the *entire cluster is an
   unreachable subgraph* (TemplateBrowserActivity is absent from AndroidManifest.xml, only 1
   activity is declared). Total dead LOC is ~2,956, higher than the ~2,300 estimate.
3. **Finding 7 overstated**: `DictionaryManager` is **not** dead — it backs user-word add/lookup
   (SuggestionHandler, InputCoordinator). Only `getPredictions()` is dead. The dual-dictionary
   memory duplication is real, but `DictionaryManager`'s `WordPredictor` trie is used for
   `isUserWord`/user-word matching, not merely a redundant predictor.
4. **Finding 9 partially overstated**: `NEURAL_BEAM_SCORE_GAP=80` does **not** cause a live 10×
   deviation — the `8.0f` value is only the `BeamSearchEngine` constructor *default*, which prod
   never uses (orchestrator always injects 80). It is doc drift + a misleading dead default, not
   a runtime bug. The ranking/confidence length-basis mismatch *is* a real (mild) inconsistency.
5. **Finding 10 partially refuted**: there is **no regex recompiled per candidate**.
   BeamSearchEngine contains zero `Regex(`/`toRegex`/`Pattern.compile`. The per-char cost is a
   `String` allocation from `ch.toString().startsWith("<")`. Other churn claims (BeamState clones,
   boxed PQ, per-beam FloatArray, per-call softmax arrays) are accurate.
6. **`rg` output caveat**: on this device the `grep`/`rg` shell function injects `-G`, which
   mangled a first-pass search (rendered `neural_beam_score_gap` as `n`). Verified all field
   names by direct `Read` of Config.kt — the source is intact; the mangling was a tooling
   artifact, not a codebase issue.

## Effort estimate to reach A grade

Current neural-pipeline subscore ≈ **C+/B-** (one P0 native leak, two P1 correctness/lifecycle
issues, honesty gaps, ~3k dead LOC).

| Work | Effort | Subscore lift |
|------|--------|---------------|
| R1 (P0 leak: encoder Result + greedy EOS) + tests | 3-4 h | C+ → B |
| R2 (synchronize predict/cleanup, app-context, reentrant buffers) + concurrency test | 4-6 h | B → B+ |
| R3 (honest init/predict/postproc failure) + test | 2 h | +correctness credit |
| R4 (delete ~3k dead LOC + dead tests) | 2-3 h | maintainability → A- |
| R5 (readBytes truncation) + test | 30 min | robustness |
| R6 (scoring consistency + doc + optional perf), gated on calibration A/B | 3-5 h | A- → A |

**Total ≈ 15-20 h** to reach a defensible **A** (A- achievable in ~12 h with R1-R4). R1 and
R2 are the gating items: the P0 native leak and the unsynchronized-cleanup TOCTOU are the only
findings that can cause user-visible failures (OOM over a long session; a crash when the
keyboard is disabled/updated mid-swipe). R4 is the largest score lever per hour (pure deletion).
Targets: **Correctness A** (R1+R2+R3+R5), **Maintainability A** (R4), **Consistency/Docs A-**
(R6).
