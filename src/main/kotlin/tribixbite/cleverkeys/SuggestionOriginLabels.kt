package tribixbite.cleverkeys

import androidx.annotation.StringRes

/**
 * The localized label resource for this origin (`provenance_origin_<name>`, pinned against
 * every locale by SuggestionProvenanceTest).
 *
 * Shared by the two places that name an origin to the user: the long-press provenance sheet
 * (`SuggestionHandler.localizedProvenanceStrings`) and the suggestion bar's spoken description
 * of an origin marker ([SuggestionOriginA11y]). One mapping, so the dot a TalkBack user hears
 * and the sheet a sighted user reads can never name different origins.
 *
 * Exhaustive `when` with no `else`, on purpose: a new [SuggestionOrigin] does not compile until
 * it has a label.
 */
@StringRes
internal fun SuggestionOrigin.labelRes(): Int = when (this) {
    SuggestionOrigin.GEOMETRIC -> R.string.provenance_origin_geometric
    SuggestionOrigin.CTC -> R.string.provenance_origin_ctc
    SuggestionOrigin.DICTIONARY_PREFIX -> R.string.provenance_origin_dictionary_prefix
    SuggestionOrigin.CONTRACTION -> R.string.provenance_origin_contraction
    SuggestionOrigin.POSSESSIVE -> R.string.provenance_origin_possessive
    SuggestionOrigin.EXACT_ADD -> R.string.provenance_origin_exact_add
    SuggestionOrigin.NEXT_WORD -> R.string.provenance_origin_next_word
    SuggestionOrigin.AUTOCORRECT -> R.string.provenance_origin_autocorrect
    SuggestionOrigin.TYPO_CORRECTION -> R.string.provenance_origin_typo_correction
}

/**
 * What a screen reader announces for a suggestion-bar entry that carries an origin marker.
 *
 * The opt-in marker (`suggestion_provenance_markers`) is a coloured "●" appended to the word.
 * Read as text, TalkBack speaks the glyph ("black circle") and never the origin, which the dot
 * conveys by colour alone. The bar therefore gives a marked entry an explicit content
 * description — the spoken text WITHOUT the glyph, followed by the origin's localized label
 * ("play, Typo correction") — and leaves every other entry without one, so it is read from
 * its visible text exactly as before (prompts, the "+word" chip, bar messages, plain words).
 *
 * Pure (the label is resolved by the caller), so the contract is unit-tested without a View.
 */
internal object SuggestionOriginA11y {

    /** Separator between the spoken text and the origin label; a comma gives a short pause. */
    private const val LABEL_SEPARATOR = ", "

    /**
     * @param spokenText the entry's text as it should be read, WITHOUT the marker glyph (the
     *   word, plus the debug score when debug scores are on)
     * @param originLabel the localized [SuggestionOrigin.labelRes] text for the entry's
     *   origin, or null when no marker is drawn for it
     * @return the content description to set, or null to let the view's text be read
     */
    fun contentDescription(spokenText: CharSequence, originLabel: String?): CharSequence? =
        if (originLabel == null) null else "$spokenText$LABEL_SEPARATOR$originLabel"
}
