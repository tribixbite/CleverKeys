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
        drift: Boolean = false, frameMs: Long = 100L,
    ) = SceneOverlayPlan.eligible(sdk, setting, moving, alt, mesh, rain, alpha, filter, drift, frameMs)

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
        // Round 17 / Galaxy Tab S11: below 10 fps the window path costs about the same, and the
        // layers can push a phone with fewer planes onto the GPU at its top refresh rate.
        assertWithMessage("10 fps: the layers halve the cost").that(eligible(frameMs = SceneOverlayPlan.MIN_FRAME_MS)).isTrue()
        assertWithMessage("15 fps").that(eligible(frameMs = 67L)).isTrue()
        assertWithMessage("8 fps: the window path").that(eligible(frameMs = 125L)).isFalse()
        assertWithMessage("the aurora's own 5 fps: the window path")
            .that(eligible(frameMs = AuroraMotion.AURORA_FRAME_MS)).isFalse()
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

    /**
     * Review 2026-11-14 M18: building the layers copies view-sized bitmaps on the main thread, and
     * running out of memory there throws an Error. It must refuse the layers (the window paints
     * the backdrop), not escape and kill the keyboard; a missing surface still only waits. Covers
     * the policy BackdropDrawable.createOverlay runs the build through, not the Android build itself.
     */
    @Test
    fun runningOutOfMemoryBuildingTheLayersRefusesThemInsteadOfCrashing() {
        class NotReady : Exception()
        val oom = OutOfMemoryError("Failed to allocate a 8294400 byte allocation")
        val refused = SceneOverlayPlan.attemptBuild({ it is NotReady }) { throw oom }
        assertThat(refused).isInstanceOf(OverlayBuild.Refused::class.java)
        assertThat((refused as OverlayBuild.Refused).cause).isSameInstanceAs(oom)
        assertThat(SceneOverlayPlan.attemptBuild({ it is NotReady }) { throw NotReady() }).isSameInstanceAs(OverlayBuild.Waiting)
        assertThat(SceneOverlayPlan.attemptBuild({ it is NotReady }) { throw IllegalStateException("driver") })
            .isInstanceOf(OverlayBuild.Refused::class.java)
        val built = SceneOverlayPlan.attemptBuild({ it is NotReady }) { "layers" }
        assertThat((built as OverlayBuild.Built).overlay).isEqualTo("layers")
    }

    /**
     * Review 2026-11-14 M18: the layers are built one at a time; when one fails, even with an
     * Error, every layer already built is freed (its buffer and SurfaceControl), and the failure
     * still reaches the caller. Fakes stand in for the layers: the Android calls are not run.
     */
    @Test
    fun aFailedLayerFreesTheLayersBuiltBeforeIt() {
        val freed = mutableListOf<Int>()
        val made = mutableListOf<Int>()
        val thrown = runCatching {
            SceneOverlayPlan.buildEach(4, { i ->
                if (i == 2) throw OutOfMemoryError("hardware copy of layer 2")
                made += i
                i
            }, { freed += it })
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(OutOfMemoryError::class.java)
        assertWithMessage("layers 0 and 1 were built").that(made).containsExactly(0, 1).inOrder()
        assertWithMessage("and freed").that(freed).containsExactly(0, 1)
        // A failing free does not keep the others.
        val freed2 = mutableListOf<Int>()
        runCatching {
            SceneOverlayPlan.buildEach(3, { i -> if (i == 2) error("refused") else i }, { if (it == 0) error("bad free") else freed2 += it })
        }
        assertThat(freed2).containsExactly(1)
        assertThat(SceneOverlayPlan.buildEach(3, { it * 10 }, { error("not freed when all are built") })).containsExactly(0, 10, 20).inOrder()
    }

    /**
     * Review 2026-11-14 (low): a build that fails while a predecessor's release is still queued
     * on the anchor must leave the anchor to that predecessor; a build that had claimed it drops
     * its claim, and an anchor nobody owns may go.
     */
    @Test
    fun aFailedBuildRemovesTheAnchorOnlyWhenNobodyOwnsIt() {
        val owners = AnchorOwners<Any, Any>()
        val anchor = Any()
        val predecessor = Any()
        owners.claim(anchor, predecessor)
        assertWithMessage("failed before claiming, the predecessor still owns it").that(owners.abandon(anchor, null)).isFalse()
        assertThat(owners.isOwned(anchor)).isTrue()
        assertWithMessage("the predecessor frees: it removes the anchor").that(owners.release(anchor, predecessor)).isTrue()

        val failed = Any()
        owners.claim(anchor, failed)
        assertWithMessage("failed after claiming: claim dropped, nobody else owns it").that(owners.abandon(anchor, failed)).isTrue()
        assertThat(owners.isOwned(anchor)).isFalse()
        assertWithMessage("an anchor nobody owned").that(owners.abandon(Any(), null)).isTrue()
    }

    /**
     * Review 2026-11-14 M20: a release queued on the window's next frame frees the layers when
     * that frame's transaction commits; when no frame comes (the window hid first) a fallback
     * frees them after [QueuedFree.FALLBACK_MS], and takes them off directly. Whichever comes
     * first frees once. A fake main-thread queue stands in for the Handler; the transaction and
     * its commit callback themselves are not modelled.
     */
    @Test
    fun aQueuedReleaseIsFreedEvenWhenTheWindowNeverDrawsAgain() {
        class FakeQueue {
            val due = mutableListOf<Pair<Runnable, Long>>()
            fun post(r: Runnable, ms: Long) { due += r to ms }
            fun cancel(r: Runnable) { due.removeAll { it.first === r } }
            fun runAll() { val now = due.toList(); due.clear(); now.forEach { it.first.run() } }
        }
        // No commit ever comes.
        val q = FakeQueue()
        val frees = mutableListOf<Boolean>()
        val stranded = QueuedFree(q::post, q::cancel) { frees += it }
        stranded.queued()
        assertWithMessage("a fallback is armed").that(q.due.map { it.second }).containsExactly(QueuedFree.FALLBACK_MS)
        q.runAll()
        assertWithMessage("freed by the fallback, taken off directly").that(frees).containsExactly(true)
        stranded.committed()
        assertWithMessage("a late commit frees nothing more").that(frees).hasSize(1)

        // The commit comes first: freed once, the fallback cancelled.
        val q2 = FakeQueue()
        val frees2 = mutableListOf<Boolean>()
        val normal = QueuedFree(q2::post, q2::cancel) { frees2 += it }
        normal.queued()
        normal.committed()
        assertThat(frees2).containsExactly(false)
        assertWithMessage("the fallback was cancelled").that(q2.due).isEmpty()
        assertThat(QueuedFree.FALLBACK_MS).isAtMost(500L)
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
