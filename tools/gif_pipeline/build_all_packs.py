#!/usr/bin/env python3
"""
build_all_packs.py — Build category-based GIF packs with outlier separation.

Produces multiple pack variations:
  - Category-based full GIF packs (~5k each, ≤P90 file size)
  - Outlier packs (>P90 file size, isolated to avoid bloating popular packs)
  - Thumbs-only packs (DB + thumbnails, no full GIFs)
  - Combined personal packs (e.g., top 25k full + all thumbs)

Pack categories:
  reactions     — viral, popular, trending (the "best of" reaction GIFs)
  positive      — happy, excited, love, proud, approve
  humor         — funny, laugh, smug, awkward
  negative      — sad, angry, scared, disgusted, ashamed, sorry
  surprise      — surprised, relieved
  universal     — universal:* subcategories (jk, bye, dance, etc.)
  cats          — supplementary:* subcategories (cat-themed)
  outliers      — GIFs above P90 file size threshold

Each full-GIF pack targets ~5k GIFs (~150-200 MB), fitting GitHub release limits.
Thumbs-only packs can be much larger since thumbnails average ~1 KB.

Usage:
    # Test: build a single 5k full pack
    python build_all_packs.py --mode test5k

    # Build all category packs (full + thumbs-only variants)
    python build_all_packs.py --mode all

    # Build thumbs-only for everything
    python build_all_packs.py --mode thumbs-all

    # Build personal 25k full + all thumbs
    python build_all_packs.py --mode personal

    # Dry run — show pack plan without building
    python build_all_packs.py --mode all --dry-run
"""

import argparse
import json
import os
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

from pack_builder import (
    extract_search_keywords,
    load_gif_metadata,
    score_gif,
    write_pack_zip,
)

# Category groupings for emotion-based packs
PACK_GROUPS = {
    "reactions": {
        "categories": ["viral", "popular", "trending"],
        "description": "Top reaction GIFs — viral, popular, and trending",
    },
    "positive": {
        "categories": ["happy", "excited", "love", "proud", "approve"],
        "description": "Positive emotion GIFs — happy, excited, love, proud",
    },
    "humor": {
        "categories": ["funny", "laugh", "smug", "awkward"],
        "description": "Humor GIFs — funny, laughing, smug, awkward",
    },
    "negative": {
        "categories": ["sad", "angry", "scared", "disgusted", "ashamed", "sorry"],
        "description": "Negative emotion GIFs — sad, angry, scared, disgusted",
    },
    "surprise": {
        "categories": ["surprised", "relieved"],
        "description": "Surprise and relief GIFs",
    },
    "universal": {
        "categories_prefix": "universal:",
        "description": "Universal reaction GIFs — bye, dance, hello, etc.",
    },
    "cats": {
        "categories_prefix": "supplementary:",
        "description": "Cat GIFs — supplementary collection",
    },
}

# Pack size target
TARGET_PACK_SIZE = 5000
# Outlier threshold percentile
OUTLIER_PERCENTILE = 90


def load_all_gifs(
    meta_dir: Path, opt_dir: Path, thumb_dir: Path
) -> List[Dict]:
    """Load all valid GIFs with metadata and file sizes."""
    entries = []
    meta_files = sorted(meta_dir.glob("*.json"))
    print(f"Scanning {len(meta_files)} metadata files...")

    for meta_file in meta_files:
        fid = meta_file.stem
        opt_path = opt_dir / f"{fid}.webp"
        thumb_path = thumb_dir / f"{fid}.webp"

        if not opt_path.exists() or not thumb_path.exists():
            continue

        opt_sz = opt_path.stat().st_size
        thm_sz = thumb_path.stat().st_size
        if opt_sz < 100 or thm_sz < 100:
            continue

        try:
            with open(meta_file) as f:
                meta = json.load(f)
        except (json.JSONDecodeError, OSError):
            continue

        cat = meta.get("category", "")
        description = meta.get("description", "")
        slug = meta.get("slug", "")
        slug_words = slug.replace("-", " ").lower() if slug else ""
        rating = meta.get("rating", "")

        # Determine parent category for grouping
        if cat.startswith("supplementary:"):
            parent = "cats"
        elif cat.startswith("universal:"):
            parent = "universal"
        elif cat in ("viral", "popular", "trending"):
            parent = "reactions"
        elif cat in ("happy", "excited", "love", "proud", "approve"):
            parent = "positive"
        elif cat in ("funny", "laugh", "smug", "awkward"):
            parent = "humor"
        elif cat in ("sad", "angry", "scared", "disgusted", "ashamed", "sorry"):
            parent = "negative"
        elif cat in ("surprised", "relieved"):
            parent = "surprise"
        else:
            parent = "humor"  # fallback

        # Priority tier for ranking (lower = more popular)
        tier_map = {
            "trending": 0, "popular": 1, "viral": 2,
            "reactions": 2,
            "positive": 3, "humor": 3, "negative": 3, "surprise": 3,
            "universal": 4, "cats": 5,
        }
        tier = tier_map.get(parent, 5)

        # Keyword bonus for scoring
        kw_count = min(len(slug_words.split()), 10)

        entries.append({
            "file_id": fid,
            "giphy_id": meta.get("giphy_id", ""),
            "description": description,
            "slug_words": slug_words,
            "category": cat,
            "parent": parent,
            "tier": tier,
            "rating": rating,
            "opt_size": opt_sz,
            "thumb_size": thm_sz,
            "opt_path": str(opt_path),
            "thumb_path": str(thumb_path),
            # Score: higher = more popular. Tier dominates, smaller files preferred within tier.
            "score": (5 - tier) * 100000 + kw_count * 10 - opt_sz / 1024,
        })

    print(f"Loaded {len(entries)} valid GIFs")
    return entries


def split_outliers(
    entries: List[Dict], percentile: int = OUTLIER_PERCENTILE
) -> Tuple[List[Dict], List[Dict]]:
    """Split entries into normal and outlier groups by file size percentile."""
    all_sizes = sorted(e["opt_size"] for e in entries)
    threshold = all_sizes[int(len(all_sizes) * percentile / 100)]

    normal = [e for e in entries if e["opt_size"] <= threshold]
    outliers = [e for e in entries if e["opt_size"] > threshold]

    print(f"Size threshold (P{percentile}): {threshold:,} bytes ({threshold/1024:.0f} KB)")
    print(f"  Normal: {len(normal):,} GIFs ({sum(e['opt_size'] for e in normal)/1024/1024:.0f} MB)")
    print(f"  Outliers: {len(outliers):,} GIFs ({sum(e['opt_size'] for e in outliers)/1024/1024:.0f} MB)")

    return normal, outliers


def group_by_category(entries: List[Dict]) -> Dict[str, List[Dict]]:
    """Group entries by parent category, sorted by score within each group."""
    groups: Dict[str, List[Dict]] = defaultdict(list)
    for e in entries:
        groups[e["parent"]].append(e)

    # Sort each group by score descending (most popular first)
    for group in groups.values():
        group.sort(key=lambda e: e["score"], reverse=True)

    return dict(groups)


def chunk_group(
    gifs: List[Dict], prefix: str, target_size: int = TARGET_PACK_SIZE
) -> List[Tuple[str, List[Dict]]]:
    """Split a list of GIFs into numbered packs of ~target_size."""
    if not gifs:
        return []

    packs = []
    n_packs = max(1, (len(gifs) + target_size - 1) // target_size)
    chunk_size = (len(gifs) + n_packs - 1) // n_packs  # even distribution

    for i in range(0, len(gifs), chunk_size):
        chunk = gifs[i:i + chunk_size]
        pack_num = len(packs) + 1
        if n_packs == 1:
            name = prefix
        else:
            name = f"{prefix}-{pack_num:02d}"
        packs.append((name, chunk))

    return packs


def plan_packs(
    entries: List[Dict],
    mode: str = "all",
    top_n: int = 25000,
) -> List[Tuple[str, List[Dict], bool]]:
    """
    Plan pack builds based on mode.

    Returns list of (pack_name, gif_list, include_full) tuples.
    """
    normal, outliers = split_outliers(entries)
    normal_groups = group_by_category(normal)
    outlier_groups = group_by_category(outliers)

    packs: List[Tuple[str, List[Dict], bool]] = []

    if mode == "test5k":
        # Quick test: top 5k most popular normal GIFs with full animations
        all_normal_scored = sorted(normal, key=lambda e: e["score"], reverse=True)
        test_gifs = all_normal_scored[:5000]
        packs.append(("test-popular-5k", test_gifs, True))
        return packs

    if mode == "personal":
        # Personal pack: top 25k full GIFs + all thumbs
        all_normal_scored = sorted(normal, key=lambda e: e["score"], reverse=True)
        top_n_gifs = all_normal_scored[:top_n]
        # Split into ~5k chunks for manageability
        chunks = chunk_group(top_n_gifs, "personal-full")
        for name, chunk in chunks:
            packs.append((name, chunk, True))

        # All thumbs (entire collection, no full GIFs)
        all_scored = sorted(entries, key=lambda e: e["score"], reverse=True)
        thumb_chunks = chunk_group(all_scored, "all-thumbs", target_size=25000)
        for name, chunk in thumb_chunks:
            packs.append((name, chunk, False))

        return packs

    # --- Category-based packs ---
    # Order: reactions first (most universally useful), then emotions, then niche
    category_order = ["reactions", "positive", "humor", "negative", "surprise",
                      "universal", "cats"]

    if mode in ("all", "full"):
        # Full GIF packs per category (normal only)
        for cat in category_order:
            cat_gifs = normal_groups.get(cat, [])
            if not cat_gifs:
                continue
            chunks = chunk_group(cat_gifs, f"full-{cat}")
            for name, chunk in chunks:
                packs.append((name, chunk, True))

        # Outlier packs (full, separated by size)
        if outliers:
            chunks = chunk_group(outliers, "full-outliers")
            for name, chunk in chunks:
                packs.append((name, chunk, True))

    if mode in ("all", "thumbs", "thumbs-all"):
        # Thumbs-only: popular first 25k
        all_scored = sorted(entries, key=lambda e: e["score"], reverse=True)
        top25k = all_scored[:25000]
        packs.append(("thumbs-popular-25k", top25k, False))

        if mode == "thumbs-all":
            # All thumbs as one big pack
            packs.append(("thumbs-all-130k", all_scored, False))
        else:
            # Per-category thumbs-only packs (includes outliers)
            all_groups = group_by_category(entries)
            for cat in category_order:
                cat_gifs = all_groups.get(cat, [])
                if not cat_gifs:
                    continue
                chunks = chunk_group(cat_gifs, f"thumbs-{cat}", target_size=10000)
                for name, chunk in chunks:
                    packs.append((name, chunk, False))

    return packs


def print_plan(packs: List[Tuple[str, List[Dict], bool]]):
    """Print pack plan summary."""
    print(f"\n{'='*70}")
    print(f"Pack Plan: {len(packs)} packs")
    print(f"{'='*70}")

    total_gifs = 0
    total_full_mb = 0
    total_thumb_mb = 0

    for name, gifs, include_full in packs:
        n = len(gifs)
        full_mb = sum(e["opt_size"] for e in gifs) / 1024 / 1024
        thumb_mb = sum(e["thumb_size"] for e in gifs) / 1024 / 1024
        total_gifs += n
        total_thumb_mb += thumb_mb

        if include_full:
            total_full_mb += full_mb
            est_zip = full_mb + thumb_mb + 1  # +1 MB for DB
            label = "FULL"
        else:
            est_zip = thumb_mb + 1  # +1 MB for DB
            label = "THUMBS"

        cats = Counter(e["parent"] for e in gifs)
        cat_summary = ", ".join(f"{c}:{n}" for c, n in cats.most_common(4))

        print(f"  {name:30s}  {n:6d} GIFs  ~{est_zip:6.0f} MB  [{label}]  ({cat_summary})")

    print(f"{'='*70}")
    print(f"  Total: {total_gifs:,} GIFs across {len(packs)} packs")
    print(f"  Full GIFs: ~{total_full_mb:.0f} MB  |  Thumbs: ~{total_thumb_mb:.0f} MB")
    print(f"  Est. total disk: ~{total_full_mb + total_thumb_mb:.0f} MB")


def build_packs(
    packs: List[Tuple[str, List[Dict], bool]],
    processed_dir: Path,
    metadata_dir: Path,
    output_dir: Path,
):
    """Build all planned packs as ZIP archives."""
    output_dir.mkdir(parents=True, exist_ok=True)

    for i, (name, gifs, include_full) in enumerate(packs, 1):
        print(f"\n[{i}/{len(packs)}] Building {name} ({len(gifs)} GIFs, "
              f"{'full+thumbs' if include_full else 'thumbs only'})...")

        t0 = time.time()
        zip_path = write_pack_zip(
            pack_name=name,
            gifs=gifs,
            processed_dir=processed_dir,
            metadata_dir=metadata_dir,
            output_dir=output_dir,
            use_optimized=True,
            include_full=include_full,
        )
        elapsed = time.time() - t0
        zip_size = zip_path.stat().st_size

        print(f"  -> {zip_path.name}: {zip_size/1024/1024:.1f} MB ({elapsed:.0f}s)")


def main():
    parser = argparse.ArgumentParser(
        description="Build category-based GIF packs with outlier separation"
    )
    parser.add_argument(
        "--mode", type=str, default="test5k",
        choices=["test5k", "all", "full", "thumbs", "thumbs-all", "personal"],
        help="Build mode: test5k (default), all, full, thumbs, thumbs-all, personal"
    )
    parser.add_argument(
        "--optimized", type=str, default="./processed/optimized",
        help="Directory with optimized WebP files"
    )
    parser.add_argument(
        "--thumbs", type=str, default="./processed/thumbs",
        help="Directory with thumbnail WebP files"
    )
    parser.add_argument(
        "--metadata", type=str, default="./raw_data/metadata",
        help="Directory with metadata JSON files"
    )
    parser.add_argument(
        "--processed", type=str, default="./processed",
        help="Root processed directory"
    )
    parser.add_argument(
        "--output", type=str, default="./packs",
        help="Output directory for pack ZIP archives"
    )
    parser.add_argument(
        "--top-n", type=int, default=25000,
        help="Number of top GIFs for personal mode (default 25000)"
    )
    parser.add_argument(
        "--dry-run", action="store_true",
        help="Show pack plan without creating files"
    )

    args = parser.parse_args()

    opt_dir = Path(args.optimized)
    thumb_dir = Path(args.thumbs)
    meta_dir = Path(args.metadata)
    processed_dir = Path(args.processed)
    output_dir = Path(args.output)

    for d, label in [(opt_dir, "optimized"), (thumb_dir, "thumbs"),
                     (meta_dir, "metadata"), (processed_dir, "processed")]:
        if not d.exists():
            print(f"ERROR: {label} directory not found: {d}")
            sys.exit(1)

    # Load all GIF metadata + sizes
    entries = load_all_gifs(meta_dir, opt_dir, thumb_dir)
    if not entries:
        print("ERROR: No valid GIFs found")
        sys.exit(1)

    # Plan packs
    packs = plan_packs(entries, mode=args.mode, top_n=args.top_n)
    print_plan(packs)

    if args.dry_run:
        print("\n(Dry run — no files created)")
        return

    # Build packs
    build_packs(packs, processed_dir, meta_dir, output_dir)

    print(f"\nDone! {len(packs)} pack(s) written to {output_dir}")


if __name__ == "__main__":
    main()
