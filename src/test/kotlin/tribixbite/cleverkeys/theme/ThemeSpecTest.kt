package tribixbite.cleverkeys.theme

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.json.JSONObject
import org.junit.Test
import org.w3c.dom.Element

/**
 * The layout `<theme>` model (2026-10-28, maintainer request "fully define a custom layout incl.
 * custom theme … from an XML config file"): the XML schema, its tolerant parser, the writer that
 * makes XML → custom theme → XML lossless, the JSON of a custom theme's `look` extension, the
 * inherit-from-the-global-theme resolution (rendering: [ThemeSpecRenderTest]). Pure JVM: the `<theme>` element of the
 * worked example (docs/wiki/layouts/examples/harbour.xml) is read with the JDK's DOM parser and
 * handed to [ThemeSpecXml.parse] as attribute maps, exactly as `KeyboardData` hands them over.
 */
class ThemeSpecTest {

    private val example = File("docs/wiki/layouts/examples/harbour.xml")

    private fun attrs(e: Element?): Map<String, String>? = e?.let {
        val m = LinkedHashMap<String, String>()
        for (i in 0 until it.attributes.length) m[it.attributes.item(i).nodeName] = it.attributes.item(i).nodeValue
        m
    }

    /** The `<theme>` of [xml] parsed by the production parser; problems into [problems]. */
    private fun parseTheme(xml: String, problems: MutableList<String> = ArrayList()): ThemeSpec {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        val theme = doc.getElementsByTagName("theme").item(0) as Element
        fun child(tag: String) = theme.getElementsByTagName(tag).item(0) as Element?
        return ThemeSpecXml.parse(attrs(theme)!!, attrs(child("light")), attrs(child("dark")), problems)
    }

    private fun roundTrip(spec: ThemeSpec): ThemeSpec {
        val problems = ArrayList<String>()
        val parsed = parseTheme("<keyboard>\n${ThemeSpecXml.write(spec)}\n</keyboard>", problems)
        assertWithMessage("the writer's own output parses cleanly").that(problems).isEmpty()
        return parsed
    }

    private fun argb(rgb: Long): Int = (0xFF000000L or rgb).toInt()

    @Test
    fun theWorkedExampleDefinesEveryFieldAndParsesWithoutProblems() {
        val problems = ArrayList<String>()
        val spec = parseTheme(example.readText(), problems)

        assertThat(problems).isEmpty()
        assertThat(spec.name).isEqualTo("Harbour")
        assertWithMessage("the example is the reference: it sets every colour role")
            .that(spec.colors.keys).containsExactlyElementsIn(ThemeColorRole.entries)
        assertThat(spec.colors[ThemeColorRole.KEY]).isEqualTo(argb(0xFFFFFF))
        assertWithMessage("#AARRGGBB keeps its alpha").that(spec.colors[ThemeColorRole.KEY_SHADOW]).isEqualTo(0x33102A43)
        assertThat(spec.colors[ThemeColorRole.BAR_SEPARATOR]).isEqualTo(0x00FFFFFF)
        assertThat(spec.geometry).isEqualTo(
            LookGeometry(
                keyGapHorizontal = 0.12f, keyGapVertical = 0.16f, cornerRadiusDp = 10f, borderWidthDp = 0f,
                shadowDyDp = 1f, labelScale = 1.15f, labelWeight = 500,
            )
        )
        assertThat(spec.functionChars).isEqualTo(",.")
        assertThat(spec.legends).isEqualTo(LookLegends.DIGITS)
        assertThat(spec.pillKeys).containsExactly(LookPillKey.ENTER, LookPillKey.ACTION)
        assertThat(spec.letterKeys).containsExactly(LookLetterKey.EMOJI)
        assertThat(spec.preview).isEqualTo(LookPreview(LookPreviewShape.BUBBLE, 1.3f, 1.25f, 1.2f, 0.2f, true))
        assertThat(spec.hintStyle).isEqualTo(LookHintStyle(0.4f, 400))
        assertThat(spec.dynamicColor).isFalse()
        assertThat(spec.bar).isEqualTo(LookBar(slots = 3, centreBest = true, quoteTyped = false))
        assertThat(spec.colors[ThemeColorRole.LATCHED_KEY]).isEqualTo(argb(0x174F80))
        assertThat(spec.light.keys).containsExactly(ThemeColorRole.KEYBOARD_BACKGROUND, ThemeColorRole.BAR_BACKGROUND)
        assertThat(spec.dark[ThemeColorRole.KEY]).isEqualTo(argb(0x1C2B3A))
        assertWithMessage("every <theme> attribute name is used by the example")
            .that(attrs(
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(example).getElementsByTagName("theme").item(0) as Element
            )!!.keys).containsAtLeastElementsIn(ThemeSpecXml.THEME_ATTRIBUTES)
    }

    @Test
    fun writeThenParseIsTheIdentity() {
        val example = parseTheme(this.example.readText())
        assertThat(roundTrip(example)).isEqualTo(example)
        for (look in KeyboardLooks.ALL) {
            val spec = ThemeSpecs.ofLook(look, name = look.id)
            assertWithMessage("${look.id} exported as <theme> and read back").that(roundTrip(spec)).isEqualTo(spec)
        }
        assertThat(roundTrip(ThemeSpec())).isEqualTo(ThemeSpec())
        val awkward = ThemeSpec(name = "Tom & \"Jerry\" <1>", functionChars = "&<\"", pillKeys = emptySet())
        assertWithMessage("free text is escaped; an empty pill set stays defined-as-empty")
            .that(roundTrip(awkward)).isEqualTo(awkward)
    }

    @Test
    fun parsingIsTolerantDroppedValuesAreReportedAndTheRestIsKept() {
        val problems = ArrayList<String>()
        val spec = parseTheme(
            """<keyboard><theme key="#12" label="#102A43" gap_horizontal="0.9" corner_radius="6"
                 hints="some" pill_keys="enter wobble" preview_width="1.2" sparkle="yes"
                 label_weight="950" hint_size="0.3">
                 <light key="red"/><dark label="#FFFFFF" corner_radius="3"/>
               </theme></keyboard>""",
            problems,
        )
        assertThat(problems).containsExactly(
            "<theme> key=\"#12\"", "<theme> sparkle=\"yes\"", "<theme> gap_horizontal=\"0.9\"",
            "<theme> label_weight=\"950\"", "<theme> hints=\"some\"", "<theme> preview_width=\"1.2\"",
            "<theme> pill_keys=\"enter wobble\"", "<light> key=\"red\"", "<dark> corner_radius=\"3\"",
        )
        assertThat(spec.colors).containsExactly(ThemeColorRole.LABEL, argb(0x102A43))
        assertThat(spec.geometry).isEqualTo(LookGeometry(cornerRadiusDp = 6f))
        assertThat(spec.legends).isNull()
        assertThat(spec.pillKeys).isNull()
        assertWithMessage("preview sizes without a preview shape size nothing").that(spec.preview).isNull()
        assertWithMessage("hint weight defaults when only the size is given")
            .that(spec.hintStyle).isEqualTo(LookHintStyle(0.3f, LookHintStyle.DEFAULT.weight))
        assertThat(spec.light).isEmpty()
        assertThat(spec.dark).containsExactly(ThemeColorRole.LABEL, argb(0xFFFFFF))
    }

    @Test
    fun aSpecInheritsWhatItLeavesUnsetAndItsCommonColourBeatsTheBasesVariant() {
        val base = ThemeSpec(
            colors = mapOf(ThemeColorRole.KEY to 1, ThemeColorRole.LABEL to 2),
            light = mapOf(ThemeColorRole.KEY to 3),
            geometry = LookGeometry(cornerRadiusDp = 8f, labelScale = 1.2f),
            legends = LookLegends.NONE,
        )
        val top = ThemeSpec(
            colors = mapOf(ThemeColorRole.KEY to 10),
            dark = mapOf(ThemeColorRole.KEY to 20, ThemeColorRole.ENTER_KEY to 30),
            geometry = LookGeometry(cornerRadiusDp = 4f),
        )
        val light = ThemeSpecs.resolve(top, base, systemLight = true)
        assertWithMessage("the top's common key beats the base's LIGHT variant")
            .that(light.colors[ThemeColorRole.KEY]).isEqualTo(10)
        assertThat(light.colors[ThemeColorRole.LABEL]).isEqualTo(2)
        assertThat(light.colors).doesNotContainKey(ThemeColorRole.ENTER_KEY)
        assertThat(light.geometry).isEqualTo(LookGeometry(cornerRadiusDp = 4f, labelScale = 1.2f))
        assertThat(light.legends).isEqualTo(LookLegends.NONE)
        assertThat(light.light).isEmpty()
        val dark = ThemeSpecs.resolve(top, base, systemLight = false)
        assertThat(dark.colors[ThemeColorRole.KEY]).isEqualTo(20)
        assertThat(dark.colors[ThemeColorRole.ENTER_KEY]).isEqualTo(30)
    }

    @Test
    fun theJsonExtensionRoundTripsAndOutOfRangeValuesAreDropped() {
        val example = parseTheme(this.example.readText())
        assertThat(ThemeSpecJson.fromJson(JSONObject(ThemeSpecJson.toJson(example).toString()))).isEqualTo(example)
        for (look in KeyboardLooks.ALL) {
            val spec = ThemeSpecs.ofLook(look)
            assertThat(ThemeSpecJson.fromJson(JSONObject(ThemeSpecJson.toJson(spec).toString()))).isEqualTo(spec)
        }
        assertWithMessage("floats are written as their shortest decimal")
            .that(ThemeSpecJson.toJson(example).toString()).contains("\"gapHorizontal\":0.12")
        val hostile = JSONObject(
            """{"geometry":{"gapHorizontal":7,"cornerRadiusDp":6,"labelWeight":5000},
               "preview":{"shape":"BUBBLE","width":40,"height":1},"hintStyle":{"size":9,"weight":400},
               "hints":"SOMETIMES","pillKeys":["ENTER","WOBBLE"],"futureKey":1}"""
        )
        val spec = ThemeSpecJson.fromJson(hostile)
        assertThat(spec.geometry).isEqualTo(LookGeometry(cornerRadiusDp = 6f))
        assertThat(spec.preview!!.widthScale).isEqualTo(ThemeSpecXml.PREVIEW_SCALE_RANGE.endInclusive)
        assertThat(spec.hintStyle).isNull()
        assertWithMessage("an unknown enum name from a newer build is dropped").that(spec.legends).isNull()
        assertThat(spec.pillKeys).containsExactly(LookPillKey.ENTER)
    }

    @Test
    fun aLayoutsThemeElementIsInsertedReplacedOrRemovedAndTheRestIsKept() {
        val spec = ThemeSpec(name = "Mine", colors = mapOf(ThemeColorRole.KEY to argb(0x123456)))
        val plain = "<keyboard name=\"A\">\n  <row><key c=\"a\"/></row>\n</keyboard>\n"
        val inserted = ThemeSpecXml.replaceInLayout(plain, spec)
        assertThat(inserted).startsWith("<keyboard name=\"A\">\n  <theme")
        assertThat(inserted).contains("<row><key c=\"a\"/></row>")
        assertThat(parseTheme(inserted)).isEqualTo(spec)
        val replaced = ThemeSpecXml.replaceInLayout(inserted, spec.copy(name = "Other"))
        assertThat(parseTheme(replaced).name).isEqualTo("Other")
        assertThat(Regex("<theme\\b").findAll(replaced).count()).isEqualTo(1)
        val withChildren = ThemeSpecXml.replaceInLayout(plain, spec.copy(dark = mapOf(ThemeColorRole.KEY to 5)))
        assertWithMessage("an element with <light>/<dark> children is removed whole")
            .that(ThemeSpecXml.replaceInLayout(withChildren, null)).isEqualTo(plain)
        assertThat(ThemeSpecXml.replaceInLayout(inserted, null)).isEqualTo(plain)
        assertThat(ThemeSpecXml.replaceInLayout(plain, null)).isEqualTo(plain)
        val commented = "<!-- uses <theme> and <keyboard> in prose -->\n$plain"
        assertWithMessage("mentions inside comments are not elements")
            .that(ThemeSpecXml.replaceInLayout(commented, spec)).isEqualTo("<!-- uses <theme> and <keyboard> in prose -->\n$inserted")
        val example = this.example.readText()
        assertThat(parseTheme(ThemeSpecXml.replaceInLayout(example, spec))).isEqualTo(spec)
    }

    @Test
    fun coloursParseInBothSpellingsAndFormatBackCanonically() {
        assertThat(ThemeValues.parseColor("#1f6fb2")).isEqualTo(argb(0x1F6FB2))
        assertThat(ThemeValues.parseColor(" #801F6FB2 ")).isEqualTo(0x801F6FB2.toInt())
        for (bad in listOf(null, "", "1F6FB2", "#1F6FB", "#1F6FB2A", "#GG0000", "red")) {
            assertWithMessage("'$bad'").that(ThemeValues.parseColor(bad)).isNull()
        }
        assertThat(ThemeValues.formatColor(argb(0x1F6FB2))).isEqualTo("#1F6FB2")
        assertThat(ThemeValues.formatColor(0x00FFFFFF)).isEqualTo("#00FFFFFF")
        assertThat(ThemeValues.formatFloat(8f)).isEqualTo("8")
        assertThat(ThemeValues.formatFloat(0.15f)).isEqualTo("0.15")
    }
}
