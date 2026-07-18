# CleverKeys C+ → A Remediation Roadmap

**Date:** 2026-07-18
**Base commit:** `1db22cd6b` (11 commits past the 2026-07-17 audit's `4ad8a536d`)
**Source audit:** `docs/audit/2026-07-17-code-quality-audit.md` (overall **C+**)
**Author:** Fable-planned, verified against HEAD, executed via Opus implementers.

This roadmap sequences the work to raise the overall grade. It records what
Tier-1/2 remediation already closed, the per-dimension grade gaps, and a
dependency-ordered work-package list with explicit risk/DEFER flags.

---

## Verified current state (do not re-plan)

**Closed since the audit** (verified in git log / source): encoder+greedy ONNX
leak, predict/cleanup RW-lock, `readBytes()` model load, retryable init + backoff
+ stale-IC guard, busy-wait→`EngineInitGate` + silent-catch + executor shutdown,
PII `vlog` gating (SuggestionHandler/Pointers/ClipboardDatabase) + migration
rethrow + null-safe `globalConfig()`, doc drift (SECURITY.md 1.5.x, README SDK 21,
CLAUDE.md tree), 10 activities de-exported, zip-slip guard + bounded reads,
release.yml/ui-testing.yml gating + `TestRunnerListDriftTest`, AES-GCM encrypted
backups, headless plaintext-import enforcement (F1), full #156 private copy + its
review fixes (cut-leak, rate-limiter clock, cold-start NPE, over-cap feedback,
DRY dispatch, extra-keys binding, customization wiring, wiki docs).

**Still open** (re-verified at HEAD, audit line-drift noted): Turkish-i
(`Keyboard2View.kt:1075`, audit :1049), `moveCursorSel` d==0 (`KeyEventHandler.kt:812`),
no `supportsRtl`, ungated PII logs (`GreedySearchEngine.kt:128`, `Keyboard2View.kt:819,852`),
5 Python pipeline defects, ~3.9k-LOC dead cluster, `MultiLanguageManager` phantom
per-language models, IC dead methods, `isLikelyNoise` stub, lint ungated, R8 off,
git size-pack 18.38 GiB, versionCode collision formula, `AutoSpaceLogicTest` mirror
rot, web-demo dict drift (52k vs 98k), 29 `Config.globalConfig()` static consumers,
dual pipeline, SuggestionBar hardcoded-English `dict_add:` protocol, no a11y.

---

## Grade-gap analysis

| Dimension | Audit | Anchor subscore | Post-Tier-1/2 reality | Lifts it to |
|---|---|---|---|---|
| Neural | C+ | ONNX lifecycle 3 | leak/race/init fixed → ~7-8 | **A−** via dead-code deletion; A needs calibration-gated beam-scoring |
| Data/Security | B− | import/export 5, manifest 5 | encryption+de-export+bounds landed → ~8 | **A−** by gating last 3 PII logs |
| Eng practices | B− | CI 4.5, repo hygiene 3 | gating+drift landed → ~6 | B+ via lint gate + Python fixes + CI dedup; **A gated on R8 soak + git rewrite + EW_API_TOKEN** |
| Core IME | C+ | error handling 5 | busy-wait/catch/executors landed → ~6.5 | B via dead-IC deletion + scope cancels; **A gated on pipeline unification** |
| UI | C+ | **accessibility 1** | nothing landed | **ExploreByTouchHelper is the biggest single subscore delta (1→8)**; then perf, i18n, supportsRtl, theming |
| Architecture | C+ | package org 3.5 | doc + null-safe Config landed | dir-only reorg + ConfigSnapshot; **A needs multi-week strangler (Tier 4)** |

**Four highest-leverage items:** (1) a11y `ExploreByTouchHelper` (UI 1→8;
skeleton in `remediation/4-ui-layer.md:139-256`), (2) dead-code deletion (~3.9k
LOC; lifts Neural + Core-IME + makes "NO CGR" true), (3) lint-gate + Python +
mirror-seam batch (~1 day, machine-verifiable), (4) pipeline unification (the
Core-IME A gate; big + risky).

---

## Sequenced work packages

- **WP1 — micro-correctness + PII tail** (~3h, LOW): Turkish-i, moveCursorSel guard,
  supportsRtl, gate last 3 PII logs. → Data/Sec A−. Verify: compile + runPureTests.
- **WP2 — Python dictionary-pipeline integrity** (~4h, LOW): spell-oracle fail-loud
  (+ hunspell sibling), V2 truncation, reproducible langpack ZIP + dated header,
  astral-codepoint masking in generate_compose_bin.py. Verify: python3 snippets.
- **WP3 — eng-practices cheap batch** (~1 day, LOW): lint gate phase 1
  (`abortOnError=true`, drop ci `continue-on-error`), AutoSpaceLogicTest→real seam,
  CI dedup, delete `remote_v*`, versionCode overflow **guard** (assert, not scheme
  change), web-demo dict parity copy.
- **WP4 — dead-code deletion** (~1 day, LOW): delete ContinuousGestureRecognizer,
  WordGestureTemplateGenerator, ComprehensiveTraceAnalyzer, TemplateBrowserActivity,
  NgramModel, onnx/{MemoryPool,BroadcastSupport,SessionConfigurator,DecoderWrapper}
  + co-delete their tests + runner entries; MultiLanguageManager phantom-model path;
  IC dead methods; isLikelyNoise stub. **BigramModel NOT deleted** (live 10× ranking
  multiplier — behavioral, defer to calibration batch). Verify: rg zero-caller +
  compile + runPureTests + runMockTests (drift test catches runner misses).
- **WP5 — lifecycle tail** (~0.5 day, LOW-MED): Pointers/EmojiKeywordIndex scope
  cancel; prompt-race guard deferred into WP9 (disappears structurally).
- **WP6 — UI render perf + SuggestionBar** (1.5-2 days, MED): pre-index
  getMappingsForKey, cached keyCodeLower, hasAnyMappings early-out; SuggestionBar
  TextView recycling + sealed Suggestion type + i18n the dict-add string. Producers
  + consumer in one commit; SuggestionBarAutofillTest must stay green. Partial ew-cli.
- **WP7 — accessibility (ExploreByTouchHelper)** (2-3 days, MED, **REQUIRES ew-cli**):
  per remediation/4-ui-layer.md skeleton; extract computeKeyRects() shared geometry,
  pure describe(KeyValue) labeller, touch-exploration-off fast-path; wire-or-remove
  dead sticky_keys/voice_guidance toggles. Cloud-testable on emulator.wtf (not
  blocked on local device); budget a 25-min gate run.
- **WP8 — i18n + theming unification** (2-3 days, LOW-MED): CleverKeysTheme wrapper
  replacing inline darkColorScheme() forks; extract ~72 raw Text("…") literals to
  R.string (scope: extraction, not full translation).
- **WP9 — pipeline unification** (4-6 days, HIGH, **DEFER**): Core-IME A gate; needs
  instrumented characterization oracle + feature-flagged soak + Termux-deletion
  decision. WP4's IC dead-code deletion is its prerequisite.
- **WP10 — architecture strangler** (5-6 days, Tier 4 "not grade-gates"):
  ConfigSnapshot hot-path, dir-only package reorg (git mv, no package-statement
  change → zero import churn — pull the reorg forward if the 158→<100 metric is
  wanted), composition root, Predictor/Vocabulary interfaces.

**Hard-gated — do not attempt without the gate:**
- **R8 re-enable** — mandatory full ew-cli soak + reflection-keep audit + release soak.
- **Git-history rewrite** (18.38 GiB) — `strip-repo-history.sh` gated on F-Droid publish of v1.5.0.
- **versionCode scheme change** — F-Droid monotonicity; WP3's build-time assert is the safe substitute.
- **ew-cli CI job** — needs `EW_API_TOKEN` repo secret from the user.
- **Beam-scoring/BigramModel** — behavioral; gate behind SwipeCalibrationActivity A/B.

---

## Honest ceiling

Reachable via code alone, no device, no release coordination: **Data/Security A−,
Neural A−, Eng practices B+, Core IME B, UI B− (B+/A− only with WP7's cloud a11y
verification), Architecture B−/B.** Realistic best overall this session: **solid
B+** (mean ≈ 3.0-3.2).

Remaining delta to **A** requires exactly four gated things: (1) pipeline
unification + instrumented oracle + soak (~1 week), (2) R8 re-enable + full ew-cli
soak + internal-track release, (3) git-history rewrite + LFS after F-Droid
publishes v1.5.0, (4) sustained architecture strangler (ConfigSnapshot to ≤10
static consumers, package reorg completion, interfaces).

Recommended execution order: **WP1 → WP2 → WP3 → WP4** (all machine-verifiable),
then WP7 (biggest grade lever, ew-cli-verifiable) or WP6.
