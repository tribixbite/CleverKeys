# Current work queue

Updated: 2026-10-08. HEAD `b9ce12e6` is pushed and equals origin/main. Pushes to origin/main
are authorized (2026-10-07) and in use; tag, version bump, release, publish and GitHub posts
still need explicit authorization. Reference state and rules: [HANDOFF.md](HANDOFF.md).
Campaign plan: [`docs/plans/2026-08-30-full-backlog-campaign.md`](../docs/plans/2026-08-30-full-backlog-campaign.md).
Issue tracker: [`docs/audit/gh-issue-resolution.md`](../docs/audit/gh-issue-resolution.md).

The "Open now" section is the only authoritative task list. Everything after it is dated history,
kept for evidence; its open boxes were reconciled on 2026-10-08 against git history, test files,
`docs/eval/`, `~/ew-output/` and the device-evidence dirs `build/oct7-*/`, `build/oct8-*/`
(screenshots, not in git). A history item that is still open says "→ Open now N" instead of
carrying its own box, so duplicates are counted once.

## Open now (2026-10-08)

Owners: **device** = needs a test phone (Saga; Seeker 192.168.0.170 keeps dropping Wi-Fi) ·
**maintainer** = the maintainer's hands or decision · **agent** = doable from this box ·
**RTX** = needs the ML training box.

1. [ ] device (Seeker): Termux drift re-test of `e4d50db4`/`99f4113e` (stale suggestions
   after the line is cleared; non-text keys end the tracked word). Not run: Seeker off Wi-Fi.
2. [ ] device: #186/#61 Persian half — custom layout bound to `fa` with the fa pack imported
   ("Language: Persian" bar, Persian typo autocorrected, Latin restores EN), en/de/fr
   three-layout cycle, uninstalled-language warning. Saga Latin→German PASS 2026-10-07.
3. [ ] device: add `adb`/`somethings` to the personal dictionary and swipe them
   (`docs/eval/2026-10-07-final-letter-drops.md` §8). `adb` part done 2026-10-10 (Seeker):
   stored at the legacy 100 it lost to `an` at Highest; raised to 255 it ranked first
   (`docs/eval/2026-10-08-user-swipe-priority.md` §9). Still open: `somethings`, and the
   follow-up device check of the legacy-frequency offer + dialog lift (eval §9 "Device check").
4. [ ] device: clipboard Select/⋮ with TalkBack; #181 glyph filter on a device WITHOUT a
   font provider; #184 oversized UPDATE of an already-installed pack.
5. [ ] device: leftovers of the plan's "Maintainer device checks" — continuous swipe with
   auto-space OFF and interrupted phrases, "." tapped right after lift, templates in
   password/inline editors and media-only clipboard, enlarged text + narrow split-screen
   clipboard pane, fa/hu pane arrows + settings-search scroll.
6. [ ] maintainer: ARC-053 daily-use soak of the minified release → ARC-063 narrow the
   blanket R8 keeps → tag decision.
7. [ ] maintainer: pre-tag checks — #90 numpad-height cases, #168 `clear_clipboard` on a
   disposable clip, #145 cold start, both minimize styles, suffix/template/continuous feel.
8. [ ] maintainer: ONE native-speaker review of every string added since 2026-09-29 in
   21 locales (command catalogue 465, popover 18, clipboard selection/bulk 25, clear-clipboard
   4, #184 refusal, terminal packages, language binding, Oct 7 dialog strings, Oct 10
   legacy-frequency offer + priority lift 9).
9. [ ] maintainer decisions: es LM needs a NEW stated reason; `FLOOR_ONE` for `static_only`
   needs a fresh pre-registration; nonzero `finger_occlusion_offset` only from device-trace
   A/B; keep or uninstall CleverKeys on the Saga (installed, enabled, not default).
10. [ ] maintainer: GitHub — 38 DONE/FIXED close candidates + 2 NOT-REPRO/BY-DESIGN, PRs
    #189/#183/#182 review and #164 close as superseded (`docs/audit/gh-issue-resolution.md`).
11. [ ] agent: language-pack final swap deletes the old directory before `renameTo`
    (`LanguagePackManager.kt:304-311`) — add recovery + fault-injection coverage.
12. [ ] agent: deferred review items — unify `Keyboard2View.executeEditingCommand` with
    `KeyEventHandler.handleEditingKey`; suffix receipt readback at command time (3 editor
    reads per committed word, # TODO in `rememberVerifiedWord`); #175 clipboard
    swipe-to-delete; summon after system hide (overlay permission).
13. [ ] agent: intermittent mock-suite timing failures under host load 12–15 (predictor
    latency 17.4 vs 15 ms; adaptation write past 12 s) — cause not established.
14. [ ] RTX: short-word CTC — word-balanced fine-tune on the raw-path input contract so
    dwell-less `ad`/`wet` decode, gated on fresh writer/session-separated human traces
    (`docs/eval/2026-10-07-short-word-ctc.md`, `…final-letter-drops.md` §6).
15. [ ] RTX: apostrophe context chooser — listed-evidence its/it's variant in its own
    pre-registered round on fresh data (`docs/eval/2026-10-07-apostrophe-context.md`).
16. [ ] RTX: Bangla spelling-preserving dictionary + mark/conjunct geometry, then a validated
    model/pack (`docs/guides/adding-a-new-language.md`); ARC-061 `ctc_golden` fixture fix.
17. [ ] unowned roadmap: 1.3 at-rest clipboard encryption, 2.3 dwell picker (measure first),
    4.1 live theme preview, 4.2 spline swipe trail (benchmark first), #187 below-accents,
    #175(3)/#80(3) toolbar.

## Device results recorded 2026-10-07/08

- Saga, release `991651d1`: clipboard persistent selection A1–A7 PASS (`build/oct7-saga3/`);
  FAB left in RTL with system `ar-XB` and app `fa` PASS (`c6fe3594`); theme change shows the
  new keyboard, Monet follows dark mode PASS (`56f3ea90`, `build/oct7-final/T1_*`); layout
  switch → German-bound layout PASS (`f85c8dcd`, `build/oct7-final/T4_*`); `ad` with a final
  stop PASS (`745d1ca3`); `lets`/`team's` offers PASS (`build/oct7-final/T7_*`).
- Seeker, Termux, `78052e86`: tapped suggestion replaces the typed word PASS
  (`4939633b`, `build/oct7-seeker/`); clipboard delete 1/1 PASS (`build/oct7-seeker3/`).
- Seeker cleanup owed from the review round DONE 2026-10-07 10:19–10:25: popover toggle OFF,
  "2 custom mappings" (temporary K→North "Test" removed), extra Chrome tab closed
  (`build/oct7-seeker3/oct7_25_after_close.png`, `oct7_44_perkey5.png`, `oct7_49/50_*.png`).
- Saga, `b9ce12e6` (2026-10-08): swipe-start A1–A4 PASS (backspace/space/digits no longer
  start word swipes; the period case is untestable on that layout); clipboard catch-up
  B1–B3 PASS (`build/oct8-saga/ck-a*`, `ck-b*`). The original 2026-10-07 Chrome-toolbar-copy
  miss is not reproduced; its cause stays unconfirmed (logcat rotated).
- Seeker, `99f4113e` Termux drift test: NOT run (Seeker drops Wi-Fi) → Open now 1.
- Native (ew-cli, Pixel7/API34) full runs with zero failures/errors/skips/flakes on
  `24afd33c` (1,590), `78052e86` (1,590) and `991651d1` (1,591;
  `~/ew-output/oct7-wave4-991651d1/results.xml`). The 991651d1 run executed
  `ClipboardFilterDialogTest#selectionSurvivesPaneRebuildAndBulkActionsCompleteOnTheDatabase`,
  `ClipboardPaneTintTest#selectionModeKeepsEntryViewportCountAndActionTargetsInLandscape`,
  `ClipboardDatabaseTest#selectionResolvedAfterReloadDeletesUnchangedRowsByIdentityAndKeepsCopies`,
  `SmartAutoSpaceTest` (75), `ContinuousSwipeTest` (20) and `DynamicTemplateTest` (26).

---

# History (reconciled 2026-10-08; boxes below are closed or point at "Open now")

## October 8: user swipe priority (frequency-setting fix for hard swipe words)

Maintainer request: a frequency-setting-based fix for `ad`/`wet`/`adb`/`somethings`.
Eval: `docs/eval/2026-10-08-user-swipe-priority.md`.

- [x] Measured: per-word frequency cannot raise (255 is the cap); needed bonus per target and
  collateral per level (real dev/held/focus traces + synthetic shapes, distinct counts).
- [x] Normal/High/Highest swipe priority for custom words (CTC +2.0/+4.0 final-score nats,
  geometric +2.0/+4.0 on pruner survivors), Dictionary Manager picker + row label, backup
  round trip, offer raises a Normal user word to High; tap prediction untouched.
- [ ] Device check (eval note §8): `adb` Highest + `wet` High swipes, then `an`/`we`/`as`
  collateral; restore Normal afterwards.
- [ ] TODO: native-speaker review of the 7 new strings in 21 locales.

## October 7 clipboard selection: persistence, bulk actions, dialog focus fix

Maintainer request (verbatim): "fix the rotation reset (bug imo) and make selected entries
persistent until tap deselect all/cancel or complete action (delete or add to todo or
pinned). also make a merge button that merges and clean button that removes trailing spaces
and inserted newlines." Spec: `docs/specs/clipboard-system.md` "Persistent selection, batch
deletion and bulk actions".

- [x] Selection moved to the service-scoped `ClipboardSelectionHolder`: survives pane
  close/reopen, pane switch, keyboard hide, rotation, field/app switch and theme rebuild;
  reopening returns to its tab. Ends only on Exit, a completed action, or its tab disabled.
- [x] ⋮ More actions: Add to Pinned / Add to Todos (one transaction, COPY), Merge (oldest
  first, one per line, size limit, privacy kept), Clean (`ClipboardTextCleaner`, in place).
- [x] Device report (Saga/Chrome): a focusable IME dialog let Chrome hide the keyboard
  mid-tap, so Delete selected deleted nothing and the filter dialog closed itself. IME
  dialogs are now non-focusable (`ImeDialogWindowPolicy`, `ImeDialogSpinner`) and confirmed
  actions run on the holder's scope, independent of the view.
- [x] Device check on the Saga with disposable clippings: Delete selected and the filter
  dialog in Chrome, rotation keeps the selection, Merge/Clean previews — A1–A7 PASS on
  `991651d1`, 2026-10-07 (`build/oct7-saga3/`). TalkBack result line not run → Open now 4.
- [x] Native run of `ClipboardFilterDialogTest#selectionSurvivesPaneRebuildAndBulkActionsCompleteOnTheDatabase`
  and the updated `ClipboardPaneTintTest` landscape case: executed in the 991651d1 full run
  (1,591 tests, 0 failures; `~/ew-output/oct7-wave4-991651d1/results.xml`, 2026-10-07).
- Native-speaker review of the 25 new strings in 21 locales → Open now 8.

## October 7 afternoon: requested features, CI, device verification

All pushed to origin/main (push authorized 2026-10-07). Device-verified on Saga (991651d1)
and Seeker (Termux) unless noted.

- [x] Clipboard: persistent selection (survives rotation/pane close/keyboard hide/app
  switch; ends on Exit, deselect-all or a completed action), select/deselect all matching
  across pages, Delete selected, ⋮ Add to Pinned / Add to Todos / Merge (oldest first,
  newline-joined, originals kept) / Clean (trailing spaces + in-paragraph line breaks;
  lists/code/URLs kept). IME dialogs are non-focusable (the app had been hiding the keyboard
  and nothing was deleted). Saga A1–A7 PASS; Seeker delete 1/1 PASS.
- [x] Language per layout (#186/#61) `f85c8dcd`; Saga layout switch → German PASS.
  Persian half → Open now 2.
- [x] CTC raw path + lift sample `745d1ca3`: held-out 91.83→92.12; `ad` with a stop PASS on Saga.
  ad/wet without a stop and wet need model retraining — recipe in docs/eval/2026-10-07-short-word-ctc.md
  → Open now 14.
- [x] Final-letter drops (`adb`→`an`, `somethings`→`something`) measured: no decoder defect
  (0 of 37 real prefix drops overturned an encoder preference; no (γ,β,λ) passes dev).
  `adb` = not in lexicon + encoder silence on `d`; `somethings` = λ-prior near-tie, remedy is
  the personal dictionary. Recipe additions in docs/eval/2026-10-07-final-letter-drops.md §6.
- Device: add `adb` / `somethings` to the personal dictionary and swipe (note §8) → Open now 3.
- [x] Apostrophe: no i's/a's/closed-class possessives; `lets` PAIRED. Context chooser failed
  its bars twice (not wired); a listed-evidence its/it's variant needs its own pre-registered round
  → Open now 15.
- [x] Theme change shows the new keyboard (Monet follows dark mode) PASS; Termux tapped
  suggestion replaces PASS (Seeker, `78052e86`); FAB left in RTL (system ar-XB and app fa) PASS.
- [x] CI: Security Scan fixed (site devalue/source-map-js overrides); lint fixed (6fd4fef4).
- [x] Clipboard: a clipping copied via Chrome's toolbar Copy once did not reach history on the
  Saga (keyboard Ctrl+C always did). Fixed 2026-10-08: the platform does deliver clip changes
  to the default IME while its keyboard is hidden (AOSP 13/14 ClipboardService), so the loss
  needs a window with NO registered listener (process reaped/restarting, registration that
  bailed at onCreate and was never retried); the only other read was at registration. Now
  onStartInputView re-registers or catches up once, recording only an unobserved clip
  (ClipboardCatchUpTest). The single Saga miss is not reproduced; root cause of that instance
  unconfirmed (logcat rotated). Saga re-test on `b9ce12e6`: B1–B3 PASS 2026-10-08
  (`build/oct8-saga/ck-b*`).
- [x] A swipe starting on the backspace key (left) was read as swipe-typing ("mb"). Fixed
  2026-10-08: the move-time latch (Pointers) never checked the start key, and Backspace collects
  its path as a short-gesture key; word swipes now start only on letter keys
  (KeyLetter.startsWordSwipe), continuous phrases too (PointersSwipeStartKeyTest). Saga
  re-test on `b9ce12e6`: A1–A4 PASS 2026-10-08, period case untestable on that layout
  (`build/oct8-saga/ck-a*`).
- [x] Termux: suggestion bar keeps stale words after the line is cleared (no readable buffer).
  Fixed 2026-10-07: non-text keys (Enter/Tab/Esc/arrows/Ctrl chords/IME action/sliders/editing
  commands) end the tracked word in terminals; stale predictions are dropped; swipe final
  autocorrect skips terminals (TerminalTypedWordTrackingTest). Seeker device re-test NOT run
  (Seeker off Wi-Fi) → Open now 1.
- Native-speaker review of the new strings (21 locales) → Open now 8.

## October 7 review round (independent review + fixes + device re-test)

Three read-only reviews (typing features, issue fixes, release docs) and two device passes.
Commits were local at the time; all were pushed to origin/main on 2026-10-07.

- [x] `17db49da`: removed the no-op Terminal Mode switch (DEPRECATED_KEYS tombstone +
  DeadPlumbingDriftTest); #181 filter consults EmojiCompat (emoji2 now a direct dep);
  #184 Settings names why an installed >100k pack is not loaded; #145 call-site pin;
  `hide_keyboard` command (#175).
- [x] `27b94fca`: v2.0 fastlane/RELEASE_NOTES (no longer "opt-in next-word"), 22 GUARDED
  RELEASE_RECORD rows, CHANGELOG fold, README/TOC/tracker corrections.
- [x] `39501aed`..`2782be56` (8 commits): continuous swipe no longer cancels itself on a
  two-write commit or drops the last word silently; suffix undo survives late (Chrome)
  callbacks; fewer blocking editor reads; invalid template keydef degrades instead of
  crashing; docs. `a5e0464b`: palette edit-mode sub-dialogs open above the palette
  (reassigning an occupied slot looked like a no-op).
- [x] Host 2,790 pure / 991 mock (implementer run, includes the palette fix); native
  1,588/1,588 distinct, 3 shards, 0 failures/errors/skips, run
  `fe0d4b26-0d51-40fe-bc3a-a93fae509d9b` (`~/ew-output/oct7-review-full/`).
- [x] Saga (Android 14) release `a5e0464b` (sha256 7a916c81…): occupied-slot reassign,
  one-Backspace suffix undo (`parents'`, `Bowie's`), continuous swipe split/no-split
  mechanics, hide_keyboard, regression + FAB — all PASS. Evidence `build/oct7-saga-retest/`.
  Earlier pass (`build/oct7-saga/`): popover/edit screen, palette, minimize bar+FAB,
  dead key, template `{cursor}` PASS. Restored: Gboard default, 0 mappings, own tabs closed.
  CleverKeys was NOT on the Saga originally and is left installed + enabled (not default);
  uninstall if the maintainer does not want it there (→ Open now 9). One pre-existing Chrome
  tab's page was replaced by the first tester's test URL (count restored, content not
  recoverable), and that tester once force-stopped CleverKeys by mistake (IME restored at once).
- [x] **Seeker cleanup owed** (dropped off Wi-Fi mid-test, 192.168.0.170): DONE 2026-10-07
  10:19–10:25 — "Subkey popover on hold" back OFF (`build/oct7-seeker3/oct7_49_gesture_tuning.png`,
  `oct7_50_back_check.png`), temporary K → North Custom Text "TestMappingOriginal" deleted
  ("2 custom mappings", `oct7_44_perkey5.png`), extra Chrome data: tab closed
  (`oct7_25_after_close.png`). Later builds (through `78052e86`) are installed there.
- [x] Deferred from the review — Monet day/night repro (#181): fixed by `56f3ea90` (new
  keyboard view after a theme change), Saga PASS 2026-10-07 (`build/oct7-final/T1_monet_*`).
  Still deferred → Open now 12: unify `Keyboard2View.executeEditingCommand` with
  `KeyEventHandler.handleEditingKey` (documented why not small); suffix receipt readback
  moved to command time (# TODO in `rememberVerifiedWord`); #175 swipe-to-delete;
  summon-after-system-hide (needs overlay permission). Batch select shipped (`f0eac6df`).

## #186 / #61 per-layout language binding (2026-10-07)

- [x] Layouts can carry a language (Layout Manager → Language chip; custom XML
  `language="fa"` default, Layout Manager choice overrides; `"none"` unbinds). Stored in
  the `layouts` entry, so reorder/edit/delete/backup carry it. Config resolves the ACTIVE
  languages (`LayoutLanguageBinding.resolve`); a bound layout is single-language.
  `ActiveLanguageSync` is now the only language-change path (replaced the key-based reload
  in `PreferenceUIUpdateHandler`), wired in `KeyboardComponentGraph`, fed from
  `CleverKeysService.onConfigChanged`. Toggles say "This layout sets the language" while
  bound; auto-detect paused while bound. Spec: dictionary-and-language-system.md
  "Per-layout language binding". Host: 2,825 pure / 1,016 mock OK; compileReleaseKotlin +
  compileDebugAndroidTestKotlin OK.
- [x] Device retest, Latin half: Saga layout switch → German-bound layout shows the German
  bar/suggestions and the list is restored after, PASS 2026-10-07 (`build/oct7-final/T4_*`,
  `build/oct7-cleanup/saga_lang*`). Persian custom layout bound to fa (fa pack imported),
  three-layout en/de/fr cycle and the uninstalled-language warning → Open now 2.
- Design notes, not tasks: a per-binding "keep secondary" option was rejected for now (see
  spec); built-in layouts never declare `language` (Layout Manager reads the XML default only
  from custom layouts — # TODO if a built-in ever declares one).

## October 2–3 issue work before 2.0

The maintainer wants GitHub feature/bug work and personal testing before release.
Do not tag, bump, publish, or close issues as part of this round. Preserve the
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
- Maintainer checks a compact bottom-row-free layout with Scale Numpad Height enabled,
  numeric/PIN with scaling on/off, and an explicitly opted-in custom numpad → Open now 7.
- [x] #186/#61 layout language shipped `f85c8dcd` (2026-10-07); explicit apostrophe suffix
  commands shipped `64f05dd2` (2026-10-06). Clipboard size/bulk deletion, #168 clear key and
  #188 compose are implemented; see below. Remaining #181 unsupported-glyph and #184
  oversized-update device checks → Open now 4.
- Maintainer manually tests the issue fixes and both minimize styles before 2.0 → Open now 7.

## October 3 device pass and “wet” report

- [x] #181 emoji glyph filtering implemented for category/recent/search grids, with
  actual themed cell paint, per-grid cache and separate text-face handling. Recent
  records are preserved. Focused 7/7, Kotlin compilation, 2,733 pure / 912 mock pass
  (5m34s). Refreshed minified APK now builds/verifies (see #184 below). Device: grids
  sampled on Seeker 2026-10-05; forced-unsupported-glyph check → Open now 4. Monet half
  fixed `56f3ea90`, Saga PASS 2026-10-07.
- [x] Reconnected Seeker reproduced live `wet`→`We`. Popover hold/cancel, empty-slot
  assignment, saved-action dwell-to-edit, FAB minimize/expand and bar minimize/resize
  verified. Bar expansion and app interaction coverage still pending.
- [x] Full-dictionary geometric comparison: clean canonical `wet/we/tree/get/yet/pet/git`
  all rank 1, `hello` rank 2. One valid human `wet` trace ranks 1 vs CTC rank 6.
  Alternate engine is a promising trial, not a broad measured human
  accuracy; CTC code/ranking remains unchanged. Evidence in issue audit and ignored
  `build/wet-geo-probe.log`.
- [x] October 3 connectivity cleanup was completed in the October 5 reconnection
  pass below: original popover/mappings/launcher/rotation/IME restored; no reboot/data clear. Fresh-install/reset checks require separate approval.
- [x] Bangla support path documented in `docs/guides/adding-a-new-language.md`: National
  and Provat tap layouts exist, no published `bn` pack/model. Dictionary building strips
  meaningful Mn signs and the geometric letter-node/projection policy excludes essential
  marks. Stage 1 preserves spelling and adds licensed dictionary/tap fixtures; stage 2
  defines/test mark/conjunct geometry; stage 3 is trained/validated CTC. Nothing advertised
  as implemented prediction/swipe support. Pipeline work → Open now 16.

- [x] Read-only GitHub refresh: still 63 open issues; no posts/closures/pushes.
- [x] Reproduced `wet`→`we` locally through the real shipped CTC model/trie: rank 7 on
  six canonical variants; rank 6 on one valid existing corpus trace (one other trace
  rejected for nonmonotonic timestamps). Endpoint dwell improves rank to 3 but never 1.
  `tree` ranks 3–4 on synthetic controls; needs a real trace before calling it a bug.
  Score decomposition and limitations: `docs/audit/gh-issue-resolution.md`.
- [x] Full Seeker manual pass: superseded by the October 5–8 device passes (minimize,
  popover, compose, clipboard, extra keys, terminal, clear-clipboard, Termux suggestions);
  the uncovered groups are listed in Open now 5 and 7.
- [x] Reconnection focus/UI-dump cleanup completed; remaining device test groups
  are tracked below and in the issue audit.
- [x] Investigate `wet` final-letter emissions/resampling: re-derived from real traces on
  2026-10-07 (`10ae7d51`, `docs/eval/2026-10-07-short-word-ctc.md`) — encoder end-of-trace
  word prior + no emission for collinear pass-through letters; λ 4 is the dev optimum and
  endpoint rescoring is neutral on 4,000 held-out traces. The raw-path featurization fix
  shipped as `745d1ca3` (+0.37 pt held-out top-1). Retraining → Open now 14. No usage-learning
  or endpoint-dwell remedy is promised.

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
- [x] Later minified builds include #181/#184 and are installed on Seeker (latest
  verified clear-clipboard artifact below). Their manual glyph/oversized-update checks
  → Open now 4.
- [x] Historical No route to host/install/cleanup blocker superseded by later
  successful installations and restoration; do not repeat the old cleanup changes.
- [x] Device groups from this list that have since passed: bar expansion (Seeker 2026-10-05),
  app scrolling around the FAB (Seeker 2026-10-05), RTL FAB (Saga 2026-10-07, `c6fe3594`),
  compose (Seeker 2026-10-05), emoji grids sampled (Seeker 2026-10-05), Monet day/night
  (Saga 2026-10-07). Still owed: compact and numeric/PIN height (#90) and cold-start custom
  gestures with swipe/prediction disabled (#145) → Open now 7; remaining release-check
  groups → Open now 5.
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
- [x] Context-driven apostrophe choice for swipe (its/it's, shed/she'd, teams/team's) via the
  static LM's previous word: EVALUATED, NOT SHIPPED. Two pre-registered stages failed their
  bars (stage-1 arm B missed OOD/`years`; frozen arm C +0.39 pt on Common Voice vs +1.0, with
  `shed`/`shell` reversing). `its` gains hold out of domain. Chooser + harness in test sources;
  next steps (listed-evidence variant, wiring line, `is`→`i's` slot-1 junk, `lets` bucket) in
  `docs/eval/2026-10-07-apostrophe-context.md`. The `i's` junk and `lets` bucket shipped
  2026-10-07 (`9f78611f`, `94209334`); the variant → Open now 15.
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
- es LM needs a NEW stated reason before another attempt (e.g. a larger test population)
  → Open now 9.
- [x] LM ratio shape measured (2026-09-29, pre-registered): dev rule kept `RAW` (FLOOR_ONE −0.15
  en prefix-1 on dev); test read once, FLOOR_ONE ahead there (+0.46 mean) — recorded only.
  `both` never applies a static penalty (learned boost ≥ 1). `docs/eval/2026-09-29-static-lm-multilingual.md`.
- FLOOR_ONE for `static_only` — needs a fresh pre-registration with new dev evidence → Open now 9.
- [x] Pack-attribution UI: Settings → Multi-Language → Language Packs → Manage shows each pack's
  licence, credit, source links and full NOTICE.txt (`269d8bb1`/`6fed1b12`, 2026-09-29).
- [x] `privacy_forget_learned_body` names swipe corrections in all 22 locales (`535a2a28`);
  terminology unified per locale with `TranslationGlossaryTest` (`4871c7e5`, `a91aad5a`).
- Native-speaker translation review and device visual verification remain distinct
  from automated structural checks → Open now 8 and 5; preserve the maintainer's
  manual-checklist edits.
- [x] i18n follow-ups closed (2026-09-30): FAQ content `721c757d`, RTL pane arrows `213e8d52`,
  localized I/O failure reasons `cfc0eed2`, command catalog in 21 locales `19d64857`, localized
  settings search + id-keyed scroll `607da6df`. `docs/i18n/2026-09-29-hardcoded-ui-sweep.md`.
- fa/hu device check of the above (pane arrows, search scroll) → Open now 5; native review of
  the 465 command-catalog strings → Open now 8.

## Maintainer/release gates

- ARC-053: soak the minified release APK → Open now 6. (Minified builds have run on Seeker
  and Saga through every October test pass, which is use, not the maintainer's soak.)
  ARC-062/096 implementation is already present.
- [x] ARC-054: decided 2026-09-03 — ru and el are announced (ru val-only tier, el
  synthesis-holdout); the release-notes pin was cleared (ARC ledger wave summary).
- ARC-063: narrow blanket R8 keeps only after the first minified soak → Open now 6.
- Decide any nonzero `finger_occlusion_offset` default only from device-trace A/B evidence
  → Open now 9.

## Agent-executable backlog

- [x] ARC-067: the 21-locale pass CLOSED in Wave D, 2026-09-01 (ledger "Wave D — ARC-067
  CLOSED", lint-enforced coverage, zero MissingTranslation suppressions). ARC-066/087 complete.
  Re-audited 2026-10-08: 0 missing names in all 21 locales, plurals/placeholders clean; fil
  English copies and one fa dropped argument fixed (`docs/i18n/2026-10-08-coverage-audit.md`).
  All translations are machine-quality pending native-speaker review.
- [x] Finish Wave E: ARC-073 citation/doc drift (`d20ed3b5`), ARC-098 phantom-`keyboard2`
  tooling sweep (`f482faf4`), the four verified doc-claim repairs, and the
  `contraction_pairings_cleaned.json` gate run (the file was already deleted in `030265ee`).
  ARC-076 and ARC-089 are complete.
- [x] ARC-072 slice 3 composition root: six Initializers collapsed into
  `wiring/KeyboardComponentGraph` (`fddb65d5`, 2026-09-01); ARC-098 source-tree half:
  gesture-recognition cluster moved into `gesture/` (`d6484ee9`, root 108→101).
- [x] ARC-027/028/029 geometric experiments CLOSED as measured declines
  (`3237d23b`/`16d3ea8d`/`80238617`; ledger).
- [x] ARC-071 Astro 5→6 migration CLOSED (`15814849`, Wave H; the site is now on Astro 7) and
  ARC-046 web regression gate / Tailwind vendoring CLOSED in the same wave (ledger "Wave H").
- [x] ML-side ARC-060 EXECUTED (app `128c93f8`; ML `66c60ad`,`8778fef`); French verb inversions
  shipped PAIRED-only. ARC-061 (`ctc_golden.json` LOW-6 fixture) remains ML-side → Open now 16.
  (ARC-056 uk/bg/mk/he lexicons/langpacks CLOSED 2026-09-01 — `538a1633`/`86156ea3`.)
- [x] ARC-044 remaining assertion-strengthening batch CLOSED (`da5171d0`/`cc07765f`/`a8f7ac03`:
  16 classes strengthened, no Truth dependency in androidTest).

## Verification backlog

- [x] Final guarded host gates on implementation commit `5fb58037`: `runPureTests` 2,087 and
  `runMockTests` 343, both passing on 2026-09-01.
- [x] Wave J: full ew-cli instrumented run executed 2026-09-02 (1,466 tests, Pixel7 API 34;
  only the 2 permanent bench reds), including ARC-058/064/074/077/091/092/095 (HANDOFF, ledger).
- [x] Wave K: Saga protocol complete (`docs/eval/2026-09-02-wave-k-device-verification.md`,
  ARC-068/069/070 evidence; ARC-070 closed no-leak) and Wave K2 Pixel full protocol
  (`a5ee26bc`). IME/properties restored; no framework restart.
- [x] Wave L remainder: the ARC ledger carries the wave D/E/H/J/K closures and HANDOFF, backlog
  and campaign-plan state were consolidated on 2026-09-01; the maintainer-input items are
  Open now 6–10.

## Release authority

Pushes to origin/main are authorized (maintainer, 2026-10-07). Do not tag, bump the version,
publish, or open/comment on external issues without explicit user authorization.

## October 5 reconnected-device follow-through

- [x] Seeker `.170` reconnected. Removed only temporary `t`/South mapping and restored
  popover to false; the other two mappings remain intact. Installed the verified
  `af742286` minified APK with data preserved; installed SHA matches `a356ac06…`.
- [x] #181: sampled smiley and text-emoticon grids render; actual keyboard input routes
  `face` into emoji search and renders mixed results. No recent items were added.
  Forced unsupported sequence/font → Open now 4; Monet live day/night PASS on Saga 2026-10-07.
- [x] #184: Settings import refuses a CKDT header declaring 1,000,000 words despite
  manifest wordCount=1, displays the smaller-pack message, and retains installed count 0.
  Removed the temporary ZIP and restored multi-language to false. Existing-pack rollback
  is host-tested; this device has no installed packs to exercise that scenario (→ Open now 4).
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
  strip pass on launcher. RTL passed on Saga 2026-10-07 (`c6fe3594`); cross-app checks
  → Open now 5.

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
  ranking/model change shipped. Held-out human short-word/model calibration → Open now 14.
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
  locale did not create RTL IME then; RTL later fixed and passed on Saga (`c6fe3594`).
- [x] Reconnected cleanup complete: deleted ONLY t/South minimize_fab and confirmed
  two original user mappings remain. Launcher test field is empty; original IME,
  rotation 0/0 and app locales [] verified. Removed scratch UI XML; HOME then
  expanded Quick Settings, confirmed NotificationShade focus. External-terminal
  editing remains pending; no terminal commands executed.
- [x] Explicit Append apostrophe-s / Append apostrophe commands with verified immediate-word
  attachment and suffix-only undo: shipped in `64f05dd2` (2026-10-06), hardened by the
  October 7 review (`6eb7c7fe`, `ede3f3b3`), Saga one-Backspace undo PASS 2026-10-07.
  Contraction projection selection stays a separate action; no plural-vs-possessive guessing.

- [x] Astra proposes general short-word encoder correction; bounded candidate geometry
  fixes ad but wet/wt collinear templates tie. Exploratory equal-duration timing fixes
  19 synthetic cases + one human wet, but matches the generator and is unvalidated.
  Existing roadmap records architecture, prior ML Phase K/I limits and held-out gates.
- [x] Frozen human screen REJECTS heuristics: 100 traces / 92 words, baseline93 correct,
  geometry83 (1 gain/11 losses), timing61 (1/33). Short20:17→14→11; long80:76→69→50.
  Zero shipping-baseline disagreements; no target/control words in sample. Corpus is
  repeatedly inspected, not held-out; no production ranking/model changes shipped.
- Fresh writer/session-separated human data, general-strata encoder training and protected
  vocabulary/layout evaluation; do not keep tuning on this screen → Open now 14.

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
- [x] Oct 6 device dialog, cancel, range validation and synthetic-only deletion checks
  pass (below). The earlier Oct 5 attempt could not reach Seeker `.170` and changed nothing.

## Clipboard persistent selection (2026-10-07)

- [x] Select mode replaces "Delete results": persistent row-identity selection
  (`clipboard/ClipboardSelection.kt`) survives search/size/filter/page changes;
  select/deselect all matching covers every page; Delete selected reuses the frozen
  `deleteSnapshot` transaction (one deletion path). All three tabs, one at a time. Lifetime
  as first built ended on exit, tab switch, pane close and keyboard hide; the same day's
  `027deaa1` moved it to the service-scoped holder so it survives those too (section above).
  22-locale strings with plurals.
- [x] Host tests: `ClipboardSelectionTest` (7), selection cases in
  `ClipboardHistoryViewStateGuardsTest`, `ClipboardMediaDeleteAffordanceTest` (real
  getView), `ClipboardTabsAndPaneCloseTest` (lifetime). androidTest sources compile.
- [x] Native (ew-cli): `ClipboardPaneTintTest#selectionModeKeepsEntryViewportCountAndActionTargetsInLandscape`,
  `ClipboardDatabaseTest#selectionResolvedAfterReloadDeletesUnchangedRowsByIdentityAndKeepsCopies`,
  the updated `ClipboardFilterDialogTest` 205-row flow and the renamed-id tint test all
  executed green in the 2026-10-07 full runs on `24afd33c`, `78052e86` and `991651d1`
  (`~/ew-output/oct7-wave{2,3,4}-*/results.xml`, 0 failures/errors/skips/flakes).
- [x] Device (Saga, synthetic clips only, `991651d1`, 2026-10-07): A1–A7 PASS — Select
  mode with checkbox rows, select-all-matching across searches, deselect one, size filter
  keeps the count, landscape fits entry row + count + icons + paging, Delete selected
  dialog count/size, Cancel keeps the selection, confirm → "Deleted N of N", pinned/todo
  copies stay (`build/oct7-saga3/a1*–a7*`); Seeker delete 1/1 PASS. The "switch app →
  selection gone" step is obsolete since `027deaa1` (selection now persists). TalkBack
  → Open now 4.

## Oct 6 Seeker clipboard verification

- [x] Installed the verified `06287570...` ARM64 release APK; on-device hash matches.
  Actual clipboard search isolates three `zzclip` synthetic private clippings (12 B,
  1,264 B, 2,313 B). Confirmation shows 3 results / 3.6 kB; Cancel preserves them.
  Minimum 1 kB leaves two results. Min 10 kB / max 1 kB shows error and disables Apply.
- [x] Reconnected cleanup: confirmed deletion removed ONLY the two remaining
  `zzclip-small` PINNED/TODOS copies (one in each); all three history fixtures were
  already deleted. Private-copy toolbar restored OFF and read back in Settings.
  System clipboard was never replaced; no personal clippings were selected for deletion.
  Clipboard search/filter cleared; rotation 0/0, original CleverKeys IME and
  NotificationShade focus verified after HOME + expanded Quick Settings. Scratch UI XML removed.
- [x] Installed `7b763f5e...` minified APK and verified on-device hash; contrast fixed.
  Actual confirmed two-entry size-filtered deletion passes; empty results disable deletion.
  Cleared size filter and deleted the small history fixture; its pinned/todo copies remain.
  Landscape filter and confirmation layout checks started; filter fits with reachable buttons.
- [x] Verified final layout changes on device: integrated pagination + feedback into
  result row after landscape tests exposed zero entry viewport. Clear old-tab feedback
  on tab switch; use count-neutral summary/title wording in all 22 locales.
  Intermediate minified APK built/verified/installed: SHA-256
  `5bcb5cfccd8859bad557951a7d6fb23ab3af8b50481b4f725abf2741bf15108a`.
  Intermediate synthetic HISTORY fixture `zzclip-final-oct6` deleted after landscape
  confirmation/cancel/delete checks; toolbar restored OFF immediately after adding it,
  then rotation 0 and Quick Settings restored. The 5bcb APK still clips entry text
  in its 31dp landscape viewport. Responsive controls and a strengthened 48dp test
  are implemented and verified on the final APK below.
- [x] Contrast fix Kotlin/resource compilation and 15 focused clipboard state tests
  pass (`build/oct6-clipboard-contrast-{compile,tests}.log`).
- [x] First compact layout: Kotlin/resource/app/test APK build and all 2,739 pure tests pass.
  Pixel7/API34 clipboard UI tests pass 4/4, zero skips: theme tint and compact 120dp
  landscape entry viewport with paging/feedback. The initial viewport test fails against
  the old APK (feedback hides entries), proving the regression before the fix.
  EW final `da9a1948-5d91-4e39-9568-c704093f82d5`; baseline
  `1d70de53-5929-4cd1-a28e-c6a630e21e4d` (expected failure).
- [x] Responsive layout: guarded Kotlin/resource/app/test APK assembly + 15 clipboard
  state tests pass (`build/oct6-clipboard-responsive-verified.log`). Two earlier test
  invocations named the target incorrectly; corrected relative package suffix passes.
  Pixel7/API34 UI tests pass 4/4, zero skips; viewport test covers 24 combinations:
  720/890dp × 120dp landscape and 400dp × 300dp portrait, LTR/RTL, paging/feedback.
  Requires ≥48dp entry viewport and 48dp paging targets, checks delete stays in bounds,
  and remeasures the same view from wide to narrow. Against the intermediate two-row
  APK it fails at the 48dp requirement (not a resource/class lookup error).
  EW responsive `a7874daa-ec83-4810-bd3e-8bf93564cf93`; baseline
  `33a8b2cc-2d9f-4860-b127-a1005032c9f7` (expected failure).
- [x] Responsive minified release build + release lint pass (7m12s). Signature v2,
  alignment and ZIP CRC verified; installed on Seeker with --no-streaming, on-device
  SHA-256 matches `211798434811ca8e47d740e5b095ce7ae7ee38b52dc9b65b6c235c57b79d0526`.
  Logs: `build/oct6-clipboard-responsive-{release,signature}.log`.
- [x] Final minified Seeker pass: full clipping text and edit/send actions visible in
  landscape; controls fit in portrait. Confirmation/cancel/confirmed deletion of ONLY
  the 17-byte `zzclip-final-oct6` fixture pass. Empty results disable deletion; tab switch
  clears old feedback. Real 32-page history: next reaches page 2, previous returns to 1;
  all-result count remains 3,113 after removing the fixture. Portrait paging fits after rotation.
  Evidence: `build/oct6-responsive-{landscape-entry,landscape-confirmation,
  landscape-deleted,landscape-page2,portrait-paging-final}.jpg`.
- [x] Final cleanup: all synthetic records removed; private-copy toolbar OFF, original
  rotation 0/0 and CleverKeys IME retained. Host test field and Settings search empty,
  clipboard search/filter cleared; scratch XML removed and NotificationShade focus restored.
  All 55 Oct 6 screenshots are under 2000px and 4MiB. OS clipboard never replaced.
- [x] Clipboard tab/close host regressions pass 9/9
  (`build/oct6-clipboard-pane-close-tests.log`), alongside the 15 state guards and 4 UI tests.
- Maintainer daily-use pass with disposable text/media clippings and their usual
  date/tag/privacy/status filters, editors, language/theme and accessibility settings
  → Open now 6. Enlarged text and very short narrow split-screen panes → Open now 5.
- Intermittent full-suite timing failures from the clipboard polish round: first run 937/938,
  predictor latency 17.4ms vs 15ms; retry 937/938, adaptation background write missed 12s
  deadline. Neither production area changed. Focused classes pass 16/16 and 11/11 without
  changing limits. Host load was 12–15 on four cores; the cause is not established.
  The subsequent clear-command round passed all 946 mocks twice; no production timing
  fix or relaxed limit was applied. Logs: `build/oct6-clipboard-polish-{tests,recheck}.log`,
  `build/oct6-{predictor-latency,persistence}-recheck.log` → Open now 13.

## #168 clear system clipboard command and extra-key fixes (2026-10-06)

- [x] Opt-in registry/extra-key command, shared ordinary-key/custom-gesture clearing,
  native API 28+ and empty-text legacy fallback; saved clipboard tabs remain intact.
- [x] Four command/feedback strings added to all 22 locales via PAL Gemini 3.8.
- [x] Full core-command host suites pass: 2,740 pure and 946 mock tests; debug and
  androidTest APKs build (`build/oct6-clear-clipboard-final-tests.log`).
- [x] Real clipboard API + all-tab/media preservation: 1/1 executed, no errors/skips,
  Pixel7/API34 emulator run `ae7ece84-4133-413c-8023-9995eff3a0d4`.
- [x] First minified release lint/build passes; Seeker shows 225 commands, retains
  its 2 mappings, finds `clear_clipboard` under search and previews the correct action.
- [x] Extra-key category includes the command. Fixed recycled row labels (Seeker showed
  Greek/Math for `clear_clipboard`) with stable item/header keys and current-resource
  labels; search now matches displayed titles. Both searches show the right row on Seeker,
  checkbox off, 19/108 keys enabled, original 2 mappings retained. No preference toggled.
- [x] Final Android gates pass 6/6, zero errors/skips (`e9a2ba28-caea-4339-9fec-385b25e86667`);
  same regression fails on frozen category-only APK at the stale-title assertion
  (`2f93dbea-8ba1-4770-b867-77db737f1f6c`), after finding/scrolling the key ID.
- [x] Final minified release Kotlin/lint-vital/R8/build passes (6m44s); signature,
  alignment and archive checks pass. Seeker installation SHA matches
  `5f87bd1d7e32287895b0e4eba81bf93f9aea11df4055ac15a1b9415acf492a15`.
  Log: `build/oct6-clear-system-clipboard-labels-release.log`. Android clipboard never
  replaced or cleared on Seeker.
- [x] Reconnected October 6: removed scratch UI XML, restored HOME + Quick Settings
  (NotificationShade), verified original CleverKeys IME and rotation 0/0.
- Maintainer tests the assigned command on a disposable copied clip → Open now 7; native
  wording review → Open now 8. Sessions preserve the Seeker's existing Android clipboard.

### October 7 review of continuous swipe / suffix commands

- [x] Eight review findings verified and fixed with fail-first host tests (commits
  `39501aed`..`f77b06fb`); 2,790 pure + 991 mock pass, release/androidTest compile.
  Details: plan doc "Review follow-up (October 7)".
- [x] Device recheck on Saga/Chrome (`a5e0464b`, 2026-10-07, `build/oct7-saga-retest/`):
  Backspace after Append apostrophe undoes the suffix in one press (`parents'`, `Bowie's`);
  continuous phrases with a typed prefix split/no-split as expected. The "tap '.' right
  after lift → feedback" case is not separately recorded → Open now 5.
- [x] Native suites `SmartAutoSpaceTest` (75), `ContinuousSwipeTest` (20) and
  `DynamicTemplateTest` (26) reran green on emulator.wtf with fresh builds of `24afd33c`,
  `78052e86` and `991651d1` (2026-10-07, `~/ew-output/oct7-wave{2,3,4}-*/results.xml`).
- TODO(perf): word receipts still cost one 3-read editor readback per committed word;
  see rememberVerifiedWord for why it is not deferred → Open now 12.

### Current next work (as of 2026-10-06; reconciled 2026-10-08)

- [x] Rejected/throwing swipe commit bookkeeping fixed and tested; successful
  acknowledgement is now required for new word/source/space/learning/ML/correction
  state. Strict suffix receipts remain next; evidence in prerequisite section below.
- [x] Explicit Append apostrophe-s / Append apostrophe commands with verified word
  attachment, suffix-only undo and learning-state correction: `64f05dd2` (see above).
- `ad`/`wet` encoder/calibration work using fresh writer/session-separated traces;
  rejected heuristics remain unshipped; no per-word switch to geometric → Open now 14.
- Bangla spelling-preserving dictionary/mark pipeline before a swipe model/pack;
  existing tap layouts remain the current support level → Open now 16.
- [x] Layout-linked language (#186/#61): `f85c8dcd` (2026-10-07). Remaining release manual
  coverage → Open now 5–7.
- [x] Custom terminal package setting: parser, exact live routing, search, strict
  backup/default/reset and 22 locales. 2,741 pure + 953 mock and 8 Android UI tests
  pass; signed/minified lint/build passes. Seeker Save/dedupe/readback/invalid/rotation/
  Cancel/clear pass; test list and 0/0 orientation restored. Installed SHA-256
  `97c289284237db8c2ea930ed42d1979e9b87a041532b547ba2f4266dfb464ce2`.
  External terminal app editing remains pending; spec: docs/specs/termux_integration.md.
- [x] Audit legacy Terminal Mode switch: no production consumer remained; the switch was
  removed in `17db49da` (2026-10-06) with a DEPRECATED_KEYS tombstone and
  `DeadPlumbingDriftTest`. Shared terminal routing is independent of it.
- [x] Autofill extra key now categorized; shared catalog partition coverage and UI
  visibility tests pass. Regression fails on previous APK; Seeker title/identifier
  search passes, enabled count unchanged (19/108).
- [x] Extra Keys landscape/rotation follow-up: one keyed lazy list replaces fixed
  headers; saveable query survives recreation. Seven UI tests pass; prior APK fails
  query-restoration regression. Seeker scroll reaches Autofill in landscape, portrait
  return retains query, enabled count stays 19/108. Final installed minified APK SHA
  `ffca616e836b496358638155df9017c3c2b6e7d69246609e715423d6ce743f75`.
  Original IME/0/0 rotation/launcher + notification shade restored; own dump removed.
  Guide/spec corrected to actual per-key Boolean storage and preferred-slot placement;
  docs build passes all 84 pages and paired guide/spec links resolve. Final Kotlin
  compile passes. UI logs: `build/oct6-extra-keys-viewport-{ui,red}-v2.log`.

### October 6 expanded native coverage (complete)

- [x] Added 14 native regressions across terminal Config refresh, minimize/compact
  geometry, emoji glyph support, oversized pack update, apostrophe selection routing,
  size-dialog/205-row deletion races, and persisted gesture cold start.
- [x] Fixed selected-range punctuation removing a space before the selected text;
  native fail-first 56/57 and fixed regression 1/1, no errors/skips.
- [x] Kotlin compilation and 2,741 pure + 953 mock pass. Mock editor fixture now
  reports both selection endpoints; hardened native fixtures fail on missing setup.
- [x] Android lint passes in 46m54s: 0 errors, 216 warnings. Corrected APKs rebuild;
  four real experimental encoders are verified in androidTest only, absent from app.
- [x] Minified ARM64 build passes release lint/R8/shrinking (5m45s); signature,
  archive, alignment and ARM64 libraries verified. SHA-256
  `3f2fed25767860d8d857340264ed0ec4c61c7682ff44a9e99ad452c88c6afc4c`.
  Not installed: Seeker absent from `adb devices`; earlier installed artifact stays.
- [x] Both previously failing classes pass 9/9, no errors/skips, run
  `7beda687-b5c6-4792-b352-a16a9e6b3730`; stale Settings section expectation corrected.
- [x] Complete unfiltered three-shard recheck passes all 1,491 distinct methods,
  0 failures/errors/skips/flakes, run `3f0c33a3-c71b-49ff-a87b-da044859eb54`.
  All 14 additions pass; inventory matches the first full run except corrected name.
- [x] Current guides/specs/skills updated to actual behavior and final evidence;
  84-page build, 428 rendered local links and 8 paired-guide routes pass.
  Coverage/docs round committed as `ef36222c`; no push/release or maintainer-checklist edits.
- [x] Accepted-commit prerequisite completed below; named feature work follows.
- Language-pack final swap currently deletes the old directory before rename; add
  recovery and fault-injection coverage. Oversize rejection is protected → Open now 11.

### Accepted-commit prerequisite (complete)

- [x] Six added real-handler host cases and two native editor wrappers cover false,
  throwing/missing writes, separator rejection, manual adaptation and exact spelling.
  Final-fixture fail-first host: 22/27; fixed focused: 28/28.
- [x] No new word/source/space/context/learning/ML/correction or success haptic after
  a rejected insertion; candidate slate remains available. Manual adaptation waits
  for acceptance; legacy pre-commit replacement deletion retains an explicit TODO.
- [x] Full Kotlin compile and suites: 2,741 pure + 959 mock; app/test APKs build.
  Log: `build/oct6-accepted-commit-full-tests-build-v2.log` (6m56s). Updated three
  source drift assertions to protect rejection cleanup and prohibit ownership fallbacks.
- [x] Native punctuation class: 33 distinct passed, 0 failures/errors/skips, run
  `3e8f6000-e943-4110-9746-c5bd8508e46e`. Frozen hashes in testing strategy.
- [x] Wiki build passes 84 pages; 30 paired-guide routes resolve. Earlier 2,182-link
  check missed relative engineering-note links; final normalized audit corrects this.
- [x] Three features committed in `64f05dd2`: optional continuous swipe, explicit verified suffix
  commands/undo/owned learning, and TEMPLATE expansion/assignment/persistence/XML.
  Kotlin + 2,757 pure / 974 mock; native feature focus 137/137 and affected fixtures 76/76.
  Final unfiltered EW 1,588/1,588, zero failures/errors/skips/flakes, all 1,491 prior +97 new;
  run `29e624bf-7d30-4c38-b87d-a8a5671b319a`. Lint 0 errors/216 warnings; minified ARM64
  signature/CRC/ELF/alignment/feature and benchmark-exclusion checks PASS. Docs 84 pages,
  2,211 links/30 guide routes. Exact artifacts/logs and remaining manual checks in plan handoff.
- [x] Install/test the final minified APK: superseded — later builds through `991651d1`
  (Saga) and `78052e86` (Seeker) were installed and exercised on 2026-10-07 (sections above).
  Maintainer cross-app/gesture/suffix/template/TalkBack checks → Open now 4, 5 and 7.
  Foreign manual checklist preserved; ad/wet (14), Bangla (16) and other plan gaps (17) stay open.
