# Scene Backdrops: Architecture, Power and Frames

> **Status (2026-11-12):** as built, through round 18 (5–9 fps cost the same on the window path,
> at every brightness; hardware layers only at 10 fps and above; the snow at 8 fps; the first
> spell after a rotation now gets its layers; a tablet check and a tablet show-path fix). Every number here was
> measured on a Pixel 8 Pro (Tensor G3, LTPO OLED with 60/120 Hz modes only, no ARR) unless the
> row says otherwise; "estimate" marks every number that was not measured. The measurement log
> with the per-run detail is `memory/todo.md` (rounds 6–16, 2026-11-09…12).

## 0. In short: what an animated theme costs

A scene costs battery only **while it moves**: by default for 20 s after the keyboard shows or
after the last touch, and never while you type (it holds still and resumes 1 s after the last
key). A still scene costs only the light of its picture (OLED, §4.3). While it moves, on a
Pixel 8 Pro with the keyboard otherwise idle, brightness 12, refresh rate left free (measured,
round 17; ~195 mW ≈ 1 % of the battery per hour):

| Theme | Default frame rate | Extra power while moving | Battery per hour of continuous motion | Ten 20-s spells an hour |
|---|---|---|---|---|
| Every scene at its default rate (Aurora and River rocks 5 fps; Stream, Meadow, Forest 7; Snow and Beach 8) | 5–8 fps | **~180 mW** (measured 153–199 at 5, 7, 8 and 9 fps and at brightness 12, 102 and 255; one older run 115 at 5 fps) | ~0.9 % | ~0.05 % |
| Any scene with Frame Rate 10 | 10 fps | ~340–374 mW; the aurora on display-hardware layers ~150–200 | ~1.7–1.9 % (layers ~0.9 %) | ~0.1 % |

Between 5 and 9 fps the frame rate does not change the cost: the panel rests between frames at
all of them (§3.2.2). Brightness does not change it either: the motion's display share is the
panel kept refreshing, +77 / +84 / +89 mW at brightness 12 / 102 / 255 (§3.2.9). Between themes
the moving cost is the same; the still picture differs by its light, up to ~45 mW at brightness
102 and ~84 mW at 255 (Meadow, Beach), under 1 mW for the aurora, which True Black removes (§4.3). Before round 16 the defaults were 8–12 fps: ~340 mW for the 10-fps
scenes, ~445 for the 12-fps ones.

**Over a day** (model `scripts/research/scenes/battery_stories.py`, usage assumed, brightness
102, details in `docs/eval/2026-11-12-scene-battery-day.md`): a light user (30 keyboard opens a
day) pays 0.16–0.25 % of the battery a day, about a minute of screen-on time; a heavy chatter
(150 opens, 225 messages, each followed by a full 20-s spell) 1.3–2.0 %, 8–12 minutes; a
writer 0.5–0.9 %. A user modelled on a real phone's app time (85 min a day in chat apps with the
keyboard always up, 36 searches a day elsewhere) pays ~0.6 % with the aurora at any brightness and
0.7–1.3 % with Meadow (brightness 12–255; 0.76 % at 255 with True Black). Only raising both Keep
Moving For and the frame rate reaches a cost the battery graph shows (6–7 %/day at 60 s and 12 fps).

## 1. What the backdrops are

The scene looks paint a picture behind the keys and move it while the keyboard is in use
(Appearance → Animated scenes, `look_backdrop_motion`, on by default).

| Look | Backdrop | How it moves | Own frame rate (`SceneKind.frameMs`) | Hardware layers? |
|---|---|---|---|---|
| CleverKeys Aurora | 5 curtains (`AuroraRibbon`) + baked haze, twinkling stars | two images cross-fade and drift (`MotionStyle.AURORA`, 12 dp, 5.5 s fade, 14 s drift) | 5 fps (`AURORA_FRAME_MS` 200; 10 from 11-10, 8 before) | at Frame Rate 10+ only (Android 13+, `SceneOverlayPlan.MIN_FRAME_MS`) |
| Meadow | grass, flowers, golden-hour sky | grass bands sway through a mesh (`SWAY_UP`), pollen motes drift | 7 fps (143 ms; was 12), `motionScale` 0.5 | no (mesh layers) |
| Stream | refracted pebble bed | two `FLOW` light layers slide and wrap; glints drift | 7 fps (143 ms; was 8) | no (layers) |
| River rocks | still stones | rain splashes drawn per frame (`RainSplashes`) | 5 fps (200 ms; was 10) | no (rain) |
| Forest | trunks, canopy, ferns | canopy and branches sway down, ferns sway up; leaves drift | 7 fps (143 ms; was 12) | no (mesh layers) |
| Snow | night sky with aurora and stars, hills, pines | the sky pair cross-fades and drifts like the aurora (`MotionStyle.forScene`); hills and pines are a `STILL` layer; snow falls | 8 fps (125 ms; 7 on 11-12 until round 17, 10 before, 8 before `a6e64884`) | no (falling snow) |
| Beach | sea and textured sand | swells `ROLL`, waves `WASH` up the sand | 8 fps (125 ms; was 12) | no (layers) |

The Frame Rate setting (`look_motion_fps`, 1–15, 0 = each scene's own) replaces every scene's
rate when set. The default rates are the maintainer's (2026-11-12: "aurora and river rocks
probably only need 5fps, beach 8, snow stream meadow and forest 7"), except the snow at 8 since
round 17 (its flakes stepped past their own width at 7, and 8 costs the same), pinned by
`SceneRasterTest.eachSceneMovesAtItsChosenFrameRate`.

## 2. Architecture

```
KeyboardLooks.SCENES ── LookBackdrop (ribbons | scene, glows, stars, topFade)
        │
Keyboard2View.setKeyboard / onWindowVisibilityChanged
        │  background = BackdropDrawable(base, backdrop, density)  ←  setTuning(Config.look_motion_tuning)
        ▼
BackdropDrawable
  ├─ painting: render thread "BackdropRender" (THREAD_PRIORITY_BACKGROUND)
  │     PaintKey(base, ribbons, glows, scene, w, h, rasterDensity, trueBlack) → LRU cache 24 MB
  │     scene  → SceneRaster.paint → (SceneTrueBlack.apply) → base [+ alt] + SceneLayer bitmaps
  │     aurora → AuroraRaster.render(base, ribbons) ×2 (alt = AuroraMotion.alternate), glows baked
  ├─ clock: AuroraMotionClock (speed, typing hold resumeMs, idle stop idleMs)
  ├─ gate:  MotionAllowance (battery saver, animator scale 0, thermal ≥ SEVERE, battery ≤ 15 %
  │         not charging) at start and every RECHECK_MS 5 s
  ├─ tick:  scheduleSelf every frameMs while moving; VisibleChangeGate skips a frame that
  │         would move nothing by ½ px or 2 alpha levels
  ├─ draw (window path): drawDrifting(base, alt) → drawLayers (mesh via SceneMotion /
  │         FLOW copies / STILL bitmap) → particles (RenderNode groups) → top fade
  └─ overlay (hardware path, aurora only): SceneOverlay — curtains A/B and two star layers (one
        when Twinkle is 0: SceneOverlayPlan.starLayers) as
        SurfaceControl children of an empty SurfaceView anchor at index 0 of the IME decor;
        each frame is one Transaction (setPosition/setScale/setAlpha), no window redraw;
        Transaction.setFrameRate(1000/frameMs) on each layer; built only while the backdrop
        moves (after the motion gate) and released when it rests, so a still keyboard is one
        window plane (SceneOverlayPlan.sync); the newest overlay owns the shared anchor
        (AnchorOwners: a rebuild in one pass no longer lets the old overlay's delayed free()
        remove the new layers' parent); each buffer is drawn turned by the window's buffer
        transform hint and declares the inverse (setBufferTransform), so a rotated screen's
        layers stay on the display hardware (round 16)
Keyboard2View.drawKeysCached: the keys are a cached RenderNode with a compositing layer, so a
backdrop frame re-records nothing of the keys.
```

Key files: `theme/BackdropDrawable.kt`, `theme/SceneRaster.kt`, `theme/SceneMotion.kt`,
`theme/AuroraMotion.kt` (`SceneMotionTuning`, `MotionStyle`, `MotionAllowance`, the clock),
`theme/LookBackdrop.kt` (`AuroraRaster`), `theme/SceneOverlay.kt`, `theme/SceneTrueBlack.kt`,
`theme/ScenePreview.kt` (Theme Manager thumbnails), `Keyboard2View.kt`.

### 2.1 Settings (Appearance)

| Setting | Key | Default | Effect on cost |
|---|---|---|---|
| Animated scenes | `look_backdrop_motion` | on | off = no frames at all |
| Scene Motion Speed | `look_motion_speed` | 100 % | none per frame (time runs faster, same frames) |
| Scene Motion Intensity | `look_motion_intensity` | 100 % | none, except 0 (with twinkle 0) lets the visible-change gate skip every frame |
| Twinkle | `look_motion_twinkle` | 55 % | as intensity |
| Pause After Typing | `look_motion_resume_ms` | 1 s | typing holds the scene still: no frames while typing |
| Keep Moving For | `look_motion_idle_s` | 20 s | the motion stops this long after the last touch: the duty cycle |
| Frame Rate | `look_motion_fps` | scene's own | the main lever: cost per second ≈ frames × per-frame cost (§4) |
| Blend on Display Hardware | `look_scene_overlay` | on | aurora only: about half the window path's cost on a free-refresh panel (§3.3) |
| Sharp Scenes | `look_scene_crisp` | off | 4× pixels for the soft backdrops (~7 MB more per size); not measured per frame |
| True Black Scenes | `look_scene_true_black` | off | static display light −79…−99 % (scenes), −42 % (aurora, ~1 mW) (§4.3) |

## 3. Measurements

Method (rounds 11–14): Perfetto `android.power` with `collect_power_rails`, no root; 5- or
10-minute runs in ABBA order (or mirrored for more arms); the keyboard up and UNTOUCHED in a
Chrome text box with Keep Moving For above the run length (typing doubles the compute baseline
and drowns the motion); the phone quieted (`quiet.sh`: Bluetooth, NFC, location, mobile data and
scans off, non-critical apps stopped); brightness pinned; the refresh rate pinned at 60 Hz or
left free as stated. Report per rail group; the display draws on three rails:
`VSYS_PWR_DISPLAY`, `L22M_DISP` and `S6M_LLDO1` (r = 0.997 with the display rail). Check each
motion run's frame count (~3,050 per 5 min at 10 fps): round 14's first attempt measured a scene
that stopped after 20 s and was discarded.

### 3.1 Every round

| Round (date) | Conditions | Arm | Δ power vs motion off | Notes |
|---|---|---|---|---|
| 6 (11-09) | Aurora 8 fps, scripted typing, brightness 102, 4 × 5 min | window | **+136 mW** (+16 %) | display +58.6 (all three rails), CPU +42, memory +14, GPU +9 (re-parsed 11-12) |
| 6 | same, `View.setRequestedFrameRate` vote | window | +108 mW | CPU less; display rails unchanged (+57) |
| 8 (11-10) | Aurora 10 fps, typing, brightness 102 | window | **+268 mW** (+33 %) | VSYS_PWR_DISPLAY +57, L22M_DISP +41, S6M_LLDO1 +49, CPU +65 |
| 8 | River rocks, same | window | +238 mW | batching splashes cut CPU ~20 %, not power |
| 9 (11-10/11) | Aurora, 10-min typing in Chrome | window | Pixel **+217** (+15 %); Note10+ +126 (+14 %, battery current); Saga +114 (+5 %, USB input) | three devices |
| 10 (11-11) | typing hold (1 s) added; 10-min typing | window | below run-to-run noise (on 1583 vs off 1652/1774) | idle minute: ~20 s of motion (2,198 frames) |
| 11 (11-11) | quiet, 60 Hz pinned, brightness 102, 10 min | window | typing: Aurora +57, Stream +57, Meadow +30; idle: +19 / +30 / +56 | the display rail never moves when pinned |
| 12 (11-11) | untouched, motion all run, 60 Hz pinned, brightness 12, 5 min ABBA | window | Aurora 10.0 fps **+129.5** (compute +96.5); Stream 7.6 fps +92.2 (+74.4); Meadow 12.0 fps +141.5 (+110.2) | **~9.5 mJ of compute and ~12.3 mJ in all per frame, whatever the scene** |
| 13 (11-11) | untouched, refresh FREE, brightness 12, Aurora, 10 × 5 min | window 10 fps | **+340** (display +257, CPU +38) | baseline off 810 mW |
| 13 | same | layers 10 fps | **+152** (display +91, CPU +26) | no frame-rate vote yet |
| 13 | same | window 5 fps | +115 (display +81, CPU +16) | |
| 13 | same | layers 5 fps | +150 (display +97, CPU +15) | layers: the same at 5 and 10 fps |
| 14 (11-12) | as 13, layers vote 10 Hz | window 10 fps | +374 (display +250, CPU +61) | vs one off run (711 mW); the closing off run had +190 mW of foreign CPU |
| 14 | same | layers 10 fps + vote | +197 (display **+92**, CPU +40) | vote applied (SurfaceFlinger `requestedFrameRate 10 Hz`, panel 60 Hz, render 20 Hz) but the display share equals round 13's +91 |
| 16 (11-12) | as 13, 8 × 5 min, off P4 P2 P3 \| P3 P2 P4 off (off 736 / 719 mW) | layers, 4 planes (2 star layers) | **+188** (display +92, CPU +33, memory +34) | runs 912 / 919 |
| 16 | same | layers, 3 planes (1 star layer) | **+160** (display +81, CPU +30, memory +20) | runs 884 / 891 |
| 16 | same | layers, 2 planes (no stars; bound only) | **+165** (display +82, CPU +29, memory +22) | runs 892 / 893 |
| 17 (11-12) | as 13, Keep Moving For 600 s, 16 × 5 min mirrored; off 770 / 768 mW, landscape off 727 / 749 | layers 5 fps | **+163** (display +96, CPU +21, memory +27) | runs 919 / 946; 0 window frames |
| 17 | same | window 5 fps | **+183** (display +80, CPU +51, memory +18) | 962 / 943; 4.9 fps |
| 17 | same | window 7 fps | **+178** (display +81, CPU +50, memory +18) | 958 / 936; 6.7 fps |
| 17 | same | window 8 fps | **+187** (display +82, CPU +50, memory +19) | 947 / 966; 7.6 fps |
| 17 | landscape (user_rotation 1) | window 5 fps | **+169** (display +86, CPU +41, memory +15) | 903 / 910 |
| 17 | landscape | "layers 5 fps" | +174 (display +85, CPU +44) | 908 / 916; **drew 1,470 window frames: the layers never came up** (bug below, fixed) — a second window-path pair |
| 18 (11-12) | as 17, brightness per arm, Aurora, 14 × 5 min mirrored; off 2106 / 2110 (255), 1558 / 1546 (102), 786 / 753 (12) | window 5 fps, brightness 255 | **+180** (display +89, compute +74) | 2288 / 2288; 1,469–1,474 frames |
| 18 | same | window 5 fps, brightness 102 | **+199** (display +84, compute +87) | 1789 / 1713 |
| 18 | same | window 5 fps, brightness 12 | **+153** (display +77, compute +59) | 930 / 915 |
| 18 | same | window 9 fps, brightness 12 | **+187** (display +80, compute +88) | 951 / 962; 2,551–2,570 frames (8.6 fps) |
| 18 | Meadow, brightness 255, motion off | still picture | Meadow's screen **+84 mW** over Aurora's (all three display rails) | one run (the model predicted +84); the three later Meadow runs are void: the battery reached 33 °C and the phone dimmed the panel (display rail 1,633 → ~1,100 mW) at thermal status 0 |

**Comparing rounds.** The rounds ran under different conditions, so their deltas are not one
series: rounds 6–10 priced motion **while typing** (the "off" runs already keep the panel and
CPU busy, so motion adds less: round 6's display rails were 427 mW off vs 486 on at
brightness 102), round 11–12 pinned the refresh at 60 Hz (hides the display share), and rounds
13–16 price an **untouched** keyboard with the refresh free (the off baseline lets the panel
idle, so motion shows its full display cost). Round 6, re-parsed per rail on 2026-11-12 with
all three display rails: display +58.6 (VSYS_PWR_DISPLAY +17.3, L22M_DISP +18.2, S6M_LLDO1
+23.1), CPU +42.1, memory +13.8, GPU +8.6, total +136.5 mW — its total was complete; it is lower
than the later rounds because of its conditions (8 fps, typing), not because the motion was
cheaper then. Like for like, the work lowered the cost (§3.2).

Frames and CPU per run (round 13/14, 5 min): window 10 fps ~3,050 frames and ~2,400 CPU
jiffies; layers 10 fps 0 window frames and ~530–580 jiffies; motion off ~15–40 jiffies.
Per-frame time (round 8, Aurora): ~1.4 ms main thread + ~2.8 ms RenderThread issue.

### 3.2 What the numbers say

1. **The cost is per frame, not per pixel drawn.** Aurora, Stream and Meadow all cost 11.8–12.9
   mJ per frame with the refresh pinned (round 12); batching stars, baking the glow and batching
   rain changed CPU ticks but not power.
2. **With the refresh rate free, the display dominates, and it is a step, not a line.** The
   panel's kernel idle timer is 80 ms (`ro.surface_flinger.set_idle_timer_ms`). With frames
   125 ms or more apart (8 fps or slower) the display rails cost +80–82 mW over a still keyboard
   at 5, 7 and 8 fps alike (round 17); at 10 fps (100 ms) +250. The CPU part is +50 mW at all
   three rates in round 17 (round 13: +16 at 5 fps), so 5–8 fps cost the same ~180 mW within the
   run-to-run spread (±15 mW). Power is **not** linear in frame rate: flat from 5 to 8, then a
   jump of ~160 mW by 10. 9 fps (111 ms) is still on the flat: +187 (round 18), so the step lies
   between 9 and 10 fps. The §4.1 curve that interpolated 5 → 10 fps (121 / 198 / 241 mW at 5 /
   7 / 8) was wrong between its anchors.
3. **Hardware layers halve it at 10 fps** (+152–197 vs +340–374): no window redraw, and the
   layers keep the panel at 60 Hz. Below 10 fps they gain little or nothing (+150 and +163 at
   5 fps vs the window path's +115 and +183), and on a phone whose display hardware has fewer
   planes they cost more (tablet, item 8), so since round 17 they are used only at 10 fps and
   above (`SceneOverlayPlan.MIN_FRAME_MS`); at the default 5 fps the aurora draws in the window.
4. **The frame-rate vote changes nothing on this panel**: it has no mode below 60 Hz. On an ARR
   panel (Android 15+, `Display.hasArrSupport()`), the same vote can run the panel near the
   content rate; not measured (no such test phone).
5. **Plane count (round 16):** the second star layer costs ~28 mW (4 → 3 planes, mostly memory
   traffic); dropping the stars entirely saves nothing more. One star layer makes every star
   pulse together, so it is used only for steady stars (Twinkle 0), where it is the same
   picture. The other ~160 mW is the panel held at 60 Hz and the per-frame transactions.
6. **Landscape:** the layers' buffers were portrait-native, so the composer turned them on the
   GPU (all four CLIENT, ROT_90); drawn pre-rotated for the window's buffer transform hint they
   are DEVICE again (round 16). The window path costs the same in landscape as in portrait
   (+169–174 vs +178–187, round 17). Round 17 also found that the **first** spell after a rotation
   (or a theme change) never built the layers: the painting for the new size arrived while the
   motion was already running, `setPainting` released the pending layers, and `wake()` did not
   ask again because the frames were already ticking. `setPainting` now syncs the layers itself.
7. **What the user sees costs nothing extra while still**: a still scene draws no frames (0
   jiffies beyond the off baseline); its only cost is the light of its pixels (§4.3).
8. **A tablet (Galaxy Tab S11, SM-X730, MediaTek, Android 16, 2560 × 1600, 30–120 Hz, no ARR;
   2026-11-12, composition only, no power rails without root):** at rest the keyboard window is
   DEVICE at 60 Hz. With the aurora's four layers up, the display hardware ran out of planes:
   the layers **and the keyboard window** were CLIENT (GPU) and the panel held 120 Hz for the
   whole spell; on the window path the keyboard stayed DEVICE at 60 Hz. One more reason for the
   10-fps floor (item 3). The keyboard itself works there in landscape, portrait and a pop-up
   (freeform) window; it spans the screen width in every case (no split or floating mode).
   Round 18 tried to price it with proxies (refresh rate, CPU, frames; it has no rails and was
   full on AC) and found instead that **the scene did not move when the keyboard showed** after
   CleverKeys Settings had been open in the same process: `startTicking` posted the first tick
   (`scheduleSelf`, a Choreographer animation callback) and no frame ever ran it, until a key
   press drew one. The first tick now goes through the main Handler. Diagnosed with logs on the
   tablet and confirmed there with the release (2026-11-13): a show right after Settings moved in
   two trials (screenshots 10 s apart differ; frame gaps 199–200 ms, i.e. the shipped 5 fps), and
   at Keep Moving For 20 s it drew 0 frames 35 s after the show. The Pixel never showed the stall.
   On this tablet `dumpsys gfxinfo` counts every frame twice in "Total frames rendered" (64 in
   the histogram, 32 in the profile data for the same 6 s); read rates from the profile data.
9. **Brightness (round 18):** motion costs the same at brightness 12, 102 and 255 (+153 / +199 /
   +180 mW, two runs each; the spread is the CPU part). The display share grows only from +77 to
   +89 mW across the whole range: what motion adds is the panel refreshing instead of idling, not
   light. What does scale with brightness is the still picture (§4.3): the display rails with
   the keyboard still draw 420 / 1,195 / 1,735 mW at 12 / 102 / 255. At 255 the Pixel dims the
   panel by itself once the battery is ~33 °C (thermal status still 0), so long runs at maximum
   brightness measure a dimmer screen; check the display rail per run.

## 4. Estimating a scene's cost

### 4.1 Model (Pixel 8 Pro; other phones scale differently, see round 9)

While a scene moves (from the keyboard showing or the last touch, for Keep Moving For, and not
while typing within Pause After Typing):

- **Refresh free, window path, 5–9 fps** (every default): **~180 mW**, measured flat (rounds 17
  and 18: 153–199 at 5–9 fps and brightness 12–255; round 13's single pair: 115 at 5 fps). Neither
  frame rate nor brightness matters in this range.
- **Refresh free, window path, 10 fps:** measured +340–374 mW (rounds 13, 14). 12 and 15 fps
  are unmeasured; the old two-point fit `8 mJ × fps + 373 mW × (1 − i)^1.67`, with
  `i = max(0, gap − 80 ms) / gap`, gives 444 and 493 there and is wrong below 10 fps.
- **Refresh free, hardware layers (aurora, Frame Rate 10+):** `P ≈ 150–200 mW`.
- **Refresh pinned, or an ARR panel (estimate for ARR):** `P ≈ 12.3 mJ × fps`
  (fit of round 12; the three scenes lie within ±5 %).

Battery: the Pixel 8 Pro holds ~19.5 Wh, so **195 mW ≈ 1 % per hour of continuous motion**.

### 4.2 By frame rate (window path, brightness 12, motion running all the time)

Every scene costs the same per frame, so one row per frame rate applies to all of them. Values
in mW; measured in bold, otherwise estimate.

| Frame rate | Refresh free | Refresh pinned / ARR | Scenes at this own rate |
|---|---|---|---|
| 5 fps | **153–199** (rounds 17–18; round 13: 115) | 62 | Aurora, River rocks |
| 7 fps | **178** | 86 | Stream, Meadow, Forest |
| 8 fps | **187** | 98 (**92** Stream at 7.6) | Snow, Beach |
| 9 fps | **187** | 111 | — |
| 10 fps | **340–374** | **123–130** | — (Aurora layers: **152–197**) |
| 12 fps | ~444 (old fit) | **142–148** | — |
| 15 fps | ~493 (old fit) | 184 | — |

**Duty cycle.** These are costs per second of motion. With the defaults the scene moves for
20 s after the keyboard shows or after the last touch and is held still while typing, so a
session of steady typing costs almost nothing (round 10: below noise), and one tap followed by
a minute of reading moves ~20 s of it (round 10: 2,198 frames in that minute). Example: any
scene, a 10-minute session with ten 20-second idle spells ≈ 200 s × 180 mW ≈ 36 J ≈ 0.05 % of
the battery (0.13 % for the 12-fps scenes before round 16). Over a whole day see §0 and
`docs/eval/2026-11-12-scene-battery-day.md`.

### 4.3 What the picture costs while still (True Black)

The display's cost for static content follows the light of the pixels (OLED). Measured: at
brightness 102 Meadow's screen drew ~55 mW more than Aurora's. Off-device, the mean linear
light of the keyboard-sized painting with its layers at rest (`SceneTrueBlackTest` method; the
cut-off is taken from that composite since the Fable audit, so the snow's ground goes black too):

| Scene | Light as painted | True black | Change | Estimated saving, brightness 102 | brightness 12 |
|---|---|---|---|---|---|
| Meadow | 0.374 | 0.078 | −79 % | ~45 mW | ~11 mW |
| Beach | 0.299 | 0.003 | −99 % | ~45 mW | ~11 mW |
| Snow | 0.183 | 0.005 | −97 % | ~27 mW | ~7 mW |
| Stream | 0.101 | 0.004 | −96 % | ~15 mW | ~4 mW |
| Forest | 0.096 | 0.010 | −90 % | ~13 mW | ~3 mW |
| River rocks | 0.065 | 0.012 | −82 % | ~8 mW | ~2 mW |
| Aurora | 0.0145 | 0.0084 | −42 % | ~1 mW | <1 mW |

The repaint runs once per painting on the render thread. Since round 16 one scratch set of three
floats per pixel serves every image of a painting (it was six arrays per image): allocations at
1008 × 840 fell from 65–86 MB to 14–34 MB per scene with byte-identical output (SHA-256 of every
image unchanged, all six painted scenes).

Estimate: ~153 mW per unit of mean light at brightness 102 (55 mW / (0.374 − 0.0145)), a quarter
of that at brightness 12 (display baseline 272 vs 1,080 mW) and ~233 mW at 255 (baseline 1,552 vs
1,020 mW, round 18). Checked once at 255: Meadow's still screen drew +84 mW over Aurora's, as
predicted (round 18, one run); so True Black saves ~70–80 mW for Meadow and Beach at maximum
brightness.

## 5. Levers, in order of effect (refresh free)

1. Keep Moving For shorter (duty cycle) — linear.
2. Frame Rate at 8 or below: 10 → 8 fps saves ~160 mW on the window path; below 8 it saves
   nothing measurable (round 17), so smoothness, not power, decides the rate there.
3. Hardware layers (aurora, Frame Rate 10+): −190 mW at 10 fps; one star layer instead of two:
   −28 mW (applied for steady stars only, §3.2.5).
4. True Black: −44 mW for the bright scenes at brightness 102, while still or moving.
5. Not levers: speed, the scene's draw work, the frame-rate vote on a 60/120 Hz panel.

Ideas not built: hardware layers for the other cross-fade scenes (needs the mesh/FLOW layers
and drifting particles composited elsewhere); a shorter default Keep Moving For or a longer
Pause After Typing (`docs/eval/2026-11-12-scene-battery-day.md` §8); measuring on an ARR phone;
tablet power (needs a rooted tablet or battery-current sampling unplugged; round 18's proxy runs
measured the show-path stall instead, §3.2.8).

**Smoothness at the default rates** (absolute per-frame ceilings, round 17 review; the earlier
bounds scaled with the frame interval and so loosened as the rate fell): every layer point
≤ 2.2 dp a frame (worst: the meadow's gustiest grass tips, 2.1 dp at 7 fps); the aurora's
curtains ≤ 1.1 dp and ≤ 0.1 of cross-fade a frame at 5 fps; drifting particles ≤ 3.2 dp, the
width of the largest snowflake (the snow 2.9 dp at 8 fps; it stepped 3.3 at 7). A beach swell
now stays fully hidden for 0.26 s around its wrap (`SceneMotion.ROLL_HIDDEN`): at 8 fps two
frames could straddle the wrap at ~6 % alpha each and show a ~30 dp jump.

## 6. Tests

`AuroraMotionTest` (tuning, clock, gate, frame intervals, absolute per-frame ceilings for the
curtains and drifting particles), `SceneRasterTest` (every scene paints,
moves the way its subject does, the snow sky moves like the aurora), `SceneMotionTest` (mesh and
flow, a 2.2-dp per-frame ceiling for every layer sampled every 37 ms, the swell hidden at its
wrap for longer than a 5-fps frame), `SceneOverlayPlanTest` (layer eligibility incl. the 10-fps floor, and geometry, the lifecycle rule, anchor ownership,
star layers per twinkle, landscape pre-rotation), `SceneTrueBlackTest` (never
brighter, light and black-share bounds per scene, a reused scratch set gives fresh-memory results), `ScenePreviewTest` (Theme Manager
thumbnails), `KeyboardLooksTest` (key legibility over every scene as it moves).
