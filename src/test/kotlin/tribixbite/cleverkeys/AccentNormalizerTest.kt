package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for AccentNormalizer.
 *
 * These tests run without Robolectric or Android dependencies,
 * making them compatible with ARM64/Termux environments.
 */
class AccentNormalizerTest {

    // =========================================================================
    // normalize() tests
    // =========================================================================

    @Test
    fun `normalize removes French accents`() {
        assertThat(AccentNormalizer.normalize("café")).isEqualTo("cafe")
        assertThat(AccentNormalizer.normalize("naïve")).isEqualTo("naive")
        assertThat(AccentNormalizer.normalize("résumé")).isEqualTo("resume")
        assertThat(AccentNormalizer.normalize("français")).isEqualTo("francais")
        assertThat(AccentNormalizer.normalize("garçon")).isEqualTo("garcon")
    }

    @Test
    fun `normalize removes German umlauts`() {
        assertThat(AccentNormalizer.normalize("München")).isEqualTo("munchen")
        assertThat(AccentNormalizer.normalize("schön")).isEqualTo("schon")
        assertThat(AccentNormalizer.normalize("größe")).isEqualTo("grosse")
        assertThat(AccentNormalizer.normalize("über")).isEqualTo("uber")
        assertThat(AccentNormalizer.normalize("ähnlich")).isEqualTo("ahnlich")
    }

    @Test
    fun `normalize handles German sharp s`() {
        assertThat(AccentNormalizer.normalize("straße")).isEqualTo("strasse")
        assertThat(AccentNormalizer.normalize("größe")).isEqualTo("grosse")
        assertThat(AccentNormalizer.normalize("weißen")).isEqualTo("weissen")
    }

    @Test
    fun `normalize removes Spanish accents`() {
        assertThat(AccentNormalizer.normalize("señor")).isEqualTo("senor")
        assertThat(AccentNormalizer.normalize("niño")).isEqualTo("nino")
        assertThat(AccentNormalizer.normalize("España")).isEqualTo("espana")
        assertThat(AccentNormalizer.normalize("jalapeño")).isEqualTo("jalapeno")
    }

    @Test
    fun `normalize removes Nordic characters`() {
        assertThat(AccentNormalizer.normalize("København")).isEqualTo("kobenhavn")
        assertThat(AccentNormalizer.normalize("fjørd")).isEqualTo("fjord")
        assertThat(AccentNormalizer.normalize("Ångström")).isEqualTo("angstrom")
    }

    @Test
    fun `normalize handles Icelandic characters`() {
        assertThat(AccentNormalizer.normalize("Þórr")).isEqualTo("thorr")
        assertThat(AccentNormalizer.normalize("Reykjavík")).isEqualTo("reykjavik")
    }

    @Test
    fun `normalize handles ligatures`() {
        assertThat(AccentNormalizer.normalize("Ærodynamic")).isEqualTo("aerodynamic")
        assertThat(AccentNormalizer.normalize("œuvre")).isEqualTo("oeuvre")
        assertThat(AccentNormalizer.normalize("Cœur")).isEqualTo("coeur")
    }

    @Test
    fun `normalize handles Polish characters`() {
        assertThat(AccentNormalizer.normalize("Łódź")).isEqualTo("lodz")
        assertThat(AccentNormalizer.normalize("żółty")).isEqualTo("zolty")
    }

    @Test
    fun `normalize handles Turkish dotless i`() {
        assertThat(AccentNormalizer.normalize("Istanbulı")).isEqualTo("istanbuli")
    }

    @Test
    fun `normalize handles empty string`() {
        assertThat(AccentNormalizer.normalize("")).isEqualTo("")
    }

    @Test
    fun `normalize leaves ASCII unchanged`() {
        assertThat(AccentNormalizer.normalize("hello")).isEqualTo("hello")
        assertThat(AccentNormalizer.normalize("WORLD")).isEqualTo("world")
        assertThat(AccentNormalizer.normalize("Test123")).isEqualTo("test123")
    }

    @Test
    fun `normalize converts to lowercase`() {
        assertThat(AccentNormalizer.normalize("HELLO")).isEqualTo("hello")
        assertThat(AccentNormalizer.normalize("CAFÉ")).isEqualTo("cafe")
    }

    // =========================================================================
    // hasAccents() tests
    // =========================================================================

    @Test
    fun `hasAccents returns true for accented words`() {
        assertThat(AccentNormalizer.hasAccents("café")).isTrue()
        assertThat(AccentNormalizer.hasAccents("naïve")).isTrue()
        assertThat(AccentNormalizer.hasAccents("München")).isTrue()
        assertThat(AccentNormalizer.hasAccents("straße")).isTrue()
    }

    @Test
    fun `hasAccents returns false for ASCII words`() {
        assertThat(AccentNormalizer.hasAccents("hello")).isFalse()
        assertThat(AccentNormalizer.hasAccents("world")).isFalse()
        assertThat(AccentNormalizer.hasAccents("test123")).isFalse()
    }

    @Test
    fun `hasAccents returns false for empty string`() {
        assertThat(AccentNormalizer.hasAccents("")).isFalse()
    }

    // =========================================================================
    // normalizePreservingCase() tests
    // =========================================================================

    @Test
    fun `normalizePreservingCase preserves lowercase`() {
        assertThat(AccentNormalizer.normalizePreservingCase("café")).isEqualTo("cafe")
        assertThat(AccentNormalizer.normalizePreservingCase("naïve")).isEqualTo("naive")
    }

    @Test
    fun `normalizePreservingCase preserves title case`() {
        assertThat(AccentNormalizer.normalizePreservingCase("Café")).isEqualTo("Cafe")
        assertThat(AccentNormalizer.normalizePreservingCase("München")).isEqualTo("Munchen")
    }

    @Test
    fun `normalizePreservingCase preserves all caps`() {
        assertThat(AccentNormalizer.normalizePreservingCase("CAFÉ")).isEqualTo("CAFE")
        assertThat(AccentNormalizer.normalizePreservingCase("MÜNCHEN")).isEqualTo("MUNCHEN")
    }

    @Test
    fun `normalizePreservingCase handles empty string`() {
        assertThat(AccentNormalizer.normalizePreservingCase("")).isEqualTo("")
    }

    // =========================================================================
    // buildAccentMap() tests
    // =========================================================================

    @Test
    fun `buildAccentMap groups words by normalized form`() {
        val words = listOf(
            "café" to 1000,
            "cafe" to 500,
            "schön" to 800,
            "schon" to 600
        )

        val accentMap = AccentNormalizer.buildAccentMap(words)

        assertThat(accentMap).hasSize(2)
        assertThat(accentMap["cafe"]).hasSize(2)
        assertThat(accentMap["schon"]).hasSize(2)
    }

    @Test
    fun `buildAccentMap sorts by frequency descending`() {
        val words = listOf(
            "cafe" to 500,
            "café" to 1000
        )

        val accentMap = AccentNormalizer.buildAccentMap(words)
        val cafeForms = accentMap["cafe"]!!

        // Higher frequency first
        assertThat(cafeForms[0].first).isEqualTo("café")
        assertThat(cafeForms[0].second).isEqualTo(1000)
        assertThat(cafeForms[1].first).isEqualTo("cafe")
        assertThat(cafeForms[1].second).isEqualTo(500)
    }

    @Test
    fun `buildAccentMap handles empty list`() {
        val accentMap = AccentNormalizer.buildAccentMap(emptyList())
        assertThat(accentMap).isEmpty()
    }

    // =========================================================================
    // getBestCanonical() tests
    // =========================================================================

    @Test
    fun `getBestCanonical returns highest frequency form`() {
        val words = listOf(
            "cafe" to 500,
            "café" to 1000
        )
        val accentMap = AccentNormalizer.buildAccentMap(words)

        val best = AccentNormalizer.getBestCanonical("cafe", accentMap)

        assertThat(best).isEqualTo("café")
    }

    @Test
    fun `getBestCanonical returns null for unknown word`() {
        val accentMap = AccentNormalizer.buildAccentMap(emptyList())

        val result = AccentNormalizer.getBestCanonical("unknown", accentMap)

        assertThat(result).isNull()
    }

    // =========================================================================
    // getAllCanonicals() tests
    // =========================================================================

    @Test
    fun `getAllCanonicals returns all forms sorted by frequency`() {
        val words = listOf(
            "cafe" to 500,
            "café" to 1000,
            "cafè" to 300
        )
        val accentMap = AccentNormalizer.buildAccentMap(words)

        val allForms = AccentNormalizer.getAllCanonicals("cafe", accentMap)

        assertThat(allForms).containsExactly("café", "cafe", "cafè").inOrder()
    }

    @Test
    fun `getAllCanonicals returns empty list for unknown word`() {
        val accentMap = AccentNormalizer.buildAccentMap(emptyList())

        val result = AccentNormalizer.getAllCanonicals("unknown", accentMap)

        assertThat(result).isEmpty()
    }

    // =========================================================================
    // Edge cases and regression tests
    // =========================================================================

    @Test
    fun `normalize handles combined diacritical marks`() {
        // Precomposed vs decomposed forms
        val precomposed = "é" // U+00E9
        val decomposed = "é"  // e + U+0301 (combining acute)

        assertThat(AccentNormalizer.normalize(precomposed)).isEqualTo("e")
        assertThat(AccentNormalizer.normalize(decomposed)).isEqualTo("e")
    }

    @Test
    fun `normalize handles Vietnamese characters`() {
        assertThat(AccentNormalizer.normalize("Việt")).isEqualTo("viet")
        assertThat(AccentNormalizer.normalize("Đà")).isEqualTo("da")
    }

    @Test
    fun `normalize handles multiple accents on same word`() {
        assertThat(AccentNormalizer.normalize("résumé")).isEqualTo("resume")
        assertThat(AccentNormalizer.normalize("naïveté")).isEqualTo("naivete")
    }
}
