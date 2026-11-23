# Current work queue

Updated: 2026-11-17. Push state: read `git status -sb` (a hash written here went stale twice).
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

**Status 2026-11-17 (agent).** A2 DONE: pure 3,890 / mock 1,842 / instrumented 1,653 (API 34–37,
after two stale tests were fixed) green on the current code (`a20209c8`). Before the tag: only the
maintainer's M1 soak, M2 strings and M4 tag sequence. Known issues and the full list: release-readiness audit "Status
2026-11-17". Review §4 device checks are all done.

**Status 2026-11-21 (agent).** A2 re-run on the code since: the first try (`e505d449`) found the
French swipe offering `francais` (item 37, fixed `a0f3958d`); at `c8f8eb6d` pure 3,950 / mock 1,862 /
instrumented 1,665 (API 34–37, 0 failed, 2 expected skips) green, CI green. Release `a82f9da2`
installed on the S25U and the Pixel. Audit "Status 2026-11-21".

**Status 2026-11-22 (agent).** Delete word in a link removes one `key=value` pair (item 38,
`a3245ff2`, device PASS on the Pixel); Swedish accent misspellings out (item 37, `931f5669`, sv.cklm
gate all pass). Follow-ups the same day: any `&key=value` text behind a default-on setting
(`b5de2e22`), word delete at Backspace NW on every layout as an icon (`45ef127a`, item 39), toggled
modifiers readable on every theme (`34fefdd9`, item 40), 123+ on Ctrl's swipe-up edge in place of
"ABC" (item 41); pure 3,978 / mock 1,876 green, CI green,
device checks PASS on the Pixel; the 19 rendering/look/theme/subkey/layout-export/settings/a11y
instrumented classes 185/185 on API 34 and 37 (`~/ew-output/toggles-layouts-2026-11-22/`).
Release 5007948e installed on the S25U and the Pixel. Instrumented: the 9 affected classes (deletion, short swipes, pipeline, key events,
auto-space, subkeys, clipboard editor, multi-language CTC, language detection) 253/253 on API 34
and 37 (`~/ew-output/url-sv-2026-11-22/`). Release 17e8b8d4 (item 41) installed on the S25U and
the Pixel, device PASS. **A2 DONE at 17e8b8d4**: full instrumented run, 1,665 tests on each of
API 34–37, 0 failed, 2 expected skips (`Issue181NoFontProviderTest`, `VoiceEngineDeviceBenchmark`;
app sha256 `fb756657399b4a1c…`, test `40a1b3577e389dd6…`, `~/ew-output/full-2026-11-22/`); pure
3,978 / mock 1,876; CI green. Release notes: the GitHub body draft
(`docs/plans/v2.0.0-github-release-body.md`) carries the features merged 11-01 … 11-22 and these
counts; the fastlane note (`RELEASE_NOTES.md` = the three ABI files, 500 bytes / 486 chars) now
names the new look, animated themes and True Black, the familiar looks, next-word, autocorrect,
clipboard Clean/Merge and the rotation fix, in place of the learning/private-copy/backup/TalkBack/
popover and minimize/terminal/macro lines (maintainer request 2026-11-22);
`ReleaseMetadataDriftTest` and `ReleaseRecordDriftTest` green. Before the tag: only M1, M2, M4.

**Review since v1.5.0 (2026-11-14; fixes 2026-11-15, agent):** `docs/audit/2026-11-14-post-v1.5-review.md`
§2a is the status table. H1–H4 and all 26 mediums are fixed, and so are most lows; a follow-up audit
of the fixes (two reviewers) found 14 more: 13 fixed, one kept as a stated residual. Every fix has a test seen failing on the
unfixed code (or, for new API, not compiling). The remaining lows were fixed on 2026-11-15 (§2a
"Remaining lows" row; one closed without change, with the reason). Still open: native review of
`io_error_backup_password_unreadable` and five Settings strings (item 10); device checks in §4
(lock-screen worst case, aurora first tick, Settings rotation — tablet landscape scene memory DONE
2026-11-15 on the Tab S11, release `a20209c8`, Aurora, Frame Rate 10: PSS 246 MB still / 291 MB
peak moving, 4 backdrop layers while shown, 0 after each of 20 mid-spell hides, native heap back to
52 MB after 30 s idle, no deaths; the tablet's lock is a PATTERN, so its lock screen never uses an
IME — the reboot there (maintainer OK) PASSED for first unlock: contraction reload ran, no deaths,
Aurora drew and moved; password bouncer DONE too (maintainer set a password: CK is the bouncer
IME, survives `am crash` as the only enabled IME, unlocks; review §4 item 2); now
also: rotate during a voice-pack import). Done on the Saga 2026-11-15 (release `d82bd309`): Undo
re-offered after a rotation, Look editor's single hint toggle; and on `a20209c8`: the snackbar sits
above the keyboard and Undo brings the mapping back (1 → 0 → 1). Saga checked back IN.

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
   arrows. Finding: a font-scale change reaches an inflated pane only after an IME restart —
   FIXED 2026-11-19 `356a1b84`: only the CLIPBOARD pane is cached (emoji/GIF inflate on every
   open); its rebuild signature now carries font scale, densityDpi and locales, so the
   `onConfigurationChanged` → `refresh_config` → `setConfig` path drops it
   (`ClipboardPaneThemeInvalidationTest`, red on the old code). Saga `.82` PASS (release
   `356a1b84`, sha256 5f216bf1…): system font size 1.0 → 2.0 → 1.0, the pane reopened at each
   size with the same IME pid; `build/nov19-saga82-fontscale/`. Suites at `356a1b84`: pure 3,934 /
   mock 1,856 green. [history](../docs/history/todo-open-now-items-2026-11-14.md#item-4)
5. [x] device: undo/redo leftovers — CLOSED 2026-11-18: every listed check PASS (Pixel 10-17,
   Compose and Termux 10-21, private-entry Ctrl+C 10-22). [history](../docs/history/todo-open-now-items-2026-11-14.md#item-5)
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
   ranking change (`they're`/`you're` lose to `there`/`your`); FIXED 2026-11-18: `ß`/`æ`/`œ` keys
   have a CTC slot and `schön`/`schon`-type collisions offer both forms (spec "Collisions": real
   FUTO traces t1 unchanged, t3 +0.1…+1.3 pt in de/es/fr; ß/æ boards synthetic pins only, no
   corpus — a device check on Neo 2/Danish is open); `yo` is nearly impossible to
   swipe (always `to`; maintainer 2026-11-18) — the item-17 short-word family, see there. [history](../docs/history/todo-open-now-items-2026-11-14.md#item-8)
9. [ ] agent (objective) + maintainer (feel): pre-tag checks. #90, #168, #145, both minimize styles
   PASS 2026-10-25 (Pixel). Full checklist 2026-11-09 (Pixel): 43 pass / 5 fail / 3 partial / 12 not
   run. Open: `d'être` (item 35), the spoken TalkBack pass, voice items, a GIF tap into an editor
   that accepts content, autofill inline (needs a test autofill service on a test phone); maintainer:
   suffix/template/continuous feel. 2026-11-18 Saga `.82` (checklist rows): emoji panel by hand and
   GIF import/browse/search/Replace/remove PASS; FAIL text emoticons invisible on a light runtime
   look (FIXED 2026-11-18, `EmojiPaneRuntimeThemeTest`; with it the per-cell AppCompat log spam,
   the stale GIF "Imported:" line after removal, GIF search spell-check, launcher rotation); FAIL inserted emojis count as part of the typed word (`+😂🍕`, then `😂🍕a`) — FIXED 2026-11-18
   `ef4a2519` (`EmojiWordBoundaryTest`); Pixel PASS 2026-11-20 (release built at `e505d449`, launcher
   test field: two emojis leave the bar empty, then `a` offers at/all/are). Rotation (maintainer 2026-11-19: Google app always, Discord with the clipboard open):
   FIXED 2026-11-19 `RotationShowGuard` — Google app 8/8 kept (was 0/8; Gboard 3/8, SwiftKey 8/8),
   the open pane comes back (4/4); Discord on the Pixel hides EVERY keyboard itself (its own
   `hide(ime())`, window kept) and still does: the maintainer's Discord-with-clipboard case on the
   S25U is unreproduced on the Pixel — retest there. Maintainer retest 2026-11-19 (S25U): works
   with and without the clipboard, except the very first try; not reproduced on the Pixel (first
   rotation of a fresh process, 3,215-entry clipboard open: kept, pane back). Its log had already
   rotated; the hide now logs its delay after the rotation (`CleverKeys/Tile`) for the next miss.
   Maintainer 2026-11-20 00:13-00:15 (S25U, Discord chat box, captured): every miss is Discord's own
   `HIDE_SOFT_INPUT_BY_INSETS_API` 140-345 ms after the rotation, window kept, so the guard leaves it
   (by design); pane closed 5/5 kept, pane open 2 of 7 hidden. Pixel, same Discord 356.3, chat box of
   another channel: the first rotation hid it (496 ms) and Discord returned to its channel list over
   the chat, while the IME stayed bound to the chat box. The IME cannot tell the two apart; open: does
   the S25U show the chat or the channel list after the hide (maintainer): the chat. FIXED 2026-11-20:
   the hide comes from the keyboard being taller than the new screen at the rotation (a pane open; the
   pane closed just before kept it 5/5), so with a pane open the guard undoes it with one request and
   the pane comes back (Pixel at S25U geometry: 3 hides in 50 rotations, all undone, 0 left closed;
   previous build 7/10 closed). Stowing the pane during the re-show did nothing (9/10). Maintainer
   retest 2026-11-20 (build 0b453fbe, installed on the S25U and the Pixel): fixed. CLOSED.
   Password bar (maintainer 2026-11-19): a new password field showed the previous field's
   password — FIXED, `SuggestionBar.setPasswordMode` resets per field (ew-cli red→green). [history](../docs/history/todo-open-now-items-2026-11-14.md#item-9)
10. [ ] maintainer: ONE native-speaker review of every string added since 2026-09-29 in 21 locales
    (later items fold their strings in here). Sheets: `docs/i18n/review/2026-11/<locale>/` —
    `1-translate.csv` 636 rows, `2-check.csv` 345, `3-read-through.csv` 34,378 optional; README
    there. RE-EXPORTED 2026-11-19 at `301c3c43` (no reviewer cells had been filled, so nothing
    was merged): the sheets now hold all 76 translatable English strings added or changed since
    the 2026-11-03 export `ba5b0c93` (70 added incl. the scene/motion, Look, theme-cycle,
    tile-toggle and border-opacity strings and the eight listed below; 6 changed:
    `clipboard_clean_message`, `appearance_key_opacity_*`, `appearance_look_key_preview_*`,
    `theme_builtin_cleverkeysdark_desc`), checked present in all 21 locales; 23 removed keys left
    the sheets. Re-import: `python3 scripts/export_translation_review.py --import <locale>/*.csv
    [--dry-run]`. Leads: `docs/audit/2026-10-22-translation-audit.md`. [history](../docs/history/todo-open-now-items-2026-11-14.md#item-10)
    RE-EXPORTED 2026-11-22 at `17e8b8d4` (still no reviewer cells filled): adds
    `input_delete_word_url_pairs_title` / `_desc` (machine-translated 2026-11-22) to all 21
    locales and refreshes the `code` column's line numbers; now 636 / 346 / 34,419 rows. The
    maintainer plans an AGENT review of the sheets (2026-11-22); an agent review is recorded as
    such, not as the native-speaker review M2 names.
    2026-11-23 independent GPT-6 review: all 35,401 sheet rows read in 21 locales; findings in
    `docs/audit/2026-11-23-translation-independent-review.md` (parallel active reviewer owns
    sheet imports). Includes missing app-visibility disclosure in lv/ru/uk, opacity wording
    inverted in cs/hu, and overlay/Shift-state meaning errors. Reconcile before treating the AI
    corrections as complete; native review and the M2 shipping decision remain open.
    Added 2026-11-15, in the sheets since the 2026-11-19 re-export: `io_error_backup_password_unreadable`,
    `theme_save_failed`, `theme_limit_reached`, `extra_keys_reset_title`, `extra_keys_reset_message`,
    `extra_keys_reset_done`; changed 2026-11-18: `clipboard_clean_message`; added 2026-11-18:
    `dict_error_out_of_memory`. 2026-11-18: the five that were English and the stale Clean message
    now have machine translations in all 21 locales, and every string added or changed since the
    last LanguageTool run (76, `ef203c87`..`e33bfffa`) went through LanguageTool in its 14 locales
    (audit §9; 1 fix, cs hu in ko lv tr vi unchecked). All still await this native review.
11. [x] maintainer decisions (2026-10-25 questionnaire) — CLOSED 2026-11-18, every part DONE: es LM stays
    unshipped (pre-registered retry failed; only UD Spanish-PUD unused), FLOOR_ONE ships for
    de/fr/it/pt/sv (en keeps RAW), `finger_occlusion_offset` stays 0, the first Saga keeps CleverKeys
    default, Text Assist keeps Android's chooser. [history](../docs/history/todo-open-now-items-2026-11-14.md#item-11)
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
    the last batch was "not run on instrumented or a device" at the time (covered since by the full
    instrumented runs of 11-01 and 11-17); border-radius readout Pixel PASS 2026-11-20 (Custom Border
    Config on: "Border Radius 0%", "as % of the key width"; switched back off);
    Monet seed FIXED 2026-11-20: `MonetSeedScheme` reads the system palette `MonetLight`/`MonetDark`
    draw (a theme created while Monet is selected, and the trail choosing Monet sets), Light/Dark
    below API 31 (`MonetSeedSchemeTest` 5, compile-red first; mock 1,862 / pure 3,945 green). (KeyboardPreviewView and its "jewel" fallback were deleted 2026-11-15: no caller.) [history](../docs/history/todo-open-now-items-2026-11-14.md#item-13c)
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
    `ad`/`wet` decode. Same family (maintainer 2026-11-18): `yo` → `to` — y→o is the tail of the
    t→y→u→i→o line and the encoder's prior favours `to`; never measured. Until then: `yo` as a custom
    word at swipe priority High/Highest (not measured for this pair). Kit `ml/rtx-kits/17-short-word-ctc/` (gate: FUTO swipe-1 VALIDATION,
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
    and go/send/next/done icons unchecked (no reference); a look shows only 3 bar entries. FIXED
    2026-11-18: the plain bar no longer highlights the first next-word chip (space types none;
    `LookSuggestionBarInstrumentedTest.plainBarHighlightsNoNextWordPrediction`, red then green, ew API 34). [history](../docs/history/todo-open-now-items-2026-11-14.md#item-25)
26. [x] agent: per-layout key geometry (spec `docs/specs/layout-system.md` "Per-layout key geometry")
    — CLOSED 2026-11-18: built; Saga 144 device passes 2026-10-27/28 incl. border OFF and the
    density fix `32c8c4bb`; Add Layout landscape fixed; no check left. [history](../docs/history/todo-open-now-items-2026-11-14.md#item-26)
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
    minimized + 8 s field tap, layers after rotation; left on shared devices: only test Chrome tabs (Note10+:
    checked 2026-11-18 — CleverKeys uninstalled, Samsung Keyboard, brightness auto; tablet: needs an
    unlock). Both in the owners' hands; not worth a UI session. [log](../docs/history/todo-item-34-scene-rounds.md#item-34)
35. [ ] device + maintainer: French/Italian accented elisions. Data fixed, replay 0 regressions;
    `l'école`/`c'est`/`l'été` and it `C'è` PASS on device. **Maintainer decision**: rank elisions by
    their own Leipzig frequency (best arm, `d'être` rank 1–2; needs a per-key table + NOTICE +
    relaxing `CoreImeHygieneDriftTest`; `docs/eval/2026-11-09-elision-base-frequency.md`); a human
    `d'être` swipe. it `l'attivita` FIXED 2026-11-18: it.cklm rebuilt from the corrected table under a
    pre-registered gate (all six pass; `scripts/build_static_lm.py` Italian config). it accent
    misspellings FIXED 2026-11-19: 78 dropped from it_enhanced.bin (`perche`, `piu`, `perchè`, `pò`;
    `it_blocklist.txt`), `qual'è`/`qual'era` + 4 bad `un'` elisions dropped (`MISSPELLED_DISPLAY`),
    it.cklm rebuilt under a new gate; `docs/eval/2026-11-19-italian-accent-spellings.md`. Follow-up:
    10 non-Italian elisions dropped (`a'i`, `fin'ora`, `l'une`), langpack-it.zip rebuilt. **Maintainer
    decision**: the it lexicon has NO one-letter word (typed `è` → empty bar); adding `e è a o i`
    failed the pre-registered tap gate (OOD prefix-1 top-1 -0.89 pt: the letter takes slot 1) though
    the model gained; parked patch + numbers in the eval doc. Open: no table offers `po'`; fr/es/pt/sv
    lack one-letter words too. [history](../docs/history/todo-open-now-items-2026-11-14.md#item-35)
36. [ ] device: "Space before opening punctuation" (default on; spec
    `docs/wiki/specs/typing/smart-punctuation-spec.md` Feature C). Ellipsis chain fixed; the
    checklist's "Punctuation and elisions" passes on Saga .144 except `d'être` (item 35). Open: a
    human run; the setting's translations (item 10). [history](../docs/history/todo-open-now-items-2026-11-14.md#item-36)
37. [ ] agent → maintainer: accent misspellings in the other Latin lexicons (2026-11-20; found by the
    full instrumented run: `francais` in a French swipe slate, all four APIs). FIXED fr/es/pt/de the
    Italian way: 313 / 668 / 160 / 4 spellings dropped (`francais`, `tambien`, `nao`, `munchen`),
    reviewed keeps listed per language, fr.cklm and pt.cklm rebuilt under pre-registered gates (all
    pass; de.cklm names none of its four), packs rebuilt; `scripts/accent_twin_audit.py`,
    `AccentSpellingDriftTest`, `docs/eval/2026-11-20-accent-spellings.md`. The instrumented slate
    check now allows a bare word that is its own canonical form (`wahrend` is real German). Swedish
    FIXED 2026-11-22: 33 dropped (`ocksa`, `maste`, `over`, `goteborg`), 56 kept (English words
    Swedish text uses as they are, `Brå`/`ÖB`, names, colloquial `sånna`/`äre`/`gö`, looked up),
    sv.cklm rebuilt under its pre-registered gate (all pass; G4a flat), pack rebuilt;
    `docs/eval/2026-11-22-swedish-accent-spellings.md`. Open: a native review of the keep/drop calls
    (maintainer; for sv the colloquial keeps `assa`, `sä`, `ör`, `na`, `ma`, `ra` and `at`); not
    device-checked.
38. [x] agent: Delete word in a link removes one `key=value` pair (maintainer request 2026-11-22,
    "would help to clean up long links"). `UrlQueryDelete` (pure) for the Delete Word key, the
    backspace swipe (`delete_last_word`, which cut a 50-character slice before) and the inline
    fields; the link stays valid after each press; terminals untouched. Tests seen failing first
    (`UrlQueryDeleteTest`, `UrlQueryDeleteRoutingTest`, `InlineSwipeSessionTest`); wiki FAQ,
    per-key actions, advanced settings, clipboard editor; skill `ime-key-routing.md`. Device PASS
    2026-11-22 on the Pixel (release bb618b60): the backspace NW flick took
    `…/item?id=42&utm_source=news&utm_medium=email` to `…?id=42&utm_source=news`, `…?id=42`,
    `…/item` in the CleverKeys launcher field (Compose) and in Settings search (EditText); a caret
    inside `utm=yz` removed that pair and its `&`; `later` after the link went as a word. The
    `delete_word` key itself was not pressed on a device (no default binding; same applier).
    Found on the way: the backspace round-trip gesture that the docs said produced `delete_word`
    no longer exists (nothing sets `Modifier.GESTURE`; TODO left in `KeyModifier.applyGesture`).
    Follow-up the same day (maintainer): any text with `&key=value` (or `?key=`) qualifies, link
    or not, behind a default-on setting (Input Behavior → "Delete one key=value pair at a time",
    `delete_word_url_pairs`, 21 locales translated by the agent, native review pending with
    item 10). Device PASS 2026-11-22 (Pixel, 5007948e): `filter: size=m&color=red&sort=price` lost
    one pair per NW flick; Settings search finds the toggle, on by default.
39. [x] agent: word delete at Backspace NW on every language layout with a Backspace key (maintainer
    2026-11-22: "preferably using a symbol"); 8 of 87 had it, 79 gained it (Colemak and Bone moved
    forward delete NW → NE); `delete_last_word` draws the key font's word-delete icon (U+E01B) in
    place of the English `word`. `BackspaceWordDeleteLayoutTest`. Not covered: ckb_fa, tj and os put
    Backspace in a letter key's corner (no Backspace key). Backspace hold-to-repeat was already on
    by default (Key Repeat on, Backspace Only Repeat on; 600 ms then every 25 ms) and the subkey
    popover never claims Backspace. Device PASS 2026-11-22 (Pixel, release 5007948e): the NW
    corner shows the icon; a 1.5 s Backspace hold deleted 36 characters.
40. [x] agent: toggled modifiers readable on every theme (maintainer S25U screenshot 2026-11-22: a
    toggled Ctrl on the true black themes had only a label tint; on the CleverKeys theme a toggled
    Shift/Ctrl drew `#2E0F45` on a near-background fill, measured `#151219`, because the look's
    light Caps Lock fill is drawn at the Activated Key Opacity setting). New TOGGLED frame for a
    latched modifier, LOCKED for a locked one, both opaque, colours from `ToggledKeyPalette`
    (contrast floors 2:1 fill, 4.5:1 label); a look's latched Shift keeps its glyph frame.
    `ToggledKeyPaletteTest` (every XML theme), `KeyboardLookRenderTest`. Device PASS 2026-11-22
    (Pixel, its neon theme, release 5007948e): latched Shift and Ctrl opaque magenta `#BA08C2`
    with white labels and corners, locked Ctrl `#FF00FF` with black labels. Open: the CleverKeys
    look and the Black theme on a phone (release installed on the S25U for the maintainer).
41. [x] agent: 123+ on Ctrl's swipe-up edge (maintainer 2026-11-22: "for any letter layout,
    shouldnt the '123+' numpad toggle display for the North subkey of Ctrl instead of 'ABC'?").
    `switch_numeric` moves from Ctrl SE to N on `res/xml/bottom_row.xml` and the three layouts
    with their own bottom row (arab_pc_ir, arab_pc_ckb_fa, latn_neo2); `switch_back_clipboard`
    ("ABC", closes an open pane, a no-op otherwise) leaves Ctrl, since every pane closes with its
    close button or its opener key. Ctrl SE is now empty. `CtrlNumericSwitchLayoutTest` (mock);
    the a11y fixtures that copy the Ctrl key follow it. The QWERTY Samsung/Gboard/iOS/SwiftKey
    styles keep their own 123 key and are unchanged. Device PASS 2026-11-22 20:33 (Pixel, release
    17e8b8d4, neon theme): Ctrl shows 123+ on its top edge, the clipboard icon bottom-left, nothing
    bottom-right; a swipe up opened the number pane and its ABC key returned to the letters, with
    nothing typed into the field.
42. [ ] agent (not a tag blocker; found during item 41's device check): in an empty field the
    auto-capitalised Shift is gone after a trip to the number pane and back (123+ → ABC gives
    lowercase letters). `Keyboard2View.setKeyboard` → `reset()` clears every modifier, and
    `KeyboardReceiver`'s SWITCH_TEXT does not run auto-capitalisation again. Not caused by item 41
    (the same path ran from Ctrl SE before). Fix: re-run the autocap check after SWITCH_TEXT, with
    a test seen failing first; left for after the A2 run at 17e8b8d4 so that run stays valid.
