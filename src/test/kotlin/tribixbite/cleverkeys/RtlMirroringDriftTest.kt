package tribixbite.cleverkeys

import java.io.File
import org.junit.Assert.assertEquals
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

    /**
     * The keyboard panes' pagers (IME Views, not Compose) draw the ◀/▶ glyphs as text, so they
     * cannot use AutoMirrored icons. [PanePagerArrows] swaps them under RTL; this pins the swap.
     */
    @Test fun panePagerGlyphsSwapUnderRtl() {
        val ltr = PanePagerArrows.glyphsFor(rtl = false)
        val rtl = PanePagerArrows.glyphsFor(rtl = true)
        assertEquals(R.string.glyph_page_prev, ltr.prev)
        assertEquals(R.string.glyph_page_next, ltr.next)
        assertEquals("RTL previous-page button must point right", R.string.glyph_page_next, rtl.prev)
        assertEquals("RTL next-page button must point left", R.string.glyph_page_prev, rtl.next)
        val english = TranslationResources.strings(TranslationResources.defaultDir)
        assertEquals("glyph_page_prev must be the left-pointing glyph", "◀", english["glyph_page_prev"])
        assertEquals("glyph_page_next must be the right-pointing glyph", "▶", english["glyph_page_next"])
    }

    /**
     * Every layout that puts a page glyph on a view must have that view bound through
     * [PanePagerArrows.apply] somewhere in the Kotlin sources; otherwise a new pager would ship
     * with fixed arrows again.
     */
    @Test fun everyGlyphPagerLayoutIsBoundThroughPanePagerArrows() {
        val viewWithGlyph = Regex(
            "android:id=\"@\\+id/(\\w+)\"[^>]*?android:text=\"@string/glyph_page_(?:prev|next)\"" +
                "|android:text=\"@string/glyph_page_(?:prev|next)\"[^>]*?android:id=\"@\\+id/(\\w+)\"",
            RegexOption.DOT_MATCHES_ALL
        )
        val ids = File("res/layout").listFiles().orEmpty().filter { it.extension == "xml" }
            .flatMap { f -> f.readText().split("<").mapNotNull { tag ->
                viewWithGlyph.find(tag)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }
            } }
        assertTrue("expected the GIF and clipboard pager glyph views, found $ids", ids.size >= 4)
        val sources = KotlinSourceScan.kotlinFiles()
            .map { KotlinSourceScan.stripComments(it.second) }
            .filter { "PanePagerArrows.apply(" in it }
            .toList()
        val unbound = ids.filter { id -> sources.none { "R.id.$id" in it } }
        assertTrue("pager glyph views not bound through PanePagerArrows.apply: $unbound", unbound.isEmpty())
    }
}
