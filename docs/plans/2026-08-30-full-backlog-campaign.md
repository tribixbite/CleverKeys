# Full-backlog remediation campaign — 2026-08-30

**Mandate (maintainer, 2026-08-30):** remediate the ENTIRE open backlog (ledger ARC items +
anything new found en route) IN FULL, excluding only items that genuinely need maintainer
input. Then test everything testable via adb on BOTH devices — pixel 8 pro `192.168.0.216:5555`
and saga `192.168.1.243:5555` (maintainer authorized adb TESTING for this campaign,
superseding the build-install-only policy for its duration; Saga hard rules stand: NO
`stop;start`/framework restart EVER, avoid reboot, leave no trace, restore any changed
setting). Maintainer manual soak (ARC-053) happens AFTER the backlog is cleared.

**Method (model routing updated 2026-08-30, maintainer mid-flight):** implementer subagents run
on FABLE, not Opus, until the maintainer says their weekly Fable limit is reached (orchestrator
cannot observe limits; maintainer will flip the switch verbally). Waves of implementers under
the proven shared-tree protocol (file fences,
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
- ARC-054 now also covers el: Greek WIRING is executing (see below); ANNOUNCING it is yours.
- ARC-063 keep-narrowing — explicitly AFTER your first minified soak.
- `finger_occlusion_offset` nonzero default — needs YOUR device-trace A/B.

## Wave-I probe RESULT (2026-08-30): the ML repo IS on this device

`~/git/swype/CleverKeys-ML` exists locally, including `ctc/artifacts/el_synth_v3_ch80_fp16w.onnx`
+ golden fixture; `~/git/swype/AnySoftKeyboard` (the ASK checkout) and `scripts/build_wordlist.py`
are present too. Consequences:
- **ARC-055 EXECUTABLE — dispatched** (agent EL): wire Greek per the ru precedent, most
  conservative tier (synthesis-holdout only, NO accuracy quotable), `SERVED_BUT_NOT_YET_ANNOUNCED
  += el`. Maintainer decides announcement (ARC-054) and can veto at soak.
- **ARC-056 ATTEMPTABLE on-device**: build uk/bg/mk/he lexicons + CKDT langpacks (immediate value:
  those packs serve TAP + GEOMETRIC users today; CTC models for them remain GPU-side/blocked).
  `he` needs the `hebrew` branch in `_is_script_word`. Toolchain deps (hunspell dicts, wordfreq)
  to be probed by the executing agent; report BLOCKED honestly if a dep is missing.
- **ARC-060/061 EXECUTABLE**: scripts live in CleverKeys-ML — commits go to THAT repo (no push).
- **Verb inversions FEASIBLE**: extraction pipeline + data deps present; late wave, strict
  landmine pins (`estelle` @16343 native, `aton` ASK-attested, `entretemps` needs FORCED_APPEND).

## Waves

| Wave | Items | Status |
|---|---|---|
| A1 | ARC-099 dead swipe-prediction chain; ARC-088 KeyModifier.modify memoize | complete (both committed; focused tests green) |
| A2 | ARC-062 dead META-INF excludes; ARC-096 release lint; ARC-090 NOTICE ru; ARC-059 gate repoint | implementation complete; ARC-053 minified soak still owed |
| A3 | ARC-093 fractional coercion; ARC-094 learned-data preview rows; ARC-100; ARC-101 notes | complete and committed |
| B | ARC-086 layout-axis fallback card + authoring doc; ARC-075 GifPanel status decouple; ARC-102 epoch cache; ARC-065 measure-on-boot | complete and committed |
| C (tests) | ARC-091/092/095/074/064/077/058; ARC-030 floors | implementation/host checks complete; full Wave-J device execution pending |
| D (i18n) | ARC-066 mode-desc; ARC-087 provenance extraction; ARC-067 full locale pass | **COMPLETE 2026-09-01**: 21/21 locales, plurals gap filled, ALL 373 MissingTranslation suppressions removed, lint green unsuppressed (`1ec6a7ef`); machine translations pending maintainer native review |
| E (docs/tooling) | ARC-073, ARC-089, ARC-076, ARC-098, doc claims, orphan data | **COMPLETE 2026-09-01**: `d20ed3b5`/`f482faf4`/`372abe7b` (ARC-098's gesture/wiring half lives in wave F) |
| F (arch) | ARC-072 slice 3: 6 Initializers → `wiring/KeyboardComponentGraph` + Bridges move + ARC-098 gesture/ cluster (reorg <100 root) | pending |
| G (geo) | ARC-027 OQ-9 overshoot clamp; ARC-028 OQ-10 ordering slack; ARC-029 OQ-11 reversal signal — each gated on local-corpus replay evidence (no blind accuracy changes); ARC-030 floors ride | pending |
| H (web) | ARC-071 astro 5→6 (+drop .trivyignore lines); ARC-046 web-demo regression gate + Tailwind vendoring | complete (15814849, af9bfd8e; astro 6.4.8, 84 pages; 32-check gate green on HEAD, red on pre-fix sources; demo has zero network deps) |
| I (ML) | ARC-056 uk/bg/mk/he lexicons; ARC-060 ru layout regen; ARC-061 golden path | **COMPLETE 2026-09-01** (`538a1633`+`86156ea3`, ML repo `7343355`): 4 langpacks built (uk/mk/he 50k, bg 35,027 wordfreq-ceiling; he via Tier-C AOSP oracle, uk/bg/mk Tier-D) — serve TAP+GEOMETRIC on import, CTC stays model-blocked per rule 4. **ARC-060 deliberately STOPPED**: regen deltas material (cx max 3.35e-3 vs 4.7e-4 noise); geometry swap = geometry+fixture+parity as ONE unit — maintainer/next-session decision. Verb inversions still pending (wave G batch) |
| J | FULL ew-cli run: all owed instrumented items (T13, ARC-083 rider, userDictionarySource seam harness, guard e2e, ARC-064, ARC-077, ARC-058) | pending |
| K (device) | adb verification on BOTH phones: release APK (LOCAL_BUILD=true) install; ARC-070 long-run MemoryProbe + dumpsys meminfo; ARC-068 #79 hwui dirty-regions discriminator (restore prop after); ARC-069 what's scriptable (#148 pane overlay screencaps, first-swipe warm-up timing from logcat, swipe decode via `input swipe` on a focused field, light+dark screens); IME enable/set with original IME restored — leave no trace | pending |
| L | Final consolidation: ledger/HANDOFF/this file; needs-input report to maintainer | partial: HANDOFF, backlog, and campaign state consolidated; ledger/report remain |

New items found mid-campaign: append to the ledger with the next free ARC id and add to a wave.

## Wave-K device protocol notes

Record BEFORE state (current IME via `settings get secure default_input_method`, any prop
changed), restore AFTER. Saga: never `stop`/`start`/zygote restart; prefer no reboot. Release
APK only (`LOCAL_BUILD=true` build keeps verbose logging). Screenshots ≤2000px and <4MB.
What adb cannot reach (collision DIALOG rendering with a real pack re-selection, .ckenc
passworded export UX, pre-v1.6.0/pre-v1.1.86 upgrade paths with real user data) stays on the
maintainer soak list.
