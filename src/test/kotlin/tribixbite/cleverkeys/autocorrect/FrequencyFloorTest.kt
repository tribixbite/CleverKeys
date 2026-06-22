package tribixbite.cleverkeys.autocorrect

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for [FrequencyFloor], which maps the fixed 100..2000
 * autocorrect "min frequency" slider onto a fraction of the loaded
 * dictionary's max frequency (so the floor means the same thing whether the
 * dictionary loaded on the ~5k..1M binary scale or the 100..10k JSON scale,
 * and can never fully disable autocorrect).
 */
class FrequencyFloorTest {

    private val BIN_MAX = 1_000_000 // V2 binary runtime max (rank 0)
    private val JSON_MAX = 10_000   // JSON fallback runtime max

    @Test
    fun sliderMin_isNoOp_floorZero() {
        // Default slider (100) must not gate anything → floor 0.
        assertThat(FrequencyFloor.effective(100, BIN_MAX)).isEqualTo(0)
        assertThat(FrequencyFloor.effective(100, JSON_MAX)).isEqualTo(0)
    }

    @Test
    fun sliderMax_isMaxStrictness_butBelowMax() {
        // At the max slider, floor = MAX_STRICTNESS * maxFreq, strictly below
        // maxFreq so the most common words always remain eligible.
        assertThat(FrequencyFloor.effective(2000, BIN_MAX)).isEqualTo(600_000)
        assertThat(FrequencyFloor.effective(2000, BIN_MAX)).isLessThan(BIN_MAX)
        assertThat(FrequencyFloor.effective(2000, JSON_MAX)).isEqualTo(6_000)
    }

    @Test
    fun midSlider_isProportional() {
        // 1050 is the midpoint of 100..2000 → t=0.5 → 0.5 * 0.6 * max.
        assertThat(FrequencyFloor.effective(1050, BIN_MAX)).isEqualTo(300_000)
    }

    @Test
    fun isMonotonicNonDecreasing() {
        var prev = -1
        for (v in 100..2000 step 50) {
            val floor = FrequencyFloor.effective(v, BIN_MAX)
            assertThat(floor).isAtLeast(prev)
            prev = floor
        }
    }

    @Test
    fun neverDisablesAutocorrect_mostCommonWordAlwaysPasses() {
        // The single most common word (freq == maxFreq) must clear the floor at
        // every slider position — otherwise the gate could disable autocorrect.
        for (v in 100..2000 step 25) {
            assertThat(BIN_MAX).isGreaterThan(FrequencyFloor.effective(v, BIN_MAX))
        }
    }

    @Test
    fun clampsOutOfDomainValues() {
        // Below/above the slider domain clamps to the endpoints rather than
        // extrapolating to a negative or >MAX_STRICTNESS floor.
        assertThat(FrequencyFloor.effective(0, BIN_MAX)).isEqualTo(0)
        assertThat(FrequencyFloor.effective(5000, BIN_MAX)).isEqualTo(600_000)
    }

    @Test
    fun unloadedDictionary_floorZero() {
        assertThat(FrequencyFloor.effective(2000, 0)).isEqualTo(0)
        assertThat(FrequencyFloor.effective(2000, -1)).isEqualTo(0)
    }
}
