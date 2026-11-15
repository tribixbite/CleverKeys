# memory/todo.md "Open now" items: full text as of 2026-11-14

Moved verbatim from `memory/todo.md` (HEAD `762f7993`, 2026-11-14) lines 70–1582 (all items except 34). Relative links below were written
for `memory/`: read `../docs/…` as `docs/…` from the repo root. The live status of every
open item is in `memory/todo.md` "Open now".

## Item 1

<!-- closed at the move -->

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

## Item 2

<!-- open at the move -->

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

## Item 3

<!-- open at the move -->

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
   #181 (agent, emulator.wtf, 2026-10-28): NO GMS-FREE IMAGE EXISTS there. `ew-cli --models` lists 9
   models (Pixel2/7, Tablet10, NexusLowRes, Monitor + 4 `*Atd`); new `Issue181NoFontProviderTest`
   probed 10 images (Pixel7Atd 31/34, Pixel7 34/37, Pixel2 34, Tablet10 35, NexusLowRes 36,
   Monitor 34, NexusLowResAtd 34, Tablet10Atd 36) and every one has GMS and
   `com.google.android.gms/com.google.android.gms.fonts` answering LOAD_EMOJI_FONT, EmojiCompat
   configured (ATD = google_atd); API ≤30 images are 32-bit x86, which the x86_64 APK cannot use.
   The test skips (assumption) on a provider device and, on a GMS-free one, asserts no provider,
   EmojiCompat not loaded, and that every category + 4 searches show exactly the `Paint.hasGlyph`-
   drawable entries in order (dropped ones undrawable); its no-provider branch has never executed.
   Still needs a GMS-free phone (e.g. LineageOS without GApps): run that one class there.
   #184 PASS (agent, Saga 144, release `16b4a500`, 2026-10-28, `build/oct28-saga144/10–12, 25`): sw
   pack (20,000 words) imported, then an UPDATE pack built locally with the same code and 100,001
   real dictionary entries (sw words + synthetic filler, `build_langpack.py`) → refused "This
   dictionary contains 100001 words; the limit is 100000", logcat `Language pack import refused`;
   Manage still listed "sw • 20000 words" and, on release `3cf3738a`, a layout bound to sw showed
   Active [SW] (17,455 entries) in Dictionary Manager, i.e. the old dictionary still loads. Pack
   deleted, Multi-Language off again, test ZIPs removed from Download.

## Item 4

<!-- open at the move -->

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

## Item 5

<!-- open at the move -->

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

## Item 6

<!-- open at the move -->

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

## Item 7

<!-- open at the move -->

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

## Item 8

<!-- open at the move -->

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

## Item 9

<!-- open at the move -->

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
   FULL LIST 2026-11-09 on the Pixel (destructive items maintainer-approved for that run;
   `build/nov09-pixel/`): 43 pass / 5 fail / 3 partial / 12 not run. Fixed the same day: the
   default-keyboard reminder never fired (only the IME service checked, and Android creates it
   only while it IS the default → launcher/Settings `onResume` now check; service check removed) and the suggestion bar
   kept its scroll across swipes. Still open: `d'être` (item 35), the spoken TalkBack pass, the
   emoji panel by hand, voice/GIF items (no packs on the phone), autofill inline.

## Item 10

<!-- open at the move -->

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
    **Sheets ready (2026-11-01; re-laid-out 2026-11-04 after the maintainer found the single
    CSV per locale hard to read):** `docs/i18n/review/2026-11/<locale>/` for all 21 locales,
    34,121 rows. Each string is in exactly ONE of three plain CSVs (no comment lines, so GitHub
    renders them as tables; English, current text and the reviewer's cell are the first three
    columns): `1-translate.csv` 626 rows still in English (codes/brands in `keepEnglish` and the
    Latin names of Hebrew/Arabic/Slavonic marks excluded), `2-check.csv` 332 rows with a stated
    question in `why_check` (review-flags.json questions incl. the 24 LanguageTool
    native-judgement items, glossary leads), `3-read-through.csv` 33,163 optional rows. The
    generated README in that folder has the instructions and per-locale counts. Re-import:
    `python3 scripts/export_translation_review.py --import <locale>/*.csv [--dry-run]`, tested by
    `scripts/test_export_translation_review.py`. Owner of the review itself: maintainer.

## Item 11

<!-- open at the move -->

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

## Item 12

<!-- open at the move -->

12. [ ] DECIDED 2026-10-25: stay at targetSdk 34 for 2.0; bump in a later release. targetSdk bump (still compile/target 34; the full instrumented suite is
    green on API 35/36/37, `6bdf282e`). Prerequisites found 2026-10-17: (a) targetSdk 35 forces
    edge-to-edge — only Settings and Launcher handle system-bar insets out of 15 activities;
    (b) targetSdk 35 needs `values-id` beside `values-in` (build.gradle TODO); (c) targetSdk 36
    makes predictive back the default — no custom back handling exists, so low risk, but every
    Compose dialog/activity back path needs a device pass; (d) targetSdk 37 can no longer opt
    out of large-screen orientation/resizability; (e) Android 18 drops implicit URI grants —
    both share paths are already explicit.

## Item 13

<!-- closed at the move -->

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

## Item 13b

<!-- open at the move -->

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

## Item 13c

<!-- open at the move -->

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

## Item 15

<!-- open at the move -->

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

## Item 16

<!-- closed at the move -->

16. [x] agent: `autocorrect_prefix_length` — measured 2026-10-21 (descriptive,
    `docs/eval/2026-10-21-autocorrect-prefix-length.md`): prefix 1 vs the shipped 0 under channel
    ranking costs GTC 79.0 % → 77.8 % (553 targets broken, 305 fixed) and S-adj 95.4 % → 82.7 %
    (first-letter slips can no longer be corrected). Default 0 stays. Description reworded in all
    22 locales (maintainer approved 2026-10-21; translations mine → string review, item 10);
    C-oov and prefix 2 not run (stopped: held the Gradle lock 3 h).

## Item 17

<!-- open at the move -->

17. [ ] RTX: short-word CTC — word-balanced fine-tune on the raw-path input contract so
    dwell-less `ad`/`wet` decode, gated on fresh writer/session-separated human traces
    (`docs/eval/2026-10-07-short-word-ctc.md`, `…final-letter-drops.md` §6).
    KIT READY (2026-11-04): `ml/rtx-kits/17-short-word-ctc/`. Gate = FUTO swipe-1 VALIDATION,
    session-disjoint from all training (567 ad/wet-class traces, 377 words, 271 sessions).
    PREP CHECK 2026-11-05 (phone, kit README §7.1): hashes, step-2 byte-identical re-run,
    patch vs ML origin/main, preset parity, gate smoke (identical model fails C2 as it must),
    contract_rows holdout exclusion, mypy — all PASS. Ready for the RTX box.

## Item 18

<!-- closed at the move -->

18. [x] agent: apostrophe context chooser — round 3 (listed-evidence, `its` only) scored once
    on the phone 2026-11-04 (maintainer: "run item 18 here") and PASSED every bar: GUM `its`
    +30.44 pt (166/15), Ubuntu 125,001–150,000 `its` +6.15 pt (1,139/897), 0 structural
    violations, 59 contraction tests green (`docs/eval/2026-10-07-apostrophe-context.md` §8).
    Shipped per the pass branch: chooser in `src/main/.../swipe/`, wired in
    `SuggestionHandler.chooseContractionForm`, real-path `SwipeContractionChoiceTest`.
    Device check of the swipe bar after "of" still to do with the next APK.
    DEVICE CHECK 2026-11-05 (Saga `.144`, release of `a9d7b351`, Chrome textarea, adb-swiped i→t→s):
    empty field → bar Its | It's, `Its ` inserted (overlay order); `A piece of ` → `its `;
    `I think ` → bar it's | its | is, `it's ` inserted; `Yesterday ` → `its `. Field cleared, tab
    closed. Release notes: CHANGELOG + RELEASE_RECORD rows added 2026-11-05.

## Item 19

<!-- open at the move -->

19. [ ] RTX: Bangla spelling-preserving dictionary + mark/conjunct geometry, then a validated
    model/pack (`docs/guides/adding-a-new-language.md`); ARC-061 `ctc_golden` fixture fix.
    KIT (2026-11-04): `ml/rtx-kits/19-bangla-pack/`. A 20,627-word subset of WM Keyboard bn.txt
    (MIT seed + FrequencyWords CC BY-SA 4.0) verified spelling-preserving with
    `build_dictionary.py --keep-script-marks`. WordChars mark fix DONE 2026-11-04 (maintainer
    approved); the kit §3 remainder DONE 2026-11-06: danda/double danda sentence end for
    next-word context AND learning (`WordChars.isSentenceEnd`), fuzzy search + dropped-double
    gate on marked words (`isSpelledWithLetters`), double-space period after a mark (inserts
    `. ` like WM Keyboard), Bengali ZWJ (`র‍্যাব`), and Devanagari/Gujarati/Kannada/Tamil/Sinhala
    marks; then Sinhala/Kannada ZWJ and the danda pulling back over an auto-space. Devanagari ZWJ (eyelash ra, half
    forms) DONE 2026-11-06. Open TODO: the Bangla conjunct form of a doubled consonant (`ক্ক`). Still blocked: the model needs a layout decision (centre keys cover
    17–31 % of words); maintainer rule: Bangla questions follow WM Keyboard (native speaker).

## Item 21

<!-- open at the move -->

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
    FULL INSTRUMENTED 2026-10-30 at `c6b40cec` (agent, detached worktree, the 4 `ctc_bench` encoders
    sha256-equal to the testing-strategy table; app `ec4eeffa…`, test `233ff801…`): unfiltered, 3 shards,
    orchestrator, Pixel7 — 1,646 run = 1,646 source `@Test`; 1,644 passed / 0 failed / 2 skipped / 0 flaky
    on API 34 (`dea153a4`), 35 (`151f9342`), 36 (`ab758d79`) and 37 (`6d663b96`). Both skips are
    environmental assumptions: `Issue181NoFontProviderTest` (image has a font provider) and
    `VoiceInputInstrumentedTest#theImportedPackTranscribesASampleOnDevice` (ignored voicepack asset not
    supplied). Rerun with the pack from `../ck-voice` (zip `33ec8c87…`): VoiceInputInstrumentedTest 3/3,
    0 skipped, on 34–37. No failures, so no fixes. Evidence `~/ew-output/full-c6b40cec/`.
    FULL INSTRUMENTED 2026-11-01 at `0b5eb513` (agent, detached worktree, the 4 `ctc_bench` encoders
    sha256-equal to the testing-strategy table AND the voicepack zip `33ec8c87…` + sample.wav/txt in the
    test APK; app `b526e106…`, test `36a6317d…`): unfiltered, 3 shards, orchestrator, Pixel7 — 1,656 run =
    1,656 source `@Test`; 1,655 passed / 0 failed / 1 skipped / 0 flaky on API 34 (`e1e1e04d`), 35
    (`0f1d2b77`), 36 (`74f77cd8`) and 37 (`99f4853f`). VoiceInputInstrumentedTest 4/4 in the full run.
    The one skip is environmental (`Issue181NoFontProviderTest`: image has a font provider; the only
    AssumptionViolated line). No failures, so no fixes. Evidence `~/ew-output/full-0b5eb513/`
    (`full-0c3a2f56` deleted).

## Item 22

<!-- open at the move -->

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

## Item 23

<!-- closed at the move -->

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

## Item 24

<!-- open at the move -->

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
    (b) DONE 2026-11-05: `ClipboardReorderDatabaseTest` 6/6 in the full instrumented run at
    `eb5c394a` on API 34–37; (c) DONE 2026-10-27, Saga 144 release `6bc336b2…` (acd3b79e): four
    imported Pinned rows, row A dragged by its handle below B, order kept after pane close/reopen;
    rows swiped away, History / Pinned / To-dos back to 0 / 0 / 0.

## Item 25

<!-- open at the move -->

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
    `build/oct27pm-pixel/20-autocorrect-tomorr-timor.png`) — ADDRESSED 2026-10-27 (agent): new
    channel step ρ "unfinished word" (pre-registered round 6, eval doc §16–§18; TEST PASS: 43.4 %
    of 2,535 hijacked unfinished-word targets kept, GTC −7 of 4,883, S-adj −3 of 5,075, named
    cases hold). `tomorr`, `becau`, `restau` now stay as typed; `caf` → car and `resta` → rest
    are UNCHANGED (cheaper than the unfinished reading at ρ = 1.5; a ρ that keeps `resta` broke
    48+ GTC-DEV targets). Slider "Unfinished Word Cost" (22 locale files, machine-drafted, native
    review owed). Not device-checked (LAN down); after a night-mode switch the keyboard
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
    ROUND 7 (2026-10-28 night, agent, Pixel): Samsung-style vs the maintainer's REAL One UI
    screenshots (dark, `build/oct28-samsung-ref/1-5.jpg`, not in git) `ccaeb232`: dark palette and
    gaps measured by pixel sampling (#010101 / #3A3A3C / #171719 / #FBFAFF / hints #9A999E; gaps
    0.15 key, 0.23 row), blue action icon #678EED at 1.3× on the dark fill, "English (US)" on the
    space bar (new Appearance switch, default on; 22 locale files machine-drafted, native review
    owed), Samsung's alternative characters up-right on the letters when look hints are on, and
    !#1 opens Samsung's two symbol pages via the new `switch_symbols` + `<keyboard symbols>`
    (CleverKeys' number pane stays a right swipe on !#1). Tests red → green; runPureTests 3463,
    runMockTests 1614 OK. Device PASS (letters, both pages, 1/2 ↔ 2/2, ABC; compare-*.png).
    Open / structural: toolbar row and card-grid clipboard (4.jpg, 5.jpg); Samsung's "!#1" / "1/2"
    / "ABC" are drawn larger than our function-key labels; SamsungOne font; light palette not
    checked against a screenshot; go/send/next/done icons inferred (only search seen). Note: the
    global theme had been switched to Gboard-style by a blind tap in one of my UI scripts (root
    cause: a missing-element tap; scripts now abort instead) — restored to Cyberpunk. A Chrome
    "File might be harmful … CleverKeys-v2.0.0-arm64-v8a.apk from s3" prompt appeared on the Pixel
    (not started by me) — left untouched; my test tab was gone (7 tabs). Pixel restored otherwise.
    ROUND 8 (2026-10-28 midday, agent, Pixel; release from bbaf814b + `4ee8bd19`, source-equal to
    `4ee8bd19`, left installed): bar RESHOOT after the round-6 unfinished-word fix. `tomorr` is no
    longer autocorrected (centre was `timor`); space keeps it, so the bar had no highlight and the
    looks centred `tomorrow` with `tomorr` left — the real Gboard/SwiftKey centre the verbatim word
    when not correcting (`kwazb` captures) → FIXED `4ee8bd19` (typed word centred, plain; quoted on
    iOS). Bar now `tomorrow | tomorr | tomorrows`; taps PASS (space and centre → `tomorr`, left →
    `tomorrow`); next word `afternoon | morning | I`. Suites pure 3485, mock 1627 OK. Showcase
    `build/oct27-night-pixel/showcase/` (old set in `showcase-old/`), Samsung compares redone in a
    Chrome search field. Side effects cleaned: an accidental custom word `tomorr` (from a centre
    tap) deleted in Dictionary Manager; learned word `tomorr` and four test bigrams
    (`tomorrow → tomorr/the/morning/afternoon`) deleted in Learning & Data. NOT removable per word:
    the selection-history count for `tomorr` (decays; no UI) — until then typing `tomorr` shows no
    "+" entry. Earlier test sessions' learned words (`tomorrow` 33×, `at`, `you`, `see`, `good`…)
    are from all agents' runs, baseline unknown — left. Restored: layouts System/Dvorak/QWERTY US,
    night yes, Cyberpunk, Next-Word OFF, look hints OFF, Learn From My Typing ON (turned off
    during captures, "Keep it" chosen, data kept), 7 tabs, launcher.
    DEVICE 2026-10-30 (agent, second Saga 192.168.0.144, portrait, release a50a89ee sha256
    fe12c29f…, evidence build/oct30-saga144/, not in git): "Unfinished Words" (7dd7fb5a) PASS —
    Ranking & Tuning shows Keep what I typed / Off with their explanations, the Unfinished Word
    Cost slider greys out under Off; `tomorr`+space in a Chrome textarea → `Tomorr ` under Keep
    (bar `Tomorrow | Tomorrows | Tomorrowland`, none highlighted) and `Timor ` under Off (bar
    highlights Timor). Restored to Keep, cost 1.50. Theme Manager (58e86ea0) PASS — Harbour
    imported as a layout, "Save theme as custom theme": its card draws the <dark> colours in dark
    mode and the light ones after `cmd uimode night no` (restored yes); Theme Creator preview
    draws QWERTY in the edited colours with Harbour as layout 2 AND as layout 1 (System removed
    temporarily). Restored: theme and layout removed, layouts [System], theme CleverKeys Dark.
    ROUND 9 (2026-10-30, agent; NO PHONES — emulator.wtf API 34 + JVM; evidence
    `build/oct30-looks/`, not in git) `6ceb58d6`: four open polish items CLOSED. (1) Theme Creator
    "Keyboard Look" → Suggestion Bar Layout: "Use keyboard setting" (chips) or slots 1–5, best in
    the middle, quoted typed word, bold pending correction / dim side words, separators follow /
    show / hide; live in the Creator preview (`creator-bar-arrangements.png`, `creator-ui-bar.png`;
    `ThemeCreatorBarArrangementComposeTest` red — emphasis chip scrolled off — then FlowRow,
    green). (2) Samsung-style !#1 / 1/2 / ABC at 0.94 of the main size (`functionLabelScale`
    1.25): ink 35 / 38 / 36 px vs digits 38 (One UI 38 / 40 / 35 vs 40), was 27–29 vs 37
    (`compare-samsung-function-text-dark.png`). (3) SwiftKey-style mic over the comma (only with
    voice typing available) and ",!?" over the period, hint colour, hidden when gesture hints are
    shown (`compare-swiftkey-legends-{light,dark}.png`); the period keeps ? ! (a layout never
    repeats a key, so the mark's comma is the product's). (4) Gboard-style filled Shift arrow
    while shifted, arrow + bar in Caps Lock, our own path (`gboard-shift-{light,dark}.png`; the
    real Gboard's shifted state was never captured — filled shape taken from the task, outline
    at rest matches). All via ThemeSpec XML/JSON (`function_label_scale`, `key_legends`,
    `shift_filled`); 12 strings × 22 locale files machine-drafted, native review owed. Pure 3629,
    mock 1672 OK. Open: device pass of all four (no phone this round); key legends have no
    Creator control (XML / JSON only); SwiftKey's low-set comma/period glyph not copied.
    DEVICE 2026-10-31 (agent, second Saga 192.168.0.144, portrait, dark, release ae8ff92e sha256
    68bb3e07…; evidence `build/oct31-saga144/`, not in git): all four PASS. (2) Samsung-style
    !#1 / 1/2 / ABC ink 36 / 37 / 37 px vs digit 39 (0.94; `23-samsung-bottom-crop.png`,
    `24-25-samsung-sym-crop.png`), matches `compare-samsung-function-text-dark.png`. (3)
    SwiftKey-style mic over the comma and ",!?" over the period (`26-swiftkey-bottom.png`); with
    "Show gesture hints on familiar looks" on, the marks give way to the full corner hints
    (`33-swiftkey-hints-on-crop.png`). Disabling Google voice typing did NOT remove the mic mark —
    correct by the code: LayoutModifier always keeps voice keys (`shouldOfferVoiceTyping` is set
    true in SubtypeManager.refreshSubtype) and the mic key itself routes to "Voice input is off";
    the mark follows the key, so "only when voice typing is available" means "only when the key
    carries a voice swipe" (LookKeyMarksTest). (4) Gboard-style Shift outline → filled →
    filled + bar on the accent fill in Caps Lock (`28-30-gboard-shift-states.png`). (1) Theme
    Creator: temporary custom theme, Suggestion Bar Layout unticked → slots 5, centre on, quotes
    on, Dim side words, separators Show; the Creator preview bar changed live
    (`35-41-preview-bar-before-after.png`); saved, applied → the keyboard bar on the System layout
    followed: 5 slots, best centred, sides dimmed, separators, "Coloz" quoted left of centre
    (`47-…`, `49-bar.png`). Theme deleted, CleverKeys Dark re-selected. Restored: layouts (System
    only), look hints OFF, Google voice IME re-enabled (enabled_input_methods string identical),
    original APK c1e4dc73…, test tabs closed (Chrome 1 tab as found).

## Item 26

<!-- open at the move -->

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

## Item 27

<!-- open at the move -->

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

## Item 28

<!-- closed at the move -->

28. [x] agent, 2026-10-28 (maintainer: "swipe to delete word doesn't work while editing a
    clipboard clipping"; "automatic space insertion for tap typing after a swiped word is not
    honoured"). FIXED `0a6dddf9`. Root causes: `KeyEventHandler.handleEditingKey`'s inline
    branches swallowed `delete_last_word`/`delete_word`/`forward_delete_word` (`else -> {}`), and
    Ctrl+Backspace reached the field as a one-character `backspaceClipboardEdit`; the inline swipe
    planner leaves no trailing space and nothing owed it to the next tap (`help`+`t` → `helpt`).
    App fields were NOT affected (Chrome: `help t` before and after). Now `InlineWordDelete` + the
    deferred space `InlineSwipeSession.spaceBeforeTap` (gated by Auto-Space After Suggestion).
    Fail-first: 3 behavioural reds in `InlineSwipeRoutingTest`; green + full runPureTests 3,484 /
    runMockTests 1,623. DEVICE PASS (Saga 192.168.0.202, release sha256 9eaf09d1…, evidence
    `build/oct28-inline/`): editor `Meet at noon` + Backspace NW flick → `Meet at ` (old build:
    unchanged), swipe `help` + tap `t` → `Meet at help t` (old: `helpt`), Ctrl+Z restores, Ctrl +
    Backspace deletes a word; clipboard search `help t` then flick → `help`. Restored: test entry
    deleted, Download file removed, Chrome data: tab closed (3 tabs), CleverKeys default, launcher.
    Emoji search, GIF search and clipboard tag field: DEVICE PASS 2026-10-28 (same Saga and
    release 9eaf09d1…, evidence `build/oct28-inline2/`): in each, swipe `help` + tap `t` →
    `help t`, Backspace NW flick → `help `, Ctrl + Backspace → empty. GIF was off (turned on for
    the test, off again); the tag field used an imported `Meet at noon` entry pinned for the test
    (unpinned and deleted; history, pinned and todos empty again; Download file removed; Chrome
    back to 3 tabs; CleverKeys default; launcher). INCIDENT: a long-press on that history row
    (`ClipboardHistoryView.kt:2783`) copies it to the OS clipboard — the phone's previous clip
    was replaced by `Meet at noon` and could not be restored (no root, history was empty).
    Device agents: expand a row with a single tap, never long-press it.

## Item 29

<!-- closed at the move -->

29. [x] agent, 2026-10-29 (maintainer: unfinished-word behaviour as a setting — Keep what I typed /
    Complete the word / Off; follows item 25's `tomorr` finding). Round 7 pre-registered
    (`836f133c`, eval doc §19): rule C7, 252-config DEV grid, new Leipzig-weighted T-trunc-L.
    DEV: NOTHING ADMISSIBLE (§19.5) — best completion accuracy 14.5 % at ≤ 15 % wrong (needs 20 %),
    any config reaching 20 % completes ≥ 26.8 % of its changes into the wrong word. TEST not run;
    "Complete the word" NOT offered. SHIPPED: Ranking & Tuning "Unfinished Words" = Keep what I
    typed (default, ρ) | Off (pre-round-6), pref `autocorrect_unfinished_word_mode`, 5 strings ×
    22 files; ρ slider greyed under Off. C7 kept only as the unoffered eval seam. DEVICE PASS
    2026-11-05 (Saga `.144`, release of `a9d7b351`, reached via Settings search "Unfinished"):
    "Keep what I typed" shows the keep explanation and the Unfinished Word Cost slider at 1.50;
    Off switches the explanation ("Space treats tomorr like any typo…") and greys the slider (value
    kept); restored to Keep what I typed. Item can close.

## Item 30

<!-- open at the move -->

30. [ ] maintainer + device: on-device voice input — SHIPS IN 2.0.0 (maintainer decision 2026-10-29;
    was proposed for 2.1). Built `02de7f61`, fixes `c6734644` (no lost audio, 30 s auto-stop) and
    `1a26da24` (bar ✕/messages fit, password notice, no repeated word), `30983ed1` (a field
    change never writes into the new field); spec
    `docs/specs/voice-input.md`, record rows in `docs/RELEASE_RECORD.md` v2.0.0 "Additions
    2026-10-29". DEVICE-VERIFIED (agent, second Saga 192.168.0.144, Android 13, release of the
    branch, LibriSpeech played through the phone's speaker): permission asked only by the switch or
    a key press, deny / don't-ask-again paths; 45 MB pack import ~3 s, a corrupted `encoder.ort`
    and an unknown engine refused; Chrome textarea (composing partials, committed finals), Termux
    (append-only), clipboard search and Settings search (inline), password field refused with the
    bar notice; mic indicator off on stop/cancel/hide/field change/auto-stop; 0 recorder
    overflows, auto-stop at 30 s, peak RSS 451 MB; WER through the speaker 15/125 (12 %) vs 4.0 %
    on the JVM. NOT VERIFIED — needs the maintainer: TalkBack (start/stop/cancel announcements;
    agents cannot drive TalkBack over adb, item 3); RustDesk / a blind remote field (item 6's
    setup); dictating into the clipboard pane's inline entry editor; real-voice accuracy with a
    person speaking (only speaker-played LibriSpeech was measured); the Pixel. DONE: the pack
    `voicepack-moonshine-v2-tiny-en.zip` + `.sha256` (sha256 `33ec8c87…`) is on the `langpacks`
    release since 2026-10-29, marked experimental ("Requires CleverKeys 2.0 or later"). OPEN:
    native review of the 45 new voice strings in 21 locales (fold into item 10); voice lines in
    `docs/plans/v2.0.0-manual-test-checklist.md`.
    2026-10-31 ENDPOINTING (spec FR-10): the 30 s auto-stop is replaced. Engine-neutral
    `stt/SpeechSegmenter` (energy VAD on 32 ms hops after the official Moonshine runtime's
    segmenter: noise floor = quietest hop of 3 s, voiced ≥ 12 dB above it and > −50 dBFS, onset
    4 of 8 hops, 0.6 s hangover, 0.512 s look-behind, a boundary forced at the engine's
    `maxSegmentSeconds` — Moonshine 20 s — at the quietest hop of the last 2 s) +
    `SegmentedRecognizer` (a fresh session per segment) + `VoiceEvent.SegmentFinal` /
    `VoiceTextPlanner.onSegmentEnd` (one space between segments, never doubled; capital only
    after `.?!`; blind fields still finals-only). Period stops after 5 min or 10 s without
    speech (`VoicePeriodLimits`); microphone backlog bounded at 70 s. JVM, real model: 88 s of
    10 clips with pauses → 10 segments, WER 8/228 (3.5 %) vs 9/228 clip by clip, no loop, slowest
    pass per segment ~0.5 s early vs ~0.6 s late (flat); 53 s with no pause → 2 forced cuts, WER
    3/153 vs control one session 169/153 (loop). Fail-first: a forced segment 256 samples over a
    10 s bound. runPureTests 3,675 + runMockTests 1,673 green; emulator.wtf
    `VoiceInputInstrumentedTest` 4/4 (API 34, two PAUSE segments on device ORT). DEVICE (Saga 144,
    release of `b243ce3d`, the same 10 clips + 1 s gaps + 13 s silence played through the Saga's
    speaker into a Chrome textarea, real microphone): 11 segments committed in order (one extra
    split at a pause inside clip 1), each 0.2–1.5 s after its clip ended; no loop; WER 45/228
    (19.7 %, acoustic path — 12 % on short clips through the speaker on 10-29, 3.5 % on the JVM);
    RSS 345 MB → 346–461 MB while listening → 345 MB, no growth over 104 s (a 24 s period reached
    484–524 MB before); idle auto-stop closed the microphone ~10 s after the last speech. Saga
    restored (APK c1e4dc73, voice off, pack removed, mic denied as found). NOT VERIFIED: a
    person's own pauses and voice; noisy rooms.
    2026-10-30 (maintainer report, voice build on their phone): the mic key (`voice_typing`) and
    its long press (`voice_typing_chooser`) now route to the built-in engine (`VoiceKeyRouting`,
    spec FR-4a); word swipes from a letter with the mic in its corner no longer fire it
    (`Pointers` Event-subkey eager path, FR-4b — the "freeze": frozen trail + voice dialog);
    a press that lost its release is dropped at the next first touch (stuck key-press preview);
    look-bar quote marks come from `bar_quote_open`/`bar_quote_close` in all 21 locales.
    Device (agent, second Saga 192.168.0.144, release of the fix, evidence build/oct30-voice/):
    Google voice IME disabled → mic key and its hold show "Voice input is off… Settings → Voice
    input" in the bar, no dialog; re-enabled → the system voice chooser as before; "acid" swiped
    from `a` commits with no trail left. NOT verified on a device: the built-in-ready route and
    the chooser's "CleverKeys voice (offline)" entry (the Saga has voice off, no pack, mic
    blocked), and the mic-on-a-letter swipe itself (the Saga's layout keeps the mic on Enter;
    covered by PointersEventSubkeyWordSwipeTest).
    DEVICE 2026-10-30 (agent, second Saga 192.168.0.144, release a50a89ee, build/oct30-saga144/):
    built-in-ready route PASS — pack zip imported (~3 s), voice on, the mic key (Enter NW
    `voice_typing`) opened "Listening…" at once; its hold offered "CleverKeys voice (offline)"
    first, then Google Voice Typing; picking ours listened, ✕ cancelled (mic off). LibriSpeech
    through the speaker into Chrome: "Congratulations report in upon the protest everywhere doing
    her journey." (4 errors / 11 words); into the clipboard pane's inline entry editor (entry made
    by Backup & Restore → Import Clip): "Voice note this has indeed been a harassing day,
    continued the young man, his eyes fixed upon his friend." (0 / 16), Chrome's field untouched
    PASS. FINDING, not fixed (maintainer rule area): the app's own `voice_mic_permission_blocked`
    pref outlives the system state — after "don't ask again" was cleared (pm clear-permission-flags
    here; a user picking "Ask every time" in system settings is the same) the switch and pack
    import never prompt again and show "Microphone access is blocked" until the user allows the
    mic in system settings (`MicPermissionGate.permissionStep` trusts the cached flag; asking on
    the explicit Settings switch would cost nothing when truly blocked — Android denies at once).
    Mic granted through App info for the test. Restored: pack removed, voice off, RECORD_AUDIO
    revoked + user-set/user-fixed cleared (pref still "blocked", as before), entry deleted
    (History / Pinned / To-dos 0 / 0 / 0 as found), Download files removed.
    FIXED 2026-10-30 (agent, the remembered-block finding): one policy for Settings and the voice
    key (`MicPermissionGate.afterRequest` / `turnOnAfterAppSettings`, spec FR-7). The Settings
    switch and a pack import always ask Android; a refusal without a prompt opens "Microphone
    access is blocked" (Open app settings / Cancel), the switch stays off until granted, coming
    back from App info with the mic allowed turns voice on, a grant clears the remembered block;
    `MicPermissionActivity` (key path) shows the same dialog. MicPermissionGateTest red 2/7 →
    green. DEVICE (second Saga 192.168.0.144, release of the fix, build/oct30-findings144/):
    remembered block + cleared system flags → the switch showed the system prompt (was: nothing);
    Don't allow → toast, block text gone; second Don't allow (USER_FIXED) → the dialog; switch
    again → the dialog at once; Open app settings → App info; mic granted there → back, switch
    ON PASS. NOT device-verified: the key path's dialog (needs a voice pack on the phone).
    Restored: original APK (c1e4dc73…), voice off, RECORD_AUDIO denied with flags cleared and
    the app's remembered block re-created by two denials, as found.
    DEVICE 2026-10-31 (agent, second Saga 192.168.0.144, release ae8ff92e sha256 68bb3e07…,
    `build/oct31-saga144/`): key path PASS — pack imported from Download (~3 s, voice turned on with
    the mic granted), then RECORD_AUDIO revoked + USER_SET|USER_FIXED; the Enter-NW mic swipe in
    Chrome opened MicPermissionActivity's "Microphone access is blocked" (`56-mic-blocked-dialog`);
    Open app settings → App info → Permissions → Microphone → Allow; back in Chrome the same swipe
    showed "Listening…" at once (`60-mic-after-grant`), ✕ cancelled with no recording session left
    (dumpsys audio). FIXED `249090bc` (found in ca398fa5's notes): TalkBack read the action key as
    "Enter" on Go / Search / Send fields — `KeyLabels.describeAction` now speaks the key's own
    label (localized key_action_* or the app's actionLabel; no new strings). KeyLabelsTest red →
    green; device content-desc "Go" / "Search" / "Send" (was "Enter"), textarea "Enter".
    Restored as found: pack removed, voice off, mic denied by two real denials (app's block text
    shown) then flags cleared, Download zip removed, original APK c1e4dc73….

## Item 31

<!-- open at the move -->

31. [ ] device + maintainer: per-key subkey sources and modified layout copies (agent 2026-10-30,
   maintainer screenshots 9/10 + request; spec `docs/specs/subkey-customization-current.md`
   "Runtime key codes", "Sources in the per-key dialog", "Layout subkeys and modified copies").
   BUILT + JVM/emulator verified: Enter/Space/Tab mappings are stored under the key's runtime
   code (old `"\n"` mappings move to Enter on load), titles/toasts name keys and commands, the
   dialog badges all 8 directions (Layout / Hidden / Built-in / Extra key / Custom / Removed),
   a built-in layout's own subkey edit creates "<name> (modified)" in place (origin kept for
   Layout Manager's Revert), Export XML no longer writes `key1`+`nw`. Fail-first 8 of 9 bug
   tests; runPureTests 3590 / runMockTests 1657 OK; emulator.wtf Pixel7 API 34 18/18.
   NOT verified on a phone (no device assigned): capture of Enter in the real IME, the copy
   replacing the live layout, Revert from Layout Manager, TalkBack reading of the rows.
   OPEN: (a) DONE 2026-10-30 (maintainer "yes fix both"): the popover's assign screen shares
   the per-key dialog's rule — `SubkeyEditPolicy` (pure) + `SubkeySlotActions`, source badges,
   the copy question (spec "One policy for both screens"); SubkeyEditPolicyTest red 4/7,
   SubkeyAssignScreenSourcesComposeTest red 3/3 on the old screen, both green; NOT verified on a
   phone (popover hold → edit → copy on a real keyboard); (b) DONE 2026-10-30 (maintainer "yes fix both"): Export XML
   and layout copies share `XmlAttributeMapper.layoutValue`; every action type reads back as
   itself (`label:'text'`, backslash escape, text-level edit keeps non-BMP characters, layout
   intent keys are started), old files import as before (spec profile_system_restoration.md
   "Export XML values"); XmlActionRoundTripTest / XmlLayoutExporterRoundTripTest /
   KeyEventHandlerLayoutIntentTest fail-first 8/10, 5/6, 2/2; (c) native review of the 24 new
   strings and `layout_manager_export_not_written` (item 10).
   DEVICE 2026-10-30 (agent, second Saga 192.168.0.144, portrait, release a50a89ee sha256
   fe12c29f…, build/oct30-saga144/): per-key dialog badges all 8 rows (Layout / Hidden / Built-in /
   Custom; empty rows unbadged as specified); Enter's title "Customize "Enter" Key"; Enter S →
   Select All toast "Mapped South → "Sele" (Command: Select All)"; swipe works and an Enter tap
   still types "\n" PASS. q NE (Layout "1") → copy question → Create copy → toast "Created QWERTY
   (US) (modified)", the keyboard shows it at once, q NE types `zap`; Layout Manager "Modified copy
   of System Settings" → Revert to original → System PASS. Popover: hold e, dwell on "3" → edit
   screen (Layout badge) → Reassign → copy question → Create copy → `pop` typed by e NE PASS, but
   the edit screen showed the dialog's "…Tap a direction to change it." → FIXED `007bbd60` (own
   string "A subkey of %1$s.", SubkeyAssignSlotTest red 1/3 → 3/3, Translation* green). Export XML
   round trip (8636480b) PASS: on the copy, w NE template `<{cursor}>` (written into the copy's
   XML, no question) plus imported global mappings a NE TEXT `hi "there" \ 😀` and s NE KEY_EVENT
   122; exported `ne="hi:'hi &quot;there&quot; \\ 😀'"`, `ne="Hm:keyevent:122"`,
   `ne="&lt;{cu:template:'&lt;{cursor}>'"`; with ALL global mappings reset the re-imported layout
   typed the exact text, moved the cursor home and left the cursor between `<>` PASS. Findings,
   not fixed: Reset All in Short Swipe Customization wipes every mapping with no confirmation or
   undo; Layout Manager left open in the back stack shows a stale list after a popover copy
   (reopening is correct); the Enter NE row reads "Acti" (4-character label slot). Restored:
   copies reverted / layouts removed, 0 custom mappings, Download files removed.
   FIXED 2026-10-30 (agent, the three findings above; second Saga 192.168.0.144, release of the
   fix, build/oct30-findings144/): Reset All asks first ("Reset all short swipes?", count named,
   layout subkeys and copies untouched), is greyed at 0, and offers Undo in a snackbar
   (`resetAll` returns the removed mappings, `restoreAfterReset` never overwrites a newer one;
   ShortSwipeResetUndoTest, ShortSwipeChangeListenerTest.resetAllCanBeUndone) — device: 2
   mappings → Cancel kept 2 → Reset → "2 mappings removed · Undo" → Undo → 2 → Reset → 0 PASS.
   Layout Manager follows the `layouts` pref (listener + resume, `LayoutListRefresh.reconcile`,
   LayoutListRefreshTest red 3/4 → green) — device: Layout Manager open, e NE removed from the
   popover → Create copy → back to the task: "QWERTY (US) (modified)" listed without reopening,
   Revert → System PASS. Direction rows never cut a label (`SubkeySlotLabel`, SubkeySlotLabelTest
   red 2/4 → green; word labels 12 sp within 64 dp, descriptions wrap) — device: Enter NE reads
   "Action  Enter" (was "Acti"; a first 88 dp/15 sp try split "Ente/r", fixed before commit) PASS.
   Restored as found (0 mappings, one System layout).

## Item 32

<!-- open at the move -->

32. [ ] device + maintainer: voice engine follow-ups from the 2026-11-05 STT follow-up
   (`docs/research/2026-10-28-stt-engine-deep-dive.md` §6.5–6.6, §7.5). (a) DEVICE: time the
   app's Moonshine session with the ORT arena off (today) vs on vs the XNNPACK EP on the Pixel and
   a Saga. On this box (Python ORT 1.26) the CPU EP with the arena off ran the official decoder
   2–2.5× slower (tiny 5 s set: 890–1048 vs 420–465 ms batch; capped stream 398 vs 212 ms per
   pass for XNNPACK). XNNPACK RSS was 221–281 vs 115 MB on short sets, and the long-session growth is
   unattributed. Pick the setting from the device numbers. (b) MAINTAINER: whether to offer Moonshine
   small-streaming as an optional "accurate" English pack (135.7 MiB, fits every cap; 10/280 vs
   14, 6/308 vs 11, 43/1260 vs 83 on 30–72 s dictation; ~2× RSS and pass time). ACFT
   small.en is equally accurate but a 252 MiB single file over the 128 MiB per-file cap, and a
   second native runtime. Not recommended. Agent-side done 2026-11-05: sub-hop final chunk fix
   (`runFrontend` pads < 80 samples; `aStopThatLeavesLessThanOneFrontendHopStillFinishes`).
   Later 2026-11-05: the small pack is BUILT and PUBLISHED (`langpacks` release, zip sha256
   `f75cd82a…`, re-hashed after upload; release notes row added) and DEVICE-TESTED on the Saga
   `192.168.0.144` (research §6.7, spec "Device test 2026-11-05"): engine pass median 387 ms (tiny
   168) as shipped; real microphone 9–10 vs 18–19 errors in 228 words, lag 0.84 vs 0.75 s, keyboard
   RSS 459–698 vs 324–522 MB. ACFT small.en quantisations: none worth pursuing (§6.5.6).
   Android 14 Saga `.1.130` confirmed the same (small 23/22 vs tiny 45/41 errors through its
   speaker at 25/25). DONE 2026-11-05 evening (maintainer: "do the rss check … if no reason not to
   switch then switch"): long-period check on both Sagas (research §6.7.1, spec "Engine setting,
   long-period check"); XNNPACK REJECTED (ORT 1.21's process-wide XNNPACK arena keeps 274–540 MB
   after the engine closes); the engine now runs the CPU EP with the arena ON (as fast as XNNPACK,
   flat over 12 min, freed on the 60 s idle release); checked in the keyboard on `.144` with small.
   2026-11-05 night: `.1.130` re-run in its new sound box (tiny 28/28, small 12/15 errors in 228
   words) and the keyboard's memory measured with and without dictation (research §6.7.1): voice
   unused PSS 140–152 MB; dictating 330–460 (tiny) / 553–731 MB (small); after the idle release
   ~90 MB of freed native memory stays with the allocator (not a leak; live bytes at baseline).
   Still open: (a) DEVICE: a low-RAM phone (keyboard RSS while dictating with small is up to
   843 MB on a Saga); optional: whether the ~90 MB retained after release is worth a JNI purge.
   (b) DEVICE: a person's voice and a noisy room for small. Pixel DONE 2026-11-05 23:00 (research
   §6.7.1): tiny 17/15, small 5/6 errors in 228 words, lag 0.62–0.86 s, restored.
   (c) Pack choice → item 33.

## Item 35

<!-- open at the move -->

35. [ ] device: French/Italian accented elisions (2026-11-07, maintainer: "can't get d'être to
    swipe"). FIXED in data: `extract_apostrophe_words.py` re-keys accented keys to their a–z
    spelling (+7,602 fr / +1,032 it REPLACE, +16 / +11 PAIRED; hyphenated compounds excluded); pure pins in
    `BundledContractionDataTest`. Open: swipe `d'être`, `l'été`, `l'école` on a phone with French
    primary; the geometric engine still cannot produce non-word elisions (it builds only lexicon
    words). 2026-11-09: replay DONE (`ctc_injection_ab.py --corpus azerty`, 0 regressions, 6
    improvements, t1 81.10 → 82.68). DEVICE (Saga .144, synthetic swipes): `l'école`, `c'est`
    top, `l'été` and `d'âme` offered, **`d'être` not offered** — reachable but outranked at the
    floor (68 vs `être` 144) by `d'été`/`d'ère`/`dette`. Raising elisions to `freq(base) − margin`
    surfaces it (rank 2–3) but costs real words at every margin (15: 6, 30: 3, 50: 1 of 2,179
    traces) → **maintainer decision**, patch + numbers in
    `docs/eval/2026-11-09-elision-base-frequency.md`. Also wants a human swipe of `d'être`.
    Follow-up the same day: ranking each elision by its OWN Leipzig corpus frequency (margin 0)
    is the best arm yet — t1 84.26 → 85.13, 2 regressions (`laisse`→`l'aise`, `donjon`→`d'union`),
    17 improvements, `d'être` rank 1–2 (same doc, "Follow-up"; scripts `scripts/research/elision/`).
    Not shipped: needs a per-key table + NOTICE and relaxing `CoreImeHygieneDriftTest`.
    Also fixed (`a5427cd6`): it `c'è`/`s'è`/`n'è` were unswipeable (their keys `cè`… lose their
    projection to `ce`…) — now PAIRED on `ce`/`se`/`ne`; DEVICE Saga .83 (Italian primary):
    `ce` → bar `Ce, Cr, Le, Che, Ci, …, C'è` (slot ≈9, after scrolling), tapping commits `C'è `.
    A paired variant sits late in the bar (like `bowie's`): ranking it higher is open. And
    unaccented ASK duplicates respelled (`l'âme`, `l'étoile`, `l'éveil`, `l'écu`, it `l'élite`);
    it `l'attivita` kept until it.cklm is rebuilt (TODO in the generator).

## Item 36

<!-- open at the move -->

36. [ ] device: "Space before opening punctuation" (2026-11-07, default on; spec
    `docs/wiki/specs/typing/smart-punctuation-spec.md` Feature C). Mock-tested through the real
    sendText (`OpeningPunctuationSpacingTest`); not run on a phone. Ellipsis after a swiped word
    chains to `wait... ` (pinned). DEVICE 2026-11-09 (Saga .144, Chrome): the ellipsis FAILED on
    `9d4003b3` (`Wait. ..` — the maintainer's "space after a period" bug, reproduced): the
    period's delete-then-commit made the editor report an intermediate cursor that dropped the
    re-stamped space. Fixed (transit position, spec "The sentence-end chain on a real editor"),
    plus a leading space on a swipe after an app-side select-all + delete. On the fix's release
    every item of the checklist's "Punctuation and elisions" passes except `d'être` (item 35).
    Open: a human run; the translations of the setting (item 10).
