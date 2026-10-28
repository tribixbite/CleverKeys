# Current work queue

Updated: 2026-10-18. HEAD `05a36547` plus this docs commit is pushed and equals origin/main.
Pushes to origin/main are authorized (2026-10-07) and in use; tag, version bump, release,
publish and GitHub posts still need explicit authorization. Reference state and rules:
[HANDOFF.md](HANDOFF.md).
Campaign plan: [`docs/plans/2026-08-30-full-backlog-campaign.md`](../docs/plans/2026-08-30-full-backlog-campaign.md).
Issue tracker: [`docs/audit/gh-issue-resolution.md`](../docs/audit/gh-issue-resolution.md).

The "Open now" section is the only authoritative task list. Everything after it is dated history,
kept for evidence; its open boxes were reconciled on 2026-10-18 against the 92 commits
`b9ce12e6..47be5f23` and their messages, the test files, `docs/eval/`, `~/ew-output/` and the
device-evidence dirs `build/oct7-*/` … `build/oct17c-*/` (screenshots and logcat, not in git).
A history item that is still open says "→ Open now N" instead of carrying its own box, so
duplicates are counted once.

## Open now (2026-10-18)

Owners: **device** = needs a test phone · **maintainer** = the maintainer's hands or decision ·
**agent** = doable from this box · **RTX** = needs the ML training box.

Test phones (DHCP leases as reported by the maintainer on 2026-10-18, not verified from this
box — rediscover with `nmap -sn` and `getprop ro.product.model`): Pixel 8 Pro `192.168.0.216`
(wireless-debugging port rotates), Saga `192.168.0.202` (CleverKeys installed, enabled, not
default), second Saga `192.168.0.144` (Termux installed; CleverKeys installed, IME disabled,
Gboard default), Seeker `10.105.217.200` (was 192.168.0.170; drops Wi-Fi when idle). Never
`stop`/`start` a Saga; leave no trace; restore any setting you change.

1. [x] device: Termux drift re-test after `fd3eec40` (CLOSED 2026-10-22: D2 PASS below) (the Saga with Termux). Done so far —
   Saga 2026-10-15 (release `7aea1ab8`, Termux 0.118.3, `build/oct15-saga/`): D1
   Backspace-to-empty clears and D3 CleverKeys Enter ends the word PASS; D2/D4 FAILED through
   Termux's OWN toolbar CTRL/⇥, which the IME cannot see → `80207612`: in terminal/remote fields
   a tap never deletes (`TerminalSuggestionPolicy`, spec `termux_integration.md` "Why taps may
   not delete"). Saga 2026-10-16 (release `d8be659b`, `build/oct17-saga/03–19`): D2 deleted
   nothing, but the bar offered `+foocbar` and the tap added it to the dictionary (removed
   again, `build/oct17b-saga/09–12`) → `fd3eec40`: terminal/blind fields never offer the
   exact-add chip or the space-time "Add to dictionary?" prompt; D4 (toolbar ⇥ then `x`) showed
   only `ecx`, no `except`, nothing deleted PASS; `hel` + tap `help` → `help ` PASS. **D2 PASS
   2026-10-21** (second Saga `192.168.0.144`, release `ba230070…`, `build/oct21-saga144/`):
   `foo`, Termux toolbar CTRL + `c`, `bar` → the bar stays empty (no `+foocbar` chip), space gives
   `bar ` with no add prompt, `foo^C` untouched. That Saga now has CleverKeys enabled and as its
   DEFAULT keyboard (maintainer: keep CleverKeys default on test phones). Item can close.
2. [ ] maintainer: Persian half of #186/#61 — BUILT 2026-10-23 (half-space key `9aac669d`; fa pack
   `479a7277`, artefact filter `2beac893`; WordChars/ArabicScriptFold `e8f1d3ae`+`4aa4f756`;
   method `docs/eval/2026-10-23-fa-wordlist.md`). DEVICE PASS on the Pixel (release `c87e2bfb`):
   pack import and in-place update OK; joined `میشود`+space stays joined, bar offers `می‌شود`
   unhighlighted; `می`+half-space key+`شو` keeps one word, completions می‌شود/می‌شوند/می‌شویم/
   می‌شوم/می‌شوید; restored afterwards. OPEN: native-speaker review of the pack and the 4
   possibly over-dropped words (اورا پیکه هنریکه سیاهو); Persian swipe quality UNVALIDATED (one
   synthetic adb trace is not evidence); half-space completions for a joined prefix not built
   (# TODO in WordPredictor). DONE 2026-10-25 (`1d3bc1d0`): أ ؤ إ as gestures on ا/و on both
   Persian layouts (all 50,000 pack words template), Persian PC declares `language="fa"`, Layout
   Manager reads built-in declarations; langpack-fa.zip PUBLISHED to the `langpacks` release as
   experimental (sha256 218024df… verified by download). Not device-checked. Open decision: the
   two Central Kurdish boards declare no language (no ckb pack exists). History below:
   Persian half of #186/#61. No `fa` language pack exists. (Correction
   2026-10-21: a Persian layout DOES exist, `srcs/layouts/arab_pc_ir.xml` "Persian PC", but it
   has no dedicated ZWNJ key; 2026-10-22: a `halfspace` corner gesture on ت exists, as upstream.)
   RESEARCH DONE 2026-10-22 (`docs/research/2026-10-21-persian-zwnj.md` §1–§7): AOSP, HeliBoard,
   FUTO, AnySoftKeyboard put a dedicated half-space key beside the space bar; ISIRI 9147 uses
   Shift+Space. Recommended: (a) add that key (layout XML only), keep the gesture; (b) ZWNJ a
   word character ONLY between two Arabic-script letters, through one shared predicate (today
   `PredictionContextTracker` treats it as one and `Keyboard2View`/`EditorTextNavigation` do
   not); (c) fold ي/ك→ی/ک and ۀ/ة/ە→ه for the pack and lookup only, never آ→ا (note:
   `normalizeForPrediction` turns ۀ into Kurdish ە today); (d) no silent rewrite in either
   direction (`بهروز` name vs `به‌روز` "up to date"; joined `نامهای` is ambiguous between
   نامه‌ای and نام‌های); offer the half-space spelling as a candidate. HeliBoard's `fa`
   dictionary (CC BY 4.0, 1.5 M entries, 667 k with ZWNJ) is a usable source. Findings 2026-10-21 for the pack: `wordfreq` has `fa` (31,317
   words), 2,395 of them ZWNJ compounds (`می‌شود`, `آن‌ها`) beside informal unspaced forms
   (`میکنم`, `میخوام`), plus a bare-ZWNJ token to drop. NEEDS MAINTAINER DECISIONS before
   building: keep ZWNJ compounds as single words (needs a script gate that allows U+200C
   inside a word, and a ZWNJ key on the layout to type them) or fold them; whether unspaced
   `میشود` should autocorrect to `می‌شود`; Arabic ي/ك → Persian ی/ک normalisation. Then build
   (`build_wordlist.py` LANG_CONFIG entry + layout XML + `build_langpack.py`;
   `docs/guides/adding-a-new-language.md`, `.claude/skills/dictionary-pipeline.md`), then on a
   phone: a custom layout bound to `fa` with the pack imported ("Language: Persian" bar, a
   Persian typo autocorrected, Latin restores EN), the en/de/fr three-layout cycle, the
   uninstalled-language warning. Saga Latin→German PASS 2026-10-07.
3. [ ] device: clipboard Select/⋮ with TalkBack; #181 glyph filter on a device WITHOUT a font
   provider; #184 oversized UPDATE of an already-installed pack.
   TalkBack (agent, Saga 202, 10-25): NOT VERIFIED — could not be driven over adb. TalkBack's
   IME window is lift-to-type, so a tap on a pane row activates it (it expanded); injected
   swipes and angle gestures (input swipe / motionevent) did nothing, so the Actions menu
   ("Delete") and the Select/⋮ menus were not reached. Needs a person. Settings restored
   (`enabled_accessibility_services` deleted = null, `accessibility_enabled` 0).
   MAINTAINER 2026-10-26: TalkBack in the clipboard pane works, BUT the pane cannot be OPENED with
   TalkBack on: short swipes (Ctrl down-left) do not reach the keyboard under explore-by-touch, and
   `KeyboardAccessibilityHelper` exposes only ACTION_CLICK per key → agent: expose each key's
   short-swipe outputs as custom accessibility actions (TalkBack Actions menu).
   AGENT 2026-10-26: DONE in code — each key's short swipes (layout subkeys + custom mappings,
   `removed` left out, none while Short gestures is off) are TalkBack custom actions labelled
   "<action> (<direction>)", e.g. Ctrl "Clipboard manager (Southwest)", dispatched through the
   real short-swipe path (`Pointers.performAccessibilityShortSwipe`). Pure + mock + instrumented
   tests; not tried with TalkBack on a phone → maintainer: Ctrl → TalkBack Actions menu → that entry.
   #181 (agent, Saga 144, 2026-10-28): NOT RUN — this Saga HAS a downloadable-fonts provider
   (`com.google.android.gms/.fonts.provider.FontsProvider`, authority `com.google.android.gms.fonts`,
   GMS 26.42.31), so it is not a device without one; still needs a GMS-free phone or emulator.
   #184 PASS (agent, Saga 144, release `16b4a500`, 2026-10-28, `build/oct28-saga144/10–12, 25`): sw
   pack (20,000 words) imported, then an UPDATE pack built locally with the same code and 100,001
   real dictionary entries (sw words + synthetic filler, `build_langpack.py`) → refused "This
   dictionary contains 100001 words; the limit is 100000", logcat `Language pack import refused`;
   Manage still listed "sw • 20000 words" and, on release `3cf3738a`, a layout bound to sw showed
   Active [SW] (17,455 entries) in Dictionary Manager, i.e. the old dictionary still loads. Pack
   deleted, Multi-Language off again, test ZIPs removed from Download.
4. [ ] device: leftovers of the plan's "Maintainer device checks" — continuous swipe with
   auto-space OFF and interrupted phrases, "." tapped right after lift, templates in
   password/inline editors and media-only clipboard, enlarged text + narrow split-screen
   clipboard pane, fa/hu pane arrows + settings-search scroll.
   AGENT 2026-10-27 pm (Pixel, release `4fdd840a`): NOT RUN — continuous swipe with auto-space
   OFF / interrupted phrases (adb motionevent traces are too coarse to judge, and it needs two
   settings changed on the maintainer's phone), templates in a password field (needs a template
   assigned to a key), narrow split-screen (would rearrange the maintainer's apps), "." after
   lift and enlarged font + clipboard pane (the host lost Wi-Fi at 16:12 before these ran).
   AGENT 2026-10-28 (Saga 202 portrait, release 3e49b983 + `d1acb09b`, sha256 ec404959…,
   `build/oct28-saga202/`): "." right after a swipe lift PASS — swipe `hello`, "." (C SW) 2 s
   later → `Hello. `; swipe `world` then "." in the same adb burst (≈70 ms after lift) →
   `World. ` (auto-space swallowed, re-added, next word capitalised). Templates (temporary g NW
   `AB{cursor}CD`): Chrome textarea → `AB|CD` PASS; Chrome `<input type=password>` → nothing
   typed PASS, but no bar message (the bar suppresses messages in password mode by design);
   clipboard-pane search field → nothing typed there or in the app PASS, no visible message
   (the bar is hidden behind the pane). Media-only clip NOT RUN (would replace the real clip).
   Font scale 2.0: the clipboard pane header/results/Select fit, text scales; entries NOT
   checked (history, pinned and to-dos all empty, clip not touched). Finding: a font-scale
   change does not reach an already-inflated pane — it stayed 1.0-sized until the IME process
   restarted (reinstall). fa/hu settings search: hu `mentes` → "Médiaelemek mentése" scrolled to
   the top of the viewport PASS; fa `GIF` → GIF panel section expanded and in view PASS (the ZIP
   row is hidden while the panel is off). fa/hu pane page arrows NOT RUN (no entries to page;
   `RtlMirroringDriftTest` covers the glyph swap). Continuous swipe with auto-space OFF /
   interrupted phrases: device NOT RUN (adb traces too coarse); JVM evidence only —
   `ContinuousSwipePureTest` (auto-space-off separator callbacks, cancel paths) and
   `Keyboard2ViewContinuousLifecycleTest` ("." while the last segment decodes → feedback;
   cancel with/without boundary) green in the full suites. Restored: font_scale 1.0, app
   locale [], 0 custom mappings, both added Chrome tabs closed (3 left), launcher.
5. [ ] device: undo/redo leftovers. DONE on the Pixel 2026-10-17 (release `d8be659b`,
   `build/oct17-pixel/`): clipboard entry editor Ctrl+Z ×2 removes one word per step, Ctrl+Y
   restores, select + Ctrl+C adds a history entry; Chrome textarea Ctrl+Z / Ctrl+Y undo and
   redo PASS (earlier: Ctrl+V pastes into the editor, not the app behind it, `3439fff0`).
   Compose field DONE 2026-10-21 (Saga 202, CleverKeys settings search, temporary `g` mappings
   reset afterwards, `build/oct21-saga/`): undo/redo reach Compose's own history (its steps merge
   edits made within a few seconds, so one undo can span several edits — Compose behaviour).
   Termux undo PASS 2026-10-21 (second Saga, default `undo` extra key on `z` SE): the bar shows
   "Action unavailable in this editor or panel" and no Ctrl+Z reaches the shell
   (`build/oct21-saga144/09.png`). Ctrl+C on a PRIVATE entry PASS 2026-10-22 (Pixel, release `643cd3bb`+i18n tree): a
   private copy (temporary `copy_private` extra key, s SE) made a 🔒 entry; in its inline editor
   keyboard Ctrl+A, Ctrl+C opened "Copy to system clipboard? … Copy anyway?"; Cancel left the OS
   clip untouched. Entry removed by swipe (COMMITTED on TIMEOUT), extra key unticked (19 of 121).
   Item can close.
6. [ ] MAINTAINER RESULTS 2026-10-25 (RustDesk): `teh`+space stays `teh` PASS; tapped suggestions
   DELETE (maintainer: acceptable); swipe corrections tapped from the bar also delete instead of
   append AND remove the preceding space (may involve the auto-space-before/after-swipe
   settings) → agent investigate; Ctrl+Backspace PASS; the PASTE SUBKEY NO LONGER WORKS → cause found by code history 2026-10-25: `68d91874`
   (10-12) switched blind-field paste from the field's own context-menu paste to an IME
   commit of the clip; RustDesk (Flutter fields, which perform the paste in the adaptor) now
   gets its own paste back (`TerminalUtils.handlesOwnPaste`); TYPE_NULL/VNC and terminals keep
   the committed clip. NOT device-verified in a RustDesk session.
   MAINTAINER PASS 2026-10-26: RustDesk paste and suggestion taps work properly now.
   PASTE SUBKEY (agent, Saga 202, 10-25): NOT REPRODUCED. The subkey is the default-on
   `extra_key_paste` on `v` (se corner; sw on bépo). It pasted in Chrome, in Chrome made a custom
   terminal (TERMINAL path: IME reads the clip, commitText) and in RustDesk's own Remote ID field
   (REMOTE path). No paste-routing change since 10-13 (`git log -S`). A remote SESSION was not
   opened (it would type into the maintainer's PC). Candidate cause, unproven: Android 14 clears
   the OS clip after 1 h (`device_config clipboard auto_clear_enabled=true`, 3600000 ms) and the
   blind-field paste is then a SILENT no-op while the pane still lists the clip; RustDesk also
   overwrites the phone clip with the remote clipboard during a session. Next: maintainer retry
   right after a fresh copy; if wanted, a "clipboard is empty" message (new string ×21).
   SWIPE-CORRECTION PART FIXED `691aa8cf` (= GH #192): the REPLACE branch assumed the swipe typed
   `word + " "`; in terminal/blind fields it added a Backspace when the char before the cursor was a
   space — the swipe's OWN trailing space — so RustDesk (its hidden field reads back) lost the space
   before the word; with auto-space-after off every editor lost it. Now the swipe records its exact
   committed text and the tap deletes exactly that, re-using the swipe's leading space.
   `BlindFieldPolicyTest` (all after/before combinations, RustDesk + TYPE_NULL + ordinary editor):
   17 cases red before, green after. Device (Saga 144, Termux = terminal kind, the same key-event
   REPLACE branch as RustDesk): OLD build 10-20 with auto-space-after OFF: `echo ok ` + swipe
   `hello` + tap `help` → `echo okhelp` (bug); FIXED release `84021ccb` (sha256 b2c916f0…) →
   `echo ok help`; after ON → `echo ok help ` on both builds (Termux reads back nothing, so the
   extra-Backspace half needs RustDesk's readback). `build/oct25-saga144/02–17`; setting restored
   ON, lines cleared. RustDesk itself not re-tested (no host here).
   Original: device + maintainer: blind-field checks with RustDesk installed on a Saga (F-Droid/GitHub
   APK `com.carriez.flutter_hbb`) controlling any reachable desktop — typed text, `teh`+space
   stays `teh`, a tapped suggestion appends (never deletes), Backspace after a swipe removes
   the word, clipboard-panel tap and paste key type the phone clip, delete-last-word is
   Ctrl+Backspace; then the same in a RustDesk remote terminal; and AVNC (TYPE_NULL). Needs a
   RustDesk host from the maintainer. `BlindFieldPolicyTest` models RustDesk (`68d91874`).
7. [ ] device: editor-command leftovers (everything else PASS 2026-10-17 → history): one real
   rewriting/translating app that RETURNS text on a phone (Gemini closed without a result,
   Google Translate's TapToTranslate has no replace action; the result path is proven only on
   emulator.wtf API 33/34 by `ProcessTextResultInstrumentedTest`). Compose field DONE
   2026-10-21 (Saga 202, settings search): case cycle hello→Hello→HELLO→hello, select word left,
   Share (one sheet with the selection) PASS; the Replace popup command is a silent no-op there —
   Compose 1.6.8 does not implement android.R.id.replaceText and the IME cannot detect the
   refusal (the connection returns IPC success; a check added in `68e779ef` never fired and was
   removed in `34dbb8e9`). Found on the way: Reset all left stale labels → fixed `9c909048`.
   FIXED 2026-10-28 `d1acb09b`: the popup's own precondition IS readable — a stock EditText
   opens it only for a SuggestionSpan (with suggestions, or misspelled/grammar) touching the
   selection start (AOSP `Editor.SuggestionsPopupWindow.updateSuggestions`), and Compose and
   Chrome hand no spans to the IME. The command reads the text around the selection start with
   GET_TEXT_WITH_STYLES; no usable span → bar "Action unavailable", nothing sent. Test
   `EditingCommandParityTest.replacePopupWithoutASuggestionSpanAtTheCursorSaysUnavailable` red
   before, green after; full pure 3,457 / mock 1,612 OK. Saga 202: Compose settings search →
   message, text untouched PASS; Dictionary Manager search (View EditText), swiped `hello`, caret
   at the word end → the editor's popup help/hell/he'll/held/hero PASS (spans cross the IPC).
   Note: with the caret after the swipe's auto-space the span does not touch it, so Replace
   reports unavailable there (EditText would show nothing either). Still open: a real app that
   RETURNS process-text on a phone.
8. [ ] maintainer soak: running every release as generated (2026-10-25). Bugs found: (a) `they've`
   hard to swipe; (b) clipboard TO-DO panel entries — text input/editing broken for long
   entries (no text inserts); (c) bépo `é` discarded in swipe: swiping `été` gives `tp`, swiping
   `ete` gives `été`. No other bugs found yet.
   MAINTAINER PASS 2026-10-26: swiping `été` (bépo) and `they've` both work.
   (b) FIXED `623070d2` + `63e27c70` (Saga 202, release built 10-25 01:40): three faults in the
   one inline editor (History/Pinned/To-dos). No length cap — the threshold is the pane: once
   the entry is taller than the visible list (~7 lines portrait, 2–3 landscape) the unfocused
   field's caret sat below it and typed text landed out of sight; tapping a spell-underlined
   word opened the focusable SuggestionsPopupWindow, Chrome hid the keyboard and the edit was
   lost (`inputType="none"` is the unset value → TYPE_CLASS_TEXT with suggestions); and on
   To-dos keystrokes went into a DETACHED recycled copy of the editor (ListView's
   detachViewFromParent keeps the window token) — saved, never shown. Now: field focused,
   `textMultiLine|textNoSuggestions`, live-row check. Device PASS after: History and To-dos
   long entry, typing scrolls to the caret, tap-to-place-caret keeps the keyboard. Pinned device
   PASS 2026-10-26 (Saga 144, release 5eefd162, landscape; throwaway 1.5 kB private entry via the
   private-copy activity, toggle restored off): insert/delete/caret moves at start, middle, end
   all land on screen and save. Found there: the editor's cancel/delete were dead in landscape —
   under the camera cutout a systemui ScreenDecorOverlay owns touches (x 2282–2400, y 492–610);
   panes ignored the side insets the keys honour. FIXED `8adc1a98` (mock fail-first → 14/14;
   device PASS on release e866a580: cancel and delete respond). Mock `ClipboardHistoryViewStateGuardsTest` fail-first → 28/28;
   instrumented `ClipboardLongEntryEditTest` 3/3 on emulator.wtf API 34 (`1812cdce`; post-fix only).
   Also fixed `c1bf9530`: images ≤ 80 px got no thumbnail ("Can't compress a recycled bitmap").
   1.x rows stored that way are repaired at service start (`ce96d38f`, ClipboardThumbnailRepair).
   (c) FIXED `84021ccb`: CTC had slots for the 26 a–z keys only, so bépo's TOP-LEVEL `é` read as
   b/p/u (`été` → `bcp`/`btp`) and QWERTZ `ü ö ä`, Swedish `å ö ä`, Spanish `ñ`, Swiss-French
   `è é à ê` were also outside the frame box. Those keys are now alias slots folded into their base
   letter (`CtcMappedLayoutBuilder`). Real FUTO German traces 81.46 → 83.75 % top-1, umlaut words
   53.6 → 77.0 % (`docs/eval/2026-10-25-ctc-accent-keys.md`); synthetic pins in
   `CtcAccentKeyLayoutReplayTest`. Geometric was already right. Left: `ß`/`æ`/`œ` keys still have
   no slot; `schön`/`schon`-type projection collisions keep only the more frequent form.
   (a) MEASURED, NOT CHANGED: `theyve` is in `en_enhanced.json` (159) with display `they've`
   (contractions_en + non_paired), pairing frequency 199. A clean synthetic QWERTY `theyve` trace
   decodes `they've` rank 1 on CTC (Python mirror of the shipped encoder + beam), 39/40 with
   per-key offset noise σ 0.025; geometric not measured. So no lexicon or display defect. The
   pronoun contraction keys sit ~40 below their pairing frequency (theyve 159/199, theyre 175/213,
   youve 169/206, weve 163/205); `they're` 8/40 and `you're` 5/40 lose to `there`/`your`. Raising
   them is a ranking change with no human evidence (FUTO corpora: 0 `theyve`, 1 `youve`, 1
   `weve` traces) → maintainer: a few real traces of the hard word would decide it.
   The checklist
   `docs/plans/v2.0.0-manual-test-checklist.md` was written and edited by agents only
   (maintainer, 2026-10-25): agents may edit it and should attempt every item.
   Original: ARC-053 daily-use soak of the minified release
   (`docs/plans/v2.0.0-manual-test-checklist.md` carries uncommitted maintainer edits — never
   edit or stage it) → ARC-063 narrow the blanket R8 keeps → tag decision.
9. [ ] agent does the objective ones (#90, #168, #145, both minimize styles) on test phones; maintainer only the feel checks (2026-10-25). pre-tag checks — #90 numpad-height cases, #168 `clear_clipboard` on a
   disposable clip, #145 cold start, both minimize styles, suffix/template/continuous feel.
   OBJECTIVE ONES DONE 2026-10-25 on the Pixel (release `ac86bda5`, APK sha256 `f78f048c…`,
   `build/oct25-pixel/`), all PASS, everything restored: **#90** Scale Numpad Height on → PIN
   and numeric pads exactly as tall as the text keyboard (key top y 1534 = 1534; off: 1526), a
   custom one-row `bottom_row="false"` layout stays one compact row, the same row with
   `numpad_height="true"` fills the full height (`49-`, `51-`, `54-`, `56-`). **#168**
   `clear_clipboard` (temporary g↗ mapping) after copying `throwaway168`: bar "System clipboard
   cleared", Ctrl+V pastes nothing, history keeps the entry (`82-`–`85-`). **#145** swipe typing +
   word predictions off, Restart Keyboard (pid 7504 → 22413), a custom short swipe (g↗ text) worked
   on the first try (`94-`). **Minimize**: `minimize_bar` → 30 dp strip, app resized, tap restores
   (`100-`, `101-`); `minimize_fab` → round button, app full screen, a tap elsewhere reaches the
   app, tap on the button restores (`102-`–`105-`). Left: the maintainer's feel checks only.
   Checklist pass (29 pass / 1 fail / 8 maintainer): `docs/plans/v2.0.0-manual-test-checklist.md`.
10. [ ] maintainer: ONE native-speaker review of every string added since 2026-09-29 in 21
    locales — command catalogue 465, popover 18, clipboard selection/bulk 25, clear-clipboard 4,
    #184 refusal, terminal packages, language binding, Oct 7 dialog strings, Oct 8 swipe
    priority 7 (`b2031f90`), Oct 10 legacy-frequency offer + lift 7 (`051dca51`), Oct 12
    editor-menu commands 4 (`a516f6ae`; 2 of them removed again on Oct 17), Oct 15 theme
    binding 10 (`e237fa95`), Oct 17 editor commands 16 new + 2 reworded (`d86f6606`), Oct 17
    `textAssistMenu` removal notice + "Replacement Suggestions (Editor)" name and description 3
    (`932207b0`), Oct 18 autocorrect "Ranking & Tuning" 46 + 2 plurals (`74c808e1`; written by
    an agent in all 21 locales, the English typo examples kept verbatim), Oct 21 clipboard
    swipe-to-delete 4 (#175; written by an agent in all 21 locales), Oct 21 Starting Letter
    Accuracy description reworded 1 (`63b1731a`, all 21 locales). Counts are the
    `<string` lines added to `res/values/strings.xml` per commit.
    (2026-10-22 audit `docs/audit/2026-10-22-translation-audit.md`: 64 mechanical defects fixed
    in `0a8fc939`, now blocked by `TranslationFormatParityTest`; back-translation found no
    meaning reversal. Review leads in its "native review" list: `cmd_clear_clipboard_desc`
    names pins/todos with words that do not match the tabs, es/hu `clipboard_selection_clear`
    reads as "delete the selection", fa example arrows reversed in RTL (device check), ru
    plural adjective case, tr/pl Backspace rendered as "undo".)
11. [ ] DECIDED 2026-10-25 (maintainer questionnaire): es LM → agent researches a new method
    and corpus, tries one or both if promising [DONE 2026-10-25: pre-registered R1 = unused 3.6×
    gate population UD AnCora test + R2 = es context-only `y a o e u` rows; R2 lost on both dev
    splits, C1 (retry model) FAILED AnCora test prefix-1 +3.64 < +5 → es stays unshipped;
    `docs/eval/2026-10-25-static-lm-es-prereg.md`; only UD Spanish-PUD remains unused]; FLOOR_ONE → maintainer asked for a fuller
    explanation (pending) [DONE 2026-10-25: maintainer-approved re-run pre-registered in
    `b85b8136` with a per-language rule on fresh UD PUD (it ParlaMint+Valico) + Leipzig 10K web
    populations, 118–150K positions each; pooled prefix-1 FLOOR_ONE−RAW en −0.09, de +0.36,
    fr +0.52, it +0.41, pt +0.46, sv +0.71 → de/fr/it/pt/sv use FLOOR_ONE in `static_only`, en
    keeps RAW (`StaticContextLm.shippedRatioShape`); `docs/eval/2026-10-25-static-lm-floor-one-prereg.md`;
    final, no second read]; finger_occlusion_offset → stays 0, no test needed (CLOSED); first
    Saga keeps CleverKeys as DEFAULT (CLOSED; it is default since 10-20); Text Assist with no
    process-text app → keep Android's own chooser (CLOSED). Original text:
    maintainer decisions: es LM needs a NEW stated reason; `FLOOR_ONE` for `static_only`
    needs a fresh pre-registration; nonzero `finger_occlusion_offset` only from device-trace
    A/B; keep or uninstall CleverKeys on the first Saga (installed, enabled, not default — the
    second Saga keeps it, 2026-10-11); Text assist when no app can take the text: since
    `e940262c` the bar says "Action unavailable" (the old `android.R.id.textAssist` fallback was
    a silent no-op) — decide whether that is final or the command should fall back to the
    editor's Replace popup as Replace text does; not device-checked on a phone without a
    process-text app. (Code reading 2026-10-22: the command always starts the trampoline, so
    with no process-text app Android shows its own empty chooser; "Action unavailable" comes
    only from terminal fields, an unreadable selection or a trampoline that cannot start —
    CHANGELOG corrected in `643cd3bb`.)
12. [ ] DECIDED 2026-10-25: stay at targetSdk 34 for 2.0; bump in a later release. targetSdk bump (still compile/target 34; the full instrumented suite is
    green on API 35/36/37, `6bdf282e`). Prerequisites found 2026-10-17: (a) targetSdk 35 forces
    edge-to-edge — only Settings and Launcher handle system-bar insets out of 15 activities;
    (b) targetSdk 35 needs `values-id` beside `values-in` (build.gradle TODO); (c) targetSdk 36
    makes predictive back the default — no custom back handling exists, so low risk, but every
    Compose dialog/activity back path needs a device pass; (d) targetSdk 37 can no longer opt
    out of large-screen orientation/resizability; (e) Android 18 drops implicit URI grants —
    both share paths are already explicit.
13. [x] CLOSED 2026-10-25 (agent; doc §3): the channel default fixes תושה→תודה (band: עושה), keeps `tfe`→the
    and every named case, and cuts the failure class 3–37× (fresh HE-adj4 633→117 pairs, fresh S-adj2 567→183
    of 6,000 eligible each); 4 GTC distant misspellings (compatability, releated, thet, choise) are a
    channel cost already counted in round-2 TEST. No ranking change; seam stays OFF (it is a band-only
    user setting now — deleting it is a maintainer UX call). 2026-10-26: setting, seam and eval DELETED (`2984b7da`; key in DEPRECATED_KEYS). Was: neighbour-substitution tiebreak — inside the ±0.10 score band a DISTANT
    one-letter substitution can beat a neighbour-key one on frequency (he תושה→עושה ת↔ע ≈4
    keys, over תודה ש↔ד; scores ≈0.89 vs 0.97). MEASURED 2026-10-12, NOT SHIPPED
    (`docs/eval/2026-10-12-neighbour-substitution-tiebreak.md`): "neighbour sub beats distant
    sub" fixes תושה but fails DEV (GTC-DEV N1 −31 distinct net, 60 breaks mostly real distant
    misspellings such as compatability→comparability; NA 135 breaks vs 167 fixes; both break
    the `tfe`→the calibration case) while gaining +848 S-adj words on synthetic neighbour slips.
    Open: a narrower pre-registered rule, or a phone-typing corpus to weigh the two error
    populations. Seam `NeighbourTiebreak` stays OFF.
13b. [ ] agent → device: noisy-channel autocorrect ranking is the DEFAULT since 2026-10-19
    (`353003c6`; `docs/eval/2026-10-18-channel-autocorrect-ranking.md` §6). The maintainer overrode
    the one failed TEST criterion, T6 (Hebrew HE-adj: 42 distinct breaks, net +270). Round-2 TEST:
    GTC 54.1 → 79.0 %, S-adj 67.7 → 95.2 %, Birkbeck +1,616 net, C-oov 0.23 %, all named cases.
    Shipped weights: s₁ 2, g 1.5, g₂ 1.0, τ 1.2, λ 0.005, β 0.25, γ 1. The band ranking stays
    selectable. Verified at `353003c6`: pure 3,040/3,040, mock 1,317/1,317, full instrumented
    suite 1,606/1,606 on emulator.wtf Pixel7 API 34 (`~/ew-output/oct19-channel/`, run e5fd3755).
    DEVICE PASS 2026-10-20, first Saga `192.168.0.202` (release from `54d5b3fa`, installed SHA
    `696a647a…`, `build/oct20-saga/`). CleverKeys was set as the keyboard temporarily, a Chrome
    textarea was used, and real key taps typed `devixe shated spaming donr helo` + space. The
    field read "Device shared spamming don't hello", and logcat showed `AUTO-CORRECT (channel)`
    for each. The new sliders render (Contraction Preference 0.25, Short-Word Deletion Cost
    0.00). The tab was closed, Gboard restored and the phone left on its home screen. Seeker
    skipped (secure lock screen), the Pixel skipped (in use), `192.168.0.81` skipped (same model
    as the Termux host). Device 2026-10-20 13:29–13:52 (first Saga `192.168.0.202`, now and
    henceforth CleverKeys as DEFAULT keyboard per the maintainer): foldable inner-screen sliders
    render disabled with the effective values and the "no fold" note (`build/oct20c-saga/`); QS
    tile on release `f47d5f3f` (`build/oct20d-saga/`): with the shade CLOSED (`cmd statusbar
    click-tile`) all five states work, but **opening the shade alone hides the IME on this
    Android 14 Saga** (control test: mInputShown=false after collapse, keyboard does not
    return), so a real shade tap lands on a hidden keyboard and does nothing; the tile is being
    reworked (remember the last visible state, apply on focus return). Pixel skipped: a pending
    Chrome "download CleverKeys APK" dialog and a screen-record indicator (not ours; untouched).
    Device 2026-10-20 15:36–15:41 (same Saga, release built from `4f70eb7e`, SHA `ee0cd58f…`,
    `build/oct20e-saga/`): **swipe into inline fields PASS**: a synthetic "the" swipe landed in
    clipboard SEARCH ("the", app textarea empty) and in the clipboard ENTRY EDITOR ("ck test
    entry" → "ck test entry the", leading space, app untouched), and one backspace removed
    " the". **QS tile rework**: PASS in a native field (Chrome omnibox) through the real shade,
    full → minimized → full; in a WEB textarea Chrome drops the page editor's focus, so our
    retried show is shown then hidden by the server (HIDE_SAME_WINDOW_FOCUSED_WITHOUT_EDITOR),
    a platform/web limitation; FAIL on the home screen (tile says "Minimize" and does nothing
    because the IME stays bound to the launcher's fallback connection and the visible flag is
    stale), and the tile agent is fixing it with decision logging. Full instrumented suite on
    `4f70eb7e`: 1,607/1,607 (`~/ew-output/oct20-merged/`). **#147 Short Swipes Only PASS** on the
    Saga (15:44): with the setting on, a long up-right swipe from `w` (far past Max Distance)
    typed "2" and a long east swipe (no subkey there) typed nothing; Max Distance showed
    disabled; the setting was turned back OFF afterwards. **#190 follow-finger PASS** (15:45):
    with Selection Movement = Follow finger, backspace short-swipe + hold, then a 128 px drag
    selected 4 characters, moving back 64 px shrank it to 2, release deleted exactly those 2;
    the mode was restored to Speed by distance. **QS tile home-screen fix PASS** (16:20–16:36,
    release from `f2ce7c88`, SHA `58089fa1…`, `build/oct20f-saga/`, tile log under
    `CleverKeys/Tile`): in a native field through the real shade, full → minimized → full;
    after Back dismissed the keyboard, a shade tap armed the deferred show, and retry #1's
    `requestShowSelf` brought it back (mInputShown=true); on the home screen the launcher's
    TYPE_NULL fallback connection is no longer read as an editor, so the tap chose
    PICK_KEYBOARD and the "Choose input method" picker opened. It opens beneath the shade and
    shows once the shade closes, the same as the pre-rework tile. The test tile was removed and
    CleverKeys left as the default. **#139 Enter always newline PASS after a fix** (16:37–17:05,
    `build/oct20g-saga/`): in a Chrome `<textarea enterkeyhint=send>` (inputType 0x2c0a1
    multi-line, imeOptions …04 SEND) the Enter key read "Go" — the modified-layout cache was
    keyed on Config.version, which a field start never bumps, so every field got the first
    field's label and swap. Fixed in `ea88d975` (cache key covers the per-field Enter state;
    `LayoutModifierCacheKeyTest` 2 new cases red → green). Release SHA `8b296e57…` on the Saga:
    textarea → "Send", omnibox (…02 GO) → "Go", settings search → "Done"; with the switch ON the
    textarea shows ↵ with Send on the corner and Enter typed a newline (`abc`/`xy`); switch turned
    back OFF, test tab closed, CleverKeys default. Full suites on `5df28cc5` (before the fix):
    3,135 pure / 1,361 mock green (`build/suite-oct20g/`); not re-run after `ea88d975` (one-file
    change, its class green). **#154 PASS** (17:06, same release): Accessibility shows "Use
    System Vibration" ON with the Custom Duration slider visible but disabled; OFF enables it,
    ON disables it again (left ON, as found). **#187 PASS** (17:07): with Macron Below and Ring
    Below enabled in Extra Keys (19 → 21 of 121) their text labels ◌̱ / ◌̥ render on the a / s
    corners; macron-below then `t` typed `ṯ` (compose table) and ring-below then `y` typed
    `y̥` (combining fallback); both keys unticked again (19 of 121). (Harness note: separate `input motionevent`
    calls take ~70 ms each, so a short swipe must be ONE big MOVE right after DOWN, or the
    backspace long-press repeat fires first.) (1) Hebrew prefix typo DEVICE PASS 2026-10-22
    on the Pixel (installed release 2026-10-20 17:08, contains `208457a0`; `langpack-he.zip`
    imported, a temporary Hebrew 1 layout bound to Hebrew): `לןא`+space → `ללא` (log `channel
    edit=1.254`, bar offered `לןא` to revert) and `טאת` → `ואת` (edit 1.222). Restored after: layout
    deleted, pack deleted, Multi-Language off again, pushed zip removed, 3 layouts as before;
    (2) Hebrew prefix-deletion class: round 3
    (short-word deletion cost δ, fresh HE-adj2 data, eval doc §7/§9) fixed 38 Hebrew words with 0
    breaks, 0 S-adj breaks and 0 C-oov changes, but failed E1 on GTC-DEV net −1 (8 fixes / 9
    breaks). The maintainer delegated the decision: E1 was overridden (§9.1) and TEST was run once
    at δ = 0.5 (§10). Result: Hebrew +51 / 0 breaks, S-adj +76 / 0, C-oov 0, but GTC-TEST −3
    (genuine English extra-letter typos `tpo`→to, `xit`→it pushed to 3-letter words). FAIL; δ
    stays 0 and was not overridden again. Round 4 (§11–§12) scoped δ to Hebrew script and ran
    on fresh HE-adj3 (ranks 2,501–6,000): +86 distinct, 0 breaks, English identical on 35,825
    inputs. PASS, so δ 0.5 + scope `hebrew` SHIPPED. Hebrew device check PASS 2026-10-22 (see (1)). Arabic prefixes: TODO pending an Arabic eval; (3) valid unlisted words: round 5 (§13–§15)
    shipped a maximum correction cost κ = 3.0. TEST: 43.8 % of the valid words the default
    changed are now protected (C-oov accuracy 26 % → 58 %), GTC −48 distinct (0.98 %), S-adj 0.
    PASS, DONE. Device 2026-10-20, first Saga (release from `4d1e8c10`, SHA `7ed139aa…`,
    `build/oct20b-saga/`): the field read "Device shared spamming don't hello nelsons
    tyrannicide". `tyrannicide` was kept (logcat: edit 5.497 > max 3.0); `keelsons` → nelsons
    (edit 2.40) was still changed, as expected at κ 3. Full instrumented suite at `4d1e8c10`
    (κ 3 + Hebrew δ): 1,606/1,606 on emulator.wtf Pixel7 API 34 (`~/ew-output/oct20-kappa/`, run
    f1aff4e2); pure 3,042/3,042 and mock 1,317/1,317. The settings group was device-checked on the second
    Saga, 2026-10-18 (`build/oct18-saga/01–10`; before the "Contraction Preference" slider).
13c. [ ] agent → device: code-quality audit 2026-10-20 (read-only report: session scratchpad
    `quality-audit.md`, 29 rows: 2 HIGH, 11 MED, 16 LOW). FIXED on local main: H1 inner-screen
    margins + M1 one height range (`35034af6`); M2 corrupt learned-data no longer reported as
    restored, M6 Layout Manager import/export errors surfaced, M7 continuous-swipe ownership from
    the snapshot, L1 dead plumbing (`b0ffc613`…`f3d5e4c9`); M3/M4/M5/M10/L12 one definition per
    default and M8/M9/M11 ranges in SettingsRanges only (`604ce902`, `ce79c881`), pinned by
    `DefaultsSingleDefinitionDriftTest` + `SettingsRangeDriftTest`. **H2 FIXED** (`ddac0605`): one
    map `Config.builtInThemeStyleId` for the keyboard view and ThemeProvider; reproduced on the
    Saga first (light mode + Monet (Auto): light keyboard, DARK Customize-key magnifier,
    `build/oct20g-saga/35.png`), light after the fix (`37.png`); the dialog's hard-coded white
    direction names then vanished and now take the theme sublabel colour (`22b35f71`,
    `38.png` light / `39.png` dark); theme, night mode restored. `ThemeStyleMapTest` 5 cases.
    Full suites at `22b35f71`: 3,135 pure / 1,368 mock green. Instrumented 1,607/1,607 (0 fail,
    0 skip) on the debug build of `bf4fbde4` (`~/ew-output/oct20-final/`, Pixel7 API 34).
    **Leftovers FIXED**: the slider off-by-one was systemic — about 40 settings sliders passed
    Compose the interval count as `steps` (stops between values; opacity moved in 0.99s);
    `SettingsSlider` now takes an `increment` and derives steps via `sliderSteps` (`e7c94474`,
    plus the two calibration sliders), border radius reads % in all 22 locales; private-copy
    defaults in `Defaults` (`cf395b12`). Pure 3,141 / mock 1,368 green; Saga release
    `ba230070…`: Long Press Timeout taps landed on 450 / 750 ms, restored 600. Not re-run on
    instrumented after these two; border-radius readout not device-checked (needs Borders on).
    Not changed: `ThemeProvider.getBuiltInColorScheme("monet")` (the Theme Creator's seed
    palette) is still the dark approximation. **Remaining LOW rows FIXED 2026-10-21** (worktree
    branch, not yet on main): L3 one recovery chain behind the four `safeGet*`, L4 sequential
    `migrate` steps, L5 one dp→% margin table, L8 `get_dip_pref` logs, L10 comments, L11
    parameter dropped, L13 `MarginPrefKeys` / `LanguagePrefs` / `SwipeEngineRouter.Mode.PREF_KEY`
    (`39fea185`); L6 `LanguagePrefs.userLanguages` for Dictionary Manager and WordPredictor
    (`6be16f9d`); L7 `swipe/UserLexiconInputs.read` (`90fa1685`); L2/L6/L7/L13 pinned by
    `PrefKeySingleDefinitionDriftTest` (`76b7e242`); L9 read-once locals, `NullAssertionDriftTest`
    (`6983eef0`); L14/L15 reader and ML cursors closed on failure (`94e96e05`); L16 device-protected
    prefs cached per Context (`aec68f92`); L8 rest, helper/preview logs (`0b3bb5ac`). Fail-first:
    `get_dip_pref` 0→2 warnings, ResourceClosingTest 7/7 red→green, DirectBootPrefsCacheTest
    51→1 protected-context creations, NullAssertionDriftTest 3/3 red→green; L3/L4/L5
    characterisation tests green before and after. Moot: L2 (the suffix `when` copies went with
    H1's `KeyboardHeightPrefs.followOuter`), L5's 0..80 clamp (aligned in `ce79c881`), L10's
    `SettingsValidation` "50" / `SettingsDefaults` "SettingsActivity" / KeyboardDimensionsHelper
    KDoc items (already fixed). Found: KeyboardDimensionsHelper's two height methods have no
    production caller → DONE `ef9de5ce` (2026-10-28): class deleted with its service field and
    wrappers, graph slot, ConfigPropagator slot/setter and proguard keep; DeadPlumbingDriftTest
    case red before, green after. KeyboardPreviewView's "jewel" fallback is unreachable while
    `globalConfig()` throws — LEFT AS IS (not trivially safe: making it reachable changes the
    preview colours; TODO stays in the class). Dictionary Manager tab for a layout-bound language
    → DONE `8cd92dff`: Active / Disabled / Custom per bound language (`DictionaryTabPlan`, pure
    test 4 cases red → 8/8 green); Saga 144 PASS (System layout bound to sw: Active [SW] 17,455,
    Disabled [SW], Custom [SW]; binding restored to Follow).
    Pure 3,178 / mock 1,426 green at `0b3bb5ac`; not run on instrumented or a device.
14. [ ] agent drafts every close comment / PR review into a file; maintainer approves per item before anything is posted (2026-10-25). GitHub — 38 DONE/FIXED close candidates + 2 NOT-REPRO/BY-DESIGN, PRs
    #189/#183/#182 review and #164 close as superseded (`docs/audit/gh-issue-resolution.md`).
    Drafts done (`docs/audit/2026-10-25-github-drafts.md`, 48 items). DECIDED 2026-10-26: hold every
    draft until v2.0.0 is tagged, then the maintainer reviews them (re-check each against the tag first).
15. [ ] maintainer: deferred review items — summon after a system hide does NOT need the overlay
    permission (checked 2026-10-22, AOSP 9–16 sources + Saga runs; no code changed):
    `requestShowSelf` → IMMS `showMySoftInput` checks only the IME token and WM shows the IME for
    the last IME-target window; `SYSTEM_ALERT_WINDOW` gates `TYPE_APPLICATION_OVERLAY` only. The
    shade (`FLAG_ALT_FOCUSABLE_IM`, layer 17 over IME 13) never reaches IMMS: over CleverKeys'
    own Settings field it did not hide the keyboard at all, and on 10-20 a tile tap under the
    open shade reshowed a Back-dismissed Chrome keyboard in 7 ms; the 10-20 "shade hide" was
    Chrome hiding its own keyboard. The shipped deferred-show tile is the cheapest approach
    (API 24+). Only a #175 button that outlives the IME window needs the overlay (plan 4.3).
    Details and KDoc/spec wording follow-ups: `docs/specs/keyboard-minimize.md` "Overlay
    permission check". The suffix receipt readback (3 editor reads per
    committed word) is KEPT BY DESIGN (2026-10-21): the # TODO in `rememberVerifiedWord` records
    why deferring it breaks the cursor-location proof; reopen only with a profile showing jank. #175 clipboard swipe-to-delete is BUILT and merged (`2787468d`, 2026-10-21; Saga 202 release
    `fe91c783…` PASS: left swipe hides the row with "Deleted" + Undo, Undo at 1.2 s restores it,
    a slow 20 % swipe springs back, swipe then keyboard hide commits at once (log PANE_HIDDEN;
    reopened pane empty), an unattended row commits on TIMEOUT; NOT yet checked on device:
    TalkBack and the Todos tab only. TODOS tab PASS 2026-10-25 (Saga 202): left swipe → "Deleted"
    + Undo, Undo restores; right swipe + Undo restores; unattended row commits on TIMEOUT at 5 s.
    TalkBack: not drivable over adb (item 3). Later the same day PASS too: a mostly vertical drag scrolls
    with no swipe; right swipe + Undo; tap still expands; long-press still copies; a Pinned-tab
    swipe removes only the pinned copy (History keeps it, commit on tab switch); with the setting
    OFF a swipe is ignored; test clips removed, setting back ON): horizontal swipe on a normal-mode row of any tab → row hidden, "Deleted" +
    Undo for 5 s, real delete through `removeFromTab` on timeout or any commit trigger;
    setting `clipboard_swipe_delete_enabled` (default on); TalkBack "Delete" action; pure
    `ClipboardSwipePolicyTest`/`ClipboardPendingDeletionTest`, mock
    `ClipboardSwipeDeleteViewTest`/`ClipboardSwipeDeleteManagerTest`. → device: swipe left and
    right on each tab, Undo, short slow swipe springs back, vertical scroll unaffected, taps and
    long-press unchanged, window expiry, the commit triggers (close pane, switch tab, type a
    search), setting off, TalkBack action; strings in 21 locales join item 10's review.
16. [x] agent: `autocorrect_prefix_length` — measured 2026-10-21 (descriptive,
    `docs/eval/2026-10-21-autocorrect-prefix-length.md`): prefix 1 vs the shipped 0 under channel
    ranking costs GTC 79.0 % → 77.8 % (553 targets broken, 305 fixed) and S-adj 95.4 % → 82.7 %
    (first-letter slips can no longer be corrected). Default 0 stays. Description reworded in all
    22 locales (maintainer approved 2026-10-21; translations mine → string review, item 10);
    C-oov and prefix 2 not run (stopped: held the Gradle lock 3 h).
17. [ ] RTX: short-word CTC — word-balanced fine-tune on the raw-path input contract so
    dwell-less `ad`/`wet` decode, gated on fresh writer/session-separated human traces
    (`docs/eval/2026-10-07-short-word-ctc.md`, `…final-letter-drops.md` §6).
18. [ ] RTX: apostrophe context chooser — listed-evidence its/it's variant in its own
    pre-registered round on fresh data (`docs/eval/2026-10-07-apostrophe-context.md`).
19. [ ] RTX: Bangla spelling-preserving dictionary + mark/conjunct geometry, then a validated
    model/pack (`docs/guides/adding-a-new-language.md`); ARC-061 `ctc_golden` fixture fix.
20. [ ] unowned roadmap: 1.3 at-rest clipboard encryption, 2.3 dwell picker (measure first),
    4.2 spline swipe trail (benchmark first), #187 below-accents, #175(3)/#80(3) toolbar,
    4.3 opt-in floating quick-action button (future release; overlay permission requested only
    on opt-in; maintainer 2026-10-22).
    (4.1 live theme preview shipped 2026-10-08, `70b94c11`.)
21. [ ] Final QA pass 2026-10-25 (agent, HEAD `2cf8338d`; evidence `build/oct25-pixel/`, not in git).
    Pure 3,225 OK, mock 1,488 OK. lintDebug: main-source analysis 0 errors / 228 warnings;
    `lintAnalyzeDebugAndroidTest` and `…UnitTest` OOM on Termux at 1.5, 2.5 and 3.5 GB heap
    (open: let CI's lintDebug cover test sources after the next push). Release arm64
    25,294,803 B, sha256 `f16a01b6…cf81a2`. ew-cli unfiltered, 3 shards (app `40003054…`,
    test `c70f2937…`): 1,609/1,610 on API 34, 36, 37; 1,606/1,610 on 35. The 1 shared failure
    was the stale `TermuxDeletionInstrumentedTest` count after `691aa8cf` (harness, fixed in
    `edd96cd9`, 7/7 on 34–37). The 3 extra API 35 failures (2 SettingsSearchTest process kills,
    1 ProcessTextResult connect timeout) passed on rerun (SettingsSearch 7/7, ProcessText 3/3).
    Upgrade: a throwaway instrumented test drove the PRODUCTION `onUpgrade` on v1.5.0-shaped
    (v4) and v1.3.0-shaped (v2) files: 2/2 on 34–37 (schema and indexes equal a fresh v5, rows
    and media columns read back). The committed `ClipboardDatabaseV5MigrationTest` mirrors the
    ALTERs instead of calling `migrateV4toV5`. Real 1.x-APK → 2.0 over-install DONE later that
    day on emulator.wtf (`tools/upgrade-test/`, `e2007ffb`/`e0c95c87`, recipe in
    `.claude/skills/ew-cli-testing.md`): v1.5.0 debug seeded by its own code → `pm install -r`
    2.0 debug (`81d65663`) → 45 checks, then backup → uninstall → fresh → import → 36 checks,
    0 failures on API 34, 36, 37; GitHub v1.5.0 release → same-key 2.0 release over it: update
    accepted, IME binds, no crash. FIXED 2026-10-26: debug and release could not coexist
    (fixed-name `SET_DEBUG_MODE` permission from `9c799506` defined by both differently signed
    packages); the name is now `${applicationId}.permission.SET_DEBUG_MODE` (release name
    unchanged, aapt2 dumps share no permission/authority). PHONE CHECK PASS 2026-10-26 on Saga 144: the `bd4ec789` debug APK
    installed beside release 2.0, each defining its own SET_DEBUG_MODE; debug then uninstalled, release stays the
    default IME. Pixel: Play Protect blocks any debug build ("Harmful app blocked", dialog dismissed, nothing installed). 1.x small images keep no thumbnail
    after the update — FIXED `ce96d38f` (startup repair; upgrade rerun 2026-10-26 on API 34/36:
    47 + 37 checks, 0 failures, small-image thumbnail PASS, `0a923417` verifier). Pixel (release installed, sha matched): theme edit with keyboard hidden applies
    without restart PASS (`d85be0dd`); bépo `été` synthetic swipe gave `Ecb` (coarse, 2.3 s,
    not evidence); long Pinned-entry editor NOT run (a Pinned entry can only come from history,
    i.e. the OS clipboard; the instrumented test covers History/To-dos caret, Pinned spell-check
    only). Left: CleverKeys default, Cyberpunk, layouts and themes restored.
    FULL INSTRUMENTED 2026-10-27 at `0c3a2f56` (agent, separate worktree, 4 genuine `ctc_bench`
    encoders, hashes = testing-strategy table; app `8637b738…`, test `44bcfd2f…`): unfiltered,
    3 shards, orchestrator, Pixel7 — 1,623/1,623 on API 34 (`e7cbf97c`), 35 (`01297cd7`),
    36 (`f2e3288c`) and 37 (`764b3dce`); 0 failed / 0 skipped / 0 flaky, 0 AssumptionViolated.
    First cloud runs of ClipboardReorderDatabaseTest (6), the 9 KeyboardAccessibility cases on
    35–37 and ClipboardDatabaseTest (110, paged editor). Looks/StyleLayouts and the thumbnail
    repair have no dedicated instrumented test (covered by pure tests and the upgrade test);
    the looks code ran only inside the keyboard-view tests. Evidence `~/ew-output/full-0c3a2f56/`.
22. [ ] Saga 202 backup round trip + real 1.x upgrade, 2026-10-25 (agent; evidence
    `build/oct25-saga/`, original backup in `backup-original/`, not in git). Plaintext full ZIP,
    the same ZIP twice, encrypted `.ckenc`, clipboard JSON and ZIP, config Merge/Replace: every
    seeded row identical after uninstall → reinstall → import (diff of re-exports incl.
    timestamps), no duplicates; the private entry survives only the encrypted backup. v1.5.0
    GitHub APK → local 2.0 release `adb install -r` works (one signer `3c70de88…` for GitHub,
    F-Droid `AllowedAPKSigningKeys`, `release.yml` and local builds); 1.x data identical after.
    Fixed `bf20161d` (short-swipe preview showed Replace's removals under Merge/Skip). Open, not
    fixed (other owners active): (a) FIXED `30c6cc6f` + `d5b825d3`: a 524,000-char entry hit
    FAILED BINDER TRANSACTION (parcel 524,156) as one `commitText`; `LargeTextCommit` now sends
    32 Ki-char chunks in one batch edit (surrogate-safe, a refused chunk retried in place, then a
    bar message) from the pane, the terminal/blind-field paste key and paste_pinned_N. Saga, release
    `73fb0d8b…`: Chrome textarea got len 524,000 and the expected checksum, no binder failure; Chrome's
    renderer then took ~6.5 min of CPU to lay out the text (keyboard not blocked). A first
    version's `getTextBeforeCursor` barrier is never answered by Chrome inside a batch edit (15×2 s
    main-thread stalls) and was removed. Chunk size measured 2026-10-26 (Saga 202, `cb6c111e`,
    `docs/eval/2026-10-26-large-paste-chunk-size.md`): chunks are now 192 KiB of wire bytes, not
    32 Ki chars; Hebrew 260K 54 → 29 s, emoji 0.7 → 0.3 s, the 13,100-line 524K entry 352 → 338 s
    (Chrome handles each newline as its own edit; no chunk size fixes that), no binder failure.
    Pane freeze FIXED `5c97417e` (2026-10-26, Saga 202, evidence `build/oct26-saga202-preview/`):
    rows bound the full text; now `ClipboardPreviewPolicy` previews (collapsed/Select 300 chars,
    expanded 20,000 + "Showing the first N of M characters…", no inline edit past that).
    Main-thread CPU, release: pane open 41.7 → 0.1 s, enter Select 51.4 → 0.05 s, leave Select
    52.1 → 0.05 s, expand ≤ 0.5 s, search keystroke ≤ 0.06 s; a query for text past 520,000
    chars still matches; emoji entry pasted with exact length + checksum; no ANR. Maintainer request
    "facilitate editing of 20k+ char entries" DONE `14277584` 2026-10-26 (Saga 202, release `36970673…`, evidence
    `build/oct26-saga202-paged/`): such entries are edited a page at a time (`ClipboardEditPages`,
    "Page N of M" + prev/next, long-press first/last; unsaved edits kept across pages; Save = one
    update of the spliced text; a row changed meanwhile → `EntryChanged`, nothing written). Per key
    tap 20–70 ms main-thread CPU on the 520K entry; History (pages 1, 14, last), Pinned and To-do
    edits pasted into Chrome with exact length + checksum. Not device-checked: the conflict message
    (no UI path changes a row behind an open editor: import only adds, expiry is >= 60 min; pinned
    by `ClipboardPagedEditViewTest`), TalkBack labels. Landscape, Saga 144 2026-10-27 (evidence
    `build/oct27pm-saga144/`, not in git): 47K-char entry opens on "Page 1 of 3" with Save/Cancel
    and Delete; a drag inside the 2-line capped field scrolled the PANE (pager and Save/Cancel out
    of sight) — FIXED `612356f2` (field keeps its drag; fail-first `ClipboardPagedEditViewTest`),
    re-verified on release `16b4a500…`: the field's text scrolls, the pane stays. Pager clipped on
    open (about half a line) FIXED `5b88a684` 2026-10-28: the cap ignored the field's 14 dp top
    margin, and the Saga's landscape pane (~204 px) cannot hold pager + margin + the field's
    two-line minimum anyway, so after focus the pane is scrolled back to the pager row
    (`revealEditPager`). Fail-first: `ClipboardPagedEditViewTest` cap cases red; device: release
    `3cf3738a` (cap only) still cut it (`build/oct28-saga144/19`), `fdf697f6` shows the whole row
    on pages 1 and 2 (`29`, `30`). 47K test entry imported via Import Clip, deleted afterwards. (b) FIXED `cf74c393`: history-change callbacks from IO
    are posted to the main thread, a listener failure is logged with its stack; Saga: image
    copied with the pane open → pane shows it, no "Loading results…", no error (both reproduced
    on the old build first; evidence `build/oct25-saga/clipfix/`). Instrumented tests adjusted
    for main-thread delivery, not run. (c) FIXED `61c5f273` + `4b93c3c2` (Saga 144,
    2026-10-25): the hex field rewrote `#FF0000` to `#FFFF0000` mid-entry (resync compared
    against the 8-digit spelling; now `theme/HexColorText`, and the picker keeps the exact typed
    ARGB and scrolls in landscape). Device: `#FF0000` and `#80FF0000` typed char by char, middle
    edit `#80FF0000`→`#80FA0000`, text and caret kept; (d) FIXED `4d69ee7e`: "Use This Text" is in
    the palette header (the IME stays attached to the activity behind it, so the dialog got no
    resize or IME insets, and panning hid the header); landscape + portrait checked, a scrim tap
    still dismisses; (e) FIXED `e9c0802e`: `custom_themes` section in the settings export and
    full backup, per-theme preview rows + Skip/Merge/Replace, themes written before the settings
    commit. Device: DIY theme selected → full backup → theme deleted → import → "Custom themes
    restored: 1", theme back and selected; then CleverKeys Dark restored, test theme and ZIP
    deleted. Restored: release `f34a977c…` (HEAD `bf20161d`) default IME, original
    backup imported, 174 prefs equal (only `clipboard_history_limit` int 0 → String "0"), 0
    words, 0 clips, 19/121 extra keys, CleverKeys Dark; test files deleted from Download.
23. [x] agent, 2026-10-26 (Pixel 8 Pro, release `dd304fb1…`; evidence `build/oct26-pixel/`, not
    in git). (b) Export result dialogs said "File: 932" (`uri.lastPathSegment` = the Downloads
    provider's document id): `02729472` resolves `OpenableColumns.DISPLAY_NAME` through one helper
    (`ui/settings/io/DocumentDisplayName.kt`) for the settings, full backup, dictionary and
    clipboard JSON/ZIP dialogs; device: Export Config → "File: cleverkeys-config (4).json" (the
    provider's rename shown), test file deleted. Not changed: `BackupRestoreManager`'s
    "Cannot read file: …" exception text still uses the segment (file had another session's
    uncommitted edits). (e) Maintainer request "Browsing learned words and phrases needs
    pagination and search": `4fe24f12` + follow-up fix — search field (settings-search fold:
    case, accents, ZWNJ, Arabic yeh/kaf), 100-row pages loaded on scroll, "N words/phrases" or
    "N of M", query and dialog survive rotation, 200-row cap gone. Device: "hel" → 4 of 102,
    landscape keeps the query, all 102 words reachable by scrolling, phrases show 235 (was capped
    at 200). The first build crashed the process on the first keystroke (stale page size vs a
    shrunken match list, `IndexOutOfBoundsException: toIndex = 100`); fixed by
    `LearnedBrowsePolicy.page`, pinned in `LearnedBrowsePolicyTest`. No learned entry was deleted.
24. [ ] agent, 2026-10-27 (maintainer request "todo items in clipboard should be rearrange-able";
    second Saga 192.168.0.144, release `efe7723d…`, evidence `build/oct27-saga144-reorder/`, not in
    git). Pinned and To-dos rows reorder by a drag handle or TalkBack Move up / Move down / Move to
    top: `32f3c2c1` (feature), `00f36502` (short landscape pane auto-scroll; stale filter hint),
    `5176889e` (end slots reachable; opaque lifted row), `14ad281c` (build.gradle cleanup), `fe67bd29`
    (availability tolerates Objenesis test views; not in the device APK, no runtime change). Design
    and thresholds: `docs/specs/clipboard-system.md` "Reordering Pinned and To-dos rows". Device
    PASS: handle on To-dos, drag drops and persists across pane close/reopen and APK reinstall,
    auto-scroll in the ~195 px landscape pane both ways (first and last slot), dimmed handle +
    hint while searching (hint clears with the search), no handle in Select mode, vertical
    scrolling and swipe-to-delete unchanged (the five throwaway to-dos were swiped away; History,
    Pinned and To-dos back to 0 / 0 / 0 as recorded before). Open: (a) TalkBack moves not
    driven on a device (adb cannot run custom actions) — pinned by `ClipboardReorderViewTest`;
    (b) `ClipboardReorderDatabaseTest` (instrumented: SQL, renumbering, backup round trip) not
    yet run on emulator.wtf; (c) DONE 2026-10-27, Saga 144 release `6bc336b2…` (acd3b79e): four
    imported Pinned rows, row A dragged by its handle below B, order kept after pane close/reopen;
    rows swiped away, History / Pinned / To-dos back to 0 / 0 / 0.
25. [ ] agent, 2026-10-27 (maintainer: the familiar-style layouts should also bring the
    product's LOOK, so a space-bar swipe turns the keyboard from Gboard to SwiftKey to iOS).
    BUILT `ddefd3bd` + `fc30948d` + `d713457c`: keyboard looks (`look_` themes: light/dark
    palette following the system + geometry; spec `docs/specs/layout-system.md` "Keyboard
    looks", research `docs/research/2026-10-27-familiar-keyboard-looks.md`), iOS-, Gboard-,
    SwiftKey-, Samsung- and iOS 18-style; the four style layouts bind theirs via `theme=`; new
    QWERTY (Samsung-style). Full suites at `d713457c`: pure 3370 OK, mock 1573 OK. Device
    (release `fc30948d`, APK sha256 `70b60905…`; evidence `build/oct27-looks/`, not in git):
    Pixel 8 Pro and Saga 202, the four layouts added, cycled by space-bar swipe in dark and light
    — every switch re-themes the keyboard; `Theme rebuild` 2–6 ms per switch (logcat); side by
    side with the real Gboard and SwiftKey on the Pixel (`compare-*-{light,dark}.png`). Restored:
    layouts (Pixel System/Dvorak/QWERTY US, current QWERTY US; Saga System), night mode yes,
    CleverKeys default IME, test tabs closed. LEAD REVIEW round (same day): looks now decide
    which corner labels are drawn (`LookLegendPolicy`: Gboard top-row digits only, the others
    none; space glyph hidden), Appearance "Show gesture hints on familiar looks"
    (`look_show_hints`, default off) restores all; Gboard ?123/Enter are pills (`LookShapes`);
    Gboard-style layout gained its own emoji key, SwiftKey-style lost its number row (Pixel
    references). Re-captured `build/oct27-looks/v2-*.png` (Pixel + Saga, light/dark, Gboard and
    SwiftKey comparisons); both phones restored again. Open: (b) Gboard-style uses one fixed
    Material You palette, not the wallpaper's (could read the system dynamic colours on API 31+);
    (c) no key-pop balloon; Gboard's emoji key draws function-coloured (real: letter-coloured);
    SwiftKey's mic/!? hints on comma/period are not drawn; (d) Samsung and iOS
    looks have no on-device reference capture (no Samsung Keyboard on the test phones, no
    iPhone) — values come from the shipping app's own resources and from measured recreations.
    ROUND 2 (2026-10-28, maintainer): (b) and (c) addressed — Gboard-style follows the system
    wallpaper palette on API 31+ (`LookDynamicColors`, Gboard's role mapping, verified against
    the real Gboard on the Pixel, seed #5D646B; fixed palette below 31 or on a contrast miss),
    its emoji key is letter-coloured (`KeyRole.LETTER`); every look draws its product's
    key-press preview (iOS balloon, Gboard bubble, SwiftKey column, Samsung plate;
    `LookPreviewPolicy`, new pref `look_key_preview` default on) and styles the long-press
    popover; look hints use `LookHintStyle` (size, weight, corner inset clear of pills). Research
    §9, spec "Keyboard looks", theme skill rule 13. Evidence `build/oct28-looks2/` (not in git).
    ROUND 3 (2026-10-27 pm, agent, Pixel 8 Pro, release `acd3b79e`, then `4fdd840a` from a private
    worktree, sha256 3c267a5d…; evidence `build/oct27pm-pixel/`, not in git): key-press previews
    PASS light + dark (iOS balloon, Gboard bubble as in the real Gboard capture, SwiftKey column,
    Samsung plate); the long-press popover takes each look's panel colours in both modes (subkey
    popover switched on for the check, restored OFF); Gboard-style follows the wallpaper palette
    (light function keys #D3E5F5 = the real Gboard's) and its emoji key is letter-coloured PASS.
    "Show gesture hints on familiar looks" ON, all four looks both modes: hints stay inside the
    ?123/Enter pills (no clipping) PASS, but on the 1-wide emoji / comma keys the bottom legend
    "ABC" runs into "GIF" (Gboard-, Samsung-style) — open; restored OFF. Shift tint CONFIRMED
    (auto-cap Shift drew the pressed grey, real Gboard keeps #2D3D4A) → FIXED `da6a6d9a` +
    `4fdd840a`: LookPalette.latchedKey/latchedLabel, a LATCHED frame; Gboard keeps the
    function-key fill, Samsung the fill with its blue icon (device-verified, both modes). Open:
    no ThemeSpec colour role carries the latched colours (needs an editor label in 21 locales;
    TODO in ThemeSpecs.render). SHOWCASE `build/oct27pm-pixel/showcase/` (four looks × light/dark
    under a chat field with suggestions, plus grid-light/grid-dark). Structural differences left:
    the bar shows 5 scrolling chips with the commit candidate first (the products: 3 slots,
    centre = commit; iOS quotes the typed word); no toolbar row; system font, not SF / Google
    Sans / Samsung One; Gboard's filled Shift glyph. FOUND, not fixed (other owners): space
    would commit `timor` for `tomorr`, `car` for `caf`, `rest` for `resta` (bar highlight,
    `build/oct27pm-pixel/20-autocorrect-tomorr-timor.png`); after a night-mode switch the keyboard
    came back shorter (row pitch 151 → 137 px, same in both modes afterwards) — FIXED `bd756a79`
    (2026-10-27, agent): `Config.refresh` sized against `resources.displayMetrics.heightPixels`,
    which on Android 15+ is the full display at service start and the display minus the system
    bars after the first configuration change; now `ScreenSize` (maximum window bounds).
    Reproduced fail-first on emulator.wtf API 36 (`NightModeKeyboardHeightInstrumentedTest`: row
    164.05 → 159.49 px, metrics 2400 → 2337); fixed build passes on API 34/35/36. Side effect:
    API ≤ 34 now sizes against 2400 not 2337 (≈2.7 % taller). Not device-checked (LAN down).
    PIXEL NOT RESTORED YET: this box lost Wi-Fi at 16:12 mid-check (still down 17:00). Left on the
    phone: night mode NO (was yes); the four style layouts still added (was System / Dvorak /
    QWERTY US, current QWERTY US); the Chrome test tab open (7 tabs before); release `4fdd840a`
    installed. Already restored: subkey popover OFF, look hints OFF; font_scale untouched (1.15).
    ROUND 4 (2026-10-27 evening, agent; NO PHONES — LAN down; emulator.wtf + JVM only):
    SUGGESTION BAR per look `9828a99d`: `LookBar` (3 slots; best = highlighted = what space commits,
    centred; typed word or "+word" chip left of it, quoted on iOS-/iOS 18-style; separators by
    the look's colour between filled slots; prompts unarranged; non-look themes unchanged).
    Drawing only — ranking order, highlight index, space commit and the commit path untouched;
    each slot taps its own entry (instrumented). Research §10, spec "Keyboard looks", theme skill
    rule 16. "ABC" × "GIF" hint overlap FIXED `35fe062a` (`SubLabelSizing.bandScale`; red then green
    on emulator.wtf). Theme Manager custom-card light colours in dark mode and the Theme Creator
    preview drawing a styled layout FIXED `58e86ea0` (item 27 findings). Latched Shift colours now
    spec roles `latched_key` / `latched_label` `9828a99d` (the 4fdd840a TODO; editor labels in 22
    locale files, machine-drafted, native review owed). Showcase `build/oct27-bar/`
    (`{gboard,swiftkey,ios,samsung}-{light,dark}.png`, `grid-{light,dark}.png`; not in git).
    Full suites: pure 3444 (1 failure fixed: androidTest harbour copy) and mock 1611 (15 bar-stub
    failures fixed: 5-argument call), reruns green. Open: device pass of the bar (taps, TalkBack
    order, landscape width, long words ellipsize); entries past the third are not shown on a
    look; the Theme Creator does not edit the bar arrangement (XML / JSON only).
    ROUND 5 (2026-10-27 night, agent, Pixel 8 Pro; evidence `build/oct27-night-pixel/`, not in
    git; showcase `showcase/`): device pass of the bar, real Gboard + SwiftKey captured for the
    same scene (light + dark), CleverKeys restored as IME after each. The real products show an
    unknown prefix PLAIN (`tomorr | tomorrow | tomorrow's`), Gboard bolds the centre only for a
    pending correction (next word `at | morning | and`, none bold), SwiftKey always inks the
    centre and dims the sides, and both shrink / middle-elide long words. FIXED `8e449574`: a
    look's slot shows the plain word (the "+word" tap still commits as typed + adds to the
    dictionary — kept, the products learn a tapped word too; plain bar unchanged),
    `LookBar.emphasis` COMMIT / CENTRE (`bar_emphasis`), next-word bars have no commit slot,
    slots auto-size to 11 sp and elide the middle. LookBarArrangementTest red → 17/17,
    LookSuggestionBarInstrumentedTest 6/6 (ew API 34). Taps PASS on device: left, centre and
    right slot commit their own word, space commits the centre (`teh` → `the`). Landscape: three
    slots span the bar, fine. Release `8e449574` (sha256 1286b85a…) left installed. Open /
    structural: no toolbar row (Gboard grid + mic, SwiftKey toolbar + chevron); system font; the
    centre for `tomorr` is `timor` on this build (ranking, other owner); the plain (non-look)
    bar still highlights the first next-word chip though space types none of them. Restored:
    layouts System / Dvorak / QWERTY US (portrait current System, landscape QWERTY US), night
    yes, Next-Word Prediction OFF (was on only for the captures), theme Cyberpunk, look hints
    OFF / key preview ON untouched, rotation auto, 7 Chrome tabs, launcher.
    ROUND 6 (2026-10-28, agent, Pixel): editor-action key per product `cd69b918` — real Gboard
    and SwiftKey captured for every enterkeyhint (go/search/send/next/done/previous/enter), light
    + dark. Gboard: icons → ⌕ ➤ →| ✓ |← on the FUNCTION pill (never primary; corrects research
    §2a); SwiftKey: ✓ (Go) ⌕ outlined plane → ↵ ↵; iOS-style blue + → ⌕ ↑ ✓ (research §1a), iOS
    18-style lower-case words; plain Enter a thin ↵ on the three icon looks. CleverKeys' own line
    icons (`LookActionKeys`), no product artwork. Samsung-style UNCHANGED (no Samsung Keyboard to
    check — open). LookActionKeysTest red → 8/8, runPureTests 3457 OK, runMockTests 1611 OK.
    Evidence `build/oct27-night-pixel/showcase/action-keys-{light,dark}.png`. Release `cd69b918`
    installed; Pixel restored (3 layouts, night yes, next-word OFF, 7 tabs). Waiting: re-shoot the
    bar showcase once the `tomorr` → `timor` ranking fix lands on main.
26. [ ] agent, 2026-10-27 (maintainer: "make it so layouts can override/set layout-specific key
    gap and border, so user setting acts as global default"). BUILT: per-layout key geometry
    (`LayoutKeyGeometry`, `KeyGeometryField`; spec `docs/specs/layout-system.md` "Per-layout key
    geometry", theme skill rule 14, settings skill): horizontal/vertical gap, border on/off,
    radius, line width per layout, each absent = XML `key_*` default, `"inherit"` = global, or a
    value; precedence per-layout > XML > look > global; Layout Manager "Key spacing & borders"
    chip + editor; Export XML; backup preview lists changes; strings in 22 locale files.
    Device: Saga 144, release `592b5b3f…` — built before two later changes that only tests cover:
    a layout's border OFF under a global custom border, and the frame-cache invalidation moved
    from a live Config read in `Keyboard2View` (forbidden by `ConfigSnapshotRatchetTest`) to a
    snapshot-version bump in `set_current_layout`; `build/oct28-saga144-geometry/` (not in git): System ↔ Colemak bound to
    iOS-style with gap 5 %, border on, radius 20 %, width 3 dp — overrides drawn over the look,
    System back to global, `Theme rebuild` 3 ms per switch, Appearance prefs unchanged (1.5 % /
    2.0 % / custom border off); Colemak removed, layouts back to [System], CleverKeys default.
    Light-mode / border-OFF pass 2026-10-27 (Saga 144, release `6bc336b2…`, evidence
    `build/oct27pm-saga144/`): Colemak with gaps 5 % / 4.25 % and border OFF under a temporary
    global custom border 3 dp — Colemak drew the theme frame, System the custom border, globals
    unchanged; BUT the gaps drew like the global 2 % / 1.5 % (~10 px): XML themes fixed
    `Theme.density` at 1 — FIXED `32c8c4bb` (fail-first `LayoutKeyGeometryConfigTest`); release
    `6e98ada2…`: 27 px / 12–13 px vs System 11 px / 4–5 px. Restored: Colemak removed, layouts
    [System], Appearance 2.0 % / 1.5 % / custom border off (width 0 dp), night mode yes. Add Layout
    landscape: all 88 Predefined layouts reachable by scrolling (acd3b79e). Was open: the Add Layout
    dialog's Predefined list does not scroll in landscape on the Saga (pre-existing, only Colemak
    and QWERTY US reachable) — FIXED 2026-10-28, item 27.
27. [ ] agent, 2026-10-28 (maintainer: "fully define a custom layout incl. custom theme with
    per-key gap and background/border from an XML config file — and save/export theme from one";
    "export theme meaning it will show up in theme editor as a custom theme"). BUILT: layout
    `<theme>` (every DIY + look colour, `<light>`/`<dark>`, geometry, hints, pills, preview,
    dynamic colour; partial over the global theme; precedence user > XML `theme=` > `<theme>` >
    global), per-key/row styling (`KeyStyle`; key > row > per-layout > XML `key_*` > look >
    global; frames centred on cells, swipe/hit-test unchanged), custom themes format version 2
    (`extension`, backups keep it), Layout Manager "Save theme as custom theme", Theme Manager
    "Export as layout XML", Export XML can embed the bound custom theme, Theme Creator "Keyboard
    look" section, Add Layout landscape fix; strings in 22 locale files; wiki
    `layouts/layout-xml-reference.md` + example `layouts/examples/harbour.xml`; spec "Layout
    themes and per-key styling", theme skill rule 15. Instrumented on emulator.wtf API 34 (red
    run before the UI: landscape "saw 2", save button absent). Open (device): the example on a
    phone in light AND dark mode (variants, pill Enter, bubble preview, pressed Enter colours),
    Save → Theme Manager → Creator edit → keyboard, Theme Manager export on a look and paste
    into a new layout, Add Layout in landscape on the Saga, a Theme Creator pass of every new
    control (sliders, chips, variant rows), Persian/RTL rendering of the new strings.
    DEVICE 2026-10-27 (Saga 192.168.0.202, APK acd3b79e sha256 6bc336b2…, portrait; evidence
    `build/oct27pm-saga202/`): (1) harbour.xml light PASS (variants, pill Enter, bubble preview,
    pressed Enter #174F80, f/j, row styling, digit hints); dark FAIL → fixed: the third row
    #F4F7FA took dark-mode label #E6EEF5 (1.09:1) and Shift/Backspace drew blank — key/row colours
    do not follow light/dark. Example recoloured + wiki rule + legibility test (`eba7dba2`); its
    latched Shift then read white on #C9D6E3 (1.48) → `4449359a` (pressed colours on Shift).
    Re-imported on the Saga: both modes legible. (2) Save as custom theme → Theme Manager →
    Creator (Enter pill off) → selected globally → keyboard shows a rounded-rect Enter: PASS.
    (3) Gboard-style "Export as layout XML" → wrapped in QWERTY rows → imported: colours/pills
    match the look selected globally (key #3B383E, bg #1E1D21, Enter #4B4358): PASS. (4) fa:
    Layout Manager chips, Key spacing & borders dialog, layout theme picker, save toast PASS;
    Theme Creator hex rows drew `FFFFFFFF#` and the dynamic-colour switch "(+12" → `ed022c9c`
    (LRM-led `HexColorText.display`, fa string). Seen, not fixed: the Theme Manager card of a
    custom theme with `<dark>` colours shows its light colours in dark mode; the Theme Creator
    preview renders the first non-System layout, so a styled layout's row colours appear in a
    theme's preview; after a night-mode switch the IME window once came up 126 px short
    (frame [0,1706], keys under the nav bar) and was right on the next show (NOT reproduced
    2026-10-27 on emulator.wtf API 34/35/36: four switches, nav inset 126 every time, keys above
    the bar; the row-height half is fixed by `bd756a79`; 126 px = the Saga's 48 dp nav bar, so the
    suspect is one transient 0 inset dispatch to the rebuilt view — unproven, nothing changed); hint/preview chip
    rows overflow and scroll in fa. Not done: Add Layout in landscape (Saga left in portrait).
    17:58 (LAN back): the `ed022c9c` APK (sha256 b3667ea5…) installed on the Saga; in fa every
    Theme Creator hex reads `#…` first (17 colour rows and Keyboard Look rows) and the switch
    reads "(Android / 12 و بالاتر)": PASS (`build/oct27pm-saga202/73-fa-fixed-strip.png`). Saga
    restored and verified: app locale [], layouts System only, custom theme "Harbour" deleted
    (Creator closed unsaved), my Chrome `data:` tab closed (3 pre-existing tabs kept),
    CleverKeys Dark selected, night mode yes, default IME CleverKeys, launcher in front, no
    files left in Download. Scratch worktrees ck-wt-saga202/202c removed (content = HEAD).

## Done 2026-10-10 → 10-17 (moved out of Open now on 2026-10-18)

The commits are the record; the evidence pointers stay here. Device dirs `build/oct10-*/` …
`build/oct17c-*/` hold screenshots and logcat (not in git).

- [x] Swipe-priority device check and hard words: `adb` stored at the legacy frequency 100 lost
  to `an` even at Highest (Seeker 2026-10-10, `docs/eval/2026-10-08-user-swipe-priority.md`
  §9); raised to 255 it ranked first (Pixel 2026-10-12). Legacy-frequency offer + dialog lift
  (`051dca51`, `8f4bdf2b`) PASS on the Pixel 2026-10-11 (Review / Not now / Dismiss / re-offer,
  lift to 255, `wet` at Highest 3/3 — it also takes plain `we` swipes, the eval's stated cost).
  `somethings` commits 3/3 with and without the custom word (Pixel 2026-10-12). The Custom tab
  counted the "+ Add" row → `8839ee85`.
- [x] Language-pack swap crash safety (`462c2eab`): `LanguagePackSwap` backup-rename swap +
  rollback, trash removal, once-per-manager startup recovery; `LanguagePackSwapTest` (18 pure)
  + 3 new `LanguagePackImportTest` cases (fail-first 3/51 on the old swap). Not device-verified.
- [x] Mock-suite timing flakes (2026-10-14, `e32a12d7`) were measurement design, not
  regressions: `WordPredictorFuzzyTypingTest` latency is now the per-input median of 11 rounds in
  thread CPU time (one ~170 ms stall had lifted a 0.2 ms input to 17.4 ms);
  `UserAdaptationManagerPersistenceTest` W6 runs on its own scheduler with a 50 ms debounce and
  waits on the commit (the 7 s+ stall was unattributed host load). 5× normal + 5× load ~20 pass;
  full runMockTests 1228/1228.
- [x] `helo` autocorrect + bar consistency: the bar highlights what space commits
  (`SpaceCommitResolver`, `ebe46e19`); the dropped-double margin rule DD-M (`df4a61f0`; M=64
  tuned and gated on the shipped `en_enhanced.bin`, `ee32d912`,
  `docs/eval/2026-10-11-dropped-double-margin.md` §5) makes `Helo` highlight and commit `Hello`.
  Pixel PASS, release `7eef0b7c`: `Helo`→`Hello` (undo chip `Helo`), `Sed`→`See` (not Seed). The
  earlier M=32 was measured on the CTC JSON scale by mistake; harnesses now load the `.bin`
  (`ShippedTapDictionaryDriftTest`). Prefix-length follow-up → Open now 16.
- [x] Hebrew autocorrect: layout-grid adjacency for letters outside the QWERTY table
  (`1d2f1d58`; he top-500 neighbour typos 189 → 231 of 352 corrected, two-letter words 0 → 9 of
  12, English unchanged 296/403). Pixel, release `7eef0b7c`, he pack on `hebr_1_il`:
  `Helo`→`Hello`; controls 3/3; אתנול→אתמול corrected; שרום was not a neighbour typo (ר and ל
  are far apart); תושה→עושה reproduced on the JVM → Open now 13.
- [x] Editing-command unification (`7d1a3271`): every Editing command runs in
  `KeyEventHandler.handleEditingKey` from layout keys and custom mappings alike
  (`EditingCommandParityTest`, fail-first 8/9; 20 of 22 commands differed before). Layout
  `textAssist`/`replaceText` keys open the app chooser like the mapping did (maintainer call).
- [x] Editor-menu commands (`a516f6ae`, 2026-10-13): `replaceTextMenu` sends the bare
  context-menu id with no chooser or selection precondition; `textAssistMenu` was added the
  same way and REMOVED again on 2026-10-17 (below). Saga 2026-10-13 (release `68d91874`,
  `build/oct13-saga/`): `replaceTextMenu` in Chrome shows nothing — Chrome does not honour the
  id (documented in the palette page), not a bug.
- [x] Blind text fields (`68d91874`): `TerminalUtils.editorKind` TEXT / TERMINAL / REMOTE
  (RustDesk + any TYPE_NULL editor = REMOTE; no readback probe). REMOTE gets the terminal
  no-rewrite policy, paste commits the clip, `clear`/suffix refused, no cursor sync or #78
  scan, key-event deletion, Ctrl+Backspace (not Ctrl+W) for delete-last-word. Evidence and
  RustDesk source lines: `docs/specs/termux_integration.md` "Blind text fields".
  `BlindFieldPolicyTest` fail-first 9/12. Device checks → Open now 6.
- [x] Inline-field shortcuts and paste (`3439fff0`): Ctrl+V/X/A act on the clipboard editor,
  Ctrl+V and the paste key paste into search/tag fields, other shortcuts are swallowed. Pixel
  2026-10-13/14: Ctrl+V lands in the editor PASS (`build/oct14-pixel/65–67`). Saga 2026-10-13
  (release `68d91874`, `build/oct13-saga/`): Termux no autocorrect, panel and custom-swipe paste
  type the clip, Backspace PASS.
- [x] Style layouts (`51d2a38d`): `latn_qwerty_us_{ios,gboard,swiftkey}_style.xml` ("QWERTY
  (iOS-style)" etc.), each with its own bottom row, the product's widths and hints, SwiftKey
  with an embedded number row; `StyleLayoutsTest` (mock, real parser); wiki "Familiar-style
  layouts" in `docs/wiki/layouts/adding-layouts.md`. Pixel 2026-10-17 (release `d8be659b`,
  `build/oct17-pixel/124–142`): each layout added and rendered, `the`/`hello` swiped on each,
  123 opens the number pane and ABC returns; no side-by-side comparison against the real
  keyboards' screenshots was recorded.
- [x] Inline editor undo/redo/copy + app undo via Ctrl+Z (`d599f84a`): `InlineEditHistory`
  (pure, bounded 100 steps / 1M chars, one step per typed word) is the editor's own history —
  every routed edit is a `setText()`, which calls `Editor.forgetUndoRedo()`; app-facing
  undo/redo send Ctrl+Z / Ctrl+Shift+Z key events (Chrome/WebView and Compose ignore the
  context-menu ids, `docs/research/2026-10-15-ime-editor-actions.md`); terminal-style fields
  report "Action unavailable" (Ctrl+Z would suspend the shell job). `InlineEditHistoryTest`
  (11 pure), `InlineFieldShortcutTest`, `EditingCommandParityTest`. Device check → Open now 5.
- [x] Per-layout theme binding (`e237fa95`, spec `docs/specs/layout-system.md` "Per-layout
  theme binding"): Layout Manager Theme chip (Follow global theme / any built-in, decorative or
  DIY theme), `<keyboard theme="…">` as the XML default written on Export XML, stored as the
  `theme` field of the `layouts` entry (rides in the backup blob; preview lists per-layout
  changes), `LayoutThemeBinding.resolve` per refresh, global `theme` pref never written,
  fallback to the global theme when the bound one is missing. Pixel 2026-10-17 first run
  (release `d8be659b`, `build/oct17-pixel/148–155`): keys and chrome switched Light ↔ Cyberpunk
  correctly but the bar showed no chips → `9e2a9804` (every bar text role ≥ 3:1 on 9 XML + 4
  decorative themes; the preference handler's stale bar/view references; one "Suggestion bar
  built" log line per rebuild). Re-check 02:54–02:58 with the fixed build
  (`build/oct17b-pixel/60–72`, `logcat-b1-ios.txt`, `logcat-b1-global.txt`): iOS-style bound
  to Light shows black chips (`hello | help | hel | held | hell`) and switching back shows
  Cyberpunk's cyan chips — PASS; the empty bar did not recur and its cause was not isolated.
  DIY-theme binding and restore exercised (`164`, `185`, `263–264`, `291–292`); XML
  import/export of `theme=` and the backup-preview rows have no screenshot evidence.
- [x] Six editor commands (`d86f6606`, `9356f280`, `f12710b1`, `fcbef011`; maintainer decision
  from `docs/research/2026-10-15-ime-editor-actions.md` §4.2), all in `handleEditingKey`:
  `case_cycle` (verified readback rewrite, keyboard-language locale), `shareText` as the IME's
  own ACTION_SEND chooser, `word_left`/`word_right`, `select_word_left`/`select_word_right`,
  `select_word`/`select_line` (readback `setSelection`; Ctrl(+Shift)+arrow without readback;
  Chrome/WebView get the editor's own keys because WEB_EDIT_TEXT 0xa0 trips the password test
  in `shouldMoveCursorForceFallback`; terminals: moves only); `textAssist`/`replaceText` write
  the app's result back (`ProcessTextActivity` → `ProcessTextReturn` → `deliverProcessTextResult`,
  only into the same field with the identical selection receipt, else clipboard + feedback);
  swipe/autocorrect commits carry their alternatives as a SuggestionSpan (flags 0, ≤ 5, text
  fields only). Catalogue 230 → 237; extra keys, TalkBack, 16 new + 2 reworded strings.
  Legacy WORD_LEFT/WORD_RIGHT mappings route to the new keys.
- [x] Editor commands on devices (2026-10-17; Pixel releases `cc4fe37d`/`e940262c`,
  `build/oct17b-pixel/`, `build/oct17c-pixel/`; Saga for Termux, `build/oct17b-saga/`,
  `build/oct17c-saga/`): case cycle Hello→HELLO→hello (Chrome + EditText); Share one sheet /
  "No text selected"; word moves + select word left/right; select word (4 cursor cases) and
  select line (visual line) in Chrome after `fcbef011`; Replace popup shows swipe alternatives;
  "Replacement Suggestions (Editor)" listed and Text Assist (Editor Menu) gone; Termux: word
  moves move the shell cursor, select word/line, case cycle and share refuse with no change —
  all PASS. Process-text RESULT path: emulator.wtf Pixel7 API 33 (ChooserActivity) and 34
  (IntentResolver) 3/3 each via `ProcessTextResultInstrumentedTest` (foreign editor + an
  EXTRA_PROCESS_TEXT upper-casing app in the test APK, the real service key_up → trampoline →
  system chooser tapped by UiAutomator → forwarded result; in place `say hello world now` →
  `say HELLO WORLD now`; field changed meanwhile → clipboard + bar message; no trampoline left
  in recents). A chooser left open with Home blocked the next request on API 34 → `ca56957f`
  (`TextActionLauncher.TRAMPOLINE_LAUNCH_FLAGS` adds CLEAR_TASK; the pre-fix APK fails
  `aChooserLeftOpenDoesNotAnswerTheNextRequest` on 34). Leftovers → Open now 7.
- [x] `textAssistMenu` removed (`932207b0`; research §4.1: `android.R.id.textAssist` is handled
  by no editor, so it was always a silent no-op): out of the catalogue (237 → 236), the wiki and
  22 resource dirs; `Editing.ASSIST_MENU` and the name stay so saved mappings, backups and
  custom layouts load and show "Text Assist (Editor Menu) is no longer supported. Reassign this
  command" without touching the app. `replaceTextMenu` renamed "Replacement Suggestions
  (Editor)". Text assist whose chooser cannot start now says "Action unavailable" instead of
  the silent id (`e940262c`) → decision in Open now 11.
- [x] API 35–37 instrumented coverage (`6bdf282e`, `47be5f23`): emulator.wtf Pixel7 API
  35/36/37, full unfiltered suite with the real `ctc_bench` encoders — 1606/1606 on 35 and 37,
  1605/1606 on 36 with the last failure fixed (class 3/3). No app bug; the app APK stayed
  byte-identical (compile/target 34). Harness-only fixes: espresso 3.7.0 stack (API 36 removed
  `InputManager.getInstance`), `ShowImeWithHardKeyboardRule` (36/37 images ship
  `show_ime_with_hard_keyboard=0`), clipboard-overlay swipe in the process-text test, 9 stale
  `TYPE_NULL` fixtures. `.claude/skills/ew-cli-testing.md` "API 35–37 coverage". targetSdk
  prerequisites → Open now 12.
- [x] Terminal taps never delete (`80207612`) and no dictionary offer there (`fd3eec40`): the
  device evidence and the one remaining re-test are in Open now 1.

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
- [x] Device check (eval note §8): `adb` at Highest lost to `an` while stored at the legacy
  frequency 100 (Seeker 2026-10-10) and ranked first once raised to 255 (Pixel 2026-10-12);
  `wet` at Highest 3/3, taking plain `we` swipes as the eval predicts (Pixel 2026-10-11). See
  "Done 2026-10-10 → 10-17". `an`/`as` collateral was not recorded separately.
- Native-speaker review of the 7 new strings → Open now 10.

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
