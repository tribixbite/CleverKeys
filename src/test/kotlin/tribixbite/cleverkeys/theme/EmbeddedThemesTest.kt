package tribixbite.cleverkeys.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Embedded layout-theme ids ([EmbeddedThemes]): equal specs share an id, a different spec or a
 * different global theme under it gives a different id (that is what re-inflates the keyboard
 * view), and every id parses back to its spec and base. Pure JVM.
 */
class EmbeddedThemesTest {

    private val a = ThemeSpec(name = "A", colors = mapOf(ThemeColorRole.KEY to 1))
    private val b = ThemeSpec(name = "B", colors = mapOf(ThemeColorRole.KEY to 2))

    @Test
    fun equalSpecsShareAnIdAndChangesOfSpecOrBaseChangeIt() {
        val id = EmbeddedThemes.idFor(a, "rosepine")
        assertThat(id).startsWith(EmbeddedThemes.ID_PREFIX)
        assertThat(EmbeddedThemes.idFor(a.copy(), "rosepine")).isEqualTo(id)
        assertThat(EmbeddedThemes.idFor(b, "rosepine")).isNotEqualTo(id)
        assertThat(EmbeddedThemes.idFor(a, "look_gboard")).isNotEqualTo(id)
    }

    @Test
    fun idsParseBackToTheirSpecAndBaseUnknownOnesDoNot() {
        val id = EmbeddedThemes.idFor(b, "custom_3F2504E0-4F89-11D3-9A0C-0305E82C3301")
        assertThat(EmbeddedThemes.parse(id)).isEqualTo(
            EmbeddedThemes.Parsed(b, "custom_3F2504E0-4F89-11D3-9A0C-0305E82C3301")
        )
        assertThat(EmbeddedThemes.parse("embedded_999999.dark")).isNull()
        assertThat(EmbeddedThemes.parse("embedded_x.dark")).isNull()
        assertThat(EmbeddedThemes.parse("embedded_")).isNull()
        assertThat(EmbeddedThemes.parse("rosepine")).isNull()
    }
}
