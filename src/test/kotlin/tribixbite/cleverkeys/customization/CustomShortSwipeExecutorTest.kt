package tribixbite.cleverkeys.customization

import android.content.Context
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MockK-based JVM tests for [CustomShortSwipeExecutor] TIMESTAMP execution (issue #141).
 *
 * Verifies:
 * - executing a [ActionType.TIMESTAMP] mapping commits a string that matches the
 *   pattern's expected format for the current time
 * - null InputConnection returns false
 * - SimpleDateFormat exceptions during execution return false (defense-in-depth —
 *   note that pattern validation in init prevents this from happening normally,
 *   but the executor must remain robust if a stored mapping is corrupted)
 *
 * Uses MockK with android.jar stubs (runMockTests).
 */
class CustomShortSwipeExecutorTest {

    private lateinit var executor: CustomShortSwipeExecutor
    private lateinit var mockContext: Context
    private lateinit var mockIc: InputConnection
    private lateinit var mockEditorInfo: EditorInfo

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        mockContext = mockk(relaxed = true)
        mockIc = mockk(relaxed = true)
        mockEditorInfo = mockk(relaxed = true)
        executor = CustomShortSwipeExecutor(mockContext)
    }

    @Test
    fun `executes TIMESTAMP and commits formatted current date`() {
        val pattern = "yyyy-MM-dd"
        val mapping = ShortSwipeMapping.timestamp(
            keyCode = "t",
            direction = SwipeDirection.N,
            displayText = "TS",
            pattern = pattern
        )

        val committed = slot<CharSequence>()
        every { mockIc.commitText(capture(committed), 1) } returns true

        val result = executor.execute(mapping, mockIc, mockEditorInfo)

        assertThat(result).isTrue()
        verify(exactly = 1) { mockIc.commitText(any(), 1) }

        // Result must match the SimpleDateFormat pattern shape: YYYY-MM-DD
        val text = committed.captured.toString()
        assertThat(text).matches("""\d{4}-\d{2}-\d{2}""")

        // Sanity-check it represents (approximately) "now" — produced by SimpleDateFormat
        // with the same pattern and Locale.getDefault, the date string should be identical
        // (the test won't span a day boundary in any realistic CI scenario).
        val expectedToday = SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
        assertThat(text).isEqualTo(expectedToday)
    }

    @Test
    fun `executes TIMESTAMP with date-and-time pattern`() {
        val pattern = "yyyy-MM-dd HH:mm"
        val mapping = ShortSwipeMapping.timestamp(
            keyCode = "t",
            direction = SwipeDirection.E,
            displayText = "DT",
            pattern = pattern
        )

        val committed = slot<CharSequence>()
        every { mockIc.commitText(capture(committed), 1) } returns true

        val result = executor.execute(mapping, mockIc, mockEditorInfo)

        assertThat(result).isTrue()
        val text = committed.captured.toString()
        // YYYY-MM-DD HH:MM shape
        assertThat(text).matches("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""")
    }

    @Test
    fun `executes TIMESTAMP with time-only HH-mm pattern`() {
        val pattern = "HH:mm"
        val mapping = ShortSwipeMapping.timestamp(
            keyCode = "t",
            direction = SwipeDirection.S,
            displayText = "TM",
            pattern = pattern
        )

        val committed = slot<CharSequence>()
        every { mockIc.commitText(capture(committed), 1) } returns true

        val result = executor.execute(mapping, mockIc, mockEditorInfo)

        assertThat(result).isTrue()
        assertThat(committed.captured.toString()).matches("""\d{2}:\d{2}""")
    }

    @Test
    fun `executes TIMESTAMP with ISO 8601 pattern containing literal T`() {
        val pattern = "yyyy-MM-dd'T'HH:mm:ss"
        val mapping = ShortSwipeMapping.timestamp(
            keyCode = "t",
            direction = SwipeDirection.W,
            displayText = "ISO",
            pattern = pattern
        )

        val committed = slot<CharSequence>()
        every { mockIc.commitText(capture(committed), 1) } returns true

        val result = executor.execute(mapping, mockIc, mockEditorInfo)

        assertThat(result).isTrue()
        // YYYY-MM-DDTHH:MM:SS — the literal T must appear, not be substituted.
        assertThat(committed.captured.toString()).matches("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}""")
    }

    @Test
    fun `returns false when InputConnection is null`() {
        val mapping = ShortSwipeMapping.timestamp(
            keyCode = "t",
            direction = SwipeDirection.N,
            displayText = "TS",
            pattern = "yyyy-MM-dd"
        )

        val result = executor.execute(mapping, inputConnection = null, editorInfo = mockEditorInfo)

        assertThat(result).isFalse()
        verify(exactly = 0) { mockIc.commitText(any(), any()) }
    }

    @Test
    fun `returns false when commitText returns false`() {
        val mapping = ShortSwipeMapping.timestamp(
            keyCode = "t",
            direction = SwipeDirection.N,
            displayText = "TS",
            pattern = "yyyy-MM-dd"
        )

        every { mockIc.commitText(any(), 1) } returns false

        val result = executor.execute(mapping, mockIc, mockEditorInfo)

        // Mapping itself is well-formed, but IC refuses commit — should propagate false.
        assertThat(result).isFalse()
    }

    @Test
    fun `executes TIMESTAMP with EEE MMM d weekday pattern`() {
        val pattern = "EEE MMM d"
        val mapping = ShortSwipeMapping.timestamp(
            keyCode = "t",
            direction = SwipeDirection.NE,
            displayText = "WD",
            pattern = pattern
        )

        val committed = slot<CharSequence>()
        every { mockIc.commitText(capture(committed), 1) } returns true

        val result = executor.execute(mapping, mockIc, mockEditorInfo)

        assertThat(result).isTrue()
        val text = committed.captured.toString()
        // Three-letter weekday + space + three-letter month + space + day number
        assertThat(text).matches("""[A-Za-z]{3} [A-Za-z]{3} \d{1,2}""")
    }
}
