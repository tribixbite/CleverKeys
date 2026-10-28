#!/usr/bin/env python3
"""Fetch LibriSpeech test-clean utterances (CC BY 4.0) for the STT research scripts, 2026-10-28.

Uses the Hugging Face datasets-server rows API for `openslr/librispeech_asr` (config `clean`,
split `test`), which serves each utterance's FLAC and transcript without pyarrow or `datasets`
(neither builds on Termux). FLAC -> 16 kHz mono PCM16 WAV with ffmpeg. Writes
`<out>/manifest.json` = [{id, text, seconds, wav}]. The cached-assets endpoint rate-limits
(HTTP 403 after ~80 files in one go); the script keeps what it has and the manifest covers
only clips whose WAV and transcript both exist.

  python3 fetch_librispeech.py --out $STT/audio --rows 120
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import subprocess
import urllib.error
import urllib.request
import wave

API = "https://datasets-server.huggingface.co/rows?dataset=openslr%2Flibrispeech_asr&config=clean&split=test"


def fetch_rows(n_rows: int) -> list[dict]:
    rows: list[dict] = []
    for off in range(0, n_rows, 40):
        with urllib.request.urlopen(f"{API}&offset={off}&length={min(40, n_rows - off)}", timeout=60) as r:
            rows += json.load(r)["rows"]
    return rows


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True)
    ap.add_argument("--rows", type=int, default=120)
    args = ap.parse_args()
    flac_dir, wav_dir = os.path.join(args.out, "flac"), os.path.join(args.out, "wav")
    os.makedirs(flac_dir, exist_ok=True)
    os.makedirs(wav_dir, exist_ok=True)
    rows = fetch_rows(args.rows)
    json.dump(rows, open(os.path.join(args.out, "rows.json"), "w"))
    texts = {}
    for row in rows:
        r = row["row"]
        uid, src = r["id"], r["audio"][0]["src"]
        texts[uid] = r["text"]
        flac, wav = os.path.join(flac_dir, f"{uid}.flac"), os.path.join(wav_dir, f"{uid}.wav")
        if not os.path.exists(flac):
            try:
                with urllib.request.urlopen(src, timeout=120) as resp, open(flac, "wb") as f:
                    f.write(resp.read())
            except urllib.error.HTTPError as e:
                print(f"{uid}: {e} (rate limit?) — stopping downloads, keeping what exists")
                break
        if not os.path.exists(wav):
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", flac, "-ar", "16000", "-ac", "1",
                            "-sample_fmt", "s16", wav], check=True)
    manifest = []
    for wav in sorted(glob.glob(os.path.join(wav_dir, "*.wav"))):
        uid = os.path.basename(wav)[:-4]
        if uid not in texts:
            continue
        with wave.open(wav) as w:
            sec = w.getnframes() / w.getframerate()
        manifest.append({"id": uid, "text": texts[uid], "seconds": round(sec, 2), "wav": os.path.abspath(wav)})
    json.dump(manifest, open(os.path.join(args.out, "manifest.json"), "w"), indent=1)
    secs = sorted(m["seconds"] for m in manifest)
    print(f"{len(manifest)} clips; seconds min/median/max {secs[0]}/{secs[len(secs)//2]}/{secs[-1]}")


if __name__ == "__main__":
    main()
