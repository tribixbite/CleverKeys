package tribixbite.cleverkeys.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Roadmap §4.1: the pure scene plan behind the theme-editor keyboard preview — which
 * trail effect is drawn, how the localized sample words parse, and the static sample
 * trail through the layout's letter keys.
 */
class ThemePreviewSceneTest {

    private val centers: Map<Char, Pair<Float, Float>> = mapOf(
        'c' to Pair(30f, 300f),
        'o' to Pair(90f, 100f),
        'l' to Pair(85f, 200f),
        'r' to Pair(40f, 100f),
        'a' to Pair(5f, 200f),
    )

    @Test
    fun configuredEffectIsKeptWhenTheTrailIsVisible() {
        for (effect in listOf("glow", "sparkle", "solid", "fade", "rainbow")) {
            assertThat(ThemePreviewScene.previewTrailEffect(true, effect)).isEqualTo(effect)
        }
    }

    @Test
    fun disabledOrInvisibleTrailFallsBackSoTheColourStaysJudgeable() {
        assertThat(ThemePreviewScene.previewTrailEffect(false, "glow"))
            .isEqualTo(ThemePreviewScene.FALLBACK_TRAIL_EFFECT)
        assertThat(ThemePreviewScene.previewTrailEffect(true, "none"))
            .isEqualTo(ThemePreviewScene.FALLBACK_TRAIL_EFFECT)
        // The fallback must itself be a visible effect.
        assertThat(ThemePreviewScene.FALLBACK_TRAIL_EFFECT).isNotEqualTo("none")
    }

    @Test
    fun sampleWordsTrimDropBlanksAndCap() {
        assertThat(ThemePreviewScene.sampleWords(" color , colors,, colorful ,extra"))
            .containsExactly("color", "colors", "colorful").inOrder()
        assertThat(ThemePreviewScene.sampleWords("")).isEmpty()
    }

    @Test
    fun trailVisitsEachLetterKeyCentreInWordOrder() {
        val points = ThemePreviewScene.trailPoints("Color", centers, samplesPerSegment = 2)
        // c o l o r: 5 anchors, 4 segments x 2 samples + start point.
        assertThat(points).hasSize(9)
        assertThat(points.first()).isEqualTo(30f to 300f)
        assertThat(points[2]).isEqualTo(90f to 100f) // o
        assertThat(points[4]).isEqualTo(85f to 200f) // l
        assertThat(points.last()).isEqualTo(40f to 100f) // r
        // Interpolated midpoint between c and o.
        assertThat(points[1]).isEqualTo(60f to 200f)
    }

    @Test
    fun repeatedLettersCollapseAndMissingLettersAreSkipped() {
        val points = ThemePreviewScene.trailPoints("coollz", centers, samplesPerSegment = 1)
        // c, o (oo collapsed), l (ll collapsed), z missing.
        assertThat(points).containsExactly(30f to 300f, Pair(90f, 100f), Pair(85f, 200f)).inOrder()
    }

    @Test
    fun wordOutsideTheLayoutFallsBackToLeftMiddleRightKeys() {
        val points = ThemePreviewScene.trailPoints("цвет", centers, samplesPerSegment = 1)
        // Sorted by x: a(5) c(30) r(40) l(85) o(90) -> left a, middle r, right o.
        assertThat(points).containsExactly(5f to 200f, Pair(40f, 100f), Pair(90f, 100f)).inOrder()
    }

    @Test
    fun layoutWithoutTwoLetterKeysHasNoTrail() {
        assertThat(ThemePreviewScene.trailPoints("a", mapOf('a' to (1f to 1f)))).isEmpty()
        assertThat(ThemePreviewScene.trailPoints("abc", emptyMap())).isEmpty()
    }

    @Test
    fun lockedModifierCandidatesNeverIncludeShift() {
        // Shift is the ACTIVATED (latched) sample; locking it too would hide that state.
        assertThat(ThemePreviewScene.LOCKED_MODIFIER_CANDIDATES).doesNotContain("shift")
        assertThat(ThemePreviewScene.LOCKED_MODIFIER_CANDIDATES.first()).isEqualTo("ctrl")
    }
}
