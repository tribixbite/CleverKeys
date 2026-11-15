package tribixbite.cleverkeys.theme

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.SourceScan

/**
 * Follow-up review 2026-11-15: the scene fixes are pinned by pure tests of their helpers
 * ([SceneOverlayPlan], [AnchorOwners], [PaintingJob]); nothing checked that production goes
 * through them. These scans fail on a plain revert of the wiring.
 */
class SceneWiringDriftTest {

    private val overlay = SourceScan.read("src/main/kotlin/tribixbite/cleverkeys/theme/SceneOverlay.kt")
    private val backdrop = SourceScan.read("src/main/kotlin/tribixbite/cleverkeys/theme/BackdropDrawable.kt")

    @Test
    fun aFailedOverlayBuildCleansUpOnAnyThrowable() {
        val create = SourceScan.functionBody(overlay, "fun create(")
        // M18: an OutOfMemoryError copying a view-sized bitmap skipped catch (Exception).
        assertWithMessage("SceneOverlay.create must catch Throwable").that(create).contains("catch (e: Throwable)")
        assertWithMessage("its layers are built one at a time").that(create).contains("SceneOverlayPlan.buildEach(")
        assertWithMessage("and the anchor goes only when nobody owns it")
            .that(create).contains("if (owners.abandon(anchor, overlay)) removeAnchor(anchor)")
    }

    @Test
    fun theAnchorIsClaimedOnlyAfterTheOverlayIsSetUp() {
        val create = SourceScan.functionBody(overlay, "fun create(")
        val claim = create.indexOf("owners.claim(anchor, built)")
        assertWithMessage("create claims the anchor for the built overlay").that(claim).isAtLeast(0)
        assertWithMessage("after the first placement and invalidate, just before returning")
            .that(claim).isGreaterThan(create.indexOf("host.invalidate()"))
        assertWithMessage("never in the constructor (a failed build would displace a predecessor)")
            .that(overlay.substringAfter("    init {").substringBefore("\n    }")).doesNotContain("owners.claim")
    }

    @Test
    fun backgroundRendersGoThroughPaintingJob() {
        val request = SourceScan.functionBody(backdrop, "private fun requestPainting(")
        // M19: stale renders skipped, a heap failure retried at half scale.
        assertWithMessage("requestPainting renders through PaintingJob").that(request).contains("PaintingJob.run(")
        assertWithMessage("with a staleness check").that(request).contains("isCurrent = { gen == generation }")
        assertWithMessage("and the half-scale retry").that(request).contains("smaller = ::halfScale")
    }
}
