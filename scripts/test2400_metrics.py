#!/usr/bin/env python3
"""test2400_metrics.py — rigorous same-split head-to-head on the FUTO 2,400-row test set.

Joins the per-trace prediction caches of every swipe decoder by ORIGINAL file index
(idx 0..2399) and reports top-1/3/5 (overall + <=3 vs 4+ length strata), macro accuracy,
and the fusion union ceiling (go/no-go input).

All engines score the SAME 2,400 traces; each is scored against its own vocabulary, so an
OOV target simply counts as a miss (identical treatment across engines -> apples-to-apples).

Caches (in ~/.cache/cleverkeys-test/ unless overridden):
  - test2400_neural.jsonl        our ONNX neural: line k == idx k (positional), field
                                 `prod_top5` (trie beam-6 + rerank) + `prod_rank` (0-based
                                 full-beam rank of the target; -1 = miss).
  - test2400_geo.jsonl           our geometric SHARK2: {"idx","word","preds":[top10]}.
  - futo_decoder_test2400.jsonl  FUTO reference decoder FLOOR: {"idx","word","preds":[top8]}.
  - futo_decoder_test2400_ceiling.jsonl  FUTO CEILING (decoder+optimized beam), if present.

Usage: python3 scripts/test2400_metrics.py [--cache DIR]
"""
from __future__ import annotations

import argparse
import json
import os
from dataclasses import dataclass, field


@dataclass
class Engine:
    """Per-engine top-k ranks keyed by trace idx (rank 0 = top-1; -1 = miss/absent)."""
    name: str
    ranks: dict[int, int] = field(default_factory=dict)


def _rank_from_list(preds: list[str], target: str) -> int:
    """0-based rank of target within a predictions list, or -1 if absent."""
    for i, p in enumerate(preds):
        if p == target:
            return i
    return -1


def load_neural(path: str, indict_idxs: list[int],
                floor_words: dict[int, str]) -> tuple[dict[int, int], dict[int, str]]:
    """Map the neural harness output onto ORIGINAL trace idx.

    The --production harness SKIPS OOV traces (target not in en_enhanced), emitting one
    line per IN-DICT trace in original order. So neural line k -> indict_idxs[k]. OOV idxs
    are absent here and score as a neural miss (rank -1) in the join — correct, since a
    trie-constrained beam can never emit an OOV word.
    """
    ranks: dict[int, int] = {}
    words: dict[int, str] = {}
    seq: list[tuple[int, str]] = []  # (rank, word) in file order
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            o = json.loads(line)
            w = o["word"].lower()
            r = o.get("prod_rank")
            if r is None:  # authoritative full-beam rank; fall back to prod_top5 membership
                r = _rank_from_list([p.lower() for p in o.get("prod_top5", [])], w)
            seq.append((r, w))
    if len(seq) != len(indict_idxs):
        raise SystemExit(
            f"[FATAL] neural lines={len(seq)} != in-dict idxs={len(indict_idxs)}; "
            f"the OOV-skip mapping assumption is broken — investigate before trusting metrics.")
    for k, (r, w) in enumerate(seq):
        idx = indict_idxs[k]
        if floor_words[idx] != w:  # positional-mapping integrity guard
            raise SystemExit(
                f"[FATAL] neural word '{w}' at in-dict pos {k} != floor word "
                f"'{floor_words[idx]}' at idx {idx}; alignment drift.")
        ranks[idx] = r
        words[idx] = w
    return ranks, words


def load_idx_keyed(path: str) -> tuple[dict[int, int], dict[int, str]]:
    """{"idx","word","preds"} cache. Returns (idx->rank, idx->target-word)."""
    ranks: dict[int, int] = {}
    words: dict[int, str] = {}
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            o = json.loads(line)
            idx = int(o["idx"])
            w = o["word"].lower()
            words[idx] = w
            ranks[idx] = _rank_from_list([p.lower() for p in o.get("preds", [])], w)
    return ranks, words


def _hit(rank: int, k: int) -> bool:
    return 0 <= rank < k


def summarize(name: str, ranks: dict[int, int], idxs: list[int],
              words: dict[int, str]) -> dict:
    """top-1/3/5 overall + <=3 / 4+ strata over the given idx set."""
    def bucket(sel: list[int]) -> dict:
        n = len(sel)
        if n == 0:
            return {"n": 0, "t1": 0.0, "t3": 0.0, "t5": 0.0}
        t1 = sum(_hit(ranks.get(i, -1), 1) for i in sel)
        t3 = sum(_hit(ranks.get(i, -1), 3) for i in sel)
        t5 = sum(_hit(ranks.get(i, -1), 5) for i in sel)
        return {"n": n, "t1": 100.0 * t1 / n, "t3": 100.0 * t3 / n, "t5": 100.0 * t5 / n}

    short = [i for i in idxs if len(words[i]) <= 3]
    long = [i for i in idxs if len(words[i]) >= 4]
    return {"name": name, "overall": bucket(idxs), "short": bucket(short), "long": bucket(long)}


def macro_top1(ranks: dict[int, int], idxs: list[int], words: dict[int, str],
               min_ex: int = 5) -> tuple[float, int]:
    by_word: dict[str, list[int]] = {}
    for i in idxs:
        by_word.setdefault(words[i], []).append(i)
    accs = []
    for w, group in by_word.items():
        if len(group) >= min_ex:
            accs.append(sum(_hit(ranks.get(i, -1), 1) for i in group) / len(group))
    if not accs:
        return 0.0, 0
    return 100.0 * sum(accs) / len(accs), len(accs)


def union(rank_maps: list[dict[int, int]], idxs: list[int], k: int) -> float:
    hits = sum(any(_hit(rm.get(i, -1), k) for rm in rank_maps) for i in idxs)
    return 100.0 * hits / len(idxs) if idxs else 0.0


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default=os.path.expanduser("~/.cache/cleverkeys-test"))
    ap.add_argument("--dict", default="src/main/assets/dictionaries/en_enhanced.json",
                    help="our vocab; used to recover which traces the neural harness skipped (OOV)")
    args = ap.parse_args()
    c = args.cache

    # Floor cache carries all 2,400 targets by original idx -> the authoritative trace set.
    floor_ranks, floor_words = load_idx_keyed(os.path.join(c, "futo_decoder_test2400.jsonl"))
    geo_ranks, _ = load_idx_keyed(os.path.join(c, "test2400_geo.jsonl"))

    # In-dict idxs (original order) = the traces the neural harness actually decoded.
    our_vocab = {k.lower() for k in json.load(open(args.dict)).keys()}
    indict_idxs = [i for i in sorted(floor_words.keys()) if floor_words[i] in our_vocab]
    neural_ranks, neural_words = load_neural(
        os.path.join(c, "test2400_neural.jsonl"), indict_idxs, floor_words)
    print(f"[join] our-vocab in-dict traces={len(indict_idxs)} "
          f"(OOV to us={len(floor_words)-len(indict_idxs)})")

    ceiling_path = os.path.join(c, "futo_decoder_test2400_ceiling.jsonl")
    ceiling_ranks: dict[int, int] | None = None
    if os.path.exists(ceiling_path):
        ceiling_ranks, _ = load_idx_keyed(ceiling_path)

    # Authoritative target words + trace set = the FUTO floor cache (all 2,400 in order).
    words = floor_words
    idxs = sorted(words.keys())
    print(f"[join] trace set N={len(idxs)}  "
          f"neural={len(neural_ranks)} geo={len(geo_ranks)} floor={len(floor_ranks)}"
          f"{' ceiling=' + str(len(ceiling_ranks)) if ceiling_ranks else ''}")

    # Sanity: neural is positional; confirm its target words match the floor's per idx.
    mism = sum(1 for i in idxs if i in neural_words and neural_words[i] != words[i])
    if mism:
        print(f"[WARN] {mism} idx where neural target-word != floor target-word "
              f"(positional-alignment drift — investigate before trusting neural).")

    engines = [
        ("our-neural (beam6)", neural_ranks),
        ("our-geo (SHARK2)", geo_ranks),
        ("FUTO floor (enc-only)", floor_ranks),
    ]
    if ceiling_ranks:
        engines.append(("FUTO ceiling", ceiling_ranks))

    def row(s: dict, key: str) -> str:
        b = s[key]
        return f"{b['t1']:5.2f} / {b['t3']:5.2f} / {b['t5']:5.2f}  (n={b['n']})"

    print("\n=== top-1 / top-3 / top-5 (micro) ===")
    print(f"{'engine':24} {'overall':26} {'<=3-char':26} {'4+-char':26}")
    for name, ranks in engines:
        s = summarize(name, ranks, idxs, words)
        print(f"{name:24} {row(s,'overall'):26} {row(s,'short'):26} {row(s,'long'):26}")

    print("\n=== macro top-1 (words with >=5 examples) ===")
    for name, ranks in engines:
        m, nw = macro_top1(ranks, idxs, words)
        print(f"{name:24} {m:5.2f}%  ({nw} words)")

    # Fusion go/no-go: union headroom over the best single engine, per stratum.
    short = [i for i in idxs if len(words[i]) <= 3]
    long = [i for i in idxs if len(words[i]) >= 4]
    strata = [("overall", idxs), ("<=3-char", short), ("4+-char", long)]

    def best_single(rank_maps: list[dict[int, int]], sel: list[int], k: int) -> float:
        return max((100.0 * sum(_hit(rm.get(i, -1), k) for i in sel) / len(sel)) for rm in rank_maps) if sel else 0.0

    print("\n=== FUSION union ceiling (go/no-go: >=~2pt headroom over best single => complementarity) ===")
    fusion_sets = [("neural+geo (OUR hybrid)", [neural_ranks, geo_ranks])]
    all_maps = [neural_ranks, geo_ranks, floor_ranks] + ([ceiling_ranks] if ceiling_ranks else [])
    fusion_sets.append(("neural+geo+FUTO (all)", all_maps))
    for label, maps in fusion_sets:
        print(f"\n  {label}:")
        for sname, sel in strata:
            u1, u3 = union(maps, sel, 1), union(maps, sel, 3)
            b1 = best_single(maps, sel, 1)
            head1 = u1 - b1
            verdict = "GO" if head1 >= 2.0 else "no-go"
            print(f"    {sname:9}  union@1={u1:5.2f}  best@1={b1:5.2f}  headroom@1={head1:+5.2f} [{verdict}]"
                  f"   union@3={u3:5.2f}")


if __name__ == "__main__":
    main()
