#!/usr/bin/env python3
"""
Simple TGIF downloader using direct file downloads.

The TGIF dataset files are stored on Hugging Face as parquet files.
This script downloads them directly without requiring pyarrow for local processing.

Alternative: Download pre-extracted GIF URLs and fetch individually.

Usage:
    python download_tgif_simple.py --output ./raw_data --limit 50000
"""

import argparse
import json
import os
import sys
import time
import requests
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed
from tqdm import tqdm

# TGIF dataset info
TGIF_REPO = "HuggingFaceM4/TGIF"
TGIF_BASE_URL = "https://huggingface.co/datasets/HuggingFaceM4/TGIF/resolve/main"

# The TGIF dataset stores GIF URLs in these files
# We'll need to parse them to get individual GIF URLs
TGIF_METADATA_URL = f"{TGIF_BASE_URL}/data/train-00000-of-00001.parquet"


def download_gif(url: str, output_path: Path, timeout: int = 30) -> bool:
    """Download a single GIF file."""
    try:
        response = requests.get(url, timeout=timeout, stream=True)
        response.raise_for_status()

        with open(output_path, 'wb') as f:
            for chunk in response.iter_content(chunk_size=8192):
                f.write(chunk)

        return True
    except Exception as e:
        return False


def download_tgif_urls(output_dir: str, limit: int = None, workers: int = 8):
    """
    Download TGIF GIFs from their original Tumblr URLs.

    Note: Many original Tumblr URLs may be dead since the dataset is from 2015.
    This method will skip unavailable GIFs and report success rate.
    """
    output_path = Path(output_dir)
    gifs_dir = output_path / "gifs"
    metadata_dir = output_path / "metadata"

    gifs_dir.mkdir(parents=True, exist_ok=True)
    metadata_dir.mkdir(parents=True, exist_ok=True)

    # First, try to get the URL list from the TGIF TSV file
    # The original TGIF release has a simpler format
    tgif_tsv_url = "https://raw.githubusercontent.com/raingo/TGIF-Release/master/data/tgif-v1.0.tsv"

    print("Fetching TGIF URL list...")
    try:
        response = requests.get(tgif_tsv_url, timeout=60)
        response.raise_for_status()
        lines = response.text.strip().split('\n')
    except Exception as e:
        print(f"Failed to fetch URL list: {e}")
        print("Trying alternative source...")

        # Alternative: Use the GitHub release directly
        alt_url = "https://github.com/raingo/TGIF-Release/raw/master/data/tgif-v1.0.tsv"
        try:
            response = requests.get(alt_url, timeout=60)
            response.raise_for_status()
            lines = response.text.strip().split('\n')
        except Exception as e2:
            print(f"Alternative also failed: {e2}")
            return {"error": str(e2)}

    print(f"Found {len(lines)} GIF entries")

    if limit:
        lines = lines[:limit]

    # Parse TSV: URL<tab>description
    entries = []
    for i, line in enumerate(lines):
        parts = line.split('\t')
        if len(parts) >= 2:
            entries.append({
                'id': f'{i:06d}',
                'url': parts[0].strip(),
                'description': parts[1].strip() if len(parts) > 1 else ''
            })

    print(f"Downloading {len(entries)} GIFs with {workers} workers...")

    stats = {
        'downloaded': 0,
        'failed': 0,
        'skipped': 0,
        'total_bytes': 0
    }

    def download_entry(entry):
        gif_path = gifs_dir / f"{entry['id']}.gif"
        meta_path = metadata_dir / f"{entry['id']}.json"

        # Skip if exists
        if gif_path.exists() and gif_path.stat().st_size > 0:
            return ('skipped', entry['id'], 0)

        # Download GIF
        if download_gif(entry['url'], gif_path):
            file_size = gif_path.stat().st_size

            # Save metadata
            metadata = {
                'id': entry['id'],
                'description': entry['description'],
                'source': 'TGIF',
                'original_url': entry['url'],
                'file_size': file_size
            }
            with open(meta_path, 'w', encoding='utf-8') as f:
                json.dump(metadata, f, ensure_ascii=False, indent=2)

            return ('downloaded', entry['id'], file_size)
        else:
            return ('failed', entry['id'], 0)

    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {executor.submit(download_entry, e): e for e in entries}

        pbar = tqdm(as_completed(futures), total=len(futures), desc="Downloading")

        for future in pbar:
            status, gif_id, size = future.result()

            if status == 'downloaded':
                stats['downloaded'] += 1
                stats['total_bytes'] += size
            elif status == 'skipped':
                stats['skipped'] += 1
            else:
                stats['failed'] += 1

            pbar.set_postfix({
                'ok': stats['downloaded'],
                'fail': stats['failed'],
                'MB': f"{stats['total_bytes'] / 1024 / 1024:.1f}"
            })

    # Save summary
    summary_path = output_path / "download_summary.json"
    with open(summary_path, 'w') as f:
        json.dump(stats, f, indent=2)

    success_rate = stats['downloaded'] / (stats['downloaded'] + stats['failed']) * 100 if (stats['downloaded'] + stats['failed']) > 0 else 0

    print(f"\nDownload complete!")
    print(f"  Downloaded: {stats['downloaded']}")
    print(f"  Skipped: {stats['skipped']}")
    print(f"  Failed: {stats['failed']}")
    print(f"  Success rate: {success_rate:.1f}%")
    print(f"  Total size: {stats['total_bytes'] / 1024 / 1024:.1f} MB")
    print(f"  Output: {output_path}")

    return stats


def main():
    parser = argparse.ArgumentParser(
        description="Download TGIF GIFs from original URLs"
    )
    parser.add_argument(
        "--output", "-o",
        type=str,
        default="./raw_data",
        help="Output directory"
    )
    parser.add_argument(
        "--limit", "-l",
        type=int,
        default=None,
        help="Maximum GIFs to download"
    )
    parser.add_argument(
        "--workers", "-w",
        type=int,
        default=8,
        help="Parallel download workers"
    )

    args = parser.parse_args()

    stats = download_tgif_urls(
        output_dir=args.output,
        limit=args.limit,
        workers=args.workers
    )

    if "error" in stats:
        sys.exit(1)


if __name__ == "__main__":
    main()
