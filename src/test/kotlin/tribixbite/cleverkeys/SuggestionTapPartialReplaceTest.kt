package tribixbite.cleverkeys

import android.content.res.Resources
import android.text.InputType
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd

/**
 * gh #151 — tapping a suggested word left the typed partial in place in some apps
 * ("exa" + tap "example" → "exa example " in the Vanadium URL bar; "hel" + "hello" →
 * "helhello" elsewhere).
 *
 * Mechanism: CleverKeys never composes — typed chars are committed directly — so replacing
 * the partial depends on deletion counts. Two editor classes break that:
 *
 *  1. **Sync-suppressed fields** (URL/email/password/number — `shouldSyncForInputType`
 *     skips them): the tracker's deletion counts stay (0,0) even though `currentWord` IS
 *     tracked from typing, so nothing was deleted AND a leading auto-space corrupted the
 *     value ("exa example ").
 *  2. **Editors without composing/extract support** (the #78 class — commit chars with no
 *     composing spans, `getTextBeforeCursor` is all they offer): the tracker has nothing at
 *     all, so the tap appended ("helhello").
 *
 * The fix (736e4eee) measures the partial by SCANNING the editor text before the cursor
 * whenever the counts are empty — forced for sync-suppressed fields — and never injects a
 * leading space into them. This test pins both behaviors with an InputConnection DOUBLE that
 * models the misbehaving editor: a plain text buffer, `getTextBeforeCursor`/
 * `deleteSurroundingText`/`commitText` only, no composing-region support.
 *
 * RED evidence (2026-09-03): with 736e4eee's two conditions locally reverted
 * (`|| syncSuppressedField` dropped from the fallback gate; the sync-suppressed
 * leading-space suppression removed), theUrlBarPartialIsReplacedNotDuplicated failed with
 * the reporter's exact symptom — buffer "https://exa example " — and
 * anEditorWithNoTrackerStateGetsTheScanFallback failed with "helhello ". Reported verbatim
 * in the wave log; both green at HEAD.
 *
 * Residual (NOT covered here): the reporter's 2026-08-23 follow-up — some of these editors
 * drop the TRAILING space of the commit ("hello " lands as "hello"). Fixed 2026-09-05 by
 * the trailing-space watch (new PredictionContextTracker state armed at commit time,
 * resolved on the stamp−1 cursor signature, repaired on the next alphanumeric keystroke);
 * covered by [SuggestionTrailingSpaceRepairTest].
 */
class SuggestionTapPartialReplaceTest {

    private val objenesis = ObjenesisStd()

    private lateinit var contextTracker: PredictionContextTracker
    private lateinit var coordinator: PredictionCoordinator
    private lateinit var bar: SuggestionBar
    private lateinit var resources: Resources
    private lateinit var config: Config

    /** The editor double's text buffer; cursor is always at its end. */
    private val editorText = StringBuilder()
    private lateinit var ic: InputConnection

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        mockkObject(Config.Companion)
        config = mockk(relaxed = true)
        // Both auto-space settings ON: the leading space is exactly what corrupted URL bars,
        // so the suppression must be field-driven, not setting-driven.
        config.auto_space_before_suggestion = true
        config.auto_space_after_suggestion = true
        every { Config.globalConfig() } returns config

        coordinator = mockk(relaxed = true)
        bar = mockk(relaxed = true)
        every { bar.getMetaForSuggestion(any()) } returns null
        resources = mockk(relaxed = true)

        contextTracker = mockk(relaxed = true)
        every { contextTracker.getLastCommitSource() } returns PredictionSource.UNKNOWN
        every { contextTracker.getLastAutoInsertedWord() } returns null
        every { contextTracker.wasLastInputSwipe() } returns false
        // The REAL input-type classification — the field kind driving the #151 paths.
        every { contextTracker.shouldSyncForInputType(any()) } answers { callOriginal() }
        // The misbehaving-editor premise: sync never yields deletion counts.
        every { contextTracker.getCharsToDeleteForPrediction() } returns Pair(0, 0)

        editorText.clear()
        ic = mockk(relaxed = true)
        every { ic.getTextBeforeCursor(any(), any()) } answers {
            editorText.takeLast(firstArg<Int>()).toString()
        }
        every { ic.getTextAfterCursor(any(), any()) } returns ""
        every { ic.getCursorCapsMode(any()) } returns 0
        every { ic.deleteSurroundingText(any(), any()) } answers {
            val before = firstArg<Int>()
            editorText.setLength((editorText.length - before).coerceAtLeast(0))
            true
        }
        every { ic.commitText(any(), any()) } answers {
            editorText.append(firstArg<CharSequence>())
            true
        }
        // The double does NOT support composing regions — the editor class #151 hit.
        every { ic.setComposingRegion(any(), any()) } returns false
        every { ic.setComposingText(any(), any()) } returns false
    }

    @After
    fun teardown() = unmockkAll()

    // ------------------------------------------------------------------ fixtures

    private fun handler(): SuggestionHandler {
        val handler = objenesis.newInstance(SuggestionHandler::class.java)
        handler.setField("contextTracker", contextTracker)
        handler.setField("predictionCoordinator", coordinator)
        handler.setField("suggestionBar", bar)
        handler.setField("config", config)
        handler.setField("contractionManager", mockk<ContractionManager>(relaxed = true))
        handler.setField("keyeventhandler", mockk<KeyEventHandler>(relaxed = true))
        handler.setField("predictionTasks", mockk<PredictionTaskRunner>(relaxed = true))
        return handler
    }

    private fun editorInfo(type: Int): EditorInfo =
        objenesis.newInstance(EditorInfo::class.java).apply {
            packageName = "com.example.browser"
            inputType = type
        }

    private val uriField =
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    private val plainField =
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL

    // ------------------------------------------- the reporter's case: URL bar

    @Test
    fun theUrlBarPartialIsReplacedNotDuplicated() {
        // Vanadium URL bar: URI field (sync-suppressed), the user typed "exa" (tracked),
        // tracker deletion counts are (0,0) because sync never ran.
        editorText.append("https://exa")
        every { contextTracker.getCurrentWordLength() } returns 3
        every { contextTracker.getCurrentWord() } returns "exa"

        handler().onSuggestionSelected(
            "example", ic, editorInfo(uriField), resources, isManualSelection = true
        )

        assertWithMessage(
            "gh #151: the typed partial must be scanned out of the editor and deleted, and " +
                "no leading auto-space may corrupt the URL"
        ).that(editorText.toString()).isEqualTo("https://example ")
    }

    @Test
    fun theUrlBarNeverGetsALeadingAutoSpace() {
        editorText.append("https://exa")
        every { contextTracker.getCurrentWordLength() } returns 3
        every { contextTracker.getCurrentWord() } returns "exa"

        handler().onSuggestionSelected(
            "example", ic, editorInfo(uriField), resources, isManualSelection = true
        )

        verify(exactly = 0) { ic.commitText(match { it.startsWith(" ") }, any()) }
    }

    // ---------------------------------- the #78 class: no tracker state at all

    @Test
    fun anEditorWithNoTrackerStateGetsTheScanFallback() {
        // Plain text field, but the editor commits chars without composing spans and the
        // tracker never accumulated a word (Fennec/Keep class): counts (0,0), length 0.
        editorText.append("hel")
        every { contextTracker.getCurrentWordLength() } returns 0
        every { contextTracker.getCurrentWord() } returns ""

        handler().onSuggestionSelected(
            "hello", ic, editorInfo(plainField), resources, isManualSelection = true
        )

        assertWithMessage("gh #151/#78: 'hel' + tap 'hello' must not yield 'helhello'")
            .that(editorText.toString()).isEqualTo("hello ")
    }

    @Test
    fun theReplacementPathNeverTouchesComposingApis() {
        // The contract that makes the fix work in these editors at all: everything is done
        // with getTextBeforeCursor/deleteSurroundingText/commitText.
        editorText.append("https://exa")
        every { contextTracker.getCurrentWordLength() } returns 3
        every { contextTracker.getCurrentWord() } returns "exa"

        handler().onSuggestionSelected(
            "example", ic, editorInfo(uriField), resources, isManualSelection = true
        )

        verify(exactly = 0) { ic.setComposingRegion(any(), any()) }
        verify(exactly = 0) { ic.setComposingText(any(), any()) }
        verify(exactly = 0) { ic.finishComposingText() }
    }

    // ------------------------------------------------- boundary of the scan

    @Test
    fun theScanStopsAtNonWordCharactersSoOnlyThePartialIsDeleted() {
        editorText.append("go to exa")
        every { contextTracker.getCurrentWordLength() } returns 0
        every { contextTracker.getCurrentWord() } returns ""

        handler().onSuggestionSelected(
            "example", ic, editorInfo(plainField), resources, isManualSelection = true
        )

        assertThat(editorText.toString()).isEqualTo("go to example ")
    }

    // ------------------------------------------------------------------ reflection

    private fun Any.setField(name: String, value: Any?) {
        val field = javaClass.declaredFields.firstOrNull { it.name == name }
        assertWithMessage(
            "field '$name' not found on ${javaClass.simpleName} — it was renamed or removed; " +
                "declared: ${javaClass.declaredFields.map { it.name }}"
        ).that(field).isNotNull()
        field!!.isAccessible = true
        field.set(this, value)
    }
}
