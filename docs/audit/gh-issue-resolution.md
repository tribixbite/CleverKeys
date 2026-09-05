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
| 151 | Suggestion tap leaves partial word | **PINNED**+residual | Fixed `736e4eee` (2026-07-13), zero tests then — pinned `3f698714`; residual: some editors drop the trailing space (wave T5) | comment; hold open for residual |
| 145 | Gestures dead after reboot (swipe off) | **PINNED** | Fixed in v1.5.0 (`5e7fdcb7`, un-gated latch ate gestures); cold-init pin added in `47969359` | comment + close |
| 134 | Short-key-customization keyboard disappears | **OPEN** | untriaged bug — wave T1 | — |
| 130 | Clipboard ignores custom theme colors | **OPEN** | untriaged bug — wave T1 | — |
| 96 | Dictionary search resets after adjusting activity | **OPEN** | untriaged bug — wave T2 | — |
| 83 | keys-per-direction ignored on medium swipes | **BY-DESIGN** | `short_gesture_max_distance` IS the boundary (T9 pin); boundary additionally pinned in `47969359`; remedy = raise the setting | comment + close |
| 79 | UI/header flicker on scroll (A17 inset strip) | **OPEN-LOW** | T4 verdict: DISTINCT from the #167 residual, still unreproduced. The report is the settings *Activity* window (Compose `statusBarsPadding()` + transparent status bar, `SettingsActivity.kt:676-691`) — the `aafec4da` fix lives in the IME window's bottom/side insets and cannot reach it; staleness gives a static mis-position, not per-frame scroll flicker (ARC-114's A17 top-strip dirty-region tint remains the only observable). Reporter capture needed: screen recording of settings scroll + Android version + nav mode + whether the flickering band equals status-bar height + whether it survives "remove animations" | — |
| 77 | Can't fully disable Greek/Math toggle (custom XML) | **OPEN-verify** | UA pinned "Fn leaves numeric switch alone when Greek-Math is not an extra key" — mechanism may already satisfy; wave T3 verifies | — |
| 75 | Swipe behaviour on Swiss French layout | **OPEN** | untriaged — wave T3 (note: CTC is layout-agnostic since the Latin-gate widening; may be stale) | — |
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

## Features awaiting maintainer priority (untouched)

#177 Pinyin · #175 hide/show advice (docs-ish) · #168 clear-clipboard key · #165 Korean ·
#163 background image · #156 encrypted clipboard · #147 remove long-swipe option ·
#143 trackpad mode · #140 spacebar-commits-highlight · #139 bottom row · #137 Whisper STT ·
#135 clear action · #133 secondary key char size · #128 lazy services (partially served by
the #179 work) · #121 custom fonts · #120 keypress sounds · #115 foldables · #111 comparison
table · #101 training game · #97 disable English dictionary · #94 copy version info (tiny —
wave T2 takes it) · #93 hex color input · #90 custom key size · #88 Arabic (blocked: needs
lexicon + model per guide §4) · #87 long→short swipe mapping · #84 smart-punct threshold ·
#80 clipboard suggestion strip · #69 two-finger swipes · #61 many-language switching
(partially served by #160 fix + multi-language) · #52 MessagEase layout contribution.

## Maintenance rule

Update the Status/Evidence columns when a fix lands (cite the commit); flip to CLOSED with
the close date when the maintainer closes on GitHub. New issues get a row on triage.
