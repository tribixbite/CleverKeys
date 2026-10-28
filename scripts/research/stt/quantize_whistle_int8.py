#!/usr/bin/env python3
"""Make dynamic-int8 versions of the Whistle ONNX graphs (research spike, 2026-10-28).

`onnxruntime.quantization.quantize_dynamic` on the published fp32 export quantises NOTHING: the
export (torch.onnx, do_constant_folding=False) reconstructs every weight matrix at run time with
Gather/Transpose nodes over layer-stacked initializers, so no MatMul has a constant B input.
ORT's own offline optimiser at ORT_ENABLE_BASIC folds those (encoder: 251 MatMuls, 179 with a
constant B afterwards; the other 72 are activation x activation attention products). The folded
graph still references the original `.onnx.data`, so the file is copied beside it before the
quantiser loads the model. Per-channel QInt8 on MatMul only.

Result on this box: encoder 72.6 MB -> 19.1 MB, decoder 73.4 MB -> 35.9 MB (single files),
transcripts unchanged on the clips tried, session load 6.5 s -> 3.0 s, RSS -100 MB.

  python3 quantize_whistle_int8.py --onnx-dir $STT/models/whistle-onnx/onnx --out-dir $STT/models/whistle-onnx/onnx-int8
"""
from __future__ import annotations

import argparse
import collections
import os
import shutil
import tempfile
import time

import onnx
import onnxruntime as ort
from onnxruntime.quantization import QuantType, quantize_dynamic


def fold_constants(src: str, dst: str) -> None:
    so = ort.SessionOptions()
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
    so.optimized_model_filepath = dst
    so.log_severity_level = 3
    ort.InferenceSession(src, so, providers=["CPUExecutionProvider"])


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--onnx-dir", required=True, help="dir with encoder.onnx(.data) and decoder.onnx(.data)")
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--per-channel", action="store_true", default=True)
    args = ap.parse_args()
    os.makedirs(args.out_dir, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=args.out_dir) as tmp:
        for g in ("encoder", "decoder"):
            t0 = time.perf_counter()
            folded = os.path.join(tmp, f"{g}.onnx")
            fold_constants(os.path.join(args.onnx_dir, f"{g}.onnx"), folded)
            data = os.path.join(args.onnx_dir, f"{g}.onnx.data")
            if os.path.exists(data):
                shutil.copy(data, os.path.join(tmp, f"{g}.onnx.data"))
            m = onnx.load(folded, load_external_data=False)
            inits = {i.name for i in m.graph.initializer}
            n_mm = sum(1 for n in m.graph.node if n.op_type == "MatMul")
            n_const = sum(1 for n in m.graph.node if n.op_type == "MatMul" and n.input[1] in inits)
            out = os.path.join(args.out_dir, f"{g}.onnx")
            quantize_dynamic(folded, out, weight_type=QuantType.QInt8, per_channel=args.per_channel, reduce_range=False,
                             op_types_to_quantize=["MatMul"])
            q = onnx.load(out, load_external_data=False)
            ops = collections.Counter(n.op_type for n in q.graph.node)
            print(f"{g}: {n_const}/{n_mm} MatMuls had constant weights after folding; MatMulInteger={ops['MatMulInteger']} "
                  f"MatMul left={ops['MatMul']}; {os.path.getsize(out):,} bytes; {time.perf_counter()-t0:.0f} s")


if __name__ == "__main__":
    main()
