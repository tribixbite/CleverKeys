# CleverKeys Roadmap

## Current Version

**v1.2.11** (2026-01-18) - Production Ready

---

## Version History

### v1.2.x Series (2026-01)
| Version | Date | Highlights |
|---------|------|------------|
| v1.2.11 | 2026-01-18 | Settings search expansion (80% coverage), I-word capitalization |
| v1.2.10 | 2026-01-17 | Clipboard TransactionTooLargeException fix, proper noun case |
| v1.2.9 | 2026-01-16 | Emoji search, password manager exclusion, timestamp keys |
| v1.2.8 | 2026-01-15 | TrackPoint mode, granular haptics, cursor-aware predictions |
| v1.2.7 | 2026-01-14 | Selection-delete mode bidirectional, vertical threshold tuning |

### v1.2.0-1.2.6 (2025-12 - 2026-01)
- Language toggle commands (primary/secondary quick switch)
- Text menu command, contractions after language toggle fix
- Profile system restoration with layout import/export
- Short swipe customization per-key

### v1.1.x Series (2025-12)
| Version | Date | Highlights |
|---------|------|------------|
| v1.1.99 | 2025-12-23 | Text processing actions fix (ACTION_PROCESS_TEXT) |
| v1.1.97 | 2025-12-21 | Custom short swipe commands, icon preview fix |
| v1.1.94-96 | 2025-12-20 | Secondary language touch/swipe, OOM fix for large lang packs |
| v1.1.90-93 | 2025-12-19 | Touch typing locale filter, V2 dictionary format |
| v1.1.85-89 | 2025-12-18 | Multilanguage full support, language-specific beam tries |

### v1.0.x Series (2025-12)
| Version | Date | Highlights |
|---------|------|------------|
| v1.0.6 | 2025-12-14 | F-Droid metadata fixes |
| v1.0.5 | 2025-12-13 | Proguard/R8 fixes for ONNX |
| v1.0.3-4 | 2025-12-13 | App name fix, version mismatch fix |
| v1.0.0 | 2025-12-11 | First F-Droid release |

---

## F-Droid Status

- **MR #30449**: Merged (2025-12-21)
- **Auto-update**: Enabled via tag detection
- **Current F-Droid version**: v1.2.x

---

## Outstanding Work

### High Priority
- [ ] English words in French-only mode investigation (diagnostic logging in place)
- [ ] Long word prediction testing (length normalization fix applied)

### Medium Priority
- [ ] Multi-language Phase 8.2: Language-specific dictionaries
- [ ] Error Reports toggle implementation
- [ ] Secondary dictionary loading from service

### Low Priority
- [ ] Settings UI polish (swipe sensitivity presets)
- [ ] Web demo P2 (lazy loading, PWA)
- [ ] Legacy code migration (deprecated Activities, preferences)

---

## Proposed Next Steps

### v1.2.12 (Next Release)
1. Verify and close French-only mode investigation
2. Complete long word prediction testing
3. Settings search coverage to 100%

### v1.3.0 (Feature Release)
1. Language-specific dictionaries (Phase 8.2)
2. Swipe sensitivity presets (Low/Medium/High)
3. Error reporting implementation

### v1.4.0 (Web Demo)
1. Model lazy loading
2. PWA/Service Worker for offline
3. Web demo feature parity check

---

## Architecture Documentation

| Spec | Path | Status |
|------|------|--------|
| Neural Prediction | docs/specs/neural-prediction.md | Complete |
| Gesture System | docs/specs/gesture-system.md | Complete |
| TrackPoint Mode | docs/specs/trackpoint-navigation-mode.md | Complete |
| Selection-Delete | docs/specs/selection-delete-mode.md | Complete |
| Short Swipe | docs/specs/short-swipe-customization.md | Complete |
| Dictionary System | docs/specs/dictionary-and-language-system.md | Complete |
| Settings System | docs/specs/settings-system.md | Complete |
| Profile System | docs/specs/profile_system_restoration.md | Complete |

---

## Metrics

- **Settings Coverage**: ~80% (99 of ~125 searchable)
- **Wiki Pages**: 36 user guides + 4 tech specs
- **Spec Documents**: 20+
- **Code TODOs**: 6 active

---

*Last updated: 2026-01-18*
