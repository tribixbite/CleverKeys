#!/usr/bin/env python3
"""
FUTO swipe-decoder eval — run FUTO's OWN encoder (`honorable_sturgeon`) on our
held-out test set and measure top-1/3/5 word accuracy, as a third baseline vs
our shipped ONNX neural + geometric SHARK2 engines.

MODEL PROVENANCE
  HF repo   : futo-org/futo-swipe (rev main, git_commit 86b375fbc0ad...)
  Encoder   : honorable_sturgeon/model_fp32.pte (635K params, XNNPACK-delegated)
  Paper     : arXiv 2606.25247 "FUTO Swipe: Layout-Agnostic Neural Swipe Decoding"
  Ref C++   : gitlab.futo.org/keyboard/swipe-library (resampler.cpp, beam_search.cpp)

RUNTIME
  The .pte is ExecuTorch (XNNPACK delegate); it is NOT runnable on native Termux
  (bionic, no executorch wheel). This script runs INSIDE proot-distro Ubuntu 24.04
  (glibc 2.39) where `pip install executorch==1.2.0` (manylinux_2_28_aarch64) works.

FEATURIZATION (exact port of swipe-library/src/resampler.cpp + README)
  Input pts are normalized [0,1] over the QWERTY letter-area (our test data already
  is). Two-stage resample:
    (1) resample_to_60hz: uniform linspace(0,dur_ms, n60) with n60=max(2,round(dur/16.667)+1)
    (2) resample to fixed T=64 via index-uniform linear interp; clip [0,1].
  Encoder input: features [1,2,64] (x row then y row), layout_keys [1,64,2],
  layout_mask [1,64]. Only (x,y) — NO velocity/time channels.

ENCODER OUTPUT
  log_emissions [1, 32, 65] : log-probs over K_max=64 keys + CTC blank (index 64).
  Only the 26 real QWERTY key slots are valid (mask true); pad slots are -inf.
  Class index i (0..25) -> letter = layout letters[i] (alphabetical a..z).

DECODING (two methods, both reported)
  greedy : collapse per-timestep argmax over the 27 active classes (26 letters+blank),
           dropping repeats and blanks (README greedy_ctc). Pure model, no lexicon.
  beam   : trie-constrained CTC prefix beam search over a lexicon with log-freqs,
           final score = ctc/L^gamma + beta*len + lambda*log_freq  (beam_search.cpp:446),
           scoring (gamma,lambda,beta) from scoring.json encoder-only optimum.

USAGE (run inside proot; see scripts/futo_decoder_eval.py driver comments)
  python futo_decoder_eval.py \
     --encoder <dir>/honorable_sturgeon/model_fp32.pte \
     --layout  <swipe-library>/models/layouts/en_qwerty.json \
     --vocab   <dir>/en_wordlist.combined \
     --test    <test_hwsfuto.jsonl> \
     --out     <futo_decoder_test2400.jsonl> \
     --beam-width 100 --top-k 8 --threads 2 [--limit N] [--skip N] [--method both]
"""

from __future__ import annotations

import argparse
import bisect
import heapq
import json
import math
import sys
import time
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np

RESAMPLE_LENGTH = 64          # T — fixed encoder input length (resampler.hpp)
HZ_INTERVAL_MS = 1000.0 / 60.0
NEG_INF = float("-inf")

# scoring.json  "encoder:honorable_sturgeon"  (encoder-only optimum, EN val tuned)
DEFAULT_GAMMA = 0.4056        # length-norm exponent  (final_score /= L**gamma)
DEFAULT_LAMBDA = 0.0176       # frequency-bonus weight (lambda * log_freq)
DEFAULT_BETA = 0.9866         # per-char length bonus  (beta * word_len)
DEFAULT_GAMMA_PRUNE = 0.4234
DEFAULT_BETA_PRUNE = 1.0382


# ── featurization (exact port of resampler.cpp) ─────────────────────────────────

def _lerp(a: float, b: float, t: float) -> float:
    return a + t * (b - a)


def resample_to_60hz(x: Sequence[float], y: Sequence[float], t: Sequence[float]
                     ) -> Tuple[List[float], List[float]]:
    """Port of resampler.cpp::resample_to_60hz — uniform linspace over [0,duration]."""
    n = len(x)
    if n == 0:
        return [], []
    if n == 1:
        return [x[0], x[0]], [y[0], y[0]]
    eps = 1e-12
    t0 = t[0]
    duration = t[n - 1] - t0
    if duration <= eps:
        return [x[0], x[n - 1]], [y[0], y[n - 1]]
    num_output = max(2, int(round(duration / HZ_INTERVAL_MS)) + 1)
    step = duration / (num_output - 1)
    out_x = [0.0] * num_output
    out_y = [0.0] * num_output
    for i in range(num_output):
        target_t = t0 + i * step
        if target_t > t[n - 1]:
            target_t = t[n - 1]
        idx1 = bisect.bisect_left(t, target_t)
        if idx1 == 0:
            out_x[i], out_y[i] = x[0], y[0]
        elif idx1 >= n:
            out_x[i], out_y[i] = x[n - 1], y[n - 1]
        else:
            idx0 = idx1 - 1
            seg = t[idx1] - t[idx0]
            frac = (target_t - t[idx0]) / seg if seg > eps else 0.0
            out_x[i] = _lerp(x[idx0], x[idx1], frac)
            out_y[i] = _lerp(y[idx0], y[idx1], frac)
    return out_x, out_y


def resample_fixed(x: Sequence[float], y: Sequence[float], length: int = RESAMPLE_LENGTH
                   ) -> Tuple[List[float], List[float]]:
    """Index-uniform resample to `length` points + clip [0,1] (resampler.cpp::resample_path,
    index-based branch — used because resample_to_60hz already made samples time-uniform)."""
    n = len(x)
    out_x = [0.0] * length
    out_y = [0.0] * length
    if n == 0:
        return out_x, out_y
    if n == 1:
        return ([min(1.0, max(0.0, x[0]))] * length,
                [min(1.0, max(0.0, y[0]))] * length)
    for i in range(length):
        frac_pos = i / (length - 1)
        idx = frac_pos * (n - 1)
        idx0 = int(idx)
        idx1 = min(idx0 + 1, n - 1)
        frac = idx - idx0
        out_x[i] = min(1.0, max(0.0, _lerp(x[idx0], x[idx1], frac)))
        out_y[i] = min(1.0, max(0.0, _lerp(y[idx0], y[idx1], frac)))
    return out_x, out_y


def featurize(px: Sequence[float], py: Sequence[float], pt: Sequence[float]) -> np.ndarray:
    """Full featurization -> [2, 64] float32 (x row, then y row). Matches README resample()."""
    hx, hy = resample_to_60hz(px, py, pt)
    rx, ry = resample_fixed(hx, hy, RESAMPLE_LENGTH)
    return np.stack([np.asarray(rx, np.float32), np.asarray(ry, np.float32)], axis=0)


# ── layout ──────────────────────────────────────────────────────────────────────

def load_layout(path: Path) -> Tuple[List[str], np.ndarray]:
    """Load en_qwerty.json -> (letters_in_key_order, key_centers[K,2] float32).

    The C++ builds layout_keys from the JSON `keys` array order. `letters` field is
    the alphabet; class index i in log_emissions maps to letters[i]."""
    obj = json.loads(Path(path).read_text())
    letters = list(obj["letters"])
    centers = []
    order = []
    for k in obj["keys"]:
        order.append(k["letter"])
        centers.append((float(k["cx"]), float(k["cy"])))
    # The emissions class order follows the KEY array order (the encoder reads keys as
    # supplied). letters (alphabet) == key order here (both a..z), but map explicitly.
    return order, np.asarray(centers, np.float32)


# ── vocabulary trie with log-frequencies (port of trie.cpp semantics) ───────────

class TrieNode:
    __slots__ = ("children", "is_word", "log_freq")

    def __init__(self) -> None:
        self.children: Dict[str, "TrieNode"] = {}
        self.is_word = False
        self.log_freq = 0.0


class LexTrie:
    """Prefix trie over lowercase a-z words with per-word log-frequency.

    log_freq = log(freq + 1e-10)  (trie.cpp:374, log_freq_direct=false path).
    """

    def __init__(self) -> None:
        self.root = TrieNode()
        self.num_words = 0

    def insert(self, word: str, freq: float) -> None:
        node = self.root
        for ch in word:
            nxt = node.children.get(ch)
            if nxt is None:
                nxt = TrieNode()
                node.children[ch] = nxt
            node = nxt
        if not node.is_word:
            self.num_words += 1
        node.is_word = True
        lf = math.log(freq + 1e-10)
        if lf > node.log_freq or node.log_freq == 0.0:
            node.log_freq = lf

    def contains(self, word: str) -> bool:
        node = self.root
        for ch in word:
            node = node.children.get(ch)
            if node is None:
                return False
        return node.is_word


def load_combined_vocab(path: Path) -> LexTrie:
    """Parse AOSP-format `word=<w>,f=<freq>,...` combined wordlist into a LexTrie.

    Only pure lowercase a-z surface forms are kept (swipeable), matching
    generate_eval_data.load_vocab_combined + normalize_word (a-z only)."""
    trie = LexTrie()
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line.startswith("word="):
                continue
            word = None
            freq = 1.0
            for field in line.split(","):
                kv = field.split("=", 1)
                if len(kv) != 2:
                    continue
                key, val = kv
                if key == "word":
                    word = val
                elif key == "f":
                    try:
                        freq = float(val)
                    except ValueError:
                        pass
            if not word:
                continue
            wl = word.lower()
            if wl and all("a" <= c <= "z" for c in wl):
                trie.insert(wl, freq if freq > 0 else 1.0)
    return trie


def load_flat_json_vocab(path: Path) -> LexTrie:
    """Parse our flat en_enhanced.json {word:int_freq} into a LexTrie (same-lexicon mode)."""
    trie = LexTrie()
    data = json.loads(Path(path).read_text())
    for word, freq in data.items():
        wl = str(word).lower()
        if wl and all("a" <= c <= "z" for c in wl):
            trie.insert(wl, float(freq) if float(freq) > 0 else 1.0)
    return trie


# ── CTC decoding ────────────────────────────────────────────────────────────────

def greedy_ctc(log_emissions: np.ndarray, letters: List[str], blank_idx: int) -> str:
    """Collapse per-timestep argmax (README greedy_ctc). log_emissions [T, C]."""
    out = []
    prev = -1
    argmax = log_emissions.argmax(axis=-1)
    n_letters = len(letters)
    for c in argmax:
        c = int(c)
        if c != prev and c != blank_idx and c < n_letters:
            out.append(letters[c])
        prev = c
    return "".join(out)


def trie_beam_search(
    log_emissions: np.ndarray,
    letters: List[str],
    blank_idx: int,
    trie: LexTrie,
    beam_width: int,
    top_k: int,
    gamma: float,
    lambda_: float,
    beta: float,
) -> List[Tuple[str, float]]:
    """Trie-constrained CTC prefix beam search.

    Standard token-passing CTC prefix beam (Graves): each hypothesis tracks
    (p_blank, p_nonblank) log-prob of the label prefix ending / not-ending in blank,
    constrained so the prefix is always a valid trie path. At the end, every
    hypothesis whose prefix is a complete word is scored with FUTO's final formula
    (beam_search.cpp:446):

        final = ctc / max(len,1)**gamma  +  beta * len  +  lambda * log_freq

    where ctc = logaddexp(p_blank, p_nonblank) (total prefix log-prob). Returns the
    top_k (word, final_score) best-first.

    NOTE: This is a faithful re-implementation of the *scoring* used by FUTO's
    library. FUTO's C++ beam (run_beam_search) is a heavily optimized single-stream
    token-passing variant with the same trie constraint and the same final scoring;
    this Python port uses the textbook prefix-beam recurrence with identical trie
    masking and identical final scoring. Small ranking differences vs the C++ beam
    are possible from beam-pruning order, but greedy-CTC cross-check bounds the risk.
    """
    T, C = log_emissions.shape
    n_letters = len(letters)

    def logaddexp(a: float, b: float) -> float:
        if a == NEG_INF:
            return b
        if b == NEG_INF:
            return a
        m = a if a > b else b
        return m + math.log(math.exp(a - m) + math.exp(b - m))

    # Beam entry keyed by prefix string -> (p_blank, p_nonblank, trie_node)
    # Start: empty prefix at root, all mass in "blank" channel (log-prob 0).
    Beam = Dict[str, Tuple[float, float, TrieNode]]
    beam: Beam = {"": (0.0, NEG_INF, trie.root)}

    for t in range(T):
        row = log_emissions[t]
        next_beam: Beam = {}

        def add(prefix: str, node: TrieNode, pb: float, pnb: float) -> None:
            cur = next_beam.get(prefix)
            if cur is None:
                next_beam[prefix] = (pb, pnb, node)
            else:
                next_beam[prefix] = (logaddexp(cur[0], pb),
                                     logaddexp(cur[1], pnb),
                                     node)

        blank_lp = float(row[blank_idx])
        for prefix, (pb, pnb, node) in beam.items():
            total = logaddexp(pb, pnb)
            # 1) emit blank -> prefix unchanged, mass moves to p_blank channel
            add(prefix, node, total + blank_lp, NEG_INF)
            # 2) repeat last char (only extends p_nonblank from p_nonblank, no new char)
            if prefix:
                last = prefix[-1]
                ci = letters.index(last) if last in letters else -1
                if 0 <= ci < n_letters:
                    add(prefix, node, NEG_INF, pnb + float(row[ci]))
            # 3) extend by each allowed next char (trie-constrained)
            for ch, child in node.children.items():
                ci = letters.index(ch)
                emit_lp = float(row[ci])
                if prefix and prefix[-1] == ch:
                    # new char equals last: can only come from the p_blank channel
                    contrib = pb + emit_lp
                else:
                    contrib = total + emit_lp
                add(prefix + ch, child, NEG_INF, contrib)

        # prune to beam_width by total prefix prob
        if len(next_beam) > beam_width:
            items = sorted(next_beam.items(),
                           key=lambda kv: logaddexp(kv[1][0], kv[1][1]),
                           reverse=True)[:beam_width]
            beam = dict(items)
        else:
            beam = next_beam

    # Final scoring over complete words in the beam.
    results: List[Tuple[str, float]] = []
    for prefix, (pb, pnb, node) in beam.items():
        if not node.is_word or not prefix:
            continue
        ctc = logaddexp(pb, pnb)
        L = max(len(prefix), 1)
        final = ctc / (L ** gamma) + beta * len(prefix) + lambda_ * node.log_freq
        results.append((prefix, final))

    results.sort(key=lambda wf: wf[1], reverse=True)
    # dedup (prefixes are already unique), cap top_k
    return results[:top_k]


# ── corpus ──────────────────────────────────────────────────────────────────────

def load_test(path: Path) -> List[Tuple[str, List[float], List[float], List[float]]]:
    """Load test_hwsfuto.jsonl rows {word, points:[{t,x,y}]} -> (word, xs, ys, ts)."""
    rows = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            pts = obj["points"]
            xs = [float(p["x"]) for p in pts]
            ys = [float(p["y"]) for p in pts]
            ts = [float(p["t"]) for p in pts]
            rows.append((obj["word"], xs, ys, ts))
    return rows


def len_stratum(word: str) -> str:
    return "<=3" if len(word) <= 3 else "4+"


class Tally:
    __slots__ = ("n", "t1", "t3", "t5")

    def __init__(self) -> None:
        self.n = self.t1 = self.t3 = self.t5 = 0

    def add(self, rank: int) -> None:
        self.n += 1
        if 0 <= rank < 1:
            self.t1 += 1
        if 0 <= rank < 3:
            self.t3 += 1
        if 0 <= rank < 5:
            self.t5 += 1

    def row(self) -> str:
        if not self.n:
            return "   n/a"
        return (f"{self.t1 / self.n * 100:5.2f}%  {self.t3 / self.n * 100:5.2f}%  "
                f"{self.t5 / self.n * 100:5.2f}%")


def rank_of(target: str, words: List[str]) -> int:
    for i, w in enumerate(words):
        if w == target:
            return i
    return -1


# ── encoder (ExecuTorch) ────────────────────────────────────────────────────────

class Encoder:
    """Wraps FUTO's honorable_sturgeon .pte via the ExecuTorch runtime."""

    def __init__(self, pte_path: str, num_threads: int) -> None:
        import torch  # noqa: F401  (executorch runtime needs torch tensors)
        from executorch.runtime import Runtime
        self._torch = __import__("torch")
        self._rt = Runtime.get()
        self._method = self._rt.load_program(pte_path).load_method("forward")

    def forward(self, features: np.ndarray, keys: np.ndarray, mask: np.ndarray) -> np.ndarray:
        """features[2,64], keys[K,2], mask[K] -> log_emissions[T', C] (float32).

        Pads keys/mask to MAX_KEYS=64 (export-time bound)."""
        torch = self._torch
        max_keys = 64
        k = keys.shape[0]
        keys_pad = np.zeros((max_keys, 2), np.float32)
        keys_pad[:k] = keys
        mask_pad = np.zeros((max_keys,), bool)
        mask_pad[:k] = mask
        f = torch.from_numpy(features[None].copy())              # [1,2,64]
        kt = torch.from_numpy(keys_pad[None].copy())             # [1,64,2]
        mt = torch.from_numpy(mask_pad[None].copy())             # [1,64]
        out = self._method.execute((f, kt, mt))
        log_emissions = out[0].numpy()[0]                        # [T', 65]
        return log_emissions


def build_active_class_view(log_emissions: np.ndarray, num_letters: int, max_keys: int
                            ) -> Tuple[np.ndarray, int]:
    """Slice the [T', 65] emissions to the active classes [T', num_letters+1] where the
    last column is the blank (class index max_keys in the full head). Pad key slots
    (num_letters..max_keys-1) are -inf and dropped."""
    T = log_emissions.shape[0]
    view = np.empty((T, num_letters + 1), np.float32)
    view[:, :num_letters] = log_emissions[:, :num_letters]
    view[:, num_letters] = log_emissions[:, max_keys]  # blank column
    return view, num_letters  # blank index in the view = num_letters


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--encoder", required=True, help="honorable_sturgeon/model_fp32.pte")
    ap.add_argument("--layout", required=True, help="en_qwerty.json")
    ap.add_argument("--vocab", required=True, help="combined wordlist OR flat json (--vocab-json)")
    ap.add_argument("--vocab-json", action="store_true",
                    help="treat --vocab as our flat en_enhanced.json {word:freq}")
    ap.add_argument("--test", required=True, help="test_hwsfuto.jsonl")
    ap.add_argument("--out", required=True, help="per-trace predictions jsonl")
    ap.add_argument("--beam-width", type=int, default=100, dest="beam_width")
    ap.add_argument("--top-k", type=int, default=8, dest="top_k")
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--skip", type=int, default=0)
    ap.add_argument("--method", choices=["greedy", "beam", "both"], default="both")
    ap.add_argument("--gamma", type=float, default=DEFAULT_GAMMA)
    ap.add_argument("--lambda", type=float, default=DEFAULT_LAMBDA, dest="lambda_")
    ap.add_argument("--beta", type=float, default=DEFAULT_BETA)
    ap.add_argument("--progress", type=int, default=100)
    args = ap.parse_args()

    # thread cap (be a good CPU citizen — heavy contention on device)
    import os
    os.environ.setdefault("OMP_NUM_THREADS", str(args.threads))

    print(f"[load] layout {args.layout}", flush=True)
    letters, key_centers = load_layout(Path(args.layout))
    num_letters = len(letters)
    max_keys = 64

    print(f"[load] vocab {args.vocab} ({'flat-json' if args.vocab_json else 'combined'})",
          flush=True)
    t0 = time.time()
    trie = (load_flat_json_vocab(Path(args.vocab)) if args.vocab_json
            else load_combined_vocab(Path(args.vocab)))
    print(f"[load] trie: {trie.num_words} words in {time.time() - t0:.1f}s", flush=True)

    print(f"[load] encoder {args.encoder}", flush=True)
    enc = Encoder(args.encoder, args.threads)

    print(f"[load] test {args.test}", flush=True)
    rows = load_test(Path(args.test))
    print(f"[load] {len(rows)} test rows", flush=True)

    mask = np.ones((num_letters,), bool)

    # resume support: count already-written lines
    out_path = Path(args.out)
    done = 0
    if args.skip:
        done = args.skip
    elif out_path.exists():
        with open(out_path) as f:
            done = sum(1 for _ in f)
        print(f"[resume] {done} already done", flush=True)

    end = len(rows) if not args.limit else min(len(rows), done + args.limit)

    out_f = open(out_path, "a")
    g_tal = Tally()
    b_tal = Tally()
    g_strat = {"<=3": Tally(), "4+": Tally()}
    b_strat = {"<=3": Tally(), "4+": Tally()}
    n_in_vocab = 0
    tstart = time.time()

    for idx in range(done, end):
        word, xs, ys, ts = rows[idx]
        target = word.lower()
        in_vocab = trie.contains(target)
        if in_vocab:
            n_in_vocab += 1
        try:
            feats = featurize(xs, ys, ts)
            emissions_full = enc.forward(feats, key_centers, mask)
            view, blank_idx = build_active_class_view(emissions_full, num_letters, max_keys)

            greedy = None
            beam_words: List[str] = []
            if args.method in ("greedy", "both"):
                greedy = greedy_ctc(view, letters, blank_idx)
            if args.method in ("beam", "both"):
                beam = trie_beam_search(view, letters, blank_idx, trie,
                                        args.beam_width, args.top_k,
                                        args.gamma, args.lambda_, args.beta)
                beam_words = [w for w, _ in beam]

            rec = {"idx": idx, "word": word, "in_vocab": in_vocab}
            if greedy is not None:
                rec["greedy"] = greedy
                g_rank = 0 if greedy == target else -1
                g_tal.add(g_rank)
                g_strat[len_stratum(target)].add(g_rank)
            if beam_words:
                rec["preds"] = beam_words
                b_rank = rank_of(target, beam_words)
                b_tal.add(b_rank)
                b_strat[len_stratum(target)].add(b_rank)
            out_f.write(json.dumps(rec) + "\n")
        except Exception as e:  # noqa: BLE001
            out_f.write(json.dumps({"idx": idx, "word": word, "error": repr(e)[:200]}) + "\n")
            g_tal.add(-1)
            b_tal.add(-1)
            g_strat[len_stratum(target)].add(-1)
            b_strat[len_stratum(target)].add(-1)
            if (idx - done) < 5:
                print(f"  [ERROR] '{word}': {e}", flush=True)

        if (idx - done + 1) % args.progress == 0:
            out_f.flush()
            el = time.time() - tstart
            rate = (idx - done + 1) / el
            eta = (end - idx - 1) / rate if rate else 0
            print(f"  [{idx + 1}/{end}] greedy_t1={g_tal.t1}/{g_tal.n} "
                  f"beam {b_tal.row()} | {rate:.1f} tr/s ETA {eta / 60:.1f}m", flush=True)

    out_f.close()
    print("=" * 70, flush=True)
    print(f"evaluated {b_tal.n} traces  ({n_in_vocab} in-vocab of the eval range)", flush=True)
    print(f"gamma={args.gamma} lambda={args.lambda_} beta={args.beta} "
          f"beam={args.beam_width} vocab_words={trie.num_words}", flush=True)
    print("                     top-1    top-3    top-5", flush=True)
    print(f"  GREEDY (t1 only)   {g_tal.t1 / g_tal.n * 100:5.2f}%" if g_tal.n else "", flush=True)
    print(f"  BEAM               {b_tal.row()}", flush=True)
    print("by length stratum (beam, top-1/3/5):", flush=True)
    for s in ("<=3", "4+"):
        print(f"  {s:<4} n={b_strat[s].n:<5} {b_strat[s].row()}", flush=True)
    print("=" * 70, flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
