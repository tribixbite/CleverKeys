package tribixbite.cleverkeys.theme

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The pure half of the display-hardware backdrop ([SceneOverlayPlan]): when the layers may run
 * and where each goes on a frame. The geometry must match what `BackdropDrawable.drawDrifting`
 * paints in the window, so switching between the two never moves the curtains.
 */
class SceneOverlayPlanTest {

    private fun eligible(
        sdk: Int = 34, setting: Boolean = true, moving: Boolean = true, alt: Boolean = true,
        mesh: Boolean = false, rain: Boolean = false, alpha: Int = 0xFF, filter: Boolean = false,
        drift: Boolean = false,
    ) = SceneOverlayPlan.eligible(sdk, setting, moving, alt, mesh, rain, alpha, filter, drift)

    @Test
    fun onlyAMovingCrossFadingBackdropAtFullOpacityRunsOnLayers() {
        assertWithMessage("the aurora, moving, on Android 13+").that(eligible()).isTrue()
        assertWithMessage("API 32 lacks setBuffer / AttachedSurfaceControl").that(eligible(sdk = 32)).isFalse()
        assertWithMessage("first release with every call").that(eligible(sdk = SceneOverlayPlan.MIN_SDK)).isTrue()
        assertWithMessage("the user's switch").that(eligible(setting = false)).isFalse()
        assertWithMessage("a still backdrop is painted once in the window").that(eligible(moving = false)).isFalse()
        assertWithMessage("no second image to cross-fade").that(eligible(alt = false)).isFalse()
        assertWithMessage("mesh-warped layers (grass, branches) need the GPU").that(eligible(mesh = true)).isFalse()
        assertWithMessage("rain is new every frame").that(eligible(rain = true)).isFalse()
        assertWithMessage("a translucent keyboard blends the curtains before the app below")
            .that(eligible(alpha = 0xFE)).isFalse()
        assertWithMessage("theme previews tint the backdrop").that(eligible(filter = true)).isFalse()
        assertWithMessage("drifting particles would need wrapped copies").that(eligible(drift = true)).isFalse()
    }

    /** The rectangle drawDrifting fills: stretched past both sides by the margin, shifted by the drift. */
    @Test
    fun aCurtainCoversExactlyTheRectangleTheWindowWouldPaint() {
        val viewX = 0f
        val viewY = 1400f
        val w = 1008
        val h = 840
        val margin = 31.5f
        val drift = -12.25f
        val p = SceneOverlayPlan.curtain(viewX, viewY, w, h, bufferW = 504, bufferH = 420, marginPx = margin, driftPx = drift, alpha = 0.4f)
        val left = p.x
        val right = p.x + 504 * p.scaleX
        val top = p.y
        val bottom = p.y + 420 * p.scaleY
        assertThat(left).isWithin(1e-3f).of(viewX - margin + drift)
        assertThat(right).isWithin(1e-3f).of(viewX + w + margin + drift)
        assertThat(top).isWithin(1e-3f).of(viewY)
        assertThat(bottom).isWithin(1e-3f).of(viewY + h)
        assertThat(p.alpha).isEqualTo(0.4f)
        assertWithMessage("alpha is clamped for the composer")
            .that(SceneOverlayPlan.curtain(0f, 0f, 10, 10, 5, 5, 0f, 0f, 1.7f).alpha).isEqualTo(1f)
        assertWithMessage("an empty buffer never divides by zero")
            .that(SceneOverlayPlan.curtain(0f, 0f, 10, 10, 0, 0, 0f, 0f, 1f).scaleX).isEqualTo(10f)
    }

    @Test
    fun starLayersSplitTheGroupsAndTwinkleByTheirOwnAlpha() {
        val groups = AuroraMotion.STAR_GROUPS
        for (g in 0 until groups) {
            val on = (0 until SceneOverlayPlan.STAR_LAYERS).filter { SceneOverlayPlan.groupOnLayer(g, it) }
            assertWithMessage("group $g is on exactly one layer").that(on).hasSize(1)
        }
        val alphas = floatArrayOf(0.3f, 0.9f, 0.5f, 0.5f, 0.5f)
        val s0 = SceneOverlayPlan.stars(2f, 3f, 0, alphas)
        val s1 = SceneOverlayPlan.stars(2f, 3f, 1, alphas)
        assertThat(s0).isEqualTo(OverlayPlacement(2f, 3f, 1f, 1f, 0.3f))
        assertThat(s1.alpha).isEqualTo(0.9f)
    }

    /** Curtain A at the bottom, then curtain B, then the stars; all above the anchor's own (empty) buffer. */
    @Test
    fun theLayersStackCurtainsFirstAboveTheAnchor() {
        val count = 2 + SceneOverlayPlan.STAR_LAYERS
        val z = (0 until count).map { SceneOverlayPlan.zOrder(it) }
        assertThat(z).isInStrictOrder()
        assertThat(z.first()).isGreaterThan(0)
    }
}
