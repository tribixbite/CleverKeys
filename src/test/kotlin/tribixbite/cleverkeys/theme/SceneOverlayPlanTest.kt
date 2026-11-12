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

    /** Round 16: one star layer saves ~28 mW, but only steady stars may share one (nothing changes on screen). */
    @Test
    fun steadyStarsShareOneLayerAndTwinklingStarsKeepTwo() {
        assertThat(SceneOverlayPlan.starLayers(0f)).isEqualTo(1)
        assertThat(SceneOverlayPlan.starLayers(0.01f)).isEqualTo(SceneOverlayPlan.STAR_LAYERS)
        assertThat(SceneOverlayPlan.starLayers(SceneMotionTuning.DEFAULT.twinkle)).isEqualTo(SceneOverlayPlan.STAR_LAYERS)
        // With twinkle 0 every group is at full alpha, so the single layer's mean is too.
        val steady = AuroraMotion.frame(12.3, MotionStyle.AURORA, SceneMotionTuning.DEFAULT.copy(twinkle = 0f)).starAlpha
        assertThat(SceneOverlayPlan.stars(0f, 0f, 0, steady, layers = 1).alpha).isEqualTo(1f)
    }

    @Test
    fun aSingleStarLayerHoldsEveryGroupAtTheirMeanTwinkle() {
        for (g in 0 until AuroraMotion.STAR_GROUPS) {
            assertWithMessage("group $g").that(SceneOverlayPlan.groupOnLayer(g, 0, layers = 1)).isTrue()
        }
        val alphas = floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f, 1.0f)
        assertThat(SceneOverlayPlan.stars(0f, 0f, 0, alphas, layers = 1).alpha).isWithin(1e-6f).of(0.6f)
    }

    /**
     * The layers' lifecycle (Fable audit): they exist only while the backdrop moves and may run,
     * stay once up, and are built only when nothing stands in the way.
     */
    @Test
    fun theLayersExistOnlyWhileMovingAndAreBuiltWhenNothingStandsInTheWay() {
        fun sync(ticking: Boolean = true, eligible: Boolean = true, up: Boolean = false, resizing: Boolean = false,
                 visible: Boolean = true, hostShown: Boolean = true) =
            SceneOverlayPlan.sync(ticking, eligible, up, resizing, visible, hostShown)
        assertWithMessage("moving and free to: build").that(sync()).isEqualTo(SceneOverlayPlan.Sync.CREATE)
        assertWithMessage("at rest: down, even when up").that(sync(ticking = false, up = true)).isEqualTo(SceneOverlayPlan.Sync.RELEASE)
        assertWithMessage("no longer eligible: down").that(sync(eligible = false, up = true)).isEqualTo(SceneOverlayPlan.Sync.RELEASE)
        assertWithMessage("up and still wanted: kept").that(sync(up = true, resizing = true)).isEqualTo(SceneOverlayPlan.Sync.KEEP)
        assertWithMessage("mid-resize: later").that(sync(resizing = true)).isEqualTo(SceneOverlayPlan.Sync.WAIT)
        assertWithMessage("drawable hidden: later").that(sync(visible = false)).isEqualTo(SceneOverlayPlan.Sync.WAIT)
        assertWithMessage("view off screen: later").that(sync(hostShown = false)).isEqualTo(SceneOverlayPlan.Sync.WAIT)
        assertWithMessage("at rest and off screen: down (nothing is left waiting)")
            .that(sync(ticking = false, hostShown = false)).isEqualTo(SceneOverlayPlan.Sync.RELEASE)
    }

    /**
     * Fable audit, finding 1: an overlay rebuilt in one pass claims the anchor before its
     * predecessor frees. The predecessor must not remove it; the newest owner does, once.
     */
    @Test
    fun aReplacedOverlayNeverRemovesItsSuccessorsAnchor() {
        val owners = AnchorOwners<Any, Any>()
        val anchor = Any()
        val old = Any()
        val new = Any()
        owners.claim(anchor, old)
        owners.claim(anchor, new)
        assertWithMessage("the replaced overlay frees: the anchor stays").that(owners.release(anchor, old)).isFalse()
        assertWithMessage("still owned by the successor").that(owners.isOwned(anchor)).isTrue()
        assertWithMessage("the successor frees: the anchor may go").that(owners.release(anchor, new)).isTrue()
        assertWithMessage("now a leftover").that(owners.isOwned(anchor)).isFalse()
        assertWithMessage("a second free removes nothing more").that(owners.release(anchor, new)).isFalse()
    }

    /**
     * Landscape (Pixel 8 Pro, 2026-11-12): un-rotated layers were all composited by the GPU. Each
     * buffer is drawn turned by the hint (the display's rotation) and declares the inverse: the
     * declared turn undoes the drawn one (content upright), and the drawn one matches the display
     * (no rotation left for the composer; declaring the hint itself left ROT_180 on the device).
     */
    @Test
    fun aRotatedScreenGetsBuffersPreRotatedForTheDisplay() {
        val clockwise = mapOf(
            SceneOverlayPlan.BUFFER_TRANSFORM_ROTATE_90 to 90,
            SceneOverlayPlan.BUFFER_TRANSFORM_ROTATE_180 to 180,
            SceneOverlayPlan.BUFFER_TRANSFORM_ROTATE_270 to 270,
        )
        for ((hint, turn) in clockwise) {
            val drawn = SceneOverlayPlan.preRotationDegrees(hint)
            val declared = clockwise.getValue(SceneOverlayPlan.bufferTransformFor(hint))
            assertWithMessage("hint $hint: upright").that((drawn + declared) % 360).isEqualTo(0)
            assertWithMessage("hint $hint: lies as the panel scans out").that(drawn).isEqualTo(turn)
        }
        // android.view.SurfaceControl.BUFFER_TRANSFORM_* (HAL transform flags).
        assertThat(listOf(SceneOverlayPlan.BUFFER_TRANSFORM_ROTATE_90, SceneOverlayPlan.BUFFER_TRANSFORM_ROTATE_180,
            SceneOverlayPlan.BUFFER_TRANSFORM_ROTATE_270)).containsExactly(4, 3, 7).inOrder()
        for (other in listOf(0, 1, 2)) {
            assertWithMessage("identity or mirror $other: drawn as is").that(SceneOverlayPlan.preRotationDegrees(other)).isEqualTo(0)
            assertWithMessage("identity or mirror $other: no transform").that(SceneOverlayPlan.bufferTransformFor(other)).isEqualTo(0)
        }
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
