#!/usr/bin/env python3
"""Rewrite a CleverKeys-ML training-tier JSONL onto the app's raw-path input contract (kit 17).

Since 2026-10-07 the app feeds CTC the recognizer's UNSMOOTHED samples after the 1.26 px
noise drop, plus one lift sample at ACTION_UP (docs/eval/2026-10-07-short-word-ctc.md §5.1,
the "proposed"/`fix` row). The shipped encoder was trained on raw corpus samples. This script
applies exactly the emulation the eval used — `app_emulate(..., "fix")` from
`scripts/short_word_ctc_eval.py`, imported, not copied — to every row of a tier, so a
fine-tune sees the input distribution the app actually produces.

Hold-out safety: the canonical val/test traces are hashed with CleverKeys-ML's own
`prepare_data.trace_hash` and dropped BEFORE the rewrite. After the rewrite a row no longer
hashes like its source, so `prepare_data.py --extra-train`'s own hold-out exclusion could not
catch a leaked row any more (train_t3 carries 588 val / 145 test rows through the documented
dedup defect, RESULTS.md caveat 3). Dropping them here closes that gap.

Usage (on the RTX box, from CleverKeys-ML/ctc):
  python3 $KIT/contract_rows.py --app $APP --ctc . --workdir $WD \
      --in data/tier_t3.jsonl --out data/tier_t3_fix.jsonl
Then: python3 prepare_data.py --workdir $WD --extra-train data/tier_t3_fix.jsonl --jobs 16
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path
from typing import Set


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--app", type=Path, required=True, help="CleverKeys app checkout (for scripts/)")
    ap.add_argument("--ctc", type=Path, required=True, help="CleverKeys-ML/ctc (for prepare_data)")
    ap.add_argument("--workdir", type=Path, required=True, help="ctc-train workdir holding data/")
    ap.add_argument("--in", dest="inp", type=Path, required=True, help="tier jsonl, relative to --workdir")
    ap.add_argument("--out", type=Path, required=True, help="output jsonl, relative to --workdir")
    a = ap.parse_args()
    sys.path.insert(0, str(a.app / "scripts"))
    sys.path.insert(0, str(a.ctc.resolve()))
    from short_word_ctc_eval import app_emulate  # noqa: E402  the eval's exact emulation
    from prepare_data import collect_hashes, iter_rows, trace_hash  # noqa: E402

    wd = a.workdir
    holdout: Set[bytes] = set()
    for split in ("val", "test"):
        holdout |= collect_hashes(wd / "data" / f"{split}_hwsfuto.jsonl")
    src, dst = wd / a.inp, wd / a.out
    n = dropped_holdout = dropped_short = 0
    t0 = time.time()
    with open(dst, "w", encoding="utf-8") as fo:
        for word, xs, ys, ts in iter_rows(src):
            n += 1
            if trace_hash(word, xs, ys, ts) in holdout:
                dropped_holdout += 1
                continue
            x, y, t = app_emulate(xs, ys, ts, "fix")
            if len(x) < 2:
                dropped_short += 1
                continue
            fo.write(json.dumps({"word": word, "points": [{"t": tt - t[0], "x": xx, "y": yy}
                                                          for xx, yy, tt in zip(x, y, t)]}) + "\n")
            if n % 200_000 == 0:
                print(f"  {n} rows ({time.time() - t0:.0f}s)", flush=True)
    kept = n - dropped_holdout - dropped_short
    print(f"{src.name}: rows {n} | dropped holdout {dropped_holdout} | dropped <2 pts {dropped_short} "
          f"| kept {kept} -> {dst}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
