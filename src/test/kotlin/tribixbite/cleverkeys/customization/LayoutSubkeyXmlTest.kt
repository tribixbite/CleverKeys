package tribixbite.cleverkeys.customization

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** Position-addressed subkey edits of layout XML text (pure DOM; see [LayoutSubkeyXml]). */
class LayoutSubkeyXmlTest {

    private val xml = """<?xml version="1.0" encoding="utf-8"?>
<keyboard name="Test" script="latin" language="fa">
  <!-- a comment survives -->
  <row>
    <key key0="q" key2="1" nw="@"/>
    <key c="w" ne="2"/>
  </row>
  <row>
    <key key0="a" key1="&amp;"/>
  </row>
</keyboard>
"""

    @Test
    fun countsKeysPerRowInOrder() {
        assertThat(LayoutSubkeyXml.keyCounts(xml)).containsExactly(2, 1).inOrder()
    }

    @Test
    fun settingASlotReplacesBothSynonyms() {
        // key0="q" has key2 (NE) as a numbered synonym: the parser rejects key2 + ne together.
        val out = LayoutSubkeyXml.setSubkey(xml, 0, 0, SwipeDirection.NE, "voice_input")
        assertThat(out).contains("ne=\"voice_input\"")
        assertThat(out).doesNotContain("key2=")
        assertThat(out).contains("nw=\"@\"") // the other slots stay
    }

    @Test
    fun removingASlotDropsBothSynonyms() {
        val out = LayoutSubkeyXml.setSubkey(xml, 1, 0, SwipeDirection.NW, null)
        assertThat(out).doesNotContain("key1=")
        assertThat(out).doesNotContain("&amp;")
    }

    @Test
    fun valuesAreEscaped() {
        val out = LayoutSubkeyXml.setSubkey(xml, 0, 1, SwipeDirection.S, "'a\"b<c'")
        assertThat(out).contains("s=\"'a&quot;b&lt;c'\"")
    }

    @Test
    fun renameAndRootAttributesAndCommentsSurvive() {
        val out = LayoutSubkeyXml.rename(LayoutSubkeyXml.setSubkey(xml, 0, 0, SwipeDirection.E, "x"), "Test (modified)")
        assertThat(out).contains("name=\"Test (modified)\"")
        assertThat(out).contains("language=\"fa\"")
        assertThat(out).contains("<!-- a comment survives -->")
        assertThat(out.trimStart()).startsWith("<?xml")
    }

    @Test
    fun noDeclarationIsAddedWhenThereWasNone() {
        val bare = "<keyboard><row><key key0=\"a\"/></row></keyboard>"
        assertThat(LayoutSubkeyXml.setSubkey(bare, 0, 0, SwipeDirection.N, "b").trimStart()).startsWith("<keyboard")
    }

    @Test
    fun aMissingKeyIsAnError() {
        assertThrows(IllegalArgumentException::class.java) {
            LayoutSubkeyXml.setSubkey(xml, 1, 5, SwipeDirection.N, "b")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LayoutSubkeyXml.setSubkey(xml, 9, 0, SwipeDirection.N, "b")
        }
    }

    @Test
    fun attributeNamesFollowTheParser() {
        // KeyboardData.Key.parse: key1 nw, key2 ne, key3 sw, key4 se, key5 w, key6 e, key7 n, key8 s.
        val expected = mapOf(
            SwipeDirection.NW to "key1", SwipeDirection.NE to "key2", SwipeDirection.SW to "key3",
            SwipeDirection.SE to "key4", SwipeDirection.W to "key5", SwipeDirection.E to "key6",
            SwipeDirection.N to "key7", SwipeDirection.S to "key8",
        )
        for ((dir, attr) in expected) assertThat(LayoutSubkeyXml.numberedAttribute(dir)).isEqualTo(attr)
    }
}
