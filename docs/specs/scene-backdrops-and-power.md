# Scene Backdrops: Architecture, Power and Frames

> **Status (2026-11-12):** as built, through round 16 (new default frame rates, landscape
> layers on the display hardware, one star layer for steady stars). Every number here was
> measured on a Pixel 8 Pro (Tensor G3, LTPO OLED with 60/120 Hz modes only, no ARR) unless the
> row says otherwise; "estimate" marks every number that was not measured. The measurement log
> with the per-run detail is `memory/todo.md` (rounds 6–16, 2026-11-09…12).

## 0. In short: what an animated theme costs

A scene costs battery only **while it moves**: by default for 20 s after the keyboard shows or
after the last touch, and never while you type (it holds still and resumes 1 s after the last
key). A still scene costs only the light of its picture (OLED, §4.3). While it moves, on a
Pixel 8 Pro with the keyboard otherwise idle, brightness 12, refresh rate left free (estimates
from the §4.1 model except the measured aurora; ~195 mW ≈ 1 % of the battery per hour):

| Theme | Default frame rate | Extra power while moving | Battery per hour of continuous motion | Ten 20-s spells an hour |
|---|---|---|---|---|
| CleverKeys Aurora (Android 13+, display-hardware layers) | 5 fps | ~150–190 mW (measured 160–188 at 10 fps; flat with frame rate) | ~0.8–1.0 % | ~0.05 % |
| CleverKeys Aurora (window path: Android 12 or older, or the blend setting off) | 5 fps | ~120 mW | ~0.6 % | ~0.03 % |
| River rocks | 5 fps | ~120 mW | ~0.6 % | ~0.03 % |
| Snow, Stream, Meadow, Forest | 7 fps | ~200 mW | ~1.0 % | ~0.06 % |
| Beach | 8 fps | ~240 mW | ~1.2 % | ~0.07 % |

Before round 16 the defaults were Aurora 10 fps (8 until 2026-11-10), River rocks and Snow 10,
Meadow, Forest and Beach 12, Stream 8: ~340 mW for the 10-fps scenes, ~445 for the 12-fps ones.
Between themes the moving cost differs only by frame rate (§3.2.1); the still picture differs by
its light: up to ~45 mW at brightness 102 (Meadow, Beach) and under 1 mW for the aurora, which
True Black removes (§4.3).

## 1. What the backdrops are

The scene looks paint a picture behind the keys and move it while the keyboard is in use
(Appearance → Animated scenes, `look_backdrop_motion`, on by default).

| Look | Backdrop | How it moves | Own frame rate (`SceneKind.frameMs`) | Hardware layers? |
|---|---|---|---|---|
| CleverKeys Aurora | 5 curtains (`AuroraRibbon`) + baked haze, twinkling stars | two images cross-fade and drift (`MotionStyle.AURORA`, 12 dp, 5.5 s fade, 14 s drift) | 5 fps (`AURORA_FRAME_MS` 200; 10 from 11-10, 8 before) | yes (Android 13+) |
| Meadow | grass, flowers, golden-hour sky | grass bands sway through a mesh (`SWAY_UP`), pollen motes drift | 7 fps (143 ms; was 12), `motionScale` 0.5 | no (mesh layers) |
| Stream | refracted pebble bed | two `FLOW` light layers slide and wrap; glints drift | 7 fps (143 ms; was 8) | no (layers) |
| River rocks | still stones | rain splashes drawn per frame (`RainSplashes`) | 5 fps (200 ms; was 10) | no (rain) |
| Forest | trunks, canopy, ferns | canopy and branches sway down, ferns sway up; leaves drift | 7 fps (143 ms; was 12) | no (mesh layers) |
| Snow | night sky with aurora and stars, hills, pines | the sky pair cross-fades and drifts like the aurora (`MotionStyle.forScene`); hills and pines are a `STILL` layer; snow falls | 7 fps (143 ms; was 10, 8 before `a6e64884`) | no (falling snow) |
| Beach | sea and textured sand | swells `ROLL`, waves `WASH` up the sand | 8 fps (125 ms; was 12) | no (layers) |

The Frame Rate setting (`look_motion_fps`, 1–15, 0 = each scene's own) replaces every scene's
rate when set. The default rates are the maintainer's (2026-11-12: "aurora and river rocks
probably only need 5fps, beach 8, snow stream meadow and forest 7"), pinned by
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
2. **With the refresh rate free, the display dominates.** The panel's kernel idle timer is 80 ms
   (`ro.surface_flinger.set_idle_timer_ms`). New content every 100 ms (10 fps) leaves it 20 ms
   of rest per frame, so the three display rails cost +250 mW over a still keyboard; at 5 fps
   (200 ms gaps) +81 mW.
3. **Hardware layers halve it at 10 fps** (+152–197 vs +340–374): no window redraw, and the
   layers keep the panel at 60 Hz. Below 10 fps they gain nothing more (+150 at 5 fps), because
   SurfaceFlinger still composes every transaction.
4. **The frame-rate vote changes nothing on this panel**: it has no mode below 60 Hz. On an ARR
   panel (Android 15+, `Display.hasArrSupport()`), the same vote can run the panel near the
   content rate; not measured (no such test phone).
5. **Plane count (round 16):** the second star layer costs ~28 mW (4 → 3 planes, mostly memory
   traffic); dropping the stars entirely saves nothing more. One star layer makes every star
   pulse together, so it is used only for steady stars (Twinkle 0), where it is the same
   picture. The other ~160 mW is the panel held at 60 Hz and the per-frame transactions.
6. **Landscape (round 16):** the layers' buffers were portrait-native, so the composer turned
   them on the GPU (all four CLIENT, ROT_90); drawn pre-rotated for the window's buffer transform
   hint they are DEVICE again (checked on the Pixel; not power-measured in landscape).
7. **What the user sees costs nothing extra while still**: a still scene draws no frames (0
   jiffies beyond the off baseline); its only cost is the light of its pixels (§4.3).

## 4. Estimating a scene's cost

### 4.1 Model (Pixel 8 Pro; other phones scale differently, see round 9)

While a scene moves (from the keyboard showing or the last touch, for Keep Moving For, and not
while typing within Pause After Typing):

- **Refresh pinned, or an ARR panel (estimate for ARR):** `P ≈ 12.3 mJ × fps`
  (fit of round 12; the three scenes lie within ±5 %).
- **Refresh free, window path:** `P ≈ 8 mJ × fps + 373 mW × (1 − i)^1.67`, with
  `i = max(0, gap − 80 ms) / gap` the share of each frame gap the panel can rest
  (two-point fit of round 13: 5 fps → 81 mW display, 10 fps → 257; the compute part is round
  13's non-display delta, 7.6–8.3 mJ per frame).
- **Refresh free, hardware layers (Aurora only):** `P ≈ 150–200 mW`, flat from 5 to 10 fps.

Battery: the Pixel 8 Pro holds ~19.5 Wh, so **195 mW ≈ 1 % per hour of continuous motion**.

### 4.2 Estimates by frame rate (window path, brightness 12, motion running all the time)

Every scene costs the same per frame, so one row per frame rate applies to all of them; a
scene at its own rate is marked. Values in mW (estimate except the measured anchors in bold).

| Frame rate | Refresh free | Refresh pinned / ARR | Scenes at this own rate |
|---|---|---|---|
| 5 fps | **115–123** (121) | 62 | Aurora (window path), River rocks |
| 8 fps | 241 | 98 (**92** Stream at 7.6) | Beach |
| 7 fps | 198 | 86 | Snow, Stream, Meadow, Forest |
| 10 fps | **340–374** (337) | **123–130** | — (Aurora layers: **152–197**) |
| 12 fps | 444 | **142–148** | — |
| 15 fps | 493 | 184 | — |

At each scene's own rate (round 16 defaults), refresh free: Aurora (layers) ~150–190 mW
(0.8–1.0 %/h), Aurora (window) / River rocks ~120 (0.6 %/h), Snow / Stream / Meadow / Forest
~200 (1.0 %/h), Beach ~240 (1.2 %/h). Before round 16: Stream ~241, the 10-fps scenes ~340, the
12-fps ones ~444.

**Duty cycle.** These are costs per second of motion. With the defaults the scene moves for
20 s after the keyboard shows or after the last touch and is held still while typing, so a
session of steady typing costs almost nothing (round 10: below noise), and one tap followed by
a minute of reading moves ~20 s of it (round 10: 2,198 frames in that minute). Example:
Meadow at 7 fps, refresh free, a 10-minute session with ten 20-second idle spells ≈ 200 s ×
198 mW ≈ 40 J ≈ 0.06 % of the battery (0.13 % at its old 12 fps).

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

Estimate: ~153 mW per unit of mean light at brightness 102 (55 mW / (0.374 − 0.0145)), and a
quarter of that at brightness 12 (display baseline 272 vs 1,080 mW). Not measured directly.

## 5. Levers, in order of effect (refresh free)

1. Keep Moving For shorter (duty cycle) — linear.
2. Frame Rate lower: 10 → 5 fps saves ~220 mW on the window path (round 13).
3. Hardware layers (aurora): −190 mW at 10 fps; one star layer instead of two: −28 mW
   (applied for steady stars only, §3.2.5).
4. True Black: −44 mW for the bright scenes at brightness 102, while still or moving.
5. Not levers: speed, the scene's draw work, the frame-rate vote on a 60/120 Hz panel.

Ideas not built: hardware layers for the other cross-fade scenes (needs the mesh/FLOW layers
and drifting particles composited elsewhere); a lower default frame rate for the 12-fps
scenes; a shorter default Keep Moving For; measuring on an ARR phone.

## 6. Tests

`AuroraMotionTest` (tuning, clock, gate, frame intervals), `SceneRasterTest` (every scene paints,
moves the way its subject does, the snow sky moves like the aurora), `SceneMotionTest` (mesh and
flow), `SceneOverlayPlanTest` (layer eligibility and geometry, the lifecycle rule, anchor ownership,
star layers per twinkle, landscape pre-rotation), `SceneTrueBlackTest` (never
brighter, light and black-share bounds per scene, a reused scratch set gives fresh-memory results), `ScenePreviewTest` (Theme Manager
thumbnails), `KeyboardLooksTest` (key legibility over every scene as it moves).
