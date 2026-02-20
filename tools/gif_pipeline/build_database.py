#!/usr/bin/env python3
"""
Build SQLite database with FTS5 full-text search for offline GIF panel.

This script:
1. Reads processed GIFs and metadata
2. Extracts keywords from descriptions
3. Classifies GIFs into emotion categories
4. Builds SQLite database with FTS5 index
5. Outputs ready-to-bundle database file

Usage:
    python build_database.py \
        --processed ./processed \
        --metadata ./raw_data/metadata \
        --output ./assets/gifs.db

The output database is designed to be bundled in the Android app's assets.
"""

import argparse
import json
import re
import sqlite3
import sys
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

from tqdm import tqdm


# Emotion categories with associated keywords
# Based on GIFGIF+ research (17 emotion categories)
CATEGORIES = {
    1: {
        "name": "Funny",
        "icon": "\U0001F602",  # Face with Tears of Joy
        "keywords": ["laugh", "funny", "lol", "haha", "hilarious", "comedy", "joke",
                    "giggle", "chuckle", "rofl", "lmao", "humor", "amusing"]
    },
    2: {
        "name": "Angry",
        "icon": "\U0001F620",  # Angry Face
        "keywords": ["angry", "mad", "rage", "furious", "upset", "frustrated",
                    "annoyed", "irritated", "pissed", "hate", "anger"]
    },
    3: {
        "name": "Smug",
        "icon": "\U0001F60F",  # Smirking Face
        "keywords": ["smug", "smirk", "eye roll", "whatever", "unimpressed",
                    "sarcastic", "condescending", "superior", "dismissive"]
    },
    4: {
        "name": "Relaxed",
        "icon": "\U0001F60C",  # Relieved Face
        "keywords": ["relaxed", "calm", "peaceful", "chill", "zen", "serene",
                    "content", "satisfied", "comfortable", "cozy"]
    },
    5: {
        "name": "Disgusted",
        "icon": "\U0001F922",  # Nauseated Face
        "keywords": ["disgusted", "gross", "ew", "yuck", "nasty", "eww",
                    "revolting", "vomit", "nausea", "sick"]
    },
    6: {
        "name": "Awkward",
        "icon": "\U0001F633",  # Flushed Face
        "keywords": ["awkward", "embarrassed", "cringe", "facepalm", "oops",
                    "uncomfortable", "nervous", "shy", "blush"]
    },
    7: {
        "name": "Excited",
        "icon": "\U0001F929",  # Star-Struck
        "keywords": ["excited", "yay", "celebration", "woohoo", "pumped",
                    "thrilled", "ecstatic", "hyped", "stoked", "celebrate"]
    },
    8: {
        "name": "Scared",
        "icon": "\U0001F628",  # Fearful Face
        "keywords": ["scared", "afraid", "fear", "terrified", "horror", "panic",
                    "frightened", "spooked", "creepy", "nightmare"]
    },
    9: {
        "name": "Sorry",
        "icon": "\U0001F614",  # Pensive Face
        "keywords": ["sorry", "apologize", "regret", "guilt", "my bad",
                    "apology", "forgive", "remorse", "ashamed"]
    },
    10: {
        "name": "Happy",
        "icon": "\U0001F60A",  # Smiling Face with Smiling Eyes
        "keywords": ["happy", "smile", "joy", "pleased", "cheerful", "glad",
                    "delighted", "joyful", "grin", "beam"]
    },
    11: {
        "name": "Love",
        "icon": "\U0001F60D",  # Smiling Face with Heart-Eyes
        "keywords": ["love", "heart", "romantic", "adore", "crush", "kiss",
                    "affection", "loving", "sweet", "cute", "aww"]
    },
    12: {
        "name": "Proud",
        "icon": "\U0001F924",  # Face with Steam from Nose (close to proud)
        "keywords": ["proud", "victory", "winning", "flex", "boss", "champion",
                    "triumph", "succeed", "accomplished", "nailed"]
    },
    13: {
        "name": "Relieved",
        "icon": "\U0001F605",  # Grinning Face with Sweat
        "keywords": ["relieved", "phew", "whew", "close call", "safe", "exhale",
                    "thank god", "dodged", "narrow escape"]
    },
    14: {
        "name": "Sad",
        "icon": "\U0001F622",  # Crying Face
        "keywords": ["sad", "cry", "crying", "tears", "depressed", "upset",
                    "unhappy", "heartbroken", "sobbing", "devastated"]
    },
    15: {
        "name": "Ashamed",
        "icon": "\U0001F61E",  # Disappointed Face
        "keywords": ["ashamed", "shame", "disappointed", "fail", "failure",
                    "embarrassment", "humiliated", "mortified"]
    },
    16: {
        "name": "Surprised",
        "icon": "\U0001F632",  # Astonished Face
        "keywords": ["surprised", "shocked", "omg", "wow", "what", "whoa",
                    "astonished", "amazed", "stunned", "gasp"]
    },
    17: {
        "name": "Approve",
        "icon": "\U0001F44D",  # Thumbs Up
        "keywords": ["yes", "agree", "approve", "ok", "good", "nice", "great",
                    "thumbs up", "correct", "right", "exactly", "perfect"]
    }
}


# Module-level constant — avoids rebuilding this set 130K+ times in extract_keywords()
STOP_WORDS: frozenset = frozenset({
    "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
    "have", "has", "had", "do", "does", "did", "will", "would", "could",
    "should", "may", "might", "must", "shall", "can", "need", "dare",
    "to", "of", "in", "for", "on", "with", "at", "by", "from", "as",
    "into", "through", "during", "before", "after", "above", "below",
    "between", "under", "again", "further", "then", "once", "here",
    "there", "when", "where", "why", "how", "all", "each", "few", "more",
    "most", "other", "some", "such", "no", "nor", "not", "only", "own",
    "same", "so", "than", "too", "very", "just", "and", "but", "if",
    "or", "because", "until", "while", "this", "that", "these", "those",
    "it", "its", "he", "she", "they", "them", "his", "her", "their",
    "i", "me", "my", "we", "us", "our", "you", "your", "gif", "image"
})


def extract_keywords(description: str) -> List[str]:
    """Extract meaningful keywords from description text."""
    if not description:
        return []

    # Convert to lowercase and remove special characters
    text = description.lower()
    text = re.sub(r"[^a-z0-9\s]", " ", text)

    # Split into words
    words = text.split()

    keywords = [w for w in words if w not in STOP_WORDS and len(w) > 2]

    # Remove duplicates while preserving order
    seen = set()
    unique_keywords = []
    for kw in keywords:
        if kw not in seen:
            seen.add(kw)
            unique_keywords.append(kw)

    return unique_keywords[:20]  # Limit to 20 keywords


def classify_gif(keywords: List[str], description: str) -> List[int]:
    """Classify a GIF into emotion categories based on keywords."""
    text = " ".join(keywords) + " " + (description or "").lower()
    matched_categories = []

    for cat_id, cat_data in CATEGORIES.items():
        for keyword in cat_data["keywords"]:
            if keyword in text:
                matched_categories.append(cat_id)
                break

    # If no match, try to assign to a general category
    if not matched_categories:
        # Default to "Funny" (1) or "Happy" (10) for unclassified
        matched_categories = [1]

    return matched_categories[:3]  # Max 3 categories per GIF


def build_pack_database(
    processed_dir: str,
    metadata_dir: str,
    output_path: str,
    gif_ids: List[str],
    use_optimized: bool = True,
) -> Dict:
    """
    Build a per-pack mini SQLite database (V3 schema) containing only the specified GIF entries.

    V3 schema matches GifDatabase.kt:
    - gifs table with search_text column (no is_available, no pack_id)
    - categories table
    - gif_category_map (WITHOUT ROWID)
    - content-synced FTS5: content='gifs', content_rowid='gif_id'
    - gif_usage table (empty)

    Used by pack_builder.py to create self-contained pack.db files.
    """
    processed_path = Path(processed_dir)
    metadata_path = Path(metadata_dir)
    output_file = Path(output_path)

    # Ensure output directory exists
    output_file.parent.mkdir(parents=True, exist_ok=True)

    # Remove existing database
    if output_file.exists():
        output_file.unlink()

    # Resolve GIF source directory — prefer optimized/ over full/
    if use_optimized:
        gif_dir = processed_path / "optimized"
        if not gif_dir.exists() or not any(gif_dir.glob("*.webp")):
            print("Warning: optimized/ empty, falling back to full/")
            gif_dir = processed_path / "full"
    else:
        gif_dir = processed_path / "full"

    # Load processing results for dimensions/sizes
    results_file = processed_path / "processing_results.json"
    results_data: Dict = {}
    if results_file.exists():
        with open(results_file) as f:
            data = json.load(f)
            for r in data.get("results", []):
                results_data[r["id"]] = r

    # Create database with V3 schema
    conn = sqlite3.connect(output_file)
    cursor = conn.cursor()

    # Optimize for size: read-only DB shipped inside a pack ZIP
    cursor.execute("PRAGMA journal_mode = OFF")
    cursor.execute("PRAGMA synchronous = OFF")
    cursor.execute("PRAGMA page_size = 4096")

    # V3 schema — matches GifDatabase.kt V3
    cursor.executescript("""
        -- Categories table
        CREATE TABLE categories (
            category_id INTEGER PRIMARY KEY,
            name TEXT NOT NULL UNIQUE,
            icon TEXT NOT NULL,
            sort_order INTEGER NOT NULL
        );

        -- Main GIF metadata table (V3: includes search_text, no is_available/pack_id)
        CREATE TABLE gifs (
            gif_id INTEGER PRIMARY KEY,
            width INTEGER NOT NULL,
            height INTEGER NOT NULL,
            duration_ms INTEGER DEFAULT 0,
            file_size INTEGER DEFAULT 0,
            search_text TEXT DEFAULT '',
            created_at INTEGER DEFAULT 0
        );

        -- Many-to-many: GIF to categories
        CREATE TABLE gif_category_map (
            category_id INTEGER NOT NULL,
            gif_id INTEGER NOT NULL,
            PRIMARY KEY (category_id, gif_id),
            FOREIGN KEY (gif_id) REFERENCES gifs(gif_id) ON DELETE CASCADE,
            FOREIGN KEY (category_id) REFERENCES categories(category_id) ON DELETE CASCADE
        ) WITHOUT ROWID;

        -- Content-synced FTS5 (V3: supports per-row delete for pack removal)
        CREATE VIRTUAL TABLE gifs_fts USING fts5(
            search_text,
            content='gifs',
            content_rowid='gif_id'
        );

        -- FTS sync triggers
        CREATE TRIGGER gifs_ai AFTER INSERT ON gifs BEGIN
            INSERT INTO gifs_fts(rowid, search_text) VALUES (new.gif_id, new.search_text);
        END;
        CREATE TRIGGER gifs_ad AFTER DELETE ON gifs BEGIN
            INSERT INTO gifs_fts(gifs_fts, rowid, search_text) VALUES('delete', old.gif_id, old.search_text);
        END;
        CREATE TRIGGER gifs_au AFTER UPDATE ON gifs BEGIN
            INSERT INTO gifs_fts(gifs_fts, rowid, search_text) VALUES('delete', old.gif_id, old.search_text);
            INSERT INTO gifs_fts(rowid, search_text) VALUES (new.gif_id, new.search_text);
        END;

        -- Usage tracking (created on-device, empty in pack DB)
        CREATE TABLE gif_usage (
            gif_id INTEGER PRIMARY KEY,
            use_count INTEGER DEFAULT 0,
            last_used INTEGER DEFAULT 0,
            FOREIGN KEY (gif_id) REFERENCES gifs(gif_id) ON DELETE CASCADE
        );
        CREATE INDEX idx_gif_usage ON gif_usage(last_used DESC);
    """)

    # Insert categories
    for cat_id, cat_data in CATEGORIES.items():
        cursor.execute(
            "INSERT INTO categories (category_id, name, icon, sort_order) VALUES (?, ?, ?, ?)",
            (cat_id, cat_data["name"], cat_data["icon"], cat_id)
        )

    # Process and insert only the specified GIF IDs
    stats: Dict = {
        "inserted": 0,
        "failed": 0,
        "skipped": 0,
        "categories_assigned": 0,
    }

    pbar = tqdm(gif_ids, desc="Building pack database")

    for gif_id_str in pbar:
        # Normalize to zero-padded 6-digit string
        gif_id_str = gif_id_str.strip().lstrip("0") or "0"
        gif_id_padded = gif_id_str.zfill(6)

        gif_file = gif_dir / f"{gif_id_padded}.webp"
        if not gif_file.exists():
            # Try unpadded filename as fallback
            gif_file = gif_dir / f"{gif_id_str}.webp"
            if not gif_file.exists():
                stats["skipped"] += 1
                continue

        # Load metadata for keyword extraction
        meta_file = metadata_path / f"{gif_id_padded}.json"
        description = ""
        slug_words = ""
        if meta_file.exists():
            try:
                with open(meta_file) as f:
                    meta = json.load(f)
                description = meta.get("description", "")
                # Giphy slugs contain hyphenated keyword chains
                slug = meta.get("slug", "")
                if slug:
                    slug_words = slug.replace("-", " ").lower()
            except (json.JSONDecodeError, OSError):
                pass

        # Get dimensions from processing results
        result = results_data.get(gif_id_padded, {})
        width = result.get("width", 200)
        height = result.get("height", 200)
        duration_ms = result.get("duration_ms", 0)
        file_size = gif_file.stat().st_size

        # Extract keywords and classify
        combined_text = f"{description} {slug_words}"
        keywords = extract_keywords(combined_text)
        categories = classify_gif(keywords, combined_text)
        search_text = " ".join(keywords)

        gif_id_int = int(gif_id_str.lstrip("0") or "0")

        try:
            # Insert GIF record with search_text (V3 schema — no pack_id/is_available)
            cursor.execute(
                """INSERT INTO gifs
                   (gif_id, width, height, duration_ms, file_size, search_text, created_at)
                   VALUES (?, ?, ?, ?, ?, ?, 0)""",
                (gif_id_int, width, height, duration_ms, file_size, search_text)
            )

            # Category mappings
            for cat_id in categories:
                cursor.execute(
                    "INSERT OR IGNORE INTO gif_category_map (category_id, gif_id) VALUES (?, ?)",
                    (cat_id, gif_id_int)
                )
                stats["categories_assigned"] += 1

            stats["inserted"] += 1

        except Exception as e:
            print(f"\nError inserting {gif_id_padded}: {e}")
            stats["failed"] += 1

        pbar.set_postfix({"inserted": stats["inserted"], "failed": stats["failed"]})

    # Commit and optimize
    conn.commit()

    # Collect final stats
    cursor.execute("SELECT COUNT(*) FROM gifs")
    total_gifs = cursor.fetchone()[0]

    cursor.execute("SELECT COUNT(*) FROM gif_category_map")
    total_mappings = cursor.fetchone()[0]

    # Vacuum to minimize file size
    cursor.execute("VACUUM")
    conn.close()

    db_size = output_file.stat().st_size

    print(f"\nPack database built successfully!")
    print(f"  Total GIFs: {total_gifs:,}")
    print(f"  Category mappings: {total_mappings:,}")
    print(f"  Skipped (not found): {stats['skipped']}")
    print(f"  Database size: {db_size / 1024:.1f} KB")
    print(f"  Output: {output_file}")

    stats["total_gifs"] = total_gifs
    stats["db_size"] = db_size

    return stats


def build_database(
    processed_dir: str,
    metadata_dir: str,
    output_path: str,
    limit: Optional[int] = None,
    use_optimized: bool = True,
) -> Dict:
    """
    Build SQLite database with FTS5 search index.

    When use_optimized=True, reads from optimized/ directory (squoosh output)
    instead of full/ directory. This is the preferred mode for pack-ready builds.
    """
    processed_path = Path(processed_dir)
    metadata_path = Path(metadata_dir)
    output_file = Path(output_path)

    # Ensure output directory exists
    output_file.parent.mkdir(parents=True, exist_ok=True)

    # Remove existing database
    if output_file.exists():
        output_file.unlink()

    # Find processed files — prefer optimized/ over full/ for final builds
    if use_optimized:
        gif_dir = processed_path / "optimized"
        if not gif_dir.exists() or not any(gif_dir.glob("*.webp")):
            print("Warning: optimized/ empty, falling back to full/")
            gif_dir = processed_path / "full"
    else:
        gif_dir = processed_path / "full"
    thumbs_dir = processed_path / "thumbs"

    gif_files = sorted(gif_dir.glob("*.webp"))
    if limit:
        gif_files = gif_files[:limit]

    print(f"Found {len(gif_files)} processed GIF files (from {gif_dir.name}/)")

    # Load processing results for dimensions/sizes
    results_file = processed_path / "processing_results.json"
    results_data = {}
    if results_file.exists():
        with open(results_file) as f:
            data = json.load(f)
            for r in data.get("results", []):
                results_data[r["id"]] = r

    # Create database
    conn = sqlite3.connect(output_file)
    cursor = conn.cursor()

    # Optimize for size: read-only DB downloaded to mobile
    cursor.execute("PRAGMA journal_mode = OFF")
    cursor.execute("PRAGMA synchronous = OFF")
    cursor.execute("PRAGMA page_size = 4096")

    # Create tables — optimized schema:
    # - No asset_name/thumbnail_name columns (derive from gif_id: "%06d.webp")
    # - pack_id normalized to INTEGER with packs lookup table
    # - is_available flag for tiered download system
    # - WITHOUT ROWID on category map (clustered by category for fast lookups)
    # - Contentless FTS5 (content='', columnsize=0) — smallest index
    cursor.executescript("""
        -- Categories table
        CREATE TABLE categories (
            category_id INTEGER PRIMARY KEY,
            name TEXT NOT NULL UNIQUE,
            icon TEXT NOT NULL,
            sort_order INTEGER NOT NULL
        );

        -- Pack lookup table (normalizes pack_id strings to integers)
        CREATE TABLE packs (
            pack_id INTEGER PRIMARY KEY,
            name TEXT NOT NULL UNIQUE,
            version INTEGER DEFAULT 1,
            gif_count INTEGER DEFAULT 0
        );

        -- Main GIF metadata table
        -- Asset paths derived at runtime: String.format("%06d.webp", gif_id)
        CREATE TABLE gifs (
            gif_id INTEGER PRIMARY KEY,
            width INTEGER NOT NULL,
            height INTEGER NOT NULL,
            duration_ms INTEGER DEFAULT 0,
            file_size INTEGER DEFAULT 0,
            pack_id INTEGER DEFAULT 0 REFERENCES packs(pack_id),
            is_available INTEGER DEFAULT 1,
            created_at INTEGER DEFAULT 0
        );

        -- Many-to-many: GIF to categories
        -- WITHOUT ROWID: clustered by (category_id, gif_id) for fast category lookups
        -- No separate index needed — PK IS the index
        CREATE TABLE gif_category_map (
            category_id INTEGER NOT NULL,
            gif_id INTEGER NOT NULL,
            PRIMARY KEY (category_id, gif_id),
            FOREIGN KEY (gif_id) REFERENCES gifs(gif_id) ON DELETE CASCADE,
            FOREIGN KEY (category_id) REFERENCES categories(category_id) ON DELETE CASCADE
        ) WITHOUT ROWID;

        -- Contentless FTS5 for keyword search
        -- content='': no content duplication (smallest possible index)
        -- columnsize=0: skip per-doc length stats (saves ~1MB)
        CREATE VIRTUAL TABLE gifs_fts USING fts5(
            search_text,
            content='',
            columnsize=0
        );

        -- Usage tracking (recently used) — created on-device, empty in shipped DB
        CREATE TABLE gif_usage (
            gif_id INTEGER PRIMARY KEY,
            use_count INTEGER DEFAULT 0,
            last_used INTEGER DEFAULT 0,
            FOREIGN KEY (gif_id) REFERENCES gifs(gif_id) ON DELETE CASCADE
        );

        -- Index for usage queries (recently used sort)
        CREATE INDEX idx_gif_usage ON gif_usage(last_used DESC);
    """)

    # Insert categories
    for cat_id, cat_data in CATEGORIES.items():
        cursor.execute(
            "INSERT INTO categories (category_id, name, icon, sort_order) VALUES (?, ?, ?, ?)",
            (cat_id, cat_data["name"], cat_data["icon"], cat_id)
        )

    # Insert default pack
    pack_cache = {}
    cursor.execute("INSERT INTO packs (pack_id, name) VALUES (0, 'default')")
    pack_cache["default"] = 0

    # Process and insert GIFs
    stats = {
        "inserted": 0,
        "failed": 0,
        "categories_assigned": 0
    }

    pbar = tqdm(gif_files, desc="Building database")

    for gif_file in pbar:
        gif_id_str = gif_file.stem

        # Guard against non-numeric filenames (.DS_Store, thumbs.db, etc.)
        if not gif_id_str.isdigit():
            stats["failed"] += 1
            continue

        thumb_file = thumbs_dir / f"{gif_id_str}.webp"

        if not thumb_file.exists():
            # Auto-generate thumbnail by extracting frame 1
            thumbs_dir.mkdir(parents=True, exist_ok=True)
            try:
                import subprocess
                r = subprocess.run(
                    ["webpmux", "-get", "frame", "1", str(gif_file), "-o", str(thumb_file)],
                    capture_output=True, timeout=10,
                )
                if r.returncode != 0 or not thumb_file.exists() or thumb_file.stat().st_size == 0:
                    import shutil
                    shutil.copy2(gif_file, thumb_file)
            except Exception:
                try:
                    import shutil
                    shutil.copy2(gif_file, thumb_file)
                except Exception:
                    stats["failed"] += 1
                    continue

        # Load metadata — use Giphy metadata for richer keyword extraction
        meta_file = metadata_path / f"{gif_id_str}.json"
        description = ""
        pack_name = "default"
        slug_words = ""
        if meta_file.exists():
            try:
                with open(meta_file) as f:
                    meta = json.load(f)
                description = meta.get("description", "")
                # Use source_category as pack grouping hint
                src_cat = meta.get("category", "")
                if src_cat:
                    pack_name = src_cat
                # Giphy slugs contain hyphenated keyword chains
                slug = meta.get("slug", "")
                if slug:
                    slug_words = slug.replace("-", " ").lower()
            except (json.JSONDecodeError, OSError):
                pass

        # Normalize pack_id to integer
        if pack_name not in pack_cache:
            cursor.execute("INSERT OR IGNORE INTO packs (name) VALUES (?)", (pack_name,))
            cursor.execute("SELECT pack_id FROM packs WHERE name = ?", (pack_name,))
            pack_cache[pack_name] = cursor.fetchone()[0]
        pack_id_int = pack_cache[pack_name]

        # Get dimensions from processing results
        result = results_data.get(gif_id_str, {})
        width = result.get("width", 200)
        height = result.get("height", 200)
        duration_ms = result.get("duration_ms", 0)
        file_size = gif_file.stat().st_size

        # Extract keywords from description + slug for broader search coverage
        combined_text = f"{description} {slug_words}"
        keywords = extract_keywords(combined_text)
        categories = classify_gif(keywords, combined_text)
        search_text = " ".join(keywords)

        # Use numeric gif_id (strip leading zeros)
        gif_id_int = int(gif_id_str)

        try:
            # Insert GIF record (no asset_name/thumbnail_name — derive from gif_id)
            cursor.execute(
                """INSERT INTO gifs
                   (gif_id, width, height, duration_ms, file_size, pack_id, is_available, created_at)
                   VALUES (?, ?, ?, ?, ?, ?, 1, 0)""",
                (gif_id_int, width, height, duration_ms, file_size, pack_id_int)
            )

            # Insert FTS record (contentless — rowid must match gif_id)
            cursor.execute(
                "INSERT INTO gifs_fts(rowid, search_text) VALUES (?, ?)",
                (gif_id_int, search_text)
            )

            # Insert category mappings (category_id first for WITHOUT ROWID clustering)
            for cat_id in categories:
                cursor.execute(
                    "INSERT OR IGNORE INTO gif_category_map (category_id, gif_id) VALUES (?, ?)",
                    (cat_id, gif_id_int)
                )
                stats["categories_assigned"] += 1

            stats["inserted"] += 1

        except Exception as e:
            print(f"\nError inserting {gif_id_str}: {e}")
            stats["failed"] += 1

        pbar.set_postfix({"inserted": stats["inserted"], "failed": stats["failed"]})

    # Commit and optimize
    conn.commit()

    # Get database stats
    cursor.execute("SELECT COUNT(*) FROM gifs")
    total_gifs = cursor.fetchone()[0]

    cursor.execute("SELECT COUNT(*) FROM gif_category_map")
    total_mappings = cursor.fetchone()[0]

    cursor.execute("SELECT COUNT(*) FROM packs")
    total_packs = cursor.fetchone()[0]

    # Update pack gif counts
    cursor.execute("""
        UPDATE packs SET gif_count = (
            SELECT COUNT(*) FROM gifs WHERE gifs.pack_id = packs.pack_id
        )
    """)
    conn.commit()

    # Vacuum to optimize file size
    cursor.execute("VACUUM")

    conn.close()

    # Get final file size and gzip it
    db_size = output_file.stat().st_size

    import gzip as gz_mod
    import shutil as shutil_mod
    gz_path = Path(str(output_file) + ".gz")
    with open(output_file, "rb") as f_in:
        with gz_mod.open(gz_path, "wb", compresslevel=9) as f_out:
            shutil_mod.copyfileobj(f_in, f_out)  # Chunked copy, avoids loading full DB into RAM
    gz_size = gz_path.stat().st_size

    print(f"\nDatabase built successfully!")
    print(f"  Total GIFs: {total_gifs:,}")
    print(f"  Category mappings: {total_mappings:,}")
    print(f"  Packs: {total_packs}")
    print(f"  Database size: {db_size / 1024 / 1024:.1f} MB")
    print(f"  Gzipped size:  {gz_size / 1024 / 1024:.1f} MB")
    print(f"  Output: {output_file}")
    print(f"  Gzipped: {gz_path}")

    stats["total_gifs"] = total_gifs
    stats["db_size"] = db_size
    stats["gz_size"] = gz_size

    return stats


def main():
    parser = argparse.ArgumentParser(
        description="Build SQLite database for offline GIF panel"
    )
    parser.add_argument(
        "--processed", "-p",
        type=str,
        required=True,
        help="Directory with processed WebP files"
    )
    parser.add_argument(
        "--metadata", "-m",
        type=str,
        required=True,
        help="Directory with metadata JSON files"
    )
    parser.add_argument(
        "--output", "-o",
        type=str,
        default="./gifs.db",
        help="Output database file path"
    )
    parser.add_argument(
        "--limit", "-l",
        type=int,
        default=None,
        help="Maximum number of GIFs to include"
    )
    parser.add_argument(
        "--use-optimized", action="store_true", default=True,
        help="Use optimized/ directory instead of full/ (default: True)"
    )
    parser.add_argument(
        "--use-full", action="store_true", default=False,
        help="Use full/ directory instead of optimized/"
    )
    parser.add_argument(
        "--pack-mode", action="store_true", default=False,
        help="Build per-pack mini DB (V3 schema) instead of monolithic DB"
    )
    parser.add_argument(
        "--gif-ids", type=str, default=None,
        help="Comma-separated list of GIF IDs to include (for --pack-mode)"
    )
    parser.add_argument(
        "--gif-ids-file", type=str, default=None,
        help="File containing GIF IDs, one per line (for --pack-mode)"
    )

    args = parser.parse_args()

    if args.pack_mode:
        # Load GIF IDs from args
        gif_ids: List[str] = []
        if args.gif_ids:
            gif_ids = [id.strip() for id in args.gif_ids.split(",")]
        elif args.gif_ids_file:
            with open(args.gif_ids_file) as f:
                gif_ids = [line.strip() for line in f if line.strip()]
        else:
            print("ERROR: --pack-mode requires --gif-ids or --gif-ids-file")
            sys.exit(1)

        stats = build_pack_database(
            processed_dir=args.processed,
            metadata_dir=args.metadata,
            output_path=args.output,
            gif_ids=gif_ids,
            use_optimized=not args.use_full,
        )
    else:
        stats = build_database(
            processed_dir=args.processed,
            metadata_dir=args.metadata,
            output_path=args.output,
            limit=args.limit,
            use_optimized=not args.use_full,
        )

    if stats.get("inserted", 0) == 0:
        print("Warning: No GIFs were inserted!")
        sys.exit(1)


if __name__ == "__main__":
    main()
