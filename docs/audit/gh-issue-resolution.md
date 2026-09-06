# GitHub issue resolution tracker

**Living document.** Snapshot 2026-09-05 (56 open issues). Statuses reflect main at `8f3d6f05`
(all fixes UNRELEASED until the next tag — reporters must retest on a build containing them).
Commit hashes are the evidence; test anchors live in the commits and the ledger
(`2026-08-28-archive-verification.md`, Rounds 3-4). **Nothing has been posted to GitHub** —
closing/commenting is the maintainer's; the "GH action" column is a recommendation only.

Statuses: **FIXED** (on main, fail-first-tested) · **PARTIAL** (fixed with a named residual) ·
**PINNED** (was already fixed; regression test added this campaign) · **NOT-REPRO** /
**BY-DESIGN** (evidence + instrument) · **RESOLVED?** (feature appears shipped — verify then
close) · **OPEN** (triaged, actionable) · **FEATURE** (awaiting maintainer priority) ·
**UNTRIAGED**.

## Bugs

| # | Title (short) | Status | Evidence | GH action |
|---|---|---|---|---|
| 179 | Slow startup with custom langpack | **FIXED** | `70284a2c` pack-first async loads; 4-10 s bulk was v1.5.0's sync neural init (ADR-011 deleted it) | comment + close on retest |
| 171 | Custom per-key mappings don't override | **FIXED** | `47969359` (±1-bin fuzz resurrected defaults) + `c29a0d87` (render overlay suppressed) | comment + close on retest |
| 169 | next/prev layout keys unremovable | **FIXED** | `e7dda022` (non-`loc` bake → now removable, value-preserving) | comment + close on retest |
| 167 | Nav bar meld + pwd manager | **PARTIAL** | Meld fixed both halves: `0288419e` (`setAttributes` write-back) + `aafec4da` (T4: `_insets_bottom` staleness — onConfigurationChanged re-derives via `WindowLayoutUtils.refreshSystemBarInsets`, one shared inset ladder, 6 mock pins); residual: pwd-manager button unresponsive (part 2 of the report, untouched) | comment; retest meld on gesture-nav A15; hold open for pwd-manager half |
| 162 | "gorgeous" never recognized | **NOT-REPRO** | Filed vs the deleted neural engine; CTC decodes it rank-1 on 27 shapes, margins +3.8..+7.3; replay instrument `afdd68a4` | comment + close |
| 161 | Portrait height changes landscape | **FIXED** | `a9c22871` (`updateConfigFromSettings` stomp; Config.refresh sole writer) | comment + close on retest |
| 160 | Language switching keeps first layout | **FIXED** | `925f0016` (named-layout selection + duplicate-tag subtype resolution) | comment + close on retest |
| 152 | Full GIF pack unusably slow | **FIXED** | `56c47fc6` (O(results×pack) write-only hydration per keystroke; index + async + debounce; ew-cli red `093b6d54`→green `ea889ac5`) | comment; close after 130k-pack soak |
| 151 | Suggestion tap leaves partial word | **PINNED**+residual-fixed | Fixed `736e4eee` (2026-07-13), zero tests then — pinned `3f698714`. T5 fixed the reporter's 2026-08-23 residual (these editors also DROP the committed trailing space → "examplew"): `9c8f5827` adds the trailing-space watch — armed at suggestion commit (cursor stamp + word), resolved on the stamp−1 dropped-space cursor signature (stamp = kept → cleared; small unmatched-callback budget survives Chrome's stale pre-commit deletion callback, device-diagnosed), owed space repaired inside the next alphanumeric keystroke's own commit (verify-at-use double-space guard; separate " " commits would be re-mangled). Fail-first `SuggestionTrailingSpaceRepairTest` (red "examplew" → OK 11). Device A/B in the Chrome omnibox: pre-fix release IME "examplew" (Pixel 8 Pro) vs fixed debug IME "example w" with full verbose log chain (Saga); well-behaved-editor control kept a single space | comment + close on retest |
| 145 | Gestures dead after reboot (swipe off) | **PINNED** | Fixed in v1.5.0 (`5e7fdcb7`, un-gated latch ate gestures); cold-init pin added in `47969359` | comment + close |
| 134 | Short-key-customization keyboard disappears | **PINNED**+residual-fixed | Reporter's exact ask (a static reopen button) shipped in v1.5.0: `6ff48751` (TopAppBar ⌨ button, `requestFocus` + `showSoftInput`; on-device green `Issue134ShowKeyboardButtonComposeTest`, source pin in `BucketBSourceContractTest`) — the report was filed on 1.3.0. T1 found + fixed the residual vanish-at-entry: the entry path used deprecated `toggleSoftInput(SHOW_FORCED)`, which HIDES an already-visible IME on entry and leaks a forced-shown IME past the activity; now the same `showSoftInput` mechanism as the button (fail-first `Issue134ImeEntryContractTest` red 2/3 → OK 3) | comment + close on retest |
| 130 | Clipboard ignores custom theme colors | **PINNED**+residual-fixed | Chrome fix shipped in v1.5.0 (`965b71e0`, `b8f17ae5`, `33c8bdcd`) — report filed on 1.3.0, where the pane showed the CleverKeysDark base (#1E1030/#2A1845, the reported purple). T1 found + fixed two residuals (`a7940256`): (1) `setConfig` never invalidated the cached pane, so a theme switch OR a color edit to the active custom theme kept the OLD colors until keyboard restart — now a pure `ClipboardPaneThemePolicy` signature (name + style res + color values) drops the pane on change; (2) entry ROWS never got runtime colors (adapter-inflated under the base style) — now painted with the theme's label/sub-label (text, button tints, provenance, timestamp). Fail-first `ClipboardPaneThemeInvalidationTest` (red: pane retained) → OK 4; `ClipboardPaneThemePolicyTest` OK 9. Visual residue for soak: inline edit-field text/hint hardcoded white (dark edit bg), search-highlight colors, filter dialog under framework dialog theme | comment + close on retest |
| 96 | Dictionary search resets after adjusting activity | **FIXED** | Toggle path was already fixed (`e46ed8c1`); wave T2 closed the RECREATION path (rotation/resize): fragment now self-saves query/sort/scroll and the initial load routes through the cancellable `filter()` so an unfiltered load can never land last and clobber the filtered list; dictionary cache kept across recreation (`if (isFinishing)`). Pinned fail-first by `DictionarySearchStatePersistenceTest` — `6c97756b` | comment + close on retest |
| 83 | keys-per-direction ignored on medium swipes | **BY-DESIGN** | `short_gesture_max_distance` IS the boundary (T9 pin); boundary additionally pinned in `47969359`; remedy = raise the setting | comment + close |
| 79 | UI/header flicker on scroll (A17 inset strip) | **OPEN-LOW** | T4 verdict: DISTINCT from the #167 residual, still unreproduced. The report is the settings *Activity* window (Compose `statusBarsPadding()` + transparent status bar, `SettingsActivity.kt:676-691`) — the `aafec4da` fix lives in the IME window's bottom/side insets and cannot reach it; staleness gives a static mis-position, not per-frame scroll flicker (ARC-114's A17 top-strip dirty-region tint remains the only observable). Reporter capture needed: screen recording of settings scroll + Android version + nav mode + whether the flickering band equals status-bar height + whether it survives "remove animations" | — |
| 77 | Can't fully disable Greek/Math toggle (custom XML) | **FIXED** | T3: the UA-pinned Fn gate was only HALF the story — the toggle the reporter sees is baked into the shipped `numeric.xml` pane as non-`loc` `key0`, and `modify_numpad` never stripped `loc` keys, so custom XML / `locale_extra_keys` / the checkbox all controlled nothing there. Fixed via the #169 pattern (`e2b64d32`): `loc switch_greekmath` in numeric.xml + `LayoutModifier.paneLocKeyStripped` (checkbox-governed `loc` pane keys stripped unless enabled). `defaultChecked` stays FALSE (pinned) — default-ON would `addExtraKeys` Greek/Math onto text layouts, worst on the reporter's own `bottom_row="false"` board. Deltas: default installs lose the numeric-pane toggle (this IS the published v1.2.8 claim; the checkbox restores it); pane `loc alt`/`loc capslock` (default-off) now follow their checkboxes like text layouts always did. Fail-first `NumericPaneGreekmathRemovalTest` (red 2/3 → OK 8; mock suite OK 568). User steps: untick nothing — default is now hidden; to KEEP the toggle, tick Extra Keys → Greek/Math | comment + close on retest |
| 75 | Swipe behaviour on Swiss French layout | **FIXED-BY-ARCHITECTURE** | T3 replay evidence (`7747fea5`): the v1.2.1 symptom (visual y-e-s → "Zeal…", i.e. decode against a hard QWERTY grid) belonged to the neural engine deleted by ADR-011. The shipping CTC path takes the DISPLAYED board's key rects as model input (`buildMappedLayout` → `CtcFeaturizer`). `CtcSwissFrenchLayoutReplayTest` re-binds the shipped decode stack to `latn_qwertz_fr_ch`'s real key centers: yes / zeal / bonjour / merci / oui / jazz all rank 1 on the Swiss geometry, while the SAME y-e-s trace on QWERTY-golden geometry decodes [zea, zeta, zee, zeus, …] — the reporter's exact old-bug shape. Pins: board stays catalogued + `script="latin"` + QWERTZ z-top/y-bottom + all 26 a–z centres + complete `CtcLayout` (the `supportsLayout` gate). fr shares the a–z alphabet/Latin encoder, so the en-lexicon replay's geometry claim transfers | comment + close on retest |
| 71 | Clipboard open stalls device | **PINNED-era** | Open path async on IO (self-documented #71 fix), 512 KB cap, pagination — verified first-hand | comment + close |
| 35 | Overly dark darkmode | **PINNED-era** | Fixed across `90c929d1`/`cc6a0b6b`/ARC-111; zero forced-dark surfaces verified at HEAD | comment + close |

## Maintainer-reported (no GH issue)

| Report | Status | Evidence | GH action |
|---|---|---|---|
| Media clipboard entries have no UI path to deletion (delete only appears in edit mode; media rows can't edit) | **FIXED** | `d3cd8dc6` — expanded media rows now show the delete row (per-tab routing: history remove / unpin / un-todo), gain the expand chevron, and the thumbnail toggles expansion; text rows keep delete behind edit mode. Store side verified + pinned: row deletion already removed the on-disk media file iff no other tab's COPY references it (no orphaning bug found). Fail-first `ClipboardMediaDeleteAffordanceTest` (red 5/8 → OK 8, drives the real `getView`); `ClipboardMediaDeletionCleanupTest` OK 7; mock suite OK 589. Note: `ClipboardPinView.kt` is dead legacy (referenced nowhere — the live pinned tab is `ClipboardHistoryView`'s and is covered). Soak: one visual tap — copy an image (browser long-press → Copy image), expand its clipboard row, delete it | — (no issue to comment on); visual tap in next maintainer soak |

## Features that appear ALREADY SHIPPED (verify, then close)

| # | Title (short) | Why it looks done |
|---|---|---|
| 68 | Greek dictionary | `langpack-el.zip` on the langpacks release + el CTC-routed since ARC-055 |
| 49 | Turkish language support | `langpack-tr.zip` shipped (tap+geometric; CTC declined by measured design — see guide) |
| 31 | Next-word prediction | Shipped (`NextWordPredictor`, GUARDED record row); defaults OFF — maintainer may flip |
| 26 | Docs: clarify language support | Round-3 doc pass (`99f5b70d`) rewrote README/wiki/FAQ to the 19-language reality |
| 58 | Scaling number keyboard | Numpad/PIN keys +20% shipped v1.2.6 (GUARDED); verify it covers the ask |
| 72 | Capitalize I + proper nouns | I-words pinned (GUARDED); sentence-start swipe caps fixed `40ad59cf`; proper-noun DICTIONARY casing deliberately not built (say so if closing) |
| 70 | Programmatic launch via Intent | An automation-intent toggle exists (seen in device-round settings); verify scope matches |
| 94 | Copy version info on long-press | Shipped `7558313b` (combinedClickable onLongClick → setPrimaryClip + toast; strings in all 22 locales; androidTest `Issue94VersionCopyComposeTest`). Wave T2 verified + hardened: payload assembly extracted pure and behaviourally pinned, wiring + 22-locale coverage pinned (`VersionCopyPayloadTest`, `1c27a10a`). Deliberate NORMAL copy — the version text enters clipboard history (not sensitive; that's the bug-reporting flow). Residual: the long-press itself needs one visual tap in the soak |

## Features awaiting maintainer priority (untouched)

#177 Pinyin · #175 hide/show advice (docs-ish) · #168 clear-clipboard key · #165 Korean ·
#163 background image · #156 encrypted clipboard · #147 remove long-swipe option ·
#143 trackpad mode · #140 spacebar-commits-highlight · #139 bottom row · #137 Whisper STT ·
#135 clear action · #133 secondary key char size · #128 lazy services (partially served by
the #179 work) · #121 custom fonts · #120 keypress sounds · #115 foldables · #111 comparison
table · #101 training game · #97 disable English dictionary · #93 hex color input · #90 custom key size · #88 Arabic (blocked: needs
lexicon + model per guide §4) · #87 long→short swipe mapping · #84 smart-punct threshold ·
#80 clipboard suggestion strip · #69 two-finger swipes · #61 many-language switching
(partially served by #160 fix + multi-language) · #52 MessagEase layout contribution.

## Closed-issue audit (2026-09-06)

Wave U1 swept the **90 closed issues** (full set — oldest is #2) for stale/auto-closures that
buried real problems. Composition: 77 COMPLETED, 9 NOT_PLANNED (all stale-bot: 7-day
auto-close after the 2026-08-23/07-26 stale sweeps, zero triage), 2 DUPLICATE, plus 2
COMPLETED-with-stale-label. 27 closures were audited in depth: every NOT_PLANNED/DUPLICATE,
plus every COMPLETED closure matching a defect class this campaign later proved real
(theme #130, height #161, mapping #171/#145, commit #151, startup #179, IME toggle #134,
GIF #152). Verdicts verified against HEAD `40b26dca`.

Headline: **the stale bot closed two REAL, still-present bugs (#148, #149) and threw away
the correct close reason on three issues that were actually FIXED (#141, #154, #146/#99)**
— their fixes landed *before* the auto-close, but nobody linked the commit, so the bot
recorded them as "not planned".

| # | Title (short) | Closed as | Verdict | Evidence | Recommended action |
|---|---|---|---|---|---|
| 148 | Clipboard opens without keyboard body / stuck behind nav bar (predictions off) | NOT_PLANNED (stale) | **STILL PRESENT** | Maintainer acknowledged the bug on-thread 2026-06-10, then stale bot closed it. Root cause live at HEAD: `PredictionViewSetup.kt:75` gates ALL top-pane containers on `word_prediction_enabled \|\| swipe_typing_enabled`; with both off, `KeyboardReceiver.kt:295-298` falls back to `setInputView(clipboardPane)` — the whole keyboard is replaced by the bare pane (reporter's exact symptom), and the raw pane misses the `aafec4da` inset ladder (the behind-nav-buttons half). Same fallback for emoji (`:229-234`) and GIF (`:326-329`) | **REOPEN** (see reopen candidates) |
| 149 | GIF zip pack inserts broken giphy link | NOT_PLANNED (stale) | **STILL PRESENT** | Reporter's dead URL has an all-lowercase ID — the smoking gun. Pipeline stores search_text fully lowercased incl. the trailing Giphy ID (`Gif.kt:82`); Giphy media IDs are case-sensitive, so `getGiphyUrl()` (`Gif.kt:85-97`) reconstructs a 404 URL, which the tap path commits as text (`KeyboardReceiver.kt:338-344`, long-press `:573`) | **REOPEN** (see reopen candidates) |
| 141 | Timestamp keys unassignable via Short Swipe Customization | NOT_PLANNED (stale) | **FIXED before close** | `f3b02b3c` (2026-05-22, 3 days after filing): TIMESTAMP ActionType + pattern editor (`CommandPaletteDialog.kt:606` carries the `issue #141` marker; `XmlAttributeMapper.kt:52-56` maps to `timestamp:'pattern'`). Stale-closed 2026-08-09 because the commit was never linked | leave closed; comment citing `f3b02b3c` + correct the close reason on retest |
| 154 | Vibration delay / no system-default haptics | NOT_PLANNED (stale) | **FIXED before close** | Diagnosed as a bug-forced `vibrate_custom=true` (master-toggle save wrote it); `ee7c4382` (2026-07-13) added the one-time migration clearing it (`Config.kt:395-411,1552-1580`); default path is low-latency `performHapticFeedback` (`VibratorCompat.kt:62-93`) = the "system default" the reporter asked for. Stale-closed 2026-08-30 | leave closed; comment citing `ee7c4382` + retest |
| 146 | Can't install Dutch language pack / no download option | NOT_PLANNED (stale) | **RESOLVED at HEAD** | `langpack-nl.zip` is on the `langpacks` release; Languages screen links to that release (`MultiLanguageSection.kt:229`); docs rewritten in the round-3 pass (`99f5b70d`) | leave closed; courtesy comment pointing at langpack-nl.zip |
| 99 | build_langpack.py docs unclear/outdated | NOT_PLANNED (stale) | **RESOLVED at HEAD** | README:353 now shows the full `--input` invocation; dedicated guide `docs/guides/adding-a-new-language.md` exists. (Hungarian itself still unshipped — that's a language ask, not this docs issue) | leave closed |
| 158 | Arabizi (digits inside words) | NOT_PLANNED (stale) | genuinely not-planned feature | Needs lexicon+tokenizer work; adjacent to open #88 Arabic (blocked per CTC guide §4) | leave closed; fold reference into open #88 |
| 89 | Google Play Store release | NOT_PLANNED (stale) | deliberate maintainer decision | Maintainer on-thread 2026-01-28: keeping CK low-profile until onboarding/docs mature | leave closed |
| 67 | build_all_languages.py can't find get_wordlist.py | NOT_PLANNED (stale) | **FIXED at HEAD** | Was a cwd-relative invocation; now `SCRIPT_DIR = Path(__file__).parent.resolve()` anchors every helper (`scripts/build_all_languages.py:50,103-140`) with a clear missing-script error | leave closed |
| 43 | Next word not predicted | DUPLICATE | correct dup of open #31 | Next-word shipped (`NextWordPredictor`, defaults OFF) — tracked in the open-features table | leave closed |
| 32 | Cancel autocorrect on backspace | DUPLICATE | correct dup of #110 (COMPLETED) | — | leave closed |
| 142 | One-click dated ZIP backup | COMPLETED (stale label) | fixed with evidence | Maintainer screenshot of the shipped feature; `backup/` subsystem at HEAD | leave closed |
| 138 | "Customize per key action" unresponsive | COMPLETED (reporter self-closed) | fixed at HEAD — reporter's "workaround" WAS the #145 defect | Self-closed on "works once swipe typing is enabled" = the un-gated latch that ate gestures when swipe was off, proven + fixed v1.5.0 `5e7fdcb7`, cold-init pinned `47969359` | leave closed (covered by #145 row) |
| 30 | Per-key keyboard-event actions do nothing | COMPLETED ("believe I addressed") | fixed at HEAD | Closed without commit evidence, but the mapping class was later re-proven and fixed: `47969359` (±1-bin fuzz resurrected defaults) + `c29a0d87` (#171 row) | leave closed |
| 129 | Editing-key gestures don't work | COMPLETED | fixed at HEAD | Same #171/#145 mapping class; closure predates proof but HEAD carries the fixes | leave closed |
| 78 | Suggestion doesn't replace typed text (flicker) | COMPLETED | premature close, since fixed | Closed 2026-05-04, but the real fix is the #151 work: `736e4eee` (2026-07-13) + T5 trailing-space repair `9c8f5827`, pinned `3f698714` | leave closed; ask reporter to retest with #151's build |
| 118 | Emoji glyphs broken in search (high DPI / custom font) | COMPLETED | fixed at HEAD | Explicit fix `225eb725` (2026-04-27) "renders '…' instead of glyphs on high-DPI/custom-font devices"; live scaling in `emoji/EmojiGridView.kt:233-245`. Reporter's "still broken in 1.4.0" predates the fix; they said "I'll reopen if still broken" and never did | leave closed |
| 114 | Custom theme background stays purple | COMPLETED | fixed, reporter-confirmed | "Solved with the last build" (reporter, 2026-03-14); residuals of the theme class later fixed in `a7940256` (#130 row) | leave closed |
| 92 | Custom background color ignored | COMPLETED | fixed, maintainer-verified | "Works now in my testing" (2026-04-26); same theme class, residuals covered by #130 row | leave closed |
| 51 | Background transparent (blur off) | COMPLETED | by-design, answered | 81% default opacity; opacity slider to 100% documented on-thread | leave closed |
| 16 | Vertical height not applying | COMPLETED | fixed, reporter-confirmed | "Yes it is, thanks!" on v1.1.77; the LATER landscape-coupling stomp was a different defect, fixed `a9c22871` (#161 row) | leave closed |
| 4 | Horizontal side margin not working | COMPLETED | fixed with evidence | Maintainer fixed the regression + added per-side control (screenshot on-thread) | leave closed |
| 123 | Crash when launching keyboard | COMPLETED | obsolete (ADR-011) | Log shows `NeuralSwipeTypingEngine`/`PredictionCoordinator` init crash — that engine is deleted; startup class separately fixed `70284a2c` (#179 row) | leave closed |
| 17 | Keyboard doesn't work (old devices) | COMPLETED | obsolete (ADR-011) | Neural-era init failure on 7-year-old hardware; reporter self-diagnosed "swipe typing doesn't work on my phone". CTC + geometric fallback replaced that stack | leave closed |
| 18 | Slow inference drops queued swipes | COMPLETED | obsolete (ADR-011) | Neural inference latency on old hardware; the shipping CTC + pure-JVM beam is the replacement; latency gated in CI (ARC-059) | leave closed |
| 136 | Swipe stops working (slider) | COMPLETED | obsolete (ADR-011) | Maintainer: the NN hot-swap slider broke inference; that surface is deleted | leave closed |
| 166 | i always capitalized with autocorrect off | COMPLETED | resolved, reporter-confirmed | Setting existed; reporter: "Oh, I missed that. Thanks" | leave closed |

### Reopen candidates (verified still-present at HEAD)

**#148 — content panes break when both predictions and swipe typing are disabled.**
Defect: `PredictionViewSetup.setupPredictionViews` (`PredictionViewSetup.kt:75`) only builds
`inputViewContainer`/`topPane`/`contentPaneContainer` when
`config.word_prediction_enabled || config.swipe_typing_enabled`. With both off, every
content-pane open in `KeyboardReceiver.handle_event_key` hits the null-container fallback
and **replaces the entire input view with the bare pane**: clipboard `KeyboardReceiver.kt:295-298`
(`keyboard2.setInputView(clipboardPane)`), emoji `:229-234`, GIF `:326-329`. Two user-visible
failures: (1) the keyboard body vanishes under the pane (reporter's video, maintainer-confirmed
2026-06-10); (2) the bare pane is not wrapped in the container that carries the
`_insets_bottom` handling (`aafec4da` ladder), so on gesture-nav devices it sits behind the
nav bar — the reporter's "cannot tap the only entry" complaint, same inset family as #167.
Repro: Settings → disable swipe typing AND word predictions → open clipboard from the keyboard.
Fix shape: build the topPane/contentPaneContainer hierarchy unconditionally (suggestion bar
itself can stay gated), or give the fallback path a proper container with inset padding; either
way delete the three `setInputView(pane)` fallbacks. Test shape: pure/robolectric pin that
`setupPredictionViews` returns non-null containers with both flags false, or an ew-cli pane-open
test under that config.

**#149 — GIF pack taps insert dead giphy.gif URLs (case-smashed IDs).**
Defect: the GIF pipeline stores `search_text` as `"keyword… giphyId"` fully **lowercased**
(`gif/Gif.kt:82` documents it; DB search normalizes to `[a-z0-9]`, `gif/GifDatabase.kt:52`).
Giphy media IDs are case-sensitive mixed-case tokens, so `getGiphyId()` → `getGiphyUrl()`
(`Gif.kt:85-97`) rebuilds `https://media.giphy.com/media/<lowercased-id>/giphy.gif` = 404.
Both commit paths use it: tap-to-insert `KeyboardReceiver.kt:338-344`, long-press `:573`.
The reporter's pasted example (`…/media/cutecdmyfhpeane9ckv6ys/giphy.gif`, all lowercase) is
this signature exactly. Fix shape: carry the ID case-preserved (own column or case-preserved
final token in the pack pipeline — pipeline lives in `tools/gif_pipeline/`, worktree
`../cleverkeys-gif-module`), or stop inserting remote URLs and commit the locally-stored GIF
via the existing `commitContent` machinery (`KeyEventHandler.kt:160-213`) — the pack ships the
media offline anyway and the app has no INTERNET permission to verify links. Repacked packs
required either way; existing imports keep dead IDs. Test shape: pure test pinning that a
mixed-case ID survives import → `getGiphyUrl()` round-trip (fails today).

### Process finding

The stale bot (7-day close after inactivity mark) is the only thing that ever set
NOT_PLANNED — all 9 such closures were unreviewed. Two buried acknowledged/real bugs and
three buried already-landed fixes under the wrong close reason. Recommendation: exempt
`bug`-labeled issues with maintainer comments from auto-close, and link fix commits
(`Fixes #N`) so completion closes carry evidence.

## Maintenance rule

Update the Status/Evidence columns when a fix lands (cite the commit); flip to CLOSED with
the close date when the maintainer closes on GitHub. New issues get a row on triage.
