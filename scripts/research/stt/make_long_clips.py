#!/usr/bin/env python3
"""Build long continuous-reading clips from consecutive LibriSpeech utterances (2026-11-05).

LibriSpeech test-clean has no single utterance near 60 s (the fetched set tops out at 23.3 s), so
long dictation is modelled by joining CONSECUTIVE utterances of one chapter — the same reader
continuing the same text — with a short silence between them. The default 0.3 s gap is below the
keyboard's 0.6 s endpointing hangover (`SpeechSegmenterConfig.hangoverSeconds`), so the app would
treat the whole clip as one segment up to the engine's forced cap: the worst case for an engine.

Writes `<out>/manifest_long.json` = [{id, text, seconds, wav, parts}] in the same shape as
`fetch_librispeech.py`'s manifest, so every bench script reads it unchanged.

  python3 make_long_clips.py --manifest $STT/audio/manifest.json --out $STT/audio \
      --target 30:6 --target 60:4 [--gap 0.3]
"""
from __future__ import annotations

import argparse
import collections
import json
import os
import wave

RATE = 16000


def utterance_key(uid: str) -> tuple[str, int]:
    """'6930-75918-0002' -> ('6930-75918', 2): chapter and position within it."""
    chapter, idx = uid.rsplit("-", 1)
    return chapter, int(idx)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--target", action="append", required=True,
                    help="SECONDS:COUNT, e.g. 30:6 = six clips of at least 30 s")
    ap.add_argument("--gap", type=float, default=0.3, help="silence between utterances (s)")
    args = ap.parse_args()

    by_chapter: dict[str, list[dict]] = collections.defaultdict(list)
    for m in json.load(open(args.manifest)):
        by_chapter[utterance_key(m["id"])[0]].append(m)
    for v in by_chapter.values():
        v.sort(key=lambda m: utterance_key(m["id"])[1])

    wav_dir = os.path.join(args.out, "wav_long")
    os.makedirs(wav_dir, exist_ok=True)
    gap = b"\x00\x00" * int(args.gap * RATE)
    out: list[dict] = []
    for spec in args.target:
        target_s, count = (float(x) for x in spec.split(":"))
        made = 0
        # round-robin over chapters so one reader does not dominate a set
        cursors = {c: 0 for c in sorted(by_chapter)}
        while made < count and any(cursors[c] < len(by_chapter[c]) for c in cursors):
            for c in sorted(cursors):
                if made >= count:
                    break
                utts, i = by_chapter[c], cursors[c]
                parts, secs = [], 0.0
                while i < len(utts) and secs < target_s:
                    parts.append(utts[i])
                    secs += utts[i]["seconds"] + (args.gap if len(parts) > 1 else 0.0)
                    i += 1
                cursors[c] = i
                if secs < target_s:
                    continue  # chapter exhausted before reaching the target length
                cid = f"long{int(target_s)}-{c}-{utterance_key(parts[0]['id'])[1]:04d}"
                frames = []
                for k, p in enumerate(parts):
                    with wave.open(p["wav"]) as w:
                        assert w.getframerate() == RATE and w.getnchannels() == 1 and w.getsampwidth() == 2
                        frames.append(w.readframes(w.getnframes()))
                    if k < len(parts) - 1:
                        frames.append(gap)
                path = os.path.join(wav_dir, f"{cid}.wav")
                with wave.open(path, "wb") as w:
                    w.setnchannels(1), w.setsampwidth(2), w.setframerate(RATE)
                    w.writeframes(b"".join(frames))
                out.append({"id": cid, "text": " ".join(p["text"] for p in parts), "seconds": round(secs, 2),
                            "wav": path, "parts": [p["id"] for p in parts]})
                made += 1
        print(f"target {target_s:.0f} s: {made} clips")
    json.dump(out, open(os.path.join(args.out, "manifest_long.json"), "w"), indent=1)
    for m in out:
        print(m["id"], m["seconds"], len(m["parts"]), "utterances")


if __name__ == "__main__":
    main()
