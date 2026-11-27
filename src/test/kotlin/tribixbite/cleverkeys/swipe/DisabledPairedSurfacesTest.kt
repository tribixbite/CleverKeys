package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [DisabledPairedSurfaces] — which disabled words stay decodable so their paired contraction is
 * still offered (Saga release r21, case 10: a disabled `well` took `we'll` with it).
 */
class DisabledPairedSurfacesTest {

    private val paired = mapOf(
        "well" to listOf("we'll"),
        "were" to listOf("weren't", "we're"),
        "ill" to listOf("i'll"),
        "world" to listOf("world's"),
        "teams" to listOf("team's"),
        "you" to listOf("you's", "you'd", "you'll", "you're", "you've"),
        "ete" to listOf("e'te"),
    )
    private val base = listOf(
        "well" to 223.0, "were" to 229.0, "ill" to 198.0, "world" to 221.0, "you" to 241.0,
        "été" to 200.0, "Well" to 150.0, "teams" to 203.0,
    )
    private val strip: (String) -> String? = { w -> w.lowercase().filter { it in 'a'..'z' }.ifEmpty { null } }

    private fun keep(vararg disabled: String) =
        DisabledPairedSurfaces.keep(disabled.toSet(), base, { paired[it] }, strip)

    @Test
    fun `a disabled key whose contraction is spelled on its own path is kept at its frequency`() {
        assertThat(keep("well", "were", "ill"))
            .containsExactly("well", 223.0, "were", 229.0, "ill", 198.0)
    }

    @Test
    fun `a key whose only projection is a possessive is not kept`() {
        // `team's` is spelled on the path `teams`, but it must not take a disabled `teams`'s rank.
        assertThat(keep("teams")).isEmpty()
    }

    @Test
    fun `a key whose contractions are other paths is not kept`() {
        // `you're` is the path `youre` and `world's` the path `worlds`, each with its own key.
        assertThat(keep("you", "world")).isEmpty()
    }

    @Test
    fun `a word that is not its own surface or not in the base is not kept`() {
        assertThat(keep("été", "ete", "unknown")).isEmpty()
    }

    @Test
    fun `nothing disabled keeps nothing`() {
        assertThat(DisabledPairedSurfaces.keep(emptySet(), base, { paired[it] }, strip)).isEmpty()
    }
}
