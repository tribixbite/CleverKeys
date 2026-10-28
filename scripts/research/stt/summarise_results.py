#!/usr/bin/env python3
"""Print Markdown tables from the result JSONs of the 2026-10-28 STT spike (`$STT/results/*.json`).

  python3 summarise_results.py $STT/results

Batch rows: engine, clips, audio s, min RTF (min over repeats, median over clips), max RTF,
first-token/TTFT, WER (basic normaliser), RSS/peak, load average at the end of the run.
Stream rows: passes, mean/max pass ms, first commit (audio s + pass ms), retractions, WER stream vs batch.
"""
from __future__ import annotations

import glob
import json
import os
import statistics
import sys


def med(xs):
    return statistics.median(xs) if xs else float("nan")


def batch_row(name: str, d: dict) -> str:
    clips = d["clips"]
    rtf = [c.get("rtf_min") or c.get("rtf_net") for c in clips]
    secs = [c["seconds"] for c in clips]
    total = [c.get("min_total_ms") or c.get("net_ms") or c.get("total_ms") for c in clips]
    ttft = [c["ttft_ms"] for c in clips if c.get("ttft_ms") is not None]
    enc = [c["encode_ms"] for c in clips if c.get("encode_ms") is not None] or [c["encoder_ms"] for c in clips if c.get("encoder_ms") is not None]
    dec = [c["decode_ms"] for c in clips if c.get("decode_ms") is not None] or [c["decoder_ms"] for c in clips if c.get("decoder_ms") is not None] or [c["decoder_joiner_ms"] for c in clips if c.get("decoder_joiner_ms") is not None]
    steps = [c.get("steps") for c in clips if c.get("steps")]
    rss = d.get("rss_mb") or d.get("child_peak_rss_mb")
    hwm = d.get("hwm_mb") or d.get("child_peak_rss_mb")
    load = (d.get("env") or {}).get("loadavg")
    return (f"| {name} | {len(clips)} | {med(secs):.1f} | {med(total):.0f} | {med(enc) if enc else float('nan'):.0f} | "
            f"{med(dec) if dec else float('nan'):.0f} | {med(steps) if steps else float('nan'):.0f} | "
            f"{med(rtf):.3f} | {max(rtf):.3f} | {med(ttft) if ttft else float('nan'):.0f} | "
            f"{d.get('errors')}/{d.get('ref_words')} = {100*d.get('wer', 0):.2f} % | {rss} / {hwm} | {load} |")


def stream_row(name: str, d: dict) -> str:
    clips = d["clips"]
    passes = [p for c in clips for p in c["stream"]["passes"]]
    pass_ms = [p["pass_ms"] for p in passes]
    firsts = [c["stream"]["first_commit_audio_s"] for c in clips if c["stream"].get("first_commit_audio_s") is not None]
    first_lat = [c["stream"]["first_commit_latency_ms"] for c in clips if c["stream"].get("first_commit_latency_ms") is not None]
    retr = sum(c["stream"]["retractions"] for c in clips)
    words = sum(c["ref_words"] for c in clips)
    es = sum(c["stream_errors"] for c in clips)
    eb = sum(c["batch_errors"] for c in clips)
    over = sum(1 for p in pass_ms if p > d.get("chunk_s", 1.0) * 1000)
    load = (d.get("env") or {}).get("loadavg")
    return (f"| {name} | {len(clips)} | {d.get('chunk_s')} | {len(passes)} | {med(pass_ms):.0f} | {max(pass_ms):.0f} | {over} | "
            f"{med(firsts) if firsts else float('nan'):.1f} s + {med(first_lat) if first_lat else float('nan'):.0f} ms | "
            f"{retr} ({retr/words*100:.0f} % of words) | {es}/{words} = {100*es/words:.2f} % | {eb}/{words} = {100*eb/words:.2f} % | {load} |")


def needle_stream_row(name: str, d: dict) -> str:
    clips = d["clips"]
    events = [e for c in clips for e in c.get("events", [])]
    pass_ms = [e["pass_ms"] for e in events if e.get("pass_ms")]
    firsts = []
    for c in clips:
        ev = [e for e in c.get("events", []) if e.get("text")]
        if ev:
            firsts.append((ev[0]["received"], ev[0]["pass_ms"]))
    over = sum(1 for p in pass_ms if p > d.get("chunk_s", 1.0) * 1000)
    load = (d.get("env") or {}).get("loadavg")
    return (f"| {name} | {len(clips)} | {d.get('chunk_s')} | {len(pass_ms)} | {med(pass_ms):.0f} | {max(pass_ms) if pass_ms else 0:.0f} | {over} | "
            f"{med([f[0] for f in firsts]):.1f} s + {med([f[1] for f in firsts]):.0f} ms | n/a (engine commits) | "
            f"{d['errors']}/{d['ref_words']} = {100*d['wer']:.2f} % | — | {load} |")


def main() -> None:
    rdir = sys.argv[1]
    files = sorted(glob.glob(os.path.join(rdir, "*.json")))
    print("### Batch (whole clip in one pass)\n")
    print("| run | clips | median s | median total ms | median enc ms | median dec ms | median steps | median RTF | max RTF | median TTFT ms | WER | RSS / peak MB | load avg |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for f in files:
        d = json.load(open(f))
        name = os.path.basename(f)[:-5]
        if d.get("mode") == "pseudo-stream" or "stream" in name:
            continue
        print(batch_row(name, d))
    print("\n### Streaming / pseudo-streaming\n")
    print("| run | clips | chunk s | passes | median pass ms | max pass ms | passes > chunk | first commit (audio + compute) | retractions | WER stream | WER batch | load avg |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for f in files:
        d = json.load(open(f))
        name = os.path.basename(f)[:-5]
        if d.get("mode") == "pseudo-stream" or (d.get("engine", "").startswith("moonshine") and "stream" in name):
            print(stream_row(name, d))
        elif d.get("engine", "").startswith("needle") and d.get("stream"):
            print(needle_stream_row(name, d))
    zips = [f for f in files if "zipformer" in f]
    if zips:
        print("\n### Zipformer first-token audio time (true streaming, no retractions)\n")
        for f in zips:
            d = json.load(open(f))
            ft = [c["first_token_audio_s"] for c in d["clips"] if c.get("first_token_audio_s") is not None]
            print(f"- {os.path.basename(f)[:-5]}: first token at median {med(ft):.2f} s of audio, median ms/step {med([c['ms_per_step'] for c in d['clips']]):.1f} (one step = 320 ms of audio)")


if __name__ == "__main__":
    main()
