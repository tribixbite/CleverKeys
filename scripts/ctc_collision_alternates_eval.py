"""Collision-alternate A/B (docs/specs/ctc-swipe-engine.md, "Collisions", 2026-11-18) on REAL FUTO swipe-5 human traces, scored in DISPLAY form.

before = the app until now: one canonical form per a-z surface (highest frequency wins).
after  = every canonical form of a decoded surface is offered; an alternate scores
         winner_final - lambda * (ln f_winner_node - ln f_alt), slate re-sorted and cut to top 8.
Shipped encoder, shipped CKDT preset, the app's current layout handling (all-letter frame,
single-letter alias keys folded = arm fold_all of scripts/ctc_accent_key_eval.py).
Usage: python3 scripts/ctc_collision_alternates_eval.py <futo_layout> <lang> [limit]
"""
import gzip, json, math, sys, time, unicodedata
from pathlib import Path
import numpy as np

ML = Path.home() / "git/swype/CleverKeys-ML/ctc"
sys.path.insert(0, str(ML))
from futo_decoder_ceiling import futo_viterbi_beam  # noqa: E402
from futo_decoder_eval import LexTrie, featurize  # noqa: E402
from eval_altlayout import read_ckdt_v2  # noqa: E402

APP = Path.home() / "git/swype/cleverkeys"
MODEL = APP / "src/main/assets/models/ctc_swipe_encoder.onnx"
G, LAM, BETA, GP, BP = (0.9, 2.0, 0.25, 0.25, 0.9882)
EXP = {"ß": "ss", "œ": "oe", "æ": "ae", "ø": "o"}
TOPK = 8


def project(word):
    s = unicodedata.normalize("NFD", word.lower())
    out = []
    for c in s:
        if unicodedata.category(c) == "Mn" or c in "'’-":
            continue
        if c in EXP:
            out.append(EXP[c]); continue
        if not ("a" <= c <= "z"):
            return None
        out.append(c)
    return "".join(out) or None


def lexicon(lang):
    groups = {}
    for w, rank in read_ckdt_v2(APP / f"src/main/assets/dictionaries/{lang}_enhanced.bin"):
        k = project(w)
        if k:
            groups.setdefault(k, []).append((float(max(1, 255 - rank)), w))
    # Highest frequency wins, ties keep first seen (CtcScriptProjection.projectLexicon);
    # alternates ordered by frequency descending, ties by insertion.
    forms = {k: sorted(v, key=lambda fw: -fw[0]) for k, v in groups.items()}
    t = LexTrie()
    for k, v in forms.items():
        t.insert(k, v[0][0])
    return t, forms


def slates(beam, forms):
    before = [(forms[s][0][1], sc) for s, sc in beam]
    out = []
    for order, (s, sc) in enumerate(beam):
        fw = forms[s]
        out.append((sc, 0, order, fw[0][1]))
        lw = math.log(fw[0][0] + 1e-10)
        for f, w in fw[1:]:
            out.append((sc - LAM * (lw - math.log(f + 1e-10)), 1, order, w))
    out.sort(key=lambda r: (-r[0], r[1], r[2]))
    after = [(w, sc) for sc, _, _, w in out[:TOPK]]
    return before, after


def main(layout, lang, limit):
    import onnxruntime as ort
    so = ort.SessionOptions(); so.intra_op_num_threads = 2
    sess = ort.InferenceSession(str(MODEL), so, providers=["CPUExecutionProvider"])
    lay = json.load(open(APP / f"src/test/resources/layouts/futo_{layout}.json"))
    keys = {k["letter"]: k for k in lay["keys"]}
    az = [chr(97 + i) for i in range(26)]
    extra = [c for c in keys if not ("a" <= c <= "z") and project(c) and len(project(c)) == 1]
    trie, forms = lexicon(lang)
    canon = {w for v in forms.values() for _, w in v}
    k = np.zeros((64, 2), np.float32); m = np.zeros(64, bool)
    for i, c in enumerate(az + extra):
        k[i] = (keys[c]["cx"], keys[c]["cy"]); m[i] = True
    rows = []
    with gzip.open(Path.home() / f".cache/cleverkeys-test/futo_swipe5_{layout}.jsonl.gz", "rt") as f:
        for line in f:
            o = json.loads(line)
            rows.append((o["word"], [p[0] for p in o["pts"]], [p[1] for p in o["pts"]], [p[2] for p in o["pts"]]))
    rows = rows[:limit] if limit else rows
    stat = {a: {"n": 0, "t1": 0, "t3": 0, "t8": 0} for a in ("before", "after")}
    loser = {a: {"n": 0, "t1": 0, "t3": 0, "t8": 0} for a in ("before", "after")}
    fixed3 = broken3 = fixed1 = broken1 = 0
    changed = []
    out = open(Path.cwd() / f"collision_{layout}.jsonl", "w")
    t0 = time.time()
    for idx, (word, xs, ys, ts) in enumerate(rows):
        word = word.lower()
        if word not in canon:
            continue
        s = project(word)
        is_loser = forms[s][0][1] != word
        feats = featurize(xs, ys, ts)
        full = sess.run(["log_emissions"], {"features": feats[None], "layout_keys": k[None], "layout_mask": m[None]})[0][0]
        lp = np.empty((full.shape[0], 27), np.float32)
        lp[:, :26] = full[:, :26]
        for j, c in enumerate(extra):
            b = ord(project(c)) - 97
            lp[:, b] = np.logaddexp(lp[:, b], full[:, 26 + j])
        lp[:, 26] = full[:, 64]
        beam = futo_viterbi_beam(lp, az, 26, trie, 100, TOPK, G, LAM, BETA, GP, BP)
        b, a = slates(beam, forms)
        ranks = {}
        for arm, sl in (("before", b), ("after", a)):
            words = [w for w, _ in sl]
            r = words.index(word) if word in words else -1
            ranks[arm] = r
            for st in ((stat[arm], loser[arm]) if is_loser else (stat[arm],)):
                st["n"] += 1; st["t1"] += r == 0; st["t3"] += 0 <= r < 3; st["t8"] += r >= 0
        rb, ra = ranks["before"], ranks["after"]
        in3 = lambda r: 0 <= r < 3
        fixed3 += in3(ra) and not in3(rb); broken3 += in3(rb) and not in3(ra)
        fixed1 += ra == 0 and rb != 0; broken1 += rb == 0 and ra != 0
        if rb != ra:
            changed.append((word, rb, ra, [w for w, _ in a[:4]]))
        out.write(json.dumps({"word": word, "loser": is_loser, "before": rb, "after": ra}, ensure_ascii=False) + "\n")
        if idx % 300 == 0:
            print(idx, stat["after"], f"{time.time()-t0:.0f}s", flush=True)
    for arm in ("before", "after"):
        s = stat[arm]; n = max(s["n"], 1); l = loser[arm]; ln = max(l["n"], 1)
        print(f"{layout}/{lang} {arm}: n={s['n']} t1={100*s['t1']/n:.2f} t3={100*s['t3']/n:.2f} t8={100*s['t8']/n:.2f}"
              f" | collision-loser targets n={l['n']} t1={100*l['t1']/ln:.2f} t3={100*l['t3']/ln:.2f} t8={100*l['t8']/ln:.2f}")
    print(f"top1 fixed={fixed1} broken={broken1}; top3 fixed={fixed3} broken={broken3}")
    from collections import Counter
    print("changed rows (word, before, after, after top4):")
    for c in changed[:60]:
        print("  ", c)
    better = Counter("rank improved" if c[2] >= 0 and (c[1] < 0 or c[2] < c[1]) else "rank worse" for c in changed)
    print("distinct changed words:", len({c[0] for c in changed}), dict(better))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 0)
