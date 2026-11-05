#!/usr/bin/env python3
"""whisper.cpp (ggml) baseline for the 2026-10-28 STT engine deep dive (added 2026-11-05).

Runs a source-built `whisper-cli` once per clip (batch) or once per buffered prefix
(pseudo-stream), on the same LibriSpeech clips, WER normaliser and two-pass commit rule as
`whistle_ort.py` / `moonshine_stream_ort.py`, so its rows sit in the same tables.

Timing comes from whisper.cpp's own `whisper_print_timings` (stderr): "compute" = total − load,
i.e. mel + encode + decode + sampling, without process start or model load. Every pass is a new
process, so per-pass model load is excluded rather than amortised — the same thing a resident
JNI context would see. Peak RSS is the child's `ru_maxrss` from `os.wait4`.

    whisper_cpp_bench.py --cli <whisper-cli> --model <ggml.bin> [--threads 4] [--beam 1]
                         [--audio-ctx {0,N,auto[+PAD][:FLOOR]}] [--tokens-per-s X] [--extra "-et 2.8"]
                         bench  <manifest.json> --ids <a,b,…> [--repeat 2] [--out x.json]
    whisper_cpp_bench.py … stream <manifest.json> --ids <…> [--chunk 1.0] [--verbose] [--out x.json]

`--audio-ctx auto` passes `-ac ceil(seconds·50)+16` (encoder frames are 20 ms; 1500 = the full
30 s window); `auto+PAD:FLOOR` uses PAD frames of margin and never fewer than FLOOR frames.
Whisper was trained only on 30 s windows, so this trades accuracy for encoder time; it is
measured here, not assumed.

`--tokens-per-s X` caps each decode at `ceil(seconds·X)+8` tokens through `-mt`, a three-line
research patch to whisper-cli that forwards `whisper_full_params.max_tokens` (the library field
exists; stock whisper-cli does not expose it). It bounds the repetition loops a short audio
context provokes. `--extra` passes further whisper-cli flags verbatim (e.g. `-et 2.8`).
"""
from __future__ import annotations

import argparse
import json
import math
import os
import re
import shlex
import subprocess
import sys
import tempfile
import time
import wave
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import numpy as np  # noqa: E402

from asr_metrics import NORMALISER_NAME, EditCounts, normalise, wer_of  # noqa: E402
from segmenting import forced_segments, merge_streams  # noqa: E402

RATE = 16000
FULL_CTX = 1500  # encoder frames in Whisper's fixed 30 s window
_TIMING = re.compile(r"whisper_print_timings:\s+(\w+) time =\s+([\d.]+) ms(?: /\s+(\d+) runs)?")
_FALLBACKS = re.compile(r"fallbacks =\s+(\d+) p /\s+(\d+) h")


@dataclass
class Pass:
    """One whisper-cli invocation: transcript, whisper.cpp stage timings (ms) and child peak RSS."""

    text: str
    timings: dict[str, float]
    decode_runs: int
    fallbacks: int
    peak_rss_mb: float
    audio_ctx: int

    @property
    def compute_ms(self) -> float:
        return self.timings.get("total", 0.0) - self.timings.get("load", 0.0)


class WhisperCli:
    def __init__(self, cli: str, model: str, threads: int, beam: int, audio_ctx: str, language: str,
                 tokens_per_s: float = 0.0, extra: str = "") -> None:
        self.cli, self.model, self.threads, self.beam = cli, model, threads, beam
        self.audio_ctx, self.language = audio_ctx, language
        self.tokens_per_s, self.extra = tokens_per_s, shlex.split(extra)
        self.model_bytes = os.path.getsize(model)
        m = re.fullmatch(r"auto(?:\+(\d+))?(?::(\d+))?", audio_ctx)
        self._auto = (int(m.group(1) or 16), int(m.group(2) or 0)) if m else None

    def _ctx_for(self, seconds: float) -> int:
        if self._auto is not None:
            pad, floor = self._auto
            return min(FULL_CTX, max(floor, math.ceil(seconds * 50) + pad))
        return int(self.audio_ctx)

    def _cmd(self, wav_path: str, seconds: float, ctx: int) -> list[str]:
        cmd = [self.cli, "-m", self.model, "-f", wav_path, "-t", str(self.threads), "-nt", "-l", self.language,
               "-bs", str(self.beam), "-bo", str(max(1, self.beam))]
        if ctx:
            cmd += ["-ac", str(ctx)]
        if self.tokens_per_s > 0:
            cmd += ["-mt", str(math.ceil(seconds * self.tokens_per_s) + 8)]
        return cmd + self.extra

    def run(self, wav_path: str, seconds: float) -> Pass:
        """One whisper-cli process; peak RSS comes from wait4's rusage for exactly this child."""
        ctx = self._ctx_for(seconds)
        cmd = self._cmd(wav_path, seconds, ctx)
        with tempfile.TemporaryFile("w+", errors="replace") as fo, tempfile.TemporaryFile("w+", errors="replace") as fe:
            # errors="replace": a short audio context can make whisper.cpp emit a truncated UTF-8 token
            proc = subprocess.Popen(cmd, stdout=fo, stderr=fe)
            _, status, usage = os.wait4(proc.pid, 0)
            proc.returncode = os.waitstatus_to_exitcode(status)
            fo.seek(0), fe.seek(0)
            out, err = fo.read(), fe.read()
        if proc.returncode != 0:
            raise RuntimeError(f"whisper-cli rc={proc.returncode}: {err[-400:]}")
        timings, decode_runs = {}, 0
        for name, ms, runs in _TIMING.findall(err):
            timings[name] = float(ms)
            if name == "decode" and runs:
                decode_runs = int(runs)
        fb = _FALLBACKS.search(err)
        # Linux reports ru_maxrss in KiB
        return Pass(" ".join(out.split()), timings, decode_runs, int(fb.group(1)) if fb else 0,
                    round(usage.ru_maxrss / 1024, 1), ctx)


def read_pcm16(path: str) -> bytes:
    with wave.open(path) as w:
        assert w.getframerate() == RATE and w.getnchannels() == 1 and w.getsampwidth() == 2, path
        return w.readframes(w.getnframes())


def write_pcm16(path: str, frames: bytes) -> None:
    with wave.open(path, "wb") as w:
        w.setnchannels(1), w.setsampwidth(2), w.setframerate(RATE)
        w.writeframes(frames)


def load_manifest(path: str, ids: str | None) -> list[dict]:
    man = json.load(open(path))
    if ids:
        want = set(ids.split(","))
        man = [m for m in man if m["id"] in want]
    return man


def loadavg() -> str | None:
    # /proc/loadavg is SELinux-denied to app uids on Android; toybox `uptime` reads sysinfo().
    try:
        up = subprocess.run(["uptime"], capture_output=True, text=True, timeout=5).stdout
        return up.split("load average:")[-1].strip()
    except Exception:
        return None


def median(xs: list[float]) -> float:
    s = sorted(xs)
    return s[len(s) // 2] if s else 0.0


def cmd_bench(w: WhisperCli, args: argparse.Namespace) -> dict:
    rows, total = [], EditCounts(0, 0, 0, 0)
    for m in load_manifest(args.manifest, args.ids):
        runs = [w.run(m["wav"], m["seconds"]) for _ in range(args.repeat)]
        best = min(runs, key=lambda p: p.compute_ms)
        ec = wer_of(m["text"], best.text)
        total = total + ec
        rows.append({"id": m["id"], "seconds": m["seconds"], "ref": m["text"], "hyp": best.text,
                     "compute_ms": round(best.compute_ms, 1), "timings": best.timings, "decode_runs": best.decode_runs,
                     "fallbacks": best.fallbacks, "audio_ctx": best.audio_ctx, "peak_rss_mb": best.peak_rss_mb,
                     "rtf": round(best.compute_ms / 1000 / m["seconds"], 4), "errors": ec.errors,
                     "ref_words": ec.reference_words})
        t = best.timings
        print(f"{m['id']} {m['seconds']:5.2f}s compute {best.compute_ms:7.1f} ms (enc {t.get('encode', 0):6.1f}, "
              f"dec {t.get('decode', 0) + t.get('batchd', 0):6.1f}) RTF {best.compute_ms / 1000 / m['seconds']:.3f} "
              f"RSS {best.peak_rss_mb} WER {ec.errors}/{ec.reference_words} | {best.text}", flush=True)
    return {"mode": "batch", "repeat": args.repeat, "wer": round(total.wer, 4), "errors": total.errors,
            "ref_words": total.reference_words,
            "median_compute_ms": median([r["compute_ms"] for r in rows]),
            "median_encode_ms": median([r["timings"].get("encode", 0.0) for r in rows]),
            "median_rtf": median([r["rtf"] for r in rows]), "max_rtf": max(r["rtf"] for r in rows),
            "peak_rss_mb": max(r["peak_rss_mb"] for r in rows), "clips": rows}


def pseudo_stream(w: WhisperCli, frames: bytes, chunk_s: float, tmpdir: str) -> dict:
    """Re-transcribe the buffered audio after every chunk; commit the words two consecutive passes
    agree on (normalised comparison, newer surface form). Same rule as `WhistleOrt.stream` so the
    retraction and first-commit columns are comparable.
    # TODO: hoist this rule into asr_metrics.py and have whistle_ort/moonshine call it too.
    """
    step = int(chunk_s * RATE) * 2  # bytes of PCM16
    committed: list[str] = []
    prev_words: list[str] = []
    prev_norm: list[str] = []
    retractions = 0
    first_commit_audio_s = first_commit_ms = None
    passes = []
    wav = os.path.join(tmpdir, "buf.wav")
    received = 0
    while received < len(frames):
        received = min(len(frames), received + step)
        write_pcm16(wav, frames[:received])
        secs = received / 2 / RATE
        p = w.run(wav, secs)
        words = p.text.split()
        norm = [" ".join(normalise(x)) for x in words]
        agree = 0
        while agree < min(len(norm), len(prev_norm)) and norm[agree] == prev_norm[agree]:
            agree += 1
        newly: list[str] = []
        if agree > len(committed):
            newly = words[len(committed):agree]
            committed += newly
            if first_commit_audio_s is None:
                first_commit_audio_s, first_commit_ms = round(secs, 2), round(p.compute_ms, 1)
        pend_prev = prev_norm[len(committed) - len(newly):]
        pend_now = norm[len(committed) - len(newly):]
        retractions += sum(1 for a, b in zip(pend_prev, pend_now) if a != b) + max(0, len(pend_prev) - len(pend_now))
        passes.append({"received_s": round(secs, 2), "pass_ms": round(p.compute_ms, 1),
                       "encode_ms": p.timings.get("encode", 0.0), "audio_ctx": p.audio_ctx,
                       "peak_rss_mb": p.peak_rss_mb, "committed": " ".join(newly),
                       "pending": " ".join(words[len(committed):])})
        prev_words, prev_norm = words, norm
    tail = prev_words[len(committed):]
    ms = [x["pass_ms"] for x in passes]
    return {"text": " ".join(committed + tail), "n_passes": len(passes), "sum_pass_ms": round(sum(ms), 1),
            "median_pass_ms": median(ms), "max_pass_ms": max(ms),
            "passes_over_chunk": sum(1 for x in ms if x > chunk_s * 1000),
            "first_commit_audio_s": first_commit_audio_s, "first_commit_latency_ms": first_commit_ms,
            "retractions": retractions, "committed_words": len(committed), "tail_words": len(tail), "passes": passes}


def cmd_stream(w: WhisperCli, args: argparse.Namespace) -> dict:
    rows = []
    tot_s = tot_b = EditCounts(0, 0, 0, 0)
    with tempfile.TemporaryDirectory(dir=args.tmp) as td:
        for m in load_manifest(args.manifest, args.ids):
            batch = w.run(m["wav"], m["seconds"])
            frames = read_pcm16(m["wav"])
            if args.max_segment > 0:
                segs = forced_segments(np.frombuffer(frames, np.int16), args.max_segment)
                st = merge_streams([pseudo_stream(w, frames[2 * a : 2 * b], args.chunk, td) for a, b in segs],
                                   [a / RATE for a, _ in segs])
                st["passes_over_chunk"] = sum(1 for p in st["passes"] if p["pass_ms"] > args.chunk * 1000)
            else:
                st = pseudo_stream(w, frames, args.chunk, td)
            ec_s, ec_b = wer_of(m["text"], st["text"]), wer_of(m["text"], batch.text)
            tot_s, tot_b = tot_s + ec_s, tot_b + ec_b
            rows.append({"id": m["id"], "seconds": m["seconds"], "ref": m["text"], "batch": batch.text,
                         "stream": st, "stream_errors": ec_s.errors, "batch_errors": ec_b.errors,
                         "ref_words": ec_s.reference_words})
            print(f"{m['id']} {m['seconds']:5.2f}s passes {st['n_passes']} median {st['median_pass_ms']:.0f} "
                  f"max {st['max_pass_ms']:.0f} ms over-chunk {st['passes_over_chunk']} first commit "
                  f"@{st['first_commit_audio_s']}s (+{st['first_commit_latency_ms']} ms) retractions {st['retractions']} "
                  f"WER stream {ec_s.errors} batch {ec_b.errors} /{ec_s.reference_words}", flush=True)
            if args.verbose:
                for p in st["passes"]:
                    print(f"   @{p['received_s']:5.1f}s {p['pass_ms']:6.0f} ms (enc {p['encode_ms']:6.0f}, ctx "
                          f"{p['audio_ctx']})  +[{p['committed']}]  ...{p['pending']}")
    all_ms = [p["pass_ms"] for r in rows for p in r["stream"]["passes"]]
    return {"mode": "pseudo-stream", "chunk_s": args.chunk, "max_segment_s": args.max_segment, "wer_stream": round(tot_s.wer, 4),
            "wer_batch": round(tot_b.wer, 4), "errors_stream": tot_s.errors, "errors_batch": tot_b.errors,
            "ref_words": tot_s.reference_words, "passes": len(all_ms), "median_pass_ms": median(all_ms),
            "max_pass_ms": max(all_ms), "passes_over_chunk": sum(1 for x in all_ms if x > args.chunk * 1000),
            "retractions": sum(r["stream"]["retractions"] for r in rows), "clips": rows}


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--cli", required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--beam", type=int, default=1, help="1 = greedy (default here; whisper-cli's own default is 5)")
    ap.add_argument("--audio-ctx", default="0", help="0 = full 30 s window, N = fixed, auto = scale with the clip")
    ap.add_argument("--language", default="en")
    ap.add_argument("--tokens-per-s", type=float, default=0.0, help="decode cap per audio second (needs the -mt patch)")
    ap.add_argument("--extra", default="", help="extra whisper-cli flags, verbatim")
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("bench", "stream"):
        sp = sub.add_parser(name)
        sp.add_argument("manifest")
        sp.add_argument("--ids")
        sp.add_argument("--out")
        if name == "bench":
            sp.add_argument("--repeat", type=int, default=2)
        else:
            sp.add_argument("--chunk", type=float, default=1.0)
            sp.add_argument("--verbose", action="store_true")
            sp.add_argument("--max-segment", type=float, default=0.0,
                            help="cut like the app's SpeechSegmenter at this bound (default engine bound: 30 s)")
            sp.add_argument("--tmp", default=os.environ.get("TMPDIR"))
    args = ap.parse_args()
    w = WhisperCli(args.cli, args.model, args.threads, args.beam, args.audio_ctx, args.language,
                   args.tokens_per_s, args.extra)
    t0 = time.time()
    res = cmd_bench(w, args) if args.cmd == "bench" else cmd_stream(w, args)
    out = {"engine": "whisper.cpp", "model": os.path.basename(args.model), "model_bytes": w.model_bytes,
           "threads": args.threads, "beam": args.beam, "audio_ctx": args.audio_ctx, "tokens_per_s": args.tokens_per_s,
           "extra": args.extra, "normaliser": NORMALISER_NAME,
           "loadavg_end": loadavg(), "wall_s": round(time.time() - t0, 1), **res}
    if args.out:
        json.dump(out, open(args.out, "w"), indent=1, ensure_ascii=False)
    summary = {k: v for k, v in out.items() if k != "clips"}
    print(json.dumps(summary))


if __name__ == "__main__":
    main()
