package tribixbite.cleverkeys.customization

import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Review 2026-11-14 (low): Short Swipe Reset All's Undo did not survive a rotation — the removed
 * mappings lived only in the coroutine showing the snackbar. They are now kept as text in saved
 * state ([ShortSwipeResetUndo]); this pins that the text gives back exactly the removed mappings
 * and that an oversized set is never put into the Bundle.
 */
class ShortSwipeResetUndoCodecTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() = unmockkAll()

    private val removed = listOf(
        ShortSwipeMapping("a", SwipeDirection.N, "@", ActionType.TEXT, "@"),
        ShortSwipeMapping("a", SwipeDirection.SE, "→", ActionType.TEXT, "->"),
        ShortSwipeMapping("q", SwipeDirection.SW, "", ActionType.TEXT, "quick brown fox", useKeyFont = true),
    )

    @Test
    fun theRemovedMappingsComeBackExactly() {
        val restored = ShortSwipeResetUndo.decode(ShortSwipeResetUndo.encode(removed))
        assertThat(restored).containsExactlyElementsIn(removed)
    }

    @Test
    fun unreadableTextGivesNothingToRestore() {
        assertThat(ShortSwipeResetUndo.decode("{not json")).isEmpty()
        assertThat(ShortSwipeResetUndo.decode("")).isEmpty()
    }

    @Test
    fun anOversizedSetIsNotSaved() {
        val small = ShortSwipeResetUndo.encode(removed)
        assertThat(ShortSwipeResetUndo.saveable(small)).isEqualTo(small)
        val big = "x".repeat(ShortSwipeResetUndo.MAX_SAVED_CHARS + 1)
        assertThat(ShortSwipeResetUndo.saveable(big)).isNull()
        assertThat(ShortSwipeResetUndo.saveable(null)).isNull()
    }
}
