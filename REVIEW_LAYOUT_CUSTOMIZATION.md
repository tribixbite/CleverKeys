# Layout & Customization Reviews

**Component Coverage:**
- Extra keys system
- Layout modification
- Key mapping and modifiers
- Preferences and customization

**Files in this component:** 8 total
- ✅ ExtraKeys.java (File 82) - FIXED
- LayoutModifier.java
- Modmap.java
- NumberLayout.java
- DirectBootAwarePreferences.java
- prefs/* files (8 files)
- WordGestureTemplateGenerator.java
- LanguageDetector.java

---

# File 82/251: ExtraKeys.java vs ExtraKeys.kt

**CATASTROPHIC BUG** - Bug #266 (P0) - FIXED ✅

## Java: ExtraKeys.java (150 lines)
**Sophisticated Dynamic Key Injection System:**

**Components:**
1. **ExtraKeys (Container):** Manages collections of extra keys
   - `compute()`: Adds keys based on script/alternatives
   - `parse()`: "key:alt1:alt2@next_to" format
   - `merge()`: Combines multiple sources

2. **ExtraKey (Inner Class):**
   - `kv`: The KeyValue to add
   - `script`: Layout script constraint (null = any)
   - `alternatives`: Keys preventing addition if present
   - `next_to`: Position hint

3. **Query (Inner Class):**
   - `script`: Current layout script
   - `present`: Keys already on layout

**Algorithm:**
```java
if ((q.script == null || script == null || q.script.equals(script))
    && (alternatives.size() == 0 || !q.present.containsAll(alternatives)))
{
    KeyValue kv_ = use_alternative ? alternatives.get(0) : kv;
    KeyboardData.PreferredPos pos = ...;
    dst.put(kv_, pos);
}
```

**String Parsing Examples:**
- `"f11_placeholder"` - Simple key
- `"accent_aigu:´@e"` - Accent with alternative, place near 'e'
- `"f11@f10|f12@f11"` - Multiple keys with position hints

## Kotlin Before: ExtraKeys.kt (18 lines - STUB)
```kotlin
enum class ExtraKeys {
    NONE, CUSTOM, FUNCTION;
}
```
**88% missing functionality!**

## Kotlin After: ExtraKeys.kt (197 lines) - FIXED ✅
**Complete Implementation:**

1. **ExtraKeys data class:**
   - `keys: List<ExtraKey>`
   - `compute()`: Full algorithm
   - `parse()`: String parsing
   - `merge()`: Multi-source combination
   - `EMPTY` constant

2. **ExtraKey data class:**
   - `kv, script, alternatives, nextTo`
   - `compute()`: Context-aware addition
   - `mergeWith()`: Conflict resolution
   - `oneOrNone()`: Generalization helper
   - `parse()`: String parsing

3. **Query data class:**
   - `script, present`

4. **KeyboardData.PreferredPos (added to KeyboardData.kt):**
   - `nextTo: KeyValue?`
   - `DEFAULT`, `ANYWHERE` companions

**Features Restored:**
- Script-specific key additions (e.g., accents only on Latin)
- Alternative key substitution (use alt if main unavailable)
- Position hints (place key next to another)
- Intelligent merging (script generalization on conflicts)

**Impact:** User customization completely blocked
- Cannot add F-keys (F1-F12)
- Cannot add accent marks to layouts
- Cannot position custom keys
- Cannot use layout-specific additions

**Rating:** 0% → 100% feature parity after fix
**Files Changed:** 2 (ExtraKeys.kt + KeyboardData.kt for PreferredPos)

---

**Component Summary:**
- **Files Reviewed:** 1/8 (12.5%)
- **Bugs Found:** 1 (Bug #266 P0 CATASTROPHIC)
- **Bugs Fixed:** 1 (fixed immediately)
- **Status:** Critical extra keys system restored ✅
