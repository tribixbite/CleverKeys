# Archive Directory

This directory contains archived files from the CleverKeys development history.

**Last Updated**: 2025-12-04

---

## Directory Structure

```
archive/
├── ARCHIVED_FEATURES.md    # Index of archived Kotlin features (120 files)
├── README.md               # This file
├── cleverkeys-kt/          # Archived Kotlin source files (120 files)
│   ├── AutoCorrection.kt
│   ├── AccessibilityHelper.kt
│   └── ... (see ARCHIVED_FEATURES.md for full list)
└── md-cleanup/             # Historical markdown documents (185 files)
    ├── SESSION_*.md        # Old session logs
    ├── BUG_*.md            # Bug investigation reports
    └── *_STATUS.md         # Historical status reports
```

---

## Archive Contents

### cleverkeys-kt/ (120 files)
Kotlin source files archived during the Unexpected-Keyboard migration (2025-11-28).
These were part of the original CleverKeys codebase before the UK foundation was adopted.

**Categories**:
- Auto-correction & prediction engines
- UI enhancement managers
- Accessibility helpers
- Advanced keyboard modes (floating, one-handed, split)
- Neural/ML components
- Language support utilities

**Status**: May be reviewed for selective reintegration once core keyboard is stable.

### md-cleanup/ (185 files)
Historical markdown files from development sessions. These were moved from the
project root to clean up the repository structure.

**Includes**:
- Session logs (SESSION_2025-*.md)
- Bug investigation reports (BUG_*.md)
- Status/completion reports (*_STATUS.md, *_COMPLETE.md)
- Historical roadmaps and analyses

**Status**: Reference only. Current documentation is in `docs/` directory.

---

## Notes

1. **Do not delete without review** - Some archived code may be useful for future features
2. **Current active docs** - Use `docs/` for current documentation
3. **Specs** - Current specifications are in `docs/specs/`
4. **History** - Recent history is in `docs/history/`
5. **Working TODOs** - Current session tasks are in `memory/todo.md`

---

## File Count Summary

| Directory | Files | Description |
|-----------|-------|-------------|
| cleverkeys-kt/ | 120 | Archived Kotlin source |
| md-cleanup/ | 185 | Historical markdown |
| **Total** | **305** | Archived files |
