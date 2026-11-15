package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 2026-11-14 (low): the textual `<keyboard …>` root lookup behind theme binding, key
 * geometry, the backup preview and Export XML. The old regex matched a `<keyboard` sample inside
 * a header comment and ended the tag at a `>` inside a quoted attribute value.
 */
class LayoutXmlRootTest {

    private val commentedSample = """
        <?xml version="1.0" encoding="utf-8"?>
        <!-- Worked example: <keyboard name="Sample" theme="dark"> ... -->
        <keyboard name="Mine" theme="light">
          <row><key key0="a"/></row>
        </keyboard>
    """.trimIndent()

    @Test
    fun aSampleRootInsideAHeaderCommentIsNotTheRoot() {
        assertEquals("light", LayoutThemeBinding.declaredThemeOfXml(commentedSample))

        val exported = LayoutThemeBinding.applyToXml(commentedSample, "dark_material")
        assertTrue("the comment is left as written", exported.contains("""<keyboard name="Sample" theme="dark">"""))
        assertTrue("the real root carries the binding", exported.contains("""<keyboard theme="dark_material" name="Mine">"""))
    }

    @Test
    fun aQuotedGreaterThanDoesNotEndTheTag() {
        val xml = """<keyboard name="a>b" key_border="true"><row/></keyboard>"""
        assertEquals(mapOf("key_border" to "true"), LayoutKeyGeometry.declaredOfXml(xml))
        assertEquals(LayoutXmlRoot.Tag(0..38, """ name="a>b" key_border="true""""), LayoutXmlRoot.find(xml))
    }

    @Test
    fun skipsDeclarationsAndOnlyAcceptsAKeyboardRoot() {
        val doctype = """<!DOCTYPE keyboard [ <!ENTITY x "y"> ]><![CDATA[<keyboard bogus>]]><keyboard/>"""
        assertEquals("/", LayoutXmlRoot.find(doctype)?.attributes)
        assertNull("another root element", LayoutXmlRoot.find("""<layout><keyboard name="x"/></layout>"""))
        assertNull("a longer element name", LayoutXmlRoot.find("""<keyboards name="x"/>"""))
        assertNull("an unterminated tag", LayoutXmlRoot.find("""<keyboard name="x" """))
        assertNull("no element", LayoutXmlRoot.find("<!-- only a comment -->"))
    }
}
