# memory/todo.md item 34: the default look, CleverKeys Aurora and animated scenes, rounds 1–19

Moved verbatim from `memory/todo.md` (HEAD `762f7993`, 2026-11-14) lines 1233–1550. Relative links below were written
for `memory/`: read `../docs/…` as `docs/…` from the repo root. The live status of every
open item is in `memory/todo.md` "Open now".

Summary, architecture and cost model: `docs/specs/scene-backdrops-and-power.md`.

## Item 34

34. [ ] device: the CleverKeys default look (agent, 2026-11-06; maintainer: "move ios/swift/sam/etc
    familiar look themes to top and … modernize and slick-ify the cleverkeys default"). BUILT:
    `look_cleverkeys` is `Defaults.THEME` (spec `layout-system.md` "The CleverKeys default look");
    Theme Manager / Layout Manager list Familiar looks first; the default yields to tuned gaps,
    trail colour (`LookUserYield`; NOT the custom border, maintainer 2026-11-06). Maintainer's
    first install was a STALE release APK (built 11-05 19:58, before `f8786c76`); feedback acted
    on: hints smaller (0.34), border no longer yields, Theme Manager no longer kills the process
    (dead first tap after a theme change). Second round (same day: "make keys not borderless",
    hints "now too small"): a 1 dp hairline (#D3CADB / #3D3546), hints 0.44; the Restart keyboard
    button rebuilds in place (`EXTRA_REBUILD_KEYBOARD`), the broken-theme fallback is the look,
    choosing the look stores the default trail.
    Third round (2026-11-07, maintainer screenshots vs SwiftKey): equal gaps (0.11 of a key,
    rows the same in px, `equal_gaps`), the global gaps no longer yield (a tuned gap had the
    keys touching), dark mode darker keys on a near-black aurora with static stars
    (`LookBackdrop` / `AuroraBackdropDrawable`). DEVICE 2026-11-09 (agent, `9d4003b3`): Saga .144
    landscape ~22 px gaps both ways, Saga .1.130 portrait 12/12 px; aurora + stars render, no
    seam under the bar; light flat and flips live; Restart keyboard keeps the process; action
    keys, filled Shift arrow and press bubble PASS (checklist "Default look", evidence
    `build/nov09-saga144/`, `build/nov09-saga130/`). Still open: the maintainer's look-and-feel
    verdict; fresh install / upgrade-keeps-theme / custom-theme-delete / per-layout gap /
    Layout Manager picker / Key-press preview switch items.
    Fourth round (2026-11-09, maintainer: "the purple is too cloud like and less aurora like"):
    the glows became two rayed curtains (`AuroraRibbon` / `AuroraRaster`, `a5427cd6`); release
    `3db9b885` shows them on Saga .144 portrait and on Saga .83 after a real 1.6.0 → 2.0 upgrade
    with no stored theme (→ the look, as designed) (`build/nov09b-saga144/02-*`,
    `build/nov09-saga83/03-*`). Verdict on the new aurora: maintainer.
    Fifth round (2026-11-10, maintainer picked from 10 previews: "finer rays, folded, 3 curtains
    with bottom going more horizontal, partial green rim, quieter more stars, slightly
    transparent keys"; motion "default off until we measure battery impact … time out with
    inactivity"): `CLEVERKEYS_AURORA` now has 3 curtains (lowest slope 0.06, edge 0.72, 2.6 folds,
    mint-to-lilac rim), alphas × 0.7, 8 stars/100 dp² glinting every 5th, `keyOpacity` 0.88
    (key fills only). Appearance → Moving aurora (`look_aurora_motion`, off): two pre-rendered
    curtain images cross-fade and drift, 3 star groups twinkle, 8 fps, rests 20 s after the
    last touch (`AuroraMotionClock`), still in battery saver / animations off / < API 29.
    Pixel 8 Pro release `17ba26e7`: renders as previewed; moving = 78 frames/10 s, about 6 % of
    one core (57–64 CPU ticks/10 s; the first build at 12 fps without the keys layer was 129);
    rested = 0 frames, 0.2 %; screen diff 73k px changing over 3 s, 0 at rest. Open: the
    maintainer's verdict on look and motion; battery over a real typing session
    (`batterystats`) before considering motion on by default.
    Sixth round (2026-11-10, maintainer: revert the folds, green and dimming, lower the curtain
    back over the nav keys, key fill 0 with full outline, a visible action glyph, make it its
    own CleverKeys Aurora theme, the default look plain again, and nature scenes with motion):
    `KeyboardLooks.auroraLook` + `SCENES` (Meadow, Stream, River rocks, Forest, Snow, Beach;
    `SceneRaster`, particles `ParticleShape` with drift), Theme Manager "Scenes", switch renamed
    "Animated scenes" (`look_backdrop_motion`, unreleased so no migration). Battery, Pixel 8 Pro,
    Perfetto power rails over 4 × 5-min scripted typing sessions (monkey into the Wi-Fi password
    field, brightness pinned 102/255, ABBA): motion ON 982 / 980 mW, OFF 846 / 843 mW, i.e.
    +136 mW (+16 %, ≈0.7 % battery per hour of keyboard time); display rails +58, CPU +36,
    memory +12, GPU +8. Then a low frame-rate vote on backdrop-only frames
    (`View.setRequestedFrameRate` via reflection, compileSdk 34). Re-measure (`5425f513`, one
    pair): ON 942 / OFF 834 mW = +108 mW; the vote saved CPU (little cores 85 vs 96–102 mW)
    but NOT the display rails (still +57 mW: the panel stays at its higher refresh while content
    updates at 8 fps). Next levers if wanted: a slower FRAME_MS, or motion only for a few
    seconds after the keyboard opens.
    All 7 scenes checked on the Pixel (`5425f513`, Settings test field). Open: maintainer's
    verdict on the scenes.
    Seventh round (2026-11-10, maintainer: separate key fill vs border opacity everywhere; the
    toggle only moved after a reload and often not at all; a 4th aurora curtain with an indigo
    middle one and livelier aurora; redo river rocks as multicoloured slate / gem / agate, forest
    as real swaying leaves without line artifacts or falling specks, beach with texture and
    full waves, meadow grass that sways instead of pulsing to blurry). Done: user
    `key_border_opacity` (v6 migration copies `key_opacity`), theme shares
    `LookGeometry.keyFillOpacity` / `keyBorderOpacity` through XML / JSON / backup / Creator /
    look export; `syncBackdropMotion` on every setKeyboard + window-visible + attach; scenes as
    layers warped by `drawBitmapMesh` (`SceneMotion`), painted at full resolution on a
    background thread; aurora `MotionStyle.AURORA` (12 dp, 10 fps, brighter alt). Previews in
    the session scratchpad (`scene2/`). Open: device check of motion on the Pixel, battery
    re-measure at the new frame rates (12 fps for the swaying scenes), maintainer's verdict.
    Eighth round (2026-11-10, maintainer with aurora photos + a Yellowknife clip: curtains
    "more in-sync like the real aurora", a 5th in the empty top right, the hallmark green on one
    side of the bottom, indigo into teal, the shimmering stars back; river rocks without the
    "white topo-like circles" on all but 1–4 stones and rain instead of glistening; controls for
    speed / intensity / twinkle / crispness; motion surviving app switches; battery at the new
    frame rates). Done: `AuroraRibbon.edgeColorEnd/topColorEnd/convergence/radiantX`, five
    curtains on one wave (`AURORA_WAVES`), 14 stars/100 dp² glinting every 3rd, 5 twinkle groups;
    rocks with `AGATE_STONES` = 4, faint slate strata, `ParticleShape.RAIN`, no caustics layer;
    `SceneMotionTuning` (`look_motion_speed` / `_intensity` / `_twinkle`, `look_scene_crisp`).
    Pixel found the real app-switch bug: kept particle RenderNodes lose their display list when
    the IME window hides, so stars / rain vanished after one switch while frames kept coming
    (fixed: re-record when `!hasDisplayList()`; verified on the Pixel: 0 frames while away, motion
    and stars back after 3 switches). Maintainer then reported lag (it coincided with Gradle runs
    on the same phone): measured the keyboard alone at ~1.4 ms main + ~5.7 ms RenderThread CPU per
    moving frame, 0 when hidden or resting; fixed real gaps anyway (layer pixel arrays no longer
    kept beside their bitmaps, cache 40 → 24 MB with onTrimMemory trim, explicit stop + GPU
    release on window hide, baked glow, banded fade, batched stars). River rocks then redone per
    maintainer: rain as splashes seen from above (`RainSplashes`), stones with chipped outlines,
    bump-mapped grain and creases, patchy gloss. Battery (Pixel 8 Pro, Perfetto rails, 5-min
    scripted typing, brightness pinned): aurora ABBA on 1070 / 1104 mW vs off 829 / 809 =
    +268 mW (+33 %); river rocks +238 mW in both orders, before and after batching the splashes
    (batching cut CPU ticks ~20 % but not power). The cost is the display pipeline: VSYS_PWR_DISPLAY
    +57, L22M_DISP (rail 20) +41, S6M_LLDO1 (rail 17) +49, CPU clusters +65. rails.py prints those
    two by index: descriptor order in the trace names them. Only fewer frames or less time moving
    lowers it. Open: maintainer's verdict (and on any frame-rate / idle-time trade).
    Ninth round (2026-11-10/11, maintainer: "longer test on 2 different devices… 3x each"; forest
    with trees and real leaves; rain that hits the rocks; a less regular stream; aurora + stars in
    snow and faster snow; meadow at golden hour and half the motion; brighter beach sand; new
    descriptions; Animated scenes on by default; theme + / − commands). Battery, 10-min scripted
    typing in a Chrome text box (guarded chunks), Aurora, same build: Pixel 8 Pro power rails
    on 1673 / 1678 vs off 1428 / 1488 mW = +217 mW (+15 %); Note10+ unplugged battery current
    on 1106 / 966 / 993 vs off 879 / 912 = +126 mW (+14 %); Saga USB input with charging
    restricted (qcom-battery restrict_chg) on 2131 / 2244 / 2280 vs off 2122 / 2057 / 2135 =
    +114 mW (+5 %). Missing for 3 + 3: Pixel 1 on / 1 off (its last runs lost to adb drops and
    a landscape rotation), Note10+ 1 off (it locked). Done in code: forest rebuilt (barkTrunk,
    foliage clusters, canopy + hanging branch layers), stream as FLOW layers over a refracted
    pebble bed, rocks rain = wet spot + crown sprites on stone / rings on water
    (ScenePainting.surface), snow aurora + stars, meadow golden hour + SceneKind.motionScale
    0.5, beach sand brighter, fills meadow 0.7 / forest 0.68 / snow 0.58, LOOK_BACKDROP_MOTION
    true, theme_next / theme_previous (243 commands). Open: Pixel still on CleverKeys Aurora
    with Animated scenes on (the maintainer was using it; restore to Cyberpunk + off); Note10+
    has CleverKeys installed and as its IME, brightness pinned — restore (uninstall, Samsung
    Keyboard, auto/91) once unlocked; theme commands not device-tested.
    Tenth round (2026-11-11, maintainer: battery options 1, 2b, 4 from the Fable review, "resume
    after 1s of no typing"; rain "way too aggressive", should vary, splash "too symmetrical and
    large"; ferns "too dainty and perfect and similar", larger leaves, more coverage; snow
    aurora "needs more of our aurora colors"). Done in code: typing hold (`AuroraMotionClock.touch`
    on key press / swipe move, `TYPING_HOLD_MS` 1 s, `HOLD_EASE_MS` 250), `VisibleChangeGate`
    (skip frames under half a pixel / two alpha levels; measured off-device it skips NONE at the
    default tuning — every scene changes visibly each frame, as its frame rate was set by its
    pace — and helps only slow tunings, e.g. intensity 0 + twinkle 0 draws one frame), `MotionAllowance` (thermal ≥ MODERATE,
    battery ≤ 15 % not charging; rechecked every 5 s while moving), rain at 8 slots / 100 dp² in
    showers (`RainSplashes.shower`), sizes skewed small, flash + uneven wet spots + lopsided
    hopping beads (`splatter`), ferns as clumps of drooping lobed fronds, forest leaves ×1.4,
    canopy to half height, two hanging branches in one layer, snow aurora in the look's palette,
    Animated scenes description in 22 locales; Theme Manager Scenes intro (22 locales). Device:
    the first build crashed the IME on the first motion tick (argument-order bug, fixed in
    1c740edf, verified on the Note10+); rain on stone was invisible until wet spots got a sheen.
    Pixel re-measure (Perfetto rails, 10-min typing, brightness pinned): motion on 1583 mW vs
    off 1652 / 1774 — the motion cost is now below run-to-run noise (display rail alone varied
    736–1061 mW between runs); before the hold it was +217. Frames: on 5514 vs off ~2700 (the
    motion resumes in the script's pauses between chunks, as designed). Idle (one tap + 59 s,
    on): 1717 mW, 2198 frames ≈ 20 s of motion a minute; the idle-off pair and a second
    typing-on run were lost to adb dropping the Pixel at ~5–9 min three times (no crash; the
    pin cron did not cause it). Open: Pixel restore (Cyberpunk, Animated scenes off, brightness
    manual 12) once reachable; Note10+ Chrome test tabs.
    Eleventh round (2026-11-11, maintainer: quiet the Pixel and rerun Aurora / Stream / Meadow;
    clean-up of terminal clips with hanging indents; merged clips onto the system clipboard;
    pause and idle seconds as settings; a motion on/off command). Done (`83896fe0`): cleaner
    hanging-indent joins + inner space runs, merge → OS clipboard (private asks first),
    `look_motion_resume_ms` / `look_motion_idle_s` (backup/restore), `toggle_scene_motion`
    (244 commands). Pixel quieted (`scratchpad/dev/quiet.sh`: BT/NFC/location/data/scans off,
    60 Hz pinned, ~51 third-party apps force-stopped), 10-min runs, brightness 102. Per scene,
    motion on / off, mW (display rail | CPU+GPU+memory rails | total):
    typing Aurora 1041|315|1768 vs 1040|275|1711, Stream 1066|342|1829 vs 1068|295|1772, Meadow
    1097|315|1830 vs 1097|293|1800; idle (tap + 59 s) Aurora 1081|187|1646 vs 1080|171|1627,
    Stream 1106|182|1677 vs 1105|159|1647, Meadow 1134|208|1745 vs 1135|164|1689. The display
    rail never moves with motion (refresh pinned); typing doubles the compute baseline, so
    typing deltas are noise-dominated (Meadow typing +23 < its idle +44).
    Twelfth round (maintainer: "10 min constant animations vs … no kb animations", then 5-min
    runs): keyboard up and untouched in a Chrome text box, "Keep Moving For" 600 s, ABBA,
    brightness 12, refresh pinned 60. Δ on−off (compute | total): Aurora 10.0 fps +96.5 | +129.5,
    Stream 7.6 fps +74.4 | +92.2, Meadow 12.0 fps +110.2 | +141.5 mW. **Every scene costs
    9.2–9.8 mJ of compute per frame**: power is frames × the window's fixed cost, not what is
    drawn. Display 272–275 mW on and off at brightness 12 (≈1080 at 102). The Pixel's
    `ro.surface_flinger.set_idle_timer_ms` = 80 with `support_kernel_idle_timer` = true, so at
    10 fps (100 ms gaps) the panel can rest only ~20 ms per frame: the +57 mW display cost of
    the sixth round (unpinned) is the refresh staying up. Fable review (maintainer asked for
    < 50 mW): (1) 5 fps ≈ half the compute and lets the panel idle 120 of every 200 ms;
    (2) curtains as two static `SurfaceControl` layers under the IME window, moved by
    `Transaction.setAlpha/setPosition` so the display hardware blends them and HWUI draws
    nothing (est. 5–20 mW compute); combine both. Next: a scene frame-rate setting and an
    unpinned sweep (10/6/5/4 fps at brightness 12 and 102) to measure the panel-idle share,
    then decide on (2) — awaiting the maintainer.
    Quick Settings tile (`fcd6da88`, maintainer: "supposed to show kb not open kb picker… if
    already shown then hide"): show/hide toggle, the picker only with another keyboard
    selected; Pixel through the real shade PASS — visible → "Tap to hide it" → hidden; hidden →
    shown under the open shade and kept after collapse; after Back → shown; home screen → no
    picker and no keyboard over the launcher, also after the 8 s window. The Pixel's shade does
    not hide the keyboard, so the shade-hidden path (Saga) is still owed, as are a minimized
    keyboard and a field tap within 8 s of a home-screen tap. CI: the APK job's ImageMagick
    step now skips when present, retries apt and falls back to the committed icons
    (`eeaed98a`; 83896fe0's build failed twice on runner DNS). Pixel restored and checked IN
    13:55 (force-stopped apps stay stopped until opened).
    Thirteenth round (2026-11-11, maintainer: "add the fps setting and write and test the
    hardware blend"; then tile fixes, "a true black option for the bright scenes", 5 fps
    videos, the unknown rail). Built: Frame Rate (`look_motion_fps`, 0 = scene's own, 1–15),
    Blend on Display Hardware (`look_scene_overlay`, `SceneOverlay`: curtains + stars as
    `SurfaceControl` children of an empty SurfaceView anchor under the IME window, moved by
    transactions, all four layers DEVICE composition in the HWC table; frame-rate vote added
    after the run), True Black Scenes (`look_scene_true_black`, `SceneTrueBlack`: broad areas
    black, light/detail/colour kept; −76…−99 % light, the top fade black too), and the tile
    fixes (monochrome raccoon, then inverted as a disc with the strokes cut out; "Toggle"; the
    show is asked for with no editor focused — Pixel PASS over Clock: shown and kept, second tap
    hides). Round 13: Aurora, still keyboard, refresh UNPINNED, brightness 12, 5-min ABBA,
    Δ vs motion off (display rails | CPU | total mW): window 10 fps +257 | +38 | +340,
    layers 10 fps +91 | +26 | +152, window 5 fps +81 | +16 | +115, layers 5 fps +97 | +15 | +150
    (the layers' build had no frame-rate vote yet). The display side is three rails:
    `VSYS_PWR_DISPLAY`, `L22M_DISP` (subsystem Display) and `S6M_LLDO1` (LDO; r = 0.997 with
    the display rail across the 10 runs, 0.83 with CPU) — the 80 mW "unknown" rail of the first
    readout was rails.py dropping names without VDD/PWR. So unpinned, most of the motion's cost
    is the panel kept refreshing, and the layers halve it at 10 fps. Videos of every scene at
    5 fps vs its own rate sent to the maintainer for a look decision (5 fps runs dropped on the
    maintainer's call). Open: True Black in the theme picker previews; native review of the new
    strings in 21 locales. Pixel restored and checked IN 17:52.
    Fourteenth round (2026-11-12, maintainer: "did you do frame rate vote?", snow aurora "ripple
    like the regular aurora", aurora in true black). Done (`a6e64884`): the snow's sky is a
    drifting cross-faded pair on the aurora's motion and frame rate behind a STILL ground layer;
    true black also blacks the aurora's sky and drops its haze (~40 % less light, ~1 mW).
    S25U (`a16143dc`): scenes never moved there because thermal status 2 (MODERATE) stopped
    them; the gate now stops at SEVERE. The vote is applied (SurfaceFlinger: four layers
    `requestedFrameRate 10 Hz`, panel 60 Hz, render rate 20 Hz) but does not lower the cost on
    this panel: Aurora 10 fps, unpinned, brightness 12, vs motion off (run 1; the closing off
    run had +190 mW of foreign CPU): display rails window +250 / layers +92 mW (round 13 layers
    without the vote: +91), totals +374 / +197. The panel has only 60/120 Hz modes and content
    every 100 ms keeps it from its 80 ms idle; the vote matters only on an ARR panel. The first
    attempt was invalid: Keep Moving For was 20 s from a restore, so the scene stopped after
    20 s (190 frames per run instead of ~3,050) — check the frame count after the first motion
    run. Pixel restored and checked IN.
    Fifteenth round (2026-11-12, maintainer: true black in the theme picker previews, "do any
    other outstanding", a Fable review and audit, an architecture/power spec; then a True Black
    key and a switch in Theme Manager's Scenes). Done: Theme Manager scene cards show their scene
    behind the sample keys (`ScenePreview`, rendered off the main thread, cached), in true black
    when on; the `toggle_true_black` command (245 commands; extra key / short swipe) and a True
    Black Scenes switch at the top of Scenes (same pref); spec
    `docs/specs/scene-backdrops-and-power.md` (architecture, every round, cost model and
    estimates). Fable audit fixes: the overlay's shared anchor is owned by the newest overlay (a
    rebuild in one pass let the old delayed free() remove the new layers' parent — not
    reproduced on the device: rotation hides the IME in Settings and Chrome); layers built only
    while moving and released at rest (Pixel: 4 moving / 0 at rest / 4 after a key press); no
    star images while the anchor has no surface; first placement anchor-relative; overlay-mode
    visible-change sampling of the 2 shown star groups; the clock's step cap follows the frame
    interval (1–3 fps no longer slow the motion); true black takes its cut-off from the composite
    (the snow's ground went black: 82 % of pixels off). Open: SceneTrueBlack scratch memory
    (TODO in code); Fable's power ideas (one pre-blended curtain plane, re-issuing the vote per
    transaction for ARR panels); native review of the new strings. Pixel restored, IN.
    Sixteenth round (2026-11-12, maintainer: "go ahead with the fewer-planes aurora idea and
    measure it and the True Black repaint memory todo", plus the agent's top three; then new
    default frame rates and the round-6 re-parse). Planes, Pixel, Aurora 10 fps layers, untouched,
    refresh free, brightness 12, 8 × 5 min (off P4 P2 P3 | P3 P2 P4 off, a local-build-only
    `files/star_layers` hook since removed; SurfaceFlinger confirmed 4/2/3 layers per arm): 4
    planes +188 mW, 3 planes (one star layer) +160, 2 planes (no stars) +165 — the second star
    layer costs ~28 mW (memory traffic), fewer planes beyond that nothing. Shipped only where it
    is the same picture: Twinkle 0 → one star layer (`SceneOverlayPlan.starLayers`); the default
    keeps the shimmer (maintainer's choice to trade it). Exact pre-blending of the curtains is not
    possible (A and B drift independently). Landscape finding: un-rotated layers were all CLIENT
    (GPU) with ROT_90; buffers now drawn turned by the window's buffer transform hint and
    declaring the inverse → DEVICE, net transform 0 expected (declaring the hint itself was DEVICE
    with ROT_180, content upright); rebuilt on a hint change (`onRotated`). True Black memory: one
    scratch set per painting (12 B/px of the largest image) instead of six arrays per image:
    65–86 MB → 14–34 MB allocated per scene at 1008 × 840, output SHA-256 identical for all six
    scenes. Agent's top three: overlay lifecycle as a pure rule (`SceneOverlayPlan.sync`) and
    anchor ownership as `AnchorOwners`, both pinned; on-device layout checks (clipboard pane
    above, numeric pane, landscape: layers placed and sized right; the pane does not move the
    keyboard view, so `onMoved` is still exercised only by the code path); `loc toggle_true_black`
    on the Fn key's right swipe in all four built-in bottom rows (off until ticked). Default frame
    rates (maintainer): Aurora and River rocks 5, Beach 8, Snow/Stream/Meadow/Forest 7 (were 10,
    10, 12, 10/8/12/12). Round 6 re-parsed with all three display rails: +58.6 display (its total
    +136.5 was complete; a guess that it missed two rails was wrong); it is lower than later rounds
    because it priced motion while typing at 8 fps. Spec §0 gives the per-theme cost.
    Seventeenth round (2026-11-12, maintainer: smoothness tests relaxed? fps vs power linear? a
    day's battery; floating/split/fold/tablet; landscape power). Pixel, Aurora, untouched, refresh
    free, brightness 12, Keep Moving For 600 s, 16 × 5 min mirrored (off lay5 win5 win7 win8 offL
    lay5L win5L | reverse): off 770/768, layers 5 fps +163, window 5 / 7 / 8 fps +183 / +178 /
    +187 (display +80–82 at all three, CPU +50): **flat from 5 to 8 fps**, the §4.1 curve
    (121/198/241) was wrong; landscape window +169/+174. The "layers, landscape" arm drew window
    frames: bug — the first show at a new size renders the painting async, `setPainting` released
    the pending layers and `wake()` returned early (already ticking); now `setPainting` calls
    `syncOverlay()`. Layers gated to 10 fps+ (`SceneOverlayPlan.MIN_FRAME_MS`): ≤ ~20 mW saved at
    5 fps on the Pixel, and on a Galaxy Tab S11 (192.168.0.68, CK installed as default IME by the
    maintainer's leave) the 4 layers pushed the layers AND the keyboard window to CLIENT at 120 Hz
    (window path: DEVICE, 60 Hz). Smoothness: the scaled per-frame bounds are now absolute
    ceilings sampled densely (layers ≤ 2.2 dp, aurora ≤ 1.1 dp / 0.1 cross-fade, particles ≤ 3.2
    dp); that found a visible beach ROLL wrap at 8 fps (fixed: `SceneMotion.ROLL_HIDDEN`) and
    snowflakes stepping 3.3 dp > their width at 7 fps → Snow 8 fps (same cost). Day-of-use model
    (Fable draft, revised with round 17): `docs/eval/2026-11-12-scene-battery-day.md`, light user
    0.16–0.25 %/day, heavy chatter 1.3–2.0 %. Tablet: keyboard fine in landscape, portrait,
    pop-up window; CK has no split or floating keyboard mode; foldables get per-screen heights
    (`FoldStateTracker`, `KeyboardHeightPrefs`). Open: device check of the fix (first show after
    rotation builds layers), the Blend setting's text (says it saves battery; true only at 10+
    fps; 21 locales), brightness-102 untouched run, 9 fps, tablet power.
    Eighteenth round (2026-11-12, maintainer: close the gaps; a user from this phone's app time,
    keyboard always up in chat apps and once per 10 min in media apps; measure max brightness).
    Pixel, Aurora, untouched, refresh free, Keep Moving For 600 s, 14 × 5 min mirrored + Meadow
    ABBA at 255 (runs 7–18 resumed after the dev phone joined the car's Android Auto Wi-Fi mid-run
    7; failed attempts kept in `b18/fail1`). Motion at 5 fps: +153 / +199 / +180 mW at brightness
    12 / 102 / 255 (display +77 / +84 / +89, the rest CPU); 9 fps +187 → **flat 5–9 fps, at any
    brightness**; the step is between 9 and 10. Still picture at 255: Meadow +84 mW over Aurora
    (model predicted 84; one run — the other three Meadow runs are void, the Pixel dimmed the panel
    at a 33 °C battery with thermal status 0). Blend text now says "at 10 frames per second or
    more" in all 22 files. Real-phone user (`dumpsys usagestats`, 15 days, categories only,
    model `OWNER_*`): chat 85.8 min/day keyboard up (48 launches) → 38 min moving; 36 searches →
    2 min; aurora ~0.63 %/day at any brightness, Meadow 0.73 / 1.07 / 1.30 at 12 / 102 / 255,
    0.76 at 255 with True Black. Tablet: proxy runs (render rate, CPU, frames) found **0 frames
    on show after CK Settings had been open** — diag logs: `startTicking` ran, the
    `scheduleSelf` Choreographer callback never did until a key press drew a frame; the first
    tick now goes through `mainHandler` (`firstTick`). Pixel motion re-checked with it (a child
    was using the tablet then: YouTube Kids came to the front mid-test; up to six Back presses
    and two Chrome launches reached that session, then all tablet work stopped). **Confirmed on
    the tablet 2026-11-13** with release 9f5ccb18 (`install -r`, sha 720ba009…): show after
    Settings moved in 2 of 2 trials (pixel diff 10 s apart; framestats gaps 199–200 ms = 5 fps);
    restored Keep Moving For 20 s, Frame Rate scene default, blend on, and at 20 s the scene
    drew 0 frames 35 s after a show. Tablet gfxinfo "Total frames" counts each frame twice. The
    three data: test tabs were closed (only the owner's Google tab left), launcher in front, CK
    still default IME (allowed). Tablet power: still unmeasured (no rails, full on AC). Battery protection
    set to Maximum (80 %) at the maintainer's request 2026-11-13 (`protect_battery` 3 Basic → 1;
    undo: Basic in Battery protection): plugged in above 80 % it does NOT discharge — status 4
    "not charging", level 100, charge counter, voltage (4403 mV) and current_avg 0 flat for an hour
    with the screen awake, i.e. the charger runs the tablet. Power on this tablet needs it unplugged.
    Unplugged 2026-11-13 ~17:00; the on-battery motion run (8 mirrored 4-min arms off / 5 fps /
    10 fps layers / 10 fps window at brightness 102, `dumpsys battery` current × voltage every
    3 s) waited on its SECURE keyguard (`wm dismiss-keyguard` cannot pass it). **Measured
    2026-11-14** after the owner unlocked it (spec §3.2 item 8): motion +10 to +150 mW on ~2.6 W,
    all inside the 226 mW gap between the two "off" arms; not resolvable further with this meter.
    A first attempt slept before arm 1: the 30 s timeout ran out and a WAKEUP key while awake does
    not restart it, so set the long timeout before the unlock. Restored: auto brightness, 30 s
    timeout, Keep Moving For 20 s. Left: the run's Chrome data: tabs (needs an unlock). Byproduct: screen-off doze drain 100 → 94 % in 9.5 h
    (charge counter −504 mAh ≈ 53 mA ≈ 230 mW average; idle readings 27–47 mA).
    Nineteenth round (2026-11-14, the 12/15 fps window-path gap in spec §4.1/§4.2). Pixel, Aurora,
    untouched in Chrome, refresh free, blend off, Keep Moving For 600 s, brightness 12, portrait,
    8 × 5 min mirrored (off / 10 / 12 / 15 fps): **+357 / +364 / +390 mW**; display rails a flat
    +249–251 at all three, compute +84 / +90 / +109 (~5 mW per fps). The old fit said 444 / 493.
    Day model (`battery_stories.py`) now interpolates the measured points above 9 fps (band ±5 %):
    the tuner (60 s, 12 fps) fell from 6.4–7.4 to 5.3–6.3 %/day; the defaults (≤ 9 fps) are
    unchanged. Third attempt: the first lost the LAN during the quiet step (radios restored by
    hand), the second ran all eight arms in LANDSCAPE (auto-rotate on, phone on its side), where
    the CK Settings controls sit below the fold, so motion never turned on (0 frames every arm;
    nothing changed). The harness now locks portrait for the run and aborts on any toggle that
    does not read back or a motion arm with 0 frames. Restored: auto-rotate (1 / 0), radios,
    brightness 12, Cyberpunk, Animated scenes off, Frame Rate default, blend on, Keep Moving 20 s.
