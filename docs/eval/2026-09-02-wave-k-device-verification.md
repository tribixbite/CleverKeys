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

---

# Wave K2 (2026-09-02)

Second pass, run 2026-09-01 23:18 → 2026-09-02 00:30 EDT against HEAD `e89bc451`.
Purpose: execute the full owed protocol on the **Pixel 8 Pro** (which was off-network for
the whole Wave-K run above and therefore missed every check), plus a delta pass on the
Saga against the newer release build. Same campaign authorization for scripted ADB.
Artifacts in the session scratchpad `…/scratchpad/wavek2/` (inventory at the end); not
committed.

## Devices

| device | serial used | result |
|---|---|---|
| Pixel 8 Pro | `192.168.0.216:40621` | Full protocol executed. Android **17**, 1008×2244 @ 360 dpi, **no root** (`su` absent), not USB-powered. |
| Saga | `192.168.1.243:5555` | Delta pass executed (fresh-APK upgrade + smoke + ARC-110). Android 13, 1080×2400 @ 420 dpi, root available. |

**Port correction:** the briefed Pixel port `40307` was refused; the live wireless-debugging
port was **40621** (already-connected session). Consistent with the documented rotation.

**Access blocker (resolved mid-run):** the Pixel sat on a *secure fingerprint* keyguard for
the first ~40 min. `wm dismiss-keyguard`, swipe-up, and `KEYCODE_MENU` all fail against it —
`dumpsys trust` showed `GoogleTrustAgent … trusted=1` but the user row read
`trustState=UNTRUSTED, deviceLocked=1`, i.e. Extend Unlock can *hold* an unlock but cannot
*initiate* one (Android 10+ behaviour). `adb install` and `am instrument` work while locked,
so the APK installs and 3 of the 4 instrumented classes were completed during the wait;
the maintainer then unlocked the device and the UI checks proceeded.

## APK provenance (verified before install)

| artifact | size | sha256 | verified on device |
|---|---|---|---|
| `release/CleverKeys-v1.6.0-arm64-v8a.apk` | 30,263,444 B (mtime Sep 1 23:16) | `5b149df0be3b…` | ✓ both devices (`pm path` + `sha256sum` match) |
| `debug/CleverKeys-v1.6.0-arm64-v8a.apk` | 42,352,146 B | `7b7e0f103652…` | ✓ pixel |
| `androidTest/CleverKeys-debug-androidTest.apk` | 3,998,515 B | `15e3a9979 3b0…` | ✓ pixel |

**Briefing correction worth keeping:** the debug APK is package
**`tribixbite.cleverkeys.debug`** (applicationIdSuffix), so it **coexists with the release
build and never replaces it**. The "reinstall release afterwards" step was therefore not
needed — release stayed installed and untouched throughout. Instrumentation component:
`tribixbite.cleverkeys.debug.test/androidx.test.runner.AndroidJUnitRunner`
(target `tribixbite.cleverkeys.debug`), read from the test APK's manifest via `aapt2 dump`.

## Pixel before-state (23:18:40 EDT, `pixel-before-state.txt`)

| item | value |
|---|---|
| default_input_method | `com.menny.android.anysoftkeyboard/.SoftKeyboard` |
| enabled_input_methods | googletts voice : **AnySoftKeyboard** : SwiftKey : Gboard : `tribixbite.cleverkeys/.CleverKeysService` (CleverKeys already enabled) |
| uimode night | yes |
| debug.hwui.show_dirty_regions | (unset) |
| stay_on_while_plugged_in / screen_off_timeout | 15 / 300000 |
| foreground | `com.android.settings/.SubSettings` (device dozing, keyguard up) |
| CleverKeys installed | versionName **1.5.0**, versionCode 105002 |

Installed the release arm64 at 23:21 (`install -r`, 1.5.0 → **1.6.0 / 106002**), device sha
`5b149df0be3b…` == local file sha.

## Pixel check results

### a. ARC-070 — memory over an exercised session (VERDICT: no unbounded growth; native plateaus, Dalvik is GC churn)

Cold start (fresh process pid 23947, 00:02:45, `pixel-ckmemprobe-coldstart.txt`) — the
verbose build's probes fire, confirming this APK really is a `LOCAL_BUILD=true` build:

```
~ init.enter                 used=9.4MB   limit=256.0MB
~ init.contractionManager     used=14.3MB  delta=+4.9MB  | known=1232
~ dictionaryManager.userWords used=15.4MB  delta=+1.1MB  | lang=en
~ init.predictionCoordinator  used=21.0MB  delta=+5.6MB
~ init.done                   used=21.0MB
~ primary.dictionary          used=63.9MB  delta=+42.9MB | words=98144 setEntries=293799
~ ctc.baseParse               used=74.3MB  delta=+10.4MB
~ ctc.mergeAndOrdinals        used=100.5MB delta=+26.1MB
~ ctc.trie                    used=128.7MB delta=+28.2MB | words=98470 nodes=231144
ModelLoader: ✅ CtcEncoder session created successfully (XNNPACK)   (00:02:46.066)
```

`dumpsys meminfo` trajectory (KB), exercise = rounds of 4 two-key swipes + `input text` +
8 backspaces (bursts 1–2 also opened/closed the emoji pane):

| sample | time | Native Heap | Dalvik Heap | TOTAL PSS | TOTAL RSS |
|---|---|---|---|---|---|
| t0 (post cold start + 2 swipes) | 00:03 | 34,556 | 55,524 | 152,812 | 300,652 |
| post burst 1 (3 rounds + pane) | 00:04:43 | 34,304 | 56,104 | 135,719 | 285,276 |
| post burst 2 (3 rounds + pane) | 00:06:25 | 50,464 | 91,588 | 449,516 | 604,396 |
| post burst 3 (3 rounds, no pane) | 00:08:04 | 49,552 | 155,972 | 388,893 | 544,128 |
| settle (3 min idle) | 00:11 | 49,452 | **61,364** | 271,494 | 426,488 |

Reading: **native heap plateaus** — 34.5 → 50.5 MB across the first two bursts, then
**−0.9 MB** on the third and −1.0 MB more at settle, i.e. warm-up caches, not per-swipe
growth. Dalvik swings 55 → 156 MB under load and **idle GC returns it to 61 MB**, against
the 256 MB limit. The 449 MB PSS peak at burst 2 is dominated by **`GL mtrack 270,808 KB`**
(graphics buffers, the launcher activity + emoji pane composited in-process); by settle
that line is `EGL mtrack 61,460 KB` and PSS is back to 271 MB. Nothing in the window
indicates a leak. Matches the Saga's front-loaded-plateau finding on different hardware.

### b. Swipe decode e2e (VERDICT: works; and it exposed a real dropped-swipe defect — see Anomaly 1)

- First swipe of a fresh process, `input swipe 455 1605 850 1605 100` (t→o): `✅ SWIPE
  DETECTED` → `Committing text: len=3`.
- Second swipe (i→t): committed, suggestion bar **`it | is | iit | ot | ir`**
  (`pixel-03-crop-sugg.png`).
- After the #148 restore, o→r decoded with bar **`or | order | orders | orange | origin`**
  (`pixel-09-sugg.png`).

Coordinate map re-derived for this device from `wm size` + a pixel-exact keyboard crop
(`pixel-01-kbregion.png`): Q-row centre **y=1605**, A-row 1760, Z-row 1915; x-centres
q=59 w=158 e=257 r=356 t=455 y=553 u=651 i=751 o=850 p=948.

### c. ARC-069 first-swipe warm-up (VERDICT: no user-perceivable first-swipe stall)

| swipe | gesture UP | commit | latency |
|---|---|---|---|
| first of process (t→o) | 00:03:06.930 | 00:03:07.006 | **76 ms** |
| second (i→t) | 00:03:20.207 | 00:03:20.239 | **32 ms** |

The ONNX session and CTC trie are built during service init (`ctc.trie` and the XNNPACK
session line both land at 00:02:45–46, ~20 s before the first swipe was physically
possible), so the first swipe pays only ~44 ms of extra ORT warm-up path, not a model
load. Same shape as the Saga (61 ms → 23 ms).

### d. ARC-068 #79 — hwui dirty-regions discriminator (VERDICT: no idle repaint storm; an inset-strip-only tint DOES appear on this device — needs a maintainer call)

Method as in the Saga pass: `setprop debug.hwui.show_dirty_regions true` → force-stop →
relaunch SettingsActivity (prop is read at renderer init) → 12 s `screenrecord` with
idle(3.5 s)/scroll(×3)/idle phases. **Plus a control run with the prop cleared**, which the
Saga pass did not have.

- **Frame-count discriminator (primary evidence): no storm.** 12 s of wall clock produced
  only **6.005 s / 295 encoded frames** — `screenrecord` only receives frames when the
  surface repaints, so an idle invalidation storm would have produced continuous full-rate
  frames. It did not. (`pixel-dirty-regions.mp4`)
- **Content area shows zero dirty-region tint**, even mid-scroll (`pixel-dirtyF-scroll.png`,
  `pixel-dirtyF-end.png`).
- **New on this device:** a red overlay tint is present on the **status-bar and
  navigation-bar inset strips** in every extracted frame. The control run with the prop
  cleared (`pixel-dirty-control.mp4`, `pixel-control-end.png`) shows **no red anywhere**,
  so the tint is genuinely the dirty-region overlay and not a capture artifact. On Android
  17 the activity draws edge-to-edge, so those strips are part of CleverKeys' own surface.
  This resembles the "status-bar-strip-only flashing" insets-conflict signature Wave-K was
  told to look for, which did **not** appear on the Saga (Android 13).
  **Honest limit of this evidence:** because idle emits no frames, the last frame in the
  recording is the tail of a scroll settle, not a true idle repaint — so this shows the
  inset strips are in the damage rect *when the surface repaints*, not that they repaint
  continuously at idle. It does not coincide with a storm. Maintainer call whether an
  edge-to-edge background redraw under the bars is expected here.
- Procedure gotcha (new): **`adb shell setprop <name> ""` from the host fails with a
  `usage:` error** and silently leaves the prop set — the first "control" run was invalid
  because of this. Quote it for the device shell: `adb shell 'setprop <name> ""'`.
- Procedure gotcha (new): backgrounding `adb shell screenrecord` on the host and pulling
  after `sleep` yields a truncated file (`moov atom not found`). Run the record + input
  sequence **inside one on-device shell** and `wait` for the recorder before pulling.

### e. #148 repro — the owed pixel-only check (VERDICT: FIXED behaviour confirmed)

State changed through the **Settings UI** (uiautomator dump + `input tap`), not by writing
prefs — the release build is not debuggable and this device has no root, so prefs were
verified visually and via uiautomator `checked=` attributes.

1. **Both toggles OFF**: "Enable Word Predictions" → `checked=false` (`pixel-15-crop.png`),
   "Enable Swipe Typing" → `checked=false` (`pixel-16-crop.png`).
2. Opened the CleverKeys launcher's own "Test your new keyboard here" field. Keyboard
   renders **with no suggestion bar**, as expected with predictions off
   (`pixel-18-crop.png`).
3. Opened the clipboard pane (short gesture down-left on the `ABC/Ctrl` key).
   **Result: the pane OVERLAYS a still-fully-visible keyboard** — search bar, four entries
   and the `1 / 31` pager sit above an untouched QWERTY
   (`pixel-19-148-full.png`, `pixel-19-crop.png`). Pre-fix behaviour was the pane replacing
   the whole keyboard. **#148 fix verified on hardware.**
4. **Both toggles restored ON** and verified (`pixel-24-crop.png`, uiautomator
   `checked="true"`), then confirmed functionally: swipe decode + suggestion bar work again
   (section b, `or | order | …`).

Short-gesture threshold observed while triggering the pane (useful for future scripting):
`minDistance=62.2 maxDistance=313.3` on this device; a 56 px swipe was rejected with
`SHORT_GESTURE SKIP`.

### f. ARC-110 + light/dark screens (VERDICT: ARC-110 fix VERIFIED on this device; one prior finding reproduced)

Dictionary Manager, dark: **ACTIVE (97960) / DISABLED (0) / USER DICT (3) / CUSTOM (5)** —
all nonzero where expected (`pixel-26-crop.png`).

`cmd uimode night no` **with the activity alive** (the discriminator for the ARC-110 fix):

- at ~6 s after recreation: ACTIVE **(0)**, list empty, USER DICT (3) / CUSTOM (5) intact
  (`pixel-27-crop.png`);
- at ~18 s: **ACTIVE (97960) / DISABLED (0) / USER DICT (3) / CUSTOM (5)** — identical to
  pre-flip, list repopulated (`pixel-28-crop.png`).

So the counts **survive recreation** — the ARC-110 fix holds. Worth recording precisely:
on this device there is a **transient (0) window of roughly 6–15 s** while the 98 k-word
dictionary reloads asynchronously after recreation. That transient is what the pre-fix bug
made *permanent*; a verification that screenshots too early could wrongly read as a
regression. (The Saga, section 2 below, showed no visible transient at all.)

Light mode: Settings renders as a correct light theme (`pixel-29-small.png`).
**The keyboard stays dark purple in system light mode** (`pixel-30-crop.png`) — this
**reproduces Wave-K finding f.1 on a second device and a different Android version**, which
raises it above "possible device quirk". Still a bug-or-by-design maintainer call.

Night mode restored to `yes` afterwards.

### g. On-device instrumented runs (VERDICT: 50/50 tests pass)

Runner `tribixbite.cleverkeys.debug.test/androidx.test.runner.AndroidJUnitRunner`.

| class | result | time | notes |
|---|---|---|---|
| `DictionaryManagerRecreationTest` (ARC-110's new test) | **OK (1 test)** | 6.86 s | run unlocked; needs `ActivityScenario` |
| `EmojiSearchTest` (ARC-106 pins) | **OK (30 tests)** | 0.93 s | ran while locked |
| `PointersGestureRoutingTest` (T13 on real hardware) | **OK (14 tests)** | 3.25 s | ran while locked |
| `PrivateCopyEditingKeyTest` | **OK (5 tests)** | 0.29 s | see below |

`PrivateCopyEditingKeyTest` **failed 1/5 on the first (locked-screen) attempt**:
`privateCopy_storesSelectionPrivately_andDoesNotTouchOsClipboard` —
`baseline must be set before the private copy expected:<os-clipboard-baseline-…> but
was:<null>`. Re-run after unlock: **5/5 OK**. Root cause is a *harness* artifact, not
product code: Android suppresses `ClipboardManager` **reads** behind a locked keyguard, so
`readPrimaryClipText()` returns null even with the shell identity adopted. Independent
corroboration that only the read was suppressed: the release keyboard's clipboard pane
(captured for #148, `pixel-19-148-full.png`) shows the entry
`os-clipboard-baseline-1800748158813…` — the test's **write** did land. Worth a
`assumeFalse(keyguardManager.isKeyguardLocked)` guard if these ever run unattended.

## Pixel restore verification (00:29:46 EDT, `pixel-restore-verification.txt`)

| item | before | after | match |
|---|---|---|---|
| default_input_method | AnySoftKeyboard | AnySoftKeyboard | ✓ |
| enabled_input_methods | voice:ASK:SwiftKey:Gboard:CleverKeys | identical string | ✓ |
| uimode night | yes | yes | ✓ |
| debug.hwui.show_dirty_regions | (unset) | `[]` (empty) | ✓ |
| stay_on_while_plugged_in | 15 | 15 | ✓ (never changed; `svc power stayon` not needed) |
| screen_off_timeout | 300000 | 300000 | ✓ |
| installed cleverkeys packages | `tribixbite.cleverkeys` only | `tribixbite.cleverkeys` only (debug + debug.test uninstalled) | ✓ |
| CleverKeys version | 1.5.0 / 105002 | **1.6.0 / 106002, sha `5b149df0be3b…`** — intentional, left for the maintainer soak | by design |
| device temp files | — | `/sdcard/*.xml`, `/sdcard/wk2*.mp4` removed | ✓ |
| foreground | Settings (dozing) | launcher (HOME) | ~ (original transient screen not restorable) |

Residual state that could not be reverted without forbidden actions (no app-data clear):
UserVocabulary learned the test words (to/it/up/or/quick/brown/fox); the release clipboard
history contains the `os-clipboard-baseline-…` string the instrumented test put on the OS
clipboard. **Two settings were touched and restored**: `word_prediction_enabled` and
`swipe_typing_enabled` (the #148 repro, restored + verified ON). **One setting was toggled
by an errant tap and restored**: `Backspace Undo Swipe` (default ON → OFF → ON, verified
`checked="true"`, `pixel-ui-rf.xml`); a second errant tap opened the Prediction Engine
dropdown, which was dismissed with BACK leaving **CTC** selected (`pixel-23-crop.png`).
No messages were sent, no keys were added in Extra Keys (`17 of 107` unchanged), and the
16 KB compatibility dialog was dismissed with **OK**, not "Don't Show Again".

## Saga delta pass

Before-state (23:23:05, `saga-before-state.txt`): default IME Gboard; enabled = voice +
Gboard (CleverKeys disabled); night `yes`; hwui prop unset; launcher foreground; CleverKeys
**1.6.0 / 106002 sha `6894b2cce616…`** (the Sep-1 17:23 build from the Wave-K run).

1. **Fresh-APK upgrade verified.** `install -r` → device sha **`5b149df0be3b…`**,
   versionCode 106002. Upgrade in place, data preserved.
2. **Swipe decode e2e.** Multi-waypoint `w-o-r-l-d` path via the root sendevent injector:
   `✅ SWIPE DETECTED` → `Committing text: len=6`, field reads **world**, suggestion bar
   **`world | would | works | worked | word`** (`saga2-03-swipe-world.png`,
   `saga2-swipe-decode.txt`).
3. **ARC-110 on this device.** `DictionaryManagerActivity` needs `su -c am start` (it is
   `exported="false"`). Dark: **ACTIVE (97960) / DISABLED (0) / USER DICT (0) / CUSTOM (1)**
   (`saga2-06-dictmgr-dark.png`). `cmd uimode night no` with the activity alive → recreated
   in light theme with **identical counts 97960/0/0/1** (`saga2-07-dictmgr-light.png`), no
   visible transient. **ARC-110 fix confirmed on the Saga too** — directly contradicting the
   pre-fix Wave-K observation (finding f.2 above, where the same flip produced all-`(0)`).
4. Restore (23:29:50, `saga-restore-verification.txt`): night `yes` ✓; default IME back to
   Gboard ✓; CleverKeys IME disabled again ✓; `/data/local/tmp/wavek-gesture.sh` removed ✓;
   messaging draft discarded, launcher foreground ✓; CleverKeys left installed at the fresh
   1.6.0 (`5b149df0be3b…`) for the maintainer soak, by design. No instrumented runs, per
   the brief.

## Anomalies needing maintainer eyes (new this pass)

1. **Dense-sampled two-key swipes are silently dropped — `MIN_KEY_DISTANCE` is compared
   against the wrong distance.** In
   `src/main/kotlin/tribixbite/cleverkeys/gesture/ImprovedSwipeGestureRecognizer.kt`,
   `registerKeyWithFiltering(key, distance, timeDelta)` does:

   ```kotlin
   if (_lastRegisteredKey != null && distance < MIN_KEY_DISTANCE) { return }
   ```

   `distance` here is the **per-sample step length** passed down from `addPoint`, not the
   distance since the last *registered key*. With `swipe_min_key_distance = 40 px` (Config
   default), any gesture sampled finely enough that each step is < 40 px can never register
   a second key. Observed on the Saga: `input swipe 487 1750 911 1750 350` (t→o, a 417 px
   path) emits ~11 ms/7–13 px events; the recognizer logged `Keys touched: 1` for the whole
   gesture, `Pointers` still classified it `SWIPE` and logged `Sending to swipe decoder`,
   and then **nothing was committed and nothing further was logged**. Reproduced at 350 ms
   *and* 700 ms duration (so it is not the `HIGH_VELOCITY_THRESHOLD` filter), and fixed by
   making the steps coarser: the same t→o path via the 8-step sendevent injector (~53 px
   steps) committed "to" normally, and on the Pixel `input swipe … 100` (≥40 px steps)
   commits every time. `stabilizeEndpoints()` cannot rescue these because it self-gates on
   `_touchedKeys.size >= 2`.
   - **Not a regression**: the file is byte-identical since Aug 29 (`git log --follow`; the
     only touch is the ARC-098 directory move). The Wave-K run's successful `To` on the
     older APK was event-density luck, not a build difference.
   - **User-facing correlate to assess**: a slow, smooth, short swipe on a high-report-rate
     digitizer is exactly the input that produces sub-40 px steps. The failure mode is
     silent — no commit, no suggestion, no error.
   - Suggested fix direction: accumulate distance since `_lastRegisteredKey` (or compare
     key-centre separation) instead of using the per-sample step.

2. **CleverKeys' native libraries are not 16 KB page-size aligned (Android 15+/16 KB
   devices).** Android 17 raised a system "Android App Compatibility" dialog naming
   `lib/arm64-v8a/libonnxruntime.so` (*Unknown error*) and
   `lib/arm64-v8a/libonnxruntime4j_jni.so` (*LOAD segment not aligned*)
   (`pixel-FINDING-16kb-alignment.png`). The dialog fires because the *debug* build is
   debuggable — the warning is suppressed for release builds, **but the misalignment itself
   is a property of the shipped ONNX Runtime `.so` files and is unchanged in release**. On a
   real 16 KB page-size device these libraries fail to load. This is a shipping-blocker
   class issue for future Android/Play requirements and is worth its own ledger item
   (bump the ONNX Runtime AAR to a 16 KB-aligned release, or re-align at packaging).

3. **Dirty-region tint on the edge-to-edge inset strips** (section d) — present on the Pixel
   (Android 17), absent on the Saga (Android 13), control-verified as the real overlay.
   Not accompanied by an idle repaint storm. Needs a call on whether it is expected.

4. **Keyboard does not follow system light mode** — Wave-K finding f.1 now **reproduced on
   a second device and Android version** (section f). Promote from "possible quirk" to a
   decision item.

5. Protocol facts worth keeping: `am force-stop tribixbite.cleverkeys` reverts
   `default_input_method` on the **Pixel/Android 17 too** (observed at 00:02:45, IME fell
   back to Gboard) — every scripted force-stop needs a following `ime set`. And
   `DictionaryManagerActivity` is `exported="false"`, so it needs root (`su -c am start`) or
   in-app navigation; on an unrooted device go Settings → Activities → Dictionary Manager.

## Scratchpad artifact inventory (`…/scratchpad/wavek2/`)

Text: `pixel-before-state.txt`, `pixel-restore-verification.txt`,
`pixel-ckmemprobe-coldstart.txt`, `pixel-coldstart-mark.txt`, `pixel-firstswipe-log.txt`,
`pixel-meminfo-{t0,t5,burst2,burst3,settle}.txt`, `pixel-unlock-poll.log`,
`pixel-instr-{EmojiSearchTest,PointersGestureRoutingTest,PrivateCopyEditingKeyTest,PrivateCopyEditingKeyTest-unlocked,DictionaryManagerRecreationTest}.txt`,
`pixel-ui-*.xml` (uiautomator dumps used for every tap target), `pixel-exercise.sh`,
`saga-before-state.txt`, `saga-restore-verification.txt`, `saga2-swipe-decode.txt`,
`draft-notes.md`.
Video: `pixel-dirty-regions.mp4` (prop on), `pixel-dirty-control.mp4` (prop cleared).
Images (all ≤1900 px, <4 MB): `pixel-01`…`pixel-31` series (keyboard geometry, swipe
evidence, settings light/dark, #148 before/after, Dictionary Manager dark/light/settled),
`pixel-dirtyF-{scroll,end}.png`, `pixel-control-{scroll,end}.png`,
`pixel-FINDING-16kb-alignment.png`, `saga2-01`…`saga2-07` series, plus crops.

— Fable 5
