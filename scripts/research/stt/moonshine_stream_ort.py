#!/usr/bin/env python3
"""Moonshine v2 "streaming tiny" (MIT) on ONNX Runtime — research spike, 2026-10-28.

Drives the five graphs of the official Moonshine streaming runtime (frontend, encoder, adapter,
cross_kv, decoder_kv) the way `core/moonshine-streaming-model.cpp` in moonshine-ai/moonshine
does, so audio is encoded INCREMENTALLY:

  frontend   audio chunk + 5 carried states -> 20 ms feature frames (causal conv stem)
  encoder    sliding window: frames older than total_lookahead (16) are stable; the window
             re-encodes 16*depth = 96 past frames of left context plus the new frames and only
             the new stable frames are kept
  adapter    adds absolute position embeddings from a running pos_offset -> memory
  cross_kv   K/V of the whole memory for the decoder (cheap, recomputed when memory grows)
  decoder_kv greedy decode from BOS; the previous pass's tokens are re-verified in ONE
             multi-token call (speculative prefix, as decode_full does), then continued

The same two-pass word agreement rule as whistle_ort.py is applied on top so the "time to first
committed word" and "retractions" numbers are comparable. `bench` feeds the whole clip as one
final chunk (the non-streaming reference for WER and RTF).

Model files: the official `.ort` set from moonshine-ai/moonshine-voice-assets
(model/tiny-streaming-en/quantized_*/) or the plain-ONNX export of the same recipe
(Masterx/moonshine-streaming-tiny-ONNX, --prefix masterx_ --int8). Tokenizer: tokenizer.json
(SentencePiece-BPE vocabulary; decode only).
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from asr_metrics import NORMALISER_NAME, EditCounts, normalise, wer_of  # noqa: E402
from whistle_ort import RATE, env_info, load_manifest, read_wav, rss_mb  # noqa: E402


class SpmTokenizer:
    """Decode-only SentencePiece/BPE vocabulary from a HF tokenizer.json (Llama-style pieces)."""

    def __init__(self, path: str) -> None:
        tj = json.load(open(path))
        vocab = tj["model"]["vocab"]
        self.pieces: dict[int, str] = {i: p for p, i in vocab.items()}
        self.special = {a["id"] for a in tj.get("added_tokens", [])}

    def decode(self, ids: list[int]) -> str:
        out = bytearray()
        for i in ids:
            if i in self.special:
                continue
            p = self.pieces.get(i, "")
            if len(p) == 6 and p.startswith("<0x") and p.endswith(">"):
                out.append(int(p[3:5], 16))
            else:
                out += p.replace("▁", " ").encode("utf-8")
        return out.decode("utf-8", "replace").strip()


@dataclass
class Timing:
    frontend_ms: float = 0.0
    encoder_ms: float = 0.0
    adapter_ms: float = 0.0
    cross_kv_ms: float = 0.0
    decoder_ms: float = 0.0
    steps: int = 0

    @property
    def total_ms(self) -> float:
        return self.frontend_ms + self.encoder_ms + self.adapter_ms + self.cross_kv_ms + self.decoder_ms


@dataclass
class State:
    sample_buffer: np.ndarray
    sample_len: np.ndarray
    conv1_buffer: np.ndarray
    conv2_buffer: np.ndarray
    frame_count: np.ndarray
    features: list[np.ndarray] = field(default_factory=list)  # [1, t, 320] pieces
    n_features: int = 0
    emitted: int = 0
    pos_offset: int = 0
    memory: list[np.ndarray] = field(default_factory=list)
    memory_len: int = 0
    tokens: list[int] = field(default_factory=list)  # last pass's text tokens (speculative prefix)
    timing: Timing = field(default_factory=Timing)


class MoonshineStream:
    def __init__(self, model_dir: str, tokenizer: str, *, prefix: str = "", int8: bool = False,
                 threads: int = 4, ep: str = "cpu", arena: bool = True) -> None:
        import onnxruntime as ort

        self.cfg = json.load(open(os.path.join(model_dir, "streaming_config.json")))
        so = ort.SessionOptions()
        so.intra_op_num_threads = threads
        so.inter_op_num_threads = 1
        so.log_severity_level = 3
        # ORT's BFC arena never shrinks; with a new input shape on every streaming pass (memory
        # and token lengths grow) it keeps every high-water block. --no-arena shows the real
        # steady-state footprint, which is what an IME process would want to configure.
        so.enable_cpu_mem_arena = arena
        providers = {"cpu": ["CPUExecutionProvider"],
                     "xnnpack": ["XnnpackExecutionProvider", "CPUExecutionProvider"]}[ep]
        ext = ".onnx" if prefix else ".ort"
        q = "_int8" if int8 else ""
        names = {"frontend": f"{prefix}frontend{ext}" if prefix else "frontend.ort",
                 "encoder": f"{prefix}encoder{q}{ext}", "adapter": f"{prefix}adapter{q}{ext}",
                 "cross_kv": f"{prefix}cross_kv{q}{ext}", "decoder": f"{prefix}decoder_kv{q}{ext}"}
        self.sessions = {}
        self.load_ms = {}
        self.model_bytes = 0
        for key, fn in names.items():
            path = os.path.join(model_dir, fn)
            if key == "frontend" and not os.path.exists(path):
                # official 26_08_21 layout splits the frontend into model + weights; use the
                # older single-file frontend.ort or the plain-ONNX frontend if present
                for alt in ("frontend.ort", f"{prefix}frontend.onnx", "masterx_frontend.onnx"):
                    if os.path.exists(os.path.join(model_dir, alt)):
                        path = os.path.join(model_dir, alt)
                        break
            t0 = time.perf_counter()
            self.sessions[key] = ort.InferenceSession(path, so, providers=providers)
            self.load_ms[key] = round((time.perf_counter() - t0) * 1000, 1)
            self.model_bytes += os.path.getsize(path)
        self.providers = {k: s.get_providers()[0] for k, s in self.sessions.items()}
        self.tok = SpmTokenizer(tokenizer)
        self.depth = self.cfg["depth"]
        self.lookahead = self.cfg["total_lookahead"]
        self.bos, self.eos = self.cfg["bos_id"], self.cfg["eos_id"]
        self.vocab = self.cfg["vocab_size"]
        self.max_tokens = self.cfg.get("max_seq_len", 448)

    def new_state(self) -> State:
        shp = self.cfg["frontend_state_shapes"]
        return State(np.zeros(shp["sample_buffer"], np.float32), np.zeros(shp["sample_len"], np.int64),
                     np.zeros(shp["conv1_buffer"], np.float32), np.zeros(shp["conv2_buffer"], np.float32),
                     np.zeros(shp["frame_count"], np.int64))

    # -- the five graphs --------------------------------------------------------------
    def feed(self, st: State, pcm: np.ndarray, final: bool) -> int:
        """Frontend + incremental encoder + adapter for one audio chunk. Returns new memory frames."""
        t0 = time.perf_counter()
        if len(pcm):
            outs = self.sessions["frontend"].run(None, {
                "audio_chunk": pcm.astype(np.float32)[None, :], "sample_buffer": st.sample_buffer,
                "sample_len": st.sample_len, "conv1_buffer": st.conv1_buffer, "conv2_buffer": st.conv2_buffer,
                "frame_count": st.frame_count})
            feats, st.sample_buffer, st.sample_len, st.conv1_buffer, st.conv2_buffer, st.frame_count = outs
            if feats.shape[1]:
                st.features.append(feats)
                st.n_features += feats.shape[1]
        t1 = time.perf_counter()
        st.timing.frontend_ms += (t1 - t0) * 1000
        stable = st.n_features if final else max(0, st.n_features - self.lookahead)
        new = stable - st.emitted
        if new <= 0:
            return 0
        window_start = max(0, st.emitted - 16 * self.depth)
        all_feats = np.concatenate(st.features, axis=1) if len(st.features) > 1 else st.features[0]
        st.features = [all_feats]
        encoded = self.sessions["encoder"].run(None, {"features": all_feats[:, window_start:, :]})[0]
        t2 = time.perf_counter()
        start = st.emitted - window_start
        piece = np.ascontiguousarray(encoded[:, start : start + new, :])
        memory = self.sessions["adapter"].run(None, {"encoded": piece, "pos_offset": np.array([st.pos_offset], np.int64)})[0]
        t3 = time.perf_counter()
        st.memory.append(memory)
        st.memory_len += new
        st.emitted = stable
        st.pos_offset += new
        st.timing.encoder_ms += (t2 - t1) * 1000
        st.timing.adapter_ms += (t3 - t2) * 1000
        return new

    def decode(self, st: State) -> list[int]:
        """Greedy decode against the current memory, re-verifying last pass's tokens in one call."""
        if st.memory_len == 0:
            return []
        t0 = time.perf_counter()
        mem = np.concatenate(st.memory, axis=1) if len(st.memory) > 1 else st.memory[0]
        st.memory = [mem]
        k_cross, v_cross = self.sessions["cross_kv"].run(None, {"memory": mem})
        t1 = time.perf_counter()
        st.timing.cross_kv_ms += (t1 - t0) * 1000
        dec = self.sessions["decoder"]
        empty = np.zeros((self.depth, 1, self.cfg["nheads"], 0, self.cfg["head_dim"]), np.float32)

        def run(tokens: list[int], k_self: np.ndarray, v_self: np.ndarray):
            logits, k_self, v_self, _, _ = dec.run(None, {
                "token": np.array([tokens], np.int64), "k_self": k_self, "v_self": v_self,
                "out_k_cross": k_cross, "out_v_cross": v_cross})
            return logits[0], k_self, v_self

        steps = 0
        prefix = [self.bos] + st.tokens
        logits, k_self, v_self = run(prefix, empty, empty)
        steps += 1
        pred = logits.argmax(axis=-1)
        accepted: list[int] = []
        for i, tok in enumerate(st.tokens):
            if int(pred[i]) != tok:
                break
            accepted.append(tok)
        if len(accepted) != len(st.tokens):  # diverged: rebuild the cache on the accepted prefix
            logits, k_self, v_self = run([self.bos] + accepted, empty, empty)
            steps += 1
        nxt = int(pred[len(accepted)]) if len(accepted) < len(pred) else int(logits[-1].argmax())
        if len(accepted) == len(st.tokens):
            nxt = int(logits[-1].argmax())
        out = list(accepted)
        while nxt != self.eos and len(out) < self.max_tokens:
            out.append(nxt)
            logits, k_self, v_self = run([nxt], k_self, v_self)
            steps += 1
            nxt = int(logits[-1].argmax())
        st.timing.decoder_ms += (time.perf_counter() - t1) * 1000
        st.timing.steps += steps
        st.tokens = out
        return out

    # -- whole-clip reference -----------------------------------------------------------
    def transcribe(self, pcm: np.ndarray) -> tuple[str, Timing, int]:
        st = self.new_state()
        self.feed(st, pcm, final=True)
        ids = self.decode(st)
        return self.tok.decode(ids), st.timing, st.memory_len

    # -- streaming ------------------------------------------------------------------------
    def stream(self, pcm: np.ndarray, chunk_s: float) -> dict:
        chunk = int(round(chunk_s * RATE))
        st = self.new_state()
        n = len(pcm)
        passes, prev_words, committed = [], [], []
        retractions = 0
        first_commit_audio_s = first_commit_latency_ms = None
        received = 0
        while received < n:
            nxt = min(n, received + chunk)
            piece = pcm[received:nxt]
            received = nxt
            final = received >= n
            before = Timing(**st.timing.__dict__)
            t0 = time.perf_counter()
            self.feed(st, piece, final)
            ids = self.decode(st)
            pass_ms = (time.perf_counter() - t0) * 1000
            words = self.tok.decode(ids).split()
            norm = [" ".join(normalise(w)) for w in words]
            prev_norm = [" ".join(normalise(w)) for w in prev_words]
            agree = 0
            while agree < min(len(norm), len(prev_norm)) and norm[agree] == prev_norm[agree]:
                agree += 1
            newly = []
            if agree > len(committed):
                newly = words[len(committed) : agree]
                committed += newly
                if first_commit_audio_s is None:
                    first_commit_audio_s = received / RATE
                    first_commit_latency_ms = pass_ms
            pend_prev = prev_norm[len(committed) - len(newly) :]
            pend_now = norm[len(committed) - len(newly) :]
            retractions += sum(1 for a, b in zip(pend_prev, pend_now) if a != b) + max(0, len(pend_prev) - len(pend_now))
            d = st.timing
            passes.append({"received_s": round(received / RATE, 2), "pass_ms": round(pass_ms, 1),
                           "frontend_ms": round(d.frontend_ms - before.frontend_ms, 1),
                           "encoder_ms": round(d.encoder_ms - before.encoder_ms, 1),
                           "adapter_ms": round(d.adapter_ms - before.adapter_ms, 1),
                           "cross_kv_ms": round(d.cross_kv_ms - before.cross_kv_ms, 1),
                           "decoder_ms": round(d.decoder_ms - before.decoder_ms, 1), "steps": d.steps - before.steps,
                           "memory_frames": st.memory_len, "partial": " ".join(words),
                           "committed": " ".join(newly), "pending": " ".join(words[len(committed):])})
            prev_words = words
        tail = prev_words[len(committed):]
        return {"text": " ".join(committed + tail), "chunk_s": chunk_s, "passes": passes, "n_passes": len(passes),
                "sum_pass_ms": round(sum(p["pass_ms"] for p in passes), 1),
                "max_pass_ms": round(max(p["pass_ms"] for p in passes), 1),
                "first_commit_audio_s": first_commit_audio_s, "first_commit_latency_ms": first_commit_latency_ms,
                "first_partial_audio_s": next((p["received_s"] for p in passes if p["partial"]), None),
                "retractions": retractions, "committed_words": len(committed), "tail_words": len(tail),
                "timing": st.timing.__dict__}


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model-dir", required=True)
    ap.add_argument("--tokenizer", required=True, help="tokenizer.json")
    ap.add_argument("--prefix", default="", help="file prefix for the plain-ONNX export (e.g. masterx_)")
    ap.add_argument("--int8", action="store_true")
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--ep", choices=("cpu", "xnnpack"), default="cpu")
    ap.add_argument("--no-arena", action="store_true", help="disable ORT's CPU memory arena (steady-state RSS)")
    sub = ap.add_subparsers(dest="cmd", required=True)
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
            p.add_argument("--verbose", action="store_true")
    args = ap.parse_args()
    rss0, _ = rss_mb()
    model = MoonshineStream(args.model_dir, args.tokenizer, prefix=args.prefix, int8=args.int8, threads=args.threads, ep=args.ep,
                            arena=not args.no_arena)
    rss1, _ = rss_mb()
    print(f"# sessions {model.load_ms} (+{rss1-rss0:.0f} MB) providers {model.providers} bytes {model.model_bytes:,}", file=sys.stderr)
    clips = load_manifest(args.manifest, args.ids, args.min_s, args.max_s)
    rows = []
    tot = tot_b = EditCounts(0, 0, 0, 0)
    for m in clips:
        pcm = read_wav(m["wav"])
        if args.cmd == "bench":
            runs = [model.transcribe(pcm) for _ in range(args.repeat)]
            text, tm, mem = min(runs, key=lambda r: r[1].total_ms)
            med = sorted(r[1].total_ms for r in runs)[len(runs) // 2]
            ec = wer_of(m["text"], text)
            tot = tot + ec
            rows.append({"id": m["id"], "seconds": m["seconds"], "ref": m["text"], "hyp": text, "min_total_ms": round(tm.total_ms, 1),
                         "median_total_ms": round(med, 1), **{k: round(v, 1) for k, v in tm.__dict__.items() if k != "steps"},
                         "steps": tm.steps, "memory_frames": mem, "rtf_min": round(tm.total_ms / 1000 / m["seconds"], 4),
                         "errors": ec.errors, "ref_words": ec.reference_words})
            print(f"{m['id']} {m['seconds']:5.2f}s total {tm.total_ms:7.1f} ms (front {tm.frontend_ms:5.1f} enc {tm.encoder_ms:6.1f} "
                  f"dec {tm.decoder_ms:6.1f}, {tm.steps} steps) RTF {tm.total_ms/1000/m['seconds']:.3f} WER {ec.errors}/{ec.reference_words} | {text}", flush=True)
        else:
            text_b, tm_b, _ = model.transcribe(pcm)
            st = model.stream(pcm, args.chunk)
            ec, ec_b = wer_of(m["text"], st["text"]), wer_of(m["text"], text_b)
            tot, tot_b = tot + ec, tot_b + ec_b
            rows.append({"id": m["id"], "seconds": m["seconds"], "ref": m["text"], "batch": text_b, "stream": st,
                         "batch_total_ms": round(tm_b.total_ms, 1), "stream_errors": ec.errors, "batch_errors": ec_b.errors,
                         "ref_words": ec.reference_words})
            print(f"{m['id']} {m['seconds']:5.2f}s passes {st['n_passes']} sum {st['sum_pass_ms']:.0f} ms max {st['max_pass_ms']:.0f} ms "
                  f"first partial @{st['first_partial_audio_s']}s first commit @{st['first_commit_audio_s']}s (+{st['first_commit_latency_ms']} ms) "
                  f"retractions {st['retractions']} WER stream {ec.errors} batch {ec_b.errors} /{ec.reference_words}", flush=True)
            if args.verbose:
                for p in st["passes"]:
                    print(f"   @{p['received_s']:5.1f}s {p['pass_ms']:6.0f} ms (enc {p['encoder_ms']:.0f} dec {p['decoder_ms']:.0f}) +[{p['committed']}] ...{p['pending']}")
    rss, hwm = rss_mb()
    out = {"engine": "moonshine-streaming-tiny-en", "int8": args.int8, "prefix": args.prefix, "threads": args.threads, "ep": args.ep,
           "load_ms": model.load_ms, "providers": model.providers, "model_bytes": model.model_bytes, "rss_mb": round(rss, 1),
           "hwm_mb": round(hwm, 1), "env": env_info(), "clips": rows, "normaliser": NORMALISER_NAME}
    if args.cmd == "bench":
        out.update({"repeat": args.repeat, "wer": round(tot.wer, 4), "errors": tot.errors, "ref_words": tot.reference_words})
        print(f"WER {tot.errors}/{tot.reference_words} = {tot.wer:.4f}  RSS {rss:.0f} MB (peak {hwm:.0f})  load {model.load_ms}")
    else:
        out.update({"chunk_s": args.chunk, "wer_stream": round(tot.wer, 4), "wer_batch": round(tot_b.wer, 4)})
        print(f"WER stream {tot.errors}/{tot.reference_words} = {tot.wer:.4f}  batch {tot_b.errors}/{tot_b.reference_words} = {tot_b.wer:.4f}  RSS {rss:.0f} MB (peak {hwm:.0f})")
    if args.out:
        json.dump(out, open(args.out, "w"), indent=1, ensure_ascii=False)


if __name__ == "__main__":
    main()
