# Profile System Restoration Implementation Details

## Overview
This document details the implementation of the Profile System Restoration, which enables importing and exporting keyboard layouts as XML files, including embedded short swipe customizations.

## Components Implemented

### 1. Backend: KeyboardData.kt
- **Reverted:** Removed `shortSwipeCustomizations` and `parse_short_swipes` to maintain standard XML compliance.
- **Parsing:** Relies on standard Unexpected Keyboard XML attributes (`nw`, `ne`, etc.) for all gestures.

### 2. Logic: ShortSwipeCustomizationManager.kt
- **New Method:** `importFromMappings(mappings: List<ShortSwipeMapping>, merge: Boolean): Int`
    - Allows importing a list of mappings directly.
    - Handles thread safety and file persistence.

### 3. Logic: XmlLayoutExporter.kt & XmlAttributeMapper.kt
- **XmlAttributeMapper:** 
    - Converts `ShortSwipeMapping` objects into Unexpected Keyboard-compatible XML attribute strings (e.g., `copy`, `keyevent:66`).
    - Maps internal `AvailableCommand` enums to legacy XML keywords.
- **XmlLayoutExporter:**
    - Parses existing layout XML.
    - Injects custom short swipe mappings directly into the corresponding `<key>` tags as standard attributes (`nw`, `se`, etc.).
    - Ensures the exported XML is a self-contained "Smart Profile" that works with legacy parsers while carrying customization data.

### 4. UI: LayoutManagerActivity.kt
- **CustomLayoutEditorDialog:**
    - Added **Import XML** and **Export XML** buttons.
    - **Import Logic:**
        - Uses `ActivityResultContracts.OpenDocument`.
        - Reads standard XML file content.
        - Loads the layout directly. Swipes defined in the XML (standard attributes) work immediately as part of the layout.
    - **Export Logic:**
        - Uses `ActivityResultContracts.CreateDocument`.
        - Retrieves current global mappings from `ShortSwipeCustomizationManager`.
        - Uses `XmlLayoutExporter` to inject these mappings into the current layout XML.
        - Writes the enhanced XML to the selected file.

## Verification
- **Build:** Successful (`./build-on-termux.sh`).
- **Functionality:**
    - Importing legacy XML layouts works (standard XML).
    - Exporting creates a file that contains both visual layout and functional swipe mappings baked into the standard attributes.
    - No proprietary tags are used, ensuring 100% compatibility with the original Unexpected Keyboard ecosystem.

## Usage
1.  Open **Settings** -> **Keyboard Layouts**.
2.  Select **Custom Layout** (or edit an existing one).
3.  Use **Export XML** to save your profile (layout + global gesture overrides).
4.  Use **Import XML** to load a profile. It loads as a standard layout with all gestures functional.
