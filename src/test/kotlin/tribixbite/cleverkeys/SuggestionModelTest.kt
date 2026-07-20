package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for the R3 de-stringified suggestion protocol
 * ([Suggestion], [SelectionRoute], [routeSuggestionSelection]).
 *
 * These exercise the REAL production seams that replaced the old scattered
 * `startsWith("dict_add:")` / `removePrefix("exact_add:")` parsing:
 *
 *   - [Suggestion.parse] — the one place that classifies a wire string. Used by
 *     `SuggestionBar.rebindSuggestionViews` (render) and, transitively via
 *     [routeSuggestionSelection], by `SuggestionHandler.onSuggestionSelected`.
 *   - `Suggestion.*.wire` — the wire format producers emit
 *     (`SuggestionHandler`/`InputCoordinator` build `Suggestion.ExactAdd(w).wire`
 *     etc.). The instrumented tests + ML-data path still read these raw strings,
 *     so wire stability is a hard contract.
 *   - [routeSuggestionSelection] — the pure click-routing decision that
 *     `SuggestionHandler.onSuggestionSelected` dispatches on.
 *
 * No Android runtime is required; only the rendered "Add '…'?" text (which needs
 * `context.getString`) is exercised in the instrumented layer instead.
 */
class SuggestionModelTest {

    // =========================================================================
    // parse(): classification
    // =========================================================================

    @Test
    fun `parse classifies an ordinary word`() {
        val s = Suggestion.parse("hello")
        assertThat(s).isInstanceOf(Suggestion.Word::class.java)
        assertThat((s as Suggestion.Word).text).isEqualTo("hello")
    }

    @Test
    fun `parse classifies a dict_add prompt and strips the prefix`() {
        val s = Suggestion.parse("dict_add:cromulent")
        assertThat(s).isInstanceOf(Suggestion.AddToDictionary::class.java)
        assertThat((s as Suggestion.AddToDictionary).word).isEqualTo("cromulent")
    }

    @Test
    fun `parse classifies an exact_add chip and strips the prefix`() {
        val s = Suggestion.parse("exact_add:xyzq")
        assertThat(s).isInstanceOf(Suggestion.ExactAdd::class.java)
        assertThat((s as Suggestion.ExactAdd).word).isEqualTo("xyzq")
    }

    @Test
    fun `parse leaves a raw-prefixed prediction as an ordinary word`() {
        // `raw:` is an orthogonal marker handled downstream (autocorrect skip +
        // MLDataCollector), NOT one of the special suggestion kinds. It must
        // stay embedded in Word.text so the commit path can strip it.
        val s = Suggestion.parse("raw:hello")
        assertThat(s).isInstanceOf(Suggestion.Word::class.java)
        assertThat((s as Suggestion.Word).text).isEqualTo("raw:hello")
    }

    @Test
    fun `parse does not misclassify a word that merely contains a prefix mid-string`() {
        // Only a LEADING prefix is special; "un_dict_add:x" or an apostrophe word
        // must not trip the classifier.
        assertThat(Suggestion.parse("it's")).isEqualTo(Suggestion.Word("it's"))
        assertThat(Suggestion.parse("foo dict_add:bar"))
            .isEqualTo(Suggestion.Word("foo dict_add:bar"))
    }

    @Test
    fun `parse handles an empty word after the prefix`() {
        assertThat(Suggestion.parse("dict_add:"))
            .isEqualTo(Suggestion.AddToDictionary(""))
        assertThat(Suggestion.parse("exact_add:"))
            .isEqualTo(Suggestion.ExactAdd(""))
    }

    // =========================================================================
    // wire: producer format + round-trip
    // =========================================================================

    @Test
    fun `AddToDictionary wire format matches the legacy dict_add prefix`() {
        assertThat(Suggestion.AddToDictionary("cromulent").wire)
            .isEqualTo("dict_add:cromulent")
    }

    @Test
    fun `ExactAdd wire format matches the legacy exact_add prefix`() {
        assertThat(Suggestion.ExactAdd("xyzq").wire).isEqualTo("exact_add:xyzq")
    }

    @Test
    fun `Word wire is the text verbatim`() {
        assertThat(Suggestion.Word("hello").wire).isEqualTo("hello")
        assertThat(Suggestion.Word("raw:hello").wire).isEqualTo("raw:hello")
    }

    @Test
    fun `wire round-trips through parse for every kind`() {
        val samples = listOf(
            Suggestion.Word("hello"),
            Suggestion.Word("raw:hello"),
            Suggestion.Word("it's"),
            Suggestion.AddToDictionary("cromulent"),
            Suggestion.ExactAdd("xyzq"),
        )
        for (s in samples) {
            assertThat(Suggestion.parse(s.wire)).isEqualTo(s)
        }
    }

    // =========================================================================
    // ExactAdd display label ("+word")
    // =========================================================================

    @Test
    fun `ExactAdd label prefixes the word with a plus sign`() {
        assertThat(Suggestion.ExactAdd("xyzq").label).isEqualTo("+xyzq")
    }

    // =========================================================================
    // routeSuggestionSelection(): click routing decision
    // =========================================================================

    @Test
    fun `routing an ordinary word yields CommitWord carrying the wire verbatim`() {
        assertThat(routeSuggestionSelection("hello"))
            .isEqualTo(SelectionRoute.CommitWord("hello"))
    }

    @Test
    fun `routing preserves the raw marker so the commit path can strip it`() {
        // CommitWord must carry the ORIGINAL wire (with raw:) — the downstream
        // autocorrect/commit code strips raw: and applies contraction handling.
        assertThat(routeSuggestionSelection("raw:hello"))
            .isEqualTo(SelectionRoute.CommitWord("raw:hello"))
    }

    @Test
    fun `routing a dict_add prompt yields the AddToDictionary action`() {
        assertThat(routeSuggestionSelection("dict_add:cromulent"))
            .isEqualTo(SelectionRoute.AddToDictionary("cromulent"))
    }

    @Test
    fun `routing an exact_add chip yields the ExactAdd action`() {
        assertThat(routeSuggestionSelection("exact_add:xyzq"))
            .isEqualTo(SelectionRoute.ExactAdd("xyzq"))
    }

    @Test
    fun `every route maps back to the same word carried by the parsed suggestion`() {
        // Guards against a producer/consumer prefix drift: the word routed for a
        // tap must equal the word parsed from the same wire.
        val dictWire = Suggestion.AddToDictionary("neologism").wire
        val exactWire = Suggestion.ExactAdd("neologism").wire

        assertThat(routeSuggestionSelection(dictWire))
            .isEqualTo(SelectionRoute.AddToDictionary("neologism"))
        assertThat(routeSuggestionSelection(exactWire))
            .isEqualTo(SelectionRoute.ExactAdd("neologism"))
    }
}
