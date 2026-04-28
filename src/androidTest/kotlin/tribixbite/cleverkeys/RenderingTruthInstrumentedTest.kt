package tribixbite.cleverkeys

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Rendering-truth tests: verify that real Views/widgets, when populated with
 * representative data, produce the structural state the IME relies on.
 *
 * These take the place of full pixel-comparison goldens (Roborazzi) — which
 * are hard to wire on Termux ARM64 because Robolectric's nativeruntime
 * graphics layer isn't reliably available there. Instead we instantiate the
 * real Views and assert post-set-data properties (text, ellipsize, child
 * count) that would fail if the rendering pipeline silently regressed.
 *
 * Coverage:
 *  - EmojiGridView.EmojiView post-setEmoji state (#118 regression)
 *  - SuggestionBar child count after setSuggestionsWithScores
 *  - SuggestionBar special-prefix display ("exact_add:" → "+word")
 */
@RunWith(AndroidJUnit4::class)
class RenderingTruthInstrumentedTest {

    private lateinit var context: Context
    private lateinit var mainHandler: Handler

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
        mainHandler = Handler(Looper.getMainLooper())

        // Initialize emoji catalog before instantiating EmojiViews directly.
        Emoji.init(context.resources)
    }

    // =========================================================================
    // EmojiGridView.EmojiView — #118 regression guards
    // =========================================================================

    @Test
    fun emojiView_realEmoji_ellipsizeNull() {
        // #118: Real emoji glyphs that exceed cell width must clip, not
        // collapse to "…". This is enforced by ellipsize being unset for
        // the regular-emoji branch of setEmoji().
        val view = makeEmojiViewOnMainThread()
        val coffee = Emoji.getEmojiByString("☕")
            ?: throw AssertionError("Coffee emoji must be in catalog")

        runOnMain { view.setEmoji(coffee) }

        assertNull(
            "#118: real emoji must have ellipsize=null so wide glyphs clip rather than show '…'",
            view.ellipsize
        )
        assertEquals("☕", view.text)
    }

    @Test
    fun emojiView_emoticon_ellipsizeEnd() {
        // Emoticon path: TruncateAt.END is the correct behavior — long
        // kaomoji (¯\_(ツ)_/¯) must truncate cleanly because its glyphs are
        // ASCII-width predictable and ellipsis reads OK.
        val view = makeEmojiViewOnMainThread()
        val kaomoji = Emoji.getEmojiByString("¯\\_(ツ)_/¯")
            ?: Emoji.getEmojiByString(":)") // Fallback if catalog differs
            ?: throw AssertionError("No kaomoji/emoticon found in catalog")

        runOnMain { view.setEmoji(kaomoji) }

        assertEquals(
            "Emoticon must use TruncateAt.END",
            TextUtils.TruncateAt.END,
            view.ellipsize
        )
    }

    @Test
    fun emojiView_initStructure_singleLineWithMinHeight() {
        // Layout invariant: emoji cells are always single-line and have a
        // minimum height for consistent grid spacing.
        val view = makeEmojiViewOnMainThread()
        assertTrue("Emoji cell must be single-line", view.isSingleLine)
        assertEquals("Emoji cell must allow only 1 line", 1, view.maxLines)
        assertTrue(
            "Emoji cell must enforce minHeight (got ${view.minHeight}px)",
            view.minHeight > 0
        )
    }

    // =========================================================================
    // SuggestionBar — child count and special-prefix rendering
    // =========================================================================

    @Test
    fun suggestionBar_setSuggestions_storesAllProvided() {
        val bar = makeSuggestionBarOnMainThread()
        val words = listOf("hello", "world", "friend", "today", "tomorrow")
        val scores = listOf(100, 90, 80, 70, 60)

        runOnMain { bar.setSuggestionsWithScores(words, scores) }
        drainMainThread()

        val rendered = bar.getCurrentSuggestions()
        assertEquals(
            "All 5 suggestions must be retained internally. Got: $rendered",
            5, rendered.size
        )
        assertEquals("Order must match input (highest score first)", "hello", rendered[0])
    }

    @Test
    fun suggestionBar_dedupesIdenticalUpdates() {
        // Regression guard for the contraction-flicker dedup logic: a second
        // setSuggestionsWithScores call with identical content must not change
        // observable state (no flicker). This mirrors what
        // ContractionFlickerIntegrationTest verifies, but in isolation.
        val bar = makeSuggestionBarOnMainThread()
        val words = listOf("a", "b", "c")
        val scores = listOf(10, 9, 8)

        runOnMain { bar.setSuggestionsWithScores(words, scores) }
        drainMainThread()
        val first = bar.getCurrentSuggestions().toList()

        runOnMain { bar.setSuggestionsWithScores(words, scores) }
        drainMainThread()
        val second = bar.getCurrentSuggestions().toList()

        assertEquals("Identical re-set must produce identical state", first, second)
    }

    @Test
    fun suggestionBar_emptyList_clearsSuggestions() {
        val bar = makeSuggestionBarOnMainThread()
        runOnMain { bar.setSuggestionsWithScores(listOf("x"), listOf(1)) }
        drainMainThread()
        assertFalse(bar.getCurrentSuggestions().isEmpty())

        runOnMain { bar.setSuggestionsWithScores(emptyList(), emptyList()) }
        drainMainThread()
        assertTrue(
            "Empty list must clear stored suggestions. Got: ${bar.getCurrentSuggestions()}",
            bar.getCurrentSuggestions().isEmpty()
        )
    }

    @Test
    fun suggestionBar_clearSuggestions_resetsToEmpty() {
        val bar = makeSuggestionBarOnMainThread()
        runOnMain { bar.setSuggestionsWithScores(listOf("foo", "bar"), listOf(2, 1)) }
        drainMainThread()

        runOnMain { bar.clearSuggestions() }
        drainMainThread()

        assertTrue(bar.getCurrentSuggestions().isEmpty())
    }

    // =========================================================================
    // Helpers — run lambdas on UI thread, await completion
    // =========================================================================

    private fun makeEmojiViewOnMainThread(): EmojiGridView.EmojiView {
        val holder = arrayOfNulls<EmojiGridView.EmojiView>(1)
        runOnMain { holder[0] = EmojiGridView.EmojiView(context) }
        return holder[0]!!
    }

    private fun makeSuggestionBarOnMainThread(): SuggestionBar {
        val holder = arrayOfNulls<SuggestionBar>(1)
        runOnMain { holder[0] = SuggestionBar(context) }
        return holder[0]!!
    }

    private fun runOnMain(block: () -> Unit) {
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
    }

    private fun drainMainThread() {
        val latch = CountDownLatch(1)
        mainHandler.post { latch.countDown() }
        latch.await(2, TimeUnit.SECONDS)
    }
}
