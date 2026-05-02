package tribixbite.cleverkeys.backup

import com.google.common.truth.Truth.assertThat
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
}
