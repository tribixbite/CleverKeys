#!/usr/bin/env python3
"""
Build the shipped static context language model (`CKLM` v1) for CleverKeys.

The model is a pruned word BIGRAM table — for each previous word, its top continuations with a
quantised conditional log-probability — plus a quantised log-unigram per vocabulary word. It is
the general-English prior behind `StaticContextLm` (tap-prediction context multiplier and the
next-word cold-start seed). See `docs/specs/context-learning-and-next-word.md` and
`scripts/data/PROVENANCE.md` for the sources and why they were chosen.

Pipeline (every step deterministic — the same inputs give byte-identical output):

 1. fetch     : sources are PINNED by sha256 in `SOURCES`; a missing file is downloaded, a hash
                mismatch is refused unless --allow-unpinned (Tatoeba's export rotates weekly, so
                a fresh download will not match the snapshot this model was built from).
 2. tokenize  : the on-device context contract (`NextWordPredictor.contextFromEditorText`) —
                runs of letters plus word-internal apostrophes/hyphens, lowercased, edge '/-
                trimmed; sentence-final `.` `?` `!` and line breaks end a sentence. Typographic
                apostrophes are normalised to ASCII first (the keyboard types `'`). A digit run
                BREAKS the bigram chain (on-device the digits are not a word either, but pairing
                the words either side of "3" would teach "have cats" from "have 3 cats").
 3. filter    : dedupe sentences (normalised text), keep 2..30-token sentences, split 90/10
                train/held-out by sentence hash (the held-out 10% is written for the pure-JVM
                eval and NEVER counted).
 4. artefacts : per corpus, drop words whose share exceeds `ARTEFACT_RATIO` x their wordfreq
                share (Tatoeba's "Tom"/"Mary" register: 452x / 174x) — they become chain breaks
                for that corpus only.
 5. vocab     : the shipped en lexicon (`dictionaries/en_enhanced.json`) UNION the contraction
                display forms, so every word the model can name is a word the app can show.
 6. combine   : weighted count sum, Leipzig x 1 + Tatoeba x W, W selected on the OUT-OF-DOMAIN
                DEV set (UD English-EWT dev; eval-only, never shipped) by next-word top-3 of the
                pruned model; ties go to the lower weight.
 7. prune     : weighted count >= MIN_COUNT, top TOP_K continuations per previous word.
 8. quantise  : -ln P in 1/16-nat steps, one byte (0..255 => P >= 1.2e-7).
 9. write     : `<out>.cklm` + `<out>.json` sidecar (counts, sha256, sources, weights) + the
                Tatoeba contributor list the CC BY 2.0 FR licence requires.

## CKLM v1 binary layout (little-endian)

    0   4  magic "CKLM"
    4   2  u16 version (1)
    6   2  u16 flags (0)
    8   4  language, ASCII, NUL-padded
   12   4  u32 vocabCount
   16   4  u32 prevCount
   20   4  u32 pairCount
   24   4  u32 prevIndexOffset      (absolute)
   28   4  u32 continuationsOffset (absolute)
   32   .. vocab, sorted by UTF-8 bytes, front-coded, one entry per word:
             u8 shared-prefix bytes, u8 suffix bytes, suffix (UTF-8), u8 -ln P(w) x 16
   prevIndexOffset: prevCount x 10 bytes, ascending word id:
             u32 word id, u32 continuation offset (relative to continuationsOffset), u16 count
   continuationsOffset: per previous word, continuations in ascending word id:
             varint id delta (the first is the absolute id), u8 -ln P(next|prev) x 16

P(next|prev) is c(prev,next) / c(prev,*) where c(prev,*) counts EVERY token that followed prev
inside a sentence (in-vocabulary or not), and P(w) is c(w) / N over every token — the same token
space on both sides, so the runtime's P(w|prev)/P(w) ratio is a genuine association measure and
the probability mass left after the listed continuations is an honest backoff.

Usage:
    python3 scripts/build_static_lm.py                       # build en from the pinned sources
    python3 scripts/build_static_lm.py --select-weight-only  # print the weight grid, write nothing

Requirements: Python 3.10+ standard library; `wordfreq` for the artefact filter (without it the
filter falls back to comparing each corpus against the OTHER corpus, recorded in the sidecar).

License: GPL-3.0 (part of CleverKeys)
"""

from __future__ import annotations

import argparse
import bz2
import hashlib
import io
import json
import math
import re
import struct
import sys
import tarfile
import urllib.request
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Iterable, Iterator

REPO = Path(__file__).resolve().parent.parent
CACHE = Path.home() / ".cache" / "cleverkeys-corpora"

FORMAT_MAGIC = b"CKLM"
FORMAT_VERSION = 1
HEADER_BYTES = 32

MIN_TOKENS, MAX_TOKENS = 2, 30
HELDOUT_FRACTION = 0.10
MIN_COUNT = 3.0
TOP_K = 20
QUANT_STEPS_PER_NAT = 16
ARTEFACT_RATIO = 20.0
# A word must be seen this often in a corpus before its share is judged: below it the ratio is
# dominated by sampling noise (and a rare word cannot distort the model much anyway).
ARTEFACT_MIN_COUNT = 50
WEIGHT_GRID = (0.0, 0.05, 0.1, 0.25, 0.5, 1.0)
SIZE_CAP_BYTES = 512 * 1024

# Apostrophe look-alikes the keyboard never types; normalised to ASCII before tokenizing.
APOSTROPHES = str.maketrans({"’": "'", "‘": "'", "ʼ": "'", "′": "'"})
SENTENCE_END = re.compile(r"[.?!\n]")
# A run of letters/apostrophes/hyphens (a candidate token), or one digit (a chain break).
RUN = re.compile(r"(?:[^\W\d_]|['\-])+|\d")


@dataclass(frozen=True)
class Source:
    """One pinned input file."""

    key: str
    filename: str
    url: str
    sha256: str
    license: str
    shipped: bool
    note: str


SOURCES: dict[str, Source] = {
    s.key: s
    for s in (
        Source(
            key="leipzig",
            filename="leipzig/eng-com_web-public_2018_300K.tar.gz",
            url="https://downloads.wortschatz-leipzig.de/corpora/eng-com_web-public_2018_300K.tar.gz",
            sha256="cc6a36b245e68523bc92b9d8131ac93592116b0167b3785418fcee9eb9c0dad7",
            license="CC BY 4.0 (Leipzig Corpora Collection download terms)",
            shipped=True,
            note="English web (.com) 2018, 300K sentences",
        ),
        Source(
            key="tatoeba",
            filename="tatoeba/eng_sentences_detailed.tsv.bz2",
            url="https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences_detailed.tsv.bz2",
            sha256="353d48de7905952cf6f1500f6a3158516ecf9e10cd844ba051982cfa4a11c111",
            license="CC BY 2.0 FR",
            shipped=True,
            note="weekly-rotating export; snapshot fetched 2026-09-26",
        ),
        Source(
            key="ood_dev",
            filename="ud/en_ewt-ud-dev.conllu",
            url="https://raw.githubusercontent.com/UniversalDependencies/UD_English-EWT/"
            "4a4d77f599ea53cc405f85d0cec4b2f14f81d42b/en_ewt-ud-dev.conllu",
            sha256="39239e0a60db3ae68f4b7036189f11b6692741d10ff8240dd91f74f2760d90f8",
            license="CC BY-SA 4.0 (eval-only, never shipped)",
            shipped=False,
            note="weight selection only",
        ),
        Source(
            key="ood_test",
            filename="ud/en_ewt-ud-test.conllu",
            url="https://raw.githubusercontent.com/UniversalDependencies/UD_English-EWT/"
            "4a4d77f599ea53cc405f85d0cec4b2f14f81d42b/en_ewt-ud-test.conllu",
            sha256="fa024f43dc5da3c5ac02563bc9bd0e974f46cbb1560823976a8f342a37dc494a",
            license="CC BY-SA 4.0 (eval-only, never shipped)",
            shipped=False,
            note="gate evaluation only (read by the Kotlin eval)",
        ),
    )
}


# ── fetch ─────────────────────────────────────────────────────────────────────────────────────


def sha256_of(path: Path) -> str:
    """Streaming sha256 of a file."""
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def ensure_source(src: Source, cache: Path, allow_unpinned: bool, offline: bool) -> tuple[Path, str]:
    """Return the local path of [src] and its actual sha256, downloading it when absent.

    Refuses (SystemExit) a file whose hash differs from the pin unless [allow_unpinned] — the
    output of such a build is a different model, and the sidecar records the hash it really used.
    """
    path = cache / src.filename
    if not path.exists():
        if offline:
            raise SystemExit(f"missing {path} and --offline given")
        path.parent.mkdir(parents=True, exist_ok=True)
        print(f"[fetch] {src.url}")
        tmp = path.with_suffix(path.suffix + ".part")
        with urllib.request.urlopen(src.url, timeout=120) as resp, open(tmp, "wb") as out:
            while chunk := resp.read(1 << 20):
                out.write(chunk)
        tmp.rename(path)
    actual = sha256_of(path)
    if actual != src.sha256:
        msg = f"{path}: sha256 {actual} != pinned {src.sha256}"
        if not allow_unpinned:
            raise SystemExit(msg + " (refusing; pass --allow-unpinned to build from it anyway)")
        print(f"[warn] {msg} — building UNPINNED")
    return path, actual


# ── tokenization (the on-device contract) ──────────────────────────────────────────────────────


def split_sentences(text: str) -> list[str]:
    """Split on sentence-final punctuation / line breaks, exactly where the device resets context."""
    return [s for s in SENTENCE_END.split(text.translate(APOSTROPHES)) if s.strip()]


def tokenize(segment: str) -> list[str | None]:
    """Tokens of one sentence segment; `None` marks a chain break (a digit run).

    Mirrors `NextWordPredictor.contextFromEditorText`: a token is a maximal run of letters,
    apostrophes and hyphens with edge apostrophes/hyphens trimmed, kept only if it contains a
    letter, lowercased. Consecutive breaks collapse to one.
    """
    out: list[str | None] = []
    for m in RUN.finditer(segment):
        run = m.group(0)
        if run.isdigit():
            if out and out[-1] is not None:
                out.append(None)
            continue
        tok = run.strip("'-")
        if tok and any(c.isalpha() for c in tok):
            out.append(sys.intern(tok.lower()))
    while out and out[-1] is None:
        out.pop()
    while out and out[0] is None:
        out.pop(0)
    return out


def word_count(tokens: list[str | None]) -> int:
    return sum(1 for t in tokens if t is not None)


def sentence_key(segment: str) -> str:
    """Dedup + split key: lowercased, whitespace-collapsed segment text."""
    return " ".join(segment.lower().split())


def is_heldout(key: str) -> bool:
    """Deterministic 90/10 split by sentence hash — the Kotlin eval reads what this writes."""
    return int(hashlib.sha1(key.encode("utf-8")).hexdigest()[:8], 16) / 0xFFFFFFFF < HELDOUT_FRACTION


# ── corpus readers ─────────────────────────────────────────────────────────────────────────────


def leipzig_lines(path: Path) -> Iterator[tuple[str, str]]:
    """(sentence, contributor) from a Leipzig `*-sentences.txt` inside the tarball."""
    with tarfile.open(path, "r:gz") as tar:
        member = next(m for m in tar.getmembers() if m.name.endswith("-sentences.txt"))
        fh = tar.extractfile(member)
        assert fh is not None
        for raw in io.TextIOWrapper(fh, encoding="utf-8", errors="replace"):
            cols = raw.rstrip("\n").split("\t", 1)
            if len(cols) == 2:
                yield cols[1], ""


def tatoeba_lines(path: Path) -> Iterator[tuple[str, str]]:
    """(sentence, username) from `eng_sentences_detailed.tsv.bz2` (id, lang, text, user, ...)."""
    with bz2.open(path, "rt", encoding="utf-8", errors="replace") as fh:
        for raw in fh:
            cols = raw.rstrip("\n").split("\t")
            if len(cols) >= 4:
                user = cols[3] if cols[3] not in ("", "\\N") else ""
                yield cols[2], user


def conllu_texts(path: Path) -> Iterator[str]:
    """Raw `# text = ...` lines of a UD .conllu — the surface string, not UD's split tokens
    (UD splits `don't` into `do` + `n't`, which is not what a keyboard sees)."""
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            if line.startswith("# text = "):
                yield line[len("# text = "):].rstrip("\n")


# ── counting ──────────────────────────────────────────────────────────────────────────────────


@dataclass
class CorpusCounts:
    """Train-split counts for one corpus."""

    name: str
    unigrams: Counter = field(default_factory=Counter)  # every token
    successors: Counter = field(default_factory=Counter)  # c(prev, *) over every following token
    bigrams: Counter = field(default_factory=Counter)  # (prev, next), both in vocab
    tokens: int = 0
    sentences: int = 0
    heldout: int = 0
    duplicates: int = 0
    contributors: set[str] = field(default_factory=set)


def iter_segments(
    lines: Iterable[tuple[str, str]], seen: set[str]
) -> Iterator[tuple[str, str, list[str | None], bool]]:
    """(segment text, contributor, tokens, heldout) for every kept, non-duplicate segment."""
    for text, who in lines:
        for seg in split_sentences(text):
            toks = tokenize(seg)
            if not MIN_TOKENS <= word_count(toks) <= MAX_TOKENS:
                continue
            key = sentence_key(seg)
            if key in seen:
                yield seg, who, [], False  # duplicate marker (empty tokens)
                continue
            seen.add(key)
            yield seg, who, toks, is_heldout(key)


def count_corpus(
    name: str,
    lines: Iterable[tuple[str, str]],
    vocab: set[str],
    seen: set[str],
    heldout_sink: Callable[[str], None],
) -> tuple[CorpusCounts, list[list[str | None]]]:
    """Count one corpus; return counts plus its train sentences (kept for the artefact re-count)."""
    c = CorpusCounts(name)
    train: list[list[str | None]] = []
    for seg, who, toks, held in iter_segments(lines, seen):
        if not toks:
            c.duplicates += 1
            continue
        if held:
            c.heldout += 1
            heldout_sink(" ".join(seg.split()))
            continue
        c.sentences += 1
        if who:
            c.contributors.add(who)
        train.append(tuple(toks))  # type: ignore[arg-type]
    recount(c, train, vocab, frozenset())
    return c, train


def recount(c: CorpusCounts, train: list[list[str | None]], vocab: set[str], artefacts: frozenset[str]) -> None:
    """(Re)build [c]'s counters from its train sentences, treating [artefacts] as chain breaks."""
    c.unigrams.clear()
    c.successors.clear()
    c.bigrams.clear()
    c.tokens = 0
    uni, succ, bi = c.unigrams, c.successors, c.bigrams
    for toks in train:
        prev: str | None = None
        for t in toks:
            if t is None or t in artefacts:
                prev = None
                continue
            uni[t] += 1
            c.tokens += 1
            if prev is not None:
                succ[prev] += 1
                if prev in vocab and t in vocab:
                    bi[(prev, t)] += 1
            prev = t


def find_artefacts(c: CorpusCounts, reference: Callable[[str], float]) -> dict[str, float]:
    """Words whose share in [c] exceeds ARTEFACT_RATIO x the reference share → ratio."""
    out: dict[str, float] = {}
    total = max(1, c.tokens)
    for w, n in c.unigrams.items():
        if n < ARTEFACT_MIN_COUNT:
            continue
        ref = reference(w)
        share = n / total
        ratio = share / ref if ref > 0 else math.inf
        if ratio > ARTEFACT_RATIO:
            out[w] = ratio
    return out


# ── model ─────────────────────────────────────────────────────────────────────────────────────


@dataclass
class Model:
    """A pruned bigram table over a vocabulary, with weighted counts."""

    table: dict[str, list[tuple[str, float]]]  # prev → [(next, count)] top-K, count-desc
    successors: dict[str, float]
    unigrams: dict[str, float]
    total_tokens: float


def combine(corpora: list[tuple[CorpusCounts, float]]) -> tuple[Counter, Counter, Counter, float]:
    uni: Counter = Counter()
    succ: Counter = Counter()
    bi: Counter = Counter()
    n = 0.0
    for c, w in corpora:
        if w == 0.0:
            continue
        for k, v in c.unigrams.items():
            uni[k] += v * w
        for k, v in c.successors.items():
            succ[k] += v * w
        for k, v in c.bigrams.items():
            bi[k] += v * w
        n += c.tokens * w
    return uni, succ, bi, n


def prune(uni: Counter, succ: Counter, bi: Counter, n: float) -> Model:
    by_prev: dict[str, list[tuple[str, float]]] = {}
    for (a, b), cnt in bi.items():
        if cnt >= MIN_COUNT - 1e-9:
            by_prev.setdefault(a, []).append((b, cnt))
    table = {a: sorted(v, key=lambda kv: (-kv[1], kv[0]))[:TOP_K] for a, v in by_prev.items()}
    return Model(table=table, successors=dict(succ), unigrams=dict(uni), total_tokens=n)


def quantise(p: float) -> int:
    """-ln p in 1/16-nat steps, clamped to a byte."""
    if p <= 0.0:
        return 255
    return max(0, min(255, int(round(-math.log(p) * QUANT_STEPS_PER_NAT))))


def varint(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def encode(model: Model, lang: str) -> tuple[bytes, dict[str, int]]:
    """Serialise [model] as CKLM v1. Returns (bytes, stats)."""
    words = sorted(
        {a for a in model.table} | {b for v in model.table.values() for b, _ in v},
        key=lambda w: w.encode("utf-8"),
    )
    wid = {w: i for i, w in enumerate(words)}
    body = bytearray()
    prev_bytes = b""
    for w in words:
        wb = w.encode("utf-8")
        common = 0
        limit = min(len(prev_bytes), len(wb), 255)
        while common < limit and prev_bytes[common] == wb[common]:
            common += 1
        suffix = wb[common:]
        if len(suffix) > 255:
            raise ValueError(f"word too long for CKLM v1: {w!r}")
        body += bytes([common, len(suffix)]) + suffix
        body.append(quantise(model.unigrams.get(w, 0.0) / model.total_tokens))
        prev_bytes = wb

    prev_ids = sorted(wid[a] for a in model.table)
    conts = bytearray()
    index = bytearray()
    pairs = 0
    for pid in prev_ids:
        a = words[pid]
        entries = sorted(model.table[a], key=lambda kv: wid[kv[0]])
        total = model.successors[a]
        index += struct.pack("<IIH", pid, len(conts), len(entries))
        last = 0
        for b, cnt in entries:
            q = quantise(cnt / total)
            conts += varint(wid[b] - last) + bytes([q])
            last = wid[b]
            pairs += 1

    prev_index_offset = HEADER_BYTES + len(body)
    conts_offset = prev_index_offset + len(index)
    lang_bytes = lang.encode("ascii")[:4].ljust(4, b"\0")
    header = FORMAT_MAGIC + struct.pack(
        "<HH4sIIIII",
        FORMAT_VERSION,
        0,
        lang_bytes,
        len(words),
        len(prev_ids),
        pairs,
        prev_index_offset,
        conts_offset,
    )
    assert len(header) == HEADER_BYTES
    blob = bytes(header + body + index + conts)
    return blob, {"vocab": len(words), "prevs": len(prev_ids), "pairs": pairs}


# ── eval (weight selection only — the gate is measured in Kotlin through UnifiedScore) ────────


def next_word_topk(model: Model, sentences: list[list[str | None]], vocab: set[str]) -> tuple[float, float, int]:
    """Next-word top-1 / top-3 of the PRUNED model over in-vocab targets with a previous word."""
    n = hit1 = hit3 = 0
    for toks in sentences:
        prev: str | None = None
        for t in toks:
            if t is None:
                prev = None
                continue
            if prev is not None and t in vocab:
                n += 1
                cands = model.table.get(prev, [])
                if cands and cands[0][0] == t:
                    hit1 += 1
                if any(b == t for b, _ in cands[:3]):
                    hit3 += 1
            prev = t
    return (100.0 * hit1 / max(1, n), 100.0 * hit3 / max(1, n), n)


# ── vocabulary ─────────────────────────────────────────────────────────────────────────────────


def load_vocab(dict_dir: Path) -> tuple[set[str], set[str]]:
    """(lexicon, contraction display forms) — the model may only name words from their union."""
    lexicon = {w.lower() for w in json.loads((dict_dir / "en_enhanced.json").read_text("utf-8"))}
    forms: set[str] = set()
    for name in ("contractions_en.json", "contractions_non_paired.json"):
        for v in json.loads((dict_dir / name).read_text("utf-8")).values():
            forms.add(v.lower())
    for entries in json.loads((dict_dir / "contraction_pairings.json").read_text("utf-8")).values():
        for e in entries:
            forms.add(e["contraction"].lower())
    return lexicon, forms


def wordfreq_reference() -> tuple[Callable[[str], float] | None, str]:
    try:
        import wordfreq  # type: ignore[import-not-found]
    except ImportError:
        return None, "unavailable"
    from importlib.metadata import version

    return (lambda w: float(wordfreq.word_frequency(w, "en"))), f"wordfreq {version('wordfreq')}"


def corpus_reference(other: CorpusCounts) -> Callable[[str], float]:
    total = max(1, other.tokens)
    return lambda w: other.unigrams.get(w, 0) / total


# ── main ─────────────────────────────────────────────────────────────────────────────────────


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--lang", default="en")
    ap.add_argument("--cache", type=Path, default=CACHE)
    ap.add_argument("--out-dir", type=Path, default=REPO / "src/main/assets/lm")
    ap.add_argument("--contributors", type=Path, default=REPO / "scripts/data/tatoeba-contributors-en.txt")
    ap.add_argument("--eval-dir", type=Path, default=CACHE / "static-lm-eval",
                    help="local-only held-out + OOD sentence files for the Kotlin eval")
    ap.add_argument("--weight", type=float, default=None, help="skip selection, use this Tatoeba weight")
    ap.add_argument("--select-weight-only", action="store_true")
    ap.add_argument("--allow-unpinned", action="store_true")
    ap.add_argument("--offline", action="store_true")
    args = ap.parse_args()
    if args.lang != "en":
        raise SystemExit("only en is built today (other languages keep their bigram seeds)")

    paths: dict[str, Path] = {}
    hashes: dict[str, str] = {}
    for key, src in SOURCES.items():
        paths[key], hashes[key] = ensure_source(src, args.cache, args.allow_unpinned, args.offline)

    lexicon, forms = load_vocab(REPO / "src/main/assets/dictionaries")
    vocab = lexicon | forms
    print(f"[vocab] lexicon {len(lexicon)} + contraction forms {len(forms)} -> {len(vocab)}")

    args.eval_dir.mkdir(parents=True, exist_ok=True)
    heldout_path = args.eval_dir / f"heldout_{args.lang}.txt"
    seen: set[str] = set()
    with open(heldout_path, "w", encoding="utf-8") as held:
        sink = lambda s: held.write(s + "\n")  # noqa: E731
        leipzig, leipzig_train = count_corpus("leipzig", leipzig_lines(paths["leipzig"]), vocab, seen, sink)
        tatoeba, tatoeba_train = count_corpus("tatoeba", tatoeba_lines(paths["tatoeba"]), vocab, seen, sink)
    del seen
    for c in (leipzig, tatoeba):
        print(f"[{c.name}] train sentences {c.sentences}, held-out {c.heldout}, "
              f"duplicates {c.duplicates}, tokens {c.tokens}")

    # OOD files — surface text, same sentence splitting, never counted.
    ood: dict[str, list[list[str | None]]] = {}
    for key in ("ood_dev", "ood_test"):
        out = args.eval_dir / f"{key}_{args.lang}.txt"
        sents: list[list[str | None]] = []
        with open(out, "w", encoding="utf-8") as fh:
            for text in conllu_texts(paths[key]):
                for seg in split_sentences(text):
                    toks = tokenize(seg)
                    if MIN_TOKENS <= word_count(toks) <= MAX_TOKENS:
                        fh.write(" ".join(seg.split()) + "\n")
                        sents.append(toks)
        ood[key] = sents
        print(f"[{key}] {len(sents)} sentences -> {out}")

    # Artefact filter, per corpus.
    ref, ref_name = wordfreq_reference()
    artefacts: dict[str, dict[str, float]] = {}
    for c, train, other in ((leipzig, leipzig_train, tatoeba), (tatoeba, tatoeba_train, leipzig)):
        reference = ref if ref is not None else corpus_reference(other)
        found = find_artefacts(c, reference)
        artefacts[c.name] = found
        top = sorted(found.items(), key=lambda kv: -c.unigrams[kv[0]])[:15]
        print(f"[artefacts:{c.name}] {len(found)} words dropped (reference: "
              f"{ref_name if ref else 'other corpus'}); most frequent: "
              + ", ".join(f"{w} {r:.0f}x" for w, r in top))
        recount(c, train, vocab, frozenset(found))
    del leipzig_train, tatoeba_train

    # Weight selection on OOD dev (pre-registered: max next-word top-3, ties → lower weight).
    if args.weight is not None:
        weight = args.weight
        grid: list[dict[str, float]] = []
    else:
        grid = []
        for w in WEIGHT_GRID:
            m = prune(*combine([(leipzig, 1.0), (tatoeba, w)]))
            t1, t3, n = next_word_topk(m, ood["ood_dev"], vocab)
            pairs = sum(len(v) for v in m.table.values())
            grid.append({"weight": w, "top1": round(t1, 3), "top3": round(t3, 3), "n": n, "pairs": pairs})
            print(f"[select] tatoeba weight {w:<5} OOD-dev next-word top1 {t1:6.2f}%  top3 {t3:6.2f}%  "
                  f"(n={n}, pairs={pairs})")
        best = max(grid, key=lambda g: (g["top3"], -g["weight"]))
        weight = best["weight"]
        print(f"[select] chosen weight {weight}")
    if args.select_weight_only:
        return 0

    model = prune(*combine([(leipzig, 1.0), (tatoeba, weight)]))
    blob, stats = encode(model, args.lang)
    if len(blob) > SIZE_CAP_BYTES:
        raise SystemExit(f"model is {len(blob)} bytes, over the {SIZE_CAP_BYTES}-byte cap")
    t1, t3, n = next_word_topk(model, ood["ood_test"], vocab)
    print(f"[model] {stats} {len(blob)} bytes; OOD-test next-word top1 {t1:.2f}% top3 {t3:.2f}% (n={n})")

    args.out_dir.mkdir(parents=True, exist_ok=True)
    cklm = args.out_dir / f"{args.lang}.cklm"
    cklm.write_bytes(blob)
    digest = hashlib.sha256(blob).hexdigest()

    # Tatoeba contributors whose sentences were counted (CC BY 2.0 FR attribution).
    names = sorted(tatoeba.contributors, key=lambda s: (s.lower(), s)) if weight > 0 else []
    args.contributors.parent.mkdir(parents=True, exist_ok=True)
    with open(args.contributors, "w", encoding="utf-8") as fh:
        fh.write("# Tatoeba contributors whose English sentences were counted into\n")
        fh.write(f"# src/main/assets/lm/{args.lang}.cklm (CC BY 2.0 FR, https://tatoeba.org).\n")
        fh.write(f"# Snapshot: {SOURCES['tatoeba'].filename.split('/')[-1]} sha256 {hashes['tatoeba']}\n")
        fh.write("# Generated by scripts/build_static_lm.py — do not edit by hand.\n")
        for name in names:
            fh.write(name + "\n")

    sidecar = {
        "format": "CKLM",
        "version": FORMAT_VERSION,
        "language": args.lang,
        "vocab": stats["vocab"],
        "prevs": stats["prevs"],
        "pairs": stats["pairs"],
        "bytes": len(blob),
        "sha256": digest,
        "model": {
            "order": 2,
            "minCount": MIN_COUNT,
            "topK": TOP_K,
            "quantStepsPerNat": QUANT_STEPS_PER_NAT,
            "vocabulary": "dictionaries/en_enhanced.json UNION contraction display forms",
        },
        "corpus": {
            "sentenceTokens": [MIN_TOKENS, MAX_TOKENS],
            "heldoutFraction": HELDOUT_FRACTION,
            "split": "sha1(normalised sentence)[:8] / 0xFFFFFFFF < heldoutFraction",
            "trainSentences": {c.name: c.sentences for c in (leipzig, tatoeba)},
            "trainTokens": {c.name: c.tokens for c in (leipzig, tatoeba)},
            "weights": {"leipzig": 1.0, "tatoeba": weight},
            "weightGrid": grid,
            "weightRule": "max OOD-dev (UD EWT dev) next-word top-3 of the pruned model; ties -> lower weight",
        },
        "artefactFilter": {
            "ratio": ARTEFACT_RATIO,
            "minCount": ARTEFACT_MIN_COUNT,
            "reference": ref_name if ref else "the other corpus",
            "dropped": {k: sorted(v) for k, v in artefacts.items()},
        },
        "sources": [
            {
                "key": s.key,
                "url": s.url,
                "sha256": hashes[s.key],
                "pinned": hashes[s.key] == s.sha256,
                "license": s.license,
                "shipped": s.shipped,
                "note": s.note,
            }
            for s in SOURCES.values()
        ],
        "tatoebaContributors": len(names),
    }
    (args.out_dir / f"{args.lang}.json").write_text(json.dumps(sidecar, indent=2, ensure_ascii=False) + "\n", "utf-8")
    print(f"[write] {cklm} sha256 {digest}; {len(names)} Tatoeba contributors -> {args.contributors}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
