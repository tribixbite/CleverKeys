package tribixbite.cleverkeys

import android.content.res.Resources
import android.view.inputmethod.InputConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.pinyin.PinyinComposingHook

/**
 * gh #177: the swipe pipeline's pinyin seam. Pins that a consuming
 * [PinyinComposingHook] short-circuits `handleSwipePredictionResults` BEFORE the
 * password and empty-slate guards (an empty decode must not clear the composing
 * candidates) and before any English step (no bar render, no auto-commit). A
 * non-consuming hook leaves the legacy flow untouched.
 *
 * The handler is built with Objenesis (the repo pattern for exercising one method on
 * a 2,600-line class) — `handleSwipePredictionResults`'s hook branch reads only the
 * bar and the hook itself, so no constructor side effects are involved.
 */
class SuggestionPinyinHookTest {

    private val objenesis = ObjenesisStd()

    private lateinit var handler: SuggestionHandler
    private lateinit var bar: SuggestionBar
    private lateinit var coordinator: InputCoordinator
    private lateinit var ic: InputConnection
    private lateinit var resources: Resources

    @Before
    fun setup() {
        handler = objenesis.newInstance(SuggestionHandler::class.java)
        bar = mockk(relaxed = true)
        handler.setSuggestionBar(bar)
        coordinator = mockk(relaxed = true)
        ic = mockk(relaxed = true)
        resources = mockk(relaxed = true)
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    private fun hookThatConsumes(consumes: Boolean): PinyinComposingHook =
        mockk<PinyinComposingHook>().also {
            every { it.onSwipeDecoded(any(), any()) } returns consumes
        }

    private fun swipe(predictions: List<String>?, scores: List<Int>?) {
        handler.handleSwipePredictionResults(
            predictions, scores, ic, null, resources,
            shiftActive = false, shiftLocked = false, inputCoordinator = coordinator,
        )
    }

    @Test
    fun aConsumingHookSuppressesTheEnglishSwipePipeline() {
        val hook = hookThatConsumes(true)
        handler.setPinyinHook(hook)

        swipe(listOf("nihao"), listOf(900))

        verify { hook.onSwipeDecoded(listOf("nihao"), listOf(900)) }
        verify(exactly = 0) { bar.setSuggestionsWithScores(any(), any(), any()) }
        verify(exactly = 0) { ic.commitText(any(), any()) }
    }

    @Test
    fun anEmptyDecodeStillReachesTheHookAndKeepsTheCandidates() {
        val hook = hookThatConsumes(true)
        handler.setPinyinHook(hook)

        swipe(null, null)

        verify { hook.onSwipeDecoded(emptyList(), emptyList()) }
        verify(exactly = 0) { bar.clearSuggestions() }
    }

    @Test
    fun aNonConsumingHookLeavesTheLegacyFlowAlone() {
        val hook = hookThatConsumes(false)
        handler.setPinyinHook(hook)

        swipe(null, null)

        verify { hook.onSwipeDecoded(emptyList(), emptyList()) }
        verify { bar.clearSuggestions() }
    }

    @Test
    fun withoutAHookTheSwipePipelineIsUnchanged() {
        swipe(null, null)
        verify { bar.clearSuggestions() }
    }
}
