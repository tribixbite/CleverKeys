#!/usr/bin/env python3
"""Convert a Creative Commons legal-code HTML page to plain text, one line per paragraph.

Used once (2026-10-31) to produce LICENSES/CC-BY-2.0-FR.txt, because SPDX's license-list-data
has no CC BY 2.0 FR text. Only the legal-code body (`<div id="plain-text-marker">`) is kept;
words and their order are unchanged — whitespace is collapsed, `<li>` items are numbered the
way their `<ol type>` says (a., b., … or 1., 2., …) and indented two spaces per list level, so
the app's NoticeTextLayout shows them as their own paragraphs.

Usage:
    curl -sL https://creativecommons.org/licenses/by/2.0/fr/legalcode -o by20fr.html
    python3 scripts/cc_legalcode_to_text.py by20fr.html > body.txt

LICENSES/README.md records the two hand edits made to the output (the title/URL header and
the space removed before the final full stop).
"""
from __future__ import annotations

import re
import sys
from html.parser import HTMLParser


class LegalCodeText(HTMLParser):
    """Collects the paragraphs inside the legal-code body of a CC licence page."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.paragraphs: list[str] = []
        self._buffer: list[str] = []
        # One [type, count] per open <ol>, innermost last.
        self._lists: list[list[str | int]] = []
        self._active = False
        self._div_depth = 0

    def _flush(self) -> None:
        text = re.sub(r"\s+", " ", "".join(self._buffer)).strip()
        if text:
            self.paragraphs.append("  " * len(self._lists) + text)
        self._buffer = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attributes = dict(attrs)
        if tag == "div" and attributes.get("id") == "plain-text-marker":
            self._active = True
            self._div_depth = 0
        if not self._active:
            return
        if tag == "div":
            self._div_depth += 1
        if tag in ("p", "h2", "div", "br"):
            self._flush()
        elif tag == "ol":
            self._flush()
            self._lists.append([attributes.get("type") or "1", 0])
        elif tag == "li":
            self._flush()
            current = self._lists[-1]
            current[1] = int(current[1]) + 1
            number = int(current[1])
            label = chr(ord("a") + number - 1) if current[0] == "a" else str(number)
            self._buffer.append(f"{label}. ")

    def handle_endtag(self, tag: str) -> None:
        if not self._active:
            return
        if tag in ("p", "h2", "li"):
            self._flush()
        elif tag == "ol":
            self._flush()
            self._lists.pop()
        elif tag == "div":
            self._flush()
            self._div_depth -= 1
            if self._div_depth == 0:
                self._active = False

    def handle_data(self, data: str) -> None:
        if self._active:
            self._buffer.append(data)


def main(path: str) -> None:
    parser = LegalCodeText()
    with open(path, encoding="utf-8") as handle:
        parser.feed(handle.read())
    parser._flush()
    print("\n\n".join(parser.paragraphs))


if __name__ == "__main__":
    main(sys.argv[1])
