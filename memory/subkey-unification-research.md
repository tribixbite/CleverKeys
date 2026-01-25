# Subkey System Unification Research

## Investigation Complete - Analysis & Unification Options

Now I have a complete picture of all three systems. Let me update the todo list and provide my analysis with unification options:

### Three Distinct Systems Found:

| System | Purpose | Storage | Scope |
|--------|---------|---------|-------|
| **XML Subkeys** | Built-in swipe destinations per key (e.g., `ne="8"`) | Layout XML files → `KeyboardData.Key.keys[]` | Per-layout defaults |
| **Custom Mappings** | User overrides for swipe destinations | JSON via `ShortSwipeCustomizationManager` | Global user prefs |
| **Extra Keys** | Additional keys added to keyboard (clipboard, voice, etc.) | SharedPreferences checkboxes | Global user prefs |

### Key Finding: Extra Keys is a Separate System
Extra Keys (`ExtraKeysPreference`) adds **NEW KEYS** to the keyboard (like Clipboard button, Copy/Paste keys). It's NOT related to subkey/swipe customization. The 90+ extra keys are placed **next to existing keys** via `KeyboardData.addExtraKeys()`.

### The Real Conflict: XML Subkeys vs Custom Mappings

**Current Behavior:**
1. `KeyMagnifierView` shows BOTH XML subkeys AND custom mappings in the visual preview
2. But the "Custom Mappings" list below only shows **custom** ones
3. User sees "8" in NE corner but NE isn't listed as editable
4. `Pointers.kt:408-419` - Custom mappings WIN over XML (early return)

---

## Unification Options

### Option A: Unified "Effective Mappings" View (Recommended)
**Concept:** Show ALL active mappings (XML + custom) in a single list, with visual distinction.

**Changes:**
1. In `KeyCustomizationDialog`, show a combined list:
   - XML-defined subkeys shown with a "Layout" badge (grey)
   - Custom mappings shown with a "Custom" badge (colored)
2. Tapping an XML entry creates a custom override
3. Deleting a custom mapping reveals the underlying XML default

**Pros:**
- User sees the full picture in one place
- No hidden conflicts
- Clear visual hierarchy

**Cons:**
- More UI complexity
- Need to load current layout's Key data

---

### Option B: "Override Mode" with Layout Preview
**Concept:** Add a toggle showing "Layout Defaults" vs "Your Customizations"

**Changes:**
1. Add tabs/toggle: "Defaults | Custom"
2. "Defaults" tab shows XML subkeys (read-only)
3. "Custom" tab shows user overrides
4. Preview always shows merged result

**Pros:**
- Clean separation of concerns
- User understands what they're overriding

**Cons:**
- Two places to look
- May still be confusing

---

### Option C: Full Merging at Load Time
**Concept:** When layout loads, merge XML subkeys into custom mappings storage with "source" flag.

**Changes:**
1. `ShortSwipeCustomizationManager` stores entries with `source: "xml" | "custom"`
2. On layout load, inject XML subkeys as `source: "xml"` entries (if no custom exists)
3. User edits always create `source: "custom"` entries
4. Single unified list in UI

**Pros:**
- Truly unified data model
- Simple UI

**Cons:**
- Complex migration
- Per-layout storage needed
- XML changes require re-sync

---

### Recommendation: Option A

Option A provides the best user experience with minimal architectural changes:

```kotlin
// In KeyCustomizationDialog, compute effective mappings:
val effectiveMappings = mutableMapOf<SwipeDirection, EffectiveMapping>()

// 1. Add XML subkeys first (from Key object)
key?.keys?.forEachIndexed { index, kv ->
    if (index in 1..8 && kv != null) {
        val dir = DIRECTION_POSITIONS[index]!!
        effectiveMappings[dir] = EffectiveMapping(
            direction = dir,
            displayText = kv.getString(),
            source = MappingSource.XML_LAYOUT,
            isOverridden = customMappings.containsKey(dir)
        )
    }
}

// 2. Overlay custom mappings (they take precedence)
customMappings.forEach { (dir, mapping) ->
    effectiveMappings[dir] = EffectiveMapping(
        direction = dir,
        displayText = mapping.displayText,
        source = MappingSource.CUSTOM,
        underlying = effectiveMappings[dir] // Preserve the XML default
    )
}
```

This gives users visibility into what the layout provides vs what they've customized, without changing the underlying storage or runtime behavior.

---

Do you want me to implement Option A, or would you prefer a different approach?

---

## User replied Option D:

> Any ShortSwipeCustomizationActivity custom mappings create a modified layout XML that replaces the current layout XML in LayoutManager, and all subkeys are displayed as 'custom mappings' in ShortSwipeCustomizationActivity and appear in the xml preview in LayoutManager. So upon first load of the app the default layout will show 4 'custom mappings' in the ShortSwipeCustomizationActivity list for L and K keys (as defined in the default layout XML). It ALSO will include 'extra keys' as it is NOT a separate system from the user's perspective - enabling the 'copy' extra key simply automatically places it in 'c' SouthEast, if that slot is available. So it should display in the ShortSwipeCustomizationActivity custom mapping list for 'c'. Any manually added custom mappings should result in the corresponding 'extra keys' entry to display as selected/checked/enabled in the 'extra keys' activity. And if removed should uncheck the corresponding field.

---

## Option D Research

### Key Findings from Codebase Analysis

#### 1. Default Layout Subkeys (latn_qwerty_us.xml)

The default QWERTY US layout has these subkeys that would appear as "custom mappings" on first load:

| Key | Direction | Value | Notes |
|-----|-----------|-------|-------|
| **K** | NE | `}` | Bracket |
| **K** | NW | `{` | Bracket |
| **K** | SW | `[` | Bracket |
| **K** | SE | `]` | Bracket |
| **L** | NW | `(` | Paren |
| **L** | NE | `)` | Paren |
| **L** | SE | `\` | Backslash |
| **L** | SW | `/` | Slash |

**Total: 8 subkeys from K and L** (4 each, as you mentioned).

Many other keys also have subkeys:
- `Q`: ne="1", nw="~", sw="`", se="loc esc"
- `I`: nw="*", ne="8"
- `M`: se="!", ne="\"", nw="?", sw="'"
- etc.

#### 2. Extra Keys Placement Mapping (ExtraKeysPreference.kt:392-410)

Current extra key → preferred position mapping:

```kotlin
"cut" -> mkPreferredPos("x", 2, 2, true)      // Next to X, row 2, col 2, bottom-right
"copy" -> mkPreferredPos("c", 2, 3, true)     // Next to C, row 2, col 3, bottom-right
"paste" -> mkPreferredPos("v", 2, 4, true)    // Next to V, row 2, col 4, bottom-right
"undo" -> mkPreferredPos("z", 2, 1, true)     // Next to Z, row 2, col 1, bottom-right
"selectAll" -> mkPreferredPos("a", 1, 0, true) // Next to A, row 1, col 0
"redo" -> mkPreferredPos("y", 0, 5, true)     // Next to Y, row 0, col 5
```

**CORRECTION**: I initially misread this as "adjacent keys". Re-examining `add_key_to_pos()` (KeyboardData.kt:111-114):

```kotlin
if (col.getKeyValue(i_dir) == null) {
    rows[i_row] = row.copy(keys = row.keys.toMutableList().apply {
        set(i_col, col.withKeyValue(i_dir, kv))  // Sets keys[i_dir] on EXISTING key!
    })
}
```

**Extra Keys ARE subkeys!** They populate `Key.keys[]` at direction `i_dir` - the SAME array as XML subkeys. When "copy" is enabled, it places `keys[4]` (SE) on the "c" key.

#### 3. All Three Systems Already Share the Same Data Model

| System | When | Where | How |
|--------|------|-------|-----|
| XML Subkeys | Parse time | `Key.keys[]` | XML attributes → array |
| Extra Keys | After parse | `Key.keys[]` | `addExtraKeys()` → array |
| Custom Mappings | Runtime | Pointers.kt check | Override before array lookup |

**The underlying data model is already unified.** The problem is the UI doesn't reflect this.

#### 4. XmlLayoutExporter Already Exists

`XmlLayoutExporter.injectMappings()` can bake custom mappings into XML:
- Parses XML DOM
- Finds `<key c="...">` elements
- Sets direction attributes: `ne`, `nw`, `se`, `sw`, `n`, `s`, `e`, `w`
- Serializes back to XML string

This infrastructure supports Option D's "modified layout XML" approach.

---

## Questions for Clarification

### Q1: Extra Keys With DEFAULT Position

Many extra keys use `PreferredPos.DEFAULT` which means "place anywhere there's an empty slot":

| Extra Key | Current Behavior |
|-----------|------------------|
| `voice_typing` | Finds any empty subkey slot on any key |
| `switch_clipboard` | Finds any empty subkey slot on any key |
| `compose` | Finds any empty subkey slot on any key |
| `accent_aigu` | Finds any empty subkey slot on any key |
| `€`, `ß`, `£` | Finds any empty subkey slot on any key |

These are ALREADY subkeys - just placed wherever there's room rather than at a specific location.

**Question**: For these "floating" extra keys, should ShortSwipeCustomizationActivity show them at their actual placed position (which varies by layout), or should we define fixed default positions for consistency?

### Q2: Slot Conflicts

What happens when an extra key's target slot is already occupied?

Example:
- Layout has `c` with `sw="."` (dot on SW)
- User enables "copy" extra key (wants SE on "c")
- SE is available → works
- But what if another layout has `c` with `se="something"`?

**Options:**
1. Skip placement (don't enable)
2. Override existing (user explicitly enabled it)
3. Find next available slot
4. Show error/warning

### Q3: Bidirectional Sync Edge Cases

If user manually adds mapping `("c", SE) → "copy"`:
- Should `extra_key_copy` checkbox become checked? ✅ (per your spec)

But what if user adds `("c", SE) → "some_other_action"`:
- Should `extra_key_copy` become unchecked?
- What if copy was at `("c", SE)` before?

And reverse: if user unchecks `extra_key_copy`:
- Should the `("c", SE) → "copy"` mapping be removed?
- What if user had manually set it (not via checkbox)?

### Q4: Layout-Specific vs Global

Current custom mappings are **global** (apply to all layouts).
XML subkeys are **per-layout**.

Option D's "modified layout XML" approach suggests per-layout storage.

**Question**: Should custom mappings become per-layout? Or should the "modified XML" be regenerated on-the-fly when layout changes?

### Q5: All Subkeys as Custom Mappings

On first load with default QWERTY layout, these would appear in ShortSwipeCustomizationActivity:

```
Q: NE→1, NW→~, SW→`, SE→esc
W: NE→2, NW→@
E: NE→3, NW→#, SE→€
R: NE→4, NW→$
T: NE→5, NW→%
Y: NE→6, NW→^
U: NE→7, NW→&
I: NW→*, NE→8
O: NE→9, SE→_, NW→-
P: NE→0, SE→|
J: NE→+, SE→=
K: NE→}, NW→{, SW→[, SE→]
L: NW→(, NE→), SE→\, SW→/
B: SE→>, SW→<
N: SE→:, SW→;
M: SE→!, NE→", NW→?, SW→'
SHIFT: NW→home, NE→end, SE→tab, SW→capslock
BACKSPACE: NE→delete, NW→delete_last_word
```

**That's 40+ entries** just from the default layout.

**Question**: Is this the intended UX? A very long list on first load? Or should we filter to only show user-modified entries with an "expand to show all" option?

---

## Proposed Architecture for Option D

```
┌─────────────────────────────────────────────────────────────────┐
│                    UnifiedSubkeyManager                         │
├─────────────────────────────────────────────────────────────────┤
│ - Merges: Layout XML + Custom Mappings + Extra Keys             │
│ - Single source of truth for all subkey data                    │
│ - Provides: getAllSubkeys(keyCode) → List<SubkeyEntry>          │
│ - Bidirectional sync with ExtraKeysPreference                   │
└─────────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
     ┌────────────┐   ┌────────────┐   ┌────────────┐
     │ XML Parser │   │ Custom     │   │ Extra Keys │
     │ (Layout)   │   │ Mappings   │   │ Preference │
     │            │   │ (JSON)     │   │ (checkboxes)│
     └────────────┘   └────────────┘   └────────────┘
```

**Data Flow:**
1. On layout load: Parse XML subkeys → populate UnifiedSubkeyManager
2. On custom mapping change: Update JSON + regenerate "modified XML"
3. On extra key toggle: Map to (key, direction) → update custom mapping → sync
4. ShortSwipeCustomizationActivity reads from UnifiedSubkeyManager
5. Pointers.kt reads from UnifiedSubkeyManager (replaces current dual lookup)

---

## Next Steps (Pending Answers)

Once you clarify the questions above, I can:
1. Define the complete extra key → subkey position mapping table
2. Design the conflict resolution strategy
3. Implement UnifiedSubkeyManager
4. Update ShortSwipeCustomizationActivity to show all subkeys
5. Add bidirectional sync with ExtraKeysPreference
6. Update Pointers.kt to use unified source

