# Language-Specific Dictionary Manager

**Status**: In Progress
**Version**: v1.1.86+
**Last Updated**: 2026-01-05

## Overview

Split custom and disabled word storage by language so that each language has its own word lists.

## Current State

### Storage Keys (Global)
```
custom_words = {"word1": freq, "word2": freq, ...}  // JSON object
disabled_words = ["word1", "word2", ...]            // StringSet
```

### Data Sources
- `MainDictionarySource` - Active words from vocabulary
- `DisabledDictionarySource` - Words excluded from predictions
- `CustomDictionarySource` - User-added words with frequencies
- `UserDictionarySource` - Android system user dictionary

## Proposed Changes

### Phase 1: Language-Specific Storage Keys

Change preference keys to include language code:
```
custom_words_en = {"word1": freq, ...}
custom_words_es = {"café": freq, ...}
disabled_words_en = ["word1", ...]
disabled_words_es = ["palabra", ...]
```

**Files to Modify:**
1. `OptimizedVocabulary.kt` - Use language-specific keys for loading custom/disabled words
2. `DisabledDictionarySource.kt` - Accept language code parameter
3. `CustomDictionarySource.kt` - Create language-specific variant or add parameter
4. `BackupRestoreManager.kt` - Export/import all language-specific data

### Phase 2: UI - Language-Specific Tabs

When multilang is enabled with a secondary language:

**Before:** `Active | Disabled | User Dict | Custom`

**After:** `Active [EN] | Disabled [EN] | Custom [EN] | Active [ES] | Disabled [ES] | Custom [ES]`

Or with collapsible sections:
```
▼ English
  - Active
  - Disabled
  - Custom
▼ Spanish
  - Active
  - Disabled
  - Custom
```

**Files to Modify:**
1. `DictionaryManagerActivity.kt` - Dynamic tab generation
2. `WordListFragment.kt` - Accept language code parameter
3. `activity_dictionary_manager.xml` - May need scrollable tabs

## Implementation Plan

### Step 1: Create Language Key Helper ✅ DONE
```kotlin
object LanguagePreferenceKeys {
    fun customWordsKey(languageCode: String) = "custom_words_$languageCode"
    fun disabledWordsKey(languageCode: String) = "disabled_words_$languageCode"
}
```
Implemented in `LanguagePreferenceKeys.kt`.

### Step 2: Modify OptimizedVocabulary ✅ DONE
- Uses `_primaryLanguageCode` field (from primary language preference)
- `loadDisabledWords()` uses `LanguagePreferenceKeys.disabledWordsKey()`
- Custom words loading uses `LanguagePreferenceKeys.customWordsKey()`
- Migration runs via `LanguagePreferenceKeys.migrateToLanguageSpecific()`

### Step 3: Modify Data Sources ✅ DONE
- `DisabledDictionarySource` accepts optional `languageCode` parameter
- Uses language-specific preference keys when languageCode provided

### Step 4: Update DictionaryManagerActivity ✅ DONE
- `loadLanguagePreferences()` reads primary/secondary from prefs
- `setupViewPager()` generates tabs dynamically based on enabled languages
- WordListFragment receives language code for language-aware data sources
- Tab layout uses MODE_SCROLLABLE when >4 tabs

### Step 5: Migration ✅ DONE
- `LanguagePreferenceKeys.migrateToLanguageSpecific()` copies global data to "en" keys
- Uses `lang_pref_migration_version` flag to track migration status

## Testing

1. Clean install: Verify new language-specific keys are used
2. Upgrade: Verify existing data migrated to English keys
3. Multilang: Verify each language has independent custom/disabled lists
4. Backup/Restore: Verify all language data exported and imported correctly

## Notes

- Primary language currently defaults to English (NN only supports 26 letters)
- Secondary language is user-selectable (ES, FR, PT, IT, DE, etc.)
- User Dict (Android system) is global and not language-specific
