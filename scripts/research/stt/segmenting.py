"""App-like forced segmentation for long-clip streaming runs (2026-11-05).

Mirrors the bound rule of the keyboard's `SpeechSegmenter` (src/main/kotlin/…/stt/
SpeechSegmenter.kt): a segment that reaches the engine's `maxSegmentSeconds` without a pause is cut
after the quietest 32 ms hop of its last `softWindowSeconds` (energy averaged over three hops).
Pause-based endpointing (0.6 s hangover) is NOT modelled: the long clips join utterances with
0.3 s gaps, below the hangover, so in the app only the bound would cut them.
"""
from __future__ import annotations

import numpy as np

RATE = 16000
HOP = 512  # 32 ms, the app's and the official Moonshine runtime's analysis hop


def forced_segments(pcm: np.ndarray, max_seconds: float, soft_seconds: float = 2.0) -> list[tuple[int, int]]:
    """[(start, end)) sample ranges covering `pcm`, each at most `max_seconds` long."""
    max_n = int(max_seconds * RATE)
    soft_n = int(min(soft_seconds, max_seconds / 2) * RATE)
    cuts: list[tuple[int, int]] = []
    start = 0
    while len(pcm) - start > max_n:
        lo, hi = start + max_n - soft_n, start + max_n
        n_hops = (hi - lo) // HOP
        hops = pcm[lo : lo + n_hops * HOP].astype(np.float64).reshape(n_hops, HOP)
        energy = (hops ** 2).mean(axis=1)
        smooth = np.convolve(energy, np.ones(3) / 3, mode="same")
        end = lo + (int(smooth.argmin()) + 1) * HOP  # cut AFTER the quietest hop, as the app does
        cuts.append((start, end))
        start = end
    cuts.append((start, len(pcm)))
    return cuts


def merge_streams(parts: list[dict], offsets_s: list[float]) -> dict:
    """Join per-segment stream results (same keys as the harnesses' `stream` dicts) into one."""
    passes = []
    for st, off in zip(parts, offsets_s):
        for p in st["passes"]:
            passes.append({**p, "received_s": round(p["received_s"] + off, 2), "segment_offset_s": round(off, 2)})
    first = next((st for st in parts if st.get("first_commit_audio_s") is not None), None)
    ms = [p["pass_ms"] for p in passes]
    return {"text": " ".join(st["text"] for st in parts if st["text"]), "segments": len(parts),
            "segment_starts_s": [round(o, 2) for o in offsets_s], "passes": passes, "n_passes": len(passes),
            "sum_pass_ms": round(sum(ms), 1), "max_pass_ms": round(max(ms), 1),
            "median_pass_ms": float(sorted(ms)[len(ms) // 2]),
            "first_commit_audio_s": first["first_commit_audio_s"] if first else None,
            "first_commit_latency_ms": first["first_commit_latency_ms"] if first else None,
            "retractions": sum(st["retractions"] for st in parts),
            "committed_words": sum(st["committed_words"] for st in parts),
            "tail_words": sum(st["tail_words"] for st in parts)}
