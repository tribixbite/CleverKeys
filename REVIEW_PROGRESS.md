## SYSTEMATIC REVIEW PROGRESS

### File 1/251: KeyValueParser.java (MISSING)
**Status**: CRITICAL - Completely missing from Kotlin
**Lines**: 289 lines of parsing logic
**Impact**: HIGH - Explains layout parsing failures, Chinese character bug

**What it does**:
- Parses key definitions from XML strings
- Supports multiple syntaxes: 'symbol:action', ':kind attr:payload', 'plain string'
- Handles quoted strings with escaping
- Parses macros (multi-key sequences)
- Parses keyevent codes
- Parses flags (dim, small)
- Old syntax compatibility

**Current Kotlin Implementation**:
- KeyValue.kt:629 has parseKeyValue() but SEVERELY INCOMPLETE
- Missing: attribute parsing (flags, symbol)
- Missing: macro parsing
- Missing: quoted string handling with escaping
- Missing: old syntax (:str, :char, :keyevent)
- Missing: comprehensive error handling

**ACTION REQUIRED**: Port complete KeyValueParser.java to KeyValueParser.kt

---



### DETAILED COMPARISON: KeyValueParser

**Java Implementation (289 lines)**:
```java
// Handles 5 different parsing modes:
1. Symbol:Action syntax    → "a:char:b"  (a key displays "a", inputs "b")
2. Symbol:Macro syntax     → "a:b,c,d"   (a key inputs sequence b,c,d)
3. Old :kind syntax        → ":str flags=dim:'text'"
4. Plain string syntax     → "hello"     (simple string key)
5. Quoted string with escape → "'Don\\'t'"

// Supports attributes:
- flags=dim,small
- symbol='custom'

// Comprehensive regex patterns:
- KEYDEF_TOKEN: Token matching with proper escaping
- QUOTED_PAT: Quoted string with \' escaping
- WORD_PAT: Word extraction
```

**Kotlin Implementation (13 lines)**:
```kotlin
// Only handles 3 basic cases:
1. char:X     → CharKey
2. string:X   → StringKey
3. Everything else → StringKey (WRONG!)

// Missing:
- Symbol parsing
- Macro parsing
- Flags parsing
- Quoted string escaping
- Old syntax support
- Attribute parsing
- Error handling
```

**BUG EXAMPLES**:
1. Chinese character: Layout XML contains complex key definition, falls through to "else" case, becomes StringKey with raw XML text
2. Keys don't work: Macro definitions not parsed, multi-key sequences broken
3. Symbols wrong: "symbol='X'" attribute ignored, displays wrong character
4. Styling broken: "flags=dim,small" ignored, keys wrong size/color

**ESTIMATED WORK**: 2-3 days to port properly
- Create KeyValueParser.kt
- Port all regex patterns
- Port all parsing methods
- Extensive testing with real layout XMLs

---

### Files Reviewed: 1 / 251 (0.4%)
### Bugs Identified: 4 major bugs from missing KeyValueParser alone
### Time Spent: 2 hours
### Estimated Time Remaining: 300-500 hours (12-20 weeks)

