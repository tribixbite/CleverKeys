# Documentation Audit

Audit of docs/specs vs docs/wiki structure for consolidation.

---

## Structure Overview

| Location | Count | Purpose |
|----------|-------|---------|
| `docs/specs/` | 27 | Top-level architectural/implementation specs |
| `docs/wiki/` | 32 | User-facing guides |
| `docs/wiki/specs/` | 25 | Wiki-paired technical specs |

---

## Top-Level Specs Classification

### Keep in docs/specs/ (Pure Architecture)
These are internal implementation details, not user-facing:

| Spec | Purpose |
|------|---------|
| `architectural-decisions.md` | ADRs for design choices |
| `core-keyboard-system.md` | Core keyboard architecture |
| `neural-prediction.md` | ONNX neural pipeline |
| `neural-multilanguage-architecture.md` | Multilang beam search |
| `kv-cache-optimization.md` | Decoder caching |
| `memory-pool-optimization.md` | Memory management |
| `performance-optimization.md` | Latency optimization |
| `dictionary-and-language-system.md` | Dictionary internals |
| `secondary-language-integration.md` | Bilingual integration |
| `settings-system.md` | Config architecture |
| `settings-layout-integration.md` | Settings ↔ layout sync |

### Have Wiki Pairs - Consider Consolidating
These exist in both places - check for redundancy:

| Top-Level Spec | Wiki Guide | Wiki Spec |
|----------------|------------|-----------|
| `selection-delete-mode.md` | `gestures/selection-delete.md` | `specs/gestures/selection-delete-spec.md` |
| `trackpoint-navigation-mode.md` | `gestures/trackpoint-mode.md` | `specs/gestures/trackpoint-mode-spec.md` |
| `cursor-navigation-system.md` | `gestures/cursor-navigation.md` | `specs/gestures/cursor-navigation-spec.md` |
| `short-swipe-customization.md` | `gestures/short-swipes.md` | `specs/gestures/short-swipes-spec.md` |
| `gesture-system.md` | Multiple gesture guides | `specs/gestures/*-spec.md` |
| `layout-system.md` | `layouts/*.md` | `specs/layouts/*-spec.md` |
| `clipboard-privacy.md` | `clipboard/clipboard-history.md` | `specs/clipboard/clipboard-history-spec.md` |
| `timestamp-keys.md` | `customization/timestamp-keys.md` | *(no spec pair)* |
| `password-field-mode.md` | *(no guide)* | *(no spec)* |

### Need Wiki Pairs Created
User-facing features without wiki guides:

| Spec | Suggested Wiki Location |
|------|------------------------|
| `password-field-mode.md` | `settings/privacy.md` or new `security/password-fields.md` |
| `quick-settings-tile.md` | `getting-started/quick-settings.md` |
| `termux_integration.md` | `getting-started/termux.md` |
| `cursor-aware-predictions.md` | Merge into `typing/autocorrect.md` |
| `language-specific-dictionary-manager.md` | Merge into `layouts/multi-language.md` |

### Declutter - Move to History
These contain session history/changelogs:

| Spec | Action |
|------|--------|
| `profile_system_restoration.md` | Extract history to `docs/history/` |
| `subkey-customization-current.md` | Merge with `per-layout-subkey-customization.md` |
| `per-layout-subkey-customization.md` | Consolidate with above |

---

## Recommended Actions

### Phase 1: Declutter (Now)
1. Move changelog sections from specs to `docs/history/`
2. Merge `subkey-customization-current.md` → `per-layout-subkey-customization.md`

### Phase 2: Consolidate (Soon)
1. For each duplicate (top-level + wiki pair), decide:
   - If top-level has more detail → Move to wiki/specs, delete top-level
   - If wiki is complete → Delete top-level
2. Create missing wiki guides for `password-field-mode.md`, `quick-settings-tile.md`

### Phase 3: Standardize (Later)
1. All user features: `docs/wiki/{category}/{feature}.md` + `docs/wiki/specs/{category}/{feature}-spec.md`
2. Architecture only: `docs/specs/{topic}.md`

---

## Wiki Structure Standard

### User Guide (docs/wiki/{category}/{feature}.md)
- What it is
- How to enable/use
- Examples
- Tips

### Technical Spec (docs/wiki/specs/{category}/{feature}-spec.md)
- Implementation details
- Key files
- Settings keys
- Edge cases

### Architecture Doc (docs/specs/{topic}.md)
- Design decisions (ADRs)
- Component interactions
- Performance considerations
- NOT user-facing

---

*Generated: 2026-01-18*
