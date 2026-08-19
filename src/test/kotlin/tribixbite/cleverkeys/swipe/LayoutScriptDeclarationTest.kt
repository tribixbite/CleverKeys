package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * `script="latin"` is gate 1 of 3 in swipe engine routing, and it is a hand-written XML
 * attribute with nothing checking it.
 *
 * ## The bug this pins
 *
 * `src/main/layouts/grek_qwerty.xml` shipped declaring `script="latin"` — so the Greek layout
 * passed the router's script gate and was stopped only by the alphabet gate downstream. The
 * commit that claimed to close this (`6af11da7`, "closes neural-swipe allowlist leak") corrected
 * `srcs/layouts/grek_qwerty.xml` — a tree **no build task reads**. `copyLayoutDefinitions` ships
 * `src/main/layouts/`, and that copy stayed wrong for months with no test able to see it.
 *
 * The user-visible impact was nil (the alphabet gate caught it), which is exactly why it
 * survived: a defence-in-depth failure is invisible until the layer in front of it is removed.
 * `CleverKeys-ML/ctc/PHASE_O.md` §3.3 names fixing it as a prerequisite for ALL multi-script
 * work, because per-script routing will make the script attribute load-bearing on its own.
 *
 * ## What is asserted
 *
 * Over the REAL shipped tree: a layout declares `script="latin"` if and only if it exposes all
 * 26 a–z letters as centre key values. The two genuine exceptions are named, so adding a third
 * has to be a deliberate act rather than a silent regression.
 */
class LayoutScriptDeclarationTest {

    private val layoutsDir = File("src/main/layouts")

    /**
     * Latin-declared layouts that genuinely lack a letter. Both are real minority-language
     * QWERTY variants missing `w`; they route to geometric via the alphabet gate, which is
     * correct — their declaration is honest, their key inventory simply is not complete.
     */
    private val incompleteButCorrectlyLatin = setOf("latn_qwerty_az", "latn_qwerty_tly")

    /** Layouts with no `script` attribute at all — legitimately not letter layouts. */
    private val nonLetterLayouts = setOf("numeric", "pin")

    private val scriptAttr = Regex("""<keyboard\b[^>]*\bscript="([^"]*)"""")

    /**
     * Centre key values are what `buildMappedLayout` reads; corner assignments do not count —
     * that distinction is exactly why `latn_qwerty_az` and `latn_qwerty_tly` are
     * letter-incomplete despite having a `w` somewhere on the board.
     *
     * **The tree uses TWO schemas for the centre value and both are live**:
     * `<key c="q" ne="1"/>` (e.g. `latn_qwerty_us`) and `<key key0="p" key1="…"/>` (e.g.
     * `latn_dvorak`). Matching only one makes ~40 layouts look like they contain no letters at
     * all. Corners are `ne/nw/se/sw` in the first schema and `key1..key4` in the second, so
     * anchoring on `c`/`key0` by name is what keeps corner letters out.
     */
    private val centreKeyAttr = Regex("""<key\b[^>]*?\b(?:c|key0)="([^"]*)"""")

    private fun lettersOf(xml: String): Set<Char> =
        centreKeyAttr.findAll(xml)
            .map { it.groupValues[1] }
            .filter { it.length == 1 }
            .map { it[0].lowercaseChar() }
            .filter { it in 'a'..'z' }
            .toSet()

    @Test
    fun latinDeclarationMatchesAzCompleteness() {
        assertWithMessage("layout dir must exist (run from project root)")
            .that(layoutsDir.isDirectory).isTrue()

        val az = ('a'..'z').toSet()
        val wronglyLatin = mutableListOf<String>()
        val wronglyNotLatin = mutableListOf<String>()

        layoutsDir.listFiles { f -> f.extension == "xml" }!!.sortedBy { it.name }.forEach { file ->
            val name = file.nameWithoutExtension
            if (name in nonLetterLayouts) return@forEach
            val xml = file.readText()
            val declared = scriptAttr.find(xml)?.groupValues?.get(1)?.lowercase()
            val complete = lettersOf(xml).containsAll(az)

            if (declared == "latin" && !complete && name !in incompleteButCorrectlyLatin) {
                wronglyLatin += "$name declares latin but is missing " +
                    (az - lettersOf(xml)).sorted().joinToString("")
            }
            if (declared != "latin" && complete) {
                wronglyNotLatin += "$name exposes all 26 a–z but declares script=$declared"
            }
        }

        assertWithMessage(
            "A layout declaring script=\"latin\" without all 26 a–z passes the router's script " +
                "gate and is caught only by the alphabet gate. That defence-in-depth stops " +
                "working the moment per-script routing lands (PHASE_O §3.1). If the layout is " +
                "genuinely a non-Latin script, fix its script attribute; if it is Latin but " +
                "letter-incomplete, add it to incompleteButCorrectlyLatin with a reason."
        ).that(wronglyLatin).isEmpty()

        assertWithMessage(
            "A layout exposing all 26 a–z but NOT declared latin is denied CTC for no reason — " +
                "the inverse leak, costing accuracy silently."
        ).that(wronglyNotLatin).isEmpty()
    }

    /**
     * The specific regression: Greek must not be tagged Latin. Asserted by name as well as by
     * the general rule above, because this exact file has been wrong before and the general
     * rule's exception list is editable.
     */
    @Test
    fun greekLayoutDeclaresGreek() {
        val xml = File(layoutsDir, "grek_qwerty.xml").readText()
        val declared = scriptAttr.find(xml)?.groupValues?.get(1)?.lowercase()
        assertWithMessage(
            "grek_qwerty.xml must declare script=\"greek\". It shipped as \"latin\"; the fix in " +
                "srcs/layouts/ never reached the tree copyLayoutDefinitions actually ships."
        ).that(declared).isEqualTo("greek")
    }

    /**
     * The two layouts with no `script` attribute are non-letter keypads. Pinned so that a new
     * letter layout cannot be added without a script attribute and quietly default to geometric.
     */
    @Test
    fun onlyNonLetterLayoutsOmitTheScriptAttribute() {
        val missing = layoutsDir.listFiles { f -> f.extension == "xml" }!!
            .filter { scriptAttr.find(it.readText()) == null }
            .map { it.nameWithoutExtension }
            .sorted()
        assertThat(missing).isEqualTo(nonLetterLayouts.sorted())
    }
}
