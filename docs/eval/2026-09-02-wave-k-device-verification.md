# Wave-K device verification — 2026-09-01 (run), filed 2026-09-02

Campaign: `docs/plans/2026-08-30-full-backlog-campaign.md` row K. Ledger items ARC-068,
ARC-069 (scriptable subset), ARC-070. Operator: automated adb session under the
maintainer's campaign authorization (supersedes the build-install-only ADB policy for
this run only). Evidence artifacts live in the session scratchpad
`…/scratchpad/wavek/` (inventory at the end); they are NOT committed to the repo.

## Devices

| device | serial | result |
|---|---|---|
| Pixel 8 Pro | 192.168.0.216:5555 | **UNREACHABLE for the whole session** — `adb connect` fails with ARP-level "No route to host"; host absent from `nmap -sn` sweeps of both 192.168.0.0/24 and 192.168.1.0/24; no host on either subnet exposes port 5555 besides the Saga (the only 5555 hit on 192.168.0.x was an Amazon Fire TV, AFTGAZL, at .173). A reconnect prober retried every 60 s for 45 min (17:09–17:53 EDT), 45/45 failures. Every pixel-only item is skipped and listed under "not reachable". |
| Saga (SM_S938U1-class test phone) | 192.168.1.243:5555 | Full protocol executed. Android 13 (TKQ1.221220.425), 1080×2400 @ 420 dpi, Magisk root available. |

## APK provenance (matters — read first)

- 17:10 EDT: installed `build/outputs/apk/release/CleverKeys-v1.6.0-arm64-v8a.apk`
  (Aug 29 build, sha256 `7f4f2ef7c894…`) over the device's v1.4.0 (104002). Verified
  installed-base.apk sha == local file sha.
- **Finding: that Aug 29 "release" APK has `ENABLE_VERBOSE_LOGGING=false`** — zero
  `CKMemProbe` lines on a fresh process cold start (KeyboardComponentGraph marks
  `init.enter` unconditionally when the flag is true), and the
  `if (BuildConfig.ENABLE_VERBOSE_LOGGING)`-gated CTC logs are absent, while ungated
  `Log.d` (ContractionManager etc.) flow normally. `build-on-termux.sh` exports
  `LOCAL_BUILD=true`, so this is the documented const-inlining trap (stale
  `build/tmp/kotlin-classes/release`) or a distribution build.
- Fix: purged `build/tmp/kotlin-classes/release`, rebuilt via
  `gradle-guard.sh assembleRelease` with `LOCAL_BUILD=true` (first attempt had died on
  R8 `OutOfMemoryError: Metaspace` under the guard's 256 m default; the successful run
  used 2048m/1024m). Fresh APK: mtime Sep 1 17:23, 30,262,371 B, sha256
  `6894b2cce616…`.
- 17:24 EDT: installed the fresh APK (`install -r`), verified device sha matches. All
  evidence below is from the fresh (verbose) build unless marked "stale-APK recon".

## Saga before-state (captured 17:09:42 EDT, `saga-before-state.txt`)

| item | value |
|---|---|
| default_input_method | `com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME` |
| enabled_input_methods | googletts `VoiceInputMethodService` : Gboard `LatinIME` (CleverKeys NOT enabled) |
| uimode night | yes |
| debug.hwui.show_dirty_regions | (unset/empty) |
| foreground | `com.google.android.googlequicksearchbox/.InternalGoogleAppActivityEntrypoint` |
| CleverKeys installed | versionName=1.4.0 versionCode=104002 |

## Check results

### a. ARC-070 — memory over an exercised session (VERDICT: no unbounded growth; front-loaded plateau)

Cold-start attribution (CKMemProbe, fresh process pid 12694, 17:24:26,
`saga-ckmemprobe-coldstart.txt`):

```
~ init.enter                      used=8.8MB             limit=256.0MB
~ init.contractionManager         used=15.6MB  delta=+6.8MB   | known=1232
~ init.predictionCoordinator      used=22.7MB  delta=+6.5MB
~ init.done                       used=22.8MB
~ primary.dictionary              used=55.3MB  delta=+32.5MB  | words=98140 setEntries=293787
~ ctc.baseParse                   used=79.1MB  delta=+23.8MB  | entries=98140
~ ctc.mergeAndOrdinals            used=106.0MB delta=+26.9MB
~ ctc.trie                        used=135.9MB delta=+29.9MB  | words=98467 nodes=231140
```

`dumpsys meminfo` trajectory (KB, PSS), ~18-minute window, exercise = 5-swipe rounds
(sendevent word paths + straight-line swipe + `input text` + backspaces) plus emoji
pane open/close:

| sample | time | Native Heap | Dalvik Heap | TOTAL PSS | TOTAL RSS |
|---|---|---|---|---|---|
| t0 (2 swipes done) | 17:25:46 | 41,249 | 71,956 | 155,651 | 255,384 |
| t+5 (post burst 1: 3 rounds + emoji pane) | 17:30:30 | 78,526 | 151,460 | 281,489 | 383,596 |
| t+13 idle | 17:38:51 | 79,147 | 71,779 | 202,201 | 305,120 |
| post burst 2 (2 rounds + pane) | 17:39:45 | 90,254 | 127,469 | 268,451 | 371,360 |
| settle 3 (3 min idle) | 17:43:03 | 87,265 | 62,982 | 201,887 | 303,532 |
| post burst 3 (2 rounds) | 17:43:41 | 88,945 | 138,437 | 280,956 | 382,660 |

Reading: Dalvik oscillates with GC (bursts leave ~70–80 MB of garbage that idle GC
fully reclaims — settled alloc returns to ~57–66 MB against the 256 MB limit every
time). Native heap grows 41→79 MB during the first burst (ONNX/XNNPACK workspaces +
emoji glyph/pane caches), +11 MB on the second burst, then **+1.7 MB on the third** —
front-loaded cache/arena warm-up that plateaus at ~87–90 MB, not per-swipe monotonic
growth. Settled TOTAL PSS stabilizes at ~202 MB (two independent idle samples 4 min
apart: 202,201 vs 201,887). Nothing in the window suggests a leak; a multi-hour soak
remains the maintainer's stronger test.

### b. Swipe decode e2e (VERDICT: works, screenshot + log evidence)

- Straight-line `input swipe 487 1750 911 1750 350` (t→o) decoded and committed "To",
  suggestion bar `To | Too | Ti | Yo | Top` (stale-APK recon, `saga-03-after-swipe1.png`).
- Multi-key word path via root sendevent injector (w→o→r→l→d): committed "world",
  suggestion bar `world | would | works | worked | worlds`
  (`saga-07-two-swipes.png`, `saga-07-crop-sugg.png`). Verbose pipeline log
  (`saga-first-swipe-decode.txt` and the 17:25:25 window):

```
17:25:25.091 D Pointers: === onTouchUp START … Path: SWIPE_TYPING completion
17:25:25.092 D SwipeRecognizer: Using traditional keys: 19
17:25:25.110 D SuggestionHandler: Added 1 possessive forms to predictions
17:25:25.114 D SuggestionHandler: Committing text: len=6
```

- Decoder sanity cross-check: a path physically drawn over b→a→l→l (bad coordinate
  map, later corrected) decoded exactly "ball" with `ball's | ball | balls | ballot |
  ballet` — the engine decodes what is actually drawn (`saga-06-hello-swipe.png`).
- **Second language: SKIPPED.** The device has a single system layout
  (`layouts=[{"kind":"system"}]`); switching languages is not reachable via visible UI
  taps without changing user prefs (forbidden on Saga), and the pixel (where pref
  changes were allowed) is down. Italian swipe remains on the maintainer list.

### c. ARC-069 first-swipe warm-up (VERDICT: negligible penalty; warm-up is at init)

Fresh IME process (force-stop → refocus, pid 12694, process start 17:24:26):

| swipe | gesture UP | text committed | latency |
|---|---|---|---|
| first of process ("Help", h-path) | 17:24:53.700 | 17:24:53.761 | **61 ms** |
| second ("world") | 17:25:25.091 | 17:25:25.114 | **23 ms** |

The ONNX session (`ModelLoader: ✅ CtcEncoder session created (XNNPACK)`) and the CTC
trie (`ctc.trie` CKMemProbe mark) are built during service init, ~27 s of wall clock
before the first swipe was physically possible, so the first swipe pays only ~40 ms of
extra path (first ORT `run()` warm-up), not a model load. No user-perceivable
first-swipe stall on this hardware.

### d. ARC-068 #79 — hwui dirty-regions discriminator (VERDICT: neither failure signature reproduces)

Method: `setprop debug.hwui.show_dirty_regions true` → force-stop → relaunch
SettingsActivity (prop is read at renderer init) → 12 s `screenrecord` with
idle(3.5s)/scroll(×3)/idle(3.5s) phases → prop restored to empty → frames extracted.

- **Idle phases emit no frames at all**: the 12 s wall-clock recording contains only
  6.63 s / 254 frames of encoded video — screenrecord only receives frames when the
  surface repaints, so a recomposition/invalidation storm at idle would have produced
  continuous full-rate frames. It did not. (`saga-dirty-regions-fresh.mp4`)
- Scroll frames show the full moving list repainting (normal for a scroll); settle
  frames (`saga-dirtyF-end.png`) show **zero dirty-region tint** — no whole-content
  flashing after input stops (recomposition storm fix holds) and **no status-bar-strip
  -only flashing** in any extracted frame (the insets-conflict signature did not
  appear on this device).
- Stale-APK recon recording (`saga-dirty-regions.mp4`, frames `saga-dirty-f1..f3`)
  matches: full-area tint only while actively scrolling, quiet when settled.
- Procedure gotcha recorded for the next run: the prop also needs a process restart to
  CLEAR — one interim capture taken after `setprop false` without restart still showed
  the overlay and was retaken.
- Cosmetic observation: the list card under the scroll-gesture start point holds its
  pressed/ripple highlight for several seconds (`saga-dirtyF-4.5.png`) — touch
  feedback from the injected swipe, not a dirty-region artifact; possibly worth a
  glance at ripple duration.

### e. #148 visual (ARC-069) — read-only on Saga (VERDICT: defaults confirmed; repro not run)

Read-only root inspection of the device-protected store
(`/data/user_de/0/tribixbite.cleverkeys/shared_prefs/tribixbite.cleverkeys_preferences.xml`):
neither `word_prediction_enabled` nor `swipe_typing_enabled` is present → both at
default (ON), consistent with the live suggestion bar and working swipe decode.
Toggling them off to reproduce #148 changes user state and was authorized only on the
pixel, which is unreachable — **repro not executed**. Related but not #148 proper: the
emoji search pane opens ABOVE the still-visible keyboard and returns cleanly
(`saga-08-pane-try1.png`, `saga-11-check.png`).

### f. Light + dark screens (ARC-069/#35/UT-1) (VERDICT: activities correct; two findings)

Dark (`cmd uimode night yes`, device's original mode): keyboard, Settings, Dictionary
Manager all render correctly (`saga-dark-keyboard.png`, `saga-dark-settings.png`,
`saga-dark-dictmgr.png` — Dictionary Manager shows ACTIVE (97960) / DISABLED (0) /
USER DICT (0) / CUSTOM (1)).

Light (`cmd uimode night no`): Settings and Dictionary Manager render as proper light
themes (`saga-light-settings.png`, `saga-light-dictmgr.png`).

Findings:
1. **The keyboard stays dark purple in system light mode** (`saga-light-keyboard.png`).
   If the default theme is meant to follow the system ("system" theme), this is a bug;
   if the default is a fixed purple theme, it is by design — maintainer call.
2. **DictionaryManagerActivity loses its tab counts on configuration change**: after
   the uimode switch recreated the existing instance, all four tabs displayed "(0)"
   while the word list itself was still fully populated (compare
   `saga-dark-dictmgr.png` ACTIVE (97960) vs `saga-light-dictmgr.png` ACTIVE (0)).
   Counts are evidently loaded once and not re-derived on recreation.

Original night mode (yes) restored afterwards.

## Restore-state verification (17:48:34 EDT, `saga-restore-verification.txt`)

| item | before | after restore | match |
|---|---|---|---|
| default_input_method | Gboard LatinIME | Gboard LatinIME | ✓ |
| enabled_input_methods | voice + Gboard (no CleverKeys) | voice + Gboard (CleverKeys disabled) | ✓ |
| uimode night | yes | yes | ✓ |
| debug.hwui.show_dirty_regions | (unset) | `[]` (empty) | ✓ |
| /data/local/tmp/wavek-gesture.sh | absent | removed (`No such file`) | ✓ |
| CleverKeys version | 1.4.0 | **1.6.0 (fresh verbose build) — intentional, left for the maintainer soak** | by design |
| foreground | quicksearchbox screen | launcher (HOME) | ~ (original transient screen not restorable) |

Residual state that could not be reverted without forbidden actions (no app-data
clear): UserVocabulary/personalization learned a handful of test words (logcat shows
"Saved 1/4 words to storage" events; committed test words included Help, world, test,
sept, swot, ball, quick, brown, fox); Messaging's "New chat" draft was discarded by
force-stop (field verified empty). No emoji were actually selected, no messages sent,
no settings values changed.

## Not reachable via adb (stays on the maintainer soak list)

- Collision DIALOG rendering with a real pack re-selection (campaign note).
- `.ckenc` passworded export UX (campaign note).
- Pre-v1.6.0 / pre-v1.1.86 upgrade paths with real user data (campaign note; this
  device's 1.4.0→1.6.0 upgrade-in-place did succeed with data preserved, but it had no
  meaningful user data to migrate).
- #148 toggle repro (pixel-only authorization; pixel unreachable).
- Second-language (Italian) swipe on Saga — needs a layout/pref change.
- ARC-005 nonzero occlusion on a geometric-served layout — needs an imported pack.
- Next-word cold-start bar (opt-in pref change).
- Everything on the Pixel 8 Pro (device off-network all session).

## Anomalies needing maintainer eyes

1. **Stale non-verbose "release" APKs in `build/outputs/apk/release/` (Aug 29)** —
   built without effective `LOCAL_BUILD=true`. Anything that previously "verified
   verbose logging" against those artifacts verified nothing. Rebuilt Sep 1 17:23.
2. **DictionaryManagerActivity tab counts reset to (0) on config-change recreation**
   (section f) — real UI bug, easily reproduced with a uimode flip while the activity
   is alive.
3. **Keyboard theme does not follow system light mode** (section f) — bug or by
   design, needs a call.
4. Protocol fact: `am force-stop tribixbite.cleverkeys` makes Android 13 revert
   `default_input_method` to Gboard — every scripted force-stop must be followed by
   `ime set`. (Bit this run twice.)
5. R8 under `gradle-guard.sh` defaults (256 m metaspace) fails with
   `OutOfMemoryError: Metaspace` on assembleRelease — release builds need
   `GRADLE_GUARD_METASPACE=512m` or more.

## Scratchpad artifact inventory (`…/scratchpad/wavek/`)

Text: saga-before-state.txt, saga-ckmemprobe-coldstart.txt, saga-first-swipe-decode.txt,
saga-meminfo-{t0,t5,t10,t15,settle3,burst3,baseline}.txt, saga-restore-verification.txt,
saga-keyboard-coords.txt, pixel-reconnect.log, gesture.sh, exercise.sh.
Video: saga-dirty-regions.mp4 (stale recon), saga-dirty-regions-fresh.mp4.
Images (all ≤1900 px, <4 MB): saga-01…saga-11 series (keyboard, decode evidence,
emoji pane), saga-dirty-f1..f3 + saga-dirtyF-{2,4.5,5.5,end} (dirty-region frames),
saga-{dark,light}-{keyboard,settings,dictmgr}.png, saga-06-kbregion.png (geometry
reference), crop files for suggestion-bar evidence.

— Fable 5
