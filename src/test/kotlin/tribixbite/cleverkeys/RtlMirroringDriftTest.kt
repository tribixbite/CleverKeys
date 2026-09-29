package tribixbite.cleverkeys

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Directional "forward / next / back" icons must mirror under RTL locales (fa).
 *
 * Device finding 2026-09-29 (Seeker, app locale fa): the settings navigation cards showed
 * right-pointing arrows in a right-to-left layout. Material's `Icons.AutoMirrored.*` variants
 * flip with the layout direction; the plain `Icons.Default/Filled.*` ones point the same way
 * in every locale, and so do glyph arrows drawn as text.
 *
 * Exception: the command palette's CURSOR category keeps a fixed right arrow on purpose —
 * it depicts the physical cursor-right key, not navigation.
 */
class RtlMirroringDriftTest {

    private val nonMirrored = Regex(
        "Icons\\.(?:Default|Filled|Outlined|Rounded|Sharp|TwoTone)\\." +
            "(?:ArrowBack|ArrowForward|KeyboardArrowLeft|KeyboardArrowRight)\\b"
    )
    private val glyphArrow = Regex("\\bText\\(\\s*\"[◀▶←→]\"")

    @Test fun directionalArrowsAreAutoMirrored() {
        val offenders = mutableListOf<String>()
        for ((rel, source) in KotlinSourceScan.kotlinFiles()) {
            KotlinSourceScan.stripComments(source).lines().forEachIndexed { i, line ->
                val cursorCategory = rel == "customization/CommandPaletteDialog.kt" &&
                    "Category.CURSOR ->" in line
                if (!cursorCategory && (nonMirrored.containsMatchIn(line) || glyphArrow.containsMatchIn(line))) {
                    offenders += "$rel:${i + 1}: ${line.trim()}"
                }
            }
        }
        assertTrue(
            "non-mirrored directional arrow(s) — use Icons.AutoMirrored.*:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test fun matcherCoversIconAndGlyphShapes() {
        assertTrue(nonMirrored.containsMatchIn("imageVector = Icons.Default.ArrowForward,"))
        assertTrue(nonMirrored.containsMatchIn("Icon(Icons.Filled.KeyboardArrowRight, null)"))
        assertTrue(!nonMirrored.containsMatchIn("Icons.AutoMirrored.Filled.ArrowForward"))
        assertTrue(!nonMirrored.containsMatchIn("Icons.Default.KeyboardArrowDown"))
        assertTrue(glyphArrow.containsMatchIn("Text(\"▶\", fontSize = 16.sp)"))
    }
}
