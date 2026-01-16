# Language-Specific Dictionary Manager

## Overview

Per-language storage for custom and disabled words. Each language has its own word lists so that customizations in one language don't affect others.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/OptimizedVocabulary.kt` | `loadDisabledWords()`, `loadCustomWords()` | Language-aware loading |
| `src/main/kotlin/tribixbite/cleverkeys/LanguagePreferenceKeys.kt` | `customWordsKey()`, `disabledWordsKey()` | Key generation |
| `src/main/kotlin/tribixbite/cleverkeys/DisabledDictionarySource.kt` | Constructor parameter | Language code support |
| `src/main/kotlin/tribixbite/cleverkeys/ui/DictionaryManagerActivity.kt` | Tab generation | Multi-language UI |

## Storage Format

### Current (Global)
```
custom_words = {"word1": freq, "word2": freq, ...}
disabled_words = ["word1", "word2", ...]
```

### New (Per-Language)
```
custom_words_en = {"word1": freq, ...}
custom_words_es = {"café": freq, ...}
disabled_words_en = ["word1", ...]
disabled_words_es = ["palabra", ...]
```

## Implementation

### Key Generation

```kotlin
object LanguagePreferenceKeys {
    fun customWordsKey(languageCode: String) = "custom_words_$languageCode"
    fun disabledWordsKey(languageCode: String) = "disabled_words_$languageCode"

    fun migrateToLanguageSpecific(prefs: SharedPreferences, targetLang: String = "en") {
        // Copy global data to language-specific keys
        val version = prefs.getInt("lang_pref_migration_version", 0)
        if (version >= 1) return

        val globalCustom = prefs.getString("custom_words", null)
        val globalDisabled = prefs.getStringSet("disabled_words", null)

        prefs.edit().apply {
            if (globalCustom != null) putString(customWordsKey(targetLang), globalCustom)
            if (globalDisabled != null) putStringSet(disabledWordsKey(targetLang), globalDisabled)
            putInt("lang_pref_migration_version", 1)
        }.apply()
    }
}
```

### OptimizedVocabulary Integration

```kotlin
// Uses _primaryLanguageCode field from preferences
fun loadDisabledWords() {
    val key = LanguagePreferenceKeys.disabledWordsKey(_primaryLanguageCode)
    val words = prefs.getStringSet(key, emptySet())
    // Load into disabled set
}

fun loadCustomWords() {
    val key = LanguagePreferenceKeys.customWordsKey(_primaryLanguageCode)
    val json = prefs.getString(key, "{}")
    // Parse and load
}
```

### DisabledDictionarySource

```kotlin
class DisabledDictionarySource(
    private val prefs: SharedPreferences,
    private val languageCode: String? = null  // null = global fallback
) {
    fun getWords(): Set<String> {
        val key = if (languageCode != null) {
            LanguagePreferenceKeys.disabledWordsKey(languageCode)
        } else {
            "disabled_words"
        }
        return prefs.getStringSet(key, emptySet()) ?: emptySet()
    }
}
```

## UI - DictionaryManagerActivity

### Tab Generation

When multilang enabled with secondary language:

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

### Implementation

```kotlin
fun setupViewPager() {
    val languages = mutableListOf(_primaryLanguageCode)
    if (_secondaryLanguageCode != "none") {
        languages.add(_secondaryLanguageCode)
    }

    for (lang in languages) {
        addTab("Active [$lang]", WordListFragment.newInstance(ACTIVE, lang))
        addTab("Disabled [$lang]", WordListFragment.newInstance(DISABLED, lang))
        addTab("Custom [$lang]", WordListFragment.newInstance(CUSTOM, lang))
    }

    // Use scrollable tabs when > 4 tabs
    if (tabLayout.tabCount > 4) {
        tabLayout.tabMode = TabLayout.MODE_SCROLLABLE
    }
}
```

## Data Sources

| Source | Description |
|--------|-------------|
| `MainDictionarySource` | Active words from vocabulary |
| `DisabledDictionarySource` | Words excluded from predictions |
| `CustomDictionarySource` | User-added words with frequencies |
| `UserDictionarySource` | Android system user dictionary (global) |

## Migration

- On first launch after update, `migrateToLanguageSpecific()` copies global data to "en" keys
- Uses `lang_pref_migration_version` flag to track migration status
- Android User Dictionary remains global (system-level)

## Backup/Restore

All language-specific keys exported:
```json
{
  "custom_words_en": {...},
  "custom_words_es": {...},
  "disabled_words_en": [...],
  "disabled_words_es": [...]
}
```
