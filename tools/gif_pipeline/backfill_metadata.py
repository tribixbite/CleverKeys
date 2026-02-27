#!/usr/bin/env python3
"""
backfill_metadata.py — Fill missing metadata for Discord GIF pack entries.

Two-phase approach:
  Phase 1 (offline): Extract keywords from media_url slugs and Discord emoji
          query params. No network required, instant.
  Phase 2 (online):  Resolve Tenor short URLs (tenor.com/xFlV.gif) by following
          redirects to get full /view/ URLs with tenor_id + slug.

After backfill, re-classifies GIFs and updates pack.db search_text.

Usage:
    # Phase 1 only (offline, no network)
    python backfill_metadata.py

    # Phase 1 + 2 (resolve short URLs via HTTP)
    python backfill_metadata.py --resolve-urls

    # Dry run
    python backfill_metadata.py --dry-run

    # Rebuild packs after backfill
    python backfill_metadata.py --resolve-urls --rebuild-packs
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time
from collections import Counter
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple
from urllib.parse import parse_qs, unquote, urlparse

sys.path.insert(0, str(Path(__file__).parent))
from build_database import CATEGORIES, STOP_WORDS
from build_discord_pack import (
    DISCORD_EMOTION_KEYWORDS,
    classify_discord_gif,
    extract_keywords,
)

META_DIR = Path("discord_processed/metadata")


def parse_media_url_slug(media_url: str) -> Dict:
    """Extract keywords from a Tenor media_url path.

    Media URLs look like:
      https://media1.tenor.com/m/ZjqPAZpKWAUAAAAC/the-road-to-el-dorado-both.gif
    The slug is: "the-road-to-el-dorado-both"
    """
    result = {"keywords": [], "slug": "", "description": ""}
    if not media_url:
        return result

    path = urlparse(media_url).path
    # Last path segment is the filename: "the-road-to-el-dorado-both.gif"
    filename = path.split("/")[-1] if "/" in path else ""
    if not filename or "." not in filename:
        return result

    stem = filename.rsplit(".", 1)[0]  # "the-road-to-el-dorado-both"
    if not stem or len(stem) < 3:
        return result

    result["slug"] = stem

    # Parse words from slug
    words = stem.replace("-", " ").replace("_", " ").lower().split()
    keywords = [w for w in words if w not in STOP_WORDS and len(w) > 1]
    result["keywords"] = keywords
    result["description"] = " ".join(keywords).title() if keywords else ""

    return result


def parse_discord_emoji_url(url: str) -> Dict:
    """Extract emoji name from Discord CDN URL query params.

    Discord URLs look like:
      https://cdn.discordapp.com/emojis/123.gif?size=64&name=WAAAAAAAH&quality=lossless
    """
    result = {"keywords": [], "description": ""}
    parsed = urlparse(url)
    params = parse_qs(parsed.query)

    name = params.get("name", [""])[0]
    if name:
        # Split camelCase/PascalCase and underscores
        words = re.sub(r"([a-z])([A-Z])", r"\1 \2", name)
        words = re.sub(r"[^a-zA-Z0-9\s]", " ", words).lower().split()
        keywords = [w for w in words if len(w) > 1 and w not in STOP_WORDS]
        result["keywords"] = keywords
        result["description"] = name

    # Also extract emoji ID as source_id
    path = parsed.path
    emoji_file = path.split("/")[-1] if "/" in path else ""
    if emoji_file:
        emoji_id = emoji_file.rsplit(".", 1)[0] if "." in emoji_file else emoji_file
        result["source_id"] = emoji_id

    return result


def resolve_tenor_short_url(url: str) -> Optional[Dict]:
    """Follow a Tenor short URL redirect to get the full /view/ URL.

    Uses curl -Ls to follow redirects and return the final URL.
    Returns parsed metadata from the resolved URL, or None on failure.
    """
    try:
        result = subprocess.run(
            ["curl", "-Ls", "-o", "/dev/null", "-w", "%{url_effective}", url],
            capture_output=True, text=True, timeout=15,
        )
        if result.returncode != 0:
            return None

        resolved = result.stdout.strip()
        if "/view/" not in resolved:
            return None

        # Parse the resolved /view/ URL
        slug = unquote(resolved.split("/view/")[-1]).strip("/")
        info = {"resolved_url": resolved, "slug": slug, "tenor_id": "", "keywords": []}

        if "-gif-" in slug:
            parts = slug.rsplit("-gif-", 1)
            info["tenor_id"] = parts[-1]
            keyword_part = parts[0]
        else:
            segments = slug.split("-")
            if segments and segments[-1].isdigit():
                info["tenor_id"] = segments[-1]
                keyword_part = "-".join(segments[:-1])
            else:
                keyword_part = slug

        words = keyword_part.replace("-", " ").replace("_", " ").lower().split()
        info["keywords"] = [w for w in words if w not in STOP_WORDS and len(w) > 1]
        info["description"] = " ".join(info["keywords"]).title() if info["keywords"] else ""

        return info
    except Exception:
        return None


def backfill_phase1(meta_dir: Path, dry_run: bool = False) -> Dict:
    """Phase 1: Offline backfill from media_url slugs and Discord emoji names."""
    stats = {"total": 0, "updated": 0, "already_ok": 0, "no_info": 0}

    for meta_file in sorted(meta_dir.glob("*.json")):
        with open(meta_file) as f:
            meta = json.load(f)
        stats["total"] += 1

        # Skip entries that already have metadata
        if meta.get("slug") and meta.get("description"):
            stats["already_ok"] += 1
            continue

        updated = False
        source = meta.get("source", "")
        media_url = meta.get("media_url", "")
        url = meta.get("url", "")

        # Strategy 1: Parse Tenor media_url
        if source == "tenor" and media_url and "tenor.com" in media_url:
            parsed = parse_media_url_slug(media_url)
            if parsed["keywords"]:
                if not meta.get("slug"):
                    meta["slug"] = parsed["slug"]
                if not meta.get("description"):
                    meta["description"] = parsed["description"]
                updated = True

        # Strategy 2: Parse Discord emoji name from URL query params
        elif source == "discord" and "discordapp.com" in url:
            parsed = parse_discord_emoji_url(url)
            if parsed.get("keywords"):
                if not meta.get("description"):
                    meta["description"] = parsed["description"]
                if not meta.get("slug"):
                    meta["slug"] = parsed.get("description", "").lower().replace(" ", "-")
                if parsed.get("source_id"):
                    meta["source_id"] = parsed["source_id"]
                updated = True

        # Strategy 3: Parse any media_url filename as last resort
        elif media_url and not meta.get("slug"):
            parsed = parse_media_url_slug(media_url)
            if parsed["keywords"]:
                meta["slug"] = parsed["slug"]
                meta["description"] = parsed["description"]
                updated = True

        if updated:
            stats["updated"] += 1
            if not dry_run:
                with open(meta_file, "w") as f:
                    json.dump(meta, f, separators=(",", ":"))
        else:
            stats["no_info"] += 1

    return stats


def backfill_phase2(meta_dir: Path, dry_run: bool = False) -> Dict:
    """Phase 2: Resolve Tenor short URLs via HTTP redirects."""
    stats = {"total_short": 0, "resolved": 0, "failed": 0, "skipped": 0}

    # Collect entries needing resolution
    to_resolve = []
    for meta_file in sorted(meta_dir.glob("*.json")):
        with open(meta_file) as f:
            meta = json.load(f)

        url = meta.get("url", "")
        source = meta.get("source", "")
        if source == "tenor" and "tenor.com" in url and "/view/" not in url and not meta.get("source_id"):
            to_resolve.append((meta_file, meta))

    stats["total_short"] = len(to_resolve)
    if not to_resolve:
        print("  No short URLs to resolve")
        return stats

    print(f"  Resolving {len(to_resolve)} Tenor short URLs...")

    for i, (meta_file, meta) in enumerate(to_resolve):
        url = meta["url"]
        if dry_run:
            stats["skipped"] += 1
            continue

        info = resolve_tenor_short_url(url)
        if info and info.get("tenor_id"):
            meta["source_id"] = info["tenor_id"]
            meta["url"] = info["resolved_url"]
            # Only overwrite slug/description if they were from media_url (less authoritative)
            if info.get("slug"):
                meta["slug"] = info["slug"]
            if info.get("description"):
                meta["description"] = info["description"]

            with open(meta_file, "w") as f:
                json.dump(meta, f, separators=(",", ":"))
            stats["resolved"] += 1
        else:
            stats["failed"] += 1

        # Rate limit: ~5 req/s to avoid hammering Tenor
        if (i + 1) % 5 == 0:
            time.sleep(1)

        # Progress
        if (i + 1) % 25 == 0 or i == len(to_resolve) - 1:
            print(f"    [{i+1}/{len(to_resolve)}] resolved={stats['resolved']} failed={stats['failed']}")

    return stats


def reclassify_all(meta_dir: Path, dry_run: bool = False) -> Dict:
    """Re-extract keywords and re-classify all entries using updated metadata."""
    stats = {"total": 0, "reclassified": 0}

    for meta_file in sorted(meta_dir.glob("*.json")):
        with open(meta_file) as f:
            meta = json.load(f)
        stats["total"] += 1

        # Build combined text from all available fields
        text_parts = []
        if meta.get("slug"):
            slug_words = meta["slug"].replace("-", " ").replace("_", " ")
            text_parts.append(slug_words)
        if meta.get("description"):
            text_parts.append(meta["description"])
        combined = " ".join(text_parts)

        keywords = extract_keywords(combined)
        categories = classify_discord_gif(keywords, combined)
        cat_name = CATEGORIES.get(categories[0], {}).get("name", "funny").lower()

        changed = (meta.get("category") != cat_name)
        meta["category"] = cat_name

        if not dry_run:
            with open(meta_file, "w") as f:
                json.dump(meta, f, separators=(",", ":"))

        if changed:
            stats["reclassified"] += 1

    return stats


def main():
    parser = argparse.ArgumentParser(description="Backfill missing Discord GIF metadata")
    parser.add_argument("--meta-dir", type=str, default="discord_processed/metadata",
                        help="Metadata directory (default: discord_processed/metadata)")
    parser.add_argument("--resolve-urls", action="store_true",
                        help="Phase 2: resolve Tenor short URLs via HTTP")
    parser.add_argument("--reclassify", action="store_true", default=True,
                        help="Re-classify all GIFs after backfill (default: True)")
    parser.add_argument("--rebuild-packs", action="store_true",
                        help="Rebuild pack ZIPs after backfill")
    parser.add_argument("--dry-run", action="store_true",
                        help="Show what would change without modifying files")

    args = parser.parse_args()
    meta_dir = Path(args.meta_dir)

    if not meta_dir.exists():
        print(f"ERROR: metadata directory not found: {meta_dir}")
        sys.exit(1)

    # ── Pre-backfill stats ──
    total = sum(1 for _ in meta_dir.glob("*.json"))
    missing_before = sum(
        1 for f in meta_dir.glob("*.json")
        if not json.load(open(f)).get("slug")
    )
    print(f"Metadata files: {total}")
    print(f"Missing slug before backfill: {missing_before}")

    # ── Phase 1: Offline backfill ──
    print(f"\n{'='*60}")
    print("Phase 1: Offline backfill (media_url slugs + Discord emoji names)")
    print(f"{'='*60}")
    p1 = backfill_phase1(meta_dir, dry_run=args.dry_run)
    print(f"  Updated: {p1['updated']}")
    print(f"  Already OK: {p1['already_ok']}")
    print(f"  No extractable info: {p1['no_info']}")

    # ── Phase 2: Online resolution ──
    if args.resolve_urls:
        print(f"\n{'='*60}")
        print("Phase 2: Resolving Tenor short URLs via HTTP")
        print(f"{'='*60}")
        p2 = backfill_phase2(meta_dir, dry_run=args.dry_run)
        print(f"  Short URLs found: {p2['total_short']}")
        print(f"  Resolved: {p2['resolved']}")
        print(f"  Failed: {p2['failed']}")

    # ── Reclassify ──
    if args.reclassify:
        print(f"\n{'='*60}")
        print("Re-classifying all GIFs with updated metadata")
        print(f"{'='*60}")
        rc = reclassify_all(meta_dir, dry_run=args.dry_run)
        print(f"  Reclassified: {rc['reclassified']} / {rc['total']}")

    # ── Post-backfill stats ──
    missing_after = sum(
        1 for f in meta_dir.glob("*.json")
        if not json.load(open(f)).get("slug")
    )
    print(f"\n{'='*60}")
    print(f"Missing slug: {missing_before} -> {missing_after}")
    print(f"{'='*60}")

    if args.rebuild_packs:
        print("\nRebuilding packs... (run build_discord_pack.py --skip-convert)")
        # TODO: call rebuild logic or print the command
        print("  Run: python build_discord_pack.py --skip-convert --workers 4 --profile balanced")


if __name__ == "__main__":
    main()
