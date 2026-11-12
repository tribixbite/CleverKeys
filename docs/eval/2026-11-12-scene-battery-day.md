# What an animated theme costs over a day (Pixel 8 Pro)

2026-11-12. Model: `scripts/research/scenes/battery_stories.py` (run `python3 -I battery_stories.py`
in that directory; it writes every table to `tables.md` beside itself). First drafted by a Fable
review agent from the measurements in `docs/specs/scene-backdrops-and-power.md` (rounds 6–16);
revised the same day with round 17, which measured the window path flat from 5 to 8 fps (~180 mW)
where the earlier model interpolated 121 / 198 / 241 mW, and with round 18 (9 fps on the same flat;
the motion cost independent of brightness; the still picture at maximum brightness), plus a user
modelled on a real phone's app time (§3.1). Usage numbers are assumptions; every physical
constant in the script is tagged MEASURED or MODEL.

## 1. Inputs

| Input | Value | Kind |
|---|---|---|
| Battery | 19.5 Wh; 1 % = 702 J; 195 mW ≈ 1 %/h | spec §4.1 |
| Any scene moving at its default rate (5–8 fps; 9 fps too), window path, refresh free, any brightness | **~180 mW** (150–200) | measured, rounds 17 and 18 (153 / 199 / 180 at brightness 12 / 102 / 255); round 13's 115 is the outlier |
| Aurora on display-hardware layers (Frame Rate 10+ only since round 17) | 150–190 mW | measured, rounds 13, 14, 16, 17 |
| Window path at 10 fps / 12 fps | 340–374 / ~444 mW | measured / old fit |
| Refresh pinned or an ARR panel | 12.3 mJ per frame | measured pinned (round 12); ARR assumed |
| Still-picture light | 153 mW per unit of mean linear light at brightness 102, a quarter at 12, ~233 at 255 | model from one measured pair (spec §4.3); checked at 255 (Meadow +84 mW measured and predicted, round 18) |
| Cross-device | Note10+ and Saga paid 0.53–0.58× the Pixel in round 9 (typing, 10 fps) | measured, other meters |

## 2. Motion gating as built

Animated scenes is on by default (`Config.Defaults.LOOK_BACKDROP_MOTION`). The frames run from the
keyboard showing or a touch on the keyboard view for Keep Moving For (20 s, `AuroraMotion.IDLE_MS`)
plus a 2.5-s ease-down; a key press holds the scene still and it moves again 1 s after the last
key (`TYPING_HOLD_MS`). One spell after the last key of a message therefore draws frames for
**21.5 s** (round 10 measured 22.0 s per spell). Battery saver, animator scale 0, severe heat and
battery ≤ 15 % stop it (`MotionAllowance`). A writer's thinking pauses longer than 1 s are spells
too.

## 3. The stories (assumed usage)

Public data bounds them only loosely: 186 phone pickups a day (Reviews.org 2026), ~10 texts per
US connection a day (CTIA 2024; 32–47 in marketing compilations), 36 WPM average phone typing
(Palin et al. 2019).

| Story | Assumptions | Keyboard visible | Motion per day |
|---|---|---|---|
| Light user | 30 opens, one 15-s message each, keyboard up 20 s after sending | 18 min | 10.4 min |
| Heavy chatter | 150 opens, 225 messages of 10 s, every message followed by ≥ 30 s of reading with the keyboard up | 152 min | 84 min |
| Writer | 20 short opens plus 3 × 25 min composing: 8-s typing runs, 3-s pauses, one in ten 15 s | 88 min | 29 min (73 % of it thinking pauses) |
| Keyboard up while reading | 30 short opens plus 6 × 10 min with the keyboard up and untouched | 78 min | 17 min |
| Tuner | the heavy chatter with Keep Moving For 60 s and Frame Rate 12 | 209 min | 168 min |

### 3.1 A user from a real phone's app time

`dumpsys usagestats` (in-memory monthly stats, 15.05 days to 2026-11-12) on the maintainer's own
phone, developer tools left out, grouped by category only. The keyboard rule is the maintainer's:
in chat apps the keyboard is up the whole time; in browsing, maps, media and shopping apps it
opens once per 10 minutes of use; the search app's launches are one search each.

| Category | Visible per day | Launches per day | Keyboard |
|---|---|---|---|
| Chat apps | 85.8 min | 48 | up the whole time: 85 min visible, 38 min moving (27–59 for 0.5–3 messages per launch) |
| Browsing, maps, media, shopping | 218.8 min | 126 | 22 opens + 15 searches = 36 a day, hidden ~2 s after the last key: 1.9 min moving |
| No keyboard (launchers, camera, clock, car, home cameras) | 69.6 min | 206 | none |

Of the 85 minutes the keyboard is up in chat, it moves for 38: each launch and each message buys
at most one ~22-s spell, and scrolling the chat does not touch the keyboard. The 4× longer
browsing time adds almost nothing, because a search hides the keyboard before a spell can run.

## 4. Extra battery per day

Refresh free, brightness 102, True Black off, as-built defaults (rounds 17–18). % of a 19.5 Wh
battery per day, central (low–high, the measurement spread, not the usage uncertainty):

| Story | Aurora | River rocks | Stream | Forest | Snow | Meadow | Beach |
|---|---|---|---|---|---|---|---|
| Light user | 0.16 (0.14–0.18) | 0.18 | 0.18 | 0.18 | 0.20 | 0.25 | 0.23 |
| Heavy chatter | 1.32 (1.11–1.47) | 1.42 | 1.49 | 1.48 | 1.66 | 2.04 (1.82–2.18) | 1.89 |
| Writer | 0.47 | 0.52 | 0.57 | 0.56 | 0.66 | 0.88 | 0.79 |
| Keyboard up while reading | 0.28 | 0.33 | 0.36 | 0.36 | 0.45 | 0.64 | 0.57 |
| Real phone's user (§3.1) | 0.63 (0.53–0.70) | 0.69 | 0.74 | 0.73 | 0.84 | 1.07 | 0.98 |
| Tuner (60 s, 12 fps) | 6.4 (layers: 2.6) | 6.6 | 6.7 | 6.7 | 6.9 | 7.4 | 7.2 |

By brightness (aurora / Meadow; only the still picture changes, the motion does not):

| Story | brightness 12 | 102 | 255 | 255 with True Black |
|---|---|---|---|---|
| Light user | 0.16 / 0.18 | 0.16 / 0.25 | 0.16 / 0.30 | |
| Heavy chatter | 1.30 / 1.48 | 1.32 / 2.04 | 1.34 / 2.43 | |
| Writer | 0.45 / 0.56 | 0.47 / 0.88 | 0.47 / 1.10 | |
| Real phone's user | 0.62 / 0.73 | 0.63 / 1.07 | 0.64 / 1.30 | 0.63 / 0.76 |

For the real phone's user at maximum brightness, Meadow's still picture costs as much as its
motion (0.134 vs 0.120 Wh a day); for the aurora the picture is 4 %.

In screen-on minutes (at 2.0 W): light user about 1–1.5 min a day, heavy chatter 8–12 min,
writer 3–5 min, reader 2–4 min, tuner 15–43 min. The themes differ mainly by the light of their
still picture (Meadow and Beach are bright); their moving cost is the same.

## 5. On the day's discharge curve

Assumed day (not measured): 07:00–23:00 awake, 5 h screen-on at 2.0 W, standby 0.3 W, so
100 % → 31.8 % at bedtime. Heavy chatter, central:

| | 07:00 | 11:00 | 15:00 | 19:00 | 23:00 |
|---|---|---|---|---|---|
| No animated theme | 100.0 | 82.9 | 65.9 | 48.8 | 31.8 |
| Aurora | 100.0 | 82.6 | 65.2 | 47.8 | 30.5 |
| Meadow | 100.0 | 82.4 | 64.9 | 47.3 | 29.8 |
| Beach | 100.0 | 82.5 | 65.0 | 47.4 | 29.9 |

(The Aurora row is its window-path default, 1.32 % spread evenly over the waking hours as the
script does for every theme; `tables.md` lists the layers variant instead.) Meadow for the heavy chatter: −2.0 %
by bedtime, the same level reached ~29 minutes earlier. Every other story stays under the
battery indicator's 1 % resolution.

## 6. True Black

For Meadow and Beach at brightness 102 the still picture's light is as large as the motion for
the reader and the chatter (0.3–0.75 %/day); True Black removes 79–99 % of it and works with
Animated scenes off (tables in `tables.md`).

## 7. Uncertainties, largest first

1. **Usage.** Everything scales with seconds of motion; nothing public measures keyboard-up time.
   §3.1 replaces the guess with one real phone's app time, but the keyboard rule per category
   (always up in chat, once per 10 minutes elsewhere) and the messages per launch are still
   assumptions (0.5–3 per launch moves that user from 0.46 to 0.96 %/day).
2. **Brightness: settled (round 18).** Motion costs +153 / +199 / +180 mW at brightness 12 /
   102 / 255; its display share +77 / +84 / +89. Only the still picture scales with brightness,
   and the model's prediction for it at 255 matched one measurement (+84 mW).
3. **The 5-fps window point** now has six measurements at 153–199 (rounds 17–18) against round
   13's single 115; the band is 150–200. 12 and 15 fps are unmeasured (old fit).
4. **Other phones.** A fixed-60 Hz panel has no idle to lose: the pinned column applies (heavy
   chatter 0.5–1.4 %/day). An ARR panel should land near it; none measured. The real phone of
   §3.1 is a Galaxy S25 Ultra (LTPO, adaptive refresh): its own motion cost is not measured (it is
   the development phone, never used for UI tests). A tablet whose display hardware has few planes
   composed the aurora's layers on the GPU at 120 Hz, which is why the layers now start only at
   10 fps; its power is not measured (no rails, full on AC).

## 8. Defaults the numbers do or do not support

Heavy chatter, % per day (central), Aurora / Meadow:

| Setting | Aurora | Meadow |
|---|---|---|
| as built (20 s, own rate, pause 1 s) | 1.32 | 2.04 |
| Keep Moving For 15 s | 1.03 | 1.75 |
| Keep Moving For 10 s | 0.75 | 1.46 |
| Pause After Typing 3 s | 1.21 | 1.92 |

1. **Frame rates 5–9 fps cost the same**, so the rate there is a smoothness choice, not a power
   one; 10 fps and above cost ~160 mW more while moving.
2. **Keep Moving For 20 → 10 s** saves the heavy chatter ~0.6 %/day and the light user ~0.06; a
   taste decision, not one the numbers force (the slider already goes down to 5 s).
3. **Pause After Typing 1 → 3 s** halves the writer's cost (0.47 → 0.24 %/day for the aurora) and
   changes little for anyone else; the 1 s was the maintainer's choice.
4. **True Black** saves as much for the bright scenes as every motion lever together, and more at
   high brightness (the real phone's user with Meadow at 255: 1.30 → 0.76 %/day); worth a line in
   the Scenes settings text.
