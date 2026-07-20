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
