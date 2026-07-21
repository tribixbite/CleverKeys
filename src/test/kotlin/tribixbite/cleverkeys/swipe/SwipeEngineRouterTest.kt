package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.swipe.SwipeEngineRouter.Engine

/**
 * WP9 R-1 step 9 (JVM leg) — routing table for the layout-routed v1 [SwipeEngineRouter].
 *
 * v1 routing is LAYOUT-BASED ONLY: QWERTY-Latin → NEURAL (unchanged, the #9 gate);
 * everything else → GEOMETRIC iff the `geometric_swipe_engine` pref is on, else NONE
 * (today's silent-disable). Uses the string overload (same seam SwipeLayoutSupportTest
 * exercises for the underlying QWERTY-Latin predicate).
 */
class SwipeEngineRouterTest {

    // ── QWERTY-Latin → NEURAL, regardless of the geometric flag ─────────────────────

    @Test
    fun `qwerty us routes neural with flag off`() {
        assertThat(SwipeEngineRouter.route("QWERTY (US)", "latin", false)).isEqualTo(Engine.NEURAL)
    }

    @Test
    fun `qwerty us routes neural even with flag on — geometric never steals qwerty`() {
        assertThat(SwipeEngineRouter.route("QWERTY (US)", "latin", true)).isEqualTo(Engine.NEURAL)
    }

    // ── Non-QWERTY Latin (Dvorak/AZERTY/QWERTZ) → GEOMETRIC when enabled ────────────

    @Test
    fun `dvorak routes geometric when enabled`() {
        assertThat(SwipeEngineRouter.route("Dvorak", "latin", true)).isEqualTo(Engine.GEOMETRIC)
    }

    @Test
    fun `dvorak routes none when disabled — today's behavior preserved`() {
        assertThat(SwipeEngineRouter.route("Dvorak", "latin", false)).isEqualTo(Engine.NONE)
    }

    @Test
    fun `azerty routes geometric when enabled`() {
        assertThat(SwipeEngineRouter.route("AZERTY (FR)", "latin", true)).isEqualTo(Engine.GEOMETRIC)
    }

    // ── Non-Latin scripts → GEOMETRIC when enabled (even QWERTY-named — Greek trap) ──

    @Test
    fun `cyrillic jcuken routes geometric when enabled`() {
        assertThat(SwipeEngineRouter.route("ЙЦУКЕН", "cyrillic", true)).isEqualTo(Engine.GEOMETRIC)
    }

    @Test
    fun `cyrillic jcuken routes none when disabled`() {
        assertThat(SwipeEngineRouter.route("ЙЦУКЕН", "cyrillic", false)).isEqualTo(Engine.NONE)
    }

    @Test
    fun `greek qwerty is NOT neural — script wins over the qwerty name`() {
        // grek_qwerty is QWERTY-named but Greek-script: the neural model can't decode it,
        // so it must route GEOMETRIC (flag on) / NONE (flag off), never NEURAL.
        assertThat(SwipeEngineRouter.route("QWERTY (Ελληνικά)", "greek", true)).isEqualTo(Engine.GEOMETRIC)
        assertThat(SwipeEngineRouter.route("QWERTY (Ελληνικά)", "greek", false)).isEqualTo(Engine.NONE)
    }

    // ── Unknown metadata → conservative: not neural; geometric only if opted in ──────

    @Test
    fun `null name or script never routes neural`() {
        assertThat(SwipeEngineRouter.route(null, "latin", false)).isEqualTo(Engine.NONE)
        assertThat(SwipeEngineRouter.route("Dvorak", null, false)).isEqualTo(Engine.NONE)
        assertThat(SwipeEngineRouter.route(null, null, true)).isEqualTo(Engine.GEOMETRIC)
    }
}
