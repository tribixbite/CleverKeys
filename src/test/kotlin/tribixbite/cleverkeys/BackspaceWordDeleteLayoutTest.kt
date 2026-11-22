package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Maintainer 2026-11-22: "make the default layout include word delete as northwest backspace
 * (preferably using a symbol to avoid needing language-layout translations)".
 *
 * Before, 8 of the 87 language layouts with a Backspace key had `delete_last_word` in its NW corner,
 * drawn as the English text `word`; Colemak and Bone had forward delete there (now NE, as on the
 * others). Now every one has it, drawn with the key font's word-delete icon (the `delete_word`
 * key's), so no layout shows English. Layouts with Backspace in a letter key's corner
 * (`key3="backspace"`: ckb_fa, tj, os) have no Backspace key to carry it.
 *
 * Project root as CWD (same convention as [StyleLayoutsTest]).
 */
class BackspaceWordDeleteLayoutTest {

    /** Not language layouts: the number pad and PIN entry keep a plain Backspace. */
    private val excluded = setOf("numeric.xml", "pin.xml")

    /** A `<key …>` element whose centre (key0 / c) is Backspace. */
    private val backspaceKey = Regex("""<key\b[^>]*\b(?:key0|c)="backspace"[^>]*>""")

    /** Its NW corner (key1 / nw). */
    private val northWest = Regex("""\b(?:key1|nw)="([^"]*)"""")

    private fun layouts(): List<File> =
        File("src/main/layouts").listFiles { f -> f.name.endsWith(".xml") }!!.sortedBy { it.name }

    @Test
    fun everyLanguageLayoutHasWordDeleteInBackspacesNorthWestCorner() {
        val missing = ArrayList<String>()
        var checked = 0
        for (file in layouts().filter { it.name !in excluded }) {
            for (key in backspaceKey.findAll(file.readText())) {
                checked++
                val nw = northWest.find(key.value)?.groupValues?.get(1)
                if (nw != "delete_last_word") missing += "${file.name}: ${key.value.trim()}"
            }
        }
        assertWithMessage("layouts read").that(checked).isAtLeast(85)
        assertWithMessage("Backspace keys without delete_last_word at NW").that(missing).isEmpty()
    }

    @Test
    fun wordDeleteIsDrawnWithTheKeyFontIconNotEnglishText() {
        val lastWord = KeyValue.getKeyByName("delete_last_word")
        val word = KeyValue.getKeyByName("delete_word")
        assertThat(lastWord.hasFlagsAny(KeyValue.FLAG_KEY_FONT)).isTrue()
        assertThat(lastWord.getString()).isEqualTo("")
        // The same icon as the delete_word key: both delete the word before the cursor.
        assertThat(lastWord.getString()).isEqualTo(word.getString())
        assertThat(lastWord.getEditing()).isEqualTo(KeyValue.Editing.DELETE_LAST_WORD)
    }
}
