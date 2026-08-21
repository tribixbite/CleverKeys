# Language-Specific Dictionary Manager

## Overview

Per-language storage for custom and disabled words. Each language has its own word lists so that customizations in one language don't affect others.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/WordPredictor.kt` | `loadDisabledWords()`, custom-word loading | Language-aware loading for tap prediction (`OptimizedVocabulary`, the neural-era consumer, was deleted 2026-08-18 — ADR-011) |
| `src/main/kotlin/tribixbite/cleverkeys/swipe/CtcEngineAdapter.kt` / `swipe/GeometricEngineAdapter.kt` | Lexicon merge (`CtcLexiconMerge`) | Swipe engines read the same per-language keys: custom words − disabled words |
| `src/main/kotlin/tribixbite/cleverkeys/LanguagePreferenceKeys.kt` | `customWordsKey()`, `disabledWordsKey()` | Key generation |
| `src/main/kotlin/tribixbite/cleverkeys/DisabledDictionarySource.kt` | Constructor parameter | Language code support |
| `src/main/kotlin/tribixbite/cleverkeys/DictionaryManagerActivity.kt` | Tab generation | Multi-language UI |

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

### Consumer Integration

`OptimizedVocabulary` (the original consumer of these keys) was deleted with the neural
engine on 2026-08-18 (ADR-011). The per-language keys are now read in two places:

```kotlin
// WordPredictor.kt (tap prediction) — language-specific keys since v1.1.92:
private fun loadDisabledWords() {
    val disabledWordsKey = LanguagePreferenceKeys.disabledWordsKey(currentLanguage)
    val disabledSet = prefs.getStringSet(disabledWordsKey, emptySet()) ?: emptySet()
    // ...
}
// custom words: prefs.getString(LanguagePreferenceKeys.customWordsKey(language), "{}")
```

The swipe engines consume the same keys through their lexicon merges: `CtcEngineAdapter`
builds `(bundled lexicon + custom words) − disabled words` per active language via
`swipe/ctc/CtcLexiconMerge.kt`; `GeometricEngineAdapter.mergeUserWords` prepends custom
words (custom overrides disabled, matching `WordPredictor` semantics) and filters disabled
words. Both rebuild on a content-hash change of (custom-words JSON, disabled set).

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
