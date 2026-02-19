# GIF Panel Specification

## Overview

Offline GIF panel for CleverKeys keyboard, similar to the emoji panel but displaying animated GIFs from a pre-bundled database. No internet permissions required.

## Status: Pipeline & Pack System Complete — UI Integration Pending
- Created: 2025-01-27
- Updated: 2026-02-08
- Author: Claude Opus 4.5 / Opus 4.6

### Completed
- [x] Giphy API pipeline (118,944 unique GIFs downloaded)
- [x] WebP conversion via ffmpeg (balanced profile: 240px, 10fps, Q55)
- [x] Squoosh optimization (85,954 optimized, avg 46KB, q12 m6 sharp_yuv)
- [x] Thumbnail generation (80px, Q70)
- [x] Kotlin data models (Gif, GifCategory, GifDatabase, GifAssetManager, GifPackManager, GifGridView)
- [x] Pack builder (tar.gz archives with manifest.json, 50MB per pack)
- [x] Pipeline improvements: fixed_width WebP download, adaptive quality, small file skip
- [x] Database builder with slug keyword extraction and pack_id support

### Remaining
- [ ] GIF panel XML layout (tab in keyboard panel)
- [ ] Integration with existing keyboard panel system
- [ ] GIF insertion via commitContent() + clipboard fallback
- [ ] FileProvider for content URIs
- [ ] Pack download manager (GifPackManager.kt)
- [ ] Coil image loading for memory-safe IME rendering
- [ ] Content moderation pass

---

## Requirements

### Must Have
- [ ] Offline-only operation (no INTERNET permission)
- [ ] 50,000+ GIFs with keyword/tag metadata
- [ ] Full-text search with FTS5
- [ ] Category/group browsing (17 emotion categories)
- [ ] Static thumbnails for grid view (memory efficient)
- [ ] Animated preview on selection
- [ ] Insert GIF as image to input field

### Nice to Have
- [ ] Recently used GIFs section
- [ ] Favorites/pinned GIFs
- [ ] Skin tone variants (where applicable)
- [ ] GIF quality settings (bandwidth-like for storage)

---

## Data Source: Giphy API

TGIF and other academic datasets were abandoned in favor of Giphy API for better
content quality, richer metadata, and more relevant reaction GIFs.

### Pipeline: `tools/gif_pipeline/pipeline.py`
- **API**: Giphy Search + Trending endpoints (beta key, 100 calls/hr)
- **Categories**: 17 emotion categories (~30 queries each) + trending + popular + viral + universal
- **Download**: Prefers `fixed_width` WebP variant (200px, already WebP, ~80% smaller than original GIF)
- **Processing**: raw download → ffmpeg WebP conversion (if GIF) → squoosh optimization (q12 m6 sharp_yuv)
- **Current stats**: 118,944 downloaded, 85,954 optimized (~3.8GB, avg 46KB/gif)
- **Quality**: P50=36KB, P95=140KB, Max=946KB per optimized file

### Pack Distribution: `tools/gif_pipeline/pack_builder.py`
- **Format**: tar.gz archives containing gifs/, thumbs/, manifest.json
- **Sizing**: 50MB per pack (~1,100 GIFs at avg 46KB)
- **Tiers**: core (top 2K most popular), category-based (per emotion), trending/viral
- **Hosting**: GitHub Releases (max 2GB per asset, no LFS needed)
- **Index**: pack_index.json for app discovery of available packs

---

## Architecture

### File Structure (New Files Only)
```
src/main/kotlin/tribixbite/cleverkeys/gif/
├── Gif.kt                    # Data model (parallel to Emoji.kt)
├── GifCategory.kt            # Category enum with emotion types
├── GifDatabase.kt            # SQLite + FTS5 database handler
├── GifDatabaseHelper.kt      # SQLiteOpenHelper implementation
├── GifGridView.kt            # GridView adapter (parallel to EmojiGridView.kt)
├── GifGroupButtonsBar.kt     # Category buttons (parallel to EmojiGroupButtonsBar.kt)
├── GifSearchManager.kt       # Search coordination
├── GifThumbnailLoader.kt     # Async image loading with caching
├── GifPreviewDialog.kt       # Full-size animated preview
└── GifInsertHelper.kt        # Insert GIF into input field

src/main/assets/
├── gifs.db                   # Pre-built SQLite database (~5MB)
└── gifs/                     # Directory of GIF files
    ├── thumbs/               # Static WebP thumbnails (50KB each avg)
    │   ├── 00001.webp
    │   └── ...
    └── full/                 # Animated WebP files (200KB each avg)
        ├── 00001.webp
        └── ...

res/layout/
└── gif_panel.xml             # GIF panel layout

res/values/
└── gif_categories.xml        # Category names and icons

tools/gif_pipeline/           # Python data processing
├── requirements.txt
├── download_tgif.py          # Download from Hugging Face
├── process_gifs.py           # Convert, resize, generate thumbs
├── build_database.py         # Create SQLite + FTS5 database
└── validate_dataset.py       # Verify integrity
```

### Database Schema

```sql
-- Categories table (17 emotion categories)
CREATE TABLE categories (
    category_id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    icon TEXT NOT NULL,           -- Category icon emoji
    sort_order INTEGER NOT NULL
);

-- Main GIF metadata table
CREATE TABLE gifs (
    gif_id INTEGER PRIMARY KEY,
    asset_name TEXT NOT NULL UNIQUE,      -- e.g., '00001.webp'
    thumbnail_name TEXT NOT NULL UNIQUE,  -- e.g., '00001_thumb.webp'
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    duration_ms INTEGER DEFAULT 0,        -- Animation duration
    file_size INTEGER DEFAULT 0,          -- For storage stats
    created_at INTEGER DEFAULT 0          -- Timestamp
);

-- Many-to-many: GIF to categories
CREATE TABLE gif_category_map (
    gif_id INTEGER NOT NULL,
    category_id INTEGER NOT NULL,
    PRIMARY KEY (gif_id, category_id),
    FOREIGN KEY (gif_id) REFERENCES gifs(gif_id) ON DELETE CASCADE,
    FOREIGN KEY (category_id) REFERENCES categories(category_id) ON DELETE CASCADE
);

-- FTS5 virtual table for search
CREATE VIRTUAL TABLE gifs_fts USING fts5(
    gif_id UNINDEXED,
    search_text,
    content='gifs',
    content_rowid='gif_id'
);

-- Usage tracking (recently used)
CREATE TABLE gif_usage (
    gif_id INTEGER PRIMARY KEY,
    use_count INTEGER DEFAULT 0,
    last_used INTEGER DEFAULT 0,
    FOREIGN KEY (gif_id) REFERENCES gifs(gif_id) ON DELETE CASCADE
);

-- Indexes for performance
CREATE INDEX idx_gif_category ON gif_category_map(category_id);
CREATE INDEX idx_gif_usage ON gif_usage(last_used DESC);
```

### Emotion Categories (from GIFGIF+ research)

| ID | Name | Icon | Example Tags |
|----|------|------|--------------|
| 0 | Recently Used | 🕐 | (dynamic) |
| 1 | Amusement | 😂 | laughing, happy, funny, lol |
| 2 | Anger | 😠 | angry, frustrated, rage, mad |
| 3 | Contempt | 😏 | eye roll, unimpressed, smug |
| 4 | Contentment | 😌 | satisfied, peaceful, relaxed |
| 5 | Disgust | 🤢 | gross, ew, disgusted |
| 6 | Embarrassment | 😳 | facepalm, awkward, cringe |
| 7 | Excitement | 🤩 | excited, yay, celebration |
| 8 | Fear | 😨 | scared, terrified, horror |
| 9 | Guilt | 😔 | sorry, regret, apologize |
| 10 | Happiness | 😊 | happy, smile, joy |
| 11 | Love | 😍 | heart, love, romantic |
| 12 | Pride | 😤 | proud, victory, flex |
| 13 | Relief | 😅 | phew, whew, relieved |
| 14 | Sadness | 😢 | sad, crying, tears |
| 15 | Shame | 😞 | ashamed, hide, disappointed |
| 16 | Surprise | 😲 | shocked, omg, surprised |
| 17 | Approval | 👍 | yes, agree, thumbs up |

---

## Storage Estimates (Actual Measured)

### Per-GIF Storage (optimized via squoosh q12 m6 sharp_yuv)
- **Optimized animated WebP**: avg 46KB (P50=36KB, P95=140KB, max=946KB)
- **Thumbnail** (80px, static WebP): avg ~2KB
- **Average per GIF**: ~48KB total

### Total Storage (86K optimized GIFs)
- **Optimized WebPs**: ~3.8 GB
- **Thumbnails**: ~170 MB
- **Database**: ~7 MB (FTS5)
- **Total**: ~4.0 GB

### Distribution: Downloadable Packs via GitHub Releases
1. **Core Pack** (recommended first install): top 2,000 GIFs (~50MB, 1 pack)
2. **Category Packs**: per-emotion (happy, funny, love, etc.), ~50MB each
3. **Full Collection**: ~78 packs at 50MB each for all 86K GIFs
4. No APK bundling — all packs downloaded on demand

---

## Implementation Plan

### Phase 1: Data Pipeline [COMPLETE]
1. [x] Giphy API download pipeline (118K GIFs)
2. [x] WebP conversion (ffmpeg libwebp)
3. [x] Squoosh optimization (q12 m6 sharp_yuv, 86K optimized)
4. [x] SQLite FTS5 database builder
5. [x] Pack builder (tar.gz with manifest.json)
6. [x] Kotlin data models (Gif, GifCategory, GifDatabase)
7. [x] Asset/pack managers (GifAssetManager, GifPackManager, GifGridView)

### Phase 2: Pack Generation & Hosting [NEXT]
1. [ ] Generate core pack (top 2K GIFs)
2. [ ] Generate category packs
3. [ ] Rebuild database from optimized/ with Giphy metadata
4. [ ] Upload packs to GitHub Releases
5. [ ] Host pack_index.json for app discovery

### Phase 3: UI Components
1. [ ] GIF panel XML layout (gif_panel.xml)
2. [ ] GIF tab in keyboard panel (SWITCH_GIF event)
3. [ ] Category browsing (GifGroupButtonsBar.kt)
4. [ ] Grid view with Coil image loading
5. [ ] Search bar with FTS5

### Phase 4: GIF Insertion & Integration
1. [ ] commitContent() API for GIF insertion
2. [ ] FileProvider for content URIs
3. [ ] Clipboard fallback for apps that don't support commitContent
4. [ ] Recently used tracking (gif_usage table)
5. [ ] Pack download UI in settings

---

## Python Pipeline Tools

### tools/gif_pipeline/
```
pipeline.py         — Continuous Giphy acquisition (download → convert → optimize → cleanup)
process_gifs.py     — GIF to animated WebP conversion via ffmpeg
build_database.py   — SQLite FTS5 database builder with keyword extraction
pack_builder.py     — Generate tar.gz GIF packs for GitHub releases
calc_packs.py       — Quick pack size estimation utility
```

### Pipeline flow
```
Giphy API → fixed_width WebP (200px) → squoosh (q12 m6 sharp_yuv) → optimized/
                                     ↓
                              raw GIF fallback → ffmpeg libwebp → full/ → squoosh → optimized/
```

### Squoosh optimization (squoosh-one.sh)
- Frame extraction: `webpmux -get frame`
- Re-encode each frame: `cwebp -q <adaptive> -m 6 -sharp_yuv`
- Reassemble: `webpmux -frame ... -loop 0`
- Adaptive quality: q12 for <20 frames, q10 for 20-50, q8 for >50
- Skip files <30KB (copy as-is, re-encoding hurts quality)
- Keep-smaller: if output > input, keep input

### Requirements
```
Pillow>=10.0.0
requests>=2.28.0
python-dotenv>=1.0.0
tqdm>=4.0.0
```

---

## Testing Strategy

### Unit Tests
- Database queries and FTS5 search
- Category filtering
- Usage tracking

### Integration Tests
- GIF loading and display
- Thumbnail caching
- Search results accuracy

### Manual Tests
- Scroll performance with 100+ thumbnails
- Memory usage monitoring
- GIF insertion to various apps

---

## Open Questions

1. **Giphy License**: Giphy API TOS allows display in apps; redistribution as
   pre-packaged assets needs legal review for commercial use
2. **Content Moderation**: Pipeline uses `rating=pg-13` filter; may need
   additional NudeNet/CLIP pass for build-time content filtering
3. **Cold Start**: First pack download UX — show placeholder grid? Download progress?
4. **commitContent() Support**: Not all apps support it; need clipboard fallback path

---

## References

- [Giphy API Docs](https://developers.giphy.com/docs/api/)
- [Android commitContent() API](https://developer.android.com/reference/android/view/inputmethod/InputContentInfo)
- [Coil Image Loading](https://coil-kt.github.io/coil/)
- [Android FTS5 Documentation](https://www.sqlite.org/fts5.html)
- [Animated WebP Support](https://developers.google.com/speed/webp)
- [libwebp Tools](https://developers.google.com/speed/webp/docs/using)
