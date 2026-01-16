# Layout System

## Overview

The layout system manages dynamic key injection through ExtraKeys, position hints, and script-aware customization. It enables users to add keys (F-keys, accents, symbols) to any layout with intelligent placement based on script compatibility and position preferences.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/ExtraKeys.kt` | `ExtraKeys`, `ExtraKey` | Extra key definitions and computation |
| `src/main/kotlin/tribixbite/cleverkeys/KeyboardData.kt` | `PreferredPos`, `addExtraKeys()` | Layout data, position hints |
| `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | `extra_keys_param` | User preferences for extra keys |
| `res/xml/*.xml` | Layout definitions | Base keyboard layouts |

## Architecture

```
User Preferences (strings)
       ↓ (parse)
ExtraKeys.parse("f1|accent@e")
       ↓
ExtraKeys(List<ExtraKey>)
       ↓ (compute with Query)
Map<KeyValue, PreferredPos>
       ↓
KeyboardData.addExtraKeys()
       ↓
Final Layout with injected keys
```

## Configuration

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `extra_keys_custom` | String | "" | User-defined extra keys (pipe-separated) |

## Implementation Details

### ExtraKey Data Structure

```kotlin
data class ExtraKey(
    val kv: KeyValue,              // Key to add
    val script: String?,           // Script constraint (null = any)
    val alternatives: List<KeyValue>, // Don't add if these present
    val nextTo: KeyValue?          // Position hint
)
```

### String Parsing Format

```
Format: "key:alt1:alt2@next_to|key2|key3:alt@pos"

Examples:
  "f11_placeholder"           → Simple key, no constraints
  "accent_aigu:´@e"          → Accent near 'e', alternative is ´
  "f1|f2|f3"                 → Multiple keys (pipe-separated)
```

### Parsing Algorithm

```kotlin
fun parse(script: String?, str: String): ExtraKeys {
    val keys = str.split('|').mapNotNull { part ->
        // Split by '@' for position hint
        val atSplit = part.split('@')
        val mainPart = atSplit[0]
        val nextTo = if (atSplit.size > 1) KeyValue.getKeyByName(atSplit[1]) else null

        // Split by ':' for key and alternatives
        val colonSplit = mainPart.split(':')
        val kv = KeyValue.getKeyByName(colonSplit[0]) ?: return@mapNotNull null
        val alternatives = colonSplit.drop(1).mapNotNull { KeyValue.getKeyByName(it) }

        ExtraKey(kv, script, alternatives, nextTo)
    }
    return ExtraKeys(keys)
}
```

### Compute Algorithm

The compute algorithm determines which keys to add based on query context:

```kotlin
data class Query(
    val script: String?,           // Current layout script
    val present: Set<KeyValue>     // Keys already on layout
)

fun ExtraKey.compute(dst: MutableMap<KeyValue, PreferredPos>, query: Query) {
    // Step 1: Alternative substitution
    // If only 1 alternative and main key not present → use alternative
    val useAlternative = (alternatives.size == 1 && !dst.containsKey(kv))

    // Step 2: Script compatibility
    // null script matches any; otherwise exact match required
    val scriptMatches = (query.script == null || script == null || query.script == script)

    // Step 3: Presence check
    // Add if no alternatives OR some alternative missing from layout
    val shouldAdd = (alternatives.isEmpty() || !query.present.containsAll(alternatives))

    if (scriptMatches && shouldAdd) {
        val keyToAdd = if (useAlternative) alternatives[0] else kv
        val pos = nextTo?.let { PreferredPos(it) } ?: PreferredPos.DEFAULT
        dst[keyToAdd] = pos
    }
}
```

### Compute Example

```
Given: ExtraKey(kv=accent_aigu, alts=[´], script=latin, nextTo=e)
Query: script=latin, present={a,b,c,e,f}  (note: ´ NOT present)

Step 1: Alternative substitution?
  alternatives.size == 1 && !dst.containsKey(accent_aigu)
  → true → keyToAdd = ´

Step 2: Script compatibility?
  "latin" == "latin" → true

Step 3: Presence check?
  !{a,b,c,e,f}.containsAll([´]) → true (´ is missing)

Result: Adds ´ key with hint to place near 'e'
```

### Merge Algorithm

Combines multiple ExtraKeys sources (user prefs + layout defaults + system):

```kotlin
fun ExtraKey.mergeWith(other: ExtraKey): ExtraKey {
    require(kv == other.kv)
    return ExtraKey(
        kv = kv,
        script = if (script == other.script) script else null,  // Generalize
        alternatives = (alternatives + other.alternatives).distinct(),
        nextTo = nextTo ?: other.nextTo  // Prefer non-null
    )
}

fun ExtraKeys.Companion.merge(keysList: List<ExtraKeys>): ExtraKeys {
    val mergedMap = mutableMapOf<KeyValue, ExtraKey>()
    for (ks in keysList) {
        for (key in ks.keys) {
            val existing = mergedMap[key.kv]
            mergedMap[key.kv] = existing?.let { key.mergeWith(it) } ?: key
        }
    }
    return ExtraKeys(mergedMap.values.toList())
}
```

### Position Hint System

```kotlin
data class PreferredPos(
    var nextTo: KeyValue? = null
) {
    companion object {
        val DEFAULT = PreferredPos(null)
    }
}

// In KeyboardData.kt
fun addKeyAtPreferredPosition(kv: KeyValue, pos: PreferredPos) {
    if (pos.nextTo != null) {
        // Find target key and insert nearby
        val targetRow = findRowContaining(pos.nextTo)
        val targetIndex = findKeyIndex(pos.nextTo)
        insertKeyAt(targetRow, targetIndex + 1, kv)
    } else {
        // Add to default extra keys row
        extraKeysRow.add(kv)
    }
}
```

### Integration with Config

```kotlin
// Config.kt
fun loadExtraKeys(prefs: SharedPreferences): ExtraKeys {
    val customStr = prefs.getString("extra_keys_custom", "") ?: ""
    val custom = ExtraKeys.parse(null, customStr)

    return ExtraKeys.merge(listOf(
        getSystemExtraKeys(),  // F-keys, common symbols
        custom
    ))
}
```

## Layout XML Format

```xml
<!-- res/xml/qwerty.xml -->
<keyboard name="qwerty" script="latin">
    <row>
        <key key0="q" key1="1" key2="!" />
        <key key0="w" key1="2" key2="@" />
        <!-- ... -->
    </row>
    <!-- ... -->
</keyboard>
```

Key attributes:
- `key0`: Primary key (tap)
- `key1`-`key8`: Swipe directions (N, NE, E, SE, S, SW, W, NW)
- `width`: Key width multiplier (default 1.0)
- `shift`: Behavior on shift (0=normal, 1=uppercase)
