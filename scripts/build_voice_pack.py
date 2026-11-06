#!/usr/bin/env python3
"""Build a CleverKeys voice pack for Moonshine v2 streaming English (tiny, or the optional small).

Output (never committed): build/voicepacks/voicepack-moonshine-v2-{tiny,small}-en.zip and .sha256.
Spec: docs/specs/voice-input.md ("Voice pack format", manifest format 1).

What it does
------------
1. Downloads the official quantised ORT graphs of Moonshine v2 "<size>-streaming" English from the
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
    python3 scripts/build_voice_pack.py            # download (if needed), verify, build (tiny)
    python3 scripts/build_voice_pack.py --variant small   # the optional, more accurate pack
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
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# --- upstream pins (verified 2026-10-29) -------------------------------------------------------
HF_REPO = "moonshine-ai/moonshine-voice-assets"
HF_REVISION = "0bf2f2e5aff22e6fbba4300b00a4e00bbc4f8aae"  # main @ 2026-08-21T17:04:19Z
HF_TREE_BASE = f"https://huggingface.co/{HF_REPO}/tree/{HF_REVISION}"
HF_RESOLVE_BASE = f"https://huggingface.co/{HF_REPO}/resolve/{HF_REVISION}"

GH_REPO = "moonshine-ai/moonshine"
GH_COMMIT = "234f60faa0eb388b01cdf7e60aca232af37aefda"  # main @ 2026-08-24T21:24:51Z
LICENSE_URL = f"https://raw.githubusercontent.com/{GH_REPO}/{GH_COMMIT}/LICENSE"

# Upstream LICENSE at GH_COMMIT (GitHub blob 53cade1c…): MIT section 1 + the Community License
# section 2 that covers only legacy non-streaming non-English models.
LICENSE_SIZE = 14_180
LICENSE_SHA256 = "fa7d1174dd8af6a7cd280be20b80d10095ed4c19b5b20b61a7715c3ad790dc5f"

ENGINE_ID = "moonshine-v2-streaming"
ENGINE_VERSION = 1


@dataclass(frozen=True)
class Variant:
    """One Moonshine v2 streaming size: where its files live upstream and the pack it becomes."""

    size: str  # "tiny" / "small": the upstream directory and every name below derive from it
    copyright_year: int
    # name -> (size, sha256). LFS files' sha256 equal the Hub's LFS oid; the two small non-LFS
    # files (frontend.model.ort, streaming_config.json) are hashed from the download.
    files: dict[str, tuple[int, str]]
    zip_time: tuple[int, int, int, int, int, int]  # fixed member timestamp → reproducible bytes

    @property
    def subdir(self) -> str:
        return f"model/{self.size}-streaming-en/quantized_26_08_21"

    @property
    def tree_url(self) -> str:
        return f"{HF_TREE_BASE}/{self.subdir}"

    @property
    def pack_id(self) -> str:
        return f"en-moonshine2-{self.size}"

    @property
    def pack_name(self) -> str:
        return f"English — Moonshine v2 {self.size} (streaming)"

    @property
    def out_name(self) -> str:
        return f"voicepack-moonshine-v2-{self.size}-en.zip"

    @property
    def model_label(self) -> str:
        return f"Moonshine v2 {self.size}-streaming English"

    @property
    def attribution(self) -> str:
        return (
            f"{self.model_label}, (c) {self.copyright_year} Useful Sensors, Inc. (dba Moonshine AI), "
            "MIT License. Unmodified quantised ONNX Runtime graphs and tokenizer from the Moonshine "
            "Voice assets; see NOTICE.txt."
        )


VARIANTS: dict[str, Variant] = {
    # The shipped pack (verified 2026-10-29). Its zip sha256 33ec8c87… is pinned in
    # docs/specs/voice-input.md, so nothing here may change its bytes.
    "tiny": Variant(
        size="tiny",
        copyright_year=2025,
        files={
            "frontend.model.ort": (23_344, "5121b561417b638afce0c6c31b760e37c93cf97f80d9b0031aad1fe7b6f25d61"),
            "frontend.weights.ort": (2_093_464, "217da24ac6f522ebf02da8ef288e77d1ac68d50d4a6821433182e4fbf4204bbd"),
            "encoder.ort": (7_675_440, "a8414e1a5dedf9f2093d7680601dd8a9b0433e7020260eafe0e370ead91134ca"),
            "adapter.ort": (1_319_664, "22ecc949e146c49667fda28d102d4e30749a107dc88a396292aa8f277ef1347c"),
            "cross_kv.ort": (1_287_544, "143a36667b8d05fd9d04e8c337b7ee121f37ef299aea6b3d82bdb3d3401950b4"),
            "decoder_kv.ort": (32_583_720, "8852553f312adb6c9aa4d17418015049b30f412209ee569d336548c0044627de"),
            "tokenizer.bin": (249_974, "6884b35fd6377d4c4d32336a0bc152f36b64d1e45b6503683cdc238250a8472d"),
            "streaming_config.json": (509, "74fe5ddebd63b17caf59e8a3b18c17547ff7bce1642050edbb1c3962674f8950"),
        },
        zip_time=(2026, 10, 29, 0, 0, 0),
    ),
    # Optional "accurate" pack (verified 2026-11-05 against the Hub's tree API at HF_REVISION;
    # the decoder, 78.1 MiB, is the largest file and the total, 135.7 MiB (142,300,974 bytes), is within the caps).
    # Same tokenizer as tiny. decoder_kv_with_attention.ort is not packed: the runtime does not
    # use it (alignment output only).
    "small": Variant(
        size="small",
        copyright_year=2025,
        files={
            "frontend.model.ort": (26_944, "09b1210ae30dc5f0f3e45f0ebab914c254741323114f53fbbe5ae62cca35058f"),
            "frontend.weights.ort": (7_769_464, "7ef97521bd4bad3928f5bb6808586f4fcc6e92bd5990394112eed7d4052ec338"),
            "encoder.ort": (44_148_576, "2d4d973e91e8aca08c51e7e7efa28a46ab265b63d809d5294d18b86bcd85b993"),
            "adapter.ort": (2_870_368, "c665f742364febad597cc9ac1e0b341ffbee0e24a1466e2f3bde95e6e4771762"),
            "cross_kv.ort": (5_356_536, "e2d3417144e9514055ebfefe8dcc4c0a55a55adcb8530435844c75c53e352bf6"),
            "decoder_kv.ort": (81_878_600, "1a05465b1dd955858dfcbee039c0020fb5dd982b0f5094c34e61735d518d771b"),
            "tokenizer.bin": (249_974, "6884b35fd6377d4c4d32336a0bc152f36b64d1e45b6503683cdc238250a8472d"),
            "streaming_config.json": (512, "26f02b6afb22d60871a5efd85c3d38e569cc0ddb6c5eb6e93d3260152ae8a47a"),
        },
        zip_time=(2026, 11, 5, 0, 0, 0),
    ),
}

PACK_VERSION = 1

# Mirrors of the importer's caps (src/main/kotlin/tribixbite/cleverkeys/stt/VoicePackManifest.kt,
# VoicePackManager.kt). Checked here so a pack the app would refuse is never written.
MAX_ENTRIES = 48
MAX_FILES = 32
MAX_FILE_BYTES = 128 * 1024 * 1024
MAX_TOTAL_BYTES = 256 * 1024 * 1024


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


def notice_text(v: Variant, license_sha: str) -> str:
    lines = [
        f"CleverKeys voice pack: English, Moonshine v2 {v.size} (streaming)",
        "",
        f"This pack contains unmodified files of the Moonshine v2 \"{v.size}-streaming\" English",
        "speech-to-text model, redistributed under the MIT License.",
        "",
        f"  Copyright (c) {v.copyright_year} Useful Sensors, Inc. (dba Moonshine AI)",
        "  Licence: MIT (LICENSE, section 1: every streaming speech-to-text model is MIT)",
        "",
        "Source",
        f"  {v.tree_url}",
        f"  Hugging Face repository {HF_REPO}, revision {HF_REVISION}",
        f"  LICENSE: {LICENSE_URL} (sha256 {license_sha})",
        "  Model paper: Moonshine v2: Ergodic Streaming Encoder ASR for Latency-Critical Speech",
        "  Applications, arXiv:2602.12241",
        "",
        "Files (size in bytes, sha256)",
    ]
    for name, (size, sha) in v.files.items():
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
        f"Copyright (c) {v.copyright_year} Useful Sensors, Inc. (dba Moonshine AI)",
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


def manifest(v: Variant) -> dict:
    return {
        "kind": "voice",
        "format": 1,
        "id": v.pack_id,
        "name": v.pack_name,
        "version": PACK_VERSION,
        "engine": {"id": ENGINE_ID, "version": ENGINE_VERSION},
        "languages": ["en"],
        "sampleRate": 16000,
        "minAppVersionCode": 0,
        "files": [{"path": n, "sha256": sha, "size": size} for n, (size, sha) in v.files.items()],
        "license": "MIT",
        "attribution": v.attribution,
        "source": v.tree_url,
    }


def write_zip(out: Path, members: list[tuple[str, bytes | Path]], zip_time: tuple[int, int, int, int, int, int]) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    tmp = out.with_suffix(".zip.part")
    with zipfile.ZipFile(tmp, "w") as z:
        for name, content in members:
            info = zipfile.ZipInfo(name, date_time=zip_time)
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
        # The app's limits (VoicePackManifest.MAX_FILES / MAX_FILE_BYTES / MAX_TOTAL_BYTES,
        # VoicePackManager.MAX_ENTRIES): a pack over any of them is refused at import.
        assert len(names) <= MAX_ENTRIES
        assert len(m["files"]) <= MAX_FILES
        assert all(f["size"] <= MAX_FILE_BYTES for f in m["files"]), "a file exceeds the per-file cap"
        assert sum(f["size"] for f in m["files"]) <= MAX_TOTAL_BYTES, "the files exceed the total cap"


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--variant", choices=sorted(VARIANTS), default="tiny", help="model size (default tiny, the shipped pack)")
    ap.add_argument("--cache", type=Path, default=Path.home() / ".cache" / "cleverkeys-voice" / HF_REVISION,
                    help="download cache; a variant's files go in <cache>/<size>-streaming-en/")
    ap.add_argument("--out-dir", type=Path, default=REPO_ROOT / "build" / "voicepacks")
    ap.add_argument("--from-dir", type=Path, help="take upstream files from this directory (verified)")
    ap.add_argument("--offline", action="store_true")
    args = ap.parse_args()
    v = VARIANTS[args.variant]

    print(f"{v.model_label} @ {HF_REPO}@{HF_REVISION[:12]}")
    cache = args.cache / f"{v.size}-streaming-en"
    # Before 2026-11-05 the builder knew only tiny and cached its files flat in <cache>; reuse them
    # (still verified against the pins) rather than downloading them again.
    from_dir = args.from_dir if args.from_dir is not None else (args.cache if v.size == "tiny" else None)
    paths = {
        name: obtain(name, size, sha, f"{HF_RESOLVE_BASE}/{v.subdir}/{name}", cache, from_dir, args.offline)
        for name, (size, sha) in v.files.items()
    }
    lic = obtain("LICENSE", LICENSE_SIZE, LICENSE_SHA256, LICENSE_URL, args.cache, None, args.offline)
    lic_text = lic.read_text(encoding="utf-8")
    # Belt and braces on top of the hash pin: the licence grant this pack relies on.
    if "all streaming speech-to-text models" not in " ".join(lic_text.split()):
        sys.exit("error: the upstream LICENSE no longer states that streaming models are MIT; review before shipping")
    lic_sha = LICENSE_SHA256

    out = args.out_dir / v.out_name
    members: list[tuple[str, bytes | Path]] = [
        ("manifest.json", (json.dumps(manifest(v), indent=2, ensure_ascii=False) + "\n").encode("utf-8")),
        ("LICENSE", lic.read_bytes()),
        ("NOTICE.txt", notice_text(v, lic_sha).encode("utf-8")),
    ] + [(name, paths[name]) for name in v.files]
    write_zip(out, members, v.zip_time)
    verify_zip(out)
    digest = sha256_of(out)
    (out.parent / (out.name + ".sha256")).write_text(f"{digest}  {out.name}\n")
    total = sum(s for s, _ in v.files.values())
    print(f"wrote {out} ({out.stat().st_size:,} B zip, {total:,} B of model files)")
    print(f"sha256 {digest}")
    print(f"LICENSE sha256 {lic_sha}")


if __name__ == "__main__":
    main()
