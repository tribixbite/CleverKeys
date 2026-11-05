#!/usr/bin/env python3
"""Rebuild an f16 whisper.cpp (ggml) model from a Hugging Face Whisper safetensors checkpoint.

Why: FUTO publishes its ACFT Whisper checkpoints as fp32 safetensors (futo-org/acft-whisper-*) and
as one ggml quantisation each (q8_0, voiceinput.futo.org). whisper.cpp's `whisper-quantize` only
accepts f32/f16 input ("unsupported ttype ... for integer quantization"), so any other
quantisation (q5_0, q4_k, ...) needs an f16 ggml first. whisper.cpp's own converter
(models/convert-h5-to-ggml.py) needs torch + transformers + the openai/whisper repo; this does the
same with numpy only.

How: FUTO's own q8_0 file is the TEMPLATE. Its header (hparams), mel filters and vocabulary are
copied byte for byte (ftype set to f16), and its tensor list fixes the order, names, shapes and
which tensors stay f32. Every tensor's DATA comes from the safetensors: tensors stored q8_0 in
the template are written f16, the others in the template's own type. Each tensor is checked
against the template's dequantised values, so a wrong name mapping or layout fails loudly
instead of producing a model that decodes garbage.

Usage:
    python3 acft_safetensors_to_ggml.py --safetensors model.safetensors \
        --template ggml-acft-small_en-q8_0.bin --out ggml-acft-small_en-f16.bin
then e.g. `whisper-quantize ggml-acft-small_en-f16.bin ggml-acft-small_en-q5_0.bin q5_0`.
"""
from __future__ import annotations

import argparse
import json
import re
import struct
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np

GGML_MAGIC = 0x67676D6C
# ggml_type ids used by whisper.cpp model files.
GGML_F32, GGML_F16, GGML_Q8_0 = 0, 1, 8
N_HPARAMS = 11  # n_vocab … n_mels, ftype (whisper_model_load)
Q8_0_BLOCK = 32  # elements per q8_0 block: one f16 scale + 32 int8

# HF transformers name fragment -> whisper.cpp name fragment (models/convert-h5-to-ggml.py conv_map).
_NAME_MAP = [
    (r"^model\.", ""),
    (r"\.self_attn\.k_proj\.", ".attn.key."),
    (r"\.self_attn\.q_proj\.", ".attn.query."),
    (r"\.self_attn\.v_proj\.", ".attn.value."),
    (r"\.self_attn\.out_proj\.", ".attn.out."),
    (r"\.self_attn_layer_norm\.", ".attn_ln."),
    (r"\.encoder_attn\.k_proj\.", ".cross_attn.key."),
    (r"\.encoder_attn\.q_proj\.", ".cross_attn.query."),
    (r"\.encoder_attn\.v_proj\.", ".cross_attn.value."),
    (r"\.encoder_attn\.out_proj\.", ".cross_attn.out."),
    (r"\.encoder_attn_layer_norm\.", ".cross_attn_ln."),
    (r"\.fc1\.", ".mlp.0."),
    (r"\.fc2\.", ".mlp.2."),
    (r"\.final_layer_norm\.", ".mlp_ln."),
    (r"\.layers\.", ".blocks."),
    (r"^encoder\.embed_positions\.weight$", "encoder.positional_embedding"),
    (r"^decoder\.embed_positions\.weight$", "decoder.positional_embedding"),
    (r"^decoder\.embed_tokens\.weight$", "decoder.token_embedding.weight"),
    (r"^encoder\.layer_norm\.", "encoder.ln_post."),
    (r"^decoder\.layer_norm\.", "decoder.ln."),
]


def ggml_name(hf: str) -> str:
    name = hf
    for pattern, repl in _NAME_MAP:
        name = re.sub(pattern, repl, name)
    return name


@dataclass
class TemplateTensor:
    name: str
    ne: list[int]  # ggml order (fastest-varying first)
    ttype: int
    data: bytes

    @property
    def count(self) -> int:
        return int(np.prod(self.ne))


def read_template(path: Path) -> tuple[bytes, list[TemplateTensor]]:
    """Split a whisper.cpp model into (header bytes up to the first tensor, tensors)."""
    buf = path.read_bytes()
    off = 0

    def i32() -> int:
        nonlocal off
        (v,) = struct.unpack_from("<i", buf, off)
        off += 4
        return v

    if struct.unpack_from("<I", buf, 0)[0] != GGML_MAGIC:
        sys.exit(f"error: {path} is not a ggml whisper model")
    off = 4 + 4 * N_HPARAMS
    n_mel, n_fft = i32(), i32()
    off += 4 * n_mel * n_fft
    n_vocab = i32()
    for _ in range(n_vocab):
        off += 4 + i32()
    header = buf[:off]

    tensors: list[TemplateTensor] = []
    while off < len(buf):
        n_dims, name_len, ttype = i32(), i32(), i32()
        ne = [i32() for _ in range(n_dims)]
        name = buf[off:off + name_len].decode()
        off += name_len
        count = int(np.prod(ne))
        if ttype == GGML_F32:
            size = 4 * count
        elif ttype == GGML_F16:
            size = 2 * count
        elif ttype == GGML_Q8_0:
            size = (count // Q8_0_BLOCK) * (2 + Q8_0_BLOCK)
        else:
            sys.exit(f"error: template tensor {name} has ggml type {ttype}; only f32/f16/q8_0 templates are supported")
        tensors.append(TemplateTensor(name, ne, ttype, buf[off:off + size]))
        off += size
    return header, tensors


def dequantize(t: TemplateTensor) -> np.ndarray:
    if t.ttype == GGML_F32:
        return np.frombuffer(t.data, dtype="<f4").astype(np.float32)
    if t.ttype == GGML_F16:
        return np.frombuffer(t.data, dtype="<f2").astype(np.float32)
    blocks = np.frombuffer(t.data, dtype=np.dtype([("d", "<f2"), ("q", "i1", (Q8_0_BLOCK,))]))
    return (blocks["d"].astype(np.float32)[:, None] * blocks["q"].astype(np.float32)).reshape(-1)


def read_safetensors(path: Path) -> dict[str, np.ndarray]:
    """Memory-map every tensor of a safetensors file (F32/F16/BF16)."""
    with open(path, "rb") as f:
        (hlen,) = struct.unpack("<Q", f.read(8))
        meta = json.loads(f.read(hlen))
    base = 8 + hlen
    mm = np.memmap(path, dtype=np.uint8, mode="r")
    out: dict[str, np.ndarray] = {}
    for name, info in meta.items():
        if name == "__metadata__":
            continue
        lo, hi = info["data_offsets"]
        raw = mm[base + lo:base + hi]
        if info["dtype"] == "F32":
            arr = raw.view("<f4")
        elif info["dtype"] == "F16":
            arr = raw.view("<f2").astype(np.float32)
        elif info["dtype"] == "BF16":
            arr = (raw.view("<u2").astype(np.uint32) << 16).view(np.float32)
        else:
            sys.exit(f"error: {name} has dtype {info['dtype']}")
        out[name] = arr.reshape(info["shape"])
    return out


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--safetensors", type=Path, required=True)
    ap.add_argument("--template", type=Path, required=True, help="a ggml model of the same checkpoint (e.g. FUTO's q8_0)")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--max-rel-err", type=float, default=0.02,
                    help="largest allowed |template - safetensors| / max|safetensors| per tensor (q8_0 is ~0.4 %%)")
    args = ap.parse_args()

    header, tmpl = read_template(args.template)
    st = read_safetensors(args.safetensors)
    by_ggml = {ggml_name(k): v for k, v in st.items()}

    hparams = list(struct.unpack_from(f"<{N_HPARAMS}i", header, 4))
    print(f"template hparams {hparams} (ftype {hparams[-1]} -> 1), {len(tmpl)} tensors")
    hparams[-1] = GGML_F16
    header = header[:4] + struct.pack(f"<{N_HPARAMS}i", *hparams) + header[4 + 4 * N_HPARAMS:]

    used: set[str] = set()
    worst = 0.0
    tmp = args.out.with_suffix(args.out.suffix + ".part")
    with open(tmp, "wb") as f:
        f.write(header)
        for t in tmpl:
            if t.name not in by_ggml:
                sys.exit(f"error: template tensor {t.name} has no safetensors source")
            src = np.ascontiguousarray(by_ggml[t.name], dtype=np.float32).reshape(-1)
            if src.size != t.count:
                sys.exit(f"error: {t.name}: {src.size} elements in safetensors, {t.count} in the template")
            ref = dequantize(t)
            scale = float(np.max(np.abs(src))) or 1.0
            rel = float(np.max(np.abs(ref - src))) / scale
            worst = max(worst, rel)
            if rel > args.max_rel_err:
                sys.exit(f"error: {t.name}: template and safetensors differ by {rel:.4f} of the range (mapping or layout wrong?)")
            out_type = GGML_F16 if t.ttype == GGML_Q8_0 else t.ttype
            name_b = t.name.encode()
            f.write(struct.pack(f"<3i{len(t.ne)}i", len(t.ne), len(name_b), out_type, *t.ne))
            f.write(name_b)
            f.write(src.astype("<f2" if out_type == GGML_F16 else "<f4").tobytes())
            used.add(t.name)
    # proj_out is tied to the token embedding in Whisper and is not stored by whisper.cpp.
    unused = sorted(k for k in by_ggml if k not in used and not k.startswith("proj_out"))
    if unused:
        sys.exit(f"error: safetensors tensors not in the template: {unused[:8]}")
    tmp.replace(args.out)
    print(f"wrote {args.out} ({args.out.stat().st_size:,} B); worst per-tensor error vs template {worst:.5f} of range")


if __name__ == "__main__":
    main()
