package tribixbite.cleverkeys.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM regression for [SettingsExporter]. Verifies the count returned
 * matches non-internal entries, that all SharedPreferences scalar types
 * round-trip, and that empty input yields zero.
 */
class SettingsExporterTest {

    @Test
    fun buildPreferencesJson_returnsCountMatchingNonInternalKeys() {
        val prefs: Map<String, Any?> = mapOf(
            "a" to 1, "b" to true, "c" to "hello",
            "version" to 99,                  // internal — must be skipped
            "current_layout_portrait" to 0,    // internal — must be skipped
        )
        val (obj, count) = SettingsExporter.buildPreferencesJson(prefs)

        assertThat(count).isEqualTo(3)
        assertThat(obj.has("a")).isTrue()
        assertThat(obj.has("b")).isTrue()
        assertThat(obj.has("c")).isTrue()
        assertThat(obj.has("version")).isFalse()
        assertThat(obj.has("current_layout_portrait")).isFalse()
    }

    @Test
    fun buildPreferencesJson_eachTypeRoundTrips() {
        val prefs: Map<String, Any?> = mapOf(
            "b" to true, "i" to 42, "l" to 1234L, "f" to 3.14f, "s" to "x",
            "set" to setOf("a", "b"),
        )
        val (obj, count) = SettingsExporter.buildPreferencesJson(prefs)

        assertThat(count).isEqualTo(6)
        assertThat(obj.get("b").asBoolean).isTrue()
        assertThat(obj.get("i").asInt).isEqualTo(42)
        assertThat(obj.get("l").asLong).isEqualTo(1234L)
        assertThat(obj.get("f").asFloat).isEqualTo(3.14f)
        assertThat(obj.get("s").asString).isEqualTo("x")
        val arr = obj.get("set").asJsonArray
        assertThat(listOf(arr.get(0).asString, arr.get(1).asString))
            .containsExactly("a", "b")
    }

    @Test
    fun buildPreferencesJson_emptyPrefs_returnsZero() {
        val (_, count) = SettingsExporter.buildPreferencesJson(emptyMap())
        assertThat(count).isEqualTo(0)
    }
}
