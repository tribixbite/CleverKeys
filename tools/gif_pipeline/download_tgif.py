#!/usr/bin/env python3
"""
Download TGIF dataset from Hugging Face.

The TGIF (Tumblr GIF) dataset contains 100K animated GIFs with natural language
descriptions. Each entry includes video_bytes (actual GIF data) and descriptions.

Usage:
    python download_tgif.py --output ./raw_data --limit 50000

Reference: https://huggingface.co/datasets/HuggingFaceM4/TGIF
"""

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Optional

from datasets import load_dataset
from tqdm import tqdm


def download_tgif(
    output_dir: str,
    limit: Optional[int] = None,
    skip_existing: bool = True,
    save_metadata: bool = True
) -> dict:
    """
    Download TGIF dataset and save GIFs with metadata.

    Args:
        output_dir: Directory to save downloaded files
        limit: Maximum number of GIFs to download (None for all)
        skip_existing: Skip files that already exist
        save_metadata: Save description metadata alongside GIFs

    Returns:
        Statistics dictionary with download counts
    """
    output_path = Path(output_dir)
    gifs_dir = output_path / "gifs"
    metadata_dir = output_path / "metadata"

    gifs_dir.mkdir(parents=True, exist_ok=True)
    metadata_dir.mkdir(parents=True, exist_ok=True)

    print("Loading TGIF dataset from Hugging Face...")
    print("This may take a while on first run (downloading ~20GB)...")

    try:
        # Load dataset - this streams from Hugging Face
        dataset = load_dataset(
            "HuggingFaceM4/TGIF",
            split="train",
            streaming=True  # Stream to avoid loading entire dataset in memory
        )
    except Exception as e:
        print(f"Error loading dataset: {e}")
        print("Make sure you have huggingface_hub installed and are logged in:")
        print("  pip install huggingface_hub")
        print("  huggingface-cli login")
        return {"error": str(e)}

    stats = {
        "downloaded": 0,
        "skipped": 0,
        "failed": 0,
        "total_bytes": 0
    }

    # Process items
    items = enumerate(dataset)
    if limit:
        items = ((i, item) for i, item in items if i < limit)

    pbar = tqdm(items, total=limit, desc="Downloading GIFs")

    for idx, item in pbar:
        gif_id = f"{idx:06d}"
        gif_path = gifs_dir / f"{gif_id}.gif"
        meta_path = metadata_dir / f"{gif_id}.json"

        # Skip if exists
        if skip_existing and gif_path.exists():
            stats["skipped"] += 1
            continue

        try:
            # Get video bytes (actual GIF data)
            video_bytes = item.get("video_bytes") or item.get("video")
            if video_bytes is None:
                # Try to get from video path/url if bytes not directly available
                print(f"Warning: No video_bytes for item {idx}")
                stats["failed"] += 1
                continue

            # Handle different data formats
            if isinstance(video_bytes, dict) and "bytes" in video_bytes:
                video_bytes = video_bytes["bytes"]
            elif isinstance(video_bytes, str):
                # It's a path or URL, skip for now
                print(f"Warning: video_bytes is path/URL for item {idx}")
                stats["failed"] += 1
                continue

            # Save GIF file
            with open(gif_path, "wb") as f:
                f.write(video_bytes)

            stats["downloaded"] += 1
            stats["total_bytes"] += len(video_bytes)

            # Save metadata
            if save_metadata:
                description = item.get("description", item.get("caption", ""))
                metadata = {
                    "id": gif_id,
                    "description": description,
                    "source": "TGIF",
                    "original_idx": idx,
                    "file_size": len(video_bytes)
                }

                # Extract any additional fields
                for key in ["video_path", "url", "tags"]:
                    if key in item and item[key]:
                        metadata[key] = item[key]

                with open(meta_path, "w", encoding="utf-8") as f:
                    json.dump(metadata, f, ensure_ascii=False, indent=2)

            pbar.set_postfix({
                "downloaded": stats["downloaded"],
                "MB": f"{stats['total_bytes'] / 1024 / 1024:.1f}"
            })

        except Exception as e:
            print(f"\nError processing item {idx}: {e}")
            stats["failed"] += 1

    # Save summary
    summary_path = output_path / "download_summary.json"
    with open(summary_path, "w") as f:
        json.dump(stats, f, indent=2)

    print(f"\nDownload complete!")
    print(f"  Downloaded: {stats['downloaded']}")
    print(f"  Skipped: {stats['skipped']}")
    print(f"  Failed: {stats['failed']}")
    print(f"  Total size: {stats['total_bytes'] / 1024 / 1024:.1f} MB")
    print(f"  Output: {output_path}")

    return stats


def main():
    parser = argparse.ArgumentParser(
        description="Download TGIF dataset from Hugging Face"
    )
    parser.add_argument(
        "--output", "-o",
        type=str,
        default="./raw_data",
        help="Output directory for downloaded files"
    )
    parser.add_argument(
        "--limit", "-l",
        type=int,
        default=None,
        help="Maximum number of GIFs to download (default: all)"
    )
    parser.add_argument(
        "--no-skip",
        action="store_true",
        help="Re-download existing files"
    )
    parser.add_argument(
        "--no-metadata",
        action="store_true",
        help="Skip saving metadata files"
    )

    args = parser.parse_args()

    stats = download_tgif(
        output_dir=args.output,
        limit=args.limit,
        skip_existing=not args.no_skip,
        save_metadata=not args.no_metadata
    )

    if "error" in stats:
        sys.exit(1)


if __name__ == "__main__":
    main()
