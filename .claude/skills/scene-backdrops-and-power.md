# Scene Backdrops and Power Skill

Use this skill for the animated keyboard backdrops (CleverKeys Aurora and the nature scenes),
their motion clock, the `SurfaceControl` blend path, and for measuring frame cost or power
(gfxinfo / framestats, Perfetto power rails) on a test phone. Architecture, every measured
round and the cost model: `docs/specs/scene-backdrops-and-power.md`; round logs:
`docs/history/todo-item-34-scene-rounds.md`.

<!-- moved verbatim from CLAUDE.md on 2026-11-14 -->
## Animated keyboard backdrop and frame-cost measurement (2026-11-10)

A background drawable's `invalidateSelf()` re-runs `Keyboard2View.onDraw` (all keys). The moving
aurora therefore caches the keys in a RenderNode with a compositing layer (`drawKeysCached`;
every non-background `invalidate` marks it stale) and its stars in recorded group nodes; what is
left per frame is the window's fixed cost, so frame rate is the only lever. Measuring:
`dumpsys gfxinfo <pkg>` prints "Total frames rendered" twice (process + window), so summing
the lines doubles the count; per-thread CPU from `/proc/<pid>/task/*/stat`, never with a
Settings screen of the same process open. `am force-stop tribixbite.cleverkeys` on the Pixel
makes Android fall back to Gboard as default IME: re-run `ime set` afterwards.
Power: the Pixel's energy rails need no root through Perfetto (`android.power` data source,
`collect_power_rails: true`; sysfs `iio:device*/energy_value` and the power-stats HAL dump are
denied to shell, and batterystats does not accumulate on AC). A `--background` trace stays a
0-byte file until its `duration_ms` ends: pull after that. Type with ONE `monkey -f` script
(Tap/UserWait); an `input tap` per key starts a JVM each time and swamps the measurement.
A setting read only in `onDraw` never reaches a re-shown keyboard whose display list is unchanged
(the "Animated scenes" toggle did nothing until a reload): apply it in `setKeyboard` (every pref
change) and `onWindowVisibilityChanged`. Never cross-fade two positions of sharp content (grass
"pulsed to blurry"): move it as a layer through `drawBitmapMesh` (`SceneMotion`), and preview a
scene off-device with kotlinc on `SceneRaster.kt` + `SceneMotion.kt` before a 20-minute build.
A RenderNode you keep and replay (`drawRenderNode`) loses its display list when the IME window
hides (app switch): it then draws nothing, silently, while frames keep coming. Check
`hasDisplayList()` before replaying and record again (the rain vanished after one app switch,
2026-11-10). A pixel diff of two screenshots, not a frame count, is what proves motion.
Per-frame cost: `dumpsys gfxinfo <pkg> reset`, then `framestats` (columns HandleInputStart …
SyncQueued = main thread, IssueDrawCommandsStart → SwapBuffers = RenderThread); measure the IME
in another app's field, never with CleverKeys Settings in front (its Compose screen costs more
than the keyboard). Aurora on the Pixel 8 Pro: ~1.4 ms main + ~2.8 ms issue per frame at 10 fps,
the window's fixed cost; batching stars and baking the glow did not move it.
Scripted typing for measurements (2026-11-10): NEVER type into a settings screen — the Wi-Fi
Add-network dialog closed mid-run and the remaining blind taps joined the Saga to another Wi-Fi
network (it vanished from adb). Type in a Chrome page holding only a text box, in ~30 s monkey
chunks, and check focus + `mInputShown=true` before every chunk (`session5.sh` pattern). A phone
with `animator_duration_scale` 0 never animates scenes (`systemAllowsMotion`): check it first,
or on/off runs measure the same thing. Older Chrome refuses `data:` VIEW intents; serve the page
over the LAN instead.
A Paint's alpha multiplies its shader: setting `color` to a low-alpha shadow and then a gradient
`shader` on the same Paint draws the gradient faint (the rain crowns were ghosts on device while
the off-device preview looked right). Reset the colour to opaque before the shader draw.
Kotlin evaluates arguments left to right: `gate.changed(buffer, fill())` passes the OLD
`buffer` when `fill()` grows it, so the first motion tick crashed the IME with an
ArrayIndexOutOfBounds (Note10+, 2026-11-11) while the pure tests of the gate passed. Call the
filler first. Turn motion ON in a device check: a phone left with Animated scenes off from an
"off" run never runs the frame loop, and a crash there takes the keyboard down every time it shows.
Price a scene's motion with the keyboard up and UNTOUCHED (raise "Keep Moving For" to outlast
the run): typing doubles the compute baseline and drowns the delta. Every scene costs ~9.5 mJ
of compute per frame (Pixel, 2026-11-11), so frame rate is the lever, not draw work. Pinning
`peak/min_refresh_rate` hides the display share: the panel's kernel idle timer is 80 ms
(`ro.surface_flinger.set_idle_timer_ms`), so 10 fps keeps it near full refresh (+57 mW
unpinned). Report the trace per rail (`VSYS_PWR_DISPLAY` vs the CPUCL/G3D/MIF/MEM rails), not
only the total: the display rail swings by hundreds of mW between runs and hides a 100 mW delta.
The Pixel 8 Pro's display draws on THREE rails, not one: `VSYS_PWR_DISPLAY`, `L22M_DISP` and
`S6M_LLDO1` (subsystem "LDO", r = 0.997 with the display rail). Read every rail descriptor's name
and subsystem (field 3) from the trace; a parser that kept only VDD/PWR names printed two of them
as bare ids and the display cost looked like an unknown 80 mW (2026-11-11).

<!-- moved verbatim from CLAUDE.md on 2026-11-14 -->
## SurfaceControl layers on a rotated screen (2026-11-12)

App-made `SurfaceControl` layers with portrait-native buffers are composited by the GPU in
landscape (`dumpsys SurfaceFlinger` HWC table: CLIENT, ROT_90), while the window stays DEVICE.
Draw each buffer turned by `AttachedSurfaceControl.bufferTransformHint` (the display's own
rotation) and declare the INVERSE with `setBufferTransform`: declaring the hint itself shows
upright too but leaves a net ROT_180. Landscape 90° ↔ 270° keeps the view size, so rebuild on
`addOnBufferTransformHintChangedListener`, not on bounds. Check the Comp Type column, not the picture.
A size change renders the painting async: whatever releases layers on its arrival must ask for
them again itself (a `wake()` that returns early while already ticking left the first spell after
every rotation on the window path, 2026-11-12). Fewer planes is not always cheaper elsewhere: a
Galaxy Tab S11 put four extra layers AND the keyboard window on the GPU at 120 Hz.
Power vs frame rate on the Pixel is a step, not a line: 5, 7 and 8 fps all cost ~180 mW (round 17),
10 / 12 / 15 fps 357 / 364 / 390 (round 19: display a flat +250, only compute grows). Lock
portrait (`accelerometer_rotation 0`) before a uiautomator-driven run: with auto-rotate on and the
phone on its side, CK Settings opens in landscape, its controls sit below the fold, and every
toggle silently read "?" (eight 0-frame "motion" runs, 2026-11-14). Bound smoothness with absolute per-frame ceilings sampled densely; bounds scaled by the
frame interval loosen as the rate falls and hid a visible beach wrap.
Motion's cost does not depend on brightness (round 18: display share +77 / +84 / +89 mW at 12 /
102 / 255); only the still picture does. At brightness 255 the Pixel dims the panel itself once the
battery is ~33 °C, with thermal status still 0: check the display rail of every run. On a Galaxy
Tab S11 the first `scheduleSelf` tick after a show never ran when CK Settings had been open (a
Choreographer callback with no frame to run it); the first tick now goes through the main Handler
(confirmed on the tablet 2026-11-13). A frame counter that stays flat while the keyboard is up is
the symptom; a key press "fixes" it. That tablet's gfxinfo "Total frames rendered" counts each
frame twice: take rates from the framestats rows (IntendedVsync gaps), not the total.
