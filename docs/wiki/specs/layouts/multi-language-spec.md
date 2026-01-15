---
title: Multi-Language Input - Technical Specification
user_guide: ../../layouts/multi-language.md
status: implemented
version: v1.2.7
---

# Multi-Language Input Technical Specification

## Overview

The multi-language system enables typing in multiple languages with automatic detection, per-language dictionaries, and seamless prediction switching.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| LanguageDetector | `LanguageDetector.kt` | Auto-detection |
| MultiDictionary | `MultiDictionary.kt` | Per-language dictionaries |
| LanguageManager | `LanguageManager.kt` | Language configuration |
| NeuralPredictor | `SwipeTrajectoryProcessor.kt` | Multi-language predictions |
| Config | `Config.kt` | Language preferences |

## Data Model

### Language Configuration

```kotlin
// LanguageManager.kt
data class LanguageConfig(
    val code: String,              // ISO code (en, fr, de)
    val locale: Locale,            // Full locale
    val displayName: String,
    val dictionary: Dictionary?,
    val preferredLayout: String?,  // Associated layout ID
    val isAutoDetectEnabled: Boolean = true
)

class LanguageManager {
    val enabledLanguages: List<LanguageConfig>
    val primaryLanguage: LanguageConfig
    var currentLanguage: LanguageConfig
}
```

### Dictionary Management

```kotlin
// MultiDictionary.kt
class MultiDictionary {
    private val dictionaries = mutableMapOf<String, Dictionary>()

    fun getDictionary(languageCode: String): Dictionary? {
        return dictionaries[languageCode]
    }

    fun getWordFrequency(word: String, language: String): Float {
        return dictionaries[language]?.getFrequency(word) ?: 0f
    }

    fun isValidWord(word: String, language: String): Boolean {
        return dictionaries[language]?.contains(word) ?: false
    }
}
```

## Language Detection

### Detection Algorithm

```kotlin
// LanguageDetector.kt
class LanguageDetector {
    private val ngramModels = mutableMapOf<String, NgramModel>()

    fun detectLanguage(text: String): DetectionResult {
        if (text.length < MIN_DETECT_LENGTH) {
            return DetectionResult(currentLanguage, confidence = 0f)
        }

        val scores = enabledLanguages.map { lang ->
            lang.code to calculateScore(text, lang.code)
        }.toMap()

        val (bestLang, bestScore) = scores.maxByOrNull { it.value }
            ?: return DetectionResult(currentLanguage, 0f)

        return DetectionResult(
            language = languageManager.getLanguage(bestLang),
            confidence = bestScore,
            allScores = scores
        )
    }

    private fun calculateScore(text: String, langCode: String): Float {
        val model = ngramModels[langCode] ?: return 0f

        // Character n-gram analysis
        val trigrams = text.windowed(3)
        val bigramScore = trigrams.sumOf { model.getTrigramProb(it) }

        // Word-based analysis
        val words = text.split("\\s+".toRegex())
        val wordScore = words.count { multiDict.isValidWord(it, langCode) }
            .toFloat() / words.size

        return (bigramScore * 0.4f + wordScore * 0.6f)
    }
}
```

### Detection Results

```kotlin
// LanguageDetector.kt
data class DetectionResult(
    val language: LanguageConfig,
    val confidence: Float,           // 0.0 - 1.0
    val allScores: Map<String, Float> = emptyMap()
)

// Confidence thresholds
const val CONFIDENCE_HIGH = 0.8f     // Definitely this language
const val CONFIDENCE_MEDIUM = 0.5f   // Probably this language
const val CONFIDENCE_LOW = 0.3f      // Maybe this language
```

## Prediction Integration

### Multi-Language Predictions

```kotlin
// SwipeTrajectoryProcessor.kt
fun getPredictions(trajectory: SwipeTrajectory): List<Prediction> {
    val enabledLangs = languageManager.enabledLanguages

    // Get predictions for each language
    val allPredictions = enabledLangs.flatMap { lang ->
        val vocab = vocabularies[lang.code] ?: return@flatMap emptyList()
        neural.predict(trajectory, vocab).map { pred ->
            pred.copy(language = lang.code)
        }
    }

    // Merge and rank by combined score
    return mergePredictions(allPredictions)
        .take(config.max_predictions)
}

private fun mergePredictions(predictions: List<Prediction>): List<Prediction> {
    // Group by word, keeping best score per word
    val byWord = predictions.groupBy { it.word }
        .mapValues { (_, preds) -> preds.maxByOrNull { it.score }!! }

    // Apply language boost to primary language
    return byWord.values
        .map { pred ->
            val boost = if (pred.language == primaryLanguage.code) 1.1f else 1.0f
            pred.copy(score = pred.score * boost)
        }
        .sortedByDescending { it.score }
}
```

### Auto-Switch on Detection

```kotlin
// LanguageDetector.kt
fun onTextChanged(newText: String) {
    if (!config.auto_detect_language) return

    val result = detectLanguage(newText)

    if (result.confidence >= CONFIDENCE_MEDIUM &&
        result.language != currentLanguage) {

        // Switch prediction language
        currentLanguage = result.language

        // Optionally switch layout
        if (config.auto_switch_layout) {
            result.language.preferredLayout?.let {
                layoutSwitcher.switchToLayout(it)
            }
        }
    }
}
```

## Mixed-Language Handling

### Code-Switching Detection

```kotlin
// LanguageDetector.kt
fun detectCodeSwitch(text: String): List<LanguageSpan> {
    val words = text.split("\\s+".toRegex())
    val spans = mutableListOf<LanguageSpan>()

    var currentSpan: LanguageSpan? = null

    words.forEachIndexed { index, word ->
        val wordLang = detectWordLanguage(word)

        if (currentSpan?.language != wordLang) {
            currentSpan?.let { spans.add(it) }
            currentSpan = LanguageSpan(wordLang, index, index)
        } else {
            currentSpan = currentSpan?.copy(endIndex = index)
        }
    }

    currentSpan?.let { spans.add(it) }
    return spans
}

data class LanguageSpan(
    val language: String,
    val startIndex: Int,
    val endIndex: Int
)
```

## Personal Dictionary

### Per-Language Words

```kotlin
// PersonalDictionary.kt
class PersonalDictionary {
    private val wordsByLanguage = mutableMapOf<String, MutableSet<String>>()

    fun addWord(word: String, language: String) {
        wordsByLanguage.getOrPut(language) { mutableSetOf() }.add(word)
        saveToPrefs()
    }

    fun getWords(language: String): Set<String> {
        return wordsByLanguage[language] ?: emptySet()
    }
}
```

## Configuration

| Setting | Key | Default | Description |
|---------|-----|---------|-------------|
| **Enabled Languages** | `enabled_languages` | ["en"] | Active languages |
| **Primary Language** | `primary_language` | "en" | Default language |
| **Auto-Detect** | `auto_detect_language` | true | Enable detection |
| **Auto-Switch Layout** | `auto_switch_layout` | false | Switch on detect |
| **Detection Threshold** | `detection_threshold` | 0.5 | Min confidence |

## Related Specifications

- [Language System](../../../specs/dictionary-and-language-system.md) - Full language architecture
- [Neural Prediction](../../../specs/neural-prediction.md) - ONNX model
- [Secondary Language](../../../specs/secondary-language-integration.md) - Multi-language integration
