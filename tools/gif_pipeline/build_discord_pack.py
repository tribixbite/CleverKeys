#!/usr/bin/env python3
"""
build_discord_pack.py — Convert Discord-sourced GIFs into CleverKeys packs.

Reads the stoatcord-bot output (dl-index.json + gifs/), extracts metadata from
Tenor slugs and URLs, converts to optimized animated WebP + thumbnails, builds
pack.db, and produces ZIP pack(s) ready for import.

Sources handled:
  - Tenor (83%): slug → keywords, tenor_id
  - Giphy (0.3%): slug → keywords, giphy_id
  - Imgur/Discord/other (17%): filename-only, minimal keywords

Pipeline steps:
  1. Parse dl-index.json → extract metadata per GIF
  2. Filter to animated files (skip JPG/PNG stills, corrupt files)
  3. Convert GIF→animated WebP (ffmpeg, balanced profile)
  4. Generate static WebP thumbnails (Pillow)
  5. Write per-GIF metadata JSON
  6. Build pack.db (SQLite with categories + search_text)
  7. Assemble ZIP pack(s) targeting ~200MB each

Usage:
    # Dry run — analyze and plan packs
    python build_discord_pack.py --dry-run

    # Full pipeline
    python build_discord_pack.py

    # Skip conversion (if already processed)
    python build_discord_pack.py --skip-convert

    # Custom profile
    python build_discord_pack.py --profile quality
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
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple
from urllib.parse import parse_qs, unquote, urlparse

from PIL import Image
from tqdm import tqdm

# Import shared utilities from existing pipeline
sys.path.insert(0, str(Path(__file__).parent))
from build_database import CATEGORIES, STOP_WORDS, classify_gif

# ─── Configuration ───────────────────────────────────────────────────────────

# Compression profiles matching process_gifs.py
PROFILES = {
    "balanced": {
        "full": {"max_width": 240, "max_height": 240, "quality": 55, "fps": 10, "lossless": False},
        "thumb": {"max_width": 80, "max_height": 80, "quality": 70, "frame": "middle"},
    },
    "quality": {
        "full": {"max_width": 320, "max_height": 320, "quality": 60, "fps": 12, "lossless": False},
        "thumb": {"max_width": 120, "max_height": 120, "quality": 70, "frame": "middle"},
    },
    "hd": {
        "full": {"max_width": 480, "max_height": 480, "quality": 70, "fps": 15, "lossless": False},
        "thumb": {"max_width": 160, "max_height": 160, "quality": 80, "frame": "middle"},
    },
}

# GIF ID offset — Discord GIFs get IDs starting here to avoid collisions
# with the existing Giphy pipeline's sequential 000000-130094 range.
DISCORD_ID_OFFSET = 200000

# Pack size target per ZIP — ~100 MB keeps downloads feasible on mobile
TARGET_PACK_MB = 100
# Fallback max GIF count per pack (only used when sizes unknown)
TARGET_PACK_GIFS = 5000

# Animated file extensions we can process
ANIMATED_EXTS = {".gif", ".webp", ".gifv"}

# Static files we'll skip for the animated pipeline but could thumbnail
STATIC_EXTS = {".jpg", ".jpeg", ".png"}

# ─── Metadata extraction ─────────────────────────────────────────────────────


def parse_tenor_slug(url: str) -> Dict:
    """Extract keywords and ID from a Tenor URL slug.

    Tenor URLs look like:
      https://tenor.com/view/minecraft-memes-villager-gif-5890516410501352645
    The slug is: "minecraft-memes-villager-gif-5890516410501352645"
    """
    result = {"source": "tenor", "tenor_id": "", "slug": "", "keywords": []}

    if "/view/" not in url:
        return result

    slug = unquote(url.split("/view/")[-1]).strip("/")
    result["slug"] = slug

    # Extract tenor ID (number after last "-gif-" or just the last numeric segment)
    if "-gif-" in slug:
        parts = slug.rsplit("-gif-", 1)
        result["tenor_id"] = parts[-1]
        keyword_part = parts[0]
    else:
        # Fallback: last purely numeric segment
        segments = slug.split("-")
        if segments and segments[-1].isdigit():
            result["tenor_id"] = segments[-1]
            keyword_part = "-".join(segments[:-1])
        else:
            keyword_part = slug

    # Parse keywords from the slug
    words = keyword_part.replace("-", " ").replace("_", " ").lower().split()
    # Filter stop words and very short words
    result["keywords"] = [w for w in words if w not in STOP_WORDS and len(w) > 1]

    return result


def parse_giphy_url(url: str) -> Dict:
    """Extract keywords and ID from a Giphy URL."""
    result = {"source": "giphy", "giphy_id": "", "slug": "", "keywords": []}

    path = urlparse(url).path.rstrip("/")
    segments = path.split("/")

    # URL format: /gifs/SLUG-ID or /gifs/ID
    for seg in reversed(segments):
        if seg and seg != "gifs":
            result["slug"] = seg
            if "-" in seg:
                parts = seg.rsplit("-", 1)
                result["giphy_id"] = parts[-1]
                keyword_part = parts[0]
            else:
                result["giphy_id"] = seg
                keyword_part = ""

            if keyword_part:
                words = keyword_part.replace("-", " ").lower().split()
                result["keywords"] = [w for w in words if w not in STOP_WORDS and len(w) > 1]
            break

    return result


def parse_imgur_url(url: str) -> Dict:
    """Extract minimal metadata from an Imgur URL."""
    result = {"source": "imgur", "imgur_id": "", "keywords": []}
    path = urlparse(url).path.rstrip("/")
    # Imgur IDs are the filename stem
    filename = path.split("/")[-1]
    stem = filename.rsplit(".", 1)[0] if "." in filename else filename
    result["imgur_id"] = stem
    return result


def extract_metadata(url: str, info: Dict) -> Dict:
    """Extract metadata from a dl-index entry.

    Returns a dict with: source, keywords, description, slug, source_id,
    filename, file_size.
    """
    meta = {
        "url": url,
        "filename": info.get("filename", ""),
        "media_url": info.get("mediaUrl", ""),
        "file_size": info.get("size", 0),
        "source": "unknown",
        "source_id": "",
        "slug": "",
        "keywords": [],
        "description": "",
    }

    if "tenor.com" in url:
        parsed = parse_tenor_slug(url)
        meta["source"] = "tenor"
        meta["source_id"] = parsed["tenor_id"]
        meta["slug"] = parsed["slug"]
        meta["keywords"] = parsed["keywords"]
        meta["description"] = " ".join(parsed["keywords"]).title() if parsed["keywords"] else ""

        # Fallback for short Tenor URLs (tenor.com/xFlV.gif) — extract from media_url
        if not meta["keywords"] and meta["media_url"] and "tenor.com" in meta["media_url"]:
            media_path = urlparse(meta["media_url"]).path
            media_filename = media_path.split("/")[-1] if "/" in media_path else ""
            if media_filename and "." in media_filename:
                stem = media_filename.rsplit(".", 1)[0]
                if stem and len(stem) > 2:
                    words = stem.replace("-", " ").replace("_", " ").lower().split()
                    kw = [w for w in words if w not in STOP_WORDS and len(w) > 1]
                    if kw:
                        meta["slug"] = stem
                        meta["keywords"] = kw
                        meta["description"] = " ".join(kw).title()

    elif "giphy.com" in url:
        parsed = parse_giphy_url(url)
        meta["source"] = "giphy"
        meta["source_id"] = parsed["giphy_id"]
        meta["slug"] = parsed["slug"]
        meta["keywords"] = parsed["keywords"]
        meta["description"] = " ".join(parsed["keywords"]).title() if parsed["keywords"] else ""

    elif "imgur.com" in url:
        parsed = parse_imgur_url(url)
        meta["source"] = "imgur"
        meta["source_id"] = parsed["imgur_id"]

    elif "discord" in url:
        meta["source"] = "discord"
        # Extract emoji name from ?name= query param (e.g. ?name=WAAAAAAAH)
        parsed_url = urlparse(url)
        params = parse_qs(parsed_url.query)
        name = params.get("name", [""])[0]
        if name:
            words = re.sub(r"([a-z])([A-Z])", r"\1 \2", name)
            words = re.sub(r"[^a-zA-Z0-9\s]", " ", words).lower().split()
            meta["keywords"] = [w for w in words if len(w) > 1 and w not in STOP_WORDS]
            meta["description"] = name
            meta["slug"] = name.lower()
        else:
            # Fallback: extract from filename
            path = parsed_url.path
            fname = path.split("/")[-1].split("?")[0]
            if fname and not fname.startswith("http"):
                stem = fname.rsplit(".", 1)[0] if "." in fname else fname
                words = re.sub(r"[^a-z0-9]", " ", stem.lower()).split()
                meta["keywords"] = [w for w in words if len(w) > 2 and w not in STOP_WORDS]
    else:
        meta["source"] = "other"

    return meta


# ─── Usage frequency from Discord posts ──────────────────────────────────────


def load_usage_frequency(ndjson_path: Path, dl_index: Dict) -> Dict[str, int]:
    """Count how many times each GIF file was used in Discord.

    Maps filename → usage count by resolving URL → filename through dl_index.
    """
    url_counts: Counter = Counter()
    with open(ndjson_path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                entry = json.loads(line)
                url_counts[entry.get("url", "")] += 1
            except (json.JSONDecodeError, KeyError):
                pass

    # Map URL usage counts to filenames
    file_usage: Dict[str, int] = {}
    for url, count in url_counts.items():
        if url in dl_index:
            fn = dl_index[url].get("filename", "")
            if fn:
                file_usage[fn] = file_usage.get(fn, 0) + count

    return file_usage


# ─── Conversion pipeline ─────────────────────────────────────────────────────


def get_gif_info(gif_path: Path) -> Optional[Dict]:
    """Get GIF/WebP metadata using ffprobe."""
    try:
        result = subprocess.run(
            ["ffprobe", "-v", "quiet", "-print_format", "json",
             "-show_format", "-show_streams", str(gif_path)],
            capture_output=True, text=True, timeout=30,
        )
        if result.returncode != 0:
            return None
        data = json.loads(result.stdout)
        stream = data.get("streams", [{}])[0]
        return {
            "width": stream.get("width", 0),
            "height": stream.get("height", 0),
            "duration": float(data.get("format", {}).get("duration", 0)),
            "nb_frames": int(stream.get("nb_frames", 0)),
            "file_size": int(data.get("format", {}).get("size", 0)),
        }
    except Exception:
        return None


def is_animated(gif_path: Path) -> bool:
    """Check if a GIF/WebP has multiple frames (is animated)."""
    try:
        with Image.open(gif_path) as img:
            return getattr(img, "n_frames", 1) > 1
    except Exception:
        return False


def convert_to_animated_webp(
    input_path: Path, output_path: Path, config: Dict
) -> bool:
    """Convert GIF/WebP to optimized animated WebP using ffmpeg."""
    try:
        info = get_gif_info(input_path)
        if not info or info["width"] == 0:
            return False

        # Reject broken GIFs with insane frame delays
        duration = info.get("duration", 0)
        frames = max(info.get("nb_frames", 1), 1)
        if duration > 0 and duration / frames > 5.0:
            return False

        # Calculate target dimensions
        w, h = info["width"], info["height"]
        max_w, max_h = config["max_width"], config["max_height"]
        if w > max_w or h > max_h:
            ratio = min(max_w / w, max_h / h)
            w = int(w * ratio)
            h = int(h * ratio)
        # Ensure even dimensions
        w = w if w % 2 == 0 else w + 1
        h = h if h % 2 == 0 else h + 1

        cmd = [
            "ffmpeg", "-y", "-i", str(input_path),
            "-c:v", "libwebp",
            "-lossless", "0",
            "-q:v", str(config["quality"]),
            "-vf", f"fps={config['fps']},scale={w}:{h}:flags=lanczos",
            "-loop", "0",
            str(output_path),
        ]
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
        return result.returncode == 0 and output_path.exists() and output_path.stat().st_size > 100
    except Exception:
        return False


def generate_thumbnail(input_path: Path, output_path: Path, config: Dict) -> bool:
    """Generate static WebP thumbnail from GIF/WebP using Pillow."""
    try:
        with Image.open(input_path) as img:
            n_frames = getattr(img, "n_frames", 1)
            if config["frame"] == "middle" and n_frames > 1:
                img.seek(n_frames // 2)
            frame = img.convert("RGBA")
            frame.thumbnail((config["max_width"], config["max_height"]), Image.Resampling.LANCZOS)
            frame.save(output_path, "WEBP", quality=config["quality"], method=6)
            return output_path.exists() and output_path.stat().st_size > 50
    except Exception:
        return False


def process_single(args: Tuple) -> Dict:
    """Process a single GIF file: convert + thumbnail."""
    input_path, full_output, thumb_output, full_config, thumb_config = args
    result = {"filename": input_path.name, "success": False,
              "full_size": 0, "thumb_size": 0, "width": 0, "height": 0, "duration_ms": 0}

    info = get_gif_info(input_path)
    if info:
        result["original_width"] = info["width"]
        result["original_height"] = info["height"]
        result["duration_ms"] = int(info["duration"] * 1000)

    if convert_to_animated_webp(input_path, full_output, full_config):
        result["full_size"] = full_output.stat().st_size
        pinfo = get_gif_info(full_output)
        if pinfo:
            result["width"] = pinfo["width"]
            result["height"] = pinfo["height"]

    if generate_thumbnail(input_path, thumb_output, thumb_config):
        result["thumb_size"] = thumb_output.stat().st_size
        result["success"] = result["full_size"] > 0  # Need both full + thumb

    return result


# ─── Keyword extraction (matching build_database.py) ─────────────────────────


def extract_keywords(text: str) -> List[str]:
    """Extract and deduplicate keywords, with compound word generation."""
    if not text:
        return []
    text = re.sub(r"[^a-z0-9\s]", " ", text.lower())
    words = text.split()
    seen: Set[str] = set()
    keywords: List[str] = []
    for w in words:
        if w not in STOP_WORDS and len(w) > 1 and w not in seen:
            seen.add(w)
            keywords.append(w)

    # Compound word forms for adjacent pairs
    compounds = []
    for i in range(len(keywords) - 1):
        a, b = keywords[i], keywords[i + 1]
        if len(a) >= 3 and len(b) >= 3:
            compound = a + b
            if compound not in seen:
                compounds.append(compound)
                seen.add(compound)
    keywords.extend(compounds)
    return keywords[:25]


# ─── Emotion classification ──────────────────────────────────────────────────

# Extended keyword map including internet/meme culture terms common in Discord
DISCORD_EMOTION_KEYWORDS = {
    1: ["laugh", "funny", "lol", "haha", "hilarious", "meme", "rofl", "lmao",
        "wheeze", "bruh", "shitpost", "comedy", "joke", "humor", "giggle", "cackling"],
    2: ["angry", "mad", "rage", "furious", "pissed", "hate", "anger", "triggered",
        "frustrated", "annoyed", "irritated"],
    3: ["smug", "smirk", "sarcastic", "whatever", "eyeroll", "superior", "smuganimegirl",
        "dismissive", "condescending", "unimpressed"],
    5: ["disgusted", "gross", "ew", "yuck", "nasty", "cringe", "eww", "vomit"],
    6: ["awkward", "embarrassed", "oops", "nervous", "facepalm", "uncomfortable",
        "shy", "blush", "cringe"],
    7: ["excited", "yay", "hype", "pumped", "hyped", "lets go", "woohoo", "stoked",
        "poggers", "pog", "celebration", "celebrate", "party"],
    8: ["scared", "terrified", "fear", "horror", "panic", "spooky", "nightmare",
        "creepy", "jump scare", "nope"],
    9: ["sorry", "apologize", "guilt", "regret", "forgive", "oops", "mybad"],
    10: ["happy", "smile", "joy", "cheerful", "pleased", "glad", "wholesome", "cute",
         "uwu", "delighted", "grin", "beam"],
    11: ["love", "heart", "kiss", "romantic", "crush", "hug", "cuddle", "affection",
         "adore", "sweet", "kawaii", "aww"],
    12: ["proud", "victory", "winning", "flex", "champion", "boss", "nailed",
         "based", "chad", "sigma", "gigachad", "alpha"],
    13: ["relieved", "phew", "finally", "safe", "exhale", "close call"],
    14: ["sad", "cry", "crying", "tears", "depressed", "heartbroken", "pain",
         "sobbing", "devastated", "rip", "dead"],
    15: ["ashamed", "shame", "fail", "failure", "embarrassing", "mortified",
         "disappointed", "humiliated"],
    16: ["surprised", "shocked", "omg", "wow", "what", "shook", "mindblown",
         "stunned", "amazed", "gasp", "wtf"],
    17: ["yes", "agree", "approve", "ok", "good", "nice", "thumbsup", "correct",
         "exactly", "perfect", "great", "based", "W", "dub"],
}


def classify_discord_gif(keywords: List[str], description: str) -> List[int]:
    """Classify a Discord GIF using expanded keyword matching."""
    text = " ".join(keywords).lower() + " " + description.lower()
    matched = []
    for cat_id, kw_list in DISCORD_EMOTION_KEYWORDS.items():
        for kw in kw_list:
            if kw in text:
                matched.append(cat_id)
                break

    if not matched:
        # Default: Funny (most Discord GIFs are humorous)
        matched = [1]
    return matched[:3]


# ─── Pack building ────────────────────────────────────────────────────────────


def build_discord_pack_db(
    entries: List[Dict],
    output_path: Path,
) -> Dict:
    """Build pack.db for Discord GIF entries.

    Plain tables only (no FTS5) — matches the existing pack_builder format
    so GifPackManager.kt can import via ATTACH.
    """
    if output_path.exists():
        output_path.unlink()

    conn = sqlite3.connect(output_path)
    cur = conn.cursor()
    cur.execute("PRAGMA journal_mode = OFF")
    cur.execute("PRAGMA synchronous = OFF")
    cur.execute("PRAGMA page_size = 4096")

    cur.executescript("""
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

    # Insert categories
    for cat_id, cat_data in CATEGORIES.items():
        cur.execute(
            "INSERT INTO categories (category_id, name, icon, sort_order) VALUES (?, ?, ?, ?)",
            (cat_id, cat_data["name"], cat_data["icon"], cat_id),
        )

    stats = {"inserted": 0, "failed": 0, "categories_assigned": 0}

    for entry in entries:
        gif_id = entry["gif_id"]
        keywords = entry.get("search_keywords", [])
        search_text = " ".join(keywords)
        categories = entry.get("categories", [1])

        try:
            cur.execute(
                """INSERT INTO gifs
                   (gif_id, width, height, duration_ms, file_size, pack_id, search_text, created_at)
                   VALUES (?, ?, ?, ?, ?, 0, ?, 0)""",
                (gif_id, entry.get("width", 200), entry.get("height", 200),
                 entry.get("duration_ms", 0), entry.get("opt_size", 0), search_text),
            )
            for cat_id in categories:
                cur.execute(
                    "INSERT OR IGNORE INTO gif_category_map (category_id, gif_id) VALUES (?, ?)",
                    (cat_id, gif_id),
                )
                stats["categories_assigned"] += 1
            stats["inserted"] += 1
        except Exception as e:
            print(f"  Error inserting gif_id={gif_id}: {e}")
            stats["failed"] += 1

    conn.commit()
    cur.execute("VACUUM")
    conn.close()

    stats["db_size"] = output_path.stat().st_size
    return stats


def write_discord_pack_zip(
    pack_name: str,
    entries: List[Dict],
    full_dir: Path,
    thumb_dir: Path,
    output_dir: Path,
    include_full: bool = True,
) -> Path:
    """Create ZIP archive matching GifPackManager.kt expectations."""
    zip_path = output_dir / f"{pack_name}.zip"

    # Build pack.db
    tmp_db_fd, tmp_db_path = tempfile.mkstemp(suffix=".db", prefix=f"pack_{pack_name}_")
    os.close(tmp_db_fd)
    tmp_db = Path(tmp_db_path)

    try:
        print(f"  Building pack.db for {pack_name} ({len(entries)} GIFs)...")
        build_discord_pack_db(entries, tmp_db)

        manifest = {
            "pack_id": pack_name,
            "name": pack_name.replace("-", " ").replace("_", " ").title(),
            "version": 1,
            "gif_count": len(entries),
            "description": f"Discord community GIFs — {pack_name}",
            "has_full_gifs": include_full,
        }
        manifest_json = json.dumps(manifest, separators=(",", ":")).encode()

        with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_STORED) as zf:
            zf.writestr("manifest.json", manifest_json)
            zf.write(tmp_db, "pack.db")

            # Thumbnails
            thumb_count = 0
            for entry in entries:
                gif_id = entry["gif_id"]
                thumb_file = thumb_dir / f"{gif_id}.webp"
                if not thumb_file.exists():
                    continue
                partition = "%03d" % (gif_id // 1000)
                arcname = f"thumbs/{partition}/{gif_id}.webp"
                zf.write(thumb_file, arcname)
                thumb_count += 1

            # Full animated WebPs (optional)
            full_count = 0
            if include_full:
                for entry in entries:
                    gif_id = entry["gif_id"]
                    full_file = full_dir / f"{gif_id}.webp"
                    if not full_file.exists():
                        continue
                    partition = "%03d" % (gif_id // 1000)
                    arcname = f"full/{partition}/{gif_id}.webp"
                    zf.write(full_file, arcname)
                    full_count += 1

            print(f"  Packed {thumb_count} thumbs, {full_count} full GIFs")

    finally:
        if tmp_db.exists():
            tmp_db.unlink()

    return zip_path


# ─── Main pipeline ────────────────────────────────────────────────────────────


def main():
    parser = argparse.ArgumentParser(description="Build CleverKeys packs from Discord GIFs")
    parser.add_argument("--input", type=str,
                        default=os.path.expanduser("~/git/stoatcord-bot/output"),
                        help="stoatcord-bot output directory")
    parser.add_argument("--output", type=str, default="./discord_processed",
                        help="Output directory for processed files")
    parser.add_argument("--packs-dir", type=str, default="./packs",
                        help="Output directory for pack ZIPs")
    parser.add_argument("--profile", type=str, default="balanced",
                        choices=list(PROFILES.keys()),
                        help="Compression profile (default: balanced)")
    parser.add_argument("--workers", type=int, default=4,
                        help="Parallel worker count for conversion")
    parser.add_argument("--skip-convert", action="store_true",
                        help="Skip GIF→WebP conversion (use existing processed/)")
    parser.add_argument("--include-full", action="store_true", default=True,
                        help="Include full animated WebPs in packs (default: True)")
    parser.add_argument("--thumbs-only", action="store_true",
                        help="Only include thumbnails (no full animated WebPs)")
    parser.add_argument("--dry-run", action="store_true",
                        help="Analyze and plan without processing/building")
    parser.add_argument("--limit", type=int, default=None,
                        help="Limit number of GIFs to process (for testing)")

    args = parser.parse_args()

    input_dir = Path(args.input)
    output_dir = Path(args.output)
    packs_dir = Path(args.packs_dir)
    profile = PROFILES[args.profile]
    include_full = not args.thumbs_only

    # Validate input
    index_path = input_dir / "dl-index.json"
    gifs_dir = input_dir / "gifs"
    ndjson_path = input_dir / "gifs.ndjson"

    if not index_path.exists():
        print(f"ERROR: dl-index.json not found at {index_path}")
        sys.exit(1)
    if not gifs_dir.exists():
        print(f"ERROR: gifs/ directory not found at {gifs_dir}")
        sys.exit(1)

    # Create output directories
    full_out = output_dir / "full"
    thumb_out = output_dir / "thumbs"
    meta_out = output_dir / "metadata"
    full_out.mkdir(parents=True, exist_ok=True)
    thumb_out.mkdir(parents=True, exist_ok=True)
    meta_out.mkdir(parents=True, exist_ok=True)
    packs_dir.mkdir(parents=True, exist_ok=True)

    print(f"Profile: {args.profile}")
    print(f"  Full: {profile['full']['max_width']}px, {profile['full']['fps']}fps, Q{profile['full']['quality']}")
    print(f"  Thumb: {profile['thumb']['max_width']}px, Q{profile['thumb']['quality']}")

    # ── Step 1: Load index and extract metadata ──────────────────────────────

    print(f"\n{'='*70}")
    print("Step 1: Loading dl-index.json and extracting metadata...")
    print(f"{'='*70}")

    with open(index_path) as f:
        dl_index = json.load(f)

    # Load usage frequency
    usage_freq: Dict[str, int] = {}
    if ndjson_path.exists():
        print("  Loading Discord usage frequency...")
        usage_freq = load_usage_frequency(ndjson_path, dl_index)
        print(f"  {len(usage_freq)} files with usage data")

    # Build metadata for each indexed file
    entries: List[Dict] = []
    skipped_static = 0
    skipped_missing = 0
    skipped_tiny = 0

    for url, info in dl_index.items():
        filename = info.get("filename", "")
        if not filename:
            continue

        filepath = gifs_dir / filename
        if not filepath.exists():
            skipped_missing += 1
            continue

        ext = filepath.suffix.lower()
        if ext in STATIC_EXTS:
            skipped_static += 1
            continue
        if ext == ".mp4":
            skipped_static += 1
            continue

        # Check file size — skip corrupt/tiny files
        fsize = filepath.stat().st_size
        if fsize < 500:
            skipped_tiny += 1
            continue

        meta = extract_metadata(url, info)
        meta["filepath"] = str(filepath)
        meta["ext"] = ext
        meta["disk_size"] = fsize
        meta["usage_count"] = usage_freq.get(filename, 1)

        entries.append(meta)

    print(f"  Total indexed: {len(dl_index)}")
    print(f"  Processable animated files: {len(entries)}")
    print(f"  Skipped static (JPG/PNG/MP4): {skipped_static}")
    print(f"  Skipped missing on disk: {skipped_missing}")
    print(f"  Skipped tiny (<500 bytes): {skipped_tiny}")

    # Apply limit if set
    if args.limit:
        # Sort by usage count descending (most popular first) before limiting
        entries.sort(key=lambda e: e["usage_count"], reverse=True)
        entries = entries[:args.limit]
        print(f"  Limited to {len(entries)} entries")

    # Source breakdown
    src_counts = Counter(e["source"] for e in entries)
    print(f"\n  Source breakdown:")
    for src, cnt in src_counts.most_common():
        kw_pct = sum(1 for e in entries if e["source"] == src and e["keywords"]) / max(cnt, 1) * 100
        print(f"    {src:10s}: {cnt:5d}  ({kw_pct:.0f}% have keywords)")

    # ── Step 2: Assign sequential IDs and compute scores ─────────────────────

    print(f"\n{'='*70}")
    print("Step 2: Assigning IDs and scoring...")
    print(f"{'='*70}")

    # Sort by usage count descending (most popular GIFs first)
    entries.sort(key=lambda e: (-e["usage_count"], -e["disk_size"]))

    for i, entry in enumerate(entries):
        gif_id = DISCORD_ID_OFFSET + i
        entry["gif_id"] = gif_id

        # Extract keywords from all available text
        text_parts = []
        if entry["keywords"]:
            text_parts.append(" ".join(entry["keywords"]))
        if entry["description"]:
            text_parts.append(entry["description"])
        combined_text = " ".join(text_parts)

        search_keywords = extract_keywords(combined_text)
        entry["search_keywords"] = search_keywords

        categories = classify_discord_gif(search_keywords, combined_text)
        entry["categories"] = categories

        # Score: usage dominates, keywords add bonus
        entry["score"] = entry["usage_count"] * 1000 + len(search_keywords) * 10

    print(f"  Assigned IDs {DISCORD_ID_OFFSET} - {DISCORD_ID_OFFSET + len(entries) - 1}")
    print(f"  GIFs with keywords: {sum(1 for e in entries if e['search_keywords'])}")
    print(f"  GIFs without keywords: {sum(1 for e in entries if not e['search_keywords'])}")

    # Category distribution
    cat_counts: Counter = Counter()
    for e in entries:
        for c in e["categories"]:
            cat_counts[c] += 1
    print(f"\n  Category distribution:")
    for cat_id in sorted(cat_counts.keys()):
        name = CATEGORIES.get(cat_id, {}).get("name", "?")
        print(f"    {cat_id:2d}. {name:12s}: {cat_counts[cat_id]:5d}")

    # ── Size analysis ────────────────────────────────────────────────────────

    print(f"\n{'='*70}")
    print("Step 3: Size analysis...")
    print(f"{'='*70}")

    all_sizes = sorted(e["disk_size"] for e in entries)
    total_raw = sum(all_sizes)
    p90 = all_sizes[int(len(all_sizes) * 0.9)]
    outliers = [e for e in entries if e["disk_size"] > p90]
    normal = [e for e in entries if e["disk_size"] <= p90]

    print(f"  Raw total: {total_raw/1024/1024/1024:.2f} GB")
    print(f"  P90 threshold: {p90/1024:.0f} KB")
    print(f"  Normal (<=P90): {len(normal)} files, {sum(e['disk_size'] for e in normal)/1024/1024:.0f} MB")
    print(f"  Outliers (>P90): {len(outliers)} files, {sum(e['disk_size'] for e in outliers)/1024/1024:.0f} MB")

    # Estimate compressed sizes based on balanced profile ratio (~30-50% of original for animated WebP)
    est_ratio = 0.35
    est_compressed = total_raw * est_ratio
    est_thumb_total = len(entries) * 2 * 1024  # ~2KB avg per thumb
    print(f"  Estimated compressed: ~{est_compressed/1024/1024:.0f} MB")
    print(f"  Estimated thumbs: ~{est_thumb_total/1024/1024:.0f} MB")

    # Pack plan (estimated — real split happens after conversion in Step 6)
    est_per_gif_kb = est_compressed / len(entries) / 1024 if entries else 100
    est_gifs_per_pack = int(TARGET_PACK_MB * 1024 / est_per_gif_kb) if est_per_gif_kb > 0 else TARGET_PACK_GIFS
    n_packs = max(1, (len(entries) + est_gifs_per_pack - 1) // est_gifs_per_pack)
    print(f"\n  Pack plan: ~{n_packs} pack(s) targeting ~{TARGET_PACK_MB} MB each")

    if args.dry_run:
        print(f"\n{'='*70}")
        print("DRY RUN — stopping here. Use without --dry-run to process.")
        print(f"{'='*70}")

        # Show top 20 most used GIFs
        print(f"\n  Top 20 most used GIFs:")
        for e in entries[:20]:
            kw = " ".join(e["keywords"][:5]) if e["keywords"] else "(no keywords)"
            print(f"    {e['usage_count']:3d}x  {e['source']:7s}  {e['disk_size']/1024:6.0f}KB  {kw}")
        return

    # ── Step 4: Convert GIFs to animated WebP + thumbnails ───────────────────

    if not args.skip_convert:
        print(f"\n{'='*70}")
        print(f"Step 4: Converting {len(entries)} GIFs to animated WebP + thumbnails...")
        print(f"{'='*70}")

        # Prepare conversion args, skipping already-processed
        process_args = []
        already_done = 0
        for entry in entries:
            gif_id = entry["gif_id"]
            input_path = Path(entry["filepath"])
            full_output = full_out / f"{gif_id}.webp"
            thumb_output = thumb_out / f"{gif_id}.webp"

            if (full_output.exists() and full_output.stat().st_size > 100 and
                    thumb_output.exists() and thumb_output.stat().st_size > 50):
                already_done += 1
                # Update sizes from existing files
                entry["opt_size"] = full_output.stat().st_size
                entry["thumb_size"] = thumb_output.stat().st_size
                continue

            process_args.append((
                input_path, full_output, thumb_output,
                profile["full"], profile["thumb"],
            ))

        if already_done > 0:
            print(f"  Skipping {already_done} already-processed GIFs")
        print(f"  Converting {len(process_args)} remaining GIFs with {args.workers} workers...")

        if process_args:
            t0 = time.time()
            converted = 0
            failed = 0

            with ProcessPoolExecutor(max_workers=args.workers) as executor:
                futures = {executor.submit(process_single, a): a[0] for a in process_args}
                pbar = tqdm(as_completed(futures), total=len(futures), desc="Converting")

                for future in pbar:
                    result = future.result()
                    fn = result["filename"]

                    # Find matching entry and update sizes
                    for entry in entries:
                        if Path(entry["filepath"]).name == fn:
                            if result["success"]:
                                entry["opt_size"] = result["full_size"]
                                entry["thumb_size"] = result["thumb_size"]
                                entry["width"] = result["width"]
                                entry["height"] = result["height"]
                                entry["duration_ms"] = result["duration_ms"]
                                converted += 1
                            else:
                                entry["opt_size"] = 0
                                entry["thumb_size"] = 0
                                failed += 1
                            break

                    pbar.set_postfix(ok=converted, fail=failed)

            elapsed = time.time() - t0
            print(f"  Converted: {converted}, Failed: {failed}, Time: {elapsed:.0f}s")
    else:
        print("\n  Skipping conversion (--skip-convert)")
        # Load sizes from existing files
        for entry in entries:
            gif_id = entry["gif_id"]
            full_file = full_out / f"{gif_id}.webp"
            thumb_file = thumb_out / f"{gif_id}.webp"
            entry["opt_size"] = full_file.stat().st_size if full_file.exists() else 0
            entry["thumb_size"] = thumb_file.stat().st_size if thumb_file.exists() else 0

    # Filter out entries that failed conversion
    before = len(entries)
    entries = [e for e in entries if e.get("opt_size", 0) > 0 and e.get("thumb_size", 0) > 0]
    dropped = before - len(entries)
    if dropped:
        print(f"  Dropped {dropped} entries that failed conversion")
    print(f"  {len(entries)} GIFs ready for packing")

    # ── Step 5: Write per-GIF metadata JSON ──────────────────────────────────

    print(f"\n{'='*70}")
    print("Step 5: Writing metadata JSON files...")
    print(f"{'='*70}")

    for entry in entries:
        gif_id = entry["gif_id"]
        meta = {
            "id": str(gif_id),
            "source": entry["source"],
            "source_id": entry.get("source_id", ""),
            "description": entry.get("description", ""),
            "slug": entry.get("slug", ""),
            "category": CATEGORIES.get(entry["categories"][0], {}).get("name", "funny").lower()
                        if entry.get("categories") else "funny",
            "url": entry.get("url", ""),
            "media_url": entry.get("media_url", ""),
            "usage_count": entry.get("usage_count", 1),
            "rating": "g",
        }
        meta_path = meta_out / f"{gif_id}.json"
        with open(meta_path, "w") as f:
            json.dump(meta, f, separators=(",", ":"))

    print(f"  Wrote {len(entries)} metadata files")

    # ── Step 6: Build packs ──────────────────────────────────────────────────

    print(f"\n{'='*70}")
    print("Step 6: Building pack ZIPs...")
    print(f"{'='*70}")

    # Sort by score descending for packing (most popular first in pack-01)
    entries.sort(key=lambda e: e.get("score", 0), reverse=True)

    # Split into packs by cumulative byte size (target ~100 MB per ZIP)
    target_bytes = TARGET_PACK_MB * 1024 * 1024
    packs: List[Tuple[str, List[Dict]]] = []
    current_chunk: List[Dict] = []
    current_bytes = 0

    for entry in entries:
        # Per-entry size: full WebP + thumbnail + ~200 bytes DB overhead
        entry_bytes = entry.get("opt_size", 0) + entry.get("thumb_size", 0) + 200
        if include_full:
            pass  # already counted opt_size
        else:
            entry_bytes = entry.get("thumb_size", 0) + 200

        # Start new pack if adding this entry would exceed target
        if current_chunk and current_bytes + entry_bytes > target_bytes:
            pack_num = len(packs) + 1
            pack_name = f"discord-community-{pack_num:02d}"
            packs.append((pack_name, current_chunk))
            current_chunk = []
            current_bytes = 0

        current_chunk.append(entry)
        current_bytes += entry_bytes

    # Final chunk
    if current_chunk:
        pack_num = len(packs) + 1
        if not packs:
            pack_name = "discord-community"
        else:
            pack_name = f"discord-community-{pack_num:02d}"
        packs.append((pack_name, current_chunk))

    # Print pack plan
    total_opt = 0
    total_thumb = 0
    for name, chunk in packs:
        opt_mb = sum(e.get("opt_size", 0) for e in chunk) / 1024 / 1024
        thm_mb = sum(e.get("thumb_size", 0) for e in chunk) / 1024 / 1024
        total_opt += opt_mb
        total_thumb += thm_mb
        label = "FULL+THUMBS" if include_full else "THUMBS"
        est = (opt_mb + thm_mb + 1) if include_full else (thm_mb + 1)
        print(f"  {name:35s}  {len(chunk):5d} GIFs  ~{est:6.0f} MB  [{label}]")

    print(f"  Total: ~{total_opt:.0f} MB full + ~{total_thumb:.0f} MB thumbs")

    # Build each pack
    for name, chunk in packs:
        t0 = time.time()
        zip_path = write_discord_pack_zip(
            pack_name=name,
            entries=chunk,
            full_dir=full_out,
            thumb_dir=thumb_out,
            output_dir=packs_dir,
            include_full=include_full,
        )
        elapsed = time.time() - t0
        zip_size = zip_path.stat().st_size
        print(f"  -> {zip_path.name}: {zip_size/1024/1024:.1f} MB ({elapsed:.0f}s)")

    # ── Summary ──────────────────────────────────────────────────────────────

    print(f"\n{'='*70}")
    print("DONE!")
    print(f"{'='*70}")
    print(f"  Source: {input_dir}")
    print(f"  Processed: {output_dir}")
    print(f"  Packs: {packs_dir}")
    print(f"  Total GIFs packed: {len(entries)}")
    print(f"  Packs created: {len(packs)}")

    total_zip = sum((packs_dir / f"{n}.zip").stat().st_size for n, _ in packs)
    print(f"  Total ZIP size: {total_zip/1024/1024:.0f} MB")


if __name__ == "__main__":
    main()
