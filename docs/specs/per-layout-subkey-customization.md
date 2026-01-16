# Per-Layout Subkey Customization

## Overview

Proposed extension to store short swipe customizations per-layout instead of globally. Currently all customizations apply to all layouts; this architecture would allow different subkey configurations for different layouts (e.g., programming symbols on QWERTY, math symbols on Greek).

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/customization/ShortSwipeCustomizationManager.kt` | Storage/retrieval | JSON persistence |
| `src/main/kotlin/tribixbite/cleverkeys/customization/ShortSwipeMapping.kt` | `layoutId` field | Data model |
| `src/main/kotlin/tribixbite/cleverkeys/prefs/LayoutsPreference.kt` | `getLayoutId()` | Layout identification |
| `src/main/kotlin/tribixbite/cleverkeys/ui/customization/ShortSwipeCustomizationActivity.kt` | Layout selector | UI |

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

### Storage Key Format

Change from global to layout-prefixed:
```
Current: "keyCode:direction"      → ShortSwipeMapping
Proposed: "layoutId:keyCode:direction" → ShortSwipeMapping
```

### Layout Identification Scheme

| Layout Type | Identifier Format | Example |
|-------------|-------------------|---------|
| NamedLayout | `"named:{name}"` | `"named:latn_qwerty_us"` |
| SystemLayout | `"system"` | `"system"` |
| CustomLayout | `"custom:{uuid}"` | `"custom:a1b2c3d4"` |

### Data Model Changes

```kotlin
data class ShortSwipeMapping(
    val layoutId: String?,     // null = global/all layouts
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

### V2 JSON Structure

```json
{
  "version": 2,
  "layouts": {
    "named:latn_qwerty_us": {
      "mappings": [...]
    },
    "system": {
      "mappings": [...]
    }
  },
  "global": {
    "mappings": [...]
  }
}
```

## API Changes

```kotlin
// Layout-aware getters
fun getMapping(layoutId: String?, keyCode: String, direction: SwipeDirection): ShortSwipeMapping?
fun getMappingsForLayout(layoutId: String): List<ShortSwipeMapping>

// Layout-aware setters
suspend fun setMapping(layoutId: String?, mapping: ShortSwipeMapping)
suspend fun removeMappingsForLayout(layoutId: String)

// Copy between layouts
suspend fun copyMappings(fromLayoutId: String, toLayoutId: String)
```

## Lookup Priority

1. Check per-layout mapping: `"layoutId:keyCode:direction"`
2. Fall back to global mapping: `"global:keyCode:direction"`
3. Use default from layout XML

## UI Changes

### Customization Activity

1. Add layout selector dropdown at top
2. Show "All Layouts" option for global mappings
3. Visual indicators:
   - `[L]` - Layout-specific custom mapping (bold)
   - `[G]` - Global custom mapping (normal)
   - `[ ]` - Default (dimmed)

## Migration

### v1 → v2 Migration

```kotlin
suspend fun migrateFromV1(v1Data: ShortSwipeCustomizationsV1): ShortSwipeCustomizations {
    return ShortSwipeCustomizations(
        version = 2,
        perLayoutMappings = emptyMap(),
        globalMappings = v1Data.mappings  // Existing become global
    )
}
```

## CustomLayout Identity

CustomLayouts are identified by UUID assigned on creation, persisting through edits:

```kotlin
data class CustomLayout(
    val uuid: String = UUID.randomUUID().toString(),
    val xml: String,
    val name: String
)
```
