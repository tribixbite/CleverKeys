#!/usr/bin/env python3
"""sherpa-onnx streaming Zipformer transducer (k2-fsa, Apache-2.0) on ONNX Runtime, driven
directly from Python — research spike, 2026-10-28.

Model: sherpa-onnx-streaming-zipformer-en-20M-2023-02-17 (icefall pruned_transducer_stateless7
_streaming "small", 20 M parameters; encoder int8 32.8 MB, decoder 2.1 MB, joiner int8 0.26 MB,
500 BPE tokens). It is a TRUE streaming model: the encoder takes 39 feature frames (32 new
frames of 10 ms + 7 of right context, "decode_chunk_len=32"/"T=39") plus 35 carried state tensors
and emits 8 encoder frames per chunk (one per 40 ms); greedy transducer search emits tokens as
they appear, so there is no re-decoding and nothing is ever retracted.

Features are Kaldi fbank computed here in numpy (povey window, pre-emphasis 0.97, DC removal,
25 ms / 10 ms, 512-point FFT, 80 mel bins 20 Hz..Nyquist, natural-log energies, dither 0,
snip_edges=False) to match sherpa-onnx's `kaldi-native-fbank` defaults for this model. Small
numerical differences from the C++ implementation are possible; WER here is a sanity check of
the pipeline, not a reproduction of the published number.

  python3 sherpa_zipformer_ort.py --model-dir $STT/models/sherpa/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17-mobile \
      stream $STT/audio/manifest.json --ids ... --chunk 0.32 --out results.json
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from asr_metrics import NORMALISER_NAME, EditCounts, wer_of  # noqa: E402
from whistle_ort import RATE, env_info, load_manifest, read_wav, rss_mb  # noqa: E402

FRAME_LEN, FRAME_SHIFT, N_FFT, N_MELS = 400, 160, 512, 80


def _mel_banks(n_mels: int, n_fft: int, rate: int, low: float = 20.0, high: float = 0.0) -> np.ndarray:
    """Kaldi-style triangular filterbank over the FFT bins (HTK mel scale)."""
    nyq = rate / 2
    high = nyq + high if high <= 0 else high
    mel = lambda f: 1127.0 * np.log(1.0 + f / 700.0)  # noqa: E731
    mel_lo, mel_hi = mel(low), mel(high)
    delta = (mel_hi - mel_lo) / (n_mels + 1)
    bins = np.arange(n_fft // 2 + 1) * rate / n_fft
    bank = np.zeros((n_fft // 2 + 1, n_mels), np.float32)
    for m in range(n_mels):
        left, centre, right = mel_lo + m * delta, mel_lo + (m + 1) * delta, mel_lo + (m + 2) * delta
        mb = mel(bins)
        up = (mb - left) / (centre - left)
        down = (right - mb) / (right - centre)
        bank[:, m] = np.clip(np.minimum(up, down), 0.0, None)
    return bank


class KaldiFbank:
    def __init__(self) -> None:
        n = np.arange(FRAME_LEN)
        self.window = (0.5 - 0.5 * np.cos(2 * np.pi * n / (FRAME_LEN - 1))) ** 0.85  # povey
        self.bank = _mel_banks(N_MELS, N_FFT, RATE, high=-400.0)  # sherpa-onnx FeatureExtractorConfig.high_freq

    def __call__(self, pcm: np.ndarray) -> np.ndarray:
        """pcm float in [-1, 1] -> [frames, 80] log-mel. sherpa-onnx feeds kaldi-native-fbank the
        [-1, 1] floats as they are (no int16 scaling), so the log energies sit ~20.8 lower than
        Kaldi's int16 convention; the model was trained that way."""
        x = pcm.astype(np.float32)
        n = len(x)
        n_frames = (n + FRAME_SHIFT // 2) // FRAME_SHIFT  # snip_edges=False
        if n_frames <= 0:
            return np.zeros((0, N_MELS), np.float32)
        half = FRAME_LEN // 2
        padded = np.concatenate([x[1 : half + 1][::-1], x, x[-half - 1 : -1][::-1]])  # reflect
        idx = np.arange(n_frames)[:, None] * FRAME_SHIFT + np.arange(FRAME_LEN)[None, :]
        frames = padded[idx]
        frames = frames - frames.mean(axis=1, keepdims=True)  # remove_dc_offset
        pre = np.empty_like(frames)
        pre[:, 1:] = frames[:, 1:] - 0.97 * frames[:, :-1]
        pre[:, 0] = frames[:, 0] - 0.97 * frames[:, 0]
        spec = np.fft.rfft(pre * self.window, n=N_FFT, axis=1)
        power = spec.real ** 2 + spec.imag ** 2
        mel = power @ self.bank
        return np.log(np.maximum(mel, np.finfo(np.float32).eps)).astype(np.float32)


class ZipformerStream:
    def __init__(self, model_dir: str, *, threads: int = 4, int8: bool = True) -> None:
        import onnxruntime as ort

        so = ort.SessionOptions()
        so.intra_op_num_threads = threads
        so.inter_op_num_threads = 1
        so.log_severity_level = 3
        pick = lambda stem: os.path.join(model_dir, f"{stem}.int8.onnx" if int8 and os.path.exists(os.path.join(model_dir, f"{stem}.int8.onnx")) else f"{stem}.onnx")  # noqa: E731
        self.paths = {"encoder": pick("encoder-epoch-99-avg-1"), "decoder": pick("decoder-epoch-99-avg-1"), "joiner": pick("joiner-epoch-99-avg-1")}
        self.load_ms, self.sessions = {}, {}
        for k, p in self.paths.items():
            t0 = time.perf_counter()
            self.sessions[k] = ort.InferenceSession(p, so, providers=["CPUExecutionProvider"])
            self.load_ms[k] = round((time.perf_counter() - t0) * 1000, 1)
        self.model_bytes = sum(os.path.getsize(p) for p in self.paths.values())
        meta = self.sessions["encoder"].get_modelmeta().custom_metadata_map
        self.chunk_frames = int(meta["T"])  # 39 input frames per step
        self.shift_frames = int(meta["decode_chunk_len"])  # 32
        dmeta = self.sessions["decoder"].get_modelmeta().custom_metadata_map
        self.context_size = int(dmeta.get("context_size", 2))
        self.vocab = int(dmeta.get("vocab_size", 500))
        self.tokens = {}
        for line in open(os.path.join(model_dir, "tokens.txt"), encoding="utf-8"):
            sym, idx = line.rstrip("\n").rsplit(" ", 1)
            self.tokens[int(idx)] = sym
        self.state_inputs = [i for i in self.sessions["encoder"].get_inputs() if i.name != "x"]
        self.fbank = KaldiFbank()

    def init_states(self) -> dict[str, np.ndarray]:
        states = {}
        for i in self.state_inputs:
            dtype = np.int64 if "int64" in i.type else np.float32
            states[i.name] = np.zeros([d if isinstance(d, int) else 1 for d in i.shape], dtype)
        return states

    def decode_text(self, ids: list[int]) -> str:
        return "".join(self.tokens.get(i, "") for i in ids).replace("▁", " ").strip()

    def run(self, pcm: np.ndarray, chunk_s: float | None) -> dict:
        """Feed the clip in `chunk_s` pieces (None = all at once; identical encoder maths, since
        the encoder only ever sees 39-frame windows) and record when each token appeared."""
        t_all = time.perf_counter()
        # tail padding as sherpa-onnx does at end of stream: 0.3 s of zero samples so the last
        # real frames clear the encoder's right context
        feats_all = self.fbank(np.concatenate([pcm, np.zeros(int(0.3 * RATE), np.float32)]))
        fe_ms = (time.perf_counter() - t_all) * 1000
        states = self.init_states()
        hyp = [0] * self.context_size  # blank context (sherpa-onnx uses -1 padding, clamped to 0 inside the graph)
        dec_out = None
        emitted: list[tuple[int, float]] = []  # (token, audio seconds when emitted)
        enc_ms = dec_ms = 0.0
        step = 0
        frames_per_chunk = None if chunk_s is None else max(self.shift_frames, int(round(chunk_s * 100)) // self.shift_frames * self.shift_frames)
        pos = 0
        passes = []
        n_steps = 0
        while pos + self.chunk_frames <= feats_all.shape[0]:
            t0 = time.perf_counter()
            x = feats_all[pos : pos + self.chunk_frames][None].astype(np.float32)
            outs = self.sessions["encoder"].run(None, {"x": x, **states})
            enc_out = outs[0][0]  # [8, 512]
            for name, val in zip([i.name for i in self.state_inputs], outs[1:]):
                states[name] = val
            t1 = time.perf_counter()
            if dec_out is None:
                dec_out = self.sessions["decoder"].run(None, {"y": np.array([hyp[-self.context_size:]], np.int64)})[0]
            for t in range(enc_out.shape[0]):
                logit = self.sessions["joiner"].run(None, {"encoder_out": enc_out[t : t + 1], "decoder_out": dec_out})[0][0]
                k = int(logit.argmax())
                if k != 0:
                    hyp.append(k)
                    emitted.append((k, (pos + self.shift_frames) / 100.0))
                    dec_out = self.sessions["decoder"].run(None, {"y": np.array([hyp[-self.context_size:]], np.int64)})[0]
            t2 = time.perf_counter()
            enc_ms += (t1 - t0) * 1000
            dec_ms += (t2 - t1) * 1000
            pos += self.shift_frames
            step += 1
            n_steps += 1
            if frames_per_chunk is not None and (pos % frames_per_chunk == 0):
                passes.append({"received_s": round(pos / 100.0, 2), "pass_ms": round((t2 - t0) * 1000, 1),
                               "partial": self.decode_text(hyp[self.context_size:])})
        ids = hyp[self.context_size:]
        total_ms = (time.perf_counter() - t_all) * 1000
        first_word_s = emitted[0][1] if emitted else None
        return {"text": self.decode_text(ids), "ids": ids, "fbank_ms": round(fe_ms, 1), "encoder_ms": round(enc_ms, 1),
                "decoder_joiner_ms": round(dec_ms, 1), "total_ms": round(total_ms, 1), "steps": n_steps,
                "ms_per_step": round((enc_ms + dec_ms) / max(1, n_steps), 2), "first_token_audio_s": first_word_s,
                "passes": passes[:64]}


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model-dir", required=True)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--fp32", action="store_true", help="use the fp32 encoder instead of int8")
    ap.add_argument("manifest")
    ap.add_argument("--ids")
    ap.add_argument("--min-s", type=float)
    ap.add_argument("--max-s", type=float)
    ap.add_argument("--repeat", type=int, default=2)
    ap.add_argument("--chunk", type=float, default=0.32, help="seconds per reported pass (multiple of 0.32)")
    ap.add_argument("--out")
    args = ap.parse_args()
    rss0, _ = rss_mb()
    model = ZipformerStream(args.model_dir, threads=args.threads, int8=not args.fp32)
    rss1, _ = rss_mb()
    print(f"# sessions {model.load_ms} (+{rss1-rss0:.0f} MB) bytes {model.model_bytes:,} T={model.chunk_frames} shift={model.shift_frames}", file=sys.stderr)
    rows, total = [], EditCounts(0, 0, 0, 0)
    for m in load_manifest(args.manifest, args.ids, args.min_s, args.max_s):
        pcm = read_wav(m["wav"])
        runs = [model.run(pcm, args.chunk) for _ in range(args.repeat)]
        best = min(runs, key=lambda r: r["total_ms"])
        ec = wer_of(m["text"], best["text"])
        total = total + ec
        rows.append({"id": m["id"], "seconds": m["seconds"], "ref": m["text"], "hyp": best["text"], **{k: v for k, v in best.items() if k not in ("text", "ids")},
                     "rtf_min": round(best["total_ms"] / 1000 / m["seconds"], 4), "errors": ec.errors, "ref_words": ec.reference_words})
        print(f"{m['id']} {m['seconds']:5.2f}s total {best['total_ms']:7.1f} ms (fbank {best['fbank_ms']:.0f} enc {best['encoder_ms']:.0f} dec+join {best['decoder_joiner_ms']:.0f}, "
              f"{best['steps']} steps, {best['ms_per_step']} ms/step) RTF {best['total_ms']/1000/m['seconds']:.3f} first token @{best['first_token_audio_s']}s "
              f"WER {ec.errors}/{ec.reference_words} | {best['text']}", flush=True)
    rss, hwm = rss_mb()
    out = {"engine": "sherpa-onnx-streaming-zipformer-en-20M", "int8": not args.fp32, "threads": args.threads, "repeat": args.repeat,
           "chunk_s": args.chunk, "load_ms": model.load_ms, "model_bytes": model.model_bytes, "rss_mb": round(rss, 1), "hwm_mb": round(hwm, 1),
           "env": env_info(), "normaliser": NORMALISER_NAME, "wer": round(total.wer, 4), "errors": total.errors,
           "ref_words": total.reference_words, "clips": rows}
    if args.out:
        json.dump(out, open(args.out, "w"), indent=1, ensure_ascii=False)
    print(f"WER {total.errors}/{total.reference_words} = {total.wer:.4f}  RSS {rss:.0f} MB (peak {hwm:.0f})  load {model.load_ms}")


if __name__ == "__main__":
    main()
