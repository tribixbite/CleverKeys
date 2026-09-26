package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * TalkBack contract for the opt-in suggestion-bar origin markers (resolves
 * TODO(a11y-origin-marker), 2026-09-26).
 *
 * The marker is a coloured "●" after the word. Read as text, a screen reader speaks the glyph
 * and never the origin, which the dot conveys by colour alone. A marked entry must be announced
 * as its text followed by the origin's localized label, with the glyph excluded; an unmarked
 * entry keeps no description so it is read from its visible text as before.
 *
 * Labels are resolved from the real `res/values/strings.xml` through [labelRes], so the test
 * checks the label a user would actually hear for each origin, not a stand-in.
 */
class SuggestionOriginA11yTest {

    private val marker = "●"

    /** Default-locale text of every `<string>` in res/values/strings.xml, by name. */
    private val englishStrings: Map<String, String> by lazy {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("res/values/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).associate {
            val e = nodes.item(it) as org.w3c.dom.Element
            e.getAttribute("name") to e.textContent
        }
    }

    /** Resource-entry name of an `R.string` id (reflection over the generated R class). */
    private fun resourceName(id: Int): String =
        R.string::class.java.fields.first { it.getInt(null) == id }.name

    private fun englishLabel(origin: SuggestionOrigin): String =
        checkNotNull(englishStrings[resourceName(origin.labelRes())]) { "no English label for $origin" }

    @Test
    fun `each origin maps to its own provenance_origin label`() {
        for (origin in SuggestionOrigin.entries) {
            assertEquals(
                "label resource for $origin",
                "provenance_origin_${origin.name.lowercase()}",
                resourceName(origin.labelRes())
            )
        }
        val labels = SuggestionOrigin.entries.map { englishLabel(it) }
        assertEquals("two origins would be announced identically", labels.size, labels.toSet().size)
    }

    @Test
    fun `a marked entry is announced as the word then its origin label, without the glyph`() {
        for (origin in SuggestionOrigin.entries) {
            val label = englishLabel(origin)
            val description = SuggestionOriginA11y.contentDescription("play", label)
            assertNotNull("marked $origin entry has no description", description)
            val spoken = description.toString()
            assertEquals("$origin", "play, $label", spoken)
            assertTrue("$origin: the word comes first", spoken.startsWith("play"))
            assertTrue("$origin: the origin label is spoken", spoken.contains(label))
            assertFalse("$origin: the marker glyph must not be spoken", spoken.contains(marker))
        }
    }

    @Test
    fun `typo correction reads as a correction, not an autocorrection`() {
        // The case that motivated a distinct origin (807c7212): the spoken form must match it.
        val spoken = SuggestionOriginA11y.contentDescription(
            "play", englishLabel(SuggestionOrigin.TYPO_CORRECTION)
        ).toString()
        assertEquals("play, Typo correction", spoken)
    }

    @Test
    fun `an unmarked entry gets no description and is read from its text`() {
        assertNull(SuggestionOriginA11y.contentDescription("play", null))
        // Debug-score text is passed through unchanged when unmarked (null ⇒ view text is read).
        assertNull(SuggestionOriginA11y.contentDescription("play\n812", null))
    }

    @Test
    fun `the debug score stays in the spoken text when a marker is shown`() {
        val spoken = SuggestionOriginA11y.contentDescription(
            "play\n812", englishLabel(SuggestionOrigin.DICTIONARY_PREFIX)
        ).toString()
        assertTrue(spoken, spoken.startsWith("play\n812, "))
        assertFalse(spoken, spoken.contains(marker))
    }

    /**
     * The View wiring is not unit-constructible, so pin it at the source: the bar must set the
     * description for EVERY bound entry (pooled views would otherwise keep a stale one), feed it
     * the glyph-free text, and draw the glyph only for the same marked entries it describes.
     */
    @Test
    fun `the suggestion bar sets the description from the glyph-free text on every bind`() {
        val bar = File("src/main/kotlin/tribixbite/cleverkeys/SuggestionBar.kt").readText()
        val bind = bar.substringAfter("private fun bindSuggestionView(").substringBefore("private fun reconcileChildren(")
        assertTrue("bindSuggestionView must set contentDescription", bind.contains("view.contentDescription = SuggestionOriginA11y.contentDescription("))
        assertTrue("the description must be built from baseText (no glyph)", bind.contains("spokenText = baseText"))
        assertTrue("the label must come from the shared labelRes", bind.contains("context.getString(it.labelRes())"))
        assertTrue("the glyph is drawn only for the described (marked) entries", bind.contains("view.text = if (markerOrigin != null)"))
        assertFalse("TODO(a11y-origin-marker) is resolved", bar.contains("TODO(a11y-origin-marker)"))
    }
}
