#!/usr/bin/env python3
"""
make_pack.py — Build a personal GIF pack for CleverKeys keyboard.

All-in-one tool: download GIFs from an API (Giphy or Klipy), convert to
optimized WebP thumbnails, build pack.db, and produce a ZIP you can import
directly into CleverKeys via Settings → GIF Panel → Import Pack.

⚠️  PERSONAL USE ONLY — GIF API terms prohibit redistribution of downloaded
    content. Each user must obtain their own free API key and build their own
    packs. Do NOT share the resulting ZIPs publicly.

Sources:
  - Giphy:  Free beta key (42 req/hr). Get one at https://developers.giphy.com
  - Klipy:  Free API (100 req/min). Get key at https://klipy.com/migrate
  - Local:  Use your own .gif files (no API key needed)

Usage:
    # Interactive setup (guides you through API key + preferences)
    python make_pack.py

    # Quick: 500 reaction GIFs from Giphy
    python make_pack.py --source giphy --preset reactions --count 500

    # Custom search queries from Klipy
    python make_pack.py --source klipy --queries "cats funny,dogs cute" --count 300

    # All 17 emotion categories, 100 each
    python make_pack.py --source giphy --preset all --per-category 100

    # Build from local GIF files
    python make_pack.py --source local --input ~/my-gifs/ --name "my-collection"

    # Dry run — show what would be downloaded
    python make_pack.py --source giphy --preset reactions --count 100 --dry-run

Requirements:
    pip install requests Pillow tqdm
    # Also need ffmpeg: pkg install ffmpeg (Termux) / brew install ffmpeg (macOS)

Output:
    packs/{pack-name}.zip — ready to import into CleverKeys
"""

import argparse
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
import zipfile
from collections import Counter, defaultdict
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

try:
    import requests
except ImportError:
    print("ERROR: 'requests' package required. Install with: pip install requests")
    sys.exit(1)

try:
    from PIL import Image
except ImportError:
    print("ERROR: 'Pillow' package required. Install with: pip install Pillow")
    sys.exit(1)

try:
    from tqdm import tqdm
except ImportError:
    # Graceful fallback — tqdm is nice but not essential
    def tqdm(iterable, **kwargs):
        desc = kwargs.get("desc", "")
        total = kwargs.get("total", None)
        for i, item in enumerate(iterable):
            if total:
                print(f"\r  {desc}: {i+1}/{total}", end="", flush=True)
            yield item
        if total:
            print()


# ── API Configuration ────────────────────────────────────────────────────────

GIPHY_SEARCH_URL = "https://api.giphy.com/v1/gifs/search"
GIPHY_TRENDING_URL = "https://api.giphy.com/v1/gifs/trending"
KLIPY_SEARCH_URL = "https://api.klipy.com/v1/gifs/search"
KLIPY_TRENDING_URL = "https://api.klipy.com/v1/gifs/trending"

# Giphy beta key: 42 searches/hr + 1000 trending/day
GIPHY_RATE_LIMIT_DELAY = 90  # seconds between API calls (42/hr ≈ 1 per 86s)
# Klipy test key: 100 req/min
KLIPY_RATE_LIMIT_DELAY = 1   # seconds between API calls
BATCH_SIZE = 50               # max results per API call (both APIs)

# ── Emotion Presets ──────────────────────────────────────────────────────────

PRESETS = {
    "reactions": {
        "description": "Top reaction GIFs — viral, popular, trending",
        "queries": [
            "reaction", "mood", "same", "vibes", "relatable",
            "this is fine", "mic drop", "mind blown", "plot twist",
            "bye felicia", "nailed it", "deal with it",
        ],
    },
    "positive": {
        "description": "Positive emotions — happy, love, excited, proud",
        "queries": [
            "happy", "joy", "smile", "celebrate", "yay", "woohoo",
            "love", "heart", "kiss", "hug", "cute",
            "excited", "hyped", "pumped", "lets go",
            "proud", "victory", "winning", "flex", "nailed it",
            "approve", "thumbs up", "yes", "agree", "clapping",
        ],
    },
    "humor": {
        "description": "Humor — funny, laughing, smug, awkward",
        "queries": [
            "funny", "lol", "hilarious", "comedy", "meme",
            "laugh", "rofl", "haha", "dying laughing", "wheeze",
            "smug", "sassy", "smirk", "eye roll", "whatever",
            "awkward", "cringe", "facepalm", "oops", "yikes",
        ],
    },
    "negative": {
        "description": "Negative emotions — sad, angry, scared, disgusted",
        "queries": [
            "sad", "crying", "tears", "heartbroken", "upset",
            "angry", "rage", "furious", "frustrated", "mad",
            "scared", "terrified", "horror", "panic", "fear",
            "disgusted", "gross", "ew", "yuck", "nope",
            "sorry", "apologize", "regret", "my bad", "oops",
            "ashamed", "shame", "fail", "embarrassing", "mortified",
        ],
    },
    "surprise": {
        "description": "Surprise and relief",
        "queries": [
            "surprised", "shocked", "omg", "what", "jaw drop",
            "mind blown", "no way", "shook", "stunned",
            "relieved", "phew", "finally", "thank god", "exhale",
        ],
    },
    "animals": {
        "description": "Animal reactions — cats, dogs, and more",
        "queries": [
            "cat funny", "cat reaction", "cat cute", "kitten",
            "dog funny", "dog reaction", "puppy cute", "doggo",
            "animal funny", "animal reaction", "wildlife funny",
            "bird funny", "parrot", "penguin", "bunny cute",
        ],
    },
    "all": {
        "description": "All emotion categories combined",
        "queries": [],  # populated dynamically from other presets
    },
}

# Build "all" preset from others
for _name, _preset in PRESETS.items():
    if _name != "all":
        PRESETS["all"]["queries"].extend(_preset["queries"])

# ── Emotion Categories (matches CleverKeys' 17 categories) ──────────────────

CATEGORIES = {
    1:  {"name": "Funny",     "icon": "\U0001F602", "keywords": ["laugh", "funny", "lol", "haha", "hilarious", "comedy", "joke", "humor", "meme", "rofl", "lmao"]},
    2:  {"name": "Angry",     "icon": "\U0001F620", "keywords": ["angry", "mad", "rage", "furious", "frustrated", "annoyed", "irritated", "pissed"]},
    3:  {"name": "Smug",      "icon": "\U0001F60F", "keywords": ["smug", "smirk", "eye roll", "whatever", "sarcastic", "dismissive"]},
    4:  {"name": "Relaxed",   "icon": "\U0001F60C", "keywords": ["relaxed", "calm", "peaceful", "chill", "zen", "content"]},
    5:  {"name": "Disgusted", "icon": "\U0001F922", "keywords": ["disgusted", "gross", "ew", "yuck", "nasty", "revolting"]},
    6:  {"name": "Awkward",   "icon": "\U0001F633", "keywords": ["awkward", "embarrassed", "cringe", "facepalm", "oops", "uncomfortable"]},
    7:  {"name": "Excited",   "icon": "\U0001F929", "keywords": ["excited", "yay", "woohoo", "pumped", "thrilled", "hyped", "stoked", "celebrate"]},
    8:  {"name": "Scared",    "icon": "\U0001F628", "keywords": ["scared", "afraid", "fear", "terrified", "horror", "panic", "creepy"]},
    9:  {"name": "Sorry",     "icon": "\U0001F614", "keywords": ["sorry", "apologize", "regret", "guilt", "my bad", "forgive"]},
    10: {"name": "Happy",     "icon": "\U0001F60A", "keywords": ["happy", "smile", "joy", "pleased", "cheerful", "glad", "delighted"]},
    11: {"name": "Love",      "icon": "\U0001F60D", "keywords": ["love", "heart", "romantic", "adore", "crush", "kiss", "cute"]},
    12: {"name": "Proud",     "icon": "\U0001F924", "keywords": ["proud", "victory", "winning", "flex", "champion", "nailed", "boss"]},
    13: {"name": "Relieved",  "icon": "\U0001F605", "keywords": ["relieved", "phew", "whew", "safe", "exhale", "finally"]},
    14: {"name": "Sad",       "icon": "\U0001F622", "keywords": ["sad", "cry", "crying", "tears", "depressed", "upset", "heartbroken"]},
    15: {"name": "Ashamed",   "icon": "\U0001F61E", "keywords": ["ashamed", "shame", "fail", "failure", "embarrassment", "mortified"]},
    16: {"name": "Surprised", "icon": "\U0001F632", "keywords": ["surprised", "shocked", "omg", "wow", "what", "stunned", "gasp", "shook"]},
    17: {"name": "Approve",   "icon": "\U0001F44D", "keywords": ["yes", "agree", "approve", "ok", "good", "nice", "thumbs up", "correct"]},
}

# ── Stop words for keyword extraction ────────────────────────────────────────

STOP_WORDS: frozenset = frozenset({
    "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
    "have", "has", "had", "do", "does", "did", "will", "would", "could",
    "should", "to", "of", "in", "for", "on", "with", "at", "by", "from",
    "as", "into", "through", "and", "but", "or", "not", "this", "that",
    "it", "its", "he", "she", "they", "them", "i", "me", "my", "we",
    "gif", "image", "giphy", "com", "media", "gifs", "tenor", "klipy",
})


# ═══════════════════════════════════════════════════════════════════════════════
# Step 1: Download GIFs from API
# ═══════════════════════════════════════════════════════════════════════════════

def search_api(api_key: str, source: str, query: str,
               offset: int = 0, limit: int = BATCH_SIZE) -> Dict:
    """Search Giphy or Klipy API."""
    if source == "giphy":
        url = GIPHY_SEARCH_URL
        params = {
            "api_key": api_key,
            "q": query,
            "limit": limit,
            "offset": offset,
            "rating": "pg-13",
            "lang": "en",
        }
    else:  # klipy — same Tenor-compatible API structure
        url = KLIPY_SEARCH_URL
        params = {
            "key": api_key,
            "q": query,
            "limit": limit,
            "pos": str(offset) if offset else None,
            "media_filter": "gif",
            "contentfilter": "medium",
        }
        params = {k: v for k, v in params.items() if v is not None}

    resp = requests.get(url, params=params, timeout=30)
    resp.raise_for_status()
    return resp.json()


def trending_api(api_key: str, source: str,
                 offset: int = 0, limit: int = BATCH_SIZE) -> Dict:
    """Fetch trending GIFs from Giphy or Klipy."""
    if source == "giphy":
        url = GIPHY_TRENDING_URL
        params = {"api_key": api_key, "limit": limit, "offset": offset, "rating": "pg-13"}
    else:
        url = KLIPY_TRENDING_URL
        params = {"key": api_key, "limit": limit, "pos": str(offset) if offset else None}
        params = {k: v for k, v in params.items() if v is not None}

    resp = requests.get(url, params=params, timeout=30)
    resp.raise_for_status()
    return resp.json()


def get_gif_url(gif_data: Dict, source: str) -> Optional[str]:
    """Extract the best GIF URL from API response data."""
    if source == "giphy":
        # Prefer fixed_width (200px) for reasonable size, fall back to original
        images = gif_data.get("images", {})
        for key in ("fixed_width", "original"):
            url = images.get(key, {}).get("url", "")
            if url:
                return url
    else:
        # Klipy uses Tenor-compatible format
        media_formats = gif_data.get("media_formats", {})
        for key in ("tinygif", "gif"):
            url = media_formats.get(key, {}).get("url", "")
            if url:
                return url
        # Fallback: media array (older Tenor format)
        media = gif_data.get("media", [])
        if media:
            for key in ("tinygif", "gif"):
                url = media[0].get(key, {}).get("url", "")
                if url:
                    return url
    return None


def get_gif_id(gif_data: Dict, source: str) -> str:
    """Extract unique GIF ID from API response."""
    if source == "giphy":
        return gif_data.get("id", "")
    else:
        return str(gif_data.get("id", ""))


def get_gif_title(gif_data: Dict, source: str) -> str:
    """Extract title/description from API response."""
    if source == "giphy":
        return gif_data.get("title", "") or gif_data.get("slug", "").replace("-", " ")
    else:
        return gif_data.get("content_description", "") or gif_data.get("title", "")


def download_file(url: str, output_path: Path) -> bool:
    """Download a single file with retry."""
    for attempt in range(3):
        try:
            resp = requests.get(url, timeout=60, stream=True)
            resp.raise_for_status()
            with open(output_path, "wb") as f:
                for chunk in resp.iter_content(chunk_size=8192):
                    f.write(chunk)
            if output_path.stat().st_size > 100:
                return True
        except Exception as e:
            if attempt == 2:
                print(f"\n  Download failed after 3 attempts: {e}")
        if output_path.exists():
            output_path.unlink()
    return False


def download_gifs(
    api_key: str,
    source: str,
    queries: List[str],
    target_count: int,
    per_query: int,
    output_dir: Path,
    rate_delay: float,
    dry_run: bool = False,
) -> Tuple[List[Dict], int]:
    """
    Download GIFs from API using search queries.
    Returns (metadata_list, api_calls_used).
    """
    gifs_dir = output_dir / "gifs"
    meta_dir = output_dir / "metadata"
    gifs_dir.mkdir(parents=True, exist_ok=True)
    meta_dir.mkdir(parents=True, exist_ok=True)

    seen_ids: Set[str] = set()
    results: List[Dict] = []
    api_calls = 0
    next_file_id = 1

    print(f"\nDownloading up to {target_count} GIFs from {source}...")
    print(f"  Queries: {len(queries)}, ~{per_query} per query")
    print(f"  Rate limit delay: {rate_delay}s between API calls")

    if dry_run:
        print(f"\n  (Dry run — would download ~{min(target_count, len(queries) * per_query)} GIFs)")
        return [], 0

    for qi, query in enumerate(queries):
        if len(results) >= target_count:
            break

        query_count = 0
        offset = 0

        while query_count < per_query and len(results) < target_count:
            # Rate limit
            if api_calls > 0 and rate_delay > 0:
                time.sleep(rate_delay)

            try:
                data = search_api(api_key, source, query, offset=offset, limit=BATCH_SIZE)
                api_calls += 1
            except Exception as e:
                print(f"\n  API error for '{query}': {e}")
                break

            gif_list = data.get("data", data.get("results", []))
            if not gif_list:
                break

            for gif_data in gif_list:
                if len(results) >= target_count or query_count >= per_query:
                    break

                api_id = get_gif_id(gif_data, source)
                if not api_id or api_id in seen_ids:
                    continue

                gif_url = get_gif_url(gif_data, source)
                if not gif_url:
                    continue

                file_id = f"{next_file_id:06d}"
                gif_path = gifs_dir / f"{file_id}.gif"

                if download_file(gif_url, gif_path):
                    title = get_gif_title(gif_data, source)
                    slug = gif_data.get("slug", "") if source == "giphy" else ""

                    meta = {
                        "id": file_id,
                        "giphy_id": api_id if source == "giphy" else "",
                        "description": title,
                        "source": source,
                        "category": query.split()[0] if query else "trending",
                        "slug": slug,
                        "rating": gif_data.get("rating", ""),
                        "original_url": gif_data.get("url", ""),
                        "file_size": gif_path.stat().st_size,
                    }

                    # Save metadata JSON
                    meta_path = meta_dir / f"{file_id}.json"
                    with open(meta_path, "w") as f:
                        json.dump(meta, f, indent=2)

                    results.append(meta)
                    seen_ids.add(api_id)
                    next_file_id += 1
                    query_count += 1

            offset += BATCH_SIZE

            # If no new results at this offset, move to next query
            if query_count == 0:
                break

        print(f"  [{qi+1}/{len(queries)}] '{query}': {query_count} GIFs "
              f"(total: {len(results)}/{target_count})")

    # Top up with trending if we haven't reached the target
    remaining = target_count - len(results)
    if remaining > 0:
        print(f"\n  Topping up with {remaining} trending GIFs...")
        trending_offset = 0
        while len(results) < target_count:
            if api_calls > 0 and rate_delay > 0:
                time.sleep(rate_delay)
            try:
                data = trending_api(api_key, source, offset=trending_offset, limit=BATCH_SIZE)
                api_calls += 1
            except Exception as e:
                print(f"  Trending API error: {e}")
                break

            gif_list = data.get("data", data.get("results", []))
            if not gif_list:
                break

            for gif_data in gif_list:
                if len(results) >= target_count:
                    break
                api_id = get_gif_id(gif_data, source)
                if not api_id or api_id in seen_ids:
                    continue
                gif_url = get_gif_url(gif_data, source)
                if not gif_url:
                    continue

                file_id = f"{next_file_id:06d}"
                gif_path = gifs_dir / f"{file_id}.gif"
                if download_file(gif_url, gif_path):
                    title = get_gif_title(gif_data, source)
                    meta = {
                        "id": file_id,
                        "giphy_id": api_id if source == "giphy" else "",
                        "description": title,
                        "source": source,
                        "category": "trending",
                        "slug": gif_data.get("slug", "") if source == "giphy" else "",
                        "rating": gif_data.get("rating", ""),
                        "original_url": gif_data.get("url", ""),
                        "file_size": gif_path.stat().st_size,
                    }
                    meta_path = meta_dir / f"{file_id}.json"
                    with open(meta_path, "w") as f:
                        json.dump(meta, f, indent=2)
                    results.append(meta)
                    seen_ids.add(api_id)
                    next_file_id += 1

            trending_offset += BATCH_SIZE

    print(f"\n  Downloaded {len(results)} GIFs ({api_calls} API calls)")
    return results, api_calls


def collect_local_gifs(input_dir: Path, output_dir: Path) -> List[Dict]:
    """
    Collect local GIF files and generate metadata.
    Copies files with sequential IDs to match pipeline expectations.
    """
    gifs_dir = output_dir / "gifs"
    meta_dir = output_dir / "metadata"
    gifs_dir.mkdir(parents=True, exist_ok=True)
    meta_dir.mkdir(parents=True, exist_ok=True)

    # Find all GIF files recursively
    gif_files = sorted(input_dir.rglob("*.gif"))
    if not gif_files:
        # Also try WebP, MP4 (will be converted later)
        gif_files = sorted(input_dir.rglob("*.gif")) + sorted(input_dir.rglob("*.webp"))

    print(f"\nFound {len(gif_files)} GIF files in {input_dir}")

    results: List[Dict] = []
    for i, src_path in enumerate(gif_files, start=1):
        file_id = f"{i:06d}"
        dst_path = gifs_dir / f"{file_id}.gif"

        # Copy or symlink
        shutil.copy2(src_path, dst_path)

        # Generate metadata from filename
        stem = src_path.stem.replace("-", " ").replace("_", " ")
        meta = {
            "id": file_id,
            "giphy_id": "",
            "description": stem,
            "source": "local",
            "category": "funny",  # default
            "slug": src_path.stem,
            "rating": "",
            "original_url": "",
            "file_size": dst_path.stat().st_size,
        }
        meta_path = meta_dir / f"{file_id}.json"
        with open(meta_path, "w") as f:
            json.dump(meta, f, indent=2)
        results.append(meta)

    print(f"  Collected {len(results)} GIFs")
    return results


# ═══════════════════════════════════════════════════════════════════════════════
# Step 2: Convert GIFs to WebP thumbnails
# ═══════════════════════════════════════════════════════════════════════════════

def check_ffmpeg() -> bool:
    """Check if ffmpeg is available."""
    try:
        result = subprocess.run(["ffmpeg", "-version"], capture_output=True, text=True)
        return result.returncode == 0
    except FileNotFoundError:
        return False


def convert_gif_to_webp(input_path: Path, output_path: Path,
                        max_size: int = 80, quality: int = 70) -> bool:
    """Convert a GIF to a static WebP thumbnail using Pillow."""
    try:
        with Image.open(input_path) as img:
            # Use middle frame for thumbnail
            n_frames = getattr(img, "n_frames", 1)
            if n_frames > 1:
                img.seek(n_frames // 2)

            frame = img.convert("RGBA")
            frame.thumbnail((max_size, max_size), Image.Resampling.LANCZOS)
            frame.save(output_path, "WEBP", quality=quality, method=6)
            return output_path.exists() and output_path.stat().st_size > 50
    except Exception as e:
        # Pillow can't handle some GIFs — fall back to ffmpeg
        try:
            cmd = [
                "ffmpeg", "-y", "-i", str(input_path),
                "-vframes", "1",
                "-vf", f"scale='min({max_size},iw)':'min({max_size},ih)':force_original_aspect_ratio=decrease",
                str(output_path),
            ]
            result = subprocess.run(cmd, capture_output=True, text=True)
            return result.returncode == 0 and output_path.exists()
        except Exception:
            return False


def convert_gif_to_animated_webp(input_path: Path, output_path: Path,
                                 max_size: int = 240, quality: int = 55,
                                 fps: int = 10) -> bool:
    """Convert a GIF to animated WebP using ffmpeg (optional — for full GIFs)."""
    if not check_ffmpeg():
        return False
    try:
        cmd = [
            "ffmpeg", "-y", "-i", str(input_path),
            "-c:v", "libwebp", "-lossless", "0",
            "-q:v", str(quality),
            "-vf", f"fps={fps},scale='min({max_size},iw)':'min({max_size},ih)':force_original_aspect_ratio=decrease",
            "-loop", "0",
            str(output_path),
        ]
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
        return result.returncode == 0 and output_path.exists() and output_path.stat().st_size > 100
    except Exception:
        return False


def process_gifs(work_dir: Path, include_animated: bool = False) -> Tuple[Path, Path]:
    """
    Convert all downloaded GIFs to WebP thumbnails (and optionally animated WebP).
    Returns (thumbs_dir, full_dir).
    """
    gifs_dir = work_dir / "gifs"
    thumbs_dir = work_dir / "thumbs"
    full_dir = work_dir / "full"
    thumbs_dir.mkdir(parents=True, exist_ok=True)

    gif_files = sorted(gifs_dir.glob("*.gif"))
    if not gif_files:
        gif_files = sorted(gifs_dir.glob("*.webp"))
    print(f"\nConverting {len(gif_files)} GIFs to WebP thumbnails...")

    has_ffmpeg = check_ffmpeg()
    if include_animated and not has_ffmpeg:
        print("  WARNING: ffmpeg not found — skipping animated WebP conversion")
        include_animated = False

    if include_animated:
        full_dir.mkdir(parents=True, exist_ok=True)

    success = 0
    failed = 0
    for gif_path in tqdm(gif_files, desc="Converting", total=len(gif_files)):
        file_id = gif_path.stem
        thumb_path = thumbs_dir / f"{file_id}.webp"

        if convert_gif_to_webp(gif_path, thumb_path):
            success += 1
        else:
            failed += 1

        # Optional: animated WebP for full-size GIF
        if include_animated:
            full_path = full_dir / f"{file_id}.webp"
            convert_gif_to_animated_webp(gif_path, full_path)

    print(f"  Converted: {success}, Failed: {failed}")
    return thumbs_dir, full_dir


# ═══════════════════════════════════════════════════════════════════════════════
# Step 3: Build pack.db
# ═══════════════════════════════════════════════════════════════════════════════

def extract_keywords(text: str) -> List[str]:
    """Extract searchable keywords from description text."""
    if not text:
        return []
    text = text.lower()
    text = re.sub(r"[^a-z0-9\s]", " ", text)
    words = text.split()
    seen: Set[str] = set()
    keywords = []
    for w in words:
        if w not in STOP_WORDS and len(w) > 1 and w not in seen:
            seen.add(w)
            keywords.append(w)
    # Compound words for adjacent pairs
    compounds = []
    for idx in range(len(keywords) - 1):
        a, b = keywords[idx], keywords[idx + 1]
        if len(a) >= 3 and len(b) >= 3:
            compound = a + b
            if compound not in seen:
                compounds.append(compound)
                seen.add(compound)
    keywords.extend(compounds)
    return keywords[:25]


def classify_gif(keywords: List[str], description: str) -> List[int]:
    """Classify a GIF into emotion categories based on keywords."""
    text = " ".join(keywords) + " " + (description or "").lower()
    matched = []
    for cat_id, cat_data in CATEGORIES.items():
        for kw in cat_data["keywords"]:
            if kw in text:
                matched.append(cat_id)
                break
    if not matched:
        matched = [1]  # Default to Funny
    return matched[:3]


def build_pack_db(
    work_dir: Path,
    metadata_list: List[Dict],
    db_path: Path,
) -> int:
    """
    Build a per-pack SQLite database matching CleverKeys' import format.
    Plain tables only — NO FTS5 (Android can't ATTACH DBs with FTS5).
    Returns number of GIFs inserted.
    """
    thumbs_dir = work_dir / "thumbs"

    if db_path.exists():
        db_path.unlink()

    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()

    cursor.execute("PRAGMA journal_mode = OFF")
    cursor.execute("PRAGMA synchronous = OFF")
    cursor.execute("PRAGMA page_size = 4096")

    # Schema must match GifDatabase.kt's ATTACH + INSERT ... SELECT expectations
    cursor.executescript("""
        CREATE TABLE categories (
            category_id INTEGER PRIMARY KEY,
            name TEXT NOT NULL UNIQUE,
            icon TEXT NOT NULL,
            sort_order INTEGER NOT NULL
        );

        CREATE TABLE gifs (
            gif_id INTEGER PRIMARY KEY,
            width INTEGER NOT NULL,
            height INTEGER NOT NULL,
            duration_ms INTEGER DEFAULT 0,
            file_size INTEGER DEFAULT 0,
            pack_id INTEGER DEFAULT 0,
            search_text TEXT DEFAULT '',
            created_at INTEGER DEFAULT 0
        );

        CREATE TABLE gif_category_map (
            category_id INTEGER NOT NULL,
            gif_id INTEGER NOT NULL,
            PRIMARY KEY (category_id, gif_id),
            FOREIGN KEY (gif_id) REFERENCES gifs(gif_id) ON DELETE CASCADE,
            FOREIGN KEY (category_id) REFERENCES categories(category_id) ON DELETE CASCADE
        ) WITHOUT ROWID;
    """)

    # Insert all 17 categories
    for cat_id, cat_data in CATEGORIES.items():
        cursor.execute(
            "INSERT INTO categories (category_id, name, icon, sort_order) VALUES (?, ?, ?, ?)",
            (cat_id, cat_data["name"], cat_data["icon"], cat_id)
        )

    inserted = 0
    for meta in tqdm(metadata_list, desc="Building DB", total=len(metadata_list)):
        file_id = meta["id"]
        gif_id_int = int(file_id.lstrip("0") or "0")

        # Check thumbnail exists
        thumb_path = thumbs_dir / f"{file_id}.webp"
        if not thumb_path.exists():
            continue

        # Get thumbnail dimensions
        try:
            with Image.open(thumb_path) as img:
                width, height = img.size
        except Exception:
            width, height = 80, 80

        file_size = thumb_path.stat().st_size
        description = meta.get("description", "")
        slug = meta.get("slug", "").replace("-", " ").lower() if meta.get("slug") else ""
        combined = f"{description} {slug}"

        keywords = extract_keywords(combined)
        categories = classify_gif(keywords, combined)
        search_text = " ".join(keywords)

        try:
            cursor.execute(
                """INSERT INTO gifs
                   (gif_id, width, height, duration_ms, file_size, pack_id, search_text, created_at)
                   VALUES (?, ?, ?, 0, ?, 0, ?, 0)""",
                (gif_id_int, width, height, file_size, search_text)
            )
            for cat_id in categories:
                cursor.execute(
                    "INSERT OR IGNORE INTO gif_category_map (category_id, gif_id) VALUES (?, ?)",
                    (cat_id, gif_id_int)
                )
            inserted += 1
        except Exception as e:
            print(f"\n  DB insert error for {file_id}: {e}")

    conn.commit()
    cursor.execute("VACUUM")
    conn.close()

    db_size = db_path.stat().st_size
    print(f"  Database: {inserted} GIFs, {db_size / 1024:.1f} KB")
    return inserted


# ═══════════════════════════════════════════════════════════════════════════════
# Step 4: Package as importable ZIP
# ═══════════════════════════════════════════════════════════════════════════════

def build_zip(
    pack_name: str,
    work_dir: Path,
    db_path: Path,
    metadata_list: List[Dict],
    output_dir: Path,
    include_animated: bool = False,
) -> Path:
    """
    Create a CleverKeys-importable ZIP archive:
      manifest.json
      pack.db
      thumbs/{partition}/{id}.webp
      full/{partition}/{id}.webp  (optional)
    """
    thumbs_dir = work_dir / "thumbs"
    full_dir = work_dir / "full"
    output_dir.mkdir(parents=True, exist_ok=True)
    zip_path = output_dir / f"{pack_name}.zip"

    manifest = {
        "pack_id": pack_name,
        "name": pack_name.replace("-", " ").replace("_", " ").title(),
        "version": 1,
        "gif_count": len(metadata_list),
        "description": f"Personal GIF pack — {len(metadata_list)} GIFs",
    }
    if include_animated:
        manifest["has_full_gifs"] = True

    print(f"\nPackaging {pack_name}.zip ({len(metadata_list)} GIFs)...")

    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_STORED) as zf:
        # manifest.json
        zf.writestr("manifest.json", json.dumps(manifest, separators=(",", ":")))

        # pack.db
        zf.write(db_path, "pack.db")

        # Thumbnails: thumbs/{partition}/{id}.webp
        thumb_count = 0
        for meta in metadata_list:
            file_id = meta["id"]
            file_id_int = int(file_id.lstrip("0") or "0")
            partition = "%03d" % (file_id_int // 1000)
            thumb_src = thumbs_dir / f"{file_id}.webp"
            if thumb_src.exists():
                arcname = f"thumbs/{partition}/{file_id_int:06d}.webp"
                zf.write(thumb_src, arcname)
                thumb_count += 1

        # Optional: full animated WebPs
        full_count = 0
        if include_animated and full_dir.exists():
            for meta in metadata_list:
                file_id = meta["id"]
                file_id_int = int(file_id.lstrip("0") or "0")
                partition = "%03d" % (file_id_int // 1000)
                full_src = full_dir / f"{file_id}.webp"
                if full_src.exists():
                    arcname = f"full/{partition}/{file_id_int:06d}.webp"
                    zf.write(full_src, arcname)
                    full_count += 1

    zip_size = zip_path.stat().st_size
    print(f"  {zip_path.name}: {zip_size / 1024 / 1024:.1f} MB "
          f"({thumb_count} thumbs" +
          (f", {full_count} full" if full_count else "") + ")")

    return zip_path


# ═══════════════════════════════════════════════════════════════════════════════
# Interactive Setup
# ═══════════════════════════════════════════════════════════════════════════════

def interactive_setup() -> Dict:
    """Guide user through API key setup and pack preferences."""
    print("=" * 60)
    print("  CleverKeys GIF Pack Builder — Interactive Setup")
    print("=" * 60)
    print()

    # Source selection
    print("GIF Sources:")
    print("  1. Giphy  — Largest library, free beta key (42 req/hr)")
    print("  2. Klipy  — Tenor replacement, free key (100 req/min)")
    print("  3. Local  — Use your own GIF files (no API key needed)")
    print()
    choice = input("Choose source [1/2/3]: ").strip()
    if choice == "3":
        source = "local"
    elif choice == "2":
        source = "klipy"
    else:
        source = "giphy"

    api_key = ""
    if source != "local":
        print()
        if source == "giphy":
            print("Get a free Giphy API key:")
            print("  1. Go to https://developers.giphy.com/")
            print("  2. Click 'Get Started' → Create an account")
            print("  3. Click 'Create an App' → Choose 'API' (not SDK)")
            print("  4. Copy your API key")
        else:
            print("Get a free Klipy API key:")
            print("  1. Go to https://klipy.com/migrate")
            print("  2. Sign up for a free account")
            print("  3. Go to Partner Dashboard → get your API key")

        print()
        api_key = input("Paste your API key: ").strip()
        if not api_key:
            print("ERROR: API key is required")
            sys.exit(1)

    # Pack preferences
    print()
    print("Pack Presets:")
    for name, preset in PRESETS.items():
        print(f"  {name:12s} — {preset['description']}")
    print()
    preset = input("Choose preset [reactions]: ").strip() or "reactions"
    if preset not in PRESETS:
        print(f"Unknown preset '{preset}', using 'reactions'")
        preset = "reactions"

    print()
    count_str = input("How many GIFs? [500]: ").strip() or "500"
    count = int(count_str)

    pack_name = input("Pack name [my-gifs]: ").strip() or "my-gifs"
    # Sanitize name
    pack_name = re.sub(r"[^a-z0-9_-]", "-", pack_name.lower())

    return {
        "source": source,
        "api_key": api_key,
        "preset": preset,
        "count": count,
        "name": pack_name,
    }


# ═══════════════════════════════════════════════════════════════════════════════
# Main
# ═══════════════════════════════════════════════════════════════════════════════

def main():
    parser = argparse.ArgumentParser(
        description="Build a personal GIF pack for CleverKeys keyboard",
        epilog="Example: python make_pack.py --source giphy --preset reactions --count 500",
    )
    parser.add_argument("--source", choices=["giphy", "klipy", "local"],
                        help="GIF source (giphy, klipy, or local)")
    parser.add_argument("--api-key", type=str,
                        help="API key (or set GIPHY_API_KEY / KLIPY_API_KEY env var)")
    parser.add_argument("--preset", choices=list(PRESETS.keys()),
                        help="Search preset (reactions, positive, humor, negative, surprise, animals, all)")
    parser.add_argument("--queries", type=str,
                        help="Custom search queries, comma-separated (overrides --preset)")
    parser.add_argument("--count", type=int, default=500,
                        help="Total GIFs to download (default: 500)")
    parser.add_argument("--per-category", type=int, default=0,
                        help="GIFs per search query (default: auto)")
    parser.add_argument("--input", type=str,
                        help="Input directory for --source local")
    parser.add_argument("--name", type=str, default="my-gifs",
                        help="Pack name (default: my-gifs)")
    parser.add_argument("--output", type=str, default="./packs",
                        help="Output directory for ZIP (default: ./packs)")
    parser.add_argument("--work-dir", type=str, default=None,
                        help="Working directory for intermediate files (default: temp dir)")
    parser.add_argument("--keep-work-dir", action="store_true",
                        help="Keep working directory after build (don't delete)")
    parser.add_argument("--include-animated", action="store_true",
                        help="Include full animated WebP files (larger ZIP, requires ffmpeg)")
    parser.add_argument("--dry-run", action="store_true",
                        help="Show what would be done without downloading")
    parser.add_argument("--no-rate-limit", action="store_true",
                        help="Disable rate limiting (use carefully — may get banned)")

    args = parser.parse_args()

    # If no source specified, run interactive mode
    if not args.source:
        config = interactive_setup()
        args.source = config["source"]
        args.api_key = config["api_key"]
        args.preset = config["preset"]
        args.count = config["count"]
        args.name = config["name"]

    # Resolve API key
    api_key = args.api_key or ""
    if not api_key and args.source == "giphy":
        api_key = os.getenv("GIPHY_API_KEY", "")
    elif not api_key and args.source == "klipy":
        api_key = os.getenv("KLIPY_API_KEY", "")

    if args.source != "local" and not api_key:
        print(f"ERROR: API key required for {args.source}")
        print(f"  Use --api-key, or set {'GIPHY_API_KEY' if args.source == 'giphy' else 'KLIPY_API_KEY'} env var")
        sys.exit(1)

    # Resolve queries
    if args.queries:
        queries = [q.strip() for q in args.queries.split(",") if q.strip()]
    elif args.preset:
        queries = PRESETS[args.preset]["queries"]
    else:
        queries = PRESETS["reactions"]["queries"]

    per_query = args.per_category if args.per_category > 0 else max(1, args.count // len(queries))

    # Rate limit
    if args.no_rate_limit:
        rate_delay = 0
    elif args.source == "giphy":
        rate_delay = GIPHY_RATE_LIMIT_DELAY
    else:
        rate_delay = KLIPY_RATE_LIMIT_DELAY

    # Sanitize pack name
    pack_name = re.sub(r"[^a-z0-9_-]", "-", args.name.lower())

    # Working directory
    if args.work_dir:
        work_dir = Path(args.work_dir)
        work_dir.mkdir(parents=True, exist_ok=True)
        cleanup_work_dir = False
    else:
        work_dir = Path(tempfile.mkdtemp(prefix=f"gifpack_{pack_name}_"))
        cleanup_work_dir = not args.keep_work_dir

    output_dir = Path(args.output)

    print(f"\n{'=' * 60}")
    print(f"  Pack: {pack_name}")
    print(f"  Source: {args.source}")
    print(f"  Target: {args.count} GIFs")
    print(f"  Queries: {len(queries)}")
    print(f"  Work dir: {work_dir}")
    print(f"  Output: {output_dir}")
    print(f"{'=' * 60}")

    if args.dry_run:
        print("\n--- DRY RUN ---")
        print(f"Would download ~{args.count} GIFs from {args.source}")
        print(f"Queries: {', '.join(queries[:10])}" + (f" (+{len(queries)-10} more)" if len(queries) > 10 else ""))
        estimated_size = args.count * 1.5 / 1024  # ~1.5 KB per thumb avg
        print(f"Estimated pack size: ~{estimated_size:.1f} MB (thumbnails only)")
        if args.include_animated:
            print(f"With animated: ~{estimated_size * 40:.0f} MB")
        return

    try:
        # Step 1: Download / collect GIFs
        if args.source == "local":
            if not args.input:
                print("ERROR: --input required for --source local")
                sys.exit(1)
            metadata_list = collect_local_gifs(Path(args.input), work_dir)
        else:
            metadata_list, api_calls = download_gifs(
                api_key=api_key,
                source=args.source,
                queries=queries,
                target_count=args.count,
                per_query=per_query,
                output_dir=work_dir,
                rate_delay=rate_delay,
            )

        if not metadata_list:
            print("ERROR: No GIFs downloaded/collected")
            sys.exit(1)

        # Step 2: Convert to WebP thumbnails
        thumbs_dir, full_dir = process_gifs(work_dir, include_animated=args.include_animated)

        # Step 3: Build pack.db
        db_path = work_dir / "pack.db"
        inserted = build_pack_db(work_dir, metadata_list, db_path)

        if inserted == 0:
            print("ERROR: No GIFs were inserted into database")
            sys.exit(1)

        # Step 4: Package ZIP
        zip_path = build_zip(
            pack_name=pack_name,
            work_dir=work_dir,
            db_path=db_path,
            metadata_list=metadata_list,
            output_dir=output_dir,
            include_animated=args.include_animated,
        )

        # Summary
        print(f"\n{'=' * 60}")
        print(f"  Pack built successfully!")
        print(f"  {zip_path}")
        print(f"  {inserted} GIFs, {zip_path.stat().st_size / 1024 / 1024:.1f} MB")
        print(f"{'=' * 60}")
        print(f"\nTo import into CleverKeys:")
        print(f"  1. Copy {zip_path.name} to your phone")
        print(f"  2. Open CleverKeys Settings → GIF Panel → Enable")
        print(f"  3. Tap 'Import Pack' → select the ZIP file")
        print()

    finally:
        # Cleanup work directory
        if cleanup_work_dir and work_dir.exists():
            print(f"Cleaning up work directory: {work_dir}")
            shutil.rmtree(work_dir, ignore_errors=True)


if __name__ == "__main__":
    main()
