#!/usr/bin/env python3
"""
download_giphy.py — Download GIFs from Giphy API by emotion category.

Fetches GIFs using search endpoint across 17 emotion categories,
deduplicates by ID, saves metadata, and supports resumption.

Usage:
    python download_giphy.py [--target 1000] [--per-category 60] [--output ./raw_data]

Rate limit: 100 API calls/hour (beta key). Each call fetches up to 50 GIFs.
"""

import argparse
import json
import os
import sys
import time
from pathlib import Path
from typing import Dict, List, Set

import requests
from dotenv import load_dotenv

# Emotion categories matching CleverKeys keyboard categories
CATEGORIES: List[Dict] = [
    {"name": "happy", "queries": ["happy", "joy", "smile", "cheerful", "grinning", "yay", "celebrate", "dancing happy", "good vibes", "feeling good", "woohoo", "beaming"]},
    {"name": "funny", "queries": ["funny", "lol", "hilarious", "comedy", "joke", "humor", "silly", "goofy", "ridiculous", "meme", "wtf funny", "dying laughing"]},
    {"name": "love", "queries": ["love", "heart", "kiss", "romance", "crush", "i love you", "hug", "cuddle", "adore", "blowing kiss", "heart eyes", "smitten"]},
    {"name": "sad", "queries": ["sad", "crying", "tears", "depressed", "heartbroken", "sobbing", "miserable", "upset", "bawling", "disappointed", "feeling down", "lonely"]},
    {"name": "angry", "queries": ["angry", "rage", "furious", "mad", "pissed off", "frustrated", "livid", "tantrum", "fuming", "irritated", "outraged", "seething"]},
    {"name": "surprised", "queries": ["surprised", "shocked", "omg", "what", "jaw drop", "mind blown", "plot twist", "no way", "gasping", "stunned", "unexpected", "shook"]},
    {"name": "excited", "queries": ["excited", "hyped", "pumped", "stoked", "thrilled", "ecstatic", "jumping", "lets go", "cant wait", "fired up", "amped", "woo"]},
    {"name": "scared", "queries": ["scared", "terrified", "horror", "fear", "nightmare", "spooky", "creepy", "haunted", "frightened", "panic", "screaming", "run away"]},
    {"name": "disgusted", "queries": ["disgusted", "gross", "ew", "nope", "nasty", "yuck", "revolting", "cringing", "vomit", "eww", "that's gross", "no thanks"]},
    {"name": "awkward", "queries": ["awkward", "cringe", "embarrassed", "uncomfortable", "yikes", "ooh awkward", "nervous", "side eye", "sweating", "crickets", "tumbleweed", "dead inside"]},
    {"name": "proud", "queries": ["proud", "achievement", "victory", "winning", "champion", "nailed it", "flex", "boss", "accomplished", "crushed it", "legend", "like a boss"]},
    {"name": "relieved", "queries": ["relieved", "phew", "calm", "finally", "thank god", "relaxed", "exhale", "weight off", "close call", "made it", "deep breath", "safe"]},
    {"name": "smug", "queries": ["smug", "sassy", "confident", "smirk", "told you so", "deal with it", "sunglasses", "mic drop", "unbothered", "flipping hair", "whatever", "too cool"]},
    {"name": "sorry", "queries": ["sorry", "apologize", "oops", "my bad", "forgive me", "regret", "apology", "whoops", "mistake", "i messed up", "please forgive", "guilt"]},
    {"name": "ashamed", "queries": ["ashamed", "shame", "facepalm", "hiding", "mortified", "walk of shame", "fail", "epic fail", "embarrassing", "cringe fail", "face palm", "bury head"]},
    {"name": "approve", "queries": ["thumbs up", "approve", "yes", "agree", "nodding", "clapping", "well done", "bravo", "nice", "good job", "you got this", "correct"]},
    {"name": "laugh", "queries": ["laugh", "rofl", "haha", "lmao", "dying", "cackling", "snort laugh", "belly laugh", "can't stop laughing", "wheeze", "giggling", "hysterical"]},
]

GIPHY_SEARCH_URL = "https://api.giphy.com/v1/gifs/search"
GIPHY_TRENDING_URL = "https://api.giphy.com/v1/gifs/trending"
BATCH_SIZE = 50  # max per API call
RATE_LIMIT_DELAY = 37  # seconds between calls (100/hr = 1 per 36s, +1s buffer)


def load_history(history_path: Path) -> Dict:
    """Load download history for resumption."""
    if history_path.exists():
        with open(history_path) as f:
            return json.load(f)
    return {"downloaded_ids": [], "categories": {}, "api_calls": 0, "total_downloaded": 0}


def save_history(history: Dict, history_path: Path) -> None:
    """Persist download history."""
    with open(history_path, "w") as f:
        json.dump(history, f, indent=2)


def search_giphy(api_key: str, query: str, offset: int = 0, limit: int = BATCH_SIZE) -> Dict:
    """Call Giphy search endpoint."""
    resp = requests.get(GIPHY_SEARCH_URL, params={
        "api_key": api_key,
        "q": query,
        "limit": limit,
        "offset": offset,
        "rating": "pg-13",
        "lang": "en",
    }, timeout=30)
    resp.raise_for_status()
    return resp.json()


def fetch_trending(api_key: str, offset: int = 0, limit: int = BATCH_SIZE) -> Dict:
    """Call Giphy trending endpoint."""
    resp = requests.get(GIPHY_TRENDING_URL, params={
        "api_key": api_key,
        "limit": limit,
        "offset": offset,
        "rating": "pg-13",
    }, timeout=30)
    resp.raise_for_status()
    return resp.json()


def download_gif(url: str, output_path: Path) -> bool:
    """Download a single GIF file."""
    try:
        resp = requests.get(url, timeout=60, stream=True)
        resp.raise_for_status()
        with open(output_path, "wb") as f:
            for chunk in resp.iter_content(chunk_size=8192):
                f.write(chunk)
        return output_path.stat().st_size > 100
    except Exception as e:
        print(f"  Download failed: {e}")
        if output_path.exists():
            output_path.unlink()
        return False


def save_metadata(gif_data: Dict, category: str, output_dir: Path, gif_id: str) -> None:
    """Save Giphy metadata JSON matching existing pipeline format."""
    meta = {
        "id": gif_id,
        "giphy_id": gif_data["id"],
        "description": gif_data.get("title", ""),
        "source": "giphy",
        "category": category,
        "original_url": gif_data.get("url", ""),
        "source_url": gif_data.get("source", ""),
        "rating": gif_data.get("rating", ""),
        "import_datetime": gif_data.get("import_datetime", ""),
        "trending_datetime": gif_data.get("trending_datetime", ""),
        "username": gif_data.get("username", ""),
        "slug": gif_data.get("slug", ""),
        "images": {
            "original": gif_data.get("images", {}).get("original", {}),
            "fixed_width": gif_data.get("images", {}).get("fixed_width", {}),
        },
        "file_size": int(gif_data.get("images", {}).get("original", {}).get("size", 0)),
    }
    meta_path = output_dir / "metadata" / f"{gif_id}.json"
    with open(meta_path, "w") as f:
        json.dump(meta, f, indent=2)


def main():
    parser = argparse.ArgumentParser(description="Download GIFs from Giphy API")
    parser.add_argument("--target", type=int, default=1000, help="Total GIFs to download")
    parser.add_argument("--per-category", type=int, default=60, help="Target GIFs per category")
    parser.add_argument("--output", type=str, default="./raw_data", help="Output directory")
    parser.add_argument("--no-rate-limit", action="store_true", help="Disable rate limit delay (for testing)")
    args = parser.parse_args()

    # Load API key
    load_dotenv()
    api_key = os.getenv("GIPHY_API_KEY")
    if not api_key:
        print("ERROR: GIPHY_API_KEY not found in .env")
        sys.exit(1)

    output_dir = Path(args.output)
    gifs_dir = output_dir / "gifs"
    meta_dir = output_dir / "metadata"
    gifs_dir.mkdir(parents=True, exist_ok=True)
    meta_dir.mkdir(parents=True, exist_ok=True)

    history_path = Path("download_history.json")
    history = load_history(history_path)
    seen_ids: Set[str] = set(history["downloaded_ids"])
    delay = 0 if args.no_rate_limit else RATE_LIMIT_DELAY

    # Find next numeric ID (continue from existing files)
    existing_ids = [int(p.stem) for p in gifs_dir.glob("*.gif") if p.stem.isdigit()]
    next_id = max(existing_ids, default=-1) + 1
    # Also check existing webp IDs to avoid collisions with TGIF data
    existing_webp = [int(p.stem) for p in gifs_dir.glob("*.webp") if p.stem.isdigit()]
    if existing_webp:
        next_id = max(next_id, max(existing_webp) + 1)

    total_new = 0
    already_downloaded = history["total_downloaded"]
    api_calls = history["api_calls"]

    print(f"Giphy Downloader — Target: {args.target} GIFs")
    print(f"Output: {output_dir}")
    print(f"Already downloaded: {already_downloaded} (resuming)")
    print(f"API calls used this session: {api_calls}")
    print(f"Rate limit delay: {delay}s between calls")
    print()

    # Phase 1: Download by category
    for cat in CATEGORIES:
        cat_name = cat["name"]
        cat_history = history["categories"].get(cat_name, {"downloaded": 0, "offset": 0, "ids": []})

        if cat_history["downloaded"] >= args.per_category:
            print(f"[{cat_name}] Already at target ({cat_history['downloaded']}/{args.per_category}), skipping")
            continue

        if already_downloaded + total_new >= args.target:
            print(f"Reached target ({args.target}), stopping")
            break

        print(f"[{cat_name}] Downloading (have {cat_history['downloaded']}/{args.per_category})...")

        for query in cat["queries"]:
            if cat_history["downloaded"] >= args.per_category:
                break
            if already_downloaded + total_new >= args.target:
                break

            # Paginate through offsets for each query (max offset 4999)
            offset = cat_history.get(f"offset_{query}", 0)
            while offset < 5000:
                if cat_history["downloaded"] >= args.per_category:
                    break
                if already_downloaded + total_new >= args.target:
                    break

                # Fetch batch
                if delay > 0 and api_calls > 0:
                    print(f"  Rate limit: waiting {delay}s...")
                    time.sleep(delay)

                try:
                    result = search_giphy(api_key, query, offset=offset, limit=BATCH_SIZE)
                    api_calls += 1
                except Exception as e:
                    print(f"  API error for '{query}': {e}")
                    break

                gifs = result.get("data", [])
                if not gifs:
                    print(f"  No more results for '{query}' at offset {offset}")
                    break

                print(f"  '{query}': {len(gifs)} results (offset {offset})")

                new_in_batch = 0
                for gif_data in gifs:
                    giphy_id = gif_data["id"]
                    if giphy_id in seen_ids:
                        continue
                    if cat_history["downloaded"] >= args.per_category:
                        break
                    if already_downloaded + total_new >= args.target:
                        break

                    # Get download URL (original GIF)
                    original = gif_data.get("images", {}).get("original", {})
                    gif_url = original.get("url", "")
                    if not gif_url:
                        continue

                    file_id = f"{next_id:06d}"
                    gif_path = gifs_dir / f"{file_id}.gif"

                    if download_gif(gif_url, gif_path):
                        save_metadata(gif_data, cat_name, output_dir, file_id)
                        seen_ids.add(giphy_id)
                        cat_history["downloaded"] += 1
                        cat_history["ids"] = cat_history.get("ids", []) + [giphy_id]
                        next_id += 1
                        total_new += 1
                        new_in_batch += 1

                        if total_new % 50 == 0:
                            print(f"  Progress: {total_new} new ({len(seen_ids)} total)")

                offset += BATCH_SIZE
                cat_history[f"offset_{query}"] = offset

                # Save history after each API call
                history["categories"][cat_name] = cat_history
                history["downloaded_ids"] = list(seen_ids)
                history["api_calls"] = api_calls
                history["total_downloaded"] = len(seen_ids)
                save_history(history, history_path)

                # If no new GIFs in this batch, move to next query
                if new_in_batch == 0:
                    break

    # Phase 2: Top up with trending if needed
    remaining = args.target - (already_downloaded + total_new)
    if remaining > 0:
        print(f"\nTopping up with {remaining} trending GIFs...")
        trending_offset = history.get("trending_offset", 0)

        while remaining > 0:
            if delay > 0 and api_calls > 0:
                print(f"  Rate limit: waiting {delay}s...")
                time.sleep(delay)

            try:
                result = fetch_trending(api_key, offset=trending_offset, limit=BATCH_SIZE)
                api_calls += 1
            except Exception as e:
                print(f"  Trending API error: {e}")
                break

            gifs = result.get("data", [])
            if not gifs:
                break

            for gif_data in gifs:
                if remaining <= 0:
                    break
                giphy_id = gif_data["id"]
                if giphy_id in seen_ids:
                    continue

                original = gif_data.get("images", {}).get("original", {})
                gif_url = original.get("url", "")
                if not gif_url:
                    continue

                file_id = f"{next_id:06d}"
                gif_path = gifs_dir / f"{file_id}.gif"

                if download_gif(gif_url, gif_path):
                    save_metadata(gif_data, "trending", output_dir, file_id)
                    seen_ids.add(giphy_id)
                    next_id += 1
                    total_new += 1
                    remaining -= 1

            trending_offset += BATCH_SIZE
            history["trending_offset"] = trending_offset
            history["downloaded_ids"] = list(seen_ids)
            history["api_calls"] = api_calls
            history["total_downloaded"] = len(seen_ids)
            save_history(history, history_path)

    # Final summary
    print(f"\n=== COMPLETE ===")
    print(f"New downloads: {total_new}")
    print(f"Total in collection: {len(seen_ids)}")
    print(f"API calls used: {api_calls}")
    print(f"History saved to: {history_path}")

    # Save final history
    history["downloaded_ids"] = list(seen_ids)
    history["api_calls"] = api_calls
    history["total_downloaded"] = len(seen_ids)
    save_history(history, history_path)


if __name__ == "__main__":
    main()
