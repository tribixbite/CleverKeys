#!/usr/bin/env python3
"""
CLI Prediction Test — decode swipe traces with the CURRENT production ONNX models.

Uses the Android model architecture (encoder input ``actual_length`` int32 scalar,
NOT a bool ``src_mask``; decoder inputs int32 ``target_tokens`` + int32
``actual_src_length``, output pre-log-softmaxed ``log_probs``; tokens a=4..z=29;
DECODER_SEQ_LENGTH=20). Verified against
``src/main/assets/models/swipe_{encoder,decoder}_android.onnx`` (2026-07-21):

    encoder  trajectory_features [b,250,6] f32 | nearest_keys [b,250] i32 | actual_length [b] i32
             -> encoder_output [b,250,256] f32
    decoder  memory [1,enc_seq,256] f32 | target_tokens [num_beams,20] i32 | actual_src_length [1] i32
             -> log_probs [num_beams,20,30] f32   (already log-softmaxed)

The decoder supports BROADCAST batched decode (``target_tokens`` shape
``[num_active_beams, 20]`` with ``memory`` staying ``[1, seq, 256]``), the same
6-8x speedup path the web_demo (``web_demo/demo/index.html`` ``stepBatched``) uses;
this runner uses it by default.

── Two run modes ──────────────────────────────────────────────────────────────
1. LEGACY smoke test (default, no ``--corpus``): the original 100-sample smoke over
   ``swype-model-training/swipes.jsonl`` in the ``{word, curve:{x,y,t}}`` shape.
2. LOCAL-CORPUS head-to-head (``--corpus PATH``): decode the gzipped
   ``{word, w, h, pts:[[nx,ny,t],...]}`` cache that the geometric replay
   (``GeoLocalCorpusReplayTest`` / ``scripts/build_local_corpus_replay.mjs``)
   consumes, apply the SAME in-dict filter (word must be in the 98,140-word en
   dictionary, ``--dict src/main/assets/dictionaries/en_enhanced.json``), and report
   neural top-1/3/5 both RAW (any beam string) and DICTIONARY-FILTERED (drop beams
   not in the dict) plus by length stratum (2-3 / 4-6 / 7+). This is the NEURAL half
   of the geometric-vs-neural head-to-head (spec ``docs/specs/geometric-swipe-engine.md``).

── CORRUPT-TIMESTAMP HANDICAP (position-only default) ─────────────────────────
The combined-English corpus has broken timestamps (t arrays reset / deltas broken),
so velocity/acceleration features computed from t actively HURT accuracy on THIS
corpus. Historical measurement: position-only 53% top-1 vs with-velocity 29%. This
runner therefore defaults to POSITION-ONLY features (velocity/accel zeroed). Pass
``--velocity`` to reconfirm the degradation on a probe (``--limit``).

── Coordinate frame ───────────────────────────────────────────────────────────
The corpus ``pts`` are normalized [0,1] over the 360x215 px ``qwerty_english`` canvas
(nx = px/360, ny = px/215). This runner reconstructs raw px (nx*360, ny*215) and then
applies the SAME model-input normalization the historical 53% reference used:
``x_norm = px/360``, ``y_norm = px/280`` (the /280 is the model-input squash band the
encoder was trained on, NOT the geometric canvas — see the geo test KDoc). Nearest
keys use the ``QWERTY_KEYS`` pixel centroids on raw px (the repo-authoritative grid,
identical to the geo test's ``QWERTY_ENGLISH_KEYS``).

Usage:
    # legacy smoke
    python3 tools/test_cli_predict.py
    # full head-to-head over the local cache (position-only, beam=8)
    python3 tools/test_cli_predict.py \
        --corpus ~/.cache/cleverkeys-test/combined_english_swipes.jsonl.gz \
        --dict src/main/assets/dictionaries/en_enhanced.json \
        --beam 8 [--limit N] [--velocity] [--out results.jsonl]

Runs under proot-distro Ubuntu (glibc) where onnxruntime loads; plain Termux
(bionic) cannot load ORT. See the env notes in the head-to-head report.
"""

import argparse
import gzip
import json
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort

# Constants matching Android/Kotlin implementation
MAX_SEQUENCE_LENGTH = 250
DECODER_SEQ_LENGTH = 20
VOCAB_SIZE = 30
PAD_IDX = 0
SOS_IDX = 2
EOS_IDX = 3
BEAM_WIDTH = 8

# Model-input coordinate-normalization band (the historical 53% reference).
# x over the 360 px canvas; y over a 280 px band the encoder was trained on.
NORM_X = 360.0
NORM_Y = 280.0

# Keyboard layout (qwerty_english) — repo-authoritative PIXEL centroids, identical to
# GeoLocalCorpusReplayTest.QWERTY_ENGLISH_KEYS and model/train_character_model.py grid.
QWERTY_KEYS = {
    'q': (18, 34), 'w': (54, 34), 'e': (90, 34), 'r': (126, 34), 't': (162, 34),
    'y': (198, 34), 'u': (234, 34), 'i': (270, 34), 'o': (306, 34), 'p': (342, 34),
    'a': (36, 93), 's': (72, 93), 'd': (108, 93), 'f': (144, 93), 'g': (180, 93),
    'h': (216, 93), 'j': (252, 93), 'k': (288, 93), 'l': (324, 93),
    'z': (72, 152), 'x': (108, 152), 'c': (144, 152), 'v': (180, 152), 'b': (216, 152),
    'n': (252, 152), 'm': (288, 152),
}

# Reverse lookup: key index to character (a=4..z=29)
KEY_IDX_TO_CHAR = ['<pad>', '<unk>', '<sos>', '<eos>'] + list('abcdefghijklmnopqrstuvwxyz')
CHAR_TO_KEY_IDX = {c: i for i, c in enumerate(KEY_IDX_TO_CHAR)}


def get_nearest_key(x, y):
    """Nearest keyboard key token index to a raw-pixel point (2D)."""
    min_dist = float('inf')
    nearest = '<unk>'
    for key, (kx, ky) in QWERTY_KEYS.items():
        dist = (x - kx) ** 2 + (y - ky) ** 2
        if dist < min_dist:
            min_dist = dist
            nearest = key
    return CHAR_TO_KEY_IDX.get(nearest, 1)  # 1 = <unk>


def extract_features(xs, ys, ts, use_velocity):
    """Extract trajectory features from raw-pixel swipe coordinates.

    Position-only by default (velocity/acceleration zeroed) because THIS corpus has
    corrupt timestamps that hurt accuracy (position-only ~53% vs velocity ~29%).
    Pass ``use_velocity=True`` to compute time-normalized deltas (the training
    formula) and reconfirm the degradation.

    Returns (trajectory_features [N,6], nearest_keys [N]).
    """
    n = len(xs)
    trajectory_features = []
    nearest_keys = []

    # Precompute velocity/accel only when requested (time-normalized, clipped to
    # [-10, 10] — matching TrajectoryFeatureCalculator / the web_demo).
    vxs = [0.0] * n
    vys = [0.0] * n
    axs = [0.0] * n
    ays = [0.0] * n
    if use_velocity:
        for i in range(1, n):
            dt = (ts[i] - ts[i - 1]) if ts is not None else 1.0
            if dt <= 0:
                dt = 1.0
            vxs[i] = _clip((xs[i] - xs[i - 1]) / NORM_X / dt)
            vys[i] = _clip((ys[i] - ys[i - 1]) / NORM_Y / dt)
        for i in range(2, n):
            dt = (ts[i] - ts[i - 1]) if ts is not None else 1.0
            if dt <= 0:
                dt = 1.0
            axs[i] = _clip((vxs[i] - vxs[i - 1]) / dt)
            ays[i] = _clip((vys[i] - vys[i - 1]) / dt)

    for i in range(n):
        x_norm = xs[i] / NORM_X
        y_norm = ys[i] / NORM_Y
        trajectory_features.append([x_norm, y_norm, vxs[i], vys[i], axs[i], ays[i]])
        nearest_keys.append(get_nearest_key(xs[i], ys[i]))

    return trajectory_features, nearest_keys


def _clip(v, lo=-10.0, hi=10.0):
    return max(lo, min(hi, v))


def create_tensors(trajectory_features, nearest_keys):
    """Create ONNX encoder input tensors (Android architecture)."""
    actual_length = len(trajectory_features)

    traj_tensor = np.zeros((1, MAX_SEQUENCE_LENGTH, 6), dtype=np.float32)
    for i in range(min(actual_length, MAX_SEQUENCE_LENGTH)):
        traj_tensor[0, i] = trajectory_features[i]

    keys_tensor = np.full((1, MAX_SEQUENCE_LENGTH), PAD_IDX, dtype=np.int32)
    for i in range(min(actual_length, MAX_SEQUENCE_LENGTH)):
        keys_tensor[0, i] = nearest_keys[i]

    actual_length_tensor = np.array([min(actual_length, MAX_SEQUENCE_LENGTH)], dtype=np.int32)
    return traj_tensor, keys_tensor, actual_length_tensor


def decode_prediction(token_indices):
    """Decode a token sequence to a lowercase word (skip specials)."""
    chars = []
    for idx in token_indices:
        if idx == SOS_IDX:
            continue
        if idx == EOS_IDX or idx == PAD_IDX:
            break
        if 4 <= idx < len(KEY_IDX_TO_CHAR):
            chars.append(KEY_IDX_TO_CHAR[idx])
    return ''.join(chars)


def run_beam_search(decoder_session, memory, actual_src_length, beam_size, max_len):
    """Broadcast-batched beam search — Android decoder.

    Mirrors the web_demo ``stepBatched`` semantics: score = SUM of step log-probs
    (higher = better); token position = min(len-1, 19); finished on EOS; finished
    beams carry forward; early-stop when all finished OR (step>=10 and >=3 finished).

    Returns list of (sequence, score) for the final beams, best-first.
    """
    actual_src_len_tensor = np.array([actual_src_length], dtype=np.int32)

    beams = [{'tokens': [SOS_IDX], 'score': 0.0, 'finished': False}]
    # Loop ceiling: the decoder input is fixed at DECODER_SEQ_LENGTH=20, so steps past
    # token-index 19 re-read the same clamped position for zero new information.
    step_cap = min(max_len, DECODER_SEQ_LENGTH - 1)

    for step in range(step_cap):
        active = [b for b in beams if not b['finished']]
        if not active:
            break
        finished_carry = [b for b in beams if b['finished']]

        # Batched target_tokens: [num_active, 20]
        batch = len(active)
        tgt = np.full((batch, DECODER_SEQ_LENGTH), PAD_IDX, dtype=np.int32)
        for b_i, beam in enumerate(active):
            for i, tok in enumerate(beam['tokens'][:DECODER_SEQ_LENGTH]):
                tgt[b_i, i] = tok

        log_probs = decoder_session.run(
            None,
            {
                'memory': memory,                       # [1, enc_seq, 256] — broadcast
                'target_tokens': tgt,                   # [num_active, 20]
                'actual_src_length': actual_src_len_tensor,  # [1]
            },
        )[0]  # -> [num_active, 20, 30]

        candidates = list(finished_carry)
        for b_i, beam in enumerate(active):
            tok_pos = min(len(beam['tokens']) - 1, DECODER_SEQ_LENGTH - 1)
            step_lp = log_probs[b_i, tok_pos]  # [30]
            top_idx = np.argpartition(step_lp, -beam_size)[-beam_size:]
            for idx in top_idx:
                idx = int(idx)
                candidates.append({
                    'tokens': beam['tokens'] + [idx],
                    'score': beam['score'] + float(step_lp[idx]),  # sum log-probs
                    'finished': idx == EOS_IDX,
                })

        candidates.sort(key=lambda b: b['score'], reverse=True)
        beams = candidates[:beam_size]

        n_finished = sum(1 for b in beams if b['finished'])
        if all(b['finished'] for b in beams) or (step >= 10 and n_finished >= 3):
            break

    return [(b['tokens'], b['score']) for b in beams]


# ── corpus loaders ──────────────────────────────────────────────────────────────

def load_legacy_swipes(path):
    """Legacy ``{word, curve:{x,y,t}}`` JSONL (raw pixels). Yields (word, xs, ys, ts)."""
    out = []
    with open(path, 'r') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            c = obj['curve']
            out.append((obj['word'], list(c['x']), list(c['y']), list(c.get('t', []))))
    return out


def load_corpus_cache(path):
    """Gzipped ``{word, w, h, pts:[[nx,ny,t],...]}`` cache (normalized coords).

    Reconstructs raw px = nx*w, ny*h so the model-input normalization band applies
    the same way as on the legacy raw-pixel corpus. Yields (word, xs_px, ys_px, ts).
    """
    opener = gzip.open if str(path).endswith('.gz') else open
    out = []
    with opener(path, 'rt') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            w = float(obj['w'])
            h = float(obj['h'])
            xs, ys, ts = [], [], []
            for p in obj['pts']:
                xs.append(float(p[0]) * w)
                ys.append(float(p[1]) * h)
                ts.append(float(p[2]) if len(p) > 2 else 0.0)
            out.append((obj['word'], xs, ys, ts))
    return out


def load_dictionary(path):
    """Load the flat ``{word: score}`` en_enhanced.json into a lowercase word set."""
    with open(path, 'r') as f:
        data = json.load(f)
    return {k.lower() for k in data.keys()}


def len_stratum(word):
    n = len(word.replace("'", ""))
    if n <= 3:
        return '2-3'
    if n <= 6:
        return '4-6'
    return '7+'


class Tally:
    """Top-K tally over ranks (0-based; -1 = absent)."""
    __slots__ = ('n', 't1', 't3', 't5')

    def __init__(self):
        self.n = self.t1 = self.t3 = self.t5 = 0

    def add(self, rank):
        self.n += 1
        if 0 <= rank < 1:
            self.t1 += 1
        if 0 <= rank < 3:
            self.t3 += 1
        if 0 <= rank < 5:
            self.t5 += 1

    def top1(self):
        return self.t1 / self.n if self.n else 0.0

    def top3(self):
        return self.t3 / self.n if self.n else 0.0

    def top5(self):
        return self.t5 / self.n if self.n else 0.0

    def row(self):
        return f"{self.top1() * 100:5.1f}%  {self.top3() * 100:5.1f}%  {self.top5() * 100:5.1f}%"


def make_session(path, threads):
    """ONNX session with explicit thread count (silences pthread affinity warnings
    under proot) and CPU provider."""
    so = ort.SessionOptions()
    so.intra_op_num_threads = threads
    so.inter_op_num_threads = 1
    return ort.InferenceSession(str(path), sess_options=so, providers=['CPUExecutionProvider'])


def rank_of(target, words):
    """0-based rank of ``target`` in ``words`` (dedup-preserving), or -1."""
    for i, w in enumerate(words):
        if w == target:
            return i
    return -1


def dedup(words):
    """Order-preserving dedup (raw beam outputs can repeat)."""
    seen = set()
    out = []
    for w in words:
        if w and w not in seen:
            seen.add(w)
            out.append(w)
    return out


def run_head_to_head(args):
    root = Path(__file__).resolve().parents[1]
    encoder_path = root / "src/main/assets/models/swipe_encoder_android.onnx"
    decoder_path = root / "src/main/assets/models/swipe_decoder_android.onnx"
    for p in (encoder_path, decoder_path):
        if not p.exists():
            print(f"ERROR: model not found: {p}")
            return 1

    corpus_path = Path(args.corpus).expanduser()
    if not corpus_path.exists():
        print(f"ERROR: corpus not found: {corpus_path}")
        return 1
    dict_path = Path(args.dict).expanduser()
    if not dict_path.exists():
        print(f"ERROR: dictionary not found: {dict_path}")
        return 1

    print("=" * 78)
    print("NEURAL HEAD-TO-HEAD — local combined-corpus (neural held-out test set)")
    print("=" * 78)
    print(f"encoder: {encoder_path}")
    print(f"decoder: {decoder_path}")
    print(f"corpus:  {corpus_path}")
    print(f"dict:    {dict_path}")

    encoder = make_session(encoder_path, args.threads)
    decoder = make_session(decoder_path, args.threads)

    # Signature sanity — abort loudly on drift.
    enc_inputs = {i.name for i in encoder.get_inputs()}
    if 'actual_length' not in enc_inputs:
        print(f"ERROR: encoder signature drift — expected 'actual_length', got {enc_inputs}")
        return 1
    dec_inputs = {i.name for i in decoder.get_inputs()}
    if not {'memory', 'target_tokens', 'actual_src_length'} <= dec_inputs:
        print(f"ERROR: decoder signature drift — got {dec_inputs}")
        return 1

    dictionary = load_dictionary(dict_path)
    print(f"dictionary: {len(dictionary)} words")

    rows = load_corpus_cache(corpus_path)
    print(f"corpus rows: {len(rows)}")

    in_dict = [r for r in rows if r[0] in dictionary]
    oov = len(rows) - len(in_dict)
    coverage = len(in_dict) / len(rows) if rows else 0.0
    print(f"in-dict: {len(in_dict)}/{len(rows)} = {coverage * 100:.1f}%  (OOV={oov})")

    if args.limit:
        in_dict = in_dict[:args.limit]
        print(f"LIMIT applied: decoding first {len(in_dict)} in-dict traces")

    feat_mode = "velocity+accel (time-normalized)" if args.velocity else "position-only (velocity/accel ZEROED)"
    print(f"features: {feat_mode}   beam={args.beam}   max_len={args.max_len}")
    print("-" * 78)

    raw = Tally()
    filt = Tally()
    raw_by_len = {'2-3': Tally(), '4-6': Tally(), '7+': Tally()}
    filt_by_len = {'2-3': Tally(), '4-6': Tally(), '7+': Tally()}

    out_f = None
    if args.out:
        out_f = open(Path(args.out).expanduser(), 'w')

    t0 = time.time()
    errors = 0
    for i, (word, xs, ys, ts) in enumerate(in_dict):
        try:
            traj, keys = extract_features(xs, ys, ts if args.velocity else None, args.velocity)
            traj_t, keys_t, alen_t = create_tensors(traj, keys)
            actual_length = int(alen_t[0])

            memory = encoder.run(
                None,
                {'trajectory_features': traj_t, 'nearest_keys': keys_t, 'actual_length': alen_t},
            )[0]

            beams = run_beam_search(decoder, memory, actual_length, args.beam, args.max_len)
            raw_words = dedup([decode_prediction(seq) for seq, _ in beams])
            filt_words = [w for w in raw_words if w in dictionary]

            r_raw = rank_of(word, raw_words)
            r_filt = rank_of(word, filt_words)
            stratum = len_stratum(word)

            raw.add(r_raw)
            filt.add(r_filt)
            raw_by_len[stratum].add(r_raw)
            filt_by_len[stratum].add(r_filt)

            if out_f is not None:
                out_f.write(json.dumps({
                    'word': word, 'len_stratum': stratum,
                    'raw_top5': raw_words[:5], 'raw_rank': r_raw,
                    'filt_top5': filt_words[:5], 'filt_rank': r_filt,
                }) + "\n")
        except Exception as e:  # noqa: BLE001 — report, keep going
            errors += 1
            raw.add(-1)
            filt.add(-1)
            raw_by_len[len_stratum(word)].add(-1)
            filt_by_len[len_stratum(word)].add(-1)
            if errors <= 5:
                print(f"  [ERROR] '{word}': {e}")

        if (i + 1) % 500 == 0:
            elapsed = time.time() - t0
            rate = (i + 1) / elapsed
            eta = (len(in_dict) - (i + 1)) / rate if rate else 0
            print(f"  [{i + 1:5d}/{len(in_dict)}] "
                  f"raw t1={raw.top1() * 100:.1f}% filt t1={filt.top1() * 100:.1f}% "
                  f"| {rate:.1f} tr/s ETA {eta / 60:.1f}m")

    if out_f is not None:
        out_f.close()

    elapsed = time.time() - t0
    print("-" * 78)
    print(f"decoded {raw.n} traces in {elapsed / 60:.1f} min  ({errors} errors)")
    print()
    print(f"features: {feat_mode}   beam={args.beam}")
    print("                 top-1    top-3    top-5")
    print(f"  RAW (any string) {raw.row()}")
    print(f"  DICT-FILTERED    {filt.row()}")
    print()
    print("by length stratum (top-1 / top-3 / top-5):")
    print("  stratum    RAW                     DICT-FILTERED")
    for s in ('2-3', '4-6', '7+'):
        rb = raw_by_len[s]
        fb = filt_by_len[s]
        print(f"  {s:<4} n={rb.n:<5} {rb.row()}    {fb.row()}")
    print("=" * 78)
    return 0


def run_legacy_smoke(args):
    root = Path(__file__).resolve().parents[1]
    encoder_path = root / "src/main/assets/models/swipe_encoder_android.onnx"
    decoder_path = root / "src/main/assets/models/swipe_decoder_android.onnx"
    swipes_path = root / "swype-model-training/swipes.jsonl"
    for p, label in ((encoder_path, 'encoder'), (decoder_path, 'decoder'), (swipes_path, 'test data')):
        if not p.exists():
            print(f"ERROR: {label} not found at {p}")
            return 1

    encoder = make_session(encoder_path, args.threads)
    decoder = make_session(decoder_path, args.threads)
    print("Encoder inputs:", [i.name for i in encoder.get_inputs()])
    if 'actual_length' not in {i.name for i in encoder.get_inputs()}:
        print("VALIDATION FAILED: expected Android model with actual_length input")
        return 1
    print("VALIDATION PASSED: Android model architecture (actual_length)")

    swipes = load_legacy_swipes(swipes_path)
    limit = min(args.limit or 100, len(swipes))
    print(f"Running {limit} legacy smoke samples")
    tally = Tally()
    for i, (word, xs, ys, ts) in enumerate(swipes[:limit]):
        try:
            traj, keys = extract_features(xs, ys, ts if args.velocity else None, args.velocity)
            traj_t, keys_t, alen_t = create_tensors(traj, keys)
            memory = encoder.run(
                None,
                {'trajectory_features': traj_t, 'nearest_keys': keys_t, 'actual_length': alen_t},
            )[0]
            beams = run_beam_search(decoder, memory, int(alen_t[0]), args.beam, args.max_len)
            words = dedup([decode_prediction(seq) for seq, _ in beams])
            tally.add(rank_of(word, words))
        except Exception as e:  # noqa: BLE001
            print(f"  [{i + 1}] '{word}' ERROR: {e}")
            tally.add(-1)
    print(f"Top-1/3/5: {tally.row()}  (n={tally.n})")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--corpus', help='gzipped {word,w,h,pts} cache — enables head-to-head mode')
    ap.add_argument('--dict', default='src/main/assets/dictionaries/en_enhanced.json',
                    help='flat {word:score} dictionary for the in-dict filter')
    ap.add_argument('--beam', type=int, default=BEAM_WIDTH, help='beam width (default 8)')
    ap.add_argument('--max-len', type=int, default=DECODER_SEQ_LENGTH, dest='max_len',
                    help='max decode length (capped at 19 internally)')
    ap.add_argument('--velocity', action='store_true',
                    help='use velocity/accel features (reconfirm the corrupt-timestamp degradation)')
    ap.add_argument('--limit', type=int, default=0, help='decode only first N in-dict traces (0=all)')
    ap.add_argument('--threads', type=int, default=4, help='ORT intra-op threads')
    ap.add_argument('--out', help='write per-trace results jsonl (LOCAL-ONLY artifact)')
    args = ap.parse_args()

    if args.corpus:
        return run_head_to_head(args)
    return run_legacy_smoke(args)


if __name__ == "__main__":
    sys.exit(main())
