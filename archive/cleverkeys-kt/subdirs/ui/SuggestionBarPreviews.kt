package tribixbite.keyboard2.ui

import androidx.compose.runtime.*
import androidx.compose.ui.tooling.preview.Preview
import tribixbite.keyboard2.theme.KeyboardThemePreview

/**
 * Compose previews for SuggestionBarM3.
 *
 * Shows various states and configurations for design review.
 */

@Preview(name = "Empty State", showBackground = true)
@Composable
fun SuggestionBarEmptyPreview() {
    KeyboardThemePreview {
        SuggestionBarM3(
            suggestions = emptyList(),
            onSuggestionClick = {}
        )
    }
}

@Preview(name = "Single Suggestion", showBackground = true)
@Composable
fun SuggestionBarSinglePreview() {
    KeyboardThemePreview {
        SuggestionBarM3(
            suggestions = listOf(
                Suggestion.simple("hello", 0.9f)
            ),
            onSuggestionClick = {}
        )
    }
}

@Preview(name = "Multiple Suggestions", showBackground = true)
@Composable
fun SuggestionBarMultiplePreview() {
    KeyboardThemePreview {
        SuggestionBarM3(
            suggestions = listOf(
                Suggestion("hello", 0.95f, PredictionSource.NEURAL),
                Suggestion("help", 0.85f, PredictionSource.AUTOCORRECT),
                Suggestion("held", 0.6f, PredictionSource.DICTIONARY),
                Suggestion("hell", 0.5f, PredictionSource.SPELLCHECK),
                Suggestion("helicopter", 0.4f, PredictionSource.USER)
            ),
            onSuggestionClick = {}
        )
    }
}

@Preview(name = "High Confidence Mix", showBackground = true)
@Composable
fun SuggestionBarHighConfidencePreview() {
    KeyboardThemePreview {
        SuggestionBarM3(
            suggestions = listOf(
                Suggestion("the", 0.95f, PredictionSource.NEURAL, frequency = 1000),
                Suggestion("that", 0.88f, PredictionSource.USER, frequency = 500),
                Suggestion("there", 0.82f, PredictionSource.AUTOCORRECT, frequency = 300),
                Suggestion("they", 0.65f, PredictionSource.DICTIONARY),
                Suggestion("them", 0.45f, PredictionSource.SPELLCHECK)
            ),
            onSuggestionClick = {}
        )
    }
}

@Preview(name = "Dark Theme", showBackground = true)
@Composable
fun SuggestionBarDarkPreview() {
    KeyboardThemePreview(darkTheme = true) {
        SuggestionBarM3(
            suggestions = listOf(
                Suggestion("hello", 0.9f),
                Suggestion("world", 0.7f),
                Suggestion("test", 0.5f)
            ),
            onSuggestionClick = {}
        )
    }
}

@Preview(name = "User Words", showBackground = true)
@Composable
fun SuggestionBarUserWordsPreview() {
    KeyboardThemePreview {
        SuggestionBarM3(
            suggestions = listOf(
                Suggestion("CleverKeys", 0.92f, PredictionSource.USER, isUserWord = true),
                Suggestion("ONNX", 0.88f, PredictionSource.USER, isUserWord = true),
                Suggestion("Kotlin", 0.75f, PredictionSource.DICTIONARY)
            ),
            onSuggestionClick = {}
        )
    }
}

/**
 * Interactive preview with state changes for testing animations.
 */
@Preview(name = "Animated State Changes", showBackground = true)
@Composable
fun SuggestionBarAnimationPreview() {
    var currentSet by remember { mutableStateOf(0) }

    val suggestionSets = listOf(
        listOf(
            Suggestion("hello", 0.9f),
            Suggestion("help", 0.7f),
            Suggestion("held", 0.5f)
        ),
        listOf(
            Suggestion("world", 0.85f),
            Suggestion("work", 0.65f),
            Suggestion("word", 0.45f)
        ),
        listOf(
            Suggestion("test", 0.92f),
            Suggestion("testing", 0.8f),
            Suggestion("tested", 0.6f)
        )
    )

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            currentSet = (currentSet + 1) % suggestionSets.size
        }
    }

    KeyboardThemePreview {
        SuggestionBarM3(
            suggestions = suggestionSets[currentSet],
            onSuggestionClick = {},
            onSuggestionLongPress = {}
        )
    }
}
