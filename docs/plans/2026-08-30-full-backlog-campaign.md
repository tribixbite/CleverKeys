# Full-backlog remediation campaign — 2026-08-30

**Mandate (maintainer, 2026-08-30):** remediate the ENTIRE open backlog (ledger ARC items +
anything new found en route) IN FULL, excluding only items that genuinely need maintainer
input. Then test everything testable via adb on BOTH devices — pixel 8 pro `192.168.0.216:5555`
and saga `192.168.1.243:5555` (maintainer authorized adb TESTING for this campaign,
superseding the build-install-only policy for its duration; Saga hard rules stand: NO
`stop;start`/framework restart EVER, avoid reboot, leave no trace, restore any changed
setting). Maintainer manual soak (ARC-053) happens AFTER the backlog is cleared.

**Method:** waves of Opus implementers under the proven shared-tree protocol (file fences,
`scripts/gradle-guard.sh` for every Gradle call, fail-first TDD with captured evidence,
partial staging of shared files, immediate commits, Fable review of every diff). Ultracode is
authorized. This file is the continuity artifact — update the status column as waves land.

## Fable decisions taken (so agents don't re-litigate)

- ARC-059: REPOINT the latency gate at `CtcBeamDecoder` (the production path); investigate
  `CtcSwipeDecoder` deadness and delete it too only if provably caller-free including tests.
- ARC-093: COERCE legacy fractional `finger_occlusion_offset` → `IntV(round)` (preserve
  calibrated values; do not reject).
- ARC-101: DECLINED-with-note — both exact-match `isUserWord` UI sites stay exact; KDoc each
  with the intentionality (additive prompts; folding changes UX timing, not safety).
- ARC-102: IMPLEMENT the epoch-based snapshot cache per the `TODO(perf)` design
  (observer-gated; cache disabled while the observer is not registered).
- ARC-065: IMPLEMENT measure-on-boot for out-of-band pack arrival (closes the
  first-swipe-goes-geometric window).
- ARC-100: fix the stale rationale; RECLASSIFY only if a preview-behavior test proves the
  null-default read is truly gone.

## Needs maintainer input (parked, final report will list)

- ARC-053 minified-release soak (yours, after campaign) — ARC-062/096 land NOW so one soak covers them.
- ARC-054 release-notes decision (announce ru or hold the wiring).
- ARC-055 Greek routing — evidence-tier appetite + the el ONNX/fixture artifacts live ML-side.
- ARC-063 keep-narrowing — explicitly AFTER your first minified soak.
- `finger_occlusion_offset` nonzero default — needs YOUR device-trace A/B.
- ML-side (likely not runnable from Termux; verified in Wave I): ARC-056 uk/bg/mk/he lexicons,
  ARC-060 ru layout provenance regen, ARC-061 make_golden home-path fix.

## Waves

| Wave | Items | Status |
|---|---|---|
| A1 | ARC-099 dead swipe-prediction chain; ARC-088 KeyModifier.modify memoize | dispatched |
| A2 | ARC-062 dead META-INF excludes; ARC-096 release lint; ARC-090 NOTICE ru; ARC-059 gate repoint | dispatched |
| A3 | ARC-093 fractional coercion; ARC-094 learned-data preview rows; ARC-100; ARC-101 notes | dispatched |
| B | ARC-086 layout-axis fallback card + authoring doc; ARC-075 GifPanel status decouple; ARC-102 epoch cache; ARC-065 measure-on-boot | pending |
| C (tests) | ARC-091 zip-slip importer; ARC-092 private-copy pins; ARC-095 SuggestionBar recycling; ARC-030 prune-recall floors; ARC-074 CrashGuard seam; ARC-064 pack edges (instrumented); ARC-077 a11y CK-150-027/029; ARC-058 rotation-memory instrumented | pending |
| D (i18n) | ARC-066 mode-desc reword (EN first) → ARC-087 provenance-sheet extraction → ARC-067 full 21-locale pass (workflow over locale files; disjoint per locale) + §3 owed strings | pending |
| E (docs/tooling) | ARC-073 + micro-bucket; ARC-089 geo-spec annotate; ARC-076 geometry-table relocate + deletions; ARC-098 phantom-keyboard2 tooling sweep; §3 doc-claims (core-keyboard-system, testing-strategy, ARCHITECTURE_MASTER, settings-system); contraction_pairings_cleaned.json deletion | pending |
| F (arch) | ARC-072 slice 3: 6 Initializers → `wiring/KeyboardComponentGraph` + Bridges move + ARC-098 gesture/ cluster (reorg <100 root) | pending |
| G (geo) | ARC-027 OQ-9 overshoot clamp; ARC-028 OQ-10 ordering slack; ARC-029 OQ-11 reversal signal — each gated on local-corpus replay evidence (no blind accuracy changes); ARC-030 floors ride | pending |
| H (web) | ARC-071 astro 5→6 (+drop .trivyignore lines); ARC-046 web-demo regression gate + Tailwind vendoring | pending |
| I (probe) | ML-side feasibility check (056/060/061 — scripts/artifacts on this device?); verb-inversions feasibility (extract_apostrophe_words data deps); report BLOCKED items honestly | pending |
| J | FULL ew-cli run: all owed instrumented items (T13, ARC-083 rider, userDictionarySource seam harness, guard e2e, ARC-064, ARC-077, ARC-058) | pending |
| K (device) | adb verification on BOTH phones: release APK (LOCAL_BUILD=true) install; ARC-070 long-run MemoryProbe + dumpsys meminfo; ARC-068 #79 hwui dirty-regions discriminator (restore prop after); ARC-069 what's scriptable (#148 pane overlay screencaps, first-swipe warm-up timing from logcat, swipe decode via `input swipe` on a focused field, light+dark screens); IME enable/set with original IME restored — leave no trace | pending |
| L | Final consolidation: ledger/HANDOFF/this file; needs-input report to maintainer | pending |

New items found mid-campaign: append to the ledger with the next free ARC id and add to a wave.

## Wave-K device protocol notes

Record BEFORE state (current IME via `settings get secure default_input_method`, any prop
changed), restore AFTER. Saga: never `stop`/`start`/zygote restart; prefer no reboot. Release
APK only (`LOCAL_BUILD=true` build keeps verbose logging). Screenshots ≤2000px and <4MB.
What adb cannot reach (collision DIALOG rendering with a real pack re-selection, .ckenc
passworded export UX, pre-v1.6.0/pre-v1.1.86 upgrade paths with real user data) stays on the
maintainer soak list.
