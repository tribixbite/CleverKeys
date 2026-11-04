#!/usr/bin/env python3
"""Write the two FRESH populations of apostrophe round 3 (kit 18; README.md "Pre-registration").

Q1 = UD English-GUM train + dev + test at a pinned commit (multi-genre: interviews, how-to,
     fiction, academic, vlogs, speeches, conversation). Never read by the static-LM builder or
     by any earlier apostrophe round. Licence CC BY-NC-SA 4.0 (parts CC BY): EVAL-ONLY, local,
     never committed, never shipped — the same footing as the EWT files of rounds 1-2.
Q2 = Ubuntu Dialogue Corpus `.tsv` members 125,001-150,000 in archive order of the local
     `ubuntu_dialogs.tgz` — disjoint from members 1-40,000 (`build_ubuntu_bigrams.py`'s
     default, the context-rescoring replay) and 100,001-125,000 (round 2's P2). Utterances
     pass `build_ubuntu_bigrams.usable_utterance`, exactly as P2's did.

Segmentation, tokenization and the 2..30-token bounds are the static-LM builder's own
(`scripts/build_static_lm.py`), as in rounds 1-2. Leakage control, borrowed from
`scripts/build_static_lm_fresh_eval.py`: a segment is DROPPED if its `sentence_key` occurs in any
English build input (Leipzig + Tatoeba, every segment), in a standing eval file
(`static-lm-eval/{heldout,ood_dev,ood_test}_en.txt`), in an earlier apostrophe population
(`ewt_train_en.txt`, `ubuntu_eval_en.txt`, Common Voice `sentence-collector.en.txt`), in the
2026-10-25 fresh LM populations (`static-lm-fresh/{ud,web}/ood_dev_en.txt`), or earlier in Q1/Q2.

Output: <out>/gum_en.txt and <out>/ubuntu_fresh_en.txt (one segment per line) — read by
`ApostropheContextEvalTest` with APOSTROPHE_EVAL_STAGE=4 APOSTROPHE_STAGE4_DIR=<out>. The script
prints only sizes and drop counts; it never counts `its`/`it's` (the bar is read once, by the test).

Usage: python3 ml/rtx-kits/18-apostrophe-context/build_round3_populations.py [--out DIR]
"""
from __future__ import annotations

import argparse
import hashlib
import sys
import tarfile
from pathlib import Path
from typing import Iterator, Set

APP = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(APP / "scripts"))
import build_static_lm as b  # noqa: E402  the tokenization contract
import build_static_lm_fresh_eval as fe  # noqa: E402  fetch + leakage-key helpers
import build_ubuntu_bigrams as ub  # noqa: E402  the Ubuntu utterance filter P2 used

GUM_COMMIT = "1fe635509c649e376dfb449d528424ab78f4eaee"  # UD_English-GUM master, 2026-05-06
GUM = tuple(
    fe._ud("UD_English-GUM", GUM_COMMIT, name, sha, "CC BY-NC-SA 4.0 (parts CC BY) — eval-only")
    for name, sha in (
        ("en_gum-ud-train.conllu", "88c410ca6a51ccc79f9536d75feb1633421691165cd191f8137f8b37569a0329"),
        ("en_gum-ud-dev.conllu", "61d02ce27a143e9749118b1b9c314ac3da40f64ec17a6aaff5fc7b42704f7910"),
        ("en_gum-ud-test.conllu", "cd96a285e7339f401f4803dd0f4f61109c51692e3ef6f9186a1f977544080b69"),
    )
)
UBUNTU = b.CACHE / "ubuntu_dialogs.tgz"
UBUNTU_SHA256 = "c4acbc55b7f83bc6a9a420960047360e352f50dfc76b3891f89b2ad0e2401354"  # measured 2026-11-04
UBUNTU_FIRST, UBUNTU_LAST = 125_001, 150_000  # 1-based `.tsv` member ordinals, inclusive
#: Earlier apostrophe-round populations and the 2026-10-25 fresh LM populations (all spent).
SPENT = (
    fe.STANDING_EVAL / "ewt_train_en.txt",
    fe.STANDING_EVAL / "ubuntu_eval_en.txt",
    b.CACHE / "commonvoice" / "sentence-collector.en.txt",
    b.CACHE / "static-lm-fresh" / "ud" / "ood_dev_en.txt",
    b.CACHE / "static-lm-fresh" / "web" / "ood_dev_en.txt",
)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def spent_keys(nfc: bool) -> Set[int]:
    keys: Set[int] = set()
    for p in SPENT:
        if not p.exists():
            raise SystemExit(f"spent population missing, cannot exclude it: {p}")
        for line in p.read_text(encoding="utf-8").splitlines():
            for seg in b.split_sentences(line, nfc):
                keys.add(hash(b.sentence_key(seg)))
    return keys


def ubuntu_texts(archive: Path) -> Iterator[str]:
    """Usable utterances of `.tsv` members UBUNTU_FIRST..UBUNTU_LAST (archive order)."""
    n = 0
    with tarfile.open(archive) as tf:
        for member in tf:
            if not member.isfile() or not member.name.endswith(".tsv"):
                continue
            n += 1
            if n < UBUNTU_FIRST:
                continue
            if n > UBUNTU_LAST:
                break
            handle = tf.extractfile(member)
            if handle is None:
                continue
            for line in handle.read().decode("utf-8", "replace").splitlines():
                cols = line.split("\t")
                if len(cols) >= 4 and cols[3].strip() and ub.usable_utterance(cols[3].strip()):
                    yield cols[3].strip()
    if n < UBUNTU_LAST:
        raise SystemExit(f"archive has only {n} .tsv members, need {UBUNTU_LAST}")


def write(out: Path, texts: Iterator[str], nfc: bool, excluded: Set[int], seen: Set[int]) -> None:
    kept = overlap = dup = 0
    with open(out, "w", encoding="utf-8") as fh:
        for text in texts:
            for seg in b.split_sentences(text, nfc):
                if not b.MIN_TOKENS <= b.word_count(b.tokenize(seg)) <= b.MAX_TOKENS:
                    continue
                k = hash(b.sentence_key(seg))
                if k in excluded:
                    overlap += 1
                    continue
                if k in seen:
                    dup += 1
                    continue
                seen.add(k)
                fh.write(" ".join(seg.split()) + "\n")
                kept += 1
    print(f"{out.name}: segments={kept} dropped_spent_or_build_overlap={overlap} dropped_duplicate={dup} "
          f"sha256={sha256_file(out)}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", type=Path, default=fe.STANDING_EVAL / "round3")
    a = ap.parse_args()
    a.out.mkdir(parents=True, exist_ok=True)
    cfg = b.CONFIGS["en"]
    if not UBUNTU.exists():
        raise SystemExit(f"missing {UBUNTU} (the archive rounds 1-2 used)")
    got = sha256_file(UBUNTU)
    if got != UBUNTU_SHA256:
        raise SystemExit(f"{UBUNTU} sha256 {got} != pinned {UBUNTU_SHA256}")
    excluded = fe.training_keys(cfg) | fe.standing_eval_keys("en", cfg.nfc) | spent_keys(cfg.nfc)
    seen: Set[int] = set()
    write(a.out / "gum_en.txt", (t for f in GUM for t in b.conllu_texts(fe.fetch(f))), cfg.nfc, excluded, seen)
    write(a.out / "ubuntu_fresh_en.txt", ubuntu_texts(UBUNTU), cfg.nfc, excluded, seen)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
