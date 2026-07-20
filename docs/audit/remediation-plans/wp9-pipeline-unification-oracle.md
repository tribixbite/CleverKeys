# WP9 — Pipeline Unification: Characterization Oracle (Step 2 of R-1)

**Date:** 2026-07-20
**Author:** Fable (design), Opus (implementation)
**Parent plan:** `docs/audit/remediation/3-core-ime.md` → "Pipeline Unification Plan"
**Status of prerequisites:** R-2 (IC dead code) DONE (WP4, 2026-07-18); R-4 (logged catch) DONE;
R-5/R-6 lifecycle DONE (WP5). This doc specifies **step 2**: the regression oracle that must be
green against CURRENT code before any logic moves (steps 3–7).

## What the oracle is

A suite of tests that records today's exact suggestion/commit behavior for both pipelines —
including their known divergences — so the unification (SuggestionHandler survives, IC becomes a
thin swipe front-end) can be verified step-by-step. Assertions are of two kinds:

- **INVARIANT** — behavior that must never change (e.g. contraction injection, exact-add wire,
  auto-space counts, Termux deletion). A failure here at any step is a regression. Full stop.
- **DIVERGENCE-PINNED** — assertions that pin a *known divergence of today* and are expected to
  flip at a specific migration step. Each carries a `// ORACLE-FLIP(step N)` comment. When step N
  lands, the assertion is inverted in the same commit — never silently.

## Verified divergences being pinned (evidence at HEAD)

| # | Divergence | Today | Flips at |
|---|---|---|---|
| D1 | Possessive augmentation (`augmentPredictionsWithPossessives`, SH:1474) | tap+cursor-sync only; absent in IC `handlePredictionResults` (IC:491-570) and `triggerPredictionsForPrefix` | step 4 (swipe gains possessives) |
| D2 | Password guard (`swipe_on_password_fields`, SH:300-303) | tap path only; IC posts predictions unguarded | step 4 |
| D3 | `specialPromptActive` prompt guard (SH:170, 1313-1319) | tap path only; cursor-sync can overwrite prompts (R-7 race) | step 5 |
| D4 | Shift/caps-at-swipe-start (`applyShiftTransformation`, IC:471-485, applied IC:513) | swipe path only; SH derives caps from typed partial | step 3 (relocated into SH — behavior identical, location moves) |
| D5 | ML capture on swipe selection inline in IC (IC:673-707) vs `MLDataCollector` on tap | two implementations | step 4 (swipe routes through MLDataCollector) |

## Harness

Extend the proven `ContractionFlickerIntegrationTest` pattern (instrumented; real
SuggestionHandler + SuggestionBar + PredictionContextTracker + ContractionManager + WordPredictor
+ real `BaseInputConnection` over an EditText; mock `KeyEventHandler.IReceiver`; reflection-inject
WordPredictor into PredictionCoordinator; `drainMainThread()` sync).

**Swipe-path determinism:** do NOT run the neural engine. Characterize the post-prediction
transformation chain by invoking `InputCoordinator.handlePredictionResults(predictions, scores,…)`
directly with synthetic prediction lists (this is the exact seam AsyncPredictionHandler calls at
IC:1236-1238). Shift state is set via `handleSwipeTyping`'s parameters or direct field access if
needed. This keeps the oracle deterministic and fast; neural correctness is covered elsewhere.

**Commit-behavior capture:** assert on the EditText buffer + `BaseInputConnection` state after
selection (deletion counts, leading/trailing spaces, replacement mid-sentence), not just on the
suggestion list.

## Scenario matrix (each = one test; ~30 tests)

Naming: `oracle_<path>_<scenario>`. All INVARIANT unless marked FLIP.

**Swipe auto-insert (IC path):**
1. plain swipe → top prediction committed, trailing space per config, `NEURAL_SWIPE` source
2. shift-at-swipe-start → first-letter capitalized across ALL bar suggestions (D4 — FLIP step 3: location only, behavior identical; assertion stays, wiring changes)
3. caps-lock-at-swipe-start → full-caps across bar
4. swipe with `raw:`-prefixed prediction → prefix stripped before commit + tracking
5. swipe replacing a previous swipe → old word deleted via `deleteSurroundingText`, correct counts
6. swipe during manual typing → typed partial replaced (prefix/suffix delete counts)
7. password field + `swipe_on_password_fields=false` → predictions still posted TODAY (D2 — FLIP step 4: becomes suppressed)
8. possessives absent from swipe bar TODAY for a possessive-eligible word (D1 — FLIP step 4: "book's" appears)
9. Termux editor (`termux_mode_enabled`): trailing-space suppression on non-swipe; swipe path unchanged — pin exact current behavior of both
10. swipe → tap alternate in bar → auto-inserted word replaced, `CANDIDATE_SELECTION` source, ML capture fires (D5 — FLIP step 4: capture via MLDataCollector, same data)
11. contraction swipe (prediction "dont") → committed as prediction gives it (pin exact)
12. `swipe_final_autocorrect_enabled` on/off → autocorrect applied/skipped on selection

**Tap/typing (SH path):**
13. type letters → predictions include paired contraction ("its" → "it's") [exists — keep]
14. single-char prefix → no paired injection [exists — keep]
15. unknown word → `exact_add:` wire [exists — keep]
16. possessives present in tap bar (D1 control — INVARIANT)
17. autocorrect-on-space + undo prompt → `specialPromptActive` blocks async overwrite (D3 control)
18. add-to-dictionary prompt survives a cursor-sync racing in TODAY only on tap (D3 — cursor-sync side FLIP step 5)
19. password field typing → suggestions cleared (D2 control)
20. I-word capitalization ("i" → "I", "i'm" → "I'm") on space
21. mid-word tap selection → prefix/suffix deletion + replacement
22. URL/email field (#151/#78 fallback) → no predictions / editor-scan fallback pinned
23. preserveCapitalization on autocorrect of capitalized typed word

**Cursor-sync (IC → step 5 target):**
24. cursor move into word → same contraction suggestions as typing path [exists — keep]
25. cursor-sync possessives absent TODAY (D1 — FLIP step 5)
26. cursor-sync exact-add present [pin]
27. cursor-sync debounce: two rapid moves → one prediction pass (pin executor behavior)

**Cross-path invariants:**
28. SuggestionBar dedup: identical repost → no re-render (pin via listener/child identity)
29. last-post-wins: typing task then cursor-sync task → final bar state deterministic (drain both)
30. `getCurrentSuggestions()` wire format unchanged (List<String>, prefixes only via Suggestion.kt)

## Placement & gates

- File: `src/androidTest/kotlin/tribixbite/cleverkeys/PipelineCharacterizationTest.kt`
  (one class; group with `@SdkSuppress` none; orchestrator-safe, no cross-test state).
- Pure-JVM extraction: any scenario not needing InputConnection/View (e.g. possessive augmentation
  pure function, shift transformation pure function) also gets a JVM test in
  `src/test/.../PipelineOracleJvmTest.kt` for fast iteration; the instrumented class remains the
  authority.
- Gate to declare step 2 done: full ew-cli run green INCLUDING the new class; all
  divergence-pinned assertions passing against HEAD (i.e. they assert today's behavior).
- Steps 3–7 then proceed per R-1, each flipping only its marked assertions in the same commit,
  feature-flagged reroute (steps 4–5) behind a debug pref per the plan.

## Out of scope for step 2

No production-code changes. No reroutes. The Termux-deletion decision (R-1 step 7) stays a
separate, user-visible decision; scenario 9 pins current behavior so that decision is explicit.

## Step 4 — LANDED (2026-07-20)

First behavior-changing step. Feature-flagged via `Config.unified_swipe_pipeline` (debug pref,
DEFAULT TRUE; registered in `Defaults.UNIFIED_SWIPE_PIPELINE`, `Config.unified_swipe_pipeline`,
loaded from `"unified_swipe_pipeline"`, classified in `SETTINGS_DEFAULTS` for the drift test).

**Delegation structure:** `InputCoordinator.handlePredictionResults` dispatches on the flag. When
TRUE (default) + delegate wired, it calls the new `SuggestionHandler.handleSwipePredictionResults`,
which owns BAR presentation (user-word case, shift/caps transform, possessive augmentation,
password guard) and delegates the COMMIT back to the extracted `InputCoordinator.autoInsertTopSuggestion`
(the byte-identical deletion/spacing/tracking engine — `IC.onSuggestionSelected` unchanged). This
keeps every commit-path oracle (1,4,5,5b,9,11,12) byte-identical while closing D1/D2 on the bar.

- **D1** (possessives): CLOSED — swipe bar posts + re-displays the augmented list (scenario 8 flip).
- **D2** (password guard): CLOSED — swipe suppressed on password fields unless opted in (scenario 7 flip).
- **D5** (ML via MLDataCollector): DEFERRED to step 6 — the kept `IC.onSuggestionSelected` still holds
  the inline ML block; routing here would touch the commit path. Oracle already skips D5's pin.

**Oracle changes (old → new):**
- Scenario 7 `oracle_swipe_passwordField_stillCommitsToday` → `oracle_swipe_passwordField_suppressedWhenNotOptedIn`:
  was `assertEquals("hunter2 ", buffer)` + NEURAL_SWIPE source → now `assertEquals("", buffer)` +
  "hunter2" absent from bar.
- Scenario 8 `oracle_swipe_possessivesAbsentFromBarToday` → `oracle_swipe_possessivesPresentInBar`:
  was `assertFalse(bar has "book's")` → now `assertTrue(bar has "book's")` + `assertEquals("book ", buffer)`.
- Added legacy-path guards (flag FALSE): `oracle_swipe_passwordField_legacyPathStillCommits` (commits
  "hunter2 ") and `oracle_swipe_possessives_legacyPathAbsent` (no "book's").
- Harness: `harness()` now sets `config.unified_swipe_pipeline = true` and calls
  `inputCoordinator.setSwipeResultDelegate(suggestionHandler)` (mirrors ManagerInitializer). The
  `swipeResults()` seam (`IC.handlePredictionResults`) is unchanged — IC still dispatches internally.

## Step 5 — LANDED (2026-07-20)

Cursor-sync prediction rerouted from InputCoordinator to SuggestionHandler under the SAME
`Config.unified_swipe_pipeline` flag (default TRUE) — one flag governs the whole reroute program.

**Delegation structure:** `InputCoordinator.onCursorMoved`'s debounced runnable KEEPS all cursor
bookkeeping — `PredictionContextTracker.onCursorPositionChanged` (SAS-1 auto-space invalidation,
synchronous), the 100ms `syncHandler` debounce (oracle scenario 27), and
`PredictionContextTracker.synchronizeWithCursor(ic, language, editorInfo)` (which populates
`currentWord` with the synced rawPrefix; the `language` param is the ONLY consumer of that arg —
CJK skip + input-type gating). Only the prediction+post phase (when the synced prefix is non-empty)
dispatches on the flag: TRUE + delegate wired → new `SuggestionHandler.handleCursorSyncPrediction`
(which calls the private `updatePredictionsForCurrentWord` — the SAME pipeline the typing path uses,
reading the already-synced `currentWord`); FALSE / unwired → legacy `IC.triggerPredictionsForPrefix`
unchanged. The empty-prefix else-branch (preserve-vs-clear the bar on autocorrect-undo / swipe) stays
in IC for both paths; SH is only reached with a non-empty prefix, so there is no double-clear race.

**Language selection preserved:** both `IC.triggerPredictionsForPrefix` and
`SH.updatePredictionsForCurrentWord` call `predictionCoordinator.getWordPredictor()` — the identical
shared active predictor (its language set by DictionaryManager). Neither passes a language into the
predictor call; the `onCursorMoved` `language` param flows only to `synchronizeWithCursor`, which IC
still owns and calls exactly as before. So predictor language is byte-identical.

**R-7 resolved structurally:** `updatePredictionsForCurrentWord` already guards on
`specialPromptActive` (before submit AND inside the posted runnable). Folding cursor-sync into it
means a cursor-sync pass can no longer clobber an SH autocorrect-undo / add-to-dictionary prompt —
there is ONE guarded pipeline, no new shared mutable state across classes.

**Behavior deltas (only these):**
- **D-exactAdd**: cursor-sync now surfaces `exact_add:` for an unknown word even with ZERO
  predictions. Legacy IC early-returned on `allResults.isEmpty()` before its exact-add branch and
  post-guarded on `finalWords.isNotEmpty()`; SH runs the exact-add branch on the empty list. (Oracle
  scenario 26 flip.)
- **Prompt-guard**: cursor-sync respects `specialPromptActive` (R-7). (Oracle scenario 18, now
  deterministic + implemented.)
- **Possessives (scenario 25)**: NO gateable delta — `updatePredictionsForCurrentWord` does NOT call
  `augmentPredictionsWithPossessives`; dictionary possessives ("book's") arrive as ordinary
  predictions on BOTH paths. Assertion UNCHANGED (not weakened).

**Dual-apostrophe search preserved:** `updatePredictionsForCurrentWord` gained an apostrophe-stripped
secondary search term that fires ONLY when the primary (apostrophe-carrying) search is empty —
restoring the legacy IC cursor-sync dual-search for prefixes like "don'". For the typing path the
partial is letters-only, so the second term equals the first and is skipped (pure no-op → tap path
byte-identical).

**Oracle changes (old → new):**
- Scenario 26 `oracle_cursorSync_unknownWordPostsNothingToday` → `oracle_cursorSync_unknownWordShowsExactAdd`:
  assertion INVERTED — was `assertTrue(bar.isEmpty())`, now `assertTrue(bar has exact_add:xyzq)`.
- Added legacy-path guards (flag FALSE): `oracle_cursorSync_unknownWord_legacyPathPostsNothing`
  (empty bar) and `oracle_cursorSync_dictionaryPossessives_legacyPathAlsoSurfaces` ("book's" present).
- Scenario 25 `oracle_cursorSync_dictionaryPossessivesSurfaceToday`: assertion UNCHANGED; comment
  updated to record the verified no-delta.
- Scenario 18: NEW deterministic test `oracle_cursorSync_doesNotClobberAutocorrectUndoPrompt`
  (previously SKIPPED). Raises an SH autocorrect-undo prompt via the real typing path, fires a real
  cursor-sync pass into a pre-existing word, asserts the prompt survives (bar still leads "teh", no
  "it's" injected). The skip rationale block was updated to point at it.
- Harness: `harness()` now also calls `inputCoordinator.setCursorSyncDelegate(suggestionHandler)`
  (mirrors ManagerInitializer).
