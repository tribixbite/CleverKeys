# Clipboard Manual Test Guide

Comprehensive edge-case testing for the clipboard system. Use a text app
(Notes, Chrome URL bar, Termux) to type into while testing.

## Prerequisites
- Release APK installed via `./build-on-termux.sh`
- At least 3 text items in clipboard history
- At least 1 pinned entry and 1 todo entry

---

## 1. Filter Dialog

### 1.1 Tab-Specific Sections
- [ ] HISTORY tab → filter icon → shows date section ONLY (no tags/status)
- [ ] PINNED tab → filter icon → shows date + tags sections (no status)
- [ ] TODOS tab → filter icon → shows date + status + tags sections

### 1.2 Filter Logic
- [ ] Date filter: set start date → only entries after that date appear
- [ ] Date filter: set end date → only entries before that date appear
- [ ] Date range: start > end → shows no results (not a crash)
- [ ] Status filter (TODOS): uncheck "active" → active todos hidden
- [ ] Status filter (TODOS): check "planned" only → only planned todos shown
- [ ] Status filter: can't Apply with ALL status checkboxes unchecked
- [ ] Tag filter (PINNED/TODOS): check one tag → only entries with that tag
- [ ] Tag filter: OR mode → entries with ANY checked tag shown
- [ ] Tag filter: AND mode → only entries with ALL checked tags shown
- [ ] Clear button → all filters reset, full list restored

### 1.3 Filter Icon Tint (BUG FIX — 23881fe20)
- [ ] No filter active → icon is normal label color (white in dark themes)
- [ ] Filter active → icon tints to activated color (blue in dark themes)
- [ ] Clear filters → icon returns to normal color
- [ ] Switch themes → icon colors follow the new theme (not always cyan)

---

## 2. Tag Panel

### 2.1 Basic Operations
- [ ] Expand pinned entry → tap tags button → tag panel opens
- [ ] Tag panel: entry list is hidden, tag panel is visible
- [ ] Type tag name → press add → chip appears
- [ ] Tap chip X → tag removed from entry
- [ ] Close panel → entry list restored, tags saved

### 2.2 Input Routing
- [ ] With tag panel open, type on keyboard → text goes to tag EditText (not to the app behind)
- [ ] Backspace in tag EditText → deletes tag text (not sent to app)
- [ ] Close tag panel → typing resumes going to the app

### 2.3 Mutual Exclusion
- [ ] Open search → open tag panel → search clears/closes
- [ ] Open edit mode → open tag panel → edit exits
- [ ] Open tag panel → tap different tab → tag panel closes

---

## 3. Edit Mode

### 3.1 Basic Editing
- [ ] Tap pencil icon → entry becomes editable (EditText visible)
- [ ] Type text → appears in EditText
- [ ] Backspace → deletes from EditText
- [ ] Save → entry updated in list and database
- [ ] Cancel → changes discarded, original content restored

### 3.2 Cursor & Selection
- [ ] Tap inside EditText → cursor repositions to tap location
- [ ] Arrow keys → cursor moves within EditText
- [ ] Enter key → newline inserted, cursor moves down
- [ ] Select-all (Ctrl+A in Termux) → all text selected
- [ ] Paste → inserts at cursor

### 3.3 Cross-Tab Editing
- [ ] Edit in HISTORY tab → save → content updated
- [ ] Edit in PINNED tab → save → content updated
- [ ] Edit in TODOS tab → save → content updated
- [ ] Edit + delete entry → edit mode exits, entry removed

### 3.4 UI Lockdown During Edit
- [ ] Search bar disabled during edit
- [ ] Tab switching blocked during edit
- [ ] Pagination blocked during edit
- [ ] Filter button blocked during edit
- [ ] Other entry action buttons blocked during edit

### 3.5 ListView Scrap View Edge Case
- [ ] Edit entry → press Enter (height change) → continue typing → text still appears
- [ ] Edit long entry → scroll away → scroll back → editing still works

---

## 4. Backup & Restore

### 4.1 JSON Export (Text-Only)
- [ ] Settings → Activities → Backup & Restore → Export Clipboard (JSON)
- [ ] File saved successfully (check toast/notification)
- [ ] Open JSON file → verify history entries present with content + timestamps
- [ ] Verify pinned entries section exists with position field
- [ ] Verify todo entries section exists with status field (active/planned/completed)
- [ ] Verify tags are present as JSON string (e.g. `"tags":"[\"work\"]"`)
- [ ] Media entries are SKIPPED in JSON export (expected — use ZIP for media)

### 4.2 JSON Import
- [ ] Clear clipboard → import the JSON file
- [ ] History entries restored with correct content
- [ ] Pinned entries restored independently (not duplicated in history)
- [ ] Todo entries restored with correct status (active/planned/completed)
- [ ] Tags survive roundtrip — verify tagged entries still have their tags
- [ ] Duplicate detection: import same file again → skipped count matches

### 4.3 ZIP Export (Full Backup with Media)
- [ ] Copy an image to clipboard → verify it appears in history
- [ ] Export as ZIP → file saved
- [ ] Inspect ZIP: contains clipboard_data.json + media files in clipboard_media/

### 4.4 ZIP Import
- [ ] Clear clipboard → import the ZIP
- [ ] Text entries restored
- [ ] Media entries restored with thumbnails visible
- [ ] Pinned/todo entries with tags restored correctly
- [ ] Orphaned media files cleaned up after import

### 4.5 Edge Cases
- [ ] Import v2 format (no tags/status columns) → defaults applied, no crash
- [ ] Import with invalid todo status → falls back to "active" (fix 23881fe20)
- [ ] Import empty export file → 0 imported, no crash
- [ ] Export with 0 entries → valid empty JSON structure

---

## 5. Media Clipboard

### 5.1 Capture
- [ ] Copy image from browser → appears in clipboard history with thumbnail
- [ ] Copy text from app → appears as normal text entry
- [ ] Media toggle OFF (Settings) → images not captured, text still captured

### 5.2 Rendering
- [ ] Image entries show 80x80 thumbnail
- [ ] Video entries show thumbnail with play badge
- [ ] PDF entries show first-page thumbnail
- [ ] Unknown MIME type shows generic file icon
- [ ] Text-only mode hides all media entries

### 5.3 Paste
- [ ] Tap image entry → image pasted into compatible app (e.g. messaging)
- [ ] If app doesn't support commitContent → falls back to URI clipboard

### 5.4 Pin/Todo Media
- [ ] Pin an image entry → appears in PINNED tab with thumbnail
- [ ] Add image to todos → appears in TODOS tab with thumbnail
- [ ] Unpin → thumbnail removed from PINNED, still in history if not expired

---

## 6. Search

### 6.1 Basic Search
- [ ] Type in search bar → results filter in real-time
- [ ] Clear search → full list restored
- [ ] Search with no results → empty state shown

### 6.2 Regex Mode
- [ ] Tap `.*` toggle → regex mode enabled
- [ ] Type `^http` → only entries starting with "http" shown
- [ ] Type `*` (glob shorthand) → expands to `.*`, matches everything
- [ ] Invalid regex (e.g. `[invalid`) → red text, no crash, matches nothing
- [ ] Toggle off → back to substring matching

### 6.3 Search + Other Modes
- [ ] Search active → enter edit mode → search input disabled but filter kept
- [ ] Search active → open tag panel → search clears

---

## 7. Entry Actions

### 7.1 History Tab
- [ ] Tap entry → pastes to active app
- [ ] Long-press entry → copies to system clipboard (toast confirmation)
- [ ] Expand → pin button works
- [ ] Expand → todo button works

### 7.2 Pinned Tab
- [ ] Expand → unpin button returns entry to history only
- [ ] Expand → todo button works
- [ ] Expand → tags button opens tag panel

### 7.3 Todos Tab
- [ ] Expand → done button toggles active↔completed (strikethrough + [done] prefix)
- [ ] Expand → status cycle: active → planned → completed
- [ ] Expand → tags button opens tag panel

### 7.4 Pagination
- [ ] Add 100+ history entries → "Next" button appears at bottom
- [ ] Next → page 2 loads, "Prev" button appears
- [ ] Prev → back to page 1

---

## 8. Theme Consistency
- [ ] Switch to Light theme → filter icon, tags, checkboxes use dark colors
- [ ] Switch to Dark theme → filter icon, tags, checkboxes use light colors
- [ ] Switch to RosePine/Everforest → accent colors match theme palette
- [ ] Monet theme → colors follow system dynamic color
