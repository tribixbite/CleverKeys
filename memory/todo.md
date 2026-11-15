# Current work queue

Updated: 2026-11-09. Push state: read `git status -sb` (a hash written here went stale twice).
Pushes to origin/main are authorized (2026-10-07) and in use; tag, version
bump, release, publish and GitHub posts still need explicit authorization. Reference state and
rules: [HANDOFF.md](HANDOFF.md). Release readiness, item by item:
[`docs/audit/2026-10-31-v2.0-release-readiness.md`](../docs/audit/2026-10-31-v2.0-release-readiness.md)
(§5 "Before tagging" and its "Resolved" section).
Campaign plan: [`docs/plans/2026-08-30-full-backlog-campaign.md`](../docs/plans/2026-08-30-full-backlog-campaign.md).
Issue tracker: [`docs/audit/gh-issue-resolution.md`](../docs/audit/gh-issue-resolution.md).

The "Open now" section is the only authoritative task list. Everything after it is dated history,
kept for evidence; its open boxes were reconciled on 2026-10-18 against the 92 commits
`b9ce12e6..47be5f23` and their messages, the test files, `docs/eval/`, `~/ew-output/` and the
device-evidence dirs `build/oct7-*/` … `build/oct17c-*/` (screenshots and logcat, not in git).
A history item that is still open says "→ Open now N" instead of carrying its own box, so
duplicates are counted once.

Split 2026-11-14 (nothing deleted): each Open-now item below keeps its owner, current status
and a link to its full dated text in
[`docs/history/todo-open-now-items-2026-11-14.md`](../docs/history/todo-open-now-items-2026-11-14.md) (item 34's round
logs: [`docs/history/todo-item-34-scene-rounds.md`](../docs/history/todo-item-34-scene-rounds.md)). The dated sections
that followed Open now (2026-09-27 → 10-17) are in
[`docs/history/todo-archive-2026-09-to-10.md`](../docs/history/todo-archive-2026-09-to-10.md).

## Open now (2026-10-18)

**Status 2026-10-31.** v2.0.0 is code-complete and NOT tagged (versionCode 20000). In 2.0 now:
CTC default + geometric fallback, channel autocorrect ranking, familiar looks, layout themes and
per-layout key geometry, clipboard reorder and paged editor, Unfinished Words, subkey sources and
modified layout copies, Persian pack + half-space key, on-device voice input with pause-based
endpointing (item 30), and the in-app Open-source licenses screen. The agent-side release
documents are done (audit "Resolved": release record, GitHub body, wiki, NOTICE, CHANGELOG
header, README counts, local F-Droid recipe synced from upstream). The tag waits on the
MAINTAINER only: the soak sign-off (items 8, 9 and 30's real-voice/TalkBack/RustDesk checks;
audit M1), the string-review decision (item 10, now including the licenses-screen strings; M2),
then the tag sequence (M4) with the full suites and an instrumented run at the tag commit (A2).
2.1 (not started): toolbar row above the keys and card-grid clipboard
(`docs/plans/2026-10-05-recommended-features-and-gaps.md` §4.4, §4.5), the pack library (§3.2,
item 33: choose the dictionary / voice pack per language), item 20's unowned roadmap,
the RTX items 17–19, and CHANGELOG `[Unreleased]`.

**Review since v1.5.0 (2026-11-14; fixes 2026-11-15, agent):** `docs/audit/2026-11-14-post-v1.5-review.md`
§2a is the status table. H1–H4 and all 26 mediums are fixed, and so are most lows; a follow-up audit
of the fixes (two reviewers) found 14 more: 13 fixed, one kept as a stated residual. Every fix has a test seen failing on the
unfixed code (or, for new API, not compiling). The remaining lows were fixed on 2026-11-15 (§2a
"Remaining lows" row; one closed without change, with the reason). Still open: native review of
`io_error_backup_password_unreadable` and five Settings strings (item 10); device checks in §4
(lock-screen worst case, tablet landscape scene memory, aurora first tick, Settings rotation — now
also: rotate during a voice-pack import). Done on the Saga 2026-11-15 (release `d82bd309`): Undo
re-offered after a rotation, Look editor's single hint toggle. Still to check on the `a20209c8`
build: tapping Undo brings the mapping back, and the snackbar now sits above the keyboard
(adjustResize). The Saga `192.168.1.130` is still checked OUT in its `/sdcard/agent-sharing.md`
with the `d82bd309` release installed; rotation and focus were restored.

Owners: **device** = needs a test phone · **maintainer** = the maintainer's hands or decision ·
**agent** = doable from this box · **RTX** = needs the ML training box.

Test phones (2026-11-09, later): EVERY phone answers on `:5555` — Pixel 8 Pro `192.168.0.216`,
Sagas `192.168.0.144` / `192.168.1.130` (CleverKeys Dark, CleverKeys default) and `.83` (ROOTED,
`persist.adb.tcp.port=5555`, Gboard default, CleverKeys 2.0 release since 2026-11-09, IME disabled),
Galaxy Note10+ `192.168.0.55` (secure lock screen — unusable unattended). Unrooted phones keep
5555 until reboot; the host cron `~/git/termux-tools/tools/adb-pin-5555.sh` (list
`~/.config/adb-pin-5555.list`) re-pins them through the rotated wireless port after one. **Check a
phone OUT/IN in `/sdcard/agent-sharing.md` on the phone** (append a row; other projects' agents use
the Pixel). `192.168.0.81` is THIS HOST (same boot_id) — never use it. Recipe for exact text checks: a
Chrome `data:` textarea read over CDP (`adb forward tcp:N localabstract:chrome_devtools_remote`);
where Chrome refuses `data:` (.83) use CleverKeys' own launcher field and read it with uiautomator.
Synthetic swipes: one `adb shell` chain of `input motionevent` through key centres (landscape Saga:
rows y 702/812/922/1027, Q at x 348, pitch 204; portrait Saga: rows y 1710/1872/2036, Q x 64,
pitch 106; short swipes as DOWN + one MOVE + UP). google/artemis (`~/git/artemis`) has no model key.
Seen from this box 2026-11-05: Pixel 8 Pro `192.168.0.216` wireless port 43213, Android
17; Sagas `192.168.1.130` Android 14 no Termux, `192.168.0.144` and `192.168.0.83` Android 13 with
Termux — `.83` was running a game, leave it alone; Play Protect blocks the DEBUG APK on the Pixel
and on `.1.130` unless `settings put global verifier_verify_adb_installs 0` (delete it afterwards);
on `.1.130` `cmd media_session volume --set` does nothing — use Volume keys while media plays. Older leases as reported on 2026-10-18 — rediscover with `nmap -sn` and
`getprop ro.product.model`): Pixel 8 Pro `192.168.0.216`
(wireless-debugging port rotates), Saga `192.168.0.202` (CleverKeys installed, enabled, not
default), second Saga `192.168.0.144` (Termux installed; CleverKeys installed, IME disabled,
Gboard default), Seeker `10.105.217.200` (was 192.168.0.170; drops Wi-Fi when idle). Never
`stop`/`start` a Saga; leave no trace; restore any setting you change.

1. [x] device: Termux drift re-test after `fd3eec40` — CLOSED 2026-10-22 (D2 PASS). [history](../docs/history/todo-open-now-items-2026-11-14.md#item-1)
2. [ ] maintainer: Persian half of #186/#61 — built 2026-10-23, Pixel device PASS. Left: native-speaker
   review of the fa pack and the 4 possibly over-dropped words (اورا پیکه هنریکه سیاهو); Persian
   swipe quality unvalidated; half-space completions for a joined prefix (# TODO in WordPredictor);
   the أ ؤ إ gestures (`1d3bc1d0`) not device-checked; decision: the two Central Kurdish boards
   declare no language (no ckb pack exists). [history](../docs/history/todo-open-now-items-2026-11-14.md#item-2)
3. [ ] device: clipboard Select/⋮ with TalkBack; #181 glyph filter without a font provider. TalkBack
   cannot be driven over adb; each key's short swipes are TalkBack custom actions since 2026-10-26 →
   maintainer: TalkBack on, Ctrl → Actions menu → "Clipboard manager (Southwest)". #181: no GMS-free
   phone or emulator.wtf image exists → run `Issue181NoFontProviderTest` on a GMS-free phone (e.g.
   LineageOS without GApps). #184 oversized UPDATE PASS 2026-10-28 (Saga 144). [history](../docs/history/todo-open-now-items-2026-11-14.md#item-3)
4. [ ] device: leftovers of the plan's "Maintainer device checks". PASS 2026-10-28 (Saga 202): "."
   after lift, templates (textarea / password / pane search), font scale 2.0 pane header, fa/hu
   settings search. NOT RUN: continuous swipe with auto-space OFF and interrupted phrases (JVM tests
   only), narrow split-screen clipboard pane, media-only clip, font-scale entries, fa/hu pane page
   arrows. Finding: a font-scale change reaches an inflated pane only after an IME restart. [history](../docs/history/todo-open-now-items-2026-11-14.md#item-4)
5. [ ] device: undo/redo leftovers — every listed check PASS (Pixel 10-17, Compose and Termux 10-21,
   private-entry Ctrl+C 10-22); the item's own text ends "Item can close" (box not ticked). [history](../docs/history/todo-open-now-items-2026-11-14.md#item-5)
6. [ ] device + maintainer: RustDesk / blind-field checks. MAINTAINER PASS 2026-10-26: paste and
   suggestion taps; `teh`+space and Ctrl+Backspace PASS 10-25. The swipe-correction tap fix
   `691aa8cf` (GH #192) was device-checked in Termux only, not in a RustDesk session. Not reported:
   the same checks in a RustDesk remote terminal and in AVNC (TYPE_NULL); optional "clipboard is
   empty" bar message (new string ×21) if the maintainer wants it. [history](../docs/history/todo-open-now-items-2026-11-14.md#item-6)
7. [ ] device: editor-command leftovers — all else PASS (Replace popup `d1acb09b`, device PASS
   10-28). Open: one real rewriting/translating app that RETURNS process-text on a phone (the result
   path is proven only on emulator.wtf API 33/34 by `ProcessTextResultInstrumentedTest`). [history](../docs/history/todo-open-now-items-2026-11-14.md#item-7)
8. [ ] maintainer soak: running every release as generated. Bugs (a) `they've`, (b) long to-do entry
   editing, (c) bépo `été`: fixed or maintainer PASS 2026-10-26. Left: the soak → ARC-063 narrow the
   blanket R8 keeps → tag decision; a few real traces of a hard pronoun contraction would decide a
   ranking change (`they're`/`you're` lose to `there`/`your`); `ß`/`æ`/`œ` keys have no CTC slot;
   `schön`/`schon`-type collisions keep only the more frequent form. [history](../docs/history/todo-open-now-items-2026-11-14.md#item-8)
9. [ ] agent (objective) + maintainer (feel): pre-tag checks. #90, #168, #145, both minimize styles
   PASS 2026-10-25 (Pixel). Full checklist 2026-11-09 (Pixel): 43 pass / 5 fail / 3 partial / 12 not
   run. Open: `d'être` (item 35), the spoken TalkBack pass, the emoji panel by hand, voice/GIF items
   (no packs on the phone), autofill inline; maintainer: suffix/template/continuous feel. [history](../docs/history/todo-open-now-items-2026-11-14.md#item-9)
10. [ ] maintainer: ONE native-speaker review of every string added since 2026-09-29 in 21 locales
    (later items fold their strings in here). Sheets: `docs/i18n/review/2026-11/<locale>/` —
    `1-translate.csv` 626 rows, `2-check.csv` 332, `3-read-through.csv` 33,163 optional; README
    there. Re-import: `python3 scripts/export_translation_review.py --import <locale>/*.csv
    [--dry-run]`. Leads: `docs/audit/2026-10-22-translation-audit.md`. [history](../docs/history/todo-open-now-items-2026-11-14.md#item-10)
    Added 2026-11-15, NOT yet in the sheets (re-export first): `io_error_backup_password_unreadable`
    (agent translations in all 21), and `theme_save_failed`, `theme_limit_reached`, `extra_keys_reset_title`,
    `extra_keys_reset_message`, `extra_keys_reset_done` (English in every locale).
11. [ ] maintainer decisions (2026-10-25 questionnaire) — every part reads DONE or CLOSED: es LM stays
    unshipped (pre-registered retry failed; only UD Spanish-PUD unused), FLOOR_ONE ships for
    de/fr/it/pt/sv (en keeps RAW), `finger_occlusion_offset` stays 0, the first Saga keeps CleverKeys
    default, Text Assist keeps Android's chooser. Box not ticked. [history](../docs/history/todo-open-now-items-2026-11-14.md#item-11)
12. [ ] DECIDED 2026-10-25: stay at targetSdk 34 for 2.0; bump in a later release (instrumented suite
    green on API 35/36/37). Bump prerequisites: edge-to-edge insets in 13 of 15 activities and
    `values-id` beside `values-in` (35), a predictive-back device pass of every Compose
    dialog/activity (36), no large-screen orientation opt-out (37). [history](../docs/history/todo-open-now-items-2026-11-14.md#item-12)
13. [x] neighbour-substitution tiebreak — CLOSED 2026-10-25; setting, seam and eval deleted `2984b7da`. [history](../docs/history/todo-open-now-items-2026-11-14.md#item-13)
13b. [ ] agent → device: noisy-channel autocorrect ranking, DEFAULT since 2026-10-19 (`353003c6`; eval
    `docs/eval/2026-10-18-channel-autocorrect-ranking.md`). Device PASS (Saga 2026-10-20; Hebrew
    prefix typos on the Pixel 2026-10-22); Hebrew δ 0.5 and κ 3.0 shipped. Open: Arabic prefixes —
    TODO pending an Arabic eval. (The block also logs 10-20 Saga PASSes of the QS tile, #147, #190,
    #139, #154, #187.) [history](../docs/history/todo-open-now-items-2026-11-14.md#item-13b)
13c. [ ] agent → device: code-quality audit 2026-10-20 — every HIGH/MED/LOW row fixed or moot. Left:
    the last batch was "not run on instrumented or a device" at the time; border-radius readout not
    device-checked (needs Borders on); `ThemeProvider.getBuiltInColorScheme("monet")` still the dark
    approximation. (KeyboardPreviewView and its "jewel" fallback were deleted 2026-11-15: no caller.) [history](../docs/history/todo-open-now-items-2026-11-14.md#item-13c)
14. [ ] agent drafts every close comment / PR review into a file; maintainer approves per item before anything is posted (2026-10-25). GitHub — 38 DONE/FIXED close candidates + 2 NOT-REPRO/BY-DESIGN, PRs
    #189/#183/#182 review and #164 close as superseded (`docs/audit/gh-issue-resolution.md`).
    Drafts done (`docs/audit/2026-10-25-github-drafts.md`, 48 items). DECIDED 2026-10-26: hold every
    draft until v2.0.0 is tagged, then the maintainer reviews them (re-check each against the tag first).
15. [ ] maintainer: deferred review items. Summon after a system hide needs no overlay permission
    (no code changed; wording follow-ups in `docs/specs/keyboard-minimize.md` "Overlay permission
    check"); the suffix receipt readback is kept by design (reopen only with a jank profile). #175
    swipe-to-delete built (`2787468d`), device PASS on every tab; open: its TalkBack "Delete" action
    (not drivable over adb, item 3); strings → item 10. [history](../docs/history/todo-open-now-items-2026-11-14.md#item-15)
16. [x] agent: `autocorrect_prefix_length` — measured 2026-10-21; default 0 stays. [history](../docs/history/todo-open-now-items-2026-11-14.md#item-16)
17. [ ] RTX: short-word CTC — word-balanced fine-tune on the raw-path input contract so dwell-less
    `ad`/`wet` decode. Kit `ml/rtx-kits/17-short-word-ctc/` (gate: FUTO swipe-1 VALIDATION,
    session-disjoint); prep check 2026-11-05 all PASS. Next: run it on the RTX box. [history](../docs/history/todo-open-now-items-2026-11-14.md#item-17)
18. [x] agent: apostrophe context chooser — round 3 PASSED, shipped, device check 2026-11-05. [history](../docs/history/todo-open-now-items-2026-11-14.md#item-18)
19. [ ] RTX: Bangla spelling-preserving dictionary + mark/conjunct geometry, then a validated
    model/pack; ARC-061 `ctc_golden` fixture fix. Kit `ml/rtx-kits/19-bangla-pack/`; WordChars and
    the kit §3 remainder DONE 2026-11-04/06. Open: TODO the Bangla conjunct form of a doubled
    consonant (`ক্ক`); the model is blocked on a layout decision (centre keys cover 17–31 % of
    words); Bangla questions follow WM Keyboard. [history](../docs/history/todo-open-now-items-2026-11-14.md#item-19)
20. [ ] unowned roadmap: 1.3 at-rest clipboard encryption, 2.3 dwell picker (measure first),
    4.2 spline swipe trail (benchmark first), #187 below-accents, #175(3)/#80(3) toolbar,
    4.3 opt-in floating quick-action button (future release; overlay permission requested only
    on opt-in; maintainer 2026-10-22).
    (4.1 live theme preview shipped 2026-10-08, `70b94c11`.)
21. [ ] agent: final QA pass. Latest full instrumented run 2026-11-01 at `0b5eb513`: 1,655 pass / 0
    fail / 1 environmental skip on API 34–37; 1.x → 2.0 upgrade tests 0 failures. Open:
    `lintAnalyzeDebugAndroidTest` / `…UnitTest` OOM on Termux → let CI's lintDebug cover test
    sources; the long Pinned-entry editor not run on the Pixel (Pinned PASS on Saga 144, item 8). [history](../docs/history/todo-open-now-items-2026-11-14.md#item-21)
22. [ ] agent: Saga 202 backup round trip + real 1.x upgrade (2026-10-25) PASS; findings (a)–(e)
    FIXED (large-paste chunks, pane previews, paged editor, IO callbacks, hex field, palette header,
    custom themes in backups). Not device-checked: the paged editor's conflict message (pinned by
    `ClipboardPagedEditViewTest`) and its TalkBack labels. [history](../docs/history/todo-open-now-items-2026-11-14.md#item-22)
23. [x] agent: export dialogs' file names + learned-words search/pagination (2026-10-26). [history](../docs/history/todo-open-now-items-2026-11-14.md#item-23)
24. [ ] agent: Pinned and To-dos rows reorder (spec `docs/specs/clipboard-system.md`). Device PASS
    2026-10-27; (b) and (c) DONE. Open: (a) TalkBack moves not driven on a device (adb cannot run
    custom actions; pinned by `ClipboardReorderViewTest`). [history](../docs/history/todo-open-now-items-2026-11-14.md#item-24)
25. [ ] agent: keyboard looks (spec `docs/specs/layout-system.md` "Keyboard looks"); rounds 1–9 built
    and device-checked through 2026-10-31. Open/structural: no toolbar row (2.1), system font,
    SwiftKey's low-set comma/period glyph; key legends have no Creator control; Samsung light palette
    and go/send/next/done icons unchecked (no reference); a look shows only 3 bar entries; the plain
    bar highlights the first next-word chip though space types none. [history](../docs/history/todo-open-now-items-2026-11-14.md#item-25)
26. [ ] agent: per-layout key geometry (spec `docs/specs/layout-system.md` "Per-layout key geometry")
    — built; Saga 144 device passes 2026-10-27/28 incl. border OFF and the density fix `32c8c4bb`;
    Add Layout landscape fixed. The item names no remaining check. [history](../docs/history/todo-open-now-items-2026-11-14.md#item-26)
27. [ ] agent: layout `<theme>`, per-key/row styling, custom themes v2, theme ↔ layout export (spec
    "Layout themes and per-key styling"). Device 2026-10-27 (Saga 202): checks 1–4 PASS after fixes.
    Open: a Theme Creator pass of every new control (sliders, chips, variant rows); hint/preview
    chip rows overflow and scroll in fa; one 126 px-short IME after a night-mode switch (not
    reproduced, unproven). [history](../docs/history/todo-open-now-items-2026-11-14.md#item-27)
28. [x] agent: inline-field word delete + deferred space after a swipe (`0a6dddf9`, device PASS 2026-10-28). [history](../docs/history/todo-open-now-items-2026-11-14.md#item-28)
29. [x] agent: "Unfinished Words" setting (device PASS 2026-11-05). [history](../docs/history/todo-open-now-items-2026-11-14.md#item-29)
30. [ ] maintainer + device: on-device voice input, SHIPS IN 2.0.0 (spec `docs/specs/voice-input.md`;
    pause endpointing 2026-10-31). Agent device checks PASS on Saga 144 (permissions, pack import,
    fields, key path, inline editor). NOT VERIFIED — maintainer: TalkBack announcements, RustDesk /
    blind remote field, a person's real voice and pauses, noisy rooms. Open: 45 voice strings →
    item 10; voice lines in `docs/plans/v2.0.0-manual-test-checklist.md`. [history](../docs/history/todo-open-now-items-2026-11-14.md#item-30)
31. [ ] device + maintainer: per-key subkey sources and modified layout copies (spec
    `docs/specs/subkey-customization-current.md`). (a) and (b) DONE; Saga 144 device PASS 2026-10-30
    (badges, Enter, copy, Revert, popover copy, Export XML round trip), its three findings fixed.
    Open: TalkBack reading of the rows; (c) native review of the 24 new strings and
    `layout_manager_export_not_written` (item 10). [history](../docs/history/todo-open-now-items-2026-11-14.md#item-31)
32. [ ] device + maintainer: voice engine follow-ups (research
    `docs/research/2026-10-28-stt-engine-deep-dive.md` §6.5–6.7). Done: CPU EP with the arena ON
    (XNNPACK rejected), small pack built + published, Saga/Pixel timings. Open: (a) a low-RAM phone
    (dictating with small reaches 843 MB RSS on a Saga), optional JNI purge of the ~90 MB kept
    after release; (b) a person's voice and a noisy room for small; (c) pack choice → item 33. [history](../docs/history/todo-open-now-items-2026-11-14.md#item-32)
33. [ ] agent (2.1): pack library — several dictionary and voice packs per language, a per-language
   choice in Settings, Remove with a confirmation (`docs/plans/2026-10-05-recommended-features-and-gaps.md`
   §3.2; maintainer request 2026-11-05). Today dictionary packs replace each other per language code
   and voice packs use the first by name with no indication.

34. [ ] device + maintainer: default look, CleverKeys Aurora, animated scenes (19 rounds to 11-14;
    summary + cost model: `docs/specs/scene-backdrops-and-power.md`). Open: maintainer verdict on look,
    aurora, scenes, motion; device: fresh install / upgrade-keeps-theme / custom-theme-delete /
    per-layout gap / picker / key-press-preview items, theme commands, QS tile shade-hidden path +
    minimized + 8 s field tap, layers after rotation; restores owed: Note10+, tablet tabs. [log](../docs/history/todo-item-34-scene-rounds.md#item-34)
35. [ ] device + maintainer: French/Italian accented elisions. Data fixed, replay 0 regressions;
    `l'école`/`c'est`/`l'été` and it `C'è` PASS on device. **Maintainer decision**: rank elisions by
    their own Leipzig frequency (best arm, `d'être` rank 1–2; needs a per-key table + NOTICE +
    relaxing `CoreImeHygieneDriftTest`; `docs/eval/2026-11-09-elision-base-frequency.md`); a human
    `d'être` swipe; it `l'attivita` until it.cklm is rebuilt (TODO in the generator). [history](../docs/history/todo-open-now-items-2026-11-14.md#item-35)
36. [ ] device: "Space before opening punctuation" (default on; spec
    `docs/wiki/specs/typing/smart-punctuation-spec.md` Feature C). Ellipsis chain fixed; the
    checklist's "Punctuation and elisions" passes on Saga .144 except `d'être` (item 35). Open: a
    human run; the setting's translations (item 10). [history](../docs/history/todo-open-now-items-2026-11-14.md#item-36)
