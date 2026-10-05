# Current work queue

Updated: 2026-10-05. Full execution state and test evidence: [HANDOFF.md](HANDOFF.md).
Campaign plan: [`docs/plans/2026-08-30-full-backlog-campaign.md`](../docs/plans/2026-08-30-full-backlog-campaign.md).

The September 1 campaign baseline was `5fb58037`; subsequent work through `79f0b464`
was pushed with maintainer authorization on September 27. Preserve shared-tree work.

## October 2–3 issue work before 2.0

The maintainer wants GitHub feature/bug work and personal testing before release.
Do not tag, bump, push, publish, or close issues as part of this round. Preserve the
maintainer-owned uncommitted manual-checklist resets.

- [x] Recovered the pending SubkeyAssignActivity lint fix. The prior guarded lint run
  finished successfully in 50m16s (0 errors, 210 warnings), after the source edit.
- [x] #145 cold-start regression pin: real service-handle assignment works with
  prediction and swipe typing both off, including replacement views, without model load.
  Kotlin compilation and full suites pass: 2,734 pure / 900 mock (4m03s).
- [x] Pending lint fix committed as `2c1583c7`; #145 test as `a53a24a3`; no push.
- [x] Fresh minified #90 APK built with release lint, R8 and resource shrinking;
  interrupted build resumed successfully (1m24s). Signature, ZIP integrity, ARM64 ONNX
  library and exact packaged numeric/PIN XML bytes verified.
  Artifact: `build/outputs/apk/release/CleverKeys-v2.0.0-arm64-v8a.apk`.
  SHA-256: `9014e7ee7e0c9a52fdd71839eb9ac30a2fba839958df38d71262c974c09acd9d`.
  Installed with `adb install -r` on Seeker `192.168.0.170:5555` (2026-10-03 UTC);
  installed base.apk hash matches. Original CleverKeys IME and launcher focus preserved.
  Manual #145 check: both prediction toggles off, restart keyboard, custom short swipe
  works immediately; repeat after changing theme. Minimize checks: keyboard-minimize spec.
- [x] User/project PAL policy updated: Gemini 3.8 (`gemini-3.8-flash`), never 3.1. Maintainer
  approved consultation and local continuation if PAL fails (2026-10-02).
- [x] #90 fixed with explicit `numpad_height` XML metadata (default false), copied
  through transformations; numeric/PIN opt in. Gemini 3.8 reviewed the approach.
  Two fail-first tests reproduced the bug (400px vs 101.27px single-row unit).
  Focused geometry/parser tests 12/12; Kotlin compilation, 2,729 pure / 912 mock pass
  (7m48s). Five existing numpad tests moved from pure to mock; seven new tests added.
- [x] #90 committed as `ba5bbee6`; refreshed and verified minified test APK installed.
- [ ] TODO: maintainer checks a compact bottom-row-free layout with Scale Numpad Height
  enabled, numeric/PIN with scaling on/off, and an explicitly opted-in custom numpad.
- [ ] TODO: device-check #181 glyph filtering and #184 oversized-pack guard; continue
  #175 clipboard bulk delete/#168 clear key, #188 compose repro and #186/#61 layout language.
- [ ] TODO: maintainer manually tests the issue fixes and both minimize styles before 2.0.

## October 3 device pass and “wet” report

- [x] #181 emoji glyph filtering implemented for category/recent/search grids, with
  actual themed cell paint, per-grid cache and separate text-face handling. Recent
  records are preserved. Focused 7/7, Kotlin compilation, 2,733 pure / 912 mock pass
  (5m34s). Refreshed minified APK now builds/verifies (see #184 below);
  TODO: device check. Monet half remains open.
- [x] Reconnected Seeker reproduced live `wet`→`We`. Popover hold/cancel, empty-slot
  assignment, saved-action dwell-to-edit, FAB minimize/expand and bar minimize/resize
  verified. Bar expansion and app interaction coverage still pending.
- [x] Full-dictionary geometric comparison: clean canonical `wet/we/tree/get/yet/pet/git`
  all rank 1, `hello` rank 2. One valid human `wet` trace ranks 1 vs CTC rank 6.
  Alternate engine is a promising trial, not a broad measured human
  accuracy; CTC code/ranking remains unchanged. Evidence in issue audit and ignored
  `build/wet-geo-probe.log`.
- [ ] TODO: Seeker dropped Wi-Fi again after the above checks. Restore temporary
  popover enable (original false) and delete test `t`/South mapping (original empty),
  clear only launcher test text, then restore Android launcher focus. No other settings
  were changed; no reboot/data clear. Fresh-install/reset checks require separate approval.
- [x] Bangla support path documented in `docs/guides/adding-a-new-language.md`: National
  and Provat tap layouts exist, no published `bn` pack/model. Dictionary building strips
  meaningful Mn signs and the geometric letter-node/projection policy excludes essential
  marks. Stage 1 preserves spelling and adds licensed dictionary/tap fixtures; stage 2
  defines/test mark/conjunct geometry; stage 3 is trained/validated CTC. Nothing advertised
  as implemented prediction/swipe support.

- [x] Read-only GitHub refresh: still 63 open issues; no posts/closures/pushes.
- [x] Reproduced `wet`→`we` locally through the real shipped CTC model/trie: rank 7 on
  six canonical variants; rank 6 on one valid existing corpus trace (one other trace
  rejected for nonmonotonic timestamps). Endpoint dwell improves rank to 3 but never 1.
  `tree` ranks 3–4 on synthetic controls; needs a real trace before calling it a bug.
  Score decomposition and limitations: `docs/audit/gh-issue-resolution.md`.
- [ ] TODO: full Seeker manual pass remains blocked. Initial attempt dropped Wi-Fi at
  field focus with no changes; the subsequent partial pass above did verify minimize/
  popover paths and left two temporary changes pending restoration after another drop.
- [ ] TODO: on reconnect inspect focus, remove `/sdcard/cleverkeys-test-ui.xml`, return
  to original Android SearchLauncher (task 860), then resume the six test groups in the audit.
- [ ] TODO: investigate `wet` final-letter emissions/resampling using maintainer playground
  traces and compare geometric decoding; protect `we` and broad short-word accuracy.
  No production recognition fix made; do not promise usage-learning or endpoint dwell fixes it.

## October 3 oversized-pack follow-through

- [x] #184 guard implemented: shared 100,000 canonical words / 16 MiB CKDT policy;
  import checks the actual header, independent of manifest metadata. Tap/geometric/CTC
  refuse older oversized files before word-array allocation. Normalized count cannot
  exceed canonical count. No vocabulary is silently truncated.
- [x] ZIP extraction bounds all members, aggregate 64 MiB / 64 entries, manifest/NOTICE
  64 KiB and model 8 MiB; rejects unsafe paths and duplicate flattened filenames.
  Refusals preserve previous installation and clean scratch. New messages translated
  in all 22 resource locales.
- [x] Final Kotlin compilation and full suites: 2,737 pure / 919 mock pass (6m43s),
  `build/issue-184-final-tests.log`. The old oversized-NOTICE test expected import
  success; updated to test new refusal plus legacy read truncation. Added exact-count,
  misleading-manifest, byte, update rollback, duplicate, entry-count and aggregate guards.
- [x] #184 committed `af742286`; refreshed minified APK builds (9m23s), including
  lint-vital, R8 and resource shrinking. ARM64 APK signature v2, alignment, ZIP CRC,
  both ARM64 ELF libraries, embedded guard strings and numeric/PIN source hashes pass.
  SHA-256 `a356ac06a3ca6a4fde204f92a28c76bb2aeea653cf0acc641d2e51bf8e5bc542`.
  Logs: `build/issue-184-release.log`, `build/issue-184-artifact-verification.log`.
- [ ] TODO: install this APK on Seeker after restoring test settings, then verify #181/#184.
  Local path: `build/outputs/apk/release/CleverKeys-v2.0.0-arm64-v8a.apk`.
  Last installed Seeker APK still predates these two fixes. No push/tag/release.
- [ ] TODO: Seeker device checks remain blocked by repeated No route to host (final bounded retry also failed).
  The last successful install contains #90/minimize, not the new #181/#184 fixes.
  Restore temporary popover true to original false, remove test t/South mapping, clear
  launcher test text and scratch UI XML, then return to original Android launcher.
- [ ] TODO: finish bar expansion, app scrolling around FAB, RTL/hide-show, compact and
  numeric/PIN height, cold-start custom gestures with both swipe/prediction disabled,
  emoji/category/search/recents + Monet day/night, compose, and main release-check groups.
- [x] Optional PAL source-file review was blocked by automatic approval review (external
  transfer not specifically authorized); continued local review/tests. No source files
  exported through that rejected call. Earlier abstract review/translations used Gemini 3.8.

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

## October 5 reconnected-device follow-through

- [x] Seeker `.170` reconnected. Removed only temporary `t`/South mapping and restored
  popover to false; the other two mappings remain intact. Installed the verified
  `af742286` minified APK with data preserved; installed SHA matches `a356ac06…`.
- [x] #181: sampled smiley and text-emoticon grids render; actual keyboard input routes
  `face` into emoji search and renders mixed results. No recent items were added.
  TODO: force an unsupported sequence/font and check Monet live day/night changes.
- [x] #184: Settings import refuses a CKDT header declaring 1,000,000 words despite
  manifest wordCount=1, displays the smaller-pack message, and retains installed count 0.
  Removed the temporary ZIP and restored multi-language to false. Existing-pack rollback
  is host-tested; this device has no installed packs to exercise that scenario.
- [x] #188 reproduced: Compose activates visually, then `e` commits immediately. The
  deferred navigation-key tap branch emits key-up and clears the newly pending state.
  Fail-first pointer regression reproduces that premature commit (8 tests, 1 failure).
  Fix routes the tap through shared latch/unlatch handling; navigation swipes retain their
  separate handling. Second regression protects exactly-once ordinary key dispatch.
- [x] #188 Kotlin compilation and full suites pass: 2,737 pure / 921 mock (5m12s),
  `build/issue-188-tests.log`. Two new pointer tests included.
- [x] #188 committed `97d6c25f`; minified build/lint-vital pass (5m25s), signature,
  alignment and ZIP CRC verified. SHA-256 `798137106bd92b39c15280119c511d123f9a39f321b953b0efd1e2eb4cafdd23`.
- [x] Bar minimize/expand, typing after expand and full-size restoration after hide pass.
  Used a new temporary t/South mapping (Mini/minimize_bar); two user mappings preserved.
- [x] Subsequent reconnection: #188 APK installed and Compose both orders/cancel/arrows
  passed. Temporary minimize-bar t/South mapping removed; two user mappings preserved.
- [x] FAB portrait/landscape minimize/expand and landscape scroll through transparent
  strip pass on launcher. RTL and cross-app checks remain pending.

- [x] Final cleanup: launcher test field empty, scratch UI XML removed, HOME and
  original expanded Quick Settings restored (NotificationShade focus).

## October 5 roadmap and apostrophe follow-through

- [x] Installed `97d6c25f` minified APK on reconnected Seeker; installed SHA matches
  `798137106bd92b39c15280119c511d123f9a39f321b953b0efd1e2eb4cafdd23`.
  Actual Compose e/apostrophe and apostrophe/e both produce é with no intermediate
  raw text. Compose cancellation resumes plain e; arrow-left then e gives Caet.
- [x] Removed temporary t/South minimize_bar mapping; confirmed original two user
  mappings remain. Launcher test field was cleared before the next ad diagnostic.
- [x] Astra reconciled apostrophe proposal against letter-only CTC + post-decoder overlay.
  Implemented single-character custom ASCII/curly apostrophe routing through ordinary
  key handling, preserving smart punctuation, inline search, typing bookkeeping/haptic.
  Focused 15/15 pass after 3 expected behavioral failures. Multi-character macros,
  including literal apostrophe-s, keep their existing behavior. No suffix command claimed.
- [x] Roadmap low-hanging terminal pipeline: SuggestionHandler now shares TerminalUtils
  detection with paste for correction, deletion and prediction guards. Focused real-handler
  tests 8/8 pass: five SSH/AVF/Termux-Nix packages use Ctrl+W; terminal partial replacement
  uses backspace events; ordinary editor uses document deletion. Custom package UI pending.
- [x] ad exists in lexicon (frequency 199), yet six canonical variants rank it 2 behind as;
  Seeker confirms ad→As. Existing wet still rank 7 synthetically, rank 6 on the one usable
  human trace. These are recognition defects; users must not switch engines per word.
  Endpoint-only rescoring cannot separate wet from wt (same final key); no unvalidated
  ranking/model change shipped. TODO: held-out human short-word/model calibration.
- [x] Combined Kotlin compilation and full suites pass: 2,737 pure / 933 mock (4m22s),
  `build/oct5-terminal-apostrophe-tests.log`.
- [x] Committed input fixes `5f07936e`; minified release build passes (5m23s). Signature,
  alignment and ZIP CRC verified; ARM64 SHA-256
  `00e80393a4d130bc68cf47832ca0e106f9f7c11ac44106e62d9d8d7ce12d22f9`.
- [x] Fresh `5f07936e` minified APK installed; on-device SHA matches. Actual custom
  apostrophe produces `As'` without auto-space and reaches emoji search. Removed that
  mapping, then temporarily assigned t/South minimize_fab for window checks.
- [x] FAB portrait/landscape minimize/expand pass; landscape swipe starting in transparent
  strip scrolls launcher content while FAB remains. Screenshots `build/oct5-fab-*.jpg`.
  Restored rotation (accelerometer=0, user_rotation=0) and app locales []. Hebrew app
  locale did not create RTL IME; do not claim RTL pass.
- [x] Reconnected cleanup complete: deleted ONLY t/South minimize_fab and confirmed
  two original user mappings remain. Launcher test field is empty; original IME,
  rotation 0/0 and app locales [] verified. Removed scratch UI XML; HOME then
  expanded Quick Settings, confirmed NotificationShade focus. External-terminal
  editing remains pending; no terminal commands executed.
- [ ] TODO: explicit Append apostrophe-s / Append apostrophe commands need verified
  immediate-word attachment plus suffix-only undo. Contraction projection selection is a
  separate action; do not guess plural vs possessive from a final s or boost absent beam paths.

- [x] Astra proposes general short-word encoder correction; bounded candidate geometry
  fixes ad but wet/wt collinear templates tie. Exploratory equal-duration timing fixes
  19 synthetic cases + one human wet, but matches the generator and is unvalidated.
  Existing roadmap records architecture, prior ML Phase K/I limits and held-out gates.
- [x] Frozen human screen REJECTS heuristics: 100 traces / 92 words, baseline93 correct,
  geometry83 (1 gain/11 losses), timing61 (1/33). Short20:17→14→11; long80:76→69→50.
  Zero shipping-baseline disagreements; no target/control words in sample. Corpus is
  repeatedly inspected, not held-out; no production ranking/model changes shipped.
- [ ] TODO: fresh writer/session-separated human data, general-strata encoder training
  and protected vocabulary/layout evaluation; do not keep tuning on this screen.

- [x] Gemini 3.8 PAL architecture critique cross-checked against actual time-uniform
  featurizer and length/frequency-aware Viterbi decoder. Add development calibration
  arm beside encoder training; consider teacher anchoring on actual 32-frame outputs.
  PAL's incorrect frame assumption/numerical prescriptions were not adopted.

## Clipboard size filter and confirmed batch deletion

- [x] Implemented inclusive min/max payload-size presets, row sizes, full-result
  count/size and current-tab Delete results with mandatory frozen-snapshot confirmation.
  Search/date/tag/privacy/status predicates combine; copies and OS clipboard remain
  independent. Exact row-version transaction guards and shared-media cleanup added.
- [x] Kotlin/resource compilation, debug app/test APK builds and full host suites pass:
  2,739 pure / 938 mock (`build/clipboard-bulk-tests.log`).
- [x] All four real SQLite tests pass on isolated Pixel7/API34, including a 205-row
  batch, changed/new-row protection, tab isolation/shared media and transaction rollback.
  emulator.wtf run `dcec77b5-0337-4d45-987b-0ad30676bc37`; 4 tests, zero skips/failures.
- [x] Minified release build and release lint pass (26m39s); ARM64 signature,
  alignment and ZIP integrity verified. SHA-256
  `06287570847b841cd7033a629260487a885557ebe9b2e1df1191473ad4053264`.
- [ ] TODO: install and verify device dialog, cancel,
  range validation and synthetic-only deletion tests. Seeker `.170`
  currently returns "No route to host"; no device state or personal clips changed.
