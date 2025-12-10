# Profile System Restoration & Intercompatibility Analysis

## 1. Executive Summary

**Objective:** Restore functionality to the "Manage Keyboard Layouts" system (effectively the "Profile System") by enabling XML import/export for individual layouts and achieving intercompatibility with the "Short Swipe Customization" system.

**Current State:**
*   **Layouts:** Managed via `LayoutManagerActivity`. Custom layouts are stored as raw XML strings in SharedPreferences. Users can edit the XML text but cannot import/export files directly.
*   **Short Swipes:** Managed via `ShortSwipeCustomizationActivity`. Mappings are stored in a global `short_swipe_customizations.json` file.
*   **Disconnect:** Layouts and Short Swipe customizations are isolated. Switching layouts does not change swipe mappings, and exporting a layout does not include swipe customizations.

**Proposed Solution:**
*   **Unified Profile Format:** Extend the standard Keyboard XML format to support a `<short_swipes>` section. This allows a single XML file to act as a portable "Profile" containing both the key layout and the custom gesture mappings.
*   **Enhanced Editor:** Upgrade the `CustomLayoutEditorDialog` to support "Import XML" and "Export XML" file operations.
*   **Smart Parsing:** Update `KeyboardData` parser to recognize and handle the embedded swipe definitions, automatically applying them to the `ShortSwipeCustomizationManager` upon import.

---

## 2. Architecture Analysis

### 2.1 Layout System
*   **Core Class:** `KeyboardData` (Data Model & XML Parser).
*   **Storage:** `LayoutsPreference` (SharedPreferences).
*   **UI:** `LayoutManagerActivity` (List & Management), `CustomLayoutEditorDialog` (Text Editing).
*   **Format:** Standard "Unexpected Keyboard" XML format (rows, keys, modifiers).

### 2.2 Short Swipe System
*   **Core Class:** `ShortSwipeCustomizationManager` (Singleton).
*   **Storage:** JSON file (`short_swipe_customizations.json`).
*   **UI:** `ShortSwipeCustomizationActivity` (Visual Editor).
*   **Data Model:** `ShortSwipeMapping` (Key + Direction -> Action).

### 2.3 The Integration Gap
Currently, `KeyboardData` parses strict XML tags (`row`, `modmap`, `key`). It throws an exception on unknown tags. This prevents us from simply embedding swipe data into the XML without code changes. Furthermore, the `LayoutManagerActivity` lacks file I/O capabilities, forcing users to copy-paste text for sharing.

---

## 3. Technical Specification

### 3.1 XML Schema Extension
We will introduce a new optional root-level tag `<short_swipes>` to the keyboard layout XML.

**Example Profile XML:**
```xml
<keyboard name="My Custom Profile" script="latin">
    <row>
        <key key0="q" />
        <key key0="w" />
        <!-- ... -->
    </row>
    
    <!-- NEW SECTION -->
    <short_swipes>
        <swipe key="a" dir="NW" type="COMMAND" value="SELECT_ALL" label="All" />
        <swipe key="c" dir="S" type="TEXT" value="my@email.com" label="Mail" />
        <swipe key="x" dir="NE" type="KEY_EVENT" value="66" label="Ent" />
    </short_swipes>
</keyboard>
```

### 3.2 Parsing Logic (`KeyboardData.kt`)
*   The `parse_keyboard` method will be updated to handle the `short_swipes` tag.
*   A new `parse_short_swipes` method will iterate through `swipe` tags and construct `ShortSwipeMapping` objects.
*   These mappings will be stored in a new field `val shortSwipeCustomizations: List<ShortSwipeMapping>?` in `KeyboardData`.

### 3.3 Import Logic (`LayoutManagerActivity`)
*   **User Action:** Clicks "Import XML".
*   **System Action:**
    1.  Reads the file content.
    2.  Validates XML structure using `KeyboardData.load_string_exn`.
    3.  If valid, populates the editor text field.
    4.  **Crucial Step:** If the parsed `KeyboardData` contains `shortSwipeCustomizations`, prompt the user: "This layout contains custom swipe gestures. Import them?"
    5.  If confirmed, call `ShortSwipeCustomizationManager.importFromMappings(...)` to merge or replace global mappings.

### 3.4 Export Logic (`LayoutManagerActivity`)
*   **User Action:** Clicks "Export XML".
*   **System Action:**
    1.  Get the current XML text from the editor.
    2.  Parse it to finding all defined keys (or just use all available global swipe mappings).
    3.  Query `ShortSwipeCustomizationManager` for all active mappings.
    4.  Inject the `<short_swipes>...</short_swipes>` block into the XML string (just before `</keyboard>`).
    5.  Write the enhanced XML to the selected file destination.

---

## 4. Implementation Todo Steps

### Phase 1: Core Data & Parsing (Backend)
- [ ] **Modify `KeyboardData.kt`**:
    - [ ] Import `ShortSwipeMapping`, `ActionType`, `SwipeDirection`.
    - [ ] Add `shortSwipeCustomizations` property to `KeyboardData` class.
    - [ ] Update `parse_keyboard` loop to handle `"short_swipes"` tag.
    - [ ] Implement `parse_short_swipes(parser)`:
        - [ ] Iterate `swipe` child tags.
        - [ ] Parse attributes: `key`, `dir`, `type`, `value`, `label`.
        - [ ] Convert strings to Enums (`SwipeDirection`, `ActionType`).
        - [ ] Return list of `ShortSwipeMapping`.

### Phase 2: Manager Integration
- [ ] **Modify `ShortSwipeCustomizationManager.kt`**:
    - [ ] Add `importFromMappings(mappings: List<ShortSwipeMapping>, merge: Boolean): Int` function.
    - [ ] Ensure it handles saving to disk immediately.

### Phase 3: UI Implementation (Frontend)
- [ ] **Modify `LayoutManagerActivity.kt`**:
    - [ ] Update `CustomLayoutEditorDialog` composable.
    - [ ] Add `FilePicker` launchers (`rememberLauncherForActivityResult`) for Import (Open) and Export (Create).
    - [ ] Add **"Import XML"** button to the dialog action row.
    - [ ] Add **"Export XML"** button to the dialog action row.
    - [ ] Implement `onImport`:
        - [ ] Read file.
        - [ ] Parse with `KeyboardData`.
        - [ ] Check for swipes.
        - [ ] If swipes exist, show Confirmation Dialog ("Import X custom gestures?").
        - [ ] Update Editor Text.
    - [ ] Implement `onExport`:
        - [ ] Parse current Editor Text to ensure validity.
        - [ ] Retrieve all global swipe mappings from `ShortSwipeCustomizationManager`.
        - [ ] Construct XML string with embedded `<short_swipes>` block.
        - [ ] Write to file.

### Phase 4: Verification
- [ ] **Test Import**: Import a layout with swipes; verify swipes appear in `ShortSwipeCustomizationActivity`.
- [ ] **Test Export**: Export a layout; verify the file contains `<short_swipes>` tag.
- [ ] **Test Interoperability**: Verify the layout loads correctly even in older versions (older parsers *should* fail on unknown tags, so this is a breaking change for backward compatibility with *pure* Unexpected Keyboard, but acceptable for CleverKeys internal profiles).
    - *Mitigation:* We are extending the format. If users export to standard UK format, they should manually remove the tag or we provide a "Export Strict" option (Low Priority).

---

## 5. Security & Safety
- **File Access:** Uses Android Storage Access Framework (SAF) for secure file reading/writing. No broad storage permissions required.
- **Validation:** XML is parsed and validated before application to prevent malformed data crashes.
