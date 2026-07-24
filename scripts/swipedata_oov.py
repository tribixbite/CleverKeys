#!/usr/bin/env python3
"""Report OOV rate + length-stratum distribution of a converted swipedata cache
against the shipped en_enhanced.json vocabulary (98,140 words)."""
import gzip
import json
import sys
from collections import Counter

DICT = "src/main/assets/dictionaries/en_enhanced.json"
CACHE = sys.argv[1] if len(sys.argv) > 1 else \
    "/data/data/com.termux/files/home/.cache/cleverkeys-test/swipedata_eval20k.jsonl.gz"

dict_words = {k.lower() for k in json.load(open(DICT)).keys()}
print("dict size:", len(dict_words))

n = 0
oov = 0
strat = Counter()
strat_oov = Counter()
with gzip.open(CACHE, "rt") as f:
    for line in f:
        o = json.loads(line)
        w = o["word"]
        n += 1
        wl = len(w.replace("'", ""))
        s = "<=3" if wl <= 3 else "4+"
        strat[s] += 1
        if w not in dict_words:
            oov += 1
            strat_oov[s] += 1

print(f"total={n} OOV={oov} ({100*oov/n:.2f}%)  in-vocab={n-oov} ({100*(n-oov)/n:.2f}%)")
for s in ("<=3", "4+"):
    if strat[s]:
        print(f"  {s}: n={strat[s]} OOV={strat_oov[s]} ({100*strat_oov[s]/strat[s]:.2f}%)")
