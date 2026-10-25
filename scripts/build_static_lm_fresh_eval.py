#!/usr/bin/env python3
"""
Write the FRESH evaluation populations of the 2026-10-25 static-LM ratio-shape re-run
(pre-registration: `docs/eval/2026-10-25-static-lm-floor-one-prereg.md`).

Two populations per shipped language, neither used by any earlier static-LM round:

- ``ud``  — a UD treebank never read before: the language's PUD treebank (1,000 news/wiki
  sentences, the one treebank all six languages share), except Italian, whose PUD was its gate
  test split (spent), which uses ParlaMint (train + test) + Valico (CC BY-SA 4.0 both).
- ``web`` — a 10K Leipzig *web* corpus of a different national domain than the language's
  training web corpus (Sweden has none, so a different year), never an input of any build.

Leakage control (eval side — the shipped models are not rebuilt): every candidate sentence is
segmented and tokenized exactly as `build_static_lm.py` does (same `split_sentences`, `tokenize`,
2..30-token bounds, NFC per config) and DROPPED if its `sentence_key` occurs anywhere in the
language's build inputs (Leipzig, Tatoeba, second Leipzig corpus — every segment, held-out or
not, any length), in an earlier eval file of that language (`static-lm-eval/ood_{dev,test}`,
`heldout`), or earlier in the fresh populations (``ud`` is written first, so ``web`` never
repeats a ``ud`` sentence).

Output: ``~/.cache/cleverkeys-corpora/static-lm-fresh/<pop>/ood_dev_<lang>.txt`` (one surface
sentence per line), read by `StaticLmTapEvalTest` with ``STATIC_LM_EVAL_SPLIT=dev`` and
``STATIC_LM_EVAL_DIR=<that dir>``; ``heldout_<lang>.txt`` there is a symlink to the standing one
(the harness checks it exists; dev mode never reads it). Local only, never committed or shipped.

Usage: python3 scripts/build_static_lm_fresh_eval.py [--lang en,de,...]

License: GPL-3.0 (part of CleverKeys)
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_static_lm as b  # noqa: E402  (the builder is the tokenization contract)

LANGS = ("en", "de", "fr", "it", "pt", "sv")
OUT = b.CACHE / "static-lm-fresh"
STANDING_EVAL = b.CACHE / "static-lm-eval"


@dataclass(frozen=True)
class Fresh:
    """One pinned fresh input file (eval-only, never shipped)."""

    filename: str  # under b.CACHE
    url: str
    sha256: str
    license: str


def _ud(repo: str, commit: str, name: str, sha256: str, license: str) -> Fresh:
    return Fresh(f"ud/{name}", f"https://raw.githubusercontent.com/UniversalDependencies/{repo}/{commit}/{name}",
                 sha256, license)


def _web(corpus: str, sha256: str) -> Fresh:
    return Fresh(f"leipzig/{corpus}.tar.gz", f"https://downloads.wortschatz-leipzig.de/corpora/{corpus}.tar.gz",
                 sha256, "CC BY 4.0 (Leipzig Corpora Collection download terms)")


# Pinned 2026-10-25. Selection rule (registered before any of these was evaluated): see the
# module docstring and the pre-registration doc's "Populations" section.
UD: dict[str, tuple[Fresh, ...]] = {
    "en": (_ud("UD_English-PUD", "f16eba4ae7f3d161870ed320676c5088b8fa476c", "en_pud-ud-test.conllu",
               "c80584f2bc2b31d5bada78a1136f9feec7ac49e5e18898db02dea434b5b8f0aa", "CC BY-SA 3.0"),),
    "de": (_ud("UD_German-PUD", "4defdfb1ad1b5f0d68f430144a27bdbf7ac04734", "de_pud-ud-test.conllu",
               "a530bdb50349bbd7c13706b6a759a9d73e8f514fff41fbf27149e914b0c3e723", "CC BY-SA 3.0"),),
    "fr": (_ud("UD_French-PUD", "db260db10fe728853c549760801229ef4e7b16e1", "fr_pud-ud-test.conllu",
               "4dfed37b83d76e77fd2e9963d0be00d723e9a010e7e2a746f8b7640c48063c10", "CC BY-SA 3.0"),),
    "it": (_ud("UD_Italian-ParlaMint", "3c2f2af215b5816fe2b7bade79b8980d052122be", "it_parlamint-ud-train.conllu",
               "2b83d41e5eacfab1511245e9032a12a412d273cbb153bcdef2cc5ac793d91aea", "CC BY-SA 4.0"),
           _ud("UD_Italian-ParlaMint", "3c2f2af215b5816fe2b7bade79b8980d052122be", "it_parlamint-ud-test.conllu",
               "aa0e0fc94683fef4d4ee73a387e747c31b1639c1123570b4a536174af2327229", "CC BY-SA 4.0"),
           _ud("UD_Italian-Valico", "8d60b2c84b8a877d900b2a75bb7644dfd84eb185", "it_valico-ud-test.conllu",
               "a9f026703c1f3321eeba060460a1dac9d49677a641a7f03e78bcf8103667815a", "CC BY-SA 4.0")),
    "pt": (_ud("UD_Portuguese-PUD", "0fdcc057bcc72a71b260ef58755778551508c77a", "pt_pud-ud-test.conllu",
               "e2bd4012a3d0e17dc7ba5d699dd17e05f57986901bb24df07bd0a271cf23178c", "CC BY-SA 3.0"),),
    "sv": (_ud("UD_Swedish-PUD", "1290a67471931e543b4d2a734829f9177c604e94", "sv_pud-ud-test.conllu",
               "4f978396f363c85a9c7ece193327529e4cdbe9ce0f6ad807eb8846e865043fff", "CC BY-SA 4.0"),),
}

WEB: dict[str, Fresh] = {
    "en": _web("eng-uk_web-public_2018_10K", "42e2445bf9804566f11f0cec36ab74dfc30d824f272acca470dcadad4769303b"),
    "de": _web("deu-at_web_2019_10K", "42836e31b1fd9224b3a958b3cc2e070efaa92d0d456802bb018839d4984f865b"),
    "fr": _web("fra-be_web_2013_10K", "0783ecef0a104d8d948e79d894f3e86ac4584dfe8f01f622dde353c621097109"),
    "it": _web("ita-ch_web_2016_10K", "387de1ffd99d9f01250b0a1652c6ac14454a229005f59171b8ad9cf2bbb5bb8c"),
    "pt": _web("por-mz_web_2016_10K", "f69e3b40e79d428648ea4d731b11b1aab829763d6e5d44d49225256895600b8c"),
    "sv": _web("swe-se_web_2020_10K", "6caf5299675b033704d2effb29d40f85247394e66e1de20e329ee9893d0f055b"),
}


def fetch(f: Fresh) -> Path:
    """The local path of [f], downloaded when absent; refuses a hash mismatch."""
    src = b.Source(key="fresh", filename=f.filename, url=f.url, sha256=f.sha256, license=f.license,
                   shipped=False, note="fresh eval population (2026-10-25)")
    path, _ = b.ensure_source(src, b.CACHE, allow_unpinned=False, offline=False)
    return path


def training_keys(cfg: b.LangConfig) -> set[int]:
    """hash(sentence_key) of every segment of every build input of [cfg] (any length, any split)."""
    keys: set[int] = set()
    inputs: list[tuple[str, Iterator[tuple[str, str]]]] = []
    lp, _ = b.ensure_source(cfg.leipzig, b.CACHE, False, True)
    inputs.append(("leipzig", b.leipzig_lines(lp)))
    tp, _ = b.ensure_source(cfg.tatoeba, b.CACHE, False, True)
    inputs.append(("tatoeba", b.tatoeba_lines(tp)))
    if cfg.leipzig2 is not None:
        l2, _ = b.ensure_source(cfg.leipzig2, b.CACHE, False, True)
        inputs.append(("leipzig2", b.leipzig_lines(l2)))
    for _, lines in inputs:
        for text, _who in lines:
            for seg in b.split_sentences(text, cfg.nfc):
                keys.add(hash(b.sentence_key(seg)))
    return keys


def standing_eval_keys(lang: str, nfc: bool) -> set[int]:
    """Keys of the earlier eval files of [lang] (already one sentence per line)."""
    keys: set[int] = set()
    for name in (f"ood_dev_{lang}.txt", f"ood_test_{lang}.txt", f"heldout_{lang}.txt"):
        p = STANDING_EVAL / name
        if p.exists():
            for line in p.read_text(encoding="utf-8").splitlines():
                for seg in b.split_sentences(line, nfc):
                    keys.add(hash(b.sentence_key(seg)))
    return keys


def write_population(lang: str, pop: str, texts: Iterator[str], nfc: bool, excluded: set[int],
                     seen: set[int]) -> None:
    out_dir = OUT / pop
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / f"ood_dev_{lang}.txt"
    kept = dropped_overlap = dropped_dup = tokens = 0
    with open(out, "w", encoding="utf-8") as fh:
        for text in texts:
            for seg in b.split_sentences(text, nfc):
                toks = b.tokenize(seg)
                if not b.MIN_TOKENS <= b.word_count(toks) <= b.MAX_TOKENS:
                    continue
                k = hash(b.sentence_key(seg))
                if k in excluded:
                    dropped_overlap += 1
                    continue
                if k in seen:
                    dropped_dup += 1
                    continue
                seen.add(k)
                fh.write(" ".join(seg.split()) + "\n")
                kept += 1
                tokens += b.word_count(toks)
    held = out_dir / f"heldout_{lang}.txt"
    if not held.exists():
        held.symlink_to(STANDING_EVAL / f"heldout_{lang}.txt")
    print(f"FRESH lang={lang} pop={pop} sentences={kept} tokens={tokens} "
          f"dropped_build_or_spent_overlap={dropped_overlap} dropped_duplicate={dropped_dup} -> {out}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--lang", default=",".join(LANGS))
    args = ap.parse_args()
    for lang in args.lang.split(","):
        cfg = b.CONFIGS[lang]
        excluded = training_keys(cfg) | standing_eval_keys(lang, cfg.nfc)
        seen: set[int] = set()
        ud_texts = (t for f in UD[lang] for t in b.conllu_texts(fetch(f)))
        write_population(lang, "ud", ud_texts, cfg.nfc, excluded, seen)
        web_texts = (s for s, _ in b.leipzig_lines(fetch(WEB[lang])))
        write_population(lang, "web", web_texts, cfg.nfc, excluded, seen)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
