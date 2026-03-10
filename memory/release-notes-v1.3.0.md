# v1.3.0 Release Notes (DRAFT)

## GitHub Release Notes

### What's New in v1.3.0

#### GIF Panel (NEW)
- Offline GIF keyboard panel with search, categories, and grid browser
- Import GIF packs via file picker — no internet permission required
- Tap to insert Giphy URL, long-press for copy/share menu
- Category browsing with group buttons bar
- GIF search with FTS4 full-text index (compound word fallback)
- Paginated grid (100 items/page) for smooth scrolling
- Per-pack install/remove + "Remove All" in Settings
- Share intent receiver: share a .zip to CleverKeys to import

**Get GIF Packs:** [CleverKeys-GIF Releases](https://github.com/tribixbite/CleverKeys/releases/tag/CleverKeys-GIF)
- 6 Discord Community packs (5,232 full animated GIFs, 575 MB)
- 7 Category thumbnail packs + 1 all-in-one + 1 popular-25k (130K GIFs)
- 6 Sequential thumbnail packs (legacy, same 130K by ID range)
- Base database: gifs_v2.db.gz (metadata index, no images)

#### New Features
- **Auto Space Before Suggestion** (#82): Optional space auto-inserted before swipe predictions
- **Backspace Undo Swipe** (#110): Single backspace deletes entire swiped word + trailing auto-space
- **Paste Pinned Commands**: paste_pinned_1 through paste_pinned_5 — insert pinned clipboard entries via short swipe
- **Intent Action Type**: Launch apps, services, or broadcasts from short swipe gestures
- **Clipboard Pagination**: 100 items per page with page navigation (matches GIF panel)
- **Clipboard Long-Press to Copy** (#107): Long-press any clipboard entry to copy it; copied entry moves to top
- **Clipboard Search Persistence**: Search text preserved across tab switches; clear (X) button added
- **Misspelling Detection Pipeline**: Dictionary QA tool for vocabulary analysis

#### Fixes
- **Terminal Paste** (#113): Short swipe paste and editing key paste now work in Termux, ConnectBot, and other terminal emulators (uses clipboard-read + commitText instead of Ctrl+V)
- **Custom Theme Background** (#92): Custom theme background color now applies to keyboard view
- **Compose Key Arrow** (#104): Arrow short swipe on disabled compose key no longer crashes
- **Autofill Display** (#109): Suggestion bar autofill no longer cuts off on password fields
- **Clipboard Dedup Reorder** (#108): Duplicate clipboard entries move to top instead of staying stale
- **Clipboard Ordering**: Pinned and todo items sorted oldest-first (ASC) for stable ordering
- **Settings Search Crash**: MonotonicFrameClock crash on settings search scroll fixed
- **Language Toggle Crash**: Catch Throwable in language toggle cascade prevents IME crash
- **Contraction Swipe Ranking**: Contractions (don't, can't, won't) now score competitively with common words
- **Contraction Autocorrect**: Real words (its, well, were, hell) no longer auto-replaced by contractions
- **Disabled Word Visibility**: Disabled words correctly show as disabled in Dictionary Manager
- **Dictionary Cache**: In-place mutation avoids reloading 50K-word cache on toggle
- **Profile Export** (#70): Writes to Downloads/CleverKeys/ via MediaStore fallback
- **Custom Text Input**: Paste button added; 100-char limit removed

#### Testing
- 958 JVM tests + 860+ instrumented tests (emulator.wtf, Pixel 7 API 34)
- 66 swipe layout support tests (#9), 20 pagination tests, 3 backspace undo tests (#110)
- 62 end-to-end typing simulation tests
- 43 GIF module tests, 32 category tests, 15 autofill tests
- 19 dictionary data source tests, 12 vocabulary ranking tests
- 14 crash guard tests (ConfigPropagator/ConfigurationManager resilience)
- 21 instrumented layout support tests, 14 instrumented backspace undo tests
- 7 clipboard dedup/ordering tests (#108, pinned/todo ASC)

---

## Build Information

| Property | Value |
|----------|-------|
| Version | 1.3.0 |
| VersionCode | 10300 |
| Package | tribixbite.cleverkeys |
| Min Android | 5.0 (API 21) |
| Target Android | 14 (API 34) |

## Download

| Source | Status |
|--------|--------|
| **GitHub** | Download APK below for your device |
| **F-Droid** | Auto-updates within 24-48 hours |

## APK Variants

| ABI | Devices | VersionCode |
|-----|---------|-------------|
| **arm64** | Most modern phones (2016+) | 103002 |
| **armv7** | Older 32-bit devices | 103001 |
| **x86_64** | Emulators, Chromebooks | 103003 |

---

## F-Droid Changelog (497 chars)

```
v1.3.0 — GIF Panel + Terminal Paste Fix

NEW: Offline GIF panel with search, categories, pagination. Import packs via file picker (no internet). Get packs: github.com/tribixbite/CleverKeys/releases/tag/CleverKeys-GIF

NEW: Auto space before suggestion, backspace undo swipe, paste pinned commands, intent actions, clipboard long-press copy + pagination

FIX: Paste in Termux/terminals, custom theme bg, compose key arrow, autofill cutoff, contraction ranking, clipboard dedup reorder, settings crash
```
