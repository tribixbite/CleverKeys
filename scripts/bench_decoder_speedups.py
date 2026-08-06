#!/usr/bin/env python3
"""Controlled in-process benchmark of the two opt-in ONNX swipe-decoder levers.

WHY IN-PROCESS + INTERLEAVED: this device's CPU governor ramps clock UP under
sustained load, so three plain back-to-back subprocess runs (baseline, then
xnnpack, then int8) come out monotonically faster regardless of config — a pure
frequency-warmup artifact, not a real speedup. To remove that confound this
harness:

  1. Loads the corpus + builds ProdVocab ONCE, and precomputes the encoder
     `memory` for every trace on a FIXED cpu fp32 encoder — so the ONLY thing
     that varies between configs is the DECODER session (the lever under test).
  2. Runs a full warmup decode pass (discarded) to drive the CPU to its steady
     clock before any measurement.
  3. Measures each config INTERLEAVED across several cycles (A B C · A B C · …)
     under continuous load, and reports the per-cycle + median traces/min. If
     the numbers were a frequency artifact they'd drift monotonically across
     cycles; interleaving + median exposes that.

Decoder-only traces/min (encoder cost excluded from the timed loop) isolates the
lever. Accuracy (prod_rank top-1/3/5) is recomputed per config to detect any
loss from int8 or XNNPACK numeric drift.

Configs:
  cpu-fp32   : shipped decoder, CPUExecutionProvider           (committed default path)
  xnnpack    : shipped decoder, XnnpackExecutionProvider+CPU   (EP lever)
  cpu-int8   : quantize_dynamic'd decoder, CPU                 (int8 lever)

LOCAL-ONLY tool. Does not touch repo assets.
"""
from __future__ import annotations

import argparse
import statistics
import sys
import time
import warnings
from pathlib import Path

warnings.filterwarnings("ignore")  # silence the cosmetic ORT "android" platform warning

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import test_cli_predict as H  # noqa: E402  (harness internals: pure functions, no import side effects)


def precompute(corpus, dict_path, limit, skip, threads):
    """Load traces, build ProdVocab, and run the FIXED cpu encoder once per trace.
    Returns (samples, prod_vocab) where samples = [(word, memory, actual_len, stratum)]."""
    encoder_path = ROOT / "src/main/assets/models/swipe_encoder_android.onnx"
    dictionary = H.load_dictionary(Path(dict_path).expanduser())
    prod_vocab = H.ProdVocab(H.load_dictionary_raw(Path(dict_path).expanduser()))
    rows = H.load_corpus_cache(Path(corpus).expanduser(), frame_remap="identity")
    in_dict = [r for r in rows if r[0] in dictionary]
    if skip:
        in_dict = in_dict[skip:]
    if limit:
        in_dict = in_dict[:limit]

    encoder = H.make_session(encoder_path, threads, "cpu")
    samples = []
    for word, xs, ys, ts in in_dict:
        rxs, rys, rts = H.resample_discard(xs, ys, ts, H.MAX_SEQUENCE_LENGTH)
        traj, keys = H.extract_features_training(rxs, rys, rts)
        traj_t, keys_t, alen_t = H.create_tensors(traj, keys)
        alen = int(alen_t[0])
        memory = encoder.run(
            None, {"trajectory_features": traj_t, "nearest_keys": keys_t, "actual_length": alen_t})[0]
        samples.append((word, memory, alen, H.len_stratum(word)))
    return samples, prod_vocab


def decode_pass(decoder, samples, prod_vocab, max_len, alpha, score=False):
    """One full production decode pass over all samples on `decoder`.
    Returns (elapsed_s, tally_or_None). Timing covers ONLY decoder beam + rerank."""
    tally = H.Tally() if score else None
    t0 = time.perf_counter()
    for word, memory, alen, _stratum in samples:
        prod_beams = H.run_production_beam(
            decoder, memory, alen, prod_vocab.trie, H.PROD_BEAM_WIDTH, max_len, alpha)
        reranked = prod_vocab.filter_predictions(prod_beams)
        if score:
            prod_words = [w for w, _ in reranked]
            tally.add(H.rank_of(word, prod_words))
    return time.perf_counter() - t0, tally


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--corpus",
                    default=str(Path("~/.cache/cleverkeys-test/test2400_ordered.jsonl.gz").expanduser()))
    ap.add_argument("--dict", default="src/main/assets/dictionaries/en_enhanced.json")
    ap.add_argument("--limit", type=int, default=300)
    ap.add_argument("--skip", type=int, default=0)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--cycles", type=int, default=3, help="interleaved measured cycles per config")
    ap.add_argument("--int8-decoder", dest="int8_decoder",
                    default=str(Path("~/.cache/cleverkeys-test/swipe_decoder_android.int8.onnx").expanduser()))
    args = ap.parse_args()

    dec_shipped = ROOT / "src/main/assets/models/swipe_decoder_android.onnx"
    max_len, alpha = H.DECODER_SEQ_LENGTH, H.PROD_BEAM_ALPHA

    print(f"precomputing encoder memory for up to {args.limit} traces (fixed cpu encoder)…")
    t0 = time.time()
    samples, prod_vocab = precompute(args.corpus, args.dict, args.limit, args.skip, args.threads)
    print(f"  {len(samples)} traces ready, ProdVocab={len(prod_vocab.vocab)} words "
          f"({time.time() - t0:.1f}s)")

    # Build the three decoder sessions once (session build is not part of timing).
    configs = [
        ("cpu-fp32", H.make_session(dec_shipped, args.threads, "cpu")),
        ("xnnpack", H.make_session(dec_shipped, args.threads, "xnnpack")),
    ]
    int8_path = Path(args.int8_decoder)
    if int8_path.exists():
        configs.append(("cpu-int8", H.make_session(int8_path, args.threads, "cpu")))
    else:
        print(f"[WARN] int8 decoder not found at {int8_path} — skipping cpu-int8")
    for name, dec in configs:
        print(f"  session[{name}] providers = {dec.get_providers()}")

    # Warmup: full pass on every session, discarded, to reach steady CPU clock.
    print("warmup pass (discarded)…")
    for _name, dec in configs:
        decode_pass(dec, samples, prod_vocab, max_len, alpha, score=False)

    # PAIRED per-trace timing: for each trace, decode it on ALL sessions
    # back-to-back so they share the same instantaneous CPU clock. Rotate the
    # session order per trace to cancel any micro within-triplet position bias.
    # This makes the speedup RATIO robust to the governor's clock ramp; absolute
    # tr/min still reflects the run-average clock.
    n = len(samples)
    names = [name for name, _ in configs]
    elapsed = {name: 0.0 for name in names}
    tally = {name: H.Tally() for name in names}
    print(f"paired timing: {args.cycles} reps × {n} traces, rotated order…")
    for rep in range(args.cycles):
        for i, (word, memory, alen, _stratum) in enumerate(samples):
            order = configs[i % len(configs):] + configs[:i % len(configs)]  # rotate
            for name, dec in order:
                t0 = time.perf_counter()
                prod_beams = H.run_production_beam(
                    dec, memory, alen, prod_vocab.trie, H.PROD_BEAM_WIDTH, max_len, alpha)
                reranked = prod_vocab.filter_predictions(prod_beams)
                elapsed[name] += time.perf_counter() - t0
                if rep == 0:  # score once (deterministic)
                    tally[name].add(H.rank_of(word, [w for w, _ in reranked]))
        print(f"  rep {rep} done  " + "  ".join(
            f"{name}={n * (rep + 1) / (elapsed[name] / 60.0):.0f}tr/min" for name in names))

    total_decodes = n * args.cycles
    tpm = {name: total_decodes / (elapsed[name] / 60.0) for name in names}
    acc = {name: (tally[name].top1(), tally[name].top3(), tally[name].top5()) for name in names}
    base = "cpu-fp32"
    base_tpm = tpm[base]
    b1, b3, b5 = acc[base]

    print("\n" + "=" * 96)
    print(f"CONTROLLED DECODER BENCHMARK  ({n} traces × {args.cycles} reps, threads={args.threads}, "
          f"PAIRED per-trace timing, decoder-only)")
    print("=" * 96)
    print(f"{'config':<10}{'tr/min':>12}{'speedup':>10}{'  prod t1/t3/t5':>22}")
    print("-" * 96)
    for name in names:
        speed = tpm[name] / base_tpm if base_tpm else 0.0
        a1, a3, a5 = acc[name]
        print(f"{name:<10}{tpm[name]:>12.1f}{speed:>9.2f}x"
              f"     {a1 * 100:4.1f}/{a3 * 100:4.1f}/{a5 * 100:4.1f}%")
    print("=" * 96)
    print("tr/min = decoder beam+rerank only (encoder precomputed, shared cpu fp32); "
          "ratio is clock-robust via paired timing.")
    print("accuracy deltas vs cpu-fp32 (percentage points):")
    for name in names:
        a1, a3, a5 = acc[name]
        print(f"    {name:<10} Δt1={(a1 - b1) * 100:+.2f}  Δt3={(a3 - b3) * 100:+.2f}  "
              f"Δt5={(a5 - b5) * 100:+.2f}")
    print("BENCH_DONE")
    return 0


if __name__ == "__main__":
    sys.exit(main())
