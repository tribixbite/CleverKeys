package tribixbite.cleverkeys

/**
 * Result container for word predictions with scores
 * Used by both the tap and swipe prediction paths
 *
 * @property languages OPTIONAL per-word source language, parallel to [words] and using the same
 *   codes [tribixbite.cleverkeys.swipe.ctc.CtcLanguageSupport.normalize] accepts. Populated ONLY
 *   by the dual-language CTC decode, where the merged slate genuinely mixes languages and the
 *   downstream bar needs to know which word came from which lexicon (CK-150-024: English `'s`
 *   possessive augmentation must not be applied to a French candidate). Null on every
 *   single-language path — the whole slate is then the active dictionary language and the
 *   language-wide gate (`SuggestionHandler.shouldAugmentPossessives`) is the right rule.
 */
data class PredictionResult(
    @JvmField val words: List<String>,
    @JvmField val scores: List<Int>, // Scores as integers (0-1000 range)
    @JvmField val languages: List<String>? = null
)
