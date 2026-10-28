#!/usr/bin/env python3
"""Benchmark Cactus's own prebuilt Android engine (`android-arm64/needle`, Bionic, API 24) on
Whistle — research spike, 2026-10-28.

The CLI is the official engine: 5-beam decoding, Cactus Quants kernels, `--audio-stream`
(1 s chunks by default, words committed when two consecutive passes agree). Each invocation
loads the 16.9 MB `.cact`, so the per-clip wall time includes the load; `--calibrate` measures
that with a 0.2 s silent clip and reports it separately. Peak RSS is the child's ru_maxrss.

  python3 needle_cli_bench.py --needle $STT/models/needle3/android-arm64/needle --cact $STT/models/whistle/whistle.cact \
      $STT/audio/manifest.json --ids ... --repeat 3 --out results.json [--stream --chunk 1]
"""
from __future__ import annotations

import argparse
import json
import os
import resource
import subprocess
import sys
import tempfile
import time
import wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from asr_metrics import NORMALISER_NAME, EditCounts, wer_of  # noqa: E402
from whistle_ort import env_info, load_manifest  # noqa: E402


def run_once(needle: str, cact: str, wav: str, *, stream: bool, chunk: float, language: str | None,
             threads: int | None, depth: int | None) -> tuple[float, float, str, int]:
    """Returns (wall_s, child_peak_rss_mb_delta, stdout, returncode)."""
    cmd = [needle, "--model", cact, "--audio", wav]
    if stream:
        cmd += ["--audio-stream", "--audio-chunk", str(chunk)]
    if language:
        cmd += ["--audio-language", language]
    if threads:
        cmd += ["--threads", str(threads)]
    if depth:
        cmd += ["--audio-depth", str(depth)]
    before = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss
    t0 = time.perf_counter()
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
    wall = time.perf_counter() - t0
    after = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss
    return wall, after / 1024, p.stdout + p.stderr, p.returncode


def parse_batch(out: str) -> dict:
    line = next((ln for ln in out.splitlines() if ln.startswith("{")), "{}")
    return json.loads(line)


def parse_stream(out: str) -> dict:
    """--audio-stream prints one JSON object per committed piece/pass; keep them all."""
    events = []
    for ln in out.splitlines():
        ln = ln.strip()
        if ln.startswith("{"):
            try:
                events.append(json.loads(ln))
            except json.JSONDecodeError:
                pass
    text = " ".join(e.get("text", "") for e in events if e.get("text"))
    return {"events": events, "text": text.strip(), "raw": out if not events else None}


def calibrate(needle: str, cact: str, repeat: int) -> float:
    """Wall time of load + a 0.2 s silent clip (returns empty text): the fixed per-process cost."""
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
        path = f.name
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes(b"\0\0" * 3200)
    try:
        return min(run_once(needle, cact, path, stream=False, chunk=1, language=None, threads=None, depth=None)[0]
                   for _ in range(repeat))
    finally:
        os.unlink(path)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--needle", required=True)
    ap.add_argument("--cact", required=True)
    ap.add_argument("manifest")
    ap.add_argument("--ids")
    ap.add_argument("--min-s", type=float)
    ap.add_argument("--max-s", type=float)
    ap.add_argument("--repeat", type=int, default=3)
    ap.add_argument("--language")
    ap.add_argument("--threads", type=int)
    ap.add_argument("--depth", type=int, help="--audio-depth ladder rung (2..8)")
    ap.add_argument("--stream", action="store_true")
    ap.add_argument("--chunk", type=float, default=1.0)
    ap.add_argument("--out")
    args = ap.parse_args()

    load_floor = calibrate(args.needle, args.cact, 3)
    print(f"# load+silent-clip floor: {load_floor*1000:.0f} ms", file=sys.stderr)
    clips = load_manifest(args.manifest, args.ids, args.min_s, args.max_s)
    rows, total = [], EditCounts(0, 0, 0, 0)
    peak = 0.0
    for m in clips:
        runs = [run_once(args.needle, args.cact, m["wav"], stream=args.stream, chunk=args.chunk, language=args.language,
                         threads=args.threads, depth=args.depth) for _ in range(args.repeat)]
        wall = min(r[0] for r in runs)
        peak = max(peak, max(r[1] for r in runs))
        out = runs[0][2]
        if args.stream:
            parsed = parse_stream(out)
            text = parsed["text"]
            info = {"events": parsed["events"][:64], "n_events": len(parsed["events"])}
        else:
            parsed = parse_batch(out)
            text = parsed.get("text", "")
            info = {"ttft_ms": parsed.get("ttft_ms"), "decode_tps": parsed.get("decode_tps"), "language": parsed.get("language")}
        ec = wer_of(m["text"], text)
        total = total + ec
        net = wall - load_floor
        rows.append({"id": m["id"], "seconds": m["seconds"], "ref": m["text"], "hyp": text, "wall_ms": round(wall * 1000, 1),
                     "net_ms": round(net * 1000, 1), "rtf_net": round(net / m["seconds"], 4), "rc": runs[0][3],
                     "errors": ec.errors, "ref_words": ec.reference_words, **info})
        print(f"{m['id']} {m['seconds']:5.2f}s wall {wall*1000:6.0f} ms net {net*1000:6.0f} ms RTF {net/m['seconds']:.3f} "
              f"WER {ec.errors}/{ec.reference_words} {info if not args.stream else ''} | {text}", flush=True)
    result = {"engine": "needle-cli-android-arm64", "stream": args.stream, "chunk_s": args.chunk, "depth": args.depth,
              "threads": args.threads, "repeat": args.repeat, "load_floor_ms": round(load_floor * 1000, 1),
              "child_peak_rss_mb": round(peak, 1), "cact_bytes": os.path.getsize(args.cact),
              "needle_bytes": os.path.getsize(args.needle), "env": env_info(), "normaliser": NORMALISER_NAME,
              "wer": round(total.wer, 4), "errors": total.errors, "ref_words": total.reference_words, "clips": rows}
    if args.out:
        json.dump(result, open(args.out, "w"), indent=1, ensure_ascii=False)
    print(f"WER {total.errors}/{total.reference_words} = {total.wer:.4f}  child peak RSS {peak:.0f} MB  load floor {load_floor*1000:.0f} ms")


if __name__ == "__main__":
    main()
