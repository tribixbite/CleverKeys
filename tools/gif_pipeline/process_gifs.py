#!/usr/bin/env python3
"""
Process GIFs: Convert to optimized WebP format and generate thumbnails.

This script:
1. Reads raw GIFs from the download directory
2. Converts to animated WebP (50-80% smaller than GIF)
3. Generates static WebP thumbnails
4. Resizes to mobile-friendly dimensions
5. Applies quality optimization

Usage:
    python process_gifs.py --input ./raw_data/gifs --output ./processed

Requirements:
    - ffmpeg installed and in PATH
    - Pillow for image processing
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from PIL import Image
from tqdm import tqdm


# Processing configuration - PRESET PROFILES
# Choose profile based on target pack size

PROFILES = {
    # ~50MB for 2000 GIFs - Very aggressive, visible quality loss
    "tiny": {
        "full": {
            "max_width": 200,
            "max_height": 200,
            "quality": 40,
            "fps": 8,
            "lossless": False
        },
        "thumb": {
            "max_width": 80,
            "max_height": 80,
            "quality": 60,
            "frame": "middle"
        }
    },
    # ~100MB for 2000 GIFs - Good balance for mobile keyboards
    "balanced": {
        "full": {
            "max_width": 240,
            "max_height": 240,
            "quality": 55,
            "fps": 10,
            "lossless": False
        },
        "thumb": {
            "max_width": 80,
            "max_height": 80,
            "quality": 70,
            "frame": "middle"
        }
    },
    # ~160MB for 2000 GIFs - Higher quality
    "quality": {
        "full": {
            "max_width": 320,
            "max_height": 320,
            "quality": 60,
            "fps": 12,
            "lossless": False
        },
        "thumb": {
            "max_width": 120,
            "max_height": 120,
            "quality": 70,
            "frame": "middle"
        }
    },
    # ~450MB for 2000 GIFs - Maximum quality
    "hd": {
        "full": {
            "max_width": 480,
            "max_height": 480,
            "quality": 70,
            "fps": 15,
            "lossless": False
        },
        "thumb": {
            "max_width": 160,
            "max_height": 160,
            "quality": 80,
            "frame": "middle"
        }
    }
}

# Default profile - optimized for keyboard use
CONFIG = PROFILES["balanced"]


def check_ffmpeg() -> bool:
    """Check if ffmpeg is available."""
    try:
        result = subprocess.run(
            ["ffmpeg", "-version"],
            capture_output=True,
            text=True
        )
        return result.returncode == 0
    except FileNotFoundError:
        return False


def get_gif_info(gif_path: Path) -> Optional[Dict]:
    """Get GIF metadata using ffprobe."""
    try:
        result = subprocess.run(
            [
                "ffprobe", "-v", "quiet",
                "-print_format", "json",
                "-show_format", "-show_streams",
                str(gif_path)
            ],
            capture_output=True,
            text=True
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
            "file_size": int(data.get("format", {}).get("size", 0))
        }
    except Exception as e:
        print(f"Error getting info for {gif_path}: {e}")
        return None


def calculate_dimensions(
    width: int,
    height: int,
    max_width: int,
    max_height: int
) -> Tuple[int, int]:
    """Calculate new dimensions maintaining aspect ratio."""
    if width <= max_width and height <= max_height:
        return width, height

    ratio = min(max_width / width, max_height / height)
    new_width = int(width * ratio)
    new_height = int(height * ratio)

    # Ensure even dimensions for video encoding
    new_width = new_width if new_width % 2 == 0 else new_width + 1
    new_height = new_height if new_height % 2 == 0 else new_height + 1

    return new_width, new_height


def process_to_animated_webp(
    input_path: Path,
    output_path: Path,
    config: Dict
) -> bool:
    """Convert GIF to animated WebP using ffmpeg."""
    try:
        # Get original dimensions
        info = get_gif_info(input_path)
        if not info:
            return False

        new_w, new_h = calculate_dimensions(
            info["width"], info["height"],
            config["max_width"], config["max_height"]
        )

        # Reject malformed GIFs with absurd frame delays (>5s avg per frame)
        # These are valid GIF files but with broken delay headers (e.g. 655s/frame)
        duration = info.get("duration", 0)
        frames = max(int(info.get("nb_frames", 1)), 1)
        avg_frame_delay = duration / frames
        if avg_frame_delay > 5.0:
            return False

        # Build ffmpeg command
        cmd = [
            "ffmpeg", "-y",
            "-i", str(input_path),
            "-c:v", "libwebp",
            "-lossless", "0" if not config["lossless"] else "1",
            "-q:v", str(config["quality"]),
            "-vf", f"fps={config['fps']},scale={new_w}:{new_h}:flags=lanczos",
            "-loop", "0",  # Infinite loop
            str(output_path)
        ]

        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True
        )

        return result.returncode == 0 and output_path.exists()

    except Exception as e:
        print(f"Error converting {input_path}: {e}")
        return False


def generate_thumbnail(
    input_path: Path,
    output_path: Path,
    config: Dict
) -> bool:
    """Generate static WebP thumbnail from GIF."""
    try:
        # Use Pillow for thumbnail generation (simpler than ffmpeg for single frame)
        with Image.open(input_path) as img:
            # Determine which frame to use
            n_frames = getattr(img, "n_frames", 1)

            if config["frame"] == "middle" and n_frames > 1:
                img.seek(n_frames // 2)
            elif config["frame"] == "last" and n_frames > 1:
                img.seek(n_frames - 1)
            # else: first frame (default)

            # Convert to RGB if necessary (WebP doesn't support palette mode well)
            frame = img.convert("RGBA")

            # Resize maintaining aspect ratio
            frame.thumbnail(
                (config["max_width"], config["max_height"]),
                Image.Resampling.LANCZOS
            )

            # Save as WebP
            frame.save(
                output_path,
                "WEBP",
                quality=config["quality"],
                method=6  # Slowest but best compression
            )

            return output_path.exists()

    except Exception as e:
        print(f"Error generating thumbnail for {input_path}: {e}")
        return False


def process_single_gif(args: Tuple[Path, Path, Path, Dict, Dict]) -> Dict:
    """Process a single GIF file (for parallel processing)."""
    input_path, full_output, thumb_output, full_config, thumb_config = args

    result = {
        "id": input_path.stem,
        "success": False,
        "full_size": 0,
        "thumb_size": 0,
        "width": 0,
        "height": 0,
        "duration_ms": 0
    }

    # Get original info
    info = get_gif_info(input_path)
    if info:
        result["original_width"] = info["width"]
        result["original_height"] = info["height"]
        result["duration_ms"] = int(info["duration"] * 1000)

    # Generate animated WebP
    if process_to_animated_webp(input_path, full_output, full_config):
        result["full_size"] = full_output.stat().st_size

        # Get processed dimensions
        processed_info = get_gif_info(full_output)
        if processed_info:
            result["width"] = processed_info["width"]
            result["height"] = processed_info["height"]

    # Generate thumbnail
    if generate_thumbnail(input_path, thumb_output, thumb_config):
        result["thumb_size"] = thumb_output.stat().st_size
        result["success"] = True

    return result


def process_all_gifs(
    input_dir: str,
    output_dir: str,
    workers: int = 4,
    limit: Optional[int] = None
) -> Dict:
    """Process all GIFs in input directory."""
    input_path = Path(input_dir)
    output_path = Path(output_dir)

    full_dir = output_path / "full"
    thumbs_dir = output_path / "thumbs"

    full_dir.mkdir(parents=True, exist_ok=True)
    thumbs_dir.mkdir(parents=True, exist_ok=True)

    # Find all GIF files (skip WebP — those go directly to squoosh)
    gif_files = sorted(input_path.glob("*.gif"))
    if limit:
        gif_files = gif_files[:limit]

    print(f"Found {len(gif_files)} GIF files to process")

    if not gif_files:
        return {"error": "No GIF files found"}

    # Prepare processing arguments, skipping already-processed files
    process_args = []
    skipped = 0
    for gif_path in gif_files:
        gif_id = gif_path.stem
        full_output = full_dir / f"{gif_id}.webp"
        thumb_output = thumbs_dir / f"{gif_id}.webp"

        # Skip if both outputs already exist with non-zero size
        if full_output.exists() and full_output.stat().st_size > 0 and \
           thumb_output.exists() and thumb_output.stat().st_size > 0:
            skipped += 1
            continue

        process_args.append((
            gif_path,
            full_output,
            thumb_output,
            CONFIG["full"],
            CONFIG["thumb"]
        ))

    if skipped > 0:
        print(f"Skipped {skipped} already-processed GIFs")
    print(f"Processing {len(process_args)} remaining GIFs")

    if not process_args:
        print("All GIFs already processed!")
        return {"processed": 0, "failed": 0, "skipped": skipped}

    # Process in parallel
    stats = {
        "processed": 0,
        "failed": 0,
        "total_full_bytes": 0,
        "total_thumb_bytes": 0,
        "results": []
    }

    with ProcessPoolExecutor(max_workers=workers) as executor:
        futures = {
            executor.submit(process_single_gif, args): args[0]
            for args in process_args
        }

        pbar = tqdm(
            as_completed(futures),
            total=len(futures),
            desc="Processing GIFs"
        )

        for future in pbar:
            result = future.result()
            stats["results"].append(result)

            if result["success"]:
                stats["processed"] += 1
                stats["total_full_bytes"] += result["full_size"]
                stats["total_thumb_bytes"] += result["thumb_size"]
            else:
                stats["failed"] += 1

            pbar.set_postfix({
                "ok": stats["processed"],
                "fail": stats["failed"],
                "MB": f"{(stats['total_full_bytes'] + stats['total_thumb_bytes']) / 1024 / 1024:.1f}"
            })

    # Save processing results
    results_path = output_path / "processing_results.json"
    with open(results_path, "w") as f:
        json.dump(stats, f, indent=2)

    print(f"\nProcessing complete!")
    print(f"  Processed: {stats['processed']}")
    print(f"  Failed: {stats['failed']}")
    print(f"  Full WebP size: {stats['total_full_bytes'] / 1024 / 1024:.1f} MB")
    print(f"  Thumbnails size: {stats['total_thumb_bytes'] / 1024 / 1024:.1f} MB")
    print(f"  Output: {output_path}")

    return stats


def main():
    parser = argparse.ArgumentParser(
        description="Convert GIFs to optimized WebP format"
    )
    parser.add_argument(
        "--input", "-i",
        type=str,
        required=True,
        help="Input directory containing GIF files"
    )
    parser.add_argument(
        "--output", "-o",
        type=str,
        default="./processed",
        help="Output directory for processed files"
    )
    parser.add_argument(
        "--workers", "-w",
        type=int,
        default=4,
        help="Number of parallel workers"
    )
    parser.add_argument(
        "--limit", "-l",
        type=int,
        default=None,
        help="Maximum number of GIFs to process"
    )
    parser.add_argument(
        "--profile", "-p",
        type=str,
        default="balanced",
        choices=["tiny", "balanced", "quality", "hd"],
        help="Compression profile: tiny (~50MB/2k), balanced (~100MB/2k), quality (~160MB/2k), hd (~450MB/2k)"
    )

    args = parser.parse_args()

    # Set profile
    global CONFIG
    CONFIG = PROFILES[args.profile]
    print(f"Using '{args.profile}' profile:")
    print(f"  Full: {CONFIG['full']['max_width']}px, {CONFIG['full']['fps']}fps, Q{CONFIG['full']['quality']}")
    print(f"  Thumb: {CONFIG['thumb']['max_width']}px, Q{CONFIG['thumb']['quality']}")

    # Check ffmpeg
    if not check_ffmpeg():
        print("Error: ffmpeg not found. Please install ffmpeg:")
        print("  Ubuntu/Debian: sudo apt install ffmpeg")
        print("  macOS: brew install ffmpeg")
        print("  Termux: pkg install ffmpeg")
        sys.exit(1)

    stats = process_all_gifs(
        input_dir=args.input,
        output_dir=args.output,
        workers=args.workers,
        limit=args.limit
    )

    if "error" in stats:
        sys.exit(1)


if __name__ == "__main__":
    main()
