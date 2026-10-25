package tribixbite.cleverkeys.clipboard

import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.ClipboardDatabase
import tribixbite.cleverkeys.ClipboardHistoryService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Saga 2026-10-25: copying an image while the clipboard pane was open left the pane on
 * "Loading results…". The media capture runs on `Dispatchers.IO` (`processClipUri` →
 * `addMediaClip`) and called the history-change listener from that thread; the pane's reload
 * touched views off the main thread ("Animators may only be run on Looper threads"), the throw
 * was caught and logged as an unrelated URI failure, and `dataReady` stayed false.
 *
 * The service must deliver listener callbacks on the main thread whatever thread changed the
 * history, and a listener failure must be logged with its stack trace. The main thread is
 * modelled by a named single-thread executor installed as `Dispatchers.Main`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClipboardHistoryListenerThreadTest {

    private val objenesis = ObjenesisStd()
    private lateinit var main: ExecutorService
    private lateinit var database: ClipboardDatabase
    private lateinit var service: ClipboardHistoryService

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        main = Executors.newSingleThreadExecutor { r -> Thread(r, MAIN_THREAD) }
        Dispatchers.setMain(main.asCoroutineDispatcher())

        database = mockk(relaxed = true)
        every { database.setTodoEntryStatus(any(), any()) } returns true
        service = objenesis.newInstance(ClipboardHistoryService::class.java)
        service.setField("_database", database)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        main.shutdownNow()
        unmockkAll()
    }

    /** Change the history from a background thread, as the media capture does; rethrow nothing. */
    private fun changeHistoryFromBackgroundThread(): Throwable? {
        var thrown: Throwable? = null
        thread(name = "io-worker") {
            try {
                service.setTodoStatus("entry", "completed")
            } catch (t: Throwable) {
                thrown = t
            }
        }.join()
        // Drain the main queue: anything posted by the change has run once this returns.
        main.submit {}.get(5, TimeUnit.SECONDS)
        return thrown
    }

    @Test
    fun aBackgroundHistoryChangeReachesTheListenerOnTheMainThread() {
        val deliveredOn = CompletableFuture<String>()
        service.setOnClipboardHistoryChange { deliveredOn.complete(Thread.currentThread().name) }
        assertNull(changeHistoryFromBackgroundThread())
        assertEquals(MAIN_THREAD, deliveredOn.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun aListenerFailureIsLoggedWithItsStackTraceNotThrownIntoTheCaller() {
        val failure = IllegalStateException("Animators may only be run on Looper threads")
        service.setOnClipboardHistoryChange { throw failure }
        assertNull("the history change itself succeeded", changeHistoryFromBackgroundThread())
        verify { Log.e(any(), any<String>(), failure) }
    }

    private fun Any.setField(name: String, value: Any?) {
        var cls: Class<*>? = javaClass
        while (cls != null) {
            val field = cls.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                field.set(this, value)
                return
            }
            cls = cls.superclass
        }
        throw AssertionError("field '$name' not found on ${javaClass.name}")
    }

    private companion object {
        const val MAIN_THREAD = "fake-main"
    }
}
