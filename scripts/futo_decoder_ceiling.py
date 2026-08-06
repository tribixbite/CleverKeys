#!/usr/bin/env python3
"""
FUTO swipe-decoder CEILING eval — pushes the encoder-only Python floor toward
FUTO's paper number by (1) wiring the magic_macaw DFSMN refiner decoder and
(2) porting FUTO's OPTIMIZED single-stream Viterbi trie beam (length-aware
pruning) from swipe-library/src/beam_search.cpp EXACTLY.

Builds on scripts/futo_decoder_eval.py (imports its featurization + trie + layout
loaders). Runs INSIDE proot Ubuntu venv `/root/etvenv` (executorch 1.2.0 aarch64
+ torch 2.11.0).

TWO LEVERS (measured independently for a clean decomposition):
  * Lever 2 (BEAM): config "beamB" — encoder-only emissions + FUTO Viterbi beam
    with length-aware pruning, encoder-only scoring params. Isolates the beam.
  * Lever 1 (DECODER): config "beamD" — encoder+magic_macaw refined log_probs +
    FUTO Viterbi beam, decoder-tuned scoring params. Full ceiling.

PIPELINE (exact port of engine.cpp::predict_segment + beam_search.cpp):
  encoder.forward(features[1,2,64], keys[1,64,2], mask[1,64])
    -> log_emissions[1,32,65], coefficients[1,32,64], lambda[1,32,1]
  slice emissions [32,65] -> [32,27]: dst[0:26]=emit[0:26], dst[26]=emit[64] (blank)
  IF decoder:
    decoder_input[32,92] = concat( sliced[27], coeff[64], lambda[1] ) per timestep
    log_probs[32,27] = magic_macaw.forward(decoder_input)   # REPLACES emissions
  ELSE:
    log_probs = sliced_emissions[32,27]
  beam = futo_viterbi_beam(log_probs, ...) -> top_k words

FUTO BEAM (single-stream, no time term for single swipes: delta_ts=0):
  hyp = (score, trie_node, blank_ended, prefix); dedup keyed by (node, blank_ended),
  MAX-merge on raw score (Viterbi, NOT logaddexp-sum). Three expansions per step:
    A blank : (node, be=True)  += p[blank]
    B child : (child, be=False) += p[child_char]   (move deeper in trie)
    C repeat: (node, be=False)  += p[last_char]     only if !be and node!=root
  Prune to beam_width by length-aware prune_score = score/max(d,1)^gp + bp*d.
  Final score (== floor / beam_search.cpp:446):
    ctc/max(L,1)^gamma + beta*L + lambda*log_freq
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path
from typing import Dict, List, Sequence, Tuple

import numpy as np

# Import the shared, already-validated pieces from the floor harness (same dir).
sys.path.insert(0, str(Path(__file__).resolve().parent))
from futo_decoder_eval import (  # noqa: E402
    featurize, greedy_ctc, len_stratum, load_combined_vocab, load_flat_json_vocab,
    load_layout, load_test, rank_of, LexTrie, TrieNode, Tally,
)

# scoring.json  "encoder:honorable_sturgeon"  (encoder-only optimum)
ENC_GAMMA, ENC_LAMBDA, ENC_BETA = 0.4056, 0.0176, 0.9866
ENC_GAMMA_PRUNE, ENC_BETA_PRUNE = 0.4234, 1.0382
# scoring.json  "encoder:honorable_sturgeon decoder:magic_macaw"
DEC_GAMMA, DEC_LAMBDA, DEC_BETA = 0.5949, 0.0134, 0.7271
DEC_GAMMA_PRUNE, DEC_BETA_PRUNE = 0.1902, 1.2727


# ── encoder returning all 3 heads (emissions, coefficients, lambda) ─────────────

class Encoder3:
    """FUTO honorable_sturgeon encoder — returns emissions + coeff + lambda."""

    def __init__(self, pte_path: str) -> None:
        import torch  # noqa: F401
        from executorch.runtime import Runtime
        self._torch = __import__("torch")
        self._rt = Runtime.get()
        self._method = self._rt.load_program(pte_path).load_method("forward")

    def forward(self, features: np.ndarray, keys: np.ndarray, mask: np.ndarray
                ) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
        """features[2,64], keys[K,2], mask[K]
        -> (log_emissions[T',65], coefficients[T',64], lambda[T'])."""
        torch = self._torch
        max_keys = 64
        k = keys.shape[0]
        keys_pad = np.zeros((max_keys, 2), np.float32)
        keys_pad[:k] = keys
        mask_pad = np.zeros((max_keys,), bool)
        mask_pad[:k] = mask
        f = torch.from_numpy(features[None].copy())      # [1,2,64]
        kt = torch.from_numpy(keys_pad[None].copy())     # [1,64,2]
        mt = torch.from_numpy(mask_pad[None].copy())     # [1,64]
        out = self._method.execute((f, kt, mt))
        emissions = out[0].numpy()[0]                    # [T',65]
        coeff = out[1].numpy()[0]                        # [T',64]
        lam = out[2].numpy()[0].reshape(-1)              # [T']
        return emissions, coeff, lam


class Decoder:
    """FUTO magic_macaw DFSMN refiner — [1,T',92] -> refined log_probs[T',27]."""

    def __init__(self, pte_path: str) -> None:
        import torch  # noqa: F401
        from executorch.runtime import Runtime
        self._torch = __import__("torch")
        self._rt = Runtime.get()
        self._method = self._rt.load_program(pte_path).load_method("forward")

    def forward(self, concat_features: np.ndarray) -> np.ndarray:
        """concat_features[T',92] -> log_probs[T',27]."""
        torch = self._torch
        x = torch.from_numpy(concat_features[None].copy())   # [1,T',92]
        out = self._method.execute((x,))
        return out[0].numpy()[0]                             # [T',27]


def slice_emissions(emissions: np.ndarray, num_letters: int, max_keys: int
                    ) -> np.ndarray:
    """[T',65] -> [T',num_letters+1] with blank (full-head class max_keys) moved to
    slot num_letters. Exact port of engine.cpp predict_segment slice block."""
    T = emissions.shape[0]
    Kp1 = num_letters + 1
    out = np.empty((T, Kp1), np.float32)
    out[:, :num_letters] = emissions[:, :num_letters]
    out[:, num_letters] = emissions[:, max_keys]     # blank_src=max_keys -> blank_dst=num_letters
    return out


def build_decoder_input(sliced: np.ndarray, coeff: np.ndarray, lam: np.ndarray
                        ) -> np.ndarray:
    """[T',27] + [T',64] + [T'] -> [T',92] (emit ; coeff ; lambda). engine.cpp."""
    T = sliced.shape[0]
    return np.concatenate(
        [sliced, coeff, lam.reshape(T, 1).astype(np.float32)], axis=1
    ).astype(np.float32)


# ── FUTO's single-stream Viterbi trie beam (exact port of beam_search.cpp) ──────

def futo_viterbi_beam(
    log_probs: np.ndarray,
    letters: List[str],
    blank_idx: int,
    trie: LexTrie,
    beam_width: int,
    top_k: int,
    gamma: float,
    lambda_: float,
    beta: float,
    gamma_prune: float,
    beta_prune: float,
) -> List[Tuple[str, float]]:
    """Port of TrieBeamSearch::run_beam_search + decode_with_scores_multi for the
    single-swipe (left-only) case where the time term delta_ts is 0.

    Hypothesis: (score, node, blank_ended, prefix). Deduped by (id(node),
    blank_ended) with MAX merge on raw path score (Viterbi). Pruned to beam_width
    by the length-aware prune_score. Final scoring identical to the C++ (and floor).
    """
    T = log_probs.shape[0]
    char_to_idx = {c: i for i, c in enumerate(letters)}
    root = trie.root

    lp_only = (gamma_prune == 0.0 and beta_prune == 0.0)

    def prune_score(score: float, depth: int) -> float:
        if lp_only:
            return score
        d = depth if depth > 0 else 1
        return score / (d ** gamma_prune) + beta_prune * depth

    # beam entry: [score, node, blank_ended, prefix]
    beam: List[list] = [[0.0, root, False, ""]]

    for t in range(T):
        row = log_probs[t]
        blank_lp = float(row[blank_idx])
        nxt: Dict[Tuple[int, int], list] = {}

        for score, node, be, prefix in beam:
            # A: emit blank -> stay at node, blank_ended=True
            key = (id(node), 1)
            s = score + blank_lp
            cur = nxt.get(key)
            if cur is None:
                nxt[key] = [s, node, True, prefix]
            elif cur[0] < s:
                cur[0], cur[2], cur[3] = s, True, prefix

            # B: emit char -> move to each child (new letter), blank_ended=False
            for ch, child in node.children.items():
                ci = char_to_idx[ch]
                s = score + float(row[ci])
                key = (id(child), 0)
                cur = nxt.get(key)
                if cur is None:
                    nxt[key] = [s, child, False, prefix + ch]
                elif cur[0] < s:
                    cur[0], cur[3] = s, prefix + ch

            # C: repeat last char (CTC-collapse) -> stay, only if !be and node!=root
            if (not be) and prefix:
                ci = char_to_idx[prefix[-1]]
                s = score + float(row[ci])
                key = (id(node), 0)
                cur = nxt.get(key)
                if cur is None:
                    nxt[key] = [s, node, False, prefix]
                elif cur[0] < s:
                    cur[0], cur[3] = s, prefix

        cand = list(nxt.values())
        if len(cand) > beam_width:
            cand.sort(key=lambda h: prune_score(h[0], len(h[3])), reverse=True)
            cand = cand[:beam_width]
        beam = cand
        if not beam:
            break

    # collect complete words (dedup by word, keep max final_score)
    best: Dict[str, float] = {}
    for score, node, be, prefix in beam:
        if node.is_word and prefix:
            L = max(len(prefix), 1)
            final = score / (L ** gamma) + beta * len(prefix) + lambda_ * node.log_freq
            if prefix not in best or best[prefix] < final:
                best[prefix] = final

    res = sorted(best.items(), key=lambda wf: wf[1], reverse=True)[:top_k]
    return res


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--encoder", required=True)
    ap.add_argument("--decoder", default="", help="magic_macaw .pte (empty = encoder-only)")
    ap.add_argument("--layout", required=True)
    ap.add_argument("--vocab", required=True)
    ap.add_argument("--vocab-json", action="store_true")
    ap.add_argument("--test", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--config", choices=["beamB", "beamD"], required=True,
                    help="beamB=enc-only+FUTO beam; beamD=enc+decoder+FUTO beam")
    ap.add_argument("--beam-width", type=int, default=100, dest="beam_width")
    ap.add_argument("--top-k", type=int, default=8, dest="top_k")
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--skip", type=int, default=0)
    ap.add_argument("--progress", type=int, default=100)
    # scoring overrides (default from scoring.json per config)
    ap.add_argument("--gamma", type=float, default=None)
    ap.add_argument("--lambda", type=float, default=None, dest="lambda_")
    ap.add_argument("--beta", type=float, default=None)
    ap.add_argument("--gamma-prune", type=float, default=None, dest="gamma_prune")
    ap.add_argument("--beta-prune", type=float, default=None, dest="beta_prune")
    args = ap.parse_args()

    os.environ.setdefault("OMP_NUM_THREADS", str(args.threads))
    try:
        import torch
        torch.set_num_threads(args.threads)
    except Exception:  # noqa: BLE001
        pass

    use_decoder = args.config == "beamD"
    if use_decoder and not args.decoder:
        print("ERROR: --config beamD requires --decoder", file=sys.stderr)
        return 2

    # scoring params (config default, overridable)
    if use_decoder:
        gamma = args.gamma if args.gamma is not None else DEC_GAMMA
        lambda_ = args.lambda_ if args.lambda_ is not None else DEC_LAMBDA
        beta = args.beta if args.beta is not None else DEC_BETA
        gp = args.gamma_prune if args.gamma_prune is not None else DEC_GAMMA_PRUNE
        bp = args.beta_prune if args.beta_prune is not None else DEC_BETA_PRUNE
    else:
        gamma = args.gamma if args.gamma is not None else ENC_GAMMA
        lambda_ = args.lambda_ if args.lambda_ is not None else ENC_LAMBDA
        beta = args.beta if args.beta is not None else ENC_BETA
        gp = args.gamma_prune if args.gamma_prune is not None else ENC_GAMMA_PRUNE
        bp = args.beta_prune if args.beta_prune is not None else ENC_BETA_PRUNE

    print(f"[cfg] config={args.config} decoder={'yes' if use_decoder else 'no'} "
          f"gamma={gamma} lambda={lambda_} beta={beta} "
          f"gamma_prune={gp} beta_prune={bp} beam={args.beam_width}", flush=True)

    print(f"[load] layout {args.layout}", flush=True)
    letters, key_centers = load_layout(Path(args.layout))
    num_letters = len(letters)
    max_keys = 64

    print(f"[load] vocab {args.vocab}", flush=True)
    t0 = time.time()
    trie = (load_flat_json_vocab(Path(args.vocab)) if args.vocab_json
            else load_combined_vocab(Path(args.vocab)))
    print(f"[load] trie: {trie.num_words} words in {time.time() - t0:.1f}s", flush=True)

    print(f"[load] encoder {args.encoder}", flush=True)
    enc = Encoder3(args.encoder)
    dec = None
    if use_decoder:
        print(f"[load] decoder {args.decoder}", flush=True)
        dec = Decoder(args.decoder)

    print(f"[load] test {args.test}", flush=True)
    rows = load_test(Path(args.test))
    print(f"[load] {len(rows)} test rows", flush=True)

    mask = np.ones((num_letters,), bool)

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
    b_tal = Tally()
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
            emissions, coeff, lam = enc.forward(feats, key_centers, mask)
            sliced = slice_emissions(emissions, num_letters, max_keys)  # [T',27]
            if use_decoder:
                din = build_decoder_input(sliced, coeff, lam)           # [T',92]
                log_probs = dec.forward(din)                            # [T',27]
            else:
                log_probs = sliced
            blank_idx = num_letters  # 26
            greedy = greedy_ctc(log_probs, letters, blank_idx)
            beam = futo_viterbi_beam(log_probs, letters, blank_idx, trie,
                                     args.beam_width, args.top_k,
                                     gamma, lambda_, beta, gp, bp)
            beam_words = [w for w, _ in beam]
            rec = {"idx": idx, "word": word, "in_vocab": in_vocab,
                   "greedy": greedy, "preds": beam_words}
            b_rank = rank_of(target, beam_words)
            b_tal.add(b_rank)
            b_strat[len_stratum(target)].add(b_rank)
            out_f.write(json.dumps(rec) + "\n")
        except Exception as e:  # noqa: BLE001
            out_f.write(json.dumps({"idx": idx, "word": word, "error": repr(e)[:200]}) + "\n")
            b_tal.add(-1)
            b_strat[len_stratum(target)].add(-1)
            if (idx - done) < 5:
                print(f"  [ERROR] '{word}': {e}", flush=True)

        if (idx - done + 1) % args.progress == 0:
            out_f.flush()
            el = time.time() - tstart
            rate = (idx - done + 1) / el
            eta = (end - idx - 1) / rate if rate else 0
            print(f"  [{idx + 1}/{end}] beam {b_tal.row()} | "
                  f"{rate:.1f} tr/s ETA {eta / 60:.1f}m", flush=True)

    out_f.close()
    print("=" * 70, flush=True)
    print(f"config={args.config} evaluated {b_tal.n} traces ({n_in_vocab} in-vocab)", flush=True)
    print(f"  BEAM top-1/3/5   {b_tal.row()}", flush=True)
    for s in ("<=3", "4+"):
        print(f"  {s:<4} n={b_strat[s].n:<5} {b_strat[s].row()}", flush=True)
    print("=" * 70, flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
