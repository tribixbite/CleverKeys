---
title: Adding Layouts - Technical Specification
user_guide: ../../layouts/adding-layouts.md
status: implemented
version: v1.2.7
---

# Adding Layouts Technical Specification

## Overview

The layout management system handles installing, configuring, and organizing keyboard layouts from built-in sources, language packs, and custom definitions.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| LayoutManager | `LayoutManager.kt` | Layout lifecycle management |
| LayoutRegistry | `LayoutRegistry.kt` | Available layouts catalog |
| LayoutStore | `LayoutStore.kt` | Persistence layer |
| LayoutParser | `LayoutParser.kt` | XML/JSON parsing |
| Config | `Config.kt` | Layout preferences |

## Data Model

### Layout Definition

```kotlin
// LayoutManager.kt
data class Layout(
    val id: String,                    // Unique identifier
    val name: String,                  // Display name
    val localeTag: String,             // Language code (en-US)
    val source: LayoutSource,          // Built-in, pack, custom
    val rows: List<LayoutRow>,         // Key rows
    val metadata: LayoutMetadata       // Additional info
)

data class LayoutRow(
    val keys: List<LayoutKey>,
    val height: Float = 1.0f           // Row height multiplier
)

data class LayoutKey(
    val primary: KeyValue,             // Main character/action
    val shifted: KeyValue?,            // Shift variant
    val subkeys: Map<Direction, KeyValue>, // 8-direction subkeys
    val width: Float = 1.0f            // Key width multiplier
)

enum class LayoutSource {
    BUILT_IN,      // Shipped with app
    LANGUAGE_PACK, // Downloaded pack
    CUSTOM         // User created
}
```

### Layout Metadata

```kotlin
// LayoutRegistry.kt
data class LayoutMetadata(
    val version: Int,
    val author: String?,
    val description: String?,
    val iconRes: Int?,
    val isRTL: Boolean = false,
    val supportedLanguages: List<String>
)
```

## Layout Storage

### Built-in Layouts

```kotlin
// LayoutRegistry.kt
object BuiltInLayouts {
    val QWERTY = Layout(
        id = "qwerty_en",
        name = "QWERTY",
        localeTag = "en-US",
        source = LayoutSource.BUILT_IN,
        rows = listOf(
            LayoutRow(listOf(
                LayoutKey(KeyValue.Char('q'), KeyValue.Char('Q')),
                LayoutKey(KeyValue.Char('w'), KeyValue.Char('W')),
                // ...
            )),
            // ... more rows
        )
    )

    val all = listOf(QWERTY, AZERTY, QWERTZ, DVORAK, COLEMAK)
}
```

### Installed Layouts Config

```kotlin
// Config.kt
// Stored as JSON array of layout IDs in order
{
    "installed_layouts": [
        "qwerty_en",
        "azerty_fr",
        "custom_123"
    ],
    "active_layouts": [  // Included in quick switch
        "qwerty_en",
        "azerty_fr"
    ]
}
```

## Layout Installation Flow

```
User selects layout to add
        ↓
LayoutRegistry.getLayout(id)
        ↓
Validate layout definition
        ↓
LayoutStore.addLayout(layout)
        ↓
Update Config.installed_layouts
        ↓
Notify listeners (keyboard reloads)
        ↓
Layout available in picker
```

## Layout Parsing

### XML Format (Legacy)

```xml
<!-- res/raw/layout_qwerty.xml -->
<layout id="qwerty_en" name="QWERTY" locale="en-US">
    <row>
        <key char="q" shifted="Q">
            <subkey dir="NW" char="1"/>
            <subkey dir="N" char="!"/>
        </key>
        <!-- more keys -->
    </row>
    <!-- more rows -->
</layout>
```

### JSON Format (Preferred)

```json
{
    "id": "qwerty_en",
    "name": "QWERTY",
    "locale": "en-US",
    "rows": [
        {
            "keys": [
                {
                    "primary": "q",
                    "shifted": "Q",
                    "subkeys": {"NW": "1", "N": "!"}
                }
            ]
        }
    ]
}
```

## Layout Validation

```kotlin
// LayoutManager.kt
fun validateLayout(layout: Layout): ValidationResult {
    val errors = mutableListOf<String>()

    // Check required fields
    if (layout.id.isBlank()) errors.add("Missing layout ID")
    if (layout.rows.isEmpty()) errors.add("Layout must have at least one row")

    // Check row consistency
    layout.rows.forEach { row ->
        if (row.keys.isEmpty()) errors.add("Row cannot be empty")
    }

    // Check for space key
    val hasSpace = layout.rows.any { row ->
        row.keys.any { it.primary == KeyValue.Event(Event.SPACE) }
    }
    if (!hasSpace) errors.add("Layout must have spacebar")

    return ValidationResult(errors.isEmpty(), errors)
}
```

## Configuration

| Setting | Key | Default | Description |
|---------|-----|---------|-------------|
| **Installed Layouts** | `installed_layouts` | ["qwerty_en"] | List of layout IDs |
| **Active Layouts** | `active_layouts` | ["qwerty_en"] | Quick switch layouts |
| **Max Layouts** | - | 10 | Hard limit |
| **Current Layout** | `current_layout` | First installed | Active layout |

## Related Specifications

- [Layout System](../../../specs/layout-system.md) - Full layout architecture
- [Switching Layouts](switching-layouts-spec.md) - Layout switching logic
- [Profile System](../../../specs/profile_system_restoration.md) - Layout import/export
