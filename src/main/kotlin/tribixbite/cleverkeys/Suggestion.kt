package tribixbite.cleverkeys

/**
 * Typed model for a single entry in the suggestion bar.
 *
 * Historically the suggestion pipeline passed a `List<String>` where a handful
 * of entries carried an in-band prefix (`dict_add:`, `exact_add:`) to signal a
 * special rendering/action. That stringly-typed protocol was parsed ad hoc in
 * several places (the render loop in [SuggestionBar] and the click dispatch in
 * [SuggestionHandler]) with duplicated `startsWith`/`removePrefix` calls.
 *
 * [Suggestion] is the single source of truth for classifying, rendering, and
 * routing those entries. [parse] turns a wire string into a typed value; the
 * consumers `when` over the sealed hierarchy instead of re-parsing prefixes.
 *
 * ## Why the wire protocol is still `List<String>`
 *
 * The producer→bar wire (`SuggestionBar.setSuggestionsWithScores(List<String>, …)`),
 * the `SuggestionBar.getCurrentSuggestions(): List<String>` accessor, and the
 * `SuggestionHandler.onSuggestionSelected(word: String, …)` entry point are a
 * cross-module contract that instrumented tests (`RenderingTruthInstrumentedTest`,
 * `ContractionFlickerIntegrationTest`, `SuggestionStateMachineIntegrationTest`)
 * and the ML-data path (`MLDataCollector`) depend on. This type therefore acts
 * as the parsing/formatting boundary *inside* those APIs — it removes the ad-hoc
 * prefix parsing without forking the wire format.
 *
 * The `raw:` prefix is intentionally NOT modelled here: it is an orthogonal
 * marker on ordinary predictions produced deep in `onnx/PredictionPostProcessor`
 * and stripped by `InputCoordinator`/`SuggestionHandler`/`MLDataCollector`. It
 * travels inside [Word.text] unchanged; classifying it would ripple into modules
 * outside this change's scope.
 *
 * This class is pure JVM (no Android dependencies) and is unit-tested by
 * `SuggestionModelTest`.
 */
sealed interface Suggestion {

    /** The wire-format string this suggestion round-trips back to on tap. */
    val wire: String

    /**
     * An ordinary word prediction (possibly carrying a leading `raw:` marker in
     * [text]; see the class doc). Rendered as-is; tapping commits the word.
     */
    data class Word(val text: String) : Suggestion {
        override val wire: String get() = text
    }

    /**
     * The "Add '<word>' to dictionary?" prompt shown after a user types an
     * unknown word followed by a space. Tapping adds [word] to the user
     * dictionary. Rendered centered/bold when it is the only suggestion.
     */
    data class AddToDictionary(val word: String) : Suggestion {
        override val wire: String get() = DICT_ADD_PREFIX + word
    }

    /**
     * The "+<word>" tap-to-add affordance appended to predictions when the user
     * typed a word that is not in any dictionary (#42). Tapping commits [word]
     * and adds it to the user dictionary. Rendered italic in the sublabel color.
     */
    data class ExactAdd(val word: String) : Suggestion {
        override val wire: String get() = EXACT_ADD_PREFIX + word

        /** Display label for the tap-to-add chip: a leading "+" then the word. */
        val label: String get() = "+$word"
    }

    companion object {
        /** In-band prefix for [AddToDictionary] entries. */
        const val DICT_ADD_PREFIX = "dict_add:"

        /** In-band prefix for [ExactAdd] entries. */
        const val EXACT_ADD_PREFIX = "exact_add:"

        /**
         * Classify a wire-format suggestion string into a typed [Suggestion].
         *
         * This is the ONE place that interprets the special prefixes; every
         * renderer/router should call [parse] rather than testing the prefixes
         * itself.
         */
        fun parse(wire: String): Suggestion = when {
            wire.startsWith(DICT_ADD_PREFIX) ->
                AddToDictionary(wire.removePrefix(DICT_ADD_PREFIX))
            wire.startsWith(EXACT_ADD_PREFIX) ->
                ExactAdd(wire.removePrefix(EXACT_ADD_PREFIX))
            else -> Word(wire)
        }
    }
}

/**
 * The action a tap on a suggestion should perform. This is the pure routing
 * decision that `SuggestionHandler.onSuggestionSelected` dispatches on — it
 * replaces the old chain of `word.startsWith("dict_add:")` / `startsWith(
 * "exact_add:")` checks. Extracting it makes the click-routing decision
 * unit-testable without an InputConnection or Android runtime.
 */
sealed interface SelectionRoute {
    /** Tap adds [word] to the user dictionary (from the "Add …?" prompt). */
    data class AddToDictionary(val word: String) : SelectionRoute
    /** Tap commits [word] and adds it to the user dictionary (the "+word" chip). */
    data class ExactAdd(val word: String) : SelectionRoute
    /** Tap commits the ordinary word via the normal autocorrect/commit path. */
    data class CommitWord(val wire: String) : SelectionRoute
}

/**
 * Map a tapped wire-format suggestion string to its [SelectionRoute]. The single
 * source of truth for what a suggestion tap does; consumed by
 * `SuggestionHandler.onSuggestionSelected`.
 *
 * Note: the ordinary-word route carries the ORIGINAL [wire] (including any
 * `raw:` marker), because the downstream commit path still strips `raw:` and
 * applies autocorrect/contraction handling to it.
 */
fun routeSuggestionSelection(wire: String): SelectionRoute =
    when (val parsed = Suggestion.parse(wire)) {
        is Suggestion.AddToDictionary -> SelectionRoute.AddToDictionary(parsed.word)
        is Suggestion.ExactAdd -> SelectionRoute.ExactAdd(parsed.word)
        is Suggestion.Word -> SelectionRoute.CommitWord(parsed.text)
    }
