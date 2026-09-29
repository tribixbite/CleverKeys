package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Flags translations that are much longer than their English source in UI that has little
 * room. It is a heuristic for overflow, not a layout measurement: it counts code points,
 * so it cannot see glyph width (CJK is ~2 columns per character but rarely long in count)
 * and it does not replace a device check with long text and enlarged fonts.
 *
 * Thresholds (documented in docs/specs/testing-strategy.md, "Translation verification"):
 *
 *  - Suggestion-bar copy (`suggestion_*`): the chip is `maxLines = 2` inside the bar's
 *    horizontal strip, so flag above [BAR_RATIO]× English AND above [MIN_LENGTH] chars.
 *  - Settings/dialog titles (`*_title`) and origin labels (`provenance_origin_*`): these
 *    wrap, so only flag gross expansion, above [TITLE_RATIO]× English AND above
 *    [MIN_LENGTH] chars.
 *
 * The absolute floor keeps short strings (where 2× is a handful of characters) out of it.
 * Format arguments count as a 4-character word on both sides. A justified exception goes in
 * [accepted] with the reason, never by loosening a threshold.
 */
class TranslationLengthTest {

    private companion object {
        const val BAR_RATIO = 1.75
        const val TITLE_RATIO = 2.0
        const val MIN_LENGTH = 40
        val ARGUMENT = Regex("%[0-9]+\\$[-#+ 0,(]*[0-9]*(?:\\.[0-9]+)?[a-zA-Z]")
    }

    /** `locale/key` -> why the length is acceptable. Empty: every offender was shortened. */
    private val accepted: Map<String, String> = emptyMap()

    private fun visibleLength(text: String): Int {
        val s = ARGUMENT.replace(TranslationResources.unescape(text), "xxxx")
        return s.codePointCount(0, s.length)
    }

    private fun ratioFor(key: String): Double? = when {
        key.startsWith("suggestion_") -> BAR_RATIO
        key.endsWith("_title") || key.startsWith("provenance_origin_") -> TITLE_RATIO
        else -> null
    }

    @Test fun constrainedStringsDoNotExpandPastTheThresholds() {
        val english = TranslationResources.strings(TranslationResources.defaultDir)
        val offenders = mutableListOf<String>()
        for (dir in TranslationResources.localeDirs) {
            val locale = TranslationResources.localeOf(dir)
            for ((key, text) in TranslationResources.strings(dir)) {
                val ratio = ratioFor(key) ?: continue
                val source = english[key] ?: continue
                val en = visibleLength(source)
                val tr = visibleLength(text)
                if (tr > MIN_LENGTH && tr > ratio * en && "$locale/$key" !in accepted) {
                    offenders += "$locale/$key: $tr chars vs English $en (limit ${ratio}×): \"$text\""
                }
            }
        }
        assertTrue(
            "${offenders.size} translation(s) too long for their UI slot — shorten, or add to " +
                "`accepted` with a reason:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /** The heuristic must actually see the bar strings it exists for. */
    @Test fun barStringsAreInScope() {
        val barKeys = TranslationResources.strings(TranslationResources.defaultDir).keys
            .filter { it.startsWith("suggestion_") }
        assertTrue("no suggestion_* strings found — scope rule is stale", barKeys.size >= 5)
        assertEquals(BAR_RATIO, ratioFor("suggestion_tap_again_to_undo")!!, 0.0)
        // "%1$s 'x'" -> "xxxx 'x'": argument = 4 chars, Android escapes resolved.
        assertEquals(8, visibleLength("%1\$s \\'x\\'"))
    }
}
