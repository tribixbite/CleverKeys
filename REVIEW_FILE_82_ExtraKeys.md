# File 82/251 Review: ExtraKeys.java → ExtraKeys.kt

## File Information
- **Java File**: `/Unexpected-Keyboard/srcs/juloo.keyboard2/ExtraKeys.java`
- **Java Lines**: 150
- **Kotlin File**: `/cleverkeys/src/main/kotlin/tribixbite/keyboard2/ExtraKeys.kt`
- **Kotlin Lines**: 18
- **Reduction**: **88% MISSING FUNCTIONALITY** (150 → 18)

## Classification: 🚨 **CATASTROPHIC BUG - CRITICAL MISSING FEATURE**

---

## Java Implementation Analysis

### Core Features:
The Java ExtraKeys system provides **sophisticated dynamic key injection** into keyboard layouts:

1. **ExtraKeys (Container Class)**: Manages collections of extra keys
   - `compute()`: Adds keys to keyboard based on script and alternatives
   - `parse()`: Parses extra key definitions from strings (e.g., "key:alt1:alt2@next_to")
   - `merge()`: Combines multiple ExtraKeys collections, merging identical keys

2. **ExtraKey (Inner Class)**: Represents a single extra key with:
   - `kv`: The KeyValue to add
   - `script`: Layout script constraint (null = any script)
   - `alternatives`: Keys that prevent addition if present
   - `next_to`: Preferred position hint (place next to this key)

3. **Query (Inner Class)**: Context for key addition decisions:
   - `script`: Current layout script
   - `present`: Keys already on the layout

### Key Algorithm:
```java
public void compute(Map<KeyValue, KeyboardData.PreferredPos> dst, Query q)
{
  boolean use_alternative = (alternatives.size() == 1 && !dst.containsKey(kv));
  if ((q.script == null || script == null || q.script.equals(script))
      && (alternatives.size() == 0 || !q.present.containsAll(alternatives)))
  {
    KeyValue kv_ = use_alternative ? alternatives.get(0) : kv;
    KeyboardData.PreferredPos pos = KeyboardData.PreferredPos.DEFAULT;
    if (next_to != null) {
      pos = new KeyboardData.PreferredPos(pos);
      pos.next_to = next_to;
    }
    dst.put(kv_, pos);
  }
}
```

**Logic Flow:**
1. If only 1 alternative exists and main key not present → use alternative instead
2. Check script compatibility (null matches any script)
3. Check if any alternative is missing from layout
4. If all conditions pass → add key with preferred position hint

### String Parsing:
```java
// Examples:
"f11_placeholder"                    // Simple key
"accent_aigu:´@e"                   // Accent key with alternative, place near 'e'
"accent_grave:`@a|accent_aigu:´@e"  // Multiple keys (pipe-separated)
```

### Merge Algorithm:
```java
public static ExtraKeys merge(List<ExtraKeys> kss) {
  Map<KeyValue, ExtraKey> merged_keys = new HashMap<>();
  for (ExtraKeys ks : kss)
    for (ExtraKey k : ks._ks) {
      ExtraKey k2 = merged_keys.get(k.kv);
      if (k2 != null)
        k = k.merge_with(k2);  // Combine alternatives, generalize script
      merged_keys.put(k.kv, k);
    }
  return new ExtraKeys(merged_keys.values());
}
```

---

## Kotlin Implementation Analysis

### Current Implementation:
```kotlin
enum class ExtraKeys {
    NONE, CUSTOM, FUNCTION;

    companion object {
        fun fromString(value: String): ExtraKeys {
            return when (value) {
                "custom" -> CUSTOM
                "function" -> FUNCTION
                else -> NONE
            }
        }
    }
}
```

**This is a STUB** - only 3 enum values with no functionality!

### Missing Features:
1. ❌ **ExtraKey class** - No representation of individual extra keys
2. ❌ **Script-based filtering** - No layout script compatibility checks
3. ❌ **Alternative key logic** - No alternative substitution system
4. ❌ **Position hints** - No PreferredPos system for key placement
5. ❌ **String parsing** - No "key:alt@next_to" parser
6. ❌ **Merging algorithm** - No multi-source key combination
7. ❌ **Query system** - No context-aware key addition
8. ❌ **KeyboardData.PreferredPos** - Position hint class missing from KeyboardData.kt

---

## Bug #266: ExtraKeys System Completely Missing

### Severity: **CATASTROPHIC**

### Impact:
- **User customization broken**: Cannot add extra keys to layouts (F-keys, accents, symbols)
- **Layout flexibility gone**: No script-specific key additions
- **Position control lost**: Cannot specify where extra keys should appear
- **Alternative logic missing**: Cannot substitute similar keys intelligently

### Root Cause:
Complete architectural omission - entire ExtraKeys system replaced with 3-value enum stub.

### Examples of Broken Functionality:
```java
// Java - User can add F11 with preference to place near F10:
ExtraKeys.parse("latin", "f11_placeholder@f10")

// Java - Add accent keys only to Latin layouts, with alternatives:
ExtraKeys.parse("latin", "accent_aigu:´@e|accent_grave:`@a")

// Kotlin - NONE OF THIS WORKS! Only has NONE/CUSTOM/FUNCTION enum values
```

---

## Feature Comparison Table

| Feature | Java (150 lines) | Kotlin (18 lines) | Status |
|---------|------------------|-------------------|--------|
| **ExtraKey class** | ✅ Full implementation | ❌ Missing | **CRITICAL** |
| **Script filtering** | ✅ Null-safe script matching | ❌ Missing | **HIGH** |
| **Alternative logic** | ✅ Substitution if only 1 alt | ❌ Missing | **HIGH** |
| **Position hints** | ✅ PreferredPos with next_to | ❌ Missing | **HIGH** |
| **String parsing** | ✅ "key:alt@next_to" format | ❌ Missing | **HIGH** |
| **Merge algorithm** | ✅ Combines multiple sources | ❌ Missing | **MEDIUM** |
| **Query system** | ✅ Context-aware decisions | ❌ Missing | **HIGH** |
| **EMPTY constant** | ✅ Collections.EMPTY_LIST | ❌ Missing | **LOW** |

---

## Recommendation: **IMPLEMENT FULL SYSTEM IMMEDIATELY**

### Implementation Plan:

1. **Create ExtraKeys.kt data classes:**
```kotlin
data class ExtraKeys(
    val keys: List<ExtraKey>
) {
    fun compute(dst: MutableMap<KeyValue, KeyboardData.PreferredPos>, query: Query)

    companion object {
        val EMPTY = ExtraKeys(emptyList())

        fun parse(script: String?, str: String): ExtraKeys
        fun merge(keysList: List<ExtraKeys>): ExtraKeys
    }

    data class ExtraKey(
        val kv: KeyValue,
        val script: String?,
        val alternatives: List<KeyValue>,
        val nextTo: KeyValue?
    ) {
        fun compute(dst: MutableMap<KeyValue, KeyboardData.PreferredPos>, query: Query)
        fun mergeWith(other: ExtraKey): ExtraKey
    }

    data class Query(
        val script: String?,
        val present: Set<KeyValue>
    )
}
```

2. **Add PreferredPos to KeyboardData.kt:**
```kotlin
data class PreferredPos(
    var nextTo: KeyValue? = null
) {
    companion object {
        val DEFAULT = PreferredPos()
    }
}
```

3. **Implement parsing logic:**
   - Split on "|" for multiple keys
   - Split on "@" for next_to hints
   - Split on ":" for alternatives

4. **Implement merge algorithm:**
   - Collect all ExtraKey instances by KeyValue
   - Combine alternatives lists
   - Generalize script to null on conflicts

---

## Priority: **P0 - BLOCKING USER CUSTOMIZATION**

This is a **complete feature omission** affecting keyboard customization. Users cannot:
- Add function keys (F1-F12)
- Add accent marks to specific layouts
- Position custom keys near related keys
- Use layout-specific key additions

**MUST BE FIXED BEFORE RELEASE** - this is core keyboard functionality!

---

## Files Requiring Changes:
1. `/cleverkeys/src/main/kotlin/tribixbite/keyboard2/ExtraKeys.kt` - Complete rewrite (150+ lines)
2. `/cleverkeys/src/main/kotlin/tribixbite/keyboard2/KeyboardData.kt` - Add PreferredPos class
3. `/cleverkeys/src/main/kotlin/tribixbite/keyboard2/prefs/ExtraKeysPreference.kt` - Verify compatibility
4. `/cleverkeys/src/main/kotlin/tribixbite/keyboard2/Config.kt` - Verify get_extra_keys() usage

---

**Review Date**: 2025-10-19
**Reviewed By**: Claude (Systematic Java→Kotlin Feature Parity Review)
**Next File**: File 83/251 - FoldStateTracker.java
