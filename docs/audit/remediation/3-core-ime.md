# Core IME — Verification & Remediation

Package note: source lives under `src/main/kotlin/tribixbite/cleverkeys/` (the audit
referenced `tribixbite/keyboard2/`, which does not exist). All file:line evidence below is
against the real path. Verified by fresh reads on 2026-07-16 (`main`, commit `b2a25742a`).

## Routing summary (load-bearing for everything below)

Two suggestion-selection pipelines coexist and are BOTH live, on different triggers:

- **Manual tap** on a suggestion → `SuggestionBar` listener → `CleverKeysService.onSuggestionSelected`
  (line 922) → `SuggestionBridge.onSuggestionSelected` (line 97) → **`SuggestionHandler.onSuggestionSelected`**
  (line 378, `isManualSelection = true`).
- **Swipe auto-insert** → `Keyboard2View.onSwipeEnd` (line 485, UI thread) →
  `CleverKeysService.handleSwipeTyping` (line 957) → **`InputCoordinator.handleSwipeTyping`** (line 1152)
  → async callback `InputCoordinator.handlePredictionResults` (line 1242/397) →
  **`InputCoordinator.onSuggestionSelected`** (line 460/535).
- **Typing / backspace / delete-last-word** → `KeyboardReceiver` (lines 666/670/674) →
  `CleverKeysService` (926/930/934) → `SuggestionBridge` (59/72/81) → **`SuggestionHandler`**.
  The identically-named `InputCoordinator` versions (`handleRegularTyping`, `handleBackspace`,
  `handleDeleteLastWord`, `updatePredictionsForCurrentWord`) have **no callers**.

Net: `SuggestionHandler` owns typing + manual-tap; `InputCoordinator` owns swipe end-to-end
including its own `onSuggestionSelected`. The swipe path therefore never gets SH's possessive
augmentation, password-mode guard, autocorrect-undo, dict_add/exact_add handling, or logged
error path. The two `onSuggestionSelected` implementations diverged since the initial port
(both blocks last meaningfully touched in `69bd051f7`, 2025-11-28) and now differ in deletion
strategy, capitalization source, and error handling.

## Verification Results

| # | Finding | Verdict | Live path? | Evidence |
|---|---------|---------|-----------|----------|
| 1 | Dual `onSuggestionSelected` near-clones, diverged | **CONFIRMED** | Both live (SH=tap/typing, IC=swipe) | IC `onSuggestionSelected` 535–880 (346 ln); SH 378–758 (381 ln). IC "UNIFIED DELETION … ALL apps" 679–687, 750–752; SH Termux backspace branches 521–536 & 618–630. `I_WORDS`/`capitalizeIWord` dup IC 60–80 / SH 61–82. `handleDeleteLastWord` dup IC 980–1107 / SH 1306–1430 (SH adds Ctrl+Backspace fallback 1382–1387,1419–1424; IC lacks it). Contraction injection dup IC 262–339 / SH 1190–1276. Routing: SuggestionBridge 121–126 & 44–49 → SH; Keyboard2View 496 → CleverKeysService 957–967 → IC 1152 → IC 1242 → IC 460. |
| 2 | Unmarked dead code in InputCoordinator | **CONFIRMED** | Dead | `rg` shows IC `handleRegularTyping` (885), `handleBackspace` (966), `updatePredictionsForCurrentWord` (501), `calculateDynamicKeyboardHeight` (1113) have zero external callers — the same-named service entrypoints route to SuggestionBridge→SH (926/930) and NeuralLayoutBridge (948). IC `updatePredictionsForCurrentWord` is only called from IC `handleRegularTyping`/`handleBackspace` (898/970), which are themselves dead. Dead `handleRegularTyping` still carries a live-looking autocorrect impl 915–944 that lacks SH's `preserveCapitalization`, non-prose guard, and undo tracking. |
| 3 | Main-thread busy-wait in `ensureNeuralEngineReady` | **CONFIRMED** | Live | `PredictionCoordinator.ensureNeuralEngineReady` 222–253 spins `Thread.sleep(50)` to 5000 ms (233–239). Called from IC `handleSwipeTyping` 1176, which runs on the UI thread (`Keyboard2View.onSwipeEnd` 485 is a `View` touch callback → `handleSwipeTyping` 496 → CleverKeysService 967, all synchronous). `neuralEngine` is `private var` **not** `@Volatile` (line 44); only `isInitializingNeuralEngine` is `@Volatile` (47). |
| 4 | Silent exception swallow in IC `onSuggestionSelected` | **CONFIRMED** | Live (swipe) | IC 869–871 `catch (e: Exception) { // Silently catch exceptions }` — no log even under `BuildConfig.ENABLE_VERBOSE_LOGGING`. Contrast SH 747–749 `catch { Log.e(TAG, "Error in onSuggestionSelected", e) }`. The `try` wraps delete + commit + state updates (658–868), so a mid-commit throw leaves `contextTracker`/auto-space state inconsistent silently. |
| 5 | Prediction executors never shut down | **CONFIRMED** | Live | IC 109 & SH 167 each `Executors.newSingleThreadExecutor()`. Neither class has `shutdown()`/`cleanup()`; `CleanupHandler.cleanup()` (39–57) references only fold tracker, clipboard, `predictionCoordinator.shutdown()`, debug manager — not IC/SH. No `inputCoordinator.*shutdown`/`suggestionHandler.*shutdown` anywhere. |
| 6 | Orphan `CoroutineScope(Dispatchers.IO)` never cancelled | **CONFIRMED** | Live | `Pointers` 42 `CoroutineScope(Dispatchers.IO).launch{…}` in `init{}` — new `Pointers`/`n` created per `Keyboard2View` (re-created on theme change). `EmojiKeywordIndex.prewarm` 48 same pattern; `loadJob` is stored (29) and `join`ed (148) but **never `cancel()`ed** — no `close()`/`shutdown` in the file. |
| 7 | Pipelines race on SuggestionBar; prompt guard missing in IC | **CONFIRMED** | Live | IC has separate `currentPredictionTask` (110) from SH (168); each posts to the same bar. SH `updatePredictionsForCurrentWord` guards with `!specialPromptActive` before post AND inside the posted runnable (1280–1286). IC's cursor-sync post (342–350) has **no** `specialPromptActive` check (the flag lives only on SH), so an IC cursor-sync can overwrite SH's "Add to dictionary?" / autocorrect-undo prompt. |
| 8 | `isLikelyNoise` always returns `false` | **CONFIRMED** | Live-but-inert | `ImprovedSwipeGestureRecognizer.isLikelyNoise` 384–388 `return false`. Reached via `applyFinalFiltering` 354/370, called from `endSwipe` 274/281 (live recognizer is `EnhancedSwipeGestureRecognizer : ImprovedSwipeGestureRecognizer`, used by `Keyboard2View`). Zigzag filter is a permanent no-op — the guard `prev != next || !isLikelyNoise(...)` is always true, so no key is ever dropped. Low impact: neural path sends `emptyList()` keys (IC 1228) and recomputes from raw path, so the filtered key list feeds only ML/legacy sequence building. |
| 9 | `moveCursorSel` `do{}while(selStart==selEnd)` infinite loop when `d==0` | **PARTIAL** | Not reachable with `d==0` | `KeyEventHandler.moveCursorSel` 805–813: if `d==0`, neither branch changes the `selStart==selEnd` invariant → infinite loop, no timeout/guard. BUT every live caller passes non-zero `d`: `Sliding.onTouchMove` dispatches `sliderKey(slider, d_)` only under `if (d_ != 0)` (Pointers 1566–1570); static slider keys are defined with repeat `1` (KeyValue 727–730); `getSliderRepeat` returns that value (190). So the loop cannot spin today — it is a latent defect, not an active bug. Cheap to harden. |

## Remediation Steps (severity-ordered)

Ordering interleaves risk: do the cheap, low-risk hardening (#3–#9) first to stabilize, then the
large unification (#1/#2) last behind its own test gate.

### R-3 [P1] Remove main-thread busy-wait in `ensureNeuralEngineReady`
- **File:** `PredictionCoordinator.kt:222–253`, field decl `:44`.
- **Change:**
  1. Make the engine reference visible across threads: `@Volatile private var neuralEngine: NeuralSwipeTypingEngine? = null` (line 44). `asyncPredictionHandler` (45) should also be `@Volatile` since it is read on the UI thread and written on the init thread.
  2. Replace the `Thread.sleep(50)` poll loop (229–244) with a proper wait primitive. Preferred: gate init completion on a `java.util.concurrent.CountDownLatch` created when `isInitializingNeuralEngine` is set true in `initializeNeuralEngine` (183) and counted down in the `finally` (211–213); `ensureNeuralEngineReady` does `latch.await(5, TimeUnit.SECONDS)` instead of spinning. This removes the CPU spin but keeps the same 5 s cap.
  3. Better: do not block the UI thread at all. `handleSwipeTyping` already has an async `AsyncPredictionHandler` path; make the "engine not ready yet" case enqueue the swipe (or drop with a toast) rather than block. Minimum viable fix is (1)+(2); (3) is the correct end state.
- **Test:** JVM test `PredictionCoordinatorInitTest` — start `initializeNeuralEngine` on a worker, call `ensureNeuralEngineReady` from another thread, assert it returns within budget and never busy-loops (measure CPU or assert latch used). Instrumented: swipe immediately after keyboard open, assert no ANR / no >200 ms main-thread block (Choreographer skipped-frames log).
- **Risk:** Low–medium. The `@Volatile` add is free. The latch refactor must ensure the latch is always counted down on every early-return in `initializeNeuralEngine` (176–214 has multiple `return`/exception paths) — miss one and `await` waits the full 5 s. Keep the `synchronized` fallback (247–252) as belt-and-suspenders.

### R-4 [P1] Log the swallowed exception in IC `onSuggestionSelected`
- **File:** `InputCoordinator.kt:869–871`.
- **Change:** `catch (e: Exception) { android.util.Log.e(TAG, "Error in onSuggestionSelected", e) }` mirroring SH 747–749. This is superseded by unification (R-1) but should land immediately since it is a one-liner that currently hides swipe-commit corruption. Do NOT expand the catch scope; just log.
- **Test:** none needed (logging only); covered incidentally when R-1 merges behavior.
- **Risk:** Trivial.

### R-7 [P1] Add prompt guard to IC cursor-sync post — RESOLVED (WP9 step 5, 2026-07-20)
- **Status:** LANDED structurally via the preferred option (1). `InputCoordinator.onCursorMoved`
  now routes its prediction+post phase to `SuggestionHandler.handleCursorSyncPrediction` →
  `updatePredictionsForCurrentWord` (behind `config.unified_swipe_pipeline`, default TRUE), which
  already guards on `specialPromptActive`. Cursor-sync and typing share ONE guarded pipeline, so a
  cursor-sync pass can no longer clobber an SH prompt — no shared mutable state added. Deterministic
  `oracle_cursorSync_doesNotClobberAutocorrectUndoPrompt` pins it. Legacy IC path (flag off) retains
  the old unguarded `triggerPredictionsForPrefix`. See
  `docs/audit/remediation-plans/wp9-pipeline-unification-oracle.md` §"Step 5 — LANDED".
- **File:** `InputCoordinator.kt:342–350` (post block) and `triggerPredictionsForPrefix` 214–357.
- **Change:** IC must not clobber a special prompt owned by SH. The `specialPromptActive` flag lives on SH (SuggestionHandler 174–175). Two options:
  1. **Preferred (post-unification):** once one pipeline owns suggestions (R-1), this race disappears; sequence R-7 to fold into R-1.
  2. **Interim standalone fix:** promote `specialPromptActive` to a shared holder both pipelines read (e.g. a field on `PredictionContextTracker`, which both already hold). SH sets/clears it at 783/905/973/1064/1108; IC checks it before its `mainHandler.post` (343) and inside the runnable, matching SH 1280–1286.
- **Test:** JVM/instrumented `PromptRaceTest` — type an unknown word to raise "Add to dictionary?", then fire an IC cursor-sync (`onCursorMoved`) and assert the prompt survives.
- **Risk:** Medium if done standalone (adds shared mutable state across two classes). Prefer folding into R-1.

### R-8 [P3] Implement or delete `isLikelyNoise`
- **File:** `ImprovedSwipeGestureRecognizer.kt:384–388`, caller 354–378.
- **Change:** Either (a) implement using key geometry — `curr` is noise iff its center lies on the segment between `prev` and `next` centers within a tolerance (the recognizer already has key rects via `findKeyAtPoint` 341 / `setKeyboard` 65); or (b) if the neural path never consumes the filtered keys (it sends `emptyList()`, IC 1228), delete `applyFinalFiltering`'s zigzag branch and the method, and add a `// TODO` only if ML sequence quality is later shown to need it. Given low impact, (b) with a documented removal is acceptable; do not leave a silent always-false stub.
- **Test:** `SwipeFilterTest` (JVM) with a synthetic a→b→a zigzag over a known layout asserting the middle key is dropped (if implementing) — else a comment-only change needs none.
- **Risk:** Low.

### R-9 [P3] Guard `moveCursorSel` loop
- **File:** `KeyEventHandler.kt:805–813`.
- **Change:** Add `if (d == 0) return` at the top of `moveCursorSel` (789), or convert the `do/while` to a bounded form: `if (d != 0) { … ; if (selStart == selEnd) { if (selLeft) selStart += d else selEnd += d } }`. Defensive only — not reachable today (see verdict) but the cost is one line and it removes a latent hang.
- **Test:** `KeyEventHandlerSliderTest` (JVM, mock InputConnection) calling `handleSlider(Selection_cursor_left, 0, true)` asserting it returns without hanging.
- **Risk:** Trivial.

### R-5 [P2] Shut down prediction executors
- **File:** `InputCoordinator.kt:109`, `SuggestionHandler.kt:167`, `CleanupHandler.kt:39–57`, and the create/onDestroy wiring in `CleverKeysService`.
- **Change:** Add `fun shutdown() { currentPredictionTask?.cancel(true); predictionExecutor.shutdownNow() }` to both IC and SH. Wire them into `CleanupHandler` — pass IC and SH into `CleanupHandler.create` and call their `shutdown()` in `cleanup()` (after `predictionCoordinator?.shutdown()`). Post-unification only one executor survives, so this shrinks to one call. Note IME `onDestroy` fires rarely (per MEMORY), so this is hygiene, not a hot leak — but the executor also holds the singleton thread alive across the process.
- **Test:** JVM — construct IC/SH, submit a task, call `shutdown()`, assert `predictionExecutor.isShutdown`.
- **Risk:** Low. Ensure `shutdownNow` is not called while a submit is racing on `onDestroy`; the `?.cancel(true)` first is sufficient.

### R-6 [P2] Cancel orphan coroutine scopes
- **File:** `Pointers.kt:42–45`, `EmojiKeywordIndex.kt:48` (+ field 29).
- **Change:**
  - `Pointers`: replace the ad-hoc `CoroutineScope(Dispatchers.IO).launch{…}` in `init` with a member `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` and a `fun close() { scope.cancel() }`; have `Keyboard2View` call `_pointers.close()` when it replaces `_pointers` on theme change / detach. The `loadMappings()` launch is one-shot and short, so the leak is a scope object, not a runaway job — still worth fixing to stop accumulating cancelled-nothing scopes on every theme swap.
  - `EmojiKeywordIndex`: add `fun cancel() { loadJob?.cancel() }` and call it from the owner's teardown; `loadJob` is already a field (29).
- **Test:** JVM — instantiate, trigger the scope, call `close()/cancel()`, assert job cancelled / scope inactive.
- **Risk:** Low. Confirm no code path relies on the load completing after `close()` (EmojiKeywordIndex 148 `join` — callers must handle cancellation).

### R-2 [P1] Delete IC dead code (do as part of / just before R-1)
- **File:** `InputCoordinator.kt` — `handleRegularTyping` 885–961, `handleBackspace` 966–975, `updatePredictionsForCurrentWord` 501–533, `calculateDynamicKeyboardHeight` 1113–1145.
- **Change:** Delete all four (verified zero external callers; `updatePredictionsForCurrentWord` only called by the other two dead methods). This also removes the divergent autocorrect impl at 915–944 that could mislead future edits. Do this BEFORE R-1 so the unification does not accidentally preserve dead behavior. Keep the live IC methods: `handleSwipeTyping`, `handlePredictionResults`, `onSuggestionSelected` (until R-1 moves them), `onCursorMoved`, `triggerPredictionsForPrefix`, swipe/ML helpers, `applyShiftTransformation`, `capitalizeIWord`, `updateContext`, `resetSwipeData`, `getCurrentSwipeData`.
- **Test:** compile (`./gradlew compileDebugKotlin`) + full `runPureTests`. Grep to re-confirm zero callers after deletion.
- **Risk:** Low — pure deletion of unreachable code. The only trap is `updatePredictionsForCurrentWord`'s name collision with the live SH method; delete the IC one only.

## Pipeline Unification Plan (finding #1 — highest leverage, highest risk)

### Which pipeline survives
**`SuggestionHandler` is the survivor.** Rationale:
- SH is the richer, more-maintained implementation: possessive augmentation (313, `augmentPredictionsWithPossessives` 1441), password mode (177,215,290), autocorrect-undo (403–411,853), `dict_add:`/`exact_add:` handling (389,397), non-prose guard, `preserveCapitalization` (95), the `#78`/`#151` editor-scan + URL-field fallbacks (595–612,646–651), and the `specialPromptActive` prompt guard (174,1280–1286). Re-implementing these into IC would be strictly larger and riskier.
- IC's genuinely-unique swipe assets are narrow: shift/caps-lock capture at swipe start (`wasShiftActiveAtSwipeStart`/`wasShiftLockedAtSwipeStart` 104–106, `applyShiftTransformation` 377) and ML-data capture on selection (617–651). These must be **ported into SH**, not lost.

### What the merged code must preserve (from BOTH)
The unified `SuggestionHandler.onSuggestionSelected` + `handlePredictionResults` must keep:

From SH (already present — do not regress):
- `dict_add:` / `exact_add:` / autocorrect-undo dispatch (389–411).
- `isKnownContraction` + `isRawPrediction` + `isManualSelection` autocorrect skip (428–461) incl. `preserveCapitalization`.
- Password-mode short-circuit in `handlePredictionResults` (290) and `syncSuppressedField` URL/email handling (491, 595–612, 646–651).
- `augmentPredictionsWithPossessives` (313).
- Logged catch (747–749).
- `specialPromptActive` guard (1280–1286).

Ported IN from IC (currently only on swipe path):
- **Shift/caps-lock-at-swipe-start** transformation. Move `wasShiftActiveAtSwipeStart`/`wasShiftLockedAtSwipeStart`/`applyShiftTransformation` to SH (or better, into the swipe-request struct passed via `AsyncPredictionHandler`) and apply in SH `handlePredictionResults` where IC applies it (419). SH currently derives capitalization only from the typed partial (682–683) — insufficient for pure swipe where there is no typed partial.
- **ML-data collection on selection** (IC 617–651). Note: this is *already* partly centralized — `SuggestionBridge.onSuggestionSelected` calls `MLDataCollector.collectAndStoreSwipeData` (105–113) for the tap path. The swipe auto-insert path (IC `handlePredictionResults` → IC `onSuggestionSelected`) uses IC's inline block instead. Unify by routing swipe auto-insert through `MLDataCollector` too, so ML capture lives in exactly one place.
- **Contraction injection** — SH already has the equivalent in `updatePredictionsForCurrentWord` (1190–1276); IC's `triggerPredictionsForPrefix` (262–339) is the cursor-sync variant. Fold IC's cursor-sync prediction into SH so there is one contraction-injection implementation.

Termux handling: SH's per-branch Termux key-event deletion (521–536, 618–630, `handleDeleteLastWord` Ctrl+W 1319–1330, Ctrl+Backspace fallback) is the correct behavior to keep. IC's "UNIFIED DELETION for ALL apps" (679–687) is the *divergent* one — decide deliberately: if Termux truly now supports `deleteSurroundingText`, remove the SH Termux branches too (a separate, tested decision); otherwise keep SH's Termux path and discard IC's. **Do not silently inherit IC's "ALL apps" behavior** — it would regress Termux deletion if that assumption is wrong.

### Incremental, test-gated migration order
Each step compiles, passes `runPureTests` + targeted instrumented tests, and is independently committable. Do NOT do it in one commit.

1. **Pre-work:** land R-2 (delete IC dead code) and R-4 (log the swallow). Gate: compile + full JVM suite. Now IC contains only swipe + cursor-sync surface.
2. **Characterize current behavior with tests BEFORE touching logic.** Add instrumented + JVM tests capturing today's outputs for: swipe auto-insert (with shift, caps-lock, Termux, password-with-`swipe_on_password_fields`), tap replace-after-swipe, mid-word tap, URL-bar tap (#151), contraction tap, possessive presence. These are the regression oracle. Gate: all green against current code.
3. **Move shift/caps-lock capture into SH.** Add the three IC members + `applyShiftTransformation` to SH; have the swipe request carry shift state (thread it through `handleSwipeTyping` → `AsyncPredictionHandler` → SH `handlePredictionResults`). Do not yet reroute selection. Gate: step-2 swipe-capitalization tests still green (behavior identical, just relocated).
4. **Reroute swipe auto-insert to SH.** Change IC `handlePredictionResults` (or the `AsyncPredictionHandler` callback wiring in IC `handleSwipeTyping` 1238–1249) to call `SuggestionHandler.handlePredictionResults` / `SuggestionHandler.onSuggestionSelected(isManualSelection=false)` instead of IC's own. Add possessive-augment + password guard coverage to swipe. Route swipe ML capture through `MLDataCollector`. Gate: step-2 oracle green; specifically assert possessives now appear on swipe and password swipe respects `swipe_on_password_fields`.
5. **Reroute cursor-sync prediction to SH.** Fold IC `onCursorMoved`/`triggerPredictionsForPrefix` (145–357) into SH (SH gains a cursor-sync entry that reuses its `updatePredictionsForCurrentWord` contraction pipeline and the `specialPromptActive` guard). This resolves R-7 structurally. Gate: prompt-race test (R-7) green; cursor-sync predictions unchanged.
6. **Delete IC `onSuggestionSelected` and the now-dead swipe-selection body.** IC shrinks to a thin swipe-gesture/ML front-end that delegates to SH. Consolidate to a single `predictionExecutor` (SH's) and apply R-5 shutdown to it. Gate: full JVM + instrumented suite; grep confirms no remaining IC selection callers.
   — **LANDED 2026-07-21.** Swipe commit now runs `SH.onSuggestionSelected(isManualSelection=false)`;
   IC's engine, `triggerPredictionsForPrefix`, `handleDeleteLastWord` dup, executor, and the
   `unified_swipe_pipeline` flag are deleted; D5 (ML via MLDataCollector) landed; the dead
   `CKS→bridge→SH.handlePredictionResults` legacy chain deleted. Deliberate deltas (#82-for-swipe
   preserved from production, mid-sentence/no-URL-leading-space/case-preserving-autocorrect
   adopted from SH) + full details in
   `docs/audit/remediation-plans/wp9-pipeline-unification-oracle.md` §"Step 6 — LANDED".
   Step 6 deliberately did NOT touch the Termux-deletion decision (step 7 below): SH's Termux
   branches are live for taps as before; the swipe auto-insert path cannot reach the replace
   branch. Per the Addendum below, the geo-engine steps 7-9 slot in next — the
   `SH.handleSwipePredictionResults` seam is now the only swipe-results path.
7. **Deliberate Termux-deletion decision** (separate commit): either keep SH Termux branches or, if validated, unify to InputConnection for all apps — with a dedicated Termux instrumented test either way. Never bundle this into the mechanical rerouting steps.

### Risk assessment
- **Highest-risk area:** deletion/spacing logic — the `deleteSurroundingText` counts, leading/trailing auto-space, `expectingSelectionUpdate` flag, and Termux key-event branches are subtle and app-specific (browser URL bars, Termux, password fields, mid-sentence replacement). Regressions here corrupt user text silently. The step-2 oracle tests are mandatory, not optional.
- **State-coupling risk:** both pipelines mutate the shared `PredictionContextTracker` (`lastAutoInsertedWord`, `lastCommitSource`, `expectingSelectionUpdate`, auto-space pending). During the transition (steps 4–5) two code paths write the same tracker fields; keep the migration window short and land steps 4→6 close together.
- **Threading risk:** consolidating to one executor (step 6) must preserve task cancellation semantics (`currentPredictionTask?.cancel(true)`) so cursor-sync and swipe don't post stale suggestions.
- **Mitigation:** feature-flag the reroute (steps 4–5) behind a debug pref so it can be toggled in QA before removing IC's implementation (step 6). Do not delete IC's `onSuggestionSelected` until the reroute has soaked.

## Refutations / Corrections

- **Path correction:** the audit's `tribixbite/keyboard2/…` paths are wrong; the package is `tribixbite/cleverkeys/`. Every line number the audit gave nonetheless matched the real files, so the audit was reading the right content under a mislabeled path.
- **Finding #9 downgraded to PARTIAL:** the `do{}while` is a real latent defect but is **not reachable** — `Sliding.onTouchMove` only dispatches slider events when `d_ != 0` (Pointers.kt:1566–1570) and static slider keys carry repeat `1` (KeyValue.kt:727–730). No live caller can pass `d==0`. Fix is still cheap and recommended, but it is not an active hang.
- **Finding #8 impact clarification:** `isLikelyNoise` is confirmed a permanent no-op, but the neural prediction path sends `emptyList()` keys (InputCoordinator.kt:1228) and recomputes from the raw path, so the always-false filter degrades only ML-data key sequences / legacy sequence building, not neural accuracy. CONFIRMED but lower-severity than a prediction-quality bug.
- **Finding #3 nuance:** `isInitializingNeuralEngine` IS already `@Volatile` (PredictionCoordinator.kt:47); only `neuralEngine` lacks it (44). The audit said "neuralEngine not @Volatile" — correct — but the poll-loop's *visibility* actually turns on `neuralEngine`, so the missing `@Volatile` on line 44 is the load-bearing bug, not the (already-volatile) init flag.
- **Finding #1 scope nuance:** the audit implies IC and SH are redundant clones. More precisely, they are *split by trigger* (SH=tap/typing, IC=swipe) and have genuinely diverged, so this is not dead duplication — both run in production on different inputs, which is why the divergence (possessives/password/prompt-guard present on tap but absent on swipe) is a real user-visible inconsistency, not merely a maintenance smell.

## Effort estimate to reach A grade

| Bucket | Items | Effort |
|--------|-------|--------|
| Quick hardening | R-4 (log), R-9 (loop guard), R-8 (noise stub decision), `@Volatile` part of R-3 | ~0.5 day |
| Lifecycle hygiene | R-5 (executor shutdown), R-6 (scope cancel), latch part of R-3 | ~1 day |
| Dead-code removal | R-2 | ~0.5 day |
| Prompt-race | R-7 (if folded into unification, ~0; standalone ~0.5 day) | 0–0.5 day |
| **Pipeline unification** | R-1 steps 1–7 incl. oracle test suite | **~4–6 days** |
| **Total to A** | | **~6.5–8.5 days** (1 engineer) |

The unification (R-1) dominates and carries the real risk; everything else is a day or two of
low-risk cleanup. Reaching an A grade specifically requires R-1 (eliminates the diverged
dual pipeline and the swipe/tap behavior inconsistency) plus R-3 (no main-thread block) —
the P2/P3 items are needed for polish but are not the grade-limiting factors.

## Addendum (2026-07-21): WP9 × geometric engine — proposed R-1 step 7+ ("geo wiring")

*Proposal from the geo-engine track for the unification track to adopt/amend. Context:
the standalone geometric engine (`swipe.geometric`, spec `docs/specs/geometric-swipe-engine.md`)
is implemented, audited, and real-corpus-validated (en/QWERTY + dvorak/azerty/qwertz/german/
spanish + 8.5k neural-testset traces). R-1 steps 4–5 landed `SH.handleSwipePredictionResults(
PredictionResult)` as the single flag-gated swipe-results entry — which is EXACTLY the seam
the geo engine was built to feed (it emits the same `PredictionResult`). These steps slot in
AFTER step 6 (IC deletion + soak), so the router lands on the unified path only.*

### Step 7 — SwipeEngineRouter (layout-routed v1)
- Insert at the existing gate site: `Config.isSwipeTypingSupportedForLayout` currently
  returns false → swipe silently disabled (`InputCoordinator.kt` swipe entry; the gate's own
  KDoc anticipates this: "#9: When algorithmic swipe is implemented, this can expand").
  v1 routing = **layout-based only**: QWERTY-Latin → neural (unchanged), everything else →
  geometric. Do NOT do length-based routing in v1 — the measured head-to-head (spec As-Built:
  neural wins ≤3-letter top-1 by +21.6, geo wins 4+/depth) supports a later QWERTY-en
  rank-merge experiment, but that is a phase-2 enhancement with its own oracle round.
- **Score comparability is a hard constraint**: geo scores are engine-relative softmax×1000
  (KDoc warning on `SwipeDecodingEngine`; spec OQ-5). The router must never numerically
  compare scores across engines. v1 = single engine owns each swipe end-to-end.

### Step 8 — GeometricEngineAdapter (the spec's named-not-built component)
Checklist (each item is a spec-documented adapter duty):
1. `KeyboardData` → `LayoutGeometry` via `a11y/KeyboardGeometry.computeKeyRects` (proven rect
   math); memoize per immutable KeyboardData instance; fingerprint churn on orientation is
   by-design.
2. `PointF` trace → `TracePoint` (key-area-local px + `keyAreaWidthPx/HeightPx`).
3. `DictionaryManager` words → `GeometricDictionary`: **merge custom words, filter disabled
   words, bump `version` on every mutation** (the existing ContentObserver is the trigger).
   This closes the two features the standalone engine deliberately does not see.
4. `warmUp(layout, dict)` on layout/language switch, background — avoid the 150–400 ms
   synchronous fallback on first swipe. Memory ceiling: indexCacheCapacity=3 ⇒ ≤7.5 MB.
5. Pref `geometric_swipe_engine` (name reserved in the spec) — MUST be classified in
   `SETTINGS_DEFAULTS` or `SettingsDefaultsDriftTest` fails (deliberate tripwire).

### Step 9 — oracle additions (extend the R-1 characterization suite)
- NEW-behavior pin: non-QWERTY layout (e.g. cyrl_jcuken_ru) now yields swipe suggestions
  (previously none) when flag on; yields none when flag off.
- Parity pins on the geo path (should be free since geo rides the same SH entry, pin anyway):
  password-field guard, possessive augmentation, case/shift transform, contraction alias
  display mapping (geo emits `dont` — assert the bar shows the mapped form per SH rules).
- Perf gate (instrumented): p95 decode+adapter overhead on-device for a JCUKEN swipe
  (engine core measured 1.8 ms warm on 98k JVM-side; adapter conversion must not dominate).

### Sequencing + effort
Step 7–9 ≈ 2–3 days on top of R-1's 4–6, strictly after step 6 soak. Router flag can reuse
`unified_swipe_pipeline` gating semantics but should be a separate pref (`geometric_swipe_engine`)
so geo can be disabled independently of the unification.

### Steps 7–9 — LANDED (2026-07-21)

**Gates (all green):** compileDebug + compileDebugAndroidTest clean; `runPureTests` 1663/1663;
targeted ew-cli GeometricSwipeOracleTest 6/6 (all executed — first run silently SKIPPED all 6
on a raw-vs-xml resource lookup; fixed + hardened assume→assert so a missing layout fails
loudly) + PipelineCharacterizationTest 26/26; **FULL ew-cli suite 1453/1453, 0 skipped**
(Pixel7 API 34, orchestrator). Commit `38df84ce`.

- **Step 7** (v1.1 same-day): `swipe/SwipeEngineRouter.kt` (pure object, KeyboardData + string
  overloads) at the IC `handleSwipeTyping` gate, MODE-based via the user-facing
  `swipe_engine_mode` String pref (default `"neural"`; supersedes the hours-lived
  `geometric_swipe_engine` Boolean, never released, dropped without deprecation):
  NEURAL = QWERTY-only swipe (pre-geo behavior); HYBRID = neural on QWERTY + geometric
  elsewhere; GEOMETRIC = SHARK2 on ALL layouts (incl. QWERTY). `Mode.fromPref` falls back to
  NEURAL on junk values. Settings UI: the "🧠 Neural Network Prediction" section is renamed
  "👆 Swipe Typing" (all 22 locales); a "Prediction Engine" dropdown (Hybrid/Neural/Geometric,
  gated on Enable Swipe Typing) selects the mode; the Beam Width / Maximum Word Length /
  Confidence Threshold sliders moved out of the main section (they remain in the Full Neural
  Settings screen, which already had them); the #9 non-QWERTY warning card shows only in
  Neural mode and now suggests switching the engine. No cross-engine score comparison; one
  engine owns each swipe end-to-end.
- **Step 8**: `swipe/GeometricEngineAdapter.kt` — all five spec duties: (1) KeyboardData →
  LayoutGeometry via `KeyboardGeometry.computeKeyRects` (view-pixel frame == trace frame),
  memoized per KeyboardData instance; (2) PointF → TracePoint snapshotting; (3) dictionary from
  the SAME production sources (langpack `files/langpacks/{code}/dictionary.bin`, else
  `dictionaries/{code}_enhanced.bin` asset, both V2 CKDT) with custom words prepended +
  disabled filtered, `version` = content hash over (source, custom JSON, disabled set) —
  recomputed per ensure, so mutations invalidate the engine cache without ContentObserver
  wiring; (4) `warmUpAsync` from `CleverKeysService.onStartInputView` →
  `IC.prewarmGeometricEngine()` (posted past layout); (5) pref classified (drift test green).
  Decode runs on the adapter's own `PredictionTaskRunner` thread (last-swipe-wins), results
  post to main → `IC.handlePredictionResults` → the step-6 `SH.handleSwipePredictionResults`
  seam, inheriting password guard/possessives/shift transform/THE commit engine for free.
- **Step 8 addendum (2026-07-23, field report)**: contraction display mapping added to the
  adapter — geo decodes the dictionary's apostrophe-free ALIAS forms ("theyd", "dont"; the
  canonical "they'd" is untypeable, apostrophe is not a key) and now maps them at emission
  via `ContractionManager.getNonPairedMapping` (per-language, lazily loaded on the decode
  thread, post-map dedupe) — the exact mirror of the neural vocab's
  `displayWord = nonPairedContractions[word]` (OptimizedVocabulary:448). Non-paired only:
  paired bases ("its") are real words and stay as decoded. This was the one addendum step-9
  parity pin ("geo emits `dont` — assert the bar shows the mapped form") missing from the
  first oracle round; `oracle_geo_contractionAlias_displaysApostropheForm` now pins it.
- **Step 8 addendum 2 (2026-07-23, multilingual contraction/possessive audit)**: measured
  audit of all 7 bundled CKDT bins: NO dictionary in ANY language stores apostrophe words —
  aliases are apostrophe-free by design, display forms exist only as runtime mappings, and
  possessives are always generated dynamically (never stored; WordPredictor:1976). The
  first-pass unconditional alias replacement was a SEVERE latent multilingual bug: de "im"
  (ordinal 16 — 16th most common German word), fr la/les/dans/ma/dont (≤ 285), it del/loro/ai
  would all have been rewritten to English contractions. Fixed by `swipe/ContractionOverlay`
  (pure, 12 JVM tests): paired-first (the binary contraction store pollutes the non-paired
  map — OptimizedVocabulary:463 carries the same guard), then a real-word ordinal guard at
  1200 (the geo equivalent of neural's `frequency > 0.65`; measured gap: real ≤ 285 vs junk
  ≥ 1506) choosing keep+variant vs replace, then case-insensitive dedupe. Paired bases now
  inject their variants ("its" → "its" + "it's" — neural parity). Variants are APPENDED after
  all engine candidates (on-device the first splice-after-base placement pushed "world" out of
  the top-3 behind "would"'s variants — replacements keep the base's slot, injections go to
  the end, matching SH's possessive placement). Adapter loads contractions
  per production semantics (base + language + en extras) and memoizes a word→ordinal map with
  the dictionary. ALSO: possessive augmentation at the SH swipe seam is now gated to
  English — `generatePossessive` is English "'s" morphology and would fabricate "дом's" /
  "maison's" on the geo path's non-English languages.
  Known minor (accepted): no geo prewarm on mid-session language toggle (decode falls back
  synchronously once, 150-400 ms); dictionary memo is single-slot (language toggling re-reads
  the bin per switch, background thread).
- **Step 9**: `SwipeEngineRouterTest` (9 JVM routing pins incl. the Greek-QWERTY trap) +
  `GeometricSwipeOracleTest` (instrumented, Dvorak+bundled-en so no langpack import needed):
  real-engine decode pin ("world" in top-3), seam commit + NEURAL_SWIPE tracking, password
  suppression on geo results, shift-capitalized commit, router re-pin on real KeyboardData,
  and a p95<150 ms warm decode gate (engine core is ~ms-scale; gate absorbs emulator noise).

### Review findings (2026-08-11): steps 6-9 implementation audit

Independent review of `949bdcf9..d0b242bd` against the Addendum above; every status
re-verified at HEAD `62c9419f` (2026-08-11). Intervening commits are CTC-eval/context-LM
work and touch the geo wiring only where noted (m-1).

| # | Finding | Sev | Status @ HEAD |
|---|---------|-----|---------------|
| M-1 | Step-6 soak was skipped — steps 7-9 landed 2h41m after step 6, so the unified pipeline never soloed in the field | M | CLOSED-BY-EXPOSURE — not retroactively fixable; the unified+geo path now has ~3 weeks of field exposure (2026-07-21 → 08-11) with no pipeline regressions reported. Lesson: a declared soak gate must be *scheduled* (dated follow-up commit), not just intended. |
| M-2 | Prewarm/decode race: `warmUpAsync` and `decodeAsync` share ONE `PredictionTaskRunner`, both via `cancelAndSubmit` (GeometricEngineAdapter.kt:73,:204,:246) — the `onStartInputView` prewarm (CleverKeysService.kt:687 → `IC.prewarmGeometricEngine`) can cancel an in-flight swipe decode → silently lost swipe on same-field restart. The decode callback also replays captured `ic`/`editorInfo` with no `isReplayInputStillCurrent`-style stale-field guard (contrast InputCoordinator.kt:461 on the neural replay path). | M | **FIXED** (`fb86a641`) — see "M-2 fix" below |
| m-1 | Geo commits provenance-tagged `PredictionSource.NEURAL_SWIPE` (SuggestionHandler.kt:572); the oracle PINS the mislabel (GeometricSwipeOracleTest.kt:277) with no deliberately-wrong marker | m | **PARTIAL** (`fb86a641`) — the pin now carries an explicit deliberately-wrong marker naming this row; the label itself is unchanged (provenance refactor out of scope). Previously mitigated — post-review `295edc43` added `SuggestionOrigin.GEOMETRIC` for *bar* provenance in pure geometric mode (SuggestionProvenance.kt:57), but hybrid-mode geo results still show NEURAL_BEAM and commit-source tracking is unchanged; todo filing survives only as the hybrid-origin item (memory/todo.md:85) |
| m-2 | Settings-import can set geo knobs outside UI ranges: adapter clamps 1..32 / 0..1 / 0..2 (GeometricEngineAdapter.kt:88-90) vs UI 3-15 / 0-0.4 / 0-0.8 (GeometricSettingsActivity.kt:98,:112,:126) — outside validated-floor territory and the slider can't display it | m | **FIXED** (`fb86a641`) — `GeoKnobRanges` (Config.kt) is now the single source of truth: the adapter clamps against it, the sliders derive their ranges from it, and `loadSavedParameters` clamps an imported value so the slider can always display what the engine runs |
| m-3 | "background re-warm on knob change" claim (spec §292, GeometricSettingsActivity.kt:46, commit `11e7f644`) oversold: `updateParameters` (:224-239) only writes Config+prefs; the adapter re-reads knobs per decode (:88-90) so the rebuild is lazy — first post-change swipe pays the 150-400 ms build (background thread, no jank) | m | **FIXED** (`fb86a641`) — implemented rather than downgraded: `updateParameters` now posts a 500 ms-debounced `CleverKeysService.requestGeometricRewarm()` → `IC.prewarmGeometricEngine()` → `warmUpAsync` in the M-2 background slot (no-op when the IME is not running / not routing to geo / not laid out). Spec §292 + the activity header restated to match |
| m-4 | Contraction load-order comment (GeometricEngineAdapter.kt:146-149) misleads: base `loadMappings()` (English contractions.bin) loads FIRST, and earlier-wins semantics (ContractionManager.kt:161-163) mean it *shadows* same-key language mappings — the comment claims the active language takes precedence | m | **FIXED** (`fb86a641`) — comment now states earlier-wins/English-shadows explicitly (mirroring production deliberately) and names the `ContractionOverlay` ordinal guard as the mitigation |
| m-5 | Three oracle parity tests soft-skip via `assumeTrue` on non-empty geo predictions (GeometricSwipeOracleTest.kt:265,:292,:320) — a decode-to-empty regression silently skips them | m | **FIXED** (`fb86a641`) — all three now hard-assert via a shared `assertGeoDecodeNonEmpty` helper (with the rationale in its KDoc); the `assumeTrue` import is gone from the class |
| n-1 | Possessive en-gate (SuggestionHandler.kt:485-495, `b2d7b908`) changed the NEURAL path too — fr/es-on-QWERTY users lose possessive augmentation. Correct fix, but no test pins either side of the gate | n | **FIXED** (`fb86a641`) — gate extracted to the pure `SuggestionHandler.shouldAugmentPossessives(activeLanguage)` (KDoc states the neural-path effect is deliberate) and pinned BOTH sides in `PipelineOracleJvmTest`: en + null augment; fr/es/de/it/ru/el/pt/nl do not |
| n-2 | Non-QWERTY geo swipes flow into `SwipeMLData` via the shared `beginSwipeCapture` (InputCoordinator.kt:487-521; called from both paths :557,:621) | n | **FIXED** (`fb86a641`) — see "n-2 fix" below. Was CONFIRMED UNTAGGED: `SwipeMLData` carries only trace/word/screen-dims/`collection_source` (ml/SwipeMLData.kt:20-30, `toJSON` :131-143) and `SwipeMLDataStore` persists only that JSON (:96-101) — NO layout or engine field anywhere, so non-QWERTY geo traces are indistinguishable from QWERTY neural traces in ML exports and would pollute the QWERTY-trained training corpus |

**Positives**: content-hash dictionary versioning (adapter :49,:432-435) is *better* than the
spec's ContentObserver proposal (mutations invalidate without wiring); the mode enum is a
strict superset of the spec's reserved boolean; the engine core is untouched, so the measured
accuracy floors remain valid; no cross-engine score comparison (comparability respected); and
strong self-auditing — `b2d7b908`/`d0b242bd` each found and fixed their own latent
multilingual bugs before field reports. Overall implementation grade: **A-**.

**Recommended actions** (by severity):
1. **M-2**: separate runner slot for warmUp (or submit-only-if-idle prewarm) + an
   `isReplayInputStillCurrent` guard in the geo decode callback; pin both with an oracle test.
2. **n-2**: add `layout`/`engine` fields to `SwipeMLData` + export JSON (legacy rows default
   `qwerty`/`neural`) before non-QWERTY trace collection accumulates.
3. **m-2**: tighten adapter clamps to the UI ranges (3..15 / 0..0.4 / 0..0.8), or clamp at
   settings-import validation.
4. **m-1/m-3/m-4** comment+doc fixes: mark the oracle NEURAL_SWIPE pin deliberately-wrong,
   correct the re-warm claim (spec §292 + activity header), fix the contraction load-order
   comment; optionally add a geo commit source.
5. **m-5/n-1**: add an empty-decode canary (or harden one assumeTrue) + pin the possessive
   en-gate on both sides.

### Remediation LANDED (2026-08-11, commit `fb86a641`)

All eight rows above are closed (m-1 PARTIAL by design — the label itself is out of scope).
Gates: `compileDebugKotlin` + `compileDebugAndroidTestKotlin` clean; `runPureTests`
**1876/1876**; `runMockTests` **299/299**. The instrumented tests were compiled but not
executed in the `fb86a641` round; they were run and went green on 2026-08-11 — **48/48** on
Pixel7 API 34 — see "instrumented follow-up" below.

**M-2 fix — two-slot task runner + stale-field guard.** `PredictionTaskRunner`
(`InputCoordinator.kt`) now tracks a FOREGROUND and a BACKGROUND in-flight task on the SAME
single thread (so the adapter keeps single-thread confinement of its engine/memo/contraction
state — no new locks, no new races):

- `cancelAndSubmit` (decode) cancels the previous decode **and** any in-flight prewarm — the
  newest user gesture still wins the thread, preserving today's last-swipe-wins semantics.
- `submitBackground` (prewarm) supersedes only a previous prewarm and **never** cancels a
  decode. `GeometricEngineAdapter.warmUpAsync` moved onto it.
- Every submitted task starts with a cleared interrupt status: a cancelled predecessor that
  never consumes its interrupt used to leak the flag into the next task, whose
  `!isInterrupted` result guard then dropped a perfectly good decode.
- Delivery is now gated on a monotonic **decode generation** (`AtomicLong`, checked on the
  worker AND again on the main thread) instead of the worker's interrupt flag, so a superseded
  decode can never post a stale suggestion list even if its interrupt is missed.
- (b) `InputCoordinator.performGeometricSwipeTyping`'s decode callback now applies
  `isReplayInputStillCurrent(ic, editorInfo)` — the same guard the neural cold-start replay
  uses — before handing results to the commit pipeline, so a late decode cannot commit into a
  field the user has since left. Drops are logged under `ENABLE_VERBOSE_LOGGING`.
- The concurrency contract is documented in KDoc on both `PredictionTaskRunner` and
  `GeometricEngineAdapter`.
- Tests: `PredictionTaskRunnerTest` (JVM, +4 — background-does-not-cancel-foreground,
  background-supersedes-background, foreground-cancels-background, no interrupt leak);
  `CoreImeHygieneDriftTest` (JVM, +2 drift pins — warmUp stays in the background slot, the geo
  callback keeps the stale-field guard); `GeometricSwipeOracleTest` (instrumented, +2 —
  prewarm-during-cold-decode still delivers, newer decode still supersedes older).

**n-2 fix — ML-trace provenance schema.** `SwipeMLData` gains two immutable fields,
`layoutName` and `engine`, serialized into `metadata` as `layout_name` / `engine`:

- Constructor params are trailing + defaulted (`UNKNOWN`) and `@JvmOverloads`, so no call site
  breaks; blank/absent values normalize to `"unknown"` (never `""`).
- Engine tags are the constants `SwipeMLData.ENGINE_NEURAL` / `ENGINE_GEOMETRIC`.
- Populated at the shared `InputCoordinator.beginSwipeCapture` seam (layout from the live
  `KeyboardData.name`), which now takes the engine from its two callers — geometric and
  neural. `MLDataCollector` CARRIES the provenance over when it re-creates the object at
  selection time (re-deriving it there would lose the geometry the trace was drawn on).
  `SwipeCalibrationActivity` tags its own fixed grid as `calibration_qwerty` + neural.
- Backwards compatible: `SwipeMLDataStore` persists the whole `toJSON()` blob, so no DB
  migration is needed and old rows simply lack the keys — the JSON constructor reads them as
  `unknown`. Both export paths (`exportToJSON` / `exportToNDJSON`, streamed straight from
  `json_data`, surfaced by `SettingsSwipeDataHandlers`) therefore carry the new fields with no
  change of their own.
- Tests: `ml.SwipeMLDataProvenanceTest` (JVM, 5 — serialization, round-trip, legacy row →
  `unknown`, blank normalization, tag stability; registered in `pureTestClasses`);
  `SwipeMLDataStoreTest` (instrumented, +1 legacy-row test, +assertions on the two existing
  JSON tests).

**Instrumented follow-up — RUN AND GREEN (2026-08-11).** The owed ew-cli gate executed on
Pixel7 API 34 (`--use-orchestrator`, ew-cli 1.3.4), run
`78ff4561-8285-4c7d-b976-de7caf86cb86`:

| Class | Tests | Result |
|---|---|---|
| `GeometricSwipeOracleTest` | 10 | 10 passed |
| `SwipeMLDataStoreTest` | 38 | 38 passed |
| **Total** | **48** | **48 passed, 0 failed, 0 ignored** |

Every test the audit owed executed and passed: the two NEW M-2 race pins
(`oracle_geo_prewarmDuringDecode_doesNotCancelDecode` — a prewarm issued mid-cold-decode no
longer cancels it; `oracle_geo_newerDecodeSupersedesOlder` — last-swipe-wins is intact and the
superseded callback never fires), the three m-5 hardened parity tests
(`oracle_geo_shiftAtStart_capitalizesCommit`, `oracle_geo_contractionAlias_displaysApostropheForm`,
`oracle_geo_pairedBase_keepsWordAndInjectsVariant`), the m-1 marker, the NEW n-2 legacy-row
test (`testSwipeMLDataFromLegacyJSONWithoutProvenance` — pre-provenance rows read `unknown`
rather than throwing), and the two provenance-augmented JSON tests. The run logged **zero**
`AssumptionViolated` lines, which is the positive proof that the m-5 `assumeTrue` → hard-assert
conversion actually asserts instead of silently skipping.

Gate caveat for whoever reruns this: `--outputs logcat` is an allowlist that REPLACES ew-cli's
default `merged_results_xml,coverage,pulled_dirs`, so that invocation writes no `results.xml`
and a stale one from an earlier run stays in `--outputs-dir`. Counts above were taken from the
run's logcat (`TestRunner: run finished: 48 tests, 0 failed, 0 ignored`) plus ew-cli's own
`All tests passed`. Use `--outputs merged_results_xml,logcat` next time; noted in
`.claude/skills/ew-cli-testing.md`.
