package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.swipe.ctc.CtcScriptSupport
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
     * The per-script extension (plan §A4). The Latin assertion above encodes
     * "latin ⟺ a–z-complete"; per-script routing makes the same question load-bearing for every
     * script in [CtcScriptSupport], so it is EXTENDED here rather than weakened there.
     *
     * The statement is per (script, LANGUAGE) and not per script, and that distinction is the
     * whole content of the test: eleven layouts declare `cyrillic`, they do not share an
     * alphabet, and only the one named in a script row is the board that row's model was
     * trained against. `cyrl_jcuken_uk` declaring `cyrillic` is correct and it is also
     * unusable for ru — ru needs ы/э as centre keys and that board has neither.
     */
    @Test
    fun everyScriptRowsLayoutDeclaresItsScriptAndExposesItsAlphabet() {
        for ((language, wiring) in CtcScriptSupport.SCRIPTS) {
            val file = File(layoutsDir, wiring.layoutXml)
            assertWithMessage(
                "$language: ${wiring.layoutXml} must exist under src/main/layouts — NOT " +
                    "srcs/layouts, which no build task reads and where the Greek script fix " +
                    "once sat looking landed for months"
            ).that(file.isFile).isTrue()
            val xml = file.readText()

            val declared = scriptAttr.find(xml)?.groupValues?.get(1)?.lowercase()
            assertWithMessage(
                "$language: ${wiring.layoutXml} must declare script=\"${wiring.script}\" — the " +
                    "router's gate 1 reads this attribute and nothing else"
            ).that(declared).isEqualTo(wiring.script)

            val centres = centreValuesOf(xml)
            val missing = wiring.alphabet.filterNot { it in centres }
            assertWithMessage(
                "$language: ${wiring.layoutXml} is missing '$missing' as CENTRE key values. " +
                    "The model's emission slots ARE this alphabet, and only `key0`/`c` becomes " +
                    "a slot (KeyboardGeometry.computeKeyRects reads keys[0]) — a letter that " +
                    "lives in a corner is not typeable by swipe and the layout gate will " +
                    "reject the whole board."
            ).that(missing).isEmpty()
        }
    }

    /**
     * The corollary that keeps the ru alphabet honest: `ё` and `ъ` are on the Russian board as
     * CORNER values, and the projection folds them onto `е`/`ь` precisely because corners are
     * not emission slots. If either ever became a centre key the alphabet would be wrong (32 or
     * 33 slots against a 31-slot model) and every decode would be permuted.
     */
    @Test
    fun russianCornerLettersStayInTheCorners() {
        val xml = File(layoutsDir, "cyrl_jcuken_ru.xml").readText()
        val centres = centreValuesOf(xml)
        for (corner in listOf('ё', 'ъ')) {
            assertWithMessage(
                "'$corner' must NOT be a centre key on cyrl_jcuken_ru: the ru model has 31 " +
                    "emission slots and CtcScriptSupport's alphabet lists 31 letters without " +
                    "it. Promoting it to key0 changes the board's centre-key count and " +
                    "silently permutes every Russian decode."
            ).that(centres).doesNotContain(corner)
            assertWithMessage("'$corner' must still be REACHABLE as a corner value")
                .that(xml).contains("\"$corner\"")
        }
    }

    /** Single-character CENTRE key values (`c=` or `key0=`), lowercased. */
    private fun centreValuesOf(xml: String): Set<Char> =
        centreKeyAttr.findAll(xml)
            .map { it.groupValues[1] }
            .filter { it.length == 1 }
            .map { it[0].lowercaseChar() }
            .toSet()

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
