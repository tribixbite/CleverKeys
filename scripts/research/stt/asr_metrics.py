"""Word-error-rate helpers shared by the STT research scripts (2026-10-28).

Normalisation is deliberately simple and identical for every engine under test: lower-case,
hyphens to spaces, punctuation stripped except an apostrophe inside a word, whitespace
collapsed. It is NOT the Whisper English normaliser (no number-word mapping, no British/
American spelling fold), so absolute WERs here are a little higher than published figures and
are only comparable with each other. If `whisper_normalizer` is importable it is used instead
and the report says so.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

try:  # optional: pip install whisper-normalizer
    from whisper_normalizer.english import EnglishTextNormalizer  # type: ignore

    _WHISPER_NORMALISER: EnglishTextNormalizer | None = EnglishTextNormalizer()
except Exception:  # pragma: no cover - depends on the environment
    _WHISPER_NORMALISER = None

NORMALISER_NAME = "whisper_normalizer.english" if _WHISPER_NORMALISER else "basic"

_PUNCT = re.compile(r"[^\w\s']", re.UNICODE)
_APOS = re.compile(r"(?<!\w)'|'(?!\w)")


def normalise(text: str) -> list[str]:
    """Return the normalised word list of `text`."""
    if _WHISPER_NORMALISER is not None:
        return _WHISPER_NORMALISER(text).split()
    t = text.lower().replace("-", " ")
    t = _PUNCT.sub(" ", t)
    t = _APOS.sub(" ", t)
    return t.split()


@dataclass(frozen=True)
class EditCounts:
    substitutions: int
    deletions: int
    insertions: int
    reference_words: int

    @property
    def errors(self) -> int:
        return self.substitutions + self.deletions + self.insertions

    @property
    def wer(self) -> float:
        return self.errors / self.reference_words if self.reference_words else 0.0

    def __add__(self, other: "EditCounts") -> "EditCounts":
        return EditCounts(
            self.substitutions + other.substitutions,
            self.deletions + other.deletions,
            self.insertions + other.insertions,
            self.reference_words + other.reference_words,
        )


def edit_counts(reference: list[str], hypothesis: list[str]) -> EditCounts:
    """Levenshtein alignment with back-tracking into S/D/I counts (standard WER definition)."""
    n, m = len(reference), len(hypothesis)
    # dp[i][j] = (cost, s, d, i) for ref[:i] vs hyp[:j]
    dp: list[list[tuple[int, int, int, int]]] = [[(0, 0, 0, 0)] * (m + 1) for _ in range(n + 1)]
    for i in range(1, n + 1):
        dp[i][0] = (i, 0, i, 0)
    for j in range(1, m + 1):
        dp[0][j] = (j, 0, 0, j)
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            if reference[i - 1] == hypothesis[j - 1]:
                dp[i][j] = dp[i - 1][j - 1]
                continue
            c_sub, s, d, ins = dp[i - 1][j - 1]
            c_del, s2, d2, ins2 = dp[i - 1][j]
            c_ins, s3, d3, ins3 = dp[i][j - 1]
            best = min(c_sub, c_del, c_ins)
            if best == c_sub:
                dp[i][j] = (c_sub + 1, s + 1, d, ins)
            elif best == c_del:
                dp[i][j] = (c_del + 1, s2, d2 + 1, ins2)
            else:
                dp[i][j] = (c_ins + 1, s3, d3, ins3 + 1)
    _, s, d, ins = dp[n][m]
    return EditCounts(s, d, ins, n)


def wer_of(reference_text: str, hypothesis_text: str) -> EditCounts:
    return edit_counts(normalise(reference_text), normalise(hypothesis_text))
