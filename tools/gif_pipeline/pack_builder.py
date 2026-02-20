#!/usr/bin/env python3
"""
pack_builder.py — Generate downloadable GIF packs for GitHub releases.

Creates ZIP archives containing a per-pack SQLite database (pack.db),
partitioned thumbnails, and a simple manifest.json. Each pack is sized
to fit within GitHub release asset limits (default 50MB).

Pack ZIP format (matches GifPackManager.kt expectations):
    pack_name.zip
    ├── manifest.json       # {pack_id, name, version, gif_count, description}
    ├── pack.db             # Mini SQLite — only this pack's gifs/categories/FTS
    └── thumbs/             # Thumbnails partitioned by id÷1000
        ├── 000/
        │   ├── 000001.webp
        │   └── 000002.webp
        └── 024/
            └── 024999.webp

Pack tiers:
  - core:       Top ~2K most popular/universal reaction GIFs
  - category:   Emotion-based packs (happy, funny, love, sad, etc.)
  - trending:   Trending and viral GIF packs
  - universal:  Remaining universal search results

Usage:
    python pack_builder.py --optimized ./processed/optimized \
                           --thumbs ./processed/thumbs \
                           --metadata ./raw_data/metadata \
                           --processed ./processed \
                           --output ./packs

Requirements:
    - Optimized WebP files in --optimized directory
    - Thumbnail WebP files in --thumbs directory
    - Metadata JSON files in --metadata directory
    - Processed directory with processing_results.json (for build_database)
"""

import argparse
import json
import os
import re
import sys
import tempfile
import time
import zipfile
from collections import Counter, defaultdict
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

from build_database import build_pack_database

# Pack sizing: GitHub release max 2GB/asset, recommended <100MB
DEFAULT_MAX_PACK_BYTES = 50 * 1024 * 1024  # 50MB per pack
CORE_PACK_TARGET = 2000  # Top N GIFs for core pack

# Priority tiers for ranking GIFs (lower = higher priority)
TIER_TRENDING = 0
TIER_POPULAR = 1
TIER_VIRAL = 2
TIER_UNIVERSAL = 3
TIER_CATEGORY = 4

# Emotion categories matching the pipeline's CATEGORIES list
EMOTION_CATEGORIES = [
    "happy", "funny", "love", "sad", "angry", "surprised", "excited",
    "scared", "disgusted", "awkward", "proud", "relieved", "smug",
    "sorry", "ashamed", "approve", "laugh",
]


def load_gif_metadata(meta_dir: Path, optimized_dir: Path,
                      thumbs_dir: Path) -> List[Dict]:
    """
    Load metadata for all GIFs that have both an optimized file and thumbnail.
    Returns list of dicts with merged metadata + file info.
    """
    entries = []
    meta_files = sorted(meta_dir.glob("*.json"))
    print(f"Scanning {len(meta_files)} metadata files...")

    for meta_file in meta_files:
        file_id = meta_file.stem
        opt_path = optimized_dir / f"{file_id}.webp"
        thumb_path = thumbs_dir / f"{file_id}.webp"

        # Only include GIFs with both optimized and thumbnail present
        if not opt_path.exists() or not thumb_path.exists():
            continue

        opt_size = opt_path.stat().st_size
        thumb_size = thumb_path.stat().st_size

        # Skip zero-byte or suspiciously tiny files
        if opt_size < 100 or thumb_size < 100:
            continue

        try:
            with open(meta_file) as f:
                meta = json.load(f)
        except (json.JSONDecodeError, OSError):
            continue

        category = meta.get("category", "")
        description = meta.get("description", "")
        slug = meta.get("slug", "")

        # Extract keywords from slug (giphy slugs are hyphenated keyword chains)
        slug_words = slug.replace("-", " ").lower() if slug else ""

        # Determine priority tier
        if category == "trending":
            tier = TIER_TRENDING
        elif category == "popular":
            tier = TIER_POPULAR
        elif category == "viral":
            tier = TIER_VIRAL
        elif category.startswith("universal:"):
            tier = TIER_UNIVERSAL
            category = "universal"
        elif category.startswith("supplementary:"):
            tier = TIER_UNIVERSAL  # same priority as universal
            category = "supplementary"
        elif category in EMOTION_CATEGORIES:
            tier = TIER_CATEGORY
        else:
            tier = TIER_CATEGORY

        # Parse frame count from original image metadata if available
        orig_images = meta.get("images", {}).get("original", {})
        frame_count = int(orig_images.get("frames", 0))

        entries.append({
            "file_id": file_id,
            "giphy_id": meta.get("giphy_id", ""),
            "description": description,
            "slug_words": slug_words,
            "category": category,
            "tier": tier,
            "rating": meta.get("rating", ""),
            "opt_size": opt_size,
            "thumb_size": thumb_size,
            "frame_count": frame_count,
            "opt_path": str(opt_path),
            "thumb_path": str(thumb_path),
        })

    print(f"Found {len(entries)} complete GIFs (optimized + thumbnail)")
    return entries


def score_gif(entry: Dict) -> float:
    """
    Score a GIF for popularity ranking. Higher = more popular.
    Trending/popular/viral GIFs rank above category-specific ones.
    """
    tier = entry["tier"]
    # Invert tier so lower tier numbers get higher scores
    tier_score = (5 - tier) * 10000

    # Slight bonus for GIFs with more descriptive slugs (more searchable)
    keyword_bonus = min(len(entry["slug_words"].split()), 10) * 10

    # Slight penalty for very large files (prefer compact GIFs for packs)
    size_penalty = entry["opt_size"] / 1024  # penalty grows with KB

    return tier_score + keyword_bonus - size_penalty


def build_core_pack(entries: List[Dict], target_count: int) -> List[Dict]:
    """Select the top N most popular/universal GIFs for the core pack."""
    # Score all entries
    scored = [(score_gif(e), e) for e in entries]
    scored.sort(key=lambda x: x[0], reverse=True)

    # Take top N
    core = [e for _, e in scored[:target_count]]
    print(f"Core pack: {len(core)} GIFs selected "
          f"(tier distribution: {Counter(e['tier'] for e in core)})")
    return core


def build_category_packs(entries: List[Dict],
                         exclude_ids: Set[str]) -> Dict[str, List[Dict]]:
    """Group remaining GIFs by emotion category."""
    category_groups: Dict[str, List[Dict]] = defaultdict(list)

    for entry in entries:
        if entry["file_id"] in exclude_ids:
            continue
        cat = entry["category"]
        if cat in EMOTION_CATEGORIES:
            category_groups[cat].append(entry)
        elif cat in ("trending", "popular", "viral", "universal"):
            # Distribute non-emotion GIFs across related emotion categories
            # based on their description keywords
            assigned = False
            desc = (entry["description"] + " " + entry["slug_words"]).lower()
            for emotion in EMOTION_CATEGORIES:
                if emotion in desc:
                    category_groups[emotion].append(entry)
                    assigned = True
                    break
            if not assigned:
                # Put unclassifiable into "funny" as catch-all
                category_groups["funny"].append(entry)

    for cat, gifs in category_groups.items():
        print(f"  {cat}: {len(gifs)} GIFs")

    return category_groups


def split_into_packs(gifs: List[Dict], pack_prefix: str,
                     max_bytes: int) -> List[Tuple[str, List[Dict]]]:
    """
    Split a list of GIFs into packs that fit within max_bytes.
    Returns list of (pack_name, gif_list) tuples.
    """
    packs = []
    current_gifs: List[Dict] = []
    current_size = 0
    pack_num = 1

    # Sort by file_id for deterministic packing
    gifs_sorted = sorted(gifs, key=lambda e: e["file_id"])

    for entry in gifs_sorted:
        # Each GIF contributes its optimized size + thumbnail size
        entry_size = entry["opt_size"] + entry["thumb_size"]

        if current_size + entry_size > max_bytes and current_gifs:
            pack_name = f"{pack_prefix}-{pack_num:03d}"
            packs.append((pack_name, current_gifs))
            current_gifs = []
            current_size = 0
            pack_num += 1

        current_gifs.append(entry)
        current_size += entry_size

    if current_gifs:
        pack_name = f"{pack_prefix}-{pack_num:03d}"
        packs.append((pack_name, current_gifs))

    return packs


def extract_search_keywords(entry: Dict) -> str:
    """Extract searchable keywords from GIF metadata."""
    text = (entry.get("description", "") + " " +
            entry.get("slug_words", "")).lower()
    text = re.sub(r"[^a-z0-9\s]", " ", text)
    words = text.split()

    stop_words = {
        "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "do", "does", "did", "will", "would", "could",
        "should", "to", "of", "in", "for", "on", "with", "at", "by", "from",
        "as", "into", "through", "and", "but", "or", "not", "this", "that",
        "it", "its", "gif", "image", "giphy", "com", "media", "gifs",
    }

    seen: Set[str] = set()
    keywords = []
    for w in words:
        if w not in stop_words and len(w) > 1 and w not in seen:
            seen.add(w)
            keywords.append(w)

    return " ".join(keywords[:20])


def classify_emotions(entry: Dict) -> List[str]:
    """Classify a GIF into emotion categories based on text content."""
    text = (entry.get("description", "") + " " +
            entry.get("slug_words", "") + " " +
            entry.get("category", "")).lower()

    # Keyword mapping for each emotion
    emotion_keywords = {
        "funny": ["laugh", "funny", "lol", "haha", "hilarious", "comedy",
                   "joke", "humor", "meme", "rofl", "lmao"],
        "happy": ["happy", "smile", "joy", "cheerful", "yay", "celebrate",
                   "pleased", "glad", "delighted", "grin"],
        "love": ["love", "heart", "kiss", "romantic", "crush", "hug",
                  "cuddle", "adore", "affection", "sweet"],
        "sad": ["sad", "cry", "crying", "tears", "depressed", "heartbroken",
                "upset", "unhappy", "sobbing"],
        "angry": ["angry", "rage", "furious", "mad", "frustrated",
                   "annoyed", "pissed", "irritated"],
        "surprised": ["surprised", "shocked", "omg", "wow", "what",
                       "stunned", "astonished", "gasp", "shook"],
        "excited": ["excited", "hyped", "pumped", "thrilled", "stoked",
                     "ecstatic", "woohoo", "lets go"],
        "scared": ["scared", "terrified", "fear", "horror", "panic",
                    "frightened", "spooky", "creepy"],
        "disgusted": ["disgusted", "gross", "ew", "yuck", "nasty",
                       "revolting", "eww"],
        "awkward": ["awkward", "cringe", "embarrassed", "uncomfortable",
                     "nervous", "facepalm", "oops"],
        "proud": ["proud", "victory", "winning", "flex", "champion",
                   "nailed", "accomplished", "boss"],
        "relieved": ["relieved", "phew", "calm", "finally", "exhale",
                      "safe", "sigh"],
        "smug": ["smug", "sassy", "smirk", "whatever", "deal with it",
                  "mic drop", "unbothered"],
        "sorry": ["sorry", "apologize", "regret", "guilt", "my bad",
                   "forgive"],
        "ashamed": ["ashamed", "shame", "facepalm", "fail", "embarrassing",
                     "mortified"],
        "approve": ["thumbs up", "approve", "yes", "agree", "good job",
                     "nice", "well done", "clapping", "bravo"],
        "laugh": ["laugh", "rofl", "haha", "cackling", "giggling",
                   "wheeze", "dying laughing"],
    }

    matched = []
    for emotion, keywords in emotion_keywords.items():
        for kw in keywords:
            if kw in text:
                matched.append(emotion)
                break

    if not matched:
        # Use the source category from pipeline metadata
        cat = entry.get("category", "")
        if cat in EMOTION_CATEGORIES:
            matched = [cat]
        else:
            matched = ["funny"]  # default

    return matched[:3]


def generate_manifest(pack_name: str, gifs: List[Dict],
                      description: str = "") -> Dict:
    """
    Generate simplified pack manifest for inclusion in the ZIP.
    Metadata/search is handled by pack.db — manifest is just for identification.
    """
    return {
        "pack_id": pack_name,
        "name": pack_name.replace("-", " ").replace("_", " ").title(),
        "version": 1,
        "gif_count": len(gifs),
        "description": description,
    }


def write_pack_zip(pack_name: str, gifs: List[Dict],
                   processed_dir: Path, metadata_dir: Path,
                   output_dir: Path, use_optimized: bool = True,
                   include_full: bool = False) -> Path:
    """
    Create a ZIP archive matching the GifPackManager.kt expected format:
      manifest.json  — simple pack identification
      pack.db        — per-pack SQLite with gifs/categories/FTS rows
      thumbs/{partition}/{id}.webp — partitioned thumbnails
      full/{partition}/{id}.webp   — partitioned full GIFs (optional)

    Uses ZIP_STORED since WebP files are already compressed.
    """
    zip_path = output_dir / f"{pack_name}.zip"

    # Collect GIF IDs for build_pack_database
    gif_ids = [entry["file_id"] for entry in gifs]

    # Build pack.db in a temporary location
    tmp_db_fd, tmp_db_path = tempfile.mkstemp(suffix=".db", prefix=f"pack_{pack_name}_")
    os.close(tmp_db_fd)
    tmp_db = Path(tmp_db_path)

    # Resolve full GIF source directory
    full_dir = processed_dir / ("optimized" if use_optimized else "full")
    if not full_dir.exists():
        full_dir = processed_dir / "full"

    try:
        print(f"  Building pack.db for {pack_name} ({len(gif_ids)} GIFs)...")
        build_pack_database(
            processed_dir=str(processed_dir),
            metadata_dir=str(metadata_dir),
            output_path=str(tmp_db),
            gif_ids=gif_ids,
            use_optimized=use_optimized,
        )

        # Generate simplified manifest
        manifest = generate_manifest(pack_name, gifs)
        if include_full:
            manifest["has_full_gifs"] = True
        manifest_json = json.dumps(manifest, separators=(",", ":")).encode()

        # Create the ZIP archive (ZIP_STORED — WebP is already compressed)
        with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_STORED) as zf:
            # 1. manifest.json
            zf.writestr("manifest.json", manifest_json)

            # 2. pack.db
            zf.write(tmp_db, "pack.db")

            # 3. Thumbnails in partitioned layout: thumbs/{partition}/{id}.webp
            for entry in gifs:
                thumb_src = Path(entry["thumb_path"])
                if not thumb_src.exists():
                    continue

                file_id_int = int(entry["file_id"])
                partition = "%03d" % (file_id_int // 1000)
                thumb_name = "%06d.webp" % file_id_int
                arcname = f"thumbs/{partition}/{thumb_name}"
                zf.write(thumb_src, arcname)

            # 4. Full animated GIFs (optional) in partitioned layout
            if include_full:
                full_count = 0
                for entry in gifs:
                    file_id_int = int(entry["file_id"])
                    file_id_padded = "%06d" % file_id_int
                    full_src = full_dir / f"{file_id_padded}.webp"
                    if not full_src.exists():
                        continue

                    partition = "%03d" % (file_id_int // 1000)
                    full_name = "%06d.webp" % file_id_int
                    arcname = f"full/{partition}/{full_name}"
                    zf.write(full_src, arcname)
                    full_count += 1

                print(f"  Included {full_count} full animated GIFs")

    finally:
        # Clean up temp pack.db
        if tmp_db.exists():
            tmp_db.unlink()

    return zip_path


def main():
    parser = argparse.ArgumentParser(
        description="Generate downloadable GIF packs as ZIP archives"
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
        help="Root processed directory (for build_pack_database)"
    )
    parser.add_argument(
        "--output", type=str, default="./packs",
        help="Output directory for pack ZIP archives"
    )
    parser.add_argument(
        "--max-pack-mb", type=int, default=50,
        help="Maximum pack size in MB (default 50)"
    )
    parser.add_argument(
        "--core-count", type=int, default=CORE_PACK_TARGET,
        help="Number of GIFs in core pack (default 2000)"
    )
    parser.add_argument(
        "--use-full", action="store_true", default=False,
        help="Use full/ directory instead of optimized/ for pack.db sizing"
    )
    parser.add_argument(
        "--dry-run", action="store_true",
        help="Show pack plan without creating any files"
    )

    args = parser.parse_args()

    optimized_dir = Path(args.optimized)
    thumbs_dir = Path(args.thumbs)
    meta_dir = Path(args.metadata)
    processed_dir = Path(args.processed)
    output_dir = Path(args.output)
    max_pack_bytes = args.max_pack_mb * 1024 * 1024
    use_optimized = not args.use_full

    if not optimized_dir.exists():
        print(f"ERROR: Optimized directory not found: {optimized_dir}")
        sys.exit(1)
    if not thumbs_dir.exists():
        print(f"ERROR: Thumbs directory not found: {thumbs_dir}")
        sys.exit(1)
    if not meta_dir.exists():
        print(f"ERROR: Metadata directory not found: {meta_dir}")
        sys.exit(1)
    if not processed_dir.exists():
        print(f"ERROR: Processed directory not found: {processed_dir}")
        sys.exit(1)

    output_dir.mkdir(parents=True, exist_ok=True)

    # Load all available GIF metadata
    entries = load_gif_metadata(meta_dir, optimized_dir, thumbs_dir)
    if not entries:
        print("ERROR: No GIFs found with matching optimized + thumbnail files")
        sys.exit(1)

    # Build core pack (top N most popular)
    core_gifs = build_core_pack(entries, args.core_count)
    core_ids = {e["file_id"] for e in core_gifs}

    # Build category packs from remaining GIFs
    category_groups = build_category_packs(entries, core_ids)

    # Plan all packs
    all_packs: List[Tuple[str, List[Dict]]] = []

    # Core packs (may need multiple if core exceeds max_pack_bytes)
    core_packs = split_into_packs(core_gifs, "core", max_pack_bytes)
    all_packs.extend(core_packs)

    # Category packs
    for cat_name in EMOTION_CATEGORIES:
        if cat_name not in category_groups:
            continue
        cat_gifs = category_groups[cat_name]
        if not cat_gifs:
            continue
        cat_packs = split_into_packs(cat_gifs, cat_name, max_pack_bytes)
        all_packs.extend(cat_packs)

    # Summary — show thumbnail-only sizes (full GIFs not included in ZIP)
    print(f"\n{'=' * 60}")
    print(f"Pack plan: {len(all_packs)} packs (thumbnails only, no full GIFs)")
    total_gifs = 0
    total_thumb_bytes = 0
    for pack_name, gifs in all_packs:
        pack_thumb_size = sum(e["thumb_size"] for e in gifs)
        total_gifs += len(gifs)
        total_thumb_bytes += pack_thumb_size
        print(f"  {pack_name:30s}  {len(gifs):5d} GIFs  "
              f"{pack_thumb_size/1024/1024:6.1f} MB thumbs")
    print(f"{'=' * 60}")
    print(f"  Total: {total_gifs} GIFs, ~{total_thumb_bytes/1024/1024:.1f} MB thumbs "
          f"(+ pack.db per ZIP) in {len(all_packs)} packs")

    if args.dry_run:
        print("\n(Dry run — no files created)")
        return

    # Generate pack ZIPs
    for pack_name, gifs in all_packs:
        zip_path = write_pack_zip(
            pack_name=pack_name,
            gifs=gifs,
            processed_dir=processed_dir,
            metadata_dir=meta_dir,
            output_dir=output_dir,
            use_optimized=use_optimized,
        )
        zip_size = zip_path.stat().st_size
        print(f"  ZIP: {zip_path} ({zip_size/1024/1024:.1f} MB, "
              f"{len(gifs)} GIFs)")

    print(f"\nDone! {len(all_packs)} pack ZIPs written to {output_dir}")


if __name__ == "__main__":
    main()
