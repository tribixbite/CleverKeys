package tribixbite.cleverkeys.backup

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import org.junit.Test

class SettingsImportPlanBuilderTest {

    private val screen = ScreenMetrics(width = 1080, height = 2400, density = 3.0f)

    @Test
    fun emptyDelta_whenJsonMatchesCurrentSnapshot() {
        val json = """{"metadata":{"app_version":"1.4.0","screen_width":1080,"screen_height":2400},"preferences":{"key_height":50}}"""
        val current = mapOf<String, Any?>("key_height" to 50)

        val plan = SettingsImportPlanBuilder.fromJson(json, current, screen)

        assertThat(plan.changes).isEmpty()
        assertThat(plan.parseSkippedKeys).isEmpty()
        assertThat(plan.internalRemoves).isEmpty()
        assertThat(plan.sourceVersion).isEqualTo("1.4.0")
    }

    @Test
    fun modifiedKey_intValueChanged() {
        val json = """{"preferences":{"key_height":60}}"""

        val plan = SettingsImportPlanBuilder.fromJson(json, mapOf("key_height" to 50), screen)

        assertThat(plan.changes).hasSize(1)
        val c = plan.changes.single()
        assertThat(c.key).isEqualTo("key_height")
        assertThat(c.type).isEqualTo(ChangeType.MODIFIED)
        assertThat(c.current).isEqualTo(PrefValue.IntV(50))
        assertThat(c.proposed).isEqualTo(PrefValue.IntV(60))
    }

    @Test
    fun addedKey_absentInSnapshot() {
        val json = """{"preferences":{"new_key":"hello"}}"""

        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)

        assertThat(plan.changes).hasSize(1)
        val c = plan.changes.single()
        assertThat(c.type).isEqualTo(ChangeType.ADDED)
        assertThat(c.current).isEqualTo(PrefValue.Unset)
        assertThat(c.proposed).isEqualTo(PrefValue.Str("hello"))
    }

    @Test
    fun prefValueVariants_eachJsonTypeMaterializesCorrectly() {
        val json = """{
            "preferences":{
                "b":true, "i":42, "f":3.14, "s":"hi"
            }
        }""".trimIndent()

        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)
        val byKey = plan.changes.associateBy { it.key }

        assertThat(byKey["b"]!!.proposed).isEqualTo(PrefValue.Bool(true))
        assertThat(byKey["i"]!!.proposed).isEqualTo(PrefValue.IntV(42))
        // Gson treats 3.14 as Double — caller decides Float vs Double.
        // Settings uses Float; verify FloatV with the original literal.
        assertThat(byKey["f"]!!.proposed).isEqualTo(PrefValue.FloatV(3.14f))
        assertThat(byKey["s"]!!.proposed).isEqualTo(PrefValue.Str("hi"))
    }

    @Test
    fun jsonBlobPref_layoutsKey_classifiedAsJsonBlob() {
        val rawLayouts = """[{"name":"qwerty"},{"name":"dvorak"}]"""
        val json = """{"preferences":{"layouts":$rawLayouts}}"""

        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)

        val c = plan.changes.single { it.key == "layouts" }
        assertThat(c.proposed).isInstanceOf(PrefValue.JsonBlob::class.java)
        // Canonicalized via JsonParser → toString — round-trip equality is the contract.
        val canonical = JsonParser.parseString(rawLayouts).toString()
        assertThat((c.proposed as PrefValue.JsonBlob).raw).isEqualTo(canonical)
    }

    @Test
    fun legacyHorizontalMargin_synthesizesNewKeysAndQueuesRemove() {
        val json = """{"preferences":{"horizontal_margin_portrait":24}}"""
        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)

        // The legacy key never lands in `changes` (user wouldn't see "old key" rows).
        assertThat(plan.changes.map { it.key }).containsExactly("margin_left_portrait", "margin_right_portrait")
        // Both new keys are ADDED (snapshot is empty).
        assertThat(plan.changes.all { it.type == ChangeType.ADDED }).isTrue()
        // The legacy key's removal is queued so apply explicitly drops it from prefs.
        assertThat(plan.internalRemoves).containsExactly("horizontal_margin_portrait")
        // And it's listed in parseSkippedKeys so the user can audit what was dropped.
        assertThat(plan.parseSkippedKeys.map { it.key }).contains("horizontal_margin_portrait")
    }

    @Test
    fun legacyHorizontalMargins_allFourPairsConverted() {
        // Density 3.0, width 1080: 24dp * 3.0 = 72px ; 72/1080 = 0.0667 → 6%.
        val json = """{"preferences":{
            "horizontal_margin_portrait":24,
            "horizontal_margin_landscape":24,
            "horizontal_margin_portrait_unfolded":24,
            "horizontal_margin_landscape_unfolded":24
        }}""".trimIndent()
        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)

        // Eight ADDED rows expected (left + right per orientation × 4).
        val addedKeys = plan.changes.filter { it.type == ChangeType.ADDED }.map { it.key }.toSet()
        assertThat(addedKeys).containsAtLeast(
            "margin_left_portrait", "margin_right_portrait",
            "margin_left_landscape", "margin_right_landscape",
            "margin_left_portrait_unfolded", "margin_right_portrait_unfolded",
            "margin_left_landscape_unfolded", "margin_right_landscape_unfolded",
        )
        // All four legacy keys queued for removal.
        assertThat(plan.internalRemoves.toSet()).containsExactly(
            "horizontal_margin_portrait",
            "horizontal_margin_landscape",
            "horizontal_margin_portrait_unfolded",
            "horizontal_margin_landscape_unfolded",
        )
    }

    @Test
    fun legacyBottomMargin_dpAboveThirty_convertedToPercent() {
        // 50dp * 3.0 / 2400 * 100 = 6.25 → 6%.
        val json = """{"preferences":{"margin_bottom_portrait":50}}"""
        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)

        val change = plan.changes.single { it.key == "margin_bottom_portrait" }
        val proposed = change.proposed as PrefValue.IntV
        assertThat(proposed.v).isLessThan(30)
        assertThat(proposed.v).isAtLeast(1)
    }

    @Test
    fun legacyBottomMargin_valueAtOrBelowThirty_passesThrough() {
        // ≤30 may already be a percent — leave alone.
        val json = """{"preferences":{"margin_bottom_portrait":15}}"""
        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)

        val change = plan.changes.single { it.key == "margin_bottom_portrait" }
        assertThat((change.proposed as PrefValue.IntV).v).isEqualTo(15)
    }
}
