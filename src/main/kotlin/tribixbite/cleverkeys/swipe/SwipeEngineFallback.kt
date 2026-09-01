package tribixbite.cleverkeys.swipe

import tribixbite.cleverkeys.KeyboardData
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.swipe.ctc.CtcLanguageSupport
import tribixbite.cleverkeys.swipe.ctc.CtcScriptSupport
import java.util.Locale

/** Pure diagnosis behind the Settings swipe-engine fallback card (ARC-086). */
object SwipeEngineFallback {
    data class LayoutFacts(
        val displayName: String,
        val script: String?,
        /** Emission-alphabet letters absent from centre (tap) key values. */
        val missingCentreLetters: String,
        /** Missing centre letters that do exist on a corner/subkey. */
        val cornerOnlyLetters: String,
    )

    enum class LayoutReason {
        SCRIPT_NOT_ROUTED,
        LETTERS_CORNER_ONLY,
        ALPHABET_INCOMPLETE,
    }

    data class LayoutFinding(
        val layout: LayoutFacts,
        val reason: LayoutReason,
        val lettersForDisplay: String,
    )

    data class Diagnosis(
        val languageFallback: Boolean,
        val layoutFindings: List<LayoutFinding>,
    ) {
        val hasAny: Boolean get() = languageFallback || layoutFindings.isNotEmpty()
    }

    /**
     * Diagnose the same ordered gates production dispatch uses. A language failure suppresses
     * layout details because changing the board cannot make an unserved language CTC-capable.
     */
    fun diagnose(
        mode: SwipeEngineRouter.Mode,
        language: String?,
        layouts: List<LayoutFacts>,
    ): Diagnosis {
        if (mode == SwipeEngineRouter.Mode.GEOMETRIC) return Diagnosis(false, emptyList())
        if (!CtcLanguageSupport.isSupported(language)) return Diagnosis(true, emptyList())

        val findings = layouts.mapNotNull { layout ->
            if (!CtcScriptSupport.isRoutableScript(layout.script)) {
                return@mapNotNull LayoutFinding(layout, LayoutReason.SCRIPT_NOT_ROUTED, "")
            }
            if (layout.missingCentreLetters.isEmpty()) return@mapNotNull null
            val missing = layout.missingCentreLetters.toSet()
            val cornerOnly = layout.cornerOnlyLetters.toSet()
            if (missing.isNotEmpty() && missing.all { it in cornerOnly }) {
                LayoutFinding(
                    layout,
                    LayoutReason.LETTERS_CORNER_ONLY,
                    formatLetters(layout.missingCentreLetters),
                )
            } else {
                LayoutFinding(
                    layout,
                    LayoutReason.ALPHABET_INCOMPLETE,
                    formatLetters(layout.missingCentreLetters),
                )
            }
        }
        return Diagnosis(false, findings)
    }

    /** Measure [KeyboardData] using the same centre-value definition as CtcEngineAdapter. */
    fun factsFor(layout: KeyboardData, language: String?): LayoutFacts {
        val alphabet = CtcScriptSupport.alphabetFor(language).toSet()
        val centres = HashSet<Char>()
        val corners = HashSet<Char>()
        for (row in layout.rows) {
            for (key in row.keys) {
                letterOf(key.keys.getOrNull(0))?.let(centres::add)
                for (i in 1 until key.keys.size) {
                    letterOf(key.keys[i])?.let(corners::add)
                }
            }
        }
        val missing = alphabet.filterNot { it in centres }.sorted()
        return LayoutFacts(
            displayName = layout.name?.takeIf { it.isNotBlank() } ?: "Unnamed layout",
            script = layout.script,
            missingCentreLetters = missing.joinToString(""),
            cornerOnlyLetters = missing.filter { it in corners }.joinToString(""),
        )
    }

    private fun letterOf(value: KeyValue?): Char? {
        val raw = when (value?.getKind()) {
            KeyValue.Kind.Char -> value.getChar().toString()
            KeyValue.Kind.String -> value.getString()
            else -> return null
        }
        val folded = raw.lowercase(Locale.ROOT)
        return folded.singleOrNull()?.takeIf(Char::isLetter)
    }

    private fun formatLetters(raw: String): String {
        val letters = raw.toSet().sorted()
        if (letters.isEmpty()) return ""
        val shown = letters.take(8).joinToString(", ")
        return if (letters.size > 8) "$shown…" else shown
    }
}
