# Bug #473: Tabbed Dictionary Manager Specification

**Date**: November 16, 2025
**Severity**: 🟡 **HIGH** (P1 - Feature enhancement for v1.0)
**Status**: 📝 **SPECIFICATION READY** → **IMPLEMENTING**

---

## 🎯 User Request

> "it should include built in 50k dict disabled tab user dict tab review java more thoroughly"

### Requirements Parsed:
1. ✅ **Built-in dictionary tab** - Browse ~10k built-in words (assets/dictionaries/en.txt)
2. ✅ **Disabled words tab** - Blacklist words from appearing in predictions
3. ✅ **User dictionary tab** - Custom words (already implemented, needs to be in tab)
4. ✅ **Tabbed interface** - Material 3 TabLayout with 3 tabs

**Note**: User said "50k dict" but actual file has 9,999 words (~10k). Close enough.

---

## 📁 Dictionary Files Found

### In `assets/dictionaries/`:
- `en.txt` - 9,999 words (English)
- `en_enhanced.txt` - 9,999 words (Enhanced English)
- `de.txt` - 58 words (German)
- `es.txt` - 58 words (Spanish)
- `fr.txt` - 58 words (French)

**Total**: ~20k words across all languages

---

## 🏗️ Architecture Specification

### Tab Structure

```
DictionaryManagerActivity (TabLayout)
├── Tab 1: User Dictionary
│   ├── LazyColumn of custom words
│   ├── FAB to add word
│   ├── Delete button per word
│   ├── Search field (to be added)
│   └── Word count
├── Tab 2: Built-in Dictionary (10k words)
│   ├── LazyColumn of all built-in words
│   ├── Search/filter field
│   ├── "Disable" button per word
│   ├── Language selector (EN, DE, ES, FR)
│   └── Word count
└── Tab 3: Disabled Words (Blacklist)
    ├── LazyColumn of disabled words
    ├── "Enable" button per word
    ├── Search field
    └── Word count
```

---

## 📝 Detailed Specifications

### Tab 1: User Dictionary (Enhance Existing)

**Current Implementation** (DictionaryManagerActivity.kt):
- ✅ Add custom words
- ✅ Delete custom words
- ✅ Validation (empty, short, duplicate)
- ✅ Alphabetical sorting
- ✅ Empty state UI

**Enhancements for Tab**:
- [ ] Add search/filter field at top
- [ ] Real-time filtering of user words
- [ ] "Export" button (save to file)
- [ ] "Import" button (load from file)

**Backend**: Already uses `DictionaryManager.addUserWord()`, `removeUserWord()`

---

### Tab 2: Built-in Dictionary (NEW)

**Purpose**: Browse and optionally disable built-in dictionary words

**Features**:
- [ ] Load `assets/dictionaries/en.txt` (9,999 words)
- [ ] Display in LazyColumn with virtualization
- [ ] Search/filter field at top (real-time)
- [ ] Each word shows:
  - Word text
  - Frequency rank (position in file = frequency)
  - "Disable" button (adds to blacklist)
- [ ] Language selector dropdown (EN/DE/ES/FR)
- [ ] Word count: "Showing 9,999 words"
- [ ] After search: "Showing 42 of 9,999 words"

**Implementation**:
```kotlin
data class DictionaryWord(
    val word: String,
    val rank: Int,
    val language: String
)

class BuiltInDictionaryViewModel {
    private var allWords: List<DictionaryWord> = emptyList()
    var filteredWords by mutableStateOf<List<DictionaryWord>>(emptyList())
    var selectedLanguage by mutableStateOf("en")

    fun loadDictionary(language: String) {
        // Load from assets/dictionaries/{language}.txt
        val inputStream = context.assets.open("dictionaries/$language.txt")
        allWords = inputStream.bufferedReader().useLines { lines ->
            lines.mapIndexed { index, word ->
                DictionaryWord(word.trim(), index + 1, language)
            }.toList()
        }
        filteredWords = allWords
    }

    fun filterWords(query: String) {
        filteredWords = if (query.isBlank()) {
            allWords
        } else {
            allWords.filter { it.word.contains(query, ignoreCase = true) }
        }
    }

    fun disableWord(word: String) {
        // Add to disabled words list
        disabledWordsManager.addDisabledWord(word)
    }
}
```

---

### Tab 3: Disabled Words (NEW)

**Purpose**: Manage blacklisted words that won't appear in predictions

**Features**:
- [ ] List of all disabled words
- [ ] Search/filter field
- [ ] "Enable" button per word (removes from blacklist)
- [ ] "Clear All" button
- [ ] Word count: "X words disabled"
- [ ] Integration with prediction system (filter out disabled words)

**Backend** (NEW class needed):
```kotlin
class DisabledWordsManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("disabled_words", Context.MODE_PRIVATE)
    private val disabledWords = mutableSetOf<String>()

    init {
        loadDisabledWords()
    }

    fun addDisabledWord(word: String) {
        disabledWords.add(word.lowercase())
        saveDisabledWords()
    }

    fun removeDisabledWord(word: String) {
        disabledWords.remove(word.lowercase())
        saveDisabledWords()
    }

    fun isWordDisabled(word: String): Boolean {
        return disabledWords.contains(word.lowercase())
    }

    fun getDisabledWords(): Set<String> {
        return disabledWords.toSet()
    }

    fun clearAll() {
        disabledWords.clear()
        saveDisabledWords()
    }

    private fun loadDisabledWords() {
        disabledWords.clear()
        disabledWords.addAll(prefs.getStringSet("words", emptySet()) ?: emptySet())
    }

    private fun saveDisabledWords() {
        prefs.edit().putStringSet("words", disabledWords).apply()
    }
}
```

**Integration with Predictions**:
Modify `DictionaryManager.getPredictions()` to filter out disabled words:
```kotlin
fun getPredictions(keySequence: String): List<String> {
    val predictions = currentPredictor.predictWords(keySequence)
    val disabledWordsManager = DisabledWordsManager(context)

    return predictions.filter { word ->
        !disabledWordsManager.isWordDisabled(word)
    }
}
```

---

## 🎨 UI Implementation

### Material 3 TabLayout

```kotlin
@Composable
fun DictionaryManagerScreen() {
    var selectedTabIndex by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("Dictionary Manager") })
                TabRow(selectedTabIndex = selectedTabIndex) {
                    Tab(
                        selected = selectedTabIndex == 0,
                        onClick = { selectedTabIndex = 0 },
                        text = { Text("User Words") }
                    )
                    Tab(
                        selected = selectedTabIndex == 1,
                        onClick = { selectedTabIndex = 1 },
                        text = { Text("Built-in (10k)") }
                    )
                    Tab(
                        selected = selectedTabIndex == 2,
                        onClick = { selectedTabIndex = 2 },
                        text = { Text("Disabled") }
                    )
                }
            }
        }
    ) { paddingValues ->
        when (selectedTabIndex) {
            0 -> UserDictionaryTab()
            1 -> BuiltInDictionaryTab()
            2 -> DisabledWordsTab()
        }
    }
}
```

---

## 📦 Files to Create/Modify

### New Files:
1. `DisabledWordsManager.kt` (~150 lines) - Backend for blacklist
2. `BuiltInDictionaryTab.kt` (~200 lines) - Tab 2 Composable
3. `DisabledWordsTab.kt` (~150 lines) - Tab 3 Composable
4. `UserDictionaryTab.kt` (~250 lines) - Extract current UI to tab

### Modified Files:
1. `DictionaryManagerActivity.kt` - Convert to TabLayout
2. `DictionaryManager.kt` - Add disabled words filtering to getPredictions()
3. `strings.xml` - Add ~30 new strings

### Estimated Additions:
- Code: ~750 lines
- i18n strings: ~30
- Testing: All 3 tabs + integration

---

## 🧪 Testing Strategy

### Test Tab 1: User Dictionary
- [ ] Add/delete custom words
- [ ] Search/filter user words
- [ ] Validation (empty, short, duplicate)
- [ ] Export/import user dictionary

### Test Tab 2: Built-in Dictionary
- [ ] Browse 10k words
- [ ] Search for specific words
- [ ] Switch languages (EN, DE, ES, FR)
- [ ] Disable word → moves to Tab 3
- [ ] Performance with 10k items (virtualization)

### Test Tab 3: Disabled Words
- [ ] List shows disabled words
- [ ] Search/filter disabled words
- [ ] Enable word → returns to predictions
- [ ] Clear all disabled words
- [ ] Verify disabled words DON'T appear in keyboard predictions

### Integration Test:
1. Add custom word "Test123" in Tab 1
2. Type "Test" in text field → "Test123" appears in predictions ✅
3. Disable "Test123" in Tab 1 or Tab 2 → moves to Tab 3
4. Type "Test" again → "Test123" does NOT appear ✅
5. Enable "Test123" in Tab 3
6. Type "Test" → "Test123" appears again ✅

---

## ⏱️ Implementation Estimate

### Phase 1: Tab Structure (2 hours)
- [ ] Convert DictionaryManagerActivity to TabLayout
- [ ] Create 3 tab composables
- [ ] Extract current UI to UserDictionaryTab
- [ ] Tab navigation and state

### Phase 2: Built-in Dictionary Tab (3 hours)
- [ ] Load dictionary files from assets
- [ ] LazyColumn with virtualization
- [ ] Search/filter implementation
- [ ] Language selector
- [ ] "Disable" button per word

### Phase 3: Disabled Words (2 hours)
- [ ] DisabledWordsManager backend
- [ ] DisabledWordsTab UI
- [ ] "Enable" button functionality
- [ ] Integration with DictionaryManager.getPredictions()

### Phase 4: Testing & Polish (1 hour)
- [ ] Test all tabs
- [ ] Test tab switching
- [ ] Test prediction filtering
- [ ] Performance testing with 10k words

**Total Effort**: ~8 hours

---

## 🚀 Implementation Plan

### Priority: HIGH (P1 - User explicitly requested)

**Start**: Now
**Target Completion**: Today (November 16, 2025)
**Testing**: After implementation (30-45 minutes)

### Steps:
1. ✅ Specification complete
2. ⏳ Implement Phase 1 (Tab structure)
3. ⏳ Implement Phase 2 (Built-in dict)
4. ⏳ Implement Phase 3 (Disabled words)
5. ⏳ Testing
6. ⏳ Build APK and deploy

---

## 📊 Summary

**Current**: Basic single-view dictionary manager
**Target**: 3-tab dictionary manager with 10k built-in dict + blacklist
**Effort**: ~8 hours
**Status**: Starting implementation now

---

**End of Specification**
