#!/usr/bin/env python3
"""Whistle (Cactus Compute, Apache-2.0 weights) on ONNX Runtime — research spike, 2026-10-28.

Runs mrfakename's Apache-2.0 ONNX export of Whistle (encoder.onnx + one-step decoder.onnx with
KV cache, opset 17) through onnxruntime with the host-side pipeline ported from the export's
JS reference (`js/whistle.js`): loudness gain, 80-bin log-mel, per-utterance CMVN, SentencePiece
decode, engram n-gram lookups and greedy decoding with language ID. The mel filterbank, the
tokenizer blob and the two 2-bit engram tables are read straight from `whistle.cact` with a
compact port of the export's `scripts/cact.py` reader, so the only model files needed are the
`.cact` and the ONNX graphs.

Three sub-commands:

  transcribe  one WAV, print JSON (text, language, timings, RSS)
  bench       batch transcription of manifest clips, N repeats, min/median timings, WER
  stream      pseudo-streaming: re-transcribe the buffered audio after every chunk and commit
              the words two consecutive passes agree on (the contract of Cactus's
              needle_stream_transcribe_process); reports per-pass cost, time to first
              committed word, retractions and final WER against the batch result

Inputs are 16 kHz mono PCM16 WAV files (LibriSpeech via scripts/research/stt/fetch_librispeech.py).
Nothing here is app code; see docs/research/2026-10-28-stt-engine-deep-dive.md for the results.

Example (paths as used in the deep-dive note; models live outside the repo):
  python3 whistle_ort.py --onnx-dir $STT/models/whistle-onnx/onnx --cact $STT/models/whistle/whistle.cact \
      bench $STT/audio/manifest.json --ids 6930-75918-0002,6930-75918-0001 --repeat 3 --threads 4
"""
from __future__ import annotations

import argparse
import json
import math
import os
import struct
import sys
import time
import wave
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from asr_metrics import NORMALISER_NAME, EditCounts, normalise, wer_of  # noqa: E402

# ----------------------------------------------------------------------------- constants
LANGS = ("en", "de", "fr", "es", "it", "nl", "pl")
LANG0, EOS, BOS, TEXT_VOCAB = 8192, 1, 2, 8192
SEED, PRIME, SLOTS = 0x9E3779B9, 0x01000193, 18432
N_FFT, HOP, WIN, N_MELS, RATE = 512, 160, 400, 80, 16000
HANN = (0.5 - 0.5 * np.cos(2 * np.pi * np.arange(WIN) / (WIN - 1))).astype(np.float64)  # symmetric
CACT_TAG = 0x05E12A84
CACT_HDR = "<48If"
CACT_REC = "<BBHIIIIQQII"
DT_FP16, DT_FP32, DT_CQ, DT_RAW = 1, 2, 3, 4


def rss_mb() -> tuple[float, float]:
    """(VmRSS, VmHWM) of this process in MiB, from /proc/self/status."""
    rss = hwm = 0.0
    with open("/proc/self/status") as f:
        for line in f:
            if line.startswith("VmRSS:"):
                rss = int(line.split()[1]) / 1024
            elif line.startswith("VmHWM:"):
                hwm = int(line.split()[1]) / 1024
    return rss, hwm


# ----------------------------------------------------------------------------- .cact reader
def _hadamard(n: int) -> np.ndarray:
    h = np.array([[1.0]], dtype=np.float32)
    while h.shape[0] < n:
        h = np.block([[h, h], [h, -h]])
    return (h / np.sqrt(n)).astype(np.float32)


class CqTable:
    """A 2/3/4-bit Cactus-Quants matrix kept packed; rows are dequantised on demand and cached.

    Dequantisation follows the export's cact.py / pack.js: LSB-packed codebook indices per
    group of `group` inputs, scaled by an fp16 per-group norm, rotated back by the normalised
    Walsh-Hadamard matrix. Whistle's engram tables are (4 tables x 18432 slots) x 128, 2-bit.
    """

    def __init__(self, blob: bytes, out: int, inp: int, group: int, bits: int, codebook: np.ndarray):
        self.out, self.inp, self.group, self.bits = out, inp, group, bits
        self.in_pad = (inp + group - 1) // group * group
        row_bytes = self.in_pad * bits // 8
        n_packed = out * row_bytes
        self.packed = np.frombuffer(blob[:n_packed], np.uint8).reshape(out, row_bytes)
        self.norms = np.frombuffer(blob[n_packed:], np.float16).reshape(out, self.in_pad // group).astype(np.float32)
        self.codebook = codebook.astype(np.float32)
        self.h = _hadamard(group)
        self.cache: dict[int, np.ndarray] = {}

    def _indices(self, row: np.ndarray) -> np.ndarray:
        bits = self.bits
        if bits == 2:
            return np.stack([row & 3, (row >> 2) & 3, (row >> 4) & 3, row >> 6], axis=1).reshape(-1)
        if bits == 4:
            return np.stack([row & 15, row >> 4], axis=1).reshape(-1)
        chunks = row.reshape(-1, bits).astype(np.uint64)
        word = np.zeros(chunks.shape[0], np.uint64)
        for b in range(bits):
            word |= chunks[:, b] << np.uint64(8 * b)
        mask = np.uint64((1 << bits) - 1)
        return np.stack([(word >> np.uint64(i * bits)) & mask for i in range(8)], axis=1).reshape(-1)

    def row(self, r: int) -> np.ndarray:
        cached = self.cache.get(r)
        if cached is not None:
            return cached
        unit = self.codebook[self._indices(self.packed[r])].reshape(-1, self.group) * self.norms[r][:, None]
        w = (unit @ self.h).reshape(-1)[: self.inp].astype(np.float32)
        self.cache[r] = w
        return w

    @property
    def packed_bytes(self) -> int:
        return self.packed.nbytes + self.norms.nbytes // 2  # norms are fp16 on disk


@dataclass
class Tokenizer:
    pieces: list[str]
    types: list[int]

    @classmethod
    def from_blob(cls, blob: bytes) -> "Tokenizer":
        hdr = "<IIIIIBBH"
        n = struct.unpack_from(hdr, blob, 0)[0]
        off = struct.calcsize(hdr)
        rec = struct.calcsize("<fBH")
        pieces, types = [], []
        for _ in range(n):
            _score, typ, ln = struct.unpack_from("<fBH", blob, off)
            off += rec
            pieces.append(blob[off : off + ln].decode("utf-8"))
            types.append(typ)
            off += ln
        return cls(pieces, types)

    def decode(self, ids: list[int]) -> str:
        out = bytearray()
        for i in ids:
            t = self.types[i]
            if t == 4:  # byte fallback piece "<0xNN>"
                out.append(int(self.pieces[i][3:5], 16))
            elif t in (0, 3):
                out += self.pieces[i].replace("▁", " ").encode("utf-8")
        return out.decode("utf-8", "replace")

    def is_word_start(self, i: int) -> bool:
        return self.pieces[i].startswith("▁")


@dataclass
class WhistleAssets:
    filterbank: np.ndarray  # [257, 80]
    tokenizer: Tokenizer
    engrams: list[CqTable]  # two sites, each (4*SLOTS) x 128
    cact_bytes: int

    @classmethod
    def from_cact(cls, path: str) -> "WhistleAssets":
        raw = open(path, "rb").read()
        hdr = struct.unpack_from(CACT_HDR, raw, 0)
        tag, n_rec, cb_n = hdr[:3]
        if tag != CACT_TAG:
            raise ValueError(f"{path}: not a Needle 3 .cact archive")
        off = struct.calcsize(CACT_HDR)
        all_cb = np.frombuffer(raw[off : off + cb_n * 4], np.float32)
        codebooks, o = {}, 0
        for b in (2, 3, 4):
            codebooks[b] = all_cb[o : o + (1 << b)]
            o += 1 << b
        off += cb_n * 4
        rec_size = struct.calcsize(CACT_REC)
        fb = tok = None
        engrams: list[CqTable] = []
        for _ in range(n_rec):
            r = struct.unpack(CACT_REC, raw[off : off + rec_size])
            off += rec_size
            dtype, ndim = r[0], r[1]
            shape = tuple(r[3 : 3 + ndim])
            blob = raw[r[7] : r[7] + r[8]]
            group, bits = r[9], r[10]
            if dtype == DT_FP32 and shape == (N_FFT // 2 + 1, N_MELS):
                fb = np.frombuffer(blob, np.float32).reshape(shape).copy()
            elif dtype == DT_RAW:
                tok = Tokenizer.from_blob(blob)
            elif dtype == DT_CQ and shape == (4 * SLOTS, 128):
                engrams.append(CqTable(blob, shape[0], shape[1], group, bits, codebooks[bits]))
        if fb is None or tok is None or len(engrams) != 2:
            raise ValueError(f"{path}: filterbank/tokenizer/engram records not found "
                             f"(fb={fb is not None}, tok={tok is not None}, engrams={len(engrams)})")
        return cls(fb, tok, engrams, len(raw))


# ----------------------------------------------------------------------------- front end
def log_mel(pcm: np.ndarray, fb: np.ndarray) -> np.ndarray:
    """JS `melFeatures` port: level gain, centred symmetric-Hann frames, power spectrum, filterbank,
    natural log with a 2^-24 floor, per-channel mean/variance normalisation over the clip."""
    pcm = pcm.astype(np.float64)
    n = pcm.shape[0]
    nch = n // 320
    gain = 1.0
    if nch > 0:
        blocks = pcm[: nch * 320].reshape(nch, 320)
        r = np.sqrt((blocks * blocks).mean(axis=1))
        r.sort()
        p = r[int(math.floor((nch - 1) * 0.99))]
        if p > 1e-6:
            gain = 0.1 / p
    t_frames = n // HOP
    if t_frames < 1:
        return np.zeros((0, N_MELS), np.float32)
    padded = np.zeros(n + WIN, np.float64)  # 200 zeros in front, >= 200 behind
    padded[200 : 200 + n] = pcm * gain
    idx = np.arange(t_frames)[:, None] * HOP + np.arange(WIN)[None, :]
    frames = padded[idx] * HANN
    spec = np.fft.rfft(frames, n=N_FFT, axis=1)
    power = spec.real ** 2 + spec.imag ** 2
    logmel = np.log(power @ fb.astype(np.float64) + 2.0 ** -24)
    mu = logmel.mean(axis=0, keepdims=True)
    sd = logmel.std(axis=0, ddof=1, keepdims=True) if t_frames > 1 else np.zeros((1, N_MELS))
    return ((logmel - mu) / (sd + 1e-5)).astype(np.float32)


# ----------------------------------------------------------------------------- engram
def _imul(a: int, b: int) -> int:
    return (a * b) & 0xFFFFFFFF


def engram_hash(toks: list[int], t: int) -> list[int]:
    """Four slot indices for position t: tables 0,1 = order-2 heads, tables 2,3 = order-3 heads."""
    out = []
    for oi in range(2):
        order = oi + 2
        for h in range(2):
            acc = _imul(SEED, oi * 2 + h + 1)
            for j in range(order):
                u = toks[t - j] if t - j >= 0 else 0
                acc = _imul(acc ^ u, PRIME)
            acc ^= acc >> 15
            out.append(acc % SLOTS)
    return out


def engram_features(engrams: list[CqTable], toks: list[int], t: int) -> np.ndarray:
    """efeat [2 sites, 4 taps, 4 tables * 128] for the token at position t (JS `Engram.feat`)."""
    f = np.zeros((2, 4, 512), np.float32)
    for j in range(4):
        p = t - 3 * j
        if p < 0:
            continue
        idx = engram_hash(toks, p)
        for i in range(4):
            if p < (1 if i < 2 else 2):
                continue  # n-gram not complete yet
            key = i * SLOTS + idx[i]
            for e in range(2):
                f[e, j, i * 128 : (i + 1) * 128] = engrams[e].row(key)
    return f


# ----------------------------------------------------------------------------- model
@dataclass
class Timing:
    mel_ms: float = 0.0
    encode_ms: float = 0.0
    decode_ms: float = 0.0
    ttft_ms: float = 0.0  # audio in -> first text token out (mel + encoder + 2 decoder steps)
    steps: int = 0
    total_ms: float = 0.0


@dataclass
class Result:
    text: str
    language: str | None
    ids: list[int]
    frames: int  # mel frames T
    memory: int  # encoder frames S
    timing: Timing = field(default_factory=Timing)


class WhistleOrt:
    def __init__(self, onnx_dir: str, assets: WhistleAssets, *, fp16: bool = False, threads: int = 4,
                 ep: str = "cpu", graph_opt: str = "all", arena: bool = True) -> None:
        import onnxruntime as ort

        self.ort = ort
        self.assets = assets
        suffix = "_fp16" if fp16 else ""
        so = ort.SessionOptions()
        so.intra_op_num_threads = threads
        so.inter_op_num_threads = 1
        so.enable_cpu_mem_arena = arena  # see --no-arena: the BFC arena keeps every high-water block
        so.graph_optimization_level = {
            "none": ort.GraphOptimizationLevel.ORT_DISABLE_ALL,
            "basic": ort.GraphOptimizationLevel.ORT_ENABLE_BASIC,
            "extended": ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED,
            "all": ort.GraphOptimizationLevel.ORT_ENABLE_ALL,
        }[graph_opt]
        providers = {"cpu": ["CPUExecutionProvider"],
                     "xnnpack": ["XnnpackExecutionProvider", "CPUExecutionProvider"],
                     "nnapi": ["NnapiExecutionProvider", "CPUExecutionProvider"]}[ep]
        t0 = time.perf_counter()
        self.enc = ort.InferenceSession(os.path.join(onnx_dir, f"encoder{suffix}.onnx"), so, providers=providers)
        t1 = time.perf_counter()
        self.dec = ort.InferenceSession(os.path.join(onnx_dir, f"decoder{suffix}.onnx"), so, providers=providers)
        t2 = time.perf_counter()
        self.load_ms = {"encoder": (t1 - t0) * 1000, "decoder": (t2 - t1) * 1000}
        self.providers = {"encoder": self.enc.get_providers(), "decoder": self.dec.get_providers()}
        # external-data exports carry a .onnx.data beside the graph; the int8 graphs are single files
        self.model_bytes = sum(os.path.getsize(p) for p in (os.path.join(onnx_dir, f"{g}{suffix}.onnx{x}")
                               for g in ("encoder", "decoder") for x in ("", ".data")) if os.path.exists(p))

    # -- one full pass -----------------------------------------------------------------
    def transcribe(self, pcm: np.ndarray, language: str | None = None, max_tokens: int | None = None,
                   forced_prefix: list[int] | None = None) -> Result:
        """Greedy transcription of one clip (<= 30 s). `forced_prefix` teacher-forces already
        committed text tokens (used by the streaming experiment) — same per-step cost, no argmax."""
        t0 = time.perf_counter()
        mel = log_mel(pcm, self.assets.filterbank)
        t_frames = mel.shape[0]
        t_mel = time.perf_counter()
        if t_frames < 4:
            return Result("", None, [], t_frames, 0, Timing(mel_ms=(t_mel - t0) * 1000, total_ms=(t_mel - t0) * 1000))
        ck, cv = self.enc.run(None, {"mel": mel})
        t_enc = time.perf_counter()
        s_frames = ck.shape[2]
        limit = max_tokens or min(320, 16 + math.ceil(t_frames / 100) * 12)
        prev = np.zeros((8, 2, 608), np.float32)
        pk = np.zeros((8, 2, 0, 48), np.float32)
        pv = np.zeros((8, 2, 0, 64), np.float32)
        toks = [BOS]
        ids: list[int] = []
        lang = language
        ttft = 0.0
        forced = list(forced_prefix or [])
        t = 0
        while True:
            feeds = {"token": np.array([toks[t]], np.int64), "pos": np.array([t], np.int64),
                     "efeat": engram_features(self.assets.engrams, toks, t), "prev": prev,
                     "past_k": pk, "past_v": pv, "cross_k": ck, "cross_v": cv}
            logits, prev, pk, pv, _xattn = self.dec.run(None, feeds)
            if t == 0:
                if lang is None:
                    lang = LANGS[int(np.argmax(logits[LANG0 : LANG0 + len(LANGS)]))]
                toks.append(LANG0 + LANGS.index(lang))
                t += 1
                continue
            if t == 1:
                ttft = (time.perf_counter() - t0) * 1000
            if forced and len(ids) < len(forced):
                k = forced[len(ids)]
            else:
                x = logits[:TEXT_VOCAB]
                k = int(np.argmax(x[1:])) + 1  # never pick PAD (0)
            if k == EOS or len(ids) >= limit:
                break
            ids.append(k)
            toks.append(k)
            t += 1
        t_dec = time.perf_counter()
        text = self.assets.tokenizer.decode(ids).strip()
        tm = Timing(mel_ms=(t_mel - t0) * 1000, encode_ms=(t_enc - t_mel) * 1000, decode_ms=(t_dec - t_enc) * 1000,
                    ttft_ms=ttft, steps=t + 1, total_ms=(t_dec - t0) * 1000)
        return Result(text, lang, ids, t_frames, s_frames, tm)

    # -- pseudo-streaming -------------------------------------------------------------
    def stream(self, pcm: np.ndarray, chunk_s: float = 1.0, language: str | None = None,
               window_s: float = 30.0, force_prefix: bool = False) -> dict:
        """Re-transcribe the buffered audio after every chunk; commit the words two consecutive
        passes agree on (normalised comparison, surface form of the newer pass committed).

        With `force_prefix` the tokens of already committed words are teacher-forced in the next
        pass, which pins the prefix (the engine cannot do that from outside, but a port could).
        """
        chunk = int(round(chunk_s * RATE))
        n = len(pcm)
        passes = []
        prev_words: list[str] = []
        committed: list[str] = []
        committed_norm: list[str] = []
        committed_ids: list[int] = []
        retractions = 0
        first_commit_audio_s = None
        first_commit_latency_ms = None
        lang = language
        received = 0
        while received < n:
            received = min(n, received + chunk)
            start = max(0, received - int(window_s * RATE))
            buf = pcm[start:received]
            t0 = time.perf_counter()
            res = self.transcribe(buf, lang, forced_prefix=committed_ids if force_prefix else None)
            pass_ms = (time.perf_counter() - t0) * 1000
            if lang is None:
                lang = res.language
            words = res.text.split()
            norm = [" ".join(normalise(w)) for w in words]
            # agreement with the previous pass, beyond what is already committed
            agree = 0
            prev_norm = [" ".join(normalise(w)) for w in prev_words]
            while agree < min(len(norm), len(prev_norm)) and norm[agree] == prev_norm[agree]:
                agree += 1
            newly = []
            if agree > len(committed):
                newly = words[len(committed) : agree]
                committed += newly
                committed_norm += norm[len(committed_norm) : agree]
                if force_prefix:
                    committed_ids = self._ids_for_words(res, len(committed))
                if first_commit_audio_s is None:
                    first_commit_audio_s = received / RATE
                    first_commit_latency_ms = pass_ms
            # retractions: pending words of the previous pass that changed in this one
            pend_prev = prev_norm[len(committed) - len(newly) :]
            pend_now = norm[len(committed) - len(newly) :]
            retractions += sum(1 for a, b in zip(pend_prev, pend_now) if a != b) + max(0, len(pend_prev) - len(pend_now))
            passes.append({"received_s": round(received / RATE, 2), "pass_ms": round(pass_ms, 1),
                           "encode_ms": round(res.timing.encode_ms, 1), "decode_ms": round(res.timing.decode_ms, 1),
                           "steps": res.timing.steps, "committed": " ".join(newly),
                           "pending": " ".join(words[len(committed):])})
            prev_words = words
        tail = prev_words[len(committed):]
        final_text = " ".join(committed + tail)
        return {"text": final_text, "language": lang, "chunk_s": chunk_s, "passes": passes,
                "n_passes": len(passes), "sum_pass_ms": round(sum(p["pass_ms"] for p in passes), 1),
                "max_pass_ms": round(max(p["pass_ms"] for p in passes), 1),
                "first_commit_audio_s": first_commit_audio_s, "first_commit_latency_ms": first_commit_latency_ms,
                "retractions": retractions, "committed_words": len(committed), "tail_words": len(tail)}

    def _ids_for_words(self, res: Result, n_words: int) -> list[int]:
        """Token ids that make up the first n_words words of `res` (word = piece starting with ▁)."""
        count = 0
        for i, tid in enumerate(res.ids):
            if self.assets.tokenizer.is_word_start(tid) or i == 0:
                count += 1
                if count > n_words:
                    return res.ids[:i]
        return list(res.ids)


# ----------------------------------------------------------------------------- IO helpers
def read_wav(path: str) -> np.ndarray:
    with wave.open(path) as w:
        assert w.getframerate() == RATE and w.getnchannels() == 1 and w.getsampwidth() == 2, path
        data = np.frombuffer(w.readframes(w.getnframes()), np.int16)
    return data.astype(np.float32) / 32768.0


def load_manifest(path: str, ids: str | None, min_s: float | None, max_s: float | None) -> list[dict]:
    man = json.load(open(path))
    if ids:
        want = set(ids.split(","))
        man = [m for m in man if m["id"] in want]
    if min_s is not None:
        man = [m for m in man if m["seconds"] >= min_s]
    if max_s is not None:
        man = [m for m in man if m["seconds"] <= max_s]
    return man


def env_info() -> dict:
    import onnxruntime as ort

    # Android Python has no os.getloadavg and /proc/loadavg is SELinux-denied to app uids; toybox
    # `uptime` reads it through sysinfo() and works. Record it with every result: this box runs
    # other sessions' Gradle builds, so the load average qualifies every timing below.
    load = None
    try:
        import subprocess

        up = subprocess.run(["uptime"], capture_output=True, text=True, timeout=5).stdout
        load = up.split("load average:")[-1].strip() if "load average:" in up else up.strip()
    except Exception:
        pass
    return {"onnxruntime": ort.__version__, "numpy": np.__version__, "python": sys.version.split()[0],
            "providers_available": ort.get_available_providers(), "loadavg": load,
            "normaliser": NORMALISER_NAME}


# ----------------------------------------------------------------------------- commands
def cmd_transcribe(model: WhistleOrt, args: argparse.Namespace) -> None:
    pcm = read_wav(args.wav)
    res = model.transcribe(pcm, args.language)
    rss, hwm = rss_mb()
    print(json.dumps({"text": res.text, "language": res.language, "seconds": round(len(pcm) / RATE, 2),
                      "frames": res.frames, "memory_frames": res.memory, "timing": res.timing.__dict__,
                      "rtf": round(res.timing.total_ms / 1000 / (len(pcm) / RATE), 4),
                      "rss_mb": round(rss, 1), "hwm_mb": round(hwm, 1)}, ensure_ascii=False, indent=1))


def cmd_bench(model: WhistleOrt, args: argparse.Namespace) -> None:
    clips = load_manifest(args.manifest, args.ids, args.min_s, args.max_s)
    rows, total = [], EditCounts(0, 0, 0, 0)
    for m in clips:
        pcm = read_wav(m["wav"])
        runs = [model.transcribe(pcm, args.language) for _ in range(args.repeat)]
        best = min(runs, key=lambda r: r.timing.total_ms)
        med = sorted(r.timing.total_ms for r in runs)[len(runs) // 2]
        ec = wer_of(m["text"], best.text)
        total = total + ec
        rows.append({"id": m["id"], "seconds": m["seconds"], "ref": m["text"], "hyp": best.text, "language": best.language,
                     "min_total_ms": round(best.timing.total_ms, 1), "median_total_ms": round(med, 1),
                     "mel_ms": round(best.timing.mel_ms, 1), "encode_ms": round(best.timing.encode_ms, 1),
                     "decode_ms": round(best.timing.decode_ms, 1), "ttft_ms": round(best.timing.ttft_ms, 1),
                     "steps": best.timing.steps, "ms_per_step": round(best.timing.decode_ms / max(1, best.timing.steps), 2),
                     "rtf_min": round(best.timing.total_ms / 1000 / m["seconds"], 4), "memory_frames": best.memory,
                     "errors": ec.errors, "ref_words": ec.reference_words})
        print(f"{m['id']} {m['seconds']:5.2f}s total {best.timing.total_ms:7.1f} ms (enc {best.timing.encode_ms:6.1f}, "
              f"dec {best.timing.decode_ms:6.1f}, {best.timing.steps} steps) RTF {best.timing.total_ms/1000/m['seconds']:.3f} "
              f"WER {ec.errors}/{ec.reference_words} | {best.text}", flush=True)
    rss, hwm = rss_mb()
    out = {"engine": "whistle-onnx", "fp16": args.fp16, "threads": args.threads, "ep": args.ep, "repeat": args.repeat,
           "load_ms": model.load_ms, "providers": model.providers, "model_bytes": model.model_bytes,
           "cact_bytes": model.assets.cact_bytes, "rss_mb": round(rss, 1), "hwm_mb": round(hwm, 1), "env": env_info(),
           "wer": round(total.wer, 4), "errors": total.errors, "ref_words": total.reference_words, "clips": rows}
    if args.out:
        json.dump(out, open(args.out, "w"), indent=1, ensure_ascii=False)
    print(f"WER {total.errors}/{total.reference_words} = {total.wer:.4f}  RSS {rss:.0f} MB (peak {hwm:.0f})  "
          f"load {model.load_ms}  providers {model.providers}")


def cmd_stream(model: WhistleOrt, args: argparse.Namespace) -> None:
    clips = load_manifest(args.manifest, args.ids, args.min_s, args.max_s)
    rows = []
    tot_stream = tot_batch = EditCounts(0, 0, 0, 0)
    for m in clips:
        pcm = read_wav(m["wav"])
        batch = model.transcribe(pcm, args.language)
        st = model.stream(pcm, args.chunk, args.language, args.window, args.force_prefix)
        ec_s, ec_b = wer_of(m["text"], st["text"]), wer_of(m["text"], batch.text)
        tot_stream, tot_batch = tot_stream + ec_s, tot_batch + ec_b
        rows.append({"id": m["id"], "seconds": m["seconds"], "ref": m["text"], "batch": batch.text, "stream": st,
                     "batch_total_ms": round(batch.timing.total_ms, 1), "stream_errors": ec_s.errors,
                     "batch_errors": ec_b.errors, "ref_words": ec_s.reference_words})
        print(f"{m['id']} {m['seconds']:5.2f}s passes {st['n_passes']} sum {st['sum_pass_ms']:.0f} ms max {st['max_pass_ms']:.0f} ms "
              f"first commit @{st['first_commit_audio_s']}s (+{st['first_commit_latency_ms']} ms) retractions {st['retractions']} "
              f"WER stream {ec_s.errors} batch {ec_b.errors} /{ec_s.reference_words}", flush=True)
        if args.verbose:
            for p in st["passes"]:
                print(f"   @{p['received_s']:5.1f}s {p['pass_ms']:6.0f} ms  +[{p['committed']}]  ...{p['pending']}")
    rss, hwm = rss_mb()
    out = {"engine": "whistle-onnx", "mode": "pseudo-stream", "chunk_s": args.chunk, "window_s": args.window,
           "force_prefix": args.force_prefix, "fp16": args.fp16, "threads": args.threads, "ep": args.ep,
           "rss_mb": round(rss, 1), "hwm_mb": round(hwm, 1), "env": env_info(),
           "wer_stream": round(tot_stream.wer, 4), "wer_batch": round(tot_batch.wer, 4), "clips": rows}
    if args.out:
        json.dump(out, open(args.out, "w"), indent=1, ensure_ascii=False)
    print(f"WER stream {tot_stream.errors}/{tot_stream.reference_words} = {tot_stream.wer:.4f}  "
          f"batch {tot_batch.errors}/{tot_batch.reference_words} = {tot_batch.wer:.4f}  RSS {rss:.0f} MB (peak {hwm:.0f})")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--onnx-dir", required=True)
    ap.add_argument("--cact", required=True, help="whistle.cact (filterbank, tokenizer, engram tables)")
    ap.add_argument("--fp16", action="store_true", help="use the *_fp16.onnx graphs")
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--ep", choices=("cpu", "xnnpack", "nnapi"), default="cpu")
    ap.add_argument("--graph-opt", choices=("none", "basic", "extended", "all"), default="all")
    ap.add_argument("--no-arena", action="store_true", help="disable ORT's CPU memory arena (steady-state RSS)")
    ap.add_argument("--language", default=None, help="force a language code; default: detect")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("transcribe")
    p.add_argument("wav")
    for name in ("bench", "stream"):
        p = sub.add_parser(name)
        p.add_argument("manifest")
        p.add_argument("--ids")
        p.add_argument("--min-s", type=float)
        p.add_argument("--max-s", type=float)
        p.add_argument("--out")
        if name == "bench":
            p.add_argument("--repeat", type=int, default=3)
        else:
            p.add_argument("--chunk", type=float, default=1.0)
            p.add_argument("--window", type=float, default=30.0)
            p.add_argument("--force-prefix", action="store_true")
            p.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    rss0, _ = rss_mb()
    t0 = time.perf_counter()
    assets = WhistleAssets.from_cact(args.cact)
    t_assets = (time.perf_counter() - t0) * 1000
    rss1, _ = rss_mb()
    model = WhistleOrt(args.onnx_dir, assets, fp16=args.fp16, threads=args.threads, ep=args.ep, graph_opt=args.graph_opt,
                       arena=not args.no_arena)
    rss2, _ = rss_mb()
    print(f"# assets {t_assets:.0f} ms (+{rss1-rss0:.0f} MB), sessions {model.load_ms} (+{rss2-rss1:.0f} MB), "
          f"providers {model.providers}", file=sys.stderr)
    {"transcribe": cmd_transcribe, "bench": cmd_bench, "stream": cmd_stream}[args.cmd](model, args)


if __name__ == "__main__":
    main()
