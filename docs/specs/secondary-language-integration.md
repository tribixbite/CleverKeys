# Secondary Language Integration Specification

## Feature Overview
**Feature Name**: Secondary Language Integration (Dual-Dictionary Mode)
**Priority**: P2 (Medium)
**Status**: Proposal
**Target Version**: v1.3.0

### Summary
This feature allows users to import a second dictionary (word list) that functions simultaneously with the primary language. This enables bilingual typing (e.g., English + Spanish) on the existing QWERTY layout without requiring the user to manually switch languages or layouts.

### Motivation
Many users type in two languages interchangeably. Switching language packs completely (which changes the layout and neural models) is friction-heavy for single sentences or mixed code-switching. A "Secondary Language" feature allows the prediction engine to suggest words from *both* languages at once, relying on the primary language's layout and neural model.

### Constraints
*   **Layout Compatibility**: The secondary language must be compatible with the current active layout (e.g., Latin script languages like French, Spanish, German, Portuguese on a US QWERTY layout).
*   **Neural Model Reuse**: We will **reuse** the active language's neural model (likely English). This works because the geometric trace for "HOLA" is identical on a QWERTY keyboard regardless of whether the underlying model "knows" Spanish, provided the dictionary validates the word "HOLA".
*   **Scope**: Only one secondary language active at a time.

---

## 1. User Experience

### 1.1 Importing a Secondary Language
*   **Settings Location**: `Settings -> Languages -> Secondary Language`.
*   **Action**: "Import Dictionary File".
*   **File Format**: Simple text file (one word per line) or JSON (`{"word": freq}`).
*   **UI Feedback**: Toast confirming import count (e.g., "Imported 35,000 words into Secondary Dictionary").

### 1.2 Managing the Dictionary
*   **Location**: `Dictionary Manager` (via "Manage Custom Words").
*   **New Tab**: A "Secondary" tab is added between "User Dict" and "Disabled".
*   **Functionality**:
    *   View all words in the secondary dictionary.
    *   Search/Filter.
    *   Delete specific words (if they conflict with primary language words).
    *   "Clear All" option to remove the secondary language.

### 1.3 Typing Experience
*   When typing/swiping, suggestions appear from *both* the Primary (Main + User + Custom) and Secondary dictionaries.
*   No UI toggle is needed while typing.
*   Prediction ranking handles conflicts (e.g., if "pie" exists in both, the frequency/context determines the winner).

---

## 2. Technical Implementation

### 2.1 Data Persistence
*   **Storage**: A dedicated file `secondary_dictionary.bin` (or `.json`) in app-private storage.
*   **Separation**: kept distinct from `user_dictionary` (Custom Words) to allow easy bulk-deletion or swapping of the secondary language without wiping user-taught words.

### 2.2 New Data Source
A new implementation of `DictionaryDataSource`:

```kotlin
class SecondaryDictionarySource(context: Context) : DictionaryDataSource {
    // Reads from files/secondary_dictionary.json
    // Supports read (getAll, search) and delete
    // 'add' might be restricted to bulk imports
}
```

### 2.3 Dictionary Manager UI Updates
*   Update `DictionaryManagerActivity.kt` to include `TabType.SECONDARY`.
*   Update `WordListFragment.kt` to instantiate `SecondaryDictionarySource`.
*   Update `BackupRestoreManager.kt` to support exporting/importing this specific file separately (optional but good practice).

### 2.4 WordPredictor Integration
The `WordPredictor` currently relies on a single `dictionary` map and `prefixIndex`. To support this feature efficiently:

1.  **Dual Index Strategy**:
    *   Maintain a separate `secondaryPrefixIndex` in memory.
    *   **Reason**: Allows instant enabling/disabling of the secondary language without rebuilding the massive primary index.

2.  **Candidate Generation**:
    *   In `predictInternal()`, query both `prefixIndex` (Primary) and `secondaryPrefixIndex` (Secondary).
    *   Merge candidate lists.

3.  **Scoring**:
    *   Apply the same `calculateUnifiedScore` logic.
    *   *Optional Tuning*: Add a small penalty (e.g., 0.9x multiplier) to secondary language words to prefer the primary language in tie-breaking scenarios (e.g., "fin" in English vs "fin" in French).

### 2.5 Schema
No database schema changes required. File-based persistence preferred for portability and size management.

---

## 3. Workflow Example: "Franglais" Support

1.  User is on **English (US)** layout.
2.  User downloads `french_common_words.txt`.
3.  User goes to **Settings > Secondary Language > Import**.
4.  App parses 20,000 French words and saves to `secondary_dictionary.json`.
5.  User opens **Dictionary Manager**; sees new "Secondary" tab populated.
6.  User types "bonjour".
    *   `WordPredictor` checks English dict: No match.
    *   `WordPredictor` checks Secondary dict: Match found!
    *   Suggestion "Bonjour" appears.
7.  User types "chat".
    *   English dict: "chat" (talk).
    *   Secondary dict: "chat" (cat).
    *   Result: "chat" appears. (Context/Frequency determines priority).
