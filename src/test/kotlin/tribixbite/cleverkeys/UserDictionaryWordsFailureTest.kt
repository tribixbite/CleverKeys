package tribixbite.cleverkeys

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.util.Log
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Review 2026-11-14 (Stores, low): a failed user-dictionary provider read must not empty the
 * personal swipe words until the next provider change.
 *
 * [UserDictionaryWords.read] swallowed every exception as an empty list, and the snapshot cache
 * stored that list under the current epoch: one transient failure (a provider restart, a binder
 * timeout) left every swipe decode without the user's words until something edited the user
 * dictionary. A failure is now reported as such, served as empty once and never cached, so the
 * next lexicon build reads the provider again.
 *
 * Mock tier: `ContentResolver`, `Cursor` and `Log` are mocked over the android.jar stubs.
 */
class UserDictionaryWordsFailureTest {

    private val resolver = mockk<ContentResolver>()
    private val context = mockk<Context>()
    private var queries = 0

    private fun cursorOf(vararg rows: Pair<String, Int>): Cursor {
        val cursor = mockk<Cursor>(relaxed = true)
        var at = -1
        every { cursor.getColumnIndex(any()) } answers {
            when (firstArg<String>()) {
                "word" -> 0
                "frequency" -> 1
                else -> -1
            }
        }
        every { cursor.moveToNext() } answers { ++at < rows.size }
        every { cursor.getString(0) } answers { rows[at].first }
        every { cursor.getInt(1) } answers { rows[at].second }
        return cursor
    }

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { context.contentResolver } returns resolver
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            queries++
            if (queries == 1) throw SecurityException("provider restarting") else cursorOf("kubernetes" to 40)
        }
        UserDictionaryWords.onObserverStarted()
    }

    @After
    fun tearDown() {
        UserDictionaryWords.onObserverStopped()
        unmockkAll()
    }

    @Test
    fun `a failed read is not cached as an empty dictionary`() {
        val first = UserDictionaryWords.snapshot(context, "en")
        assertWithMessage("the failed read serves nothing").that(first.entries).isEmpty()
        val second = UserDictionaryWords.snapshot(context, "en")
        assertWithMessage("the next build reads the provider again and gets the user's words")
            .that(second.entries).containsExactly("kubernetes" to 40)
        assertWithMessage("provider queries").that(queries).isEqualTo(2)
    }
}
