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

## Maintenance rule

Update the Status/Evidence columns when a fix lands (cite the commit); flip to CLOSED with
the close date when the maintainer closes on GitHub. New issues get a row on triage.
