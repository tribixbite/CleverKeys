#!/usr/bin/env python3
"""LanguageTool pass over the CleverKeys locale strings (public API, rate-limited, cached).

Maintainer approval 2026-10-25 for https://api.languagetool.org/v2/check: the script sends the
text of res/values-<locale>/strings.xml (the app's own UI strings, nothing else). Limits
published at https://dev.languagetool.org/public-http-api: 20 requests/IP/minute (peak),
75 KB text/IP/minute, 20 KB text/request. This script stays at <= 12 KB per request and
>= MIN_INTERVAL_S seconds between requests (about 3.5 requests and 42 KB per minute), and caches
every response by content hash so a re-run never re-sends text that has not changed.

Usage:
  python3 scripts/lt_check.py [locale ...]                 full pass (default: every LT locale)
  python3 scripts/lt_check.py --since <git-ref> [locale ...]   only entries added/changed since ref
  python3 scripts/lt_check.py --recheck <locale> <key,key,...>  re-check edited keys
  --out <dir>   output directory (default $CLEVERKEYS_LT_OUT or ~/.cache/cleverkeys-lt)

Output (under --out, never in the repo):
  cache/<lt-code>/<sha256>.json   raw LanguageTool responses
  matches/<locale>.jsonl          full pass: one line per match, mapped back to key / quantity
  matches-since/<locale>.jsonl    --since pass; summary-since.json beside it
  matches/recheck-<locale>.jsonl  --recheck pass
  summary.json                    full pass: counts per locale, issue type and rule

Matches that touch a format placeholder are dropped (never a translation error). Triage of the
remaining matches is manual; the record of each pass is docs/audit/2026-10-22-translation-audit.md
sections 7 and 8.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
# Output paths; set from --out by configure() before any pass runs.
OUT = Path(os.environ.get("CLEVERKEYS_LT_OUT", Path.home() / ".cache" / "cleverkeys-lt"))
CACHE = OUT / "cache"
MATCHES = OUT / "matches"


def configure(out: Path) -> None:
    """Point every output path at `out` (cache, matches, summaries)."""
    global OUT, CACHE, MATCHES
    OUT, CACHE, MATCHES = out, out / "cache", out / "matches"
LT_URL = "https://api.languagetool.org/v2/check"
USER_AGENT = "cleverkeys-i18n-audit/1.0 (one-off localisation QA; rate-limited; cached)"

# res/values-<locale> -> LanguageTool longCode (from /v2/languages, 2026-10-25).
# Unsupported by LanguageTool and therefore not covered: cs hu in ko lv tr vi.
LOCALES: dict[str, str] = {
    "de": "de-DE",
    "es": "es",
    "fa": "fa",
    "fil": "tl-PH",
    "fr": "fr",
    "it": "it",
    "ja": "ja-JP",
    "nl": "nl",
    "pl": "pl-PL",
    "pt": "pt-BR",
    "ro": "ro-RO",
    "ru": "ru-RU",
    "uk": "uk-UA",
    "zh-rCN": "zh-CN",
}

MAX_BYTES = 12_000          # LanguageTool: 20 KB text per request; the server also caps one check
                            # at 20 s of processing (HTTP 500 "took longer"), which 18 KB of French hit


class TooSlow(Exception):
    """The server gave up on the batch after its 20 s processing cap; split it and retry."""
MIN_INTERVAL_S = 17.0       # LanguageTool: 20 requests and 75 KB per minute; this is ~3.5 and ~63 KB
SEP = "\n\n"                # paragraph break between batched strings
# java.util.Formatter specifier, as String.format parses it (same regex family as the parity test).
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[-#+ 0,(<]*\d*(?:\.\d+)?[tT]?[a-zA-Z%]")


@dataclass(frozen=True)
class Entry:
    """One translatable text: a `<string>` (quantity "") or one `<plurals>` item."""
    key: str
    quantity: str
    text: str


@dataclass
class Match:
    locale: str
    key: str
    quantity: str
    rule_id: str
    category: str
    issue_type: str
    message: str
    short_message: str
    matched: str
    replacements: list[str]
    offset_in_entry: int
    length: int
    entry_text: str


def unescape(raw: str) -> str:
    """Resolve the Android resource escapes that affect visible text."""
    s = raw
    if len(s) >= 2 and s[0] == '"' and s[-1] == '"':
        s = s[1:-1]
    # \uXXXX (e.g. the NBSP inside French guillemets) is resolved by aapt2 too; leaving it
    # literal made LanguageTool flag "u00A0" as a misspelling (2026-11-03 pass).
    s = re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), s)
    return (
        s.replace("\\'", "'")
        .replace('\\"', '"')
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\@", "@")
        .replace("\\?", "?")
    )


def entries(locale: str, ref: str | None = None) -> list[Entry]:
    """Translatable entries of one locale, from the working tree or (with `ref`) a git commit."""
    path = f"res/values-{locale}/strings.xml"
    if ref is None:
        root = ET.parse(REPO / path).getroot()
    else:
        blob = subprocess.run(
            ["git", "-C", str(REPO), "show", f"{ref}:{path}"], check=True, capture_output=True
        ).stdout
        root = ET.fromstring(blob)
    out: list[Entry] = []
    for el in root:
        if el.get("translatable") == "false":
            continue
        name = el.get("name")
        if not name:
            continue
        if el.tag == "string":
            out.append(Entry(name, "", unescape(el.text or "")))
        elif el.tag == "plurals":
            for item in el.findall("item"):
                out.append(Entry(name, item.get("quantity", ""), unescape(item.text or "")))
    return out


def utf16_len(s: str) -> int:
    """LanguageTool is Java: offsets are UTF-16 code units (emoji count twice)."""
    return len(s.encode("utf-16-le")) // 2


def batches(items: list[Entry]) -> list[list[Entry]]:
    out: list[list[Entry]] = []
    cur: list[Entry] = []
    size = 0
    for e in items:
        add = len(e.text.encode("utf-8")) + len(SEP)
        if cur and size + add > MAX_BYTES:
            out.append(cur)
            cur, size = [], 0
        cur.append(e)
        size += add
    if cur:
        out.append(cur)
    return out


class Client:
    def __init__(self) -> None:
        self.last_request = 0.0
        self.requests_sent = 0
        self.cache_hits = 0

    def check(self, lt_code: str, text: str) -> dict:
        cache_dir = CACHE / lt_code
        cache_dir.mkdir(parents=True, exist_ok=True)
        digest = hashlib.sha256((lt_code + "\0" + text).encode("utf-8")).hexdigest()
        cached = cache_dir / f"{digest}.json"
        if cached.exists():
            self.cache_hits += 1
            return json.loads(cached.read_text(encoding="utf-8"))
        body = urllib.parse.urlencode(
            {"text": text, "language": lt_code, "enabledOnly": "false"}
        ).encode("utf-8")
        for attempt in range(4):
            wait = self.last_request + MIN_INTERVAL_S - time.monotonic()
            if wait > 0:
                time.sleep(wait)
            req = urllib.request.Request(
                LT_URL,
                data=body,
                method="POST",
                headers={
                    "User-Agent": USER_AGENT,
                    "Accept": "application/json",
                    "Content-Type": "application/x-www-form-urlencoded",
                },
            )
            self.last_request = time.monotonic()
            try:
                with urllib.request.urlopen(req, timeout=120) as r:
                    data = json.load(r)
                self.requests_sent += 1
                cached.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
                return data
            except urllib.error.HTTPError as e:
                detail = e.read().decode("utf-8", "replace")[:300]
                print(f"  HTTP {e.code} (attempt {attempt + 1}): {detail}", file=sys.stderr, flush=True)
                if e.code == 500 and "took longer" in detail:
                    raise TooSlow(detail) from e
                if e.code in (429, 500, 502, 503, 504):
                    time.sleep(75.0 * (attempt + 1))
                    continue
                raise
            except (urllib.error.URLError, TimeoutError) as e:
                print(f"  network error (attempt {attempt + 1}): {e}", file=sys.stderr, flush=True)
                time.sleep(60.0)
        raise RuntimeError(f"LanguageTool request failed after retries ({lt_code})")


def placeholder_spans_utf16(text: str) -> list[tuple[int, int]]:
    spans: list[tuple[int, int]] = []
    for m in PLACEHOLDER.finditer(text):
        start = utf16_len(text[: m.start()])
        spans.append((start, start + utf16_len(m.group(0))))
    return spans


def run_locale(client: Client, locale: str, lt_code: str) -> list[Match]:
    items = entries(locale)
    out: list[Match] = []
    for batch in batches(items):
        out.extend(check_batch(client, locale, lt_code, batch))
    return out


def check_batch(client: Client, locale: str, lt_code: str, batch: list[Entry]) -> list[Match]:
    """Check one batch; on the server's 20 s cap, bisect it and check the halves."""
    text = SEP.join(e.text for e in batch)
    try:
        data = client.check(lt_code, text)
    except TooSlow:
        if len(batch) == 1:
            print(f"  {locale}: UNCHECKED (too slow even alone): {batch[0].key}", flush=True)
            return []
        mid = len(batch) // 2
        print(f"  {locale}: batch of {len(batch)} too slow for the server, splitting", flush=True)
        return check_batch(client, locale, lt_code, batch[:mid]) + check_batch(client, locale, lt_code, batch[mid:])
    out: list[Match] = []
    # UTF-16 start offset of each entry inside the batch text.
    starts: list[int] = []
    pos = 0
    for e in batch:
        starts.append(pos)
        pos += utf16_len(e.text) + utf16_len(SEP)
    for m in data.get("matches", []):
        off = int(m["offset"])
        length = int(m["length"])
        # Find the entry containing the match start.
        idx = max(i for i, s in enumerate(starts) if s <= off)
        e = batch[idx]
        local_off = off - starts[idx]
        if local_off >= utf16_len(e.text):
            continue  # starts inside the separator: nothing to map
        # Drop matches that touch a format placeholder: they are never translation errors.
        if any(not (local_off + length <= a or local_off >= b) for a, b in placeholder_spans_utf16(e.text)):
            continue
        ctx = m.get("context", {})
        ctx_text = ctx.get("text", "")
        c_off, c_len = int(ctx.get("offset", 0)), int(ctx.get("length", 0))
        matched = ctx_text[c_off : c_off + c_len] if ctx_text else ""
        rule = m.get("rule", {})
        out.append(
            Match(
                locale=locale,
                key=e.key,
                quantity=e.quantity,
                rule_id=rule.get("id", ""),
                category=rule.get("category", {}).get("id", ""),
                issue_type=rule.get("issueType", ""),
                message=m.get("message", ""),
                short_message=m.get("shortMessage", ""),
                matched=matched,
                replacements=[r.get("value", "") for r in m.get("replacements", [])][:5],
                offset_in_entry=local_off,
                length=length,
                entry_text=e.text,
            )
        )
    print(
        f"  {locale}: batch of {len(batch)} entries, {len(text.encode('utf-8'))} bytes, "
        f"{len(data.get('matches', []))} raw matches (sent={client.requests_sent}, cached={client.cache_hits})",
        flush=True,
    )
    return out


def recheck(locale: str, keys: set[str]) -> int:
    """Check only the named keys of one locale (after editing them) and print the matches."""
    client = Client()
    picked = [e for e in entries(locale) if e.key in keys]
    missing = keys - {e.key for e in picked}
    if missing:
        print(f"  {locale}: unknown keys {sorted(missing)}", file=sys.stderr)
    matches: list[Match] = []
    for batch in batches(picked):
        matches.extend(check_batch(client, locale, LOCALES[locale], batch))
    MATCHES.mkdir(parents=True, exist_ok=True)
    with (MATCHES / f"recheck-{locale}.jsonl").open("w", encoding="utf-8") as f:
        for m in matches:
            f.write(json.dumps(asdict(m), ensure_ascii=False) + "\n")
    print(f"== recheck {locale}: {len(picked)} entries, {len(matches)} matches")
    for m in matches:
        q = f"#{m.quantity}" if m.quantity else ""
        print(f"- {m.key}{q} [{m.rule_id}] {m.message[:100]} :: {m.entry_text[:120]}")
    return 0


def changed_entries(locale: str, ref: str) -> list[Entry]:
    """Entries (string or plural item) added or whose text changed since `ref`."""
    before = {(e.key, e.quantity): e.text for e in entries(locale, ref)}
    return [e for e in entries(locale) if before.get((e.key, e.quantity)) != e.text]


def run_since(ref: str, wanted: list[str]) -> int:
    """Incremental pass: only entries added or changed since `ref` (e.g. the last full pass).

    Writes matches-since/<locale>.jsonl and summary-since.json, leaving the full pass's
    matches/ and summary.json untouched.
    """
    out_dir = OUT / "matches-since"
    out_dir.mkdir(parents=True, exist_ok=True)
    client = Client()
    summary: dict[str, dict] = {"ref": ref}
    for locale in wanted:
        lt_code = LOCALES[locale]
        picked = changed_entries(locale, ref)
        print(f"== {locale} ({lt_code}): {len(picked)} changed entries since {ref}", flush=True)
        matches: list[Match] = []
        for batch in batches(picked):
            matches.extend(check_batch(client, locale, lt_code, batch))
        with (out_dir / f"{locale}.jsonl").open("w", encoding="utf-8") as f:
            for m in matches:
                f.write(json.dumps(asdict(m), ensure_ascii=False) + "\n")
        by_rule: dict[str, int] = {}
        for m in matches:
            by_rule[m.rule_id] = by_rule.get(m.rule_id, 0) + 1
        summary[locale] = {
            "lt_code": lt_code,
            "entries": len(picked),
            "distinct_keys": len({e.key for e in picked}),
            "matches": len(matches),
            "matched_keys": len({m.key for m in matches}),
            "by_rule": dict(sorted(by_rule.items(), key=lambda kv: -kv[1])),
        }
        (OUT / "summary-since.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"  {locale}: {len(matches)} matches after placeholder filtering", flush=True)
    print(f"done: {client.requests_sent} requests sent, {client.cache_hits} cache hits", flush=True)
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("locales", nargs="*", help="res/values-<locale> suffixes (default: every LanguageTool locale)")
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--since", metavar="REF", help="check only entries added or changed since this git ref")
    mode.add_argument("--recheck", nargs=2, metavar=("LOCALE", "KEYS"), help="re-check comma-separated keys")
    ap.add_argument("--out", type=Path, default=OUT, help="output directory (default %(default)s)")
    args = ap.parse_args(argv[1:])
    unknown = [loc for loc in args.locales if loc not in LOCALES]
    if unknown:
        ap.error(f"not a LanguageTool locale here: {unknown} (supported: {', '.join(LOCALES)})")
    configure(args.out)
    if args.recheck:
        if args.recheck[0] not in LOCALES:
            ap.error(f"not a LanguageTool locale here: {args.recheck[0]}")
        return recheck(args.recheck[0], set(args.recheck[1].split(",")))
    if args.since:
        return run_since(args.since, args.locales or list(LOCALES))
    wanted = args.locales or list(LOCALES)
    MATCHES.mkdir(parents=True, exist_ok=True)
    client = Client()
    summary_path = OUT / "summary.json"
    # Merge with earlier runs so a per-locale rerun keeps the other locales' counts.
    summary: dict[str, dict] = (
        json.loads(summary_path.read_text(encoding="utf-8")) if summary_path.exists() else {}
    )
    for locale in wanted:
        lt_code = LOCALES[locale]
        print(f"== {locale} ({lt_code})", flush=True)
        matches = run_locale(client, locale, lt_code)
        with (MATCHES / f"{locale}.jsonl").open("w", encoding="utf-8") as f:
            for m in matches:
                f.write(json.dumps(asdict(m), ensure_ascii=False) + "\n")
        by_type: dict[str, int] = {}
        by_rule: dict[str, int] = {}
        for m in matches:
            by_type[m.issue_type] = by_type.get(m.issue_type, 0) + 1
            by_rule[m.rule_id] = by_rule.get(m.rule_id, 0) + 1
        summary[locale] = {
            "lt_code": lt_code,
            "entries": len(entries(locale)),
            "matches": len(matches),
            "by_issue_type": dict(sorted(by_type.items(), key=lambda kv: -kv[1])),
            "by_rule": dict(sorted(by_rule.items(), key=lambda kv: -kv[1])),
        }
        (OUT / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"  {locale}: {len(matches)} matches after placeholder filtering", flush=True)
    print(f"done: {client.requests_sent} requests sent, {client.cache_hits} cache hits", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
