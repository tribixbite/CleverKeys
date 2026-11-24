#!/usr/bin/env python3
"""Write GeoRealCorpusReplayTest-format corpora whose traces are what the GEOMETRIC engine
receives in the app: the recognizer's smoothed path + its timestamps (no lift sample;
InputCoordinator.performGeometricSwipeTyping decodes `swipePath`, Keyboard2View.kt:1056),
pre-processed at the S25U pixel scale by swipe_settings_replay.replay() under one settings config.

Each output lands at <out_dir>/futo_swipe1_test_sample.jsonl.gz, the fixed file name the test
reads from $CLEVERKEYS_TEST_CACHE (GeoRealCorpusReplayTest.kt:63-68), with the source row's
word, w, h kept so the test's in-dictionary filter, aspect buckets and px scaling are unchanged.
"""
from __future__ import annotations

import gzip
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import swipe_settings_replay as R  # noqa: E402

SRC = "/data/data/com.termux/files/home/.cache/cleverkeys-test/futo_swipe1_test_sample.jsonl.gz"


def main(cfg_name: str, out_dir: str) -> None:
    cfg = R.CFGS[cfg_name]
    Path(out_dir).mkdir(parents=True, exist_ok=True)
    n = 0
    with gzip.open(SRC, "rt") as fi, gzip.open(Path(out_dir) / "futo_swipe1_test_sample.jsonl.gz", "wt") as fo:
        for line in fi:
            if not line.strip():
                continue
            r = json.loads(line)
            st, _, _ = R.replay(r["pts"], cfg)
            # Fixed-point text: the test's point regex `-?[0-9.eE]+` cannot read `1e-06`.
            pts = ",".join(f"[{x / R.KB_W:.6f},{y / R.LETTER_H:.6f},{t}]" for (x, y), t in zip(st.smooth, st.times))
            head = json.dumps({"word": r["word"], "w": r["w"], "h": r["h"]})[:-1]
            fo.write(f'{head}, "pts": [{pts}]}}\n')
            n += 1
    print(f"{cfg.name}: {n} rows -> {out_dir}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
