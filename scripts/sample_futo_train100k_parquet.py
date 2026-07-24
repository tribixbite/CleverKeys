#!/usr/bin/env python3
"""sample_futo_train100k_parquet.py — seeded 100k sample of FUTO swipe-1/train from
locally-downloaded parquet shards (rate-limit-immune alternative to the /rows API
sampler `fetch_futo_train100k.mjs`; same filters, same output format, same seed).

Run under an environment with pyarrow (proot ubuntu /root/ckvenv on this device):
  python3 scripts/sample_futo_train100k_parquet.py \
      --shards-dir ~/.cache/cleverkeys-test/futo_parquet \
      --out ~/.cache/cleverkeys-test/futo_train100k.jsonl.gz

Sampling: uniform WITHOUT clustering — a seeded RNG picks 100k row indices over the
full 939,550-row split (superior to the API sampler's 100-row window clustering).
Filters identical to fetch_futo_replay_sample.mjs: potentially_invalid_sentence,
>= 3 points, word lowercases to /^[a-z']+$/ (stored lowercased); rows failing a
filter are replaced by the next unused seeded index so the final kept count hits
the target. Output rows: {"word","w","h","pts":[[nx,ny,t-rel-ms],...]} SHUFFLED
(seeded) so head-N is itself a random subset.
"""
import argparse
import gzip
import json
import random
import re
from pathlib import Path

import pyarrow.parquet as pq

SEED = 20260723
WORD_RE = re.compile(r"^[a-z']+$")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--shards-dir", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--target", type=int, default=100000)
    args = ap.parse_args()

    shards = sorted(Path(args.shards_dir).expanduser().glob("*.parquet"))
    assert shards, f"no parquet shards in {args.shards_dir}"
    files = [pq.ParquetFile(s) for s in shards]
    sizes = [f.metadata.num_rows for f in files]
    total = sum(sizes)
    print(f"shards={len(shards)} rows={sizes} total={total} seed={SEED}")

    rng = random.Random(SEED)
    order = list(range(total))
    rng.shuffle(order)  # seeded permutation; consume until target kept

    # Map global row index -> (shard, row-group, offset) lazily via row-group loads.
    # For efficiency: bucket wanted indices per shard, then per row-group, and read
    # each needed row-group once (columns subset).
    cols = ["word", "canvas_width", "canvas_height", "data",
            "potentially_invalid_sentence"]

    kept_lines = []
    dropped = {"invalid_sentence": 0, "too_few_points": 0, "bad_word": 0, "bad_data": 0}
    consumed = 0

    # Pre-compute shard offsets.
    shard_base = []
    acc = 0
    for n in sizes:
        shard_base.append(acc)
        acc += n

    # We process the seeded permutation in chunks, grouping by row-group, until target.
    CHUNK = 40000
    pos = 0
    while len(kept_lines) < args.target and pos < total:
        want = order[pos:pos + CHUNK]
        pos += CHUNK
        consumed += len(want)
        # group by shard
        per_shard = {}
        for g in want:
            si = max(i for i, b in enumerate(shard_base) if b <= g)
            per_shard.setdefault(si, []).append(g - shard_base[si])
        for si, rows in per_shard.items():
            f = files[si]
            # group by row-group
            rg_offsets = []
            a = 0
            for rg in range(f.metadata.num_row_groups):
                rg_offsets.append(a)
                a += f.metadata.row_group(rg).num_rows
            per_rg = {}
            for r in rows:
                rgi = max(i for i, b in enumerate(rg_offsets) if b <= r)
                per_rg.setdefault(rgi, []).append(r - rg_offsets[rgi])
            for rgi, local in per_rg.items():
                tbl = f.read_row_group(rgi, columns=cols)
                d = tbl.to_pydict()
                for li in local:
                    if len(kept_lines) >= args.target:
                        break
                    if d["potentially_invalid_sentence"][li] is True:
                        dropped["invalid_sentence"] += 1
                        continue
                    word = d["word"][li]
                    if not isinstance(word, str):
                        dropped["bad_word"] += 1
                        continue
                    lw = word.lower()
                    if not WORD_RE.match(lw) or not lw.replace("'", ""):
                        dropped["bad_word"] += 1
                        continue
                    w = d["canvas_width"][li]
                    h = d["canvas_height"][li]
                    try:
                        w = float(w); h = float(h)
                    except (TypeError, ValueError):
                        dropped["bad_data"] += 1
                        continue
                    if not (w > 0 and h > 0):
                        dropped["bad_data"] += 1
                        continue
                    data = d["data"][li]
                    if not data or len(data) < 3:
                        dropped["too_few_points"] += 1
                        continue
                    t0 = float(data[0]["t"] or 0)
                    pts = []
                    ok = True
                    for p in data:
                        try:
                            x = float(p["x"]); y = float(p["y"]); t = float(p["t"])
                        except (TypeError, ValueError, KeyError):
                            ok = False
                            break
                        pts.append([round(x, 5), round(y, 5), max(0, round(t - t0))])
                    if not ok or len(pts) < 3:
                        dropped["bad_data"] += 1
                        continue
                    kept_lines.append(json.dumps(
                        {"word": lw, "w": w, "h": h, "pts": pts},
                        separators=(",", ":")))
        print(f"progress: consumed={consumed} kept={len(kept_lines)}")

    rng.shuffle(kept_lines)  # head-N = seeded random subset
    out = Path(args.out).expanduser()
    out.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(out, "wt", compresslevel=6) as fo:
        for line in kept_lines:
            fo.write(line + "\n")
    print(f"kept={len(kept_lines)} dropped={dropped}")
    print(f"wrote -> {out}")


if __name__ == "__main__":
    main()
