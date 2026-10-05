# GitHub issue resolution tracker

**Living document.** Snapshot **2026-10-01** (63 open issues, 87 closed). Statuses reflect main at
`a9d8cb1d` (2026-09-30). The previous snapshot was 2026-09-05 at `8f3d6f05`. The latest tag is
**v1.5.0 (2026-07-15)**; v2.0.0 is prepped but NOT tagged, so every fix marked "unreleased" below
reaches reporters only when that tag ships, and reporters must retest on a build that contains it.
Commit hashes are the evidence, and test anchors live in the commits and the ledger
(`2026-08-28-archive-verification.md`). Local implementation updates through 2026-10-05 add
#90/#188 fixes, #145 coverage, #181 filtering and #184 bounds below; the GitHub counts remain the dated snapshot. **Nothing has been posted to GitHub.** Closing and
commenting are the maintainer's job. The "GH action" column is only a recommendation.

Statuses: **DONE** (the ask is shipped on main; close candidate) · **FIXED** (bug fixed on main,
fail-first-tested) · **PINNED** (was already fixed; regression test added) · **PARTIAL** (part
of the ask is shipped; the residual is named) · **IN PROGRESS** (being built now) ·
**NOT-REPRO** / **BY-DESIGN** (evidence + instrument) · **OPEN** (triaged, actionable) ·
**OPEN-ROOT-CAUSED** (defect located in code, unfixed) · **NEEDS-REPRO** (plausible, cannot be
settled from source alone).

## Summary (GitHub snapshot 2026-10-01; implementation update 2026-10-05)

| Bucket | Count | Issues |
|---|---|---|
| DONE/FIXED but still open (close candidates) | 35 | bugs: #188 #184 #90 #179 #171 #169 #161 #160 #154 #152 #151 #149 #148 #146 #145 #141 #134 #130 #99 #96 #77 #75 #71 #67 #35 · features: #135 #111 #94 #93 #70 #68 #58 #49 #31 #26 |
| Close with explanation (NOT-REPRO / BY-DESIGN) | 2 | #162 #83 |
| PARTIAL | 9 | #181 #175 #167 #156 #101 #97 #88 #80 #72 |
| Still open | 17 | bugs: #186 #79 · features: #187 #177 #168 #165 #163 #147 #139 #121 #120 #115 #87 #84 #69 #61 #52 |

What changed since 2026-09-05:
- The maintainer reopened nine bot-closed but resolved issues on 2026-09-21 (#67 #99 #130 #134
  #141 #146 #148 #149 #154) so they can be closed as completed by hand. The stale bot was
  removed in `169feef6`.
- Five new issues were filed (#181, #184, #186, #187, #188).
- The bot closed five feature issues on 2026-09-13, before it was removed. One of them (#133) was
  already shipped. See the stale-closed re-check below.
- **#145 attribution corrected.** v1.5.0's `5e7fdcb7` was not the fix. The real cause was a
  `swipe_typing_enabled` gate on the service-handle wiring, and the fix is `8d3ac943`, which is
  unreleased. A third reporter (2026-09-10) still sees the bug on v1.5.0, which matches this.
- **#90 reclassified** from feature to bug, with its root cause located (the #58 numpad-scaling
  proxy).
- **Ten feature issues were found already shipped**, most of them in v1.5.0: #135 #111 #93 #94
  #70 #68 #58 #49 #31 #26.

## Bugs

| # | Title (short) | Status | Evidence | GH action |
|---|---|---|---|---|
| 188 | Compose key does not work | **FIXED locally; Seeker retest passed (2026-10-05)** | Reproduced on Seeker: Compose activates visually, then the next letter commits raw. The deferred navigation-subkey tap branch emits key-up and immediately clears the pending compose state. `97d6c25f` routes taps through the shared latch/unlatch lifecycle instead; arrow flicks retain their own handling. A fail-first pointer test reproduced premature commit; a second protects exactly-once ordinary dispatch. Kotlin compilation and 2,737 pure / 921 mock tests pass. Minified APK installed with matching hash; both é sequence orders, pending-state silence, cancellation and arrow navigation pass on Seeker. | retest composed output, cancel, arrows and ordinary typing before closure |
| 186 | Autocorrect only works for the primary language | **OPEN** (confirmed in code; new 2026-09-22) | `WordPredictor.autoCorrect` (`WordPredictor.kt:2876`) consults only the primary `dictionary`. Layouts carry no language binding, so `switch_forward` to a `script="persian"` layout leaves fa as secondary, and only primary-language typos get corrected. Workaround today: bind the `primaryLangToggle` command (`CommandRegistry.kt:566`) to a key or short swipe. Proper fix: a per-layout language binding that drives the primary language on layout switch. That is the same feature #61 asks for, and the reporter links a fork commit (`mostafaqanbaryan/CleverKeys@7c91d8f4`) that adds a `language` layout attribute as reference. | comment with the toggle workaround; fix with #61 |
| 184 | 1M-word dictionary crashes | **FIXED locally (2026-10-03); device refusal verified** | Import now refuses actual CKDT header counts above 100,000 with a translated smaller-pack message. Shared tap/geometric/CTC readers reject oversized installed dictionaries before count-sized allocation. Dictionary/member bytes are bounded at 16 MiB, model at 8 MiB, manifest/NOTICE at 64 KiB; ZIP aggregate 64 MiB/64 entries. Unsafe names and duplicate basenames are refused; rejected updates preserve the old pack. Kotlin compilation and full suites pass: 2,737 pure / 919 mock (6m43s). Seeker Settings refuses a real-header million-word fixture despite manifest wordCount=1; installed count remains 0 and UI stays responsive. Existing-pack rollback remains host-tested because Seeker has no installed packs. This bounds inputs, not every possible heap use. | verify refusal/retained pack/continued typing on Seeker before closure |
| 181 | Monet Auto does not follow system dark mode; some emoji render as tofu | **PARTIAL** (glyph filtering implemented locally, 2026-10-03; Monet still NEEDS-REPRO) | `EmojiGridView` filters categories, recents and search using the actual themed cell paint. Whole Unicode sequences must have a glyph; the final text-emoticon group is checked per visible codepoint. Cache is grid-scoped and recent data is preserved. Focused tests 7/7; Kotlin compilation and full suites 2,733 pure / 912 mock pass. Refreshed minified APK builds and verifies; Installed on Seeker: sampled smiley/text-face grids and actual-keyboard face search render; reopening resets search and recents remain empty. Forced unsupported-font and Monet checks remain pending. The Monet configuration-change path remains wired but needs an on-device day/night reproduction. | test glyph filtering and Monet live update before closure |
| 179 | Slow startup with custom langpack | **FIXED** (unreleased) | `70284a2c` pack-first async loads. The 4-10 s bulk was v1.5.0's sync neural init, which ADR-011 deleted | comment + close on v2.0.0 |
| 171 | Custom per-key mappings don't override | **FIXED** (unreleased) | `47969359` (±1-bin fuzz resurrected defaults) + `c29a0d87` (render overlay suppressed) | comment + close on v2.0.0 |
| 169 | next/prev layout keys unremovable | **FIXED** (unreleased) | `e7dda022` (non-`loc` bake → now removable, value-preserving) | comment + close on v2.0.0 |
| 167 | Nav bar meld + pwd manager button | **PARTIAL** | Meld: both halves fixed (`0288419e` `setAttributes` write-back + `aafec4da` inset re-derive on config change). **Residual OPEN:** the inline-autofill (password manager) chip in the suggestion bar's top-left stopped responding to taps. The reporter says it broke "2 or 3 releases" before v1.5.0, which puts the window at v1.3.0, where all three autofill-touching commits landed: `83c547df` (#48 inline autofill), `b990f7f7` (#109 chip style), `f13d5174` (#109 display cutoff). Needs a device repro with Bitwarden/KeePassDX on API 30+ | comment; retest meld; keep open for the autofill half |
| 162 | "gorgeous" never recognized | **NOT-REPRO** | Filed against the deleted neural engine. CTC decodes it rank-1 on 27 shapes, margins +3.8..+7.3. Replay instrument `afdd68a4` | comment + close |
| 161 | Portrait height changes landscape | **FIXED** (unreleased) | `a9c22871` (`updateConfigFromSettings` stomp; `Config.refresh` is the sole writer). Contributor PR #164 targets the same bug and is superseded | comment + close on v2.0.0; close PR #164 as superseded |
| 160 | Language switching keeps first layout | **FIXED** (unreleased) | `925f0016` (named-layout selection + duplicate-tag subtype resolution) | comment + close on v2.0.0 |
| 154 | Vibration delay / no system haptics | **FIXED** (v1.5.0) — reopened 2026-09-21 | `ee7c4382` one-time migration clears the bug-forced `vibrate_custom=true` (`Config.kt:476`). The default path is `performHapticFeedback` | close as completed citing `ee7c4382` |
| 152 | Full GIF pack unusably slow | **FIXED** (unreleased) | `56c47fc6` (index + async + debounce; ew-cli red `093b6d54` → green `ea889ac5`) + GIF audit wave (`d3fc3d5b`, `f6c802d4`) | comment; close after 130k-pack soak |
| 151 | Suggestion tap leaves partial word | **FIXED** (unreleased) | `736e4eee` + pin `3f698714`. Trailing-space residual fixed in `9c8f5827` (device A/B: pre-fix "examplew", fixed "example w") | comment + close on v2.0.0 |
| 149 | GIF pack inserts dead giphy link | **FIXED** (unreleased) — reopened 2026-09-21 | `ecd1abc8` + `56597c1b`: taps commit the local WebP via commitContent; the URL fallback uses the case-preserved `gid:` token (`gif/Gif.kt:149`). Old pack ZIPs need a rebuild to get the URL fallback | close as completed citing both; note the repack |
| 148 | Clipboard opens without keyboard body | **FIXED** (unreleased) — reopened 2026-09-21 | `cb7cebd4` (container gate) + `56597c1b` (no `setInputView(pane)` left in `KeyboardReceiver`; only comments at `:304`/`:552`) | close as completed citing both |
| 146 | Can't install Dutch pack | **RESOLVED** — reopened 2026-09-21 | `langpack-nl.zip` is on the `langpacks` release (re-uploaded 2026-09-27), linked from the Languages screen | close as completed |
| 145 | Per-key gestures dead after reboot (swipe typing off) | **FIXED** (unreleased) — **attribution corrected** | Real root cause: v1.5.0's `PredictionInitializer.initializeIfEnabled` wired the keyboard view's service handle (`setSwipeTypingComponents`) only when `swipe_typing_enabled`. With swipe typing off at cold start (reboot, keyboard switch), `_keyboard2` stayed null and `onCustomShortSwipe` returned early with "no service reference". Toggling swipe typing on re-wired it through `PredictionViewSetup`, and turning it off again left the handle set, which is exactly the reported workaround. The fix is `8d3ac943` (2026-08-18: wiring moved outside every config gate), kept by `fddb65d5` (`KeyboardComponentGraph.wireSwipeTypingComponents`). `5e7fdcb7` (v1.5.0) fixed a different latch defect, and the `47969359` mock pin cannot model a cold service start. A third reporter (kxtbit, 2026-09-10, presumably on v1.5.0) confirms the bug persisted. **Regression coverage (2026-10-02):** `KeyboardViewLateBindingDriftTest.coldViewGetsServiceHandleWithPredictionAndSwipeTypingDisabled` executes the real graph wiring and view setter with both prediction settings off, a null predictor and a fresh/replacement view; checks the actual service handle and verifies no model load. Full suites pass (2,734 pure / 900 mock) | comment explaining the real fix; maintainer cold-start test, then close after release |
| 141 | Timestamp keys unassignable | **FIXED** (v1.5.0) — reopened 2026-09-21 | `f3b02b3c` (TIMESTAMP ActionType + pattern editor) | close as completed |
| 134 | Short-key-customization keyboard disappears | **FIXED** (unreleased) — reopened 2026-09-21 | Reopen button shipped v1.5.0 (`6ff48751`). The vanish-at-entry residual is fixed in `a9b000cf` (`showSoftInput` instead of `toggleSoftInput(SHOW_FORCED)`; `Issue134ImeEntryContractTest`) | close as completed citing `a9b000cf` |
| 130 | Clipboard ignores custom theme colors | **FIXED** (unreleased) — reopened 2026-09-21 | Chrome fixed in v1.5.0. Cached-pane invalidation + row colors in `a7940256`; emoji/GIF panes got the same treatment in `7144e2c7` (H-3). Visual residue for the soak: inline edit-field text, search highlight | close as completed citing `a7940256` |
| 99 | build_langpack.py docs unclear | **RESOLVED** — reopened 2026-09-21 | README shows the full `--input` invocation, and `docs/guides/adding-a-new-language.md` exists | close as completed |
| 96 | Dictionary search resets after recreation | **FIXED** (unreleased) | `e46ed8c1` (toggle path) + `6c97756b` (recreation path, `DictionarySearchStatePersistenceTest`) | comment + close on v2.0.0 |
| 90 | Custom size for keyboard/row (bug: `bottom_row="false"`) | **FIXED** (local, unreleased, 2026-10-02) | `Theme.Computed` now checks explicit `layout.numpad_height`, parsed from XML with default false and preserved by every layout transformation. Built-in numeric/PIN opt in; custom bottom-row-free layouts retain normal row sizing. Two fail-first tests measured the old error (400px instead of 101.27px for a single row). `NumpadKeySizeTest` covers real parsing/geometry, custom row heights/shifts, numeric/PIN toggle behavior, metadata copies and screen-height cap: 12/12 pass; full suites 2,729 pure / 912 mock pass. See `docs/specs/layout-system.md` | maintainer device check; close after release |
| 83 | keys-per-direction ignored on medium swipes | **BY-DESIGN** | `short_gesture_max_distance` IS the boundary (pinned in `47969359`). The "200=disabled" label was never implemented and is retired (`Config.kt:712`). The ask overlaps #147/#87 | comment + close; point to #87 |
| 79 | Settings header flicker on scroll (A17) | **OPEN-LOW** | Distinct from #167. The Wave-K device pass did not reproduce it (`docs/eval/2026-09-02-wave-k-device-verification.md`). The only observable is ARC-114's A17 top-strip tint. Needs a reporter capture | ask for a screen recording, or close as not-repro |
| 77 | Can't disable Greek/Math toggle (custom XML) | **FIXED** (unreleased) | `e2b64d32` (`loc switch_greekmath` in numeric.xml + `paneLocKeyStripped`) | comment + close on v2.0.0 |
| 75 | Swiss French swipe | **FIXED-BY-ARCHITECTURE** | `7747fea5` replay: CTC decodes on the displayed QWERTZ geometry (yes/zeal/bonjour/merci/oui/jazz all rank 1) | comment + close on v2.0.0 |
| 71 | Clipboard open stalls device | **PINNED-era** | Open path async on IO, 512 KB cap, pagination | comment + close |
| 67 | build_all_languages.py can't find get_wordlist.py | **FIXED** — reopened 2026-09-21 | `SCRIPT_DIR = Path(__file__).parent.resolve()` (`scripts/build_all_languages.py:50`) | close as completed |
| 35 | Overly dark darkmode | **PINNED-era** | `90c929d1`/`cc6a0b6b`/ARC-111 | comment + close |

## Features

| # | Title (short) | Status | Evidence | GH action |
|---|---|---|---|---|
| 187 | Accents: macron below + ring below | **OPEN** (new 2026-09-25) | ISO 15919 transliteration (ṟ ḻ r̥). The command catalogue has `accent_macron`/`accent_ring`/`accent_dot_below` and `combining_macron`/`combining_ring`, but no *below* variants (U+0331, U+0325) and no matching `src/main/compose/accent_*.json`. Small: two dead keys + compose tables + Extra Keys checkboxes + 21-locale strings | accept; S |
| 177 | Pinyin IME (zh-Hans/zh-Hant) | **OPEN** — contributor PR #183 | PR #183 (Macho0x, +2934/−12, 24 files): pack schema, `CKPY` phrase table, tap engine, swipe feed. The PR itself says device validation and pack data are still owed | review PR #183 |
| 175 | Hide/summon keyboard + clipboard bulk delete + toolbar + editing panel | **PARTIAL** — part 1 **IMPLEMENTED, device checks partial (2026-10-05)** | (1) `01b6212d` implements `minimize_bar`/`minimize_fab` commands; Seeker verifies FAB minimize/expand and bar resize/expand, typing after expand, and full-size restoration after hide. Landscape transparent-strip scrolling and minimize/expand also pass on the launcher; RTL and cross-app checks remain pending. Summoning an already-hidden keyboard remains unbuilt (overlay permission would be required). (2) **IMPLEMENTED locally (2026-10-05)**: size filters combine with search and existing predicates; Delete results requires confirmation for a frozen all-page/current-tab snapshot. Changed/new entries and copies in other tabs remain; media cleanup checks references. Automated and device verification are tracked in `memory/todo.md`. Swipe-to-delete remains open. (3) **OPEN**: no customizable toolbar (same ask as #80 part 3). (4) **PARTIAL**: the actions exist as commands (`selectAll`, `cut`, `copy`, `paste`, `home`/`end`, `doc_home`/`doc_end`, `cursor_up`/`down`, `selection_mode`, `selection_cursor_*`) and can be bound to short swipes or the new subkey popover (`fde558cd`). The maintainer confirmed this on-thread 2026-09-29. There is no dedicated editing pane | update thread when (1) lands; (2) next |
| 168 | Clear-clipboard key | **OPEN** | No `clear_clipboard` command in `CommandRegistry`. Building blocks exist: `ClipboardManager.clearPrimaryClip()` is already used at `ClipboardHistoryService.kt:264`, and `clearHistory()` has no callers. Pairs with #175 part 2 | S; do with #175(2) |
| 165 | Standard Korean behavior | **OPEN** | Only the upstream-inherited `hang_dubeolsik_kr.xml` with modifier-based Hangul composition (`KeyModifier` `Hangul_initial`/`Hangul_medial`). No standard automaton and no ko pack | L; needs a Korean-typing spec |
| 163 | Background image | **OPEN** | No background-image support (`git grep` finds no hits). Theme Creator fields all wired (`c9939571`) | M |
| 156 | Encrypted clipboard | **PARTIAL** | Ask 1 (OS clipboard never sees plaintext) **shipped, unreleased**: private copy/paste `4719feff`, short-swipe wiring `fde5e604`, leak fixes `cc91d127`, no system-clipboard fallback for private media `21a320ba` (ARC-001), provenance line `05db07eb`, schema V5 `is_private`. Ask 2 (at-rest encryption of the history DB) is **design-only**: `docs/plans/156-at-rest-clipboard-encryption.md` (PROPOSED, migration V6) | comment: ask 1 ships in 2.0; keep open for at-rest |
| 147 | Option to remove long swipe gestures | **OPEN** | Three users. No toggle exists, and the "200=disabled" label was never implemented (retired, `Config.kt:712`). With swipe typing off, a beyond-boundary gesture taps the start key (`5e7fdcb7`). Partial relief: the hold-then-select subkey popover (`fde558cd`, default ON for fresh installs only) gives a precision path to subkeys. Same mechanism as #87 | S-M; build with #87 |
| 139 | Change the bottom row | **OPEN** | No bottom-row editor UI. Workaround: a custom layout with `bottom_row="false"` plus its own row (the #60 answer), but on released builds that workaround triggers #90's stretch. Thread sub-ask (two users): Enter shows as the editor's action (search/send) and a stray tap sends. That is the upstream editor-driven behaviour (`swapEnterActionKey`, `LayoutModifier.kt:244`). A "prefer Enter over action" option would answer it | M (editor UI); S for the Enter option |
| 135 | `clear` action | **DONE** (v1.5.0) | `c4778d9d`: `clear` key value (`KeyValue.kt:737`, `Editing.CLEAR`), handler `KeyEventHandler.kt:725/764`, command palette entry (`CommandRegistry.kt:125`), a11y label | close as completed |
| 121 | Custom fonts | **OPEN** | Only the bundled `special_font.ttf` (`Theme.kt:461`). No user font import | M |
| 120 | Keypress sounds | **OPEN** | No sound feedback code (`git grep` finds no `playSoundEffect`/`SoundPool` hits). A contributor (synthyst) offered a PR on 2026-04-24 and got no reply | answer the PR offer; S-M |
| 115 | Foldable usability | **OPEN** | Exists: per-fold heights (`_portrait_unfolded`/`_landscape_unfolded`, `Config.kt:1268-1307`) and per-side margins. Not built: floating keyboard, split layout, half-width swipe zone. Prior art: the closed PR #100 (free-floating/resizable mode) | L |
| 111 | README comparison: add Urik | **DONE** (v1.5.0) | `bedd193a` added the Urik column + footnotes ¹⁰ ¹¹ (`README.md:59`) | close as completed |
| 101 | Autocorrect training game | **PARTIAL** | The commenters' real complaint (tap autocorrect too literal: "tge", "wuestion") is served by `d7f72597` adjacency-weighted autocorrect (v1.5.0), `87338e35`, and the typo-tolerant tap bar `f8fa86f6` (unreleased). The game itself is not built, and on-device model training is impossible in shipped ORT (verdict recorded in round 9-10, HANDOFF) | comment; close as not-planned for the training half, or keep as a practice-mode idea |
| 97 | Disable the English dictionary | **PARTIAL** | Reporter's case: a non-English primary via multi-language works (maintainer, 2026-04-17), but **no `langpack-pl` exists** on the langpacks release, so a Polish user must build one. Commenter's case (user-dictionary-only English): not built. Partial alternatives: smaller English packs (`langpack-en-wordfreq`/`-opensubtitles`), disabled words | comment; a "no base dictionary" option is S-M |
| 94 | Copy version info on long-press | **DONE** (v1.5.0) | `7558313b` + pins `1c27a10a` | close as completed |
| 93 | Hex color input in theme editor | **DONE** (v1.5.0) | `1015087e` editable hex input in `ColorPickerDialog` | close as completed |
| 88 | Arabic | **PARTIAL** (path open) | CTC stays blocked (guide §4), but imported non-Latin packs already work for tap + geometric swipe (the #186 reporter types Persian from a pack; `9ae7005c` letter gate accepts every script). The missing piece is a pack: contributor **PR #182** adds a 50k-word Arabic pack (binary, +0/−0). Needs provenance/licence review against the data-licensing audit (`638d492b`) and an attribution NOTICE | review PR #182; ship as a langpack, then close |
| 87 | Long swipe → short swipe when swipe typing is off | **OPEN** | With swipe typing off a long gesture taps the start key (`5e7fdcb7`, by design per #83). The request is a mode where, with swipe typing off, every displacement resolves to the nearest direction's subkey. Two users (one with a Thumb-Key-style layout at 250 px). Same mechanism as #147 | S-M |
| 84 | Smart punctuation time threshold | **OPEN** | `smart_punctuation` is a plain boolean (`Config.kt:727`), with no interval | S |
| 80 | Clipboard suggestion strip + nav | **PARTIAL** | Part 2 (close buttons) shipped v1.2.8, pinned `6fd2bfbe`. Part 1 (offer the latest clip in the suggestion strip) and part 3 (toolbar) are not built. Part 3 is the same as #175(3) | comment; keep open for part 1 |
| 72 | Capitalize I + proper nouns | **PARTIAL** (close-able) | I-words pinned (`0b31edc0`). User-dictionary original case for tap + swipe (`49b8a970`, incremental-add case `d94affc1`). Sentence-start caps `40ad59cf`. Base-dictionary proper-noun casing is deliberately not built (adding "Boston" to the user dictionary is the remedy) | comment + close, stating the proper-noun scope |
| 70 | Programmatic launch via Intent | **DONE** (v1.4.0) — **contract changes in 2.0** | Six `am start` actions (`BackupRestoreActivity.kt:51-56`). The `json_base64` extra bypasses scoped storage for files edited by other apps (`75e6e89e`, v1.4.0), which answers Hubert21's copy/vim failure. **2.0 change:** headless actions now require a stored backup password and **refuse plaintext imports** (`754e0889`, unreleased; ARC-031/032/033), and an `--es passphrase` import override exists behind a toggle. The reporter's edit-JSON-then-import loop no longer works headlessly | comment explaining both; close |
| 69 | Two-finger swiping | **OPEN** | Not built. Large (multi-pointer swipe capture + decoder support) | L; maintainer call |
| 68 | Greek dictionary | **DONE** (v1.5.0) | `langpack-el.zip` on the langpacks release; el CTC-routed (ARC-055). The reporter's 1M-word pack crash is tracked separately as #184 | close as completed; point to #184 |
| 61 | Switch among 3+ languages | **OPEN** | Exists: two primaries + two secondaries via toggles. The reporter wants language bound to layout, which is exactly #186's fix | M; fix with #186 |
| 58 | Scale number keyboard | **DONE** (v1.2.6) | `bd8cbafe` + `0f711855` (maintainer confirmed on-thread). Note: its `!bottom_row` proxy is #90's root cause | close; fix #90 |
| 52 | MessagEase layout contribution | **OPEN** (stale contribution) | The layout XML was never submitted as a PR. The maintainer reworked subkey activation (2026-07-20), and the subkey popover (`fde558cd`) adds a hold-and-slide path | ask for a PR or close |
| 49 | Turkish | **DONE** | `langpack-tr.zip` (tap + geometric; CTC declined by measured design) + `res/values-tr` UI | close as completed |
| 31 | Next-word / Russian completion | **DONE** | The thread is really about Russian word completion: `langpack-ru` (`32f93f1d`, v1.5.0; ru swipe announced for 2.0 in `f6cc401d`, validation-only tier). Next-word itself is default ON with a static tier (`a90a6489`, unreleased) | close as completed |
| 26 | Docs: clarify language support | **DONE** | `99f5b70d` round-3 doc pass | close as completed |

## Linked pull requests (open)

| PR | Relates to | State / recommendation |
|---|---|---|
| #189 short swipes fire on keys with no center value (+65/−1, with test) | upstream #104 class; custom center-less layouts (#52/#87 users) | small, tested; review |
| #183 Pinyin composing IME (+2934) | #177 | review; owes device validation + pack data |
| #182 Arabic 50k-word pack | #88 | provenance/licence review, then publish as a langpack |
| #164 landscape height | #161 | superseded by `a9c22871`; close with thanks |
| #185 actions bump, #173 README star chart | — | routine |

## Maintainer-reported (no GH issue)

| Report | Status | Evidence | GH action |
|---|---|---|---|
| Media clipboard entries have no UI path to deletion | **FIXED** | `d3cd8dc6`: expanded media rows show the delete row (per-tab routing), gain the chevron, and the thumbnail toggles expansion. Fail-first `ClipboardMediaDeleteAffordanceTest`. Wiki updated `2edc9976`. Soak: one visual tap | — |
| Custom and unusual words are harder to swipe than they should be | **FIXED** | Stored 1..255 user frequency is calibrated per consumer (`UserWordFrequency.scaleOnto`). "bowien" @100 went from rank 3 to rank 1 (+2.23). Residual: base words at the 134 floor keep the λ-by-design prior. Follow-on: "Prefer X when swiping?" offer `de7d0e87` and joiner preference `1bb0ddb4` | — |

## Closed-issue audit (2026-09-06, updated 2026-10-01)

Wave U1 swept the **90 closed issues** of 2026-09-06 for stale or auto-closures that buried
real problems (verdicts verified at `40b26dca`). The headline was that the stale bot had closed
two REAL bugs (#148, #149) and discarded the correct close reason on resolved ones.
**Follow-up as of 2026-10-01:** both bugs were fixed (`56597c1b`, `ecd1abc8`). The stale bot was
removed (`169feef6`). On 2026-09-21 the maintainer **reopened all nine** bot-closed but resolved
issues (#67 #99 #130 #134 #141 #146 #148 #149 #154) so they can be closed as completed by hand.
They now appear in the Bugs table above. The other verdicts stand:

| # | Title (short) | Closed as | Verdict | Evidence |
|---|---|---|---|---|
| 158 | Arabizi | NOT_PLANNED (stale) | genuinely not-planned; leave closed | Needs lexicon + tokenizer work; adjacent to #88 |
| 89 | Play Store release | NOT_PLANNED (stale) | deliberate maintainer decision | Maintainer on-thread 2026-01-28 |
| 43 | Next word not predicted | DUPLICATE | correct dup of #31 | #31 now DONE |
| 32 | Cancel autocorrect on backspace | DUPLICATE | correct dup of #110 | — |
| 142 | Dated ZIP backup | COMPLETED (stale label) | fixed | `backup/` subsystem |
| 138 | "Customize per key action" unresponsive | COMPLETED (self-closed) | **evidence corrected 2026-10-01**: the reporter's "works once swipe typing is enabled" is the #145 service-handle gate. It was fixed in `8d3ac943` (unreleased), not `5e7fdcb7` | see #145 |
| 30 / 129 | Per-key / editing-key gestures do nothing | COMPLETED | fixed at HEAD | `47969359` + `c29a0d87`. Any "only works with swipe typing on" variant is the #145 gate (`8d3ac943`) |
| 78 | Suggestion doesn't replace typed text | COMPLETED | premature close, since fixed | `736e4eee` + `9c8f5827` |
| 118 | Emoji glyphs broken in search | COMPLETED | fixed (`225eb725`) | A distinct tofu class, missing system glyphs, is open as #181(2) |
| 114 / 92 | Custom theme background | COMPLETED | fixed, confirmed | residuals `a7940256` |
| 51 / 16 / 4 / 166 | opacity, height, margin, i-caps | COMPLETED | fixed or answered | — |
| 123 / 17 / 18 / 136 | neural-era crashes and latency | COMPLETED | obsolete (ADR-011) | — |

### Stale-closed re-check (2026-10-01): closures since the last audit

The bot closed five feature issues on **2026-09-13** (7-day close after the 09-06 stale marks).
That was one week before the bot was removed. HANDOFF (2026-09-21) records the maintainer's
decision that these stay closed as "unbuilt feature asks". The re-check found that one of them is
**not** unbuilt:

| # | Title (short) | Verdict | Evidence | Recommended action |
|---|---|---|---|---|
| 133 | Separate size for secondary labels | **DONE before close — mislabeled** | `92296e34` (2026-06-02, **v1.5.0**): independent Secondary Label Size control (earlier factor constant `c4778d9d`). The bot recorded "not planned" on a shipped feature | reopen + close as completed citing `92296e34` (same treatment as the nine) |
| 128 | Lazy-load services (~300 MB) | **substantially addressed**; closed is fine | Neural engine deleted (ADR-011), pack-first async loads `70284a2c`, APK 44→21 MB, and retired-service retention cut from ~41 MB to ~0.55 MB each (`358cd54b`, `d8250a6d`; 2026-09-10 OOM audit). Bilingual heap ≈ 87 MiB | leave closed; optional courtesy comment with the numbers |
| 140 | Space accepts highlighted suggestion | unbuilt; closed per decision | No such option (only auto-space settings). It is cheap and standard in other keyboards | leave closed, or reopen as a small feature (listed in Recommended next) |
| 143 | Whole-keyboard trackpad mode | unbuilt; closed per decision | Design spec only (`7f967c6b`, 2026-05-22, "planned") | leave closed; the spec preserves the design |
| 137 | Offline Whisper STT | unbuilt; closed per decision | Out of scope for a no-INTERNET, pure-Kotlin IME without a large model budget | leave closed |

No other issue was closed after 2026-09-06. COMPLETED closures before that date were covered by
the 2026-09-06 audit, apart from the #138 correction above.

### Process finding (updated)

The stale bot (`community-health.yml`) was the only source of NOT_PLANNED closures, and it was
removed on 2026-09-20 (`169feef6`). It mislabeled **six** shipped or fixed items in all (#141
#154 #146/#99 #67 and now #133) and buried two real bugs. The surviving gap is evidence
linkage: commits cite `#N` in the subject but are never pushed as `Fixes #N`, so nothing closes
on merge and tracker rows like #145 can carry the wrong fix for months. Recommendation: put
`Fixes #N` in fix-commit bodies, and close issues in bulk with commit links when v2.0.0 is
tagged.

## Recommended next (2026-10-01)

### Seeker device pass and short-word investigation (2026-10-03)

The maintainer requested the full manual checklist on `192.168.0.170:5555`.
This pass is **blocked, not signed off**: the device initially responded, then lost
Wi-Fi during the first test-field interaction. Subsequent transport reconnects returned
`No route to host`. No keyboard interaction or checklist item was verified successfully.

- Installed APK remains the previously verified minified build, SHA-256
  `9014e7ee7e0c9a52fdd71839eb9ac30a2fba839958df38d71262c974c09acd9d`.
- Initial focus: Android SearchLauncher, task 860. CleverKeys LauncherActivity opened
  successfully and its test field was located through the UI hierarchy. The focus-field
  request stalled; screenshot/IME-state requests also stalled. Host requests were cancelled
  and the ADB transport reconnected without restarting Android.
- No settings, dictionary, layouts, clipboard or learned data were intentionally changed.
  TODO: once the device reconnects, inspect current focus, remove the temporary
  `/sdcard/cleverkeys-test-ui.xml` dump and return to the original Android launcher.
- The maintainer-owned checklist is untouched. Fresh-install, reset and reboot checks
  need separate authorization; they cannot be inferred from an in-place install.
- Read-only GitHub refresh still reports 63 open issues. No external posts or closures.

#### Confirmed local recognition gap: “wet”

The shipped English lexicon contains `wet` (frequency byte 195). The real shipped
ONNX model and production CTC beam/trie were exercised through `CtcReplayEngine`,
using the committed golden layout and the existing bionic ORT natives. This tests decoding
independently of Android touch classification; it does **not** test the Seeker's live geometry.

| Input | Result |
|---|---|
| Canonical w→e→t; 4/12/24 samples per segment × 8/20 ms per sample | `wet` rank 7 in all six variants; greedy `we` |
| Existing local corpus: two `wet` traces | One rejected for nonmonotonic timestamps; the one valid trace ranks `wet` 6, returns `we` first |
| Endpoint dwell, 4 extra samples × 16 ms (64 ms), five small midpoint arcs | `wet` rank 3 in all five variants; still `we` first |
| Longer dwell, 12 extra samples × 16 ms (192 ms), five arcs | `wet` rank 4–5; still `we` first |
| Twelve endpoint offsets (x −0.04/0/+0.04/+0.08; y −0.05/0/+0.05 in normalized frame) | `wet` rank 4–8; no rank-1 recovery |
| Controls, six variants per word | `we`, `were`, `west`, `get`, `yet`, `red`, `try`, `git` rank 1 throughout; `pet` ranks 1–2, `hello` 1–2, `tree` 3–4 |

These are **one failing word with repeated controlled variants, plus one independent valid
corpus trace**, not six independent user failures. The `tree` result is an additional synthetic
warning, not a confirmed device bug. Local diagnostic output: `build/wet-probe.log` (ignored).

On the canonical trace, forced scoring gives `we`: CTC −0.340, log-frequency 5.451,
final 22.122; `wet`: CTC −6.489, log-frequency 5.273, final 19.428. Under the shipped
`ctc / length^0.9 + 0.25 * length + 4 * logFreq` formula, the raw CTC difference is
length-normalized; frequency contributes about 0.712 of the 2.694 final-score gap.
The missing final letter in greedy decoding and the stronger emission loss make an
endpoint/short-path recognition investigation more promising than a word-list patch.

Practical interim checks: scroll the suggestion strip to reach `wet` (the engine returns eight
candidates); a brief endpoint pause may put it in the first three, but is **not a reliable fix**.
Tap typing is the dependable fallback. Trying the geometric engine is an A/B experiment,
not a verified workaround. Repeated usage alone must not be promised to fix this: the
experimental CTC learned prior is not enabled in the shipping adapter, and context rescoring's
0.5 score-ratio guard cannot promote `wet` at 44/645 on this trace.

TODO: capture the maintainer's real failed `wet` traces in the playground, record active layout,
engine and gesture settings, inspect final-letter emissions/resampling, and compare geometric
decoding. Keep a protected `we` control and a broader short-word corpus when evaluating a fix;
do not special-case `wet` or weaken global ranking guards from this small sample.

**Reconnected-device follow-up:** the Seeker's live IME reproduced `wet`→`We` with
`input swipe 190 1900 540 1900 350` on the displayed QWERTY row; the test field read
`We ` and current logcat recorded `SWIPE_TYPING completion` followed by a three-character
commit. This confirms the touch pipeline reaches prediction rather than dispatching a subkey.
Popover hold/neutral release, empty t/South assignment, saved-action dwell-to-edit,
FAB minimize and expansion were verified. Bar minimize resized the app and rendered
the strip; expansion was not verified before another Wi-Fi drop. The temporary mapping
and popover switch still need restoration (see working todo).

**Geometric comparison:** the production geometric engine with its default configuration,
full 98,140-word English CKDT dictionary and the shipped QWERTY geometry ranks `wet`,
`we`, `tree`, `get`, `yet`, `pet` and `git` first on their canonical 12-step-per-segment
paths (`hello` second). These are eight distinct clean synthetic words, not a human-trace
accuracy estimate. On the one usable existing human `wet` trace, geometric also ranks
`wet` first, compared with CTC rank 6; this is one trace, not broad human validation.
`build/wet-geo-probe.log` holds local output. This comparison is diagnostic evidence, not a per-word engine-switching workflow.
A general default-engine fix needs real-finger short-word and wider corpus validation. No cross-engine score merge or CTC ranking change was made.

**Current test artifact:** source `5f07936e`, ARM64 minified APK at
`build/outputs/apk/release/CleverKeys-v2.0.0-arm64-v8a.apk`; SHA-256
`00e80393a4d130bc68cf47832ca0e106f9f7c11ac44106e62d9d8d7ce12d22f9`.
Kotlin compilation and full suites: 2,737 pure / 933 mock. Minified build (5m23s),
lint-vital, signature, alignment and ZIP CRC pass. Previous Seeker artifact was verified `97d6c25f`
(SHA `798137106bd92b39c15280119c511d123f9a39f321b953b0efd1e2eb4cafdd23`);
Compose both orders/cancel/navigation passed there. The new APK is now installed
with matching SHA; actual custom apostrophe and inline emoji routing pass.

**October 5 cleanup state:** popover and multi-language restored OFF; import ZIP
removed. Both the temporary minimize-bar and apostrophe mappings were removed,
preserving the original two mappings. The new APK fixes actual custom apostrophe
attachment (`As'`) and routes it into emoji search. A fresh t/South `minimize_fab`
mapping then verified portrait/landscape minimize/expand; landscape scrolling from
the transparent strip moves launcher content while the FAB remains. Original rotation
restored (accelerometer=0, user_rotation=0); temporary app locale restored to [].
The Hebrew app locale did not create an RTL IME, so RTL placement remains unverified.
Final reconnection cleanup completed: deleted ONLY t/South minimize_fab, confirmed
two original user mappings remain and launcher test field empty. Original IME,
rotation 0/0 and app locales [] verified. Removed scratch UI XML; HOME and expanded
Quick Settings restored (NotificationShade focus). No reboot/data clear, clipboard
overwrite or terminal command execution. Actual external-terminal editing remains pending.

#### Remaining hands-on release checks, ordered by consequence

1. **Typing and language:** ordinary English words plus `wet/we/tree/get/yet/pet/git`,
   contractions, possessives, custom/cased words, custom-word removal, non-QWERTY swipe,
   language switching and first swipe after a cold IME process start.
2. **Gestures/window:** #145 with prediction and swipe typing off, after a theme change;
   custom override and popover assignment; both minimize styles, app taps/scrolls outside
   the FAB, hide/reopen, numeric/PIN scaling on/off and a compact custom bottom-row-free board.
3. **Themes/settings:** all nine theme fields round-trip/live-update, caps-lock coloring,
   active-theme deletion fallback, built-ins/Monet day-night, new switches persist,
   Sparkle trail, PIN layout, settings search and search-state persistence.
4. **Clipboard/GIF/emoji:** media row deletion, private entry/OS clipboard isolation,
   pane theme updates, GIF search/pagination/content insertion/reimport, emoji search,
   categories/insertion/recent entries and unsupported-glyph behavior.
5. **Data/privacy:** playground records only playground swipes; export/share; settings
   merge collision winner; encrypted round-trip; pack import/licence display. Reset preserving
   dictionary/layout/learning data requires a backed-up, separately authorized destructive pass.
6. **Real app/system integration:** Chrome and chat/text editors, Termux editing, actual
   password-manager autofill-chip taps, TalkBack labels/activation, rotation and navigation
   insets. Fresh-install ONNX initialization and once-per-boot reminder need an isolated
   install/reboot pass; this device was not cleared or rebooted.

No group has a complete release-specific pass. Targeted Seeker checks above cover
parts of gestures/window, emoji and dictionary refusal; the other cases remain pending.
Prior JVM/mock and historical device results do not replace the remaining soak.

### Ordered issue queue

Ordered by value ÷ cost. Sizes: S ≤ 1 day, M ≈ 2-5 days, L > 1 week.

0. **Release held for issue work and maintainer testing (2026-10-02).** The maintainer
   explicitly wants GitHub features/bugs addressed and to test personally before 2.0.
   The checklist is reopened in their working tree; do not infer release readiness from
   the older HANDOFF sign-off. Tagging, pushing, publishing and issue closures are deferred.
1. **#90 — FIXED locally (2026-10-02).** Explicit `numpad_height` metadata replaces
   the bottom-row proxy. Compact custom layouts retain authored sizing, numeric/PIN still
   scale (`ba5bbee6`). Fail-first geometry coverage and full JVM suites pass. Verified
   minified APK installed on Seeker on October 3; maintainer functional check remains pending.
2. **#145 regression pin — DONE (2026-10-02).** The mock test executes actual graph/view
   wiring with both prediction settings off, a null predictor, and two fresh views; the service
   handle is assigned without model loading. Maintainer cold-start gesture testing remains.
3. **#181(2) emoji tofu — implemented locally (2026-10-03).** Glyph filtering and full
   host suites pass. Sampled grids/search pass on Seeker; force an unsupported glyph and reproduce
   #181(1) Monet live-update on the remaining device pass.
4. **#175(2): size-filtered batch deletion implemented locally (2026-10-05).** Mandatory
   confirmation covers every matching page in the current tab; tests and device status
   are recorded in `memory/todo.md`. **#168 remains open**: a separate `clear_clipboard`
   command is not implemented. Swipe-to-delete rows remain open.
5. **#188 compose key — fixed locally (`97d6c25f`).** Device repro and fail-first
   pointer coverage confirmed the navigation tap cancels pending state. Fresh minified
   APK was installed with matching hash; both sequence orders, cancel and arrows pass.
6. **#186 + #61: bind language to layout.** M. One feature closes two issues: autocorrect and
   predictions follow the active layout. A reference implementation exists in the #186
   reporter's fork commit.
7. **#184 big-pack guard — implemented locally.** Verify a refused oversized update keeps
   the existing pack and typing operational on Seeker. Full format validation and broader
   bilingual heap measurements remain separate follow-up work.
8. **#147 + #87: "short swipes only" mode when swipe typing is off.** S-M. Five users across
   three issues (#147, #87, #83). Resolve any displacement to the nearest direction's subkey.
9. **#167(2) password-manager inline chip unresponsive.** M. A security-relevant regression whose
   window is narrowed to the v1.3.0 autofill commits (`83c547df`/`b990f7f7`/`f13d5174`). Needs
   Bitwarden or KeePassDX on an API 30+ test phone.
10. **Contributor PR queue: #189, #182, #183; close #164.** S each to review (#183 is L to
    validate). #189 is small and tested. #182 unblocks #88 Arabic as a langpack. #183 is a
    contributor-built #177 Pinyin. Unanswered contributor offers (#120 sounds, #52 MessagEase)
    also need a yes or no.
11. **#187 macron-below / ring-below dead keys.** S. Two compose tables + checkboxes.
12. **Small quality-of-life asks.** S each: #140 (space accepts the highlighted completion;
    reopen if wanted), #84 (smart-punctuation interval), #139's "prefer Enter over action key"
    option.

Deferred (L / maintainer call): #165 Korean automaton, #115 floating/split keyboard (prior art
PR #100), #69 two-finger swipe, #163 background image, #121 custom fonts, #156 at-rest
encryption (design ready), #175(3)/#80(3) customizable toolbar.

## Maintenance rule

When a fix lands, update the Status/Evidence columns and cite the commit. When the maintainer
closes an issue on GitHub, flip it to CLOSED with the close date. New issues get a row on triage.
Re-derive "shipped in vX" with `git tag --contains <hash>`, never from memory. #145 shows how an
unverified attribution can survive two snapshots.

### October 5 default-engine and apostrophe follow-through

Seeker now runs the verified `97d6c25f` minified APK. Both Compose orders produce é,
intermediate prefixes commit no raw text, cancellation resumes normal typing, and
arrow-left followed by e produces Caet. The temporary bar mapping is removed and the
two user mappings preserved; final focus/test-text restoration follows the next pass.

The shipped-model diagnostic reproduces ad→as (ad rank 2 in six canonical sampling
conditions; Seeker commits As). The lexicon includes ad at frequency 199. This is a
second default-CTC recognition defect, not a missing dictionary word or gesture-route
failure. There are 19 distinct synthetic words in `build/short-word-probe.log`; repeated
sampling variants are not independent human evidence. Wet additionally competes with
wt, which shares its endpoint, so endpoint-only penalties cannot resolve that ordering.
TODO: collect held-out real short-word traces, inspect emissions and validate a general
model/scoring correction with protected we/as and broader vocabulary. Manual engine
switching is not an acceptable solution for ordinary words.

Astra found a separate apostrophe defect: custom single-character apostrophe TEXT
mappings bypass the ordinary text route. The fix routes ASCII/curly apostrophes through
KeyEventHandler, preserving owned auto-space attachment, inline search and bookkeeping.
Focused fail-first evidence: 3 failures → 15/15 pass. Literal multi-character macros are
unchanged; explicit possessive suffix transactions and suffix-only undo remain TODO.
See the reconciled roadmap §2.1 for plural/possessive/contraction distinctions.

Shared terminal detection is implemented in `5f07936e`; five terminal package
fixtures use Ctrl+W and terminal prefix replacement uses native backspaces.
Ordinary editor deletion remains covered. Combined suites pass 2,737 / 933; the new
APK is installed with matching hash; custom apostrophe/emoji routing pass on device.
Temporary FAB mapping cleanup is complete; external-terminal editing remains pending.

Astra's default-CTC proposal was screened before considering production changes.
Geometry candidate rescoring fixes synthetic ad but cannot separate collinear wet/wt;
an exploratory timing term gives synthetic success but regresses actual human input.
Frozen 100-trace / 92-word screen: baseline93 correct, geometry83 (1 gain/11 losses),
geometry+timing61 (1 gain/33 losses). Short-word subset20:17→14→11; longer80:76→69→50.
Zero baseline differences from shipped replay. No ad/wet/as/we/wt in this sample;
corpus is repeatedly inspected and not held-out. See `build/short-word-human-screen.log`.
Both heuristic arms rejected; no model/ranking change. TODO: real-data encoder
correction by general failure strata, separate writer/session development and held-out
splits, protected common words, wider vocabulary/layout regression and device latency.
