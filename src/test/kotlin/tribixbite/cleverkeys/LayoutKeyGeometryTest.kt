package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.LayoutKeyGeometry.Choice
import tribixbite.cleverkeys.theme.KeyboardLookGeometry
import tribixbite.cleverkeys.theme.LookGeometry

/**
 * Per-layout key gaps and border (maintainer 2026-10-27: "layouts can override/set
 * layout-specific key gap and border, so user setting acts as global default").
 *
 * Pins the stored three-state entry, XML defaults, export, editor state and — above all — the
 * precedence: user per-layout choice > layout XML default > effective look > global setting.
 * Spec: docs/specs/layout-system.md "Per-layout key geometry".
 */
class LayoutKeyGeometryTest {

    private val H = KeyGeometryField.HORIZONTAL_GAP
    private val V = KeyGeometryField.VERTICAL_GAP
    private val B = KeyGeometryField.BORDER
    private val R = KeyGeometryField.BORDER_RADIUS
    private val W = KeyGeometryField.BORDER_WIDTH

    // ── stored entries ──────────────────────────────────────────────────────────

    @Test
    fun storedEntriesKeepValidValuesAndInheritAndDropGarbage() {
        val entry = LayoutKeyGeometry.normalizeEntry(
            mapOf(
                "horizontal_gap" to 3.5,
                "vertical_gap" to "INHERIT",
                "border" to true,
                "border_radius" to 101, // out of the import envelope 0..100
                "border_width" to "wide", // not a number
                "unknown" to 1,
            ),
        )
        assertThat(entry).containsExactly(H, Choice.Value(3.5f), V, Choice.Inherit, B, Choice.Value(1f))
        assertThat(LayoutKeyGeometry.normalizeEntry(mapOf("horizontal_gap" to 9))).isNull()
        assertThat(LayoutKeyGeometry.normalizeEntry(emptyMap())).isNull()
        assertThat(LayoutKeyGeometry.normalizeEntry(null)).isNull()
        assertWithMessage("NaN never binds").that(LayoutKeyGeometry.normalizeEntry(mapOf("vertical_gap" to Double.NaN))).isNull()
    }

    @Test
    fun rawFormRoundTripsThroughNormalize() {
        val entry = mapOf(H to Choice.Value(1.35f), B to Choice.Value(0f), R to Choice.Value(12f), W to Choice.Inherit)
        val raw = LayoutKeyGeometry.toRaw(entry)!!
        assertThat(raw).containsExactly(
            "horizontal_gap", 1.350000023841858, "border", false, "border_radius", 12L, "border_width", "inherit",
        )
        assertThat(LayoutKeyGeometry.normalizeEntry(raw)).isEqualTo(entry)
        assertWithMessage("an untouched entry stores nothing (byte-identical preference)")
            .that(LayoutKeyGeometry.toRaw(emptyMap())).isNull()
    }

    // ── XML defaults and effective overrides ────────────────────────────────────

    @Test
    fun xmlDefaultsAreParsedTolerantlyAndProblemsReported() {
        val declared = mapOf(
            "key_horizontal_gap" to "4", "key_border" to "true", "key_border_radius" to "x", "key_border_width" to "11",
        )
        assertThat(LayoutKeyGeometry.declaredValues(declared)).containsExactly(H, 4f, B, 1f)
        assertThat(LayoutKeyGeometry.xmlProblems(declared)).containsExactly("key_border_radius", "key_border_width")
        assertThat(LayoutKeyGeometry.xmlProblems(mapOf("key_vertical_gap" to "0.5"))).isEmpty()
    }

    @Test
    fun userChoiceBeatsXmlDefaultAndInheritRemovesIt() {
        val declared = mapOf(H to 4f, V to 1f, B to 1f)
        val entry = mapOf(H to Choice.Value(0.5f), V to Choice.Inherit)
        assertThat(LayoutKeyGeometry.effective(entry, declared)).containsExactly(H, 0.5f, B, 1f)
        assertThat(LayoutKeyGeometry.effective(null, declared)).isEqualTo(declared)
        assertThat(LayoutKeyGeometry.effective(null, emptyMap())).isEmpty()
    }

    @Test
    fun entryForStoresOnlyWhatDiffersFromTheXml() {
        val declared = mapOf(H to 4f, B to 1f)
        // Keep the XML gap, follow global for the border, own vertical gap.
        val stored = LayoutKeyGeometry.entryFor(mapOf(H to 4f, V to 2f, B to null), declared)
        assertThat(stored).containsExactly(V, Choice.Value(2f), B, Choice.Inherit)
        assertWithMessage("everything inherited on a layout without XML defaults stores nothing")
            .that(LayoutKeyGeometry.entryFor(KeyGeometryField.entries.associateWith { null }, emptyMap())).isNull()
        assertWithMessage("the editor's starting state reproduces the effective values")
            .that(LayoutKeyGeometryEditor.initialChoices(stored, declared))
            .isEqualTo(mapOf(H to 4f, V to 2f, B to null, R to null, W to null))
    }

    @Test
    fun exportWritesValuesRemovesInheritedAndKeepsTheRest() {
        val xml = """<keyboard name="T" key_border="true" key_vertical_gap='1'><row/></keyboard>"""
        val out = LayoutKeyGeometry.applyToXml(xml, mapOf(H to Choice.Value(2.5f), B to Choice.Inherit))
        assertThat(out).contains("key_horizontal_gap=\"2.5\"")
        assertThat(out).doesNotContain("key_border=")
        assertThat(out).contains("key_vertical_gap='1'")
        assertThat(out).endsWith("<row/></keyboard>")
        assertWithMessage("re-import reads back the exported values")
            .that(LayoutKeyGeometry.declaredValues(LayoutKeyGeometry.declaredOfXml(out)))
            .containsExactly(H, 2.5f, V, 1f)
        assertThat(LayoutKeyGeometry.applyToXml(xml, null)).isEqualTo(xml)
    }

    // ── precedence ──────────────────────────────────────────────────────────────

    private val density = 2f
    private val keyWidth = 100f
    private val rowHeight = 50f
    private val gap: (Float) -> Float = { it * density / 100f }
    private val globalsOff = LayoutKeyGeometry.Globals(borderOn = false, borderRadiusFraction = 0.05f, borderWidthPx = 2f)
    private val globalsOn = globalsOff.copy(borderOn = true)

    /** Global setting (user) → look → layout, exactly as Theme.Computed composes them. */
    private fun compose(
        look: LookGeometry?,
        overrides: Map<KeyGeometryField, Float>,
        globals: LayoutKeyGeometry.Globals,
    ): KeyboardLookGeometry.Effective {
        // The user layer sees the layout's border switch (a layout OFF drops the custom frame).
        val borderOn = LayoutKeyGeometry.effectiveBorderOn(overrides, globals.borderOn)
        val user = KeyboardLookGeometry.UserGeometry(
            verticalMargin = 1.5f * density / 100f * rowHeight,
            horizontalMargin = 2f * density / 100f * keyWidth,
            borderRadius = if (borderOn) globals.borderRadiusFraction * keyWidth else 7f,
            borderWidth = if (borderOn) globals.borderWidthPx else 1f,
            borderWidthActivated = if (borderOn) globals.borderWidthPx else 1.5f,
        )
        val resolved = KeyboardLookGeometry.resolve(look, user, keyWidth, rowHeight, density)
        return LayoutKeyGeometry.applyTo(resolved, overrides, globals, keyWidth, rowHeight, density)
    }

    @Test
    fun noOverridesIsExactlyTheLookOrGlobalGeometry() {
        val look = LookGeometry(keyGapHorizontal = 0.1f, cornerRadiusDp = 8f)
        val resolved = KeyboardLookGeometry.resolve(
            look, KeyboardLookGeometry.UserGeometry(1f, 2f, 3f, 4f, 5f), keyWidth, rowHeight, density,
        )
        assertThat(LayoutKeyGeometry.applyTo(resolved, emptyMap(), globalsOn, keyWidth, rowHeight, density))
            .isSameInstanceAs(resolved)
    }

    @Test
    fun layoutGapBeatsTheLookAndTheGlobalSetting() {
        val look = LookGeometry(keyGapHorizontal = 0.1f, keyGapVertical = 0.2f)
        val g = compose(look, mapOf(H to 5f), globalsOff)
        assertThat(g.horizontalMargin).isWithin(1e-4f).of(5f * density / 100f * keyWidth)
        assertWithMessage("a field the layout does not override keeps the look's value")
            .that(g.verticalMargin).isWithin(1e-4f).of(0.2f * rowHeight)
        val noLook = compose(null, mapOf(V to 0f), globalsOff)
        assertThat(noLook.verticalMargin).isEqualTo(0f)
        assertThat(noLook.horizontalMargin).isWithin(1e-4f).of(gap(2f) * keyWidth)
    }

    @Test
    fun layoutBorderBeatsTheLooksCornerAndOutline() {
        val look = LookGeometry(cornerRadiusDp = 8f, borderWidthDp = 0f, shadowDyDp = 1f)
        val g = compose(look, mapOf(B to 1f, R to 20f, W to 3f), globalsOff)
        assertThat(g.borderRadius).isWithin(1e-4f).of(0.2f * keyWidth)
        assertThat(g.borderWidth).isWithin(1e-4f).of(3f * density)
        assertThat(g.borderWidthActivated).isWithin(1e-4f).of(3f * density)
        assertWithMessage("the look's shadow is not a gap/border field and stays")
            .that(g.shadowDy).isWithin(1e-4f).of(1f * density)
    }

    @Test
    fun aRadiusAloneUsesTheGlobalSwitchAndGlobalWidth() {
        val on = compose(null, mapOf(R to 10f), globalsOn)
        assertThat(on.borderRadius).isWithin(1e-4f).of(0.1f * keyWidth)
        assertThat(on.borderWidth).isEqualTo(globalsOn.borderWidthPx)
        assertWithMessage("with the global border off, a radius alone does not turn a border on")
            .that(compose(null, mapOf(R to 10f), globalsOff).borderRadius).isEqualTo(7f)
    }

    @Test
    fun borderOffForTheLayoutKeepsTheThemeOrLookFrame() {
        // Global custom border ON, layout OFF: the theme's own frame (7 / 1 / 1.5 here)…
        val g = compose(null, mapOf(B to 0f), globalsOn)
        assertThat(g.borderRadius).isEqualTo(7f)
        assertThat(g.borderWidth).isEqualTo(1f)
        assertThat(g.borderWidthActivated).isEqualTo(1.5f)
        // …or, under a look, the look's corner.
        val look = compose(LookGeometry(cornerRadiusDp = 8f), mapOf(B to 0f), globalsOn)
        assertThat(look.borderRadius).isWithin(1e-4f).of(8f * density)
    }

    @Test
    fun globalBorderSwitchSeenByTheUserLayerHonoursALayoutOverride() {
        assertThat(LayoutKeyGeometry.effectiveBorderOn(mapOf(B to 0f), globalBorderOn = true)).isFalse()
        assertThat(LayoutKeyGeometry.effectiveBorderOn(mapOf(B to 1f), globalBorderOn = false)).isTrue()
        assertThat(LayoutKeyGeometry.effectiveBorderOn(emptyMap(), globalBorderOn = true)).isTrue()
    }

    // ── backup preview ──────────────────────────────────────────────────────────

    @Test
    fun backupPreviewListsPerLayoutGeometryChangesFromSanitisedValues() {
        fun blob(json: String) = com.google.gson.JsonParser.parseString(json).asJsonArray
        val cur = blob(
            """["latn_qwerty_us",{"kind":"custom","xml":"<keyboard name=\"C\" key_border=\"true\"><row/></keyboard>"},""" +
                """{"kind":"system"}]""",
        )
        val prop = blob(
            """[{"kind":"named","name":"latn_qwerty_us","key_geometry":{"horizontal_gap":3}},""" +
                """{"kind":"custom","xml":"<keyboard name=\"C\" key_border=\"true\"><row/></keyboard>","key_geometry":{"border":"inherit"}},""" +
                """{"kind":"system","key_geometry":{"vertical_gap":99}}]""",
        )
        val changes = tribixbite.cleverkeys.backup.LayoutThemeBindingDiff.geometryChanges(cur, prop)
        assertThat(changes.map { Triple(it.name, it.from, it.to) }).containsExactly(
            Triple("latn_qwerty_us", emptyMap<KeyGeometryField, Float>(), mapOf(H to 3f)),
            Triple("C", mapOf(B to 1f), emptyMap<KeyGeometryField, Float>()),
        ).inOrder()
        assertWithMessage("an out-of-range value is dropped like loading drops it: no change shown")
            .that(changes.none { it.kind == tribixbite.cleverkeys.backup.LayoutThemeBindingDiff.Kind.SYSTEM }).isTrue()
        assertThat(tribixbite.cleverkeys.backup.LayoutThemeBindingDiff.themeChanges(cur, prop)).isEmpty()
    }

    // ── editor ──────────────────────────────────────────────────────────────────

    @Test
    fun editorSnapsToTheGlobalSlidersStepAndShowsInheritedValues() {
        assertThat(LayoutKeyGeometryEditor.snap(H, 1.3499f)).isEqualTo(1.35f)
        assertThat(LayoutKeyGeometryEditor.snap(R, 7.6f)).isEqualTo(8f)
        assertThat(LayoutKeyGeometryEditor.snap(W, 42f)).isEqualTo(10f)
        assertThat(LayoutKeyGeometryEditor.sliderValue(H, own = null, global = 2f)).isEqualTo(2f)
        assertWithMessage("a legacy global radius above the slider is shown at the slider's end")
            .that(LayoutKeyGeometryEditor.sliderValue(R, own = null, global = 60f)).isEqualTo(20f)
        assertThat(sliderSteps(H.sliderRange, H.increment)).isEqualTo(99)
        assertThat(sliderSteps(W.sliderRange, W.increment)).isEqualTo(9)
    }

    @Test
    fun fieldsMirrorTheGlobalPreferencesAndTheirRanges() {
        assertThat(KeyGeometryField.entries.map { it.prefKey }).containsExactly(
            "key_horizontal_margin", "key_vertical_margin", "border_config", "custom_border_radius", "custom_border_line_width",
        )
        assertThat(R.sliderRange).isEqualTo(SettingsRanges.CUSTOM_BORDER_RADIUS.toSliderRange())
        assertThat(R.range).isEqualTo(SettingsRanges.ImportEnvelope.CUSTOM_BORDER_RADIUS.toSliderRange())
        assertThat(W.range).isEqualTo(SettingsRanges.CUSTOM_BORDER_LINE_WIDTH)
    }
}
