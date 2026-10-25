# GitHub drafts for maintainer approval (2026-10-25)

**Nothing in this file has been posted.** Every reply, review and close note below is a draft; the
maintainer approves each item individually (tick its `APPROVE? [ ]` line) before anyone posts it.
Source list: `docs/audit/gh-issue-resolution.md` (the DONE/FIXED close candidates, the two
NOT-REPRO/BY-DESIGN items, PRs #189/#183/#182 and PR #164). Each item was re-verified read-only
against HEAD `ac86bda5` (2026-10-24): fix commits confirmed as ancestors of HEAD with
`git merge-base --is-ancestor`, release state with `git tag --contains <hash> | rg '^v'` (latest
release tag **v1.5.0**; **v2.0.0 is not tagged**, so "will ship in v2.0.0" in a reply means
unreleased), pinning tests located at HEAD, behaviour re-read in source, and the full issue/PR
thread re-read (threads as downloaded 2026-10-20; no in-scope issue or PR was updated after
that date except PR #164, re-read live). Release assets (`langpacks`, `CleverKeys-GIF`) were
checked live with read-only `gh release view`.

Conventions in the drafts: maintainer's voice, first person; settings paths are quoted as the
user sees them, without the section emoji; "will ship in v2.0.0" marks an unreleased fix; "I'll
close this when v2.0.0 is out" leaves the close-now / close-at-tag choice to the maintainer, who
can post the comment now and close later, or edit the last line and close at once.

## Summary

Counts by proposed action (48 items): **close as completed now** 14 · **comment now, close at the
v2.0.0 tag** 22 · **comment only, keep open** 6 · **close as not reproducible** 1 · **close as by
design** 1 · **PR: request changes** 3 (one of them a rebase-only step before merge) · **PR: close as
superseded** 1.

"Now" means the fix is in v1.5.0 (or is documentation already on main); "at the tag" means the fix is
on main but unreleased, so the comment can go out now and the close follows the v2.0.0 tag.

| # | Title (short) | Proposed action | Release state | Key evidence at HEAD | Tracker row | Needs a decision |
|---|---|---|---|---|---|---|
| 190 | Non-accelerated backspace swipe | close at tag | unreleased `a60f8692` `c5c598b5` | `PointersFollowFingerSelectionTest`; Saga PASS 10-20 | stale: "no device check yet" | — |
| 188 | Compose key does not work | close at tag | unreleased `97d6c25f` | fail-first pointer test; Seeker PASS 10-05 | ok | — |
| 186 | Autocorrect only for primary language | close at tag (with #61) | unreleased `f85c8dcd` + fa pack `479a7277`… | `LayoutLanguageBindingConfigTest`; Saga + Pixel PASS; `langpack-fa.zip` on the release 10-25 | stale: "Persian half untested" | — |
| 184 | 1M-word dictionary crashes | **comment only, keep open** | unreleased `af742286` `17db49da` | 100k / 16 MiB refusal; Seeker refusal PASS | **wrong bucket**: summary table lists it as a close candidate; the row itself says scope call | **scope**: by-design cap or support big packs |
| 179 | Slow startup with custom pack | close at tag (ask for a timing) | unreleased `70284a2c` | source-scan drift test only; no latency measured | ok | — |
| 171 | Custom mappings don't override | close at tag | unreleased `47969359` `c29a0d87` `835de98b` | mock + instrumented render test | omits `835de98b` | — |
| 169 | Layout-switch keys unremovable | close at tag | unreleased `e7dda022` | `ExtraKeysSwitchRemovalTest` (fail-first) | ok | — |
| 162 | "gorgeous" never recognised | **close as not reproducible** at tag; post now | neural engine deleted; `afdd68a4` replay | synthetic 27/27 rank 1; corrects the wrong on-thread frequency advice | ok | post the correction before the tag? |
| 161 | Portrait slider changes landscape | close at tag | unreleased `a9c22871` | source-scan drift test; no device check | ok | phone check first? |
| 160 | Language switch keeps first layout | close at tag | unreleased `925f0016` | `SubtypeLayoutFollowTest` (mock) | ok | — |
| 154 | Vibration delay / no system option | close at tag | latency v1.5.0; switch unreleased `2a6c43fc` | `HapticsBehaviorDriftTest`; Saga PASS 10-20 | stale: "not checked on a device" | — |
| 152 | Full GIF pack unusably slow | **comment only, keep open** | unreleased `56c47fc6` | only a 500-GIF synthetic test; no 130k timing | ok ("close after soak") | who runs the 130k retest |
| 151 | Suggestion tap leaves partial word | close at tag (answers mooreye) | partial-word v1.5.0 `736e4eee`; residuals unreleased `9c8f5827` `80207612` `fd3eec40` | device A/B recorded; Termux PASS | **wrong release**: row says all unreleased; omits Termux commits | — |
| 149 | GIF pack inserts dead link | **comment only, keep open** | code unreleased `ecd1abc8` `56597c1b`; packs NOT rebuilt | all 22 GIF release assets predate `gid:` (02/03-2026); thumbnail packs would insert nothing on v2.0.0 | **wrong**: listed close-ready; it is PARTIAL until the repack | **rebuild + re-upload the thumbnail packs** |
| 148 | Clipboard opens without keyboard body | close at tag | unreleased `cb7cebd4` `56597c1b` `aafec4da` | mock + drift tests; Pixel visual PASS | ok | — |
| 146 | Can't install Dutch pack | close now | asset live since 09-27; in-app link unreleased `7e1034be` | `langpack-nl.zip` verified live | row implies the link is released | — |
| 145 | Per-key gestures dead after reboot | close at tag (comment now) | unreleased `8d3ac943` `fddb65d5` (test `a53a24a3`) | mock test runs the real wiring; cold-start phone check owed | ok | — |
| 141 | Timestamp keys unassignable | close now | v1.5.0 `f3b02b3c` | `CustomShortSwipeExecutorTest`; racuna redirected to #147 | ok | — |
| 134 | Customization keyboard disappears | close now | button v1.5.0 `6ff48751`; residual unreleased `a9b000cf` | source-scan test + instrumented button test | **wrong release**: row says all unreleased | — |
| 130 | Clipboard ignores custom theme | close at tag | v1.5.0 `965b71e0` `b8f17ae5`; unreleased `a7940256` `7144e2c7` | pure + mock tests; checklist line unchecked | omits `b8f17ae5` | — |
| 99 | build_langpack.py docs unclear | close now | docs/scripts on main `b5772e87` | CSV parse re-run locally | row cites no commit | — |
| 96 | Dictionary search resets | close at tag (steps already fixed in v1.3.0) | v1.3.0 `e46ed8c1`; unreleased `6c97756b` | pure + source-scan tests | ok | — |
| 90 | Custom size for keyboard/row | close at tag | unreleased `ba5bbee6` | `NumpadKeySizeTest` 12/12; device check owed | ok | scope: absolute XML height not built |
| 83 | keys-per-direction on medium swipes | **close as by design** now | boundary unchanged; Short Swipes Only unreleased | `Pointers.kt` gates; `PointersShortSwipesOnlyTest` | line ref stale (`Config.kt:992`) | — |
| 77 | Can't disable Greek/Math toggle | close at tag | unreleased `e2b64d32` `9bb88bfe` | `NumericPaneGreekmathRemovalTest` | omits `9bb88bfe` | **default OFF removes the key for everyone who never ticked it** |
| 75 | Swiss French swipe | close at tag | unreleased `90b1efe2` `a7d03bc8` `7747fea5` | synthetic replay test; no device check | ok | — |
| 71 | Clipboard open stalls device | close now | v1.5.0 `8cf40104` `20ef3006` `89d318fc` | constant pins; instrumented tests stale (100-entry cap gone) | row cited no commits | — |
| 67 | build_all_languages.py script error | close now | v1.2.6 `886f0b11` | helper check; reporter ran a lone file | **misattributed** to `.resolve()` | — |
| 35 | Overly dark dark mode | close now | v1.5.0 `90c929d1` `cc6a0b6b` `42b09104` | DayNight styles; visual only | omits `42b09104` | — |
| 187 | Macron-below / ring-below accents | close at tag | unreleased `30f205d9` | `DeadKeyMarksBelowTest`; instrumented passed on emulator.wtf 10-20; Saga PASS | stale: "not run on a device"; summary table still lists it open | — |
| 168 | Clear-clipboard key | close at tag | unreleased `ef12ad20` | mock + pure tests; disposable-clip phone check owed | row cites no commit | — |
| 147 | Remove long swipe gestures | close at tag | unreleased `c93be97b` `918dc322` | `PointersShortSwipesOnlyTest`; Saga PASS 10-20 | queue item says device check owed (done) | — |
| 135 | `clear` action | close now | v1.5.0 `c4778d9d` | parity + blind-field tests | line refs drifted | — |
| 111 | README: Urik column | close now | main (in v1.5.0) `bedd193a` | `README.md:59-100` | ok | — |
| 94 | Copy version info on long-press | close now | v1.5.0 `7558313b` | `VersionCopyPayloadTest` | ok | — |
| 93 | Hex colour input | close now | v1.5.0 `1015087e` | instrumented Compose test | ok | — |
| 87 | Long swipe → short swipe when swipe typing off | close at tag | unreleased `c93be97b` `918dc322` | as #147 (opt-in, not automatic) | ok | — |
| 70 | Programmatic launch via Intent | **comment only, keep open** | intents v1.2.6 `90c929d1`; `json_base64` v1.4.0; 2.0 contract `754e0889` | headless export encrypted, plaintext import refused; decrypt script only | **wrong release** (says v1.4.0); skips the open design question | **encrypt script / plaintext opt-in / accept** |
| 68 | Greek dictionary | close now (point crash to #184) | pack v1.2.6 `a497e4eb`; CTC routing unreleased | pack is wordfreq-based; no Greek accuracy figure | **wrong**: "el CTC-routed" attributed to v1.5.0 | — |
| 61 | Switch among 3+ languages | close at tag | unreleased `f85c8dcd` | two pure tests; Saga two-layout PASS; three-layout cycle JVM only | ok | — |
| 58 | Scale number keyboard | close now (corrects on-thread hashes + path) | v1.2.6 `bd8cbafe` `0f711855`; switch unreleased `68bafddc` | `NumpadKeySizeTest` | ok (hash note already there) | — |
| 49 | Turkish | close now | v1.2.6 `310fc83a` | pack + layout + 2,178/2,188 strings; geometric swipe, unmeasured | ok | — |
| 31 | Next word / Russian completion | **comment only, keep open** | ru pack v1.5.0 `32f93f1d`; next-word unreleased `997d8f78` `a90a6489` | no Russian-specific test or device check | **premature close**; next-word never released | — |
| 26 | Docs: clarify language support | **comment only, keep open** | docs on main `99f5b70d` `df6e4a08` | `README.md:63` still says FUTO swipe is "English only" (FUTO docs disagree) | **wrong**: listed DONE | fix the one README cell |
| PR 189 | Short swipes on centre-less keys | **request changes** (rebase + run tests), then merge | not on main; CONFLICTING | fix still needed at `Pointers.kt:372`; tests never confirmed run | "tested" overstated | self-rebase if the author is slow? |
| PR 183 | Pinyin composing IME | **request changes** (10 items) | not on main; CONFLICTING | typed failures, cap mismatch, `stageAndSwap`, blind fields, IPC-result fallback, hook placement, licence plumbing, test pack | add: CONFLICTING; `phrases.bin` silently dropped today | `phrases.bin` cap; accept a small CC-CEDICT test pack |
| PR 182 | Arabic 50k pack | **request changes** (provenance) | not on main; MERGEABLE | valid CKDT V2, 50,000 words, no licence fields / NOTICE / unigrams; 694 Latin tokens | cites `638d492b`; code/test commit is `19c132f7` | accept Latin tokens once licensed, or require an `ar` wordlist row |
| PR 164 | Landscape height fix | **close as superseded** | superseded by `a9c22871` (unreleased) | calls deleted `NeuralLayoutBridge` (`64f401d2`) | ok | — |

### Items where the tracker (`docs/audit/gh-issue-resolution.md`) was wrong

Status or action wrong (corrected in the tracker in this commit):

- **#149** — listed as a close candidate. The code fix is real, but every published `CleverKeys-GIF`
  asset predates the `gid:` token (2026-02/03), and the thumbnail packs carry no full media, so on
  v2.0.0 a tap from the packs the reporter downloads inserts nothing ("GIF media unavailable"). PARTIAL
  until the thumbnail packs are rebuilt and re-uploaded.
- **#26** — listed DONE. `README.md:63` still marks FUTO's multilingual swipe "English only"; FUTO's
  documentation says it swipes across enabled languages, which was the reporter's point. PARTIAL
  until that cell is fixed.
- **#184** — the summary table counted it among the DONE/FIXED close candidates while its own row and
  the 2026-10-20 correction call it a maintainer scope decision (packs over 100,000 words are refused,
  not supported).
- **#31** — "close as completed" rests on no Russian-specific test or device check; the reporter must
  confirm. Next-word prediction did not exist in v1.5.0 at all (`997d8f78`, 2026-08-06).
- **#70** — the intents shipped in v1.2.6 (`90c929d1`), not v1.4.0 (that is the `json_base64` fix);
  the row's "comment; close" skips the open question that the v2.0.0 contract (headless export always
  encrypted, plaintext headless import refused, only a decrypt script) removes the reporter's
  export → edit → import loop.
- **#68** — "el CTC-routed" was attributed to v1.5.0; Greek CTC routing is unreleased (v1.5.0 swipes
  Greek on the geometric engine). The pack itself dates from v1.2.6.
- **#134**, **#151** — rows said "FIXED (unreleased)" although the reporter's ask is in v1.5.0
  (`6ff48751`; `736e4eee`); only residuals are unreleased.
- **#67** — fix credited to `SCRIPT_DIR = Path(__file__).parent.resolve()`; helper paths were already
  script-relative. The real change is `886f0b11`'s up-front missing-helper check, and the reporter had
  run a lone copy of the script.
- **#146** — "linked from the Languages screen" reads as released; the link (`7e1034be`) is v2.0.0.
- **#187** — the summary table still listed it under "Still open" and the queue as open work; the row
  said the instrumented test had not run on a device (it passed in the 2026-10-20 emulator.wtf run and
  the Saga check is recorded).
- **PR #189** — "small, tested": the PR's own checklist does not claim a passing run, and its command
  names `testDebugUnitTest`, which is disabled on the ARM64 build box.
- **PR #182 / #88** — the licensing remediation is `19c132f7` (builder flags, repack, enforcing test);
  `638d492b` is the docs commit.

Stale but not wrong (also corrected): device-check status for #154, #190, #147/#87 (all passed on the
Saga 2026-10-20); #186 Persian half (built, Pixel-checked, `langpack-fa.zip` on the release
2026-10-25); missing commits on #71, #99, #130, #168, #171; drifted line refs on #83 and #135.

### Maintainer decisions needed before posting

1. **Close now vs at the tag** for the 22 "at the tag" items: each draft ends "I'll close this when
   v2.0.0 is out" so the comment can go out now; edit that line to close at once.
2. **#149** — rebuild and re-upload the 15 thumbnail GIF packs with `gid:` tokens (the pipeline already
   writes them) before closing; the draft promises this.
3. **#184** — keep the 100,000-word / 16 MiB cap as the answer (close as by design at the tag) or treat
   large packs as a feature request. The draft asks the reporter whether 100k is enough.
4. **#70** — ship `scripts/ckenc-encrypt.py`, add a plaintext-headless-import opt-in, or accept the
   limitation (the draft assumes the last; adjust its final paragraph otherwise).
5. **#77** — the Greek/Math checkbox defaults OFF, so v2.0.0 removes the key from the ?123 pane for
   every install that never ticked it. Confirm that is intended.
6. **#90** — close the bug-only reading, or keep the original "absolute height in XML" ask open (the
   draft offers to keep it open if the reporter still wants it).
7. **#162** — post the frequency-advice correction before the tag (recommended) or with the close.
8. **#26** — fix the one README cell (`README.md:63`, FUTO "English only" → supported) before closing.
9. **#31** — wait for the reporter's confirmation of Russian completion, or close at the tag anyway.
10. **PR #189** — merge with a maintainer rebase if the author does not respond.
11. **PR #183** — the `phrases.bin` size cap (16 MiB suggested) and whether a small CC-CEDICT-derived
    pack is acceptable for the device check.
12. **PR #182** — accept the pack with its 694 Latin tokens once licence metadata is added, or require
    a script-filtered rebuild (`build_wordlist.py` `ar` row) first.
13. Phone checks still owed before the tag, independent of these replies: #145 cold start, #161
    rotation, #168 disposable clip, #90 numpad cases, #184 oversized update of an installed pack, #152
    130k-pack timing.

### Not in the source list

- **#192** (filed 2026-10-20, "Replacing a swiped word deletes the space before it when Auto space
  after suggestion is off") has no tracker row. It is still present at HEAD:
  `SuggestionHandler.kt:1670` deletes `word.length + 1` on every swiped-word replacement, so with
  auto-space off the space before the word is removed, exactly as the reporter diagnosed. The
  maintainer's 2026-10-25 RustDesk soak (`memory/todo.md` Open now 6) reports the same symptom. Needs a
  row (OPEN-ROOT-CAUSED) and a fix; no draft reply here.

## Bugs — close candidates and the two NOT-REPRO / BY-DESIGN items

## #190 — [Feature]: Non-accelerated backspace swipe

- **Proposed action:** close as completed at the v2.0.0 tag
- **Released state:** unreleased, will ship in v2.0.0 (`a60f8692`, `c5c598b5`)
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `a60f8692` 2026-10-20 wip(#190): follow-finger backspace selection — ancestor of HEAD: yes; tags: none
  - commits: `c5c598b5` 2026-10-20 feat(gestures): follow-finger backspace swipe selection (#190) — ancestor: yes; tags: none
  - tests: `src/test/kotlin/tribixbite/cleverkeys/PointersFollowFingerSelectionTest.kt` (mock, 8 cases: proportional growth, shrink, crossing the start, vertical lines, default unchanged, identical release) — exists at HEAD: yes
  - source: `Pointers.kt:1419-1421` — target selection = `followFingerSteps(ptr.lastX - ptr.keyCenterX, snap.selection_delete_step_px)` and `followFingerLines(dy, …)`; helpers `:2414-2430`
  - source: `Config.kt:159-162` `SELECTION_DELETE_MODE = "speed"` (default unchanged), `SELECTION_DELETE_MODE_FOLLOW = "follow"`, `SELECTION_DELETE_STEP_DP = 12`; `:568` range `4..40`; `:1347-1349` read from `selection_delete_mode` / `selection_delete_step_dp`; `ui/settings/sections/GestureTuningSection.kt:198-228` the selector and the Step Size slider
  - strings: `res/values/strings.xml:476` "👆 Gesture Tuning", `:1174` "Selection-Delete Mode", `:707` "Selection Movement", `:709` "Speed by distance", `:710` "Follow finger", `:711` "Step Size", `:712` desc "Finger travel needed to select one more character in Follow finger mode. Smaller = faster, larger = more precise."
  - device check: Saga 2026-10-20 15:45 PASS — Follow finger, backspace short-swipe + hold, 128 px drag selected 4 characters, moving back 64 px shrank it to 2, release deleted exactly those 2; mode restored (`memory/todo.md:213-216`)
- **Thread notes:** reporter mooreye (feature): backspace swipe selection "starts slow and then accelerates", asks to "remove acceleration or make it configurable" like HeliBoard/FlorisBoard. No comments. The reply gives the setting, keeps the old mode as default, and explains the step.
- **Audit-doc correction:** `docs/audit/gh-issue-resolution.md:105` says "No device check yet" — stale; the Saga PASS of 2026-10-20 15:45 is at `memory/todo.md:213-216` and in the tracker's own header paragraph. (The 2026-10-20 audit's NOT-DONE row predates the same-day fix.)
- **Draft reply:**

> Added. The joystick behaviour you describe (selection speed grows with distance from the key) is now one of two modes, and the other is the HeliBoard/FlorisBoard style you asked for.
>
> Settings → Gesture Tuning → Selection-Delete Mode → "Selection Movement": "Speed by distance" (the previous behaviour, still the default) or "Follow finger". In Follow finger the selection is simply how far your finger has travelled divided by "Step Size", recomputed on every move, so moving back shrinks the selection and there is no acceleration. Step Size runs from 4 to 40 dp, default 12; smaller is faster, larger more precise. Moving up or down past the dead zone still selects whole lines, one per key height. Releasing deletes the selection in both modes (`a60f8692`, `c5c598b5`).
>
> This is not in v1.5.0 and will ship in v2.0.0. To check after updating: switch to Follow finger, short-swipe on backspace and hold, drag further and watch the selection grow evenly, drag back and watch it shrink, release to delete.
>
> I'll close this when v2.0.0 is out; reopen if the movement still feels accelerated on that build.

- **APPROVE? [ ]**


---

## #188 — [Bug]: Compose key doesn't work

- **Proposed action:** close as completed at the v2.0.0 tag
- **Released state:** unreleased, will ship in v2.0.0 (`97d6c25f`; its message does not cite #188)
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `97d6c25f` 2026-10-05 fix(input): preserve compose latch on navigation-key taps — ancestor of HEAD: yes; tags: none
  - tests: `src/test/kotlin/tribixbite/cleverkeys/PointersShortSwipeCustomOverrideTest.kt#deferredComposeTap_latchesInsteadOfEmittingAndClearing` (`:191`, mock; fail-first per `memory/todo.md:897-901`) — exists at HEAD: yes
  - source: `Pointers.kt:296-303` — the deferred navigation-subkey TAP branch now only emits key-down and clears the deferred flag; the pending compose prefix goes through the common latch lifecycle instead of key-up + clear (the old path cancelled the compose state immediately)
  - source: `src/main/compose/compose/en_US_UTF_8_Compose.pre:188-191` — `<c> <equal>` and `<equal> <c>` (and capital C) → "€"
  - device check: Seeker 2026-10-05 — reproduced first (Compose lit, next letter committed raw), then with the fix installed (SHA `798137106b…`) both compose orders, cancel and arrow navigation passed (`memory/todo.md:897-909`)
- **Thread notes:** reporter (v1.5.0, GrapheneOS 17) points at discussion #159; steps: Compose, `c`, `=` expecting €. No comments beyond the bot. The reply covers the € check in both orders plus cancel and arrows.
- **Audit-doc correction:** none (`docs/audit/gh-issue-resolution.md:106` is accurate). Worth remembering only that `97d6c25f` does not mention #188 in its message.
- **Draft reply:**

> Thanks for filing this from the discussion. I reproduced it: Compose lit up, then the next letter was committed as itself and the pending compose state was dropped.
>
> The cause was that Compose shares its key with the arrow sub-keys, so a tap on it took a deferred "navigation key" path that emitted the key-up and cleared modifiers immediately, cancelling the compose state it had just set. `97d6c25f` routes that tap through the same latch handling every other modifier uses; arrow flicks keep their own path.
>
> This is not in v1.5.0 and will ship in v2.0.0. To check after updating: tap Compose, then `c`, then `=` and you should get €; `=` then `c` works too, and tapping Compose again cancels it. Arrow flicks on the same key should still move the cursor.
>
> I'll close this when v2.0.0 is out; reopen if a sequence still commits the letter on that build.

- **APPROVE? [ ]**


---

## #186 — [Bug]: Autocorrect doesn't work on secondary language

- **Proposed action:** close as completed at the v2.0.0 tag (together with #61)
- **Released state:** unreleased, will ship in v2.0.0 (`f85c8dcd`; Persian additions `9aac669d`, `479a7277`, `2beac893`, `e8f1d3ae`). The `langpack-fa.zip` pack IS on the GitHub `langpacks` release (uploaded 2026-10-25T02:36Z, 545,291 bytes = the repo copy from `2beac893`), so the lead's "not yet published" and `memory/todo.md` Open now 2's "publishing the pack as a release asset needs the maintainer" are both out of date as of today.
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `f85c8dcd` 2026-10-07 feat(language): bind a language to each layout (#186, #61) — ancestor of HEAD: yes; tags: none
  - commits: `9aac669d` 2026-10-22 feat(layouts): half-space key right of the space bar on Persian PC — ancestor: yes; tags: none
  - commits: `479a7277` 2026-10-22 feat(dictionaries): Persian (fa) language pack from a Leipzig + wordfreq blend; `2beac893` 2026-10-23 fix(dictionaries): drop glued-clitic artefacts from the fa pack; `e8f1d3ae` 2026-10-22 feat(i18n): Persian half-space is a word character between Arabic letters — all ancestors: yes; tags: none
  - commits: `1d2f1d58` 2026-10-08 fix(autocorrect): neighbour-key typos on Hebrew and other non-Latin boards are corrected — ancestor: yes; tags: none (makes autocorrect adjacency work for non-QWERTY letters, which fa needs)
  - tests: `src/test/kotlin/tribixbite/cleverkeys/LayoutLanguageBindingConfigTest.kt#switchingToALayoutBoundToFaMakesFaThePredictionAndAutocorrectLanguage` (`:76`, mock; 9 cases) — exists at HEAD: yes; `src/test/kotlin/tribixbite/cleverkeys/ActiveLanguageSyncTest.kt` (pure JVM, 6 cases) — exists: yes
  - source: `Config.kt:1562-1578` `set_current_layout` → `recomputeActiveLanguages()` (`:1523-1536`, `LayoutLanguageBinding.resolve`); `ActiveLanguageSync.kt:64-69` `apply` reloads the primary (autocorrect) dictionary when it changed; `KeyboardData.kt:569` parses the XML `language` attribute
  - strings: `res/values/strings.xml:162` "📝 Input Behavior", `:1139` "Manage Keyboard Layouts", `:1506` chip "Language: %1$s", `:1500` dialog "Layout language", `:1501` "Follow Multi-Language settings", `:1502` "%1$s (layout default)", `:1503` dialog text (bound = only language for predictions, autocorrect and swipe; Follow keeps primary + secondary), `:1504` not-installed warning
  - device check: Latin half — Saga 2026-10-07, layout switch → German-bound layout shows the German bar, list restored after, PASS (`memory/todo.md:651-654`). Persian half — Pixel 8 Pro, release `c87e2bfb`, fa pack import and in-place update OK, half-space words stay joined, completions offered (`memory/todo.md` Open now 2, 2026-10-23). Still open there: native-speaker review of the pack, Persian swipe quality unvalidated.
  - working tree note: `activities/LayoutManagerActivity.kt` and `src/main/layouts/arab_pc_ir.xml` carry uncommitted edits from another session; everything above was read from HEAD.
- **Thread notes:** reporter mostafaqanbaryan (dev + v1.5.0, Android 15): two custom layouts (`script: latin` / `script: persian`), fa pack imported, primary EN / secondary FA; switching layouts does not switch primary/secondary so autocorrect only works for the primary. Asks: "Is there anyway to switch the layout and the autocorrect simultaneously?" Follow-up: links his own fork commit adding a `language` attribute to layouts. The reply answers the question, explains the by-design unbound behaviour, acknowledges the fork, and points at the Persian pack and half-space work.
- **Audit-doc correction:** `docs/audit/gh-issue-resolution.md:107` status says "Persian-pack half untested" — stale: the Persian half was built 2026-10-22/23 and device-checked on the Pixel (`memory/todo.md` Open now 2), and `langpack-fa.zip` is on the `langpacks` release since 2026-10-25.
- **Draft reply:**

> Thanks, and thanks for the fork commit; a `language` attribute on the layout is essentially what shipped.
>
> You diagnosed it correctly: autocorrect only ever used the primary language, and layouts carried no language, so switching to the Persian layout left Persian as the secondary. Since `f85c8dcd` each layout can be bound to a language. While a bound layout is shown, its language is the only active one for predictions, autocorrect and swipe typing, so switching layouts switches all of them together.
>
> Two ways to bind: in Settings → Input Behavior → Manage Keyboard Layouts, each layout has a "Language: …" chip; tap it and pick the language. Or add `language="fa"` to the `<keyboard>` element of a custom layout XML; the Layout Manager then shows "Persian (layout default)" for it.
>
> One point that is by design: a layout left on "Follow Multi-Language settings" keeps the old behaviour, your primary and secondary languages with autocorrect on the primary. So for your setup, bind the Latin layout to English and the Persian one to Persian.
>
> For Persian specifically, a Persian language pack (`langpack-fa.zip`, marked experimental) is now on the langpacks release page, and v2.0.0 adds a half-space key next to the space bar on the Persian PC layout plus half-space handling inside words such as می‌شود.
>
> None of this is in v1.5.0; it will ship in v2.0.0. To check after updating: bind both layouts, type a misspelt Persian word on the Persian layout and confirm it is corrected, switch back and confirm English autocorrect returns. I'll close this when v2.0.0 is out; reopen if the second layout still isn't corrected on that build.

- **APPROVE? [ ]**


---

## #184 — [Bug]: Cannot handle big dictionaries

- **Proposed action:** comment only, keep open — MAINTAINER DECISION: (a) close as by design once v2.0.0 ships (the 100,000-word / 16 MiB cap is the answer; the reporter's 1M-word Greek pack is refused, not supported), or (b) keep open as a feature request for large packs (would need memory-mapped CKDT reading and an on-disk trie across `CkdtDictionaryReader`, `BinaryDictionaryLoader`, `CtcCkdtLexicon` and the geometric loader). The reply asks the reporter whether 100k is enough, which informs that decision. Also owed: the Seeker/Saga check of an oversized UPDATE that must keep the installed pack (`memory/todo.md` Open now 3).
- **Released state:** unreleased, will ship in v2.0.0 (`af742286`, `17db49da`). On v1.5.0 the pack still crashes.
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `af742286` 2026-10-03 fix(langpack): refuse oversized dictionaries before allocation (gh #184) — ancestor of HEAD: yes; tags: none
  - commits: `17db49da` 2026-10-06 fix: act on the 2026-10-07 review of the issue fixes — ancestor: yes; tags: none (Settings names the reason under an already-installed oversized pack; 21-locale strings)
  - tests: `src/test/kotlin/tribixbite/cleverkeys/langpack/LanguagePackImportTest.kt#anInstalledPackOverTheWordCapReportsWhyItIsNotLoaded` (`:1042`, mock; 51 cases in the class) — exists at HEAD: yes; `src/test/kotlin/tribixbite/cleverkeys/swipe/geometric/CkdtReaderTest.kt` (pure JVM, 12 cases) — exists: yes
  - source: `swipe/geometric/CkdtDictionaryReader.kt:42-43` `MAX_WORD_COUNT = 100_000`, `MAX_DICTIONARY_BYTES = 16 MiB`; `:56,:109` byte cap; `:127` `require(wordCount in 0..MAX_WORD_COUNT)` before any count-sized allocation
  - source: `langpack/LanguagePackManager.kt:502-503` — import reads the real CKDT header and returns `PackImportFailure.DictionaryTooLarge`; `:538` `installedDictionaryProblem` for a pack installed before the cap
  - strings: `res/values/strings.xml:2785` "This dictionary contains %1$d words; the limit is %2$d. Import a smaller pack."; the cap is documented at `docs/wiki/layouts/language-packs.md:41`
  - related facts: the bundled English dictionary has 98,140 words (the cap sits 1,860 above it); `langpack-el.zip` (Greek, 39,860 words per the wiki table `:66`) is on the GitHub `langpacks` release (asset list checked read-only 2026-10-25)
  - device check: Seeker refused a real-header million-word fixture, installed count stayed 0, UI responsive (tracker row 108, 2026-10-03/06). Oversized UPDATE of an installed pack: still owed (`memory/todo.md` Open now 3).
- **Thread notes:** reporter dim-geo (also the author of #68 "Greek dictionary" and of the linked greekdictionary repo) on 1.5.0/Android 12: importing the 1M-word `langpack-el.zip` v2 crashes the keyboard. mooreye commented "see also #179" (2026-10-08). The reply must be honest that the pack will be refused, not loaded, and offer the two workable paths.
- **Audit-doc correction:** `docs/audit/gh-issue-resolution.md:73` (summary table) still counts #184 among "DONE/FIXED but still open (close candidates)", while the row at `:108` and the 2026-10-20 correction paragraph (`:51`) correctly call it a maintainer scope decision. The row's GH-action column ("verify refusal … on Seeker before closure") is partly done: the refusal passed on the Seeker; only the oversized-update case remains.
- **Draft reply:**

> Thanks for the report, and for the pack. On v1.5.0 a million-word dictionary does crash the keyboard: each dictionary is read whole into memory by the readers that need it (tap predictions, swipe, autocorrect), with arrays sized from the word count in the pack header, so a pack that size exhausts the heap before anything can be shown.
>
> Since `af742286` there is an explicit limit instead of a crash. A pack may hold up to 100,000 dictionary words and 16 MiB of dictionary data; a larger one is refused at import with "This dictionary contains N words; the limit is 100000. Import a smaller pack." and the keyboard keeps working. If an oversized pack was installed before the limit existed, it is skipped at load and Settings names the reason under that pack (`17db49da`). These changes are not in v1.5.0 and will ship in v2.0.0.
>
> To be clear about where this leaves your pack: it will be refused, not loaded. Two options that work today: keep the top 100,000 entries of your frequency-sorted list and rebuild the pack (that is slightly more than the bundled English dictionary has), or import the Greek pack on the langpacks release page, which has about 40,000 words. The limit is documented on the language-packs wiki page.
>
> I'd like to know whether a 100,000-word Greek pack is good enough for you in practice, or whether you really need the full list. Raising the limit would mean reading dictionaries from disk instead of memory, which is a much larger change, and your answer helps decide whether it is worth doing.

- **APPROVE? [ ]**


---

## #179 — [Bug]: Very long time to startup with custom language pack

- **Proposed action:** close as completed at the v2.0.0 tag, asking the reporter for a startup-time retest (no on-device measurement exists; the pin is a source-scan test). If the maintainer prefers hard evidence first: measure IME create → first input view on a test phone with a 5k/10k-word imported pack and auto-detect on.
- **Released state:** unreleased, will ship in v2.0.0 (`70284a2c`; the neural-engine removal, ADR-011, is also unreleased)
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `70284a2c` 2026-09-03 perf(langpack): imported-pack dictionary loads leave the IME-create main thread (#179) — ancestor of HEAD: yes; tags: none
  - tests: `src/test/kotlin/tribixbite/cleverkeys/LangpackStartupOffMainDriftTest.kt` (pure JVM, source-scan drift test, 4 cases; its KDoc records the two surviving main-thread costs) — exists at HEAD: yes
  - source: `AsyncDictionaryLoader.kt:212-226` — the background loader probes the installed language pack BEFORE the bundled assets (previously a pack-only language always failed the async load and the fallback parsed the pack synchronously on the main thread on every IME create); `:312` `runOffMain` builds the secondary index off the main thread
  - source: the v1.5.0 synchronous neural init that made up most of the 4-10 s is deleted (ADR-011, `docs/plans/2026-08-18-neural-engine-removal.md`)
  - device check: not recorded; no startup-latency measurement with an imported pack exists
- **Thread notes:** reporter (mooreye, 1.5.0, Android 16): 5,000-word wordfreq pack + auto-detect → 4-10 s startup, sometimes a timeout crash, worst when switching Personal/Private space; 10k words was unusable. Explicit question: "Are there some more good practices regarding custom lang packs that I miss?" — the reply answers it (pack size was not the cause; up to 100,000 words supported). #184's reporter linked here on 2026-10-08.
- **Audit-doc correction:** none (`docs/audit/gh-issue-resolution.md:110`)
- **Draft reply:**

> Thanks for the detailed report, and sorry for the wait. The pack size was not the problem: 5,000 or 10,000 words is small, and packs up to 100,000 words are supported. Two things in 1.5.0 made startup slow in exactly your configuration.
>
> First, 1.5.0 still built its neural swipe engine synchronously while the keyboard was being created. That engine has since been removed entirely, which takes most of the 4-10 s with it.
>
> Second, the background dictionary loader only knew about the bundled dictionaries. For a language that exists only as an imported pack it always failed, and the fallback then parsed the whole pack on the main thread on every keyboard start; the secondary dictionary that auto-detect needs was loaded the same way. `70284a2c` makes the loader look at the installed pack first and moves both loads off the main thread, so the keyboard appears before the dictionaries finish and predictions fill in a moment later.
>
> Switching between Personal and Private space hurt twice because each space runs its own copy of the keyboard, so each one paid the full cold start.
>
> None of this is in v1.5.0; it will ship in v2.0.0. I don't have a startup measurement from a phone with an imported pack, so a retest would help: with your pack imported and auto-detect on, roughly how long from tapping a text field to the keyboard appearing, and does switching spaces still stall? I'll close this when v2.0.0 is out unless that retest says otherwise.

- **APPROVE? [ ]**


---

## #171 — [Bug]: Customize per-key mappings (swipe directions) are not overwriting defaults

- **Proposed action:** close as completed at the v2.0.0 tag. Note for the maintainer: open PR #189 edits `Pointers.kt` and `PointersShortSwipeCustomOverrideTest.kt`, the same files, so it needs a rebase (another agent drafts the PR note).
- **Released state:** unreleased, will ship in v2.0.0 (`47969359`, `c29a0d87`; plus `835de98b`)
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `47969359` 2026-09-03 fix(gestures): custom short-swipe mappings shadow defaults at every fuzzed bin (#171) — ancestor of HEAD: yes; tags: none
  - commits: `c29a0d87` 2026-09-03 fix(render): custom short-swipe glyph replaces the default sublabel, not overlaid (#171) — ancestor: yes; tags: none
  - commits: `835de98b` 2026-10-08 fix(custom): type single-character mappings through the key pipeline — ancestor: yes; tags: none (not in the audit; extends the #171 tests, routes single-char custom TEXT through autocap/auto-space like a layout subkey)
  - tests: `src/test/kotlin/tribixbite/cleverkeys/PointersShortSwipeCustomOverrideTest.kt` (mock, 11 cases incl. the #188 one) — exists at HEAD: yes; `src/androidTest/kotlin/tribixbite/cleverkeys/Keyboard2ViewCustomMappingRenderTest.kt` (instrumented, 6 cases) — exists at HEAD: yes
  - source: `Pointers.kt:536-545` — custom mapping consulted before anything else; `:563` the ±1-bin fuzz consults the custom mapping at each bin; `:896-925` nearest-key resolution where a custom mapping shadows the default in the same slot
  - source: `Keyboard2View.kt:1820-1833` — default sublabel skipped where `SwipeDirection.coveredSubLabelMask` (`customization/SwipeDirection.kt:48`) marks the slot as covered
  - strings: `res/values/strings.xml:446` "📱 Activities", `:1417` "Customize Per-Key Actions" (tile), `:396` "Short Swipe Customization" (screen title)
  - device check: not recorded on a phone. The instrumented render test runs in the emulator.wtf suites (full suite 1,607/1,607 on 2026-10-20, `memory/todo.md:210`) — emulator, not a phone.
- **Thread notes:** reporter (1.5.0, Android 16): custom "!"/"?" on q NW/N drawn on top of the default "~"/"`", and "there is a chance that either character will print". Both halves answered (dispatch + render). No follow-ups.
- **Audit-doc correction:** `docs/audit/gh-issue-resolution.md:111` omits `835de98b` (2026-10-08), which changed how single-character custom mappings are typed and extended the #171 tests.
- **Draft reply:**

> Thanks for the clear screenshot. There were two bugs, one in dispatch and one in drawing, and both are fixed.
>
> Dispatch (`47969359`): a short swipe is matched with a little angular tolerance, one direction bin either side of the exact one. Your custom mapping was only consulted at the exact bin, so a slightly off-angle swipe fell through to the neighbouring default, which is why either character could come out. The custom mapping is now checked at every bin the tolerance visits and always wins over the default in that slot.
>
> Drawing (`c29a0d87`): the key face drew the default sublabel and then your mapping on top of it. A custom mapping now replaces the default glyph in its slot, so you see only "!" and "?" where "~" and "`" used to be.
>
> Separately, single-character custom mappings now go through the normal typing path, so auto-capitalisation and automatic spacing treat them like any other key (`835de98b`).
>
> None of this is in v1.5.0; it will ship in v2.0.0. To check after updating: Settings → Activities → Customize Per-Key Actions, map q north-west to "!", then swipe that direction a few times with varied angles. Every swipe should type "!" and the key should show only "!" in that corner.
>
> I'll close this when v2.0.0 is out; reopen if a default still bleeds through on that build.

- **APPROVE? [ ]**


---

## #169 — [Bug] Next n previous layout keys cannot be removed

- **Proposed action:** close as completed at the v2.0.0 tag (no phone check recorded; the mock test is fail-first)
- **Released state:** unreleased, will ship in v2.0.0 (`e7dda022`)
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `e7dda022` 2026-09-03 fix(extra-keys): next/previous-layout keys honor their checkboxes (#169) — ancestor of HEAD: yes; tags: none
  - tests: `src/test/kotlin/tribixbite/cleverkeys/prefs/ExtraKeysSwitchRemovalTest.kt` (mock, 6 cases) — exists at HEAD: yes
  - source: `res/xml/bottom_row.xml:8` — `key7="loc switch_forward" key8="loc switch_backward"`; no unprefixed `switch_forward`/`switch_backward` remains in `src/main/layouts` or `res/xml`
  - source: `prefs/ExtraKeysPreference.kt:220` — both default ON; `:536-540` `dropLayoutSwitchKeys` removes them when only one layout is enabled; `LayoutModifier.kt:82` calls it
  - strings: `res/values/strings.xml:162` "📝 Input Behavior", `:1140` "Configure Extra Keys" (also `:451` the Activities tile "Configure Extra Keys"), `:390` "Extra Keys Configuration" (screen title), `:1948` group "Layout Switching", `:2151` "Next Layout", `:2152` "Previous Layout"
  - device check: not recorded
- **Thread notes:** reporter (1.5.0, Android 15): unticking the two layout-switch shortcuts in Extra Keys does nothing, all other keys remove fine. No comments at all.
- **Audit-doc correction:** none (`docs/audit/gh-issue-resolution.md:112`). The 2026-10-20 audit's line refs `ExtraKeysPreference.kt:216/:528`, `LayoutModifier.kt:54` have drifted to `:220/:536`, `:82`.
- **Draft reply:**

> This was a real bug. The next/previous-layout keys on the space bar were written straight into the bottom row, so they ignored the Extra Keys checkboxes and could not be removed. In `e7dda022` they go through the same mechanism as every other extra key, so the checkboxes govern them.
>
> After updating to v2.0.0 (this is not in 1.5.0): Settings → Input Behavior → Configure Extra Keys, group "Layout Switching", untick "Next Layout" and "Previous Layout". The space-bar gestures are gone the next time the keyboard shows; ticking them again brings them back in the same place.
>
> Two notes so the defaults don't surprise you: both boxes are ticked by default when you have more than one layout enabled, so existing setups keep their switching gestures; and with a single enabled layout the two keys are hidden regardless of the checkboxes, because there is nothing to switch to.
>
> I'll close this when v2.0.0 is out; reopen if the keys survive unticking on that build.

- **APPROVE? [ ]**


---

## #162 — [Bug]: Will not recognise "gorgeous" when swiped

- **Proposed action:** close as not reproducible (at the v2.0.0 tag; post the comment now, since it corrects earlier on-thread advice and asks the reporter to retest)
- **Released state:** the swipe decoder the report was filed against (v1.5.0's neural engine) was removed after v1.5.0 (ADR-011, 2026-08-18); the CTC decoder that replaces it ships in v2.0.0. Replay instrument `afdd68a4` is unreleased.
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `afdd68a4` 2026-09-03 test(ctc): #162 gorgeous replay instrument + healthy-state pins — ancestor of HEAD: yes; tags: none. Frequency scale: `4525eb9c` 2026-09-06 (stored 1..255, default 255, calibrated per consumer), `b2031f90` 2026-10-08 (per-word swipe priority), `051dca51` 2026-10-10 (offer to raise words still at the old default 100) — all ancestors of HEAD, no `v*` tag.
  - tests: `src/test/kotlin/tribixbite/cleverkeys/swipe/CtcDecodeReplayTest.kt#gorgeousIsRankOneOnDeterministicShapes` and `#gorgeousIsRankOneOnHumanlikeTraces` (pure; `build.gradle` pureTestClasses:1127; 27 synthetic shapes on the production CTC model; `assumeOrt()` at `:47` skips them when ONNX natives are absent, so they only count where `extractOrtNative` ran) — exist at HEAD: yes. Evidence is synthetic only; no human trace of the reporter's gesture exists.
  - source: `src/main/assets/dictionaries/en_enhanced.json:36456` — `"gorgeous": 191` (in the swipe lexicon). `UserWordFrequency.kt:10-14` — custom-word frequency is 1..255, higher is stronger, default 255; `:40-42` — legacy values ≥ 256 saturate to the ceiling at read time, so the reporter's 10000 is treated as top. Add/Edit Word dialog hint "Frequency (1-255, higher wins)" (`strings.xml:1939`, `WordListFragment.kt:346-347`, `:429-433`); "Swipe priority" Normal/High/Highest (`strings.xml:241-245`); bar offer "Prefer “%1$s” when swiping?" (`strings.xml:199`, `SuggestionBar.kt:309`, `SuggestionHandler.kt:2688-2700`); Dictionary Manager Custom-tab offer to raise words still at 100 (`strings.xml:254-257`). Per-word on/off switch in the Dictionary Manager (`WordListFragment.kt:175`, `:290`). Path: Settings → Activities → Dictionary Manager (`strings.xml:447`) → Custom tab (`:1919`).
  - The maintainer's 2026-07-27 comment ("lower = higher frequency, so try setting it to 100") was wrong for v1.5.0 as well: `git show v1.5.0:src/main/kotlin/tribixbite/cleverkeys/OptimizedVocabulary.kt` line 558 — `tier = if (customFreq >= 8000) 2 else 1`, higher frequency got the stronger boost; the reporter's 10000 was already the top tier.
  - device check: not recorded (no phone trace of "gorgeous").
- **Thread notes:** molove (v1.5.0, Android 16): "gorgeous" never appears at any rank; wrong results Gothenburg, forgetting, github, …; beam width, confidence, boundary and repeated typing made no difference; a Claude-written analysis blames the self-intersecting path in the v1 encoder. Maintainer: add as custom word and/or disable Gothenburg; a geometric engine is coming. molove: custom word at frequency 10000 did not help. Maintainer: "lower = higher, try 100" (wrong). The reply must: say the decoder changed; state the synthetic result honestly; correct the frequency advice plainly; show the current levers (frequency 255, Swipe priority Highest, the "Prefer … when swiping" offer, the per-word switch for Gothenburg); ask for a retest on v2.0.0 with the exact outputs or a recording if it still fails.
- **Audit-doc correction:** none (row 114 and the 2026-10-20 note at `:60-61` already flag the frequency correction).
- **Draft reply:**

> First a correction. I said earlier that a lower number means a higher frequency and to try 100. That was wrong, in 1.5.0 as well: higher has always meant stronger, and your 10000 was already the strongest setting, so the number was never the problem.
>
> The swipe decoder you tested has since been replaced. In v2.0.0 swipes go through a different (CTC) decoder, and in my replays of the g-o-r-g-e-o-u-s gesture, 27 generated shapes at different speeds and wobble on the standard layout, "gorgeous" comes out first every time. Those are synthetic traces, not your finger, so I would like you to retest on v2.0.0 when it is out rather than take my word for it.
>
> If it still misses, two things help in v2.0.0. Custom words are now stored on a 1 to 255 scale (higher wins); in Settings → Activities → Dictionary Manager, Custom tab, edit "gorgeous" and set Frequency 255 and Swipe priority to Highest, which adds a fixed boost on top. Also, when you keep correcting a swipe to the same word, the suggestion bar offers "Prefer “gorgeous” when swiping?" and one tap does the same thing. Words such as Gothenburg can be switched off per word in the Dictionary Manager.
>
> I'll close this when v2.0.0 is out. If "gorgeous" still does not appear on that build, reopen with the exact words you get and, if you can, a short screen recording of the swipe. I'd rather fix it from a real trace than from my generated ones.

- **APPROVE? [ ]**




---

## #161 — [bug]: changing the portrait mode height changes the height for both landscape and portrait modes

- **Proposed action:** close as completed at the v2.0.0 tag. Owed first: a phone check that the two sliders move independently after rotation (never recorded; the pin is a source-scan test). Contributor PR #164 targets the same bug and is superseded (another agent drafts that PR note).
- **Released state:** unreleased, will ship in v2.0.0 (`a9c22871`)
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `a9c22871` 2026-09-03 fix(settings): landscape keyboard height no longer stomped by the portrait slider (#161) — ancestor of HEAD: yes; tags: none
  - tests: `src/test/kotlin/tribixbite/cleverkeys/KeyboardHeightOrientationDriftTest.kt` (pure JVM, source-scan drift test; 3 cases: `updateConfigFromSettings_doesNotStompOrientedKeyboardHeight`, `configRefresh_resolvesOrientationScopedHeightThroughTheSharedRule`, `appearanceSliders_writeDistinctOrientationKeys`) — exists at HEAD: yes
  - source: `ui/settings/SettingsPersistence.kt:535-540` — `keyboardHeightPercent` is deliberately not written by `updateConfigFromSettings` any more (the stomp)
  - source: `Config.kt:1119-1125` — `Config.refresh` resolves `keyboardHeightPercent` per orientation via `KeyboardHeightPrefs.percent(landscape = …)`; `CleverKeysService.kt:1135-1141` `onConfigurationChanged` → `refresh_config()`
  - source: `ui/settings/sections/AppearanceSection.kt:28-48` — the two sliders write `keyboard_height` and `keyboard_height_landscape`
  - strings: `res/values/strings.xml:159` "🎨 Appearance", `:561` "Keyboard Height (Portrait)", `:563` "Keyboard Height (Landscape)"
  - device check: not recorded for portrait/landscape independence. The later foldable-height sliders (`a6f870d6`, `35034af6`) were checked on the Saga 2026-10-20, which exercises the same resolver but not this exact case.
- **Thread notes:** reporter (v1.5.0, Android 12) describes exactly the stomp: portrait slider moves both heights, landscape slider does nothing; two recordings attached. No follow-ups.
- **Audit-doc correction:** none for the tracker row (`:115`). The 2026-10-20 audit's `CleverKeysService.kt:923-928` reference has drifted to `:1135-1141`.
- **Draft reply:**

> You read it right. Every time a setting was saved, the portrait height was copied into the one live height value, which is also what the keyboard reads in landscape, so the portrait slider silently overrode the landscape one and the landscape slider had no visible effect. That copy is gone in `a9c22871`. The keyboard now resolves the height for the current orientation when it starts and again on each rotation, and the two sliders (Settings → Appearance → "Keyboard Height (Portrait)" and "Keyboard Height (Landscape)") write to separate values.
>
> This is not in v1.5.0 and will ship in v2.0.0. After updating, set the two sliders to clearly different values and rotate: portrait should use the portrait value and landscape the landscape value, and changing one must not move the other.
>
> Thanks for the two recordings; they made the cause easy to pin down. I'll close this when v2.0.0 is out; reopen if the heights still track each other on that build.

- **APPROVE? [ ]**


---

## #160 — switching languages doesn't work

- **Proposed action:** close as completed at the v2.0.0 tag
- **Released state:** unreleased, will ship in v2.0.0 (`925f0016`; related `f85c8dcd`)
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `925f0016` 2026-09-03 fix(subtypes): a language switch now changes the visible layout (#160) — ancestor of HEAD: yes; tags: none
  - commits: `f85c8dcd` 2026-10-07 feat(language): bind a language to each layout (#186, #61) — ancestor: yes; tags: none (covers the "language" half: predictions follow the layout)
  - tests: `src/test/kotlin/tribixbite/cleverkeys/SubtypeLayoutFollowTest.kt` (mock, 7 cases; KDoc names both causes) — exists at HEAD: yes
  - source: `LayoutManager.kt:73-86` `setLocaleTextLayout` — when the layouts list has a NAMED entry selected, a subtype change now re-points the selection to the layout with that name, or to a System (null) entry
  - source: `SubtypeManager.kt:160-171` `selectCurrentSubtype` — identity match first, `languageTag` only as a fallback (method.xml has duplicate tags: ar/ar_TN, en/en_NG)
  - strings: `res/values/strings.xml:162` "📝 Input Behavior", `:1139` "Manage Keyboard Layouts", `:1283` "Keyboard Layouts" (the manager's title), `:1506` "Language: %1$s"
  - device check: not recorded
- **Thread notes:** one-line report: adding several language layouts works, but switching to another CleverKeys language in Android's keyboard picker keeps the first layout. No follow-ups (bot only). The reply explains both causes and asks for layouts/language if it persists.
- **Audit-doc correction:** none (`docs/audit/gh-issue-resolution.md:116` is accurate)
- **Draft reply:**

> Thanks for the report. I could reproduce it and found two separate causes, both fixed in `925f0016`.
>
> First, when you add layouts in the keyboard's own Layout Manager, that list took priority over the language picked in Android's keyboard switcher, so a system-level language switch changed nothing on screen. The switch now moves the keyboard to the layout that matches the chosen language (or to the "System" entry if you have one in the list).
>
> Second, some languages share a language tag with a regional variant, and switching between two of those always resolved to the first one. The chosen entry is now matched exactly first, so each variant gets its own layout.
>
> This is not in v1.5.0 and will ship in v2.0.0. After updating: add two or more language layouts in Settings → Input Behavior → Manage Keyboard Layouts, then switch languages with Android's keyboard picker; the visible layout should follow each time.
>
> One related addition: each layout can now carry its own language (the "Language: …" chip on each layout in the Layout Manager), so predictions and autocorrect switch with the layout as well (`f85c8dcd`).
>
> I'll close this when v2.0.0 is out. If it still happens on that build, please tell me which layouts you added and which language you switched to.

- **APPROVE? [ ]**


---

## #154 — [Bug]: Vibration delay on keypress.

- **Proposed action:** close as completed at the v2.0.0 tag (the latency half is already in v1.5.0; the reporter's explicit "system default" switch is unreleased). Maintainer choice: close now with the reply as written, or at the tag.
- **Released state:** latency fix in v1.5.0 (`0261aa97`, `ee7c4382`); the "Use System Vibration" switch is unreleased, will ship in v2.0.0 (`2a6c43fc`)
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `0261aa97` 2026-07-13 fix(haptics): use low-latency performHapticFeedback by default for keypress (#154) — ancestor of HEAD: yes; tags: v1.5.0
  - commits: `ee7c4382` 2026-07-13 fix(haptics): one-time migration clears bug-forced vibrate_custom (#154 follow-up) — ancestor: yes; tags: v1.5.0
  - commits: `2a6c43fc` 2026-10-20 feat(settings): system-vibration switch (#154) and Enter-always-newline (#139) — ancestor: yes; tags: none
  - tests: `src/test/kotlin/tribixbite/cleverkeys/HapticsBehaviorDriftTest.kt` (pure JVM, source-scan drift test, 14 cases; build.gradle pure list) — exists at HEAD: yes
  - source: `ui/settings/sections/AccessibilitySection.kt:74-82` — the switch is `checked = !vibrateCustom` and writes `vibrate_custom` to the pref and `Config.globalConfig()`; `:87-93` the "Custom Duration (optional)" slider is `enabled = vibrateCustom` (visible but disabled while the switch is on)
  - source: `VibratorCompat.kt:68` — custom one-shot only when `config.vibrate_custom && vibrate_duration > 0`, otherwise `performHapticFeedback` (`:82`); `Config.kt:80` `VIBRATE_CUSTOM = false` (system is the default); `Config.kt:2054-2078` the one-time migration
  - strings: `res/values/strings.xml:169` "♿ Accessibility", `:1556` "Haptic Feedback", `:165` "Vibration Feedback", `:484` "Use System Vibration", `:485` desc "Use your phone's built-in haptic pattern (lowest latency). Turn off to set a custom duration.", `:486` "Custom Duration (optional)"
  - device check: Saga 2026-10-20 17:06 PASS — switch ON shows the slider disabled, OFF enables it, ON disables again (`memory/todo.md:234-236`). The keypress latency itself was never measured on a device; the v1.5.0 fix is pinned by the drift test only.
- **Thread notes:** reporter (DetectiveIH, on 1.4.0) raised two things: (1) no "system default" option, only custom; (2) custom haptics feel rough and delayed. Both are answered. Only bot comments follow; the maintainer reopened it 2026-09-21 after the stale bot closed it. No earlier maintainer statement to correct.
- **Audit-doc correction:** `docs/audit/gh-issue-resolution.md:117` row text still says "Host tests only; not yet checked on a device" — stale; the Saga PASS of 2026-10-20 is recorded in `memory/todo.md:234` and in the tracker's own header paragraph (line 34).
- **Draft reply:**

> Two separate things were going on here, and both are addressed.
>
> The delay: in 1.4.0 the keyboard forced its own custom vibration path whenever vibration was turned on, which bypasses the phone's low-latency haptic engine. Since v1.5.0 the default is the system haptic again (`0261aa97`), and a one-time migration clears the stuck setting on upgrade (`ee7c4382`). If you are on 1.5.0 and still feel a delay, please tell me the device and whether the duration slider had ever been moved.
>
> The missing option: there is now an explicit switch, Settings → Accessibility → Haptic Feedback → "Use System Vibration" (`2a6c43fc`). It is on by default and uses your phone's built-in haptic pattern. While it is on, the "Custom Duration (optional)" slider is disabled; turn it off to set a custom duration, turn it back on to return to the system pattern. So a touched slider can always be undone now. That switch is not in 1.5.0 and will ship in v2.0.0.
>
> To check after updating: Settings → Accessibility, confirm "Use System Vibration" is on, then type a few keys. The feedback should be the same short tick other apps give you.
>
> I'll close this when v2.0.0 is out; reopen if the delay is still there on that build.

- **APPROVE? [ ]**


---

## #152 — [Bug]: Full GIF pack is unusably slow

- **Proposed action:** comment only, keep open (close after a 130k-pack retest on v2.0.0 — reporter confirmation or the maintainer's own soak; my call: the fix is real and pinned, but the complaint is wall-clock feel at 130k and nobody has measured that)
- **Released state:** unreleased, will ship in v2.0.0 (`56c47fc6`; follow-ups `d3fc3d5b`, `f6c802d4`)
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `56c47fc6` 2026-09-03 perf(gif): remove the O(results × pack) category hydration behind #152 — ancestor of HEAD: yes; tags: none
  - commits: `d3fc3d5b` 2026-09-07 fix(gif): E-1 portable upsert, E-3 compound pagination, E-7 FTS sanitizer, E-8 gid backfill, E-9 honest import count — ancestor: yes; tags: none; `f6c802d4` 2026-09-07 fix(gif): E-4 category/offset truth in the grid, E-5 results callback, E-1 scope crash guard — ancestor: yes; tags: none
  - "093b6d54" / "ea889ac5" are ew-cli RUN IDs (red → green on Pixel7/API 34), not git objects — the tracker row :118 now says so
  - tests: `src/androidTest/kotlin/tribixbite/cleverkeys/gif/GifDatabaseScaleInstrumentedTest.kt` (instrumented; imports a SYNTHETIC 500-GIF pack through the real `importPack`; pins: no per-row category hydration, `idx_gcm_gif` exists, `getCategoryCount(ALL)` reflects the pack) — exists at HEAD: yes. It does not time anything and uses 500 GIFs, not 130k
  - source: `gif/GifDatabase.kt:749` — `CREATE INDEX IF NOT EXISTS idx_gcm_gif ON gif_category_map(gif_id)` in onOpen (no version bump, so no re-import); `gif/GifGridView.kt:143-156` — per-keystroke search cancels the in-flight job and waits `SEARCH_DEBOUNCE_MS = 150L` (`:317`); `:130` — `setCategory` cancels a pending search
  - import caps: none that would refuse the 130k pack (`PackImportFailure` GIF variants are only no/partial thumbnails; `gif/GifPackManager.kt:59-175`)
  - device check: NOT recorded for the 130k pack. `memory/HANDOFF.md:290` and `docs/audit/2026-08-28-archive-verification.md:1126` both list "wall-clock feel on a real 130k pack → maintainer soak" as owed
- **Thread notes:** reporter (v1.4.0, Pixel 9): thumbs-all-130k import → search results take several seconds; thumbs-popular-25k fine. mooreye (2026-08-23, on v1.5.0): still relevant — consistent, the fix is unreleased. The reply should say what was found, what was measured (synthetic only), and ask for a retest on v2.0.0 with device and rough timing. It should also warn that the thumbnail packs will insert nothing on tap until the #149 repack (so a retester is not surprised).
- **Audit-doc correction:** none beyond what the header already fixed (run ids ≠ commits). The row's "close after 130k-pack soak" stands; the soak has not happened.
- **Draft reply:**

> I found the cause. For every search keystroke, each of the 100 result rows ran a separate query against the category table, which had no index on the GIF id, so each of those was a full scan of that table. The cost scaled with the pack size, which is why 25k felt fine and 130k did not, and the result of all that work was never even displayed.
>
> Fixed for v2.0.0 (`56c47fc6`): the per-row lookups are gone, the category table gets an index when the database is opened (no re-import needed), category counts moved off the main thread, and search now waits 150 ms after the last keystroke instead of running on every one. Follow-ups fixed the All view stopping at the first page (`d3fc3d5b`, `f6c802d4`).
>
> What I have measured so far is on a synthetic pack, not on the 130k one, so I would rather not call it done until someone has typed into the real thing. If you still have thumbs-all-130k, could you retest on v2.0.0 when it is out and tell me roughly how long a search takes to update, and on which device? I'll run it on a Pixel as well.
>
> One unrelated note so the retest does not surprise you: until I rebuild the thumbnail packs (see #149), tapping a GIF from one of them shows "GIF media unavailable" instead of inserting. Searching and browsing, which this issue is about, does not depend on that.

- **APPROVE? [ ]**




---

## #151 — [Bug]: Tapping suggested word leaves partial word instead of replacing it in some apps

- **Proposed action:** close as completed (at the v2.0.0 tag — comment now; mooreye's 2026-08-23 comment must be answered)
- **Released state:** partial-word replacement in v1.5.0 (`736e4eee`); trailing-space repair and Termux append-only unreleased, will ship in v2.0.0 (`9c8f5827`, `80207612`, `fd3eec40`)
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `736e4eee` 2026-07-13 fix(suggestions): replace typed partial token on suggestion tap in URL bars (#151) — ancestor of HEAD: yes; tags: v1.5.0
  - commits: `3f698714` 2026-09-03 test(suggestions): pin the #151 partial-word replacement in composing-less editors — ancestor of HEAD: yes; tags: none
  - commits: `9c8f5827` 2026-09-05 fix(suggestions): repair the editor-dropped trailing space after suggestion taps (#151 residual) — ancestor of HEAD: yes; tags: none
  - commits: `80207612` 2026-10-15 fix(terminal): a suggestion tap never deletes in terminal and blind fields — ancestor of HEAD: yes; tags: none; `fd3eec40` 2026-10-17 fix(terminal): never offer an unverifiable terminal word for the dictionary — ancestor: yes; tags: none
  - tests: `src/androidTest/kotlin/tribixbite/cleverkeys/Issue151UrlBarSuggestionTapTest.kt` (instrumented; real `BaseInputConnection`, 5 cases) — exists: yes; `src/test/kotlin/tribixbite/cleverkeys/SuggestionTapPartialReplaceTest.kt` (mock list build.gradle:1440) — exists: yes; `src/test/kotlin/tribixbite/cleverkeys/SuggestionTrailingSpaceRepairTest.kt` (mock :1456; `theManglingEditorGetsTheOwedSpaceOnTheNextKeystroke` red "expected: example w / but was: examplew") — exists: yes; `src/test/kotlin/tribixbite/cleverkeys/TerminalSuggestionPolicyTest.kt` (JVM, mock list :1446) — exists: yes
  - source: `PredictionContextTracker.kt:197-212` — "#151 TSR" trailing-space watch (arm at commit, resolve on cursor callbacks, consume on next keystroke via `takeOwedTrailingSpace`)
  - source: `SuggestionHandler.kt:1745-1766` — in Termux the tap goes through `TerminalSuggestionPolicy.planTap`: Append the remainder or Refuse, never delete; `TerminalSuggestionPolicy.kt:1-45` documents why
  - device check: RECORDED for the trailing space — `9c8f5827` body and `docs/audit/2026-08-28-archive-verification.md:1197-1198`: Chrome omnibox pre-fix `examplew` on the Pixel 8 Pro, post-fix `example w` on the Saga, no-double-space control. RECORDED for Termux — memory/todo.md "Open now" item 1: Saga 2026-10-16 `hel` + tap `help` → `help ` PASS; D2 PASS 2026-10-21. URL-bar partial-word fix: instrumented test only, no device record found
- **Thread notes:** reporter (v1.4.0, Vanadium URL bar): `exa` + tap `example` → `exa example `. EsterWings: also Brave/IronFox, other phones. aaktrance (two Xiaomi devices): Termux `helhello`; Fennec address bar `hel hello`; AIHub and GPTAssist complete the word but add no trailing space (GPTAssist: tapping again adds the space); Thunderbird/Simplenote fine. mooreye (2026-08-23, on v1.5.0): partially fixed — the partial word no longer remains, but no trailing space, so the next word cannot be typed immediately. Each of these has an answer: URL bars → `736e4eee` (v1.5.0, matches mooreye); missing space → `9c8f5827`; Termux → `80207612`/`fd3eec40`.
- **Audit-doc correction:** docs/audit/gh-issue-resolution.md:119 labels the row "**FIXED** (unreleased)". `736e4eee` IS in v1.5.0 (the header's correction list already says so at :63, but the row itself was not updated), and the row omits the Termux fix (`80207612`, `fd3eec40`). Row should read "partial-word v1.5.0; trailing-space `9c8f5827` and Termux append-only `80207612`/`fd3eec40` unreleased".
- **Draft reply:**

> Several things were going on in this thread, so here is where each stands.
>
> Browser URL bars (Vanadium, Fennec, Brave; the `exa` + example > `exa example ` case): fixed in v1.5.0 (`736e4eee`). Those fields do not report cursor movement to the keyboard, so the tap never knew how much to delete; it now reads the field directly before replacing. @mooreye, the partial fix you saw is this one.
>
> The missing trailing space (AIHub, GPTAssist, and mooreye's follow-up): some editors drop the trailing space of the committed word on their side. The keyboard now notices that and puts the space back the moment you type the next letter, so you can go straight on typing. I verified it in the Chrome address bar: before, `example` then `w` gave `examplew`; after, `example w`. Editors that keep the space are untouched. Not in v1.5.0; ships in v2.0.0 (`9c8f5827`).
>
> Termux (`hel` + hello > `helhello`): the terminal never shows the keyboard what is on the line, so deleting there is unsafe. In terminal fields a tap now appends only the rest of the word (`hel` + help gives `help `), and a suggestion that would need a deletion is not offered (`80207612`, `fd3eec40`). Checked on a phone with Termux; ships in v2.0.0.
>
> Thunderbird and Simplenote were never affected because they report the cursor properly.
>
> To check after updating: type `exa` in a browser address bar, tap `example`, then type `w`; expect `example w`. In Termux type `hel` and tap a suggestion; expect `help ` or `hello `.
>
> I'll close this when v2.0.0 is out; reopen with the app name if a field still misbehaves.

- **APPROVE? [ ]**




---

## #149 — [Bug]: gif zip pack don't work

- **Proposed action:** comment only, keep open (maintainer decision needed: rebuild + re-upload the 15 thumbnail packs with `gid:` tokens; then close at/after the v2.0.0 tag)
- **Released state:** unreleased, will ship in v2.0.0 (`ecd1abc8`, `56597c1b`); the published GIF pack assets are UNCHANGED since 2026-03-09
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `ecd1abc8` 2026-09-06 fix(gif): #149 — Giphy IDs carried case-preserved via a marked token; pure insert policy — ancestor of HEAD: yes; tags: none
  - commits: `56597c1b` 2026-09-06 fix(ime): #148/#149 receiver — pane openers never replace the input view; GIF tap inserts media, not dead links — ancestor of HEAD: yes; tags: none
  - commits: `d3fc3d5b` 2026-09-07 fix(gif): … E-8 gid backfill … — ancestor of HEAD: yes; tags: none (a later pack carrying `gid:` updates legacy rows for the same GIF ids, `gif/GifDatabase.kt:402-417`)
  - tests: `src/test/kotlin/tribixbite/cleverkeys/gif/GifInsertPolicyTest.kt` (pure list build.gradle:666; 12 cases of the decision matrix) and `src/test/kotlin/tribixbite/cleverkeys/gif/GifTest.kt` (pure :663; incl. `getGiphyUrl is null for legacy case-smashed search text`, the reporter's exact dead URL) — exist at HEAD: yes; receiver half in `KeyboardReceiverPaneHostTest` (mock)
  - source: `gif/GifInsertPolicy.kt:53-67` — ladder: COMMIT_MEDIA (API 25+, IC, local full WebP, editor accepts image/webp) → COMMIT_URL_TEXT (only with a marked ID) → COPY_MEDIA_TO_CLIPBOARD → COPY_URL_TO_CLIPBOARD → NONE
  - source: `gif/Gif.kt:104-108` — `getGiphyId()` reads ONLY the `gid:` token (`GIPHY_ID_MARKER`, `:149`); `:113-118` — `getGiphyUrl()` null for legacy packs, so a dead URL can never be built
  - source: `KeyboardReceiver.kt:648-696` — `insertGif`: `hasLocalMedia` = full WebP on disk; `NONE` shows the suggestion-bar message "GIF media unavailable" (strings.xml:2205)
  - pipeline writes the marker: `tools/gif_pipeline/pack_builder.py:279`, `make_pack.py:765`, `build_database.py:368`
  - release assets (LIVE, `gh release view CleverKeys-GIF` 2026-10-25): all 22 assets last updated 2026-02-19 … 2026-03-09, i.e. BEFORE `gid:` existed. No `tribixbite/CleverKeys-GIF` repo (404). Release body: "`full/NNN/ID.webp` — animated WebP (discord packs only)"; the thumbnail packs (thumbs-all-130k, thumbs-popular-25k, 7 category, 6 all-thumbs) hold 80px previews only
  - consequence at HEAD for a published thumbnail pack: `hasLocalMedia=false`, `hasUrl=false` → `NONE` → a tap inserts nothing and shows "GIF media unavailable". Only the six discord-community packs insert (commitContent) today. Re-importing the same pack id returns `AlreadyInstalled` unless replaced (`gif/GifPackManager.kt:97-108`), so a rebuilt pack is installed by removing the old one first
  - spec: `docs/specs/gif-panel-spec.md:245` still has "[ ] Rebuild database from optimized/ with Giphy metadata" unchecked
  - device check: not recorded for the commitContent tap path
- **Thread notes:** reporter (v1.4.0, GrapheneOS Android 16) downloaded "the offline gif zip from the git page" (the release's Quick Start points at the thumbnail packs); selecting a GIF inserted a dead link `https://media.giphy.com/media/cutecdmyfhpeane9ckv6ys/giphy.gif`. No human follow-ups. Reopened 2026-09-21.
- **Audit-doc correction:** docs/audit/gh-issue-resolution.md:120 recommends "close as completed citing both; note the repack". I disagree: for the packs the reporter can download, v2.0.0 turns a dead link into "GIF media unavailable" — nothing is inserted. The dead-link defect is fixed, but the reporter's packs still do not work until the thumbnail packs are rebuilt with `gid:` tokens and re-uploaded (not done as of 2026-10-25). Row should be PARTIAL / "comment, keep open until the repack".
- **Draft reply:**

> You hit two defects at once. The pack pipeline lower-cased the whole search text, and Giphy media IDs are case-sensitive; and the "ID" was taken to be the last keyword, which for your GIF was the compound token `cute` plus the ID. Every link built that way was dead, for every pack.
>
> What changed in the code (`ecd1abc8`, `56597c1b`, both for v2.0.0): a tap now inserts the GIF itself, as the locally stored animated WebP, in any app that accepts images from the keyboard (most messengers do). Where that is not possible it inserts a Giphy link only if the pack carries the real, case-preserved ID; otherwise it copies the media to the clipboard. A dead link can no longer be produced.
>
> What that means for the packs you can download today, and I want to be plain about it: the thumbnail packs (thumbs-popular-25k, thumbs-all-130k, the category packs) contain only the 80px previews and no IDs in the new form, so on v2.0.0 a tap on one of those shows "GIF media unavailable" in the suggestion bar instead of a dead link. Only the discord-community packs carry full animations and insert on v2.0.0. The thumbnail packs need to be rebuilt with the IDs and re-uploaded before they insert working links; the pipeline already writes them, and the rebuild is on me. When they are up, remove the old pack and import the rebuilt one.
>
> I'll leave this open until the rebuilt packs are published and report here when they are.

- **APPROVE? [ ]**




---

## #148 — [Bug] Hiding the keyboard when opening the clipboard

- **Proposed action:** close as completed (at the v2.0.0 tag — comment now)
- **Released state:** unreleased, will ship in v2.0.0 (`cb7cebd4`, `56597c1b`; inset half `aafec4da`)
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `cb7cebd4` 2026-08-27 fix(ui): #148 — keyboard no longer vanishes when a pane opens with predictions off (ARC-002) — ancestor of HEAD: yes; tags: none
  - commits: `56597c1b` 2026-09-06 fix(ime): #148/#149 receiver — pane openers never replace the input view; GIF tap inserts media, not dead links — ancestor of HEAD: yes; tags: none
  - commits: `aafec4da` 2026-09-05 fix(insets): config/nav-mode change re-derives Keyboard2View insets (#167 residual) — ancestor of HEAD: yes; tags: none
  - tests: `src/test/kotlin/tribixbite/cleverkeys/KeyboardReceiverPaneHostTest.kt` (mock list build.gradle:1472; 10 tests incl. `gifOpenWithoutContainerNeverReplacesTheInputView`, `switchBackWithoutContainerNeverCallsSetInputView`) — exists at HEAD: yes
  - tests: `src/test/kotlin/tribixbite/cleverkeys/CoreImeHygieneDriftTest.kt#predictionsDisabledStillBuildsTheContentPaneContainer` (:127, source-scan drift, pure list build.gradle:784) — exists at HEAD: yes
  - source: `PredictionViewSetup.kt:219-226` — predictions-disabled branch builds the same topPane/contentPaneContainer hierarchy with the suggestion strip collapsed to 0 height (comment names the old `setInputView(pane)` fallback as "deterministic on every device, not HyperOS-specific")
  - source: `KeyboardReceiver.kt:305-312` — SWITCH_EMOJI refuses without a container ("NEVER setInputView(pane)"); `:553-556` — SWITCH_BACK has no `setInputView` restore; no `setInputView(` call site remains in the receiver (only the two comments)
  - device check: RECORDED — `docs/audit/2026-08-28-archive-verification.md:769` (Wave K2, Pixel full protocol): "#148 fixed behavior visually confirmed (pane overlays a visible keyboard, prefs restored)"
- **Thread notes:** reporter (v1.4.0 HyperOS, Android 15, imgur recording): with "neural network prediction" and "word predictions" off, opening the clipboard hides the keyboard body and the clipboard sits behind the navigation buttons, so the single entry is unusable. synthyst (2026-06-10): depends on the suggestion bar's height; workaround = turn predictions on; hopes it gets fixed. Maintainer (2026-06-10) explained the pane reuses the suggestion bar area. The neural switch no longer exists (neural engine removed); the two switches today are Enable Word Predictions and Enable Swipe Typing. Reopened 2026-09-21.
- **Audit-doc correction:** none (row 121 is accurate; it could cite the Pixel visual check at archive-verification.md:769, which it omits).
- **Draft reply:**

> Thanks for the recording, it pointed straight at it. This was not HyperOS-specific. With word predictions and swipe typing both off, the keyboard was built without the area that the clipboard, emoji and GIF panes open into, so the pane opener fell back to replacing the whole input view with the pane. That is why only the pane was visible, and because that bare pane sat outside the part of the keyboard that accounts for the navigation bar, it landed behind the buttons. The suggestion-bar dependency synthyst noticed is the same thing from the other side: turning predictions on built the area.
>
> Two changes fix it: the pane area is now always built, with the suggestion strip collapsed to zero height when predictions are off (`cb7cebd4`), and the openers can no longer replace the input view at all (`56597c1b`). A related fix makes the keyboard re-read the navigation-bar height when the nav mode or orientation changes (`aafec4da`). I checked the fixed build on a Pixel with predictions off: the clipboard opens above a still-visible keyboard, nothing behind the nav bar.
>
> None of this is in v1.5.0; it ships in v2.0.0. To check after updating: Settings → Input Behavior → Enable Word Predictions off, Settings → Swipe Typing → Enable Swipe Typing off, then open the clipboard. The keyboard should stay below the pane and the entries should be tappable.
>
> I'll close this when v2.0.0 is out; reopen if the keyboard still disappears on that build.

- **APPROVE? [ ]**




---

## #146 — [Bug]: (untitled on GitHub; "Cannot install Dutch language pack")

- **Proposed action:** close as completed (now — the pack is live and importable on v1.4.0/v1.5.0; the in-app link is a v2.0.0 convenience)
- **Released state:** pack asset live since 2026-09-27 (no app change needed); in-app link unreleased, will ship in v2.0.0 (`7e1034be`)
- **Evidence (verified at HEAD ac86bda5):**
  - release asset (LIVE, `gh release view langpacks` 2026-10-25): `langpack-nl.zip` 1,507,750 bytes, updated 2026-09-27T22:36:16Z, on https://github.com/tribixbite/CleverKeys/releases/tag/langpacks (23 packs total; `langpack-fa.zip` was added 2026-10-25)
  - repo copy: `scripts/dictionaries/langpack-nl.zip` 1,507,750 bytes (same size as the asset)
  - commits: `7e1034be` 2026-09-03 feat(langpack): in-app pack link points at the langpacks release; mk/he display names — ancestor of HEAD: yes; tags: none
  - tests: none specific to the asset or the link (n/a)
  - source: `ui/settings/sections/MultiLanguageSection.kt:279-301` — "Language Packs" header, description, and the clickable "Browse available packs ↗" text opening `SettingsActivity.LANGUAGE_PACKS_URL`; `activities/SettingsActivity.kt:96-97` — URL `https://github.com/tribixbite/CleverKeys/releases/tag/langpacks`; `:324`/`:330` — "Import Pack" / "Manage" buttons
  - docs: `docs/wiki/layouts/language-packs.md:73` lists Dutch (`langpack-nl.zip`, 40,000 words); `:119-125` post-import steps (set Primary or Secondary Language). The "Download Language Packs" wording the reporter quoted is absent at HEAD (`rg` over README/docs/site: no hits; it was in README via `843b8c8e` and removed by `8c2fa657`)
  - device check: n/a (asset presence verified live; import flow is the existing Import Pack path)
- **Thread notes:** reporter (v1.4.0 on /e/OS): Languages screen shows only Import Pack and Manage; no "Download Language Packs" option despite documentation; asks how to install the Dutch pack. No human follow-ups. Reopened by the maintainer 2026-09-21. The reply must give the download URL, the import steps, the activation step, and correct the docs claim.
- **Audit-doc correction:** docs/audit/gh-issue-resolution.md:122 says "linked from the Languages screen" without qualification; that link (`7e1034be`) is in no `v*` tag, so v1.5.0 users still have no in-app path. Row should say "linked on main (unreleased)".
- **Draft reply:**

> The Dutch pack exists and is published: `langpack-nl.zip` (about 1.5 MB, 40,000 words) on the langpacks release, https://github.com/tribixbite/CleverKeys/releases/tag/langpacks, next to the other prebuilt packs.
>
> To install it on your version: download the zip in your browser, then in CleverKeys go to Settings → Multi-Language → Language Packs → Import Pack and pick the file. Then set Primary Language (or Secondary Language) in the same section to Dutch. Manage lists the installed packs. Predictions, autocorrect and swipe decoding then use the Dutch dictionary with a Latin layout.
>
> The app has no internet permission, so there is no in-app download. The documentation wording that suggested a download button was wrong and no longer says that. v2.0.0 adds a "Browse available packs" link in that section that opens the release page in your browser (`7e1034be`), so the path is visible from inside the app.
>
> I'll close this as completed; reopen if the import fails and include the message it shows.

- **APPROVE? [ ]**




---

## #145 — [Bug]: "Customize per key action" gestures stop working on phone reboot if swipe typing is disabled

- **Proposed action:** close as completed (at the v2.0.0 tag, after the owed cold-start device check — comment now)
- **Released state:** unreleased, will ship in v2.0.0 (`8d3ac943`, `fddb65d5`)
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `8d3ac943` 2026-08-18 fix(ime): restore word swipes and user-created subkey short swipes — my regression in d671d19e — ancestor of HEAD: yes; tags: none (the real fix: wiring moved outside every config gate)
  - commits: `fddb65d5` 2026-09-01 refactor(wiring): ARC-072 slice 3 — six Initializers collapse into KeyboardComponentGraph — ancestor of HEAD: yes; tags: none (keeps the unconditional call)
  - commits: `a53a24a3` 2026-10-02 test(keyboard): protect cold-start custom gesture wiring (gh #145) — ancestor of HEAD: yes; tags: none (adds the regression test; missed by the audit lead list)
  - commits (NOT the fix): `5e7fdcb7` 2026-06-10 fix(gestures): swipe_typing_enabled gates every word-swipe route — tags: v1.5.0 — a different latch defect; kxtbit's 2026-09-10 "still present" on v1.5.0 is consistent
  - tests: `src/test/kotlin/tribixbite/cleverkeys/KeyboardViewLateBindingDriftTest.kt#coldViewGetsServiceHandleWithPredictionAndSwipeTypingDisabled` (:104; mock list build.gradle:1479; runs the REAL `wireSwipeTypingComponents` and the real view setter with both switches off, null predictor, twice for a replacement view, verifies no model load) and `#onCreateWiresSwipeTypingComponentsUnconditionally` (source-scan of `CleverKeysService.onCreate`) — exists at HEAD: yes
  - source: `wiring/KeyboardComponentGraph.kt:258-262` — `keyboardView.setSwipeTypingComponents(predictor, service)` runs UNCONDITIONALLY, before the `if (config.word_prediction_enabled || config.swipe_typing_enabled)` gate at `:264`; KDoc `:231-252` names the three wrong gates that lived there
  - source: `CleverKeysService.kt:483` — `_graph.wireSwipeTypingComponents()` in `onCreate`, no condition; `:695`, `:762` re-set the handle when the view is rebuilt
  - source: `Keyboard2View.kt:1088-1095` — `onCustomShortSwipe` returns early with "Cannot execute custom swipe: no service reference" when `_keyboard2` is null (the symptom)
  - device check: NOT recorded. The cold-start (reboot, swipe typing off) check is still owed — memory/todo.md "Open now" item 9 (2026-10-25: "#145 cold start" on a test phone)
- **Thread notes:** reporter (v1.4.0, Android 15): custom gestures die after reboot when swipe typing is off; toggling swipe typing on/off revives them; suspects same as #138. vectravox (2026-05-31): same, also after switching keyboards. kxtbit (2026-09-10): still present (on v1.5.0, the only release since). The reply must say v1.5.0 does not contain the fix, explain the mechanism, and give the reboot test.
- **Audit-doc correction:** none (row 123 already carries the corrected attribution and "close after release" + cold-start test).
- **Draft reply:**

> You are right that this was still happening on v1.5.0. The change I had earlier attributed to it (`5e7fdcb7`) fixed a different problem.
>
> The actual cause: at start-up the keyboard view is handed a reference to the keyboard service, and that step only ran when swipe typing was on. Custom short swipes go through that reference, so with swipe typing off at a cold start (after a reboot, or after switching keyboards, which restarts the service) they had nothing to call and did nothing. Turning swipe typing on ran the step; turning it off again left the reference in place, which is exactly the workaround you found. Subkeys defined by the layout itself never used that path, which is why only the customised ones broke.
>
> The reference is now set unconditionally at start-up, before any prediction or swipe setting is read (`8d3ac943`, kept through the later wiring rework in `fddb65d5`). This is not in v1.5.0; it will ship in v2.0.0.
>
> To check after updating: turn off Enable Swipe Typing (Settings → Swipe Typing) and Enable Word Predictions (Settings → Input Behavior), reboot, and use a customised short swipe without touching any setting first. Switching to another keyboard and back is the same test.
>
> I'll close this when v2.0.0 is out; reopen if a custom gesture is dead after a reboot on that build.

- **APPROVE? [ ]**




---

## #141 — Timestamp keys cannot be assigned through Short Swipe Customization

- **Proposed action:** close as completed (now)
- **Released state:** in v1.5.0 (`f3b02b3c`)
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `f3b02b3c` 2026-05-22 feat(customization): TIMESTAMP ActionType for short-swipe customization (#141) — ancestor of HEAD: yes; tags: v1.5.0
  - commits (racuna redirect): `918dc322` 2026-10-20 feat(gestures): short swipes only mode (#147, #87) — ancestor of HEAD: yes; tags: none
  - tests: `src/test/kotlin/tribixbite/cleverkeys/customization/CustomShortSwipeExecutorTest.kt#executes TIMESTAMP and commits formatted current date` (:55), `#executes TIMESTAMP with date-and-time pattern` (:84), `#executes TIMESTAMP with time-only HH-mm pattern` (:105), `#executes TIMESTAMP with ISO 8601 pattern containing literal T` (:124) (MockK, mock list build.gradle:1399) — exists at HEAD: yes
  - source: `customization/ShortSwipeMapping.kt:33-40` — `ActionType.TIMESTAMP` validates the value as a parseable `SimpleDateFormat` pattern; `:183-191` factory stores the pattern untrimmed (`take(MAX_ACTION_LENGTH)`), so a trailing `" - "` survives; `:229-234` getter
  - source: `customization/CommandPaletteDialog.kt:646` — "Timestamp" `QuickAction` in the action picker; `:1099-1101` pattern dialog validates (blank / invalid) and `:243-247` live preview; presets strings.xml:1268-1278 (Date, Date + Time, Time, Time + sec, 12h time, Day Mon d, Long date, ISO 8601, Slash MDY); dialog title "Timestamp Pattern" (:1257)
  - source: `customization/CommandRegistry.kt:628-649` — eight fixed `timestamp_*` commands in the "Timestamps" category; `customization/XmlAttributeMapper.kt:48` — XML form `timestamp:'…'`
  - source (redirect): `ui/settings/sections/GestureTuningSection.kt:68` — "Short Swipes Only" switch
  - device check: not recorded in what I read (shipped in v1.5.0 three months ago; no on-thread complaint since)
- **Thread notes:** reporter (v1.4.0, Android 10) wanted timestamp_date/timestamp_datetime or custom patterns (`yyyy-MM-dd`, `yyyy-MM-dd HH:mm`, `yyyy-MM-dd - `, `yyyy-MM-dd HH:mm - `) assignable to a short swipe; Custom Text saved them as literal text. racuna (2026-05-19) raised an unrelated complaint: short swipes are too long while typing even at the maximum gesture length. The maintainer answered racuna 2026-05-21 ("going to see if I can improve this … collision with swiped words like 'we'"). That is #147/#87, fixed by `918dc322` (unreleased); the reply redirects.
- **Audit-doc correction:** none (row 124 is accurate).
- **Draft reply:**

> This is in v1.5.0 (`f3b02b3c`). In Short Swipe Customization, pick the key and the direction; the action picker has a Timestamp entry alongside Custom Text. It opens a Timestamp Pattern dialog with preset chips (Date, Date + Time, Time, ISO 8601 and others), a free pattern field using the Java SimpleDateFormat letters, and a live preview of what the current time formats to. Your examples work as typed, including `yyyy-MM-dd HH:mm - `: hyphens, spaces and colons are literal, and letters that are not pattern letters go in single quotes (for example `'T'`). The command list also has fixed entries (Date (ISO), Time (24h), Date & Time, and so on) if you do not need a custom pattern.
>
> @racuna, the swipe-length problem you describe is a different issue (#147, #87). The fix for it is a Short Swipes Only switch under Settings → Gesture Tuning: every swipe from the minimum distance up is treated as a short swipe, however long it is, and swipe typing is off while it is on (`918dc322`). That ships in v2.0.0; please follow those issues for it.
>
> I'll close this as completed; reopen if a pattern that SimpleDateFormat accepts does not insert.

- **APPROVE? [ ]**




---

## #134 — [Bug]: 'short key customization' keyboard disappearance

- **Proposed action:** close as completed (now — the reporter's ask is in v1.5.0; the entry-path residual rides v2.0.0)
- **Released state:** in v1.5.0 (`6ff48751`); residual unreleased, will ship in v2.0.0 (`a9b000cf`)
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `6ff48751` 2026-04-26 feat: #134 show-keyboard button on customization screen — ancestor of HEAD: yes; tags: v1.5.0
  - commits: `a9b000cf` 2026-09-06 fix(customization): IME entry path stops toggling the keyboard away (#134) — ancestor of HEAD: yes; tags: none
  - tests: `src/test/kotlin/tribixbite/cleverkeys/Issue134ImeEntryContractTest.kt#entry path must not toggle the IME`, `#entry path must not force-show the IME`, `#entry path and reopen button share the showSoftInput mechanism` (source-scan contract test, pure list build.gradle:754) — exists at HEAD: yes
  - tests: `src/androidTest/kotlin/tribixbite/cleverkeys/Issue134ShowKeyboardButtonComposeTest.kt` (instrumented, the reopen button) — exists at HEAD: yes
  - source: `src/main/kotlin/tribixbite/cleverkeys/activities/ShortSwipeCustomizationActivity.kt:177-186` — TopAppBar IconButton "⌨" (contentDescription "Show keyboard") does `focusRequester.requestFocus()` + `imm.showSoftInput(rootView, 0)`
  - source: `ShortSwipeCustomizationActivity.kt:130-139` — the entry `LaunchedEffect` now calls `imm.showSoftInput(rootView, 0)` (was `toggleSoftInput(SHOW_FORCED)`, which HID an already-visible IME at entry)
  - source: reached via `activities/ShortSwipeCalibrationActivity.kt:489,506` ("Customize Per-Key Actions") and settings search; the direct `openShortSwipeCustomization()` helper was removed (`ui/settings/SettingsNavigation.kt:54-56`)
  - device check: the button is recorded as on-device verified (Issue134ImeEntryContractTest KDoc, via the instrumented Compose test); the `a9b000cf` entry-path fix has NO device record — pinned only by the source-scan test
- **Thread notes:** reporter (v1.3.0, Android 12) asked for exactly one thing: a static keyboard button on the customization screen to reopen the IME without backing out. No human follow-ups (bot welcome/stale only). Reopened by the maintainer 2026-09-21 after a bot close.
- **Audit-doc correction:** docs/audit/gh-issue-resolution.md:125 labels the row "**FIXED** (unreleased)". The reporter's ask (the button) is in v1.5.0 (`6ff48751`); only the entry-path residual `a9b000cf` is unreleased. Row should read "button v1.5.0; entry-path residual unreleased".
- **Draft reply:**

> The keyboard button you suggested shipped in v1.5.0 (`6ff48751`): the Short Swipe Customization screen has a keyboard icon at the top right that brings the keyboard back without leaving the screen.
>
> There was a second part I only fixed later. Opening the screen used a "toggle keyboard" call, so if a keyboard was already showing when you entered, the toggle hid it. That is the vanish-at-entry you described. The screen now shows the keyboard with a plain show call instead (`a9b000cf`). That part is not in v1.5.0 and will ship in v2.0.0.
>
> To check after updating: open Short Swipe Customization (Settings → Activities → Short Swipe Calibration → Customize Per-Key Actions, or search Settings for it) while a keyboard is already up. It should stay up. If it ever goes away, the keyboard icon at the top right reopens it.
>
> I'll close this as completed; reopen if the keyboard still disappears on v2.0.0.

- **APPROVE? [ ]**




---

## #130 — [Bug]: Clipboard doesn't follow Custom Theme colors

- **Proposed action:** close as completed (at the v2.0.0 tag). Owed before/at close: the soak check of the two residual elements (inline edit-field text colour, search-highlight colours) and the unchecked manual-checklist item.
- **Released state:** split — pane painted with the active theme on open is in v1.5.0 (`965b71e0`, `b8f17ae5`); re-theming an already-built pane and the emoji/GIF panes is unreleased, will ship in v2.0.0 (`a7940256`, `7144e2c7`)
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `965b71e0` 2026-06-02 fix(clipboard): honor custom/decorative theme colors in clipboard panel (#130) — ancestor: yes; tags: v1.5.0
  - commits: `b8f17ae5` 2026-04-26 feat: #130 timestamp dim color resolves from theme attr colorSubLabel — ancestor: yes; tags: v1.5.0 (not in the audit row)
  - commits: `a7940256` 2026-09-05 fix(clipboard): theme changes reach a cached clipboard pane (#130) — ancestor: yes; tags: none
  - commits: `7144e2c7` 2026-09-07 fix(ime): … H-3 runtime theme … — ancestor: yes; tags: none (emoji/GIF panes)
  - commits (commenter's ask): `fd6a1085` 2026-03-28 feat: clipboard UI overhaul — icons, tap-to-expand, tags & status — ancestor: yes; tags: v1.4.0, v1.5.0. Replaced the emoji tab labels with vector drawables (`ic_tab_history/pinned/todos` added here)
  - tests: `src/test/kotlin/tribixbite/cleverkeys/clipboard/ClipboardPaneThemePolicyTest.kt` (pure, `build.gradle:840`; 9 methods `:34-115`, e.g. `#runtimeThemeColorBeatsTheBaseStyleAttr`, `#editingTheActiveCustomThemesColorsRebuildsEvenThoughTheNameIsUnchanged`) — exists: yes. `clipboard/ClipboardPaneThemeInvalidationTest.kt` (mock, `build.gradle:1351`; `#switchingThemesWhileAPaneIsCachedDropsTheCachedPane`, `#unchangedThemeKeepsTheCachedPane`, `#editingTheActiveCustomThemesColorsDropsThePaneEvenThoughTheNameIsUnchanged`, `#invalidationPreservesTheCloseCallbackAndTabChoice`, `#aBulkResultArrivingAfterThePaneWasDroppedIsKept`, `:75-175`) — exists: yes
  - source: `src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardPaneThemePolicy.kt:1-20` (mechanism: a runtime custom theme has no XML style, so `?attr/` resolved to the CleverKeysDark base — the purple); `clipboard/ClipboardManager.kt:1521` `applyRuntimeThemeColors()`, `:1488` `effectiveColor`, `:1617` `needsRebuild(appliedThemeSignature, currentThemeSignature())`
  - icons: `res/layout/clipboard_pane.xml:41,56,72` `@drawable/ic_tab_*`; `res/layout/clipboard_history_entry.xml` row actions are all `@drawable/ic_*`. The one remaining emoji glyph is the private-copy lock badge (`strings.xml:353` `glyph_private_badge` = 🔒, used at `clipboard_history_entry.xml:59`)
  - release note: `CHANGELOG.md:516` under [2.0.0]
  - device check: NOT done — `docs/plans/v2.0.0-manual-test-checklist.md:50` "[ ] Clipboard pane matches theme immediately after a theme change (#130, H-3)" is unchecked; `memory/HANDOFF.md:298` lists a "#130 visual residue (edit-field white text, search-highlight colors — soak)". The tracker row (`gh-issue-resolution.md:126`) names the same residue
- **Thread notes:** Reporter (1.3.0, Android 16): with a custom theme active, the clipboard pane is purple and cannot be edited. synthyst (2026-04-24, on 1.3.0): "using SVG icons instead of emojis would be nicer" — already shipped in 1.4.0 the next day, barring the lock badge. Stale bot 2026-09-06; reopened 2026-09-21. The reply covers the mechanism, the two-stage fix, the icons, and asks for element-level reports.
- **Audit-doc correction:** `docs/audit/gh-issue-resolution.md:126` is accurate on commits and residue; add `b8f17ae5` (v1.5.0, timestamp colour) and note that the "close as completed" recommendation is conditional on the soak residue being either confirmed fine or filed separately.
- **Draft reply:**

> The purple came from how custom themes were applied. A custom theme has no XML style, so the clipboard pane fell back to the colours of the base dark style instead of yours. Since 1.5.0 the pane is painted with the active theme's colours when it opens (`965b71e0`, `b8f17ae5`). One gap remained: changing the theme while the keyboard was already running left an already-built pane in the old colours until the keyboard restarted. That is fixed in `a7940256`, and the emoji and GIF panes got the same treatment (`7144e2c7`). Those two ship in v2.0.0.
>
> @synthyst: the pane's tabs and row actions have used vector icons rather than emoji since 1.4.0 (`fd6a1085`). The one emoji glyph left is the lock badge on privately copied entries.
>
> To check after updating: create a custom theme, make it active, open the clipboard; then edit the theme's colours and open the clipboard again without restarting the keyboard. It should match both times. I'll close this when v2.0.0 is out. Reopen with a screenshot if any part of the pane still shows the wrong colour, and say which element (for example the inline edit field or the search highlight).

- **APPROVE? [ ]**


---

## #99 — Unclear or outdated documentation of build_langpack.py

- **Proposed action:** close as completed (now — the fix is documentation and scripts on main; no app release is needed to benefit from it)
- **Released state:** unreleased as a tag (`b5772e87`, 2026-10-20), but the README, wiki and script are live on main, which is what the reporter uses
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `b5772e87` 2026-10-20 fix(scripts): build_dictionary accepts word,frequency CSV; document word-list formats (#99) — ancestor: yes; tags: none
  - tests: none in the JVM suites (Python script). Independent run from the scratchpad against HEAD's `scripts/build_dictionary.py`: input lines `alma,120` / `körte<TAB>45` / `szilva 7` / `barack` / `ez egy mondat, nem szó` → `[('alma', 120.0), ('körte', 45.0), ('szilva', 7.0), ('barack', None), ('ez egy mondat, nem szó', None)]`, with a stderr note that one multi-token line had no frequency. The CSV form the README promises is now really parsed
  - source: `scripts/build_dictionary.py:98-171` `load_word_list` (comma branch `:137-139`, taken only when the text after the last comma parses as a number); `scripts/build_langpack.py:405-411` `--input` is handed to `build_dictionary.py --input`, so the same loader applies; `:54-56` usage shows `--input` or `--dict` is required; `:749-753` `--use-wordfreq` on an unlisted language implies wordfreq as the attributed data source, so the Hungarian command below is not refused
  - docs: `README.md:379-391` Options 2 and 3 state the four line formats and "raw prose (e.g. a Wikipedia dump) is not tokenized — make a word list first"; `docs/wiki/layouts/language-packs.md:151-156` same; `docs/guides/adding-a-new-language.md:46-72` full new-language sequence. `scripts/get_wordlist.py:21-27` lists `hu` as a wordfreq-supported language
  - the reporter's symptom: the old loader kept the first token of each line, so a 73-line prose dump became 73 "words"; the new loader keeps the whole line as one entry when there is no trailing number (and skips lines over 50 characters). Either way prose is not tokenised; the docs now say so
  - device check: n/a (scripts/docs)
- **Thread notes:** Reporter wants Hungarian; found the README example `build_langpack.py --lang fr --output …` fails because `--input` is required; fed a scraped Hungarian Wikipedia page as `--input` and got a 73-word dictionary; found no statement of the input format; asks for clear README instructions on creating and adding custom packs. Bot only otherwise (stale 2026-04-12). Reopened by the maintainer 2026-09-21. The reply explains the two doc errors, the format, why 73 words, and gives a Hungarian recipe plus the import path.
- **Audit-doc correction:** `docs/audit/gh-issue-resolution.md:127` still reads "RESOLVED — README shows the full `--input` invocation, and `docs/guides/adding-a-new-language.md` exists" and cites no commit. The header (`:34-35`, `:73`) records that this was RESOLVED while the README promised a CSV the loader rejected; the row itself should cite `b5772e87` and the CSV parser.
- **Draft reply:**

> Thanks for the detail; the documentation was wrong in two ways.
>
> The old README example without `--input` never worked: `build_langpack.py` needs either `--input <word list>` or `--dict <.bin>`. And the README promised a `word,frequency` CSV that the loader did not actually parse (it kept "alma,120" as one word). Both are fixed on main (`b5772e87`): `build_dictionary.py`, and `build_langpack.py --input`, accept one entry per line as `word`, `word,frequency`, `word<TAB>frequency` or `word frequency`, and the README, the wiki page and `docs/guides/adding-a-new-language.md` now say so.
>
> Your 73-word result is explained by the format: the input is a word list, not prose. Nothing tokenises text for you, so a scraped page gives one entry per line, and your 73 lines became 73 entries.
>
> For Hungarian the quickest path is wordfreq, which supports `hu`:
>
> ```
> cd scripts
> pip install wordfreq
> python3 get_wordlist.py --lang hu --output hu_words.txt --count 50000
> python3 build_langpack.py --lang hu --name "Hungarian" --input hu_words.txt --use-wordfreq --output langpack-hu.zip
> ```
>
> Then Settings → Multi-Language → Import Pack. If you have your own list, put it in one of the formats above and use the same `--input`.
>
> I'll close this now that the documentation matches the scripts. Reopen if a step in the guide does not work as written.

- **APPROVE? [ ]**


---

## #96 — [Bug]: Dictionary search resets after adjusting activity

- **Proposed action:** close as completed (at the v2.0.0 tag; the reporter's exact steps are already fixed in v1.5.0, so closing now is defensible — maintainer's call)
- **Released state:** split — toggle/delete path in v1.5.0 (`e46ed8c1`, since v1.3.0); rotation/recreation + scroll position unreleased, will ship in v2.0.0 (`6c97756b`)
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `e46ed8c1` 2026-02-11 fix: resolve #51 #96 #50 + add 57-test issue regression suite — ancestor: yes; tags: v1.3.0, v1.4.0, v1.5.0
  - commits: `6c97756b` 2026-09-05 fix(dict): search context survives Dictionary Manager recreation (#96) — ancestor: yes; tags: none
  - tests: `src/test/kotlin/tribixbite/cleverkeys/IssueRegressionTest.kt#issue 96 — refresh re-applies current search query and sort type` (`:503`), `#issue 96 — refresh with empty search query still preserves sort type` (`:529`), `#issue 96 — refresh does NOT reset to defaults` (`:544`) (pure JVM) — exist: yes
  - tests: `src/test/kotlin/tribixbite/cleverkeys/DictionarySearchStatePersistenceTest.kt` (pure, source-scan drift test, `build.gradle:1085`): `#fragmentSavesSearchContextOnDestruction`, `#fragmentRestoresSearchContextBeforeTheFirstLoad`, `#initialLoadReappliesTheFilterAndCannotClobberIt`, `#scrollPositionIsSavedAndRestoredAfterDataLands`, `#activitySavesAndRestoresItsOwnSearchState`, `#toggleAndDeletePreserveTheActiveFilter` (`:75-170`) — exist: yes. Its KDoc says a Fragment cannot run on the JVM, so the wiring is pinned by scanning source; the sort/filter logic runs for real in `DictionarySortOrderTest`
  - source: `src/main/kotlin/tribixbite/cleverkeys/WordListFragment.kt:113-119` restores query/sort/layout-manager state; `:131-136` `onSaveInstanceState` saves them; `:196`, `:294`, `:311` toggle and delete re-apply `filter(currentSearchQuery, currentSortType)`; `:647-654` `refresh()` does the same. The "Match" sort chip is `strings.xml:1925` `dict_sort_match`
  - release note: `CHANGELOG.md:522-523` under [2.0.0]
  - device check: not recorded (no rotation/split-screen check of Dictionary Manager in any memory file)
- **Thread notes:** Reporter (1.2.8, Android 16): in Dictionary Manager type "ab", choose Match, toggle a word's switch → the list becomes unfiltered and frequency-sorted; wants the same list at the same scroll position. Only bot comments (welcome, stale 2026-09-20). The reply covers both the toggle path (already released) and the rotation/scroll path (v2.0.0).
- **Audit-doc correction:** none; `docs/audit/gh-issue-resolution.md:128` matches. Worth noting the reporter's exact steps were fixed in v1.3.0, i.e. are in v1.5.0 already.
- **Draft reply:**

> Two separate problems were behind this.
>
> Toggling or deleting a word used to reload the full list and drop your search and sort. That was fixed in 1.3.0 (`e46ed8c1`): after a toggle or delete the list is rebuilt with the same search text and the same sort (Match, in your case), so your exact steps should already behave on 1.5.0.
>
> Rotating or resizing the Dictionary Manager (split screen) still lost the search text, the sort and the scroll position, and sometimes the unfiltered list finished loading after the filtered one and overwrote it. That is fixed in `6c97756b`: search text, sort and scroll position are saved and restored, and the restored filter can no longer be overwritten by the initial load. This part ships in v2.0.0.
>
> To check: type "ab", pick Match, toggle a word, then rotate the device. The same filtered list should come back at the same scroll position both times. I'll close this when v2.0.0 is out. Reopen if either step still resets the list, and say which one.

- **APPROVE? [ ]**


---

## #90 — [Feature]: custom size for keyboard/row

- **Proposed action:** close as completed (at the v2.0.0 tag) — maintainer decision noted below: the reporter's reframed ask (the `bottom_row="false"` stretching bug) is fixed; an absolute per-layout height in XML that overrides the Settings keyboard height is not built.
- **Released state:** unreleased, will ship in v2.0.0 (`ba5bbee6`)
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `ba5bbee6` 2026-10-02 fix(layout): preserve compact custom row heights (gh #90) — ancestor of HEAD: yes; tags: none. No other commit mentions #90 apart from a docs plan.
  - tests: `src/test/kotlin/tribixbite/cleverkeys/NumpadKeySizeTest.kt` (mock; `build.gradle` mockTestClasses:1535; 12 @Test) — exists at HEAD: yes. Relevant methods: `#customSingleRow_keepsNormalRowHeightWithNumpadScalingEnabled`, `#customRowHeightAndShift_keepTheirAuthoredProportions`, `#numpadHeightFlag_defaultsOffAndOnlyExplicitTrueOptsIn`, `#numericAndPinLayouts_scaleToConfiguredHeightOnlyWhenEnabled`, `#rowAndKeyTransformations_preserveTheSourceHeightPolicy`.
  - source: `KeyboardData.kt:555` — `numpad_height` parsed from the `<keyboard>` tag, default false; `Theme.kt:315-322` — rows are divided into the configured keyboard height (`keysHeight` divisor) only when Appearance → "Scale numpad height" is on AND `layout.numpad_height`; otherwise the normal 3.95-row unit, capped at screen height. `KeyboardData.kt:252` — per-row `height` multiplier (and `shift`) still honoured. `numeric.xml:2`, `pin.xml:4` opt in explicitly. Spec: `docs/specs/layout-system.md:218-236`. Appearance switch label "Scale numpad height" (`strings.xml:570`, `AppearanceSection.kt:89-96`).
  - device check: not recorded as a functional check. A minified APK with the fix was installed on the Seeker on 2026-10-03 (`memory/todo.md:671-679`); `memory/todo.md:766` still lists "compact and numeric/PIN height (#90)" as owed and Open-now item 9 (`memory/todo.md:124`) assigns it to the test phones before the tag.
- **Thread notes:** Original ask: per-keyboard / per-row size in the XML, or an option to let the XML heights override the Settings keyboard height, because one-row layouts should take only a small strip at the bottom. The reporter's edit reframes it: it used to work and broke only with `bottom_row="false"`. Hubert21 bumped it in September to keep it open. The reply explains the root cause (the `bottom_row="false"` proxy from the #58 numpad scaling), the fix, how to size rows now, how to opt a custom numpad back into stretching, and says plainly that an absolute XML height override is not built.
- **Audit-doc correction:** none (row 129 and lower item 1 are accurate; the device check is still owed, as both say).
- **Draft reply:**

> The stretching you saw was a bug, and it is fixed in `ba5bbee6`; it will ship in v2.0.0.
>
> What happened: since the numpad-scaling change (#58), a layout with `bottom_row="false"` was taken as a numeric keypad, and keypads are stretched to fill the whole configured keyboard height. A one-row custom layout therefore filled the full height instead of being one normal row. That proxy is gone. A layout now opts into the stretch only with an explicit `numpad_height="true"` on the `<keyboard>` tag, which the built-in numeric and PIN layouts carry; everything else keeps the normal row height, so a one-row board is one row high again, and the `height` attribute on a `<row>` (for example `height="0.8"`) still scales that row relative to the others. The Appearance → Scale numpad height switch only affects layouts that opt in.
>
> What is not built: an absolute height in the XML that overrides the keyboard height set in Appearance. Row heights are relative multipliers of the one row unit, which comes from that setting. If you still want an absolute override after trying the fixed build, say so here and I'll keep that part open as its own request.
>
> To check after updating: load your one-row `bottom_row="false"` layout. It should sit at the bottom at the height of a single normal row. I'll close this when v2.0.0 is out; reopen if it still stretches.

- **APPROVE? [ ]**




---

## #83 — [Bug]: "keys per direction" not used when doing an average length swipe

- **Proposed action:** close as by design (now), pointing to the Short Swipes Only switch from #147/#87
- **Released state:** behaviour unchanged since v1.2.5 and in v1.5.0; the boundary pin `47969359` (2026-09-03, with #171) and the Short Swipes Only mode (`918dc322`) are unreleased, will ship in v2.0.0.
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `47969359` 2026-09-03 fix(gestures): custom short-swipe mappings shadow defaults at every fuzzed bin (#171) — ancestor of HEAD: yes; tags: none (adds `PointersShortSwipeCustomOverrideTest`). `918dc322` 2026-10-20 — ancestor: yes; tags: none. `427069bc` 2025-12-09 (slider became percent of key diagonal, max 200 %) — in v1.0.x.
  - tests: `src/test/kotlin/tribixbite/cleverkeys/PointersShortSwipeCustomOverrideTest.kt#beyondMaxBoundary_swipeTypingDisabled_tapsStartingKey` (mock; `build.gradle` mockTestClasses:1262) — exists at HEAD: yes. `PointersShortSwipesOnlyTest.kt#off_beyondTheBoundary_swipeTypingOff_tapsTheStartingKey`, `#off_insideTheBoundary_isTheSubkey` (mock) — yes.
  - source: `Config.kt:992` — `short_gesture_max_distance` IS the short/long boundary, 50..200 % of the key diagonal (`Config.kt:644`); the old "200=disabled" label was never implemented and is retired. `Pointers.kt:1023-1034` — once the finger is more than Max Distance from the touch-down point the gesture is marked as having left the key; `Pointers.kt:487-488` — the short-swipe branch requires `short_gestures_enabled` and (not left the key OR `allowLeftKey` OR Short Swipes Only); `Pointers.kt:474` — `allowLeftKey = !isCharKey`, so non-character keys (switch layout, backspace, voice, settings, …) may leave the key, which is exactly the commenter's "special keys work"; `Pointers.kt:514` — inside the boundary the subkey fires. With "Enable Short Gestures" off nothing in that branch runs, which matches the reporter's "disabling short gestures prevents commands per direction".
  - device check: not applicable (by design); the Short Swipes Only mode was device-checked on the Saga 2026-10-20 (`memory/todo.md:210`).
- **Thread notes:** Reporter (v1.2.5, dictionary disabled): direction commands work only in a narrow band — too short does nothing, "slightly too long" (beyond ~100 px) is missed, and the configured threshold seems ignored; turning short gestures off kills them entirely. Hubert21: special keys (switch keyboard, letters, voice, settings) are not affected; custom keys are. The reply explains the one boundary, why non-character keys differ, confirms the short-gestures switch is a prerequisite, and points to the new mode that removes the boundary.
- **Audit-doc correction:** row 130 (`docs/audit/gh-issue-resolution.md:130`) cites `Config.kt:712` for the retired label; it is at `Config.kt:992` at HEAD. Substance is correct.
- **Draft reply:**

> This is how the gesture boundary works, not a lost setting, so I'll close it as by design and point you at the fix that makes it moot.
>
> A short swipe on a letter key counts only while your finger stays within Max Distance (Settings → Gesture Tuning, a percentage of the key diagonal, 50 to 200 %) of where it touched down. Past that it is a word swipe, or a plain tap of the starting key when swipe typing is off, which is the "slightly too long is missed" you saw. Below Min Distance it is a tap. The two sliders are the only band, and Enable Short Gestures has to be on, as you found. There was once a "200 = disabled" label on the slider; that was never implemented and has been removed.
>
> @Hubert21, the special keys behave differently on purpose: a long swipe on a non-letter key (switch layout, backspace, voice, settings) cannot be a word, so those keys are allowed to leave the key and still fire the direction. Letter keys, including custom letter keys, stop at the boundary because beyond it the gesture belongs to swipe typing.
>
> If you want one swipe per direction with no boundary at all, v2.0.0 adds Settings → Gesture Tuning → Short Swipes Only: every swipe past the minimum types the subkey in its direction, however long, and word swipes are off while it is on. That is tracked in #147 and #87; reopen here if the mode does not cover your case.

- **APPROVE? [ ]**




---

## #77 — [Bug]: Cannot completely disable Greek/Math toggle on Numeric Layer (Custom XML)

- **Proposed action:** close as completed (at the v2.0.0 tag — unreleased). Maintainer decision flagged: the checkbox defaults OFF, so in v2.0.0 the Greek/Math key disappears from the ?123 pane for every install that never ticked it.
- **Released state:** unreleased, will ship in v2.0.0 (`e2b64d32`, `9bb88bfe`); the partial Fn-only gate `b3354e8a` is in v1.2.8+ / v1.5.0
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `b3354e8a` 2026-01-22 fix: respect Greek/Math disabled setting in numeric layer (#77) — ancestor: yes; tags: v1.2.8 … v1.5.0. Only gates Fn + ?123 → Greek/Math in `KeyModifier.kt`; the key on the pane stayed
  - commits: `e2b64d32` 2026-09-05 fix(layout): the numeric pane's Greek/Math toggle honors its Extra Keys checkbox (#77) — ancestor: yes; tags: none
  - commits: `9bb88bfe` 2026-09-07 fix(view): H-1 double layout switch; H-7 missing haptic; H-8 blank pane cell — ancestor: yes; tags: none. H-8 is the #77 residual: the stripped `loc switch_greekmath` cell was drawn as an empty key frame
  - tests: `src/test/kotlin/tribixbite/cleverkeys/NumericPaneGreekmathRemovalTest.kt` (mock tier, `build.gradle:1365`): `#numericPane_declaresGreekmathAsLoc`, `#modifyNumpad_consultsThePaneLocStrip`, `#locGreekmath_isStrippedWhenNotEnabled`, `#locGreekmath_isKeptWhenEnabled`, `#nonLocKeys_areNeverStripped`, `#ungovernedLocKeys_keepLegacyPaneBehaviour`, `#otherGovernedLocPaneKeys_followTheirCheckbox`, `#greekmathCheckbox_staysDefaultOff` (`:44-158`) — exist: yes. `ReleaseClaimKeyModifierTest` (mock, `99272f8d`) pins the Fn gate
  - source: `src/main/layouts/numeric.xml:22-24` `<key width="0.75" key0="loc switch_greekmath"/>`; `src/main/kotlin/tribixbite/cleverkeys/LayoutModifier.kt:160-178` `paneLocKeyStripped` (strip iff the loc key has an Extra Keys checkbox and is not enabled), called at `:198`; `prefs/ExtraKeysPreference.kt:105` `switch_greekmath` in `EXTRA_KEYS`, `:195` group "Layout Switching", `:399` title "Greek/Math"; `defaultChecked("switch_greekmath")` is false (test `:151-158`)
  - settings path: "Input Behavior" → "Configure Extra Keys" (`ui/settings/sections/InputBehaviorSection.kt:56`) → group "Layout Switching" (`strings.xml:1948`) → "Greek/Math" (`strings.xml:2150`)
  - release note: `CHANGELOG.md:520` under [2.0.0]
  - device check: not recorded (no commit body or memory note records a ?123-pane screenshot with the box off)
- **Thread notes:** Reporter (1.2.5 debug, custom XML with `bottom_row="false"` for Termux) tried (1) leaving `switch_greekmath` out of the custom XML, (2) `locale_extra_keys="false"`, (3) unticking Greek/Math in Extra Keys Configuration; the πλ∇¬ key stayed on the ?123 pane. Only the bot commented. The reply explains why (1) and (2) cannot work (the pane is a shipped layout), corrects the 1.2.8 claim, and gives the control. The LayoutModifier KDoc (`:161-165`) notes the v1.2.8 release note "claimed" the checkbox governed it.
- **Audit-doc correction:** `docs/audit/gh-issue-resolution.md:132` cites only `e2b64d32`; the residual `9bb88bfe` (blank cell) is part of the fix and the earlier partial `b3354e8a` (v1.2.8) is what the thread-era release note oversold.
- **Draft reply:**

> The ?123 pane is a built-in layout, so your custom XML cannot remove its keys, and `locale_extra_keys="false"` only affects the text layout. The Extra Keys checkbox was meant to be the control, but it was not: the 1.2.8 change (`b3354e8a`) only gated the Fn shortcut into Greek/Math, while the key on the pane itself ignored the setting. I implied back then that the checkbox governed it; that was wrong.
>
> Fixed in `e2b64d32` (plus `9bb88bfe`, which removes the blank cell the stripped key left behind). The Greek/Math key on the numeric pane now follows Settings → Input Behavior → Configure Extra Keys → Layout Switching → Greek/Math. It is off by default, so with that box unticked the key is gone from the ?123 pane; tick it to get it back.
>
> This ships in v2.0.0. After updating, open your custom layout, press ?123, and the πλ∇¬ key should be absent. I'll close this when v2.0.0 is out. Reopen if the key is still there with the box unticked.

- **APPROVE? [ ]**


---

## #75 — [Bug]: Swipe behaviour on Swiss French keyboard layout

- **Proposed action:** close as completed (at the v2.0.0 tag — the engine change is unreleased; reporter must retest)
- **Released state:** unreleased, will ship in v2.0.0 (`90b1efe2`, `a7d03bc8`; replay `7747fea5`)
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `90b1efe2` 2026-08-15 feat(ctc): widen layout gate to any Latin layout for English (Dvorak/Colemak get CTC) — ancestor: yes (in HEAD's history of `SwipeEngineRouter.kt`); tags: none
  - commits: `a7d03bc8` 2026-08-18 refactor(swipe): route every swipe to CTC or geometric — neural becomes unreachable — ancestor: yes; tags: none (ADR-011; `docs/plans/2026-08-18-neural-engine-removal.md`)
  - commits: `7747fea5` 2026-09-05 test(ctc): #75 Swiss French QWERTZ replay — the engine follows the displayed layout — ancestor: yes; tags: none
  - commits (false match): `28970f40` "(Bug #75)" is a ComposeKey fix from the pre-GitHub numbering
  - tests: `src/test/kotlin/tribixbite/cleverkeys/swipe/CtcSwissFrenchLayoutReplayTest.kt#issueGestureAndFrenchWordsDecodeOnSwissGeometry` (`:232-262`; pure JVM, registered in `pureTestClasses` `build.gradle:1163`; `assumeOrt()` skips it when ONNX natives are absent) — exists: yes. Asserts yes/zeal/bonjour/merci/oui/jazz decode at rank 1 over `latn_qwertz_fr_ch.xml`'s real key centres and that y-e-s is not a z-word. Also `#swissLayoutStaysServedAndQwertz`, `#swissGeometryBuildsACompleteCtcLayout`. `swipe/SwipeEngineRouterTest.kt:43` `colemak routes ctc in ctc mode` (pure). The traces are synthetic straight lines, not human swipes
  - source: `src/main/kotlin/tribixbite/cleverkeys/swipe/CtcEngineAdapter.kt:518-545` `buildMappedLayout` takes each letter's centre from `KeyboardGeometry.computeKeyRects(keyboard, …)` of the DISPLAYED layout; `swipe/ctc/CtcLayout.kt:3-17` geometry is a model input; `swipe/SwipeEngineRouter.kt:120-135` any layout whose `script` is routable → CTC; `swipe/ctc/CtcScriptSupport.kt:325-326` `isRoutableScript(null)` is false, so a custom layout WITHOUT `script="latin"` goes to the geometric engine, which also builds from displayed rects (`swipe/geometric/LayoutGeometry.kt:166`). `latn_colemak.xml:3` and `latn_qwertz_fr_ch.xml:3` both declare `script="latin"`
  - release timing: `CHANGELOG.md:99` "CTC swipe engine, now the default" is under [2.0.0]; the 1.5.0 section has no CTC entry, so every tagged release still has the QWERTY-bound neural engine and the bug
  - settings path for verification: "Swipe Typing" → "Prediction Engine" (`strings.xml:119-120`)
  - device check: not recorded for Swiss French, Colemak, or a custom layout
- **Thread notes:** Reporter (1.2.1, Pixel 8a, Swiss French QWERTZ): swiping Y-E-S yields "Zeal"; Z-E-S yields "yes"; expects swipe to follow the visible layout. mehimme (Colemak): same, asks whether the net can be retrained for alternative layouts. rubahness: video of a custom alt layout where the offset breaks detection. The reply answers all three, including the `script="latin"` detail for custom XML.
- **Audit-doc correction:** none; `docs/audit/gh-issue-resolution.md:133` ("FIXED-BY-ARCHITECTURE … comment + close on v2.0.0") matches. The row could cite the engine commits (`90b1efe2`, `a7d03bc8`) beside the replay test.
- **Draft reply:**

> You were right about the cause. The old swipe engine assumed QWERTY key positions no matter which layout was on screen, so on Swiss French QWERTZ a swipe over Y-E-S was read as Z-E-S. That engine has been removed. The engine that replaced it takes the positions of the keys actually displayed as an input (`90b1efe2`, `a7d03bc8`), so Swiss French QWERTZ, Colemak, Dvorak and any other Latin layout decode on what you see. Replaying the Y-E-S gesture over the Swiss French key centres now gives "yes", and "zeal", "bonjour", "merci", "oui" and "jazz" come out first on that board as well (`7747fea5`).
>
> @mehimme: no retraining is needed for Colemak. Key geometry is an input to the model, not something baked into it.
>
> @rubahness: for a custom layout, give the `<keyboard>` tag `script="latin"` and it uses the same engine. Without that attribute the keyboard falls back to the geometric engine, which also works from the displayed key positions, so the offset you showed should be gone either way.
>
> This ships in v2.0.0; every released build so far still has the old engine. After updating, check that Settings → Swipe Typing → Prediction Engine says CTC, then swipe Y-E-S on the Swiss French layout. I'll close this when v2.0.0 is out. Reopen with the layout and the word if a swipe still lands on the wrong letters.

- **APPROVE? [ ]**


---

## #71 — [Bug]: Opening clipboard causes the whole device to crash\stop for 2 to 3 sec.

- **Proposed action:** close as completed (now — all three fixes are in v1.5.0; reporter retest invited; an owed check is named below)
- **Released state:** in v1.5.0 (`8cf40104` since v1.2.6; `20ef3006` since v1.4.0; `89d318fc` in v1.5.0)
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `8cf40104` 2026-01-17 fix(clipboard): prevent TransactionTooLargeException with large histories (#71) — ancestor: yes; tags: v1.2.6 … v1.5.0. Body: 100-entry display cap, defaults 50 entries / 256 KB per item / 5 MB total, try-catch in `clearExpiredAndGetHistory()`
  - commits: `20ef3006` 2026-03-18 fix: async clipboard DB loads — eliminate UI thread freeze (#71) — ancestor: yes; tags: v1.4.0, v1.5.0
  - commits: `89d318fc` 2026-05-19 feat(backup): structured JSON-blob diff + 5 more default tunings — ancestor: yes; tags: v1.5.0. This is where the per-item default became 512 KB (the audit row quotes 512 KB without a commit)
  - tests: `src/test/kotlin/tribixbite/cleverkeys/IssueRegressionTest.kt#issue 71 — clipboard max item size is 512KB` (`:103-108`) and four sibling `issue 71` methods `:95-125` (pure JVM; they pin Defaults constants only) — exists at HEAD: yes
  - tests: `src/androidTest/kotlin/tribixbite/cleverkeys/ClipboardHistoryTest.kt#testHistoryLimitedTo100Entries`, `#testLargeClipboardDoesNotCrash` (`:125-155`, instrumented) — exist: yes, but WEAK: they assert `history.size <= 100` while production no longer caps at 100 (`ClipboardHistoryService.kt:312-320` returns every entry unless the user set History Limit), so the assertion passes vacuously on a small test DB. The audit's `BucketBSourceContractTest.kt:168+` lead did not pan out (no 512/clipboard-cap assertion found there)
  - source: `src/main/kotlin/tribixbite/cleverkeys/Config.kt:308-309` defaults `CLIPBOARD_MAX_ITEM_SIZE_KB = "512"`; `:578` range 64..1024 KB; `clipboard/ClipboardHistoryService.kt:434-449` rejects an oversize clip with a toast and `return`s without storing (`strings.xml:105` "Clipboard item too large (%1$d KB). Limit is %2$d KB."); `clipboard/ClipboardHistoryView.kt:1355-1356` history load runs in `withContext(Dispatchers.IO)`; `:486` `ITEMS_PER_PAGE = 100`, `:757` `getTotalPages()`
  - settings UI: "Clipboard" section → "Max Item Size" (`ui/settings/sections/ClipboardSection.kt:177`, `strings.xml:661`), "History Limit" (`:654`), "Size Limit" (`:657`)
  - per-entry delete button exists in v1.5.0 (`git show v1.5.0:res/layout/clipboard_history_entry.xml` has `clipboard_entry_delete`; added `3c26107c` 2026-01-15). Batch "Select → Select all matching results → Delete selected" (`strings.xml:2799-2805`) is #175 part 2, UNRELEASED
  - device check: not recorded. `memory/HANDOFF.md:285` lists #71 as "ALREADY-FIXED (recommend closing after reporter retest)". Owed: replay the reporter's recipe (several 500 KB clips, then open the pane; one 798 KB clip, expect the toast) on a test phone running the v1.5.0 or v2.0.0 build
- **Thread notes:** Reporter (v1.2.5, realme RMX3870, Android 15): copying a 2.2 MB HTML via `termux-clipboard-set` failed with `TransactionTooLargeException` (that trace is Termux:API's `setPrimaryClip`, not CleverKeys); after splitting into 500 KB chunks and copying each, opening the CleverKeys clipboard stalled the whole device 3–5 s and the pane never opened. Follow-ups: HeliBoard handled the same; the stall persisted until app data was cleared, so they ask for a "clipboard cleaner"; on 2026-01-24 a 798 KB clip still reproduced it (still v1.2.x; `8cf40104` landed in 1.2.6 on 01-17, and the async load only in 1.4.0). The reply explains the Termux trace, names the three fixes, the settings, the delete controls, and asks for a retest on 1.5.0.
- **Audit-doc correction:** `docs/audit/gh-issue-resolution.md:134` ("PINNED-era … 512 KB cap") is right on substance; it lacks the commit for the 512 KB default (`89d318fc`), and the instrumented "100-entry" pins it leans on are stale (see tests above).
- **Draft reply:**

> Three things were going on here, and the fixes for all of them are in 1.5.0.
>
> The `TransactionTooLargeException` in your log is thrown by Termux:API's own `termux-clipboard-set` when a clip exceeds Android's binder limit; the keyboard never received that clip. What CleverKeys got wrong was the panel: on 1.2.x it read the whole history on the UI thread while opening, so a history holding several 500 KB entries stalled the screen.
>
> Since then: the history loads in the background and the list is paged at 100 entries (`20ef3006`, 1.4.0); each entry has a size cap, 512 KB by default, and an oversize clip is refused with the toast "Clipboard item too large (N KB). Limit is 512 KB." instead of being stored (`8cf40104`, `89d318fc`). The cap is under Settings → Clipboard → Max Item Size (64 KB to 1 MB); History Limit and Size Limit bound the store.
>
> On a "clipboard cleaner": once the panel opens, every row has a delete button, so wiping app data should not be needed again. v2.0.0 adds Select → Select all matching results → Delete selected.
>
> If you can, retest on 1.5.0 with the same file: copy the 798 KB chunk (you should get the toast and nothing stored), copy a few 500 KB chunks, then open the panel. I'll close this as fixed in 1.5.0; reopen with your version and the item size if it still stalls.

- **APPROVE? [ ]**


---

## #67 — Script error

- **Proposed action:** close as completed (now — released since v1.2.6; the reporter's actual failure was a lone copy of the script)
- **Released state:** in v1.5.0 (`886f0b11`, in since v1.2.6)
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `886f0b11` 2026-01-16 fix: add helper script validation to build_all_languages.py — ancestor of HEAD: yes; tags: v1.2.6 … v1.5.0
  - commits (false matches to ignore): `f11b1c5e` "(#67)" is a KeyboardShapes theme commit from the pre-GitHub numbering; `IssueRegressionTest` "issue 67 — clipboard history enabled by default" is likewise not this issue
  - tests: none (Python script; nothing pins it)
  - source: `scripts/build_all_languages.py:50` `SCRIPT_DIR = Path(__file__).parent.resolve()`; `:56-62` `REQUIRED_SCRIPTS` (build_wordlist.py, build_dictionary.py, generate_unigrams.py, build_langpack.py, compute_prefix_boosts.py); `:125-127` prints "required helper scripts not found in <dir>: <names>" and exits before any work; every helper call goes through `SCRIPT_DIR /` (`:142,:164,:174,:215,:221`). `get_wordlist.py` is no longer in the orchestrated pipeline (configured languages run `build_wordlist.py`); it still exists at `scripts/get_wordlist.py` for the raw wordfreq path
  - root cause re-read: the script the reporter ran (`886f0b11^`, lines 41 and 77) ALREADY resolved `SCRIPT_DIR / 'get_wordlist.py'` relative to the script file, not the CWD. The error path `/storage/emulated/0/Download/get_wordlist.py` therefore means `build_all_languages.py` itself had been copied to Downloads alone, without its sibling helpers. The maintainer's on-thread diagnosis ("looking in your current working directory") was wrong on the mechanism; the "run from a full checkout" advice was right. `886f0b11` turns the mid-run crash into an immediate, named error
  - device check: n/a (desktop script); not run locally for this draft beyond reading the code
- **Thread notes:** Reporter ran `python3 build_all_languages.py --lang fr` and got "can't open file '/storage/emulated/0/Download/get_wordlist.py'"; says they searched the git tree for `get_wordlist.py` without success (it is at `scripts/get_wordlist.py`; the maintainer linked it). Maintainer replied twice (link, then the CWD explanation + two run options). Stale-bot warning 2026-03-22. Reopened by the maintainer 2026-09-21 for a manual close. The reply corrects the mechanism, restates the fix, and gives the current command.
- **Audit-doc correction:** `docs/audit/gh-issue-resolution.md:135` credits the fix to `SCRIPT_DIR = Path(__file__).parent.resolve()`; the `.resolve()` was not what the reporter was missing (the path was already script-relative). The meaningful change in `886f0b11` is the up-front missing-helper check, and the user-side cause was a single downloaded file. The row's status (FIXED, close) still stands.
- **Draft reply:**

> I said earlier that the script looks in your current working directory. That was not quite right: it looks in the folder that `build_all_languages.py` itself lives in. Your error path (`/storage/emulated/0/Download/get_wordlist.py`) shows the script had been copied to Downloads on its own, so its helper scripts were not beside it. They do exist in the repo under `scripts/`, `get_wordlist.py` included.
>
> What changed: since 1.2.6 (`886f0b11`) the script checks for its helpers before doing anything and prints which ones are missing and where it expected them, instead of failing part way through a build. The pipeline has also moved on: `build_all_languages.py` now drives `build_wordlist.py` for the configured languages (French is one), and `get_wordlist.py` is only used for the raw wordfreq path.
>
> To run it: clone the whole repository (or copy the entire `scripts/` folder, not one file), `pip install wordfreq`, then from the repo root run `python3 scripts/build_all_languages.py --lang fr`. The directory you run it from does not matter as long as the folder is intact.
>
> I'll close this. Reopen if a full clone still fails and paste the first error line.

- **APPROVE? [ ]**


---

## #35 — [Bug]: Overly dark darkmode

- **Proposed action:** close as completed (now — the whole fix is in v1.5.0)
- **Released state:** in v1.5.0 (`90c929d1` since v1.2.6; `cc6a0b6b`, `42b09104` in v1.5.0)
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `90c929d1` 2026-01-16 feat: settings follow system theme + Intent automation (#35, #70) — ancestor of HEAD: yes; tags: v1.2.6 … v1.5.0
  - commits: `cc6a0b6b` 2026-07-13 fix(settings): remaining forced-dark activities follow system day/night (#35) — ancestor: yes; tags: v1.5.0
  - commits: `42b09104` 2026-07-15 fix(theming): Dictionary Manager follows system day/night (UT-1) — ancestor: yes; tags: v1.5.0 (not in the audit row; it is the last forced-dark screen)
  - tests: none (visual-only change; no pinning test exists)
  - source: `res/values/styles.xml:39` — `appTheme` parent is `Theme.AppCompat.DayNight.NoActionBar`; `:45` launcherTheme is `Theme.Material3.DayNight.NoActionBar`
  - source: `src/main/kotlin/tribixbite/cleverkeys/theme/KeyboardTheme.kt:32` and `theme/CleverKeysTheme.kt:43` — `darkTheme: Boolean = isSystemInDarkTheme()`; `activities/ThemeSettingsActivity.kt:144`, `activities/LauncherActivity.kt:287` branch on `isSystemInDarkTheme()`; no activity forces `darkColorScheme()` (the two hits in `theme/MaterialThemeManager.kt:273/311` are the light/dark halves of one builder)
  - source for the reporter's second ask: `ui/settings/sections/AppearanceSection.kt:184` "Label Brightness" (default 100, `Config.kt:31`); `ui/settings/sections/VersionActionsSection.kt:21-39` "Reset All" button under the "Information & Actions" section (`strings.xml:180,184,187-188`)
  - release note: `CHANGELOG.md:603-604` (1.5.0 "System light theme … (#35)")
  - device check: not recorded (no commit body or memory file records a light-mode screen check; `memory/HANDOFF.md:285` lists #35 as "recommend closing after reporter retest")
- **Thread notes:** Reporter (Android 14 tablet, version unknown) asks (1) that the settings app default to following the system light/dark setting, and (2) how to undo a setting they changed by accident while scrolling ("key label brightness or something"). No other commenters besides the bot. The reply answers both.
- **Audit-doc correction:** none in substance; `docs/audit/gh-issue-resolution.md:136` cites `90c929d1`/`cc6a0b6b`/ARC-111 and could add `42b09104` (Dictionary Manager, the last screen).
- **Draft reply:**

> The settings screens now follow the system light/dark setting. The first pass went out in 1.2.6 (`90c929d1`); the screens that were still forced dark (Theme Settings, Short Swipe Customization, Extra Keys, Dictionary Manager) were fixed in 1.5.0 (`cc6a0b6b`, `42b09104`). With system dark mode off, every CleverKeys settings screen opens in light mode. There is no separate in-app switch; it mirrors the system.
>
> On the setting you changed by accident: the slider you most likely hit is Settings → Appearance → Label Brightness. Its default is 100%. If you would rather start clean, Settings → Information & Actions → Reset All puts every setting back to its default (it asks before doing so).
>
> To check after updating to 1.5.0: set your tablet to light mode and open CleverKeys from the app drawer; the settings should come up light.
>
> I'll close this as fixed in 1.5.0. Reopen if any screen still comes up dark with system dark mode off, and say which screen.

- **APPROVE? [ ]**


---


## Features — close candidates

## #187 — [Feature]:

(GitHub title is literally "[Feature]: "; the body asks for macron-below and ring-below dead keys under Settings → Configure extra keys → Accents, for ISO 15919 transliteration — ṟ, ḻ, r̥.)

- **Proposed action:** close as completed (at the v2.0.0 tag)
- **Released state:** unreleased, will ship in v2.0.0 (`30f205d9`)
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `30f205d9` 2026-10-20 feat(accents): macron-below and ring-below dead keys (#187) — ancestor of HEAD: yes; tags: none. Device-pass docs: `80286534` 2026-10-20 — ancestor: yes.
  - tests: `src/test/kotlin/tribixbite/cleverkeys/DeadKeyMarksBelowTest.kt` (mock, loads the real compose data; `build.gradle` mockTestClasses:1269; 11 @Test) — exists at HEAD: yes. `#ringBelowTypesVocalicRAndLWithTheCombiningMark`, `#macronBelowComposesEveryPrecomposedLetterAndItsCapital`, `#hWithLineBelowHasNoPrecomposedCapitalSoShiftUsesTheCombiningMark`, `#macronBelowFallsBackToTheCombiningMarkOnOtherLetters`, `#allFourKeysAreOfferedAsExtraKeysInTheirCategories`, `#allFourKeysAreCommandPaletteCommands`. `src/androidTest/kotlin/tribixbite/cleverkeys/ComposeKeySequenceInstrumentedTest.kt#marksBelowTablesResolve` (instrumented) — exists: yes; ran and passed in the 2026-10-20 emulator.wtf run (`~/ew-output/oct20-merged/results.xml:523`, 1,607 tests, 0 failures; `30f205d9` is an ancestor of that run's `4f70eb7e`).
  - source: `KeyValue.kt:86-87` `Modifier.MACRON_BELOW` / `RING_BELOW`; `:582-583` dead keys `accent_macron_below` / `accent_ring_below` with text labels ◌̱ / ◌̥; `:614-615` `combining_macron_below` / `combining_ring_below` (U+0331 / U+0325). Tables: `srcs/compose/accent_macron_below.json` (b d h k l n r t z → ḇ ḏ ẖ ḵ ḻ ṉ ṟ ṯ ẕ), `srcs/compose/accent_ring_below.json` (a → ḁ), `srcs/compose/shift.json:62` (ẖ → H + U+0331); every other letter gets letter + combining mark via `KeyModifier.kt:261` `applyComposeOrCombiningOnLetter`, which is how r̥ and l̥ type. Extra Keys: `prefs/ExtraKeysPreference.kt:86-87` (Accents group, `:301`), `:157-158` (Combining Characters group, `:309`), labels "Macron Below" / "Ring Below" (`strings.xml:2175-2176`); palette entries `strings.xml:2509-2512`, `:2563-2566`. `src/main/layouts/latn_bone.xml:45` no longer says "not implemented yet" for macron below (lines 44 and 72 still say so for rough/smooth breathing, unrelated to this issue).
  - device check: Saga 2026-10-20 17:07 (`memory/todo.md:236-239`): both keys enabled in Extra Keys, labels rendered on the a/s corners, Macron Below then `t` typed ṯ (table) and Ring Below then `y` typed y̥ (combining fallback); keys unticked afterwards.
- **Thread notes:** Reporter wants two tick boxes under Accents for macron below and ring below, for ISO 15919 Dravidian/Indic transliteration; currently places ṟ ḻ r̥ on key corners by hand and fears running out of room. No comments. The reply names the two boxes, the exact letters covered by precomposed forms, how r̥/l̥ come out (combining mark), capitals, and the separate bare combining keys.
- **Audit-doc correction:** `docs/audit/gh-issue-resolution.md:142` says `ComposeKeySequenceInstrumentedTest#marksBelowTablesResolve` is "not yet run on a device" — it ran and passed in the 2026-10-20 emulator.wtf run above, and the Saga check is recorded. Lower section item 11 (`:416`) still lists #187 as open work, and the summary table (`:84`) still lists #187 under "Still open" — both stale against row 142.
- **Draft reply:**

> Both dead keys are added in `30f205d9` and will ship in v2.0.0.
>
> Under Settings → Input Behavior → Configure Extra Keys you will find Macron Below and Ring Below in the Accents group, as tick boxes next to the existing accents. Tap the dead key, then the letter. Macron Below gives ḇ ḏ ẖ ḵ ḻ ṉ ṟ ṯ ẕ, so ṟ and ḻ come straight out; Ring Below gives ḁ. Unicode has no precomposed r̥ or l̥, so for those (and any other letter without a precomposed form) the key adds the combining mark after the letter, which is what ISO 15919 text normally uses anyway. Shift works for the capitals (Ṟ Ḻ Ṯ, and R̥ with the combining mark).
>
> If you would rather type the letter first, the Combining Characters group has the bare marks as well (Combining Macron Below, Combining Ring Below). All four are also in the command palette for custom short swipes.
>
> To check after updating: tick Macron Below, then on the keyboard tap it and `r`; you should get ṟ. Ring Below then `r` should give r̥. I'll close this when v2.0.0 is out; reopen if a letter you need comes out wrong.

- **APPROVE? [ ]**




---

## #168 — [Feature]: clear clipboard key

- **Proposed action:** close as completed (at the v2.0.0 tag)
- **Released state:** unreleased, will ship in v2.0.0 (`ef12ad20`)
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `ef12ad20` 2026-10-05 feat(clipboard): add clear system clipboard command — ancestor of HEAD: yes; tags: none. Kept through `7d1a3271` 2026-10-12 refactor(editing): one implementation for every Editing command — ancestor: yes; tags: none. No commit message cites #168 (only `2d671d5d`, a docs commit).
  - tests: `src/test/kotlin/tribixbite/cleverkeys/clipboard/ClipboardServiceHygieneTest.kt#explicitClearUsesNativeApiWithoutReadingOrWritingHistory` and `#explicitClearReportsFailureWhenProfileRestrictsIt` (mock; `build.gradle` mockTestClasses:1348) — exist at HEAD: yes. `src/test/kotlin/tribixbite/cleverkeys/customization/PrivateCopyCommandTest.kt#clear system clipboard is an opt-in registry command distinct from field clear` (pure; :848) — yes. `EditingCommandParityTest.kt:98,420` routes `CLEAR_CLIPBOARD` (mock; :1511) — yes. `a11y/KeyLabelsTest.kt` (pure; :851) — yes. Instrumented: `ClipboardMediaDatabaseTest`, `ExtraKeysConfigActivityComposeTest` reference it.
  - source: `KeyValue.kt:113`, `:791` — `"clear_clipboard" -> editingKey("⌧", Editing.CLEAR_CLIPBOARD)`; `KeyEventHandler.kt:877-881` — handled even while an inline clipboard field owns routing, shows the result in the bar; `clipboard/ClipboardHistoryService.kt:1120-1143` — `clearPrimaryClip()` on API 28+, empty-text clip on 21-27, never reads the clip or touches saved history; `prefs/ExtraKeysPreference.kt:114` + `:198` (Editing group), `:238`, `:380`; `customization/CommandRegistry.kt:93` (palette, Clipboard category). Labels: "Clear system clipboard" / "Clear Android's current clipboard; saved history, pins and todos stay." (`strings.xml:2848-2849`); feedback "System clipboard cleared" / "Could not clear system clipboard" (`:2850-2851`). Path: Settings → Input Behavior → Configure Extra Keys (`strings.xml:1140`, `InputBehaviorSection.kt:56`; also under Activities, `:451`) → Editing group (`:1951`).
  - device check: Seeker 2026-10-06 (`memory/todo.md:1100-1104`): command visible under Extra Keys search and palette with the right label; the clearing itself ran as an instrumented test on an emulator.wtf Pixel 7 / API 34 run (`memory/todo.md:1098-1099`). A clear on a physical phone with a disposable clip is still owed (Open-now item 9, `memory/todo.md:124-125`).
- **Thread notes:** Single ask, no comments: a key to clear the current clipboard "when managing clipboard". The reply gives the key, where to enable it, what it does and does not touch, and the API 21-27 caveat.
- **Audit-doc correction:** row 145 (`docs/audit/gh-issue-resolution.md:145`) does not cite the fix commit; add `ef12ad20`. Otherwise accurate (device check owed).
- **Draft reply:**

> Added in `ef12ad20`; it will ship in v2.0.0.
>
> It is an opt-in key called "Clear system clipboard". Turn it on under Settings → Input Behavior → Configure Extra Keys, in the Editing group, and it appears on the keyboard; it is also in the command palette and can be put on a custom short swipe. Tapping it clears Android's current clipboard only. Your saved clipboard history, pinned entries and todos in CleverKeys are not touched, and the key never reads the clip. The suggestion bar shows "System clipboard cleared" (or "Could not clear system clipboard" if the system refuses, for example on a managed profile).
>
> One caveat: Android only gained a real clear call in Android 9. On Android 5 to 8.1 the key replaces the clip with an empty text item instead, which is the closest the platform allows.
>
> To check after updating: copy something, tap the key, then long-press a text field. Paste should offer nothing (or an empty item on old Android), and the clipboard panel's history should still have the entry. I'll close this when v2.0.0 is out; reopen if it behaves differently.

- **APPROVE? [ ]**




---

## #147 — [Feature]: Option to remove long Swipe Gestures

- **Proposed action:** close as completed (at the v2.0.0 tag)
- **Released state:** unreleased, will ship in v2.0.0 (`c93be97b`, `918dc322`)
- **Evidence (verified at HEAD ac86bda5):**
  - commits: same as #87 — `c93be97b` 2026-10-20 and `918dc322` 2026-10-20, both ancestors of HEAD, no `v*` tag.
  - tests: `src/test/kotlin/tribixbite/cleverkeys/PointersShortSwipesOnlyTest.kt` (mock; 10 @Test) — exists at HEAD: yes. `#on_wordShapedLongGesture_isNeverAWordSwipe`, `#on_longSwipeTowardAnEmptyDirection_typesNothing`, `#on_keyWithoutAnySubkey_keepsItsTap`, `#on_longDisplacement_firesTheCustomMappingForItsDirection`.
  - source: as #87 — `Config.kt:1332-1334`, `Pointers.kt:486-488`, `:514`, `:370`, `:1131`; the boundary tracker the commenter describes is `Pointers.kt:1023-1034` (moving more than Max Distance from the touch-down point marks the gesture as having left the key).
  - device check: Saga 2026-10-20 15:44 (`memory/todo.md:210-213`); see #87.
- **Thread notes:** poyribn wants long swipe gestures gone entirely so short swipes cannot overshoot into them; says no Max Distance value fixed it; uses special characters a lot. hiepoggy123: a short swipe only registers while it stays inside the key, 1 in 10 success. Both are answered by the new switch; the reply also confirms hiepoggy123's description of the boundary mechanism.
- **Audit-doc correction:** see #87 (lower item 8 stale on the device check). Row 149 is accurate.
- **Draft reply:**

> You can now turn long swipes off. It is in `918dc322` (with `c93be97b`) and will ship in v2.0.0.
>
> Settings → Gesture Tuning → Short Swipes Only. With it on there is only one swipe per direction: any swipe that reaches the minimum distance types the subkey in that direction, however far it goes, and nothing becomes a word swipe. A swipe toward a direction with no subkey types nothing, and a key with no subkeys keeps its tap. Word swipe typing is off while the switch is on (its switch and the Max Distance slider show as disabled with a note), and comes back when you turn it off. The switch needs Enable Short Gestures on.
>
> @hiepoggy123, what you observed is how it works without the switch: a short swipe counts only while the finger stays within Max Distance of where it touched down; beyond that it becomes a word swipe, or a plain tap when swipe typing is off. The new mode removes that boundary.
>
> To check after updating: turn the switch on and swipe from any key right across the keyboard toward a subkey. It should type that subkey every time. I'll close this when v2.0.0 is out; reopen if a long swipe still does anything other than the subkey.

- **APPROVE? [ ]**




---

## #135 — [Feature]: Add `clear` action in additon to delete word

- **Proposed action:** close as completed (now).
- **Released state:** in v1.5.0 (`c4778d9d`); the terminal/blind-field refusal is unreleased
  (2026-10-13 change, will ship in v2.0.0).
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `c4778d9d` 2026-04-26 feat: #135 clear command + #133 sublabel size factor constant —
    ancestor of HEAD: yes; tags: v1.5.0 (earliest).
  - tests: `src/test/.../EditingCommandParityTest.kt:193,421` (mock, build.gradle:1511);
    `src/test/.../BlindFieldPolicyTest.kt:388` (mock, :1450) — refused in blind fields;
    `src/test/.../IssueRegressionTest.kt:640-654` (pure JVM, :660) — `clear` present in the command
    catalogue — exist: yes.
  - source: `KeyValue.kt:801` `"clear" -> editingKey("clr", Editing.CLEAR, FLAG_SMALLER_FONT)`;
    `KeyEventHandler.kt:973-983` — select-all + `commitText("", 1)` inside one batch edit;
    `if (isTerminalStyleEditor()) return` (comment dated 2026-10-13; the v1.5.0 handler at its
    `:674` had no such guard, so v1.5.0 clears in every field); `:892-895` same action inside the
    inline clipboard editor. `customization/CommandRegistry.kt:129` `Command("clear", "Clear",
    "Erase entire field (select all + delete)", Category.EDITING)` (`strings.xml:2333-2334`);
    `a11y/KeyLabels.kt:163` → "Clear text field" (`strings.xml:67`). Not an Extra Key
    (`prefs/ExtraKeysPreference.kt` has no `"clear"`). Reachable via Short Swipe Customization
    (opened from Short Swipe Calibration under Activities, or Settings search;
    `SettingsNavigation.kt:55-57`) or as a layout key value.
  - device check: not recorded.
- **Thread notes:** reporter asked for a `clear` key erasing the whole input. No follow-ups. The
  bot touched it 2026-09-13 (label), still OPEN.
- **Audit-doc correction:** `docs/audit/gh-issue-resolution.md:151` cites drifted locations
  (`KeyValue.kt:737`, `KeyEventHandler.kt:725/764`, `CommandRegistry.kt:125`); at HEAD they are
  `KeyValue.kt:801`, `KeyEventHandler.kt:973` (and `:892`), `customization/CommandRegistry.kt:129`.
  Status and release are correct.
- **Draft reply:**

> Added in v1.5.0 (`c4778d9d`) as a `clear` command: it selects everything in the field and deletes it in one step.
>
> Two ways to use it: in Short Swipe Customization, tap a key, pick a direction and choose "Clear" from the Editing commands; or in a custom layout use `clear` as a key value (it renders as a small "clr" key). TalkBack reads it as "Clear text field".
>
> One limit, from v2.0.0 on: in terminal-style fields (Termux, RustDesk and other apps that do not expose their text) the key does nothing, because select-all cannot reach text the keyboard cannot see and the fallback would send a single stray Backspace to the remote side. Everywhere else it works as you asked.
>
> Closing as shipped; reopen if it leaves text behind in a specific app, with the app name.

- **APPROVE? [ ]**




---

## #111 — Expanded Comparison Table for Urik

- **Proposed action:** close as completed (now).
- **Released state:** docs on main since `bedd193a` (in v1.5.0); README on GitHub main is what the
  reporter sees.
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `bedd193a` 2026-04-26 docs: add Urik comparison column + reframe gesture roadmap as
    dual-path — ancestor of HEAD: yes; tags: v1.5.0 (earliest).
  - tests: none (documentation).
  - source: `README.md:59` header `| Feature | CleverKeys | HeliBoard | FUTO | FlorisBoard |
    AnySoftKeyboard | Urik |`; `:61` Termux row Urik "❓" (kept as the reporter proposed);
    `:63` Multi-Language Swipe Urik "✅ Simultaneous"; `:67` APK Size Urik "~40 MB"; `:75` License
    GPL-3.0; footnotes `:98` ¹⁰ (geometric/template matcher, QWERTZ-native, simultaneous
    multilingual spell-check) and `:100` ¹¹ (short-swipe limited to Space/Backspace).
  - device check: n/a.
- **Thread notes:** reporter supplied a draft Urik column and key details; all of it is reflected.
  No follow-ups. Labelled `stale` by the removed bot 2026-09-20.
- **Audit-doc correction:** `docs/audit/gh-issue-resolution.md:155` is accurate.
- **Draft reply:**

> Done in `bedd193a`: the README comparison table has a Urik column with your values (geometric engine, simultaneous multilingual swipe, ~40 MB, GPL-3.0, Space/Backspace-only short swipes) and two footnotes carrying the detail you wrote up. I kept the Termux cell as "❓" since neither of us has tested it. Thanks for the draft; it went in almost verbatim.
>
> Closing. If Urik changes something material, open a new issue or PR against README.md.

- **APPROVE? [ ]**




---

## #94 — [Feature]: Copy version information on settings Version Information modal long press

- **Proposed action:** close as completed (now).
- **Released state:** in v1.5.0 (`7558313b`); the pinning test is unreleased (`1c27a10a`).
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `7558313b` 2026-04-26 feat: #94 long-press copy version info from Settings — ancestor
    of HEAD: yes; tags: v1.5.0 (earliest). `1c27a10a` 2026-09-05 test(settings): pin #94 long-press
    version copy — ancestor: yes; tags: none.
  - tests: `src/test/.../ui/settings/VersionCopyPayloadTest.kt` (pure JVM, build.gradle:1089):
    `payloadIsTitleThenBuildLine` :35, `longPressCopiesThePayloadAndConfirms` :73,
    `copyStringsAreLocalizedEverywhere` :89 — exist: yes.
  - source: `ui/settings/SettingsInfoCards.kt:65-76` `onLongClick` → `buildVersionCopyPayload`
    (:241) → `ClipboardManager.setPrimaryClip` (:76, deliberately the normal system clipboard) →
    toast "Version info copied" (`strings.xml:183`). Card "📱 Version Information"
    (`strings.xml:181`) inside section "📋 Information & Actions" (`:180`,
    `VersionActionsSection.kt:22-26`) at the bottom of Settings.
  - device check: not recorded.
- **Thread notes:** reporter wanted a long-press on the version card to copy version info for bug
  reports. No follow-ups. Labelled `stale` by the removed bot 2026-09-20.
- **Audit-doc correction:** `docs/audit/gh-issue-resolution.md:158` is accurate.
- **Draft reply:**

> In v1.5.0 (`7558313b`). At the bottom of Settings, under "Information & Actions", long-press the "Version Information" card: the version and build lines go to the system clipboard and a "Version info copied" toast confirms. Paste it straight into a bug report.
>
> Closing as shipped; reopen if the long-press does nothing on your device.

- **APPROVE? [ ]**




---

## #93 — [Feature]: Custom Themes: add hex color input field

- **Proposed action:** close as completed (now).
- **Released state:** in v1.5.0 (`1015087e`).
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `1015087e` 2026-04-26 feat: #93 editable hex color input in ColorPickerDialog —
    ancestor of HEAD: yes; tags: v1.5.0 (earliest).
  - tests: `src/androidTest/.../Issue93ThemeHexInputComposeTest.kt` (instrumented):
    `themeSettings_hasHexInputField` :34, `themeSettings_hexInputAcceptsRRGGBB` :40 — exist: yes.
  - source: `activities/ThemeSettingsActivity.kt:1110-1140` — "Hex" text field (`strings.xml:434`)
    accepts `#RRGGBB` or `#AARRGGBB`, parses to ARGB and updates the HSL sliders; the field is
    re-synced when the sliders move (:1113-1116). Opened from a colour row in the Create/Edit Theme
    dialog (`:925-938` `ColorAttributeRow` → `ColorPickerDialog`). Path: Settings → "📱 Activities"
    → "Theme Manager" (`strings.xml:449`) → "Create Theme" / "Edit Theme" (`:407-408`) → tap a
    colour → "Hex".
  - device check: not recorded (instrumented Compose test only).
- **Thread notes:** reporter wanted an input field for hex codes because sliders are hard to hit
  exactly. No follow-ups. Labelled `stale` by the removed bot 2026-09-20.
- **Audit-doc correction:** `docs/audit/gh-issue-resolution.md:159` is accurate.
- **Draft reply:**

> This is in v1.5.0 (`1015087e`). Settings → Activities → Theme Manager, create or edit a theme, tap any colour row: the picker has a "Hex" field above the sliders. It accepts `#RRGGBB` or `#AARRGGBB`, applies as you type, and the sliders follow; moving a slider rewrites the hex, so you can copy exact values between colours.
>
> Closing as shipped. Reopen if a value is not accepted or the sliders and field disagree, with the value you typed.

- **APPROVE? [ ]**




---

## #87 — [Feature]: If swipe typing is disabled, long swipes should map to short swipes, not taps

- **Proposed action:** close as completed (at the v2.0.0 tag)
- **Released state:** unreleased, will ship in v2.0.0 (`c93be97b`, `918dc322`)
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `c93be97b` 2026-10-20 wip: short swipes only — ancestor of HEAD: yes; tags: none. `918dc322` 2026-10-20 feat(gestures): short swipes only mode (#147, #87) — ancestor of HEAD: yes; tags: none. (`2f67a7b0`'s subject cites `c30ad450`, which is the pre-merge worktree-branch commit, not on main; the tracker rows cite the right hashes.)
  - tests: `src/test/kotlin/tribixbite/cleverkeys/PointersShortSwipesOnlyTest.kt#on_longDisplacement_typesTheDirectionsSubkey_notATapOfTheStartKey` (mock; `build.gradle` mockTestClasses:1265; 10 @Test) — exists at HEAD: yes. `#off_beyondTheBoundary_swipeTypingOff_tapsTheStartingKey` and `#off_beyondTheBoundary_swipeTypingOn_isAWordSwipe` pin that the default behaviour is unchanged.
  - source: `Config.kt:1000` field; `Config.kt:1332-1334` — effective mode = `short_swipes_only` pref AND `short_gestures_enabled`; while on, `swipe_typing_enabled` is forced false for the live config (stored pref untouched). `Pointers.kt:486-488` — leaving the start key no longer disqualifies a short swipe; `Pointers.kt:514` — `distance >= minDistance && (shortSwipesOnly || distance <= maxDistance)`; `Pointers.kt:370`, `:1131` — never a word swipe in the mode. UI: `GestureTuningSection.kt:68-74` switch "Short Swipes Only" (`strings.xml:699-700`), enabled only with "Enable Short Gestures" on (`:71`); Max Distance slider disabled with a reason (`GestureTuningSection.kt:95-108`, `strings.xml:702`); "Enable Swipe Typing" switch disabled with a reason (`SwipeTypingSection.kt:62-70`, `strings.xml:701`). The Max Distance slider is a percent of the key diagonal, range 50..200 (`Config.kt:644`), since `427069bc` (2025-12-09, in v1.0.x).
  - device check: Saga 2026-10-20 15:44 (`memory/todo.md:210-213`, commit `4619d08f`): with the switch on, a long up-right swipe from `w` far past Max Distance typed "2", a long east swipe toward an empty direction typed nothing, Max Distance showed disabled; switch turned back off. Standard layout only — no MessagEase/Thumb-key-style layout was checked (tracker row 149 asks for one).
- **Thread notes:** Reporter wants a long swipe to act as a short swipe when swipe typing is off, "no UI necessary, but could be a settings toggle"; alternative: raise or disable the short-swipe maximum ("250px is far too low"). racuna (2026-04-22): same problem on a Thumb-key-style split layout for es/pt, raising the limit to 250 did not help; side ask: can the keyboard be moved to one side for one-handed use. The reply answers: the toggle, why it is opt-in rather than automatic, what the Max Distance slider is (percent, caps at 200), and that no one-handed/side-offset mode exists (redirect to #115, which tracks the split/floating keyboard work).
- **Audit-doc correction:** `docs/audit/gh-issue-resolution.md:407-408` (lower section item 8) still says "Device check of long flicks still owed"; the Saga pass of 2026-10-20 is recorded in `memory/todo.md:210`. Row 161 is accurate.
- **Draft reply:**

> This is built, in `918dc322` (with `c93be97b`), and will ship in v2.0.0.
>
> There is a new switch, Settings → Gesture Tuning → Short Swipes Only (it needs Enable Short Gestures on). With it on, every swipe that is at least the minimum distance types the subkey in that direction, no matter how far you go. Swiping toward a direction that has no subkey types nothing, and a key with no subkeys keeps its tap. Word swipe typing is off while the switch is on; the Enable Swipe Typing switch and the Max Distance slider show as disabled with a note saying why, and your stored swipe-typing setting comes back when you turn the mode off.
>
> I made it a switch rather than automatic when swipe typing is off, so nothing changes for people who have swipe typing off today and rely on a long swipe falling back to a tap. Default behaviour is untouched.
>
> On the 250 number: Max Distance is a percentage of the key diagonal, not pixels, and tops out at 200 %. With Short Swipes Only on it is not used at all, so you no longer have to tune it.
>
> @racuna, your Thumb-key-style layout is exactly the case this is for. Moving the keyboard to one side for one-handed use is not built; the split/floating keyboard work is tracked in #115.
>
> To check after updating: turn the switch on, then swipe from any key right across the keyboard in a direction that has a subkey. It should type that subkey. I'll close this when v2.0.0 is out; reopen if it still fails on your layout and tell me the layout file and the gesture.

- **APPROVE? [ ]**




---

## #70 — [Feature]: programmatic launch via Intent

- **Proposed action:** comment only, keep open — **needs a maintainer decision** before closing:
  the v2.0.0 contract makes the reporter's export → edit → import loop impossible headlessly
  (exports always encrypted, plaintext imports refused, decrypt script but no encrypt script).
  Options: (a) ship `scripts/ckenc-encrypt.py` mirroring `ckenc-decrypt.py` so edited JSON can be
  re-encrypted and imported; (b) a second opt-in toggle admitting plaintext headless imports;
  (c) accept the limitation and close after explaining. Also decide whether to answer Hubert21's
  unanswered 2026-03-17 follow-up now (the fix he needed shipped in v1.4.0).
- **Released state:** intent launch in v1.2.6 (`90c929d1`); file-URI/MediaStore fixes v1.3.0
  (`6315f018`, `48a6535e`, `cf224cd7`); `json_base64` v1.4.0 (`75e6e89e`). Encryption contract
  unreleased, will ship in v2.0.0 (`754e0889`, `e6452f48`, `f1f96321`).
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `90c929d1` 2026-01-16 feat: settings follow system theme + Intent automation (#35,
    #70) — ancestor: yes; tags: v1.2.6 (earliest), v1.5.0. `6315f018` 2026-02-27 fix: intent
    export/import for file:// URIs (#70) — v1.3.0. `48a6535e` 2026-02-27 fix: write exports to
    Downloads/CleverKeys/ via MediaStore fallback (#70) — v1.3.0. `cf224cd7` 2026-03-09 fix(#70):
    read imports via MediaStore for files modified by other apps — v1.3.0. `75e6e89e` 2026-03-17
    fix: scoped storage import fallbacks + json_base64 bypass (#70) — v1.4.0 (earliest), v1.5.0.
    `754e0889` 2026-07-17 feat(security): encrypted backups (AES-256-GCM) — ancestor: yes; tags:
    none. `e6452f48` 2026-08-27 fix(backup): scope the exported BackupRestoreActivity to its six
    backup actions (ARC-031) — none. `f1f96321` 2026-08-27 fix(backup): bound the json_base64 decode
    and delete its scratch file (ARC-033) — none.
  - tests: `src/androidTest/.../BackupRestoreActivityHeadlessEncryptionTest.kt` (instrumented):
    `noPassphrase_exportRefused_managerNeverInvoked` :106, `headlessImport_plaintextBase64_isRejected`
    :122, `headlessImport_encryptedBase64_isApplied` :234, temp-file cleanup :182/:206 — exist:
    yes. `BackupRestoreManagerEncryptionTest` (mock, build.gradle:1463), `BackupRestoreFullBackupTest`
    (mock, :1397).
  - source: `activities/BackupRestoreActivity.kt:51-56` six actions; `:161-165` no stored
    password → fail closed with `strings.xml:1787` "Set a backup password in Settings → Backup &
    Restore first"; `:170-175` 2 s throttle; `:178` `EncryptionPolicy.HEADLESS_MANDATORY` (exports
    always encrypted; `BackupRestoreManager.kt:118,126-127`); `:183-184` `json_base64` extra;
    `:189-196` `--es passphrase` accepted for IMPORT only, only when
    `backup_allow_intent_passphrase` is on (`:112`; Settings → Backup & Restore → Backup Password
    block, `BackupPasswordBlock.kt:57,126`, label `strings.xml:1002` "Allow password via automation
    intent"); `:200-205` plaintext headless import rejected with `strings.xml:1789` "Import failed:
    plaintext backups are not accepted via automation — use the app's Import button". Wiki
    `docs/wiki/troubleshooting/backup-restore.md:228-275` (contract), `:291-335` (actions,
    `json_base64`, size limits ~96 KB via Termux `am`, ~48 KB via adb). `scripts/ckenc-decrypt.py`
    exists (reference CKENC1 decryptor); **no encrypt counterpart** (`rg --files scripts | rg
    -i encrypt` → only decrypt).
  - device check: not recorded for the headless path on a phone (instrumented tests only).
- **Thread notes:** reporter asked for intent-launchable import/export (done v1.2.6). Hubert21
  (2026-03-09, v1.3.0): a file copied or edited by another app (cp, vim, even saved unchanged)
  imports as "not a JSON object"; `mv` works. Maintainer (03-16): Android owner/perms, retest on
  latest; Hubert (03-17): still broken on 1.3.0 (which predates `75e6e89e`, v1.4.0). Unanswered
  since. Mechanism: scoped storage — a file created/modified by another app is no longer readable
  by CleverKeys at that path even when the name is unchanged; `mv` keeps the original file's
  ownership. Fix shipped in v1.4.0: `--es json_base64 "$(base64 -w0 < file)"` passes the content
  inline. Reply must explain this, give the command, and state the v2.0.0 contract honestly.
- **Audit-doc correction:** `docs/audit/gh-issue-resolution.md:165` says "DONE (v1.4.0)"; the
  intent actions shipped in v1.2.6 (`90c929d1`) — v1.4.0 is only the `json_base64` fix. The row's
  "comment explaining both; close" skips the open design question (no re-encrypt path for an
  edited backup); recommend deciding (a)/(b)/(c) above before closing.
- **Draft reply (assumes option (c) — adjust the last paragraph if (a) or (b) is chosen):**

> @Hubert21 sorry for the silence; your report was right and the fix landed in v1.4.0, after your last test on 1.3.0.
>
> What was happening: on Android 10+ a file that another app creates or rewrites (cp, vim saving, even with no change) belongs to that app, and CleverKeys can no longer read it at the same path. `mv` keeps the original file's owner, which is why that case worked. The fix (`75e6e89e`) is an extra that passes the content inline instead of a path:
>
> ```
> am start -a tribixbite.cleverkeys.action.IMPORT_SETTINGS \
>   -n tribixbite.cleverkeys/.BackupRestoreActivity \
>   --es json_base64 "$(base64 -w0 < c2.json)"
> ```
>
> Limit is about 96 KB of JSON from Termux (48 KB via `adb shell`); the wiki's Backup & Restore page has a chunked variant for bigger clipboard backups.
>
> One important change coming in v2.0.0 (`754e0889`): the automation path requires a Backup Password (Settings → Backup & Restore → Backup Password), every headless export is encrypted (`.ckenc`, AES-256-GCM), and a headless import refuses an unencrypted file. The `am start` commands themselves do not change, and `--es passphrase` on imports only, behind a switch, lets you restore another device's backup. This closes a hole where any zero-permission app could pull your settings or inject a backup through these intents. The cost for scripts like yours: you can decrypt an export off-device (`scripts/ckenc-decrypt.py`) and edit it, but an edited file has to go back in through the in-app Import button, which still accepts plain JSON. If you need the full export → edit → import loop without touching the UI, say so here and describe the edits you make; that decides whether I add a matching encrypt script.
>
> I'll leave this open until I hear whether the v2.0.0 contract works for you.

- **APPROVE? [ ]**




---

## #68 — [Feature]: Greek dictionary

- **Proposed action:** close as completed (now) for the dictionary ask; point the oversized-pack
  crash to #184; say Greek swipe on the Greek alphabet is a v2.0.0 change with no accuracy figure.
- **Released state:** Greek pack in v1.2.6 and later including v1.5.0 (`a497e4eb`; docs
  `fbdf7bcc` v1.5.0). Greek CTC swipe routing and the 100,000-word import limit are unreleased,
  will ship in v2.0.0 (`f6cc401d`-era wiring in `CtcScriptSupport.kt`; `CkdtDictionaryReader`
  caps).
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `a497e4eb` 2026-01-16 feat: add Greek language pack + improve keyboard command routing
    (#68, #30) — ancestor: yes; tags: v1.2.6 (earliest), v1.5.0. `fbdf7bcc` 2026-06-02 docs(#68):
    surface the already-existing Greek language pack + fix import guide — ancestor: yes; tags:
    v1.5.0. `f6cc401d` 2026-09-03 announce Russian and Greek swipe (release notes) — tags: none.
    `git grep '"el"' v1.5.0 -- src/main/kotlin/tribixbite/cleverkeys/swipe` is empty: Greek CTC
    routing is NOT in v1.5.0 (Greek swipe there = geometric fallback).
  - pack: live asset `langpack-el.zip` (979,829 B, 2026-09-27): `dictionary.bin` 1,650,491 B,
    `model.onnx` 589,406 B, `unigrams.txt`, `manifest.json`, `NOTICE.txt`. 39,860 words, oracle
    "C (AOSP only, curated down from 46,306)" (`README.md:314`). NOTICE: word list adapted from
    wordfreq (CC BY-SA 4.0) with the AOSP list as inclusion oracle — **the reporter's
    greekdictionary was not used.**
  - source: `src/main/layouts/grek_qwerty.xml`; `swipe/ctc/CtcScriptSupport.kt:192-206` `"el"`
    ScriptWiring, alphabet αβγδεζηθικλμνξοπρςστυφχψω, `status = ROUTED`, comment "Greek has NO
    real-swipe probe at any tier; never quote … as accuracy". #184 cap:
    `swipe/geometric/CkdtDictionaryReader.kt:42-43` `MAX_WORD_COUNT = 100_000`,
    `MAX_DICTIONARY_BYTES = 16 MiB`; user message `strings.xml:2785` "This dictionary contains %1$d
    words; the limit is %2$d. Import a smaller pack." (unreleased).
  - tests: `src/test/.../langpack/LanguagePackImportTest.kt:234-239` (pure JVM) imports an `el`
    pack. No Greek completion/swipe test; device check: not recorded for Greek.
  - #184 (live): "[Bug]: Cannot handle big dictionaries", OPEN, filed by dim-geo 2026-09-18 on
    v1.5.0 with a 1,000,000-word Greek pack.
- **Thread notes:** dim-geo: (1) use my dictionary / HeliBoard dictionaries; (2) is touch typing
  useful — yes; (3) can swipe work for Greek via a Greek→Latin pseudo-English mapping and retrain?
  kreorei: German + Greek in parallel, reliable suggestions without aggressive autocorrect, reuse
  HeliBoard/AnySoftKeyboard dictionaries. Reply must answer all five and point the 1M-word crash
  to #184.
- **Audit-doc correction:** `docs/audit/gh-issue-resolution.md:167` says "DONE (v1.5.0) …
  el CTC-routed (ARC-055)". The pack is in v1.2.6 already; the CTC routing is NOT in v1.5.0
  (unreleased, v2.0.0) — in v1.5.0 Greek swipes via the geometric engine.
- **Draft reply:**

> Greek is in. Summary of where things stand, with answers to the questions in the thread.
>
> Dictionary (v1.2.6 onward, so in v1.5.0): download `langpack-el.zip` from https://github.com/tribixbite/CleverKeys/releases/tag/langpacks, then Settings → Multi-Language → Import Pack. 39,860 words. I built it from wordfreq frequency data with the AOSP Greek list as a filter, not from @dim-geo's list — if you find words it lacks, send me a few and I will compare against yours. Suggestions and autocorrect are separate: Settings → Auto-Correction → "Enable Auto-Correction" can stay off while suggestions keep working, which is what @kreorei asked for.
>
> Two languages at once (@kreorei): today, set German and Greek as primary and secondary under Multi-Language. In v2.0.0 you can instead bind the Greek layout to Greek and your German layout to German in Layout Manager, and switching layouts switches the language with no mixing.
>
> Swipe (@dim-geo): no pseudo-English mapping needed. In v2.0.0 the pack carries a Greek-alphabet swipe model, and swiping on the Greek layout decodes Greek directly. In v1.5.0 Greek swipe runs on the geometric engine instead. I have no measured accuracy figure for Greek swipe in either version, so treat it as working-but-unmeasured and tell me what you see.
>
> HeliBoard/AnySoftKeyboard dictionaries: the pack builder takes a plain word list with frequencies, which is what those projects' sources are; there is no direct importer for their binary format.
>
> The crash with the 1,000,000-word Greek pack is #184. In v2.0.0 a pack above 100,000 words is refused with a message instead of crashing; the limit itself is discussed there.
>
> I'll close this as shipped; the remaining pieces are tracked in #184 and the v2.0.0 notes.

- **APPROVE? [ ]**




---

## #61 — [Feature]: actively switch  many languages

- **Proposed action:** close as completed at the v2.0.0 tag (fix is unreleased; two-layout switch
  device-verified, three-layout cycle only JVM-tested).
- **Released state:** unreleased, will ship in v2.0.0 (`f85c8dcd`).
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `f85c8dcd` 2026-10-07 feat(language): bind a language to each layout (#186, #61) —
    ancestor of HEAD: yes; tags: none.
  - tests: `src/test/.../LayoutLanguageBindingTest.kt#threeBoundLayoutsCycleThroughThreeLanguages`
    :115 (pure JVM, build.gradle:819) — exists: yes.
    `src/test/.../ActiveLanguageSyncTest.kt#threeBoundLayoutsCycleThroughThreeDictionaries` :91
    (pure JVM, :820) — exists: yes.
  - source: `LayoutLanguageBinding.kt:109-128` `resolve` — a bound layout returns
    `secondary = null` (":119 #61: a bound layout is single-language — no words mixed in from a
    secondary"); unbound layouts keep the pre-binding primary/secondary behaviour (:122-124).
    `activities/LayoutManagerActivity.kt:501-502` AssistChip "Language: %1$s"
    (`strings.xml:1506`) opens the picker (:325-343); dialog strings `:1500` "Layout language",
    `:1501` "Follow Multi-Language settings" (default), `:1503` "While this layout is active, the
    chosen language is the only language used for predictions, autocorrect and swipe typing…",
    `:1504` "No %1$s dictionary is installed…". Path: Settings → "📱 Activities" (`:446`) →
    "Layout Manager" (`:453`). Wiki: `docs/wiki/layouts/multi-language.md:64` "One Language per
    Layout", `:165`, `:183` → https://cleverkeys.app/wiki/layouts/multi-language/ (route
    `site/src/pages/wiki/[...slug].astro`, base `docs/wiki`).
  - device check: Saga, Latin→German-bound layout shows German bar/suggestions and restores after,
    PASS 2026-10-07 (`memory/todo.md:651-653`, `build/oct7-final/T4_*`). Three-layout en/de/fr cycle
    and the uninstalled-language warning NOT device-checked (`memory/todo.md:74-75`, Open now 2).
- **Thread notes:** reporter: 3+ languages, no word mixing, one layout per language; maintainer
  offered the two-at-a-time toggles (Jan); zepatrik (2026-04-30): not enough, wants language coupled
  to layout and single-language detection. Reply explains the binding, that bound = single
  language, that unbound layouts keep the old toggles, and that each language needs its pack.
- **Audit-doc correction:** `docs/audit/gh-issue-resolution.md:168` is accurate.
- **Draft reply:**

> This is built the way you described and will ship in v2.0.0 (`f85c8dcd`): each layout can carry its own language, and switching layouts switches the language.
>
> Setup: Settings → Activities → Layout Manager. Each layout in your list has a "Language: …" chip; tap it and pick the language for that layout. Add the same layout twice if you want one layout for two languages. Any number of layouts can be bound, so three is fine.
>
> Behaviour: while a bound layout is active, its language is the only one used for suggestions, autocorrect and swipe — nothing is mixed in from a second language, which should also help the accuracy you mentioned. Layouts you leave on "Follow Multi-Language settings" keep today's primary/secondary behaviour, so nothing changes for people who like the current toggles.
>
> Each language needs its dictionary: the 7 bundled ones work as-is, others via Settings → Multi-Language → Import Pack (packs at https://github.com/tribixbite/CleverKeys/releases/tag/langpacks). If a bound layout's dictionary is missing, the chip says so and the layout still types, just without suggestions.
>
> To verify after updating: bind three layouts to three languages, cycle through them with the layout-switch key, and watch the suggestion bar change language each time. I have confirmed the two-layout case on a phone; the three-layout cycle is covered by tests but I have not walked it on a device yet, so a report from you would be welcome.
>
> I'll close this when v2.0.0 is out; reopen if words from another language still show up on a bound layout.

- **APPROVE? [ ]**




---

## #58 — [Feature]: Scaling number keyboard

- **Proposed action:** close as completed (now — the scaling itself has shipped since v1.2.6);
  the reply must correct the on-thread hashes and the Settings path, which did not exist in any
  release.
- **Released state:** in v1.2.6 and later (`bd8cbafe`, `0f711855`); the Settings switch and the
  opt-in layout attribute are unreleased, will ship in v2.0.0 (`68bafddc`, `ba5bbee6`).
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `bd8cbafe` 2026-01-16 feat: add numpad height scaling for larger keys (#58) —
    ancestor: yes; tags: v1.2.6 (earliest), v1.5.0. `0f711855` 2026-01-16 fix: enlarge PIN
    keyboard keys by 20% (#58) — ancestor: yes; tags: v1.2.6, v1.5.0. `68bafddc` 2026-09-08
    feat(settings): F-8 decided — surface the writerless prefs — ancestor: yes; tags: none (adds
    the Settings switch; `git grep scale_numpad_height v1.5.0 -- ui activities strings.xml` is
    empty, so no release has a switch). `ba5bbee6` 2026-10-02 fix(layout): preserve compact custom
    row heights (gh #90) — ancestor: yes; tags: none (makes scaling opt-in per layout).
    **`be23db40` and `74580c22` (cited on-thread) do not exist in the repository.**
  - tests: `src/test/.../NumpadKeySizeTest.kt` (mock, build.gradle:1535):
    `pinLayout_keysAreExactlyTwentyPercentLargerThanBeforeTheFix` :94,
    `pinLayout_everyRowIsTheSameFiveUnitsWide` :102,
    `numericLayouts_keepTheHeightScalingBranchReachable` :122,
    `customSingleRow_keepsNormalRowHeightWithNumpadScalingEnabled` :134 — exist at HEAD: yes.
  - source: `Config.kt:60` `SCALE_NUMPAD_HEIGHT = true` (default on), `:813`, `:1135`;
    `Theme.kt:315` `if (config.scale_numpad_height && layout.numpad_height)` → rows stretch to the
    full keyboard height; `KeyboardData.kt:555` `numpad_height` attribute default false;
    `src/main/layouts/numeric.xml:2` and `pin.xml:4` `numpad_height="true"`;
    `ui/settings/sections/AppearanceSection.kt:86-96` switch, strings `:570` "Scale numpad height"
    / `:571` "Stretch PIN and numpad rows to fill the full keyboard height" under `:159`
    "🎨 Appearance".
  - device check: the v1.2.6 behaviour has been in users' hands since January; the v2.0.0 opt-in
    cases (numeric/PIN on/off, compact custom layouts) are still on the pre-tag list
    (`memory/todo.md` Open now 9, "#90 numpad-height cases") — not yet device-checked.
- **Thread notes:** reporter wanted the number/PIN keyboard scaled to fill the keyboard area. The
  maintainer's 2026-01-16 comment cites two non-existent hashes and a Settings path
  ("Settings > Layout > Scale numpad height") that no release has. Reply corrects both.
- **Audit-doc correction:** `docs/audit/gh-issue-resolution.md:169` is accurate (hash correction
  already noted in the header). Add: the on-thread Settings path never existed before `68bafddc`
  (2.0), and in 2.0 it is Settings → Appearance, not "Layout".
- **Draft reply:**

> Closing the loop on this with two corrections to my January comment.
>
> The commit hashes I quoted were wrong. The real ones are `bd8cbafe` (numpad rows stretch to fill the keyboard height) and `0f711855` (PIN keys 20 % wider), and both have been in every release since v1.2.6, so v1.5.0 has this behaviour today: the number and PIN keyboards use the full keyboard area instead of letter-keyboard proportions.
>
> I also pointed you at a "Settings → Layout → Scale numpad height" switch. That switch did not exist in any release; scaling was simply always on. It will exist in v2.0.0 as Settings → Appearance → "Scale numpad height" (on by default, `68bafddc`). In v2.0.0 the stretching also applies only to layouts that ask for it (the built-in numeric and PIN layouts do, via `numpad_height="true"` in the layout XML, `ba5bbee6`), because the old behaviour was also stretching some compact custom layouts (#90).
>
> To check on v1.5.0: open a PIN or number field and compare key height with the letter keyboard. On v2.0.0, flip the switch off and on in the same field.
>
> I'll close this as shipped; reopen if a number field still shows small keys on your device, with the app name and a screenshot.

- **APPROVE? [ ]**




---

## #49 — [Feature]: Turkish (Türkçe) language support

- **Proposed action:** close as completed (now).
- **Released state:** in v1.2.6 and every later release including v1.5.0 (`310fc83a`).
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `310fc83a` 2026-01-16 feat: add timestamp commands to UI + Turkish/Swedish language
    packs — ancestor of HEAD: yes; tags: v1.2.6 (earliest), v1.5.0.
  - pack: live asset `langpack-tr.zip` (393,073 B, 2026-09-27); contents `dictionary.bin`
    1,039,124 B, `unigrams.txt`, `manifest.json`, `NOTICE.txt` — **no `model.onnx`** (geometric by
    design); 40,000 words, oracle "C (AOSP only)" (`README.md:322`).
  - source: `src/main/layouts/latn_qwerty_tr.xml` (ç ğ ı ö ş ü keys); `res/values-tr/strings.xml`
    2,178 of 2,188 strings translated; `swipe/ctc/CtcImportedPackSupport.kt:66-77` — Turkish is
    73.34 % / 81.7 % a–z-projectable (dotless ı has no NFD form) so CTC is refused and swipe runs
    on the geometric engine; `README.md:265-270` states the same.
  - tests: `src/test/.../swipe/ctc/CtcImportedPackSupportTest.kt:304` (pure JVM) pins the Turkish
    rejection → geometric.
  - device check: not recorded for Turkish (no swipe accuracy measured for tr anywhere).
- **Thread notes:** reporter asked for Turkish characters, autocorrect/suggestions, swipe, and a
  Turkish UI. No follow-ups. Reply covers all four and says plainly that Turkish swipe is the
  geometric engine with no measured accuracy figure.
- **Audit-doc correction:** `docs/audit/gh-issue-resolution.md:171` is accurate. The header's
  "shipped in v1.5.0" list is loose: `310fc83a` is in v1.2.6 already.
- **Draft reply:**

> Turkish has been in since v1.2.6, so everything below is in v1.5.0 today:
>
> - Layout: Settings → Activities → Layout Manager, add "QWERTY (Türkçe)" — ç, ğ, ı, ö, ş, ü are on the keys.
> - Dictionary (suggestions and autocorrect): download `langpack-tr.zip` from https://github.com/tribixbite/CleverKeys/releases/tag/langpacks, then Settings → Multi-Language → Import Pack and choose Turkish as primary or secondary language. 40,000 words.
> - App UI: fully translated (2,178 of 2,188 strings); it follows your system language.
> - Swipe: works on the Turkish layout through the geometric engine. This is deliberate: dotless ı has no a–z spelling, so a quarter of Turkish vocabulary (nasıl, artık, mı, aynı) could never be swiped on the CTC model. The geometric engine decodes over the real keys instead. I have no measured accuracy number for Turkish swipe, so treat it as working-but-unmeasured and tell me if common words come out wrong.
>
> In v2.0.0 you will also be able to bind the Turkish layout to the Turkish dictionary in Layout Manager, so switching layouts switches language.
>
> I'll close this as shipped; reopen with examples (word, what you got instead) if suggestions or swipe misbehave.

- **APPROVE? [ ]**




---

## #31 — [Feature]: Next word prediction

- **Proposed action:** comment only, keep open — ask the reporter to retest Russian word completion
  on v1.5.0 with `langpack-ru.zip` imported; close when confirmed, or at the v2.0.0 tag if there is
  no reply. (Tracker says close now; nothing Russian-specific was ever tested or device-checked.)
- **Released state:** Russian pack in v1.5.0 (`32f93f1d`); non-Latin autocorrect fix and the
  next-word feature are unreleased, will ship in v2.0.0 (`997d8f78`, `a90a6489`, `1d2f1d58`).
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `32f93f1d` 2026-06-02 feat(lang): add Russian language pack (#68 follow-up) —
    ancestor: yes; tags: v1.5.0 (earliest). `997d8f78` 2026-08-06 feat(context-lm): … opt-in
    next-word — ancestor: yes; tags: none (next-word did NOT exist in v1.5.0). `a90a6489`
    2026-09-26 feat(next-word): static tier works with learning off; default ON — ancestor: yes;
    tags: none. `9ae7005c` 2026-09-07 fix(gesture): letter gate accepts every script (swipe only) —
    tags: none. `1d2f1d58` 2026-10-08 fix(autocorrect): neighbour-key typos on Hebrew and other
    non-Latin boards are corrected — ancestor: yes; tags: none.
  - pack: live asset `langpack-ru.zip` (1,058,900 B, 2026-09-27) = `dictionary.bin` 2,088,865 B +
    `model.onnx` 589,406 B + `unigrams.txt` + `manifest.json` + `NOTICE.txt`; 50,000 words
    (`README.md:316`). Layout `src/main/layouts/cyrl_jcuken_ru.xml` present in v1.5.0.
  - v1.5.0 Settings: `ui/settings/io/SettingsLanguagePackHandlers.kt:20-48` (at tag v1.5.0) adds
    every installed pack to the Primary/Secondary Language lists, so a v1.5.0 user can pick
    Russian after importing. HEAD: `WordChars.kt:47` uses `Character.isLetter` (Cyrillic counts);
    `res/values/strings.xml:479` "🌐 Multi-Language", `:1115` "Import Pack", `:811` "Primary
    Language"; next-word switch `:759` "Next-Word Prediction" under "📝 Input Behavior"
    (`InputBehaviorSection.kt:172-177`), default `Config.kt:206` = true.
  - tests: `src/test/.../langpack/LanguagePackImportTest.kt:336-346` (pure JVM) installs an `ru`
    pack with its model; `WordCharsTest.kt:65` (JVM) Cyrillic word characters. **No Russian
    tap-completion test exists.**
  - device check: not recorded for Russian tap completion.
- **Thread notes:** the title is misleading: Drugoy's actual ask is current-word completion for
  Cyrillic (прив → привет) on "System Layout" and `cyrl_jcuken_ru`; the maintainer's last reply
  (2026-01-15) said non-Latin "probably not going to work yet" and never followed up after the pack
  shipped. racuna (2026-04-24) asked for next-word from stored word pairs with counts. Drugoy
  (2026-04-24): "still not usable for Russian" — written before the pack (2026-06-02) and v1.5.0
  (2026-07-15). Reply must: tell Drugoy the pack exists and how to enable it; answer racuna.
- **Audit-doc correction:** `docs/audit/gh-issue-resolution.md:172` says "Next-word itself is
  default ON (`a90a6489`, unreleased)" — fine, but the feature was only added in `997d8f78`
  (2026-08-06), so no release has next-word at all. Row recommends "close as completed" with no
  Russian-specific test or device evidence; treat as needs-reporter-confirmation.
- **Draft reply:**

> @Drugoy I owe you an update. When we last talked there was no Russian dictionary, so the suggestion row stayed empty no matter what you typed. There is one now.
>
> Since v1.5.0: download `langpack-ru.zip` from the langpacks release (https://github.com/tribixbite/CleverKeys/releases/tag/langpacks), then Settings → Multi-Language → Import Pack, and set Primary Language to Russian. With the `cyrl_jcuken_ru` layout, typing прив should now offer привет in the bar; the pack has 50,000 words. Word completion works on letters of any script once the matching dictionary is loaded, so the system-layout case should behave the same.
>
> I have not had a Russian speaker confirm this on a phone, so please try it and tell me what you see, with a word or two that fails if any do. Two more things land in v2.0.0: a typo one key over is corrected on non-Latin boards too (`1d2f1d58`), and a layout can be bound to a language in Layout Manager, so switching to the Russian layout switches the dictionary by itself (`f85c8dcd`).
>
> @racuna next-word suggestions exist now and will ship in v2.0.0, on by default (`a90a6489`): Settings → Input Behavior → Next-Word Prediction. It works much as you describe: shipped phrase data for English, German, French and Italian, plus pairs learned from what you type when on-device learning is on. The learned data is yours and exportable.
>
> I'll keep this open until Drugoy confirms Russian completion works, and retitle it to match.

- **APPROVE? [ ]**




---

## #26 — Docs/description: clarify current language support + update README comparison table (FUTO multilingual swipe)

- **Proposed action:** comment only, keep open — one README cell still contradicts the reporter's
  (correct) claim; fix `README.md:63` FUTO cell, then close as completed.
- **Released state:** docs on `main` (`99f5b70d`, `df6e4a08`); not in v1.5.0, but README on GitHub
  main is what the reporter reads, so the tag is irrelevant here.
- **Evidence (verified at HEAD ac86bda5):**
  - commits: `99f5b70d` 2026-09-03 docs(wiki,readme): correct the swipe-language scope and drop
    neural-era FAQ fossils — ancestor of HEAD: yes; tags: none. `df6e4a08` 2026-10-23 docs(readme):
    minSdk 24, measured APK size, theme names, 240 commands, ORT 1.21.1, Persian counts — ancestor:
    yes; tags: none.
  - tests: none (documentation).
  - source: `README.md:250-291` — "Current swipe-typing scope" block separates the CTC groups
    (7 bundled Latin, 6 script-routed non-Latin after pack import, a–z-projectable imported Latin
    packs) from the geometric engine (Turkish, Persian, fallback), states "Tap typing remains
    available independently of swipe support", and an "Evidence tiers, stated honestly" paragraph
    (en/fr/de/es measured; it/pt/sv provisional; ru validation-only; el/uk/bg/mk/he and every
    imported pack no real-swipe probe). `README.md:96` footnote ⁹ — 7 bundled + 13 downloadable.
    `README.md:308-345` — downloadable pack table with per-language oracle tier. `README.md:98` and
    `:437` — links to ROADMAP.md. `README.md:63` — comparison row "Multi-Language Swipe".
  - **residual:** `README.md:63` marks FUTO "⚠️ English only" for multi-language swipe. FUTO's own
    docs (docs.keyboard.futo.org/settings/languagesmodels, fetched 2026-10-25) say: with
    Multilingual typing on for e.g. English + German "you can swipe those words, no layout
    switching needed". The reporter's suggested "✅ Supported (docs)" for FUTO is right; our cell
    is not. Also: the reporter's checkbox roadmap list (#13 #22 #19 #18) is not in README; README
    links ROADMAP.md instead (#13 language packs and #22 French are shipped anyway).
  - device check: n/a.
- **Thread notes:** reporter asked for (1) layouts vs language-models wording — done
  (`README.md:250-291`); (2) roadmap-first with links — ROADMAP.md linked, no checkbox list;
  (3) a comparison row for multilingual swipe — row exists but the FUTO cell is wrong per FUTO's
  docs. No follow-ups; only the welcome bot replied.
- **Audit-doc correction:** `docs/audit/gh-issue-resolution.md:173` says DONE / close as completed;
  the comparison-row half of the ask still carries a factually wrong FUTO cell (`README.md:63`),
  so it is PARTIAL until that one cell is edited. (Tracker header also lists #26 under "shipped in
  v1.5.0"; it is docs on main, never part of a release build.)
- **Draft reply:**

> Thanks for this — you were right, and the README on main now says what the app actually does.
>
> What changed (`99f5b70d`, refreshed in `df6e4a08`, both on main):
> - The language section separates layouts, tap-typing dictionaries and swipe support. Swipe is described per group: the 7 bundled Latin languages, 6 non-Latin languages that swipe on their own alphabet after you import their pack, imported Latin packs that are a–z-typeable, and Turkish/Persian on the geometric engine. Tap typing is stated to work independently of swipe.
> - Evidence tiers are stated rather than implied: only English, French, German and Spanish have a measured accuracy bar; Italian, Portuguese and Swedish are marked provisional; Russian validation-only; Greek, Ukrainian, Bulgarian, Macedonian, Hebrew and every imported pack have no accuracy number quoted anywhere.
> - The comparison table has a "Multi-Language Swipe" row with a footnote on how it works, and the README links ROADMAP.md as the plan of record. I did not add the checkbox list you sketched; #13 (language packs) and #22 (French) have shipped since, and the roadmap file carries the rest.
>
> One thing you flagged is still wrong: that row marks FUTO's swipe as English-only, and FUTO's docs say multilingual typing swipes across the enabled languages. I'll correct that cell and close this once it is in. If you spot anything else in the README that over-promises, point at the line and I'll fix it.

- **APPROVE? [ ]**




---


## Pull requests

## #189 — fix(gestures): short swipes fire on keys with no center value

- **Proposed action:** request changes (rebase + run the test class), then merge. No design objection.
- **PR state:** base `79f0b464` head `9b111275` mergeable=CONFLICTING (mergeStateStatus DIRTY) files=2 (+65/−1)
  — author konstcranky (fork branch `main`), opened 2026-09-27, 0 comments, 0 reviews, `closingIssues` = #104
  (closed 2026-03-15).
- **Evidence (verified at HEAD ac86bda5):**
  - the fix is NOT on main: `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt:372` still reads
    `val canShortGesture = snap.short_gestures_enabled && ptr_value != null`; entry into the classification
    block is `canClassify = (snap.swipe_typing_enabled && isCharKey) || canShortGesture` (`:376`), so a
    centre-less key can only get in through `canShortGesture` — the PR changes the right variable.
  - why GitHub reports a conflict: the PR's context lines changed. At the base the line above was
    `val canSwipeType = snap.swipe_typing_enabled && isCharKey`; at HEAD it is
    `snap.swipe_typing_enabled && !snap.short_swipes_only && isCharKey && mayBeWordSwipe(ptr)` (`:370-371`,
    from `9c3fb179` 2026-10-08 and `918dc322` 2026-10-20) with a five-line comment above it. Pointers.kt has 10
    commits since the base (`fde558cd a9d8cb1d 97d6c25f 64f05dd2 745d1ca3 9c3fb179 1e369314 f135b2b7 a60f8692
    c93be97b`); the test file has 2 (`97d6c25f` #188 test, `835de98b` single-char custom mapping) and grew from
    ~300 to 444 lines, so the PR's test hunk (anchored on the old `// ---- harness` comment) conflicts too.
  - the fix still works at HEAD, checked path by path: `onTouchMove` records `ptr.lastX/lastY` before any gate
    (`:974-975`), and the touch-up short-swipe branch measures `ptr.lastX - ptr.downX` (`:475-477`), so the
    touch-move gate `isShortGestureKey = … && ptrValue != null` (`:1095`, also present at the base `:1020`)
    skipping path collection for a centre-less key does not matter. `ptr.gesture` is only set for Slider/Event
    subkeys (`:1058`, `:1069`), which return immediately. Inside the block: `allowLeftKey = !isCharKey` is true
    (`:472`); `keyCode = ptr.key.keys[0]?.getString() ?: ""` (`:538`) so custom mappings are skipped;
    `resolveNearestShortSwipe` guards `keyCode.isNotEmpty()` (`:924`) and `keyHasAnyShortSwipe` handles `""`
    (`:890-891`); the no-subkey fallthrough ends in `_handler.onPointerUp(null, …)` (`:702`) →
    `Keyboard2View.onPointerUp` (`:696`) → `KeyEventHandler.key_up` returns on `key == null` (`:100`).
    `make_pointer` already accepts a null value (`:1990`).
  - Short Swipes Only (`918dc322`): with the fix a centre-less key's flick of at least the minimum distance fires
    its subkey regardless of length (`:514`), and a flick toward an empty slot on a key that has subkeys types
    nothing (`:615-621`) — the same outcome the PR's `noCenter_flickTowardEmptySlot_emitsNothing` asserts (that
    test passes trivially today too, since the fallthrough is a null tap; it is still a useful pin).
  - `mayBeWordSwipe` (`:730-734`): a null key0 is neither `startsOnLetter` nor `Kind.Char`, so `canSwipeType`
    is false and `swipeEndCount` stays 0 — the PR's `noCenter_diagonalFlick_swipeTypingEnabled_emitsCornerSubkey`
    is a stronger guarantee at HEAD than at its base.
  - tests: the PR adds 5 methods to `src/test/kotlin/tribixbite/cleverkeys/PointersShortSwipeCustomOverrideTest.kt`
    (mock tier: `build.gradle:1262` lists it in `mockTestClasses`). Every harness name the new tests use exists
    at HEAD: `handler.ups` (`:392`), `handler.customs` (`:393`), `handler.swipeEndCount` (`:394`),
    `ptrs`, `flick(key, dx, dy, snap)` (`:158-172`), `snap(swipeTyping)` (`:103`),
    `KeyboardData.Key.EMPTY.withKeyValue` (`KeyboardData.kt:301`, `:326`).
  - the PR never confirmed its tests ran: the "New and existing unit tests pass locally" and "All unit tests
    pass" boxes are unticked, the manual-test device is blank, and the quoted command
    (`scripts/gradle-guard.sh testDebugUnitTest --tests …`) names a task that is disabled on ARM64 Termux — the
    class runs under `runMockTests`. Do NOT run it yourself; ask the author, or run after the rebase:
    `scripts/gradle-guard.sh runMockTests -PtestClass=PointersShortSwipeCustomOverrideTest`.
  - device check: none recorded (not by the author, not here).
- **Thread notes (PR description + comments):** description says corner/edge short swipes on a key with no
  centre (`c=""` in a custom layout) did nothing because the touch-up gate required a non-null value; only nav
  subkeys were rescued by the FIX #104 branch (`Pointers.kt:308-321`). Side effects listed (never a word
  candidate, custom mappings skipped, tap stays a no-op). Offers a CHANGELOG entry. `Assisted-by` trailer
  present. No comments. Related issue #104 was closed 2026-03-15; its one comment (OilSubjectLoss7) already said
  "it's the center key — if the center key is empty it causes this bug", which this PR generalises.
- **Audit-doc correction:** `docs/audit/gh-issue-resolution.md:179` calls it "small, tested" — the PR's own
  checklist does not claim a passing run, and its command targets a task disabled on the build box; say
  "small, with tests" until someone runs the class. The scratch audit's note that `mayBeWordSwipe` cannot be
  triggered by a centre-less key is confirmed.
- **Draft review (request changes — rebase only):**

> Thanks, this is the right fix and the tests are exactly what I want pinned. The reason #104 was only fixed
> for nav subkeys is that the deferred-down branch was the only place a null centre was handled; your change
> moves the rule to the gate itself, which is where it belongs.
>
> Two things before I can merge:
>
> 1. Please rebase onto main. `Pointers.kt` changed around your hunk (`9c3fb179` added the letter-start rule
>    to `canSwipeType`, `918dc322` added the "short swipes only" mode), and
>    `PointersShortSwipeCustomOverrideTest.kt` gained tests after your insertion point (`97d6c25f`,
>    `835de98b`), so GitHub shows a conflict. Notes for the rebase are inline below.
> 2. Please run the class and tick the boxes. `testDebugUnitTest` is disabled on the ARM64 build box; the class
>    runs under the mock runner:
>    `scripts/gradle-guard.sh runMockTests -PtestClass=PointersShortSwipeCustomOverrideTest`
>
> I'll add the changelog line myself when merging. I'll also run a quick check on a phone with a `c=""` key
> (corner, edge, tap, and the same with Short Swipes Only on) before the v2.0.0 tag.

- **Line comments (text):**

  - `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt` —
    `+        val canShortGesture = snap.short_gestures_enabled &&` /
    `+            (ptr_value != null || ptr.key.keys.any { it != null })`
    > Fine as is. On main there is now `keyHasAnyShortSwipe(ptr, keyCode)` (added for Short Swipes Only); it
    > checks the eight subkey slots through `_handler.modifyKey`, so a subkey the handler removes does not
    > count. If you like, `(ptr_value != null || keyHasAnyShortSwipe(ptr, ""))` reuses it; the raw
    > `keys.any` is also acceptable since a removed subkey only leads to the harmless null tap.

  - `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt` —
    `+        // FIX #104 above). A plain tap on such a key still ends in onPointerUp(null), a no-op.`
    > Correct at main too: `key_up` returns on a null key. Please also mention that `canClassify` (the
    > variable that now gates entry to the block on main) picks this up through `canShortGesture`, so a
    > reader does not look for a second gate.

  - `src/test/kotlin/tribixbite/cleverkeys/PointersShortSwipeCustomOverrideTest.kt` —
    `+    fun noCenter_diagonalFlick_swipeTypingEnabled_emitsCornerSubkey() {`
    > Keep this one. On main `canSwipeType` also requires `mayBeWordSwipe(ptr)`, which a null centre can
    > never satisfy, so `swipeEndCount == 0` is guaranteed twice over now.

  - `src/test/kotlin/tribixbite/cleverkeys/PointersShortSwipeCustomOverrideTest.kt` —
    `+    fun noCenter_flickTowardEmptySlot_emitsNothing() {`
    > Worth adding a sibling with `short_swipes_only = true` in the snapshot: in that mode a flick toward an
    > empty slot on a key that has subkeys must type nothing (not the start key), which is a different code
    > path (`SHORT_SWIPES_ONLY` branch) from the one your test exercises.

  - PR description, "Automated Testing" —
    `scripts/gradle-guard.sh testDebugUnitTest --tests 'tribixbite.cleverkeys.PointersShortSwipeCustomOverrideTest'`
    > That task is disabled on ARM64; this class is in the `runMockTests` list:
    > `scripts/gradle-guard.sh runMockTests -PtestClass=PointersShortSwipeCustomOverrideTest`.

- **APPROVE? [ ]**




---

## #183 — feat(pinyin): composing IME — pack schema, CKPY format, tap engine, swipe feed (gh #177)

- **Proposed action:** request changes (friendly; numbered list). Not mergeable as is.
- **PR state:** base `263bd0ab` head `9d7f79c3` mergeable=CONFLICTING (mergeStateStatus DIRTY) files=24
  (+2934/−12) — author Macho0x (fork branch `feature/pinyin-ime`), opened 2026-09-11, last updated
  2026-09-11, 0 comments, 0 reviews, no `closingIssues` (refs #177 in the body).
- **Evidence (verified at HEAD ac86bda5):**
  - nothing pinyin on main: `rg -il pinyin src/main docs/specs scripts res build.gradle` hits only three word
    corpora (`scripts/norvig_count_1w.txt`, `scripts/en_opensubtitles_full.txt`,
    `scripts/dictionaries/en/en_words.txt`). None of the PR's new files exist at HEAD
    (`docs/specs/pinyin-ime.md`, `scripts/build_phrase_table.py`, `src/main/kotlin/tribixbite/cleverkeys/pinyin/`,
    `src/test/kotlin/tribixbite/cleverkeys/pinyin/`, `SuggestionPinyinHookTest.kt`). The PR describes the spec
    as "updated"; on main it arrives new.
  - churn since the base (`git log --oneline 263bd0ab..HEAD -- <path> | wc -l`): build.gradle 100,
    res/values/strings.xml 59, SuggestionHandler.kt 28, KeyEventHandler.kt 23, CleverKeysService.kt 17,
    LanguagePackManager.kt 7, SuggestionBar.kt 7, docs/TABLE_OF_CONTENTS.md 7, LanguagePackImportTest.kt 6,
    KeyEventReceiverBridge.kt 5, SuggestionBridge.kt 4, scripts/build_langpack.py 4, docs/specs/README.md 3,
    SuggestionProvenance.kt 2. Lead commits confirmed: `7d1a3271` 2026-10-12 (one `handleEditingKey` for every
    Editing command), `68d91874` 2026-10-12 (blind TYPE_NULL/RustDesk fields), `80207612` 2026-10-15 (a
    suggestion tap never deletes in terminal/blind fields), `462c2eab` 2026-10-12 (`LanguagePackSwap`).
  - importer at HEAD (`LanguagePackManager.kt`): results are typed — `ImportResult.Error(val failure:
    PackImportFailure)` (`:725-728`; the sealed class is `src/main/kotlin/tribixbite/cleverkeys/PackImportFailure.kt:15-91`
    with `MissingMember`, `InvalidMember`, `MemberTooLarge`, `DictionaryTooLarge`, …, each rendering a
    localized reason). The PR's `ImportResult.Error("Missing phrases.bin …")` string results (6 new tests
    compare string literals) will not compile. Member size caps at extraction (`:219-223`): `model.onnx` →
    `MAX_MODEL_BYTES`, `manifest.json`/`NOTICE.txt` → 64 KiB, **everything else →
    `CkdtDictionaryReader.MAX_DICTIONARY_BYTES` (16 MiB)**; the whole ZIP is bounded at 64 MiB (`:224`). The
    PR's reader and builder allow a 64 MiB `phrases.bin` (`CkpyPhraseTable.MAX_FILE_BYTES`,
    `build_langpack.py MAX_PHRASES_BYTES`), so the three disagree. There is no name allow-list; instead
    `stageAndSwap()` (`:316-360`) copies an explicit list (`dictionary.bin`, `unigrams.txt`,
    `contractions.json`, `prefix_boost.bin`, `NOTICE.txt`, a declared `model.onnx`, `manifest.json`) — an
    unlisted `phrases.bin` is extracted to temp and silently dropped. The PR's copy hunk targets the old
    inline copy block that no longer exists. `LanguagePackManifest` gained `license`/`attribution`/`source`
    (manifest data class, `attributionField` `:435-440`); `getInstance(context)` exists (`:119`).
  - shipped-pack contract: `LanguagePackImportTest.everyShippedPackImportsAndCarriesItsAttribution`
    (`src/test/kotlin/tribixbite/cleverkeys/langpack/LanguagePackImportTest.kt:627`, mock tier) requires every
    `scripts/dictionaries/langpack-*.zip` to carry `license` = `GPL-3.0-only`, a non-empty `attribution`, an
    `https://` `source` and a `NOTICE.txt` with the change note; `scripts/build_langpack.py` refuses to build a
    pack with no known data source (`DATA_SOURCES` keys `wordfreq aosp-latinime frequencywords swwiki leipzig
    lilak`; `--attribution` is the only route for own data, `:283-300`). The PR's `--input-method pinyin` path
    does not thread through any of this (the base predates `19c132f7`). `docs/audit/2026-09-26-data-licensing-audit.md`
    rows 1-3 are the policy.
  - KeyEventHandler at HEAD: `key_up` Char/String still go straight to `sendText` (`:106-107`), so the text
    hook's placement is still valid. The DEL chain now starts with the inline-field route and the pane
    branches (`:126-142`), then `isClipboardEditMode() && … EDIT_MODE_DISPATCH_KEYS` (`:145`), then
    `learningHooks?.undoSuffix()` (`:152`), then undo-swipe/undo-autocorrect (`:154`, `:156`). A new
    `KeyValue.Kind.Editing -> handleEditingKey(...)` arm (`:181`, `7d1a3271`) handles select/cut/copy/…;
    the PR only intercepts `Kind.Keyevent`. `IReceiver` is at `:1645`;
    `KeyEventReceiverBridgeDelegationTest` exists (mock tier, `build.gradle:1480`).
  - blind/terminal policy at HEAD: `TerminalUtils.EditorKind { TERMINAL, REMOTE }` (`TerminalUtils.kt:73-88`);
    any `TYPE_NULL` editor is REMOTE. The repo rule since `68d91874` is "never rewrite unseen text; taps append
    only". `PinyinController` relies on `setComposingText`, `getTextBeforeCursor` readback and
    `deleteSurroundingText` — exactly what such fields cannot honour — and only gates on the password flag.
  - the Q1 fallback trigger is built on a false premise: on the IME side `setComposingText`/`commitText` return
    the IPC result, never the editor's acceptance (AOSP `IRemoteInputConnectionInvoker`), so `if
    (!conn.setComposingText(...))` will not flip to the committed-text path on a real device; the
    `anEditorThatRefusesComposingGetsTheCommittedTextFallback` test models a mock that returns false, which
    nothing real does.
  - SuggestionHandler at HEAD: `handleSwipePredictionResults` (`:955-966`) gained `origin`, `languages`,
    `commitGuard` (defaulted, so the PR's test call still compiles). The FIRST guard is now the inline-field
    route (`:975-982`, 2026-10-20: a swipe while a clipboard/emoji/GIF search field owns typing goes there and
    nothing else may run); the PR inserts its hook before the password guard, i.e. before that route.
  - SuggestionBridge.onSuggestionSelected (`:95-133`) has the same shape as at the base; the PR's early return
    before ML capture still fits.
  - language source: `Config.primary_language` exists (`Config.kt:939`), but a layout can bind its own
    language since 2026-10-07 (#186/#61; `LayoutsPreference.kt`, `KeyboardComponentGraph.kt`,
    `PredictionCoordinator.kt`), so `{ _config?.primary_language }` can disagree with what the predictors use.
  - strings: `provenance_origin_pinyin` is added to `res/values/strings.xml` only; the sibling
    `provenance_origin_*` strings exist in all 22 locale files (`rg -l … res/values*/strings.xml` = 22).
    `TranslationCoverageDriftTest.newSwipeCopyExistsInEverySupportedLocale` (`:127`) only pins a listed set of
    names and the locale-dir count (21), so the PR compiles and passes; the fallback is English.
  - build.gradle: the PR adds `CkpyPhraseTableTest`/`PinyinSessionTest` to `pureTestClasses` (`:603` at HEAD)
    and `PinyinControllerTest`/`SuggestionPinyinHookTest` to `mockTestClasses` (`:1241`); anchors moved
    (100 commits) — trivial conflicts. `LanguagePackImportTest` has 51 `@Test`s at HEAD (the PR says 35).
  - design read (code quality is good): `CkpyPhraseTable` bounds every length/count, refuses trailing bytes,
    unsorted keys and out-of-order ranks, streams with a 64 MiB ceiling; `PinyinSession` is pure and
    deterministic (exact → completions → greedy longest-match); `PinyinController` keeps a process-lifetime
    scope, async load, no learning, password bypass; `build_phrase_table.py` re-parses its own output. Minor:
    `validatePhraseTable` uses a single `fis.read(header)` (may return fewer than 8 bytes) where
    `validateDictionary` uses `readFully` (`:493`).
  - device check: none (the PR says so: "needs the test phones"); pack data: none (Q4 open — no licensed
    pinyin→Hanzi source chosen).
- **Thread notes (PR description + comments):** Phase 0 (manifest `inputMethod`, `phrases.bin` V1 `CKPY`,
  Python writer, `build_langpack.py --input-method/--phrases`), Phase 1 (`PinyinSession`, `PinyinController`
  with composing region + per-field committed-text fallback, `IReceiver` hooks, bridge gating on panes,
  `SuggestionOrigin.PINYIN`, `getInstalledPack`), Phase 2 (`PinyinComposingHook` at the top of
  `handleSwipePredictionResults`, never auto-commits). Verification claimed 2026-09-11: compile green,
  `runPureTests` 2,400 green except `GeoBenchmarkTest`, listed mock classes green, Python↔Kotlin round-trip.
  Deferred table: device validation, distributable zh-Hans/zh-Hant packs (licence), context ranking/learning,
  space-behaviour setting, CTC `zh` row, lattice segmentation. Open question Q1 (composing vs replace) —
  author recommends keeping composing as default and validating on device. No comments. Issue #177 (same
  author, 2026-08-28) is the design proposal; its only comment is the welcome bot.
- **Audit-doc correction:** `docs/audit/gh-issue-resolution.md:143` and `:180` are accurate. Add: GitHub now
  reports CONFLICTING (the brief had UNKNOWN); the HEAD importer has no name allow-list but a fixed copy list
  in `stageAndSwap`, so an unported `phrases.bin` would be silently dropped rather than refused; and the Q1
  fallback cannot trigger on a device because IME-side `InputConnection` results are IPC results.
- **Draft review (request changes):**

> Thank you for this. It is a serious piece of work: the CKPY reader bounds every untrusted field, the
> session is pure and deterministic, the pack contract is explicit, and the tests pin the byte layout. I want
> this in, and #177 is the right design for Chinese on this keyboard.
>
> It cannot go in as it stands, mostly because main moved a long way in the six weeks since your base
> (`263bd0ab`), and a few of the moves change your integration points. Here is what I need, in order:
>
> 1. **Rebase onto main.** `build.gradle`, `strings.xml`, `SuggestionHandler`, `KeyEventHandler`,
>    `CleverKeysService` and `LanguagePackManager` all changed under you; GitHub shows the conflict.
> 2. **Typed import failures.** `ImportResult.Error` now takes a `PackImportFailure` (sealed, each case
>    renders a localized reason), not a string. Your three new refusals need cases — reuse
>    `MissingMember("phrases.bin")` / `InvalidMember("phrases.bin")`, and add one for an unsupported
>    `inputMethod` — plus the strings they render. The six new `LanguagePackImportTest` assertions follow.
> 3. **Make the size caps agree.** The importer caps any member it does not know at 16 MiB and the whole ZIP
>    at 64 MiB; your reader and builder allow a 64 MiB `phrases.bin`. Add a `phrases.bin` entry to the
>    `memberCap` table and use one number in all three places. Unless a real table needs more, 16 MiB is my
>    preference — tell me what a CC-CEDICT-sized table actually weighs.
> 4. **Port the copy into `stageAndSwap()`.** Since `462c2eab` the install is a staging dir swapped into place,
>    and only listed members are copied; an unlisted `phrases.bin` is extracted and silently dropped today.
> 5. **Stay inactive in blind and terminal fields.** Since `68d91874` the keyboard treats RustDesk/VNC and any
>    `TYPE_NULL` editor as a blind field (`TerminalUtils.editorKind`): it never rewrites text it cannot see.
>    Composing, readback and `deleteSurroundingText` are exactly that, so the controller must switch off there
>    the way it does for passwords.
> 6. **Rethink the Q1 fallback trigger.** On the IME side `setComposingText`/`commitText` return whether the
>    IPC went through, never whether the editor accepted it, so `if (!conn.setComposingText(...))` will not
>    flip to the committed-text path on a real phone. Keep both paths, but decide by a readback check after the
>    first composing write, or an explicit switch, and let the device check pick the default.
> 7. **Hook placement after the inline-field route.** Since 2026-10-20 a swipe or key while a clipboard/emoji/
>    GIF search field owns typing goes to that field first (`SuggestionHandler.handleSwipePredictionResults`,
>    `KeyEventHandler.key_up`); your hooks must sit after that route. Also cover `KeyValue.Kind.Editing`
>    (`7d1a3271`): an editing command during a composing run should flush or cancel the run first.
> 8. **Language source.** A layout can bind its own language since 2026-10-07, so `_config.primary_language`
>    alone can disagree with what the predictors use; read the same resolved language they do.
> 9. **Licence plumbing for the pack.** Every shipped pack now carries `license`/`attribution`/`source` and a
>    `NOTICE.txt` (`19c132f7`), the builder refuses to build without a known data source, and a test imports
>    every `scripts/dictionaries/langpack-*.zip` and checks those fields. `--input-method pinyin` needs a
>    `DATA_SOURCES` row for the phrase data and the NOTICE text, and the first zh pack must pass that test.
> 10. **A pack to test with, then a device check.** I cannot validate the composing region without data. A
>     small table is enough to start (a few thousand syllables and common phrases from a CC-licensed source
>     such as CC-CEDICT), as long as its licence fields are right. Once there is one I will run it on my test
>     phones in a few editors (Chrome, a messenger, Termux, a `TYPE_NULL` remote field) and report back here;
>     if you have a device, a short recording helps.
>
> Smaller things are inline. The `provenance_origin_pinyin` string is English-only for now; that is fine, I
> can fill the 21 translations.

- **Line comments (text):**

  - `src/main/kotlin/tribixbite/cleverkeys/langpack/LanguagePackManager.kt` —
    `+                return ImportResult.Error("Unsupported inputMethod \"${manifest.inputMethod}\"")`
    > On main `ImportResult.Error` takes a `PackImportFailure` (see `PackImportFailure.kt`); each case has a
    > localized reason the pack manager shows. Add an `UnsupportedInputMethod(value)` case here and use
    > `MissingMember(PHRASES_FILE)` / `InvalidMember(PHRASES_FILE)` for the other two refusals.

  - `src/main/kotlin/tribixbite/cleverkeys/langpack/LanguagePackManager.kt` —
    `+                    phrasesFile.copyTo(File(stagingDir, PHRASES_FILE), overwrite = true)`
    > The copy block you patched no longer exists; main installs through `stageAndSwap()` (staging dir +
    > `LanguagePackSwap`). Put the copy there, right after `dictFile.copyTo`, so a half-written pack can
    > never become "complete" without its table.

  - `src/main/kotlin/tribixbite/cleverkeys/langpack/LanguagePackManager.kt` — the extraction loop's
    `val memberCap = when (fileName) { MODEL_FILE -> …; MANIFEST_FILE, NOTICE_FILE -> 64L * 1024; else -> CkdtDictionaryReader.MAX_DICTIONARY_BYTES }`
    > `phrases.bin` falls into `else` (16 MiB) while `CkpyPhraseTable.MAX_FILE_BYTES` and
    > `build_langpack.py MAX_PHRASES_BYTES` say 64 MiB — and the whole ZIP is capped at 64 MiB, so a 64 MiB
    > table can never fit beside the dictionary anyway. One shared constant, please.

  - `src/main/kotlin/tribixbite/cleverkeys/langpack/LanguagePackManager.kt` —
    `+                val header = ByteArray(8)` / `+                if (fis.read(header) != 8) return false`
    > `read` may return fewer bytes than asked; `validateDictionary` on main uses
    > `DataInputStream(fis).readFully(header)` — same here.

  - `src/main/kotlin/tribixbite/cleverkeys/pinyin/PinyinController.kt` —
    `+            if (!conn.setComposingText(s.composingText, 1)) {`
    > This never fires on a device: the IME's `InputConnection` is a remote proxy whose boolean is the IPC
    > result (AOSP `IRemoteInputConnectionInvoker`), not the editor's answer. The fallback needs a different
    > trigger (readback mismatch after the first composing write, or a setting); your mock test models
    > something no real editor does.

  - `src/main/kotlin/tribixbite/cleverkeys/pinyin/PinyinController.kt` —
    `+    fun onStartInputView(isPasswordField: Boolean) {`
    > Please also take the editor kind. `TerminalUtils.editorKind(editorInfo, config.custom_terminal_packages)`
    > returns `TERMINAL`/`REMOTE` for terminals, RustDesk/VNC and every `TYPE_NULL` field; the controller must
    > stay inactive for those exactly as it does for passwords — composing, `getTextBeforeCursor` readback and
    > `deleteSurroundingText` are the operations those fields cannot honour.

  - `src/main/kotlin/tribixbite/cleverkeys/pinyin/PinyinController.kt` —
    `+        if (conn.getTextBeforeCursor(text.length, 0)?.toString() == text) {`
    > Good that you verify before deleting. Note that on main the English replace path now also runs its edits
    > through an `EditorCommitGuard` (verified selection + acknowledgement); when the pinyin commit path is
    > rebased it should use the same guard rather than a bare `commitText`.

  - `src/main/kotlin/tribixbite/cleverkeys/KeyEventHandler.kt` —
    `+                } else if (key.getKeyevent() == KeyEvent.KEYCODE_DEL && recv.pinyinHandleBackspace()) {`
    > On main the DEL chain starts with the inline-field and pane branches (`isInlineFieldActive()`, tag, edit,
    > search, emoji, GIF) and then `learningHooks?.undoSuffix()`. Your branch belongs after the pane branches
    > and before the suffix undo. Separately, `KeyValue.Kind.Editing -> handleEditingKey(...)` (`7d1a3271`) is
    > a new arm your hooks do not see; select-all/cut/paste during a composing run should flush it first.

  - `src/main/kotlin/tribixbite/cleverkeys/wiring/KeyEventReceiverBridge.kt` —
    `+        if (isClipboardTagMode() || isClipboardEditMode() || isClipboardSearchMode() ||` /
    `+            isEmojiPaneOpen() || isGifPaneOpen()) {`
    > Add the inline-field condition that main introduced on 2026-10-20 (the inline editor owns typing); the
    > five checks here are its predecessors.

  - `src/main/kotlin/tribixbite/cleverkeys/SuggestionHandler.kt` —
    `+        pinyinHook?.let { hook ->` / `+            if (hook.onSwipeDecoded(predictions ?: emptyList(), scores ?: emptyList())) return`
    > On main the first thing this method does is route the swipe to an active inline field
    > (`inlineTargetProvider`) — a swipe in the emoji or clipboard search box must land there, not in a pinyin
    > buffer. Place the hook after that route and before the password guard, as you intended.

  - `src/main/kotlin/tribixbite/cleverkeys/CleverKeysService.kt` —
    `+            languageProvider = { _config?.primary_language },`
    > A layout can carry its own language binding now (#186/#61), so this can disagree with what the
    > predictors resolve. Use the same source they use.

  - `scripts/build_langpack.py` —
    `+    parser.add_argument('--input-method', choices=['wordfreq', 'pinyin'], default='wordfreq',`
    > Since `19c132f7` this script also writes `license`/`attribution`/`source` and `NOTICE.txt`, and refuses
    > to build when nothing identifies the data's origin (`DATA_SOURCES`/`PACK_SOURCES`, or `--attribution`).
    > A pinyin pack needs a `DATA_SOURCES` row for its phrase data (licence, credit, link, NOTICE block) so
    > `everyShippedPackImportsAndCarriesItsAttribution` passes for `langpack-zh-*.zip`.

  - `src/test/kotlin/tribixbite/cleverkeys/langpack/LanguagePackImportTest.kt` —
    `+            ImportResult.Error("Missing phrases.bin for inputMethod \"pinyin\"")`
    > Will become `ImportResult.Error(PackImportFailure.MissingMember("phrases.bin"))` after item 2; the class
    > has 51 tests on main now, so expect a few more anchor moves.

  - `res/values/strings.xml` —
    `+    <string name="provenance_origin_pinyin">Pinyin candidate</string>`
    > The other `provenance_origin_*` strings exist in all 21 locale files. English fallback is acceptable for
    > the first cut; I can add the translations.

  - `docs/TABLE_OF_CONTENTS.md` —
    `+| `pinyin-ime.md` | **UPDATED 2026-09-11** Pinyin composing IME …`
    > The spec is new on main (there was no earlier version); "NEW".

- **APPROVE? [ ]**




---

## #182 — Add Arabic language Pack "50.000 Words"

- **Proposed action:** request changes (provenance + licence fields; one command fixes the archive if the
  source is wordfreq). Mergeable path afterwards: merge into `scripts/dictionaries/` and mirror on the
  `langpacks` release.
- **PR state:** base `263bd0ab` head `5b742a1f` mergeable=MERGEABLE (mergeStateStatus CLEAN) files=1 (+0/−0,
  binary `scripts/dictionaries/langpack-ar.zip`, 558,646 bytes, blob sha `fa9a08d0`, archive sha256
  `3383672c…3dd2aa`) — author Fouez-Mahrouk (fork branch `main`), opened 2026-09-10, 0 comments, 0 reviews.
- **Evidence (verified at HEAD ac86bda5):**
  - archive contents (downloaded via `gh api …/contents/…?ref=5b742a1f`, no fetch): `dictionary.bin`
    (1,571,802 B) and `manifest.json` (119 B) only — **no `NOTICE.txt`, no `unigrams.txt`**. Entry timestamps
    are 1980-01-01 (the deterministic `ZIP_EPOCH` of `build_langpack.py`), so it was built with the repo's
    builder, before `19c132f7` (2026-09-26) added the licence fields.
  - `manifest.json`: `{"code":"ar","name":"Arabic","version":1,"author":"","wordCount":50000,"hasPrefixBoost":false}`
    — no `license`, `attribution` or `source`; `author` empty.
  - `dictionary.bin` header parsed against `CkdtDictionaryReader.kt:107-128` / `build_dictionary.py:299-306`:
    magic `0x54444B43` ("CKDT"), version 2, lang `ar`, **wordCount 50,000** (cap `MAX_WORD_COUNT` = 100,000,
    `CkdtDictionaryReader.kt:42`), canonical offset 48, normalized section 47,732 entries; file 1.5 MB ≤ 16 MiB
    (`MAX_DICTIONARY_BYTES`). It would pass `validateDictionary` (`LanguagePackManager.kt:486-511`) and the
    #184 bounds; `ar` matches `VALID_PACK_CODE` (`LanguagePackSwap.kt:102`).
  - content: 50,000 distinct words, 0 duplicates; lengths 2-13; ranks 0-172 (143 distinct). Top entries
    `في من على أن لا إلى ما عن هذا مع` — a frequency-ranked list. **49,306 Arabic-script words and 694
    Latin-only tokens** (`the http of and to in by on for de at https al is no with la you from my … google
    march … bahrain … pdf`, first at rank position 329) — web/Twitter noise typical of wordfreq's `ar` list,
    which is the likely source (unconfirmed; the PR says only "Adding Arabic language pack" + a screenshot).
  - what main requires of a shipped pack: `docs/audit/2026-09-26-data-licensing-audit.md` rows 1-3 (`19c132f7`
    build + repack, `638d492b` docs) — manifest `license`/`attribution`/`source` and a `NOTICE.txt` member;
    enforced by `LanguagePackImportTest.everyShippedPackImportsAndCarriesItsAttribution`
    (`src/test/kotlin/tribixbite/cleverkeys/langpack/LanguagePackImportTest.kt:627`, mock tier), which scans
    every `scripts/dictionaries/langpack-*.zip` — **merging this file as is turns that test red**.
  - the fix path exists in the repo: `scripts/build_langpack.py --repack ZIP` rewrites an archive with licence
    metadata + `NOTICE.txt` keeping payload members byte-identical (`:723-726`); flags verified at
    `:727-735`: `--data-source {aosp-latinime,frequencywords,leipzig,lilak,swwiki,wordfreq}` (repeatable),
    `--license`, `--attribution` ("mandatory for a pack built from your own data"), `--source`. There is no
    `PACK_SOURCES` row for `ar` (`:252-262` has `en`, `en-*`, `sw`, `fa`, …), so `--data-source` must be
    passed. The builder refuses a pack with no identified origin (`:289-295`).
  - reference shape of a current pack (`scripts/dictionaries/langpack-fa.zip`, committed `2beac893`):
    `NOTICE.txt`, `dictionary.bin`, `manifest.json` (with `license`, 400-char `attribution`, `source`),
    `unigrams.txt`. `unigrams.txt` is optional (`getUnigramsPath` returns null when absent, `:554-557`).
  - where packs live: `scripts/dictionaries/langpack-*.zip` in git AND mirrored on the `langpacks` release
    (`README.md:310-311`, `:422`; audit scope line) — **the brief's lead "published on the release, not in
    git" is wrong; the PR's path is correct**, the release mirror is the maintainer's step. No `ar` pack
    exists at HEAD in either place (23 committed zips; release assets = the same 23 incl. `fa`).
  - Arabic layouts ship at HEAD: `src/main/layouts/arab_pc.xml`, `arab_alt.xml`, `arab_pc_hindu.xml`,
    `arab_hamvaj_tly.xml` (+ `arab_pc_ckb*.xml`, `arab_pc_ir.xml`). Tap + geometric swipe work for any
    imported script since `9ae7005c`; CTC stays blocked for Arabic (guide §4). `scripts/build_wordlist.py
    LANG_CONFIG` has no `ar` row (the `fa` row, `:257-269`, uses an Arabic-block alphabet and
    `foreign=("ar",)`), so a script-filtered rebuild would be a new row, not a flag.
  - Settings path for the import (verified: `settings_section_multilang` = "Multi-Language",
    `multilang_packs_header` = "Language Packs", `multilang_import_pack` = "Import Pack"):
    Settings → Multi-Language → Language Packs → Import Pack.
  - device check: none (not by the author beyond the screenshot, not here).
- **Thread notes (PR description + comments):** body is one line ("Adding Arabic language pack.") plus a
  screenshot of the pack listed in the app. No source, licence or build command stated; no comments. Issue
  #88 ("Please add the Arabic language option", 2026-01-25) has only the welcome bot; the pack is the missing
  piece for it.
- **Audit-doc correction:** `docs/audit/gh-issue-resolution.md:160` cites `638d492b` as the data-licensing
  audit — that hash is the docs commit; the remediation (builder flags, repack, the enforcing test) is
  `19c132f7`. Add to `:181`: the archive is a valid CKDT V2 at 50,000 words (within the #184 cap), lacks
  `NOTICE.txt`/licence keys and `unigrams.txt`, and carries 694 Latin tokens.
- **Draft review (request changes):**

> Thanks for building this — Arabic has been asked for since January (#88) and the archive itself is sound:
> a valid V2 dictionary with 50,000 words, which is inside the import limits, and the Arabic PC layout is
> already in the app, so tap and swipe will work with it.
>
> I can't merge it yet because of where the words came from. Since late September every pack in this repo has
> to say so inside the archive: `license`, `attribution` and `source` in `manifest.json` plus a `NOTICE.txt`
> with the credits. Yours has none of them (and a test that imports every shipped pack would fail on it).
>
> Two questions, then one command:
>
> 1. Where is the list from, and under what licence? From the top entries and the ~700 Latin tokens in it
>    (`the`, `http`, `google`, `pdf`…) it looks like wordfreq's Arabic list — is that right, and which
>    version/command did you use?
> 2. Do you want your name in the `author` field? It is empty at the moment.
>
> If it is wordfreq, you don't need to rebuild anything; this adds the metadata and keeps the dictionary
> byte-identical:
>
> ```
> python3 scripts/build_langpack.py --repack scripts/dictionaries/langpack-ar.zip \
>     --data-source wordfreq --author "Your Name" --output scripts/dictionaries/langpack-ar.zip
> ```
>
> If the data is your own or from somewhere else, pass `--license`, `--attribution` and `--source` instead and
> tell me the origin here. Please commit the result to the same path.
>
> Optional, separate from the licence: the Latin tokens would disappear and you'd get a `unigrams.txt` (used
> for language detection) by adding an `ar` row next to `fa` in `scripts/build_wordlist.py` and following
> `docs/guides/adding-a-new-language.md`. Happy to take that as a follow-up or do it myself.
>
> Once the metadata is in I'll merge, upload the file to the `langpacks` release so people can install it
> without a checkout, and test it on a phone: Settings → Multi-Language → Language Packs → Import Pack, with
> the Arabic (PC) layout. That would let me close #88 as well.

- **Line comments (text):**

  - `scripts/dictionaries/langpack-ar.zip` → `manifest.json` —
    `{"code":"ar","name":"Arabic","version":1,"author":"","wordCount":50000,"hasPrefixBoost":false}`
    > Needs `license`, `attribution` and `source` (see any other pack here, e.g. `langpack-fa.zip`), and a
    > `NOTICE.txt` member. `--repack` adds them without touching `dictionary.bin`.

  - `scripts/dictionaries/langpack-ar.zip` → `dictionary.bin` (header: CKDT v2, lang `ar`, 50,000 words)
    > Format and size are fine. 694 of the 50,000 entries are Latin words (`the`, `http`, `of`, …); a
    > script-filtered rebuild (an `ar` row in `scripts/build_wordlist.py`) would drop them. Not a blocker.

- **APPROVE? [ ]**




---

## #164 — Fix #161: Apply landscape keyboard height dynamically on orientation change

- **Proposed action:** close as superseded (with thanks).
- **PR state:** base `a575f74a` head `d08ce45f` mergeable=MERGEABLE (mergeStateStatus CLEAN) files=1 (+19/−1,
  `src/main/kotlin/tribixbite/cleverkeys/CleverKeysService.kt`) — author FarzanArzoiy (fork branch `main`),
  opened 2026-07-23, `updatedAt` 2026-10-16, 1 comment (gemini-code-assist sunset notice, 2026-07-23), 0 reviews,
  `closingIssues` = #161. GitHub's CLEAN is textual only: the hunks apply, but the code they call is gone.
- **Evidence (verified at HEAD ac86bda5):**
  - superseding commit: `a9c22871` 2026-09-03 "fix(settings): landscape keyboard height no longer stomped by
    the portrait slider (#161)" — ancestor of HEAD: yes; tags: none (unreleased, ships in v2.0.0).
  - source at HEAD: `src/main/kotlin/tribixbite/cleverkeys/ui/settings/SettingsPersistence.kt:535-536` —
    "`keyboardHeightPercent` is deliberately NOT set here — it is orientation-resolved"; the portrait mirror
    assignment that caused #161 is gone. `Config.kt:1112-1124` — `Config.refresh` reads the orientation and
    resolves `keyboardHeightPercent` through `KeyboardHeightPrefs.percent(prefs, landscape, unfolded)`.
    `CleverKeysService.kt:1135-1140` — `onConfigurationChanged` calls `refresh_config()`, so rotation
    re-resolves it. Later `a6f870d6` (#115, foldable inner screen) and `35034af6` build on the same rule.
  - the PR would not compile: it calls `_neuralLayoutBridge.calculateDynamicKeyboardHeight()`;
    `NeuralLayoutBridge` was deleted in `64f401d2` 2026-08-18 ("delete the neural engine…", ADR-011; rename
    pass `eb430fa0` the same day). `rg -n NeuralLayoutBridge src/` finds only two comments
    (`CleverKeysService.kt:1349`, `KeyboardDimensionsHelper.kt:30`); no `_neuralLayoutBridge` field exists.
  - the PR's approach recomputed the view height on every height-pref change and on rotation; the actual root
    cause (`updateConfigFromSettings` writing `keyboardHeightPercent = keyboardHeight` after every save, racing
    `Config.refresh`) was left in place, so it treated the symptom.
  - tests: `src/test/kotlin/tribixbite/cleverkeys/KeyboardHeightOrientationDriftTest.kt` — 3 methods
    (`updateConfigFromSettings_doesNotStompOrientedKeyboardHeight` `:41`,
    `configRefresh_resolvesOrientationScopedHeightThroughTheSharedRule` `:70`,
    `appearanceSliders_writeDistinctOrientationKeys` `:92`); **source-scan drift test, pure JVM**
    (`build.gradle:767`, `pureTestClasses`). It pins the absence of the stomp and the slider keys, not the
    rendered height.
  - device check of `a9c22871`: not recorded (the #161 row says "comment + close on v2.0.0"; nothing device-side
    is cited). The PR author's own manual tests (Galaxy A31, Android 12) were of the PR, not of `a9c22871`.
    Owed before the tag: rotate with different portrait/landscape values on a phone.
- **Thread notes (PR description + comments):** description: landscape slider did nothing, portrait slider
  controlled both; root cause stated as "height calculated once and never updated"; adds
  `refreshKeyboardHeight()` via `NeuralLayoutBridge`, called from `onConfigurationChanged()` and from
  `onSharedPreferenceChanged()` for the four `keyboard_height*` keys; manual tests on a Galaxy A31 all ticked;
  "Closes #161". Only comment: the Gemini bot's sunset notice. Issue #161 (2026-07-21, v1.5.0, Android 12,
  two recordings) has only the welcome bot as a comment — the closing reply there belongs to the #161 item,
  not this PR.
- **Audit-doc correction:** none — `docs/audit/gh-issue-resolution.md:64`, `:115`, `:182` are accurate
  (superseded by `a9c22871`; no longer compiles). The scratch audit's `SettingsPersistence.kt:494` is now
  `:535`.
- **Draft close note:**

> Thanks for digging into this one, and for the careful manual run on the A31 — you found the user-visible
> half of the problem exactly.
>
> I'm closing this as superseded rather than merging it. The root cause turned out to be one line in the
> settings code: after every save it wrote the portrait slider's value into the field `Config.refresh()` had
> just resolved for the current orientation, so the portrait slider won every time and the landscape slider
> looked dead. `a9c22871` removes that write and leaves `Config.refresh()` as the only writer, which also
> covers rotation (the service already refreshes config in `onConfigurationChanged`). That fix is on main and
> will ship in v2.0.0.
>
> Your branch also calls `NeuralLayoutBridge`, which was removed in August (`64f401d2`), so it would no longer
> build as is.
>
> The fix itself is tracked in #161; once v2.0.0 is out, please retest there with different portrait and
> landscape heights and reopen that issue if anything is still off.

- **APPROVE? [ ]**




---
