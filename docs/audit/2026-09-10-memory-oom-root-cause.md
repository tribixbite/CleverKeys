# Memory audit: IME recreation and Java heap OOM

Date: 2026-09-10. Status: evidence reviewed; lifecycle mitigations implemented,
validation recorded below. MiB means 1,048,576 bytes; MB/kB are decimal.

## Verified evidence and limits

The Samsung SM-S938U1 running the pre-fix v2.0.0/200002 APK exhausted its
**256 MiB Java heap**. Physical RAM and total process PSS do not measure the same
limit. The allocation site identifies where allocation failed, not the owner of
retained memory. The first failure was in `keyCellIsEmpty`; it does not prove a
swipe-path leak.

In `build/memory-phone-28699.log`, service destruction at **13:18:54.319** and
creation at **13:18:54.406** were 87 ms apart. `init.enter` reported **242.5 MiB**
already allocated. Dictionary/CTC rebuilding encountered OOM at 13:19:04–05.
A later GC freed 111 MiB at 13:20:05. The user observed the issue on screen-on.
These events support a recreation-overlap hypothesis. They do **not** prove that
an unreachable graph simply needed more than 87 ms to collect, or identify the
phone's retaining root. The production app rejects `am dumpheap`; the attempted
Perfetto trace contained no heap graph.

A corrected synthetic Pixel 7 / API 34 recreation test **did** identify this root:

```text
GC native global
  → WindowOnBackInvokedDispatcher$OnBackInvokedCallbackWrapper.mCallbackRef
  → CallbackRef.mStrongRef
  → InputMethodService$$ExternalSyntheticLambda3.f$0
  → destroyed CleverKeysService
```

Two retired English-only services retained **82,002,810 bytes total**, about
39.1 MiB each, in `build/memory-shark-corrected.txt`. The original diagnostic
(`build/memory-shark-analysis.txt`) also retained a service through an
instrumentation-thread Java local. That contaminated diagnostic is not evidence
of a production root. The corrected probe reads service references only on main
and returns primitives to the instrumentation thread.

Commit `358cd54b` detached adapters during terminal InputCoordinator shutdown and
removed Keyboard2View's unused Predictor reference. The same native root then
retained only **1,107,881 bytes total** across two retired services (~554 kB each),
a **98.65% reduction**. See `build/memory-shark-fixed.txt`. All six English trie
loads completed in that test. The framework retention itself remains; this fix
makes the retained service shed its large dictionary payload.

## Measured memory budget

The isolated EN+IT predictor/CTC staged test measured settled Java allocation,
not a dominator census and not a complete bilingual service UI:

| Stage | Java bytes | MiB |
|---|---:|---:|
| Baseline | 2,511,224 | 2.4 |
| Context | 2,740,944 | 2.6 |
| English predictor | 21,012,728 | 20.0 |
| Add Italian predictor | 39,080,040 | 37.3 |
| Add English CTC trie | 68,152,264 | 65.0 |
| Add Italian CTC trie | 91,259,536 | 87.0 |
| First decode | 91,296,288 | 87.1 |
| After 100 further decodes | 91,298,712 | 87.1 |

Source: `build/ew-memory-stages/`, run
`f0d26cdb-3418-439b-8b2a-0c5359cb226f`. The last 100 decodes added 2,424 bytes.
The full English-only service first show measured 60,197,712 bytes after the
retention fix; after five service replacements it measured 62,997,400 bytes.
A 15-second held/released swipe remained near 63 MB. Source:
`build/ew-memory-fixed/`, run `4e41ac4a-1b68-4e2b-980d-c838189bfb60`.

These measurements replace the previous unsupported 110–135 MiB bilingual budget,
35–50 MiB transient estimate, and exact class census. That census conflated
phone bilingual trie counts with an English-only recreation dump. Graphics/PSS
must not be added to Java heap allocation as though they shared one limit.
`tools/hprof_analyzer.py` is a supplementary inspection tool; a shallow census
alone does not establish strong-reference dominators. Retained-byte claims above
come from the corrected Shark analysis.

All cloud runs enabled verbose logging. `LOCAL_BUILD=true` enables it in local
release builds; debug builds enable it independently. Logging overhead is real,
but these runs do not support logging alone as the source of accumulating heaps.

## Findings and disposition

### 1. Retired services retained large dictionaries — proven and mitigated

The native callback root is proven on the emulator, not yet on the Samsung phone.
The existing `358cd54b` fix remains appropriate. Running workers keep their own
adapter until they finish; teardown never clears a mutable trie beneath a worker.
The existing 250 ms ORT shutdown safety timeout remains in place.

### 2. Obsolete asynchronous dictionary work — valid code defects

The prior loader cancelled only its primary Future, did not gate already-posted
callbacks, and left secondary tasks untracked. Coordinator teardown stopped the
observer but not those tasks. Disabling a secondary language could race an older
worker publishing it again. These are independently valid lifecycle races, even
though their contribution to the recorded phone OOM was not measured.

The remediation uses instance-owned primary/secondary cancellation and guarded
publication, with terminal predictor disposal. The shared executor is not shut
down when a single service retires. Cooperative binary/trie loading checks stop
obsolete allocation; cancellation must propagate without publishing partial data
or being converted into a synchronous fallback load. A coroutine rewrite is not
required to enforce those lifecycle guarantees.

### 3. Fold listeners created during measurement — valid code defect

Both `calculateDynamicKeyboardHeight()` and `getUserKeyboardHeightPercent()`
constructed `FoldStateTracker` objects, each registering a WindowInfoTracker
listener without a matching close. This was confirmed in source; no separate
heap trace quantifies its contribution on the phone.

Both now use the existing service-owned tracker passed through
KeyboardComponentGraph. CleanupHandler already closes that tracker. Closing also
clears its callback and folding feature references. Measurements now observe the
same live fold state as ConfigurationManager without registering more listeners.

### 4. CTC warm-up allocation — bounded cache, cancellation improved

`onStartInputView` requests warm-up, but `lexiconFor` returns a memoized trie when
language/content version match. It does **not** rebuild on every show. The cache
is bounded to two trie memos; custom words, disabled words, platform user words,
and installed-pack fingerprints affect its version. Ordinary show/hide was stable.

Trie loaders now check interruption per word and throw rather than return partial
tries. CTC JSON parsing and phase/publication checkpoints also observe interruption.
The existing worker runner interrupts obsolete warm-up and teardown work; these
checks make the allocation loops cooperate. Native inference and individual I/O
calls are not guaranteed to stop immediately.

A process-wide dictionary singleton is **not required by this evidence**. The
suggested `OptimizedVocabulary` class was deleted on 2026-08-18. The actual runtime
lexicons merge mutable user/configuration inputs, so a shared cache needs bounded
ownership and invalidation design; treating all dictionaries as immutable would
risk stale predictions. Existing bounds and lifecycle disposal address the proven
retention without introducing that architecture.

## Why v1.5 could behave differently

Source comparison against the `v1.5.0` tag (2026-09-11) establishes an ownership
change: NeuralSwipeTypingEngine used `SwipePredictorOrchestrator.getInstance(context)`.
That static singleton owned its OptimizedVocabulary. Its cleanup closed encoder/decoder
sessions and reset initialization flags, but did not clear the singleton or replace
its vocabulary object. Retired service wrappers therefore did not each own a distinct
copy of that swipe vocabulary. PredictionCoordinator.shutdown also nulled neuralEngine.

The replacement CTC adapter owns its own language tries. Before `358cd54b`,
InputCoordinator.shutdown stopped the adapter but retained its reference, allowing
framework-rooted retired services to retain separate trie graphs. The corrected
emulator dump demonstrates that failure mode in the new architecture. This is a
concrete reason recreation can be more costly than in v1.5, not proof that CTC's
single-instance total is larger than the old engine's total.

The unused view predictor reference, unclosed height-helper fold trackers, and weak
primary-load cancellation already existed in v1.5. Its secondary dictionary loader
was synchronous; the later asynchronous version introduced the stale-secondary
publication race fixed here. No matched v1.5/current phone heap comparison has been
run, so the exact difference in steady-state or peak heap remains unmeasured.

A bounded process-wide swipe cache could recover some old reuse behavior. It is a
valid future optimization with content invalidation and owner-independent state,
not a prerequisite for the now-tested teardown mitigation. A singleton alone is
not evidence of safe context ownership or correct lifecycle cleanup.

## Validation and remaining work

Current-round verification passed:

- Guarded `assembleDebug assembleDebugAndroidTest runPureTests runMockTests`:
  Kotlin app/test compilation, **2,377 pure tests and 731 mock tests**, 4m55s.
  Log: `build/memory-audit-fix-validation.log`. Existing MockK String/StringBuilder
  backing-field warnings appeared without assertion failures. The first run caught
  non-void signatures in two new JUnit methods; corrected before the passing run.
- [Pixel 7 / API 34 cloud run](https://emulator.wtf/o/64da92b3-67fb-427a-b56d-11e62fff8751/r/e401aeb0-a5c7-44d9-8db6-e390b1abc031):
  **4 tests, 0 failures/errors/skips**, 64.873 seconds of test execution, isolated
  with Orchestrator. XML and logcat: `build/ew-memory-audit-fixes/`.
- Ten strongly retained retired predictors: **zero stale callbacks**, no primary or
  secondary dictionary restored. A live replacement loaded **98,140 words**;
  its cancelled Italian secondary stayed disabled.
- Contraction-reader cancellation propagated the original exception and closed
  the stream. Pure regressions cover dequeued callbacks, independent slot
  cancellation, noncooperative work, bounded pending tasks, and interrupted trie/CKDT
  loading. The metadata regression verifies private builds preserve serving state.
- EN+IT: first decode **91,326,136 bytes**, after 100 further decodes
  **91,336,248 bytes** (+10,112 bytes), consistent with the previous ~87 MiB result.
- Full English service: first show **60,228,512 bytes**, after five replacements
  **63,024,912 bytes** (+2.67 MiB). All five retired services remained alive and
  all six trie builds completed. Held/released 15-second swipe:
  **63,064,992 → 63,118,160 bytes**. The 16 MiB growth gates passed.
- Independent local diff review found no blocking issue; `git diff --check` passed.
  Final post-build source edits only corrected thread-ownership comments and a
  test assertion's explanatory message; executable behavior was unchanged.

<!-- TODO: Validate the fixed minified release on the Samsung after install/restart
approval. No production-phone installation or restart is part of this round. -->
The phone's latest recorded Java allocation was 197,332 KiB (~192.7 MiB), with
PSS 342,383 KiB. It still ran the pre-fix APK (SHA-256
`ed953fad6d8f3d74d1cd45031e1ed3c4235ad5f589e7ce7b879a6e038d1a6ffd`).
A fixed-release phone soak and rapid whole-service recreation during startup
remain distinct from the passing settled English recreation and rapid dictionary-owner
retirement tests. The current ADB device list was empty; no phone app was installed
or restarted in this round.
