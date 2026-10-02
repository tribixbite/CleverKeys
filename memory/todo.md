# Current work queue

Updated: 2026-10-02. Full execution state and test evidence: [HANDOFF.md](HANDOFF.md).
Campaign plan: [`docs/plans/2026-08-30-full-backlog-campaign.md`](../docs/plans/2026-08-30-full-backlog-campaign.md).

The September 1 campaign baseline was `5fb58037`; subsequent work through `79f0b464`
was pushed with maintainer authorization on September 27. Preserve shared-tree work.

## October 2 issue work before 2.0

The maintainer wants GitHub feature/bug work and personal testing before release.
Do not tag, bump, push, publish, or close issues as part of this round. Preserve the
maintainer-owned uncommitted manual-checklist resets.

- [x] Recovered the pending SubkeyAssignActivity lint fix. The prior guarded lint run
  finished successfully in 50m16s (0 errors, 210 warnings), after the source edit.
- [x] #145 cold-start regression pin: real service-handle assignment works with
  prediction and swipe typing both off, including replacement views, without model load.
  Kotlin compilation and full suites pass: 2,734 pure / 900 mock (4m03s).
- [x] Pending lint fix committed as `2c1583c7`; #145 test as `a53a24a3`; no push.
- [x] Fresh minified APK built with release lint, R8 and resource shrinking (5m25s),
  signature/ZIP integrity/ARM64 ONNX library verified; no install or device changes.
  Artifact: `build/outputs/apk/release/CleverKeys-v2.0.0-arm64-v8a.apk`.
  SHA-256: `12d29fd7d8177946eb755c90fbccbc30e6e3173f3be7366da72a43ec7a23bb50`.
  Manual #145 check: both prediction toggles off, restart keyboard, custom short swipe
  works immediately; repeat after changing theme. Minimize checks: keyboard-minimize spec.
- [ ] TODO: #90 custom bottom-row-free layout height fix; local implementation awaiting
  permission after automatic review rejected the repository-required external PAL consultation.
- [ ] TODO: continue #181 glyph filtering, #175 clipboard bulk delete/#168 clear key,
  #188 compose repro, #186/#61 layout language, and #184 oversized-pack guard.
- [ ] TODO: maintainer manually tests the issue fixes and both minimize styles before 2.0.

## September 27 follow-through

- [x] Prior 54 commits through `79f0b464` pushed; commit-specific CI, site deployment,
  APK build and UI/performance workflows succeeded.
- [x] Existing `langpacks` release updated: Norvig asset removed; 22 replacements and
  description published; all 22 downloaded SHA-256 hashes verified.
- [x] Local Astro build: 84 pages pass under Bun using the current `bin/astro.mjs` entry.
  Legacy wiki HTML URLs are deployment-generated redirects, not stale published bodies.
- [x] Site TypeScript check and `build:termux` pass; Android Rollup is optional and the
  lockfile is synchronized. No dependency versions changed.
- [x] Builder now rejects unsupported `--lang` before any reads/writes; mypy passes after
  correcting fractional-count annotations/callback typing. CKLM encoding matches the previous
  implementation across all six corpus-selection weights on a controlled fixture.
- [x] Final local gates: Kotlin compilation, Android lint, 2,610 pure tests and 859 mock
  tests pass (guarded run 7m51s). Site build: 84 pages, 104 affected-page links resolve;
  TypeScript and Python mypy pass. Saga connected read-only; no app/settings changes.
- [x] Translation structural audit: 936/936 resources in all 21 locales; indexed arguments
  and plural items match. Expanded translation guard passes 6/6 focused tests.
- [x] Multilingual LM pilot (2026-09-29): per-language builder configs (en byte-identical),
  language-parameterised S1 eval + drift test. Spanish FAILED S1 (prefix-1 +4.69 < +5), so
  nothing beyond `en` ships; de/fr/it measured passing, pt/sv failing —
  `docs/eval/2026-09-29-static-lm-multilingual.md`.
- [x] Static LM contraction-lookup fix + per-language shipping (2026-09-29): REPLACE keys
  (`dont`, `cest`) now resolve to the display form the model names (was backoff only — en
  `i → dont` 0.51 vs `i → don't` 27.5). One re-evaluation, unchanged gates: de +5.16, fr +6.75,
  it +7.48 pt prefix-1 → SHIPPED (with Tatoeba contributor lists, NOTICE, PROVENANCE);
  es/pt/sv still fail, unshipped. `docs/eval/2026-09-29-static-lm-multilingual.md`.
- [x] Next-word allow check admits contraction display forms through their apostrophe-free
  dictionary key (`NextWordContractionAllowTest`, 2026-09-29).
- [x] es/pt/sv LM retry (2026-09-29, pre-registered, dev-selected second Leipzig corpus + Tatoeba
  weight, one test look): pt +5.41 and sv +5.98 prefix-1 → SHIPPED; es +4.96 → FAILED, unshipped.
- [x] Legacy hardcoded tables no longer penalise unlisted pairs or apply English's tables to other
  languages (es `static_only` prefix-1 −22.65 → +0.04 pt). `docs/eval/2026-09-29-static-lm-multilingual.md`.
- [ ] TODO: es LM needs a NEW stated reason before another attempt (e.g. a larger test population).
- [x] LM ratio shape measured (2026-09-29, pre-registered): dev rule kept `RAW` (FLOOR_ONE −0.15
  en prefix-1 on dev); test read once, FLOOR_ONE ahead there (+0.46 mean) — recorded only.
  `both` never applies a static penalty (learned boost ≥ 1). `docs/eval/2026-09-29-static-lm-multilingual.md`.
- [ ] TODO: FLOOR_ONE for `static_only` — needs a fresh pre-registration with new dev evidence.
- [x] Pack-attribution UI: Settings → Multi-Language → Language Packs → Manage shows each pack's
  licence, credit, source links and full NOTICE.txt (`269d8bb1`/`6fed1b12`, 2026-09-29).
- [x] `privacy_forget_learned_body` names swipe corrections in all 22 locales (`535a2a28`);
  terminology unified per locale with `TranslationGlossaryTest` (`4871c7e5`, `a91aad5a`).
- [ ] TODO: native-speaker translation review and device visual verification remain distinct
  from automated structural checks; preserve the maintainer's manual-checklist edits.
- [x] i18n follow-ups closed (2026-09-30): FAQ content `721c757d`, RTL pane arrows `213e8d52`,
  localized I/O failure reasons `cfc0eed2`, command catalog in 21 locales `19d64857`, localized
  settings search + id-keyed scroll `607da6df`. `docs/i18n/2026-09-29-hardcoded-ui-sweep.md`.
- [ ] TODO: fa/hu device check of the above (pane arrows, search scroll) and native review of the
  465 command-catalog strings.

## Maintainer/release gates

- [ ] ARC-053: soak the minified release APK; ARC-062/096 implementation is already present.
- [ ] ARC-054: decide whether v1.6 release notes announce ru and synthesis-holdout-only el.
- [ ] ARC-063: narrow blanket R8 keeps only after the first minified soak.
- [ ] Decide any nonzero `finger_occlusion_offset` default only from device-trace A/B evidence.

## Agent-executable backlog

- [ ] ARC-067: translate the common 384 missing resources into all 21 locale files. Preserve
  placeholders and plurals shapes; do not use English copies. ARC-066/087 are complete.
- [x] Finish Wave E: ARC-073 citation/doc drift (`d20ed3b5`), ARC-098 phantom-`keyboard2`
  tooling sweep (`f482faf4`), the four verified doc-claim repairs, and the
  `contraction_pairings_cleaned.json` gate run (the file was already deleted in `030265ee`).
  ARC-076 and ARC-089 are complete. ARC-098's source-tree half (`gesture/`,
  Bridges/Initializers→`wiring/`) remains under ARC-072 slice 3 below.
- [ ] ARC-072 slice 3 composition-root/reorg work, folded with the gesture portion of ARC-098.
- [ ] ARC-027/028/029 geometric experiments, evidence-gated on non-regressing corpus replay.
- [ ] ARC-071 migration is superseded by the installed Astro 7 site; reconcile the remaining
  ARC-046 web regression gate/Tailwind vendoring evidence.
- [ ] ML-side ARC-060/061 and the documented verb-inversion feasibility work. (ARC-056
  uk/bg/mk/he lexicons/langpacks CLOSED 2026-09-01 — `538a1633`/`86156ea3`.)
- [ ] ARC-044 remaining assertion-strengthening batch (no Truth dependency in androidTest).

## Verification backlog

- [x] Final guarded host gates on implementation commit `5fb58037`: `runPureTests` 2,087 and
  `runMockTests` 343, both passing on 2026-09-01.
- [ ] Wave J: full ew-cli instrumented run, including ARC-058/064/074/077/091/092/095.
- [ ] Wave K: both authorized phones per the campaign protocol; restore IME/properties and
  never framework-restart Saga. Capture ARC-068/069/070 evidence.
- [ ] Wave L remainder: update the ARC ledger and maintainer-input report. HANDOFF, backlog, and
  campaign-plan state were consolidated on 2026-09-01.

## Release authority

Do not commit, tag, push, publish, or open external issues without explicit user authorization.
