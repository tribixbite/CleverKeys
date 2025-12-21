# Per-Layout Subkey Customization Spec

## Problem Statement

Currently, subkey customizations (short swipe mappings) are stored globally and apply to ALL layouts. Users may want different subkey configurations for different layouts (e.g., programming symbols on QWERTY, math symbols on Greek).

## Current Implementation

**Storage**: Single JSON file `short_swipe_customizations.json`
**Key format**: `"keyCode:direction"` (e.g., `"a:NW"`)
**Scope**: Global - same mappings apply to all layouts

```kotlin
// Current: ShortSwipeCustomizationManager.kt
private val mappingCache = ConcurrentHashMap<String, ShortSwipeMapping>()
// Key: "keyCode:direction" -> applies to all layouts
```

## Proposed Architecture

### Option A: Layout-Prefixed Storage (Recommended)

Change storage key to include layout identifier:
```
"layoutId:keyCode:direction" -> ShortSwipeMapping
```

**Pros**:
- Single file, simple migration
- O(1) lookup with composite key
- Easy to query all mappings for a layout

**Cons**:
- Larger file size with many layouts
- Need stable layout identifiers

### Option B: Separate Files Per Layout

Store each layout's customizations in separate files:
```
short_swipe_customizations_qwerty_us.json
short_swipe_customizations_system.json
short_swipe_customizations_custom_1.json
```

**Pros**:
- Clean separation
- Easy to backup/restore individual layouts

**Cons**:
- Multiple file I/O operations
- Complexity managing file names

### Option C: Nested JSON Structure

```json
{
  "version": 2,
  "layouts": {
    "qwerty_us": {
      "mappings": [...]
    },
    "system": {
      "mappings": [...]
    }
  },
  "global": {
    "mappings": [...] // Fallback for unmapped layouts
  }
}
```

**Pros**:
- Single file
- Supports global fallback
- Clear structure

**Cons**:
- More complex parsing
- Need migration from v1

## Layout Identification

### Challenge: SystemLayout

SystemLayout is dynamic (resolved at runtime based on locale). Need stable identifier.

### Proposed Layout ID Scheme

| Layout Type | Identifier Format | Example |
|-------------|-------------------|---------|
| NamedLayout | `"named:{name}"` | `"named:latn_qwerty_us"` |
| SystemLayout | `"system"` | `"system"` |
| CustomLayout | `"custom:{hash}"` | `"custom:a1b2c3"` (first 6 chars of XML hash) |

### CustomLayout Stability

CustomLayout XML can be edited. Options:
1. **Hash-based**: ID changes when XML changes (mappings lost)
2. **UUID-based**: Assign UUID on creation, persists through edits
3. **Index-based**: `"custom:0"`, `"custom:1"` (breaks on reorder)

**Recommendation**: UUID-based for CustomLayout stability.

## Data Model Changes

### ShortSwipeMapping (Updated)

```kotlin
data class ShortSwipeMapping(
    val layoutId: String?,     // NEW: null = global/all layouts
    val keyCode: String,
    val direction: SwipeDirection,
    val actionType: ActionType,
    val actionValue: String,
    val displayLabel: String?
) {
    fun toStorageKey(): String {
        val prefix = layoutId ?: "global"
        return "$prefix:${keyCode.lowercase()}:${direction.name}"
    }
}
```

### ShortSwipeCustomizations (Updated)

```kotlin
data class ShortSwipeCustomizations(
    val version: Int = 2,
    val perLayoutMappings: Map<String, List<ShortSwipeMappingData>>,
    val globalMappings: List<ShortSwipeMappingData>  // Fallback
)
```

## API Changes

### ShortSwipeCustomizationManager

```kotlin
// New: Layout-aware getters
fun getMapping(layoutId: String?, keyCode: String, direction: SwipeDirection): ShortSwipeMapping?
fun getMappingsForLayout(layoutId: String): List<ShortSwipeMapping>

// New: Layout-aware setters
suspend fun setMapping(layoutId: String?, mapping: ShortSwipeMapping)
suspend fun removeMappingsForLayout(layoutId: String)

// New: Copy between layouts
suspend fun copyMappings(fromLayoutId: String, toLayoutId: String)
```

### Lookup Priority

1. Check per-layout mapping first: `"layoutId:keyCode:direction"`
2. Fall back to global mapping: `"global:keyCode:direction"`
3. Use default from layout XML

## UI Changes

### Customization Activity

1. Add layout selector dropdown at top of screen
2. Show "All Layouts" option for global mappings
3. Show current layout's key with visual indicator of:
   - Per-layout custom (bold)
   - Global custom (normal)
   - Default (dimmed)

### Layout Indicator States

```
[A] - Layout-specific custom mapping
[G] - Global custom mapping
[ ] - Default (no customization)
```

## Migration Strategy

### v1 -> v2 Migration

When loading v1 format:
1. Detect version (missing `version` field = v1)
2. Import all v1 mappings as `global` mappings
3. Save in v2 format

```kotlin
suspend fun migrateFromV1(v1Data: ShortSwipeCustomizationsV1): ShortSwipeCustomizations {
    return ShortSwipeCustomizations(
        version = 2,
        perLayoutMappings = emptyMap(),
        globalMappings = v1Data.mappings
    )
}
```

## Implementation Phases

### Phase 1: Backend (Non-Breaking)
- [ ] Add `layoutId` field to ShortSwipeMapping (nullable, default null)
- [ ] Update storage format to v2 with migration
- [ ] Add layout-aware lookup with global fallback
- [ ] Add `getLayoutId()` to LayoutsPreference.Layout types

### Phase 2: UI Integration
- [ ] Add layout selector to ShortSwipeCustomizationActivity
- [ ] Show layout indicator on customized keys
- [ ] Add "Copy from layout" option

### Phase 3: Polish
- [ ] Add "Apply to all layouts" bulk action
- [ ] Export/import per-layout or global
- [ ] Settings to disable per-layout (use global only)

## Open Questions

1. **Default behavior for new layouts**: Inherit from global, or start empty?
2. **Layout deletion**: What happens to per-layout customizations when layout removed?
3. **SystemLayout switching**: If user changes phone locale, does "system" mapping persist?

## Related Files

- `ShortSwipeCustomizationManager.kt` - Core storage/retrieval
- `ShortSwipeCustomizationActivity.kt` - UI
- `LayoutsPreference.kt` - Layout model with ID generation
- `Config.kt` - Layout list access

---
*Created: 2024-12-21*
*Status: Draft*
