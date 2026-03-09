# GIF Pipeline

Tools for building offline GIF packs for CleverKeys keyboard.

## Build Your Own Pack (Users)

The easiest way to create a personal GIF pack for CleverKeys. Downloads GIFs
from a free API, converts to optimized thumbnails, and produces an importable ZIP.

**Requirements:** Python 3.10+, Pillow, requests, tqdm, ffmpeg (optional)

```bash
pip install requests Pillow tqdm

# Interactive mode — guides you through everything
python make_pack.py

# Quick: 500 reaction GIFs from Giphy (free API key required)
python make_pack.py --source giphy --api-key YOUR_KEY --preset reactions --count 500

# All emotion categories, 100 each from Klipy (free, Tenor replacement)
python make_pack.py --source klipy --api-key YOUR_KEY --preset all --per-category 100

# Custom search queries
python make_pack.py --source giphy --api-key YOUR_KEY --queries "cats funny,dogs cute" --count 300

# Build from your own local GIF files (no API key needed)
python make_pack.py --source local --input ~/my-gifs/ --name "my-collection"
```

### Getting a Free API Key

| Source | Free Tier | Rate Limit | Sign Up |
|--------|-----------|------------|---------|
| **Giphy** | Beta key | 42 req/hr | [developers.giphy.com](https://developers.giphy.com/) |
| **Klipy** | Production key | 100 req/min | [klipy.com/migrate](https://klipy.com/migrate) |

### Importing Into CleverKeys

1. Copy the generated `.zip` file to your phone
2. Open CleverKeys Settings → GIF Panel → Enable
3. Tap **Import Pack** → select the ZIP file

### Available Presets

| Preset | Description |
|--------|-------------|
| `reactions` | Viral reactions, mood, relatable GIFs |
| `positive` | Happy, love, excited, proud, approve |
| `humor` | Funny, laughing, smug, awkward |
| `negative` | Sad, angry, scared, disgusted, sorry |
| `surprise` | Surprised and relieved reactions |
| `animals` | Cat, dog, and animal reactions |
| `all` | All of the above combined |

> **Note:** API terms prohibit redistributing downloaded GIFs. Each user must
> get their own free API key and build packs for personal use only.

---

## Pre-Built Packs (Download)

Ready-made packs are available on the
[CleverKeys-GIF release](https://github.com/tribixbite/CleverKeys/releases/tag/CleverKeys-GIF):

- **6 Discord Community packs** — 5,232 full animated GIFs (575 MB)
- **7 Category thumbnail packs** — organized by emotion (130K GIFs)
- **1 All-in-one thumbnail pack** — thumbs-all-130k.zip (187 MB)
- **Base database** — gifs_v2.db.gz (metadata index, no images)

---

## Developer Pipeline

Full pipeline for building packs from scratch (advanced).

### Quick Start

```bash
# Install dependencies
pip install -r requirements.txt

# Also need ffmpeg installed
# Termux: pkg install ffmpeg
# Ubuntu: sudo apt install ffmpeg
# macOS: brew install ffmpeg

# 1. Download TGIF dataset (100K GIFs, ~20GB download)
python download_tgif.py --output ./raw_data --limit 50000

# 2. Process GIFs to WebP format
python process_gifs.py --input ./raw_data/gifs --output ./processed --workers 4

# 3. Build SQLite database
python build_database.py \
    --processed ./processed \
    --metadata ./raw_data/metadata \
    --output ../../assets/gifs.db
```

## Pipeline Steps

### 1. Download (`download_tgif.py`)

Downloads the TGIF dataset from Hugging Face:
- 100K animated GIFs with natural language descriptions
- Saves raw GIF files and metadata JSON

```bash
# Download first 1000 for testing
python download_tgif.py --output ./raw_data --limit 1000

# Download all (default)
python download_tgif.py --output ./raw_data
```

### 2. Process (`process_gifs.py`)

Converts GIFs to optimized WebP format:
- **Full animated WebP**: 480px max, 15fps, ~70% quality
- **Static thumbnail**: 160px, single frame
- Parallel processing with configurable workers

```bash
# Process with 8 workers
python process_gifs.py \
    --input ./raw_data/gifs \
    --output ./processed \
    --workers 8
```

### 3. Build Database (`build_database.py`)

Creates SQLite database with FTS5 full-text search:
- Extracts keywords from descriptions
- Classifies GIFs into 17 emotion categories
- Builds FTS5 index for fast search

```bash
python build_database.py \
    --processed ./processed \
    --metadata ./raw_data/metadata \
    --output ./gifs.db
```

## Output Structure

```
processed/
├── full/           # Animated WebP files (480px)
│   ├── 000001.webp
│   └── ...
├── thumbs/         # Static thumbnails (160px)
│   ├── 000001.webp
│   └── ...
└── processing_results.json

gifs.db             # SQLite database (~5MB for 50K GIFs)
```

## Database Schema

```sql
-- Categories (17 emotion categories)
CREATE TABLE categories (
    category_id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    icon TEXT NOT NULL,
    sort_order INTEGER NOT NULL
);

-- GIF metadata
CREATE TABLE gifs (
    gif_id INTEGER PRIMARY KEY,
    asset_name TEXT NOT NULL,
    thumbnail_name TEXT NOT NULL,
    width INTEGER,
    height INTEGER,
    duration_ms INTEGER,
    file_size INTEGER
);

-- Category mappings
CREATE TABLE gif_category_map (
    gif_id INTEGER,
    category_id INTEGER
);

-- FTS5 full-text search
CREATE VIRTUAL TABLE gifs_fts USING fts5(
    search_text
);

-- Usage tracking
CREATE TABLE gif_usage (
    gif_id INTEGER PRIMARY KEY,
    use_count INTEGER,
    last_used INTEGER
);
```

## Storage Estimates

| Component | Per GIF | 50,000 GIFs |
|-----------|---------|-------------|
| Full WebP | ~100KB | ~5 GB |
| Thumbnail | ~15KB | ~750 MB |
| Database | - | ~5 MB |
| **Total** | ~115KB | **~5.75 GB** |

## Tiered Packs Strategy

Since 5.75 GB is too large for APK bundling:

1. **Core Pack** (bundled): 2,000 popular GIFs (~230 MB)
2. **Extended Pack** (download): 10,000 GIFs (~1.15 GB)
3. **Full Pack** (download): 50,000 GIFs (~5.75 GB)

## Categories

Based on GIFGIF+ research (MIT Media Lab):

| ID | Name | Icon | Keywords |
|----|------|------|----------|
| 1 | Funny | 😂 | laugh, lol, haha |
| 2 | Angry | 😠 | angry, rage, mad |
| 3 | Smug | 😏 | smirk, eye roll |
| 4 | Relaxed | 😌 | calm, chill, zen |
| 5 | Disgusted | 🤢 | gross, ew, yuck |
| 6 | Awkward | 😳 | cringe, facepalm |
| 7 | Excited | 🤩 | yay, woohoo |
| 8 | Scared | 😨 | fear, terrified |
| 9 | Sorry | 😔 | apologize, regret |
| 10 | Happy | 😊 | smile, joy |
| 11 | Love | 😍 | heart, romantic |
| 12 | Proud | 😤 | victory, flex |
| 13 | Relieved | 😅 | phew, whew |
| 14 | Sad | 😢 | cry, tears |
| 15 | Ashamed | 😞 | shame, fail |
| 16 | Surprised | 😲 | omg, shocked |
| 17 | Approve | 👍 | yes, agree, ok |

## License & API Terms

### API Terms (make_pack.py personal packs)
- **Giphy**: Prohibits caching, storing, or building databases from their content
  for redistribution. Personal-use packs built with make_pack.py are for your
  device only — do NOT share resulting ZIPs publicly.
- **Klipy**: Free Tenor replacement. Requires "Powered by KLIPY" attribution.
  Terms silent on offline caching for personal use.
- **Tenor**: Shut down June 30, 2026. New API keys no longer available.

### Dataset Sources (developer pipeline)
- **TGIF Dataset**: Academic research license — verify for commercial use
- **Video2GIF Dataset**: BSD-3-Clause (permissive)
- **GIFGIF+**: MIT Media Lab research
- **Discord CDN**: Community-sourced GIFs, fair-use thumbnails (80px static WebP)

### Pre-built packs on GitHub Releases
- Thumbnails (80px static WebP): strong fair use — *Google v. Perfect 10* precedent
- Full animated Discord packs: grey-area (non-commercial, user-curated, preservation)

Always verify licensing before distribution.

## Troubleshooting

### "ffmpeg not found"
```bash
# Termux
pkg install ffmpeg

# Ubuntu/Debian
sudo apt install ffmpeg

# macOS
brew install ffmpeg
```

### "Dataset loading failed"
```bash
# Login to Hugging Face
pip install huggingface_hub
huggingface-cli login
```

### "Out of memory"
Reduce worker count or process in batches:
```bash
python process_gifs.py --workers 2 --limit 10000
```
