#!/usr/bin/env python3
"""Export per-locale review sheets for native-speaker translation review.

Every translation in res/values-*/strings.xml was produced or revised by a model; none has
been native-speaker reviewed (docs/i18n/*.md). This script turns the resources into one CSV
per locale that a reviewer can open in any spreadsheet app, with the English source beside
each translation and the known open questions pre-filled in a `flag` column.

Columns:
  key          resource name (plural items as `name#quantity`)
  area         review grouping: command palette / keyboard keys / settings / other
  english      the source text from res/values/strings.xml
  translation  the locale's current text ("" when the locale does not override it)
  changed      "yes" when the key's text in this locale differs from `--since`
  flag         open question for this key from docs/i18n/review-flags.json ("" when none)
  verdict      left empty for the reviewer: ok / fix
  correction   left empty for the reviewer

Rows are sorted flagged first, then changed, then by area and key, so a reviewer with
limited time reads the open questions before the bulk.

Usage:
  python3 scripts/export_translation_review.py --out <dir> [--since <git-ref>] [--locale fa ...]
"""
from __future__ import annotations

import argparse
import csv
import json
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
RES = REPO / "res"
FLAGS_FILE = REPO / "docs" / "i18n" / "review-flags.json"

# Key-prefix → review area. First match wins; order matters (cmd_ before the generic rest).
AREAS: list[tuple[str, str]] = [
    ("cmd_", "command palette"),
    ("command_category_", "command palette"),
    ("key_descr_", "keyboard keys"),
    ("extra_key_", "keyboard keys"),
    ("settings_", "settings"),
    ("pref_", "settings"),
]


def area_of(key: str) -> str:
    for prefix, area in AREAS:
        if key.startswith(prefix):
            return area
    return "other"


def element_text(el: ET.Element) -> str:
    """Resource text including inline markup such as <xliff:g>, as the reviewer sees it."""
    parts = [el.text or ""]
    for child in el:
        parts.append(ET.tostring(child, encoding="unicode"))
    return "".join(parts).strip()


def parse_strings(xml: str) -> dict[str, str]:
    """Translatable <string> and <plurals> items, keyed as in the CSV `key` column."""
    root = ET.fromstring(xml)
    out: dict[str, str] = {}
    for el in root:
        name = el.get("name")
        if not name or el.get("translatable") == "false":
            continue
        if el.tag == "string":
            out[name] = element_text(el)
        elif el.tag == "plurals":
            for item in el.findall("item"):
                out[f"{name}#{item.get('quantity')}"] = element_text(item)
    return out


def read_at(ref: str | None, rel: str) -> str | None:
    """File content at a git ref (None = working tree); None when it does not exist there."""
    if ref is None:
        path = REPO / rel
        return path.read_text(encoding="utf-8") if path.exists() else None
    proc = subprocess.run(
        ["git", "-C", str(REPO), "show", f"{ref}:{rel}"],
        capture_output=True, text=True, encoding="utf-8",
    )
    return proc.stdout if proc.returncode == 0 else None


def load_flags() -> dict[str, dict[str, str]]:
    """review-flags.json: {"locales": {"<locale>" | "*": {"<key>": "<question>"}}}."""
    if not FLAGS_FILE.exists():
        return {}
    return json.loads(FLAGS_FILE.read_text(encoding="utf-8")).get("locales", {})


def export_locale(locale: str, english: dict[str, str], since: str | None,
                  flags: dict[str, dict[str, str]], out_dir: Path) -> tuple[int, int, int]:
    rel = f"res/values-{locale}/strings.xml"
    current_xml = read_at(None, rel)
    if current_xml is None:
        raise SystemExit(f"no such locale: {rel}")
    current = parse_strings(current_xml)
    before: dict[str, str] = {}
    if since is not None:
        old_xml = read_at(since, rel)
        before = parse_strings(old_xml) if old_xml is not None else {}

    locale_flags = {**flags.get("*", {}), **flags.get(locale, {})}
    rows = []
    for key, src in english.items():
        text = current.get(key, "")
        changed = since is not None and text != before.get(key, "")
        rows.append({
            "key": key,
            "area": area_of(key.split("#", 1)[0]),
            "english": src,
            "translation": text,
            "changed": "yes" if changed else "",
            "flag": locale_flags.get(key.split("#", 1)[0], ""),
            "verdict": "",
            "correction": "",
        })
    rows.sort(key=lambda r: (r["flag"] == "", r["changed"] == "", r["area"], r["key"]))

    out = out_dir / f"review-{locale}.csv"
    # utf-8-sig so Excel detects the encoding; LibreOffice and Sheets ignore the BOM.
    with out.open("w", encoding="utf-8-sig", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    return len(rows), sum(r["flag"] != "" for r in rows), sum(r["changed"] == "yes" for r in rows)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True, type=Path, help="output directory (created if missing)")
    ap.add_argument("--since", help="git ref; marks strings whose translation changed after it")
    ap.add_argument("--locale", action="append", help="locale dir suffix, e.g. fa or zh-rCN (repeatable; default all)")
    args = ap.parse_args()

    english = parse_strings((RES / "values" / "strings.xml").read_text(encoding="utf-8"))
    locales = args.locale or sorted(
        p.parent.name.removeprefix("values-")
        for p in RES.glob("values-*/strings.xml")
    )
    flags = load_flags()
    unknown = [k for loc in flags.values() for k in loc if k not in english and f"{k}#other" not in english]
    if unknown:
        print(f"review-flags.json names keys not in res/values: {unknown}", file=sys.stderr)
        return 1

    args.out.mkdir(parents=True, exist_ok=True)
    for locale in locales:
        total, flagged, changed = export_locale(locale, english, args.since, flags, args.out)
        extra = f", {changed} changed since {args.since}" if args.since else ""
        print(f"{locale}: {total} strings, {flagged} flagged{extra}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
