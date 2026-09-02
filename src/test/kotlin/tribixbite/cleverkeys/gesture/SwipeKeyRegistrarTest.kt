package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * ARC-112: dense-sampled two-key swipes were silently dropped because the minimum-distance
 * gate in key registration compared `swipe_min_key_distance` against the PER-SAMPLE step
 * length instead of the travel since the last REGISTERED key. On a high-report-rate
 * digitizer a slow smooth swipe emits ~7–13 px steps, so after the start key no sample
 * could ever clear a 15–40 px gate: the whole gesture registered ONE key, was still
 * classified SWIPE, and died downstream with no commit, no log, no error (device evidence:
 * docs/eval/2026-09-02-wave-k-device-verification.md, Wave K2 anomaly 1 — `input swipe
 * 487 1750 911 1750 350`, a 417 px t→o path, logged `Keys touched: 1`).
 *
 * Drives the REAL registration path ([SwipeKeyRegistrar], the extracted core that
 * [ImprovedSwipeGestureRecognizer.registerKeyWithFiltering] delegates to) with:
 *  - a dense-sampled synthetic two-key trace (417 px, 10 px steps) → must register BOTH keys
 *    (red before the fix: 1 key);
 *  - boundary-chatter jitter (±5 px oscillation across a key seam right after a
 *    registration) → must NOT register the neighbour (pins the property MIN_KEY_DISTANCE
 *    exists to provide, under both the old and new distance basis);
 *  - same-key jitter inside one key → no double registration;
 *  - a coarse-sampled control of the same path → unchanged behavior.
 *
 * Pure JVM by design: the recognizer itself is uninstantiable off-device
 * (android.graphics.PointF / android.util.Log are "Stub!" throws) and wall-clock-coupled.
 */
class SwipeKeyRegistrarTest {

    /**
     * Device-observed threshold from the Wave-K2 finding (the shipped field initializer is
     * 40 px; the pref default is 15 px — the dense trace's 10 px steps starve BOTH).
     */
    private val minKeyDistance = 40f

    private fun newRegistrar() = SwipeKeyRegistrar<String>(
        minKeyDistance = { minKeyDistance },
        minDwellTimeMs = { 7L },              // Defaults.SWIPE_MIN_DWELL_TIME
        highVelocityThreshold = { 1000f },    // Defaults.SWIPE_HIGH_VELOCITY_THRESHOLD
    )

    /** Two-key row abstraction: "t" left of the seam at x=250, "o" right of it. */
    private fun keyAt(x: Float): String = if (x < 250f) "t" else "o"

    @Test
    fun denseSampledTwoKeySwipeRegistersBothKeys() {
        val reg = newRegistrar()
        // 417 px horizontal path sampled every 10 px, ~11 ms apart (velocity ~909 px/s,
        // below the high-velocity threshold, so the dwell gate never fires — isolating
        // the distance gate, exactly as on the device where 350 ms and 700 ms durations
        // both reproduced).
        reg.registerStartKey("t", 20f, 50f)
        var x = 30f
        while (x <= 437f) {
            reg.offer(keyAt(x), x, 50f, 11L, 909f)
            x += 10f
        }
        assertThat(reg.touchedKeys).containsExactly("t", "o").inOrder()
    }

    @Test
    fun boundaryChatterDoesNotRegisterNeighbourKey() {
        val reg = newRegistrar()
        // Start key registers just left of the t|o seam; the finger then oscillates ±5 px
        // across the seam without genuinely travelling. The neighbour must NOT register —
        // this is the double-registration jitter MIN_KEY_DISTANCE exists to suppress, and
        // it must hold under the fixed (travel-since-last-registration) basis too: every
        // sample stays within 9 px of the registration point.
        reg.registerStartKey("t", 245f, 50f)
        repeat(20) {
            reg.offer(keyAt(254f), 254f, 50f, 50L, 200f)
            reg.offer(keyAt(244f), 244f, 50f, 50L, 200f)
        }
        assertThat(reg.touchedKeys).containsExactly("t")
    }

    @Test
    fun sameKeyJitterDoesNotDoubleRegister() {
        val reg = newRegistrar()
        reg.registerStartKey("t", 100f, 50f)
        // ±5 px oscillation entirely inside one key: never re-registers.
        repeat(20) {
            reg.offer(keyAt(105f), 105f, 50f, 50L, 200f)
            reg.offer(keyAt(95f), 95f, 50f, 50L, 200f)
        }
        assertThat(reg.touchedKeys).containsExactly("t")
    }

    @Test
    fun coarseSampledControlIsUnchanged() {
        val reg = newRegistrar()
        // Same 417 px path at ~52 px steps (the 8-step sendevent injector that always
        // worked on-device). Registers both keys under the old AND new basis.
        reg.registerStartKey("t", 20f, 50f)
        var x = 72f
        while (x <= 437f) {
            reg.offer(keyAt(x), x, 50f, 55L, 945f)
            x += 52f
        }
        assertThat(reg.touchedKeys).containsExactly("t", "o").inOrder()
    }

    @Test
    fun fastPassThroughStillFiltered() {
        val reg = newRegistrar()
        // Dwell/velocity gate preserved: a sample arriving < minDwellTimeMs at
        // > highVelocityThreshold is passing through and must not register even though
        // it has travelled far from the last registration.
        reg.registerStartKey("t", 20f, 50f)
        assertThat(reg.offer("o", 300f, 50f, 5L, 2000f)).isFalse()
        assertThat(reg.touchedKeys).containsExactly("t")
        // The same key at dwellable speed registers.
        assertThat(reg.offer("o", 310f, 50f, 20L, 500f)).isTrue()
        assertThat(reg.touchedKeys).containsExactly("t", "o").inOrder()
    }

    @Test
    fun recentDuplicateWindowStillFiltered() {
        val reg = newRegistrar()
        // Gate 3 preserved: returning to a key registered within the duplicate window is
        // filtered even after genuine travel.
        reg.registerStartKey("t", 20f, 50f)
        reg.offer("o", 300f, 50f, 20L, 500f)
        assertThat(reg.offer("t", 20f, 50f, 20L, 500f)).isFalse()
        assertThat(reg.touchedKeys).containsExactly("t", "o").inOrder()
    }
}
