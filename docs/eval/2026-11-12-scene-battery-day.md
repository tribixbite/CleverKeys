# What an animated theme costs over a day (Pixel 8 Pro)

2026-11-12. Model: `scripts/research/scenes/battery_stories.py` (run `python3 -I battery_stories.py`
in that directory; it writes every table to `tables.md` beside itself). First drafted by a Fable
review agent from the measurements in `docs/specs/scene-backdrops-and-power.md` (rounds 6–16);
revised the same day with round 17, which measured the window path flat from 5 to 8 fps (~180 mW)
where the earlier model interpolated 121 / 198 / 241 mW. Usage numbers are assumptions; every
physical constant in the script is tagged MEASURED or MODEL.

## 1. Inputs

| Input | Value | Kind |
|---|---|---|
| Battery | 19.5 Wh; 1 % = 702 J; 195 mW ≈ 1 %/h | spec §4.1 |
| Any scene moving at its default rate (5–8 fps), window path, refresh free, brightness 12 | **~180 mW** (115–190) | measured, rounds 13 and 17 |
| Aurora on display-hardware layers (Frame Rate 10+ only since round 17) | 150–190 mW | measured, rounds 13, 14, 16, 17 |
| Window path at 10 fps / 12 fps | 340–374 / ~444 mW | measured / old fit |
| Refresh pinned or an ARR panel | 12.3 mJ per frame | measured pinned (round 12); ARR assumed |
| Still-picture light | 153 mW per unit of mean linear light at brightness 102, a quarter at 12 | model from one measured pair (spec §4.3) |
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

## 4. Extra battery per day

Refresh free, brightness 102, True Black off, as-built defaults (round 17). % of a 19.5 Wh
battery per day, central (low–high, the measurement spread, not the usage uncertainty):

| Story | Aurora | River rocks | Stream | Forest | Snow | Meadow | Beach |
|---|---|---|---|---|---|---|---|
| Light user | 0.16 (0.11–0.17) | 0.18 | 0.18 | 0.18 | 0.20 | 0.25 | 0.23 |
| Heavy chatter | 1.32 (0.86–1.39) | 1.42 | 1.49 | 1.48 | 1.66 | 2.04 (1.57–2.11) | 1.89 |
| Writer | 0.47 | 0.52 | 0.57 | 0.56 | 0.66 | 0.88 | 0.79 |
| Keyboard up while reading | 0.28 | 0.33 | 0.36 | 0.36 | 0.45 | 0.64 | 0.57 |
| Tuner (60 s, 12 fps) | 6.4 (layers: 2.6) | 6.6 | 6.7 | 6.7 | 6.9 | 7.4 | 7.2 |

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
2. **Brightness.** Every refresh-free motion measurement was at brightness 12. The display share
   is assumed brightness-independent (it is the panel kept refreshing, not emission); one
   untouched run at brightness 102 would settle it. If it scaled with emission, the motion
   figures would roughly triple.
3. **The 5-fps window point** varies between rounds (115 in round 13, 183 in round 17); the band
   carries both. 12 and 15 fps are unmeasured (old fit).
4. **Other phones.** A fixed-60 Hz panel has no idle to lose: the pinned column applies (heavy
   chatter 0.5–1.4 %/day). An ARR panel should land near it; none measured. A tablet whose
   display hardware has few planes composed the aurora's layers on the GPU at 120 Hz, which is why
   the layers now start only at 10 fps.

## 8. Defaults the numbers do or do not support

Heavy chatter, % per day (central), Aurora / Meadow:

| Setting | Aurora | Meadow |
|---|---|---|
| as built (20 s, own rate, pause 1 s) | 1.32 | 2.04 |
| Keep Moving For 15 s | 1.03 | 1.75 |
| Keep Moving For 10 s | 0.75 | 1.46 |
| Pause After Typing 3 s | 1.21 | 1.92 |

1. **Frame rates 5–8 fps cost the same**, so the rate there is a smoothness choice, not a power
   one; 10 fps and above cost ~160 mW more while moving.
2. **Keep Moving For 20 → 10 s** saves the heavy chatter ~0.6 %/day and the light user ~0.06; a
   taste decision, not one the numbers force (the slider already goes down to 5 s).
3. **Pause After Typing 1 → 3 s** halves the writer's cost (0.47 → 0.24 %/day for the aurora) and
   changes little for anyone else; the 1 s was the maintainer's choice.
4. **True Black** saves as much for the bright scenes as every motion lever together; worth a line
   in the Scenes settings text.
