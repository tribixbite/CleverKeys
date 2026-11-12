#!/usr/bin/env python3
"""
Battery "user stories" for CleverKeys' animated scene backdrops (Pixel 8 Pro).

Turns a daily keyboard-usage profile into extra energy per day, per theme, and puts it on a
typical day's discharge curve. Every physical constant below is either MEASURED on the Pixel
8 Pro (docs/specs/scene-backdrops-and-power.md §3.1, rounds 6-16, 2026-11-09..12) or the
spec's MODEL fitted to those measurements (§4.1); each is tagged. Usage numbers are
ASSUMPTIONS, stated per story and justified in report.md.

Motion gating as built (verified in code 2026-11-12):
  theme/AuroraMotion.kt      IDLE_MS 20_000 (Keep Moving For), EASE_MS 2_500, TYPING_HOLD_MS
                             1_000 (Pause After Typing), HOLD_EASE_MS 250, AURORA_FRAME_MS 200
  theme/AuroraMotion.kt      SceneMotionTuning defaults: fps 0 (= scene's own), overlay true,
                             trueBlack false, idleSeconds 20, resumeMs 1000
  theme/SceneRaster.kt       SceneKind.frameMs: MEADOW/STREAM/FOREST/SNOW 143, RIVER_ROCKS 200,
                             BEACH 125
  Config.kt Defaults         LOOK_BACKDROP_MOTION = true (Animated scenes on by default)
  theme/BackdropDrawable.kt  wake() on the keyboard showing (setVisible / onAttachedToWindow /
                             onWindowVisibilityChanged VISIBLE); touch() on ACTION_DOWN /
                             POINTER_DOWN / MOVE on the keyboard view (keys and swipes);
                             stopMotion() when hidden; MotionAllowance: battery saver, animator
                             scale 0, thermal >= SEVERE, battery <= 15 % not charging.
  AuroraMotionClock.touch()  sets BOTH lastWake and lastTouch: a key press restarts the 20 s AND
                             holds for 1 s; frames run while awake OR speed > 0 (the 2.5 s
                             ease-down after the 20 s still draws frames).

So one "spell" after the last key press draws frames from +1.0 s (hold end) to +22.5 s
(idle 20 s + ease-down 2.5 s): 21.5 s. Round 10 measured 2,198 frames per ten one-minute
tap-and-wait cycles at 10 fps = 22.0 s per spell, which agrees. A spell after the keyboard
SHOWS (no key press) runs 22.5 s unless a key press cuts it short.

Run:  python3 battery_stories.py   (writes tables.md beside itself and prints it)
"""
from __future__ import annotations

from dataclasses import dataclass, replace
from pathlib import Path
from typing import Iterable

# ----------------------------------------------------------------------------------------
# Battery and display constants (Pixel 8 Pro)
# ----------------------------------------------------------------------------------------
BATTERY_WH = 19.5                       # spec §4.1 (5,050 mAh); 195 mW ~ 1 %/h
J_PER_PERCENT = BATTERY_WH * 3600 / 100  # 702 J per 1 % of battery

# Still-picture light cost (spec §4.3): ~153 mW per unit of mean linear light at brightness 102
# (from the MEASURED +55 mW of Meadow's screen vs Aurora's at 102), scaled by the display's
# emission baseline at brightness 12 (272 vs 1,080 mW, MEASURED round 12) and at 255 (1,552 vs
# 1,020 mW on VSYS_PWR_DISPLAY with motion off, MEASURED round 18). MODEL; at 255 it predicts
# Meadow's still picture at +84 mW over Aurora's and round 18 measured +83.5 (one run).
LIGHT_MW_PER_UNIT = {102: 153.0, 12: 153.0 * 272 / 1080, 255: 153.0 * 1552 / 1020}
BRIGHTNESSES = (12, 102, 255)

# ----------------------------------------------------------------------------------------
# Motion gating defaults (code, see docstring)
# ----------------------------------------------------------------------------------------
IDLE_S_DEFAULT = 20.0      # Keep Moving For
EASE_S = 2.5               # ease-down after the idle timeout still draws frames
HOLD_S_DEFAULT = 1.0       # Pause After Typing
HOLD_EASE_S = 0.25         # frames drawn while slowing to a stop at a key press

# ----------------------------------------------------------------------------------------
# Power model while moving (spec §4.1), mW
# ----------------------------------------------------------------------------------------
PANEL_IDLE_TIMER_MS = 80.0  # ro.surface_flinger.set_idle_timer_ms (MEASURED/read on the Pixel)


# Window path from 5 to 9 fps: MEASURED flat. Round 17 (2 runs per arm, brightness 12): +183 at
# 5 fps, +178 at 7, +187 at 8; landscape 5 fps +169/+174. Round 18 (2 runs per arm, mirrored):
# 5 fps +153 at brightness 12, +199 at 102, +180 at 255; 9 fps +187 at 12. The motion's display
# share (three display rails) is +77 / +84 / +89 mW at 12 / 102 / 255: it is the panel kept
# refreshing, nearly independent of brightness. Round 13's single pair (+115 at 5 fps) is the
# outlier and no longer sets the low end. The §4.1 curve (121 / 198 / 241) was wrong between its
# 5- and 10-fps anchors.
WINDOW_FLAT_MW = (150.0, 180.0, 200.0)   # low, central, high, for frame intervals >= 111 ms
WINDOW_FLAT_MIN_FRAME_MS = 111.0          # 9 fps, the fastest rate measured on the flat


def window_path_refresh_free_mw(frame_ms: float) -> float:
    """Spec §4.1 two-point fit of round 13 (5 fps -> 81 mW display, 10 fps -> 257) plus
    ~8 mJ of compute per frame. MODEL, used only above 9 fps (10 fps measured +340-374;
    12 and 15 fps unmeasured); 5-9 fps use WINDOW_FLAT_MW."""
    fps = 1000.0 / frame_ms
    idle_share = max(0.0, frame_ms - PANEL_IDLE_TIMER_MS) / frame_ms
    return 8.0 * fps + 373.0 * (1.0 - idle_share) ** 1.67


def pinned_or_arr_mw(frame_ms: float) -> float:
    """Spec §4.1: 12.3 mJ per frame with the refresh pinned (MEASURED round 12, three scenes
    within +-5 %); the ARR-panel estimate reuses it (MODEL, no ARR phone measured)."""
    return 12.3 * 1000.0 / frame_ms


# Aurora on display-hardware layers: MEASURED +150 (5 fps, r13), +152/+197/+188 (10 fps,
# r13/r14/r16); "flat with frame rate" between 5 and 10. Above 10 fps unmeasured.
AURORA_LAYERS_MW = (150.0, 170.0, 190.0)   # low, central, high

# Window-path model band: measured anchors sit -5 % (115 vs 121 at 5 fps) to +11 % (374 vs 337
# at 10 fps) from the model, so carry +-10 % as the band.
WINDOW_BAND = 0.10


@dataclass(frozen=True)
class Theme:
    name: str
    frame_ms: int          # default SceneKind.frameMs / AURORA_FRAME_MS (code, 2026-11-12)
    light: float           # mean linear light as painted (spec §4.3, SceneTrueBlackTest method)
    light_true_black: float
    hardware_layers: bool = False   # aurora only, Android 13+, Blend on Display Hardware on

    @property
    def fps(self) -> float:
        return 1000.0 / self.frame_ms

    def moving_mw(self, frame_ms: float | None = None, path: str = "free") -> tuple[float, float, float]:
        """(low, central, high) extra power while moving, mW.

        path: "free"   refresh rate left free (Pixel default, what a user has)
              "pinned" refresh pinned at 60 Hz, or an ARR panel (estimate)
        """
        fm = frame_ms or self.frame_ms
        if path == "pinned":
            p = pinned_or_arr_mw(fm)
            return (p * 0.95, p, p * 1.05)
        if self.hardware_layers:
            if fm >= 100:          # measured flat from 5 to 10 fps
                return AURORA_LAYERS_MW
            # Above 10 fps: unmeasured. Assume flat plus the extra compute of the extra
            # frames (round 13: ~2.2 mJ/frame on layers = (26-15)/5 mW per fps).
            extra = 2.2 * (1000.0 / fm - 10.0)
            return tuple(v + extra for v in AURORA_LAYERS_MW)  # type: ignore[return-value]
        if fm >= WINDOW_FLAT_MIN_FRAME_MS - 0.5:
            return WINDOW_FLAT_MW
        p = window_path_refresh_free_mw(fm)
        return (p * (1 - WINDOW_BAND), p, p * (1 + WINDOW_BAND))

    def still_light_mw(self, brightness: int, true_black: bool) -> float:
        """Extra display power of the still picture vs a BLACK keyboard, mW (MODEL, §4.3)."""
        light = self.light_true_black if true_black else self.light
        return LIGHT_MW_PER_UNIT[brightness] * light


THEMES: list[Theme] = [
    Theme("Aurora (layers: Frame Rate 10+ only)", 200, 0.0145, 0.0084, hardware_layers=True),
    Theme("Aurora (default: window path)", 200, 0.0145, 0.0084),
    Theme("River rocks", 200, 0.065, 0.012),
    Theme("Stream", 143, 0.101, 0.004),
    Theme("Forest", 143, 0.096, 0.010),
    Theme("Snow", 125, 0.183, 0.005),
    Theme("Meadow", 143, 0.374, 0.078),
    Theme("Beach", 125, 0.299, 0.003),
]


# ----------------------------------------------------------------------------------------
# Usage profile ("story")
# ----------------------------------------------------------------------------------------
@dataclass(frozen=True)
class Story:
    name: str
    # Short interactions (messages): each "open" shows the keyboard; each "burst" is one typed
    # message (keys or swipes), followed by the keyboard staying up (reading) before the next
    # burst or the keyboard hiding.
    opens: int
    bursts_per_open: float
    typing_s: float                 # seconds of typing per burst: no frames
    first_key_after_show_s: float   # the show spell is cut off by the first key press
    up_after_burst_s: float         # keyboard visible after a burst's last key
    # Long-form composing (writer): typing in runs with thinking pauses; a pause longer than the
    # 1 s hold resumes the motion until the next key.
    compose_sessions: int = 0
    compose_min_per_session: float = 0.0
    compose_run_s: float = 8.0          # typing run between pauses
    compose_pause_s: float = 3.0        # usual pause
    compose_long_pause_s: float = 15.0  # re-reading pause
    compose_long_pause_share: float = 0.1
    # Keyboard left up while reading, touched only now and then
    read_episodes: int = 0
    read_episode_s: float = 0.0
    read_key_presses_per_episode: int = 0   # isolated presses, spaced further than a spell
    # Settings
    idle_s: float = IDLE_S_DEFAULT
    hold_s: float = HOLD_S_DEFAULT
    fps_override: int = 0            # 0 = each scene's own
    brightness: int = 102            # 102 = assumed typical indoor; 12 = the measurement level
    true_black: bool = False

    # --- spells -------------------------------------------------------------------------
    def spell_after_key_s(self, up_for_s: float) -> float:
        """Frames after the last key of a burst: from the hold end to idle+ease, cut when hidden."""
        return max(0.0, min(self.idle_s + EASE_S, up_for_s) - self.hold_s)

    def spell_after_show_s(self, until_first_key_s: float) -> float:
        return max(0.0, min(self.idle_s + EASE_S, until_first_key_s))

    def pause_frames_s(self, pause_s: float) -> float:
        """Frames a composing pause costs: motion resumes at the hold end and runs until the
        next key (or the idle timeout); a pause shorter than the hold costs nothing, a longer one
        also costs the slowing frames at the next key press."""
        frames = max(0.0, min(pause_s - self.hold_s, self.idle_s + EASE_S))
        return frames + HOLD_EASE_S if frames > 0 else 0.0

    def compose_pauses(self) -> float:
        compose_s = self.compose_sessions * self.compose_min_per_session * 60.0
        mean_pause = ((1 - self.compose_long_pause_share) * self.compose_pause_s
                      + self.compose_long_pause_share * self.compose_long_pause_s)
        return compose_s / (self.compose_run_s + mean_pause) if compose_s else 0.0

    # --- daily totals ---------------------------------------------------------------------
    def motion_seconds(self) -> dict[str, float]:
        burst_spell = self.spell_after_key_s(self.up_after_burst_s) + HOLD_EASE_S
        messages = self.opens * (self.spell_after_show_s(self.first_key_after_show_s)
                                 + self.bursts_per_open * burst_spell)
        # composing: thinking pauses longer than the hold resume the motion
        per_pause = ((1 - self.compose_long_pause_share) * self.pause_frames_s(self.compose_pause_s)
                     + self.compose_long_pause_share * self.pause_frames_s(self.compose_long_pause_s))
        compose = self.compose_pauses() * per_pause + self.compose_sessions * (
            self.spell_after_show_s(self.first_key_after_show_s) + burst_spell)
        # reading with the keyboard up
        reading = self.read_episodes * (
            self.spell_after_show_s(self.read_episode_s)
            + self.read_key_presses_per_episode * (self.spell_after_key_s(self.idle_s + EASE_S) + HOLD_EASE_S))
        return {"messages": messages, "composing": compose, "reading": reading,
                "total": messages + compose + reading}

    def spells(self) -> float:
        return (self.opens * (1 + self.bursts_per_open)
                + self.compose_sessions * 2
                + self.read_episodes * (1 + self.read_key_presses_per_episode))

    def visible_seconds(self) -> float:
        per_open = self.first_key_after_show_s + self.bursts_per_open * (self.typing_s + self.up_after_burst_s)
        return (self.opens * per_open
                + self.compose_sessions * (self.compose_min_per_session * 60.0 + self.up_after_burst_s)
                + self.read_episodes * self.read_episode_s)

    def typing_seconds(self) -> float:
        return self.opens * self.bursts_per_open * self.typing_s + self.compose_sessions * self.compose_min_per_session * 60.0


@dataclass(frozen=True)
class Cost:
    motion_j: tuple[float, float, float]
    light_j: float

    @property
    def total_j(self) -> tuple[float, float, float]:
        return tuple(m + self.light_j for m in self.motion_j)  # type: ignore[return-value]

    def pct(self) -> tuple[float, float, float]:
        return tuple(j / J_PER_PERCENT for j in self.total_j)  # type: ignore[return-value]

    def wh(self) -> tuple[float, float, float]:
        return tuple(j / 3600 for j in self.total_j)  # type: ignore[return-value]


def cost(story: Story, theme: Theme, path: str = "free") -> Cost:
    frame_ms = 1000.0 / story.fps_override if story.fps_override else theme.frame_ms
    lo, mid, hi = theme.moving_mw(frame_ms, path)
    s = story.motion_seconds()["total"]
    motion = (lo * s / 1000, mid * s / 1000, hi * s / 1000)
    light = theme.still_light_mw(story.brightness, story.true_black) * story.visible_seconds() / 1000
    return Cost(motion, light)


# ----------------------------------------------------------------------------------------
# A typical day (ASSUMPTION, see report.md §3)
# ----------------------------------------------------------------------------------------
DAY_AWAKE_H = 16.0        # 07:00 -> 23:00
SCREEN_ON_H = 5.0         # Reviews.org 2026: 4 h 05 m smartphone; Harmony: 5 h 16 m (US)
SCREEN_ON_W = 2.0         # ~ 19.5 Wh / 10 h (Tom's Guide's Pixel 8 Pro web test, 150 nits)
STANDBY_W = 0.30          # ~1.5 %/h screen-off with radios on (assumption)
DAY_WH = SCREEN_ON_H * SCREEN_ON_W + (DAY_AWAKE_H - SCREEN_ON_H) * STANDBY_W
DAY_PCT = DAY_WH / BATTERY_WH * 100
BEDTIME_PCT = 100 - DAY_PCT
AVG_PCT_PER_H = DAY_PCT / DAY_AWAKE_H


def minutes_earlier(delta_pct: float) -> float:
    """How much earlier the battery reaches the bedtime level, at the day's average rate."""
    return delta_pct / AVG_PCT_PER_H * 60


def screen_minutes_lost(delta_wh: float) -> float:
    return delta_wh / SCREEN_ON_W * 60


# ----------------------------------------------------------------------------------------
# Stories
# ----------------------------------------------------------------------------------------
LIGHT = Story("Light user", opens=30, bursts_per_open=1.0, typing_s=15, first_key_after_show_s=1.5,
              up_after_burst_s=20)
HEAVY = Story("Heavy chatter", opens=150, bursts_per_open=1.5, typing_s=10, first_key_after_show_s=1.0,
              up_after_burst_s=30)
WRITER = Story("Writer", opens=20, bursts_per_open=1.0, typing_s=15, first_key_after_show_s=1.5,
               up_after_burst_s=20, compose_sessions=3, compose_min_per_session=25)
READER = Story("Keyboard up while reading", opens=30, bursts_per_open=1.0, typing_s=15,
               first_key_after_show_s=1.5, up_after_burst_s=20,
               read_episodes=6, read_episode_s=600, read_key_presses_per_episode=2)
TUNER = replace(HEAVY, name="Heavy chatter, Keep Moving For 60 s + 12 fps", idle_s=60, fps_override=12,
                up_after_burst_s=45)
STORIES: list[Story] = [LIGHT, HEAVY, WRITER, READER, TUNER]

# A real phone's app time (MEASURED: `dumpsys usagestats`, in-memory monthly stats, 15.05 days to
# 2026-11-12, the maintainer's own phone, developer tools such as Termux left out; categories only,
# no app names). Keyboard use per category is the maintainer's rule (ASSUMPTION):
#   chat apps           85.8 min/day visible, 48 launches/day: the keyboard is up the whole time
#   browsing, maps,     218.8 min/day: the keyboard opens once per 10 minutes of use (22 opens),
#   media, shopping     plus the search app's 14.9 launches/day, one search each -> 36 opens/day
#   no keyboard         69.6 min/day (launchers, camera, clock, car, home cameras): nothing
# Chat: per launch, ~15 s of reading before the first key (the show spell runs until then), 1.5
# messages of ~10 s, then the keyboard stays up while reading (51 s, so the visible total matches
# 85.8 min); every message ends in a full spell. Searches: the keyboard hides ~2 s after the last key.
DEVICE_DAYS = 15.05
OWNER_CHAT = Story("Owner's phone: chat apps, keyboard always up", opens=48, bursts_per_open=1.5,
                   typing_s=10, first_key_after_show_s=15, up_after_burst_s=51)
OWNER_SEARCH = Story("Owner's phone: browsing, maps, media (one search per 10 min)", opens=36,
                     bursts_per_open=1.0, typing_s=8, first_key_after_show_s=2, up_after_burst_s=2)
OWNER_PARTS: list[Story] = [OWNER_CHAT, OWNER_SEARCH]
# Message-rate band for the chat part: 0.5 / 1.5 / 3 messages per launch, the low and high ends
# also reading through the whole show spell before the first key.
OWNER_CHAT_BAND = (replace(OWNER_CHAT, bursts_per_open=0.5, first_key_after_show_s=22.5, up_after_burst_s=158),
                   OWNER_CHAT,
                   replace(OWNER_CHAT, bursts_per_open=3.0, first_key_after_show_s=22.5, up_after_burst_s=18))


# ----------------------------------------------------------------------------------------
# Tables
# ----------------------------------------------------------------------------------------
def f(x: float, d: int = 2) -> str:
    return f"{x:.{d}f}"


def rng(t: tuple[float, float, float], d: int = 2) -> str:
    lo, mid, hi = t
    return f"{mid:.{d}f} ({lo:.{d}f}–{hi:.{d}f})"


def usage_table(stories: Iterable[Story]) -> list[str]:
    out = ["| Story | Keyboard opens | Typed bursts | Composing pauses > hold | Keyboard visible | Typing (no frames) | Motion spells | Motion per day | of visible |",
           "|---|---|---|---|---|---|---|---|---|"]
    for s in stories:
        ms = s.motion_seconds()
        vis = s.visible_seconds()
        out.append(f"| {s.name} | {s.opens} | {s.opens * s.bursts_per_open + s.compose_sessions:.0f} | "
                   f"{s.compose_pauses():.0f} | "
                   f"{vis / 60:.0f} min | {s.typing_seconds() / 60:.0f} min | {s.spells():.0f} | "
                   f"{ms['total'] / 60:.1f} min ({ms['total']:.0f} s) | {100 * ms['total'] / vis:.0f} % |")
    return out


def theme_table(story: Story, path: str = "free") -> list[str]:
    out = [f"**{story.name}** — motion {story.motion_seconds()['total']:.0f} s/day, keyboard visible "
           f"{story.visible_seconds() / 60:.0f} min/day, brightness {story.brightness}, True Black "
           f"{'on' if story.true_black else 'off'}, Keep Moving For {story.idle_s:.0f} s, "
           f"frame rate {'scene\'s own' if not story.fps_override else str(story.fps_override) + ' fps'}",
           "",
           "| Theme | fps | Moving, mW | Still light, mW | Motion, Wh | Light, Wh | Extra per day, Wh | % of battery | Screen-on min lost | Min earlier at bedtime |",
           "|---|---|---|---|---|---|---|---|---|---|"]
    for t in THEMES:
        fm = 1000.0 / story.fps_override if story.fps_override else t.frame_ms
        c = cost(story, t, path)
        mw = t.moving_mw(fm, path)
        light_mw = t.still_light_mw(story.brightness, story.true_black)
        wh = c.wh()
        pct = c.pct()
        out.append(f"| {t.name} | {1000 / fm:.0f} | {rng(mw, 0)} | {light_mw:.0f} | "
                   f"{rng(tuple(m / 3600 for m in c.motion_j), 3)} | {c.light_j / 3600:.3f} | "
                   f"{rng(wh, 3)} | {rng(pct, 2)} | {screen_minutes_lost(wh[1]):.1f} ({screen_minutes_lost(wh[0]):.1f}–{screen_minutes_lost(wh[2]):.1f}) | "
                   f"{minutes_earlier(pct[1]):.1f} |")
    return out


def true_black_table(story: Story) -> list[str]:
    out = [f"**{story.name}**, still-light cost of the picture while the keyboard is visible "
           f"({story.visible_seconds() / 60:.0f} min/day), vs a black keyboard, Wh/day (% of battery)",
           "",
           "| Theme | " + " | ".join(f"brightness {b} | brightness {b}, True Black" for b in BRIGHTNESSES) + " |",
           "|---|" + "---|---|" * len(BRIGHTNESSES)]
    vis = story.visible_seconds()
    for t in THEMES:
        cells = []
        for b in BRIGHTNESSES:
            for tb in (False, True):
                wh = t.still_light_mw(b, tb) * vis / 1000 / 3600
                cells.append(f"{wh:.3f} ({wh * 3600 / J_PER_PERCENT:.2f} %)")
        out.append(f"| {t.name} | " + " | ".join(cells) + " |")
    return out


def curve_table(story: Story, themes: Iterable[Theme]) -> list[str]:
    """Battery level through the day, baseline vs with the theme (central estimate), assuming
    the theme's extra is spread over awake hours like screen-on time."""
    hours = [0, 4, 8, 12, 16]
    out = [f"**{story.name}**: battery % at hours after waking (07:00), baseline vs theme (central)",
           "",
           "| | " + " | ".join(f"+{h} h ({7 + h:02d}:00)" for h in hours) + " |",
           "|---|" + "---|" * len(hours)]
    out.append("| Baseline | " + " | ".join(f"{100 - AVG_PCT_PER_H * h:.1f}" for h in hours) + " |")
    for t in themes:
        d = cost(story, t).pct()[1]
        out.append(f"| {t.name} | " + " | ".join(f"{100 - AVG_PCT_PER_H * h - d * h / DAY_AWAKE_H:.1f}" for h in hours) + " |")
    return out


def defaults_table(story: Story) -> list[str]:
    """Battery per day saved by candidate default changes (central estimates)."""
    variants = [
        ("as built: Keep Moving For 20 s, own fps", {}),
        ("Keep Moving For 15 s", {"idle_s": 15.0}),
        ("Keep Moving For 10 s", {"idle_s": 10.0}),
        ("Frame rate 5 fps for every scene", {"fps_override": 5}),
        ("Keep Moving For 10 s + 5 fps", {"idle_s": 10.0, "fps_override": 5}),
        ("Pause After Typing 2 s", {"hold_s": 2.0}),
        ("Pause After Typing 3 s", {"hold_s": 3.0}),
    ]
    out = [f"**{story.name}**: % of battery per day (central), by setting",
           "",
           "| Setting | " + " | ".join(t.name for t in THEMES) + " |",
           "|---|" + "---|" * len(THEMES)]
    for label, kw in variants:
        s = replace(story, **kw)
        out.append(f"| {label} ({s.motion_seconds()['total']:.0f} s motion) | "
                   + " | ".join(f"{cost(s, t).pct()[1]:.3f}" for t in THEMES) + " |")
    return out


def path_table(story: Story) -> list[str]:
    out = [f"**{story.name}**: % of battery per day (central) by display path — refresh free (Pixel "
           f"default) vs refresh pinned at 60 Hz / an ARR panel (estimate)",
           "",
           "| Theme | refresh free | pinned / ARR |",
           "|---|---|---|"]
    for t in THEMES:
        out.append(f"| {t.name} | {cost(story, t, 'free').pct()[1]:.3f} | {cost(story, t, 'pinned').pct()[1]:.3f} |")
    return out


def summary_matrix(stories: Iterable[Story]) -> list[str]:
    """Headline: % of battery per day, central (low–high), per story and theme."""
    out = ["| Story | " + " | ".join(t.name for t in THEMES) + " |", "|---|" + "---|" * len(THEMES)]
    for s in stories:
        cells = []
        for t in THEMES:
            lo, mid, hi = cost(s, t).pct()
            cells.append(f"{mid:.2f} ({lo:.2f}–{hi:.2f})")
        out.append(f"| {s.name} | " + " | ".join(cells) + " |")
    out += ["", "Same, as minutes of screen-on time per day (central, at 2.0 W) / minutes earlier at the bedtime level:", "",
            "| Story | " + " | ".join(t.name for t in THEMES) + " |", "|---|" + "---|" * len(THEMES)]
    for s in stories:
        cells = []
        for t in THEMES:
            c = cost(s, t)
            cells.append(f"{screen_minutes_lost(c.wh()[1]):.1f} / {minutes_earlier(c.pct()[1]):.0f}")
        out.append(f"| {s.name} | " + " | ".join(cells) + " |")
    return out


def owner_cost(theme: Theme, brightness: int, chat: Story = OWNER_CHAT, true_black: bool = False) -> Cost:
    """The owner's phone's day: the chat part plus the search part, summed."""
    parts = [replace(chat, brightness=brightness, true_black=true_black),
             replace(OWNER_SEARCH, brightness=brightness, true_black=true_black)]
    costs = [cost(s, theme) for s in parts]
    motion = tuple(sum(c.motion_j[i] for c in costs) for i in range(3))
    return Cost(motion, sum(c.light_j for c in costs))  # type: ignore[arg-type]


def owner_tables() -> list[str]:
    """The example user deduced from a real phone's app time (OWNER_* above)."""
    out = ["## Example user from a real phone's app time", "",
           f"From {DEVICE_DAYS} days of `dumpsys usagestats` on the maintainer's phone (developer tools left out): "
           "chat apps 85.8 min/day with the keyboard up the whole time (48 launches), browsing, maps, media "
           "and shopping 218.8 min/day with one search per 10 minutes plus the search app's 14.9 searches "
           "(36 keyboard opens), no keyboard 69.6 min/day.", ""]
    out += usage_table(list(OWNER_CHAT_BAND) + [OWNER_SEARCH])
    out += ["", "% of battery per day, central motion (motion band from the measurement spread); the chat-rate "
            "column uses 0.5 / 3 messages per launch for its low / high", "",
            "| Theme | " + " | ".join(f"brightness {b}" for b in BRIGHTNESSES)
            + " | brightness 255, True Black | chat rate low–high at 102 |",
            "|---|" + "---|" * (len(BRIGHTNESSES) + 2)]
    for t in THEMES:
        cells = []
        for b in BRIGHTNESSES:
            lo, mid, hi = owner_cost(t, b).pct()
            cells.append(f"{mid:.2f} ({lo:.2f}–{hi:.2f})")
        tb = owner_cost(t, 255, true_black=True).pct()[1]
        lo_rate = owner_cost(t, 102, OWNER_CHAT_BAND[0]).pct()[1]
        hi_rate = owner_cost(t, 102, OWNER_CHAT_BAND[2]).pct()[1]
        out.append(f"| {t.name} | " + " | ".join(cells) + f" | {tb:.2f} | {lo_rate:.2f}–{hi_rate:.2f} |")
    out += ["", "Same day split into motion and still light at brightness 255 (Wh/day):", "",
            "| Theme | motion | still light | motion share |", "|---|---|---|---|"]
    for t in THEMES:
        c = owner_cost(t, 255)
        m = c.motion_j[1] / 3600
        light = c.light_j / 3600
        out.append(f"| {t.name} | {m:.3f} | {light:.3f} | {100 * m / (m + light):.0f} % |")
    return out + [""]


def main() -> None:
    lines: list[str] = []
    lines += ["# Battery stories — computed tables", "",
              f"Battery {BATTERY_WH} Wh; 1 % = {J_PER_PERCENT:.0f} J. Typical day: {DAY_AWAKE_H:.0f} h awake, "
              f"{SCREEN_ON_H:.0f} h screen-on at {SCREEN_ON_W:.1f} W, {DAY_AWAKE_H - SCREEN_ON_H:.0f} h standby at "
              f"{STANDBY_W * 1000:.0f} mW → {DAY_WH:.1f} Wh = {DAY_PCT:.1f} % by bedtime (100 → {BEDTIME_PCT:.1f} %), "
              f"average {AVG_PCT_PER_H:.2f} %/h awake.", ""]
    lines += ["## Spell lengths (as built)", "",
              f"After a key press: frames from +{HOLD_S_DEFAULT:.0f} s to +{IDLE_S_DEFAULT + EASE_S:.1f} s = "
              f"{LIGHT.spell_after_key_s(1e9):.1f} s (+{HOLD_EASE_S} s of slowing frames at the press). "
              f"After the keyboard shows with no key: {LIGHT.spell_after_show_s(1e9):.1f} s.", ""]
    lines += ["## Moving cost per theme at its default rate (mW; spec §4.1 model, measured anchors in the report)", "",
              "| Theme | frame ms | fps | refresh free (lo–hi) | pinned / ARR |", "|---|---|---|---|---|"]
    for t in THEMES:
        lines.append(f"| {t.name} | {t.frame_ms} | {t.fps:.2f} | {rng(t.moving_mw(), 0)} | {t.moving_mw(path='pinned')[1]:.0f} |")
    lines += ["", "## Usage profiles", ""] + usage_table(STORIES)
    lines += ["", "## Headline: % of battery per day, central (low–high), refresh free, brightness 102, True Black off", ""]
    lines += summary_matrix(STORIES)
    lines += ["", "## Per story, per theme (refresh free, brightness 102, True Black off)", ""]
    for s in STORIES:
        lines += theme_table(s) + [""]
    lines += ["## Same stories at brightness 12 (the measurement level) — only the still-light column changes", ""]
    for s in STORIES:
        lines += theme_table(replace(s, brightness=12)) + [""]
    lines += ["## Same stories at brightness 255 (maximum) — only the still-light column changes", ""]
    for s in STORIES:
        lines += theme_table(replace(s, brightness=255)) + [""]
    lines += owner_tables()
    lines += ["## True Black: still-light cost by brightness", ""]
    for s in (HEAVY, READER):
        lines += true_black_table(s) + [""]
    lines += ["## Discharge curve shift", ""]
    for s in (HEAVY, READER):
        lines += curve_table(s, [THEMES[0], THEMES[6], THEMES[7]]) + [""]
    lines += ["## Candidate default changes", ""]
    for s in (LIGHT, HEAVY, WRITER, READER):
        lines += defaults_table(s) + [""]
    lines += ["## Display path", ""]
    for s in (HEAVY,):
        lines += path_table(s) + [""]
    # sanity vs the spec's own example: Meadow 7 fps, ten 20-s spells ≈ 0.06 %
    meadow = THEMES[6]
    spec_example_pct = meadow.moving_mw()[1] * 200 / 1000 / J_PER_PERCENT
    lines += ["## Sanity checks", "",
              f"- Spec §4.2 example, Meadow at 7 fps, ten 20-s spells (200 s): {spec_example_pct:.3f} % (spec says ~0.06 %).",
              f"- Spec §0 'ten 20-s spells an hour': Aurora layers {THEMES[0].moving_mw()[1] * 200 / 1000 / J_PER_PERCENT:.3f} % (spec ~0.05), "
              f"Beach {THEMES[7].moving_mw()[1] * 200 / 1000 / J_PER_PERCENT:.3f} % (spec ~0.07).",
              f"- Round 10: one spell at 10 fps ≈ {LIGHT.spell_after_key_s(1e9) * 10:.0f} frames (measured 2,198 / 10 = 220).", ""]
    text = "\n".join(lines)
    Path(__file__).with_name("tables.md").write_text(text, encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
