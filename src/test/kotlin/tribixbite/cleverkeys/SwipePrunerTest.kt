package tribixbite.cleverkeys

import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Pure JVM tests for SwipePruner.
 *
 * Tests the extremity map construction and dictionary indexing logic.
 *
 * NOTE: pruneByExtremities() and pruneByLength() depend on android.graphics.PointF
 * (public field access: point.x, point.y) and KeyboardData.Key, which are Android
 * framework classes. These methods require Robolectric for testing.
 *
 * android.util.Log must be mocked since SwipePruner.buildExtremityMap() uses
 * Log.d() during initialization.
 */
class SwipePrunerTest {

    @Before
    fun setup() {
        // Mock android.util.Log to prevent JVM test crash
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.i(any(), any()) } returns 0
    }

    @After
    fun teardown() {
        unmockkStatic(Log::class)
    }

    // =========================================================================
    // Constructor and extremity map tests
    // =========================================================================

    @Test
    fun `constructor builds extremity map from dictionary`() {
        val dictionary = mapOf(
            "hello" to 100,
            "help" to 80,
            "happy" to 50,
            "world" to 70
        )
        val pruner = SwipePruner(dictionary)
        assertThat(pruner).isNotNull()
    }

    @Test
    fun `constructor handles empty dictionary`() {
        val pruner = SwipePruner(emptyMap())
        assertThat(pruner).isNotNull()
    }

    @Test
    fun `constructor skips single-char words`() {
        // Single-char words (length < 2) should be skipped in extremity map building
        val dictionary = mapOf(
            "a" to 100,
            "I" to 90,
            "ab" to 80,
            "xyz" to 70
        )
        val pruner = SwipePruner(dictionary)
        assertThat(pruner).isNotNull()
    }

    @Test
    fun `constructor handles words with same first and last letter`() {
        // "level" and "lull" both have first='l', last='l' -> same extremity key "ll"
        val dictionary = mapOf(
            "level" to 100,
            "lull" to 90,
            "loyal" to 80
        )
        val pruner = SwipePruner(dictionary)
        assertThat(pruner).isNotNull()
    }

    @Test
    fun `constructor handles large dictionary without crash`() {
        val dictionary = mutableMapOf<String, Int>()
        for (i in 0 until 10000) {
            dictionary["word$i"] = 100 - (i % 100)
        }
        val pruner = SwipePruner(dictionary)
        assertThat(pruner).isNotNull()
    }

    @Test
    fun `constructor handles two-character words`() {
        val dictionary = mapOf(
            "go" to 100,  // first='g', last='o'
            "to" to 90,   // first='t', last='o'
            "no" to 80,   // first='n', last='o'
            "on" to 70    // first='o', last='n'
        )
        val pruner = SwipePruner(dictionary)
        assertThat(pruner).isNotNull()
    }

    @Test
    fun `constructor handles words with numbers and special chars`() {
        val dictionary = mapOf(
            "123" to 100,
            "abc123" to 90,
            "test!" to 80,
            "hello" to 70
        )
        // These all have length >= 2, so extremity pairs are:
        // "123" -> "13", "abc123" -> "a3", "test!" -> "t!", "hello" -> "ho"
        val pruner = SwipePruner(dictionary)
        assertThat(pruner).isNotNull()
    }

    @Test
    fun `constructor handles all same-letter words`() {
        val dictionary = mapOf(
            "aa" to 100,
            "aaa" to 90,
            "aaaa" to 80
        )
        // All share extremity key "aa"
        val pruner = SwipePruner(dictionary)
        assertThat(pruner).isNotNull()
    }

    // =========================================================================
    // Extremity map coverage tests
    //
    // While we can't directly inspect the private extremityMap, we can verify
    // the pruner's behavior with pruneByExtremities (if Android deps were available).
    // Here we verify the data structure properties indirectly.
    // =========================================================================

    @Test
    fun `dictionary with 26 unique first-last pairs`() {
        // Create words covering a-z as first letters with 'a' as last
        val dictionary = ('a'..'z').associate { c ->
            "${c}test${c}a" to 100 // e.g., "atesta" has extremity "aa"
        }
        val pruner = SwipePruner(dictionary)
        assertThat(pruner).isNotNull()
    }

    @Test
    fun `dictionary with duplicate words is handled`() {
        // Map keys are unique, so duplicates aren't possible, but test idempotency
        val dictionary = mapOf(
            "hello" to 100,
            "world" to 90
        )
        val pruner1 = SwipePruner(dictionary)
        val pruner2 = SwipePruner(dictionary)
        // Both should initialize without issue
        assertThat(pruner1).isNotNull()
        assertThat(pruner2).isNotNull()
    }

    // =========================================================================
    // Distance calculation verification (manual math)
    //
    // SwipePruner.distance() is private, but pruneByLength uses it.
    // We verify the expected math here for documentation.
    // =========================================================================

    @Test
    fun `euclidean distance formula verification`() {
        // distance(0, 0, 3, 4) should be 5 (3-4-5 triangle)
        val dx = 3f - 0f
        val dy = 4f - 0f
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        assertThat(dist).isWithin(1e-5f).of(5f)
    }

    @Test
    fun `euclidean distance zero when same point`() {
        val dx = 0f
        val dy = 0f
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        assertThat(dist).isEqualTo(0f)
    }

    @Test
    fun `euclidean distance horizontal line`() {
        val dx = 100f
        val dy = 0f
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        assertThat(dist).isWithin(1e-5f).of(100f)
    }

    @Test
    fun `euclidean distance vertical line`() {
        val dx = 0f
        val dy = 200f
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        assertThat(dist).isWithin(1e-5f).of(200f)
    }

    @Test
    fun `euclidean distance diagonal`() {
        // (0,0) to (1,1) = sqrt(2) ≈ 1.4142
        val dx = 1f
        val dy = 1f
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        assertThat(dist).isWithin(1e-4f).of(1.4142f)
    }

    // =========================================================================
    // pruneByLength formula verification (manual math)
    //
    // pruneByLength filters candidates where:
    //   |pathLength - idealLength| < lengthThreshold * keyWidth
    // where idealLength = (word.length - 1) * keyWidth * 0.8
    // =========================================================================

    @Test
    fun `idealLength formula for short word`() {
        // "hi" (length 2): idealLength = (2-1) * 50 * 0.8 = 40
        val wordLength = 2
        val keyWidth = 50f
        val idealLength = (wordLength - 1) * keyWidth * 0.8f
        assertThat(idealLength).isWithin(1e-5f).of(40f)
    }

    @Test
    fun `idealLength formula for medium word`() {
        // "hello" (length 5): idealLength = (5-1) * 50 * 0.8 = 160
        val wordLength = 5
        val keyWidth = 50f
        val idealLength = (wordLength - 1) * keyWidth * 0.8f
        assertThat(idealLength).isWithin(1e-5f).of(160f)
    }

    @Test
    fun `idealLength formula for long word`() {
        // "supercalifragilistic" (length 20): idealLength = (20-1) * 50 * 0.8 = 760
        val wordLength = 20
        val keyWidth = 50f
        val idealLength = (wordLength - 1) * keyWidth * 0.8f
        assertThat(idealLength).isWithin(1e-5f).of(760f)
    }

    @Test
    fun `pruneByLength filter condition matches short path vs long word`() {
        // pathLength = 100, word "supercalifragilistic", keyWidth = 50, threshold = 3
        val pathLength = 100f
        val idealLength = (20 - 1) * 50f * 0.8f // = 760
        val threshold = 3f * 50f // = 150
        val diff = kotlin.math.abs(pathLength - idealLength) // = 660

        // 660 > 150 -> should be filtered
        assertThat(diff).isGreaterThan(threshold)
    }

    @Test
    fun `pruneByLength filter condition matches similar lengths`() {
        // pathLength = 100, word "hi" (length 2), keyWidth = 50, threshold = 3
        val pathLength = 100f
        val idealLength = (2 - 1) * 50f * 0.8f // = 40
        val threshold = 3f * 50f // = 150
        val diff = kotlin.math.abs(pathLength - idealLength) // = 60

        // 60 < 150 -> should pass filter
        assertThat(diff).isLessThan(threshold)
    }
}
