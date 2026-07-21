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

- **Step 7**: `swipe/SwipeEngineRouter.kt` (pure object, KeyboardData + string overloads) at the
  IC `handleSwipeTyping` gate: QWERTY-Latin → NEURAL (unchanged), else GEOMETRIC iff
  `Config.geometric_swipe_engine` (default FALSE, classified in `SETTINGS_DEFAULTS`; no UI —
  same debug-pref pattern the step-4 flag used), else NONE. No cross-engine score comparison;
  one engine owns each swipe end-to-end.
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
- **Step 9**: `SwipeEngineRouterTest` (9 JVM routing pins incl. the Greek-QWERTY trap) +
  `GeometricSwipeOracleTest` (instrumented, Dvorak+bundled-en so no langpack import needed):
  real-engine decode pin ("world" in top-3), seam commit + NEURAL_SWIPE tracking, password
  suppression on geo results, shift-capitalized commit, router re-pin on real KeyboardData,
  and a p95<150 ms warm decode gate (engine core is ~ms-scale; gate absorbs emulator noise).
