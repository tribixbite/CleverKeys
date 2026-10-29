#!/usr/bin/env python3
"""Build the CleverKeys voice pack for Moonshine v2 tiny-streaming (English).

Output (never committed): build/voicepacks/voicepack-moonshine-v2-tiny-en.zip and its .sha256.
Spec: docs/specs/voice-input.md ("Voice pack format", manifest format 1).

What it does
------------
1. Downloads the official quantised ORT graphs of Moonshine v2 "tiny-streaming" English from the
   Moonshine Voice asset mirror on Hugging Face at a PINNED revision, plus the upstream LICENSE
   from the moonshine-ai/moonshine GitHub repository at a pinned commit (cache:
   ~/.cache/cleverkeys-voice/, override with --cache).
2. Verifies every file's size and sha256 against the values pinned below (an upstream change or
   a corrupt download stops the build; nothing is re-hashed "to whatever arrived").
3. Writes manifest.json (kind "voice", format 1, engine moonshine-v2-streaming v1, the files
   with sha256 + size), LICENSE (upstream text) and NOTICE.txt (provenance + MIT notice).
4. Zips them with deterministic member order and timestamps, so the same inputs give the same
   bytes and the same sha256.
5. Re-reads the ZIP and checks every member against the manifest (what the app's importer does).

The files are redistributed unmodified. The weights and the runtime they come from are MIT
(LICENSE section 1: "all streaming speech-to-text models ... are MIT"); CleverKeys' Kotlin
runtime (stt/moonshine/) is a port of the MIT runtime core/moonshine-streaming-model.cpp.

Usage
-----
    python3 scripts/build_voice_pack.py            # download (if needed), verify, build
    python3 scripts/build_voice_pack.py --offline  # cache only; fail if a file is missing
    python3 scripts/build_voice_pack.py --from-dir <dir>  # take the files from a local dir
                                                   # (still verified against the pins)
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
import urllib.request
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# --- upstream pins (verified 2026-10-29) -------------------------------------------------------
HF_REPO = "moonshine-ai/moonshine-voice-assets"
HF_REVISION = "0bf2f2e5aff22e6fbba4300b00a4e00bbc4f8aae"  # main @ 2026-08-21T17:04:19Z
HF_SUBDIR = "model/tiny-streaming-en/quantized_26_08_21"
HF_BASE = f"https://huggingface.co/{HF_REPO}/resolve/{HF_REVISION}/{HF_SUBDIR}"
HF_TREE = f"https://huggingface.co/{HF_REPO}/tree/{HF_REVISION}/{HF_SUBDIR}"

GH_REPO = "moonshine-ai/moonshine"
GH_COMMIT = "234f60faa0eb388b01cdf7e60aca232af37aefda"  # main @ 2026-08-24T21:24:51Z
LICENSE_URL = f"https://raw.githubusercontent.com/{GH_REPO}/{GH_COMMIT}/LICENSE"

# name -> (size, sha256). LFS files' sha256 equal the Hub's LFS oid; the two small non-LFS files
# (frontend.model.ort, streaming_config.json) were hashed from the download, and FILES.tsv's md5
# for them was checked (6c5e6287…, d022b54c…).
MODEL_FILES: dict[str, tuple[int, str]] = {
    "frontend.model.ort": (23_344, "5121b561417b638afce0c6c31b760e37c93cf97f80d9b0031aad1fe7b6f25d61"),
    "frontend.weights.ort": (2_093_464, "217da24ac6f522ebf02da8ef288e77d1ac68d50d4a6821433182e4fbf4204bbd"),
    "encoder.ort": (7_675_440, "a8414e1a5dedf9f2093d7680601dd8a9b0433e7020260eafe0e370ead91134ca"),
    "adapter.ort": (1_319_664, "22ecc949e146c49667fda28d102d4e30749a107dc88a396292aa8f277ef1347c"),
    "cross_kv.ort": (1_287_544, "143a36667b8d05fd9d04e8c337b7ee121f37ef299aea6b3d82bdb3d3401950b4"),
    "decoder_kv.ort": (32_583_720, "8852553f312adb6c9aa4d17418015049b30f412209ee569d336548c0044627de"),
    "tokenizer.bin": (249_974, "6884b35fd6377d4c4d32336a0bc152f36b64d1e45b6503683cdc238250a8472d"),
    "streaming_config.json": (509, "74fe5ddebd63b17caf59e8a3b18c17547ff7bce1642050edbb1c3962674f8950"),
}
# Upstream LICENSE at GH_COMMIT (GitHub blob 53cade1c…): MIT section 1 + the Community License
# section 2 that covers only legacy non-streaming non-English models.
LICENSE_SIZE = 14_180
LICENSE_SHA256 = "fa7d1174dd8af6a7cd280be20b80d10095ed4c19b5b20b61a7715c3ad790dc5f"

# --- pack identity ------------------------------------------------------------------------------
PACK_ID = "en-moonshine2-tiny"
PACK_NAME = "English — Moonshine v2 tiny (streaming)"
PACK_VERSION = 1
ENGINE_ID = "moonshine-v2-streaming"
ENGINE_VERSION = 1
OUT_NAME = "voicepack-moonshine-v2-tiny-en.zip"
ZIP_TIME = (2026, 10, 29, 0, 0, 0)  # fixed member timestamp → reproducible bytes

ATTRIBUTION = (
    "Moonshine v2 tiny-streaming English, (c) 2025 Useful Sensors, Inc. (dba Moonshine AI), "
    "MIT License. Unmodified quantised ONNX Runtime graphs and tokenizer from the Moonshine "
    "Voice assets; see NOTICE.txt."
)


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def fetch(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    t0 = time.time()
    with urllib.request.urlopen(url, timeout=120) as r, open(tmp, "wb") as f:
        while True:
            block = r.read(1 << 20)
            if not block:
                break
            f.write(block)
    os.replace(tmp, dest)
    print(f"  fetched {dest.name}: {dest.stat().st_size:,} B in {time.time() - t0:.1f}s")


def obtain(name: str, size: int, sha: str, url: str, cache: Path, from_dir: Path | None, offline: bool) -> Path:
    """Return a verified local copy of one upstream file."""
    dest = cache / name
    if from_dir is not None and not dest.exists():
        src = from_dir / name
        if src.exists():
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(src.read_bytes())
    if dest.exists() and (dest.stat().st_size != size or sha256_of(dest) != sha):
        print(f"  cached {name} does not match the pin; re-fetching")
        dest.unlink()
    if not dest.exists():
        if offline:
            sys.exit(f"error: {name} is not cached and --offline was given")
        fetch(url, dest)
    got_size = dest.stat().st_size
    got_sha = sha256_of(dest)
    if got_size != size or got_sha != sha:
        sys.exit(f"error: {name} is {got_size} B sha256 {got_sha}; pinned {size} B {sha}")
    return dest


def notice_text(license_sha: str) -> str:
    lines = [
        "CleverKeys voice pack: English, Moonshine v2 tiny (streaming)",
        "",
        "This pack contains unmodified files of the Moonshine v2 \"tiny-streaming\" English",
        "speech-to-text model, redistributed under the MIT License.",
        "",
        "  Copyright (c) 2025 Useful Sensors, Inc. (dba Moonshine AI)",
        "  Licence: MIT (LICENSE, section 1: every streaming speech-to-text model is MIT)",
        "",
        "Source",
        f"  {HF_TREE}",
        f"  Hugging Face repository {HF_REPO}, revision {HF_REVISION}",
        f"  LICENSE: {LICENSE_URL} (sha256 {license_sha})",
        "  Model paper: Moonshine v2: Ergodic Streaming Encoder ASR for Latency-Critical Speech",
        "  Applications, arXiv:2602.12241",
        "",
        "Files (size in bytes, sha256)",
    ]
    for name, (size, sha) in MODEL_FILES.items():
        lines.append(f"  {name}  {size}  {sha}")
    lines += [
        "",
        "How CleverKeys uses it",
        "  The keyboard runs these graphs on the ONNX Runtime it already ships, entirely on the",
        "  device. Audio is recorded only while voice input is listening, is never stored and",
        "  never leaves the device (CleverKeys has no network permission). The runtime code in",
        "  CleverKeys (stt/moonshine/) is a Kotlin port of the MIT-licensed",
        f"  core/moonshine-streaming-model.cpp of {GH_REPO} at commit {GH_COMMIT}.",
        "",
        "MIT License",
        "",
        "Copyright (c) 2025 Useful Sensors, Inc. (dba Moonshine AI)",
        "",
        "Permission is hereby granted, free of charge, to any person obtaining a copy",
        "of this software and associated documentation files (the \"Software\"), to deal",
        "in the Software without restriction, including without limitation the rights",
        "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell",
        "copies of the Software, and to permit persons to whom the Software is",
        "furnished to do so, subject to the following conditions:",
        "",
        "The above copyright notice and this permission notice shall be included in all",
        "copies or substantial portions of the Software.",
        "",
        "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR",
        "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,",
        "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE",
        "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER",
        "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,",
        "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE",
        "SOFTWARE.",
        "",
    ]
    return "\n".join(lines)


def manifest() -> dict:
    return {
        "kind": "voice",
        "format": 1,
        "id": PACK_ID,
        "name": PACK_NAME,
        "version": PACK_VERSION,
        "engine": {"id": ENGINE_ID, "version": ENGINE_VERSION},
        "languages": ["en"],
        "sampleRate": 16000,
        "minAppVersionCode": 0,
        "files": [{"path": n, "sha256": sha, "size": size} for n, (size, sha) in MODEL_FILES.items()],
        "license": "MIT",
        "attribution": ATTRIBUTION,
        "source": HF_TREE,
    }


def write_zip(out: Path, members: list[tuple[str, bytes | Path]]) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    tmp = out.with_suffix(".zip.part")
    with zipfile.ZipFile(tmp, "w") as z:
        for name, content in members:
            info = zipfile.ZipInfo(name, date_time=ZIP_TIME)
            info.external_attr = 0o644 << 16
            # The .ort graphs are already dense; deflate costs time for ~5 %. Text deflates well.
            info.compress_type = zipfile.ZIP_STORED if name.endswith(".ort") else zipfile.ZIP_DEFLATED
            data = content.read_bytes() if isinstance(content, Path) else content
            z.writestr(info, data)
    os.replace(tmp, out)


def verify_zip(out: Path) -> None:
    with zipfile.ZipFile(out) as z:
        m = json.loads(z.read("manifest.json"))
        assert m["kind"] == "voice" and m["format"] == 1
        names = set(z.namelist())
        for f in m["files"]:
            data = z.read(f["path"])
            assert len(data) == f["size"], f["path"]
            assert hashlib.sha256(data).hexdigest() == f["sha256"], f["path"]
        assert {"LICENSE", "NOTICE.txt", "manifest.json"} <= names
        assert len(names) <= 48


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--cache", type=Path, default=Path.home() / ".cache" / "cleverkeys-voice" / HF_REVISION)
    ap.add_argument("--out-dir", type=Path, default=REPO_ROOT / "build" / "voicepacks")
    ap.add_argument("--from-dir", type=Path, help="take upstream files from this directory (verified)")
    ap.add_argument("--offline", action="store_true")
    args = ap.parse_args()

    print(f"Moonshine v2 tiny-streaming en @ {HF_REPO}@{HF_REVISION[:12]}")
    paths = {
        name: obtain(name, size, sha, f"{HF_BASE}/{name}", args.cache, args.from_dir, args.offline)
        for name, (size, sha) in MODEL_FILES.items()
    }
    lic = obtain("LICENSE", LICENSE_SIZE, LICENSE_SHA256, LICENSE_URL, args.cache, None, args.offline)
    lic_text = lic.read_text(encoding="utf-8")
    # Belt and braces on top of the hash pin: the licence grant this pack relies on.
    if "all streaming speech-to-text models" not in " ".join(lic_text.split()):
        sys.exit("error: the upstream LICENSE no longer states that streaming models are MIT; review before shipping")
    lic_sha = LICENSE_SHA256

    out = args.out_dir / OUT_NAME
    members: list[tuple[str, bytes | Path]] = [
        ("manifest.json", (json.dumps(manifest(), indent=2, ensure_ascii=False) + "\n").encode("utf-8")),
        ("LICENSE", lic.read_bytes()),
        ("NOTICE.txt", notice_text(lic_sha).encode("utf-8")),
    ] + [(name, paths[name]) for name in MODEL_FILES]
    write_zip(out, members)
    verify_zip(out)
    digest = sha256_of(out)
    (out.parent / (out.name + ".sha256")).write_text(f"{digest}  {out.name}\n")
    total = sum(s for s, _ in MODEL_FILES.values())
    print(f"wrote {out} ({out.stat().st_size:,} B zip, {total:,} B of model files)")
    print(f"sha256 {digest}")
    print(f"LICENSE sha256 {lic_sha}")


if __name__ == "__main__":
    main()
