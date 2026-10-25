"""Alias-key fold A/B on REAL FUTO swipe-5 human traces (docs/eval/2026-10-25-ctc-accent-keys.md).

Question: on a layout whose accented letters are TOP-LEVEL keys (FUTO "german" = the app's
latn_qwertz_de: ü right of p, ö ä right of l; "spanish": ñ right of l), does giving the encoder
those keys as extra slots and FOLDING their emission columns into the base letter (ü→u) beat
what the app does today (a–z-only letter box, accented keys outside it, path clipped)?

Arms (all at the app's shipped CKDT preset: γ 0.9 λ 2.0 β 0.25 γp 0.25 βp 0.9882, beam 100):
  app_now   frame = a–z keys' bounding box (CtcEngineAdapter.buildMappedLayout today); points
            beyond it clip to [0,1]; 26 slots.
  az26_all  frame = every letter key (FUTO's own frame); 26 slots.
  fold_all  frame = every letter key; 26 + extra slots; extra column logaddexp-folded into its
            NFD base letter's column.

Usage (python3.13 with onnxruntime + numpy; needs ~/git/swype/CleverKeys-ML/ctc for the beam and
the FUTO caches ~/.cache/cleverkeys-test/futo_swipe5_<layout>.jsonl.gz):
    python3 scripts/ctc_accent_key_eval.py german de
    python3 scripts/ctc_accent_key_eval.py spanish es
Writes per-trace ranks to ctc_accent_<layout>.jsonl beside the current directory.
"""
import gzip, json, sys, time, unicodedata
from pathlib import Path
import numpy as np

ML = Path.home() / "git/swype/CleverKeys-ML/ctc"
sys.path.insert(0, str(ML))
from futo_decoder_ceiling import futo_viterbi_beam  # noqa: E402
from futo_decoder_eval import LexTrie, featurize, rank_of  # noqa: E402
from eval_altlayout import read_ckdt_v2  # noqa: E402

APP = Path.home() / "git/swype/cleverkeys"
MODEL = APP / "src/main/assets/models/ctc_swipe_encoder.onnx"
PRESET = (0.9, 2.0, 0.25, 0.25, 0.9882)
EXP = {"ß": "ss", "œ": "oe", "æ": "ae", "ø": "o"}


def project(word):
    """CtcAzProjection.project."""
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


def trie_for(lang):
    t = LexTrie()
    for w, rank in read_ckdt_v2(APP / f"src/main/assets/dictionaries/{lang}_enhanced.bin"):
        k = project(w)
        if k:
            t.insert(k, float(max(1, 255 - rank)))
    return t


def main(layout, lang, limit):
    import onnxruntime as ort
    so = ort.SessionOptions(); so.intra_op_num_threads = 2
    sess = ort.InferenceSession(str(MODEL), so, providers=["CPUExecutionProvider"])
    lay = json.load(open(APP / f"src/test/resources/layouts/futo_{layout}.json"))
    keys = {k["letter"]: k for k in lay["keys"]}
    az = [chr(97 + i) for i in range(26)]
    extra = [c for c in keys if not ("a" <= c <= "z") and project(c) and len(project(c)) == 1]
    # a–z letter box (app today) in FUTO frame units
    L = min(keys[c]["cx"] - keys[c]["rx"] for c in az); R = max(keys[c]["cx"] + keys[c]["rx"] for c in az)
    T = min(keys[c]["cy"] - keys[c]["ry"] for c in az); B = max(keys[c]["cy"] + keys[c]["ry"] for c in az)
    print(f"{layout}: extra={extra} azbox x[{L:.3f},{R:.3f}] y[{T:.3f},{B:.3f}]", flush=True)
    trie = trie_for(lang)

    def slots(frame_box, with_extra):
        k = np.zeros((64, 2), np.float32); m = np.zeros(64, bool)
        letters = az + (extra if with_extra else [])
        for i, c in enumerate(letters):
            x, y = keys[c]["cx"], keys[c]["cy"]
            if frame_box:
                x = (x - L) / (R - L); y = (y - T) / (B - T)
            k[i] = (x, y); m[i] = True
        return k, m

    arms = {
        "app_now": (True, False),
        "az26_all": (False, False),
        "fold_all": (False, True),
    }
    rows = []
    with gzip.open(Path.home() / f".cache/cleverkeys-test/futo_swipe5_{layout}.jsonl.gz", "rt") as f:
        for line in f:
            o = json.loads(line)
            rows.append((o["word"], [p[0] for p in o["pts"]], [p[1] for p in o["pts"]], [p[2] for p in o["pts"]]))
    rows = rows[:limit] if limit else rows
    g, lam, beta, gp, bp = PRESET
    res = {a: {"n": 0, "t1": 0, "t3": 0, "acc_n": 0, "acc_t1": 0} for a in arms}
    out = open(Path.cwd() / f"ctc_accent_{layout}.jsonl", "w")
    t0 = time.time()
    for idx, (word, xs, ys, ts) in enumerate(rows):
        tgt = project(word)
        if not tgt or not trie.contains(tgt):
            continue
        accented = any(c in word.lower() for c in extra)
        rec = {"word": word}
        for a, (box, ext) in arms.items():
            if box:
                px = [(x - L) / (R - L) for x in xs]; py = [(y - T) / (B - T) for y in ys]
            else:
                px, py = xs, ys
            k, m = slots(box, ext)
            feats = featurize(px, py, ts)
            full = sess.run(["log_emissions"], {"features": feats[None], "layout_keys": k[None], "layout_mask": m[None]})[0][0]
            lp = np.empty((full.shape[0], 27), np.float32)
            lp[:, :26] = full[:, :26]
            if ext:
                for j, c in enumerate(extra):
                    b = ord(project(c)) - 97
                    lp[:, b] = np.logaddexp(lp[:, b], full[:, 26 + j])
            lp[:, 26] = full[:, 64]
            beam = futo_viterbi_beam(lp, az, 26, trie, 100, 8, g, lam, beta, gp, bp)
            r = rank_of(tgt, [w for w, _ in beam])
            s = res[a]; s["n"] += 1; s["t1"] += r == 0; s["t3"] += 0 <= r < 3
            if accented:
                s["acc_n"] += 1; s["acc_t1"] += r == 0
            rec[a] = r
        out.write(json.dumps(rec, ensure_ascii=False) + "\n")
        if idx % 200 == 0:
            print(idx, {a: f"{s['t1']}/{s['n']}" for a, s in res.items()}, f"{time.time()-t0:.0f}s", flush=True)
    for a, s in res.items():
        n = max(s["n"], 1); an = max(s["acc_n"], 1)
        print(f"{layout} {a}: n={s['n']} t1={100*s['t1']/n:.2f} t3={100*s['t3']/n:.2f} "
              f"| accented-word rows n={s['acc_n']} t1={100*s['acc_t1']/an:.2f}", flush=True)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 0)
