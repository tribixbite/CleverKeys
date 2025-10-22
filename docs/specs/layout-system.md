# Layout Customization System Specification

**Feature**: Dynamic Extra Keys and Layout Customization
**Status**: 🚨 CATASTROPHIC - 88% MISSING
**Priority**: P0 (CRITICAL)
**Assignee**: TBD
**Date Created**: 2025-10-20

---

## TODOs

### ⚠️ KNOWN ISSUES (From Historical Review)

#### CRITICAL Issues (Fixed)

**✅ Issue #2: Missing ExtraKeysPreference Implementation**
- **File**: `src/main/kotlin/tribixbite/keyboard2/Config.kt:295`
- **Status**: ⚠️ NEEDS VERIFICATION
- **Original Problem**: `extra_keys_param = emptyMap() // TODO: Fix ExtraKeysPreference.get_extra_keys(prefs)`
- **Impact**: Extra keys feature completely non-functional
- **Action Required**:
  - [ ] Verify ExtraKeysPreference.get_extra_keys() is implemented
  - [ ] Test extra keys loading from preferences
  - [ ] Test extra keys saving to preferences
  - [ ] Verify integration with Config.kt
  - Related to Task 1-2 below (ExtraKeys system implementation)

**✅ Issue #4: CustomLayoutEditor Save/Load Stubs**
- **File**: `src/main/kotlin/tribixbite/keyboard2/CustomLayoutEditor.kt:121-127`
- **Status**: ⚠️ NEEDS VERIFICATION
- **Original Problem**: Save and load functions just show toast with "TODO"
- **Impact**: Custom layouts cannot be persisted
- **Action Required**:
  - [ ] Review CustomLayoutEditor.kt lines 121-127
  - [ ] Implement save to SharedPreferences or JSON file
  - [ ] Implement load from storage
  - [ ] Test custom layout persistence across app restarts
  - [ ] Add validation for malformed layout data

**✅ Issue #6: CustomExtraKeysPreference Not Implemented**
- **File**: `src/main/kotlin/tribixbite/keyboard2/prefs/CustomExtraKeysPreference.kt`
- **Status**: ✅ STUB CREATED (Full implementation pending)
- **Current State**: Minimal stub to prevent crashes
  - Returns emptyMap() from get() method
  - Displays "Feature coming soon" when clicked
  - Disabled to prevent user confusion
- **Future Work**:
  - [ ] Key picker dialog with all available keys
  - [ ] Position configuration UI
  - [ ] JSON persistence implementation
  - [ ] Visual keyboard preview
- Related to Tasks 1-2 below

### LOW PRIORITY Issues

**Issue #25: Test Layout Interface Not Implemented**
- **File**: `src/main/kotlin/tribixbite/keyboard2/CustomLayoutEditor.kt:143-144`
- **Status**: FUTURE WORK
- **Problem**: `toast("Test layout (TODO: Implement test interface)")`
- **Impact**: Cannot preview custom layouts before saving
- **Action Required** (Future):
  - [ ] Create test/preview activity for custom layouts
  - [ ] Show full keyboard with custom layout applied
  - [ ] Allow user to type test text
  - [ ] Provide "Save" or "Discard" options

**Issue #26: Key Editing Dialog Not Implemented**
- **File**: `src/main/kotlin/tribixbite/keyboard2/CustomLayoutEditor.kt:223`
- **Status**: FUTURE WORK
- **Problem**: `// TODO: Open key editing dialog`
- **Impact**: Cannot edit individual keys in custom layouts
- **Action Required** (Future):
  - [ ] Create key properties dialog
  - [ ] Allow editing: label, keyValue, width, behavior
  - [ ] Support multi-tap keys
  - [ ] Support swipe actions per key

**Issue #27: Add Key to Layout Not Implemented**
- **File**: `src/main/kotlin/tribixbite/keyboard2/CustomLayoutEditor.kt:267`
- **Status**: FUTURE WORK
- **Problem**: `// TODO: Add key to layout`
- **Impact**: Cannot add new keys to custom layouts
- **Action Required** (Future):
  - [ ] Implement key insertion logic
  - [ ] Support inserting at specific position
  - [ ] Adjust surrounding key widths automatically
  - [ ] Validate layout integrity after insertion

---

**Implementation Tasks**:
- [ ] **Task 1**: Port ExtraKeys.kt complete system (150 lines from Java)
  - ExtraKey data class (kv, script, alternatives, nextTo)
  - ExtraKeys container class
  - Query context class
  - Estimated Time: 4-6 hours

- [ ] **Task 2**: Implement string parsing ("key:alt@next_to" format)
  - Parse single keys with alternatives and position hints
  - Parse pipe-separated multi-key definitions
  - Error handling for malformed strings
  - Estimated Time: 3-4 hours

- [ ] **Task 3**: Implement compute() algorithm
  - Script compatibility checking
  - Alternative substitution logic
  - Position hint application
  - Estimated Time: 2-3 hours

- [ ] **Task 4**: Implement merge() algorithm
  - Combine multiple ExtraKeys sources
  - Merge alternatives for identical keys
  - Generalize script constraints
  - Estimated Time: 2-3 hours

- [ ] **Task 5**: Add PreferredPos to KeyboardData.kt
  - Position hint system
  - Integration with layout loading
  - Estimated Time: 2 hours

- [ ] **Task 6**: Fix ComposeKeyData arrays (Bug #78)
  - Generate missing ~14,900/15,000 entries
  - Add 33 named constants (Bug #79)
  - Estimated Time: 4-6 hours (code generation)

- [ ] **Task 7**: Testing
  - Unit tests for parsing, compute, merge
  - Integration tests with keyboard layouts
  - Manual testing of extra key customization
  - Estimated Time: 4-5 hours

**Total Estimated Time**: 21-29 hours (3-4 days)

---

## 1. Feature Overview

### Purpose
Sophisticated dynamic key injection system enabling users to:
- Add extra keys to keyboard layouts (F-keys, accents, symbols)
- Customize key placement with position hints
- Script-specific key additions (e.g., accents only for Latin layouts)
- Alternative key substitution (intelligent fallback logic)
- Merge multiple customization sources

### User Value
- **Customization**: Users can add missing keys to any layout
- **Flexibility**: Different extra keys per script/language
- **Intelligence**: Automatic alternative selection based on layout
- **Positioning**: Control where extra keys appear
- **Power User Features**: F1-F12, clipboard history shortcuts, symbols

### Current Status
- **Java Implementation**: ExtraKeys.java (150 lines) - COMPLETE ✅
- **Kotlin Implementation**: ExtraKeys.kt (18 lines) - **88% MISSING** ❌
  - Current: 3-value enum stub (NONE, CUSTOM, FUNCTION)
  - Missing: All parsing, compute, merge, positioning logic
- **Bug**: #266 (CATASTROPHIC)

### Related Bugs
- **Bug #78**: ComposeKeyData arrays 99% missing (~14,900 entries)
- **Bug #79**: Missing 33 named constants in ComposeKeyData
- **Bug #82**: DirectBootAwarePreferences 75% missing

### Dependencies
- `KeyValue.kt` - Key value definitions
- `KeyboardData.kt` - Needs PreferredPos class
- `ComposeKeyData.kt` - Needs complete data arrays
- `Config.kt` - User preferences for extra keys

---

## 2. Requirements

### Functional Requirements

**FR-1: ExtraKey Representation**
- Each extra key has:
  - `kv`: KeyValue to add
  - `script`: Layout script constraint (null = any script)
  - `alternatives`: List of alternative keys (prevent addition if present)
  - `nextTo`: Preferred position hint (place near this key)

**FR-2: String Parsing**
- Format: `"key:alt1:alt2@next_to"`
- Examples:
  - `"f11_placeholder"` - Simple key
  - `"accent_aigu:´@e"` - Accent near 'e' key
  - `"f1|f2|f3"` - Multiple keys (pipe-separated)
- Must handle:
  - Keys with no alternatives or position hints
  - Multiple alternatives
  - Script prefixes (e.g., `"latin"`)

**FR-3: Compute Algorithm**
- Decision logic for adding keys:
  1. **Alternative substitution**: If only 1 alternative exists and main key not present → use alternative instead
  2. **Script compatibility**: Check script matches (null matches any)
  3. **Presence check**: If any alternative missing from layout → add key
  4. **Position hint**: Apply nextTo preference if specified
- Output: Map of KeyValue → PreferredPos

**FR-4: Merge Algorithm**
- Combine multiple ExtraKeys sources
- For duplicate keys (same kv):
  - Merge alternatives lists
  - Generalize script (null if different scripts)
  - Preserve nextTo hint
- Use case: Merge user prefs + layout defaults + system extras

**FR-5: Query Context**
- Provides decision context:
  - `script`: Current layout script (e.g., "latin", "cyrillic")
  - `present`: Set of keys already on layout
- Used by compute() to make intelligent decisions

### Non-Functional Requirements

**NFR-1: Performance**
- Parsing: < 10ms for typical extra keys string
- Compute: O(n) where n = number of extra keys
- Merge: O(n) where n = total keys across all sources
- No allocations in layout hot path

**NFR-2: Correctness**
- Parsing must handle malformed input gracefully
- Merge must be associative (order doesn't matter)
- Script matching must be null-safe

**NFR-3: Maintainability**
- Clear separation of parsing vs compute vs merge
- Well-documented algorithm logic
- Comprehensive unit tests

---

## 3. Technical Design

### Architecture

```
User Preferences (strings)
    ↓ (parse)
ExtraKeys
    ↓ (compute with Query)
Map<KeyValue, PreferredPos>
    ↓
KeyboardData.addExtraKeys()
    ↓
Final Layout
```

### Data Structures

**ExtraKeys System**:
```kotlin
data class ExtraKeys(
    val keys: List<ExtraKey>
) {
    /**
     * Add extra keys to destination map based on query context.
     *
     * @param dst Output map of KeyValue → PreferredPos
     * @param query Context (script, present keys)
     */
    fun compute(dst: MutableMap<KeyValue, PreferredPos>, query: Query) {
        keys.forEach { it.compute(dst, query) }
    }

    companion object {
        val EMPTY = ExtraKeys(emptyList())

        /**
         * Parse extra keys from string format.
         *
         * Format: "key1:alt1:alt2@next_to|key2|key3:alt@pos"
         *
         * @param script Script constraint (null = any script)
         * @param str Extra keys definition string
         * @return Parsed ExtraKeys
         */
        fun parse(script: String?, str: String): ExtraKeys {
            val keys = str.split('|').mapNotNull { part ->
                parseExtraKey(script, part.trim())
            }
            return ExtraKeys(keys)
        }

        /**
         * Merge multiple ExtraKeys sources.
         *
         * Combines keys from all sources, merging duplicates:
         * - Merge alternatives lists
         * - Generalize script if different
         * - Preserve nextTo hint
         *
         * @param keysList List of ExtraKeys to merge
         * @return Merged ExtraKeys
         */
        fun merge(keysList: List<ExtraKeys>): ExtraKeys {
            val mergedMap = mutableMapOf<KeyValue, ExtraKey>()

            for (ks in keysList) {
                for (key in ks.keys) {
                    val existing = mergedMap[key.kv]
                    mergedMap[key.kv] = if (existing != null) {
                        key.mergeWith(existing)
                    } else {
                        key
                    }
                }
            }

            return ExtraKeys(mergedMap.values.toList())
        }

        private fun parseExtraKey(script: String?, part: String): ExtraKey? {
            // Parse "key:alt1:alt2@next_to" format
            val atSplit = part.split('@')
            val mainPart = atSplit[0]
            val nextTo = if (atSplit.size > 1) {
                KeyValue.getKeyByName(atSplit[1])
            } else null

            val colonSplit = mainPart.split(':')
            if (colonSplit.isEmpty()) return null

            val kv = KeyValue.getKeyByName(colonSplit[0]) ?: return null
            val alternatives = colonSplit.drop(1).mapNotNull {
                KeyValue.getKeyByName(it)
            }

            return ExtraKey(kv, script, alternatives, nextTo)
        }
    }

    /**
     * Represents a single extra key with customization options.
     */
    data class ExtraKey(
        val kv: KeyValue,
        val script: String?,
        val alternatives: List<KeyValue>,
        val nextTo: KeyValue?
    ) {
        /**
         * Add this key to destination if conditions are met.
         *
         * Algorithm:
         * 1. Use alternative if only 1 alt exists and main key not present
         * 2. Check script compatibility (null matches any)
         * 3. Check if any alternative is missing from layout
         * 4. If all pass → add key with position hint
         *
         * @param dst Output map
         * @param query Context (script, present keys)
         */
        fun compute(dst: MutableMap<KeyValue, PreferredPos>, query: Query) {
            // Alternative substitution logic
            val useAlternative = (alternatives.size == 1 && !dst.containsKey(kv))

            // Script compatibility check
            val scriptMatches = (query.script == null ||
                                 script == null ||
                                 query.script == script)

            // Presence check: add if no alternatives OR some alternative missing
            val shouldAdd = (alternatives.isEmpty() ||
                            !query.present.containsAll(alternatives))

            if (scriptMatches && shouldAdd) {
                val keyToAdd = if (useAlternative) alternatives[0] else kv
                val pos = if (nextTo != null) {
                    PreferredPos(nextTo)
                } else {
                    PreferredPos.DEFAULT
                }
                dst[keyToAdd] = pos
            }
        }

        /**
         * Merge this key with another (same kv).
         *
         * - Combine alternatives lists (no duplicates)
         * - Generalize script (null if different)
         * - Preserve nextTo hint (prefer non-null)
         */
        fun mergeWith(other: ExtraKey): ExtraKey {
            require(kv == other.kv) { "Cannot merge keys with different kv" }

            val mergedAlternatives = (alternatives + other.alternatives).distinct()
            val mergedScript = if (script == other.script) script else null
            val mergedNextTo = nextTo ?: other.nextTo

            return ExtraKey(kv, mergedScript, mergedAlternatives, mergedNextTo)
        }
    }

    /**
     * Context for key addition decisions.
     */
    data class Query(
        val script: String?,           // Current layout script
        val present: Set<KeyValue>     // Keys already on layout
    )
}
```

**KeyboardData.PreferredPos**:
```kotlin
// In KeyboardData.kt
data class PreferredPos(
    var nextTo: KeyValue? = null
) {
    companion object {
        val DEFAULT = PreferredPos(null)
    }
}
```

### Algorithms

**1. String Parsing Algorithm**:
```
Input: "accent_aigu:´@e|f11_placeholder@f10"

Split by '|':
  ["accent_aigu:´@e", "f11_placeholder@f10"]

For each part:
  Split by '@': ["accent_aigu:´", "e"]
    mainPart = "accent_aigu:´"
    nextTo = KeyValue("e")

  Split mainPart by ':': ["accent_aigu", "´"]
    kv = KeyValue("accent_aigu")
    alternatives = [KeyValue("´")]

  Create: ExtraKey(kv=accent_aigu, alts=[´], nextTo=e)

Result: ExtraKeys([
  ExtraKey(accent_aigu, alts=[´], nextTo=e),
  ExtraKey(f11_placeholder, alts=[], nextTo=f10)
])
```

**2. Compute Algorithm Logic**:
```
Given: ExtraKey(kv=accent_aigu, alts=[´], script=latin, nextTo=e)
Query: script=latin, present={a,b,c,e,f}  (note: ´ NOT present)

Step 1: Alternative substitution?
  alternatives.size == 1 && !dst.containsKey(accent_aigu)
  → true → useAlternative = true → keyToAdd = ´

Step 2: Script compatibility?
  query.script == script  → "latin" == "latin" → true

Step 3: Presence check?
  !query.present.containsAll([´])  → !{a,b,c,e,f}.containsAll([´])
  → true (´ is missing)

Step 4: Add key!
  dst[´] = PreferredPos(nextTo=e)

Result: Adds ´ key with hint to place near 'e'
```

**3. Merge Algorithm Example**:
```
Source 1: ExtraKey(kv=f1, script=null, alts=[], nextTo=tab)
Source 2: ExtraKey(kv=f1, script=latin, alts=[F1], nextTo=null)

Merge:
  kv: f1 (same)
  script: null (generalize - was null vs latin)
  alternatives: [] + [F1] = [F1]
  nextTo: tab (prefer non-null)

Result: ExtraKey(kv=f1, script=null, alts=[F1], nextTo=tab)
```

### Integration Points

**Config.kt** (user preferences):
```kotlin
data class GlobalConfig(
    // ... existing fields ...
    val extra_keys: ExtraKeys = ExtraKeys.EMPTY,
    val extra_keys_custom: String = "",  // User-defined extra keys string
)

// In Config initialization
fun loadExtraKeys(prefs: SharedPreferences): ExtraKeys {
    val customStr = prefs.getString("extra_keys_custom", "") ?: ""
    val custom = ExtraKeys.parse(null, customStr)

    // Merge with system defaults
    return ExtraKeys.merge(listOf(
        getSystemExtraKeys(),  // F-keys, common symbols
        custom
    ))
}
```

**KeyboardData.kt** (layout loading):
```kotlin
fun addExtraKeys(extraKeys: ExtraKeys, script: String?) {
    val query = ExtraKeys.Query(
        script = script,
        present = rows.flatMap { it.keys }.map { it.key0.kv }.toSet()
    )

    val extraKeyMap = mutableMapOf<KeyValue, ExtraKeys.PreferredPos>()
    extraKeys.compute(extraKeyMap, query)

    // Add keys to layout at preferred positions
    for ((kv, pos) in extraKeyMap) {
        addKeyAtPreferredPosition(kv, pos)
    }
}

private fun addKeyAtPreferredPosition(kv: KeyValue, pos: ExtraKeys.PreferredPos) {
    // Find position based on pos.nextTo hint
    // If nextTo is null, add to default extra keys row
    // If nextTo exists, find that key and insert nearby
}
```

---

## 4. Implementation Plan

### Phase 1: Core Data Structures (Day 1)
1. Create `ExtraKeys.kt` with data classes
   - ExtraKey, ExtraKeys, Query
   - PreferredPos in KeyboardData.kt
2. Implement basic constructors and properties
3. Write unit tests for data class creation

**Acceptance Criteria**:
- All data classes compile
- Can create ExtraKey instances manually
- Unit tests pass

### Phase 2: String Parsing (Day 1-2)
1. Implement `parseExtraKey()` helper
   - Parse "key:alt@next_to" format
   - Handle edge cases (no alts, no nextTo, malformed)
2. Implement `ExtraKeys.parse()`
   - Split by pipe, parse each part
3. Write comprehensive parsing tests

**Acceptance Criteria**:
- Parses all valid formats correctly
- Handles malformed input gracefully
- Unit tests cover edge cases

### Phase 3: Compute Algorithm (Day 2)
1. Implement `ExtraKey.compute()`
   - Alternative substitution logic
   - Script compatibility check
   - Presence check
   - Position hint application
2. Implement `ExtraKeys.compute()`
3. Write algorithm unit tests

**Acceptance Criteria**:
- Compute logic matches Java behavior
- Alternative substitution works
- Script filtering works
- Position hints applied

### Phase 4: Merge Algorithm (Day 2-3)
1. Implement `ExtraKey.mergeWith()`
2. Implement `ExtraKeys.merge()`
3. Write merge tests (order independence, duplicate handling)

**Acceptance Criteria**:
- Merge combines keys correctly
- Order-independent (associative)
- Handles duplicates properly

### Phase 5: Integration (Day 3)
1. Add extra_keys to Config.kt
2. Implement KeyboardData.addExtraKeys()
3. Add position hint system to layout loading
4. Wire up user preferences

**Acceptance Criteria**:
- Extra keys load from preferences
- Keys added to layouts correctly
- Position hints work
- Can test via user settings

### Phase 6: ComposeKeyData Fix (Day 3-4)
1. Generate missing ~14,900 compose key entries (Bug #78)
2. Add 33 named constants (Bug #79)
3. Validate data completeness

**Acceptance Criteria**:
- All compose key combinations available
- Named constants accessible
- No data truncation

### Phase 7: Testing (Day 4)
1. Integration tests with real layouts
2. Manual testing on device
3. Test all example cases from spec

**Acceptance Criteria**:
- Extra keys appear in keyboard
- Position hints work visually
- User customization functional

---

## 5. Testing Strategy

### Unit Tests

**Parsing Tests**:
```kotlin
@Test
fun `parse simple key`() {
    val ek = ExtraKeys.parse(null, "f11_placeholder")
    assertEquals(1, ek.keys.size)
    assertEquals("f11_placeholder", ek.keys[0].kv.name)
}

@Test
fun `parse key with alternative and position`() {
    val ek = ExtraKeys.parse(null, "accent_aigu:´@e")
    val key = ek.keys[0]
    assertEquals("accent_aigu", key.kv.name)
    assertEquals(1, key.alternatives.size)
    assertEquals("´", key.alternatives[0].name)
    assertEquals("e", key.nextTo?.name)
}

@Test
fun `parse multiple keys`() {
    val ek = ExtraKeys.parse(null, "f1|f2|f3")
    assertEquals(3, ek.keys.size)
}

@Test
fun `parse with script constraint`() {
    val ek = ExtraKeys.parse("latin", "accent_grave@a")
    assertEquals("latin", ek.keys[0].script)
}

@Test
fun `parse malformed input returns empty`() {
    val ek = ExtraKeys.parse(null, "::@|@")
    assertEquals(0, ek.keys.size)
}
```

**Compute Tests**:
```kotlin
@Test
fun `compute adds key when alternative missing`() {
    val key = ExtraKey(
        kv = KeyValue("accent_aigu"),
        script = null,
        alternatives = listOf(KeyValue("´")),
        nextTo = null
    )
    val query = ExtraKeys.Query(
        script = null,
        present = setOf(KeyValue("a"), KeyValue("e"))
    )
    val dst = mutableMapOf<KeyValue, ExtraKeys.PreferredPos>()

    key.compute(dst, query)

    assertEquals(1, dst.size)
    assertTrue(dst.containsKey(KeyValue("´")))  // Alternative used
}

@Test
fun `compute skips key when alternative present`() {
    val key = ExtraKey(
        kv = KeyValue("accent_aigu"),
        script = null,
        alternatives = listOf(KeyValue("´")),
        nextTo = null
    )
    val query = ExtraKeys.Query(
        script = null,
        present = setOf(KeyValue("´"))  // Alternative already present
    )
    val dst = mutableMapOf<KeyValue, ExtraKeys.PreferredPos>()

    key.compute(dst, query)

    assertEquals(0, dst.size)  // Not added
}

@Test
fun `compute respects script constraint`() {
    val key = ExtraKey(
        kv = KeyValue("accent_grave"),
        script = "latin",
        alternatives = emptyList(),
        nextTo = null
    )

    // Match
    val query1 = ExtraKeys.Query(script = "latin", present = emptySet())
    val dst1 = mutableMapOf<KeyValue, ExtraKeys.PreferredPos>()
    key.compute(dst1, query1)
    assertEquals(1, dst1.size)

    // No match
    val query2 = ExtraKeys.Query(script = "cyrillic", present = emptySet())
    val dst2 = mutableMapOf<KeyValue, ExtraKeys.PreferredPos>()
    key.compute(dst2, query2)
    assertEquals(0, dst2.size)
}

@Test
fun `compute applies position hint`() {
    val key = ExtraKey(
        kv = KeyValue("f11"),
        script = null,
        alternatives = emptyList(),
        nextTo = KeyValue("f10")
    )
    val query = ExtraKeys.Query(script = null, present = emptySet())
    val dst = mutableMapOf<KeyValue, ExtraKeys.PreferredPos>()

    key.compute(dst, query)

    assertEquals(KeyValue("f10"), dst[KeyValue("f11")]?.nextTo)
}
```

**Merge Tests**:
```kotlin
@Test
fun `merge combines alternatives`() {
    val key1 = ExtraKey(KeyValue("f1"), null, listOf(KeyValue("F1")), null)
    val key2 = ExtraKey(KeyValue("f1"), null, listOf(KeyValue("fn1")), null)

    val merged = key1.mergeWith(key2)

    assertEquals(2, merged.alternatives.size)
    assertTrue(merged.alternatives.contains(KeyValue("F1")))
    assertTrue(merged.alternatives.contains(KeyValue("fn1")))
}

@Test
fun `merge generalizes script`() {
    val key1 = ExtraKey(KeyValue("f1"), "latin", emptyList(), null)
    val key2 = ExtraKey(KeyValue("f1"), "cyrillic", emptyList(), null)

    val merged = key1.mergeWith(key2)

    assertNull(merged.script)  // Generalized to null
}

@Test
fun `merge is order-independent`() {
    val ek1 = ExtraKeys(listOf(ExtraKey(KeyValue("f1"), null, emptyList(), null)))
    val ek2 = ExtraKeys(listOf(ExtraKey(KeyValue("f2"), null, emptyList(), null)))

    val merged1 = ExtraKeys.merge(listOf(ek1, ek2))
    val merged2 = ExtraKeys.merge(listOf(ek2, ek1))

    assertEquals(merged1.keys.toSet(), merged2.keys.toSet())
}
```

### Integration Tests

**Test Cases**:
1. Load extra keys from preferences
2. Add F1-F12 to layout
3. Add accent keys to Latin layout only
4. Verify position hints work (keys appear near target)
5. Test merge of user + system extra keys

### Manual Testing Checklist

- [ ] Add custom extra keys via settings
- [ ] Verify F-keys appear on keyboard
- [ ] Test accent keys on Latin layout
- [ ] Test accent keys don't appear on non-Latin layout
- [ ] Verify position hints (e.g., accent near 'e')
- [ ] Test alternative substitution (if main key missing, use alt)
- [ ] Test multi-source merge (user + system)
- [ ] Verify compose key data complete (Bug #78 fixed)

---

## 6. Success Criteria

### Functional Success
- ✅ Parse all extra key string formats
- ✅ Compute adds keys based on script, alternatives, presence
- ✅ Merge combines multiple sources correctly
- ✅ Position hints apply to layout
- ✅ User can customize extra keys via settings
- ✅ ComposeKeyData arrays complete (~15,000 entries)

### Technical Success
- ✅ Algorithm matches Java behavior exactly
- ✅ Parsing handles malformed input gracefully
- ✅ Merge is associative (order-independent)
- ✅ No performance degradation
- ✅ Unit test coverage ≥ 95%

### User Experience Success
- ✅ Extra keys appear as expected
- ✅ Position hints feel natural
- ✅ Script filtering works transparently
- ✅ Customization UI intuitive

---

## 7. References

### Source Files
- **Original Java**: `Unexpected-Keyboard/srcs/juloo.keyboard2/ExtraKeys.java` (150 lines)
- **Current Kotlin**: `src/main/kotlin/tribixbite/keyboard2/ExtraKeys.kt` (18 lines - STUB)
- **Review Document**: `REVIEW_FILE_82_ExtraKeys.md`
- **Bug Reports**: Bug #266 (CATASTROPHIC), #78, #79, #82

### Related Files
- `KeyValue.kt` - Key value definitions
- `KeyboardData.kt` - Layout data structures (needs PreferredPos)
- `ComposeKeyData.kt` - Compose key combinations (needs data fix)
- `Config.kt` - User preferences
- `KeyboardLayoutLoader.kt` - Layout loading

### Algorithm References
- **Alternative Substitution**: Intelligent fallback when main key missing
- **Script Filtering**: Null-safe script matching
- **Position Hints**: next_to placement strategy
- **Merge**: Associative combination of multiple sources

---

## 8. Notes

### Why This Is Critical
- **Bug #266 CATASTROPHIC**: 88% of ExtraKeys system missing
- **User Impact**: Cannot customize keyboard layouts at all
- **Feature Parity**: Original has full system, CleverKeys has 3-value enum stub
- **Power Users**: F-keys, accents, symbols completely unavailable

### Implementation Complexity
- **Medium**: Parsing is straightforward, compute logic well-defined
- **Risk**: Integration with KeyboardData layout loading
- **Estimate**: 3-4 days for full implementation + testing

### Related Work
- Bug #78 fix (ComposeKeyData) can be done in parallel
- Bug #82 (DirectBootAwarePreferences) independent
- Layout loading system may need refactoring for position hints

### Future Enhancements
1. Visual layout editor for extra keys
2. Import/export extra key configurations
3. Community-shared extra key presets
4. Per-layout extra key overrides
5. Dynamic extra keys based on context (app-specific)

---

**Last Updated**: 2025-10-20
**Status**: Awaiting implementation
**Priority**: P0 CATASTROPHIC (critical feature missing)
