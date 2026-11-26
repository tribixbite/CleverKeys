package tribixbite.cleverkeys

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * The emoji keyword index loads when the emoji pane first opens, not at keyboard start
 * (docs/audit/2026-11-26-memory-analysis.md, candidate 5: 7.66 MiB of trie held by every keyboard
 * process, with no emoji pane ever opened).
 *
 * Pins:
 *  - the service's start-up no longer prewarms it, and the emoji grid (built when the pane opens)
 *    does — so the index is loading by the time the pane's search bar can take a query;
 *  - nothing is loaded before the first prewarm, and the loaded index answers prefix searches
 *    the way it did when it was prewarmed at start;
 *  - one load per process, and the loader reads assets through the APPLICATION context, so the
 *    background load never keeps the pane's view context alive.
 *
 * Mock tier (`Log`, a mocked `Context`/`AssetManager`). [EmojiKeywordIndex] is a process
 * singleton, so each test resets its three fields by reflection first.
 */
class EmojiKeywordIndexLazyLoadTest {

    private val mainKotlin = File("src/main/kotlin/tribixbite/cleverkeys")

    private lateinit var appContext: Context
    private lateinit var paneContext: Context
    private lateinit var assets: AssetManager

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        resetIndex()

        assets = mockk()
        every { assets.open(any()) } answers {
            ByteArrayInputStream("fire\t🔥\nfireworks\t🎆\nfish\t🐟,🐠\npumpkin\t🎃\n".toByteArray())
        }
        appContext = mockk()
        every { appContext.applicationContext } returns appContext
        every { appContext.assets } returns assets
        paneContext = mockk()
        every { paneContext.applicationContext } returns appContext
        every { paneContext.assets } returns assets
    }

    @After
    fun tearDown() {
        resetIndex()
        unmockkAll()
    }

    @Test
    fun `keyboard start does not load the index and the emoji grid does`() {
        val service = File(mainKotlin, "CleverKeysService.kt").readText()
        assertWithMessage("CleverKeysService must not prewarm the emoji keyword index at start")
            .that(service).doesNotContain("EmojiKeywordIndex.prewarm(")
        val grid = File(mainKotlin, "emoji/EmojiGridView.kt").readText()
        val init = grid.substringAfter("    init {").substringBefore("\n    }")
        assertWithMessage("EmojiGridView's init (the pane opening) starts the load")
            .that(init).contains("EmojiKeywordIndex.prewarm(")
    }

    @Test
    fun `nothing is loaded before the first prewarm`() {
        assertWithMessage("ready before any prewarm").that(EmojiKeywordIndex.isReady()).isFalse()
        assertWithMessage("search before any prewarm").that(EmojiKeywordIndex.search("fi")).isEmpty()
        verify(exactly = 0) { assets.open(any()) }
    }

    @Test
    fun `the first prewarm loads the index and search answers prefixes`() {
        EmojiKeywordIndex.prewarm(paneContext)
        awaitLoad()

        assertWithMessage("ready after the load").that(EmojiKeywordIndex.isReady()).isTrue()
        assertWithMessage("prefix fi").that(EmojiKeywordIndex.search("fi"))
            .containsExactly("🔥", "🎆", "🐟", "🐠")
        assertWithMessage("whole keyword").that(EmojiKeywordIndex.search("Pumpkin")).containsExactly("🎃")
        assertWithMessage("no match").that(EmojiKeywordIndex.search("zz")).isEmpty()
    }

    @Test
    fun `a second prewarm does not load again`() {
        EmojiKeywordIndex.prewarm(paneContext)
        awaitLoad()
        EmojiKeywordIndex.prewarm(paneContext)
        awaitLoad()
        verify(exactly = 1) { assets.open(any()) }
    }

    @Test
    fun `the load reads through the application context, never the pane's`() {
        EmojiKeywordIndex.prewarm(paneContext)
        awaitLoad()
        verify(exactly = 0) { paneContext.assets }
        verify(exactly = 1) { appContext.assets }
    }

    private fun awaitLoad() = runBlocking { withTimeout(5_000) { EmojiKeywordIndex.awaitReady() } }

    /** Back to a never-loaded process: no trie, not loaded, no job. */
    private fun resetIndex() {
        (readField("loadJob") as Job?)?.let { runBlocking { it.join() } }
        writeField("rootNode", null)
        writeField("isLoaded", false)
        writeField("loadJob", null)
    }

    private fun readField(name: String): Any? {
        val field = EmojiKeywordIndex::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(EmojiKeywordIndex)
    }

    private fun writeField(name: String, value: Any?) {
        val field = EmojiKeywordIndex::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(EmojiKeywordIndex, value)
    }
}
