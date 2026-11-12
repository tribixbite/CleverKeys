# Battery stories — computed tables

Battery 19.5 Wh; 1 % = 702 J. Typical day: 16 h awake, 5 h screen-on at 2.0 W, 11 h standby at 300 mW → 13.3 Wh = 68.2 % by bedtime (100 → 31.8 %), average 4.26 %/h awake.

## Spell lengths (as built)

After a key press: frames from +1 s to +22.5 s = 21.5 s (+0.25 s of slowing frames at the press). After the keyboard shows with no key: 22.5 s.

## Moving cost per theme at its default rate (mW; spec §4.1 model, measured anchors in the report)

| Theme | frame ms | fps | refresh free (lo–hi) | pinned / ARR |
|---|---|---|---|---|
| Aurora (layers: Frame Rate 10+ only) | 200 | 5.00 | 170 (150–190) | 62 |
| Aurora (default: window path) | 200 | 5.00 | 180 (150–200) | 62 |
| River rocks | 200 | 5.00 | 180 (150–200) | 62 |
| Stream | 143 | 6.99 | 180 (150–200) | 86 |
| Forest | 143 | 6.99 | 180 (150–200) | 86 |
| Snow | 125 | 8.00 | 180 (150–200) | 98 |
| Meadow | 143 | 6.99 | 180 (150–200) | 86 |
| Beach | 125 | 8.00 | 180 (150–200) | 98 |

## Usage profiles

| Story | Keyboard opens | Typed bursts | Composing pauses > hold | Keyboard visible | Typing (no frames) | Motion spells | Motion per day | of visible |
|---|---|---|---|---|---|---|---|---|
| Light user | 30 | 30 | 0 | 18 min | 8 min | 60 | 10.4 min (622 s) | 57 % |
| Heavy chatter | 150 | 225 | 0 | 152 min | 38 min | 375 | 84.1 min (5044 s) | 55 % |
| Writer | 20 | 23 | 369 | 88 min | 80 min | 46 | 29.2 min (1750 s) | 33 % |
| Keyboard up while reading | 30 | 30 | 0 | 78 min | 8 min | 78 | 17.0 min (1018 s) | 22 % |
| Heavy chatter, Keep Moving For 60 s + 12 fps | 150 | 225 | 0 | 209 min | 38 min | 375 | 168.4 min (10106 s) | 81 % |

## Headline: % of battery per day, central (low–high), refresh free, brightness 102, True Black off

| Story | Aurora (layers: Frame Rate 10+ only) | Aurora (default: window path) | River rocks | Stream | Forest | Snow | Meadow | Beach |
|---|---|---|---|---|---|---|---|---|
| Light user | 0.15 (0.14–0.17) | 0.16 (0.14–0.18) | 0.18 (0.15–0.19) | 0.18 (0.16–0.20) | 0.18 (0.16–0.20) | 0.20 (0.18–0.22) | 0.25 (0.22–0.27) | 0.23 (0.20–0.25) |
| Heavy chatter | 1.25 (1.11–1.39) | 1.32 (1.11–1.47) | 1.42 (1.21–1.57) | 1.49 (1.28–1.64) | 1.48 (1.27–1.63) | 1.66 (1.44–1.80) | 2.04 (1.82–2.18) | 1.89 (1.67–2.03) |
| Writer | 0.44 (0.39–0.49) | 0.47 (0.39–0.52) | 0.52 (0.45–0.57) | 0.57 (0.49–0.61) | 0.56 (0.48–0.61) | 0.66 (0.58–0.71) | 0.88 (0.81–0.93) | 0.79 (0.72–0.84) |
| Keyboard up while reading | 0.26 (0.23–0.29) | 0.28 (0.23–0.31) | 0.33 (0.28–0.36) | 0.36 (0.32–0.39) | 0.36 (0.32–0.39) | 0.45 (0.40–0.48) | 0.64 (0.60–0.67) | 0.57 (0.52–0.60) |
| Heavy chatter, Keep Moving For 60 s + 12 fps | 2.55 (2.26–2.84) | 6.44 (5.80–7.08) | 6.58 (5.94–7.22) | 6.67 (6.03–7.31) | 6.66 (6.02–7.30) | 6.90 (6.26–7.54) | 7.42 (6.78–8.06) | 7.21 (6.57–7.85) |

Same, as minutes of screen-on time per day (central, at 2.0 W) / minutes earlier at the bedtime level:

| Story | Aurora (layers: Frame Rate 10+ only) | Aurora (default: window path) | River rocks | Stream | Forest | Snow | Meadow | Beach |
|---|---|---|---|---|---|---|---|---|
| Light user | 0.9 / 2 | 1.0 / 2 | 1.0 / 2 | 1.1 / 3 | 1.1 / 3 | 1.2 / 3 | 1.5 / 4 | 1.4 / 3 |
| Heavy chatter | 7.3 / 18 | 7.7 / 19 | 8.3 / 20 | 8.7 / 21 | 8.7 / 21 | 9.7 / 23 | 11.9 / 29 | 11.1 / 27 |
| Writer | 2.6 / 6 | 2.7 / 7 | 3.1 / 7 | 3.3 / 8 | 3.3 / 8 | 3.9 / 9 | 5.1 / 12 | 4.6 / 11 |
| Keyboard up while reading | 1.5 / 4 | 1.6 / 4 | 1.9 / 5 | 2.1 / 5 | 2.1 / 5 | 2.6 / 6 | 3.8 / 9 | 3.3 / 8 |
| Heavy chatter, Keep Moving For 60 s + 12 fps | 14.9 / 36 | 37.7 / 91 | 38.5 / 93 | 39.0 / 94 | 39.0 / 94 | 40.4 / 97 | 43.4 / 104 | 42.2 / 102 |

## Per story, per theme (refresh free, brightness 102, True Black off)

**Light user** — motion 622 s/day, keyboard visible 18 min/day, brightness 102, True Black off, Keep Moving For 20 s, frame rate scene's own

| Theme | fps | Moving, mW | Still light, mW | Motion, Wh | Light, Wh | Extra per day, Wh | % of battery | Screen-on min lost | Min earlier at bedtime |
|---|---|---|---|---|---|---|---|---|---|
| Aurora (layers: Frame Rate 10+ only) | 5 | 170 (150–190) | 2 | 0.029 (0.026–0.033) | 0.001 | 0.030 (0.027–0.034) | 0.15 (0.14–0.17) | 0.9 (0.8–1.0) | 2.2 |
| Aurora (default: window path) | 5 | 180 (150–200) | 2 | 0.031 (0.026–0.035) | 0.001 | 0.032 (0.027–0.035) | 0.16 (0.14–0.18) | 1.0 (0.8–1.1) | 2.3 |
| River rocks | 5 | 180 (150–200) | 10 | 0.031 (0.026–0.035) | 0.003 | 0.034 (0.029–0.038) | 0.18 (0.15–0.19) | 1.0 (0.9–1.1) | 2.5 |
| Stream | 7 | 180 (150–200) | 15 | 0.031 (0.026–0.035) | 0.005 | 0.036 (0.031–0.039) | 0.18 (0.16–0.20) | 1.1 (0.9–1.2) | 2.6 |
| Forest | 7 | 180 (150–200) | 15 | 0.031 (0.026–0.035) | 0.004 | 0.036 (0.030–0.039) | 0.18 (0.16–0.20) | 1.1 (0.9–1.2) | 2.6 |
| Snow | 8 | 180 (150–200) | 28 | 0.031 (0.026–0.035) | 0.009 | 0.040 (0.034–0.043) | 0.20 (0.18–0.22) | 1.2 (1.0–1.3) | 2.9 |
| Meadow | 7 | 180 (150–200) | 57 | 0.031 (0.026–0.035) | 0.017 | 0.049 (0.043–0.052) | 0.25 (0.22–0.27) | 1.5 (1.3–1.6) | 3.5 |
| Beach | 8 | 180 (150–200) | 46 | 0.031 (0.026–0.035) | 0.014 | 0.045 (0.040–0.048) | 0.23 (0.20–0.25) | 1.4 (1.2–1.5) | 3.3 |

**Heavy chatter** — motion 5044 s/day, keyboard visible 152 min/day, brightness 102, True Black off, Keep Moving For 20 s, frame rate scene's own

| Theme | fps | Moving, mW | Still light, mW | Motion, Wh | Light, Wh | Extra per day, Wh | % of battery | Screen-on min lost | Min earlier at bedtime |
|---|---|---|---|---|---|---|---|---|---|
| Aurora (layers: Frame Rate 10+ only) | 5 | 170 (150–190) | 2 | 0.238 (0.210–0.266) | 0.006 | 0.244 (0.216–0.272) | 1.25 (1.11–1.39) | 7.3 (6.5–8.2) | 17.6 |
| Aurora (default: window path) | 5 | 180 (150–200) | 2 | 0.252 (0.210–0.280) | 0.006 | 0.258 (0.216–0.286) | 1.32 (1.11–1.47) | 7.7 (6.5–8.6) | 18.6 |
| River rocks | 5 | 180 (150–200) | 10 | 0.252 (0.210–0.280) | 0.025 | 0.277 (0.235–0.305) | 1.42 (1.21–1.57) | 8.3 (7.1–9.2) | 20.0 |
| Stream | 7 | 180 (150–200) | 15 | 0.252 (0.210–0.280) | 0.039 | 0.291 (0.249–0.319) | 1.49 (1.28–1.64) | 8.7 (7.5–9.6) | 21.0 |
| Forest | 7 | 180 (150–200) | 15 | 0.252 (0.210–0.280) | 0.037 | 0.290 (0.247–0.318) | 1.48 (1.27–1.63) | 8.7 (7.4–9.5) | 20.9 |
| Snow | 8 | 180 (150–200) | 28 | 0.252 (0.210–0.280) | 0.071 | 0.323 (0.281–0.351) | 1.66 (1.44–1.80) | 9.7 (8.4–10.5) | 23.3 |
| Meadow | 7 | 180 (150–200) | 57 | 0.252 (0.210–0.280) | 0.145 | 0.398 (0.356–0.426) | 2.04 (1.82–2.18) | 11.9 (10.7–12.8) | 28.7 |
| Beach | 8 | 180 (150–200) | 46 | 0.252 (0.210–0.280) | 0.116 | 0.368 (0.326–0.396) | 1.89 (1.67–2.03) | 11.1 (9.8–11.9) | 26.6 |

**Writer** — motion 1750 s/day, keyboard visible 88 min/day, brightness 102, True Black off, Keep Moving For 20 s, frame rate scene's own

| Theme | fps | Moving, mW | Still light, mW | Motion, Wh | Light, Wh | Extra per day, Wh | % of battery | Screen-on min lost | Min earlier at bedtime |
|---|---|---|---|---|---|---|---|---|---|
| Aurora (layers: Frame Rate 10+ only) | 5 | 170 (150–190) | 2 | 0.083 (0.073–0.092) | 0.003 | 0.086 (0.076–0.096) | 0.44 (0.39–0.49) | 2.6 (2.3–2.9) | 6.2 |
| Aurora (default: window path) | 5 | 180 (150–200) | 2 | 0.087 (0.073–0.097) | 0.003 | 0.091 (0.076–0.100) | 0.47 (0.39–0.52) | 2.7 (2.3–3.0) | 6.6 |
| River rocks | 5 | 180 (150–200) | 10 | 0.087 (0.073–0.097) | 0.015 | 0.102 (0.088–0.112) | 0.52 (0.45–0.57) | 3.1 (2.6–3.4) | 7.4 |
| Stream | 7 | 180 (150–200) | 15 | 0.087 (0.073–0.097) | 0.023 | 0.110 (0.096–0.120) | 0.57 (0.49–0.61) | 3.3 (2.9–3.6) | 8.0 |
| Forest | 7 | 180 (150–200) | 15 | 0.087 (0.073–0.097) | 0.022 | 0.109 (0.094–0.119) | 0.56 (0.48–0.61) | 3.3 (2.8–3.6) | 7.9 |
| Snow | 8 | 180 (150–200) | 28 | 0.087 (0.073–0.097) | 0.041 | 0.129 (0.114–0.138) | 0.66 (0.58–0.71) | 3.9 (3.4–4.2) | 9.3 |
| Meadow | 7 | 180 (150–200) | 57 | 0.087 (0.073–0.097) | 0.084 | 0.172 (0.157–0.181) | 0.88 (0.81–0.93) | 5.1 (4.7–5.4) | 12.4 |
| Beach | 8 | 180 (150–200) | 46 | 0.087 (0.073–0.097) | 0.067 | 0.155 (0.140–0.164) | 0.79 (0.72–0.84) | 4.6 (4.2–4.9) | 11.2 |

**Keyboard up while reading** — motion 1018 s/day, keyboard visible 78 min/day, brightness 102, True Black off, Keep Moving For 20 s, frame rate scene's own

| Theme | fps | Moving, mW | Still light, mW | Motion, Wh | Light, Wh | Extra per day, Wh | % of battery | Screen-on min lost | Min earlier at bedtime |
|---|---|---|---|---|---|---|---|---|---|
| Aurora (layers: Frame Rate 10+ only) | 5 | 170 (150–190) | 2 | 0.048 (0.042–0.054) | 0.003 | 0.051 (0.045–0.057) | 0.26 (0.23–0.29) | 1.5 (1.4–1.7) | 3.7 |
| Aurora (default: window path) | 5 | 180 (150–200) | 2 | 0.051 (0.042–0.057) | 0.003 | 0.054 (0.045–0.059) | 0.28 (0.23–0.31) | 1.6 (1.4–1.8) | 3.9 |
| River rocks | 5 | 180 (150–200) | 10 | 0.051 (0.042–0.057) | 0.013 | 0.064 (0.055–0.070) | 0.33 (0.28–0.36) | 1.9 (1.7–2.1) | 4.6 |
| Stream | 7 | 180 (150–200) | 15 | 0.051 (0.042–0.057) | 0.020 | 0.071 (0.063–0.077) | 0.36 (0.32–0.39) | 2.1 (1.9–2.3) | 5.1 |
| Forest | 7 | 180 (150–200) | 15 | 0.051 (0.042–0.057) | 0.019 | 0.070 (0.062–0.076) | 0.36 (0.32–0.39) | 2.1 (1.8–2.3) | 5.1 |
| Snow | 8 | 180 (150–200) | 28 | 0.051 (0.042–0.057) | 0.037 | 0.087 (0.079–0.093) | 0.45 (0.40–0.48) | 2.6 (2.4–2.8) | 6.3 |
| Meadow | 7 | 180 (150–200) | 57 | 0.051 (0.042–0.057) | 0.075 | 0.126 (0.117–0.131) | 0.64 (0.60–0.67) | 3.8 (3.5–3.9) | 9.1 |
| Beach | 8 | 180 (150–200) | 46 | 0.051 (0.042–0.057) | 0.060 | 0.111 (0.102–0.116) | 0.57 (0.52–0.60) | 3.3 (3.1–3.5) | 8.0 |

**Heavy chatter, Keep Moving For 60 s + 12 fps** — motion 10106 s/day, keyboard visible 209 min/day, brightness 102, True Black off, Keep Moving For 60 s, frame rate 12 fps

| Theme | fps | Moving, mW | Still light, mW | Motion, Wh | Light, Wh | Extra per day, Wh | % of battery | Screen-on min lost | Min earlier at bedtime |
|---|---|---|---|---|---|---|---|---|---|
| Aurora (layers: Frame Rate 10+ only) | 12 | 174 (154–194) | 2 | 0.490 (0.433–0.546) | 0.008 | 0.497 (0.441–0.553) | 2.55 (2.26–2.84) | 14.9 (13.2–16.6) | 35.9 |
| Aurora (default: window path) | 12 | 444 (400–489) | 2 | 1.248 (1.123–1.372) | 0.008 | 1.255 (1.131–1.380) | 6.44 (5.80–7.08) | 37.7 (33.9–41.4) | 90.6 |
| River rocks | 12 | 444 (400–489) | 10 | 1.248 (1.123–1.372) | 0.035 | 1.282 (1.157–1.407) | 6.58 (5.94–7.22) | 38.5 (34.7–42.2) | 92.6 |
| Stream | 12 | 444 (400–489) | 15 | 1.248 (1.123–1.372) | 0.054 | 1.301 (1.177–1.426) | 6.67 (6.03–7.31) | 39.0 (35.3–42.8) | 93.9 |
| Forest | 12 | 444 (400–489) | 15 | 1.248 (1.123–1.372) | 0.051 | 1.299 (1.174–1.423) | 6.66 (6.02–7.30) | 39.0 (35.2–42.7) | 93.7 |
| Snow | 12 | 444 (400–489) | 28 | 1.248 (1.123–1.372) | 0.097 | 1.345 (1.220–1.470) | 6.90 (6.26–7.54) | 40.4 (36.6–44.1) | 97.1 |
| Meadow | 12 | 444 (400–489) | 57 | 1.248 (1.123–1.372) | 0.199 | 1.447 (1.322–1.571) | 7.42 (6.78–8.06) | 43.4 (39.7–47.1) | 104.4 |
| Beach | 12 | 444 (400–489) | 46 | 1.248 (1.123–1.372) | 0.159 | 1.407 (1.282–1.532) | 7.21 (6.57–7.85) | 42.2 (38.5–45.9) | 101.5 |

## Same stories at brightness 12 (the measurement level) — only the still-light column changes

**Light user** — motion 622 s/day, keyboard visible 18 min/day, brightness 12, True Black off, Keep Moving For 20 s, frame rate scene's own

| Theme | fps | Moving, mW | Still light, mW | Motion, Wh | Light, Wh | Extra per day, Wh | % of battery | Screen-on min lost | Min earlier at bedtime |
|---|---|---|---|---|---|---|---|---|---|
| Aurora (layers: Frame Rate 10+ only) | 5 | 170 (150–190) | 1 | 0.029 (0.026–0.033) | 0.000 | 0.030 (0.026–0.033) | 0.15 (0.13–0.17) | 0.9 (0.8–1.0) | 2.1 |
| Aurora (default: window path) | 5 | 180 (150–200) | 1 | 0.031 (0.026–0.035) | 0.000 | 0.031 (0.026–0.035) | 0.16 (0.13–0.18) | 0.9 (0.8–1.0) | 2.3 |
| River rocks | 5 | 180 (150–200) | 3 | 0.031 (0.026–0.035) | 0.001 | 0.032 (0.027–0.035) | 0.16 (0.14–0.18) | 1.0 (0.8–1.1) | 2.3 |
| Stream | 7 | 180 (150–200) | 4 | 0.031 (0.026–0.035) | 0.001 | 0.032 (0.027–0.036) | 0.17 (0.14–0.18) | 1.0 (0.8–1.1) | 2.3 |
| Forest | 7 | 180 (150–200) | 4 | 0.031 (0.026–0.035) | 0.001 | 0.032 (0.027–0.036) | 0.17 (0.14–0.18) | 1.0 (0.8–1.1) | 2.3 |
| Snow | 8 | 180 (150–200) | 7 | 0.031 (0.026–0.035) | 0.002 | 0.033 (0.028–0.037) | 0.17 (0.14–0.19) | 1.0 (0.8–1.1) | 2.4 |
| Meadow | 7 | 180 (150–200) | 14 | 0.031 (0.026–0.035) | 0.004 | 0.036 (0.030–0.039) | 0.18 (0.16–0.20) | 1.1 (0.9–1.2) | 2.6 |
| Beach | 8 | 180 (150–200) | 12 | 0.031 (0.026–0.035) | 0.004 | 0.035 (0.029–0.038) | 0.18 (0.15–0.20) | 1.0 (0.9–1.1) | 2.5 |

**Heavy chatter** — motion 5044 s/day, keyboard visible 152 min/day, brightness 12, True Black off, Keep Moving For 20 s, frame rate scene's own

| Theme | fps | Moving, mW | Still light, mW | Motion, Wh | Light, Wh | Extra per day, Wh | % of battery | Screen-on min lost | Min earlier at bedtime |
|---|---|---|---|---|---|---|---|---|---|
| Aurora (layers: Frame Rate 10+ only) | 5 | 170 (150–190) | 1 | 0.238 (0.210–0.266) | 0.001 | 0.240 (0.212–0.268) | 1.23 (1.09–1.37) | 7.2 (6.3–8.0) | 17.3 |
| Aurora (default: window path) | 5 | 180 (150–200) | 1 | 0.252 (0.210–0.280) | 0.001 | 0.254 (0.212–0.282) | 1.30 (1.09–1.44) | 7.6 (6.3–8.4) | 18.3 |
| River rocks | 5 | 180 (150–200) | 3 | 0.252 (0.210–0.280) | 0.006 | 0.259 (0.217–0.287) | 1.33 (1.11–1.47) | 7.8 (6.5–8.6) | 18.7 |
| Stream | 7 | 180 (150–200) | 4 | 0.252 (0.210–0.280) | 0.010 | 0.262 (0.220–0.290) | 1.34 (1.13–1.49) | 7.9 (6.6–8.7) | 18.9 |
| Forest | 7 | 180 (150–200) | 4 | 0.252 (0.210–0.280) | 0.009 | 0.262 (0.220–0.290) | 1.34 (1.13–1.49) | 7.8 (6.6–8.7) | 18.9 |
| Snow | 8 | 180 (150–200) | 7 | 0.252 (0.210–0.280) | 0.018 | 0.270 (0.228–0.298) | 1.39 (1.17–1.53) | 8.1 (6.8–8.9) | 19.5 |
| Meadow | 7 | 180 (150–200) | 14 | 0.252 (0.210–0.280) | 0.037 | 0.289 (0.247–0.317) | 1.48 (1.27–1.62) | 8.7 (7.4–9.5) | 20.8 |
| Beach | 8 | 180 (150–200) | 12 | 0.252 (0.210–0.280) | 0.029 | 0.281 (0.239–0.309) | 1.44 (1.23–1.59) | 8.4 (7.2–9.3) | 20.3 |

**Writer** — motion 1750 s/day, keyboard visible 88 min/day, brightness 12, True Black off, Keep Moving For 20 s, frame rate scene's own

| Theme | fps | Moving, mW | Still light, mW | Motion, Wh | Light, Wh | Extra per day, Wh | % of battery | Screen-on min lost | Min earlier at bedtime |
|---|---|---|---|---|---|---|---|---|---|
| Aurora (layers: Frame Rate 10+ only) | 5 | 170 (150–190) | 1 | 0.083 (0.073–0.092) | 0.001 | 0.083 (0.074–0.093) | 0.43 (0.38–0.48) | 2.5 (2.2–2.8) | 6.0 |
| Aurora (default: window path) | 5 | 180 (150–200) | 1 | 0.087 (0.073–0.097) | 0.001 | 0.088 (0.074–0.098) | 0.45 (0.38–0.50) | 2.6 (2.2–2.9) | 6.4 |
| River rocks | 5 | 180 (150–200) | 3 | 0.087 (0.073–0.097) | 0.004 | 0.091 (0.077–0.101) | 0.47 (0.39–0.52) | 2.7 (2.3–3.0) | 6.6 |
| Stream | 7 | 180 (150–200) | 4 | 0.087 (0.073–0.097) | 0.006 | 0.093 (0.079–0.103) | 0.48 (0.40–0.53) | 2.8 (2.4–3.1) | 6.7 |
| Forest | 7 | 180 (150–200) | 4 | 0.087 (0.073–0.097) | 0.005 | 0.093 (0.078–0.103) | 0.48 (0.40–0.53) | 2.8 (2.4–3.1) | 6.7 |
| Snow | 8 | 180 (150–200) | 7 | 0.087 (0.073–0.097) | 0.010 | 0.098 (0.083–0.108) | 0.50 (0.43–0.55) | 2.9 (2.5–3.2) | 7.1 |
| Meadow | 7 | 180 (150–200) | 14 | 0.087 (0.073–0.097) | 0.021 | 0.109 (0.094–0.118) | 0.56 (0.48–0.61) | 3.3 (2.8–3.6) | 7.8 |
| Beach | 8 | 180 (150–200) | 12 | 0.087 (0.073–0.097) | 0.017 | 0.104 (0.090–0.114) | 0.54 (0.46–0.59) | 3.1 (2.7–3.4) | 7.5 |

**Keyboard up while reading** — motion 1018 s/day, keyboard visible 78 min/day, brightness 12, True Black off, Keep Moving For 20 s, frame rate scene's own

| Theme | fps | Moving, mW | Still light, mW | Motion, Wh | Light, Wh | Extra per day, Wh | % of battery | Screen-on min lost | Min earlier at bedtime |
|---|---|---|---|---|---|---|---|---|---|
| Aurora (layers: Frame Rate 10+ only) | 5 | 170 (150–190) | 1 | 0.048 (0.042–0.054) | 0.001 | 0.049 (0.043–0.054) | 0.25 (0.22–0.28) | 1.5 (1.3–1.6) | 3.5 |
| Aurora (default: window path) | 5 | 180 (150–200) | 1 | 0.051 (0.042–0.057) | 0.001 | 0.052 (0.043–0.057) | 0.26 (0.22–0.29) | 1.5 (1.3–1.7) | 3.7 |
| River rocks | 5 | 180 (150–200) | 3 | 0.051 (0.042–0.057) | 0.003 | 0.054 (0.046–0.060) | 0.28 (0.23–0.31) | 1.6 (1.4–1.8) | 3.9 |
| Stream | 7 | 180 (150–200) | 4 | 0.051 (0.042–0.057) | 0.005 | 0.056 (0.048–0.062) | 0.29 (0.24–0.32) | 1.7 (1.4–1.8) | 4.0 |
| Forest | 7 | 180 (150–200) | 4 | 0.051 (0.042–0.057) | 0.005 | 0.056 (0.047–0.061) | 0.29 (0.24–0.31) | 1.7 (1.4–1.8) | 4.0 |
| Snow | 8 | 180 (150–200) | 7 | 0.051 (0.042–0.057) | 0.009 | 0.060 (0.052–0.066) | 0.31 (0.26–0.34) | 1.8 (1.5–2.0) | 4.3 |
| Meadow | 7 | 180 (150–200) | 14 | 0.051 (0.042–0.057) | 0.019 | 0.070 (0.061–0.075) | 0.36 (0.31–0.39) | 2.1 (1.8–2.3) | 5.0 |
| Beach | 8 | 180 (150–200) | 12 | 0.051 (0.042–0.057) | 0.015 | 0.066 (0.057–0.072) | 0.34 (0.29–0.37) | 2.0 (1.7–2.1) | 4.8 |

**Heavy chatter, Keep Moving For 60 s + 12 fps** — motion 10106 s/day, keyboard visible 209 min/day, brightness 12, True Black off, Keep Moving For 60 s, frame rate 12 fps

| Theme | fps | Moving, mW | Still light, mW | Motion, Wh | Light, Wh | Extra per day, Wh | % of battery | Screen-on min lost | Min earlier at bedtime |
|---|---|---|---|---|---|---|---|---|---|
| Aurora (layers: Frame Rate 10+ only) | 12 | 174 (154–194) | 1 | 0.490 (0.433–0.546) | 0.002 | 0.492 (0.435–0.548) | 2.52 (2.23–2.81) | 14.7 (13.1–16.4) | 35.5 |
| Aurora (default: window path) | 12 | 444 (400–489) | 1 | 1.248 (1.123–1.372) | 0.002 | 1.250 (1.125–1.374) | 6.41 (5.77–7.05) | 37.5 (33.7–41.2) | 90.2 |
| River rocks | 12 | 444 (400–489) | 3 | 1.248 (1.123–1.372) | 0.009 | 1.256 (1.132–1.381) | 6.44 (5.80–7.08) | 37.7 (33.9–41.4) | 90.7 |
| Stream | 12 | 444 (400–489) | 4 | 1.248 (1.123–1.372) | 0.014 | 1.261 (1.136–1.386) | 6.47 (5.83–7.11) | 37.8 (34.1–41.6) | 91.0 |
| Forest | 12 | 444 (400–489) | 4 | 1.248 (1.123–1.372) | 0.013 | 1.260 (1.136–1.385) | 6.46 (5.82–7.10) | 37.8 (34.1–41.6) | 91.0 |
| Snow | 12 | 444 (400–489) | 7 | 1.248 (1.123–1.372) | 0.025 | 1.272 (1.147–1.397) | 6.52 (5.88–7.16) | 38.2 (34.4–41.9) | 91.8 |
| Meadow | 12 | 444 (400–489) | 14 | 1.248 (1.123–1.372) | 0.050 | 1.298 (1.173–1.423) | 6.66 (6.02–7.29) | 38.9 (35.2–42.7) | 93.7 |
| Beach | 12 | 444 (400–489) | 12 | 1.248 (1.123–1.372) | 0.040 | 1.288 (1.163–1.412) | 6.60 (5.96–7.24) | 38.6 (34.9–42.4) | 92.9 |

## Same stories at brightness 255 (maximum) — only the still-light column changes

**Light user** — motion 622 s/day, keyboard visible 18 min/day, brightness 255, True Black off, Keep Moving For 20 s, frame rate scene's own

| Theme | fps | Moving, mW | Still light, mW | Motion, Wh | Light, Wh | Extra per day, Wh | % of battery | Screen-on min lost | Min earlier at bedtime |
|---|---|---|---|---|---|---|---|---|---|
| Aurora (layers: Frame Rate 10+ only) | 5 | 170 (150–190) | 3 | 0.029 (0.026–0.033) | 0.001 | 0.030 (0.027–0.034) | 0.16 (0.14–0.17) | 0.9 (0.8–1.0) | 2.2 |
| Aurora (default: window path) | 5 | 180 (150–200) | 3 | 0.031 (0.026–0.035) | 0.001 | 0.032 (0.027–0.036) | 0.16 (0.14–0.18) | 1.0 (0.8–1.1) | 2.3 |
| River rocks | 5 | 180 (150–200) | 15 | 0.031 (0.026–0.035) | 0.005 | 0.036 (0.031–0.039) | 0.18 (0.16–0.20) | 1.1 (0.9–1.2) | 2.6 |
| Stream | 7 | 180 (150–200) | 24 | 0.031 (0.026–0.035) | 0.007 | 0.038 (0.033–0.042) | 0.20 (0.17–0.21) | 1.1 (1.0–1.3) | 2.8 |
| Forest | 7 | 180 (150–200) | 22 | 0.031 (0.026–0.035) | 0.007 | 0.038 (0.033–0.041) | 0.19 (0.17–0.21) | 1.1 (1.0–1.2) | 2.7 |
| Snow | 8 | 180 (150–200) | 43 | 0.031 (0.026–0.035) | 0.013 | 0.044 (0.039–0.048) | 0.23 (0.20–0.24) | 1.3 (1.2–1.4) | 3.2 |
| Meadow | 7 | 180 (150–200) | 87 | 0.031 (0.026–0.035) | 0.026 | 0.058 (0.052–0.061) | 0.30 (0.27–0.31) | 1.7 (1.6–1.8) | 4.2 |
| Beach | 8 | 180 (150–200) | 70 | 0.031 (0.026–0.035) | 0.021 | 0.052 (0.047–0.056) | 0.27 (0.24–0.29) | 1.6 (1.4–1.7) | 3.8 |

**Heavy chatter** — motion 5044 s/day, keyboard visible 152 min/day, brightness 255, True Black off, Keep Moving For 20 s, frame rate scene's own

| Theme | fps | Moving, mW | Still light, mW | Motion, Wh | Light, Wh | Extra per day, Wh | % of battery | Screen-on min lost | Min earlier at bedtime |
|---|---|---|---|---|---|---|---|---|---|
| Aurora (layers: Frame Rate 10+ only) | 5 | 170 (150–190) | 3 | 0.238 (0.210–0.266) | 0.009 | 0.247 (0.219–0.275) | 1.27 (1.12–1.41) | 7.4 (6.6–8.2) | 17.8 |
| Aurora (default: window path) | 5 | 180 (150–200) | 3 | 0.252 (0.210–0.280) | 0.009 | 0.261 (0.219–0.289) | 1.34 (1.12–1.48) | 7.8 (6.6–8.7) | 18.8 |
| River rocks | 5 | 180 (150–200) | 15 | 0.252 (0.210–0.280) | 0.038 | 0.291 (0.249–0.319) | 1.49 (1.27–1.63) | 8.7 (7.5–9.6) | 21.0 |
| Stream | 7 | 180 (150–200) | 24 | 0.252 (0.210–0.280) | 0.060 | 0.312 (0.270–0.340) | 1.60 (1.38–1.74) | 9.4 (8.1–10.2) | 22.5 |
| Forest | 7 | 180 (150–200) | 22 | 0.252 (0.210–0.280) | 0.057 | 0.309 (0.267–0.337) | 1.58 (1.37–1.73) | 9.3 (8.0–10.1) | 22.3 |
| Snow | 8 | 180 (150–200) | 43 | 0.252 (0.210–0.280) | 0.108 | 0.360 (0.318–0.388) | 1.85 (1.63–1.99) | 10.8 (9.6–11.7) | 26.0 |
| Meadow | 7 | 180 (150–200) | 87 | 0.252 (0.210–0.280) | 0.221 | 0.473 (0.431–0.502) | 2.43 (2.21–2.57) | 14.2 (12.9–15.0) | 34.2 |
| Beach | 8 | 180 (150–200) | 70 | 0.252 (0.210–0.280) | 0.177 | 0.429 (0.387–0.457) | 2.20 (1.98–2.34) | 12.9 (11.6–13.7) | 31.0 |

**Writer** — motion 1750 s/day, keyboard visible 88 min/day, brightness 255, True Black off, Keep Moving For 20 s, frame rate scene's own

| Theme | fps | Moving, mW | Still light, mW | Motion, Wh | Light, Wh | Extra per day, Wh | % of battery | Screen-on min lost | Min earlier at bedtime |
|---|---|---|---|---|---|---|---|---|---|
| Aurora (layers: Frame Rate 10+ only) | 5 | 170 (150–190) | 3 | 0.083 (0.073–0.092) | 0.005 | 0.088 (0.078–0.097) | 0.45 (0.40–0.50) | 2.6 (2.3–2.9) | 6.3 |
| Aurora (default: window path) | 5 | 180 (150–200) | 3 | 0.087 (0.073–0.097) | 0.005 | 0.092 (0.078–0.102) | 0.47 (0.40–0.52) | 2.8 (2.3–3.1) | 6.7 |
| River rocks | 5 | 180 (150–200) | 15 | 0.087 (0.073–0.097) | 0.022 | 0.110 (0.095–0.119) | 0.56 (0.49–0.61) | 3.3 (2.9–3.6) | 7.9 |
| Stream | 7 | 180 (150–200) | 24 | 0.087 (0.073–0.097) | 0.035 | 0.122 (0.107–0.132) | 0.63 (0.55–0.68) | 3.7 (3.2–4.0) | 8.8 |
| Forest | 7 | 180 (150–200) | 22 | 0.087 (0.073–0.097) | 0.033 | 0.120 (0.106–0.130) | 0.62 (0.54–0.67) | 3.6 (3.2–3.9) | 8.7 |
| Snow | 8 | 180 (150–200) | 43 | 0.087 (0.073–0.097) | 0.063 | 0.150 (0.136–0.160) | 0.77 (0.69–0.82) | 4.5 (4.1–4.8) | 10.8 |
| Meadow | 7 | 180 (150–200) | 87 | 0.087 (0.073–0.097) | 0.128 | 0.215 (0.201–0.225) | 1.10 (1.03–1.15) | 6.5 (6.0–6.8) | 15.5 |
| Beach | 8 | 180 (150–200) | 70 | 0.087 (0.073–0.097) | 0.102 | 0.190 (0.175–0.199) | 0.97 (0.90–1.02) | 5.7 (5.3–6.0) | 13.7 |

**Keyboard up while reading** — motion 1018 s/day, keyboard visible 78 min/day, brightness 255, True Black off, Keep Moving For 20 s, frame rate scene's own

| Theme | fps | Moving, mW | Still light, mW | Motion, Wh | Light, Wh | Extra per day, Wh | % of battery | Screen-on min lost | Min earlier at bedtime |
|---|---|---|---|---|---|---|---|---|---|
| Aurora (layers: Frame Rate 10+ only) | 5 | 170 (150–190) | 3 | 0.048 (0.042–0.054) | 0.004 | 0.052 (0.047–0.058) | 0.27 (0.24–0.30) | 1.6 (1.4–1.7) | 3.8 |
| Aurora (default: window path) | 5 | 180 (150–200) | 3 | 0.051 (0.042–0.057) | 0.004 | 0.055 (0.047–0.061) | 0.28 (0.24–0.31) | 1.7 (1.4–1.8) | 4.0 |
| River rocks | 5 | 180 (150–200) | 15 | 0.051 (0.042–0.057) | 0.020 | 0.071 (0.062–0.076) | 0.36 (0.32–0.39) | 2.1 (1.9–2.3) | 5.1 |
| Stream | 7 | 180 (150–200) | 24 | 0.051 (0.042–0.057) | 0.031 | 0.082 (0.073–0.087) | 0.42 (0.37–0.45) | 2.4 (2.2–2.6) | 5.9 |
| Forest | 7 | 180 (150–200) | 22 | 0.051 (0.042–0.057) | 0.029 | 0.080 (0.072–0.086) | 0.41 (0.37–0.44) | 2.4 (2.1–2.6) | 5.8 |
| Snow | 8 | 180 (150–200) | 43 | 0.051 (0.042–0.057) | 0.056 | 0.106 (0.098–0.112) | 0.55 (0.50–0.58) | 3.2 (2.9–3.4) | 7.7 |
| Meadow | 7 | 180 (150–200) | 87 | 0.051 (0.042–0.057) | 0.114 | 0.164 (0.156–0.170) | 0.84 (0.80–0.87) | 4.9 (4.7–5.1) | 11.9 |
| Beach | 8 | 180 (150–200) | 70 | 0.051 (0.042–0.057) | 0.091 | 0.142 (0.133–0.147) | 0.73 (0.68–0.76) | 4.3 (4.0–4.4) | 10.2 |

**Heavy chatter, Keep Moving For 60 s + 12 fps** — motion 10106 s/day, keyboard visible 209 min/day, brightness 255, True Black off, Keep Moving For 60 s, frame rate 12 fps

| Theme | fps | Moving, mW | Still light, mW | Motion, Wh | Light, Wh | Extra per day, Wh | % of battery | Screen-on min lost | Min earlier at bedtime |
|---|---|---|---|---|---|---|---|---|---|
| Aurora (layers: Frame Rate 10+ only) | 12 | 174 (154–194) | 3 | 0.490 (0.433–0.546) | 0.012 | 0.501 (0.445–0.557) | 2.57 (2.28–2.86) | 15.0 (13.4–16.7) | 36.2 |
| Aurora (default: window path) | 12 | 444 (400–489) | 3 | 1.248 (1.123–1.372) | 0.012 | 1.259 (1.135–1.384) | 6.46 (5.82–7.10) | 37.8 (34.0–41.5) | 90.9 |
| River rocks | 12 | 444 (400–489) | 15 | 1.248 (1.123–1.372) | 0.053 | 1.300 (1.175–1.425) | 6.67 (6.03–7.31) | 39.0 (35.3–42.8) | 93.9 |
| Stream | 12 | 444 (400–489) | 24 | 1.248 (1.123–1.372) | 0.082 | 1.329 (1.205–1.454) | 6.82 (6.18–7.46) | 39.9 (36.1–43.6) | 96.0 |
| Forest | 12 | 444 (400–489) | 22 | 1.248 (1.123–1.372) | 0.078 | 1.325 (1.201–1.450) | 6.80 (6.16–7.44) | 39.8 (36.0–43.5) | 95.7 |
| Snow | 12 | 444 (400–489) | 43 | 1.248 (1.123–1.372) | 0.148 | 1.396 (1.271–1.521) | 7.16 (6.52–7.80) | 41.9 (38.1–45.6) | 100.8 |
| Meadow | 12 | 444 (400–489) | 87 | 1.248 (1.123–1.372) | 0.303 | 1.551 (1.426–1.675) | 7.95 (7.31–8.59) | 46.5 (42.8–50.3) | 111.9 |
| Beach | 12 | 444 (400–489) | 70 | 1.248 (1.123–1.372) | 0.242 | 1.490 (1.365–1.615) | 7.64 (7.00–8.28) | 44.7 (41.0–48.4) | 107.5 |

## Example user from a real phone's app time

From 15.05 days of `dumpsys usagestats` on the maintainer's phone (developer tools left out): chat apps 85.8 min/day with the keyboard up the whole time (48 launches), browsing, maps, media and shopping 218.8 min/day with one search per 10 minutes plus the search app's 14.9 searches (36 keyboard opens), no keyboard 69.6 min/day.

| Story | Keyboard opens | Typed bursts | Composing pauses > hold | Keyboard visible | Typing (no frames) | Motion spells | Motion per day | of visible |
|---|---|---|---|---|---|---|---|---|
| Owner's phone: chat apps, keyboard always up | 48 | 24 | 0 | 85 min | 4 min | 72 | 26.7 min (1602 s) | 31 % |
| Owner's phone: chat apps, keyboard always up | 48 | 72 | 0 | 85 min | 12 min | 120 | 38.1 min (2286 s) | 45 % |
| Owner's phone: chat apps, keyboard always up | 48 | 144 | 0 | 85 min | 24 min | 192 | 59.4 min (3564 s) | 70 % |
| Owner's phone: browsing, maps, media (one search per 10 min) | 36 | 36 | 0 | 7 min | 5 min | 72 | 1.9 min (117 s) | 27 % |

% of battery per day, central motion (motion band from the measurement spread); the chat-rate column uses 0.5 / 3 messages per launch for its low / high

| Theme | brightness 12 | brightness 102 | brightness 255 | brightness 255, True Black | chat rate low–high at 102 |
|---|---|---|---|---|---|
| Aurora (layers: Frame Rate 10+ only) | 0.59 (0.52–0.65) | 0.60 (0.53–0.67) | 0.61 (0.54–0.68) | 0.60 | 0.43–0.91 |
| Aurora (default: window path) | 0.62 (0.52–0.69) | 0.63 (0.53–0.70) | 0.64 (0.54–0.71) | 0.63 | 0.46–0.96 |
| River rocks | 0.64 (0.53–0.70) | 0.69 (0.59–0.76) | 0.74 (0.63–0.80) | 0.64 | 0.52–1.02 |
| Stream | 0.65 (0.54–0.72) | 0.74 (0.64–0.81) | 0.80 (0.70–0.87) | 0.62 | 0.56–1.07 |
| Forest | 0.65 (0.54–0.71) | 0.73 (0.63–0.80) | 0.79 (0.69–0.86) | 0.63 | 0.56–1.06 |
| Snow | 0.67 (0.57–0.74) | 0.84 (0.73–0.91) | 0.95 (0.85–1.02) | 0.63 | 0.66–1.16 |
| Meadow | 0.73 (0.63–0.80) | 1.07 (0.97–1.14) | 1.30 (1.20–1.37) | 0.76 | 0.89–1.40 |
| Beach | 0.71 (0.60–0.78) | 0.98 (0.87–1.05) | 1.17 (1.06–1.23) | 0.62 | 0.80–1.31 |

Same day split into motion and still light at brightness 255 (Wh/day):

| Theme | motion | still light | motion share |
|---|---|---|---|
| Aurora (layers: Frame Rate 10+ only) | 0.113 | 0.005 | 96 % |
| Aurora (default: window path) | 0.120 | 0.005 | 96 % |
| River rocks | 0.120 | 0.023 | 84 % |
| Stream | 0.120 | 0.036 | 77 % |
| Forest | 0.120 | 0.034 | 78 % |
| Snow | 0.120 | 0.066 | 65 % |
| Meadow | 0.120 | 0.134 | 47 % |
| Beach | 0.120 | 0.107 | 53 % |

## True Black: still-light cost by brightness

**Heavy chatter**, still-light cost of the picture while the keyboard is visible (152 min/day), vs a black keyboard, Wh/day (% of battery)

| Theme | brightness 12 | brightness 12, True Black | brightness 102 | brightness 102, True Black | brightness 255 | brightness 255, True Black |
|---|---|---|---|---|---|---|
| Aurora (layers: Frame Rate 10+ only) | 0.001 (0.01 %) | 0.001 (0.00 %) | 0.006 (0.03 %) | 0.003 (0.02 %) | 0.009 (0.04 %) | 0.005 (0.03 %) |
| Aurora (default: window path) | 0.001 (0.01 %) | 0.001 (0.00 %) | 0.006 (0.03 %) | 0.003 (0.02 %) | 0.009 (0.04 %) | 0.005 (0.03 %) |
| River rocks | 0.006 (0.03 %) | 0.001 (0.01 %) | 0.025 (0.13 %) | 0.005 (0.02 %) | 0.038 (0.20 %) | 0.007 (0.04 %) |
| Stream | 0.010 (0.05 %) | 0.000 (0.00 %) | 0.039 (0.20 %) | 0.002 (0.01 %) | 0.060 (0.31 %) | 0.002 (0.01 %) |
| Forest | 0.009 (0.05 %) | 0.001 (0.01 %) | 0.037 (0.19 %) | 0.004 (0.02 %) | 0.057 (0.29 %) | 0.006 (0.03 %) |
| Snow | 0.018 (0.09 %) | 0.000 (0.00 %) | 0.071 (0.36 %) | 0.002 (0.01 %) | 0.108 (0.56 %) | 0.003 (0.02 %) |
| Meadow | 0.037 (0.19 %) | 0.008 (0.04 %) | 0.145 (0.75 %) | 0.030 (0.16 %) | 0.221 (1.13 %) | 0.046 (0.24 %) |
| Beach | 0.029 (0.15 %) | 0.000 (0.00 %) | 0.116 (0.60 %) | 0.001 (0.01 %) | 0.177 (0.91 %) | 0.002 (0.01 %) |

**Keyboard up while reading**, still-light cost of the picture while the keyboard is visible (78 min/day), vs a black keyboard, Wh/day (% of battery)

| Theme | brightness 12 | brightness 12, True Black | brightness 102 | brightness 102, True Black | brightness 255 | brightness 255, True Black |
|---|---|---|---|---|---|---|
| Aurora (layers: Frame Rate 10+ only) | 0.001 (0.00 %) | 0.000 (0.00 %) | 0.003 (0.01 %) | 0.002 (0.01 %) | 0.004 (0.02 %) | 0.003 (0.01 %) |
| Aurora (default: window path) | 0.001 (0.00 %) | 0.000 (0.00 %) | 0.003 (0.01 %) | 0.002 (0.01 %) | 0.004 (0.02 %) | 0.003 (0.01 %) |
| River rocks | 0.003 (0.02 %) | 0.001 (0.00 %) | 0.013 (0.07 %) | 0.002 (0.01 %) | 0.020 (0.10 %) | 0.004 (0.02 %) |
| Stream | 0.005 (0.03 %) | 0.000 (0.00 %) | 0.020 (0.10 %) | 0.001 (0.00 %) | 0.031 (0.16 %) | 0.001 (0.01 %) |
| Forest | 0.005 (0.02 %) | 0.001 (0.00 %) | 0.019 (0.10 %) | 0.002 (0.01 %) | 0.029 (0.15 %) | 0.003 (0.02 %) |
| Snow | 0.009 (0.05 %) | 0.000 (0.00 %) | 0.037 (0.19 %) | 0.001 (0.01 %) | 0.056 (0.28 %) | 0.002 (0.01 %) |
| Meadow | 0.019 (0.10 %) | 0.004 (0.02 %) | 0.075 (0.38 %) | 0.016 (0.08 %) | 0.114 (0.58 %) | 0.024 (0.12 %) |
| Beach | 0.015 (0.08 %) | 0.000 (0.00 %) | 0.060 (0.31 %) | 0.001 (0.00 %) | 0.091 (0.47 %) | 0.001 (0.00 %) |

## Discharge curve shift

**Heavy chatter**: battery % at hours after waking (07:00), baseline vs theme (central)

| | +0 h (07:00) | +4 h (11:00) | +8 h (15:00) | +12 h (19:00) | +16 h (23:00) |
|---|---|---|---|---|---|
| Baseline | 100.0 | 82.9 | 65.9 | 48.8 | 31.8 |
| Aurora (layers: Frame Rate 10+ only) | 100.0 | 82.6 | 65.3 | 47.9 | 30.5 |
| Meadow | 100.0 | 82.4 | 64.9 | 47.3 | 29.8 |
| Beach | 100.0 | 82.5 | 65.0 | 47.4 | 29.9 |

**Keyboard up while reading**: battery % at hours after waking (07:00), baseline vs theme (central)

| | +0 h (07:00) | +4 h (11:00) | +8 h (15:00) | +12 h (19:00) | +16 h (23:00) |
|---|---|---|---|---|---|
| Baseline | 100.0 | 82.9 | 65.9 | 48.8 | 31.8 |
| Aurora (layers: Frame Rate 10+ only) | 100.0 | 82.9 | 65.8 | 48.7 | 31.5 |
| Meadow | 100.0 | 82.8 | 65.6 | 48.4 | 31.2 |
| Beach | 100.0 | 82.8 | 65.6 | 48.4 | 31.2 |

## Candidate default changes

**Light user**: % of battery per day (central), by setting

| Setting | Aurora (layers: Frame Rate 10+ only) | Aurora (default: window path) | River rocks | Stream | Forest | Snow | Meadow | Beach |
|---|---|---|---|---|---|---|---|---|
| as built: Keep Moving For 20 s, own fps (622 s motion) | 0.154 | 0.163 | 0.175 | 0.184 | 0.183 | 0.203 | 0.249 | 0.231 |
| Keep Moving For 15 s (548 s motion) | 0.136 | 0.144 | 0.156 | 0.164 | 0.163 | 0.184 | 0.230 | 0.212 |
| Keep Moving For 10 s (398 s motion) | 0.100 | 0.105 | 0.117 | 0.126 | 0.125 | 0.146 | 0.191 | 0.173 |
| Frame rate 5 fps for every scene (622 s motion) | 0.154 | 0.163 | 0.175 | 0.184 | 0.183 | 0.203 | 0.249 | 0.231 |
| Keep Moving For 10 s + 5 fps (398 s motion) | 0.100 | 0.105 | 0.117 | 0.126 | 0.125 | 0.146 | 0.191 | 0.173 |
| Pause After Typing 2 s (592 s motion) | 0.147 | 0.155 | 0.167 | 0.176 | 0.175 | 0.196 | 0.241 | 0.223 |
| Pause After Typing 3 s (562 s motion) | 0.140 | 0.148 | 0.160 | 0.168 | 0.167 | 0.188 | 0.233 | 0.216 |

**Heavy chatter**: % of battery per day (central), by setting

| Setting | Aurora (layers: Frame Rate 10+ only) | Aurora (default: window path) | River rocks | Stream | Forest | Snow | Meadow | Beach |
|---|---|---|---|---|---|---|---|---|
| as built: Keep Moving For 20 s, own fps (5044 s motion) | 1.250 | 1.322 | 1.423 | 1.495 | 1.485 | 1.658 | 2.039 | 1.890 |
| Keep Moving For 15 s (3919 s motion) | 0.978 | 1.034 | 1.134 | 1.206 | 1.196 | 1.370 | 1.751 | 1.601 |
| Keep Moving For 10 s (2794 s motion) | 0.705 | 0.745 | 0.846 | 0.918 | 0.908 | 1.081 | 1.462 | 1.313 |
| Frame rate 5 fps for every scene (5044 s motion) | 1.250 | 1.322 | 1.423 | 1.495 | 1.485 | 1.658 | 2.039 | 1.890 |
| Keep Moving For 10 s + 5 fps (2794 s motion) | 0.705 | 0.745 | 0.846 | 0.918 | 0.908 | 1.081 | 1.462 | 1.313 |
| Pause After Typing 2 s (4819 s motion) | 1.196 | 1.264 | 1.365 | 1.437 | 1.427 | 1.601 | 1.981 | 1.832 |
| Pause After Typing 3 s (4594 s motion) | 1.141 | 1.207 | 1.308 | 1.379 | 1.369 | 1.543 | 1.924 | 1.774 |

**Writer**: % of battery per day (central), by setting

| Setting | Aurora (layers: Frame Rate 10+ only) | Aurora (default: window path) | River rocks | Stream | Forest | Snow | Meadow | Beach |
|---|---|---|---|---|---|---|---|---|
| as built: Keep Moving For 20 s, own fps (1750 s motion) | 0.440 | 0.465 | 0.524 | 0.565 | 0.559 | 0.660 | 0.880 | 0.793 |
| Keep Moving For 15 s (1692 s motion) | 0.427 | 0.451 | 0.509 | 0.550 | 0.545 | 0.645 | 0.865 | 0.779 |
| Keep Moving For 10 s (1522 s motion) | 0.385 | 0.407 | 0.465 | 0.507 | 0.501 | 0.601 | 0.821 | 0.735 |
| Frame rate 5 fps for every scene (1750 s motion) | 0.440 | 0.465 | 0.524 | 0.565 | 0.559 | 0.660 | 0.880 | 0.793 |
| Keep Moving For 10 s + 5 fps (1522 s motion) | 0.385 | 0.407 | 0.465 | 0.507 | 0.501 | 0.601 | 0.821 | 0.735 |
| Pause After Typing 2 s (1358 s motion) | 0.346 | 0.365 | 0.423 | 0.465 | 0.459 | 0.559 | 0.779 | 0.693 |
| Pause After Typing 3 s (883 s motion) | 0.231 | 0.243 | 0.301 | 0.343 | 0.337 | 0.437 | 0.658 | 0.571 |

**Keyboard up while reading**: % of battery per day (central), by setting

| Setting | Aurora (layers: Frame Rate 10+ only) | Aurora (default: window path) | River rocks | Stream | Forest | Snow | Meadow | Beach |
|---|---|---|---|---|---|---|---|---|
| as built: Keep Moving For 20 s, own fps (1018 s motion) | 0.261 | 0.276 | 0.328 | 0.365 | 0.359 | 0.448 | 0.644 | 0.567 |
| Keep Moving For 15 s (854 s motion) | 0.222 | 0.234 | 0.285 | 0.322 | 0.317 | 0.406 | 0.602 | 0.525 |
| Keep Moving For 10 s (614 s motion) | 0.163 | 0.172 | 0.224 | 0.261 | 0.256 | 0.345 | 0.540 | 0.463 |
| Frame rate 5 fps for every scene (1018 s motion) | 0.261 | 0.276 | 0.328 | 0.365 | 0.359 | 0.448 | 0.644 | 0.567 |
| Keep Moving For 10 s + 5 fps (614 s motion) | 0.163 | 0.172 | 0.224 | 0.261 | 0.256 | 0.345 | 0.540 | 0.463 |
| Pause After Typing 2 s (976 s motion) | 0.251 | 0.265 | 0.317 | 0.354 | 0.349 | 0.438 | 0.633 | 0.556 |
| Pause After Typing 3 s (934 s motion) | 0.241 | 0.254 | 0.306 | 0.343 | 0.338 | 0.427 | 0.622 | 0.546 |

## Display path

**Heavy chatter**: % of battery per day (central) by display path — refresh free (Pixel default) vs refresh pinned at 60 Hz / an ARR panel (estimate)

| Theme | refresh free | pinned / ARR |
|---|---|---|
| Aurora (layers: Frame Rate 10+ only) | 1.250 | 0.471 |
| Aurora (default: window path) | 1.322 | 0.471 |
| River rocks | 1.423 | 0.571 |
| Stream | 1.495 | 0.819 |
| Forest | 1.485 | 0.809 |
| Snow | 1.658 | 1.072 |
| Meadow | 2.039 | 1.364 |
| Beach | 1.890 | 1.303 |

## Sanity checks

- Spec §4.2 example, Meadow at 7 fps, ten 20-s spells (200 s): 0.051 % (spec says ~0.06 %).
- Spec §0 'ten 20-s spells an hour': Aurora layers 0.048 % (spec ~0.05), Beach 0.051 % (spec ~0.07).
- Round 10: one spell at 10 fps ≈ 215 frames (measured 2,198 / 10 = 220).
